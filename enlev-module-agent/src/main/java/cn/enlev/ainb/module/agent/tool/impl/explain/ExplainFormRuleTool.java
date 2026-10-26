package cn.enlev.ainb.module.agent.tool.impl.explain;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignSaveReqDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：解释表单的联动与校验规则
 *
 * <h3>为什么需要它</h3>
 * 「为什么我填了金额，说明那一栏没有变成必填」是这个平台上最高频的问题之一。
 * 要回答它，得把联动规则那份 JSON 读懂：触发时机是什么、条件怎么组合、
 * 满足和不满足时分别做什么。规则多起来之后，人在设计器里一条条点开看也很费劲。
 *
 * <p>这个工具把规则翻译成「什么时候、满足什么、就会怎样」的句子。
 *
 * <h3>它<b>不</b>回答「为什么没生效」</h3>
 * 那是 {@code diagnose_not_working} 的事。这里只负责如实说明<b>配的是什么</b>——
 * 两件事混在一个工具里，输出会变成一半解释一半猜测，而猜测部分最容易被当真。
 * 不过规则本身被禁用、或者引用了不存在的字段，这里会直接标出来，
 * 因为那属于「配的是什么」的一部分。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "explain_form_rule",
        displayName = "解读表单规则",
        groupName = "诊断排错",
        description = "用中文解释一张表单上配的联动规则与校验规则："
                + "什么时候触发、满足什么条件、会让哪些字段显示/隐藏/必填/赋值。"
                + "用户问「这张表单有哪些规则」「金额填大了会怎样」时用它。"
                + "注意它只说明配的是什么，不判断为什么不生效 —— 那个用 diagnose_not_working",
        permission = "infra:form-design:query",
        risk = RiskLevel.READ_ONLY)
public class ExplainFormRuleTool implements AgentToolFunction {

    public static final String PARAM_CODE = "code";

    /** 触发时机 */
    private static final Map<String, String> TRIGGERS = Map.of(
            "LOAD", "表单打开时",
            "CHANGE", "相关字段被修改时",
            "SUBMIT", "提交时");

    /** 条件运算符 */
    private static final Map<String, String> OPERATORS = Map.ofEntries(
            Map.entry("EQ", "等于"), Map.entry("NE", "不等于"),
            Map.entry("GT", "大于"), Map.entry("GE", "大于等于"),
            Map.entry("LT", "小于"), Map.entry("LE", "小于等于"),
            Map.entry("IN", "属于"), Map.entry("NOT_IN", "不属于"),
            Map.entry("CONTAIN", "包含"), Map.entry("NOT_CONTAIN", "不包含"),
            Map.entry("EMPTY", "为空"), Map.entry("NOT_EMPTY", "不为空"));

    /** 动作 */
    private static final Map<String, String> ACTIONS = Map.ofEntries(
            Map.entry("SHOW", "显示"), Map.entry("HIDE", "隐藏"),
            Map.entry("REQUIRE", "设为必填"), Map.entry("OPTIONAL", "设为选填"),
            Map.entry("ENABLE", "设为可编辑"), Map.entry("DISABLE", "设为只读"),
            Map.entry("SET_VALUE", "赋值"), Map.entry("CLEAR_VALUE", "清空"));

    @Resource
    private FormDesignApi formDesignApi;

    @Override
    public Object execute(Map<String, Object> params) {
        Object code = params.get(PARAM_CODE);
        if (code == null || StrUtil.isBlank(code.toString())) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "explain_form_rule",
                    "缺少 code（表单编码）");
        }
        FormDesignSaveReqDTO form = formDesignApi.getFormDesignByCode(code.toString().trim());
        if (form == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "explain_form_rule",
                    "找不到表单「" + code + "」，先用 form_list 确认准确的编码");
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", form.getCode());
        resp.put("name", form.getName());

        List<String> logic = explainLogicRules(form.getLogicRules());
        List<String> validate = explainValidateRules(form.getValidateRules());
        resp.put("logicRules", logic);
        resp.put("validateRules", validate);

        if (logic.isEmpty() && validate.isEmpty()) {
            resp.put("summary", "这张表单没有配置任何联动规则或校验规则");
        } else {
            resp.put("summary", "共 " + logic.size() + " 条联动规则、"
                    + validate.size() + " 条校验规则");
        }
        return resp;
    }

    // ==================== 联动规则 ====================

    private List<String> explainLogicRules(String json) {
        List<String> result = new ArrayList<>();
        for (JSONObject rule : parseArray(json)) {
            StringBuilder sb = new StringBuilder();
            sb.append("「").append(StrUtil.blankToDefault(rule.getStr("name"), "未命名规则"))
                    .append("」：");
            // 禁用的规则要标出来 —— 「配了但不生效」里最平凡的一种原因就是它被关了
            if (Boolean.FALSE.equals(rule.getBool("enabled"))) {
                sb.append("【已禁用，当前不会执行】");
            }
            sb.append(TRIGGERS.getOrDefault(rule.getStr("trigger"),
                    "触发时机 " + rule.getStr("trigger")));
            sb.append("，若 ").append(describeCondition(rule.getJSONObject("when")));
            sb.append("，则 ").append(describeActions(rule.getJSONArray("then")));

            JSONArray otherwise = rule.getJSONArray("otherwise");
            if (otherwise != null && !otherwise.isEmpty()) {
                sb.append("；否则 ").append(describeActions(otherwise));
            }
            result.add(sb.toString());
        }
        return result;
    }

    // ==================== 校验规则 ====================

    private List<String> explainValidateRules(String json) {
        List<String> result = new ArrayList<>();
        for (JSONObject rule : parseArray(json)) {
            StringBuilder sb = new StringBuilder();
            sb.append("「").append(StrUtil.blankToDefault(rule.getStr("name"), "未命名规则"))
                    .append("」：要求 ").append(describeCondition(rule.getJSONObject("must")));
            String message = rule.getStr("message");
            if (StrUtil.isNotBlank(message)) {
                sb.append("，不满足时提示「").append(message).append("」");
            } else {
                // 没有提示语的校验规则，用户被拦住却不知道为什么
                sb.append("，⚠️ 但没有配置提示语，不满足时用户不知道哪里错了");
            }
            result.add(sb.toString());
        }
        return result;
    }

    // ==================== 条件与动作 ====================

    private String describeCondition(JSONObject condition) {
        if (condition == null) {
            return "（没有条件，恒成立）";
        }
        JSONArray items = condition.getJSONArray("items");
        if (items == null || items.isEmpty()) {
            return "（没有条件，恒成立）";
        }
        List<String> parts = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof JSONObject cond)) {
                continue;
            }
            String field = cond.getStr("field");
            String operator = OPERATORS.getOrDefault(cond.getStr("operator"),
                    cond.getStr("operator"));
            Object value = cond.get("value");
            // valueType=FIELD 表示右值是另一个字段，不是字面量 ——
            // 不区分的话会把「售价 小于等于 标价」解释成「售价 小于等于 字符串 list_price」
            String right = Objects.equals(cond.getStr("valueType"), "FIELD")
                    ? "字段「" + value + "」的值"
                    : String.valueOf(value);
            parts.add(StrUtil.isBlank(right) || "null".equals(right)
                    ? field + " " + operator
                    : field + " " + operator + " " + right);
        }
        String joiner = Objects.equals(condition.getStr("logic"), "OR") ? " 或 " : " 且 ";
        return String.join(joiner, parts);
    }

    private String describeActions(JSONArray actions) {
        if (actions == null || actions.isEmpty()) {
            return "（没有配置动作，这条规则什么也不会做）";
        }
        List<String> parts = new ArrayList<>();
        for (Object item : actions) {
            if (!(item instanceof JSONObject action)) {
                continue;
            }
            String type = ACTIONS.getOrDefault(action.getStr("type"), action.getStr("type"));
            String target = action.getStr("target");
            Object value = action.get("value");
            parts.add(value == null
                    ? "把「" + target + "」" + type
                    : "把「" + target + "」" + type + "为 " + value);
        }
        return String.join("、", parts);
    }

    private List<JSONObject> parseArray(String json) {
        List<JSONObject> result = new ArrayList<>();
        if (StrUtil.isBlank(json) || "null".equals(json)) {
            return result;
        }
        try {
            for (Object item : JSONUtil.parseArray(json)) {
                if (item instanceof JSONObject obj) {
                    result.add(obj);
                }
            }
        } catch (Exception ex) {
            // 解析不了就当没有，不要让整个解释失败 —— 另一半规则还是有价值的
            return result;
        }
        return result;
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("logicRules", "validateRules");
    }

    @Override
    public String paramsSchema() {
        return "code（必填，字符串）：表单编码，如 test_demo_verify_expense，可从 form_list 拿";
    }

}
