package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataCreateTool;
import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataDeleteTool;
import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataParamResolver;
import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataUpdateTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
 * 业务数据三件套的单测（S2-c2）：{@code form_data_create / update / delete}
 *
 * <h3>🔴 这里钉的两件事，都不是「功能对不对」而是「会不会说漏 / 说错」</h3>
 * <ol>
 *   <li><b>不泄露业务值</b>。清单会被冻结进 BPM 流程变量、渲染给审批人看，
 *       而 {@code data} 里装的正是用户提交的业务数据。
 *       {@link #neverCarriesBusinessValues} 把整份 {@link ChangeItem} 序列化成 JSON 再搜值 ——
 *       只断言「fields 里没有值」是不够的，值也可能从 summary、target 或将来新加的某个 detail 键漏出去。</li>
 *   <li><b>把三种误读写死在措辞里</b>：update 是 PATCH 还是全量覆盖（两种误判都致命）、
 *       delete 是不是不可逆（其实是逻辑删除）、
 *       {@code data} 里的 {@code {{$.x}}} 到底会不会被替换（不会，会当普通文字写进库）。</li>
 * </ol>
 *
 * @author 走进新时代
 */
class FormDataChangeExtractorTest {

    /** 一个真实形态的身份证号，用来验证它不会顺着清单流出去 */
    private static final String SECRET_ID_CARD = "110101199001011234";
    private static final String SECRET_PHONE = "13800001234";

    // ==================== form_data_create ====================

    @Nested
    @DisplayName("form_data_create → DATA_WRITE（新增）")
    class Create {

        private final FormDataCreateChangeExtractor extractor = new FormDataCreateChangeExtractor();

        @Test
        @DisplayName("🔴 只报字段名，业务值一个都不许出现在整份变更项里")
        void neverCarriesBusinessValues() {
            NodeDTO node = toolNode("write", FormDataCreateTool.NAME, Map.of(
                    FormDataParamResolver.PARAM_CODE, "employee",
                    FormDataParamResolver.PARAM_DATA, sensitiveData()));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(ChangeKind.DATA_WRITE, item.getKind());
            assertEquals("新增", item.getTarget().get("operation"));
            assertEquals(List.of("emp_name", "id_card", "phone", "items"),
                    item.getDetail().get("fields"));
            assertEquals(List.of("items"), item.getDetail().get("subTableFields"));
            assertEquals("在表单 employee 新增 1 条记录，写入 4 个字段"
                    + "（emp_name、id_card、phone、items），其中 1 个是子表明细（items）", item.getSummary());

            // 整份变更项序列化后搜值：漏在任何新加的键里都能被这条抓到
            String json = JsonUtils.toJsonString(item);
            assertFalse(json.contains(SECRET_ID_CARD), "身份证号漏进清单了：" + json);
            assertFalse(json.contains(SECRET_PHONE), "手机号漏进清单了：" + json);
            assertFalse(json.contains("张三"), "姓名值漏进清单了：" + json);
        }

        @Test
        @DisplayName("🔴 data 里的 {{$.x}} 运行期不会被替换，必须在写入前说出来")
        void warnsAboutNestedPlaceholderThatWillNotBeResolved() {
            Map<String, Object> data = new LinkedHashMap<>();
            // 顶层字符串参数会被 StateTemplateResolver 替换，data 里的不会 —— 这一行会被原样写进库
            data.put("emp_name", "{{$.start.name}}");
            data.put("dept", "研发部");
            data.put("items", List.of(Map.of("sku", "{{$.start.sku}}", "qty", 2)));
            NodeDTO node = toolNode("write", FormDataCreateTool.NAME, Map.of(
                    FormDataParamResolver.PARAM_CODE, "employee",
                    FormDataParamResolver.PARAM_DATA, data));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(List.of("emp_name", "items"), item.getDetail().get("unresolvedPlaceholders"),
                    "嵌在子表明细里的占位符也要被抓到");
            assertEquals("在表单 employee 新增 1 条记录，写入 3 个字段（emp_name、dept、items），"
                            + "其中 1 个是子表明细（items）。"
                            + "⚠️ 字段 emp_name、items 的值还是 {{…}} 占位符，"
                            + "运行期不会被替换，会当成普通文字原样写进库",
                    item.getSummary());
        }

        @Test
        @DisplayName("data 被模型序列化成 JSON 串时照样认得（与 resolveData 同口径）")
        void acceptsDataSerializedAsJsonString() {
            NodeDTO node = toolNode("write", FormDataCreateTool.NAME, Map.of(
                    FormDataParamResolver.PARAM_CODE, "employee",
                    FormDataParamResolver.PARAM_DATA, "{\"emp_name\":\"张三\",\"dept\":\"研发部\"}"));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(List.of("emp_name", "dept"), item.getDetail().get("fields"));
            assertEquals("在表单 employee 新增 1 条记录，写入 2 个字段（emp_name、dept）", item.getSummary());
        }

        @Test
        @DisplayName("超过 5 个字段时 summary 折成「等 N 个」，但 detail 里给全量")
        void foldsLongFieldListsInSummaryOnly() {
            Map<String, Object> data = new LinkedHashMap<>();
            for (int i = 1; i <= 7; i++) {
                data.put("f" + i, "v" + i);
            }
            NodeDTO node = toolNode("write", FormDataCreateTool.NAME, Map.of(
                    FormDataParamResolver.PARAM_CODE, "employee",
                    FormDataParamResolver.PARAM_DATA, data));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(7, item.getDetail().get("fieldCount"));
            assertEquals(7, ((List<?>) item.getDetail().get("fields")).size(), "detail 里必须给全量");
            assertEquals("在表单 employee 新增 1 条记录，写入 7 个字段（f1、f2、f3、f4、f5 等 7 个）",
                    item.getSummary());
        }

        @Test
        @DisplayName("data 缺失时如实说「执行时会被拒」，不显示成一次成功的写入")
        void missingDataIsSaidOutLoud() {
            NodeDTO node = toolNode("write", FormDataCreateTool.NAME,
                    Map.of(FormDataParamResolver.PARAM_CODE, "employee"));
            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(List.of(), item.getDetail().get("fields"));
            assertEquals("在表单 employee 新增 1 条记录。⚠️ 没有认出任何要写入的字段，"
                    + "执行时会被拒（data 缺失、为空或不是合法对象）", item.getSummary());
        }

        @Test
        @DisplayName("编码未填时说「编码未填」，不留一个带空格的模板洞；编码是占位符时标明待定")
        void codeSlotHasTwoDistinctDegenerateForms() {
            NodeDTO missing = toolNode("write", FormDataCreateTool.NAME, Map.of());
            assertEquals("在表单（编码未填）新增 1 条记录。⚠️ 没有认出任何要写入的字段，"
                            + "执行时会被拒（data 缺失、为空或不是合法对象）",
                    extractor.extract(missing, context(missing)).getSummary());

            NodeDTO pending = toolNode("write", FormDataCreateTool.NAME, Map.of(
                    FormDataParamResolver.PARAM_CODE, "{{$.start.formCode}}",
                    FormDataParamResolver.PARAM_DATA, Map.of("emp_name", "张三")));
            ChangeItem item = extractor.extract(pending, context(pending));
            assertEquals(Boolean.TRUE, item.getDetail().get("codePending"));
            assertTrue(item.getSummary().contains("{{$.start.formCode}}"),
                    "照字面显示占位符，不要编一个编码出来：" + item.getSummary());
        }

        private Map<String, Object> sensitiveData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("emp_name", "张三");
            data.put("id_card", SECRET_ID_CARD);
            data.put("phone", SECRET_PHONE);
            data.put("items", List.of(Map.of("sku", "A-1", "qty", 2, "price", 199)));
            return data;
        }
    }

    // ==================== form_data_update ====================

    @Nested
    @DisplayName("form_data_update → DATA_WRITE（修改）")
    class Update {

        private final FormDataUpdateChangeExtractor extractor = new FormDataUpdateChangeExtractor();

        @Test
        @DisplayName("🔴 PATCH 语义必须写出来：只改列出的字段，其余保持原值")
        void statesPatchSemantics() {
            NodeDTO node = toolNode("patch", FormDataUpdateTool.NAME, Map.of(
                    FormDataParamResolver.PARAM_CODE, "employee",
                    FormDataParamResolver.PARAM_PK, "1024",
                    FormDataParamResolver.PARAM_DATA, data("emp_name", "dept")));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals("修改", item.getTarget().get("operation"));
            assertEquals("1024", item.getTarget().get("pk"));
            assertEquals("只改上面列出的字段，未提到的字段保持原值不变（不会被清空）",
                    item.getDetail().get("patchNote"));
            assertEquals("修改表单 employee 中记录 1024，改动 2 个字段（emp_name、dept）；"
                    + "未提到的字段保持原值", item.getSummary());
        }

        @Test
        @DisplayName("🔴 主键认别名 id（resolvePk 两种都收），不认就会说「主键未填」而实际能跑通")
        void honorsTheIdAliasForPk() {
            NodeDTO byPk = toolNode("patch", FormDataUpdateTool.NAME, Map.of(
                    FormDataParamResolver.PARAM_CODE, "employee",
                    FormDataParamResolver.PARAM_PK, "1024",
                    FormDataParamResolver.PARAM_DATA, data("emp_name")));
            NodeDTO byId = toolNode("patch", FormDataUpdateTool.NAME, Map.of(
                    FormDataParamResolver.PARAM_CODE, "employee",
                    FormDataParamResolver.PARAM_ID, "1024",
                    FormDataParamResolver.PARAM_DATA, data("emp_name")));

            assertEquals("1024", extractor.extract(byPk, context(byPk)).getTarget().get("pk"));
            assertEquals("1024", extractor.extract(byId, context(byId)).getTarget().get("pk"));
        }

        @Test
        @DisplayName("主键是 {{$.createOut.id}} 时说清「改的是本流程刚写的那行」，不是库里某条既有数据")
        void pendingPkIsSaidToBeRuntime() {
            NodeDTO node = toolNode("patch", FormDataUpdateTool.NAME, Map.of(
                    FormDataParamResolver.PARAM_CODE, "employee",
                    FormDataParamResolver.PARAM_PK, "{{$.createOut.id}}",
                    FormDataParamResolver.PARAM_DATA, data("emp_name")));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(Boolean.TRUE, item.getDetail().get("pkPending"));
            assertEquals("修改表单 employee 中记录 {{$.createOut.id}}（运行期才确定），"
                    + "改动 1 个字段（emp_name）；未提到的字段保持原值", item.getSummary());
        }

        @Test
        @DisplayName("主键没填时警告，但字段照列 —— 两件事都缺时一句话里都说清")
        void missingPkIsSaidOutLoud() {
            NodeDTO noPk = toolNode("patch", FormDataUpdateTool.NAME, Map.of(
                    FormDataParamResolver.PARAM_CODE, "employee",
                    FormDataParamResolver.PARAM_DATA, data("emp_name")));
            assertEquals("修改表单 employee 中一条记录，改动 1 个字段（emp_name）；"
                            + "未提到的字段保持原值。⚠️ 主键未填，执行时会被拒",
                    extractor.extract(noPk, context(noPk)).getSummary());

            NodeDTO nothing = toolNode("patch", FormDataUpdateTool.NAME, Map.of());
            assertEquals("修改表单（编码未填）中一条记录。⚠️ 没有认出任何要改的字段，"
                            + "执行时会被拒（data 缺失、为空或不是合法对象）；主键也没填",
                    extractor.extract(nothing, context(nothing)).getSummary());
        }

        @Test
        @DisplayName("data 里的占位符同样不会被替换，警告措辞与 create 共用一份")
        void warnsAboutNestedPlaceholder() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("emp_name", "{{$.start.name}}");
            NodeDTO node = toolNode("patch", FormDataUpdateTool.NAME, Map.of(
                    FormDataParamResolver.PARAM_CODE, "employee",
                    FormDataParamResolver.PARAM_PK, "1024",
                    FormDataParamResolver.PARAM_DATA, data));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(List.of("emp_name"), item.getDetail().get("unresolvedPlaceholders"));
            assertTrue(item.getSummary().endsWith("⚠️ 字段 emp_name 的值还是 {{…}} 占位符，"
                    + "运行期不会被替换，会当成普通文字原样写进库"), item.getSummary());
        }
    }

    // ==================== form_data_delete ====================

    @Nested
    @DisplayName("form_data_delete → DATA_WRITE（删除）")
    class Delete {

        private final FormDataDeleteChangeExtractor extractor = new FormDataDeleteChangeExtractor();

        @Test
        @DisplayName("🔴「删除」这个词后面必须立刻跟着「逻辑删除、可恢复」")
        void saysLogicalDeleteRightAfterTheWordDelete() {
            NodeDTO node = toolNode("drop", FormDataDeleteTool.NAME, Map.of(
                    FormDataParamResolver.PARAM_CODE, "employee",
                    FormDataParamResolver.PARAM_PK, "1024"));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals("删除", item.getTarget().get("operation"));
            assertEquals("逻辑删除：数据仍在库里、标记为已删除，可恢复", item.getDetail().get("deleteNote"));
            assertEquals("删除表单 employee 中记录 1024，逻辑删除、数据仍在库里、可恢复",
                    item.getSummary());
            assertNull(item.getDetail().get("fields"), "删除不读 data，不要凭空列一堆字段出来");
        }

        @Test
        @DisplayName("主键是占位符时，「运行期才确定」与「逻辑删除」两句话不能挤成两对括号")
        void pendingPkDoesNotCollideWithTheDeleteNote() {
            NodeDTO node = toolNode("drop", FormDataDeleteTool.NAME, Map.of(
                    FormDataParamResolver.PARAM_CODE, "employee",
                    FormDataParamResolver.PARAM_PK, "{{$.createOut.id}}"));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals("删除表单 employee 中记录 {{$.createOut.id}}（运行期才确定），"
                    + "逻辑删除、数据仍在库里、可恢复", item.getSummary());
        }

        @Test
        @DisplayName("主键没填时不再说「可恢复」—— 那句话说的是某一条具体记录")
        void missingPkDropsTheRecoverableClaim() {
            NodeDTO node = toolNode("drop", FormDataDeleteTool.NAME,
                    Map.of(FormDataParamResolver.PARAM_CODE, "employee"));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals("删除表单 employee 中一条记录。⚠️ 主键未填，执行时会被拒", item.getSummary());
        }
    }

    // ==================== 三者横向对照 ====================

    @Test
    @DisplayName("🔴 三个工具的 kind 都是 DATA_WRITE，故 operation 是唯一的区分点，必须互不相同")
    void theThreeOperationsAreDistinguishable() {
        Map<String, Object> args = Map.of(FormDataParamResolver.PARAM_CODE, "employee",
                FormDataParamResolver.PARAM_PK, "1024",
                FormDataParamResolver.PARAM_DATA, data("emp_name"));
        NodeDTO create = toolNode("c", FormDataCreateTool.NAME, args);
        NodeDTO update = toolNode("u", FormDataUpdateTool.NAME, args);
        NodeDTO delete = toolNode("d", FormDataDeleteTool.NAME, args);
        ManifestContext ctx = context(create, update, delete);

        assertEquals(ChangeKind.DATA_WRITE,
                new FormDataCreateChangeExtractor().extract(create, ctx).getKind());
        assertEquals(List.of("新增", "修改", "删除"), List.of(
                new FormDataCreateChangeExtractor().extract(create, ctx).getTarget().get("operation"),
                new FormDataUpdateChangeExtractor().extract(update, ctx).getTarget().get("operation"),
                new FormDataDeleteChangeExtractor().extract(delete, ctx).getTarget().get("operation")));
    }

    private static Map<String, Object> data(String... fields) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String field : fields) {
            data.put(field, "v");
        }
        return data;
    }

}
