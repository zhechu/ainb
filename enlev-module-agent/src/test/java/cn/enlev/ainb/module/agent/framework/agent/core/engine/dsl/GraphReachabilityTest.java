package cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GraphReachability} 单测（重构 S2 / D-new7）
 *
 * <h3>这个测试的主要职责是「迁移等价性」，不是「新功能正确性」</h3>
 * D-new7 把 {@code WorkflowDslValidator} 里两套各写一遍的图遍历收进一个类。搬代码本身很快，
 * <b>成本全在这里</b>：迁移前后对同一批 DSL 的判定必须逐用例一致，否则「收敛重复」就变成了「引入回归」，
 * 而可达性判定的回归是<b>静默</b>的 —— 它不会抛异常，只会让某条校验少报或多报一个错。
 *
 * <p>做法是<b>特征化测试</b>（characterization test）：把被替换掉的两段旧算法<b>逐字抄进本文件</b>
 * 当参照实现（{@link #legacyReachesAny} / {@link #legacyAncestorsOf}），再让新实现与它们在一批
 * 覆盖四种边界的 DSL 上逐一比对。旧算法在 {@code WorkflowDslValidator} 里已经被删掉，
 * 所以这两份副本是<b>刻意的、有期限的</b>：它们的唯一用途就是钉住这次迁移，不参与任何生产判定。
 *
 * <h3>🔴 有两处是<b>故意</b>不等价的，都必须「断言它确实不同」而不是假装相同</h3>
 * <ol>
 *   <li>条件边的 {@code to}：旧正向遍历无条件收它，而编译器从不读
 *       （{@code DslToStateGraphCompiler:372-382}）。这一处是<b>修一个假阴性</b>，
 *       见 {@link #conditionalEdgeWithStrayToIsIgnoredLikeTheCompiler()}。</li>
 *   <li>{@code ancestorsOf(END)}：旧反向遍历建 {@code incoming} 时排除 END，新实现不排除。
 *       这一处不影响任何真实查询（生产从不查 END 的祖先），
 *       见 {@link #endIsNotSpecialCasedInIncomingButProductionNeverQueriesIt()}。</li>
 * </ol>
 * 两条用例都同时断言「新旧确实不同」与「新的那一边为什么是对的」，免得后来人把它当 bug「修」回去。
 *
 * @author 走进新时代
 */
class GraphReachabilityTest {

    private static final String START = "START";
    private static final String END = "END";

    // ==================== 参照实现：逐字抄自被删掉的旧代码 ====================

    /**
     * 旧的正向遍历，逐字抄自 {@code WorkflowDslValidator#reachesAny}（原 L236-271）。
     *
     * <p>🔴 抄的时候<b>不要</b>顺手「修正」它 —— 它的价值恰恰在于它保留了旧的（有缺陷的）口径，
     * 这样比对才能证明「除了那一处已知缺陷，别的都没变」。
     */
    private static boolean legacyReachesAny(WorkflowDefDTO def, Set<String> sources, String target) {
        List<EdgeDTO> edges = def.getEdges();
        if (edges == null) {
            return false;
        }
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (EdgeDTO edge : edges) {
            if (edge == null || edge.getFrom() == null) {
                continue;
            }
            Set<String> targets = adjacency.computeIfAbsent(edge.getFrom(), key -> new HashSet<>());
            if (edge.getTo() != null) {
                targets.add(edge.getTo());
            }
            if (edge.getDefaultTarget() != null) {
                targets.add(edge.getDefaultTarget());
            }
            if (edge.getCases() != null) {
                targets.addAll(edge.getCases().values());
            }
        }
        Deque<String> queue = new ArrayDeque<>(sources);
        Set<String> seen = new HashSet<>(sources);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (target.equals(current)) {
                return true;
            }
            for (String next : adjacency.getOrDefault(current, Set.of())) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    /**
     * 旧的边目标解释，逐字抄自 {@code WorkflowDslValidator#edgeTargets}（原 L412-429）。
     *
     * <p>🔴 {@code defaultTarget} 那支在 {@code cases != null} 的<b>外面</b>——即「只有 default、没有 cases」
     * 的条件边仍然收 default。抄的时候极易顺手把它嵌进去（我第一版就嵌错了），一嵌错
     * {@code defaultOnly} 那张 fixture 的比对就变成了「新实现对、参照实现错」，等价性测试立刻失去意义。
     */
    private static Set<String> legacyEdgeTargets(EdgeDTO e) {
        Set<String> targets = new LinkedHashSet<>();
        if (e.isConditional()) {
            if (e.getCases() != null) {
                for (String v : e.getCases().values()) {
                    if (v != null && !v.isBlank()) {
                        targets.add(v);
                    }
                }
            }
            if (e.getDefaultTarget() != null && !e.getDefaultTarget().isBlank()) {
                targets.add(e.getDefaultTarget());
            }
        } else if (e.getTo() != null && !e.getTo().isBlank()) {
            targets.add(e.getTo());
        }
        return targets;
    }

    /**
     * 旧的反向祖先集，逐字抄自 {@code WorkflowDslValidator#validateReferenceReachability} 里建
     * {@code incoming} 的那段（原 L373-385）+ {@code ancestorsOf}（原 L432-446）。
     *
     * <p>注意它<b>跳过 END</b>（{@code END.equals(to)} 那支）—— 新实现不跳，
     * 类注释里论证了这对任何真实查询都无差别，本文件的比对就是那个论证的证据。
     */
    private static Set<String> legacyAncestorsOf(WorkflowDefDTO def, String startId) {
        Map<String, Set<String>> incoming = new HashMap<>();
        List<EdgeDTO> edges = def.getEdges();
        if (edges != null) {
            for (EdgeDTO e : edges) {
                if (e == null || e.getFrom() == null || e.getFrom().isBlank()) {
                    continue;
                }
                String from = e.getFrom();
                for (String to : legacyEdgeTargets(e)) {
                    if (to == null || to.isBlank() || END.equals(to)) {
                        continue;
                    }
                    incoming.computeIfAbsent(to, k -> new HashSet<>()).add(from);
                }
            }
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.push(startId);
        visited.add(startId);
        while (!queue.isEmpty()) {
            String cur = queue.pop();
            for (String prev : incoming.getOrDefault(cur, Set.of())) {
                if (visited.add(prev)) {
                    queue.push(prev);
                }
            }
        }
        return visited;
    }

    // ==================== DSL 构造小工具 ====================

    private static EdgeDTO edge(String from, String to) {
        EdgeDTO e = new EdgeDTO();
        e.setFrom(from);
        e.setTo(to);
        return e;
    }

    /** 条件边：{@code cases} 为「路由键 → 目标」，{@code defaultTarget} 可为 null。刻意<b>不设</b> {@code to}。 */
    private static EdgeDTO conditional(String from, Map<String, String> cases, String defaultTarget) {
        EdgeDTO e = new EdgeDTO();
        e.setFrom(from);
        e.setType("conditional");
        e.setCases(cases);
        e.setDefaultTarget(defaultTarget);
        return e;
    }

    private static WorkflowDefDTO dsl(EdgeDTO... edges) {
        WorkflowDefDTO def = new WorkflowDefDTO();
        def.setEdges(new ArrayList<>(List.of(edges)));
        return def;
    }

    /** 一份 DSL 里值得查询的全部端点（去重、保持出现顺序） */
    private static Set<String> allEndpoints(WorkflowDefDTO def) {
        Set<String> ids = new LinkedHashSet<>();
        for (EdgeDTO e : def.getEdges()) {
            ids.add(e.getFrom());
            if (e.getTo() != null) {
                ids.add(e.getTo());
            }
            if (e.getDefaultTarget() != null) {
                ids.add(e.getDefaultTarget());
            }
            if (e.getCases() != null) {
                ids.addAll(e.getCases().values());
            }
        }
        return ids;
    }

    /**
     * 覆盖 D-new7 点名的四种边界 + 几种常规形状。
     *
     * <p>键是给人看的名字（断言失败时能直接知道是哪一张图挂了），值是 DSL。
     */
    private static Map<String, WorkflowDefDTO> fixtures() {
        Map<String, WorkflowDefDTO> f = new LinkedHashMap<>();

        // 线性
        f.put("linear", dsl(edge(START, "a"), edge("a", "b"), edge("b", END)));

        // 边界①：条件边 cases（两个分支各自到 END）
        f.put("cases", dsl(
                edge(START, "gate"),
                conditional("gate", mapOf("yes", "approve", "no", "reject"), null),
                edge("approve", END),
                edge("reject", END)));

        // 边界②：defaultTarget（只有 default、没有 cases 也是一种；这里两种都覆盖）
        f.put("defaultOnly", dsl(
                edge(START, "gate"),
                conditional("gate", null, "fallback"),
                edge("fallback", END)));
        f.put("casesAndDefault", dsl(
                edge(START, "gate"),
                conditional("gate", mapOf("yes", "approve"), "reject"),
                edge("approve", END),
                edge("reject", END)));

        // 边界③：环（a↔b），BFS 必须靠 seen 去重而不是靠深度上限
        f.put("cycle", dsl(
                edge(START, "a"), edge("a", "b"), edge("b", "a"), edge("b", END)));

        // 边界③变体：自环
        f.put("selfLoop", dsl(edge(START, "a"), edge("a", "a"), edge("a", END)));

        // 边界④：孤立节点 z（没有任何边）。注意 allEndpoints 收不到它，故单独断言
        f.put("isolated", dsl(edge(START, "a"), edge("a", END)));

        // 菱形（parallel 展开后的形状）：两条分支都汇到 j
        f.put("diamond", dsl(
                edge(START, "p"), edge("p", "q1"), edge("p", "q2"),
                edge("q1", "j"), edge("q2", "j"), edge("j", END)));

        // 条件边套条件边（嵌套分支）
        f.put("nestedCases", dsl(
                edge(START, "g1"),
                conditional("g1", mapOf("a", "g2"), "z"),
                conditional("g2", mapOf("b", "deep"), "z"),
                edge("deep", END),
                edge("z", END)));

        // 只有 START 一条边（最小图）
        f.put("minimal", dsl(edge(START, END)));

        return f;
    }

    private static Map<String, String> mapOf(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ==================== 等价性：良构 DSL 上必须逐用例一致 ====================

    @Test
    @DisplayName("正向可达：新实现与旧 reachesAny 在全部良构 fixture × 全部端点对上一致")
    void forwardReachabilityMatchesLegacyOnWellFormedDsl() {
        int pairs = 0;
        for (Map.Entry<String, WorkflowDefDTO> entry : fixtures().entrySet()) {
            String name = entry.getKey();
            WorkflowDefDTO def = entry.getValue();
            GraphReachability.Graph graph = GraphReachability.of(def);
            Set<String> endpoints = allEndpoints(def);
            for (String source : endpoints) {
                for (String target : endpoints) {
                    boolean expected = legacyReachesAny(def, Set.of(source), target);
                    boolean actual = graph.reaches(source, target);
                    assertEquals(expected, actual,
                            () -> String.format("图[%s] 正向 %s→%s 判定与旧实现不一致", name, source, target));
                    pairs++;
                }
            }
        }
        // 🔴 阳性对照：断言这个循环真的跑了。fixtures 改空、或 allEndpoints 返回空集时，
        // 上面那个 assertEquals 一次都不执行也会「全绿」——那是最坏的一种通过。
        // 下界取「实测值」而非拍脑袋的整数：加 fixture 只会让它更大，删/空 fixture 立刻红。
        assertTrue(pairs >= 192, "端点对比对只跑了 " + pairs + " 次（实测应为 192），fixture 是不是空了？");
    }

    @Test
    @DisplayName("正向可达（多源）：新实现与旧 reachesAny 在多源入参上一致")
    void forwardReachabilityMatchesLegacyWithMultipleSources() {
        WorkflowDefDTO def = fixtures().get("diamond");
        GraphReachability.Graph graph = GraphReachability.of(def);
        Set<String> sources = Set.of("q1", "q2");
        for (String target : allEndpoints(def)) {
            assertEquals(legacyReachesAny(def, sources, target), graph.reachesAny(sources, target),
                    () -> "多源正向可达 " + sources + "→" + target + " 与旧实现不一致");
        }
        // 两个源都到得了 j，但都到不了 p（方向反了）
        assertTrue(graph.reachesAny(sources, "j"));
        assertFalse(graph.reachesAny(sources, "p"));
    }

    @Test
    @DisplayName("反向祖先：新实现与旧 ancestorsOf 在全部良构 fixture × 全部真实节点上一致")
    void ancestorsMatchLegacyOnWellFormedDsl() {
        int nodes = 0;
        for (Map.Entry<String, WorkflowDefDTO> entry : fixtures().entrySet()) {
            String name = entry.getKey();
            WorkflowDefDTO def = entry.getValue();
            GraphReachability.Graph graph = GraphReachability.of(def);
            for (String node : allEndpoints(def)) {
                // 🔴 跳过 END：新旧在 END 上**故意**不同（旧建 incoming 时排除 END，新不排除），
                // 见 endIsNotSpecialCasedInIncomingButProductionNeverQueriesIt。
                // 这里比对的是「生产真会查的那部分」——validateReferenceReachability 的循环体
                // 遍历的是 def.getNodes()，END 是保留端点、不是节点，永远不会被当成 startId 传进去。
                if (END.equals(node)) {
                    continue;
                }
                assertEquals(legacyAncestorsOf(def, node), graph.ancestorsOf(node),
                        () -> String.format("图[%s] 节点 %s 的祖先集与旧实现不一致", name, node));
                nodes++;
            }
        }
        assertTrue(nodes >= 32, "祖先集比对只跑了 " + nodes + " 次（实测应为 32），fixture 是不是空了？");
    }

    @Test
    @DisplayName("🔴 END 的祖先集：新旧故意不同，且这个不同影响不到任何真实判定")
    void endIsNotSpecialCasedInIncomingButProductionNeverQueriesIt() {
        WorkflowDefDTO def = fixtures().get("linear");   // START→a→b→END
        GraphReachability.Graph graph = GraphReachability.of(def);

        // 旧实现建 incoming 时有一支 `END.equals(to) → continue`，于是 incoming 里根本没有 END 这个键，
        // ancestorsOf(END) 只回它自己；新实现不特殊处理，回真实的祖先链。
        assertEquals(Set.of(END), legacyAncestorsOf(def, END));
        assertEquals(Set.of(END, "b", "a", START), graph.ancestorsOf(END));
        assertNotEquals(legacyAncestorsOf(def, END), graph.ancestorsOf(END));

        // 为什么这个不同是安全的：END 没有出边 ⟹ 反向遍历**不可能穿过 END** 到达别处，
        // 所以「incoming 里有没有 END 这个键」只影响 ancestorsOf(END) 自己这一个查询。
        // ⚠️ 断言写的是 {END} 而不是空集：reachableFrom 按设计**含起点自身**（见 ancestorsIncludeSelf），
        // 「END 走不出去」的正确表达是「除了它自己哪儿也到不了」。我第一版写成 isEmpty() 就撞红了。
        assertEquals(Set.of(END), graph.reachableFrom(END), "END 无出边：除了自身到不了任何地方");
        assertTrue(graph.terminalEndpoints().contains(END));
        // 而 ancestorsOf(END) 在生产里没人调：唯一调用点遍历的是节点列表，END 不是节点。
        for (String node : List.of(START, "a", "b")) {
            assertEquals(legacyAncestorsOf(def, node), graph.ancestorsOf(node));
        }
    }

    @Test
    @DisplayName("祖先集含自身：这是必须保持的既有行为（自环/同节点产出并引用依赖它）")
    void ancestorsIncludeSelf() {
        GraphReachability.Graph graph = GraphReachability.of(fixtures().get("linear"));
        assertTrue(graph.ancestorsOf("b").contains("b"), "旧实现 visited.add(startId)，新实现必须一致");
        assertTrue(graph.ancestorsOf("b").containsAll(Set.of("b", "a", START)));
        assertTrue(graph.reachableFrom("a").contains("a"), "正向也必须含自身，与 reaches(x,x)==true 一致");
        assertTrue(graph.reaches("a", "a"));
    }

    // ==================== 🔴 故意不等价的两处 ====================

    @Test
    @DisplayName("🔴 条件边同时带 to（病态 DSL）：新实现忽略 to，与编译器一致；旧实现会多算一条可达")
    void conditionalEdgeWithStrayToIsIgnoredLikeTheCompiler() {
        // gate 是条件边、却又带了 to="approve"。EdgeDTO.to 的 javadoc 明写「条件边时为空」，
        // 但 DSL 是 LLM 产的、且 @JsonIgnoreProperties(ignoreUnknown=true) 不校验这一条，
        // 所以这种形状真的会出现。
        EdgeDTO stray = conditional("gate", mapOf("yes", "reject"), null);
        stray.setTo("approve");
        WorkflowDefDTO def = dsl(edge(START, "gate"), stray, edge("approve", END), edge("reject", END));
        GraphReachability.Graph graph = GraphReachability.of(def);

        // 新口径：条件边只看 cases/default ⟹ gate 到不了 approve
        assertFalse(graph.reaches("gate", "approve"),
                "编译器 DslToStateGraphCompiler:372-382 对条件边从不读 to，可达性判定必须同口径");
        assertTrue(graph.reaches("gate", "reject"));
        assertFalse(graph.ancestorsOf("approve").contains("gate"));

        // 旧口径会认为可达 —— 这一处差异是**修假阴性**，不是回归
        assertTrue(legacyReachesAny(def, Set.of("gate"), "approve"),
                "旧实现确实多算了这条边；若这句失败说明参照实现被改过，比对就失去意义了");
        assertNotEquals(legacyReachesAny(def, Set.of("gate"), "approve"), graph.reaches("gate", "approve"));

        // 危害方向：validateSaveDraftMainTable 用它做 `if (reachesAny(...)) continue;`
        // ⟹ 旧口径在这里会**跳过**主表存在性校验，放过一个真实的错
        assertTrue(graph.reachesAny(Set.of("gate"), "reject"));
    }

    // ==================== 边界与健壮性 ====================

    @Test
    @DisplayName("孤立节点：不在图里，正反查询都只回自己，且不抛")
    void isolatedNodeIsSafe() {
        GraphReachability.Graph graph = GraphReachability.of(fixtures().get("isolated"));
        assertEquals(Set.of("z"), graph.ancestorsOf("z"));
        assertEquals(Set.of("z"), graph.reachableFrom("z"));
        assertFalse(graph.reaches("z", "a"));
        assertFalse(graph.reaches("a", "z"));
        assertFalse(graph.hasAncestor("a", "z"));
    }

    @Test
    @DisplayName("空图 / null：全部查询安全返回，不抛 NPE")
    void emptyAndNullAreSafe() {
        GraphReachability.Graph empty = GraphReachability.of((WorkflowDefDTO) null);
        // ⚠️ 两个方向都是「只有起点自己」而不是空集：reachableFrom / ancestorsOf 按设计都含起点
        //（旧实现也是：queue 与 seen 都先塞 sources、visited 先 add(startId)）。
        // 我第一版把正向写成 Set.of() —— 那是把「图上没有边」误当成「结果里没有起点」。
        assertEquals(Set.of("a"), empty.reachableFrom("a"), "正向含自身，空图上就只有自己");
        assertEquals(Set.of("a"), empty.ancestorsOf("a"), "反向含自身，空图上就只有自己");
        assertFalse(empty.reachesAny(Set.of("a"), "b"));
        assertFalse(empty.reachesAny(null, "b"));
        assertFalse(empty.reachesAny(Set.of(), "b"));
        assertFalse(empty.reaches(null, "b"));
        assertFalse(empty.reaches("a", null));
        assertEquals(Set.of(), empty.reachableFrom((Collection<String>) null));
        assertEquals(Set.of(), empty.endpoints(), "空图上没有任何端点");
        assertEquals(Set.of(), empty.terminalEndpoints());

        WorkflowDefDTO noEdges = new WorkflowDefDTO();
        GraphReachability.Graph g2 = GraphReachability.of(noEdges);
        assertEquals(Set.of("a"), g2.reachableFrom("a"));

        // edges 列表里混 null 元素（LLM 产物里出现过）也不能炸
        WorkflowDefDTO withNull = new WorkflowDefDTO();
        List<EdgeDTO> list = new ArrayList<>();
        list.add(null);
        list.add(edge(START, "a"));
        withNull.setEdges(list);
        assertTrue(GraphReachability.of(withNull).reaches(START, "a"));
    }

    @Test
    @DisplayName("环不会让 BFS 死循环；endpoints/terminalEndpoints 与实际边一致")
    void cycleTerminatesAndEndpointHelpersAreCorrect() {
        WorkflowDefDTO def = fixtures().get("cycle");
        GraphReachability.Graph graph = GraphReachability.of(def);
        // a↔b 成环：从 a 出发能回到 a
        assertTrue(graph.reaches("a", "a"));
        assertTrue(graph.reachableFrom("a").containsAll(Set.of("a", "b", END)));
        assertEquals(Set.of("a", "b", START), graph.ancestorsOf("a"));

        assertTrue(graph.endpoints().containsAll(Set.of(START, "a", "b", END)));
        assertEquals(Set.of(END), graph.terminalEndpoints(), "只有 END 没有出边");
    }

    @Test
    @DisplayName("一张图可反复查询（邻接表只建一次），且正反向对同一条边的理解必然一致")
    void graphIsReusableAndBothDirectionsAgree() {
        WorkflowDefDTO def = fixtures().get("casesAndDefault");
        GraphReachability.Graph graph = GraphReachability.of(def);
        for (int i = 0; i < 3; i++) {
            assertTrue(graph.reaches("gate", "approve"));
            assertTrue(graph.reaches("gate", "reject"), "default 分支也算可达");
        }
        // 正向说 x 能到 y ⟺ 反向说 y 的祖先含 x。这条不变式成立，
        // 正是因为两个方向由同一次遍历、同一份 edgeTargets 建出
        for (String x : allEndpoints(def)) {
            for (String y : allEndpoints(def)) {
                assertEquals(graph.reaches(x, y), graph.ancestorsOf(y).contains(x),
                        () -> "正反向不一致：" + x + "→" + y);
            }
        }
    }

    @Test
    @DisplayName("hasAncestor 供 V16 用：写工具节点的上游有没有人工闸口")
    void hasAncestorSupportsUpstreamGateCheck() {
        WorkflowDefDTO def = dsl(
                edge(START, "ask"),
                edge("ask", "create_table"),
                edge("create_table", END));
        GraphReachability.Graph graph = GraphReachability.of(def);
        assertTrue(graph.hasAncestor("create_table", "ask"));
        assertFalse(graph.hasAncestor("ask", "create_table"));

        // 闸口在**分支的另一条**上 ⟹ 不算上游（这正是 V11/V16 要区分的）
        WorkflowDefDTO bypass = dsl(
                edge(START, "gate"),
                conditional("gate", mapOf("safe", "ask", "unsafe", "create_table"), null),
                edge("ask", "create_table"),
                edge("create_table", END));
        GraphReachability.Graph g2 = GraphReachability.of(bypass);
        assertTrue(g2.hasAncestor("create_table", "ask"), "走 safe 分支时 ask 确实在上游");
        assertTrue(g2.reachableFrom("gate").contains("create_table"),
                "但 unsafe 分支能绕过 ask 直达写工具 —— 这就是 V11 要拦的形状");
    }

    @Test
    @DisplayName("Collection 入参：Set/List 皆可，起点里的 null 与空串被丢掉而不是塞进结果")
    void sourcesAreNormalized() {
        GraphReachability.Graph graph = GraphReachability.of(fixtures().get("linear"));
        Collection<String> withJunk = new ArrayList<>();
        withJunk.add("a");
        withJunk.add(null);
        withJunk.add("");
        Set<String> reached = graph.reachableFrom(withJunk);
        assertTrue(reached.containsAll(Set.of("a", "b", END)));
        assertFalse(reached.contains(""), "空串不是节点，不该出现在结果里");
        assertFalse(reached.contains(null));
    }

    // ==================== sourcesOf：谁<b>直接</b>连到某个端点 ====================

    /**
     * {@code ancestorsOf} 给的是传递闭包，问不出「谁直接连到 END」—— 而那是 V20 的判定起点。
     *
     * <p>🔴 这几条断言里最要紧的是<b>条件边那两条</b>：V20 之所以走 {@code sourcesOf} 而不是自己遍历
     * {@code def.getEdges()}，就是因为条件边的目标由 {@code edgeTargets} 独家解释
     * （cases 的值 + default，<b>不含 to</b>）。自己遍历十有八九会去读 {@code to} ——
     * 而条件边的 {@code to} 是空的，于是「default 直接连 END」这条腿会被<b>整个漏掉</b>，
     * 而那恰恰是 def 416 的驳回腿、也就是 V20 唯一要抓的形状。
     */
    @Test
    @DisplayName("sourcesOf(END)：静态边取 to，条件边取 cases 的值 + default，从不取 to")
    void sourcesOfEndFollowsTheSameEdgeSemantics() {
        Map<String, WorkflowDefDTO> f = fixtures();

        assertEquals(Set.of("b"), GraphReachability.of(f.get("linear")).sourcesOf(END),
                "线性图只有一条腿收在 END 上");

        // cases 与 default 两种写法都要收得到
        assertEquals(Set.of("approve", "reject"),
                GraphReachability.of(f.get("cases")).sourcesOf(END));
        assertEquals(Set.of("fallback"),
                GraphReachability.of(f.get("defaultOnly")).sourcesOf(END));
        assertEquals(Set.of("approve", "reject"),
                GraphReachability.of(f.get("casesAndDefault")).sourcesOf(END),
                "default 直接指向 END 的那条腿必须收得到 —— def 416 的驳回腿就是这个形状");

        // 嵌套条件边：两条腿各收一次
        assertEquals(Set.of("deep", "z"),
                GraphReachability.of(f.get("nestedCases")).sourcesOf(END));

        // 🔴 条件节点自己<b>不是</b> END 的来源，哪怕它的 cases/default 里有 END：
        //    来源是「有边指向 END 的那个端点」，而条件边的目标是它路由到的节点
        WorkflowDefDTO directToEnd = dsl(
                edge(START, "gate"),
                conditional("gate", mapOf("yes", "approve"), END),
                edge("approve", END));
        assertEquals(Set.of("approve", "gate"),
                GraphReachability.of(directToEnd).sourcesOf(END),
                "default 直接写 END 时，gate 自己就是那条腿的来源");
    }

    @Test
    @DisplayName("sourcesOf：条件边带了多余的 to 也不算，与编译器同口径")
    void sourcesOfIgnoresStrayToOnConditionalEdge() {
        EdgeDTO stray = conditional("gate", mapOf("yes", "reject"), null);
        stray.setTo("approve");
        GraphReachability.Graph graph =
                GraphReachability.of(dsl(edge(START, "gate"), stray, edge("approve", END), edge("reject", END)));

        assertTrue(graph.sourcesOf("approve").isEmpty(),
                "编译器 DslToStateGraphCompiler:372-382 对条件边从不读 to，反向邻接必须同口径 —— "
                        + "那个多余的 to=\"approve\" 压根不该建出一条指向 approve 的边");
        assertEquals(Set.of("gate"), graph.sourcesOf("reject"),
                "真正指向 reject 的是 gate 的 cases.yes —— 这一条是上面那个 isEmpty 的阳性对照："
                        + "反向邻接本身是通的，approve 收不到来源纯粹是因为条件边不读 to");
        assertEquals(Set.of("approve", "reject"), graph.sourcesOf(END));
    }

    @Test
    @DisplayName("sourcesOf：START 也能当来源（退化图）；查不到的端点与 null 都返回空集")
    void sourcesOfEdgeCases() {
        GraphReachability.Graph minimal = GraphReachability.of(fixtures().get("minimal"));
        assertEquals(Set.of(START), minimal.sourcesOf(END),
                "START→END 那张退化图上，END 的来源就是 START —— "
                        + "V20 靠「START 不在 nodes[] 里」天然跳过它，不需要额外分支");

        GraphReachability.Graph linear = GraphReachability.of(fixtures().get("linear"));
        assertTrue(linear.sourcesOf("nonexistent").isEmpty(), "没人指向它");
        assertTrue(linear.sourcesOf(START).isEmpty(), "START 没有来源");
        assertTrue(linear.sourcesOf(null).isEmpty(), "null 不抛");
    }

    @Test
    @DisplayName("sourcesOf 返回副本：调用方改不动图内部那个活对象")
    void sourcesOfReturnsACopy() {
        GraphReachability.Graph graph = GraphReachability.of(fixtures().get("linear"));
        Set<String> first = graph.sourcesOf(END);
        first.clear();
        assertEquals(Set.of("b"), graph.sourcesOf(END),
                "内部 incoming 里存的是建图时的 LinkedHashSet，直接交出去等于让调用方能改图");
    }

    // ==================== reachableFromAvoiding：V32 判「支配」用的那一个 ====================

    /**
     * 🔴 这几条断言里最要紧的是<b>「下游节点也没了」</b>那一条。
     *
     * <p>{@code reachableFromAvoiding} 的唯一用途是判支配：摘掉某节点后从 START 还走不走得到目标。
     * 而「摘掉」最省力的错法是<b>拿 {@code reachableFrom} 的结果减掉一个元素</b> ——
     * 那样目标节点仍留在集合里（它是从被摘掉的那个节点走过去收进 seen 的），
     * 于是「支配」永远判不出来，V32 会退化成一条<b>恒沉默</b>的规则：
     * 不报错、不变红、没有任何一处会提示它其实没在工作。
     * 所以这里必须同时钉住两件事：被摘的节点不在结果里，<b>它的下游也不在</b>。
     */
    @Test
    @DisplayName("reachableFromAvoiding：摘掉的节点与它的下游一起消失（不是「可达集减一个元素」）")
    void avoidingRemovesTheNodeAndEverythingOnlyReachableThroughIt() {
        GraphReachability.Graph linear = GraphReachability.of(fixtures().get("linear")); // START→a→b→END

        assertEquals(Set.of(START, "a", "b", END), linear.reachableFrom(START), "阳性对照：不摘时全都到得了");

        Set<String> withoutA = linear.reachableFromAvoiding(START, "a");
        assertEquals(Set.of(START), withoutA,
                "b 与 END 只能<b>穿过</b> a 才走得到，摘掉 a 它们必须一起消失");
        assertFalse(withoutA.contains("a"), "被摘的节点自己当然不在结果里");

        // 🔴 这一句就是「减法错法」的变异探针：两种口径在线性图上必然不同
        Set<String> subtractive = new LinkedHashSet<>(linear.reachableFrom(START));
        subtractive.remove("a");
        assertNotEquals(subtractive, withoutA,
                "若这句红了，说明实现被改成了「先算全量再减一个元素」—— 那样 V32 恒沉默");

        // 摘掉一个<b>不在</b>通往目标路上的节点 ⟹ 目标照样可达（这一档 V32 该闭嘴）
        assertTrue(linear.reachableFromAvoiding(START, "nonexistent").contains("b"));
        Set<String> withoutB = linear.reachableFromAvoiding(START, "b");
        assertTrue(withoutB.containsAll(Set.of(START, "a")), "b 上游的那半不受影响");
        assertFalse(withoutB.contains("b"), "被摘的节点自己不在结果里");
        assertFalse(withoutB.contains(END), "END 只能穿过 b，故一起消失");
    }

    @Test
    @DisplayName("reachableFromAvoiding：菱形图上摘掉一条分支，汇合点仍从另一条走得到 ⟹ 不支配")
    void avoidingOneBranchOfADiamondStillReachesTheJoin() {
        // START→p→{q1,q2}→j→END
        GraphReachability.Graph graph = GraphReachability.of(fixtures().get("diamond"));

        assertTrue(graph.reachableFromAvoiding(START, "q1").contains("j"),
                "q1 只是 j 的<b>祖先</b>、不是必经之路：摘掉它 j 仍从 q2 走得到 —— "
                        + "这正是 V32 不能拿 ancestorsOf 判的全部理由");
        assertFalse(graph.reachableFromAvoiding(START, "p").contains("j"),
                "p 是必经之路：摘掉它 j 就走不到了 ⟹ p 支配 j");
        assertFalse(graph.reachableFromAvoiding(START, "q1").contains("q1"));
    }

    @Test
    @DisplayName("reachableFromAvoiding：摘掉 START 自己得到空集；source/avoided 为空白时退回常规口径")
    void avoidingDegenerateInputsIsSafe() {
        GraphReachability.Graph graph = GraphReachability.of(fixtures().get("linear"));

        assertEquals(Set.of(), graph.reachableFromAvoiding(START, START),
                "连起点都踏不进去 ⟹ 哪儿也到不了，包括起点自己");
        assertEquals(Set.of(), graph.reachableFromAvoiding(null, "a"), "source 为 null 不抛");
        assertEquals(Set.of(), graph.reachableFromAvoiding("", "a"), "source 为空白同样不抛");
        assertEquals(graph.reachableFrom(START), graph.reachableFromAvoiding(START, null),
                "avoided 为 null ⟹ 等价于不摘");
        assertEquals(graph.reachableFrom(START), graph.reachableFromAvoiding(START, "  "),
                "avoided 为空白 ⟹ 等价于不摘（否则会把某个真节点当成「空白」而误摘不到）");
    }

    @Test
    @DisplayName("reachableFromAvoiding：环与条件边都按既有口径处理（不会因为摘节点而死循环或漏边）")
    void avoidingIsCycleSafeAndFollowsConditionalEdgeSemantics() {
        // a↔b 成环：摘掉 b 之后从 a 出发仍能回到 a 自己，且不会死循环
        GraphReachability.Graph cycle = GraphReachability.of(fixtures().get("cycle"));
        assertEquals(Set.of(START, "a"), cycle.reachableFromAvoiding(START, "b"),
                "a 的自足可达性不依赖 b；END 只能穿过 b，故一起消失");

        // 条件边：目标只写在 cases 里（没有 to）也要摘得准
        GraphReachability.Graph cases = GraphReachability.of(fixtures().get("cases"));
        assertFalse(cases.reachableFromAvoiding(START, "gate").contains("approve"),
                "gate 是 approve/reject 的唯一来路，摘掉它两条腿都该消失");
        assertTrue(cases.reachableFromAvoiding(START, "approve").contains("reject"),
                "摘掉一条腿不影响另一条");
    }

}
