package cn.enlev.ainb.module.system.framework.permission.core.subject;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey.ofRole;
import static cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey.ofUser;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SubjectGraph} 的单元测试类
 *
 * 纯逻辑，不起 Spring，也不碰 DB —— 图算法的各种形状在这里钉死，
 * Service 层的测试就只用管「校验有没有被调用、异常对不对」
 *
 * @author 走进新时代
 */
public class SubjectGraphTest {

    private static SubjectGraph.Edge edge(long container, long member) {
        return new SubjectGraph.Edge(ofRole(container), ofRole(member));
    }

    @Test
    public void testAncestorsOf_链式() {
        // 1 ← 2 ← 3：3 是 2 的成员，2 是 1 的成员
        SubjectGraph graph = new SubjectGraph(List.of(edge(1, 2), edge(2, 3)));

        assertEquals(Set.of(ofRole(1L), ofRole(2L)), graph.ancestorsOf(ofRole(3L), SubjectGraph.MAX_DEPTH));
        assertEquals(Set.of(ofRole(1L)), graph.ancestorsOf(ofRole(2L), SubjectGraph.MAX_DEPTH));
        // 根没有祖先，且【不含自身】
        assertTrue(graph.ancestorsOf(ofRole(1L), SubjectGraph.MAX_DEPTH).isEmpty());
        // 图里没有的主体
        assertTrue(graph.ancestorsOf(ofUser(99L), SubjectGraph.MAX_DEPTH).isEmpty());
    }

    @Test
    public void testAncestorsOf_菱形不重复() {
        // 1 ← 2, 1 ← 3, 2 ← 4, 3 ← 4：4 经两条路径都能到 1，1 只能出现一次
        SubjectGraph graph = new SubjectGraph(List.of(edge(1, 2), edge(1, 3), edge(2, 4), edge(3, 4)));

        Set<SubjectKey> ancestors = graph.ancestorsOf(ofRole(4L), SubjectGraph.MAX_DEPTH);
        assertEquals(3, ancestors.size());
        assertEquals(Set.of(ofRole(1L), ofRole(2L), ofRole(3L)), ancestors);
    }

    @Test
    public void testAncestorsOf_按深度截断() {
        // 1 ← 2 ← 3 ← 4
        SubjectGraph graph = new SubjectGraph(List.of(edge(1, 2), edge(2, 3), edge(3, 4)));

        assertEquals(Set.of(ofRole(3L)), graph.ancestorsOf(ofRole(4L), 1));
        assertEquals(Set.of(ofRole(3L), ofRole(2L)), graph.ancestorsOf(ofRole(4L), 2));
        assertEquals(Set.of(ofRole(3L), ofRole(2L), ofRole(1L)), graph.ancestorsOf(ofRole(4L), 3));
        // maxDepth <= 0 时什么都不给
        assertTrue(graph.ancestorsOf(ofRole(4L), 0).isEmpty());
    }

    @Test
    public void testAncestorsOf_有环也要终止() {
        // 1 ← 2 ← 3 ← 1，脏数据（写入校验拦得住，但库可以被直接改）
        SubjectGraph graph = new SubjectGraph(List.of(edge(1, 2), edge(2, 3), edge(3, 1)));

        // 关键是：不栈溢出、不死循环，返回环上的其余节点即可
        Set<SubjectKey> ancestors = graph.ancestorsOf(ofRole(1L), SubjectGraph.MAX_DEPTH);
        assertEquals(Set.of(ofRole(3L), ofRole(2L)), ancestors);
    }

    @Test
    public void testAncestorsOf_超长链在求值时被截断而不是报错() {
        // 6 条边的链，超过上限 5。写入时校验会拒绝，但直接改库能造出来
        List<SubjectGraph.Edge> edges = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            edges.add(edge(i, i + 1));
        }
        SubjectGraph graph = new SubjectGraph(edges);

        // 求值路径在登录链路上，宁可少算也不能抛
        Set<SubjectKey> ancestors = graph.ancestorsOf(ofRole(7L), SubjectGraph.MAX_DEPTH);
        assertEquals(SubjectGraph.MAX_DEPTH, ancestors.size());
        assertFalse(ancestors.contains(ofRole(1L))); // 第 6 跳的那个被截掉了
    }

    @Test
    public void testReachesUpward() {
        SubjectGraph graph = new SubjectGraph(List.of(edge(1, 2), edge(2, 3)));

        assertTrue(graph.reachesUpward(ofRole(3L), ofRole(1L)));
        assertTrue(graph.reachesUpward(ofRole(3L), ofRole(3L))); // 自己到自己
        assertFalse(graph.reachesUpward(ofRole(1L), ofRole(3L))); // 方向是单向的
        assertFalse(graph.reachesUpward(null, ofRole(1L)));
    }

    @Test
    public void testReachesUpward_不受深度上限限制() {
        // 环检测必须能看到超过 MAX_DEPTH 的地方 —— 环可能就藏在那里
        List<SubjectGraph.Edge> edges = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            edges.add(edge(i, i + 1));
        }
        SubjectGraph graph = new SubjectGraph(edges);

        assertTrue(graph.reachesUpward(ofRole(9L), ofRole(1L)));
    }

    @Test
    public void testMaxChainLength() {
        assertEquals(0, new SubjectGraph(List.of()).maxChainLength());
        assertEquals(1, new SubjectGraph(List.of(edge(1, 2))).maxChainLength());
        assertEquals(2, new SubjectGraph(List.of(edge(1, 2), edge(2, 3))).maxChainLength());
        // 边的顺序不影响结果（松弛是按轮跑的，不依赖拓扑序）
        assertEquals(2, new SubjectGraph(List.of(edge(2, 3), edge(1, 2))).maxChainLength());
        // 分叉：取最长的那条
        assertEquals(2, new SubjectGraph(List.of(edge(1, 2), edge(2, 3), edge(1, 4))).maxChainLength());
    }

    @Test
    public void testMaxChainLength_刚好卡在上限() {
        List<SubjectGraph.Edge> edges = new ArrayList<>();
        for (int i = 1; i <= SubjectGraph.MAX_DEPTH; i++) {
            edges.add(edge(i, i + 1));
        }
        // 5 条边的链正好合法，不能被误判成超限
        assertEquals(SubjectGraph.MAX_DEPTH, new SubjectGraph(edges).maxChainLength());

        edges.add(edge(SubjectGraph.MAX_DEPTH + 1, SubjectGraph.MAX_DEPTH + 2));
        assertEquals(SubjectGraph.MAX_DEPTH + 1, new SubjectGraph(edges).maxChainLength());
    }

    @Test
    public void testMaxChainLength_有环时返回超限而不是死循环() {
        SubjectGraph graph = new SubjectGraph(List.of(edge(1, 2), edge(2, 3), edge(3, 1)));

        assertEquals(SubjectGraph.MAX_DEPTH + 1, graph.maxChainLength());
    }

    @Test
    public void testBuildAncestorMap() {
        SubjectGraph graph = new SubjectGraph(List.of(edge(1, 2), edge(2, 3)));

        Map<String, Set<String>> map = graph.buildAncestorMap();
        // 只有「有祖先的」主体才出现，根节点 ROLE:1 不出现
        assertEquals(Set.of("ROLE:2", "ROLE:3"), map.keySet());
        assertEquals(Set.of("ROLE:1"), map.get("ROLE:2"));
        assertEquals(Set.of("ROLE:1", "ROLE:2"), map.get("ROLE:3"));
    }

    @Test
    public void testPlus_不改原图() {
        SubjectGraph graph = new SubjectGraph(List.of(edge(1, 2)));
        SubjectGraph merged = graph.plus(edge(2, 3));

        assertEquals(1, graph.edgeCount());
        assertEquals(2, merged.edgeCount());
        assertEquals(1, graph.maxChainLength());
        assertEquals(2, merged.maxChainLength());
    }

    @Test
    public void testConstructor_脏边被跳过而不是抛异常() {
        List<SubjectGraph.Edge> edges = new ArrayList<>();
        edges.add(edge(1, 2));
        edges.add(new SubjectGraph.Edge(null, ofRole(3L)));
        edges.add(new SubjectGraph.Edge(ofRole(3L), null));
        edges.add(null);

        SubjectGraph graph = new SubjectGraph(edges);
        assertEquals(1, graph.edgeCount());
    }

}
