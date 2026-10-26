package cn.enlev.ainb.module.infra.enums.datainterface;

import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 数据接口的类型枚举
 *
 * 三种数据来源各由一个执行器实现，新增类型只需加枚举 + 实现类。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum DataInterfaceTypeEnum implements ArrayValuable<Integer> {

    SQL(1, "SQL 查询"),
    STATIC(2, "静态 JSON"),
    HTTP(3, "HTTP 转发");

    public static final Integer[] ARRAYS = Arrays.stream(values())
            .map(DataInterfaceTypeEnum::getType).toArray(Integer[]::new);

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

    public static DataInterfaceTypeEnum typeOf(Integer type) {
        return Arrays.stream(values())
                .filter(item -> item.getType().equals(type))
                .findFirst().orElse(null);
    }

}
