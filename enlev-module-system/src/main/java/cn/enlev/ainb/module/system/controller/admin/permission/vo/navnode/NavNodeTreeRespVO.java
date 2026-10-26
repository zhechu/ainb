package cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 分端导航树 Response VO（移动端 {@code GET /system/nav-node/tree} 消费）
 *
 * <p>服务端已按当前登录用户的授权权限集合过滤（{@code visible} + {@code permissions} 命中 + 分组级联），
 * 故不再下发 {@code permissions} / {@code status} / {@code visible} —— 移动端直接渲染即可，
 * 不需要再跑一遍白名单过滤（见「移动端优化方案」§4.6 Phase B2/B3）
 *
 * @author 走进新时代
 */
@Schema(description = "用户 App - 分端导航树 Response VO")
@Data
public class NavNodeTreeRespVO {

    @Schema(description = "节点编号（移动端用作稳定 key，替代 menu.json 的 key）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long id;

    @Schema(description = "父节点编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Long parentId;

    @Schema(description = "节点类型，参见 NavNodeTypeEnum 枚举类：1=分组 2=子分组 3=导航项", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    private Integer nodeType;

    @Schema(description = "展示名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "用户管理")
    private String name;

    @Schema(description = "叶子节点页面路径", example = "/pages-system/user/index")
    private String route;

    @Schema(description = "图标", example = "user")
    private String icon;

    @Schema(description = "图标颜色", example = "#1890ff")
    private String iconColor;

    @Schema(description = "是否 tabbar 页：决定 switchTab vs navigateTo", example = "false")
    private Boolean isTabbar;

    @Schema(description = "是否仅 PC：移动端点击跳提示页", example = "false")
    private Boolean onlyPc;

    @Schema(description = "页面参数（如在线表单 code=xxx）", example = "code=leave")
    private String query;

    @Schema(description = "同级排序", example = "1")
    private Integer sort;

    @Schema(description = "子节点列表")
    private List<NavNodeTreeRespVO> children;

}
