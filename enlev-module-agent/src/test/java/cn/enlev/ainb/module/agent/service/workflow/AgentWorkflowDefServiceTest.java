package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefPageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerExtractor;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.WorkflowChangeManifestBuilder;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_DRAFT;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_PUBLISHED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentWorkflowDefServiceImpl} 管理页 CRUD + 发布/停用 单测（增量8 / P4 第一刀）
 *
 * <p>纯 mock（{@link AgentWorkflowDefMapper} + {@link WorkflowTriggerExtractor}），0 token、不碰真 DB。
 * 与 {@code AgentWorkflowDefServicePublishDefTest}（测 {@code publishDef(code)} 引擎入口）互补：本类测
 * 管理页专用的 {@code updateDef/publishDefById/disableDefById/getDefPage}。断言聚焦「状态守卫 +
 * 幂等 + 部分 DO 形态」三件事：
 * <ol>
 *   <li>updateDef：DRAFT 成功（改 name/description/dsl、重跑 triggerExtractor.apply、部分 DO 只带这些列 +
 *       不带 code/version/status）；非 DRAFT 抛 {@code WORKFLOW_DEF_NOT_DRAFT}、不 updateById</li>
 *   <li>publishDefById：DRAFT→PUBLISHED（部分 DO 只带 id+status）；已 PUBLISHED 幂等返回、updateById 一次都不调；
 *       DISABLED 抛 {@code WORKFLOW_DEF_NOT_DRAFT}</li>
 *   <li>disableDefById：PUBLISHED→DISABLED（部分 DO 只带 id+status）；已 DISABLED 幂等、updateById 一次都不调；
 *       DRAFT 抛 {@code WORKFLOW_DEF_NOT_PUBLISHED}</li>
 *   <li>getDefPage：原样委派 mapper.selectPage 并返回其结果</li>
 * </ol>
 *
 * <p>🔴 <b>删除相关的用例不在本类</b>，全部在 {@code AgentWorkflowDefServiceDeleteTest}
 * （常规删除 + 批量删除）与 {@code AgentWorkflowDefServiceForceDeleteTest}（管理员强制删除）。
 * 迭代八起删除不再是「一句 deleteById」：它要过状态 / 引用 / 在途三道闸门并跑一条四步级联，
 * 需要 execution、nodeExecution、checkpointSaver 三个本类用不着的协作者。
 * 留一条弱化版在这里就等于第二份夹具 —— 两份必然漂移，而漂移的表现是「本类绿了、专用类红了」。
 *
 * @author 走进新时代
 */
class AgentWorkflowDefServiceTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentWorkflowDefServiceImpl defService;

    @Mock
    private AgentWorkflowDefMapper defMapper;

    @Mock
    private WorkflowTriggerExtractor triggerExtractor;

    /**
     * S4b 发布闸门（{@code assertPublishable}）的依赖。
     *
     * <p>本类测的是 CRUD 与状态机，只有 {@code publishDefById_draftToPublished_updatesStatusOnly} 会走到闸门，
     * 在那里就地让它放行；闸门自己的判定在 {@code AgentWorkflowDefServicePublishGateTest}。
     */
    @Mock
    private DslParser dslParser;
    @Mock
    private WorkflowDslValidator dslValidator;
    @Mock
    private DslToStateGraphCompiler dslCompiler;
    @Mock
    private WorkflowChangeManifestBuilder manifestBuilder;
    /** 执行史取数口（台账 #81）：闸门会按 code 问一次「跑过几次」，默认空 = 没跑过 = 不降档 */
    @Mock
    private AgentWorkflowExecutionMapper executionMapper;

    private static AgentWorkflowDefDO def(Long id, String code, Integer version, String status) {
        AgentWorkflowDefDO d = new AgentWorkflowDefDO();
        d.setId(id);
        d.setCode(code);
        d.setVersion(version);
        d.setStatus(status);
        d.setDsl("{\"id\":\"" + code + "\"}");
        return d;
    }

    // ==================== updateDef ====================

    @Test
    void updateDef_draft_updatesEditableColumnsOnly() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "wf_x", 1, WorkflowDefStatusEnum.DRAFT.getStatus()));
        AgentWorkflowDefSaveReqVO reqVO = new AgentWorkflowDefSaveReqVO();
        reqVO.setId(100L);
        reqVO.setName("新名字");
        reqVO.setDescription("新描述");
        reqVO.setDsl("{\"id\":\"wf_x\",\"trigger\":{\"type\":\"chat\"}}");

        defService.updateDef(reqVO);

        // DSL 改了触发器 → 必须重跑投影
        verify(triggerExtractor).apply(any(AgentWorkflowDefDO.class));
        ArgumentCaptor<AgentWorkflowDefDO> captor = ArgumentCaptor.forClass(AgentWorkflowDefDO.class);
        verify(defMapper).updateById(captor.capture());
        AgentWorkflowDefDO updated = captor.getValue();
        assertEquals(100L, updated.getId(), "只带 id 定位那一行");
        assertEquals("新名字", updated.getName());
        assertEquals("新描述", updated.getDescription());
        assertEquals("{\"id\":\"wf_x\",\"trigger\":{\"type\":\"chat\"}}", updated.getDsl());
        // 🔴 部分更新：code/version/status 不该被带进 update（null 不进 SET，保持原值）
        assertEquals(null, updated.getCode(), "code 不参与更新");
        assertEquals(null, updated.getVersion(), "version 不参与更新");
        assertEquals(null, updated.getStatus(), "status 不因编辑而变");
    }

    @Test
    void updateDef_notDraft_throws() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "wf_x", 2, WorkflowDefStatusEnum.PUBLISHED.getStatus()));
        AgentWorkflowDefSaveReqVO reqVO = new AgentWorkflowDefSaveReqVO();
        reqVO.setId(100L);
        reqVO.setName("x");
        reqVO.setDsl("{}");

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.updateDef(reqVO));
        assertEquals(WORKFLOW_DEF_NOT_DRAFT.getCode(), ex.getCode(), "已发布版本不可就地改");
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
        verify(triggerExtractor, never()).apply(any());
    }

    // ==================== publishDefById ====================

    @Test
    void publishDefById_draftToPublished_updatesStatusOnly() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "wf_x", 1, WorkflowDefStatusEnum.DRAFT.getStatus()));
        // 🔴 B6 / S16 之后 publishDefById 多了一步「补钉主版本」：该 code 一个主版本都没钉过时，
        //    会把刚发布这版钉上（第二次 updateById，只带 id + primary_flag）。本用例断言的是
        //    **翻状态那一笔**是部分 DO（dsl/version/code 都不带进去），故显式让「已有主版本」成立，
        //    把补钉那一步挡掉 —— 否则 captor 会收到两个值，断言就得靠 getAllValues() 的**下标**去猜
        //    哪一笔是翻状态，顺序一变就静默测错东西。
        //    补钉本身的语义（钉/不钉、与管理页和聊天两条路的不对称）在
        //    AgentWorkflowDefServicePublishDefTest 的「B6」小节里逐条测。
        when(defMapper.selectPrimaryByCode("wf_x")).thenReturn(def(90L, "wf_x", 0,
                WorkflowDefStatusEnum.PUBLISHED.getStatus()));
        // S4b 闸门放行：本用例断言的是「只翻状态、其余列不动」，体检判据在 PublishGateTest 里测。
        // 🔴 就地 stub 而不是 @BeforeEach：本类只有这一条用例走得到闸门，STRICT_STUBS 会把其余判成 UnnecessaryStubbing
        when(dslParser.parse(anyString())).thenReturn(new WorkflowDefDTO());
        when(dslValidator.validate(any(WorkflowDefDTO.class))).thenReturn(new AgentValidateResult());
        when(dslCompiler.dryRunValidate(any(WorkflowDefDTO.class), any())).thenReturn(new AgentValidateResult());

        Long id = defService.publishDefById(100L);

        assertEquals(100L, id);
        ArgumentCaptor<AgentWorkflowDefDO> captor = ArgumentCaptor.forClass(AgentWorkflowDefDO.class);
        verify(defMapper).updateById(captor.capture());
        AgentWorkflowDefDO updated = captor.getValue();
        assertEquals(100L, updated.getId());
        assertEquals(WorkflowDefStatusEnum.PUBLISHED.getStatus(), updated.getStatus());
        assertEquals(null, updated.getDsl(), "只翻状态，dsl 不动");
        assertEquals(null, updated.getVersion(), "只翻状态，version 不动");
        assertEquals(null, updated.getCode(), "只翻状态，code 不动");
        assertEquals(null, updated.getPrimaryFlag(), "已有主版本时这一笔绝不顺手改 primary_flag");
    }

    @Test
    void publishDefById_alreadyPublished_idempotentNoUpdate() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "wf_x", 2, WorkflowDefStatusEnum.PUBLISHED.getStatus()));

        Long id = defService.publishDefById(100L);

        assertEquals(100L, id, "幂等返回原 id");
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    @Test
    void publishDefById_disabled_throwsNotDraft() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "wf_x", 3, WorkflowDefStatusEnum.DISABLED.getStatus()));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDefById(100L));
        assertEquals(WORKFLOW_DEF_NOT_DRAFT.getCode(), ex.getCode(), "停用版不可重发，请出新版本");
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    // ==================== disableDefById ====================

    @Test
    void disableDefById_publishedToDisabled_updatesStatusOnly() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus()));

        defService.disableDefById(100L);

        ArgumentCaptor<AgentWorkflowDefDO> captor = ArgumentCaptor.forClass(AgentWorkflowDefDO.class);
        verify(defMapper).updateById(captor.capture());
        AgentWorkflowDefDO updated = captor.getValue();
        assertEquals(100L, updated.getId());
        assertEquals(WorkflowDefStatusEnum.DISABLED.getStatus(), updated.getStatus());
        assertEquals(null, updated.getDsl(), "只翻状态，dsl 不动");
    }

    @Test
    void disableDefById_alreadyDisabled_idempotentNoUpdate() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "wf_x", 1, WorkflowDefStatusEnum.DISABLED.getStatus()));

        defService.disableDefById(100L);

        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    @Test
    void disableDefById_draft_throwsNotPublished() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "wf_x", 1, WorkflowDefStatusEnum.DRAFT.getStatus()));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.disableDefById(100L));
        assertEquals(WORKFLOW_DEF_NOT_PUBLISHED.getCode(), ex.getCode(), "没发布过谈不上停用");
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    // ==================== getDefPage ====================

    @Test
    void getDefPage_delegatesToMapper() {
        AgentWorkflowDefPageReqVO reqVO = new AgentWorkflowDefPageReqVO();
        reqVO.setCode("wf");
        PageResult<AgentWorkflowDefDO> expected = new PageResult<>(
                List.of(def(100L, "wf_x", 1, WorkflowDefStatusEnum.DRAFT.getStatus())), 1L);
        when(defMapper.selectPage(reqVO)).thenReturn(expected);

        PageResult<AgentWorkflowDefDO> actual = defService.getDefPage(reqVO);

        assertSame(expected, actual, "分页原样返回 mapper 结果，service 不再加工");
        verify(defMapper).selectPage(reqVO);
    }

}
