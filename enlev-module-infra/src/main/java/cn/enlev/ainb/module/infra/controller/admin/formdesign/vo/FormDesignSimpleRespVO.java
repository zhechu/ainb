package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 表单设计精简 Response VO")
@Data
public class FormDesignSimpleRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "表单名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "客户管理")
    private String name;

    @Schema(description = "表单编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "customer")
    private String code;

    @Schema(description = "表单类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    private Integer formType;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer status;

}
