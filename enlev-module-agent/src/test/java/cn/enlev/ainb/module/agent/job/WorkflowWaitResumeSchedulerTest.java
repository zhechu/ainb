package cn.enlev.ainb.module.agent.job;

import cn.enlev.ainb.framework.common.biz.system.tenant.TenantCommonApi;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_EXECUTION_STATE_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowWaitResumeScheduler} 轮询器单测（P2 增量4a，纯 mock，0 token）
 *
 * <p>断言：① 到期项以正确身份（userId 取自 DO、tenantId 取自循环）被 resume；② userId 为空的行被跳过；
 * ③ resume 抛 STATE_INVALID（已被别人 CAS 认领）时被吞、不冒泡；④ 空到期列表不触发 resume；
 * ⑤ <b>失效租户（过期/停用/校验本身出错）一律 fail-close 跳过，续费后下一轮立刻恢复</b>（B4 第 5 条）。
 *
 * <p>🔴 用 {@code ReflectionTestUtils} 手动注入 mock，并把「租户上下文」这层用真 {@code TenantUtils.execute}
 * 走一遍（它只操作 ThreadLocal，无需 Spring）——这样能验证「mapper/service 调用发生在租户上下文内」的语义，
 * 同时确认组件<b>不碰</b> {@code executeIgnore}（红线由 AgentArchitectureTest 另行钉死）。
 *
 * @author 走进新时代
 */
class WorkflowWaitResumeSchedulerTest {

    private WorkflowWaitResumeScheduler scheduler;
    private TenantCommonApi tenantCommonApi;
    private AgentWorkflowExecutionMapper executionMapper;
    private AgentWorkflowExecutionService executionService;

    @BeforeEach
    void setUp() {
        tenantCommonApi = mock(TenantCommonApi.class);
        executionMapper = mock(AgentWorkflowExecutionMapper.class);
        executionService = mock(AgentWorkflowExecutionService.class);

        scheduler = new WorkflowWaitResumeScheduler();
        ReflectionTestUtils.setField(scheduler, "tenantCommonApi", tenantCommonApi);
        ReflectionTestUtils.setField(scheduler, "executionMapper", executionMapper);
        ReflectionTestUtils.setField(scheduler, "executionService", executionService);
    }

    private static AgentWorkflowExecutionDO exec(Long id, Long userId) {
        AgentWorkflowExecutionDO e = new AgentWorkflowExecutionDO();
        e.setId(id);
        e.setUserId(userId);
        e.setStatus("INTERRUPTED");
        e.setResumeAt(LocalDateTime.now().minusSeconds(1));
        return e;
    }

    @Test
    void pollDueWaits_resumesDueExecutions_withIdentityFromRow() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L));
        when(executionMapper.selectDueWaits(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(exec(10L, 9L)));
        when(executionService.resume(eq(10L), any(), any())).thenReturn(Flux.empty());

        scheduler.pollDueWaits();

        ArgumentCaptor<AgentExecutionContext> ctxCap = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(executionService, times(1)).resume(eq(10L), eq(Map.of()), ctxCap.capture());
        AgentExecutionContext ctx = ctxCap.getValue();
        assertEquals(9L, ctx.getUserId().longValue(), "resume 身份 userId 应取自 DO");
        assertEquals(1L, ctx.getTenantId().longValue(), "resume 身份 tenantId 应取自当前租户循环");
    }

    @Test
    void pollDueWaits_skipsRowsWithoutUserId() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L));
        when(executionMapper.selectDueWaits(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(exec(11L, null)));

        scheduler.pollDueWaits();

        verify(executionService, never()).resume(any(), any(), any());
    }

    @Test
    void pollDueWaits_swallowsStateInvalid_asAlreadyResumed() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L));
        when(executionMapper.selectDueWaits(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(exec(12L, 9L)));
        // resume 抛 STATE_INVALID（已被别的实例/人工 CAS 认领）—— 竞态正常，须被吞
        when(executionService.resume(eq(12L), any(), any()))
                .thenThrow(exception(WORKFLOW_EXECUTION_STATE_INVALID, "RUNNING"));

        assertDoesNotThrow(() -> scheduler.pollDueWaits(),
                "STATE_INVALID 应被当作「已被恢复」吞掉，不冒泡打断本轮其余租户");
    }

    @Test
    void pollDueWaits_swallowsGenericException_perRow() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L));
        when(executionMapper.selectDueWaits(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(exec(13L, 9L)));
        when(executionService.resume(eq(13L), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> scheduler.pollDueWaits(), "单行异常应被吞，不影响轮询器存活");
    }

    @Test
    void pollDueWaits_emptyDueList_noResume() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L));
        when(executionMapper.selectDueWaits(any(LocalDateTime.class), anyInt())).thenReturn(List.of());

        scheduler.pollDueWaits();

        verify(executionService, never()).resume(any(), any(), any());
    }

    @Test
    void pollDueWaits_perTenantIsolation_oneTenantFailureDoesNotStopOthers() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L, 2L));
        // 租户 1 扫描抛错，租户 2 正常
        when(executionMapper.selectDueWaits(any(LocalDateTime.class), anyInt()))
                .thenThrow(new RuntimeException("tenant1 db down"))
                .thenReturn(List.of(exec(20L, 9L)));
        when(executionService.resume(eq(20L), any(), any())).thenReturn(Flux.empty());

        assertDoesNotThrow(() -> scheduler.pollDueWaits(), "单租户失败应被隔离");
        // 租户 2 的到期项仍被恢复
        verify(executionService, times(1)).resume(eq(20L), any(), any());
    }

    // ==== 租户合法性闸门（B4 第 5 条）====
    //
    // 🔴 下面四条都用「过期租户 122」这个开发库里的真号（system_tenant: id=122 name=test
    //    expire_time=2023-04-29，过期两年多，而 getTenantIdList() 照样返回它）。
    // 🔴 刻意不 import system 的 ErrorCodeConstants 去造真的 TENANT_EXPIRE：跨模块只走 api/ 层
    //    （AgentArchitectureTest 的规矩，本模块连测试里的跨模块 import 全是 api/）。
    //    而生产代码 catch 的是 Exception —— fail-close 的判据本来就是「验不出合法性就跳过」，
    //    与异常类型无关，所以用 RuntimeException 反而把这条契约钉得更准。

    @Test
    void pollDueWaits_expiredTenant_neverResumes() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(122L));
        when(executionMapper.selectDueWaits(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(exec(30L, 9L)));
        doThrow(new RuntimeException("租户已过期")).when(tenantCommonApi).validateTenant(122L);

        assertDoesNotThrow(() -> scheduler.pollDueWaits(), "租户失效应整户跳过，不冒泡");

        verify(executionService, never()).resume(any(), any(), any());
    }

    @Test
    void pollDueWaits_validateTenantItselfFails_failCloseSkips() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L));
        when(executionMapper.selectDueWaits(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(exec(31L, 9L)));
        // 不是「租户失效」，是校验本身炸了（DB 抖动之类）—— 照样必须跳过：
        // 放行 = 可能给过期客户白烧钱，跳过 = 这条 wait 晚一轮（5s）恢复、resume_at 原样留着什么都不丢
        doThrow(new RuntimeException("system_tenant 查询超时")).when(tenantCommonApi).validateTenant(1L);

        assertDoesNotThrow(() -> scheduler.pollDueWaits());

        verify(executionService, never()).resume(any(), any(), any());
    }

    @Test
    void pollDueWaits_tenantRenewed_resumesOnNextPoll() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(122L));
        when(executionMapper.selectDueWaits(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(exec(32L, 9L)));
        when(executionService.resume(eq(32L), any(), any())).thenReturn(Flux.empty());
        // 第一轮失效、第二轮（续费后）合法
        doThrow(new RuntimeException("租户已过期")).doNothing().when(tenantCommonApi).validateTenant(122L);

        scheduler.pollDueWaits();
        verify(executionService, never()).resume(any(), any(), any());

        scheduler.pollDueWaits();
        // 🔴 这条钉的是「日志去重的那个 Set 绝不参与判定」：
        //    若它被误当成缓存用了，续费客户会被永久拉黑 —— 那比原缺陷严重得多
        verify(executionService, times(1)).resume(eq(32L), eq(Map.of()), any());
    }

    @Test
    void pollDueWaits_nothingDue_doesNotEvenValidateTenant() {
        when(tenantCommonApi.getTenantIdList()).thenReturn(List.of(1L, 122L));
        when(executionMapper.selectDueWaits(any(LocalDateTime.class), anyInt())).thenReturn(List.of());

        scheduler.pollDueWaits();

        // 校验放在「扫到到期项之后」是有意的：5s 一轮，若放在租户循环里，
        // 就是每轮每租户一次 getTenant 查询 + 失效租户每天上万行日志，而绝大多数轮次根本没活干
        verify(tenantCommonApi, never()).validateTenant(anyLong());
        verify(executionService, never()).resume(any(), any(), any());
    }

}
