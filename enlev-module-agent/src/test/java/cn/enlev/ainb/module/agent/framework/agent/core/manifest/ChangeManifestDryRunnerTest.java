package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.EdgeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.GraphReachability;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.BpmProcessValidator;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.DataModelCreateValidator;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.DataModelUpdateValidator;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelParamResolver;
import cn.enlev.ainb.module.agent.tool.impl.form.FormPublishBundleTool;
import cn.enlev.ainb.module.agent.tool.impl.form.FormPublishParamResolver;
import cn.enlev.ainb.module.agent.tool.impl.form.FormSaveDraftTool;
import cn.enlev.ainb.module.agent.tool.impl.process.ProcessCreateAndDeployTool;
import cn.enlev.ainb.module.bpm.api.definition.BpmCategoryApi;
import cn.enlev.ainb.module.bpm.api.definition.BpmModelApi;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelRespDTO;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmProcessConstraintsRespDTO;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignMenuPreviewRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignPublishRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignSaveReqDTO;
import cn.enlev.ainb.module.system.api.menu.MenuApi;
import cn.enlev.ainb.module.system.api.menu.dto.MenuSimpleRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChangeManifestDryRunner} 单测
 * （S2-d3a 建表/改表，S2-d3b 建表单/发布，S2-d3c 部署审批流）
 *
 * <h3>本测试真正在钉的东西：三态不能互相塌缩</h3>
 * 干跑有三个结论，而其中两个塌缩的方向都是<b>撒谎</b>：
 * <ul>
 *   <li>{@code NOT_PROBED} 塌成 {@code PASSED} —— infra 挂了，清单一片绿，
 *       用户照着点「同意」，执行时才炸。这是最坏的一种，因为它给了信心。</li>
 *   <li>{@code NOT_PROBED} 塌成 {@code REJECTED} —— 表名是 {@code {{$.x}}} 这种运行期才定的值，
 *       拿去问 infra 一定被按「表名不合法」拒。清单于是在<b>冤枉一份合法的 DSL</b>，
 *       用户会去改一个根本不该改的地方。</li>
 * </ul>
 * 所以这里的用例大量在断言「<b>不是</b>哪一态」「<b>没打</b>哪个接口」，而不只是「是哪一态」。
 *
 * <h3>以及：静默的 null</h3>
 * {@code conflict == null} 有两种来历 —— 「查过了、没撞车」与「没查成」。
 * 界面上长得一模一样，而前者可以放行、后者不能。
 * {@link #conflictProbeBroken_leavesAWarningInsteadOfASilentNull} 与
 * {@link #processDeploy_modelQueryBroken_neverSilentlySaysNew} 各钉一处
 * （后者的 null 是 {@code detail.deployMode}：填「新建」与「没查成」同样长得一样）。
 *
 * <h3>以及：清单不能在最标准的那条流程上喊狼来了</h3>
 * {@code 建表 → 存表单草稿 → 发布} 是本平台最典型的一份 DSL，而做清单的这一刻
 * <b>表还不存在</b>。直接拿主表名去查库就会判它「主表不存在，会被拒」——
 * 冤枉的恰恰是最正确的那一份。{@link #formCreate_mainTableCreatedByAnUpstreamNode_isNotAccused}
 * 与 {@link #formCreate_mainTableCreatedByANodeThatDoesNotReachIt_isRejected} 一正一反钉这一条。
 *
 * <h3>🔴 以及：断言强度要够，否则强制点自己有盲区也不会报告</h3>
 * 「一条分类都没配就不回落」那半个条件被变异掉时，干跑器会走进 {@code enabled.get(0)}
 * 抛 {@code IndexOutOfBoundsException}，被最外层接住落成 {@code NOT_PROBED} ——
 * 而 {@code NOT_PROBED} 恰好<b>不带</b>已收集的警告，于是「警告为空」这条断言照样通过，
 * 62 个用例全绿。是变异探针把它照出来的，不是测试自己。
 * 修法是把结论断言（{@code PASSED}）排在警告断言之前，见
 * {@link #processDeploy_categoryFine_noWarning} 里的注释。
 * <p>通则：<b>凡是「什么都没发生」这类否定式断言，前面必须先有一条肯定式断言说明
 * 这一项确实探通了</b>，否则「探通了没事」与「崩了」共用同一个绿色。
 *
 * <h3>没有覆盖的一条</h3>
 * {@code BUDGET_SECONDS} 的整体预算。它读的是 {@code System.nanoTime()}，
 * 要测就得给干跑器塞一个时钟 —— 为一个「超时就落黄色」的兜底加一处生产代码的可注入缝，
 * 不值当。真要验证它，等 S4b 的发布闸门把「探不出来要不要放行」变成必须回答的问题时一起做。
 *
 * @author 走进新时代
 */
class ChangeManifestDryRunnerTest {

    private static final String CREATE_TOOL = "datamodel_create";
    private static final String UPDATE_TOOL = "datamodel_update";
    private static final String SAVE_DRAFT_TOOL = "form_save_draft";
    private static final String PUBLISH_TOOL = "form_publish_bundle";
    private static final String TABLE = "biz_emp";
    private static final String FORM_CODE = "employee";
    private static final Long FORM_ID = 5L;
    private static final Long PARENT_MENU_ID = 2600L;

    /**
     * infra 建按钮权限时那 7 个动作，顺序照 {@code FormDesignServiceImpl:500-503}
     *
     * <p>用例里刻意只取前 3 个：菜单条数必须是<b>算</b>出来的，
     * 写死「共 8 条」的实现只有在按钮数不等于 7 时才露得出来
     */
    private static final String[] MENU_ACTIONS = {
            "query", "create", "update", "delete", "export", "import", "reference"};

    private static final String DEPLOY_TOOL = "process_create_and_deploy";
    private static final String PROCESS_KEY = "purchase_apply";
    /**
     * 一棵最小的合法节点树：发起人 → 一个审批节点 → 结束
     *
     * <p>刻意不复用 {@code BpmProcessValidator.FEW_SHOT_EXAMPLE}：那份带条件分支，
     * 于是 {@link #processConstraints()} 还得配 {@code conditionTypes}，
     * 而本测试要钉的是干跑器<b>怎么转述</b>校验器的结论，不是校验器自己那 9 条规则
     * （那些由 {@code BpmProcessValidatorTest} 钉）。
     */
    private static final String VALID_TREE = """
            {"id":"StartUserNode","name":"发起人","type":10,
             "childNode":{"id":"Activity_1","name":"部门审批","type":11,
               "approveType":1,"approveMethod":1,"candidateStrategy":30,"candidateParam":"1",
               "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
    /** 只有发起人 → 结束：合法，但校验器会给一条「没有任何审批节点」的警告 */
    private static final String TREE_WITHOUT_APPROVE = """
            {"id":"StartUserNode","name":"发起人","type":10,
             "childNode":{"id":"EndEvent","name":"结束","type":1}}""";
    /** 与 {@link #VALID_TREE} 只差审批节点上多绑了一张独立表单 */
    private static final String TREE_WITH_NODE_FORM = """
            {"id":"StartUserNode","name":"发起人","type":10,
             "childNode":{"id":"Activity_1","name":"部门审批","type":11,
               "approveType":1,"approveMethod":1,"candidateStrategy":30,"candidateParam":"1",
               "formType":30,"formCode":"sub_form",
               "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
    /** 缺结束节点：后端构造流程图时会直接空指针，校验器硬拦 */
    private static final String TREE_WITHOUT_END = """
            {"id":"StartUserNode","name":"发起人","type":10,
             "childNode":{"id":"Activity_1","name":"部门审批","type":11,
               "approveType":1,"approveMethod":1,"candidateStrategy":30,"candidateParam":"1"}}""";

    private DataModelApi dataModelApi;
    private DataModelParamResolver resolver;
    private DataModelCreateValidator createValidator;
    private DataModelUpdateValidator updateValidator;
    private FormDesignApi formDesignApi;
    private MenuApi menuApi;
    private BpmModelApi bpmModelApi;
    private BpmCategoryApi bpmCategoryApi;
    private ChangeManifestDryRunner dryRunner;

    @BeforeEach
    void setUp() {
        dataModelApi = mock(DataModelApi.class);
        resolver = mock(DataModelParamResolver.class);
        createValidator = mock(DataModelCreateValidator.class);
        updateValidator = mock(DataModelUpdateValidator.class);
        formDesignApi = mock(FormDesignApi.class);
        menuApi = mock(MenuApi.class);
        bpmModelApi = mock(BpmModelApi.class);
        bpmCategoryApi = mock(BpmCategoryApi.class);
        dryRunner = new ChangeManifestDryRunner();
        ReflectionTestUtils.setField(dryRunner, "dataModelApi", dataModelApi);
        ReflectionTestUtils.setField(dryRunner, "dataModelParamResolver", resolver);
        ReflectionTestUtils.setField(dryRunner, "createValidator", createValidator);
        ReflectionTestUtils.setField(dryRunner, "updateValidator", updateValidator);
        ReflectionTestUtils.setField(dryRunner, "formDesignApi", formDesignApi);
        // 🔴 解析器用<b>真</b>的、只把 MenuApi 换成 mock。
        //    发布那一支要钉的正是「干跑与真发布读同一份反查」，把解析器整个 mock 掉就等于没测
        FormPublishParamResolver publishResolver = new FormPublishParamResolver();
        ReflectionTestUtils.setField(publishResolver, "menuApi", menuApi);
        ReflectionTestUtils.setField(dryRunner, "formPublishParamResolver", publishResolver);
        // 🔴 同理，流程校验器也用<b>真</b>的、只把 BpmModelApi 换成 mock（与干跑器共用同一个 mock：
        //    生产环境里它们本就是同一个 bean）。部署那一支要钉的正是
        //    「干跑调的是工具调的那个校验器」，把它 mock 掉就等于什么都没测
        BpmProcessValidator processValidator = new BpmProcessValidator();
        ReflectionTestUtils.setField(processValidator, "bpmModelApi", bpmModelApi);
        ReflectionTestUtils.setField(dryRunner, "bpmModelApi", bpmModelApi);
        ReflectionTestUtils.setField(dryRunner, "bpmCategoryApi", bpmCategoryApi);
        ReflectionTestUtils.setField(dryRunner, "processValidator", processValidator);
        // 部署那一支的默认桩：规则齐全、没有同标识的流程、分类启用中、主表单可用
        lenient().when(bpmModelApi.getProcessConstraints()).thenReturn(processConstraints());
        lenient().when(bpmModelApi.getModelList(any())).thenReturn(List.of());
        lenient().when(bpmCategoryApi.getEnabledCategoryCodes()).thenReturn(List.of("oa", "hr"));
        lenient().when(formDesignApi.validateFlowReady(anyString(), anyBoolean())).thenReturn(null);
        // 默认「库里没有这张表」，撞车相关的用例自己覆写
        when(dataModelApi.getPhysicalTableList(anyString())).thenReturn(List.of());
    }

    // ==================== 建表：三态各自的入口 ====================

    @Test
    @DisplayName("建表探通：PASSED，DDL 落在 evidence 里，校验器的 warning 一并带出")
    void tableCreate_passed() {
        stubCreateChain(passingValidation("字段 name 没写长度，会建成 varchar(255)"),
                List.of("CREATE TABLE biz_emp (...)", "ALTER TABLE ..."));

        ChangeItem item = createItem();
        dryRunner.enrich(manifest(item), ctx(createNode()));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.PASSED, result.getOutcome());
        assertTrue(result.getReason().contains("预览通过"), result.getReason());
        assertTrue(result.getReason().contains("2 条 DDL"), result.getReason());
        // 「尚未执行」不是客套话：清单会被冻进 BPM 流程变量、几天后才有人看，
        // 那时「预览通过」很容易被读成「已经建好了」
        assertTrue(result.getReason().contains("尚未执行"), result.getReason());
        assertEquals(List.of("字段 name 没写长度，会建成 varchar(255)"), result.getWarnings());
        assertEquals(2, ((List<?>) result.getEvidence().get("ddl")).size());
        assertNull(item.getConflict());
    }

    @Test
    @DisplayName("建表参数解析就抛 ServiceException：REJECTED —— 这是平台给出的结论，不是故障")
    void tableCreate_rejectedByResolver() {
        when(resolver.resolveCreate(any(), eq(CREATE_TOOL)))
                .thenThrow(new ServiceException(400, "缺少 model（建表定义）"));

        ChangeItem item = createItem();
        dryRunner.enrich(manifest(item), ctx(createNode()));

        assertEquals(DryRunResult.Outcome.REJECTED, item.getDryRun().getOutcome());
        assertEquals("缺少 model（建表定义）", item.getDryRun().getReason());
        verify(dataModelApi, never()).previewDdl(any());
    }

    @Test
    @DisplayName("校验不通过：REJECTED，多条错误连成一行 —— 清单不是给模型看的，不该套 toFeedback 那套编号")
    void tableCreate_rejectedByValidation() {
        AgentValidateResult validation = new AgentValidateResult();
        validation.addError("表名 emp 缺少业务表前缀，应改为 biz_emp");
        validation.addError("字段 name 缺少中文注释");
        validation.addWarning("字段 remark 是 TEXT，它的默认值会被静默丢弃");
        stubCreateChain(validation, List.of());

        ChangeItem item = createItem();
        dryRunner.enrich(manifest(item), ctx(createNode()));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.REJECTED, result.getOutcome());
        assertEquals("表名 emp 缺少业务表前缀，应改为 biz_emp；字段 name 缺少中文注释", result.getReason());
        assertEquals(List.of("字段 remark 是 TEXT，它的默认值会被静默丢弃"), result.getWarnings());
        // 校验都没过就不该再去问后端要 DDL —— 白打一次接口，还可能把半截结论暴露给用户
        verify(dataModelApi, never()).previewDdl(any());
    }

    @Test
    @DisplayName("🔴 infra 级故障：NOT_PROBED，绝不是 PASSED 也绝不是 REJECTED")
    void tableCreate_notProbedWhenInfraIsDown() {
        stubCreateChain(passingValidation(), List.of());
        when(dataModelApi.previewDdl(any()))
                .thenThrow(new InfraDownException("Communications link failure"));

        ChangeItem item = createItem();
        dryRunner.enrich(manifest(item), ctx(createNode()));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        // 异常类名必须带上：运维看到满屏黄色时，要能一眼分出是网络、权限还是 SQL 超时
        assertTrue(result.getReason().contains("InfraDownException"), result.getReason());
        assertTrue(result.getReason().contains("本次未校验"), result.getReason());
        assertFalse(result.getReason().contains("预览通过"), result.getReason());
        assertNull(item.getConflict(), "撞车那一支是探出来了的（默认桩返回空表），不该被这次故障抹掉");
    }

    @Test
    @DisplayName("🔴 表名是占位符：NOT_PROBED，且不冤枉这份 DSL —— 一次接口都不打")
    void tableCreate_placeholderTableNameIsNotAnAccusation() {
        ChangeItem item = createItem();
        NodeDTO node = createNode(Map.of("tableName", "{{$.input.tableName}}"));

        dryRunner.enrich(manifest(item), ctx(node));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        // 🔴 这四条才是重点，故排在文案断言之前：占位符送去 resolveCreate 一定会被 infra
        //    按「表名不合法」拒，于是清单会说一份合法的 DSL 有问题，用户去改一个不该改的地方
        verify(resolver, never()).resolveCreate(any(), anyString());
        verify(createValidator, never()).validate(any());
        verify(dataModelApi, never()).previewDdl(any());
        verify(dataModelApi, never()).getPhysicalTableList(anyString());
        assertTrue(result.getReason().contains("运行期"), result.getReason());
        assertTrue(result.getReason().contains("{{$.input.tableName}}"), result.getReason());
    }

    /**
     * 上一条守的是「{@code model} 是对象、里面的 {@code tableName} 是占位符」。
     * 这一条守的是<b>再外面一层</b>：{@code model} <b>整体</b>就是占位符
     * （{@code {"model": "{{$.prepared_args.model}}"}，让前置 script 节点拼建表定义）。
     *
     * <p>🔴 那一层的守卫对本形态<b>完全无效</b>（缺陷 D4-17，run11 示例 55）：
     * {@code ManifestArgs.map} 解析占位符会失败、按契约静默返回空 Map，
     * 于是取出来的 {@code tableName} 是 null 而不是占位符，{@code isPending(null)} 恒 false，
     * 值被原样送进 {@code resolveCreate}。而那份 DSL <b>执行期是跑得通的</b> ——
     * {@code StateTemplateResolver#resolve} 把脚本产出的对象用 {@code JSONUtil.toJsonStr}
     * 渲染成 JSON 串，{@code JSONUtil.parseObj} 两种形态都收（{@code ManifestArgs#map}
     * 的类注释早就把「与执行期同口径」写成了本类的纪律）。
     * 所以正确结论只能是 NOT_PROBED「无从复核」，报 REJECTED 就是冤枉。
     */
    @Test
    @DisplayName("🔴 model 整体是占位符（脚本拼建表定义）：NOT_PROBED，一次接口都不打")
    void tableCreate_wholeModelIsPlaceholder_isNotAnAccusation() {
        ChangeItem item = createItem();
        NodeDTO node = node("n1", Map.of(DataModelParamResolver.PARAM_MODEL,
                "{{$.prepared_args.model}}"));

        dryRunner.enrich(manifest(item), ctx(node));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        verify(resolver, never()).resolveCreate(any(), anyString());
        verify(createValidator, never()).validate(any());
        verify(dataModelApi, never()).previewDdl(any());
        verify(dataModelApi, never()).getPhysicalTableList(anyString());
        // 原样引回那串占位符：审批人要知道「无从复核的是哪一格」，只说「运行期才知道」等于没说
        assertTrue(result.getReason().contains("{{$.prepared_args.model}}"), result.getReason());
        assertTrue(result.getReason().contains("运行期"), result.getReason());
    }

    /**
     * 上一条用 mock 解析器钉的是「压根不把它交给解析器」。这一条换<b>真</b>解析器，
     * 钉的是用户真正看到的那句话里没有库的内部文案。
     *
     * <p>🔴 为什么值得单开一条：mock 版的 {@code verify(never())} 只证明「本类没调」，
     * 一旦哪天守卫被挪走或判据写歪，hutool 的原文就会顺着
     * {@code previewCreate} 的 {@code catch (ServiceException)} → {@code DryRunResult.rejected}
     * → {@code WorkflowDslValidator#foldDryRunRejections} 一路透传成一条体检 error，
     * 而 mock 版用例<b>照样全绿</b>。实测（hutool-all 5.8.46，jshell 复现）那句话是：
     * <pre>A JSONObject can not directly nest another JSONObject or JSONArray. at 2 [character 3 line 1]</pre>
     * —— 它连「哪个参数、该怎么改」都没说，用户无从下手。run11 示例 55 报的就是它。
     */
    @Test
    @DisplayName("🔴 model 整体是占位符时，绝不让 hutool 的库内部文案漏进用户可见的结论")
    void tableCreate_wholeModelIsPlaceholder_neverLeaksHutoolInternals() {
        // 真解析器：resolveCreate 的第一步就是 JSONUtil.parseObj(model)，不碰任何注入依赖
        ReflectionTestUtils.setField(dryRunner, "dataModelParamResolver", new DataModelParamResolver());
        ChangeItem item = createItem();
        NodeDTO node = node("n1", Map.of(DataModelParamResolver.PARAM_MODEL,
                "{{$.prepared_args.model}}"));

        dryRunner.enrich(manifest(item), ctx(node));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertFalse(result.getReason().contains("JSONObject"), result.getReason());
        assertFalse(result.getReason().contains("can not directly nest"), result.getReason());
        assertFalse(result.getReason().contains("不是合法的建表定义"), result.getReason());
        verify(dataModelApi, never()).previewDdl(any());
    }

    @Test
    @DisplayName("🔴 建表链的步骤与顺序必须与真执行一致 —— 这条链已有第三份副本，漂移面就是步骤清单本身")
    void tableCreate_runsTheSameStepsInTheSameOrder() {
        stubCreateChain(passingValidation(), List.of("CREATE TABLE biz_emp (...)"));

        ChangeItem item = createItem();
        dryRunner.enrich(manifest(item), ctx(createNode()));

        InOrder order = inOrder(dataModelApi, resolver, createValidator);
        // 存在性探测排在最前：它是本类<b>额外</b>加的一步（previewDdl 与校验器都不查表是否已存在，
        // 只有 createDataModel 在执行时才拦），先探它才分得开「撞车」与「参数写错」
        order.verify(dataModelApi).getPhysicalTableList(TABLE);
        order.verify(resolver).resolveCreate(any(), eq(CREATE_TOOL));
        order.verify(createValidator).validate(any());
        order.verify(dataModelApi).previewDdl(any());
        order.verifyNoMoreInteractions();
        assertEquals(DryRunResult.Outcome.PASSED, item.getDryRun().getOutcome());
    }

    // ==================== 撞车：null 的两种来历必须可区分 ====================

    @Test
    @DisplayName("表已存在且已登记：conflict=TABLE_ALREADY_EXISTS，出路是换名或复用")
    void conflict_registeredTable() {
        stubCreateChain(passingValidation(), List.of());
        when(dataModelApi.getPhysicalTableList(TABLE)).thenReturn(List.of(physicalTable(TABLE, true)));

        ChangeItem item = createItem();
        dryRunner.enrich(manifest(item), ctx(createNode()));

        ChangeItem.Conflict conflict = item.getConflict();
        assertNotNull(conflict);
        assertEquals(ChangeItem.Conflict.TYPE_TABLE_ALREADY_EXISTS, conflict.getType());
        assertTrue(conflict.getMessage().contains("已经建好"), conflict.getMessage());
        assertTrue(conflict.getMessage().contains("换一个表名"), conflict.getMessage());
        // 🔴 撞车必须同时是 REJECTED：S4b 的发布闸门只读 dryRun，stats.dryRunRejected 是
        //    「一眼看到全貌」那个数。只填 conflict 的话，一份注定执行失败的清单会显示「0 项跑不通」
        assertEquals(DryRunResult.Outcome.REJECTED, item.getDryRun().getOutcome());
        assertEquals(conflict.getMessage(), item.getDryRun().getReason());
        // 撞车就到此为止：这一项已经红了，再把它那串 DDL 预演出来帮不了用户
        verify(dataModelApi, never()).previewDdl(any());
    }

    @Test
    @DisplayName("🔴 表存在但没登记：出路是「导入已有表」，不是换名 —— 换名会在库里多留一张没人管的表")
    void conflict_unregisteredTable() {
        stubCreateChain(passingValidation(), List.of());
        when(dataModelApi.getPhysicalTableList(TABLE)).thenReturn(List.of(physicalTable(TABLE, false)));

        ChangeItem item = createItem();
        dryRunner.enrich(manifest(item), ctx(createNode()));

        ChangeItem.Conflict conflict = item.getConflict();
        assertNotNull(conflict);
        assertEquals(ChangeItem.Conflict.TYPE_TABLE_EXISTS_UNREGISTERED, conflict.getType());
        assertTrue(conflict.getMessage().contains("导入已有表"), conflict.getMessage());
        // 这两种撞车的处置<b>相反</b>：已登记的换名是对的，未登记的换名是错的
        assertFalse(conflict.getMessage().contains("换一个表名"), conflict.getMessage());
        assertEquals(DryRunResult.Outcome.REJECTED, item.getDryRun().getOutcome());
    }

    @Test
    @DisplayName("🔴 keyword 是模糊匹配：探 biz_emp 撞上 biz_employee 不能算撞车")
    void conflict_fuzzyHitIsFilteredOut() {
        stubCreateChain(passingValidation(), List.of());
        when(dataModelApi.getPhysicalTableList(TABLE))
                .thenReturn(List.of(physicalTable("biz_employee", true)));

        ChangeItem item = createItem();
        dryRunner.enrich(manifest(item), ctx(createNode()));

        assertNull(item.getConflict());
        assertEquals(DryRunResult.Outcome.PASSED, item.getDryRun().getOutcome());
        // 这是「查过了、没撞车」，不是「没查成」，故不该留下任何 warning
        assertTrue(item.getDryRun().getWarnings().isEmpty(), item.getDryRun().getWarnings().toString());
    }

    @Test
    @DisplayName("🔴 存在性探测自己失败：conflict 只能是 null，但必须留下一条 warning —— 静默的 null 会被读成「确认过、没撞车」")
    void conflictProbeBroken_leavesAWarningInsteadOfASilentNull() {
        stubCreateChain(passingValidation(), List.of("CREATE TABLE biz_emp (...)"));
        when(dataModelApi.getPhysicalTableList(TABLE)).thenThrow(new InfraDownException("timeout"));

        ChangeItem item = createItem();
        dryRunner.enrich(manifest(item), ctx(createNode()));

        assertNull(item.getConflict());
        DryRunResult result = item.getDryRun();
        // 探测失败不该连累建表干跑本身：那是独立的两次调用，后一次照样探通了
        assertEquals(DryRunResult.Outcome.PASSED, result.getOutcome());
        assertEquals(1, result.getWarnings().size(), result.getWarnings().toString());
        String warning = result.getWarnings().get(0);
        assertTrue(warning.contains("未能确认表 " + TABLE), warning);
        assertTrue(warning.contains("执行时才失败"), warning);
    }

    // ==================== 改表 ====================

    @Test
    @DisplayName("改表探通：PASSED，且走的是改表那一套校验器")
    void tableAlter_passed() {
        DataModelSaveReqDTO reqDTO = new DataModelSaveReqDTO();
        DataModelStructureRespDTO current = new DataModelStructureRespDTO();
        current.setTableName(TABLE);
        when(resolver.resolveUpdate(any(), eq(UPDATE_TOOL))).thenReturn(
                new DataModelParamResolver.UpdateResolution(reqDTO, current,
                        List.of("age"), List.of(), List.of()));
        when(updateValidator.validate(reqDTO, current)).thenReturn(passingValidation());
        when(dataModelApi.previewDdl(reqDTO)).thenReturn(List.of("ALTER TABLE biz_emp ADD COLUMN age int"));

        ChangeItem item = alterItem();
        dryRunner.enrich(manifest(item), ctx(alterNode()));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.PASSED, result.getOutcome());
        assertTrue(result.getReason().contains("1 条 DDL"), result.getReason());
        verify(createValidator, never()).validate(any());
    }

    @Test
    @DisplayName("改表：表没登记进数据模型时 resolveUpdate 抛 ServiceException ⟹ REJECTED")
    void tableAlter_rejectedWhenModelNotFound() {
        when(resolver.resolveUpdate(any(), eq(UPDATE_TOOL))).thenThrow(new ServiceException(400,
                "找不到表「biz_emp」对应的数据模型。先用 datamodel_list 确认表名"));

        ChangeItem item = alterItem();
        dryRunner.enrich(manifest(item), ctx(alterNode()));

        assertEquals(DryRunResult.Outcome.REJECTED, item.getDryRun().getOutcome());
        assertTrue(item.getDryRun().getReason().contains("datamodel_list"), item.getDryRun().getReason());
        verify(dataModelApi, never()).previewDdl(any());
    }

    @Test
    @DisplayName("改表不探撞车：它要的恰恰是「表已经在了」")
    void tableAlter_neverProbesForConflicts() {
        when(resolver.resolveUpdate(any(), eq(UPDATE_TOOL))).thenThrow(new ServiceException(400, "x"));

        ChangeItem item = alterItem();
        dryRunner.enrich(manifest(item), ctx(alterNode()));

        verify(dataModelApi, never()).getPhysicalTableList(anyString());
        assertNull(item.getConflict());
    }

    @Test
    @DisplayName("改表的表名是占位符：同样是 NOT_PROBED，不是 REJECTED")
    void tableAlter_placeholderTableNameIsNotAnAccusation() {
        ChangeItem item = alterItem();
        dryRunner.enrich(manifest(item),
                ctx(node("n1", Map.of(DataModelParamResolver.PARAM_TABLE_NAME, "{{$.input.table}}"))));

        assertEquals(DryRunResult.Outcome.NOT_PROBED, item.getDryRun().getOutcome());
        verify(resolver, never()).resolveUpdate(any(), anyString());
    }

    // ==================== 建表单：只探得到的两步，和一步都不许冤枉 ====================

    @Test
    @DisplayName("🔴 主表在库里不存在：REJECTED，且用的是工具自己那句话 —— 两份文案必然漂移")
    void formCreate_mainTableMissing_isRejectedWithTheToolsOwnWording() {
        ChangeItem item = formCreateItem();

        dryRunner.enrich(manifest(item), ctx(formCreateNode()));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.REJECTED, result.getOutcome());
        // 逐字相等，不是 contains：这句话既是模型的改错提示，也是审批人的拒绝理由
        assertEquals(FormSaveDraftTool.mainTableMissingMessage(TABLE), result.getReason());
        // 主表这一步排在编码那一步前面（与工具 execute:92 → :111 同序），故不必再查编码
        verify(formDesignApi, never()).getFormDesignByCode(anyString());
        assertNull(item.getConflict(), "主表不存在不是「撞车」，是「缺东西」，出路也不同");
    }

    @Test
    @DisplayName("🔴 主表由本流程上游的建表节点建出：不许冤枉这份最标准的 DSL")
    void formCreate_mainTableCreatedByAnUpstreamNode_isNotAccused() {
        stubCreateChain(passingValidation(), List.of());
        ChangeItem create = createItemFor(TABLE);
        ChangeItem form = formCreateItem();

        dryRunner.enrich(manifest(create, form),
                ctx(List.of(edge("n1", "n2")), createNode(), formCreateNode()));

        DryRunResult result = form.getDryRun();
        assertFalse(result.getReason().contains("不存在"), result.getReason());
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getWarnings().isEmpty(), result.getWarnings().toString());
        // 建表单那一支一次库都没查：只有建表项自己那次撞车探测
        verify(dataModelApi, times(1)).getPhysicalTableList(TABLE);
    }

    /**
     * 上一条守的是「上游建表项的表名<b>读得到</b>」。这一条守的是它<b>读不到</b>的时候 ——
     * 整份建表定义写成占位符，抽取器 {@code ManifestArgs.map} 解析失败静默得到空 Map，
     * {@code target.tableName} 于是是 null，那一项进不了 {@code tablesCreatedHere.byName}。
     *
     * <p>🔴 此时「库里查不到这张表」<b>不构成拒绝理由</b>（缺陷 D4-17 的第二条红）：
     * run11 示例 55 里 {@code create_table} 的 model 是 {@code {{$.prepared_args.model}}}，
     * 于是下游 {@code save_draft} 被判「主表 biz_meeting_booking 在数据库里不存在，
     * 请先用 datamodel_create 把它建出来」—— 而那张表恰恰是本流程<b>上一步</b>要建的。
     * 一个根因出两条红，两条都在让用户去改一个没写错的地方。
     */
    @Test
    @DisplayName("🔴 上游建表项的表名定不下来：下游建表单降成 warning，不喊「主表不存在」")
    void formCreate_mainTableCreatedByUpstreamNodeWhoseNameIsPending_isNotAccused() {
        ChangeItem create = createItem();          // target 里没有 tableName：正是抽取器读不到时的样子
        create.setNodeName("创建数据表");
        ChangeItem form = formCreateItem();
        NodeDTO pendingCreateNode = node("n1", Map.of(DataModelParamResolver.PARAM_MODEL,
                "{{$.prepared_args.model}}"));

        dryRunner.enrich(manifest(create, form),
                ctx(List.of(edge("n1", "n2")), pendingCreateNode, formCreateNode()));

        DryRunResult result = form.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertFalse(result.getReason().contains("不存在"), result.getReason());
        // reason 那句「都查过了、没撞上」必须把读者指向警告，否则它与下面这条警告当面打架：
        // 一边说主表查过没撞上、一边说库里现在没有这张表，用户只会挑对自己方便的那句读
        assertTrue(result.getReason().contains("以警告为准"), result.getReason());
        // 降档不等于闭嘴：审批人得知道这一格没复核出来、以及为什么没复核出来
        assertEquals(1, result.getWarnings().size(), result.getWarnings().toString());
        String warning = result.getWarnings().get(0);
        // 报 nodeName 而不是 nodeId，口径与「建表节点走不到建表单节点」那条一致
        assertTrue(warning.contains("创建数据表"), warning);
        assertTrue(warning.contains(TABLE), warning);
        assertTrue(warning.contains("运行期"), warning);
    }

    /**
     * 上一条那条降档<b>必须</b>带可达性前提，否则它会把主表存在性这条判据整体关掉：
     * 另一条分支上建的表救不了这条分支上的表单。
     *
     * <p>与 {@code formCreate_mainTableCreatedByANodeThatDoesNotReachIt_isRejected}
     * 是同一口径的两半 —— 那边表名读得到、这边读不到，但「走不到就照样拒」必须一致，
     * 否则同一张图在两处得到相反的结论。
     */
    @Test
    @DisplayName("🔴 那个建表节点走不到建表单节点：表名定不下来也照样拒，不许拿它当挡箭牌")
    void formCreate_namePendingCreateNodeThatDoesNotReachIt_stillRejected() {
        ChangeItem create = createItem();
        create.setNodeName("创建数据表");
        ChangeItem form = formCreateItem();
        NodeDTO pendingCreateNode = node("n1", Map.of(DataModelParamResolver.PARAM_MODEL,
                "{{$.prepared_args.model}}"));

        // 边反了：n2（建表单）→ n1（建表）
        dryRunner.enrich(manifest(create, form),
                ctx(List.of(edge("n2", "n1")), pendingCreateNode, formCreateNode()));

        DryRunResult result = form.getDryRun();
        assertEquals(DryRunResult.Outcome.REJECTED, result.getOutcome());
        assertEquals(FormSaveDraftTool.mainTableMissingMessage(TABLE), result.getReason());
    }

    @Test
    @DisplayName("🔴 建表节点走不到建表单节点（排在后面 / 在另一条分支上）：照样 REJECTED，并点名是哪个节点")
    void formCreate_mainTableCreatedByANodeThatDoesNotReachIt_isRejected() {
        stubCreateChain(passingValidation(), List.of());
        ChangeItem create = createItemFor(TABLE);
        create.setNodeName("建员工表");
        ChangeItem form = formCreateItem();

        // 边反了：n2（建表单）→ n1（建表）。表要到建表单之后才建出来
        dryRunner.enrich(manifest(create, form),
                ctx(List.of(edge("n2", "n1")), createNode(), formCreateNode()));

        DryRunResult result = form.getDryRun();
        assertEquals(DryRunResult.Outcome.REJECTED, result.getOutcome());
        assertTrue(result.getReason().contains("走不到这个节点"), result.getReason());
        // 报 nodeName 而不是 nodeId：审批人看到「节点 n1」等于没看到
        assertTrue(result.getReason().contains("建员工表"), result.getReason());
        assertTrue(result.getReason().contains(TABLE), result.getReason());
    }

    @Test
    @DisplayName("表单编码已被占用：conflict=FORM_CODE_TAKEN 且同时 REJECTED，文案共用工具那一份")
    void formCreate_codeTaken_setsConflictAndRejects() {
        when(dataModelApi.getPhysicalTableList(TABLE)).thenReturn(List.of(physicalTable(TABLE, true)));
        when(formDesignApi.getFormDesignByCode(FORM_CODE)).thenReturn(existingForm(77L));

        ChangeItem item = formCreateItem();
        dryRunner.enrich(manifest(item), ctx(formCreateNode()));

        ChangeItem.Conflict conflict = item.getConflict();
        assertNotNull(conflict);
        assertEquals(ChangeItem.Conflict.TYPE_FORM_CODE_TAKEN, conflict.getType());
        assertEquals(FormSaveDraftTool.codeTakenMessage(FORM_CODE, 77L), conflict.getMessage());
        assertEquals(DryRunResult.Outcome.REJECTED, item.getDryRun().getOutcome());
        assertEquals(conflict.getMessage(), item.getDryRun().getReason());
    }

    @Test
    @DisplayName("🔴 两步都探通了也不报 PASSED —— 少查一半还报绿，就是把方案 §3-L2 那个假信号再造一遍")
    void formCreate_neverReportsPassedEvenWhenEverythingChecksOut() {
        when(dataModelApi.getPhysicalTableList(TABLE)).thenReturn(List.of(physicalTable(TABLE, true)));
        when(formDesignApi.getFormDesignByCode(FORM_CODE)).thenReturn(null);

        ChangeItem item = formCreateItem();
        dryRunner.enrich(manifest(item), ctx(formCreateNode()));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertFalse(result.getReason().contains("通过"), result.getReason());
        // 说清「查过什么、没查什么」，否则黄色的原因无从判断
        assertTrue(result.getReason().contains("都查过了"), result.getReason());
        assertTrue(result.getReason().contains("字段与列"), result.getReason());
        assertNull(item.getConflict());
    }

    @Test
    @DisplayName("🔴 主表名是占位符：NOT_PROBED + 一条 warning，一次接口都不打")
    void formCreate_placeholderMainTable_isNotAnAccusation() {
        ChangeItem item = formCreateItem();
        NodeDTO node = node("n2", formCreateArgs("{{$.input.table}}", FORM_CODE));

        dryRunner.enrich(manifest(item), ctx(node));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        verify(dataModelApi, never()).getPhysicalTableList(anyString());
        assertEquals(1, result.getWarnings().size(), result.getWarnings().toString());
        assertTrue(result.getWarnings().get(0).contains("{{$.input.table}}"), result.getWarnings().toString());
    }

    @Test
    @DisplayName("主表存在性探测自己失败：warning 而不是 REJECTED —— 口径照工具的 fail-soft")
    void formCreate_mainTableProbeBroken_leavesAWarningNotARejection() {
        when(dataModelApi.getPhysicalTableList(TABLE)).thenThrow(new InfraDownException("timeout"));

        ChangeItem item = formCreateItem();
        dryRunner.enrich(manifest(item), ctx(formCreateNode()));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertEquals(1, result.getWarnings().size(), result.getWarnings().toString());
        assertTrue(result.getWarnings().get(0).contains("未能确认主表 " + TABLE), result.getWarnings().toString());
    }

    @Test
    @DisplayName("编码占用探测自己失败：同样是 warning 而不是 REJECTED")
    void formCreate_codeProbeBroken_leavesAWarningNotARejection() {
        when(dataModelApi.getPhysicalTableList(TABLE)).thenReturn(List.of(physicalTable(TABLE, true)));
        when(formDesignApi.getFormDesignByCode(FORM_CODE)).thenThrow(new InfraDownException("timeout"));

        ChangeItem item = formCreateItem();
        dryRunner.enrich(manifest(item), ctx(formCreateNode()));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertNull(item.getConflict());
        assertEquals(1, result.getWarnings().size(), result.getWarnings().toString());
        assertTrue(result.getWarnings().get(0).contains("未能确认表单编码 " + FORM_CODE),
                result.getWarnings().toString());
    }

    @Test
    @DisplayName("表单编码是占位符：跳过占用查询，落一条 warning")
    void formCreate_pendingCode_skipsTheLookup() {
        when(dataModelApi.getPhysicalTableList(TABLE)).thenReturn(List.of(physicalTable(TABLE, true)));
        ChangeItem item = formCreateItem();

        dryRunner.enrich(manifest(item), ctx(node("n2", formCreateArgs(TABLE, "{{$.input.code}}"))));

        verify(formDesignApi, never()).getFormDesignByCode(anyString());
        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getWarnings().get(0).contains("没能确认它是否已被占用"),
                result.getWarnings().toString());
    }

    // ==================== 发布表单：体检 + 菜单预演 ====================

    @Test
    @DisplayName("🔴 体检发现问题：REJECTED，理由与发布时抛的那句逐字相同（连「；」分隔都一样）")
    void formPublish_inspectProblems_becomeTheRejectionVerbatim() {
        List<String> problems = List.of("主表没有绑定", "字段 age 的控件类型不支持");
        when(formDesignApi.inspectFormDesign(FORM_ID)).thenReturn(problems);

        ChangeItem item = formPublishItem();
        dryRunner.enrich(manifest(item), ctx(formPublishNode(publishArgs())));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.REJECTED, result.getOutcome());
        assertEquals(String.join("；", problems), result.getReason());
        assertEquals(problems, result.getEvidence().get("inspectIssues"));
        // 发布已经注定失败，菜单预演不必再打
        verify(formDesignApi, never()).previewPublishMenu(any(), any());
    }

    @Test
    @DisplayName("🔴 首发：把「会建出什么菜单」填进 detail，条数算出来而不是写死 8")
    void formPublish_firstPublish_fillsMenuAndPermissions() {
        when(formDesignApi.inspectFormDesign(FORM_ID)).thenReturn(List.of());
        // 刻意给 3 个而不是真实的 7 个：写死「共 8 条菜单记录」的实现会在这里露出来
        when(formDesignApi.previewPublishMenu(FORM_ID, PARENT_MENU_ID))
                .thenReturn(preview(true, null, menuConfig("员工信息", "/infra/employee", 3)));

        ChangeItem item = formPublishItem();
        dryRunner.enrich(manifest(item), ctx(formPublishNode(publishArgs())));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.PASSED, result.getOutcome());
        assertEquals("体检通过。这次发布会新建 1 个菜单，含 3 个按钮权限，共 4 条菜单记录", result.getReason());
        assertEquals(3, result.getEvidence().get("permissionCount"));
        // detail.menu 必须是 Map 而不是 POJO：清单要冻进 BPM 流程变量，读回来是 Map
        Map<?, ?> menu = assertInstanceOf(Map.class, item.getDetail().get("menu"));
        assertEquals("员工信息", menu.get("name"));
        assertEquals("/infra/employee", menu.get("path"));
        assertEquals(3, menu.get("permissionCount"));
        // builder 的 countPermissions 就靠这一份求和出 headline 那句「含 N 个按钮权限」
        assertEquals(List.of("infra:form-design-run:employee:query",
                "infra:form-design-run:employee:create",
                "infra:form-design-run:employee:update"), item.getDetail().get("permissions"));
    }

    @Test
    @DisplayName("🔴 再发布：绝不声称会新建菜单，也绝不写 detail.permissions")
    void formPublish_rePublish_neverClaimsNewMenus() {
        when(formDesignApi.inspectFormDesign(FORM_ID)).thenReturn(List.of());
        when(formDesignApi.previewPublishMenu(FORM_ID, PARENT_MENU_ID))
                .thenReturn(preview(false, 99L, null));

        ChangeItem item = formPublishItem();
        dryRunner.enrich(manifest(item), ctx(formPublishNode(publishArgs())));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.PASSED, result.getOutcome());
        String menu = assertInstanceOf(String.class, item.getDetail().get("menu"));
        assertTrue(menu.contains("不新建菜单"), menu);
        assertTrue(menu.contains("#99"), menu);
        // 写了 permissions，headline 就会说「含 N 个按钮权限」—— 而这次一个按钮都不建
        assertFalse(item.getDetail().containsKey("permissions"), item.getDetail().toString());
        assertTrue(result.getReason().contains("不新建菜单"), result.getReason());
        assertEquals(99L, result.getEvidence().get("existingMenuId"));
    }

    @Test
    @DisplayName("🔴 表单编号是占位符（发布的正是上一步刚建的表单）：NOT_PROBED，一次接口都不打")
    void formPublish_pendingFormId_isNotAnAccusation() {
        ChangeItem item = formPublishItem();
        Map<String, Object> args = publishArgs();
        args.put(FormPublishBundleTool.PARAM_ID, "{{$.save_form.tool_result.id}}");

        dryRunner.enrich(manifest(item), ctx(formPublishNode(args)));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getReason().contains("{{$.save_form.tool_result.id}}"), result.getReason());
        // 占位符若被喂进解析器，得到的是「id 必须是数字」—— 一份合法 DSL 被判成 REJECTED
        verify(formDesignApi, never()).inspectFormDesign(anyLong());
        verify(formDesignApi, never()).previewPublishMenu(any(), any());
    }

    /**
     * 🔴 args <b>整体</b>写成占位符：REJECTED，且理由说的是执行期一定会发生的那件事
     *
     * <p>缺陷 D4-14，2026-10-04 run8 实测。模型为了让前置 script 节点替它拼参数表，
     * 写出了 {@code "args": "{{$.publish_bundle_input}}"}（示例 55 与 59 各一次，
     * 节点都是 {@code do_publish}，后端日志 18:05:55.461 / 18:07:09.911）。
     * 修复前 {@code ManifestArgs.of} 把 {@code configMap} 的 null 原样透传，
     * {@code resolveFormId} 第一行 NPE，被 {@code probeSafely} 吞成 WARN，
     * 而<b>原始 Java 异常文案</b>反倒进了用户可见的清单 ——
     * 一个 HIGH 风险项在点「同意」之前就这么失去了复核。
     *
     * <p>结论必须是 REJECTED 而不是 NOT_PROBED：{@code ToolNodeExecutor} 拿到非对象的 args
     * 会跳过整个参数装配、以<b>空参数表</b>去调工具，那个工具必然报缺少参数。
     * 这不是「探不出来」，是「探得出来、且结论是跑不通」；
     * 说「本次没探」会让审批人放过一项注定失败的高风险变更。
     *
     * <p>⚠️ 构造样本走 {@code ManifestTestFixtures.toolNode} 而<b>不是</b>本类的
     * {@link #node(String, Map)}：后者的形参就写死了 {@code Map}，压根表达不出
     * 「args 是个字符串」这个形状 —— 而这正是要测的那件事。
     */
    @Test
    @DisplayName("🔴 args 整体是个占位符：REJECTED + 说清执行期会以空参数表调工具，一次接口都不打")
    void formPublish_argsAsWholePlaceholder_isRejectedBeforeAnyProbe() {
        NodeDTO node = ManifestTestFixtures.toolNode("n1", PUBLISH_TOOL,
                "\"{{$.publish_bundle_input}}\"");
        ChangeItem item = formPublishItem();

        dryRunner.enrich(manifest(item), ctx(node));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.REJECTED, result.getOutcome());
        // 原样引回模型写的那串，用户才知道要改哪儿；不许只说「参数不合法」
        assertTrue(result.getReason().contains("{{$.publish_bundle_input}}"), result.getReason());
        assertTrue(result.getReason().contains("空参数表"), result.getReason());
        // 修法要给出来，否则这句话只是把人拦下、没指路
        assertTrue(result.getReason().contains("outputVar"), result.getReason());
        // 🔴 绝不能再出现那句原始 Java 异常文案 —— 那正是 D4-14 的病征
        assertFalse(result.getReason().contains("NullPointerException"), result.getReason());
        assertFalse(result.getReason().contains("未能完成干跑"), result.getReason());
        verify(formDesignApi, never()).inspectFormDesign(anyLong());
        verify(formDesignApi, never()).previewPublishMenu(any(), any());
        verify(menuApi, never()).getEnabledDirectoryList();
    }

    /**
     * 同一条判据换个变更类型也得拦住：五个 probe 开头都是同一句 {@code ManifestArgs.of(node)}，
     * 所以这道检查放在 {@code probeSafely} 的 switch <b>之前</b>，一处生效、五处覆盖。
     * 拿建表这一支复验，钉的就是「不必写五遍、也没有哪一份漏」。
     */
    @Test
    @DisplayName("🔴 args 整体是个字符串（建表那一支）：同样 REJECTED，不走建表预演")
    void tableCreate_argsAsWholeString_isRejectedToo() {
        NodeDTO node = ManifestTestFixtures.toolNode("n1", CREATE_TOOL, "\"biz_emp_json\"");
        ChangeItem item = createItem();

        dryRunner.enrich(manifest(item), ctx(node));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.REJECTED, result.getOutcome());
        assertTrue(result.getReason().contains("biz_emp_json"), result.getReason());
        verify(createValidator, never()).validate(any());
        verify(dataModelApi, never()).getPhysicalTableList(anyString());
    }

    /**
     * 「压根没写 args」是<b>另一种</b>退化形态，不该被上面那条判据收走
     *
     * <p>{@code ManifestTestFixtures.toolNode} 的 javadoc 特意把这两种形态分开点名，
     * 因为它们的正确说法不同：args 是字符串，说的是「你写错了形状，执行器会当空参数表」；
     * args 键不存在，说的就是解析器那句「缺少 id（表单编号）」—— 后者才是这份 DSL 的真实毛病。
     * 让新判据把后者也一并吞掉，用户会看到一句与自己 DSL 无关的指引。
     */
    @Test
    @DisplayName("没有 args 这个键：仍按解析器自己的话拒（缺少 id），不套「空参数表」那句")
    void formPublish_noArgsKeyAtAll_keepsTheResolversOwnWording() {
        NodeDTO node = ManifestTestFixtures.toolNode("n1", PUBLISH_TOOL, (String) null);
        ChangeItem item = formPublishItem();

        dryRunner.enrich(manifest(item), ctx(node));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.REJECTED, result.getOutcome());
        assertTrue(result.getReason().contains("缺少 " + FormPublishBundleTool.PARAM_ID), result.getReason());
        assertFalse(result.getReason().contains("空参数表"), result.getReason());
        verify(formDesignApi, never()).inspectFormDesign(anyLong());
    }

    @Test
    @DisplayName("🔴 上级菜单名写错：用的是解析器那句话（含可选目录清单），且排在体检之前")
    void formPublish_unknownParentMenuName_isRejectedWithTheResolversOwnWording() {
        when(menuApi.getEnabledDirectoryList()).thenReturn(List.of(dir(PARENT_MENU_ID, "人事管理")));
        Map<String, Object> args = publishArgs();
        args.remove(FormPublishBundleTool.PARAM_PARENT_MENU_ID);
        args.put(FormPublishBundleTool.PARAM_PARENT_MENU_NAME, "人事管理理");

        ChangeItem item = formPublishItem();
        dryRunner.enrich(manifest(item), ctx(formPublishNode(args)));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.REJECTED, result.getOutcome());
        assertTrue(result.getReason().contains("找不到名为「人事管理理」的启用目录"), result.getReason());
        assertTrue(result.getReason().contains("人事管理"), result.getReason());
        verify(formDesignApi, never()).inspectFormDesign(anyLong());
    }

    @Test
    @DisplayName("上级菜单是占位符：体检照做、菜单预演跳过，仍然 PASSED + 一条 warning")
    void formPublish_pendingParentMenuName_stillInspectsButSkipsTheMenuPreview() {
        when(formDesignApi.inspectFormDesign(FORM_ID)).thenReturn(List.of());
        Map<String, Object> args = publishArgs();
        args.remove(FormPublishBundleTool.PARAM_PARENT_MENU_ID);
        args.put(FormPublishBundleTool.PARAM_PARENT_MENU_NAME, "{{$.input.menu}}");

        ChangeItem item = formPublishItem();
        dryRunner.enrich(manifest(item), ctx(formPublishNode(args)));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.PASSED, result.getOutcome());
        assertEquals("体检通过，可以发布", result.getReason());
        assertTrue(result.getWarnings().get(0).contains("没能预演菜单会建成什么样"),
                result.getWarnings().toString());
        verify(formDesignApi, never()).previewPublishMenu(any(), any());
        // 反查目录是拿字面量去比名字，占位符一定比不中，故一次都不该打
        verify(menuApi, never()).getEnabledDirectoryList();
    }

    @Test
    @DisplayName("表单不存在：REJECTED，理由就是发布时会抛的那句")
    void formPublish_formNotFound_isRejected() {
        when(formDesignApi.inspectFormDesign(FORM_ID))
                .thenThrow(new ServiceException(1_002_008_000, "表单不存在"));

        ChangeItem item = formPublishItem();
        dryRunner.enrich(manifest(item), ctx(formPublishNode(publishArgs())));

        assertEquals(DryRunResult.Outcome.REJECTED, item.getDryRun().getOutcome());
        assertEquals("表单不存在", item.getDryRun().getReason());
    }

    @Test
    @DisplayName("体检自己没探成：NOT_PROBED，绝不是 PASSED")
    void formPublish_inspectBroken_isNotProbed() {
        when(formDesignApi.inspectFormDesign(FORM_ID)).thenThrow(new InfraDownException("link failure"));

        ChangeItem item = formPublishItem();
        dryRunner.enrich(manifest(item), ctx(formPublishNode(publishArgs())));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getReason().contains("InfraDownException"), result.getReason());
        assertTrue(result.getReason().contains("本次未校验"), result.getReason());
        verify(formDesignApi, never()).previewPublishMenu(any(), any());
    }

    @Test
    @DisplayName("菜单预演没探成：仍然 PASSED —— 那里的探测是判据，这里的只是说明")
    void formPublish_menuPreviewBroken_stillPasses() {
        when(formDesignApi.inspectFormDesign(FORM_ID)).thenReturn(List.of());
        when(formDesignApi.previewPublishMenu(FORM_ID, PARENT_MENU_ID))
                .thenThrow(new InfraDownException("timeout"));

        ChangeItem item = formPublishItem();
        dryRunner.enrich(manifest(item), ctx(formPublishNode(publishArgs())));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.PASSED, result.getOutcome());
        assertEquals("体检通过，可以发布", result.getReason());
        assertTrue(result.getWarnings().get(0).contains("菜单预演没探成"), result.getWarnings().toString());
        assertFalse(item.getDetail().containsKey("menu"), item.getDetail().toString());
    }

    @Test
    @DisplayName("parentMenuId 走的也是同一个解析器：干跑预演的编号 = 真发布用的编号")
    void formPublish_parentMenuIdResolvedThroughTheSameResolver() {
        when(formDesignApi.inspectFormDesign(FORM_ID)).thenReturn(List.of());
        when(formDesignApi.previewPublishMenu(FORM_ID, PARENT_MENU_ID))
                .thenReturn(preview(true, null, menuConfig("员工信息", "/infra/employee", 7)));
        Map<String, Object> args = publishArgs();
        // 模型常把数字写成字符串，解析器要认，干跑也就必须认
        args.put(FormPublishBundleTool.PARAM_PARENT_MENU_ID, "2600");

        ChangeItem item = formPublishItem();
        dryRunner.enrich(manifest(item), ctx(formPublishNode(args)));

        verify(formDesignApi).previewPublishMenu(FORM_ID, PARENT_MENU_ID);
        assertEquals(DryRunResult.Outcome.PASSED, item.getDryRun().getOutcome());
        assertTrue(item.getDryRun().getReason().contains("共 8 条菜单记录"), item.getDryRun().getReason());
        verify(menuApi, never()).getEnabledDirectoryList();
    }

    // ==================== 部署审批流：唯一「部署成功也可能不是你要的」那一类 ====================

    @Test
    @DisplayName("部署探通：PASSED，deployMode 说清是新建，理由带「尚未执行」")
    void processDeploy_passed() {
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(deployArgs(PROCESS_KEY, VALID_TREE, FORM_CODE))));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.PASSED, result.getOutcome());
        assertEquals("预演通过：节点树合法、主表单可用于流程；这些改动尚未执行", result.getReason());
        assertTrue(result.getWarnings().isEmpty(), result.getWarnings().toString());
        assertEquals("新建一条流程（当前没有同标识的流程模型）", item.getDetail().get("deployMode"));
        // 撞 key 就覆盖是工具设计好的行为，出路不是「换名」，故不是 conflict
        assertNull(item.getConflict());
    }

    @Test
    @DisplayName("🔴 缺结束节点：REJECTED，用校验器的原话，不套 toFeedback 那套编号")
    void processDeploy_invalidTree_isRejectedWithTheValidatorsOwnWords() {
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item),
                ctx(deployNode(deployArgs(PROCESS_KEY, TREE_WITHOUT_END, FORM_CODE))));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.REJECTED, result.getOutcome());
        assertTrue(result.getReason().contains("结束节点"), result.getReason());
        // toFeedback 是给 LLM 自修正看的格式，清单是给人看的
        assertFalse(result.getReason().startsWith("以下问题必须修正"), result.getReason());
        assertInstanceOf(List.class, result.getEvidence().get("treeIssues"));
        // 树都不合法就不必再问「是新建还是覆盖」：白打一次接口，还把两句话挤在一个 reason 里
        verify(bpmModelApi, never()).getModelList(any());
        verify(formDesignApi, never()).validateFlowReady(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("🔴 approveType 不等于 1 必须硬拦：写错时部署照样成功，但候选人/审批方式全被静默丢弃")
    void processDeploy_wrongApproveType_isRejectedNotWarned() {
        String tree = VALID_TREE.replace("\"approveType\":1", "\"approveType\":2");
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(deployArgs(PROCESS_KEY, tree, FORM_CODE))));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.REJECTED, result.getOutcome());
        assertTrue(result.getReason().contains("approveType"), result.getReason());
        assertTrue(result.getWarnings().isEmpty(), "这条不能降级成警告：" + result.getWarnings());
    }

    @Test
    @DisplayName("🔴 首个审批节点用「审批人自选」：REJECTED —— 证明第二步 validateFirstApproveNode 真的被调了")
    void processDeploy_firstApproveNodeSelfSelect_isRejected() {
        String tree = VALID_TREE.replace("\"candidateStrategy\":30", "\"candidateStrategy\":34");
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(deployArgs(PROCESS_KEY, tree, FORM_CODE))));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.REJECTED, result.getOutcome());
        assertTrue(result.getReason().contains("第一个审批节点"), result.getReason());
    }

    @Test
    @DisplayName("校验器的警告要一并带出来：没有审批节点的流程发起后立刻结束")
    void processDeploy_carriesTheValidatorsWarnings() {
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item),
                ctx(deployNode(deployArgs(PROCESS_KEY, TREE_WITHOUT_APPROVE, FORM_CODE))));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.PASSED, result.getOutcome());
        assertEquals(1, result.getWarnings().size(), result.getWarnings().toString());
        assertTrue(result.getWarnings().get(0).contains("没有任何审批"), result.getWarnings().toString());
    }

    @Test
    @DisplayName("🔴 key 已有流程：deployMode 说「覆盖」，并警告整棵树会被替换、旧版会挂起、要管理员")
    void processDeploy_overwriteExistingModel() {
        when(bpmModelApi.getModelList(any())).thenReturn(List.of(model(PROCESS_KEY, "采购申请审批")));
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(deployArgs(PROCESS_KEY, VALID_TREE, FORM_CODE))));

        assertEquals("覆盖已有流程「采购申请审批」并发新版本", item.getDetail().get("deployMode"));
        DryRunResult result = item.getDryRun();
        assertEquals(1, result.getWarnings().size(), result.getWarnings().toString());
        String warning = result.getWarnings().get(0);
        assertTrue(warning.contains("整体替换"), warning);
        assertTrue(warning.contains("挂起"), warning);
        assertTrue(warning.contains("管理员"), warning);
        // 覆盖是合法的，不该把这一项染红；也不是 conflict（出路不是换名）
        assertEquals(DryRunResult.Outcome.PASSED, result.getOutcome());
        assertNull(item.getConflict());
    }

    @Test
    @DisplayName("🔴 只按 key 匹配、不按 name：同名的另一条流程不能让它变成「覆盖」")
    void processDeploy_matchesByKeyOnly_notByName() {
        // ExplainProcessTool 那个「key 或 name 命中」的写法搬过来就会在这条用例上翻车：
        // 后端 getModelByKey 是 modelKey(key) 精确匹配，多比一个 name 就等于凭空说「要覆盖一条正在用的流程」
        when(bpmModelApi.getModelList(any())).thenReturn(List.of(model("other_key", PROCESS_KEY)));
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(deployArgs(PROCESS_KEY, VALID_TREE, FORM_CODE))));

        assertEquals("新建一条流程（当前没有同标识的流程模型）", item.getDetail().get("deployMode"));
        assertTrue(item.getDryRun().getWarnings().isEmpty(), item.getDryRun().getWarnings().toString());
    }

    @Test
    @DisplayName("🔴 模型查询自己失败：只能说「未能确认」，不能悄悄填「新建」—— 两者界面上长得一样而含义相反")
    void processDeploy_modelQueryBroken_neverSilentlySaysNew() {
        when(bpmModelApi.getModelList(any())).thenThrow(new InfraDownException("timeout"));
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(deployArgs(PROCESS_KEY, VALID_TREE, FORM_CODE))));

        assertNull(item.getDetail().get("deployMode"), "查不出来时填「新建」就是撒谎");
        DryRunResult result = item.getDryRun();
        assertEquals(1, result.getWarnings().size(), result.getWarnings().toString());
        assertTrue(result.getWarnings().get(0).contains("未能确认标识 " + PROCESS_KEY),
                result.getWarnings().toString());
        // 这一次探测失败不连累其余各项：节点树与主表单都探通了
        assertEquals(DryRunResult.Outcome.PASSED, result.getOutcome());
    }

    @Test
    @DisplayName("分类没启用：警告逐字等于工具自己那句话 —— 文案单源，抄一份进断言就成了第二份口径")
    void processDeploy_categoryNotEnabled_warnsWithTheToolsOwnWording() {
        Map<String, Object> args = deployArgs(PROCESS_KEY, VALID_TREE, FORM_CODE);
        args.put(ProcessCreateAndDeployTool.PARAM_CATEGORY, "finance");
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(args)));

        DryRunResult result = item.getDryRun();
        // 分类回落不是拒绝：部署照样成功，只是流程被挂到另一个分类下
        assertEquals(DryRunResult.Outcome.PASSED, result.getOutcome());
        assertEquals(List.of(ProcessCreateAndDeployTool.categoryFallbackWarning("finance", "oa")),
                result.getWarnings());
    }

    @Test
    @DisplayName("分类启用中 / 平台一个分类都没配：都不给警告 —— 后者不存在「被悄悄改掉」这件事")
    void processDeploy_categoryFine_noWarning() {
        ChangeItem enabled = deployItem();
        dryRunner.enrich(manifest(enabled),
                ctx(deployNode(deployArgs(PROCESS_KEY, VALID_TREE, FORM_CODE))));
        // 🔴 结论断言必须排在警告断言之前：干跑器自己出错时会落 NOT_PROBED 且<b>不带</b>警告，
        //    于是只断言「警告为空」的话，「这一支崩了」与「这一支探通了、没什么可警告」长得一模一样。
        //    变异探针 J（去掉「一条分类都没配就不回落」那半个条件）正是从这个缝里溜过去的
        assertEquals(DryRunResult.Outcome.PASSED, enabled.getDryRun().getOutcome());
        assertTrue(enabled.getDryRun().getWarnings().isEmpty(),
                enabled.getDryRun().getWarnings().toString());

        // 一条分类都没配时工具原样落库，警告会是假的
        when(bpmCategoryApi.getEnabledCategoryCodes()).thenReturn(List.of());
        Map<String, Object> args = deployArgs(PROCESS_KEY, VALID_TREE, FORM_CODE);
        args.put(ProcessCreateAndDeployTool.PARAM_CATEGORY, "finance");
        ChangeItem none = deployItem();
        dryRunner.enrich(manifest(none), ctx(deployNode(args)));
        assertEquals(DryRunResult.Outcome.PASSED, none.getDryRun().getOutcome());
        assertTrue(none.getDryRun().getWarnings().isEmpty(), none.getDryRun().getWarnings().toString());
    }

    @Test
    @DisplayName("分类探测自己失败：留一条警告，不变红")
    void processDeploy_categoryProbeBroken_leavesAWarning() {
        when(bpmCategoryApi.getEnabledCategoryCodes()).thenThrow(new InfraDownException("bpm down"));
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(deployArgs(PROCESS_KEY, VALID_TREE, FORM_CODE))));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.PASSED, result.getOutcome());
        assertEquals(1, result.getWarnings().size(), result.getWarnings().toString());
        assertTrue(result.getWarnings().get(0).contains("未能确认流程分类"), result.getWarnings().toString());
    }

    @Test
    @DisplayName("🔴 主表单不可用于流程：REJECTED，冒号后面一字不改是 infra 的原话")
    void processDeploy_mainFormNotReady_isRejectedWithInfrasVerbatimReason() {
        String reason = "主表(biz_emp)缺少 process_instance_id 列，请补加 varchar(64) 列";
        when(formDesignApi.validateFlowReady(FORM_CODE, true)).thenReturn(reason);
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(deployArgs(PROCESS_KEY, VALID_TREE, FORM_CODE))));

        // 前缀不得不自己写（bpm 的 ONLINE_FORM_NOT_READY 长在 enums/ 里，跨模块只准走 api/ 层），
        // 但后半句必须是 infra 的原话，否则就有了第二份「究竟哪一列不合格」的口径
        assertEquals(DryRunResult.Outcome.REJECTED, item.getDryRun().getOutcome());
        assertEquals("在线表单 " + FORM_CODE + " 不可用于流程：" + reason, item.getDryRun().getReason());
        // 🔴 第二个参数必须是 true：主表单要同时有 process_instance_id 与 process_status 两列，
        //    传 false 只查前一列，于是「主表缺 process_status」会被报成 PASSED
        verify(formDesignApi).validateFlowReady(FORM_CODE, true);
        verify(formDesignApi, never()).validateFlowReady(FORM_CODE, false);
    }

    @Test
    @DisplayName("🔴 主表单探测自己失败：NOT_PROBED，绝不是 PASSED")
    void processDeploy_mainFormProbeBroken_isNotProbed() {
        when(formDesignApi.validateFlowReady(anyString(), anyBoolean()))
                .thenThrow(new InfraDownException("timeout"));
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(deployArgs(PROCESS_KEY, VALID_TREE, FORM_CODE))));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getReason().contains("InfraDownException"), result.getReason());
        assertFalse(result.getReason().contains("预演通过"), result.getReason());
    }

    @Test
    @DisplayName("主表单编码是占位符：NOT_PROBED，但其余各项照探（deployMode 已填）")
    void processDeploy_pendingFormCode_isNotAnAccusation() {
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item),
                ctx(deployNode(deployArgs(PROCESS_KEY, VALID_TREE, "{{$.save_draft.code}}"))));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getReason().contains("{{$.save_draft.code}}"), result.getReason());
        verify(formDesignApi, never()).validateFlowReady(anyString(), anyBoolean());
        assertEquals("新建一条流程（当前没有同标识的流程模型）", item.getDetail().get("deployMode"));
    }

    @Test
    @DisplayName("🔴 绑普通表单（formId）：NOT_PROBED —— 探不到就绝不报绿")
    void processDeploy_normalFormPath_isNotProbed() {
        Map<String, Object> args = deployArgs(PROCESS_KEY, VALID_TREE, null);
        args.put(ProcessCreateAndDeployTool.PARAM_FORM_ID, "5");
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(args)));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getReason().contains("普通表单"), result.getReason());
        verify(formDesignApi, never()).validateFlowReady(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("🔴 节点上还配了独立表单：降级成 NOT_PROBED —— 那一步部署时才逐个校验")
    void processDeploy_nodeLevelForm_downgradesToNotProbed() {
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item),
                ctx(deployNode(deployArgs(PROCESS_KEY, TREE_WITH_NODE_FORM, FORM_CODE))));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getReason().contains("独立表单"), result.getReason());
        // 主表单是探通了的：降级不等于什么都不查
        verify(formDesignApi).validateFlowReady(FORM_CODE, true);
    }

    @Test
    @DisplayName("🔴 流程标识是占位符：NOT_PROBED，且一次接口都不打")
    void processDeploy_pendingKey_isNotAnAccusation() {
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item),
                ctx(deployNode(deployArgs("{{$.input.key}}", VALID_TREE, FORM_CODE))));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getReason().contains("{{$.input.key}}"), result.getReason());
        // 占位符 key 送去 validateKey 一定会被按「不是合法的流程标识」拒 ⟹ 守卫必须排在校验之前
        verify(bpmModelApi, never()).getProcessConstraints();
        verify(bpmModelApi, never()).getModelList(any());
        verify(bpmCategoryApi, never()).getEnabledCategoryCodes();
        verify(formDesignApi, never()).validateFlowReady(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("🔴 节点树是占位符：NOT_PROBED —— 送去归一化一定会被按「不是合法 JSON」拒")
    void processDeploy_pendingSimpleModel_isNotAnAccusation() {
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item),
                ctx(deployNode(deployArgs(PROCESS_KEY, "{{$.input.tree}}", FORM_CODE))));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getReason().contains("{{$.input.tree}}"), result.getReason());
        verify(bpmModelApi, never()).getProcessConstraints();
    }

    @Test
    @DisplayName("节点树不是合法 JSON：REJECTED 且是工具那句话，绝不退化成「根节点 type 必须是 10」")
    void processDeploy_brokenJson_isRejectedWithTheToolsOwnWording() {
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item),
                ctx(deployNode(deployArgs(PROCESS_KEY, "这不是 JSON", FORM_CODE))));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.REJECTED, result.getOutcome());
        assertTrue(result.getReason().contains("simpleModel 不是合法的 JSON 对象"), result.getReason());
        // 换成「解析不出来就当空对象」的归一化，报出来的就会是下面这句 —— 指着一个不存在的问题让人去改
        assertFalse(result.getReason().contains("根节点的 type"), result.getReason());
    }

    @Test
    @DisplayName("simpleModel 整个没给：REJECTED，同样是工具那句话（归一化共用一份顺带带来的）")
    void processDeploy_missingSimpleModel_isRejected() {
        Map<String, Object> args = deployArgs(PROCESS_KEY, VALID_TREE, FORM_CODE);
        args.remove(ProcessCreateAndDeployTool.PARAM_SIMPLE_MODEL);
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(args)));

        assertEquals(DryRunResult.Outcome.REJECTED, item.getDryRun().getOutcome());
        assertTrue(item.getDryRun().getReason().contains("缺少 simpleModel（流程节点树）"),
                item.getDryRun().getReason());
    }

    @Test
    @DisplayName("🔴 流程约束取不回来：NOT_PROBED，不是 PASSED 也不是 REJECTED")
    void processDeploy_constraintsUnavailable_isNotProbed() {
        when(bpmModelApi.getProcessConstraints()).thenThrow(new InfraDownException("bpm down"));
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(deployArgs(PROCESS_KEY, VALID_TREE, FORM_CODE))));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getReason().contains("InfraDownException"), result.getReason());
        assertTrue(result.getReason().contains("本次未校验"), result.getReason());
        verify(bpmModelApi, never()).getModelList(any());
    }

    @Test
    @DisplayName("🔴 探测顺序：树（两步）→ 查 key → 分类 → 主表单。查 key 刻意提到表单之前，好让「这是覆盖」不被短路掉")
    void processDeploy_probesInTheDocumentedOrder() {
        ChangeItem item = deployItem();

        dryRunner.enrich(manifest(item), ctx(deployNode(deployArgs(PROCESS_KEY, VALID_TREE, FORM_CODE))));

        InOrder order = inOrder(bpmModelApi, bpmCategoryApi, formDesignApi);
        // 两次：validate 与 validateFirstApproveNode 各取一次约束 —— 少调第二步这里就只有一次
        order.verify(bpmModelApi, times(2)).getProcessConstraints();
        order.verify(bpmModelApi).getModelList(null);
        order.verify(bpmCategoryApi).getEnabledCategoryCodes();
        order.verify(formDesignApi).validateFlowReady(FORM_CODE, true);
        order.verifyNoMoreInteractions();
        assertEquals(DryRunResult.Outcome.PASSED, item.getDryRun().getOutcome());
    }

    // ==================== 探不了的，要老实说探不了 ====================

    @Test
    @DisplayName("通用工具：NOT_PROBED，理由说清是「平台无从预演」而不是「没问题」")
    void genericTool_isNotProbedWithAnHonestReason() {
        ChangeItem item = item(ChangeKind.GENERIC_TOOL, "n1", "custom_tool");

        dryRunner.enrich(manifest(item), ctx(node("n1", Map.of())));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getReason().contains("运行期"), result.getReason());
        assertFalse(result.getReason().contains("通过"), result.getReason());
        verify(dataModelApi, never()).previewDdl(any());
        verify(dataModelApi, never()).getPhysicalTableList(anyString());
        verify(resolver, never()).resolveCreate(any(), anyString());
    }

    @Test
    @DisplayName("还没有干跑接口的类型：NOT_PROBED，且点名是哪一类没校验（本类型将来配上探测时，把用例换成那时还没配的那一类）")
    void kindWithoutAProbe_saysWhichKindWasSkipped() {
        ChangeItem item = item(ChangeKind.TABLE_REGISTER, "n1", "datamodel_import");

        dryRunner.enrich(manifest(item), ctx(node("n1", Map.of())));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getReason().contains(ChangeKind.TABLE_REGISTER.getLabel()), result.getReason());
        assertTrue(result.getReason().contains("还没有干跑接口"), result.getReason());
    }

    @Test
    @DisplayName("找不到节点：NOT_PROBED，而不是让整份清单 NPE 消失")
    void missingNode_isNotProbed() {
        ChangeItem item = createItem();
        item.setNodeId("不存在的节点");

        dryRunner.enrich(manifest(item), ctx(createNode()));

        DryRunResult result = item.getDryRun();
        assertEquals(DryRunResult.Outcome.NOT_PROBED, result.getOutcome());
        assertTrue(result.getReason().contains("不存在的节点"), result.getReason());
        verify(dataModelApi, never()).previewDdl(any());
        verify(resolver, never()).resolveCreate(any(), anyString());
    }

    @Test
    @DisplayName("变更类型没定下来：NOT_PROBED")
    void nullKind_isNotProbed() {
        ChangeItem item = new ChangeItem();
        item.setNodeId("n1");

        dryRunner.enrich(manifest(item), ctx(createNode()));

        assertEquals(DryRunResult.Outcome.NOT_PROBED, item.getDryRun().getOutcome());
    }

    @Test
    @DisplayName("空清单与 null 清单：直接返回，不打任何接口")
    void emptyOrNullManifestIsANoOp() {
        dryRunner.enrich(null, ctx(createNode()));
        dryRunner.enrich(new ChangeManifest(), ctx(createNode()));

        verify(dataModelApi, never()).previewDdl(any());
        verify(dataModelApi, never()).getPhysicalTableList(anyString());
        verify(resolver, never()).resolveCreate(any(), anyString());
        verify(resolver, never()).resolveUpdate(any(), anyString());
    }

    // ==================== enrich 的边界 ====================

    @Test
    @DisplayName("🔴 三态各自落在正确的项上；且干跑器不碰 stats（汇总归 builder，只有它知道裁掉了什么）")
    void outcomesStaySeparate_andStatsAreLeftToTheBuilder() {
        stubCreateChainEchoingTableName();

        ChangeItem passed = item(ChangeKind.TABLE_CREATE, "ok", CREATE_TOOL);
        ChangeItem rejected = item(ChangeKind.TABLE_CREATE, "bad", CREATE_TOOL);
        ChangeItem notProbed = item(ChangeKind.GENERIC_TOOL, "unknown", "custom_tool");

        ChangeManifest manifest = manifest(passed, rejected, notProbed);
        dryRunner.enrich(manifest, ctx(
                node("ok", createArgs(TABLE)),
                node("bad", createArgs("biz_bad")),
                node("unknown", Map.of())));

        assertEquals(DryRunResult.Outcome.PASSED, passed.getDryRun().getOutcome());
        assertEquals(DryRunResult.Outcome.REJECTED, rejected.getDryRun().getOutcome());
        assertEquals("表描述太短", rejected.getDryRun().getReason());
        assertEquals(DryRunResult.Outcome.NOT_PROBED, notProbed.getDryRun().getOutcome());
        // 🔴 上面四条肯定式断言先证明「这一趟确实探通了」，下面两条否定式断言才有意义
        //    （否则「没写 stats」与「整个 enrich 崩了」共用同一个绿色）。
        //    这两个数改由 WorkflowChangeManifestBuilder#finish 从「裁完之后留下来的」changes 现算：
        //    干跑器探的是全图、清单显示的是子图，它自己累加就会把裁掉的项也算进去。
        //    端到端的计数口径见 WorkflowChangeManifestBuilderTest。
        assertEquals(0, manifest.getStats().getDryRunRejected(), "干跑器不该自己累加汇总计数");
        assertEquals(0, manifest.getStats().getDryRunNotProbed(), "干跑器不该自己累加汇总计数");
        // 每一项都写了结论：dryRun 永不为 null，null 与「全部通过」在界面上长得一样
        for (ChangeItem each : manifest.getChanges()) {
            assertNotNull(each.getDryRun());
        }
    }

    @Test
    @DisplayName("就地写回：enrich 不替换 changes 列表，调用方拿到的还是自己那个对象")
    void enrichMutatesInPlace() {
        stubCreateChain(passingValidation(), List.of());
        ChangeItem item = createItem();
        ChangeManifest manifest = manifest(item);

        dryRunner.enrich(manifest, ctx(createNode()));

        assertSame(item, manifest.getChanges().get(0));
        assertNotNull(item.getDryRun());
    }

    // ==================== 装配 ====================

    private AgentValidateResult passingValidation(String... warnings) {
        AgentValidateResult result = new AgentValidateResult();
        for (String warning : warnings) {
            result.addWarning(warning);
        }
        return result;
    }

    private void stubCreateChain(AgentValidateResult validation, List<String> ddl) {
        DataModelSaveReqDTO reqDTO = new DataModelSaveReqDTO();
        reqDTO.setTableName(TABLE);
        when(resolver.resolveCreate(any(), eq(CREATE_TOOL))).thenReturn(reqDTO);
        when(createValidator.validate(reqDTO)).thenReturn(validation);
        when(dataModelApi.previewDdl(reqDTO)).thenReturn(ddl);
    }

    /**
     * 让桩把入参里的表名回显出来，好在一个清单里同时造出「探通」与「被拒」两项
     *
     * <p>取表名用的是 {@link ManifestArgs} 本尊（包级可见，本测试同包）——
     * 与干跑器同一个剥法，免得桩与实现各剥一遍、剥出两个答案。
     */
    private void stubCreateChainEchoingTableName() {
        when(resolver.resolveCreate(any(), eq(CREATE_TOOL))).thenAnswer(invocation -> {
            Map<String, Object> params = invocation.getArgument(0);
            DataModelSaveReqDTO reqDTO = new DataModelSaveReqDTO();
            reqDTO.setTableName(ManifestArgs.str(
                    ManifestArgs.map(params, DataModelParamResolver.PARAM_MODEL),
                    DataModelParamResolver.PARAM_TABLE_NAME));
            return reqDTO;
        });
        when(createValidator.validate(any())).thenAnswer(invocation -> {
            DataModelSaveReqDTO reqDTO = invocation.getArgument(0);
            if ("biz_bad".equals(reqDTO.getTableName())) {
                AgentValidateResult bad = new AgentValidateResult();
                bad.addError("表描述太短");
                return bad;
            }
            return passingValidation();
        });
        when(dataModelApi.previewDdl(any())).thenReturn(List.of("CREATE TABLE ..."));
    }

    private static DataModelApi.PhysicalTableRespDTO physicalTable(String name, Boolean registered) {
        DataModelApi.PhysicalTableRespDTO table = new DataModelApi.PhysicalTableRespDTO();
        table.setTableName(name);
        table.setRegistered(registered);
        return table;
    }

    private static Map<String, Object> createArgs(String tableName) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put(DataModelParamResolver.PARAM_TABLE_NAME, tableName);
        model.put("tableComment", "员工表");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(DataModelParamResolver.PARAM_MODEL, model);
        return args;
    }

    private static NodeDTO createNode() {
        return createNode(Map.of(DataModelParamResolver.PARAM_TABLE_NAME, TABLE,
                "tableComment", "员工表"));
    }

    private static NodeDTO createNode(Map<String, Object> model) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(DataModelParamResolver.PARAM_MODEL, model);
        return node("n1", args);
    }

    private static NodeDTO alterNode() {
        return node("n1", Map.of(DataModelParamResolver.PARAM_TABLE_NAME, TABLE));
    }

    private static NodeDTO node(String id, Map<String, Object> args) {
        NodeDTO node = new NodeDTO();
        node.setId(id);
        node.setType("tool");
        node.setName(id);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("args", args);
        node.setConfig(config);
        return node;
    }

    private static ChangeItem createItem() {
        return item(ChangeKind.TABLE_CREATE, "n1", CREATE_TOOL);
    }

    /**
     * 建表项，并把它建的那张表写进 {@code target}
     *
     * <p>{@code tablesCreatedHere} 读的是抽取器填好的 {@code target.tableName}
     * （「这项变更改的是哪个东西」的对外契约），不再从 args 里剥一遍 ——
     * 桩也要照这个契约喂，否则测的就不是实现读的那一份
     */
    private static ChangeItem createItemFor(String tableName) {
        ChangeItem item = createItem();
        item.getTarget().put("tableName", tableName);
        return item;
    }

    private static ChangeItem alterItem() {
        return item(ChangeKind.TABLE_ALTER, "n1", UPDATE_TOOL);
    }

    private static ChangeItem formCreateItem() {
        return item(ChangeKind.FORM_CREATE, "n2", SAVE_DRAFT_TOOL);
    }

    private static ChangeItem formPublishItem() {
        return item(ChangeKind.FORM_PUBLISH, "n1", PUBLISH_TOOL);
    }

    private static Map<String, Object> formCreateArgs(String mainTable, String code) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(FormSaveDraftTool.PARAM_MAIN_TABLE, mainTable);
        args.put(FormSaveDraftTool.PARAM_CODE, code);
        return args;
    }

    private static NodeDTO formCreateNode() {
        return node("n2", formCreateArgs(TABLE, FORM_CODE));
    }

    /** 发布参数：模型常把数字写成字符串，故 id 用 Long、parentMenuId 用 Long，各用例自行覆写 */
    private static Map<String, Object> publishArgs() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(FormPublishBundleTool.PARAM_ID, FORM_ID);
        args.put(FormPublishBundleTool.PARAM_PARENT_MENU_ID, PARENT_MENU_ID);
        return args;
    }

    private static NodeDTO formPublishNode(Map<String, Object> args) {
        return node("n1", args);
    }

    private static FormDesignSaveReqDTO existingForm(Long id) {
        FormDesignSaveReqDTO dto = new FormDesignSaveReqDTO();
        dto.setId(id);
        return dto;
    }

    private static FormDesignPublishRespDTO.MenuConfig menuConfig(String name, String path,
                                                                  int permCount) {
        FormDesignPublishRespDTO.MenuConfig config = new FormDesignPublishRespDTO.MenuConfig();
        config.setName(name);
        config.setPath(path);
        config.setParentId(PARENT_MENU_ID);
        List<FormDesignPublishRespDTO.PermissionConfig> permissions = new ArrayList<>();
        for (int i = 0; i < permCount; i++) {
            FormDesignPublishRespDTO.PermissionConfig perm = new FormDesignPublishRespDTO.PermissionConfig();
            perm.setName("员工信息" + MENU_ACTIONS[i]);
            perm.setPermission("infra:form-design-run:" + FORM_CODE + ":" + MENU_ACTIONS[i]);
            perm.setSort(i + 1);
            permissions.add(perm);
        }
        config.setPermissions(permissions);
        return config;
    }

    private static FormDesignMenuPreviewRespDTO preview(boolean needCreate, Long existingMenuId,
                                                        FormDesignPublishRespDTO.MenuConfig config) {
        FormDesignMenuPreviewRespDTO preview = new FormDesignMenuPreviewRespDTO();
        preview.setCode(FORM_CODE);
        preview.setName("员工信息");
        preview.setNeedCreateMenu(needCreate);
        preview.setExistingMenuId(existingMenuId);
        preview.setMenuConfig(config);
        return preview;
    }

    private static MenuSimpleRespDTO dir(Long id, String name) {
        MenuSimpleRespDTO dto = new MenuSimpleRespDTO();
        dto.setId(id);
        dto.setName(name);
        return dto;
    }

    private static EdgeDTO edge(String from, String to) {
        EdgeDTO edge = new EdgeDTO();
        edge.setFrom(from);
        edge.setTo(to);
        return edge;
    }

    /**
     * 一份「VALID_TREE 用得到的规则都齐全」的流程约束
     *
     * <p>⚠️ 刻意只放这几项，不是全量复刻 {@code BpmModelApiImpl#getProcessConstraints}：
     * 校验器的设计前提就是「规则只有一份、全部来自后端」，测试里复刻一份等于又抄了一遍枚举。
     * 规则本身对不对由 {@code BpmProcessValidatorTest} 钉，这里只钉干跑器<b>怎么转述</b>它的结论。
     */
    private static BpmProcessConstraintsRespDTO processConstraints() {
        BpmProcessConstraintsRespDTO constraints = new BpmProcessConstraintsRespDTO();
        constraints.setRequiredApproveType(1);
        constraints.setNodeTypes(List.of(nodeType(10), nodeType(11), nodeType(1)));
        constraints.setCandidateStrategies(List.of(strategy(30, true), strategy(34, false)));
        constraints.setApproveMethods(List.of(approveMethod(1)));
        // 首个审批节点不许「审批人自选」
        constraints.setFirstNodeForbiddenStrategies(List.of(34));
        constraints.setConditionTypes(List.of());
        return constraints;
    }

    private static BpmProcessConstraintsRespDTO.NodeType nodeType(int type) {
        BpmProcessConstraintsRespDTO.NodeType item = new BpmProcessConstraintsRespDTO.NodeType();
        item.setType(type);
        item.setName("类型" + type);
        item.setBranch(false);
        return item;
    }

    private static BpmProcessConstraintsRespDTO.CandidateStrategy strategy(int value,
                                                                          boolean paramRequired) {
        BpmProcessConstraintsRespDTO.CandidateStrategy item =
                new BpmProcessConstraintsRespDTO.CandidateStrategy();
        item.setStrategy(value);
        item.setName("策略" + value);
        item.setParamRequired(paramRequired);
        item.setParamFormat(paramRequired ? "逗号分隔的用户编号" : null);
        return item;
    }

    private static BpmProcessConstraintsRespDTO.ApproveMethod approveMethod(int method) {
        BpmProcessConstraintsRespDTO.ApproveMethod item =
                new BpmProcessConstraintsRespDTO.ApproveMethod();
        item.setMethod(method);
        item.setName("方式" + method);
        item.setRatioRequired(false);
        return item;
    }

    private static BpmModelRespDTO model(String key, String name) {
        BpmModelRespDTO model = new BpmModelRespDTO();
        model.setId("m-1");
        model.setKey(key);
        model.setName(name);
        return model;
    }

    /**
     * 一份部署审批流的参数
     *
     * <p>🔴 用 {@code LinkedHashMap} 而不是 {@code Map.of}：后者每个 JVM 实例的迭代顺序
     * 都不一样（{@code ImmutableCollections.SALT}），一旦哪个断言依赖顺序就会时红时绿
     */
    private static Map<String, Object> deployArgs(String key, String simpleModel,
                                                  String formDesignCode) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(ProcessCreateAndDeployTool.PARAM_KEY, key);
        args.put(ProcessCreateAndDeployTool.PARAM_NAME, "采购申请审批");
        args.put(ProcessCreateAndDeployTool.PARAM_SIMPLE_MODEL, simpleModel);
        // 不给 formDesignCode 就是「绑普通表单」那一支，key 干脆别出现，
        // 免得实现读到空串还以为绑了在线表单
        if (formDesignCode != null) {
            args.put(ProcessCreateAndDeployTool.PARAM_FORM_DESIGN_CODE, formDesignCode);
        }
        args.put(ProcessCreateAndDeployTool.PARAM_CATEGORY, "oa");
        return args;
    }

    private static NodeDTO deployNode(Map<String, Object> args) {
        return node("p1", args);
    }

    private static ChangeItem deployItem() {
        return item(ChangeKind.PROCESS_DEPLOY, "p1", DEPLOY_TOOL);
    }

    private static ChangeItem item(ChangeKind kind, String nodeId, String tool) {
        ChangeItem item = new ChangeItem();
        item.setKind(kind);
        item.setNodeId(nodeId);
        item.setTool(tool);
        return item;
    }

    private static ChangeManifest manifest(ChangeItem... items) {
        ChangeManifest manifest = new ChangeManifest();
        manifest.setChanges(new ArrayList<>(List.of(items)));
        return manifest;
    }

    /** 无边版的上下文：不同 id 之间 {@code reaches} 恒为 false，够多数用例用 */
    private static ManifestContext ctx(NodeDTO... nodes) {
        return ctx(List.of(), nodes);
    }

    /**
     * 带边的上下文
     *
     * <p>「建表节点走不走得到建表单节点」这条判据只有在有边时才测得出来 ——
     * 而它恰恰是最标准那份 DSL 不被冤枉的前提，故单开一个重载
     */
    private static ManifestContext ctx(List<EdgeDTO> edges, NodeDTO... nodes) {
        WorkflowDefDTO def = new WorkflowDefDTO();
        def.setNodes(List.of(nodes));
        def.setEdges(edges);
        Map<String, NodeDTO> nodeById = new LinkedHashMap<>();
        for (NodeDTO node : nodes) {
            nodeById.put(node.getId(), node);
        }
        // labels 给一个没有 api 的实例：干跑器根本不读它，但 record 的约定是四个成员都非 null
        return new ManifestContext(def, GraphReachability.of(def), nodeById, new ManifestLabels(null));
    }

    /** 模拟 infra 级故障。不用 Spring 的 DAO 异常，是为了让断言里的类名一眼可读 */
    private static class InfraDownException extends RuntimeException {
        InfraDownException(String message) {
            super(message);
        }
    }

}
