package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

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
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DslToStateGraphCompiler} 的 sub_workflow <b>inputMap/outputMap 运行时</b>单测（P2 增量2c）
 *
 * <p>与 {@link DslToStateGraphCompilerSubWorkflowTest}（纯编译、断形状）互补：本类用<b>真编译器</b>建图 +
 * <b>真执行</b>（{@code MemorySaver} + {@code compiled.stream(...)}）跑通父图，断言<b>值真的按映射重命名流动</b>。
 * 子流程节点用 {@link CaptureExecutor}（桩）：从共享 state 读指定键（记录<b>原始类型</b>）、写回指定 outputVar。
 * mock 掉 {@link AgentWorkflowDefService}（喂子 def DSL）与 {@link NodeTraceRecorder}（不碰真 DB）。
 *
 * <h3>四个判据（对应计划 R1/R2/R3）</h3>
 * <ol>
 *   <li>① <b>input 重命名 + 类型保真</b>：父 input {@code theId=5}（Integer），inputMap {@code {modelId:"{{$.theId}}"}}，
 *       子流程读到 {@code modelId} 是 <b>Integer 5</b>（非字符串 "5"）—— 证单整占位符走 {@code StatePathNavigator}
 *       保原始类型，而非 {@code StateTemplateResolver}（会 stringify）。</li>
 *   <li>② <b>output 重命名 + 类型保真</b>：子流程写 {@code structOut={tableName:act_evt_log}}（Map），
 *       outputMap {@code {parentStruct:"{{$.structOut}}"}}，父终止时 {@code parentStruct} 是<b>原始 Map</b>（非 JSON 串）
 *       —— 同时证「子图写→父下一节点可见」的 merge-back（增量2b 已证，这里 _sw_out 就是 sw 的下一个父节点）。</li>
 *   <li>③ <b>混合模板插值</b>：inputMap {@code {tag:"{{$.kind}}-sub"}}（kind="x"）→ 子读到 {@code tag=="x-sub"}
 *       （非单占位符 → 走字符串插值）。</li>
 *   <li>④ <b>unmapped 直挂回归</b>：不配映射 → 父子同名变量照常双向流动（增量2b 行为），且不合成 {@code _in}/{@code _out}。</li>
 * </ol>
 *
 * @author 走进新时代
 */
class DslToStateGraphCompilerSubWorkflowMappingRuntimeTest {

    private DslToStateGraphCompiler compiler;
    private DslParser parser;
    private AgentWorkflowDefService defService;
    private CaptureExecutor captureExecutor;

    /**
     * 桩执行器：从共享 state 读 {@code config.captureKeys} 列出的键（记原始值到 {@link #captured}），
     * 再把 {@link #writeValue} 写进 {@code config.outputVar}。用于模拟「子流程读入参、写结果」。
     */
    private static class CaptureExecutor implements NodeExecutor {
        final Map<String, Object> captured = new LinkedHashMap<>();
        Object writeValue;

        @Override
        public String getType() {
            return "capture";
        }

        @Override
        public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
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

    /** 只声明 type、execute 返回空 delta 的桩（trigger.chat/llm/tool 占位，本类不真正用到其业务） */
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
                captureExecutor,
                new StubExecutor("trigger.chat"),
                new StubExecutor("llm"),
                new StubExecutor("tool")));

        defService = mock(AgentWorkflowDefService.class);

        compiler = new DslToStateGraphCompiler();
        ReflectionTestUtils.setField(compiler, "registry", registry);
        // 真执行会触发 trace 三段包，但 traceRecorder 是 mock（begin 返回 null、success/failure 空操作），不碰真 DB
        ReflectionTestUtils.setField(compiler, "traceRecorder", mock(NodeTraceRecorder.class));
        ReflectionTestUtils.setField(compiler, "defService", defService);
        ReflectionTestUtils.setField(compiler, "dslParser", parser);
    }

    /** 编译并执行父图，返回上浮的节点序列；终态从最后一个 NodeOutput 读 */
    private List<NodeOutput> run(String parentDsl, Map<String, Object> input) throws Exception {
        WorkflowDefDTO def = parser.parse(parentDsl);
        CompiledGraph graph = compiler.compile(def, 1L, "t1:1",
                AgentExecutionContext.ofForTest(1L, 1L),
                new MemorySaver(), ObservationRegistry.NOOP);
        List<NodeOutput> outputs = graph
                .stream(input, RunnableConfig.builder().threadId("t1:1").build())
                .collectList()
                .block();
        assertNotNull(outputs, "父图流应正常完成（未挂起、未抛错）");
        return outputs;
    }

    private static Object finalValue(List<NodeOutput> outputs, String key) {
        return outputs.get(outputs.size() - 1).state().value(key).orElse(null);
    }

    private static AgentWorkflowDefDO childDef(String code, Integer version, String dsl) {
        AgentWorkflowDefDO d = new AgentWorkflowDefDO();
        d.setCode(code);
        d.setVersion(version);
        d.setStatus("PUBLISHED");
        d.setDsl(dsl);
        return d;
    }

    // ==================== ①②③ 配映射：input/output 重命名 + 类型保真 + 混合模板 ====================

    @Test
    void runWithMapping_renamesInputAndOutput_preservingRawTypes() throws Exception {
        // 子流程：读 modelId/tag（入参名），写 structOut（结果名）—— 全用「子流程自己的变量名」
        String child = """
                {"id":"wf_child","name":"子",
                 "nodes":[{"id":"cwork","type":"capture",
                           "config":{"captureKeys":["modelId","tag"],"outputVar":"structOut"}}],
                 "edges":[{"from":"START","to":"cwork"},{"from":"cwork","to":"END"}]}
                """;
        when(defService.getSubWorkflowDef("wf_child", 1)).thenReturn(childDef("wf_child", 1, child));
        captureExecutor.writeValue = Map.of("tableName", "act_evt_log");

        // 父流程：inputMap 把 theId→modelId（单占位符保类型）、kind→tag（混合模板插值）；
        //         outputMap 把子 structOut→父 parentStruct（单占位符保 Map）。父子变量名完全不同。
        String parent = """
                {"id":"wf_parent","name":"父",
                 "nodes":[{"id":"sw","type":"sub_workflow","config":{
                     "workflowId":"wf_child","version":1,
                     "inputMap":{"modelId":"{{$.theId}}","tag":"{{$.kind}}-sub"},
                     "outputMap":{"parentStruct":"{{$.structOut}}"}}}],
                 "edges":[{"from":"START","to":"sw"},{"from":"sw","to":"END"}]}
                """;

        List<NodeOutput> outputs = run(parent, Map.of("theId", 5, "kind", "x"));

        // ① input 重命名 + 类型保真：子读到 modelId 是 Integer 5（非 "5"）
        Object modelId = captureExecutor.captured.get("modelId");
        assertTrue(modelId instanceof Integer,
                "① inputMap 单占位符应保原始类型：modelId 应为 Integer，实际："
                        + (modelId == null ? "null" : modelId.getClass() + "=" + modelId));
        assertEquals(5, ((Number) modelId).intValue(), "① modelId 应重命名自 theId=5");

        // ③ 混合模板：tag = "{{$.kind}}-sub" → "x-sub"（字符串插值）
        assertEquals("x-sub", captureExecutor.captured.get("tag"),
                "③ 混合模板应插值成 x-sub，实际：" + captureExecutor.captured.get("tag"));

        // ② output 重命名 + 类型保真：父终止时 parentStruct 是原始 Map（非 JSON 串）
        Object parentStruct = finalValue(outputs, "parentStruct");
        assertTrue(parentStruct instanceof Map,
                "② outputMap 单占位符应保原始 Map：parentStruct 应为 Map，实际："
                        + (parentStruct == null ? "null" : parentStruct.getClass() + "=" + parentStruct));
        assertEquals(Map.of("tableName", "act_evt_log"), parentStruct,
                "② parentStruct 应重命名自子 structOut");

        // 信息性 + 弱断言：父侧 _sw_in / _sw_out 合成节点出现在上浮序列里（夹住子图），子节点以 sw- 命名空间上浮
        List<String> seen = outputs.stream().map(NodeOutput::node).filter(n -> n != null).toList();
        System.out.println("[map-runtime] 上浮节点序列 = " + seen);
        assertTrue(seen.contains("_sw_in"), "应见父侧 _sw_in，实际：" + seen);
        assertTrue(seen.contains("_sw_out"), "应见父侧 _sw_out，实际：" + seen);
        assertTrue(seen.stream().anyMatch(n -> n.contains("cwork")),
                "子图节点应以 sw- 命名空间上浮，实际：" + seen);
    }

    // ==================== ④ unmapped 回归：同名变量双向流动，不合成 _in/_out ====================

    @Test
    void runWithoutMapping_sameNameVariablesFlow_noSyntheticNodes() throws Exception {
        // 子流程：读 shared（与父同名），写 result（与父同名）
        String child = """
                {"id":"wf_child","name":"子",
                 "nodes":[{"id":"cwork","type":"capture",
                           "config":{"captureKeys":["shared"],"outputVar":"result"}}],
                 "edges":[{"from":"START","to":"cwork"},{"from":"cwork","to":"END"}]}
                """;
        when(defService.getSubWorkflowDef("wf_child", 1)).thenReturn(childDef("wf_child", 1, child));
        captureExecutor.writeValue = "done";

        // 父流程：sw 不配任何映射 → 增量2b 原样直挂
        String parent = """
                {"id":"wf_parent","name":"父",
                 "nodes":[{"id":"sw","type":"sub_workflow","config":{
                     "workflowId":"wf_child","version":1}}],
                 "edges":[{"from":"START","to":"sw"},{"from":"sw","to":"END"}]}
                """;

        List<NodeOutput> outputs = run(parent, Map.of("shared", "hello"));

        // 同名变量流入子图：子读到 shared=="hello"
        assertEquals("hello", captureExecutor.captured.get("shared"),
                "④ 未配映射时同名变量应流入子图");
        // 同名变量流回父图：父终止时 result=="done"
        assertEquals("done", finalValue(outputs, "result"),
                "④ 未配映射时子图结果应同名流回父图");

        // 不合成 _in/_out
        List<String> seen = outputs.stream().map(NodeOutput::node).filter(n -> n != null).toList();
        System.out.println("[map-runtime-unmapped] 上浮节点序列 = " + seen);
        assertFalse(seen.contains("_sw_in"), "④ 未配映射不应合成 _sw_in，实际：" + seen);
        assertFalse(seen.contains("_sw_out"), "④ 未配映射不应合成 _sw_out，实际：" + seen);
    }

    // ==================== 补充：只配 outputMap（input 同名直入）仍能重命名结果 ====================

    @Test
    void runWithOnlyOutputMap_renamesResultOnly() throws Exception {
        // 子流程读同名 shared、写 structOut；父只配 outputMap 把 structOut→parentStruct
        String child = """
                {"id":"wf_child","name":"子",
                 "nodes":[{"id":"cwork","type":"capture",
                           "config":{"captureKeys":["shared"],"outputVar":"structOut"}}],
                 "edges":[{"from":"START","to":"cwork"},{"from":"cwork","to":"END"}]}
                """;
        when(defService.getSubWorkflowDef("wf_child", 1)).thenReturn(childDef("wf_child", 1, child));
        captureExecutor.writeValue = List.of(1, 2, 3);

        String parent = """
                {"id":"wf_parent","name":"父",
                 "nodes":[{"id":"sw","type":"sub_workflow","config":{
                     "workflowId":"wf_child","version":1,
                     "outputMap":{"parentStruct":"{{$.structOut}}"}}}],
                 "edges":[{"from":"START","to":"sw"},{"from":"sw","to":"END"}]}
                """;

        List<NodeOutput> outputs = run(parent, new LinkedHashMap<>(Map.of("shared", "in")));

        // 子读到同名 shared（无 inputMap，直入）
        assertEquals("in", captureExecutor.captured.get("shared"));
        // outputMap 保原始 List 类型写回父 parentStruct
        Object parentStruct = finalValue(outputs, "parentStruct");
        assertTrue(parentStruct instanceof List,
                "outputMap 单占位符应保原始 List，实际："
                        + (parentStruct == null ? "null" : parentStruct.getClass() + "=" + parentStruct));
        assertEquals(List.of(1, 2, 3), parentStruct);

        List<String> seen = outputs.stream().map(NodeOutput::node).filter(n -> n != null).toList();
        assertFalse(seen.contains("_sw_in"), "只配 outputMap 不应合成 _sw_in，实际：" + seen);
        assertTrue(seen.contains("_sw_out"), "只配 outputMap 应合成 _sw_out，实际：" + seen);
    }

}
