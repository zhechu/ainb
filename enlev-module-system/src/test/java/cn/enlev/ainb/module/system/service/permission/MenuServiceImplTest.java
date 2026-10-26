package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.menu.MenuListReqVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.menu.MenuSaveVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.MenuMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.enums.permission.MenuTypeEnum;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.service.tenant.TenantService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static cn.enlev.ainb.framework.common.util.collection.SetUtils.asSet;
import static cn.enlev.ainb.framework.common.util.object.ObjectUtils.cloneIgnoreId;
import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.framework.test.core.util.RandomUtils.*;
import static cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO.ID_ROOT;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

@Import(MenuServiceImpl.class)
public class MenuServiceImplTest extends BaseDbUnitTest {

    @Resource
    private MenuServiceImpl menuService;

    @Resource
    private MenuMapper menuMapper;
    @Resource
    private ResourceMapper resourceMapper;

    @MockitoBean
    private PermissionService permissionService;
    @MockitoBean
    private TenantService tenantService;
    /**
     * 菜单增删改会调 {@code syncFromMenu} 实时同步资源表。
     * 这里 mock 掉：本测试类验的是菜单自身的行为，
     * 同步逻辑由 {@code menu_resource_realtime_sync.py} 端到端覆盖
     */
    @MockitoBean
    private ResourceService resourceService;

    @Test
    public void testCreateMenu_success() {
        // mock 数据（构造父菜单）
        MenuDO menuDO = buildMenuDO(MenuTypeEnum.MENU,
                "parent", 0L);
        menuMapper.insert(menuDO);
        Long parentId = menuDO.getId();
        // 准备参数
        MenuSaveVO reqVO = randomPojo(MenuSaveVO.class, o -> {
            o.setParentId(parentId);
            o.setName("testSonName");
            o.setType(MenuTypeEnum.MENU.getType());
        }).setId(null); // 防止 id 被赋值
        Long menuId = menuService.createMenu(reqVO);

        // 校验记录的属性是否正确
        MenuDO dbMenu = menuMapper.selectById(menuId);
        assertPojoEquals(reqVO, dbMenu, "id");
    }

    @Test
    public void testUpdateMenu_success() {
        // mock 数据（构造父子菜单）
        MenuDO sonMenuDO = createParentAndSonMenu();
        Long sonId = sonMenuDO.getId();
        // 准备参数
        MenuSaveVO reqVO = randomPojo(MenuSaveVO.class, o -> {
            o.setId(sonId);
            o.setName("testSonName"); // 修改名字
            o.setParentId(sonMenuDO.getParentId());
            o.setType(MenuTypeEnum.MENU.getType());
        });

        // 调用
        menuService.updateMenu(reqVO);
        // 校验记录的属性是否正确
        MenuDO dbMenu = menuMapper.selectById(sonId);
        assertPojoEquals(reqVO, dbMenu);
    }

    @Test
    public void testUpdateMenu_sonIdNotExist() {
        // 准备参数
        MenuSaveVO reqVO = randomPojo(MenuSaveVO.class);
        // 调用，并断言异常
        assertServiceException(() -> menuService.updateMenu(reqVO), MENU_NOT_EXISTS);
    }

    @Test
    public void testDeleteMenu_success() {
        // mock 数据
        MenuDO menuDO = randomPojo(MenuDO.class);
        menuMapper.insert(menuDO);
        // 准备参数
        Long id = menuDO.getId();

        // 调用
        menuService.deleteMenu(id);
        // 断言
        MenuDO dbMenuDO = menuMapper.selectById(id);
        assertNull(dbMenuDO);
        verify(permissionService).processMenuDeleted(id);
    }

    @Test
    public void testDeleteMenu_menuNotExist() {
        assertServiceException(() -> menuService.deleteMenu(randomLongId()),
                MENU_NOT_EXISTS);
    }

    @Test
    public void testDeleteMenu_existChildren() {
        // mock 数据（构造父子菜单）
        MenuDO sonMenu = createParentAndSonMenu();
        // 准备参数
        Long parentId = sonMenu.getParentId();

        // 调用并断言异常
        assertServiceException(() -> menuService.deleteMenu(parentId), MENU_EXISTS_CHILDREN);
    }

    /**
     * 批量删一整棵子树：父与子在同一批里被勾中，必须能一次删干净。
     *
     * <p>🔴 这是「批量删除」最常见的用法，也是最容易被写错的一处：若照单删的口径判
     * 「有子菜单就拒」，父永远删不掉（它的子还在表里），管理员只能自底向上分好几轮删。
     * 正确的判据是「有没有<b>不在本批里</b>的子菜单」，见 {@code MenuServiceImpl#deleteMenuList}。
     */
    @Test
    public void testDeleteMenuList_wholeSubtreeInOneBatch() {
        // mock 数据：一个目录 + 挂在它下面的两个按钮
        MenuDO parent = buildMenuDO(MenuTypeEnum.MENU, "parent", ID_ROOT);
        menuMapper.insert(parent);
        MenuDO son1 = buildMenuDO(MenuTypeEnum.BUTTON, "son1", parent.getId());
        MenuDO son2 = buildMenuDO(MenuTypeEnum.BUTTON, "son2", parent.getId());
        menuMapper.insert(son1);
        menuMapper.insert(son2);
        List<Long> ids = List.of(parent.getId(), son1.getId(), son2.getId());

        // 调用
        menuService.deleteMenuList(ids);

        // 断言：三行全没了，且每行都清掉了角色授权
        ids.forEach(id -> {
            assertNull(menuMapper.selectById(id));
            verify(permissionService).processMenuDeleted(id);
        });
    }

    /**
     * 本批之外还有子菜单 → 整批拒绝，且<b>一条都不删</b>。
     *
     * <p>🔴 后半句才是这条用例的重点：批量删若「边删边校验」，就会删掉前面几条、在出错那条抛异常，
     * 用户看到报错以为一条都没动，回列表才发现少了几行。故必须先全校验、再全删除。
     */
    @Test
    public void testDeleteMenuList_existChildrenNotSelected() {
        // mock 数据：父有两个子，本批只勾了父 + 其中一个子，另一个子留在批外
        MenuDO parent = buildMenuDO(MenuTypeEnum.MENU, "parent", ID_ROOT);
        menuMapper.insert(parent);
        MenuDO son1 = buildMenuDO(MenuTypeEnum.BUTTON, "son1", parent.getId());
        MenuDO son2 = buildMenuDO(MenuTypeEnum.BUTTON, "son2", parent.getId());
        menuMapper.insert(son1);
        menuMapper.insert(son2);

        // 调用并断言异常
        assertServiceException(() -> menuService.deleteMenuList(List.of(parent.getId(), son1.getId())),
                MENU_EXISTS_CHILDREN);
        // 断言：一条都没删（含批内那个本可以删的 son1）
        assertNotNull(menuMapper.selectById(parent.getId()));
        assertNotNull(menuMapper.selectById(son1.getId()));
        assertNotNull(menuMapper.selectById(son2.getId()));
    }

    /** 空列表不该发任何 SQL（前端会禁用按钮，但万一发了也不能拼出 {@code in ()} 这种非法条件） */
    @Test
    public void testDeleteMenuList_empty() {
        menuService.deleteMenuList(Collections.emptyList());

        assertEquals(0, menuMapper.selectList().size());
    }

    @Test
    public void testGetMenuList_all() {
        // mock 数据
        MenuDO menu100 = randomPojo(MenuDO.class);
        menuMapper.insert(menu100);
        MenuDO menu101 = randomPojo(MenuDO.class);
        menuMapper.insert(menu101);
        // 准备参数

        // 调用
        List<MenuDO> list = menuService.getMenuList();
        // 断言
        assertEquals(2, list.size());
        assertPojoEquals(menu100, list.get(0));
        assertPojoEquals(menu101, list.get(1));
    }

    @Test
    public void testGetMenuList() {
        // mock 数据
        MenuDO menuDO = randomPojo(MenuDO.class, o -> o.setName("芋艿").setStatus(CommonStatusEnum.ENABLE.getStatus()));
        menuMapper.insert(menuDO);
        // 测试 status 不匹配
        menuMapper.insert(cloneIgnoreId(menuDO, o -> o.setStatus(CommonStatusEnum.DISABLE.getStatus())));
        // 测试 name 不匹配
        menuMapper.insert(cloneIgnoreId(menuDO, o -> o.setName("艿")));
        // 准备参数
        MenuListReqVO reqVO = new MenuListReqVO().setName("芋").setStatus(CommonStatusEnum.ENABLE.getStatus());

        // 调用
        List<MenuDO> result = menuService.getMenuList(reqVO);
        // 断言
        assertEquals(1, result.size());
        assertPojoEquals(menuDO, result.get(0));
    }

    @Test
    public void testGetMenuListByTenant() {
        // mock 数据
        MenuDO menu100 = randomPojo(MenuDO.class, o -> o.setId(100L).setStatus(CommonStatusEnum.ENABLE.getStatus()));
        menuMapper.insert(menu100);
        MenuDO menu101 = randomPojo(MenuDO.class, o -> o.setId(101L).setStatus(CommonStatusEnum.DISABLE.getStatus()));
        menuMapper.insert(menu101);
        MenuDO menu102 = randomPojo(MenuDO.class, o -> o.setId(102L).setStatus(CommonStatusEnum.ENABLE.getStatus()));
        menuMapper.insert(menu102);
        // mock 过滤菜单
        Set<Long> menuIds = asSet(100L, 101L);
        doNothing().when(tenantService).handleTenantMenu(argThat(handler -> {
            handler.handle(menuIds);
            return true;
        }));
        // 准备参数
        MenuListReqVO reqVO = new MenuListReqVO().setStatus(CommonStatusEnum.ENABLE.getStatus());

        // 调用
        List<MenuDO> result = menuService.getMenuListByTenant(reqVO);
        // 断言
        assertEquals(1, result.size());
        assertPojoEquals(menu100, result.get(0));
    }

    @Test
    public void testGetMenuIdListByPermissionFromCache() {
        // S1b：查询路径已切到 sys_resource，所以准备数据要往 sys_resource 插，而不是 system_menu。
        // MenuDO 仍然存在（导航用），但 permission 字段不再是权限查询的依据。
        MenuDO menu100 = randomPojo(MenuDO.class);
        menuMapper.insert(menu100);
        MenuDO menu101 = randomPojo(MenuDO.class);
        menuMapper.insert(menu101);
        // 在 sys_resource 里建对应的资源行，menuId 关联回 menu100
        String permission = menu100.getPermission();
        cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO resource =
                new cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO();
        resource.setPermission(permission);
        resource.setName("test");
        resource.setType(cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum.ACTION.getCode());
        resource.setParentId(cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO.ID_ROOT);
        resource.setSort(0);
        resource.setDefaultEffect("DENY");
        resource.setStatus(0);
        resource.setMenuId(menu100.getId());
        resource.setPath("/root/" + permission + "/");
        resource.setDepth((short) 1);
        resourceMapper.insert(resource);

        // 调用
        List<Long> ids = menuService.getMenuIdListByPermissionFromCache(permission);
        // 断言：返回的是 resource.menuId（即 menu100.getId()），以保证下游角色-菜单关联查询不受影响
        assertEquals(1, ids.size());
        assertEquals(menu100.getId(), ids.get(0));
    }

    @Test
    public void testGetMenuList_ids() {
        // mock 数据
        MenuDO menu100 = randomPojo(MenuDO.class);
        menuMapper.insert(menu100);
        MenuDO menu101 = randomPojo(MenuDO.class);
        menuMapper.insert(menu101);
        // 准备参数
        Collection<Long> ids = Collections.singleton(menu100.getId());

        // 调用
        List<MenuDO> list = menuService.getMenuList(ids);
        // 断言
        assertEquals(1, list.size());
        assertPojoEquals(menu100, list.get(0));
    }

    @Test
    public void testGetMenu() {
        // mock 数据
        MenuDO menu = randomPojo(MenuDO.class);
        menuMapper.insert(menu);
        // 准备参数
        Long id = menu.getId();

        // 调用
        MenuDO dbMenu = menuService.getMenu(id);
        // 断言
        assertPojoEquals(menu, dbMenu);
    }

    @Test
    public void testValidateParentMenu_success() {
        // mock 数据
        MenuDO menuDO = buildMenuDO(MenuTypeEnum.MENU, "parent", 0L);
        menuMapper.insert(menuDO);
        // 准备参数
        Long parentId = menuDO.getId();

        // 调用，无需断言
        menuService.validateParentMenu(parentId, null);
    }

    @Test
    public void testValidateParentMenu_canNotSetSelfToBeParent() {
        // 调用，并断言异常
        assertServiceException(() -> menuService.validateParentMenu(1L, 1L),
                MENU_PARENT_ERROR);
    }

    @Test
    public void testValidateParentMenu_parentNotExist() {
        // 调用，并断言异常
        assertServiceException(() -> menuService.validateParentMenu(randomLongId(), null),
                MENU_PARENT_NOT_EXISTS);
    }

    @Test
    public void testValidateParentMenu_parentTypeError() {
        // mock 数据
        MenuDO menuDO = buildMenuDO(MenuTypeEnum.BUTTON, "parent", 0L);
        menuMapper.insert(menuDO);
        // 准备参数
        Long parentId = menuDO.getId();

        // 调用，并断言异常
        assertServiceException(() -> menuService.validateParentMenu(parentId, null),
                MENU_PARENT_NOT_DIR_OR_MENU);
    }

    @Test
    public void testValidateMenu_Name_success() {
        // mock 父子菜单
        MenuDO sonMenu = createParentAndSonMenu();
        // 准备参数
        Long parentId = sonMenu.getParentId();
        Long otherSonMenuId = randomLongId();
        String otherSonMenuName = randomString();

        // 调用，无需断言
        menuService.validateMenuName(parentId, otherSonMenuName, otherSonMenuId);
    }

    @Test
    public void testValidateMenu_sonMenuNameNameDuplicate() {
        // mock 父子菜单
        MenuDO sonMenu = createParentAndSonMenu();
        // 准备参数
        Long parentId = sonMenu.getParentId();
        Long otherSonMenuId = randomLongId();
        String otherSonMenuName = sonMenu.getName(); //相同名称

        // 调用，并断言异常
        assertServiceException(() -> menuService.validateMenuName(parentId, otherSonMenuName, otherSonMenuId),
                MENU_NAME_DUPLICATE);
    }

    // ====================== 初始化方法 ======================

    /**
     * 插入父子菜单，返回子菜单
     *
     * @return 子菜单
     */
    private MenuDO createParentAndSonMenu() {
        // 构造父子菜单
        MenuDO parentMenuDO = buildMenuDO(MenuTypeEnum.MENU, "parent", ID_ROOT);
        menuMapper.insert(parentMenuDO);
        // 构建子菜单
        MenuDO sonMenuDO = buildMenuDO(MenuTypeEnum.MENU, "testSonName",
                parentMenuDO.getParentId());
        menuMapper.insert(sonMenuDO);
        return sonMenuDO;
    }

    private MenuDO buildMenuDO(MenuTypeEnum type, String name, Long parentId) {
        return buildMenuDO(type, name, parentId, randomCommonStatus());
    }

    private MenuDO buildMenuDO(MenuTypeEnum type, String name, Long parentId, Integer status) {
        return randomPojo(MenuDO.class, o -> o.setId(null).setName(name).setParentId(parentId)
                .setType(type.getType()).setStatus(status));
    }

}
