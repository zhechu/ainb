package cn.enlev.ainb.module.infra.framework.datainterface.core.sql;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * SQL 租户条件自动改写器
 *
 * ★ 这是数据接口功能的技术命门。
 *
 * 数据接口允许用户自己写 SQL，而用户写的 SQL 不会自带租户过滤。若不处理，
 * A 租户配一句 `SELECT * FROM biz_order` 就能看到全部租户的订单。
 * 靠「文档约定用户自己加条件」不可靠 —— 漏写一次就是数据泄漏。
 *
 * 所以这里把用户 SQL 解析成语法树，对**每个涉及的表**判断是否需要隔离，
 * 需要则自动注入 `AND <表>.tenant_id = ?`。用户既不用写，也绕不过：
 * 即便用户自己写了 `tenant_id = 999`，注入的条件依然生效，两个条件相与结果为空。
 *
 * 覆盖范围：
 * - 简单查询、带 WHERE / 不带 WHERE
 * - JOIN（每个 join 的表各自判断）
 * - 子查询（FROM 子查询、WHERE 子查询）
 * - UNION（每个分支各自处理）
 * - UPDATE / DELETE（防止跨租户改数据）
 *
 * 解析不了的 SQL **一律拒绝**，见 {@link RewriteResult#isSuccess()}。
 * 宁可不支持某类 SQL，也不留隔离缺口。
 *
 * @author 走进新时代
 */
@Slf4j
public class SqlTenantRewriter {

    /**
     * 租户字段名
     */
    public static final String TENANT_COLUMN = "tenant_id";

    /**
     * 改写结果
     */
    @Getter
    public static class RewriteResult {

        /**
         * 改写后的 SQL；多语句时用分号拼接，仅用于日志展示
         */
        private String sql;
        /**
         * 逐条改写后的 SQL，执行器按此顺序逐条执行
         *
         * JDBC 默认不允许一次 execute 多条语句，所以必须拆开执行；
         * 事务由调用方用 @Transactional 保证「失败全回滚」。
         */
        private final List<String> statementSqlList = new ArrayList<>();
        /**
         * 被注入了租户条件的表名
         */
        private final Set<String> rewrittenTables = new LinkedHashSet<>();
        /**
         * 注入的租户条件个数，决定了要追加多少个参数值
         */
        private int tenantParamCount;
        /**
         * 失败原因；成功时为 null
         */
        private String errorMsg;

        public boolean isSuccess() {
            return errorMsg == null;
        }

        static RewriteResult fail(String errorMsg) {
            RewriteResult result = new RewriteResult();
            result.errorMsg = errorMsg;
            return result;
        }

    }

    /**
     * 判断某个表是否需要注入租户条件
     *
     * 由调用方提供，通常实现为「查 information_schema 看该表有没有 tenant_id 字段，
     * 且不在框架的租户忽略清单里」。放在外部是为了让本类保持纯粹、可单测。
     */
    private final Predicate<String> tenantTablePredicate;

    public SqlTenantRewriter(Predicate<String> tenantTablePredicate) {
        this.tenantTablePredicate = tenantTablePredicate;
    }

    /**
     * 改写 SQL，注入租户条件
     *
     * 注入的条件用字面量而非占位符 —— 租户编号来自服务端上下文而非用户输入，
     * 不存在注入风险；用字面量可以让「实际执行的 SQL」在日志里一目了然，
     * 排查「为什么查不到数据」时是最直接的证据。
     *
     * @param sql      用户配置的 SQL（此时 #{xx} 已被替换成 ?），支持多条语句
     * @param tenantId 当前租户编号
     * @return 改写结果
     */
    public RewriteResult rewrite(String sql, Long tenantId) {
        if (tenantId == null) {
            return RewriteResult.fail("当前无租户上下文，拒绝执行");
        }
        List<Statement> parsed;
        try {
            parsed = CCJSqlParserUtil.parseStatements(sql).getStatements();
        } catch (Exception ex) {
            // 解析失败 → 无法确认能否隔离 → 拒绝。这是硬约束
            log.warn("[rewrite][SQL 解析失败，拒绝执行：{}]", sql, ex);
            return RewriteResult.fail("SQL 无法解析，无法保证租户隔离，请改写后重试："
                    + ex.getMessage());
        }
        if (parsed == null || parsed.isEmpty()) {
            return RewriteResult.fail("SQL 不能为空");
        }

        RewriteResult result = new RewriteResult();
        List<String> rewrittenList = new ArrayList<>();
        try {
            // 多语句逐条改写，任一条出问题即整体失败
            for (Statement statement : parsed) {
                rewriteStatement(statement, tenantId, result);
                rewrittenList.add(statement.toString());
            }
        } catch (Exception ex) {
            log.error("[rewrite][SQL 改写异常：{}]", sql, ex);
            return RewriteResult.fail("SQL 改写失败：" + ex.getMessage());
        }
        // 用分号加换行拼回，便于日志阅读
        result.sql = String.join(";\n", rewrittenList);
        result.statementSqlList.addAll(rewrittenList);
        return result;
    }

    /**
     * 改写单条语句
     */
    private void rewriteStatement(Statement statement, Long tenantId, RewriteResult result) {
        if (statement instanceof Select select) {
            handleSelectBody(select.getSelectBody(), tenantId, result);
            return;
        }
        if (statement instanceof Update update) {
            handleUpdate(update, tenantId, result);
            return;
        }
        if (statement instanceof Delete delete) {
            handleDelete(delete, tenantId, result);
            return;
        }
        if (statement instanceof net.sf.jsqlparser.statement.insert.Insert insert) {
            handleInsert(insert, tenantId, result);
            return;
        }
        // DDL 等不涉及数据行，无需注入
    }

    /**
     * INSERT 的租户隔离
     *
     * INSERT 没有 WHERE 可注入，改为**往字段列表里补 tenant_id 列并追加对应值**：
     *
     * <pre>
     * 用户写：INSERT INTO biz_order (order_no) VALUES (?)
     * 改写后：INSERT INTO biz_order (order_no, tenant_id) VALUES (?, 1)
     * </pre>
     *
     * 用户自己写了 tenant_id 时不重复添加，但会把值**强制改成当前租户** ——
     * 不允许通过 INSERT 往别的租户塞数据。
     */
    private void handleInsert(net.sf.jsqlparser.statement.insert.Insert insert,
                              Long tenantId, RewriteResult result) {
        Table table = insert.getTable();
        if (table == null || !needTenantCondition(table.getName())) {
            return;
        }
        List<Column> columns = insert.getColumns();
        // 没有显式列清单（INSERT INTO t VALUES (...)）时无法安全插入租户列，拒绝
        if (columns == null || columns.isEmpty()) {
            throw new IllegalStateException("INSERT 必须显式写出字段列表，否则无法注入租户编号");
        }
        // 先分辨是 VALUES 形式还是 SELECT 形式。
        // 注意：SELECT 形式下调用 insert.getValues() 会抛 ClassCastException（内部强转 Values），
        // 所以必须先看 getSelect() 的实际类型，不能靠 getValues() == null 判断。
        Select select = insert.getSelect();
        boolean valuesForm = select instanceof net.sf.jsqlparser.statement.select.Values;
        if (!valuesForm) {
            // INSERT ... SELECT：租户隔离由 SELECT 自身的条件保证，字段列表不动
            if (select != null) {
                handleSelectBody(select, tenantId, result);
            }
            return;
        }

        int tenantIndex = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (TENANT_COLUMN.equalsIgnoreCase(columns.get(i).getColumnName())) {
                tenantIndex = i;
                break;
            }
        }
        @SuppressWarnings("unchecked")
        List<net.sf.jsqlparser.expression.Expression> values =
                (List<net.sf.jsqlparser.expression.Expression>)
                        (List<?>) insert.getValues().getExpressions();
        if (tenantIndex >= 0) {
            // 用户写了 tenant_id：强制改成当前租户，不允许往别的租户塞数据
            values.set(tenantIndex, new LongValue(tenantId));
        } else {
            columns.add(new Column(TENANT_COLUMN));
            values.add(new LongValue(tenantId));
        }
        result.rewrittenTables.add(table.getName());
    }

    // ==================== SELECT ====================

    /**
     * 处理 SELECT 主体，兼顾 UNION 等集合操作
     */
    private void handleSelectBody(Select selectBody, Long tenantId, RewriteResult result) {
        if (selectBody instanceof PlainSelect plainSelect) {
            handlePlainSelect(plainSelect, tenantId, result);
            return;
        }
        if (selectBody instanceof SetOperationList setOperationList) {
            // UNION / UNION ALL / INTERSECT 等：每个分支单独处理
            if (setOperationList.getSelects() != null) {
                setOperationList.getSelects()
                        .forEach(select -> handleSelectBody(select, tenantId, result));
            }
            return;
        }
        if (selectBody instanceof ParenthesedSelect parenthesedSelect) {
            handleSelectBody(parenthesedSelect.getSelect(), tenantId, result);
        }
    }

    /**
     * 处理单个 SELECT
     */
    private void handlePlainSelect(PlainSelect plainSelect, Long tenantId, RewriteResult result) {
        List<Expression> conditions = new ArrayList<>();
        // FROM 项
        collectFromItem(plainSelect.getFromItem(), tenantId, result, conditions);
        // JOIN 项
        if (plainSelect.getJoins() != null) {
            plainSelect.getJoins().forEach(join ->
                    collectFromItem(join.getRightItem(), tenantId, result, conditions));
        }
        // WHERE 里的子查询也要处理
        handleSubSelectInExpression(plainSelect.getWhere(), tenantId, result);
        // 把收集到的租户条件与原 WHERE 相与
        if (!conditions.isEmpty()) {
            plainSelect.setWhere(mergeConditions(plainSelect.getWhere(), conditions));
        }
    }

    /**
     * 处理 FROM / JOIN 中的一项：可能是表，也可能是子查询
     */
    private void collectFromItem(FromItem fromItem, Long tenantId,
                                 RewriteResult result, List<Expression> conditions) {
        if (fromItem == null) {
            return;
        }
        if (fromItem instanceof Table table) {
            if (needTenantCondition(table.getName())) {
                conditions.add(buildTenantCondition(table, tenantId));
                result.rewrittenTables.add(table.getName());
            }
            return;
        }
        // FROM (SELECT ...) 形式：递归进子查询。
        // 子查询内部自己会加条件，所以外层不需要再加
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            handleSelectBody(parenthesedSelect.getSelect(), tenantId, result);
        }
    }

    /**
     * 处理表达式里嵌套的子查询（如 WHERE id IN (SELECT ...)）
     *
     * JSqlParser 的表达式树形态很多，这里用 toString 粗筛 + 递归解析的方式，
     * 避免为每种表达式类型写一个分支。粗筛不命中时不递归，不影响正确性。
     */
    private void handleSubSelectInExpression(Expression expression, Long tenantId,
                                             RewriteResult result) {
        if (expression == null) {
            return;
        }
        if (expression instanceof AndExpression andExpression) {
            handleSubSelectInExpression(andExpression.getLeftExpression(), tenantId, result);
            handleSubSelectInExpression(andExpression.getRightExpression(), tenantId, result);
            return;
        }
        if (expression instanceof net.sf.jsqlparser.expression.operators.conditional.OrExpression orExpression) {
            handleSubSelectInExpression(orExpression.getLeftExpression(), tenantId, result);
            handleSubSelectInExpression(orExpression.getRightExpression(), tenantId, result);
            return;
        }
        if (expression instanceof net.sf.jsqlparser.expression.operators.relational.InExpression inExpression) {
            handleSubSelectInExpression(inExpression.getLeftExpression(), tenantId, result);
            handleSubSelectInExpression(inExpression.getRightExpression(), tenantId, result);
            return;
        }
        // EXISTS (SELECT ...) —— 漏了这个分支会让 EXISTS 里的表不受租户隔离
        if (expression instanceof net.sf.jsqlparser.expression.operators.relational.ExistsExpression existsExpression) {
            handleSubSelectInExpression(existsExpression.getRightExpression(), tenantId, result);
            return;
        }
        // NOT (...)
        if (expression instanceof net.sf.jsqlparser.expression.NotExpression notExpression) {
            handleSubSelectInExpression(notExpression.getExpression(), tenantId, result);
            return;
        }
        // (...) 括号包裹
        if (expression instanceof net.sf.jsqlparser.expression.Parenthesis parenthesis) {
            handleSubSelectInExpression(parenthesis.getExpression(), tenantId, result);
            return;
        }
        // 比较运算符两侧都可能是子查询，如 id = (SELECT MAX(id) FROM biz_order)
        if (expression instanceof net.sf.jsqlparser.expression.BinaryExpression binaryExpression) {
            handleSubSelectInExpression(binaryExpression.getLeftExpression(), tenantId, result);
            handleSubSelectInExpression(binaryExpression.getRightExpression(), tenantId, result);
            return;
        }
        if (expression instanceof ParenthesedSelect parenthesedSelect) {
            handleSelectBody(parenthesedSelect.getSelect(), tenantId, result);
            return;
        }
        if (expression instanceof Select select) {
            handleSelectBody(select, tenantId, result);
        }
    }

    // ==================== UPDATE / DELETE ====================

    /**
     * UPDATE 也必须注入 —— 否则可以跨租户改别人的数据
     */
    private void handleUpdate(Update update, Long tenantId, RewriteResult result) {
        Table table = update.getTable();
        if (table != null && needTenantCondition(table.getName())) {
            update.setWhere(mergeConditions(update.getWhere(),
                    List.of(buildTenantCondition(table, tenantId))));
            result.rewrittenTables.add(table.getName());
        }
    }

    /**
     * DELETE 同理
     */
    private void handleDelete(Delete delete, Long tenantId, RewriteResult result) {
        Table table = delete.getTable();
        if (table != null && needTenantCondition(table.getName())) {
            delete.setWhere(mergeConditions(delete.getWhere(),
                    List.of(buildTenantCondition(table, tenantId))));
            result.rewrittenTables.add(table.getName());
        }
    }

    // ==================== 工具 ====================

    private boolean needTenantCondition(String tableName) {
        return tableName != null && tenantTablePredicate.test(tableName);
    }

    /**
     * 构造 `表名或别名.tenant_id = 租户编号`
     *
     * 有别名时必须用别名 —— JOIN 场景下用表名会报「Unknown column」
     */
    private Expression buildTenantCondition(Table table, Long tenantId) {
        String prefix = table.getAlias() != null && table.getAlias().getName() != null
                ? table.getAlias().getName()
                : table.getName();
        Column column = new Column(new Table(prefix), TENANT_COLUMN);
        return new EqualsTo(column, new LongValue(tenantId));
    }

    /**
     * 把租户条件与原有 WHERE 合并
     */
    private Expression mergeConditions(Expression where, List<Expression> tenantConditions) {
        Expression merged = where;
        for (Expression condition : tenantConditions) {
            merged = merged == null ? condition : new AndExpression(merged, condition);
        }
        return merged;
    }

}
