package cn.enlev.ainb.module.agent.job;

import cn.enlev.ainb.framework.common.biz.system.tenant.TenantCommonApi;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerInvoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowScheduleTriggerScheduler} 轮询器单测（增量6，纯 mock，0 token）
 *
 * <p>断言：① 🔴 先 CAS 认领（推进 next_fire 到未来）、成功后才 invokeHeadless（InOrder 钉死顺序，
 * 这是 at-most-once 的命门）；② CAS 返回 false（被别的实例认领）→ 绝不触发；③ cron 损坏 → 跳过，
 * 既不 CAS 也不触发（否则 next_fire 永远<=now，会每轮重复触发）；④ 空到期列表不触发；
 * ⑤ 单租户失败被隔离，不波及其余租户；⑥ payload={triggerType:schedule, scheduledFireTime:原到点时刻, cron}；
 * ⑦ <b>失效租户（过期/停用/校验本身出错）fail-close 跳过，且跳过发生在 CAS 之前 —— 否则这次到点会被静默吃掉</b>
 * （B4 第 5 条）。
 *
 * <p>🔴 与 {@code WorkflowWaitResumeSchedulerTest} 同款：{@code ReflectionTestUtils} 手动注入 mock，
 * 「租户上下文」这层用真 {@code TenantUtils.execute} 走一遍（只操作 ThreadLocal，无需 Spring），
 * 从而验证 mapper/invoker 调用发生在租户上下文内，且组件<b>不碰</b> {@code executeIgnore}（红线由 AgentArchitectureTest 另钉）。
 *
 * @author 走进新时代
 */
@DisplayName("agent WorkflowScheduleTriggerScheduler 定时触发轮询")
class WorkflowScheduleTriggerSchedulerTest {

    private WorkflowScheduleTriggerScheduler scheduler;
    private TenantCommonApi tenantCommonApi;
    private AgentWorkflowDefMapper defMapper;
    private WorkflowTriggerInvoker triggerInvoker;

    @BeforeEach
    void setUp() {
        tenantCommonApi = mock(TenantCommonApi.class);
        defMapper = mock(AgentWorkflowDefMapper.class);
        triggerInvoker = mock(WorkflowTriggerInvoker.class);

        scheduler = new WorkflowScheduleTriggerScheduler();
        ReflectionTestUtils.setField(scheduler, "tenantCommonApi", tenantCommonApi);
        ReflectionTestUtils.setField(scheduler, "defMapper", defMapper);
        ReflectionTestUtils.setField(scheduler, "triggerInvoker", triggerInvoker);
    }

    private static AgentWorkflowDefDO schedDef(Long id, String cron, LocalDateTime nextFire) {
        AgentWorkflowDefDO def = new AgentWorkflowDefDO();
        def.setId(id);
        def.setCode("wf_sched");
        def.setTriggerType("schedule");
        def.setTriggerKey(cron);
        def.setTriggerNextFireAt(nextFire);
        def.setOwnerUserId(7L);
        return def;
    }

    @Test
    @DisplayName("到期 def：先 CAS 推进 next_fire 到未来，成功后才无头触发（InOrder 钉死先认领后触发）")
    void pollDueSchedules_claimsThenFires() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L));
        LocalDateTime due = LocalDateTime.now().minusMinutes(1);
        AgentWorkflowDefDO def = schedDef(100L, "0 0 * * * *", due);
        when(defMapper.selectDueSchedules(any(LocalDateTime.class), anyInt())).thenReturn(List.of(def));
        when(defMapper.casAdvanceSchedule(eq(100L), eq(due), any(LocalDateTime.class))).thenReturn(true);

        scheduler.pollDueSchedules();

        // CAS 用「读回的旧 next_fire」做条件，推进到一个未来时刻
        ArgumentCaptor<LocalDateTime> newFireCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(defMapper).casAdvanceSchedule(eq(100L), eq(due), newFireCap.capture());
        assertTrue(newFireCap.getValue().isAfter(due), "推进后的下次触发时间应在旧到点时刻之后（未来）");

        // payload 形状
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCap = ArgumentCaptor.forClass(Map.class);
        verify(triggerInvoker).invokeHeadless(eq(def), payloadCap.capture());
        Map<String, Object> payload = payloadCap.getValue();
        assertEquals("schedule", payload.get("triggerType"));
        assertEquals(due.toString(), payload.get("scheduledFireTime"));
        assertEquals("0 0 * * * *", payload.get("cron"));

        // 🔴 顺序：CAS 认领必须发生在触发之前
        InOrder order = inOrder(defMapper, triggerInvoker);
        order.verify(defMapper).casAdvanceSchedule(eq(100L), eq(due), any(LocalDateTime.class));
        order.verify(triggerInvoker).invokeHeadless(eq(def), any());
    }

    @Test
    @DisplayName("🔴 CAS 返回 false（被别的实例/上一轮认领）→ 绝不触发，杜绝同一到点被双跑")
    void pollDueSchedules_casLost_doesNotFire() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L));
        LocalDateTime due = LocalDateTime.now().minusMinutes(1);
        when(defMapper.selectDueSchedules(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(schedDef(101L, "0 0 * * * *", due)));
        when(defMapper.casAdvanceSchedule(any(), any(), any())).thenReturn(false);

        scheduler.pollDueSchedules();

        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("cron 损坏（脏数据）→ 跳过：既不 CAS 也不触发，避免每轮重复触发")
    void pollDueSchedules_badCron_skips() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L));
        LocalDateTime due = LocalDateTime.now().minusMinutes(1);
        when(defMapper.selectDueSchedules(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(schedDef(102L, "not a cron", due)));

        scheduler.pollDueSchedules();

        verify(defMapper, never()).casAdvanceSchedule(any(), any(), any());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("触发列不完整（cron 或 next_fire 为 null）→ 跳过")
    void pollDueSchedules_incompleteTriggerColumns_skips() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L));
        when(defMapper.selectDueSchedules(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(schedDef(103L, null, LocalDateTime.now().minusMinutes(1))));

        scheduler.pollDueSchedules();

        verify(defMapper, never()).casAdvanceSchedule(any(), any(), any());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("空到期列表 → 不 CAS、不触发")
    void pollDueSchedules_emptyDueList_noFire() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L));
        when(defMapper.selectDueSchedules(any(LocalDateTime.class), anyInt())).thenReturn(List.of());

        scheduler.pollDueSchedules();

        verify(defMapper, never()).casAdvanceSchedule(any(), any(), any());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("单租户扫描失败被隔离，不波及其余租户的到期触发")
    void pollDueSchedules_perTenantIsolation() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L, 2L));
        LocalDateTime due = LocalDateTime.now().minusMinutes(1);
        // 租户 1 扫描抛错，租户 2 正常返回一条到期 def
        when(defMapper.selectDueSchedules(any(LocalDateTime.class), anyInt()))
                .thenThrow(new RuntimeException("tenant1 db down"))
                .thenReturn(List.of(schedDef(200L, "0 0 * * * *", due)));
        when(defMapper.casAdvanceSchedule(any(), any(), any())).thenReturn(true);

        assertDoesNotThrow(() -> scheduler.pollDueSchedules(), "单租户失败应被隔离");
        verify(triggerInvoker, times(1)).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("获取租户列表失败 → 本轮整体跳过，不抛")
    void pollDueSchedules_tenantListFails_skipsRound() {
        when(tenantCommonApi.getTenantIdList()).thenThrow(new RuntimeException("no tenant"));

        assertDoesNotThrow(() -> scheduler.pollDueSchedules());
        verify(defMapper, never()).selectDueSchedules(any(), anyInt());
    }

    // ==== 租户合法性闸门（B4 第 5 条）====
    //
    // 🔴 用「过期租户 122」这个开发库里的真号（system_tenant: id=122 name=test
    //    expire_time=2023-04-29，过期两年多，而 getTenantIdList() 照样返回它）。
    // 🔴 刻意不 import system 的 ErrorCodeConstants 去造真的 TENANT_EXPIRE：跨模块只走 api/ 层。
    //    生产代码 catch 的是 Exception —— fail-close 的判据是「验不出合法性就跳过」，与异常类型无关。

    @Test
    @DisplayName("🔴 过期租户 → 既不 CAS 也不触发：CAS 会把这次到点吃掉，跳过必须发生在 CAS 之前")
    void pollDueSchedules_expiredTenant_neverClaimsNorFires() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(122L));
        LocalDateTime due = LocalDateTime.now().minusMinutes(1);
        when(defMapper.selectDueSchedules(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(schedDef(300L, "0 0 * * * *", due)));
        doThrow(new RuntimeException("租户已过期")).when(tenantCommonApi).validateTenant(122L);

        assertDoesNotThrow(() -> scheduler.pollDueSchedules(), "租户失效应整户跳过，不冒泡");

        // 🔴 never CAS 才是这条的重点：next_fire 必须原样留在过去，
        //    这样租户续费后还能补触发一次；CAS 了就等于把这次到点静默吃掉
        verify(defMapper, never()).casAdvanceSchedule(any(), any(), any());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("validateTenant 自身出错（DB 抖动）→ fail-close 跳过，绝不放行")
    void pollDueSchedules_validateTenantItselfFails_failCloseSkips() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L));
        LocalDateTime due = LocalDateTime.now().minusMinutes(1);
        when(defMapper.selectDueSchedules(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(schedDef(301L, "0 0 * * * *", due)));
        doThrow(new RuntimeException("system_tenant 查询超时")).when(tenantCommonApi).validateTenant(1L);

        assertDoesNotThrow(() -> scheduler.pollDueSchedules());

        verify(defMapper, never()).casAdvanceSchedule(any(), any(), any());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("租户续费 → 下一轮补触发一次（去重 Set 绝不参与判定，否则续费客户被永久拉黑）")
    void pollDueSchedules_tenantRenewed_firesOnNextPoll() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(122L));
        LocalDateTime due = LocalDateTime.now().minusMinutes(1);
        AgentWorkflowDefDO def = schedDef(302L, "0 0 * * * *", due);
        when(defMapper.selectDueSchedules(any(LocalDateTime.class), anyInt())).thenReturn(List.of(def));
        when(defMapper.casAdvanceSchedule(any(), any(), any())).thenReturn(true);
        doThrow(new RuntimeException("租户已过期")).doNothing().when(tenantCommonApi).validateTenant(122L);

        scheduler.pollDueSchedules();
        verify(triggerInvoker, never()).invokeHeadless(any(), any());

        scheduler.pollDueSchedules();
        verify(triggerInvoker, times(1)).invokeHeadless(eq(def), any());
    }

    @Test
    @DisplayName("没有到期定义 → 连 validateTenant 都不查（5s 一轮，不为空转付查询与日志代价）")
    void pollDueSchedules_nothingDue_doesNotEvenValidateTenant() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L, 122L));
        when(defMapper.selectDueSchedules(any(LocalDateTime.class), anyInt())).thenReturn(List.of());

        scheduler.pollDueSchedules();

        verify(tenantCommonApi, never()).validateTenant(anyLong());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

}
