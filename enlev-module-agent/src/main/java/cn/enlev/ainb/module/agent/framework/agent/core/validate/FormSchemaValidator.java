package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictDataRespDTO;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.agent.tool.impl.form.FormFieldFactory;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.system.api.dict.DictDataApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 表单 schema 的生成后校验（方案 §5.5 前半）
 *
 * <h3>这里拦的每一条，后端都不会在「保存」时报错</h3>
 * 表单草稿保存几乎不校验，问题要到<b>发布</b>甚至<b>运行时</b>才暴露。
 * 而那时 AI 已经告诉用户「表单建好了」——
 * 用户点进去看到的是一个渲染不出来、或者存不进数据的表单。
 *
 * <p>所以这些必须在进 Plan 之前就拦住。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormSchemaValidator {

    /**
     * 由后端按登录上下文维护的列，禁止被表单字段映射
     *
     * 与 {@code FormDesignPublishValidator.BACKEND_MANAGED_COLUMNS} 一致。
     * 映射了会怎样：表单提交时用用户填的值覆盖掉后端按上下文算出来的值 ——
     * {@code tenant_id} 被覆盖就是<b>跨租户写入</b>。
     */
    private static final Set<String> BACKEND_MANAGED_COLUMNS = Set.of("tenant_id");

    /**
     * 不该由用户填、应交给平台自动维护的列
     *
     * 比 {@link #BACKEND_MANAGED_COLUMNS} 宽：这些映射了不会造成安全问题，
     * 但会让「创建人」变成一个用户可以随便改的输入框，所以只警告不拦。
     */
    private static final Set<String> AUTO_FILL_COLUMNS = Set.of(
            "id", "creator", "create_time", "updater", "update_time", "deleted", "version");

    /**
     * 标识符最大长度（MySQL 上限）
     */
    private static final int IDENTIFIER_MAX_LENGTH = 64;

    /**
     * 控件类型的<b>语义别名</b>：模型的直觉名 → 契约规范名（键统一小写）
     *
     * <p>{@link #lowercaseTypeIndex} 只能消化大小写差异（{@code Input}→{@code input}），
     * 消化不了「同义不同名」—— 模型极爱把日期控件写成 {@code date}/{@code datetime}、
     * 数字写成 {@code number}、布尔写成 {@code boolean}，而契约里它们分别叫
     * {@code datePicker}/{@code inputNumber}/{@code switch}。实测踩过 {@code date}：
     * 规划期没拦（见下），一路到运行期 {@code form_save_draft} 才报「控件类型 date 不存在」，
     * 用户看到的是一张点了确认却失败的卡片。
     *
     * <p>与 {@code DataModelParamResolver.COLUMN_TYPE_ALIASES}（TIMESTAMP→DATETIME 等）
     * 是同一条纪律：把模型的高频直觉名在<b>真正保存之前</b>归一到规范名，
     * 而不是靠多烧一轮模型自修正去撞对。
     *
     * <p>🔴 上传族（{@code image}/{@code file} 等）曾经「一律不收」，理由是契约里有
     * UploadFile/UploadImg/UploadImgs 三个候选、怕猜错。实测推翻了这条判断：
     * 规划器把 {@code image} 冻进了已发布流程的 save_draft 参数里（它不是运行期现写的，
     * 改提示词救不了），运行期报「控件类型 image 不存在」整条流程失败 ——
     * <b>整单失败的代价远大于选成单图/多图的代价</b>，后者用户在设计器里一眼能改。
     * 归一规则取最符合直觉的默认：单数→单图 UploadImg，复数→多图 UploadImgs，
     * file/attachment→UploadFile；真要多图的用户写的是「多图/照片墙」，落到的键也是复数。
     *
     * <p>⚠️ 键不得与任何契约规范名的小写形式冲突（否则别名会盖掉真实控件）；
     * {@link #resolveCanonical} 里<b>先查契约、后查别名</b>，从机制上保证契约名永远优先。
     */
    private static final Map<String, String> WIDGET_TYPE_ALIASES = Map.ofEntries(
            Map.entry("date", "datePicker"),
            Map.entry("datetime", "datePicker"),
            Map.entry("time", "timePicker"),
            Map.entry("number", "inputNumber"),
            Map.entry("integer", "inputNumber"),
            Map.entry("int", "inputNumber"),
            Map.entry("decimal", "inputNumber"),
            Map.entry("boolean", "switch"),
            Map.entry("bool", "switch"),
            Map.entry("dropdown", "select"),
            Map.entry("image", "UploadImg"),
            Map.entry("img", "UploadImg"),
            Map.entry("picture", "UploadImg"),
            Map.entry("photo", "UploadImg"),
            Map.entry("images", "UploadImgs"),
            Map.entry("imgs", "UploadImgs"),
            Map.entry("file", "UploadFile"),
            Map.entry("files", "UploadFile"),
            Map.entry("attachment", "UploadFile"));

    @Resource
    private FormDesignApi formDesignApi;
    @Resource
    private DictDataApi dictDataApi;

    /**
     * 校验表单定义（<b>改</b>已有表单那条路），且<b>本次没有新增任何控件</b>
     *
     * <p>与 {@link #validateNewForm} 的差别见 {@link #validateRule} 里那两条分档判据：
     * 选项来源缺失、控件配置缺格，在这里都只出 <b>warning</b> ——
     * 收的是<b>合并后的整份 formFields</b>，里面可能有先于本次请求就存在的格子。
     *
     * <p>🔴 本次<b>动了控件结构</b>的调用方不要用这一档，走
     * {@link #validate(List, String, Collection)} 把新增的字段点出来：
     * 生产上唯一的改表单入口（{@code FormUpdateTool} 的 ⑤）就是这么做的，
     * 而「不点名新增字段」的后果是本次自己造出来的空控件只被提醒一句就存进去了 ——
     * 那正是这一档要保护的「存量缺陷不该挡掉无关请求」反过来咬人的形状。
     */
    public AgentValidateResult validate(List<String> formFields, String tableBinding) {
        return validate(formFields, tableBinding, false, Set.of());
    }

    /**
     * 同上，但<b>点名本次 patch 新增的字段</b>：这些格子按新建那一档判（error）
     *
     * <p>🔴 为什么要多这一个入参，而不能整份一刀切：{@code form_update} 收的是合并后的整份
     * formFields，里面既有<b>本次刚加的</b>控件，也有<b>设计器里早就配好的</b>控件。
     * <ul>
     *   <li>一律按 error 判 ⟹ 一个先于本次请求就存在的缺陷，会把「把备注改成必填」这种
     *       毫不相干的请求整个挡掉（这正是 {@link #validateOptionSource} 分档的理由）；</li>
     *   <li>一律按 warning 判 ⟹ 用户说「加一个字典选择器字段」，工具报一句黄标就把它存进去了，
     *       而那一格是个空控件 —— 本次自己造出来的缺陷，没理由只是提醒。</li>
     * </ul>
     * 所以按<b>格子是不是本次新写的</b>分档，与本类其余各条同一条纪律。
     *
     * @param newFields 本次 patch 新增的 {@code field}（{@code FormUpdateTool} 的 {@code added}）；
     *                  传空即「本次没新增控件」
     */
    public AgentValidateResult validate(List<String> formFields, String tableBinding,
                                        Collection<String> newFields) {
        return validate(formFields, tableBinding, false,
                CollUtil.isEmpty(newFields) ? Set.of() : Set.copyOf(newFields));
    }

    /**
     * 校验表单定义（<b>新建</b>表单那条路，{@code form_save_draft}）
     *
     * <p>「选项来源一个都没有」「控件缺了契约要求的配置」在这里都是 <b>error</b>：
     * 新建时每一个控件都是这次刚写的，没有任何存量需要保护，而放过去的后果是一张
     * <b>存得进、发布得过、那一格是空的</b>表单。
     */
    public AgentValidateResult validateNewForm(List<String> formFields, String tableBinding) {
        return validate(formFields, tableBinding, true, Set.of());
    }

    /**
     * @param newForm   整份都是本次新写的（{@code form_save_draft}）⟹ 分档判据一律走 error 那一档
     * @param newFields 改表单时本次 patch 新增的 {@code field}；{@code newForm=true} 时不需要它
     */
    private AgentValidateResult validate(List<String> formFields, String tableBinding,
                                         boolean newForm, Set<String> newFields) {
        AgentValidateResult result = new AgentValidateResult();
        if (CollUtil.isEmpty(formFields)) {
            result.addError("表单至少要有一个控件");
            return result;
        }

        Map<String, FormDesignApi.WidgetSimpleRespDTO> widgets = loadWidgets();
        Map<String, String> typeIndex = lowercaseTypeIndex(widgets);
        List<FormDesignApi.ValidationPatternRespDTO> patterns = loadValidationPatterns();
        Set<String> fields = new HashSet<>();

        for (String raw : formFields) {
            JSONObject rule = parseRule(raw, result);
            if (rule == null) {
                continue;
            }
            validateRule(rule, widgets, typeIndex, patterns, fields, newForm, newFields, result);
        }

        validateFieldMapping(tableBinding, fields, result);
        return result;
    }

    /**
     * 把模型写的控件类型归一到契约里的规范名
     *
     * <p>两步归一：① 大小写不敏感命中契约规范名（{@code Input}→{@code input}）；
     * ② 命不中再查语义别名（{@code date}→{@code datePicker}）。契约永远优先于别名，
     * 别名只兜「同义不同名」的高频直觉写法（见 {@link #WIDGET_TYPE_ALIASES}）。
     *
     * <p>归一只能在<b>真正保存之前</b>做，只在校验期归一救不了已经写坏的 rule ——
     * 不归一的下场不是报错，而是整条建表单流程在运行期失败、或表单存进去渲染不出来。
     *
     * @param type 模型给出的控件类型
     * @return 契约规范名；契约与别名都不认识时原样返回，交由校验报错
     */
    public String canonicalType(String type) {
        if (StrUtil.isBlank(type)) {
            return type;
        }
        return resolveCanonical(type, lowercaseTypeIndex(loadWidgets()));
    }

    /**
     * 判定一个控件类型最终能不能用：归一后查契约
     *
     * <p>给<b>不持有 form-create rule、只拿着控件名</b>的校验方用 —— 典型是
     * {@code WorkflowDslValidator}：form_save_draft 节点的参数是规划期<b>冻进 DSL</b> 的，
     * 运行期不会再问模型，错了就是整条执行失败（实测 {@code image} 就是这么炸的）。
     * 所以规划期就要用与运行期<b>同一份</b>归一+白名单判定拦一遍。
     *
     * <h3>🔴 返回的补救建议必须在这条路径上<b>照做得到</b></h3>
     * 本方法的唯一消费者是规划期的 {@code WorkflowDslValidator#validateSaveDraftWidgets}，
     * 而 planner 那条路径上<b>没有任何工具可调</b>（{@code AiChatReqDTO} 里连工具字段都没有）。
     * 原文案写的是「可用类型请调 widget_contract_get 查询」，那是一句<b>做不到</b>的指令，
     * 却会被每轮回灌原样重复一遍 —— 模型于是在同一堵墙上撞满轮次上限：
     * 实测连猜六个控件名，第五轮改写成 {@code {{$.valid_widgets.user_widget}}}
     * 想让运行期替它取值（疤记在 {@code WorkflowDslPlannerPrompt#appendWidgetCatalog}）。
     * 故这里改成指向 <b>system prompt 里那份完整白名单</b>。
     *
     * <p>⚠️ 与 {@link #validateRule} 那句同形文案<b>刻意不同</b>：那一条服务的是持有 rule 的
     * 执行期路径（{@code form_save_draft} 工具真的能调 {@code widget_contract_get}），
     * 别为了「统一文案」把两边合回去。
     *
     * <h3>🔴 第三道判定：在白名单里 ≠ AI 建得出来</h3>
     * 实测 63 个可用控件里有 <b>18 个</b>是「契约里存在、{@code supported=true}，
     * 而它们的必需配置字段描述表达不了」（{@code DictSelect} 要 {@code dictType}、
     * {@code cascader} 的选项要写进 {@code props.options}…）。判据与后果都在
     * {@code FormFieldFactory#missingWidgetConfig} 的注释里，这里只说<b>为什么落在本方法</b>：
     * 本方法的唯一消费者（{@code WorkflowDslValidator#validateSaveDraftWidgets}）已经
     * 每个字段调它一次，而每次调用都要读一遍契约（infra 那侧没有缓存）——
     * 挂在这里 ⟹ 这道闸<b>一次额外探测都不花</b>；另起一条规则就是每个字段再读一遍契约，
     * 还多一个探测中止点。
     *
     * <p>⚠️ 所以它<b>没有取新的 V 号</b>：这不是新增的一条 DSL 校验规则，而是既有「控件白名单」
     * 那条的判据变宽了 —— 报错点、section 分隔条、消费者一个都没变。
     * （{@code WorkflowDslValidator} 的号域权威段记着「新规则必须取号」，那条纪律针对的是
     * 新增规则；本条属于「老规则多判一件事」，故按原号计。）
     *
     * @param type 控件类型（大小写变体与语义别名都会被归一）
     * @return 可用返回 {@code null}；否则一句与运行期同口径、且这条路径照做得到的错误说明
     */
    public String checkWidgetType(String type) {
        if (StrUtil.isBlank(type)) {
            return "字段缺少控件类型";
        }
        Map<String, FormDesignApi.WidgetSimpleRespDTO> widgets = loadWidgets();
        String canonical = resolveCanonical(type, lowercaseTypeIndex(widgets));
        FormDesignApi.WidgetSimpleRespDTO widget = widgets.get(canonical);
        // 🔴 用 String.format 而不是 StrUtil.format：后者认 {} 占位符，%s 会原样漏出去
        if (widget == null) {
            return String.format("控件类型 %s 不存在。请从 system prompt 的「控件白名单」章节里挑一个 —— "
                    + "那份清单是完整的，你手上没有可调的工具，别写 {{$.xxx}} 占位、也别凭记忆再编一个名字", type);
        }
        if (!Boolean.TRUE.equals(widget.getSupported())) {
            return String.format("控件类型 %s 已停用，不能使用。请改选 system prompt 的「控件白名单」章节里"
                    + "列出的控件（那份清单只含可用控件）", canonical);
        }
        String gap = FormFieldFactory.checkWidgetAuthorable(widget);
        if (gap != null) {
            // 🔴 出路给的是「改选一个没带标记的」而不是「去查哪个能建」：这条路径上没有工具可调，
            //    而白名单那一段已经把带标记的逐行印出来了，模型手上就有对照物
            return String.format("控件 %s（%s）虽然在白名单里、也确实可用，但%s —— 写了不报错："
                            + "草稿照存、发布照过，运行期那一格是个空控件（点不开的下拉 / 显示不出内容的块），"
                            + "没有任何一处会主动说。请从 system prompt 的「控件白名单」章节里改选一个"
                            + "<b>没带 %s 标记</b>的控件（选择类需求用 %s + options/dictType 就够表达）；"
                            + "用户点名要这一类控件时，把表单其余部分建好，并在最终话术里如实说清"
                            + "哪一格需要人工去「表单设计」里配",
                    canonical, StrUtil.blankToDefault(widget.getLabel(), canonical), gap,
                    FormFieldFactory.UNAUTHORABLE_MARK, FormFieldFactory.optionWidgetCatalog());
        }
        return null;
    }

    /**
     * 这个控件类型（<b>归一后</b>）是不是「靠选项来源渲染」的那三个之一 —— 是的话把规范名一并给出
     *
     * <p>🔴 返回规范名而不是 {@code boolean}，是为了让调用方<b>只探一次契约</b>：
     * 归一本身就要读一遍 {@code getWidgetContracts()}（infra 那侧每次调用都重建整份契约 DTO、没有缓存），
     * 而拿到判定结果的人紧接着几乎一定还要用规范名 —— V29 要把它写进试探用的 rule、
     * 再交给 {@link #validateOptionSource} 那个「已归一」的重载。拆成两个方法就是同一次归一付两遍钱。
     *
     * <p>与 {@code FormFieldFactory#isOptionWidget} 刻意<b>不是同一个方法</b>：那边拿到的是
     * 已经归一过的 rule 值（{@code toRule}/{@code mergeRule} 里都先过了 {@link #canonicalType}），
     * 故不再查一次契约；本方法给的是<b>规划期</b>的消费者（{@code WorkflowDslValidator} V29），
     * 它手上是模型原样写的 {@code widget}，可能是 {@code Select}、也可能是 {@code dropdown} 这类别名，
     * 不归一就会把「其实是选项控件」判成「不是」—— 判反的后果是这条校验在最需要它的写法上不开口。
     *
     * <p>名单本体仍是 {@code FormFieldFactory#OPTION_WIDGETS} 那一份，这里只多一步归一。
     *
     * @param type 控件类型（大小写变体与语义别名都会被归一）
     * @return 是选项类控件 ⟹ 它的契约规范名；不是、或类型为空 ⟹ {@code null}
     */
    public String optionWidgetCanonical(String type) {
        if (StrUtil.isBlank(type)) {
            return null;
        }
        String canonical = canonicalType(type);
        // ⚠️ canonical 到这里必非空（type 非空 ⟹ resolveCanonical 不会返回 null），故不必再防 contains(null)
        return FormFieldFactory.OPTION_WIDGETS.contains(canonical) ? canonical : null;
    }

    /**
     * 字典类型在系统里存不存在
     *
     * <p>🔴 事实取自 {@code DictDataApi#getDictType}（{@code DictDataCommonApi} 那个方法的 javadoc
     * 明写它是「供调用方判断字典是否存在、是否启用、是什么形态」用的），本类<b>不</b>持有任何字典清单：
     * 手抄一份必然漂移，而漂了的表现正是这里要拦的那个静默失效。
     *
     * <h3>为什么必须查，而不能让模型自己写</h3>
     * 模型没有建字典的工具（{@code @AgentTool} 全集里没有任何 dict_* 写工具），
     * 于是它编出来的 {@code dictType} 在库里必然不存在。实测过一批：34 份定义里 7 个 dictType，
     * <b>6 个是编的</b>（{@code meeting_rooms}、{@code inspection_status}、{@code gender_dict}…），
     * 其中 2 个还挂在<b>已发布</b>的定义上。
     *
     * <p>而「编出来的字典」在这条路上的表现是<b>三次静默</b>：草稿存得进、发布……
     * 发布会被 infra 的 {@code FormDesignPublishValidator#validateDictSources} 拦住，
     * 但那已经是<b>用户点完确认、表已经建了、草稿已经存了</b>之后 ——
     * 与 {@code image} 控件、{@code parentMenuName} 那两个事故同一个形状。
     *
     * <h3>🔴 补救建议必须是这条路径照做得到的</h3>
     * 规划期没有工具可调（{@code checkWidgetType} 那条的同款纪律），所以文案给的出路只有两个：
     * 写静态 {@code options}，或者只在用户点名了某个已存在字典时才写 {@code dictType}。
     * 不能建议它「去查一下有哪些字典」—— {@code DictDataCommonApi} 压根没有「列出全部字典类型」的方法。
     *
     * @param dictType 字典类型
     * @return 存在返回 {@code null}；否则一句可直接照做的错误说明
     */
    public String checkDictTypeExists(String dictType) {
        if (StrUtil.isBlank(dictType)) {
            return "字段声明了字典作为选项来源，但 dictType 是空的。"
                    + "写字典就给出字典类型，写固定选项就改用 options";
        }
        if (dictDataApi.getDictType(dictType) == null) {
            return String.format("字典 %s 在系统里不存在。你没有建字典的工具，凭记忆编一个 dictType 的后果"
                    + "不是报错，而是下拉里一个选项都没有。固定选项请直接写 "
                    + "options:[{\"label\":\"选项名\",\"value\":\"存进库的值\"}]；"
                    + "只有用户点名了某个已存在的字典时才写 dictType", dictType);
        }
        return null;
    }

    /**
     * 字典存在，但它有没有「开启」状态的字典数据
     *
     * <p>🔴 这条<b>刻意只是 warning，不是 error</b>，与 {@link #checkDictTypeExists} 分档的理由是
     * 两者的可修复性完全不同：
     * <ul>
     *   <li>字典<b>不存在</b> —— 没有任何后续动作能让它变对，模型必须当场改写；</li>
     *   <li>字典存在但<b>暂时没数据</b> —— 这是个合法状态，管理员随后在【字典管理】里补上就好了，
     *       infra 的发布关（{@code FormDesignPublishValidator#validateDictSources}）也<b>不拦</b>它。
     *       在这里拦就会比设计器更严，出现「AI 说建不了、手工配同一个字典却能发布」的自相矛盾。</li>
     * </ul>
     * 但也不能不说：警告会出现在 Plan 卡片上（{@code AgentValidateResult#warnings} 的用途），
     * 审批的人至少知道「这一格现在点开是空的」。
     *
     * <p>⚠️ 判据是<b>启用状态的数据条数</b>，不是「字典类型是否启用 / 是否懒加载」——
     * 后两条是 infra 发布关的判据（那边读 {@code DictTypeRespDTO} 的 {@code nodeType}/{@code cacheMode}），
     * 这里<b>不重复实现</b>：分组类型的字典天然没有数据，已被本条覆盖；
     * 而停用/懒加载那两种情况用户点的是自己配过的字典，发布关会给出精确报错，
     * 在这里再抄一份数字常量（1-分组 2-字典、1-全量 2-懒加载）就是第三份判据。
     *
     * @param dictType 字典类型（调用方应已确认它存在）
     * @return 有启用数据返回 {@code null}；否则一句给人看的警告说明
     */
    public String checkDictTypeHasEnabledData(String dictType) {
        List<DictDataRespDTO> list = dictDataApi.getDictDataList(dictType);
        boolean anyEnabled = list != null
                && list.stream().anyMatch(item -> CommonStatusEnum.isEnable(item.getStatus()));
        if (anyEnabled) {
            return null;
        }
        return String.format("字典 %s 存在，但一条「开启」状态的字典数据都没有，所以下拉现在是空的。"
                + "若这些选项是固定的，改用 options:[{\"label\":\"…\",\"value\":\"…\"}] 更稳；"
                + "若确实要用字典，需要管理员先在【系统管理 → 字典管理】里把数据补上", dictType);
    }

    /**
     * 先查契约（大小写不敏感）、再查语义别名，都不中则原样返回
     *
     * @param type      模型给出的控件类型（非空）
     * @param typeIndex 契约规范名的小写索引
     */
    private String resolveCanonical(String type, Map<String, String> typeIndex) {
        String lower = type.trim().toLowerCase(Locale.ROOT);
        String canonical = typeIndex.get(lower);
        if (canonical != null) {
            return canonical;
        }
        String aliased = aliasCanonical(type);
        return aliased != null ? aliased : type;
    }

    /**
     * 语义别名的静态查询：{@code date}→{@code datePicker} 之类
     *
     * <p>抽成 {@code static} 是给同样要做控件归一、但<b>不持有本 bean</b> 的类复用
     * （如规划期浅校验 {@code FormSaveDraftParamValidator}），保证别名表只有一份、两处不漂移。
     * 只查别名、不查契约 —— 契约的大小写归一各自用手上的 widget 清单做即可。
     *
     * @param type 模型给出的控件类型
     * @return 命中别名则返回规范名，否则 {@code null}
     */
    public static String aliasCanonical(String type) {
        if (StrUtil.isBlank(type)) {
            return null;
        }
        return WIDGET_TYPE_ALIASES.get(type.trim().toLowerCase(Locale.ROOT));
    }

    private Map<String, String> lowercaseTypeIndex(Map<String, FormDesignApi.WidgetSimpleRespDTO> widgets) {
        Map<String, String> index = new HashMap<>();
        for (String type : widgets.keySet()) {
            index.putIfAbsent(type.toLowerCase(Locale.ROOT), type);
        }
        return index;
    }

    private Map<String, FormDesignApi.WidgetSimpleRespDTO> loadWidgets() {
        Map<String, FormDesignApi.WidgetSimpleRespDTO> map = new HashMap<>();
        for (FormDesignApi.WidgetSimpleRespDTO widget : formDesignApi.getWidgetContracts()) {
            map.put(widget.getType(), widget);
        }
        return map;
    }

    private JSONObject parseRule(String raw, AgentValidateResult result) {
        try {
            return JSONUtil.parseObj(raw);
        } catch (Exception ex) {
            result.addError("控件定义不是合法的 JSON：%s", StrUtil.maxLength(raw, 80));
            return null;
        }
    }

    // ==================== 单个控件 ====================

    private void validateRule(JSONObject rule,
                              Map<String, FormDesignApi.WidgetSimpleRespDTO> widgets,
                              Map<String, String> typeIndex,
                              List<FormDesignApi.ValidationPatternRespDTO> patterns,
                              Set<String> fields, boolean newForm, Set<String> newFields,
                              AgentValidateResult result) {
        String type = rule.getStr("type");
        String field = rule.getStr("field");

        // 🔴 控件白名单。凭记忆编控件名的后果不是报错，
        //    而是表单能保存、能发布，就是渲染不出来
        if (StrUtil.isBlank(type)) {
            result.addError("有控件缺少 type");
        } else {
            String canonical = resolveCanonical(type, typeIndex);
            if (!canonical.equals(type)) {
                result.addWarning("控件类型 %s 已按契约归一为 %s", type, canonical);
                type = canonical;
            }
            FormDesignApi.WidgetSimpleRespDTO widget = widgets.get(type);
            if (widget == null) {
                result.addError("控件类型 %s 不存在。可用类型请调 widget_contract_get 查询", type);
            } else if (!Boolean.TRUE.equals(widget.getSupported())) {
                result.addError("控件类型 %s 已停用，不能使用", type);
            } else {
                if (Boolean.TRUE.equals(widget.getDeprecated())) {
                    result.addWarning("控件 %s 已废弃，建议换用其它控件", type);
                }
                // 🔴 「在白名单里、也确实可用，而这一格的配置就是缺的」—— 与上面那两条不同的根因，
                //    故不接在 else-if 链上（一个控件可能既已废弃又缺配置，两件事都要说）。
                //    判据不在这里：FormFieldFactory#missingWidgetConfig 从契约现算，
                //    而且判的是【这一条 rule】，不是【这个控件名】——
                //    设计器里配好了 props.dictType 的 DictSelect 不该被冤枉，
                //    而 AI 用字段描述建出来的那一格必然缺（toRule 只写 placeholder/validateRules）。
                validateWidgetConfig(field, type, widget, rule, newForm, newFields, result);
            }
        }

        // 布局类控件（分组标题等）没有 field，跳过字段校验
        if (StrUtil.isBlank(field)) {
            return;
        }

        // 🔴 field 去重，含子表内。重复的后果是后一个覆盖前一个，
        //    表现为「我明明加了两个字段，只存进去一个」
        if (!fields.add(field)) {
            result.addError("字段标识 %s 重复。每个控件的 field 必须唯一（子表内的也算）", field);
        }

        // form-create 内部用下划线开头做保留字，撞上会有诡异行为
        if (field.startsWith("_")) {
            result.addError("字段标识 %s 不能以下划线开头，那与 form-create 的内部保留字冲突", field);
        }

        // 副表字段名形如 yd_<表>_yd_<列>，超长会被数据库截断
        if (field.length() > IDENTIFIER_MAX_LENGTH) {
            result.addError("字段标识 %s 超过 %d 字符", field, IDENTIFIER_MAX_LENGTH);
        }

        if (StrUtil.isBlank(rule.getStr("title"))) {
            result.addWarning("字段 %s 没有标题，界面上会显示成一个没有标签的输入框", field);
        }

        validateFieldValidationRules(field, rule, patterns, result);
        // 🔴 传上面那个 canonical 后的局部 type（不是 rule 上的原样值）：既保证别名照判，
        //    也省掉一次 getWidgetContracts() —— 那个读没有缓存，见 validateOptionSource 的重载
        validateOptionSource(field, type, rule, result, newForm);
    }

    /**
     * 这一格控件<b>缺了契约说「不填就没法正常工作」的配置</b>
     *
     * <h3>🔴 后果与本类其余各条一模一样：不报错的失效</h3>
     * 契约对 {@code required} 的原话是「不填则该控件无法正常工作」，而 infra 的发布关<b>不看</b>它
     * （那边只查「配了字典的，字典还在不在」）。于是 {@code DictSelect} 少了 {@code dictType}、
     * {@code cascader} 少了 {@code props.options}，一路存草稿、发布、上线都不吭声，
     * 用户点进去看到的是一个点不开的下拉 / 一个显示不出内容的块。
     * 判据本体在 {@code FormFieldFactory#missingWidgetConfig}（从契约现算，本类不抄名单）。
     *
     * <h3>⚠️ 为什么判 rule 而不是判控件名</h3>
     * 判控件名的话，设计器里<b>配好了</b>那一格的 {@code DictSelect} 也会被点名 ——
     * 而 {@code form_update} 收的是合并后的整份 formFields，「把备注改成必填」这种请求
     * 就会被一个跟它无关、且其实是好的控件刷一条黄标。判 rule 就没有这个假警报，
     * 顺带还把「导入的 / 手工改坏的」存量也一起如实报出来。
     *
     * <h3>分档：本次新写的格子 error，先于本次就存在的格子 warning</h3>
     * 与 {@link #validateOptionSource} 的 {@code requireOptionSource} 同一条纪律，
     * 只是这里多认一层「本次 patch 新增的 field」（见 {@link #validate(List, String, Collection)}）：
     * 用户说「加一个字典选择器字段」时，那一格是<b>本次自己造的</b>，没有理由只提醒一句就存进去。
     *
     * <p>🔴 布局/展示类控件也照判（它们没有 {@code field}，故本方法必须在
     * {@link #validateRule} 的「没有 field 就 return」<b>之前</b>调）：{@code fcTitle} 少了
     * {@code title} 就是一个空标题块，与有没有字段无关。
     */
    private void validateWidgetConfig(String field, String type,
                                      FormDesignApi.WidgetSimpleRespDTO widget, JSONObject rule,
                                      boolean newForm, Set<String> newFields,
                                      AgentValidateResult result) {
        List<String> missing = FormFieldFactory.missingWidgetConfig(widget, rule);
        if (missing.isEmpty()) {
            return;
        }
        String where = StrUtil.isBlank(field) ? "" : "字段 " + field + " 的";
        // 🔴 出路刻意不点名任何具体控件：这份文案服务的是<b>持有 rule</b> 的执行期路径，
        //    那里 widget_contract_get 真的调得到，而判据「props 里没有 required=true 的那种」
        //    与本条用的是同一份契约 ⟹ 照它挑出来的控件必然过得了这一关。
        //    抄一份控件名进来就是第二份会漂的名单（漂移的表现见 NOT_CREATABLE_MARK 那段教训）
        String message = String.format("%s控件 %s（%s）缺了契约说「不填该控件就无法正常工作」的配置：%s。"
                        + "存得进、发布得过，运行期那一格是个空控件（点不开的下拉 / 显示不出内容的块），"
                        + "没有任何一处会主动说。请改选一个<b>不需要额外配置</b>的控件"
                        + "（widget_contract_get 的 props 里没有 required=true 的那种；"
                        + "选择类需求用 %s + options/dictType 就够表达），"
                        + "或先建好表单、再到「表单设计」里手工配这一格",
                where, type, StrUtil.blankToDefault(widget.getLabel(), type),
                String.join("、", missing), FormFieldFactory.optionWidgetCatalog());
        if (newForm || (StrUtil.isNotBlank(field) && newFields.contains(field))) {
            result.addError(message);
        } else {
            result.addWarning(message + "。这一格不是本次改的（本次只是路过它），故只如实报出来、不拦本次修改");
        }
    }

    // ==================== 选项来源 ====================

    /**
     * 校验选项类控件（{@code FormFieldFactory#OPTION_WIDGETS}）的<b>选项来源</b>配得对不对
     *
     * <h3>🔴 这一条守的就是「AI 建的下拉是空的」那个缺陷</h3>
     * select/radio/checkbox 没有选项来源时，表单<b>存得进、发布也过得去</b>（infra 的发布关只看
     * 「配了字典的，字典还在不在」，压根不看「有没有配」），运行期渲染出来就是一个点不开的下拉。
     * 用户拿到的是一句「表单建好了」，加上一个选不了任何值的控件 —— 与
     * {@link #validateFieldValidationRules} 那条同一类：不报错的失效。
     *
     * <h3>判据只有四条，每条都不是这里发明的</h3>
     * <ol>
     *   <li><b>字典在不在</b> —— {@link #checkDictTypeExists}，事实取自 {@code DictDataApi}。</li>
     *   <li><b>字典有没有启用数据</b> —— {@link #checkDictTypeHasEnabledData}，只出 warning（理由见那里）。</li>
     *   <li><b>静态选项的形状</b> —— {@code [{label, value}]}，取自契约 JSON 里 select 的
     *       {@code options} PropSpec（「每项 {label, value}」）与库里的真实表单样本。
     *       写成 {@code ["a","b"]} 或 {@code [{text,name}]} 时前端 {@code optionSource.ts}
     *       读不到 {@code label}/{@code value}，下拉里是一排空白项 —— 同样不报错。</li>
     *   <li><b>三种来源只能用一种</b> —— 前端 {@code detectOptionSourceType} 的优先级是
     *       {@code dataSource.interfaceCode} → {@code dictSource.dictType} → 静态。
     *       两个都写的话<b>字典赢</b>，用户以为改成的静态选项静默不生效。</li>
     * </ol>
     *
     * <h3>⚠️ {@code requireOptionSource} 为什么分新建/修改两档</h3>
     * 「一个来源都没有」在<b>新建</b>时是 error（这一格是刚写的，没有任何理由空着），
     * 在<b>修改</b>时只能是 warning：存量表单里真有这种控件（实测 3 个字段，其中 2 个已发布），
     * 而 {@code validate} 收的是<b>合并后的整份 formFields</b> —— 按 error 判的话，
     * 「把备注改成必填」这种毫不相干的请求会被一个<b>先于本次请求就存在</b>的缺陷整个挡掉。
     * 🔴 字典存不存在那两条<b>不分档</b>：编出来的 dictType 永远不可能靠后续动作变对，
     * 而且 infra 的发布关本来就会拒它（那两份已发布的定义今天就已经重新发布不了），
     * 在这里放过只是把同一句报错推迟到用户点完确认之后。
     *
     * <p>⚠️ 只看<b>顶层</b>控件，与本类其余各条同口径（AI 这条路造不出子表内层控件）。
     *
     * @param field             字段标识，只用于报错点名
     * @param rule              form-create 规则（新建时是 {@code FormFieldFactory#toRule} 的产物，
     *                          修改时是合并后的既有 rule）
     * @param requireOptionSource 没有任何选项来源时判 error 还是 warning
     */
    public void validateOptionSource(String field, JSONObject rule,
                                     AgentValidateResult result, boolean requireOptionSource) {
        if (rule == null) {
            return;
        }
        // 🔴 这里必须归一（而不是拿 rule 上的原样值查 OPTION_WIDGETS）：本方法收的 rule 有两个来源，
        //    新建那条已经归一过，而 form_update 那条收的是<b>库里存着的原样 rule</b> ——
        //    那里真会出现 Select / dropdown 这类别名（WIDGET_TYPE_ALIASES 存在的理由就是它）。
        //    拿原样值查名单会把「其实是选项控件」判成「不是」，于是这条校验在最需要它的存量上不开口。
        //    归一后仍认不出的类型交给上面控件白名单那条报错，这里沉默（一个根因只说一遍）。
        validateOptionSource(field, canonicalType(rule.getStr("type")), rule, result, requireOptionSource);
    }

    /**
     * 同上，但控件类型由<b>调用方</b>先归一好
     *
     * <p>🔴 存在的唯一理由是<b>契约探测的次数</b>：归一一次就读一次 {@code getWidgetContracts()}，
     * 而 infra 那侧每次调用都<b>重建整份</b>契约 DTO（没有缓存）。已经归过一的两个调用方 ——
     * {@link #validateRule}（控件白名单那条刚算出 canonical）与
     * {@code WorkflowDslValidator#validateSaveDraftOptionSources}（V29 先用 {@link #optionWidgetCanonical}
     * 判过是不是选项控件，那一下内部就归过一了）—— 再走上面那个入口，等于每个字段多打一遍 infra；
     * V29 那边还另有「契约读不到就整趟停手并认领跳过范围」的纪律，多一次探测就多一个中止点。
     *
     * <p>⚠️ 传进来的必须<b>确实是</b>规范名（{@link #canonicalType} 的产物）。传原样的
     * {@code Select} / {@code dropdown} 会被 {@code OPTION_WIDGETS.contains} 判成「不是选项控件」
     * 而让整条沉默 —— 与本方法存在的目的正好相反。
     */
    public void validateOptionSource(String field, String canonicalType, JSONObject rule,
                                     AgentValidateResult result, boolean requireOptionSource) {
        // 🔴 先判 blank 再 contains：OPTION_WIDGETS 是 Set.of(...)，contains(null) 直接 NPE，
        //    而「rule 上没有 type」正是上面那条「有控件缺少 type」在报的形状，不该在这儿炸掉整个校验
        if (rule == null || StrUtil.isBlank(canonicalType)
                || !FormFieldFactory.OPTION_WIDGETS.contains(canonicalType)) {
            return;
        }
        Object rawSource = rule.get("props") instanceof JSONObject props
                ? props.get(FormFieldFactory.DICT_SOURCE_KEY) : null;
        String dictType = null;
        if (rawSource != null) {
            if (!(rawSource instanceof JSONObject source)) {
                result.addError("字段 %s 的 props.%s 不是对象（收到：%s）。它必须形如 "
                                + "{\"dictType\":\"字典类型\",\"valueType\":\"str\"}；写成别的形状时前端读不到，"
                                + "下拉是空的，而且不报错",
                        field, FormFieldFactory.DICT_SOURCE_KEY,
                        StrUtil.maxLength(String.valueOf(rawSource), 60));
                return;
            }
            dictType = source.getStr(FormFieldFactory.DICT_TYPE_KEY);
            if (StrUtil.isBlank(dictType)) {
                // 🔴 库里真会有这种形状（手工改过 / 从别处导入的）：dictSource 在、dictType 是空的。
                //    不在这儿单独开口，它就会掉进下面「没有任何选项来源」那句里 ——
                //    补救办法确实一样，但「字典写了一半」与「什么都没写」是两件事，
                //    而报错点不准的代价是模型/人照着改完仍不明白自己漏了哪一格。
                result.addError("字段 %s 的%s", field, checkDictTypeExists(dictType));
                return;
            }
        }

        Object rawOptions = rule.get(FormFieldFactory.OPTIONS_KEY);
        boolean hasOptions = false;
        if (rawOptions != null) {
            if (!(rawOptions instanceof JSONArray options)) {
                result.addError("字段 %s 的 %s 不是数组（收到：%s）。它必须形如 "
                                + "[{\"label\":\"选项名\",\"value\":\"存进库的值\"}]",
                        field, FormFieldFactory.OPTIONS_KEY,
                        StrUtil.maxLength(String.valueOf(rawOptions), 60));
                return;
            }
            hasOptions = !options.isEmpty();
            if (hasOptions) {
                validateStaticOptions(field, options, result);
            }
        }

        if (dictType != null) {
            if (hasOptions) {
                result.addError("字段 %s 同时给了字典（props.%s）与静态选项（%s）—— 三种选项来源只能用一种。"
                                + "前端的优先级是「接口 → 字典 → 静态」，两个都写时字典赢，"
                                + "你以为改成的静态选项会静默不生效。请只留一个",
                        field, FormFieldFactory.DICT_SOURCE_KEY, FormFieldFactory.OPTIONS_KEY);
                return;
            }
            try {
                String problem = checkDictTypeExists(dictType);
                if (problem != null) {
                    result.addError("字段 %s 的%s", field, problem);
                    return;
                }
                String empty = checkDictTypeHasEnabledData(dictType);
                if (empty != null) {
                    result.addWarning("字段 %s 的%s", field, empty);
                }
            } catch (Exception ex) {
                // 与 loadValidationPatterns 同一条纪律：探测这个动作本身故障时不拦，
                // 绝不能因为查字典失败让整张建/改表单流程崩掉。发布关仍会拦
                log.warn("[validateOptionSource][读取字典({})失败，跳过字段({})的字典可用性校验：{}]",
                        dictType, field, ex.getMessage());
            }
            return;
        }
        if (!hasOptions) {
            String problem = String.format("是 %s 控件，却没有任何选项来源 —— 运行期它会渲染成一个点开是空的下拉，"
                            + "而存草稿、发布都不会报错。固定选项请写 options:[{\"label\":\"…\",\"value\":\"…\"}]，"
                            + "用字典请写 dictType（必须是系统里已存在的字典，你没有建字典的工具），"
                            + "或者改用一个不需要选项的控件（如 input）",
                    rule.getStr("type"));
            if (requireOptionSource) {
                result.addError("字段 %s %s", field, problem);
            } else {
                result.addWarning("字段 %s %s（这是本次修改之前就有的形状，故只提醒不阻断）", field, problem);
            }
        }
    }

    /**
     * 静态选项的每一项都要是 {@code {label, value}}
     *
     * <p>{@code value} 只要求「有」而不要求是字符串：前端 {@code castValue} 会把它拼成字符串，
     * 数字值（{@code {"label":"启用","value":0}}）是合法的。{@code label} 则必须非空 ——
     * 空标签的选项在下拉里是一行空白，用户看不出那是个能选的东西。
     */
    private void validateStaticOptions(String field, JSONArray options, AgentValidateResult result) {
        for (int i = 0; i < options.size(); i++) {
            Object item = options.get(i);
            if (!(item instanceof JSONObject option)) {
                result.addError("字段 %s 的 %s 第 %d 项不是对象（收到：%s）。它必须形如 "
                                + "{\"label\":\"选项名\",\"value\":\"存进库的值\"}",
                        field, FormFieldFactory.OPTIONS_KEY, i + 1,
                        StrUtil.maxLength(String.valueOf(item), 60));
                continue;
            }
            if (StrUtil.isBlank(option.getStr("label"))) {
                result.addError("字段 %s 的 %s 第 %d 项没有 label —— 下拉里会是一行空白，"
                                + "用户看不出那是个能选的选项", field, FormFieldFactory.OPTIONS_KEY, i + 1);
            }
            if (option.get("value") == null) {
                result.addError("字段 %s 的 %s 第 %d 项没有 value —— 选了它也存不进任何值",
                        field, FormFieldFactory.OPTIONS_KEY, i + 1);
            }
        }
    }

    // ==================== 字段级格式校验 ====================

    /**
     * 校验控件 {@code props.validateRules}（身份证 / 手机号 / 邮箱 …）配得对不对
     *
     * <h3>🔴 为什么必须在【存草稿】这一步就拦，而不是等发布体检</h3>
     * 运行期对配错的校验规则一律「放过 + 记 warn」（infra 的 {@code FormValidationGuard#check}，
     * 那是有意的可用性取舍：一次正则库调整不该让存量表单集体提交不了），
     * 真拦的是发布关的 {@code FormDesignPublishValidator#validateValidationRules}。
     * 于是「键名编错」在这条路上的表现是：草稿<b>照样存进去</b>、AI 告诉用户「表单建好了」，
     * 而任何输入都能存 —— 用户要的那条格式约束一个字都没生效，且没有任何一处会主动说。
     * 在保存这一步就拒，模型才会在<b>还没造出一张坏草稿</b>时改对。
     *
     * <h3>判据只有两条，都不是这里发明的</h3>
     * <ol>
     *   <li><b>「有没有 patternKey」决定它想不想引用内置库</b> —— 抄自 infra
     *       {@code FieldValidationRule#resolveType} 的判据（那个类的注释写明：配了 patternKey
     *       显然是想引用内置库，故 type 缺省或认不出时按它推断）。
     *       🔴 本类<b>拿不到</b>那个类（它在 infra 的 framework 包，跨模块只许碰 api），
     *       所以 {@link #referencesBuiltinLibrary} 是它的一份<b>镜像</b>：那边改了推断规则这边要跟着改。
     *       镜像刻意只朝「少拦」那侧偏 —— 明写 {@code REGEX} 又顺手带了个 patternKey 的写法，
     *       运行期确实忽略那个 key，拦了就是误报，而误报的代价是让模型去改一个没病的表单。</li>
     *   <li><b>键名在不在库里</b> —— 事实取自 {@link FormDesignApi#getValidationPatternList()}，
     *       与运行期、发布期读的是同一份 {@code validation-patterns.json}。
     *       🔴 本类<b>不</b>持有任何键名清单：手抄一份必然漂移，而漂了的表现正是这里要拦的那个静默失效。</li>
     * </ol>
     *
     * <p>⚠️ 只看<b>顶层</b>控件，不递归进 {@code props.rule}（子表内层）与 {@code children}（布局）。
     * 与本类其余各条同口径（控件白名单那条也不递归），也因为 {@code form_save_draft} 的
     * {@code fields[]} 里压根没有能放子字段的键 —— AI 这条路造不出带内层校验的表单。
     * 不递归是<b>漏报</b>方向，而漏报的兜底是发布体检那一关，它照旧会拦。
     */
    private void validateFieldValidationRules(String field, JSONObject rule,
                                              List<FormDesignApi.ValidationPatternRespDTO> patterns,
                                              AgentValidateResult result) {
        if (!(rule.get("props") instanceof JSONObject props)
                || !props.containsKey(FormFieldFactory.VALIDATE_RULES_KEY)) {
            return;
        }
        Object raw = props.get(FormFieldFactory.VALIDATE_RULES_KEY);
        if (!(raw instanceof JSONArray entries) || entries.isEmpty()) {
            result.addError("字段 %s 的 props.%s 不是非空数组（收到：%s）。它必须形如 "
                            + "[{\"type\":\"BUILTIN\",\"patternKey\":\"ID_CARD\"}]；写成别的形状时"
                            + "前后端都读不到，用户要的格式校验一个字都不生效，而且不报错",
                    field, FormFieldFactory.VALIDATE_RULES_KEY, StrUtil.maxLength(String.valueOf(raw), 60));
            return;
        }
        for (Object item : entries) {
            if (!(item instanceof JSONObject entry)) {
                result.addError("字段 %s 的 props.%s 里有一项不是对象（收到：%s）",
                        field, FormFieldFactory.VALIDATE_RULES_KEY, StrUtil.maxLength(String.valueOf(item), 60));
                continue;
            }
            String patternKey = entry.getStr("patternKey");
            if (StrUtil.isBlank(patternKey) && StrUtil.isBlank(entry.getStr("pattern"))) {
                result.addError("字段 %s 有一条格式校验既没写 patternKey 也没写 pattern —— "
                                + "运行期会把它当「配置不完整」跳过（只记一条 warn），用户要的校验静默失效。"
                                + "引用内置正则写 patternKey（可用值：%s），自定义正则写 pattern",
                        field, patternCatalog(patterns));
                continue;
            }
            if (StrUtil.isBlank(patternKey) || !referencesBuiltinLibrary(entry.getStr("type"))) {
                continue;
            }
            if (patterns.isEmpty()) {
                // 正则库整个加载不出来时 listAll() 就是空的（infra 那边刻意不阻止应用启动）。
                // 此时分不清「库坏了」与「键名编错了」，一律放过 —— 发布体检那一关仍会拦
                log.warn("[validateFieldValidationRules][内置正则库为空，跳过字段({})的键名({})存在性校验]",
                        field, patternKey);
                continue;
            }
            boolean known = patterns.stream()
                    .anyMatch(pattern -> patternKey.equalsIgnoreCase(pattern.getKey()));
            if (!known) {
                result.addError("字段 %s 引用了不存在的内置正则 %s。可用的键名是：%s。"
                                + "运行期对认不出的键名是「放过 + 记日志」，所以不在这里拦的话，"
                                + "表单会建成功、用户要的校验静默失效",
                        field, patternKey, patternCatalog(patterns));
            }
        }
    }

    /**
     * 这条规则是不是想引用内置正则库
     *
     * <p>🔴 判据是「<b>没有</b>明写 REGEX」而不是「明写 BUILTIN」，为的是与 infra
     * {@code FieldValidationRule#resolveType} 逐条对上：那边 type 缺省、或写了个认不出的值，
     * 都会落到「有 patternKey 就当 BUILTIN」。只认字面 BUILTIN 的话，
     * 少写一个 type 字段（那个类明确说了不该让规则因此失效）就会被这里误判成「自定义正则」而放过。
     */
    private static boolean referencesBuiltinLibrary(String type) {
        return !"REGEX".equalsIgnoreCase(type);
    }

    /**
     * 内置正则目录的<b>教学文本</b>：「{@code KEY（中文名）、…}」
     *
     * <h3>🔴 提示词与报错文案必须共用这一份渲染</h3>
     * 消费者有两个：{@code FormSaveDraftTool#paramsSchema}（教模型有哪些键名可用）
     * 与本类 {@link #validateFieldValidationRules} 的报错（告诉它刚才那个键名不在哪些里）。
     * 两处各渲染一份的话，漂移的表现是<b>最坏的那种</b>：提示词印的清单里有、判据那份里没有，
     * 于是模型照着提示词写、被判据拒，然后按报错里另一份清单改 —— 每轮一次真实计费，
     * 而它在两堵墙之间来回撞，看不出到底哪份是真的。
     *
     * <p>事实本身仍只有一处：{@link FormDesignApi#getValidationPatternList()}，
     * 也就是 infra 那份 {@code validation-patterns.json}。本方法只负责排版。
     */
    public String validateRulesCatalog() {
        return patternCatalog(loadValidationPatterns());
    }

    /**
     * 把内置正则目录印成「KEY（中文名）」，用来给出路
     *
     * <p>🔴 必须带上中文名：模型手上是用户的说法（「身份证」「统一社会信用代码」），
     * 只印 {@code ID_CARD}/{@code USCC} 的话它还得再猜一次映射 ——
     * 而这条错误的整个成因就是猜。目录本身取自 {@link FormDesignApi#getValidationPatternList()}，
     * 不在这里手敲。
     */
    private static String patternCatalog(List<FormDesignApi.ValidationPatternRespDTO> patterns) {
        if (CollUtil.isEmpty(patterns)) {
            return "（内置正则库当前读不到，请改用 pattern 写自定义正则）";
        }
        return patterns.stream()
                .map(pattern -> StrUtil.isBlank(pattern.getLabel())
                        ? pattern.getKey()
                        : pattern.getKey() + "（" + pattern.getLabel() + "）")
                .collect(Collectors.joining("、"));
    }

    /**
     * 读内置正则目录。fail-soft：读不到就返回空列表，
     * 让 {@link #validateFieldValidationRules} 跳过键名存在性那一条（理由见那里的注释），
     * 绝不因为这次查询失败而让整张建/改表单流程崩掉 —— 与 {@code loadWidgets} 那条不同，
     * 控件白名单读不到时后面每条校验都无从谈起，而这里只少一条。
     */
    private List<FormDesignApi.ValidationPatternRespDTO> loadValidationPatterns() {
        try {
            List<FormDesignApi.ValidationPatternRespDTO> patterns = formDesignApi.getValidationPatternList();
            return patterns == null ? List.of() : patterns;
        } catch (Exception ex) {
            log.warn("[loadValidationPatterns][读取内置正则目录失败，本次跳过键名存在性校验：{}]", ex.getMessage());
            return List.of();
        }
    }

    // ==================== 字段映射 ====================

    /**
     * 校验 fieldMapping
     *
     * <h3>为什么「每个字段都要有映射」是硬规则</h3>
     * 没映射的字段在表单上显示得好好的，用户填了、点了提交、提示成功 ——
     * 但那个值<b>根本没有落库</b>。这是「配了但不生效」最典型的一种，
     * 而且用户往往过很久才发现。
     */
    private void validateFieldMapping(String tableBinding, Set<String> fields,
                                      AgentValidateResult result) {
        if (StrUtil.isBlank(tableBinding)) {
            result.addError("缺少 tableBinding（表绑定），表单不知道数据往哪张表存");
            return;
        }
        JSONObject binding;
        try {
            binding = JSONUtil.parseObj(tableBinding);
        } catch (Exception ex) {
            result.addError("tableBinding 不是合法的 JSON：%s", ex.getMessage());
            return;
        }

        if (StrUtil.isBlank(binding.getStr("mainTable"))) {
            result.addError("tableBinding 缺少 mainTable（主表名）");
        }

        JSONObject fieldMapping = binding.getJSONObject("fieldMapping");
        if (fieldMapping == null || fieldMapping.isEmpty()) {
            result.addError("tableBinding 缺少 fieldMapping。没有映射的字段，"
                    + "用户填了、提交也成功，但值根本不会落库");
            return;
        }

        for (String field : fields) {
            JSONObject mapping = fieldMapping.getJSONObject(field);
            if (mapping == null) {
                result.addError("字段 %s 没有 fieldMapping。它在界面上能填，"
                        + "但提交后不会落库", field);
                continue;
            }
            validateSingleMapping(field, mapping, result);
        }

        // 反向：映射里有、控件里没有的字段
        for (String key : fieldMapping.keySet()) {
            if (!fields.contains(key)) {
                result.addWarning("fieldMapping 里的 %s 没有对应的控件，是多余的", key);
            }
        }
    }

    private void validateSingleMapping(String field, JSONObject mapping,
                                       AgentValidateResult result) {
        String column = mapping.getStr("column");
        if (StrUtil.isBlank(column)) {
            result.addError("字段 %s 的 fieldMapping 缺少 column（物理列名）", field);
            return;
        }
        // jdbcType 缺失会让运行时去反射猜类型，那是参数绑定出错的源头
        if (StrUtil.isBlank(mapping.getStr("jdbcType"))) {
            result.addError("字段 %s 的 fieldMapping 缺少 jdbcType。"
                    + "不显式声明的话运行时要靠反射猜类型，是绑定出错的源头", field);
        }

        String lower = column.trim().toLowerCase(Locale.ROOT);

        // 🔴 这条必须硬拦：tenant_id 被表单字段覆盖 = 跨租户写入
        if (BACKEND_MANAGED_COLUMNS.contains(lower)) {
            result.addError("字段 %s 映射到了 %s —— 这是后端按登录上下文维护的列，"
                    + "被表单值覆盖会造成跨租户写入，绝对禁止", field, column);
            return;
        }

        // 这些映射了不会出安全问题，但会让「创建人」变成用户能随便改的输入框
        if (AUTO_FILL_COLUMNS.contains(lower)) {
            result.addWarning("字段 %s 映射到了自动维护列 %s，"
                    + "平台会自动填这一列，做成表单字段意义不大", field, column);
        }
    }

}
