package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 去风险 spike（P2 增量3 agent_loop 的 go/no-go 闸门）
 *
 * <h3>为什么必须有这个 spike</h3>
 * agent_loop 采用「编译器合成<b>环形</b>子图 + {@code addNode(id, StateGraph)}」（Option A，规格 §4.2 L107）。
 * 增量2b 已经证明「<b>无环</b>子图 + 共享 checkpoint saver」成立，但<b>环形子图</b>是全新路径：
 * SAA 能否编译环、环能否在共享 saver 下正确终止、迭代/递归上限怎么算——字节码看不出来，只能实测。
 * 本 spike 用最小的「计数器自环」把这些问题一次性钉死。
 *
 * <h3>判定</h3>
 * 全绿 → Option A 成立，继续 C1–C8；任一红 → 停，回退 Option B（执行器内部 for 循环，零编译器改动）。
 *
 * <p>纯内存：不碰 DB、不碰模型、不依赖 Spring 上下文，直接手搓 {@link StateGraph}。
 *
 * @author 走进新时代
 */
class AgentLoopSubGraphSpikeTest {

    /** 共享的 KeyStrategyFactory：默认 REPLACE，未声明的内部键（如计数 n）也能写进 state */
    private static KeyStrategyFactory replaceFactory() {
        return new KeyStrategyFactoryBuilder().defaultStrategy(KeyStrategy.REPLACE).build();
    }

    private static CompileConfig configWith(MemorySaver saver, Integer recursionLimit) {
        CompileConfig.Builder b = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build());
        if (recursionLimit != null) {
            b.recursionLimit(recursionLimit);
        }
        return b.build();
    }

    /**
     * 🔴 核心问题：环形子图能否编译 + 在共享 checkpoint saver 下按条件出口正确终止？
     *
     * <p>子图：{@code START→counter}，counter 每次把 n++；条件边读 n：{@code n<3} 回 counter（自环），
     * {@code n>=3} 走 END。父图：{@code START→loop→END}，loop = {@code addNode("loop", childGraph)}。
     * 期望 counter 恰好跑 3 次后整图终止。
     */
    @Test
    void spike_cyclicSubGraph_compilesRunsTerminates() throws Exception {
        AtomicInteger counterRuns = new AtomicInteger(0);
        List<String> seenNodes = new ArrayList<>();

        // ---- 子图（环形）----
        StateGraph child = new StateGraph("loop_child", replaceFactory());
        child.addNode("counter", AsyncNodeAction.node_async(state -> {
            counterRuns.incrementAndGet();
            int n = ((Number) state.value("n").orElse(0)).intValue();
            return Map.of("n", n + 1);
        }));
        child.addEdge(StateGraph.START, "counter");
        child.addConditionalEdges("counter",
                edge_async(state -> {
                    int n = ((Number) state.value("n").orElse(0)).intValue();
                    return n < 3 ? "loop" : "done";
                }),
                Map.of("loop", "counter", "done", StateGraph.END));

        // ---- 父图：把环形子图作为单节点内嵌 ----
        StateGraph parent = new StateGraph("spike_parent", replaceFactory());
        parent.addNode("loop", child);
        parent.addEdge(StateGraph.START, "loop");
        parent.addEdge("loop", StateGraph.END);

        CompiledGraph compiled = parent.compile(configWith(new MemorySaver(), null));
        assertNotNull(compiled, "环形子图应能编译（不抛 GraphStateException）");

        List<NodeOutput> outputs = compiled
                .stream(Map.of("input", "hi"), RunnableConfig.builder().threadId("t1:spike").build())
                .doOnNext(o -> seenNodes.add(o.node()))
                .collectList()
                .block();

        assertNotNull(outputs, "流应正常完成（未挂起、未抛错）");
        // ① 循环恰好 3 次后终止（条件出口生效，不是靠 recursionLimit 兜底）
        assertEquals(3, counterRuns.get(),
                "counter 应恰好跑 3 次（n: 0→1→2→3，n>=3 时走 END）。实际：" + counterRuns.get());
        // ② 最终状态 n==3（子图写回、共享 state 生效）
        Object finalN = outputs.get(outputs.size() - 1).state().value("n").orElse(null);
        assertEquals(3, ((Number) finalN).intValue(), "终止时 n 应为 3");
        // 信息性：打印上浮的节点名，观察子图节点在父流里的命名（是否 loop-counter 命名空间）
        System.out.println("[spike] 父流上浮的节点序列 = " + seenNodes);
    }

    /**
     * 信息性（R2）：读出 SAA 默认 recursionLimit，供 compile() 定显式值时参考。
     */
    @Test
    void spike_defaultRecursionLimit_isReadable() {
        int defaultLimit = configWith(new MemorySaver(), null).recursionLimit();
        System.out.println("[spike] SAA 默认 recursionLimit = " + defaultLimit);
        assertTrue(defaultLimit > 0, "默认 recursionLimit 应为正数，实际：" + defaultLimit);

        int custom = configWith(new MemorySaver(), 50).recursionLimit();
        assertEquals(50, custom, "显式设置的 recursionLimit 应生效");
    }

    /**
     * 🔴 关键安全发现（R2）：永不出口的环 + 显式 recursionLimit(10) → SAA <b>不抛错，静默截断</b>。
     *
     * <p>spike 实测：{@code collectList().block()} 在 0.02s 内正常完成、未抛任何异常，counter 只跑了有限次。
     * 即 <b>recursionLimit 命中时 SAA 是「悄悄停流」而非 fail-close</b>。
     *
     * <h3>这对 agent_loop 的设计意味着什么</h3>
     * <b>绝不能依赖 recursionLimit 来收敛或报错。</b> agent_loop 的循环上界必须由<b>自己的 step 计数器 +
     * 条件出口</b>保证（上一个测试已证其能干净终止），非收敛时的 fail-close 也必须由<b>自己的 reason 节点
     * 主动抛异常</b>（step 耗尽仍吐 tool_calls 时），而不是等 SAA 兜底 —— SAA 只会静默截断，留下半截状态、
     * 不报错，那正是平台头号缺陷「配了不生效且无提示」的翻版。
     *
     * <p>用显式小上限（10）保证测试快速返回、不挂。本测试是<b>观测性</b>的：断言「环被有限拦截、未无限挂起」，
     * 并打印真实失败形态供设计参考。
     */
    @Test
    void spike_neverExitingCycle_isSilentlyTruncatedNotThrown() throws Exception {
        AtomicInteger runs = new AtomicInteger(0);

        StateGraph child = new StateGraph("infinite_child", replaceFactory());
        child.addNode("counter", AsyncNodeAction.node_async(state -> {
            runs.incrementAndGet();
            int n = ((Number) state.value("n").orElse(0)).intValue();
            return Map.of("n", n + 1);
        }));
        child.addEdge(StateGraph.START, "counter");
        // 永远回 counter，无出口
        child.addConditionalEdges("counter",
                edge_async(state -> "loop"),
                Map.of("loop", "counter"));

        StateGraph parent = new StateGraph("spike_infinite_parent", replaceFactory());
        parent.addNode("loop", child);
        parent.addEdge(StateGraph.START, "loop");
        parent.addEdge("loop", StateGraph.END);

        CompiledGraph compiled = parent.compile(configWith(new MemorySaver(), 10));

        // 不 assertThrows：实测证明它不抛。这里观测「是否有限终止」
        List<NodeOutput> outputs = compiled
                .stream(Map.of("input", "hi"), RunnableConfig.builder().threadId("t1:inf").build())
                .collectList()
                .block();
        System.out.println("[spike] 环失控真实形态：静默截断、未抛错。counter 跑了 " + runs.get()
                + " 次，流元素 " + (outputs == null ? 0 : outputs.size()) + " 个（recursionLimit=10）");

        // 环被有限拦截（未无限挂起）——这才是本测试要保证的安全性质；至于是抛错还是静默截断，交给上面观测
        assertTrue(runs.get() > 0 && runs.get() < 1000,
                "recursionLimit 应有限拦截失控环（不无限挂起），实际跑了 " + runs.get() + " 次");
    }

}
