package cn.enlev.ainb.module.system.controller.admin.permission.vo.resource;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 权限资源响应 VO（用于资源树展示与授权配置）
 */
@Schema(description = "管理后台 - 权限资源响应")
@Data
public class ResourceRespVO {

    @Schema(description = "资源编号")
    private Long id;

    @Schema(description = "权限标识，如 system:user:query")
    private String permission;

    @Schema(description = "资源名称")
    private String name;

    @Schema(description = "资源类型：MENU/ACTION/PROCESS/CATEGORY/DATASET/COLUMN/FIELD")
    private String type;

    @Schema(description = "父资源编号，根节点为 0")
    private Long parentId;

    @Schema(description = "物化路径")
    private String path;

    @Schema(description = "节点深度")
    private Short depth;

    @Schema(description = "显示顺序")
    private Integer sort;

    @Schema(description = "无授权时的默认效果：ALLOW 或 DENY")
    private String defaultEffect;

    @Schema(description = "状态：0 启用 1 停用")
    private Integer status;


    @Schema(description = "是否还有子资源，树表懒加载用", example = "true")
    private Boolean hasChildren;

}
