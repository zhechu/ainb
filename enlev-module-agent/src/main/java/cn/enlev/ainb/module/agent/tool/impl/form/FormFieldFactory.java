package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.FormSchemaValidator;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 「简化的字段描述」→ 表单各处产物的<b>唯一一份</b>转换
 *
 * <h3>为什么必须有这一份</h3>
 * 模型给的是 {@code {field, widget, title, column, jdbcType, required, searchable, placeholder}}
 * 这样的描述，而库里存的是四份彼此必须一致的东西：
 * <ul>
 *   <li>{@code formFields} —— form-create 的 rule（含 {@code _fc_id}/{@code name}/{@code _fc_drag_tag} 内部字段）</li>
 *   <li>{@code listConf.columns} —— 列表列，日期类还要带 formatter</li>
 *   <li>{@code listConf.searchItems} —— 查询区，widget/operator 的推断必须与前端 {@code searchWidget.ts} 逐字一致</li>
 *   <li>{@code tableBinding.fieldMapping} —— 落库映射，缺了它「填了、提交成功、值没进库」</li>
 * </ul>
 *
 * <p>🔴 让模型直接写这四份是不现实的：写错<b>不报错</b>，只是渲染不出来 / 查不动 / 不落库。
 * 而只要有两个调用方各自实现一遍，它们就必然漂开 —— 实测漂过一次：
 * {@code form_save_draft} 会把 {@code searchable} 变成查询项，{@code form_update} 完全不认这个词，
 * 于是「给已有表单加一个查询条件」保存成功、查询区一个框都没多。
 * 所以新建与修改<b>共用本类</b>，改一处两边同时生效。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormFieldFactory {

    /**
     * form-create 内部标识的前缀：{@code _fc_id = id_ai%04d}、{@code name = ref_ai%04d}
     *
     * <p>这两个值由工具统一生成而不是让模型编：模型编出来的是重复值，
     * 而重复的后果是控件在渲染时互相覆盖 —— 表现是「加了两个字段只显示一个」。
     * 前缀写死成 {@code _ai} 系列，也让人一眼看出「这一格是 AI 建的」。
     */
    private static final String FC_ID_PREFIX = "id_ai";
    private static final String FC_NAME_PREFIX = "ref_ai";

    /** 匹配上面那两个前缀生成的序号，用来给新增控件续号（见 {@link #nextSeq}） */
    private static final Pattern FC_ID_SEQ = Pattern.compile("^" + FC_ID_PREFIX + "(\\d+)$");

    /**
     * 字段级格式校验在 rule 里的键名：{@code props.validateRules}
     *
     * <p>public 是给三个消费方共用同一份字面量：{@link #toRule} 写它、
     * {@code FormUpdateTool#mergeRule} 把它并进已有 rule 的 props、
     * {@code FormSchemaValidator#validateRule} 读它做校验。
     * 🔴 三处各写一遍字符串的话，改名的表现是「新建的表单有校验、改出来的没有」，
     * 而两侧都保存成功、都不报错。
     *
     * <p>键名本身出自 infra 的 {@code FieldValidationRule}（那个类的注释解释了为什么
     * 刻意不叫 {@code validate}），本模块只是引用，<b>不</b>另立一个名字。
     */
    public static final String VALIDATE_RULES_KEY = "validateRules";

    /** 选项来源在 rule 里的三个键名。public 的理由与 {@link #VALIDATE_RULES_KEY} 完全同一条 */
    public static final String OPTIONS_KEY = "options";
    public static final String DICT_SOURCE_KEY = "dictSource";
    public static final String DICT_TYPE_KEY = "dictType";
    public static final String DATA_SOURCE_KEY = "dataSource";

    /**
     * 「选项类」控件：它们的静态选项<b>都写在 rule 顶层的 {@code options}</b>、valueKey 都是 {@code value}
     *
     * <h3>🔴 为什么只有这三个，而不是把前端那张表整个搬过来</h3>
     * 前端 {@code optionSource.ts} 的 {@code OPTION_SOURCE_TARGETS} 一共登记了 6 个控件，
     * 但它们的落点<b>各不相同</b>：cascader 写 {@code props.options}，tree/elTreeSelect 写
     * {@code props.data} 且 valueKey 是 {@code id}。搬过来就得在本类里养一份「控件 → 落点/键名」的表，
     * 而那份表与前端那张<b>必然漂移</b>，漂了不报错 —— 表现正是本类要消灭的那个：
     * 选项写进去了、下拉还是空的。
     *
     * <p>这三个之所以能合成一条，是因为它们的落点与 valueKey <b>逐字相同</b>，
     * 于是 Java 侧只需要一个常量、不需要任何按控件分叉的表 ⟹ 结构上就没有可漂移的东西。
     * 这三件套在本类里<b>原先已经出现过一次</b>（{@link #buildSearchItem} 的
     * {@code case "select", "radio", "checkbox"}），本常量是把那处重复收口，不是新增一处。
     *
     * <p>⚠️ 日后要支持 cascader/tree，正确做法是经契约 JSON + {@code FormDesignApi}
     * 开一个只读出口（{@code getSearchOperatorList} 那条先例），<b>不要</b>在这里手抄落点表。
     */
    public static final Set<String> OPTION_WIDGETS = Set.of("select", "radio", "checkbox");

    /**
     * 字典选项的值类型一律写 {@code "str"}
     *
     * <p>🔴 这个数字是<b>量出来的</b>，不是推出来的：库里 104 份真实表单的
     * {@code props.dictSource.valueType} <b>全部</b>是 {@code "str"}，其中 4 个字典的取值清一色是数字
     * （{@code common_status} 的 0/1 之类）；且 {@code test_demo_*} 里每一个绑字典的列都是 {@code varchar}。
     *
     * <p>所以这里<b>刻意不做</b>「jdbcType → valueType」的推断。要做就得把契约 JSON 里
     * {@code jdbcTypeFamilies} 那套<b>顺序敏感的关键字包含</b>算法在 Java 侧再实现一遍 ——
     * 而那份 JSON 的 {@code _comment} 明写不许各自硬编码关键字（「最容易在一侧被改动后悄悄失配」），
     * 且 {@code JdbcTypeFamily} 在 infra 的 framework 包里、本模块<b>压根 import 不到</b>
     * （{@code AgentArchitectureTest#testOnlyDependOnApiLayer}）。
     * 推断错的代价也不是报错：值类型对不上时字典选项匹配不上，下拉又是空的。
     */
    public static final String DICT_VALUE_TYPE = "str";

    /**
     * 「这个控件 AI 建不出来」在<b>提示词</b>与<b>报错文案</b>里共用的标记
     *
     * <p>🔴 收成一个常量，理由与 {@link #optionWidgetCatalog()} 完全同一条：控件白名单里逐行打的是它
     * （{@code WorkflowDslPlannerPrompt#appendWidgetCatalog}）、段末汇总解释的是它、
     * 规划期回灌那句话点名的还是它（{@code FormSchemaValidator#checkWidgetType}）。
     * 三处各敲一遍字面量，漂了的表现是「报错让模型避开一个清单里压根没出现过的符号」——
     * 模型无从对照，于是照旧选它，白烧一轮计费。
     */
    public static final String UNAUTHORABLE_MARK = "⚠️AI建不出来";

    /**
     * {@link #toRule} 真能写进 {@code rule.props} 的键：<b>只有这两个</b>
     *
     * <p>🔴 这份名单是 {@link #missingWidgetConfig} 的判据之一，改 {@code toRule}
     * （往 props 里多写一格）就必须同步改这里 —— 两者放在同一个文件里正是为了让这层耦合看得见。
     * 不同步的后果分两半、都不报错：多写了却没加进名单 ⟹ 本来建得出来的控件被判成建不出来；
     * 加进名单却没真写 ⟹ 判据说建得出来，存进去的 rule 缺那一格，运行期又是个空控件。
     *
     * <p>⚠️ 选项来源那两格（{@link #DICT_SOURCE_KEY} / {@link #OPTIONS_KEY}）<b>不在</b>这里：
     * 它们由 {@link #applyOptionSource} 写，且<b>只</b>对 {@link #OPTION_WIDGETS} 那三个生效 ——
     * 这正是 {@link #missingWidgetConfig} 里「选项来源」要单独成一条判据的原因。
     */
    private static final Set<String> AUTHORABLE_PROP_KEYS = Set.of("placeholder", VALIDATE_RULES_KEY);

    /**
     * 契约里表示<b>选项来源</b>的 prop 键名
     *
     * <p>🔴 前三个直接引本类那几个常量（那三个名字在本模块只有那一份出处，见
     * {@link #DICT_SOURCE_KEY} 的注释）；{@code "data"} 是 tree / elTreeSelect 的落点，
     * 本类<b>从不</b>写它，故只在这里以字面量出现 —— 它出现在这份名单里的意义恰恰是
     * 「有控件把选项放在这儿，而我们够不着」。
     *
     * <p>⚠️ 别把它与 {@link #OPTION_WIDGETS} 搞混：那一份是「哪些控件的静态选项写在 rule <b>顶层</b>」，
     * 这一份是「契约声明的选项来源键名有哪些」。两份名单的交集就是本类真能写的那三个。
     */
    private static final Set<String> OPTION_SOURCE_PROP_KEYS = Set.of(
            DICT_SOURCE_KEY, DATA_SOURCE_KEY, OPTIONS_KEY, "data");

    @Resource
    private FormSchemaValidator schemaValidator;
    @Resource
    private DataModelApi dataModelApi;

    /**
     * 把模型写的控件名归一到契约规范名（{@code Input}→{@code input}、{@code date}→{@code datePicker}）
     *
     * <p>不归一存进去的 rule 渲染不出来，而且不报错。规则本体在 {@link FormSchemaValidator}，
     * 这里只是转一手，让「新建」与「修改」两条路都必经同一份归一。
     */
    public String canonicalType(String widget) {
        return schemaValidator.canonicalType(widget);
    }

    /**
     * 描述符 → form-create rule
     *
     * @param descriptor 模型给的字段描述
     * @param seq        序号，决定 {@code _fc_id}/{@code name}
     * @param nullability 物理表「列名(小写) → 是否可空」，用来对齐 required 与 NOT NULL
     */
    public JSONObject toRule(JSONObject descriptor, int seq, Map<String, Boolean> nullability) {
        String field = descriptor.getStr("field");
        String widget = canonicalType(descriptor.getStr("widget"));
        String column = StrUtil.blankToDefault(descriptor.getStr("column"), field);

        JSONObject rule = new JSONObject();
        rule.set("type", widget);
        rule.set("field", field);
        rule.set("title", descriptor.getStr("title"));
        rule.set("$required", resolveRequired(descriptor.getBool("required", false), column, nullability));
        rule.set("_fc_id", fcId(seq));
        rule.set("name", fcName(seq));
        rule.set("_fc_drag_tag", widget);
        rule.set("display", true);
        rule.set("hidden", false);
        // 🔴 props 里写得进去的<b>只有</b> placeholder 与下面那个 validateRules ——
        //    这两格就是 AUTHORABLE_PROP_KEYS 的全部内容。往这里多写一格，
        //    就必须同步改那份名单，否则「AI 建不建得出来」那个判据会漂（见 missingWidgetConfig）
        JSONObject props = new JSONObject();
        if (StrUtil.isNotBlank(descriptor.getStr("placeholder"))) {
            props.set("placeholder", descriptor.getStr("placeholder"));
        }
        // 🔴 字段格式校验（身份证 / 手机号 / 邮箱…）落在 props.validateRules，不是 rule 级的 validate。
        //    键名与形状由 infra 的 FieldValidationRule 定义，前端从它【派生】出 form-create 的 validate
        //    （单向，见那个类的注释）；服务端复校验读的是同一个 props.validateRules。
        //    写成 rule.validate 的话两侧都读不到 —— 表单存得进去、发布也过得去，就是任何输入都收。
        //
        //    这里【原样透传】、不做任何归一或筛选：形状对不对由 FormSchemaValidator 统一判
        //    （新建与修改两条路都过它）。在这儿悄悄丢掉一个看不懂的写法，
        //    就又造出一例「用户要了校验、工具说建好了、其实一个字都没生效」。
        if (descriptor.containsKey(VALIDATE_RULES_KEY)) {
            props.set(VALIDATE_RULES_KEY, descriptor.get(VALIDATE_RULES_KEY));
        }
        rule.set("props", props);
        if (isOptionWidget(widget)) {
            applyOptionSource(rule, descriptor);
        }
        return rule;
    }

    /**
     * 这个控件是不是「靠选项来源渲染」的那三个之一（{@link #OPTION_WIDGETS}）
     */
    public boolean isOptionWidget(String widget) {
        return widget != null && OPTION_WIDGETS.contains(widget);
    }

    /**
     * 选项类控件名单的<b>教学文本</b>：{@code checkbox、radio、select}
     *
     * <p>与 {@code FormSchemaValidator#validateRulesCatalog} 同一条纪律：报错文案与提示词
     * 必须共用一份渲染，两处各敲一遍就会漂，而漂的表现是「照着文案改、改完还是被拒」。
     *
     * <p>🔴 <b>必须排序</b>：{@link #OPTION_WIDGETS} 是 {@code Set.of(...)}，遍历顺序
     * 由 JVM 的 salt 决定、<b>每次启动都可能不同</b>。直接 join 的话同一份 DSL 两次体检
     * 报出来的文案不一样 —— 断言它的测试会变成偶发红灯，而人会以为是两个不同的问题。
     */
    public static String optionWidgetCatalog() {
        return OPTION_WIDGETS.stream().sorted().collect(Collectors.joining("、"));
    }

    /**
     * 把描述符里的选项来源写进 rule：<b>字典</b>（{@code dictType}）或<b>静态选项</b>（{@code options}）
     *
     * <h3>🔴 这一份是「AI 建的 select 是空下拉」那个缺陷的修复本体</h3>
     * 修复前本类<b>压根不写选项</b>：{@code dictType} 唯一的消费者是 {@link #buildSearchItem}，
     * 而它只在 {@code searchable=true} 时才被调到。于是「状态：下拉，字典 common_status」这句话
     * 的产物是一张<b>列表查询区有下拉、表单里那一格是空下拉</b>的表单 ——
     * 存得进去、发布也过得去、AI 告诉用户建好了，用户点进去才发现选不了任何值。
     * 更常见的是模型连字典都没编：描述符里<b>没有 options 这个键</b>，它想给固定选项也无从表达，
     * 于是只能去编一个 {@code dictType}（实测 34 份定义里 7 个 dictType，<b>6 个是编的</b>）。
     *
     * <h3>落点与形状全部取自库里的真实表单，不是这里设计的</h3>
     * <ul>
     *   <li>静态选项在 rule <b>顶层</b>：{@code {"type":"select",…,"options":[{"label":"阅读","value":"read"}]}}。
     *       契约 JSON 里 select 的 {@code options} PropSpec 也这么写（「本控件的静态选项写在规则顶层的
     *       options 上，不在 props 里」）；只有 cascader 是 {@code props.options}，而它不在本类范围内。</li>
     *   <li>字典在 {@code props.dictSource}，且<b>同时</b>把顶层 {@code options} 写成 {@code []}：
     *       {@code {"type":"select",…,"props":{"dictSource":{"dictType":"test_demo_priority","valueType":"str"}},"options":[]}}。
     *       前端 {@code stripResolvedOptions} 就是把派生来源的选项路径清空的，留着旧值会被当成静态选项。</li>
     * </ul>
     *
     * <h3>两个优先级/互斥规则都是前端那两份的镜像，不许在这里另想一套</h3>
     * <ul>
     *   <li><b>字典赢</b>：两个都给了就用字典 —— 对齐 {@code detectOptionSourceType}
     *       （{@code dataSource.interfaceCode} → {@code dictSource.dictType} → 静态）。</li>
     *   <li><b>换成静态时删掉 {@code props.dictSource} 与 {@code props.dataSource}}</b> ——
     *       对齐设计器 {@code clearOtherSources}。不删的话上面那个优先级会反过来咬人：
     *       用户明明改成了静态选项，运行期仍按字典取，界面上一片空白。</li>
     * </ul>
     *
     * <p>⚠️ 三态：<b>两个键都没提就一个字都不动</b>。这与 {@code FormUpdateTool#mergeRule} 处理
     * {@code required} 是同一条纪律 —— 在「改表单」那条路上，patch 没提选项来源不等于要把它清空，
     * 而清空一个用户在设计器里配好的字典正是「悄悄改掉用户的东西」。
     *
     * <p>🔴 本方法<b>只</b>负责写，不负责判「写得对不对」。字典存不存在、静态选项是不是
     * {@code {label,value}} 的形状、选项控件一个来源都没有 —— 全部由 {@code FormSchemaValidator}
     * 统一判（新建与修改两条路都过它），规划期还有一道 V29 提前拦。
     * 在这里再判一遍就是第二份判据，而判据漂移的表现恰好是本方法要修的那个静默失效。
     *
     * @param rule       目标 rule（{@code props} 必须已经 set 过）
     * @param descriptor 描述符（新建给的是字段描述，修改给的是 patch）
     */
    public void applyOptionSource(JSONObject rule, JSONObject descriptor) {
        String dictType = descriptor.getStr(DICT_TYPE_KEY);
        boolean hasDict = StrUtil.isNotBlank(dictType);
        boolean hasOptions = descriptor.get(OPTIONS_KEY) != null;
        if (!hasDict && !hasOptions) {
            return;
        }
        JSONObject props = rule.getJSONObject("props");
        if (props == null) {
            props = new JSONObject();
        }
        if (hasDict) {
            props.set(DICT_SOURCE_KEY, new JSONObject()
                    .set(DICT_TYPE_KEY, dictType)
                    .set("valueType", DICT_VALUE_TYPE));
            props.remove(DATA_SOURCE_KEY);
            rule.set("props", props);
            rule.set(OPTIONS_KEY, new JSONArray());
        } else {
            props.remove(DICT_SOURCE_KEY);
            props.remove(DATA_SOURCE_KEY);
            rule.set("props", props);
            rule.set(OPTIONS_KEY, descriptor.get(OPTIONS_KEY));
        }
    }

    // ==================== 这个控件配得齐吗 ====================

    /**
     * 这一格控件<b>缺了哪些契约说「不填就没法正常工作」的配置</b>
     *
     * <h3>🔴 这一条守的是「控件在白名单里、也确实可用，但那一格是空的」</h3>
     * 实测 63 个可用控件里有 <b>18 个</b>落在这一类，两种形状：
     * <ul>
     *   <li><b>必填配置够不着</b>（15 个）：{@code DictSelect} 要 {@code dictType}、
     *       {@code RelationForm} 要 {@code targetFormCode}+{@code displayField}、
     *       {@code Formula} 要 {@code expression}、{@code fcTitle} 要 {@code title}…
     *       而 {@link #toRule} 往 props 里只写 {@link #AUTHORABLE_PROP_KEYS} 那两格。
     *       契约对 {@code required} 的原话就是「不填则该控件无法正常工作」。</li>
     *   <li><b>选项来源够不着</b>（3 个）：{@code cascader} 的静态选项在 {@code props.options}、
     *       {@code tree} / {@code elTreeSelect} 在 {@code props.data}，而
     *       {@link #applyOptionSource} <b>只</b>给 {@link #OPTION_WIDGETS} 那三个写。
     *       于是「省市区级联」这种需求存得进、发布得过，运行期是个点不开的下拉。</li>
     * </ul>
     * 两种形状的后果一模一样，也正是本类要消灭的那个：<b>不报错的失效</b> ——
     * 草稿照存、发布照过、AI 告诉用户建好了，用户点进去才发现那一格什么都没有。
     *
     * <h3>🔴 判据一律从契约现算，本类<b>不</b>持有那 18 个名字</h3>
     * 事实取自 {@code WidgetSimpleRespDTO#props} 的 {@code required} 与键名。手抄一份名单必然漂移，
     * 而漂了的表现恰好是本条要拦的那个：契约给某个控件新加一个必填配置项，名单没跟上 ⟹
     * 又能建出一个空控件，且照样不报错。
     *
     * <h3>为什么不干脆支持它们</h3>
     * 支持就要在本类里养一份「控件 → 选项落点/键名」的表，而那份表与前端 {@code optionSource.ts} 的
     * {@code OPTION_SOURCE_TARGETS} <b>必然漂移</b> —— {@link #OPTION_WIDGETS} 的注释已经把这个理由
     * 写过一遍（含「日后要支持 cascader/tree 的正确做法」），这里不再抄第二份判据。
     * 取舍是<b>如实拒绝</b>：拦在建表单那一刻（{@code FormSchemaValidator#validateRule}）
     * 与规划期（{@code FormSchemaValidator#checkWidgetType}），并给出两条真能照做的出路。
     *
     * <h3>⚠️ {@code rule} 传 {@code null} 就是「字段描述能写出来的最好情况」</h3>
     * 这是给<b>手上只有控件名、还没有 rule</b> 的两个消费者用的：控件白名单那段提示词
     * （要给每个控件打标）与规划期回灌（DSL 里冻的是描述符）。此时必填项一律算缺 ——
     * 但 {@link #AUTHORABLE_PROP_KEYS} 那两格<b>不在</b>必填项的扫描范围里
     * （描述符写得进去，缺了是模型没写 placeholder，不是本条要拦的形状），
     * 所以「空 rule 的判定结果」与「真 rule 的判定结果」在<b>新建</b>那条路上必然一致：
     * {@code toRule} 的产物除了那两格与选项来源，props 里什么都没有。
     *
     * @param widget 契约里的这一个控件；{@code null} 或没有 props ⟹ 不缺
     * @param rule   要判的 form-create rule；{@code null} 表示「还没有 rule」（见上）
     * @return 缺的配置（人读的顺序，可直接拼进文案）；不缺 ⟹ 空列表
     */
    public static List<String> missingWidgetConfig(FormDesignApi.WidgetSimpleRespDTO widget, JSONObject rule) {
        if (widget == null || CollUtil.isEmpty(widget.getProps())) {
            return List.of();
        }
        // 🔴 用 instanceof 而不是 rule.getJSONObject("props")：props 被写成一个字符串/数组时
        //    后者会抛，而「props 形状不对」本身已有别处报错，这里只当「一格都没配」处理
        JSONObject props = rule != null && rule.get("props") instanceof JSONObject obj ? obj : null;
        // ⚠️ 先判 null：OPTION_WIDGETS 是 Set.of(...)，contains(null) 直接 NPE
        boolean optionWidget = widget.getType() != null && OPTION_WIDGETS.contains(widget.getType());
        List<String> missing = new ArrayList<>();
        List<String> sourceKeys = new ArrayList<>();
        for (FormDesignApi.WidgetPropDTO prop : widget.getProps()) {
            String name = prop == null ? null : prop.getName();
            if (StrUtil.isBlank(name) || AUTHORABLE_PROP_KEYS.contains(name)) {
                continue;
            }
            if (Boolean.TRUE.equals(prop.getRequired()) && isBlankConfig(props, name)) {
                missing.add(name);
            }
            if (!optionWidget && OPTION_SOURCE_PROP_KEYS.contains(name)) {
                sourceKeys.add(name);
            }
        }
        // 🔴 选项来源是「声明的这几个键里有一个配了就算配了」，与必填项「逐个都得有」不同 ——
        //    对齐前端 detectOptionSourceType 的优先级：dataSource.interfaceCode → dictSource.dictType → 静态。
        //    那三个本来互斥（见 applyOptionSource 的注释），逐个要求就会把配好了的控件也判成缺
        if (!sourceKeys.isEmpty() && sourceKeys.stream().allMatch(key -> isBlankConfig(props, key))) {
            missing.add("选项来源（props." + String.join(" / props.", sourceKeys) + " 之一）");
        }
        return missing;
    }

    /**
     * 这个控件<b>用字段描述建不建得出来</b>
     *
     * <p>它就是 {@link #missingWidgetConfig} 在「还没有 rule」那一档上的读法，
     * 抽出来是给两个只有控件名的消费者共用一句因果话术：控件白名单那段提示词
     * （{@code WorkflowDslPlannerPrompt#appendWidgetCatalog} 用它打标）与
     * {@code FormSchemaValidator#checkWidgetType}（规划期回灌）。
     *
     * @param widget 契约里的这一个控件
     * @return 建得出来返回 {@code null}；否则一句<b>原因</b>，由调用方按自己那条路径包成完整文案
     *         （两边的补救建议必须各自「在这条路径上照做得到」，故不在这里写死出路）
     */
    public static String checkWidgetAuthorable(FormDesignApi.WidgetSimpleRespDTO widget) {
        List<String> missing = missingWidgetConfig(widget, null);
        if (missing.isEmpty()) {
            return null;
        }
        return "契约要求 " + String.join("、", missing) + "，而字段描述（fields[]）里没有能放这些配置的格子";
    }

    /**
     * 这一格配置算不算「没配」
     *
     * <p>🔴 空串、空数组、空对象都算没配：契约里 {@code dictType} 是 string、
     * {@code options} 是 array、{@code dictSource} 是 object，只判 {@code null} 的话
     * 「写了个空值」会被当成配好了 —— 而前端读到的同样是一个空下拉。
     */
    private static boolean isBlankConfig(JSONObject props, String name) {
        if (props == null) {
            return true;
        }
        Object value = props.get(name);
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence text) {
            return StrUtil.isBlank(text.toString());
        }
        // hutool 的 JSONArray 本身就是一个 List，故这一支同时管住数组与任何集合
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof JSONObject object) {
            return object.isEmpty();
        }
        return false;
    }

    /**
     * 由 rule 反推它对应的列表列（field/label + 日期类 formatter）
     *
     * <p>不给日期控件配 formatter 的话，列表里日期显示成原始时间戳/ISO 串，不可读。
     */
    public JSONObject toColumn(String field, String title, String widget) {
        JSONObject col = new JSONObject();
        col.set("field", field);
        col.set("label", title);
        if ("datePicker".equals(widget)) {
            col.set("formatter", new JSONObject().set("type", "DATE"));
        } else if ("timePicker".equals(widget)) {
            col.set("formatter", new JSONObject().set("type", "DATETIME"));
        }
        return col;
    }

    /**
     * 构造一个列表查询项，widget/operator 推断逐字对齐前端 {@code searchWidget.ts}
     *
     * <p>🔴 这份推断<b>不能</b>自己另想一套：前端设计器用的是
     * {@code inferSearchWidget} + {@code coerceOperator}，两边不一致的表现是
     * 「设计器里配的查询能用、AI 配的查不动」—— 而两处都保存成功、都不报错。
     *
     * <p>inferSearchWidget：select/radio/checkbox→SELECT、datePicker→DATE_RANGE、
     * inputNumber→NUMBER_RANGE、switch→SWITCH、其余→TEXT；
     * coerceOperator 取该 widget 允许操作符的第一项：TEXT→LIKE、SELECT→EQ、
     * DATE_RANGE/NUMBER_RANGE→BETWEEN、SWITCH→EQ。模型显式给了 operator 则优先用它。
     */
    public JSONObject buildSearchItem(String field, String label, String widget,
                                      String dictType, String operator) {
        String searchWidget;
        String defaultOperator;
        String key = widget == null ? "" : widget;
        // 🔴 用 OPTION_WIDGETS 而不是在这里再列一遍三个 case：那份常量是「哪些控件靠选项来源渲染」
        //    在本类里的唯一出处（applyOptionSource 与 FormSchemaValidator 都读它）。
        //    两处各列一遍的后果是漂移，而漂移的表现分两半、都不报错：
        //    日后加进选项族的控件，要么查询区没下拉、要么表单里那一格没选项。
        //    ⚠️ 先判 null：Set.of(...) 的 contains(null) 直接 NPE
        if (OPTION_WIDGETS.contains(key)) {
            searchWidget = "SELECT";
            defaultOperator = "EQ";
        } else {
            switch (key) {
                case "datePicker" -> {
                    searchWidget = "DATE_RANGE";
                    defaultOperator = "BETWEEN";
                }
                case "inputNumber" -> {
                    searchWidget = "NUMBER_RANGE";
                    defaultOperator = "BETWEEN";
                }
                case "switch" -> {
                    searchWidget = "SWITCH";
                    defaultOperator = "EQ";
                }
                default -> {
                    searchWidget = "TEXT";
                    defaultOperator = "LIKE";
                }
            }
        }
        JSONObject item = new JSONObject();
        item.set("field", field);
        item.set("label", label);
        item.set("widget", searchWidget);
        if (StrUtil.isNotBlank(dictType)) {
            item.set(DICT_TYPE_KEY, dictType);
        }
        item.set("operator", StrUtil.blankToDefault(operator, defaultOperator));
        return item;
    }

    /**
     * 一个字段在 {@code tableBinding.fieldMapping} 里的那一格
     *
     * <p>缺了它的后果不是报错，而是「界面上能填、提交也提示成功、值根本没落库」——
     * 「配了但不生效」里最难被发现的一种，用户往往过很久才察觉。
     *
     * @param declaredJdbcType 模型声明的列类型，仅在物理列读不到时才用得上，见 {@link #resolveJdbcType}
     * @param schema           物理表事实，见 {@link #loadPhysicalSchema}
     */
    public JSONObject toFieldMapping(String mainTable, String column, String declaredJdbcType,
                                     PhysicalSchema schema) {
        JSONObject mapping = new JSONObject();
        mapping.set("table", mainTable);
        mapping.set("column", column);
        mapping.set("jdbcType", resolveJdbcType(declaredJdbcType, column, schema));
        return mapping;
    }

    /**
     * 落库映射里那个 {@code jdbcType} 该写什么：<b>物理列的真实类型</b>，不是模型声明的那个
     *
     * <p>🔴 这一格写错的表现是一句「服务器错误,请联系管理员!」，而根因藏在三层之外：
     * {@code FormValueSerializer#resolveMode} 靠它决定多选这类 delimited 控件是拼逗号串还是存 JSON 串
     * （列类型族是 {@code JSON} 时升级为 JSON 串）。模型把一个 json 列声明成 {@code VARCHAR} 之后，
     * 序列化拼出 {@code 微生物,重金属} 往 json 列里写，MySQL 报
     * {@code Data truncation: Invalid JSON text: "Invalid value." at position 0}。
     * 实测于表单 880510、物理表 {@code biz_lab_sample} 的 {@code test_items} 列。
     *
     * <p>模型为什么会写错：它<b>看不到</b>物理列。建表是上一步 {@code datamodel_create} 干的，
     * 列类型由那边按受控词表定；而 {@code jdbcType} 是让模型对同一件事<b>再猜一次</b> ——
     * 两次独立的猜测不可能永远一致。人工设计器那侧不猜：
     * {@code FormDesignController#getTableColumns} 把物理列的类型直接给前端，前端原样回填。
     * 本方法就是把 AI 这一侧接到同一个事实上。
     *
     * <p>读不到物理列时退回声明值：那说明表还没登记进数据模型，此时 binding 里的类型本来也无从核对，
     * 而「按声明值落库」正是改造前的行为 —— 不能因为读不到就把建表单整条路径卡死。
     *
     * @param declared 模型声明的类型名
     * @param column   物理列名
     */
    public String resolveJdbcType(String declared, String column, PhysicalSchema schema) {
        if (schema == null || StrUtil.isBlank(column)) {
            return declared;
        }
        DataModelStructureRespDTO.ColumnDTO col = schema.columns().get(column.toLowerCase(Locale.ROOT));
        if (col == null || StrUtil.isBlank(col.getColumnType())) {
            return declared;
        }
        String mysqlType = schema.mysqlTypes().get(col.getColumnType().toUpperCase(Locale.ROOT));
        return StrUtil.blankToDefault(mysqlType, declared);
    }

    /**
     * required 的最终值：物理列 NOT NULL 时一律必填，否则照调用方给的
     *
     * <p>🔴 这条对齐守的是一个会炸在数据库上的不一致：列是 NOT NULL 而表单不要求填，
     * 用户留空提交 → 数据库拒绝 → 报一句看不懂的 SQL 异常。
     * 表单这一侧本来就该跟着物理约束走，不该让模型（或用户）来决定。
     */
    public boolean resolveRequired(Boolean requested, String column, Map<String, Boolean> nullability) {
        if (isColumnNotNull(column, nullability)) {
            return true;
        }
        return Boolean.TRUE.equals(requested);
    }

    /**
     * 该列在物理表上是不是 NOT NULL
     *
     * <p>单拎出来是给「改已有字段」用的：那边 required 是三态（改 / 不改 / 没说），
     * 只有确认 NOT NULL 才允许<b>不问就</b>把它抬成必填 —— 反过来「没说就降级成非必填」
     * 会把用户配好的必填悄悄去掉，绝不能做。
     */
    public boolean isColumnNotNull(String column, Map<String, Boolean> nullability) {
        if (StrUtil.isBlank(column) || nullability == null) {
            return false;
        }
        return Boolean.FALSE.equals(nullability.get(column.toLowerCase(Locale.ROOT)));
    }

    /**
     * 读物理表事实：列结构 + 受控列类型 → MySQL 类型名的词表
     *
     * <p>fail-soft：表未登记进数据模型、或任一接口抛错时返回 {@link PhysicalSchema#EMPTY}，
     * 让 required 与 jdbcType 都退回调用方给的值 —— 绝不因这步查询失败而中断建/改表单。
     */
    public PhysicalSchema loadPhysicalSchema(String mainTable) {
        Map<String, DataModelStructureRespDTO.ColumnDTO> columns = new LinkedHashMap<>();
        if (StrUtil.isBlank(mainTable)) {
            return PhysicalSchema.EMPTY;
        }
        try {
            DataModelApi.DataModelSimpleRespDTO model = dataModelApi.getDataModelList().stream()
                    .filter(item -> mainTable.equalsIgnoreCase(item.getTableName()))
                    .findFirst().orElse(null);
            if (model == null) {
                return PhysicalSchema.EMPTY;
            }
            DataModelStructureRespDTO structure = dataModelApi.getStructure(model.getId());
            if (structure == null || structure.getColumns() == null) {
                return PhysicalSchema.EMPTY;
            }
            for (DataModelStructureRespDTO.ColumnDTO col : structure.getColumns()) {
                if (col != null && StrUtil.isNotBlank(col.getColumnName())) {
                    columns.put(col.getColumnName().toLowerCase(Locale.ROOT), col);
                }
            }
        } catch (Exception ex) {
            log.warn("[loadPhysicalSchema][读取表({}) 结构失败，required 与 jdbcType 将沿用调用方给的值：{}]",
                    mainTable, ex.getMessage());
            return PhysicalSchema.EMPTY;
        }
        return new PhysicalSchema(columns, loadMysqlTypes(mainTable));
    }

    /**
     * 受控列类型(大写) → MySQL 类型名(大写)
     *
     * <p>🔴 词表<b>不在本类里抄一份</b>，而是读 {@link DataModelApi#getColumnTypeList()}：
     * {@code DataModelStructureRespDTO.ColumnDTO#columnType} 的注释已经点明
     * 「权威出口是 {@code getColumnTypeList()}，别拿注释当判据」，而 {@code DataModelApi#getTableConstraints}
     * 的注释更进一步写了理由 ——「校验器若自己抄一份，就会和这边的实现<b>各自漂移</b>」。
     * 抄一份的表现是「加了列类型之后，AI 建的表单又开始写错 jdbcType」，且没有任何测试会变红。
     */
    private Map<String, String> loadMysqlTypes(String mainTable) {
        try {
            Map<String, String> result = new LinkedHashMap<>();
            for (DataModelApi.ColumnTypeRespDTO type : dataModelApi.getColumnTypeList()) {
                if (type != null && StrUtil.isNotBlank(type.getType())
                        && StrUtil.isNotBlank(type.getMysqlType())) {
                    result.put(type.getType().toUpperCase(Locale.ROOT),
                            type.getMysqlType().toUpperCase(Locale.ROOT));
                }
            }
            return result;
        } catch (Exception ex) {
            // 词表读不到只影响 jdbcType 归真（退回声明值），列结构与 required 对齐照常
            log.warn("[loadMysqlTypes][读取列类型词表失败，表({}) 的 jdbcType 将沿用模型给的值：{}]",
                    mainTable, ex.getMessage());
            return Map.of();
        }
    }

    /**
     * 一次读齐的物理表事实
     *
     * <p>两样东西放在一起，是因为它们必须<b>同一时刻</b>的口径：列结构说的是这张表现在长什么样，
     * 词表说的是那些受控类型名各自对应哪个 MySQL 类型。分开读要打两次接口，
     * 而 {@code resolveJdbcType} 要用前者查到的 {@code columnType} 去后者里换名字 ——
     * 两份数据来自不同时刻时，换出来的名字可能对不上刚建好的列。
     *
     * @param columns    列名(小写) → 列结构。空表 = 读不到（表未登记 / 接口故障），
     *                   此时 {@link #resolveJdbcType} 与 {@link FormFieldFactory#isColumnNotNull}
     *                   都退回调用方给的值
     * @param mysqlTypes 受控列类型(大写) → MySQL 类型名(大写)
     */
    public record PhysicalSchema(Map<String, DataModelStructureRespDTO.ColumnDTO> columns,
                                 Map<String, String> mysqlTypes) {

        public static final PhysicalSchema EMPTY = new PhysicalSchema(Map.of(), Map.of());

        /**
         * 列名(小写) → 是否可空，喂给 {@link FormFieldFactory#resolveRequired}
         *
         * <p>调用方<b>只调一次</b>再把结果传下去：{@code toRule} 是逐字段调的，
         * 每次都重建这张表就是 O(n²) 的白做功。
         */
        public Map<String, Boolean> nullability() {
            Map<String, Boolean> result = new LinkedHashMap<>(columns.size());
            columns.forEach((column, col) -> result.put(column, col.getNullable()));
            return result;
        }

    }

    /**
     * 给一批已有 rule 续号：返回第一个没被占用的序号
     *
     * <p>🔴 不能从 1 重新开始。往一张已有 5 个控件的表单里加第 6 个时若给了 {@code id_ai0001}，
     * 就与现存的撞了 —— 而 form-create 靠 {@code _fc_id} 认控件，撞号的表现是
     * 两个控件互相覆盖，界面上少一个，且<b>不报错</b>。
     *
     * <p>非本工具生成的 rule（设计器建的那些 {@code _fc_id} 长别的样）不参与取最大值，
     * 它们与 {@code id_ai} 号段天然不冲突。
     */
    public int nextSeq(Collection<JSONObject> rules) {
        int max = 0;
        if (rules == null) {
            return 1;
        }
        for (JSONObject rule : rules) {
            if (rule == null) {
                continue;
            }
            Matcher matcher = FC_ID_SEQ.matcher(StrUtil.nullToEmpty(rule.getStr("_fc_id")));
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return max + 1;
    }

    public String fcId(int seq) {
        return FC_ID_PREFIX + String.format("%04d", seq);
    }

    public String fcName(int seq) {
        return FC_NAME_PREFIX + String.format("%04d", seq);
    }

    /**
     * 由列描述生成默认列表配置（list_conf）
     *
     * <p>给一个「开箱即用」的列表：序号列 + 每字段一列 + 操作列（编辑/详情/删除）+ 分页
     * + 查询项（searchItems）。设计器里能配的项这里只给最常用的默认值，作者发布后仍可进列表设计再调。
     */
    public String buildListConf(List<JSONObject> columns, List<JSONObject> searchItems) {
        JSONObject conf = new JSONObject();
        conf.set("columns", columns);
        JSONObject pagination = new JSONObject();
        pagination.set("enabled", true);
        pagination.set("pageSize", 20);
        conf.set("pagination", pagination);
        conf.set("operationColumn", defaultOperationColumn());
        conf.set("showIndexColumn", true);
        // 运行时 render/index.vue 读的就是 conf.searchItems；
        // 空数组 = 查询区一个框都不渲染（这正是「指定要能查询却被忽略」的表现）
        conf.set("searchItems", searchItems);
        return conf.toString();
    }

    /**
     * 默认操作列
     *
     * <p>{@code create} 是工具栏按钮（新增），{@code edit}/{@code detail}/{@code delete} 是行按钮。
     * 漏了 create 的话列表上方没有「新增」，用户只能看不能录 —— 表现是「发布出去但没法用」。
     */
    public JSONObject defaultOperationColumn() {
        JSONObject operationColumn = new JSONObject();
        operationColumn.set("show", true);
        operationColumn.set("label", "操作");
        operationColumn.set("width", 160);
        operationColumn.set("buttons", new ArrayList<>(List.of("create", "edit", "detail", "delete")));
        return operationColumn;
    }

    public String defaultFormConf() {
        JSONObject form = new JSONObject();
        form.set("inline", false);
        form.set("hideRequiredAsterisk", false);
        form.set("labelPosition", "right");
        form.set("size", "default");
        form.set("labelWidth", "150px");
        JSONObject submitBtn = new JSONObject();
        submitBtn.set("show", true);
        submitBtn.set("innerText", "提交");
        JSONObject resetBtn = new JSONObject();
        resetBtn.set("show", false);
        resetBtn.set("innerText", "重置");
        JSONObject conf = new JSONObject();
        conf.set("form", form);
        conf.set("submitBtn", submitBtn);
        conf.set("resetBtn", resetBtn);
        return conf.toString();
    }

}
