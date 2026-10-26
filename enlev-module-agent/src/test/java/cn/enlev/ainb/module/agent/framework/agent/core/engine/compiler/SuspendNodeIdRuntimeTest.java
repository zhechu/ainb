package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlConfirmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.LlmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ParallelNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ToolNodeExecutor;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 「这一次挂起在<b>哪个</b>节点」的运行时单测（真编译器 + 真挂起/恢复，0 模型 token）
 *
 * <h3>钉住的缺陷：{@code InterruptionMetadata.node()} 不是挂起节点，是<b>最后一个跑完的</b>节点</h3>
 *
 * <p>{@code AgentWorkflowExecutionServiceImpl} 的挂起分支拿这个 id 回 DSL 里按
 * {@code id} + {@code type} <b>双匹配</b>查节点，据此算 {@code resume_at} / {@code event_key} /
 * 发起 BPM 审批。id 拿成前一个节点 ⟹ 三条<b>全部匹配不上</b>，而它们匹配不上时都是<b>静默返回 null</b>：
 * <ul>
 *   <li>{@code wait(duration)} ⟹ {@code resume_at} 不落库 ⟹ 轮询器永远扫不到 ⟹ 静默卡在 INTERRUPTED</li>
 *   <li>{@code wait(event)} ⟹ {@code event_key} 不落库 ⟹ {@code POST /agent/workflow/event} 永远匹配不上 ⟹ 同上</li>
 *   <li>{@code hitl.bpm_approval} ⟹ <b>审批实例压根不发起</b> ⟹ 审批人看不到任务 ⟹ 卡死且<b>无人能恢复</b></li>
 * </ul>
 *
 * <p>⚠️ {@code hitl.confirm} 反倒没事：聊天内恢复走 {@code resume-stream}，这三样一个都不需要。
 * 它因此成了掩护 —— 手工确认一直好用，让人以为整条挂起/恢复链都是好的。
 *
 * <h3>🔴 为什么这个前提活了这么久：既有单测的 DSL 恰好全是「挂起节点是 START 之后第一个」</h3>
 * {@code WaitNodeRuntimeTest}（{@code START→w}）、{@code HitlConfirmResumeRuntimeTest}（{@code START→gate}）、
 * {@code HitlBpmApprovalResumeRuntimeTest}（{@code START→gate}）—— 三条都是那个<b>唯一碰巧正确</b>的形状，
 * 而且都断言了 {@code node()}。真实编排里挂起节点前面几乎总有 llm/tool/condition，于是生产走的是另一条路。
 * 本类第 ① 组就是把四种形状摆在一起量一遍。
 *
 * <h3>修法</h3>
 * 从 checkpoint 快照取 {@code next()}，即 {@link DslToStateGraphCompiler#resolveSuspendNodeId}。
 * 🔴 取不到快照时<b>刻意返回 null 而不回落成 {@code node()}</b>：回落值是个错值，
 * 落库/回显一个错节点比留 NULL 更坏 —— NULL 只是「说不清在哪恢复」，
 * 错值会让救援的人去恢复一个<b>早就跑完了</b>的节点，还确信自己是对的。
 *
 * <h3>🔴 负控（2026-10-06 实测，非空洞）</h3>
 * 把 {@code resolveSuspendNodeId} 的方法引用从 {@code StateSnapshot::next} 改成
 * {@code StateSnapshot::node}（即退回「用快照里那个跑完了的节点」）：本类 4 条里 <b>3 条红</b>
 * （{@code 形状 waitFirst：expected <w> but was <__START__>}、
 * {@code 第一次挂起：next() 也应是 gate} 同样拿到 {@code __START__}、
 * {@code 双匹配应命中 wait 节点…expected <wait> but was <tool>}），
 * {@code KeyStrategyResumeRuntimeTest} 另有 3 条经 {@code runUntilSuspended} 红；
 * {@code AgentWorkflowExecutionServiceImplWaitResolveTest} <b>全绿</b>（它自己把 nodeId 传进去，
 * 不经过这个方法）—— 红的位置正好圈出「取值口径」这一件事。已机械还原并核对 sha256 与 numstat。
 *
 * <p>⚠️ 顺带量到一条实测表里没写的：形状 S1（{@code START→w}）下 {@code StateSnapshot.node()} 是
 * {@code __START__} 而<b>不是</b> {@code w}，即快照的 {@code node()} 与 {@code InterruptionMetadata.node()}
 * 在这个形状下也不相等。所以「错值」并不总是「前一个业务节点」，它还可能是一个引擎内部名 ——
 * 这反而让回落方案更糟：{@code __START__} 拿去双匹配一定落空，且看不出是哪种落空。
 *
 * <h3>🔴 本类<b>没有</b>覆盖到的那一半（别误以为覆盖到了）</h3>
 * 修法有两半：①「取 {@code next()}」——本类钉住；②「两处 Service 改成调这个方法，而不是就地拿
 * {@code output.node()}」——<b>没有任何单测钉住</b>。②只能靠 e2e（真实挂起 + 查 {@code resume_at}/
 * {@code event_key} 落库）验证；把②退回 {@code output.node()} 不会让本类或全模块任何一条变红。
 *
 * @author 走进新时代
 */
@DisplayName("agent 挂起节点 id：必须取 checkpoint 快照的 next()，不能用 InterruptionMetadata.node()")
class SuspendNodeIdRuntimeTest {

    private DslToStateGraphCompiler compiler;
    private DslParser parser;
    private CaptureExecutor captureExecutor;

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

    /**
     * 桩：按<b>真执行器的取键方式</b>写一个键 —— {@code configStr("outputVar", 缺省名)}。
     * 只为让挂起节点前面有「跑完了的节点」，写什么键不重要。
     */
    private static class WritingStubExecutor implements NodeExecutor {
        private final String type;
        private final String defaultKey;

        WritingStubExecutor(String type, String defaultKey) {
            this.type = type;
            this.defaultKey = defaultKey;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
            return Map.of(node.configStr("outputVar", defaultKey), node.configStr("stubValue", type));
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
                // 🔴 hitl.confirm 用<b>真执行器</b>，不用桩：它既要当挂起节点（形状 4），
                //    又要当「跑完了的前一个节点」（③ 里第二次挂起）。用桩就证不到真执行器同样如此。
                new HitlConfirmNodeExecutor(),
                captureExecutor,
                new WritingStubExecutor("tool", ToolNodeExecutor.DEFAULT_OUTPUT_VAR),
                new WritingStubExecutor("llm", LlmNodeExecutor.DEFAULT_OUTPUT_VAR)));

        compiler = new DslToStateGraphCompiler();
        ReflectionTestUtils.setField(compiler, "registry", registry);
        ReflectionTestUtils.setField(compiler, "traceRecorder", mock(NodeTraceRecorder.class));
        ReflectionTestUtils.setField(compiler, "defService", mock(AgentWorkflowDefService.class));
        ReflectionTestUtils.setField(compiler, "dslParser", parser);
    }

    private static final String TOOL = """
            {"id":"t","type":"tool","config":{"stubValue":"T"}}""";
    private static final String LLM = """
            {"id":"m","type":"llm","config":{"stubValue":"L"}}""";
    private static final String WAIT = """
            {"id":"w","type":"wait","config":{"waitType":"duration","duration":3}}""";
    private static final String GATE = """
            {"id":"gate","type":"hitl.confirm","config":{}}""";
    private static final String CAP = """
            {"id":"cap","type":"capture","config":{"captureKeys":[],"outputVar":"done"}}""";

    private WorkflowDefDTO def(String id, String nodes, String edges) throws Exception {
        return parser.parse("""
                {"dslVersion":"1.0","id":"wf_%s","name":"挂起节点 id",
                 "variables":[{"key":"done","type":"string","merge":"replace"}],
                 "nodes":[%s],
                 "edges":[%s]}
                """.formatted(id, nodes, edges));
    }

    private CompiledGraph compile(WorkflowDefDTO def) throws Exception {
        return compiler.compile(def, 1L, "t1:1",
                AgentExecutionContext.ofForTest(1L, 1L),
                new MemorySaver(), ObservationRegistry.NOOP);
    }

    /** 跑到挂起，返回末元素；顺带断言它确实是中断、且下游 capture 没跑 */
    private InterruptionMetadata runUntilSuspended(CompiledGraph graph, RunnableConfig config) {
        List<NodeOutput> outs = graph.stream(Map.of(), config).collectList().block();
        assertNotNull(outs, "挂起时 stream 应正常结束，而非抛错");
        NodeOutput last = outs.get(outs.size() - 1);
        assertTrue(last instanceof InterruptionMetadata,
                "末元素应是 InterruptionMetadata（interruptBefore 挂起），实际：" + last.getClass());
        assertEquals(0, captureExecutor.runs.get(), "挂起时下游 capture 不应执行");
        return (InterruptionMetadata) last;
    }

    // ========== ① 四种形状一次量清：node() 何时碰巧对、何时指向前一个节点 ==========

    /**
     * 一条形状一行：{@code expectedSuspendId} 是 {@code interruptsBefore} 真正拦下的节点，
     * {@code expectedMetadataNode} 是 {@code InterruptionMetadata.node()} 实测给出的值。
     *
     * <p>🔴 两列<b>刻意分开写</b>、不合并成「都对」：第 1 行两列相同，正是既有三条单测覆盖的唯一形状，
     * 也是这个缺陷能长期隐身的原因；后三行两列不同，才是生产里的常态。
     * 只断言「{@code resolveSuspendNodeId} 对」而不把 {@code node()} 的错值也钉下来，
     * 将来 SAA 升级把 {@code node()} 修对了，本测试会<b>照样绿</b> —— 那时这段 javadoc 就成了谣言，
     * 而没人会知道该回来改。钉住错值，升级时它会红，读者被带到现场。
     */
    private record Shape(String label, String nodes, String edges,
                         String expectedSuspendId, String expectedMetadataNode, String suspendType) {
    }

    private static final List<Shape> SHAPES = List.of(
            // 1. 挂起节点是 START 之后第一个 —— 唯一「碰巧对」的形状，既有单测全在这里
            new Shape("waitFirst", WAIT + "," + CAP,
                    "{\"from\":\"START\",\"to\":\"w\"},{\"from\":\"w\",\"to\":\"cap\"},"
                            + "{\"from\":\"cap\",\"to\":\"END\"}",
                    "w", "w", "wait"),
            // 2. 前面一个 tool
            new Shape("toolThenWait", TOOL + "," + WAIT + "," + CAP,
                    "{\"from\":\"START\",\"to\":\"t\"},{\"from\":\"t\",\"to\":\"w\"},"
                            + "{\"from\":\"w\",\"to\":\"cap\"},{\"from\":\"cap\",\"to\":\"END\"}",
                    "w", "t", "wait"),
            // 3. 前面两个节点 —— 错值跟着「最后一个跑完的」走，不是固定指第一个
            new Shape("toolLlmThenWait", TOOL + "," + LLM + "," + WAIT + "," + CAP,
                    "{\"from\":\"START\",\"to\":\"t\"},{\"from\":\"t\",\"to\":\"m\"},{\"from\":\"m\",\"to\":\"w\"},"
                            + "{\"from\":\"w\",\"to\":\"cap\"},{\"from\":\"cap\",\"to\":\"END\"}",
                    "w", "m", "wait"),
            // 4. 挂起的是 hitl.confirm，前面一个 tool
            new Shape("toolThenGate", TOOL + "," + GATE + "," + CAP,
                    "{\"from\":\"START\",\"to\":\"t\"},{\"from\":\"t\",\"to\":\"gate\"},"
                            + "{\"from\":\"gate\",\"to\":\"cap\"},{\"from\":\"cap\",\"to\":\"END\"}",
                    "gate", "t", "hitl.confirm"));

    @Test
    @DisplayName("🔴 四种形状：next() 恒为真挂起点，而 node() 只在「挂起节点是第一个」时碰巧相等")
    void suspendNodeId_comesFromSnapshotNext_notFromInterruptionMetadataNode() throws Exception {
        for (Shape s : SHAPES) {
            WorkflowDefDTO d = def(s.label(), s.nodes(), s.edges());
            CompiledGraph graph = compile(d);
            RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();

            InterruptionMetadata interrupt = runUntilSuspended(graph, config);

            assertEquals(s.expectedMetadataNode(), interrupt.node(),
                    "形状 " + s.label() + "：InterruptionMetadata.node() 的实测值变了。"
                            + "若它现在等于真挂起点，说明 SAA 自己修好了 —— 本类与 "
                            + "DslToStateGraphCompiler.resolveSuspendNodeId 的实测表都要回来改");
            String resolved = DslToStateGraphCompiler.resolveSuspendNodeId(graph, config);
            assertEquals(s.expectedSuspendId(), resolved,
                    "形状 " + s.label() + "：resolveSuspendNodeId 应给出真挂起点");
            // 真挂起点的 type 必须与 interruptsBefore 登记的那个节点类型一致 —— Service 的双匹配就靠这一条
            assertEquals(s.suspendType(), nodeOf(d, resolved).type(),
                    "形状 " + s.label() + "：resolveSuspendNodeId 给出的 id 在 DSL 里对应的不是挂起节点");
        }
    }

    /** 复刻 Service 的按 id 查节点（它的第二重条件是 type，见 {@link #wrongSuspendNodeId_makesResumeAtUnresolvable}） */
    private static SuspendNode nodeOf(WorkflowDefDTO def, String nodeId) {
        if (nodeId == null) {
            return null;
        }
        for (NodeDTO n : def.getNodes()) {
            if (nodeId.equals(n.getId())) {
                return new SuspendNode(n.getId(), n.getType(), n);
            }
        }
        return null;
    }

    private record SuspendNode(String id, String type, NodeDTO node) {
    }

    // ========== ② 「所以呢」：错 id 让 resume_at 算不出来，对 id 算得出 ==========

    /**
     * 把 Service 那条链走到能看见后果为止：挂起分支拿 id → 按 id+type 双匹配 → {@code resolveWaitDurationSeconds}。
     *
     * <p>🔴 这一条是①的<b>非空洞证明</b>。只断言「两个 id 不相等」是弱的 ——
     * 万一 Service 哪天不再按 id 查节点，①照样红着，而危害已经没了。
     * 这里直接把危害摆出来：错 id ⟹ 双匹配落空 ⟹ {@code resume_at} 只能是 null；
     * 对 id ⟹ 匹配到 ⟹ 算出 3 秒。轮询器扫的是 {@code resume_at}，NULL 就是永远不醒。
     */
    @Test
    @DisplayName("🔴 后果：用 node() 双匹配落空（resume_at 只能为 null），用 next() 查得到")
    void wrongSuspendNodeId_makesResumeAtUnresolvable() throws Exception {
        Shape s = SHAPES.get(1); // toolThenWait
        WorkflowDefDTO d = def(s.label(), s.nodes(), s.edges());
        CompiledGraph graph = compile(d);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();

        InterruptionMetadata interrupt = runUntilSuspended(graph, config);

        // 错 id：指向 tool 节点，type 不是 wait ⟹ Service 的 resolveWaitResumeAt 双匹配落空、直接返回 null
        SuspendNode byMetadata = nodeOf(d, interrupt.node());
        assertNotNull(byMetadata, "node() 给出的 id 至少在 DSL 里存在（它是个真节点，只是不是挂起的那个）");
        assertFalse("wait".equals(byMetadata.type()),
                "若 node() 竟指到了 wait 节点，本条的前提就没了（SAA 修好了？），实测：" + byMetadata);

        // 对 id：双匹配命中 wait 节点，duration 算得出来 ⟹ resume_at 有值 ⟹ 轮询器到点能唤醒
        SuspendNode bySnapshot = nodeOf(d, DslToStateGraphCompiler.resolveSuspendNodeId(graph, config));
        assertNotNull(bySnapshot, "resolveSuspendNodeId 给出的 id 必须能在 DSL 里查到");
        assertEquals("wait", bySnapshot.type(), "应双匹配命中 wait 节点，实际：" + bySnapshot);
        assertEquals(3L, DslToStateGraphCompiler.resolveWaitDurationSeconds(bySnapshot.node()),
                "命中 wait 节点后 duration 应能解析出来 —— 这正是 resume_at 的唯一来源");
    }

    // ========== ③ 恢复之后的第二个挂起点：同样只能用 next() ==========

    /**
     * 两次挂起：{@code START → gate(hitl.confirm) → w(wait) → cap}。
     *
     * <p>🔴 这一条同时证两件事：
     * <ol>
     *   <li>第一次挂起时 {@code node()} 碰巧对（gate 是第一个节点），恢复之后第二次挂起就<b>错</b>了 ——
     *       它指回了刚跑完的 gate。只看首次挂起的用例会漏掉整个 resume 路径，
     *       而 resume 路径正是 {@code AgentWorkflowExecutionServiceImpl} 走 {@code isResume=true} 的那条。</li>
     *   <li>{@code resolveSuspendNodeId} 在<b>恢复流跑完之后</b>、用<b>原来那个</b> config
     *       （没有 {@code updateState} 返回的 checkpointId）也能取到第二个挂起点
     *       ⟹ 生产里 resume 分支之后仍用原 config 查是安全的。</li>
     * </ol>
     */
    @Test
    @DisplayName("🔴 恢复后的第二个挂起点：node() 指回刚跑完的 gate，next() 才是 wait")
    void suspendNodeId_afterResume_pointsAtTheSecondGate() throws Exception {
        WorkflowDefDTO d = def("twoSuspends", GATE + "," + WAIT + "," + CAP,
                "{\"from\":\"START\",\"to\":\"gate\"},{\"from\":\"gate\",\"to\":\"w\"},"
                        + "{\"from\":\"w\",\"to\":\"cap\"},{\"from\":\"cap\",\"to\":\"END\"}");
        CompiledGraph graph = compile(d);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();

        InterruptionMetadata first = runUntilSuspended(graph, config);
        assertEquals("gate", first.node(), "第一次挂起：gate 是 START 之后第一个节点，node() 碰巧对");
        assertEquals("gate", DslToStateGraphCompiler.resolveSuspendNodeId(graph, config),
                "第一次挂起：next() 也应是 gate");

        // 聊天内恢复喂的就是这一个写死的键（AgentAssistantServiceImpl.HITL_APPROVE_VAR）
        RunnableConfig updated = graph.updateState(config,
                Map.of(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR, true));
        List<NodeOutput> resumed = graph.stream(null, updated.withResume()).collectList().block();
        assertNotNull(resumed, "恢复流应正常结束");
        NodeOutput last = resumed.get(resumed.size() - 1);
        assertTrue(last instanceof InterruptionMetadata,
                "恢复后应停在第二个挂起点（wait），实际：" + last.getClass());

        assertEquals("gate", ((InterruptionMetadata) last).node(),
                "第二次挂起：node() 指回了刚跑完的 gate，而不是被拦下的 wait");
        assertEquals("w", DslToStateGraphCompiler.resolveSuspendNodeId(graph, config),
                "第二次挂起：next() 应是 wait —— 生产里 resume 分支之后仍用原 config 查，这一条就是它的安全依据");
        assertEquals(0, captureExecutor.runs.get(),
                "第二个挂起点拦在 wait 之前，wait 之后的 capture 不该跑；实际执行次数："
                        + captureExecutor.runs.get());
    }

    // ========== ④ 取不到快照时返回 null，而不是回落成那个错值 ==========

    /**
     * 🔴 钉住「宁可 NULL 不要错值」这个刻意选择。
     *
     * <p>用一个从没跑过的 threadId 查：没有 checkpoint ⟹ 取不到快照 ⟹ 必须是 null。
     * 若有人把它改成 {@code orElse(output.node())} 之类的回落，本条不会红（这里根本没有 output）——
     * 所以真正防回落的是 {@link DslToStateGraphCompiler#resolveSuspendNodeId} 的 javadoc 加上②的实证：
     * 回落值<b>不是无害的缺省</b>，它是个指错地方的确定值，而确定值比 NULL 更容易被人当真。
     */
    @Test
    @DisplayName("取不到 checkpoint 快照时返回 null（不回落成 InterruptionMetadata.node()）")
    void suspendNodeId_withoutCheckpoint_isNull() throws Exception {
        Shape s = SHAPES.get(1);
        WorkflowDefDTO d = def(s.label(), s.nodes(), s.edges());
        CompiledGraph graph = compile(d);

        RunnableConfig neverRan = RunnableConfig.builder().threadId("t1:never-ran").build();
        assertNull(DslToStateGraphCompiler.resolveSuspendNodeId(graph, neverRan),
                "没有 checkpoint 时应返回 null；返回任何非 null 都意味着它回落成了某个猜测值");
    }

}
