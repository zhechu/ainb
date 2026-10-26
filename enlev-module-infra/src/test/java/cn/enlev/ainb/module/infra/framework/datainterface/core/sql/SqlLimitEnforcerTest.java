package cn.enlev.ainb.module.infra.framework.datainterface.core.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SqlLimitEnforcer} 的单元测试
 *
 * @author 走进新时代
 */
class SqlLimitEnforcerTest {

    @Test
    @DisplayName("未开启分页且无 LIMIT：自动加 LIMIT 10000")
    void testAutoInjectLimit() {
        String sql = "SELECT * FROM user WHERE status = 1";
        String result = SqlLimitEnforcer.enforce(sql, false);
        assertTrue(result.contains("LIMIT"), "应自动加 LIMIT: " + result);
        assertTrue(result.contains("10000"), "应加 LIMIT 10000: " + result);
    }

    @Test
    @DisplayName("未开启分页但已有 LIMIT：保持原样")
    void testKeepExistingLimit() {
        String sql = "SELECT * FROM user WHERE status = 1 LIMIT 50";
        String result = SqlLimitEnforcer.enforce(sql, false);
        assertEquals(sql, result, "已有 LIMIT 不应再加");
    }

    @Test
    @DisplayName("开启分页且有 LIMIT：通过")
    void testPagedWithLimit() {
        String sql = "SELECT * FROM user LIMIT 20 OFFSET 40";
        assertDoesNotThrow(() -> SqlLimitEnforcer.enforce(sql, true));
    }

    @Test
    @DisplayName("⚠️ 开启分页但无 LIMIT：报错")
    void testPagedWithoutLimit() {
        String sql = "SELECT * FROM user WHERE status = 1";
        Exception ex = assertThrows(Exception.class, () ->
                SqlLimitEnforcer.enforce(sql, true));
        assertTrue(ex.getMessage().contains("LIMIT"), "错误信息应提示缺少 LIMIT");
    }

    @Test
    @DisplayName("子查询里有 LIMIT，最外层没有：未开分页时仍加 LIMIT")
    void testSubqueryLimit() {
        String sql = "SELECT * FROM (SELECT * FROM user LIMIT 10) t";
        String result = SqlLimitEnforcer.enforce(sql, false);
        assertTrue(result.contains("LIMIT"), "最外层应加 LIMIT: " + result);
    }

    @Test
    @DisplayName("复杂 SQL：GROUP BY + ORDER BY，未开分页自动加 LIMIT")
    void testComplexSql() {
        String sql = "SELECT dept_id, COUNT(*) FROM user WHERE status = 1 GROUP BY dept_id ORDER BY COUNT(*) DESC";
        String result = SqlLimitEnforcer.enforce(sql, false);
        assertTrue(result.contains("LIMIT"), "应自动加 LIMIT: " + result);
    }

}
