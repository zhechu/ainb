package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.mybatis.core.util.SystemTablePolicy;
import cn.enlev.ainb.framework.mybatis.core.util.TableOperation;

import java.util.Set;
import java.util.regex.Pattern;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * SQL 安全校验器
 *
 * 数据模型与动态 CRUD 都要把「表名、字段名」拼进 SQL，而这两个位置**无法使用 ? 占位符**，
 * 是 SQL 注入的主要入口。本类是所有拼接前的唯一入口，三道关卡：
 *
 * 1. {@link #checkTableName(String)} / {@link #checkColumnName(String)}
 *    标识符白名单正则 + 长度 + 关键字，拦掉一切奇怪字符（空格、引号、分号、注释符、反引号）
 * 2. {@link #checkTableManaged(String, Set)}
 *    表名必须在「受管表登记」范围内，防止越权操作框架表或其他业务表
 * 3. {@link #checkColumnExists(String, Set)} / {@link #checkOrderColumn(String, Set)}
 *    字段名必须真实存在于该表（实时读取的字段清单），排序字段额外走这一关
 *
 * 注意：本类只做校验，不做转义。不要试图用「转义」来放宽标识符字符集 ——
 * 反引号包裹在 MySQL 里仍可被 `` 逃逸，白名单才是可靠做法。
 *
 * @author 走进新时代
 */
public class SqlSafeGuard {

    /**
     * 合法标识符：字母或下划线开头，后接字母、数字、下划线
     *
     * 刻意不允许 $ 与中文 —— MySQL 允许，但会给后续代码生成、JSON 序列化带来麻烦
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    /**
     * snake_case 标识符：全小写，字母开头，字母或数字结尾，中间可含下划线
     *
     * 比 {@link #IDENTIFIER_PATTERN} 严格，只用于<b>新建</b>路径的建模规范校验：
     * <ul>
     *   <li>禁驼峰 —— {@code SqlSafeGuard} 全程大小写不敏感比对，而 Linux 下
     *       {@code lower_case_table_names=0} 时 MySQL 表名是大小写敏感的，
     *       混用大小写会让「同一张表」在两个平台上表现不同</li>
     *   <li>禁下划线开头/结尾 —— {@code _foo} 与 {@code foo_} 常是手滑，且前者与
     *       form-create 的内部保留字前缀冲突</li>
     * </ul>
     *
     * ⚠️ 至少 2 个字符。单字母表名/列名（{@code a}、{@code t}）在真实库里只会是临时产物。
     */
    private static final Pattern SNAKE_CASE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,62}[a-z0-9]$");

    /**
     * 供跨模块只读取用的 snake_case 正则文本
     *
     * agent 的生成后校验器要在提交前按同一条规则拦住不合规的表名/列名。
     * 它不能自己抄一份 —— 抄了就会漂移，而漂移的表现是
     * 「AI 说合法、后端拒绝」（白烧一轮 token）或「AI 说不合法、界面上能建」。
     *
     * <p>给的是<b>新建路径</b>的那条（更严格的 snake_case），
     * 因为 AI 只被允许走新建路径。
     */
    public static String snakeCasePattern() {
        return SNAKE_CASE_PATTERN.pattern();
    }

    /**
     * 标识符最大长度（MySQL 上限是 64）
     */
    public static final int IDENTIFIER_MAX_LENGTH = 64;

    /**
     * MySQL 保留关键字
     *
     * 取自 MySQL 8.0 官方保留字表。用表名/字段名撞上这些词时，不写反引号就会语法错误；
     * 与其到处补反引号，不如直接禁止。
     */
    private static final Set<String> MYSQL_KEYWORDS = Set.of(
            "accessible", "add", "all", "alter", "analyze", "and", "as", "asc", "asensitive",
            "before", "between", "bigint", "binary", "blob", "both", "by",
            "call", "cascade", "case", "change", "char", "character", "check", "collate",
            "column", "condition", "constraint", "continue", "convert", "create", "cross",
            "cube", "cume_dist", "current_date", "current_time", "current_timestamp", "current_user", "cursor",
            "database", "databases", "day_hour", "day_microsecond", "day_minute", "day_second",
            "dec", "decimal", "declare", "default", "delayed", "delete", "dense_rank", "desc",
            "describe", "deterministic", "distinct", "distinctrow", "div", "double", "drop", "dual",
            "each", "else", "elseif", "empty", "enclosed", "escaped", "except", "exists", "exit", "explain",
            "false", "fetch", "first_value", "float", "float4", "float8", "for", "force",
            "foreign", "from", "fulltext", "function",
            "generated", "get", "grant", "group", "grouping", "groups",
            "having", "high_priority", "hour_microsecond", "hour_minute", "hour_second",
            "if", "ignore", "in", "index", "infile", "inner", "inout", "insensitive", "insert",
            "int", "int1", "int2", "int3", "int4", "int8", "integer", "intersect", "interval",
            "into", "io_after_gtids", "io_before_gtids", "is", "iterate",
            "join", "json_table", "key", "keys", "kill",
            "lag", "last_value", "lateral", "lead", "leading", "leave", "left", "like", "limit",
            "linear", "lines", "load", "localtime", "localtimestamp", "lock", "long", "longblob",
            "longtext", "loop", "low_priority",
            "master_bind", "master_ssl_verify_server_cert", "match", "maxvalue", "mediumblob",
            "mediumint", "mediumtext", "middleint", "minute_microsecond", "minute_second", "mod", "modifies",
            "natural", "not", "no_write_to_binlog", "nth_value", "ntile", "null", "numeric",
            "of", "on", "optimize", "optimizer_costs", "option", "optionally", "or", "order", "out",
            "outer", "outfile", "over",
            "partition", "percent_rank", "precision", "primary", "procedure", "purge",
            "range", "rank", "read", "reads", "read_write", "real", "recursive", "references",
            "regexp", "release", "rename", "repeat", "replace", "require", "resignal", "restrict",
            "return", "revoke", "right", "rlike", "row", "rows", "row_number",
            "schema", "schemas", "second_microsecond", "select", "sensitive", "separator", "set",
            "show", "signal", "smallint", "spatial", "specific", "sql", "sqlexception", "sqlstate",
            "sqlwarning", "sql_big_result", "sql_calc_found_rows", "sql_small_result", "ssl",
            "starting", "stored", "straight_join", "system",
            "table", "terminated", "then", "tinyblob", "tinyint", "tinytext", "to", "trailing",
            "trigger", "true",
            "undo", "union", "unique", "unlock", "unsigned", "update", "usage", "use", "using",
            "utc_date", "utc_time", "utc_timestamp",
            "values", "varbinary", "varchar", "varcharacter", "varying", "virtual",
            "when", "where", "while", "window", "with", "write",
            "xor", "year_month", "zerofill"
    );

    private SqlSafeGuard() {
    }

    // ==================== 第一关：标识符合法性 ====================

    /**
     * 校验表名合法
     *
     * @param tableName 表名
     */
    public static void checkTableName(String tableName) {
        if (StrUtil.isBlank(tableName) || !IDENTIFIER_PATTERN.matcher(tableName).matches()) {
            throw exception(DATA_MODEL_TABLE_NAME_INVALID, tableName);
        }
        if (isKeyword(tableName)) {
            throw exception(DATA_MODEL_TABLE_NAME_KEYWORD, tableName);
        }
    }

    /**
     * 校验字段名合法
     *
     * @param columnName 字段名
     */
    public static void checkColumnName(String columnName) {
        if (StrUtil.isBlank(columnName) || !IDENTIFIER_PATTERN.matcher(columnName).matches()) {
            throw exception(DATA_MODEL_COLUMN_NAME_INVALID, columnName);
        }
        if (isKeyword(columnName)) {
            throw exception(DATA_MODEL_COLUMN_NAME_KEYWORD, columnName);
        }
    }

    /**
     * 判断是否为 MySQL 保留关键字（大小写不敏感）
     */
    public static boolean isKeyword(String identifier) {
        return identifier != null && MYSQL_KEYWORDS.contains(identifier.toLowerCase());
    }

    /**
     * 判断标识符是否符合白名单正则（不校验关键字）
     *
     * 索引名等「非表名字段名」的标识符用它做格式校验，错误提示各自定制
     */
    public static boolean isIdentifier(String identifier) {
        return identifier != null && IDENTIFIER_PATTERN.matcher(identifier).matches();
    }

    /**
     * 判断是否为系统内置表
     *
     * 口径 = {@link TableOperation#DDL} —— 除了系统核心表，还占住未启用模块的前缀
     * （crm_/erp_/mall_ 等），避免用户建出将来会撞名的表。
     *
     * ⚠️ 这与「写数据」的口径不同：业务模块表是可以被表单绑定并写入的。
     * 详见 {@link SystemTablePolicy} 的类注释。
     */
    public static boolean isFrameworkTable(String tableName) {
        return !SystemTablePolicy.isAllowed(tableName, TableOperation.DDL);
    }

    /**
     * 校验不是系统内置表
     *
     * 建表时调用：防止用户创建 system_xxx 这类与框架表混淆的表名
     */
    public static void checkNotFrameworkTable(String tableName) {
        if (isFrameworkTable(tableName)) {
            throw exception(DATA_MODEL_TABLE_IS_FRAMEWORK, tableName);
        }
    }

    // ==================== 第一关半：建模规范（只作用于新建） ====================

    /**
     * 判断标识符是否为 snake_case
     */
    public static boolean isSnakeCase(String identifier) {
        return identifier != null && SNAKE_CASE_PATTERN.matcher(identifier).matches();
    }

    /**
     * 校验表名符合 snake_case
     *
     * ⚠️ 只在<b>新建</b>时调用。{@code importTables} 导入存量表不能调 ——
     * 库里本就存在的 {@code QRTZ_LOCKS}、{@code test_demo_*} 会被全部挡在门外。
     *
     * @param enforced 是否启用，来自 {@code DataModelProperties.snakeCaseEnforced}
     */
    public static void checkTableNameSnakeCase(String tableName, boolean enforced) {
        if (enforced && !isSnakeCase(tableName)) {
            throw exception(DATA_MODEL_TABLE_NAME_NOT_SNAKE_CASE, tableName);
        }
    }

    /**
     * 校验字段名符合 snake_case
     *
     * @param enforced 是否启用，来自 {@code DataModelProperties.snakeCaseEnforced}
     */
    public static void checkColumnNameSnakeCase(String columnName, boolean enforced) {
        if (enforced && !isSnakeCase(columnName)) {
            throw exception(DATA_MODEL_COLUMN_NAME_NOT_SNAKE_CASE, columnName);
        }
    }

    /**
     * 校验表名带业务前缀
     *
     * 这与 {@link #checkNotFrameworkTable(String)} 是<b>两道独立的关卡，都要过</b>：
     * <ul>
     *   <li>{@code checkNotFrameworkTable} 是反向黑名单 —— 不许撞上 24 个框架/预留模块前缀</li>
     *   <li>本方法是正向白名单 —— 必须以约定前缀开头</li>
     * </ul>
     * 少了正向这道，{@code abc}、{@code t1}、{@code yonghubiao} 这类名字全都合法，
     * 而它们还会被 {@code getDatabaseTableList} 喂给表单设计器，长期下来库里没人认得清哪些是业务表。
     *
     * @param enforced 是否启用，来自 {@code DataModelProperties.tablePrefixEnforced}
     * @param prefix   要求的前缀，来自 {@code DataModelProperties.tablePrefix}
     */
    public static void checkBusinessTablePrefix(String tableName, boolean enforced, String prefix) {
        if (!enforced || StrUtil.isBlank(prefix)) {
            return;
        }
        if (tableName == null || !tableName.toLowerCase().startsWith(prefix.toLowerCase())) {
            throw exception(DATA_MODEL_TABLE_PREFIX_REQUIRED, tableName, prefix);
        }
    }

    /**
     * 校验索引名前缀：唯一索引 {@code uk_}，普通索引 {@code idx_}
     *
     * 这条约定的<b>事实执行率已接近 100%</b>（基线库 114 个索引里 idx_ 97 个、uk_ 13 个），
     * 但此前只靠前端自动生成索引名来保证，且用户可以手改、后端零校验 ——
     * 也就是说绕开界面直接调接口时，这条约定等于不存在。
     *
     * @param enforced 是否启用，来自 {@code DataModelProperties.indexPrefixEnforced}
     */
    public static void checkIndexNamePrefix(String indexName, boolean unique, boolean enforced) {
        if (!enforced || StrUtil.isBlank(indexName)) {
            return;
        }
        String expected = unique ? "uk_" : "idx_";
        if (!indexName.toLowerCase().startsWith(expected)) {
            throw exception(DATA_MODEL_INDEX_NAME_PREFIX_INVALID, indexName);
        }
    }

    /**
     * 判断注释是否「有意义」
     *
     * 下限刻意压得很低，只拦明显的敷衍 —— 判断「注释写得好不好」不是程序该做的事，
     * 但「注释等于字段名」「注释是个 1」这类等于没写，拦得住。
     *
     * <p>此前两处的严格度还不一致：列注释走 {@code isBlank}（一个空格拦得住、{@code "1"} 拦不住），
     * 表注释走 VO 的 {@code @NotEmpty}（一个空格就能过）。本方法统一二者。
     *
     * @param comment 注释
     * @param name    对应的表名/字段名，注释与它相同视为无意义；传 null 则跳过该项判断
     */
    public static boolean isMeaningfulComment(String comment, String name) {
        String trimmed = StrUtil.trim(comment);
        if (StrUtil.isBlank(trimmed) || trimmed.length() < 2) {
            return false;
        }
        if (name != null && trimmed.equalsIgnoreCase(name.trim())) {
            return false;
        }
        // 纯数字、纯标点/符号（不含任何字母与汉字）视为无意义
        return trimmed.chars().anyMatch(Character::isLetter);
    }

    // ==================== 第二关：受管范围 ====================

    /**
     * 校验表在受管范围内
     *
     * 改表、删表、动态 CRUD 都必须先过这一关。即使标识符合法，也不允许操作
     * 未登记的表 —— 否则等于把整个数据库的 DDL 权限暴露给接口调用方。
     *
     * @param tableName    待操作的表名
     * @param managedTables 受管表名集合（来自 infra_data_model）
     */
    public static void checkTableManaged(String tableName, Set<String> managedTables) {
        checkTableName(tableName);
        if (CollUtil.isEmpty(managedTables) || !containsIgnoreCase(managedTables, tableName)) {
            throw exception(DATA_MODEL_TABLE_NOT_MANAGED, tableName);
        }
    }

    // ==================== 第三关：字段真实存在 ====================

    /**
     * 校验字段真实存在于表中
     *
     * @param columnName    字段名
     * @param actualColumns 该表的真实字段集合（实时读取 information_schema 得到）
     */
    public static void checkColumnExists(String columnName, Set<String> actualColumns) {
        checkColumnName(columnName);
        if (CollUtil.isEmpty(actualColumns) || !containsIgnoreCase(actualColumns, columnName)) {
            throw exception(DATA_MODEL_COLUMN_NAME_INVALID, columnName);
        }
    }

    /**
     * 校验并返回安全的排序片段
     *
     * 强制两件事：
     * 1. 字段必须在真实字段清单里
     * 2. 排序方向只能是 ASC / DESC，其余一律按 ASC 处理
     *
     * @param columnName    排序字段
     * @param asc           是否升序
     * @param actualColumns 该表的真实字段集合
     * @return 形如 `create_time` DESC 的安全片段
     */
    public static String checkOrderColumn(String columnName, boolean asc, Set<String> actualColumns) {
        checkColumnExists(columnName, actualColumns);
        return wrap(columnName) + (asc ? " ASC" : " DESC");
    }

    // ==================== 工具方法 ====================

    /**
     * 用反引号包裹标识符
     *
     * 前提：调用方已通过上面的白名单校验，此时包裹只是为了规避潜在的关键字问题，
     * 不承担安全职责。未经校验的字符串禁止传入。
     */
    public static String wrap(String identifier) {
        return "`" + identifier + "`";
    }

    private static boolean containsIgnoreCase(Set<String> set, String target) {
        return set.stream().anyMatch(item -> item.equalsIgnoreCase(target));
    }

}
