package cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "管理后台 - 分端导航节点创建/修改 Request VO")
@Data
public class NavNodeSaveVO {

    @Schema(description = "节点编号", example = "1024")
    private Long id;

    @Schema(description = "端标识，参见 NavTerminalEnum 枚举类；缺省 app", example = "app")
    @Size(max = 16, message = "端标识长度不能超过 16 个字符")
    private String terminal;

    @Schema(description = "父节点编号，0 表示根", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "父节点编号不能为空")
    private Long parentId;

    @Schema(description = "节点类型，参见 NavNodeTypeEnum 枚举类", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    @NotNull(message = "节点类型不能为空")
    private Integer nodeType;

    @Schema(description = "展示名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "用户管理")
    @NotBlank(message = "展示名称不能为空")
    @Size(max = 64, message = "展示名称长度不能超过 64 个字符")
    private String name;

    @Schema(description = "叶子节点页面路径（须命中移动端 pages.json）", example = "/pages-system/user/index")
    @Size(max = 200, message = "页面路径不能超过 200 个字符")
    private String route;

    @Schema(description = "权限串列表，逗号分隔，任一命中即可见；空表示不设权限门槛", example = "system:user:list,system:user:query")
    @Size(max = 512, message = "权限串列表不能超过 512 个字符")
    private String permissions;

    @Schema(description = "可选：指回 system_menu.id，标记与某 Web 菜单同一能力", example = "1024")
    private Long menuRefId;

    @Schema(description = "图标（wot-ui 图标名或图片路径）", example = "user")
    @Size(max = 128, message = "图标不能超过 128 个字符")
    private String icon;

    @Schema(description = "图标颜色", example = "#1890ff")
    @Size(max = 32, message = "图标颜色不能超过 32 个字符")
    private String iconColor;

    @Schema(description = "是否 tabbar 页", example = "false")
    private Boolean isTabbar;

    @Schema(description = "是否仅 PC（移动端点击跳提示页）", example = "false")
    private Boolean onlyPc;

    @Schema(description = "页面参数（如在线表单 code=xxx）", example = "code=leave")
    @Size(max = 255, message = "页面参数不能超过 255 个字符")
    private String query;

    @Schema(description = "同级排序", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "同级排序不能为空")
    private Integer sort;

    @Schema(description = "该端是否显示", example = "true")
    private Boolean visible;

    @Schema(description = "状态，参见 CommonStatusEnum 枚举类", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    private Integer status;

}
