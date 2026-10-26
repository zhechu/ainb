package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 表单设计可绑定的数据库列 Response VO")
@Data
public class FormDesignDbColumnRespVO {

    @Schema(description = "列名", requiredMode = Schema.RequiredMode.REQUIRED, example = "name")
    private String columnName;

    @Schema(description = "列描述", example = "客户名称")
    private String columnComment;

    @Schema(description = "JDBC 类型", example = "VARCHAR")
    private String jdbcType;

    @Schema(description = "Java 类型，供前端推荐控件类型", example = "String")
    private String javaType;

    @Schema(description = "是否主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "false")
    private Boolean primaryKey;

    @Schema(description = "是否可为空", example = "true")
    private Boolean nullable;

}
