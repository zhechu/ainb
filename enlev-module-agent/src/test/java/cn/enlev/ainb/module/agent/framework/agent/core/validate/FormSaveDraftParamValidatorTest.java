package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * {@code form_save_draft} 规划期参数校验的单元测试
 *
 * <h3>这里测的是「用户看到的卡片必须能执行成功」</h3>
 * 规划期漏拦一个问题，它就会变成一个用户点了确认才失败的计划 ——
 * 而执行阶段没有回灌重规划的循环，AI 连自修正的机会都没有。
 *
 * @author 走进新时代
 */
public class FormSaveDraftParamValidatorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormSaveDraftParamValidator validator;

    @Mock
    private FormDesignApi formDesignApi;

    @BeforeEach
    public void setUp() {
        List<FormDesignApi.WidgetSimpleRespDTO> widgets = new ArrayList<>();
        widgets.add(widget("input", "单行输入", true, false));
        widgets.add(widget("textarea", "多行输入", true, false));
        widgets.add(widget("inputNumber", "数字", true, false));
        widgets.add(widget("switch", "开关", true, false));
        widgets.add(widget("datePicker", "日期", true, false));
        widgets.add(widget("dateRange", "日期区间", true, false));
        widgets.add(widget("timePicker", "时间", true, false));
        widgets.add(widget("select", "下拉", true, false));
        widgets.add(widget("oldSelect", "旧下拉", true, true));    // 已废弃
        widgets.add(widget("brokenWidget", "坏控件", false, false)); // 已停用
        // 这两个刻意与 dateTimePicker 高度相似（都含 date/picker），
        // 好让「不推荐不可用控件」这条真的被测到 —— 光靠 oldSelect 那种
        // 词交集为 0 的控件，过滤逻辑失效了测试也照样绿
        widgets.add(widget("oldDatePicker", "旧日期", true, true));        // 已废弃
        widgets.add(widget("brokenDatePicker", "坏日期", false, false));    // 已停用
        lenient().when(formDesignApi.getWidgetContracts()).thenReturn(widgets);
    }

    private FormDesignApi.WidgetSimpleRespDTO widget(String type, String label,
                                                     boolean supported, boolean deprecated) {
        FormDesignApi.WidgetSimpleRespDTO dto = new FormDesignApi.WidgetSimpleRespDTO();
        dto.setType(type);
        dto.setLabel(label);
        dto.setSupported(supported);
        dto.setDeprecated(deprecated);
        return dto;
    }

    /**
     * 造一份 form_save_draft 的参数
     *
     * @param widgets 每个字段的控件类型，字段名自动生成
     */
    private Map<String, Object> params(String... widgets) {
        JSONArray fields = new JSONArray();
        for (int i = 0; i < widgets.length; i++) {
            JSONObject field = new JSONObject();
            field.set("field", "field_" + i);
            field.set("widget", widgets[i]);
            field.set("title", "字段" + i);
            field.set("jdbcType", "VARCHAR");
            fields.add(field);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "控件验证测试");
        params.put("code", "widget_verify_test");
        params.put("mainTable", "biz_widget_verify");
        params.put("fields", fields.toString());
        return params;
    }

    @Test
    @DisplayName("合规的参数要能通过")
    public void testValid() {
        AgentValidateResult result = validator.validate(
                params("input", "textarea", "datePicker", "select"));

        assertTrue(result.isPassed(), "不该报错：" + result.getErrors());
    }

    /**
     * 🔴 直觉名 date 应归一到契约 datePicker 后通过，不在规划期白烧一轮自修正
     *（与 FormSchemaValidator / FormSaveDraftTool 同一口径）
     */
    @Test
    @DisplayName("🔴 直觉名 date 归一到 datePicker，规划期直接通过")
    public void testAliasDateAccepted() {
        AgentValidateResult result = validator.validate(params("date", "number", "bool"));

        assertTrue(result.isPassed(),
                "date/number/bool 应分别归一到 datePicker/inputNumber/switch：" + result.getErrors());
    }

    @Test
    @DisplayName("只支持 form_save_draft")
    public void testSupports() {
        assertTrue(validator.supports("form_save_draft"));
        assertFalse(validator.supports("form_update"));
    }

    // ==================== 控件白名单 ====================

    /**
     * 🔴 复现线上那个 BUG：模型凭记忆编了 dateTimePicker。
     *    契约里日期时间不是独立控件，要用 datePicker 配 valueFormat。
     *    这条必须在规划期就拦下来，否则它会变成一张点了确认才失败的卡片
     */
    @Test
    @DisplayName("🔴 幻觉出的控件类型在规划期被拒，并给出可用的候选")
    public void testHallucinatedWidgetRejected() {
        AgentValidateResult result = validator.validate(params("dateTimePicker"));

        assertFalse(result.isPassed());
        String feedback = result.toFeedback();
        assertTrue(feedback.contains("dateTimePicker"), "要指名道姓：" + feedback);
        assertTrue(feedback.contains("widget_contract_get"), "要告诉模型去哪查：" + feedback);
        assertTrue(feedback.contains("datePicker"),
                "要给出最接近的可用类型，让它一轮就能改对：" + feedback);
    }

    @Test
    @DisplayName("候选按相似度排序，且不推荐已停用和已废弃的控件")
    public void testSuggestionExcludesUnusable() {
        AgentValidateResult result = validator.validate(params("dateTimePicker"));

        // oldDatePicker（废弃）与 brokenDatePicker（停用）和 dateTimePicker 的词交集
        // 同样是 2，过滤失效的话它们就会挤进候选
        assertEquals("最接近的可用类型：datePicker、timePicker、dateRange。",
                suggestionsOf(result.toFeedback()));
    }

    @Test
    @DisplayName("找不出候选时不硬凑，只提示去查白名单")
    public void testNoSuggestion() {
        AgentValidateResult result = validator.validate(params("zzz"));

        assertFalse(result.isPassed());
        assertTrue(suggestionsOf(result.toFeedback()).isEmpty(),
                "没有交集就不要给候选：" + result.toFeedback());
        assertTrue(result.toFeedback().contains("widget_contract_get"),
                "不给候选也得告诉它去哪查：" + result.toFeedback());
    }

    /**
     * 截出反馈里的候选那一句
     */
    private String suggestionsOf(String feedback) {
        int start = feedback.indexOf("最接近的可用类型：");
        if (start < 0) {
            return "";
        }
        return feedback.substring(start, feedback.indexOf("。", start) + 1);
    }

    @Test
    @DisplayName("已停用的控件被拒")
    public void testUnsupportedWidget() {
        AgentValidateResult result = validator.validate(params("brokenWidget"));
        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("已停用")),
                "实际：" + result.getErrors());
    }

    @Test
    @DisplayName("已废弃的控件只警告，不拦")
    public void testDeprecatedWidget() {
        AgentValidateResult result = validator.validate(params("oldSelect"));

        assertTrue(result.isPassed(), "废弃不等于不可用：" + result.getErrors());
        assertFalse(result.getWarnings().isEmpty());
    }

    // ==================== 其余浅校验（回归） ====================

    @Test
    @DisplayName("缺 widget 被拒")
    public void testMissingWidget() {
        Map<String, Object> params = params("input");
        JSONArray fields = new JSONArray();
        JSONObject field = new JSONObject();
        field.set("field", "order_no");
        field.set("title", "订单号");
        field.set("jdbcType", "VARCHAR");
        fields.add(field);
        params.put("fields", fields.toString());

        AgentValidateResult result = validator.validate(params);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("widget")),
                "实际：" + result.getErrors());
    }

    @Test
    @DisplayName("表单编码不是全小写下划线被拒，并给出改法")
    public void testInvalidCode() {
        Map<String, Object> params = params("input");
        params.put("code", "WidgetVerifyTest");

        AgentValidateResult result = validator.validate(params);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream()
                        .anyMatch(e -> e.contains("widget_verify_test")),
                "要直接给出改好的样子：" + result.getErrors());
    }

    /**
     * 🔴 这条的后果是<b>跨租户写入</b>
     */
    @Test
    @DisplayName("🔴 映射到 tenant_id 必须硬拦")
    public void testTenantIdRejected() {
        JSONArray fields = new JSONArray();
        JSONObject field = new JSONObject();
        field.set("field", "org_id");
        field.set("widget", "input");
        field.set("title", "组织");
        field.set("jdbcType", "BIGINT");
        field.set("column", "tenant_id");
        fields.add(field);
        Map<String, Object> params = params("input");
        params.put("fields", fields.toString());

        AgentValidateResult result = validator.validate(params);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("跨租户")),
                "实际：" + result.getErrors());
    }

    @Test
    @DisplayName("一次把所有问题列出来 —— 每多一轮都是一次真实的模型调用")
    public void testCollectsAll() {
        AgentValidateResult result = validator.validate(
                params("dateTimePicker", "brokenWidget", "noSuchWidget"));

        assertTrue(result.getErrors().size() >= 3,
                "应一次列出全部问题，实际 " + result.getErrors().size() + " 条："
                        + result.getErrors());
    }

    @Test
    @DisplayName("缺必填参数被拒")
    public void testMissingRequired() {
        Map<String, Object> params = params("input");
        params.remove("mainTable");

        assertFalse(validator.validate(params).isPassed());
    }

    @Test
    @DisplayName("fields 为空被拒")
    public void testEmptyFields() {
        Map<String, Object> params = params("input");
        params.put("fields", "[]");

        assertFalse(validator.validate(params).isPassed());
    }

}
