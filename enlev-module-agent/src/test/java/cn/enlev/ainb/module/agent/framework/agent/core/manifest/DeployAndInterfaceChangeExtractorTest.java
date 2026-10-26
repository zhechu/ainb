package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.datainterface.DataInterfaceInvokeTool;
import cn.enlev.ainb.module.agent.tool.impl.datainterface.DataInterfaceParamResolver;
import cn.enlev.ainb.module.agent.tool.impl.process.ProcessCreateAndDeployTool;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署审批流与调用数据接口两个抽取器的单测（S2-c2），外加它们共用的参数预览 {@link ManifestArgs#preview}
 *
 * <h3>🔴 这两项的共同点是：清单<b>不可能</b>说全，于是必须说清自己没说全</h3>
 * <ul>
 *   <li>{@code process_create_and_deploy}：新建还是覆盖已有流程，要查 {@code BpmModelApi} 才知道；
 *       审批节点树谁批、走哪个分支，要拿真实 constraints 才翻得成人话。两者都归 S2-d 的干跑器，
 *       所以本项刻意很薄 —— 但「没绑表单会被直接拒」「不限发起人 = 人人可发起」这两件
 *       不需要查库、且审批人非知道不可的事必须说</li>
 *   <li>{@code data_interface_invoke}：接口读还是写、写哪张表，全在 infra 侧的实现里。
 *       于是它带 {@code effectUnknown=true}，明说「清单判断不了」，
 *       绝不出现「只读」「安全」这类会让人放心点同意的词</li>
 * </ul>
 *
 * @author 走进新时代
 */
class DeployAndInterfaceChangeExtractorTest {

    // ==================== process_create_and_deploy ====================

    @Nested
    @DisplayName("process_create_and_deploy → PROCESS_DEPLOY")
    class Deploy {

        private final ProcessDeployChangeExtractor extractor = new ProcessDeployChangeExtractor();

        @Test
        @DisplayName("绑在线表单：报出标识与表单编码，并声明主表需要 process_instance_id 列")
        void describesOnlineFormBinding() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put(ProcessCreateAndDeployTool.PARAM_KEY, "purchase_apply");
            args.put(ProcessCreateAndDeployTool.PARAM_NAME, "采购申请审批");
            args.put(ProcessCreateAndDeployTool.PARAM_FORM_DESIGN_CODE, "employee");
            args.put(ProcessCreateAndDeployTool.PARAM_CATEGORY, "oa");
            args.put(ProcessCreateAndDeployTool.PARAM_MANAGER_USER_IDS, List.of(1));
            args.put(ProcessCreateAndDeployTool.PARAM_START_USER_IDS, List.of(101, 102));
            NodeDTO node = toolNode("deploy", ProcessCreateAndDeployTool.NAME, args);

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(ChangeKind.PROCESS_DEPLOY, item.getKind());
            assertEquals("purchase_apply", item.getTarget().get("key"));
            assertEquals("采购申请审批", item.getTarget().get("name"));
            assertEquals("oa", item.getDetail().get("category"));
            assertEquals(List.of("101", "102"), item.getDetail().get("startUserIds"));
            assertEquals("process_instance_id", item.getDetail().get("requiresColumn"));
            assertFalse(item.getDetail().containsKey("startUserNote"),
                    "限定了发起人就不该再说「人人可发起」");
            assertEquals("部署审批流「采购申请审批」（标识 purchase_apply），绑定在线表单 employee",
                    item.getSummary());
        }

        @Test
        @DisplayName("🔴 startUserIds 为空必须说「部署后人人都能发起」—— DSL 里它表现为「这个参数没写」")
        void warnsWhenStartUsersAreUnrestricted() {
            NodeDTO node = toolNode("deploy", ProcessCreateAndDeployTool.NAME, Map.of(
                    ProcessCreateAndDeployTool.PARAM_KEY, "purchase_apply",
                    ProcessCreateAndDeployTool.PARAM_NAME, "采购申请审批",
                    ProcessCreateAndDeployTool.PARAM_FORM_DESIGN_CODE, "employee"));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals("未限定可发起人 —— 部署后全平台所有人都能发起这条流程",
                    item.getDetail().get("startUserNote"));
            assertEquals("部署审批流「采购申请审批」（标识 purchase_apply），绑定在线表单 employee。"
                    + "⚠️ 未限定可发起人，部署后人人都能发起", item.getSummary());
        }

        @Test
        @DisplayName("绑普通表单时按编号显示，且不声明 process_instance_id（那是在线表单主表的要求）")
        void describesNormalFormBinding() {
            NodeDTO node = toolNode("deploy", ProcessCreateAndDeployTool.NAME, Map.of(
                    ProcessCreateAndDeployTool.PARAM_KEY, "leave",
                    ProcessCreateAndDeployTool.PARAM_NAME, "请假审批",
                    ProcessCreateAndDeployTool.PARAM_FORM_ID, 1024));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals("1024", item.getDetail().get("formId"));
            assertFalse(item.getDetail().containsKey("requiresColumn"),
                    "普通表单没有「主表」这回事，别把在线表单的要求套上去");
            assertTrue(item.getSummary().contains("绑定普通表单 #1024"), item.getSummary());
        }

        @Test
        @DisplayName("🔴 两种表单都没给时如实说「执行时会被直接拒绝」，并且不再叠别的警告")
        void missingFormIsSaidOutLoud() {
            // startUserIds 也没给，但必死项已经说清了，再叠一句「人人可发起」只会淹没它
            // （与 DataInterfaceInvokeChangeExtractor 缺 code 时早退同理）
            NodeDTO node = toolNode("deploy", ProcessCreateAndDeployTool.NAME, Map.of(
                    ProcessCreateAndDeployTool.PARAM_KEY, "leave",
                    ProcessCreateAndDeployTool.PARAM_NAME, "请假审批"));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals("部署审批流「请假审批」（标识 leave）。"
                    + "⚠️ 没有绑定表单（formDesignCode / formId 都没给），执行时会被直接拒绝",
                    item.getSummary());
        }

        @Test
        @DisplayName("名称与标识都没给时不编，直说未填")
        void missingNameAndKeyAreSaidOutLoud() {
            NodeDTO node = toolNode("deploy", ProcessCreateAndDeployTool.NAME, Map.of());
            assertEquals("部署审批流（名称未填），标识未填。"
                            + "⚠️ 没有绑定表单（formDesignCode / formId 都没给），执行时会被直接拒绝",
                    extractor.extract(node, context(node)).getSummary());
        }

        @Test
        @DisplayName("🔴 simpleModel 一个字都不进清单：它是几十上百行的审批树，且合法性归校验器判")
        void neverCarriesTheSimpleModel() {
            String marker = "MARKER_不该出现在清单里";
            Map<String, Object> simpleModel = new LinkedHashMap<>();
            simpleModel.put("approveNode", Map.of("name", marker, "approveType", 1));
            NodeDTO node = toolNode("deploy", ProcessCreateAndDeployTool.NAME, Map.of(
                    ProcessCreateAndDeployTool.PARAM_KEY, "leave",
                    ProcessCreateAndDeployTool.PARAM_NAME, "请假审批",
                    ProcessCreateAndDeployTool.PARAM_FORM_ID, 1024,
                    ProcessCreateAndDeployTool.PARAM_SIMPLE_MODEL, simpleModel));

            ChangeItem item = extractor.extract(node, context(node));

            assertFalse(item.getDetail().containsKey(ProcessCreateAndDeployTool.PARAM_SIMPLE_MODEL));
            assertFalse(JsonUtils.toJsonString(item).contains(marker),
                    "审批节点树漏进清单了：" + JsonUtils.toJsonString(item));
        }
    }

    // ==================== data_interface_invoke ====================

    @Nested
    @DisplayName("data_interface_invoke → DATA_INTERFACE")
    class Invoke {

        private final DataInterfaceInvokeChangeExtractor extractor = new DataInterfaceInvokeChangeExtractor();

        @Test
        @DisplayName("🔴 明说自己判断不了副作用，绝不出现「只读」「安全」这类词")
        void admitsItCannotTellTheEffect() {
            // 🔴 params 用 LinkedHashMap：paramKeys 要保持书写顺序，而 Map.of 的迭代顺序随 JVM 实例变
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("ids", List.of(1, 2));
            params.put("dept", "研发部");
            NodeDTO node = toolNode("call", DataInterfaceInvokeTool.NAME, Map.of(
                    DataInterfaceParamResolver.PARAM_CODE, "employee_batch_update",
                    DataInterfaceParamResolver.PARAM_PARAMS, params));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(ChangeKind.DATA_INTERFACE, item.getKind());
            assertEquals("employee_batch_update", item.getTarget().get("code"));
            assertEquals(Boolean.TRUE, item.getDetail().get("effectUnknown"));
            assertEquals("这是全能力执行（可能写库、可能调外部 HTTP）。"
                            + "接口具体改什么由 infra 侧的接口实现决定，清单判断不了，请谨慎确认",
                    item.getDetail().get("effectNote"));
            assertEquals(List.of("ids", "dept"), item.getDetail().get("paramKeys"));
            assertEquals("调用数据接口 employee_batch_update，传 2 个入参（ids、dept）。"
                    + "⚠️ 全能力执行，可能产生写副作用，清单判断不了它到底改什么", item.getSummary());

            String summary = item.getSummary().toLowerCase();
            assertFalse(summary.contains("只读"), "清单不该替一个黑盒接口背书");
            assertFalse(summary.contains("安全"), "同上");
        }

        @Test
        @DisplayName("params 被模型序列化成 JSON 串时照样认得（与 resolveArgs 同口径）")
        void acceptsParamsSerializedAsJsonString() {
            NodeDTO node = toolNode("call", DataInterfaceInvokeTool.NAME, Map.of(
                    DataInterfaceParamResolver.PARAM_CODE, "employee_batch_update",
                    DataInterfaceParamResolver.PARAM_PARAMS, "{\"ids\":[1,2,3]}"));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(List.of("ids"), item.getDetail().get("paramKeys"));
            assertTrue(item.getSummary().contains("传 1 个入参（ids）"), item.getSummary());
        }

        @Test
        @DisplayName("没传 params 时说「无入参」，而不是含糊地不提")
        void noParamsIsSaidOutLoud() {
            NodeDTO node = toolNode("call", DataInterfaceInvokeTool.NAME,
                    Map.of(DataInterfaceParamResolver.PARAM_CODE, "employee_sync"));

            assertEquals("调用数据接口 employee_sync，无入参。"
                            + "⚠️ 全能力执行，可能产生写副作用，清单判断不了它到底改什么",
                    extractor.extract(node, context(node)).getSummary());
        }

        @Test
        @DisplayName("code 缺失时只说这一件必死的事，不再叠副作用警告")
        void missingCodeShortCircuits() {
            NodeDTO node = toolNode("call", DataInterfaceInvokeTool.NAME, Map.of());

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals("调用数据接口（编码未填）。⚠️ code 缺失，执行时会被直接拒绝", item.getSummary());
        }

        @Test
        @DisplayName("code 是占位符时照字面显示并标明待定")
        void pendingCodeIsShownLiterally() {
            NodeDTO node = toolNode("call", DataInterfaceInvokeTool.NAME, Map.of(
                    DataInterfaceParamResolver.PARAM_CODE, "{{$.start.interfaceCode}}"));

            ChangeItem item = extractor.extract(node, context(node));

            assertEquals(Boolean.TRUE, item.getDetail().get("codePending"));
            assertTrue(item.getSummary().contains("{{$.start.interfaceCode}}"), item.getSummary());
        }
    }

    // ==================== ManifestArgs.preview（兜底与本项共用的那份截断口径） ====================

    @Nested
    @DisplayName("ManifestArgs.preview：两处共用的截断口径")
    class Preview {

        @Test
        @DisplayName("长值截到 200 字并补省略号；短值原样保留")
        void truncatesLongValuesOnly() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("blob", "x".repeat(500));
            args.put("short", "abc");

            Map<String, String> preview = ManifestArgs.preview(args);

            assertEquals(ManifestArgs.MAX_VALUE_CHARS + 1, preview.get("blob").length(), "+1 是那个省略号");
            assertTrue(preview.get("blob").endsWith("…"));
            assertEquals("abc", preview.get("short"));
        }

        @Test
        @DisplayName("最多 20 个键，多出来的折成一句「另有 N 个参数未展示」")
        void capsKeyCount() {
            Map<String, Object> args = new LinkedHashMap<>();
            for (int i = 1; i <= 25; i++) {
                args.put("k" + i, i);
            }

            Map<String, String> preview = ManifestArgs.preview(args);

            assertEquals(ManifestArgs.MAX_KEYS + 1, preview.size());
            assertEquals("另有 5 个参数未展示", preview.get("…"));
            assertEquals("1", preview.get("k1"));
            assertFalse(preview.containsKey("k21"));
        }

        @Test
        @DisplayName("嵌套结构一律渲染成 JSON 串 —— 清单要能冻进 BPM 流程变量，不能带任意深的对象")
        void rendersNestedStructuresAsJsonText() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("ids", List.of(1, 2));
            args.put("filter", Map.of("dept", "研发部"));
            args.put("nothing", null);

            Map<String, String> preview = ManifestArgs.preview(args);

            assertEquals("[1,2]", preview.get("ids"));
            assertEquals("{\"dept\":\"研发部\"}", preview.get("filter"));
            assertEquals("null", preview.get("nothing"));
        }

        @Test
        @DisplayName("空参数与 null 都返回空表，不抛")
        void emptyAndNullYieldEmptyPreview() {
            assertEquals(Map.of(), ManifestArgs.preview(null));
            assertEquals(Map.of(), ManifestArgs.preview(Map.of()));
        }

        @Test
        @DisplayName("🔴 兜底抽取器与数据接口抽取器用的是同一份预览，同一个值不会截出两种长度")
        void bothConsumersShareTheSameRendering() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("blob", "y".repeat(500));
            NodeDTO unknownTool = toolNode("x", "some_brand_new_tool", args);
            NodeDTO invoke = toolNode("y", DataInterfaceInvokeTool.NAME, Map.of(
                    DataInterfaceParamResolver.PARAM_CODE, "employee_sync",
                    DataInterfaceParamResolver.PARAM_PARAMS, args));

            @SuppressWarnings("unchecked")
            Map<String, String> viaGeneric = (Map<String, String>) new GenericToolChangeExtractor()
                    .extract(unknownTool, context(unknownTool)).getDetail().get("argsPreview");
            @SuppressWarnings("unchecked")
            Map<String, String> viaInterface = (Map<String, String>) new DataInterfaceInvokeChangeExtractor()
                    .extract(invoke, context(invoke)).getDetail().get("argsPreview");

            assertEquals(viaGeneric.get("blob"), viaInterface.get("blob"));
            assertEquals(Boolean.TRUE,
                    new GenericToolChangeExtractor().extract(unknownTool, context(unknownTool))
                            .getDetail().get("incomplete"),
                    "兜底要标明自己没读懂；数据接口那项用的是 effectUnknown，理由不同不该共用一个标记");
            assertFalse(viaInterface.containsKey("incomplete"));
        }
    }

}
