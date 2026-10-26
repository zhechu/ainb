package cn.enlev.ainb.module.system.controller.admin.permission.vo.grant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 生效权限树的汇总计数
 *
 * <h3>为什么必须有这一坨数字</h3>
 *
 * 🔴 权限地图的节点数**天然少于**列表页的行数：4 行台账可能合并成 1 个节点、
 * 过期的行不参与判定、被 DENY 否决的动作不出现、纯默认放行的节点默认被收起。
 * 如果不把「藏了什么」报出来，管理员对着两个数对不上，只会当成 bug 报上来。
 * 前端要把这些数字拼成一句话显示在树顶上。
 */
@Schema(description = "管理后台 - 生效权限树汇总")
@Data
public class EffectiveTreeSummaryVO {

    @Schema(description = "树里的资源节点数（含 structural 路径节点）")
    private Integer nodeCount;

    @Schema(description = "树里的生效动作总数")
    private Integer actionCount;

    @Schema(description = "继承自上级资源的动作数（source=INHERITED）")
    private Integer inheritedCount;

    @Schema(description = "来自全员主体的动作数（source=ALL_USERS）")
    private Integer allUsersCount;

    /**
     * 「纯默认放行」的节点数：该节点上每一个动作都来自 default_effect 回落，
     * 也就是**没有任何人给它配过授权**
     *
     * 🔴 这个数与 {@code includeDefaultAllow} 开关**无关**，两种模式下含义一致 ——
     * 关掉时它报「被收起了多少个」，打开时它报「树里有多少个是这种」。
     * 实测超级管理员角色有 205 个（DATASET/COLUMN/FIELD 的 default_effect 全是 ALLOW），
     * 全部渲染出来会淹掉真正配过的那些，所以默认收起，但必须报数
     */
    @Schema(description = "纯默认放行（无人配置）的节点数，与 includeDefaultAllow 开关无关")
    private Integer defaultAllowCount;

    @Schema(description = "被显式 DENY 否决的 (资源 × 动作) 项数，不出现在树里")
    private Integer deniedCount;

    /**
     * 该主体集合下已过期、因此未参与判定的授权行数
     *
     * ⚠️ 口径是 {@code GrantDO#isActive()}（判定侧），不是列表页那两处各写一份的
     * {@code expired} 计算 —— {@code GrantAdminController#listByResource} 用 {@code !isActive()}、
     * {@code #toRespVOs} 用 {@code expireTime != null && now.isAfter(expireTime)}，
     * 后者会把脏 subject_type 也标成「已过期」。本 VO 不复用那两处
     */
    @Schema(description = "已过期、未计入判定的授权行数")
    private Integer expiredSkippedCount;

    /**
     * 该角色是不是超管角色
     *
     * 🔴 真实的用户鉴权对超管是**旁路授权台账**的
     * （{@code GrantDecisionService#isSuperAdmin} → {@code roleService.hasAnySuperAdmin}），
     * 而这张图画的恰恰是**台账**。所以超管角色打开时，图上看到的和该角色下的用户
     * 实际能做的不是一回事，前端必须据此弹提示。
     *
     * 判据与 {@code @ss.hasPermission} 走的是同一个 {@code roleService.hasAnySuperAdmin}，
     * 不是另写一份
     */
    @Schema(description = "该角色是否为超管角色（超管的真实鉴权旁路授权台账）")
    private Boolean superAdminRole;

}
