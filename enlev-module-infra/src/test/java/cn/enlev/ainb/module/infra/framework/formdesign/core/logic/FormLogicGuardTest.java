package cn.enlev.ainb.module.infra.framework.formdesign.core.logic;

import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Spy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 联动规则服务端复校验的对抗性测试
 *
 * 对应设计方案 §9.3 的第 1、2 条：「绕过前端直接构造请求」。
 * 这些用例模拟的不是正常操作，而是拿 curl 直接打接口 ——
 * 前端算出来的必填与只读只是体验，能被绕过；这里验证服务端是否真的兜住了。
 *
 * @author 走进新时代
 */
public class FormLogicGuardTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormLogicGuard guard;

    /**
     * 用真实求值器：要守的正是「规则怎么写，服务端就怎么拦」这条链路，
     * mock 掉求值器等于把被测逻辑一起 mock 掉了
     */
    @Spy
    private LogicRuleEvaluator evaluator = new LogicRuleEvaluator();

    /** 造 formFields：字段名 → 是否必填 */
    private String fields(Map<String, Boolean> fieldRequired) {
        List<String> rules = fieldRequired.entrySet().stream()
                .map(e -> String.format(
                        "{\"type\":\"input\",\"field\":\"%s\",\"title\":\"%s字段\",\"$required\":%s}",
                        e.getKey(), e.getKey(), e.getValue()))
                .toList();
        return FormFieldsCodec.write(rules);
    }

    private String rules(String... ruleJsons) {
        return "[" + String.join(",", ruleJsons) + "]";
    }

    private Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    // ==================== 只读字段的提交值必须被丢弃 ====================

    @Test
    @DisplayName("⚠️ 绕过前端给只读字段塞值 → 服务端丢弃该值")
    void testReadonlyFieldValueDiscarded() {
        // 规则：locked == 1 时 price 只读
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT",
                 "when":{"logic":"AND","items":[{"field":"locked","operator":"EQ","value":1}]},
                 "then":[{"type":"READONLY","target":"price"}]}
                """);
        // 攻击者照样提交了 price
        Map<String, Object> submitted = data("locked", 1, "price", 1);

        FormLogicGuard.GuardResult result = guard.guard(logic,
                fields(Map.of("locked", false, "price", false)), submitted);

        assertTrue(result.isPassed());
        assertFalse(result.getData().containsKey("price"),
                "price 被规则设为只读，提交值必须被丢弃，否则锁定价格形同虚设");
        assertTrue(result.getDiscardedFields().contains("price"));
        // 其它字段不受影响
        assertEquals(1, result.getData().get("locked"));
    }

    @Test
    @DisplayName("⚠️ 绕过前端给隐藏字段塞值 → 同样被丢弃（隐藏必须蕴含不可写）")
    void testHiddenFieldValueDiscarded() {
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT","when":null,
                 "then":[{"type":"HIDE","target":"internalNote"}]}
                """);
        Map<String, Object> submitted = data("internalNote", "偷偷塞的值", "normal", "ok");

        FormLogicGuard.GuardResult result = guard.guard(logic,
                fields(Map.of("internalNote", false, "normal", false)), submitted);

        assertFalse(result.getData().containsKey("internalNote"),
                "前端隐藏不等于服务端放行 —— 隐藏字段的提交值必须被丢弃");
        assertEquals("ok", result.getData().get("normal"));
    }

    @Test
    @DisplayName("条件不满足时字段可写，提交值保留")
    void testWritableWhenConditionNotMet() {
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT",
                 "when":{"logic":"AND","items":[{"field":"locked","operator":"EQ","value":1}]},
                 "then":[{"type":"READONLY","target":"price"}],
                 "otherwise":[{"type":"EDITABLE","target":"price"}]}
                """);
        Map<String, Object> submitted = data("locked", 0, "price", 99);

        FormLogicGuard.GuardResult result = guard.guard(logic,
                fields(Map.of("locked", false, "price", false)), submitted);

        assertEquals(99, result.getData().get("price"), "未锁定时 price 应当可写");
        assertTrue(result.getDiscardedFields().isEmpty());
    }

    @Test
    @DisplayName("规则自己给只读字段赋的值要保留 —— 那是服务端算的，不是前端提交的")
    void testRuleWrittenValueOnReadonlyFieldKept() {
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT","when":null,
                 "then":[{"type":"READONLY","target":"total"},
                         {"type":"SET_VALUE","target":"total","payload":{"value":888}}]}
                """);
        // 前端提交了一个不同的值，应当被规则的值覆盖而不是被整体丢弃
        Map<String, Object> submitted = data("total", 1);

        FormLogicGuard.GuardResult result = guard.guard(logic,
                fields(Map.of("total", false)), submitted);

        assertEquals(888, result.getData().get("total"),
                "字段只读但规则给它赋了值，应当保留规则算出的值");
    }

    // ==================== 必填校验必须在服务端复现 ====================

    @Test
    @DisplayName("⚠️ 绕过前端跳过规则设定的必填字段 → 服务端拒绝")
    void testRuleRequiredFieldMissingRejected() {
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT",
                 "when":{"logic":"AND","items":[{"field":"amount","operator":"GT","value":10000}]},
                 "then":[{"type":"REQUIRE","target":"reason"}]}
                """);
        Map<String, Object> submitted = data("amount", 50000);

        FormLogicGuard.GuardResult result = guard.guard(logic,
                fields(Map.of("amount", false, "reason", false)), submitted);

        assertFalse(result.isPassed(), "金额超限时说明字段必填，缺失必须被拒绝");
        assertTrue(result.getMissingRequiredFields().contains("reason字段"),
                "报错要用字段标题而不是字段 ID，用户看不懂 ID");
    }

    @Test
    @DisplayName("规则必填但值已填 → 放行")
    void testRuleRequiredFieldFilledPasses() {
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT",
                 "when":{"logic":"AND","items":[{"field":"amount","operator":"GT","value":10000}]},
                 "then":[{"type":"REQUIRE","target":"reason"}]}
                """);
        Map<String, Object> submitted = data("amount", 50000, "reason", "大额采购");

        FormLogicGuard.GuardResult result = guard.guard(logic,
                fields(Map.of("amount", false, "reason", false)), submitted);

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("⚠️ 没有联动规则时，控件自身的 $required 也要在服务端校验")
    void testBaseRequiredCheckedWithoutRules() {
        Map<String, Object> submitted = data("optional", "有值");

        FormLogicGuard.GuardResult result = guard.guard(null,
                fields(Map.of("mustFill", true, "optional", false)), submitted);

        assertFalse(result.isPassed(),
                "控件的必填标记同样是前端可绕过的，服务端必须复校验");
        assertTrue(result.getMissingRequiredFields().contains("mustFill字段"));
    }

    @Test
    @DisplayName("规则可以把基础必填改成非必填")
    void testRuleCanRelaxBaseRequired() {
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT","when":null,
                 "then":[{"type":"OPTIONAL","target":"mustFill"}]}
                """);
        FormLogicGuard.GuardResult result = guard.guard(logic,
                fields(Map.of("mustFill", true)), data());

        assertTrue(result.isPassed(), "规则显式放宽后不应再要求必填");
    }

    // ==================== 配置矛盾要报出来 ====================

    @Test
    @DisplayName("⚠️ 既必填又只读是逻辑死锁，要报配置矛盾而不是让提交永远失败")
    void testRequiredAndReadonlyConflict() {
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT","when":null,
                 "then":[{"type":"REQUIRE","target":"x"},{"type":"READONLY","target":"x"}]}
                """);
        FormLogicGuard.GuardResult result = guard.guard(logic, fields(Map.of("x", false)), data());

        assertFalse(result.isPassed());
        assertTrue(result.getConflictFields().contains("x"));
        assertTrue(result.getMissingRequiredFields().isEmpty(),
                "该报的是配置矛盾，不是「用户没填」—— 用户根本没有途径填它");
    }

    // ==================== 容错 ====================

    @Test
    @DisplayName("规则 JSON 坏掉时跳过规则但仍校验基础必填，且不抛异常")
    void testBrokenRulesJsonFallsBack() {
        FormLogicGuard.GuardResult result = assertDoesNotThrow(() ->
                guard.guard("这不是 JSON", fields(Map.of("mustFill", true)), data()));
        assertFalse(result.isPassed(), "规则解析失败也不能放弃基础必填校验");
    }

    @Test
    @DisplayName("formFields 坏掉时不抛异常")
    void testBrokenFieldsJsonTolerated() {
        assertDoesNotThrow(() -> guard.guard(null, "不是 JSON", data("a", 1)));
        assertDoesNotThrow(() -> guard.guard(null, null, data("a", 1)));
    }

    @Test
    @DisplayName("提交数据为 null 时不抛异常")
    void testNullSubmittedData() {
        FormLogicGuard.GuardResult result =
                assertDoesNotThrow(() -> guard.guard(null, fields(Map.of("a", false)), null));
        assertTrue(result.isPassed());
        assertNotNull(result.getData());
    }

    @Test
    @DisplayName("入参数据不被修改 —— 调用方还要用原始提交值做比对")
    void testSubmittedDataNotMutated() {
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT","when":null,
                 "then":[{"type":"READONLY","target":"x"}]}
                """);
        Map<String, Object> submitted = data("x", 1);
        String before = JsonUtils.toJsonString(submitted);

        guard.guard(logic, fields(Map.of("x", false)), submitted);

        assertEquals(before, JsonUtils.toJsonString(submitted));
    }

    // ==================== 行作用域规则（子表明细） ====================

    @Test
    @DisplayName("⚠️ 明细行里「数量大于 100 必须填备注」—— 逐行求值，报错带行号")
    void testRowScopedRequired() {
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT","rowScope":"items",
                 "when":{"logic":"AND","items":[{"field":"qty","operator":"GT","value":100}]},
                 "then":[{"type":"REQUIRE","target":"remark"}]}
                """);
        Map<String, Object> submitted = data("items", List.of(
                Map.of("qty", 50),                      // 不触发
                new LinkedHashMap<>(Map.of("qty", 200)) // 触发且没填备注
        ));

        FormLogicGuard.GuardResult result =
                guard.guard(logic, fields(Map.of("items", false)), submitted);

        assertFalse(result.isPassed(),
                "主表作用域的规则拿不到某一行的数量，这类约束只能靠行作用域表达");
        assertTrue(result.getMissingRequiredFields().stream()
                        .anyMatch(m -> m.contains("第 2 行")),
                "报错必须带行号，否则 30 行明细里用户不知道是哪一行");
    }

    @Test
    @DisplayName("行作用域规则：条件不满足的行不报错")
    void testRowScopedNotTriggered() {
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT","rowScope":"items",
                 "when":{"logic":"AND","items":[{"field":"qty","operator":"GT","value":100}]},
                 "then":[{"type":"REQUIRE","target":"remark"}]}
                """);
        FormLogicGuard.GuardResult result = guard.guard(logic,
                fields(Map.of("items", false)),
                data("items", List.of(Map.of("qty", 10), Map.of("qty", 20))));

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("行作用域规则：条件满足且已填时放行")
    void testRowScopedFilled() {
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT","rowScope":"items",
                 "when":{"logic":"AND","items":[{"field":"qty","operator":"GT","value":100}]},
                 "then":[{"type":"REQUIRE","target":"remark"}]}
                """);
        FormLogicGuard.GuardResult result = guard.guard(logic,
                fields(Map.of("items", false)),
                data("items", List.of(Map.of("qty", 200, "remark", "大批量"))));

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("⚠️ 行作用域规则不影响主表字段的判定")
    void testRowScopedDoesNotLeakToMainScope() {
        // 明细里有个叫 remark 的列，主表也有个 remark 字段 —— 两者不能互相干扰
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT","rowScope":"items",
                 "when":null,
                 "then":[{"type":"REQUIRE","target":"remark"}]}
                """);
        FormLogicGuard.GuardResult result = guard.guard(logic,
                fields(Map.of("remark", false, "items", false)),
                data("items", List.of(Map.of("remark", "明细填了"))));

        assertTrue(result.isPassed(),
                "主表的 remark 没填，但规则是行作用域的，不该波及主表");
    }

    @Test
    @DisplayName("子表字段没提交或不是数组时，行作用域规则跳过")
    void testRowScopedMissingSubTable() {
        String logic = rules("""
                {"id":"r1","trigger":"SUBMIT","rowScope":"items","when":null,
                 "then":[{"type":"REQUIRE","target":"x"}]}
                """);
        assertTrue(guard.guard(logic, fields(Map.of("a", false)), data("a", 1)).isPassed());
        assertTrue(guard.guard(logic, fields(Map.of("a", false)), data("items", "不是数组")).isPassed());
    }

    @Test
    @DisplayName("CHANGE 时机的规则不参与提交复校验")
    void testChangeTriggerRulesIgnoredOnSubmit() {
        String logic = rules("""
                {"id":"r1","trigger":"CHANGE","when":null,
                 "then":[{"type":"REQUIRE","target":"x"}]}
                """);
        FormLogicGuard.GuardResult result = guard.guard(logic, fields(Map.of("x", false)), data());

        assertTrue(result.isPassed(),
                "CHANGE 是录入过程中的交互规则；提交时的约束应当配成 SUBMIT");
    }

}
