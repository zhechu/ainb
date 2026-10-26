package cn.enlev.ainb.module.bpm.service.task;

import cn.enlev.ainb.framework.security.core.service.SecurityFrameworkService;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import cn.enlev.ainb.module.bpm.service.definition.BpmProcessDefinitionService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 流程实例的「实例级访问控制」
 *
 * <h3>这个类拦的是一个水平越权</h3>
 *
 * 五个查看类端点此前只有 {@code @PreAuthorize("@ss.hasPermission('bpm:process-instance:query')")}
 * 这一道静态权限串，没有任何「你是否与这条实例相关」的判定。
 * 而这个权限串<b>每个普通用户都必须有</b>（否则看不到自己提交的单子）——
 * 于是任何登录用户换一个 id，就能读到别人的请假、报销、薪资审批，含完整表单数据。
 * {@code /get-print-data} 更直接：它返回的就是完整表单数据。
 *
 * <h3>⚠️ 被测的是临时实现</h3>
 *
 * S5b 会建统一的流程参与人表，届时这五个分支收敛成一次查询。
 * 本测试类的用例届时应当<b>原样保留</b> —— 判定口径不变，只是实现换了。
 *
 * @author 走进新时代
 */
// ⚠️ 判定是短路的：命中「发起人」就不会再查任务/抄送/管理员。
// 严格模式下，那些用不到的桩会被报成 UnnecessaryStubbing —— 而它们恰恰是
// 「这一条分支没被走到」的证明。所以这里放宽严格度。
@MockitoSettings(strictness = Strictness.LENIENT)
public class BpmProcessInstanceViewableTest extends BaseMockitoUnitTest {

    private static final String PROC_ID = "proc-1";
    private static final String DEF_ID = "def-1";
    private static final Long STARTER = 1000L;
    private static final Long APPROVER = 2000L;
    private static final Long CC_USER = 3000L;
    private static final Long MANAGER = 4000L;
    private static final Long STRANGER = 9999L;

    @Mock
    private BpmTaskService taskService;
    @Mock
    private BpmProcessInstanceCopyService processInstanceCopyService;
    @Mock
    private BpmProcessDefinitionService processDefinitionService;
    @Mock
    private SecurityFrameworkService securityFrameworkService;
    /**
     * S5b 起判定先查参与表。默认不命中 —— 这样下面 11 条用例验的仍是
     * 【存量回落那条路】，即判定口径确实没变
     */
    @Mock
    private BpmInstanceParticipantService instanceParticipantService;
    /**
     * S5d 起，流程管理员/监控岗先看流程资源上的 MANAGE / VIEW_ALL 授权。
     * 默认不命中 —— 这样下面的用例验的仍是【存量回落】那条路，即判定口径没变
     */
    @Mock
    private cn.enlev.ainb.module.bpm.framework.permission.BpmProcessPermissionChecker processPermissionChecker;

    /**
     * 只测判定逻辑，不起 Flowable。
     * {@code getHistoricProcessInstance} 是被测类自己的方法，用 spy 打桩
     */
    private BpmProcessInstanceServiceImpl newService(HistoricProcessInstance instance) {
        BpmProcessInstanceServiceImpl service = Mockito.spy(new BpmProcessInstanceServiceImpl());
        ReflectionTestUtils.setField(service, "taskService", taskService);
        ReflectionTestUtils.setField(service, "processInstanceCopyService", processInstanceCopyService);
        ReflectionTestUtils.setField(service, "processDefinitionService", processDefinitionService);
        ReflectionTestUtils.setField(service, "securityFrameworkService", securityFrameworkService);
        ReflectionTestUtils.setField(service, "instanceParticipantService", instanceParticipantService);
        ReflectionTestUtils.setField(service, "processPermissionChecker", processPermissionChecker);
        doReturn(instance).when(service).getHistoricProcessInstance(anyString());
        return service;
    }

    private HistoricProcessInstance instance() {
        HistoricProcessInstance instance = mock(HistoricProcessInstance.class);
        lenient().when(instance.getStartUserId()).thenReturn(String.valueOf(STARTER));
        lenient().when(instance.getProcessDefinitionId()).thenReturn(DEF_ID);
        return instance;
    }

    private HistoricTaskInstance task(Long assignee, Long owner) {
        HistoricTaskInstance task = mock(HistoricTaskInstance.class);
        lenient().when(task.getAssignee()).thenReturn(assignee == null ? null : String.valueOf(assignee));
        lenient().when(task.getOwner()).thenReturn(owner == null ? null : String.valueOf(owner));
        return task;
    }

    /** 默认：没有任务、没有抄送、没有管理员、没有监控权限 */
    private void mockNothing() {
        lenient().when(taskService.getTaskListByProcessInstanceId(anyString(), any()))
                .thenReturn(Collections.emptyList());
        lenient().when(processInstanceCopyService.isCopyUser(anyString(), any())).thenReturn(false);
        lenient().when(processDefinitionService.getProcessDefinitionInfo(anyString())).thenReturn(null);
        lenient().when(securityFrameworkService.hasPermission(anyString())).thenReturn(false);
        lenient().when(processPermissionChecker.isManager(any(), any())).thenReturn(false);
        lenient().when(processPermissionChecker.canViewAll(any(), any())).thenReturn(false);
    }

    @Test
    @DisplayName("⚠️ 无关的人看不到 —— 这条就是被修掉的越权")
    void testStrangerDenied() {
        mockNothing();
        BpmProcessInstanceServiceImpl service = newService(instance());
        assertFalse(service.canUserViewProcessInstance(STRANGER, PROC_ID));
    }

    @Test
    @DisplayName("发起人能看")
    void testStarterAllowed() {
        mockNothing();
        // TODO #30 摘掉存量回落后，发起人由【参与表】放行（S5a 在实例创建时写入）。
        // 判定口径没变 —— 发起人照样能看 —— 变的只是这条结论从哪来
        when(instanceParticipantService.isParticipant(PROC_ID, STARTER)).thenReturn(true);
        BpmProcessInstanceServiceImpl service = newService(instance());
        assertTrue(service.canUserViewProcessInstance(STARTER, PROC_ID));
    }

    @Test
    @DisplayName("当前审批人能看")
    void testAssigneeAllowed() {
        mockNothing();
        List<HistoricTaskInstance> tasks = List.of(task(APPROVER, null));
        when(taskService.getTaskListByProcessInstanceId(anyString(), any())).thenReturn(tasks);
        // 审批人由参与表放行（S5a 在任务分配时写入 APPROVER）
        when(instanceParticipantService.isParticipant(PROC_ID, APPROVER)).thenReturn(true);
        BpmProcessInstanceServiceImpl service = newService(instance());
        assertTrue(service.canUserViewProcessInstance(APPROVER, PROC_ID));
    }

    @Test
    @DisplayName("转办出去的原受理人（owner）也能看 —— 否则「我转办的单子」自己反而看不到")
    void testOwnerAllowed() {
        mockNothing();
        List<HistoricTaskInstance> tasks = List.of(task(STRANGER, APPROVER));
        when(taskService.getTaskListByProcessInstanceId(anyString(), any())).thenReturn(tasks);
        // 转办出去的原受理人由参与表放行（S5a 在转办时写入 TRANSFER_FROM）
        when(instanceParticipantService.isParticipant(PROC_ID, APPROVER)).thenReturn(true);
        BpmProcessInstanceServiceImpl service = newService(instance());
        assertTrue(service.canUserViewProcessInstance(APPROVER, PROC_ID));
    }

    @Test
    @DisplayName("历史审批人能看 —— 已办结的任务也算")
    void testHistoricAssigneeAllowed() {
        mockNothing();
        List<HistoricTaskInstance> tasks = Arrays.asList(task(APPROVER, null), task(STRANGER, null));
        when(taskService.getTaskListByProcessInstanceId(anyString(), any())).thenReturn(tasks);
        // 历史审批人：参与记录在任务办结时只补 end_time，不删除，所以照样能看
        when(instanceParticipantService.isParticipant(PROC_ID, APPROVER)).thenReturn(true);
        BpmProcessInstanceServiceImpl service = newService(instance());
        assertTrue(service.canUserViewProcessInstance(APPROVER, PROC_ID));
    }

    @Test
    @DisplayName("抄送人能看")
    void testCopyUserAllowed() {
        mockNothing();
        when(processInstanceCopyService.isCopyUser(PROC_ID, CC_USER)).thenReturn(true);
        // 抄送人由参与表放行（S5a 在抄送时写入 COPY）
        when(instanceParticipantService.isParticipant(PROC_ID, CC_USER)).thenReturn(true);
        BpmProcessInstanceServiceImpl service = newService(instance());
        assertTrue(service.canUserViewProcessInstance(CC_USER, PROC_ID));
    }

    @Test
    @DisplayName("流程管理员能看（S5d 起由 BpmProcessPermissionChecker 判定，含旧列表回落）")
    void testManagerAllowed() {
        mockNothing();
        BpmProcessDefinitionInfoDO info = new BpmProcessDefinitionInfoDO();
        info.setManagerUserIds(List.of(MANAGER));
        when(processDefinitionService.getProcessDefinitionInfo(DEF_ID)).thenReturn(info);
        when(processPermissionChecker.isManager(eq(MANAGER), any())).thenReturn(true);
        BpmProcessInstanceServiceImpl service = newService(instance());
        assertTrue(service.canUserViewProcessInstance(MANAGER, PROC_ID));
    }

    @Test
    @DisplayName("持流程监控权限的人能看 —— 否则监控页列得出来却点不开")
    void testMonitorPermissionAllowed() {
        mockNothing();
        when(securityFrameworkService.hasPermission("bpm:process-instance:manager-query")).thenReturn(true);
        BpmProcessInstanceServiceImpl service = newService(instance());
        assertTrue(service.canUserViewProcessInstance(STRANGER, PROC_ID));
    }

    @Test
    @DisplayName("未发起的场景（实例号为空）放行 —— 新建流程时要预览审批链")
    void testBlankInstanceIdAllowed() {
        BpmProcessInstanceServiceImpl service = newService(null);
        assertTrue(service.canUserViewProcessInstance(STRANGER, null));
        assertTrue(service.canUserViewProcessInstance(STRANGER, ""));
    }

    @Test
    @DisplayName("实例不存在时放行，交给调用方报「流程实例不存在」—— 不冒充成权限问题")
    void testMissingInstanceNotMaskedAsPermission() {
        BpmProcessInstanceServiceImpl service = newService(null);
        assertTrue(service.canUserViewProcessInstance(STRANGER, PROC_ID));
    }

    @Test
    @DisplayName("未登录（userId 为空）拒绝")
    void testNullUserDenied() {
        BpmProcessInstanceServiceImpl service = newService(instance());
        assertFalse(service.canUserViewProcessInstance(null, PROC_ID));
    }

    // ==================== S5b：参与表 ====================

    @Test
    @DisplayName("S5b · 参与表命中即放行，不必再查 Flowable")
    void testParticipantAllowedWithoutLegacyQuery() {
        mockNothing();
        when(instanceParticipantService.isParticipant(PROC_ID, STRANGER)).thenReturn(true);

        BpmProcessInstanceServiceImpl service = newService(instance());
        assertTrue(service.canUserViewProcessInstance(STRANGER, PROC_ID));
        // 参与表命中就该短路。还去遍历历史任务的话，
        // 建这张表最主要的收益（把 N 次查询压成 1 次）就没了
        verify(taskService, never()).getTaskListByProcessInstanceId(anyString(), any());
    }

    @Test
    @DisplayName("🔴 TODO #30 已摘回落：参与表缺行即拒绝 —— 所以补登记脚本必须先跑")
    void testParticipantMissNoLongerFallsBack() {
        mockNothing();
        when(instanceParticipantService.isParticipant(anyString(), any())).thenReturn(false);
        // 他是发起人，但参与表里没有记录
        BpmProcessInstanceServiceImpl service = newService(instance());

        assertFalse(service.canUserViewProcessInstance(STARTER, PROC_ID),
                "摘除回落的前提是对账通过（xpy-2026-09-17-fallback-reconcile.sql 两个数都为 0）。"
                        + "从别的环境导入历史数据后必须先跑补登记脚本，"
                        + "否则那批实例的参与人会打不开自己的单子");
        // 旧的五分支不该再被调用 —— 摘除的意义正在于此
        verify(taskService, never()).getTaskListByProcessInstanceId(anyString(), any());
        verify(processInstanceCopyService, never()).isCopyUser(anyString(), any());
    }

    @Test
    @DisplayName("⚠️ S5b · 参与表与回落都不命中，仍然拒绝 —— 越权那条底线不能松")
    void testStrangerStillDeniedAfterS5b() {
        mockNothing();
        when(instanceParticipantService.isParticipant(anyString(), any())).thenReturn(false);

        BpmProcessInstanceServiceImpl service = newService(instance());
        assertFalse(service.canUserViewProcessInstance(STRANGER, PROC_ID));
    }

}
