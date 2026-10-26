package cn.enlev.ainb.module.infra.enums.formdesign;

import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 表单类型枚举
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum FormTypeEnum implements ArrayValuable<Integer> {

    /**
     * 纯表单
     *
     * 只有表单设计，没有列表。适用于只需填写、无需查询列表的场景。
     */
    FORM_ONLY(1, "纯表单"),

    /**
     * 表单+列表
     *
     * 同时包含表单与列表，适用于标准 CRUD 场景。
     * 运行时渲染：列表页 + 行操作唤起表单弹窗（新增/编辑/详情）。
     */
    FORM_LIST(2, "表单+列表");

    public static final Integer[] ARRAYS = Arrays.stream(values())
            .map(FormTypeEnum::getType).toArray(Integer[]::new);

    /**
     * 类型
     */
    private final Integer type;
    /**
     * 描述
     */
    private final String name;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }

    public static FormTypeEnum typeOf(Integer type) {
        return Arrays.stream(values())
                .filter(item -> item.getType().equals(type))
                .findFirst().orElse(null);
    }

}
