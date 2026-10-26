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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DslToStateGraphCompiler} 的 wait 节点<b>编译期</b>单测（P2 增量4a duration + 增量4b event）
 *
 * <p>纯编译（{@code MemorySaver} + 桩执行器 + mock traceRecorder/defService，不执行图、不碰真 DB），断言：
 * <ol>
 *   <li>① 合法 {@code wait(duration)} 与 {@code wait(event)} 均编译成功，节点与边链照常出现在 mermaid 里
 *       （wait 是普通节点，只是被登记进 {@code interruptsBefore}）</li>
 *   <li>② 编译期 fail-fast 拒绝：waitType 缺失 / 未知 / hitl（等价 hitl.confirm）/ duration 缺失 /
 *       duration<=0 / duration 非法串 / duration 超上限 / eventKey 缺失 / eventKey 空白 / eventKey 超长
 *       —— 均 {@code WORKFLOW_DSL_INVALID}</li>
 *   <li>③ 回归：wait（duration 或 event）出现在 sub_workflow 子图内被拒（跨子图 interrupt 未证，留增量2d）；
 *       出现在 iteration body 内被拒（既有黑名单）</li>
 * </ol>
 * 运行时「interruptBefore 挂起 → resume 续跑」见 {@code WaitNodeRuntimeTest}。
 *
 * @author 走进新时代
 */
class DslToStateGraphCompilerWaitTest {

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
                new StubExecutor("wait"),
                new StubExecutor("llm"),
                new StubExecutor("tool")));

        defService = mock(AgentWorkflowDefService.class);

        compiler = new DslToStateGraphCompiler();
        ReflectionTestUtils.setField(compiler, "registry", registry);
        ReflectionTestUtils.setField(compiler, "traceRecorder", mock(NodeTraceRecorder.class));
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

    private static AgentWorkflowDefDO childDef(String code, Integer version, String dsl) {
        AgentWorkflowDefDO d = new AgentWorkflowDefDO();
        d.setCode(code);
        d.setVersion(version);
        d.setStatus("PUBLISHED");
        d.setDsl(dsl);
        return d;
    }

    // ==================== ① 合法 wait 编译成功 ====================

    @Test
    void compile_waitDuration_embedsAsPlainNode() throws Exception {
        String dsl = """
                {"dslVersion":"1.0","id":"wf_wait","name":"定时等待",
                 "nodes":[
                   {"id":"w","type":"wait","name":"等3秒","config":{"waitType":"duration","duration":3}},
                   {"id":"after","type":"tool","config":{"toolId":"datamodel_structure","outputVar":"structOut"}}
                 ],
                 "edges":[
                   {"from":"START","to":"w"},
                   {"from":"w","to":"after"},
                   {"from":"after","to":"END"}
                 ]}
                """;
        CompiledGraph graph = compile(dsl);
        assertNotNull(graph, "合法 wait(duration) 应编译成功");
        String m = mermaid(graph);
        assertTrue(m.contains("w") && m.contains("after"),
                "wait 应作为普通节点出现在 mermaid，实际：\n" + m);
    }

    @Test
    void compile_waitDurationIso8601String_ok() throws Exception {
        // duration 允许 ISO-8601 串（PT3S）
        String dsl = """
                {"id":"wf_wait","nodes":[
                   {"id":"w","type":"wait","config":{"waitType":"duration","duration":"PT3S"}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """;
        assertNotNull(compile(dsl), "ISO-8601 duration 应编译成功");
    }

    // ==================== ② 编译期 fail-fast 拒绝 ====================

    @Test
    void compile_waitTypeMissing_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"w","type":"wait","config":{"duration":3}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    @Test
    void compile_waitTypeEvent_ok() throws Exception {
        // 增量4b：wait(event) 现在合法——同样是普通节点 + interruptsBefore 挂起，只是唤醒靠 POST /event 而非轮询
        String dsl = """
                {"id":"wf_wait_event","name":"等外部事件",
                 "nodes":[
                   {"id":"w","type":"wait","name":"等 demo_ready","config":{"waitType":"event","eventKey":"demo_ready"}},
                   {"id":"after","type":"tool","config":{"toolId":"datamodel_structure","outputVar":"structOut"}}
                 ],
                 "edges":[
                   {"from":"START","to":"w"},
                   {"from":"w","to":"after"},
                   {"from":"after","to":"END"}
                 ]}
                """;
        CompiledGraph graph = compile(dsl);
        assertNotNull(graph, "合法 wait(event) 应编译成功");
        String m = mermaid(graph);
        assertTrue(m.contains("w") && m.contains("after"),
                "wait(event) 应作为普通节点出现在 mermaid，实际：\n" + m);
    }

    @Test
    void compile_waitTypeHitl_rejected() {
        // wait(hitl) 等价 hitl.confirm 节点，不做（请作者直接用 hitl.confirm）
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"w","type":"wait","config":{"waitType":"hitl"}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    @Test
    void compile_waitUnknownType_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"w","type":"wait","config":{"waitType":"telepathy"}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    // ==================== ②b 增量4b：event 型 fail-fast 拒绝 ====================

    @Test
    void compile_waitEventKeyMissing_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"w","type":"wait","config":{"waitType":"event"}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    @Test
    void compile_waitEventKeyBlank_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"w","type":"wait","config":{"waitType":"event","eventKey":"   "}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    @Test
    void compile_waitEventKeyTooLong_rejected() {
        // eventKey 上限 128（与 DDL varchar(128) 对齐），给 129 个字符应拒
        String longKey = "k".repeat(129);
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"w","type":"wait","config":{"waitType":"event","eventKey":"%s"}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """.formatted(longKey)));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    @Test
    void compile_durationMissing_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"w","type":"wait","config":{"waitType":"duration"}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    @Test
    void compile_durationZero_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"w","type":"wait","config":{"waitType":"duration","duration":0}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    @Test
    void compile_durationNegative_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"w","type":"wait","config":{"waitType":"duration","duration":-5}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    @Test
    void compile_durationUnparseable_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"w","type":"wait","config":{"waitType":"duration","duration":"three-seconds"}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    @Test
    void compile_durationExceedsMax_rejected() {
        // 上限 7 天 = 604800 秒，超出（这里给 8 天）应拒
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"w","type":"wait","config":{"waitType":"duration","duration":691200}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    // ==================== ③ 回归：wait 不能在 sub_workflow 子图 / iteration body 内 ====================

    @Test
    void compile_waitInsideSubWorkflowChild_rejected() {
        String childWithWait = """
                {"id":"wf_child_wait","name":"含 wait",
                 "nodes":[
                   {"id":"ctrigger","type":"trigger.chat"},
                   {"id":"cw","type":"wait","config":{"waitType":"duration","duration":3}}
                 ],
                 "edges":[
                   {"from":"START","to":"ctrigger"},
                   {"from":"ctrigger","to":"cw"},
                   {"from":"cw","to":"END"}
                 ]}
                """;
        when(defService.getSubWorkflowDef("wf_child_wait", 1))
                .thenReturn(childDef("wf_child_wait", 1, childWithWait));
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_child_wait","version":1}}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"sw"},{"from":"sw","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    @Test
    void compile_waitEventInsideSubWorkflowChild_rejected() {
        // 增量4b：event-wait 也是 wait 节点，子图内同样拒（按 type 判定，覆盖所有 waitType）
        String childWithEventWait = """
                {"id":"wf_child_evt","name":"含 event-wait",
                 "nodes":[
                   {"id":"ctrigger","type":"trigger.chat"},
                   {"id":"cw","type":"wait","config":{"waitType":"event","eventKey":"k"}}
                 ],
                 "edges":[
                   {"from":"START","to":"ctrigger"},
                   {"from":"ctrigger","to":"cw"},
                   {"from":"cw","to":"END"}
                 ]}
                """;
        when(defService.getSubWorkflowDef("wf_child_evt", 1))
                .thenReturn(childDef("wf_child_evt", 1, childWithEventWait));
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"sw","type":"sub_workflow","config":{"workflowId":"wf_child_evt","version":1}}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"sw"},{"from":"sw","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    @Test
    void compile_waitInsideIterationBody_rejected() {
        // iteration body 引用了 wait 节点 → 既有黑名单（isControlFlowBodyType）拒绝
        ServiceException ex = assertThrows(ServiceException.class, () -> compile("""
                {"id":"wf","nodes":[
                   {"id":"trigger","type":"trigger.chat"},
                   {"id":"it","type":"iteration","config":{"itemsVar":"items","itemVar":"item","body":["bw"]}},
                   {"id":"bw","type":"wait","config":{"waitType":"duration","duration":3}}],
                 "edges":[{"from":"START","to":"trigger"},{"from":"trigger","to":"it"},{"from":"it","to":"END"}]}
                """));
        assertEquals(WORKFLOW_DSL_INVALID_CODE, ex.getCode());
    }

    /** WORKFLOW_DSL_INVALID 的 code（避免多引入一处静态 import，直接取常量） */
    private static final Integer WORKFLOW_DSL_INVALID_CODE =
            cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID.getCode();

}
