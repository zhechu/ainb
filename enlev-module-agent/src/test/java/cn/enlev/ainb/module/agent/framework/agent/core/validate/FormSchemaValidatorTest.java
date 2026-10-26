package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictDataRespDTO;
import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictTypeRespDTO;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.tool.impl.form.FormFieldFactory;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.system.api.dict.DictDataApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 表单 schema 校验的单元测试
 *
 * <h3>这里每一条拦的都是「保存成功、但不对」</h3>
 * 表单草稿保存几乎不校验，问题要到发布甚至运行时才暴露 ——
 * 而那时 AI 已经告诉用户「表单建好了」。
 *
 * @author 走进新时代
 */
public class FormSchemaValidatorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormSchemaValidator validator;

    @Mock
    private FormDesignApi formDesignApi;

    @Mock
    private DictDataApi dictDataApi;

    @BeforeEach
    public void setUp() {
        List<FormDesignApi.WidgetSimpleRespDTO> widgets = new ArrayList<>();
        widgets.add(widget("input", true, false));
        widgets.add(widget("textarea", true, false));
        widgets.add(widget("datePicker", true, false));
        widgets.add(widget("fcTitle", true, false));
        widgets.add(widget("oldSelect", true, true));    // 已废弃
        widgets.add(widget("brokenWidget", false, false)); // 已停用
        widgets.add(widget("UploadImg", true, false));
        widgets.add(widget("UploadImgs", true, false));
        widgets.add(widget("UploadFile", true, false));
        // 🔴 选项族那三个必须进契约清单：它们不在的话，下面整段「选项来源」的断言
        //    会先撞上「控件类型不存在」，测的就不是被测的那条判据了
        FormFieldFactory.OPTION_WIDGETS.forEach(type -> widgets.add(widget(type, true, false)));
        // 🔴 「在白名单里、也确实可用，而配置就是缺的」那一档的三种代表形状
        //    （实测 55 个可用控件里有 18 个是这种，见 FormFieldFactory#missingWidgetConfig）：
        //    DictSelect 要 props.dictType（必填）、cascader 的选项落在 props.options
        //    （它<b>不在</b> OPTION_WIDGETS 里，故由本档判而不是选项来源那档）、
        //    elAlert 要 props.title 且这类控件<b>没有 field</b>（布局/展示类照样得判）
        widgets.add(widgetWithProps("DictSelect", prop("dictType", true), prop("valueType", false)));
        widgets.add(widgetWithProps("cascader", prop("options", false)));
        widgets.add(widgetWithProps("elAlert", prop("title", true)));
        lenient().when(formDesignApi.getWidgetContracts()).thenReturn(widgets);
        // 内置正则目录只给三个，够断言「点名了哪个键、又列了哪些可用键」。
        // 🔴 刻意不在这里抄 infra 那 18 个：目录的<b>事实来源</b>是 validation-patterns.json，
        //    抄一份进测试等于把「两边漂移」这件事从被测范围里挪出去
        lenient().when(formDesignApi.getValidationPatternList()).thenReturn(List.of(
                pattern("ID_CARD", "身份证"), pattern("MOBILE", "手机号"), pattern("EMAIL", "邮箱")));
    }

    private FormDesignApi.WidgetSimpleRespDTO widget(String type, boolean supported,
                                                     boolean deprecated) {
        FormDesignApi.WidgetSimpleRespDTO dto = new FormDesignApi.WidgetSimpleRespDTO();
        dto.setType(type);
        dto.setSupported(supported);
        dto.setDeprecated(deprecated);
        return dto;
    }

    /**
     * 一个<b>带配置项</b>的可用控件
     *
     * <p>🔴 桩里不抄 infra 那 63 份契约（同上面那份内置正则目录的纪律）：
     * 判据的事实来源是 {@code form-widget-contracts.json}，抄一份进测试等于把「两边漂移」挪出被测范围。
     * 这里只桩判据会读的两格 —— 键名与「不填则该控件无法正常工作」。
     */
    private FormDesignApi.WidgetSimpleRespDTO widgetWithProps(String type,
                                                              FormDesignApi.WidgetPropDTO... props) {
        FormDesignApi.WidgetSimpleRespDTO dto = widget(type, true, false);
        dto.setProps(List.of(props));
        return dto;
    }

    private static FormDesignApi.WidgetPropDTO prop(String name, boolean required) {
        FormDesignApi.WidgetPropDTO dto = new FormDesignApi.WidgetPropDTO();
        dto.setName(name);
        dto.setRequired(required);
        return dto;
    }

    private FormDesignApi.ValidationPatternRespDTO pattern(String key, String label) {
        FormDesignApi.ValidationPatternRespDTO dto = new FormDesignApi.ValidationPatternRespDTO();
        dto.setKey(key);
        dto.setLabel(label);
        return dto;
    }

    private String rule(String type, String field, String title) {
        JSONObject obj = new JSONObject();
        obj.set("type", type);
        if (field != null) {
            obj.set("field", field);
        }
        obj.set("title", title);
        return obj.toString();
    }

    /**
     * 一条带 {@code props.<键>} 的 input 控件
     *
     * <p>{@code propValue} 收 {@code Object} 是有意的：被测的正是「形状不对时怎么办」，
     * 所以这里要能塞进字符串、空数组、非对象元素这些<b>本来就不该出现</b>的东西。
     */
    private String ruleWithProp(String field, String propKey, Object propValue) {
        JSONObject props = new JSONObject();
        props.set(propKey, propValue);
        JSONObject obj = new JSONObject();
        obj.set("type", "input");
        obj.set("field", field);
        obj.set("title", "身份证号");
        obj.set("props", props);
        return obj.toString();
    }

    private String validateRules(Object... entries) {
        return ruleWithProp("id_card", FormFieldFactory.VALIDATE_RULES_KEY, List.of(entries));
    }

    private AgentValidateResult validateOne(String rawRule) {
        return validator.validate(List.of(rawRule), binding("id_card"));
    }

    private String binding(String... fields) {
        JSONObject mapping = new JSONObject();
        for (String field : fields) {
            JSONObject item = new JSONObject();
            item.set("table", "biz_demo");
            item.set("column", field);
            item.set("jdbcType", "VARCHAR");
            mapping.set(field, item);
        }
        JSONObject binding = new JSONObject();
        binding.set("mainTable", "biz_demo");
        binding.set("fieldMapping", mapping);
        return binding.toString();
    }

    @Test
    @DisplayName("合规的表单要能通过")
    public void testValid() {
        AgentValidateResult result = validator.validate(
                List.of(rule("input", "order_no", "订单号"),
                        rule("datePicker", "apply_time", "申请时间")),
                binding("order_no", "apply_time"));

        assertTrue(result.isPassed(), "不该报错：" + result.getErrors());
    }

    // ==================== 控件白名单 ====================

    /**
     * 🔴 凭记忆编控件名的后果不是报错，而是表单能保存、能发布，就是渲染不出来
     */
    @Test
    @DisplayName("🔴 控件类型不存在被拒，并提示去查白名单")
    public void testUnknownWidget() {
        AgentValidateResult result = validator.validate(
                List.of(rule("el-input", "order_no", "订单号")), binding("order_no"));

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("widget_contract_get")),
                "要告诉模型去哪查：" + result.getErrors());
    }

    @Test
    @DisplayName("已停用的控件被拒")
    public void testUnsupportedWidget() {
        AgentValidateResult result = validator.validate(
                List.of(rule("brokenWidget", "f", "字段")), binding("f"));
        assertFalse(result.isPassed());
    }

    @Test
    @DisplayName("已废弃的控件只警告，不拦")
    public void testDeprecatedWidget() {
        AgentValidateResult result = validator.validate(
                List.of(rule("oldSelect", "f", "字段")), binding("f"));
        assertTrue(result.isPassed());
        assertFalse(result.getWarnings().isEmpty());
    }

    // ==================== 语义别名归一 ====================

    /**
     * 🔴 复现示例③运行期那个 BUG：模型把日期控件写成 {@code date}（直觉名），
     *    契约里叫 {@code datePicker}。不归一就会一路到运行期 form_save_draft 才报
     *    「控件类型 date 不存在」，用户看到一张点了确认却失败的卡片。
     */
    @Test
    @DisplayName("🔴 直觉名 date 归一到契约 datePicker，通过校验并给出归一警告")
    public void testAliasDateNormalized() {
        AgentValidateResult result = validator.validate(
                List.of(rule("date", "apply_date", "申请日期")), binding("apply_date"));

        assertTrue(result.isPassed(), "date 应归一到 datePicker 后通过：" + result.getErrors());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("datePicker")),
                "要提示已归一，别静默改写：" + result.getWarnings());
    }

    @Test
    @DisplayName("静态别名表：date/datetime→datePicker，契约名与未知名返回 null")
    public void testAliasCanonicalStatic() {
        assertEquals("datePicker", FormSchemaValidator.aliasCanonical("date"));
        assertEquals("datePicker", FormSchemaValidator.aliasCanonical("DATETIME"));
        assertEquals("inputNumber", FormSchemaValidator.aliasCanonical("number"));
        // 契约规范名本身不是别名，返回 null（归一时契约优先，绝不别名盖真名）
        assertNull(FormSchemaValidator.aliasCanonical("input"));
        assertNull(FormSchemaValidator.aliasCanonical("datePicker"));
        assertNull(FormSchemaValidator.aliasCanonical("noSuchWidget"));
        assertNull(FormSchemaValidator.aliasCanonical(""));
    }

    /**
     * 🔴 复现 def-152 那次失败：规划器把 {@code image} 冻进了已发布流程的 save_draft 参数，
     *    运行期报「控件类型 image 不存在」整条流程死掉。上传族直觉名必须归一后放行。
     */
    @Test
    @DisplayName("🔴 上传族直觉名归一：image→UploadImg、images→UploadImgs、file→UploadFile")
    public void testUploadFamilyAliasesNormalized() {
        AgentValidateResult result = validator.validate(
                List.of(rule("image", "device_image", "设备图片"),
                        rule("images", "photos", "照片墙"),
                        rule("file", "attachment", "附件")),
                binding("device_image", "photos", "attachment"));
        assertTrue(result.isPassed(), "上传族直觉名应归一后通过：" + result.getErrors());
    }

    /**
     * 🔴 这条<b>刻意</b>不钉死整句文案，只钉「补救建议在这条路径上照做得到」这个<b>性质</b>。
     *
     * <p>{@code checkWidgetType} 的唯一消费者是规划期的 {@code WorkflowDslValidator}，
     * 而 planner 那条路径上<b>没有任何工具可调</b>。原文案是「可用类型请调 widget_contract_get 查询」
     * —— 一句做不到的指令，被每轮回灌原样重复，模型就在同一堵墙上撞满轮次上限：
     * 实测连猜六个控件名，第五轮改写成 {@code {{$.valid_widgets.user_widget}}}
     * 想让运行期替它取值（疤记在 {@code WorkflowDslPlannerPrompt#appendWidgetCatalog}）。
     *
     * <p>钉性质而不是钉字面量的好处：日后改措辞不会白白变红，
     * 而「又叫模型去调它调不到的工具」一定会红。
     *
     * <p>⚠️ 同一条断言<b>不能</b>搬到 {@link #testUnknownWidget} 那条上 —— 那一条走的是
     * {@code validate()}（执行期、持有 rule），那条路径上 {@code widget_contract_get} 真能调，
     * 文案里点名它是<b>对的</b>。两处文案刻意不同，别为了「统一」合回去。
     */
    @Test
    @DisplayName("checkWidgetType：可用返回 null；不可用时补救建议指向 prompt 白名单，不再叫模型去调它调不到的工具")
    public void testCheckWidgetType() {
        assertNull(validator.checkWidgetType("image"), "直觉名归一后应判可用");
        assertNull(validator.checkWidgetType("UploadImg"));

        String unsupported = validator.checkWidgetType("brokenWidget");
        assertTrue(unsupported.contains("brokenWidget") && unsupported.contains("已停用"),
                "要点名归一后的规范名并说清是停用，实际：" + unsupported);
        assertRemedyIsReachable(unsupported);

        String notFound = validator.checkWidgetType("noSuchWidget");
        assertTrue(notFound.contains("noSuchWidget") && notFound.contains("不存在"),
                "要点名模型写的那个类型，实际：" + notFound);
        assertRemedyIsReachable(notFound);
    }

    /**
     * 规划期回灌的补救建议必须「照做得到」：指向 prompt 里那份完整白名单，
     * 且<b>不</b>叫模型去调它调不到的工具
     */
    private void assertRemedyIsReachable(String message) {
        assertTrue(message.contains("控件白名单"),
                "补救建议必须指向 system prompt 里那份完整清单，否则模型只能接着猜。实际：" + message);
        assertFalse(message.contains("widget_contract_get"),
                "🔴 规划期这条路径上没有任何工具可调，叫模型去调 widget_contract_get 是一句照做不了的指令，"
                        + "会被每轮回灌重复一遍、把轮次上限烧光。实际：" + message);
    }

    // ==================== 控件配得齐吗（AI 建不出来的那些） ====================

    /**
     * 🔴 这一档守的还是「不报错的失效」，但根因与上面两档都不同：
     * 控件名<b>在白名单里、也确实可用</b>，只是它的必需配置在字段描述（fields[]）里没有格子能放。
     * 后果一模一样 —— 存得进、发布得过，运行期那一格是个点不开的下拉 / 显示不出内容的块。
     */
    @Test
    @DisplayName("🔴 checkWidgetType 第三道判定：在白名单里 ≠ AI 建得出来")
    public void testCheckWidgetTypeUnauthorable() {
        assertNull(validator.checkWidgetType("input"), "不需要额外配置的控件照旧放行");
        assertNull(validator.checkWidgetType("select"),
                "选项族那三个靠 options/dictType 就能表达，不能被判成建不出来");

        String gap = validator.checkWidgetType("DictSelect");

        assertTrue(gap.contains("DictSelect") && gap.contains("dictType"),
                "要点名控件与缺的那一格，实际：" + gap);
        assertTrue(gap.contains(FormFieldFactory.UNAUTHORABLE_MARK),
                "要点名提示词里那个标记，否则模型无从对照清单：" + gap);
        assertRemedyIsReachable(gap);
    }

    @Test
    @DisplayName("🔴 新建表单时选了「契约要求配置、描述表达不了」的控件 ⟹ 报错")
    public void testNewFormUnauthorableWidget_rejected() {
        AgentValidateResult result = validateNew(optionRule("DictSelect", null, null));

        assertFalse(result.isPassed(), result.toFeedback());
        String error = onlyError(result);
        assertTrue(error.contains("DictSelect") && error.contains("dictType"), error);
        assertTrue(error.contains("空控件"), "要说清后果，否则模型以为这只是个风格建议：" + error);
        assertTrue(error.contains("widget_contract_get"),
                "这条路径上工具真能调，出路就该指向它（与 checkWidgetType 那条刻意不同）：" + error);
    }

    @Test
    @DisplayName("改表单时路过一格存量的坏控件 ⟹ 只警告，不拦本次修改")
    public void testExistingFormUnauthorableWidget_warnsNotBlocks() {
        AgentValidateResult result = validateExisting(optionRule("DictSelect", null, null));

        assertTrue(result.isPassed(),
                "一张表单可能在设计器里就留下了历史问题，拦下「把备注改成必填」这种无关的 patch，"
                        + "等于让用户连一句话都加不上：" + result.toFeedback());
        String warning = onlyWarning(result);
        assertTrue(warning.contains("不是本次改的"), warning);
    }

    /**
     * 🔴 分档认的不只是「新建 vs 改」，还要认「本次 patch <b>新增</b>的那几个 field」：
     * 用户说「加一个字典选择器字段」时，那一格是本次自己造的，没有理由只提醒一句就存进去。
     * 这一条钉的正是 {@code FormUpdateTool} 交出 {@code outcome.added()} 那半步 ——
     * 不交的话本用例与上面那条给出同一个结论，「加了个空控件」就静默过关了。
     */
    @Test
    @DisplayName("🔴 patch 新增的字段按新建那一档判（error），存量字段仍只警告")
    public void testPatchAddedField_isErrorNotWarning() {
        String raw = optionRule("DictSelect", null, null).toString();

        AgentValidateResult added = validator.validate(List.of(raw), binding("status"), List.of("status"));
        assertFalse(added.isPassed(), added.toFeedback());
        assertFalse(onlyError(added).contains("不是本次改的"), added.getErrors().toString());

        AgentValidateResult untouched = validator.validate(List.of(raw), binding("status"), List.of("other"));
        assertTrue(untouched.isPassed(), untouched.toFeedback());
        assertTrue(onlyWarning(untouched).contains("不是本次改的"), untouched.getWarnings().toString());
    }

    @Test
    @DisplayName("设计器里配好了那一格的同款控件 ⟹ 一句都不说（判 rule，不判控件名）")
    public void testConfiguredUnauthorableWidget_silent() {
        JSONObject props = new JSONObject();
        props.set("dictType", "common_status");

        AgentValidateResult result = validateExisting(optionRule("DictSelect", props, null));

        assertTrue(result.isPassed(), result.toFeedback());
        assertTrue(result.getWarnings().isEmpty(),
                "配好了还报，用户学会的就是「这条黄标不用看」：" + result.getWarnings());
    }

    @Test
    @DisplayName("选项落在 props 里、又不在选项族那三个里的控件（级联/树）同样判缺来源")
    public void testOptionSourceOutsideOptionFamily_rejected() {
        AgentValidateResult result = validateNew(optionRule("cascader", null, null));

        assertFalse(result.isPassed(), result.toFeedback());
        String error = onlyError(result);
        assertTrue(error.contains("cascader") && error.contains("选项来源"), error);
    }

    /**
     * 🔴 布局/展示类控件<b>没有 field</b>，而 {@code validateRule} 在「没有 field 就 return」之前
     * 必须先过这一关 —— 否则 {@code fcTitle} 少了 {@code title} 就是一个空标题块，
     * 而它压根走不到任何按 field 判的规则上去。
     */
    @Test
    @DisplayName("没有 field 的展示类控件照样判（少个 title 就是个空块）")
    public void testLayoutWidgetWithoutField_stillJudged() {
        AgentValidateResult result = validator.validateNewForm(
                List.of(rule("elAlert", null, "提示"), rule("input", "status", "状态")), binding("status"));

        assertFalse(result.isPassed(), result.toFeedback());
        String error = onlyError(result);
        assertTrue(error.contains("elAlert") && error.contains("title"), error);
        assertFalse(error.contains("字段"), "没有 field 的控件不该被说成「字段 …」：" + error);
    }

    private String onlyWarning(AgentValidateResult result) {
        assertEquals(1, result.getWarnings().size(), "应只留这一条提示，实际：" + result.getWarnings());
        return result.getWarnings().get(0);
    }

    // ==================== 字段 ====================

    /**
     * 🔴 重复的后果是后一个覆盖前一个 —— 「我明明加了两个字段，只存进去一个」
     */
    @Test
    @DisplayName("🔴 field 重复被拒")
    public void testDuplicateField() {
        AgentValidateResult result = validator.validate(
                List.of(rule("input", "order_no", "订单号"),
                        rule("input", "order_no", "又一个订单号")),
                binding("order_no"));

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("重复")));
    }

    @Test
    @DisplayName("field 以下划线开头被拒 —— 与 form-create 内部保留字冲突")
    public void testUnderscorePrefix() {
        AgentValidateResult result = validator.validate(
                List.of(rule("input", "_internal", "内部字段")), binding("_internal"));
        assertFalse(result.isPassed());
    }

    @Test
    @DisplayName("布局类控件没有 field，不参与字段校验")
    public void testLayoutWidgetWithoutField() {
        AgentValidateResult result = validator.validate(
                List.of(rule("fcTitle", null, "分组标题"),
                        rule("input", "order_no", "订单号")),
                binding("order_no"));
        assertTrue(result.isPassed(), "实际：" + result.getErrors());
    }

    // ==================== fieldMapping ====================

    /**
     * 🔴 这是「配了但不生效」最典型的一种
     */
    @Test
    @DisplayName("🔴 字段没有 fieldMapping 被拒 —— 填了、提交成功、但不落库")
    public void testMissingMapping() {
        AgentValidateResult result = validator.validate(
                List.of(rule("input", "order_no", "订单号"),
                        rule("input", "amount", "金额")),
                binding("order_no"));   // amount 没映射

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream()
                        .anyMatch(e -> e.contains("amount") && e.contains("不会落库")),
                "实际：" + result.getErrors());
    }

    @Test
    @DisplayName("缺 jdbcType 被拒 —— 靠反射猜类型是绑定出错的源头")
    public void testMissingJdbcType() {
        JSONObject item = new JSONObject();
        item.set("column", "order_no");
        JSONObject mapping = new JSONObject();
        mapping.set("order_no", item);
        JSONObject binding = new JSONObject();
        binding.set("mainTable", "biz_demo");
        binding.set("fieldMapping", mapping);

        AgentValidateResult result = validator.validate(
                List.of(rule("input", "order_no", "订单号")), binding.toString());

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("jdbcType")));
    }

    /**
     * 🔴 这条的后果是<b>跨租户写入</b>
     */
    @Test
    @DisplayName("🔴 映射到 tenant_id 必须硬拦 —— 被表单值覆盖就是跨租户写入")
    public void testTenantIdMappingRejected() {
        JSONObject item = new JSONObject();
        item.set("column", "tenant_id");
        item.set("jdbcType", "BIGINT");
        JSONObject mapping = new JSONObject();
        mapping.set("org_id", item);
        JSONObject binding = new JSONObject();
        binding.set("mainTable", "biz_demo");
        binding.set("fieldMapping", mapping);

        AgentValidateResult result = validator.validate(
                List.of(rule("input", "org_id", "组织")), binding.toString());

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("跨租户")),
                "实际：" + result.getErrors());
    }

    @Test
    @DisplayName("映射到自动维护列只警告 —— 不出安全问题，但意义不大")
    public void testAutoFillColumnWarned() {
        JSONObject item = new JSONObject();
        item.set("column", "creator");
        item.set("jdbcType", "VARCHAR");
        JSONObject mapping = new JSONObject();
        mapping.set("creator", item);
        JSONObject binding = new JSONObject();
        binding.set("mainTable", "biz_demo");
        binding.set("fieldMapping", mapping);

        AgentValidateResult result = validator.validate(
                List.of(rule("input", "creator", "创建人")), binding.toString());

        assertTrue(result.isPassed());
        assertFalse(result.getWarnings().isEmpty());
    }

    @Test
    @DisplayName("缺 tableBinding 被拒 —— 表单不知道数据往哪存")
    public void testMissingBinding() {
        AgentValidateResult result = validator.validate(
                List.of(rule("input", "order_no", "订单号")), null);
        assertFalse(result.isPassed());
    }

    @Test
    @DisplayName("空表单被拒")
    public void testEmptyForm() {
        assertFalse(validator.validate(List.of(), binding()).isPassed());
    }

    @Test
    @DisplayName("一次把所有问题列出来")
    public void testCollectsAll() {
        AgentValidateResult result = validator.validate(
                List.of(rule("el-input", "_bad", "坏字段"),
                        rule("brokenWidget", "another", "另一个")),
                "{\"mainTable\":\"biz_demo\",\"fieldMapping\":{}}");

        assertTrue(result.getErrors().size() >= 3,
                "应一次列出全部问题，实际 " + result.getErrors().size() + " 条：" + result.getErrors());
    }

    // ==================== props.validateRules（字段级格式校验） ====================

    /**
     * 🔴 这一节钉的是「用户要了身份证校验、AI 说建好了、其实一个字都没生效」
     *
     * <p>运行期对认不出的 {@code patternKey} 是「放过 + 记 warn」（infra 的
     * {@code FormValidationGuard}，那是有意的可用性取舍：一次正则库调整不该让存量表单集体提交不了），
     * 真拦的只有发布体检那一关。所以<b>存草稿</b>这一步不拦的话，中间没有任何一处会主动说 ——
     * 而模型编键名的概率不低：它手上的用户原话是「身份证」，库里那个键叫 {@code ID_CARD}，
     * 凭记忆写成 {@code IDCARD} / {@code ID_NUMBER} 都很自然。
     *
     * <p>⚠️ 每条断言都要求报错里<b>给出路</b>（点名可用键名），不只是说「不对」：
     * 出路不通的话，模型最省力的改法是把这条校验整个删掉 —— 用户的要求就此消失，而且仍然不报错。
     */
    @Test
    @DisplayName("引用存在的内置正则要通过")
    public void testBuiltinPatternKeyAccepted() {
        AgentValidateResult result = validateOne(validateRules(entry("BUILTIN", "ID_CARD", null)));
        assertTrue(result.isPassed(), "不该报错：" + result.getErrors());
    }

    @Test
    @DisplayName("type 缺省时按「有 patternKey 就是想引用内置库」处理 —— 与 infra 的 resolveType 同判据")
    public void testPatternKeyWithoutTypeIsStillChecked() {
        assertFalse(validateOne(validateRules(entry(null, "NO_SUCH_KEY", null))).isPassed(),
                "少写一个 type 不该让这条校验逃过键名存在性检查 —— infra 那边明说了少写不该让规则失效");
    }

    @Test
    @DisplayName("键名大小写不敏感 —— 运行期查库也是不敏感的，这里更严就成了误报")
    public void testPatternKeyIsCaseInsensitive() {
        AgentValidateResult result = validateOne(validateRules(entry("BUILTIN", "id_card", null)));
        assertTrue(result.isPassed(), "不该报错：" + result.getErrors());
    }

    /**
     * 🔴 本节头号用例：编错的键名必须被拦，且报错里列出可用键名
     */
    @Test
    @DisplayName("🔴 引用不存在的内置正则被拒，报错点名词库并给出路")
    public void testUnknownPatternKeyRejected() {
        AgentValidateResult result = validateOne(validateRules(entry("BUILTIN", "IDCARD", null)));

        assertFalse(result.isPassed());
        String error = onlyError(result);
        assertTrue(error.contains("id_card") && error.contains("IDCARD"),
                "要点名是哪个字段的哪个键名，否则模型不知道该改哪儿：" + error);
        assertTrue(error.contains("ID_CARD（身份证）"),
                "出路必须带上中文名：模型手上是用户的说法「身份证」，只给 ID_CARD 它还得再猜一次映射：" + error);
    }

    @Test
    @DisplayName("props.validateRules 不是数组时被拒 —— 直接写成键名字符串是最省力的错法")
    public void testValidateRulesNotAnArrayIsRejected() {
        AgentValidateResult result = validateOne(
                ruleWithProp("id_card", FormFieldFactory.VALIDATE_RULES_KEY, "ID_CARD"));

        assertFalse(result.isPassed());
        String error = onlyError(result);
        assertTrue(error.contains("BUILTIN") && error.contains("patternKey"),
                "要给出正确形状，否则模型只知道错了、不知道怎么改：" + error);
    }

    @Test
    @DisplayName("空数组同样被拒 —— 「配了但一条都没有」与「压根没配」在运行期是同一种静默")
    public void testEmptyValidateRulesIsRejected() {
        assertFalse(validateOne(ruleWithProp("id_card", FormFieldFactory.VALIDATE_RULES_KEY, List.of()))
                .isPassed());
    }

    @Test
    @DisplayName("数组里有一项不是对象时被拒")
    public void testValidateRulesEntryNotAnObjectIsRejected() {
        AgentValidateResult result = validateOne(
                ruleWithProp("id_card", FormFieldFactory.VALIDATE_RULES_KEY, List.of("ID_CARD")));

        assertFalse(result.isPassed());
        assertTrue(onlyError(result).contains("不是对象"), result.getErrors().toString());
    }

    @Test
    @DisplayName("既没 patternKey 也没 pattern 时被拒，且报错里列出可用键名")
    public void testEntryWithoutKeyOrPatternIsRejected() {
        AgentValidateResult result = validateOne(validateRules(entry("BUILTIN", null, null)));

        assertFalse(result.isPassed());
        String error = onlyError(result);
        assertTrue(error.contains("patternKey") && error.contains("pattern"),
                "要说清两条出路各写哪个键：" + error);
        assertTrue(error.contains("ID_CARD（身份证）"), "出路：" + error);
    }

    /**
     * 🔴 这条钉的是<b>刻意漏报</b>的方向，与「宁可漏报也不误报」是同一条纪律
     *
     * <p>{@code referencesBuiltinLibrary} 的判据是「没有明写 REGEX」而不是「明写 BUILTIN」，
     * 为的是与 infra {@code FieldValidationRule#resolveType} 逐条对上；代价就是这一条：
     * 明写 REGEX 又顺手带了个编错的 patternKey 时，运行期确实忽略那个 key（走 pattern），
     * 拦它就是误报 —— 而误报的代价是让模型去改一个没病的表单。
     */
    @Test
    @DisplayName("🔴 明写 REGEX 又带了个编错的 patternKey：不报，运行期本来就走 pattern")
    public void testExplicitRegexWithStrayPatternKeyIsNotReported() {
        AgentValidateResult result = validateOne(
                validateRules(entry("REGEX", "NO_SUCH_KEY", "^\\d{17}[\\dXx]$")));

        assertTrue(result.isPassed(), "误报会让模型去改一个没病的表单，每轮自修正都是真实计费："
                + result.getErrors());
    }

    @Test
    @DisplayName("正则库整个读不到时只跳过键名存在性那一条，形状错误照旧拦")
    public void testPatternLibraryUnavailableFailsSoft() {
        when(formDesignApi.getValidationPatternList()).thenThrow(new IllegalStateException("库没加载出来"));

        assertTrue(validateOne(validateRules(entry("BUILTIN", "IDCARD", null))).isPassed(),
                "分不清「库坏了」与「键名编错了」时一律放过；发布体检那一关仍会拦");
        assertFalse(validateOne(ruleWithProp("id_card", FormFieldFactory.VALIDATE_RULES_KEY, "ID_CARD"))
                        .isPassed(),
                "依赖故障不该把形状校验也一起放掉 —— 那一条不依赖正则库");
    }

    // ==================== 目录渲染：提示词与报错共用一份 ====================

    /**
     * 🔴 {@code validateRulesCatalog}（喂给 {@code FormSaveDraftTool#paramsSchema}）
     * 与上面那条报错里的目录必须是<b>同一份渲染</b>
     *
     * <p>两处各排版一份的话，漂移的表现是最坏的那种：提示词印的清单里有、判据那份里没有，
     * 模型照着提示词写、被判据拒，再按报错里另一份清单改 —— 在两堵墙之间来回撞，
     * 每轮一次真实计费，而它看不出到底哪份是真的。
     */
    @Test
    @DisplayName("🔴 validateRulesCatalog 与报错文案里那份目录逐字相同")
    public void testCatalogIsTheSameRenderingTheErrorUses() {
        String catalog = validator.validateRulesCatalog();

        assertEquals("ID_CARD（身份证）、MOBILE（手机号）、EMAIL（邮箱）", catalog);
        assertTrue(onlyError(validateOne(validateRules(entry("BUILTIN", "IDCARD", null)))).contains(catalog),
                "报错里的目录必须就是这一份渲染，不能另排一版");
    }

    @Test
    @DisplayName("目录里没配中文名的键退化成只印 key，不留一对空括号")
    public void testCatalogOmitsBlankLabel() {
        when(formDesignApi.getValidationPatternList()).thenReturn(List.of(
                pattern("USCC", "统一社会信用代码"), pattern("AMOUNT", null)));

        assertEquals("USCC（统一社会信用代码）、AMOUNT", validator.validateRulesCatalog());
    }

    @Test
    @DisplayName("库读不到时目录退化成一句「改用 pattern」，而不是空串 —— 空清单等于没有出路")
    public void testCatalogDegradesToActionableText() {
        when(formDesignApi.getValidationPatternList()).thenReturn(List.of());

        String catalog = validator.validateRulesCatalog();

        assertFalse(catalog.isBlank(), "提示词里会印出一个空的冒号，模型只能接着猜键名");
        assertTrue(catalog.contains("pattern"), "要给出路：" + catalog);
    }

    /**
     * 一条格式校验规则。三个参数都可传 {@code null}，代表「模型没写这一格」
     */
    private JSONObject entry(String type, String patternKey, String pattern) {
        JSONObject obj = new JSONObject();
        if (type != null) {
            obj.set("type", type);
        }
        if (patternKey != null) {
            obj.set("patternKey", patternKey);
        }
        if (pattern != null) {
            obj.set("pattern", pattern);
        }
        return obj;
    }

    private String onlyError(AgentValidateResult result) {
        assertEquals(1, result.getErrors().size(), "应只报这一条，实际：" + result.getErrors());
        return result.getErrors().get(0);
    }

    // ==================== 选项来源 ====================

    /**
     * 一条选项类控件的 rule。{@code props} 与 {@code options} 都收 {@code Object} 是有意的：
     * 被测的正是「形状不对时怎么办」，所以要能塞进字符串、纯字符串数组这些<b>本来就不该出现</b>的东西
     */
    private JSONObject optionRule(String type, Object props, Object options) {
        JSONObject obj = new JSONObject();
        obj.set("type", type);
        obj.set("field", "status");
        obj.set("title", "状态");
        if (props != null) {
            obj.set("props", props);
        }
        if (options != null) {
            obj.set(FormFieldFactory.OPTIONS_KEY, options);
        }
        return obj;
    }

    private JSONObject dictProps(String dictType) {
        JSONObject source = new JSONObject();
        if (dictType != null) {
            source.set(FormFieldFactory.DICT_TYPE_KEY, dictType);
        }
        source.set("valueType", FormFieldFactory.DICT_VALUE_TYPE);
        JSONObject props = new JSONObject();
        props.set(FormFieldFactory.DICT_SOURCE_KEY, source);
        return props;
    }

    private JSONArray options(Object... entries) {
        JSONArray array = new JSONArray();
        for (Object entry : entries) {
            array.add(entry);
        }
        return array;
    }

    private JSONObject option(String label, Object value) {
        JSONObject obj = new JSONObject();
        if (label != null) {
            obj.set("label", label);
        }
        if (value != null) {
            obj.set("value", value);
        }
        return obj;
    }

    private AgentValidateResult validateNew(JSONObject rule) {
        return validator.validateNewForm(List.of(rule.toString()), binding("status"));
    }

    private AgentValidateResult validateExisting(JSONObject rule) {
        return validator.validate(List.of(rule.toString()), binding("status"));
    }

    /**
     * 造一个字典：类型存在，数据按给的状态各来一条
     *
     * <p>🔴 状态一律取 {@link CommonStatusEnum}，不写 {@code 0}/{@code 1} 字面量 ——
     * 那份数字的含义是 infra 定的，测试里抄一遍就是第二份判据
     */
    private void stubDict(String dictType, CommonStatusEnum... statuses) {
        DictTypeRespDTO type = new DictTypeRespDTO();
        type.setType(dictType);
        lenient().when(dictDataApi.getDictType(dictType)).thenReturn(type);
        List<DictDataRespDTO> data = new ArrayList<>();
        for (CommonStatusEnum status : statuses) {
            DictDataRespDTO item = new DictDataRespDTO();
            item.setDictType(dictType);
            item.setLabel("标签" + status.getStatus());
            item.setValue("value" + status.getStatus());
            item.setStatus(status.getStatus());
            data.add(item);
        }
        lenient().when(dictDataApi.getDictDataList(dictType)).thenReturn(data);
    }

    /**
     * 🔴 这一条守的是「AI 建的下拉是空的」：存草稿、发布<b>都不报错</b>，
     * 用户拿到的是一句「表单建好了」加一个选不了任何值的控件
     */
    @Test
    @DisplayName("🔴 新建表单时选项类控件一个来源都没有 ⟹ 报错，并给出两条照做得到的出路")
    public void testNewFormOptionWidgetWithoutSource() {
        AgentValidateResult result = validateNew(optionRule("select", null, null));

        assertFalse(result.isPassed());
        String error = onlyError(result);
        assertTrue(error.contains("没有任何选项来源"), error);
        assertTrue(error.contains(FormFieldFactory.OPTIONS_KEY), "要给出路（固定选项）：" + error);
        assertTrue(error.contains(FormFieldFactory.DICT_TYPE_KEY), "要给出路（字典）：" + error);
    }

    /**
     * 🔴 同一条形状在<b>改表单</b>那条路上只能是 warning：{@code validate} 收的是合并后的整份
     * formFields，按 error 判的话「把备注改成必填」这种毫不相干的请求会被一个<b>先于本次请求
     * 就存在</b>的缺陷整个挡掉（实测存量里有 3 个这种字段，其中 2 个已发布）
     */
    @Test
    @DisplayName("🔴 改表单时同一个形状只提醒不阻断")
    public void testExistingFormOptionWidgetWithoutSourceOnlyWarns() {
        AgentValidateResult result = validateExisting(optionRule("select", null, null));

        assertTrue(result.isPassed(), "存量形状不该挡住一次无关的修改：" + result.getErrors());
        assertEquals(1, result.getWarnings().size(), "但要让人在 Plan 卡片上看见：" + result.getWarnings());
        assertTrue(result.getWarnings().get(0).contains("没有任何选项来源"), result.getWarnings().toString());
    }

    @Test
    @DisplayName("选项族里三个控件都受同一条判据管，不是只管 select")
    public void testEveryOptionWidgetIsJudged() {
        for (String type : FormFieldFactory.OPTION_WIDGETS) {
            assertFalse(validateNew(optionRule(type, null, null)).isPassed(),
                    type + " 没有选项来源却放行了 —— 它渲染出来同样是个空控件");
        }
    }

    /**
     * 🔴 模型没有建字典的工具，于是它编出来的 dictType 在库里必然不存在。
     * 实测过一批：34 份定义里 7 个 dictType，<b>6 个是编的</b>，其中 2 个还挂在已发布的定义上
     */
    @Test
    @DisplayName("🔴 编出来的字典类型被拒，且两条路上都是 error（不分新建/修改）")
    public void testInventedDictTypeIsRejectedOnBothPaths() {
        JSONObject rule = optionRule("select", dictProps("meeting_rooms"), null);

        String newError = onlyError(validateNew(rule));
        String existingError = onlyError(validateExisting(rule));

        assertTrue(newError.contains("meeting_rooms"), "要点名是哪个字典：" + newError);
        assertTrue(newError.contains("不存在"), newError);
        assertTrue(newError.contains("没有建字典的工具"),
                "要说清它为什么不可能靠后续动作变对：" + newError);
        assertEquals(newError, existingError,
                "编出来的字典不会因为「这是存量」就变得能用，故这一条刻意不分档");
    }

    @Test
    @DisplayName("字典存在且有开启的数据 ⟹ 一条都不说")
    public void testUsableDictPasses() {
        stubDict("common_status", CommonStatusEnum.ENABLE, CommonStatusEnum.DISABLE);

        AgentValidateResult result = validateNew(optionRule("select", dictProps("common_status"), null));

        assertTrue(result.isPassed(), "不该报错：" + result.getErrors());
        assertTrue(result.getWarnings().isEmpty(), "也不该提醒：" + result.getWarnings());
    }

    /**
     * 🔴 这一档<b>刻意只是 warning</b>：字典暂时没数据是合法状态，管理员随后补上就好，
     * infra 的发布关也<b>不拦</b>它。在这里拦就会比设计器更严，
     * 出现「AI 说建不了、手工配同一个字典却能发布」的自相矛盾
     */
    @Test
    @DisplayName("字典存在但一条开启数据都没有 ⟹ 只提醒")
    public void testDictWithoutEnabledDataOnlyWarns() {
        stubDict("common_status", CommonStatusEnum.DISABLE);

        AgentValidateResult result = validateNew(optionRule("select", dictProps("common_status"), null));

        assertTrue(result.isPassed(), "不该阻断：" + result.getErrors());
        String warning = result.getWarnings().get(0);
        assertTrue(warning.contains("开启"), warning);
        assertTrue(warning.contains(FormFieldFactory.OPTIONS_KEY), "要给出更稳的出路：" + warning);
    }

    @Test
    @DisplayName("字典压根没数据行 ⟹ 同样只提醒（分组类型的字典天然就是这种）")
    public void testDictWithNoDataAtAllOnlyWarns() {
        stubDict("common_status");

        assertTrue(validateNew(optionRule("select", dictProps("common_status"), null)).isPassed());
    }

    @Test
    @DisplayName("dictSource 写了但 dictType 是空的 ⟹ 点准是「字典写了一半」，不是「什么都没写」")
    public void testDictSourceWithoutDictType() {
        String error = onlyError(validateNew(optionRule("select", dictProps(null), null)));

        assertTrue(error.contains("dictType 是空的"), error);
    }

    /**
     * 🔴 两个都写时<b>字典赢</b>（前端 {@code detectOptionSourceType} 的优先级），
     * 于是「我明明改成固定选项了」静默不生效 —— 与它要防的那个缺陷同一个形状
     */
    @Test
    @DisplayName("字典与静态选项同时给 ⟹ 报错，并说明谁会赢")
    public void testBothSourcesIsRejected() {
        JSONObject rule = optionRule("select", dictProps("common_status"), options(option("待处理", "pending")));

        String error = onlyError(validateNew(rule));

        assertTrue(error.contains("只能用一种"), error);
        assertTrue(error.contains("字典赢"), "要说清后果，否则模型不知道该删哪个：" + error);
    }

    @Test
    @DisplayName("形状正确的静态选项 ⟹ 通过（value 是数字也收，前端 castValue 会拼成字符串）")
    public void testWellShapedStaticOptionsPass() {
        AgentValidateResult result = validateNew(
                optionRule("checkbox", null, options(option("启用", 0), option("停用", 1))));

        assertTrue(result.isPassed(), "不该报错：" + result.getErrors());
    }

    @Test
    @DisplayName("静态选项写成裸字符串数组 ⟹ 报错并给出正确形状")
    public void testStaticOptionsAsBareStrings() {
        String error = onlyError(validateNew(optionRule("select", null, options("待处理"))));

        assertTrue(error.contains("不是对象"), error);
        assertTrue(error.contains("label"), "要给出正确形状：" + error);
    }

    @Test
    @DisplayName("静态选项缺 label / 缺 value ⟹ 各自点准缺的是哪一格")
    public void testStaticOptionMissingLabelOrValue() {
        assertTrue(onlyError(validateNew(optionRule("select", null, options(option(null, "x")))))
                .contains("没有 label"));
        assertTrue(onlyError(validateNew(optionRule("select", null, options(option("x", null)))))
                .contains("没有 value"));
    }

    @Test
    @DisplayName("options 写成对象而不是数组 ⟹ 报错")
    public void testOptionsNotAnArray() {
        JSONObject rule = optionRule("select", null, null);
        rule.set(FormFieldFactory.OPTIONS_KEY, new JSONObject().set("label", "待处理"));

        assertTrue(onlyError(validateNew(rule)).contains("不是数组"));
    }

    /**
     * 🔴 判据入口<b>必须归一</b>：改表单那条路收的是库里存着的原样 rule，
     * 那里真会出现 {@code Select} / {@code dropdown}（{@code WIDGET_TYPE_ALIASES} 存在的理由就是它）。
     * 拿原样值查名单会把「其实是选项控件」判成「不是」，于是这条校验在最需要它的存量上不开口。
     *
     * <p>断言的是<b>返回的规范名</b>而不是 boolean：调用方（规划期 V29）拿它去建试探用的 rule，
     * 归一归错一个字符，那条 rule 就判不到同一格上
     */
    @Test
    @DisplayName("别名与大小写变体照样被判据认出来，并给出规范名")
    public void testAliasAndCaseVariantsAreRecognized() {
        assertEquals("select", validator.optionWidgetCanonical("Select"), "大小写变体");
        assertEquals("select", validator.optionWidgetCanonical("dropdown"), "语义别名");
        assertEquals("radio", validator.optionWidgetCanonical("radio"));
        assertNull(validator.optionWidgetCanonical("input"));
        assertNull(validator.optionWidgetCanonical("noSuchWidget"), "认不出的交给控件白名单那条报错");
        assertNull(validator.optionWidgetCanonical(null), "不能炸");
        assertNull(validator.optionWidgetCanonical("  "), "空白同样不能炸");

        assertFalse(validateNew(optionRule("dropdown", null, null)).isPassed(),
                "写 dropdown 的也该被拦：它的规范名就是 select");
    }

    /**
     * 🔴 探测本身故障时<b>不拦</b>：与 {@code loadValidationPatterns} 同一条纪律。
     * 查字典失败就拒绝建表单，等于让一个基础设施抖动挡掉一整条业务流程
     */
    @Test
    @DisplayName("字典查询抛异常时跳过该字段，不拦整张表单")
    public void testDictProbeFailureFailsSoft() {
        when(dictDataApi.getDictType("common_status")).thenThrow(new RuntimeException("字典服务不可用"));

        AgentValidateResult result = validateNew(optionRule("select", dictProps("common_status"), null));

        assertTrue(result.isPassed(), "探测故障不该变成一条错误：" + result.getErrors());
    }

    @Test
    @DisplayName("非选项类控件配了 dictSource ⟹ 本层沉默（规划期 V29 才管这件事）")
    public void testNonOptionWidgetWithDictSourceIsSilentHere() {
        AgentValidateResult result = validateNew(optionRule("input", dictProps("common_status"), null));

        assertTrue(result.isPassed(), "" + result.getErrors());
    }

}
