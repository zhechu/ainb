package cn.enlev.ainb.module.infra.controller.admin.portal.vo;

import lombok.Data;

@Data
public class PortalRuntimeRespVO {
    private Long id;
    private String name;
    private String code;
    private Integer version;
    private String content;
    private Integer schemaVersion;
    /** 门户类型：0-在线设计 1-自定义链接 */
    private Integer type;
    /** 链接类型：0-页面（内部路由） 1-外链，type=1 时有值 */
    private Integer linkType;
    /** 链接地址，type=1 时有值 */
    private String linkUrl;
    /** 是否为默认门户，运行时列表接口使用 */
    private Boolean isDefault;
}
