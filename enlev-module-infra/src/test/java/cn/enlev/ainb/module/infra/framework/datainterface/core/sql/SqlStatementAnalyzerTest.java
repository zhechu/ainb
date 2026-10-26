package cn.enlev.ainb.module.infra.framework.datainterface.core.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SqlStatementAnalyzer} 的单元测试
 *
 * 语句类型判断错了，会导致「会改数据的接口」被当成只读接口放过，
 * 绕过高危权限与二次确认。所以逐类型断言。
 *
 * @author 走进新时代
 */
class SqlStatementAnalyzerTest {

    private SqlStatementAnalyzer.AnalyzeResult analyze(String sql) {
        SqlStatementAnalyzer.AnalyzeResult result = SqlStatementAnalyzer.analyze(sql);
        assertTrue(result.isSuccess(), "应解析成功：" + result.getErrorMsg());
        return result;
    }

    /** 单语句场景下取第一条的信息 */
    private SqlStatementAnalyzer.StatementInfo first(String sql) {
        return analyze(sql).getStatements().get(0);
    }

    @ParameterizedTest
    @DisplayName("语句类型识别")
    @CsvSource({
            "SELECT id FROM biz_order,                     SELECT, false",
            "INSERT INTO biz_order (id) VALUES (1),        INSERT, true",
            "UPDATE biz_order SET status = 1,              UPDATE, true",
            "DELETE FROM biz_order WHERE id = 1,           DELETE, true",
            "TRUNCATE TABLE biz_order,                     DDL,    true",
    })
    void testStatementType(String sql, String expectedType, boolean expectedWrite) {
        SqlStatementAnalyzer.StatementInfo info = first(sql);
        assertEquals(expectedType, info.getStatementType(), "语句类型：" + sql);
        assertEquals(expectedWrite, info.isWriteOperation(), "写操作标记：" + sql);
    }

    @ParameterizedTest
    @DisplayName("DDL 语句识别为高危")
    @ValueSource(strings = {
            "CREATE TABLE zz_t (id bigint)",
            "DROP TABLE zz_t",
            "ALTER TABLE zz_t ADD COLUMN name varchar(10)",
    })
    void testDdl(String sql) {
        SqlStatementAnalyzer.StatementInfo info = first(sql);
        assertEquals("DDL", info.getStatementType(), sql);
        assertTrue(info.isWriteOperation(), "DDL 必须标记为写操作：" + sql);
    }

    // ==================== 多语句（业务需求） ====================

    @Test
    @DisplayName("多语句：逐条分析，顺序保持")
    void testMultiStatement() {
        SqlStatementAnalyzer.AnalyzeResult result = analyze(
                "INSERT INTO biz_order (order_no) VALUES ('A'); "
                        + "INSERT INTO biz_order_item (order_id) VALUES (1)");
        assertTrue(result.isMultiStatement());
        assertEquals(2, result.getStatements().size());
        assertEquals("INSERT", result.getStatements().get(0).getStatementType());
        assertEquals("INSERT", result.getStatements().get(1).getStatementType());
    }

    @Test
    @DisplayName("多语句：混合类型，整体标记为含写操作")
    void testMultiStatement_mixed() {
        SqlStatementAnalyzer.AnalyzeResult result = analyze(
                "SELECT id FROM biz_order; UPDATE biz_order SET status = 2 WHERE id = 1");
        assertTrue(result.hasWriteOperation(), "含 UPDATE 应标记为写操作");
        assertEquals("UPDATE", result.getOverallType(), "整体类型取首条写操作");
    }

    @Test
    @DisplayName("多语句：全是查询则整体为 SELECT")
    void testMultiStatement_allSelect() {
        SqlStatementAnalyzer.AnalyzeResult result = analyze(
                "SELECT id FROM biz_order; SELECT id FROM biz_order_item");
        assertFalse(result.hasWriteOperation());
        assertEquals("SELECT", result.getOverallType());
    }

    @Test
    @DisplayName("多语句：表名合集包含所有语句涉及的表")
    void testMultiStatement_allTableNames() {
        SqlStatementAnalyzer.AnalyzeResult result = analyze(
                "UPDATE biz_order SET status = 1; DELETE FROM biz_order_item WHERE order_id = 1");
        Set<String> tables = result.getAllTableNames();
        assertTrue(containsIgnoreCase(tables, "biz_order"), "实际：" + tables);
        assertTrue(containsIgnoreCase(tables, "biz_order_item"), "实际：" + tables);
    }

    @Test
    @DisplayName("末尾分号不影响解析")
    void testTrailingSemicolon() {
        SqlStatementAnalyzer.AnalyzeResult result = analyze("SELECT id FROM biz_order;");
        assertEquals(1, result.getStatements().size(), "末尾分号不该多出一条空语句");
        assertEquals("SELECT", result.getOverallType());
    }

    @Test
    @DisplayName("字符串字面量里的分号不该被当成语句分隔")
    void testSemicolonInLiteral() {
        SqlStatementAnalyzer.AnalyzeResult result =
                analyze("SELECT id FROM biz_order WHERE remark = 'a;b'");
        assertEquals(1, result.getStatements().size(), "字面量里的分号不该拆句");
    }

    @Test
    @DisplayName("非法 SQL 拒绝")
    void testInvalidSql() {
        SqlStatementAnalyzer.AnalyzeResult result = SqlStatementAnalyzer.analyze("这不是 SQL");
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMsg().contains("解析失败"), result.getErrorMsg());
    }

    @Test
    @DisplayName("空 SQL 拒绝")
    void testEmptySql() {
        assertFalse(SqlStatementAnalyzer.analyze("").isSuccess());
        assertFalse(SqlStatementAnalyzer.analyze("   ").isSuccess());
    }

    // ==================== 表名提取 ====================

    @Test
    @DisplayName("表名提取：简单查询")
    void testExtractTable() {
        assertTrue(containsIgnoreCase(first("SELECT id FROM biz_order").getTableNames(), "biz_order"));
    }

    @Test
    @DisplayName("表名提取：JOIN 的表都要提取到（框架表保护依赖这个）")
    void testExtractTable_join() {
        Set<String> tables = first(
                "SELECT a.id FROM biz_order a LEFT JOIN system_users b ON a.user_id = b.id")
                .getTableNames();
        assertTrue(containsIgnoreCase(tables, "biz_order"), "实际：" + tables);
        assertTrue(containsIgnoreCase(tables, "system_users"),
                "join 的表也要提取到，否则框架表保护会漏：" + tables);
    }

    @Test
    @DisplayName("表名提取：子查询里的表")
    void testExtractTable_subQuery() {
        Set<String> tables = first(
                "SELECT id FROM biz_order WHERE id IN (SELECT order_id FROM biz_order_item)")
                .getTableNames();
        assertTrue(containsIgnoreCase(tables, "biz_order_item"), "实际：" + tables);
    }

    @Test
    @DisplayName("表名提取：解析器对反引号与库名限定的原始返回形态")
    void testExtractTable_identifierForm() {
        // 这条不是为了断言某种「正确」形态，而是把解析器的实际行为钉住 ——
        // 系统表写保护依赖表名前缀比对，解析器返回 `sys_grant` 还是 sys_grant
        // 直接决定要不要在比对前剥引号。升级 mybatis-plus（连带 JSqlParser）时这条会报警。
        Set<String> quoted = first("UPDATE `sys_grant` SET effect = 'ALLOW'").getTableNames();
        Set<String> qualified = first("UPDATE mydb.sys_grant SET effect = 'ALLOW'").getTableNames();

        assertEquals(Set.of("`sys_grant`"), quoted,
                "若此处变成 sys_grant，说明解析器自己剥了引号，实际：" + quoted);
        assertEquals(Set.of("mydb.sys_grant"), qualified,
                "库名限定是否保留，实际：" + qualified);
    }

    @Test
    @DisplayName("表名提取：写操作的目标表")
    void testExtractTable_write() {        assertTrue(containsIgnoreCase(
                first("UPDATE system_users SET nickname = 'x'").getTableNames(), "system_users"));
        assertTrue(containsIgnoreCase(
                first("DELETE FROM system_users WHERE id = 1").getTableNames(), "system_users"));
    }

    private boolean containsIgnoreCase(Set<String> set, String target) {
        return set.stream().anyMatch(item -> item.equalsIgnoreCase(target));
    }

}
