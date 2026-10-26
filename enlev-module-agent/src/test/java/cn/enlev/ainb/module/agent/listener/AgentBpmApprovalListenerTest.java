package cn.enlev.ainb.module.agent.listener;

import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService;
import cn.enlev.ainb.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentBpmApprovalListener} 单测（P6 收口，纯 mock，0 token）
 *
 * <p>钉住「审批完成 → 唤醒工作流」的五条：
 * <ol>
 *   <li>🔴 只认 {@code agent-wf:} 前缀的 businessKey；在线表单/OA 等其它流程事件原样放过（publishEvent never）</li>
 *   <li>🔴 只在流程终态唤醒：RUNNING(1) 忽略；APPROVE(2)/REJECT(3)/CANCEL(4) 才 publishEvent</li>
 *   <li>payload 归一：APPROVE→bpm_approved=true、REJECT/CANCEL→false，bpm_status 透传原始码</li>
 *   <li>🔴 fail-close：捕获不到完整登录身份 → 跳过唤醒（不匿名），工作流停在挂起态等人工</li>
 *   <li>🔴 唤醒抛错被 try/catch 吞掉，绝不外泄波及 BPM 审批事务本身</li>
 * </ol>
 *
 * <p>⚠️ 「事件在审批人同步线程上发布、租户即执行所有者租户」这一事务/线程语义无法在纯 mock 单测里验证，
 * 由 e2e 用真实 BPM 审批覆盖；此处只验监听器被回调后的过滤/归一/派发/容错逻辑。
 *
 * @author 走进新时代
 */
@DisplayName("agent AgentBpmApprovalListener 审批完成唤醒工作流")
class AgentBpmApprovalListenerTest {

    private AgentBpmApprovalListener listener;
    private AgentWorkflowExecutionService executionService;

    @BeforeEach
    void setUp() {
        executionService = mock(AgentWorkflowExecutionService.class);
        listener = new AgentBpmApprovalListener();
        ReflectionTestUtils.setField(listener, "executionService", executionService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    /** 在 SecurityContext + TenantContext 里贴上完整登录身份，使 snapshot().isComplete() 为真 */
    private void givenLoginContext() {
        LoginUser user = new LoginUser();
        user.setId(9L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()));
        TenantContextHolder.setTenantId(1L);
    }

    private BpmProcessInstanceStatusEvent event(String businessKey, Integer status) {
        BpmProcessInstanceStatusEvent e = new BpmProcessInstanceStatusEvent(this);
        e.setId("pi-1");
        e.setProcessDefinitionKey("leave_key");
        e.setBusinessKey(businessKey);
        e.setStatus(status);
        return e;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePayload(String expectedKey) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(executionService).publishEvent(eq(expectedKey), captor.capture(), any(AgentExecutionContext.class));
        return captor.getValue();
    }

    @Test
    @DisplayName("🔴 非 agent-wf 前缀（在线表单/OA）→ 原样放过，不 publishEvent")
    void onEvent_nonAgentBusinessKey_ignored() {
        givenLoginContext();
        listener.onApplicationEvent(event("leave:123", 2));
        verify(executionService, never()).publishEvent(any(), any(), any());
    }

    @Test
    @DisplayName("🔴 RUNNING(1) 中间态 → 忽略，审批还没结束")
    void onEvent_runningStatus_ignored() {
        givenLoginContext();
        listener.onApplicationEvent(event("agent-wf:1:n", 1));
        verify(executionService, never()).publishEvent(any(), any(), any());
    }

    @Test
    @DisplayName("APPROVE(2) → publishEvent，payload bpm_approved=true、bpm_status=2")
    void onEvent_approve_publishesApprovedTrue() {
        givenLoginContext();
        when(executionService.publishEvent(any(), any(), any())).thenReturn(1);

        listener.onApplicationEvent(event("agent-wf:1:n", 2));

        Map<String, Object> payload = capturePayload("agent-wf:1:n");
        assertEquals(true, payload.get("bpm_approved"));
        assertEquals(2, payload.get("bpm_status"));
    }

    @Test
    @DisplayName("REJECT(3) → publishEvent，payload bpm_approved=false、bpm_status=3")
    void onEvent_reject_publishesApprovedFalse() {
        givenLoginContext();
        when(executionService.publishEvent(any(), any(), any())).thenReturn(1);

        listener.onApplicationEvent(event("agent-wf:1:n", 3));

        Map<String, Object> payload = capturePayload("agent-wf:1:n");
        assertEquals(false, payload.get("bpm_approved"));
        assertEquals(3, payload.get("bpm_status"));
    }

    @Test
    @DisplayName("CANCEL(4) → publishEvent，payload bpm_approved=false、bpm_status=4")
    void onEvent_cancel_publishes() {
        givenLoginContext();
        when(executionService.publishEvent(any(), any(), any())).thenReturn(1);

        listener.onApplicationEvent(event("agent-wf:1:n", 4));

        Map<String, Object> payload = capturePayload("agent-wf:1:n");
        assertEquals(false, payload.get("bpm_approved"));
        assertEquals(4, payload.get("bpm_status"));
    }

    @Test
    @DisplayName("🔴 捕获不到完整登录身份 → fail-close 跳过唤醒，绝不匿名 publishEvent")
    void onEvent_incompleteContext_skips() {
        // 刻意不 givenLoginContext()：snapshot() 拿到空身份，isComplete() 为假
        listener.onApplicationEvent(event("agent-wf:1:n", 2));
        verify(executionService, never()).publishEvent(any(), any(), any());
    }

    @Test
    @DisplayName("🔴 publishEvent 抛错 → try/catch 吞掉，绝不外泄波及 BPM 审批事务")
    void onEvent_publishThrows_swallowed() {
        givenLoginContext();
        when(executionService.publishEvent(any(), any(), any())).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> listener.onApplicationEvent(event("agent-wf:1:n", 2)),
                "唤醒失败不能让 BPM 审批本身回滚——审批结果以 Flowable 为准，工作流可事后人工 resume");
    }

}
