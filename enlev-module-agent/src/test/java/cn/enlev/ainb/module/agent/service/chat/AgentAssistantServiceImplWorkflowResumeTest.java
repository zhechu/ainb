package cn.enlev.ainb.module.agent.service.chat;

import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentMessageDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聊天内「恢复挂起工作流」的接线单测（增量13 / 收口 slice-3c 的 RK5，0 token）
 *
 * <h3>钉死什么</h3>
 * 工作流驱动的 Agent 绑定的 chatflow 在 {@code hitl.confirm} 节点挂起后，用户在聊天里点确认卡片的
 * 「同意/拒绝」→ {@link AgentAssistantService#resumeWorkflowInChat} 把决定经 {@code resume} 的 feedback
 * 写进 checkpoint 续跑，再把续跑的 {@link WorkflowEvent} 桥成 {@link AgentChatEvent} 回推、落一条 assistant 消息。
 *
 * <p>本类用 {@code never()}/{@code eq()} 钉死四件最易漂移的事：
 * <ol>
 *   <li><b>approveVar 键名</b>：resume 必须收到 {@code Map.of("hitl_approved", 决定)} —— 键名与
 *       {@code HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR} 一字不差，否则节点读到 null 恒判「拒绝」，
 *       用户点了「同意」流程却按拒绝走，且无任何报错（本平台头号反模式的翻版）</li>
 *   <li><b>越权守卫</b>：必须校验会话归属（{@code getConversation(id, userId)}），堵住拿别人 conversationId 塞消息</li>
 *   <li><b>不双计量</b>（DD8）：续跑各节点自记计量，chat 侧 {@code usageLogService.record} 恒 never</li>
 *   <li><b>递归确认</b>：续跑又撞上 hitl → 再发 WORKFLOW_CONFIRM，而非死在第一次</li>
 * </ol>
 *
 * @author 走进新时代
 */
public class AgentAssistantServiceImplWorkflowResumeTest extends BaseMockitoUnitTest {

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
    /** 取回挂起执行对应的流程定义（resolveChatVars 用其 DSL 定 outputVar） */
    @Mock
    private AgentWorkflowDefService defService;
    /** 恢复挂起执行 */
    @Mock
    private AgentWorkflowExecutionService workflowExecutionService;
    @Mock
    private DslParser dslParser;

    @BeforeEach
    public void setUp() {
        LoginUser loginUser = new LoginUser().setId(1L).setTenantId(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
        TenantContextHolder.setTenantId(1L);

        // appendMessage 返回一个 messageId（settle 落库用）；getConversation 不必 stub——只作归属校验、返回值被忽略
        lenient().when(conversationService.appendMessage(any())).thenReturn(300L);
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    /** 一个处于 INTERRUPTED、归属定义 16L 的执行实例 */
    private AgentWorkflowExecutionDO interruptedExec(Long executionId, Long defId) {
        AgentWorkflowExecutionDO exec = new AgentWorkflowExecutionDO();
        exec.setId(executionId);
        exec.setWorkflowDefId(defId);
        return exec;
    }

    /** 绑定 chatflow 的定义（DSL 留空 → resolveChatVars fail-soft 回落 input/output） */
    private AgentWorkflowDefDO chatWorkflow(String code) {
        AgentWorkflowDefDO wf = new AgentWorkflowDefDO();
        wf.setCode(code);
        return wf;
    }

    private List<AgentChatEvent> resume(Long executionId, boolean approved, Long conversationId) {
        return assistantService.resumeWorkflowInChat(executionId, approved, conversationId,
                AgentExecutionContext.snapshot()).collectList().block();
    }

    private AgentMessageDO capturedAssistantMessage() {
        ArgumentCaptor<AgentMessageDO> captor = ArgumentCaptor.forClass(AgentMessageDO.class);
        verify(conversationService, atLeastOnce()).appendMessage(captor.capture());
        return captor.getAllValues().stream()
                .filter(m -> AgentMessageDO.ROLE_ASSISTANT.equals(m.getRole()))
                .findFirst().orElse(null);
    }

    private boolean hasEvent(List<AgentChatEvent> events, EventType type, String contentEquals) {
        return events.stream().anyMatch(e -> e.getType() == type
                && (contentEquals == null || contentEquals.equals(e.getContent())));
    }

    // ==================== ① 同意续跑 → DONE → 最终回复 ====================

    @Test
    @DisplayName("① 同意(approved=true)：resume 喂 hitl_approved=true、桥 DONE→MESSAGE(output)+DONE、落最终回复、校验会话归属、不记CHAT计量")
    public void testResumeApprovedRunsToDone() {
        when(workflowExecutionService.getExecution(eq(900L), any())).thenReturn(interruptedExec(900L, 16L));
        when(defService.getDef(16L)).thenReturn(chatWorkflow("wf_chat"));
        when(workflowExecutionService.resume(eq(900L), eq(Map.of("hitl_approved", true)), any()))
                .thenReturn(Flux.just(
                        WorkflowEvent.node(900L, "after_hitl", Map.of("output", "中间态")),
                        WorkflowEvent.done(900L, Map.of("output", "确认后的最终回复"))));

        List<AgentChatEvent> events = resume(900L, true, 100L);

        // 🔴 键名一字不差：resume 收到默认 approveVar 键 hitl_approved=true（HitlConfirmNodeExecutor 读的正是它）
        verify(workflowExecutionService).resume(eq(900L), eq(Map.of("hitl_approved", true)), any());
        // 🔴 越权守卫：校验会话归属当前用户
        verify(conversationService).getConversation(eq(100L), any());
        // 桥接保真：META 首事件、DONE 抽 output→MESSAGE、以 DONE 收尾
        assertEquals(EventType.META, events.get(0).getType(), "首事件必须是 META");
        assertTrue(hasEvent(events, EventType.MESSAGE, "确认后的最终回复"), "续跑 DONE 抽 output 桥成 MESSAGE");
        assertTrue(events.stream().anyMatch(e -> e.getType() == EventType.DONE), "以 DONE 收尾");
        // 🔴 DD8：续跑各节点自记计量，chat 侧不再记 CHAT
        verify(usageLogService, never()).record(any());

        // 落最终回复消息：execution_id 为 null（普通气泡，非确认卡片）
        AgentMessageDO assistant = capturedAssistantMessage();
        assertNotNull(assistant, "续跑成功必须落一条助手消息");
        assertEquals("确认后的最终回复", assistant.getContent());
        assertEquals("SUCCESS", assistant.getStatus());
        assertNull(assistant.getExecutionId(), "最终回复是普通气泡，不带 execution_id");
    }

    // ==================== ② 拒绝续跑 ====================

    @Test
    @DisplayName("② 拒绝(approved=false)：resume 喂 hitl_approved=false（拒绝）")
    public void testResumeRejected() {
        when(workflowExecutionService.getExecution(eq(900L), any())).thenReturn(interruptedExec(900L, 16L));
        when(defService.getDef(16L)).thenReturn(chatWorkflow("wf_chat"));
        when(workflowExecutionService.resume(eq(900L), eq(Map.of("hitl_approved", false)), any()))
                .thenReturn(Flux.just(WorkflowEvent.done(900L, Map.of("output", "已按拒绝处理"))));

        List<AgentChatEvent> events = resume(900L, false, 100L);

        // 🔴 拒绝也是同一个键、值为 false（节点据此走 reject 分支）
        verify(workflowExecutionService).resume(eq(900L), eq(Map.of("hitl_approved", false)), any());
        assertTrue(hasEvent(events, EventType.MESSAGE, "已按拒绝处理"), "拒绝续跑的结果照常回推");
    }

    // ==================== ③ 续跑又撞上 hitl → 递归确认卡片 ====================

    @Test
    @DisplayName("③ 续跑又撞上 hitl（INTERRUPTED）：再发 WORKFLOW_CONFIRM（递归确认）+ 落新 execution_id + DONE")
    public void testResumeHitsAnotherHitl() {
        when(workflowExecutionService.getExecution(eq(900L), any())).thenReturn(interruptedExec(900L, 16L));
        when(defService.getDef(16L)).thenReturn(chatWorkflow("wf_chat"));
        when(workflowExecutionService.resume(eq(900L), any(), any()))
                .thenReturn(Flux.just(WorkflowEvent.interrupted(900L, "hitl_2")));

        List<AgentChatEvent> events = resume(900L, true, 100L);

        AgentChatEvent confirm = events.stream()
                .filter(e -> e.getType() == EventType.WORKFLOW_CONFIRM).findFirst().orElse(null);
        assertNotNull(confirm, "续跑撞上另一个 hitl → 再发确认卡片（可递归，而非死在第一次）");
        assertEquals("hitl_2", confirm.getNodeId(), "卡片带新的挂起节点 id");
        assertEquals(900L, confirm.getExecutionId());
        assertTrue(events.stream().anyMatch(e -> e.getType() == EventType.DONE), "以 DONE 收尾，等下一次确认");

        AgentMessageDO assistant = capturedAssistantMessage();
        assertNotNull(assistant);
        assertEquals(900L, assistant.getExecutionId(), "新的确认卡片同样落 execution_id 供刷新重建");
    }

    // ==================== ④ 续跑回 ERROR ====================

    @Test
    @DisplayName("④ 续跑回 ERROR 事件：桥成 AgentChatEvent.ERROR + 落 FAILURE 助手消息，SSE 不断连")
    public void testResumeErrorEventBridges() {
        when(workflowExecutionService.getExecution(eq(900L), any())).thenReturn(interruptedExec(900L, 16L));
        when(defService.getDef(16L)).thenReturn(chatWorkflow("wf_chat"));
        when(workflowExecutionService.resume(eq(900L), any(), any()))
                .thenReturn(Flux.just(WorkflowEvent.error(900L, "续跑节点炸了")));

        List<AgentChatEvent> events = resume(900L, true, 100L);

        assertTrue(events.stream().anyMatch(e -> e.getType() == EventType.ERROR
                && "续跑节点炸了".equals(e.getErrorMsg())), "ERROR 事件桥成 AgentChatEvent.ERROR（而非断连）");
        verify(usageLogService, never()).record(any());
        AgentMessageDO assistant = capturedAssistantMessage();
        assertNotNull(assistant);
        assertEquals("FAILURE", assistant.getStatus());
    }

    // ==================== ⑤ resume() 组装期抛错 → defer 兜成流内 ERROR ====================

    @Test
    @DisplayName("⑤ resume() 组装期抛错（如已被并发恢复/状态不符）：defer 兜成流内 onError → ERROR + settle(failure)")
    public void testResumeThrowsBridgesToError() {
        when(workflowExecutionService.getExecution(eq(900L), any())).thenReturn(interruptedExec(900L, 16L));
        when(defService.getDef(16L)).thenReturn(chatWorkflow("wf_chat"));
        when(workflowExecutionService.resume(eq(900L), any(), any()))
                .thenThrow(new IllegalStateException("执行已被恢复"));

        List<AgentChatEvent> events = resume(900L, true, 100L);

        assertTrue(events.stream().anyMatch(e -> e.getType() == EventType.ERROR),
                "组装期异常经 defer 变流内 onError → ERROR 事件（不绕过 settle 回调）");
        AgentMessageDO assistant = capturedAssistantMessage();
        assertNotNull(assistant, "异常也要落一条助手消息，否则刷新后只看到提问悬着");
        assertEquals("FAILURE", assistant.getStatus());
    }

}
