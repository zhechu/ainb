package cn.enlev.ainb.module.infra.framework.formdesign.core.validation;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicRuleEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Spy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段格式与跨字段校验的服务端复校验测试
 *
 * 守的是「前端的格式校验能被绕过」这件事：这些用例模拟的都是直接构造请求，
 * 手机号填 abc、结束日期早于开始日期。这类脏数据的特点是当时不报错、
 * 事后无法追溯，所以服务端必须真的拦下来。
 *
 * @author 走进新时代
 */
public class FormValidationGuardTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormValidationGuard guard;

    /**
     * 用真实注册表与真实求值器：要守的正是「配了什么正则，服务端就按它拦」
     * 这条完整链路，mock 掉任何一环等于把被测逻辑一起 mock 掉了
     */
    @Spy
    private ValidationPatternRegistry patternRegistry = new ValidationPatternRegistry();
    @Spy
    private LogicRuleEvaluator logicRuleEvaluator = new LogicRuleEvaluator();

    @BeforeEach
    void setUp() {
        // 注册表的正则是在 @PostConstruct 里编译的，单测不经过容器要手动调
        patternRegistry.init();
    }

    // ==================== 造数据的辅助方法 ====================

    /** 造一个配了内置正则的字段 */
    private String builtinField(String field, String title, String patternKey) {
        return String.format("{\"type\":\"input\",\"field\":\"%s\",\"title\":\"%s\","
                        + "\"props\":{\"validateRules\":[{\"type\":\"BUILTIN\",\"patternKey\":\"%s\"}]}}",
                field, title, patternKey);
    }

    private String fields(String... rules) {
        return FormFieldsCodec.write(List.of(rules));
    }

    private Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private FormValidationGuard.ValidationResult validate(String fieldsJson, Map<String, Object> data) {
        return guard.validate(fieldsJson, null, data);
    }

    // ==================== 内置正则 ====================

    @Test
    @DisplayName("内置正则拦住格式不对的手机号")
    void testBuiltinReject() {
        FormValidationGuard.ValidationResult result = validate(
                fields(builtinField("phone", "手机号", "MOBILE")), data("phone", "abc"));
        assertFalse(result.isPassed());
        // 报错必须带字段【标题】而不是字段 ID —— 用户看不懂 fc_a1b2c3
        assertTrue(result.toMessage().contains("手机号"), result.toMessage());
    }

    @Test
    @DisplayName("格式正确的值放过")
    void testBuiltinPass() {
        assertTrue(validate(fields(builtinField("phone", "手机号", "MOBILE")),
                data("phone", "13812345678")).isPassed());
    }

    @Test
    @DisplayName("⚠️ 空值不校验格式 —— 否则「非必填 + 有格式」的字段永远填不了空")
    void testEmptySkipped() {
        String fieldsJson = fields(builtinField("phone", "手机号", "MOBILE"));
        assertTrue(validate(fieldsJson, data("phone", null)).isPassed());
        assertTrue(validate(fieldsJson, data("phone", "")).isPassed());
        // 字段根本没出现在提交数据里，同样放过。必填是另一条路径（FormLogicGuard）的职责
        assertTrue(validate(fieldsJson, data()).isPassed());
    }

    @Test
    @DisplayName("数值型提交值按字符串校验 —— 表单里的数字常常是 String，也常常是 Number")
    void testNumericValue() {
        String fieldsJson = fields(builtinField("qty", "数量", "POSITIVE_INTEGER"));
        assertTrue(validate(fieldsJson, data("qty", 12)).isPassed());
        assertTrue(validate(fieldsJson, data("qty", "12")).isPassed());
        assertFalse(validate(fieldsJson, data("qty", 0)).isPassed());
        assertFalse(validate(fieldsJson, data("qty", "-3")).isPassed());
    }

    @Test
    @DisplayName("⚠️ 非标量值跳过格式校验 —— 对数组做正则匹配得先编一套拼接语义")
    void testNonScalarSkipped() {
        assertTrue(validate(fields(builtinField("tags", "标签", "MOBILE")),
                data("tags", List.of("a", "b"))).isPassed());
    }

    @Test
    @DisplayName("⚠️ 引用了不存在的内置正则时放过 —— 拦下来会让一次正则库调整使存量表单集体提交失败")
    void testUnknownPatternKeyPasses() {
        assertTrue(validate(fields(builtinField("phone", "手机号", "NOT_EXISTS_KEY")),
                data("phone", "abc")).isPassed());
    }

    // ==================== 自定义正则 ====================

    @Test
    @DisplayName("自定义正则与自定义提示")
    void testCustomRegex() {
        String field = "{\"type\":\"input\",\"field\":\"no\",\"title\":\"编号\","
                + "\"props\":{\"validateRules\":[{\"type\":\"REGEX\","
                + "\"pattern\":\"^AB[0-9]{4}$\",\"message\":\"必须是 AB 开头加 4 位数字\"}]}}";
        FormValidationGuard.ValidationResult result = validate(fields(field), data("no", "XX1234"));
        assertFalse(result.isPassed());
        assertEquals("「编号」必须是 AB 开头加 4 位数字", result.toMessage());
        assertTrue(validate(fields(field), data("no", "AB1234")).isPassed());
    }

    @Test
    @DisplayName("⚠️ 正则语法错误时放过 —— 用户配错了正则，不该表现为他的输入被拒绝")
    void testInvalidRegexPasses() {
        String field = "{\"type\":\"input\",\"field\":\"no\",\"title\":\"编号\","
                + "\"props\":{\"validateRules\":[{\"type\":\"REGEX\",\"pattern\":\"^[a-\"}]}}";
        assertTrue(validate(fields(field), data("no", "anything")).isPassed());
    }

    @Test
    @DisplayName("多条规则逐条校验，只报第一条不通过的")
    void testMultipleRules() {
        String field = "{\"type\":\"input\",\"field\":\"no\",\"title\":\"编号\","
                + "\"props\":{\"validateRules\":["
                + "{\"type\":\"REGEX\",\"pattern\":\"^[0-9]+$\",\"message\":\"只能是数字\"},"
                + "{\"type\":\"REGEX\",\"pattern\":\"^.{6}$\",\"message\":\"必须是 6 位\"}]}}";
        // 两条都不满足，但只报第一条 —— 同一个值同时违反两条时，报两条没有额外信息
        assertEquals("「编号」只能是数字", validate(fields(field), data("no", "abc")).toMessage());
        // 第一条满足、第二条不满足
        assertEquals("「编号」必须是 6 位", validate(fields(field), data("no", "123")).toMessage());
        assertTrue(validate(fields(field), data("no", "123456")).isPassed());
    }

    @Test
    @DisplayName("停用的规则不生效")
    void testDisabledRule() {
        String field = "{\"type\":\"input\",\"field\":\"no\",\"title\":\"编号\","
                + "\"props\":{\"validateRules\":[{\"type\":\"REGEX\",\"pattern\":\"^[0-9]+$\","
                + "\"enabled\":false}]}}";
        assertTrue(validate(fields(field), data("no", "abc")).isPassed());
    }

    // ==================== 布局容器与子表 ====================

    @Test
    @DisplayName("藏在布局容器里的字段照样校验 —— 栅格是最常见的布局，漏了它等于大半表单没校验")
    void testInsideLayoutContainer() {
        String nested = "{\"type\":\"col\",\"children\":["
                + builtinField("phone", "手机号", "MOBILE") + "]}";
        assertFalse(validate(fields(nested), data("phone", "abc")).isPassed());
    }

    @Test
    @DisplayName("子表明细列的报错带行号，同列多行合并成一条")
    void testSubTableRows() {
        String sub = "{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":["
                + builtinField("mobile", "联系电话", "MOBILE") + "]}}";
        Map<String, Object> row1 = data("mobile", "abc");
        Map<String, Object> row2 = data("mobile", "13812345678");
        Map<String, Object> row3 = data("mobile", "xyz");
        FormValidationGuard.ValidationResult result = validate(fields(sub),
                data("items", List.of(row1, row2, row3)));
        assertFalse(result.isPassed());
        // 行号从 1 开始，与界面一致；第 2 行格式正确不该出现在报错里
        assertEquals(1, result.getErrors().size(), result.toMessage());
        assertTrue(result.toMessage().contains("第 1、3"), result.toMessage());
        assertTrue(result.toMessage().contains("明细"), result.toMessage());
    }

    @Test
    @DisplayName("子表里的空单元格不校验格式")
    void testSubTableEmptyCell() {
        String sub = "{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":["
                + builtinField("mobile", "联系电话", "MOBILE") + "]}}";
        assertTrue(validate(fields(sub), data("items", List.of(data("mobile", "")))).isPassed());
    }

    // ==================== 跨字段校验 ====================

    /** 结束日期 ≥ 开始日期：when 为空恒真，must 用 FIELD 右值 */
    private static final String RULE_DATE_RANGE = "[{\"id\":\"r1\",\"name\":\"日期区间\","
            + "\"must\":{\"logic\":\"AND\",\"items\":[{\"field\":\"endDate\",\"operator\":\"GE\","
            + "\"valueType\":\"FIELD\",\"value\":\"startDate\"}]},"
            + "\"message\":\"结束日期不能早于开始日期\"}]";

    @Test
    @DisplayName("跨字段：结束日期早于开始日期被拦下")
    void testCrossFieldDateRange() {
        FormValidationGuard.ValidationResult result = guard.validate(null, RULE_DATE_RANGE,
                data("startDate", "2026-09-10", "endDate", "2026-09-01"));
        assertFalse(result.isPassed());
        assertEquals("结束日期不能早于开始日期", result.toMessage());

        assertTrue(guard.validate(null, RULE_DATE_RANGE,
                data("startDate", "2026-09-01", "endDate", "2026-09-10")).isPassed());
    }

    @Test
    @DisplayName("⚠️ 断言里的操作数还没填时不检查 —— 否则表单填一半就被拦下")
    void testAssertionNotApplicable() {
        // 只填了开始日期，结束日期还没填 —— 此时判不了「结束 ≥ 开始」
        assertTrue(guard.validate(null, RULE_DATE_RANGE,
                data("startDate", "2026-09-10", "endDate", "")).isPassed());
        assertTrue(guard.validate(null, RULE_DATE_RANGE,
                data("startDate", "2026-09-10")).isPassed());
        // 反过来也一样：右值那个字段还没填
        assertTrue(guard.validate(null, RULE_DATE_RANGE,
                data("startDate", "", "endDate", "2026-09-01")).isPassed());
        // 两个都填了才检查
        assertFalse(guard.validate(null, RULE_DATE_RANGE,
                data("startDate", "2026-09-10", "endDate", "2026-09-01")).isPassed());
    }

    @Test
    @DisplayName("EMPTY / NOT_EMPTY 不受上一条影响 —— 它们问的就是「空不空」")
    void testEmptinessOperatorsStillApply() {
        String rules = "[{\"id\":\"r1\","
                + "\"must\":{\"items\":[{\"field\":\"taxNo\",\"operator\":\"NOT_EMPTY\"}]},"
                + "\"message\":\"税号必填\"}]";
        assertFalse(guard.validate(null, rules, data("taxNo", "")).isPassed());
        assertFalse(guard.validate(null, rules, data()).isPassed());
        assertTrue(guard.validate(null, rules, data("taxNo", "T9")).isPassed());
    }

    @Test
    @DisplayName("跨字段：A 填了则 B 必填")
    void testCrossFieldConditionalRequired() {
        String rules = "[{\"id\":\"r1\",\"name\":\"填了发票号就要填税号\","
                + "\"when\":{\"items\":[{\"field\":\"invoiceNo\",\"operator\":\"NOT_EMPTY\"}]},"
                + "\"must\":{\"items\":[{\"field\":\"taxNo\",\"operator\":\"NOT_EMPTY\"}]},"
                + "\"message\":\"填写发票号后，税号必填\"}]";
        // 前置条件不满足 → 整条规则跳过
        assertTrue(guard.validate(null, rules, data("invoiceNo", "", "taxNo", "")).isPassed());
        // 前置条件满足但断言不满足 → 拦下
        assertFalse(guard.validate(null, rules, data("invoiceNo", "A001", "taxNo", "")).isPassed());
        assertTrue(guard.validate(null, rules, data("invoiceNo", "A001", "taxNo", "T9")).isPassed());
    }

    @Test
    @DisplayName("跨字段：A 与 B 不能同时为空")
    void testCrossFieldNotBothEmpty() {
        String rules = "[{\"id\":\"r1\",\"name\":\"联系方式\","
                + "\"when\":{\"items\":[{\"field\":\"mobile\",\"operator\":\"EMPTY\"}]},"
                + "\"must\":{\"items\":[{\"field\":\"email\",\"operator\":\"NOT_EMPTY\"}]},"
                + "\"message\":\"手机号与邮箱至少填一个\"}]";
        assertFalse(guard.validate(null, rules, data("mobile", "", "email", "")).isPassed());
        assertTrue(guard.validate(null, rules, data("mobile", "138", "email", "")).isPassed());
        assertTrue(guard.validate(null, rules, data("mobile", "", "email", "a@b.c")).isPassed());
    }

    @Test
    @DisplayName("⚠️ 规则 JSON 坏掉时跳过全部跨字段校验，而不是让提交失败")
    void testBrokenRulesJson() {
        assertTrue(guard.validate(null, "{不是数组", data("a", 1)).isPassed());
    }

    @Test
    @DisplayName("⚠️ 缺断言或缺提示的规则跳过 —— 一条没配完的校验不该恒定拦住提交")
    void testIncompleteCrossFieldRule() {
        // 没有 must
        assertTrue(guard.validate(null,
                "[{\"id\":\"r1\",\"message\":\"x\"}]", data("a", 1)).isPassed());
        // 有 must 没有 message
        assertTrue(guard.validate(null,
                "[{\"id\":\"r1\",\"must\":{\"items\":[{\"field\":\"a\",\"operator\":\"NOT_EMPTY\"}]}}]",
                data("a", "")).isPassed());
    }

    @Test
    @DisplayName("停用的跨字段规则不生效")
    void testDisabledCrossFieldRule() {
        String rules = "[{\"id\":\"r1\",\"enabled\":false,"
                + "\"must\":{\"items\":[{\"field\":\"a\",\"operator\":\"NOT_EMPTY\"}]},"
                + "\"message\":\"a 必填\"}]";
        assertTrue(guard.validate(null, rules, data("a", "")).isPassed());
    }

    // ==================== 错误汇总 ====================

    @Test
    @DisplayName("多处错误一次性全报 —— 改完一处再提交又被拦下一处，用户要来回好几轮")
    void testErrorsAggregated() {
        String fieldsJson = fields(
                builtinField("phone", "手机号", "MOBILE"),
                builtinField("email", "邮箱", "EMAIL"));
        String rules = "[{\"id\":\"r1\","
                + "\"must\":{\"items\":[{\"field\":\"amount\",\"operator\":\"GT\",\"value\":0}]},"
                + "\"message\":\"金额必须大于 0\"}]";
        FormValidationGuard.ValidationResult result = guard.validate(fieldsJson, rules,
                data("phone", "abc", "email", "not-an-email", "amount", 0));
        assertEquals(3, result.getErrors().size(), result.toMessage());
    }

}
