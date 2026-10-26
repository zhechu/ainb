package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 发布结果
 *
 * 由于 infra 模块不能反向依赖 system 模块（system 已依赖 infra，反向会造成循环依赖），
 * 后端发布只做「校验 + 存版本 + 更新状态」，并返回建菜单所需的配置；
 * 由前端拿着这份配置再调 /system/menu/create 建菜单。
 *
 * 这样菜单写入走的是真正的 MenuService，@CacheEvict 会正常触发，
 * 新建的菜单与按钮权限立即生效。
 */
@Schema(description = "管理后台 - 表单设计发布结果 Response VO")
@Data
public class FormDesignPublishRespVO {

    @Schema(description = "本次发布产生的版本号", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    private Integer version;

    @Schema(description = "是否需要前端建菜单。已有菜单时为 false", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    private Boolean needCreateMenu;

    @Schema(description = "待创建的菜单配置。needCreateMenu 为 false 时为 null")
    private MenuConfig menuConfig;

    @Schema(description = "是否需要前端新建 App 导航节点。请求未勾选 App 端、或已有导航节点时为 false",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    private Boolean needCreateNavNode;

    @Schema(description = "App 导航节点配置。请求未勾选 App 端时为 null；"
            + "needCreateNavNode 为 false 时前端拿它去更新已有节点（表单改名后 App 入口名要跟着变）")
    private NavNodeConfig navNodeConfig;

    @Schema(description = "菜单配置")
    @Data
    public static class MenuConfig {

        @Schema(description = "菜单名称", example = "客户管理")
        private String name;

        @Schema(description = "路由地址", example = "form-design-run/customer")
        private String path;

        @Schema(description = "组件路径", example = "infra/formDesign/render/index")
        private String component;

        @Schema(description = "组件名称", example = "FormDesignRunCustomer")
        private String componentName;

        @Schema(description = "上级菜单编号", example = "2")
        private Long parentId;

        @Schema(description = "菜单排序", example = "0")
        private Integer sort;

        @Schema(description = "菜单图标", example = "ep:document")
        private String icon;

        @Schema(description = "待创建的按钮权限列表")
        private List<PermissionConfig> permissions;
    }

    @Schema(description = "按钮权限配置")
    @Data
    public static class PermissionConfig {

        @Schema(description = "权限名称", example = "客户管理查询")
        private String name;

        @Schema(description = "权限标识", example = "infra:form-design-run:customer:query")
        private String permission;

        @Schema(description = "排序", example = "1")
        private Integer sort;
    }

    /**
     * App 端导航节点配置
     *
     * 与 MenuConfig 同一条道理：infra 不能反向依赖 system，导航节点由前端拿这份配置
     * 调 /system/nav-node/create（或 update）落地，再回调 /infra/form-design/bind-app-nav 登记。
     */
    @Schema(description = "App 导航节点配置")
    @Data
    public static class NavNodeConfig {

        @Schema(description = "节点名称", example = "客户管理")
        private String name;

        @Schema(description = "上级节点编号（分组或子分组）", example = "23")
        private Long parentId;

        @Schema(description = "节点类型：1 分组 / 2 子分组 / 3 导航项。发布产物恒为 3", example = "3")
        private Integer nodeType;

        @Schema(description = "移动端页面路径", example = "/pages-infra/form-design/run/index")
        private String route;

        @Schema(description = "页面参数", example = "code=customer")
        private String query;

        @Schema(description = "权限串（逗号分隔，任一命中即可见）。只给 query：语义是「能查才显示」",
                example = "infra:form-design-run:customer:query")
        private String permissions;

        @Schema(description = "排序", example = "0")
        private Integer sort;

        @Schema(description = "图标（wot-ui 图标名）", example = "list")
        private String icon;

        @Schema(description = "图标颜色，留空走主题色", example = "")
        private String iconColor;
    }

}
