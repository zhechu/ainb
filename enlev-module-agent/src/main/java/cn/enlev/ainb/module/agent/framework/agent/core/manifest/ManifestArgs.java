package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.StateTemplateResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抽取器读 {@code config.args} 的一组 null-safe 取数工具
 *
 * <h3>为什么值得单独一个类</h3>
 * 十来个抽取器全都在做同一件事：拿到 {@code args} 这个 Map，然后按名字取字符串、取数组、
 * 取嵌套对象。每一处都要处理「键不存在」「类型不对」「值是 {@code null}」——
 * 而这些情况在 DSL 里<b>都合法</b>（DSL 有问题由校验器报错，清单照出，见
 * {@link WorkflowChangeManifestBuilder#build} 的契约）。
 * 让每个抽取器自己写一遍防御，结果是十一份互不相同的兜底，其中总有一份会 NPE，
 * 而清单接口一 NPE，用户在点「同意」前就什么都看不到了。
 *
 * <h3>🔴 类型不对时一律返回空，绝不抛</h3>
 * 与 {@link NodeDTO#configList} / {@link NodeDTO#configMap} 同口径。
 * 比如 {@code datamodel_import} 的 {@code tableNames} 在执行期要求必须是真数组
 * （字符串形式会被 {@code DataModelImportTool} 拒），但清单不该在这里替执行器再判一次 ——
 * 它只需要「认不出来就当没有」，然后把这一项照样列出来，让人看见参数写得不对。
 *
 * <p>⚠️ 包级可见：这些是抽取器的实现细节，不是清单层对外的契约。
 * <b>唯一的例外是 {@link #argsShapeProblem}</b> —— 校验器 V21 要说的是同一句话，
 * 故那一个方法（以及为了够到它、本类自己）是 public，其余一律维持包级。
 *
 * @author 走进新时代
 */
public final class ManifestArgs {

    /** {@code tool} 节点里存工具入参的 config 键；与 {@code ToolNodeExecutor#execute} 同源 */
    static final String CONFIG_ARGS = "args";

    /** {@link #preview} 里单个参数值渲染后的最大字符数；超了截断。清单是给人看的，不是给人拉滚动条的 */
    static final int MAX_VALUE_CHARS = 200;
    /** {@link #preview} 最多展示多少个参数键 */
    static final int MAX_KEYS = 20;

    private static final String ELLIPSIS = "…";

    private ManifestArgs() {
    }

    /**
     * 取节点的 {@code args}，永不返回 null
     *
     * <p>🔴 「永不返回 null」这句话在 2026-10-04 之前是<b>假的</b>（缺陷 D4-14）：
     * {@link NodeDTO#configMap} 认不出类型时返回 null 而不是空 Map ——
     * 与它旁边的 {@link NodeDTO#configList} 返回 {@code List.of()} <b>不对称</b> ——
     * 而本方法把那个 null 原样透传了出去。模型只要把 args <b>整体</b>写成占位符
     * （{@code "args": "{{$.publish_bundle_input}}"}，让前置 script 节点去拼参数表）就踩中它：
     * {@code FormPublishParamResolver#resolveFormId} 第一行 {@code params.get(...)} NPE，
     * 被 {@code ChangeManifestDryRunner#probeSafely} 吞成一句 WARN，
     * 而那句<b>原始 Java 异常文案</b>反倒被渲染进了用户可见的变更清单
     * （「未能完成干跑（NullPointerException：Cannot invoke "java.util.Map.get(Object)"
     * because "&lt;parameter1&gt;" is null）」）。一个 HIGH 风险项就这么在点「同意」之前失去了复核。
     * run8 的示例 55 与 59 各中一次（后端日志 18:05:55.461 / 18:07:09.911，节点都是 {@code do_publish}）。
     *
     * <p>本类其余取数方法（{@link #raw}/{@link #map}/{@link #list}/{@link #preview}）
     * 早就各自防了 {@code args == null}，唯独入口没防 —— 那是「十一份互不相同的兜底，
     * 其中总有一份会 NPE」这句话在本类自己身上的应验。
     */
    static Map<String, Object> of(NodeDTO node) {
        Map<String, Object> args = node == null ? null : node.configMap(CONFIG_ARGS);
        return args == null ? Map.of() : args;
    }

    /**
     * {@code args} 存在、但<b>不是</b>「参数名 → 取值模板」这个对象时，返回一句诚实的说法；
     * 是对象（或压根没写 args）返回 null
     *
     * <h3>为什么这句话必须由清单来说</h3>
     * {@code ToolNodeExecutor#execute} 读 args 用的也是 {@link NodeDTO#configMap}，
     * 拿到 null 就直接<b>跳过整个参数装配</b>、以空参数表去调工具。也就是说
     * {@code "args": "{{$.x}}"} 这种写法在执行期<b>必然</b>报「缺少参数」——
     * 这不是「探不出来」，是「探得出来、且结论是跑不通」。
     * 干跑层若只回 NOT_PROBED，审批人看到的是「本次没探」，而真相是「这项一定失败」。
     *
     * <p>⚠️ 校验器那边<b>已经</b>有一条规则拦这种形状了：{@code WorkflowDslValidator} 的
     * <b>V21</b>（{@code validateToolArgsShape}），报 error。它<b>调用本方法</b>取文案，
     * 不自己另写一份 —— 同一种形状若在「体检的 error」与「清单那一格的红标」上是两种说法，
     * 用户会当成两件事去改两遍。于是本方法是 V 系列里唯一一条被校验器借走文案的，
     * 改这里的措辞等于同时改两处，改之前先想清楚那句话在两边都成立。
     *
     * <p>（2026-10-04 之前这一段写的是「校验器<b>没有</b>规则拦这种形状，补规则要动规则号、
     * 已单独报给用户裁定」—— 用户裁了「补」，V21 就是那一刀。旧话留着只会误导下一个人。）
     */
    public static String argsShapeProblem(NodeDTO node) {
        Object raw = node == null || node.getConfig() == null ? null : node.getConfig().get(CONFIG_ARGS);
        if (raw == null || raw instanceof Map) {
            return null;
        }
        String shown = raw instanceof String s ? s : JSONUtil.toJsonStr(raw);
        if (shown.length() > MAX_VALUE_CHARS) {
            shown = shown.substring(0, MAX_VALUE_CHARS) + ELLIPSIS;
        }
        return "args 整体写成了「" + shown + "」，但 tool 节点只把 args 当「参数名 → 取值模板」"
                + "逐个替换（ToolNodeExecutor 拿到非对象的 args 会直接以**空参数表**去调工具），"
                + "所以这个节点执行期必然报缺少参数。要让脚本拼参数表，得让脚本把结果写进 outputVar、"
                + "再在 args 里逐键引用，例如 {\"id\": \"{{$.publish_bundle_input.id}}\"}";
    }

    /**
     * 取字符串；缺失、非字符串、空白都返回 null
     *
     * <p>数字会被 {@code toString}：{@code form_publish_bundle.id} 常被写成数字，
     * 而清单要的是「能显示的东西」，不是「类型正确的东西」。
     */
    static String str(Map<String, Object> args, String key) {
        Object v = raw(args, key);
        if (v == null) {
            return null;
        }
        String s = v instanceof String str ? str : String.valueOf(v);
        return StrUtil.isBlank(s) ? null : s.trim();
    }

    static Object raw(Map<String, Object> args, String key) {
        return args == null ? null : args.get(key);
    }

    /**
     * 取嵌套对象；不是对象返回空 Map
     *
     * <h3>🔴 也接受「模型把对象序列化成的 JSON 字符串」</h3>
     * 这不是多此一举的宽容，而是与执行期<b>同口径</b>：
     * {@code DataModelParamResolver#resolveCreate} 用 {@code JSONUtil.parseObj(model)} 取 {@code model}，
     * {@code FormDataParamResolver#resolveData} 与 {@code DataInterfaceParamResolver#resolveArgs}
     * 都显式兼容 Map 与 JSON 串两种形态。
     * 抽取器只认 Map 的话，同一个节点在执行期能跑通、在清单里却显示成
     * 「新建 1 张数据表（表名未填）」—— 清单比实际保守同样是错，
     * 它会让审批人以为这一项没有作用对象。
     *
     * <p>解析不出来（既不是 Map 也不是合法 JSON 对象）返回空 Map，<b>不抛</b>：
     * DSL 有问题由校验器报错，清单照出（见 {@link WorkflowChangeManifestBuilder#build} 的契约）。
     */
    static Map<String, Object> map(Map<String, Object> args, String key) {
        Object v = raw(args, key);
        if (v == null) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            JSONUtil.parseObj(v).forEach(result::put);
            return result;
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 取数组；不是 List 返回空 List
     */
    static List<Object> list(Map<String, Object> args, String key) {
        Object v = raw(args, key);
        return v instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
    }

    /**
     * 取「对象数组」，跳过其中不是对象的项
     *
     * <p>{@code columns[]}、{@code fields[]} 这类参数的标准形态。
     * 跳过而不是报错：DSL 里混进一个字符串是校验器的事，清单能认几项认几项。
     */
    static List<Map<String, Object>> mapList(Map<String, Object> args, String key) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list(args, key)) {
            if (item instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) m;
                result.add(casted);
            }
        }
        return result;
    }

    /**
     * 取「字符串数组」（也接受数字，统一转字符串）；不是 List 返回空 List
     *
     * <p>{@code dropColumns}、{@code roleIds}、{@code tableNames} 用。
     */
    static List<String> strList(Map<String, Object> args, String key) {
        List<String> result = new ArrayList<>();
        for (Object item : list(args, key)) {
            if (item == null) {
                continue;
            }
            String s = item instanceof String str ? str : String.valueOf(item);
            if (StrUtil.isNotBlank(s)) {
                result.add(s.trim());
            }
        }
        return result;
    }

    /**
     * 取整数；数字与数字串都认，其余返回 null
     */
    static Integer integer(Map<String, Object> args, String key) {
        Object v = raw(args, key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && StrUtil.isNotBlank(s)) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 这个值是不是一个「运行期才知道」的占位符
     *
     * <p>🔴 判定口径来自 {@link StateTemplateResolver#containsPlaceholder}，不在这里另写一份正则。
     * 抽取器据此把「还没定的值」与「已经存在的对象」在措辞上分开：
     * 显示 {@code id = {{$.save_draft.id}}} 时用户看到的是待定的东西，
     * 显示 {@code id = 1024} 时看到的是一张真实的表单 —— 两者要改的对象完全不同。
     *
     * <p>⚠️ 顺着占位符反查「是哪个节点产出它」这件事<b>不在本刀</b>：
     * {@code ToolNodeExecutor} 把节点结果写进 state 用的键是 {@code outputVar}（默认 {@code tool_result}），
     * 不是节点 id，所以 {@code {{$.save_draft.id}}} 里的 {@code save_draft} 未必真是节点 id。
     * 反查逻辑归 S2-e 的 {@code untrustedInputs} 数据流溯源统一做，此处只判「是不是占位符」。
     */
    static boolean isPending(String value) {
        return StateTemplateResolver.containsPlaceholder(value);
    }

    /**
     * 这个键的值是不是「本该是对象、却被整个写成占位符」的参数；是就返回那串占位符原文，不是返回 null
     *
     * <h3>为什么值得单开一个方法，而不是让每个 probe 自己写 {@code raw + instanceof + isPending}</h3>
     * 有一类参数在<b>执行期</b>是对象，但在 DSL 里写成占位符、由前置 script 节点算出来 ——
     * {@code {"model": "{{$.prepared_args.model}}"} 就是。这种写法<b>合法且能跑通</b>：
     * {@code StateTemplateResolver#resolve} 返回的是字符串，把对象渲染成 JSON 串，
     * 而 {@code DataModelParamResolver#resolveCreate} 的 {@code JSONUtil.parseObj} 两种形态都收。
     *
     * <p>坏在做清单的这一刻它<b>还没被渲染</b>：{@link #map} 解析 {@code "{{$.x}}"} 会失败，
     * 按本类「认不出来就当没有」的契约<b>静默返回空 Map</b> ——
     * 于是「先取里面的表名、再判它是不是占位符」那套路数拿到的是 null，守卫<b>直接失效</b>，
     * 原始占位符被送进解析器。hutool 对它抛的是
     * 「A JSONObject can not directly nest another JSONObject or JSONArray. at 2 [character 3 line 1]」，
     * 而那句<b>库内部文案</b>被 {@code ChangeManifestDryRunner#previewCreate} 转成 REJECTED、
     * 再被 {@code WorkflowDslValidator#foldDryRunRejections} 原样透传成一条体检 error
     * （缺陷 D4-17，run11 示例 55）。一份跑得通的 DSL 被判成「这么改跑不通」，
     * 用户看到的还是一句自己无从下手的 JSON 解析器报错。
     *
     * <p>部署那一支早就为 {@code simpleModel} 手写过同样三行
     * （{@code ChangeManifestDryRunner#probeProcessDeploy}），建表这一支漏了 ——
     * 与 {@link #argsShapeProblem} 收进本类时写下的那条教训是同一句话：
     * 同一种形状在五份 probe 里各写一遍，总有一份漏。故判定收在这里，两处共用。
     *
     * <p>⚠️ 只认「值<b>整体</b>是占位符字符串」。值是真对象、占位符嵌在它里面某一层的情况
     * 不在此列 —— 那种形状解析器能正常解析，只是解出来的某个字段是占位符，
     * 由各自那一支的 {@link #isPending} 守卫处置（如 {@code model.tableName}）。
     */
    static String pendingObject(Map<String, Object> args, String key) {
        Object v = raw(args, key);
        return v instanceof String s && isPending(s) ? s : null;
    }

    /**
     * 参数表 → 「键 → 短字符串」的有序预览
     *
     * <h3>为什么要收在这里</h3>
     * 兜底抽取器要用它，{@code data_interface_invoke} 的抽取器也要用 ——
     * 后者拿不到接口的参数契约（那要查库），能说的同样只有「传了哪些键、值大概长什么样」。
     * 两处各写一份的后果是同一个值在清单的两处截断长度不同，
     * 而用户会以为那是两个不同的参数。
     *
     * <p>值一律渲染成字符串而非保留原结构：清单要能被冻结进 BPM 流程变量，
     * 而参数里可能有任意深的嵌套（{@code form_save_draft.fields} 就是几十行的数组），
     * 原样带上会让一份清单膨胀到几十 KB 挂在流程变量里。
     */
    static Map<String, String> preview(Map<String, Object> args) {
        Map<String, String> preview = new LinkedHashMap<>();
        if (args == null || args.isEmpty()) {
            return preview;
        }
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (preview.size() >= MAX_KEYS) {
                preview.put(ELLIPSIS, "另有 " + (args.size() - MAX_KEYS) + " 个参数未展示");
                break;
            }
            preview.put(entry.getKey(), render(entry.getValue()));
        }
        return preview;
    }

    private static String render(Object value) {
        String text;
        if (value == null) {
            text = "null";
        } else if (value instanceof Map || value instanceof Collection || value.getClass().isArray()) {
            text = JSONUtil.toJsonStr(value);
        } else {
            text = String.valueOf(value);
        }
        return text.length() <= MAX_VALUE_CHARS ? text : text.substring(0, MAX_VALUE_CHARS) + ELLIPSIS;
    }

}
