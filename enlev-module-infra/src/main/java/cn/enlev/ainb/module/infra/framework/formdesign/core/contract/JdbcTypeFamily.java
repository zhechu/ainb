package cn.enlev.ainb.module.infra.framework.formdesign.core.contract;

/**
 * JDBC 列类型族
 *
 * 契约里的 columnTypes 用类型族表达而不是具体的 JDBC 类型名 —— 后者各数据库写法不一
 * （MySQL 的 datetime、PostgreSQL 的 timestamptz、Oracle 的 DATE 语义都不同），
 * 逐个枚举既写不全也难维护。
 *
 * <p>本枚举只是族的标识，【不含】匹配关键字。关键字与匹配优先级放在契约文件的
 * {@code jdbcTypeFamilies} 数组里，由 {@link WidgetContractRegistry#resolveJdbcTypeFamily} 使用。
 * 之所以不写在枚举里：这套匹配是顺序敏感的（"TIMESTAMP" 含 "TIME"、"BIGINT" 含 "INT"），
 * 前后端必须用同一份顺序，写在各自代码里就是两份可以独立漂移的实现。
 *
 * @author 走进新时代
 */
public enum JdbcTypeFamily {

    /**
     * 定长/变长字符串。varchar、char、nvarchar 等
     */
    STRING,
    /**
     * 大文本。text、clob 等
     */
    TEXT,
    /**
     * 整型（非 Long）。tinyint、smallint、int
     */
    INTEGER,
    /**
     * 长整型。bigint
     */
    LONG,
    /**
     * 定点与浮点。decimal、numeric、float、double
     */
    DECIMAL,
    /**
     * 布尔。boolean、bit
     */
    BOOLEAN,
    /**
     * 纯日期
     */
    DATE,
    /**
     * 纯时间
     */
    TIME,
    /**
     * 日期时间。timestamp、datetime
     */
    DATETIME,
    /**
     * 原生 JSON 列
     */
    JSON,
    /**
     * 二进制
     */
    BINARY

}
