package cn.enlev.ainb.module.infra.enums.portal;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 门户类型枚举
 */
@Getter
@AllArgsConstructor
public enum PortalTypeEnum {

    ONLINE_DESIGN(0, "在线设计"),
    LINK(1, "自定义链接");

    public static final int LINK_PAGE = 0; // 链接类型：页面（内部路由）
    public static final int LINK_EXTERNAL = 1; // 链接类型：外链

    private final Integer type;
    private final String name;

    public static boolean isLink(Integer type) {
        return LINK.getType().equals(type);
    }
}
