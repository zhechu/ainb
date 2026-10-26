package cn.enlev.ainb.module.system.framework.permission.core.rule;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 条件树操作符枚举
 *
 * ## 防注入关键
 *
 * 操作符只允许这个枚举里定义的值，不允许用户传入任意字符串。
 * 解析器在生成 SQL 片段时，操作符来自枚举的 sqlOp 字段，不来自用户输入。
 * 这样即使用户想传入 `; DROP TABLE` 作为操作符，也会因为 {@link #parse} 返回 null 而被拒。
 *
 * ⚠️ 被拒的后果不是抛异常，而是<b>整条规则求不出来</b>：
 * {@code ConditionTreeEvaluator} 会把它标成 {@code unevaluable}，
 * 由调用方落恒假（ALLOW）或全拒（DENY）。早先只是「跳过这个条件」，
 * 那会让 AND 组少一个条件、过滤反而变宽 —— 配错规则比不配规则还松。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum ConditionOperatorEnum {

    /** 等于 */
    EQ("EQ", "=", "等于"),
    /** 不等于 */
    NE("NE", "!=", "不等于"),
    /** 大于 */
    GT("GT", ">", "大于"),
    /** 大于等于 */
    GTE("GTE", ">=", "大于等于"),
    /** 小于 */
    LT("LT", "<", "小于"),
    /** 小于等于 */
    LTE("LTE", "<=", "小于等于"),
    /**
     * IN：值或变量必须是集合类型
     * 生成 SQL：column IN (?, ?, ?) 或 column IN (:var)
     */
    IN("IN", "IN", "属于（IN）"),
    /**
     * NOT IN
     */
    NOT_IN("NOT_IN", "NOT IN", "不属于（NOT IN）"),
    /**
     * IS NULL
     */
    IS_NULL("IS_NULL", "IS NULL", "为空"),
    /**
     * IS NOT NULL
     */
    IS_NOT_NULL("IS_NOT_NULL", "IS NOT NULL", "不为空"),
    /**
     * LIKE（前缀匹配）：自动加 %
     */
    LIKE("LIKE", "LIKE", "包含"),
    /**
     * NOT LIKE
     */
    NOT_LIKE("NOT_LIKE", "NOT LIKE", "不包含");

    /** 枚举代码（落库值，条件树 JSON 里用这个） */
    private final String code;
    /** SQL 操作符片段（生成 SQL 时用这个，不用 code） */
    private final String sqlOp;
    /** 中文名，供规则编辑器下拉展示（见 /system/rule/template-meta） */
    private final String label;

    public static ConditionOperatorEnum parse(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        for (ConditionOperatorEnum op : values()) {
            if (op.code.equals(code)) {
                return op;
            }
        }
        return null;
    }

    /** 是否不需要值（IS NULL / IS NOT NULL） */
    public boolean isUnary() {
        return this == IS_NULL || this == IS_NOT_NULL;
    }

    /** 是否需要集合值（IN / NOT IN） */
    public boolean isMultiValue() {
        return this == IN || this == NOT_IN;
    }

}
