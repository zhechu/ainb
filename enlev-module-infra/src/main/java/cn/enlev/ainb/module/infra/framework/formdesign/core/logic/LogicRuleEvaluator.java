package cn.enlev.ainb.module.infra.framework.formdesign.core.logic;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 联动规则求值器
 *
 * 输入一组规则 + 表单数据 + 字段基础状态，输出每个字段的最终状态（可见/可编辑/必填）
 * 以及规则赋值后的数据。
 *
 * <h3>它为什么必须存在于服务端</h3>
 *
 * 前端算出来的「必填/只读」只是体验。构造一个请求直接提交，就能给只读字段塞值、
 * 或跳过必填字段。所以提交时服务端要用同一套规则重算一遍，据此丢弃只读字段的提交值、
 * 拒绝必填字段的空值。这也是本类与前端求值器必须行为一致的原因 ——
 * 两边不一致会表现为「界面上填完了但提交被拒」，或者反过来「该拦的没拦住」。
 *
 * 一致性由 form-design/logic-cases.json 保证：前端 Vitest 与后端 JUnit 读同一份用例。
 *
 * <h3>求值语义</h3>
 *
 * <ol>
 *   <li>只处理 enabled 且 trigger 匹配的规则</li>
 *   <li>规则按声明顺序求值，同一字段同一维度后者覆盖前者</li>
 *   <li>when 为空视为恒真；条件不满足时执行 otherwise</li>
 *   <li>有限次不动点迭代（最多 {@link #MAX_PASSES} 轮），让 A→B→C 的链式联动生效</li>
 * </ol>
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class LogicRuleEvaluator {

    /**
     * 不动点迭代的最大轮数
     *
     * ⚠️ 必须与前端求值器一致（见 logic-cases.json 的 maxPasses）。
     * 两边轮数不同会让「刚好在第 N 轮收敛」的规则集在一侧算出结果、另一侧算不出。
     *
     * 取 10 是因为：正常的链式联动深度极少超过 3~4 层，10 轮足够收敛；
     * 而互相翻转的规则再多轮也不会收敛，多迭代只是浪费。
     */
    public static final int MAX_PASSES = 10;

    /**
     * 求值
     *
     * @param rules     规则列表，可为 null
     * @param trigger   本次的触发时机
     * @param data      表单数据。方法不修改入参，赋值结果放在返回值里
     * @param baseState 字段基础状态（visible/editable 默认 true，required 取自控件的 $required）
     * @return 求值结果
     */
    public EvaluationResult evaluate(List<LogicRule> rules, LogicEnums.Trigger trigger,
                                     Map<String, Object> data, Map<String, FieldState> baseState) {
        Map<String, Object> workingData = data != null ? new LinkedHashMap<>(data) : new LinkedHashMap<>();
        Map<String, FieldState> workingState = copyBaseState(baseState);

        List<LogicRule> applicable = filterApplicable(rules, trigger);
        if (applicable.isEmpty()) {
            return new EvaluationResult(workingState, workingData, true, 0);
        }

        boolean stable = false;
        int passes = 0;
        for (int i = 0; i < MAX_PASSES; i++) {
            passes = i + 1;
            Map<String, Object> dataBefore = new LinkedHashMap<>(workingData);
            Map<String, FieldState> stateBefore = copyState(workingState);

            // 每一轮都从基础状态重算，而不是在上一轮结果上累加。
            // 否则「条件从满足变回不满足」时，上一轮设的状态会残留下来 ——
            // 那正是 otherwise 分支要解决的问题，不能在迭代实现上又把它引回来
            workingState = copyBaseState(baseState);
            for (LogicRule rule : applicable) {
                boolean matched = matches(rule.getWhen(), workingData);
                List<LogicRule.Action> actions = matched ? rule.getThenOrEmpty() : rule.getOtherwiseOrEmpty();
                for (LogicRule.Action action : actions) {
                    applyAction(action, workingState, workingData);
                }
            }

            if (Objects.equals(dataBefore, workingData) && stateEquals(stateBefore, workingState)) {
                stable = true;
                break;
            }
        }

        if (!stable) {
            // 不抛异常：一条配歪的规则不该让整个提交流程失败。
            // 记日志 + 在结果里标出来，由调用方决定要不要提示用户
            log.warn("[evaluate][联动规则在 {} 轮内没有收敛，可能存在互相翻转的规则。trigger={}]",
                    MAX_PASSES, trigger);
        }
        return new EvaluationResult(workingState, workingData, stable, passes);
    }

    // ==================== 规则筛选 ====================

    private List<LogicRule> filterApplicable(List<LogicRule> rules, LogicEnums.Trigger trigger) {
        if (rules == null || rules.isEmpty()) {
            return Collections.emptyList();
        }
        List<LogicRule> result = new ArrayList<>();
        for (LogicRule rule : rules) {
            if (rule == null || !rule.isEnabled()) {
                continue;
            }
            if (trigger != null && rule.getTrigger() != null && rule.getTrigger() != trigger) {
                continue;
            }
            result.add(rule);
        }
        return result;
    }

    // ==================== 条件求值 ====================

    /**
     * 条件组是否满足。为空视为恒真
     *
     * 公开而非包级可见：跨字段校验（core.validation 包）复用同一套条件求值 ——
     * 「结束日期 ≥ 开始日期」这类约束的条件模型与联动规则完全一样，
     * 那边不该为此再写一份比较原语。再写一份就意味着要再维护一份双端一致性。
     */
    public boolean matches(LogicRule.ConditionGroup group, Map<String, Object> data) {
        if (group == null || group.getItemsOrEmpty().isEmpty()) {
            return true;
        }
        boolean and = group.getLogicOrDefault() == LogicEnums.GroupLogic.AND;
        for (LogicRule.ConditionNode node : group.getItemsOrEmpty()) {
            if (node == null) {
                continue;
            }
            boolean hit = node.isGroup()
                    ? matches(node.asGroup(), data)
                    : matchesCondition(node, data);
            if (and && !hit) {
                return false;
            }
            if (!and && hit) {
                return true;
            }
        }
        // AND 走到这里说明全都满足；OR 走到这里说明没有一项满足
        return and;
    }

    private boolean matchesCondition(LogicRule.ConditionNode condition, Map<String, Object> data) {
        LogicEnums.Operator operator = condition.resolveOperator();
        if (operator == null) {
            // 操作符识别不出来按不满足处理。不抛异常：规则可能是旧版本写的，
            // 或用户手工改坏了，让整个表单打不开的代价远大于少生效一条规则
            return false;
        }
        Object left = data == null ? null : data.get(condition.getField());
        Object right = resolveRightValue(condition, data);

        return switch (operator) {
            case EMPTY -> isEmpty(left);
            case NOT_EMPTY -> !isEmpty(left);
            case EQ -> looseEquals(left, right);
            case NE -> !looseEquals(left, right);
            case GT -> compare(left, right) > 0;
            case GE -> compare(left, right) >= 0;
            case LT -> compareStrict(left, right) < 0;
            case LE -> compareStrict(left, right) <= 0;
            case IN -> containsValue(toCollection(right), left);
            case NOT_IN -> !containsValue(toCollection(right), left);
            case CONTAINS -> contains(left, right);
            case NOT_CONTAINS -> !contains(left, right);
            case STARTS_WITH -> asString(left).startsWith(asString(right));
            case ENDS_WITH -> asString(left).endsWith(asString(right));
            case BETWEEN -> between(left, right);
        };
    }

    private Object resolveRightValue(LogicRule.ConditionNode condition, Map<String, Object> data) {
        if (condition.getValueTypeOrDefault() == LogicEnums.ValueType.FIELD) {
            String refField = asString(condition.getValue());
            return data == null ? null : data.get(refField);
        }
        return condition.getValue();
    }

    // ==================== 比较原语 ====================
    //
    // 这些原语的行为必须与前端逐条对齐，logic-cases.json 里每一条都有用例。
    // 特别是「表单值几乎总是字符串」这一点：数字输入框的值可能是 "3" 而不是 3，
    // 严格相等会让所有数值条件失效，所以 EQ 走宽松比较。

    /**
     * 是否为空。空串、空集合、null 都算空；数字 0 与 false 【不】算空
     *
     * 公开而非包级可见：跨字段校验（core.validation 包）要用同一套「空」的定义
     * 判断断言能不能判定。两处对「空」的理解不同会让同一份数据在校验与联动之间
     * 表现不一致 —— 联动认为字段有值、校验认为没填。
     */
    public boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence cs) {
            return cs.isEmpty();
        }
        if (value instanceof Collection<?> c) {
            return c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        if (value instanceof Object[] arr) {
            return arr.length == 0;
        }
        return false;
    }

    /**
     * 宽松相等
     *
     * 两边都能转成数字时按数值比，否则按字符串比。
     * 这样 "3" 与 3 相等 —— 表单里的数值往往是字符串，严格比较会让规则集体失效。
     */
    boolean looseEquals(Object left, Object right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        BigDecimal ln = toNumber(left);
        BigDecimal rn = toNumber(right);
        if (ln != null && rn != null) {
            return ln.compareTo(rn) == 0;
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            return asString(left).equalsIgnoreCase(asString(right));
        }
        return asString(left).equals(asString(right));
    }

    /**
     * 比较，用于 GT / GE
     *
     * 两边都是数字时按数值比；两边都是文本时按字典序比（见 {@link #compareAsText}）。
     * 其余情况返回 -1（即「不大于」），使 GT/GE 判为不满足。
     */
    int compare(Object left, Object right) {
        BigDecimal ln = toNumber(left);
        BigDecimal rn = toNumber(right);
        if (ln != null && rn != null) {
            return ln.compareTo(rn);
        }
        Integer byText = compareAsText(left, right);
        return byText != null ? byText : -1;
    }

    /**
     * 比较，用于 LT / LE
     *
     * 与 {@link #compare} 的区别只在「无法比较时返回什么」：
     * LT/LE 若也返回 -1 就会变成「不是数字反而判为满足」，方向正好错了。
     * 所以这里返回 1（即「不小于」），同样使条件判为不满足。
     * 这个细节两端必须一致，logic-cases.json 里有用例钉住。
     */
    int compareStrict(Object left, Object right) {
        BigDecimal ln = toNumber(left);
        BigDecimal rn = toNumber(right);
        if (ln != null && rn != null) {
            return ln.compareTo(rn);
        }
        Integer byText = compareAsText(left, right);
        return byText != null ? byText : 1;
    }

    /**
     * 按字典序比较文本
     *
     * <h3>为什么需要它</h3>
     *
     * 「结束日期 ≥ 开始日期」是最常见的跨字段约束，而日期控件的值是字符串
     * （"2026-09-10"），两边都转不成数字。只按数值比较的话，这条约束在
     * 任何一对日期上都判为不满足 —— 表现为「配了区间校验，正常填的日期也被拦下」。
     *
     * <h3>为什么字典序对日期是对的</h3>
     *
     * 日期/时间控件产出的是 ISO 格式（yyyy-MM-dd、yyyy-MM-dd HH:mm:ss），
     * 定宽且高位在前，字典序与时间先后一致。
     *
     * <p><b>前提是 ISO 格式</b>：换成 "2026/9/1" 这类不定宽格式，
     * 字典序会给出错误结果（"2026/9/1" > "2026/10/1"）。本模块的日期控件只产出
     * ISO 格式，所以这个前提成立；若以后引入自定义日期格式的控件，这里要一起改。
     *
     * <h3>为什么限定「两边都是文本」</h3>
     *
     * 只在 {@code left} 与 {@code right} 都是字符串时启用：
     * <ul>
     *   <li>一侧是数字一侧是文本 → 仍判为不可比较。混合比较没有正确答案，
     *       而 logic-cases.json 里「非数值参与数值比较判为不满足」那条用例钉的正是这种情况</li>
     *   <li>布尔值排除在外 —— {@code true > false} 不是任何人想表达的意思</li>
     * </ul>
     *
     * Java 的 {@code String.compareTo} 与 JS 的 {@code <} 都按 UTF-16 码元顺序比较，
     * 两端结果一致。
     *
     * @return 比较结果；不适用返回 null
     */
    private Integer compareAsText(Object left, Object right) {
        if (!(left instanceof CharSequence) || !(right instanceof CharSequence)) {
            return null;
        }
        String ls = left.toString();
        String rs = right.toString();
        if (ls.isEmpty() || rs.isEmpty()) {
            // 空串不参与大小比较：空的结束日期不该被判成「早于任何开始日期」，
            // 那会让一个还没填完的表单被区间校验拦下。空值的语义由 EMPTY / 必填管
            return null;
        }
        return Integer.signum(ls.compareTo(rs));
    }

    private BigDecimal toNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = asString(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * left 是否包含 right
     *
     * left 是集合时判成员，是字符串时判子串。
     */
    boolean contains(Object left, Object right) {
        if (left == null) {
            return false;
        }
        Collection<?> collection = toCollection(left);
        if (collection != null) {
            return containsValue(collection, right);
        }
        return asString(left).contains(asString(right));
    }

    private boolean containsValue(Collection<?> collection, Object target) {
        if (collection == null) {
            return false;
        }
        for (Object item : collection) {
            if (looseEquals(item, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * BETWEEN，闭区间。右值必须是长度 >= 2 的集合
     */
    boolean between(Object left, Object right) {
        Collection<?> range = toCollection(right);
        if (range == null || range.size() < 2) {
            return false;
        }
        List<?> list = new ArrayList<>(range);
        return compare(left, list.get(0)) >= 0 && compareStrict(left, list.get(1)) <= 0;
    }

    private Collection<?> toCollection(Object value) {
        if (value instanceof Collection<?> c) {
            return c;
        }
        if (value instanceof Object[] arr) {
            return List.of(arr);
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    // ==================== 动作执行 ====================

    private void applyAction(LogicRule.Action action, Map<String, FieldState> state,
                             Map<String, Object> data) {
        if (action == null) {
            return;
        }
        LogicEnums.ActionType type = action.resolveType();
        if (type == null || StrUtil.isBlank(action.getTarget())) {
            // 类型识别不出来或没有目标字段，忽略这一条，其余动作照常执行。
            // 整条规则作废会让用户更难发现是哪个动作配错了
            return;
        }
        String target = action.getTarget();
        FieldState fieldState = state.computeIfAbsent(target, k -> FieldState.defaults());
        switch (type) {
            case SHOW -> fieldState.setVisible(true);
            case HIDE -> {
                fieldState.setVisible(false);
                // 隐藏的同时收掉可编辑性。只置 visible=false 的话，
                // 服务端复校验时该字段仍被视为可写，构造请求就能给隐藏字段塞值 ——
                // 那正是「前端隐藏不等于服务端放行」要防的事
                fieldState.setEditable(false);
            }
            case ENABLE, EDITABLE -> fieldState.setEditable(true);
            case DISABLE, READONLY -> fieldState.setEditable(false);
            case REQUIRE -> fieldState.setRequired(true);
            case OPTIONAL -> fieldState.setRequired(false);
            case SET_VALUE -> data.put(target, action.payloadValue("value"));
            case CLEAR_VALUE -> data.put(target, null);
        }
    }

    // ==================== 状态拷贝与比较 ====================

    private Map<String, FieldState> copyBaseState(Map<String, FieldState> baseState) {
        Map<String, FieldState> result = new LinkedHashMap<>();
        if (baseState != null) {
            baseState.forEach((field, s) -> result.put(field, s == null ? FieldState.defaults() : s.copy()));
        }
        return result;
    }

    private Map<String, FieldState> copyState(Map<String, FieldState> state) {
        Map<String, FieldState> result = new HashMap<>();
        state.forEach((field, s) -> result.put(field, s.copy()));
        return result;
    }

    private boolean stateEquals(Map<String, FieldState> a, Map<String, FieldState> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, FieldState> entry : a.entrySet()) {
            FieldState other = b.get(entry.getKey());
            if (other == null || !entry.getValue().sameAs(other)) {
                return false;
            }
        }
        return true;
    }

    // ==================== 结果模型 ====================

    /**
     * 单个字段的状态
     */
    @Data
    public static class FieldState {

        private boolean visible = true;
        private boolean editable = true;
        private boolean required = false;

        public static FieldState defaults() {
            return new FieldState();
        }

        public static FieldState of(boolean visible, boolean editable, boolean required) {
            FieldState state = new FieldState();
            state.visible = visible;
            state.editable = editable;
            state.required = required;
            return state;
        }

        public FieldState copy() {
            return of(visible, editable, required);
        }

        public boolean sameAs(FieldState other) {
            return other != null && visible == other.visible
                    && editable == other.editable && required == other.required;
        }

    }

    /**
     * 求值结果
     */
    @Data
    public static class EvaluationResult {

        /**
         * 各字段的最终状态
         */
        private final Map<String, FieldState> state;
        /**
         * 规则赋值后的数据。入参 data 不会被修改
         */
        private final Map<String, Object> data;
        /**
         * 是否在最大轮数内收敛。false 说明可能存在互相翻转的规则
         */
        private final boolean stable;
        /**
         * 实际迭代轮数，排查用
         */
        private final int passes;

        public EvaluationResult(Map<String, FieldState> state, Map<String, Object> data,
                                boolean stable, int passes) {
            this.state = state;
            this.data = data;
            this.stable = stable;
            this.passes = passes;
        }

        /**
         * 取某字段的状态，没有记录时返回默认状态
         */
        public FieldState stateOf(String field) {
            FieldState s = state.get(field);
            return s != null ? s : FieldState.defaults();
        }

    }

}
