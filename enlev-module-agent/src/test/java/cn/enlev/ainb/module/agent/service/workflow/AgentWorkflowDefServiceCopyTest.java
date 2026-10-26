package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefCopyReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefCopyRespVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import cn.enlev.ainb.module.agent.enums.WorkflowSourceEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.copy.WorkflowCopyRewriter;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerExtractor;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_CODE_DUPLICATE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AgentWorkflowDefServiceImpl#copyDef} 单测（S5-g）
 *
 * <h3>这一层钉的是「编排」，不是「改写」</h3>
 * 「哪一格算冲突、改成什么、回显怎么写」全归 {@code WorkflowCopyRewriterTest}（那边连 DSL 都是字面量）；
 * 本类只钉 {@code copyDef} 自己那几步的取舍，四条不可变纪律：
 * <ol>
 *   <li><b>落库一律 DRAFT + MANUAL</b>，与源的状态/来源无关；</li>
 *   <li><b>DSL 取源库里那一版</b>，不接受前端传入（{@code AgentWorkflowDefCopyReqVO} 里压根没有 dsl 字段，
 *       这条在 VO 层是结构保证，在 service 层由「传给改写器的是 {@code source.getDsl()}」再钉一次）；</li>
 *   <li><b>查重先于一切写动作</b>，撞名时改写器都不该被叫醒；</li>
 *   <li><b>不跑发布闸门</b> —— 这正是 {@code rewritten[]} 必须存在、且必须原样带回前端的理由。</li>
 * </ol>
 *
 * <h3>🔴 第 4 条为什么值得单独一条用例</h3>
 * {@code insertNewVersion} 只跑 {@code triggerExtractor.apply}，<b>不</b>跑 {@code validateDsl}、
 * <b>不</b>跑 {@code assertPublishable}（S4b 的闸门在<b>发布期</b>，复制在<b>建草稿期</b>）。
 * 后果是：复制可以产出一份<b>必然发不出去</b>的草稿而当场一个错都不报。
 * 这既是设计取舍（把源流程抄出来看/改是合理动作，不该被闸门拦在建草稿这一步），
 * 也就意味着 {@code rewritten[]} 是这一步<b>唯一</b>的用户可见信号 ——
 * 所以「清单被吞掉/被折叠成条数/变成 null」在这里是缺陷，不是风格问题。
 * 把「闸门不跑」写死成断言，是为了让将来有人顺手把 {@code assertPublishable} 加进复制路径时当场红：
 * 那会让所有源流程不合规的存量行<b>复制不出来</b>，而复制恰恰是修它们的入口。
 *
 * @author 走进新时代
 */
class AgentWorkflowDefServiceCopyTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentWorkflowDefServiceImpl defService;

    @Mock
    private AgentWorkflowDefMapper defMapper;

    @Mock
    private WorkflowTriggerExtractor triggerExtractor;

    @Mock
    private WorkflowCopyRewriter copyRewriter;

    /** 以下三个是发布闸门的取数面。mock 出来只为断言「复制这条路一次都没碰它们」 */
    @Mock
    private WorkflowDslValidator dslValidator;
    @Mock
    private DslToStateGraphCompiler dslCompiler;
    @Mock
    private AgentWorkflowExecutionMapper executionMapper;

    private static final Long SOURCE_ID = 101L;
    private static final Long NEW_ID = 202L;
    private static final String SRC_CODE = "wf_src";
    private static final String NEW_CODE = "wf_new";
    private static final String SRC_DSL = "{\"id\":\"wf_src\",\"nodes\":[]}";
    private static final String REWRITTEN_DSL = "{\"id\":\"wf_new\",\"nodes\":[]}";

    /** insert 落下去的那一行：mock 里没有 MyBatis-Plus 的 id 回填，只能自己在 answer 里接住 */
    private AgentWorkflowDefDO inserted;

    // ==================== 桩 ====================

    private static AgentWorkflowDefDO source(String status, String sourceFlag, String description) {
        AgentWorkflowDefDO d = new AgentWorkflowDefDO();
        d.setId(SOURCE_ID);
        d.setCode(SRC_CODE);
        d.setVersion(3);
        d.setStatus(status);
        d.setSource(sourceFlag);
        d.setName("源流程");
        d.setDescription(description);
        d.setDsl(SRC_DSL);
        return d;
    }

    private static AgentWorkflowDefCopyReqVO req(String newDescription, Boolean rewriteConflicts) {
        AgentWorkflowDefCopyReqVO reqVO = new AgentWorkflowDefCopyReqVO();
        reqVO.setId(SOURCE_ID);
        reqVO.setNewCode(NEW_CODE);
        reqVO.setNewName("新流程");
        reqVO.setNewDescription(newDescription);
        reqVO.setRewriteConflicts(rewriteConflicts);
        return reqVO;
    }

    /**
     * 最小可用桩：查重放行 → insert 回填 id → {@code getDef(newId)} 回读<b>刚插进去那一行</b>
     *
     * <p>🔴 {@code selectById} 用 answer 返回真正落库的那个对象，而不是另 new 一份：
     * {@code copyDef} 的 RespVO 走的是 {@code BeanUtils.toBean(getDef(newId))}，即<b>回读</b>。
     * 另 new 一份的话，「VO 里的值到底来自落库行还是来自入参」就再也分不出来了。
     */
    private void stubPersist() {
        when(defMapper.selectCount(any(SFunction.class), eq(NEW_CODE))).thenReturn(0L);
        when(defMapper.selectList(any(SFunction.class), eq(NEW_CODE))).thenReturn(List.of());
        when(defMapper.insert(any(AgentWorkflowDefDO.class))).thenAnswer(inv -> {
            inserted = inv.getArgument(0, AgentWorkflowDefDO.class);
            inserted.setId(NEW_ID);
            return 1;
        });
        when(defMapper.selectById(NEW_ID)).thenAnswer(inv -> inserted);
    }

    private List<WorkflowCopyRewriter.Rewrite> stubRewrite(List<WorkflowCopyRewriter.Rewrite> manifest) {
        when(copyRewriter.rewrite(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new WorkflowCopyRewriter.Result(REWRITTEN_DSL, manifest));
        return manifest;
    }

    /** 把传给改写器的 5 个实参原样捞出来（顺序 = {@code dsl, newCode, newName, newDescription, rewriteConflicts}） */
    private Object[] rewriterArgs() {
        ArgumentCaptor<String> dslCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> codeCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> nameCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> descCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> flagCap = ArgumentCaptor.forClass(Boolean.class);
        verify(copyRewriter).rewrite(dslCap.capture(), codeCap.capture(), nameCap.capture(),
                descCap.capture(), flagCap.capture());
        return new Object[]{dslCap.getValue(), codeCap.getValue(), nameCap.getValue(),
                descCap.getValue(), flagCap.getValue()};
    }

    // ==================== 落库身份：DRAFT + MANUAL，与源无关 ====================

    @Test
    void copyDef_publishedAutoSource_landsAsDraftManual() {
        // 源是「已发布 + 自动生成」——两个方向都朝着「复制品会继承错的属性」去挑最坏的
        when(defMapper.selectById(SOURCE_ID)).thenReturn(source(
                WorkflowDefStatusEnum.PUBLISHED.getStatus(), WorkflowSourceEnum.AUTO.getSource(), "源描述"));
        stubPersist();
        stubRewrite(List.of());

        AgentWorkflowDefCopyRespVO respVO = defService.copyDef(req(null, true));

        ArgumentCaptor<AgentWorkflowDefDO> captor = ArgumentCaptor.forClass(AgentWorkflowDefDO.class);
        verify(defMapper).insert(captor.capture());
        AgentWorkflowDefDO row = captor.getValue();
        // 🔴 状态：复制品一律 DRAFT。继承 PUBLISHED 等于让一份<b>没跑过发布闸门</b>的 DSL 直接上线
        assertEquals(WorkflowDefStatusEnum.DRAFT.getStatus(), row.getStatus(), "复制品一律是草稿");
        // 🔴 来源：一律 MANUAL。继承 AUTO 的话列表页「只看手动添加的」滤不掉它，
        //    而它明明是管理员在页面上点出来的 —— 那一列的全部意义就是「谁建的、敢不敢删」
        assertEquals(WorkflowSourceEnum.MANUAL.getSource(), row.getSource(), "复制品是人工建的");
        assertEquals(NEW_CODE, row.getCode());
        assertEquals("新流程", row.getName());
        // 🔴 落库的是<b>改写后</b>的 DSL，不是源 DSL：这一格是「复制」与「原样抄一份」的全部区别
        assertEquals(REWRITTEN_DSL, row.getDsl(), "落库必须是改写后的 DSL");
        assertEquals(1, row.getVersion(), "新编码下是第一版");

        assertEquals(NEW_ID, respVO.getId());
        assertEquals(NEW_CODE, respVO.getCode());
        assertEquals("新流程", respVO.getName());
        assertEquals(WorkflowDefStatusEnum.DRAFT.getStatus(), respVO.getStatus());
    }

    @Test
    void copyDef_versionIsReadBack_notHardcodedOne() {
        when(defMapper.selectById(SOURCE_ID)).thenReturn(source(
                WorkflowDefStatusEnum.DRAFT.getStatus(), WorkflowSourceEnum.MANUAL.getSource(), null));
        // 🔴 刻意让「查重」与「算版本号」给出矛盾的答案：count=0（放行）而 selectList 里有 v7。
        //    真库不可能这样，但这两次查询在代码里确实是<b>各自独立</b>的两次调用，
        //    矛盾桩是这里唯一能把「version 从落库行读回」与「就地写死 1」分开的办法 ——
        //    新编码下版本恒为 1，用一致的桩两种写法都绿，等于没测。
        when(defMapper.selectCount(any(SFunction.class), eq(NEW_CODE))).thenReturn(0L);
        AgentWorkflowDefDO v7 = new AgentWorkflowDefDO();
        v7.setCode(NEW_CODE);
        v7.setVersion(7);
        when(defMapper.selectList(any(SFunction.class), eq(NEW_CODE))).thenReturn(List.of(v7));
        when(defMapper.insert(any(AgentWorkflowDefDO.class))).thenAnswer(inv -> {
            inserted = inv.getArgument(0, AgentWorkflowDefDO.class);
            inserted.setId(NEW_ID);
            return 1;
        });
        when(defMapper.selectById(NEW_ID)).thenAnswer(inv -> inserted);
        stubRewrite(List.of());

        AgentWorkflowDefCopyRespVO respVO = defService.copyDef(req(null, true));

        assertEquals(8, respVO.getVersion(), "版本号来自落库行，不是就地写死的 1");
        verify(defMapper).selectById(NEW_ID);
    }

    // ==================== 查重 / 源不存在：先于一切写动作 ====================

    @Test
    void copyDef_duplicateNewCode_throwsBeforeWakingTheRewriter() {
        when(defMapper.selectById(SOURCE_ID)).thenReturn(source(
                WorkflowDefStatusEnum.PUBLISHED.getStatus(), WorkflowSourceEnum.MANUAL.getSource(), null));
        when(defMapper.selectCount(any(SFunction.class), eq(NEW_CODE))).thenReturn(2L);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.copyDef(req(null, true)));

        assertEquals(WORKFLOW_DEF_CODE_DUPLICATE.getCode(), ex.getCode());
        // 🔴 撞名时改写器都不该被叫醒：改写是纯函数无副作用，但「先改一遍再发现存不进去」
        //    会让日志里出现一份没人认领的改写清单，排查时分不清它到底落库了没有
        verifyNoInteractions(copyRewriter);
        verify(defMapper, never()).insert(any(AgentWorkflowDefDO.class));
    }

    @Test
    void copyDef_sourceNotExists_throws() {
        when(defMapper.selectById(SOURCE_ID)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.copyDef(req(null, true)));

        assertEquals(WORKFLOW_DEF_NOT_EXISTS.getCode(), ex.getCode());
        verifyNoInteractions(copyRewriter);
        // 源都不存在，连查重都不该发生（更不该 insert）
        verify(defMapper, never()).selectCount(any(SFunction.class), any());
        verify(defMapper, never()).insert(any(AgentWorkflowDefDO.class));
    }

    // ==================== 描述：留空沿用源，与改写器同口径 ====================

    @Test
    void copyDef_blankDescription_inheritsSourceDescription() {
        when(defMapper.selectById(SOURCE_ID)).thenReturn(source(
                WorkflowDefStatusEnum.PUBLISHED.getStatus(), WorkflowSourceEnum.MANUAL.getSource(), "源描述"));
        stubPersist();
        stubRewrite(List.of());

        defService.copyDef(req("   ", true));

        // 🔴 与改写器同一个口径：改写器对空白描述是「原样保留 DSL 里那一格」，
        //    这里的<b>列</b>若写成空白，就会出现「列表里描述是空的、点进去 DSL 里描述还在」两个说法
        assertEquals("源描述", inserted.getDescription());
    }

    @Test
    void copyDef_explicitDescription_wins() {
        when(defMapper.selectById(SOURCE_ID)).thenReturn(source(
                WorkflowDefStatusEnum.PUBLISHED.getStatus(), WorkflowSourceEnum.MANUAL.getSource(), "源描述"));
        stubPersist();
        stubRewrite(List.of());

        defService.copyDef(req("新描述", true));

        assertEquals("新描述", inserted.getDescription());
    }

    // ==================== 传给改写器的实参 ====================

    @Test
    void copyDef_feedsRewriterTheSourceDslFromDb_notAnythingFromTheRequest() {
        when(defMapper.selectById(SOURCE_ID)).thenReturn(source(
                WorkflowDefStatusEnum.PUBLISHED.getStatus(), WorkflowSourceEnum.MANUAL.getSource(), null));
        stubPersist();
        stubRewrite(List.of());

        defService.copyDef(req("新描述", true));

        Object[] args = rewriterArgs();
        // 🔴 这是 G11 的服务端封口：{@code AgentWorkflowDefCopyReqVO} 里压根没有 dsl 字段（结构保证），
        //    本断言再钉一次「进改写器的确实是库里那一版」。两者一旦同时松动，
        //    前端又能把一份自己拼的 DSL 塞进「复制」——那正是 G11 的形状
        assertEquals(SRC_DSL, args[0], "改写器的输入必须是源定义库里那一版 DSL");
        assertEquals(NEW_CODE, args[1], "新 code 就是新 DSL 的 id");
        assertEquals("新流程", args[2]);
        assertEquals("新描述", args[3]);
        assertEquals(Boolean.TRUE, args[4]);
    }

    @Test
    void copyDef_rewriteConflictsNull_defaultsToTrue() {
        when(defMapper.selectById(SOURCE_ID)).thenReturn(source(
                WorkflowDefStatusEnum.PUBLISHED.getStatus(), WorkflowSourceEnum.MANUAL.getSource(), null));
        stubPersist();
        stubRewrite(List.of());

        defService.copyDef(req(null, null));

        // 🔴 缺省必须朝「不会撞车」那一侧：前端漏传这一格时若默认不改写，
        //    结果是两条流程去建同一张表（建表不可逆），而用户只会在清单里看到一堆「未改写」
        assertEquals(Boolean.TRUE, rewriterArgs()[4], "rewriteConflicts 缺省按 true");
    }

    @Test
    void copyDef_rewriteConflictsFalse_isHonoured() {
        when(defMapper.selectById(SOURCE_ID)).thenReturn(source(
                WorkflowDefStatusEnum.PUBLISHED.getStatus(), WorkflowSourceEnum.MANUAL.getSource(), null));
        stubPersist();
        stubRewrite(List.of());

        defService.copyDef(req(null, false));

        assertEquals(Boolean.FALSE, rewriterArgs()[4], "显式关掉就必须真的关掉");
    }

    // ==================== 清单：原样带回，一条都不许吞 ====================

    @Test
    void copyDef_manifestPassesThroughVerbatim_includingUnchangedRows() {
        when(defMapper.selectById(SOURCE_ID)).thenReturn(source(
                WorkflowDefStatusEnum.PUBLISHED.getStatus(), WorkflowSourceEnum.MANUAL.getSource(), null));
        stubPersist();
        WorkflowCopyRewriter.Rewrite changed = new WorkflowCopyRewriter.Rewrite(
                "nodes[create].config.args.model.tableName", "biz_emp", "biz_emp_copy", "建表名加了后缀");
        // from == to 的那一行：刻意没改，但用户必须知道（前端据此分成两组渲染）
        WorkflowCopyRewriter.Rewrite kept = new WorkflowCopyRewriter.Rewrite(
                "nodes[approve].config.bpmProcessDefKey", "leave_1", "leave_1", "审批流未复制");
        List<WorkflowCopyRewriter.Rewrite> manifest = stubRewrite(List.of(changed, kept));

        AgentWorkflowDefCopyRespVO respVO = defService.copyDef(req(null, true));

        assertSame(manifest, respVO.getRewritten(), "清单原样带回，不重排、不折叠、不过滤");
        assertEquals(2, respVO.getRewritten().size());
        assertEquals("biz_emp", respVO.getRewritten().get(0).from());
        assertEquals("biz_emp_copy", respVO.getRewritten().get(0).to());
        assertEquals("leave_1", respVO.getRewritten().get(1).from(), "未改写的那一行也必须在清单里");
        assertEquals(respVO.getRewritten().get(1).from(), respVO.getRewritten().get(1).to(),
                "from == to 就是「未改写」的判据，前端靠它分组");
    }

    @Test
    void copyDef_emptyManifestStaysEmptyList_notNull() {
        when(defMapper.selectById(SOURCE_ID)).thenReturn(source(
                WorkflowDefStatusEnum.PUBLISHED.getStatus(), WorkflowSourceEnum.MANUAL.getSource(), null));
        stubPersist();
        stubRewrite(List.of());

        AgentWorkflowDefCopyRespVO respVO = defService.copyDef(req(null, true));

        // 空清单 = 「这份 DSL 真的没有会撞车的配置」（如一条纯 LLM 问答流程），与「清单不可用」是两回事。
        // 变成 null 的话前端要么 NPE，要么静默不弹对话框 ⟹ 用户再也分不清「没冲突」和「没拿到清单」
        // 🔴 两句都带话：光秃秃的 assertNotNull 红了只报「expected: not <null>」，
        //    读日志的人得回来翻代码才知道是哪件事坏了（S5-g 的变异探针就是被这句噎住的）
        assertNotNull(respVO.getRewritten(),
                "空清单必须是一个空集合、不能是 null：null 会让前端分不清「这份 DSL 真的没冲突」和「清单没拿到」");
        assertTrue(respVO.getRewritten().isEmpty(),
                "改写器一条都没报 ⟹ 带回来的也必须是空的，实际=%s".formatted(respVO.getRewritten()));
    }

    // ==================== 不跑发布闸门 ====================

    @Test
    void copyDef_neverRunsThePublishGate_butStillProjectsTheTrigger() {
        when(defMapper.selectById(SOURCE_ID)).thenReturn(source(
                WorkflowDefStatusEnum.PUBLISHED.getStatus(), WorkflowSourceEnum.MANUAL.getSource(), null));
        stubPersist();
        stubRewrite(List.of());

        defService.copyDef(req(null, true));

        // 🔴 闸门一次都不跑：跑了的话所有源流程不合规的存量行都<b>复制不出来</b>，
        //    而复制恰恰是修它们的入口（把副本改对再发布）。代价是复制品可能必然发不出去，
        //    那份风险由 rewritten[] 在复制这一刻回显、由 S4b 闸门在发布那一刻兜住
        verifyNoInteractions(dslValidator, dslCompiler, executionMapper);
        // 但触发器投影<b>必须</b>跑：它是 insert 之前唯一的一道校验，
        // 也正是它让「改写器削出来的 webhook path 仍非法」在复制当场炸而不是等到发布
        verify(triggerExtractor).apply(any(AgentWorkflowDefDO.class));
    }

}
