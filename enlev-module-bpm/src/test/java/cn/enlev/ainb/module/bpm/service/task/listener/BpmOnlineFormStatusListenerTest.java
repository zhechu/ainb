package cn.enlev.ainb.module.bpm.service.task.listener;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import cn.enlev.ainb.module.bpm.service.definition.BpmProcessDefinitionService;
import cn.enlev.ainb.module.bpm.service.task.BpmOnlineFormDataService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BpmOnlineFormStatusListener} 的派发前置过滤（台账 #106）
 *
 * <p>钉住：在线表单的 businessKey 是<b>业务表行的数字主键</b>；agent 模块的 {@code hitl.bpm_approval}
 * 用 {@code agent-wf:<executionId>:<nodeId>} 命名空间键发起审批。修复前，这个键会被拿去 UPDATE
 * 业务表主键列，抛异常把事务标成 rollback-only —— 监听器自己的 try/catch 拦不住这种标记，
 * 审批 500、事务回滚（实测见 {@code _evidence/backend-s6.log} 10:38:15）。
 *
 * <p>守卫与 {@code BpmProcessInstanceStatusEventListenerTest} 同一判据（{@code NumberUtil.isLong}）、
 * 同一理由，只是那个基类管「实体表单」这一类，本类管「在线表单」这一个通用监听器。
 *
 * @author 走进新时代
 */
@DisplayName("bpm BpmOnlineFormStatusListener 在线表单状态回写守卫（#106）")
class BpmOnlineFormStatusListenerTest extends BaseMockitoUnitTest {

    /** agent 的 businessKey 形状（见 AgentBpmApprovalService#resolveBpmApprovalEventKey） */
    private static final String AGENT_BUSINESS_KEY = "agent-wf:135:n_gate";

    @InjectMocks
    private BpmOnlineFormStatusListener listener;

    @Mock
    private BpmProcessDefinitionService processDefinitionService;
    @Mock
    private BpmOnlineFormDataService onlineFormDataService;
    @Mock
    private RuntimeService runtimeService;
    @Mock
    private HistoryService historyService;

    private BpmProcessInstanceStatusEvent event(String businessKey) {
        BpmProcessInstanceStatusEvent event = new BpmProcessInstanceStatusEvent("test-source");
        event.setId("proc-inst-1");
        event.setProcessDefinitionKey("test_online_leave");
        event.setStatus(2); // BPM 的「通过」
        event.setBusinessKey(businessKey);
        return event;
    }

    /** 让 getProcessDefinitionInfo 走「运行时实例」分支并命中一个在线表单定义 */
    private void stubOnlineFormDefinition(String processInstanceId, String definitionId,
                                          BpmProcessDefinitionInfoDO info) {
        ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
        ProcessInstance instance = mock(ProcessInstance.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(query);
        when(query.processInstanceId(processInstanceId)).thenReturn(query);
        when(query.singleResult()).thenReturn(instance);
        when(instance.getProcessDefinitionId()).thenReturn(definitionId);
        when(processDefinitionService.getProcessDefinitionInfo(definitionId)).thenReturn(info);
    }

    @Test
    @DisplayName("🔴 agent 命名空间键 → 直接跳过，绝不触发在线表单状态回写（修复前这里 500 回滚）")
    void agentNamespacedBusinessKeyIsSkipped() {
        assertDoesNotThrow(() -> listener.onApplicationEvent(event(AGENT_BUSINESS_KEY)));
        verify(onlineFormDataService, never()).updateStatus(any(), any(), any());
        // 守卫在查流程定义之前就 return，运行时/历史查询一个都不该发生
        verify(runtimeService, never()).createProcessInstanceQuery();
        verify(historyService, never()).createHistoricProcessInstanceQuery();
    }

    @Test
    @DisplayName("非数字 businessKey（不是 agent 的也一样）→ 跳过，不回写")
    void nonNumericBusinessKeyIsSkipped() {
        assertDoesNotThrow(() -> listener.onApplicationEvent(event("order-abc")));
        verify(onlineFormDataService, never()).updateStatus(any(), any(), any());
    }

    @Test
    @DisplayName("null / 空串 businessKey → 跳过（在线表单发起时必有 businessKey，但事件字段允许为空）")
    void blankBusinessKeyIsSkipped() {
        for (String blank : new String[]{null, "", "   "}) {
            assertDoesNotThrow(() -> listener.onApplicationEvent(event(blank)));
        }
        verify(onlineFormDataService, never()).updateStatus(any(), any(), any());
    }

    @Test
    @DisplayName("数字 businessKey + 在线表单 → 照常回写（守卫没把合法路径拦掉）")
    void numericBusinessKeyWithOnlineFormIsUpdated() {
        BpmProcessDefinitionInfoDO info = new BpmProcessDefinitionInfoDO();
        info.setFormDesignCode("test_leave");
        stubOnlineFormDefinition("proc-inst-1", "def-1", info);
        when(onlineFormDataService.isOnlineForm(info)).thenReturn(true);

        listener.onApplicationEvent(event("42"));

        verify(onlineFormDataService).updateStatus(eq(info), eq("42"), eq(2));
    }

    @Test
    @DisplayName("数字 businessKey + 非在线表单 → 跳过（按表单类型过滤这道照旧在）")
    void numericBusinessKeyWithNonOnlineFormIsSkipped() {
        BpmProcessDefinitionInfoDO info = new BpmProcessDefinitionInfoDO();
        stubOnlineFormDefinition("proc-inst-1", "def-1", info);
        when(onlineFormDataService.isOnlineForm(info)).thenReturn(false);

        listener.onApplicationEvent(event("42"));

        verify(onlineFormDataService, never()).updateStatus(any(), any(), any());
    }

    @Test
    @DisplayName("运行时实例查不到 → 退回历史表再查（原有兜底路径不被守卫影响）")
    void fallsBackToHistoryWhenRuntimeInstanceIsGone() {
        ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(query);
        when(query.processInstanceId("proc-inst-1")).thenReturn(query);
        when(query.singleResult()).thenReturn(null);
        HistoricProcessInstanceQuery historyQuery = mock(HistoricProcessInstanceQuery.class);
        org.flowable.engine.history.HistoricProcessInstance historic =
                mock(org.flowable.engine.history.HistoricProcessInstance.class);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historyQuery);
        when(historyQuery.processInstanceId("proc-inst-1")).thenReturn(historyQuery);
        when(historyQuery.singleResult()).thenReturn(historic);
        when(historic.getProcessDefinitionId()).thenReturn("def-1");
        BpmProcessDefinitionInfoDO info = new BpmProcessDefinitionInfoDO();
        info.setFormDesignCode("test_leave");
        when(processDefinitionService.getProcessDefinitionInfo("def-1")).thenReturn(info);
        when(onlineFormDataService.isOnlineForm(info)).thenReturn(true);

        listener.onApplicationEvent(event("43"));

        verify(onlineFormDataService).updateStatus(eq(info), eq("43"), eq(2));
    }

}
