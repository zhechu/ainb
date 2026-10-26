package cn.enlev.ainb.module.infra.framework.formdesign.core.mask;

import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据脱敏的单元测试
 *
 * 本模块的字段级权限延后了，**脱敏是敏感字段目前唯一的防线**。
 * 前端脱敏是安全假象（接口照样返回明文，抓包即可见），所以这些用例守的是
 * 「明文真的没有离开服务端」。
 *
 * @author 走进新时代
 */
class FormDataMaskerTest {

    private final FormDataMasker masker = new FormDataMasker();

    private String fieldRule(String field, String rule) {
        return String.format("{\"type\":\"input\",\"field\":\"%s\",\"props\":{\"maskRule\":\"%s\"}}",
                field, rule);
    }

    private String fields(String... rules) {
        return FormFieldsCodec.write(List.of(rules));
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    // ==================== 各策略 ====================

    @Test
    @DisplayName("手机号保留前 3 后 4")
    void testMobile() {
        Map<String, Object> result = masker.mask(fields(fieldRule("phone", "MOBILE")),
                row("phone", "13812348000"));
        assertEquals("138****8000", result.get("phone"));
    }

    @Test
    @DisplayName("身份证保留前 3 后 4")
    void testIdCard() {
        Map<String, Object> result = masker.mask(fields(fieldRule("idCard", "ID_CARD")),
                row("idCard", "110101199001011234"));
        assertEquals("110***********1234", result.get("idCard"));
    }

    @Test
    @DisplayName("⚠️ 姓名只保留姓 —— 保留后一位的话「张三」等于没脱敏")
    void testName() {
        assertEquals("张*", masker.mask(fields(fieldRule("n", "NAME")), row("n", "张三")).get("n"));
        assertEquals("欧**", masker.mask(fields(fieldRule("n", "NAME")), row("n", "欧阳修")).get("n"));
    }

    @Test
    @DisplayName("邮箱只遮 @ 前面，域名保留 —— 遮域名会让用户认不出是哪个邮箱")
    void testEmail() {
        assertEquals("ab****@example.com",
                masker.mask(fields(fieldRule("e", "EMAIL")), row("e", "abcdef@example.com")).get("e"));
    }

    @Test
    @DisplayName("不是合法邮箱时退化成中间遮掩，而不是原样返回")
    void testInvalidEmailStillMasked() {
        Object v = masker.mask(fields(fieldRule("e", "EMAIL")), row("e", "notanemail")).get("e");
        assertNotEquals("notanemail", v, "脏数据不该成为脱敏的绕过口");
    }

    @Test
    @DisplayName("自定义策略按配置的前后保留位数")
    void testCustom() {
        String rule = "{\"type\":\"input\",\"field\":\"c\",\"props\":{\"maskRule\":\"CUSTOM\","
                + "\"maskKeepPrefix\":1,\"maskKeepSuffix\":2,\"maskFiller\":\"#\"}}";
        // abcde 共 5 位，保留前 1 后 2，中间遮 5-1-2=2 位
        assertEquals("a##de", masker.mask(fields(rule), row("c", "abcde")).get("c"));
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("⚠️ 值比要保留的位数还短时全部遮掉，而不是原样输出")
    void testShortValueFullyMasked() {
        // 一个只有 3 位的「手机号」若按「保留前 3 后 4」处理会原样输出，等于没脱敏
        assertEquals("***", masker.mask(fields(fieldRule("p", "MOBILE")), row("p", "138")).get("p"));
    }

    @Test
    @DisplayName("空值与空串不报错")
    void testEmptyValue() {
        assertNull(masker.mask(fields(fieldRule("p", "MOBILE")), row("p", null)).get("p"));
        assertEquals("", masker.mask(fields(fieldRule("p", "MOBILE")), row("p", "")).get("p"));
    }

    @Test
    @DisplayName("⚠️ 认不出的策略按不脱敏处理，而不是按最严格处理")
    void testUnknownRuleFallsBackToNone() {
        // 按最严格处理会让一个拼错的配置把正常字段变成一片星号，用户会以为数据坏了
        assertEquals("abc", masker.mask(fields(fieldRule("x", "拼错了")), row("x", "abc")).get("x"));
    }

    @Test
    @DisplayName("没配脱敏的字段原样返回")
    void testNoMaskConfigured() {
        assertEquals("13812348000",
                masker.mask(fields("{\"type\":\"input\",\"field\":\"p\"}"),
                        row("p", "13812348000")).get("p"));
    }

    // ==================== 覆盖面 ====================

    @Test
    @DisplayName("⚠️ 子表明细里的敏感字段也要脱敏 —— 否则挪进明细就能绕过")
    void testSubTableDetailMasked() {
        String rule = "{\"type\":\"group\",\"field\":\"items\",\"props\":{\"rule\":["
                + fieldRule("phone", "MOBILE") + "]}}";
        Map<String, Object> data = row("items",
                List.of(row("phone", "13812348000"), row("phone", "13900001111")));

        Map<String, Object> result = masker.mask(fields(rule), data);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("items");
        assertEquals("138****8000", rows.get(0).get("phone"));
        assertEquals("139****1111", rows.get(1).get("phone"));
    }

    @Test
    @DisplayName("⚠️ 藏在布局容器里的敏感字段也要脱敏")
    void testNestedInLayoutContainer() {
        String nested = "{\"type\":\"fcRow\",\"children\":[{\"type\":\"col\",\"children\":["
                + fieldRule("phone", "MOBILE") + "]}]}";
        assertEquals("138****8000",
                masker.mask(fields(nested), row("phone", "13812348000")).get("phone"));
    }

    @Test
    @DisplayName("列表批量脱敏")
    void testMaskAll() {
        List<Map<String, Object>> rows = List.of(
                row("phone", "13812348000"), row("phone", "13900001111"));
        List<Map<String, Object>> result = masker.maskAll(fields(fieldRule("phone", "MOBILE")), rows);
        assertEquals("138****8000", result.get(0).get("phone"));
        assertEquals("139****1111", result.get(1).get("phone"));
    }

    @Test
    @DisplayName("入参不被修改 —— 调用方可能还要用明文做别的事")
    void testInputNotMutated() {
        Map<String, Object> original = row("phone", "13812348000");
        masker.mask(fields(fieldRule("phone", "MOBILE")), original);
        assertEquals("13812348000", original.get("phone"));
    }

    @Test
    @DisplayName("formFields 坏掉或数据为 null 时不抛异常")
    void testBrokenInput() {
        assertNull(masker.mask(fields(fieldRule("p", "MOBILE")), null));
        assertDoesNotThrow(() -> masker.mask("不是 JSON", row("p", "1")));
        assertDoesNotThrow(() -> masker.maskAll(null, List.of(row("p", "1"))));
    }

    // ==================== 回写保护 ====================
    //
    // 详情接口下发的是脱敏值，前端提交时回传的是整条记录。不拦的话用户只改了金额，
    // 手机号会被永久写成 138****5678 —— 是数据损坏，不是显示问题。

    @Test
    @DisplayName("⚠️ 提交的是脱敏回显 —— 该字段必须被剔除，不能写库")
    void testStripMaskedEcho() {
        String formFields = fields(fieldRule("phone", "MOBILE"));
        Map<String, Object> current = row("phone", "13812345678", "amount", 100);
        // 前端把 get 返回的整条记录回传，只改了金额
        Map<String, Object> submitted = row("phone", "138****5678", "amount", 999);

        Map<String, Object> result = masker.stripMaskedEcho(formFields, submitted, current);

        assertFalse(result.containsKey("phone"), "脱敏回显必须被剔除，否则真实手机号被覆盖");
        assertEquals(999, result.get("amount"), "其它字段照常更新");
    }

    @Test
    @DisplayName("用户真的改了手机号 —— 必须照常写入，不能被保护挡掉")
    void testGenuineEditPassesThrough() {
        String formFields = fields(fieldRule("phone", "MOBILE"));
        Map<String, Object> current = row("phone", "13812345678");
        Map<String, Object> submitted = row("phone", "13900009999");

        Map<String, Object> result = masker.stripMaskedEcho(formFields, submitted, current);

        assertEquals("13900009999", result.get("phone"));
    }

    @Test
    @DisplayName("当前值为空时一律放行 —— 空值脱敏还是空，此时提交的任何值都是新值")
    void testNullCurrentAlwaysWrites() {
        String formFields = fields(fieldRule("phone", "MOBILE"));
        Map<String, Object> result =
                masker.stripMaskedEcho(formFields, row("phone", "13812345678"), row("phone", null));
        assertEquals("13812345678", result.get("phone"));
    }

    @Test
    @DisplayName("没配脱敏的字段不受影响")
    void testUnmaskedFieldUntouched() {
        String formFields = fields(fieldRule("phone", "MOBILE"));
        Map<String, Object> current = row("phone", "13812345678", "remark", "旧备注");
        Map<String, Object> submitted = row("phone", "138****5678", "remark", "旧备注");

        Map<String, Object> result = masker.stripMaskedEcho(formFields, submitted, current);

        assertFalse(result.containsKey("phone"));
        // remark 没配脱敏，哪怕值没变也照常进 SET —— 保护只针对脱敏字段
        assertEquals("旧备注", result.get("remark"));
    }

    @Test
    @DisplayName("入参不被修改")
    void testStripDoesNotMutateInput() {
        String formFields = fields(fieldRule("phone", "MOBILE"));
        Map<String, Object> submitted = row("phone", "138****5678");
        masker.stripMaskedEcho(formFields, submitted, row("phone", "13812345678"));
        assertTrue(submitted.containsKey("phone"), "原 Map 不该被改动");
    }

    @Test
    @DisplayName("没有脱敏字段时原样返回，不做多余拷贝")
    void testNoMaskedFieldsReturnsSame() {
        Map<String, Object> submitted = row("a", "1");
        assertSame(submitted, masker.stripMaskedEcho(fields(), submitted, row("a", "2")));
        assertFalse(masker.hasMaskedMainFields(fields()));
        assertTrue(masker.hasMaskedMainFields(fields(fieldRule("phone", "MOBILE"))));
    }

    @Test
    @DisplayName("⚠️ 子表明细的脱敏回显同样要剔除 —— 只保主表等于留了半扇门")
    void testStripMaskedEchoForSubRow() {
        String subRule = "{\"type\":\"SubTable\",\"field\":\"items\",\"props\":{\"rule\":["
                + fieldRule("phone", "MOBILE") + "]}}";
        String formFields = fields(subRule);

        assertTrue(masker.hasMaskedSubFields(formFields, "items"));
        assertFalse(masker.hasMaskedSubFields(formFields, "other"));

        Map<String, Object> result = masker.stripMaskedEchoForSubRow(formFields, "items",
                row("id", 7, "phone", "138****5678", "qty", 5),
                row("id", 7, "phone", "13812345678", "qty", 3));
        assertFalse(result.containsKey("phone"), "明细里的脱敏回显必须被剔除");
        assertEquals(5, result.get("qty"));
    }

    @Test
    @DisplayName("新增的明细行没有对应旧行 —— 原样放行")
    void testSubRowWithoutCurrentPassesThrough() {
        String subRule = "{\"type\":\"SubTable\",\"field\":\"items\",\"props\":{\"rule\":["
                + fieldRule("phone", "MOBILE") + "]}}";
        Map<String, Object> submitted = row("phone", "13812345678");
        assertSame(submitted,
                masker.stripMaskedEchoForSubRow(fields(subRule), "items", submitted, null));
    }

}
