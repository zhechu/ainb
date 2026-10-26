package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link GrantDecisionService#listAllowedPermissions} 的单元测试
 *
 * <h3>这个方法为什么存在</h3>
 *
 * {@code /system/auth/get-permission-info} 原来从 {@code system_role_menu} 推权限串，
 * 而鉴权早已切到 {@code sys_grant}。两边分叉的后果是
 * <b>后端放行、前端 v-hasPermi 却把按钮藏起来</b>，且完全不报错 ——
 * S8a 的授权配置页配出来的东西只写 sys_grant，不补这个方法的话对前端一律无效。
 *
 * 下面的用例分两类：
 *   ① 判定口径必须与 {@code hasPermission} 一致（继承、DENY 优先、default_effect 回落）
 *   ② 不能把「目录层」和「过期授权」算进清单
 *
 * @author 走进新时代
 */
public class GrantDecisionListPermissionsTest extends BaseMockitoUnitTest {

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
    @Mock
    private MenuMapper menuMapper;
    @Mock
    private RoleService roleService;
    @Mock
    private PermissionService permissionService;

    /** 目录层：没有 permission，只是树的结构节点 */
    private ResourceDO menu;
    /** 按钮层：default_effect = DENY，必须显式授权才有 */
    private ResourceDO actionQuery;
    private ResourceDO actionCreate;

    @BeforeEach
    void setUp() {
        menu = resource(10L, null, "角色管理", 0L, "DENY");
        actionQuery = resource(11L, "system:grant:query", "授权查询", 10L, "DENY");
        actionCreate = resource(12L, "system:grant:create", "授权新增", 10L, "DENY");

        lenient().when(subjectClosureService.getSubjectKeysByUserId(eq(USER_ID)))
                .thenReturn(Set.of(SubjectKey.ofRole(ROLE_ID)));
        lenient().when(resourceMapper.selectList(any()))
                .thenReturn(List.of(menu, actionQuery, actionCreate));
    }

    @Test
    @DisplayName("没有任何授权时返回空集，而不是全部放行")
    void 无授权返回空集() {
        when(grantMapper.selectListActiveBySubjects(any())).thenReturn(List.of());

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        // ACTION 的 default_effect 是 DENY，没配就是没有 —— 回落方向错了会把整个后台敞开
        assertTrue(result.isEmpty(), "实际：" + result);
    }

    @Test
    @DisplayName("只授了一个权限点，就只返回那一个（权限点之间不串味）")
    void 单条ALLOW只放行自己() {
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(actionQuery.getId(), "VIEW", "ALLOW", null)));

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        assertEquals(Set.of("system:grant:query"), result);
    }

    @Test
    @DisplayName("授在父目录上，子权限点跟着继承")
    void 父目录授权被继承() {
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(menu.getId(), "VIEW", "ALLOW", null)));

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        // 继承是资源树的核心语义；断不住的话「给分类授权，其下流程自动继承」也会失效
        assertEquals(Set.of("system:grant:query", "system:grant:create"), result);
    }

    @Test
    @DisplayName("子节点的 DENY 压过父目录的 ALLOW")
    void DENY优先于继承来的ALLOW() {
        when(grantMapper.selectListActiveBySubjects(any())).thenReturn(List.of(
                grant(menu.getId(), "VIEW", "ALLOW", null),
                grant(actionQuery.getId(), "VIEW", "DENY", null)));

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        assertEquals(Set.of("system:grant:create"), result,
                "DENY 必须能在继承链上收紧，否则「先全开再逐个关」的配法做不到");
    }

    @Test
    @DisplayName("过期的授权不进清单")
    void 过期授权不生效() {
        when(grantMapper.selectListActiveBySubjects(any())).thenReturn(List.of(
                grant(actionQuery.getId(), "VIEW", "ALLOW", LocalDateTime.now().minusDays(1))));

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        // SQL 侧已经滤过一次，这里再挡一次：调用方从别处拿到 GrantDO 的路径不能漏
        assertTrue(result.isEmpty(), "实际：" + result);
    }

    @Test
    @DisplayName("目录层（没有 permission）不进清单")
    void 目录层不进清单() {
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(menu.getId(), "VIEW", "ALLOW", null)));

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        assertFalse(result.contains(null));
        assertFalse(result.contains(""));
    }

    @Test
    @DisplayName("主体闭包为空时返回空集，且不查授权表")
    void 无主体直接返回() {
        when(subjectClosureService.getSubjectKeysByUserId(eq(USER_ID))).thenReturn(Set.of());

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        assertTrue(result.isEmpty());
        verify(grantMapper, never()).selectListActiveBySubjects(any());
    }

    @Test
    @DisplayName("通配符动作 * 同样放行")
    void 通配符动作放行() {
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(actionQuery.getId(), "*", "ALLOW", null)));

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        assertEquals(Set.of("system:grant:query"), result);
    }

    // ==================== 塌缩开关打开后（规则 R2：反向展开） ====================

    /** 塌缩后的身份行：兄弟行 system:grant:query / :create 都已退役 */
    private ResourceDO grantIdentity;

    /**
     * 造出「迁移已落库、开关已打开」的世界
     *
     * <p>⚠️ 两张表的形态是<b>不对称</b>的，而这个不对称正是 R2 成立的前提：
     * 资源表是迁移后的样子（只剩身份行），导航表是<b>一直没被本次迁移动过</b>的样子
     * （还留着全部原始串）—— 反向展开表的唯一来源就是导航表
     */
    private void stubCollapsedWorld() {
        grantIdentity = resource(20L, "system:grant", "授权管理", 0L, "DENY");
        when(resourceMapper.selectList(any())).thenReturn(List.of(grantIdentity));
        when(menuMapper.selectList()).thenReturn(List.of(
                menu(1L, "system:grant:query"),
                menu(2L, "system:grant:list"),
                menu(3L, "system:grant:create"),
                menu(4L, "")));   // 目录层没有权限串，不该进展开表
        permissionProperties.setCollapseEnabled(true);
    }

    @Test
    @DisplayName("塌缩打开：CREATE 授权展开回 :create，且不串到 :query")
    void 塌缩后按动作反向展开() {
        stubCollapsedWorld();
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(grantIdentity.getId(), "CREATE", "ALLOW", null)));

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        // 🔴 迁移把「挂在 system:grant:create 上的 VIEW」改写成了「挂在 system:grant 上的 CREATE」
        //    （规则 R9）。清单侧必须还原回原始串：前端 v-hasPermi 与后端 @PreAuthorize
        //    比的都是 :create 这种串，清单里只有身份串的话 —— 按钮全消失、接口全 403，
        //    且全程不报错
        assertEquals(Set.of("system:grant:create"), result);
    }

    @Test
    @DisplayName("塌缩打开：VIEW 授权展开出全部映射到 VIEW 的原始串")
    void 塌缩后VIEW展开出多个原始串() {
        stubCollapsedWorld();
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(grantIdentity.getId(), "VIEW", "ALLOW", null)));

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        // query 与 list 都映射到 VIEW，两个串都真实存在过 ⟹ 都要还原。
        // 身份串 system:grant 自己也要在：它与开关关闭时那个 add 是同一个东西
        assertEquals(Set.of("system:grant:query", "system:grant:list", "system:grant"), result);
    }

    @Test
    @DisplayName("塌缩打开：通配符授权也只展开出库里真实存在过的串")
    void 塌缩后不铸出库中不存在的串() {
        stubCollapsedWorld();
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(grantIdentity.getId(), "*", "ALLOW", null)));

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        // 🔴 * 命中全部动作，但导航表里只存在过 query / list / create 三个串。
        //    按动词映射表反推会额外造出 :update / :delete / :export / :import / :get …，
        //    它们不会让判定出错，却会让「权限清单 vs 注解面」的对账永久对不平 ——
        //    而对账正是发现漂移的唯一手段；更坏的是它盖住了真正的缺口
        assertEquals(Set.of("system:grant:query", "system:grant:list",
                "system:grant:create", "system:grant"), result);
    }

    @Test
    @DisplayName("塌缩打开：同一身份上 DENY 掉一个动作，只影响那个动作")
    void 塌缩后DENY按动作收紧() {
        stubCollapsedWorld();
        when(grantMapper.selectListActiveBySubjects(any())).thenReturn(List.of(
                grant(grantIdentity.getId(), "VIEW", "ALLOW", null),
                grant(grantIdentity.getId(), "CREATE", "DENY", null)));

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        assertEquals(Set.of("system:grant:query", "system:grant:list", "system:grant"), result);
    }

    /**
     * 🔴 不塌缩的档必须靠「身份串自己进清单」那一行保住
     *
     * <p>档 C（业务专有动词）的末段不在动词映射表里，展开表里<b>没有它的条目</b>。
     * 少了那一行，全部档 C / 档 D 权限点会从清单里消失，而判定侧照样放行 ——
     * 又是本文件开头记的那个老症状：<b>后端放行、前端 v-hasPermi 把按钮藏起来</b>
     */
    @Test
    @DisplayName("塌缩打开：档 C（业务专有动词）原样进清单")
    void 塌缩后档C原样进清单() {
        ResourceDO leave = resource(30L, "system:leave:cancel", "撤销请假", 0L, "DENY");
        when(resourceMapper.selectList(any())).thenReturn(List.of(leave));
        when(menuMapper.selectList()).thenReturn(List.of(menu(5L, "system:leave:cancel")));
        permissionProperties.setCollapseEnabled(true);
        when(grantMapper.selectListActiveBySubjects(any()))
                .thenReturn(List.of(grant(leave.getId(), "VIEW", "ALLOW", null)));

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        assertEquals(Set.of("system:leave:cancel"), result);
    }

    /**
     * 🔴 这条钉住的是 {@code collapseCandidates} 里那句「VIEW 无条件在候选里」
     *
     * <p>零配置资源（链上<b>一条授权行都没有</b>）是靠 {@code default_effect=ALLOW} 回落放行的。
     * 若候选动作集只从授权行里收，这类资源的候选集会是<b>空集</b> ⟹ 一次判定都不做 ⟹
     * 从清单里凭空消失，而判定侧 {@code hasPermission} 照样放行 ——
     * 又是「后端放行、前端 v-hasPermi 把按钮藏起来」那个老症状。
     *
     * <p>实测这类资源有多少：启用且 {@code default_effect=ALLOW} 的 232 行里，
     * 只有 37 行挂着活授权 ⟹ <b>195 行完全靠回落放行</b>（DATASET 178 / FIELD 17 /
     * COLUMN 18 / PROCESS 18 / CATEGORY 1，有重叠）。
     *
     * <p>生效权限树那侧的 {@code candidateActions} 刻意是相反口径（无授权行就返回空集），
     * 为的是压掉 205 个零配置 DATASET 节点的<b>展示</b>假阳性 —— 那边少报是安全的一侧，
     * 这边少报等于按钮消失。两个方法不能合并
     */
    @Test
    @DisplayName("塌缩打开：零配置但默认放行的资源仍进清单")
    void 塌缩后默认放行的资源仍进清单() {
        // 取真实形状：COLUMN 类资源，default_effect=ALLOW，链上没有任何授权行
        ResourceDO column = resource(50L, "system:user:col:mobile", "用户:列:手机号", 0L, "ALLOW");
        when(resourceMapper.selectList(any())).thenReturn(List.of(column));
        permissionProperties.setCollapseEnabled(true);
        when(grantMapper.selectListActiveBySubjects(any())).thenReturn(List.of());

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        // 末段不是动词 ⟹ 不塌缩 ⟹ 展开表里没有它的条目，
        // 它是靠「身份串自己也进清单」那一行进来的（与 塌缩后档C原样进清单 同一条代码）
        assertEquals(Set.of("system:user:col:mobile"), result);
    }

    /**
     * 超管短路必须完全不受塌缩开关影响
     *
     * <p>它取的是【菜单表】原样，而本次迁移压根不动导航表 ——
     * 所以对超管来说，开关翻开前后清单必须逐字节一致。
     * 这也意味着<b>只在超管账号上做迁移前后 diff 是无效验证</b>
     */
    @Test
    @DisplayName("塌缩打开：超管短路仍然直接吐菜单表，不走展开")
    void 塌缩后超管短路不变() {
        permissionProperties.setCollapseEnabled(true);
        when(permissionService.getUserRoleIdListByUserId(eq(USER_ID))).thenReturn(Set.of(1L));
        when(roleService.hasAnySuperAdmin(any())).thenReturn(true);
        when(menuMapper.selectList()).thenReturn(List.of(
                menu(1L, "system:grant:query"), menu(2L, "system:grant:create"), menu(3L, "")));

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        assertEquals(Set.of("system:grant:query", "system:grant:create"), result);
    }

    // -------- 造数 --------

    private static ResourceDO resource(Long id, String permission, String name,
                                       Long parentId, String defaultEffect) {
        ResourceDO r = new ResourceDO();
        r.setId(id);
        r.setPermission(permission);
        r.setName(name);
        r.setParentId(parentId);
        r.setDefaultEffect(defaultEffect);
        r.setStatus(0);
        return r;
    }

    /**
     * 🔴 超管短路的回归钉子
     *
     * 后端 @ss.hasPermission 对超管直接放行，而前端按钮显隐来自本方法。
     * 不短路的话两边分叉：接口能调、按钮不显示。
     *
     * 实测过的真实症状：超管在 system_role_menu 里只有 274 行，
     * 而全库 1878 个权限点 —— 切到新引擎后大量按钮（如「删除菜单」）凭空消失。
     */
    @Test
    @DisplayName("超管直接拿到全部权限标识，不看 sys_grant")
    void 超管短路返回全部权限() {
        when(permissionService.getUserRoleIdListByUserId(eq(USER_ID))).thenReturn(Set.of(1L));
        when(roleService.hasAnySuperAdmin(any())).thenReturn(true);
        when(menuMapper.selectList()).thenReturn(List.of(
                menu(1L, "system:menu:delete"), menu(2L, "system:user:create"),
                menu(3L, "")));   // 目录层没有权限串，不该进清单

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        assertTrue(result.contains("system:menu:delete"), "超管必须拿到全部权限点");
        assertTrue(result.contains("system:user:create"));
        assertFalse(result.contains(""), "空权限串（目录层）不该进清单");
        assertEquals(2, result.size());
        // 短路要彻底：不该再去查主体闭包和授权表
        verify(subjectClosureService, never()).getSubjectKeysByUserId(any());
        verify(grantMapper, never()).selectListActiveBySubjects(any());
    }

    @Test
    @DisplayName("超管判定异常时按非超管处理，不能把全部权限放出去")
    void 超管判定异常按非超管处理() {
        when(permissionService.getUserRoleIdListByUserId(eq(USER_ID)))
                .thenThrow(new RuntimeException("模拟判定失败"));
        when(subjectClosureService.getSubjectKeysByUserId(eq(USER_ID))).thenReturn(Set.of());

        Set<String> result = grantDecisionService.listAllowedPermissions(USER_ID);

        assertTrue(result.isEmpty(), "判不出来时宁可少给权限，不能反向放开");
    }

    private static MenuDO menu(Long id, String permission) {
        MenuDO m = new MenuDO();
        m.setId(id);
        m.setPermission(permission);
        return m;
    }

    private static GrantDO grant(Long resourceId, String action, String effect,
                                 LocalDateTime expireTime) {
        GrantDO g = new GrantDO();
        g.setSubjectType("ROLE");
        g.setSubjectRefId(ROLE_ID);
        g.setResourceId(resourceId);
        g.setAction(action);
        g.setEffect(effect);
        g.setExpireTime(expireTime);
        return g;
    }

}
