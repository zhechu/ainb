package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.infra.api.logic.InfraLogicRuleApi;
import cn.enlev.ainb.module.infra.api.logic.InfraLogicRuleApiImpl;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicRuleEvaluator;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuleConditionNodeExecutor} 单测
 *
 * <p>两条求值路径都覆盖：
 * <ul>
 *   <li><b>扁平 conditions</b>（P1，向后兼容）：执行器就地求值，不依赖注入 —— 直接 new 即可，
 *       验证数值等值容错、非数值比较落 false、is_true/contains、无命中走 default、空 conditions 恒命中、
 *       未知算子不命中。</li>
 *   <li><b>conditionGroups</b>（P2 增量1，嵌套 AND/OR）：经 {@link InfraLogicRuleApi} 委托 infra 的
 *       {@link LogicRuleEvaluator}。测试注入<b>真实</b>求值器（纯逻辑、无依赖），端到端验证
 *       Map→ConditionGroup 适配 + 单层/嵌套/OR 短路/多算子。</li>
 * </ul>
 *
 * <p>注入真实 {@link InfraLogicRuleApiImpl} 而非 mock：本测试的价值正在「适配层是否把 Map 正确
 * 转成 ConditionGroup 并命中真实语义」，mock 掉就把这段逻辑跳过了。测试作用域不被
 * {@code AgentArchitectureTest}（只扫 src/main/java）约束，故可直接构造 infra 内部实现来装配 api。
 *
 * @author 走进新时代
 */
class RuleConditionNodeExecutorTest {

    private final RuleConditionNodeExecutor executor = new RuleConditionNodeExecutor();

    @BeforeEach
    void setUp() {
        // 装配真实 api：LogicRuleEvaluator 是纯逻辑求值器，ObjectMapper 用默认实例即可
        InfraLogicRuleApiImpl api = new InfraLogicRuleApiImpl();
        ReflectionTestUtils.setField(api, "logicRuleEvaluator", new LogicRuleEvaluator());
        ReflectionTestUtils.setField(api, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(executor, "infraLogicRuleApi", (InfraLogicRuleApi) api);
    }

    private static WorkflowContext ctx(Map<String, Object> data) {
        OverAllState state = new OverAllState(new HashMap<>(data));
        return new WorkflowContext(state, 1L, "t1:1", AgentExecutionContext.ofForTest(1L, 1L));
    }

    private static NodeDTO node(Map<String, Object> config) {
        NodeDTO n = new NodeDTO();
        n.setId("cond");
        n.setType(RuleConditionNodeExecutor.TYPE);
        n.setConfig(config);
        return n;
    }

    private static Map<String, Object> branch(String target, Map<String, Object>... conditions) {
        Map<String, Object> b = new HashMap<>();
        b.put("target", target);
        b.put("conditions", List.of(conditions));
        return b;
    }

    private static Map<String, Object> cond(String var, String op, Object value) {
        Map<String, Object> c = new HashMap<>();
        c.put("var", var);
        c.put("op", op);
        c.put("value", value);
        return c;
    }

    private String routeOf(Map<String, Object> delta) {
        assertTrue(delta.containsKey(NodeExecutor.routeKey("cond")), "delta 应含路由键");
        return String.valueOf(delta.get(NodeExecutor.routeKey("cond")));
    }

    @Test
    void execute_containsHit_routesToBranchTarget() {
        NodeDTO n = node(Map.of(
                "branches", List.of(branch("praise", cond("text", "contains", "好"))),
                "default", "fallback"));
        Map<String, Object> delta = executor.execute(n, ctx(Map.of("text", "这个很好")));
        assertEquals("praise", routeOf(delta));
    }

    @Test
    void execute_conditionValuePlaceholder_isNotResolved() {
        // 🔴 钉住 NodeIoContract 里 condition.rule 那句 hint「比较值按字面量处理，{{$.变量}} 不替换」：
        //    执行器是 cond.get("value") 原样取来比，中间没有任何 StateTemplateResolver。
        //    这条 hint 曾经写反了（承诺「条件值可用 {{$.变量}}」），而它没有任何强制点，所以漂了很久没人发现。

        // 先肯定「分支本身是通的」：条件值写 state 里的真值就命中。
        // 没有这一条，下面那条「不命中」就和「求值整个坏了」共用同一个绿色。
        NodeDTO literal = node(Map.of(
                "branches", List.of(branch("same", cond("q", "eq", "采购"))),
                "default", "fallback"));
        assertEquals("same", routeOf(executor.execute(literal, ctx(Map.of("q", "采购")))));

        // 再断言占位符不被替换：state 里 q="采购"，条件值写 "{{$.q}}"，若被替换就该命中 same，
        // 实际按字面串比 ⟹ 落 default。
        NodeDTO placeholder = node(Map.of(
                "branches", List.of(branch("same", cond("q", "eq", "{{$.q}}"))),
                "default", "fallback"));
        assertEquals("fallback", routeOf(executor.execute(placeholder, ctx(Map.of("q", "采购")))),
                "条件值里的 {{$.q}} 应保持字面串，不该被 state 里的 q 替换掉");
    }

    @Test
    void execute_varIsTopLevelOnly_doesNotNavigatePaths() {
        // 🔴 钉住同一句 hint 的后半「变量名只认顶层键」：ctx.get(var) 是 OverAllState 的扁平读，
        //    不走 StatePathNavigator，故 var 写 "order.amount" 取不到嵌套值（与 {{$.order.amount}} 的
        //    模板语义不同——那套是能下钻的）。两种读法并存最容易让人误以为条件里也能写路径。

        // 先肯定顶层键这条路是通的
        NodeDTO flat = node(Map.of(
                "branches", List.of(branch("big", cond("amount", "gt", 500))),
                "default", "small"));
        assertEquals("big", routeOf(executor.execute(flat, ctx(Map.of("amount", 900)))));

        // 再断言路径式取不到：state 里确实有 order.amount=900，但 var 写成路径就落 default
        NodeDTO nested = node(Map.of(
                "branches", List.of(branch("big", cond("order.amount", "gt", 500))),
                "default", "small"));
        Map<String, Object> state = new HashMap<>();
        state.put("order", Map.of("amount", 900));
        assertEquals("small", routeOf(executor.execute(nested, ctx(state))),
                "var 写路径取不到嵌套值，应落 default —— 这正是 hint 警告的那件事");
    }

    @Test
    void execute_eqTolerantAcrossNumberAndString() {
        // state 里是字符串 "1"，条件里写数字 1，应按数值相等命中
        NodeDTO n = node(Map.of(
                "branches", List.of(branch("one", cond("count", "eq", 1))),
                "default", "other"));
        assertEquals("one", routeOf(executor.execute(n, ctx(Map.of("count", "1")))));
    }

    @Test
    void execute_gtOnNonNumeric_doesNotMatch_fallsToDefault() {
        // 关键回归：非数值比较一律落 false（而不是被哨兵值误判成命中）
        NodeDTO n = node(Map.of(
                "branches", List.of(branch("big", cond("name", "gt", 10))),
                "default", "fallback"));
        assertEquals("fallback", routeOf(executor.execute(n, ctx(Map.of("name", "abc")))));
    }

    @Test
    void execute_isTrueMatchesBooleanAndString() {
        NodeDTO n = node(Map.of(
                "branches", List.of(branch("yes", cond("flag", "is_true", null))),
                "default", "no"));
        assertEquals("yes", routeOf(executor.execute(n, ctx(Map.of("flag", Boolean.TRUE)))));
        assertEquals("yes", routeOf(executor.execute(n, ctx(Map.of("flag", "true")))));
    }

    @Test
    void execute_allConditionsMustHold_andSemantics() {
        // 两个条件 AND，只满足一个 → 不命中 → default
        NodeDTO n = node(Map.of(
                "branches", List.of(branch("both",
                        cond("a", "eq", 1), cond("b", "eq", 2))),
                "default", "fallback"));
        assertEquals("fallback", routeOf(executor.execute(n, ctx(Map.of("a", 1, "b", 99)))));
        assertEquals("both", routeOf(executor.execute(n, ctx(Map.of("a", 1, "b", 2)))));
    }

    @Test
    void execute_emptyConditions_treatedAsHit() {
        NodeDTO n = node(Map.of(
                "branches", List.of(branch("always")),
                "default", "fallback"));
        assertEquals("always", routeOf(executor.execute(n, ctx(Map.of()))));
    }

    @Test
    void execute_noBranchNoDefault_usesDefaultRouteSentinel() {
        NodeDTO n = node(Map.of("branches", List.of()));
        assertEquals(NodeExecutor.DEFAULT_ROUTE, routeOf(executor.execute(n, ctx(Map.of()))));
    }

    @Test
    void execute_unknownOperator_treatedAsNoMatch() {
        NodeDTO n = node(Map.of(
                "branches", List.of(branch("x", cond("a", "weird_op", 1))),
                "default", "fallback"));
        assertEquals("fallback", routeOf(executor.execute(n, ctx(Map.of("a", 1)))));
    }

    // ========== P2 增量1：conditionGroups 嵌套（经 InfraLogicRuleApi → LogicRuleEvaluator） ==========

    /** 叶子条件：{field, operator, valueType, value} */
    private static Map<String, Object> leaf(String field, String op, Object value) {
        Map<String, Object> c = new HashMap<>();
        c.put("field", field);
        c.put("operator", op);
        c.put("valueType", "CONST");
        c.put("value", value);
        return c;
    }

    /** 条件组：{logic, items}；items 可为叶子或嵌套组 */
    private static Map<String, Object> group(String logic, Map<String, Object>... items) {
        Map<String, Object> g = new HashMap<>();
        g.put("logic", logic);
        g.put("items", List.of(items));
        return g;
    }

    /** 带 conditionGroups 的分支 */
    private static Map<String, Object> branchGroups(String target, Map<String, Object> conditionGroups) {
        Map<String, Object> b = new HashMap<>();
        b.put("target", target);
        b.put("conditionGroups", conditionGroups);
        return b;
    }

    @Test
    void execute_conditionGroups_singleLayerAnd() {
        // amount GT 1000 AND level EQ "vip"
        NodeDTO n = node(Map.of(
                "branches", List.of(branchGroups("pass",
                        group("AND", leaf("amount", "GT", 1000), leaf("level", "EQ", "vip")))),
                "default", "reject"));
        assertEquals("pass", routeOf(executor.execute(n,
                ctx(Map.of("amount", 2000, "level", "vip")))));
        // 只满足一个 → AND 不命中 → default
        assertEquals("reject", routeOf(executor.execute(n,
                ctx(Map.of("amount", 2000, "level", "normal")))));
    }

    @Test
    void execute_conditionGroups_nestedAndOr() {
        // (amount GT 1000 AND level EQ vip) OR (whitelist EQ true)
        NodeDTO n = node(Map.of(
                "branches", List.of(branchGroups("pass",
                        group("OR",
                                group("AND", leaf("amount", "GT", 1000), leaf("level", "EQ", "vip")),
                                leaf("whitelist", "EQ", true)))),
                "default", "reject"));
        // 走内层 AND 命中
        assertEquals("pass", routeOf(executor.execute(n,
                ctx(Map.of("amount", 2000, "level", "vip", "whitelist", false)))));
        // 内层 AND 不满足，但外层 OR 的 whitelist 命中
        assertEquals("pass", routeOf(executor.execute(n,
                ctx(Map.of("amount", 10, "level", "normal", "whitelist", true)))));
        // 两条都不满足
        assertEquals("reject", routeOf(executor.execute(n,
                ctx(Map.of("amount", 10, "level", "normal", "whitelist", false)))));
    }

    @Test
    void execute_conditionGroups_orShortCircuit() {
        // OR：任一命中即路由，验证不会因后一个不满足而整体失败
        NodeDTO n = node(Map.of(
                "branches", List.of(branchGroups("hit",
                        group("OR", leaf("a", "EQ", 1), leaf("b", "EQ", 999)))),
                "default", "miss"));
        assertEquals("hit", routeOf(executor.execute(n, ctx(Map.of("a", 1, "b", 2)))));
    }

    @Test
    void execute_conditionGroups_variousOperators() {
        // CONTAINS
        assertEquals("c", routeOf(executor.execute(
                node(Map.of("branches", List.of(branchGroups("c",
                        group("AND", leaf("text", "CONTAINS", "好"))), "default", "x"))),
                ctx(Map.of("text", "这个很好")))));
        // BETWEEN（含端点，值为 [lo, hi]）
        assertEquals("b", routeOf(executor.execute(
                node(Map.of("branches", List.of(branchGroups("b",
                        group("AND", leaf("age", "BETWEEN", List.of(18, 60)))), "default", "x"))),
                ctx(Map.of("age", 30)))));
        // IN
        assertEquals("i", routeOf(executor.execute(
                node(Map.of("branches", List.of(branchGroups("i",
                        group("AND", leaf("code", "IN", List.of("A", "B")))), "default", "x"))),
                ctx(Map.of("code", "B")))));
    }

    @Test
    void execute_conditionGroups_emptyItems_treatedAsTrue() {
        // 空 items 的条件组恒真（与 infra「when 为空视为恒真」一致）
        NodeDTO n = node(Map.of(
                "branches", List.of(branchGroups("always", group("AND"))),
                "default", "fallback"));
        assertEquals("always", routeOf(executor.execute(n, ctx(Map.of()))));
    }

    @Test
    void execute_conditionGroups_takesPrecedenceOverFlatConditions() {
        // 同一分支两者都给：conditionGroups 命中即路由，忽略会落空的扁平 conditions
        Map<String, Object> b = new HashMap<>();
        b.put("target", "via_groups");
        b.put("conditionGroups", group("AND", leaf("a", "EQ", 1)));
        b.put("conditions", List.of(cond("a", "eq", 999))); // 与真实值矛盾，若走扁平会落 default
        NodeDTO n = node(Map.of("branches", List.of(b), "default", "fallback"));
        assertEquals("via_groups", routeOf(executor.execute(n, ctx(Map.of("a", 1)))));
    }

}
