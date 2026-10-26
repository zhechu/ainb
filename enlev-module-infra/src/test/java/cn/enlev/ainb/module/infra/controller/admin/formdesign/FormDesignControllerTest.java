package cn.enlev.ainb.module.infra.controller.admin.formdesign;

import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignDbTableRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignDbColumnRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.db.DataSourceConfigDO;
import cn.enlev.ainb.module.infra.service.db.DataSourceConfigService;
import cn.enlev.ainb.module.infra.service.db.DatabaseTableService;
import cn.enlev.ainb.module.infra.service.formdesign.FormDesignService;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FormDesignController} 的单元测试
 */
@DisplayName("FormDesignController 单元测试")
class FormDesignControllerTest {

    @Mock
    private FormDesignService formDesignService;
    @Mock
    private DataSourceConfigService dataSourceConfigService;
    @Mock
    private DatabaseTableService databaseTableService;

    @InjectMocks
    private FormDesignController controller;

    private static final Long DS_ID = 0L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("getDbTables - 只返回有 tenant_id 的表")
    void testGetDbTablesOnlyReturnTablesWithTenantId() {
        // 准备数据：3 个表，只有 biz_customer 有 tenant_id
        TableInfo customerTable = mockTable("biz_customer", "客户表");
        TableInfo orderTable = mockTable("biz_order", "订单表");
        TableInfo systemUserTable = mockTable("system_users", "系统用户表");

        when(databaseTableService.getTableList(eq(DS_ID), eq(null), eq(null)))
                .thenReturn(Arrays.asList(customerTable, orderTable, systemUserTable));

        // biz_customer 有 tenant_id
        mockTableFields("biz_customer", "id", "name", "tenant_id", "deleted");
        // biz_order 没有 tenant_id
        mockTableFields("biz_order", "id", "order_no", "deleted");
        // system_users 没有 tenant_id（系统内置表）
        mockTableFields("system_users", "id", "username", "deleted");

        // 调用接口
        var result = controller.getDbTables(DS_ID, null);

        // 验证：只返回 biz_customer
        assertNotNull(result);
        assertTrue(result.isSuccess());
        List<FormDesignDbTableRespVO> tables = result.getData();
        assertEquals(1, tables.size(), "应该只返回 1 个有 tenant_id 的表");
        assertEquals("biz_customer", tables.get(0).getName());
    }

    @Test
    @DisplayName("getDbTables - 所有表都没有 tenant_id 时返回空列表")
    void testGetDbTablesReturnEmptyWhenNoTenantId() {
        TableInfo systemUserTable = mockTable("system_users", "系统用户表");
        TableInfo infraConfigTable = mockTable("infra_config", "系统配置表");

        when(databaseTableService.getTableList(eq(DS_ID), eq(null), eq(null)))
                .thenReturn(Arrays.asList(systemUserTable, infraConfigTable));

        mockTableFields("system_users", "id", "username");
        mockTableFields("infra_config", "id", "config_key", "config_value");

        var result = controller.getDbTables(DS_ID, null);

        assertTrue(result.isSuccess());
        assertTrue(result.getData().isEmpty(), "没有 tenant_id 的表应该被全部过滤");
    }

    @Test
    @DisplayName("getDbTables - 模糊搜索也应该过滤无 tenant_id 的表")
    void testGetDbTablesWithNameLikeFilter() {
        TableInfo bizCustomer = mockTable("biz_customer", "客户表");
        TableInfo bizConfig = mockTable("biz_config", "业务配置表");

        when(databaseTableService.getTableList(eq(DS_ID), eq("biz"), eq(null)))
                .thenReturn(Arrays.asList(bizCustomer, bizConfig));

        mockTableFields("biz_customer", "id", "name", "tenant_id");
        mockTableFields("biz_config", "id", "config_key");  // 无 tenant_id

        var result = controller.getDbTables(DS_ID, "biz");

        assertTrue(result.isSuccess());
        assertEquals(1, result.getData().size());
        assertEquals("biz_customer", result.getData().get(0).getName());
    }

    // ==================== getTableColumns 测试 ====================

    @Test
    @DisplayName("getTableColumns - 过滤掉系统自动维护的字段")
    void testGetTableColumnsFilterAutoManagedColumns() {
        String tableName = "biz_customer";
        mockTableFieldsWithDetails(tableName,
                field("id", true, "主键"),
                field("name", false, "客户名称"),
                field("level", false, "客户等级"),
                field("creator", false, "创建人"),
                field("create_time", false, "创建时间"),
                field("updater", false, "更新人"),
                field("update_time", false, "更新时间"),
                field("deleted", false, "逻辑删除"),
                field("tenant_id", false, "租户ID")
        );

        var result = controller.getTableColumns(DS_ID, tableName);

        assertTrue(result.isSuccess());
        List<FormDesignDbColumnRespVO> columns = result.getData();
        // 应该只返回：id (主键保留), name, level
        assertEquals(3, columns.size(), "应该过滤掉 6 个系统字段，保留主键和业务字段");

        List<String> names = columns.stream().map(FormDesignDbColumnRespVO::getColumnName).toList();
        assertTrue(names.contains("id"), "主键应该保留");
        assertTrue(names.contains("name"), "业务字段应该保留");
        assertTrue(names.contains("level"), "业务字段应该保留");
        assertFalse(names.contains("creator"), "系统字段应该被过滤");
        assertFalse(names.contains("tenant_id"), "租户字段应该被过滤");
    }

    @Test
    @DisplayName("getTableColumns - 表不存在时返回空列表")
    void testGetTableColumnsReturnEmptyWhenTableNotExists() {
        when(databaseTableService.getTable(eq(DS_ID), eq("not_exists")))
                .thenReturn(null);

        var result = controller.getTableColumns(DS_ID, "not_exists");

        assertTrue(result.isSuccess());
        assertTrue(result.getData().isEmpty());
    }

    // ==================== 辅助方法 ====================

    private TableInfo mockTable(String name, String comment) {
        TableInfo table = mock(TableInfo.class);
        when(table.getName()).thenReturn(name);
        when(table.getComment()).thenReturn(comment);
        return table;
    }

    private void mockTableFields(String tableName, String... columnNames) {
        TableInfo table = mock(TableInfo.class);
        List<TableField> fields = Arrays.stream(columnNames)
                .map(col -> {
                    TableField field = mock(TableField.class);
                    when(field.getName()).thenReturn(col);
                    return field;
                })
                .toList();
        when(table.getFields()).thenReturn(fields);
        when(databaseTableService.getTable(eq(DS_ID), eq(tableName))).thenReturn(table);
    }

    private void mockTableFieldsWithDetails(String tableName, TableField... fields) {
        TableInfo table = mock(TableInfo.class);
        when(table.getFields()).thenReturn(Arrays.asList(fields));
        when(databaseTableService.getTable(eq(DS_ID), eq(tableName))).thenReturn(table);
    }

    private TableField field(String name, boolean isPrimaryKey, String comment) {
        TableField field = mock(TableField.class);
        when(field.getName()).thenReturn(name);
        when(field.isKeyFlag()).thenReturn(isPrimaryKey);
        when(field.getComment()).thenReturn(comment);
        return field;
    }
}
