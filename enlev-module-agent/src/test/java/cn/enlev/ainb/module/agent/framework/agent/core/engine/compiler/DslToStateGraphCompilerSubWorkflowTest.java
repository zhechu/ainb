package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ParallelNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trace.NodeTraceRecorder;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
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

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_SUB_WORKFLOW_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DslToStateGraphCompiler} 的 sub_workflow 展开单测（P2 增量2b + 增量2c）
 *
 * <p>重点：sub_workflow 节点在<b>建图期</b>被展开成 SAA 原生子图 —— 子 def 递归构建成
 * {@link com.alibaba.cloud.ai.graph.StateGraph} 后 {@code addNode(id, childGraph)}，交给 SAA 在父图
 * compile 时用父 config 编译。这里用 {@code MemorySaver} + 桩执行器 + mock traceRecorder + mock
 * {@link AgentWorkflowDefService}（喂子 def 的 DSL）做<b>纯编译</b>（不执行图、不碰真 DB），断言：
 * ① 父图编译成功、子图以单节点 {@code sw} 出现；② 各类编译期契约校验按预期抛错
 * （workflowId 缺失 / 子 def 查不到 / 非法 inputMap/outputMap / 子图含 hitl / 循环引用 / 嵌套过深）；
 * ③ parallel 分支引用 sub_workflow 被编译期拒绝（SAA 内联子图破坏「单一 target」收敛，见该方法注释）；
 * ④ 无 sub_workflow 的普通 DSL 仍编译通过（回归，确认 buildStateGraph 抽取零行为变化）；
 * ⑤（增量2c）配了 inputMap/outputMap 的 sw 被父侧 {@code _<sw>_in}/{@code _<sw>_out} 合成节点夹住
 * （形状见本类，运行时重命名见 {@code DslToStateGraphCompilerSubWorkflowMappingRuntimeTest}）；
 * 未配映射 = 增量2b 原样直挂（无 {@code _in}/{@code _out}）。
 *
 * @author 走进新时代
 */
class DslToStateGraphCompilerSubWorkflowTest {

    private DslToStateGraphCompiler compiler;
    private DslParser parser;
    private AgentWorkflowDefService defService;

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
                new StubExecutor("knowledge_retrieval"),
                new StubExecutor("condition.rule"),
                new StubExecutor("llm"),
                new StubExecutor("tool")));

        defService = mock(AgentWorkflowDefService.class);

        compiler = new DslToStateGraphCompiler();
        ReflectionTestUtils.setField(compiler, "registry", registry);
        // 形状测试只编译不执行，traceRecorder 不会触发，用 mock 占位即可
        ReflectionTestUtils.setField(compiler, "traceRecorder", mock(NodeTraceRecorder.class));
        // sub_workflow：子 def 查询走 mock，子 DSL 解析复用真 DslParser（与顶层同一套 Jackson 语义）
        ReflectionTestUtils.setField(compiler, "defService", defService);
        ReflectionTestUtils.setField(compiler, "dslParser", parser);
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

    /** 造一行子 def（version 精确、PUBLISHED），dsl 为子流程 JSON */
    private static AgentWorkflowDefDO childDef(String code, Integer version, String dsl) {
        AgentWorkflowDefDO d = new AgentWorkflowDefDO();
        d.setCode(code);
        d.setVersion(version);
        d.setStatus("PUBLISHED");
        d.setDsl(dsl);
        return d;
    }

    /** 一条最简子链：START→trigger→subllm(llm)→END */
    private static final String CHILD_CHAIN_DSL = """
            {"id":"wf_child","name":"子流程",
             "nodes":[
               {"id":"ctrigger","type":"trigger.chat"},
               {"id":"subllm","type":"llm","config":{"prompt":"子","outputVar":"sub_answer"}}
             ],
             "edges":[
               {"from":"START","to":"ctrigger"},
               {"from":"ctrigger","to":"subllm"},
               {"from":"subllm","to":"END"}
             ]}
            """;

    // ==================== 形状（unmapped 回归）：子图以单节点 sw 内嵌，父连边照常，无 _in/_out ====================

    @Test
    void compile_subWorkflowAsChildGraph_embedsAsSingleNode() throws Exception {
        when(defService.getSubWorkflowDef("wf_child", 1))
                .thenReturn(childDef("wf_child", 1, CHILD_CHAIN_DSL));

        String parent = """
                {"dslVersion":"1.0","id":"wf_parent","name":"父流程",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_child","version":1}},
                   {"id":"final","type":"llm","config":{"prompt":"复述 {{$.sub_answer}}","outputVar":"answer"}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},
                   {"from":"trigger","to":"sw"},
                   {"from":"sw","to":"final"},
                   {"from":"final","to":"END"}
                 ]}
                """;
        CompiledGraph graph = compile(parent);
        assertNotNull(graph, "父图应编译成功");
        String m = mermaid(graph);
        // 子图在父图里以单节点 sw 注册，父连边 trigger→sw→final→END 照常
        assertTrue(m.contains("sw"), "父 mermaid 应含子图节点 sw，实际：\n" + m);
        assertTrue(m.contains("final"), "父 mermaid 应含 final 节点，实际：\n" + m);
        // 增量2c 回归：未配映射 → 不合成 _in/_out（增量2b 原样直挂）
        assertFalse(m.contains("_sw_in"), "未配映射不应合成 _sw_in，实际：\n" + m);
        assertFalse(m.contains("_sw_out"), "未配映射不应合成 _sw_out，实际：\n" + m);
    }

    // ==================== 形状（mapped，增量2c）：_in/_out 合成节点夹住子图 ====================

    @Test
    void compile_subWorkflowWithInputOutputMap_splicesInAndOutNodes() throws Exception {
        when(defService.getSubWorkflowDef("wf_child", 1))
                .thenReturn(childDef("wf_child", 1, CHILD_CHAIN_DSL));

        String parent = """
                {"dslVersion":"1.0","id":"wf_parent","name":"父流程",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_child","version":1,
                       "inputMap":{"modelId":"{{$.theId}}"},
                       "outputMap":{"parentStruct":"{{$.sub_answer}}"}}},
                   {"id":"final","type":"llm","config":{"prompt":"复述 {{$.parentStruct}}","outputVar":"answer"}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},
                   {"from":"trigger","to":"sw"},
                   {"from":"sw","to":"final"},
                   {"from":"final","to":"END"}
                 ]}
                """;
        CompiledGraph graph = compile(parent);
        assertNotNull(graph, "配了合法映射的父图应编译成功");
        String m = mermaid(graph);
        // 父侧合成 _sw_in / _sw_out 夹住子图 sw；父连边 trigger→_sw_in→…子图…→_sw_out→final
        assertTrue(m.contains("_sw_in"), "配了 inputMap 应合成 _sw_in，实际：\n" + m);
        assertTrue(m.contains("_sw_out"), "配了 outputMap 应合成 _sw_out，实际：\n" + m);
        assertTrue(m.contains("final"), "父 mermaid 应含 final 节点，实际：\n" + m);
    }

    @Test
    void compile_subWorkflowWithOnlyInputMap_splicesInNodeOnly() throws Exception {
        when(defService.getSubWorkflowDef("wf_child", 1))
                .thenReturn(childDef("wf_child", 1, CHILD_CHAIN_DSL));
        // 只配 inputMap：合成 _sw_in，不合成 _sw_out（避免空 plumbing 节点）
        String parent = """
                {"dslVersion":"1.0","id":"wf_parent","name":"父流程",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_child","version":1,
                       "inputMap":{"modelId":"{{$.theId}}"}}},
                   {"id":"final","type":"llm","config":{"prompt":"x"}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},{"from":"trigger","to":"sw"},
                   {"from":"sw","to":"final"},{"from":"final","to":"END"}
                 ]}
                """;
        String m = mermaid(compile(parent));
        assertTrue(m.contains("_sw_in"), "只配 inputMap 应合成 _sw_in，实际：\n" + m);
        assertFalse(m.contains("_sw_out"), "未配 outputMap 不应合成 _sw_out，实际：\n" + m);
    }

    @Test
    void compile_subWorkflowWithEmptyMaps_treatedAsUnmapped() throws Exception {
        when(defService.getSubWorkflowDef("wf_child", 1))
                .thenReturn(childDef("wf_child", 1, CHILD_CHAIN_DSL));
        // 空对象 {} 合法且等价「无映射」：不合成 _in/_out
        String parent = """
                {"dslVersion":"1.0","id":"wf_parent","name":"父流程",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_child","version":1,
                       "inputMap":{},"outputMap":{}}},
                   {"id":"final","type":"llm","config":{"prompt":"x"}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},{"from":"trigger","to":"sw"},
                   {"from":"sw","to":"final"},{"from":"final","to":"END"}
                 ]}
                """;
        String m = mermaid(compile(parent));
        assertFalse(m.contains("_sw_in"), "空 inputMap 不应合成 _sw_in，实际：\n" + m);
        assertFalse(m.contains("_sw_out"), "空 outputMap 不应合成 _sw_out，实际：\n" + m);
    }

    // ==================== 增量16D：子流程内嵌条件边（condition.*）已支持 ====================

    /**
     * 🔴 sub_workflow 的<b>子流程内嵌条件边</b>（condition.* + conditional cases/default）是<b>已支持</b>的能力，
     * 不同于 iteration body（后者仅 plain 线性链、拒绝 condition.*）。原因：sub_workflow 子图走
     * {@code buildStateGraph(childDef,…)} 完整递归，条件节点/条件边与顶层同一套映射逻辑；只有 hitl 与 wait
     * 因「跨子图 interruptBefore 传播未证」被编译期拒（见 {@code compile_childContainsHitl_rejected}）。
     * 本用例把这条能力钉死，防未来误把 condition.* 也一并挡掉（那会造成「合法子流程被拒」的回退）。
     */
    @Test
    void compile_subWorkflowChildWithConditionEdges_compiles() throws Exception {
        String childWithCondition = """
                {"id":"wf_cond","name":"含条件子流程",
                 "nodes":[
                   {"id":"ctrigger","type":"trigger.chat"},
                   {"id":"cond","type":"condition.rule","config":{"default":"no"}},
                   {"id":"yes","type":"llm","config":{"prompt":"命中","outputVar":"sub_answer"}},
                   {"id":"no","type":"llm","config":{"prompt":"未命中","outputVar":"sub_answer"}}
                 ],
                 "edges":[
                   {"from":"START","to":"ctrigger"},
                   {"from":"ctrigger","to":"cond"},
                   {"from":"cond","type":"conditional","cases":{"yes":"yes"},"default":"no"},
                   {"from":"yes","to":"END"},
                   {"from":"no","to":"END"}
                 ]}
                """;
        when(defService.getSubWorkflowDef("wf_cond", 1))
                .thenReturn(childDef("wf_cond", 1, childWithCondition));

        String parent = """
                {"dslVersion":"1.0","id":"wf_parent","name":"父流程",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_cond","version":1}},
                   {"id":"final","type":"llm","config":{"prompt":"复述 {{$.sub_answer}}","outputVar":"answer"}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},
                   {"from":"trigger","to":"sw"},
                   {"from":"sw","to":"final"},
                   {"from":"final","to":"END"}
                 ]}
                """;
        CompiledGraph graph = compile(parent);
        assertNotNull(graph, "含条件边的子流程应能作为 SAA 子图编译成功");
        String m = mermaid(graph);
        assertTrue(m.contains("sw"), "父 mermaid 应含子图节点 sw，实际：\n" + m);
        assertTrue(m.contains("final"), "父 mermaid 应含 final 节点，实际：\n" + m);
    }

    // ==================== 契约校验拒绝 ====================

    @Test
    void compile_missingWorkflowId_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"version":1}}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"sw"},{"from":"sw","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_childNotFound_rejected() {
        // 子 def 查不到：Service 抛 WORKFLOW_SUB_WORKFLOW_NOT_FOUND，编译器原样上抛
        when(defService.getSubWorkflowDef("ghost", 1))
                .thenThrow(exception(WORKFLOW_SUB_WORKFLOW_NOT_FOUND, "ghost@1"));
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"ghost","version":1}}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"sw"},{"from":"sw","to":"END"}]}
                """));
        assertEquals(WORKFLOW_SUB_WORKFLOW_NOT_FOUND.getCode(), ex.getCode());
    }

    // ==================== 增量2c：非法 inputMap/outputMap 编译期拒绝（在取子 def 之前，无需 stub defService） ====================

    @Test
    void compile_inputMapNotObject_rejected() {
        // inputMap 不是对象（是字符串）→ 拒绝
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_child","version":1,"inputMap":"oops"}}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"sw"},{"from":"sw","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_inputMapBlankKey_rejected() {
        // 键为空（键必须是目标变量名）→ 拒绝
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_child","version":1,"inputMap":{"":"{{$.x}}"}}}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"sw"},{"from":"sw","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_inputMapInvalidVarName_rejected() {
        // 键不是合法变量名（大写 + 连字符）→ 拒绝
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_child","version":1,"inputMap":{"A-b":"{{$.x}}"}}}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"sw"},{"from":"sw","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_inputMapNonStringValue_rejected() {
        // 值不是字符串（是数字）→ 拒绝（值必须是取值表达式/模板字符串）
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_child","version":1,"inputMap":{"modelId":123}}}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"sw"},{"from":"sw","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_outputMapBlankValue_rejected() {
        // 值为空白字符串 → 拒绝
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_child","version":1,"outputMap":{"parentStruct":"  "}}}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"sw"},{"from":"sw","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_outputMapInvalidVarName_rejected() {
        // outputMap 键（父变量名）非法 → 拒绝
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_child","version":1,"outputMap":{"1bad":"{{$.x}}"}}}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"sw"},{"from":"sw","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_childContainsHitl_rejected() {
        String childWithHitl = """
                {"id":"wf_hitl","name":"含人工",
                 "nodes":[
                   {"id":"ctrigger","type":"trigger.chat"},
                   {"id":"confirm","type":"hitl.confirm","config":{}}
                 ],
                 "edges":[
                   {"from":"START","to":"ctrigger"},
                   {"from":"ctrigger","to":"confirm"},
                   {"from":"confirm","to":"END"}
                 ]}
                """;
        when(defService.getSubWorkflowDef("wf_hitl", 1))
                .thenReturn(childDef("wf_hitl", 1, childWithHitl));
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_hitl","version":1}}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"sw"},{"from":"sw","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_cycleReference_rejected() {
        // wf_a → wf_b → wf_a：buildPath 命中重复 childKey，编译期 fail-fast
        String wfA = subChainDsl("wf_a", "wf_b");
        String wfB = subChainDsl("wf_b", "wf_a");
        when(defService.getSubWorkflowDef("wf_b", 1)).thenReturn(childDef("wf_b", 1, wfB));
        when(defService.getSubWorkflowDef("wf_a", 1)).thenReturn(childDef("wf_a", 1, wfA));

        String top = subChainDsl("wf_a", "wf_b"); // 顶层 def 直接解析（等价 wf_a）
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(top));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_tooDeepNesting_rejected() {
        // wf_l0 → wf_l1 → ... 每层引用不同 code（避开环检测），到 buildPath.size()==8 触发深度守卫
        for (int i = 1; i <= 8; i++) {
            String dsl = subChainDsl("wf_l" + i, "wf_l" + (i + 1));
            when(defService.getSubWorkflowDef("wf_l" + i, 1)).thenReturn(childDef("wf_l" + i, 1, dsl));
        }
        String top = subChainDsl("wf_l0", "wf_l1");
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(top));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    /** 造一份「trigger→sw(引用 childCode@1)→END」的最简 def，用于环/深度用例 */
    private static String subChainDsl(String selfCode, String childCode) {
        return """
                {"id":"%s","name":"%s",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"%s","version":1}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},
                   {"from":"trigger","to":"sw"},
                   {"from":"sw","to":"END"}
                 ]}
                """.formatted(selfCode, selfCode, childCode);
    }

    // ==================== 组合限制：parallel 分支不能是 sub_workflow 子图 ====================

    @Test
    void compile_parallelBranchReferencingSubWorkflow_rejected() {
        // 🔴 实测（SAA 1.1.2.2）：sub_workflow 子图作 parallel 分支时，父图 compile 会把子图内联展开成
        //    多节点链，findParallelNodeTargets 取到各分支的直接 target 为子图内部节点（sw_a-subllm /
        //    sw_b-subllm），互不相同 → SAA 抛「parallel node must have only one target」。expandParallel
        //    提前拦截，给清晰中文 WORKFLOW_DSL_INVALID，而不是把 SAA 英文栈抛给编排者。
        //    故「多节点并行分支 = 封成 sub_workflow 单节点引用」在 SAA 1.1.2.2 下不成立。
        //    注：建图第 1 步会先把两个子图各自构建出来（此处 stub 子 def），第 2 步 expandParallel 才拦截，
        //    与生产顺序一致（getSubWorkflowDef 生产环境查不到会抛 NOT_FOUND、绝不返回 null）。
        when(defService.getSubWorkflowDef("wf_child", 1))
                .thenReturn(childDef("wf_child", 1, CHILD_CHAIN_DSL));
        String parent = """
                {"dslVersion":"1.0","id":"wf_par_sub","name":"并行套子流程",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"par","type":"parallel","config":{"branches":[["sw_a"],["sw_b"]],"join":"all"}},
                   {"id":"sw_a","type":"sub_workflow","config":{"workflowId":"wf_child","version":1}},
                   {"id":"sw_b","type":"sub_workflow","config":{"workflowId":"wf_child","version":1}},
                   {"id":"merge","type":"llm","config":{"prompt":"汇总"}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},
                   {"from":"trigger","to":"par"},
                   {"from":"par","to":"merge"},
                   {"from":"merge","to":"END"}
                 ]}
                """;
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(parent));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    // ==================== 回归：无 sub_workflow 的普通 DSL 不受影响 ====================

    @Test
    void compile_noSubWorkflow_plainDslStillWorks() throws Exception {
        String dsl = """
                {"dslVersion":"1.0","id":"wf_plain","name":"普通",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"kr","type":"knowledge_retrieval","config":{"outputVar":"r"}},
                   {"id":"llm1","type":"llm","config":{"prompt":"hi"}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},
                   {"from":"trigger","to":"kr"},
                   {"from":"kr","to":"llm1"},
                   {"from":"llm1","to":"END"}
                 ]}
                """;
        CompiledGraph graph = compile(dsl);
        assertNotNull(graph);
        String m = mermaid(graph);
        assertTrue(m.contains("kr") && m.contains("llm1"), "普通 DSL 应正常建图，实际：\n" + m);
    }

}
