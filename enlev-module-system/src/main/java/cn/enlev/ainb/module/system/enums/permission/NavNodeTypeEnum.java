package cn.enlev.ainb.module.system.enums.permission;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 分端导航节点类型枚举
 *
 * <p>与移动端 menu.json 的三层结构 groups → subGroups → menus 一一对应，
 * 是 {@code system_nav_node} 树自引用组织的节点分类。参照 {@link MenuTypeEnum} 的最小写法
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum NavNodeTypeEnum {

    GROUP(1), // 分组
    SUB_GROUP(2), // 子分组
    ITEM(3) // 导航项（叶子）
    ;

    /**
     * 类型
     */
    private final Integer type;

}
