package cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DSL 图的可达性遍历 —— 正向与反向的<b>唯一</b>实现（重构 S2 / D-new7）
 *
 * <h3>为什么要有这个类：同一件事此前写了两遍，而且两遍不等价</h3>
 * {@code WorkflowDslValidator} 里曾经各写一套：
 * <ul>
 *   <li>正向 {@code reachesAny}（原 L236-271，内联建 {@code adjacency}）——判「建表节点能否走到 save_draft」；</li>
 *   <li>反向 {@code validateReferenceReachability} + {@code edgeTargets} + {@code ancestorsOf}
 *       （原 L344 / L412 / L432，另建 {@code incoming}）——判「产出变量的节点是不是祖先」。</li>
 * </ul>
 * 两套对「一条条件边通向哪里」的理解<b>不一样</b>，而这不是洁癖问题：V6 / V11 / V13 / V16 / V18
 * 与 {@code ManifestScoper} 六处都要可达性判定，各写一遍就是六遍，且「可达」的语义会在六处各自漂移
 * —— 与 D7「节点 schema 四处副本」同病，而 D7 已经证明这个病会真的发作。
 *
 * <h3>🔴 统一口径 = 运行期口径：条件边<b>不看</b> {@code to}</h3>
 * 权威是编译器：{@code DslToStateGraphCompiler#buildStateGraph} 遇到条件边只走 {@code addConditionalEdge}，
 * <b>从不读</b> {@code edge.getTo()}；{@code DslToStateGraphCompiler#validateEdgeEndpoints} 也同样跳过条件边的 {@code to}。
 * 这与 {@code EdgeDTO#to} 的 javadoc（「条件边时为空，走 cases/default」）一致。
 * <p>原来的正向那套是<b>无条件</b>把 {@code to}、{@code defaultTarget}、{@code cases.values()} 三者全收，
 * 于是在「条件边同时带了 {@code to}」这种病态 DSL 上会<b>多算一条可达</b>。危害方向是确定的：
 * 它唯一的调用点是 {@code validateSaveDraftMainTable} 里的 {@code if (reachesAny(...)) continue;}
 * —— 多算可达 = <b>少报一个 error</b>，而那条校验的存在理由正是一次真实事故
 * （save_draft 绑了不存在的表，运行期一路 SUCCESS，用户发布后点列表才撞 SQL 异常）。
 * <p>⟹ 收编<b>不是</b>行为不变的搬代码，它同时修掉一个假阴性。等价性由
 * {@code GraphReachabilityTest} 两半钉住：良构 DSL 上逐用例一致；病态 DSL 上断言新口径与编译器一致。
 *
 * <h3>两处不改变任何真实判定的收敛</h3>
 * <ol>
 *   <li>{@code END} 不再被特殊排除。原反向遍历建 {@code incoming} 时有一支 {@code to==END → continue}，
 *       于是 {@code incoming} 里根本没有 END 这个键、{@code ancestorsOf(END)} 只回它自己；新实现回真实祖先链。
 *       之所以无碍：END 正常情况下没有出边（有出边是病态 DSL，{@link Graph#terminalEndpoints()} 会暴露它），
 *       反向遍历<b>不可能穿过 END</b>，故 {@code ancestorsOf(X)}（X≠END）逐点不变 ——
 *       而生产里唯一的调用点遍历的是 {@code def.getNodes()}，END 是保留端点、不是节点，永远不会被传进去。</li>
 *   <li>{@code from} 的判空由 {@code != null} 统一为 {@code isBlank}。节点 id 受
 *       {@code ^[a-z][a-z0-9_]{0,62}$} 约束，空串不是合法 id，两者只在病态 DSL 上有别。</li>
 * </ol>
 * <p>这两处连同上面那个「假阴性修复」，在 {@code GraphReachabilityTest} 里都有对应用例：
 * 一致的比对一致、故意不一致的<b>断言它确实不一致</b>并说明为什么安全。
 *
 * <h3>用法：先 {@code of}，再多次查询</h3>
 * 邻接表只在构造时建一次，正反向共用同一份 {@link #edgeTargets} 解释 ——
 * 这是「两个方向不可能对同一条边有不同理解」的结构性保证，而不是靠两处代码写得一像。
 *
 * @author 走进新时代
 */
public final class GraphReachability {

    private GraphReachability() {
    }

    /** 从一份 DSL 建图。{@code def} 为 null 或无 edges 时得到一张空图（所有查询都安全返回）。 */
    public static Graph of(WorkflowDefDTO def) {
        return new Graph(def == null ? null : def.getEdges());
    }

    /** 从裸边列表建图（调用方手上只有 edges 时用，例如已经拿到子图边集的场景）。 */
    public static Graph of(List<EdgeDTO> edges) {
        return new Graph(edges);
    }

    /**
     * 一张只读的图视图。线程安全（构造后不再修改），可在一次校验/一次 manifest 生成里反复查询。
     */
    public static final class Graph {

        /** from → 目标集（含 START/END 这类保留端点，它们就是普通字符串） */
        private final Map<String, Set<String>> outgoing;
        /** to → 来源集。与 {@link #outgoing} 由同一次遍历、同一份 {@link #edgeTargets} 建出 */
        private final Map<String, Set<String>> incoming;

        private Graph(List<EdgeDTO> edges) {
            Map<String, Set<String>> out = new HashMap<>();
            Map<String, Set<String>> in = new HashMap<>();
            if (edges != null) {
                for (EdgeDTO edge : edges) {
                    if (edge == null || StrUtil.isBlank(edge.getFrom())) {
                        continue;
                    }
                    String from = edge.getFrom();
                    for (String to : edgeTargets(edge)) {
                        out.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
                        in.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from);
                    }
                }
            }
            this.outgoing = out;
            this.incoming = in;
        }

        /**
         * 一条边的全部目标端点：普通边 = {to}；条件边 = cases 的值 + defaultTarget（<b>不含 to</b>）。
         *
         * <p>🔴 这是全模块对「条件边通向哪里」的<b>唯一</b>解释，与运行期一致（理由见类注释）。
         * 改动它等于改动所有可达性判定的语义，必须同时改 {@code GraphReachabilityTest}。
         */
        private static Set<String> edgeTargets(EdgeDTO edge) {
            Set<String> targets = new LinkedHashSet<>();
            if (edge.isConditional()) {
                if (edge.getCases() != null) {
                    for (String value : edge.getCases().values()) {
                        if (StrUtil.isNotBlank(value)) {
                            targets.add(value);
                        }
                    }
                }
                if (StrUtil.isNotBlank(edge.getDefaultTarget())) {
                    targets.add(edge.getDefaultTarget());
                }
            } else if (StrUtil.isNotBlank(edge.getTo())) {
                targets.add(edge.getTo());
            }
            return targets;
        }

        /**
         * 从 {@code source} 出发沿正向边能到达的全部节点，<b>含 source 自身</b>（与 {@link #ancestorsOf} 对称）。
         *
         * <p>{@code ManifestScoper} 用它做「从这个 hitl 节点往后」的子图裁剪。
         * 含自身是刻意的：审批人要看的「同意之后会发生什么」包括他正站着的那个节点。
         */
        public Set<String> reachableFrom(String source) {
            return bfs(source == null ? Set.of() : Set.of(source), outgoing);
        }

        /** 多源正向可达集（含全部 source 自身）。空集合入参得到空集合。 */
        public Set<String> reachableFrom(Collection<String> sources) {
            return bfs(sources, outgoing);
        }

        /**
         * 从 {@code source} 出发的正向可达集，但<b>不踏进</b> {@code avoided}（结果里也不含它自身）。
         *
         * <p>🔴 这<b>不是</b> {@link #reachableFrom} 的结果减掉一个元素 —— 那样会得到假答案：
         * {@code avoided} <b>下游</b>的节点仍留在集合里，而它们其实是「只能通过它才走得到」的。
         * 必须在遍历时就不进去，所以 {@link #bfs} 收一个 avoid 参数、由它统一实现两种口径。
         *
         * <p>用途是判<b>支配</b>：摘掉某节点后从入口还走不走得到目标 ——
         * 走不到，就说明每条路都经过它。{@code WorkflowDslValidator} V32 靠它判
         * 「{@code exists} 判的那个键是不是恒被写」，那里要的正是「每条路」而不是「有某条路」
         * （后者是 {@link #ancestorsOf}，会把另一条分支上的写入者也算进来，得到假答案）。
         */
        public Set<String> reachableFromAvoiding(String source, String avoided) {
            if (StrUtil.isBlank(source)) {
                return Collections.emptySet();
            }
            return bfs(Set.of(source), outgoing, avoided);
        }

        /**
         * {@code from} 能否走到 {@code to}。两者相同恒为真（一个节点可达自己）——
         * 与原 {@code reachesAny} 的行为一致：它把 sources 先塞进 seen 再开始 poll。
         */
        public boolean reaches(String from, String to) {
            return from != null && to != null && reachableFrom(from).contains(to);
        }

        /**
         * {@code sources} 里任一节点能否走到 {@code target}。
         *
         * <p>签名与语义都对齐被它替换的 {@code WorkflowDslValidator#reachesAny}，
         * 唯一差别是条件边不再收 {@code to}（见类注释：那是修一个假阴性）。
         */
        public boolean reachesAny(Collection<String> sources, String target) {
            if (target == null) {
                return false;
            }
            return bfs(sources, outgoing).contains(target);
        }

        /**
         * 沿反向边能走到 {@code nodeId} 的全部节点（即它的全部祖先），<b>含自身</b>。
         *
         * <p>含自身是<b>必须保持</b>的既有行为：{@code validateReferenceReachability} 用它判
         * 「产出该变量的节点是否在祖先集里」，自环（同节点既产出又引用）在原实现里算可达。
         */
        public Set<String> ancestorsOf(String nodeId) {
            return bfs(nodeId == null ? Set.of() : Set.of(nodeId), incoming);
        }

        /** {@code nodeId} 是否是 {@code ancestor} 的后代（即 {@code ancestor} 在它的祖先集里）。 */
        public boolean hasAncestor(String nodeId, String ancestor) {
            return ancestor != null && ancestorsOf(nodeId).contains(ancestor);
        }

        /**
         * 直接指向 {@code nodeId} 的来源集（反向邻接的一跳，<b>不含</b>自身；没有则空集）。
         *
         * <p>{@link #ancestorsOf} 给的是<b>传递闭包</b>，问不出「谁<b>直接</b>连到 END」——
         * 而「哪几条腿收在 END 上」正是 {@code WorkflowDslValidator} V20 的判定起点。
         *
         * <p>🔴 走这个方法而<b>不是</b>在调用方自己遍历 {@code def.getEdges()}：
         * 条件边通向哪里由 {@link #edgeTargets} 独家解释（cases 的值 + default，<b>不含 to</b>），
         * 调用方再遍历一遍 edges 就等于开了第二份副本（D7），而那份副本十有八九会去读 {@code to}
         * ——条件边的 {@code to} 是空的，于是「default 直接连 END」这条腿会被整个漏掉。
         *
         * <p>返回<b>副本</b>：内部那个 {@code LinkedHashSet} 是建图时的活对象，直接交出去等于让调用方能改图。
         */
        public Set<String> sourcesOf(String nodeId) {
            if (nodeId == null) {
                return Collections.emptySet();
            }
            return new LinkedHashSet<>(incoming.getOrDefault(nodeId, Collections.emptySet()));
        }

        /**
         * 从一批起点沿给定方向做 BFS。环安全（靠 {@code seen} 去重，不靠深度上限）。
         *
         * <p>起点里 null / 空串一律丢掉：它们不是节点，留着只会在结果集里塞进一个永远匹配不上的键。
         */
        private static Set<String> bfs(Collection<String> sources, Map<String, Set<String>> adjacency) {
            return bfs(sources, adjacency, null);
        }

        /**
         * 同上，外加一个<b>不许踏进</b>的节点（null / 空白 = 不避开任何节点）。
         *
         * <p>🔴 两种口径共用这一份实现，是 {@link #reachableFromAvoiding} 那段注释要求的：
         * 「避开某点」若写成「先全走一遍再从结果里删」，被避开者下游的节点会留在集合里，
         * 支配判定就成了假答案。起点自身等于 avoided 时直接得到空集（一步都不走）。
         */
        private static Set<String> bfs(Collection<String> sources, Map<String, Set<String>> adjacency,
                                       String avoided) {
            if (sources == null || sources.isEmpty()) {
                return Collections.emptySet();
            }
            String blocked = StrUtil.isBlank(avoided) ? null : avoided;
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            for (String source : sources) {
                if (StrUtil.isNotBlank(source) && !source.equals(blocked) && seen.add(source)) {
                    queue.add(source);
                }
            }
            while (!queue.isEmpty()) {
                String current = queue.poll();
                for (String next : adjacency.getOrDefault(current, Set.of())) {
                    if (!next.equals(blocked) && seen.add(next)) {
                        queue.add(next);
                    }
                }
            }
            return seen;
        }

        /** 图里出现过的全部端点（含 START/END），供「孤立节点」这类判定复用，不必再遍历一次 edges。 */
        public Set<String> endpoints() {
            Set<String> all = new LinkedHashSet<>(outgoing.keySet());
            all.addAll(incoming.keySet());
            return all;
        }

        /** 没有任何出边的端点（END 是正常情况；别的节点是「走到这里就断了」）。 */
        public Set<String> terminalEndpoints() {
            Set<String> terminals = new LinkedHashSet<>();
            for (String node : endpoints()) {
                if (outgoing.getOrDefault(node, Set.of()).isEmpty()) {
                    terminals.add(node);
                }
            }
            return terminals;
        }
    }

}
