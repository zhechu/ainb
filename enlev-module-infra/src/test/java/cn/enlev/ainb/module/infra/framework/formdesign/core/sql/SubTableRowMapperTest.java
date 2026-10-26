package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 子表明细行的列名 ↔ 字段名转换测试
 *
 * 验证前端子表控件能拿到正确的键，以及逐行 diff 需要的主键始终在场。
 *
 * @author 走进新时代
 */
class SubTableRowMapperTest {

    private SubTableBinding orderItemBinding() {
        SubTableBinding subTable = new SubTableBinding();
        subTable.setTable("biz_customer_order_item");
        subTable.setPk("id");
        subTable.setForeignKey("customer_id");
        subTable.setField("orderItems");
        return subTable;
    }

    private FieldMapping mapping(String column) {
        FieldMapping fieldMapping = new FieldMapping();
        fieldMapping.setTable("biz_customer_order_item");
        fieldMapping.setColumn(column);
        return fieldMapping;
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    @Test
    void testColumnRenamedToField() {
        SubTableBinding subTable = orderItemBinding();
        subTable.setFieldMapping(new HashMap<>(Map.of(
                "productName", mapping("product_name"),
                "quantity", mapping("qty"),
                "unitPrice", mapping("unit_price"))));

        List<Map<String, Object>> rows = SubTableRowMapper.toFieldKeyedRows(subTable, List.of(
                row("id", 1001L, "customer_id", 100L, "product_name", "笔记本电脑",
                        "qty", 2, "unit_price", 5999.00),
                row("id", 1002L, "customer_id", 100L, "product_name", "鼠标",
                        "qty", 5, "unit_price", 89.00)));

        assertEquals(2, rows.size());
        Map<String, Object> first = rows.get(0);
        assertEquals("笔记本电脑", first.get("productName"));
        assertEquals(2, first.get("quantity"));
        assertEquals(5999.00, first.get("unitPrice"));
        assertEquals(1001L, first.get("id"), "主键保留");
        assertEquals(100L, first.get("customer_id"), "外键未映射，保持原列名");

        // 物理列名不该再出现，否则前端会同时看到两份同样的数据
        assertFalse(first.containsKey("product_name"));
        assertFalse(first.containsKey("qty"));
        assertFalse(first.containsKey("unit_price"));

        assertEquals("鼠标", rows.get(1).get("productName"));
    }

    @Test
    void testPkKeptWhenMappedToAnotherFieldName() {
        // 用户把主键列映射成了 rowId：diff 仍必须能按物理列名 id 取到主键，
        // 否则每次保存都会把明细全删再全插，明细 ID 全部漂移
        SubTableBinding subTable = orderItemBinding();
        subTable.setFieldMapping(new HashMap<>(Map.of(
                "rowId", mapping("id"),
                "productName", mapping("product_name"))));

        List<Map<String, Object>> rows = SubTableRowMapper.toFieldKeyedRows(subTable,
                List.of(row("id", 1001L, "product_name", "笔记本电脑")));

        Map<String, Object> first = rows.get(0);
        assertEquals(1001L, first.get("id"), "主键必须以物理列名保留一份供 diff 使用");
        assertEquals(1001L, first.get("rowId"), "用户映射的字段名同样要有值");
        assertEquals("笔记本电脑", first.get("productName"));
    }

    @Test
    void testNonDefaultPkColumn() {
        SubTableBinding subTable = orderItemBinding();
        subTable.setPk("item_id");
        subTable.setFieldMapping(new HashMap<>(Map.of("productName", mapping("product_name"))));

        List<Map<String, Object>> rows = SubTableRowMapper.toFieldKeyedRows(subTable,
                List.of(row("item_id", 77L, "product_name", "鼠标")));

        assertEquals(77L, rows.get(0).get("item_id"), "主键列不叫 id 时同样要保留");
    }

    @Test
    void testWithoutFieldMappingKeepsColumnNames() {
        SubTableBinding subTable = orderItemBinding();
        subTable.setFieldMapping(null);

        List<Map<String, Object>> rows = SubTableRowMapper.toFieldKeyedRows(subTable,
                List.of(row("id", 1L, "name", "测试")));

        assertEquals(1L, rows.get(0).get("id"));
        assertEquals("测试", rows.get(0).get("name"), "无 fieldMapping 时保持原列名");
    }

    @Test
    void testEmptyAndNullInput() {
        SubTableBinding subTable = orderItemBinding();
        assertTrue(SubTableRowMapper.toFieldKeyedRows(subTable, null).isEmpty());
        assertTrue(SubTableRowMapper.toFieldKeyedRows(subTable, List.of()).isEmpty());
    }

    @Test
    void testPkColumnOfFallsBackToId() {
        assertEquals("id", SubTableRowMapper.pkColumnOf(null));
        assertEquals("id", SubTableRowMapper.pkColumnOf(new SubTableBinding()));

        SubTableBinding blank = new SubTableBinding();
        blank.setPk("  ");
        assertEquals("id", SubTableRowMapper.pkColumnOf(blank), "空白主键列回退到约定名 id");
    }

    @Test
    void testRowMissingPkIsPassedThrough() {
        // 查询没把主键列查出来时不该凭空造一个 null 主键 ——
        // diff 会把 null 当作「新增行」，凭空造出来的键会掩盖查询白名单漏配的问题
        SubTableBinding subTable = orderItemBinding();
        subTable.setFieldMapping(new HashMap<>(Map.of("productName", mapping("product_name"))));

        List<Map<String, Object>> rows = SubTableRowMapper.toFieldKeyedRows(subTable,
                List.of(row("product_name", "键盘")));

        assertFalse(rows.get(0).containsKey("id"), "原始行没有主键时不补空键");
        assertEquals("键盘", rows.get(0).get("productName"));
    }

}
