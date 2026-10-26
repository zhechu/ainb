package cn.enlev.ainb.module.infra.framework.formdesign.core.subtable;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 子表服务端校验的对抗性测试
 *
 * 前端配的「最少几行、最多几行」和明细必填都能被绕过 —— 直接构造请求即可。
 * 这些用例验证服务端是不是真的兜住了，尤其是行数硬顶：
 * 没有它，一个构造出来的请求就能让数据库卡在一个几千行的写入事务上。
 *
 * @author 走进新时代
 */
public class SubTableGuardTest extends BaseMockitoUnitTest {

    @InjectMocks
    private SubTableGuard guard;

    /** 用真实契约注册表：子表控件类型要从契约取 */
    @Spy
    private WidgetContractRegistry widgetContractRegistry = newRegistry();

    private static WidgetContractRegistry newRegistry() {
        WidgetContractRegistry registry = new WidgetContractRegistry();
        registry.init();
        return registry;
    }

    /**
     * 造一个子表控件规则
     *
     * @param columns 形如 "field:title:required"
     */
    private String subTableRule(String field, String title, Integer minRows, Integer maxRows,
                                String... columns) {
        StringBuilder inner = new StringBuilder("[");
        for (int i = 0; i < columns.length; i++) {
            String[] parts = columns[i].split(":");
            if (i > 0) {
                inner.append(",");
            }
            inner.append(String.format(
                    "{\"type\":\"input\",\"field\":\"%s\",\"title\":\"%s\",\"$required\":%s}",
                    parts[0], parts[1], parts.length > 2 ? parts[2] : "false"));
        }
        inner.append("]");

        StringBuilder props = new StringBuilder("{\"rule\":").append(inner);
        if (minRows != null) {
            props.append(",\"min\":").append(minRows);
        }
        if (maxRows != null) {
            props.append(",\"max\":").append(maxRows);
        }
        props.append("}");

        return String.format("{\"type\":\"group\",\"field\":\"%s\",\"title\":\"%s\",\"props\":%s}",
                field, title, props);
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

    private Map<String, Object> dataWithRows(String field, List<Map<String, Object>> rows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(field, rows);
        return data;
    }

    // ==================== 行数 ====================

    @Test
    @DisplayName("⚠️ 绕过前端提交 0 行明细 → 服务端按最少行数拒绝")
    void testMinRowsEnforced() {
        SubTableGuard.ValidationResult result = guard.validate(
                fields(subTableRule("items", "订单明细", 1, null, "price:单价")),
                dataWithRows("items", List.of()));

        assertFalse(result.isPassed(), "一张没有任何明细的订单不该能提交");
        assertTrue(result.toMessage().contains("订单明细"), "报错要带控件标题，不能只报字段 ID");
        assertTrue(result.toMessage().contains("至少"));
    }

    @Test
    @DisplayName("⚠️ 绕过前端提交超量明细 → 服务端按最多行数拒绝")
    void testMaxRowsEnforced() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(row("price", i));
        }
        SubTableGuard.ValidationResult result = guard.validate(
                fields(subTableRule("items", "订单明细", null, 3, "price:单价")),
                dataWithRows("items", rows));

        assertFalse(result.isPassed());
        assertTrue(result.toMessage().contains("最多 3 行"));
    }

    @Test
    @DisplayName("⚠️ 即便没配上限，也有与配置无关的行数硬顶")
    void testHardMaxRowsEnforcedWithoutConfig() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < SubTableGuard.HARD_MAX_ROWS + 1; i++) {
            rows.add(row("price", i));
        }
        // 注意：没有配 maxRows
        SubTableGuard.ValidationResult result = guard.validate(
                fields(subTableRule("items", "订单明细", null, null, "price:单价")),
                dataWithRows("items", rows));

        assertFalse(result.isPassed(),
                "没有硬顶的话，一个构造出来的请求就能让数据库卡在几千行的写入事务上");
        assertTrue(result.toMessage().contains("系统上限"));
    }

    @Test
    @DisplayName("行数在区间内时放行")
    void testRowCountWithinRange() {
        SubTableGuard.ValidationResult result = guard.validate(
                fields(subTableRule("items", "订单明细", 1, 5, "price:单价")),
                dataWithRows("items", List.of(row("price", 1), row("price", 2))));

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("没配行数限制时任意行数都放行（仍受硬顶约束）")
    void testNoRowLimitConfigured() {
        SubTableGuard.ValidationResult result = guard.validate(
                fields(subTableRule("items", "明细", null, null, "price:单价")),
                dataWithRows("items", List.of()));

        assertTrue(result.isPassed());
    }

    // ==================== 行内必填 ====================

    @Test
    @DisplayName("⚠️ 明细行里必填列留空 → 服务端拒绝，且报出行号")
    void testRequiredCellEnforcedWithRowNumber() {
        SubTableGuard.ValidationResult result = guard.validate(
                fields(subTableRule("items", "订单明细", null, null, "price:单价:true")),
                dataWithRows("items", List.of(
                        row("price", 10),
                        row("price", null),
                        row("price", 20))));

        assertFalse(result.isPassed(), "必填的单价留空，后续算总价时会按 0 处理，金额直接错");
        assertTrue(result.toMessage().contains("第 2"), "一张 30 行的明细里只说「单价必填」，用户不知道是哪一行");
        assertTrue(result.toMessage().contains("单价"));
    }

    @Test
    @DisplayName("空串也算未填")
    void testEmptyStringCountsAsMissing() {
        SubTableGuard.ValidationResult result = guard.validate(
                fields(subTableRule("items", "明细", null, null, "name:名称:true")),
                dataWithRows("items", List.of(row("name", ""))));

        assertFalse(result.isPassed());
    }

    @Test
    @DisplayName("⚠️ 同一列多行缺失合并成一条，不刷屏")
    void testManyMissingRowsSummarized() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(row("price", null));
        }
        SubTableGuard.ValidationResult result = guard.validate(
                fields(subTableRule("items", "明细", null, null, "price:单价:true")),
                dataWithRows("items", rows));

        assertFalse(result.isPassed());
        assertEquals(1, result.getErrors().size(),
                "30 行都缺失时报 30 条错误，用户看到的是一屏刷屏，反而找不到重点");
        assertTrue(result.toMessage().contains("等 30 行"));
    }

    @Test
    @DisplayName("非必填列留空不报错")
    void testOptionalCellAllowedEmpty() {
        SubTableGuard.ValidationResult result = guard.validate(
                fields(subTableRule("items", "明细", null, null, "remark:备注")),
                dataWithRows("items", List.of(row("remark", null))));

        assertTrue(result.isPassed());
    }

    // ==================== 容错 ====================

    @Test
    @DisplayName("表单里没有子表控件时放行")
    void testNoSubTableWidget() {
        SubTableGuard.ValidationResult result = guard.validate(
                fields("{\"type\":\"input\",\"field\":\"a\"}"), Map.of("a", 1));
        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("子表字段完全没提交时按 0 行处理")
    void testMissingSubTableFieldTreatedAsZeroRows() {
        SubTableGuard.ValidationResult result = guard.validate(
                fields(subTableRule("items", "明细", 1, null, "price:单价")),
                Map.of("other", 1));

        assertFalse(result.isPassed(), "漏传子表字段与提交空数组应当是同一种结果");
    }

    @Test
    @DisplayName("formFields 坏掉或数据为 null 时不抛异常")
    void testBrokenInputTolerated() {
        assertDoesNotThrow(() -> guard.validate(null, Map.of()));
        assertDoesNotThrow(() -> guard.validate("不是 JSON", Map.of()));
        assertDoesNotThrow(() -> guard.validate(
                fields(subTableRule("items", "明细", null, null, "p:单价")), null));
    }

    @Test
    @DisplayName("⚠️ 藏在布局容器里的子表控件也要被校验")
    void testSubTableInsideLayoutContainer() {
        String nested = "{\"type\":\"fcRow\",\"children\":[{\"type\":\"col\",\"children\":["
                + subTableRule("items", "明细", 1, null, "price:单价") + "]}]}";

        SubTableGuard.ValidationResult result =
                guard.validate(fields(nested), dataWithRows("items", List.of()));

        assertFalse(result.isPassed(),
                "子表控件常被放进栅格，不递归就等于这些子表完全没有服务端校验");
    }

    @Test
    @DisplayName("多个子表各自独立校验，错误一次性汇总")
    void testMultipleSubTablesAggregated() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("a", List.of());
        data.put("b", List.of());

        SubTableGuard.ValidationResult result = guard.validate(
                fields(subTableRule("a", "明细A", 1, null, "x:列"),
                        subTableRule("b", "明细B", 2, null, "y:列")),
                data);

        assertEquals(2, result.getErrors().size(),
                "一次性列出全部问题，而不是改完一处再提交又被拦下一处");
    }

}
