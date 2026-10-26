package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SqlSafeGuard} 的单元测试
 *
 * 这是安全边界代码，重点覆盖注入攻击向量。用纯 JUnit，不需要 Spring 上下文。
 *
 * @author 走进新时代
 */
class SqlSafeGuardTest {

    // ==================== 第一关：标识符合法性 ====================

    @Test
    @DisplayName("合法表名：通过")
    void testCheckTableName_success() {
        assertDoesNotThrow(() -> SqlSafeGuard.checkTableName("biz_order"));
        assertDoesNotThrow(() -> SqlSafeGuard.checkTableName("_tmp"));
        assertDoesNotThrow(() -> SqlSafeGuard.checkTableName("t1"));
        assertDoesNotThrow(() -> SqlSafeGuard.checkTableName("BizOrder"));
    }

    @ParameterizedTest
    @DisplayName("注入向量：一律拒绝")
    @ValueSource(strings = {
            "biz_order; DROP TABLE system_users",   // 语句分隔
            "biz_order`; DROP TABLE x; -- ",        // 反引号逃逸
            "biz_order' OR '1'='1",                 // 引号
            "biz_order/**/UNION/**/SELECT",         // 注释绕过
            "biz_order#comment",                    // MySQL 行注释
            "biz_order-- comment",                  // SQL 行注释
            "biz order",                            // 空格
            "biz.order",                            // 跨库引用
            "biz(order)",                           // 括号
            "1biz_order",                           // 数字开头
            "biz-order",                            // 连字符
            "biz$order",                            // 美元符
            "订单表",                                // 中文
            "",                                     // 空串
            " ",                                    // 空白
    })
    void testCheckTableName_injection(String evil) {
        assertThrows(ServiceException.class, () -> SqlSafeGuard.checkTableName(evil),
                "应当拒绝：" + evil);
    }

    @Test
    @DisplayName("表名为 null：拒绝")
    void testCheckTableName_null() {
        assertThrows(ServiceException.class, () -> SqlSafeGuard.checkTableName(null));
    }

    @Test
    @DisplayName("超长标识符（>64）：拒绝")
    void testCheckTableName_tooLong() {
        String longName = "t".repeat(SqlSafeGuard.IDENTIFIER_MAX_LENGTH + 1);
        assertThrows(ServiceException.class, () -> SqlSafeGuard.checkTableName(longName));
    }

    @ParameterizedTest
    @DisplayName("数据库关键字做表名：拒绝（含大小写混写）")
    @ValueSource(strings = {"select", "SELECT", "Order", "table", "index", "key", "group", "desc"})
    void testCheckTableName_keyword(String keyword) {
        assertThrows(ServiceException.class, () -> SqlSafeGuard.checkTableName(keyword));
    }

    @Test
    @DisplayName("关键字判断：大小写不敏感")
    void testIsKeyword() {
        assertTrue(SqlSafeGuard.isKeyword("select"));
        assertTrue(SqlSafeGuard.isKeyword("SELECT"));
        assertTrue(SqlSafeGuard.isKeyword("SeLeCt"));
        assertFalse(SqlSafeGuard.isKeyword("biz_order"));
    }

    @Test
    @DisplayName("字段名校验：与表名同规则")
    void testCheckColumnName() {
        assertDoesNotThrow(() -> SqlSafeGuard.checkColumnName("order_no"));
        assertThrows(ServiceException.class, () -> SqlSafeGuard.checkColumnName("order no"));
        assertThrows(ServiceException.class, () -> SqlSafeGuard.checkColumnName("`x`"));
        assertThrows(ServiceException.class, () -> SqlSafeGuard.checkColumnName("select"));
    }

    // ==================== 框架表保护 ====================

    @ParameterizedTest
    @DisplayName("系统内置表：识别为框架表")
    @ValueSource(strings = {
            "system_users", "infra_data_model", "bpm_process_instance",
            "act_re_procdef", "member_user", "pay_order", "qrtz_triggers",
            "SYSTEM_USERS",  // 大小写不敏感
    })
    void testIsFrameworkTable_true(String tableName) {
        assertTrue(SqlSafeGuard.isFrameworkTable(tableName), "应识别为框架表：" + tableName);
    }

    @ParameterizedTest
    @DisplayName("业务自建表：不是框架表")
    @ValueSource(strings = {"biz_order", "my_table", "t_customer", "app_config"})
    void testIsFrameworkTable_false(String tableName) {
        assertFalse(SqlSafeGuard.isFrameworkTable(tableName));
    }

    @Test
    @DisplayName("框架表禁止改动")
    void testCheckNotFrameworkTable() {
        assertThrows(ServiceException.class,
                () -> SqlSafeGuard.checkNotFrameworkTable("system_users"));
        assertDoesNotThrow(() -> SqlSafeGuard.checkNotFrameworkTable("biz_order"));
    }

    // ==================== 第二关：受管范围 ====================

    @Test
    @DisplayName("表在受管范围内：通过")
    void testCheckTableManaged_success() {
        Set<String> managed = Set.of("biz_order", "biz_customer");
        assertDoesNotThrow(() -> SqlSafeGuard.checkTableManaged("biz_order", managed));
        // 大小写不敏感
        assertDoesNotThrow(() -> SqlSafeGuard.checkTableManaged("BIZ_ORDER", managed));
    }

    @Test
    @DisplayName("表不在受管范围：拒绝（防越权操作其他业务表）")
    void testCheckTableManaged_notManaged() {
        Set<String> managed = Set.of("biz_order");
        assertThrows(ServiceException.class,
                () -> SqlSafeGuard.checkTableManaged("other_table", managed));
    }

    @Test
    @DisplayName("受管清单为空：一律拒绝")
    void testCheckTableManaged_emptySet() {
        assertThrows(ServiceException.class,
                () -> SqlSafeGuard.checkTableManaged("biz_order", Set.of()));
        assertThrows(ServiceException.class,
                () -> SqlSafeGuard.checkTableManaged("biz_order", null));
    }

    @Test
    @DisplayName("受管校验同时挡住注入串")
    void testCheckTableManaged_injection() {
        Set<String> managed = Set.of("biz_order");
        assertThrows(ServiceException.class,
                () -> SqlSafeGuard.checkTableManaged("biz_order; DROP TABLE x", managed));
    }

    // ==================== 第三关：字段真实性与排序 ====================

    @Test
    @DisplayName("字段存在：通过")
    void testCheckColumnExists_success() {
        Set<String> actual = Set.of("id", "order_no", "create_time");
        assertDoesNotThrow(() -> SqlSafeGuard.checkColumnExists("order_no", actual));
        assertDoesNotThrow(() -> SqlSafeGuard.checkColumnExists("ORDER_NO", actual));
    }

    @Test
    @DisplayName("字段不存在：拒绝")
    void testCheckColumnExists_notExists() {
        Set<String> actual = Set.of("id", "order_no");
        assertThrows(ServiceException.class,
                () -> SqlSafeGuard.checkColumnExists("password", actual));
    }

    @Test
    @DisplayName("排序字段：生成安全片段")
    void testCheckOrderColumn() {
        Set<String> actual = Set.of("id", "create_time");
        assertEquals("`create_time` DESC",
                SqlSafeGuard.checkOrderColumn("create_time", false, actual));
        assertEquals("`id` ASC",
                SqlSafeGuard.checkOrderColumn("id", true, actual));
    }

    @Test
    @DisplayName("排序字段注入：拒绝（ORDER BY 无法参数化，这里是关键防线）")
    void testCheckOrderColumn_injection() {
        Set<String> actual = Set.of("id", "create_time");
        // 经典的 ORDER BY 注入向量
        assertThrows(ServiceException.class,
                () -> SqlSafeGuard.checkOrderColumn("id; DROP TABLE x", true, actual));
        assertThrows(ServiceException.class,
                () -> SqlSafeGuard.checkOrderColumn("(CASE WHEN 1=1 THEN id ELSE 1 END)", true, actual));
        assertThrows(ServiceException.class,
                () -> SqlSafeGuard.checkOrderColumn("id, (SELECT 1)", true, actual));
        // 字段不在清单里也要拒绝
        assertThrows(ServiceException.class,
                () -> SqlSafeGuard.checkOrderColumn("password", true, actual));
    }

    // ==================== 工具方法 ====================

    @Test
    @DisplayName("wrap 只负责包裹，不承担安全职责")
    void testWrap() {
        assertEquals("`biz_order`", SqlSafeGuard.wrap("biz_order"));
    }

}
