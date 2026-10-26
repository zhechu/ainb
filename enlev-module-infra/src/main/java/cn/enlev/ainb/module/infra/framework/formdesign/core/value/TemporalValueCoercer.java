package cn.enlev.ainb.module.infra.framework.formdesign.core.value;

import cn.hutool.core.util.StrUtil;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时间类列的读写值归一
 *
 * <h3>它修的是一个「读出来的东西写不回去」的问题</h3>
 *
 * 读取路径用 {@code jdbcTemplate.queryForList}，DATE 列拿到的是
 * {@code java.sql.Date}；这份 Map 直接被 Jackson 序列化下发，
 * 而 Jackson 对 {@code java.sql.Date} 的默认写法是**纪元毫秒数**。
 * 于是 {@code GET /{code}/get} 返回的 {@code start_date} 是 {@code 1772294400000}。
 *
 * 把这份数据原样交回 {@code PUT /{code}/update} 时，那个 Long 被塞进
 * PreparedStatement 的日期列，MySQL 直接拒绝：
 *
 * <pre>
 * Data truncation: Incorrect date value: '1772294400000' for column 'start_date'
 * </pre>
 *
 * 也就是说**读写两个方向的格式对不上**，一条记录读出来再写回去是失败的。
 * 弹窗编辑碰不到，是因为值在日期控件里转过一手；
 * 列表行内编辑要把整条记录回传，一上来就撞上了。
 *
 * <h3>写入侧：也认毫秒数</h3>
 *
 * {@link #coerce(Object, String)} 把回传的毫秒数还原成日期类型。
 * 这一层是**纯增量**的 —— 这类值以前一律抛异常，不存在依赖旧行为的调用方。
 *
 * 字符串一律原样放行：MySQL 自己能解析 {@code '2026-03-01'}、
 * {@code '2026-03-01 10:00:00'} 这些写法，我们再插一手只会多一处格式分歧。
 *
 * <h3>读取侧：下发字符串而不是毫秒数</h3>
 *
 * {@link #normalizeRow(Map)} 在数据下发前把时间类值统一成
 * {@code yyyy-MM-dd} / {@code HH:mm:ss} / {@code yyyy-MM-dd HH:mm:ss} 字符串。
 * 这不是「顺手统一一下格式」，它修的是两个实打实的缺陷：
 *
 * <ol>
 * <li><b>日期控件回显不上</b>。form-create 的 datePicker 会给控件补一个默认的
 *     {@code valueFormat}（date 是 {@code YYYY-MM-DD}），而 Element Plus 拿到
 *     {@code dayjs(1788192000000, 'YYYY-MM-DD')} 解析不出有效日期 —— 表现是
 *     编辑时明明选过日期，再打开却是空的。时间列不受影响，因为 TIME 列
 *     {@code queryForList} 给回的本来就是字符串。</li>
 * <li><b>导出直接 500</b>。FastExcel 的转换器按值的**确切类型**查找，
 *     {@code java.sql.Date} 没有对应的 Converter，抛
 *     {@code Can not find 'Converter' support class Date}，整张表导不出来。</li>
 * </ol>
 *
 * 下发字符串之后，写回来的值走的是「字符串原样放行」那一支，读写方向依然闭合。
 *
 * @author 走进新时代
 */
public class TemporalValueCoercer {

    private TemporalValueCoercer() {
    }

    /**
     * 按列的 jdbcType 归一写入值
     *
     * @param value        表单提交或回传的值
     * @param jdbcTypeName 列的 JDBC 类型名，取自表绑定的字段映射
     * @return 归一后的值。不需要归一时**原样返回入参**
     */
    public static Object coerce(Object value, String jdbcTypeName) {
        if (!(value instanceof Number number) || StrUtil.isBlank(jdbcTypeName)) {
            return value;
        }
        // 顺序敏感：TIMESTAMP 含 TIME，必须先判长的那个
        String type = jdbcTypeName.toUpperCase();
        if (type.contains("TIMESTAMP") || type.contains("DATETIME")) {
            return toLocalDateTime(number);
        }
        if (type.contains("DATE")) {
            return toLocalDateTime(number).toLocalDate();
        }
        if (type.contains("TIME")) {
            return toLocalDateTime(number).toLocalTime();
        }
        return value;
    }

    /**
     * 纪元毫秒 → 本地日期时间
     *
     * 用系统默认时区，与 {@code java.sql.Date} 序列化成毫秒时用的是同一个时区 ——
     * 换一个时区会让「读出来再写回去」的日期整体偏一天，
     * 而且只在时区偏移跨过零点时才显现，是最难复现的那种偏差。
     */
    private static LocalDateTime toLocalDateTime(Number millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis.longValue()), ZoneId.systemDefault());
    }

    /** 供测试与调用方判断某个类型名算不算时间类 */
    public static boolean isTemporal(String jdbcTypeName) {
        if (StrUtil.isBlank(jdbcTypeName)) {
            return false;
        }
        String type = jdbcTypeName.toUpperCase();
        return type.contains("DATE") || type.contains("TIME");
    }

    /** 便于单测直接构造期望值 */
    static LocalDate toLocalDate(long millis) {
        return toLocalDateTime(millis).toLocalDate();
    }

    /** 便于单测直接构造期望值 */
    static LocalTime toLocalTimeValue(long millis) {
        return toLocalDateTime(millis).toLocalTime();
    }

    // ==================== 读方向 ====================

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 把一行数据里的时间类值统一成字符串
     *
     * @param row 数据行。<b>不修改入参</b>，子表明细也一并递归处理 ——
     *            明细里同样会有日期列，漏掉的话「主表的日期好了、明细的还是空的」
     * @return 归一后的新行；入参为 null 时原样返回 null
     */
    public static Map<String, Object> normalizeRow(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>(row.size());
        row.forEach((key, value) -> result.put(key, normalizeValue(value)));
        return result;
    }

    /** 批量版。列表与导出用 */
    public static List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(normalizeRow(row));
        }
        return result;
    }

    /**
     * 单个值的归一
     *
     * 集合与嵌套 Map 要递归：子表明细是挂在主行上的 {@code List<Map>}，
     * 多值字段还原之后也是集合。
     */
    @SuppressWarnings("unchecked")
    public static Object normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        // 顺序敏感：Timestamp 与 java.sql.Date 都是 java.util.Date 的子类，
        // 先判具体的那两个，落到 java.util.Date 的只剩真正的 util.Date
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(DATE_TIME_FORMAT);
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().format(DATE_FORMAT);
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().format(TIME_FORMAT);
        }
        if (value instanceof java.util.Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault())
                    .format(DATE_TIME_FORMAT);
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.format(DATE_TIME_FORMAT);
        }
        if (value instanceof LocalDate date) {
            return date.format(DATE_FORMAT);
        }
        if (value instanceof LocalTime time) {
            return time.format(TIME_FORMAT);
        }
        if (value instanceof Map<?, ?> map) {
            return normalizeRow((Map<String, Object>) map);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> items = new java.util.ArrayList<>(collection.size());
            for (Object item : collection) {
                items.add(normalizeValue(item));
            }
            return items;
        }
        return value;
    }
}
