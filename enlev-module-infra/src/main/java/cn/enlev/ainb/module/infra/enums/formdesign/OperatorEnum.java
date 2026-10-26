package cn.enlev.ainb.module.infra.enums.formdesign;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 查询操作符枚举
 *
 * 用于 {@link cn.enlev.ainb.module.infra.framework.formdesign.core.sql.DynamicSqlBuilder}
 * 构建 WHERE 条件时的操作符映射。
 *
 * 枚举化是防注入的关键 —— 运行时【绝不】拼接用户传来的操作符字符串。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum OperatorEnum {

    EQ("EQ", "=", "等于"),
    NE("NE", "!=", "不等于"),
    GT("GT", ">", "大于"),
    GE("GE", ">=", "大于等于"),
    LT("LT", "<", "小于"),
    LE("LE", "<=", "小于等于"),
    LIKE("LIKE", "LIKE", "包含"),
    LEFT_LIKE("LEFT_LIKE", "LIKE", "左包含"),
    RIGHT_LIKE("RIGHT_LIKE", "LIKE", "右包含"),
    IN("IN", "IN", "在范围内"),
    BETWEEN("BETWEEN", "BETWEEN", "区间"),
    IS_NULL("IS_NULL", "IS NULL", "为空"),
    IS_NOT_NULL("IS_NOT_NULL", "IS NOT NULL", "不为空");

    /**
     * 代码（前端传来的值）
     */
    private final String code;
    /**
     * SQL 片段
     */
    private final String sqlFragment;
    /**
     * 描述
     */
    private final String description;

}
