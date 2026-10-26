package cn.enlev.ainb.module.system.framework.permission.core.subject;

import java.util.*;

/**
 * 主体成员关系图
 *
 * 纯逻辑，不依赖 Spring 与 DB，便于单测直接构造各种形状的图。
 *
 * ## 方向
 *
 * 一条边是「成员 → 容器」的隶属关系，权限沿这个方向**向下流**：
 * 成员拿到容器的权限。所以求「一个用户属于哪些主体」，是**向上**遍历。
 * 详见 {@link cn.enlev.ainb.module.system.dal.dataobject.permission.SubjectMemberDO}
 * 的类注释 —— 方向写反会静默提权。
 *
 * ## 为什么全部用迭代，不用递归
 *
 * S0 里踩过：递归写法遇到环会自己栈溢出，而栈溢出正是要检测的东西
 * （见 docs/permission-rebuild/S0-交接.md §3.5）。
 * 本类所有遍历都是「队列 + visited」或「按轮松弛」，环只会让它提前终止。
 *
 * @author 走进新时代
 */
public class SubjectGraph {

    /**
     * 继承链的深度上限（边数）
     *
     * 取 5 的依据见 v9 §7.1 与 §11 的风险表。这个数字同时是两处的口径：
     * 写入时拒绝超过它的配置（让配置者立刻看到错误），
     * 求值时截断到它（因为库可以被人直接用 SQL 改，写入校验管不住）
     */
    public static final int MAX_DEPTH = 5;

    /**
     * 一条边
     *
     * @param container 容器主体（权限较基础的一方）
     * @param member    成员主体（继承者）
     */
    public record Edge(SubjectKey container, SubjectKey member) {
    }

    /**
     * 成员 → 它的直接容器们
     */
    private final Map<SubjectKey, Set<SubjectKey>> up = new HashMap<>();
    /**
     * 容器 → 它的直接成员们
     */
    private final Map<SubjectKey, Set<SubjectKey>> down = new HashMap<>();

    private final List<Edge> edges = new ArrayList<>();

    public SubjectGraph(Collection<Edge> edges) {
        if (edges == null) {
            return;
        }
        for (Edge edge : edges) {
            if (edge == null || edge.container() == null || edge.member() == null) {
                continue; // 脏数据跳过，不让一行坏数据毁掉整次求值
            }
            this.edges.add(edge);
            up.computeIfAbsent(edge.member(), k -> new LinkedHashSet<>()).add(edge.container());
            down.computeIfAbsent(edge.container(), k -> new LinkedHashSet<>()).add(edge.member());
        }
    }

    /**
     * 在当前图上加一条边，得到一张新图（不改原图）
     *
     * 校验「加了这条边会不会成环/超深」时用：先造出加完的图再问它
     */
    public SubjectGraph plus(Edge edge) {
        List<Edge> merged = new ArrayList<>(edges);
        merged.add(edge);
        return new SubjectGraph(merged);
    }

    /**
     * 求某个主体的全部祖先容器（不含自身），深度截断到 maxDepth
     *
     * @param maxDepth 最大跳数；脏数据导致超深时按这个截断，不报错 ——
     *                 求值路径在登录链路上，宁可少算也不能抛
     */
    public Set<SubjectKey> ancestorsOf(SubjectKey key, int maxDepth) {
        Set<SubjectKey> result = new LinkedHashSet<>();
        if (key == null || maxDepth <= 0) {
            return result;
        }
        Set<SubjectKey> visited = new HashSet<>();
        visited.add(key);
        Collection<SubjectKey> frontier = List.of(key);
        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            Set<SubjectKey> next = new LinkedHashSet<>();
            for (SubjectKey node : frontier) {
                for (SubjectKey container : up.getOrDefault(node, Set.of())) {
                    if (visited.add(container)) { // visited 同时承担「去重」与「防环」
                        next.add(container);
                        result.add(container);
                    }
                }
            }
            frontier = next;
        }
        return result;
    }

    /**
     * 从 from 出发向上走，能不能走到 to
     *
     * ⚠️ 这里【不设】深度上限：它是环检测用的，
     * 而环可能藏在超过 MAX_DEPTH 的地方（比如有人直接改库造出来的）。
     * visited 保证一定终止
     */
    public boolean reachesUpward(SubjectKey from, SubjectKey to) {
        if (from == null || to == null) {
            return false;
        }
        if (Objects.equals(from, to)) {
            return true;
        }
        Set<SubjectKey> visited = new HashSet<>();
        Deque<SubjectKey> queue = new ArrayDeque<>();
        visited.add(from);
        queue.add(from);
        while (!queue.isEmpty()) {
            SubjectKey node = queue.poll();
            for (SubjectKey container : up.getOrDefault(node, Set.of())) {
                if (Objects.equals(container, to)) {
                    return true;
                }
                if (visited.add(container)) {
                    queue.add(container);
                }
            }
        }
        return false;
    }

    /**
     * 全图最长链的长度（边数）
     *
     * 用「按轮松弛」而不是求最长路径的 DFS：
     * 每轮把所有边过一遍，第 k 轮结束时 depth[n] 已经等于「长度 ≤ k 的最长上行链」。
     * 跑到 MAX_DEPTH + 1 轮还在变，说明要么有超过上限的链、要么有环 ——
     * 两种情况都要拒绝，返回值只需要「大于上限」这个事实。
     *
     * 复杂度 O(MAX_DEPTH × 边数)，边数是千级，代价可以忽略。
     * 好处是完全迭代、对环免疫、没有递归深度风险
     *
     * @return 最长链长度；超过 MAX_DEPTH 时返回 MAX_DEPTH + 1（不再精确区分）
     */
    public int maxChainLength() {
        Map<SubjectKey, Integer> depth = new HashMap<>();
        int max = 0;
        for (int round = 0; round <= MAX_DEPTH; round++) {
            boolean changed = false;
            for (Edge edge : edges) {
                int candidate = depth.getOrDefault(edge.container(), 0) + 1;
                if (candidate > depth.getOrDefault(edge.member(), 0)) {
                    depth.put(edge.member(), candidate);
                    max = Math.max(max, candidate);
                    changed = true;
                }
            }
            if (!changed) {
                return max;
            }
            if (max > MAX_DEPTH) {
                return MAX_DEPTH + 1;
            }
        }
        // 跑满 MAX_DEPTH + 1 轮还在变：有环，或者链长超过上限
        return MAX_DEPTH + 1;
    }

    /**
     * 把整张图的祖先闭包一次性算出来，用于缓存
     *
     * key 与 value 都用寻址串而不是 {@link SubjectKey}：
     * 这个结果要进 Redis，而 record 做 JSON 的 Map key 序列化不友好。
     * 寻址串本来就是对外的唯一表示，直接用它
     *
     * @return 成员寻址串 → 它的全部祖先容器寻址串（不含自身）；没有祖先的主体不出现在结果里
     */
    public Map<String, Set<String>> buildAncestorMap() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (SubjectKey member : up.keySet()) {
            Set<SubjectKey> ancestors = ancestorsOf(member, MAX_DEPTH);
            if (ancestors.isEmpty()) {
                continue;
            }
            Set<String> value = new LinkedHashSet<>(ancestors.size());
            for (SubjectKey ancestor : ancestors) {
                value.add(ancestor.asString());
            }
            result.put(member.asString(), value);
        }
        return result;
    }

    /**
     * 直接成员（不含传递的）
     */
    public Set<SubjectKey> directMembersOf(SubjectKey container) {
        return Collections.unmodifiableSet(down.getOrDefault(container, Set.of()));
    }

    /**
     * 边数，单测与排错用
     */
    public int edgeCount() {
        return edges.size();
    }

}
