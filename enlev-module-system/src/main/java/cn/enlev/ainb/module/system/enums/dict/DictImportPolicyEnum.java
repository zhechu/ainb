package cn.enlev.ainb.module.system.enums.dict;

import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/**
 * 字典迁移包的冲突处理策略
 *
 * 导入时几乎一定会撞上同名或同编码的分类——同一份字典被反复往同一个环境导，是最常见的用法。
 * 与其在撞车时统一报错让用户自己去删，不如把「撞了怎么办」交给用户在导入前决定。
 *
 * 三种策略覆盖了实际会遇到的三种意图：
 * <ul>
 *     <li>{@link #SKIP}——「我只想补上缺的」：已存在的分类和数据一律保持原样，只写入新东西。
 *         这是默认值，因为它是唯一不会破坏现有数据的选项</li>
 *     <li>{@link #OVERWRITE}——「以这个包为准」：已存在的按包里的内容更新</li>
 *     <li>{@link #RENAME}——「我想要一份独立的副本」：整棵子树全部新建，编码和名称自动加后缀。
 *         适合拿线上字典改造出一套新的，而不影响原来那套</li>
 * </ul>
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum DictImportPolicyEnum implements ArrayValuable<Integer> {

    /** 跳过：已存在的分类与数据都不动，只补新增 */
    SKIP(1),
    /** 覆盖：已存在的按包里的内容更新 */
    OVERWRITE(2),
    /** 副本：整棵子树新建一份，编码与名称自动加后缀 */
    RENAME(3);

    public static final Integer[] ARRAYS = Arrays.stream(values()).map(DictImportPolicyEnum::getPolicy).toArray(Integer[]::new);

    /**
     * 策略值
     */
    private final Integer policy;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }

    public static boolean isSkip(Integer policy) {
        return Objects.equals(SKIP.getPolicy(), policy);
    }

    public static boolean isOverwrite(Integer policy) {
        return Objects.equals(OVERWRITE.getPolicy(), policy);
    }

    public static boolean isRename(Integer policy) {
        return Objects.equals(RENAME.getPolicy(), policy);
    }

}
