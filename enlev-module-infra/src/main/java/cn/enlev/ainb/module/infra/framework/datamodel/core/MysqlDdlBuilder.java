package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.enums.datamodel.DataModelColumnTypeEnum;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableIndexModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * MySQL DDL 生成器
 *
 * 只负责「拼 SQL 字符串」，不执行、不访问数据库，因此可以纯单测覆盖，
 * 也让「预览 DDL」功能天然可用 —— 生成与执行分离是本类存在的意义。
 *
 * 安全前提：所有标识符在进入本类前必须已通过 {@link SqlSafeGuard} 校验。
 * 本类内部对标识符统一用反引号包裹，但**不承担安全职责**。
 *
 * @author 走进新时代
 */
public class MysqlDdlBuilder {

    /**
     * 默认字符串长度
     *
     * ⚠️ 这是一条<b>静默改写</b>：STRING 不传 length 不会报错，会悄悄用 255。
     * 公开出来是给 agent 的校验器用的 —— 它要在提交前提醒「你没写长度，
     * 建出来会是 varchar(255)」，而不是等表建完了才发现列宽不对。
     * 建表是不可逆的，发现得晚就只能改表。
     */
    public static final int STRING_DEFAULT_LENGTH = 255;

    /**
     * varchar 单列长度上限
     *
     * 单列上限实际受行长度限制，16383 是 utf8mb4 下的理论上限，这里作兜底校验。
     */
    public static final int STRING_MAX_LENGTH = 16383;

    /**
     * DECIMAL 的默认总位数与小数位
     */
    private static final int DEFAULT_DECIMAL_LENGTH = 10;
    private static final int DEFAULT_DECIMAL_SCALE = 2;

    private MysqlDdlBuilder() {
    }

    // ==================== 建表 ====================

    /**
     * 生成 CREATE TABLE 语句（不含索引）
     */
    public static String buildCreateTable(String tableName, String tableComment,
                                          List<TableColumnModel> columns) {
        return buildCreateTable(tableName, tableComment, columns, List.of());
    }

    /**
     * 生成 CREATE TABLE 语句
     *
     * @param tableName    表名
     * @param tableComment 表描述
     * @param columns      字段列表
     * @param indexes      索引列表，可为空
     * @return DDL
     */
    public static String buildCreateTable(String tableName, String tableComment,
                                          List<TableColumnModel> columns,
                                          List<TableIndexModel> indexes) {
        validateColumns(columns);
        validateIndexes(indexes, columns);

        List<String> lines = new ArrayList<>();
        for (TableColumnModel column : columns) {
            lines.add("  " + buildColumnDefinition(column));
        }
        // 主键单独一行，不写在字段定义里 —— 便于将来支持复合主键
        String primaryKey = columns.stream().filter(TableColumnModel::isPrimaryKey)
                .map(column -> SqlSafeGuard.wrap(column.getColumnName()))
                .collect(Collectors.joining(", "));
        lines.add("  PRIMARY KEY (" + primaryKey + ")");
        // 二级索引跟在主键后，建表时一次性建好，避免事后补 ALTER 全表重建
        for (TableIndexModel index : CollUtil.emptyIfNull(indexes)) {
            lines.add("  " + buildIndexDefinition(index));
        }

        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE ").append(SqlSafeGuard.wrap(tableName)).append(" (\n");
        sql.append(String.join(",\n", lines));
        sql.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        if (StrUtil.isNotBlank(tableComment)) {
            sql.append(" COMMENT=").append(quote(tableComment));
        }
        return sql.toString();
    }

    // ==================== 改表 ====================

    /**
     * 生成 ALTER TABLE 语句列表
     *
     * 一次改表可能涉及多个动作，逐条返回而不合并成一句，原因：
     * 1. 便于在预览里逐条展示
     * 2. 某条失败时能定位到具体动作
     *
     * @param tableName  表名
     * @param addColumns    新增字段
     * @param modifyColumns 修改字段（含改名）
     * @param dropColumns   删除字段名
     * @return DDL 列表，可能为空
     */
    public static List<String> buildAlterTable(String tableName,
                                               List<TableColumnModel> addColumns,
                                               List<TableColumnModel> modifyColumns,
                                               List<String> dropColumns) {
        List<String> sqlList = new ArrayList<>();
        String wrappedTable = SqlSafeGuard.wrap(tableName);
        // 先删后加，避免「删掉旧字段再加同名字段」时冲突
        if (CollUtil.isNotEmpty(dropColumns)) {
            for (String columnName : dropColumns) {
                sqlList.add("ALTER TABLE " + wrappedTable
                        + " DROP COLUMN " + SqlSafeGuard.wrap(columnName));
            }
        }
        if (CollUtil.isNotEmpty(addColumns)) {
            for (TableColumnModel column : addColumns) {
                sqlList.add("ALTER TABLE " + wrappedTable
                        + " ADD COLUMN " + buildColumnDefinition(column));
            }
        }
        if (CollUtil.isNotEmpty(modifyColumns)) {
            for (TableColumnModel column : modifyColumns) {
                sqlList.add(buildModifyColumn(wrappedTable, column));
            }
        }
        return sqlList;
    }

    /**
     * 生成修改字段的 DDL
     *
     * 改名用 CHANGE COLUMN，仅改类型用 MODIFY COLUMN。
     * 用 CHANGE 时必须重复完整定义，这是 MySQL 的语法要求。
     */
    private static String buildModifyColumn(String wrappedTable, TableColumnModel column) {
        boolean rename = StrUtil.isNotBlank(column.getOldColumnName())
                && !column.getOldColumnName().equalsIgnoreCase(column.getColumnName());
        if (rename) {
            return "ALTER TABLE " + wrappedTable
                    + " CHANGE COLUMN " + SqlSafeGuard.wrap(column.getOldColumnName())
                    + " " + buildColumnDefinition(column);
        }
        return "ALTER TABLE " + wrappedTable
                + " MODIFY COLUMN " + buildColumnDefinition(column);
    }

    /**
     * 生成修改表描述的 DDL
     */
    public static String buildAlterTableComment(String tableName, String tableComment) {
        return "ALTER TABLE " + SqlSafeGuard.wrap(tableName)
                + " COMMENT=" + quote(tableComment);
    }

    // ==================== 索引 ====================

    /**
     * 生成索引相关的 ALTER TABLE 语句列表
     *
     * 调用方负责顺序：删索引要在删字段<b>之前</b>（字段没了索引必然失败），
     * 加索引要在加字段<b>之后</b>（索引引用的字段必须先存在）。
     *
     * @param tableName      表名
     * @param addIndexes     要新增的索引
     * @param dropIndexNames 要删除的索引名
     * @return DDL 列表，可能为空
     */
    public static List<String> buildIndexAlter(String tableName,
                                               List<TableIndexModel> addIndexes,
                                               List<String> dropIndexNames) {
        List<String> sqlList = new ArrayList<>();
        String wrappedTable = SqlSafeGuard.wrap(tableName);
        for (String indexName : CollUtil.emptyIfNull(dropIndexNames)) {
            sqlList.add("ALTER TABLE " + wrappedTable
                    + " DROP INDEX " + SqlSafeGuard.wrap(indexName));
        }
        for (TableIndexModel index : CollUtil.emptyIfNull(addIndexes)) {
            sqlList.add("ALTER TABLE " + wrappedTable + " ADD " + buildIndexDefinition(index));
        }
        return sqlList;
    }

    /**
     * 生成索引定义片段，形如：KEY `idx_x` (`a`, `b`) / UNIQUE KEY `uk_x` (`a`)
     *
     * CREATE TABLE 里直接拼这一行；ALTER TABLE 时前面补 ADD 即可复用
     */
    private static String buildIndexDefinition(TableIndexModel index) {
        String columns = index.getColumnNames().stream()
                .map(SqlSafeGuard::wrap)
                .collect(Collectors.joining(", "));
        return (index.isUnique() ? "UNIQUE KEY " : "KEY ")
                + SqlSafeGuard.wrap(index.getIndexName()) + " (" + columns + ")";
    }

    /**
     * 校验索引列表：名字合法不重复、列非空且都真实存在于字段列表、列的类型可建键
     *
     * 索引列不存在时 MySQL 会报「Key column doesn't exist」，
     * 但那时 CREATE TABLE 可能已经执行了一半，提前校验才能给用户干净的错误。
     * 「列是 TEXT/JSON 这类不可建键的类型」是同一条理由，见 {@link #isNonKeyable}
     */
    public static void validateIndexes(List<TableIndexModel> indexes, List<TableColumnModel> columns) {
        if (CollUtil.isEmpty(indexes)) {
            return;
        }
        // 🔴 存 name → columnType 而不是只存名字：多出来的类型是为了在**建 DDL 之前**
        //    拦下 TEXT/JSON 这类不可建键的索引列。名字统一转小写，因为 MySQL 字段名大小写不敏感
        Map<String, String> columnTypes = new HashMap<>();
        CollUtil.emptyIfNull(columns).forEach(column -> columnTypes.put(
                StrUtil.emptyIfNull(column.getColumnName()).toLowerCase(), column.getColumnType()));
        Set<String> indexNames = new HashSet<>();
        for (TableIndexModel index : indexes) {
            String indexName = index.getIndexName();
            if (StrUtil.isBlank(indexName) || SqlSafeGuard.isKeyword(indexName)
                    || !SqlSafeGuard.isIdentifier(indexName)) {
                throw exception(DATA_MODEL_INDEX_NAME_INVALID, indexName);
            }
            if (!indexNames.add(indexName.toLowerCase())) {
                throw exception(DATA_MODEL_INDEX_NAME_DUPLICATE, indexName);
            }
            if (CollUtil.isEmpty(index.getColumnNames())) {
                throw exception(DATA_MODEL_INDEX_COLUMN_EMPTY, indexName);
            }
            for (String columnName : index.getColumnNames()) {
                String lowerName = StrUtil.emptyIfNull(columnName).toLowerCase();
                if (!columnTypes.containsKey(lowerName)) {
                    throw exception(DATA_MODEL_INDEX_COLUMN_INVALID, indexName, columnName);
                }
                String columnType = columnTypes.get(lowerName);
                if (isNonKeyable(DataModelColumnTypeEnum.typeOf(columnType))) {
                    throw exception(DATA_MODEL_COLUMN_NOT_KEYABLE, columnName, columnType);
                }
            }
        }
    }

    // ==================== 删表 ====================

    /**
     * 生成 DROP TABLE 语句
     *
     * 刻意不加 IF EXISTS：表不存在时应当报错让调用方知道，而不是静默成功
     */
    public static String buildDropTable(String tableName) {
        return "DROP TABLE " + SqlSafeGuard.wrap(tableName);
    }

    // ==================== 字段定义 ====================

    /**
     * 生成单个字段定义，形如：`name` varchar(64) NOT NULL COMMENT '名称'
     *
     * ⚠️ 默认值为空串时【不】生成 DEFAULT 子句。界面上那个输入框的占位符就是「无」，
     * 新增字段时它的初值也是空串 —— 空串在这条输入路径上表示「没设默认值」，
     * 而不是「默认值是空字符串」。按后者理解的话，用户每加一个没填默认值的字段，
     * 都会平白多出一个 {@code DEFAULT ''}。
     */
    public static String buildColumnDefinition(TableColumnModel column) {
        DataModelColumnTypeEnum typeEnum = DataModelColumnTypeEnum.typeOf(column.getColumnType());
        if (typeEnum == null) {
            throw exception(DATA_MODEL_COLUMN_TYPE_INVALID, column.getColumnName(), column.getColumnType());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(SqlSafeGuard.wrap(column.getColumnName())).append(" ");
        sb.append(buildTypeDefinition(column, typeEnum));

        // 主键强制 NOT NULL —— MySQL 本身也不允许主键为空，显式写出避免歧义
        boolean notNull = column.isPrimaryKey() || !column.isNullable();
        sb.append(notNull ? " NOT NULL" : " NULL");

        if (column.isPrimaryKey() && column.isAutoIncrement()) {
            sb.append(" AUTO_INCREMENT");
        } else {
            // 自增字段不能有默认值
            appendDefaultValue(sb, column, typeEnum);
            // ON UPDATE CURRENT_TIMESTAMP 只对时间类型有意义，MySQL 也只允许用在 datetime/timestamp 上
            if (column.isOnUpdateCurrentTimestamp()
                    && (typeEnum == DataModelColumnTypeEnum.DATETIME)) {
                sb.append(" ON UPDATE CURRENT_TIMESTAMP");
            }
        }

        if (StrUtil.isNotBlank(column.getColumnComment())) {
            sb.append(" COMMENT ").append(quote(column.getColumnComment()));
        }
        return sb.toString();
    }

    /**
     * 生成类型片段，如 varchar(64)、decimal(10,2)、datetime
     */
    private static String buildTypeDefinition(TableColumnModel column, DataModelColumnTypeEnum typeEnum) {
        String mysqlType = typeEnum.getMysqlType();
        if (Boolean.TRUE.equals(typeEnum.getNeedScale())) {
            int length = column.getLength() != null ? column.getLength() : DEFAULT_DECIMAL_LENGTH;
            int scale = column.getScale() != null ? column.getScale() : DEFAULT_DECIMAL_SCALE;
            if (length <= 0 || scale < 0 || scale >= length) {
                throw exception(DATA_MODEL_COLUMN_LENGTH_INVALID, column.getColumnName());
            }
            return mysqlType + "(" + length + "," + scale + ")";
        }
        if (Boolean.TRUE.equals(typeEnum.getNeedLength())) {
            int length = column.getLength() != null ? column.getLength() : STRING_DEFAULT_LENGTH;
            if (length <= 0 || length > STRING_MAX_LENGTH) {
                throw exception(DATA_MODEL_COLUMN_LENGTH_INVALID, column.getColumnName());
            }
            return mysqlType + "(" + length + ")";
        }
        // BOOLEAN 落成 bit(1)，与框架 BaseDO 里 deleted 字段的做法一致
        if (typeEnum == DataModelColumnTypeEnum.BOOLEAN) {
            return mysqlType + "(1)";
        }
        return mysqlType;
    }

    /**
     * 追加默认值
     *
     * TEXT / LONGTEXT / JSON 在 MySQL 里不支持**字面量**默认值（报 1101
     * 「BLOB, TEXT, GEOMETRY or JSON column 'x' can't have a default value」），直接跳过
     *
     * 🔴 8.0.13 起这三类都收**表达式**形式 {@code DEFAULT ('{}')}（实测于 8.0.46）。
     *    刻意**不**改成发表达式形式，两个理由：
     *    ① 要在 Java 侧判「这个默认值是不是合法 JSON」，而这件事只有 MySQL 判得准
     *       （见 {@link ValueConverter#toJsonText} 的同一条理由），自己判一套就会两边打架；
     *    ② {@link TableMetaReader#convertColumn} 会把 information_schema 的 COLUMN_DEFAULT 原样读回来，
     *       而表达式默认值在那一列里存的是带字符集引导符的**表达式文本**：实测存进去 {@code ('{}')}，
     *       读回来是 {@code _utf8mb4\'{}\'}。它既不是纯字面量、也不是能直接回填的表达式，
     *       再套一层引号发出去就是废 SQL —— 「导入已有表 → 改表」这条路上必炸。
     *       要支持它得先把这层引导符剥掉、并判定文本是否仍是合法表达式，那是另一件事。
     *    ⚠️ 于是这里跟 TEXT 一样是**静默丢弃**。丢弃这件事本身有人管：agent 侧
     *       {@code DataModelCreateValidator#validateSilentRewrites} 会把它作为 warning 报给用户。
     *       UI 侧那个默认值输入框对 TEXT/LONG_TEXT/JSON 一律可填，填了不生效也没提示 ——
     *       这是三类共有的存量缺陷，不是 JSON 新引入的，已单独报给用户。
     */
    private static void appendDefaultValue(StringBuilder sb, TableColumnModel column,
                                           DataModelColumnTypeEnum typeEnum) {
        if (column.getDefaultValue() == null || column.getDefaultValue().isEmpty()) {
            return;
        }
        if (typeEnum == DataModelColumnTypeEnum.TEXT || typeEnum == DataModelColumnTypeEnum.LONG_TEXT
                || typeEnum == DataModelColumnTypeEnum.JSON) {
            return;
        }
        String defaultValue = column.getDefaultValue();
        // CURRENT_TIMESTAMP 是函数不是字面量，不能加引号
        if ("CURRENT_TIMESTAMP".equalsIgnoreCase(defaultValue)) {
            sb.append(" DEFAULT CURRENT_TIMESTAMP");
            return;
        }
        switch (typeEnum) {
            case TINYINT:
            case INT:
            case LONG:
            case DECIMAL:
            case BOOLEAN:
                // 数字与布尔：校验是数字，避免把任意字符串拼进 DDL
                if (!StrUtil.isNumeric(defaultValue.replace("-", "").replace(".", ""))) {
                    throw exception(DATA_MODEL_COLUMN_DEFAULT_INVALID,
                            column.getColumnName(), defaultValue, typeEnum.name());
                }
                sb.append(" DEFAULT ").append(defaultValue);
                break;
            default:
                sb.append(" DEFAULT ").append(quote(defaultValue));
        }
    }

    // ==================== 校验与工具 ====================

    /**
     * 这几类在 MySQL 里**不能建键**（既不能做主键，也不能做索引列）
     *
     * 两种类型报的是不同的错、补救方向也不同（均经真库实测，见
     * {@code DataModelMysqlIntegrationTest#testJsonColumnEndToEnd}）：
     * <ul>
     *   <li>TEXT / LONG_TEXT → 1170「BLOB/TEXT column 'x' used in key specification without a key
     *       length」。带前缀长度（{@code KEY idx (col(255))}）**能**绕开，但 {@link #buildIndexDefinition}
     *       从不生成前缀长度，主键更无从给长度 —— 所以在平台的 DDL 方言里不可做键。</li>
     *   <li>JSON → 3152「JSON column 'x' supports indexing only via generated columns on a specified
     *       JSON path.」。前缀长度**绕不开**（实测 {@code KEY (j(255))} 照样 3152），唯一出路是生成列，
     *       而 TableColumnModel 与 DDL 生成都表达不了它 —— 彻底的不可做键。</li>
     * </ul>
     *
     * 🔴 用 {@code ==} 而不是 Set.contains：{@code typeOf} 对不受支持的类型返回 null
     *    （「导入已有表」会把 blob 这类原样带进来，见 {@link TableMetaReader#reverseType}），
     *    而 {@code Set.of(...)} 的 contains(null) 是**抛 NPE** 不是返回 false。
     *    null 在这里应当是「不归我管」—— 它由 {@link #buildColumnDefinition} 用
     *    DATA_MODEL_COLUMN_TYPE_INVALID 去拒，不该在这一步先炸成一个看不懂的 NPE。
     */
    private static boolean isNonKeyable(DataModelColumnTypeEnum typeEnum) {
        return typeEnum == DataModelColumnTypeEnum.TEXT
                || typeEnum == DataModelColumnTypeEnum.LONG_TEXT
                || typeEnum == DataModelColumnTypeEnum.JSON;
    }

    /**
     * 校验字段列表：非空、有且只有一个主键、字段名不重复、主键可建键
     */
    private static void validateColumns(List<TableColumnModel> columns) {
        if (CollUtil.isEmpty(columns)) {
            throw exception(DATA_MODEL_COLUMN_EMPTY);
        }
        long primaryKeyCount = columns.stream().filter(TableColumnModel::isPrimaryKey).count();
        if (primaryKeyCount != 1) {
            throw exception(DATA_MODEL_PRIMARY_KEY_REQUIRED);
        }
        // MySQL 字段名大小写不敏感，name 与 NAME 会冲突，所以统一转小写比较
        Set<String> names = new HashSet<>();
        for (TableColumnModel column : columns) {
            String lowerName = StrUtil.emptyIfNull(column.getColumnName()).toLowerCase();
            if (!names.add(lowerName)) {
                throw exception(DATA_MODEL_COLUMN_NAME_DUPLICATE, column.getColumnName());
            }
            if (column.isPrimaryKey()
                    && isNonKeyable(DataModelColumnTypeEnum.typeOf(column.getColumnType()))) {
                throw exception(DATA_MODEL_COLUMN_NOT_KEYABLE,
                        column.getColumnName(), column.getColumnType());
            }
        }
    }

    /**
     * 字符串字面量转义
     *
     * 表描述、字段注释、默认值来自用户输入，会进入 SQL 字面量位置。
     * 反斜杠必须先转，否则会把后面转出来的引号再次逃逸。
     */
    private static String quote(String value) {
        String escaped = value
                .replace("\\", "\\\\")
                .replace("'", "\\'");
        return "'" + escaped + "'";
    }

}
