package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.MenuMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.enums.permission.MenuTypeEnum;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionConfiguration;
import cn.enlev.ainb.module.system.framework.permission.core.scan.CollapsedResourceLocator;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ResourceServiceImpl} 的单元测试类
 *
 * 重点：物化路径维护（单节点 / 级联更新）、按需创建语义、迁移幂等性
 *
 * @author 走进新时代
 */
@Import({ResourceServiceImpl.class, CollapsedResourceLocator.class, PermissionConfiguration.class})
public class ResourceServiceImplTest extends BaseDbUnitTest {

    @Resource
    private ResourceServiceImpl resourceService;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private MenuMapper menuMapper;
    /**
     * 只用来验「五个写方法都驱逐了资源链缓存」这一件事，不参与判定逻辑
     */
    @MockitoBean
    private GrantDecisionService grantDecisionService;

    // ============================= 物化路径 =============================

    @Test
    public void testCreateResource_根节点路径() {
        ResourceDO resource = build("system:user:query", ResourceTypeEnum.ACTION, ResourceDO.ID_ROOT);
        resourceService.createResource(resource);

        ResourceDO saved = resourceMapper.selectById(resource.getId());
        assertEquals("/root/system:user:query/", saved.getPath());
        assertEquals(1, (int) saved.getDepth());
    }

    @Test
    public void testCreateResource_嵌套路径() {
        // 先建父
        ResourceDO parent = build("system:user", ResourceTypeEnum.MENU, ResourceDO.ID_ROOT);
        resourceService.createResource(parent);

        // 再建子
        ResourceDO child = build("system:user:query", ResourceTypeEnum.ACTION, parent.getId());
        resourceService.createResource(child);

        ResourceDO saved = resourceMapper.selectById(child.getId());
        assertEquals("/root/system:user/system:user:query/", saved.getPath());
        assertEquals(2, (int) saved.getDepth());
    }

    @Test
    public void testUpdateResource_permission变更级联更新路径() {
        ResourceDO parent = build("system:user", ResourceTypeEnum.MENU, ResourceDO.ID_ROOT);
        resourceService.createResource(parent);
        ResourceDO child = build("system:user:query", ResourceTypeEnum.ACTION, parent.getId());
        resourceService.createResource(child);

        // 改父的 permission，子的路径必须跟着变
        ResourceDO updateParent = new ResourceDO();
        updateParent.setId(parent.getId());
        updateParent.setPermission("sys:user");
        updateParent.setName("用户管理（改名）");
        updateParent.setType(ResourceTypeEnum.MENU.getCode());
        updateParent.setParentId(ResourceDO.ID_ROOT);
        updateParent.setSort(1);
        updateParent.setDefaultEffect(ResourceTypeEnum.MENU.defaultEffect());
        updateParent.setStatus(CommonStatusEnum.ENABLE.getStatus());
        resourceService.updateResource(updateParent);

        ResourceDO updatedParent = resourceMapper.selectById(parent.getId());
        assertEquals("/root/sys:user/", updatedParent.getPath());

        // 子路径也跟着变了
        ResourceDO updatedChild = resourceMapper.selectById(child.getId());
        assertEquals("/root/sys:user/system:user:query/", updatedChild.getPath());
        // ⚠️ depth 也必须跟着对。级联更新曾用「斜杠数-1」算 depth，
        // 比 fillPathAndDepth 的口径多 1，于是同一个节点新建时 depth 对、
        // 父节点一变就被写大 1（实测库里曾有 219 个这种）。
        // 原来这里只断言 path，depth 错了测试照样绿，所以 bug 一直没被发现
        assertEquals(2, (int) updatedChild.getDepth());
    }

    @Test
    public void testUpdateResource_parentId变更级联更新路径() {
        ResourceDO p1 = build("system", ResourceTypeEnum.MENU, ResourceDO.ID_ROOT);
        ResourceDO p2 = build("bpm", ResourceTypeEnum.MENU, ResourceDO.ID_ROOT);
        ResourceDO child = build("system:user:query", ResourceTypeEnum.ACTION, ResourceDO.ID_ROOT);
        resourceService.createResource(p1);
        resourceService.createResource(p2);
        resourceService.createResource(child);

        // 把 child 从根移到 p1 下
        ResourceDO update = new ResourceDO();
        update.setId(child.getId());
        update.setPermission("system:user:query");
        update.setName("查询");
        update.setType(ResourceTypeEnum.ACTION.getCode());
        update.setParentId(p1.getId());    // 关键：移到 p1
        update.setSort(1);
        update.setDefaultEffect(ResourceTypeEnum.ACTION.defaultEffect());
        update.setStatus(CommonStatusEnum.ENABLE.getStatus());
        resourceService.updateResource(update);

        ResourceDO moved = resourceMapper.selectById(child.getId());
        assertEquals("/root/system/system:user:query/", moved.getPath());
        assertEquals(2, (int) moved.getDepth());
    }

    @Test
    public void testUpdateResource_多层级联时depth与path口径一致() {
        // 三层：system → system:user → system:user:query
        ResourceDO root = build("system", ResourceTypeEnum.MENU, ResourceDO.ID_ROOT);
        resourceService.createResource(root);
        ResourceDO mid = build("system:user", ResourceTypeEnum.MENU, root.getId());
        resourceService.createResource(mid);
        ResourceDO leaf = build("system:user:query", ResourceTypeEnum.ACTION, mid.getId());
        resourceService.createResource(leaf);
        assertEquals(3, (int) resourceMapper.selectById(leaf.getId()).getDepth());

        // 改最上层的 permission，触发两层级联
        ResourceDO update = new ResourceDO();
        update.setId(root.getId());
        update.setPermission("sys");
        update.setName("系统");
        update.setType(ResourceTypeEnum.MENU.getCode());
        update.setParentId(ResourceDO.ID_ROOT);
        update.setSort(1);
        update.setDefaultEffect(ResourceTypeEnum.MENU.defaultEffect());
        update.setStatus(CommonStatusEnum.ENABLE.getStatus());
        resourceService.updateResource(update);

        // 级联后每一层的 depth 都要与 path 的层数吻合（path 斜杠数 - 2）
        for (ResourceDO r : resourceMapper.selectList()) {
            long slash = r.getPath().chars().filter(c -> c == '/').count();
            assertEquals((int) (slash - 2), (int) r.getDepth(),
                    "depth 与 path 不一致：" + r.getPath() + " depth=" + r.getDepth());
        }
    }

    @Test
    public void testDeleteResource_有子节点时拒绝() {
        ResourceDO parent = build("system:user", ResourceTypeEnum.MENU, ResourceDO.ID_ROOT);
        resourceService.createResource(parent);
        ResourceDO child = build("system:user:query", ResourceTypeEnum.ACTION, parent.getId());
        resourceService.createResource(child);

        assertThrows(Exception.class, () -> resourceService.deleteResource(parent.getId()));
        // 没有子节点的才能删
        assertDoesNotThrow(() -> resourceService.deleteResource(child.getId()));
        assertDoesNotThrow(() -> resourceService.deleteResource(parent.getId()));
    }

    @Test
    public void testDeleteResourceList_父子一起勾选_整棵删除() {
        ResourceDO parent = build("system:user", ResourceTypeEnum.MENU, ResourceDO.ID_ROOT);
        resourceService.createResource(parent);
        ResourceDO child = build("system:user:query", ResourceTypeEnum.ACTION, parent.getId());
        resourceService.createResource(child);

        // 父与子都在这批里 = 整棵子树一起删，放行；两行都被软删
        assertDoesNotThrow(() -> resourceService.deleteResourceList(List.of(parent.getId(), child.getId())));
        assertNull(resourceMapper.selectById(parent.getId()));
        assertNull(resourceMapper.selectById(child.getId()));
    }

    @Test
    public void testDeleteResourceList_子节点没一起勾选_整批拒绝() {
        ResourceDO parent = build("system:user", ResourceTypeEnum.MENU, ResourceDO.ID_ROOT);
        resourceService.createResource(parent);
        ResourceDO child = build("system:user:query", ResourceTypeEnum.ACTION, parent.getId());
        resourceService.createResource(child);

        // 只勾父、子没勾：删了会在树里留下孤儿，整批拒绝，父与子都还在
        assertThrows(Exception.class, () -> resourceService.deleteResourceList(List.of(parent.getId())));
        assertNotNull(resourceMapper.selectById(parent.getId()));
        assertNotNull(resourceMapper.selectById(child.getId()));
    }

    @Test
    public void testDeleteResourceList_空列表_不抛不删() {
        ResourceDO resource = build("system:user:query", ResourceTypeEnum.ACTION, ResourceDO.ID_ROOT);
        resourceService.createResource(resource);

        assertDoesNotThrow(() -> resourceService.deleteResourceList(List.of()));
        assertNotNull(resourceMapper.selectById(resource.getId()));
    }

    // ============================= 按需创建语义 =============================

    @Test
    public void testGetResourceListByPermission_只返回启用的() {
        ResourceDO enabled = build("system:user:query", ResourceTypeEnum.ACTION, ResourceDO.ID_ROOT);
        enabled.setStatus(CommonStatusEnum.ENABLE.getStatus());
        ResourceDO disabled = build("system:user:query", ResourceTypeEnum.ACTION, ResourceDO.ID_ROOT);
        disabled.setStatus(CommonStatusEnum.DISABLE.getStatus());
        resourceService.createResource(enabled);
        resourceService.createResource(disabled);

        List<ResourceDO> results = resourceService.getResourceListByPermission("system:user:query");
        assertEquals(1, results.size());
        assertEquals(enabled.getId(), results.get(0).getId());
    }

    // ============================= 迁移 =============================

    @Test
    public void testMigrateFromMenu_非目录都迁_无权限串也建MENU类型资源() {
        insertMenu(100L, "用户管理菜单", "", MenuTypeEnum.MENU.getType(), CommonStatusEnum.ENABLE.getStatus());
        insertMenu(101L, "查询用户", "system:user:query", MenuTypeEnum.BUTTON.getType(), CommonStatusEnum.ENABLE.getStatus());
        insertMenu(102L, "新增用户", "system:user:create", MenuTypeEnum.BUTTON.getType(), CommonStatusEnum.ENABLE.getStatus());
        insertMenu(103L, "空权限按钮", "", MenuTypeEnum.BUTTON.getType(), CommonStatusEnum.ENABLE.getStatus());
        // 菜单层带 permission（实测本库有 68 个，如 infra:codegen:query）—— 应建成 ACTION 类型
        insertMenu(104L, "代码生成", "infra:codegen:query", MenuTypeEnum.MENU.getType(), CommonStatusEnum.ENABLE.getStatus());
        // 目录是唯一不建的类型
        insertMenu(105L, "目录层", "", MenuTypeEnum.DIR.getType(), CommonStatusEnum.ENABLE.getStatus());

        resourceService.migrateFromMenu();

        // 迁的是 100 / 101 / 102 / 103 / 104 —— 全部非目录的 5 条
        // 无 permission 的 100 / 103 建成 MENU 类型；有 permission 的建成 ACTION 类型
        assertEquals(5L, resourceMapper.selectCount());
        assertNotNull(resourceMapper.selectByMenuId(100L));  // MENU 类型（无权限串）
        assertNotNull(resourceMapper.selectByMenuId(101L));  // ACTION 类型
        assertNotNull(resourceMapper.selectByMenuId(102L));  // ACTION 类型
        assertNotNull(resourceMapper.selectByMenuId(103L));  // MENU 类型（无权限串，按钮但没配权限）
        assertNotNull(resourceMapper.selectByMenuId(104L));  // ACTION 类型（菜单层但有权限串）
        // 只有目录不建
        assertNull(resourceMapper.selectByMenuId(105L));

        // 无权限串的行类型是 MENU，有权限串的是 ACTION
        ResourceDO noPermMenu = resourceMapper.selectByMenuId(100L);
        assertEquals(ResourceTypeEnum.MENU.getCode(), noPermMenu.getType());
        assertEquals("DENY", noPermMenu.getDefaultEffect());

        ResourceDO withPermBtn = resourceMapper.selectByMenuId(101L);
        assertEquals(ResourceTypeEnum.ACTION.getCode(), withPermBtn.getType());
    }

    @Test
    public void testMigrateFromMenu_带权限标识的菜单层建成ACTION_无权限标识的建成MENU() {
        // 有权限串的菜单层
        insertMenu(104L, "代码生成", "infra:codegen:query", MenuTypeEnum.MENU.getType(), CommonStatusEnum.ENABLE.getStatus());
        // 无权限串的菜单层（如「发起流程」、「地区管理」这类）
        insertMenu(105L, "发起流程", "", MenuTypeEnum.MENU.getType(), CommonStatusEnum.ENABLE.getStatus());

        resourceService.migrateFromMenu();

        // 有权限串 → ACTION（这行是要被授权的权限点，类型跟着语义走）
        ResourceDO actionResource = resourceMapper.selectByMenuId(104L);
        assertEquals(ResourceTypeEnum.ACTION.getCode(), actionResource.getType());
        assertEquals("DENY", actionResource.getDefaultEffect());
        assertEquals("infra:codegen:query", actionResource.getPermission());

        // 无权限串 → MENU（靠 menu_id 映射回导航，可以被显式授权）
        ResourceDO menuResource = resourceMapper.selectByMenuId(105L);
        assertNotNull(menuResource);
        assertEquals(ResourceTypeEnum.MENU.getCode(), menuResource.getType());
        assertEquals("DENY", menuResource.getDefaultEffect());
        assertTrue(StrUtil.isBlank(menuResource.getPermission()));
        // 物化路径用 "menu:<menuId>" 做 ident，不能是 "_tmp_"
        assertTrue(menuResource.getPath().contains("menu:105"));
    }

    @Test
    public void testMigrateFromMenu_幂等() {
        insertMenu(101L, "查询用户", "system:user:query", MenuTypeEnum.BUTTON.getType(), CommonStatusEnum.ENABLE.getStatus());

        resourceService.migrateFromMenu();
        resourceService.migrateFromMenu();

        // 迁移两次，资源行只有一行
        assertEquals(1L, resourceMapper.selectCount());
    }

    @Test
    public void testMigrateFromMenu_menu_id字段正确() {
        insertMenu(101L, "查询用户", "system:user:query", MenuTypeEnum.BUTTON.getType(), CommonStatusEnum.ENABLE.getStatus());

        resourceService.migrateFromMenu();

        ResourceDO resource = resourceMapper.selectByMenuId(101L);
        assertNotNull(resource);
        assertEquals("system:user:query", resource.getPermission());
        assertEquals(ResourceTypeEnum.ACTION.getCode(), resource.getType());
        assertEquals(101L, resource.getMenuId());
        assertEquals("DENY", resource.getDefaultEffect());
        assertTrue(resource.getPath().contains("system:user:query"));
    }

    // ============================= 资源链缓存驱逐 =============================

    /**
     * {@code GrantDecisionService#resourceChainCache} 是进程内 {@code ConcurrentHashMap}、<b>没有 TTL</b>，
     * 而本类的这五个写方法是资源树唯一的变化入口 —— 漏掉任意一个，新配的授权就要等重启才生效。
     *
     * <p>实测过的后果（不是假想）：流程发布时 {@code registerProcess} 建资源<b>之前</b>，判定侧已经
     * 查过一次这个 permission，空链被缓存住；随后资源行与 START 授权都写对了、库里查也对，
     * 发起流程列表却永远看不见这个流程，重启才恢复。
     */
    @Test
    public void testEvictChainCache_五个写方法都驱逐() {
        ResourceDO parent = build("bpm:process:doc_sample_approval", ResourceTypeEnum.PROCESS, ResourceDO.ID_ROOT);
        resourceService.createResource(parent);
        verify(grantDecisionService).evictResourceChainCache();

        clearInvocations(grantDecisionService);
        parent.setName("样品检测审批");
        resourceService.updateResource(parent);
        verify(grantDecisionService).evictResourceChainCache();

        clearInvocations(grantDecisionService);
        ResourceDO child = build("bpm:node:doc_sample_approval:StartUserNode", ResourceTypeEnum.PROCESS, parent.getId());
        resourceService.createResource(child);
        clearInvocations(grantDecisionService);
        resourceService.deleteResource(child.getId());
        verify(grantDecisionService).evictResourceChainCache();

        clearInvocations(grantDecisionService);
        resourceService.deleteResourceList(List.of(parent.getId()));
        verify(grantDecisionService).evictResourceChainCache();

        // syncFromMenu 有 6 个出口、5 处直接写库，所以驱逐挂在 finally 上：
        // 连「菜单查不到」这条什么都没改的早退路也要清 —— 上一轮可能已经把空链缓存住了
        insertMenu(101L, "查询用户", "system:user:query", MenuTypeEnum.BUTTON.getType(), CommonStatusEnum.ENABLE.getStatus());
        clearInvocations(grantDecisionService);
        resourceService.syncFromMenu(101L);
        verify(grantDecisionService, atLeastOnce()).evictResourceChainCache();

        clearInvocations(grantDecisionService);
        resourceService.syncFromMenu(999L);
        verify(grantDecisionService, atLeastOnce()).evictResourceChainCache();

        // 反过来：真没动库的就别清。空列表是纯 no-op，清一次等于让全站的资源链白重建一遍
        clearInvocations(grantDecisionService);
        resourceService.deleteResourceList(List.of());
        verify(grantDecisionService, never()).evictResourceChainCache();
    }

    // ============================= 工具方法 =============================

    private ResourceDO build(String permission, ResourceTypeEnum type, Long parentId) {
        ResourceDO r = new ResourceDO();
        r.setPermission(permission);
        r.setName(permission);
        r.setType(type.getCode());
        r.setParentId(parentId);
        r.setSort(0);
        r.setDefaultEffect(type.defaultEffect());
        r.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return r;
    }

    private void insertMenu(Long id, String name, String permission, Integer type, Integer status) {
        MenuDO menu = new MenuDO();
        menu.setId(id);
        menu.setName(name);
        menu.setPermission(permission);
        menu.setType(type);
        menu.setSort(0);
        menu.setParentId(0L);
        menu.setStatus(status);
        // 导航字段置空（模拟真实情况）
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
