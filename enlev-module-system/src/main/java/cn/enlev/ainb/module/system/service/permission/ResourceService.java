package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;

import java.util.List;

/**
 * 权限资源 Service
 *
 * 负责资源的增删改查与物化路径维护。
 * 权限查询（鉴权用途）统一走 {@link MenuService#getMenuIdListByPermissionFromCache}，
 * 在 S1b 把它的查询对象从 system_menu 切到 sys_resource。
 *
 * @author 走进新时代
 */
public interface ResourceService {

    /**
     * 创建资源节点，自动计算并写入物化路径
     *
     * @return 资源编号
     */
    Long createResource(ResourceDO resource);

    /**
     * 更新资源节点。若 parentId 或 permission 变更，级联更新所有后代的物化路径
     */
    void updateResource(ResourceDO resource);

    /**
     * 删除资源（软删除）。如果有子节点则拒绝删除
     */
    void deleteResource(Long id);

    /**
     * 批量删除资源（软删除），全有或全无。
     *
     * <p>批量特有的约束是「子资源必须一起勾选」：只要有任意一个资源的子节点没被一起放进这批 ids，
     * 删除就会在资源树里留下孤儿（父没了子还在，物化路径 {@code path} 指向一个不存在的祖先），
     * 因此整批拒绝。父与子被同时勾选时视为「整棵子树一起删」，放行。
     *
     * <p>⚠️ 菜单派生 / 被授权引用 的校验在 Controller 里逐条做（与 {@link #deleteResource} 单条口径一致），
     * 本方法只负责「子节点必须一起删」这条约束，再在同一事务内执行删除。
     */
    void deleteResourceList(List<Long> ids);

    ResourceDO getResource(Long id);

    List<ResourceDO> getResourceListByPermission(String permission);

    /**
     * 迁移 system_menu 数据到 sys_resource
     *
     * 幂等操作：已经有 menu_id 对应行的跳过，没有的创建。
     * 调用时机：应用启动时由 ResourceMigrateRunner 触发一次；此后 MenuServiceImpl 的写路径
     * 会同步写入 sys_resource（双写阶段），直到验证正确后摘掉旧查询。
     *
     * <p>🔴 上面那句幂等判据只在<b>塌缩关闭</b>时成立。
     * {@code enlev.permission.collapse-enabled=true} 之后，一个身份行承载同一页的多个按钮、
     * 只保留一个代表 menu_id，于是「menu_id 查不到」不再等于「没迁过」。
     * 此时本方法在<b>非空库上整体跳过</b>（空库仍然跑，那是全新安装的引导路径），
     * 增量一律走 {@link #syncFromMenu(Long)}。判据与实测数字见实现处的注释
     */
    void migrateFromMenu();

    /**
     * 按菜单实时同步资源行（菜单增删改时调用）
     *
     * <h3>为什么必须有它：低代码平台不能要求重启</h3>
     *
     * {@link #migrateFromMenu()} 只在应用启动时跑一次。在它之外，
     * 菜单管理里新建一个按钮权限后，{@code sys_resource} 里没有对应行，
     * 于是<b>授权页的资源树里找不到它，配不了权限，必须重启后端</b>。
     *
     * 对一个低代码平台来说这是不可接受的：权限点本来就是运行期由管理员配出来的东西，
     * 配一个权限点要重启一次系统，等于这个能力不可用。
     *
     * 所以菜单的增 / 改 / 删都要同步到资源表。本方法与 {@code migrateFromMenu}
     * <b>共用同一套建行规则</b> —— 各写一份必然漂移，
     * 而漂移的表现是「重启前后同一个菜单迁出来的资源不一样」。
     *
     * <h3>判据是「是不是目录」</h3>
     *
     * 目录（type=1）不建资源行 —— 它是纯容器，靠 {@code listAllowedMenuIds} 沿菜单父链
     * 补全即可，从来不需要被显式授权。
     *
     * 非目录一律建：<b>包括没有 permission 的导航菜单</b>。它们建成 MENU 类型资源，
     * 靠 {@code menu_id} 映射回导航。不建的话，「整棵子树都没有权限标识」的那批菜单
     * （审批中心/发起流程、系统管理/地区管理、表单设计/表单设计器 …）
     * <b>对非超管永远不可见，且没有任何界面能把它配出来</b>。
     *
     * <h3>🔴 塌缩开启后，「菜单 : 资源行」从 1:1 变成 N:1</h3>
     *
     * {@code system:user:query} 与 {@code system:user:create} 都由 {@code system:user}
     * 这一行承载。于是两条判据都要换：
     * <ul>
     *   <li>「该不该新建」不再看 {@code selectByMenuId}，而看「这个身份有没有行」</li>
     *   <li>「该不该改显示字段」多一条前置：<b>本菜单是不是那一行的代表菜单</b>。
     *       不是就什么都不做 —— 那一行的名字属于代表菜单</li>
     *   <li>代表菜单被删 / 被改成目录时，先把 menu_id <b>转交</b>给同族的下一个活菜单，
     *       而不是删行：授权挂在这一行上，删行等于删掉整页按钮的权限</li>
     * </ul>
     * 换算口径与判定侧 {@code GrantDecisionService#lookupOf} 同源，
     * 都走 {@code CollapsedResourceLocator}。开关关闭时行为与塌缩前逐字节一致
     *
     * @param menuId 菜单编号；菜单已被删除时传入 id 也可以，会清掉对应资源
     */
    void syncFromMenu(Long menuId);

}
