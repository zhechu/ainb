package cn.enlev.ainb.module.system.framework.permission.core.dataset;

import cn.enlev.ainb.module.infra.api.formdesign.dto.FormResourceDescriptorDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SlotInferencer} 的单元测试类
 *
 * 纯函数，不需要 Spring，也不需要数据库。
 *
 * 重点覆盖两件事：
 * 1. 显式声明（autoFill 的创建人列）必须压过按列名猜
 * 2. 推不出来时<b>什么都不写</b> —— 写一个空列名会让条件树生成语法不合法的 SQL，
 *    那是整张列表查不出来，比「这条规则没生效」严重得多
 *
 * @author 走进新时代
 */
public class SlotInferencerTest {

    private final SlotInferencer inferencer = new SlotInferencer();

    @Test
    public void testInfer_三个槽位都能推出来() {
        FormResourceDescriptorDTO dto = build(
                List.of("id", "name", "create_user_id", "create_dept_id", "status"), null);

        Map<String, String> slots = inferencer.infer(dto);

        assertEquals("create_user_id", slots.get("owner"));
        assertEquals("create_dept_id", slots.get("dept"));
        assertEquals("status", slots.get("status"));
    }

    @Test
    public void testInfer_显式声明的创建人列优先于按列名猜() {
        // 表里 creator 和 owner_user_id 都在，候选名单里 creator 排得更靠前；
        // 但设计者显式声明了 owner_user_id，必须听设计者的
        FormResourceDescriptorDTO dto = build(
                List.of("id", "creator", "owner_user_id"), "owner_user_id");

        Map<String, String> slots = inferencer.infer(dto);

        assertEquals("owner_user_id", slots.get("owner"));
    }

    @Test
    public void testInfer_显式声明的列不存在时回退到猜() {
        // 设计者填了一个表里没有的列名。按它推会生成一个查不到列的 SQL，
        // 所以退回按列名猜，而不是原样采信
        FormResourceDescriptorDTO dto = build(List.of("id", "creator"), "not_exists_column");

        Map<String, String> slots = inferencer.infer(dto);

        assertEquals("creator", slots.get("owner"));
    }

    @Test
    public void testInfer_推不出来的槽位不写() {
        FormResourceDescriptorDTO dto = build(List.of("id", "name", "amount"), null);

        Map<String, String> slots = inferencer.infer(dto);

        assertFalse(slots.containsKey("owner"));
        assertFalse(slots.containsKey("dept"));
        assertFalse(slots.containsKey("status"));
        // ⚠️ bizKey 是例外：表里有 id 列就推得出来。
        // 它和另外三个的取舍方向相反 —— owner/dept/status 推错会「按错误的列过滤」，
        // 而 bizKey 推不出来会让派生可见性与记录共享【恒假】（一行都看不到，
        // 比不配还糟，见 PermissionSlotEnum.BIZ_KEY 的注释）
        assertEquals("id", slots.get("bizKey"));
    }

    @Test
    public void testInfer_主键列显式给出时优先于按列名猜() {
        FormResourceDescriptorDTO dto = build(List.of("id", "order_no"), null);
        dto.setMainPk("order_no");

        Map<String, String> slots = inferencer.infer(dto);

        assertEquals("order_no", slots.get("bizKey"));
    }

    @Test
    public void testInfer_主键列不在物理列里也照样采信() {
        // ⚠️ 与 owner 的口径刻意不同：owner 的显式声明要求列真的存在，
        // 而 mainPk 不要求 —— 主键通常不在表单字段里，物理列全集在数据源不通时又是空的。
        // 要求它存在等于在最需要它的场景下推不出来
        FormResourceDescriptorDTO dto = new FormResourceDescriptorDTO();
        dto.setMainTable("biz_order");
        dto.setMainPk("order_no");

        Map<String, String> slots = inferencer.infer(dto);

        assertEquals("order_no", slots.get("bizKey"));
    }

    @Test
    public void testInfer_读不到表结构时退化为看字段映射() {
        // 元数据读不到（数据源不通）→ tableColumns 为空，只剩字段映射这条线索
        FormResourceDescriptorDTO dto = new FormResourceDescriptorDTO();
        dto.setMainTable("biz_order");
        dto.setFields(List.of(item("orderStatus", "status"), item("title", "title")));

        Map<String, String> slots = inferencer.infer(dto);

        assertEquals("status", slots.get("status"));
        assertNull(slots.get("owner"));
    }

    @Test
    public void testInfer_子表字段映射的列不算主表的列() {
        // items.auditStatus 映射到子表的 status 列。行过滤作用在主表上，
        // 把它当成主表列会推出一个主表根本没有的列名
        FormResourceDescriptorDTO dto = new FormResourceDescriptorDTO();
        dto.setMainTable("biz_order");
        dto.setFields(List.of(item("items.auditStatus", "status")));

        Map<String, String> slots = inferencer.infer(dto);

        assertFalse(slots.containsKey("status"), "子表列不该被当成主表列，实际：" + slots);
        assertFalse(slots.containsKey("owner"));
    }

    @Test
    public void testInfer_列名大小写不敏感() {
        FormResourceDescriptorDTO dto = build(List.of("ID", "CREATE_USER_ID", "STATUS"), "CREATE_USER_ID");

        Map<String, String> slots = inferencer.infer(dto);

        assertEquals("create_user_id", slots.get("owner"));
        assertEquals("status", slots.get("status"));
    }

    @Test
    public void testInfer_空描述不抛异常() {
        assertTrue(inferencer.infer(null).isEmpty());
        assertTrue(inferencer.infer(new FormResourceDescriptorDTO()).isEmpty());
    }

    // ==================== 构造 ====================

    private FormResourceDescriptorDTO build(List<String> tableColumns, String creatorColumn) {
        FormResourceDescriptorDTO dto = new FormResourceDescriptorDTO();
        dto.setFormCode("demo");
        dto.setMainTable("biz_demo");
        dto.setTableColumns(tableColumns);
        dto.setCreatorColumn(creatorColumn);
        return dto;
    }

    private FormResourceDescriptorDTO.Item item(String key, String column) {
        FormResourceDescriptorDTO.Item item = new FormResourceDescriptorDTO.Item();
        item.setKey(key);
        item.setColumn(column);
        return item;
    }

}
