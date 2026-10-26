package cn.enlev.ainb.module.infra.controller.admin.portal.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import lombok.Data;

@Data
public class PortalPageReqVO extends PageParam {
    private String name;
    private String code;
    private Integer status;
    private Boolean enabled;
}
