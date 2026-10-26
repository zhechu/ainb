package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowExecutionStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_EXECUTION_NOT_EXISTS;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_EXECUTION_STATE_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code cancel()} 单测（迭代七 B3-2：新接口 {@code POST /agent/workflow/cancel} 的准入闸与撞车处理）
 *
 * <p>纯 mock、0 token、不碰网络。真库那一半（SQL 条件 {@code status<>'CANCELLED'} 到底拦不拦得住晚到的收尾）
 * 由验收③的只读 SQL 与 {@code AgentWorkflowExecutionServiceImplEventFluxRuntimeTest} 分工覆盖。
 *
 * <h3>本测钉的五件事</h3>
 * <ol>
 *   <li><b>准入闸是白名单</b>：只有 CREATED / RUNNING / INTERRUPTED 三档能取消，其余六档一律拒。</li>
 *   <li><b>取消不反做、不告警</b>：B3-1「行为错 / 告警错」两条的正面钉子 —— 即便那一行
 *       {@code recovery=COMPENSATE}（在 settle 那边是「失败必补偿」那一档），cancel 也<b>绝不</b>调补偿器。
 *       触发补偿等于把用户想停的东西再跑一遍，比不停更糟。</li>
 *   <li><b>落库字段齐</b>：{@code error_msg} 逐字落「用户中断」（与 SSE 断流那条 {@code .doOnCancel}
 *       共用同一常量），{@code end_time} 与 {@code duration_ms} 都要有 ——
 *       否则同一个「取消」动作经两条路径会落成两种形状，而抽屉「耗时(ms)」那一列正是本轮要给人看的。</li>
 *   <li><b>撞车时报真实状态</b>：预检通过、CAS 影响 0 行时必须<b>重读</b>再报 ——
 *       拿预检时的旧值糊过去，用户看到的会是「当前状态（RUNNING）不允许该操作」，
 *       而那一行其实早已 SUCCEEDED：把「你没取消成」说成了「它还在跑」。</li>
 *   <li><b>没有身份就罢工</b>：fail-close，连 selectById 都不查。</li>
 * </ol>
 *
 * @author 走进新时代
 */
class AgentWorkflowExecutionServiceImplCancelTest {

    private static final Long EXECUTION_ID = 77L;
    private static final String REASON = "用户中断";

    private AgentWorkflowExecutionServiceImpl service;
    private AgentWorkflowExecutionMapper executionMapper;
    private AgentWorkflowCompensator compensator;
    private AgentFailureAlertService failureAlertService;

    private final AgentExecutionContext context = AgentExecutionContext.ofForTest(9L, 1L);

    @BeforeEach
    void setUp() {
        executionMapper = mock(AgentWorkflowExecutionMapper.class);
        compensator = mock(AgentWorkflowCompensator.class);
        failureAlertService = mock(AgentFailureAlertService.class);
        service = new AgentWorkflowExecutionServiceImpl();
        ReflectionTestUtils.setField(service, "executionMapper", executionMapper);
        ReflectionTestUtils.setField(service, "compensator", compensator);
        ReflectionTestUtils.setField(service, "failureAlertService", failureAlertService);
        // 默认「CAS 抢到」；要测撞车的用例各自覆盖成 0
        stubCas(1);
    }

    private void stubCas(int rows) {
        when(executionMapper.casToCancelled(anyLong(), anyString(), any(), any())).thenReturn(rows);
    }

    private AgentWorkflowExecutionDO row(String status) {
        AgentWorkflowExecutionDO e = new AgentWorkflowExecutionDO();
        e.setId(EXECUTION_ID);
        e.setUserId(9L);
        e.setTenantId(1L);
        e.setStatus(status);
        e.setBeginTime(LocalDateTime.now().minusSeconds(30));
        // 🔴 刻意声明 compensate：这一档在 settle 那边是「失败必补偿」，
        //    正好用来证明 cancel 走的是<b>另一条</b>路 —— 它压根不看 recovery。
        e.setRecovery("COMPENSATE");
        return e;
    }

    private void givenRow(String status) {
        when(executionMapper.selectById(EXECUTION_ID)).thenReturn(row(status));
    }

    // ==================== ① 可取消的三档 ====================

    @Test
    @DisplayName("RUNNING → CAS 成 CANCELLED，落「用户中断」+ end_time + duration_ms，且不补偿不告警")
    void cancel_running_casToCancelledWithFullBookkeeping() {
        givenRow(WorkflowExecutionStatusEnum.RUNNING.getStatus());

        service.cancel(EXECUTION_ID, context);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> endTime = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Integer> durationMs = ArgumentCaptor.forClass(Integer.class);
        verify(executionMapper, times(1))
                .casToCancelled(eq(EXECUTION_ID), reason.capture(), endTime.capture(), durationMs.capture());

        // 🔴 逐字比对，不是「非空就行」：这句文案是运维在失败原因列里唯一能看到的「为什么停了」，
        //    也是与 .doOnCancel 那条路共用同一常量的证据（共用常量这件事本身由 CANCEL_REASON 保证，
        //    这里保证的是它<b>真的被写进去了</b>、没有在半路被换成 ex.getMessage()）
        assertEquals(REASON, reason.getValue(), "取消原因必须逐字落「用户中断」");
        assertNotNull(endTime.getValue(), "取消是终态，必须落 end_time");
        // 🔴 duration_ms 也必须落：settle 那条路（SSE 断流）本来就写它。这里不写的话，
        //    同样是「取消」，经抽屉按钮落库的行耗时为空、经关页面落库的行耗时有值 ——
        //    两种形状没人会报错，只会让「这一列有时有值有时没有」变成一件说不清的事。
        assertNotNull(durationMs.getValue(), "耗时必须落，否则与 SSE 断流那条路落成两种形状");
        assertTrue(durationMs.getValue() >= 30_000, "begin_time 是 30 秒前，耗时应至少 30000ms，实际："
                + durationMs.getValue());

        // 🔴 B3-1 的正面钉子：这一行 recovery=COMPENSATE，settle 撞见 FAILED 是会补偿的；
        //    cancel 撞见它<b>绝不能</b>补偿。同理也不能告警 —— 用户主动停的东西进了失败告警面，
        //    值班的人就会被叫去看一件「有人按了停止键」的正常事。
        verifyNoInteractions(compensator);
        verifyNoInteractions(failureAlertService);
    }

    @Test
    @DisplayName("CREATED / INTERRUPTED 同样可取消（挂在闸口上没人管的执行，cancel 是最后一条出路）")
    void cancel_createdAndInterrupted_areCancellable() {
        for (String status : new String[]{WorkflowExecutionStatusEnum.CREATED.getStatus(),
                WorkflowExecutionStatusEnum.INTERRUPTED.getStatus()}) {
            setUp();
            givenRow(status);

            service.cancel(EXECUTION_ID, context);

            verify(executionMapper, times(1)).casToCancelled(eq(EXECUTION_ID), eq(REASON), any(), any());
            verifyNoInteractions(compensator);
            verifyNoInteractions(failureAlertService);
        }
    }

    // ==================== ② 不可取消的六档：拒在 CAS 之前 ====================

    @Test
    @DisplayName("六个非白名单档一律拒，报错带真实状态，且压根不发 UPDATE")
    void cancel_nonWhitelistedStatuses_rejectedBeforeAnyWrite() {
        for (String status : new String[]{WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(),
                WorkflowExecutionStatusEnum.FAILED.getStatus(),
                WorkflowExecutionStatusEnum.CANCELLED.getStatus(),
                WorkflowExecutionStatusEnum.COMPENSATING.getStatus(),
                WorkflowExecutionStatusEnum.COMPENSATED.getStatus(),
                WorkflowExecutionStatusEnum.COMPENSATION_FAILED.getStatus()}) {
            setUp();
            givenRow(status);
            assertFalse(WorkflowExecutionStatusEnum.isCancellable(status),
                    "前提核对：" + status + " 必须不在白名单里，否则这条测在测一件不会发生的事");

            ServiceException ex = assertThrows(ServiceException.class,
                    () -> service.cancel(EXECUTION_ID, context), status + " 不该能被取消");
            assertEquals(WORKFLOW_EXECUTION_STATE_INVALID.getCode(), ex.getCode());
            // 🔴 消息里必须带上<b>当时真实</b>的状态：只说「不允许该操作」的话，用户分不清是
            //    「它已经跑完了」还是「它正在回滚、不许打断」——两种处置完全不同。
            assertTrue(ex.getMessage().contains(status),
                    "报错要带真实状态，实际消息：" + ex.getMessage());
            // 拒在 CAS 之前：连一次 UPDATE 都不该发出去（发出去也是 0 行，但那是白跑一趟数据库）
            verify(executionMapper, never()).casToCancelled(anyLong(), anyString(), any(), any());
        }
    }

    /**
     * 🔴 这一条是<b>写 B3-2 那轮时真抓到的一个缺陷</b>的钉子，不是假想场景。
     *
     * <p>B3-2 第一版实现把准入闸写成了 {@code isFinal(status) || COMPENSATING.equals(status)}。
     * 那时 {@code FAILED} <b>不在</b> {@code isFinal} 里，于是 FAILED 从闸门口溜了过去 ——
     * 一条<b>已经失败收尾</b>的执行能被改写成 CANCELLED：{@code error_msg} 里那句真实的失败原因
     * 被「用户中断」顶掉，{@code end_time} 换成第二次的时间，事后<b>再也看不出这里曾经失败过</b>。
     * 而它偏偏还是 B3-2 验收③的判据本身（「查该行是 CANCELLED 而<b>不是</b> FAILED」）——
     * 闸写反了，验收反而会「通过」。
     *
     * <p>修法是改用枚举里的<b>白名单</b> {@code isCancellable}（CREATED/RUNNING/INTERRUPTED），
     * 而不是「{@code !isFinal} 再补几个例外」：白名单只有三档、与 {@code casToCancelled} 的 SQL {@code IN}
     * 同源，新状态默认落进「不可取消」而不是默认落进「可取消」。
     *
     * <p>🔴 B3-4 之后 FAILED 已进 isFinal，「!isFinal 当准入闸」这条路对 FAILED 已不再漏 ——
     * 但本测<b>刻意保留</b>：白名单另有一层保险（新状态默认「不可取消」），
     * 而 {@link WorkflowExecutionStatusEnum#isCancellable} 的注释也把 COMPENSATING 那一档
     * 列为「!isFinal 会漏」的现存反例。改回「!isFinal 再补例外」这种写法，本测会红。
     */
    @Test
    @DisplayName("FAILED 不可取消（B3-4 之后它已在 isFinal 里，但白名单另有保险 —— 新状态默认「不可取消」）")
    void cancel_failedIsNotCancellable_evenThoughItIsFinalNow() {
        givenRow(WorkflowExecutionStatusEnum.FAILED.getStatus());
        // 🔴 B3-4 起 FAILED 已是终态。本断言与 B3-2 那时相反，是刻意翻面的：
        //    钉住「isFinal(FAILED) == true 但 isCancellable(FAILED) == false」这个组合，
        //    两把尺子在 FAILED 这一档上必须分开表态。
        assertTrue(WorkflowExecutionStatusEnum.isFinal(WorkflowExecutionStatusEnum.FAILED.getStatus()),
                "前提核对：B3-4 之后 FAILED 是终态");
        assertFalse(WorkflowExecutionStatusEnum.isCancellable(WorkflowExecutionStatusEnum.FAILED.getStatus()),
                "但白名单里没它 —— 已收尾的执行改写成 CANCELLED 会抹掉一次真实故障");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.cancel(EXECUTION_ID, context));
        assertEquals(WORKFLOW_EXECUTION_STATE_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains(WorkflowExecutionStatusEnum.FAILED.getStatus()),
                "实际消息：" + ex.getMessage());
        verify(executionMapper, never()).casToCancelled(anyLong(), anyString(), any(), any());
        verifyNoInteractions(compensator);
    }

    /**
     * 🔴 COMPENSATING 是<b>另一个</b>「不是终态却不可取消」的反例，与 FAILED 各有各的理由，故也单列一条。
     *
     * <p>那一档正在逆序调反做工具。判它「已取消」= 宣称撤销完成而撤销其实只做了一半 ——
     * <b>半撤销比卡死更难查</b>：卡死看得见，半撤销看着像成功。
     */
    @Test
    @DisplayName("COMPENSATING 不可取消（正在反做，判它「已停」= 宣称撤销完成而其实只做了一半）")
    void cancel_compensating_rejected() {
        givenRow(WorkflowExecutionStatusEnum.COMPENSATING.getStatus());
        assertFalse(WorkflowExecutionStatusEnum.isFinal(WorkflowExecutionStatusEnum.COMPENSATING.getStatus()),
                "前提核对：COMPENSATING 确实不是终态（反做完了还要再走一档），所以拦它的只能是白名单");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.cancel(EXECUTION_ID, context));
        assertEquals(WORKFLOW_EXECUTION_STATE_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains(WorkflowExecutionStatusEnum.COMPENSATING.getStatus()),
                "报错要让人看出「它正在回滚、不许打断」，实际消息：" + ex.getMessage());
        verify(executionMapper, never()).casToCancelled(anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("执行不存在 → NOT_EXISTS，且不发任何 UPDATE")
    void cancel_missing_notExists() {
        when(executionMapper.selectById(EXECUTION_ID)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.cancel(EXECUTION_ID, context));
        assertEquals(WORKFLOW_EXECUTION_NOT_EXISTS.getCode(), ex.getCode());
        verify(executionMapper, never()).casToCancelled(anyLong(), anyString(), any(), any());
    }

    // ==================== ③ fail-close：没有身份就罢工 ====================

    @Test
    @DisplayName("身份快照为 null / 不完整 → 一律拒，且连 selectById 都不查")
    void cancel_withoutIdentity_failClose() {
        for (AgentExecutionContext bad : new AgentExecutionContext[]{null, AgentExecutionContext.ofForTest(null, null)}) {
            assertThrows(ServiceException.class, () -> service.cancel(EXECUTION_ID, bad),
                    "拿不到身份就该罢工，绝不降级放行（与 AgentExecutionContext 类注释一致）");
        }
        verify(executionMapper, never()).selectById(anyLong());
        verify(executionMapper, never()).casToCancelled(anyLong(), anyString(), any(), any());
    }

    // ==================== ④ 撞车：CAS 影响 0 行 ====================

    /**
     * 🔴 这条钉的是「<b>不许拿预检时的旧状态糊过去</b>」。
     *
     * <p>形状：预检看到 RUNNING（可取消）→ 就在这一瞬间，跑图那条流的 settle 把它写成了 SUCCEEDED
     * → 我们的 CAS 条件匹配不上、影响 0 行。此刻若直接抛「当前状态（RUNNING）不允许该操作」就是一句<b>假话</b>：
     * 那一行早已不在 RUNNING。用户会据此去查「为什么还在跑」，而真正的答案是「它已经跑完了，你没赶上」。
     */
    @Test
    @DisplayName("CAS 影响 0 行 → 重读并抛<b>库里当前真实</b>的状态，而不是预检时那个旧值")
    void cancel_casLost_reportsTheRealCurrentStatus() {
        when(executionMapper.selectById(EXECUTION_ID))
                .thenReturn(row(WorkflowExecutionStatusEnum.RUNNING.getStatus()))    // 预检时
                .thenReturn(row(WorkflowExecutionStatusEnum.SUCCEEDED.getStatus())); // 撞车后重读
        stubCas(0);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.cancel(EXECUTION_ID, context));

        assertEquals(WORKFLOW_EXECUTION_STATE_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains(WorkflowExecutionStatusEnum.SUCCEEDED.getStatus()),
                "必须报重读到的真实状态 SUCCEEDED，实际消息：" + ex.getMessage());
        assertFalse(ex.getMessage().contains(WorkflowExecutionStatusEnum.RUNNING.getStatus()),
                "不许把预检时那个已过期的 RUNNING 说给用户听，实际消息：" + ex.getMessage());
        // 重读是为了说实话，不是为了再试一次：CAS 只发一次，绝不重试
        verify(executionMapper, times(1)).casToCancelled(anyLong(), anyString(), any(), any());
        verify(executionMapper, times(2)).selectById(EXECUTION_ID);
    }

    @Test
    @DisplayName("CAS 影响 0 行且重读也查不到（并发逻辑删除）→ NOT_EXISTS，不抛 NPE")
    void cancel_casLostAndRowGone_notExists() {
        when(executionMapper.selectById(EXECUTION_ID))
                .thenReturn(row(WorkflowExecutionStatusEnum.RUNNING.getStatus()))
                .thenReturn(null);
        stubCas(0);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.cancel(EXECUTION_ID, context));
        assertEquals(WORKFLOW_EXECUTION_NOT_EXISTS.getCode(), ex.getCode());
    }

}
