package cn.enlev.ainb.module.infra.controller.admin.portal.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PortalRespVO {
    private Long id;
    private String name;
    private String code;
    private String description;
    private Integer type;
    private Integer linkType;
    private String linkUrl;
    private Integer sort;
    private Integer status;
    private String content;
    private Integer publishedVersion;
    private Boolean isDefault;
    private Boolean enabled;
    private Integer visibleScope;
    private String visibleIds;
    private Integer schemaVersion;
    private String creator;
    private LocalDateTime createTime;
    private String updater;
    private LocalDateTime updateTime;
}
