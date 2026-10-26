package cn.enlev.ainb.module.agent.framework.agent.core.loop;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.enums.ToolSourceEnum;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentLoopProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trace.NodeTraceRecorder;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentSecurityGateway;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolCatalog;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_AGENT_LOOP_NOT_CONVERGED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentLoopGraphBuilder} 的<b>运行时行为</b>单测（P2 增量3）
 *
 * <p>S1 spike 已在纯计数器层面证明「环形子图能编译、能按条件出口收敛、能写回共享 state」；
 * 本测试把 reason/act 的<b>真实业务动作</b>接上（mock 掉模型 / 工具 / trace / 网关），
 * 单独编译并跑通合成的环形子图，断言 agent_loop 的四条核心性质：
 * <ol>
 *   <li>①「tool_calls→观察→answer」两轮收敛：outputVar 落最终答复、trajectoryVar 含两轮、只读工具被调；</li>
 *   <li>②自修正：首轮坏 JSON → 回灌 hint → 次轮好 JSON 收敛，且 <b>step 未因解析失败而 ++</b>（act 从未跑）；</li>
 *   <li>③不收敛：每轮都 tool_calls 到 maxSteps → 抛 {@code WORKFLOW_AGENT_LOOP_NOT_CONVERGED}（fail-close，
 *       <b>不依赖 recursionLimit 静默截断</b>）；</li>
 *   <li>④只读强制：模型点名<b>写工具</b> → 不执行、回灌拒绝消息、循环继续（运行期第二道闸）；
 *       ④-b 两道闸都读<b>声明</b>风险级 —— 被 {@code agent_tool_override} 压成只读的写工具照样拦（D11）；
 *       ④-c 自定义工具（{@code agent_tool_def}）能进 tools[] 并真跑起来，写工具 / 不可执行来源 / 缺风险级
 *       照样编译期拒，且结果必被截断（B2）。</li>
 * </ol>
 *
 * <p>纯内存：{@code MemorySaver} + mock {@link AiModelApi}（按轮脚本化返回）+ mock {@link AgentToolExecutor}
 * + mock {@link AgentToolRegistry}（桩只读/写工具）+ mock trace/网关，不碰 DB、不碰真模型。
 *
 * @author 走进新时代
 */
class AgentLoopGraphBuilderTest {

    private AgentLoopGraphBuilder builder;
    private AiModelApi aiModelApi;
    private AgentToolExecutor toolExecutor;
    private AgentToolRegistry toolRegistry;
    private AgentLoopPrompt loopPrompt;
    private AgentLoopProperties loopProperties;
    /**
     * 自定义工具目录（B2）。绝大多数用例不桩它 —— 未桩的 mock 返回 null，
     * {@code AgentToolCatalog#customRows()} 据此当成「本租户没有自定义工具」，
     * 于是既有那些 {@code stubReadOnly/stubWrite} 的桩<b>一行都不用改</b>。
     */
    private AgentToolDefService toolDefService;

    @BeforeEach
    void setUp() {
        aiModelApi = mock(AiModelApi.class);
        toolExecutor = mock(AgentToolExecutor.class);
        toolRegistry = mock(AgentToolRegistry.class);
        toolDefService = mock(AgentToolDefService.class);
        loopPrompt = mock(AgentLoopPrompt.class);
        when(loopPrompt.buildSystemPrompt(any(), anyInt())).thenReturn("你是 ReAct 助手，每轮只输出一个 JSON 对象");

        builder = new AgentLoopGraphBuilder();
        ReflectionTestUtils.setField(builder, "aiModelApi", aiModelApi);
        ReflectionTestUtils.setField(builder, "toolExecutor", toolExecutor);
        // B2 之后 builder 认工具走的是 AgentToolCatalog（内置注册表 ∪ 当前租户自定义工具目录），
        // 不再是裸 registry。这里塞<b>真</b> catalog、底下垫 mock 的两个协作者：
        //   ① registry 的桩（下面 stubReadOnly/stubWrite）一个字都不用改 —— catalog 的 toView
        //      只是把同一批 getter 搬进 record，桩什么就搬什么；
        //   ② ToolView 是 record，mock 不了；手搓 14 个字段的 record 又会把断言重心从
        //      「两道闸读的是 declaredRisk 还是 effectiveRisk」挪到「我有没有填对 record」上，
        //      而那恰恰是本测试 ④-b 要钉的东西。
        ReflectionTestUtils.setField(builder, "toolCatalog",
                new AgentToolCatalog(toolRegistry, toolDefService));
        ReflectionTestUtils.setField(builder, "loopPrompt", loopPrompt);
        loopProperties = new AgentLoopProperties();
        ReflectionTestUtils.setField(builder, "loopProperties", loopProperties);
        // 形状/行为测试不落库：trace 与网关用 mock 占位（begin 返回 null，success/failure 皆空操作）
        ReflectionTestUtils.setField(builder, "traceRecorder", mock(NodeTraceRecorder.class));
        ReflectionTestUtils.setField(builder, "gateway", mock(AgentSecurityGateway.class));
    }

    // ==================== 桩工具 ====================

    /** 桩一个只读工具（编译期 tools[] 校验 + 运行期 act 执行都靠它） */
    private void stubReadOnly(String name) {
        AgentToolRegistry.ToolDefinition def = mock(AgentToolRegistry.ToolDefinition.class);
        when(def.isRequireConfirm()).thenReturn(false);
        when(def.getRisk()).thenReturn(RiskLevel.READ_ONLY);
        when(def.getDeclaredRisk()).thenReturn(RiskLevel.READ_ONLY);
        when(def.isTruncateResult()).thenReturn(false);
        when(def.getName()).thenReturn(name);
        when(toolRegistry.get(name)).thenReturn(def);
    }

    /** 桩一个写工具（运行期点名它时应被拒，绝不执行） */
    private void stubWrite(String name) {
        AgentToolRegistry.ToolDefinition def = mock(AgentToolRegistry.ToolDefinition.class);
        when(def.isRequireConfirm()).thenReturn(true);
        when(def.getRisk()).thenReturn(RiskLevel.IRREVERSIBLE);
        when(def.getDeclaredRisk()).thenReturn(RiskLevel.IRREVERSIBLE);
        when(def.isTruncateResult()).thenReturn(false);
        when(def.getName()).thenReturn(name);
        when(toolRegistry.get(name)).thenReturn(def);
    }

    /**
     * 🔴 桩一个「被租户覆盖成只读的写工具」：声明 IRREVERSIBLE、生效 READ_ONLY（D11 的那个形状）。
     * 两道只读闸都必须照声明值判 —— 否则一行 {@code agent_tool_override} 就能把
     * 「无人值守不得写」这条铁律静默推倒，而界面上什么都看不出来。
     */
    private void stubWriteDowngradedByOverride(String name) {
        AgentToolRegistry.ToolDefinition def = mock(AgentToolRegistry.ToolDefinition.class);
        when(def.isRequireConfirm()).thenReturn(false);
        when(def.getRisk()).thenReturn(RiskLevel.READ_ONLY);
        when(def.getDeclaredRisk()).thenReturn(RiskLevel.IRREVERSIBLE);
        when(def.isTruncateResult()).thenReturn(false);
        when(def.getName()).thenReturn(name);
        when(toolRegistry.get(name)).thenReturn(def);
    }

    private static AiChatRespDTO resp(String content) {
        AiChatRespDTO r = new AiChatRespDTO();
        r.setContent(content);
        r.setModelId(67L);
        r.setUsage(AiChatRespDTO.Usage.of(10, 20, 30));
        return r;
    }

    private static NodeDTO loopNode(List<String> tools, int maxSteps, String outputVar, String trajectoryVar) {
        NodeDTO n = new NodeDTO();
        n.setId("ag");
        n.setType("agent_loop");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("modelId", 67L);
        cfg.put("tools", tools);
        cfg.put("maxSteps", maxSteps);
        cfg.put("prompt", "库里有没有和采购相关的表？");
        cfg.put("outputVar", outputVar);
        if (trajectoryVar != null) {
            cfg.put("trajectoryVar", trajectoryVar);
        }
        n.setConfig(cfg);
        return n;
    }

    /** 合成环形子图 → 单独编译（MemorySaver）→ 跑通 → 返回终止时的共享 state */
    private Map<String, Object> runLoop(NodeDTO node) throws Exception {
        StateGraph sg = builder.build(node, 1L, "t1:1",
                AgentExecutionContext.ofForTest(1L, 1L), node.getId() + "-");
        CompiledGraph cg = sg.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .recursionLimit(100)
                .build());
        List<NodeOutput> outs = cg.stream(Map.of(), RunnableConfig.builder().threadId("t1:1").build())
                .collectList().block();
        assertNotNull(outs, "子图流应正常完成");
        return outs.get(outs.size() - 1).state().data();
    }

    // ==================== ① 两轮收敛：tool_calls → 观察 → answer ====================

    @Test
    void loop_toolCallsThenAnswer_convergesInTwoRounds() throws Exception {
        stubReadOnly("ro_query");
        when(toolExecutor.execute(any(), eq("ro_query"), any())).thenReturn(Map.of("rows", 3));
        when(aiModelApi.chat(any())).thenReturn(
                resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"ro_query\",\"params\":{\"q\":\"purchase\"}}]}"),
                resp("{\"type\":\"answer\",\"text\":\"库里有 3 张采购相关的表\"}"));

        Map<String, Object> state = runLoop(loopNode(List.of("ro_query"), 3, "agent_answer", "agent_trace"));

        assertEquals("库里有 3 张采购相关的表", state.get("agent_answer"), "outputVar 应落最终答复");
        Object traj = state.get("agent_trace");
        assertTrue(traj instanceof List<?> list && list.size() == 2,
                "trajectoryVar 应含两轮（act 的 tool_calls + reason 的 answer），实际：" + traj);
        verify(toolExecutor, times(1)).execute(any(), eq("ro_query"), any());
        verify(aiModelApi, times(2)).chat(any());
    }

    // ==================== ② 自修正：坏 JSON 回灌后收敛，step 不 ++ ====================

    @Test
    void loop_badJsonThenAnswer_selfCorrectsWithoutConsumingStep() throws Exception {
        stubReadOnly("ro_query");
        when(aiModelApi.chat(any())).thenReturn(
                resp("抱歉，我不太会输出 JSON"),           // 无花括号 → extractJsonObject 返回 null → retry
                resp("{\"type\":\"answer\",\"text\":\"好了\"}"));

        Map<String, Object> state = runLoop(loopNode(List.of("ro_query"), 3, "agent_answer", "agent_trace"));

        assertEquals("好了", state.get("agent_answer"), "自修正后应收敛出 answer");
        // 🔴 关键：解析失败走 retry（step 不 ++），act 从未跑 → 工具零调用、step 键从未写入
        verify(toolExecutor, never()).execute(any(), any(), any());
        verify(aiModelApi, times(2)).chat(any());
        assertNull(state.get("__agentloop_ag_step"), "解析失败不应推进 step（act 未执行）");
    }

    // ==================== ③ 不收敛：耗尽 maxSteps 仍 tool_calls → fail-close ====================

    @Test
    void loop_neverConverges_throwsNotConverged() {
        stubReadOnly("ro_query");
        when(toolExecutor.execute(any(), eq("ro_query"), any())).thenReturn(Map.of("rows", 1));
        // 每轮都吐 tool_calls，永不 answer
        when(aiModelApi.chat(any())).thenReturn(
                resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"ro_query\",\"params\":{}}]}"));

        NodeDTO node = loopNode(List.of("ro_query"), 2, "agent_answer", "agent_trace");
        Throwable t = assertThrows(Throwable.class, () -> runLoop(node),
                "耗尽 maxSteps 仍不收敛应主动抛错（而非被 recursionLimit 静默截断）");
        assertTrue(hasCode(t, WORKFLOW_AGENT_LOOP_NOT_CONVERGED.getCode()),
                "应抛 WORKFLOW_AGENT_LOOP_NOT_CONVERGED，实际异常链：" + chain(t));
    }

    // ==================== ④ 只读强制：模型点名写工具 → 拒绝、不执行、循环继续 ====================

    @Test
    void loop_modelNamesWriteTool_refusedNotExecuted() throws Exception {
        stubReadOnly("ro_query");   // tools[] 里只有只读工具（编译期通过）
        stubWrite("dm_create");     // 模型运行期点名这个写工具
        when(aiModelApi.chat(any())).thenReturn(
                resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"dm_create\",\"params\":{}}]}"),
                resp("{\"type\":\"answer\",\"text\":\"我不会替你建表\"}"));

        Map<String, Object> state = runLoop(loopNode(List.of("ro_query"), 3, "agent_answer", "agent_trace"));

        assertEquals("我不会替你建表", state.get("agent_answer"));
        // 🔴 写工具绝不执行
        verify(toolExecutor, never()).execute(any(), any(), any());
        // 拒绝消息作为观察回灌进轨迹
        Object traj = state.get("agent_trace");
        assertTrue(traj instanceof List<?> list && !list.isEmpty(), "轨迹应记录这一步");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) traj).get(0);
        String obs = String.valueOf(first.get("observations"));
        assertTrue(obs.contains("写操作"), "观察里应含写工具拒绝说明，实际：" + obs);
    }

    // ==================== ④-b 两道闸都读「声明」风险级（D11） ====================

    /** 编译期闸的基准形：{@code tools[]} 里直接放写工具 → 建图即拒（不必等到运行期）。 */
    @Test
    void loop_writeToolInToolsRefusedAtCompileTime() {
        stubWrite("dm_create");

        NodeDTO node = loopNode(List.of("dm_create"), 3, "agent_answer", "agent_trace");
        Throwable t = assertThrows(Throwable.class, () -> runLoop(node),
                "tools[] 里的写工具必须编译期就拒，不能等运行期模型点名才拦");
        assertTrue(hasCode(t, WORKFLOW_DSL_INVALID.getCode()),
                "应抛 WORKFLOW_DSL_INVALID，实际异常链：" + chain(t));
        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    /**
     * 🔴 编译期闸：{@code agent_tool_override} 把写工具的<b>生效</b>风险级压成 READ_ONLY 之后，
     *    {@code tools[]} 里放它仍然必须被拒。
     *
     * <p>旧实现读 {@code isRequireConfirm()}（= {@code getRisk().isRequireConfirm()}，走租户生效值），
     * 于是一行覆盖数据就能让「无人值守不得写」这条铁律静默失效：界面上工具照样显示为写操作、
     * 审计里也照样是写操作，只有 agent_loop 的准入判定被改写了 —— 这正是 D11 说的
     * 「安全判据站在可被数据推动的地基上」。
     *
     * <p>断言里必须出现<b>声明</b>等级「不可逆」而不是生效等级「只读」：报错文案是给
     * LLM 自修正回路看的，说「只读」会让模型认为校验器在胡说。
     */
    @Test
    void loop_writeToolDowngradedByOverride_stillRefusedAtCompileTime() {
        stubWriteDowngradedByOverride("dm_create");

        NodeDTO node = loopNode(List.of("dm_create"), 3, "agent_answer", "agent_trace");
        Throwable t = assertThrows(Throwable.class, () -> runLoop(node),
                "生效风险级被覆盖成只读的写工具，仍不得进 agent_loop 的 tools[]");
        assertTrue(hasCode(t, WORKFLOW_DSL_INVALID.getCode()),
                "应抛 WORKFLOW_DSL_INVALID，实际异常链：" + chain(t));
        assertTrue(messages(t).contains("不可逆"),
                "报错要点名声明风险级「不可逆」（不是被覆盖后的「只读」），实际：" + messages(t));
        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    /**
     * 🔴 运行期闸（最后一道）：{@code tools[]} 合法，但模型点名的那个工具被覆盖成只读。
     *    循环里没有 Plan 确认，若按生效值判定，模型就能无人值守地改数据。
     */
    @Test
    void loop_modelNamesToolDowngradedByOverride_refusedNotExecuted() throws Exception {
        stubReadOnly("ro_query");                    // tools[] 里只有真只读工具（编译期通过）
        stubWriteDowngradedByOverride("dm_create");  // 模型运行期点名这个「被压成只读的写工具」
        when(aiModelApi.chat(any())).thenReturn(
                resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"dm_create\",\"params\":{}}]}"),
                resp("{\"type\":\"answer\",\"text\":\"我不会替你建表\"}"));

        Map<String, Object> state = runLoop(loopNode(List.of("ro_query"), 3, "agent_answer", "agent_trace"));

        assertEquals("我不会替你建表", state.get("agent_answer"));
        verify(toolExecutor, never()).execute(any(), any(), any());
        Object traj = state.get("agent_trace");
        assertTrue(traj instanceof List<?> list && !list.isEmpty(), "轨迹应记录这一步");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) traj).get(0);
        String obs = String.valueOf(first.get("observations"));
        assertTrue(obs.contains("写操作"), "观察里应含写工具拒绝说明，实际：" + obs);
    }

    // ==================== ④-c 自定义工具（agent_tool_def，B2）====================

    /**
     * 🔴 这一节钉的是 B2 在 agent_loop 上的两半：<b>能进</b>与<b>该拦的照样拦</b>。
     *
     * <p>B2 之前这里存在一处<b>三处口径互相矛盾</b>的假绿：{@code WorkflowDslPlannerPrompt#customTools}
     * 的提示词明写「只读工具……也可放进 {@code agent_loop.tools}」，{@code WorkflowDslValidator} 的
     * {@code ToolResolver} 也确实认自定义工具（它走 {@code resolveForGateway}，看得到停用行），
     * 于是模型照着提示词编、{@code /validate} 放行、发布通过 —— 一直到运行期
     * {@code resolveSpec} 才炸「声明的工具(x)未登记」。<b>体检说是好的</b>，这正是本平台头号缺陷的形状。
     *
     * <p>所以放宽准入必须连带钉住三件事，否则就是把「运行期炸」换成更贵的「静默乱来」：
     * 写工具照样编译期拒、不可执行的来源照样编译期拒、结果<b>必被截断</b>
     * （自定义工具拿不到 {@code truncateResult=false} 那个白名单待遇）。
     */
    private void stubCustomTool(String key, String source, String riskLevel) {
        AgentToolDefDO row = new AgentToolDefDO();
        row.setToolKey(key);
        row.setSource(source);
        row.setRiskLevel(riskLevel);
        row.setEnabled(true);
        row.setDisplayName("销售日报");
        // registry 查不到它（unstubbed → null），catalog 必然回落到自定义目录 —— 这正是要走的那条路
        when(toolDefService.getToolDefListIncludingDisabled()).thenReturn(List.of(row));
    }

    @Test
    void loop_customReadOnlyTool_compilesAndRuns() throws Exception {
        stubCustomTool("sales_daily_report", ToolSourceEnum.DATA_INTERFACE.getSource(), "READ_ONLY");
        when(toolExecutor.execute(any(), eq("sales_daily_report"), any())).thenReturn(Map.of("amount", 120000));
        when(aiModelApi.chat(any())).thenReturn(
                resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"sales_daily_report\",\"params\":{\"day\":\"2026-10-01\"}}]}"),
                resp("{\"type\":\"answer\",\"text\":\"当天销售额 12 万\"}"));

        Map<String, Object> state = runLoop(
                loopNode(List.of("sales_daily_report"), 3, "agent_answer", "agent_trace"));

        assertEquals("当天销售额 12 万", state.get("agent_answer"),
                "🔴 B2 本体：此前 resolveSpec 直接抛「未登记」，连图都建不起来");
        verify(toolExecutor, times(1)).execute(any(), eq("sales_daily_report"), any());
        Object traj = state.get("agent_trace");
        assertTrue(traj instanceof List<?> list && list.size() == 2,
                "轨迹应含两轮，实际：" + traj);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) traj).get(0);
        String obs = String.valueOf(first.get("observations"));
        assertTrue(obs.contains("120000"), "工具结果要回灌进轨迹，实际：" + obs);
        assertTrue(obs.contains("tool_observation"),
                "🔴 工具结果是污点源，自定义工具的也不例外（B2-3）。实际：" + obs);
    }

    @Test
    void loop_customWriteTool_refusedAtCompileTime() {
        stubCustomTool("sales_reset", ToolSourceEnum.DATA_INTERFACE.getSource(), "IRREVERSIBLE");

        Throwable t = assertThrows(Throwable.class,
                () -> runLoop(loopNode(List.of("sales_reset"), 3, "agent_answer", "agent_trace")),
                "自定义写工具照样不得进 agent_loop —— 它是<b>无人值守</b>的自主循环，没有 Plan 确认兜着");
        assertTrue(hasCode(t, WORKFLOW_DSL_INVALID.getCode()),
                "应抛 WORKFLOW_DSL_INVALID，实际异常链：" + chain(t));
        assertTrue(messages(t).contains("不可逆"),
                "报错要说出自定义工具自己那列 risk_level 的中文描述，实际：" + messages(t));
        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    @Test
    void loop_customToolWithBlankRisk_refusedAtCompileTime() {
        stubCustomTool("risk_missing", ToolSourceEnum.DATA_INTERFACE.getSource(), null);

        Throwable t = assertThrows(Throwable.class,
                () -> runLoop(loopNode(List.of("risk_missing"), 3, "agent_answer", "agent_trace")));
        assertTrue(hasCode(t, WORKFLOW_DSL_INVALID.getCode()), "实际异常链：" + chain(t));
        assertTrue(messages(t).contains("高风险"),
                "risk_level 缺失按 HIGH 兜（与 RiskLevel.parseOrHigh 同方向），实际：" + messages(t));
    }

    @Test
    void loop_customReservedSource_refusedAtCompileTime() {
        // FORM/SCRIPT 是 ToolSourceEnum 的预留位；登记入口只放行 DATA_INTERFACE，
        // 故这一支只有绕过接口直接改库才走得到 —— 但真走到就得在<b>编译期</b>拦下：
        // 放过去等于让自主循环每一步都失败，而失败原因藏在 observation 里，看图的人看不出是配置问题
        stubCustomTool("form_writer", "FORM", "READ_ONLY");

        Throwable t = assertThrows(Throwable.class,
                () -> runLoop(loopNode(List.of("form_writer"), 3, "agent_answer", "agent_trace")));
        assertTrue(hasCode(t, WORKFLOW_DSL_INVALID.getCode()), "实际异常链：" + chain(t));
        assertTrue(messages(t).contains("FORM") && messages(t).contains("不可执行"),
                "要点名是哪个来源不可执行 —— 报错是这条路径上唯一的线索，实际：" + messages(t));
        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    @Test
    void loop_customReadOnlyTool_resultIsTruncated() throws Exception {
        loopProperties.setToolResultMaxChars(50);
        stubCustomTool("sales_daily_report", ToolSourceEnum.DATA_INTERFACE.getSource(), "READ_ONLY");
        // 数据接口回多大平台无从静态断言 —— 这正是它进不了那个代码白名单的理由，故刻意造一个超长的
        when(toolExecutor.execute(any(), eq("sales_daily_report"), any()))
                .thenReturn(Map.of("rows", "x".repeat(500)));
        when(aiModelApi.chat(any())).thenReturn(
                resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"sales_daily_report\",\"params\":{}}]}"),
                resp("{\"type\":\"answer\",\"text\":\"数据太多\"}"));

        Map<String, Object> state = runLoop(
                loopNode(List.of("sales_daily_report"), 3, "agent_answer", "agent_trace"));

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) state.get("agent_trace")).get(0);
        String obs = String.valueOf(first.get("observations"));
        assertTrue(obs.contains("已截断"),
                "🔴 不截断的白名单待遇只给代码里 @AgentTool(truncateResult=false) 声明过的工具。实际：" + obs);
        assertFalse(obs.contains("x".repeat(500)), "原文不该整段进上下文，实际：" + obs);
    }

    // ==================== ⑤ 调查目标的键名：goal 与 prompt 双认 ====================

    /**
     * 🔴 这一节钉的是一个「三处口径对一处运行期」的漂移，而不是普通的取值兜底。
     *
     * <p>{@code NodeIoContract} 的入端口、前端 {@code defaultConfig('agent_loop')}、
     * {@code WorkflowDslPlannerPrompt} 给模型的 config 样板 —— 三处都说这个键叫 {@code goal}，
     * 而运行期只读 {@code prompt}。后果不是「配置不生效」这么轻：编译期直接抛
     * 「需要 prompt 或 inputVar 之一」，于是<b>设计器拖出来的 agent_loop 与 AI 生成的 agent_loop
     * 一律无法发布</b>，而报错让用户去填一个界面上根本不存在的框。
     *
     * <p>前端 {@code nodeConfigSchema.spec.ts} 那条「字段键对齐后端 GraphBuilder」的测试之所以没拦住：
     * 它只断言前端自己的键集，从不读后端。约束写在测试名里、没有强制点 —— 与 {@code SummarySpacingTest}
     * 要解决的是同一类问题。
     */
    @Test
    void goalKeyAlone_drivesTheLoop() throws Exception {
        stubReadOnly("ro_query");
        when(aiModelApi.chat(any())).thenReturn(resp("{\"type\":\"answer\",\"text\":\"查完了\"}"));

        // 设计器与 AI 生成的形状：只有 goal，没有 prompt
        Map<String, Object> state = runLoop(loopNodeWith(Map.of("goal", "库里有没有和采购相关的表？")));

        assertEquals("查完了", state.get("agent_answer"));
        // 🔴 编译过了不等于生效：还要断言这段目标真的作为 user 消息发给了模型。
        //    「配了却没进 prompt」正是本项目的头号缺陷形态，只断言不抛异常会把它放过去
        assertEquals("库里有没有和采购相关的表？", onlyUserMessage());
    }

    @Test
    void promptKeyStillWorks_soExistingDslDoesNotBreak() throws Exception {
        stubReadOnly("ro_query");
        when(aiModelApi.chat(any())).thenReturn(resp("{\"type\":\"answer\",\"text\":\"查完了\"}"));

        // 存量形状：只有 prompt。它是运行期一直认的那个键，改成只认 goal 会让已发布的流程编译失败
        runLoop(loopNodeWith(Map.of("prompt", "按旧键写的目标")));

        assertEquals("按旧键写的目标", onlyUserMessage());
    }

    @Test
    void goalWinsWhenBothPresent() throws Exception {
        stubReadOnly("ro_query");
        when(aiModelApi.chat(any())).thenReturn(resp("{\"type\":\"answer\",\"text\":\"查完了\"}"));

        Map<String, Object> both = new HashMap<>();
        both.put("goal", "界面上填的那个");
        both.put("prompt", "手改 DSL 留下的那个");
        runLoop(loopNodeWith(both));

        // goal 优先：它是三个作者面写出来的键，两个都在时以「用户看得见的那个」为准
        assertEquals("界面上填的那个", onlyUserMessage());
    }

    @Test
    void missingGoal_namesTheKeyTheDesignerActuallyRenders() {
        stubReadOnly("ro_query");
        NodeDTO node = loopNodeWith(Map.of());

        Throwable t = assertThrows(Throwable.class, () -> runLoop(node));
        String message = String.valueOf(t.getMessage());
        assertTrue(message.contains("goal"), "报错应指向界面上真有的那个键，实际：" + message);
        // 🔴 不许再提 prompt：让用户去填一个设计器不渲染的框，正是这个 bug 能藏住的原因
        assertFalse(message.contains("prompt"), "报错不该再指向界面上没有的键，实际：" + message);
    }

    /** 造一个 agent_loop 节点，「调查目标」部分由入参决定（键名与值都可控） */
    private static NodeDTO loopNodeWith(Map<String, Object> goalConfig) {
        NodeDTO n = new NodeDTO();
        n.setId("ag");
        n.setType("agent_loop");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("modelId", 67L);
        cfg.put("tools", List.of("ro_query"));
        cfg.put("maxSteps", 3);
        cfg.put("outputVar", "agent_answer");
        cfg.putAll(goalConfig);
        n.setConfig(cfg);
        return n;
    }

    /** 唯一那次 chat 调用里的 user 消息内容（本节每个用例都只让模型答一轮） */
    private String onlyUserMessage() {
        ArgumentCaptor<AiChatReqDTO> captor = ArgumentCaptor.forClass(AiChatReqDTO.class);
        verify(aiModelApi).chat(captor.capture());
        List<String> userMessages = captor.getValue().getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(AiChatReqDTO.Message::getContent)
                .toList();
        assertEquals(1, userMessages.size(), "应只有一条 user 消息，实际：" + userMessages);
        return userMessages.get(0);
    }

    // ==================== 工具方法 ====================

    private static boolean hasCode(Throwable t, Integer code) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ServiceException se && code.equals(se.getCode())) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getSimpleName());
            if (c instanceof ServiceException se) {
                sb.append('(').append(se.getCode()).append(')');
            }
            sb.append(" <- ");
            if (c.getCause() == c) {
                break;
            }
        }
        return sb.toString();
    }

    /** 整条异常链的文案拼起来：{@link #chain} 只给类名与错误码，断言报错措辞时要这个 */
    private static String messages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
            if (c.getCause() == c) {
                break;
            }
        }
        return sb.toString();
    }

}
