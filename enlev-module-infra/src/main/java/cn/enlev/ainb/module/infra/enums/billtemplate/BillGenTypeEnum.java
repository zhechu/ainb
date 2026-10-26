package cn.enlev.ainb.module.infra.enums.billtemplate;

import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 单据号的生成方式枚举
 *
 * 三种方式的「主体」部分算法完全不同，前后缀的拼接逻辑则是共用的，
 * 见 {@link cn.enlev.ainb.module.infra.framework.billtemplate.core.BillNumberGenerator}。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum BillGenTypeEnum implements ArrayValuable<Integer> {

    /**
     * 时间流水
     *
     * 主体 = 日期段 + 左补零的流水号。最常用的方式，也是唯一会消耗 Redis 计数的方式。
     */
    TIME(1, "时间流水"),

    /**
     * 随机数编号
     *
     * 主体 = 指定位数的随机串。不消耗计数，但理论上存在重复可能，
     * 因此目标列应加唯一索引兜底。
     */
    RANDOM(2, "随机数编号"),

    /**
     * UUID
     *
     * 主体 = 大写 UUID。不消耗计数，实际重复概率可忽略。
     */
    UUID(3, "UUID");

    public static final Integer[] ARRAYS = Arrays.stream(values())
            .map(BillGenTypeEnum::getType).toArray(Integer[]::new);

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

    public static BillGenTypeEnum typeOf(Integer type) {
        return Arrays.stream(values())
                .filter(item -> item.getType().equals(type))
                .findFirst().orElse(null);
    }

    /**
     * 是否需要消耗流水计数
     *
     * 只有 {@link #TIME} 需要。随机数与 UUID 每次独立生成，不走 Redis INCR。
     */
    public boolean needSequence() {
        return this == TIME;
    }

}
