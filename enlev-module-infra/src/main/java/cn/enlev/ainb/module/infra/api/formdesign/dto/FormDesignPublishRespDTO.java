package cn.enlev.ainb.module.infra.api.formdesign.dto;

import lombok.Data;

import java.util.List;

/**
 * 表单发布结果 DTO
 *
 * <h3>🔴 发布只是整件事的第一步</h3>
 * 「只 publish 不建菜单」的表现是：表单 {@code status=1} 看着成功，
 * 菜单树下面空空如也。「建了菜单不分配角色」的表现是：
 * 菜单在库里，但不出现在 {@code get-permission-info} 里，前端不注册路由 ——
 * <b>「菜单在库里但界面上没有」</b>。
 *
 * <p>两种都不报错。所以调用方拿到这个结果之后<b>还有活要干</b>：
 * 按 {@link #menuConfig} 建菜单、建按钮权限、回绑、分配角色。
 *
 * <p>后端只返回菜单配置、不代建菜单，是刻意设计：infra 不能反向依赖 system
 *（循环依赖），且走真正的 {@code MenuService} 才能触发 {@code @CacheEvict}。
 *
 * @author 走进新时代
 */
@Data
public class FormDesignPublishRespDTO {

    /**
     * 发布后的版本号
     */
    private Integer version;

    /**
     * 是否还需要建菜单
     *
     * 已经绑过菜单的表单再次发布时为 false。
     */
    private Boolean needCreateMenu;

    /**
     * 菜单配置，{@link #needCreateMenu} 为 true 时有值
     */
    private MenuConfig menuConfig;

    @Data
    public static class MenuConfig {

        private String name;
        private String path;
        private String component;
        private String componentName;
        private Long parentId;
        private Integer sort;
        private String icon;

        /**
         * 按钮权限，要逐个建成 type=3 的菜单
         *
         * 漏建的表现是「页面能进，但增删改按钮点了没反应或报无权限」。
         */
        private List<PermissionConfig> permissions;
    }

    @Data
    public static class PermissionConfig {

        private String name;
        private String permission;
        private Integer sort;
    }

}
