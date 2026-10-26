package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlBpmApprovalNodeExecutor;
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
import org.junit.jupiter.api.DisplayName;
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
 * hitl.bpm_approval 恢复的<b>审批结果落点</b>运行时单测（真编译器 + 真挂起/恢复，0 模型 token）
 *
 * <h3>钉住的缺陷：BPM 审批「通过」了，工作流却按「驳回」走完，状态还报 SUCCEEDED</h3>
 * 两半原因叠在一起，缺任一半都不会错，所以必须端到端钉：
 * <ol>
 *   <li><b>键名对不上</b>：{@code AgentBpmApprovalListener} 跑在审批人线程上、手里只有 businessKey，
 *       拿不到挂起节点的 config，payload 恒为默认名 {@code bpm_approved}/{@code bpm_status}；
 *       而节点配了 {@code approveVar: my_approved} 时执行器只读自己的名字 → null → 判成驳回。</li>
 *   <li><b>键根本没进 state</b>：SAA resume 走 {@code OverAllState.input(checkpointState)} 重载状态，
 *       只保留<b>注册过 KeyStrategy</b> 的键（{@code WaitNodeRuntimeTest} 已实测），defaultStrategy
 *       不给未声明键补注册。DSL 几乎不会在 variables[] 里声明这两个回调键 ⟹ 即便键名对得上也会被丢掉。
 *       这一半对<b>所有</b> bpm_approval 流程都成立，不止自定义了变量名的那些。</li>
 * </ol>
 *
 * <p>与 {@code HitlConfirmResumeRuntimeTest} 完全对称（那条钉 {@code hitl_approved}），
 * 修复也是同款两半：{@code KeyStrategyFactoryGenerator} 无条件注册两个回调键 +
 * {@code HitlBpmApprovalNodeExecutor} 读不到自定义键时回退默认键。
 *
 * @author 走进新时代
 */
@DisplayName("agent hitl.bpm_approval 恢复：审批结果必须真的落到下游")
class HitlBpmApprovalResumeRuntimeTest {

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
                new HitlBpmApprovalNodeExecutor(),
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

    /** 跑到挂起，断言末元素是审批闸口节点的中断元数据、下游确实没跑 */
    private void runUntilSuspended(CompiledGraph graph, RunnableConfig config, String gateId) {
        List<NodeOutput> first = graph.stream(Map.of(), config).collectList().block();
        assertNotNull(first, "挂起时 stream 应正常结束，而非抛错");
        NodeOutput last = first.get(first.size() - 1);
        assertTrue(last instanceof InterruptionMetadata,
                "末元素应是 InterruptionMetadata（interruptBefore 在审批闸口之前挂起），实际：" + last.getClass());
        assertEquals(gateId, ((InterruptionMetadata) last).node(), "中断节点应是 bpm_approval 闸口");
        assertEquals(0, captureExecutor.runs.get(), "挂起时下游 capture 不应执行");
    }

    /**
     * 按 {@code AgentBpmApprovalListener.onApplicationEvent} 的真实 payload 恢复。
     *
     * <p>🔴 刻意用监听器的常量而不是字面串：这两个键名就是契约本身，
     * 监听器改了名而执行器/注册没跟上，本测试必须红。
     */
    private List<NodeOutput> resumeAsListener(CompiledGraph graph, RunnableConfig config,
                                              boolean approved, int status) throws Exception {
        RunnableConfig updated = graph.updateState(config, Map.of(
                HitlBpmApprovalNodeExecutor.DEFAULT_APPROVE_VAR, approved,
                HitlBpmApprovalNodeExecutor.DEFAULT_STATUS_VAR, status));
        List<NodeOutput> resumed = graph.stream(null, updated.withResume()).collectList().block();
        assertNotNull(resumed, "恢复流应正常结束");
        return resumed;
    }

    private static Object last(List<NodeOutput> outs, String key) {
        return outs.get(outs.size() - 1).state().value(key).orElse(null);
    }

    // ========== ① 自定义 approveVar/statusVar + 监听器默认键：通过必须真的是「通过」 ==========

    /**
     * variables[] 只声明自定义名，<b>没有</b> bpm_approved/bpm_status —— 这才是编排器/AI 造出来的真实形状
     * （回调键是引擎内部约定，作者不会去声明它）。两半缺陷在这份 DSL 上同时成立。
     */
    private static final String CUSTOM_VAR_DSL = """
            {"dslVersion":"1.0","id":"wf_bpm_custom","name":"自定义 approveVar 的 BPM 审批",
             "variables":[
               {"key":"my_approved","type":"boolean","merge":"replace"},
               {"key":"my_status","type":"number","merge":"replace"},
               {"key":"done","type":"string","merge":"replace"}
             ],
             "nodes":[
               {"id":"gate","type":"hitl.bpm_approval",
                "config":{"bpmProcessDefKey":"oa_leave","approveVar":"my_approved","statusVar":"my_status"}},
               {"id":"cap","type":"capture",
                "config":{"captureKeys":["my_approved","my_status"],"outputVar":"done"}}
             ],
             "edges":[
               {"from":"START","to":"gate"},
               {"from":"gate","to":"cap"},
               {"from":"cap","to":"END"}
             ]}
            """;

    @Test
    @DisplayName("🔴 自定义变量名 + 审批通过(2) → 下游读到 my_approved=true、gate_result=approved")
    void resume_customVars_approve_treatedAsApproved() throws Exception {
        CompiledGraph graph = compile(CUSTOM_VAR_DSL);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();
        runUntilSuspended(graph, config, "gate");

        List<NodeOutput> resumed = resumeAsListener(graph, config, true, 2);

        assertEquals(1, captureExecutor.runs.get(), "恢复后应越过闸口跑到下游 capture");
        // 🔴 关键：闸口把监听器的默认键归一化写回自己的 approveVar，下游 condition.rule 因此判得出「通过」
        assertEquals(Boolean.TRUE, captureExecutor.captured.get("my_approved"),
                "下游应读到 my_approved=true（监听器默认键被回退采纳并写回 approveVar），实际："
                        + captureExecutor.captured.get("my_approved"));
        assertEquals(2, captureExecutor.captured.get("my_status"),
                "状态码同样要回退读到，否则取消(4) 与驳回(3) 分不开，实际："
                        + captureExecutor.captured.get("my_status"));
        assertEquals("approved", last(resumed, "gate_result"), "闸口应留下 approved 标记");
        assertEquals(Boolean.TRUE, last(resumed, "my_approved"), "最终状态里 my_approved 应为 true");
    }

    @Test
    @DisplayName("自定义变量名 + 审批驳回(3) → 下游读到 my_approved=false、gate_result=rejected")
    void resume_customVars_reject_treatedAsRejected() throws Exception {
        CompiledGraph graph = compile(CUSTOM_VAR_DSL);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();
        runUntilSuspended(graph, config, "gate");

        List<NodeOutput> resumed = resumeAsListener(graph, config, false, 3);

        assertEquals(Boolean.FALSE, captureExecutor.captured.get("my_approved"),
                "驳回时下游应读到 my_approved=false（走 default 分支）");
        assertEquals("rejected", last(resumed, "gate_result"), "闸口应留下 rejected 标记");
    }

    /**
     * 取消(4) 是<b>状态码键</b>独立的强制点：监听器此时写的 approved 也是 false，
     * 若 {@code bpm_status} 没注册/没回退，结果会退化成 "rejected" 而不是 "canceled" ——
     * 单看 approveVar 的断言永远发现不了这一半。
     */
    @Test
    @DisplayName("🔴 自定义变量名 + 审批取消(4) → gate_result=canceled（状态码键独立生效）")
    void resume_customVars_cancel_keepsStatusCode() throws Exception {
        CompiledGraph graph = compile(CUSTOM_VAR_DSL);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();
        runUntilSuspended(graph, config, "gate");

        List<NodeOutput> resumed = resumeAsListener(graph, config, false, 4);

        assertEquals("canceled", last(resumed, "gate_result"),
                "状态码 4 必须活着走到执行器，否则取消会被误报成驳回");
        assertEquals(4, last(resumed, "gate_status"), "原始状态码应透传供审计");
    }

    // ========== ② 默认变量名的既有行为不回退 ==========

    /**
     * 默认名流程同样依赖「回调键无条件注册」这一半：variables[] 里<b>没有</b> bpm_approved/bpm_status，
     * 修复前它也会在 resume 瞬间被 {@code OverAllState.input()} 丢掉 —— 缺陷范围不止自定义变量名。
     */
    private static final String DEFAULT_VAR_DSL = """
            {"dslVersion":"1.0","id":"wf_bpm_default","name":"默认变量名的 BPM 审批",
             "variables":[
               {"key":"done","type":"string","merge":"replace"}
             ],
             "nodes":[
               {"id":"gate","type":"hitl.bpm_approval","config":{"bpmProcessDefKey":"oa_leave"}},
               {"id":"cap","type":"capture",
                "config":{"captureKeys":["bpm_approved","bpm_status"],"outputVar":"done"}}
             ],
             "edges":[
               {"from":"START","to":"gate"},
               {"from":"gate","to":"cap"},
               {"from":"cap","to":"END"}
             ]}
            """;

    @Test
    @DisplayName("🔴 默认变量名且 variables[] 未声明回调键 → 通过仍能落到下游（注册那一半的强制点）")
    void resume_defaultVars_approve_survivesInputFilter() throws Exception {
        CompiledGraph graph = compile(DEFAULT_VAR_DSL);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();
        runUntilSuspended(graph, config, "gate");

        List<NodeOutput> resumed = resumeAsListener(graph, config, true, 2);

        assertEquals(Boolean.TRUE, captureExecutor.captured.get("bpm_approved"),
                "未注册的键会被 OverAllState.input() 过滤掉，下游就读到 null；实际："
                        + captureExecutor.captured.get("bpm_approved"));
        assertEquals("approved", last(resumed, "gate_result"), "闸口应留下 approved 标记");
    }

    /**
     * 工作台 {@code /agent/workflow/resume-stream} 显式按 approveVar 传 feedback 时，
     * 自己的键优先于监听器默认键（回退只在读不到时生效），既有调用方零影响。
     */
    @Test
    @DisplayName("显式 approveVar 反馈压过监听器默认键（两者故意相反）")
    void resume_explicitVarFeedback_winsOverListenerDefault() throws Exception {
        CompiledGraph graph = compile(CUSTOM_VAR_DSL);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();
        runUntilSuspended(graph, config, "gate");

        RunnableConfig updated = graph.updateState(config, Map.of(
                "my_approved", true,
                "my_status", 2,
                HitlBpmApprovalNodeExecutor.DEFAULT_APPROVE_VAR, false,
                HitlBpmApprovalNodeExecutor.DEFAULT_STATUS_VAR, 3));
        List<NodeOutput> resumed = graph.stream(null, updated.withResume()).collectList().block();
        assertNotNull(resumed, "恢复流应正常结束");

        assertEquals(Boolean.TRUE, captureExecutor.captured.get("my_approved"),
                "显式自定义键应压过默认键，实际：" + captureExecutor.captured.get("my_approved"));
        assertEquals("approved", last(resumed, "gate_result"), "闸口应留下 approved 标记");
    }

}
