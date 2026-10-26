package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 动态 SQL 执行器
 *
 * ⚠️⚠️ 本类走 JdbcTemplate，**完全绕开 MyBatis 的拦截器链**。这意味着框架自带的
 * 多租户隔离、数据权限、逻辑删除、审计字段自动填充**全部失效**，必须在这里手工补齐：
 *
 * | 框架能力 | MyBatis 路径 | 本类的处理 |
 * |---------|-------------|-----------|
 * | 多租户 | TenantLineInnerInterceptor | {@link #appendTenantCondition} 手工拼 tenant_id |
 * | 逻辑删除 | @TableLogic | {@link #appendDeletedCondition} 手工拼 deleted = 0 |
 * | 审计字段 | DefaultDBFieldHandler | {@link #fillCreateFields} 手工填充 |
 *
 * 漏掉任何一条都会造成数据泄漏或脏数据。这是整个动态 CRUD 最容易出事的地方。
 *
 * 安全约定：
 * - 表名、字段名一律先过 {@link SqlSafeGuard}（无法参数化的位置）
 * - 所有**值**一律用 ? 占位符，绝不拼进 SQL 字符串
 *
 * @author 走进新时代
 */
@Slf4j
public class DynamicSqlExecutor {

    /**
     * 约定的系统字段名。表里有这些字段时才会自动处理
     */
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_TENANT_ID = "tenant_id";
    public static final String COLUMN_DELETED = "deleted";
    public static final String COLUMN_CREATOR = "creator";
    public static final String COLUMN_CREATE_TIME = "create_time";
    public static final String COLUMN_UPDATER = "updater";
    public static final String COLUMN_UPDATE_TIME = "update_time";

    private final JdbcTemplate jdbcTemplate;

    public DynamicSqlExecutor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 查询 ====================

    /**
     * 分页查询
     *
     * @param tableName     表名（已校验）
     * @param actualColumns 表的真实字段集合，用于校验条件字段与排序字段
     * @param conditions    查询条件
     * @param orderBy       排序片段，已由 SqlSafeGuard#checkOrderColumn 生成；为空则按 id 倒序
     * @param offset        偏移
     * @param limit         每页条数
     */
    public List<Map<String, Object>> selectPage(String tableName, Set<String> actualColumns,
                                                List<QueryCondition> conditions, String orderBy,
                                                int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(tableName, actualColumns, conditions, args);

        StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(SqlSafeGuard.wrap(tableName)).append(where);
        // 排序字段已在外层白名单校验，此处直接拼；为空给个稳定的默认排序
        if (StrUtil.isNotBlank(orderBy)) {
            sql.append(" ORDER BY ").append(orderBy);
        } else if (actualColumns.contains(COLUMN_ID)) {
            sql.append(" ORDER BY ").append(SqlSafeGuard.wrap(COLUMN_ID)).append(" DESC");
        }
        sql.append(" LIMIT ?, ?");
        args.add(offset);
        args.add(limit);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /**
     * 统计总数，与 {@link #selectPage} 使用相同的 WHERE 条件
     */
    public long selectCount(String tableName, Set<String> actualColumns,
                            List<QueryCondition> conditions) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(tableName, actualColumns, conditions, args);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + SqlSafeGuard.wrap(tableName) + where,
                Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    /**
     * 按主键查询单条
     */
    public Map<String, Object> selectById(String tableName, Set<String> actualColumns, Object id) {
        List<Object> args = new ArrayList<>();
        args.add(id);
        StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(SqlSafeGuard.wrap(tableName))
                .append(" WHERE ").append(SqlSafeGuard.wrap(COLUMN_ID)).append(" = ?");
        appendTenantCondition(sql, actualColumns, args);
        appendDeletedCondition(sql, actualColumns);
        List<Map<String, Object>> list = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        return list.isEmpty() ? null : list.get(0);
    }

    // ==================== 插入 ====================

    /**
     * 插入一行，返回自增主键
     *
     * @param data 字段值，key 为字段名（会逐个校验真实性）
     */
    public Object insert(String tableName, Set<String> actualColumns, Map<String, Object> data) {
        Map<String, Object> row = new LinkedHashMap<>(data);
        // 主键交给数据库自增，前端传了也忽略
        row.remove(COLUMN_ID);
        fillCreateFields(row, actualColumns);

        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            SqlSafeGuard.checkColumnExists(entry.getKey(), actualColumns);
            columns.add(SqlSafeGuard.wrap(entry.getKey()));
            placeholders.add("?");
            args.add(entry.getValue());
        }
        String sql = "INSERT INTO " + SqlSafeGuard.wrap(tableName)
                + " (" + String.join(", ", columns) + ") VALUES (" + String.join(", ", placeholders) + ")";
        // 用 JDBC 标准的 getGeneratedKeys 取自增主键。
        // 注意：不能用 "SELECT LAST_INSERT_ID()" 单独查一次 —— 它是【连接级】的，
        // JdbcTemplate 每次调用都从连接池另取连接，第二次查很可能拿到 0。
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    // ==================== 更新 ====================

    /**
     * 按主键更新
     *
     * @return 影响行数
     */
    public int updateById(String tableName, Set<String> actualColumns,
                          Object id, Map<String, Object> data) {
        Map<String, Object> row = new LinkedHashMap<>(data);
        // 主键与创建信息不允许通过更新改动
        row.remove(COLUMN_ID);
        row.remove(COLUMN_CREATOR);
        row.remove(COLUMN_CREATE_TIME);
        row.remove(COLUMN_TENANT_ID);
        fillUpdateFields(row, actualColumns);

        List<String> setList = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            SqlSafeGuard.checkColumnExists(entry.getKey(), actualColumns);
            setList.add(SqlSafeGuard.wrap(entry.getKey()) + " = ?");
            args.add(entry.getValue());
        }
        if (setList.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(SqlSafeGuard.wrap(tableName))
                .append(" SET ").append(String.join(", ", setList))
                .append(" WHERE ").append(SqlSafeGuard.wrap(COLUMN_ID)).append(" = ?");
        args.add(id);
        // 租户与逻辑删除条件放在最后，保证不会跨租户改到别人的数据
        appendTenantCondition(sql, actualColumns, args);
        appendDeletedCondition(sql, actualColumns);
        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    // ==================== 删除 ====================

    /**
     * 按主键删除
     *
     * 表里有 deleted 字段则做逻辑删除，否则物理删除 —— 与框架的 @TableLogic 行为对齐
     */
    public int deleteById(String tableName, Set<String> actualColumns, Object id) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        if (actualColumns.contains(COLUMN_DELETED)) {
            sql.append("UPDATE ").append(SqlSafeGuard.wrap(tableName))
                    .append(" SET ").append(SqlSafeGuard.wrap(COLUMN_DELETED)).append(" = 1");
            // 顺手记下删除人，便于追溯
            if (actualColumns.contains(COLUMN_UPDATER)) {
                sql.append(", ").append(SqlSafeGuard.wrap(COLUMN_UPDATER)).append(" = ?");
                args.add(currentUserId());
            }
            if (actualColumns.contains(COLUMN_UPDATE_TIME)) {
                sql.append(", ").append(SqlSafeGuard.wrap(COLUMN_UPDATE_TIME)).append(" = ?");
                args.add(LocalDateTime.now());
            }
        } else {
            sql.append("DELETE FROM ").append(SqlSafeGuard.wrap(tableName));
        }
        sql.append(" WHERE ").append(SqlSafeGuard.wrap(COLUMN_ID)).append(" = ?");
        args.add(id);
        appendTenantCondition(sql, actualColumns, args);
        appendDeletedCondition(sql, actualColumns);
        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    /**
     * 按外键批量删除，用于主子表场景删除子表数据
     */
    public int deleteByColumn(String tableName, Set<String> actualColumns,
                              String columnName, Object value) {
        SqlSafeGuard.checkColumnExists(columnName, actualColumns);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        if (actualColumns.contains(COLUMN_DELETED)) {
            sql.append("UPDATE ").append(SqlSafeGuard.wrap(tableName))
                    .append(" SET ").append(SqlSafeGuard.wrap(COLUMN_DELETED)).append(" = 1");
        } else {
            sql.append("DELETE FROM ").append(SqlSafeGuard.wrap(tableName));
        }
        sql.append(" WHERE ").append(SqlSafeGuard.wrap(columnName)).append(" = ?");
        args.add(value);
        appendTenantCondition(sql, actualColumns, args);
        appendDeletedCondition(sql, actualColumns);
        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    /**
     * 按外键查询，用于主子表场景查子表数据
     */
    public List<Map<String, Object>> selectListByColumn(String tableName, Set<String> actualColumns,
                                                        String columnName, Object value) {
        SqlSafeGuard.checkColumnExists(columnName, actualColumns);
        List<Object> args = new ArrayList<>();
        args.add(value);
        StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(SqlSafeGuard.wrap(tableName))
                .append(" WHERE ").append(SqlSafeGuard.wrap(columnName)).append(" = ?");
        appendTenantCondition(sql, actualColumns, args);
        appendDeletedCondition(sql, actualColumns);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    // ==================== WHERE 构建 ====================

    /**
     * 构建 WHERE 子句
     *
     * 字段名逐个过白名单校验，值全部走占位符。
     */
    private String buildWhere(String tableName, Set<String> actualColumns,
                              List<QueryCondition> conditions, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (CollUtil.isNotEmpty(conditions)) {
            for (QueryCondition condition : conditions) {
                appendCondition(sql, actualColumns, condition, args);
            }
        }
        appendTenantCondition(sql, actualColumns, args);
        appendDeletedCondition(sql, actualColumns);
        return sql.toString();
    }

    /**
     * 拼接单个查询条件
     *
     * 操作符来自受控枚举，不接受前端自由传入的 SQL 片段
     */
    private void appendCondition(StringBuilder sql, Set<String> actualColumns,
                                 QueryCondition condition, List<Object> args) {
        SqlSafeGuard.checkColumnExists(condition.getColumnName(), actualColumns);
        String column = SqlSafeGuard.wrap(condition.getColumnName());
        Object value = condition.getValue();
        switch (condition.getOperator()) {
            case EQ -> {
                sql.append(" AND ").append(column).append(" = ?");
                args.add(value);
            }
            case NE -> {
                sql.append(" AND ").append(column).append(" != ?");
                args.add(value);
            }
            case LIKE -> {
                sql.append(" AND ").append(column).append(" LIKE ?");
                // 通配符加在参数值里，不拼进 SQL
                args.add("%" + value + "%");
            }
            case GT -> {
                sql.append(" AND ").append(column).append(" > ?");
                args.add(value);
            }
            case GE -> {
                sql.append(" AND ").append(column).append(" >= ?");
                args.add(value);
            }
            case LT -> {
                sql.append(" AND ").append(column).append(" < ?");
                args.add(value);
            }
            case LE -> {
                sql.append(" AND ").append(column).append(" <= ?");
                args.add(value);
            }
            case BETWEEN -> {
                // 值必须是两元素集合，否则忽略该条件
                if (value instanceof Collection<?> collection && collection.size() == 2) {
                    Iterator<?> iterator = collection.iterator();
                    sql.append(" AND ").append(column).append(" BETWEEN ? AND ?");
                    args.add(iterator.next());
                    args.add(iterator.next());
                }
            }
            case IN -> {
                if (value instanceof Collection<?> collection && !collection.isEmpty()) {
                    // 占位符个数由集合长度决定，值仍走参数
                    String placeholders = String.join(", ", Collections.nCopies(collection.size(), "?"));
                    sql.append(" AND ").append(column).append(" IN (").append(placeholders).append(")");
                    args.addAll(collection);
                }
            }
            case IS_NULL -> sql.append(" AND ").append(column).append(" IS NULL");
            case IS_NOT_NULL -> sql.append(" AND ").append(column).append(" IS NOT NULL");
        }
    }

    // ==================== 框架能力的手工补齐 ====================

    /**
     * 手工拼接租户条件
     *
     * ⚠️ 这是防跨租户数据泄漏的唯一防线。表有 tenant_id 字段、且当前未设置忽略租户时才拼。
     */
    private void appendTenantCondition(StringBuilder sql, Set<String> actualColumns, List<Object> args) {
        if (!actualColumns.contains(COLUMN_TENANT_ID)) {
            return;
        }
        if (TenantContextHolder.isIgnore()) {
            return;
        }
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            return;
        }
        sql.append(" AND ").append(SqlSafeGuard.wrap(COLUMN_TENANT_ID)).append(" = ?");
        args.add(tenantId);
    }

    /**
     * 手工拼接逻辑删除条件
     */
    private void appendDeletedCondition(StringBuilder sql, Set<String> actualColumns) {
        if (actualColumns.contains(COLUMN_DELETED)) {
            sql.append(" AND ").append(SqlSafeGuard.wrap(COLUMN_DELETED)).append(" = 0");
        }
    }

    /**
     * 手工填充创建时的审计字段与租户编号
     */
    private void fillCreateFields(Map<String, Object> row, Set<String> actualColumns) {
        LocalDateTime now = LocalDateTime.now();
        String userId = currentUserId();
        if (actualColumns.contains(COLUMN_CREATOR) && !row.containsKey(COLUMN_CREATOR)) {
            row.put(COLUMN_CREATOR, userId);
        }
        if (actualColumns.contains(COLUMN_CREATE_TIME)) {
            row.put(COLUMN_CREATE_TIME, now);
        }
        if (actualColumns.contains(COLUMN_UPDATER)) {
            row.put(COLUMN_UPDATER, userId);
        }
        if (actualColumns.contains(COLUMN_UPDATE_TIME)) {
            row.put(COLUMN_UPDATE_TIME, now);
        }
        if (actualColumns.contains(COLUMN_DELETED) && !row.containsKey(COLUMN_DELETED)) {
            row.put(COLUMN_DELETED, 0);
        }
        // 租户编号强制取当前上下文，不接受前端传入
        if (actualColumns.contains(COLUMN_TENANT_ID)) {
            Long tenantId = TenantContextHolder.getTenantId();
            if (tenantId != null) {
                row.put(COLUMN_TENANT_ID, tenantId);
            }
        }
    }

    /**
     * 手工填充更新时的审计字段
     */
    private void fillUpdateFields(Map<String, Object> row, Set<String> actualColumns) {
        if (actualColumns.contains(COLUMN_UPDATER)) {
            row.put(COLUMN_UPDATER, currentUserId());
        }
        if (actualColumns.contains(COLUMN_UPDATE_TIME)) {
            row.put(COLUMN_UPDATE_TIME, LocalDateTime.now());
        }
    }

    private String currentUserId() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return userId == null ? null : userId.toString();
    }

    // ==================== 查询条件模型 ====================

    /**
     * 查询操作符
     *
     * 做成枚举而非让前端传 SQL 片段 —— 这是防注入的关键设计
     */
    public enum Operator {
        EQ, NE, LIKE, GT, GE, LT, LE, BETWEEN, IN, IS_NULL, IS_NOT_NULL
    }

    /**
     * 查询条件
     */
    public static class QueryCondition {

        private final String columnName;
        private final Operator operator;
        private final Object value;

        public QueryCondition(String columnName, Operator operator, Object value) {
            this.columnName = columnName;
            this.operator = operator;
            this.value = value;
        }

        public String getColumnName() {
            return columnName;
        }

        public Operator getOperator() {
            return operator;
        }

        public Object getValue() {
            return value;
        }

    }

}
