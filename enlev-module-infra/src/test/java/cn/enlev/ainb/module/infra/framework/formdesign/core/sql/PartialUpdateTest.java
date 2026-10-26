package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

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
 * UPDATE 的「局部更新」语义
 *
 * <h3>为什么必须是局部更新</h3>
 * 原实现无条件遍历整个 fieldMapping，对每个字段取 {@code data.get(field)} ——
 * 提交数据里没有的字段取到 null，于是被写成 NULL。
 *
 * 这在流程场景下是直接的数据丢失：
 * <ul>
 *   <li>审批节点按字段权限裁剪后只提交可编辑字段，其余字段【全被清空】</li>
 *   <li>节点表单只放了三五个补充字段，一提交就把发起时填的内容抹掉</li>
 * </ul>
 * 而且两者都不报错 —— 用户是在流程走完之后才发现单据变空了。
 *
 * 局部更新对普通表单毫无影响：表单提交的是完整表单，字段都在 data 里，
 * 用户清空的字段也会以 key 存在、值为 null 的形式提交，照样能被更新成 NULL。
 * 区别只在「压根没提交这个 key」和「提交了但值为空」，这两者本来就该区分对待。
 *
 * @author 走进新时代
 */
class PartialUpdateTest {

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
        mockTable(TABLE, "id", "name", "level", "address", "remark");
        mockTable(SIDE_TABLE, "id", "customer_id", "tax_no", "bank_account");
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

    private TableBinding binding() {
        TableBinding binding = new TableBinding();
        binding.setMainTable(TABLE);
        binding.setMainPk("id");
        binding.setPkStrategy("AUTO");
        Map<String, FieldMapping> fieldMapping = new LinkedHashMap<>();
        fieldMapping.put("customerName", mapping(TABLE, "name"));
        fieldMapping.put("customerLevel", mapping(TABLE, "level"));
        fieldMapping.put("customerAddress", mapping(TABLE, "address"));
        fieldMapping.put("customerRemark", mapping(TABLE, "remark"));
        binding.setFieldMapping(fieldMapping);
        return binding;
    }

    @Test
    @DisplayName("⚠️ 只提交部分字段时，未提交的字段不能出现在 SET 里")
    void testOnlySubmittedFieldsAreUpdated() {
        // 模拟审批节点：字段权限裁剪后只剩两个可编辑字段
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("customerLevel", 3);
        submitted.put("customerRemark", "审批备注");

        String sql = builder.buildUpdate(DS_MAIN, binding(), submitted, USER_ID, 1L).getSql();

        assertTrue(sql.contains("`level` = ?"), sql);
        assertTrue(sql.contains("`remark` = ?"), sql);
        // 没提交的字段绝不能被写成 NULL —— 那是发起人填的内容
        assertFalse(sql.contains("`name` = ?"), "未提交的 name 被清空了：" + sql);
        assertFalse(sql.contains("`address` = ?"), "未提交的 address 被清空了：" + sql);
    }

    @Test
    @DisplayName("提交了但值为空的字段，仍要更新成 NULL")
    void testExplicitNullStillUpdates() {
        // 「没提交这个 key」与「提交了值为空」必须区分：后者是用户主动清空
        Map<String, Object> submitted = new HashMap<>();
        submitted.put("customerRemark", null);

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildUpdate(DS_MAIN, binding(), submitted, USER_ID, 1L);
        assertTrue(result.getSql().contains("`remark` = ?"), result.getSql());
        assertNull(result.getParams().get(0), "用户主动清空的字段要写 NULL");
        assertFalse(result.getSql().contains("`name` = ?"), result.getSql());
    }

    @Test
    @DisplayName("提交完整表单时行为不变，四个字段全部更新")
    void testFullSubmitUnchanged() {
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("customerName", "张三");
        submitted.put("customerLevel", 1);
        submitted.put("customerAddress", "杭州");
        submitted.put("customerRemark", "备注");

        String sql = builder.buildUpdate(DS_MAIN, binding(), submitted, USER_ID, 1L).getSql();
        for (String col : List.of("name", "level", "address", "remark")) {
            assertTrue(sql.contains("`" + col + "` = ?"), col + " 应被更新：" + sql);
        }
    }

    @Test
    @DisplayName("一个可写字段都没提交时，不生成非法的空 SET 语句")
    void testNoWritableFieldRejected() {
        // 没有任何可更新列时应抛错而不是拼出 UPDATE t SET WHERE ...
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildUpdate(DS_MAIN, binding(), Map.of("unknownField", 1), USER_ID, 1L));
    }

    @Test
    @DisplayName("⚠️ 副表 UPDATE 同样只更新提交过的字段")
    void testSideTablePartialUpdate() {
        SideTableBinding side = new SideTableBinding();
        side.setTable(SIDE_TABLE);
        side.setPk("id");
        side.setForeignKey("customer_id");
        Map<String, FieldMapping> sideMapping = new LinkedHashMap<>();
        sideMapping.put("taxNo", mapping(SIDE_TABLE, "tax_no"));
        sideMapping.put("bankAccount", mapping(SIDE_TABLE, "bank_account"));
        side.setFieldMapping(sideMapping);

        TableBinding binding = binding();
        binding.setSideTables(List.of(side));

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableUpdate(
                DS_MAIN, binding, side, Map.of("taxNo", "TAX-1"), 1L);

        assertNotNull(result);
        assertTrue(result.getSql().contains("`tax_no` = ?"), result.getSql());
        assertFalse(result.getSql().contains("`bank_account` = ?"),
                "未提交的副表字段被清空了：" + result.getSql());
    }

    @Test
    @DisplayName("副表一个字段都没提交时返回 null，调用方据此跳过")
    void testSideTableNothingToUpdate() {
        SideTableBinding side = new SideTableBinding();
        side.setTable(SIDE_TABLE);
        side.setPk("id");
        side.setForeignKey("customer_id");
        side.setFieldMapping(new LinkedHashMap<>(Map.of("taxNo", mapping(SIDE_TABLE, "tax_no"))));

        TableBinding binding = binding();
        binding.setSideTables(List.of(side));

        assertNull(builder.buildSideTableUpdate(DS_MAIN, binding, side, Map.of("other", 1), 1L));
    }

}
