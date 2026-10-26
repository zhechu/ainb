package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelCreateTool;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelImportTool;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelUpdateTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.agent.framework.agent.core.manifest.ManifestTestFixtures.context;
import static cn.enlev.ainb.module.agent.framework.agent.core.manifest.ManifestTestFixtures.toolNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据模型三个抽取器的单测（S2-c2）：{@code datamodel_create / update / import}
 *
 * <h3>🔴 这三条钉的是三种「不可逆程度完全不同」的表操作必须能区分开</h3>
 * 建表（IRREVERSIBLE，DDL 执行后不假装能回滚）、改表（IRREVERSIBLE，删列会带走数据）、
 * 登记表（LOW，只加一条元数据）。若清单把三者说成一个样子，
 * 审批人就无法据以判断该不该点同意 —— 而「登记已有表」被显示成「新建数据表」
 * 会让他以为要动库，从而拒掉一个无害操作。反过来更糟。
 *
 * <h3>summary 一律逐字断言</h3>
 * 这几句话是聊天确认卡与 BPM 审批详情上唯一必读的一行，
 * 措辞漂了不会有任何测试外的信号。用「包含某几个词」来断言等于没断言：
 * 「新建数据表 biz_x」漂成「新建数据表（表名未填）」时，包含「新建数据表」照样成立。
 *
 * @author 走进新时代
 */
class DataModelChangeExtractorTest {

    // ==================== datamodel_create ====================

    @Nested
    @DisplayName("datamodel_create → TABLE_CREATE")
    class Create {

        private final DataModelCreateChangeExtractor extractor = new DataModelCreateChangeExtractor();

        @Test
        @DisplayName("完整建表定义：表名、注释、列（含 scale）、索引都读出来，summary 逐字对得上")
        void readsWholeCreateDefinition() {
            Map<String, Object> amount = new LinkedHashMap<>();
            amount.put("columnName", "amount");
            amount.put("columnComment", "金额");
            amount.put("columnType", "DECIMAL");
            amount.put("length", 10);
            amount.put("scale", 2);
            amount.put("nullable", false);
            Map<String, Object> name = new LinkedHashMap<>();
            name.put("columnName", "emp_name");
            name.put("columnComment", "姓名");
            name.put("columnType", "STRING");
            name.put("length", 64);

            Map<String, Object> index = new LinkedHashMap<>();
            index.put("indexName", "idx_emp_name");
            index.put("indexType", "NORMAL");
            index.put("columnNames", List.of("emp_name"));

            Map<String, Object> model = new LinkedHashMap<>();
            model.put("tableName", "biz_employee");
            model.put("tableComment", "员工信息");
            model.put("modelName", "员工");
            model.put("columns", List.of(name, amount));
            model.put("indexes", List.of(index));

            NodeDTO node = toolNode("make", DataModelCreateTool.NAME, Map.of("model", model));
            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(ChangeKind.TABLE_CREATE, item.getKind());
            assertEquals("biz_employee", item.getTarget().get("tableName"));
            assertEquals("员工信息", item.getTarget().get("tableComment"));
            assertEquals("员工", item.getDetail().get("modelName"));
            assertEquals("新建数据表 biz_employee（员工信息），共 2 列、1 个索引", item.getSummary());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) item.getDetail().get("columns");
            assertEquals(2, columns.size());
            // scale 必须跟着 length 一起出来：只报「精度 10」的话，审批人看不出小数位是 2
            assertEquals(10, columns.get(1).get("length"));
            assertEquals(2, columns.get(1).get("scale"));
            assertEquals(false, columns.get(1).get("nullable"));
            assertNull(columns.get(0).get("scale"), "没写 scale 的列就该是 null，不要编一个 0 出来");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> indexes = (List<Map<String, Object>>) item.getDetail().get("indexes");
            assertEquals(List.of("emp_name"), indexes.get(0).get("columnNames"));
        }

        @Test
        @DisplayName("🔴 model 被模型序列化成 JSON 串时照样认得（与 resolveCreate 同口径，否则清单会谎报表名未填）")
        void acceptsModelSerializedAsJsonString() {
            String modelJson = JSONUtil.toJsonStr(Map.of("tableName", "biz_employee",
                    "tableComment", "员工信息"));
            NodeDTO node = toolNode("make", DataModelCreateTool.NAME,
                    Map.of("model", modelJson));
            ChangeItem item = extractor.extract(node, context(node));

            assertEquals("biz_employee", item.getTarget().get("tableName"));
            assertEquals("新建数据表 biz_employee（员工信息），共 0 列", item.getSummary());
        }

        @Test
        @DisplayName("🔴 args.model 整个缺失时说「没有给出建表定义」，而不是误诊成「表名未填」")
        void missingModelIsNotMisdiagnosedAsMissingTableName() {
            NodeDTO noArgs = toolNode("make", DataModelCreateTool.NAME, (Map<String, Object>) null);
            assertEquals("新建数据表。⚠️ 没有给出建表定义（args.model 缺失或不是对象），执行时会被拒",
                    extractor.extract(noArgs, context(noArgs)).getSummary());

            // args 有、但 model 根本不是对象（写成了一个裸字符串）：同一句话，且不抛
            NodeDTO wrongShape = toolNode("make", DataModelCreateTool.NAME,
                    Map.of("model", "biz_employee"));
            assertEquals("新建数据表。⚠️ 没有给出建表定义（args.model 缺失或不是对象），执行时会被拒",
                    extractor.extract(wrongShape, context(wrongShape)).getSummary());
        }

        /**
         * 上一条那两种形态（args 整个缺失 / model 是个裸字符串）执行期<b>确实</b>会被拒，
         * 说「执行时会被拒」是实话。但「model 整体是占位符」是<b>第三种</b>形态，
         * 执行期跑得通（{@code StateTemplateResolver#resolve} 把脚本产出的对象渲染成 JSON 串，
         * {@code resolveCreate} 的 {@code JSONUtil.parseObj} 两种形态都收）——
         * 共用上面那句话就是清单在凭空造一个缺陷（缺陷 D4-17，run11 示例 55）。
         *
         * <p>🔴 这一条与上一条必须<b>同时</b>存在：它们钉的是「三种形态三句话」，
         * 只留一条的话，把两个分支合并成一句也照样全绿。
         */
        @Test
        @DisplayName("🔴 args.model 整体是占位符时说「无从复核」，绝不跟着说「执行时会被拒」")
        void pendingModelIsNotCalledInvalid() {
            NodeDTO node = toolNode("make", DataModelCreateTool.NAME,
                    Map.of("model", "{{$.prepared_args.model}}"));

            ChangeItem item = extractor.extract(node, context(node));

            String summary = item.getSummary();
            assertTrue(summary.contains("{{$.prepared_args.model}}"), summary);
            assertTrue(summary.contains("运行期"), summary);
            assertFalse(summary.contains("执行时会被拒"), summary);
            assertFalse(summary.contains("缺失或不是对象"), summary);
            // 表名读不出来就不编一个，与「表名未填」那条同一纪律
            assertNull(item.getTarget().get("tableName"));
        }

        @Test
        @DisplayName("有 model 但没写 tableName 时，说「表名未填」而不是编一个名字")
        void blankTableNameIsSaidOutLoud() {
            NodeDTO node = toolNode("make", DataModelCreateTool.NAME,
                    Map.of("model", Map.of("columns", List.of(Map.of("columnName", "c1")))));
            ChangeItem item = extractor.extract(node, context(node));

            assertNull(item.getTarget().get("tableName"));
            // 🔴 「新建数据表（表名未填）」中间不留空格：全角括号前留白会读成一个模板洞，
            //    规则与强制点见 SummarySpacingTest
            assertEquals("新建数据表（表名未填），共 1 列", item.getSummary());
        }
    }

    // ==================== datamodel_update ====================

    @Nested
    @DisplayName("datamodel_update → TABLE_ALTER")
    class Update {

        private final DataModelUpdateChangeExtractor extractor = new DataModelUpdateChangeExtractor();

        @Test
        @DisplayName("加列/改列/删列三组分别计数，改列与删列要把列名说出来")
        void listsAllThreeColumnChanges() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("tableName", "biz_employee");
            args.put("addColumns", List.of(
                    Map.of("columnName", "email", "columnType", "STRING", "length", 128),
                    Map.of("columnName", "salary", "columnType", "DECIMAL", "length", 12, "scale", 2)));
            args.put("modifyColumns", List.of(
                    Map.of("columnName", "emp_name", "length", 128)));
            args.put("dropColumns", List.of("old_col"));

            NodeDTO node = toolNode("alter", DataModelUpdateTool.NAME, args);
            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(ChangeKind.TABLE_ALTER, item.getKind());
            assertEquals("biz_employee", item.getTarget().get("tableName"));
            assertEquals("修改表 biz_employee 的结构：新增 2 列、修改 1 列（emp_name）、删除 1 列（old_col）",
                    item.getSummary());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> added = (List<Map<String, Object>>) item.getDetail().get("addColumns");
            assertEquals(2, added.size());
            assertEquals(128, added.get(0).get("length"));
            assertEquals(2, added.get(1).get("scale"));
            assertEquals(List.of("old_col"), item.getDetail().get("dropColumns"));
        }

        @Test
        @DisplayName("🔴 改列时原样列出 DSL 里写的键，不按「工具认哪些键」过滤 —— 打错的键名要能被看见")
        void modifyColumnsShowRawDslKeys() {
            // lenght 是 length 的手误。执行期 applyChange 只认 columnComment/columnType/length/scale/
            // nullable/defaultValue，多出来的键被静默丢弃 —— 也就是「改了但没改」。
            // 抽取器若照那份白名单过滤，清单上就只剩一个空的 changedKeys，手误彻底隐形；
            // 原样列出来，审批人一眼就能看到 lenght 这个不存在的键
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("columnName", "emp_name");
            column.put("lenght", 128);
            column.put("columnComment", "姓名");
            NodeDTO node = toolNode("alter", DataModelUpdateTool.NAME,
                    Map.of("tableName", "biz_employee", "modifyColumns", List.of(column)));

            ChangeItem item = extractor.extract(node, context(node));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> modified =
                    (List<Map<String, Object>>) item.getDetail().get("modifyColumns");
            assertEquals(List.of("lenght", "columnComment"), modified.get(0).get("changedKeys"));
        }

        @Test
        @DisplayName("三组列变更全空时如实说「未看出要改什么」，不假装这是一次无害的改表")
        void allThreeEmptyIsSaidOutLoud() {
            NodeDTO node = toolNode("alter", DataModelUpdateTool.NAME,
                    Map.of("tableName", "biz_employee"));
            ChangeItem item = extractor.extract(node, context(node));

            assertEquals("修改表 biz_employee 的结构：未看出要改什么（三组列变更都是空的）",
                    item.getSummary());
        }
    }

    // ==================== datamodel_import ====================

    @Nested
    @DisplayName("datamodel_import → TABLE_REGISTER")
    class Import {

        private final DataModelImportChangeExtractor extractor = new DataModelImportChangeExtractor();

        @Test
        @DisplayName("登记已有表：列出表名，且 kind 是 TABLE_REGISTER 而不是 TABLE_CREATE")
        void registersExistingTables() {
            NodeDTO node = toolNode("register", DataModelImportTool.NAME,
                    Map.of("tableNames", List.of("biz_a", "biz_b", "biz_c")));
            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(ChangeKind.TABLE_REGISTER, item.getKind());
            assertEquals(3, item.getDetail().get("count"));
            assertEquals("登记 3 张已有表：biz_a、biz_b、biz_c", item.getSummary());
        }

        @Test
        @DisplayName("🔴 tableNames 写成逗号串（执行期会被拒）时如实说没认出来，不静默当成 0 张")
        void tableNamesAsStringIsReportedNotSilentlyDropped() {
            NodeDTO node = toolNode("register", DataModelImportTool.NAME,
                    Map.of("tableNames", "biz_a,biz_b"));
            ChangeItem item = extractor.extract(node, context(node));

            assertTrue(item.getSummary().startsWith("登记已有表（未认出表名"), item.getSummary());
            assertEquals(0, item.getDetail().get("count"));
        }

        @Test
        @DisplayName("数字表名（模型偶尔会给出编号）也认，统一按字符串展示")
        void numericTableNamesAreAccepted() {
            NodeDTO node = toolNode("register", DataModelImportTool.NAME,
                    Map.of("tableNames", new ArrayList<>(List.of(1001, 1002))));
            ChangeItem item = extractor.extract(node, context(node));

            assertEquals("登记 2 张已有表：1001、1002", item.getSummary());
        }
    }

}
