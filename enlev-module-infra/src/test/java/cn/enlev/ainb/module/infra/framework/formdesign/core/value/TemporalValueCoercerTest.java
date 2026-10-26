package cn.enlev.ainb.module.infra.framework.formdesign.core.value;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 时间类列写入值归一的单测
 *
 * 这一层修的是「读出来的东西写不回去」：读取路径把 DATE 列下发成纪元毫秒，
 * 原样回传时数据库拒收。列表行内编辑要整条记录回传，一上来就撞上了。
 */
class TemporalValueCoercerTest {

    /** 2026-03-01 00:00:00 在本机时区的纪元毫秒 */
    private static final long DATE_MILLIS =
            LocalDate.of(2026, 3, 1).atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli();

    @Test
    @DisplayName("DATE 列的毫秒数归一成 LocalDate")
    void coerceDate() {
        Object result = TemporalValueCoercer.coerce(DATE_MILLIS, "DATE");
        assertEquals(LocalDate.of(2026, 3, 1), result);
    }

    @Test
    @DisplayName("DATETIME 与 TIMESTAMP 列归一成 LocalDateTime")
    void coerceDateTime() {
        assertInstanceOf(LocalDateTime.class, TemporalValueCoercer.coerce(DATE_MILLIS, "DATETIME"));
        assertInstanceOf(LocalDateTime.class, TemporalValueCoercer.coerce(DATE_MILLIS, "TIMESTAMP"));
    }

    @Test
    @DisplayName("⚠️ TIMESTAMP 含 TIME，判断顺序不能反")
    void timestampIsNotTime() {
        // 顺序反了的话 TIMESTAMP 会被当成 TIME 处理，日期部分整个丢掉，
        // 而且只在带时分秒的列上才显现
        assertInstanceOf(LocalDateTime.class, TemporalValueCoercer.coerce(DATE_MILLIS, "TIMESTAMP"));
        assertInstanceOf(LocalTime.class, TemporalValueCoercer.coerce(DATE_MILLIS, "TIME"));
    }

    @Test
    @DisplayName("非时间列原样返回")
    void nonTemporalUnchanged() {
        assertEquals(123L, TemporalValueCoercer.coerce(123L, "BIGINT"));
        assertEquals(123L, TemporalValueCoercer.coerce(123L, "VARCHAR"));
        assertEquals(123L, TemporalValueCoercer.coerce(123L, "DECIMAL"));
    }

    @Test
    @DisplayName("⚠️ 字符串一律原样放行，不插手数据库自己能解析的写法")
    void stringUnchanged() {
        // 再插一手只会多一处格式分歧
        assertEquals("2026-03-01", TemporalValueCoercer.coerce("2026-03-01", "DATE"));
        assertEquals("2026-03-01 10:00:00",
                TemporalValueCoercer.coerce("2026-03-01 10:00:00", "DATETIME"));
    }

    @Test
    @DisplayName("null 与空类型名不抛异常")
    void nullSafe() {
        assertNull(TemporalValueCoercer.coerce(null, "DATE"));
        assertEquals(DATE_MILLIS, TemporalValueCoercer.coerce(DATE_MILLIS, null));
        assertEquals(DATE_MILLIS, TemporalValueCoercer.coerce(DATE_MILLIS, ""));
    }

    @Test
    @DisplayName("类型名大小写不敏感")
    void caseInsensitive() {
        assertEquals(LocalDate.of(2026, 3, 1), TemporalValueCoercer.coerce(DATE_MILLIS, "date"));
    }

    @Test
    @DisplayName("Integer / Double 等其它数字类型也认")
    void otherNumberTypes() {
        // 反序列化出来的数字类型不由我们决定，写死成 Long 会在 Integer 上静默失效
        int smallMillis = 86_400_000;
        assertInstanceOf(LocalDate.class, TemporalValueCoercer.coerce(smallMillis, "DATE"));
        assertInstanceOf(LocalDate.class, TemporalValueCoercer.coerce((double) DATE_MILLIS, "DATE"));
    }

    @Test
    @DisplayName("⚠️ 读出来再写回去要是同一天")
    void roundTripKeepsSameDay() {
        // 换个时区算会让日期整体偏一天，且只在时区偏移跨过零点时显现
        LocalDate original = LocalDate.of(2026, 3, 1);
        long millis = original.atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        assertEquals(original, TemporalValueCoercer.coerce(millis, "DATE"));
    }

    @Test
    @DisplayName("isTemporal 认得出时间类类型名")
    void isTemporal() {
        assertTrue(TemporalValueCoercer.isTemporal("DATE"));
        assertTrue(TemporalValueCoercer.isTemporal("DATETIME"));
        assertTrue(TemporalValueCoercer.isTemporal("TIMESTAMP"));
        assertTrue(TemporalValueCoercer.isTemporal("TIME"));
        assertFalse(TemporalValueCoercer.isTemporal("VARCHAR"));
        assertFalse(TemporalValueCoercer.isTemporal(null));
    }

    // ==================== 读方向 ====================
    //
    // 下发字符串而不是纪元毫秒，修的是两个实打实的缺陷：
    // 日期控件回显不上（form-create 给它补了 valueFormat，解析不了毫秒数），
    // 以及导出直接 500（FastExcel 找不到 java.sql.Date 的 Converter）。

    @Test
    @DisplayName("java.sql.Date 下发成 yyyy-MM-dd")
    void normalizeSqlDate() {
        Object result = TemporalValueCoercer.normalizeValue(java.sql.Date.valueOf("2026-03-01"));
        assertEquals("2026-03-01", result);
    }

    @Test
    @DisplayName("java.sql.Time 下发成 HH:mm:ss")
    void normalizeSqlTime() {
        assertEquals("09:30:00", TemporalValueCoercer.normalizeValue(java.sql.Time.valueOf("09:30:00")));
    }

    @Test
    @DisplayName("⚠️ Timestamp 是 java.sql.Date 的兄弟、都继承 util.Date，判断顺序不能反")
    void normalizeTimestamp() {
        java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf("2026-03-01 10:20:30");
        assertEquals("2026-03-01 10:20:30", TemporalValueCoercer.normalizeValue(timestamp));
        // java.sql.Date 若被当成 util.Date 处理，会多出一段 00:00:00
        assertEquals("2026-03-01", TemporalValueCoercer.normalizeValue(java.sql.Date.valueOf("2026-03-01")));
    }

    @Test
    @DisplayName("java.time 的三个类型同样归一")
    void normalizeJavaTime() {
        assertEquals("2026-03-01", TemporalValueCoercer.normalizeValue(LocalDate.of(2026, 3, 1)));
        assertEquals("10:20:30", TemporalValueCoercer.normalizeValue(LocalTime.of(10, 20, 30)));
        assertEquals("2026-03-01 10:20:30",
                TemporalValueCoercer.normalizeValue(LocalDateTime.of(2026, 3, 1, 10, 20, 30)));
    }

    @Test
    @DisplayName("非时间类的值原样返回")
    void normalizeKeepsOthers() {
        assertEquals("abc", TemporalValueCoercer.normalizeValue("abc"));
        assertEquals(123, TemporalValueCoercer.normalizeValue(123));
        assertNull(TemporalValueCoercer.normalizeValue(null));
    }

    @Test
    @DisplayName("子表明细里的日期列也要归一 —— 漏掉会变成「主表好了、明细还是空的」")
    void normalizeNestedRows() {
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("deliver_date", java.sql.Date.valueOf("2026-04-05"));
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("sign_date", java.sql.Date.valueOf("2026-03-01"));
        row.put("items", java.util.List.of(detail));

        java.util.Map<String, Object> result = TemporalValueCoercer.normalizeRow(row);
        assertEquals("2026-03-01", result.get("sign_date"));
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> items =
                (java.util.List<java.util.Map<String, Object>>) result.get("items");
        assertEquals("2026-04-05", items.get(0).get("deliver_date"));
    }

    @Test
    @DisplayName("不修改入参 —— 调用方可能还要用原始那份")
    void normalizeDoesNotMutateInput() {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("sign_date", java.sql.Date.valueOf("2026-03-01"));
        TemporalValueCoercer.normalizeRow(row);
        assertInstanceOf(java.sql.Date.class, row.get("sign_date"));
    }

    @Test
    @DisplayName("归一之后写回去仍然走得通：字符串原样放行，数据库自己解析")
    void normalizedValueIsWritableBack() {
        Object read = TemporalValueCoercer.normalizeValue(java.sql.Date.valueOf("2026-03-01"));
        // coerce 只处理数字，字符串原样返回 —— 读写两个方向依然闭合
        assertEquals(read, TemporalValueCoercer.coerce(read, "DATE"));
    }
}
