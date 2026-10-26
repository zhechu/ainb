package cn.enlev.ainb.module.agent.service.chat;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentMessageDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentChatProperties;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentPlanProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentIntentClassifier;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentPromptBuilder;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentSuggestionParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentSecurityGateway;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolExecutor;
import cn.enlev.ainb.module.agent.service.chat.AgentAssistantService.AgentChatEvent;
import cn.enlev.ainb.module.agent.service.chat.AgentAssistantService.EventType;
import cn.enlev.ainb.module.agent.service.conversation.AgentConversationService;
import cn.enlev.ainb.module.agent.service.def.AgentDefService;
import cn.enlev.ainb.module.agent.service.quota.AgentQuotaService;
import cn.enlev.ainb.module.agent.service.usage.AgentUsageLogService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService.WorkflowEvent;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.rag.AiRagApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_CONVERSATION_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「聊天内按 code 跑工作流」入口的接线单测（A1，0 token）
 *
 * <h3>这个入口为什么存在</h3>
 * 助手给出工作流草稿卡后，用户点「确认并运行」原先调的是 {@code POST /agent/workflow/run-stream} ——
 * 那条通道只写 {@code agent_workflow_execution}、<b>全程不碰 agent_message</b>，而聊天历史与 LLM 的记忆
 * 都来自 agent_message。于是回复刷新即消失，用户接着追问一句，模型完全不知道刚才跑过什么
 * （原话：「连个记忆功能都没有吗」）。修法是<b>加一个带 conversationId 的助手入口</b>
 * {@link AgentAssistantService#runWorkflowInChat}，而不是把落库逻辑塞进那条通用通道。
 *
 * <h3>钉死什么</h3>
 * <ol>
 *   <li><b>回复真的落库</b>：一条 role=assistant、content=流程 output 的消息（这是本入口存在的全部理由）</li>
 *   <li><b>落的那条不是草稿卡</b>：{@code workflow_def_id} 必须保持 null —— 前端 {@code loadMessages}
 *       见到非空的 workflow_def_id 就把它重建成<b>又一张</b>草稿卡，于是刷新后同一次运行会显示成
 *       「一张卡 + 一条重复的卡」，而回复正文不见了</li>
 *   <li><b>越权守卫</b>：{@code getConversation(id, userId)} 必须被调到；它抛错时<b>同步</b>失败，
 *       一步都不往下走（否则就成了拿别人 conversationId 往里塞消息的写入口）</li>
 *   <li><b>不双计量</b>（DD8）：工作流各节点自记计量，本入口 {@code usageLogService.record} 恒 never</li>
 *   <li><b>PLANNING 带机器键</b>：{@code executionId}/{@code nodeId}/{@code nodeName} 必须随进度事件下发 ——
 *       executionId 是断连提示语（B3-8）报编号的唯一来源，nodeId 供草稿卡点亮「已跑完」的节点格子</li>
 * </ol>
 *
 * <p>与 {@code AgentAssistantServiceImplWorkflowRouteTest}（按 agentDefId 反查绑定 chatflow）、
 * {@code AgentAssistantServiceImplWorkflowResumeTest}（hitl 续跑）是三个入口共用同一套
 * {@code AgentAssistantServiceImpl#bridgeWorkflowRun} 骨架；本类只钉「起步方式 + 同步校验段」的差异，
 * 桥接与落库的共性由那两个类覆盖。
 *
 * @author 走进新时代
 */
public class AgentAssistantServiceImplRunWorkflowInChatTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentAssistantServiceImpl assistantService;

    @Mock
    private AgentSecurityGateway gateway;
    @Mock
    private AgentToolExecutor toolExecutor;
    @Mock
    private AgentConversationService conversationService;
    @Spy
    private AgentPromptBuilder promptBuilder = new AgentPromptBuilder();
    @Mock
    private AgentSuggestionParser suggestionParser;
    @Mock
    private AgentUsageLogService usageLogService;
    @Mock
    private AiModelApi aiModelApi;
    @Spy
    private AgentChatProperties chatProperties = new AgentChatProperties();
    @Mock
    private AgentIntentClassifier intentClassifier;
    @Mock
    private AgentPlanProperties planProperties;
    @Mock
    private AgentQuotaService quotaService;
    @Mock
    private AgentDefService agentDefService;
    @Mock
    private AiRagApi aiRagApi;
    /** A1：按 code 取已发布定义 */
    @Mock
    private AgentWorkflowDefService defService;
    /** A1：起一条执行 */
    @Mock
    private AgentWorkflowExecutionService workflowExecutionService;
    /** A1：解析 DSL 里的 inputVar/outputVar 与节点显示名 */
    @Mock
    private DslParser dslParser;

    private static final Long CONVERSATION_ID = 100L;

    @BeforeEach
    public void setUp() {
        LoginUser loginUser = new LoginUser().setId(1L).setTenantId(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
        TenantContextHolder.setTenantId(1L);

        // getConversation 只作归属校验、返回值被忽略，故不必 stub；appendMessage 返回 messageId 供 settle 用
        lenient().when(conversationService.appendMessage(any())).thenReturn(300L);
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    private AgentWorkflowDefDO publishedWorkflow(String code) {
        AgentWorkflowDefDO wf = new AgentWorkflowDefDO();
        wf.setId(777L);
        wf.setCode(code);
        return wf;
    }

    private List<AgentChatEvent> run(String code, Map<String, Object> input) {
        return assistantService.runWorkflowInChat(code, input, CONVERSATION_ID,
                AgentExecutionContext.snapshot()).collectList().block();
    }

    /** 本轮落库的全部消息（正常只有 assistant 那一条） */
    private List<AgentMessageDO> capturedMessages() {
        ArgumentCaptor<AgentMessageDO> captor = ArgumentCaptor.forClass(AgentMessageDO.class);
        verify(conversationService, atLeastOnce()).appendMessage(captor.capture());
        return captor.getAllValues();
    }

    private AgentMessageDO capturedAssistantMessage() {
        return capturedMessages().stream()
                .filter(m -> AgentMessageDO.ROLE_ASSISTANT.equals(m.getRole()))
                .findFirst().orElse(null);
    }

    // ==================== ① 主路：桥事件 + 落库 + 不双计量 ====================

    @Test
    @DisplayName("① 按 code 跑已发布流程：META 带会话号、NODE→PLANNING 带机器键、DONE→MESSAGE，并落一条 assistant 消息")
    public void testRunWorkflowInChatBridgesAndPersists() {
        when(defService.getPublishedByCode("wf_draft")).thenReturn(publishedWorkflow("wf_draft"));
        when(workflowExecutionService.run(eq("wf_draft"), eq(Map.of()), any()))
                .thenReturn(Flux.just(
                        WorkflowEvent.meta(900L, "thread-1"),
                        WorkflowEvent.node(900L, "step_1", Map.of("output", "中间态")),
                        WorkflowEvent.done(900L, Map.of("output", "工作流回复"))));

        List<AgentChatEvent> events = run("wf_draft", Map.of());

        // 首事件 META 带会话号（前端靠它确认落到了哪个会话）
        assertEquals(EventType.META, events.get(0).getType(), "首事件必须是 META");
        assertEquals(CONVERSATION_ID, events.get(0).getConversationId());
        assertTrue(events.stream().anyMatch(e -> e.getType() == EventType.MESSAGE
                && "工作流回复".equals(e.getContent())), "DONE 抽 output 桥成 MESSAGE");
        assertTrue(events.stream().anyMatch(e -> e.getType() == EventType.DONE), "以 DONE 收尾");

        // 🔴 PLANNING 带机器键：executionId 是断连提示语报编号的唯一来源，nodeId 供草稿卡点亮已跑完的节点
        AgentChatEvent planning = events.stream()
                .filter(e -> e.getType() == EventType.PLANNING && "step_1".equals(e.getNodeId()))
                .findFirst().orElse(null);
        assertNotNull(planning, "NODE 桥成 PLANNING，并带上原始 nodeId（靠显示名反查在重名时会点错格子）");
        assertEquals(900L, planning.getExecutionId(), "进度事件带 executionId");
        assertEquals("正在执行：step_1", planning.getContent(), "DSL 无节点名时回落 nodeId");

        // 🔴 这个入口存在的全部理由：回复落进 agent_message，刷新后还在、LLM 也读得到
        AgentMessageDO assistant = capturedAssistantMessage();
        assertNotNull(assistant, "必须落一条助手消息（否则刷新即失忆，正是 A1 要修的缺陷）");
        assertEquals("工作流回复", assistant.getContent());
        assertEquals("SUCCESS", assistant.getStatus());
        assertEquals(CONVERSATION_ID, assistant.getConversationId());

        // 🔴 越权守卫 + DD8 不双计量
        verify(conversationService).getConversation(eq(CONVERSATION_ID), any());
        verify(usageLogService, never()).record(any());
        // 只落 assistant 消息：这一击没有新的用户输入，触发规划的那句提问早就落过库了，再补一条会显示两遍
        assertEquals(1, capturedMessages().size(), "不补落 user 消息");
    }

    // ==================== ② 落的那条不能被重建成草稿卡 ====================

    /**
     * 🔴 这条断言看着像废话（{@code settleWorkflowAnswer} 从来就没写过 workflowDefId），
     * 但它钉的是一个<b>很容易「顺手补上」</b>的陷阱：前端 {@code loadMessages} 的重建规则是
     * 「workflow_def_id 非空 ⟹ 这是一张草稿卡，去调 workflow-draft 重新装配」，
     * 「execution_id 非空 ⟹ 这是一张 hitl 确认卡」。
     *
     * <p>本入口跑的正是某张草稿卡对应的流程，于是「把 workflow_def_id 也写上，好让刷新后还能看到卡片」
     * 是一个非常有诱惑力的改动 —— 而它的后果是：刷新后那一轮变成<b>两张</b>草稿卡
     * （原来那张 + 回复这条被误判成的那张），真正的回复正文反而不见了。
     * 正常跑完的回复就该是普通气泡；只有 INTERRUPTED 那一支才写 execution_id。
     */
    @Test
    @DisplayName("② 🔴 正常跑完的回复：workflow_def_id 保持 null（写了就会在刷新后被重建成第二张草稿卡）")
    public void testPersistedReplyIsNotRebuiltAsDraftCard() {
        when(defService.getPublishedByCode("wf_draft")).thenReturn(publishedWorkflow("wf_draft"));
        when(workflowExecutionService.run(any(), any(), any()))
                .thenReturn(Flux.just(WorkflowEvent.done(900L, Map.of("output", "工作流回复"))));

        run("wf_draft", Map.of());

        AgentMessageDO assistant = capturedAssistantMessage();
        assertNotNull(assistant);
        assertNull(assistant.getWorkflowDefId(),
                "🔴 必须为 null：非空 ⟹ 前端把它重建成又一张草稿卡，回复正文消失");
        assertNull(assistant.getExecutionId(), "正常跑完不是中断，不该带确认卡片的钥匙");
    }

    // ==================== ③ 越权守卫：同步 fail-close ====================

    @Test
    @DisplayName("③ 🔴 会话不属于当前用户：同步抛错，一步都不往下走（不跑流程、不落消息）")
    public void testConversationOwnershipIsEnforced() {
        when(defService.getPublishedByCode("wf_draft")).thenReturn(publishedWorkflow("wf_draft"));
        // conversationId 由前端传入 ⟹ 不校验归属就成了往任意会话塞消息的写入口
        when(conversationService.getConversation(eq(CONVERSATION_ID), any()))
                .thenThrow(exception(AGENT_CONVERSATION_NOT_EXISTS));

        // 🔴 校验段在 Servlet 线程上同步执行（不是流内 onError），Controller 那层 try/catch 才接得住并转成流内 ERROR
        assertThrows(ServiceException.class, () -> run("wf_draft", Map.of()));

        verify(workflowExecutionService, never()).run(any(), any(), any());
        verify(conversationService, never()).appendMessage(any());
    }

    // ==================== ④ input 为 null：兜成空 Map ====================

    @Test
    @DisplayName("④ input 传 null：兜成空 Map 交给 run（null 会被原样塞进流程变量表，下游是 NPE）")
    public void testNullInputBecomesEmptyMap() {
        when(defService.getPublishedByCode("wf_draft")).thenReturn(publishedWorkflow("wf_draft"));
        when(workflowExecutionService.run(eq("wf_draft"), eq(Map.of()), any()))
                .thenReturn(Flux.just(WorkflowEvent.done(900L, Map.of("output", "ok"))));

        List<AgentChatEvent> events = run("wf_draft", null);

        verify(workflowExecutionService).run(eq("wf_draft"), eq(Map.of()), any());
        assertTrue(events.stream().anyMatch(e -> e.getType() == EventType.DONE), "照常跑完");
    }

}
