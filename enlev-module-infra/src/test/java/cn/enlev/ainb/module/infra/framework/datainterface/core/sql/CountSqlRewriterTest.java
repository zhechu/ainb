package cn.enlev.ainb.module.infra.framework.datainterface.core.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CountSqlRewriter} 的单元测试
 *
 * @author 走进新时代
 */
class CountSqlRewriterTest {

    @Test
    @DisplayName("简单查询：改写成功")
    void testSimpleQuery() {
        String sql = "SELECT id, name FROM user WHERE status = 1";
        String result = CountSqlRewriter.rewrite(sql);
        assertTrue(result.contains("COUNT(*)"), "应包含 COUNT(*): " + result);
        assertTrue(result.contains("total"), "应有 total 别名: " + result);
    }

    @Test
    @DisplayName("带 ORDER BY 的查询：改写后不影响（包在子查询里）")
    void testWithOrderBy() {
        String sql = "SELECT id, name FROM user ORDER BY id DESC";
        String result = CountSqlRewriter.rewrite(sql);
        assertTrue(result.contains("COUNT(*)"), result);
        // 包一层后 ORDER BY 在子查询里不影响 COUNT
    }

    @Test
    @DisplayName("带 LIMIT 的查询：改写后不影响")
    void testWithLimit() {
        String sql = "SELECT id, name FROM user LIMIT 10";
        String result = CountSqlRewriter.rewrite(sql);
        assertTrue(result.contains("COUNT(*)"), result);
    }

    @Test
    @DisplayName("带 JOIN 的查询：改写成功")
    void testWithJoin() {
        String sql = "SELECT u.id, d.name FROM user u LEFT JOIN dept d ON u.dept_id = d.id WHERE u.status = 1";
        String result = CountSqlRewriter.rewrite(sql);
        assertTrue(result.contains("COUNT(*)"), result);
    }

    @Test
    @DisplayName("带 GROUP BY 的查询：包一层避免多行")
    void testWithGroupBy() {
        String sql = "SELECT dept_id, COUNT(*) FROM user GROUP BY dept_id";
        String result = CountSqlRewriter.rewrite(sql);
        assertTrue(result.contains("COUNT(*)"), result);
        assertTrue(result.contains("temp_count_table"), "应包一层: " + result);
    }

    @Test
    @DisplayName("空 SQL：返回兜底 COUNT")
    void testEmptySql() {
        String result = CountSqlRewriter.rewrite(null);
        assertEquals("SELECT COUNT(*) AS total", result);

        result = CountSqlRewriter.rewrite("");
        assertEquals("SELECT COUNT(*) AS total", result);
    }

}
