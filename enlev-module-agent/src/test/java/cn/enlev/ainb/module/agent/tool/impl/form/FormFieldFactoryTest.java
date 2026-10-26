package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.FormSchemaValidator;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * 「字段描述 → 表单各处产物」这一份转换的单元测试
 *
 * <h3>🔴 选项来源那几条守的是一个<b>静默</b>缺陷</h3>
 * 修复前 {@code toRule} 压根不写 {@code options} / {@code props.dictSource}，
 * 于是「状态：下拉，选项是待处理/已处理」建出来的表单<b>存得进、发布也过得去</b>，
 * 而那一格是个点开为空的下拉。这类断言不能只验「没抛异常」，必须验<b>落点</b>：
 * 静态选项在 rule 顶层、字典在 {@code props.dictSource}，写错一处同样是静默失效。
 *
 * @author 走进新时代
 */
public class FormFieldFactoryTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormFieldFactory fieldFactory;

    @Mock
    private FormSchemaValidator schemaValidator;

    @Mock
    private DataModelApi dataModelApi;

    /** 归一在本类里只是转一手（规则本体在 {@code FormSchemaValidator}），测试里原样返回即可 */
    private void stubCanonicalPassthrough() {
        lenient().when(schemaValidator.canonicalType(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private JSONObject descriptor(String widget, Object... kv) {
        JSONObject obj = new JSONObject();
        obj.set("field", "status");
        obj.set("widget", widget);
        obj.set("title", "状态");
        for (int i = 0; i < kv.length; i += 2) {
            obj.set(String.valueOf(kv[i]), kv[i + 1]);
        }
        return obj;
    }

    private JSONArray staticOptions() {
        return JSONUtil.parseArray("[{\"label\":\"待处理\",\"value\":\"pending\"},"
                + "{\"label\":\"已处理\",\"value\":\"done\"}]");
    }

    // ==================== 描述符 → rule 的选项来源 ====================

    @Test
    @DisplayName("🔴 固定选项落在 rule 顶层的 options —— 这是唯一不依赖别处配置就能出选项的写法")
    public void toRule_staticOptions_writtenAtRuleTopLevel() {
        stubCanonicalPassthrough();

        JSONObject rule = fieldFactory.toRule(
                descriptor("select", "options", staticOptions()), 1, Map.of());

        JSONArray options = rule.getJSONArray(FormFieldFactory.OPTIONS_KEY);
        assertEquals(2, options.size(), "选项一个都不能少：" + rule);
        assertEquals("待处理", options.getJSONObject(0).getStr("label"));
        assertEquals("pending", options.getJSONObject(0).getStr("value"));
        JSONObject props = rule.getJSONObject("props");
        assertNull(props.get(FormFieldFactory.DICT_SOURCE_KEY),
                "没写字典就不该凭空多出一个 dictSource：" + props);
    }

    @Test
    @DisplayName("🔴 字典落在 props.dictSource，且顶层 options 被清成空数组")
    public void toRule_dictType_writtenIntoPropsDictSource() {
        stubCanonicalPassthrough();

        JSONObject rule = fieldFactory.toRule(
                descriptor("select", FormFieldFactory.DICT_TYPE_KEY, "common_status"), 1, Map.of());

        JSONObject source = rule.getJSONObject("props").getJSONObject(FormFieldFactory.DICT_SOURCE_KEY);
        assertEquals("common_status", source.getStr(FormFieldFactory.DICT_TYPE_KEY));
        // 🔴 valueType 一律 "str"：库里 104 份真实表单全部是这个值，含 4 个取值清一色是数字的字典。
        //    写错的后果不是报错，而是字典选项匹配不上、下拉又是空的
        assertEquals(FormFieldFactory.DICT_VALUE_TYPE, source.getStr("valueType"));
        // 前端 stripResolvedOptions 就是把派生来源的选项路径清空的，留着旧值会被当成静态选项
        assertEquals(0, rule.getJSONArray(FormFieldFactory.OPTIONS_KEY).size(),
                "字典来源下顶层 options 必须是空数组：" + rule);
    }

    @Test
    @DisplayName("两个来源都给了字典赢 —— 与前端 detectOptionSourceType 的优先级同口径")
    public void toRule_bothSources_dictWins() {
        stubCanonicalPassthrough();

        JSONObject rule = fieldFactory.toRule(descriptor("radio",
                FormFieldFactory.DICT_TYPE_KEY, "common_status",
                "options", staticOptions()), 1, Map.of());

        assertEquals("common_status", rule.getJSONObject("props")
                .getJSONObject(FormFieldFactory.DICT_SOURCE_KEY).getStr(FormFieldFactory.DICT_TYPE_KEY));
        assertEquals(0, rule.getJSONArray(FormFieldFactory.OPTIONS_KEY).size(),
                "字典赢时静态选项必须被清掉，否则前端读到的是哪个取决于优先级、而不是取决于用户意图");
    }

    @Test
    @DisplayName("非选项类控件不写任何来源 —— 写了会被整个忽略，还会挡住同名的合法用途")
    public void toRule_nonOptionWidget_leavesOptionKeysAlone() {
        stubCanonicalPassthrough();

        JSONObject rule = fieldFactory.toRule(
                descriptor("input", "options", staticOptions()), 1, Map.of());

        assertFalse(rule.containsKey(FormFieldFactory.OPTIONS_KEY),
                "input 不读 options，凭空写一个上去就是「配了但不生效」的另一种形状：" + rule);
    }

    // ==================== applyOptionSource：改表单那条路 ====================

    /**
     * 🔴 换成静态选项时必须删掉 {@code props.dictSource} 与 {@code props.dataSource}
     *
     * <p>不删的话前端那个优先级会反过来咬人：用户明明改成了固定选项，
     * 运行期仍按字典取，界面上一片空白 —— 而保存是成功的。
     */
    @Test
    @DisplayName("🔴 改用静态选项时把字典与接口来源一并清掉，其余 props 原样保留")
    public void applyOptionSource_switchToStatic_dropsOtherSources() {
        JSONObject rule = JSONUtil.parseObj("""
                {"type":"select","field":"status","title":"状态",
                 "props":{"placeholder":"请选择","dictSource":{"dictType":"common_status","valueType":"str"},
                          "dataSource":{"interfaceCode":"xx"}}}
                """);

        fieldFactory.applyOptionSource(rule, descriptor("select", "options", staticOptions()));

        JSONObject props = rule.getJSONObject("props");
        assertNull(props.get(FormFieldFactory.DICT_SOURCE_KEY), "字典来源没被清掉：" + props);
        assertNull(props.get(FormFieldFactory.DATA_SOURCE_KEY), "接口来源没被清掉：" + props);
        assertEquals("请选择", props.getStr("placeholder"), "无关的 props 不能被顺手抹掉");
        assertEquals(2, rule.getJSONArray(FormFieldFactory.OPTIONS_KEY).size());
    }

    @Test
    @DisplayName("改用字典时把存量静态选项清空，否则前端读到的是哪个取决于优先级")
    public void applyOptionSource_switchToDict_emptiesStaleOptions() {
        JSONObject rule = JSONUtil.parseObj("""
                {"type":"checkbox","field":"status","title":"状态","options":[{"label":"旧选项","value":"old"}]}
                """);

        fieldFactory.applyOptionSource(rule,
                descriptor("checkbox", FormFieldFactory.DICT_TYPE_KEY, "common_status"));

        assertEquals(0, rule.getJSONArray(FormFieldFactory.OPTIONS_KEY).size(), "" + rule);
        assertEquals("common_status", rule.getJSONObject("props")
                .getJSONObject(FormFieldFactory.DICT_SOURCE_KEY).getStr(FormFieldFactory.DICT_TYPE_KEY));
    }

    /**
     * 🔴 三态里的第三态：<b>没提就不动</b>
     *
     * <p>在改表单那条路上，patch 没提选项来源不等于要把它清空 ——
     * 清空一个用户在设计器里配好的字典正是「悄悄改掉用户的东西」，而且不报错。
     */
    @Test
    @DisplayName("patch 两个键都没提时一个字都不动")
    public void applyOptionSource_neitherKey_touchedNothing() {
        JSONObject rule = JSONUtil.parseObj("""
                {"type":"select","field":"status","title":"状态",
                 "props":{"dictSource":{"dictType":"common_status","valueType":"str"}},"options":[]}
                """);
        String before = rule.toString();

        fieldFactory.applyOptionSource(rule, descriptor("select", "title", "处理状态"));

        assertEquals(before, rule.toString(), "没提选项来源却动了它：" + rule);
    }

    // ==================== 名单本身 ====================

    /**
     * 🔴 教学文本必须<b>有序</b>：{@code OPTION_WIDGETS} 是 {@code Set.of(...)}，
     * 遍历顺序由 JVM 的 salt 决定、每次启动都可能不同。直接 join 的话同一份 DSL 两次体检
     * 报出来的文案不一样，断言它的测试会变成偶发红灯。
     */
    @Test
    @DisplayName("控件名单的渲染既完整又有序")
    public void optionWidgetCatalog_isCompleteAndSorted() {
        List<String> rendered = Arrays.asList(FormFieldFactory.optionWidgetCatalog().split("、"));

        assertEquals(FormFieldFactory.OPTION_WIDGETS.size(), rendered.size(),
                "渲染出来的名单与常量对不上（漏印一个的后果是模型以为那个控件不用给选项）");
        assertTrue(FormFieldFactory.OPTION_WIDGETS.containsAll(rendered), "印出了名单外的名字：" + rendered);
        List<String> sorted = rendered.stream().sorted().toList();
        assertEquals(sorted, rendered, "名单没排序，文案会随 JVM 启动变化：" + rendered);
    }

    /**
     * 🔴 遍历常量本身而不是把三个名字再敲一遍：日后往选项族里加控件，
     * 这条会自动覆盖到它 —— 而「查询区没下拉」与「表单里那一格没选项」正是漂移的两半表现。
     */
    @Test
    @DisplayName("选项族里的每个控件在查询区都渲染成下拉，并带上字典类型")
    public void buildSearchItem_everyOptionWidget_becomesSelect() {
        for (String widget : FormFieldFactory.OPTION_WIDGETS) {
            JSONObject item = fieldFactory.buildSearchItem(
                    "status", "状态", widget, "common_status", null);

            assertEquals("SELECT", item.getStr("widget"), widget + " 在查询区没渲染成下拉");
            assertEquals("EQ", item.getStr("operator"), widget + " 的默认操作符与前端 coerceOperator 不一致");
            assertEquals("common_status", item.getStr(FormFieldFactory.DICT_TYPE_KEY),
                    widget + " 的查询项丢了字典类型，运行期会退化成一个文本框");
        }
    }

    @Test
    @DisplayName("非选项族控件的查询项推断不受这次收口影响")
    public void buildSearchItem_otherWidgets_keepTheirOwnInference() {
        assertEquals("TEXT", fieldFactory.buildSearchItem("memo", "备注", "input", null, null).getStr("widget"));
        assertEquals("DATE_RANGE",
                fieldFactory.buildSearchItem("day", "日期", "datePicker", null, null).getStr("widget"));
        assertEquals("SWITCH",
                fieldFactory.buildSearchItem("flag", "标记", "switch", null, null).getStr("widget"));
    }

    @Test
    @DisplayName("isOptionWidget 只认名单里的那几个，且 null 不炸")
    public void isOptionWidget_matchesTheCatalogOnly() {
        for (String widget : FormFieldFactory.OPTION_WIDGETS) {
            assertTrue(fieldFactory.isOptionWidget(widget), widget + " 应被认成选项类控件");
        }
        assertFalse(fieldFactory.isOptionWidget("input"));
        assertFalse(fieldFactory.isOptionWidget("cascader"),
                "cascader 的落点是 props.options，与本类那三件套不同，收进来就会写错地方");
        assertFalse(fieldFactory.isOptionWidget(null), "Set.of(...) 的 contains(null) 会 NPE");
    }

    // ==================== 这个控件 AI 配得齐吗 ====================

    /**
     * 一个契约控件。{@code props} 传空即「这个控件不需要额外配置」
     *
     * <p>🔴 桩里<b>不</b>抄 infra 那 63 份契约：判据的事实来源是 {@code form-widget-contracts.json}，
     * 抄一份进测试等于把「两边漂移」这件事挪出被测范围（与 {@code FormSchemaValidatorTest}
     * 那份内置正则目录同一条纪律）。这里只桩三种<b>形状</b>：
     * 必填项、选项来源、以及 {@code toRule} 真写得进去的那两格。
     */
    private static FormDesignApi.WidgetSimpleRespDTO contractWidget(String type,
                                                                    FormDesignApi.WidgetPropDTO... props) {
        FormDesignApi.WidgetSimpleRespDTO dto = new FormDesignApi.WidgetSimpleRespDTO();
        dto.setType(type);
        dto.setSupported(true);
        dto.setProps(props.length == 0 ? null : List.of(props));
        return dto;
    }

    /** 一个配置项。只填判据会读的两格：键名，与契约那句「不填则该控件无法正常工作」 */
    private static FormDesignApi.WidgetPropDTO prop(String name, boolean required) {
        FormDesignApi.WidgetPropDTO dto = new FormDesignApi.WidgetPropDTO();
        dto.setName(name);
        dto.setRequired(required);
        return dto;
    }

    /** 一条只带 props 的 rule；不给任何键即 {@code props:{}} */
    private static JSONObject ruleWithProps(Object... kv) {
        JSONObject props = new JSONObject();
        for (int i = 0; i < kv.length; i += 2) {
            props.set(String.valueOf(kv[i]), kv[i + 1]);
        }
        return new JSONObject().set("type", "whatever").set("props", props);
    }

    @Test
    @DisplayName("🔴 契约说 required=true 的配置缺了 ⟹ 点名缺哪一格")
    public void missingWidgetConfig_requiredPropAbsent_reported() {
        FormDesignApi.WidgetSimpleRespDTO dictSelect = contractWidget("DictSelect", prop("dictType", true));

        // rule 传 null = 「字段描述能写出来的最好情况」（toRule 只写 placeholder/validateRules）
        assertEquals(List.of("dictType"), FormFieldFactory.missingWidgetConfig(dictSelect, null));
        assertEquals(List.of("dictType"),
                FormFieldFactory.missingWidgetConfig(dictSelect, ruleWithProps()));
    }

    @Test
    @DisplayName("配齐了就不报 —— 判的是【这一条 rule】，不是【这个控件名】")
    public void missingWidgetConfig_requiredPropWritten_silent() {
        FormDesignApi.WidgetSimpleRespDTO dictSelect = contractWidget("DictSelect", prop("dictType", true));

        assertTrue(FormFieldFactory
                .missingWidgetConfig(dictSelect, ruleWithProps("dictType", "common_status")).isEmpty());
    }

    @Test
    @DisplayName("空串 / 空数组 / 空对象一律算「没配」—— 它们在界面上与不写完全一样")
    public void missingWidgetConfig_blankShapesStillMissing() {
        FormDesignApi.WidgetSimpleRespDTO dictSelect = contractWidget("DictSelect", prop("dictType", true));

        for (Object blank : List.of("", "   ", new JSONArray(), new JSONObject())) {
            assertEquals(List.of("dictType"),
                    FormFieldFactory.missingWidgetConfig(dictSelect, ruleWithProps("dictType", blank)),
                    "这个形状该算没配：" + blank);
        }
    }

    @Test
    @DisplayName("🔴 props 形状不对时按「一格都没配」处理，不抛 —— 「形状错」本身已有别处报")
    public void missingWidgetConfig_malformedProps_treatedAsEmpty() {
        FormDesignApi.WidgetSimpleRespDTO dictSelect = contractWidget("DictSelect", prop("dictType", true));

        assertEquals(List.of("dictType"), FormFieldFactory.missingWidgetConfig(dictSelect,
                new JSONObject().set("props", "不是对象")));
        assertEquals(List.of("dictType"), FormFieldFactory.missingWidgetConfig(dictSelect,
                new JSONObject().set("props", new JSONArray())));
    }

    @Test
    @DisplayName("🔴 toRule 写得进去的那两格永不被判缺 —— 这份名单与 toRule 是同一层耦合的两半")
    public void missingWidgetConfig_authorableKeysNeverReported() {
        FormDesignApi.WidgetSimpleRespDTO widget = contractWidget("input",
                prop("placeholder", true), prop(FormFieldFactory.VALIDATE_RULES_KEY, true));

        assertTrue(FormFieldFactory.missingWidgetConfig(widget, null).isEmpty(),
                "这两格字段描述里都有位置，判成「建不出来」就是假警报");
    }

    /**
     * 🔴 上一条的反向对照，也是这两条<b>必须成对</b>存在的理由：
     * 只钉「那两格不被判缺」的话，把 {@code AUTHORABLE_PROP_KEYS} 扩成「所有键都算写得进去」
     * 一样能绿 —— 而那正是判据整体失效（18 个控件一个都拦不住）的形状。
     */
    @Test
    @DisplayName("🔴 契约多要求一格，就算字段描述写满了也建不出来")
    public void missingWidgetConfig_thirdRequiredProp_survivesAFullDescriptor() {
        stubCanonicalPassthrough();
        FormDesignApi.WidgetSimpleRespDTO widget = contractWidget("input",
                prop("placeholder", true), prop(FormFieldFactory.VALIDATE_RULES_KEY, true),
                prop("uniqueCheck", true));

        JSONObject rule = fieldFactory.toRule(descriptor("input",
                "placeholder", "请输入",
                FormFieldFactory.VALIDATE_RULES_KEY, List.of(Map.of("type", "BUILTIN", "patternKey", "ID_CARD"))),
                1, Map.of());

        assertEquals(List.of("uniqueCheck"), FormFieldFactory.missingWidgetConfig(widget, rule),
                "字段描述能给的都给了、仍然缺一格 ⟹ 这就是「AI 建不出来」的定义");
    }

    @Test
    @DisplayName("🔴 选项来源是「声明的这几格里配了一格就算配了」，与必填项「逐个都得有」不同")
    public void missingWidgetConfig_optionSourceIsAnyOneOf() {
        FormDesignApi.WidgetSimpleRespDTO cascader = contractWidget("cascader",
                prop(FormFieldFactory.DICT_SOURCE_KEY, false),
                prop(FormFieldFactory.DATA_SOURCE_KEY, false),
                prop(FormFieldFactory.OPTIONS_KEY, false));

        assertEquals(List.of("选项来源（props.dictSource / props.dataSource / props.options 之一）"),
                FormFieldFactory.missingWidgetConfig(cascader, ruleWithProps()));
        assertTrue(FormFieldFactory.missingWidgetConfig(cascader,
                        ruleWithProps(FormFieldFactory.DATA_SOURCE_KEY,
                                new JSONObject().set("interfaceCode", "i1"))).isEmpty(),
                "配了 dataSource 就不该再要 dictSource：三者在 detectOptionSourceType 里是互斥的优先级");
    }

    @Test
    @DisplayName("🔴 选项族那三个不在这儿判来源 —— 那是 validateOptionSource 的活，两处都报就是把一个根因说两遍")
    public void missingWidgetConfig_optionWidgetNotAskedForSource() {
        for (String type : FormFieldFactory.OPTION_WIDGETS) {
            FormDesignApi.WidgetSimpleRespDTO widget = contractWidget(type,
                    prop(FormFieldFactory.DICT_SOURCE_KEY, false),
                    prop(FormFieldFactory.DATA_SOURCE_KEY, false),
                    prop(FormFieldFactory.OPTIONS_KEY, false));

            assertTrue(FormFieldFactory.missingWidgetConfig(widget, null).isEmpty(),
                    type + " 缺来源该由选项来源那条规则说，不该在这里再说一遍");
        }
    }

    @Test
    @DisplayName("没有 props 的控件与 null 控件一律「不缺」—— 判据不许凭空造出缺口")
    public void missingWidgetConfig_noPropsNothingMissing() {
        assertTrue(FormFieldFactory.missingWidgetConfig(contractWidget("input"), null).isEmpty());
        assertTrue(FormFieldFactory.missingWidgetConfig(contractWidget("input"), ruleWithProps()).isEmpty());
        assertTrue(FormFieldFactory.missingWidgetConfig(null, null).isEmpty());
    }

    @Test
    @DisplayName("checkWidgetAuthorable：缺就把缺口说成一句人话，不缺返回 null")
    public void checkWidgetAuthorable_namesTheGap() {
        assertNull(FormFieldFactory.checkWidgetAuthorable(contractWidget("input")));

        String gap = FormFieldFactory.checkWidgetAuthorable(
                contractWidget("DictSelect", prop("dictType", true)));

        assertTrue(gap.contains("dictType"), gap);
        assertTrue(gap.contains("fields[]"),
                "要说清缺口在<b>字段描述</b>这一侧、不在控件那一侧，否则模型只会去改控件名：" + gap);
    }

}
