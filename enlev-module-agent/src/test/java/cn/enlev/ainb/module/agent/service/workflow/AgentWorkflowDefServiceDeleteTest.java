package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_DELETE_HAS_UNFINISHED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_DELETE_PINNED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_DELETABLE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 常规删除档（{@link AgentWorkflowDefServiceImpl#deleteDef} /
 * {@link AgentWorkflowDefServiceImpl#deleteDefList}）单测（迭代八）
 *
 * <p>与 {@code AgentWorkflowDefServiceForceDeleteTest}（管理员档）互补，两者共用同一份 Service 内核
 * {@code doDeleteDefs}，<b>唯一</b>差别是状态闸门那一道。故本类<b>不</b>重复覆盖 pin 闸门与在途闸门的
 * 全部边界（fail-close 的两处、release 失败的两类、级联覆盖面等）—— 那些用例改一处两档一起变，
 * 抄一份到这里就成了会漂移的第二份判据。本类只测<b>常规档独有</b>的东西：
 *
 * <ol>
 *   <li><b>状态闸门本身</b>：草稿与已停用放行、已发布拒删，且拒删发生在任何一次查库之前。</li>
 *   <li><b>已停用行的级联</b>：这是本次新增的行为（改造前 DISABLED 直接被状态闸门拒掉，走不到级联），
 *       必须钉住顺序 release → node → execution → def。</li>
 *   <li><b>批量档</b>：整批只捞一次全表快照、按调用方 id 顺序走、去重、数量对不上抛不存在、
 *       批内互相 pin 不算引用、以及最要紧的一条 —— <b>全成或全不成</b>。</li>
 * </ol>
 *
 * <p>🔴 「前端看到的可删」与「后端接受不接受删除」是否同结论、{@code getDeletable()} 的真值表、
 * {@code deleteDeniedReason} 当且仅当不可删时为 null、以及派生 getter 有没有真被序列化出去 ——
 * 这四条在 {@code AgentWorkflowDefEditableTest} 里，本类<b>不再抄一份</b>。
 *
 * <p>⚠️ STRICT_STUBS 生效：<b>多桩一个不会被调用的方法就判失败</b>。这在下面几处是刻意为之的断言手段
 * （例如状态闸门被拒时故意不桩 {@code selectListForAudit}，桩了就会红），改动时别顺手「补齐」桩。
 *
 * @author 走进新时代
 */
class AgentWorkflowDefServiceDeleteTest extends BaseMockitoUnitTest {

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

    // ==================== 造数工具（与 ForceDeleteTest 同款，各自私有一份，不抽公共夹具） ====================

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
    void deleteDef_notExists_throws() throws Exception {
        when(defMapper.selectById(404L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.deleteDef(404L));

        assertEquals(WORKFLOW_DEF_NOT_EXISTS.getCode(), ex.getCode());
        verifyNothingTouched();
    }

    // ==================== 闸门一（常规档独有）：状态 ====================

    @Test
    void deleteDef_draft_deletes() throws Exception {
        AgentWorkflowDefDO target = def(701L, "wf_child", 1, WorkflowDefStatusEnum.DRAFT.getStatus());
        when(defMapper.selectById(701L)).thenReturn(target);
        when(defMapper.selectListForAudit()).thenReturn(List.of(target));
        when(executionMapper.selectListByDefId(701L)).thenReturn(List.of());

        defService.deleteDef(701L);

        verify(defMapper).deleteById(701L);
        // 草稿从没被执行入口选中过，名下必无执行史 ⟹ 一次 release 都不该发生
        verifyNoRelease();
    }

    /**
     * 🔴 已停用行删得掉，<b>而且级联</b> —— 这是本次改动新增的行为，也是本类最该盯的一条
     *
     * <p>改造前 DISABLED 直接被状态闸门拒掉，压根走不到级联；现在放行了，而 DISABLED 意味着
     * <b>曾发布过</b>，名下很可能有执行史与节点 trace。只软删 def 会留下一堆指向已删定义的孤儿行 ——
     * 执行历史抽屉里那些记录还在，点进去却查不到是哪个流程跑的，那正是管理员强删档当初要消灭的东西。
     *
     * <p>顺序不可换的理由不在这里重述（见 {@code WORKFLOW_DEF_DELETE_CHECKPOINT_FAILED} 那一节），
     * 本用例只负责把它钉在<b>常规档</b>上：管理员档测过顺序不等于常规档也对，
     * 两档虽然共用内核，但「常规档也走级联」这件事本身是新的。
     */
    @Test
    void deleteDef_disabled_deletesAndCascadesInOrder() throws Exception {
        AgentWorkflowDefDO target = def(702L, "wf_x", 2, WorkflowDefStatusEnum.DISABLED.getStatus());
        when(defMapper.selectById(702L)).thenReturn(target);
        when(defMapper.selectListForAudit()).thenReturn(List.of(target));
        when(executionMapper.selectListByDefId(702L)).thenReturn(List.of(
                execution(900L, WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), "t1:900"),
                execution(901L, WorkflowExecutionStatusEnum.FAILED.getStatus(), "t1:901")));

        defService.deleteDef(702L);

        InOrder order = inOrder(checkpointSaver, nodeExecutionMapper, executionMapper, defMapper);
        order.verify(checkpointSaver, times(2)).release(any(RunnableConfig.class));
        order.verify(nodeExecutionMapper).deleteBatch(any(), any());
        order.verify(executionMapper).deleteBatch(any(), any());
        order.verify(defMapper).deleteById(702L);
    }

    /**
     * 已发布拒删，且<b>拒在任何一次查库之前</b>
     *
     * <p>🔴 后半条不是性能洁癖，是可断言的事实：状态闸门只读入参那一行的 {@code status}，
     * 而 pin 闸门要 {@code selectListForAudit()} 把<b>全部活定义连 DSL 正文</b>捞回来。
     * 整批都是已发布时（前端复选框挡不住的接口直调）先跑状态闸门就省掉那次最贵的查询。
     * 故这里<b>刻意不桩</b> {@code selectListForAudit} —— 桩了 STRICT_STUBS 会判 UnnecessaryStubbing，
     * 顺序一改这条用例当场红。
     */
    @Test
    void deleteDef_published_refusesBeforeAnyQuery() throws Exception {
        AgentWorkflowDefDO published = def(703L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectById(703L)).thenReturn(published);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.deleteDef(703L));

        assertEquals(WORKFLOW_DEF_NOT_DELETABLE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains(WorkflowDefStatusEnum.PUBLISHED.getStatus()),
                "文案要点名当前状态，用户才知道该先停用哪一行，原文：" + ex.getMessage());
        verify(defMapper, never()).selectListForAudit();
        verifyNothingTouched();
    }

    // ==================== 闸门二：sub_workflow pin（只测常规档独有的那一面） ====================

    /**
     * 🔴 <b>草稿也能被 pin 住</b> —— 这条用例存在的理由是把一句流传很广的假话钉死
     *
     * <p>{@code AgentWorkflowDefService#deleteDef} 的旧 javadoc 写过「草稿从未被执行入口选中，
     * 故必无执行历史与子流程引用」。前半句真，后半句<b>假</b>：pin 扫描
     * （{@code findSubWorkflowReferencers}）压根不看 target 的状态，只看引用方 DSL 里
     * {@code config.workflowId} + {@code config.version} 指没指到这一行。
     * 草稿被 version pin 住之后运行期确实解析不到（{@code getSubWorkflowDef} 对 DRAFT 抛
     * {@code WORKFLOW_SUB_WORKFLOW_NOT_FOUND}），但那是「pin 本来就跑不通」，
     * 与「删掉它会不会让别人的 DSL 指向空气」是两件事。
     *
     * <p>也正因为这条成立，<b>草稿没有快速通道</b>：不给它跳过 pin 扫描，
     * 否则 fail-close 那几条（DSL 今天解析不了、明天修好照样去查那个 code）就全丢了。
     */
    @Test
    void deleteDef_draftPinnedByVersion_refusesAndNamesReferencer() throws Exception {
        AgentWorkflowDefDO target = def(704L, "wf_child", 3, WorkflowDefStatusEnum.DRAFT.getStatus());
        AgentWorkflowDefDO referencer = def(600L, "wf_parent", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectById(704L)).thenReturn(target);
        when(defMapper.selectListForAudit()).thenReturn(List.of(target, referencer));
        // 只有引用方的 DSL 会被解析（批内成员被跳过），故只桩它，避免 STRICT_STUBS 判多余
        when(dslParser.parse(referencer.getDsl())).thenReturn(subWorkflowDsl("wf_child", 3));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.deleteDef(704L));

        assertEquals(WORKFLOW_DEF_DELETE_PINNED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("#600 wf_parent@1"), "文案要点名引用方，原文：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("call_child"), "文案要点名是哪个节点，原文：" + ex.getMessage());
        verifyNothingTouched();
    }

    // ==================== 闸门三：未终态执行（常规档新可达） ====================

    /**
     * 已停用行名下有未终态执行 ⟹ 拒删
     *
     * <p>🔴 这道闸门对常规档是<b>新可达</b>的：改造前 DISABLED 被状态闸门挡在门外，
     * 永远走不到这里。而「停用了但还有执行停在 INTERRUPTED」是真实存在的现场
     * （停用只挡新的触发，不打断已在途的那批）。
     */
    @Test
    void deleteDef_disabledHasUnfinishedExecution_refuses() throws Exception {
        AgentWorkflowDefDO target = def(705L, "wf_x", 1, WorkflowDefStatusEnum.DISABLED.getStatus());
        when(defMapper.selectById(705L)).thenReturn(target);
        when(defMapper.selectListForAudit()).thenReturn(List.of(target));
        when(executionMapper.selectListByDefId(705L)).thenReturn(List.of(
                execution(910L, WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), "t1:910"),
                execution(911L, WorkflowExecutionStatusEnum.INTERRUPTED.getStatus(), "t1:911")));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.deleteDef(705L));

        assertEquals(WORKFLOW_DEF_DELETE_HAS_UNFINISHED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("#911=INTERRUPTED"), "要指名道姓哪一条没完，原文：" + ex.getMessage());
        assertTrue(!ex.getMessage().contains("#910"), "已终态的不该被算进来，原文：" + ex.getMessage());
        verifyNothingTouched();
    }

    // ==================== 批量档：正常路径 ====================

    @Test
    void deleteDefList_happyPath_queriesAuditOnceAndFollowsCallerOrder() throws Exception {
        AgentWorkflowDefDO a = def(1L, "wf_a", 1, WorkflowDefStatusEnum.DRAFT.getStatus());
        AgentWorkflowDefDO b = def(2L, "wf_b", 1, WorkflowDefStatusEnum.DISABLED.getStatus());
        AgentWorkflowDefDO c = def(3L, "wf_c", 1, WorkflowDefStatusEnum.DISABLED.getStatus());
        // 🔴 刻意让 IN 的返回顺序与调用方给的顺序不同：实现若直接按返回顺序走，
        //    「点名哪一行不合规」就依赖数据库的返回顺序，同样的数据这次报 A、下次报 B，无从复现
        when(defMapper.selectByIds(any())).thenReturn(List.of(c, a, b));
        when(defMapper.selectListForAudit()).thenReturn(List.of(a, b, c));
        when(executionMapper.selectListByDefId(1L)).thenReturn(List.of());
        when(executionMapper.selectListByDefId(2L)).thenReturn(List.of(
                execution(920L, WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), "t1:920")));
        when(executionMapper.selectListByDefId(3L)).thenReturn(List.of());

        defService.deleteDefList(List.of(1L, 2L, 3L));

        // 🔴 整批只捞一次：pin 扫描要逐行读 DSL，「同 code 还有没有别的活 PUBLISHED 行」也要在这份里数 ——
        //    分两次查会读到两个时刻的库，批内前后两行各拿一份不一致的快照
        verify(defMapper, times(1)).selectListForAudit();
        InOrder order = inOrder(defMapper);
        order.verify(defMapper).deleteById(1L);
        order.verify(defMapper).deleteById(2L);
        order.verify(defMapper).deleteById(3L);
        verify(checkpointSaver, times(1)).release(any(RunnableConfig.class));
        // 三行全在批内 ⟹ pin 扫描一行都不必解析（跳过的是「同批一起删的」，不是「自己」）
        verify(dslParser, never()).parse(anyString());
    }

    @Test
    void deleteDefList_emptyOrNull_touchesNothing() {
        defService.deleteDefList(List.of());
        defService.deleteDefList(null);

        verifyNoInteractions(defMapper, executionMapper, nodeExecutionMapper, dslParser, checkpointSaver);
    }

    @Test
    void deleteDefList_idNotFound_throwsNotExistsInsteadOfSilentlyDeletingFewer() throws Exception {
        when(defMapper.selectByIds(any())).thenReturn(List.of(def(1L, "wf_a", 1,
                WorkflowDefStatusEnum.DRAFT.getStatus())));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> defService.deleteDefList(List.of(1L, 2L)));

        assertEquals(WORKFLOW_DEF_NOT_EXISTS.getCode(), ex.getCode());
        // 🔴 不静默少删：静默成功会让用户以为勾中的都删了，刷新后仍有残留，看起来像「删不掉」
        verifyNothingTouched();
        verify(defMapper, never()).selectListForAudit();
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteDefList_duplicateIds_dedupedBeforeCounting() throws Exception {
        AgentWorkflowDefDO a = def(1L, "wf_a", 1, WorkflowDefStatusEnum.DRAFT.getStatus());
        AgentWorkflowDefDO b = def(2L, "wf_b", 1, WorkflowDefStatusEnum.DISABLED.getStatus());
        when(defMapper.selectByIds(any())).thenReturn(List.of(a, b));
        when(defMapper.selectListForAudit()).thenReturn(List.of(a, b));
        when(executionMapper.selectListByDefId(1L)).thenReturn(List.of());
        when(executionMapper.selectListByDefId(2L)).thenReturn(List.of());

        defService.deleteDefList(List.of(1L, 1L, 2L));

        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(defMapper).selectByIds(captor.capture());
        assertEquals(List.of(1L, 2L), new ArrayList<>(captor.getValue()),
                "先去重再拿去查库：前端复选框不会给重复 id，但接口是公开的，"
                        + "拿原始 ids.size() 去比会把「同一个 id 勾两次」误判成「有 id 查不到」");
        // 去重也保证同一行不会被级联两遍（第二遍 deleteBatch 影响 0 行、但 release 会撞上「已释放」）
        verify(defMapper, times(1)).deleteById(1L);
        verify(defMapper, times(1)).deleteById(2L);
    }

    // ==================== 🔴 批量档独有：批内成员不算引用方 ====================

    @Test
    void deleteDefList_referencerInsideBatch_bothDeleted() throws Exception {
        // A 的 DSL 按 version pin 住 B，而 A、B 同批一起删 ⟹ 那条 pin 删完就没了，不会悬空
        AgentWorkflowDefDO parent = def(10L, "wf_parent", 1, WorkflowDefStatusEnum.DISABLED.getStatus());
        AgentWorkflowDefDO child = def(11L, "wf_child", 2, WorkflowDefStatusEnum.DRAFT.getStatus());
        when(defMapper.selectByIds(any())).thenReturn(List.of(parent, child));
        when(defMapper.selectListForAudit()).thenReturn(List.of(parent, child));
        when(executionMapper.selectListByDefId(10L)).thenReturn(List.of());
        when(executionMapper.selectListByDefId(11L)).thenReturn(List.of());

        defService.deleteDefList(List.of(10L, 11L));

        verify(defMapper).deleteById(10L);
        verify(defMapper).deleteById(11L);
        // 全表都在批内 ⟹ 一次 DSL 解析都不必付（这条顺带钉住「跳过的是同批成员，不只是自己」）
        verify(dslParser, never()).parse(anyString());
    }

    /**
     * 上一条的对照组：引用方<b>不在</b>批里就照样拦
     *
     * <p>🔴 没有这条，上一条可以被一个错实现满足 —— 把 pin 扫描整个跳过（或把 {@code aliveDefs}
     * 传成空列表）同样能让批内互删通过。排除的范围必须正好是「本批的 id 集合」，不多不少。
     */
    @Test
    void deleteDefList_referencerOutsideBatch_stillBlocks() throws Exception {
        AgentWorkflowDefDO child = def(11L, "wf_child", 2, WorkflowDefStatusEnum.DRAFT.getStatus());
        AgentWorkflowDefDO outsider = def(10L, "wf_parent", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectByIds(any())).thenReturn(List.of(child));
        when(defMapper.selectListForAudit()).thenReturn(List.of(child, outsider));
        when(dslParser.parse(outsider.getDsl())).thenReturn(subWorkflowDsl("wf_child", 2));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> defService.deleteDefList(List.of(11L)));

        assertEquals(WORKFLOW_DEF_DELETE_PINNED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("#10 wf_parent@1"), "原文：" + ex.getMessage());
        verifyNothingTouched();
    }

    // ==================== 🔴 全成或全不成 ====================

    /**
     * 第 3 行的闸门失败 ⟹ 前两行<b>一行未动</b>，尤其是一个 checkpoint 都没释放
     *
     * <p>🔴 这条用例钉的不是整洁，是一件回滚不了的事：{@code checkpointSaver.release} 发的是真实的
     * {@code UPDATE GRAPH_THREAD SET is_released = TRUE}，<b>{@code @Transactional} 回滚不了它</b>
     * （那是 SAA 的表，且释放本身就是要让它对 SAA 不可见）。「边删边校验」的实现会在这里留下
     * {@code t1:901}/{@code t1:902} 已 released、而两条执行行还在的中间态 ——
     * 执行历史抽屉里它们照常显示，点 resume 却读不出快照，而<b>没有任何接口能解释这件事</b>。
     *
     * <p>故内核是两阶段：阶段一把所有行的三道闸门跑完，阶段二才逐行级联。
     * 被拦时数据一行未动，那句错误提示就是全部事实。
     *
     * <p>⚠️ 顺带钉住阶段一的短路：第 3 行在 pin 闸门就停了，不该再去查它的执行史
     * （故 {@code selectListByDefId(3L)} 刻意<b>不桩</b>，桩了会被判 UnnecessaryStubbing）。
     */
    @Test
    void deleteDefList_thirdRowBlocked_firstTwoUntouchedAndNothingReleased() throws Exception {
        AgentWorkflowDefDO a = def(1L, "wf_a", 1, WorkflowDefStatusEnum.DISABLED.getStatus());
        AgentWorkflowDefDO b = def(2L, "wf_b", 1, WorkflowDefStatusEnum.DISABLED.getStatus());
        AgentWorkflowDefDO c = def(3L, "wf_child", 1, WorkflowDefStatusEnum.DRAFT.getStatus());
        AgentWorkflowDefDO outsider = def(900L, "wf_parent", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectByIds(any())).thenReturn(List.of(a, b, c));
        when(defMapper.selectListForAudit()).thenReturn(List.of(a, b, c, outsider));
        // 🔴 前两行都带执行史：这样「边删边校验」的实现必然会在撞上第 3 行之前就 release 这两个 thread
        when(executionMapper.selectListByDefId(1L)).thenReturn(List.of(
                execution(901L, WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), "t1:901")));
        when(executionMapper.selectListByDefId(2L)).thenReturn(List.of(
                execution(902L, WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), "t1:902")));
        when(dslParser.parse(outsider.getDsl())).thenReturn(subWorkflowDsl("wf_child", 1));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> defService.deleteDefList(List.of(1L, 2L, 3L)));

        assertEquals(WORKFLOW_DEF_DELETE_PINNED.getCode(), ex.getCode());
        verifyNoRelease();
        verifyNothingTouched();
        verify(executionMapper, never()).selectListByDefId(3L);
    }

    /**
     * 同 code 的 v1+v2 一起勾删、而 v1 是<b>已发布</b>的 ⟹ 整批被状态闸门拒掉，v2 也删不成
     *
     * <p>🔴 这条同时是一根<b>引线</b>，别把它当普通边界删掉。内核里排除批内成员的比较写在
     * <b>两处</b>：pin 扫描循环里的跳过，以及 {@code codeFallbackWouldDangle} 的存活者判据
     * （「同 code 还有没有别的活 PUBLISHED 行」）。第二处要求 target 自己是 PUBLISHED 才成立，
     * 而常规档的状态闸门<b>恰好</b>把 PUBLISHED 全挡在门外 —— 也就是说，
     * 第二处在今天的常规档里<b>走不到</b>，是「同 code 两个已停用版本一起删 + 外部未 pin 版本的引用」
     * 这类场景的<b>潜在</b>保护，靠的正是状态闸门比它更严。
     *
     * <p>哪天有人把 {@code isDeletable} 放宽到收 PUBLISHED，本用例会当场红；
     * 那时第二处比较就从潜在变成承重，必须补上真正走得到它的用例（并同步
     * {@code AgentWorkflowDefServiceForceDeleteTest} —— 强删档传的是单元素集合，两处都退化成「等于 target 自己」）。
     */
    @Test
    void deleteDefList_publishedRowInBatch_statusGateRejectsWholeBatch() throws Exception {
        AgentWorkflowDefDO v1 = def(21L, "wf_child", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        AgentWorkflowDefDO v2 = def(22L, "wf_child", 2, WorkflowDefStatusEnum.DISABLED.getStatus());
        when(defMapper.selectByIds(any())).thenReturn(List.of(v1, v2));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> defService.deleteDefList(List.of(21L, 22L)));

        assertEquals(WORKFLOW_DEF_NOT_DELETABLE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains(WorkflowDefStatusEnum.PUBLISHED.getStatus()),
                "文案要点名是哪一行的状态不合规，原文：" + ex.getMessage());
        // 状态闸门在阶段一就把整批拦下 ⟹ 连全表快照都不必捞，更不必动任何数据
        verify(defMapper, never()).selectListForAudit();
        verifyNothingTouched();
    }

    /**
     * 同 code 的 v1+v2 都是已停用、一起勾删，而外部有人按 version pin 住 v1 ⟹ 整批拒绝，v2 也保住
     *
     * <p>🔴 与上一条的区别：那条是状态闸门拦的，这条是 pin 闸门拦的，且<b>走得到</b>批内排除逻辑
     * （v2 是批内成员，不算 v1 的存活者；真正拦住 v1 的是那个不在批里的引用方）。
     * 「一条不合规就整批拒绝」在 pin 这一维上的样子就是这条。
     */
    @Test
    void deleteDefList_oneRowPinnedByOutsider_wholeBatchRejected() throws Exception {
        AgentWorkflowDefDO v1 = def(21L, "wf_child", 1, WorkflowDefStatusEnum.DISABLED.getStatus());
        AgentWorkflowDefDO v2 = def(22L, "wf_child", 2, WorkflowDefStatusEnum.DISABLED.getStatus());
        AgentWorkflowDefDO outsider = def(900L, "wf_parent", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectByIds(any())).thenReturn(List.of(v1, v2));
        when(defMapper.selectListForAudit()).thenReturn(List.of(v1, v2, outsider));
        when(dslParser.parse(outsider.getDsl())).thenReturn(subWorkflowDsl("wf_child", 1));
        // v1 在 pin 闸门就被拦，走不到在途闸门 ⟹ 两行的执行史都不必桩（桩了判 UnnecessaryStubbing）

        ServiceException ex = assertThrows(ServiceException.class,
                () -> defService.deleteDefList(List.of(21L, 22L)));

        assertEquals(WORKFLOW_DEF_DELETE_PINNED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("#21 wf_child@1"), "要点名被 pin 的是哪一行，原文：" + ex.getMessage());
        verify(defMapper, never()).deleteById(22L);
        verifyNothingTouched();
    }

}
