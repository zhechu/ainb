package cn.enlev.ainb.module.infra.framework.formdesign.core.logic;

/**
 * 联动规则的枚举集合
 *
 * 放在一个文件里而不是拆成三个：三者都只是取值清单，没有行为，
 * 拆开只会让「想知道规则模型长什么样」时要开三个文件。
 *
 * @author 走进新时代
 */
public final class LogicEnums {

    private LogicEnums() {
    }

    /**
     * 触发时机
     */
    public enum Trigger {
        /**
         * 表单加载完成时
         */
        LOAD,
        /**
         * 字段值变化时
         */
        CHANGE,
        /**
         * 提交前。服务端复校验用的就是这个时机
         */
        SUBMIT
    }

    /**
     * 条件组的逻辑运算
     */
    public enum GroupLogic {
        AND,
        OR
    }

    /**
     * 条件右值的来源
     */
    public enum ValueType {
        /**
         * 常量，直接取 value
         */
        CONST,
        /**
         * 另一个字段，value 是字段名
         */
        FIELD
    }

    /**
     * 条件操作符
     */
    public enum Operator {
        EQ, NE,
        GT, GE, LT, LE,
        IN, NOT_IN,
        CONTAINS, NOT_CONTAINS,
        STARTS_WITH, ENDS_WITH,
        EMPTY, NOT_EMPTY,
        BETWEEN
    }

    /**
     * 动作类型
     */
    public enum ActionType {
        /**
         * 显示
         */
        SHOW,
        /**
         * 隐藏。同时会失去可编辑性 —— 隐藏了还能提交值就是绕过入口
         */
        HIDE,
        /**
         * 启用
         */
        ENABLE,
        /**
         * 禁用
         */
        DISABLE,
        /**
         * 置为只读。与 DISABLE 在服务端是同一件事（都不接受提交值），
         * 保留两个是因为前端的视觉表现不同
         */
        READONLY,
        /**
         * 置为可编辑
         */
        EDITABLE,
        /**
         * 置为必填
         */
        REQUIRE,
        /**
         * 置为非必填
         */
        OPTIONAL,
        /**
         * 赋值。payload.value 为要写入的值
         */
        SET_VALUE,
        /**
         * 清空值
         */
        CLEAR_VALUE
    }

}
