package cn.enlev.ainb.module.system.api.permission.dto;

import lombok.Data;

import java.util.List;

/**
 * 权限点的诊断信息
 *
 * <p>专供「配了但不生效」的排查使用：把几个<b>已知会静默失效</b>的点
 * 一次性查出来，而不是让人一张表一张表翻。
 *
 * @author 走进新时代
 */
@Data
public class ResourceDiagnosticRespDTO {

    /**
     * 权限串
     */
    private String permission;

    /**
     * 资源是否已登记
     *
     * 🔴 没登记的话，判定引擎里 {@code chains.isEmpty()} 直接返回「无权限」，
     * 于是「授权配了等于没配」—— 而界面上那条授权记录看着好好的。
     */
    private Boolean registered;

    /**
     * 该权限串对应的资源<b>行数</b>
     *
     * 🔴 大于 1 时要特别小心：同一个权限串挂在多个菜单上是上游常见做法，
     * 而授权只配在其中一行上时，清单侧（按任意行命中）与判定侧若口径不一致，
     * 就会出现「前端按钮显示了、点下去 403」。
     */
    private Integer resourceCount;

    /**
     * 数据集资源对应的物理表名，非数据集资源为 null
     */
    private String datasetTable;

    /**
     * 该资源上的授权行数
     */
    private Integer grantCount;

    /**
     * 租户写错的授权行数
     *
     * 🔴 {@code sys_grant.tenant_id} 的正确来源是<b>角色的租户</b>，不是默认 0。
     * 写成 0 的话，这条授权在任何租户下都匹配不上，表现为「授权配了等于全放行/全不放行」。
     */
    private Integer tenantMismatchGrantCount;

    /**
     * 出现过的非法 {@code fieldMode} 取值
     *
     * 🔴 合法值只有 EDITABLE / READONLY / HIDDEN。传了别的（最常见是 VISIBLE）
     * 会让<b>整行配置被静默丢弃</b>，不报错。
     */
    private List<String> illegalFieldModes;

}
