package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DeptDataPermissionRespDTO;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.dept.DeptDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleMenuDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.UserRoleDO;
import cn.enlev.ainb.module.system.dal.dataobject.user.AdminUserDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.MenuMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.RoleMenuMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.UserRoleMapper;
import cn.enlev.ainb.module.system.enums.permission.DataScopeEnum;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.enums.permission.MenuTypeEnum;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import cn.enlev.ainb.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static cn.hutool.core.collection.ListUtil.toList;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;
import static cn.enlev.ainb.framework.common.util.collection.SetUtils.asSet;
import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.enlev.ainb.framework.test.core.util.RandomUtils.randomLongId;
import static cn.enlev.ainb.framework.test.core.util.RandomUtils.randomPojo;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Import({PermissionServiceImpl.class,
        cn.enlev.ainb.module.system.framework.permission.config.PermissionConfiguration.class})
public class PermissionServiceTest extends BaseDbUnitTest {

    @Resource
    private PermissionServiceImpl permissionService;

    @Resource
    private RoleMenuMapper roleMenuMapper;
    @Resource
    private UserRoleMapper userRoleMapper;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private GrantMapper grantMapper;
    @Resource
    private MenuMapper menuMapper;

    @Resource
    private cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties permissionProperties;

    @MockitoBean
    private RoleService roleService;
    @MockitoBean
    private MenuService menuService;
    @MockitoBean
    private DeptService deptService;
    @MockitoBean
    private AdminUserService userService;
    @MockitoBean
    private GrantDecisionService grantDecisionService;

    @Test
    public void testHasAnyPermissions_superAdmin() {
        try (MockedStatic<SpringUtil> springUtilMockedStatic = mockStatic(SpringUtil.class)) {
            springUtilMockedStatic.when(() -> SpringUtil.getBean(eq(PermissionServiceImpl.class)))
                    .thenReturn(permissionService);

            // 准备参数
            Long userId = 1L;
            String[] roles = new String[]{"system:user:query", "system:user:create"};
            // mock 用户登录的角色
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(100L));
            RoleDO role = randomPojo(RoleDO.class, o -> o.setId(100L)
                    .setStatus(CommonStatusEnum.ENABLE.getStatus()));
            when(roleService.getRoleListFromCache(eq(singleton(100L)))).thenReturn(toList(role));
            // mock 其它方法
            when(roleService.hasAnySuperAdmin(eq(asSet(100L)))).thenReturn(true);

            // 调用，并断言
            assertTrue(permissionService.hasAnyPermissions(userId, roles));
        }
    }

    @Test
    public void testHasAnyPermissions_normal_旧路径() {
        // grantEnabled = false（默认）→ 走 system_role_menu 旧路径
        try (MockedStatic<SpringUtil> springUtilMockedStatic = mockStatic(SpringUtil.class)) {
            springUtilMockedStatic.when(() -> SpringUtil.getBean(eq(PermissionServiceImpl.class)))
                    .thenReturn(permissionService);

            Long userId = 1L;
            String[] permissions = new String[]{"system:user:query", "system:user:create"};
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(100L));
            RoleDO role = randomPojo(RoleDO.class, o -> o.setId(100L)
                    .setStatus(CommonStatusEnum.ENABLE.getStatus()));
            when(roleService.getRoleListFromCache(eq(singleton(100L)))).thenReturn(toList(role));
            when(roleService.hasAnySuperAdmin(eq(asSet(100L)))).thenReturn(false);
            // 旧路径：mock menuService + roleMenuMapper
            Long menuId = 1000L;
            when(menuService.getMenuIdListByPermissionFromCache(
                    eq("system:user:create"))).thenReturn(singletonList(menuId));
            roleMenuMapper.insert(randomPojo(RoleMenuDO.class).setRoleId(100L).setMenuId(1000L));

            assertTrue(permissionService.hasAnyPermissions(userId, permissions));
        }
    }

    @Test
    public void testHasAnyPermissions_normal_新路径() {
        // grantEnabled = true → 走 GrantDecisionService 新路径
        permissionProperties.setGrantEnabled(true);
        try (MockedStatic<SpringUtil> springUtilMockedStatic = mockStatic(SpringUtil.class)) {
            springUtilMockedStatic.when(() -> SpringUtil.getBean(eq(PermissionServiceImpl.class)))
                    .thenReturn(permissionService);

            Long userId = 1L;
            String[] permissions = new String[]{"system:user:query", "system:user:create"};
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(100L));
            RoleDO role = randomPojo(RoleDO.class, o -> o.setId(100L)
                    .setStatus(CommonStatusEnum.ENABLE.getStatus()));
            when(roleService.getRoleListFromCache(eq(singleton(100L)))).thenReturn(toList(role));
            when(roleService.hasAnySuperAdmin(eq(asSet(100L)))).thenReturn(false);
            when(grantDecisionService.hasPermission(eq(userId), eq("system:user:create"), eq(null)))
                    .thenReturn(true);

            assertTrue(permissionService.hasAnyPermissions(userId, permissions));
        } finally {
            permissionProperties.setGrantEnabled(false); // 复位
        }
    }

    @Test
    public void testHasAnyRoles() {
        try (MockedStatic<SpringUtil> springUtilMockedStatic = mockStatic(SpringUtil.class)) {
            springUtilMockedStatic.when(() -> SpringUtil.getBean(eq(PermissionServiceImpl.class)))
                    .thenReturn(permissionService);

            // 准备参数
            Long userId = 1L;
            String[] roles = new String[]{"yunai", "tudou"};
            // mock 用户与角色的缓存
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(100L));
            RoleDO role = randomPojo(RoleDO.class, o -> o.setId(100L).setCode("tudou")
                    .setStatus(CommonStatusEnum.ENABLE.getStatus()));
            when(roleService.getRoleListFromCache(eq(singleton(100L)))).thenReturn(toList(role));

            // 调用，并断言
            assertTrue(permissionService.hasAnyRoles(userId, roles));
        }
    }

    // ========== 角色-菜单的相关方法  ==========

    @Test
    public void testAssignRoleMenu() {
        // 准备参数
        Long roleId = 1L;
        Set<Long> menuIds = asSet(200L, 300L);
        // mock 数据
        RoleMenuDO roleMenu01 = randomPojo(RoleMenuDO.class).setRoleId(1L).setMenuId(100L);
        roleMenuMapper.insert(roleMenu01);
        RoleMenuDO roleMenu02 = randomPojo(RoleMenuDO.class).setRoleId(1L).setMenuId(200L);
        roleMenuMapper.insert(roleMenu02);

        // 调用
        permissionService.assignRoleMenu(roleId, menuIds);
        // 断言
        List<RoleMenuDO> roleMenuList = roleMenuMapper.selectList();
        assertEquals(2, roleMenuList.size());
        assertEquals(1L, roleMenuList.get(0).getRoleId());
        assertEquals(200L, roleMenuList.get(0).getMenuId());
        assertEquals(1L, roleMenuList.get(1).getRoleId());
        assertEquals(300L, roleMenuList.get(1).getMenuId());
    }

    @Test
    public void testProcessRoleDeleted() {
        // 准备参数
        Long roleId = randomLongId();
        // mock 数据 UserRole
        UserRoleDO userRoleDO01 = randomPojo(UserRoleDO.class, o -> o.setRoleId(roleId)); // 被删除
        userRoleMapper.insert(userRoleDO01);
        UserRoleDO userRoleDO02 = randomPojo(UserRoleDO.class); // 不被删除
        userRoleMapper.insert(userRoleDO02);
        // mock 数据 RoleMenu
        RoleMenuDO roleMenuDO01 = randomPojo(RoleMenuDO.class, o -> o.setRoleId(roleId)); // 被删除
        roleMenuMapper.insert(roleMenuDO01);
        RoleMenuDO roleMenuDO02 = randomPojo(RoleMenuDO.class); // 不被删除
        roleMenuMapper.insert(roleMenuDO02);

        // 调用
        permissionService.processRoleDeleted(roleId);
        // 断言数据 RoleMenuDO
        List<RoleMenuDO> dbRoleMenus = roleMenuMapper.selectList();
        assertEquals(1, dbRoleMenus.size());
        assertPojoEquals(dbRoleMenus.get(0), roleMenuDO02);
        // 断言数据 UserRoleDO
        List<UserRoleDO> dbUserRoles = userRoleMapper.selectList();
        assertEquals(1, dbUserRoles.size());
        assertPojoEquals(dbUserRoles.get(0), userRoleDO02);
    }

    @Test
    public void testProcessMenuDeleted() {
        // 准备参数
        Long menuId = randomLongId();
        // mock 数据
        RoleMenuDO roleMenuDO01 = randomPojo(RoleMenuDO.class, o -> o.setMenuId(menuId)); // 被删除
        roleMenuMapper.insert(roleMenuDO01);
        RoleMenuDO roleMenuDO02 = randomPojo(RoleMenuDO.class); // 不被删除
        roleMenuMapper.insert(roleMenuDO02);

        // 调用
        permissionService.processMenuDeleted(menuId);
        // 断言数据
        List<RoleMenuDO> dbRoleMenus = roleMenuMapper.selectList();
        assertEquals(1, dbRoleMenus.size());
        assertPojoEquals(dbRoleMenus.get(0), roleMenuDO02);
    }

    // ========== 角色-菜单授权（sys_grant 套餐重算）的相关方法  ==========

    /**
     * 套餐重算只能碰「菜单派生的资源」，绝不能删掉行级数据集授权
     *
     * <p>回归的是 2026-10-10 的实测事故：一次在线表单发布触发 {@code assignRoleMenuGrants}，
     * 把超管在 system:dept / system:user 上的「全部数据」授权当成「超出目标集合」删了，
     * 于是组织管理只剩 1 个部门、用户管理只剩 1 个用户，且不报任何错。
     */
    @Test
    public void testAssignRoleMenuGrants_不误删非菜单派生的授权() {
        // 准备参数
        Long roleId = 100L;
        Long keepMenuId = 1000L; // 在本次提交的菜单集合里
        Long dropMenuId = 2000L; // 不在，属于套餐降级，该删
        // mock 数据：两个菜单派生资源 + 一个行级数据集资源（menu_id 为 NULL）
        ResourceDO keepResource = insertResource(ResourceTypeEnum.MENU, keepMenuId, null);
        ResourceDO dropResource = insertResource(ResourceTypeEnum.MENU, dropMenuId, null);
        ResourceDO datasetResource = insertResource(ResourceTypeEnum.DATASET, null, "system_users");
        insertPackageGrant(roleId, keepResource.getId());
        GrantDO dropGrant = insertPackageGrant(roleId, dropResource.getId());
        GrantDO datasetGrant = insertPackageGrant(roleId, datasetResource.getId());

        // 调用
        permissionService.assignRoleMenuGrants(roleId, asSet(keepMenuId));

        // 断言：数据集授权与目标菜单授权都还在，只有超出集合的菜单授权被删
        List<Long> resourceIds = convertList(grantMapper.selectList(), GrantDO::getResourceId);
        assertEquals(2, resourceIds.size());
        assertTrue(resourceIds.contains(keepResource.getId()));
        assertTrue(resourceIds.contains(datasetResource.getId()));
        assertFalse(resourceIds.contains(dropResource.getId()));
        assertNull(grantMapper.selectById(dropGrant.getId()));
        assertNotNull(grantMapper.selectById(datasetGrant.getId()));
    }

    /**
     * {@link #testAssignRoleMenuGrants_不误删非菜单派生的授权} 的收窄语义版本
     *
     * <p>{@code retainRoleMenuGrants} 只删不加，走的是同一个 syncRoleMenuGrants。
     * 单独测一遍，是为了防止有人日后把过滤逻辑内联进 assign 那条分支。
     */
    @Test
    public void testRetainRoleMenuGrants_不误删非菜单派生的授权() {
        // 准备参数
        Long roleId = 100L;
        // mock 数据
        ResourceDO keepResource = insertResource(ResourceTypeEnum.MENU, 1000L, null);
        ResourceDO datasetResource = insertResource(ResourceTypeEnum.DATASET, null, "system_dept");
        insertPackageGrant(roleId, keepResource.getId());
        insertPackageGrant(roleId, datasetResource.getId());

        // 调用：菜单集合为空 = 套餐里什么菜单都没了
        permissionService.retainRoleMenuGrants(roleId, Set.of());

        // 断言：菜单授权被删空，数据集授权不受影响
        List<GrantDO> grants = grantMapper.selectList();
        assertEquals(1, grants.size());
        assertEquals(datasetResource.getId(), grants.get(0).getResourceId());
    }

    private ResourceDO insertResource(ResourceTypeEnum type, Long menuId, String datasetTable) {
        ResourceDO resource = new ResourceDO();
        resource.setPermission("test:" + type.getCode() + ":" + randomLongId());
        resource.setName(resource.getPermission());
        resource.setType(type.getCode());
        resource.setParentId(0L);
        resource.setPath("/" + resource.getPermission());
        resource.setDepth((short) 1);
        resource.setSort(0);
        resource.setDefaultEffect(type.defaultEffect());
        resource.setStatus(CommonStatusEnum.ENABLE.getStatus());
        resource.setMenuId(menuId);
        resource.setDatasetTable(datasetTable);
        resourceMapper.insert(resource);
        return resource;
    }

    /** 套餐型授权行：ROLE + VIEW + ALLOW + 无上下文，即 selectListPackageStyleByRole 的命中形状 */
    private GrantDO insertPackageGrant(Long roleId, Long resourceId) {
        return insertPackageGrant(roleId, resourceId, GrantActionEnum.VIEW.getCode());
    }

    private GrantDO insertPackageGrant(Long roleId, Long resourceId, String action) {
        GrantDO grant = new GrantDO();
        grant.setSubjectType(SubjectTypeEnum.ROLE.name());
        grant.setSubjectRefId(roleId);
        grant.setResourceId(resourceId);
        grant.setAction(action);
        grant.setEffect("ALLOW");
        grantMapper.insert(grant);
        return grant;
    }

    // ==================== 塌缩开关打开后的套餐换算（规则 R5） ====================

    /**
     * 🔴 R5 要修的就是这一条：套餐只勾了「新增」按钮、没勾「查询」按钮
     *
     * <p>塌缩迁移之后，兄弟资源行（{@code test:user:query} / {@code test:user:create}）
     * 都退役了，只剩一个身份行 {@code test:user}，而它<b>只能挂一个 menu_id</b>
     * （{@code ResourceCollapsePlanner#chooseMenuId} 挑出来的那一个）。
     * 于是「菜单集合 → {@code selectListByMenuIds} → 资源集合」这条换算断了：
     * 按 menu_id 查不到身份行，目标集合为空，原有的授权反被当成「超出目标集合」删掉。
     *
     * <p>实测规模：107 个身份的 menu_id 扇入 &gt; 1，核心遗留族 168 个 (角色,身份) 组合里
     * 55 个（32.7%）是部分勾选，四个租户（1 / 121 / 122 / 162）全都有。
     *
     * <p>打开态走权限串就没这个问题：勾了哪个按钮，就解析出那个按钮对应的动作。
     */
    @Test
    public void testAssignRoleMenuGrants_塌缩后部分勾选仍换算得出动作() {
        // 准备参数
        Long roleId = 100L;
        Long queryMenuId = insertMenu("test:user:query");
        Long createMenuId = insertMenu("test:user:create");
        // 迁移后的形态：身份行的 menu_id 是「查询」那个菜单
        ResourceDO identity = insertResource("test:user", queryMenuId);
        GrantDO viewGrant = insertPackageGrant(roleId, identity.getId());

        // 调用：套餐里只有「新增」
        withCollapseEnabled(() -> permissionService.assignRoleMenuGrants(roleId, asSet(createMenuId)));

        // 断言：VIEW 被删、CREATE 被建，落在同一个身份行上
        assertNull(grantMapper.selectById(viewGrant.getId()));
        List<GrantDO> grants = grantMapper.selectList();
        assertEquals(1, grants.size());
        assertEquals(identity.getId(), grants.get(0).getResourceId());
        assertEquals(GrantActionEnum.CREATE.getCode(), grants.get(0).getAction());
    }

    /**
     * {@link #testAssignRoleMenuGrants_塌缩后部分勾选仍换算得出动作} 的关闭态对照
     *
     * <p>同一份数据、开关关着 ⟹ 授权被删空、什么都不新建。
     * 这条用例钉的是<b>缺陷本身</b>，不是期望行为：它说明这两个开关状态在迁移后不等价，
     * 从而说明「迁移落库」与「翻 collapse-enabled」为什么必须同批
     */
    @Test
    public void testAssignRoleMenuGrants_关闭态部分勾选会丢掉权限() {
        // 准备参数：与打开态那条用例逐字节相同
        Long roleId = 100L;
        Long queryMenuId = insertMenu("test:user:query");
        Long createMenuId = insertMenu("test:user:create");
        ResourceDO identity = insertResource("test:user", queryMenuId);
        insertPackageGrant(roleId, identity.getId());

        // 调用
        permissionService.assignRoleMenuGrants(roleId, asSet(createMenuId));

        // 断言：menu_id 换算认不出 createMenuId ⟹ 目标为空 ⟹ 原有授权被删、没有新建
        assertTrue(grantMapper.selectList().isEmpty());
        assertNotNull(resourceMapper.selectById(identity.getId()));
    }

    /**
     * 🔴 删除粒度必须是 {@code (resource_id, action)}，不能是 {@code resource_id}
     *
     * <p>塌缩后一个身份行上会同时挂着 VIEW / CREATE / DELETE 多行套餐授权。
     * 仍按 resource_id 比的话，「套餐降级到只剩查询」会得出
     * 「这个资源还在目标集合里」⟹ 三条全留 ⟹ <b>权限收不回，且不报错</b>
     */
    @Test
    public void testRetainRoleMenuGrants_塌缩后按动作粒度收窄() {
        // 准备参数
        Long roleId = 100L;
        Long queryMenuId = insertMenu("test:dict:query");
        Long createMenuId = insertMenu("test:dict:create");
        Long deleteMenuId = insertMenu("test:dict:delete");
        ResourceDO identity = insertResource("test:dict", queryMenuId);
        insertPackageGrant(roleId, identity.getId(), GrantActionEnum.VIEW.getCode());
        GrantDO createGrant = insertPackageGrant(roleId, identity.getId(),
                GrantActionEnum.CREATE.getCode());
        GrantDO deleteGrant = insertPackageGrant(roleId, identity.getId(),
                GrantActionEnum.DELETE.getCode());

        // 调用：套餐降级到只剩「查询」
        withCollapseEnabled(() -> permissionService.retainRoleMenuGrants(roleId, asSet(queryMenuId)));

        // 断言
        assertNull(grantMapper.selectById(createGrant.getId()));
        assertNull(grantMapper.selectById(deleteGrant.getId()));
        List<GrantDO> left = grantMapper.selectList();
        assertEquals(1, left.size());
        assertEquals(GrantActionEnum.VIEW.getCode(), left.get(0).getAction());
        // 上面两个菜单没进套餐，也不该因此新建任何东西（retainOnly 是只删不加）
        assertFalse(convertList(left, GrantDO::getAction)
                .contains(GrantActionEnum.CREATE.getCode()));
    }

    /**
     * 🔴 空权限串的菜单必须回落 {@code menu_id} 口径，否则它的授权会被整片删掉
     *
     * <p>规则 R3：空权限行不合并、不铸串，授权原样留在自己那行上。
     * 而这类菜单压根解析不出身份串，走不了权限串那条路。
     * 少了这个回落，下一次套餐重算会把它们当成「超出目标集合」删光 ——
     * 实测 119 行资源、160 条活授权、3 个角色，表现是这三个角色的菜单可见性整片消失，
     * <b>且不报任何错</b>
     */
    @Test
    public void testAssignRoleMenuGrants_塌缩后空权限菜单的授权不被误删() {
        // 准备参数：一个没有权限串的菜单（导航表里 permission 是 NOT NULL DEFAULT ''）
        Long roleId = 100L;
        Long blankMenuId = insertMenu("");
        ResourceDO blankResource = insertResource("", blankMenuId);
        GrantDO grant = insertPackageGrant(roleId, blankResource.getId());

        // 调用
        withCollapseEnabled(() -> permissionService.assignRoleMenuGrants(roleId, asSet(blankMenuId)));

        // 断言
        assertNotNull(grantMapper.selectById(grant.getId()));
        assertEquals(1, grantMapper.selectList().size());
    }

    /**
     * 🔴 停用的遗留行同样要回落：它的授权一条都不该动（规则 R4）
     *
     * <p>R4 让停用行不参与塌缩，于是它对应的身份行可能压根没被建出来。
     * 这时权限串那条路查不到启用行（{@code selectListByPermissions} 过滤 status），
     * 必须回落到 menu_id 口径 —— 而 {@code selectListByMenuIds} 不过滤 status，
     * 于是目标集合与关闭态一致。
     *
     * <p>实测 3 行属于这种（{@code infra:build:list} / {@code infra:codegen:query} /
     * {@code infra:swagger:list}），共 12 条活授权，四个租户的套餐角色各一条
     */
    @Test
    public void testAssignRoleMenuGrants_塌缩后停用资源行沿用关闭态口径() {
        // 准备参数
        Long roleId = 100L;
        Long menuId = insertMenu("test:build:list");
        ResourceDO disabled = insertResource("test:build:list", menuId);
        disabled.setStatus(CommonStatusEnum.DISABLE.getStatus());
        resourceMapper.updateById(disabled);
        GrantDO grant = insertPackageGrant(roleId, disabled.getId());

        // 调用
        withCollapseEnabled(() -> permissionService.assignRoleMenuGrants(roleId, asSet(menuId)));

        // 断言：既没被删，也没在别处新建出一条
        assertNotNull(grantMapper.selectById(grant.getId()));
        assertEquals(1, grantMapper.selectList().size());
    }

    /**
     * 档 C（业务专有动词）不塌缩：目标仍是它自己那一行 + VIEW，与关闭态一致
     *
     * <p>{@code cancel} 不在动词映射表里，{@code resolve} 给出的 resourceKey 就是整串、
     * action 恒为 VIEW。所以「不塌缩的档」在 {@code collapseTargets} 里不需要单独分支 ——
     * 这条用例钉的正是那个「不需要分支」的结论，改解析层分档时它会先红
     */
    @Test
    public void testAssignRoleMenuGrants_塌缩后业务专有动词留在自己那行() {
        // 准备参数
        Long roleId = 100L;
        Long menuId = insertMenu("test:leave:cancel");
        ResourceDO resource = insertResource("test:leave:cancel", menuId);
        GrantDO grant = insertPackageGrant(roleId, resource.getId());

        // 调用
        withCollapseEnabled(() -> permissionService.assignRoleMenuGrants(roleId, asSet(menuId)));

        // 断言
        assertNotNull(grantMapper.selectById(grant.getId()));
        assertEquals(1, grantMapper.selectList().size());
    }

    /**
     * 同一权限串有多行启用资源时，取 <b>id 最小的</b>
     *
     * <p>必须与 {@code ResourceCollapsePlanner} 建锚点的口径一致：不一致的话，
     * 迁移把授权写在 A 行、套餐重算对齐到 B 行，两行都活着，
     * 于是授权页出现「配了却删不掉」的重复行。实测今天有 4 个权限串是重复的
     */
    @Test
    public void testAssignRoleMenuGrants_塌缩后同串多行取最小id() {
        // 准备参数
        Long roleId = 100L;
        Long menuId = insertMenu("test:job:query");
        ResourceDO first = insertResource("test:job", menuId);
        ResourceDO second = insertResource("test:job", menuId);
        assertTrue(first.getId() < second.getId(), "自增主键下先插入的 id 更小，否则本用例没有意义");

        // 调用
        withCollapseEnabled(() -> permissionService.assignRoleMenuGrants(roleId, asSet(menuId)));

        // 断言：只在小 id 那行上建授权
        List<GrantDO> grants = grantMapper.selectList();
        assertEquals(1, grants.size());
        assertEquals(first.getId(), grants.get(0).getResourceId());
    }

    /**
     * 🔴 打开态把动作过滤放宽成「不限」之后，行级数据集授权仍然必须活着
     *
     * <p>{@code selectListPackageStyleByRole} 的动作过滤从 {@code eq(VIEW)} 改成
     * {@code eqIfPresent} 之后，它能捞到的行变多了 —— 包括动作不是 VIEW 的那些。
     * 挡住它们的仍然是 {@code filterMenuDerived}（判据 {@code menu_id != null}），
     * 而 DATASET / COLUMN / FIELD 的 menu_id 恒为 NULL。
     *
     * <p>这条用例是那次真实事故的回归钉子：一次在线表单发布触发套餐重算，
     * 把超管在 {@code system:dept} / {@code system:user} 上的「全部数据」授权删了，
     * 组织管理只显示 1 个部门、用户管理只显示 1 个用户，<b>且不报任何错</b>
     */
    @Test
    public void testAssignRoleMenuGrants_塌缩后仍不误删非菜单派生的授权() {
        // 准备参数
        Long roleId = 100L;
        Long keepMenuId = insertMenu("test:keep:query");
        ResourceDO keepResource = insertResource("test:keep", keepMenuId);
        ResourceDO datasetResource = insertResource(ResourceTypeEnum.DATASET, null, "system_users");
        insertPackageGrant(roleId, keepResource.getId());
        // 行级授权：动作不是 VIEW，关闭态下压根查不出来，打开态会被捞进 existing
        GrantDO rowLevelGrant = insertPackageGrant(roleId, datasetResource.getId(),
                GrantActionEnum.VIEW_ALL.getCode());

        // 调用
        withCollapseEnabled(() -> permissionService.assignRoleMenuGrants(roleId, asSet(keepMenuId)));

        // 断言
        assertNotNull(grantMapper.selectById(rowLevelGrant.getId()));
        assertEquals(2, grantMapper.selectList().size());
    }

    /**
     * 打开塌缩开关跑一段，跑完一定还原
     *
     * <p>⚠️ {@code permissionProperties} 是整个测试上下文共享的单例。漏了还原，
     * 后面所有用例都会跑在打开态下 —— 而它们的断言全是按关闭态写的，
     * 失败信息会指向一堆毫不相干的地方
     */
    private void withCollapseEnabled(Runnable body) {
        Boolean original = permissionProperties.getCollapseEnabled();
        permissionProperties.setCollapseEnabled(true);
        try {
            body.run();
        } finally {
            permissionProperties.setCollapseEnabled(original);
        }
    }

    private Long insertMenu(String permission) {
        MenuDO menu = new MenuDO();
        menu.setName(StrUtil.isBlank(permission) ? "无权限串的菜单" : permission);
        menu.setPermission(permission);
        menu.setType(MenuTypeEnum.BUTTON.getType());
        menu.setSort(0);
        menu.setParentId(0L);
        menu.setStatus(CommonStatusEnum.ENABLE.getStatus());
        menuMapper.insert(menu);
        return menu.getId();
    }

    /** 启用态的遗留资源行（type=ACTION），menu_id 由入参决定 */
    private ResourceDO insertResource(String permission, Long menuId) {
        ResourceDO resource = new ResourceDO();
        resource.setPermission(permission);
        resource.setName(StrUtil.isBlank(permission) ? "空权限行" : permission);
        resource.setType(ResourceTypeEnum.ACTION.getCode());
        resource.setParentId(0L);
        resource.setPath("/" + permission);
        resource.setDepth((short) 1);
        resource.setSort(0);
        resource.setDefaultEffect(ResourceTypeEnum.ACTION.defaultEffect());
        resource.setStatus(CommonStatusEnum.ENABLE.getStatus());
        resource.setMenuId(menuId);
        resourceMapper.insert(resource);
        return resource;
    }

    @Test
    public void testGetRoleMenuIds_superAdmin() {
        // 准备参数
        Long roleId = 100L;
        // mock 方法
        when(roleService.hasAnySuperAdmin(eq(singleton(100L)))).thenReturn(true);
        List<MenuDO> menuList = singletonList(randomPojo(MenuDO.class).setId(1L));
        when(menuService.getMenuList()).thenReturn(menuList);

        // 调用
        Set<Long> menuIds = permissionService.getRoleMenuListByRoleId(roleId);
        // 断言
        assertEquals(singleton(1L), menuIds);
    }

    @Test
    public void testGetRoleMenuIds_normal() {
        // 准备参数
        Long roleId = 100L;
        // mock 数据
        RoleMenuDO roleMenu01 = randomPojo(RoleMenuDO.class).setRoleId(100L).setMenuId(1L);
        roleMenuMapper.insert(roleMenu01);
        RoleMenuDO roleMenu02 = randomPojo(RoleMenuDO.class).setRoleId(100L).setMenuId(2L);
        roleMenuMapper.insert(roleMenu02);

        // 调用
        Set<Long> menuIds = permissionService.getRoleMenuListByRoleId(roleId);
        // 断言
        assertEquals(asSet(1L, 2L), menuIds);
    }

    @Test
    public void testGetMenuRoleIdListByMenuIdFromCache() {
        // 准备参数
        Long menuId = 1L;
        // mock 数据
        RoleMenuDO roleMenu01 = randomPojo(RoleMenuDO.class).setRoleId(100L).setMenuId(1L);
        roleMenuMapper.insert(roleMenu01);
        RoleMenuDO roleMenu02 = randomPojo(RoleMenuDO.class).setRoleId(200L).setMenuId(1L);
        roleMenuMapper.insert(roleMenu02);

        // 调用
        Set<Long> roleIds = permissionService.getMenuRoleIdListByMenuIdFromCache(menuId);
        // 断言
        assertEquals(asSet(100L, 200L), roleIds);
    }

    // ========== 用户-角色的相关方法  ==========

    @Test
    public void testAssignUserRole() {
        // 准备参数
        Long userId = 1L;
        Set<Long> roleIds = asSet(200L, 300L);
        // mock 数据
        UserRoleDO userRole01 = randomPojo(UserRoleDO.class).setUserId(1L).setRoleId(100L);
        userRoleMapper.insert(userRole01);
        UserRoleDO userRole02 = randomPojo(UserRoleDO.class).setUserId(1L).setRoleId(200L);
        userRoleMapper.insert(userRole02);

        // 调用
        permissionService.assignUserRole(userId, roleIds);
        // 断言
        List<UserRoleDO> userRoleDOList = userRoleMapper.selectList();
        assertEquals(2, userRoleDOList.size());
        assertEquals(1L, userRoleDOList.get(0).getUserId());
        assertEquals(200L, userRoleDOList.get(0).getRoleId());
        assertEquals(1L, userRoleDOList.get(1).getUserId());
        assertEquals(300L, userRoleDOList.get(1).getRoleId());
    }

    @Test
    public void testProcessUserDeleted() {
        // 准备参数
        Long userId = randomLongId();
        // mock 数据
        UserRoleDO userRoleDO01 = randomPojo(UserRoleDO.class, o -> o.setUserId(userId)); // 被删除
        userRoleMapper.insert(userRoleDO01);
        UserRoleDO userRoleDO02 = randomPojo(UserRoleDO.class); // 不被删除
        userRoleMapper.insert(userRoleDO02);

        // 调用
        permissionService.processUserDeleted(userId);
        // 断言数据
        List<UserRoleDO> dbUserRoles = userRoleMapper.selectList();
        assertEquals(1, dbUserRoles.size());
        assertPojoEquals(dbUserRoles.get(0), userRoleDO02);
    }

    @Test
    public void testGetUserRoleIdListByUserId() {
        // 准备参数
        Long userId = 1L;
        // mock 数据
        UserRoleDO userRoleDO01 = randomPojo(UserRoleDO.class, o -> o.setUserId(1L).setRoleId(10L));
        userRoleMapper.insert(userRoleDO01);
        UserRoleDO roleMenuDO02 = randomPojo(UserRoleDO.class, o -> o.setUserId(1L).setRoleId(20L));
        userRoleMapper.insert(roleMenuDO02);

        // 调用
        Set<Long> result = permissionService.getUserRoleIdListByUserId(userId);
        // 断言
        assertEquals(asSet(10L, 20L), result);
    }

    @Test
    public void testGetUserRoleIdListByUserIdFromCache() {
        // 准备参数
        Long userId = 1L;
        // mock 数据
        UserRoleDO userRoleDO01 = randomPojo(UserRoleDO.class, o -> o.setUserId(1L).setRoleId(10L));
        userRoleMapper.insert(userRoleDO01);
        UserRoleDO roleMenuDO02 = randomPojo(UserRoleDO.class, o -> o.setUserId(1L).setRoleId(20L));
        userRoleMapper.insert(roleMenuDO02);

        // 调用
        Set<Long> result = permissionService.getUserRoleIdListByUserIdFromCache(userId);
        // 断言
        assertEquals(asSet(10L, 20L), result);
    }

    @Test
    public void testGetUserRoleIdsFromCache() {
        // 准备参数
        Long userId = 1L;
        // mock 数据
        UserRoleDO userRoleDO01 = randomPojo(UserRoleDO.class, o -> o.setUserId(1L).setRoleId(10L));
        userRoleMapper.insert(userRoleDO01);
        UserRoleDO roleMenuDO02 = randomPojo(UserRoleDO.class, o -> o.setUserId(1L).setRoleId(20L));
        userRoleMapper.insert(roleMenuDO02);

        // 调用
        Set<Long> result = permissionService.getUserRoleIdListByUserIdFromCache(userId);
        // 断言
        assertEquals(asSet(10L, 20L), result);
    }

    @Test
    public void testGetUserRoleIdListByRoleId() {
        // 准备参数
        Collection<Long> roleIds = asSet(10L, 20L);
        // mock 数据
        UserRoleDO userRoleDO01 = randomPojo(UserRoleDO.class, o -> o.setUserId(1L).setRoleId(10L));
        userRoleMapper.insert(userRoleDO01);
        UserRoleDO roleMenuDO02 = randomPojo(UserRoleDO.class, o -> o.setUserId(2L).setRoleId(20L));
        userRoleMapper.insert(roleMenuDO02);

        // 调用
        Set<Long> result = permissionService.getUserRoleIdListByRoleId(roleIds);
        // 断言
        assertEquals(asSet(1L, 2L), result);
    }

    @Test
    public void testGetEnableUserRoleListByUserIdFromCache() {
        try (MockedStatic<SpringUtil> springUtilMockedStatic = mockStatic(SpringUtil.class)) {
            springUtilMockedStatic.when(() -> SpringUtil.getBean(eq(PermissionServiceImpl.class)))
                    .thenReturn(permissionService);

            // 准备参数
            Long userId = 1L;
            // mock 用户登录的角色
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(100L));
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(200L));
            RoleDO role01 = randomPojo(RoleDO.class, o -> o.setId(100L)
                    .setStatus(CommonStatusEnum.ENABLE.getStatus()));
            RoleDO role02 = randomPojo(RoleDO.class, o -> o.setId(200L)
                    .setStatus(CommonStatusEnum.DISABLE.getStatus()));
            when(roleService.getRoleListFromCache(eq(asSet(100L, 200L))))
                    .thenReturn(toList(role01, role02));

            // 调用
            List<RoleDO> result = permissionService.getEnableUserRoleListByUserIdFromCache(userId);
            // 断言
            assertEquals(1, result.size());
            assertPojoEquals(role01, result.get(0));
        }
    }

    // ========== 用户-部门的相关方法  ==========

    @Test
    public void testAssignRoleDataScope() {
        // 准备参数
        Long roleId = 1L;
        Integer dataScope = 2;
        Set<Long> dataScopeDeptIds = asSet(10L, 20L);

        // 调用
        permissionService.assignRoleDataScope(roleId, dataScope, dataScopeDeptIds);
        // 断言
        verify(roleService).updateRoleDataScope(eq(roleId), eq(dataScope), eq(dataScopeDeptIds));
    }

    @Test
    public void testGetDeptDataPermission_All() {
        try (MockedStatic<SpringUtil> springUtilMockedStatic = mockStatic(SpringUtil.class)) {
            springUtilMockedStatic.when(() -> SpringUtil.getBean(eq(PermissionServiceImpl.class)))
                    .thenReturn(permissionService);

            // 准备参数
            Long userId = 1L;
            // mock 用户的角色编号
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(2L));
            // mock 获得用户的角色
            RoleDO roleDO = randomPojo(RoleDO.class, o -> o.setDataScope(DataScopeEnum.ALL.getScope())
                    .setStatus(CommonStatusEnum.ENABLE.getStatus()));
            when(roleService.getRoleListFromCache(eq(singleton(2L)))).thenReturn(toList(roleDO));

            // 调用
            DeptDataPermissionRespDTO result = permissionService.getDeptDataPermission(userId);
            // 断言
            assertTrue(result.getAll());
            assertFalse(result.getSelf());
            assertTrue(CollUtil.isEmpty(result.getDeptIds()));
        }
    }

    @Test
    public void testGetDeptDataPermission_DeptCustom() {
        try (MockedStatic<SpringUtil> springUtilMockedStatic = mockStatic(SpringUtil.class)) {
            springUtilMockedStatic.when(() -> SpringUtil.getBean(eq(PermissionServiceImpl.class)))
                    .thenReturn(permissionService);

            // 准备参数
            Long userId = 1L;
            // mock 用户的角色编号
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(2L));
            // mock 获得用户的角色
            RoleDO roleDO = randomPojo(RoleDO.class, o -> o.setDataScope(DataScopeEnum.DEPT_CUSTOM.getScope())
                    .setStatus(CommonStatusEnum.ENABLE.getStatus()));
            when(roleService.getRoleListFromCache(eq(singleton(2L)))).thenReturn(toList(roleDO));
            // mock 部门的返回
            when(userService.getUser(eq(1L))).thenReturn(new AdminUserDO().setDeptId(3L),
                    null, null); // 最后返回 null 的目的，看看会不会重复调用

            // 调用
            DeptDataPermissionRespDTO result = permissionService.getDeptDataPermission(userId);
            // 断言
            assertFalse(result.getAll());
            assertFalse(result.getSelf());
            assertEquals(roleDO.getDataScopeDeptIds().size() + 1, result.getDeptIds().size());
            assertTrue(CollUtil.containsAll(result.getDeptIds(), roleDO.getDataScopeDeptIds()));
            assertTrue(CollUtil.contains(result.getDeptIds(), 3L));
        }
    }

    @Test
    public void testGetDeptDataPermission_DeptCustom_userDeptIdNull() {
        try (MockedStatic<SpringUtil> springUtilMockedStatic = mockStatic(SpringUtil.class)) {
            springUtilMockedStatic.when(() -> SpringUtil.getBean(eq(PermissionServiceImpl.class)))
                    .thenReturn(permissionService);

            // 准备参数
            Long userId = 1L;
            // mock 用户的角色编号
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(2L));
            // mock 获得用户的角色
            RoleDO roleDO = randomPojo(RoleDO.class, o -> o.setDataScope(DataScopeEnum.DEPT_CUSTOM.getScope())
                    .setStatus(CommonStatusEnum.ENABLE.getStatus()));
            when(roleService.getRoleListFromCache(eq(singleton(2L)))).thenReturn(toList(roleDO));
            // mock 部门的返回：用户未设置部门
            when(userService.getUser(eq(1L))).thenReturn(new AdminUserDO()); // deptId 为 null

            // 调用
            DeptDataPermissionRespDTO result = permissionService.getDeptDataPermission(userId);
            // 断言：角色配置的可见部门仍正常加入，但 null 不进集合
            assertFalse(result.getAll());
            assertFalse(result.getSelf());
            assertEquals(roleDO.getDataScopeDeptIds().size(), result.getDeptIds().size());
            assertTrue(CollUtil.containsAll(result.getDeptIds(), roleDO.getDataScopeDeptIds()));
            assertFalse(result.getDeptIds().contains(null));
        }
    }

    @Test
    public void testGetDeptDataPermission_DeptOnly() {
        try (MockedStatic<SpringUtil> springUtilMockedStatic = mockStatic(SpringUtil.class)) {
            springUtilMockedStatic.when(() -> SpringUtil.getBean(eq(PermissionServiceImpl.class)))
                    .thenReturn(permissionService);

            // 准备参数
            Long userId = 1L;
            // mock 用户的角色编号
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(2L));
            // mock 获得用户的角色
            RoleDO roleDO = randomPojo(RoleDO.class, o -> o.setDataScope(DataScopeEnum.DEPT_ONLY.getScope())
                    .setStatus(CommonStatusEnum.ENABLE.getStatus()));
            when(roleService.getRoleListFromCache(eq(singleton(2L)))).thenReturn(toList(roleDO));
            // mock 部门的返回
            when(userService.getUser(eq(1L))).thenReturn(new AdminUserDO().setDeptId(3L),
                    null, null); // 最后返回 null 的目的，看看会不会重复调用

            // 调用
            DeptDataPermissionRespDTO result = permissionService.getDeptDataPermission(userId);
            // 断言
            assertFalse(result.getAll());
            assertFalse(result.getSelf());
            assertEquals(1, result.getDeptIds().size());
            assertTrue(CollUtil.contains(result.getDeptIds(), 3L));
        }
    }

    @Test
    public void testGetDeptDataPermission_DeptAndChild() {
        try (MockedStatic<SpringUtil> springUtilMockedStatic = mockStatic(SpringUtil.class)) {
            springUtilMockedStatic.when(() -> SpringUtil.getBean(eq(PermissionServiceImpl.class)))
                    .thenReturn(permissionService);

            // 准备参数
            Long userId = 1L;
            // mock 用户的角色编号
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(2L));
            // mock 获得用户的角色
            RoleDO roleDO = randomPojo(RoleDO.class, o -> o.setDataScope(DataScopeEnum.DEPT_AND_CHILD.getScope())
                    .setStatus(CommonStatusEnum.ENABLE.getStatus()));
            when(roleService.getRoleListFromCache(eq(singleton(2L)))).thenReturn(toList(roleDO));
            // mock 部门的返回
            when(userService.getUser(eq(1L))).thenReturn(new AdminUserDO().setDeptId(3L),
                    null, null); // 最后返回 null 的目的，看看会不会重复调用
            // mock 方法（部门)
            DeptDO deptDO = randomPojo(DeptDO.class);
            when(deptService.getChildDeptIdListFromCache(eq(3L))).thenReturn(singleton(deptDO.getId()));

            // 调用
            DeptDataPermissionRespDTO result = permissionService.getDeptDataPermission(userId);
            // 断言
            assertFalse(result.getAll());
            assertFalse(result.getSelf());
            assertEquals(2, result.getDeptIds().size());
            assertTrue(CollUtil.contains(result.getDeptIds(), deptDO.getId()));
            assertTrue(CollUtil.contains(result.getDeptIds(), 3L));
        }
    }

    @Test
    public void testGetDeptDataPermission_DeptAndChild_userDeptIdNull() {
        try (MockedStatic<SpringUtil> springUtilMockedStatic = mockStatic(SpringUtil.class)) {
            springUtilMockedStatic.when(() -> SpringUtil.getBean(eq(PermissionServiceImpl.class)))
                    .thenReturn(permissionService);

            // 准备参数
            Long userId = 1L;
            // mock 用户的角色编号
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(2L));
            // mock 获得用户的角色
            RoleDO roleDO = randomPojo(RoleDO.class, o -> o.setDataScope(DataScopeEnum.DEPT_AND_CHILD.getScope())
                    .setStatus(CommonStatusEnum.ENABLE.getStatus()));
            when(roleService.getRoleListFromCache(eq(singleton(2L)))).thenReturn(toList(roleDO));
            // mock 部门的返回：用户未设置部门
            when(userService.getUser(eq(1L))).thenReturn(new AdminUserDO()); // deptId 为 null

            // 调用
            DeptDataPermissionRespDTO result = permissionService.getDeptDataPermission(userId);
            // 断言：deptId 为 null，整段跳过；deptIds 为空，子部门查询不被触发
            assertFalse(result.getAll());
            assertFalse(result.getSelf());
            assertTrue(CollUtil.isEmpty(result.getDeptIds()));
            verify(deptService, never()).getChildDeptIdListFromCache(any());
        }
    }

    @Test
    public void testGetDeptDataPermission_Self() {
        try (MockedStatic<SpringUtil> springUtilMockedStatic = mockStatic(SpringUtil.class)) {
            springUtilMockedStatic.when(() -> SpringUtil.getBean(eq(PermissionServiceImpl.class)))
                    .thenReturn(permissionService);

            // 准备参数
            Long userId = 1L;
            // mock 用户的角色编号
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(2L));
            // mock 获得用户的角色
            RoleDO roleDO = randomPojo(RoleDO.class, o -> o.setDataScope(DataScopeEnum.SELF.getScope())
                    .setStatus(CommonStatusEnum.ENABLE.getStatus()));
            when(roleService.getRoleListFromCache(eq(singleton(2L)))).thenReturn(toList(roleDO));

            // 调用
            DeptDataPermissionRespDTO result = permissionService.getDeptDataPermission(userId);
            // 断言
            assertFalse(result.getAll());
            assertTrue(result.getSelf());
            assertTrue(CollUtil.isEmpty(result.getDeptIds()));
        }
    }

}
