package cn.enlev.ainb.module.infra.enums.billtemplate;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 内联单据规则中，前后缀段的取值来源
 *
 * 仅内联模式使用 —— 规则直接写在表单字段上，前后缀由若干「段」拼成。
 * 模板模式的前后缀是固定字符串，没有来源概念。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum SegmentSourceEnum {

    /**
     * 取表单字段的值
     *
     * 作用域规则：子表内的单据字段先在本行找，找不到再回主表找；
     * 主表的单据字段只在主表里找。否则明细行会串到别的行的值上。
     */
    FIELD(1, "字段"),

    /**
     * 自定义常量
     */
    CONSTANT(2, "自定义");

    /**
     * 类型
     */
    private final Integer type;
    /**
     * 描述
     */
    private final String name;

    /**
     * 按类型解析，解析不到返回 {@link #CONSTANT}
     *
     * 不抛异常：段配置是用户在表单设计器里点出来的，脏数据不应该让整个表单保存不了。
     * 兜底成常量意味着「按字面值输出」，最差情况是号码里多一段没求值的文本，
     * 比直接报错阻断保存更可接受。
     */
    public static SegmentSourceEnum typeOf(Integer type) {
        return Arrays.stream(values())
                .filter(item -> item.getType().equals(type))
                .findFirst().orElse(CONSTANT);
    }

}
