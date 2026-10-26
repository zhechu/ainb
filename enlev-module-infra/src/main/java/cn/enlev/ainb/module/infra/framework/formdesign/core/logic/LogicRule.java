package cn.enlev.ainb.module.infra.framework.formdesign.core.logic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 联动规则模型
 *
 * 与前端 `src/components/FormLogic/types.ts` 一一对应，两侧读同一份用例集
 * （form-design/logic-cases.json）保证求值行为一致。
 *
 * 为什么用声明式模型而不是让用户写脚本：规则是 JSON，能静态检查、能做版本 diff、
 * 能在服务端复算。脚本能力另有出口（见设计方案 6.3），但它不是默认路径。
 *
 * @author 走进新时代
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogicRule {

    /**
     * 规则编号，表单内唯一
     */
    private String id;
    /**
     * 规则名称，仅用于设计器展示与报错定位
     */
    private String name;
    /**
     * 是否启用。缺省视为启用 —— 少写一个布尔不该让规则静默失效
     */
    private Boolean enabled;
    /**
     * 触发时机
     */
    private LogicEnums.Trigger trigger;
    /**
     * 行作用域：本规则作用在哪个子表的明细行上
     *
     * 为空表示作用在主表字段上（常规规则）。
     * 有值时，规则会对该子表的【每一行】各求值一次，条件与动作里的字段名
     * 指的都是明细列，而不是主表字段。
     *
     * 为什么需要它：明细里「数量大于 100 时必须填备注」这类约束，
     * 用主表作用域的规则表达不了 —— 那样只能判断整个子表数组，
     * 拿不到某一行的数量。
     */
    private String rowScope;

    /**
     * 条件。为 null 或 items 为空时视为恒真
     */
    private ConditionGroup when;
    /**
     * 条件满足时执行的动作
     */
    private List<Action> then;
    /**
     * 条件不满足时执行的动作
     *
     * 这一支是必要的而非可选的锦上添花：只写 then 是同类产品最常见的坑 ——
     * 条件从满足变回不满足时字段不会恢复，表现为「填了又改回去，字段还留在必填状态」。
     */
    private List<Action> otherwise;

    /**
     * 是否启用，null 安全
     */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /**
     * 是否是行作用域规则
     */
    public boolean isRowScoped() {
        return rowScope != null && !rowScope.isBlank();
    }

    public List<Action> getThenOrEmpty() {
        return then != null ? then : Collections.emptyList();
    }

    public List<Action> getOtherwiseOrEmpty() {
        return otherwise != null ? otherwise : Collections.emptyList();
    }

    /**
     * 条件组
     *
     * items 里既可以是条件，也可以是嵌套的条件组 —— 靠有没有 logic 字段区分，
     * 见 {@link #isGroup()}。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConditionGroup {

        private LogicEnums.GroupLogic logic;
        private List<ConditionNode> items;

        /**
         * 逻辑运算，缺省 AND
         */
        public LogicEnums.GroupLogic getLogicOrDefault() {
            return logic != null ? logic : LogicEnums.GroupLogic.AND;
        }

        public List<ConditionNode> getItemsOrEmpty() {
            return items != null ? items : Collections.emptyList();
        }

    }

    /**
     * 条件树的节点：要么是一个条件，要么是一个嵌套组
     *
     * 用一个扁平类承载两种形态，而不是搞继承 + 多态反序列化：
     * Jackson 的多态需要类型标记字段，那会让规则 JSON 里多出一列纯技术性的 `@type`，
     * 用户在设计器里导出规则看到它只会困惑。靠 logic 字段是否存在来判别更自然。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConditionNode {

        // ===== 作为嵌套组时用这两个 =====
        private LogicEnums.GroupLogic logic;
        private List<ConditionNode> items;

        // ===== 作为条件时用这四个 =====
        private String field;
        private String operator;
        private String valueType;
        private Object value;

        /**
         * 是否是嵌套组
         *
         * 判据是「有 logic 字段」。只有 items 没有 logic 的也算组（logic 走缺省 AND），
         * 否则用户少写一个 logic 就会让整组条件被当成一个残缺的条件而恒不满足。
         */
        public boolean isGroup() {
            return logic != null || items != null;
        }

        /**
         * 转成条件组看待
         */
        public ConditionGroup asGroup() {
            ConditionGroup group = new ConditionGroup();
            group.setLogic(logic);
            group.setItems(items);
            return group;
        }

        /**
         * 右值来源，缺省 CONST
         */
        public LogicEnums.ValueType getValueTypeOrDefault() {
            if (valueType == null) {
                return LogicEnums.ValueType.CONST;
            }
            try {
                return LogicEnums.ValueType.valueOf(valueType.toUpperCase());
            } catch (IllegalArgumentException e) {
                // 写错了按常量处理，而不是让整条规则报错
                return LogicEnums.ValueType.CONST;
            }
        }

        /**
         * 解析操作符
         *
         * @return 操作符；无法识别返回 null，调用方按「条件不满足」处理
         */
        public LogicEnums.Operator resolveOperator() {
            if (operator == null) {
                return null;
            }
            try {
                return LogicEnums.Operator.valueOf(operator.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

    }

    /**
     * 动作
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Action {

        /**
         * 动作类型
         */
        private String type;
        /**
         * 目标字段
         */
        private String target;
        /**
         * 附加参数。SET_VALUE 用 payload.value
         */
        private Map<String, Object> payload;

        /**
         * 解析动作类型
         *
         * @return 类型；无法识别返回 null，调用方忽略该动作
         */
        public LogicEnums.ActionType resolveType() {
            if (type == null) {
                return null;
            }
            try {
                return LogicEnums.ActionType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /**
         * 取 payload 里的某个值
         */
        public Object payloadValue(String key) {
            return payload != null ? payload.get(key) : null;
        }

    }

}
