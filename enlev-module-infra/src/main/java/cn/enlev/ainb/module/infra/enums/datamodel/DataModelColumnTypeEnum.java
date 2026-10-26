package cn.enlev.ainb.module.infra.enums.datamodel;

import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 数据模型的字段类型枚举
 *
 * 刻意做成「受控的有限集合」而不是让用户自由填 MySQL 类型：
 * 1. 类型是要拼进 DDL 的，枚举化后天然没有注入面
 * 2. 同时固定了 Java 类型映射，便于后续与代码生成器对接
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum DataModelColumnTypeEnum implements ArrayValuable<String> {

    // ========== 字符串 ==========
    STRING("STRING", "varchar", "String", true, false, "短文本，需指定长度"),
    TEXT("TEXT", "text", "String", false, false, "长文本"),
    LONG_TEXT("LONG_TEXT", "longtext", "String", false, false, "超长文本"),

    // ========== 结构化 ==========
    // 🔴 JSON 能建列，但**不能做键**，而且比 TEXT 更彻底：MySQL 报的是 3152
    //    「JSON column 'x' supports indexing only via generated columns on a specified JSON path.」——
    //    不是 1170 那种「给个前缀长度就行」，而是「只能靠生成列 + JSON path」。
    //    实测（MySQL 8.0，见 DataModelMysqlIntegrationTest#testJsonColumnEndToEnd）：
    //    索引、主键、乃至写了前缀长度的 KEY (j(255)) 三种都是 3152，前缀长度对它一点用没有。
    //    生成列这条路本平台的 TableColumnModel 与 DDL 生成都表达不了，所以结论就是不可做键。
    //    MysqlDdlBuilder 的 validateColumns / validateIndexes 会提前拦下 —— 拦在建 DDL 之前，
    //    因为 DdlExecutor 只取 ex.getMessage()，真放过去用户拿到的是 Spring 那句
    //    「bad SQL grammar [ALTER TABLE …]」，连「JSON」三个字都不含。
    //    description 会被 WorkflowDslPlannerPrompt 原样印进「建表列类型白名单」，
    //    所以「不能做主键或索引列」这句是写给模型看的，别删。
    JSON("JSON", "json", "String", false, false, "JSON 文档，不能做主键或索引列"),

    // ========== 数字 ==========
    // 状态、类型这类「取值很少且配字典」的字段用 TINYINT，与框架表 60 余处 status tinyint 的惯例一致
    TINYINT("TINYINT", "tinyint", "Integer", false, false, "小整数，状态/类型字段用它并配字典"),
    INT("INT", "int", "Integer", false, false, "整数"),
    LONG("LONG", "bigint", "Long", false, false, "长整数"),
    DECIMAL("DECIMAL", "decimal", "BigDecimal", true, true, "小数，需指定长度与精度"),
    BOOLEAN("BOOLEAN", "bit", "Boolean", false, false, "布尔"),

    // ========== 时间 ==========
    DATE("DATE", "date", "LocalDate", false, false, "日期"),
    DATETIME("DATETIME", "datetime", "LocalDateTime", false, false, "日期时间"),
    ;

    public static final String[] ARRAYS = Arrays.stream(values())
            .map(DataModelColumnTypeEnum::getType).toArray(String[]::new);

    /**
     * 类型标识（前端与接口使用）
     */
    private final String type;
    /**
     * MySQL 的数据类型
     */
    private final String mysqlType;
    /**
     * 对应的 Java 类型，用于后续对接代码生成器
     */
    private final String javaType;
    /**
     * 是否需要指定长度
     */
    private final Boolean needLength;
    /**
     * 是否需要指定精度（小数位）
     */
    private final Boolean needScale;
    /**
     * 描述
     */
    private final String description;

    @Override
    public String[] array() {
        return ARRAYS;
    }

    /**
     * 根据类型标识获得枚举，找不到返回 null
     */
    public static DataModelColumnTypeEnum typeOf(String type) {
        return Arrays.stream(values())
                .filter(item -> item.getType().equalsIgnoreCase(type))
                .findFirst().orElse(null);
    }

}
