package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ValueConverter} 的单元测试
 *
 * 类型转换错误不会报错，只会静默存进错误的值，所以逐类型断言。
 *
 * @author 走进新时代
 */
class ValueConverterTest {

    @Test
    @DisplayName("字符串类型：原样保留，空串不转 null")
    void testString() {
        assertEquals("abc", ValueConverter.convert("abc", "STRING"));
        assertEquals("", ValueConverter.convert("", "STRING"), "字符串字段的空串应保留");
        assertEquals("123", ValueConverter.convert(123, "STRING"), "数字转字符串");
    }

    @Test
    @DisplayName("数字类型：字符串转 Integer / Long")
    void testNumber() {
        assertEquals(123, ValueConverter.convert("123", "INT"));
        assertEquals(123L, ValueConverter.convert("123", "LONG"));
        // 前端 JSON 数字过来是 Integer/Double，也要能转
        assertEquals(123L, ValueConverter.convert(123, "LONG"));
        assertEquals(123, ValueConverter.convert(123L, "INT"));
        // 带空格的输入
        assertEquals(456L, ValueConverter.convert(" 456 ", "LONG"));
    }

    @Test
    @DisplayName("数字类型：空串转 null，避免存成 0")
    void testNumber_emptyToNull() {
        assertNull(ValueConverter.convert("", "INT"), "空串不该变成 0");
        assertNull(ValueConverter.convert("  ", "LONG"));
        assertNull(ValueConverter.convert(null, "INT"));
    }

    @Test
    @DisplayName("数字类型：非法输入抛异常而不是静默存 0")
    void testNumber_invalid() {
        assertThrows(ServiceException.class, () -> ValueConverter.convert("abc", "INT"));
        assertThrows(ServiceException.class, () -> ValueConverter.convert("12a", "LONG"));
    }

    @Test
    @DisplayName("DECIMAL：必须走 BigDecimal，不能用 Double")
    void testDecimal() {
        Object result = ValueConverter.convert("12.34", "DECIMAL");
        assertInstanceOf(BigDecimal.class, result, "金额必须是 BigDecimal");
        assertEquals(new BigDecimal("12.34"), result);
        // 精度不能丢
        assertEquals(new BigDecimal("0.1"), ValueConverter.convert("0.1", "DECIMAL"));
    }

    @Test
    @DisplayName("BOOLEAN：兼容 true/1/是/y 多种表示")
    void testBoolean() {
        assertEquals(true, ValueConverter.convert(true, "BOOLEAN"));
        assertEquals(true, ValueConverter.convert("true", "BOOLEAN"));
        assertEquals(true, ValueConverter.convert("TRUE", "BOOLEAN"));
        assertEquals(true, ValueConverter.convert("1", "BOOLEAN"));
        assertEquals(true, ValueConverter.convert(1, "BOOLEAN"));
        assertEquals(true, ValueConverter.convert("是", "BOOLEAN"));
        assertEquals(true, ValueConverter.convert("y", "BOOLEAN"));

        assertEquals(false, ValueConverter.convert("false", "BOOLEAN"));
        assertEquals(false, ValueConverter.convert("0", "BOOLEAN"));
        assertEquals(false, ValueConverter.convert(0, "BOOLEAN"));
        assertEquals(false, ValueConverter.convert("否", "BOOLEAN"));
    }

    @Test
    @DisplayName("DATETIME：兼容多种格式")
    void testDateTime() {
        assertEquals(LocalDateTime.of(2026, 8, 30, 10, 0, 0),
                ValueConverter.convert("2026-08-30 10:00:00", "DATETIME"));
        // 前端 el-date-picker 常传 ISO 格式
        assertEquals(LocalDateTime.of(2026, 8, 30, 10, 0, 0),
                ValueConverter.convert("2026-08-30T10:00:00", "DATETIME"));
        // 不带秒
        assertEquals(LocalDateTime.of(2026, 8, 30, 10, 0),
                ValueConverter.convert("2026-08-30 10:00", "DATETIME"));
    }

    @Test
    @DisplayName("DATE：只取日期部分，容忍传入完整时间")
    void testDate() {
        assertEquals(LocalDate.of(2026, 8, 30), ValueConverter.convert("2026-08-30", "DATE"));
        assertEquals(LocalDate.of(2026, 8, 30),
                ValueConverter.convert("2026-08-30 10:00:00", "DATE"),
                "date 字段收到完整时间应截断而不是报错");
    }

    @Test
    @DisplayName("日期非法输入抛异常")
    void testDate_invalid() {
        assertThrows(ServiceException.class, () -> ValueConverter.convert("not-a-date", "DATETIME"));
        assertThrows(ServiceException.class, () -> ValueConverter.convert("2026-13-45", "DATE"));
    }

    // ==================== JSON ====================
    //
    // JSON 列是 2026-10-04 加进枚举的（用户裁定「改建表枚举，加上 JSON」，缺陷 D4-15 的根治）。
    // JDBC 只认字符串，所以这一类的转换职责是「把结构化值序列化成 JSON 文本」，
    // 而**不是**判它合不合法 —— 合法性只认 MySQL 一个权威，理由见 ValueConverter#toJsonText。

    @Test
    @DisplayName("JSON：字符串原样透传（前端传的本就是 JSON 文本）")
    void testJson_string() {
        assertEquals("{\"a\":1}", ValueConverter.convert("{\"a\":1}", "JSON"));
    }

    @Test
    @DisplayName("🔴 JSON：Map / List 必须序列化成文本，否则 JDBC 绑不上去")
    void testJson_structuredValueSerialized() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", "文本");
        assertEquals("{\"a\":1,\"b\":\"文本\"}", ValueConverter.convert(map, "JSON"),
                "Map 没被序列化 —— JDBC 会拿一个 Map 去绑 json 列");

        assertEquals("[1,2,3]", ValueConverter.convert(List.of(1, 2, 3), "JSON"));
    }

    /**
     * 🔴 这条守的是**一个刻意不做的事**：不在 Java 侧校验 JSON 合法性。
     *
     * <p>{@code JsonUtils.isJson} 走 Hutool 的 isTypeJSON，只认 {}/[] 开头结尾，
     * 会把合法的 JSON 标量判成非法 —— 而 MySQL 的 json 列是收标量的
     * （{@code SELECT JSON_VALID('"abc"')} = 1）。真拿它当校验，表现就是
     * 「平台说不合法、其实库里收」，一个凭白多出来的拒绝。
     */
    @Test
    @DisplayName("🔴 JSON 标量照收不误：合法性只由 MySQL 判，Java 侧不加第二套判据")
    void testJson_scalarNotRejected() {
        assertEquals("\"abc\"", ValueConverter.convert("\"abc\"", "JSON"));
        assertEquals("123", ValueConverter.convert("123", "JSON"));
        assertEquals("true", ValueConverter.convert("true", "JSON"));
    }

    @Test
    @DisplayName("🔴 JSON 的空串转 null —— \"\" 不是合法 JSON，MySQL 会直接拒")
    void testJson_blankBecomesNull() {
        assertNull(ValueConverter.convert("", "JSON"),
                "空串被当字符串存进 json 列了 —— MySQL 会报 Invalid JSON text");
        assertNull(ValueConverter.convert("   ", "JSON"));
        assertNull(ValueConverter.convert(null, "JSON"));
    }

    @Test
    @DisplayName("未知类型：原样传给 JDBC，不阻断导入已有表的场景")
    void testUnknownType() {
        assertEquals("raw", ValueConverter.convert("raw", "BLOB"));
    }

    @Test
    @DisplayName("整行转换：按表结构决定目标类型，不信前端声明")
    void testConvertRow() {
        List<TableColumnModel> columns = List.of(
                new TableColumnModel().setColumnName("id").setColumnType("LONG"),
                new TableColumnModel().setColumnName("name").setColumnType("STRING"),
                new TableColumnModel().setColumnName("amount").setColumnType("DECIMAL"),
                new TableColumnModel().setColumnName("paid").setColumnType("BOOLEAN"),
                new TableColumnModel().setColumnName("created_at").setColumnType("DATETIME")
        );
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", "100");
        data.put("name", "订单A");
        data.put("amount", "99.50");
        data.put("paid", "1");
        data.put("created_at", "2026-08-30 12:00:00");

        Map<String, Object> result = ValueConverter.convertRow(data, columns);
        assertEquals(100L, result.get("id"));
        assertEquals("订单A", result.get("name"));
        assertEquals(new BigDecimal("99.50"), result.get("amount"));
        assertEquals(true, result.get("paid"));
        assertEquals(LocalDateTime.of(2026, 8, 30, 12, 0), result.get("created_at"));
    }

    @Test
    @DisplayName("整行转换：字段名大小写不敏感")
    void testConvertRow_caseInsensitive() {
        List<TableColumnModel> columns = List.of(
                new TableColumnModel().setColumnName("order_no").setColumnType("LONG"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ORDER_NO", "123");

        Map<String, Object> result = ValueConverter.convertRow(data, columns);
        assertEquals(123L, result.get("ORDER_NO"), "大小写不同也应命中字段定义");
    }

    @Test
    @DisplayName("整行转换：表里没有的字段原样保留，交给白名单校验拦截")
    void testConvertRow_unknownColumn() {
        List<TableColumnModel> columns = List.of(
                new TableColumnModel().setColumnName("id").setColumnType("LONG"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evil_column", "x");

        Map<String, Object> result = ValueConverter.convertRow(data, columns);
        assertEquals("x", result.get("evil_column"));
    }

}
