package cn.enlev.ainb.module.system.controller.admin.permission.vo.rule;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 行过滤规则新增 / 修改请求 VO
 */
@Schema(description = "管理后台 - 行过滤规则新增/修改请求")
@Data
public class RuleSaveReqVO {

    @Schema(description = "规则编号，修改时必填")
    private Long id;

    @Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "规则名称不能为空")
    private String name;

    @Schema(description = "规则模板代码，如 BUILT_IN_OWNER_ONLY 或自定义", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "规则模板不能为空")
    private String template;

    @Schema(description = "模板参数，JSON 对象格式，如 {\"allowedStatuses\":[1,2]}")
    private String params;

    @Schema(description = "规则描述，展示给管理员")
    private String description;

    @Schema(description = "状态：0 启用 1 停用")
    private Integer status;

}
