package cn.enlev.ainb.module.system.framework.permission.core.scan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PermissionTreeDeriver} 的单元测试类
 *
 * @author 走进新时代
 */
public class PermissionTreeDeriverTest {

    private final PermissionTreeDeriver deriver = new PermissionTreeDeriver();

    @Test
    public void testDerive_三段式权限串() {
        List<PermissionTreeDeriver.DerivedNode> nodes = deriver.derive(Set.of("system:user:query"));

        // 应得到三个节点：system（目录）、system:user（目录）、system:user:query（ACTION）
        assertEquals(3, nodes.size());
        assertNode(nodes, "system", PermissionTreeDeriver.DerivedNodeType.MENU, null);
        assertNode(nodes, "system:user", PermissionTreeDeriver.DerivedNodeType.MENU, "system");
        assertNode(nodes, "system:user:query", PermissionTreeDeriver.DerivedNodeType.ACTION, "system:user");
    }

    @Test
    public void testDerive_两段式权限串() {
        List<PermissionTreeDeriver.DerivedNode> nodes = deriver.derive(Set.of("system:dept"));

        assertEquals(2, nodes.size());
        assertNode(nodes, "system", PermissionTreeDeriver.DerivedNodeType.MENU, null);
        assertNode(nodes, "system:dept", PermissionTreeDeriver.DerivedNodeType.ACTION, "system");
    }

    @Test
    public void testDerive_同一父节点共享目录() {
        List<PermissionTreeDeriver.DerivedNode> nodes = deriver.derive(
                Set.of("system:user:query", "system:user:create", "system:dept:query"));

        // system 只应该出现一次，system:user 也只出现一次
        long systemCount = nodes.stream()
                .filter(n -> "system".equals(n.getPermission())).count();
        long systemUserCount = nodes.stream()
                .filter(n -> "system:user".equals(n.getPermission())).count();
        assertEquals(1, systemCount, "目录节点不应重复");
        assertEquals(1, systemUserCount, "目录节点不应重复");
        // 共 5 个节点：system, system:user, system:dept, system:user:query, system:user:create, system:dept:query
        assertEquals(6, nodes.size());
    }

    @Test
    public void testDerive_不同模块() {
        List<PermissionTreeDeriver.DerivedNode> nodes = deriver.derive(
                Set.of("system:user:query", "infra:portal:run"));

        // 两个顶级目录：system 和 infra
        long topDirs = nodes.stream()
                .filter(n -> n.getParentPermission() == null).count();
        assertEquals(2, topDirs);
    }

    @Test
    public void testDerive_空输入() {
        assertTrue(deriver.derive(Set.of()).isEmpty());
        assertTrue(deriver.derive(null).isEmpty());
    }

    @Test
    public void testDerive_ACTION节点的父权限正确() {
        List<PermissionTreeDeriver.DerivedNode> nodes = deriver.derive(Set.of("system:user:export"));

        PermissionTreeDeriver.DerivedNode action = nodes.stream()
                .filter(n -> "system:user:export".equals(n.getPermission()))
                .findFirst().orElseThrow();
        assertEquals("system:user", action.getParentPermission());
        assertEquals(PermissionTreeDeriver.DerivedNodeType.ACTION, action.getType());
        assertEquals("export", action.getName(), "name 应该是最后一段");
    }

    private void assertNode(List<PermissionTreeDeriver.DerivedNode> nodes, String permission,
                             PermissionTreeDeriver.DerivedNodeType type, String parentPermission) {
        PermissionTreeDeriver.DerivedNode node = nodes.stream()
                .filter(n -> permission.equals(n.getPermission()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到节点：" + permission));
        assertEquals(type, node.getType(), permission + " 的类型不对");
        assertEquals(parentPermission, node.getParentPermission(), permission + " 的父节点不对");
    }

}
