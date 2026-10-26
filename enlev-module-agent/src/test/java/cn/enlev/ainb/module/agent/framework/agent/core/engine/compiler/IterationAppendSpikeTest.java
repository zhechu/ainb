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
 * S1-iteration 去风险 spike（P2 增量3-iteration 的 go/no-go 闸门）
 *
 * <h3>为什么必须有这个 spike</h3>
 * 增量3 的 agent_loop 已实测「SAA <b>环形子图</b>能编译、能按自身条件出口干净终止、写回共享 state、
 * 子节点事件以 {@code <id>-<internal>} 逐轮上浮父 SSE」。iteration 是<b>同一种环形子图</b>，
 * 只是循环体从「合成的 reason/act」换成「真实 DSL body 节点链」，收敛条件从「step≥maxSteps」换成
 * 「index≥items.size」。这些 agent_loop 都已去风险。
 *
 * <p>iteration 的<b>唯一新未知</b>是：{@link KeyStrategy#APPEND} 能否在<b>环形子图</b>里<b>跨轮累积</b>、
 * 且累积结果<b>父图可见</b>。agent_loop 刻意用 REPLACE + 手动 append（read 旧列表→追加→write 全量）绕开了这点，
 * 所以 APPEND-in-cycle 从没被真正验证过。本 spike 用最小的「guard→collect→advance」计数器环把它钉死。
 *
 * <h3>判定</h3>
 * 全绿（尤其 ③ acc==[0,1,2]）→ APPEND-in-cycle 成立，按「append 变量累积」主设计继续 C1–C4。
 * ③ 红（acc 被 REPLACE 覆盖成 [2] / 只留末项 / 累积不进父 / 双份 [0,1,2,0,1,2]）→ <b>回退</b>：
 * 子图 factory 把 append 变量也注册成 REPLACE，由 advance 节点手动 read+append+write-full
 * （agent_loop 已证的稳妥套路）。回退是计划内分支，不是失败。
 *
 * <p>纯内存：不碰 DB、不碰模型、不依赖 Spring 上下文，直接手搓 {@link StateGraph}。
 *
 * @author 走进新时代
 */
class IterationAppendSpikeTest {

    /**
     * 镜像真实设计的 KeyStrategyFactory：默认 REPLACE（兜内部键 index/route/item），
     * append 变量 acc 显式注册成 APPEND（父图与子图用同一套，对齐 {@code KeyStrategyFactoryGenerator.generate(def)}）。
     */
    private static KeyStrategyFactory appendFactory() {
        return new KeyStrategyFactoryBuilder()
                .defaultStrategy(KeyStrategy.REPLACE)
                .addStrategy("acc", KeyStrategy.APPEND)
                .build();
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
     * 🔴 核心问题：APPEND 变量能否在环形子图里跨轮累积、且父图终止时可见正确结果？
     *
     * <p>子图（环形，镜像 iteration 合成结构）：
     * <pre>
     * START → _guard ─(index&lt;3：写 item=index, route=body)─▶ collect ─▶ _advance ─┐
     *           │                                                                 │
     *           └─(index≥3：route=end)─▶ END                       (index++) ─────┘
     * </pre>
     * collect 把 item 写进 APPEND 变量 acc（每轮 append 一项）。父图：{@code START→loop→END}，
     * loop = {@code addNode("loop", childGraph)}。期望循环恰 3 次、acc==[a,b,c]。
     *
     * <p>🔴 已核实：SAA {@code Node.validate} 拒绝任何以 {@code __} 开头的节点 id（仅 {@code __START__}/{@code __END__}
     * 常量例外，错误消息固定为误导性的 "END is not a valid node id!"）。故内部控制节点用<b>单下划线</b>前缀
     * {@code _guard}/{@code _advance}——既合法（非 {@code __}），又与 DSL 节点 id（{@code ^[a-z]...}）天然不冲突。
     * 注意：这是<b>节点 id</b> 的限制；<b>state 键</b>无此限制（agent_loop 的 {@code __agentloop_*} 键已证可行）。
     */
    @Test
    void spike_appendInCyclicSubGraph_accumulatesAndSurfacesInParent() throws Exception {
        AtomicInteger guardRuns = new AtomicInteger(0);
        AtomicInteger collectRuns = new AtomicInteger(0);
        List<String> seenNodes = new ArrayList<>();

        // ---- 子图（环形）----
        StateGraph child = new StateGraph("iter_child", appendFactory());

        // _guard：读 index（默认 0），index<size → 写 item=items[index] + route=body；否则 route=end
        child.addNode("_guard", AsyncNodeAction.node_async(state -> {
            guardRuns.incrementAndGet();
            int index = ((Number) state.value("index").orElse(0)).intValue();
            List<?> items = (List<?>) state.value("items").orElse(List.of());
            if (index < items.size()) {
                return Map.of("index", index, "item", items.get(index), "route", "body");
            }
            return Map.of("route", "end");
        }));

        // collect：把当前 item 写进 APPEND 变量 acc（模拟 body 节点写 append 累积变量）
        child.addNode("collect", AsyncNodeAction.node_async(state -> {
            collectRuns.incrementAndGet();
            Object item = state.value("item").orElse(null);
            return Map.of("acc", item);
        }));

        // _advance：index++，回 _guard（回边）
        child.addNode("_advance", AsyncNodeAction.node_async(state -> {
            int index = ((Number) state.value("index").orElse(0)).intValue();
            return Map.of("index", index + 1);
        }));

        child.addEdge(StateGraph.START, "_guard");
        child.addConditionalEdges("_guard",
                edge_async(state -> (String) state.value("route").orElse("end")),
                Map.of("body", "collect", "end", StateGraph.END));
        child.addEdge("collect", "_advance");
        child.addEdge("_advance", "_guard");

        // ---- 父图：把环形子图作为单节点内嵌 ----
        StateGraph parent = new StateGraph("iter_spike_parent", appendFactory());
        parent.addNode("loop", child);
        parent.addEdge(StateGraph.START, "loop");
        parent.addEdge("loop", StateGraph.END);

        CompiledGraph compiled = parent.compile(configWith(new MemorySaver(), null));
        assertNotNull(compiled, "环形子图应能编译（不抛 GraphStateException）");

        // items=[a,b,c] 由 input 直接播种（对齐 e2e：列表来自 input，无需模型/工具产生）
        List<NodeOutput> outputs = compiled
                .stream(Map.of("items", List.of("a", "b", "c")),
                        RunnableConfig.builder().threadId("t1:iter-spike").build())
                .doOnNext(o -> seenNodes.add(o.node()))
                .collectList()
                .block();

        assertNotNull(outputs, "流应正常完成（未挂起、未抛错）");

        // ① 循环恰好 3 次后终止（条件出口生效，不是靠 recursionLimit 兜底）
        assertEquals(3, collectRuns.get(),
                "collect（body）应恰好跑 3 次（items 有 3 项）。实际：" + collectRuns.get());
        // guard 比 collect 多跑一次（末轮判 index>=size 走 end）
        assertEquals(4, guardRuns.get(),
                "guard 应跑 4 次（3 轮 body + 1 轮判出口）。实际：" + guardRuns.get());

        // ②③ 🔴 核心判据：父图终止时 acc==[a,b,c]（APPEND 跨轮累积成功且父可见，非被 REPLACE 覆盖成 [c]，
        //     也非双份 [a,b,c,a,b,c]）
        Object finalAcc = outputs.get(outputs.size() - 1).state().value("acc").orElse(null);
        assertNotNull(finalAcc, "终止时 acc 应存在（子图 append 累积写回父 state）");
        assertTrue(finalAcc instanceof List, "acc 应为 List，实际类型：" + finalAcc.getClass());
        List<?> accList = (List<?>) finalAcc;
        assertEquals(List.of("a", "b", "c"), accList,
                "🔴 APPEND-in-cycle 判据：acc 应恰为 [a,b,c]（跨 3 轮累积、不重不漏）。实际：" + accList);

        // ④ 信息性：打印上浮的节点名，观察子图节点在父流里的命名（是否 loop-collect 命名空间、逐轮 3 次）
        System.out.println("[iter-spike] 父流上浮的节点序列 = " + seenNodes);
        long collectSurfaced = seenNodes.stream().filter(n -> n != null && n.contains("collect")).count();
        System.out.println("[iter-spike] collect 上浮次数 = " + collectSurfaced + "（期望逐轮 3 次）");
    }

}
