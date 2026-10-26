package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.biz.system.permission.dto.DeptDataPermissionRespDTO;

import java.util.Collection;
import java.util.Set;

import static java.util.Collections.singleton;

/**
 * 权限 Service 接口
 * <p>
 * 提供用户-角色、角色-菜单、角色-部门的关联权限处理
 *
 * @author 芋道源码
 */
public interface PermissionService {

    /**
     * 判断是否有权限，任一一个即可
     *
     * @param userId      用户编号
     * @param permissions 权限
     * @return 是否
     */
    boolean hasAnyPermissions(Long userId, String... permissions);

    /**
     * 判断是否有角色，任一一个即可
     *
     * @param roles 角色数组
     * @return 是否
     */
    boolean hasAnyRoles(Long userId, String... roles);

    // ========== 角色-菜单的相关方法  ==========

    /**
     * 设置角色菜单
     *
     * @param roleId  角色编号
     * @param menuIds 菜单编号集合
     */
    void assignRoleMenu(Long roleId, Set<Long> menuIds);

    /**
     * 按菜单集合<b>全量重建</b>角色在 {@code sys_grant} 里的 VIEW 授权
     *
     * <h3>为什么租户套餐不能再用 {@link #assignRoleMenu}</h3>
     *
     * {@code grant-enabled=true} 后，鉴权与菜单渲染读的都是 {@code sys_grant}，
     * 而 {@code assignRoleMenu} 只写 {@code system_role_menu}。两者之间唯一的通路是
     * 启动时的 {@code GrantMigrateService#migrate()}，而它是 <b>insert-only</b> 的。
     * 于是租户套餐出现两个都不报错的缺陷：
     *
     * <ul>
     *   <li><b>新建租户的管理员没有任何菜单</b> —— 要等到下次重启迁移跑过才拿得到</li>
     *   <li><b>套餐降级收不回权限</b> —— {@code updateTenantRoleMenu} 里那个
     *       {@code intersectionDistinct} 算出来的「收窄」结果只落在旧表，
     *       {@code sys_grant} 里多出来的授权行永远留着</li>
     * </ul>
     *
     * 所以这里必须是<b>全量对齐</b>（多的删、少的补），不能沿用 insert-only 的语义 ——
     * 「收权限收了个寂寞」正是本轮要修的那一类问题。
     *
     * <h3>目录不参与</h3>
     *
     * 套餐的 {@code menu_ids} 里含目录，但目录没有资源行（见 {@code ResourceService#syncFromMenu}）。
     * 这不影响结果：目录的可见性由 {@code completeMenuAncestors} 沿菜单父链补全，
     * 不需要也不应该有授权行。
     *
     * <h3>只动「套餐型」授权行</h3>
     *
     * 只对齐 {@code action=VIEW && effect=ALLOW && context_resource_id IS NULL} 的行。
     * DENY 行与带上下文的行一律保留 —— 它们是管理员<b>显式收紧</b>的结果，
     * 被套餐重算抹掉会造成权限放大，比少给权限危险得多。
     *
     * @param roleId  角色编号
     * @param menuIds 菜单编号集合，{@code null} 或空表示回收该角色的全部套餐型 VIEW 授权
     */
    void assignRoleMenuGrants(Long roleId, Set<Long> menuIds);

    /**
     * 把角色在 {@code sys_grant} 里的 VIEW 授权<b>收窄</b>到菜单集合之内（只删不加）
     *
     * 对应 {@code updateTenantRoleMenu} 里「其他角色去掉超过套餐的权限」那一支：
     * 套餐是租户的能力边界，超出边界的要收回，但不能反过来给角色补上它本来没有的权限。
     *
     * 与 {@link #assignRoleMenuGrants} 一样，只动套餐型授权行，DENY 行不碰。
     *
     * @param roleId  角色编号
     * @param menuIds 允许保留的菜单编号集合
     */
    void retainRoleMenuGrants(Long roleId, Set<Long> menuIds);

    /**
     * 处理角色删除时，删除关联授权数据
     *
     * @param roleId 角色编号
     */
    void processRoleDeleted(Long roleId);

    /**
     * 处理菜单删除时，删除关联授权数据
     *
     * @param menuId 菜单编号
     */
    void processMenuDeleted(Long menuId);

    /**
     * 获得角色拥有的菜单编号集合
     *
     * @param roleId 角色编号
     * @return 菜单编号集合
     */
    default Set<Long> getRoleMenuListByRoleId(Long roleId) {
        return getRoleMenuListByRoleId(singleton(roleId));
    }

    /**
     * 获得角色们拥有的菜单编号集合
     *
     * @param roleIds 角色编号数组
     * @return 菜单编号集合
     */
    Set<Long> getRoleMenuListByRoleId(Collection<Long> roleIds);

    /**
     * 获得拥有指定菜单的角色编号数组，从缓存中获取
     *
     * @param menuId 菜单编号
     * @return 角色编号数组
     */
    Set<Long> getMenuRoleIdListByMenuIdFromCache(Long menuId);

    // ========== 用户-角色的相关方法  ==========

    /**
     * 设置用户角色
     *
     * @param userId  角色编号
     * @param roleIds 角色编号集合
     */
    void assignUserRole(Long userId, Set<Long> roleIds);

    /**
     * 处理用户删除时，删除关联授权数据
     *
     * @param userId 用户编号
     */
    void processUserDeleted(Long userId);

    /**
     * 获得拥有多个角色的用户编号集合
     *
     * @param roleIds 角色编号集合
     * @return 用户编号集合
     */
    Set<Long> getUserRoleIdListByRoleId(Collection<Long> roleIds);

    /**
     * 获得用户拥有的角色编号集合
     *
     * @param userId 用户编号
     * @return 角色编号集合
     */
    Set<Long> getUserRoleIdListByUserId(Long userId);

    /**
     * 获得用户拥有的角色编号集合，从缓存中获取
     *
     * @param userId 用户编号
     * @return 角色编号集合
     */
    Set<Long> getUserRoleIdListByUserIdFromCache(Long userId);

    // ========== 用户-部门的相关方法  ==========

    /**
     * 设置角色的数据权限
     *
     * @param roleId           角色编号
     * @param dataScope        数据范围
     * @param dataScopeDeptIds 部门编号数组
     */
    void assignRoleDataScope(Long roleId, Integer dataScope, Set<Long> dataScopeDeptIds);

    /**
     * 获得登陆用户的部门数据权限
     *
     * @param userId 用户编号
     * @return 部门数据权限
     */
    DeptDataPermissionRespDTO getDeptDataPermission(Long userId);

}
