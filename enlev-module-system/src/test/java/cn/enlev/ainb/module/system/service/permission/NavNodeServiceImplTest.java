package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeSaveVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeTreeRespVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.NavNodeDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.NavNodeMapper;
import cn.enlev.ainb.module.system.enums.permission.NavNodeTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import jakarta.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.NAV_NODE_EXISTS_CHILDREN;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NavNodeServiceImpl} 的单元测试类
 *
 * <p>聚焦最高风险、也最难肉眼复核的一块：{@link NavNodeService#getNavTree} 的
 * 「授权命中 + visible/status + 分组级联隐藏 + terminal 隔离」过滤逻辑（「移动端优化方案」§4.7）
 *
 * @author 走进新时代
 */
@Import(NavNodeServiceImpl.class)
public class NavNodeServiceImplTest extends BaseDbUnitTest {

    @Resource
    private NavNodeServiceImpl navNodeService;
    @Resource
    private NavNodeMapper navNodeMapper;

    /**
     * 造一个节点并落库。id 显式给定，保证树结构确定
     */
    private void insert(Long id, String terminal, Long parentId, NavNodeTypeEnum type, String name,
                        String permissions, Boolean visible, Integer status) {
        NavNodeDO n = new NavNodeDO();
        n.setId(id);
        n.setTerminal(terminal);
        n.setParentId(parentId);
        n.setNodeType(type.getType());
        n.setName(name);
        n.setPermissions(permissions);
        n.setVisible(visible);
        n.setStatus(status);
        n.setRoute("");
        n.setIcon("");
        n.setIconColor("");
        n.setQuery("");
        n.setIsTabbar(false);
        n.setOnlyPc(false);
        n.setSort(0);
        navNodeMapper.insert(n);
    }

    private void insertApp(Long id, Long parentId, NavNodeTypeEnum type, String name,
                           String permissions, Boolean visible, Integer status) {
        insert(id, "app", parentId, type, name, permissions, visible, status);
    }

    /** ① permissions 命中的项展示、未命中的隐藏 */
    @Test
    public void testGetNavTree_permissionFilter() {
        // group A ▸ sub A1 ▸ {item X(app:x), item Y(app:y)}
        insertApp(1L, NavNodeDO.ID_ROOT, NavNodeTypeEnum.GROUP, "A", "", true, CommonStatusEnum.ENABLE.getStatus());
        insertApp(2L, 1L, NavNodeTypeEnum.SUB_GROUP, "A1", "", true, CommonStatusEnum.ENABLE.getStatus());
        insertApp(3L, 2L, NavNodeTypeEnum.ITEM, "X", "app:x", true, CommonStatusEnum.ENABLE.getStatus());
        insertApp(4L, 2L, NavNodeTypeEnum.ITEM, "Y", "app:y", true, CommonStatusEnum.ENABLE.getStatus());

        List<NavNodeTreeRespVO> tree = navNodeService.getNavTree("app", Set.of("app:x"));

        assertEquals(1, tree.size());
        NavNodeTreeRespVO a = tree.get(0);
        assertEquals("A", a.getName());
        assertEquals(1, a.getChildren().size());
        NavNodeTreeRespVO a1 = a.getChildren().get(0);
        assertEquals("A1", a1.getName());
        // 只有 X 命中，Y 未命中被过滤
        assertEquals(1, a1.getChildren().size());
        assertEquals(3L, a1.getChildren().get(0).getId());
    }

    /** ①补 多权限「任一命中」：permissions="app:x,app:z"，用户只有 app:z 也应展示 */
    @Test
    public void testGetNavTree_anyOfPermissions() {
        insertApp(1L, NavNodeDO.ID_ROOT, NavNodeTypeEnum.GROUP, "A", "", true, CommonStatusEnum.ENABLE.getStatus());
        insertApp(2L, 1L, NavNodeTypeEnum.ITEM, "X", "app:x,app:z", true, CommonStatusEnum.ENABLE.getStatus());

        List<NavNodeTreeRespVO> tree = navNodeService.getNavTree("app", Set.of("app:z"));

        assertEquals(1, tree.size());
        assertEquals(1, tree.get(0).getChildren().size());
        assertEquals("X", tree.get(0).getChildren().get(0).getName());
    }

    /** ② permissions 为空的项不设门槛：即使用户授权集合为空也展示 */
    @Test
    public void testGetNavTree_emptyPermissionsAlwaysVisible() {
        insertApp(1L, NavNodeDO.ID_ROOT, NavNodeTypeEnum.GROUP, "A", "", true, CommonStatusEnum.ENABLE.getStatus());
        insertApp(2L, 1L, NavNodeTypeEnum.ITEM, "P", "", true, CommonStatusEnum.ENABLE.getStatus());

        List<NavNodeTreeRespVO> tree = navNodeService.getNavTree("app", Collections.emptySet());

        assertEquals(1, tree.size());
        assertEquals("P", tree.get(0).getChildren().get(0).getName());
    }

    /** ③ visible=0 → 隐藏；且叶子隐藏后其分组无可见子节点 → 级联隐藏整枝 */
    @Test
    public void testGetNavTree_invisibleCascadeHidesBranch() {
        insertApp(1L, NavNodeDO.ID_ROOT, NavNodeTypeEnum.GROUP, "A", "", true, CommonStatusEnum.ENABLE.getStatus());
        insertApp(2L, 1L, NavNodeTypeEnum.SUB_GROUP, "A1", "", true, CommonStatusEnum.ENABLE.getStatus());
        insertApp(3L, 2L, NavNodeTypeEnum.ITEM, "X", "", false, CommonStatusEnum.ENABLE.getStatus()); // visible=0

        List<NavNodeTreeRespVO> tree = navNodeService.getNavTree("app", Collections.emptySet());

        // X 不可见 → A1 无可见子节点 → A 无可见子节点 → 整棵树空
        assertTrue(tree.isEmpty());
    }

    /** ③补 status 停用 → 整枝不展示（停用的子分组连带其叶子一起消失） */
    @Test
    public void testGetNavTree_disabledStatusCascade() {
        insertApp(1L, NavNodeDO.ID_ROOT, NavNodeTypeEnum.GROUP, "A", "", true, CommonStatusEnum.ENABLE.getStatus());
        insertApp(2L, 1L, NavNodeTypeEnum.SUB_GROUP, "A1", "", true, CommonStatusEnum.DISABLE.getStatus()); // 停用
        insertApp(3L, 2L, NavNodeTypeEnum.ITEM, "X", "", true, CommonStatusEnum.ENABLE.getStatus());

        List<NavNodeTreeRespVO> tree = navNodeService.getNavTree("app", Collections.emptySet());

        assertTrue(tree.isEmpty());
    }

    /** ⑤ terminal 隔离：只返回 app 树，别的端的行不掺进来 */
    @Test
    public void testGetNavTree_terminalIsolation() {
        // app 端：group + 一个可见叶子（分组须有可见子节点才不被级联隐藏）
        insertApp(1L, NavNodeDO.ID_ROOT, NavNodeTypeEnum.GROUP, "AppGroup", "", true, CommonStatusEnum.ENABLE.getStatus());
        insertApp(3L, 1L, NavNodeTypeEnum.ITEM, "AppItem", "", true, CommonStatusEnum.ENABLE.getStatus());
        // 直接经 mapper 塞一棵别的端的树（绕过 service 的 terminal 校验），验证查询按 terminal 过滤
        insert(2L, "web", NavNodeDO.ID_ROOT, NavNodeTypeEnum.GROUP, "WebGroup", "", true, CommonStatusEnum.ENABLE.getStatus());
        insert(4L, "web", 2L, NavNodeTypeEnum.ITEM, "WebItem", "", true, CommonStatusEnum.ENABLE.getStatus());

        List<NavNodeTreeRespVO> tree = navNodeService.getNavTree("app", Collections.emptySet());

        assertEquals(1, tree.size());
        assertEquals("AppGroup", tree.get(0).getName());
        assertEquals(1, tree.get(0).getChildren().size());
        assertEquals("AppItem", tree.get(0).getChildren().get(0).getName());
    }

    /** CRUD：创建后能被 list 查到，且 NOT NULL 列的缺省值被兜住 */
    @Test
    public void testCreateNavNode() {
        NavNodeSaveVO reqVO = new NavNodeSaveVO();
        reqVO.setParentId(NavNodeDO.ID_ROOT);
        reqVO.setNodeType(NavNodeTypeEnum.GROUP.getType());
        reqVO.setName("系统管理");
        reqVO.setSort(1);
        reqVO.setStatus(CommonStatusEnum.ENABLE.getStatus());
        // terminal / visible / isTabbar / onlyPc 均不传，验证 service 兜底默认

        Long id = navNodeService.createNavNode(reqVO);

        assertNotNull(id);
        NavNodeDO saved = navNodeMapper.selectById(id);
        assertEquals("app", saved.getTerminal());
        assertTrue(saved.getVisible());
        assertFalse(saved.getIsTabbar());
        assertFalse(saved.getOnlyPc());
    }

    /** CRUD：有子节点时不允许删除 */
    @Test
    public void testDeleteNavNode_existsChildren() {
        insertApp(1L, NavNodeDO.ID_ROOT, NavNodeTypeEnum.GROUP, "A", "", true, CommonStatusEnum.ENABLE.getStatus());
        insertApp(2L, 1L, NavNodeTypeEnum.ITEM, "X", "", true, CommonStatusEnum.ENABLE.getStatus());

        assertServiceException(() -> navNodeService.deleteNavNode(1L), NAV_NODE_EXISTS_CHILDREN);
    }

}
