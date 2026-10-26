package cn.enlev.ainb.module.bpm.service.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 节点提交数据的字段权限裁剪
 *
 * <h3>为什么子表要逐行裁</h3>
 * 子表控件提交上来的是一个数组（{@code contacts: [{...}, {...}]}），整段只有一个顶层 key。
 * 只按顶层 key 判权限的话，明细里的每一列就只能整体放行或整体禁止，
 * 做不到「审批人只能改明细的备注列」这种常见诉求。
 * 明细列的权限用 {@code 子表控件field.明细字段} 表示，裁剪时逐行按内部字段过滤。
 *
 * <h3>为什么主键必须留下</h3>
 * 运行时靠明细主键做逐行 diff。主键被裁掉的话每一行都会被当成新增，
 * 于是每次审批都把明细全删再全插，明细 ID 全部漂移，挂在明细上的引用直接断掉。
 *
 * @author 走进新时代
 */
class BpmFieldPermissionFilterTest {

    private static final String READ = "1";
    private static final String WRITE = "2";
    private static final String NONE = "3";

    private final BpmTaskServiceImpl service = new BpmTaskServiceImpl();

    @SuppressWarnings("unchecked")
    private Map<String, Object> filter(Map<String, Object> data, Map<String, String> permission) {
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                service, "filterWritableFields", data, permission);
    }

    private Map<String, Object> row(Object id, String name, String remark) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (id != null) {
            row.put("id", id);
        }
        row.put("contactName", name);
        row.put("remark", remark);
        return row;
    }

    @Test
    @DisplayName("没配权限时全部放行")
    void testNoPermissionKeepsAll() {
        Map<String, Object> data = Map.of("name", "张三", "contacts", List.of(row(1L, "李四", "备注")));
        assertEquals(data, filter(data, Collections.emptyMap()));
        assertEquals(data, filter(data, null));
    }

    @Test
    @DisplayName("顶层只读字段被裁掉")
    void testReadOnlyTopLevelDropped() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "张三");
        data.put("amount", 100);

        Map<String, Object> result = filter(data, Map.of("name", READ, "amount", WRITE));
        assertFalse(result.containsKey("name"), "只读字段不该被写回");
        assertEquals(100, result.get("amount"));
    }

    @Test
    @DisplayName("⚠️ 子表按明细列逐行裁剪：只读的明细列被剔除，可写的保留")
    void testSubTableInnerFieldFiltered() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contacts", new ArrayList<>(List.of(
                row(11L, "李四", "备注A"),
                row(12L, "王五", "备注B"))));

        // 明细的姓名只读、备注可编辑
        Map<String, String> permission = Map.of(
                "contacts.contactName", READ,
                "contacts.remark", WRITE);

        Map<String, Object> result = filter(data, permission);
        List<?> rows = (List<?>) result.get("contacts");
        assertEquals(2, rows.size());

        for (Object item : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) item;
            assertFalse(r.containsKey("contactName"), "只读的明细列不该被写回：" + r);
            assertTrue(r.containsKey("remark"), "可编辑的明细列要保留：" + r);
        }
    }

    @Test
    @DisplayName("⚠️ 明细主键必须原样保留，否则逐行 diff 会退化成全删全插")
    void testSubRowPrimaryKeyAlwaysKept() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contacts", new ArrayList<>(List.of(row(11L, "李四", "备注A"))));

        // 把明细的每一列都设成只读，主键仍必须留下
        Map<String, String> permission = Map.of(
                "contacts.contactName", READ,
                "contacts.remark", NONE);

        List<?> rows = (List<?>) filter(data, permission).get("contacts");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) rows.get(0);
        assertEquals(11L, r.get("id"), "主键是 diff 的依据，不受字段权限约束");
        assertFalse(r.containsKey("contactName"));
        assertFalse(r.containsKey("remark"));
    }

    @Test
    @DisplayName("子表整段被设为只读时，整个数组都不写回")
    void testWholeSubTableReadOnly() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contacts", new ArrayList<>(List.of(row(11L, "李四", "备注"))));
        assertFalse(filter(data, Map.of("contacts", READ)).containsKey("contacts"));
    }

    @Test
    @DisplayName("没配明细列权限时整段原样放行，不做无谓的逐行遍历")
    void testSubTableWithoutInnerPermissionUntouched() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(11L, "李四", "备注")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contacts", rows);

        Map<String, Object> result = filter(data, Map.of("name", READ));
        assertSame(rows, result.get("contacts"), "未配明细权限时应原样返回同一个引用");
    }

    @Test
    @DisplayName("明细里没配权限的列保持可写，与顶层字段的处理一致")
    void testUnknownInnerFieldStaysWritable() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contacts", new ArrayList<>(List.of(row(11L, "李四", "备注"))));

        // 只声明了 contactName，remark 没声明 —— 表单新增了列但权限配置还没同步
        List<?> rows = (List<?>) filter(data, Map.of("contacts.contactName", READ)).get("contacts");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) rows.get(0);
        assertTrue(r.containsKey("remark"), "未声明权限的明细列应保持可写");
        assertFalse(r.containsKey("contactName"));
    }

    @Test
    @DisplayName("明细行里没有主键（新增行）时不凭空造一个")
    void testNewSubRowWithoutPk() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contacts", new ArrayList<>(List.of(row(null, "新联系人", "备注"))));

        List<?> rows = (List<?>) filter(data, Map.of("contacts.contactName", WRITE)).get("contacts");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) rows.get(0);
        assertFalse(r.containsKey("id"), "新增行本就没有主键，不该补一个 null 出来");
        assertEquals("新联系人", r.get("contactName"));
    }

}
