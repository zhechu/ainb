package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.tool.impl.form.FormSaveDraftTool;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code form_save_draft} 的参数校验
 *
 * <h3>为什么这里只做「浅校验」</h3>
 * 完整的 schema 校验（fieldMapping 完整性、列类型相容性）要先把简化描述<b>展开成</b>
 * form-create rule 才能做，而展开逻辑在工具里。在这里重写一份展开逻辑，
 * 两份必然漂移 —— 漂移的表现是「规划时说没问题、执行时报错」，
 * 那正好是把校验前移想避免的事。
 *
 * <p>所以这里只拦<b>不用展开就能看出来</b>的问题：必填参数缺失、
 * 字段定义明显不完整、控件类型不在白名单里。
 * 其余深校验交给工具执行时的 {@code FormSchemaValidator}。
 *
 * <h3>🔴 控件白名单必须在规划期就查</h3>
 * 它原先只在执行期查，后果是模型幻觉出的控件名（实测踩过 {@code dateTimePicker} ——
 * 契约里日期时间不是独立控件，要用 {@code datePicker} 配 {@code valueFormat}）
 * 能顺利通过规划、生成计划卡、被用户确认，直到执行那一步才炸。
 * 而执行阶段<b>没有</b>回灌重规划的循环，错误只能原样甩给用户 ——
 * 用户看到一张「点了确认却失败」的卡片，AI 也失去了自修正的机会。
 * 在规划期拦下来，错误会回灌给模型，它改对了用户才看得到卡片。
 *
 * <p>这一条不算「重写展开逻辑」：判断控件类型在不在白名单，
 * 只需要 {@code widget} 这个字符串本身，查的还是同一份 {@code getWidgetContracts()}。
 *
 * @author 走进新时代
 */
@Component
public class FormSaveDraftParamValidator implements AgentToolParamValidator {

    /**
     * 一次最多给几个候选控件名
     *
     * 给全量白名单等于没给 —— 60 多个类型塞进反馈里，
     * 模型还是得自己挑，而这一段的 token 是每轮都要重付的。
     */
    private static final int SUGGESTION_LIMIT = 3;

    @Resource
    private FormDesignApi formDesignApi;

    @Override
    public boolean supports(String toolName) {
        return "form_save_draft".equals(toolName);
    }

    @Override
    public AgentValidateResult validate(Map<String, Object> params) {
        AgentValidateResult result = new AgentValidateResult();
        if (params == null || params.isEmpty()) {
            result.addError("缺少参数");
            return result;
        }
        requireText(params, FormSaveDraftTool.PARAM_NAME, "表单名称", result);
        String code = requireText(params, FormSaveDraftTool.PARAM_CODE, "表单编码", result);
        requireText(params, FormSaveDraftTool.PARAM_MAIN_TABLE, "主表名", result);

        if (code != null && !code.matches("^[a-z][a-z0-9_]*$")) {
            result.addError("表单编码 %s 不合规，应为全小写下划线形式，如 %s",
                    code, toSnakeCase(code));
        }

        Object fields = params.get(FormSaveDraftTool.PARAM_FIELDS);
        if (fields == null) {
            result.addError("缺少 fields（表单字段列表）");
            return result;
        }
        try {
            JSONArray array = JSONUtil.parseArray(fields);
            if (array.isEmpty()) {
                result.addError("fields 是空的，表单至少要有一个字段");
            }
            Map<String, FormDesignApi.WidgetSimpleRespDTO> widgets = loadWidgets();
            for (Object item : array) {
                if (item instanceof JSONObject field) {
                    validateField(field, widgets, result);
                }
            }
        } catch (Exception ex) {
            result.addError("fields 不是合法的数组：%s", ex.getMessage());
        }
        return result;
    }

    private Map<String, FormDesignApi.WidgetSimpleRespDTO> loadWidgets() {
        Map<String, FormDesignApi.WidgetSimpleRespDTO> map = new HashMap<>();
        for (FormDesignApi.WidgetSimpleRespDTO widget : formDesignApi.getWidgetContracts()) {
            map.put(widget.getType(), widget);
        }
        return map;
    }

    private void validateField(JSONObject field,
                               Map<String, FormDesignApi.WidgetSimpleRespDTO> widgets,
                               AgentValidateResult result) {
        String name = field.getStr("field");
        if (StrUtil.isBlank(name)) {
            result.addError("有字段缺少 field（字段标识）");
            return;
        }
        String widget = field.getStr("widget");
        if (StrUtil.isBlank(widget)) {
            result.addError("字段 %s 缺少 widget（控件类型），可用类型请调 widget_contract_get", name);
        } else {
            validateWidget(name, widget, widgets, result);
        }
        if (StrUtil.isBlank(field.getStr("title"))) {
            result.addError("字段 %s 缺少 title（中文标签）", name);
        }
        if (StrUtil.isBlank(field.getStr("jdbcType"))) {
            result.addError("字段 %s 缺少 jdbcType", name);
        }
        // 🔴 这条在规划阶段就拦：tenant_id 被表单字段覆盖 = 跨租户写入
        String column = StrUtil.blankToDefault(field.getStr("column"), name);
        if ("tenant_id".equalsIgnoreCase(column)) {
            result.addError("字段 %s 映射到了 tenant_id —— 那是后端按登录上下文维护的列，"
                    + "被表单值覆盖会造成跨租户写入，绝对禁止", name);
        }
    }

    /**
     * 控件类型必须落在契约白名单里
     *
     * <p>与 {@code FormSchemaValidator} 的判定保持一致：不存在与已停用都硬拦，
     * 已废弃只警告。这里提前到规划期做，是为了让错误还能回灌给模型自修正。
     */
    private void validateWidget(String name, String widget,
                                Map<String, FormDesignApi.WidgetSimpleRespDTO> widgets,
                                AgentValidateResult result) {
        // 与 FormSchemaValidator / FormSaveDraftTool 同一口径归一：① 大小写变体（Input→input）；
        // ② 命不中契约再查语义别名（date→datePicker）。别名表复用 FormSchemaValidator.aliasCanonical
        // 这个唯一真源，不在这里另写一份导致两处漂移。不为这类差异白烧一轮模型自修正
        String canonical = widget.trim();
        for (Map.Entry<String, FormDesignApi.WidgetSimpleRespDTO> entry : widgets.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(canonical)) {
                canonical = entry.getKey();
                break;
            }
        }
        if (!widgets.containsKey(canonical)) {
            String aliased = FormSchemaValidator.aliasCanonical(widget);
            if (aliased != null) {
                canonical = aliased;
            }
        }
        FormDesignApi.WidgetSimpleRespDTO contract = widgets.get(canonical);
        if (contract == null) {
            result.addError("字段 %s 的控件类型 %s 不存在。%s"
                            + "完整清单请调 widget_contract_get 查询，不要凭记忆写控件类型",
                    name, widget, suggestSimilar(widget, widgets));
        } else if (!Boolean.TRUE.equals(contract.getSupported())) {
            result.addError("字段 %s 的控件类型 %s 已停用，必须换用其它控件。%s",
                    name, widget, suggestSimilar(widget, widgets));
        } else if (Boolean.TRUE.equals(contract.getDeprecated())) {
            result.addWarning("字段 %s 用的控件 %s 已废弃，新表单建议换用其它控件", name, widget);
        }
    }

    /**
     * 给出最接近的可用控件名
     *
     * <p>只说「不存在，去查白名单」的话，模型下一轮很可能照着记忆再编一个 ——
     * 它幻觉出 {@code dateTimePicker} 正是因为「日期时间」在契约里不是一个独立控件。
     * 把候选直接摆在它面前，一轮就能改对；而每多一轮都是一次真实的模型调用与计费
     *（{@code AgentValidateResult} 的类注释说的就是这件事）。
     *
     * <p>相似度用 camelCase 分词后的词交集来算：{@code dateTimePicker} 拆成
     * date/time/picker，与 {@code datePicker}（date/picker）交集 2、
     * 与 {@code timePicker}（time/picker）交集 2、与 {@code dateRange} 交集 1 ——
     * 正好把该推荐的排在前面。不引入编辑距离那类算法，
     * 分词交集在这个场景下已经够准，而且行为一眼能看懂。
     */
    private String suggestSimilar(String widget,
                                  Map<String, FormDesignApi.WidgetSimpleRespDTO> widgets) {
        Set<String> words = splitCamelCase(widget);
        List<String> candidates = widgets.values().stream()
                .filter(item -> Boolean.TRUE.equals(item.getSupported()))
                .filter(item -> !Boolean.TRUE.equals(item.getDeprecated()))
                .map(item -> Map.entry(item.getType(),
                        countCommonWords(words, splitCamelCase(item.getType()))))
                .filter(entry -> entry.getValue() > 0)
                // 同分时按类型名排序：Map 的遍历顺序不保证，不排一下反馈文本会随机变，
                // 既让模型困惑，也让单元测试时对时错
                .sorted(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(SUGGESTION_LIMIT)
                .map(Map.Entry::getKey)
                .toList();
        if (candidates.isEmpty()) {
            return "";
        }
        return "最接近的可用类型：" + String.join("、", candidates) + "。";
    }

    private static Set<String> splitCamelCase(String type) {
        Set<String> words = new HashSet<>();
        for (String segment : type.split("[^a-zA-Z0-9]+")) {
            for (String word : segment.split("(?=[A-Z])")) {
                String lower = word.toLowerCase(Locale.ROOT);
                if (!lower.isEmpty()) {
                    words.add(lower);
                }
            }
        }
        return words;
    }

    private static int countCommonWords(Set<String> left, Set<String> right) {
        int count = 0;
        for (String word : left) {
            if (right.contains(word)) {
                count++;
            }
        }
        return count;
    }

    private String requireText(Map<String, Object> params, String key, String label,
                               AgentValidateResult result) {
        Object value = params.get(key);
        if (value == null || StrUtil.isBlank(value.toString())) {
            result.addError("缺少 %s（%s）", key, label);
            return null;
        }
        return value.toString().trim();
    }

    private static String toSnakeCase(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

}
