package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ParallelNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.WaitNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trace.NodeTraceRecorder;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * wait 节点的<b>运行时挂起/恢复</b>单测（P2 增量4a，0 模型 token）
 *
 * <p>与 {@link DslToStateGraphCompilerWaitTest}（纯编译、断形状）互补：本类用<b>真编译器</b>建图 +
 * <b>真执行</b>（{@code MemorySaver} + {@code compiled.stream(...)}），证明 wait 走的正是 hitl 已验证的
 * interruptBefore → checkpoint 挂起 → updateState + withResume 续跑 那条链：
 * <ol>
 *   <li>① <b>挂起</b>：{@code START→w(wait,duration=3)→capture→END}，stream 正常结束，末元素是
 *       {@link InterruptionMetadata} 且 {@code node()=="w"}；下游 capture 桩<b>未执行</b>（captured 为空）
 *       —— 证 interruptBefore 在 w 之前挂起、下游没跑。</li>
 *   <li>② <b>恢复</b>：对同一 threadId {@code updateState(config,{})} → {@code stream(null, updated.withResume())}，
 *       capture 桩执行、w 执行器写 {@code w_waited=true}、流正常结束（无 InterruptionMetadata）
 *       —— 证 resume 越过 w 续跑到 capture。</li>
 *   <li>③ <b>增量4b event-wait</b>：{@code wait(event)} 同机制挂起（末元素 InterruptionMetadata node=="w"）；
 *       用「空 input」启动、resume 时 {@code updateState(config,{theId:6})} 模拟事件 payload，
 *       恢复后 capture 读到 {@code theId==6} —— 证 payload 经 {@code variables[]} 声明的键穿过 resume 到达下游。</li>
 * </ol>
 *
 * <p>🔴 这里只验「挂起/恢复」这条 SAA 原语链（wait 与 hitl 共用），<b>不</b>验 resume_at 的计算/落库 ——
 * 那是 Service 层职责（interruptBefore 时 wait 未执行，duration 只能由 Service 从挂起节点 id 回查 DSL），
 * 见 {@code AgentWorkflowExecutionServiceImpl} 的 resolveWaitResumeAt。
 *
 * <p>🔴 <b>不要从本类的 {@code node()=="w"} 推广出「{@code InterruptionMetadata.node()} 就是挂起节点」</b>。
 * 本类（以及 {@code HitlConfirmResumeRuntimeTest} / {@code HitlBpmApprovalResumeRuntimeTest}）的 DSL
 * 都是 {@code START → 挂起节点}，而那是 node() <b>唯一碰巧正确</b>的形状：挂起节点前面只要还有一个
 * 跑完了的节点，node() 就指到那个<b>前一个</b>节点上去。Service 曾经就是照这个前提取 id 的，
 * 于是 wait/bpm_approval 的唤醒凭据全都落不了库。实测四种形状与修法见 {@code SuspendNodeIdRuntimeTest}。
 *
 * <p>🔴 实测约束（对 hitl/wait 一切 resume 都成立）：恢复时 SAA 走 {@code OverAllState.input(checkpointState)}
 * 重载状态，而 {@code input()} <b>只保留在 KeyStrategyFactory 里注册过的键</b>（即 DSL {@code variables[]} 声明的键，
 * defaultStrategy=REPLACE 不会给未声明键补注册）。故 wait 下游要读的「挂起前变量」<b>必须在 variables[] 里声明</b>，
 * 否则 resume 后会丢。本测试的 seed/done 已声明，与生产 e2e（theId/structOut 声明在 variables[]）一致。
 *
 * @author 走进新时代
 */
class WaitNodeRuntimeTest {

    private DslToStateGraphCompiler compiler;
    private DslParser parser;
    private CaptureExecutor captureExecutor;

    /** 桩：记录自己被执行过（execute 调用次数）与读到的键，写 outputVar */
    private static class CaptureExecutor implements NodeExecutor {
        final Map<String, Object> captured = new LinkedHashMap<>();
        final AtomicInteger runs = new AtomicInteger(0);
        Object writeValue = "captured";

        @Override
        public String getType() {
            return "capture";
        }

        @Override
        public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
            runs.incrementAndGet();
            for (Object k : node.configList("captureKeys")) {
                captured.put(String.valueOf(k), ctx.get(String.valueOf(k)));
            }
            Map<String, Object> delta = new LinkedHashMap<>();
            String outputVar = node.configStr("outputVar");
            if (outputVar != null && !outputVar.isEmpty()) {
                delta.put(outputVar, writeValue);
            }
            return delta;
        }
    }

    private static class StubExecutor implements NodeExecutor {
        private final String type;

        StubExecutor(String type) {
            this.type = type;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
            return Map.of();
        }
    }

    @BeforeEach
    void setUp() {
        parser = new DslParser();
        ReflectionTestUtils.setField(parser, "objectMapper", new ObjectMapper());

        captureExecutor = new CaptureExecutor();
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        registry.setExecutors(List.of(
                new ParallelNodeExecutor(),
                new WaitNodeExecutor(),
                captureExecutor,
                new StubExecutor("trigger.chat"),
                new StubExecutor("llm"),
                new StubExecutor("tool")));

        compiler = new DslToStateGraphCompiler();
        ReflectionTestUtils.setField(compiler, "registry", registry);
        ReflectionTestUtils.setField(compiler, "traceRecorder", mock(NodeTraceRecorder.class));
        ReflectionTestUtils.setField(compiler, "defService", mock(AgentWorkflowDefService.class));
        ReflectionTestUtils.setField(compiler, "dslParser", parser);
    }

    private static final String WAIT_DSL = """
            {"dslVersion":"1.0","id":"wf_wait_rt","name":"wait 运行时",
             "variables":[
               {"key":"seed","type":"string","merge":"replace"},
               {"key":"done","type":"string","merge":"replace"}
             ],
             "nodes":[
               {"id":"w","type":"wait","config":{"waitType":"duration","duration":3}},
               {"id":"cap","type":"capture","config":{"captureKeys":["seed"],"outputVar":"done"}}
             ],
             "edges":[
               {"from":"START","to":"w"},
               {"from":"w","to":"cap"},
               {"from":"cap","to":"END"}
             ]}
            """;

    private CompiledGraph compileWait() throws Exception {
        WorkflowDefDTO def = parser.parse(WAIT_DSL);
        return compiler.compile(def, 1L, "t1:1",
                AgentExecutionContext.ofForTest(1L, 1L),
                new MemorySaver(), ObservationRegistry.NOOP);
    }

    // ==================== ① 挂起：末元素 InterruptionMetadata(node=w)，下游未跑 ====================

    @Test
    void stream_waitInterruptBefore_suspendsBeforeNode_downstreamNotRun() throws Exception {
        CompiledGraph graph = compileWait();
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();

        List<NodeOutput> outputs = graph.stream(Map.of("seed", "x"), config).collectList().block();
        assertNotNull(outputs, "挂起时 stream 应正常结束（返回已产出的元素），而非抛错");
        assertFalse(outputs.isEmpty(), "至少应有中断元数据元素");

        NodeOutput last = outputs.get(outputs.size() - 1);
        assertTrue(last instanceof InterruptionMetadata,
                "末元素应是 InterruptionMetadata（interruptBefore 挂起），实际：" + last.getClass());
        assertEquals("w", ((InterruptionMetadata) last).node(),
                // 🔴 这里对，是因为 w 正好是 START 之后的第一个节点 —— 别推广，见类 javadoc 末段
                "中断节点应是 wait 节点 w（本形状下 node() 碰巧等于挂起节点；Service 回查 duration 用的是"
                        + " checkpoint 快照的 next()，不是它），实际："
                        + ((InterruptionMetadata) last).node());

        // 下游 capture 桩未执行 —— 证挂起发生在 w 之前，w 与其后节点都没跑
        assertEquals(0, captureExecutor.runs.get(),
                "挂起时下游 capture 不应执行，实际执行次数：" + captureExecutor.runs.get());
    }

    // ==================== ② 恢复：updateState + withResume 越过 w 续跑到 capture ====================

    @Test
    void resume_afterWait_continuesPastWaitToDownstream() throws Exception {
        CompiledGraph graph = compileWait();
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();

        // 先跑到挂起
        List<NodeOutput> first = graph.stream(Map.of("seed", "x"), config).collectList().block();
        assertNotNull(first);
        assertTrue(first.get(first.size() - 1) instanceof InterruptionMetadata, "首轮应挂起");
        assertEquals(0, captureExecutor.runs.get(), "挂起时下游未跑");

        // 恢复：把（空）反馈写进 checkpoint，再从断点续跑
        RunnableConfig updated = graph.updateState(config, Map.of());
        List<NodeOutput> resumed = graph.stream(null, updated.withResume()).collectList().block();
        assertNotNull(resumed, "恢复流应正常结束");

        // 下游 capture 执行了
        assertEquals(1, captureExecutor.runs.get(),
                "恢复后应越过 w 跑到 capture，实际执行次数：" + captureExecutor.runs.get());
        // w 执行器（WaitNodeExecutor）写了标记键 w_waited=true
        Object waited = resumed.get(resumed.size() - 1).state().value("w_waited").orElse(null);
        assertEquals(Boolean.TRUE, waited, "恢复后 w 执行器应写 w_waited=true，实际：" + waited);
        // capture 读到了挂起前写入的 seed（checkpoint 状态延续），并写了 done
        assertEquals("x", captureExecutor.captured.get("seed"), "恢复后 capture 应读到 checkpoint 里的 seed");
        Object done = resumed.get(resumed.size() - 1).state().value("done").orElse(null);
        assertEquals("captured", done, "恢复后 capture 应写 done，实际：" + done);
        // 恢复流里不再有中断元数据（已越过唯一的 wait）
        boolean stillInterrupted = resumed.stream().anyMatch(o -> o instanceof InterruptionMetadata);
        assertFalse(stillInterrupted, "恢复后不应再次挂起（只有一个 wait 节点）");
    }

    // ==================== ③ 增量4b：event-wait 挂起 + payload 经声明变量活得过 resume ====================

    private static final String EVENT_WAIT_DSL = """
            {"dslVersion":"1.0","id":"wf_wait_evt_rt","name":"wait(event) 运行时",
             "variables":[
               {"key":"theId","type":"number","merge":"replace"},
               {"key":"done","type":"string","merge":"replace"}
             ],
             "nodes":[
               {"id":"w","type":"wait","config":{"waitType":"event","eventKey":"demo_ready"}},
               {"id":"cap","type":"capture","config":{"captureKeys":["theId"],"outputVar":"done"}}
             ],
             "edges":[
               {"from":"START","to":"w"},
               {"from":"w","to":"cap"},
               {"from":"cap","to":"END"}
             ]}
            """;

    private CompiledGraph compileWaitEvent() throws Exception {
        WorkflowDefDTO def = parser.parse(EVENT_WAIT_DSL);
        return compiler.compile(def, 1L, "t1:1",
                AgentExecutionContext.ofForTest(1L, 1L),
                new MemorySaver(), ObservationRegistry.NOOP);
    }

    @Test
    void resume_afterEventWait_payloadVarVisibleDownstream() throws Exception {
        CompiledGraph graph = compileWaitEvent();
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();

        // 🔴 故意用「空 input」启动：theId 不在初始状态里，留给事件 payload 在 resume 时注入
        List<NodeOutput> first = graph.stream(Map.of(), config).collectList().block();
        assertNotNull(first, "event-wait 挂起时 stream 应正常结束");
        NodeOutput last = first.get(first.size() - 1);
        assertTrue(last instanceof InterruptionMetadata,
                "末元素应是 InterruptionMetadata（event-wait 也走 interruptBefore），实际：" + last.getClass());
        assertEquals("w", ((InterruptionMetadata) last).node(), "中断节点应是 event-wait 节点 w");
        assertEquals(0, captureExecutor.runs.get(), "挂起时下游 capture 不应执行");

        // 模拟 POST /event 的 payload：经 updateState 写进 checkpoint。
        // theId 已在 variables[] 声明，故活得过 SAA resume 的 OverAllState.input() 过滤（增量4a 已证的约束）。
        RunnableConfig updated = graph.updateState(config, Map.of("theId", 6));
        List<NodeOutput> resumed = graph.stream(null, updated.withResume()).collectList().block();
        assertNotNull(resumed, "恢复流应正常结束");

        assertEquals(1, captureExecutor.runs.get(), "恢复后应越过 w 跑到 capture");
        Object waited = resumed.get(resumed.size() - 1).state().value("w_waited").orElse(null);
        assertEquals(Boolean.TRUE, waited, "恢复后 w 执行器应写 w_waited=true");
        // 🔴 关键断言：capture 读到了 payload 注入的 theId=6（证 payload 经声明变量穿过 resume 到达下游）
        assertEquals(6, captureExecutor.captured.get("theId"),
                "恢复后 capture 应读到事件 payload 注入的 theId=6，实际：" + captureExecutor.captured.get("theId"));
        Object done = resumed.get(resumed.size() - 1).state().value("done").orElse(null);
        assertEquals("captured", done, "恢复后 capture 应写 done");
        assertFalse(resumed.stream().anyMatch(o -> o instanceof InterruptionMetadata),
                "恢复后不应再次挂起（只有一个 event-wait 节点）");
    }

}
