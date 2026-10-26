package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.enlev.ainb.module.infra.enums.datamodel.SystemColumnProfileEnum;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统列注入策略的单元测试（P0b / R2）
 *
 * <p>这组用例守的是「两套事实规范」的收敛结果：此前「不要 tenant_id」只能靠
 * 绕过注入器实现，现在是 {@link SystemColumnProfileEnum} 的显式声明。
 *
 * <p>同时钉住时间列的定义 —— 改造前生成的是「可空、无默认值」，
 * 与本分支样板表的 {@code NOT NULL DEFAULT CURRENT_TIMESTAMP} 不一致。
 *
 * @author 走进新时代
 */
public class SystemColumnInjectorProfileTest {

    private TableColumnModel biz(String name) {
        return new TableColumnModel().setColumnName(name).setColumnType("STRING")
                .setNullable(true).setPrimaryKey(false).setColumnComment("业务列");
    }

    private Optional<TableColumnModel> find(List<TableColumnModel> columns, String name) {
        return columns.stream().filter(c -> c.getColumnName().equalsIgnoreCase(name)).findFirst();
    }

    private boolean has(List<TableColumnModel> columns, String name) {
        return find(columns, name).isPresent();
    }

    // ==================== 三种策略各注入什么 ====================

    @Test
    @DisplayName("TENANT：注入 tenant_id，不注入 version")
    void testTenantProfile() {
        List<TableColumnModel> result = SystemColumnInjector.inject(
                List.of(biz("order_no")), SystemColumnProfileEnum.TENANT);
        assertTrue(has(result, "tenant_id"));
        assertFalse(has(result, "version"));
        assertTrue(has(result, "deleted"));
        assertTrue(has(result, "id"));
        assertTrue(has(result, "creator") && has(result, "create_time")
                && has(result, "updater") && has(result, "update_time"));
    }

    @Test
    @DisplayName("🔴 GLOBAL：不注入 tenant_id —— 这是显式声明「数据对所有租户可见」")
    void testGlobalProfile() {
        List<TableColumnModel> result = SystemColumnInjector.inject(
                List.of(biz("model_name")), SystemColumnProfileEnum.GLOBAL);
        assertFalse(has(result, "tenant_id"), "GLOBAL 表不应有 tenant_id");
        assertFalse(has(result, "version"));
        // 其余系统列照旧
        assertTrue(has(result, "id") && has(result, "deleted") && has(result, "create_time"));
    }

    @Test
    @DisplayName("LEGACY：注入 version、不注入 tenant_id —— 对齐 test_demo_* 样板表")
    void testLegacyProfile() {
        List<TableColumnModel> result = SystemColumnInjector.inject(
                List.of(biz("order_no")), SystemColumnProfileEnum.LEGACY);
        assertTrue(has(result, "version"), "LEGACY 表应有 version");
        assertFalse(has(result, "tenant_id"), "LEGACY 表不应有 tenant_id");
        assertTrue(has(result, "deleted"));
    }

    @Test
    @DisplayName("不传 profile 的旧重载 = TENANT，行为与改造前一致")
    void testDefaultOverloadUnchanged() {
        List<TableColumnModel> result = SystemColumnInjector.inject(List.of(biz("order_no")));
        assertTrue(has(result, "tenant_id"));
        assertFalse(has(result, "version"));
    }

    @Test
    @DisplayName("profileOf 对 null 与未知值都回落到 TENANT —— 老调用方不传也安全")
    void testProfileOfFallback() {
        assertSame(SystemColumnProfileEnum.TENANT, SystemColumnProfileEnum.profileOf(null));
        assertSame(SystemColumnProfileEnum.TENANT, SystemColumnProfileEnum.profileOf(""));
        assertSame(SystemColumnProfileEnum.TENANT, SystemColumnProfileEnum.profileOf("不存在的策略"));
        assertSame(SystemColumnProfileEnum.GLOBAL, SystemColumnProfileEnum.profileOf("global"));
        assertSame(SystemColumnProfileEnum.LEGACY, SystemColumnProfileEnum.profileOf("LEGACY"));
    }

    @Test
    @DisplayName("null profile 按 TENANT 处理，不抛 NPE")
    void testNullProfile() {
        List<TableColumnModel> result = SystemColumnInjector.inject(List.of(biz("order_no")), null);
        assertTrue(has(result, "tenant_id"));
    }

    // ==================== 时间列的定义（R2） ====================

    @Test
    @DisplayName("🔴 create_time：NOT NULL + DEFAULT CURRENT_TIMESTAMP")
    void testCreateTimeDefinition() {
        TableColumnModel createTime = find(
                SystemColumnInjector.inject(List.of(biz("order_no"))), "create_time").orElseThrow();
        assertFalse(createTime.isNullable(), "create_time 应为 NOT NULL");
        assertEquals("CURRENT_TIMESTAMP", createTime.getDefaultValue());
        assertFalse(createTime.isOnUpdateCurrentTimestamp(), "create_time 不该带 ON UPDATE");
    }

    @Test
    @DisplayName("🔴 update_time：NOT NULL + DEFAULT CURRENT_TIMESTAMP + ON UPDATE")
    void testUpdateTimeDefinition() {
        TableColumnModel updateTime = find(
                SystemColumnInjector.inject(List.of(biz("order_no"))), "update_time").orElseThrow();
        assertFalse(updateTime.isNullable());
        assertEquals("CURRENT_TIMESTAMP", updateTime.getDefaultValue());
        assertTrue(updateTime.isOnUpdateCurrentTimestamp(), "update_time 应带 ON UPDATE");
    }

    @Test
    @DisplayName("生成的 DDL 里确实有 ON UPDATE CURRENT_TIMESTAMP")
    void testDdlContainsOnUpdate() {
        String ddl = MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                SystemColumnInjector.inject(List.of(biz("order_no"))));
        assertTrue(ddl.contains("ON UPDATE CURRENT_TIMESTAMP"), ddl);
        // CURRENT_TIMESTAMP 是函数不能加引号
        assertFalse(ddl.contains("'CURRENT_TIMESTAMP'"), "CURRENT_TIMESTAMP 不该被当字面量加引号：" + ddl);
        assertTrue(ddl.contains("`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP"), ddl);
    }

    @Test
    @DisplayName("GLOBAL 表生成的 DDL 里没有 tenant_id")
    void testGlobalDdl() {
        String ddl = MysqlDdlBuilder.buildCreateTable("biz_model_price", "模型单价",
                SystemColumnInjector.inject(List.of(biz("model_name")), SystemColumnProfileEnum.GLOBAL));
        assertFalse(ddl.contains("tenant_id"), ddl);
    }

    @Test
    @DisplayName("审计人列生成 varchar(64) NULL，不带 DEFAULT '' —— 与框架表的一处已知差异")
    void testAuditorColumnHasNoEmptyDefault() {
        String ddl = MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                SystemColumnInjector.inject(List.of(biz("order_no"))));
        assertTrue(ddl.contains("`creator` varchar(64) NULL"), ddl);
        // 当前模型无法表达「默认值是空串」，所以这里刻意没有 DEFAULT ''
        assertFalse(ddl.contains("`creator` varchar(64) NULL DEFAULT"), ddl);
    }

    // ==================== 原有「不覆盖」语义不能被破坏 ====================

    @Test
    @DisplayName("用户自己定义了同名系统列时，注入器让位不覆盖")
    void testUserDefinedColumnNotOverridden() {
        TableColumnModel ownId = new TableColumnModel().setColumnName("id")
                .setColumnType("STRING").setLength(32).setNullable(false)
                .setPrimaryKey(true).setColumnComment("业务主键");
        List<TableColumnModel> result = SystemColumnInjector.inject(List.of(ownId));
        // 只有一个 id，且保持用户的 STRING 定义
        assertEquals(1, result.stream().filter(c -> "id".equalsIgnoreCase(c.getColumnName())).count());
        assertEquals("STRING", find(result, "id").orElseThrow().getColumnType());
    }

    @Test
    @DisplayName("LEGACY 下用户自己定义了 version 时也不覆盖")
    void testUserDefinedVersionNotOverridden() {
        TableColumnModel ownVersion = new TableColumnModel().setColumnName("version")
                .setColumnType("LONG").setNullable(true).setPrimaryKey(false)
                .setColumnComment("自定义版本");
        List<TableColumnModel> result = SystemColumnInjector.inject(
                List.of(ownVersion), SystemColumnProfileEnum.LEGACY);
        assertEquals(1, result.stream()
                .filter(c -> "version".equalsIgnoreCase(c.getColumnName())).count());
        assertEquals("LONG", find(result, "version").orElseThrow().getColumnType());
    }

    // ==================== version 纳入托管的后果 ====================

    @Test
    @DisplayName("🔴 version 现在是系统列 —— 它因此受改表的删除保护")
    void testVersionIsSystemColumn() {
        assertTrue(SystemColumnInjector.isSystemColumn("version"));
        assertTrue(SystemColumnInjector.isSystemColumn("VERSION"), "大小写不敏感");
    }

    @Test
    @DisplayName("业务列不是系统列")
    void testBusinessColumnNotSystem() {
        assertFalse(SystemColumnInjector.isSystemColumn("order_no"));
        assertFalse(SystemColumnInjector.isSystemColumn(null));
    }

    // ==================== 改表比对不能因 R2 产生多余 ALTER ====================

    @Test
    @DisplayName("🔴 同一份注入结果自比对：零变化 —— 否则每次改表都会白改 update_time")
    void testInjectedColumnsSelfDiffIsEmpty() {
        List<TableColumnModel> injected = SystemColumnInjector.inject(List.of(biz("order_no")));
        TableDiffer.Diff diff = TableDiffer.diff(injected, injected);
        assertTrue(diff.getAddColumns().isEmpty(), "不该有新增");
        assertTrue(diff.getModifyColumns().isEmpty(), "不该有修改：" + diff.getModifyColumns());
        assertTrue(diff.getDropColumns().isEmpty(), "不该有删除");
    }

    @Test
    @DisplayName("ON UPDATE 的增减会被识别为字段变化")
    void testOnUpdateChangeDetected() {
        TableColumnModel without = new TableColumnModel().setColumnName("update_time")
                .setColumnType("DATETIME").setNullable(false).setPrimaryKey(false)
                .setColumnComment("更新时间").setDefaultValue("CURRENT_TIMESTAMP");
        TableColumnModel with = new TableColumnModel().setColumnName("update_time")
                .setColumnType("DATETIME").setNullable(false).setPrimaryKey(false)
                .setColumnComment("更新时间").setDefaultValue("CURRENT_TIMESTAMP")
                .setOnUpdateCurrentTimestamp(true);
        assertEquals(1, TableDiffer.diff(List.of(with), List.of(without)).getModifyColumns().size());
    }
}
