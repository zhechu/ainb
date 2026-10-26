package cn.enlev.ainb.module.system.controller.admin.permission.vo.resource;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理后台 - 权限资源新增/修改 Request VO
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 权限资源新增/修改 Request VO")
@Data
public class ResourceSaveReqVO {

    @Schema(description = "资源编号，新增时为空", example = "1024")
    private Long id;

    @Schema(description = "权限标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "system:user:query")
    @NotBlank(message = "权限标识不能为空")
    private String permission;

    @Schema(description = "资源名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "用户查询")
    @NotBlank(message = "资源名称不能为空")
    private String name;

    @Schema(description = "资源类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "ACTION")
    @NotBlank(message = "资源类型不能为空")
    private String type;

    @Schema(description = "父资源编号，挂根下传 0", example = "0")
    @NotNull(message = "父资源编号不能为空")
    private Long parentId;

    @Schema(description = "显示顺序", example = "1")
    private Integer sort;

    @Schema(description = "默认效果 ALLOW/DENY，留空则按类型取默认值", example = "DENY")
    private String defaultEffect;

    @Schema(description = "状态，0 开启 1 关闭", example = "0")
    private Integer status;

    @Schema(description = "关联的菜单编号", example = "1024")
    private Long menuId;

    @Schema(description = "数据集对应的物理表名，仅 DATASET 类型有意义", example = "system_users")
    private String datasetTable;

}
