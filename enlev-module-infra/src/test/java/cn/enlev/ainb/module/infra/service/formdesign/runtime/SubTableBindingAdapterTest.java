package cn.enlev.ainb.module.infra.service.formdesign.runtime;

import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 子表配置适配成 TableBinding 的规则测试
 *
 * 这层适配是为了复用 SqlBuilder，但它决定了「哪些列会被拼进 INSERT / UPDATE」，
 * 配错的后果都是运行时才炸、且报错信息完全指不到表单配置上。
 *
 * @author 走进新时代
 */
class SubTableBindingAdapterTest {

    private static final String FK_VIRTUAL_FIELD = "__fk__";

    private SubTableBinding subTable() {
        SubTableBinding subTable = new SubTableBinding();
        subTable.setTable("biz_order_item");
        subTable.setField("orderItems");
        subTable.setPk("id");
        subTable.setForeignKey("order_id");

        Map<String, FieldMapping> mapping = new LinkedHashMap<>();
        mapping.put("productName", mapping("product_name"));
        mapping.put("quantity", mapping("qty"));
        subTable.setFieldMapping(mapping);
        return subTable;
    }

    private FieldMapping mapping(String column) {
        FieldMapping fieldMapping = new FieldMapping();
        fieldMapping.setTable("biz_order_item");
        fieldMapping.setColumn(column);
        return fieldMapping;
    }

    /** 调用被测的私有适配方法 */
    @SuppressWarnings("unchecked")
    private TableBinding adapt(SubTableBinding subTable, boolean includeForeignKey) {
        FormDesignRuntimeServiceImpl service = new FormDesignRuntimeServiceImpl();
        return (TableBinding) ReflectionTestUtils.invokeMethod(
                service, "toSubTableBinding", subTable, includeForeignKey);
    }

    private boolean mapsToColumn(TableBinding binding, String column) {
        return binding.getFieldMapping().values().stream()
                .anyMatch(m -> m != null && column.equals(m.getColumn()));
    }

    @Test
    @DisplayName("INSERT 需要外键：明细靠它关联主记录")
    void testInsertIncludesForeignKey() {
        TableBinding binding = adapt(subTable(), true);
        assertTrue(binding.getFieldMapping().containsKey(FK_VIRTUAL_FIELD),
                "插入时外键必须作为虚拟字段进入映射，否则明细成为孤儿行");
        assertEquals("order_id", binding.getFieldMapping().get(FK_VIRTUAL_FIELD).getColumn());
    }

    @Test
    @DisplayName("⚠️ UPDATE 不能带外键：会拼进 SET 且取到 null，直接违反非空约束")
    void testUpdateExcludesForeignKey() {
        TableBinding binding = adapt(subTable(), false);

        // 带上外键会让 buildUpdate 生成 SET `order_id` = ?，而值取自提交数据里
        // 并不存在的虚拟字段 __fk__，必然是 null →
        // Column 'order_id' cannot be null，整个更新接口 500
        assertFalse(binding.getFieldMapping().containsKey(FK_VIRTUAL_FIELD),
                "更新明细时外键不该出现在映射里：" + binding.getFieldMapping().keySet());
        assertFalse(mapsToColumn(binding, "order_id"),
                "更新语句不该改动外键，明细永远属于同一条主记录");
        // 业务字段仍要在
        assertTrue(mapsToColumn(binding, "product_name"));
        assertTrue(mapsToColumn(binding, "qty"));
    }

    @Test
    @DisplayName("⚠️ 用户把外键映射进表单时，仍不允许前端的值覆盖后端填充")
    void testUserMappedForeignKeyIsStripped() {
        SubTableBinding subTable = subTable();
        Map<String, FieldMapping> mapping = new LinkedHashMap<>(subTable.getFieldMapping());
        // 用户手滑把外键列也配成了一个表单字段
        mapping.put("orderId", mapping("order_id"));
        subTable.setFieldMapping(mapping);

        // 插入：外键只能由后端强制填充，出现两次会生成 INSERT 里重复列
        TableBinding insertBinding = adapt(subTable, true);
        long fkCount = insertBinding.getFieldMapping().values().stream()
                .filter(m -> m != null && "order_id".equals(m.getColumn())).count();
        assertEquals(1, fkCount, "外键在 INSERT 映射里只能出现一次：" + insertBinding.getFieldMapping());
        assertTrue(insertBinding.getFieldMapping().containsKey(FK_VIRTUAL_FIELD));

        // 更新：一次都不该出现
        assertFalse(mapsToColumn(adapt(subTable, false), "order_id"));
    }

    @Test
    @DisplayName("⚠️ 映射到主键列的字段要剔除：INSERT 会撞成两列，UPDATE 会改主键")
    void testPrimaryKeyMappingStripped() {
        SubTableBinding subTable = subTable();
        Map<String, FieldMapping> mapping = new LinkedHashMap<>(subTable.getFieldMapping());
        // 主键被映射成一个任意字段名，剔除必须按物理列名判断
        mapping.put("rowId", mapping("id"));
        subTable.setFieldMapping(mapping);

        assertFalse(mapsToColumn(adapt(subTable, true), "id"), "INSERT 映射里不该有主键列");
        assertFalse(mapsToColumn(adapt(subTable, false), "id"), "UPDATE 映射里不该有主键列");
    }

    @Test
    @DisplayName("适配后的 binding 指向子表自身，主键策略固定 AUTO 由调用方显式传值")
    void testBasicShape() {
        TableBinding binding = adapt(subTable(), true);
        assertEquals("biz_order_item", binding.getMainTable());
        assertEquals("id", binding.getMainPk());
        assertEquals("AUTO", binding.getPkStrategy(),
                "策略若跟着子表配置走，会与 insertSubRow 各生成一个不同的主键");
    }

}
