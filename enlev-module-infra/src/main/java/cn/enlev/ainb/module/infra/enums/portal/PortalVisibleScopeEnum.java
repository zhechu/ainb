package cn.enlev.ainb.module.infra.enums.portal;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 门户可见范围枚举
 */
@Getter
@AllArgsConstructor
public enum PortalVisibleScopeEnum {

    ALL(0, "所有人"),
    ROLE(1, "指定角色"),
    USER(2, "指定用户");

    private final Integer scope;
    private final String name;
}
