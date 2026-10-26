package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableIndexModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TableDiffer} 的单元测试
 *
 * 差异比对算错会导致「该改的没改」或「不该删的删了」，逐场景锁死行为。
 *
 * @author 走进新时代
 */
class TableDifferTest {

    private TableColumnModel col(String name, String type) {
        return new TableColumnModel().setColumnName(name).setColumnType(type)
                .setNullable(true).setPrimaryKey(false).setAutoIncrement(false)
                .setColumnComment("").setDefaultValue(null);
    }

    @Test
    @DisplayName("结构一致：无差异")
    void testDiff_noChange() {
        List<TableColumnModel> current = List.of(col("id", "LONG"), col("name", "STRING").setLength(64));
        List<TableColumnModel> target = List.of(col("id", "LONG"), col("name", "STRING").setLength(64));

        TableDiffer.Diff diff = TableDiffer.diff(target, current);
        assertTrue(diff.isEmpty(), "应无差异");
        assertFalse(diff.hasLossyChange());
    }

    @Test
    @DisplayName("新增字段")
    void testDiff_add() {
        List<TableColumnModel> current = List.of(col("id", "LONG"));
        List<TableColumnModel> target = List.of(col("id", "LONG"), col("memo", "TEXT"));

        TableDiffer.Diff diff = TableDiffer.diff(target, current);
        assertEquals(1, diff.getAddColumns().size());
        assertEquals("memo", diff.getAddColumns().get(0).getColumnName());
        assertTrue(diff.getModifyColumns().isEmpty());
        assertTrue(diff.getDropColumns().isEmpty());
    }

    @Test
    @DisplayName("删除字段：标记为有损操作")
    void testDiff_drop() {
        List<TableColumnModel> current = List.of(col("id", "LONG"), col("obsolete", "INT"));
        List<TableColumnModel> target = List.of(col("id", "LONG"));

        TableDiffer.Diff diff = TableDiffer.diff(target, current);
        assertEquals(List.of("obsolete"), diff.getDropColumns());
        assertTrue(diff.hasLossyChange(), "删字段必须标记为有损");
    }

    @Test
    @DisplayName("改类型：识别为修改")
    void testDiff_modifyType() {
        List<TableColumnModel> current = List.of(col("amount", "INT"));
        List<TableColumnModel> target = List.of(col("amount", "LONG"));

        TableDiffer.Diff diff = TableDiffer.diff(target, current);
        assertEquals(1, diff.getModifyColumns().size());
        assertFalse(diff.hasLossyChange(), "改类型不算有损（本方案定义）");
    }

    @Test
    @DisplayName("改长度：识别为修改")
    void testDiff_modifyLength() {
        List<TableColumnModel> current = List.of(col("name", "STRING").setLength(64));
        List<TableColumnModel> target = List.of(col("name", "STRING").setLength(128));

        TableDiffer.Diff diff = TableDiffer.diff(target, current);
        assertEquals(1, diff.getModifyColumns().size());
    }

    @Test
    @DisplayName("改名：走修改而不是删除+新增")
    void testDiff_rename() {
        List<TableColumnModel> current = List.of(col("order_code", "STRING").setLength(32));
        List<TableColumnModel> target = List.of(
                col("order_no", "STRING").setLength(32).setOldColumnName("order_code"));

        TableDiffer.Diff diff = TableDiffer.diff(target, current);
        assertEquals(1, diff.getModifyColumns().size(), "改名应是修改");
        assertTrue(diff.getDropColumns().isEmpty(), "改名不该产生删除");
        assertTrue(diff.getAddColumns().isEmpty(), "改名不该产生新增");
        assertFalse(diff.hasLossyChange(), "改名不是有损操作");
    }

    @Test
    @DisplayName("不传 oldColumnName 的改名：会被判成删除+新增（这是预期行为）")
    void testDiff_renameWithoutOldName() {
        List<TableColumnModel> current = List.of(col("order_code", "STRING").setLength(32));
        List<TableColumnModel> target = List.of(col("order_no", "STRING").setLength(32));

        TableDiffer.Diff diff = TableDiffer.diff(target, current);
        assertEquals(1, diff.getAddColumns().size());
        assertEquals(1, diff.getDropColumns().size());
        assertTrue(diff.hasLossyChange(), "此路径会丢数据，必须标记有损");
    }

    @Test
    @DisplayName("字段名大小写不同：不算变化")
    void testDiff_caseInsensitive() {
        List<TableColumnModel> current = List.of(col("Name", "STRING").setLength(64));
        List<TableColumnModel> target = List.of(col("name", "STRING").setLength(64));

        TableDiffer.Diff diff = TableDiffer.diff(target, current);
        assertTrue(diff.getDropColumns().isEmpty(), "大小写差异不该判成删除");
        assertTrue(diff.getAddColumns().isEmpty());
    }

    @Test
    @DisplayName("长度 null 与 0：视作等价，不产生无谓 ALTER")
    void testDiff_lengthNullVsZero() {
        List<TableColumnModel> current = List.of(col("memo", "TEXT").setLength(0));
        List<TableColumnModel> target = List.of(col("memo", "TEXT").setLength(null));

        TableDiffer.Diff diff = TableDiffer.diff(target, current);
        assertTrue(diff.isEmpty(), "null 与 0 应等价，实际修改数：" + diff.getModifyColumns().size());
    }

    @Test
    @DisplayName("改可空、主键、自增、注释、默认值：都识别为修改")
    void testDiff_modifyOtherAttributes() {
        // 可空
        assertEquals(1, TableDiffer.diff(
                List.of(col("f", "INT").setNullable(false)),
                List.of(col("f", "INT").setNullable(true))).getModifyColumns().size(), "可空变化");
        // 自增
        assertEquals(1, TableDiffer.diff(
                List.of(col("f", "LONG").setAutoIncrement(true)),
                List.of(col("f", "LONG").setAutoIncrement(false))).getModifyColumns().size(), "自增变化");
        // 注释
        assertEquals(1, TableDiffer.diff(
                List.of(col("f", "INT").setColumnComment("新注释")),
                List.of(col("f", "INT").setColumnComment(""))).getModifyColumns().size(), "注释变化");
        // 默认值
        assertEquals(1, TableDiffer.diff(
                List.of(col("f", "INT").setDefaultValue("0")),
                List.of(col("f", "INT").setDefaultValue(null))).getModifyColumns().size(), "默认值变化");
    }

    @Test
    @DisplayName("综合场景：一次改表包含增删改")
    void testDiff_mixed() {
        List<TableColumnModel> current = List.of(
                col("id", "LONG"), col("old_field", "INT"), col("name", "STRING").setLength(64));
        List<TableColumnModel> target = List.of(
                col("id", "LONG"),
                col("name", "STRING").setLength(128),   // 改长度
                col("new_field", "DATETIME"));          // 新增；old_field 被删

        TableDiffer.Diff diff = TableDiffer.diff(target, current);
        assertEquals(1, diff.getAddColumns().size());
        assertEquals(1, diff.getModifyColumns().size());
        assertEquals(List.of("old_field"), diff.getDropColumns());
        assertTrue(diff.hasLossyChange());
    }

    // ==================== 索引差异 ====================

    private TableIndexModel idx(String name, String type, String... cols) {
        return new TableIndexModel().setIndexName(name).setIndexType(type).setColumnNames(List.of(cols));
    }

    @Test
    @DisplayName("索引一致：无差异（名字大小写不敏感、列顺序参与比对）")
    void testDiffIndexes_noChange() {
        TableDiffer.IndexDiff diff = TableDiffer.diffIndexes(
                List.of(idx("idx_name", TableIndexModel.TYPE_NORMAL, "name")),
                List.of(idx("IDX_NAME", TableIndexModel.TYPE_NORMAL, "name")));
        assertTrue(diff.isEmpty(), "同名同结构应无差异");
    }

    @Test
    @DisplayName("索引：新增（当前没有）")
    void testDiffIndexes_add() {
        TableDiffer.IndexDiff diff = TableDiffer.diffIndexes(
                List.of(idx("idx_name", TableIndexModel.TYPE_NORMAL, "name")), List.of());
        assertEquals(1, diff.getAddIndexes().size());
        assertTrue(diff.getDropIndexNames().isEmpty());
    }

    @Test
    @DisplayName("索引：删除（目标没有）")
    void testDiffIndexes_drop() {
        TableDiffer.IndexDiff diff = TableDiffer.diffIndexes(
                List.of(), List.of(idx("idx_old", TableIndexModel.TYPE_NORMAL, "name")));
        assertEquals(List.of("idx_old"), diff.getDropIndexNames());
        assertTrue(diff.getAddIndexes().isEmpty());
    }

    @Test
    @DisplayName("索引：同名但列变了，先删旧再加新")
    void testDiffIndexes_rebuildOnChange() {
        TableDiffer.IndexDiff diff = TableDiffer.diffIndexes(
                List.of(idx("idx_x", TableIndexModel.TYPE_NORMAL, "name")),
                List.of(idx("idx_x", TableIndexModel.TYPE_NORMAL, "code")));
        assertEquals(List.of("idx_x"), diff.getDropIndexNames());
        assertEquals(1, diff.getAddIndexes().size());
    }

    @Test
    @DisplayName("索引：同名但类型变了（普通→唯一），先删旧再加新")
    void testDiffIndexes_rebuildOnTypeChange() {
        TableDiffer.IndexDiff diff = TableDiffer.diffIndexes(
                List.of(idx("idx_x", TableIndexModel.TYPE_UNIQUE, "name")),
                List.of(idx("idx_x", TableIndexModel.TYPE_NORMAL, "name")));
        assertEquals(List.of("idx_x"), diff.getDropIndexNames());
        assertEquals(1, diff.getAddIndexes().size());
    }

    @Test
    @DisplayName("索引：null 安全")
    void testDiffIndexes_nullSafe() {
        assertTrue(TableDiffer.diffIndexes(null, null).isEmpty());
    }

}
