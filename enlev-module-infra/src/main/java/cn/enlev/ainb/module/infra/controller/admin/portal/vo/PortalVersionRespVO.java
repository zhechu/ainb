package cn.enlev.ainb.module.infra.controller.admin.portal.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PortalVersionRespVO {
    private Long id;
    private Long portalId;
    private Integer version;
    private String content;
    private Integer schemaVersion;
    private String publishRemark;
    private String creator;
    private LocalDateTime createTime;
}
