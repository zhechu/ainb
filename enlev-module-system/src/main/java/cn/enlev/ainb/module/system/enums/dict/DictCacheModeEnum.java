package cn.enlev.ainb.module.system.enums.dict;

import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/**
 * 字典的缓存模式枚举
 *
 * 管理后台前端会把字典整包缓存到本地，供全站同步取用。这对绝大多数字典（几个到几十个值）
 * 是最优解，但行政区划这类上万节点的树形字典塞进去会让首屏包体积失控。
 * 标记为懒加载后，该字典不进全量包，改由选择器展开时按需取子级。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum DictCacheModeEnum implements ArrayValuable<Integer> {

    /** 全量下发：随 /system/dict-data/sync 一次性给到前端 */
    FULL(1),
    /** 按需懒加载：不进全量包，展开节点时才取子级 */
    LAZY(2);

    public static final Integer[] ARRAYS = Arrays.stream(values()).map(DictCacheModeEnum::getMode).toArray(Integer[]::new);

    /**
     * 缓存模式
     */
    private final Integer mode;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }

    public static boolean isLazy(Integer mode) {
        return Objects.equals(LAZY.getMode(), mode);
    }

}
