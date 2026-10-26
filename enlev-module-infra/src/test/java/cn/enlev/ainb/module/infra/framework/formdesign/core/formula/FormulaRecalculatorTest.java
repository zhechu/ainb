package cn.enlev.ainb.module.infra.framework.formdesign.core.formula;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Spy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公式字段服务端重算的对抗性测试
 *
 * 对应设计方案 §9.3 第 3 条「篡改公式结果」。
 *
 * 这些用例模拟的不是正常操作，而是拿 curl 直接打接口：前端算出来的合计只是显示，
 * 能被绕过；这里验证服务端是不是真的以自己的计算结果为准。
 *
 * @author 走进新时代
 */
public class FormulaRecalculatorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormulaRecalculator recalculator;

    /** 用真实求值器：要守的是「公式怎么写，服务端就怎么算」这条链路 */
    @Spy
    private FormulaEvaluator evaluator = new FormulaEvaluator();

    /**
     * 造一个公式字段的规则
     */
    private String formulaRule(String field, String astJson, Integer precision) {
        String precisionPart = precision == null ? "" : ",\"precision\":" + precision;
        return String.format(
                "{\"type\":\"Formula\",\"field\":\"%s\",\"title\":\"%s\","
                        + "\"props\":{\"ast\":%s%s}}",
                field, field, astJson, precisionPart);
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

    private BigDecimal num(Object value) {
        return evaluator.toDecimal(value);
    }

    // ==================== 篡改必须被覆盖 ====================

    @Test
    @DisplayName("⚠️ 篡改公式字段的提交值 → 服务端以自己算的为准")
    void testTamperedValueOverwritten() {
        // total = SUM(items.price)
        String ast = "{\"type\":\"call\",\"name\":\"SUM\",\"args\":"
                + "[{\"type\":\"subfield\",\"sub\":\"items\",\"field\":\"price\"}]}";
        // 明细合计是 30，但攻击者提交 total = 1
        Map<String, Object> submitted = data(
                "items", List.of(Map.of("price", 10), Map.of("price", 20)),
                "total", 1);

        Map<String, Object> result =
                recalculator.recalculate(fields(formulaRule("total", ast, null)), submitted);

        assertEquals(0, new BigDecimal("30").compareTo(num(result.get("total"))),
                "公式字段必须以服务端重算结果为准，否则构造请求就能写入任意金额");
    }

    @Test
    @DisplayName("⚠️ 前端漏传公式字段时，服务端补算出来")
    void testMissingValueComputed() {
        String ast = "{\"type\":\"binary\",\"op\":\"*\","
                + "\"left\":{\"type\":\"field\",\"name\":\"qty\"},"
                + "\"right\":{\"type\":\"field\",\"name\":\"price\"}}";
        Map<String, Object> submitted = data("qty", 3, "price", 7);

        Map<String, Object> result =
                recalculator.recalculate(fields(formulaRule("amount", ast, null)), submitted);

        assertEquals(0, new BigDecimal("21").compareTo(num(result.get("amount"))));
    }

    @Test
    @DisplayName("非公式字段的提交值原样保留")
    void testNonFormulaFieldsUntouched() {
        String ast = "{\"type\":\"num\",\"value\":1}";
        Map<String, Object> submitted = data("remark", "用户填的备注", "x", 1);

        Map<String, Object> result =
                recalculator.recalculate(fields(formulaRule("f", ast, null)), submitted);

        assertEquals("用户填的备注", result.get("remark"));
        assertEquals(1, result.get("x"));
    }

    // ==================== 依赖顺序 ====================

    @Test
    @DisplayName("⚠️ 公式引用别的公式时，按依赖顺序算 —— 否则会拿到前端提交的旧值")
    void testDependencyOrder() {
        // subtotal = SUM(items.amount)；tax = subtotal * 0.06
        String subtotalAst = "{\"type\":\"call\",\"name\":\"SUM\",\"args\":"
                + "[{\"type\":\"subfield\",\"sub\":\"items\",\"field\":\"amount\"}]}";
        String taxAst = "{\"type\":\"binary\",\"op\":\"*\","
                + "\"left\":{\"type\":\"field\",\"name\":\"subtotal\"},"
                + "\"right\":{\"type\":\"num\",\"value\":0.06}}";

        // 故意把 tax 的规则放在 subtotal 【前面】，并提交一个伪造的 subtotal。
        // 不做拓扑排序的话 tax 会用伪造值算出 60
        Map<String, Object> submitted = data(
                "items", List.of(Map.of("amount", 100), Map.of("amount", 200)),
                "subtotal", 1000);

        Map<String, Object> result = recalculator.recalculate(
                fields(formulaRule("tax", taxAst, null), formulaRule("subtotal", subtotalAst, null)),
                submitted);

        assertEquals(0, new BigDecimal("300").compareTo(num(result.get("subtotal"))));
        assertEquals(0, new BigDecimal("18").compareTo(num(result.get("tax"))),
                "tax 必须基于服务端算出的 subtotal(300)，而不是前端提交的 1000");
    }

    @Test
    @DisplayName("公式循环引用时不死循环，也不抛异常")
    void testCyclicFormulasTolerated() {
        // a = b + 1；b = a + 1
        String aAst = "{\"type\":\"binary\",\"op\":\"+\","
                + "\"left\":{\"type\":\"field\",\"name\":\"b\"},\"right\":{\"type\":\"num\",\"value\":1}}";
        String bAst = "{\"type\":\"binary\",\"op\":\"+\","
                + "\"left\":{\"type\":\"field\",\"name\":\"a\"},\"right\":{\"type\":\"num\",\"value\":1}}";

        Map<String, Object> result = assertDoesNotThrow(() -> recalculator.recalculate(
                fields(formulaRule("a", aAst, null), formulaRule("b", bAst, null)),
                data("a", 0, "b", 0)));

        // 结果顺序不确定，但必须算出东西且不崩 —— 发布校验才是拦环的正主
        assertNotNull(result.get("a"));
        assertNotNull(result.get("b"));
    }

    // ==================== 精度 ====================

    @Test
    @DisplayName("按配置的精度收敛结果")
    void testPrecisionApplied() {
        String ast = "{\"type\":\"binary\",\"op\":\"/\","
                + "\"left\":{\"type\":\"num\",\"value\":10},\"right\":{\"type\":\"num\",\"value\":3}}";

        Map<String, Object> result =
                recalculator.recalculate(fields(formulaRule("v", ast, 2)), data());

        assertEquals(0, new BigDecimal("3.33").compareTo(num(result.get("v"))));
    }

    // ==================== 容错 ====================

    @Test
    @DisplayName("公式字段没配 AST 时跳过，不影响其它字段")
    void testMissingAstSkipped() {
        String rule = "{\"type\":\"Formula\",\"field\":\"empty\",\"props\":{}}";
        Map<String, Object> result = assertDoesNotThrow(() ->
                recalculator.recalculate(fields(rule), data("other", 1)));
        assertEquals(1, result.get("other"));
    }

    @Test
    @DisplayName("AST 坏掉时跳过该字段，不让整次提交失败")
    void testBrokenAstSkipped() {
        String rule = "{\"type\":\"Formula\",\"field\":\"bad\",\"props\":{\"ast\":\"不是对象\"}}";
        assertDoesNotThrow(() -> recalculator.recalculate(fields(rule), data("other", 1)));
    }

    @Test
    @DisplayName("表单里没有公式字段时原样返回")
    void testNoFormulaFields() {
        String rule = "{\"type\":\"input\",\"field\":\"a\"}";
        Map<String, Object> result = recalculator.recalculate(fields(rule), data("a", 1));
        assertEquals(1, result.get("a"));
    }

    @Test
    @DisplayName("入参数据不被修改")
    void testInputNotMutated() {
        String ast = "{\"type\":\"num\",\"value\":9}";
        Map<String, Object> submitted = data("v", 1);
        recalculator.recalculate(fields(formulaRule("v", ast, null)), submitted);
        assertEquals(1, submitted.get("v"), "重算不得就地修改入参");
    }

    @Test
    @DisplayName("formFields 为空或坏掉时不抛异常")
    void testBrokenFormFields() {
        assertDoesNotThrow(() -> recalculator.recalculate(null, data("a", 1)));
        assertDoesNotThrow(() -> recalculator.recalculate("不是 JSON", data("a", 1)));
    }

    @Test
    @DisplayName("⚠️ 藏在布局容器里的公式字段也要被找到")
    void testFormulaInsideLayoutContainer() {
        String ast = "{\"type\":\"num\",\"value\":42}";
        String nested = "{\"type\":\"fcRow\",\"children\":[{\"type\":\"col\",\"children\":["
                + formulaRule("deep", ast, null) + "]}]}";

        Map<String, Object> result = recalculator.recalculate(fields(nested), data("deep", 1));

        assertEquals(0, new BigDecimal("42").compareTo(num(result.get("deep"))),
                "公式控件常被放进栅格，不递归就等于这些字段完全没有服务端保护");
    }

    // ==================== 子表内的公式 ====================

    /** 造一个带内部公式的子表控件规则。⚠️ 内部规则挂在 props.rule 下，不是 children */
    private String subTableRule(String subField, String innerRules) {
        return String.format(
                "{\"type\":\"group\",\"field\":\"%s\",\"title\":\"%s\","
                        + "\"props\":{\"rule\":[%s]}}",
                subField, subField, innerRules);
    }

    private static final String MULTIPLY_QTY_PRICE =
            "{\"type\":\"binary\",\"op\":\"*\","
                    + "\"left\":{\"type\":\"field\",\"name\":\"qty\"},"
                    + "\"right\":{\"type\":\"field\",\"name\":\"price\"}}";

    @Test
    @DisplayName("⚠️ 子表里的公式按行重算 —— 服务端从前根本不收集它们")
    @SuppressWarnings("unchecked")
    public void testSubTableFormulaRecalculated() {
        // collect 原来只走 children，而子表的内部规则挂在 props.rule 下。
        // 于是子表里的公式服务端从不重算，前端提交什么就存什么 ——
        // 与本模块「推导值一律服务端重算覆盖」的原则相悖
        String formFields = fields(subTableRule("items",
                formulaRule("amount", MULTIPLY_QTY_PRICE, 2)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", List.of(
                new LinkedHashMap<>(Map.of("qty", 2, "price", 100)),
                new LinkedHashMap<>(Map.of("qty", 3, "price", 50.5))));

        Map<String, Object> result = recalculator.recalculate(formFields, data);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("items");
        assertEquals(0, new BigDecimal("200").compareTo(num(rows.get(0).get("amount"))));
        assertEquals(0, new BigDecimal("151.5").compareTo(num(rows.get(1).get("amount"))));
    }

    @Test
    @DisplayName("⚠️ 明细行里伪造的小计会被覆盖掉")
    @SuppressWarnings("unchecked")
    public void testSubTableFormulaOverridesSubmitted() {
        String formFields = fields(subTableRule("items",
                formulaRule("amount", MULTIPLY_QTY_PRICE, 2)));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("qty", 2);
        row.put("price", 100);
        row.put("amount", new BigDecimal("1"));   // 绕过界面直接送一个假的小计
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", List.of(row));

        Map<String, Object> result = recalculator.recalculate(formFields, data);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("items");
        assertEquals(0, new BigDecimal("200").compareTo(num(rows.get(0).get("amount"))));
    }

    @Test
    @DisplayName("子表公式用的是【这一行】的值，不是主表上的同名字段")
    @SuppressWarnings("unchecked")
    public void testSubTableFormulaUsesRowScope() {
        // 主表上也有 qty / price，但它们与明细无关。
        // 作用域搞混的话，两行明细会算出一样的结果
        String formFields = fields(subTableRule("items",
                formulaRule("amount", MULTIPLY_QTY_PRICE, 2)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("qty", 999);
        data.put("price", 999);
        data.put("items", List.of(
                new LinkedHashMap<>(Map.of("qty", 1, "price", 10)),
                new LinkedHashMap<>(Map.of("qty", 2, "price", 10))));

        Map<String, Object> result = recalculator.recalculate(formFields, data);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("items");
        assertEquals(0, new BigDecimal("10").compareTo(num(rows.get(0).get("amount"))));
        assertEquals(0, new BigDecimal("20").compareTo(num(rows.get(1).get("amount"))));
    }

    @Test
    @DisplayName("子表数据缺失或元素不是对象时不抛异常")
    public void testSubTableFormulaTolerantToBadRows() {
        String formFields = fields(subTableRule("items",
                formulaRule("amount", MULTIPLY_QTY_PRICE, 2)));

        // 一行坏数据不该让整次提交失败，子表校验那一步会用更准确的话报出来
        assertDoesNotThrow(() -> recalculator.recalculate(formFields, new LinkedHashMap<>()));
        Map<String, Object> withBadRow = new LinkedHashMap<>();
        withBadRow.put("items", java.util.Arrays.asList(null, "不是对象"));
        assertDoesNotThrow(() -> recalculator.recalculate(formFields, withBadRow));
    }

    @Test
    @DisplayName("主表公式与子表公式互不干扰")
    @SuppressWarnings("unchecked")
    public void testMainAndSubFormulasCoexist() {
        String mainAst = "{\"type\":\"binary\",\"op\":\"+\","
                + "\"left\":{\"type\":\"field\",\"name\":\"a\"},"
                + "\"right\":{\"type\":\"field\",\"name\":\"b\"}}";
        String formFields = fields(
                formulaRule("total", mainAst, 2),
                subTableRule("items", formulaRule("amount", MULTIPLY_QTY_PRICE, 2)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("a", 1);
        data.put("b", 2);
        data.put("items", List.of(new LinkedHashMap<>(Map.of("qty", 2, "price", 3))));

        Map<String, Object> result = recalculator.recalculate(formFields, data);

        assertEquals(0, new BigDecimal("3").compareTo(num(result.get("total"))));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("items");
        assertEquals(0, new BigDecimal("6").compareTo(num(rows.get(0).get("amount"))));
    }
}
