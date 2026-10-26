package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.enums.datamodel.DataModelColumnTypeEnum;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.DATA_MODEL_COLUMN_TYPE_INVALID;

/**
 * 值类型转换器
 *
 * 前端通过 JSON 提交的值几乎都是字符串或数字，而 JDBC 需要明确的 Java 类型才能
 * 正确绑定参数。不转换会出现两类问题：
 * 1. "123" 存进 bigint 字段 —— MySQL 会隐式转换，但索引可能失效
 * 2. "2026-08-30 10:00:00" 存进 datetime —— JDBC 直接报类型不匹配
 *
 * 转换目标由**表的真实结构**决定，不信任前端声明的类型。
 *
 * @author 走进新时代
 */
public class ValueConverter {

    /**
     * 支持的日期时间格式，按常见程度排序
     */
    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    );

    private ValueConverter() {
    }

    /**
     * 按表结构转换整行数据
     *
     * @param data    前端提交的数据
     * @param columns 表的真实字段定义
     * @return 转换后的数据，key 保持原样
     */
    public static Map<String, Object> convertRow(Map<String, Object> data,
                                                 List<TableColumnModel> columns) {
        Map<String, TableColumnModel> columnMap = new LinkedHashMap<>();
        columns.forEach(column -> columnMap.put(column.getColumnName().toLowerCase(), column));

        Map<String, Object> result = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            TableColumnModel column = columnMap.get(key.toLowerCase());
            // 字段不在表里：原样放入，由 DynamicSqlExecutor 的白名单校验去拦截
            result.put(key, column == null ? value : convert(value, column.getColumnType()));
        });
        return result;
    }

    /**
     * 转换单个值
     *
     * @param value      原始值
     * @param columnType 目标类型，见 {@link DataModelColumnTypeEnum}
     */
    public static Object convert(Object value, String columnType) {
        // null 与空串统一转成 null，避免 "" 存进数字字段变成 0
        if (value == null) {
            return null;
        }
        if (value instanceof String str && StrUtil.isBlank(str)) {
            return isStringType(columnType) ? str : null;
        }
        DataModelColumnTypeEnum typeEnum = DataModelColumnTypeEnum.typeOf(columnType);
        if (typeEnum == null) {
            // 未知类型（导入已有表时可能出现不受支持的类型）：原样传给 JDBC
            return value;
        }
        return switch (typeEnum) {
            case STRING, TEXT, LONG_TEXT -> value.toString();
            case JSON -> toJsonText(value);
            // TINYINT 与 INT 在 Java 侧都是 Integer，转换逻辑相同
            case TINYINT, INT -> toInteger(value, columnType);
            case LONG -> toLong(value, columnType);
            case DECIMAL -> toDecimal(value, columnType);
            case BOOLEAN -> toBoolean(value);
            case DATE -> toLocalDate(value, columnType);
            case DATETIME -> toLocalDateTime(value, columnType);
        };
    }

    /**
     * JSON 列：JDBC 只认字符串，Map / List 这类结构化值必须先序列化
     *
     * 🔴 刻意**不**在这里校验 JSON 是否合法 —— MySQL 自己会校，而且它的判据比任何 Java 侧的
     *    形状检查都准：{@code JsonUtils.isJson} 走的是 Hutool 的 isTypeJSON，只认 {}/[] 开头结尾，
     *    会把合法的 JSON 标量（{@code "abc"}、{@code 123}、{@code true}）判成非法，
     *    而 MySQL 的 JSON 列是收这些的（{@code JSON_VALID('"abc"')} = 1）。
     *    两边各判一套的结果就是「保存时说不合法、其实库里收」——合法性只认 MySQL 一个权威。
     */
    private static Object toJsonText(Object value) {
        return value instanceof String str ? str : JsonUtils.toJsonString(value);
    }

    /**
     * 空串要不要原样保留（否则一律转 null）
     *
     * 🔴 JSON **刻意不在**这个名单里：{@code ""} 不是合法 JSON，MySQL 会直接拒。
     *    所以 JSON 列的空串要跟数字/时间一样落到 null，而不是当字符串存进去。
     */
    private static boolean isStringType(String columnType) {
        DataModelColumnTypeEnum typeEnum = DataModelColumnTypeEnum.typeOf(columnType);
        return typeEnum == DataModelColumnTypeEnum.STRING
                || typeEnum == DataModelColumnTypeEnum.TEXT
                || typeEnum == DataModelColumnTypeEnum.LONG_TEXT;
    }

    private static Integer toInteger(Object value, String columnType) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException ex) {
            throw exception(DATA_MODEL_COLUMN_TYPE_INVALID, value, columnType);
        }
    }

    private static Long toLong(Object value, String columnType) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(value.toString().trim());
        } catch (NumberFormatException ex) {
            throw exception(DATA_MODEL_COLUMN_TYPE_INVALID, value, columnType);
        }
    }

    /**
     * 金额等小数一律走 BigDecimal
     *
     * 刻意不用 Double —— 浮点数在金额场景会出现 0.1 + 0.2 != 0.3 这类问题
     */
    private static BigDecimal toDecimal(Object value, String columnType) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException ex) {
            throw exception(DATA_MODEL_COLUMN_TYPE_INVALID, value, columnType);
        }
    }

    /**
     * 布尔值兼容多种表示：true/false、1/0、"是"/"否"
     *
     * 前端复选框、下拉框、Excel 导入传过来的形态各不相同
     */
    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String str = value.toString().trim();
        return "true".equalsIgnoreCase(str) || "1".equals(str)
                || "是".equals(str) || "y".equalsIgnoreCase(str);
    }

    private static LocalDate toLocalDate(Object value, String columnType) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        String str = value.toString().trim();
        try {
            // 兼容前端传完整日期时间到 date 字段的情况
            return str.length() > 10
                    ? toLocalDateTime(str, columnType).toLocalDate()
                    : LocalDate.parse(str);
        } catch (Exception ex) {
            throw exception(DATA_MODEL_COLUMN_TYPE_INVALID, value, columnType);
        }
    }

    private static LocalDateTime toLocalDateTime(Object value, String columnType) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        String str = value.toString().trim();
        // 时间戳（毫秒）
        if (str.matches("\\d{13}")) {
            return LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(Long.parseLong(str)),
                    java.time.ZoneId.systemDefault());
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(str, formatter);
            } catch (Exception ignore) {
                // 换下一个格式继续试
            }
        }
        // 只有日期没有时间：补 00:00:00
        try {
            return LocalDate.parse(str).atStartOfDay();
        } catch (Exception ex) {
            throw exception(DATA_MODEL_COLUMN_TYPE_INVALID, value, columnType);
        }
    }

}
