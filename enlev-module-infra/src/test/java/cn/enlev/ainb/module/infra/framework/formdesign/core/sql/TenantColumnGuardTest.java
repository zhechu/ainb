package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SideTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.service.db.DatabaseTableService;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 租户列不可被表单字段映射的安全约束
 *
 * <h3>为什么必须在 SQL 构建层拦</h3>
 * tenant_id 由后端从租户上下文填充，任何情况下都不该接受前端提交的值。
 * 前端的「保留列黑名单」只是界面约束，不是安全边界 ——
 * table_binding 是一段 JSON，调用方可以直接调设计接口提交任意内容。
 *
 * 更关键的是：表单发布后再调 update 修改设计【不会】重新走发布校验，
 * 而运行时读的是实时的 table_binding，改完立即生效。
 * 所以只在发布校验里拦是不够的，必须在真正拼 SQL 的地方兜住。
 *
 * <h3>被拦住的攻击</h3>
 * 把任意表单字段映射到 tenant_id 列，则 UPDATE 会生成 {@code SET tenant_id = ?}
 * 并绑上前端传来的值 —— 等于把一条记录搬到别的租户下，或反过来把别的租户的数据
 * 拉进当前租户的可见范围。
 *
 * @author 走进新时代
 */
class TenantColumnGuardTest {

    private static final Long DS_MAIN = 0L;
    private static final String TABLE = "biz_customer";
    private static final String SIDE_TABLE = "biz_customer_ext";
    private static final Long USER_ID = 1L;

    private FormDesignSqlBuilder builder;
    private DatabaseTableService databaseTableService;

    @BeforeEach
    void setUp() {
        databaseTableService = mock(DatabaseTableService.class);
        // 协作者较多，装配统一放在 SqlBuilderTestFactory
        builder = SqlBuilderTestFactory.create(databaseTableService);
        mockTable(TABLE, "id", "name", "tenant_id", "deleted");
        mockTable(SIDE_TABLE, "id", "customer_id", "ext_remark", "tenant_id", "deleted");
        TenantContextHolder.setTenantId(1L);
    }

    private void mockTable(String tableName, String... columns) {
        TableInfo tableInfo = mock(TableInfo.class);
        List<TableField> fields = Arrays.stream(columns).map(col -> {
            TableField field = mock(TableField.class);
            when(field.getName()).thenReturn(col);
            return field;
        }).toList();
        when(tableInfo.getFields()).thenReturn(fields);
        when(databaseTableService.getTable(eq(DS_MAIN), eq(tableName))).thenReturn(tableInfo);
    }

    private FieldMapping mapping(String table, String column) {
        FieldMapping fieldMapping = new FieldMapping();
        fieldMapping.setTable(table);
        fieldMapping.setColumn(column);
        return fieldMapping;
    }

    /** 一份把表单字段偷偷映射到 tenant_id 的恶意绑定 */
    private TableBinding maliciousBinding() {
        TableBinding binding = new TableBinding();
        binding.setMainTable(TABLE);
        binding.setMainPk("id");
        binding.setPkStrategy("AUTO");
        Map<String, FieldMapping> fieldMapping = new LinkedHashMap<>();
        fieldMapping.put("customerName", mapping(TABLE, "name"));
        fieldMapping.put("evilTenant", mapping(TABLE, "tenant_id"));
        binding.setFieldMapping(fieldMapping);
        return binding;
    }

    @Test
    @DisplayName("⚠️ UPDATE 拒绝把表单字段映射到 tenant_id：否则前端可把记录搬到别的租户")
    void testUpdateRejectsTenantMapping() {
        Map<String, Object> data = Map.of("customerName", "张三", "evilTenant", 999L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> builder.buildUpdate(DS_MAIN, maliciousBinding(), data, USER_ID, 1L));
        assertTrue(ex.getMessage().contains("tenant_id"), ex.getMessage());
    }

    @Test
    @DisplayName("⚠️ INSERT 同样拒绝：租户值只能来自服务端上下文")
    void testInsertRejectsTenantMapping() {
        Map<String, Object> data = Map.of("customerName", "张三", "evilTenant", 999L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> builder.buildInsert(DS_MAIN, maliciousBinding(), data, USER_ID, null));
        assertTrue(ex.getMessage().contains("tenant_id"), ex.getMessage());
    }

    @Test
    @DisplayName("⚠️ 副表写入同样拒绝")
    void testSideTableRejectsTenantMapping() {
        SideTableBinding side = new SideTableBinding();
        side.setTable(SIDE_TABLE);
        side.setPk("id");
        side.setForeignKey("customer_id");
        side.setFieldMapping(new LinkedHashMap<>(Map.of("evilTenant", mapping(SIDE_TABLE, "tenant_id"))));

        TableBinding binding = new TableBinding();
        binding.setMainTable(TABLE);
        binding.setMainPk("id");
        binding.setFieldMapping(new LinkedHashMap<>(Map.of("customerName", mapping(TABLE, "name"))));
        binding.setSideTables(List.of(side));

        Map<String, Object> data = Map.of("evilTenant", 999L);
        assertThrows(ServiceException.class,
                () -> builder.buildSideTableInsert(DS_MAIN, binding, side, data, 1L));
        assertThrows(ServiceException.class,
                () -> builder.buildSideTableUpdate(DS_MAIN, binding, side, data, 1L));
    }

    @Test
    @DisplayName("大小写变形也要拦住，不能靠字面量比对绕过")
    void testCaseInsensitive() {
        TableBinding binding = maliciousBinding();
        binding.getFieldMapping().put("evilTenant", mapping(TABLE, "TENANT_ID"));
        assertThrows(ServiceException.class,
                () -> builder.buildUpdate(DS_MAIN, binding, Map.of("evilTenant", 9L), USER_ID, 1L));
    }

    @Test
    @DisplayName("正常字段不受影响，租户值仍由服务端上下文填充")
    void testNormalMappingStillWorks() {
        TableBinding binding = new TableBinding();
        binding.setMainTable(TABLE);
        binding.setMainPk("id");
        binding.setPkStrategy("AUTO");
        binding.setFieldMapping(new LinkedHashMap<>(Map.of("customerName", mapping(TABLE, "name"))));

        FormDesignSqlBuilder.SqlWithParams insert =
                builder.buildInsert(DS_MAIN, binding, Map.of("customerName", "张三"), USER_ID, null);
        assertTrue(insert.getSql().contains("`tenant_id`"), "租户列仍要由后端写入");
        assertTrue(insert.getParams().contains(1L), "写入的是上下文里的租户 ID");
        // 只出现一次，不会因为映射与自动填充各写一遍而生成重复列
        assertEquals(1, insert.getSql().split("`tenant_id`", -1).length - 1, insert.getSql());
    }

}
