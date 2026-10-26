package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列业务元数据存取的单元测试（P0b / R3）
 *
 * <p>重点在 {@link ColumnMetaStore#rebuild} —— 改名跟随与删除清理这两条，
 * 错了都是<b>静默失效</b>：改名后配置默默失联、删列后「幽灵配置」附到将来的同名新列上。
 *
 * @author 走进新时代
 */
public class ColumnMetaStoreTest {

    private TableColumnModel col(String name) {
        return new TableColumnModel().setColumnName(name).setColumnType("STRING");
    }

    private TableColumnModel withDict(String name, String dictType) {
        return col(name).setDictType(dictType);
    }

    private TableColumnModel withRef(String name, String refTable, String refColumn) {
        return col(name).setRefTable(refTable).setRefColumn(refColumn);
    }

    @Nested
    @DisplayName("序列化")
    class Serialize {

        @Test
        @DisplayName("只存有元数据的列")
        void testOnlyMeaningful() {
            String json = ColumnMetaStore.serialize(List.of(
                    col("order_no"), // 无元数据，不该进去
                    withDict("status", "order_status"),
                    withRef("customer_id", "biz_customer", "id")));
            assertNotNull(json);
            assertTrue(json.contains("order_status"));
            assertTrue(json.contains("biz_customer"));
            assertTrue(!json.contains("order_no"), "没有元数据的列不该占位：" + json);
        }

        @Test
        @DisplayName("一个都没有时返回 null，而不是空对象 —— 让库里保持干净")
        void testEmptyReturnsNull() {
            assertNull(ColumnMetaStore.serialize(List.of(col("a_col"), col("b_col"))));
            assertNull(ColumnMetaStore.serialize(List.of()));
            assertNull(ColumnMetaStore.serialize(null));
        }

        @Test
        @DisplayName("空白值按无值处理")
        void testBlankTrimmed() {
            assertNull(ColumnMetaStore.serialize(List.of(withDict("status", "   "))));
        }

        @Test
        @DisplayName("列名统一小写存储")
        void testLowerCaseKey() {
            String json = ColumnMetaStore.serialize(List.of(withDict("Status", "order_status")));
            assertTrue(json.contains("\"status\""), json);
        }

        @Test
        @DisplayName("🔴 产物形态精确匹配：不带 empty、不带 null 字段")
        void testExactJsonShape() {
            // 这条钉的是确切形态而非 contains —— 之前只断言 contains，
            // 漏掉了 Lombok 的 isEmpty() 被 Jackson 当 getter 序列化成 "empty":false，
            // 以及只配字典的列也会存下 "refTable":null,"refColumn":null。
            // 两者叠加让这一列的 JSON 膨胀一倍有余，而它要随表结构反复读写。
            assertEquals("{\"status\":{\"dictType\":\"order_status\"}}",
                    ColumnMetaStore.serialize(List.of(withDict("status", "order_status"))));
            assertEquals("{\"customer_id\":{\"refTable\":\"biz_customer\",\"refColumn\":\"id\"}}",
                    ColumnMetaStore.serialize(List.of(withRef("customer_id", "biz_customer", "id"))));
        }
    }

    @Nested
    @DisplayName("反序列化与合并")
    class Merge {

        @Test
        @DisplayName("合并回字段列表")
        void testMerge() {
            String json = ColumnMetaStore.serialize(List.of(
                    withDict("status", "order_status"),
                    withRef("customer_id", "biz_customer", "id")));
            List<TableColumnModel> columns = List.of(col("order_no"), col("status"), col("customer_id"));
            ColumnMetaStore.merge(columns, json);

            assertNull(columns.get(0).getDictType());
            assertEquals("order_status", columns.get(1).getDictType());
            assertEquals("biz_customer", columns.get(2).getRefTable());
            assertEquals("id", columns.get(2).getRefColumn());
        }

        @Test
        @DisplayName("大小写不敏感匹配")
        void testMergeIgnoreCase() {
            String json = ColumnMetaStore.serialize(List.of(withDict("status", "order_status")));
            List<TableColumnModel> columns = List.of(col("STATUS"));
            ColumnMetaStore.merge(columns, json);
            assertEquals("order_status", columns.get(0).getDictType());
        }

        @Test
        @DisplayName("🔴 JSON 坏掉时不抛错 —— 业务语义不该拖垮整张表的结构读取")
        void testBrokenJsonIsTolerated() {
            List<TableColumnModel> columns = List.of(col("status"));
            ColumnMetaStore.merge(columns, "{这不是合法 JSON");
            assertNull(columns.get(0).getDictType());
            assertTrue(ColumnMetaStore.deserialize("{坏的").isEmpty());
            assertTrue(ColumnMetaStore.deserialize(null).isEmpty());
            assertTrue(ColumnMetaStore.deserialize("").isEmpty());
        }
    }

    @Nested
    @DisplayName("🔴 改表时重建")
    class Rebuild {

        private final String current = ColumnMetaStore.serialize(List.of(
                withDict("status", "order_status"),
                withRef("customer_id", "biz_customer", "id")));

        @Test
        @DisplayName("普通改表不带元数据时：沿用旧的，不清空")
        void testInheritWhenNotSubmitted() {
            // 模拟「只改了个长度」的提交：没带 dictType/refTable
            String json = ColumnMetaStore.rebuild(
                    List.of(col("order_no"), col("status"), col("customer_id")), current);
            Map<String, ColumnMetaStore.ColumnMeta> map = ColumnMetaStore.deserialize(json);
            assertEquals("order_status", map.get("status").getDictType());
            assertEquals("biz_customer", map.get("customer_id").getRefTable());
        }

        @Test
        @DisplayName("提交里带了新值时：以提交的为准")
        void testSubmittedWins() {
            String json = ColumnMetaStore.rebuild(
                    List.of(withDict("status", "new_dict_type")), current);
            assertEquals("new_dict_type",
                    ColumnMetaStore.deserialize(json).get("status").getDictType());
        }

        @Test
        @DisplayName("🔴 列改名：元数据跟着改名走，不失联")
        void testRenameFollows() {
            TableColumnModel renamed = col("order_status_code").setOldColumnName("status");
            String json = ColumnMetaStore.rebuild(List.of(renamed), current);
            Map<String, ColumnMetaStore.ColumnMeta> map = ColumnMetaStore.deserialize(json);
            assertEquals("order_status", map.get("order_status_code").getDictType(),
                    "改名后应当能按新列名找到原配置");
            assertNull(map.get("status"), "旧列名不该残留");
        }

        @Test
        @DisplayName("🔴 列删除：元数据一并清理，否则会附到将来的同名新列上")
        void testDropIsPruned() {
            // 提交的完整列表里没有 customer_id，即它被删了
            String json = ColumnMetaStore.rebuild(List.of(col("status")), current);
            Map<String, ColumnMetaStore.ColumnMeta> map = ColumnMetaStore.deserialize(json);
            assertNull(map.get("customer_id"), "已删列的元数据必须清掉");
            assertNotNull(map.get("status"));
        }

        @Test
        @DisplayName("全部清空后返回 null")
        void testAllRemoved() {
            assertNull(ColumnMetaStore.rebuild(List.of(col("order_no")), current));
        }

        @Test
        @DisplayName("原本没有元数据时，改表也不会凭空造出来")
        void testNoMetaStaysNull() {
            assertNull(ColumnMetaStore.rebuild(List.of(col("a_col"), col("b_col")), null));
        }
    }

    @Nested
    @DisplayName("prune 修复存量")
    class Prune {

        @Test
        @DisplayName("清掉不存在的列")
        void testPrune() {
            String json = ColumnMetaStore.serialize(List.of(
                    withDict("status", "order_status"),
                    withRef("obsolete_col", "biz_x", "id")));
            String pruned = ColumnMetaStore.prune(json, Set.of("status", "order_no"));
            Map<String, ColumnMetaStore.ColumnMeta> map = ColumnMetaStore.deserialize(pruned);
            assertNotNull(map.get("status"));
            assertNull(map.get("obsolete_col"));
        }

        @Test
        @DisplayName("全清掉时返回 null")
        void testPruneAll() {
            String json = ColumnMetaStore.serialize(List.of(withDict("status", "order_status")));
            assertNull(ColumnMetaStore.prune(json, Set.of("other_col")));
            assertNull(ColumnMetaStore.prune(null, Set.of("status")));
        }
    }

    @Nested
    @DisplayName("🔴 业务语义不参与结构比对与 DDL")
    class NotStructural {

        @Test
        @DisplayName("只改字典/关联：不产生任何 ALTER —— 它们不是存储结构")
        void testMetaChangeProducesNoAlter() {
            TableColumnModel before = col("status").setDictType("old_dict")
                    .setRefTable("biz_a").setRefColumn("id");
            TableColumnModel after = col("status").setDictType("new_dict")
                    .setRefTable("biz_b").setRefColumn("code");
            TableDiffer.Diff diff = TableDiffer.diff(List.of(after), List.of(before));
            assertTrue(diff.getModifyColumns().isEmpty(),
                    "业务语义变化不该生成 MODIFY：" + diff.getModifyColumns());
            assertTrue(diff.getAddColumns().isEmpty());
            assertTrue(diff.getDropColumns().isEmpty());
        }

        @Test
        @DisplayName("生成的 DDL 里不出现字典/关联信息")
        void testMetaNotInDdl() {
            String ddl = MysqlDdlBuilder.buildColumnDefinition(
                    new TableColumnModel().setColumnName("status").setColumnType("TINYINT")
                            .setNullable(false).setPrimaryKey(false).setColumnComment("状态")
                            .setDictType("order_status").setRefTable("biz_customer").setRefColumn("id"));
            assertTrue(!ddl.contains("order_status") && !ddl.contains("biz_customer"), ddl);
        }
    }
}
