package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：取控件契约（裁剪版）
 *
 * <h3>生成表单 schema 之前必须先调它</h3>
 * 控件 {@code type} 是个受控白名单，凭记忆写的后果不是报错，而是
 * <b>表单能保存、能发布，就是渲染不出来</b> —— 典型的「配了但不生效」。
 *
 * <p>🔴 选型清单<b>只给 supported=true 的控件</b>：曾经把 supported=false 的也带上并标注，
 * 理由是「模型用了废弃控件时能知道它存在但不能用、该换哪个」。实测这个理由站不住 ——
 * 清单里躺着一条 label 为「上传（内核内置）」的 {@code upload}，模型给图片字段选控件时
 * 无视 supported 标记与「只能使用 supported=true」的使用要求直接选了它，
 * 一路到运行期 {@code form_save_draft} 才报「控件类型 upload 已停用」，
 * 用户看到的又是一张点了确认却失败的卡片。选型清单是<b>给生成用的白名单</b>，
 * 不能用的就不该出现在里面；停用控件仍可按名查详情（带 supported=false），
 * 校验报错也会点名并给出最接近的可用候选（{@code FormSaveDraftParamValidator.suggestSimilar}）。
 *
 * <h3>🔴 为什么分成「清单」与「详情」两段</h3>
 * 全量含 props 的输出实测有 <b>20777 字符</b>，而回灌预算只有 4000 ——
 * 于是一次截断砍掉了 81%，清单里只剩下前 8 个控件（到 {@code select} 为止），
 * 而 {@code datePicker} 排在第 13 位。
 *
 * <p>后果不是「少看到几个控件」这么轻：模型拿到的是一份<b>看起来完整、实则残缺</b>
 * 的白名单，它无从知道自己没看全。用户要「日期时间」字段时它在清单里找不到，
 * 就照着 Element Plus 的记忆编了个 {@code dateTimePicker}；被校验拦下后它反复重查
 * 白名单想拿到完整清单 —— 每次都被同样截断，5 轮上限耗尽，规划彻底失败。
 *
 * <p>所以这里改成：默认返回<b>全部</b>控件的选型信息（type/label/group/supported/
 * columnTypes，约 7000 字符，且本工具已声明 {@code truncateResult=false} 不受截断），
 * 而占掉 8988 字符的 props 改成<b>按 {@code types} 参数取</b> ——
 * 模型定下要用哪几个控件之后再查那几个，一次只几百字符。
 * 选型看清单、配置看详情，两边都不会残缺。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "widget_contract_get",
        displayName = "查询控件契约白名单",
        groupName = "控件契约",
        description = "获得表单可用的控件清单（类型、可绑定的列类型；已停用的控件不在清单里）。"
                + "生成或修改表单 schema 之前必须先调它 —— 控件类型是受控白名单，"
                + "写错不会报错，表单能保存但渲染不出来。"
                + "不带参数时返回全部可用控件的选型信息（不含配置项）；"
                + "定下要用哪些控件后，带 types 参数再查一次拿它们的配置项（props）",
        permission = "infra:form-design:query",
        risk = RiskLevel.READ_ONLY,
        truncateResult = false)
public class WidgetContractGetTool implements AgentToolFunction {

    /** 参数名：要查配置项的控件类型 */
    public static final String PARAM_TYPES = "types";

    /**
     * 返回里表示「这个控件 AI 建不出来」的键名
     *
     * <p>🔴 收成一个常量：清单与详情两个返回分支都要写它，而「使用要求」那段散文还要点名同一个键
     * （模型读的是键名，散文里写成别的字样就对不上号了）。三处各敲一遍字面量迟早会漂，
     * 漂了的表现是「说明在解释一个返回里从来没出现过的键」。
     */
    private static final String AI_AUTHORABLE_KEY = "aiAuthorable";

    /**
     * 同上，但给的是<b>原因</b>，只在详情那一档返回
     *
     * <p>⚠️ 清单那一档<b>刻意不带</b>它：18 个控件各带一句原因就是 1.4 KB，
     * 而清单的体积是有预算的（见 {@link #AI_AUTHORABLE_KEY} 那段）。
     * 模型真要用某个控件时会带 {@code types} 再查一次，那时才需要知道缺哪一格。
     */
    private static final String AI_AUTHORABLE_REASON_KEY = "aiAuthorableReason";

    @Resource
    private FormDesignApi formDesignApi;

    @Override
    public Object execute(Map<String, Object> params) {
        List<FormDesignApi.WidgetSimpleRespDTO> widgets = formDesignApi.getWidgetContracts();
        List<String> types = parseTypes(params == null ? null : params.get(PARAM_TYPES));
        return types.isEmpty() ? briefList(widgets) : detailList(widgets, types);
    }

    // ==================== 全量清单（不含 props） ====================

    private Map<String, Object> briefList(List<FormDesignApi.WidgetSimpleRespDTO> widgets) {
        // 🔴 只给能用的：停用控件进了选型清单就会被模型真的选上（见类注释的 upload 事故）
        List<FormDesignApi.WidgetSimpleRespDTO> usable = widgets.stream()
                .filter(widget -> Boolean.TRUE.equals(widget.getSupported()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", usable.size());
        result.put("说明", "这是<b>全部</b> " + usable.size() + " 个<b>可用</b>控件的选型清单，没有省略。"
                + "只能使用出现在这里的 type，凭记忆编一个不在清单里的名字会被直接拒绝");
        result.put("widgets", usable.stream().map(widget -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", widget.getType());
            item.put("label", widget.getLabel());
            item.put("group", widget.getGroup());
            item.put("supported", widget.getSupported());
            if (Boolean.TRUE.equals(widget.getDeprecated())) {
                // 只有废弃的才带这个键：给每个控件都挂一个 deprecated:false
                // 会让这份清单凭空多出几十行噪声
                item.put("deprecated", true);
            }
            item.put("columnTypes", widget.getColumnTypes());
            if (Boolean.TRUE.equals(widget.getSubTable())) {
                item.put("subTable", true);
            }
            // 🔴 「在清单里」≠「AI 建得出来」：这 63 个可用控件里实测有 18 个的必需配置
            //    （字典选择器的 dictType、级联的 props.options、公式的 expression…）
            //    字段描述里<b>没有能放它的格子</b>，写了不报错、表单照样发布，
            //    运行期那一格是个空控件。判据在 FormFieldFactory#checkWidgetAuthorable（从契约现算）。
            //    ⚠️ 这里只给<b>结论</b>、不给原因：原因按 types 查详情时会给，
            //    而这份清单的体积是有预算的（类注释记着那次两万字符被砍掉 81% 的事故）
            if (FormFieldFactory.checkWidgetAuthorable(widget) != null) {
                item.put(AI_AUTHORABLE_KEY, false);
            }
            return item;
        }).toList());
        result.put("使用要求", "清单里的控件都可以用；标了 deprecated=true 的虽然还能用，但新表单不要选。"
                + "没出现在清单里的类型一律不能用（含已停用的历史控件，如 upload）。"
                + "控件绑定的列类型必须出现在它的 columnTypes 里，"
                + "否则表单能保存但跑起来不对。"
                + "🔴 标了 " + AI_AUTHORABLE_KEY + "=false 的控件<b>AI 建不出来</b>："
                + "它们必需的配置字段描述里没有对应的格子，写了不报错 —— 表单照样保存、照样发布，"
                + "运行期那一格是个空控件（点不开的下拉 / 显示不出内容的块），没有任何一处会主动说。"
                + "选择类需求（含级联、树形）请改用 " + FormFieldFactory.optionWidgetCatalog()
                + " 配 options 或 dictType；别的场景改选一个不带这个标记的控件，"
                + "或先建好表单、再由人到「表单设计」里配那一格（此时要在回复里如实说清哪一格要人工配）");
        // 🔴 这段必须说清楚「props 不是没有，是要另外查」。
        //    不说的话模型会以为这些控件不需要配置，于是给字典选择器建一个空下拉 ——
        //    能保存、能发布、不报错，就是选不出东西
        result.put("配置项要求", "本次<b>未</b>返回 props（控件配置项），不是这些控件不需要配置。"
                + "定下要用哪几个控件后，带 types 参数再查一次即可拿到它们的 props，"
                + "例如 {\"types\":[\"select\",\"DictSelect\",\"RelationForm\"]}。"
                // 🔴 必须点明「再查一次」是用 tool_calls 轮次查，不是排进计划里。
                //    实测模型把本工具当成一个 step 写进了 plan.steps：
                //    {"tool":"widget_contract_get","params":{"types":[…]}}，
                //    而 steps 只收需要用户确认的写操作，这一步注定被打回重来。
                //    它这么做恰恰是因为照着上面那句「再查一次」办的 ——
                //    话是我们说的，歧义也得由我们消除
                + "🔴 这次补查要用 tool_calls 轮次做，不要写进 plan 的 steps —— "
                + "steps 里只放需要用户确认的写操作，只读工具放进去会被直接打回。"
                + "🔴 不要凭记忆写 props 的键名：键名必须与返回的 name 完全一致，"
                + "写成别的键不会报错，控件会安静地回落成默认值，"
                + "表现是「配了、保存成功了、界面上没生效」");
        return result;
    }

    // ==================== 指定控件的详情（含 props） ====================

    private Map<String, Object> detailList(List<FormDesignApi.WidgetSimpleRespDTO> widgets,
                                           List<String> types) {
        Map<String, FormDesignApi.WidgetSimpleRespDTO> byType = new LinkedHashMap<>();
        widgets.forEach(widget -> byType.put(widget.getType(), widget));

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> details = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> unusable = new ArrayList<>();
        for (String type : types) {
            FormDesignApi.WidgetSimpleRespDTO widget = byType.get(type);
            if (widget == null) {
                unknown.add(type);
            } else {
                if (!Boolean.TRUE.equals(widget.getSupported())) {
                    unusable.add(type);
                }
                details.add(toDetail(widget));
            }
        }
        result.put("widgets", details);

        // 🔴 停用控件按名查详情时要点名说死「不能用」：选型清单里已经没有它了，
        //    模型会走到这里只可能是校验报错后回来核对，此刻最需要一句明确的结论
        if (!unusable.isEmpty()) {
            result.put("已停用的类型", unusable);
            result.put("停用提示", "上述控件已停用，不能用于新表单（历史表单里可能还有）。"
                    + "请改选不带 types 参数时返回的选型清单里的控件。");
        }

        // 🔴 编错的名字要在这里就点名，而不是静默返回一个短清单。
        //    静默的话模型会以为「查到了」，继续拿着错名字去建表单
        if (!unknown.isEmpty()) {
            long usableCount = widgets.stream()
                    .filter(widget -> Boolean.TRUE.equals(widget.getSupported())).count();
            result.put("不存在的类型", unknown);
            result.put("提示", "上述类型不在白名单里，用了会被拒。"
                    + "不带 types 参数再调一次本工具可看到全部 "
                    + usableCount + " 个可用类型");
        }
        result.put("配置项要求", "props 列的是该控件写在 rule.props 里的配置项。"
                + "🔴 键名必须与 name 完全一致 —— 写成别的键不会报错，"
                + "控件会安静地回落成默认值，表现是「配了、保存成功了、界面上没生效」。"
                + "required=true 的不填该控件就没法用；"
                + "有 allowedValues 的只能从里面选；"
                + "有 source 的说明这个值要去哪儿取（比如字典编码要先用真实存在的字典），"
                + "不要凭空编一个编码。没有列出 props 的控件不需要额外配置。"
                + "🔴 标了 " + AI_AUTHORABLE_KEY + "=false 的控件<b>AI 建不出来</b>："
                + "它 required=true 的那些配置项，form_save_draft / form_update 的字段描述里"
                + "<b>没有对应的格子</b>（" + AI_AUTHORABLE_REASON_KEY + " 写着缺哪几格），"
                + "写进去不会报错、表单照样发布，运行期那一格是个空控件。"
                + "请改选不带这个标记的控件；用户点名要它时，把表单其余部分建好，"
                + "并在回复里如实说清哪一格要人到「表单设计」里配");
        return result;
    }

    private Map<String, Object> toDetail(FormDesignApi.WidgetSimpleRespDTO widget) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", widget.getType());
        item.put("label", widget.getLabel());
        item.put("group", widget.getGroup());
        item.put("supported", widget.getSupported());
        if (Boolean.TRUE.equals(widget.getDeprecated())) {
            item.put("deprecated", true);
        }
        item.put("columnTypes", widget.getColumnTypes());
        if (Boolean.TRUE.equals(widget.getSubTable())) {
            item.put("subTable", true);
        }
        // 🔴 配置项：这一块决定了「这个控件能不能真的用起来」。
        //    没有它，字典选择器只能建成一个空下拉、关联表单点不出任何东西 ——
        //    而且两者都能保存、能发布，不报任何错
        if (widget.getProps() != null && !widget.getProps().isEmpty()) {
            item.put("props", widget.getProps().stream().map(prop -> {
                Map<String, Object> spec = new LinkedHashMap<>();
                spec.put("name", prop.getName());
                spec.put("type", prop.getType());
                if (Boolean.TRUE.equals(prop.getRequired())) {
                    spec.put("required", true);
                }
                spec.put("desc", prop.getDesc());
                if (prop.getAllowedValues() != null && !prop.getAllowedValues().isEmpty()) {
                    spec.put("allowedValues", prop.getAllowedValues());
                }
                if (prop.getSource() != null) {
                    spec.put("source", prop.getSource());
                }
                if (prop.getExample() != null) {
                    spec.put("example", prop.getExample());
                }
                return spec;
            }).toList());
        }
        // 🔴 详情这一档给<b>原因</b>：走到这里的模型通常已经定下要用哪个控件了，
        //    只说「建不出来」不说「缺哪一格」，它无从判断该换成什么、也不知道该跟用户交代什么
        String gap = FormFieldFactory.checkWidgetAuthorable(widget);
        if (gap != null) {
            item.put(AI_AUTHORABLE_KEY, false);
            item.put(AI_AUTHORABLE_REASON_KEY, gap);
        }
        return item;
    }

    // ==================== 入参 ====================

    /**
     * 解析 types 参数
     *
     * <p>同时接受数组与逗号分隔的字符串：模型两种写法都会给，
     * 而因为「格式不对」去拒一次，等于白烧一轮。
     */
    private List<String> parseTypes(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        if (raw instanceof JSONArray array) {
            array.forEach(item -> addType(types, item == null ? null : item.toString()));
        } else if (raw instanceof List<?> list) {
            list.forEach(item -> addType(types, item == null ? null : item.toString()));
        } else {
            String text = raw.toString();
            // 模型有时会给一段 JSON 数组字符串而不是真数组
            if (StrUtil.startWithIgnoreCase(StrUtil.trim(text), "[")) {
                try {
                    JSONUtil.parseArray(text)
                            .forEach(item -> addType(types, item == null ? null : item.toString()));
                    return types;
                } catch (Exception ignored) {
                    // 落到下面按逗号切
                }
            }
            for (String part : text.split(",")) {
                addType(types, part);
            }
        }
        return types;
    }

    private void addType(List<String> types, String type) {
        String trimmed = StrUtil.trimToNull(StrUtil.removeAllLineBreaks(type));
        if (trimmed != null && !types.contains(trimmed)) {
            types.add(trimmed);
        }
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("widgets", "已停用的类型", "不存在的类型");
    }

    @Override
    public String paramsSchema() {
        return """
                types（可选，字符串数组）：要查看配置项（props）的控件类型，
                  如 ["select","DictSelect"]。
                  不传时返回全部<b>可用</b>控件的选型清单（type/label/group/columnTypes，
                  已停用的控件不在其中），但不含 props —— 全量带 props 有两万多字符，会撑爆预算。
                  推荐用法：先不带参数拿全量清单选控件，再带 types 查这几个控件的配置项。
                  传了不存在的类型会在返回里点名，不会静默忽略""";
    }

}
