package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.def.AgentDefMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerExtractor;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.WorkflowChangeManifestBuilder;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_VALIDATE_FAILED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S4b 发布闸门（{@code AgentWorkflowDefServiceImpl#assertPublishable}）单测
 *
 * <p>纯 mock，0 token、不碰真 DB。发布状态机（幂等/不存在/DISABLED/chat 绑定）在
 * {@code AgentWorkflowDefServicePublishDefTest}，那边一律让闸门放行；本类反过来 —— 只测闸门自己的判定。
 *
 * <p>要守住的十一件事：
 * <ol>
 *   <li>① 基础层有 error → 拒发布，且状态<b>没翻</b>（updateById 一次都不调）</li>
 *   <li>② 🔴 异常文案<b>逐条</b>列出 error，不是「有 N 项不通过」—— 这是「点了发布、什么都没发生」时
 *       唯一能到达用户的文字（台账 #91：前端拦截器只把 msg 塞进一个会自动消失的通知）</li>
 *   <li>③ 🔴 <b>只有 warning 不拦</b>：判据是 {@code isPassed()} == {@code errors.isEmpty()}。
 *       谁把条件「改进」成 {@code errors.isEmpty() && warnings.isEmpty()}，存量里那 20+ 行带 warning 的
 *       流程会一夜之间全部发不出去，而 warning 的定义就是「静默改写、不阻断」</li>
 *   <li>④ 深层（编译器干跑）的 error 同样拦 —— 闸门吃的是三层合并后的结果，不只基础层</li>
 *   <li>⑤ 🔴 干跑清单那一层<b>默认带上</b>（S0 裁定⑥）：清单里折出来的 REJECTED 也能拦下发布</li>
 *   <li>⑥ 两条发布路径<b>都</b>过闸门（{@code publishDef(code)} / {@code publishDefById(id)}）——
 *       漏一条就是一个绕过口子</li>
 *   <li>⑦ 🔴 幂等短路在闸门<b>之前</b>：已 PUBLISHED 的行压根不体检。否则 V 系列后续加严时，
 *       一次重试会把历史上合法发布过、且从未改动的行报成不通过</li>
 *   <li>⑧ 清单建不出来（infra/bpm 不通）<b>不</b>拦发布：探针失败不是违规（§6.1），静态层照常判</li>
 *   <li>⑨ 🔴 {@code createDef} / {@code updateDef} <b>不</b>过闸门：草稿必须允许半成品，
 *       否则「先起流程、后建表」这个正常编辑顺序会被堵死</li>
 *   <li>⑩ 🔴 执行史按 <b>code</b> 展开后交给折叠（台账 #81）：回滚出的新版本自己一次都没跑过，
 *       只问它自己的 id 就会把「已落地的撞车」照样拦死，那条流程于是永远失去回滚能力</li>
 *   <li>⑪ 🔴 取不到执行史时传 <b>0</b>（= 一档都不降）。降档是放行方向，
 *       没证据就落在 fail-close 那一边，绝不「猜一个大概跑过」</li>
 * </ol>
 *
 * @author 走进新时代
 */
class AgentWorkflowDefServicePublishGateTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentWorkflowDefServiceImpl defService;

    @Mock
    private AgentWorkflowDefMapper defMapper;
    @Mock
    private AgentDefMapper agentDefMapper;
    @Mock
    private WorkflowTriggerExtractor triggerExtractor;

    /** 闸门吃的三层校验 + 清单生成器 */
    @Mock
    private DslParser dslParser;
    @Mock
    private WorkflowDslValidator dslValidator;
    @Mock
    private DslToStateGraphCompiler dslCompiler;
    @Mock
    private WorkflowChangeManifestBuilder manifestBuilder;

    /** 执行史取数口（台账 #81）：闸门按 code 展开全部版本数「真跑过几次」 */
    @Mock
    private AgentWorkflowExecutionMapper executionMapper;

    /** 内容不重要（parse 被 mock），但必须非空 —— resolveManifestSource 用 isNotBlank 判「给了 dsl」 */
    private static final String DSL = "{\"id\":\"wf_gate\",\"nodes\":[],\"edges\":[]}";

    private static AgentWorkflowDefDO draft(Long id, String code) {
        AgentWorkflowDefDO d = new AgentWorkflowDefDO();
        d.setId(id);
        d.setCode(code);
        d.setVersion(1);
        d.setStatus(WorkflowDefStatusEnum.DRAFT.getStatus());
        d.setDsl(DSL);
        return d;
    }

    /**
     * 只 stub 到基础层。
     *
     * <p>🔴 刻意<b>不</b>顺手把深层也 stub 掉：{@code validateDsl} 在基础层不通过时会跳过深层干跑，
     * 那些用例里 {@code dryRunValidate} 一次都不会被调用，而 {@code MockitoExtension} 默认 STRICT_STUBS
     * 会把它判成 UnnecessaryStubbing。要用深层的用例自己再调 {@link #stubDeep}。
     */
    private void stubBasic(AgentValidateResult basic) {
        when(dslParser.parse(anyString())).thenReturn(new WorkflowDefDTO());
        when(dslValidator.validate(any(WorkflowDefDTO.class))).thenReturn(basic);
    }

    /** 深层（编译器干跑）只有在基础层通过时才会被调用 */
    private void stubDeep(AgentValidateResult deep) {
        when(dslCompiler.dryRunValidate(any(WorkflowDefDTO.class), any())).thenReturn(deep);
    }

    /** 三层全干净放行（清单生成器不 stub：mock 默认返回 null，正好等价于「清单不可用」，第三层自己早退） */
    private void stubGatePasses() {
        stubBasic(new AgentValidateResult());
        stubDeep(new AgentValidateResult());
    }

    // ==================== ①② 基础层 error → 拒发布，且文案逐条列出 ====================

    @Test
    void publishDef_basicLayerError_rejectedAndListsEveryError() {
        when(defMapper.selectLatestByCode("wf_gate")).thenReturn(draft(100L, "wf_gate"));
        AgentValidateResult basic = new AgentValidateResult();
        basic.addError("表名 order_info 不合法，应改为 biz_order_info");
        basic.addError("节点 n3 的 outputVar 未声明：请在节点配置里补上 outputVar");
        stubBasic(basic);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDef("wf_gate"));

        assertEquals(WORKFLOW_DEF_VALIDATE_FAILED.getCode(), ex.getCode());
        // 🔴 逐条带出，不折叠成条数：两条 error 的「怎么改」都必须在文案里
        assertTrue(ex.getMessage().contains("应改为 biz_order_info"), "第一条 error 的修法要能读到：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("请在节点配置里补上 outputVar"), "第二条 error 的修法要能读到");
        assertTrue(ex.getMessage().contains("；"), "两条之间用「；」分隔（落到通知 title 里换行不渲染）");
        // 🔴 状态没翻：拒发布必须是「什么都没发生」，不能先翻再回滚
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    // ==================== ③ 只有 warning：照发 ====================

    @Test
    void publishDef_warningsOnly_stillPublishes() {
        when(defMapper.selectLatestByCode("wf_gate")).thenReturn(draft(100L, "wf_gate"));
        AgentValidateResult basic = new AgentValidateResult();
        basic.addWarning("widget 大小写会被静默改写：Input → input");
        basic.addWarning("tool.args 的嵌套占位符不会被解析");
        stubBasic(basic);
        stubDeep(new AgentValidateResult());

        Long id = defService.publishDef("wf_gate");

        assertEquals(100L, id, "🔴 warning 的定义就是「不阻断、但要在 Plan 卡片上说」，拦下来等于把 20+ 行存量一夜判死");
        verify(defMapper).updateById(any(AgentWorkflowDefDO.class));
    }

    // ==================== ④ 深层 error 同样拦 ====================

    @Test
    void publishDef_deepLayerError_rejected() {
        when(defMapper.selectLatestByCode("wf_gate")).thenReturn(draft(100L, "wf_gate"));
        AgentValidateResult deep = new AgentValidateResult();
        deep.addError("agent_loop 节点缺 goal：编译不出可执行的图");
        stubBasic(new AgentValidateResult());
        stubDeep(deep);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDef("wf_gate"));

        assertEquals(WORKFLOW_DEF_VALIDATE_FAILED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("缺 goal"), "闸门吃的是三层合并后的结果，不只基础层");
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    // ==================== ⑤ 干跑清单那一层默认带上（S0 裁定⑥） ====================

    @Test
    void publishDef_dryRunRejectionFolded_rejected() {
        when(defMapper.selectLatestByCode("wf_gate")).thenReturn(draft(100L, "wf_gate"));
        stubGatePasses();
        // 清单建得出来，且折叠时判出一条 REJECTED（V4/V5/V12 的入口）
        when(manifestBuilder.build(any(WorkflowDefDTO.class), any(), any())).thenReturn(new ChangeManifest());
        doAnswer(inv -> {
            inv.getArgument(1, AgentValidateResult.class)
                    .addError("表 biz_order 已存在且列不一致：save_draft 会在运行期失败");
            return null;
        }).when(dslValidator).foldDryRunRejections(any(ChangeManifest.class), any(AgentValidateResult.class), anyLong());

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDef("wf_gate"));

        assertEquals(WORKFLOW_DEF_VALIDATE_FAILED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("biz_order"), ex.getMessage());
        // 🔴 干跑真的跑了（不是「清单为 null 所以第三层静默缺席」蒙对的）
        verify(manifestBuilder).build(any(WorkflowDefDTO.class), any(), any());
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    // ==================== ⑩ 执行史按 code 展开后交给折叠（台账 #81） ====================

    /**
     * 🔴 闸门必须把「本 code 名下<b>全部版本</b>真跑过几次」传进折叠，而不是这一行自己的次数。
     *
     * <p>这里只钉「闸门有没有把数交出去」；「交出去之后怎么降档」是
     * {@code WorkflowDslValidatorTest} 的事（{@code dslValidator} 在本类是 mock，降档逻辑不在射程内）。
     *
     * <p>场景就是 #81 的现场：id=90 是跑过 3 次的 v1，id=100 是从它回滚出来的 v2 草稿。
     * 新版本自己一次都没跑过 —— 若闸门只问 id=100，拿到的就是 0，降档不触发，
     * 于是这条流程<b>永远失去回滚能力</b>：回滚出的草稿发不出去，原版又已 PUBLISHED 走幂等短路。
     * 桩返回的 Map 按<b>旧 id</b>(90) 键，故 {@code eq(3L)} 一条就能咬住「少展开这一步」的变异。
     */
    @Test
    void publishDef_passesExecutionCountSummedAcrossTheCode() {
        when(defMapper.selectLatestByCode("wf_gate")).thenReturn(draft(100L, "wf_gate"));
        stubGatePasses();
        when(manifestBuilder.build(any(WorkflowDefDTO.class), any(), any())).thenReturn(new ChangeManifest());
        when(defMapper.selectListByCode("wf_gate")).thenReturn(List.of(draft(100L, "wf_gate"), draft(90L, "wf_gate")));
        when(executionMapper.countByDefIds(anyCollection())).thenReturn(Map.of(90L, 3L));

        assertDoesNotThrow(() -> defService.publishDef("wf_gate"));

        verify(dslValidator).foldDryRunRejections(any(ChangeManifest.class), any(AgentValidateResult.class), eq(3L));
    }

    /**
     * 🔴 取不到执行史时闸门必须传 <b>0</b>（= 一档都不降），而不是「猜一个大概跑过」。
     *
     * <p>降档是<b>放行</b>方向，故拿不到证据就落在 fail-close 那一边。这里 stub 的是
     * {@code selectListByCode} 返回空列表 —— 真实世界里对应「这条 code 只有这一版、且从没执行过」，
     * 也正是存量里那 4 行静态错之外的多数行的处境。
     */
    @Test
    void publishDef_withoutExecutionHistory_passesZero() {
        when(defMapper.selectLatestByCode("wf_gate")).thenReturn(draft(100L, "wf_gate"));
        stubGatePasses();
        when(manifestBuilder.build(any(WorkflowDefDTO.class), any(), any())).thenReturn(new ChangeManifest());

        assertDoesNotThrow(() -> defService.publishDef("wf_gate"));

        verify(dslValidator).foldDryRunRejections(any(ChangeManifest.class), any(AgentValidateResult.class), eq(0L));
    }

    // ==================== ⑥ 两条发布路径都过闸门 ====================

    @Test
    void publishDefById_isAlsoGated() {
        when(defMapper.selectById(100L)).thenReturn(draft(100L, "wf_gate"));
        AgentValidateResult basic = new AgentValidateResult();
        basic.addError("wait 节点未声明唤醒方：无头触发下它会永久挂起");
        stubBasic(basic);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDefById(100L));

        assertEquals(WORKFLOW_DEF_VALIDATE_FAILED.getCode(), ex.getCode(),
                "🔴 漏掉这一条就等于给管理页留了一个绕过闸门的路径");
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    // ==================== ⑦ 幂等短路在闸门之前 ====================

    @Test
    void publishDef_alreadyPublished_neverRunsGate() {
        AgentWorkflowDefDO published = draft(100L, "wf_gate");
        published.setStatus(WorkflowDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectLatestByCode("wf_gate")).thenReturn(published);

        Long id = defService.publishDef("wf_gate");

        assertEquals(100L, id);
        // 🔴 一次都不体检：V 系列加严后，重试不该把「历史上合法发布过、且没改动过」的行报成不通过
        verifyNoInteractions(dslParser, dslValidator, dslCompiler, manifestBuilder);
    }

    // ==================== ⑧ 清单建不出来 ≠ 违规（§6.1） ====================

    @Test
    void publishDef_manifestBuilderThrows_stillPublishes() {
        when(defMapper.selectLatestByCode("wf_gate")).thenReturn(draft(100L, "wf_gate"));
        stubGatePasses();
        when(manifestBuilder.build(any(WorkflowDefDTO.class), any(), any()))
                .thenThrow(new RuntimeException("infra 数据模型接口不通"));

        Long id = assertDoesNotThrow(() -> defService.publishDef("wf_gate"),
                "🔴 探针失败不是违规：infra/bpm 当时不通不该把发布堵死，静态层照常判");

        assertEquals(100L, id);
        verify(defMapper).updateById(any(AgentWorkflowDefDO.class));
    }

    // ==================== ⑨ 落草稿不过闸门 ====================

    @Test
    void createDef_isNotGated() {
        // 🔴 这份 DSL 是「一定过不了体检」的那种；草稿必须照样落得下去，
        //    否则「先起流程、后建表/后建 Agent」这个正常编辑顺序会被堵死（同 validateChatBinding 的理由）
        Long id = assertDoesNotThrow(() -> defService.createDef("wf_gate", "坏草稿", DSL,
                WorkflowDefStatusEnum.DRAFT.getStatus()));

        assertEquals(null, id, "mock 的 insert 不回填 id，这里只关心「没抛」");
        verify(defMapper).insert(any(AgentWorkflowDefDO.class));
        verifyNoInteractions(dslValidator, dslCompiler);
    }

    @Test
    void updateDef_isNotGated() {
        when(defMapper.selectById(100L)).thenReturn(draft(100L, "wf_gate"));
        AgentWorkflowDefSaveReqVO vo = new AgentWorkflowDefSaveReqVO();
        vo.setId(100L);
        vo.setName("改个名");
        vo.setDsl(DSL);

        assertDoesNotThrow(() -> defService.updateDef(vo));

        verify(defMapper).updateById(any(AgentWorkflowDefDO.class));
        verifyNoInteractions(dslValidator, dslCompiler);
    }

}
