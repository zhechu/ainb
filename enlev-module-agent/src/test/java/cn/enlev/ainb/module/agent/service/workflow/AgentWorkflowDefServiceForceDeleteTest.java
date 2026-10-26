package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentNodeExecutionDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentNodeExecutionMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import cn.enlev.ainb.module.agent.enums.WorkflowExecutionStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_DELETE_CHECKPOINT_FAILED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_DELETE_HAS_UNFINISHED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_DELETE_PINNED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_DELETABLE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentWorkflowDefServiceImpl#forceDeleteDef} 单测（迭代七 / 轮10 D3）
 *
 * <p>纯 mock，0 token、不碰真 DB、更不碰真 checkpoint 表。与
 * {@code AgentWorkflowDefServiceDeleteTest}（测常规档 {@code deleteDef} / 批量档 {@code deleteDefList}）互补：
 * 那个测「常规删除只放行草稿与已停用」，本类测「管理员档连已发布也能删、
 * 但同样要先过两道闸门，且级联顺序不可换」。
 *
 * <p>🔴 迭代八起两档<b>共用同一份 Service 内核</b>（{@code doDeleteDefs}），差别只有状态闸门那一道。
 * 故本类里那两道闸门与级联顺序的用例，同时也就是常规档的判据 —— 它们<b>不是</b>各写一份的副本，
 * 改一处两档一起变。这正是「共用内核」要的效果，也是本类不必再复制一遍那些用例的原因。
 *
 * <h3>本类盯的三件事（对应轮10 分册 §3.2 D3 的三条要求）</h3>
 * <ol>
 *   <li><b>孤儿引用</b>：仍被活定义的 {@code sub_workflow} pin 住 ⟹ 拒绝并点名，<b>绝不</b>悄悄删成断链；
 *       判据与运行期 {@code getSubWorkflowDef} 同源（pin 版本走精确匹配、未 pin 走「回落还能不能落到别的已发布版」）。</li>
 *   <li><b>执行史</b>：名下有未终态执行 ⟹ 拒绝，并给出 {@code POST /agent/workflow/cancel} 这条现成出路；
 *       全是终态 ⟹ 连带软删执行行与节点 trace 行。</li>
 *   <li><b>顺序</b>：释放 checkpoint 必须发生在<b>任何</b>业务行被改动之前 ——
 *       {@code thread_name} 只存在执行行上，而它是 GRAPH_CHECKPOINT 的唯一寻址键，
 *       先删行就等于亲手造出「查不出属于谁、也没有接口能再删」的快照孤儿。</li>
 * </ol>
 *
 * <p>🔴 fail-close 的两处（DSL 解析不出、{@code version} 填了非数字）各有专门用例：
 * 强制删除不可逆，「说不清有没有人 pin」时必须当成「有人 pin」。
 *
 * <p>🔴 释放 checkpoint 的失败<b>分两类</b>，各有用例，不可合并（2026-10-08 字节码实测 SAA 的两条抛出路径）：
 * <ul>
 *   <li>真失败（连不上库 / SQL 报错，SAA 抛受检 {@code Exception}）⟹ 整单中止、业务行一行未动。</li>
 *   <li>「thread 不存在 / 早已释放」（SAA 抛 {@code IllegalStateException}）⟹ 记一行 warn 后继续。
 *       把这一类也当中止会造出<b>死结</b>，详见对应用例的注释。</li>
 * </ul>
 *
 * <p>⚠️ 顺带记一笔现场实测：{@code release} <b>不删</b> {@code graph_checkpoint} 的行，
 * 只把 {@code graph_thread.is_released} 翻成 1（快照从此读不出来）。样本 def #584（其执行 #175 的 thread）
 * 强删后仍有 2 行快照。故本类只验「调了 release、且顺序在业务行之前」，
 * 「快照行数归零」<b>不是</b>判据、也验不出来。
 *
 * @author 走进新时代
 */
class AgentWorkflowDefServiceForceDeleteTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentWorkflowDefServiceImpl defService;

    @Mock
    private AgentWorkflowDefMapper defMapper;
    @Mock
    private AgentWorkflowExecutionMapper executionMapper;
    @Mock
    private AgentNodeExecutionMapper nodeExecutionMapper;
    @Mock
    private DslParser dslParser;
    /** 只 mock 接口：{@code release} 在 {@code MemorySaver} 上是 final，但接口上不是，故可 stub/verify */
    @Mock
    private BaseCheckpointSaver checkpointSaver;

    // ==================== 造数工具 ====================

    private static AgentWorkflowDefDO def(Long id, String code, Integer version, String status) {
        AgentWorkflowDefDO d = new AgentWorkflowDefDO();
        d.setId(id);
        d.setCode(code);
        d.setVersion(version);
        d.setStatus(status);
        d.setDsl("{\"id\":\"" + code + "\"}");
        return d;
    }

    private static AgentWorkflowExecutionDO execution(Long id, String status, String threadName) {
        AgentWorkflowExecutionDO e = new AgentWorkflowExecutionDO();
        e.setId(id);
        e.setStatus(status);
        e.setThreadName(threadName);
        return e;
    }

    /** 造一个「只含一个 sub_workflow 节点」的解析结果；{@code version} 为 null 表示 DSL 里没写这一格 */
    private static WorkflowDefDTO subWorkflowDsl(String childCode, Object version) {
        Map<String, Object> config = new HashMap<>();
        config.put("workflowId", childCode);
        if (version != null) {
            config.put("version", version);
        }
        NodeDTO node = new NodeDTO();
        node.setId("call_child");
        node.setType("sub_workflow");
        node.setConfig(config);
        WorkflowDefDTO dto = new WorkflowDefDTO();
        List<NodeDTO> nodes = new ArrayList<>();
        nodes.add(node);
        dto.setNodes(nodes);
        return dto;
    }

    /** 目标行是 PUBLISHED、库里只有它自己 ⟹ 最常见的强删场景 */
    private void stubTargetOnly(Long id, String code, Integer version, String status) {
        when(defMapper.selectById(id)).thenReturn(def(id, code, version, status));
        when(defMapper.selectListForAudit()).thenReturn(List.of(def(id, code, version, status)));
    }

    private void verifyNothingTouched() throws Exception {
        verify(defMapper, never()).deleteById(any());
        verify(executionMapper, never()).deleteBatch(any(), any());
        verify(nodeExecutionMapper, never()).deleteBatch(any(), any());
        verifyNoRelease();
    }

    private void verifyNoRelease() throws Exception {
        verify(checkpointSaver, never()).release(any());
    }

    // ==================== 闸门零：行得先存在 ====================

    @Test
    void forceDeleteDef_notExists_throws() throws Exception {
        when(defMapper.selectById(404L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.forceDeleteDef(404L));

        assertEquals(WORKFLOW_DEF_NOT_EXISTS.getCode(), ex.getCode());
        verifyNothingTouched();
    }

    // ==================== 闸门一：sub_workflow pin ====================

    @Test
    void forceDeleteDef_pinnedByVersion_refusesAndNamesReferencer() throws Exception {
        AgentWorkflowDefDO target = def(503L, "wf_child", 2, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        AgentWorkflowDefDO referencer = def(600L, "wf_parent", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectById(503L)).thenReturn(target);
        when(defMapper.selectListForAudit()).thenReturn(List.of(target, referencer));
        // 只有引用方的 DSL 会被解析（目标行自己被跳过），故只 stub 它，避免 STRICT_STUBS 判多余
        when(dslParser.parse(referencer.getDsl())).thenReturn(subWorkflowDsl("wf_child", 2));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.forceDeleteDef(503L));

        assertEquals(WORKFLOW_DEF_DELETE_PINNED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("#600 wf_parent@1"), "文案要点名引用方，原文：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("call_child"), "文案要点名是哪个节点，原文：" + ex.getMessage());
        verifyNothingTouched();
    }

    @Test
    void forceDeleteDef_pinnedByOtherVersion_allowed() throws Exception {
        // pin 的是 v1，删的是 v2 ⟹ 指向的是另一行，与本次删除无关
        AgentWorkflowDefDO target = def(503L, "wf_child", 2, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        AgentWorkflowDefDO referencer = def(600L, "wf_parent", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectById(503L)).thenReturn(target);
        when(defMapper.selectListForAudit()).thenReturn(List.of(target, referencer));
        when(dslParser.parse(referencer.getDsl())).thenReturn(subWorkflowDsl("wf_child", 1));
        when(executionMapper.selectListByDefId(503L)).thenReturn(List.of());

        defService.forceDeleteDef(503L);

        verify(defMapper).deleteById(503L);
    }

    @Test
    void forceDeleteDef_unpinnedAndLastPublishedOfCode_refuses() throws Exception {
        // 未 pin 版本 ⟹ 运行期走 selectPublishedByCode 回落；本行是最后一个活 PUBLISHED ⟹ 删了就回落不到任何东西
        AgentWorkflowDefDO target = def(503L, "wf_child", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        AgentWorkflowDefDO referencer = def(600L, "wf_parent", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectById(503L)).thenReturn(target);
        when(defMapper.selectListForAudit()).thenReturn(List.of(target, referencer));
        when(dslParser.parse(referencer.getDsl())).thenReturn(subWorkflowDsl("wf_child", null));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.forceDeleteDef(503L));

        assertEquals(WORKFLOW_DEF_DELETE_PINNED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("回不到别的已发布版本"), "要说清是回落断链，原文：" + ex.getMessage());
        verifyNothingTouched();
    }

    @Test
    void forceDeleteDef_unpinnedButAnotherPublishedSurvives_allowed() throws Exception {
        // 同 code 还有一个活 PUBLISHED 版本 ⟹ 回落落得到它，删本行不制造断链
        AgentWorkflowDefDO target = def(503L, "wf_child", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        AgentWorkflowDefDO survivor = def(504L, "wf_child", 2, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        AgentWorkflowDefDO referencer = def(600L, "wf_parent", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectById(503L)).thenReturn(target);
        when(defMapper.selectListForAudit()).thenReturn(List.of(target, survivor, referencer));
        // survivor 的 DSL 里没有 sub_workflow 节点（nodes 为 null）⟹ 扫描时跳过
        when(dslParser.parse(survivor.getDsl())).thenReturn(new WorkflowDefDTO());
        when(dslParser.parse(referencer.getDsl())).thenReturn(subWorkflowDsl("wf_child", null));
        when(executionMapper.selectListByDefId(503L)).thenReturn(List.of());

        defService.forceDeleteDef(503L);

        verify(defMapper).deleteById(503L);
        verify(defMapper, never()).deleteById(504L);
    }

    @Test
    void forceDeleteDef_unpinnedAndTargetIsDraft_allowed() throws Exception {
        // 目标行是 DRAFT ⟹ selectPublishedByCode 压根选不到它，删它不改变回落结果
        AgentWorkflowDefDO target = def(503L, "wf_child", 3, WorkflowDefStatusEnum.DRAFT.getStatus());
        AgentWorkflowDefDO referencer = def(600L, "wf_parent", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectById(503L)).thenReturn(target);
        when(defMapper.selectListForAudit()).thenReturn(List.of(target, referencer));
        when(dslParser.parse(referencer.getDsl())).thenReturn(subWorkflowDsl("wf_child", null));
        when(executionMapper.selectListByDefId(503L)).thenReturn(List.of());

        defService.forceDeleteDef(503L);

        verify(defMapper).deleteById(503L);
    }

    @Test
    void forceDeleteDef_referencerDslUnparsable_failClose() throws Exception {
        AgentWorkflowDefDO target = def(503L, "wf_child", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        AgentWorkflowDefDO broken = def(600L, "wf_parent", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectById(503L)).thenReturn(target);
        when(defMapper.selectListForAudit()).thenReturn(List.of(target, broken));
        when(dslParser.parse(broken.getDsl())).thenThrow(new RuntimeException("不是合法 JSON"));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.forceDeleteDef(503L));

        assertEquals(WORKFLOW_DEF_DELETE_PINNED.getCode(), ex.getCode(), "解析不出 = 说不清有没有 pin，取 fail-close");
        assertTrue(ex.getMessage().contains("DSL 解析失败"), "原文：" + ex.getMessage());
        verifyNothingTouched();
    }

    @Test
    void forceDeleteDef_pinVersionNotNumeric_failClose() throws Exception {
        AgentWorkflowDefDO target = def(503L, "wf_child", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        AgentWorkflowDefDO referencer = def(600L, "wf_parent", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectById(503L)).thenReturn(target);
        when(defMapper.selectListForAudit()).thenReturn(List.of(target, referencer));
        when(dslParser.parse(referencer.getDsl())).thenReturn(subWorkflowDsl("wf_child", "latest"));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.forceDeleteDef(503L));

        assertEquals(WORKFLOW_DEF_DELETE_PINNED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("version 填的不是数字"), "原文：" + ex.getMessage());
        verifyNothingTouched();
    }

    @Test
    void forceDeleteDef_otherCodeSubWorkflow_ignored() throws Exception {
        // 引用方 pin 的是别的 code ⟹ 与本行无关；本用例顺带钉住「不 pin 任何人的流程照常能删」
        AgentWorkflowDefDO target = def(503L, "wf_child", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        AgentWorkflowDefDO referencer = def(600L, "wf_parent", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectById(503L)).thenReturn(target);
        when(defMapper.selectListForAudit()).thenReturn(List.of(target, referencer));
        when(dslParser.parse(referencer.getDsl())).thenReturn(subWorkflowDsl("wf_someone_else", 1));
        when(executionMapper.selectListByDefId(503L)).thenReturn(List.of());

        defService.forceDeleteDef(503L);

        verify(defMapper).deleteById(503L);
    }

    // ==================== 闸门二：未终态执行 ====================

    @Test
    void forceDeleteDef_hasInterruptedExecution_refuses() throws Exception {
        stubTargetOnly(503L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(executionMapper.selectListByDefId(503L)).thenReturn(List.of(
                execution(900L, WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), "t1:900"),
                execution(901L, WorkflowExecutionStatusEnum.INTERRUPTED.getStatus(), "t1:901")));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.forceDeleteDef(503L));

        assertEquals(WORKFLOW_DEF_DELETE_HAS_UNFINISHED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("#901=INTERRUPTED"), "要指名道姓哪一条没完，原文：" + ex.getMessage());
        assertTrue(!ex.getMessage().contains("#900"), "已终态的不该被算进来，原文：" + ex.getMessage());
        verifyNothingTouched();
    }

    @Test
    void forceDeleteDef_compensatingExecution_refuses() throws Exception {
        // 🔴 反例钉死：COMPENSATING 既不在 isFinal 里、也**不可取消**（isCancellable 是白名单）。
        //    若实现拿 !isCancellable 当尺子，这条会被判成「已了结」而放过去 —— 可它正在逆序调反做工具。
        stubTargetOnly(503L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(executionMapper.selectListByDefId(503L)).thenReturn(List.of(
                execution(902L, WorkflowExecutionStatusEnum.COMPENSATING.getStatus(), "t1:902")));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.forceDeleteDef(503L));

        assertEquals(WORKFLOW_DEF_DELETE_HAS_UNFINISHED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("#902=COMPENSATING"), "原文：" + ex.getMessage());
        verifyNothingTouched();
    }

    @Test
    void forceDeleteDef_nullStatusExecution_failClose() throws Exception {
        stubTargetOnly(503L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(executionMapper.selectListByDefId(503L)).thenReturn(List.of(execution(903L, null, "t1:903")));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.forceDeleteDef(503L));

        assertEquals(WORKFLOW_DEF_DELETE_HAS_UNFINISHED.getCode(), ex.getCode(),
                "status 为 NULL 时 isFinal 返回 false ⟹ 一并拦住");
        verifyNothingTouched();
    }

    @Test
    void forceDeleteDef_failedExecutionIsFinal_allowed() throws Exception {
        // FAILED 是终态（B3-4 起 isFinal 含 FAILED），拦它会把「早已炸完、正等着清场」的那批永远锁在库里
        stubTargetOnly(503L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(executionMapper.selectListByDefId(503L)).thenReturn(List.of(
                execution(904L, WorkflowExecutionStatusEnum.FAILED.getStatus(), "t1:904"),
                execution(905L, WorkflowExecutionStatusEnum.CANCELLED.getStatus(), "t1:905"),
                execution(906L, WorkflowExecutionStatusEnum.COMPENSATION_FAILED.getStatus(), "t1:906")));

        defService.forceDeleteDef(503L);

        verify(defMapper).deleteById(503L);
    }

    // ==================== 级联：顺序 + 覆盖面 ====================

    @Test
    void forceDeleteDef_releasesCheckpointsBeforeTouchingAnyRow() throws Exception {
        stubTargetOnly(503L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(executionMapper.selectListByDefId(503L)).thenReturn(List.of(
                execution(900L, WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), "t1:900"),
                execution(901L, WorkflowExecutionStatusEnum.FAILED.getStatus(), "t1:901")));

        defService.forceDeleteDef(503L);

        // 🔴 顺序是本用例的全部意义：thread_name 只存在执行行上，先删行就再也找不回快照属于谁
        InOrder order = inOrder(checkpointSaver, nodeExecutionMapper, executionMapper, defMapper);
        order.verify(checkpointSaver, times(2)).release(any(RunnableConfig.class));
        order.verify(nodeExecutionMapper).deleteBatch(any(), any());
        order.verify(executionMapper).deleteBatch(any(), any());
        order.verify(defMapper).deleteById(503L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void forceDeleteDef_cascadesToNodeTraceAndExecutionRows() throws Exception {
        stubTargetOnly(503L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(executionMapper.selectListByDefId(503L)).thenReturn(List.of(
                execution(900L, WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), "t1:900"),
                execution(901L, WorkflowExecutionStatusEnum.CANCELLED.getStatus(), "t1:901")));

        defService.forceDeleteDef(503L);

        ArgumentCaptor<Collection<Long>> nodeCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(nodeExecutionMapper).deleteBatch(any(), nodeCaptor.capture());
        assertEquals(List.of(900L, 901L), new ArrayList<>(nodeCaptor.getValue()),
                "节点 trace 按 execution_id 连带软删，否则留下一堆指向不存在执行的行");
        ArgumentCaptor<Collection<Long>> execCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(executionMapper).deleteBatch(any(), execCaptor.capture());
        assertEquals(List.of(900L, 901L), new ArrayList<>(execCaptor.getValue()));
    }

    @Test
    void forceDeleteDef_noExecutions_stillDeletesDef() throws Exception {
        stubTargetOnly(519L, "wf_ro_pub_e2e", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(executionMapper.selectListByDefId(519L)).thenReturn(List.of());

        defService.forceDeleteDef(519L);

        verify(defMapper).deleteById(519L);
        verifyNoRelease();
        // deleteBatch 自带空集合守卫（返回 0、不打 IN () 的废 SQL），故这里只要求「调了也没删到东西」
    }

    @Test
    void forceDeleteDef_blankThreadName_skipsReleaseButStillDeletes() throws Exception {
        stubTargetOnly(503L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(executionMapper.selectListByDefId(503L)).thenReturn(List.of(
                execution(900L, WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), ""),
                execution(901L, WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), "t1:901")));

        defService.forceDeleteDef(503L);

        // 没有寻址键的那条跳过（SAA 那边本来也不可能有它的快照），有键的那条照释放
        verify(checkpointSaver, times(1)).release(any(RunnableConfig.class));
        verify(defMapper).deleteById(503L);
    }

    @Test
    void forceDeleteDef_releaseThrows_abortsBeforeAnyBusinessRow() throws Exception {
        stubTargetOnly(503L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(executionMapper.selectListByDefId(503L)).thenReturn(List.of(
                execution(900L, WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), "t1:900")));
        // 🔴 刻意用受检 Exception 而不是 IllegalStateException：后者在实现里是「目标状态已达成」的信号，
        //    走的是下一条用例。这里模拟的是 SAA 真失败的那条路径 ——
        //    MysqlSaver#releasedCheckpoints 捕到 SQLException 后抛的正是
        //    new Exception("Unable to release checkpoint", cause)（2026-10-08 字节码实测）。
        doThrow(new Exception("Unable to release checkpoint", new SQLException("connection refused")))
                .when(checkpointSaver).release(any(RunnableConfig.class));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.forceDeleteDef(503L));

        assertEquals(WORKFLOW_DEF_DELETE_CHECKPOINT_FAILED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("t1:900"), "文案要带上失败的 thread，原文：" + ex.getMessage());
        // 🔴 「中止」必须等于「业务数据一行未动」——这正是把释放排在级联软删之前的理由
        verify(defMapper, never()).deleteById(any());
        verify(executionMapper, never()).deleteBatch(any(), any());
        verify(nodeExecutionMapper, never()).deleteBatch(any(), any());
    }

    /**
     * 「thread 不存在 / 早已释放」不算失败：记一行 warn 后继续删。
     *
     * <p>🔴 这条用例钉的是一个<b>死结</b>，不是一条边界。SAA 的那条 UPDATE 带
     * {@code AND is_released = FALSE}，影响 0 行时抛
     * {@code IllegalStateException("Thread '%s' not found or already released")}。
     * 若把它当失败中止：一单里两条执行、第二条真失败 ⟹ 中止（业务行未动，看着很安全）⟹
     * 修好原因重跑 ⟹ 这次<b>第一条</b>已是 released ⟹ 抛 ISE ⟹ 再中止 ⟹ 这行定义<b>永远删不掉</b>，
     * 而「永远删不掉」恰好是强删档存在的唯一理由被自己否掉。
     */
    @Test
    void forceDeleteDef_releaseThrowsAlreadyReleased_toleratedAndStillDeletes() throws Exception {
        stubTargetOnly(503L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(executionMapper.selectListByDefId(503L)).thenReturn(List.of(
                execution(900L, WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), "t1:900"),
                execution(901L, WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), "t1:901")));
        // 第一条早已释放（上一轮中止留下的现场），第二条正常
        // 🔴 第二条只能用 doReturn(null) 而不是 doNothing()：release 有返回值（Tag），
        //    Mockito 对非 void 方法调 doNothing() 直接抛「Only void methods can doNothing()!」（实测）。
        doThrow(new IllegalStateException("Thread 't1:900' not found or already released"))
                .doReturn(null)
                .when(checkpointSaver).release(any(RunnableConfig.class));

        defService.forceDeleteDef(503L);

        // 两条都尝试过（不是遇到 ISE 就整批放弃），且业务行照删
        verify(checkpointSaver, times(2)).release(any(RunnableConfig.class));
        verify(nodeExecutionMapper).deleteBatch(any(), any());
        verify(executionMapper).deleteBatch(any(), any());
        verify(defMapper).deleteById(503L);
    }

    // ==================== 与常规 deleteDef 的分工 ====================

    @Test
    void forceDeleteDef_acceptsPublishedWherePlainDeleteRefuses() throws Exception {
        // 🔴 对照档从 DISABLED 换成 PUBLISHED（迭代八）：常规 deleteDef 现在也收 DISABLED 了，
        //    两档唯一的差别只剩状态闸门那一道 —— 常规档对 PUBLISHED 抛 WORKFLOW_DEF_NOT_DELETABLE，
        //    强删档必须放行，否则「正在被触发入口选用、业务上却已废弃」的行只能靠裸 SQL 清，
        //    那正是 D3 要消灭的东西。
        AgentWorkflowDefDO published = def(416L, "wf_plan_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectById(416L)).thenReturn(published);

        // 常规档：状态闸门是「零 IO」的那一道，排在取全表快照之前 ——
        // 故这里刻意不 stub selectListForAudit（stub 了会被 STRICT_STUBS 判成 UnnecessaryStubbing）
        ServiceException ex = assertThrows(ServiceException.class, () -> defService.deleteDef(416L));
        assertEquals(WORKFLOW_DEF_NOT_DELETABLE.getCode(), ex.getCode(), "常规档拒删已发布");
        verify(defMapper, never()).selectListForAudit();
        verify(defMapper, never()).deleteById(any());

        // 强删档：跳过状态闸门，往下才要过 pin 闸门与在途闸门
        when(defMapper.selectListForAudit()).thenReturn(List.of(published));
        when(executionMapper.selectListByDefId(416L)).thenReturn(List.of());

        defService.forceDeleteDef(416L);

        verify(defMapper).deleteById(416L);
    }

    @Test
    void forceDeleteDef_neverQueriesByCode_soOtherVersionsKeepTheirHistory() throws Exception {
        // 🔴 级联取数只按**单个 defId**，不按 code 展开全部版本：
        //    按 code 展开会把同 code 别的版本的执行史一并抹掉（那些版本的留痕与本次删除无关）
        AgentWorkflowDefDO target = def(416L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        AgentWorkflowDefDO sibling = def(417L, "wf_x", 2, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectById(416L)).thenReturn(target);
        when(defMapper.selectListForAudit()).thenReturn(List.of(target, sibling));
        when(dslParser.parse(anyString())).thenReturn(new WorkflowDefDTO());
        when(executionMapper.selectListByDefId(416L)).thenReturn(List.of());

        defService.forceDeleteDef(416L);

        verify(executionMapper).selectListByDefId(416L);
        verify(executionMapper, never()).selectListByDefId(417L);
        verify(defMapper, never()).deleteById(417L);
    }

}
