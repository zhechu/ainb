package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.MenuMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link GrantDecisionService#listAllowedMenuIds} 的单元测试
 *
 * <h3>这个方法为什么存在</h3>
 *
 * 菜单树（导航）需要从 {@code sys_grant} 计算，而不是从 {@code system_role_menu} ——
 * 不切的话会出现「按钮有了、但进不去那个页面」。
 *
 * 比 {@link GrantDecisionListPermissionsTest} 多了两个关注点：
 *   ① 没有 menu_id 的资源（表单/流程）不进菜单集合
 *   ② 叶子命中后，它的祖先菜单必须一并带上（否则前端树断链）
 *
 * @author 走进新时代
 */
public class GrantDecisionListMenuIdsTest extends BaseMockitoUnitTest {

    private static final Long USER_ID = 1L;
    private static final Long ROLE_ID = 2L;

    @InjectMocks
    private GrantDecisionService grantDecisionService;

    /** 真对象而非 @Mock：塌缩开关的默认值必须来自 PermissionProperties 自己，
     *  而不是 mock 返回 null 的副作用 */
    @Spy
    private PermissionProperties permissionProperties = new PermissionProperties();
    @Mock
    private SubjectClosureService subjectClosureService;
    @Mock
    private ResourceMapper resourceMapper;
    @Mock
    private GrantMapper grantMapper;
    /**
     * 菜单树祖先补全要用它。
     * ⚠️ 不 mock 会 NPE —— {@code completeMenuAncestors} 在有 menuId 命中时必然调用它
     */
    @Mock
    private MenuMapper menuMapper;

    /** 菜单：系统管理（父目录，menu_id=1） */
    private ResourceDO menu;
    /** 按钮：角色查询（menu_id=30） */
    private ResourceDO action;
    /** 数据集：没有 menu_id（在线表单，不进导航） */
    private ResourceDO dataset;

    @BeforeEach
    void setUp() {
        menu = resource(10L, null, "系统管理", null, 0L, 1L, "DENY");
        action = resource(11L, "system:role:query", "角色查询", 10L, 30L, "DENY");
        dataset = resource(12L, "form:test:data", "测试表单", 10L, null, "ALLOW");

        lenient().when(subjectClosureService.getSubjectKeysByUserId(eq(USER_ID)))
                .thenReturn(Set.of(SubjectKey.ofRole(ROLE_ID)));
        lenient().when(resourceMapper.selectList(any()))
                .thenReturn(List.of(menu, action, dataset));
    }

    @Test
    @DisplayName("没有任何授权时返回空集")
    void 无授权返回空集() {
        when(grantMapper.selectListActiveBySubjects(any())).thenReturn(List.of());

        Set<Long> result = grantDecisionService.listAllowedMenuIds(USER_ID);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("授权按钮后，按钮本身和它的父菜单都进集合")
    void 按钮授权带出父菜单() {
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(action.getId(), "VIEW", "ALLOW")));

        Set<Long> result = grantDecisionService.listAllowedMenuIds(USER_ID);

        assertTrue(result.contains(30L), "按钮自身的 menuId 应该在集合里");
        assertTrue(result.contains(1L), "父菜单的 menuId 必须一并带上，否则树断链");
    }

    @Test
    @DisplayName("没有 menu_id 的资源（表单/流程）即使授权也不进集合")
    void 无菜单ID的资源不进集合() {
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(dataset.getId(), "VIEW", "ALLOW")));

        Set<Long> result = grantDecisionService.listAllowedMenuIds(USER_ID);

        assertFalse(result.contains(null));
        // 父目录 menu_id=1 也不应该因为 dataset 的命中而被带进来
        // （dataset 没有 menu_id，就没有「叶子 + 祖先」的逻辑触发）
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("DENY 能把继承来的权限收紧，对应菜单不进集合")
    void DENY收紧后菜单不进集合() {
        when(grantMapper.selectListActiveBySubjects(any())).thenReturn(List.of(
                grant(menu.getId(), "VIEW", "ALLOW"),
                grant(action.getId(), "VIEW", "DENY")));

        Set<Long> result = grantDecisionService.listAllowedMenuIds(USER_ID);

        // action 被 DENY，它的菜单不进；但 menu 本身如果直接命中可以进
        // （menu 的 menu_id=1，而 action 的 menu_id=30）
        assertFalse(result.contains(30L), "被 DENY 的按钮对应菜单不应出现");
    }

    @Test
    @DisplayName("主体闭包为空时直接返回空集，且不查授权表")
    void 无主体直接返回() {
        when(subjectClosureService.getSubjectKeysByUserId(eq(USER_ID))).thenReturn(Set.of());

        Set<Long> result = grantDecisionService.listAllowedMenuIds(USER_ID);

        assertTrue(result.isEmpty());
        verify(grantMapper, never()).selectListActiveBySubjects(any());
    }

    /**
     * 🔴 这条钉住的是「登录后导航栏整个是空的」那个缺陷
     *
     * <h3>为什么上面那条「按钮授权带出父菜单」钉不住</h3>
     *
     * 那条用的是<b>嵌套</b>的资源（action.parentId = menu.id），于是祖先菜单能从
     * <b>资源链</b>上取到。但生产库不长这样：
     * <ul>
     *   <li>迁移时资源<b>全部挂在根下</b>，实测 1851/1979 个资源在深度 1 —— 资源链只有自身</li>
     *   <li>「角色管理」这类 permission 为空的菜单<b>压根没有资源行</b>（全库 466 个），
     *       永远不可能作为资源祖先出现</li>
     * </ul>
     *
     * 所以必须沿 <b>system_menu 自己的父链</b> 上溯。这里就按生产的真实形状造数：
     * 资源是扁平的，菜单树是 按钮(30) → 角色管理(5) → 系统管理(1)。
     *
     * 修复前：只返回 {30}，前端 buildMenuTree 找不到 parentId=5 → 整棵树被丢掉 → 导航全空。
     */
    @Test
    @DisplayName("资源扁平时，仍沿 system_menu 父链补全祖先菜单（导航栏空白缺陷的回归钉子）")
    void 扁平资源也要补全菜单树祖先() {
        // 资源是扁平的：按钮资源直接挂根下，没有任何祖先资源
        ResourceDO flatAction = resource(21L, "system:role:query", "角色查询", 0L, 30L, "DENY");
        when(resourceMapper.selectList(any())).thenReturn(List.of(flatAction));
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(flatAction.getId(), "VIEW", "ALLOW")));
        // 菜单树：按钮(30) → 角色管理(5) → 系统管理(1) → 根(0)
        when(menuMapper.selectList()).thenReturn(List.of(
                menuRow(30L, 5L), menuRow(5L, 1L), menuRow(1L, 0L)));

        Set<Long> result = grantDecisionService.listAllowedMenuIds(USER_ID);

        assertTrue(result.contains(30L), "按钮自身的 menuId 应该在集合里");
        assertTrue(result.contains(5L),
                "父菜单（角色管理，无 permission 因而没有资源行）必须沿菜单父链补上");
        assertTrue(result.contains(1L),
                "祖父目录（系统管理）也必须补上，否则前端整棵树挂在不存在的父节点上被丢弃");
    }

    // -------- W2b：塌缩态（enlev.permission.collapse-enabled=true） --------

    /**
     * 🔴 这条钉住的是「能写不能读的角色，整页从导航里消失」
     *
     * <h3>为什么塌缩会把它弄丢</h3>
     *
     * 塌缩前这个集合是从 ~620 个<b>动词行</b>上收 {@code menu_id} 的：角色只要在其中
     * <b>任意一行</b>被判放行（比如只有 {@code system:user:create}、没有 {@code :query}），
     * 那一行的 menu_id 就进集合，{@code completeMenuAncestors} 再沿 {@code system_menu}
     * 父链把「用户管理」这个页面补出来。
     *
     * 塌缩后 620 行变成 ~117 个<b>身份行</b>。若仍只试 VIEW：
     * 身份行的 {@code default_effect} 是 DENY（菜单类资源的既有口径），
     * 而链上没有一条 VIEW 授权 ⟹ 引擎回落 default_effect ⟹ 判 DENY ⟹ menu_id 不进集合。
     * 表现是<b>页面还在、路由还能直达，只是左侧菜单不显示了，而且全程 200 无报错</b>。
     */
    @Test
    @DisplayName("塌缩后只有 CREATE 授权（资源默认 DENY）的页面仍进导航")
    void 塌缩后能写不能读也进导航() {
        ResourceDO identity = resource(40L, "system:user", "用户管理", 0L, 100L, "DENY");
        when(resourceMapper.selectList(any())).thenReturn(List.of(identity));
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(identity.getId(), "CREATE", "ALLOW")));
        // 菜单树：用户管理(100) → 系统管理(1) → 根(0)
        when(menuMapper.selectList()).thenReturn(List.of(menuRow(100L, 1L), menuRow(1L, 0L)));
        permissionProperties.setCollapseEnabled(true);

        Set<Long> result = grantDecisionService.listAllowedMenuIds(USER_ID);

        assertTrue(result.contains(100L), "实际：" + result);
        assertTrue(result.contains(1L), "祖先目录必须补齐，否则前端整棵树被丢弃。实际：" + result);
    }

    /**
     * 「任一动作放行就进导航」不等于「有授权行就进导航」
     *
     * <p>资源默认 ALLOW，所以只要循环里有任何一次判定被绕过（或候选动作集里混进了
     * 一个没有对应授权行的动作），这个页面就会错误地出现在导航里。
     * {@code action='*'} 的 DENY 会被引擎的动作过滤命中（{@code actionMatches(WILDCARD)}），
     * 于是 <b>全部</b> 候选动作都判 DENY ⟹ 集合为空
     */
    @Test
    @DisplayName("塌缩后通配符 DENY 仍然收紧，菜单不进集合")
    void 塌缩后通配符DENY仍收紧() {
        ResourceDO identity = resource(41L, "system:user", "用户管理", 0L, 100L, "ALLOW");
        when(resourceMapper.selectList(any())).thenReturn(List.of(identity));
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(identity.getId(), "*", "DENY")));
        permissionProperties.setCollapseEnabled(true);

        Set<Long> result = grantDecisionService.listAllowedMenuIds(USER_ID);

        assertTrue(result.isEmpty(), "实际：" + result);
    }

    /**
     * 同一份数据、开关关掉 ⟹ 页面丢失
     *
     * <p>它同时钉住两件事：① 上面那条缺陷在关闭态<b>确实存在</b>（这就是 W2b 要修的东西，
     * 不是凭空加的语义）；② 塌缩语义<b>真的被开关挡住了</b>，
     * 关闭态与改造前逐字节同口径（导航是登录后第一眼看到的东西，回归代价最高）
     */
    @Test
    @DisplayName("关闭态下同一份数据会丢页面（钉住开关确实生效）")
    void 关闭态能写不能读会丢页面() {
        ResourceDO identity = resource(40L, "system:user", "用户管理", 0L, 100L, "DENY");
        when(resourceMapper.selectList(any())).thenReturn(List.of(identity));
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(identity.getId(), "CREATE", "ALLOW")));

        Set<Long> result = grantDecisionService.listAllowedMenuIds(USER_ID);

        assertFalse(result.contains(100L), "关闭态只试 VIEW，实际：" + result);
    }

    // -------- 造数 --------

    private static MenuDO menuRow(Long id, Long parentId) {
        MenuDO m = new MenuDO();
        m.setId(id);
        m.setParentId(parentId);
        return m;
    }

    private static ResourceDO resource(Long id, String permission, String name,
                                       Long parentId, Long menuId, String defaultEffect) {
        ResourceDO r = new ResourceDO();
        r.setId(id);
        r.setPermission(permission);
        r.setName(name);
        r.setParentId(parentId != null ? parentId : 0L);
        r.setMenuId(menuId);
        r.setDefaultEffect(defaultEffect);
        r.setStatus(0);
        return r;
    }

    private static ResourceDO resource(Long id, String permission, String name,
                                       Long menuId, Long parentId, Long menuId2, String defaultEffect) {
        return resource(id, permission, name, parentId, menuId2, defaultEffect);
    }

    private static GrantDO grant(Long resourceId, String action, String effect) {
        GrantDO g = new GrantDO();
        g.setSubjectType("ROLE");
        g.setSubjectRefId(ROLE_ID);
        g.setResourceId(resourceId);
        g.setAction(action);
        g.setEffect(effect);
        return g;
    }
}
