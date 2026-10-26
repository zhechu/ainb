package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.MenuMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.enums.permission.MenuTypeEnum;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties;
import cn.enlev.ainb.module.system.framework.permission.core.scan.CollapsedResourceLocator;
import cn.enlev.ainb.module.system.framework.permission.core.scan.PermissionDriftDetector;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResourceServiceImpl} 与 {@link PermissionDriftDetector} 在
 * <b>{@code enlev.permission.collapse-enabled=true}</b> 下的写路径测试
 *
 * <h3>为什么需要单独一个类，而不是往 {@code ResourceServiceImplTest} 里加用例</h3>
 *
 * 那个类跑的是<b>开关关闭</b>的路径，而关闭态必须与塌缩前逐字节一致 ——
 * 把开关打开的用例混进去，就要在每个用例里各自覆写配置，
 * 而 Spring 的测试上下文是按配置组合缓存的，混在一起等于每个用例都重建一次上下文。
 * 分开之后：那边整个类共享一个「关闭」上下文，这边整个类共享一个「开启」上下文。
 *
 * <h3>这些用例挡的是什么</h3>
 *
 * 三条写路径原本都假设「一个权限串 ⟷ 一行资源」「菜单的权限串 == 它那一行的权限串」。
 * 塌缩把这两个前提都推翻了，而违背它们<b>不报错</b>：
 * <ul>
 *   <li>{@code migrateFromMenu} 的幂等键是 menu_id，而 R1 只给每个身份行留一个代表 menu_id
 *       ⟹ 一次重启把退役的成员行整批插回来（实测本库 341 / 666 个非目录菜单会中招）</li>
 *   <li>{@code syncFromMenu} 会把身份行的 permission 改回菜单的动词串
 *       ⟹ 判定侧查身份串，查不到 ⟹ 整页按钮静默 403（实测 94 行会中招，85 个是按钮）</li>
 *   <li>{@code PermissionDriftDetector#syncTree} 照着代码里的原始串推导
 *       ⟹ 同样把成员行建回来，而且每次启动都建</li>
 * </ul>
 *
 * @author 走进新时代
 */
@Import({ResourceServiceImpl.class, PermissionDriftDetector.class,
        CollapsedResourceLocator.class, ResourceServiceImplCollapseTest.CollapseEnabledConfig.class})
public class ResourceServiceImplCollapseTest extends BaseDbUnitTest {

    /**
     * 把塌缩开关打开。
     *
     * ⚠️ 不 import {@code PermissionConfiguration}：那会用 {@code @EnableConfigurationProperties}
     * 注册一个默认实例（开关是关的），与本 Bean 撞名。这里直接给一个真对象，
     * 而不是 mock —— mock 返回 null 会让「开关关着」和「开关读不到」两种状态混为一谈
     */
    @TestConfiguration
    public static class CollapseEnabledConfig {

        @Bean
        public PermissionProperties permissionProperties() {
            PermissionProperties properties = new PermissionProperties();
            properties.setCollapseEnabled(true);
            return properties;
        }

    }

    @Resource
    private ResourceServiceImpl resourceService;
    @Resource
    private PermissionDriftDetector driftDetector;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private MenuMapper menuMapper;
    /** 只为把 ResourceServiceImpl 装进上下文（它的写方法要驱逐资源链缓存），本类不验驱逐 */
    @MockitoBean
    private GrantDecisionService grantDecisionService;

    // ============================= migrateFromMenu =============================

    @Test
    @DisplayName("塌缩后引导：同族按钮只建一行身份资源，permission 是身份串")
    public void testMigrateFromMenu_塌缩_同族合并成身份行() {
        insertMenu(200L, "用户管理", "", MenuTypeEnum.MENU.getType());
        insertMenu(201L, "查询用户", "system:user:query", MenuTypeEnum.BUTTON.getType());
        insertMenu(202L, "新增用户", "system:user:create", MenuTypeEnum.BUTTON.getType());
        // 同一个权限串既挂在导航菜单上、又挂在按钮上（实测本库有这种）
        insertMenu(203L, "角色管理", "system:role:query", MenuTypeEnum.MENU.getType());
        insertMenu(204L, "查询角色", "system:role:query", MenuTypeEnum.BUTTON.getType());
        // 档 C：业务专有动词，本次迁移<b>没有</b>塌缩它，形状必须与今天一致
        insertMenu(205L, "发布流程", "bpm:process:publish", MenuTypeEnum.BUTTON.getType());
        insertMenu(206L, "目录层", "", MenuTypeEnum.DIR.getType());

        resourceService.migrateFromMenu();

        // system:user（一族一行）+ system:role（一族一行）+ bpm:process:publish + 200 的空权限串行
        assertEquals(4L, resourceMapper.selectCount());

        ResourceDO userIdentity = resourceMapper.selectByPermissionIncludeDisabled("system:user");
        assertNotNull(userIdentity, "同族的两个按钮应当合并出 system:user 这一行");
        assertEquals(ResourceTypeEnum.ACTION.getCode(), userIdentity.getType());
        assertEquals("DENY", userIdentity.getDefaultEffect());
        // 🔴 成员行绝不能被建出来：它们一出现，判定侧的 R11 镜像就改按原始串查，
        //    而授权在身份行上 —— 结果是权限静默消失
        assertNull(resourceMapper.selectByPermissionIncludeDisabled("system:user:query"));
        assertNull(resourceMapper.selectByPermissionIncludeDisabled("system:user:create"));
        assertNull(resourceMapper.selectByMenuId(202L), "同族第二个按钮不该有自己的行");

        // 代表菜单优先挑非按钮的（与计划器 R1 的 navMenuIds 同口径）：
        // 203 是 MENU、204 是 BUTTON，同一个串，挑 203
        ResourceDO roleIdentity = resourceMapper.selectByPermissionIncludeDisabled("system:role");
        assertNotNull(roleIdentity);
        assertEquals(203L, roleIdentity.getMenuId());
        assertNull(resourceMapper.selectByMenuId(204L));

        // 档 C 不塌缩：permission 原样、menu_id 原样，与开关关闭时逐字节一致
        ResourceDO publish = resourceMapper.selectByMenuId(205L);
        assertNotNull(publish);
        assertEquals("bpm:process:publish", publish.getPermission());

        // 空权限串的导航菜单照旧建 MENU 类型行（塌缩没动这一档）
        ResourceDO blank = resourceMapper.selectByMenuId(200L);
        assertNotNull(blank);
        assertEquals(ResourceTypeEnum.MENU.getCode(), blank.getType());
    }

    @Test
    @DisplayName("塌缩后引导：sys_resource 非空则整体跳过 —— 挡住「一次重启插回 341 行」")
    public void testMigrateFromMenu_塌缩_非空库整体跳过() {
        insertMenu(201L, "查询用户", "system:user:query", MenuTypeEnum.BUTTON.getType());
        insertMenu(202L, "新增用户", "system:user:create", MenuTypeEnum.BUTTON.getType());
        // 库里已经是塌缩后的形状：只有身份行，menu_id 指着代表菜单 201
        insertIdentity("system:user", 201L);

        resourceService.migrateFromMenu();

        // 🔴 判据必须是「行数没变」。按 menu_id 判幂等的话，202 查不到承载行，
        //    会被当成「没迁过」而 insert 出一行 system:user:create
        assertEquals(1L, resourceMapper.selectCount());
        assertNull(resourceMapper.selectByPermissionIncludeDisabled("system:user:create"));
        assertNull(resourceMapper.selectByMenuId(202L));
    }

    // ============================= syncFromMenu =============================

    @Test
    @DisplayName("塌缩后同步：编辑非代表菜单的按钮，不许把身份行改名回动词串")
    public void testSyncFromMenu_塌缩_不改名身份行() {
        insertMenu(201L, "查询用户", "system:user:query", MenuTypeEnum.BUTTON.getType());
        insertMenu(202L, "新增用户", "system:user:create", MenuTypeEnum.BUTTON.getType());
        insertIdentity("system:user", 201L);

        resourceService.syncFromMenu(202L);

        // 🔴 改名的后果：判定侧查 system:user，而这一行已经叫 system:user:create 了
        //    ⟹ selectListByPermission 返回空 ⟹ 整页按钮静默 403
        ResourceDO row = resourceMapper.selectByPermissionIncludeDisabled("system:user");
        assertNotNull(row);
        assertEquals("system:user", row.getPermission());
        assertEquals(1L, resourceMapper.selectCount(), "也不该为它多建一行");
        assertEquals(201L, row.getMenuId(), "代表菜单不该被抢走");
    }

    @Test
    @DisplayName("塌缩后同步：编辑代表菜单，显示字段跟着走、permission 不动")
    public void testSyncFromMenu_塌缩_代表菜单同步显示字段() {
        // ⚠️ insertMenu 的 parentId 默认 0，也就是「这个按钮没有页面菜单可依附」，
        //    所以名字该跟着按钮走。按钮挂在页面菜单下时是另一条路径，
        //    见 testSyncFromMenu_塌缩_代表按钮让位给页面菜单名
        insertMenu(201L, "查询用户", "system:user:query", MenuTypeEnum.BUTTON.getType());
        insertIdentity("system:user", 201L);

        MenuDO renamed = new MenuDO();
        renamed.setId(201L);
        renamed.setName("用户查询（新）");
        renamed.setSort(7);
        renamed.setStatus(CommonStatusEnum.DISABLE.getStatus());
        menuMapper.updateById(renamed);

        resourceService.syncFromMenu(201L);

        ResourceDO row = resourceMapper.selectById(resourceMapper
                .selectByPermissionIncludeDisabled("system:user").getId());
        assertEquals("用户查询（新）", row.getName());
        assertEquals(7, row.getSort());
        assertEquals(CommonStatusEnum.DISABLE.getStatus(), row.getStatus());
        assertEquals("system:user", row.getPermission());
    }

    @Test
    @DisplayName("塌缩后同步：代表菜单是按钮时，承载行的名字取父级页面菜单而不是动作名")
    public void testSyncFromMenu_塌缩_代表按钮让位给页面菜单名() {
        // 与本库真实形状一致：页面菜单的 permission 是空串，进不了塌缩的候选集，
        // 于是 R1 只能挑按钮当代表，承载行的 menu_id 指着按钮 201
        insertMenu(200L, "数据模型", "", MenuTypeEnum.MENU.getType(), 0L);
        insertMenu(201L, "数据模型查询", "infra:data-model:query",
                MenuTypeEnum.BUTTON.getType(), 200L);
        insertIdentity("infra:data-model", 201L);

        // 管理员只是把按钮改了个名（或者调了 sort 后点保存），页面名一个字没动
        MenuDO renamed = new MenuDO();
        renamed.setId(201L);
        renamed.setName("数据模型列表查询");
        menuMapper.updateById(renamed);

        resourceService.syncFromMenu(201L);

        ResourceDO row = resourceMapper.selectByPermissionIncludeDisabled("infra:data-model");
        // 🔴 名字必须是页面名。取按钮名的后果：授权树上整页的节点叫「数据模型列表查询」，
        //    而承载行不是菜单（菜单管理里看不到它）、资源树又是只读的，没有任何界面能改回去
        assertEquals("数据模型", row.getName());
        assertEquals("infra:data-model", row.getPermission(), "permission 一个字都不能动");
    }

    @Test
    @DisplayName("塌缩后同步：按钮没有页面菜单可依附时，仍然用自己的名字")
    public void testSyncFromMenu_塌缩_代表按钮无页面父级时退回按钮名() {
        // 父级是目录。目录名是容器名（如「系统管理」），比动作名更不像这一页，刻意不采纳
        insertMenu(100L, "系统管理", "", MenuTypeEnum.DIR.getType(), 0L);
        insertMenu(201L, "查询用户", "system:user:query",
                MenuTypeEnum.BUTTON.getType(), 100L);
        insertIdentity("system:user", 201L);

        MenuDO renamed = new MenuDO();
        renamed.setId(201L);
        renamed.setName("用户查询（新）");
        menuMapper.updateById(renamed);

        resourceService.syncFromMenu(201L);

        assertEquals("用户查询（新）",
                resourceMapper.selectByPermissionIncludeDisabled("system:user").getName());
    }

    @Test
    @DisplayName("塌缩后同步：新增同族按钮不建行，新增新页首个按钮才建身份行")
    public void testSyncFromMenu_塌缩_按需建承载行() {
        insertMenu(201L, "查询用户", "system:user:query", MenuTypeEnum.BUTTON.getType());
        insertIdentity("system:user", 201L);
        insertMenu(202L, "新增用户", "system:user:create", MenuTypeEnum.BUTTON.getType());
        insertMenu(203L, "导出角色", "system:role:export", MenuTypeEnum.BUTTON.getType());

        resourceService.syncFromMenu(202L);
        assertEquals(1L, resourceMapper.selectCount(), "同族已有承载行，不该再建");

        resourceService.syncFromMenu(203L);
        assertEquals(2L, resourceMapper.selectCount());
        ResourceDO roleIdentity = resourceMapper.selectByPermissionIncludeDisabled("system:role");
        assertNotNull(roleIdentity, "新页的第一个按钮应当建出身份行");
        assertEquals(ResourceTypeEnum.ACTION.getCode(), roleIdentity.getType());
        assertEquals(203L, roleIdentity.getMenuId());
        // 物化路径的 ident 用的是 permission，改名后必须重算，不能停在动词串上
        assertTrue(roleIdentity.getPath().endsWith("/system:role/"),
                "实际路径：" + roleIdentity.getPath());
        assertFalse(roleIdentity.getPath().contains("export"));
    }

    @Test
    @DisplayName("塌缩后删除：代表菜单没了，menu_id 转交给同族下一个活菜单，行不删")
    public void testSyncFromMenu_塌缩_代表菜单删除时转交() {
        insertMenu(201L, "查询用户", "system:user:query", MenuTypeEnum.BUTTON.getType());
        insertMenu(202L, "新增用户", "system:user:create", MenuTypeEnum.BUTTON.getType());
        ResourceDO identity = insertIdentity("system:user", 201L);

        menuMapper.deleteById(201L);
        resourceService.syncFromMenu(201L);

        // 🔴 删行的后果：授权挂在这一行上，删掉等于把整页按钮的权限一起删掉，
        //    而界面上只会看到「菜单删了」，事后完全反查不出权限是怎么没的
        ResourceDO row = resourceMapper.selectById(identity.getId());
        assertNotNull(row, "同族还有 202 靠它承载，不能删");
        assertEquals(202L, row.getMenuId());
        assertEquals("system:user", row.getPermission());
    }

    @Test
    @DisplayName("塌缩后删除：同族最后一个菜单没了，行才真的删掉")
    public void testSyncFromMenu_塌缩_无接班人时才删行() {
        insertMenu(201L, "查询用户", "system:user:query", MenuTypeEnum.BUTTON.getType());
        ResourceDO identity = insertIdentity("system:user", 201L);

        menuMapper.deleteById(201L);
        resourceService.syncFromMenu(201L);

        assertNull(resourceMapper.selectById(identity.getId()));
    }

    // ============================= PermissionDriftDetector =============================

    @Test
    @DisplayName("塌缩后漂移检测：按承载串比对，绝不把退役的成员行建回来")
    public void testDetect_塌缩_不重建成员行() {
        insertMenu(201L, "查询用户", "system:user:query", MenuTypeEnum.BUTTON.getType());
        insertIdentity("system:user", 201L);

        PermissionDriftDetector.DriftReport report =
                driftDetector.detect(Set.of("system:user:query", "system:user:create"));

        // 🔴 这一条是本用例的全部理由：syncTree 若照原始串推导，
        //    两个成员行都会被 createResource 建回来，而且每次启动都建一次
        assertNull(resourceMapper.selectByPermissionIncludeDisabled("system:user:query"));
        assertNull(resourceMapper.selectByPermissionIncludeDisabled("system:user:create"));
        // 承载行在库里 ⟹ 不算漂移。反过来报「漂移」也是错的：
        // 那条 WARN 说的是「鉴权永远返回 false」，而这里判定得好好的
        assertFalse(report.missing().contains("system:user"));
        assertTrue(report.missing().isEmpty(), "实际：" + report.missing());
        // 约定式推导仍然补它该补的：system:user 的父节点 system 库里没有，建出来
        assertEquals(1, report.created());
        assertNotNull(resourceMapper.selectByPermissionIncludeDisabled("system"));
        assertEquals(2L, resourceMapper.selectCount());
    }

    // ============================= 工具方法 =============================

    /**
     * 直接插一行「塌缩后的身份行」，模拟迁移落库之后的世界
     */
    private ResourceDO insertIdentity(String identity, Long menuId) {
        ResourceDO row = new ResourceDO();
        row.setPermission(identity);
        row.setName(identity);
        row.setType(ResourceTypeEnum.ACTION.getCode());
        row.setParentId(ResourceDO.ID_ROOT);
        row.setSort(0);
        row.setDefaultEffect(ResourceTypeEnum.ACTION.defaultEffect());
        row.setStatus(CommonStatusEnum.ENABLE.getStatus());
        row.setMenuId(menuId);
        resourceService.createResource(row);
        return row;
    }

    private void insertMenu(Long id, String name, String permission, Integer type) {
        insertMenu(id, name, permission, type, 0L);
    }

    /**
     * @param parentId 父菜单编号。默认 0（挂顶层）—— 这意味着「没有页面菜单可依附」，
     *                 {@code carrierDisplayName} 会退回用菜单自己的名字。
     *                 要覆盖「按钮挂在页面菜单下」那条路径，必须显式传父级
     */
    private void insertMenu(Long id, String name, String permission, Integer type, Long parentId) {
        MenuDO menu = new MenuDO();
        menu.setId(id);
        menu.setName(name);
        menu.setPermission(permission);
        menu.setType(type);
        menu.setSort(0);
        menu.setParentId(parentId);
        menu.setStatus(CommonStatusEnum.ENABLE.getStatus());
        menu.setPath("");
        menu.setIcon("#");
        menu.setComponent("");
        menu.setComponentName("");
        menu.setVisible(true);
        menu.setKeepAlive(true);
        menu.setAlwaysShow(false);
        menuMapper.insert(menu);
    }

}
