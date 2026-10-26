package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerExtractor;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentWorkflowDefServiceImpl#validateDsl} 三层校验的<b>编排</b>单测（增量8 / P4 第二刀 + S4a-c）
 *
 * <p>纯 mock（{@link DslParser} + {@link WorkflowDslValidator} + {@link DslToStateGraphCompiler}），0 token、不建真图。
 * 断言聚焦「三层校验的编排纪律」——前两层正是 {@code WorkflowPlannerServiceImpl.validateTwoLayer} 的同一套逻辑，
 * 搬到管理页给编排者用：
 * <ol>
 *   <li>基础层通过 → 才跑深层干跑，两层结果合并</li>
 *   <li>基础层不通过 → 跳深层（{@code dryRunValidate} 一次都不调），省得在同一处重复 fail-fast</li>
 *   <li>两层的 warnings 都并进结果</li>
 *   <li>JSON 本身不合法（parse 抛 {@code WORKFLOW_DSL_INVALID}）→ 捕获成一条 error 返回、<b>不冒泡</b>，
 *       且基础/深层校验都不再跑</li>
 *   <li>🔴 第三层（折叠清单的干跑结论，V4/V5/V12）<b>不受</b>第 2 条约束，且只由三参重载触发 ——
 *       两参重载必须把 null 传进去，生成器那条最多 6 轮的自修正回路才不会每轮重跑一趟 15 秒的探测</li>
 *   <li>🔴 {@code foldUntrustedWrites}（V16 污点入写工具）只与它共享「不受第 2 条约束」这半句：
 *       台账 C9 之后它<b>不再吃清单</b> —— 清单为 null 时自己追一遍纯静态溯源，故两参重载
 *       （AI 自修正回路那条路）也照样判。原来那句「清单为 null 时它自己早退」已作废</li>
 * </ol>
 *
 * <p>折叠本身的规则（哪些 outcome 折、文案长什么样）在 {@code WorkflowDslValidatorTest} 第 ⑦ / ⑩ 节。
 *
 * @author 走进新时代
 */
class AgentWorkflowDefServiceValidateTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentWorkflowDefServiceImpl defService;

    // create/update 路径的依赖：本类不触发它们，但 @InjectMocks 需要能把它们注进去（留 null 亦可，此处显式 mock）
    @Mock
    private AgentWorkflowDefMapper defMapper;
    @Mock
    private WorkflowTriggerExtractor triggerExtractor;

    // validateDsl 的三件套
    @Mock
    private DslParser dslParser;
    @Mock
    private WorkflowDslValidator dslValidator;
    @Mock
    private DslToStateGraphCompiler dslCompiler;

    // 第四参（执行史）的取数口（台账 #81）
    @Mock
    private AgentWorkflowExecutionMapper executionMapper;

    private static final String DSL = "{\"id\":\"wf_x\",\"nodes\":[],\"edges\":[]}";
    private final AgentExecutionContext ctx = AgentExecutionContext.ofForTest(1L, 1L);

    @Test
    void validateDsl_basicPasses_runsDeepAndMerges() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        when(dslParser.parse(DSL)).thenReturn(def);
        // 基础层通过、带一条 warning
        AgentValidateResult basic = new AgentValidateResult();
        basic.addWarning("基础层提醒：outputVar 未声明");
        when(dslValidator.validate(def)).thenReturn(basic);
        // 深层通过、带一条 warning
        AgentValidateResult deep = new AgentValidateResult();
        deep.addWarning("深层提醒：sub_workflow 可加载但无输出映射");
        when(dslCompiler.dryRunValidate(def, ctx)).thenReturn(deep);

        AgentValidateResult result = defService.validateDsl(DSL, ctx);

        assertTrue(result.isPassed(), "两层都无 error → 通过");
        verify(dslCompiler).dryRunValidate(def, ctx);
        // 两层 warnings 合并
        assertTrue(result.getWarnings().contains("基础层提醒：outputVar 未声明"));
        assertTrue(result.getWarnings().contains("深层提醒：sub_workflow 可加载但无输出映射"));
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void validateDsl_deepFails_marksNotPassed() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        when(dslParser.parse(DSL)).thenReturn(def);
        when(dslValidator.validate(def)).thenReturn(new AgentValidateResult()); // 基础通过
        AgentValidateResult deep = new AgentValidateResult();
        deep.addError("DSL 深层结构不合法：节点 x 的 parallel 分支为空");
        when(dslCompiler.dryRunValidate(def, ctx)).thenReturn(deep);

        AgentValidateResult result = defService.validateDsl(DSL, ctx);

        assertFalse(result.isPassed(), "深层报 error → 不通过");
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("parallel 分支为空"));
    }

    @Test
    void validateDsl_basicFails_skipsDeepLayer() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        when(dslParser.parse(DSL)).thenReturn(def);
        AgentValidateResult basic = new AgentValidateResult();
        basic.addError("节点 id「OrderInfo」不合法：应改为 order_info");
        when(dslValidator.validate(def)).thenReturn(basic);

        AgentValidateResult result = defService.validateDsl(DSL, ctx);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().get(0).contains("order_info"));
        // 🔴 基础没过就不干跑深层：省得在同一处重复 fail-fast，也避免无谓的建图开销
        verify(dslCompiler, never()).dryRunValidate(any(), any());
    }

    @Test
    void validateDsl_parseThrows_returnsErrorNotThrow() {
        // JSON 语法错：parser 抛 WORKFLOW_DSL_INVALID
        when(dslParser.parse(anyString())).thenThrow(exception(WORKFLOW_DSL_INVALID, "JSON 解析失败：第 3 行缺逗号"));

        AgentValidateResult result = defService.validateDsl("{bad json", ctx);

        assertFalse(result.isPassed(), "非法 JSON 判不通过");
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("JSON 解析失败"), "parse 的原始原因被收成 error，而非冒泡成 HTTP 异常");
        // 解析都没过，基础/深层都不该再跑
        verify(dslValidator, never()).validate(any());
        verify(dslCompiler, never()).dryRunValidate(any(), any());
        verify(dslValidator, never()).foldUntrustedWrites(any(), any(), any());
    }

    @Test
    void validateDsl_parseThrowsServiceException_isCaughtNotPropagated() {
        // 兜底确认：ServiceException 被 catch，调用方拿到的是结果对象而不是异常
        when(dslParser.parse(anyString())).thenThrow(new ServiceException(WORKFLOW_DSL_INVALID.getCode(), "DSL 内容为空"));

        AgentValidateResult result = defService.validateDsl("", ctx);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().get(0).contains("DSL 内容为空"));
    }

    // ==================== 第三层：折叠清单里的干跑结论（S4a-c / V4/V5/V12） ====================

    /** 桩一次折叠：往传进来的结果里加一条 error，用来证明「折叠产物确实并进了返回值」 */
    private void stubFoldAdding(ChangeManifest manifest, String error) {
        doAnswer(inv -> {
            ((AgentValidateResult) inv.getArgument(1)).addError("%s", error);
            return null;
        }).when(dslValidator).foldDryRunRejections(same(manifest), any(), anyLong());
    }

    /** 同上，桩的是 V16 那一趟（结果是<b>第三个</b>入参，且它只产 warning） */
    private void stubFoldUntrustedAdding(ChangeManifest manifest, String warning) {
        doAnswer(inv -> {
            ((AgentValidateResult) inv.getArgument(2)).addWarning("%s", warning);
            return null;
        }).when(dslValidator).foldUntrustedWrites(any(), same(manifest), any());
    }

    @Test
    void validateDsl_withManifest_foldsDryRunRejectionsIntoErrors() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        when(dslParser.parse(DSL)).thenReturn(def);
        when(dslValidator.validate(def)).thenReturn(new AgentValidateResult());   // 基础通过
        when(dslCompiler.dryRunValidate(def, ctx)).thenReturn(new AgentValidateResult()); // 深层通过
        ChangeManifest manifest = new ChangeManifest();
        stubFoldAdding(manifest, "节点(建表) 的「新建数据表」这么改跑不通");

        AgentValidateResult result = defService.validateDsl(DSL, ctx, manifest);

        // 🔴 折叠前，这一份的返回是 passed=true + 一个带红标的清单：界面上「校验通过」与「跑不通」同屏
        assertFalse(result.isPassed(), "被平台拒绝的变更必须阻断，否则绿章与红标自相矛盾");
        assertEquals(1, result.getErrors().size(), result.toFeedback());
        verify(dslValidator).foldDryRunRejections(same(manifest), any(), anyLong());
        verify(dslValidator).foldUntrustedWrites(same(def), same(manifest), any());
    }

    /**
     * 🔴 V16 产的是 warning —— 故它<b>不该</b>把 passed 拉黑。
     * 「远程改数据的形状」值得说给人听，却不值得替人做决定（理由见 {@code foldUntrustedWrites} 的注释）。
     *
     * <p>⚠️ 它<b>不再是</b>「同一份清单的第二个消费者」（台账 C9）：服务层照样把 manifest 原样递下去，
     * 但 {@code foldUntrustedWrites} 收到 null 也会自己追一遍纯静态溯源。本用例验的是服务层那一半。
     */
    @Test
    void validateDsl_withManifest_foldsUntrustedWritesIntoWarningsOnly() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        when(dslParser.parse(DSL)).thenReturn(def);
        when(dslValidator.validate(def)).thenReturn(new AgentValidateResult());
        when(dslCompiler.dryRunValidate(def, ctx)).thenReturn(new AgentValidateResult());
        ChangeManifest manifest = new ChangeManifest();
        stubFoldUntrustedAdding(manifest, "本流程由 webhook 触发、跑起来时没有人在场，而下面 1 处流向没人过目");

        AgentValidateResult result = defService.validateDsl(DSL, ctx, manifest);

        assertTrue(result.isPassed(), "V16 只提醒、不阻断：" + result.toFeedback());
        assertEquals(1, result.getWarnings().size(), result.toFeedback());
        assertTrue(result.getWarnings().get(0).contains("没有人在场"), result.getWarnings().get(0));
    }

    /**
     * 🔴 第三层<b>不</b>受「基础通过才跑」的约束 —— 与第二层刻意不同。
     *
     * <p>第二层跳过是因为它 fail-fast，基础不对时大概率在同一处再炸一遍，说了等于没说；
     * 第三层是清单里<b>已经探完</b>的结论，「节点 id 不合法」与「表名已被占用」互不遮蔽，
     * 一次说完才不用让人改一个、再点一次校验、又撞下一个。
     */
    @Test
    void validateDsl_basicFails_stillFoldsTheManifest() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        when(dslParser.parse(DSL)).thenReturn(def);
        AgentValidateResult basic = new AgentValidateResult();
        basic.addError("节点 id「OrderInfo」不合法：应改为 order_info");
        when(dslValidator.validate(def)).thenReturn(basic);
        ChangeManifest manifest = new ChangeManifest();
        stubFoldAdding(manifest, "表名已被占用");

        AgentValidateResult result = defService.validateDsl(DSL, ctx, manifest);

        assertEquals(2, result.getErrors().size(), "基础错与干跑结论要一次都说完：" + result.toFeedback());
        verify(dslCompiler, never()).dryRunValidate(any(), any());
        verify(dslValidator).foldDryRunRejections(same(manifest), any(), anyLong());
        // 🔴 V16 同纪律，但不是同一个理由：它不受「基础通过才跑」那道门约束（台账 C9 之后判据是纯静态溯源、
        //    压根不读清单），故「节点 id 不合法」照样遮蔽不了「污点进了写工具」
        verify(dslValidator).foldUntrustedWrites(same(def), same(manifest), any());
    }

    /**
     * 🔴 两参重载必须把 null 传进两个折叠器，且执行史必须传 <b>0</b>（= 一档都不降）。
     *
     * <p>生成器那条自修正回路走的就是这一档，最多 6 轮。若它也跟着折干跑结论，
     * 就等于每轮重跑一趟最多 15 秒的只读探测 —— 而探测结论（表名撞车、DDL 不合规）
     * 模型改一轮也变不了，那是人去决定换哪个名字的事。
     *
     * <p>执行史传 0 是<b>同一条纪律的另一半</b>（台账 #81）：生成器手上没有 defId，
     * 若这里为了「顺手」去查库猜一个身份，猜错的方向永远是「多放行一条」——
     * 而降档的理由是「这东西极可能是自己早先的版本建的」，一份还没落库的草稿恰恰<b>不可能</b>建过任何东西。
     *
     * <p>⚠️ 本用例原名 {@code doesNotFold}，台账 C9 之后那名字<b>只对一半</b>，故改成「传 null 进去」：
     * {@code foldDryRunRejections} 收到 null 确实什么也不做（它的判据就是干跑结论），
     * 而 {@code foldUntrustedWrites} 收到 null 会<b>自己追一遍</b>污点（溯源是纯静态的，不花钱）。
     * 这里 {@code dslValidator} 是 mock，验的是「服务层把 null 原样递下去、没有偷偷替调用方建清单」，
     * 递下去之后两个折叠器各自怎么办由它们自己的测试管
     * （{@code WorkflowDslValidatorTest} 的 V16 一节）。
     */
    @Test
    void validateDsl_twoArgOverload_passesNullManifestThrough() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        when(dslParser.parse(DSL)).thenReturn(def);
        when(dslValidator.validate(def)).thenReturn(new AgentValidateResult());
        when(dslCompiler.dryRunValidate(def, ctx)).thenReturn(new AgentValidateResult());

        AgentValidateResult result = defService.validateDsl(DSL, ctx);

        assertTrue(result.isPassed(), result.toFeedback());
        verify(dslValidator).foldDryRunRejections(isNull(), any(), eq(0L));
        verify(dslValidator).foldUntrustedWrites(same(def), isNull(), any());
    }

    /** JSON 都不合法时清单必然也是 null（{@code buildManifestOrNull} 同样 parse 不出来），折叠不该再跑 */
    @Test
    void validateDsl_parseThrows_skipsTheFold() {
        when(dslParser.parse(anyString())).thenThrow(exception(WORKFLOW_DSL_INVALID, "JSON 解析失败：第 3 行缺逗号"));

        AgentValidateResult result = defService.validateDsl("{bad json", ctx, new ChangeManifest());

        assertFalse(result.isPassed());
        verify(dslValidator, never()).foldDryRunRejections(any(), any(), anyLong());
        verify(dslValidator, never()).foldUntrustedWrites(any(), any(), any());
    }

    // ==================== 第四参：执行史（台账 #81 = S0 裁定① 的 A 方案） ====================

    private static AgentWorkflowDefDO def(Long id, String code, Integer version) {
        AgentWorkflowDefDO def = new AgentWorkflowDefDO();
        def.setId(id);
        def.setCode(code);
        def.setVersion(version);
        return def;
    }

    /** 三层都放行的桩：本节只关心第四参传了什么，不关心体检本身 */
    private WorkflowDefDTO stubAllLayersPass() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        when(dslParser.parse(DSL)).thenReturn(def);
        when(dslValidator.validate(def)).thenReturn(new AgentValidateResult());
        when(dslCompiler.dryRunValidate(def, ctx)).thenReturn(new AgentValidateResult());
        return def;
    }

    /**
     * 🔴 四参重载传进折叠的必须是「本 code 名下<b>全部版本</b>」的合计，不是这一行自己的次数。
     *
     * <p>场景就是 #81 的那个现场：id=90 是跑过 3 次的 v1，id=100 是从它<b>回滚出来</b>的 v2 草稿 ——
     * 新版本自己一次都没跑过（执行史全在旧 id 上），而用户正要发布的就是它。
     * 少展开这一步的话这里拿到的是 0，降档不触发，「已落地的撞车」照样把发布拦死，
     * 于是这条流程<b>永远失去了回滚能力</b>（回滚出来的草稿发不出去，原版又已是 PUBLISHED 走幂等短路）。
     *
     * <p>断言只写 {@code eq(3L)} 就够咬住那个变异：桩返回的 Map 是按<b>旧 id</b>(90) 键的，
     * 若实现只问新 id(100)，{@code getOrDefault} 兜出来就是 0。
     */
    @Test
    void validateDsl_withDefId_sumsExecutionsAcrossAllVersionsOfTheCode() {
        stubAllLayersPass();
        ChangeManifest manifest = new ChangeManifest();
        when(defMapper.selectById(100L)).thenReturn(def(100L, "wf_x", 2));
        // selectListByCode 的真实排序是 version DESC（新版在前），照它摆
        when(defMapper.selectListByCode("wf_x")).thenReturn(List.of(def(100L, "wf_x", 2), def(90L, "wf_x", 1)));
        when(executionMapper.countByDefIds(anyCollection())).thenReturn(Map.of(90L, 3L));

        defService.validateDsl(DSL, ctx, manifest, 100L);

        verify(dslValidator).foldDryRunRejections(same(manifest), any(), eq(3L));
    }

    /**
     * 🔴 id 在库里不存在 ⟹ 按 0 次算、<b>不抛</b> {@code WORKFLOW_DEF_NOT_EXISTS}，且一次库都不查。
     *
     * <p>{@code /validate} 是只读打分端点：为一个不存在的 id 抛「不存在」，等于把「给一份 DSL 打分」
     * 变成「先证明这行在库里」—— 而设计器里那份还没保存的草稿恰恰没有 id。
     * 取 0 也刚好落在 fail-close 那一边（降档是<b>放行</b>方向，拿不到证据就不放）。
     */
    @Test
    void validateDsl_withUnknownDefId_treatsAsNeverExecuted() {
        stubAllLayersPass();
        ChangeManifest manifest = new ChangeManifest();

        AgentValidateResult result = defService.validateDsl(DSL, ctx, manifest, 999L);

        assertTrue(result.isPassed(), result.toFeedback());
        verify(dslValidator).foldDryRunRejections(same(manifest), any(), eq(0L));
        verify(executionMapper, never()).countByDefIds(anyCollection());
    }

    /** {@code defId} 传 null（未落库的草稿）⟹ 与三参重载逐字同效：0 次，且连 selectById 都不打 */
    @Test
    void validateDsl_withNullDefId_isSameAsThreeArgOverload() {
        stubAllLayersPass();
        ChangeManifest manifest = new ChangeManifest();

        defService.validateDsl(DSL, ctx, manifest, null);

        verify(dslValidator).foldDryRunRejections(same(manifest), any(), eq(0L));
        verify(defMapper, never()).selectById(any());
    }

}
