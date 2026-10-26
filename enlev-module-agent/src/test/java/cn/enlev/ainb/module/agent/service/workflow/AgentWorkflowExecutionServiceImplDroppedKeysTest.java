package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlConfirmNodeExecutor;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3-7 的三条验证（V-a / V-b / V-c）：{@code computeDroppedKeys} 的差集口径。
 *
 * <p>🔴 与 {@code KeyStrategyResumeRuntimeTest} 的分工：那边是<b>真挂起/真恢复</b>的端到端性质测试
 * （钉住「裸差集恒非空」这条 C8 前置事实，并<b>刻意不钉</b>任何字面内部键名）；本类是<b>纯单元</b>，
 * 构造已知形状的 {@code OverAllState} 逐条验证<b>过了滤网之后</b>的差集算法本身。两者互补，不重叠。
 *
 * <h3>🔴 本类钉的核心性质：报出来的每一个键，作者补一条 {@code variables[]} 声明就真的能救</h3>
 * 裸差集（state 键 − 注册键）恒非空，里面全是引擎簿记键与执行器审计键 —— 那些键作者无从声明，
 * 报出来就是<b>每次恢复都弹一次的假警报</b>，还会把运维支去改一个改不动的地方。
 * 故 V-a 用 {@code assertEquals} 逐字比对（不是 {@code assertFalse(isEmpty)}）：
 * 只断「非空」会同时漏掉「多算了引擎键」与「少算了作者键」这两种错法，而前者正是本类存在的理由。
 *
 * <p>⚠️ 夹具里出现的 {@code _graph_execution_id_} / {@code __route_…} / {@code …_confirmed} 只是
 * <b>长得像</b>引擎与执行器会写的那些键，用来喂进 state；断言侧钉的是「白名单外的一个都不许留下」，
 * 不钉这些字面名会由 SAA 产出 —— 与 {@code KeyStrategyResumeRuntimeTest} 同一条纪律。
 *
 * @author 走进新时代
 */
class AgentWorkflowExecutionServiceImplDroppedKeysTest {

    /**
     * 作者把 {@code tool} 节点的输出名<b>改过</b>（{@code outputVar=my_tool_out}）却没在
     * {@code variables[]} 里声明它 —— 这正是 V19 在发布期拦的那一档，也是过滤后差集
     * <b>唯一</b>可能非空的形状：契约缺省名与已声明变量都被 {@code KeyStrategyFactoryGenerator}
     * 注册了，剩下的只有「改过名又没声明」这一种。
     */
    private static final String UNDECLARED_OVERRIDDEN_DSL = """
            {"dslVersion":"1.0","id":"wf_b37_undeclared","name":"改过输出名却没声明",
             "variables":[
               {"key":"declared_var","type":"string","merge":"replace"}
             ],
             "nodes":[
               {"id":"t","type":"tool","config":{"outputVar":"my_tool_out"}},
               {"id":"cap","type":"capture","config":{"captureKeys":["my_tool_out"],"outputVar":"declared_var"}}
             ],
             "edges":[
               {"from":"START","to":"t"},
               {"from":"t","to":"cap"},
               {"from":"cap","to":"END"}
             ]}
            """;

    /** 同一个 def，但 {@code my_tool_out} 补了声明 —— 作者做对了的情形，差集必须为空 */
    private static final String FULLY_DECLARED_DSL = """
            {"dslVersion":"1.0","id":"wf_b37_declared","name":"改过输出名且声明了",
             "variables":[
               {"key":"declared_var","type":"string","merge":"replace"},
               {"key":"my_tool_out","type":"string","merge":"replace"}
             ],
             "nodes":[
               {"id":"t","type":"tool","config":{"outputVar":"my_tool_out"}},
               {"id":"cap","type":"capture","config":{"captureKeys":["my_tool_out"],"outputVar":"declared_var"}}
             ],
             "edges":[
               {"from":"START","to":"t"},
               {"from":"t","to":"cap"},
               {"from":"cap","to":"END"}
             ]}
            """;

    private DslParser parser;

    @BeforeEach
    void setUp() {
        parser = new DslParser();
        ReflectionTestUtils.setField(parser, "objectMapper", new ObjectMapper());
    }

    private WorkflowDefDTO parse(String dsl) {
        return parser.parse(dsl);
    }

    private static OverAllState stateOf(Map<String, Object> data, String... registered) {
        Map<String, KeyStrategy> strategies = new HashMap<>();
        for (String key : registered) {
            strategies.put(key, KeyStrategy.REPLACE);
        }
        OverAllState state = new OverAllState(data);
        state.registerKeyAndStrategy(strategies);
        return state;
    }

    /**
     * V-a：过滤后的差集<b>恰好</b>等于「作者写过、checkpoint 却没注册」的那一个键。
     *
     * <p>state 里同时塞了三类未注册键：引擎簿记（执行号、条件路由）、执行器审计（{@code <nodeId>_confirmed}）、
     * 以及作者真写过的那个。前者必须被滤掉，后者必须留下。
     */
    @Test
    @DisplayName("V-a：差集恰好等于「作者写过但未注册」的键 —— 引擎簿记键与执行器审计键一个都不许混进来")
    void droppedKeys_exactDiff_afterAuthorFilter() {
        WorkflowDefDTO def = parse(UNDECLARED_OVERRIDDEN_DSL);

        Map<String, Object> data = new HashMap<>();
        data.put("my_tool_out", "T");                    // 作者改过名又没声明 ⟹ 该报
        data.put("declared_var", "hello");               // 已声明且已注册 ⟹ 不报
        data.put("_graph_execution_id_", "exec-001");    // 引擎簿记，未注册 ⟹ 滤掉
        data.put("__route_approve_gate", "yes");         // 条件节点路由键，未注册 ⟹ 滤掉
        data.put("gate_confirmed", true);                // 执行器审计键，未注册 ⟹ 滤掉

        OverAllState state = stateOf(data, "declared_var");

        Set<String> dropped = AgentWorkflowExecutionServiceImpl.computeDroppedKeys(state, def);

        assertEquals(Set.of("my_tool_out"), dropped,
                "差集必须恰好是那个「改过名却没声明」的作者键；混进任何一个引擎/审计键都说明滤网漏了，"
                        + "而那会让每次恢复都弹一次作者修不了的假警报");
    }

    /**
     * V-a′（本类最要紧的一条）：裸差集非空、但里面<b>一个作者键都没有</b> ⟹ 过滤后为空。
     *
     * <p>🔴 这一条就是「假警报」与「不报」的分水岭。{@code KeyStrategyResumeRuntimeTest} 已经证明
     * 作者该做的全做对了、裸差集<b>仍然</b>非空；若没有滤网，那种完全健康的执行也会被落库、
     * 也会让前端确认框弹警告。少了这条断言，V-a 全绿也照样可能实现成「裸差集直接报」。
     */
    @Test
    @DisplayName("V-a′：裸差集非空但没有作者键 → 过滤后为空（健康的执行不许被报警）")
    void droppedKeys_emptyWhenOnlyEngineNoise() {
        WorkflowDefDTO def = parse(FULLY_DECLARED_DSL);

        Map<String, Object> data = new HashMap<>();
        data.put("my_tool_out", "T");                    // 已声明 ⟹ 白名单内，但下面注册了它
        data.put("declared_var", "hello");
        data.put("_graph_execution_id_", "exec-002");    // 未注册
        data.put("__route_approve_gate", "yes");         // 未注册
        data.put("gate_confirmed", true);                // 未注册

        OverAllState state = stateOf(data, "declared_var", "my_tool_out");

        // 先证裸差集确实非空（否则这条断言会因夹具失效而空转变绿）
        Set<String> raw = new java.util.LinkedHashSet<>(state.data().keySet());
        raw.removeAll(state.keyStrategies().keySet());
        assertFalse(raw.isEmpty(), "夹具失效：裸差集本该非空（引擎簿记键没人注册），实际=" + raw);

        Set<String> dropped = AgentWorkflowExecutionServiceImpl.computeDroppedKeys(state, def);

        assertTrue(dropped.isEmpty(),
                "作者该声明的都声明了，裸差集里只剩引擎与执行器的键 —— 必须被滤成空集。"
                        + "非空就意味着 serializeDroppedKeys 会落库、前端会弹一条修不了的假警告。实际=" + dropped);
        assertNull(AgentWorkflowExecutionServiceImpl.serializeDroppedKeys(dropped),
                "空集必须序列化成 null，让列保持 NULL");
    }

    /**
     * V-b（双向的另一半）：state 键<b>全部</b>已注册 ⟹ 差集为空。
     *
     * <p>🔴 不钉这一半，实现可能变成「无条件落一行日志」—— 即便没有任何键被丢弃也报。
     * 空集意味着 {@code serializeDroppedKeys} 返回 null，列保持 NULL，前端确认框不展示警告。
     */
    @Test
    @DisplayName("V-b：state 键全部已注册 → 差集为空（不落日志、不吓用户）")
    void droppedKeys_emptyWhenAllRegistered() {
        WorkflowDefDTO def = parse(FULLY_DECLARED_DSL);

        Map<String, Object> data = new HashMap<>();
        data.put("var_a", 1);
        data.put("var_b", 2);

        OverAllState state = stateOf(data, "var_a", "var_b");

        Set<String> dropped = AgentWorkflowExecutionServiceImpl.computeDroppedKeys(state, def);

        assertTrue(dropped.isEmpty(),
                "所有键都已注册，差集必须为空。若非空则 serializeDroppedKeys 会落库、前端会展示假警告");
    }

    /**
     * V-b 补：def 为 null 时一律不报（宁可漏报也不假报）。
     *
     * <p>resume 路径上 dto 恒非空（{@code defService.getDef} 取不到会先抛异常），故这一档是防御性的：
     * 万一将来多了一个拿不到 def 的调用方，行为应是「沉默」而不是「把整份裸差集报出去」。
     */
    @Test
    @DisplayName("V-b 补：def 为 null → 差集为空（拿不到定义时宁可沉默，也不报一堆作者修不了的键）")
    void droppedKeys_emptyWhenDefNull() {
        Map<String, Object> data = new HashMap<>();
        data.put("my_tool_out", "T");
        data.put("_graph_execution_id_", "exec-003");

        OverAllState state = stateOf(data);

        assertTrue(AgentWorkflowExecutionServiceImpl.computeDroppedKeys(state, null).isEmpty(),
                "def 为 null 时白名单为空，差集必须为空");
    }

    /**
     * V-c：{@code hitl_approved} 这个硬编码豁免键<b>不进</b>差集。
     *
     * <p>它是 HITL 通道自带的键，{@code KeyStrategyFactoryGenerator} 无条件注册，
     * 正常情况本就不会进差集。但万一将来注册逻辑变了（或测试构造了一个没注册它的 state），
     * 显式豁免保证它永远不会被报出来 —— 报出来就是噪音，运维会以为 HITL 通道坏了。
     *
     * <p>🔴 为了让豁免真的被走到，这里<b>刻意</b>把它声明进 {@code variables[]}（进白名单）
     * 又不注册它（进裸差集）：少了任何一步，它都会被前一道关卡先滤掉，V-c 就成了恒真断言。
     *
     * <p>🔴 引用常量 {@code HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR}，不写字面量：
     * {@code NodeIoContractTest#mainSourcesHaveNoDefaultNameLiterals} 扫 src/main 全部源码文本行。
     * 本文件在 src/test，不受那条扫描约束，但保持一致习惯。
     */
    @Test
    @DisplayName("V-c：hitl_approved 豁免 —— 进了白名单也没注册，仍不进差集（HITL 通道自带的键，报出来是噪音）")
    void droppedKeys_hitlApprovedExempt() {
        String dsl = """
                {"dslVersion":"1.0","id":"wf_b37_hitl","name":"豁免验证",
                 "variables":[
                   {"key":"%s","type":"boolean","merge":"replace"},
                   {"key":"my_tool_out","type":"string","merge":"replace"}
                 ],
                 "nodes":[
                   {"id":"gate","type":"hitl.confirm","config":{}},
                   {"id":"t","type":"tool","config":{"outputVar":"my_tool_out"}}
                 ],
                 "edges":[
                   {"from":"START","to":"gate"},
                   {"from":"gate","to":"t"},
                   {"from":"t","to":"END"}
                 ]}
                """.formatted(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR);
        WorkflowDefDTO def = parse(dsl);

        Map<String, Object> data = new HashMap<>();
        data.put(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR, true);  // 进了白名单，但下面刻意不注册
        data.put("my_tool_out", "T");                                  // 同样进白名单且未注册 ⟹ 该留下
        data.put("_graph_execution_id_", "exec-004");                  // 引擎键，未注册 ⟹ 滤掉

        // 🔴 一个都不注册：my_tool_out 必须留在差集里当「对照」，
        //    否则「hitl_approved 被豁免」与「整个差集被滤空」两种实现看起来一模一样。
        OverAllState state = stateOf(data);
        // 反向确认夹具成立：它确实在白名单里、确实没被注册
        assertTrue(AgentWorkflowExecutionServiceImpl.authorWrittenKeys(def)
                        .contains(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR),
                "夹具失效：hitl_approved 本该被声明进白名单，否则 V-c 会被前一道关卡滤掉而恒真");
        assertFalse(state.keyStrategies().containsKey(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR),
                "夹具失效：hitl_approved 本该未被注册，否则它压根进不了裸差集");

        Set<String> dropped = AgentWorkflowExecutionServiceImpl.computeDroppedKeys(state, def);

        assertEquals(Set.of("my_tool_out"), dropped,
                "差集必须恰好只剩那个真该报的作者键；hitl_approved 必须被豁免，"
                        + "引擎簿记键必须被滤掉。实际=" + dropped);
    }

    /**
     * 补充：serializeDroppedKeys 对空集返回 null（列保持 NULL），对非空集返回 JSON 数组。
     */
    @Test
    @DisplayName("serializeDroppedKeys：空集→null，非空→JSON 数组字符串")
    void serializeDroppedKeys_nullForEmpty_jsonForNonEmpty() {
        assertNull(AgentWorkflowExecutionServiceImpl.serializeDroppedKeys(Set.of()),
                "空集必须返回 null，让列保持 NULL（= 差集为空）");

        String json = AgentWorkflowExecutionServiceImpl.serializeDroppedKeys(Set.of("key_a", "key_b"));
        assertTrue(json != null && json.startsWith("[") && json.endsWith("]"),
                "非空集必须返回 JSON 数组字符串，实际：" + json);
        assertTrue(json.contains("key_a") && json.contains("key_b"),
                "JSON 里必须包含全部键，实际：" + json);
    }
}
