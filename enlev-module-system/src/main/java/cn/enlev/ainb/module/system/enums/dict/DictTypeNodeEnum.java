package cn.enlev.ainb.module.system.enums.dict;

import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/**
 * 字典分类的节点类型枚举
 *
 * 分组节点只用来在左树上归拢字典，本身不挂字典数据。
 * 之所以让分组和字典共用一张表而不是另建分组表：两者在树上的行为完全一致
 * （排序、移动、防环、编码唯一），拆开会让树的每个操作都要处理两种实体。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum DictTypeNodeEnum implements ArrayValuable<Integer> {

    /** 分组：仅用于归拢，不能挂字典数据 */
    GROUP(1),
    /** 字典：可以挂字典数据 */
    DICT(2);

    public static final Integer[] ARRAYS = Arrays.stream(values()).map(DictTypeNodeEnum::getType).toArray(Integer[]::new);

    /**
     * 节点类型
     */
    private final Integer type;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }

    public static boolean isGroup(Integer type) {
        return Objects.equals(GROUP.getType(), type);
    }

    public static boolean isDict(Integer type) {
        return Objects.equals(DICT.getType(), type);
    }

}
