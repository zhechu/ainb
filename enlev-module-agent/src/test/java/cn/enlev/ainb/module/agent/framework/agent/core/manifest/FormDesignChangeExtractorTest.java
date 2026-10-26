package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.form.FormPublishBundleTool;
import cn.enlev.ainb.module.agent.tool.impl.form.FormSaveDraftTool;
import cn.enlev.ainb.module.agent.tool.impl.form.FormUpdateTool;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.agent.framework.agent.core.manifest.ManifestTestFixtures.context;
import static cn.enlev.ainb.module.agent.framework.agent.core.manifest.ManifestTestFixtures.hitlNode;
import static cn.enlev.ainb.module.agent.framework.agent.core.manifest.ManifestTestFixtures.toolNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 表单三件套的单测（S2-c2）：{@code form_save_draft / form_update / form_publish_bundle}，
 * 外加它们共用的机器码翻译表 {@link ManifestLabels}
 *
 * <h3>🔴 这三条钉的是「配了但不生效且无提示」这一类缺陷的事前可见性</h3>
 * 三个动作都能「执行成功、返回 success、用户却看不到任何东西」：
 * <ul>
 *   <li>{@code form_update} 改完不发布 → 用户看到的仍是旧表单</li>
 *   <li>{@code form_publish_bundle} 不给 roleIds → 菜单只有超级管理员可见</li>
 *   <li>{@code form_save_draft} 存的只是草稿 → 不发布同样什么都看不到</li>
 * </ul>
 * 三者都没有异常、没有错误码，执行期日志一片绿。清单是唯一能在<b>点同意之前</b>说出来的地方，
 * 所以这几句警告的措辞要逐字钉住 —— 漂成一句更含糊的话，测试外不会有任何信号。
 *
 * @author 走进新时代
 */
class FormDesignChangeExtractorTest {

    // ==================== form_save_draft ====================

    @Nested
    @DisplayName("form_save_draft → FORM_CREATE")
    class SaveDraft {

        private final FormSaveDraftChangeExtractor extractor = new FormSaveDraftChangeExtractor();

        @Test
        @DisplayName("控件与操作符的机器码就地翻成中文，可查询字段单独点出来")
        void translatesMachineCodesAndListsSearchableFields() {
            FormDesignApi api = mock(FormDesignApi.class);
            when(api.getWidgetContracts()).thenReturn(List.of(
                    widget("input", "单行输入"), widget("select", "下拉选择")));
            when(api.getSearchOperatorList()).thenReturn(List.of(
                    operator("LIKE", "包含"), operator("EQ", "等于")));

            Map<String, Object> args = new LinkedHashMap<>();
            args.put(FormSaveDraftTool.PARAM_NAME, "员工信息");
            args.put(FormSaveDraftTool.PARAM_CODE, "employee");
            args.put(FormSaveDraftTool.PARAM_MAIN_TABLE, "biz_employee");
            args.put(FormSaveDraftTool.PARAM_FIELDS, List.of(
                    field("emp_name", "姓名", "input", true, "LIKE"),
                    field("dept", null, "select", true, "EQ"),
                    field("remark", "备注", "input", false, null)));
            NodeDTO node = toolNode("draft", FormSaveDraftTool.NAME, args);

            ChangeItem item = extractor.extract(node, context(api, node));

            assertEquals(ChangeKind.FORM_CREATE, item.getKind());
            assertEquals("employee", item.getTarget().get("code"));
            assertEquals("biz_employee", item.getTarget().get("mainTable"));
            assertEquals(3, item.getDetail().get("fieldCount"));
            // dept 没写 title，落到字段名 —— 查询区里显示空白比显示字段名更让人摸不着头脑
            assertEquals(List.of("姓名", "dept"), item.getDetail().get("searchableFields"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) item.getDetail().get("fields");
            assertEquals("单行输入", fields.get(0).get("widgetLabel"));
            assertEquals("包含", fields.get(0).get("operatorLabel"));
            assertEquals("下拉选择", fields.get(1).get("widgetLabel"));
            // 机器码本身也要留着：翻译表漂了的时候，用户至少还能拿机器码去查
            assertEquals("input", fields.get(0).get("widget"));

            assertEquals("新建表单「员工信息」（employee），绑定表 biz_employee，共 3 个字段，"
                    + "其中 2 个进查询区（姓名、dept）", item.getSummary());
        }

        @Test
        @DisplayName("🔴 拿不到 infra 出口时原样显示机器码，不抛异常、也不显示成 null")
        void fallsBackToMachineCodeWithoutApi() {
            NodeDTO node = toolNode("draft", FormSaveDraftTool.NAME,
                    Map.of(FormSaveDraftTool.PARAM_NAME, "员工信息",
                            FormSaveDraftTool.PARAM_FIELDS, List.of(
                                    field("emp_name", "姓名", "select", true, "LIKE"))));

            ChangeItem item = extractor.extract(node, context(node));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) item.getDetail().get("fields");
            assertEquals("select", fields.get(0).get("widgetLabel"));
            assertEquals("LIKE", fields.get(0).get("operatorLabel"));
        }

        @Test
        @DisplayName("控件没写时 widgetLabel 是 null，而不是空串或某个默认控件名")
        void missingWidgetYieldsNullLabel() {
            Map<String, Object> bare = new LinkedHashMap<>();
            bare.put("field", "emp_name");
            bare.put("title", "姓名");
            NodeDTO node = toolNode("draft", FormSaveDraftTool.NAME,
                    Map.of(FormSaveDraftTool.PARAM_FIELDS, List.of(bare)));

            ChangeItem item = extractor.extract(node, context(node));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) item.getDetail().get("fields");
            assertNull(fields.get(0).get("widget"));
            assertNull(fields.get(0).get("widgetLabel"));
            assertNull(fields.get(0).get("operatorLabel"));
            assertEquals(List.of(), item.getDetail().get("searchableFields"),
                    "searchable 没写就是没写，不要当成 false 也不要当成 true");
        }

        @Test
        @DisplayName("名称未填时如实说出来，不编一个名字")
        void missingNameIsSaidOutLoud() {
            NodeDTO node = toolNode("draft", FormSaveDraftTool.NAME, Map.of());
            assertEquals("新建表单（名称未填），共 0 个字段",
                    extractor.extract(node, context(node)).getSummary());
        }

        private Map<String, Object> field(String name, String title, String widget,
                                          boolean searchable, String searchOperator) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("field", name);
            if (title != null) {
                field.put("title", title);
            }
            field.put("widget", widget);
            field.put("required", true);
            field.put("searchable", searchable);
            if (searchOperator != null) {
                field.put("operator", searchOperator);
            }
            return field;
        }
    }

    // ==================== form_update ====================

    @Nested
    @DisplayName("form_update → FORM_UPDATE")
    class Update {

        private final FormUpdateChangeExtractor extractor = new FormUpdateChangeExtractor();

        @Test
        @DisplayName("🔴 下游没有发布节点时必须警告「改完不会生效」")
        void warnsWhenNothingPublishesDownstream() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put(FormUpdateTool.PARAM_ID, "1024");
            // 🔴 用 LinkedHashMap 而不是 Map.of：Map.of 的迭代顺序随 JVM 实例变
            // （ImmutableCollections 的 SALT 每次启动都不同），而 patchKeys 要保持 DSL 里的书写顺序。
            // 用 Map.of 的话这条断言会时绿时红 —— 第一次跑绿了纯属运气
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("formConf", "{\"list\":[…]}");
            patch.put("listColumns", "[…]");
            args.put(FormUpdateTool.PARAM_PATCH, patch);
            args.put(FormUpdateTool.PARAM_CLEAR_FIELDS, List.of("printTemplate"));
            NodeDTO node = toolNode("update", FormUpdateTool.NAME, args);

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(ChangeKind.FORM_UPDATE, item.getKind());
            assertEquals(Boolean.TRUE, item.getDetail().get("needsRepublish"));
            assertEquals("修改表单 #1024：改动 formConf、listColumns；并清空 printTemplate。"
                    + "⚠️ 这条流程里它后面没有发布节点，改完不会生效，用户看到的仍是旧表单",
                    item.getSummary());
        }

        @Test
        @DisplayName("下游确有发布节点时闭嘴 —— 每次都喊「不会生效」等于没喊")
        void staysQuietWhenAPublishFollows() {
            NodeDTO update = toolNode("update", FormUpdateTool.NAME,
                    Map.of(FormUpdateTool.PARAM_ID, "1024",
                            FormUpdateTool.PARAM_PATCH, Map.of("formConf", "{}")));
            NodeDTO publish = toolNode("publish", FormPublishBundleTool.NAME,
                    Map.of(FormPublishBundleTool.PARAM_ID, "1024"));

            ChangeItem item = extractor.extract(update, context(update, publish));

            assertEquals(Boolean.FALSE, item.getDetail().get("needsRepublish"));
            assertEquals("修改表单 #1024：改动 formConf", item.getSummary());
        }

        @Test
        @DisplayName("🔴 只算「下游」：发布节点在上游、或下游只是个人工确认节点，都照旧警告")
        void onlyDownstreamCounts() {
            NodeDTO update = toolNode("update", FormUpdateTool.NAME,
                    Map.of(FormUpdateTool.PARAM_ID, "1024",
                            FormUpdateTool.PARAM_PATCH, Map.of("formConf", "{}")));
            // 发布在前、修改在后：先发的是旧定义，改完没人发
            NodeDTO publishFirst = toolNode("publish", FormPublishBundleTool.NAME,
                    Map.of(FormPublishBundleTool.PARAM_ID, "1024"));
            assertEquals(Boolean.TRUE,
                    extractor.extract(update, context(publishFirst, update)).getDetail().get("needsRepublish"));
            // hitl 节点会让人以为「有人确认过就生效了」，它并不会发布表单
            assertEquals(Boolean.TRUE,
                    extractor.extract(update, context(update, hitlNode("confirm")))
                            .getDetail().get("needsRepublish"));
        }

        @Test
        @DisplayName("patch 只列键名，绝不带值 —— 这份清单要能冻进 BPM 流程变量")
        void listsPatchKeysButNeverValues() {
            String hugeValue = "x".repeat(5000);
            NodeDTO node = toolNode("update", FormUpdateTool.NAME,
                    Map.of(FormUpdateTool.PARAM_ID, "1024",
                            FormUpdateTool.PARAM_PATCH, Map.of("formConf", hugeValue)));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(List.of("formConf"), item.getDetail().get("patchKeys"));
            assertFalse(item.getDetail().containsKey(FormUpdateTool.PARAM_PATCH),
                    "整份 patch 不能进清单");
            assertFalse(item.getSummary().contains("xxxx"), "summary 里也不能漏出值");
        }

        @Test
        @DisplayName("编号与 patch 都缺时如实说，不假装这是一次成功的修改")
        void missingIdAndPatchAreSaidOutLoud() {
            NodeDTO node = toolNode("update", FormUpdateTool.NAME, Map.of());
            ChangeItem item = extractor.extract(node, context(node));

            assertNull(item.getTarget().get("formId"));
            // 🔴 「修改表单（编号未填）」中间不留空格：全角括号前留白会读成一个模板洞，
            //    规则与强制点见 SummarySpacingTest
            assertEquals("修改表单（编号未填）：没有给出要改的项。"
                    + "⚠️ 这条流程里它后面没有发布节点，改完不会生效，用户看到的仍是旧表单",
                    item.getSummary());
        }

        // ==================== D4-20（迭代七第 08 轮 · 裁定 R-02）：改表也要能新增查询条件 ====================

        @Test
        @DisplayName("🔴 方案 §2.3 的判据：FORM_UPDATE 带 1 个可查询字段 → 查询条件通道必须非空")
        void aSearchableFieldInPatchReachesTheSearchChannel() {
            NodeDTO node = toolNode("update", FormUpdateTool.NAME,
                    Map.of(FormUpdateTool.PARAM_ID, "1024",
                            FormUpdateTool.PARAM_PATCH, Map.of("formFields", List.of(
                                    patchField("competitor_price", "竞对报价", "inputNumber", true, "BETWEEN")))));

            ChangeItem item = extractor.extract(node, context(node));

            // 修复前这里是空的：抽取器压根不看 patch 里的字段，
            // 于是前端「列表查询条件」那张表对 FORM_UPDATE 永远是空的（缺陷 D4-20）
            assertEquals(List.of("竞对报价"), item.getDetail().get("searchableFields"));
            Map<String, Object> row = firstRow(item);
            assertEquals(Boolean.TRUE, row.get("searchable"));
            assertEquals("inputNumber", row.get("widget"));
            assertEquals("BETWEEN", row.get("operator"));
            assertEquals(1, item.getDetail().get("fieldCount"));
            assertEquals("修改表单 #1024：改动 formFields；带 1 个字段进查询区（竞对报价）。"
                    + "⚠️ 这条流程里它后面没有发布节点，改完不会生效，用户看到的仍是旧表单",
                    item.getSummary());
        }

        @Test
        @DisplayName("patch.fields 不是可改字段（工具会直接报错），清单照样认 —— 要在点同意前看见它写歪了")
        void theIllegalFieldsKeyIsStillShown() {
            // 实测形态：2026-10-05 真跑出的 agent_workflow_def #467 就是这么写的
            NodeDTO node = toolNode("update", FormUpdateTool.NAME,
                    Map.of(FormUpdateTool.PARAM_ID, "1024",
                            FormUpdateTool.PARAM_PATCH, Map.of("fields", List.of(
                                    patchField("competitor_price", "竞对报价", "inputNumber", true, null)))));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(List.of("竞对报价"), item.getDetail().get("searchableFields"));
            assertTrue(item.getSummary().contains("改动 fields"), item.getSummary());
        }

        @Test
        @DisplayName("🔴 listConf.searchItems 也认 —— 那是改表加查询条件唯一「保存后真的生效」的路径")
        void searchItemsInsideListConfCount() {
            // listConf 在 DTO 上是 JSON 串，模型也照着写串；ManifestArgs.map 两种形态都收
            String listConf = "{\"columns\":[],\"searchItems\":["
                    + "{\"field\":\"visit_date\",\"label\":\"拜访日期\",\"widget\":\"DATE_RANGE\","
                    + "\"operator\":\"BETWEEN\"}]}";
            NodeDTO node = toolNode("update", FormUpdateTool.NAME,
                    Map.of(FormUpdateTool.PARAM_ID, "1024",
                            FormUpdateTool.PARAM_PATCH, Map.of("listConf", listConf)));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(List.of("拜访日期"), item.getDetail().get("searchableFields"));
            Map<String, Object> row = firstRow(item);
            assertEquals("DATE_RANGE", row.get("widget"));
            // 查询控件的机器码与表单控件不是一个目录，翻不出来就原样显示，不硬套表单控件表
            assertEquals("DATE_RANGE", row.get("widgetLabel"));
            assertEquals("BETWEEN", row.get("operatorLabel"));
            assertEquals("拜访日期", row.get("title"), "查询项用 label，字段行用 title，得在这里对齐");
        }

        @Test
        @DisplayName("同一个字段被 formFields 与 searchItems 各提一次 → 并成一行，searchable 取「或」")
        void theSameFieldFromTwoPlacesBecomesOneRow() {
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("formFields", List.of(patchField("visit_date", null, "datePicker", false, null)));
            patch.put("listConf", Map.of("searchItems", List.of(Map.of(
                    "field", "visit_date", "label", "拜访日期",
                    "widget", "DATE_RANGE", "operator", "BETWEEN"))));
            NodeDTO node = toolNode("update", FormUpdateTool.NAME,
                    Map.of(FormUpdateTool.PARAM_ID, "1024", FormUpdateTool.PARAM_PATCH, patch));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(1, item.getDetail().get("fieldCount"),
                    "同名字段出现两行，前端「查询条件」表会一行说是一行说否，自相矛盾");
            Map<String, Object> row = firstRow(item);
            assertEquals(Boolean.TRUE, row.get("searchable"), "查询项是「可查」的声明，不该被另一处的 false 抹掉");
            assertEquals("拜访日期", row.get("title"), "标题的空位由查询项的 label 补上");
            assertEquals("datePicker", row.get("widget"), "已有值就不被查询控件覆盖");
            assertEquals("BETWEEN", row.get("operator"), "操作符的空位由查询项补上");
            assertEquals(List.of("拜访日期"), item.getDetail().get("searchableFields"));
        }

        @Test
        @DisplayName("🔴 patch 整个写成占位符时不许说「没有给出要改的项」—— 那是把「我看不见」说成「没有内容」")
        void aPlaceholderPatchIsSaidOutLoudInsteadOfClaimingEmpty() {
            // 实测形态：2026-10-08 基线轮真跑出的 agent_workflow_def #566，
            // patch 由前置 prepare_patch 脚本节点算出
            NodeDTO node = toolNode("update", FormUpdateTool.NAME,
                    Map.of(FormUpdateTool.PARAM_ID, "1024",
                            FormUpdateTool.PARAM_PATCH, "{{$.patch_payload.patch}}"));

            ChangeItem item = extractor.extract(node, context(node));

            assertFalse(item.getSummary().contains("没有给出要改的项"), item.getSummary());
            assertEquals("要改的项由前置节点算出（{{$.patch_payload.patch}}），清单看不到具体改了什么",
                    item.getDetail().get("patchPendingNote"));
            assertTrue(item.getSummary().contains(
                    (String) item.getDetail().get("patchPendingNote")), "summary 与区块里的说法必须是同一句");
            assertEquals(List.of(), item.getDetail().get("searchableFields"),
                    "看不见就是看不见，不许把「没认出来」当成「查询区是空的」这个结论");
        }

        @Test
        @DisplayName("🔴 行构造与 FORM_CREATE 共用一份：同一个字段描述必须渲染成逐字相同的一行")
        void rowsCarryExactlyTheSameShapeAsFormCreate() {
            // 前端把两种行并排渲染在同一张「表单字段」表里，还要断言
            // 「表格里可查询的那些」与「查询条件表」两个集合恰好相等（方案 §2.2③）。
            // 两份行构造只要漂开一个键，那条断言就会红在另一个文件里
            FormSaveDraftChangeExtractor create = new FormSaveDraftChangeExtractor();
            Map<String, Object> oneField = patchField("emp_name", "姓名", "input", true, "LIKE");
            NodeDTO createNode = toolNode("draft", FormSaveDraftTool.NAME,
                    Map.of(FormSaveDraftTool.PARAM_FIELDS, List.of(oneField)));
            NodeDTO updateNode = toolNode("update", FormUpdateTool.NAME,
                    Map.of(FormUpdateTool.PARAM_PATCH, Map.of("formFields", List.of(oneField))));

            assertEquals(firstRow(create.extract(createNode, context(createNode))),
                    firstRow(extractor.extract(updateNode, context(updateNode))));
        }

        @Test
        @DisplayName("searchable 漏写就是漏写：不当成 false，更不当成 true")
        void missingSearchableIsNotGuessed() {
            Map<String, Object> bare = new LinkedHashMap<>();
            bare.put("field", "emp_name");
            bare.put("title", "姓名");
            NodeDTO node = toolNode("update", FormUpdateTool.NAME,
                    Map.of(FormUpdateTool.PARAM_PATCH, Map.of("formFields", List.of(bare))));

            ChangeItem item = extractor.extract(node, context(node));

            assertNull(firstRow(item).get("searchable"));
            assertEquals(List.of(), item.getDetail().get("searchableFields"));
            assertFalse(item.getSummary().contains("进查询区"), item.getSummary());
        }

        @Test
        @DisplayName("patch 只改 formConf 时字段表是空的，且 summary 不多说一句查询区")
        void aPatchWithoutFieldsAddsNothing() {
            NodeDTO node = toolNode("update", FormUpdateTool.NAME,
                    Map.of(FormUpdateTool.PARAM_ID, "1024",
                            FormUpdateTool.PARAM_PATCH, Map.of("formConf", "{}")));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(List.of(), item.getDetail().get("fields"));
            assertEquals(0, item.getDetail().get("fieldCount"));
            assertFalse(item.getSummary().contains("进查询区"), item.getSummary());
            // 逐字断言在 staysQuietWhenAPublishFollows 里，那里下游有发布节点、没有 ⚠️ 那一句
            assertTrue(item.getSummary().startsWith("修改表单 #1024：改动 formConf"), item.getSummary());
        }

        /** patch 里的一个字段描述；形状取自模型真写出来的那些 DSL（def #467 / #566） */
        private Map<String, Object> patchField(String name, String title, String widget,
                                               boolean searchable, String operator) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("field", name);
            if (title != null) {
                field.put("title", title);
            }
            field.put("widget", widget);
            field.put("searchable", searchable);
            if (operator != null) {
                field.put("operator", operator);
            }
            return field;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> firstRow(ChangeItem item) {
            List<Map<String, Object>> fields = (List<Map<String, Object>>) item.getDetail().get("fields");
            assertEquals(1, fields.size(), "期望恰好一行字段，实际 " + fields);
            return fields.get(0);
        }
    }

    // ==================== form_publish_bundle ====================

    @Nested
    @DisplayName("form_publish_bundle → FORM_PUBLISH")
    class Publish {

        private final FormPublishBundleChangeExtractor extractor = new FormPublishBundleChangeExtractor();

        @Test
        @DisplayName("🔴 roleIds 为空必须警告「仅超级管理员可见」—— 这就是最典型的配了不生效")
        void warnsWhenNoRolesGiven() {
            NodeDTO node = toolNode("publish", FormPublishBundleTool.NAME,
                    Map.of(FormPublishBundleTool.PARAM_ID, "1024",
                            FormPublishBundleTool.PARAM_PARENT_MENU_NAME, "人事管理"));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(ChangeKind.FORM_PUBLISH, item.getKind());
            assertEquals("人事管理", item.getTarget().get("parentMenu"));
            assertEquals("未指定角色，发布后仅超级管理员可见", item.getDetail().get("roleNote"));
            assertEquals(Boolean.FALSE, item.getDetail().get("formIdPending"));
            assertEquals("发布表单 #1024，挂到「人事管理」下。"
                    + "⚠️ 未指定角色，发布后仅超级管理员可见，业务用户看不到入口", item.getSummary());
        }

        @Test
        @DisplayName("给了角色就报个数；只给 parentMenuId 时按编号显示上级菜单")
        void reportsRoleCountAndNumericParentMenu() {
            NodeDTO node = toolNode("publish", FormPublishBundleTool.NAME,
                    Map.of(FormPublishBundleTool.PARAM_ID, 1024,
                            FormPublishBundleTool.PARAM_PARENT_MENU_ID, 2048,
                            FormPublishBundleTool.PARAM_ROLE_IDS, List.of(101, 102)));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals("#2048", item.getTarget().get("parentMenu"));
            assertEquals(List.of("101", "102"), item.getDetail().get("roleIds"));
            assertEquals("分配给 2 个角色", item.getDetail().get("roleNote"));
            assertEquals("发布表单 #1024，挂到菜单 #2048 下，分配给 2 个角色", item.getSummary());
        }

        @Test
        @DisplayName("🔴 编号是 {{$.save_draft.id}} 时照字面显示，并标明运行期才确定")
        void pendingFormIdIsShownLiterally() {
            NodeDTO node = toolNode("publish", FormPublishBundleTool.NAME,
                    Map.of(FormPublishBundleTool.PARAM_ID, "{{$.save_draft.id}}"));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(Boolean.TRUE, item.getDetail().get("formIdPending"));
            assertEquals("顶层菜单", item.getTarget().get("parentMenu"), "两个上级菜单参数都没给就是挂顶层");
            // 🔴 「发布表单（编号运行期才确定：…）」中间不留空格：全角括号前留白会读成一个模板洞，
            //    而这个分支出现的时机恰恰是模型把参数写错、审批人正在逐字读的时候
            assertEquals("发布表单（编号运行期才确定：{{$.save_draft.id}}），挂到顶层。"
                    + "⚠️ 未指定角色，发布后仅超级管理员可见，业务用户看不到入口", item.getSummary());
        }

        @Test
        @DisplayName("编号完全没给时说「编号未填」，与「运行期才确定」区分开")
        void missingFormIdIsSaidOutLoud() {
            NodeDTO node = toolNode("publish", FormPublishBundleTool.NAME, Map.of());
            ChangeItem item = extractor.extract(node, context(node));

            assertNull(item.getTarget().get("formId"));
            assertTrue(item.getSummary().startsWith("发布表单（编号未填），挂到顶层"),
                    item.getSummary());
        }

        @Test
        @DisplayName("🔴 不许说「目录」—— 上级是目录还是菜单要查 system_menu，抽取器没查")
        void neverClaimsTheParentMenuType() {
            NodeDTO node = toolNode("publish", FormPublishBundleTool.NAME,
                    Map.of(FormPublishBundleTool.PARAM_ID, "1024",
                            FormPublishBundleTool.PARAM_PARENT_MENU_NAME, "人事管理"));

            ChangeItem item = extractor.extract(node, context(node));

            String json = JsonUtils.toJsonString(item);
            // 表单运行时菜单自己是 type=2（菜单），它的上级既可能是 type=1（目录）
            // 也可能是另一个菜单。抽取器随口安一个类型，就是替没查过的东西下断言
            assertTrue(!json.contains("目录"), "清单给上级菜单安了一个自己没查过的类型：" + json);
        }
    }

    // ==================== ManifestLabels ====================

    @Nested
    @DisplayName("ManifestLabels：机器码 → 中文名")
    class Labels {

        @Test
        @DisplayName("🔴 一次构建里每张表最多查一遍 infra（懒加载 + 缓存）")
        void loadsEachTableAtMostOnce() {
            FormDesignApi api = mock(FormDesignApi.class);
            when(api.getWidgetContracts()).thenReturn(List.of(widget("select", "下拉选择")));
            when(api.getSearchOperatorList()).thenReturn(List.of(operator("LIKE", "包含")));
            ManifestLabels labels = new ManifestLabels(api);

            verifyNoInteractions(api);

            assertEquals("下拉选择", labels.widgetLabel("select"));
            assertEquals("下拉选择", labels.widgetLabel("select"));
            assertEquals("包含", labels.operatorLabel("LIKE"));
            assertEquals("包含", labels.operatorLabel("LIKE"));

            verify(api, times(1)).getWidgetContracts();
            verify(api, times(1)).getSearchOperatorList();
        }

        @Test
        @DisplayName("只用控件名时不去查操作符表 —— 全是建表节点的 DSL 不该为用不上的表付跨模块调用")
        void doesNotLoadTheOtherTable() {
            FormDesignApi api = mock(FormDesignApi.class);
            when(api.getWidgetContracts()).thenReturn(List.of(widget("select", "下拉选择")));
            ManifestLabels labels = new ManifestLabels(api);

            assertEquals("下拉选择", labels.widgetLabel("select"));

            verify(api, times(1)).getWidgetContracts();
            verify(api, times(0)).getSearchOperatorList();
        }

        @Test
        @DisplayName("🔴 infra 出口抛异常时回落机器码，且不重试（清单自己绝不能成为新的失败点）")
        void apiFailureFallsBackAndIsNotRetried() {
            FormDesignApi api = mock(FormDesignApi.class);
            when(api.getWidgetContracts()).thenThrow(new RuntimeException("infra 挂了"));
            ManifestLabels labels = new ManifestLabels(api);

            assertEquals("select", labels.widgetLabel("select"));
            assertEquals("select", labels.widgetLabel("select"));

            verify(api, times(1)).getWidgetContracts();
        }

        @Test
        @DisplayName("api 为 null（纯单测装配）时同样回落机器码")
        void nullApiFallsBackToMachineCode() {
            ManifestLabels labels = new ManifestLabels(null);
            assertEquals("select", labels.widgetLabel("select"));
            assertEquals("LIKE", labels.operatorLabel("LIKE"));
        }

        @Test
        @DisplayName("查不到的机器码原样返回；中文名为空时也回落机器码，不显示成空白")
        void unknownCodeIsReturnedVerbatim() {
            FormDesignApi api = mock(FormDesignApi.class);
            when(api.getWidgetContracts()).thenReturn(List.of(widget("input", ""), widget(null, "没有 type 的项")));
            when(api.getSearchOperatorList()).thenReturn(List.of(operator("GE", null)));
            ManifestLabels labels = new ManifestLabels(api);

            assertEquals("input", labels.widgetLabel("input"), "label 为空就用 type 顶上");
            assertEquals("myCustom", labels.widgetLabel("myCustom"), "表里没有的机器码原样显示");
            assertEquals("GE", labels.operatorLabel("GE"));
        }

        @Test
        @DisplayName("没写控件就是 null，与「写了但翻不出来」区分开")
        void blankCodeYieldsNull() {
            ManifestLabels labels = new ManifestLabels(null);
            assertNull(labels.widgetLabel(null));
            assertNull(labels.widgetLabel(""));
            assertNull(labels.operatorLabel("  "));
        }
    }

    private static FormDesignApi.WidgetSimpleRespDTO widget(String type, String label) {
        FormDesignApi.WidgetSimpleRespDTO dto = new FormDesignApi.WidgetSimpleRespDTO();
        dto.setType(type);
        dto.setLabel(label);
        return dto;
    }

    private static FormDesignApi.SearchOperatorRespDTO operator(String code, String label) {
        FormDesignApi.SearchOperatorRespDTO dto = new FormDesignApi.SearchOperatorRespDTO();
        dto.setOperator(code);
        dto.setLabel(label);
        return dto;
    }

}
