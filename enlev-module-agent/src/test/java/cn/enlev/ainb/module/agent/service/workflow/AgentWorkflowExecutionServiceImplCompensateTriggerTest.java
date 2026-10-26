package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowExecutionStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code settle()} 的失败收尾接线单测（待办 #5 Saga 触发 + 待办 #6 失败告警，纯 mock，0 token）
 *
 * <p>设计方案待办原文：「{@code settle()} 在 {@code FAILED && recovery==COMPENSATE} 时逆序调度补偿」
 * + 「失败告警（{@code settle()} 漏斗接 {@code NotifyMessageSendApi}）」。本测把这两条接线钉死：
 * <ul>
 *   <li><b>补偿触发</b>：① 只有 FAILED+COMPENSATE 才触发；② FORWARD / SUCCEEDED / INTERRUPTED 一律不触发；
 *       ③ settle-once 已生效（重复 settle）不再触发第二次补偿。</li>
 *   <li><b>告警触发</b>：④ FORWARD 失败 → 告警；⑤ COMPENSATE 且补偿后 COMPENSATION_FAILED → 告警；
 *       ⑥ COMPENSATE 且补偿后 COMPENSATED（已自愈）→ <b>不告警</b>；⑦ SUCCEEDED/INTERRUPTED/重复 settle → 不告警。</li>
 *   <li><b>返回值契约</b>：⑧ 抢到 settle-once 返回 true 且状态确已落库；⑨ 已被同 JVM 内另一次收尾抢先返回 false；
 *       ⑩ 那一行已被 {@code cancel} 判成 CANCELLED（SQL 守卫影响 0 行）也返回 false，且不补偿不告警。
 *       {@code runGraph} 的中断分支据此决定「INTERRUPTED 事件发不发」，见该方法上的注释。</li>
 * </ul>
 * 用反射调私有 {@code settle}，{@code compensator} 与 {@code failureAlertService} 均用 mock 隔离真实工具调用与站内信发送。
 *
 * @author 走进新时代
 */
class AgentWorkflowExecutionServiceImplCompensateTriggerTest {

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
        // 🔴 必须桩：settle 收尾走 mapper 的 default 方法 updateByIdUnlessCancelled（B3-1：不许覆盖已取消的行），
        //    而 Mockito 不执行 default 方法体 ⟹ 不桩就恒返回 0 ⟹ settle 以为「本行已被取消」提前 return false，
        //    补偿与告警两段代码<b>一行都跑不到</b>。表现是本类 8 条测全红、看着像接线断了，其实是走了另一条分支。
        when(executionMapper.updateByIdUnlessCancelled(any(AgentWorkflowExecutionDO.class))).thenReturn(1);
    }

    private static AgentWorkflowExecutionDO exec(String recovery) {
        AgentWorkflowExecutionDO e = new AgentWorkflowExecutionDO();
        e.setId(1L);
        e.setUserId(9L);
        e.setTenantId(1L);
        e.setRecovery(recovery);
        return e;
    }

    private Boolean invokeSettle(AtomicBoolean settled, AgentWorkflowExecutionDO exec, String status) {
        // 🔴 第 8 个参数 failedAtNode（B5-2 新增）：本类的补偿/告警断言与它无关，故传 null 保持原语义。
        //    failedAtNode 的写侧另由 settle_writesFailedAtNodeToExecAndDb 专测。
        return ReflectionTestUtils.invokeMethod(service, "settle",
                settled, exec, context, status, "boom", null, System.currentTimeMillis(), null);
    }

    /** 带 failedAtNode 调 settle（B5-2：验证「失败所在节点」真的被写进 exec 与落库的 update） */
    private Boolean invokeSettleWithNode(AtomicBoolean settled, AgentWorkflowExecutionDO exec,
                                         String status, String failedAtNode) {
        return ReflectionTestUtils.invokeMethod(service, "settle",
                settled, exec, context, status, "boom", null, System.currentTimeMillis(), failedAtNode);
    }

    private static AgentWorkflowCompensator.CompensationResult result(String status, String firstError) {
        return new AgentWorkflowCompensator.CompensationResult(1, 0, 1, status, firstError);
    }

    // ==================== 补偿触发（待办 #5） ====================

    @Test
    @DisplayName("FAILED + recovery=COMPENSATE → 触发一次逆序补偿")
    void settle_failedCompensate_triggers() {
        AgentWorkflowExecutionDO exec = exec("COMPENSATE");
        invokeSettle(new AtomicBoolean(false), exec, WorkflowExecutionStatusEnum.FAILED.getStatus());
        verify(compensator, times(1)).compensate(eq(exec), eq(context));
    }

    @Test
    @DisplayName("FAILED + recovery=FORWARD → 不补偿（默认向前恢复，不擅自反做）")
    void settle_failedForward_noCompensate() {
        invokeSettle(new AtomicBoolean(false), exec("FORWARD"), WorkflowExecutionStatusEnum.FAILED.getStatus());
        verify(compensator, never()).compensate(any(), any());
    }

    @Test
    @DisplayName("SUCCEEDED + recovery=COMPENSATE → 不补偿（成功无需反做）")
    void settle_succeeded_noCompensate() {
        invokeSettle(new AtomicBoolean(false), exec("COMPENSATE"), WorkflowExecutionStatusEnum.SUCCEEDED.getStatus());
        verify(compensator, never()).compensate(any(), any());
    }

    @Test
    @DisplayName("INTERRUPTED + recovery=COMPENSATE → 不补偿（挂起非失败）")
    void settle_interrupted_noCompensate() {
        invokeSettle(new AtomicBoolean(false), exec("COMPENSATE"), WorkflowExecutionStatusEnum.INTERRUPTED.getStatus());
        verify(compensator, never()).compensate(any(), any());
    }

    @Test
    @DisplayName("settle-once：已 settle 过再调 → 既不重复落库也不重复补偿")
    void settle_alreadySettled_noSecondCompensate() {
        AtomicBoolean settled = new AtomicBoolean(true); // 模拟已被前一次 settle 认领
        invokeSettle(settled, exec("COMPENSATE"), WorkflowExecutionStatusEnum.FAILED.getStatus());
        verify(compensator, never()).compensate(any(), any());
        verify(executionMapper, never()).updateByIdUnlessCancelled(any(AgentWorkflowExecutionDO.class));
    }

    // ==================== 失败告警（待办 #6 P7 治理） ====================

    @Test
    @DisplayName("FAILED + FORWARD → 告警（失败即终态，无自动撤销，需人工介入）")
    void settle_failedForward_alerts() {
        AgentWorkflowExecutionDO exec = exec("FORWARD");
        invokeSettle(new AtomicBoolean(false), exec, WorkflowExecutionStatusEnum.FAILED.getStatus());
        verify(failureAlertService).alertExecutionFailure(eq(exec), eq(context),
                eq(WorkflowExecutionStatusEnum.FAILED.getStatus()), eq("boom"));
    }

    @Test
    @DisplayName("FAILED + COMPENSATE 且补偿后 COMPENSATION_FAILED → 告警，带上补偿首个失败原因")
    void settle_compensationFailed_alerts() {
        AgentWorkflowExecutionDO exec = exec("COMPENSATE");
        when(compensator.compensate(eq(exec), eq(context)))
                .thenReturn(result(WorkflowExecutionStatusEnum.COMPENSATION_FAILED.getStatus(), "反做工具 500"));
        invokeSettle(new AtomicBoolean(false), exec, WorkflowExecutionStatusEnum.FAILED.getStatus());
        verify(failureAlertService).alertExecutionFailure(eq(exec), eq(context),
                eq(WorkflowExecutionStatusEnum.COMPENSATION_FAILED.getStatus()), eq("反做工具 500"));
    }

    @Test
    @DisplayName("FAILED + COMPENSATE 且补偿后 COMPENSATED（已自愈回滚）→ 不告警")
    void settle_compensated_noAlert() {
        AgentWorkflowExecutionDO exec = exec("COMPENSATE");
        when(compensator.compensate(eq(exec), eq(context)))
                .thenReturn(result(WorkflowExecutionStatusEnum.COMPENSATED.getStatus(), null));
        invokeSettle(new AtomicBoolean(false), exec, WorkflowExecutionStatusEnum.FAILED.getStatus());
        verify(failureAlertService, never()).alertExecutionFailure(any(), any(), any(), any());
    }

    @Test
    @DisplayName("补偿调度自身抛异常 → 保守按 FAILED 告警（终态未知，交人工核对）")
    void settle_compensateThrows_alertsAsFailed() {
        AgentWorkflowExecutionDO exec = exec("COMPENSATE");
        when(compensator.compensate(eq(exec), eq(context))).thenThrow(new RuntimeException("补偿器炸了"));
        invokeSettle(new AtomicBoolean(false), exec, WorkflowExecutionStatusEnum.FAILED.getStatus());
        verify(failureAlertService).alertExecutionFailure(eq(exec), eq(context),
                eq(WorkflowExecutionStatusEnum.FAILED.getStatus()), eq("boom"));
    }

    @Test
    @DisplayName("SUCCEEDED / INTERRUPTED → 不告警（非失败终态）")
    void settle_nonFailed_noAlert() {
        invokeSettle(new AtomicBoolean(false), exec("FORWARD"), WorkflowExecutionStatusEnum.SUCCEEDED.getStatus());
        invokeSettle(new AtomicBoolean(false), exec("FORWARD"), WorkflowExecutionStatusEnum.INTERRUPTED.getStatus());
        verify(failureAlertService, never()).alertExecutionFailure(any(), any(), any(), any());
    }

    @Test
    @DisplayName("settle-once：已 settle 过再调 → 不告警")
    void settle_alreadySettled_noAlert() {
        invokeSettle(new AtomicBoolean(true), exec("FORWARD"), WorkflowExecutionStatusEnum.FAILED.getStatus());
        verify(failureAlertService, never()).alertExecutionFailure(any(), any(), any(), any());
    }

    // ==================== settle 的返回值契约 ====================

    /**
     * 🔴 钉住 {@code settle} 的返回值语义：{@code runGraph} 的中断分支靠它决定「能不能发 INTERRUPTED 事件」。
     *
     * <p>背景是 e2e {@code agent-workflow-hitl-chat} 抓到的一个真缺陷：早先 INTERRUPTED 事件在 stream 的
     * {@code doOnNext} 里就发出去了，而那一刻库里状态还是 RUNNING。事件一到，聊天端立刻渲染出可点的
     * 「同意/拒绝」卡片，用户点下去 resume 撞上状态校验，收到
     * 「Workflow 执行实例当前状态（RUNNING）不允许该操作」—— 卡片看着能点，点了必然被拒。
     * 机器人点得比人快，所以 e2e 稳定复现、真人只是偶发。修法是「先落库、再发事件」，
     * 而「落库到底成没成」正是这个返回值要回答的问题。
     *
     * <p>🔴 迭代七 B3-1 之后，{@code false} 有<b>两个</b>互不相同的来源，下面三条测各钉一个：
     * <ol>
     *   <li><b>JVM 内</b>的 settle-once（{@code AtomicBoolean} CAS 输了）—— 同一次执行的超时/正常收尾撞车；</li>
     *   <li><b>跨请求</b>的 SQL 守卫（{@code updateByIdUnlessCancelled} 影响 0 行）—— 那一行已被
     *       {@code POST /agent/workflow/cancel} 判成 CANCELLED。这一档<b>必须</b>由 SQL 条件而不是内存标记来做：
     *       cancel 请求可能被路由到<b>另一个</b>实例，而跑图的收尾在本实例（同类先例：{@code casInterruptedToRunning}）。</li>
     * </ol>
     * 两档都返回 {@code false}，故两档都不会发 INTERRUPTED 事件、不补偿、不告警 —— 语义正确：
     * 已取消的执行再挂一张「同意/拒绝」卡片，点下去必然被 {@code isResumable} 拒。
     */
    @Test
    @DisplayName("抢到 settle-once → 返回 true，且状态确实已写进库（先落库再发事件的保证）")
    void settle_returnsTrue_whenItWinsCas() {
        Boolean applied = invokeSettle(new AtomicBoolean(false), exec("FORWARD"),
                WorkflowExecutionStatusEnum.INTERRUPTED.getStatus());

        assertEquals(Boolean.TRUE, applied, "首次收尾应生效，调用方据此才发 INTERRUPTED 事件");
        ArgumentCaptor<AgentWorkflowExecutionDO> captor = ArgumentCaptor.forClass(AgentWorkflowExecutionDO.class);
        verify(executionMapper).updateByIdUnlessCancelled(captor.capture());
        assertEquals(WorkflowExecutionStatusEnum.INTERRUPTED.getStatus(), captor.getValue().getStatus(),
                "返回 true 就必须已经把状态落库，否则「先落库再发事件」是句空话");
    }

    @Test
    @DisplayName("已被同 JVM 内另一次收尾抢先（settle-once CAS）→ 返回 false，不写库")
    void settle_returnsFalse_whenAlreadySettled() {
        Boolean applied = invokeSettle(new AtomicBoolean(true), exec("FORWARD"),
                WorkflowExecutionStatusEnum.INTERRUPTED.getStatus());

        assertEquals(Boolean.FALSE, applied);
        verify(executionMapper, never()).updateByIdUnlessCancelled(any(AgentWorkflowExecutionDO.class));
    }

    /**
     * 🔴 B3-1 的另一半：{@code AtomicBoolean} 还是 false（本 JVM 里没人抢），但库里那一行<b>已经是 CANCELLED</b>。
     *
     * <p>这正是「用户点了取消，图还在跑」的真实形状：cancel 走的是另一个 HTTP 请求（甚至另一个实例），
     * 它把行改成了 CANCELLED；随后跑图那边自然收尾、调 settle。此刻 settle 若照旧盲写，
     * CANCELLED 就会被覆盖成 SUCCEEDED —— 用户明明按了停止，历史里却记着「执行成功」，
     * 验收③「查该行是 CANCELLED 而不是 FAILED」也就永远过不了。
     *
     * <p>判据是<b>返回值</b>而不只是「没覆盖」：返回 false 才连带关掉补偿、告警与 INTERRUPTED 事件。
     */
    @Test
    @DisplayName("库里那行已被 cancel 判成 CANCELLED → 返回 false，且不补偿不告警")
    void settle_returnsFalse_whenRowAlreadyCancelled() {
        when(executionMapper.updateByIdUnlessCancelled(any(AgentWorkflowExecutionDO.class))).thenReturn(0);
        AgentWorkflowExecutionDO exec = exec("COMPENSATE");

        Boolean applied = invokeSettle(new AtomicBoolean(false), exec,
                WorkflowExecutionStatusEnum.FAILED.getStatus());

        assertEquals(Boolean.FALSE, applied, "写影响 0 行就必须报 false，调用方据此不发 INTERRUPTED 事件");
        // recovery=COMPENSATE 且 status=FAILED 本来是「必补偿必告警」那一档，被取消后两样都不许发生
        verify(compensator, never()).compensate(any(), any());
        verify(failureAlertService, never()).alertExecutionFailure(any(), any(), any(), any());
    }

    // ==================== failedAtNode 写侧（B5-2） ====================

    /**
     * 🔴 B5-2：{@code failed_at_node} 曾是「只建列、没人写」的死列。本测钉死它的<b>写侧</b>两处都写到——
     * <ul>
     *   <li>落库的 {@code update}（执行历史页 / RespVO 经 BeanUtils 读它，前端「失败节点」列展示）；</li>
     *   <li>内存态的 {@code exec}（紧接着 {@code alertExecutionFailure(exec,...)} → doAlert 读
     *       {@code exec.getFailedAtNode()} 塞进站内信载荷，B5-6 文案）。</li>
     * </ul>
     * 只写其一，「写 → 读 → 展示」链就断在中间：只写 update 则告警拿 null，只写 exec 则历史页空着。
     */
    @Test
    @DisplayName("FAILED 带 failedAtNode → 同时写进落库 update 与内存 exec（B5-2 死列复活）")
    void settle_writesFailedAtNodeToExecAndDb() {
        AgentWorkflowExecutionDO exec = exec("FORWARD");

        invokeSettleWithNode(new AtomicBoolean(false), exec,
                WorkflowExecutionStatusEnum.FAILED.getStatus(), "create_record");

        // 落库侧：updateByIdUnlessCancelled 收到的 update 带上截断后的节点名
        ArgumentCaptor<AgentWorkflowExecutionDO> captor = ArgumentCaptor.forClass(AgentWorkflowExecutionDO.class);
        verify(executionMapper).updateByIdUnlessCancelled(captor.capture());
        assertEquals("create_record", captor.getValue().getFailedAtNode(), "落库的 update 必须带 failed_at_node");
        // 内存侧：doAlert 读的就是这个 exec，故它也必须被写（否则告警载荷里 failedAtNode 恒为占位）
        assertEquals("create_record", exec.getFailedAtNode(), "内存 exec 也要写，告警侧才读得到");
    }

    @Test
    @DisplayName("failedAtNode 超列宽（varchar 64）→ 截断后再写，不撞 Data truncation")
    void settle_truncatesOverlongFailedAtNode() {
        AgentWorkflowExecutionDO exec = exec("FORWARD");
        String overlong = "n".repeat(200);

        invokeSettleWithNode(new AtomicBoolean(false), exec,
                WorkflowExecutionStatusEnum.FAILED.getStatus(), overlong);

        ArgumentCaptor<AgentWorkflowExecutionDO> captor = ArgumentCaptor.forClass(AgentWorkflowExecutionDO.class);
        verify(executionMapper).updateByIdUnlessCancelled(captor.capture());
        String written = captor.getValue().getFailedAtNode();
        assertEquals(64, written.length(), "必须截到列宽 64，实得 " + written.length());
    }

}
