package cn.enlev.ainb.module.infra.enums.portal;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Portal draft and published status. */
@Getter
@AllArgsConstructor
public enum PortalStatusEnum {

    DRAFT(0, "草稿"),
    PUBLISHED(1, "已发布"),
    PUBLISHED_MODIFIED(2, "已发布有改动");

    private final Integer status;
    private final String name;
}
