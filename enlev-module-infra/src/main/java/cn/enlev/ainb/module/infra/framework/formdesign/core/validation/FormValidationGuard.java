package cn.enlev.ainb.module.infra.framework.formdesign.core.validation;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormErrorText;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicEnums;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicRule;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicRuleEvaluator;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 字段格式与跨字段校验的服务端复校验
 *
 * <h3>它防的是什么</h3>
 *
 * 前端的格式校验只是体验。构造一个请求直接提交，手机号可以是「abc」、
 * 结束日期可以早于开始日期。这类脏数据的特点是**当时不报错，用后患无穷**：
 * 一个存进库的「abc」手机号，直到某天群发短信时才暴露，那时已经没法追溯是哪次提交写的。
 *
 * 所以格式校验必须在服务端再跑一遍，用的是与前端同一份正则库
 * （{@link ValidationPatternRegistry}）与同一套条件求值器（{@link LogicRuleEvaluator}）。
 *
 * <h3>空值不校验格式</h3>
 *
 * 「填了就必须是手机号」与「必须填」是两件事。空值一律放过，必填由联动规则那条路径管
 * （{@code FormLogicGuard}）。混在一起会让「非必填 + 有格式」的字段永远填不了空 ——
 * 用户清空它反而提交不了。
 *
 * <h3>非标量值不校验格式</h3>
 *
 * 多选、上传这类控件的值是数组或对象，对它做正则匹配得先决定「怎么拼成字符串」，
 * 而任何一种拼法都是我们自己编的语义。这里直接跳过并记日志：
 * 给多选框配正则本身就是配错了，该在设计器里拦，而不是在这里发明一套语义。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormValidationGuard {

    @Resource
    private ValidationPatternRegistry patternRegistry;
    @Resource
    private LogicRuleEvaluator logicRuleEvaluator;

    /**
     * 校验
     *
     * @param formFieldsJson    表单字段规则 JSON，字段级格式规则来自控件的 props.validateRules
     * @param validateRulesJson 跨字段校验规则 JSON（infra_form_design.validate_rules）
     * @param data              提交数据。本方法只读
     * @return 校验结果
     */
    public ValidationResult validate(String formFieldsJson, String validateRulesJson,
                                     Map<String, Object> data) {
        ValidationResult result = new ValidationResult();
        Map<String, Object> row = data != null ? data : Collections.emptyMap();

        // 1. 字段级格式校验（含子表明细列）
        for (ValidatedField field : parseValidatedFields(formFieldsJson)) {
            if (field.getSubField() == null) {
                validateMainField(field, row, result);
            } else {
                validateSubField(field, row, result);
            }
        }

        // 2. 跨字段校验。放在字段级之后：字段自己的格式都不对时，
        //    再报一条「结束日期不能早于开始日期」只会让用户更困惑
        for (CrossFieldRule rule : parseCrossFieldRules(validateRulesJson)) {
            validateCrossField(rule, row, result);
        }
        return result;
    }

    // ==================== 字段级 ====================

    private void validateMainField(ValidatedField field, Map<String, Object> data,
                                   ValidationResult result) {
        Object value = data.get(field.getField());
        String text = toScalarText(field, value);
        if (text == null) {
            return;
        }
        for (FieldValidationRule rule : field.getRules()) {
            String failure = check(rule, text, field.label());
            if (failure != null) {
                result.addError(failure);
                // 一个字段只报第一条不通过的规则：同一个值同时不满足
                // 「是手机号」和「以 138 开头」时，报两条对用户没有额外信息
                return;
            }
        }
    }

    /**
     * 校验子表明细列
     *
     * 同一列的多行错误合并成一条，行号从 1 开始 —— 一张 30 行的明细里说
     * 「手机号格式不对」，用户不知道是哪一行。
     */
    private void validateSubField(ValidatedField field, Map<String, Object> data,
                                  ValidationResult result) {
        List<Map<String, Object>> rows = toRows(data.get(field.getSubField()));
        if (rows.isEmpty()) {
            return;
        }
        for (FieldValidationRule rule : field.getRules()) {
            List<Integer> badRows = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                String text = toScalarText(field, rows.get(i).get(field.getField()));
                if (text != null && check(rule, text, field.label()) != null) {
                    badRows.add(i + 1);
                }
            }
            if (badRows.isEmpty()) {
                continue;
            }
            result.addError(String.format("「%s」第 %s 的「%s」%s",
                    field.subLabel(), FormErrorText.rows(badRows), field.label(),
                    resolveMessage(rule)));
        }
    }

    /**
     * 校验一条字段级规则
     *
     * @return 不通过时返回错误文案；通过或规则不可用返回 null
     */
    private String check(FieldValidationRule rule, String text, String label) {
        if (!rule.isEnabled()) {
            return null;
        }
        if (!rule.isUsable()) {
            // 配置没写完（选了内置库却没选具体哪条，或选了自定义却没填正则）。
            // 跳过并记日志：为一条没配好的校验让整张表单提交不了，代价过大。
            // 但不能静默 —— 配的人以为它在生效
            log.warn("[check][字段({})的校验规则配置不完整，本次跳过：type={}]",
                    label, rule.getType());
            return null;
        }
        boolean passed = rule.resolveType() == FieldValidationRule.Type.BUILTIN
                ? checkBuiltin(rule, text, label)
                : checkCustom(rule, text);
        return passed ? null : String.format("「%s」%s", label, resolveMessage(rule));
    }

    private boolean checkBuiltin(FieldValidationRule rule, String text, String label) {
        ValidationPatternRegistry.BuiltinPattern builtin = patternRegistry.get(rule.getPatternKey());
        if (builtin == null) {
            // 引用了一个不存在的内置正则 key。可能是正则库删掉了某条，
            // 也可能是配置里拼错了。放过 + 记日志：拦下来的话，
            // 一次正则库调整会让所有引用它的表单集体提交失败
            log.warn("[checkBuiltin][字段({})引用的内置正则({})不存在，本次跳过校验]",
                    label, rule.getPatternKey());
            return true;
        }
        return builtin.matches(text);
    }

    private boolean checkCustom(FieldValidationRule rule, String text) {
        Pattern pattern = patternRegistry.compileCustom(rule.getPattern());
        // 正则语法错误时 compileCustom 返回 null 并已记 error 日志。
        // 这里放过：用户配错了正则，不该表现为他的输入被拒绝
        return pattern == null || pattern.matcher(text).matches();
    }

    /**
     * 取错误提示：字段上配了自定义提示用它，否则用内置正则自带的
     */
    private String resolveMessage(FieldValidationRule rule) {
        if (StrUtil.isNotBlank(rule.getMessage())) {
            return rule.getMessage();
        }
        ValidationPatternRegistry.BuiltinPattern builtin = patternRegistry.get(rule.getPatternKey());
        if (builtin != null && StrUtil.isNotBlank(builtin.getMessage())) {
            return builtin.getMessage();
        }
        return "格式不正确";
    }

    /**
     * 取标量文本
     *
     * @return 可校验的文本；空值与非标量值返回 null（调用方跳过）
     */
    private String toScalarText(ValidatedField field, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> || value instanceof Map<?, ?> || value instanceof Object[]) {
            log.debug("[toScalarText][字段({})的值不是标量，跳过格式校验]", field.label());
            return null;
        }
        String text = String.valueOf(value);
        // 空串等同于没填。这里不 trim 后判空 —— 纯空格是不是「没填」取决于业务，
        // 而正则本身可以表达（^\\S+$），不该由校验器替用户决定
        return text.isEmpty() ? null : text;
    }

    // ==================== 跨字段 ====================

    private void validateCrossField(CrossFieldRule rule, Map<String, Object> data,
                                    ValidationResult result) {
        if (!rule.isEnabled()) {
            return;
        }
        if (!rule.isUsable()) {
            log.warn("[validateCrossField][跨字段校验规则({})缺少断言或提示，本次跳过]",
                    StrUtil.blankToDefault(rule.getName(), rule.getId()));
            return;
        }
        // when 为空视为恒真，即「这条约束永远要检查」
        if (!logicRuleEvaluator.matches(rule.getWhen(), data)) {
            return;
        }
        // 断言里有还没填的操作数时不检查，见 isAssertionApplicable
        if (!isAssertionApplicable(rule.getMust(), data)) {
            return;
        }
        if (logicRuleEvaluator.matches(rule.getMust(), data)) {
            return;
        }
        result.addError(rule.getMessage());
    }

    /**
     * 断言是否可以判定
     *
     * <h3>为什么需要这一步：填一半的表单不该报错</h3>
     *
     * 「结束日期 ≥ 开始日期」这条断言在结束日期还没填时是<b>判不了</b>的。
     * 而条件求值器对「空值参与比较」的定义是「条件不满足」——
     * 那是为联动规则设计的：不满足就不执行动作，是安全的默认。
     *
     * 到了校验这边语义正好反过来：断言不满足 = 拒绝提交。照搬求值器的结果，
     * 用户只填了开始日期就会被拦下，而他还没开始填结束日期。
     *
     * 所以这里加一道判定：<b>除了 EMPTY / NOT_EMPTY，任何操作符只要有一个操作数是空的，
     * 整条规则就不检查</b>。
     *
     * <p>这不会变成「空着也能提交」—— 字段该不该填由必填管（联动规则那条路径），
     * 本规则只管「填了之后几个字段之间的关系」。两件事分开，才能既不误拦也不放过。
     *
     * ⚠️ 与前端 {@code FormValidation/crossField.ts#isAssertionApplicable} 必须一致。
     */
    private boolean isAssertionApplicable(LogicRule.ConditionGroup group, Map<String, Object> data) {
        if (group == null || group.getItemsOrEmpty().isEmpty()) {
            return true;
        }
        for (LogicRule.ConditionNode node : group.getItemsOrEmpty()) {
            if (node == null) {
                continue;
            }
            if (node.isGroup()) {
                if (!isAssertionApplicable(node.asGroup(), data)) {
                    return false;
                }
                continue;
            }
            LogicEnums.Operator operator = node.resolveOperator();
            if (operator == LogicEnums.Operator.EMPTY || operator == LogicEnums.Operator.NOT_EMPTY) {
                // 这两个操作符问的就是「空不空」，空值对它们有意义
                continue;
            }
            if (logicRuleEvaluator.isEmpty(data.get(node.getField()))) {
                return false;
            }
            // 右值取的是另一个字段时，它也可能还没填
            if (node.getValueTypeOrDefault() == LogicEnums.ValueType.FIELD
                    && logicRuleEvaluator.isEmpty(data.get(String.valueOf(node.getValue())))) {
                return false;
            }
        }
        return true;
    }

    // ==================== 解析 ====================

    /**
     * 解析跨字段校验规则
     *
     * 解析失败返回空列表而不是抛异常：规则 JSON 可能是旧版本写的或被手工改坏，
     * 让整个提交流程失败的代价过大。但要记 warn —— 静默跳过全部规则
     * 等于悄悄放弃了服务端约束。
     *
     * 公开而非私有：发布校验也要用它解析规则，核对引用的字段是否存在。
     */
    public List<CrossFieldRule> parseCrossFieldRules(String validateRulesJson) {
        if (StrUtil.isBlank(validateRulesJson)) {
            return Collections.emptyList();
        }
        List<CrossFieldRule> rules = JsonUtils.parseObjectQuietly(validateRulesJson,
                new TypeReference<List<CrossFieldRule>>() {});
        if (rules == null) {
            log.warn("[parseCrossFieldRules][跨字段校验规则 JSON 解析失败，本次提交将跳过全部跨字段校验]");
            return Collections.emptyList();
        }
        return rules;
    }

    /**
     * 从表单字段规则里提取配了格式校验的字段
     *
     * 要递归：字段可能在布局容器或子表里。递归结构与 FormDataMasker 一致 ——
     * 两者都是「按 props 里的某个键筛选字段」。
     *
     * 公开而非私有：发布校验要用它核对配置完整性。
     */
    public List<ValidatedField> parseValidatedFields(String formFieldsJson) {
        List<ValidatedField> result = new ArrayList<>();
        for (String rule : FormFieldsCodec.read(formFieldsJson)) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collect(node, result, null, null);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> node, List<ValidatedField> result,
                         String subField, String subTitle) {
        String field = node.get("field") instanceof String f && StrUtil.isNotBlank(f) ? f : null;
        Map<String, Object> props = node.get("props") instanceof Map<?, ?> p
                ? (Map<String, Object>) p : null;

        if (field != null && props != null) {
            List<FieldValidationRule> rules = readRules(props.get("validateRules"));
            if (!rules.isEmpty()) {
                ValidatedField validated = new ValidatedField();
                validated.setField(field);
                validated.setTitle(node.get("title") instanceof String t ? t : null);
                validated.setSubField(subField);
                validated.setSubTitle(subTitle);
                validated.getRules().addAll(rules);
                result.add(validated);
            }
            // 子表控件：内部规则挂在 props.rule 下，它们的归属子表就是本控件
            if (props.get("rule") instanceof List<?> innerRules) {
                String title = node.get("title") instanceof String t ? t : null;
                for (Object inner : innerRules) {
                    if (inner instanceof Map<?, ?> innerMap) {
                        collect((Map<String, Object>) innerMap, result, field, title);
                    }
                }
            }
        }
        if (node.get("children") instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    // 布局容器不改变归属，原样把子表信息传下去
                    collect((Map<String, Object>) childMap, result, subField, subTitle);
                }
            }
        }
    }

    /**
     * 读 props.validateRules
     *
     * 走一次 JSON 往返而不是手工挨个取键：规则模型以后还会加字段，
     * 手工取键漏一个不会报错，只会让那个配置项静默失效。
     */
    private List<FieldValidationRule> readRules(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<FieldValidationRule> rules = JsonUtils.parseObjectQuietly(
                JsonUtils.toJsonString(list), new TypeReference<List<FieldValidationRule>>() {});
        return rules != null ? rules : Collections.emptyList();
    }

    /**
     * 从表单字段规则里提取配了唯一性校验的字段
     *
     * <h3>为什么校验本身不在本类里做</h3>
     *
     * 唯一性要查库，而本类刻意不碰数据库 —— 它的其余职责（正则、条件求值）都是
     * 纯计算，单测直接 new 就能跑。查库那部分放在运行时服务里（它有 JdbcTemplate
     * 与表绑定配置），本类只负责「哪些字段要查」这个解析。
     *
     * <h3>只取主表字段</h3>
     *
     * 明细行内的唯一性是另一回事：既可能是「同一张单据内不重复」，也可能是
     * 「整张明细表内不重复」，两种语义的 SQL 完全不同。不猜用户想要哪个 ——
     * 猜错的表现是「校验通过了但数据其实重复」，比不做更糟。
     *
     * @return 配了 props.uniqueCheck = true 的主表字段
     */
    public List<ValidatedField> parseUniqueFields(String formFieldsJson) {
        List<ValidatedField> result = new ArrayList<>();
        for (String rule : FormFieldsCodec.read(formFieldsJson)) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collectUnique(node, result);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void collectUnique(Map<String, Object> node, List<ValidatedField> result) {
        if (node.get("field") instanceof String field && StrUtil.isNotBlank(field)
                && node.get("props") instanceof Map<?, ?> propsMap) {
            Map<String, Object> props = (Map<String, Object>) propsMap;
            if (Boolean.TRUE.equals(props.get("uniqueCheck"))) {
                ValidatedField unique = new ValidatedField();
                unique.setField(field);
                unique.setTitle(node.get("title") instanceof String t ? t : null);
                result.add(unique);
            }
            // 刻意【不】递归 props.rule：子表明细列的唯一性语义不明确，见方法注释
        }
        if (node.get("children") instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collectUnique((Map<String, Object>) childMap, result);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toRows(Object raw) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Map<?, ?> map) {
                    rows.add((Map<String, Object>) map);
                }
            }
        }
        return rows;
    }

    // ==================== 模型 ====================

    /**
     * 一个配了格式校验的字段
     */
    @Data
    public static class ValidatedField {

        private String field;
        private String title;
        /**
         * 所属子表控件的 field；为空表示主表字段
         */
        private String subField;
        /**
         * 所属子表控件的标题，报错时用
         */
        private String subTitle;
        private final List<FieldValidationRule> rules = new ArrayList<>();

        /**
         * 报错时用的展示名。有标题用标题 —— 报字段 ID 用户看不懂
         */
        public String label() {
            return StrUtil.isNotBlank(title) ? title : field;
        }

        public String subLabel() {
            return StrUtil.isNotBlank(subTitle) ? subTitle : subField;
        }

    }

    /**
     * 校验结果
     */
    @Data
    public static class ValidationResult {

        private final List<String> errors = new ArrayList<>();

        public void addError(String message) {
            errors.add(message);
        }

        public boolean isPassed() {
            return errors.isEmpty();
        }

        /**
         * 汇总成一条消息
         *
         * 一次性把所有问题列出来，而不是报第一条就返回 —— 用户改完一处再提交
         * 又被拦下一处，来回好几轮
         */
        public String toMessage() {
            return String.join("；", errors);
        }

    }

}
