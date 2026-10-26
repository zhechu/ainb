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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link DslToStateGraphCompiler} 的 parallel 展开单测（P2 增量2）
 *
 * <p>重点：parallel 节点在<b>建图期</b>被展开成 SAA 原生 fan-out/fan-in —— 分支节点写在顶层 nodes[]，
 * 其连边不写进 edges[]，全由 {@code expandParallel} 生成。这里用 {@code MemorySaver} + 桩执行器 +
 * mock traceRecorder 做<b>纯编译</b>（不执行图、不碰 DB），断言：
 * ① 图形状（MERMAID 里 fan-out/链内/fan-in 边都在）；② 7 类编译期校验按预期抛 WORKFLOW_DSL_INVALID；
 * ③ 无 parallel 的普通 DSL 仍编译通过（回归，确认第 2 步改动没破坏既有静态/条件边路径）。
 *
 * @author 走进新时代
 */
class DslToStateGraphCompilerParallelTest {

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
                new ParallelNodeExecutor(),
                new StubExecutor("trigger.chat"),
                new StubExecutor("knowledge_retrieval"),
                new StubExecutor("llm"),
                new StubExecutor("tool")));

        compiler = new DslToStateGraphCompiler();
        ReflectionTestUtils.setField(compiler, "registry", registry);
        // 形状测试只编译不执行，traceRecorder 不会触发，用 mock 占位即可
        ReflectionTestUtils.setField(compiler, "traceRecorder", mock(NodeTraceRecorder.class));
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

    // ==================== 形状：fan-out / 链内 / fan-in ====================

    @Test
    void compile_parallelTwoSingleNodeBranches_generatesFanOutAndFanIn() throws Exception {
        String dsl = """
                {"dslVersion":"1.0","id":"wf_par","name":"并行",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"par","type":"parallel","config":{"branches":[["kr_a"],["kr_b"]],"join":"all"}},
                   {"id":"kr_a","type":"knowledge_retrieval","config":{"outputVar":"outA"}},
                   {"id":"kr_b","type":"knowledge_retrieval","config":{"outputVar":"outB"}},
                   {"id":"merge","type":"llm","config":{"prompt":"汇总"}}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},
                   {"from":"trigger","to":"par"},
                   {"from":"par","to":"merge"},
                   {"from":"merge","to":"END"}
                 ]}
                """;
        String m = mermaid(compile(dsl));

        // fan-out：par 分发到两个分支节点（同一 superstep 并发）
        assertTrue(m.contains("par:::par --> kr_a"), "应有 fan-out 边 par→kr_a，实际：\n" + m);
        assertTrue(m.contains("par:::par --> kr_b"), "应有 fan-out 边 par→kr_b，实际：\n" + m);
        // fan-in barrier：两分支汇聚到 join 目标 merge（join=all）
        assertTrue(m.contains("kr_a:::kr_a --> merge"), "应有 fan-in 边 kr_a→merge，实际：\n" + m);
        assertTrue(m.contains("kr_b:::kr_b --> merge"), "应有 fan-in 边 kr_b→merge，实际：\n" + m);
        // 入边与出口照常
        assertTrue(m.contains("trigger:::trigger --> par"), "应有入边 trigger→par，实际：\n" + m);
        assertTrue(m.contains("merge:::merge --> __END__"), "应有 merge→END，实际：\n" + m);
    }

    @Test
    void compile_multiNodeBranchChain_rejectedInMvp() {
        // 🔴 SAA 原生 fan-out/fan-in 要求各分支直接汇聚到同一 join，故 MVP 只支持单节点分支；
        //    多节点链（[a1,a2]）编译期拒绝，指向增量2b 的 sub_workflow。
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"par","type":"parallel","config":{"branches":[["a1","a2"],["b1"]]}},
                   {"id":"a1","type":"tool"},{"id":"a2","type":"llm"},{"id":"b1","type":"knowledge_retrieval"},
                   {"id":"merge","type":"llm"}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"par"},
                          {"from":"par","to":"merge"},{"from":"merge","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_parallelFanInToEnd_ok() throws Exception {
        // join 目标为 END：fan-in barrier 直接汇聚到 END
        String dsl = """
                {"dslVersion":"1.0","id":"wf_par_end","name":"并行到END",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"par","type":"parallel","config":{"branches":[["kr_a"],["kr_b"]]}},
                   {"id":"kr_a","type":"knowledge_retrieval"},
                   {"id":"kr_b","type":"knowledge_retrieval"}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},
                   {"from":"trigger","to":"par"},
                   {"from":"par","to":"END"}
                 ]}
                """;
        String m = mermaid(compile(dsl));
        // join 目标为 END：fan-in barrier 直接汇聚到 __END__
        assertTrue(m.contains("par:::par --> kr_a") && m.contains("par:::par --> kr_b"),
                "应有 fan-out 到两分支，实际：\n" + m);
        assertTrue(m.contains("kr_a:::kr_a --> __END__") && m.contains("kr_b:::kr_b --> __END__"),
                "应有 fan-in 到 END，实际：\n" + m);
    }

    // ==================== 校验拒绝 ====================

    @Test
    void compile_joinAny_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"par","type":"parallel","config":{"branches":[["a"],["b"]],"join":"any"}},
                   {"id":"a","type":"tool"},{"id":"b","type":"tool"}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"par"},{"from":"par","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_branchIdNotInNodes_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"par","type":"parallel","config":{"branches":[["ghost"],["b"]]}},
                   {"id":"b","type":"tool"}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"par"},{"from":"par","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_branchIdDuplicatedAcrossBranches_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"par","type":"parallel","config":{"branches":[["a"],["a"]]}},
                   {"id":"a","type":"tool"}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"par"},{"from":"par","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_branchIdHandWiredInEdges_rejected() {
        // 分支节点 a 又手写进了 edges[]，违反「其连边由 parallel 生成」
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"par","type":"parallel","config":{"branches":[["a"],["b"]]}},
                   {"id":"a","type":"tool"},{"id":"b","type":"tool"},{"id":"merge","type":"llm"}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"par"},
                          {"from":"par","to":"merge"},{"from":"a","to":"merge"},{"from":"merge","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_parallelNoOutEdge_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"par","type":"parallel","config":{"branches":[["a"],["b"]]}},
                   {"id":"a","type":"tool"},{"id":"b","type":"tool"}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"par"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_parallelTwoOutEdges_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"par","type":"parallel","config":{"branches":[["a"],["b"]]}},
                   {"id":"a","type":"tool"},{"id":"b","type":"tool"},{"id":"m1","type":"llm"},{"id":"m2","type":"llm"}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"par"},
                          {"from":"par","to":"m1"},{"from":"par","to":"m2"},{"from":"m1","to":"END"},{"from":"m2","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_branchesExceedMaxParallelism_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","settings":{"maxParallelism":2},
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"par","type":"parallel","config":{"branches":[["a"],["b"],["c"]]}},
                   {"id":"a","type":"tool"},{"id":"b","type":"tool"},{"id":"c","type":"tool"}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"par"},{"from":"par","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_nestedParallel_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"par","type":"parallel","config":{"branches":[["inner"],["b"]]}},
                   {"id":"inner","type":"parallel","config":{"branches":[["x"],["y"]]}},
                   {"id":"x","type":"tool"},{"id":"y","type":"tool"},{"id":"b","type":"tool"}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"par"},{"from":"par","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void compile_emptyBranches_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"par","type":"parallel","config":{"branches":[]}},
                   {"id":"a","type":"tool"}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"par"},{"from":"par","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    // ==================== 回归：无 parallel 的普通 DSL 不受影响 ====================

    @Test
    void compile_noParallel_staticAndConditionalStillWork() throws Exception {
        String dsl = """
                {"dslVersion":"1.0","id":"wf_plain","name":"普通",
                 "nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"llm1","type":"llm","config":{"prompt":"hi"}},
                   {"id":"cond","type":"condition.rule","config":{}},
                   {"id":"yes","type":"tool"},{"id":"no","type":"tool"}
                 ],
                 "edges":[
                   {"from":"START","to":"trigger"},
                   {"from":"trigger","to":"llm1"},
                   {"from":"llm1","to":"cond"},
                   {"from":"cond","type":"conditional","cases":{"y":"yes"},"default":"no"},
                   {"from":"yes","to":"END"},
                   {"from":"no","to":"END"}
                 ]}
                """;
        // condition.rule 需要执行器；这里只验编译路径不报 parallel 相关错
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        registry.setExecutors(List.of(
                new ParallelNodeExecutor(),
                new StubExecutor("trigger.chat"),
                new StubExecutor("llm"),
                new StubExecutor("tool"),
                new StubExecutor("condition.rule")));
        ReflectionTestUtils.setField(compiler, "registry", registry);

        String m = mermaid(compile(dsl));
        assertTrue(m.contains("llm1") && m.contains("cond"), "普通 DSL 应正常建图");
    }

}
