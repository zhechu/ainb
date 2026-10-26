package cn.enlev.ainb.module.agent.framework.agent.core.loop;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.SettingsDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.VariableDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_ITERATION_TOO_MANY_ITEMS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IterationGraphBuilder} 的<b>运行时行为</b>单测（P2 增量3-iteration）
 *
 * <p>S1-iteration spike 已在纯计数器层面证明「APPEND 变量能在环形子图里跨轮累积、且父图可见」；
 * 本测试把<b>真实 body 节点链</b>接上（body action 用桩 lambda 模拟「读 itemVar → 写 append 变量」，
 * 等价于真 tool/llm 执行器的 delta），单独编译并跑通合成的 foreach 环形子图，断言 iteration 的五条核心性质：
 * <ol>
 *   <li>① 3 项 foreach：append 变量累积成 3 项、itemVar 逐项正确、body 恰执行 3 次；</li>
 *   <li>② 空列表：0 次迭代、body 从不执行、子图直接 END（合法，不报错）；</li>
 *   <li>③ 超长 fail-close：列表项数 &gt; maxIterations → 抛 {@code WORKFLOW_ITERATION_TOO_MANY_ITEMS}
 *       （<b>而非静默截断</b>只跑前 N 项）；</li>
 *   <li>④ 多节点 body 链：每轮按序跑 b1→b2，两个 append 变量各自累积正确、执行次序正确；</li>
 *   <li>⑤ <b>建图期</b>（非运行期）：itemVar 必须是合法变量名，否则拒绝 —— E5 / 台账 #103。</li>
 * </ol>
 *
 * <p>纯内存：{@code MemorySaver} + 桩 body action，不碰 DB、不碰模型、不依赖 Spring 上下文
 * （{@link IterationGraphBuilder} 无任何注入依赖，{@code new} 出来即用）。
 *
 * @author 走进新时代
 */
class IterationGraphBuilderTest {

    private IterationGraphBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new IterationGraphBuilder();
    }

    // ==================== 夹具 ====================

    /** 造一份 def：把指定键声明为 append 变量（供子图 factory 注册 APPEND），可选设 maxIterations */
    private static WorkflowDefDTO defWithAppendVars(Integer maxIterations, String... appendKeys) {
        WorkflowDefDTO def = new WorkflowDefDTO();
        def.setId("wf_iter_test");
        List<VariableDTO> vars = new ArrayList<>();
        // 待遍历列表用 replace（每项覆盖，guard 只读它一次取 size/get）
        VariableDTO items = new VariableDTO();
        items.setKey("tables");
        items.setType("array");
        items.setMerge("replace");
        vars.add(items);
        for (String key : appendKeys) {
            VariableDTO v = new VariableDTO();
            v.setKey(key);
            v.setType("array");
            v.setMerge("append");
            vars.add(v);
        }
        def.setVariables(vars);
        if (maxIterations != null) {
            SettingsDTO s = new SettingsDTO();
            s.setMaxIterations(maxIterations);
            def.setSettings(s);
        }
        return def;
    }

    private static NodeDTO iterNode(String itemsVar, String itemVar, List<String> body, Integer concurrency) {
        NodeDTO n = new NodeDTO();
        n.setId("iter");
        n.setType("iteration");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("itemsVar", itemsVar);
        cfg.put("itemVar", itemVar);
        cfg.put("body", body);
        if (concurrency != null) {
            cfg.put("concurrency", concurrency);
        }
        n.setConfig(cfg);
        return n;
    }

    private static NodeDTO bodyNode(String id) {
        NodeDTO n = new NodeDTO();
        n.setId(id);
        n.setType("tool");
        n.setConfig(Map.of());
        return n;
    }

    /** 桩 body action：读 itemVar，把 {@code prefix+item} 写进 append 变量 accVar（模拟真执行器的 delta） */
    private static AsyncNodeAction appendAction(String itemVar, String accVar, String prefix,
                                                AtomicInteger counter, List<String> orderLog, String tag) {
        return AsyncNodeAction.node_async(state -> {
            if (counter != null) {
                counter.incrementAndGet();
            }
            if (orderLog != null) {
                orderLog.add(tag);
            }
            Object item = state.value(itemVar).orElse(null);
            Map<String, Object> delta = new HashMap<>();
            delta.put(accVar, prefix + item);
            return delta;
        });
    }

    /** 合成环形子图 → 单独编译（MemorySaver）→ 用 input 播种 items 跑通 → 返回终止时的共享 state */
    private Map<String, Object> run(NodeDTO iterNode, List<NodeDTO> bodyNodes,
                                    Map<String, AsyncNodeAction> bodyActions, WorkflowDefDTO def,
                                    Map<String, Object> input) throws Exception {
        StateGraph sg = builder.build(iterNode, bodyNodes, bodyActions, def);
        CompiledGraph cg = sg.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .recursionLimit(200)
                .build());
        List<NodeOutput> outs = cg.stream(input, RunnableConfig.builder().threadId("t1:1").build())
                .collectList().block();
        assertNotNull(outs, "子图流应正常完成");
        return outs.get(outs.size() - 1).state().data();
    }

    // ==================== ① 3 项 foreach：append 累积 + itemVar 逐项 + body 执行 3 次 ====================

    @Test
    void iteration_threeItems_accumulatesAppendVarAndRunsBodyThreeTimes() throws Exception {
        WorkflowDefDTO def = defWithAppendVars(null, "acc");
        NodeDTO describe = bodyNode("describe");
        AtomicInteger runs = new AtomicInteger(0);
        Map<String, AsyncNodeAction> actions = new LinkedHashMap<>();
        actions.put("describe", appendAction("row", "acc", "struct:", runs, null, null));

        Map<String, Object> state = run(
                iterNode("tables", "row", List.of("describe"), 1),
                List.of(describe), actions, def,
                Map.of("tables", List.of("a", "b", "c")));

        assertEquals(3, runs.get(), "body 应恰执行 3 次（列表 3 项）");
        assertEquals(List.of("struct:a", "struct:b", "struct:c"), state.get("acc"),
                "🔴 append 变量应跨 3 轮累积成 3 项（不重不漏、非被覆盖成末项）");
        assertEquals("c", state.get("row"), "终止时 itemVar 应为最后一项");
    }

    // ==================== ② 空列表：0 次迭代、body 从不执行、直接 END ====================

    @Test
    void iteration_emptyList_zeroIterationsBodyNeverRuns() throws Exception {
        WorkflowDefDTO def = defWithAppendVars(null, "acc");
        NodeDTO describe = bodyNode("describe");
        AtomicInteger runs = new AtomicInteger(0);
        Map<String, AsyncNodeAction> actions = new LinkedHashMap<>();
        actions.put("describe", appendAction("row", "acc", "struct:", runs, null, null));

        Map<String, Object> state = run(
                iterNode("tables", "row", List.of("describe"), 1),
                List.of(describe), actions, def,
                Map.of("tables", List.of()));

        assertEquals(0, runs.get(), "空列表应 0 次迭代，body 从不执行");
        assertNull(state.get("acc"), "空列表下 append 变量从未被写入，应缺席（null）");
    }

    // ==================== ③ 超长 fail-close：项数 > maxIterations → 抛错，不静默截断 ====================

    @Test
    void iteration_tooManyItems_failCloseThrows() {
        WorkflowDefDTO def = defWithAppendVars(2, "acc"); // maxIterations=2
        NodeDTO describe = bodyNode("describe");
        AtomicInteger runs = new AtomicInteger(0);
        Map<String, AsyncNodeAction> actions = new LinkedHashMap<>();
        actions.put("describe", appendAction("row", "acc", "struct:", runs, null, null));

        // 列表 3 项 > maxIterations 2 → guard 首轮即 fail-close
        Throwable t = assertThrows(Throwable.class, () -> run(
                iterNode("tables", "row", List.of("describe"), 1),
                List.of(describe), actions, def,
                Map.of("tables", List.of("a", "b", "c"))),
                "列表超长应主动抛错（而非静默截断只跑前 maxIterations 项）");
        assertTrue(hasCode(t, WORKFLOW_ITERATION_TOO_MANY_ITEMS.getCode()),
                "应抛 WORKFLOW_ITERATION_TOO_MANY_ITEMS，实际异常链：" + chain(t));
        assertEquals(0, runs.get(), "fail-close 发生在 guard 首轮，body 不应执行任何一次");
    }

    // ==================== ④ 多节点 body 链：每轮按序 b1→b2，两个 append 变量各自累积 ====================

    @Test
    void iteration_multiNodeBodyChain_runsInOrderAndAccumulates() throws Exception {
        WorkflowDefDTO def = defWithAppendVars(null, "acc1", "acc2");
        NodeDTO b1 = bodyNode("b1");
        NodeDTO b2 = bodyNode("b2");
        List<String> order = new ArrayList<>();
        Map<String, AsyncNodeAction> actions = new LinkedHashMap<>();
        actions.put("b1", appendAction("row", "acc1", "b1:", null, order, "b1"));
        actions.put("b2", appendAction("row", "acc2", "b2:", null, order, "b2"));

        Map<String, Object> state = run(
                iterNode("tables", "row", List.of("b1", "b2"), 1),
                List.of(b1, b2), actions, def,
                Map.of("tables", List.of("x", "y")));

        assertEquals(List.of("b1", "b2", "b1", "b2"), order,
                "每轮应按 body 链顺序跑 b1→b2，共 2 轮");
        assertEquals(List.of("b1:x", "b1:y"), state.get("acc1"), "acc1 应累积 b1 的两轮输出");
        assertEquals(List.of("b2:x", "b2:y"), state.get("acc2"), "acc2 应累积 b2 的两轮输出");
    }

    // ==================== ⑤ 建图期：itemVar 必须是合法变量名（E5 / 台账 #103） ====================

    /**
     * 🔴 E5（台账 #103）：itemVar 会被<b>原样</b>当 state 的键 {@code put} 进去（{@code configStr} 不 trim），
     * 所以非法名字必须在<b>建图期</b>就拒，而不是留到运行期变成一个「配了不生效且无提示」的哑键。
     *
     * <p>四个样本各钉一类不同的病因，少一个就留一个可乘之机：
     * <ul>
     *   <li>{@code " row "} —— 首尾空格：运行期写的键是 {@code " row "}，而占位符正则
     *       {@code \{\{\s*([^}]+?)\s*}} 吃掉的是<b>读取侧</b>的空白 ⟹ 循环体的 {@code {{$.row}}}
     *       永远取不到值、静默拿到空。这正是 {@code UntrustedInputTracerTest} 里
     *       {@code itemVarWithSurroundingSpacesOverReportsByDesign} 过去只能「多报」的那个形状，
     *       如今它在建图期就没了 ⟹ 那条污点测试报的边从此指向一个建不起来的图（它的注释已同步）。</li>
     *   <li>{@code "__iter_iter_route"} —— 撞内部键：本子图自己用 {@code __iter_<id>_index/route} 记状态，
     *       若 itemVar 也叫这个名，guard 每轮写的路由值就会和当前项互相覆盖 ⟹ 可能失控或死循环。
     *       VAR_NAME 的「字母开头」一条即足以永远排除 {@code __} 前缀（这正是它 javadoc 里写的理由）。</li>
     *   <li>{@code "1row"} —— 数字开头：不是合法标识符，{@code {{$.1row}}} 也解析不出来。</li>
     *   <li>{@code "row-item"} —— 含连字符：同上。</li>
     * </ul>
     *
     * <p>⚠️ 判据用的是 {@code DslToStateGraphCompiler.VAR_NAME}（全仓「什么名字可以当 state 键」的唯一权威，
     * sub_workflow 的 {@code inputMap}/{@code outputMap} 早就走它），<b>不是</b>这里另抄的一份正则。
     */
    @Test
    void iteration_itemVarNotLegalVarName_rejected() {
        for (String bad : List.of(" row ", "__iter_iter_route", "1row", "row-item")) {
            WorkflowDefDTO def = defWithAppendVars(null, "acc");
            NodeDTO describe = bodyNode("describe");
            AtomicInteger runs = new AtomicInteger(0);
            Map<String, AsyncNodeAction> actions = new LinkedHashMap<>();
            actions.put("describe", appendAction("row", "acc", "struct:", runs, null, null));

            Throwable t = assertThrows(Throwable.class, () -> builder.build(
                            iterNode("tables", bad, List.of("describe"), 1),
                            List.of(describe), actions, def),
                    "itemVar=[" + bad + "] 应在建图期就被拒，而不是留到运行期变成一个取不到值的哑键");
            assertTrue(hasCode(t, WORKFLOW_DSL_INVALID.getCode()),
                    "itemVar=[" + bad + "] 应抛 WORKFLOW_DSL_INVALID，实际异常链：" + chain(t));
            assertEquals(0, runs.get(), "itemVar=[" + bad + "] 连图都没建起来，body 不应执行任何一次");
            assertTrue(rootMessage(t).contains(bad),
                    "报错文案必须把作者填的那个名字原样打出来，否则他不知道该改哪一格；实际：" + rootMessage(t));
        }
    }

    /**
     * 🟡 钉住 VAR_NAME 的<b>宽松</b>是刻意的：camelCase 变量名全系统都能跑（{@code outputVar}、
     * {@code variables[].key} 都<b>不</b>强制小写），只有<b>节点 id</b> 才是 {@code ^[a-z][a-z0-9_]{0,62}$}。
     * 若哪天有人把 itemVar 也收成「仅小写」，就会造出「{@code outputVar:"myVar"} 合法、
     * 但 {@code itemVar:"myItem"} 被拒」的割裂 —— 这条测试是那次改动的绊线。
     */
    @Test
    void iteration_itemVarNotForcedLowercase_accepted() throws Exception {
        // 🔴 两个样本各钉一半，缺一个就留一个真空档：
        //    "myRow" 钉「大写允许出现在<b>非首</b>字符」，"MyRow" 钉「大写允许出现在<b>首</b>字符」。
        //    这个真空档是<b>实测</b>撞出来的（2026-10-05）：变异探针把 VAR_NAME 从 ^[a-zA-Z]… 改成
        //    ^[a-z][a-zA-Z0-9_]{0,62}$（只禁「大写开头」）时，当时只有 "myRow" 的那版测试<b>全绿</b>、
        //    62 条一个不红 ⟹ 那次变异什么也没证明。按 §11.2「探针不红就不是证据」，
        //    红不起来的第一个要怀疑的是<b>测试留了空档</b>，而不是「这条性质没人会改」。
        for (String name : List.of("myRow", "MyRow")) {
            WorkflowDefDTO def = defWithAppendVars(null, "acc");
            NodeDTO describe = bodyNode("describe");
            Map<String, AsyncNodeAction> actions = new LinkedHashMap<>();
            actions.put("describe", appendAction(name, "acc", "struct:", null, null, null));

            Map<String, Object> state = run(
                    iterNode("tables", name, List.of("describe"), 1),
                    List.of(describe), actions, def,
                    Map.of("tables", List.of("a", "b")));

            assertEquals(List.of("struct:a", "struct:b"), state.get("acc"),
                    "itemVar=[" + name + "] 应照常工作：VAR_NAME 只要求「字母开头」，不照搬节点 id 的仅小写");
        }
    }

    /**
     * 🟡 「全空白」与「不合法」是<b>两条不同的错</b>，别合并成一条：
     * 全空白走 {@code isBlank} → 报「缺少 itemVar」，出路是<b>补上</b>这一格；
     * 带字符但不合法走 VAR_NAME → 报「不是合法变量名」，出路是<b>改名</b>。
     *
     * <p>⚠️ VAR_NAME 本身也拒空白串，所以「把 {@code isBlank} 那道删了、只留 VAR_NAME」在
     * <b>拒不拒</b>上完全等价，却会把「忘了填」的作者指引成「你填的名字不合法」——
     * 两条错误的价值全在文案给出的<b>出路</b>不同，故这里钉住各自的文案。
     */
    @Test
    void iteration_itemVarBlank_rejectedAsMissingNotAsIllegalName() {
        WorkflowDefDTO def = defWithAppendVars(null, "acc");
        NodeDTO describe = bodyNode("describe");
        Map<String, AsyncNodeAction> actions = new LinkedHashMap<>();
        actions.put("describe", appendAction("row", "acc", "struct:", null, null, null));

        Throwable t = assertThrows(Throwable.class, () -> builder.build(
                        iterNode("tables", "   ", List.of("describe"), 1),
                        List.of(describe), actions, def),
                "全空白的 itemVar 也该拒（运行期它会变成一个名叫 \"   \" 的键）");
        assertTrue(hasCode(t, WORKFLOW_DSL_INVALID.getCode()), "异常链：" + chain(t));
        String msg = rootMessage(t);
        assertTrue(msg.contains("缺少 itemVar"),
                "全空白应报「缺少 itemVar」（出路=补上），而不是「不是合法变量名」（出路=改名）；实际：" + msg);
    }

    // ==================== 工具方法 ====================

    /** 取异常链最内层的 message（本类的报错都由 {@code ServiceExceptionUtil} 直接抛，不经包装，故通常就是它自己） */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return String.valueOf(cur.getMessage());
    }

    private static boolean hasCode(Throwable t, Integer code) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ServiceException se && code.equals(se.getCode())) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getSimpleName());
            if (c instanceof ServiceException se) {
                sb.append('(').append(se.getCode()).append(')');
            }
            sb.append(" <- ");
            if (c.getCause() == c) {
                break;
            }
        }
        return sb.toString();
    }

}
