package cn.enlev.ainb.framework.mybatis.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static cn.enlev.ainb.framework.mybatis.core.util.TableOperation.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SystemTablePolicy} 的单元测试
 *
 * 这批用例钉住的是一个真实缺口：表单设计的前缀名单只有 system_/infra_/bpm_，
 * 于是 sys_grant（S8 权限重建的授权表）能被绑定为可写主表 —— 有表单设计权限的人
 * 可以给自己插授权行，绕过整个权限判定链。详见 docs/安全修复-框架表写保护缺口.md。
 *
 * @author 走进新时代
 */
public class SystemTablePolicyTest {

    // ==================== 写保护：这批是缺口本体 ====================

    @ParameterizedTest
    @DisplayName("⚠️ 系统核心表禁止写入")
    @ValueSource(strings = {
            "sys_grant", "sys_resource", "sys_rule", "sys_subject",   // S8 权限体系，原名单漏掉
            "system_users", "infra_config", "bpm_process_instance",   // 原名单已覆盖
            "ai_api_key",                                             // 密钥表，原名单漏掉
            "act_ru_task", "flw_ru_batch",                            // 工作流引擎，原名单漏掉
            "qrtz_triggers"                                           // 调度表，原名单漏掉
    })
    void testSystemCoreWriteDenied(String tableName) {
        assertFalse(SystemTablePolicy.isAllowed(tableName, WRITE),
                "系统核心表不应允许写入：" + tableName);
    }

    @ParameterizedTest
    @DisplayName("业务模块表允许写入 —— 收紧的是 8 个前缀，不是 24 个")
    @ValueSource(strings = {"crm_customer", "erp_product", "mall_order", "hrm_employee", "iot_device"})
    void testBusinessModuleWriteAllowed(String tableName) {
        assertTrue(SystemTablePolicy.isAllowed(tableName, WRITE),
                "业务模块表是合法的写入目标：" + tableName);
    }

    @ParameterizedTest
    @DisplayName("普通业务表允许写入")
    @ValueSource(strings = {"biz_purchase_order", "test_demo_order", "my_table"})
    void testPlainTableWriteAllowed(String tableName) {
        assertTrue(SystemTablePolicy.isAllowed(tableName, WRITE));
    }

    // ==================== 读一律放行，不要改坏 ====================

    @ParameterizedTest
    @DisplayName("读操作一律放行 —— 做报表需要读系统表")
    @ValueSource(strings = {"sys_grant", "system_users", "qrtz_triggers", "act_ru_task", "crm_customer"})
    void testReadAlwaysAllowed(String tableName) {
        assertTrue(SystemTablePolicy.isAllowed(tableName, READ),
                "读框架表应放行，这是刻意设计：" + tableName);
    }

    // ==================== DDL / REGISTER 口径更严 ====================

    @ParameterizedTest
    @DisplayName("建表禁止占用预留模块前缀 —— 防止与将来启用的模块撞名")
    @ValueSource(strings = {"crm_customer", "erp_product", "mall_order", "report_daily", "sys_grant"})
    void testDdlDeniedForReservedModule(String tableName) {
        assertFalse(SystemTablePolicy.isAllowed(tableName, DDL));
        assertFalse(SystemTablePolicy.isAllowed(tableName, REGISTER), "登记口径应与建表一致");
    }

    @Test
    @DisplayName("建表允许普通业务前缀")
    void testDdlAllowedForPlainTable() {
        assertTrue(SystemTablePolicy.isAllowed("biz_purchase_order", DDL));
        assertTrue(SystemTablePolicy.isAllowed("test_demo_order", DDL));
    }

    @Test
    @DisplayName("WRITE 比 DDL 宽：crm_ 能写不能建")
    void testWriteLooserThanDdl() {
        assertTrue(SystemTablePolicy.isAllowed("crm_customer", WRITE));
        assertFalse(SystemTablePolicy.isAllowed("crm_customer", DDL));
    }

    // ==================== 归一化：三种形态都要拦住 ====================

    @ParameterizedTest
    @DisplayName("⚠️ 大写变形要拦住 —— QRTZ_* 在库里就是大写的")
    @ValueSource(strings = {"SYS_GRANT", "SYSTEM_USERS", "QRTZ_TRIGGERS", "Sys_Grant"})
    void testUpperCaseDenied(String tableName) {
        assertFalse(SystemTablePolicy.isAllowed(tableName, WRITE));
    }

    @ParameterizedTest
    @DisplayName("⚠️ 反引号包裹要拦住 —— SQL 解析器可能返回带引号的形态")
    @ValueSource(strings = {"`sys_grant`", "`SYS_GRANT`", "\"sys_grant\""})
    void testQuotedDenied(String tableName) {
        assertFalse(SystemTablePolicy.isAllowed(tableName, WRITE));
    }

    @ParameterizedTest
    @DisplayName("⚠️ 库名限定要拦住")
    @ValueSource(strings = {"mydb.sys_grant", "`mydb`.`sys_grant`", "ruoyi-vue-pro.system_users"})
    void testSchemaQualifiedDenied(String tableName) {
        assertFalse(SystemTablePolicy.isAllowed(tableName, WRITE));
    }

    @Test
    @DisplayName("归一化后的结果")
    void testNormalize() {
        assertEquals("sys_grant", SystemTablePolicy.normalize("`mydb`.`SYS_GRANT`"));
        assertEquals("sys_grant", SystemTablePolicy.normalize("  SYS_GRANT  "));
        assertEquals("", SystemTablePolicy.normalize(null));
        assertEquals("", SystemTablePolicy.normalize("   "));
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("表名为空时按更危险的处理：写拒绝、读放行")
    void testBlankTableName() {
        assertFalse(SystemTablePolicy.isAllowed(null, WRITE));
        assertFalse(SystemTablePolicy.isAllowed("", DDL));
        assertTrue(SystemTablePolicy.isAllowed(null, READ), "读没有限制，空表名也不该在这里报");
    }

    @Test
    @DisplayName("前缀相近但不命中的表不受影响")
    void testSimilarButNotMatching() {
        assertTrue(SystemTablePolicy.isAllowed("systematic_data", WRITE), "system 后面不是下划线");
        assertTrue(SystemTablePolicy.isAllowed("syslog_archive", WRITE), "sys 后面不是下划线");
        assertTrue(SystemTablePolicy.isAllowed("action_log", WRITE), "act 后面不是下划线");
    }

    // ==================== isSystemTable 的口径 ====================

    @Test
    @DisplayName("isSystemTable 用的是 SYSTEM_CORE，不把 crm_ 标成系统表")
    void testIsSystemTableScope() {
        assertTrue(SystemTablePolicy.isSystemTable("sys_grant"));
        assertTrue(SystemTablePolicy.isSystemTable("QRTZ_TRIGGERS"));
        assertFalse(SystemTablePolicy.isSystemTable("crm_customer"),
                "界面上把业务模块表标成「系统表」会误导用户");
        assertFalse(SystemTablePolicy.isSystemTable("biz_order"));
    }

    // ==================== 下发前端的前缀集 ====================

    @Test
    @DisplayName("prefixes 按操作返回不同集合")
    void testPrefixes() {
        assertTrue(SystemTablePolicy.prefixes(READ).isEmpty(), "读无限制");
        assertEquals(8, SystemTablePolicy.prefixes(WRITE).size());
        assertTrue(SystemTablePolicy.prefixes(WRITE).contains("qrtz_"));
        assertTrue(SystemTablePolicy.prefixes(DDL).containsAll(SystemTablePolicy.prefixes(WRITE)),
                "DDL 口径必须覆盖 WRITE");
        assertTrue(SystemTablePolicy.prefixes(DDL).contains("crm_"));
    }

}
