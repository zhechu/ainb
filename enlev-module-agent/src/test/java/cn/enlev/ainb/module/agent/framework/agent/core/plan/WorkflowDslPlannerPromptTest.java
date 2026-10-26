package cn.enlev.ainb.module.agent.framework.agent.core.plan;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.enums.WaitTypeEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.EdgeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.io.NodeIoContract;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.ScriptSandbox;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.ToolResultShapes;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.FormSchemaValidator;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelColumnTypeTool;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelPhysicalTableTool;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelPreviewDdlTool;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelStructureTool;
import cn.enlev.ainb.module.agent.tool.impl.explain.ExplainFormRuleTool;
import cn.enlev.ainb.module.agent.tool.impl.form.FormFieldFactory;
import cn.enlev.ainb.module.agent.tool.impl.form.WidgetContractGetTool;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Planner system prompt 的<b>内容</b>守卫
 *
 * <h3>为什么 prompt 的文本值得单开一个测试类</h3>
 * prompt 是这个模块里唯一「文本即行为」的地方：平台没有原生 function-calling，
 * 模型能产出什么样的 DSL 完全由这段文本决定。它写错一个键名，后果不是「文案不好看」，
 * 而是模型照着错键名产出草稿、校验器报错、回灌自修正 —— 一来一回烧掉两轮<b>真实计费</b>，
 * 而单测（{@code WorkflowPlannerServiceImplTest}）全绿，因为它把 prompt 整个 mock 成了 "SYSTEM_PROMPT"。
 *
 * <h3>🔴 本类守的那条缝：prompt 与校验器必须同口径</h3>
 * 目录的「只读 / 写」分列曾读 {@code isRequireConfirm()}（= {@code getRisk()}，租户<b>生效</b>值），
 * 而 {@code WorkflowDslValidator} 的 V3/V11 一律读 {@code getDeclaredRisk()}（<b>声明</b>值）。
 * 两者一分叉，{@code agent_tool_override} 一行数据就能让 prompt 把一个不可逆工具列进
 * 「只读工具（可放心用）」，模型据此塞进 {@code agent_loop.tools}，V3 按声明值直接拒 ——
 * 用户看到的是「AI 生成的草稿反复失败」，根因却藏在两个读者读了不同字段。
 * 故 {@link #relaxedTool_staysInTheWriteBucket} 与 {@link #escalatedTool_staysInTheReadBucket}
 * 是<b>一对</b>：只钉前者的话，把两栏一起按生效值分也能变绿，那就把真口径也丢了。
 *
 * <h3>为什么 few-shot 要真解析、还要回查契约</h3>
 * 样例是模型唯一的形状参照，而它<b>没有任何编译期约束</b> —— 它就是一段字符串。
 * {@link #fewShotSamplesAreParsableDsl} 与 {@link #fewShotConfigKeysAreAllRegistered}
 * 把它当真 DSL 解析一遍、逐个 config 键回查 {@link NodeIoContract}，
 * 于是「样例里写了一个契约没登记的键」会在 CI 变红，而不是在用户那一轮计费里变红。
 *
 * @author 走进新时代
 */
@DisplayName("agent Planner system prompt 内容守卫")
class WorkflowDslPlannerPromptTest {

    private WorkflowDslPlannerPrompt prompt;
    private AgentToolRegistry toolRegistry;
    private AgentToolDefService toolDefService;
    private FormDesignApi formDesignApi;
    private DataModelApi dataModelApi;
    private DslParser parser;

    /** 只读工具（声明与生效一致） */
    private static final String READ_TOOL = "data_interface_list";
    /** 声明不可逆、但被租户降级成只读的工具 —— 本类的主角 */
    private static final String RELAXED_TOOL = "datamodel_create";
    /** 声明只读、但被租户升级成不可逆的工具 —— 反方向探针 */
    private static final String ESCALATED_TOOL = "form_data_get";

    /**
     * 控件契约的测试替身
     *
     * <p>type/label 一律取<b>真契约里的原值</b>，不是随手编的：白名单是一行一个
     * {@code - type（label）：列类型} 拼出来的，渲染与抠取都靠字符形状吃饭。
     * 这里刻意把几种会咬人的形状全摆上 ——
     * 大写驼峰（{@code OrgPicker}/{@code UploadImg}，抠取用的正则要认）、
     * label 里自带全角括号（{@code 用户选择器（已删除）}，别把 label 的括号当成 type 的收尾）、
     * {@code columnTypes} 为空（{@code group}，布局类控件不绑列）、
     * 为 {@code null}（{@code oldSelect}，与空同样要渲染成「不绑列」而不是抛 NPE）、
     * 以及 {@code OrgPicker} 本尊 —— 2026-10-04 那两例连猜六个控件名，缺的正是它。
     *
     * <p>🔴 {@code inputNumber} 与 {@code timePicker} 是**打标断言的素材**：真契约里它们分别宣称
     * 能绑 {@code INTEGER} 与 {@code TIME}，而建表枚举里是 {@code INT}、且没有 {@code TIME} ——
     * 这是 JSON 修掉之后**仍然活着**的那部分契约/枚举分歧（INTEGER 有 12 个控件在宣称、TIME 有 1 个）。
     * 原先这一格用的是 {@code OrgPicker} 的 JSON，2026-10-04 用户裁定「改建表枚举，加上 JSON」
     * 之后 JSON 变成可建，素材就得换成还收不了的这两个，否则打标逻辑没人守了。
     *
     * <p>🔴 {@code DictSelect} / {@code cascader} / {@code input} 的 {@code props} 是
     * <b>「⚠️AI建不出来」那个标记的素材</b>（2026-10-10 补，真契约原值），三种形状各摆一个 ——
     * 必填项配不齐（{@code DictSelect} 要 {@code dictType}）、
     * 非选项族却没有选项来源（{@code cascader} 的 dictSource/dataSource/options 全是 optional）、
     * 以及<b>有 props 但一个都不必填</b>（{@code input} 的 validateRules/uniqueCheck/maskRule）。
     * 第三个是前两个的反向对照：少了它，「凡带 props 的一律打标」也能绿，
     * 而那会把清单里绝大多数控件都标成建不出来 —— 标记一多就等于没有标记。
     */
    private static final List<FormDesignApi.WidgetSimpleRespDTO> WIDGET_CONTRACTS = List.of(
            widget("input", "单行输入", true, false, List.of("STRING", "TEXT"),
                    prop("validateRules", false), prop("uniqueCheck", false), prop("maskRule", false)),
            widget("datePicker", "日期", true, false, List.of("DATE", "DATETIME", "STRING", "LONG")),
            widget("OrgPicker", "组织选择器", true, false, List.of("STRING", "TEXT", "JSON", "LONG")),
            widget("inputNumber", "数字输入", true, false, List.of("INTEGER", "LONG", "DECIMAL")),
            widget("timePicker", "时间", true, false, List.of("TIME", "STRING", "DATETIME")),
            widget("UploadImg", "单图上传", true, false, List.of("STRING", "TEXT")),
            widget("group", "子表", true, false, List.of()),
            widget("oldSelect", "旧下拉", true, true, null),
            widget("DictSelect", "字典选择器", true, false, List.of("STRING", "TEXT", "INTEGER"),
                    prop("dictType", true), prop("valueType", false)),
            widget("cascader", "级联选择", true, false, List.of("STRING", "TEXT", "JSON"),
                    prop("dictSource", false), prop("dataSource", false), prop("options", false)),
            widget("brokenWidget", "停用控件", false, false, List.of("STRING")),
            widget("UserSelect", "用户选择器（已删除）", false, false, List.of("STRING")),
            widget("upload", "上传（内核内置）", false, false, null));

    private static FormDesignApi.WidgetSimpleRespDTO widget(String type, String label,
                                                            boolean supported, boolean deprecated,
                                                            List<String> columnTypes) {
        return widget(type, label, supported, deprecated, columnTypes, new FormDesignApi.WidgetPropDTO[0]);
    }

    private static FormDesignApi.WidgetSimpleRespDTO widget(String type, String label,
                                                            boolean supported, boolean deprecated,
                                                            List<String> columnTypes,
                                                            FormDesignApi.WidgetPropDTO... props) {
        FormDesignApi.WidgetSimpleRespDTO dto = new FormDesignApi.WidgetSimpleRespDTO();
        dto.setType(type);
        dto.setLabel(label);
        dto.setSupported(supported);
        dto.setDeprecated(deprecated);
        dto.setColumnTypes(columnTypes);
        dto.setProps(props.length == 0 ? null : List.of(props));
        return dto;
    }

    /** 一个配置项。只填判据读的两格：键名，与契约那句「不填则该控件无法正常工作」 */
    private static FormDesignApi.WidgetPropDTO prop(String name, boolean required) {
        FormDesignApi.WidgetPropDTO dto = new FormDesignApi.WidgetPropDTO();
        dto.setName(name);
        dto.setRequired(required);
        return dto;
    }

    /** 替身里 {@code supported=true} 的全部 type（多条断言的期望值都从它推，不写死数字） */
    private static Set<String> supportedTypes() {
        Set<String> supported = new LinkedHashSet<>();
        for (FormDesignApi.WidgetSimpleRespDTO widget : WIDGET_CONTRACTS) {
            if (Boolean.TRUE.equals(widget.getSupported())) {
                supported.add(widget.getType());
            }
        }
        return supported;
    }

    /**
     * 建表列类型的测试替身：<b>逐条照抄</b> {@code DataModelColumnTypeEnum} 的 11 个值
     * （type / mysqlType / needLength / needScale / description 五个字段全是原值）
     *
     * <p>🔴 抄真值而不是随手编两个，是因为这一段的渲染靠字符形状吃饭
     * （一行一个 {@code - TYPE（mysqlType）：description【…】}），而本类要守的正是
     * 「prompt 印的」与「{@code DataModelCreateValidator} 判的」同源。
     * 替身里少一个类型，下面那条「一个不多一个不少」的断言就守不住真清单的形状。
     *
     * <p>⚠️ 这份替身是**手抄**的，加了枚举值要回来同步 —— 抄漏了不会有任何一条断言变红
     * （「一个不多一个不少」比的是替身与 prompt，两边一起漏就照样相等），
     * 守真清单形状的是 infra 侧的 {@code DataModelApiImplTest#testColumnTypes}。
     *
     * <p>🔴 JSON 是 2026-10-04 加的（用户裁定「改建表枚举，加上 JSON」，缺陷 D4-15 的根治）。
     * 此前这份替身**刻意不含 JSON**，因为真枚举里就没有它，而 {@code OrgPicker} 的契约宣称能绑 ——
     * 那对矛盾正是 D4-15 的本体。现在矛盾在根上没了，打标断言的素材换成了 INTEGER / TIME
     * （见 {@link #WIDGET_CONTRACTS} 的注释）。
     */
    private static final List<DataModelApi.ColumnTypeRespDTO> COLUMN_TYPES = List.of(
            columnType("STRING", "varchar", true, false, "短文本，需指定长度"),
            columnType("TEXT", "text", false, false, "长文本"),
            columnType("LONG_TEXT", "longtext", false, false, "超长文本"),
            columnType("JSON", "json", false, false, "JSON 文档，不能做主键或索引列"),
            columnType("TINYINT", "tinyint", false, false, "小整数，状态/类型字段用它并配字典"),
            columnType("INT", "int", false, false, "整数"),
            columnType("LONG", "bigint", false, false, "长整数"),
            columnType("DECIMAL", "decimal", true, true, "小数，需指定长度与精度"),
            columnType("BOOLEAN", "bit", false, false, "布尔"),
            columnType("DATE", "date", false, false, "日期"),
            columnType("DATETIME", "datetime", false, false, "日期时间"));

    private static DataModelApi.ColumnTypeRespDTO columnType(String type, String mysqlType,
                                                             boolean needLength, boolean needScale,
                                                             String description) {
        DataModelApi.ColumnTypeRespDTO dto = new DataModelApi.ColumnTypeRespDTO();
        dto.setType(type);
        dto.setMysqlType(mysqlType);
        dto.setNeedLength(needLength);
        dto.setNeedScale(needScale);
        dto.setDescription(description);
        return dto;
    }

    @BeforeEach
    void setUp() {
        prompt = new WorkflowDslPlannerPrompt();
        toolRegistry = mock(AgentToolRegistry.class);
        toolDefService = mock(AgentToolDefService.class);
        formDesignApi = mock(FormDesignApi.class);
        dataModelApi = mock(DataModelApi.class);
        ReflectionTestUtils.setField(prompt, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(prompt, "toolDefService", toolDefService);
        ReflectionTestUtils.setField(prompt, "formDesignApi", formDesignApi);
        ReflectionTestUtils.setField(prompt, "dataModelApi", dataModelApi);
        when(formDesignApi.getWidgetContracts()).thenReturn(WIDGET_CONTRACTS);
        when(dataModelApi.getColumnTypeList()).thenReturn(COLUMN_TYPES);

        parser = new DslParser();
        ReflectionTestUtils.setField(parser, "objectMapper", new ObjectMapper());

        // 🔴 必须先建好列表再 stub：builtin() 内部也在 mock+stub，
        //    直接写进 when(...).thenReturn(List.of(builtin(...))) 会在外层 stubbing 未完成时嵌套打桩，
        //    Mockito 抛 UnfinishedStubbing（12 条用例一起红，报错还指向 builtin 而不是这一行）
        List<AgentToolRegistry.ToolDefinition> builtins = List.of(
                builtin(READ_TOOL, "查数据接口", RiskLevel.READ_ONLY, RiskLevel.READ_ONLY, true),
                builtin(RELAXED_TOOL, "建表", RiskLevel.IRREVERSIBLE, RiskLevel.READ_ONLY, true),
                builtin(ESCALATED_TOOL, "取表单数据", RiskLevel.READ_ONLY, RiskLevel.IRREVERSIBLE, true),
                builtin("disabled_tool", "已停用", RiskLevel.READ_ONLY, RiskLevel.READ_ONLY, false));
        when(toolRegistry.list()).thenReturn(builtins);
        when(toolDefService.getEnabledToolDefList()).thenReturn(List.of());
    }

    // ==================== 🔴 分列与风险标签只认声明值 ====================

    @Test
    @DisplayName("被租户降级成只读的不可逆工具：仍进「写操作」栏，且标签写声明的「不可逆」")
    void relaxedTool_staysInTheWriteBucket() {
        String text = build();
        String readonly = section(text, "### 只读工具", "### 写操作工具");
        String write = section(text, "### 写操作工具", "## 硬规则");

        assertFalse(readonly.contains(RELAXED_TOOL),
                "生效值被降成 READ_ONLY 就挪进只读栏 = prompt 与 V3/V11 分叉，模型会把它塞进 agent_loop.tools 然后被拒");
        assertTrue(write.contains(RELAXED_TOOL), "声明不可逆的工具必须留在写操作栏，实际写栏内容：" + write);
        assertTrue(write.contains("**" + RELAXED_TOOL + "**（建表）  【写，风险：不可逆】"),
                "风险标签必须取声明值的中文说法，实际写栏内容：" + write);
    }

    @Test
    @DisplayName("对照：被租户升级成不可逆的只读工具，仍进「只读」栏（口径是声明值，不是「哪个更狠用哪个」）")
    void escalatedTool_staysInTheReadBucket() {
        String text = build();
        String readonly = section(text, "### 只读工具", "### 写操作工具");
        String write = section(text, "### 写操作工具", "## 硬规则");

        // 🔴 没有这一条，把两栏一起改回按生效值分列也能让上一条变绿 —— 那就把真口径一起丢了。
        //    校验器读的就是声明值（ToolResolver#declaredRiskOf），prompt 必须与它同口径，
        //    而不是「取更保守的那个」：后者会在另一个方向上分叉（prompt 说不可逆、V11 说无要求）。
        assertTrue(readonly.contains(ESCALATED_TOOL),
                "声明只读的工具应在只读栏，实际只读栏内容：" + readonly);
        assertFalse(write.contains(ESCALATED_TOOL),
                "按生效值升级会把它挪进写栏，与 V11 的判定分叉，实际写栏内容：" + write);
        assertTrue(readonly.contains(READ_TOOL), "真正的只读工具应在只读栏");
    }

    @Test
    @DisplayName("停用的工具不进目录（启用状态读生效值：停用是更安全的方向，被数据推动不构成绕过）")
    void disabledTool_isOmitted() {
        assertFalse(build().contains("disabled_tool"),
                "停用的工具不该教给模型：网关会拒调用，草稿必然执行失败");
    }

    // ==================== 自定义工具 ====================

    @Test
    @DisplayName("自定义工具：只列 source=DATA_INTERFACE 的，FORM/SCRIPT 是执行期必炸的预留位")
    void customTools_onlyExecutableSourceIsListed() {
        when(toolDefService.getEnabledToolDefList()).thenReturn(List.of(
                custom("my_query_tool", "我的查询", "DATA_INTERFACE", "READ_ONLY"),
                custom("my_form_tool", "表单工具", "FORM", "READ_ONLY"),
                custom("my_script_tool", "脚本工具", "SCRIPT", "READ_ONLY")));

        String text = build();
        assertTrue(text.contains("**my_query_tool**（我的查询）"),
                "启用中的 DATA_INTERFACE 自定义工具应进目录并带中文名，否则租户配了 AI 也用不上");
        // 🔴 FORM/SCRIPT 不是「少列一个」的小事：它们的校验能过（ToolResolver 回落到 agent_tool_def），
        //    执行期才抛 AGENT_TOOL_SOURCE_UNSUPPORTED。教给模型 = 批量产出「校验通过、执行必炸」的草稿。
        assertFalse(text.contains("my_form_tool"), "FORM 是预留位，执行期抛 AGENT_TOOL_SOURCE_UNSUPPORTED");
        assertFalse(text.contains("my_script_tool"), "SCRIPT 是预留位，执行期抛 AGENT_TOOL_SOURCE_UNSUPPORTED");
    }

    @Test
    @DisplayName("自定义工具与内置同名时不列：执行路径 registry-first，真正跑的是内置那个")
    void customTool_shadowedByBuiltin_isOmitted() {
        when(toolDefService.getEnabledToolDefList()).thenReturn(List.of(
                custom(READ_TOOL, "冒名顶替", "DATA_INTERFACE", "READ_ONLY")));

        String text = build();
        assertFalse(text.contains("冒名顶替"),
                "两边都列出来，模型可能按自定义那份的参数说明去调内置工具，而这类分叉界面上完全看不出来");
        // 🔴 必须切出「## 工具目录」那一段再断言，不能对整份 prompt 断言 contains：
        //    READ_TOOL(data_interface_list) 还硬编码在 few-shot 的样例1/样例3 里
        //    （WorkflowDslPlannerPrompt:288/:305），而 few-shot 是 buildSystemPrompt **最后**追加的、
        //    与目录产没产出无关 ⟹ 目录整个退化成空的，这行照样绿。
        //    「对照」半边一旦恒真，它就挡不住「过度过滤把内置工具一起滤掉」这个它本来要挡的缺陷。
        String catalog = section(text, "## 工具目录", "## 硬规则");
        assertTrue(catalog.contains(READ_TOOL), "内置那个仍要在目录里，实际目录内容：" + catalog);
    }

    @Test
    @DisplayName("自定义工具表查不动时退化成「只认内置」，不能让整条 AI 编排链路挂掉")
    void customToolLookupFailure_degradesToBuiltinOnly() {
        when(toolDefService.getEnabledToolDefList()).thenThrow(new RuntimeException("库连不上"));

        String text = build();
        // 🔴 同 customTool_shadowedByBuiltin_isOmitted：这两个工具名都硬编码在 few-shot 里
        //    （:288/:305/:309/:319/:324），而 few-shot 在 appendToolCatalog **之后**追加 ⟹
        //    catch 分支把目录整个吞成空的，对整份 prompt 断言 contains 也照样绿，
        //    于是「库连不上时目录仍照常产出」这条**恰恰是它要证的事**根本没被证。
        String catalog = section(text, "## 工具目录", "## 硬规则");
        assertTrue(catalog.contains(READ_TOOL), "内置目录必须照常产出，实际目录内容：" + catalog);
        assertTrue(catalog.contains(RELAXED_TOOL), "内置目录必须照常产出，实际目录内容：" + catalog);
    }

    // ==================== 节点目录 ↔ 契约 ====================

    @Test
    @DisplayName("节点目录列出的 type 与 NodeIoContract 登记的 13 种恰好一致（不多不少）")
    void nodeCatalog_coversExactlyTheContractTypes() {
        Set<String> catalog = catalogTypes(build());
        Set<String> contract = NodeIoContract.types();

        assertEquals(13, contract.size(), "契约登记的类型数变了，本测试的前提要重新核");
        // 先比差集再比集合：报错能直接指出「哪个类型只有一侧有」，比 assertEqual 两个集合好定位
        Set<String> missing = new LinkedHashSet<>(contract);
        missing.removeAll(catalog);
        Set<String> invented = new LinkedHashSet<>(catalog);
        invented.removeAll(contract);
        assertEquals(Set.of(), missing, "这些类型契约里有、prompt 没教 —— 模型压根不知道有这种节点");
        assertEquals(Set.of(), invented, "这些类型 prompt 教了、契约里没有 —— 模型会产出一个编译期就拒的 type");
    }

    @Test
    @DisplayName("目录标题写的数量与实际列出的条数一致（「8 种执行器 + 3 种子图」曾漏掉 script 与 bpm_approval）")
    void nodeCatalog_countInProseMatchesReality() {
        String text = build();
        Matcher m = Pattern.compile("只能是下面这 (\\d+) 种").matcher(text);
        assertTrue(m.find(), "目录标题应写明类型总数，好让「少列一种」在文本上就看出来");
        assertEquals(catalogTypes(text).size(), Integer.parseInt(m.group(1)),
                "标题写的数量与实际列出的条数不一致 —— 这正是当初漏掉 script / hitl.bpm_approval 却没人发现的形态");
    }

    // ==================== 🔴 waitType 取值 ↔ WaitTypeEnum（缺陷 E2） ====================

    /**
     * prompt 教的 {@code waitType} 取值，与 {@link WaitTypeEnum} 里 {@code supported=true} 的集合<b>恰好一致</b>
     *
     * <p>为什么这一条不能省：前端 {@code nodeConfigSchema.ts} 那份 waitType 下拉是<b>副本不是引用</b>
     * （Java 枚举跨不到 TS 那边），prompt 这一份同理 —— 它是一段文本块里的 {@code "duration"} / {@code "event"}。
     * 两份副本都不会因为枚举改了而编译失败，只会<b>静默地</b>继续教旧取值。
     * 与 E1（节点目录 ↔ 契约）是同一条纪律，故同样<b>两边都比</b>：
     * <ul>
     *   <li>只钉「少教」：把 {@code hitl} 也教给模型照样绿 —— 而 {@code hitl} 是编译器<b>刻意拒</b>的一档
     *       （叫作者改用 {@code hitl.confirm}），模型产出它等于白烧一轮自修正；</li>
     *   <li>只钉「多教」：把 wait 那一行整段删掉也照样绿 —— 模型于是不知道 wait 要配 waitType，
     *       产出的 DSL 在编译期就拒。</li>
     * </ul>
     *
     * <p>⚠️ 抽取口径是「整份 prompt 里所有 {@code "waitType":"xxx"}」，不是只扫节点目录那一段：
     * 今天 few-shot 里没有 wait 样例，但将来若加了一个，那份样例教的取值同样得算数 ——
     * 模型照抄样例的概率比照抄目录高。
     */
    @Test
    @DisplayName("prompt 教的 waitType 取值与 WaitTypeEnum 支持的那两档恰好一致（不多不少）")
    void promptWaitTypeValues_coverExactlyTheSupportedEnumValues() {
        Set<String> taught = waitTypeValuesInPrompt(build());
        Set<String> supported = new LinkedHashSet<>();
        for (WaitTypeEnum item : WaitTypeEnum.values()) {
            if (item.isSupported()) {
                supported.add(item.getValue());
            }
        }

        assertEquals(2, supported.size(),
                "WaitTypeEnum 支持的档数变了，本测试的前提要重新核（实际：" + supported + "）");
        Set<String> missing = new LinkedHashSet<>(supported);
        missing.removeAll(taught);
        Set<String> invented = new LinkedHashSet<>(taught);
        invented.removeAll(supported);
        assertEquals(Set.of(), missing,
                "这些 waitType 取值枚举支持、prompt 没教 —— 模型压根不知道能这么等");
        assertEquals(Set.of(), invented,
                "这些 waitType 取值 prompt 教了、枚举不支持 —— 模型会产出一个编译期就拒的 wait 节点"
                        + "（尤其别把 hitl 教出去：那一档是<b>刻意</b>不做的，请作者用 hitl.confirm）");
    }

    // ==================== 🔴 控件白名单 ↔ 契约 ↔ 校验器 ====================

    /**
     * 白名单里列出的 type，与契约里 {@code supported=true} 的集合<b>恰好一致</b>
     *
     * <p>与 {@link #nodeCatalog_coversExactlyTheContractTypes} 同一条纪律，也必须<b>两边都比</b>：
     * 只钉「少列」的话，把 8 个已停用控件也一起列进来照样绿 —— 而 {@code WidgetContractGetTool}
     * 的类注释记着正是这么炸的（清单里躺着一条 {@code upload}，模型无视 supported 标记选了它，
     * 一路到运行期才报「已停用」）。只钉「多列」的话，漏掉 {@code OrgPicker} 也照样绿 ——
     * 而那就是 2026-10-04 T2 里 id=55/57 连猜六个控件名、各烧满六轮的直接原因。
     */
    @Test
    @DisplayName("控件白名单列出的 type 与契约里 supported=true 的集合恰好一致（不多不少）")
    void widgetCatalog_coversExactlyTheSupportedTypes() {
        Set<String> listed = widgetCatalogTypes(build());
        Set<String> supported = supportedTypes();

        Set<String> missing = new LinkedHashSet<>(supported);
        missing.removeAll(listed);
        Set<String> invented = new LinkedHashSet<>(listed);
        invented.removeAll(supported);
        assertEquals(Set.of(), missing,
                "这些控件契约里可用、prompt 没列 —— 模型看不见就只能凭记忆编，编了必被校验拒、每轮回灌白烧一次计费");
        assertEquals(Set.of(), invented,
                "这些 type prompt 列了、契约里并不支持 —— 模型照 prompt 写、校验器照契约拒，回灌里还看不出分歧在哪");
    }

    /**
     * 🔴 prompt 教的每一个 type，校验器都必须<b>当场认下</b>——带标记的那几个除外，它们必须<b>当场被拒</b>
     *
     * <p>上一条比的是「prompt 与契约」，这一条比的是「prompt 与<b>校验器</b>」—— 后者才是本类
     * 存在的理由（见类注释：prompt 与校验器必须同口径）。两份比对不等价：
     * {@code FormSchemaValidator} 在契约之上还叠了大小写归一与语义别名，
     * 万一它哪天把某个规范名归一到了别处，上一条仍会绿，而模型照 prompt 写的名字会被拒。
     *
     * <p>用<b>同一个</b> contract mock 喂两个读者，才叫同口径；各喂一份就是各说各话。
     *
     * <p>🔴 本条原先断言的是「白名单里<b>每一个</b> type 都被 {@code checkWidgetType} 认下」。
     * 2026-10-10 起这句话在生产上<b>已经不成立</b>：白名单照印全部 {@code supported=true} 的控件，
     * 而其中带 {@code ⚠️AI建不出来} 标记的那批（真契约 63 个里有 18 个）恰恰要被规划期拒掉 ——
     * 印出来是为了让模型<b>看得见自己在躲什么</b>，不是为了让它选。
     * 它当时没变红只是因为替身里一个控件都没配 props（判据算不出缺口）。
     * 那是本类注释里点过名的同一种假绿：替身没覆盖到形状 ⟹ 断言恒真 ⟹ 守卫悄悄失效。
     * 所以现在按「带不带标记」分两半断言，两半都必须非空（见 {@link #unauthorableTypes}）。
     */
    @Test
    @DisplayName("白名单里没带标记的 type 都被校验器认下、带标记的都被拒（prompt 与校验器同口径）")
    void widgetCatalog_everyListedTypeIsAcceptedByTheValidator() {
        FormSchemaValidator validator = new FormSchemaValidator();
        ReflectionTestUtils.setField(validator, "formDesignApi", formDesignApi);

        String text = build();
        Set<String> listed = widgetCatalogTypes(text);
        // 🔴 先钉「抠到了东西」：不钉的话，抠取正则一旦失配，下面那个循环压根不进，
        //    rejected 恒为空、本用例恒绿 —— 而它要证的「prompt 与校验器同口径」根本没被证。
        //    （同 customTool_shadowedByBuiltin_isOmitted 里那条「对照半边一旦恒真就挡不住缺陷」的疤）
        // 期望条数从契约替身推，不写死数字：写死 6 的时候，为了验 JSON 那摊事往替身里加
        // inputNumber / timePicker 就把它顶红了，而渲染和抠取一个字没变 —— 那是假红。
        // 推导不会削弱守卫：listed 是从**渲染后的 prompt** 里抠的，正则失配就是 0 ≠ 10，照样红。
        Set<String> supported = supportedTypes();
        assertEquals(supported.size(), listed.size(),
                "契约替身里 supported=true 的有 " + supported.size() + " 个，从 prompt 里抠到的条数对不上，"
                        + "说明渲染或抠取变了，本测试的前提要重新核");

        Set<String> marked = unauthorableTypes(text);
        Map<String, String> rejected = new HashMap<>();
        Map<String, String> accepted = new HashMap<>();
        for (String type : listed) {
            String problem = validator.checkWidgetType(type);
            if (marked.contains(type)) {
                if (problem == null) {
                    accepted.put(type, "带标记却放行");
                } else if (!problem.contains(FormFieldFactory.UNAUTHORABLE_MARK)) {
                    accepted.put(type, problem);
                }
            } else if (problem != null) {
                rejected.put(type, problem);
            }
        }
        assertEquals(Map.of(), rejected,
                "prompt 教了这些 type、校验器却拒 —— 模型照 prompt 写就撞墙，而回灌给它的补救建议指向的正是这份 prompt");
        assertEquals(Map.of(), accepted,
                "prompt 打了「AI建不出来」的标记、校验器却放行（或拒的理由里没提这个标记）—— "
                        + "两侧不同口径的表现是：模型被拦下却看不懂自己错在哪，只会换个控件名再猜一轮");
        // 🔴 两半都得非空：任何一半为空，上面那个循环就退化成恒真（正是本条要消灭的假绿）
        assertFalse(marked.isEmpty(), "替身里应当有「建不出来」的控件，否则本条只证了半边");
        assertTrue(marked.size() < listed.size(), "替身里也应当有建得出来的控件，否则本条只证了另半边");
    }

    /**
     * 🔴 「在白名单里」与「AI 建得出来」必须在 prompt 里就分成两件事说
     *
     * <p>复现的缺陷：需求里出现「按字典选」「省市区级联」时，模型<b>照这份清单</b>选了
     * {@code DictSelect} / {@code cascader}——它们确实 {@code supported=true}，清单也确实列了它们，
     * 于是草稿照存、发布照过、AI 回一句「表单已建好」，用户点进去是一个点不开的下拉。
     * 一路<b>没有任何一处</b>会主动说这一格是空的。
     *
     * <p>打不打标由 {@code FormFieldFactory#checkWidgetAuthorable} 逐次现算（读的是同一份契约的
     * {@code props.required} 与键名），所以本条断言的期望值也从替身推，<b>不写死控件名</b> ——
     * 写死的话，契约给某个控件补上必填项、或 {@code toRule} 学会写那一格，这里就成了假话。
     */
    @Test
    @DisplayName("建不出来的控件逐行打标（并说清缺哪一格），段末再汇总一次")
    void widgetCatalog_marksWidgetsAiCannotAuthor() {
        String text = build();
        String section = section(text, "## 控件白名单", "## 样例");

        Set<String> marked = unauthorableTypes(text);
        Set<String> expected = new LinkedHashSet<>();
        for (FormDesignApi.WidgetSimpleRespDTO widget : WIDGET_CONTRACTS) {
            if (FormFieldFactory.checkWidgetAuthorable(widget) != null) {
                expected.add(widget.getType());
            }
        }
        assertEquals(expected, marked,
                "打标的那几个与判据现算出来的不是同一批 —— 渲染漏标就是「模型看得见清单、看不见禁令」");
        assertFalse(expected.isEmpty(), "替身里必须有建不出来的控件，否则本条恒绿");

        // 逐行那个标记要<b>就地说清缺哪一格</b>：只印一个符号，模型不知道该改什么，
        // 而这条路径上它没有任何工具可查（见 widgetCatalog_overridesTheUnreachableToolAdvice）
        assertTrue(section.contains("- DictSelect（字典选择器）【"
                        + FormFieldFactory.UNAUTHORABLE_MARK + "：契约要求 dictType，"),
                "必填项那一档没在行内说清缺哪一格。实际段落：" + section);
        assertTrue(section.contains("- cascader（级联选择）【"
                        + FormFieldFactory.UNAUTHORABLE_MARK + "：契约要求 选项来源（props."),
                "选项来源那一档没在行内说清缺什么。实际段落：" + section);

        // 🔴 反向对照，与上面两条<b>必须成对</b>存在：input 在真契约里带三个 props
        //    （validateRules / uniqueCheck / maskRule），但一个都不必填。
        //    只钉「该标的标了」的话，把「凡带 props 的一律打标」也能绿 ——
        //    而那会把清单里绝大多数控件标成建不出来，标记一多就等于没有标记
        assertFalse(section.contains("- input（单行输入）【"),
                "有 props 但都不必填的控件不该被打标。实际段落：" + section);

        // 段末汇总：把带标记的<b>点出名</b>、给出条数，并给两条真能照做的出路。
        // 控件名与条数都从 unauthorable 现渲染，故这里只断言「点到了名、说清了后果」
        assertTrue(section.contains("标记的这 " + expected.size() + " 个控件"),
                "段末汇总的条数与实际打标数对不上 —— 模型会以为清单里还藏着没印出来的禁令");
        for (String type : expected) {
            assertTrue(section.contains(type), "段末汇总漏点了 " + type);
        }
        assertTrue(section.contains("写了<b>不报错</b>"),
                "汇总段没说清后果是「不报错的失效」—— 不说后果，模型只会把标记当成风格建议");
        assertTrue(section.contains(FormFieldFactory.optionWidgetCatalog()),
                "汇总段要给出路：选择类需求改用哪几个控件 + options/dictType");
        assertTrue(section.contains("如实说清"),
                "用户点名要这类控件时的出路（建好其余部分 + 如实说清哪一格要人工配）没印出来");
    }

    /** 从「## 控件白名单」那段里抓出所有带 {@code ⚠️AI建不出来} 标记的 type */
    private static Set<String> unauthorableTypes(String text) {
        String catalog = section(text, "## 控件白名单", "## 样例");
        Set<String> types = new LinkedHashSet<>();
        Matcher m = Pattern.compile("^- ([A-Za-z][A-Za-z0-9_]*)（.*【"
                + Pattern.quote(FormFieldFactory.UNAUTHORABLE_MARK), Pattern.MULTILINE).matcher(catalog);
        while (m.find()) {
            types.add(m.group(1));
        }
        return types;
    }

    // ==================== 🔴 建表列类型白名单（缺陷 D4-15） ====================

    /**
     * prompt 印出来的列类型清单，必须与 {@code DataModelApi#getColumnTypeList()} 给的<b>一模一样</b>
     *
     * <p>🔴 这条守的是「同源」这件事在渲染层没被弄丢。判「这个 columnType 收不收」的
     * {@code DataModelCreateValidator:69} 读的就是这个出口，所以只要渲染不漏不加，
     * 模型照 prompt 写就不可能撞建表校验。
     *
     * <p>此前的写法是手编一句「STRING/TEXT/INT/LONG/DECIMAL/BOOLEAN/DATE/DATETIME <b>等</b>」：
     * 漏了 LONG_TEXT 与 TINYINT，末尾那个「等」还明写着「剩下的自己猜」，
     * 工具名也写成了单数 {@code datamodel_column_type}（真名带 s）。
     * run8 的示例 57 就是这么红的 —— 模型写了个 JSON，而建表枚举里没有它。
     */
    @Test
    @DisplayName("列类型白名单印的就是 getColumnTypeList() 那一份，一个不多一个不少")
    void columnTypeCatalog_listsExactlyTheValidatorSource() {
        String section = section(build(), "## 建表列类型白名单", "## 控件白名单");

        Set<String> listed = new LinkedHashSet<>();
        Matcher m = Pattern.compile("^- ([A-Z][A-Z0-9_]*)（", Pattern.MULTILINE).matcher(section);
        while (m.find()) {
            listed.add(m.group(1));
        }
        // 🔴 先钉「抠到了东西」：抠取正则一旦失配，下面那条相等断言会变成「空 == 空」以外的假红，
        //    或者更糟 —— 与「清单没取到」那条降级分支的空白混为一谈
        Set<String> expected = new LinkedHashSet<>();
        for (DataModelApi.ColumnTypeRespDTO type : COLUMN_TYPES) {
            expected.add(type.getType());
        }
        assertEquals(expected, listed,
                "prompt 印的列类型与 getColumnTypeList() 给的不是同一份 —— 模型照 prompt 写就会撞建表校验");

        // 「必须显式给 length / scale」这一条也是从 DTO 渲染的，不是写死的：
        // 真清单里只有 STRING（length）与 DECIMAL（length + scale）需要，其余都不需要
        assertTrue(section.contains("- STRING（varchar）：短文本，需指定长度【length 必填】"),
                "STRING 那一行没把 needLength 渲染出来 —— 模型会漏给 length，建表被拒");
        assertTrue(section.contains("- DECIMAL（decimal）：小数，需指定长度与精度【length 必填、scale 必填】"),
                "DECIMAL 那一行没把 needLength + needScale 一起渲染出来");
        assertFalse(section.contains("- INT（int）：整数【"),
                "INT 不需要 length，却渲染出了【…】—— needLength=false 没被认下");
        // 🔴 JSON 的 description 是**唯一**能把「不能做主键或索引列」这件事告诉模型的地方：
        //    这条路径上没有任何工具可调，而 DataModelCreateValidator 拦下 JSON 索引列时
        //    建表流程已经走了一半。所以这句必须原样印出来，改枚举描述时要回来看这条
        assertTrue(section.contains("- JSON（json）：JSON 文档，不能做主键或索引列"),
                "JSON 那一行没把「不能做主键或索引列」印给模型 —— 它会去建一个必被拒的 JSON 索引");
    }

    /**
     * 控件契约宣称能绑、而建表枚举里<b>没有</b>的列类型，必须在控件白名单里当场打标
     *
     * <p>🔴 这是 D4-15 的另一半，也是 run8 真正咬人的那一半：{@code OrgPicker} 的契约写着
     * 能绑 {@code STRING/TEXT/JSON/LONG}，杠杆A 把契约原样印进「可绑定的列类型」，
     * 模型照印出来的 JSON 去写 {@code columnType}，撞上的却是没有 JSON 的建表枚举。
     * 只印权威清单、不给契约里那些多出来的类型打标，模型仍会先去撞自己刚读到的那一行。
     *
     * <p>🔴 JSON 那一对矛盾已在根上修掉（枚举里加了 JSON），所以本条的素材换成
     * <b>仍然活着</b>的分歧：{@code INTEGER}（真契约里 12 个控件在宣称，枚举里叫 {@code INT}）
     * 与 {@code TIME}（1 个控件在宣称，枚举里没有）。这段打标逻辑因此**不是死代码** ——
     * 契约与枚举是两份手工维护的清单，它是两者之间唯一的一致性检查。
     *
     * <p>⚠️ 契约与枚举本身的不一致<b>不在这里修</b>（要同源得让一边引另一边，那是另一刀）；
     * 本条只保证「模型看到的两份清单不再互相矛盾而无提示」。
     */
    @Test
    @DisplayName("契约宣称能绑、建表不收的列类型：逐行打标 + 段末汇总，两处都点到名")
    void widgetCatalog_marksColumnTypesTheTableBuilderRejects() {
        String section = section(build(), "## 控件白名单", "## 样例");

        // INTEGER / TIME 是替身里建表**不收**的两个类型，各自只出现在一个控件上
        assertTrue(section.contains("- inputNumber（数字输入）：INTEGER⚠️建表不收/LONG/DECIMAL"),
                "INTEGER 没被打标 —— 建表枚举里是 INT，模型照这一行写 columnType: INTEGER 必被拒");
        assertTrue(section.contains("- timePicker（时间）：TIME⚠️建表不收/STRING/DATETIME"),
                "TIME 没被打标 —— 建表枚举里没有 TIME，模型照这一行写必被拒");
        // 🔴 JSON 现在**可建**了（2026-10-04 用户裁定「改建表枚举，加上 JSON」），
        //    所以 OrgPicker 那一行必须一个标都不带。这条断言守的正是那笔裁定在 prompt 层生效了
        assertTrue(section.contains("- OrgPicker（组织选择器）：STRING/TEXT/JSON/LONG\n"),
                "JSON 已经进建表枚举了却仍被打标 —— 模型会白白放弃一个能用的类型（D4-15 的根治没生效）");
        // 同一行里建表**收**的类型不能被误标，否则模型会以为什么都不能建
        assertTrue(section.contains("- datePicker（日期）：DATE/DATETIME/STRING/LONG\n"),
                "datePicker 的四个类型建表全收，却被打标或渲染变形了");
        // 段末汇总要把「哪些类型不收」点出来，光有符号没有解释等于没写。
        // 顺序钉的是 WIDGET_CONTRACTS 里的声明顺序（inputNumber 在 timePicker 之前），
        // 那份列表就在本文件上面，改顺序的人应当顺手改这里
        assertTrue(section.contains("（INTEGER、TIME）是<b>控件契约宣称能绑、而建表枚举里没有</b>的"),
                "段末汇总没把 INTEGER 与 TIME 都点出来 —— 逐行那个符号就成了无处解释的噪音");
    }

    /**
     * 列类型清单没取到时，必须<b>明说</b>「别建表」，不能静默少一段
     *
     * <p>与 {@code customToolLookupFailure_degradesToBuiltinOnly} 同一路数：降级可以，
     * 但降级要在正文里说出来。留白的后果是模型回去凭记忆猜类型名 —— 那正是本段要修的事。
     */
    @Test
    @DisplayName("getColumnTypeList() 抛异常：清单段降级成「别建表」，且整份 prompt 照样建得出来")
    void columnTypeCatalog_lookupFailureTellsModelNotToCreateTables() {
        when(dataModelApi.getColumnTypeList()).thenThrow(new RuntimeException("infra 挂了"));

        String text = build();
        String section = section(text, "## 建表列类型白名单", "## 控件白名单");
        assertTrue(section.contains("这份清单本次<b>没取到</b>"),
                "取不到清单却没在正文里说出来 —— 模型看不见这一段就会凭记忆猜类型名");
        assertTrue(section.contains("就<b>不要</b>编排建表节点"),
                "降级分支没告诉模型别建表");

        // 🔴 清单没取到时，控件白名单里**一个标记都不该打**：
        //    creatableTypes 为空意味着「不知道收不收」，不是「全都不收」。
        //    把全部类型都标成「建表不收」比不标更坏 —— 模型会以为平台压根不能建表
        String widgets = section(text, "## 控件白名单", "## 样例");
        assertFalse(widgets.contains("⚠️建表不收"),
                "清单没取到就把契约里所有列类型都标成「建表不收」—— 那是把「不知道」说成了「不行」");
        assertTrue(widgets.contains("- OrgPicker（组织选择器）：STRING/TEXT/JSON/LONG"),
                "降级时控件那几行应原样渲染，不带任何标记");
    }

    /**
     * 已停用控件只能出现在<b>黑名单段</b>，不能混进可选清单
     *
     * <p>点名停用项是本段与 {@code WidgetContractGetTool} <b>刻意相反</b>的取舍（理由见
     * {@code appendWidgetCatalog} 的 javadoc）：那六个猜测名全是「用户/部门选择器」的变体，
     * 而这类控件在本平台曾经存在过又被删掉，模型对它们有很强的先验。
     * 但「点名」必须与「可选」在文本上分得开 —— 混进清单就是 {@code upload} 事故重演。
     */
    @Test
    @DisplayName("已停用控件出现在黑名单段里、不出现在可选清单里")
    void widgetCatalog_unsupportedTypesAreBlacklistedNotSelectable() {
        String section = section(build(), "## 控件白名单", "## 样例");
        Set<String> selectable = widgetCatalogTypes(build());

        for (FormDesignApi.WidgetSimpleRespDTO widget : WIDGET_CONTRACTS) {
            if (Boolean.TRUE.equals(widget.getSupported())) {
                continue;
            }
            assertFalse(selectable.contains(widget.getType()),
                    widget.getType() + " 已停用，不该出现在可选清单里");
            assertTrue(section.contains(widget.getType() + "「"),
                    widget.getType() + " 已停用，但要在黑名单段里点名（模型对这类名字有很强的先验，"
                            + "不点名它就会先去撞自己最熟的那个）。实际段落：" + section);
        }
        assertTrue(section.contains("存在但已停用"), "黑名单段要说清「存在但不能用」，别让它读起来像第二份清单");
    }

    /**
     * 🔴 这一段必须自己说清「你调不到工具」，且排在工具目录<b>之后</b>
     *
     * <p>{@code FormSaveDraftTool} 的 description 与 paramsSchema 都在教「先用 widget_contract_get
     * 拿白名单」，那两段被 {@code appendTools} 原样拼进工具目录 —— 而 planner 这条路径上
     * 没有任何工具可调。矛盾无法在源头消除（那句话在<b>执行期</b>是对的，删不得），
     * 只能由这一段就地纠正。故：① 必须显式说「没有任何工具可调」；
     * ② 必须排在工具目录之后，让纠正的话是模型最后读到的那份。
     */
    @Test
    @DisplayName("白名单段显式声明「这条路径上没有工具可调」，且排在工具目录之后")
    void widgetCatalog_overridesTheUnreachableToolAdvice() {
        String text = build();
        assertTrue(text.indexOf("## 控件白名单") > text.indexOf("## 工具目录"),
                "白名单必须排在工具目录之后：纠正的话要比被纠正的那句更晚出现");
        assertTrue(section(text, "## 控件白名单", "## 样例").contains("没有任何工具可调"),
                "工具目录里那句「必须来自 widget_contract_get」在这条路径上照做不了，白名单段必须自己说清");
    }

    /** 从「## 控件白名单」那段里抓出所有 {@code - type（…）} 的 type */
    private static Set<String> widgetCatalogTypes(String text) {
        String catalog = section(text, "## 控件白名单", "## 样例");
        Set<String> types = new LinkedHashSet<>();
        Matcher m = Pattern.compile("^- ([A-Za-z][A-Za-z0-9_]*)（", Pattern.MULTILINE).matcher(catalog);
        while (m.find()) {
            types.add(m.group(1));
        }
        return types;
    }

    // ==================== 🔴 few-shot 是真 DSL，不是好看的字符串 ====================

    @Test
    @DisplayName("三份 few-shot 样例都能被 DslParser 解析成 WorkflowDefDTO")
    void fewShotSamplesAreParsableDsl() {
        List<String> samples = samples();
        assertEquals(3, samples.size(), "样例数变了要同步改本测试，别让它悄悄少守一份");

        for (int i = 0; i < samples.size(); i++) {
            WorkflowDefDTO def = parser.parse(samples.get(i));
            assertNotNull(def, "样例" + (i + 1) + " 解析不出来");
            assertFalse(def.getNodes() == null || def.getNodes().isEmpty(), "样例" + (i + 1) + " 没有节点");
            assertFalse(def.getEdges() == null || def.getEdges().isEmpty(), "样例" + (i + 1) + " 没有边");
        }
    }

    @Test
    @DisplayName("样例里出现的每个 config 键都在 NodeIoContract 登记过（A3(c)）")
    void fewShotConfigKeysAreAllRegistered() {
        List<String> offenders = new ArrayList<>();
        for (String sample : samples()) {
            for (NodeDTO node : parser.parse(sample).getNodes()) {
                NodeIoContract.Contract contract = NodeIoContract.of(node.getType());
                if (contract == null) {
                    offenders.add(node.getType() + "：契约里没有这个类型");
                    continue;
                }
                Set<String> registered = new HashSet<>();
                contract.inputs().forEach(p -> registered.add(p.key()));
                // outputVar / approveVar / statusVar 这些是**输出**端口的 configKey，不在 inputs 里
                contract.outputs().forEach(p -> {
                    if (p.configKey() != null) {
                        registered.add(p.configKey());
                    }
                });
                Map<String, Object> config = node.getConfig();
                if (config == null) {
                    continue;
                }
                for (String key : config.keySet()) {
                    if (!registered.contains(key)) {
                        offenders.add(node.getType() + "." + key);
                    }
                }
            }
        }
        // 🔴 这条是 prompt 与契约之间唯一的机械 linkage：样例是模型照抄的对象，
        //    抄一个契约没登记的键，运行期要么静默不生效、要么编译期拒，两种都比 CI 变红贵得多
        assertEquals(List.of(), offenders, "样例里出现了契约未登记的 config 键");
    }

    /**
     * 样例3 的建表参数必须是 {@code args.model} 那套四层嵌套
     *
     * <h3>🔴 这条守的是「config 键回查契约」守不住的那一层</h3>
     * {@link #fewShotConfigKeysAreAllRegistered} 只查 config 的<b>键名</b>在不在 {@code NodeIoContract} 里，
     * 而 {@code args} 当然是 {@code tool} 节点登记过的键 —— 于是样例把 args 的<b>内容</b>写错时它全绿。
     * 实测就是这么漏过去的：样例3 曾写成 {@code "args":{"name":"{{$.table_name}}"}}（一个扁平单键），
     * 而 {@code datamodel_create} 真正读的是 {@code args.model.{tableName,tableComment,columns[]}}
     * （{@code DataModelParamResolver.CREATE_PARAMS_SCHEMA}），
     * {@code ChangeManifestDryRunner} 会调 {@code resolveCreate} 按这个形状解析、扁平单键直接拒。
     *
     * <p>代价不是「样例不好看」：模型缺了四层嵌套的参照，真需求要建一张五个字段的表时只能凭空造，
     * 然后在 {@code columns} 数组收尾处少写一个 {@code &#125;} —— 一次建表编排六轮里五轮错在
     * <b>同一个字符位置</b>，第 3~6 轮原文 md5 完全相同，约十万 prompt token 烧完仍出不了草稿。
     *
     * <p>⚠️ 刻意<b>不</b> import {@code DataModelColumnTypeEnum} 去比对 columnType 全集：
     * 它在 infra 的 {@code enums.datamodel} 包、不在 {@code api/} 下，agent 模块引它就破了
     * 「跨模块只走 api」那条红线（{@code DataModelParamResolver} 只引 {@code infra.api.datamodel.*}）；
     * 而在本类抄一份字面量清单，又造出第二个副本 —— 改枚举时这里不会跟着动，比不守更坏。
     * 故只钉两件不会漂的事：<b>必须全大写</b>（枚举名而非 MySQL 类型名），
     * 以及<b>不是那几个真被模型写出来过的 MySQL 拼法</b>。
     */
    @Test
    @DisplayName("样例3 的 datamodel_create args 是 CREATE_PARAMS_SCHEMA 要的四层嵌套（不是一个扁平单键）")
    void headlessSample_createTableArgsMatchTheRealParamsSchema() {
        WorkflowDefDTO def = parser.parse(samples().get(2));
        String nodeId = nodeOfType(def, "tool", RELAXED_TOOL);
        assertNotNull(nodeId, "样例3 应含一个调 " + RELAXED_TOOL + " 的 tool 节点");
        NodeDTO node = def.getNodes().stream()
                .filter(n -> nodeId.equals(n.getId())).findFirst().orElseThrow();

        Map<String, Object> args = node.configMap("args");
        assertNotNull(args, "建表节点的 config 里应有 args");

        Object rawModel = args.get("model");
        assertTrue(rawModel instanceof Map,
                "datamodel_create 的参数必须整块嵌在 args.model 里，实际 args 的键：" + args.keySet());
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) rawModel;

        for (String forbidden : List.of("id", "dataSourceConfigId", "systemColumnProfile")) {
            assertFalse(model.containsKey(forbidden),
                    "model 里不许出现 " + forbidden + "（平台自己补，传了会被拒）");
        }

        String tableName = nonBlank(model.get("tableName"));
        assertNotNull(tableName, "model.tableName 必填");
        assertTrue(tableName.startsWith("biz_"), "tableName 必须以 biz_ 开头，实际：" + tableName);
        // 🔴 只能是字面量：StateTemplateResolver 只替换 args 的**顶层**字符串值
        //    （ToolNodeExecutor 那句 `v instanceof String s ? resolve(s, ctx) : v`），
        //    嵌在 model 里的 {{$.x}} 原样传下去，然后被「不以 biz_ 开头」拒掉。
        //    样例曾教模型用 script 算表名再 {{$.table_name}} 引过来 —— 那条路根本走不通。
        assertFalse(tableName.contains("{{"),
                "tableName 不能用 {{$.变量}}：嵌套位置的模板不会被替换，实际：" + tableName);

        String tableComment = nonBlank(model.get("tableComment"));
        assertNotNull(tableComment, "model.tableComment 必填，要用中文说明这张表存什么");
        assertFalse(tableComment.equals(tableName), "tableComment 不能与表名相同");

        Object rawColumns = model.get("columns");
        assertTrue(rawColumns instanceof List && !((List<?>) rawColumns).isEmpty(),
                "model.columns 必填且不能是空数组，实际：" + rawColumns);

        List<String> offenders = new ArrayList<>();
        for (Object rawColumn : (List<?>) rawColumns) {
            if (!(rawColumn instanceof Map)) {
                offenders.add("columns 里有一项不是对象：" + rawColumn);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> column = (Map<String, Object>) rawColumn;
            String columnName = nonBlank(column.get("columnName"));
            String columnType = nonBlank(column.get("columnType"));
            if (columnName == null || !columnName.matches("^[a-z][a-z0-9_]*$")) {
                offenders.add("columnName 必须是小写 snake_case：" + columnName);
            }
            if (nonBlank(column.get("columnComment")) == null) {
                offenders.add(columnName + "：columnComment 必填");
            }
            if (columnType == null) {
                offenders.add(columnName + "：columnType 必填");
            } else {
                if (!columnType.equals(columnType.toUpperCase())) {
                    offenders.add(columnName + "：columnType 要用平台的<b>大写枚举名</b>，不是 MySQL 类型名 → "
                            + columnType);
                }
                for (String mysqlSpelling : List.of("VARCHAR", "TIMESTAMP", "BIGINT", "DOUBLE", "FLOAT")) {
                    if (columnType.equalsIgnoreCase(mysqlSpelling)) {
                        offenders.add(columnName + "：columnType 写成了 MySQL 类型名 " + columnType
                                + "（短文本用 STRING 并显式给 length，长文本用 TEXT，日期时间用 DATETIME）");
                    }
                }
                if (("STRING".equals(columnType) || "DECIMAL".equals(columnType))
                        && column.get("length") == null) {
                    offenders.add(columnName + "：" + columnType + " 必须显式给 length");
                }
            }
            if (Boolean.TRUE.equals(column.get("primaryKey"))) {
                offenders.add(columnName + "：primaryKey 必须为 false（主键 id 由平台自动补）");
            }
        }
        assertEquals(List.of(), offenders, "样例3 的 columns 里有不符合建表参数说明的项");
    }

    /** 取非空字符串；不是字符串或全空白都算没填 */
    private static String nonBlank(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    @Test
    @DisplayName("无头样例：不可逆工具的上游确有 hitl.bpm_approval，且全图没有 hitl.confirm（V11/V13）")
    void headlessSample_gatesTheIrreversibleToolWithBpmApproval() {
        WorkflowDefDTO def = parser.parse(samples().get(2));
        assertEquals("trigger.schedule", def.getTrigger().getType(), "样例3 应是无头（定时）触发");

        String writeNode = nodeOfType(def, "tool", RELAXED_TOOL);
        assertNotNull(writeNode, "样例3 应含一个调 " + RELAXED_TOOL + " 的 tool 节点");

        String gate = nodeOfType(def, "hitl.bpm_approval", null);
        assertNotNull(gate, "样例3 应含一个 hitl.bpm_approval 闸口节点");
        Set<String> upstream = ancestorsOf(def, writeNode);
        assertTrue(upstream.contains(gate),
                "不可逆动作上游必须能走到 BPM 审批闸口，实际上游：" + upstream);
        assertEquals(-1, indexOfType(def, "hitl.confirm"),
                "无头流程里出现 hitl.confirm 会被 V13 判 error（没有聊天窗口，卡推给谁？）—— 样例绝不能教这个");
    }

    @Test
    @DisplayName("webhook 鉴权规则教了 authType 的两种合法取值与 secret（V15）")
    void webhookAuthRuleIsTaught() {
        // 🔴 必须切出「3. webhook 触发器必须配鉴权」那一段再断言，不能对整份 prompt 断言 contains：
        //    authType/hmac/token/secret 这四个词在上面**节点类型目录**里就已经出现过一遍了 ——
        //    trigger.webhook 那行的 config 形状示意写着 {"path":…,"authType":"hmac 或 token","secret":"校验密钥"}
        //    （WorkflowDslPlannerPrompt:215），比本用例要守的闸口规则（:228-232）早十几行。
        //    ⟹ 把那条 V15 规则**整段删掉**，原先三行 contains 也一律照绿，判据完全失效。
        //    形状同方案 P2-3 已抓的第 1 例（assertTrue(contains("截断")) 撞上「所以**不是**截断」）。
        //    section() 自带阳性对照：起点标记找不到它自己就先红。
        String rule = section(build(), "3. **webhook 触发器必须配鉴权**", "## 工具目录");
        assertTrue(rule.contains("authType"), "必须教 authType 这一格，否则模型产出的 webhook 流程稳定撞 V15");
        assertTrue(rule.contains("hmac") && rule.contains("token"),
                "必须给出两种合法取值，只说「要配鉴权」模型会自己编一个");
        assertTrue(rule.contains("secret"), "hmac/token 都必须同时配 secret，漏教会被入站一律拒收");
    }

    @Test
    @DisplayName("聊天回复约定教的是「每一条走到 END 的腿」，不是「整张图有一个就行」（V20）")
    void chatReplyRuleIsPerLeg() {
        // 🔴 必须切段再断言：output / outputVar 这两个词在上面的**节点类型目录**里已经出现过好几遍
        //    （llm/tool/script/knowledge_retrieval 各自的 config 形状示意都写着 outputVar），
        //    对整份 prompt 断言 contains("output") 是恒真的 —— 把这条规则的正文整段删掉也照样绿。
        //    形状同 webhookAuthRuleIsTaught 与方案 P2-3 抓的第 1 例。section() 自带阳性对照：
        //    起点标记找不到它自己就先红。
        String rule = section(build(), "- **聊天回复约定**", "## 边（edges[]）");

        assertTrue(rule.contains("每一条走到 END 的腿"),
                "口径必须钉在「每条腿」上。原先写的是「流程末尾必须留一个节点」，而模型**照字面满足了它**："
                        + "def 416 的同意腿有 show_inspect 写 output、驳回腿 default->END 一路上什么都没写 ——"
                        + "用户点完驳回只收到一句兜底，不知道自己驳回成功了没有。"
                        + "一句话教错，产出的每张分支图都缺一条腿的话术");
        assertFalse(rule.contains("流程末尾必须留一个节点"),
                "旧的那句「整张图有一个就行」必须已经不在：留着它，上面那条断言就成了两句互相打架的话，"
                        + "模型挑哪句读都对");
        assertTrue(rule.contains("驳回"),
                "要点出分支图那个**具体**漏法。只说「每条腿都要写」，模型仍会给整张图配一个总结节点了事");
        assertTrue(rule.contains("警告"),
                "必须说清这一条是警告、不拦发布（V20 落在 AgentValidateResult.warnings 那一档）："
                        + "写成「违反即发布被拒」，模型会为了过闸门去改别的地方，而不是补那条腿的话术");
    }

    @Test
    @DisplayName("condition.rule 那一格教的是「三种错法都会被体检拦」，且教清了 V32 的射程边界")
    void ruleConditionTautologyIsTaughtAsBlocked() {
        // 🔴 必须切段再断言：exists / is_true / 布尔量 这些词在整份 prompt 的别处也出现过
        //    （样例3 就教了一遍「先算成布尔量再按 is_true 路由」），对整份 prompt 断言 contains 是恒真的 ——
        //    把本段正文整段删掉也照样绿。形状同 webhookAuthRuleIsTaught / chatReplyRuleIsPerLeg。
        //    section() 自带阳性对照：起点标记找不到它自己就先红。
        String rule = section(build(), "- **condition.rule**：", "- **condition.llm**：");

        assertTrue(rule.contains("三种都会被发布前体检判 error"),
                "①②③ 现在【全部】是 error：V24 管「没人写这个键」，V32 管「有个必经节点写了这个键，"
                        + "于是 exists / not_exists 恒真恒假」。②曾经只写在提示词里、体检沉默，"
                        + "2026-10-10 那次实跑（图书借阅登记，执行号 197）撞的正是②："
                        + "全图唯一的建表节点成了死代码，跑到 form_save_draft 才报「主表不存在」");
        assertFalse(rule.contains("看不出来"),
                "旧的那句「② 体检看不出来（那个键确实有人写），只能靠你别这么写」必须已经不在："
                        + "它是模型的输入，留着就是教它「这么写不会被拦」—— 那次实跑就是这么来的");
        assertTrue(rule.contains("病根不在算子"),
                "必须堵掉最省力的那条绕法。只说「这么写会被拦」，模型会把 exists 换成 not_exists 再交一次");
        assertTrue(rule.contains("别矫枉过正"),
                "🔴 还得把 V32 的【射程边界】教给模型：写入者只在某条分支上（另一条路绕开它）时，"
                        + "exists 问的正是「那条分支跑过没有」，那是合法写法、体检不拦。"
                        + "不教这一格，模型会把所有 exists 一律改掉 —— 包括那些真有信息量的，"
                        + "于是「查没查到」这类判定退化成恒走 default，症状与它要修的病一模一样");
    }

    // ==================== 🔴 用户要建/要改时，写步骤必须编进计划（缺陷 D4-16） ====================
    @Test
    @DisplayName("硬规则教了「用户明确要求建/改 ⟹ 写步骤必须编进计划」，不可逆不是省略的理由")
    void writeStepsAreMandatoryWhenTheUserAsksToBuild() {
        // 🔴 必须切段再断言：闸口 / 不可逆 / hitl 这些词在上面「触发器与人工闸口」那一整段里
        //    已经出现过好几遍，对整份 prompt 断言 contains("闸口") 是恒真的 —— 把本条规则整段删掉也照样绿。
        //    形状同 webhookAuthRuleIsTaught / chatReplyRuleIsPerLeg。section() 自带阳性对照。
        String rule = section(build(), "## 硬规则", "## 建表列类型白名单");

        assertTrue(rule.contains("用户明确要求建/改东西时，那些写步骤必须编进计划"),
                "缺这一句，硬规则就只剩单向抑制（「除非用户明确要求，否则优先用只读工具」），"
                        + "而「除非…否则…」读起来是**许可**不是**义务**。run10 的 id=57 就是这么红的："
                        + "用户开口就要建一张客户拜访记录表，交上来的四个节点却全是只读，"
                        + "manifest kinds=[]，草稿卡照样渲染 —— 用户点开看到的是一张什么也不建的卡");
        assertTrue(rule.contains("编进计划 ≠ 会被执行"),
                "必须正面拆掉模型自己的那条推理。它在 script 注释里逐字写着「datamodel_create 是不可逆操作，"
                        + "且需要人工闸口」所以「分阶段进行，第一阶段只做到生成 Schema 建议」——"
                        + "不点破「闸口在发布之前」，它就会继续把不可逆当成省略的理由");
        assertTrue(rule.contains("condition 分支"),
                "「先查有没有能复用的表 / 别急着建新表」必须给出**出路**（查到就复用、查不到就在闸口后面建），"
                        + "只说「不是停下来反问用户」而不说该写成什么形状，模型没有可抄的替代方案");
        assertTrue(rule.contains("别把计划切成"),
                "模型这次的收尾方式是「第一阶段先做到这里」+ 一个反问用户的 llm 节点，"
                        + "而「每条走到 END 的腿都要写回复键」那条规则它是**照字面满足了**的 —— "
                        + "反问也是一句回复。不点名这种切法，光靠回复键规则拦不住");
    }

    @Test
    @DisplayName("工具目录的写操作栏标题不再只说「非必要不用」，两个方向都写死")
    void writeToolBucketHeadingStatesBothDirections() {
        String text = build();
        String write = section(text, "### 写操作工具", "## 硬规则");

        assertTrue(write.contains("用户明确要求建/改时必须编进计划"),
                "写操作栏的标题是模型读到工具风险标签时**紧挨着**的一句话，只写「非必要不用」"
                        + "会被读成「能不用就不用」—— 这正是 D4-16 的半个病根");
        assertTrue(write.contains("照样把它编进计划"),
                "栏首那句引言必须把「不可逆 + 要有闸口」的**方向**掰正：它是编进去的理由，不是省掉的理由");
        // 🔴 断在整份 prompt 上，不是断在 write 这一段里：这句话原本就在标题上，
        //    改标题时若只是往后面追加而没删掉旧的，段落切片照样能切出「必须编进计划」，
        //    于是新旧两句互相打架的话会同时进模型 —— 与 chatReplyRuleIsPerLeg 里
        //    assertFalse("流程末尾必须留一个节点") 同一个道理。
        assertFalse(text.contains("非必要不用"),
                "旧的单向抑制措辞必须已经不在（注释里留着无妨，注释不进 prompt）："
                        + "留着它，上面两条断言就成了两句互相打架的话，模型挑哪句读都对");
    }

    @Test
    @DisplayName("解释「⚠️建表不收」的那句散文不再拿 JSON 举例（JSON 已经可建了）")
    void notCreatableProseDoesNotNameJsonAsExample() {
        // 🔴 这一条守的是**提示词自己与自己对打**：D4-15 根治时用户裁定「改建表枚举，加上 JSON」，
        //    JSON 从此**可建**，逐行清单里的 ⚠️建表不收 标记是现算的、自动就不再打在 JSON 上了；
        //    但 few-shot 里那句解释标记的散文当初把「典型就是 JSON」抄进了字面量，
        //    于是同一份 prompt 一边把 JSON 列进可建白名单、一边教模型「新建表时绝不能用 JSON」。
        String prose = section(build(),
                "🔴 控件白名单里有些控件的「可绑定的列类型」带着", "- 审批节点<b>没有</b>配 approveVar");

        assertFalse(prose.contains("JSON"),
                "解释「建表不收」的那段里不许再出现 JSON —— 它现在是**可建**类型，"
                        + "举例举一个自己白名单里收着的类型，模型会照着散文而不去看清单");
        assertTrue(prose.contains("以标记为准"),
                "必须把权威指向**现算的标记**而不是散文里抄的名单：名单会随枚举变，散文不会跟着变。"
                        + "这条断言与上一条是一对 —— 只删掉 JSON 那个例子而不给出「看标记」的指引，"
                        + "模型就没有任何依据判断哪些类型建表不收");
    }

    // ==================== 🔴 提示词教模型抄的那段 JS，必须在真沙箱里跑得通 ====================

    /** 真沙箱：本节要量的正是「这段 JS 在 GraalJS 里到底怎么表现」，mock 掉等于什么都没验 */
    private static final ScriptSandbox SANDBOX = new ScriptSandbox();

    /**
     * 从 prompt 正文里抽出 {@code pickRows} 的函数体（花括号配平扫描）。
     *
     * <p>🔴 刻意<b>不在测试里抄第二份</b>：抄第二份就是「同一段代码两处副本」，
     * 改了 prompt 忘了改测试（或反过来）都不会有人发现，而本节全部价值恰恰是
     * 「prompt 里教的那一份真能跑」。抽取失败必须响 —— 那说明提示词已经不再教这个函数，
     * 本节的实测依据要跟着换写法，而不是把断言删掉。
     *
     * <p>⚠️ 裸数花括号在这里是安全的：函数体内层那个 {@code function(k) { ... }} 自身配平，
     * 且所有字符串字面量都只用单引号、不含花括号。若将来往消息里加了 {@code &#123;}，
     * 本方法会报「没配平」而不是静默抽到半份代码。
     */
    private String pickRowsFromPrompt() {
        List<String> copies = allPickRowsCopies();
        assertFalse(copies.isEmpty(), "prompt 里找不到 pickRows —— 提示词不再教这个取值函数了？"
                + "别直接删本节：它是 def #620 那类「猜错列表键」缺陷的唯一实测依据");
        return copies.get(0);
    }

    /** prompt 里 {@code pickRows} 的<b>全部</b>副本，按出现顺序 */
    private List<String> allPickRowsCopies() {
        String text = build();
        List<String> copies = new ArrayList<>();
        for (int from = 0; ; ) {
            int start = text.indexOf("function pickRows(o, key) {", from);
            if (start < 0) {
                return copies;
            }
            copies.add(extractBraced(text, start));
            from = start + 1;
        }
    }

    private String extractBraced(String text, int start) {
        int depth = 0;
        for (int i = text.indexOf('{', start); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        throw new AssertionError("pickRows 的花括号没配平 —— prompt 里那段 JS 语法已经坏了");
    }

    /**
     * 跑一次取值：state 里放 {@code target}（被测的那个上游结果）与 {@code key}（点名的键，可空）。
     *
     * <p>{@code key} 传 null 时它在 JSON 里<b>整个消失</b>（{@code JsonUtils} 的 NON_NULL 设置），
     * JS 侧读成 {@code undefined} ⟹ {@code if (key)} 不成立 ⟹ 走「自动挑唯一数组」那条。
     * 这正是运行期的真实形状，不是测试特有的简化。
     */
    private Object pick(Object target, String key) {
        Map<String, Object> state = new HashMap<>();
        state.put("target", target);
        state.put("key", key);
        return SANDBOX.eval(pickRowsFromPrompt() + "\nreturn pickRows(state.target, state.key);",
                state, ScriptSandbox.DEFAULT_TIMEOUT_MS);
    }

    /** datamodel_structure 的真实形状：一次回<b>三个</b>数组，且第一个是系统列不是业务列 */
    private static Map<String, Object> structureResult() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tableName", "biz_customer_info");
        m.put("tableComment", "客户信息登记表");
        m.put("systemColumns", List.of(Map.of("columnName", "id"), Map.of("columnName", "creator")));
        m.put("businessColumns", List.of(Map.of("columnName", "customer_name")));
        m.put("indexes", List.of());
        return m;
    }

    @Test
    @DisplayName("datamodel_list 的 {total,models}：不点名也取得到（def #620 猜 items/list 拿到 [] 的现场）")
    void pickRows_dataModelListShape_findsModels() {
        Map<String, Object> listOut = Map.of("total", 1,
                "models", List.of(Map.of("id", 314, "tableName", "biz_customer_info")));

        Object rows = pick(listOut, null);
        assertInstanceOf(List.class, rows, "取不到列表就会重演 def #620 的静默 []");
        assertEquals(1, ((List<?>) rows).size());

        // 再把 extract_model_id 真正想做的事跑一遍：按 tableName 挑出 id
        Object id = SANDBOX.eval(pickRowsFromPrompt()
                        + "\nvar t = pickRows(state.model_list_out)"
                        + ".find(function(m){ return m.tableName === 'biz_customer_info'; });"
                        + "\nif (!t) throw new Error('没查到这张表');"
                        + "\nreturn t.id;",
                Map.of("model_list_out", listOut), ScriptSandbox.DEFAULT_TIMEOUT_MS);
        assertEquals(314, ((Number) id).intValue(),
                "取不到 id 就会重演 def #620：return null ⟹ 本节记 SUCCESS ⟹ "
                        + "下游只报一句「id 必须是数字，收到的是：」，根因藏在上一节");
    }

    @Test
    @DisplayName("form_list 的 {total,items,message} 与 data_interface_list 的 {total,list}：同样不点名就取得到")
    void pickRows_itemsAndListShapes_found() {
        assertEquals(2, ((List<?>) pick(
                Map.of("total", 2, "items", List.of("a", "b"), "message", "ok"), null)).size(),
                "form_list 那条本来就能跑的路不能被新写法弄坏");
        assertEquals(1, ((List<?>) pick(Map.of("total", 1, "list", List.of("x")), null)).size());
    }

    @Test
    @DisplayName("datamodel_physical_tables 的 {total,items,danglingRegistrations}：两个数组也不点名就取得对（执行 #190 的现场）")
    void pickRows_physicalTablesShape_prefersConventionalMainKey() {
        // 形状逐字取自 DataModelPhysicalTableTool：items 是主列表，danglingRegistrations 是旁路诊断、恒在（可能为空）
        Map<String, Object> physical = new LinkedHashMap<>();
        physical.put("total", 1);
        physical.put("items", List.of(Map.of("tableName", "biz_teacher")));
        physical.put("danglingRegistrations", List.of());

        Object rows = pick(physical, null);
        assertInstanceOf(List.class, rows);
        assertEquals(1, ((List<?>) rows).size(),
                "两个数组时必须靠惯例名挑中 items；挑不中就会重演执行 #190："
                        + "throw「猜不出该取哪个数组，候选：items,danglingRegistrations」，"
                        + "一条本该跑得通的「先查有没有能复用的表」流程当场死在第一步");
    }

    @Test
    @DisplayName("多个数组里有<b>两个</b>都叫惯例名 → 仍然 throw，惯例名不是「随便挑一个」的许可证")
    void pickRows_twoConventionalKeys_stillThrows() {
        Map<String, Object> ambiguous = new LinkedHashMap<>();
        ambiguous.put("items", List.of("a"));
        ambiguous.put("list", List.of("b"));
        ServiceException ex = assertThrows(ServiceException.class, () -> pick(ambiguous, null));
        String msg = String.valueOf(ex.getMessage());
        assertTrue(msg.contains("items") && msg.contains("list"),
                "两个惯例名同时在场时必须报出来让人点名，实际原文：" + msg);
    }

    /**
     * 🔴 这两条钉的是<b>逐个真调只读工具量出来的</b>形状（{@code /agent/diagnostic/tool-execute}），
     * 不是我照着源码猜的。
     *
     * <p>为什么要量：{@code MAIN} 那一步（执行 #190 的修复）只兜得住「多个数组里恰好一个沾惯例名」。
     * 实测 18 个只读工具，回<b>多个</b>数组的一共 3 个，其中
     * {@code datamodel_column_types} 与 {@code explain_form_rule} 的键<b>一个惯例名都不沾</b> ——
     * 对它们不点名照样 throw，和执行 #190 是同一个坑的另外两个活口。
     * 提示词原先只交代了 {@code datamodel_structure} 一个，模型没有理由知道另外两个。
     */
    @Test
    @DisplayName("datamodel_column_types 的 {types,选型指导}：不点名必 throw，点名 'types' 才取得到")
    void pickRows_columnTypesShape_requiresNaming() {
        Map<String, Object> columnTypes = new LinkedHashMap<>();
        columnTypes.put("types", List.of("STRING", "DECIMAL", "JSON"));
        columnTypes.put("选型指导", List.of("金额一律用 DECIMAL，绝不用浮点类型"));

        ServiceException ex = assertThrows(ServiceException.class, () -> pick(columnTypes, null),
                "两个键都不沾惯例名，自动挑必然失败 —— 这条 throw 是预期的，"
                        + "提示词因此必须把 datamodel_column_types 列进「必须点名」那一档");
        assertTrue(String.valueOf(ex.getMessage()).contains("选型指导"),
                "报错要把两个候选都列出来，实际原文：" + ex.getMessage());

        Object types = pick(columnTypes, "types");
        assertInstanceOf(List.class, types);
        assertEquals(3, ((List<?>) types).size(), "点名 'types' 就该拿到类型清单本身");
    }

    @Test
    @DisplayName("explain_form_rule 的 {logicRules,validateRules}：不点名必 throw，问校验就点 'validateRules'")
    void pickRows_explainFormRuleShape_requiresNaming() {
        Map<String, Object> explained = new LinkedHashMap<>();
        explained.put("name", "教师管理");
        explained.put("logicRules", List.of());
        explained.put("validateRules", List.of("身份证必须是 18 位"));

        assertThrows(ServiceException.class, () -> pick(explained, null));
        Object rules = pick(explained, "validateRules");
        assertEquals(1, ((List<?>) rules).size(),
                "取错成 logicRules 就会「静默判成没有校验规则」—— 那正是空数组最坏的失败方式");
    }

    // ==================== 🔴 「谁回哪些数组」这张表由声明渲染，不再手抄 ====================

    /**
     * 四种分档各来一个，键名取<b>真实工具的实测声明</b>
     *
     * <p>🔴 刻意不用随手编的 {@code a/b/c}：分档判据里有一条是「多个数组里恰有一个叫
     * {@code items/list/models}」，编的键名压根碰不到这条，
     * {@code datamodel_physical_tables} 那个中间态就没人守了。
     */
    private static Map<String, List<String>> shapeFixture() {
        Map<String, List<String>> shapes = new LinkedHashMap<>();
        shapes.put("datamodel_structure", List.of("systemColumns", "businessColumns", "indexes"));
        shapes.put("datamodel_physical_tables", List.of("items", "danglingRegistrations"));
        shapes.put("datamodel_list", List.of("models"));
        shapes.put("explain_process", List.of());
        return shapes;
    }

    private void stubShapes() {
        List<AgentToolRegistry.ToolDefinition> builtins = new ArrayList<>();
        shapeFixture().forEach((name, keys) -> builtins.add(
                builtin(name, name, RiskLevel.READ_ONLY, RiskLevel.READ_ONLY, true, keys)));
        builtins.add(unknownShapeBuiltin("data_interface_invoke"));
        when(toolRegistry.list()).thenReturn(builtins);
        // 自定义工具的形状是<b>不可静态声明</b>的（结果就是那个数据接口回的东西），与上面那个同栏
        when(toolDefService.getEnabledToolDefList()).thenReturn(List.of(
                custom("my_iface_tool", "我的接口", "DATA_INTERFACE", "READ_ONLY")));
    }

    /** 从渲染出来的那张表里切出四栏中的一栏 */
    private static String shapeBucket(String table, String start, String end) {
        return section(table, start, end);
    }

    @Test
    @DisplayName("返回值形状表由声明渲染：四种分档各归各位，「不点名也对」那栏印出落到哪个键")
    void resultShapeTable_isRenderedFromDeclarations() {
        stubShapes();
        String table = section(build(), "### 返回值形状", "### 只读工具");

        String mustName = shapeBucket(table, "【必须点名", "【不点名也对");
        assertTrue(mustName.contains("datamodel_structure → systemColumns / businessColumns / indexes"),
                "三个数组、没一个沾惯例名 ⟹ 必须点名档，实际该栏内容：" + mustName);
        assertFalse(mustName.contains("datamodel_list"),
                "单数组的工具被放进「必须点名」= 白教模型点多一次名，实际该栏内容：" + mustName);

        String unkeyed = shapeBucket(table, "【不点名也对", "【压根不回数组");
        assertTrue(unkeyed.contains("datamodel_list → models"), "实际该栏内容：" + unkeyed);
        // 🔴 这一格是本表存在的理由之一：模型看得见「不点名会落到 items」，
        //    才会在它其实想要 danglingRegistrations 时改用点名。只印一句「不点名也对」等于让它赌。
        assertTrue(unkeyed.contains("datamodel_physical_tables → items + danglingRegistrations（不点名取到 items）"),
                "多数组但惯例名兜得住的中间态，必须把落点印出来，实际该栏内容：" + unkeyed);

        String none = shapeBucket(table, "【压根不回数组", "【形状随接口而定");
        assertTrue(none.contains("explain_process"),
                "声明为空的工具要落进「别对它用 pickRows」那一栏");
        // 🔴 这一格是本表最容易说假话的地方：「形状不可知」与「确实没有数组」在声明上都是空列表，
        //    混进同一栏就等于对一个可能回数组的工具说「别对它用 pickRows」。
        assertFalse(none.contains("data_interface_invoke"),
                "形状不可知的工具被印进「压根不回数组」= 提示词对它说了一句假话，实际该栏内容：" + none);

        String unknown = shapeBucket(table, "【形状随接口而定", "> 表里只说");
        assertTrue(unknown.contains("data_interface_invoke") && unknown.contains("my_iface_tool"),
                "🔴 内置的不可知工具与自定义工具必须同占一栏：那一栏的指引是「先把键名打出来看一眼」，"
                        + "多花一个节点，但不会把模型推进一个运行期才炸的写法。实际该栏内容：" + unknown);
    }

    @Test
    @DisplayName("每个声明的形状：真沙箱里 pickRows 不点名的落点，与 ToolResultShapes 说的逐一对上")
    void pickRowsUnkeyed_agreesWithToolResultShapesOnEveryDeclaredShape() {
        shapeFixture().forEach((tool, keys) -> {
            Map<String, Object> fake = new LinkedHashMap<>();
            // 每个数组塞一项「键名-row」，这样取错栏时断言能直接指出取到了哪个键
            keys.forEach(k -> fake.put(k, List.of(k + "-row")));
            fake.put("total", 1);   // 真实结果里总有几个非数组键（total / name），它们不该参与取舍
            String expected = ToolResultShapes.autoResolvedKey(keys);
            if (expected == null) {
                assertThrows(ServiceException.class, () -> pick(fake, null),
                        tool + " 的声明 " + keys + " 被 Java 判成「必须点名」，"
                                + "而提示词里那段 JS 却不点名就取到了值 —— 两边漂移了，"
                                + "后果是校验器 V26 放行的脚本到运行期才抛");
            } else {
                assertEquals(List.of(expected + "-row"), pick(fake, null),
                        tool + " 的声明 " + keys + "：Java 说不点名落到 " + expected
                                + "，JS 实际落到别的键 —— 两边漂移了");
            }
        });
    }

    @Test
    @DisplayName("提示词 JS 里那份 MAIN 是 ToolResultShapes.MAIN_LIST_KEYS 的逐字副本（两份都要对）")
    void promptMainListKeys_mirrorsTheJavaConstant() {
        List<String> copies = allPickRowsCopies();
        assertEquals(2, copies.size(), "份数变了，本条防漂移断言随之失去意义（同 prompt_pickRowsCopies_neverDrift）");
        for (String js : copies) {
            Matcher m = Pattern.compile("var MAIN = \\[([^]]*)];").matcher(js);
            assertTrue(m.find(), "pickRows 里找不到 var MAIN = [...] —— 「惯例名」那一步被删了？"
                    + "删了它，datamodel_physical_tables 这类工具就从「不点名也对」掉进「必 throw」，实际正文：" + js);
            List<String> parsed = new ArrayList<>();
            for (String part : m.group(1).split(",")) {
                String key = part.trim().replace("'", "");
                if (!key.isEmpty()) {
                    parsed.add(key);
                }
            }
            // 🔴 比的是<b>有序列表</b>不是集合：Java 侧 autoResolvedKey 用 contains 判，顺序确实无关，
            //    但「顺序也逐字一致」能让两边看起来就是同一行代码，改一边忘了另一边时 diff 一眼可见
            assertEquals(ToolResultShapes.MAIN_LIST_KEYS, parsed,
                    "提示词教的惯例名与 Java 判据已经漂移 —— 同一段脚本会在规划期被判合法、运行期才抛，"
                            + "或反过来被 V26 误拒。JS 那份是本类唯一一处允许的副本，靠本条钉住");
        }
    }

    /** 用<b>真实工具类</b>的声明建目录：名字取 {@code @AgentTool.name}，形状取 {@code resultArrayKeys()} */
    private void stubRealShapes(AgentToolFunction... functions) {
        List<AgentToolRegistry.ToolDefinition> builtins = new ArrayList<>();
        for (AgentToolFunction fn : functions) {
            String name = fn.getClass().getAnnotation(AgentTool.class).name();
            builtins.add(builtin(name, name, RiskLevel.READ_ONLY, RiskLevel.READ_ONLY, true,
                    fn.resultArrayKeys()));
        }
        when(toolRegistry.list()).thenReturn(builtins);
    }

    /**
     * 从「该点哪个键」那段散文里抠出被点名的工具
     *
     * <p>🔴 抠不到就返回空集合，而下面那条断言会立刻变红 —— 口径写窄导致静默少抓，
     * 与「散文真没点名任何工具」在断言上必须<b>不一样</b>（同 {@code waitTypeValuesInPrompt} 的取舍）。
     */
    private static Set<String> proseNamedTools(String text) {
        String prose = section(text, "该点哪个键</b>是业务判断，记住这几条：", "表里没进「必须点名」那一栏的");
        Set<String> names = new LinkedHashSet<>();
        Matcher m = Pattern.compile("^\\s+([a-z][a-z0-9_]*)：", Pattern.MULTILINE).matcher(prose);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    @Test
    @DisplayName("散文里点名的每个工具，按它<b>真实的声明</b>确实落在「必须点名」那一栏")
    void proseNamedTools_reallyMustBeNamed() {
        stubRealShapes(new DataModelStructureTool(), new ExplainFormRuleTool(),
                new DataModelColumnTypeTool(), new DataModelPreviewDdlTool());
        String text = build();
        String mustName = shapeBucket(section(text, "### 返回值形状", "### 只读工具"),
                "【必须点名", "【不点名也对");

        Set<String> named = proseNamedTools(text);
        assertFalse(named.isEmpty(), "散文里一个工具都没抠到 —— 抠取口径已经对不上正文，本条随之恒真");
        for (String tool : named) {
            assertTrue(mustName.contains(tool + " →"),
                    "散文教「" + tool + " 该点哪个键」，但按它自己代码里的声明，它并不在「必须点名」那一栏。"
                            + "两边打架时模型无从取舍：要么白点一次名，要么照散文以为不点名也行。"
                            + "实际该栏内容：" + mustName);
        }
    }

    @Test
    @DisplayName("形状随返回分支变的工具：声明必须是<b>全部分支的并集</b>（实测值钉在这里）")
    void branchDependentDeclarations_areTheUnionOfAllBranches() {
        // 🔴 这两个是本类最容易被人「顺手改窄」的声明，而改窄的后果是静默的：
        //    少声明一个键 ⟹ 那个工具从「必须点名」掉进「不点名也对」甚至「不回数组」，
        //    模型据此写出的脚本规划期一路绿灯，运行期才抛。
        //    期望值来自 2026-10-09 逐个真调工具量出来的形状（/agent/diagnostic/tool-execute）。
        assertEquals(List.of("addedColumns", "modifiedColumns", "droppedColumns", "ddl", "warnings"),
                new DataModelPreviewDdlTool().resultArrayKeys(),
                "CREATE 分支一个数组都不回、ALTER 分支回这五个 —— 只按 CREATE 声明，"
                        + "它就会掉进「压根不回数组」那一栏。散文手抄时正是这么错过一次，别再错第二次");
        assertEquals(List.of("widgets", "已停用的类型", "不存在的类型"),
                new WidgetContractGetTool().resultArrayKeys(),
                "正常路径只回 widgets，控件名写错时那条错误路径还会多回两个中文键 —— "
                        + "并集少了它们，模型在<b>错误路径</b>上写的不点名取值就会从「报错说清哪儿不对」退化成「猜不出该取哪个数组」");
        assertEquals(List.of("items", "danglingRegistrations"),
                new DataModelPhysicalTableTool().resultArrayKeys(),
                "这个工具的声明决定它落进「不点名也对」而不是「必须点名」，靠的正是 items 沾惯例名 —— "
                        + "改键名或改顺序前先核 ToolResultShapes.MAIN_LIST_KEYS");
    }

    @Test
    @DisplayName("上游回三个数组时不点名 → throw 并列出候选，绝不静默拿第一个（systemColumns）")
    void pickRows_multipleArrays_throwsNamingCandidates() {
        ServiceException ex = assertThrows(ServiceException.class, () -> pick(structureResult(), null));
        String msg = String.valueOf(ex.getMessage());
        assertTrue(msg.contains("systemColumns") && msg.contains("businessColumns"),
                "报错必须把候选键逐个列出来，模型才知道该点名哪个；实际原文：" + msg);

        // 点名之后取到的必须是业务列，而不是排在最前面的系统列
        assertEquals(1, ((List<?>) pick(structureResult(), "businessColumns")).size(),
                "点名 businessColumns 却取到别的数组（systemColumns 有 2 项、indexes 有 0 项）");
    }

    @Test
    @DisplayName("猜错键名（def #620 写的 state.structure_out.columns）→ throw，且消息点名实有的键")
    void pickRows_wrongKeyName_throwsNamingRealKeys() {
        ServiceException ex = assertThrows(ServiceException.class, () -> pick(structureResult(), "columns"));
        String msg = String.valueOf(ex.getMessage());
        assertTrue(msg.contains("columns"), "报错要点名模型填的那个键，实际原文：" + msg);
        assertTrue(msg.contains("businessColumns"), "报错要给出实有的键，实际原文：" + msg);
    }

    @Test
    @DisplayName("上游压根没写回结果 → throw，而不是安静地给一个空列表")
    void pickRows_missingUpstream_throws() {
        assertThrows(ServiceException.class, () -> pick(null, null));
    }

    @Test
    @DisplayName("提示词不再教 (o.items || o.list) 那种赌键名的兜底，且给出可抄的取值函数")
    void prompt_teachesPickRowsNotKeyGuessing() {
        String text = build();
        assertFalse(text.contains("var rows = (state.X && (state.X.items || state.X.list)) || [];"),
                "旧口径还在：它盖不全 models 这种键，而且赌错时返回 [] 不报错 —— 正是 def #620 的根因");
        assertTrue(text.contains("function pickRows(o, key) {"),
                "提示词必须给出可抄的取值函数，而不是让模型自己赌键名");
        assertTrue(text.contains("models") && text.contains("segments"),
                "必须点出「同一个列表在不同工具下键名不同」这件事，否则模型没有理由不赌；实际正文缺了举例");
    }

    /**
     * 🔴 这条钉的是<b>本测试类自己的盲区</b>：上面 8 条全都只跑 {@link #pickRowsFromPrompt()}
     * 抽出的<b>第一份</b>（正文那份多行的），而样例3 的 {@code judge} 节点里还躺着<b>第二份</b>
     * （压成一行的内联副本）。模型抄哪份取决于它读到哪段 —— 两份一旦漂移，
     * 同一条取值规则在「正文教的」与「样例演示的」之间就有了两个版本，
     * 而本节那 8 条沙箱断言<b>一条都不会红</b>：它们量的始终是没坏的那份。
     *
     * <p>这不是假想：{@code MAIN} 那一步（执行 #190 的修复）就是手工同步了两处才没漂。
     */
    @Test
    @DisplayName("prompt 里那两份 pickRows 必须同源 —— 漂移了模型就会抄到旧的那份，而沙箱断言全绿")
    void prompt_pickRowsCopies_neverDrift() {
        List<String> copies = allPickRowsCopies();
        assertEquals(2, copies.size(),
                "pickRows 应当正好两份：正文那份多行的、样例3 judge 节点里那份单行的。"
                        + "份数变了说明有一处被删掉或多抄了一份，本条防漂移断言随之失去意义");
        assertEquals(collapse(copies.get(0)), collapse(copies.get(1)),
                "两份 pickRows 已经漂移 —— 改一处必须同步另一处，否则模型抄到哪份全凭运气");
    }

    /** 两份的差别<b>只</b>该是排版（多行 vs 压成一行），故按空白归一后比较 */
    private static String collapse(String js) {
        return js.replaceAll("\\s+", " ");
    }

    // ==================== 工具方法 ====================

    private String build() {
        return prompt.buildSystemPrompt(3);
    }

    /**
     * few-shot 那一段里的全部 DSL 样例
     *
     * <p>🔴 必须先切出「## 样例」那一段再扫，不能对整份 prompt 扫：HEADER 里那份
     * <b>顶层结构说明</b>同样以 {@code {"dslVersion"} 开头，但它是带 {@code ...} 与 {@code [...]}
     * 占位的<b>形状示意</b>、不是合法 JSON。整份扫会把它当第四份样例，然后报「解析失败」——
     * 把一个正确的示意误判成缺陷。
     */
    private List<String> samples() {
        return extractSamples(section(build(), "## 样例", "现在，请针对用户的需求"));
    }

    /** 取 [start, end) 之间的文本；用于把「只读栏」与「写栏」分开断言 */
    private static String section(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end);
        assertTrue(from >= 0, "找不到段落起点：" + start);
        assertTrue(to > from, "找不到段落终点：" + end);
        return text.substring(from, to);
    }

    /** 从「节点类型目录」那段里抓出所有 `- **type**：` 的 type */
    private static Set<String> catalogTypes(String text) {
        String catalog = section(text, "## 节点类型目录", "## 变量声明");
        Set<String> types = new LinkedHashSet<>();
        Matcher m = Pattern.compile("^- \\*\\*([a-z][a-z0-9_.]*)\\*\\*", Pattern.MULTILINE).matcher(catalog);
        while (m.find()) {
            types.add(m.group(1));
        }
        return types;
    }

    /**
     * 抓出整份 prompt 里所有 {@code "waitType":"xxx"} 的 {@code xxx}
     *
     * <p>🔴 抽不到就返回空集合，而空集合会让 {@link #promptWaitTypeValues_coverExactlyTheSupportedEnumValues}
     * 的 missing 半边立刻变红 —— 这是刻意的：口径写窄（比如只扫节点目录那一段）时静少抓，
     * 与「prompt 真没教」在断言上必须<b>不一样</b>，否则强制点看着一样绿。
     */
    private static Set<String> waitTypeValuesInPrompt(String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"waitType\"\\s*:\\s*\"([^\"]*)\"").matcher(text);
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }

    /**
     * 抽出 few-shot 里的 DSL 样例
     *
     * <p>从每个 {@code {"dslVersion"} 起做<b>字符串感知</b>的括号配平扫描。
     * 必须感知字符串：样例的 script 节点里有 {@code function(r){ ... }}，
     * 裸数括号会在第一个 {@code &#125;} 就截断，拿到半份 JSON 再报「解析失败」——
     * 那会把「扫描器写错了」误报成「样例写错了」。
     */
    private static List<String> extractSamples(String text) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int start = text.indexOf("{\"dslVersion\"", from);
            if (start < 0) {
                return out;
            }
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        out.add(text.substring(start, i + 1));
                        from = i + 1;
                        break;
                    }
                }
            }
            if (from <= start) {
                throw new IllegalStateException("括号没配平，扫描器停在了 " + start);
            }
        }
    }

    /** 桩一个内置工具：声明与生效风险级<b>分开</b>给，这正是本类要区分的两个字段 */
    private static AgentToolRegistry.ToolDefinition builtin(String name, String displayName,
                                                            RiskLevel declared, RiskLevel effective,
                                                            boolean enabled) {
        return builtin(name, displayName, declared, effective, enabled, List.of());
    }

    /**
     * @param resultArrayKeys 该工具<b>声明</b>的返回数组键。
     *                        🔴 不给就有默认值 {@code List.of()}，而它在渲染时落到「压根不回数组」那一栏 ——
     *                        这是<b>已知的形状</b>，不是「没配」。要测那一栏之外的分档必须显式传。
     */
    private static AgentToolRegistry.ToolDefinition builtin(String name, String displayName,
                                                            RiskLevel declared, RiskLevel effective,
                                                            boolean enabled, List<String> resultArrayKeys) {
        AgentToolRegistry.ToolDefinition def = mock(AgentToolRegistry.ToolDefinition.class);
        when(def.getName()).thenReturn(name);
        when(def.getDisplayName()).thenReturn(displayName);
        when(def.getDescription()).thenReturn(displayName + "的说明");
        when(def.getParamsSchema()).thenReturn("");
        when(def.isEnabled()).thenReturn(enabled);
        when(def.getDeclaredRisk()).thenReturn(declared);
        when(def.getDeclaredResultArrayKeys()).thenReturn(resultArrayKeys);
        when(def.isDeclaredResultShapeKnown()).thenReturn(true);
        when(def.getRisk()).thenReturn(effective);
        when(def.isRequireConfirm()).thenReturn(effective.isRequireConfirm());
        return def;
    }

    /**
     * 形状<b>不可静态声明</b>的内置工具（现实里就是那两个数据接口执行工具）
     *
     * <p>🔴 必须与「声明为空」分开造：两者在渲染时落进不同的栏，
     * 而后者那句「压根不回数组，别对它用 pickRows」是<b>确定的指引</b> ——
     * 对形状不可知的工具说这句就是假话，模型照它写出的代码要到运行期才炸。
     */
    private static AgentToolRegistry.ToolDefinition unknownShapeBuiltin(String name) {
        AgentToolRegistry.ToolDefinition def =
                builtin(name, name, RiskLevel.READ_ONLY, RiskLevel.READ_ONLY, true, List.of());
        when(def.isDeclaredResultShapeKnown()).thenReturn(false);
        return def;
    }

    private static AgentToolDefDO custom(String key, String displayName, String source, String risk) {
        AgentToolDefDO row = new AgentToolDefDO();
        row.setToolKey(key);
        row.setDisplayName(displayName);
        row.setDescription(displayName + "的说明");
        row.setSource(source);
        row.setRiskLevel(risk);
        row.setParamsSchema("");
        return row;
    }

    /** 找第一个 type 匹配（且 toolId 匹配，若给了）的节点 id；没有返回 null */
    private static String nodeOfType(WorkflowDefDTO def, String type, String toolId) {
        for (NodeDTO node : def.getNodes()) {
            if (type.equals(node.getType())
                    && (toolId == null || toolId.equals(node.configStr("toolId")))) {
                return node.getId();
            }
        }
        return null;
    }

    private static int indexOfType(WorkflowDefDTO def, String type) {
        for (int i = 0; i < def.getNodes().size(); i++) {
            if (type.equals(def.getNodes().get(i).getType())) {
                return i;
            }
        }
        return -1;
    }

    /** 反向可达集：从 target 沿边逆向走。条件边的 cases 值与 default 都算落点，否则会漏掉 gate→approve 那一跳 */
    private static Set<String> ancestorsOf(WorkflowDefDTO def, String target) {
        Map<String, Set<String>> predecessors = new HashMap<>();
        for (EdgeDTO edge : def.getEdges()) {
            for (String to : targetsOf(edge)) {
                predecessors.computeIfAbsent(to, k -> new HashSet<>()).add(edge.getFrom());
            }
        }
        Set<String> reached = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(target);
        while (!queue.isEmpty()) {
            for (String from : predecessors.getOrDefault(queue.poll(), Set.of())) {
                if (reached.add(from)) {
                    queue.add(from);
                }
            }
        }
        return reached;
    }

    /** 一条边的全部落点：静态边一个，条件边是 cases 的值 + default */
    private static Set<String> targetsOf(EdgeDTO edge) {
        Set<String> out = new HashSet<>();
        if (edge.getTo() != null) {
            out.add(edge.getTo());
        }
        if (edge.getCases() != null) {
            edge.getCases().values().forEach(v -> {
                if (v != null) {
                    out.add(v);
                }
            });
        }
        if (edge.getDefaultTarget() != null) {
            out.add(edge.getDefaultTarget());
        }
        return out;
    }

}
