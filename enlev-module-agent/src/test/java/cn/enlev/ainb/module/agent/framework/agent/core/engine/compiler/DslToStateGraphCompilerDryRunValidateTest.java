package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ParallelNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trace.NodeTraceRecorder;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link DslToStateGraphCompiler#dryRunValidate} 单测（增量7a，T2）
 *
 * <p>钉三件事：
 * <ol>
 *   <li>合法 DSL 干跑通过（{@code isPassed()}）；</li>
 *   <li>深层结构错（如 wait 配置非法）被<b>捕获成一条 error</b>，而不是抛出去——这样生成循环才能回灌自修正；</li>
 *   <li>🔴 <b>回归保护</b>：{@code dryRunValidate} 不改 {@code compile()} 的既有 fail-fast 行为——
 *       同一份非法 DSL，{@code compile()} 仍抛 {@code WORKFLOW_DSL_INVALID}（执行路径一行未动）。</li>
 * </ol>
 *
 * @author 走进新时代
 */
class DslToStateGraphCompilerDryRunValidateTest {

    private DslToStateGraphCompiler compiler;
    private DslParser parser;

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

        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        registry.setExecutors(List.of(
                new ParallelNodeExecutor(),
                new StubExecutor("trigger.chat"),
                new StubExecutor("wait"),
                new StubExecutor("llm"),
                new StubExecutor("tool")));

        compiler = new DslToStateGraphCompiler();
        ReflectionTestUtils.setField(compiler, "registry", registry);
        ReflectionTestUtils.setField(compiler, "traceRecorder", mock(NodeTraceRecorder.class));
        ReflectionTestUtils.setField(compiler, "defService", mock(AgentWorkflowDefService.class));
        ReflectionTestUtils.setField(compiler, "dslParser", parser);
    }

    private AgentExecutionContext ctx() {
        return AgentExecutionContext.ofForTest(1L, 1L);
    }

    // ==================== ① 合法 DSL 干跑通过 ====================

    @Test
    void dryRunValidate_validDsl_passed() throws Exception {
        WorkflowDefDTO def = parser.parse("""
                {"id":"wf","name":"n",
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"list","type":"tool","config":{"toolId":"data_interface_list","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"list"},{"from":"list","to":"END"}]}
                """);
        AgentValidateResult r = compiler.dryRunValidate(def, ctx());
        assertTrue(r.isPassed(), "合法 DSL 干跑应通过：" + r.toFeedback());
    }

    // ==================== ② 深层结构错被捕获成 error（不抛） ====================

    @Test
    void dryRunValidate_waitBadConfig_capturedAsError() throws Exception {
        // wait 缺 waitType：基础校验看不出（type=wait 是已知的），只有 buildStateGraph 的 validateWaitConfig 能探出
        WorkflowDefDTO def = parser.parse("""
                {"id":"wf",
                 "nodes":[{"id":"w","type":"wait","config":{"duration":3}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """);
        AgentValidateResult r = compiler.dryRunValidate(def, ctx());
        assertFalse(r.isPassed(), "wait 配置非法应被干跑探出");
        assertTrue(r.toFeedback().contains("waitType"), r.toFeedback());
    }

    @Test
    void dryRunValidate_unknownExecutorType_capturedAsError() throws Exception {
        // registry.get 抛 WORKFLOW_NODE_EXECUTOR_NOT_FOUND（ServiceException）→ 应被捕获成 error
        WorkflowDefDTO def = parser.parse("""
                {"id":"wf",
                 "nodes":[{"id":"a","type":"not_a_real_type","config":{}}],
                 "edges":[{"from":"START","to":"a"},{"from":"a","to":"END"}]}
                """);
        AgentValidateResult r = compiler.dryRunValidate(def, ctx());
        assertFalse(r.isPassed());
    }

    // ==================== ③ 🔴 回归：compile() 仍 fail-fast（dryRunValidate 未改执行路径） ====================

    @Test
    void compile_waitBadConfig_stillThrows() throws Exception {
        // 同一份非法 DSL：compile() 必须照旧抛 WORKFLOW_DSL_INVALID，而不是被 dryRunValidate 的引入「软化」
        WorkflowDefDTO def = parser.parse("""
                {"id":"wf",
                 "nodes":[{"id":"w","type":"wait","config":{"duration":3}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """);
        ServiceException ex = assertThrows(ServiceException.class, () ->
                compiler.compile(def, 1L, "t1:1", ctx(), new MemorySaver(), ObservationRegistry.NOOP));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_validDsl_stillCompiles() throws Exception {
        // 合法 DSL：compile() 照常产出 CompiledGraph（干跑封装没有副作用）
        WorkflowDefDTO def = parser.parse("""
                {"id":"wf","name":"n",
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"list","type":"tool","config":{"toolId":"data_interface_list","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"list"},{"from":"list","to":"END"}]}
                """);
        CompiledGraph graph = compiler.compile(def, 1L, "t1:1", ctx(),
                new MemorySaver(), ObservationRegistry.NOOP);
        assertNotNull(graph);
    }

    // ==================== ④ E12：写进 state 的名字必须合法（outputVar / variables[].key） ====================

    @Test
    void dryRunValidate_outputVarWithPaddingSpaces_capturedAsError() throws Exception {
        // 🔴 E12 的主形态。NodeDTO.configStr 是 String.valueOf(v)，**不 trim**，
        //    执行器于是原样把 " out " 当键 delta.put 进 state，而下游 {{$.out}} 取不到值 ——
        //    静默拿到空、零提示。修复前这一份 DSL 干跑是**通过**的。
        WorkflowDefDTO def = parser.parse("""
                {"id":"wf","name":"n",
                 "variables":[{"key":"out","type":"object"}],
                 "nodes":[{"id":"list","type":"tool","config":{"toolId":"x","outputVar":" out "}}],
                 "edges":[{"from":"START","to":"list"},{"from":"list","to":"END"}]}
                """);
        AgentValidateResult r = compiler.dryRunValidate(def, ctx());
        assertFalse(r.isPassed(), "带首尾空格的 outputVar 应被干跑拦下");
        assertTrue(r.toFeedback().contains("不是合法变量名"), r.toFeedback());
    }

    @Test
    void dryRunValidate_outputVarPureBlank_capturedAsError() throws Exception {
        // 纯空白 ≠ 空串：configStr 只在 null / "" 时回落成契约缺省名，"   " 会被原样当键写进 state，
        // 所以它就是一个非法名、必须拦（「没填该不该回落」是台账 C3 的事，不在这里判）
        WorkflowDefDTO def = parser.parse("""
                {"id":"wf","name":"n",
                 "nodes":[{"id":"g","type":"llm","config":{"outputVar":"   "}}],
                 "edges":[{"from":"START","to":"g"},{"from":"g","to":"END"}]}
                """);
        AgentValidateResult r = compiler.dryRunValidate(def, ctx());
        assertFalse(r.isPassed(), "纯空白 outputVar 应被干跑拦下");
        assertTrue(r.toFeedback().contains("不是合法变量名"), r.toFeedback());
    }

    @Test
    void dryRunValidate_outputVarDigitStart_capturedAsError() throws Exception {
        WorkflowDefDTO def = parser.parse("""
                {"id":"wf","name":"n",
                 "nodes":[{"id":"list","type":"tool","config":{"toolId":"x","outputVar":"1out"}}],
                 "edges":[{"from":"START","to":"list"},{"from":"list","to":"END"}]}
                """);
        AgentValidateResult r = compiler.dryRunValidate(def, ctx());
        assertFalse(r.isPassed(), "数字开头的 outputVar 应被干跑拦下");
        assertTrue(r.toFeedback().contains("不是合法变量名"), r.toFeedback());
    }

    @Test
    void dryRunValidate_variablesKeyIllegal_capturedAsError() throws Exception {
        // variables[].key 同样是 state 的键，与 outputVar 共用同一个判据（VAR_NAME），不另立第二份正则
        WorkflowDefDTO def = parser.parse("""
                {"id":"wf","name":"n",
                 "variables":[{"key":"bad-key","type":"object"}],
                 "nodes":[{"id":"list","type":"tool","config":{"toolId":"x"}}],
                 "edges":[{"from":"START","to":"list"},{"from":"list","to":"END"}]}
                """);
        AgentValidateResult r = compiler.dryRunValidate(def, ctx());
        assertFalse(r.isPassed(), "非法的 variables[].key 应被干跑拦下");
        assertTrue(r.toFeedback().contains("不是合法变量名"), r.toFeedback());
    }

    @Test
    void dryRunValidate_outputVarEmpty_fallsBackToContractDefault_stillPasses() throws Exception {
        // 🔴 判据自检（防「恒真式」）：空串必须**放过**。configStr 在 null / "" 时回落到契约缺省名
        //    （tool 的 ToolNodeExecutor.DEFAULT_OUTPUT_VAR），那是个合法名。
        //    若这条也红，说明上面那组断言判的不是「名字合不合法」，而是「有没有填」—— 两件事必须分清。
        WorkflowDefDTO def = parser.parse("""
                {"id":"wf","name":"n",
                 "nodes":[{"id":"list","type":"tool","config":{"toolId":"x","outputVar":""}}],
                 "edges":[{"from":"START","to":"list"},{"from":"list","to":"END"}]}
                """);
        AgentValidateResult r = compiler.dryRunValidate(def, ctx());
        assertTrue(r.isPassed(), "空 outputVar 回落到契约缺省名，不该被本条拦下：" + r.toFeedback());
    }

    @Test
    void dryRunValidate_outputVarLegal_stillPasses() throws Exception {
        // 判据自检的另一半：合法名（含数字与下划线、字母开头）必须放过
        WorkflowDefDTO def = parser.parse("""
                {"id":"wf","name":"n",
                 "variables":[{"key":"my_out1","type":"object"}],
                 "nodes":[{"id":"list","type":"tool","config":{"toolId":"x","outputVar":"my_out1"}}],
                 "edges":[{"from":"START","to":"list"},{"from":"list","to":"END"}]}
                """);
        AgentValidateResult r = compiler.dryRunValidate(def, ctx());
        assertTrue(r.isPassed(), "合法变量名不该被拦下：" + r.toFeedback());
    }

    @Test
    void compile_outputVarIllegal_stillThrows() throws Exception {
        // 🔴 执行路径同样 fail-fast：E12 落在 buildStateGraph 里，compile() 与 dryRunValidate 共用它，
        //    所以「绕过发布闸门直接跑」也拦得住，不是只在闸门上装样子
        WorkflowDefDTO def = parser.parse("""
                {"id":"wf","name":"n",
                 "nodes":[{"id":"list","type":"tool","config":{"toolId":"x","outputVar":" out "}}],
                 "edges":[{"from":"START","to":"list"},{"from":"list","to":"END"}]}
                """);
        ServiceException ex = assertThrows(ServiceException.class, () ->
                compiler.compile(def, 1L, "t1:1", ctx(), new MemorySaver(), ObservationRegistry.NOOP));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

}
