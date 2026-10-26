package cn.enlev.ainb.module.infra.framework.datainterface.core.sql;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SqlPolicyValidator} 的单元测试
 *
 * 数据接口禁止 DDL、禁止对框架表做写操作。这两条是安全边界，
 * 漏了就意味着「配个接口就能把系统表删掉」。
 *
 * @author 走进新时代
 */
class SqlPolicyValidatorTest {

    private void validate(String sql) {
        SqlPolicyValidator.validate(SqlStatementAnalyzer.analyze(sql));
    }

    // ==================== 禁止 DDL ====================

    @ParameterizedTest
    @DisplayName("⚠️ DDL 一律拒绝：改表结构应走数据模型")
    @ValueSource(strings = {
            "CREATE TABLE zz_t (id bigint)",
            "DROP TABLE zz_t",
            "ALTER TABLE zz_t ADD COLUMN name varchar(10)",
            "TRUNCATE TABLE zz_t",
    })
    void testDdlDenied(String sql) {
        ServiceException ex = assertThrows(ServiceException.class, () -> validate(sql),
                "应拒绝 DDL：" + sql);
        assertTrue(ex.getMessage().contains("DDL"), "错误信息应说明原因：" + ex.getMessage());
    }

    @Test
    @DisplayName("⚠️ 多语句里混一句 DDL：整体拒绝")
    void testDdlInMultiStatement() {
        assertThrows(ServiceException.class, () ->
                        validate("INSERT INTO biz_order (id) VALUES (1); DROP TABLE biz_order"),
                "多语句里混 DDL 必须拒绝 —— MySQL 的 DDL 无法回滚，事务保证会失效");
    }

    @Test
    @DisplayName("DDL 藏在最后一句也要拦住")
    void testDdlAtEnd() {
        assertThrows(ServiceException.class, () ->
                validate("SELECT id FROM biz_order; SELECT 1; TRUNCATE TABLE biz_order"));
    }

    // ==================== 允许的语句 ====================

    @ParameterizedTest
    @DisplayName("增删改查都允许（业务需求）")
    @ValueSource(strings = {
            "SELECT id FROM biz_order",
            "INSERT INTO biz_order (order_no) VALUES ('A')",
            "UPDATE biz_order SET status = 2 WHERE id = 1",
            "DELETE FROM biz_order WHERE id = 1",
    })
    void testDmlAllowed(String sql) {
        assertDoesNotThrow(() -> validate(sql), "应允许：" + sql);
    }

    @Test
    @DisplayName("多语句的增删改组合：允许")
    void testMultiStatementDmlAllowed() {
        assertDoesNotThrow(() -> validate(
                "INSERT INTO biz_order (order_no) VALUES ('A'); "
                        + "INSERT INTO biz_order_item (order_id, qty) VALUES (1, 2)"),
                "主子表插入是核心业务场景，必须允许");
    }

    // ==================== 框架表写保护 ====================

    @ParameterizedTest
    @DisplayName("⚠️ 对框架表的写操作一律拒绝")
    @ValueSource(strings = {
            "UPDATE system_users SET nickname = 'x'",
            "DELETE FROM system_users WHERE id = 1",
            "INSERT INTO system_users (username) VALUES ('x')",
            "UPDATE infra_data_interface SET name = 'x'",
            "DELETE FROM bpm_process_instance WHERE id = 1",
    })
    void testFrameworkTableWriteDenied(String sql) {
        ServiceException ex = assertThrows(ServiceException.class, () -> validate(sql),
                "应拒绝对框架表的写操作：" + sql);
        assertTrue(ex.getMessage().contains("系统内置表"), ex.getMessage());
    }

    @ParameterizedTest
    @DisplayName("⚠️ 原名单漏掉的系统表前缀：sys_ / ai_ / act_ / flw_ / qrtz_")
    @ValueSource(strings = {
            // sys_grant 是 S8 权限体系的授权表，写它等于自助提权
            "INSERT INTO sys_grant (subject_type, resource_id, effect) VALUES ('USER', 1, 'ALLOW')",
            "UPDATE sys_resource SET name = 'x'",
            "DELETE FROM sys_rule WHERE id = 1",
            "UPDATE ai_api_key SET api_key = 'x'",          // 大模型密钥
            "DELETE FROM act_ru_task WHERE id_ = '1'",       // Flowable 引擎
            "UPDATE flw_ru_batch SET rev_ = 1",              // Flowable 6.x 新前缀
            "DELETE FROM qrtz_triggers WHERE sched_name = 'x'", // 调度表
    })
    void testNewlyCoveredPrefixesDenied(String sql) {
        assertThrows(ServiceException.class, () -> validate(sql),
                "这些前缀在修复前是放行的：" + sql);
    }

    @ParameterizedTest
    @DisplayName("⚠️ 标识符变形不能绕过：反引号 / 库名限定 / 大写")
    @ValueSource(strings = {
            "UPDATE `sys_grant` SET effect = 'ALLOW'",
            "INSERT INTO `system_users` (username) VALUES ('x')",
            "UPDATE mydb.sys_grant SET effect = 'ALLOW'",
            "UPDATE `mydb`.`sys_grant` SET effect = 'ALLOW'",
            "UPDATE SYS_GRANT SET effect = 'ALLOW'",
            "DELETE FROM SYSTEM_USERS WHERE id = 1",
    })
    void testIdentifierVariantsDenied(String sql) {
        assertThrows(ServiceException.class, () -> validate(sql),
                "标识符变形不应绕过系统表写保护：" + sql);
    }

    @ParameterizedTest
    @DisplayName("业务模块表允许写入 —— 收紧的是系统表，不是所有带前缀的表")
    @ValueSource(strings = {
            "UPDATE crm_customer SET name = 'x'",
            "INSERT INTO erp_product (name) VALUES ('x')",
            "DELETE FROM mall_order WHERE id = 1",
            "UPDATE biz_purchase_order SET amount = 1",
    })
    void testBusinessTableWriteAllowed(String sql) {
        assertDoesNotThrow(() -> validate(sql), "业务表写入是合法需求：" + sql);
    }

    @Test
    @DisplayName("查询框架表是允许的 —— 只禁写不禁读")
    void testFrameworkTableReadAllowed() {        assertDoesNotThrow(() -> validate("SELECT id, nickname FROM system_users"),
                "查框架表应允许，很多报表需要");
        assertDoesNotThrow(() -> validate(
                "SELECT a.id FROM biz_order a LEFT JOIN system_users b ON a.user_id = b.id"),
                "JOIN 框架表做查询应允许");
    }

    @Test
    @DisplayName("⚠️ 多语句里对框架表的写操作：整体拒绝")
    void testFrameworkTableWriteInMultiStatement() {
        assertThrows(ServiceException.class, () ->
                validate("SELECT id FROM biz_order; UPDATE system_users SET nickname = 'x'"));
    }

    // ==================== 解析失败 ====================

    @Test
    @DisplayName("无法解析的 SQL 拒绝")
    void testInvalidSql() {
        assertThrows(ServiceException.class, () -> validate("这不是 SQL"));
    }

    @Test
    @DisplayName("空 SQL 拒绝")
    void testEmptySql() {
        assertThrows(ServiceException.class, () -> validate(""));
    }

}
