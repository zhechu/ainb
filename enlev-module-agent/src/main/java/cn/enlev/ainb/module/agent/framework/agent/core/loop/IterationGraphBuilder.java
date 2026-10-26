package cn.enlev.ainb.module.agent.framework.agent.core.loop;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.KeyStrategyFactoryGenerator;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler.VAR_NAME;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_ITERATION_TOO_MANY_ITEMS;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/**
 * iteration（foreach）节点 → SAA <b>环形子图</b> 的合成器（P2 增量3-iteration 结构核心，DSL §4.3 L118、§6 L237）
 *
 * <h3>与 agent_loop 的关系：同一种环形子图，不同的循环体与收敛条件</h3>
 * 增量3 的 {@link AgentLoopGraphBuilder} 已实测「SAA 环形子图能编译、能按自身条件出口干净终止、写回共享 state、
 * 子节点事件以 {@code <id>-<internal>} 逐轮上浮父 SSE、每轮独立 trace 行」。iteration 复用<b>同一机制</b>，
 * 只是把循环体从「合成的 reason/act」换成「真实 DSL body 节点链」，收敛条件从「step≥maxSteps」换成
 * 「index≥items.size」。agent_loop 是<b>限步数自主</b>循环（模型决定何时停），iteration 是<b>确定次数</b>的
 * foreach（遍历一个列表，每项跑一遍 body 子链）。
 *
 * <h3>合成子图结构（内部控制节点 id：{@code _guard} / {@code _advance}；body 节点用其真实 id；
 * trace 命名空间前缀 {@code <iterId>-}）</h3>
 * <pre>
 * START ─▶ _guard ─(index&lt;size：写 itemVar=items[index], route=body)─▶ body[0]─▶…─▶body[n-1] ─▶ _advance ─┐
 *            │                                                                                              │
 *            └─(index≥size：route=end)─▶ END                                                 (index++) ─────┘
 * </pre>
 *
 * <h3>🔴 内部控制节点 id 用<b>单下划线</b>前缀（{@code _guard}/{@code _advance}），不能用 {@code __}</h3>
 * SAA {@code Node.validate} 拒绝任何以 {@code __} 开头的节点 id（仅 {@code __START__}/{@code __END__} 常量例外，
 * 错误消息还是误导性的固定串 "END is not a valid node id!"，S1-iteration spike 实测踩到）。单下划线既合法
 * （非 {@code __}），又与 DSL 节点 id（{@code ^[a-z][a-z0-9_]{0,62}$}，首字符必为小写字母）<b>天然不冲突</b>——
 * body 节点永远不可能叫 {@code _guard}。注意这是<b>节点 id</b> 的限制；<b>state 键</b>无此限制，故内部计数/路由键
 * 仍用 {@code __iter_<id>_*} 前缀（与 agent_loop 的 {@code __agentloop_*} 键同理，spike 已证可行）。
 *
 * <h3>🔴 累积靠 append-merge 变量，iteration 自身无 outputVar（S1-iteration spike 已证）</h3>
 * body 节点把每项结果写进一个在顶层 {@code variables} 声明 {@code merge:append} 的数组变量，
 * 子图 factory 直接用 {@link KeyStrategyFactoryGenerator#generate(WorkflowDefDTO)}（与父图同一套 merge 策略），
 * SAA {@link com.alibaba.cloud.ai.graph.KeyStrategy#APPEND} 跨轮累积。spike 实测：3 项 foreach 终止时
 * append 变量 == [a,b,c]（跨 3 轮累积、不重不漏、父图可见），<b>非</b>被 REPLACE 覆盖成 [c]、<b>非</b>双份。
 * 故无需 agent_loop 那套「手动 read+append+write-full」回退。
 *
 * <h3>🔴 循环上界 = {@code settings.maxIterations}，guard 运行期 fail-close</h3>
 * foreach 的迭代次数 = 列表长度（编译期未知），唯一风险是<b>超大列表</b>打爆 recursionLimit/资源。
 * guard 若发现 {@code items.size() > maxIterations} → 抛 {@code WORKFLOW_ITERATION_TOO_MANY_ITEMS}（fail-close），
 * <b>而非静默截断</b>只跑前 N 项（那正是平台头号缺陷「配了不生效且无提示」的翻版）。与 agent_loop
 * 「绝不依赖 recursionLimit 收敛」同纪律：SAA 命中 recursionLimit 是静默截断而非抛错。
 *
 * <h3>MVP 边界（编译期 fail-fast，见 {@code DslToStateGraphCompiler}）</h3>
 * <ul>
 *   <li>{@code concurrency>1} 拒绝（SAA 无原生动态 fan-out，运行时长度的并发 foreach 留后续增量）。</li>
 *   <li>body 仅允许「plain」节点（llm/tool/knowledge_retrieval 线性链）；拒绝 body 内出现
 *       condition.* 、parallel、iteration、sub_workflow、agent_loop、hitl.* 、wait（子图内嵌条件边/嵌套子图/中断恢复留后续）。</li>
 *   <li>body 节点写在顶层 nodes[] 但<b>从父图排除</b>、<b>不得出现在 edges[]</b>（其连边由本子图内部生成）。</li>
 * </ul>
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class IterationGraphBuilder {

    /** foreach 单节点默认最大迭代次数（settings.maxIterations 缺省时用），防超大列表打爆 recursionLimit/资源 */
    public static final int DEFAULT_MAX_ITERATIONS = 100;

    /** 内部控制节点：守卫（判 index<size、写 itemVar、定路由）。单下划线前缀，见类 javadoc */
    private static final String GUARD_NODE = "_guard";
    /** 内部控制节点：推进（index++、回 guard）。单下划线前缀 */
    private static final String ADVANCE_NODE = "_advance";

    /** guard 的路由：还有下一项，进 body 链 */
    private static final String ROUTE_BODY = "body";
    /** guard 的路由：遍历完毕，走 END */
    private static final String ROUTE_END = "end";

    /**
     * 合成一个 iteration 节点的环形子图（<b>不编译</b>，交由父图 compile 时用父 config 编译）
     *
     * @param iterNode    iteration 节点 DSL（读 config：itemsVar/itemVar/concurrency）
     * @param bodyNodes   body 节点链（<b>有序</b>，编译器已按 config.body 解析并校验为 plain 节点）
     * @param bodyActions body 节点 id → 编译器用 {@code wrap()} 预包好的 SAA action（已含 trace 三段包，
     *                    trace 命名空间前缀 {@code <iterId>-}）；本方法<b>只接线不重复包 trace</b>
     * @param def         顶层 DSL（供 {@link KeyStrategyFactoryGenerator#generate} 产出子图 factory，
     *                    使 append 变量在子图内也 APPEND）
     * @return 未编译的环形 {@link StateGraph}
     */
    public StateGraph build(NodeDTO iterNode, List<NodeDTO> bodyNodes,
                            Map<String, AsyncNodeAction> bodyActions, WorkflowDefDTO def)
            throws GraphStateException {
        String iterId = iterNode.getId();

        // ---- 编译期 config 校验（body 的结构性校验——存在/类型/不在 edges——由编译器在解析 bodyNodes 前完成）----
        String itemsVar = iterNode.configStr("itemsVar");
        if (StrUtil.isBlank(itemsVar)) {
            throw exception(WORKFLOW_DSL_INVALID, "iteration 节点(" + iterId + ") 缺少 itemsVar（待遍历的数组变量名）");
        }
        String itemVar = iterNode.configStr("itemVar");
        if (StrUtil.isBlank(itemVar)) {
            throw exception(WORKFLOW_DSL_INVALID, "iteration 节点(" + iterId + ") 缺少 itemVar（每项写入的变量名）");
        }
        // 🔴 E5（台账 #103）：itemVar 会被<b>原样</b>当键写进 state（下面 guard 的 delta.put(itemVar, …)），
        //    而 configStr 不 trim（String.valueOf 原样返回）⟹ 填 " row " 时运行期写的键就是 " row "，
        //    循环体的 {{$.row}} 永远取不到值、静默拿到空。占位符正则 \{\{\s*([^}]+?)\s*}} 吃掉的是<b>读取侧</b>
        //    的空白，救不了<b>写入侧</b>的键名 —— 这正是平台头号缺陷「配了不生效且无提示」的形状，故编译期拒绝。
        //    判据用 DslToStateGraphCompiler.VAR_NAME（全仓「什么名字可以当 state 键」的唯一权威，
        //    sub_workflow 的 inputMap/outputMap 早就走它），不在这里另抄一份正则。
        //    ⚠️ 这里刻意<b>拒绝而不 trim</b>：trim 只会把 " row " 悄悄改写成 row 然后照跑，
        //       作者以为配的是 " row "、实际生效的是 row，两处口径分叉且无人被告知（与 C6 同一条纪律）。
        if (!VAR_NAME.matcher(itemVar).matches()) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "iteration 节点(" + iterId + ") 的 itemVar(" + itemVar
                            + ") 不是合法变量名（须字母开头，仅含字母/数字/下划线，" + VAR_NAME.pattern()
                            + "）；它会被原样当作 state 的键写入，循环体需用 {{$." + itemVar + "}} 取当前项，"
                            + "含空格或其它字符时那个引用永远取不到值");
        }
        int concurrency = iterNode.configInt("concurrency", 1);
        if (concurrency > 1) {
            // 🔴 SAA 墙（增量16D 已核实、诚实 fail-fast，不伪装支持）：并发 foreach 需「运行时按列表长度动态 fan-out」
            //    ——建图期未知项数，SAA 的并行分支是<b>编译期静态</b>的（parallel 节点在建图时就得把 N 条分支画死），
            //    没有「按 state 里的列表长度动态裂变 N 个并发子任务再收敛」的原生算子。硬凑会破坏 parallel 的「单一
            //    target 收敛」契约（见 DslToStateGraphCompiler.expandParallel 注释）。解锁前置：SAA 提供动态 fan-out
            //    算子，或本引擎改成「预扫描列表→按最大项数建静态并行骨架→越界项空跑」。故编译期明确拒，不静默降级成串行。
            throw exception(WORKFLOW_DSL_INVALID,
                    "iteration 节点(" + iterId + ") 暂只支持 concurrency=1（串行 foreach）；"
                            + "并发 foreach 需运行时按列表长度动态 fan-out，SAA 并行分支是编译期静态的、无此原生算子"
                            + "（需 SAA 提供动态 fan-out 能力后解锁）。收到：" + concurrency);
        }
        if (bodyNodes == null || bodyNodes.isEmpty()) {
            throw exception(WORKFLOW_DSL_INVALID, "iteration 节点(" + iterId + ") 的 body 为空（至少一个节点）");
        }

        int maxIterations = resolveMaxIterations(def);

        // 子图 factory 直接复用父 def 的变量策略：append 变量在子图内也 APPEND（spike 已证跨轮累积成立），
        // 内部键 __iter_*_index/route 与 itemVar 未声明 → 落 defaultStrategy REPLACE 兜底
        KeyStrategyFactory factory = KeyStrategyFactoryGenerator.generate(def);
        StateGraph graph = new StateGraph("iteration_" + iterId, factory);

        String keyPrefix = "__iter_" + iterId + "_";
        String kIndex = keyPrefix + "index";
        String kRoute = keyPrefix + "route";

        // ---- _guard：读 index + items，判出口 / 写当前项 ----
        graph.addNode(GUARD_NODE, AsyncNodeAction.node_async(state -> {
            int index = state.value(kIndex).orElse(null) instanceof Number n ? n.intValue() : 0;
            Object rawItems = state.value(itemsVar).orElse(null);
            List<?> items;
            if (rawItems == null) {
                // 缺席 = 空列表（0 迭代直接 END）；warn 留痕，避免「itemsVar 拼错→静默跑 0 次」无从排查
                items = List.of();
                if (index == 0) {
                    log.warn("[iteration][节点({}) 的 itemsVar({}) 在 state 中缺席，按空列表处理（0 次迭代）]",
                            iterId, itemsVar);
                }
            } else if (rawItems instanceof List<?> l) {
                items = l;
            } else {
                throw exception(WORKFLOW_DSL_INVALID,
                        "iteration 节点(" + iterId + ") 的 itemsVar(" + itemsVar + ") 解析出的值不是数组，无法遍历，实际类型："
                                + rawItems.getClass().getName());
            }
            // 🔴 安全上界 fail-close：超长列表直接抛错，绝不静默截断只跑前 maxIterations 项
            if (items.size() > maxIterations) {
                throw exception(WORKFLOW_ITERATION_TOO_MANY_ITEMS, iterId, items.size(), maxIterations);
            }
            Map<String, Object> delta = new HashMap<>();
            if (index < items.size()) {
                delta.put(kIndex, index);
                delta.put(itemVar, items.get(index)); // 项可能为 null，故用 HashMap 而非 Map.of
                delta.put(kRoute, ROUTE_BODY);
            } else {
                delta.put(kRoute, ROUTE_END);
            }
            return delta;
        }));

        // ---- body 链：编译器预包的 action，逐个 addNode（保留 config.body 的顺序）----
        for (NodeDTO bodyNode : bodyNodes) {
            AsyncNodeAction action = bodyActions.get(bodyNode.getId());
            if (action == null) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "iteration 节点(" + iterId + ") 的 body 节点(" + bodyNode.getId() + ") 缺少预包 action（编译器内部错误）");
            }
            graph.addNode(bodyNode.getId(), action);
        }

        // ---- _advance：index++，回 _guard（回边）----
        graph.addNode(ADVANCE_NODE, AsyncNodeAction.node_async(state -> {
            int index = state.value(kIndex).orElse(null) instanceof Number n ? n.intValue() : 0;
            Map<String, Object> delta = new HashMap<>();
            delta.put(kIndex, index + 1);
            return delta;
        }));

        // ---- 接线 ----
        graph.addEdge(StateGraph.START, GUARD_NODE);
        // guard 条件出口：body→body 链首节点、end→END；缺失/非法路由一律落 end（宁可安全终止，不退回 guard 造成失控环）
        String firstBodyId = bodyNodes.get(0).getId();
        graph.addConditionalEdges(GUARD_NODE,
                edge_async(state -> {
                    Object route = state.value(kRoute).orElse(ROUTE_END);
                    String key = String.valueOf(route);
                    return ROUTE_BODY.equals(key) ? ROUTE_BODY : ROUTE_END;
                }),
                Map.of(ROUTE_BODY, firstBodyId, ROUTE_END, StateGraph.END));
        // body 链：body[0]→body[1]→…→body[n-1]
        for (int i = 0; i < bodyNodes.size() - 1; i++) {
            graph.addEdge(bodyNodes.get(i).getId(), bodyNodes.get(i + 1).getId());
        }
        // body 链尾 → advance → 回 guard（成环）
        graph.addEdge(bodyNodes.get(bodyNodes.size() - 1).getId(), ADVANCE_NODE);
        graph.addEdge(ADVANCE_NODE, GUARD_NODE);

        log.info("[build][iteration 节点({}) 合成环形子图：itemsVar={}、itemVar={}、body={}、maxIterations={}]",
                iterId, itemsVar, itemVar,
                bodyNodes.stream().map(NodeDTO::getId).toList(), maxIterations);
        return graph;
    }

    /**
     * 解析 foreach 安全上界：{@code settings.maxIterations} 缺省 / 非法（&lt;1）时回落 {@link #DEFAULT_MAX_ITERATIONS}。
     *
     * <p>编译器 {@code computeRecursionLimit} 也用本方法，保证「guard fail-close 的上界」与「recursionLimit 预算」
     * 同源，不会出现「guard 放行但 recursionLimit 不够被静默截断」的错配。
     */
    public static int resolveMaxIterations(WorkflowDefDTO def) {
        Integer configured = def == null || def.getSettings() == null ? null : def.getSettings().getMaxIterations();
        if (configured == null || configured < 1) {
            return DEFAULT_MAX_ITERATIONS;
        }
        return configured;
    }

}
