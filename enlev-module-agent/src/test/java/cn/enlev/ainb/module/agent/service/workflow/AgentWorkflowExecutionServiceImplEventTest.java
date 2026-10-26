package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_EXECUTION_STATE_INVALID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentWorkflowExecutionServiceImpl#publishEvent} 单测（P2 增量4b，纯 mock/spy，0 token）
 *
 * <p>断言：① 命中行以<b>行身份</b>（userId/tenantId 取自 DO）被 resume、payload 原样透传为 feedback；
 * ② userId 为空的行被跳过；③ 空匹配返回 0、不触发 resume；④ resume 抛 STATE_INVALID（已被 CAS 认领）被吞、不冒泡；
 * ⑤ resume 抛通用异常被吞、不冒泡。
 *
 * <p>🔴 用 Mockito <b>spy</b> 真 Service 并 stub 其 {@code resume(...)}（spy 是子类代理，{@code publishEvent} 内部的
 * {@code this.resume(...)} 自调用会被拦截），从而隔离「唤醒派发逻辑」与「真正的图恢复执行」。{@code executionMapper} 用
 * {@code ReflectionTestUtils} 注入 mock。发布者身份用 {@code ofForTest}（complete，过 requireIdentity）。
 *
 * @author 走进新时代
 */
class AgentWorkflowExecutionServiceImplEventTest {

    private AgentWorkflowExecutionServiceImpl service;
    private AgentWorkflowExecutionMapper executionMapper;

    /** 发布者身份（Servlet 线程快照的替身）；complete，能过 requireIdentity */
    private final AgentExecutionContext publisher = AgentExecutionContext.ofForTest(1L, 1L);

    @BeforeEach
    void setUp() {
        executionMapper = mock(AgentWorkflowExecutionMapper.class);
        service = spy(new AgentWorkflowExecutionServiceImpl());
        ReflectionTestUtils.setField(service, "executionMapper", executionMapper);
    }

    private static AgentWorkflowExecutionDO exec(Long id, Long userId, Long tenantId) {
        AgentWorkflowExecutionDO e = new AgentWorkflowExecutionDO();
        e.setId(id);
        e.setUserId(userId);
        e.setTenantId(tenantId);
        e.setStatus("INTERRUPTED");
        e.setEventKey("k");
        return e;
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishEvent_wakesMatchedExecutions_withRowIdentityAndPayload() {
        when(executionMapper.selectInterruptedByEventKey(eq("k"), anyInt()))
                .thenReturn(List.of(exec(10L, 9L, 1L)));
        doReturn(Flux.empty()).when(service).resume(anyLong(), any(), any());

        int dispatched = service.publishEvent("k", Map.of("theId", 6), publisher);

        assertEquals(1, dispatched, "命中 1 条应派发 1 条恢复");
        ArgumentCaptor<Map<String, Object>> inputsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<AgentExecutionContext> ctxCaptor = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(service, times(1)).resume(eq(10L), inputsCaptor.capture(), ctxCaptor.capture());
        // payload 原样透传为 resume 的 feedback
        assertEquals(6, inputsCaptor.getValue().get("theId"), "payload 应作为 feedback 透传给 resume");
        // 🔴 唤醒身份用「行身份」（userId/tenantId 取自 DO），不是发布者
        assertEquals(9L, ctxCaptor.getValue().getUserId().longValue(), "resume 身份 userId 应取自 DO");
        assertEquals(1L, ctxCaptor.getValue().getTenantId().longValue(), "resume 身份 tenantId 应取自 DO");
    }

    @Test
    void publishEvent_skipsRowsWithoutUserId() {
        when(executionMapper.selectInterruptedByEventKey(eq("k"), anyInt()))
                .thenReturn(List.of(exec(11L, null, 1L)));

        int dispatched = service.publishEvent("k", Map.of(), publisher);

        assertEquals(0, dispatched, "userId 为空无法合成恢复身份，应跳过");
        verify(service, never()).resume(any(), any(), any());
    }

    @Test
    void publishEvent_returnsZero_whenNoMatch() {
        when(executionMapper.selectInterruptedByEventKey(eq("nobody"), anyInt())).thenReturn(List.of());

        int dispatched = service.publishEvent("nobody", Map.of(), publisher);

        assertEquals(0, dispatched, "无匹配应返回 0（事件无等待者是正常情况，非错误）");
        verify(service, never()).resume(any(), any(), any());
    }

    @Test
    void publishEvent_swallowsStateInvalid_perRow() {
        when(executionMapper.selectInterruptedByEventKey(eq("k"), anyInt()))
                .thenReturn(List.of(exec(12L, 9L, 1L)));
        // resume 同步抛 STATE_INVALID（已被并发发布/人工 CAS 认领）—— benign race，须被吞
        doThrow(exception(WORKFLOW_EXECUTION_STATE_INVALID, "RUNNING"))
                .when(service).resume(eq(12L), any(), any());

        assertDoesNotThrow(() -> service.publishEvent("k", Map.of(), publisher),
                "STATE_INVALID 应被当作「已被恢复」吞掉，不冒泡");
    }

    @Test
    void publishEvent_swallowsGenericException_perRow() {
        when(executionMapper.selectInterruptedByEventKey(eq("k"), anyInt()))
                .thenReturn(List.of(exec(13L, 9L, 1L)));
        doThrow(new RuntimeException("boom")).when(service).resume(eq(13L), any(), any());

        assertDoesNotThrow(() -> service.publishEvent("k", Map.of(), publisher),
                "单行通用异常应被吞，不影响其余行与端点返回");
    }

}
