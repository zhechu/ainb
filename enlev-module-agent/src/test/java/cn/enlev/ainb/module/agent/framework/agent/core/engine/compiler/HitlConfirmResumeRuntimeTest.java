package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlConfirmNodeExecutor;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * hitl.confirm 恢复的<b>反馈键落点</b>运行时单测（真编译器 + 真挂起/恢复，0 模型 token）
 *
 * <h3>钉住的缺陷：自定义 approveVar 的流程，聊天里点「同意」会被当成「拒绝」</h3>
 * 聊天内恢复（{@code AgentAssistantServiceImpl.resumeWorkflowInChat}）只认一个写死的键
 * {@code hitl_approved}；而编排器生成的流程常把闸口配成 {@code config.approveVar: confirm_result}。
 * 两处对不上时，节点读到 null → 判成拒绝 → 流程静默走 default 分支，执行状态还是 SUCCEEDED。
 * 用户看到的就是「我明明点了同意，表单却没建出来」。
 *
 * <p>修复是两半，缺一不可，本测试同时钉住：
 * <ol>
 *   <li>{@code KeyStrategyFactoryGenerator} 无条件注册 {@code hitl_approved}：SAA resume 走
 *       {@code OverAllState.input()} 重载状态，只保留注册过 KeyStrategy 的键，
 *       DSL 没声明 {@code hitl_approved} 时它会在恢复瞬间被丢掉（见 {@code WaitNodeRuntimeTest} 同款实测约束）。</li>
 *   <li>{@code HitlConfirmNodeExecutor} 读不到自己的 approveVar 时回退读 {@code hitl_approved}，
 *       并把归一化后的布尔<b>写回 approveVar</b> —— 下游 condition.rule 因此对任何 approveVar 都判得对。</li>
 * </ol>
 *
 * @author 走进新时代
 */
class HitlConfirmResumeRuntimeTest {

    private DslToStateGraphCompiler compiler;
    private DslParser parser;
    private CaptureExecutor captureExecutor;

    /** 桩：记录恢复后自己读到的键（等价于下游 condition 节点看到的变量），并写 outputVar */
    private static class CaptureExecutor implements NodeExecutor {
        final Map<String, Object> captured = new LinkedHashMap<>();
        final AtomicInteger runs = new AtomicInteger(0);

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
                delta.put(outputVar, "captured");
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
                new HitlConfirmNodeExecutor(),
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

    private CompiledGraph compile(String dsl) throws Exception {
        WorkflowDefDTO def = parser.parse(dsl);
        return compiler.compile(def, 1L, "t1:1",
                AgentExecutionContext.ofForTest(1L, 1L),
                new MemorySaver(), ObservationRegistry.NOOP);
    }

    /** 跑到挂起，断言末元素是闸口节点的中断元数据、下游确实没跑 */
    private void runUntilSuspended(CompiledGraph graph, RunnableConfig config, String gateId) {
        List<NodeOutput> first = graph.stream(Map.of(), config).collectList().block();
        assertNotNull(first, "挂起时 stream 应正常结束，而非抛错");
        NodeOutput last = first.get(first.size() - 1);
        assertTrue(last instanceof InterruptionMetadata,
                "末元素应是 InterruptionMetadata（interruptBefore 在闸口之前挂起），实际：" + last.getClass());
        assertEquals(gateId, ((InterruptionMetadata) last).node(), "中断节点应是 hitl 闸口");
        assertEquals(0, captureExecutor.runs.get(), "挂起时下游 capture 不应执行");
    }

    // ========== ① 自定义 approveVar + 聊天固定键：同意必须真的是「同意」 ==========

    /**
     * 复刻「设备管理表单创建流程」的形状：闸口配 {@code approveVar: confirm_result}，
     * variables[] 只声明 confirm_result（<b>没有</b> hitl_approved），下游 condition 靠 is_true 分流。
     */
    private static final String CUSTOM_APPROVE_VAR_DSL = """
            {"dslVersion":"1.0","id":"wf_hitl_custom","name":"自定义 approveVar",
             "variables":[
               {"key":"confirm_result","type":"boolean","merge":"replace"},
               {"key":"done","type":"string","merge":"replace"}
             ],
             "nodes":[
               {"id":"gate","type":"hitl.confirm","config":{"approveVar":"confirm_result"}},
               {"id":"cap","type":"capture",
                "config":{"captureKeys":["confirm_result","hitl_approved"],"outputVar":"done"}}
             ],
             "edges":[
               {"from":"START","to":"gate"},
               {"from":"gate","to":"cap"},
               {"from":"cap","to":"END"}
             ]}
            """;

    @Test
    void resume_customApproveVar_chatFixedKeyTrue_treatedAsApproved() throws Exception {
        CompiledGraph graph = compile(CUSTOM_APPROVE_VAR_DSL);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();
        runUntilSuspended(graph, config, "gate");

        // 聊天内恢复喂的就是这一个写死的键（AgentAssistantServiceImpl.HITL_APPROVE_VAR）
        RunnableConfig updated = graph.updateState(config, Map.of(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR, true));
        List<NodeOutput> resumed = graph.stream(null, updated.withResume()).collectList().block();
        assertNotNull(resumed, "恢复流应正常结束");
        assertEquals(1, captureExecutor.runs.get(), "恢复后应越过闸口跑到下游 capture");

        // 🔴 关键：闸口把决定归一化写回自己的 approveVar，下游 condition.rule(is_true) 因此判得出「同意」
        assertEquals(Boolean.TRUE, captureExecutor.captured.get("confirm_result"),
                "下游应读到 confirm_result=true（聊天固定键被回退采纳并写回 approveVar），实际："
                        + captureExecutor.captured.get("confirm_result"));
        assertEquals(Boolean.TRUE,
                resumed.get(resumed.size() - 1).state().value("confirm_result").orElse(null),
                "最终状态里 confirm_result 应为 true");
        assertEquals("approved", resumed.get(resumed.size() - 1).state().value("gate_confirmed").orElse(null),
                "闸口应留下 approved 标记，实际：" + resumed.get(resumed.size() - 1).state().value("gate_confirmed"));
    }

    @Test
    void resume_customApproveVar_chatFixedKeyFalse_treatedAsRejected() throws Exception {
        CompiledGraph graph = compile(CUSTOM_APPROVE_VAR_DSL);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();
        runUntilSuspended(graph, config, "gate");

        RunnableConfig updated = graph.updateState(config,
                Map.of(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR, false));
        List<NodeOutput> resumed = graph.stream(null, updated.withResume()).collectList().block();
        assertNotNull(resumed, "恢复流应正常结束");

        assertEquals(Boolean.FALSE, captureExecutor.captured.get("confirm_result"),
                "拒绝时下游应读到 confirm_result=false（走 default 分支），实际："
                        + captureExecutor.captured.get("confirm_result"));
        assertEquals("rejected", resumed.get(resumed.size() - 1).state().value("gate_confirmed").orElse(null),
                "闸口应留下 rejected 标记");
    }

    // ========== ② 默认 approveVar 的既有行为不回退（工作台显式 feedback 优先级更高） ==========

    private static final String DEFAULT_APPROVE_VAR_DSL = """
            {"dslVersion":"1.0","id":"wf_hitl_default","name":"默认 approveVar",
             "variables":[
               {"key":"hitl_approved","type":"boolean","merge":"replace"},
               {"key":"done","type":"string","merge":"replace"}
             ],
             "nodes":[
               {"id":"gate","type":"hitl.confirm","config":{}},
               {"id":"cap","type":"capture",
                "config":{"captureKeys":["hitl_approved"],"outputVar":"done"}}
             ],
             "edges":[
               {"from":"START","to":"gate"},
               {"from":"gate","to":"cap"},
               {"from":"cap","to":"END"}
             ]}
            """;

    @Test
    void resume_defaultApproveVar_stillHonoursChatFixedKey() throws Exception {
        CompiledGraph graph = compile(DEFAULT_APPROVE_VAR_DSL);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();
        runUntilSuspended(graph, config, "gate");

        RunnableConfig updated = graph.updateState(config, Map.of(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR, true));
        List<NodeOutput> resumed = graph.stream(null, updated.withResume()).collectList().block();
        assertNotNull(resumed, "恢复流应正常结束");

        assertEquals(Boolean.TRUE, captureExecutor.captured.get("hitl_approved"),
                "默认 approveVar 流程应照旧读到同意，实际：" + captureExecutor.captured.get("hitl_approved"));
        assertEquals("approved", resumed.get(resumed.size() - 1).state().value("gate_confirmed").orElse(null),
                "闸口应留下 approved 标记");
    }

    /**
     * 工作台 {@code /agent/workflow/resume-stream} 显式按 approveVar 传 feedback 时，
     * 自己的键优先于聊天固定键（回退只在读不到时生效），既有调用方零影响。
     */
    @Test
    void resume_explicitApproveVarFeedback_winsOverChatFixedKey() throws Exception {
        CompiledGraph graph = compile(CUSTOM_APPROVE_VAR_DSL);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();
        runUntilSuspended(graph, config, "gate");

        RunnableConfig updated = graph.updateState(config, Map.of(
                "confirm_result", true,
                HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR, false));
        List<NodeOutput> resumed = graph.stream(null, updated.withResume()).collectList().block();
        assertNotNull(resumed, "恢复流应正常结束");

        assertEquals(Boolean.TRUE, captureExecutor.captured.get("confirm_result"),
                "显式 approveVar 反馈应压过聊天固定键（此处故意让两者相反），实际："
                        + captureExecutor.captured.get("confirm_result"));
        assertEquals("approved", resumed.get(resumed.size() - 1).state().value("gate_confirmed").orElse(null),
                "闸口应留下 approved 标记");
    }

}
