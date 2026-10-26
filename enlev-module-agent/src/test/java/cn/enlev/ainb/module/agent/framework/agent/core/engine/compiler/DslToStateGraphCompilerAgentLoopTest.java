package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentLoopProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ParallelNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trace.NodeTraceRecorder;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentSecurityGateway;
import cn.enlev.ainb.module.agent.framework.agent.core.loop.AgentLoopGraphBuilder;
import cn.enlev.ainb.module.agent.framework.agent.core.loop.AgentLoopPrompt;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolCatalog;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DslToStateGraphCompiler} 接 agent_loop 的单测（P2 增量3）
 *
 * <p>重点：agent_loop 节点在<b>建图期</b>被 {@link AgentLoopGraphBuilder} 现场合成一张 reason↔act
 * <b>环形子图</b>，再 {@code addNode(id, loopGraph)} 内嵌 —— 与 sub_workflow 同属「节点动作即子图」，
 * 区别是子图<b>不来自另一份 DSL</b>而是从 config 合成。这里用<b>真 builder + mock 其依赖</b>做纯编译
 * （{@code MemorySaver} + 桩执行器 + mock traceRecorder，不执行图、不碰真 DB / 模型），断言：
 * <ol>
 *   <li>① 形状：含 agent_loop 的父 DSL 编译成功，agent_loop 在父 mermaid 里以<b>单节点</b>出现，父连边照常；</li>
 *   <li>② 编译期只读契约拒绝：tools[] 含写工具 / 含未登记工具 / 缺 tools / 缺 outputVar / 缺初始目标 →
 *       均抛 {@code WORKFLOW_DSL_INVALID}；</li>
 *   <li>③ 回归：无 agent_loop 的普通 DSL 仍编译通过（确认接线零副作用）。</li>
 * </ol>
 *
 * @author 走进新时代
 */
class DslToStateGraphCompilerAgentLoopTest {

    private DslToStateGraphCompiler compiler;
    private DslParser parser;
    private AgentToolRegistry toolRegistry;

    /** 桩执行器：只声明 type，execute 返回空 delta（形状测试不执行图，永远不会被调用） */
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
                new StubExecutor("llm"),
                new StubExecutor("tool")));

        // 真 builder（跑真 resolveSpec 的编译期只读校验）+ mock 其依赖
        toolRegistry = mock(AgentToolRegistry.class);
        AgentLoopPrompt loopPrompt = mock(AgentLoopPrompt.class);
        when(loopPrompt.buildSystemPrompt(any(), anyInt())).thenReturn("你是 ReAct 助手");
        AgentLoopGraphBuilder loopBuilder = new AgentLoopGraphBuilder();
        ReflectionTestUtils.setField(loopBuilder, "aiModelApi", mock(AiModelApi.class));
        ReflectionTestUtils.setField(loopBuilder, "toolExecutor", mock(AgentToolExecutor.class));
        // B2 之后 builder 认工具走 AgentToolCatalog（内置注册表 ∪ 当前租户自定义工具目录）。
        // 塞真 catalog、底下垫 mock：toolRegistry 的桩（下面 stubReadOnly/stubWrite）一个字都不用改；
        // toolDefService 不桩 —— mock 返回 null，customRows() 按「本租户没有自定义工具」处理，
        // 于是本类关于 agent_loop 编译期闸的断言与 B2 之前完全等价。
        ReflectionTestUtils.setField(loopBuilder, "toolCatalog",
                new AgentToolCatalog(toolRegistry, mock(AgentToolDefService.class)));
        ReflectionTestUtils.setField(loopBuilder, "loopPrompt", loopPrompt);
        ReflectionTestUtils.setField(loopBuilder, "loopProperties", new AgentLoopProperties());
        ReflectionTestUtils.setField(loopBuilder, "traceRecorder", mock(NodeTraceRecorder.class));
        ReflectionTestUtils.setField(loopBuilder, "gateway", mock(AgentSecurityGateway.class));

        compiler = new DslToStateGraphCompiler();
        ReflectionTestUtils.setField(compiler, "registry", registry);
        ReflectionTestUtils.setField(compiler, "traceRecorder", mock(NodeTraceRecorder.class));
        ReflectionTestUtils.setField(compiler, "defService", mock(AgentWorkflowDefService.class));
        ReflectionTestUtils.setField(compiler, "dslParser", parser);
        ReflectionTestUtils.setField(compiler, "agentLoopGraphBuilder", loopBuilder);
        ReflectionTestUtils.setField(compiler, "loopProperties", new AgentLoopProperties());
    }

    private CompiledGraph compile(String dslJson) throws Exception {
        WorkflowDefDTO def = parser.parse(dslJson);
        return compiler.compile(def, 1L, "t1:1",
                AgentExecutionContext.ofForTest(1L, 1L),
                new MemorySaver(), ObservationRegistry.NOOP);
    }

    private String mermaid(CompiledGraph graph) {
        return graph.getGraph(GraphRepresentation.Type.MERMAID).content();
    }

    private void stubReadOnly(String name) {
        AgentToolRegistry.ToolDefinition def = mock(AgentToolRegistry.ToolDefinition.class);
        when(def.isRequireConfirm()).thenReturn(false);
        when(def.getRisk()).thenReturn(RiskLevel.READ_ONLY);
        // 编译期只读闸读的是「声明」风险级（D11）：不桩就会拿到 null，被兜底成 HIGH 而误判为写工具
        when(def.getDeclaredRisk()).thenReturn(RiskLevel.READ_ONLY);
        when(def.getName()).thenReturn(name);
        when(toolRegistry.get(name)).thenReturn(def);
    }

    private void stubWrite(String name) {
        AgentToolRegistry.ToolDefinition def = mock(AgentToolRegistry.ToolDefinition.class);
        when(def.isRequireConfirm()).thenReturn(true);
        when(def.getRisk()).thenReturn(RiskLevel.IRREVERSIBLE);
        when(def.getDeclaredRisk()).thenReturn(RiskLevel.IRREVERSIBLE);
        when(def.getName()).thenReturn(name);
        when(toolRegistry.get(name)).thenReturn(def);
    }

    /** 造一条 trigger→ag(agent_loop)→final(llm)→END 的父 DSL，ag 的 config 由入参给 */
    private static String parentDsl(String agConfigJson) {
        return """
                {"dslVersion":"1.0","id":"wf_agent_demo","name":"agent_loop 演示",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"ag","type":"agent_loop","config":%s},
                   {"id":"final","type":"llm","config":{"prompt":"复述 {{$.agent_answer}}","outputVar":"answer"}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},
                   {"from":"trigger","to":"ag"},
                   {"from":"ag","to":"final"},
                   {"from":"final","to":"END"}
                 ]}
                """.formatted(agConfigJson);
    }

    // ==================== ① 形状：agent_loop 以单节点内嵌，父连边照常 ====================

    @Test
    void compile_agentLoopAsChildGraph_embedsAsSingleNode() throws Exception {
        stubReadOnly("datamodel_list");
        stubReadOnly("datamodel_structure");
        String cfg = """
                {"modelId":67,"tools":["datamodel_list","datamodel_structure"],"maxSteps":3,
                 "prompt":"库里有哪些表？","outputVar":"agent_answer","trajectoryVar":"agent_trace"}
                """;
        CompiledGraph graph = compile(parentDsl(cfg));
        assertNotNull(graph, "含 agent_loop 的父图应编译成功");
        String m = mermaid(graph);
        // agent_loop 在父图里以单节点 ag 注册（内部 reason/act 藏进子图），父连边 trigger→ag→final→END 照常
        assertTrue(m.contains("ag"), "父 mermaid 应含 agent_loop 节点 ag，实际：\n" + m);
        assertTrue(m.contains("final"), "父 mermaid 应含 final 节点，实际：\n" + m);
    }

    // ==================== ② 编译期只读契约拒绝 ====================

    @Test
    void compile_writeToolInTools_rejected() {
        stubReadOnly("datamodel_list");
        stubWrite("datamodel_create");   // 写工具（不可逆）
        String cfg = """
                {"modelId":67,"tools":["datamodel_list","datamodel_create"],"maxSteps":3,
                 "prompt":"建个表","outputVar":"agent_answer"}
                """;
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(parentDsl(cfg)));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_unregisteredTool_rejected() {
        stubReadOnly("datamodel_list");
        // ghost_tool 未登记：toolRegistry.get 返回 null
        String cfg = """
                {"modelId":67,"tools":["datamodel_list","ghost_tool"],"maxSteps":3,
                 "prompt":"查","outputVar":"agent_answer"}
                """;
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(parentDsl(cfg)));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_missingTools_rejected() {
        String cfg = """
                {"modelId":67,"maxSteps":3,"prompt":"查","outputVar":"agent_answer"}
                """;
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(parentDsl(cfg)));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_missingOutputVar_rejected() {
        stubReadOnly("datamodel_list");
        String cfg = """
                {"modelId":67,"tools":["datamodel_list"],"maxSteps":3,"prompt":"查"}
                """;
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(parentDsl(cfg)));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_missingGoal_rejected() {
        stubReadOnly("datamodel_list");
        // 既无 prompt 也无 inputVar
        String cfg = """
                {"modelId":67,"tools":["datamodel_list"],"maxSteps":3,"outputVar":"agent_answer"}
                """;
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(parentDsl(cfg)));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    // ==================== ③ 回归：无 agent_loop 的普通 DSL 不受影响 ====================

    @Test
    void compile_noAgentLoop_plainDslStillWorks() throws Exception {
        String dsl = """
                {"dslVersion":"1.0","id":"wf_plain","name":"普通",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"llm1","type":"llm","config":{"prompt":"hi","outputVar":"answer"}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},
                   {"from":"trigger","to":"llm1"},
                   {"from":"llm1","to":"END"}
                 ]}
                """;
        CompiledGraph graph = compile(dsl);
        assertNotNull(graph);
        assertTrue(mermaid(graph).contains("llm1"), "普通 DSL 应正常建图");
    }

}
