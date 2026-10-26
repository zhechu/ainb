package cn.enlev.ainb.module.infra.controller.admin.portal.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PortalPublishReqVO {
    @NotNull(message = "门户编号不能为空")
    private Long id;
    private String publishRemark;
}
