package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SubTableDiffEngine} 的单元测试
 *
 * 子表 diff 的正确性直接关系到数据安全：判错会导致明细行被误删，
 * 或者该更新的行被当成新增而产生重复数据。
 *
 * @author 走进新时代
 */
class SubTableDiffEngineTest {

    /**
     * 构造一个默认子表配置：主键为 id
     */
    private SubTableBinding binding() {
        SubTableBinding binding = new SubTableBinding();
        binding.setTable("biz_order_item");
        binding.setPk("id");
        binding.setForeignKey("order_id");
        binding.setField("orderItems");
        return binding;
    }

    private Map<String, Object> row(Object id, String name) {
        Map<String, Object> row = new HashMap<>();
        if (id != null) {
            row.put("id", id);
        }
        row.put("productName", name);
        return row;
    }

    @Test
    @DisplayName("提交行无主键：判为新增")
    void testNoPkIsInsert() {
        List<Map<String, Object>> submitted = List.of(row(null, "商品A"));
        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(binding(), submitted, Collections.emptySet());

        assertEquals(1, result.getToInsert().size());
        assertTrue(result.getToUpdate().isEmpty());
        assertTrue(result.getToDeletePks().isEmpty());
    }

    @Test
    @DisplayName("提交行有主键且库里存在：判为修改")
    void testExistingPkIsUpdate() {
        List<Map<String, Object>> submitted = List.of(row(1L, "商品A改名"));
        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(binding(), submitted, Set.of(1L));

        assertEquals(1, result.getToUpdate().size());
        assertTrue(result.getToInsert().isEmpty());
        assertTrue(result.getToDeletePks().isEmpty(), "库里的 1 已被提交覆盖，不该删");
    }

    @Test
    @DisplayName("库里有但提交里没有：判为删除")
    void testMissingFromSubmitIsDelete() {
        List<Map<String, Object>> submitted = List.of(row(1L, "商品A"));
        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(binding(), submitted, Set.of(1L, 2L, 3L));

        assertEquals(1, result.getToUpdate().size());
        assertEquals(2, result.getToDeletePks().size(), "2 和 3 未提交，应删除");
        assertTrue(result.getToDeletePks().containsAll(List.of(2L, 3L)));
    }

    @Test
    @DisplayName("⚠️ 提交行有主键但库里不存在：容错为新增而非报错")
    void testDirtyPkIsInsert() {
        // 前端可能传来脏 id（比如复制粘贴了别的记录的明细）
        List<Map<String, Object>> submitted = List.of(row(999L, "商品X"));
        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(binding(), submitted, Set.of(1L));

        assertEquals(1, result.getToInsert().size(), "脏 id 应按新增处理");
        assertTrue(result.getToUpdate().isEmpty());
        assertEquals(1, result.getToDeletePks().size(), "库里的 1 未被提交，应删除");
    }

    @Test
    @DisplayName("⚠️ 主键类型不一致（Integer vs Long）：仍应正确匹配为修改")
    void testPkTypeNormalization() {
        // JSON 反序列化后主键可能是 Integer，而库里查出来是 Long。
        // 若不做归一化，会误判为「新增 + 删除」，导致明细 ID 漂移
        List<Map<String, Object>> submitted = List.of(row(1, "商品A"));
        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(binding(), submitted, Set.of(1L));

        assertEquals(1, result.getToUpdate().size(), "Integer 1 应匹配 Long 1");
        assertTrue(result.getToInsert().isEmpty(), "不该判为新增");
        assertTrue(result.getToDeletePks().isEmpty(), "不该误删");
    }

    @Test
    @DisplayName("⚠️ 主键是字符串数字：仍应正确匹配")
    void testStringPkNormalization() {
        List<Map<String, Object>> submitted = List.of(row("1", "商品A"));
        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(binding(), submitted, Set.of(1L));

        assertEquals(1, result.getToUpdate().size(), "字符串 \"1\" 应匹配 Long 1");
        assertTrue(result.getToDeletePks().isEmpty());
    }

    @Test
    @DisplayName("主键为空字符串：判为新增")
    void testBlankPkIsInsert() {
        List<Map<String, Object>> submitted = List.of(row("", "商品A"));
        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(binding(), submitted, Collections.emptySet());

        assertEquals(1, result.getToInsert().size(), "空串主键应按新增处理");
    }

    @Test
    @DisplayName("混合场景：新增 + 修改 + 删除同时发生")
    void testMixedScenario() {
        List<Map<String, Object>> submitted = new ArrayList<>();
        submitted.add(row(1L, "改名的商品"));   // 修改
        submitted.add(row(null, "新增的商品"));  // 新增
        // 库里的 2L 未提交 → 删除

        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(binding(), submitted, Set.of(1L, 2L));

        assertEquals(1, result.getToInsert().size());
        assertEquals(1, result.getToUpdate().size());
        assertEquals(1, result.getToDeletePks().size());
        assertEquals(2L, result.getToDeletePks().get(0));
    }

    @Test
    @DisplayName("提交为空但库里有数据：全部删除")
    void testEmptySubmitDeletesAll() {
        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(binding(), Collections.emptyList(), Set.of(1L, 2L));

        assertEquals(2, result.getToDeletePks().size());
        assertTrue(result.getToInsert().isEmpty());
        assertTrue(result.getToUpdate().isEmpty());
    }

    @Test
    @DisplayName("提交为 null：等价于空提交，库里数据全删")
    void testNullSubmit() {
        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(binding(), null, Set.of(1L));

        assertEquals(1, result.getToDeletePks().size());
    }

    @Test
    @DisplayName("库里为 null：只处理新增")
    void testNullExisting() {
        List<Map<String, Object>> submitted = List.of(row(null, "商品A"));
        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(binding(), submitted, null);

        assertEquals(1, result.getToInsert().size());
        assertTrue(result.getToDeletePks().isEmpty());
    }

    @Test
    @DisplayName("跳过空行：前端可能传来全空的占位行")
    void testSkipEmptyRows() {
        List<Map<String, Object>> submitted = new ArrayList<>();
        submitted.add(new HashMap<>());  // 空行
        submitted.add(row(null, "商品A"));

        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(binding(), submitted, Collections.emptySet());

        assertEquals(1, result.getToInsert().size(), "空行应被跳过");
    }

    @Test
    @DisplayName("自定义主键名：不叫 id 时也应正确识别")
    void testCustomPkName() {
        SubTableBinding customBinding = binding();
        customBinding.setPk("item_no");

        Map<String, Object> row = new HashMap<>();
        row.put("item_no", 5L);
        row.put("productName", "商品A");

        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(customBinding, List.of(row), Set.of(5L));

        assertEquals(1, result.getToUpdate().size(), "应按 item_no 识别主键");
    }

    @Test
    @DisplayName("无变化场景：diff 结果为空")
    void testNoChange() {
        SubTableDiffEngine.DiffResult result =
                SubTableDiffEngine.diff(binding(), Collections.emptyList(), Collections.emptySet());

        assertTrue(result.isEmpty(), "无提交无库存时 diff 应为空");
    }

}
