package cn.enlev.ainb.module.infra.api.formdesign.dto;

import lombok.Data;

/**
 * 「这次发布会不会建菜单、建成什么样」的只读预演结果
 *
 * <h3>🔴 为什么不能拿 {@code publishFormDesign} 当预演用</h3>
 * 那个方法<b>真发布</b>：存版本快照、把 {@code status} 改成已发布、
 * 在事务内同步广播 {@code FormDesignPublishedEvent}（权限资源登记）。
 * 它的 javadoc 写「发布不会建菜单，只返回菜单配置」—— 说的是<b>菜单</b>不建，
 * 不是说这个方法没有副作用。拿它做预演 = 为了看一眼菜单长什么样，
 * 把一条流程真的发布掉、还留下一串版本号。
 *
 * <p>本方法<b>一行都不写</b>：不建版本、不改状态、不发事件、不建菜单。
 * 它只回答「如果现在发布，菜单这块会发生什么」。
 *
 * <h3>为什么清单需要它</h3>
 * 建不建菜单的判据是「这张表单当前有没有绑过菜单」，而这件事
 * 光看 DSL 算不出来（DSL 里只有表单 code）。硬算的结果是<b>再发布时也报「将新建 1 个菜单」</b>——
 * 对用户说一件不会发生的事，正是变更清单要消灭的那类错误。
 *
 * <p>⚠️ {@link #menuConfig} 直接复用 {@link FormDesignPublishRespDTO.MenuConfig}，
 * 不另立一份同形状的类：这两个 DTO 描述的是<b>同一个菜单配置</b>，
 * 一份是「发布时给你的」、一份是「发布前给你看的」，
 * 分成两份的代价是改了字段忘了改另一份，于是预演说的和真发布做的不一样。
 *
 * @author 走进新时代
 */
@Data
public class FormDesignMenuPreviewRespDTO {

    /**
     * 表单编码
     */
    private String code;

    /**
     * 表单名称（也就是将要建出来的菜单名）
     */
    private String name;

    /**
     * 这次发布会不会新建菜单
     *
     * <p>判据与 {@code FormDesignServiceImpl#publish} 里那份完全同源：
     * {@code menuId == null} 即首发。已绑过菜单的再发布为 false，
     * 此时 {@link #menuConfig} 为 null —— 因为压根不会建。
     */
    private Boolean needCreateMenu;

    /**
     * 已经绑上的菜单编号；null 表示从没绑过
     */
    private Long existingMenuId;

    /**
     * 🔴 首发但没给上级菜单 —— 真发布时会被拒
     *
     * <p>{@code publish} 对这种组合抛 {@code FORM_DESIGN_MENU_PARENT_REQUIRED}
     * （「请选择上级菜单」）。预演<b>刻意不抛</b>：
     * 清单要做的恰恰是在用户点同意之前把这句说出来，
     * 而不是让他在执行之后看见一个报错。
     *
     * <p>为 true 时 {@link #menuConfig} 仍然给出（按顶层菜单的路径形态算），
     * 因为「补上 parentMenuId 之后会建成什么样」也是有用的信息。
     */
    private Boolean parentMenuMissing;

    /**
     * 将会建出来的菜单配置，{@link #needCreateMenu} 为 false 时是 null
     *
     * <p>🔴 它是 <b>1 个组件菜单（{@code system_menu.type=2}）+ 7 个按钮权限
     * （{@code type=3}）= 8 条菜单记录</b>，不是一个目录。
     * 而且后端<b>一条都不建</b>：{@code buildMenuConfig} 只组装配置，
     * 真正调 {@code /system/menu/create} 的是前端（{@code PublishDialog.vue} 的
     * {@code createMenus}，主菜单 {@code type:2}、按钮 {@code type:3}）。
     * 说成「目录」或说成「后端会建」都会让审批人对着一个不存在的动作做判断。
     */
    private FormDesignPublishRespDTO.MenuConfig menuConfig;

}
