package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.FormClearGuard;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.FormSchemaValidator;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignSaveReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：修改表单设计
 *
 * <h3>🔴 这个工具的全部设计都围绕一件事：别把用户的配置弄丢</h3>
 * {@code updateFormDesign} <b>不是增量更新</b> —— 没传的 8 个字段会被<b>静默清空</b>
 *（联动规则、校验规则、自定义按钮、页签、打印模板、前端脚本、列表设计、备注）。
 * 后端对此<b>零兜底</b>：没有 {@code riskConfirmed}、没有有损变更校验，直接 set null，
 * 接口返回成功。用户重新打开表单才发现配了半天的东西没了，
 * 而那时也没有草稿期快照可回滚。
 *
 * <p>所以工具接受的是<b>「要改哪几项」</b>（patch），而不是完整 VO：
 * <ol>
 *   <li>强制先取回全量</li>
 *   <li>把 patch 应用上去</li>
 *   <li>整体回写</li>
 * </ol>
 * 让模型直接提交完整 VO 看着更灵活，实际是把「记得带上所有字段」这件事
 * 交给了一个<b>并不可靠</b>的执行者 —— 而漏一次的代价是用户的配置没了。
 *
 * <h3>⚠️ 改完还要重新发布</h3>
 * 更新后状态变成「已发布有改动」，不 publish 用户看到的仍是旧的。
 * 返回值里会明确说这件事 —— 这本身就是个「配了但没生效」的入口。
 *
 * <h3>🔴 {@code formFields} 是这套「按格覆盖」里唯一的例外</h3>
 * 别的格子（{@code name}、{@code remark}、{@code printTemplate}…）本身就是「一整份配置」，
 * 覆盖即意图。但 {@code formFields} 是一个<b>数组</b>，数组的覆盖语义是「整份换掉」，
 * 而模型想要的一直是「只动其中一个字段」。
 *
 * <p>实测（台账 D4-31，流程 def #467 / #570）：模型给的是<b>字段描述</b>
 * {@code [{"field":"competitor_price","widget":"inputNumber","title":"竞对报价","searchable":true}]}
 * —— 与 {@code form_save_draft} 的 {@code fields} 同形，而不是 form-create 的 rule。
 * 老代码按键覆盖之后：
 * <ol>
 *   <li>原有 2 个控件<b>全没了</b>，只剩这 1 个（数据丢失，而接口返回 {@code code=0}「表单已修改」）</li>
 *   <li>存进去的是<b>描述符</b>而不是 rule —— 没有 {@code type}、没有 {@code _fc_id}，表单渲染不出来</li>
 *   <li>{@code listConf.searchItems} <b>一动不动</b> —— {@code searchable:true} 被整个忽略，
 *       用户要的查询条件没长出来</li>
 * </ol>
 * 三条都不报错。所以现在 {@code formFields} 走 {@link #mergeFormFields}：按 {@code field} 合并、
 * 描述符就地归一成真 rule、并按 {@code searchable} 同步派生查询项与落库映射。
 * 删除字段必须显式写 {@code {"field":"x","remove":true}} —— 与本类
 * {@link #PARAM_CLEAR_FIELDS} 同一条纪律：<b>「没提」与「想删」在数据上必须能区分</b>，
 * 否则合并语义会把删除能力一起吞掉（那相对老行为是倒退）。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = FormUpdateTool.NAME,
        displayName = "修改表单",
        groupName = "表单设计",
        description = "修改已有表单。只需给出要改的项（patch），工具会自动取回全量配置再回写 —— "
                + "绝不要自己拼完整的表单定义，漏传任何一项都会让用户配的联动规则、校验规则、"
                + "自定义按钮、打印模板被静默清空。改控件结构（formFields）时按 field 合并，"
                + "只写要动的那几个字段；要加列表查询条件就给该字段传 searchable:true。"
                + "⚠️ 改完必须重新发布才生效",
        permission = "infra:form-design:update",
        risk = RiskLevel.MEDIUM)
@Slf4j
public class FormUpdateTool implements AgentToolFunction {

    /**
     * 工具键
     *
     * <p>🔴 上面注解的 {@code name} 与变更清单抽取器的 {@code toolId()} 都必须引用它。
     * 三处各写一遍字面量时，改名不会报错 —— 只会让清单静默退回兜底解读。
     */
    public static final String NAME = "form_update";

    /** 参数名：表单编号 */
    public static final String PARAM_ID = "id";

    /**
     * 乐观锁版本号在 DTO 上的字段名
     *
     * <p>🔴 它<b>不是</b>一个可 patch 的项，见 {@link #NON_PATCHABLE_FIELDS}。
     * 工具自己从 {@code getFormDesignById} 取回来、原样带回 {@code updateFormDesign}，
     * 模型全程碰不到它。
     */
    private static final String FIELD_VERSION = "version";

    /** 参数名：要改的项 */
    public static final String PARAM_PATCH = "patch";

    /**
     * 参数名：明确要清空哪些配置项
     *
     * 用户确实想删掉打印模板时走这里，而不是靠「不传 printTemplate」——
     * 后者与「忘了传」在数据上<b>完全无法区分</b>，
     * 而这两件事一个是意图、一个是事故。
     */
    public static final String PARAM_CLEAR_FIELDS = "clearFields";

    /**
     * 参数名（patch 内的键）：表单控件结构
     *
     * <p>🔴 唯一<b>不走</b>「按键整份覆盖」的一格，见 {@link #mergeFormFields}。
     */
    public static final String KEY_FORM_FIELDS = "formFields";

    /** 参数名（patch 内的键）：列表设计 */
    public static final String KEY_LIST_CONF = "listConf";

    /** 参数名（patch 内的键）：表绑定 */
    public static final String KEY_TABLE_BINDING = "tableBinding";

    /**
     * 合并单个控件时<b>不</b>原样透传的键
     *
     * <p>分三类，各有各的理由：
     * <ul>
     *   <li>{@code _fc_id} / {@code name} —— form-create 靠它们认控件。跟着 patch 换掉
     *       等于让 form-create 把控件当成新的重建，用户在这一格上配过的东西全丢；
     *       而模型编出来的值还会与既有的撞号，撞号的表现是<b>两个控件互相覆盖、界面上少一个</b>。</li>
     *   <li>{@code field} —— 它是合并的<b>主键</b>，让它被改就等于「删一个再加一个」，
     *       而那条路上没有任何一处会去清旧的查询项与落库映射。</li>
     *   <li>{@code widget} / {@code column} / {@code jdbcType} / {@code required} / {@code searchable}
     *       / {@code dictType} / {@code operator} / {@code placeholder} / {@code validateRules}
     *       / {@code options} / {@code remove}
     *       —— 这些是<b>字段描述</b>的词，不是 rule 的词。原样透传的话 rule 里会多出
     *       {@code widget:"input"} 这种 form-create 不认识的键，而它认的是 {@code type}。
     *       它们各自都有专门的落点（{@code widget}→{@code type}、{@code searchable}→查询项、
     *       {@code column}/{@code jdbcType}→落库映射、{@code validateRules}→{@code props} 里…）。
     *       🔴 {@code validateRules} 尤其不能透传：它的落点是 {@code props.validateRules}，
     *       透传会把它挂到 rule 顶层 —— 前后端都只读 props 里那份，
     *       于是「给已有字段加个手机号校验」保存成功、一个字都不生效。</li>
     * </ul>
     *
     * <p>⚠️ {@code options} 与 {@code dictType} 在<b>选项类控件</b>上恰好与 rule 同名/近名，
     * 看着像「透传也没事」，其实不然：静态选项在 rule 顶层、字典在 {@code props.dictSource}，
     * 两个来源还互斥（切到静态必须删掉字典，否则运行期仍按字典取、界面一片空白）。
     * 这套落点与互斥规则由 {@code FormFieldFactory#applyOptionSource} 一处负责，
     * 本工具只在 {@link #mergeRule} 末尾调它 —— 与新建那条路共用同一份。
     */
    private static final Set<String> NON_PASSTHROUGH_KEYS = Set.of(
            "_fc_id", "name", "_fc_drag_tag", "type", "field",
            "widget", "column", "jdbcType", "required", "$required",
            "searchable", FormFieldFactory.DICT_TYPE_KEY, "operator", "placeholder",
            FormFieldFactory.VALIDATE_RULES_KEY, FormFieldFactory.OPTIONS_KEY, "remove");

    /**
     * 反射派生时要点名排除的 DTO 字段
     *
     * <p>🔴 这份名单与 {@link #copy} 是一对纪律：{@code copy} 逐字段拷贝时，
     * 名单里的字段<b>照样要拷</b>（原样带过去），但<b>不许从 patch 里取</b>。
     * 漏掉名单里的一格 ⟹ 模型可以自己设它；漏掉 {@code copy} 里的一格 ⟹ 改动被静默丢掉。
     * 两种漏法都不报错，所以两处各留一句注释指回对方。
     *
     * <p>🔴 <b>必须声明在 {@link #PATCHABLE_FIELDS} 之前</b> —— 静态字段按<b>声明顺序</b>初始化，
     * 而 {@code PATCHABLE_FIELDS} 的初值来自 {@link #patchableFields()}，那个方法要读这份名单。
     * 顺序反了的话，读到的还是 null，{@code NON_PATCHABLE_FIELDS.contains(name)} 当场 NPE，
     * 整个类<b>加载不起来</b>（{@code ExceptionInInitializerError}）⟹ 工具注册表建不出来
     * ⟹ <b>后端启动失败</b>。
     *
     * <p>⚠️ 这个坑踩过一次（B4-4 把 {@code version} 加进名单时）：改动前那行判据写的是
     * {@code PARAM_ID.equals(name)}，{@code PARAM_ID} 是编译期常量、被 javac 内联，
     * 不依赖任何静态初始化，所以顺序无所谓；换成读 {@code Set} 之后就依赖了。
     * 单测能抓到（{@code @InjectMocks} 一实例化就触发 clinit），但<b>编译抓不到</b> ——
     * {@code mvn install -DskipTests} 全绿、jar 照样装进本地仓，直到重启后端才炸。
     */
    private static final Set<String> NON_PATCHABLE_FIELDS = Set.of(PARAM_ID, FIELD_VERSION);

    /**
     * patch 里允许出现的键 —— <b>唯一权威</b>，由 DTO 自己的字段反射派生。
     * {@link #paramsSchema()} 与「不是可修改的表单字段」那句报错都从这一份出，
     * 所以文档、报错、判据三者<b>不可能</b>各说一套。
     *
     * <h3>🔴 为什么不能拿 {@code JSONUtil.parseObj(target).containsKey(key)} 当判据（原来的写法）</h3>
     * Hutool 的 {@code parseObj(bean)} 缺省 {@code ignoreNullValue=true}：
     * <b>值为 null 的属性压根不会进那个 JSONObject</b>（实测：一个 {@code a="A"}、{@code b=null} 的 bean
     * 解析出来 {@code keys=[a]}、{@code containsKey("b")=false}）。
     * 而 {@code target} 是从库里读回来的全量 DTO，{@code BeanUtils.toBean} 把 DB 的 NULL 原样变成 null 属性；
     * {@code form_save_draft} 建表单时从不设 category / logicRules / validateRules / customButtons /
     * detailTabs / printTemplate / extensionMode / formScript / submitHookTimeoutAction / remark
     * ⟹ <b>AI 建的表单这十格全是 null</b>。
     *
     * <p>于是模型想给刚建好的表单加一句备注（{@code patch:{"remark":"…"}}）时，
     * 得到的是「remark 不是表单的有效字段」—— 一句<b>彻底错误</b>的话。
     * 它的坏处不止于这一次失败：模型会据此认定这个字段不存在，于是换个名字再试，
     * 或者直接告诉用户「这个表单不支持备注」。而真实原因只是「这一格现在恰好是空的」。
     * 想加联动规则（{@code logicRules}）时同一句话再来一遍 —— 那恰恰是最常见的需求。
     *
     * <h3>🔴 为什么反射派生而不是手写一份清单</h3>
     * 手写清单会漂：DTO 加一个字段而忘了加进清单，表现是「新字段永远改不了、
     * 且报错说它不是有效字段」—— 与上面那个缺陷同型，而且更难查，因为清单看着是完整的。
     * 反射派生则不可能与 DTO 不一致。顺序取字段声明顺序（与 DTO 源码排版一致，读起来是分组过的）。
     *
     * <p>只排除两类：<b>静态字段</b>（{@code CLEARABLE_FIELDS} 也在 {@code getDeclaredFields()} 里）
     * 与 <b>{@link #NON_PATCHABLE_FIELDS}</b> 里点名的那几个 —— 它们是「改哪一张表单 / 这一版是谁的」
     * 的选择器与并发凭据，而不是可改的内容：让 {@code id} 进 patch 只会造出
     * 「patch 里的 id 与参数 id 不一致、该信谁」这种场面；让 {@code version} 进 patch
     * 则更糟 —— 模型可以填任意版本号，乐观锁当场作废（B4-4 的全部意义就在这把锁上）。
     * {@code code} 刻意<b>不</b>排除：草稿表单本来就能改编码，已发布的改不动时
     * 由 Service 抛它自己那句 {@code FORM_DESIGN_CODE_CANNOT_UPDATE}（真错误、真原因），
     * 在这里提前拦掉反而会把「草稿可以改」也一起否了。
     */
    private static final List<String> PATCHABLE_FIELDS = patchableFields();

    private static List<String> patchableFields() {
        List<String> fields = new ArrayList<>();
        for (Field field : FormDesignSaveReqDTO.class.getDeclaredFields()) {
            String name = field.getName();
            // 合成字段（如覆盖率插桩塞进来的 $jacocoData）不是 DTO 的属性
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()
                    || name.startsWith("$") || NON_PATCHABLE_FIELDS.contains(name)) {
                continue;
            }
            fields.add(name);
        }
        return List.copyOf(fields);
    }

    @Resource
    private FormDesignApi formDesignApi;
    @Resource
    private FormClearGuard clearGuard;
    /**
     * 描述符 → rule / 列 / 查询项 / 映射的转换
     *
     * <p>🔴 与 {@link FormSaveDraftTool} <b>共用同一个 bean</b>：两份实现必然漂开，
     * 而漂开的实测后果就是本类开头记的那三条（控件丢失、存进描述符、查询条件不长）。
     */
    @Resource
    private FormFieldFactory fieldFactory;
    @Resource
    private FormSchemaValidator schemaValidator;

    @Override
    public Object execute(Map<String, Object> params) {
        Long id = parseId(params);

        // ① 取回全量。这一步是强制的，不接受调用方自己提供「完整定义」
        FormDesignSaveReqDTO original = formDesignApi.getFormDesignById(id);
        if (original == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_update", "表单(" + id + ")不存在");
        }

        // ② 应用 patch
        // 🔴 这第二次取回<b>必须</b>拿到一个与 original 不同的实例：第 ⑤ 步的 clearGuard 是拿
        //    「改之前」与「改之后」两份对比来发现「不是有意清空、却变没了」的配置项的。
        //    两次是同一个对象的话，applyPatch 会把 original 一起改掉 ⟹ 对比恒等 ⟹ 那张安全网
        //    静默变成空操作，而用户的联动规则照样被清空、接口照样返回成功。
        //    今天成立是因为 FormDesignApiImpl.toSaveReqDTO 每次都 BeanUtils.toBean 新分配一个；
        //    一旦有人给 getFormDesign 加上 @Cacheable，这条前提就没了 —— 加缓存前先看到这里。
        FormDesignSaveReqDTO updated = formDesignApi.getFormDesignById(id); // 再取一份作为基底
        PatchOutcome outcome = applyPatch(updated, params.get(PARAM_PATCH));

        // ③ 用户明确要清空的，按他说的来
        List<String> clearFields = parseClearFields(params);
        clearFields.forEach(field -> applyClear(updated, field));

        // ④ 把「不是有意清空、却变没了」的补回去
        List<String> restored = clearGuard.restoreCleared(original, updated);

        // ⑤ 动了控件结构才体检
        // 🔴 刻意<b>不</b>无条件体检：一张表单可能在设计器里就留下了历史问题
        //    （比如 fieldMapping 少一格）。那种情况下拦下「改个备注」这种毫不相干的 patch，
        //    等于让用户连一句话都加不上，还看不懂为什么。
        //    只在本次真的改了控件结构时体检 —— 那时问题是本次带进来的，必须当场说清楚。
        AgentValidateResult validateResult = new AgentValidateResult();
        if (outcome.touchedFormFields()) {
            // 🔴 把本次<b>新增</b>的字段点出来：合并后的整份 formFields 里既有本次刚写的格子，
            //    也有设计器里早就配好的格子，一律按同一档判的话两头都不对 ——
            //    按 error 判会让一个先于本次请求就存在的缺陷挡掉「改个备注」，
            //    按 warning 判会让「加一个字典选择器字段」这种本次自己造的空控件只是被提醒一句就存进去。
            //    分档理由见 FormSchemaValidator#validate(List, String, Collection)
            validateResult = schemaValidator.validate(updated.getFormFields(), updated.getTableBinding(),
                    outcome.added());
            if (!validateResult.isPassed()) {
                throw exception(AGENT_TOOL_PARAM_INVALID, "form_update", validateResult.toFeedback());
            }
        }

        // ⑥ 回写
        formDesignApi.updateFormDesign(updated);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", updated.getName());
        outcome.describeInto(result);
        if (!validateResult.getWarnings().isEmpty()) {
            result.put("warnings", validateResult.getWarnings());
        }
        if (!restored.isEmpty()) {
            // 如实报出来。静默补回去也是一种静默 ——
            // 万一模型本意就是要清空，它得知道自己的意图没被执行
            result.put("autoRestoredFields", restored.stream()
                    .map(FormClearGuard::describe).toList());
            result.put("autoRestoredNote", "上述配置项未在 patch 中出现，已自动保留原值。"
                    + "若确实要清空，请用 clearFields 明确指定");
        }
        if (!clearFields.isEmpty()) {
            result.put("clearedFields", clearFields.stream()
                    .map(FormClearGuard::describe).toList());
        }
        // 🔴 这句话必须有：不说的话，AI 会告诉用户「改好了」，
        //    而用户去看还是旧的 —— 又一个「配了但没生效」
        result.put("message", "表单已修改。⚠️ 当前状态为「已发布有改动」，"
                + "必须重新发布（form_publish_bundle）后用户才能看到变化");
        return result;
    }

    /**
     * 一次 patch 实际改动了什么
     *
     * <p>🔴 这份东西<b>必须</b>回到模型手上，尤其是「查询项加了哪几个」。
     * 缺陷 D4-31 最难查的一环不是改坏了，而是<b>接口返回成功</b> ——
     * 模型据此告诉用户「已加上按竞对报价查询」，用户打开列表却没有那个框，
     * 两边都没说谎，只是没人把「实际改了什么」说出来。
     * 现在改了什么就报什么：没派生出查询项时 {@code notes} 里也会写明为什么没落地。
     */
    private record PatchOutcome(List<String> added, List<String> updated, List<String> removed,
                                List<String> searchAdded, List<String> searchRemoved,
                                List<String> repaired, List<String> notes) {

        static PatchOutcome untouched() {
            return new PatchOutcome(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        /**
         * 本次有没有动控件结构
         *
         * <p>「修好了旧版留下的描述符」也算动了 —— 那确实改写了 {@code formFields}，
         * 必须过一遍体检，否则修出来的 rule 是不是能渲染没人保证。
         */
        boolean touchedFormFields() {
            return !(added.isEmpty() && updated.isEmpty() && removed.isEmpty() && repaired.isEmpty());
        }

        void describeInto(Map<String, Object> result) {
            putIfNotEmpty(result, "fieldsAdded", added);
            putIfNotEmpty(result, "fieldsUpdated", updated);
            putIfNotEmpty(result, "fieldsRemoved", removed);
            putIfNotEmpty(result, "searchItemsAdded", searchAdded);
            putIfNotEmpty(result, "searchItemsRemoved", searchRemoved);
            putIfNotEmpty(result, "repairedFields", repaired);
            putIfNotEmpty(result, "notes", notes);
        }

        private static void putIfNotEmpty(Map<String, Object> result, String key, List<String> value) {
            if (!value.isEmpty()) {
                result.put(key, value);
            }
        }
    }

    private Long parseId(Map<String, Object> params) {
        Object id = params.get(PARAM_ID);
        if (id == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_update", "缺少 id（表单编号）");
        }
        try {
            return Long.parseLong(id.toString());
        } catch (NumberFormatException ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_update",
                    "id 必须是数字，实际收到：" + id);
        }
    }

    /**
     * 把 patch 应用到全量定义上
     *
     * 只认 {@link #PATCHABLE_FIELDS} 里的键。不认识的键<b>直接报错</b>而不是忽略 ——
     * 忽略的表现是「我明明改了，保存也成功了，就是没变」，
     * 而模型会以为自己做对了，不会重试。
     *
     * <p>🔴 判据<b>不能</b>是「{@code merged} 里有没有这个键」：那份 JSONObject 是
     * {@code parseObj(target)} 来的，而 Hutool 会把值为 null 的属性整个丢掉 ——
     * 于是「这一格现在是空的」会被误报成「没有这一格」。详见 {@link #PATCHABLE_FIELDS}。
     *
     * <p>🔴 {@code formFields} 从这轮覆盖里<b>摘出来</b>，等覆盖完了再单独合并。
     * 顺序不能反：合并会按 {@code searchable} 往 {@code listConf} 里派生查询项，
     * 先合并再覆盖的话，patch 里若同时带了 {@code listConf}，派生出来的那份会被盖掉。
     *
     * @return 本次实际改动了什么，要原样回给调用方
     */
    private PatchOutcome applyPatch(FormDesignSaveReqDTO target, Object patch) {
        JSONObject patchObj = parsePatch(patch);

        JSONObject merged = JSONUtil.parseObj(target);
        for (String key : patchObj.keySet()) {
            if (!PATCHABLE_FIELDS.contains(key)) {
                throw exception(AGENT_TOOL_PARAM_INVALID, "form_update",
                        "patch 里的 " + key + " 不是可修改的表单字段。可改的只有："
                                + String.join("、", PATCHABLE_FIELDS));
            }
            if (KEY_FORM_FIELDS.equals(key)) {
                continue; // 数组，走合并；见 mergeFormFields
            }
            // merged 里没有这个键也没关系：值为 null 的属性被 parseObj 丢掉了，set 会把它加回来
            merged.set(key, patchObj.get(key));
        }
        // 覆盖回 target
        FormDesignSaveReqDTO result = JSONUtil.toBean(merged, FormDesignSaveReqDTO.class);
        copy(result, target);

        if (!patchObj.containsKey(KEY_FORM_FIELDS)) {
            return PatchOutcome.untouched();
        }
        return mergeFormFields(target, patchObj.get(KEY_FORM_FIELDS),
                patchObj.containsKey(KEY_LIST_CONF), patchObj.containsKey(KEY_TABLE_BINDING));
    }

    private JSONObject parsePatch(Object patch) {
        if (patch == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_update", "缺少 patch（要修改的内容）");
        }
        JSONObject patchObj;
        try {
            patchObj = JSONUtil.parseObj(patch);
        } catch (Exception ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_update",
                    "patch 不是合法的对象：" + ex.getMessage());
        }
        if (patchObj.isEmpty()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_update", "patch 是空的，没有要改的内容");
        }
        return patchObj;
    }

    // ==================== formFields：按 field 合并 ====================

    /**
     * 把 patch 里的控件结构<b>合并</b>进现有控件，而不是整份换掉
     *
     * <p>每一项可以是两种写法之一：
     * <ul>
     *   <li><b>字段描述</b>（{@code widget}）—— 与 {@code form_save_draft} 的 {@code fields} 同形，
     *       由 {@link FormFieldFactory} 归一成真 rule。<b>推荐</b>，也是模型实际在发的那种</li>
     *   <li><b>form-create rule</b>（{@code type}）—— 原样收下，只补上缺失的内部标识</li>
     * </ul>
     * 两者都不是的东西<b>直接报错</b>：老代码把它 stringify 塞进 {@code List<String>} 存了下来，
     * 保存返回成功、表单渲染不出来，是本方法要修的那条缺陷本身。
     *
     * <p>合并顺带把三处派生配置一起改掉，它们必须与控件保持一致：
     * {@code listConf.searchItems}（查询区）、{@code listConf.columns}（列表列）、
     * {@code tableBinding.fieldMapping}（落库映射）。
     *
     * @param explicitListConf patch 自己带了 listConf —— 那时以它为准，不再派生
     * @param explicitBinding  patch 自己带了 tableBinding —— 同上
     */
    private PatchOutcome mergeFormFields(FormDesignSaveReqDTO target, Object raw,
                                         boolean explicitListConf, boolean explicitBinding) {
        JSONArray incoming;
        try {
            incoming = JSONUtil.parseArray(raw);
        } catch (Exception ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_update",
                    "formFields 不是合法的数组：" + ex.getMessage());
        }
        if (incoming.isEmpty()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_update",
                    "formFields 是空数组。要删掉某个字段请写 {\"field\":\"该字段\",\"remove\":true}；"
                            + "在合并语义下空数组等于「什么都不改」，那不该是一次调用");
        }

        String mainTable = resolveMainTable(target);
        // 物理表事实：既用来把 required 和列的 NOT NULL 对齐，也用来把 binding 里的 jdbcType
        // 归真成物理列的真实类型（见 FormFieldFactory#resolveJdbcType）
        FormFieldFactory.PhysicalSchema schema = fieldFactory.loadPhysicalSchema(mainTable);
        Map<String, Boolean> nullability = schema.nullability();

        // ── 现有控件：保序读出 ──
        // slot 是 JSONObject（读得懂的）或原始 String（读不懂的）。
        // 🔴 读不懂的原样留着：这里丢一项就是丢一个控件，
        //    而「读不懂」是我们的解析能力问题，不是这条数据的错
        List<Object> slots = new ArrayList<>();
        List<JSONObject> parsed = new ArrayList<>();
        for (String rawRule : target.getFormFields() == null ? List.<String>of() : target.getFormFields()) {
            JSONObject rule = tryParseRule(rawRule);
            slots.add(rule != null ? rule : rawRule);
            if (rule != null) {
                parsed.add(rule);
            }
        }

        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> searchAdded = new ArrayList<>();
        List<String> searchRemoved = new ArrayList<>();
        List<String> repaired = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, JSONObject> searchToAdd = new LinkedHashMap<>();
        Map<String, JSONObject> columnsToAdd = new LinkedHashMap<>();
        Map<String, JSONObject> mappingToAdd = new LinkedHashMap<>();
        Map<String, JSONObject> mappingToPatch = new LinkedHashMap<>();
        Map<String, String> titleChanged = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        int seq = fieldFactory.nextSeq(parsed);

        // ── 就地修好「描述符冒充 rule」的存量 ──
        // 老版本把模型给的描述符原样 stringify 存进了 formFields：没有 type、没有 _fc_id，
        // 表单渲染不出来。既然这一轮已经在动 formFields，就顺手修好，别留给下一次。
        // 🔴 光把它变成 rule 还不够 —— 那条描述符当初要的查询条件 / 列表列 / 落库映射
        //    一样是被老版本吞掉的，只修 rule 的话表单能渲染了，用户要的那个查询框还是没有
        for (int i = 0; i < slots.size(); i++) {
            if (!(slots.get(i) instanceof JSONObject descriptor)
                    || descriptor.containsKey("type") || StrUtil.isBlank(descriptor.getStr("widget"))) {
                continue;
            }
            String field = descriptor.getStr("field");
            JSONObject rule = fieldFactory.toRule(descriptor, seq++, nullability);
            slots.set(i, rule);
            if (StrUtil.isBlank(field)) {
                continue;
            }
            repaired.add(field);
            columnsToAdd.put(field,
                    fieldFactory.toColumn(field, rule.getStr("title"), rule.getStr("type")));
            mappingToAdd.put(field, fieldFactory.toFieldMapping(mainTable,
                    StrUtil.blankToDefault(descriptor.getStr("column"), field),
                    descriptor.getStr("jdbcType"), schema));
            if (descriptor.getBool("searchable", false)) {
                searchToAdd.put(field, fieldFactory.buildSearchItem(field, rule.getStr("title"),
                        rule.getStr("type"), descriptor.getStr("dictType"), descriptor.getStr("operator")));
                searchAdded.add(field);
            }
        }

        Map<String, Integer> positionByField = new LinkedHashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            // 布局类控件（分组标题等）没有 field，不参与合并，原位留着
            if (slots.get(i) instanceof JSONObject rule && StrUtil.isNotBlank(rule.getStr("field"))) {
                positionByField.putIfAbsent(rule.getStr("field"), i);
            }
        }

        for (Object item : incoming) {
            JSONObject in = asFieldObject(item);
            String field = in.getStr("field");
            if (StrUtil.isBlank(field)) {
                throw exception(AGENT_TOOL_PARAM_INVALID, "form_update",
                        "formFields 里有一项没有 field（字段标识），无法判断它要改哪个控件："
                                + StrUtil.maxLength(in.toString(), 120));
            }
            if (!seen.add(field)) {
                throw exception(AGENT_TOOL_PARAM_INVALID, "form_update",
                        "formFields 里 " + field + " 出现了两次。同一个字段写两遍时无从判断哪一遍算数，"
                                + "请合成一项");
            }

            if (Boolean.TRUE.equals(in.getBool("remove"))) {
                removed.add(field);
                continue;
            }

            boolean hasType = in.containsKey("type");
            boolean hasWidget = in.containsKey("widget");
            if (hasType && hasWidget) {
                throw exception(AGENT_TOOL_PARAM_INVALID, "form_update",
                        field + " 同时给了 type 与 widget —— 前者是 form-create 规则的键、"
                                + "后者是字段描述的键，两种写法只能选一种。推荐字段描述："
                                + "{\"field\":\"" + field + "\",\"widget\":\"…\",\"title\":\"…\"}");
            }

            String column = StrUtil.blankToDefault(in.getStr("column"), field);
            Integer position = positionByField.get(field);
            if (position == null) {
                // 🔴 只有<b>新增</b>字段才要求给出控件类型。已有字段不要求 ——
                //    「只写 {"field":"memo","searchable":true} 让它变成可查询」是最常见的诉求，
                //    要求它顺带把 widget 再报一遍，等于逼模型去猜一个它没读过的控件类型，
                //    而猜错的后果不是报错、是这个控件换成了别的样子
                if (!hasType && !hasWidget) {
                    throw exception(AGENT_TOOL_PARAM_INVALID, "form_update",
                            "表单里没有 " + field + " 这个字段，而你又没说要建成什么控件。"
                                    + "新增字段必须给 widget（字段描述，推荐）或 type（form-create 规则）之一，如 "
                                    + "{\"field\":\"" + field + "\",\"widget\":\"input\",\"title\":\"备注\","
                                    + "\"jdbcType\":\"VARCHAR\",\"searchable\":true}；"
                                    + "控件名必须来自 widget_contract_get。"
                                    + "若本意是改一个已有字段，请先用 form_get 看清它现在的 field 叫什么");
                }
                JSONObject rule = hasWidget
                        ? fieldFactory.toRule(in, seq++, nullability)
                        : adoptRule(in, seq++);
                slots.add(rule);
                positionByField.put(field, slots.size() - 1);
                added.add(field);
                columnsToAdd.put(field,
                        fieldFactory.toColumn(field, rule.getStr("title"), rule.getStr("type")));
                mappingToAdd.put(field,
                        fieldFactory.toFieldMapping(mainTable, column, in.getStr("jdbcType"), schema));
                // 新字段沿用新建时的语义：没标 searchable 就是不进查询区
                if (in.getBool("searchable", false)) {
                    searchToAdd.put(field, fieldFactory.buildSearchItem(field, rule.getStr("title"),
                            rule.getStr("type"), in.getStr("dictType"), in.getStr("operator")));
                    searchAdded.add(field);
                }
            } else {
                JSONObject rule = (JSONObject) slots.get(position);
                String titleBefore = rule.getStr("title");
                mergeRule(rule, in, column, nullability);
                updated.add(field);
                if (in.containsKey("title") && !StrUtil.equals(titleBefore, rule.getStr("title"))) {
                    titleChanged.put(field, rule.getStr("title"));
                }
                // 🔴 已有字段只在 patch <b>明确说了</b> searchable 时才动查询区。
                //    「没说」与「说了 false」必须区分：前者是「别动我配好的」，后者才是「取消查询」
                if (in.containsKey("searchable")) {
                    if (in.getBool("searchable", false)) {
                        searchToAdd.put(field, fieldFactory.buildSearchItem(field, rule.getStr("title"),
                                rule.getStr("type"), in.getStr("dictType"), in.getStr("operator")));
                        if (!searchAdded.contains(field)) {
                            searchAdded.add(field);
                        }
                        searchRemoved.remove(field);
                    } else {
                        searchToAdd.remove(field);
                        searchAdded.remove(field);
                        if (!searchRemoved.contains(field)) {
                            searchRemoved.add(field);
                        }
                    }
                }
                if (in.containsKey("column") || in.containsKey("jdbcType")) {
                    mappingToPatch.put(field, in);
                }
            }
        }

        // 删除：控件、查询项、列表列、落库映射四处一起清
        // 🔴 只删控件的话，另外三处会留着指向一个已经不存在的字段 ——
        //    查询区多一个查不动的框、列表多一列永远空白，都不报错
        if (!removed.isEmpty()) {
            slots.removeIf(slot -> slot instanceof JSONObject rule && removed.contains(rule.getStr("field")));
            for (String field : removed) {
                columnsToAdd.remove(field);
                mappingToAdd.remove(field);
                mappingToPatch.remove(field);
                searchToAdd.remove(field);
                searchAdded.remove(field);
                titleChanged.remove(field);
                repaired.remove(field);
            }
        }

        target.setFormFields(slots.stream()
                .map(slot -> slot instanceof JSONObject rule ? rule.toString() : String.valueOf(slot))
                .toList());

        if (!repaired.isEmpty()) {
            notes.add("字段 " + repaired + " 此前被存成了「字段描述」而不是 form-create 规则"
                    + "（旧版 form_update 整份替换留下的），本次已就地修好，"
                    + "并补齐了它当初要的列表列 / 查询项 / 落库映射");
        }

        deriveListConf(target, searchToAdd, searchAdded, searchRemoved, columnsToAdd,
                titleChanged, removed, explicitListConf, notes);
        deriveFieldMapping(target, mappingToAdd, mappingToPatch, removed,
                mainTable, explicitBinding, schema, notes);

        return new PatchOutcome(added, updated, removed, searchAdded, searchRemoved, repaired, notes);
    }

    /**
     * 把改动合并进一个已有 rule
     *
     * <p>🔴 {@code _fc_id} 与 {@code name} <b>必须留住</b>：form-create 靠它们认控件，
     * 换掉等于让它把这一格当成新控件重建，用户在这一格上配过的东西全丢；
     * 而模型自己编的值还会与既有的撞号，撞号的表现是两个控件互相覆盖、界面上少一个。
     * 具体哪些键不透传见 {@link #NON_PASSTHROUGH_KEYS}。
     */
    private void mergeRule(JSONObject current, JSONObject in, String column,
                           Map<String, Boolean> nullability) {
        String widget = StrUtil.blankToDefault(in.getStr("widget"), in.getStr("type"));
        // 合并后这一格到底是什么控件：patch 换了控件就用归一后的新值，没换就沿用现有的。
        // 下面判「是不是选项类控件」用的就是它 —— 用 current.getStr("type") 会在「patch 把 input
        // 改成 select」时读到旧值，于是选项来源整段被跳过，表现是「改成了下拉、但下拉是空的」
        String mergedType = current.getStr("type");
        if (StrUtil.isNotBlank(widget)) {
            mergedType = fieldFactory.canonicalType(widget);
            current.set("type", mergedType);
            current.set("_fc_drag_tag", mergedType);
        }
        if (in.containsKey("title")) {
            current.set("title", in.getStr("title"));
        }
        // 🔴 NOT NULL 列一律抬成必填，那是物理约束不是偏好：列不许空而表单不要求填，
        //    用户留空提交 → 数据库拒绝 → 报一句看不懂的 SQL 异常。
        //    但「patch 没提 required」时绝不把它降下来 —— 那等于悄悄取消用户配好的必填
        if (fieldFactory.isColumnNotNull(column, nullability)) {
            current.set("$required", true);
        } else if (in.containsKey("required")) {
            current.set("$required",
                    fieldFactory.resolveRequired(in.getBool("required"), column, nullability));
        } else if (in.containsKey("$required")) {
            current.set("$required", in.getBool("$required"));
        }
        if (StrUtil.isNotBlank(in.getStr("placeholder"))) {
            JSONObject props = objOf(current, "props");
            props.set("placeholder", in.getStr("placeholder"));
            current.set("props", props);
        }
        // 🔴 落点是 props.validateRules，不是 rule 顶层（理由见 NON_PASSTHROUGH_KEYS）。
        //    与新建那条路（FormFieldFactory#toRule）同款：【原样透传】、不归一不筛选，
        //    形状对不对由 FormSchemaValidator 统一判（本工具 execute 里也过它）。
        //    在这里悄悄丢掉一个看不懂的写法，就又造出一例「用户要了校验、工具说改好了、其实没生效」。
        //    用 containsKey 而不是判非空：显式传 [] 或传错形状也要送到校验器面前去挨一句真话，
        //    而不是在这儿静默变成「没改」
        if (in.containsKey(FormFieldFactory.VALIDATE_RULES_KEY)) {
            JSONObject props = objOf(current, "props");
            props.set(FormFieldFactory.VALIDATE_RULES_KEY, in.get(FormFieldFactory.VALIDATE_RULES_KEY));
            current.set("props", props);
        }
        // 其余键按 rule 级原样并入（props / col / wrap / info …）
        for (String key : in.keySet()) {
            if (!NON_PASSTHROUGH_KEYS.contains(key)) {
                current.set(key, in.get(key));
            }
        }
        // 🔴 选项来源（字典 dictType / 静态 options）与新建那条路共用 FormFieldFactory#applyOptionSource。
        //    共用之前这一格是漏的：patch 里的 dictType 只被拿去重建查询项（上面 searchable 那几处），
        //    rule 上压根不写 props.dictSource ⟹「给已有下拉配个字典」保存成功、查询区有下拉、
        //    表单里那一格照旧是空的，两侧都不报错。
        //    ⚠️ 必须排在原样并入之后：patch 里带一个 props 键时会整块替换掉 current 的 props
        //    （props 不在 NON_PASSTHROUGH_KEYS 里），先写 dictSource 会被那一下抹掉。
        //    placeholder / validateRules 上面那两处同样受这个次序影响，那是既存行为、本次不动。
        if (fieldFactory.isOptionWidget(mergedType)) {
            fieldFactory.applyOptionSource(current, in);
        } else if (in.containsKey(FormFieldFactory.DICT_TYPE_KEY)
                || in.containsKey(FormFieldFactory.OPTIONS_KEY)) {
            // 🔴 不是选项类控件却配了选项来源 ⟹ 当场拒，不留一个静默的「改了个寂寞」。
            //    这两个键在 NON_PASSTHROUGH_KEYS 上，所以原样并入那一步不会把它们写进 rule；
            //    它们唯一的去处是查询项，而查询项那边也认不出来（render 页要 widget=SELECT
            //    且带 dictType 才渲染成下拉，input 推出来的是 TEXT）——
            //    于是整件事在界面上一个痕迹都不留，接口却返回「表单已修改」。
            //    判据与规划期那条（WorkflowDslValidator#validateSaveDraftOptionSources）同口径。
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_update",
                    String.format("字段 %s 配了选项来源（dictType / options），但它合并后是 %s 控件 —— "
                                    + "只有 %s 会读这两个键，写在别的控件上会被整个忽略：不报错，"
                                    + "界面上也看不出任何变化。要么把控件换成其中之一（patch 里写 widget），"
                                    + "要么把这两个键删掉",
                            current.getStr("field"), StrUtil.blankToDefault(mergedType, "（没有控件类型）"),
                            FormFieldFactory.optionWidgetCatalog()));
        }
    }

    /**
     * 模型直接给了一条 form-create rule（而不是字段描述）：补上缺的内部标识后收下
     */
    private JSONObject adoptRule(JSONObject rule, int seq) {
        String canonical = fieldFactory.canonicalType(rule.getStr("type"));
        rule.set("type", canonical);
        if (StrUtil.isBlank(rule.getStr("_fc_id"))) {
            rule.set("_fc_id", fieldFactory.fcId(seq));
        }
        if (StrUtil.isBlank(rule.getStr("name"))) {
            rule.set("name", fieldFactory.fcName(seq));
        }
        if (StrUtil.isBlank(rule.getStr("_fc_drag_tag"))) {
            rule.set("_fc_drag_tag", canonical);
        }
        return rule;
    }

    /**
     * 把查询项 / 新增列 / 改名 落到 {@code list_conf} 上
     *
     * <p>🔴 派生不出来时<b>必须写进 notes</b>，不能悄悄跳过。
     * 「用户要了查询条件、工具返回成功、列表上却没有那个框」正是本类要修的那条缺陷，
     * 静默跳过等于把它换个地方重演一遍。
     */
    private void deriveListConf(FormDesignSaveReqDTO target,
                                Map<String, JSONObject> searchToAdd, List<String> searchAdded,
                                List<String> searchRemoved, Map<String, JSONObject> columnsToAdd,
                                Map<String, String> titleChanged, List<String> removed,
                                boolean explicitListConf, List<String> notes) {
        if (searchToAdd.isEmpty() && searchRemoved.isEmpty() && columnsToAdd.isEmpty()
                && titleChanged.isEmpty() && removed.isEmpty()) {
            return;
        }
        if (explicitListConf) {
            notes.add("patch 自带 listConf，列表列与查询项以它为准，未再自动派生");
            return;
        }
        if (StrUtil.isBlank(target.getListConf())) {
            notes.add("这张表单没有列表设计（list_conf 为空）"
                    + (searchToAdd.isEmpty() ? "" : "，查询项 " + searchToAdd.keySet() + " 无处落地")
                    + (columnsToAdd.isEmpty() ? "" : "，新增列 " + columnsToAdd.keySet() + " 无处落地")
                    + "。要查询条件请在 patch 里带上完整的 listConf，或先在「列表设计」里配好");
            return;
        }
        JSONObject conf;
        try {
            conf = JSONUtil.parseObj(target.getListConf());
        } catch (Exception ex) {
            notes.add("list_conf 不是合法 JSON，本次没有改动它：" + ex.getMessage());
            return;
        }

        boolean searchItemsExisted = conf.containsKey("searchItems");
        JSONArray searchItems = arrayOf(conf, "searchItems");
        searchToAdd.forEach((field, item) -> {
            int index = indexOfField(searchItems, field);
            if (index >= 0) {
                searchItems.set(index, item);
            } else {
                searchItems.add(item);
            }
        });
        // searchRemoved 与 removed 都走「按下标删」，删一个就要重新找一次下标
        for (String field : concat(searchRemoved, removed)) {
            int index = indexOfField(searchItems, field);
            if (index >= 0) {
                searchItems.remove(index);
            }
        }
        if (searchItemsExisted || !searchItems.isEmpty()) {
            conf.set("searchItems", searchItems);
        }

        boolean columnsExisted = conf.containsKey("columns");
        JSONArray columns = arrayOf(conf, "columns");
        // 🔴 只在「本来就有一套列设计」时才追加/同步。原本是空数组意味着这张表单的列表
        //    压根没配列（很可能就是个仅录入表单），凭空塞一列进去等于替用户发明了一套列表设计
        boolean hasColumnDesign = !columns.isEmpty();
        if (hasColumnDesign) {
            for (String field : removed) {
                int index = indexOfField(columns, field);
                if (index >= 0) {
                    columns.remove(index);
                }
            }
            titleChanged.forEach((field, label) -> {
                int index = indexOfField(columns, field);
                if (index >= 0) {
                    columns.getJSONObject(index).set("label", label);
                }
            });
            columnsToAdd.forEach((field, col) -> {
                if (indexOfField(columns, field) < 0) {
                    columns.add(col);
                }
            });
        }
        if (columnsExisted || !columns.isEmpty()) {
            conf.set("columns", columns);
        }
        if (!hasColumnDesign && !columnsToAdd.isEmpty()) {
            // 🔴 不替用户发明一套列表设计，但也不能不说：新增字段进不了列表这件事，
            //    用户打开页面才发现就已经晚了
            notes.add("list_conf.columns 是空的（这张表单没有配任何列表列），"
                    + "本次没有替它发明一套列设计，新增字段 " + columnsToAdd.keySet()
                    + " 不会出现在列表里。要列表列请先在「列表设计」里配好，"
                    + "或在 patch 里带上完整的 listConf");
        }
        target.setListConf(conf.toString());
    }

    /**
     * 把新增字段的落库映射补进 {@code table_binding.fieldMapping}
     *
     * <p>🔴 少了这一格的后果不是报错：字段在界面上显示得好好的，用户填了、点了提交、提示成功 ——
     * 而那个值<b>根本没有落库</b>。这是「配了但不生效」里最难被发现的一种。
     * 所以派生不出来时同样必须写进 notes。
     */
    private void deriveFieldMapping(FormDesignSaveReqDTO target, Map<String, JSONObject> mappingToAdd,
                                    Map<String, JSONObject> mappingToPatch, List<String> removed,
                                    String mainTable, boolean explicitBinding,
                                    FormFieldFactory.PhysicalSchema schema, List<String> notes) {
        if (mappingToAdd.isEmpty() && mappingToPatch.isEmpty() && removed.isEmpty()) {
            return;
        }
        if (explicitBinding) {
            notes.add("patch 自带 tableBinding，落库映射以它为准，未再自动派生");
            return;
        }
        if (StrUtil.isBlank(target.getTableBinding())) {
            notes.add("这张表单没有表绑定（table_binding 为空）"
                    + (mappingToAdd.isEmpty() ? "" : "，新增字段 " + mappingToAdd.keySet()
                    + " 在界面上能填、提交也提示成功，但值不会落库")
                    + "。请在 patch 里带上 tableBinding");
            return;
        }
        JSONObject binding;
        try {
            binding = JSONUtil.parseObj(target.getTableBinding());
        } catch (Exception ex) {
            notes.add("table_binding 不是合法 JSON，本次没有改动它：" + ex.getMessage());
            return;
        }
        JSONObject mapping = objOf(binding, "fieldMapping");
        mappingToAdd.forEach(mapping::set);
        mappingToPatch.forEach((field, in) -> {
            JSONObject current = objOf(mapping, field);
            if (StrUtil.isNotBlank(in.getStr("column"))) {
                current.set("column", in.getStr("column"));
            }
            // 🔴 与新增那一路同一个口径：写进 binding 的必须是【物理列】的类型，不是模型声明的。
            //    模型声明错了不报错，炸在运行期写库那一刻，见 FormFieldFactory#resolveJdbcType。
            //    读不到物理列时 resolveJdbcType 原样返回声明值，故仍按老规矩「非空才覆盖」
            String resolved = fieldFactory.resolveJdbcType(in.getStr("jdbcType"),
                    StrUtil.blankToDefault(in.getStr("column"), current.getStr("column")), schema);
            if (StrUtil.isNotBlank(resolved)) {
                current.set("jdbcType", resolved);
            }
            current.set("table", StrUtil.blankToDefault(current.getStr("table"), mainTable));
            mapping.set(field, current);
        });
        removed.forEach(mapping::remove);
        binding.set("fieldMapping", mapping);
        target.setTableBinding(binding.toString());
    }

    // ==================== JSON 小工具 ====================

    private String resolveMainTable(FormDesignSaveReqDTO target) {
        if (StrUtil.isNotBlank(target.getMainTable())) {
            return target.getMainTable();
        }
        // 有的表单只在 tableBinding 里写了 mainTable
        if (StrUtil.isBlank(target.getTableBinding())) {
            return null;
        }
        try {
            return JSONUtil.parseObj(target.getTableBinding()).getStr("mainTable");
        } catch (Exception ex) {
            return null;
        }
    }

    private static JSONObject tryParseRule(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(raw);
        } catch (Exception ex) {
            log.warn("[tryParseRule][控件定义不是合法 JSON，原样保留不动：{}]", StrUtil.maxLength(raw, 80));
            return null;
        }
    }

    /**
     * 把 formFields 里的一项读成对象
     *
     * <p>🔴 认不出来就<b>报错</b>，绝不 stringify 了事。老代码走的是
     * {@code JSONUtil.toBean(merged, FormDesignSaveReqDTO.class)}，Hutool 会宽容地把对象
     * 转成字符串塞进 {@code List<String>} —— 存下来的是一条描述符而不是 rule，
     * 保存返回成功，表单渲染不出来。
     */
    private static JSONObject asFieldObject(Object item) {
        if (item instanceof JSONObject obj) {
            return obj;
        }
        if (item instanceof CharSequence text) {
            // 模型把字段描述又 JSON 编码了一层（发成字符串而不是对象）时，解一层收下
            if (JSONUtil.isTypeJSONObject(text.toString())) {
                return JSONUtil.parseObj(text.toString());
            }
        } else if (item instanceof Map<?, ?>) {
            return JSONUtil.parseObj(item);
        }
        throw exception(AGENT_TOOL_PARAM_INVALID, "form_update",
                "formFields 里有一项不是字段对象：" + StrUtil.maxLength(String.valueOf(item), 80)
                        + "。每一项都该形如 {\"field\":\"…\",\"widget\":\"…\",\"title\":\"…\"}");
    }

    /** 取出嵌套对象；不是对象（或不存在）就给一个新的，调用方改完要自己 set 回去 */
    private static JSONObject objOf(JSONObject parent, String key) {
        Object value = parent.get(key);
        if (value instanceof JSONObject obj) {
            return obj;
        }
        if (value instanceof CharSequence text && JSONUtil.isTypeJSONObject(text.toString())) {
            return JSONUtil.parseObj(text.toString());
        }
        return new JSONObject();
    }

    /** 取出嵌套数组；不是数组（或不存在）就给一个新的，调用方改完要自己 set 回去 */
    private static JSONArray arrayOf(JSONObject parent, String key) {
        Object value = parent.get(key);
        if (value instanceof JSONArray array) {
            return array;
        }
        if (value instanceof CharSequence text && JSONUtil.isTypeJSONArray(text.toString())) {
            return JSONUtil.parseArray(text.toString());
        }
        return new JSONArray();
    }

    private static int indexOfField(JSONArray array, String field) {
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i) instanceof JSONObject item && field.equals(item.getStr("field"))) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private List<String> parseClearFields(Map<String, Object> params) {
        Object clearFields = params.get(PARAM_CLEAR_FIELDS);
        if (clearFields == null) {
            return List.of();
        }
        List<String> fields = JSONUtil.toList(JSONUtil.parseArray(clearFields), String.class);
        for (String field : fields) {
            if (!FormDesignSaveReqDTO.CLEARABLE_FIELDS.contains(field)) {
                throw exception(AGENT_TOOL_PARAM_INVALID, "form_update",
                        "clearFields 里的 " + field + " 不是可清空的配置项。可选："
                                + String.join("、", FormDesignSaveReqDTO.CLEARABLE_FIELDS));
            }
        }
        return fields;
    }

    private void applyClear(FormDesignSaveReqDTO target, String field) {
        try {
            FormDesignSaveReqDTO.class
                    .getMethod("set" + StrUtil.upperFirst(field), String.class)
                    .invoke(target, (Object) null);
        } catch (Exception ex) {
            log.warn("[applyClear][清空字段({})失败]", field, ex);
        }
    }

    /**
     * 字段级拷贝
     *
     * 刻意逐字段拷而不是直接换引用：调用方持有的是 {@code target} 这个对象，
     * 换引用的话外面拿到的还是旧的 —— 这类错误不会报错，只会让改动「没生效」。
     *
     * <p>🔴 <b>DTO 加字段时这里必须跟着加一行</b>，否则那一格会变成
     * 「patch 认它、报错也不报、保存也返回成功，但改动被这一步丢掉」——
     * 正是本工具类注释开头骂的那种缺陷。
     * 靠人记不住，所以 {@code FormUpdateToolTest#copyCoversEveryPatchableField}
     * 拿 {@link #PATCHABLE_FIELDS} 逐格比对，漏一行就红。
     *
     * <p>⚠️ {@link #NON_PATCHABLE_FIELDS} 里的那几格<b>不在</b>上面那个测试的比对范围内
     * （它们本就不许被 patch），但<b>照样要在这里拷</b> —— {@code from} 是从 {@code target}
     * 序列化再反序列化出来的，值与 {@code target} 上的相同，所以拷与不拷结果一样；
     * 写上是为了让「这一格有归属」这件事在源码里看得见，而不是靠读代码的人推断。
     */
    private void copy(FormDesignSaveReqDTO from, FormDesignSaveReqDTO to) {
        to.setId(from.getId());
        // 🔴 乐观锁基线（B4-4）。它<b>只能</b>来自 {@code getFormDesignById} 的取回值：
        //    这里不拷的话 target 上留着的还是原值（结果相同），
        //    但一旦有人哪天把 version 放进 patch 又忘了排除，这一行就是最后一道兜底 ——
        //    所以 {@link #NON_PATCHABLE_FIELDS} 与这一行必须一起看
        to.setVersion(from.getVersion());
        to.setName(from.getName());
        to.setCode(from.getCode());
        to.setCategory(from.getCategory());
        to.setFormType(from.getFormType());
        to.setFormConf(from.getFormConf());
        to.setFormFields(from.getFormFields());
        to.setListConf(from.getListConf());
        to.setDataSourceConfigId(from.getDataSourceConfigId());
        to.setMainTable(from.getMainTable());
        to.setMainPk(from.getMainPk());
        to.setTableBinding(from.getTableBinding());
        to.setLogicRules(from.getLogicRules());
        to.setValidateRules(from.getValidateRules());
        to.setCustomButtons(from.getCustomButtons());
        to.setDetailTabs(from.getDetailTabs());
        to.setPrintTemplate(from.getPrintTemplate());
        to.setExtensionMode(from.getExtensionMode());
        to.setFormScript(from.getFormScript());
        to.setSubmitHookTimeoutAction(from.getSubmitHookTimeoutAction());
        to.setSort(from.getSort());
        to.setRemark(from.getRemark());
    }


    /**
     * 🔴 这份文本里的 {@code formFields} 段落是「改控件结构怎么改」教法<b>唯一那一处</b>。
     *
     * <p>缺陷 D4-31 的根因就在这儿：原文只说了「改 formFields 就是改控件结构（form-create 的 rule）」，
     * 全文<b>一次都没提</b> {@code searchable}、{@code listConf.searchItems}、{@code remove}。
     * 于是模型照着 {@code form_save_draft} 的 {@code fields} 形状发了一份字段描述过来
     * （实测 def #467 / #570 都是这个形状），而老代码按键整份覆盖：控件丢光、
     * 存进去的是描述符不是 rule、{@code searchable:true} 被整个忽略 —— 三条都不报错。
     *
     * <p>现在两件事都做了：执行侧按 field 合并（{@link #mergeFormFields}），
     * 文案侧把「推荐写字段描述」「searchable 会自动长出查询条件」「删字段要写 remove」讲明白。
     * 只改一边都不够 —— 只改执行侧，模型仍会去手拼 rule；只改文案，模型偶尔不遵从时照样丢数据。
     */
    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("warnings", "autoRestoredFields", "clearedFields");
    }

    @Override
    public String paramsSchema() {
        // 🔴 三份名单都从常量出，不在这里手敲一遍：手敲的那一份迟早与判据漂开，
        //    而漂开的表现是「文档说能改、工具说不是有效字段」，模型只会以为自己拼错了名字
        //    （选项类控件那三个名字的出处是 FormFieldFactory#OPTION_WIDGETS）
        return """
                id（必填，数字）：表单编号
                patch（必填，对象）：只写要改的字段，如 {"name": "新名字"}。
                  绝不要自己拼完整表单定义，工具会自动取回全量再合并。
                  可改的字段就这些（写别的会直接报错，不会静默忽略）：
                  %s
                  ⚠️ 改 formFields / formConf 就是改表单的控件结构。
                  控件类型是受控白名单：写错不报错，表单能保存但渲染不出来。
                  所以动结构前必须先 widget_contract_get 拿控件清单与配置项，
                  改完 form_inspect 体检，再 form_publish_bundle 重新发布。
                  🔴 清单里标了 aiAuthorable=false 的控件【不要新增】：它们的必需配置
                    patch 里没有能放它的格子，加进去就是个空控件（点不开的下拉 / 显示不出内容的块），
                    而这件事不报错。详见 form_save_draft 那份 schema 的 widget 说明。
                    存量里已有的这类控件只在体检结果里如实报出来，不拦本次修改
                  🔴 不要改用 form_save_draft「重建一份」来绕过：它对已存在的编码一律拒建。
                patch.formFields（数组，可选）：按 field 合并，不是整份替换 ——
                  只写要动的那几个字段，没提到的控件原样保留。每项两种写法二选一：
                  ① 推荐「字段描述」（与 form_save_draft 的 fields 同形）：
                     {"field":"memo","widget":"input","title":"备注","jdbcType":"VARCHAR",
                      "column":"memo","required":false,"searchable":true,"placeholder":"请输入"}
                  ② form-create 的原始 rule（含 type / props / _fc_id 等），仅在你确实要
                     精细控制渲染时才用；_fc_id 与 name 不用自己编，工具会发号
                  · 加一个可查询的字段：给新字段传 "searchable": true，
                    工具会同时把它写进 list_conf.searchItems（列表查询区）、
                    list_conf.columns（列表列）和 table_binding.fieldMapping（落库映射）——
                    这三处不用你自己拼，漏了任何一处的表现都是「保存成功但用不了」
                  · 让已有字段变成可查询 / 取消可查询：只写
                    {"field":"该字段","searchable":true} 或 false，其余属性不写就是不改
                  · searchable 不写等于「不动查询区」，与写 false（明确取消）是两件事
                  · 给字段加/改【格式】校验（身份证、手机号、邮箱…）：只写
                    {"field":"该字段","validateRules":[{"type":"BUILTIN","patternKey":"ID_CARD"}]}
                    工具会把它落进 props.validateRules（写别处不生效）。可用的 patternKey
                    见 form_save_draft 那份 schema 的 validateRules 说明 —— 两个工具共用同一份目录
                  · 给选项类控件（%s）配选项：固定选项写
                    {"field":"该字段","options":[{"label":"选项名","value":"存进库的值"}]}，
                    用字典写 {"field":"该字段","dictType":"系统里已存在的字典类型"}。
                    两个都不写就是不动这一格已有的选项来源；要清掉字典改用静态选项就直接给 options，
                    工具会顺手把 dictSource 删掉（两个都留着时字典赢，静态选项静默不生效）。
                    🔴 你没有建字典的工具，凭记忆编 dictType 的后果不是报错，而是下拉一个选项都没有；
                    选项是固定的就写 options。详见 form_save_draft 那份 schema 的同名说明
                  · 🔴 删字段必须显式写 {"field":"该字段","remove":true}。
                    「不写这个字段」在合并语义下等于「不改它」，删不掉任何东西
                  · 传空数组会直接报错，那在合并语义下等于什么都没改
                clearFields（可选，字符串数组）：确实要清空哪些配置项，可选值
                  %s
                  不传等于「不清空」——「没传」与「想清空」必须用这个参数区分"""
                .formatted(String.join("、", PATCHABLE_FIELDS),
                        FormFieldFactory.optionWidgetCatalog(),
                        String.join("/", FormDesignSaveReqDTO.CLEARABLE_FIELDS));
    }

}
