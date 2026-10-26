package cn.enlev.ainb.module.system.controller.admin.permission.vo.grant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 生效权限树响应 VO（角色权限地图）
 *
 * 由 {@code GrantDecisionService#listEffectiveTree} 产出，
 * 供前端「切换智能查看」的只读树形视图渲染。
 *
 * <h3>🔴 判定全部在后端完成，前端一行判定逻辑都不写</h3>
 *
 * 本仓库的注释自己就写着「另写一份判定必然与鉴权路径漂移」，而它**已经漂了两处**
 * （{@code #buildResourceChain} 沿 path 上溯不过滤 status vs {@code #buildChainFromMap}
 * 沿 parentId 只含启用资源；{@code GrantAdminController#listByResource} 与 {@code #toRespVOs}
 * 两处 expired 口径不一致）。往 TypeScript 里抄第三份判定等于主动制造第三处漂移 ——
 * 而这是一张**权限地图**：它说「拥有」而引擎判 DENY，比没有这张图更糟。
 *
 * 另一条硬理由：主体闭包前端根本拿不到（{@code ALL / all-users} 实测 9 行、
 * 角色之间的继承 {@code sys_subject_member}、POST/DEPT 开关）。
 *
 * <h3>口径</h3>
 *
 * <ul>
 *   <li>只认 {@code status=ENABLE} 的存活资源 —— 与 {@code #listAllowedPermissions} /
 *       {@code #listAllowedMenuIds} 一致，即沿 {@code #buildChainFromMap} 的 parentId 上溯。
 *       与诊断页 {@code #resolveResourceChain} 那条沿 path 上溯的路径**可能不同**（停用祖先场景），
 *       这是刻意选择</li>
 *   <li>{@code sys_grant} 分租户（{@code GrantDO extends TenantBaseDO}）而 {@code sys_resource}
 *       是全局表，所以**同一棵资源树在不同租户下剪枝结果完全不同**。实测租户 1 有 1360 行、
 *       121/122/162 各 165/142/135 行。这是正确行为，不是 bug</li>
 *   <li>主体集合含角色自身 + 其继承的上级角色 + 全员主体，<b>不含</b>
 *       USER / POST / DEPT / GROUP / SYSTEM。所以这是「角色维度」的地图，
 *       <b>少于</b>「拥有该角色的某个用户最终能做的」</li>
 * </ul>
 */
@Schema(description = "管理后台 - 生效权限树响应")
@Data
public class EffectiveTreeRespVO {

    /**
     * 实际参与判定的主体，取自 {@code SubjectKey#asString()}，已排序
     *
     * 🔴 回给前端是为了让页面上能写明「这张图算了哪些主体」——
     * 否则「只算角色维度」这句话就只是文档里的一行字，用户看不见
     */
    @Schema(description = "实际参与判定的主体清单")
    private List<String> subjects;

    @Schema(description = "汇总计数")
    private EffectiveTreeSummaryVO summary;

    @Schema(description = "剪枝后的资源树（根层）。没有任何生效权限时为空列表")
    private List<EffectiveResourceNodeVO> tree;

}
