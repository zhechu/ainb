package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.module.agent.framework.agent.config.AgentLoopProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trace.NodeTraceRecorder;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.loop.IterationGraphBuilder;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 子图节点落 trace 时的<b>命名空间</b>与<b>父节点 id</b>：两者必须同源，且命名空间必须与 SAA 的一致
 *
 * <p>迭代六 §13.1 合流对② 的验证面（B5-1 加 {@code parent_node_id} 列 + D2 加 {@code tool_id}/{@code node_name}）。
 * {@code NodeTraceRecorderTest} 守的是记录器自己那半（截断、不把父 id 从截断过的串里切回来）；
 * 本类守的是<b>编译器那半</b>：{@code wrap} 到底传了什么下去。
 *
 * <h3>🔴 为什么非要真跑一遍图，纯编译断形状不够</h3>
 * {@code DslToStateGraphCompilerIterationTest} 已经断过 mermaid 里有 {@code iter-describe}，
 * 但那个串是 <b>SAA 自己</b>拼的（{@code addNode(id, childGraph)} 时按 {@code formatId(%s-%s)} 给子节点加前缀），
 * 与 {@code DslToStateGraphCompiler.TRACE_NAMESPACE_SEPARATOR} 是<b>两个字面量</b>。
 * 把后者改成 {@code ":"}：编译照过、mermaid 照旧、既有单测全绿，
 * 而 trace 行的 node_id 变成 {@code iter:describe}，与父 SSE 上浮的事件 id {@code iter-describe} 对不上 ——
 * 前端按事件 id 找 trace 行会全落空，且<b>没有任何一处报错</b>。
 * 所以这里必须真跑：把 SAA 实际发出的 {@code NodeOutput.node()} 与 trace 实际记下的 node_id 摆在一起比。
 *
 * <h3>判据</h3>
 * <ol>
 *   <li>① body 节点的 trace node_id 与 SAA 发出的子节点 id <b>逐字相同</b>（命名空间对齐）；</li>
 *   <li>② body 节点的 {@code parent_node_id} 是<b>裸</b>父 id {@code iter} —— 不是前缀 {@code iter-}，
 *       也不是靠切 {@code node_id} 得到的（裁定 A-Ⅱ 明确不要字符串命名空间那套）；</li>
 *   <li>③ 顶层节点的 {@code parent_node_id} 为 {@code null}，trace node_id 就是裸 id（不被无端加前缀）。</li>
 * </ol>
 *
 * <h3>🔴 本类没有覆盖到的</h3>
 * sub_workflow 与 agent_loop 走的是同一个分隔符，但各自需要另一套夹具（前者要 mock {@code AgentWorkflowDefService}
 * 喂子 DSL，后者要一个能产出 ReAct 结构的 llm 桩），这里没有重复搭。
 * 三处共用 {@code TRACE_NAMESPACE_SEPARATOR} 这一个常量是本类的覆盖能外推的<b>唯一</b>理由 ——
 * 哪天有人给某一处另拼一个字面量，本类不会变红。
 *
 * @author 走进新时代
 */
@DisplayName("agent 子图 trace 命名空间：必须与 SAA 的节点 id 一致，且父 id 单独落列")
class SubGraphTraceNamespaceRuntimeTest {

    private DslToStateGraphCompiler compiler;
    private DslParser parser;
    private NodeTraceRecorder traceRecorder;

    /** 桩执行器：只声明 type，execute 返回空 delta（本类只关心 trace 的 begin 参数，不关心业务产出） */
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

    /** trigger→iter(body=[describe])→END；describe 是 tool 节点，写在 nodes[] 但不在 edges[]（由子图接线） */
    private static final String ITERATION_DSL = """
            {"dslVersion":"1.0","id":"wf_iter_trace","name":"trace 命名空间",
             "variables":[
               {"key":"tables","type":"array","merge":"replace"},
               {"key":"structures","type":"array","merge":"append"}
             ],
             "nodes":[
               {"id":"trigger","type":"trigger.chat","name":"入口"},
               {"id":"iter","type":"iteration","name":"逐表处理",
                "config":{"itemsVar":"tables","itemVar":"row","body":["describe"],"concurrency":1}},
               {"id":"describe","type":"tool","name":"取结构",
                "config":{"toolId":"datamodel_structure","outputVar":"structures"}}
             ],
             "edges":[
               {"from":"START","to":"trigger"},
               {"from":"trigger","to":"iter"},
               {"from":"iter","to":"END"}
             ]}
            """;

    @BeforeEach
    void setUp() {
        parser = new DslParser();
        ReflectionTestUtils.setField(parser, "objectMapper", new ObjectMapper());

        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        registry.setExecutors(List.of(
                new StubExecutor("trigger.chat"),
                new StubExecutor("tool")));

        // 🔴 必须是字段而不是内联 mock：判据全靠 verify/ArgumentCaptor 读 begin 的实参
        traceRecorder = mock(NodeTraceRecorder.class);

        compiler = new DslToStateGraphCompiler();
        ReflectionTestUtils.setField(compiler, "registry", registry);
        ReflectionTestUtils.setField(compiler, "traceRecorder", traceRecorder);
        ReflectionTestUtils.setField(compiler, "defService", mock(AgentWorkflowDefService.class));
        ReflectionTestUtils.setField(compiler, "dslParser", parser);
        ReflectionTestUtils.setField(compiler, "iterationGraphBuilder", new IterationGraphBuilder());
        ReflectionTestUtils.setField(compiler, "loopProperties", new AgentLoopProperties());
    }

    /** 真跑一遍，返回 SAA 实际发出的全部 NodeOutput */
    private List<NodeOutput> run() throws Exception {
        WorkflowDefDTO def = parser.parse(ITERATION_DSL);
        CompiledGraph graph = compiler.compile(def, 1L, "t1:1",
                AgentExecutionContext.ofForTest(1L, 1L),
                new MemorySaver(), ObservationRegistry.NOOP);
        // tables 必须非空：iteration 的 _guard 见到空列表会直接出口，body 一次都不跑 ⟹ begin 根本不会被调用，
        // 那样三条判据会全部「通过」而实际上什么都没验（恒绿）
        return graph.stream(Map.of("tables", List.of(Map.of("name", "act_evt_log"))),
                        RunnableConfig.builder().threadId("t1:1").build())
                .collectList().block();
    }

    /** 捕获 begin 的 (effectiveNodeId, parentNodeId) 两列实参 */
    private Map<String, String> capturedBeginArgs() {
        ArgumentCaptor<String> nodeId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> parentId = ArgumentCaptor.forClass(String.class);
        verify(traceRecorder, atLeastOnce())
                .begin(any(WorkflowContext.class), any(NodeDTO.class), nodeId.capture(), parentId.capture());
        Map<String, String> pairs = new LinkedHashMap<>();
        List<String> parents = parentId.getAllValues();
        List<String> ids = nodeId.getAllValues();
        for (int i = 0; i < ids.size(); i++) {
            pairs.put(ids.get(i), parents.get(i));
        }
        return pairs;
    }

    @Test
    @DisplayName("① 子图 body 节点的 trace node_id 与 SAA 发出的节点 id 逐字相同")
    void traceNodeId_matchesSaaNamespacedNodeId() throws Exception {
        List<NodeOutput> outputs = run();
        Set<String> saaNodeIds = new LinkedHashSet<>();
        outputs.forEach(o -> {
            if (o.node() != null) {
                saaNodeIds.add(o.node());
            }
        });

        Map<String, String> traced = capturedBeginArgs();
        // 前置断言：body 真的跑了。少了这一句，下面几条在「body 没跑」时也会绿
        assertTrue(traced.containsKey("iter-describe"),
                "body 节点应以 iter- 命名空间落 trace，实际记下的是 " + traced.keySet());
        assertTrue(saaNodeIds.contains("iter-describe"),
                "SAA 也应以同名 id 上浮 body 节点事件，实际 " + saaNodeIds);
        // 核心判据：trace 记的 id 必须在 SAA 发出的 id 集合里 —— 这才是「按事件 id 能找到 trace 行」的充要条件
        assertTrue(saaNodeIds.containsAll(traced.keySet()),
                "trace 记下的 node_id 必须都能在 SAA 上浮的事件 id 里找到，否则前端对不上号。\n"
                        + "trace=" + traced.keySet() + "\nSAA=" + saaNodeIds);
    }

    @Test
    @DisplayName("② parent_node_id 是裸父 id；③ 顶层节点为 null 且不加前缀")
    void parentNodeId_isBareIdForChildren_andNullAtTopLevel() throws Exception {
        run();

        Map<String, String> traced = capturedBeginArgs();
        assertEquals("iter", traced.get("iter-describe"),
                "body 节点的 parent_node_id 必须是裸父 id，不是前缀 'iter-'");
        assertTrue(traced.containsKey("trigger"), "顶层 trigger 节点也应落 trace，实际 " + traced.keySet());
        assertNull(traced.get("trigger"),
                "顶层节点的 parent_node_id 必须是 null（不属于任何子图）");
        assertTrue(traced.keySet().stream().noneMatch(id -> id.startsWith("iter-trigger")),
                "顶层节点不得被无端套上子图前缀，实际 " + traced.keySet());
    }

}
