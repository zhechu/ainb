package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@code widget_contract_get} 的单元测试
 *
 * <h3>这里守的是一条实测炸过的线：白名单不许残缺</h3>
 * 全量含 props 的输出有两万多字符，而回灌预算只有 4000 ——
 * 一次截断砍掉 81%，清单里只剩前 8 个控件，{@code datePicker} 排在第 13 位。
 * 模型拿到的是「看起来完整、实则残缺」的白名单，它无从知道自己没看全，
 * 于是用户要日期字段时它照着 Element Plus 的记忆编了个 {@code dateTimePicker}。
 *
 * @author 走进新时代
 */
public class WidgetContractGetToolTest extends BaseMockitoUnitTest {

    /** {@code datePicker} 在真实契约里的位置（第 13 个），正好落在原先 4000 字符的截断点之后 */
    private static final int DATE_PICKER_POSITION = 13;

    @InjectMocks
    private WidgetContractGetTool tool;

    @Mock
    private FormDesignApi formDesignApi;

    @BeforeEach
    public void setUp() {
        List<FormDesignApi.WidgetSimpleRespDTO> widgets = new ArrayList<>();
        // 前 12 个填满原先的截断预算，好让「datePicker 排在第 13 位」这件事真的被测到
        widgets.add(widget("input", "单行输入", "basic"));
        widgets.add(widget("textarea", "多行输入", "basic"));
        widgets.add(widget("password", "密码", "basic"));
        widgets.add(widget("inputNumber", "数字", "basic"));
        widgets.add(widget("switch", "开关", "basic"));
        widgets.add(widget("radio", "单选", "basic"));
        widgets.add(widget("checkbox", "多选", "basic"));
        widgets.add(widget("select", "下拉", "select"));
        widgets.add(widget("cascader", "级联", "select"));
        widgets.add(widget("tree", "树", "select"));
        widgets.add(widget("elTreeSelect", "树选择", "select"));
        widgets.add(widget("elTransfer", "穿梭框", "select"));
        widgets.add(widget("datePicker", "日期", "basic"));
        widgets.add(widget("timePicker", "时间", "basic"));
        widgets.add(widget("colorPicker", "颜色", "advanced"));
        // 一个带 props 的：详情模式下必须能拿到，清单模式下必须不带
        FormDesignApi.WidgetSimpleRespDTO dict = widget("DictSelect", "字典选择", "advanced");
        FormDesignApi.WidgetPropDTO prop = new FormDesignApi.WidgetPropDTO();
        prop.setName("dictType");
        prop.setType("string");
        prop.setRequired(true);
        prop.setDesc("字典编码，必须是真实存在的字典");
        dict.setProps(List.of(prop));
        widgets.add(dict);
        // 一个已停用的：选型清单里必须看不见它，按名查详情时要点名说死不能用
        FormDesignApi.WidgetSimpleRespDTO upload = widget("upload", "上传（内核内置）", "advanced");
        upload.setSupported(false);
        widgets.add(upload);
        lenient().when(formDesignApi.getWidgetContracts()).thenReturn(widgets);
    }

    private FormDesignApi.WidgetSimpleRespDTO widget(String type, String label, String group) {
        FormDesignApi.WidgetSimpleRespDTO dto = new FormDesignApi.WidgetSimpleRespDTO();
        dto.setType(type);
        dto.setLabel(label);
        dto.setGroup(group);
        dto.setSupported(true);
        dto.setColumnTypes(List.of("STRING"));
        return dto;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> widgetsOf(Object result) {
        return (List<Map<String, Object>>) ((Map<String, Object>) result).get("widgets");
    }

    private List<String> typesOf(Object result) {
        return widgetsOf(result).stream().map(item -> String.valueOf(item.get("type"))).toList();
    }

    // ==================== 全量清单 ====================

    /**
     * 🔴 这条就是原 BUG 的回归测试：排在截断点之后的控件必须看得见
     */
    @Test
    @DisplayName("🔴 不带参数时返回全部控件，排在后面的 datePicker 也在")
    public void testBriefListIsComplete() {
        Object result = tool.execute(Map.of());

        List<String> types = typesOf(result);
        assertEquals(16, types.size(), "一个都不能少：" + types);
        assertTrue(types.contains("datePicker"),
                "datePicker 排在第 " + DATE_PICKER_POSITION + " 位，正是原先被截断掉的那个：" + types);
        assertTrue(types.contains("timePicker") && types.contains("colorPicker"),
                "截断点之后的都要在：" + types);
    }

    /**
     * 🔴 清单模式下不能带 props：带上就又回到两万多字符、又被截断的老路
     */
    @Test
    @DisplayName("🔴 清单模式不含 props，体积才压得下来")
    public void testBriefListOmitsProps() {
        Object result = tool.execute(Map.of());

        assertTrue(widgetsOf(result).stream().noneMatch(item -> item.containsKey("props")),
                "清单里不该出现 props");
        // 但要明说 props 是「另外查」，否则模型会以为这些控件不需要配置
        String text = JSONUtil.toJsonStr(result);
        assertTrue(text.contains("types"), "要告诉模型怎么拿 props：" + text);
    }

    @Test
    @DisplayName("清单里保留选型必需的字段")
    public void testBriefListKeepsSelectionFields() {
        Object result = tool.execute(Map.of());

        Map<String, Object> first = widgetsOf(result).get(0);
        assertEquals("input", first.get("type"));
        assertEquals("单行输入", first.get("label"));
        assertEquals("basic", first.get("group"));
        assertNotNull(first.get("columnTypes"), "列类型相容性靠它判断，不能省");
    }

    /**
     * 🔴 upload 事故的回归测试：停用控件进了选型清单，就会被模型真的选上。
     *    实测它给图片字段选了 label 为「上传（内核内置）」的 upload，无视 supported=false，
     *    一路到运行期 form_save_draft 才报「控件类型 upload 已停用」
     */
    @Test
    @DisplayName("🔴 已停用的 upload 不出现在选型清单里")
    public void testUnsupportedWidgetHiddenFromBriefList() {
        Object result = tool.execute(Map.of());

        List<String> types = typesOf(result);
        assertFalse(types.contains("upload"),
                "停用控件不能出现在给生成用的白名单里：" + types);
        assertEquals(16, types.size(), "其余可用控件一个不少：" + types);
        assertTrue(widgetsOf(result).stream()
                        .allMatch(item -> Boolean.TRUE.equals(item.get("supported"))),
                "清单里每一项都必须是可用的");
        // 使用要求里要点名 upload，模型从校验报错回来核对时才对得上号
        String text = JSONUtil.toJsonStr(result);
        assertTrue(text.contains("upload"), "使用要求里要点名 upload 不能用：" + text);
    }

    /**
     * 🔴 但按名查详情时必须点名说死「已停用」，不能静默返回 supported=false 了事 ——
     *    模型走到这一步只可能是校验报错后回来核对，此刻最需要一句明确的结论
     */
    @Test
    @DisplayName("🔴 按 types 查已停用的 upload 时被点名，不静默")
    public void testUnsupportedWidgetNamedInDetail() {
        Object result = tool.execute(Map.of("types", List.of("upload", "datePicker")));

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        @SuppressWarnings("unchecked")
        List<String> unusable = (List<String>) map.get("已停用的类型");
        assertEquals(List.of("upload"), unusable);
        assertNotNull(map.get("停用提示"), "要给明确的「不能用、换清单里的」结论");
        // 详情条目照常返回，且 supported=false 可见
        List<Map<String, Object>> widgets = widgetsOf(result);
        assertEquals(2, widgets.size());
        Map<String, Object> upload = widgets.stream()
                .filter(item -> "upload".equals(item.get("type"))).findFirst().orElseThrow();
        assertEquals(false, upload.get("supported"));
    }

    // ==================== 指定控件取详情 ====================

    @Test
    @DisplayName("带 types 时只返回指定控件，且带上 props")
    public void testDetailList() {
        Object result = tool.execute(Map.of("types", List.of("DictSelect")));

        List<Map<String, Object>> widgets = widgetsOf(result);
        assertEquals(1, widgets.size());
        assertEquals("DictSelect", widgets.get(0).get("type"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> props = (List<Map<String, Object>>) widgets.get(0).get("props");
        assertNotNull(props, "详情模式必须带 props，否则字典选择器只能建成空下拉");
        assertEquals("dictType", props.get(0).get("name"));
        assertEquals(true, props.get(0).get("required"));
    }

    /**
     * 🔴 静默忽略编错的名字，模型会以为「查到了」，继续拿错名字去建表单
     */
    @Test
    @DisplayName("🔴 types 里编错的类型被点名，不静默忽略")
    public void testUnknownTypeNamed() {
        Object result = tool.execute(Map.of("types", List.of("dateTimePicker", "datePicker")));

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        @SuppressWarnings("unchecked")
        List<String> unknown = (List<String>) map.get("不存在的类型");
        assertEquals(List.of("dateTimePicker"), unknown,
                "就是它 —— 模型凭记忆编出来的那个名字");
        assertEquals(List.of("datePicker"), typesOf(result), "对的那个照常返回");
    }

    @Test
    @DisplayName("types 接受逗号分隔的字符串与 JSON 数组字符串")
    public void testTypesParsing() {
        assertEquals(List.of("datePicker", "timePicker"),
                typesOf(tool.execute(Map.of("types", "datePicker,timePicker"))));
        assertEquals(List.of("datePicker", "timePicker"),
                typesOf(tool.execute(Map.of("types", "datePicker, timePicker "))));
        assertEquals(List.of("datePicker", "timePicker"),
                typesOf(tool.execute(Map.of("types", "[\"datePicker\",\"timePicker\"]"))));
    }

    @Test
    @DisplayName("types 为空串或空数组时，退回全量清单")
    public void testEmptyTypesFallsBackToBriefList() {
        assertEquals(16, typesOf(tool.execute(Map.of("types", ""))).size());
        assertEquals(16, typesOf(tool.execute(Map.of("types", List.of()))).size());
        assertEquals(16, typesOf(tool.execute(null)).size());
    }

    // ==================== 🔴「AI 建不出来」那个标记 ====================

    /**
     * 清单那一档只给<b>结论</b>（{@code aiAuthorable=false}），不给原因
     *
     * <p>复现的缺陷：模型按这份清单选了 {@code DictSelect}（{@code supported=true}、清单里确实有它），
     * 而它必需的 {@code dictType} 在 {@code form_save_draft} 的字段描述里没有能放它的格子 ——
     * 草稿照存、发布照过、AI 回一句「表单已建好」，用户点进去是一个点不开的下拉。
     * 打标之前，这份清单读起来就是「这 16 个都能用」。
     *
     * <p>🔴 原因刻意<b>不在</b>清单里给：18 个控件各带一句原因就是 1.4 KB，
     *    而这份清单的体积是有预算的（类注释记着那次两万字符被砍掉 81%、datePicker 消失的事故）。
     *    模型真要用某个控件时会带 {@code types} 再查一次，那时才给原因（下一条）。
     */
    @Test
    @DisplayName("🔴 清单模式给 aiAuthorable=false 的结论，但不给原因（体积有预算）")
    public void testBriefListMarksUnauthorableWidget() {
        Object result = tool.execute(Map.of());

        Map<String, Object> dict = itemOfType(result, "DictSelect");
        assertEquals(false, dict.get("aiAuthorable"),
                "契约要求 dictType、字段描述表达不了 ⟹ 必须打标，否则模型照清单选它就建出一个空下拉");
        assertFalse(dict.containsKey("aiAuthorableReason"),
                "清单那一档不该带原因：18 个控件各带一句就是 1.4 KB，这份清单的体积是有预算的");

        Map<String, Object> input = itemOfType(result, "input");
        assertFalse(input.containsKey("aiAuthorable"),
                "建得出来的控件不该挂这个键 —— 与 deprecated 同一条纪律：给每项都挂一个 "
                        + "aiAuthorable:true 会让清单凭空多出几十行噪声");

        // 「使用要求」那段必须点名同一个键，并给出这条路径上照做得到的出路。
        // 键名由常量渲染，故这里断言的是「说明与返回对得上号」
        String text = JSONUtil.toJsonStr(result);
        assertTrue(text.contains("aiAuthorable=false"),
                "返回里出现了这个键、说明里却没解释它 —— 模型只会当成噪声跳过：" + text);
        assertTrue(text.contains(FormFieldFactory.optionWidgetCatalog()),
                "说明要给出路：选择类需求改用哪几个控件配 options/dictType：" + text);
        assertTrue(text.contains("没有任何一处会主动说"),
                "说明要说清后果是「不报错的失效」—— 不说后果，这个标记读起来就像风格建议：" + text);
    }

    /**
     * 详情那一档给<b>原因</b>：走到这里的模型通常已经定下要用哪个控件了，
     * 只说「建不出来」不说「缺哪一格」，它无从判断该换成什么、也不知道该跟用户交代什么
     */
    @Test
    @DisplayName("🔴 详情模式连原因一起给，且原因点名缺的那一格")
    public void testDetailListGivesTheReason() {
        Object result = tool.execute(Map.of("types", List.of("DictSelect", "input")));

        Map<String, Object> dict = itemOfType(result, "DictSelect");
        assertEquals(false, dict.get("aiAuthorable"));
        String reason = String.valueOf(dict.get("aiAuthorableReason"));
        assertTrue(reason.contains("dictType"), "原因要点名缺的那一格：" + reason);
        assertTrue(reason.contains("fields[]"),
                "要说清缺口在字段描述这一侧、不在控件那一侧，否则模型只会去改控件名：" + reason);

        Map<String, Object> input = itemOfType(result, "input");
        assertFalse(input.containsKey("aiAuthorable"), input.toString());
        assertFalse(input.containsKey("aiAuthorableReason"), input.toString());
    }

    /**
     * 🔴 标记是<b>从契约现算</b>的，本工具不持有那 18 个名字
     *
     * <p>这条是上面两条的变异探针：只钉「DictSelect 被标了」的话，
     * 把判据换成一份手抄的名单（{@code Set.of("DictSelect","cascader",…)}）一样能绿 ——
     * 而手抄名单必然漂移，漂了的表现恰好就是本标记要拦的那个：
     * 契约给某个控件新加一个必填配置项、名单没跟上 ⟹ 又能建出一个空控件，且照样不报错。
     * 故这里造一个<b>任何名单里都不可能有</b>的新控件名，它必须照样被标出来。
     */
    @Test
    @DisplayName("🔴 判据从契约现算：换个没见过的控件名，只要契约要求配置就照样打标")
    public void testMarkIsComputedFromContractNotFromANameList() {
        FormDesignApi.WidgetSimpleRespDTO brandNew = widget("brandNewWidget", "全新的控件", "advanced");
        brandNew.setProps(List.of(prop("someBrandNewKey", true)));
        FormDesignApi.WidgetSimpleRespDTO cascaderLike = widget("cascaderLike", "级联（选项来源那档）", "select");
        cascaderLike.setProps(List.of(prop(FormFieldFactory.DICT_SOURCE_KEY, false),
                prop(FormFieldFactory.DATA_SOURCE_KEY, false), prop(FormFieldFactory.OPTIONS_KEY, false)));
        FormDesignApi.WidgetSimpleRespDTO allOptional = widget("allOptional", "配置项都可选", "basic");
        allOptional.setProps(List.of(prop("placeholder", false), prop("maskRule", false)));
        when(formDesignApi.getWidgetContracts()).thenReturn(List.of(brandNew, cascaderLike, allOptional));

        Object result = tool.execute(Map.of());

        assertEquals(false, itemOfType(result, "brandNewWidget").get("aiAuthorable"),
                "契约要求一个必填配置项、而字段描述写不进去 ⟹ 不管这个控件名叫什么都得打标");
        assertEquals(false, itemOfType(result, "cascaderLike").get("aiAuthorable"),
                "非选项族却拿不到选项来源 ⟹ 运行期就是个点不开的下拉，同样要打标");
        assertFalse(itemOfType(result, "allOptional").containsKey("aiAuthorable"),
                "配置项全都可选 ⟹ 建得出来，打了标就是把「能用」说成「不能用」");
    }

    private static FormDesignApi.WidgetPropDTO prop(String name, boolean required) {
        FormDesignApi.WidgetPropDTO dto = new FormDesignApi.WidgetPropDTO();
        dto.setName(name);
        dto.setRequired(required);
        return dto;
    }

    private Map<String, Object> itemOfType(Object result, String type) {
        return widgetsOf(result).stream()
                .filter(item -> type.equals(item.get("type"))).findFirst()
                .orElseThrow(() -> new AssertionError("返回里没有 " + type + "：" + widgetsOf(result)));
    }

    // ==================== 截断豁免 ====================

    /**
     * 🔴 少了这个声明，清单又会被 truncateForPrompt 砍回 4000 字符，
     *    上面那些「必须看得见」的断言会全部失去意义
     */
    @Test
    @DisplayName("🔴 本工具必须声明结果不可截断")
    public void testTruncateResultDisabled() {
        AgentTool annotation = WidgetContractGetTool.class.getAnnotation(AgentTool.class);

        assertNotNull(annotation);
        assertFalse(annotation.truncateResult(),
                "白名单被截断就是幻觉的源头，这个声明不能改回 true");
    }

}
