package cn.enlev.ainb.module.infra.framework.formdesign.core.logic;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联动规则的服务端复校验
 *
 * <h3>它防的是什么</h3>
 *
 * 前端算出来的「必填 / 只读 / 隐藏」只是体验。绕过界面直接构造一个提交请求，就能：
 * <ul>
 *   <li>给只读字段塞值 —— 比如把已锁定的价格改掉</li>
 *   <li>跳过必填字段 —— 比如金额超限时本该填的审批说明</li>
 *   <li>给隐藏字段塞值 —— 前端隐藏不等于服务端放行</li>
 * </ul>
 *
 * 所以提交时服务端要用【同一套规则】重算一遍，据此丢弃不可写字段的提交值、
 * 拒绝必填字段的空值。前后端求值器的一致性由 logic-cases.json 保证。
 *
 * <h3>为什么是「丢弃」而不是「报错」</h3>
 *
 * 不可写字段带了值，最常见的原因不是攻击，而是前端把整个表单对象原样提交了 ——
 * 里面自然包含那些只读字段的当前值。对这种情况报错会让正常操作失败。
 * 丢弃既安全又不打扰用户：值不会进库，用户也不会看到莫名的报错。
 *
 * 必填缺失则必须报错 —— 那是真的缺数据，静默放过会留下不完整的业务记录。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormLogicGuard {

    @Resource
    private LogicRuleEvaluator evaluator;

    /**
     * 复校验并净化提交数据
     *
     * @param logicRulesJson 表单的联动规则 JSON
     * @param formFieldsJson 表单字段规则 JSON，用于取字段的基础必填状态与标题
     * @param submitted      前端提交的数据
     * @return 净化结果
     */
    public GuardResult guard(String logicRulesJson, String formFieldsJson,
                             Map<String, Object> submitted) {
        Map<String, Object> data = submitted != null
                ? new LinkedHashMap<>(submitted) : new LinkedHashMap<>();

        List<LogicRule> rules = parseRules(logicRulesJson);
        Map<String, FieldMeta> fieldMetas = parseFieldMetas(formFieldsJson);

        if (rules.isEmpty()) {
            // 没有联动规则时仍要校验基础必填 —— 那来自控件自身的 $required，
            // 同样是前端可绕过的
            return checkRequiredOnly(data, fieldMetas);
        }

        // 行作用域规则的结果先攒着，最后并入总结果
        List<String> rowScopedErrors = new ArrayList<>();
        checkRowScopedRules(rules, data, rowScopedErrors);

        // 主流程只处理主表作用域的规则。行作用域规则的字段名指的是明细列，
        // 混进来会把「明细里的 remark」当成「主表的 remark」去判定 ——
        // 表现为主表某个同名字段莫名其妙变成必填
        List<LogicRule> mainScopeRules = rules.stream().filter(r -> r != null && !r.isRowScoped()).toList();

        // 基础状态：可见可编辑，必填取控件的 $required
        Map<String, LogicRuleEvaluator.FieldState> baseState = new LinkedHashMap<>();
        fieldMetas.forEach((field, meta) ->
                baseState.put(field, LogicRuleEvaluator.FieldState.of(true, true, meta.isRequired())));

        // 用 SUBMIT 时机求值。规则里配 CHANGE 的不会在这里生效 ——
        // 那是录入过程中的交互规则，提交时的约束应当由 SUBMIT 规则表达。
        // 但注意：CHANGE 规则设置的「必填/只读」在提交时不复现，
        // 因此设计器会提示用户把需要服务端兜住的约束配成 SUBMIT
        LogicRuleEvaluator.EvaluationResult result =
                evaluator.evaluate(mainScopeRules, LogicEnums.Trigger.SUBMIT, data, baseState);

        GuardResult guardResult = new GuardResult();
        Map<String, Object> sanitized = new LinkedHashMap<>(result.getData());

        // 1. 丢弃不可写字段的提交值
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String field = entry.getKey();
            LogicRuleEvaluator.FieldState state = result.stateOf(field);
            boolean writable = state.isVisible() && state.isEditable();
            if (writable) {
                continue;
            }
            // 规则本身给该字段赋过值的情况要放过：那是服务端算出来的值，不是前端提交的。
            // 判据是「求值后的值与提交值不同」—— 说明这个值是规则写的
            Object submittedValue = entry.getValue();
            Object evaluatedValue = result.getData().get(field);
            boolean writtenByRule = !java.util.Objects.equals(submittedValue, evaluatedValue);
            if (writtenByRule) {
                continue;
            }
            sanitized.remove(field);
            guardResult.getDiscardedFields().add(field);
        }

        // 2. 必填校验。用净化后的数据来判 —— 一个字段既不可写又必填时，
        //    该报的是「配置矛盾」，见下面的死锁检查
        result.getState().forEach((field, state) -> {
            if (!state.isRequired()) {
                return;
            }
            if (!state.isVisible() || !state.isEditable()) {
                // 逻辑死锁：字段被规则设成必填，同时又被设成隐藏或只读，
                // 用户没有任何途径填它。这是规则配错了，不是用户的问题，
                // 报出来比让提交永远失败要好
                guardResult.getConflictFields().add(field);
                return;
            }
            if (evaluator.isEmpty(sanitized.get(field))) {
                guardResult.getMissingRequiredFields().add(fieldLabel(fieldMetas, field));
            }
        });

        guardResult.getMissingRequiredFields().addAll(rowScopedErrors);
        guardResult.setData(sanitized);
        guardResult.setStable(result.isStable());
        if (!result.isStable()) {
            log.warn("[guard][联动规则未收敛，本次复校验结果可能不完整]");
        }
        if (!guardResult.getDiscardedFields().isEmpty()) {
            // 记 info 而不是 warn：前端把整个表单对象原样提交是常态，不是异常
            log.info("[guard][丢弃了 {} 个不可写字段的提交值：{}]",
                    guardResult.getDiscardedFields().size(), guardResult.getDiscardedFields());
        }
        return guardResult;
    }

    /**
     * 校验行作用域规则
     *
     * 这类规则作用在子表明细行上：对该子表的【每一行】各求值一次，
     * 条件与动作里的字段名指的都是明细列。
     *
     * 为什么需要它：明细里「数量大于 100 时必须填备注」这类约束，用主表作用域的规则
     * 表达不了 —— 那样只能判断整个子表数组，拿不到某一行的数量。
     *
     * 目前只处理「必填」这一种动作。显隐与只读是纯前端的交互表现，明细行的值
     * 已经由 SubTableGuard 逐行校验过必填，这里补的是「由规则动态决定的必填」。
     *
     * @param errors 收集到的错误消息，带行号
     */
    @SuppressWarnings("unchecked")
    void checkRowScopedRules(List<LogicRule> rules, Map<String, Object> data, List<String> errors) {
        for (LogicRule rule : rules) {
            if (rule == null || !rule.isEnabled() || !rule.isRowScoped()) {
                continue;
            }
            // 只在提交时机生效。CHANGE 是录入过程中的交互规则
            if (rule.getTrigger() != null && rule.getTrigger() != LogicEnums.Trigger.SUBMIT) {
                continue;
            }
            Object raw = data == null ? null : data.get(rule.getRowScope());
            if (!(raw instanceof List<?> rows)) {
                continue;
            }
            for (int i = 0; i < rows.size(); i++) {
                if (!(rows.get(i) instanceof Map<?, ?> rowMap)) {
                    continue;
                }
                Map<String, Object> rowData = (Map<String, Object>) rowMap;
                // 用行数据作为求值上下文 —— 字段名直接对应明细列
                LogicRuleEvaluator.EvaluationResult rowResult = evaluator.evaluate(
                        List.of(rule), LogicEnums.Trigger.SUBMIT, rowData, new LinkedHashMap<>());

                int rowNumber = i + 1;
                rowResult.getState().forEach((field, state) -> {
                    if (state.isRequired() && evaluator.isEmpty(rowData.get(field))) {
                        // 行号从 1 开始，与界面显示一致
                        errors.add(String.format("明细第 %d 行的「%s」按联动规则是必填的",
                                rowNumber, field));
                    }
                });
            }
        }
    }

    /**
     * 没有联动规则时只校验基础必填
     */
    private GuardResult checkRequiredOnly(Map<String, Object> data, Map<String, FieldMeta> metas) {
        GuardResult result = new GuardResult();
        result.setData(data);
        result.setStable(true);
        metas.forEach((field, meta) -> {
            if (meta.isRequired() && evaluator.isEmpty(data.get(field))) {
                result.getMissingRequiredFields().add(meta.labelOr(field));
            }
        });
        return result;
    }

    // ==================== 解析 ====================

    /**
     * 解析联动规则
     *
     * 解析失败返回空列表而不是抛异常：规则 JSON 可能是旧版本写的或被手工改坏，
     * 让整个提交流程失败的代价过大。但要记 warn —— 静默跳过全部规则等于
     * 悄悄放弃了服务端约束，必须留下痕迹。
     *
     * 公开而非包级可见：发布校验（在 service 包）也要用它解析规则做环检测，
     * 那边不该为此再写一份解析逻辑。
     */
    public List<LogicRule> parseRules(String logicRulesJson) {
        if (StrUtil.isBlank(logicRulesJson)) {
            return Collections.emptyList();
        }
        List<LogicRule> rules = JsonUtils.parseObjectQuietly(logicRulesJson,
                new TypeReference<List<LogicRule>>() {});
        if (rules == null) {
            log.warn("[parseRules][联动规则 JSON 解析失败，本次提交将跳过全部规则约束]");
            return Collections.emptyList();
        }
        return rules;
    }

    /**
     * 从表单字段规则里提取每个字段的基础元信息
     *
     * 要递归：字段可能被放在栅格、标签页等布局容器里。
     * 子表内部字段不在这里处理 —— 子表的明细行有自己的校验路径。
     *
     * 公开而非包级可见：发布校验（在 service 包）要用它拿到表单的字段清单，
     * 核对公式引用的字段是否存在。那边不该为此再写一份解析逻辑。
     */
    public Map<String, FieldMeta> parseFieldMetas(String formFieldsJson) {
        Map<String, FieldMeta> result = new LinkedHashMap<>();
        List<String> rules = FormFieldsCodec.read(formFieldsJson);
        for (String rule : rules) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collectFieldMeta(node, result);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void collectFieldMeta(Map<String, Object> node, Map<String, FieldMeta> result) {
        if (node.get("field") instanceof String field && StrUtil.isNotBlank(field)) {
            FieldMeta meta = new FieldMeta();
            meta.setField(field);
            meta.setTitle(node.get("title") instanceof String t ? t : null);
            meta.setRequired(Boolean.TRUE.equals(node.get("$required")));
            result.put(field, meta);
        }
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collectFieldMeta((Map<String, Object>) childMap, result);
                }
            }
        }
    }

    private String fieldLabel(Map<String, FieldMeta> metas, String field) {
        FieldMeta meta = metas.get(field);
        return meta != null ? meta.labelOr(field) : field;
    }

    // ==================== 模型 ====================

    /**
     * 字段的基础元信息
     */
    @Data
    public static class FieldMeta {
        private String field;
        private String title;
        private boolean required;

        /**
         * 报错时用的展示名。有标题用标题 —— 报字段 ID 用户看不懂
         */
        public String labelOr(String fallback) {
            return StrUtil.isNotBlank(title) ? title : fallback;
        }
    }

    /**
     * 复校验结果
     */
    @Data
    public static class GuardResult {

        /**
         * 净化后的数据。调用方应当用这份写库，而不是原始提交数据
         */
        private Map<String, Object> data = new LinkedHashMap<>();
        /**
         * 被丢弃的不可写字段
         */
        private final List<String> discardedFields = new ArrayList<>();
        /**
         * 缺失的必填字段（展示名）
         */
        private final List<String> missingRequiredFields = new ArrayList<>();
        /**
         * 配置矛盾的字段：既必填又不可填
         */
        private final List<String> conflictFields = new ArrayList<>();
        /**
         * 规则是否收敛
         */
        private boolean stable = true;

        /**
         * 是否可以放行
         */
        public boolean isPassed() {
            return missingRequiredFields.isEmpty() && conflictFields.isEmpty();
        }

    }

}
