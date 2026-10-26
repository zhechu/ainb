package cn.enlev.ainb.module.agent.framework.agent.core.chat;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentLoopProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.loop.AgentLoopPrompt;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolCatalog;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.enlev.ainb.module.agent.service.usage.AgentUsageLogService;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_CHAT_TOOL_LOOP_NOT_CONVERGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentChatToolLoop} 的 ReAct 只读循环单测（增量12 / slice-3b 批次 B4b，0 token）
 *
 * <h3>钉死什么</h3>
 * 聊天工具循环是「选中带只读工具的 Agent 对话时，先查清现状再回答」的核心。它的全部风险都在<b>协议路由</b>与
 * <b>只读铁律</b>上，故这里把七件事各钉一刀：
 * <ol>
 *   <li>① 直接 answer：返回文本、记一条 TOOL_LOOP 计量、<b>不调任何工具</b>；persona/modelId 进请求</li>
 *   <li>② tool_calls→act→answer：只读工具经 {@link AgentToolExecutor} 真执行、进度回调上浮「正在调查：…」</li>
 *   <li>③ 写工具（<b>声明</b>风险级，B2 之后即 {@code ToolView#declaredRequireConfirm()}）：<b>绝不执行</b>，回灌拒绝让循环继续（运行期第二道只读闸）；
 *       ③-b 被 {@code agent_tool_override} 压成只读的写工具照样拦（D11）</li>
 *   <li>④ 未登记工具（两类目录都查不到）：<b>绝不执行</b>，回灌「工具不存在」</li>
 *   <li>⑤ 到 maxSteps 仍吐 tool_calls：fail-close 抛 {@code AGENT_CHAT_TOOL_LOOP_NOT_CONVERGED}（不静默截断）</li>
 *   <li>⑥ 连续输出无法解析的 JSON 超过 maxParseRetries：同样 fail-close 抛错（不无限打转）</li>
 *   <li>⑦ 自定义工具（{@code agent_tool_def}）：只读的真能执行、写工具照样拒、且结果<b>必被截断</b>（B2）</li>
 * </ol>
 *
 * <p>纯 mock：{@code aiModelApi.chat} 喂<b>写死的干净 JSON</b>（平台无原生 function-calling，协议靠 JSON 约定），
 * {@code toolExecutor}/{@code toolRegistry}/{@code usageLogService} 全 mock，{@code loopPrompt} mock 掉 system prompt 渲染，
 * {@code loopProperties} 用 {@code @Spy} 真实例（好按需 setDefaultMaxSteps/setMaxParseRetries）。全程 0 真实 LLM 调用、0 DB。
 *
 * @author 走进新时代
 */
class AgentChatToolLoopTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentChatToolLoop toolLoop;

    @Mock
    private AiModelApi aiModelApi;
    @Mock
    private AgentToolExecutor toolExecutor;
    @Mock
    private AgentToolRegistry toolRegistry;
    /**
     * 自定义工具目录（B2）。绝大多数用例不桩它 —— 未桩的 mock 返回 null，
     * {@code AgentToolCatalog#customRows()} 据此当成「本租户没有自定义工具」，
     * 于是既有那些 {@code when(toolRegistry.get(...))} 的桩<b>一行都不用改</b>：
     * 目录内部先查 registry、命中就返回，压根走不到这一侧。
     */
    @Mock
    private AgentToolDefService toolDefService;
    @Mock
    private AgentLoopPrompt loopPrompt;
    @Mock
    private AgentUsageLogService usageLogService;
    @Spy
    private AgentLoopProperties loopProperties = new AgentLoopProperties();

    private final AgentExecutionContext context = AgentExecutionContext.ofForTest(1L, 1L);

    @BeforeEach
    void setUp() {
        // 🔴 用<b>真实</b>的 AgentToolCatalog（只把它的两个依赖换成 mock），而不是 mock 掉目录本身（B2）。
        //    这样本类既有的 {@code when(toolRegistry.get("ro_tool")).thenReturn(roDef)} 一类桩
        //    全部照旧生效 —— 目录只是把它们转了一道手。若改成 mock 目录，
        //    十几处桩就得逐个改写成 {@code when(toolCatalog.resolve(...))} 返回一个 record，
        //    而 record 不能 mock，只能手搓十四个字段：改动量大不说，
        //    更糟的是<b>再也没人在测「registry 命中之后各道闸读的是哪一格」</b>——
        //    那恰恰是本类②③④三档（写工具 / 降险 / 不存在）真正要钉的东西。
        ReflectionTestUtils.setField(toolLoop, "toolCatalog",
                new AgentToolCatalog(toolRegistry, toolDefService));
        // 每个用例都会进 run() → 第一步就渲染 system prompt，故此 stub 必被使用（非 lenient 也不会误报）
        when(loopPrompt.buildSystemPrompt(any(), anyInt())).thenReturn("SYS_BASE");
    }

    private AiChatRespDTO resp(String content) {
        AiChatRespDTO r = new AiChatRespDTO();
        r.setContent(content);
        // 带 usage → recordUsage 走 PROVIDER 分支，不触发本地估算，计量断言更确定
        r.setUsage(AiChatRespDTO.Usage.of(10, 5, 15));
        return r;
    }

    /**
     * 🔴 桩一律桩 {@code getDeclaredRisk()}（<b>声明</b>值），不桩 {@code isRequireConfirm()}/{@code getRisk()}：
     * 运行期那道只读闸读的是声明值（见 {@code AgentChatToolLoop} 的 D11 注释），
     * STRICT_STUBS 下桩了不被读的方法会直接判失败。
     */
    private AgentToolRegistry.ToolDefinition readOnlyDef() {
        AgentToolRegistry.ToolDefinition def = mock(AgentToolRegistry.ToolDefinition.class);
        when(def.getDeclaredRisk()).thenReturn(RiskLevel.READ_ONLY);
        when(def.isTruncateResult()).thenReturn(false);
        return def;
    }

    private AgentToolRegistry.ToolDefinition writeDef() {
        AgentToolRegistry.ToolDefinition def = mock(AgentToolRegistry.ToolDefinition.class);
        when(def.getDeclaredRisk()).thenReturn(RiskLevel.IRREVERSIBLE);
        return def;
    }

    /**
     * 🔴 桩一个「被 {@code agent_tool_override} 压成只读的写工具」（D11 的形状）：
     * 声明 IRREVERSIBLE、生效 READ_ONLY 且 {@code isRequireConfirm()==false}。
     * 形状与 {@code AgentLoopGraphBuilderTest#stubWriteDowngradedByOverride} 一致。
     */
    private AgentToolRegistry.ToolDefinition writeDefDowngradedByOverride() {
        AgentToolRegistry.ToolDefinition def = mock(AgentToolRegistry.ToolDefinition.class);
        // 这两个生效值桩如今<b>会被真读到</b>（AgentToolCatalog 的 ToolView 两格都装），
        // 留 lenient() 只是为了让它们读不到时也不报 STRICT_STUBS —— 判据本身仍只认下面那行声明值
        lenient().when(def.isRequireConfirm()).thenReturn(false);
        lenient().when(def.getRisk()).thenReturn(RiskLevel.READ_ONLY);
        when(def.getDeclaredRisk()).thenReturn(RiskLevel.IRREVERSIBLE);
        return def;
    }

    // ==================== ① 直接 answer ====================

    @Test
    @DisplayName("① 模型直接给 answer：返回文本、记一条 TOOL_LOOP、不调工具；persona/modelId 进请求")
    void run_immediateAnswer_returnsTextAndRecordsUsage() {
        when(aiModelApi.chat(any())).thenReturn(resp("{\"type\":\"answer\",\"text\":\"结论A\"}"));

        String answer = toolLoop.run(context, "有哪些接口", List.of("ro_tool"),
                7L, "你是接口助手", 100L, p -> {
                });

        assertEquals("结论A", answer);
        // 一轮 LLM 调用 → 一条计量
        verify(usageLogService, times(1)).record(any());
        // answer 路径不碰任何工具
        verify(toolExecutor, never()).execute(any(), any(), any());

        // persona 补进 system prompt、modelId 透传、显式给 maxTokens（不落到模型自身配置）
        ArgumentCaptor<AiChatReqDTO> captor = ArgumentCaptor.forClass(AiChatReqDTO.class);
        verify(aiModelApi).chat(captor.capture());
        AiChatReqDTO sent = captor.getValue();
        assertEquals(7L, sent.getModelId());
        assertTrue(sent.getSystemPrompt().contains("SYS_BASE"), "以 loop prompt 为底");
        assertTrue(sent.getSystemPrompt().contains("你是接口助手"), "persona 作为身份设定补进 system prompt");
        assertEquals(8000, sent.getMaxTokens().intValue(), "显式给 maxTokens，避免输出被截断误判为解析失败");
    }

    // ==================== ② tool_calls → act → answer ====================

    @Test
    @DisplayName("② tool_calls 后 answer：只读工具经执行器真执行、进度回调上浮「正在调查：…」、两轮各记一次计量、🔴 两轮共用同一个 taskKey")
    void run_toolCallsThenAnswer_executesReadOnlyToolAndReportsProgress() {
        when(aiModelApi.chat(any()))
                .thenReturn(resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"ro_tool\",\"params\":{\"k\":\"v\"}}]}"))
                .thenReturn(resp("{\"type\":\"answer\",\"text\":\"查完了\"}"));
        // 🔴 先把 def mock 造好再喂给 thenReturn：readOnlyDef() 内部还要 when(...)，
        //    若直接写在 thenReturn(readOnlyDef()) 里，会在外层 stubbing 未完成时又起一个 stubbing → UnfinishedStubbing
        AgentToolRegistry.ToolDefinition roDef = readOnlyDef();
        when(toolRegistry.get("ro_tool")).thenReturn(roDef);
        when(toolExecutor.execute(any(), eq("ro_tool"), any())).thenReturn(Map.of("count", 3));

        List<String> progress = new ArrayList<>();
        String answer = toolLoop.run(context, "查一下", List.of("ro_tool"),
                null, null, 100L, progress::add);

        assertEquals("查完了", answer);
        // 只读工具经唯一收口的执行器执行（绝不直连实现）
        verify(toolExecutor).execute(any(), eq("ro_tool"), any());
        // 进度上浮（前端桥成 PLANNING 原地替换）
        assertEquals(List.of("正在调查：ro_tool"), progress);
        // 两轮 LLM 调用 → 两条 TOOL_LOOP 计量
        ArgumentCaptor<AiChatReqDTO> rounds = ArgumentCaptor.forClass(AiChatReqDTO.class);
        verify(aiModelApi, times(2)).chat(rounds.capture());
        verify(usageLogService, times(2)).record(any());

        // 🔴 一次 run = 一个任务：两轮必须带**同一个** taskKey（裁定 R-01 / 缺陷 D4-28）。
        //    这条守的失效形态是**静默的**：把 AgentTaskKeys.newToolLoopRun 从入口挪进 while 里，
        //    每轮各取一个新键 ⟹ 粘滞一次都不生效、不报错、别的断言全绿，
        //    而第 N 轮额度耗尽被换掉的模型和第 N+1 轮接着推理的模型就不是同一个了。
        List<AiChatReqDTO> sent = rounds.getAllValues();
        assertNotNull(sent.get(0).getTaskKey(), "taskKey 必须非空，空 = 不做粘滞");
        assertEquals(sent.get(0).getTaskKey(), sent.get(1).getTaskKey(), "同一次 run 的两轮必须共用一个 taskKey");
        assertTrue(sent.get(0).getTaskKey().startsWith("TOOLLOOP:100:"),
                "键形如 TOOLLOOP:<conversationId>:<uuid>，conversationId 拼进去只为日志可检索");
    }

    // ==================== ③ 写工具：绝不执行 ====================

    @Test
    @DisplayName("③ 模型点名写工具（requireConfirm）：运行期第二道闸拦住、绝不执行，回灌拒绝让循环继续到 answer")
    void run_writeToolRejected_notExecuted() {
        when(aiModelApi.chat(any()))
                .thenReturn(resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"write_tool\",\"params\":{}}]}"))
                .thenReturn(resp("{\"type\":\"answer\",\"text\":\"改用只读工具\"}"));
        AgentToolRegistry.ToolDefinition writeToolDef = writeDef();
        when(toolRegistry.get("write_tool")).thenReturn(writeToolDef);

        String answer = toolLoop.run(context, "帮我建表", List.of("write_tool"),
                null, null, 100L, p -> {
                });

        assertEquals("改用只读工具", answer);
        // 🔴 写工具一律不自动执行（聊天循环无人工确认）
        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    /**
     * 🔴 ③-b（P0-1 / D11）：被 {@code agent_tool_override} 压成只读的写工具，运行期这道闸照样必须拦。
     *
     * <p>这条比 ③ 要紧得多。③ 拦的是「明摆着的写工具」，谁都拦得住；
     * 本条拦的是<b>一行数据就能改判</b>的那条路 —— 而聊天工具循环是<b>无人值守</b>的，
     * 循环内没有 Plan 确认卡兜着，一旦放行，模型自己就把表建了。
     *
     * <p>{@code ToolOverride.risk} 的 javadoc 明写「允许低于声明值，产品决策」，
     * 界面上随时能点，且点完之后：界面上工具照样标着写操作、审计日志里也照样是写操作，
     * <b>只有准入判定被改写，故全程静默</b>。
     *
     * <p>发布期那道闸（{@code AgentDefServiceImpl#validateToolKeys}）在<b>发布那一刻</b>判，
     * 覆盖行是发布之后才写进去的也照样能改判 —— 所以两道都得读搬不动的声明值。
     *
     * <p><b>🔴 变异探针</b>：把 {@code AgentChatToolLoop} 里的 {@code getDeclaredRisk()} 改回
     * {@code isRequireConfirm()}，本用例必须变红，且红在 {@code verify(toolExecutor, never())} 那行
     * —— 即「写工具被真执行了」。改回去后 {@code lenient()} 那两个桩会被真读到，
     * 故 STRICT_STUBS 不会再报未使用，红的理由只剩这一个，不会混噪声。
     */
    @Test
    @DisplayName("③-b 🔴 被覆盖行压成只读的写工具：运行期闸照声明值拦，绝不执行（D11）")
    void run_writeToolDowngradedByOverride_notExecuted() {
        when(aiModelApi.chat(any()))
                .thenReturn(resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"datamodel_create\",\"params\":{}}]}"))
                .thenReturn(resp("{\"type\":\"answer\",\"text\":\"改用只读工具\"}"));
        AgentToolRegistry.ToolDefinition downgraded = writeDefDowngradedByOverride();
        when(toolRegistry.get("datamodel_create")).thenReturn(downgraded);
        // 刻意桩上执行结果：若闸被绕过，这个桩会被真用到，断言就能明确指出「执行了」而不是「查不到」
        lenient().when(toolExecutor.execute(any(), eq("datamodel_create"), any())).thenReturn(Map.of("ok", true));

        String answer = toolLoop.run(context, "帮我建表", List.of("datamodel_create"),
                null, null, 100L, p -> {
                });

        assertEquals("改用只读工具", answer);
        verify(toolExecutor, never()).execute(any(), any(), any());
        // 🔴 为什么这里不再断言 verify(downgraded, never()).getRisk()（B2 之前是有的）：
        //    闸现在隔着 AgentToolCatalog 读工具，而 catalog 的 ToolView 是<b>视图对象</b>，
        //    同时供配置页的「生效值快照」端点使用，故它必然把 declared 与 effective 两格都读出来。
        //    红线因此从「校验路径上禁止调用 getRisk()」变成「闸只读 ToolView#declaredRisk()」，
        //    在 mock 层面已经表达不出来了。取而代之的钉法是这两条：
        //    ① 本用例自己就是变异探针 —— allowed 列表里<b>有</b> datamodel_create，
        //       所以「没执行」只可能是风险闸拦的；把闸改成 def.requireConfirm()（生效值），
        //       生效 READ_ONLY 会让它落到 toolExecutor.execute，上面那行立刻变红；
        //    ② AgentToolCatalogTest 钉住 catalog 不把两格混成一格
        //       （declared=IRREVERSIBLE / effective=READ_ONLY 时 declaredRequireConfirm 仍为 true）。
    }

    // ==================== ④ 未登记工具：绝不执行 ====================

    @Test
    @DisplayName("④ 模型点名未登记工具（registry 查不到）：绝不执行，回灌「工具不存在」让循环继续")
    void run_unregisteredToolRejected_notExecuted() {
        when(aiModelApi.chat(any()))
                .thenReturn(resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"ghost\",\"params\":{}}]}"))
                .thenReturn(resp("{\"type\":\"answer\",\"text\":\"没有该工具\"}"));
        when(toolRegistry.get("ghost")).thenReturn(null);

        String answer = toolLoop.run(context, "x", List.of("ro_tool"),
                null, null, 100L, p -> {
                });

        assertEquals("没有该工具", answer);
        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    // ==================== ⑤ maxSteps 耗尽 → fail-close ====================

    @Test
    @DisplayName("⑤ 到 maxSteps 仍吐 tool_calls：fail-close 抛 NOT_CONVERGED，不静默截断成半截结果")
    void run_maxStepsExhausted_throwsNotConverged() {
        loopProperties.setDefaultMaxSteps(1);
        when(aiModelApi.chat(any()))
                .thenReturn(resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"ro_tool\",\"params\":{}}]}"));
        AgentToolRegistry.ToolDefinition roDef = readOnlyDef();
        when(toolRegistry.get("ro_tool")).thenReturn(roDef);
        when(toolExecutor.execute(any(), eq("ro_tool"), any())).thenReturn("ok");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> toolLoop.run(context, "查", List.of("ro_tool"), null, null, 100L, p -> {
                }));

        assertEquals(AGENT_CHAT_TOOL_LOOP_NOT_CONVERGED.getCode(), ex.getCode());
    }

    // ==================== ⑥ 解析失败重试耗尽 → fail-close ====================

    @Test
    @DisplayName("⑥ 连续输出无法解析的 JSON 超过 maxParseRetries：fail-close 抛 NOT_CONVERGED，不无限回灌打转")
    void run_parseFailureRetriesExhausted_throwsNotConverged() {
        loopProperties.setMaxParseRetries(1);
        // 无 '{' → extractJsonObject 返回 null → 走「找不到 JSON」回灌分支，retries++
        when(aiModelApi.chat(any())).thenReturn(resp("我不知道该输出什么格式"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> toolLoop.run(context, "x", List.of("ro_tool"), null, null, 100L, p -> {
                }));

        assertEquals(AGENT_CHAT_TOOL_LOOP_NOT_CONVERGED.getCode(), ex.getCode());
        // 格式问题不推进 step、也不该执行任何工具
        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    // ==================== ⑦ 自定义工具（agent_tool_def，B2）====================

    /**
     * 🔴 这一节钉的是「第二类工具在<b>运行期</b>这道闸上的处置」。
     *
     * <p>B2 之前本类拿到自定义工具键会走 {@code def == null} 那一支，回灌「该工具不存在」——
     * 而 {@code AgentToolExecutor} 其实执行得了它。表现是：管理员登记好工具、绑到 Agent、
     * 发布成功、聊天里模型也确实点了它，然后模型收到一句「该工具不存在」，
     * 于是它开始向用户解释「系统里没有这个工具」。<b>配置全对、界面全绿、结果是编的</b>。
     *
     * <p>三件事各钉一刀：只读的<b>真能执行</b>、写工具<b>照样拒</b>（不因「是自定义的」而放宽）、
     * 结果<b>必被截断</b>（自定义工具拿不到 {@code truncateResult=false} 那个白名单待遇）。
     */
    private void customTool(String key, String riskLevel) {
        AgentToolDefDO row = new AgentToolDefDO();
        row.setToolKey(key);
        row.setSource("DATA_INTERFACE");
        row.setRiskLevel(riskLevel);
        row.setEnabled(true);
        row.setDisplayName("销售日报");
        // registry 查不到它（unstubbed → null），catalog 必然回落到自定义目录 —— 这正是要走的那条路
        when(toolDefService.getToolDefListIncludingDisabled()).thenReturn(List.of(row));
    }

    /** 第 N 次 chat 请求里那条回灌工具结果的 user 消息（没有则 null） */
    private static String observationOf(ArgumentCaptor<AiChatReqDTO> captor, int callIndex) {
        return captor.getAllValues().get(callIndex).getMessages().stream()
                .filter(m -> "user".equals(m.getRole()) && m.getContent().contains("工具执行结果"))
                .map(AiChatReqDTO.Message::getContent)
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("⑦-a 🔴 自定义只读工具：真被执行，结果回灌进下一轮（此前回灌的是「该工具不存在」）")
    void run_customReadOnlyTool_executed() {
        when(aiModelApi.chat(any()))
                .thenReturn(resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"sales_daily_report\",\"params\":{\"day\":\"2026-10-01\"}}]}"))
                .thenReturn(resp("{\"type\":\"answer\",\"text\":\"当天销售额 12 万\"}"));
        customTool("sales_daily_report", "READ_ONLY");
        when(toolExecutor.execute(any(), eq("sales_daily_report"), any())).thenReturn(Map.of("amount", 120000));

        List<String> progress = new ArrayList<>();
        String answer = toolLoop.run(context, "10 月 1 日销售额多少", List.of("sales_daily_report"),
                null, null, 100L, progress::add);

        assertEquals("当天销售额 12 万", answer);
        verify(toolExecutor).execute(any(), eq("sales_daily_report"), any());
        assertEquals(List.of("正在调查：sales_daily_report"), progress);

        // 🔴 结果必须真进了下一轮的上下文，而不只是「调用了执行器」：
        //    调了但没回灌，模型照样只能凭空编一个数字 —— 那和 B2 之前的病症一模一样，
        //    只是从「说不存在」变成「说不知道」
        ArgumentCaptor<AiChatReqDTO> rounds = ArgumentCaptor.forClass(AiChatReqDTO.class);
        verify(aiModelApi, times(2)).chat(rounds.capture());
        String observation = observationOf(rounds, 1);
        assertNotNull(observation, "第二轮必须带上工具执行结果那条 user 消息");
        assertTrue(observation.contains("120000"), "结果正文要回灌给模型，实际：" + observation);
        assertTrue(observation.contains("tool_observation"),
                "🔴 工具结果是污点源，必须各自包成数据段（B2-3）。实际：" + observation);
    }

    @Test
    @DisplayName("⑦-b 🔴 自定义写工具：照样拒，绝不因「是用户自己登记的」而放宽只读铁律")
    void run_customWriteTool_notExecuted() {
        when(aiModelApi.chat(any()))
                .thenReturn(resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"sales_reset\",\"params\":{}}]}"))
                .thenReturn(resp("{\"type\":\"answer\",\"text\":\"这个操作我不能替你做\"}"));
        customTool("sales_reset", "IRREVERSIBLE");

        String answer = toolLoop.run(context, "把销售数据清零", List.of("sales_reset"),
                null, null, 100L, p -> {
                });

        assertEquals("这个操作我不能替你做", answer);
        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("⑦-c 🔴 risk_level 漏填的自定义工具：按 HIGH 兜 ⟹ 拒执行（宁可多说风险）")
    void run_customToolWithBlankRisk_notExecuted() {
        when(aiModelApi.chat(any()))
                .thenReturn(resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"risk_missing\",\"params\":{}}]}"))
                .thenReturn(resp("{\"type\":\"answer\",\"text\":\"查不了\"}"));
        customTool("risk_missing", null);

        toolLoop.run(context, "x", List.of("risk_missing"), null, null, 100L, p -> {
        });

        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("⑦-d 🔴 自定义工具的结果必被截断：truncateResult=false 是代码里的白名单，数据接口拿不到")
    void run_customReadOnlyTool_resultIsTruncated() {
        loopProperties.setToolResultMaxChars(50);
        when(aiModelApi.chat(any()))
                .thenReturn(resp("{\"type\":\"tool_calls\",\"calls\":[{\"tool\":\"sales_daily_report\",\"params\":{}}]}"))
                .thenReturn(resp("{\"type\":\"answer\",\"text\":\"数据太多\"}"));
        customTool("sales_daily_report", "READ_ONLY");
        // 数据接口回多大平台无从静态断言 —— 这正是它不能进白名单的理由，故这里刻意造一个超长的
        when(toolExecutor.execute(any(), eq("sales_daily_report"), any()))
                .thenReturn(Map.of("rows", "x".repeat(500)));

        toolLoop.run(context, "x", List.of("sales_daily_report"), null, null, 100L, p -> {
        });

        ArgumentCaptor<AiChatReqDTO> rounds = ArgumentCaptor.forClass(AiChatReqDTO.class);
        verify(aiModelApi, times(2)).chat(rounds.capture());
        String observation = observationOf(rounds, 1);
        assertNotNull(observation);
        assertTrue(observation.contains("已截断"),
                "🔴 白名单待遇（不截断）只给代码里 @AgentTool(truncateResult=false) 声明过的工具。实际：" + observation);
        assertFalse(observation.contains("x".repeat(500)), "原文不该整段进上下文，实际：" + observation);
    }

}
