package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.enlev.ainb.module.infra.enums.datamodel.DataModelColumnTypeEnum;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableIndexModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 表结构读取器
 *
 * 从 information_schema 实时读取表结构。刻意不缓存、不落元数据表 ——
 * 表结构的唯一事实来源是数据库本身，缓存只会带来「元数据与真实结构不一致」的问题。
 *
 * 这也是 {@link SqlSafeGuard} 第三关（字段真实性校验）的数据来源。
 *
 * @author 走进新时代
 */
@Slf4j
public class TableMetaReader {

    private final JdbcTemplate jdbcTemplate;

    public TableMetaReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 判断表是否存在
     *
     * 用 information_schema 而不是 SHOW TABLES LIKE，因为后者的 LIKE 会把
     * 表名里的 _ 当通配符，`biz_order` 会误匹配到 `bizXorder`。
     */
    public boolean existsTable(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Integer.class, tableName);
        return count != null && count > 0;
    }

    /**
     * 读取表描述，表不存在时返回 null
     */
    public String getTableComment(String tableName) {
        List<String> result = jdbcTemplate.queryForList(
                "SELECT TABLE_COMMENT FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                String.class, tableName);
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * 读取表的字段列表，按表中原始顺序
     */
    public List<TableColumnModel> getColumns(String tableName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, COLUMN_COMMENT, DATA_TYPE, COLUMN_TYPE, "
                        + "       CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, "
                        + "       IS_NULLABLE, COLUMN_KEY, EXTRA, COLUMN_DEFAULT "
                        + "FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                        + "ORDER BY ORDINAL_POSITION",
                tableName);
        return rows.stream().map(this::convertColumn).collect(Collectors.toList());
    }

    /**
     * 读取表的字段名集合，用于安全校验
     *
     * 用 LinkedHashSet 保留顺序，便于前端展示时与表结构一致
     */
    public Set<String> getColumnNames(String tableName) {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                        + "ORDER BY ORDINAL_POSITION",
                String.class, tableName);
        return new LinkedHashSet<>(names);
    }

    /**
     * 读取表的二级索引列表（不含主键）
     *
     * 主键单独由 {@link TableColumnModel#isPrimaryKey()} 表达，这里只返回普通/唯一索引。
     * STATISTICS 里一个复合索引会有多行（每列一行），按 SEQ_IN_INDEX 还原列顺序，
     * 用 LinkedHashMap 分组保留索引出现顺序。
     */
    public List<TableIndexModel> getIndexes(String tableName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME, SEQ_IN_INDEX "
                        + "FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME <> 'PRIMARY' "
                        + "ORDER BY INDEX_NAME, SEQ_IN_INDEX",
                tableName);
        Map<String, TableIndexModel> grouped = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String indexName = str(row.get("INDEX_NAME"));
            TableIndexModel index = grouped.computeIfAbsent(indexName, name -> new TableIndexModel()
                    .setIndexName(name)
                    .setIndexType(intOf(row.get("NON_UNIQUE")) == 0
                            ? TableIndexModel.TYPE_UNIQUE : TableIndexModel.TYPE_NORMAL)
                    .setColumnNames(new java.util.ArrayList<>()));
            index.getColumnNames().add(str(row.get("COLUMN_NAME")));
        }
        return new java.util.ArrayList<>(grouped.values());
    }

    /**
     * 统计表的数据行数
     *
     * 用于删表/删字段前的风险提示。注意这里是精确 COUNT，大表上会慢；
     * 之所以不用 information_schema.TABLES.TABLE_ROWS（近似值），是因为
     * 「有没有数据」这个判断必须准确 —— 近似值在小表上可能返回 0。
     */
    public long countRows(String tableName) {
        // tableName 已由调用方通过 SqlSafeGuard 校验，此处包裹反引号
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + SqlSafeGuard.wrap(tableName), Long.class);
        return count == null ? 0L : count;
    }

    /**
     * 查询当前库的表名列表（模糊匹配），用于「导入已有表」
     */
    public List<Map<String, Object>> getTableList(String tableNameLike, String tableCommentLike) {
        StringBuilder sql = new StringBuilder(
                "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'");
        List<Object> args = new java.util.ArrayList<>();
        // 值走占位符，不拼字符串
        if (cn.hutool.core.util.StrUtil.isNotBlank(tableNameLike)) {
            sql.append(" AND TABLE_NAME LIKE ?");
            args.add("%" + tableNameLike + "%");
        }
        if (cn.hutool.core.util.StrUtil.isNotBlank(tableCommentLike)) {
            sql.append(" AND TABLE_COMMENT LIKE ?");
            args.add("%" + tableCommentLike + "%");
        }
        sql.append(" ORDER BY TABLE_NAME");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /**
     * 把 information_schema 的一行转成内部模型
     *
     * 关键在于把 MySQL 的物理类型反向映射回我们受控的类型枚举。映射不了的类型
     * （blob、enum 等）保留原始 DATA_TYPE，前端展示为只读 —— 这样
     * 「导入已有表」不会因为存在不支持的类型而整表失败。
     */
    private TableColumnModel convertColumn(Map<String, Object> row) {
        String dataType = str(row.get("DATA_TYPE"));
        String columnType = str(row.get("COLUMN_TYPE"));

        TableColumnModel column = new TableColumnModel()
                .setColumnName(str(row.get("COLUMN_NAME")))
                .setColumnComment(str(row.get("COLUMN_COMMENT")))
                .setNullable("YES".equalsIgnoreCase(str(row.get("IS_NULLABLE"))))
                .setPrimaryKey("PRI".equalsIgnoreCase(str(row.get("COLUMN_KEY"))))
                .setAutoIncrement(str(row.get("EXTRA")).toLowerCase().contains("auto_increment"))
                // EXTRA 里形如 "on update CURRENT_TIMESTAMP"，与 auto_increment 共用这一列
                .setOnUpdateCurrentTimestamp(
                        str(row.get("EXTRA")).toLowerCase().contains("on update current_timestamp"))
                .setDefaultValue(row.get("COLUMN_DEFAULT") == null
                        ? null : str(row.get("COLUMN_DEFAULT")));

        DataModelColumnTypeEnum typeEnum = reverseType(dataType, columnType);
        if (typeEnum == null) {
            // 不受支持的类型：保留原始类型名，前端置为只读
            column.setColumnType(dataType.toUpperCase());
            return column;
        }
        column.setColumnType(typeEnum.getType());
        if (Boolean.TRUE.equals(typeEnum.getNeedLength())) {
            if (typeEnum == DataModelColumnTypeEnum.DECIMAL) {
                column.setLength(intOrNull(row.get("NUMERIC_PRECISION")));
                column.setScale(intOrNull(row.get("NUMERIC_SCALE")));
            } else {
                column.setLength(intOrNull(row.get("CHARACTER_MAXIMUM_LENGTH")));
            }
        }
        return column;
    }

    /**
     * MySQL 物理类型 → 受控类型枚举，映射不了返回 null
     */
    private DataModelColumnTypeEnum reverseType(String dataType, String columnType) {
        return switch (dataType.toLowerCase()) {
            case "varchar", "char" -> DataModelColumnTypeEnum.STRING;
            case "text", "tinytext", "mediumtext" -> DataModelColumnTypeEnum.TEXT;
            case "longtext" -> DataModelColumnTypeEnum.LONG_TEXT;
            // json 落成受控的 JSON 枚举，而不是走下面 default 那条「保留原始类型名 + 前端只读」。
            // 🔴 代价是导入后这一列从只读变成可编辑，它的 COLUMN_DEFAULT 会跟着被读进 defaultValue：
            //    表达式默认值在 information_schema 里存的是**带字符集引导符的表达式文本**，
            //    实测存进去 DEFAULT ('{}')、读回来是 _utf8mb4\'{}\'（8.0.46）。
            //    所以 MysqlDdlBuilder#appendDefaultValue 必须跳过 JSON，否则改表时会把它当字面量
            //    再套一层引号发出去，成了废 SQL —— 那两处是一对，改一处要改另一处。
            // ⚠️ 跳过带来一个已知缺口（TEXT/LONG_TEXT 同样有，但 json 是这次才从只读变可编辑、
            //    因而**新暴露**出来的）：TableDiffer#isChanged 比的是 defaultValue 字符串，
            //    改这一列的**任何别的**属性（注释、可空）都会生成一条 MODIFY COLUMN，
            //    而 MODIFY 走的 buildColumnDefinition 不发 DEFAULT —— 导入表原有的默认值就被静默丢了。
            //    没在这里修：正确做法要先决定「表达式默认值在模型里怎么表示」，是另一件事，已报给用户。
            case "json" -> DataModelColumnTypeEnum.JSON;
            case "int", "integer", "smallint", "mediumint" -> DataModelColumnTypeEnum.INT;
            case "bigint" -> DataModelColumnTypeEnum.LONG;
            case "decimal", "numeric" -> DataModelColumnTypeEnum.DECIMAL;
            // tinyint(1) 与 bit 都当布尔；其余宽度的 tinyint 映射到 TINYINT，忠实反映物理类型
            // （R9 之前没有 TINYINT 枚举，只能退化成 INT，界面上看不出这列其实是 tinyint）
            case "bit" -> DataModelColumnTypeEnum.BOOLEAN;
            case "tinyint" -> columnType.toLowerCase().startsWith("tinyint(1)")
                    ? DataModelColumnTypeEnum.BOOLEAN : DataModelColumnTypeEnum.TINYINT;
            case "date" -> DataModelColumnTypeEnum.DATE;
            case "datetime", "timestamp" -> DataModelColumnTypeEnum.DATETIME;
            default -> null;
        };
    }

    private String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private Integer intOrNull(Object value) {
        return value == null ? null : Integer.valueOf(value.toString());
    }

    private int intOf(Object value) {
        return value == null ? 0 : Integer.parseInt(value.toString());
    }

}
