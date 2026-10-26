package cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 分端导航节点 Response VO")
@Data
public class NavNodeRespVO {

    @Schema(description = "节点编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long id;

    @Schema(description = "端标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "app")
    private String terminal;

    @Schema(description = "父节点编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Long parentId;

    @Schema(description = "节点类型，参见 NavNodeTypeEnum 枚举类", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    private Integer nodeType;

    @Schema(description = "展示名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "用户管理")
    private String name;

    @Schema(description = "叶子节点页面路径", example = "/pages-system/user/index")
    private String route;

    @Schema(description = "权限串列表，逗号分隔", example = "system:user:list")
    private String permissions;

    @Schema(description = "指回的 system_menu.id", example = "1024")
    private Long menuRefId;

    @Schema(description = "图标", example = "user")
    private String icon;

    @Schema(description = "图标颜色", example = "#1890ff")
    private String iconColor;

    @Schema(description = "是否 tabbar 页", example = "false")
    private Boolean isTabbar;

    @Schema(description = "是否仅 PC", example = "false")
    private Boolean onlyPc;

    @Schema(description = "页面参数", example = "code=leave")
    private String query;

    @Schema(description = "同级排序", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer sort;

    @Schema(description = "该端是否显示", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    private Boolean visible;

    @Schema(description = "状态，参见 CommonStatusEnum 枚举类", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Integer status;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

}
