package cn.enlev.ainb.module.bpm.service.task;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import cn.enlev.ainb.module.bpm.dal.dataobject.task.BpmInstanceParticipantDO;
import cn.enlev.ainb.module.bpm.dal.mysql.task.BpmInstanceParticipantMapper;
import cn.enlev.ainb.module.bpm.enums.task.BpmParticipationEnum;
import cn.enlev.ainb.module.bpm.service.definition.BpmProcessDefinitionService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ExecutionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 流程实例参与人的六个写入时机
 *
 * <h3>这批用例挡的是什么</h3>
 *
 * 参与关系必须在<b>发生的那一刻</b>写下来 —— 事后从 Flowable 推不回来。
 * 漏记一个时机的表现不是报错，而是「某个人本该看得到这张单子，却打不开」，
 * 且没有任何日志能指向真因。六个时机各测一条，就是钉住「都接上了」。
 *
 * <p>另外三条不变量单独测：</p>
 * <ul>
 *   <li>转办 / 委派 / 加签<b>只记原受理人</b> —— 新受理人由任务分配事件自动记，
 *       这里再记一次会出现同一人同一任务两行</li>
 *   <li>顶层实例的 {@code root} 补成<b>自身</b>而不是留空</li>
 *   <li>历史态求 root 要<b>沿父链上溯</b> ——
 *       {@code HistoricProcessInstance} 没有 {@code getRootProcessInstanceId()}</li>
 * </ul>
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class BpmInstanceParticipantServiceImplTest extends BaseMockitoUnitTest {

    private static final String PROC_ID = "proc-1";
    private static final String CHILD_ID = "proc-child";
    private static final String DEF_ID = "def-1";
    private static final String TASK_ID = "task-1";
    private static final String ACTIVITY_ID = "node-1";
    private static final String FORM_CODE = "demo_leave";
    private static final String BUSINESS_KEY = "9527";
    private static final Long STARTER = 1000L;
    private static final Long APPROVER = 2000L;
    private static final Long TARGET = 3000L;
    private static final Long CC_USER = 4000L;

    @Mock
    private BpmInstanceParticipantMapper participantMapper;
    @Mock
    private RuntimeService runtimeService;
    @Mock
    private HistoryService historyService;
    @Mock
    private BpmProcessDefinitionService processDefinitionService;

    private BpmInstanceParticipantServiceImpl service;

    /**
     * ⚠️ 三个查询对象只建一次，按 id 路由到各自的结果
     *
     * 每个 helper 里各 mock 一次 {@code createProcessInstanceQuery()} 的话，
     * 后一次会把前一次整个覆盖掉 —— 多层血缘的用例就只剩最后一层有桩
     */
    private ProcessInstanceQuery runtimeQuery;
    private HistoricProcessInstanceQuery historicQuery;
    private ExecutionQuery executionQuery;

    @BeforeEach
    void setUp() {
        service = new BpmInstanceParticipantServiceImpl();
        ReflectionTestUtils.setField(service, "participantMapper", participantMapper);
        ReflectionTestUtils.setField(service, "runtimeService", runtimeService);
        ReflectionTestUtils.setField(service, "historyService", historyService);
        ReflectionTestUtils.setField(service, "processDefinitionService", processDefinitionService);

        runtimeQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(runtimeQuery);
        historicQuery = mock(HistoricProcessInstanceQuery.class);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historicQuery);
        executionQuery = mock(ExecutionQuery.class);
        when(runtimeService.createExecutionQuery()).thenReturn(executionQuery);
        // 默认：任何 id 在运行态与历史态都查不到，由各用例按需放行。
        // ⚠️ 子查询对象必须【先建好再传进 when】—— 写成 when(x).thenReturn(piResult(null))
        // 会在外层打桩未完成时又发起一次打桩，Mockito 直接抛 UnfinishedStubbingException
        ProcessInstanceQuery emptyPi = piResult(null);
        HistoricProcessInstanceQuery emptyHpi = hpiResult(null);
        ExecutionQuery emptyExec = execResult(null);
        when(runtimeQuery.processInstanceId(anyString())).thenReturn(emptyPi);
        when(historicQuery.processInstanceId(anyString())).thenReturn(emptyHpi);
        when(executionQuery.executionId(anyString())).thenReturn(emptyExec);

        // 默认：表里还没有记录（即不走「复用已算好的血缘」那条捷径）
        when(participantMapper.selectFirstByProcessInstanceId(anyString())).thenReturn(null);
        when(participantMapper.selectChildInstanceIds(anyString())).thenReturn(List.of());
        when(participantMapper.selectInstanceIdsByRoot(anyString())).thenReturn(List.of());
        when(participantMapper.selectByTaskAndUserAndParticipation(
                anyString(), any(), any(), any())).thenReturn(null);

        BpmProcessDefinitionInfoDO info = new BpmProcessDefinitionInfoDO();
        info.setFormDesignCode(FORM_CODE);
        when(processDefinitionService.getProcessDefinitionInfo(anyString())).thenReturn(info);
    }

    // ==================== 六个时机 ====================

    @Test
    @DisplayName("时机 1 · 创建：记发起人，且不可写")
    void testRecordStarter() {
        service.recordStarter(runningInstance(PROC_ID, null));

        BpmInstanceParticipantDO saved = captureInsert();
        assertEquals(STARTER, saved.getUserId());
        assertEquals(BpmParticipationEnum.STARTER.getParticipation(), saved.getParticipation());
        assertEquals(FORM_CODE, saved.getFormCode(), "表单编码要冗余下来，否则按表单筛选无从查起");
        assertEquals(BUSINESS_KEY, saved.getBusinessKey(), "业务主键是派生可见性（S5c）的钥匙");
        assertFalse(saved.getWritable(),
                "发起人不可写 —— 改单据走「撤回后重新提交」，不是直接改");
        assertNotNull(saved.getStartTime());
        assertNull(saved.getEndTime(), "刚记录时参与尚未结束");
    }

    @Test
    @DisplayName("时机 2 · 任务分配：记审批人，且可写")
    void testRecordApprover() {
        mockRuntimeInstance(PROC_ID, null);

        service.recordApprover(task(APPROVER));

        BpmInstanceParticipantDO saved = captureInsert();
        assertEquals(APPROVER, saved.getUserId());
        assertEquals(BpmParticipationEnum.APPROVER.getParticipation(), saved.getParticipation());
        assertEquals(TASK_ID, saved.getTaskId());
        assertTrue(saved.getWritable(), "在办的审批人必须可写，否则审批页只能看不能填");
    }

    @Test
    @DisplayName("时机 3 · 转办：记原受理人，且【只读】")
    void testRecordTransferFrom() {
        mockRuntimeInstance(PROC_ID, null);

        service.recordHandover(PROC_ID, TASK_ID, ACTIVITY_ID, APPROVER,
                BpmParticipationEnum.TRANSFER_FROM);

        BpmInstanceParticipantDO saved = captureInsert();
        assertEquals(APPROVER, saved.getUserId(), "记的是【原】受理人");
        assertEquals(BpmParticipationEnum.TRANSFER_FROM.getParticipation(), saved.getParticipation());
        assertFalse(saved.getWritable(), "转办出去就不归他办了，只保留可见性");
    }

    @Test
    @DisplayName("时机 3 · 委派：原受理人【保有可写】—— 与转办的唯一差别")
    void testRecordDelegateFromKeepsWritable() {
        mockRuntimeInstance(PROC_ID, null);

        service.recordHandover(PROC_ID, TASK_ID, ACTIVITY_ID, APPROVER,
                BpmParticipationEnum.DELEGATE_FROM);

        BpmInstanceParticipantDO saved = captureInsert();
        assertTrue(saved.getWritable(),
                "委派办完会退回原受理人，写成不可写的话「委派回来之后自己反而改不了了」");
    }

    @Test
    @DisplayName("时机 3 · 加签：记加签发起人")
    void testRecordSignFrom() {
        mockRuntimeInstance(PROC_ID, null);

        service.recordHandover(PROC_ID, TASK_ID, ACTIVITY_ID, APPROVER,
                BpmParticipationEnum.SIGN_FROM);

        BpmInstanceParticipantDO saved = captureInsert();
        assertEquals(BpmParticipationEnum.SIGN_FROM.getParticipation(), saved.getParticipation());
        assertTrue(saved.getWritable(), "加签发起人最终仍要办理这个任务");
    }

    @Test
    @DisplayName("时机 4 · 抄送：每个被抄送人一行，且全部只读")
    void testRecordCopy() {
        mockRuntimeInstance(PROC_ID, null);

        service.recordCopy(PROC_ID, TASK_ID, ACTIVITY_ID, List.of(CC_USER, TARGET));

        ArgumentCaptor<BpmInstanceParticipantDO> captor =
                ArgumentCaptor.forClass(BpmInstanceParticipantDO.class);
        verify(participantMapper, times(2)).insert(captor.capture());
        for (BpmInstanceParticipantDO saved : captor.getAllValues()) {
            assertEquals(BpmParticipationEnum.COPY.getParticipation(), saved.getParticipation());
            assertFalse(saved.getWritable(),
                    "抄送的语义是「知会」。记成可写的话，「抄送给财务看一眼」会变成「财务能改这张单子」");
        }
    }

    @Test
    @DisplayName("时机 5 · 任务办结：按 taskId 收口，不是删除")
    void testCloseTask() {
        service.closeTask(TASK_ID);

        verify(participantMapper).closeByTaskId(eq(TASK_ID), any());
        verify(participantMapper, never()).deleteByProcessInstanceId(anyString());
    }

    @Test
    @DisplayName("时机 6 · 流程办结：按实例收口，不是删除")
    void testCloseInstance() {
        service.closeInstance(PROC_ID);

        verify(participantMapper).closeByProcessInstanceId(eq(PROC_ID), any());
        // 删掉的话已办结流程的参与人会立刻失去可见性 ——
        // 表现是「昨天还能查到的历史审批，今天打不开了」
        verify(participantMapper, never()).deleteByProcessInstanceId(anyString());
    }

    // ==================== 血缘 ====================

    @Test
    @DisplayName("⚠️ 顶层实例的 root 补成自身，而不是留空")
    void testRootFallsBackToSelf() {
        ProcessInstance instance = runningInstance(PROC_ID, null);
        when(instance.getRootProcessInstanceId()).thenReturn(null);

        service.recordStarter(instance);

        assertEquals(PROC_ID, captureInsert().getRootProcessInstanceId(),
                "留空的话「查主单据下的全部参与人」要写成 root = ? OR (root IS NULL AND ...)，"
                        + "既走不好索引，也迟早有人漏写后半截");
    }

    @Test
    @DisplayName("子流程：root 与 parent 都要落库")
    void testChildProcessLineage() {
        // 子流程的 superExecutionId 指向父流程的某个 execution
        ProcessInstance child = runningInstance(CHILD_ID, "exec-parent");
        when(child.getRootProcessInstanceId()).thenReturn(PROC_ID);
        mockExecution("exec-parent", PROC_ID);

        service.recordStarter(child);

        BpmInstanceParticipantDO saved = captureInsert();
        assertEquals(PROC_ID, saved.getRootProcessInstanceId());
        assertEquals(PROC_ID, saved.getParentProcessInstanceId(),
                "父子关系挂在 execution 上，要多一跳才拿得到父实例编号");
    }

    @Test
    @DisplayName("⚠️ 历史态求 root 靠沿父链上溯 —— HistoricProcessInstance 没有 getRootProcessInstanceId()")
    void testHistoricRootWalksUpSuperChain() {
        // 运行态查不到（流程已办结），只能走历史态 —— 默认桩就是查不到，无需再置
        // 三层：CHILD_ID → mid → PROC_ID(顶层)
        mockHistoricInstance(CHILD_ID, "mid");
        mockHistoricInstance("mid", PROC_ID);
        mockHistoricInstance(PROC_ID, null);

        service.recordHandover(CHILD_ID, TASK_ID, ACTIVITY_ID, APPROVER,
                BpmParticipationEnum.TRANSFER_FROM);

        BpmInstanceParticipantDO saved = captureInsert();
        assertEquals(PROC_ID, saved.getRootProcessInstanceId(),
                "要一路上溯到没有父实例的那一层，而不是停在直接父实例");
        assertEquals("mid", saved.getParentProcessInstanceId(),
                "parent 是【直接】父实例，与 root 不是一回事");
    }

    // ==================== 其它不变量 ====================

    @Test
    @DisplayName("⚠️ 重复触发不产生第二行 —— Flowable 事件会重试")
    void testDuplicateIsSkipped() {
        mockRuntimeInstance(PROC_ID, null);
        when(participantMapper.selectByTaskAndUserAndParticipation(
                anyString(), any(), any(), any())).thenReturn(new BpmInstanceParticipantDO());

        service.recordApprover(task(APPROVER));

        verify(participantMapper, never()).insert(any(BpmInstanceParticipantDO.class));
    }

    @Test
    @DisplayName("⚠️ 移交入口拒绝非移交的参与方式，不写脏数据")
    void testHandoverRejectsNonHandoverParticipation() {
        mockRuntimeInstance(PROC_ID, null);

        service.recordHandover(PROC_ID, TASK_ID, ACTIVITY_ID, APPROVER, BpmParticipationEnum.COPY);

        verify(participantMapper, never()).insert(any(BpmInstanceParticipantDO.class));
    }

    @Test
    @DisplayName("参与人判定要上溯祖先：父流程参与人能看子流程")
    void testIsParticipantIncludesAncestors() {
        // 子流程自己没有该用户的参与记录，但父流程有
        mockRuntimeInstance(CHILD_ID, "exec-parent");
        mockExecution("exec-parent", PROC_ID);
        mockRuntimeInstance(PROC_ID, null);

        service.isParticipant(CHILD_ID, STARTER);

        // S6c 起判定拆成两步：先查自己（快路径），再查祖先链。
        // 两步合起来要覆盖「自己 + 父实例」
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(participantMapper, atLeastOnce())
                .existsByProcessInstanceIdsAndUserId(captor.capture(), eq(STARTER));
        Set<String> asked = new java.util.LinkedHashSet<>();
        captor.getAllValues().forEach(asked::addAll);
        assertTrue(asked.contains(CHILD_ID), "自己要被查到");
        assertTrue(asked.contains(PROC_ID),
                "父实例要在链上，否则父流程发起人看不到子流程进展");
    }

    // ==================== S6c：父子可见性配置 ====================

    @Test
    @DisplayName("⚠️ 未配置可见性时沿用默认（父→子可见）—— 存量流程不能集体隐身")
    void testDefaultParentCanViewChild() {
        mockRuntimeInstance(CHILD_ID, "exec-parent");
        mockExecution("exec-parent", PROC_ID);
        mockRuntimeInstance(PROC_ID, null);
        // processDefinitionService 默认返回的 info 里 childProcessVisibilitySetting 为 null

        service.isParticipant(CHILD_ID, STARTER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(participantMapper, atLeastOnce())
                .existsByProcessInstanceIdsAndUserId(captor.capture(), eq(STARTER));
        boolean askedAncestor = captor.getAllValues().stream().anyMatch(c -> c.contains(PROC_ID));
        assertTrue(askedAncestor, "没配过这一项的流程必须沿用默认可见，否则升级瞬间父流程参与人全看不到子流程");
    }

    @Test
    @DisplayName("⚠️ 关掉 parentCanViewChild 后，父流程参与人看不到这条子流程")
    void testParentCanViewChildDisabled() {
        mockRuntimeInstance(CHILD_ID, "exec-parent");
        mockExecution("exec-parent", PROC_ID);
        mockRuntimeInstance(PROC_ID, null);
        BpmProcessDefinitionInfoDO info = new BpmProcessDefinitionInfoDO();
        info.setFormDesignCode(FORM_CODE);
        cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO
                .ChildProcessVisibilitySetting setting =
                new cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO
                        .ChildProcessVisibilitySetting();
        setting.setParentCanViewChild(false);
        info.setChildProcessVisibilitySetting(setting);
        when(processDefinitionService.getProcessDefinitionInfo(anyString())).thenReturn(info);
        // 子流程自身没有该用户的参与记录
        when(participantMapper.existsByProcessInstanceIdsAndUserId(any(), any())).thenReturn(false);

        assertFalse(service.isParticipant(CHILD_ID, STARTER),
                "典型场景：子流程是「薪资核定」，主单据发起人不该看到");
    }

    @Test
    @DisplayName("直接参与不受可见性配置影响 —— 他本来就在这条实例上")
    void testDirectParticipantUnaffectedByVisibility() {
        mockRuntimeInstance(CHILD_ID, "exec-parent");
        BpmProcessDefinitionInfoDO info = new BpmProcessDefinitionInfoDO();
        cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO
                .ChildProcessVisibilitySetting setting =
                new cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO
                        .ChildProcessVisibilitySetting();
        setting.setParentCanViewChild(false);
        info.setChildProcessVisibilitySetting(setting);
        when(processDefinitionService.getProcessDefinitionInfo(anyString())).thenReturn(info);
        when(participantMapper.existsByProcessInstanceIdsAndUserId(argThat(
                c -> c != null && c.contains(CHILD_ID)), eq(STARTER))).thenReturn(true);

        assertTrue(service.isParticipant(CHILD_ID, STARTER),
                "关掉的是「父流程参与人看子流程」，不是「子流程自己的参与人」");
    }

    @Test
    @DisplayName("⚠️ 子→父默认可见：子流程参与人能看父流程，否则审批人不知道自己在审什么的上游")
    void testChildCanViewParentByDefault() {
        mockRuntimeInstance(PROC_ID, null);
        // 父实例自身没有该用户的参与记录，但子实例有
        when(participantMapper.selectChildInstanceIds(PROC_ID)).thenReturn(List.of(CHILD_ID));
        when(participantMapper.existsByProcessInstanceIdsAndUserId(
                argThat(c -> c != null && c.contains(CHILD_ID)), eq(APPROVER))).thenReturn(true);

        assertTrue(service.isParticipant(PROC_ID, APPROVER));
    }

    @Test
    @DisplayName("⚠️ 关掉 childCanViewParent 后，子流程参与人看不到父流程")
    void testChildCanViewParentDisabled() {
        mockRuntimeInstance(PROC_ID, null);
        mockVisibility(null, false, null);
        when(participantMapper.selectChildInstanceIds(PROC_ID)).thenReturn(List.of(CHILD_ID));
        when(participantMapper.existsByProcessInstanceIdsAndUserId(
                argThat(c -> c != null && c.contains(CHILD_ID)), eq(APPROVER))).thenReturn(true);

        assertFalse(service.isParticipant(PROC_ID, APPROVER));
    }

    @Test
    @DisplayName("🔴 兄弟子流程默认【不】可见 —— 开了等于同主单据下所有子流程互相透明")
    void testSiblingInvisibleByDefault() {
        mockRuntimeInstance(CHILD_ID, "exec-parent");
        mockExecution("exec-parent", PROC_ID);
        mockRuntimeInstance(PROC_ID, null);

        service.isParticipant(CHILD_ID, APPROVER);

        // 默认关闭意味着这条查询压根不该执行
        verify(participantMapper, never()).selectInstanceIdsByRoot(anyString());
    }

    @Test
    @DisplayName("显式打开 childCanViewSibling 后才查同根实例")
    void testSiblingVisibleWhenEnabled() {
        mockRuntimeInstance(CHILD_ID, "exec-parent");
        mockExecution("exec-parent", PROC_ID);
        mockVisibility(null, null, true);

        service.isParticipant(CHILD_ID, APPROVER);

        verify(participantMapper).selectInstanceIdsByRoot(anyString());
    }

    @Test
    @DisplayName("受理人不是数字时忽略，不让审批因为记不了参与人而失败")
    void testNonNumericAssigneeIgnored() {
        mockRuntimeInstance(PROC_ID, null);

        assertDoesNotThrow(() -> service.recordApprover(task("flowable-system")));
        verify(participantMapper, never()).insert(any(BpmInstanceParticipantDO.class));
    }

    @Test
    @DisplayName("实例在运行态与历史态都查不到时跳过，不抛异常")
    void testMissingInstanceSkipped() {
        // 默认桩即「两处都查不到」
        assertDoesNotThrow(() -> service.recordApprover(task(APPROVER)));
        verify(participantMapper, never()).insert(any(BpmInstanceParticipantDO.class));
    }

    // ==================== 构造 ====================

    private BpmInstanceParticipantDO captureInsert() {
        ArgumentCaptor<BpmInstanceParticipantDO> captor =
                ArgumentCaptor.forClass(BpmInstanceParticipantDO.class);
        verify(participantMapper).insert(captor.capture());
        return captor.getValue();
    }

    private ProcessInstance runningInstance(String id, String superExecutionId) {
        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getId()).thenReturn(id);
        when(instance.getStartUserId()).thenReturn(String.valueOf(STARTER));
        when(instance.getProcessDefinitionId()).thenReturn(DEF_ID);
        when(instance.getBusinessKey()).thenReturn(BUSINESS_KEY);
        when(instance.getRootProcessInstanceId()).thenReturn(id);
        when(instance.getSuperExecutionId()).thenReturn(superExecutionId);
        return instance;
    }

    /** 让 runtimeService 能按 id 查到这条实例 */
    private void mockRuntimeInstance(String id, String superExecutionId) {
        ProcessInstanceQuery result = piResult(runningInstance(id, superExecutionId));
        when(runtimeQuery.processInstanceId(id)).thenReturn(result);
    }

    private void mockExecution(String executionId, String processInstanceId) {
        Execution execution = mock(Execution.class);
        when(execution.getProcessInstanceId()).thenReturn(processInstanceId);
        ExecutionQuery result = execResult(execution);
        when(executionQuery.executionId(executionId)).thenReturn(result);
    }

    private void mockHistoricInstance(String id, String superProcessInstanceId) {
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        when(historic.getId()).thenReturn(id);
        when(historic.getProcessDefinitionId()).thenReturn(DEF_ID);
        when(historic.getBusinessKey()).thenReturn(BUSINESS_KEY);
        when(historic.getSuperProcessInstanceId()).thenReturn(superProcessInstanceId);
        HistoricProcessInstanceQuery result = hpiResult(historic);
        when(historicQuery.processInstanceId(id)).thenReturn(result);
    }

    private ProcessInstanceQuery piResult(ProcessInstance value) {
        ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
        when(query.singleResult()).thenReturn(value);
        return query;
    }

    private HistoricProcessInstanceQuery hpiResult(HistoricProcessInstance value) {
        HistoricProcessInstanceQuery query = mock(HistoricProcessInstanceQuery.class);
        when(query.singleResult()).thenReturn(value);
        return query;
    }

    private ExecutionQuery execResult(Execution value) {
        ExecutionQuery query = mock(ExecutionQuery.class);
        when(query.singleResult()).thenReturn(value);
        return query;
    }

    /** 配置父子可见性；null 表示该项不配（走默认） */
    private void mockVisibility(Boolean parentCanViewChild, Boolean childCanViewParent,
                                Boolean childCanViewSibling) {
        BpmProcessDefinitionInfoDO info = new BpmProcessDefinitionInfoDO();
        info.setFormDesignCode(FORM_CODE);
        cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO
                .ChildProcessVisibilitySetting setting =
                new cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO
                        .ChildProcessVisibilitySetting();
        setting.setParentCanViewChild(parentCanViewChild);
        setting.setChildCanViewParent(childCanViewParent);
        setting.setChildCanViewSibling(childCanViewSibling);
        info.setChildProcessVisibilitySetting(setting);
        when(processDefinitionService.getProcessDefinitionInfo(anyString())).thenReturn(info);
    }

    private Task task(Long assignee) {
        return task(String.valueOf(assignee));
    }

    private Task task(String assignee) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(TASK_ID);
        when(task.getProcessInstanceId()).thenReturn(PROC_ID);
        when(task.getTaskDefinitionKey()).thenReturn(ACTIVITY_ID);
        when(task.getAssignee()).thenReturn(assignee);
        return task;
    }

}
