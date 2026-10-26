package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentLoopProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trace.NodeTraceRecorder;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.loop.IterationGraphBuilder;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
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

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link DslToStateGraphCompiler} 接 iteration 的单测（P2 增量3-iteration）
 *
 * <p>重点：iteration 节点在<b>建图期</b>被 {@link IterationGraphBuilder} 现场合成一张 {@code _guard→body链→_advance}
 * <b>环形子图</b>，再 {@code addNode(id, iterGraph)} 内嵌 —— 与 agent_loop 同属「节点动作即合成环形子图」。
 * 关键结构性质：<b>body 节点写在顶层 nodes[] 但从父图排除、只进子图</b>（与 parallel 分支同纪律）。
 * 这里用<b>真 builder（无依赖）+ 桩执行器 + mock traceRecorder</b> 做纯编译（{@code MemorySaver}，不执行图、
 * 不碰真 DB / 模型），断言：
 * <ol>
 *   <li>① 形状：含 iteration 的父 DSL 编译成功；🔴 SAA 会把子图<b>内联</b>进父 mermaid，内部节点以
 *       {@code <iterId>-} 命名空间出现（{@code iter-_guard}/{@code iter-describe}/{@code iter-_advance} + 回边），
 *       故 body 节点 describe <b>不以裸父图节点</b>出现、只作为 {@code iter-describe} 存在于子图；</li>
 *   <li>② 编译期契约拒绝：concurrency&gt;1 / body 引用不存在节点 / body 含控制流节点 / body 写进 edges[] /
 *       缺 itemsVar / 缺 itemVar / body 为空 / 两个 iteration 共享同一 body 节点 → 均抛 {@code WORKFLOW_DSL_INVALID}；</li>
 *   <li>③ 回归：无 iteration 的普通 DSL 仍编译通过（确认接线零副作用）。</li>
 * </ol>
 *
 * @author 走进新时代
 */
class DslToStateGraphCompilerIterationTest {

    private DslToStateGraphCompiler compiler;
    private DslParser parser;

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
                new StubExecutor("trigger.chat"),
                new StubExecutor("llm"),
                new StubExecutor("tool"),
                new StubExecutor("knowledge_retrieval")));

        compiler = new DslToStateGraphCompiler();
        ReflectionTestUtils.setField(compiler, "registry", registry);
        ReflectionTestUtils.setField(compiler, "traceRecorder", mock(NodeTraceRecorder.class));
        ReflectionTestUtils.setField(compiler, "defService", mock(AgentWorkflowDefService.class));
        ReflectionTestUtils.setField(compiler, "dslParser", parser);
        // iteration builder 无任何注入依赖，new 出来即用
        ReflectionTestUtils.setField(compiler, "iterationGraphBuilder", new IterationGraphBuilder());
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

    /** 标准 iteration 父 DSL：trigger→iter(body=[describe])→END，describe 是 tool 节点、写在 nodes[] 但不在 edges[] */
    private static String baseDsl(String iterConfigJson) {
        return """
                {"dslVersion":"1.0","id":"wf_iter_demo","name":"iteration 演示",
                 "variables":[
                   {"key":"tables","type":"array","merge":"replace"},
                   {"key":"structures","type":"array","merge":"append"}
                 ],
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"iter","type":"iteration","config":%s},
                   {"id":"describe","type":"tool","config":{"toolId":"datamodel_structure","outputVar":"structures"}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},
                   {"from":"trigger","to":"iter"},
                   {"from":"iter","to":"END"}
                 ]}
                """.formatted(iterConfigJson);
    }

    private static final String OK_CONFIG =
            "{\"itemsVar\":\"tables\",\"itemVar\":\"row\",\"body\":[\"describe\"],\"concurrency\":1}";

    // ==================== ① 形状：iteration 子图内联进父 mermaid，body 节点不以裸父图节点出现 ====================

    @Test
    void compile_iterationAsChildGraph_inlinedWithNamespace_bodyExcludedFromParent() throws Exception {
        CompiledGraph graph = compile(baseDsl(OK_CONFIG));
        assertNotNull(graph, "含 iteration 的父图应编译成功");
        String m = mermaid(graph);
        System.out.println("[iteration-shape] 父 mermaid =\n" + m);
        // 🔴 SAA 会把子图内联进父 mermaid，内部节点以 <iterId>- 命名空间出现（与 trace 前缀/SSE 节点 id 一致）：
        //    故 iteration 不是「裸单节点」，而是展开成 iter-_guard / iter-describe / iter-_advance + 回边。
        assertTrue(m.contains("iter-_guard"), "子图守卫节点应以 iter- 命名空间出现，实际：\n" + m);
        assertTrue(m.contains("iter-_advance"), "子图推进节点应以 iter- 命名空间出现，实际：\n" + m);
        assertTrue(m.contains("iter-describe"),
                "body 节点应以 iter- 命名空间出现在子图里（与 trace 前缀/SSE 节点 id 一致），实际：\n" + m);
        assertTrue(m.contains("trigger"), "父 mermaid 应含 trigger 节点，实际：\n" + m);
        // body 节点 describe 不得作为「裸父图节点」出现 —— 只能以命名空间 iter-describe 存在于子图。
        // 去掉命名空间形态后，父图里不应再残留 describe。
        assertFalse(m.replace("iter-describe", "").contains("describe"),
                "body 节点 describe 应被排除出父图（只以 iter-describe 命名空间存在于子图），却出现裸 describe：\n" + m);
    }

    // ==================== ② 编译期契约拒绝 ====================

    @Test
    void compile_concurrencyGreaterThanOne_rejected() {
        String cfg = "{\"itemsVar\":\"tables\",\"itemVar\":\"row\",\"body\":[\"describe\"],\"concurrency\":2}";
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(baseDsl(cfg)));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_bodyReferencesMissingNode_rejected() {
        String cfg = "{\"itemsVar\":\"tables\",\"itemVar\":\"row\",\"body\":[\"ghost\"],\"concurrency\":1}";
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(baseDsl(cfg)));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_missingItemsVar_rejected() {
        String cfg = "{\"itemVar\":\"row\",\"body\":[\"describe\"],\"concurrency\":1}";
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(baseDsl(cfg)));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_missingItemVar_rejected() {
        String cfg = "{\"itemsVar\":\"tables\",\"body\":[\"describe\"],\"concurrency\":1}";
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(baseDsl(cfg)));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    /**
     * 🔴 E5（台账 #103）：itemVar 填了「不是合法变量名」的值 → 编译期拒。
     *
     * <p>为什么必须在编译期：{@code IterationGraphBuilder} 的 guard 是<b>原样</b> {@code delta.put(itemVar, …)}
     * （{@code NodeDTO.configStr} 也不 trim），而循环体读它走占位符 {@code {{$.row}}}，那个正则
     * {@code \{\{\s*([^}]+?)\s*}} 吃掉的是<b>读取侧</b>的空白 ⟹ 填 {@code " row "} 时运行期写的键是
     * {@code " row "}、读的键是 {@code row}，两者对不上，body 每轮静默拿到空。
     * 这正是平台头号缺陷「配了不生效且无提示」的形状，故 fail-fast。
     *
     * <p>{@code "__iter_iter_route"} 那个样本另钉一件事：本子图自己用 {@code __iter_<id>_index/route}
     * 记循环状态，itemVar 若也叫这个名就会和 guard 写的路由值互相覆盖 ⟹ 循环可能失控。
     * 判据是 {@code DslToStateGraphCompiler.VAR_NAME} 的「字母开头」，一条即排除全部 {@code __} 前缀。
     */
    @Test
    void compile_illegalItemVar_rejected() {
        for (String bad : List.of(" row ", "__iter_iter_route", "1row", "row-item")) {
            String cfg = "{\"itemsVar\":\"tables\",\"itemVar\":\"" + bad
                    + "\",\"body\":[\"describe\"],\"concurrency\":1}";
            ServiceException ex = assertThrows(ServiceException.class, () -> compile(baseDsl(cfg)),
                    "itemVar=[" + bad + "] 应在编译期被拒");
            assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode(), "itemVar=[" + bad + "]：" + ex.getMessage());
            assertTrue(ex.getMessage().contains(bad),
                    "文案要把作者填的那个名字原样打出来，否则他不知道该改哪一格；itemVar=[" + bad
                            + "] 实际：" + ex.getMessage());
        }
    }

    /**
     * 🟡 非全小写的 itemVar 必须<b>照常放行</b>：{@code VAR_NAME} 刻意只要求「字母开头」，
     * 不照搬节点 id 的「仅小写」——因为 {@code outputVar}/{@code variables[].key} 全系统都不强制小写。
     * 这条是「哪天有人把 itemVar 也收成仅小写」的绊线（那会造出 outputVar 与 itemVar 口径割裂）。
     *
     * <p>🔴 两个样本各钉一半：{@code "myRow"} 钉「大写出现在非首字符」、{@code "MyRow"} 钉「大写出现在首字符」。
     * 只有前者是不够的 —— 实测（2026-10-05）把 {@code VAR_NAME} 改成 {@code ^[a-z][a-zA-Z0-9_]{0,62}$}
     * （只禁大写开头）时，全类<b>一个不红</b>，那次变异因此毫无证明力。
     */
    @Test
    void compile_itemVarNotForcedLowercase_accepted() throws Exception {
        for (String name : List.of("myRow", "MyRow")) {
            String cfg = "{\"itemsVar\":\"tables\",\"itemVar\":\"" + name
                    + "\",\"body\":[\"describe\"],\"concurrency\":1}";
            CompiledGraph graph = compile(baseDsl(cfg));
            assertNotNull(graph, "itemVar=[" + name + "] 应编译成功（VAR_NAME 只要求字母开头，不强制小写）");
        }
    }

    /**
     * 🔴 干跑校验也必须探出非法 itemVar —— 这条钉的是「作者<b>多早</b>看见」，而不只是「会不会崩」。
     *
     * <p>{@code dryRunValidate} 走的是<b>同一个</b> {@code buildStateGraph}，区别只在于它把
     * {@code ServiceException} 收成一条 error 而不是抛出。而 error 在本系统是<b>发布闸门 fail-close</b>
     * （没有 force / skipValidation 后门），所以 E5 这道校验的实际落点<b>不止</b>「运行期建图失败」，
     * 而是「校验就红、发不出去、生成器会被回灌这条反馈去自修正」——
     * 管理页校验（{@code AgentWorkflowDefServiceImpl#validateDsl}）、生成器
     * （{@code WorkflowPlannerServiceImpl}）、沙盒、执行四个调用点吃的是同一份结果。
     */
    @Test
    void dryRunValidate_illegalItemVar_capturedAsError() throws Exception {
        WorkflowDefDTO def = parser.parse(baseDsl(
                "{\"itemsVar\":\"tables\",\"itemVar\":\" row \",\"body\":[\"describe\"],\"concurrency\":1}"));

        AgentValidateResult r = compiler.dryRunValidate(def, AgentExecutionContext.ofForTest(1L, 1L));

        assertFalse(r.isPassed(), "非法 itemVar 应被干跑探出为 error（发布闸门据此 fail-close）");
        assertTrue(String.join("|", r.getErrors()).contains("itemVar"),
                "error 文案要点名 itemVar，作者/模型才知道改哪一格；实际：" + r.toFeedback());
        assertTrue(String.join("|", r.getErrors()).contains(" row "),
                "error 文案要把填错的那个值原样带出来（含空格），否则「改了但没改对」无从对照；实际："
                        + r.toFeedback());
    }

    @Test
    void compile_emptyBody_rejected() {
        String cfg = "{\"itemsVar\":\"tables\",\"itemVar\":\"row\",\"body\":[],\"concurrency\":1}";
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(baseDsl(cfg)));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_bodyContainsControlFlowNode_rejected() {
        // body 引用一个 condition.rule 节点：子图内嵌条件边留后续增量，编译期拒
        String dsl = """
                {"dslVersion":"1.0","id":"wf_iter_cf","name":"iter",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"iter","type":"iteration","config":{"itemsVar":"tables","itemVar":"row","body":["cond"],"concurrency":1}},
                   {"id":"cond","type":"condition.rule","config":{}}
                 ],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"iter"},{"from":"iter","to":"END"}]}
                """;
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(dsl));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_bodyNodeAlsoInEdges_rejected() {
        // describe 既是 body 又手写进 edges[]：连边应由子图生成，冲突 → 拒
        String dsl = """
                {"dslVersion":"1.0","id":"wf_iter_edge","name":"iter",
                 "variables":[{"key":"tables","type":"array","merge":"replace"},{"key":"structures","type":"array","merge":"append"}],
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"iter","type":"iteration","config":{"itemsVar":"tables","itemVar":"row","body":["describe"],"concurrency":1}},
                   {"id":"describe","type":"tool","config":{"toolId":"datamodel_structure","outputVar":"structures"}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},{"from":"trigger","to":"iter"},
                   {"from":"iter","to":"describe"},{"from":"describe","to":"END"}
                 ]}
                """;
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(dsl));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_twoIterationsShareBodyNode_rejected() {
        // 两个 iteration 都把 describe 当 body：一个节点只能属于一条 body 链 → 拒
        String dsl = """
                {"dslVersion":"1.0","id":"wf_iter_dup","name":"iter",
                 "variables":[{"key":"tables","type":"array","merge":"replace"},{"key":"structures","type":"array","merge":"append"}],
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"iter1","type":"iteration","config":{"itemsVar":"tables","itemVar":"row","body":["describe"],"concurrency":1}},
                   {"id":"iter2","type":"iteration","config":{"itemsVar":"tables","itemVar":"row","body":["describe"],"concurrency":1}},
                   {"id":"describe","type":"tool","config":{"toolId":"datamodel_structure","outputVar":"structures"}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},{"from":"trigger","to":"iter1"},
                   {"from":"iter1","to":"iter2"},{"from":"iter2","to":"END"}
                 ]}
                """;
        ServiceException ex = assertThrows(ServiceException.class, () -> compile(dsl));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    // ==================== ③ 回归：无 iteration 的普通 DSL 不受影响 ====================

    @Test
    void compile_noIteration_plainDslStillWorks() throws Exception {
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
