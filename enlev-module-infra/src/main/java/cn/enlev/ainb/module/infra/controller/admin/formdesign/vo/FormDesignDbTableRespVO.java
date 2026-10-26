package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 表单设计可绑定的数据库表 Response VO")
@Data
public class FormDesignDbTableRespVO {

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "biz_customer")
    private String name;

    @Schema(description = "表描述", example = "客户表")
    private String comment;

    /**
     * 框架表（system_* / infra_* / bpm_* 等）不允许绑定为可写主表，
     * 防止绕过业务校验直改系统表。此标记供前端置灰或提示。
     */
    @Schema(description = "是否为系统内置表，不可作为可写主表", requiredMode = Schema.RequiredMode.REQUIRED, example = "false")
    private Boolean frameworkTable;

}
