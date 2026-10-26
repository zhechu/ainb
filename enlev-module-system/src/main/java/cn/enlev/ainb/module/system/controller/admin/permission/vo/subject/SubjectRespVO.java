package cn.enlev.ainb.module.system.controller.admin.permission.vo.subject;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 授权主体响应 VO
 */
@Schema(description = "管理后台 - 授权主体响应")
@Data
public class SubjectRespVO {

    @Schema(description = "主体编号")
    private Long id;

    @Schema(description = "主体类型：ROLE/USER/DEPT/POST/GROUP/SYSTEM/ALL")
    private String type;

    @Schema(description = "指向的业务表主键，SYSTEM/ALL 类型为 null")
    private Long refId;

    @Schema(description = "主体编码，仅 SYSTEM/ALL 类型使用")
    private String code;

    @Schema(description = "展示名（仅用于排错与列表展示，可能过期）")
    private String name;

    @Schema(description = "状态：0 启用 1 停用")
    private Integer status;

}
