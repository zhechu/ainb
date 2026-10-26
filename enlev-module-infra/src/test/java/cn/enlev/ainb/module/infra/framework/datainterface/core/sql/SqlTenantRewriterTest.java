package cn.enlev.ainb.module.infra.framework.datainterface.core.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SqlTenantRewriter} 的单元测试
 *
 * 这是整个数据接口功能的安全命门：改写漏了一处，就是跨租户数据泄漏。
 * 所以逐场景断言「租户条件真的注入了」，而不只是「没报错」。
 *
 * @author 走进新时代
 */
class SqlTenantRewriterTest {

    /** 模拟：biz_ 前缀的表有 tenant_id，其余没有 */
    private final SqlTenantRewriter rewriter =
            new SqlTenantRewriter(table -> table.toLowerCase().startsWith("biz_"));

    private String rewrite(String sql) {
        SqlTenantRewriter.RewriteResult result = rewriter.rewrite(sql, 1L);
        assertTrue(result.isSuccess(), "改写应成功，实际失败：" + result.getErrorMsg());
        return result.getSql();
    }

    // ==================== 基础场景 ====================

    @Test
    @DisplayName("带 WHERE：租户条件与原条件相与")
    void testWithWhere() {
        String sql = rewrite("SELECT id FROM biz_order WHERE status = 1");
        assertTrue(sql.contains("tenant_id = 1"), "应注入租户条件：" + sql);
        assertTrue(sql.contains("status = 1"), "原条件应保留：" + sql);
        assertTrue(sql.toUpperCase().contains("AND"), "应以 AND 连接：" + sql);
    }

    @Test
    @DisplayName("不带 WHERE：自动补上 WHERE")
    void testWithoutWhere() {
        String sql = rewrite("SELECT id FROM biz_order");
        assertTrue(sql.toUpperCase().contains("WHERE"), "应补 WHERE：" + sql);
        assertTrue(sql.contains("tenant_id = 1"), "应注入租户条件：" + sql);
    }

    @Test
    @DisplayName("⚠️ 用户自己写了 tenant_id 试图窥探别人：注入条件依然生效")
    void testUserForgedTenantId() {
        String sql = rewrite("SELECT id FROM biz_order WHERE tenant_id = 999");
        // 两个条件相与，结果必然为空
        assertTrue(sql.contains("tenant_id = 999"), "用户条件保留：" + sql);
        assertTrue(sql.contains("tenant_id = 1"), "注入条件也在：" + sql);
        assertTrue(sql.toUpperCase().contains("AND"), "相与后查不到数据：" + sql);
    }

    @Test
    @DisplayName("表无 tenant_id 字段：不注入")
    void testNonTenantTable() {
        String sql = rewrite("SELECT id FROM system_dict_data WHERE status = 0");
        assertFalse(sql.contains("tenant_id"), "无租户字段的表不该注入：" + sql);
    }

    @Test
    @DisplayName("带别名：条件用别名限定，避免多表歧义")
    void testWithAlias() {
        String sql = rewrite("SELECT o.id FROM biz_order o WHERE o.status = 1");
        assertTrue(sql.contains("o.tenant_id = 1"), "应用别名限定：" + sql);
    }

    // ==================== JOIN ====================

    @Test
    @DisplayName("JOIN：每个含租户字段的表都注入")
    void testJoin() {
        String sql = rewrite("SELECT a.id FROM biz_order a "
                + "LEFT JOIN biz_order_item b ON a.id = b.order_id WHERE a.status = 1");
        assertTrue(sql.contains("a.tenant_id = 1"), "主表应注入：" + sql);
        assertTrue(sql.contains("b.tenant_id = 1"), "join 表也应注入：" + sql);
    }

    @Test
    @DisplayName("JOIN 混合：只给有租户字段的表注入")
    void testJoinMixed() {
        String sql = rewrite("SELECT a.id FROM biz_order a "
                + "LEFT JOIN system_dict_data d ON a.status = d.value");
        assertTrue(sql.contains("a.tenant_id = 1"), "业务表应注入：" + sql);
        // 字典表不该被注入
        assertFalse(sql.contains("d.tenant_id"), "字典表不该注入：" + sql);
    }

    @Test
    @DisplayName("三表 JOIN")
    void testThreeTableJoin() {
        String sql = rewrite("SELECT a.id FROM biz_order a "
                + "JOIN biz_order_item b ON a.id = b.order_id "
                + "JOIN biz_customer c ON a.customer_id = c.id");
        assertTrue(sql.contains("a.tenant_id = 1"), sql);
        assertTrue(sql.contains("b.tenant_id = 1"), sql);
        assertTrue(sql.contains("c.tenant_id = 1"), sql);
    }

    // ==================== 子查询 ====================

    @Test
    @DisplayName("WHERE 子查询：子查询里的表也注入")
    void testSubQueryInWhere() {
        String sql = rewrite("SELECT id FROM biz_order "
                + "WHERE id IN (SELECT order_id FROM biz_order_item)");
        // 外层与子查询各一处
        int count = countOccurrences(sql, "tenant_id = 1");
        assertEquals(2, count, "外层与子查询各应注入一次，实际 " + count + "：" + sql);
    }

    @Test
    @DisplayName("FROM 子查询")
    void testSubQueryInFrom() {
        String sql = rewrite("SELECT t.id FROM (SELECT id FROM biz_order) t");
        assertTrue(sql.contains("tenant_id = 1"), "子查询应注入：" + sql);
    }

    @Test
    @DisplayName("EXISTS 子查询")
    void testExistsSubQuery() {
        String sql = rewrite("SELECT id FROM biz_order o "
                + "WHERE EXISTS (SELECT 1 FROM biz_order_item i WHERE i.order_id = o.id)");
        int count = countOccurrences(sql, "tenant_id = 1");
        assertEquals(2, count, "外层与 EXISTS 内各一次，实际 " + count + "：" + sql);
    }

    // ==================== UNION ====================

    @Test
    @DisplayName("UNION：每个分支各自注入")
    void testUnion() {
        String sql = rewrite("SELECT id FROM biz_order UNION SELECT id FROM biz_order_history");
        int count = countOccurrences(sql, "tenant_id = 1");
        assertEquals(2, count, "两个分支各一次，实际 " + count + "：" + sql);
    }

    // ==================== 写操作 ====================

    @Test
    @DisplayName("⚠️ UPDATE：注入租户条件，防跨租户改数据")
    void testUpdate() {
        String sql = rewrite("UPDATE biz_order SET status = 2 WHERE id = 100");
        assertTrue(sql.contains("tenant_id = 1"), "UPDATE 必须注入：" + sql);
        assertTrue(sql.contains("id = 100"), "原条件保留：" + sql);
    }

    @Test
    @DisplayName("⚠️ UPDATE 不带 WHERE：也要注入（否则全表更新跨租户）")
    void testUpdateWithoutWhere() {
        String sql = rewrite("UPDATE biz_order SET status = 2");
        assertTrue(sql.contains("tenant_id = 1"), "无 WHERE 的 UPDATE 更要注入：" + sql);
    }

    @Test
    @DisplayName("⚠️ DELETE：注入租户条件")
    void testDelete() {
        String sql = rewrite("DELETE FROM biz_order WHERE id = 100");
        assertTrue(sql.contains("tenant_id = 1"), "DELETE 必须注入：" + sql);
    }

    @Test
    @DisplayName("⚠️ DELETE 不带 WHERE：也要注入")
    void testDeleteWithoutWhere() {
        String sql = rewrite("DELETE FROM biz_order");
        assertTrue(sql.contains("tenant_id = 1"), sql);
    }

    // ==================== 拒绝场景 ====================

    @Test
    @DisplayName("解析失败：拒绝执行，不放行")
    void testUnparsable() {
        SqlTenantRewriter.RewriteResult result = rewriter.rewrite("这不是 SQL", 1L);
        assertFalse(result.isSuccess(), "无法解析应拒绝");
        assertTrue(result.getErrorMsg().contains("无法解析"), result.getErrorMsg());
    }

    @Test
    @DisplayName("无租户上下文：拒绝执行")
    void testNoTenantContext() {
        SqlTenantRewriter.RewriteResult result = rewriter.rewrite("SELECT id FROM biz_order", null);
        assertFalse(result.isSuccess(), "无租户上下文应拒绝");
    }

    // ==================== 记录被改写的表 ====================

    @Test
    @DisplayName("返回被注入的表名，供调用方审计")
    void testRewrittenTables() {
        SqlTenantRewriter.RewriteResult result = rewriter.rewrite(
                "SELECT a.id FROM biz_order a JOIN biz_order_item b ON a.id = b.order_id", 1L);
        assertTrue(result.isSuccess());
        Set<String> tables = result.getRewrittenTables();
        assertTrue(tables.contains("biz_order"), tables.toString());
        assertTrue(tables.contains("biz_order_item"), tables.toString());
    }

    private int countOccurrences(String text, String target) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }

    // ==================== 容易漏的表达式位置（回归用） ====================
    // EXISTS 分支最初就漏了，测试抓出来后才补上。这组用例锁死其余同类位置。

    @Test
    @DisplayName("NOT EXISTS 子查询")
    void testNotExistsSubQuery() {
        String sql = rewrite("SELECT id FROM biz_order o "
                + "WHERE NOT EXISTS (SELECT 1 FROM biz_order_item i WHERE i.order_id = o.id)");
        assertEquals(2, countOccurrences(sql, "tenant_id = 1"),
                "外层与 NOT EXISTS 内各一次：" + sql);
    }

    @Test
    @DisplayName("比较运算符右侧的标量子查询")
    void testScalarSubQuery() {
        String sql = rewrite("SELECT id FROM biz_order "
                + "WHERE amount = (SELECT MAX(amount) FROM biz_order_history)");
        assertEquals(2, countOccurrences(sql, "tenant_id = 1"),
                "外层与标量子查询各一次：" + sql);
    }

    @Test
    @DisplayName("NOT IN 子查询")
    void testNotInSubQuery() {
        String sql = rewrite("SELECT id FROM biz_order "
                + "WHERE id NOT IN (SELECT order_id FROM biz_order_item)");
        assertEquals(2, countOccurrences(sql, "tenant_id = 1"),
                "外层与 NOT IN 子查询各一次：" + sql);
    }

    @Test
    @DisplayName("OR 分支里的子查询")
    void testSubQueryInOr() {
        String sql = rewrite("SELECT id FROM biz_order "
                + "WHERE status = 1 OR id IN (SELECT order_id FROM biz_order_item)");
        assertEquals(2, countOccurrences(sql, "tenant_id = 1"),
                "OR 分支里的子查询也要注入：" + sql);
    }

    @Test
    @DisplayName("嵌套两层子查询")
    void testNestedSubQuery() {
        String sql = rewrite("SELECT id FROM biz_order WHERE id IN ("
                + "SELECT order_id FROM biz_order_item WHERE product_id IN ("
                + "SELECT id FROM biz_product))");
        assertEquals(3, countOccurrences(sql, "tenant_id = 1"),
                "三层各注入一次：" + sql);
    }

    // ==================== INSERT（无 WHERE，靠补字段列） ====================

    @Test
    @DisplayName("⚠️ INSERT：自动补 tenant_id 字段与值")
    void testInsert() {
        String sql = rewrite("INSERT INTO biz_order (order_no, amount) VALUES ('A', 100)");
        assertTrue(sql.contains("tenant_id"), "应补上租户字段：" + sql);
        assertTrue(sql.contains("1)") || sql.contains(", 1"), "应补上租户值：" + sql);
    }

    @Test
    @DisplayName("⚠️ INSERT 用户自己写了 tenant_id：强制改成当前租户，不许往别的租户塞数据")
    void testInsert_userForgedTenantId() {
        String sql = rewrite("INSERT INTO biz_order (order_no, tenant_id) VALUES ('A', 999)");
        assertFalse(sql.contains("999"), "用户填的租户值应被覆盖：" + sql);
        assertTrue(sql.contains("1"), "应改成当前租户：" + sql);
        // 不该重复添加字段
        assertEquals(1, countOccurrences(sql, "tenant_id"), "不该重复加字段：" + sql);
    }

    @Test
    @DisplayName("INSERT 到无租户字段的表：不改写")
    void testInsert_nonTenantTable() {
        String sql = rewrite("INSERT INTO system_dict_data (label) VALUES ('x')");
        assertFalse(sql.contains("tenant_id"), "无租户字段的表不该注入：" + sql);
    }

    @Test
    @DisplayName("INSERT 不写字段列表：拒绝（无法安全注入租户列）")
    void testInsert_withoutColumns() {
        SqlTenantRewriter.RewriteResult result =
                rewriter.rewrite("INSERT INTO biz_order VALUES (1, 'A')", 1L);
        assertFalse(result.isSuccess(), "应拒绝：不写字段列表无法注入租户列");
    }

    @Test
    @DisplayName("INSERT ... SELECT：SELECT 部分注入租户条件")
    void testInsertSelect() {
        String sql = rewrite("INSERT INTO biz_order_history (order_no) "
                + "SELECT order_no FROM biz_order WHERE status = 2");
        assertTrue(sql.contains("tenant_id = 1"), "SELECT 部分应注入：" + sql);
    }

    // ==================== 多语句（业务需求） ====================

    @Test
    @DisplayName("多语句：每条各自注入租户条件")
    void testMultiStatement() {
        SqlTenantRewriter.RewriteResult result = rewriter.rewrite(
                "UPDATE biz_order SET status = 2 WHERE id = 1; "
                        + "DELETE FROM biz_order_item WHERE order_id = 1", 1L);
        assertTrue(result.isSuccess(), result.getErrorMsg());
        assertEquals(2, result.getStatementSqlList().size(), "应拆成两条");
        result.getStatementSqlList().forEach(sql ->
                assertTrue(sql.contains("tenant_id = 1"), "每条都要注入：" + sql));
    }

    @Test
    @DisplayName("多语句：INSERT + INSERT 的主子表场景")
    void testMultiStatement_masterDetail() {
        SqlTenantRewriter.RewriteResult result = rewriter.rewrite(
                "INSERT INTO biz_order (order_no) VALUES ('A'); "
                        + "INSERT INTO biz_order_item (order_id, qty) VALUES (1, 2)", 1L);
        assertTrue(result.isSuccess(), result.getErrorMsg());
        assertEquals(2, result.getStatementSqlList().size());
        result.getStatementSqlList().forEach(sql ->
                assertTrue(sql.contains("tenant_id"), "每条都要补租户字段：" + sql));
    }

    @Test
    @DisplayName("多语句：任一条解析失败则整体拒绝")
    void testMultiStatement_oneFails() {
        SqlTenantRewriter.RewriteResult result = rewriter.rewrite(
                "SELECT id FROM biz_order; 这不是SQL", 1L);
        assertFalse(result.isSuccess(), "一条失败应整体拒绝，不留隔离缺口");
    }

    @Test
    @DisplayName("多语句：拆分后的 SQL 列表供逐条执行（JDBC 不允许一次执行多条）")
    void testMultiStatement_splitForExecution() {
        SqlTenantRewriter.RewriteResult result = rewriter.rewrite(
                "SELECT id FROM biz_order; SELECT id FROM biz_order_item", 1L);
        assertTrue(result.isSuccess());
        assertEquals(2, result.getStatementSqlList().size());
        // 拆出的每条都不该再含分号
        result.getStatementSqlList().forEach(sql ->
                assertFalse(sql.contains(";"), "拆分后不该含分号：" + sql));
    }

}
