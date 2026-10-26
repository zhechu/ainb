package cn.enlev.ainb.module.system.api.menu.dto;

import lombok.Data;

/**
 * 菜单创建 DTO
 *
 * 字段与 {@code MenuSaveVO} 一一对应，通过薄封装暴露给跨模块调用方。
 */
@Data
public class MenuCreateReqDTO {

    private String name;
    private String permission;

    /** 类型：1=目录 2=菜单 3=按钮 */
    private Integer type;
    private Integer sort;
    private Long parentId;
    private String path;
    private String icon;
    private String component;
    private String componentName;

    /** 状态：0=正常 1=禁用 */
    private Integer status;
    private Boolean visible;
    private Boolean keepAlive;
    private Boolean alwaysShow;

}
