package cn.enlev.ainb.module.system.controller.admin.permission;

import cn.hutool.core.collection.CollUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.datapermission.core.annotation.DataPermission;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeListReqVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeRespVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeSaveVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeSortReqVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeTreeRespVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.NavNodeDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleDO;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties;
import cn.enlev.ainb.module.system.service.permission.GrantDecisionService;
import cn.enlev.ainb.module.system.service.permission.MenuService;
import cn.enlev.ainb.module.system.service.permission.NavNodeService;
import cn.enlev.ainb.module.system.service.permission.PermissionService;
import cn.enlev.ainb.module.system.service.permission.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

/**
 * 分端导航节点 Controller
 *
 * <p>两类端点泾渭分明：
 * <ul>
 *   <li>{@code GET /tree}：移动端工作台消费，<b>仅需登录</b>、按当前用户授权过滤后的导航树；
 *       与 {@code get-permission-info} 同源取权限集合（见 {@link #resolveAllowedPermissions}）</li>
 *   <li>其余 CRUD：admin「移动端导航」编辑器消费，逐个挂 {@code @PreAuthorize}；
 *       编辑器读的是<b>未过滤</b>的 {@code /list}，才能看到全部节点去编辑</li>
 * </ul>
 *
 * <p>🔴 <b>不碰 {@code AuthController} / {@code AuthConvert} / {@code getPermissionInfo}</b> ——
 * Web 侧走的老路一字不动，这是「修正版 B」相对 C 方案省掉的最大一块回归风险
 * （见「移动端优化方案」§4.4.3）
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - 分端导航节点")
@RestController
@RequestMapping("/system/nav-node")
@Validated
public class NavNodeController {

    @Resource
    private NavNodeService navNodeService;

    // ========== 下面这些只为「与 get-permission-info 同源」地算出当前用户的授权权限集合 ==========
    @Resource
    private PermissionProperties permissionProperties;
    @Resource
    private GrantDecisionService grantDecisionService;
    @Resource
    private PermissionService permissionService;
    @Resource
    private RoleService roleService;
    @Resource
    private MenuService menuService;

    @GetMapping("/tree")
    @Operation(summary = "获取当前用户的分端导航树", description = "移动端工作台消费；按登录用户授权过滤")
    @Parameter(name = "terminal", description = "端标识，缺省 app", example = "app")
    @DataPermission(enable = false) // 与 getPermissionInfo 一致：算权限这件事本身不该再被数据权限过滤一遍
    public CommonResult<List<NavNodeTreeRespVO>> getNavTree(
            @RequestParam(value = "terminal", required = false) String terminal) {
        Set<String> allowedPermissions = resolveAllowedPermissions(getLoginUserId());
        return success(navNodeService.getNavTree(terminal, allowedPermissions));
    }

    @PostMapping("/create")
    @Operation(summary = "创建导航节点")
    @PreAuthorize("@ss.hasPermission('system:nav-node:create')")
    public CommonResult<Long> createNavNode(@Valid @RequestBody NavNodeSaveVO createReqVO) {
        return success(navNodeService.createNavNode(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "修改导航节点")
    @PreAuthorize("@ss.hasPermission('system:nav-node:update')")
    public CommonResult<Boolean> updateNavNode(@Valid @RequestBody NavNodeSaveVO updateReqVO) {
        navNodeService.updateNavNode(updateReqVO);
        return success(true);
    }

    @PutMapping("/update-sort")
    @Operation(summary = "批量修改导航节点排序 / 父节点", description = "拖拽排序用，同一事务落库")
    @PreAuthorize("@ss.hasPermission('system:nav-node:update')")
    public CommonResult<Boolean> updateNavNodeSort(@Valid @RequestBody List<NavNodeSortReqVO> list) {
        navNodeService.updateNavNodeSort(list);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除导航节点")
    @Parameter(name = "id", description = "节点编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:nav-node:delete')")
    public CommonResult<Boolean> deleteNavNode(@RequestParam("id") Long id) {
        navNodeService.deleteNavNode(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除导航节点")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('system:nav-node:delete')")
    public CommonResult<Boolean> deleteNavNodeList(@RequestParam("ids") List<Long> ids) {
        navNodeService.deleteNavNodeList(ids);
        return success(true);
    }

    @GetMapping("/list")
    @Operation(summary = "获取导航节点列表", description = "admin「移动端导航」编辑器用；未做授权过滤，返回全部节点")
    @PreAuthorize("@ss.hasPermission('system:nav-node:query')")
    public CommonResult<List<NavNodeRespVO>> getNavNodeList(NavNodeListReqVO reqVO) {
        List<NavNodeDO> list = navNodeService.getNavNodeList(reqVO);
        return success(BeanUtils.toBean(list, NavNodeRespVO.class));
    }

    @GetMapping("/get")
    @Operation(summary = "获取导航节点")
    @Parameter(name = "id", description = "节点编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:nav-node:query')")
    public CommonResult<NavNodeRespVO> getNavNode(@RequestParam("id") Long id) {
        NavNodeDO node = navNodeService.getNavNode(id);
        return success(BeanUtils.toBean(node, NavNodeRespVO.class));
    }

    /**
     * 算出当前登录用户的授权权限串集合，<b>与 {@code AuthController#getPermissionInfo} 逐分支同源</b>：
     * {@code grant-enabled} 开 → 走 {@link GrantDecisionService#listAllowedPermissions}；
     * 关 → 由 role-menu 推（取用户可见菜单的 {@code permission} 串）
     *
     * <p>🔴 为什么不抽公共方法给两边共用：本次改造的红线是「不碰 {@code AuthController}」
     * （Web 路由生成链零回归，见类注释）。抽公共方法就得改 {@code getPermissionInfo} 去调它，
     * 反而动了那条红线。故这里<b>刻意复刻</b>同一套分支，代价是两处需同步 ——
     * 但两处读的是同一批 Service、同一判据（{@code permissionProperties.getGrantEnabled()}），
     * 漂移风险仅限「将来有人改了 getPermissionInfo 的权限来源却忘了这里」，已在两处注释互相指名
     */
    private Set<String> resolveAllowedPermissions(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        // 分支一：新授权引擎（grant-enabled）
        if (Boolean.TRUE.equals(permissionProperties.getGrantEnabled())) {
            return grantDecisionService.listAllowedPermissions(userId);
        }
        // 分支二：legacy —— 由 role-menu 推，与 getPermissionInfo 的 else 分支一致
        Set<Long> roleIds = permissionService.getUserRoleIdListByUserId(userId);
        if (CollUtil.isEmpty(roleIds)) {
            return Collections.emptySet();
        }
        List<RoleDO> roles = roleService.getRoleList(roleIds);
        roles.removeIf(role -> !CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus())); // 移除禁用角色
        Set<Long> menuIds = permissionService.getRoleMenuListByRoleId(convertSet(roles, RoleDO::getId));
        List<MenuDO> menuList = menuService.getMenuList(menuIds);
        menuList = menuService.filterDisableMenus(menuList);
        return convertSet(menuList, MenuDO::getPermission);
    }

}
