package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.infra.api.logic.InfraLogicRuleApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code condition.rule} 节点执行器：规则条件路由（DSL §4.3）
 *
 * <p>评估 config.branches，返回<b>第一个命中的</b>分支 target（路由键）；
 * 都不命中返回 config.default。路由键写进 {@code __route_<nodeId>}，由条件边读取后映射到目标节点。
 *
 * <h3>一个分支两种写法，conditionGroups 优先（P2 增量1）</h3>
 * <ul>
 *   <li><b>{@code conditionGroups}</b>（新，DSL §4.3 的本意）：嵌套 AND/OR 条件组，形状 = infra
 *       {@code LogicRule.ConditionGroup}。求值<b>不在本类重写</b>，而是走
 *       {@link InfraLogicRuleApi} 委托 infra 的 {@code LogicRuleEvaluator} —— 那是与前端
 *       logic-cases.json 双端一致性测试绑定的求值器，15 种算子、任意深度嵌套。
 *       跨模块只能走 api/ 层（架构红线），故经 api 而非直接注入求值器。</li>
 *   <li><b>{@code conditions}</b>（P1 扁平写法，向后兼容）：列表内 AND 的扁平条件，
 *       算子 eq/ne/contains/not_contains/gt/ge/lt/le/exists/not_exists/is_true/is_false，
 *       由本类 {@link #evaluateAll} 就地求值。P1 的用例继续走这条路径。</li>
 * </ul>
 * 分支同时给了两者时以 {@code conditionGroups} 为准（见 {@link #matchBranch}）。
 *
 * <h3>⚠️ conditionGroups 的 field 是顶层变量名，不是 JSONPath</h3>
 * 求值器对 field 做扁平 {@code data.get(field)}，而这里传入的是 {@code ctx.data()}（顶层变量）。
 * 需要按嵌套路径（{@code $.a.b}）取值的条件，本增量不支持 —— 那要么改求值器（被一致性测试绑定，不能改），
 * 要么在传入前把路径预解析成顶层键，均留后续增量。扁平 {@code conditions} 路径的 var 同样是顶层变量名。
 *
 * <h3>本节点不调模型，token 恒为 0</h3>
 * 度量粘滞的坑（SPIKE SR-2）正来自「非 LLM 节点裸读 tokenUsage」。这里根本不碰 usage，
 * metrics 保持默认 0，trace 如实记 0 —— 而不是继承上一个 llm 节点的数字。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class RuleConditionNodeExecutor implements NodeExecutor {

    public static final String TYPE = "condition.rule";

    @Resource
    private InfraLogicRuleApi infraLogicRuleApi;

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
        List<Object> branches = node.configList("branches");
        String route = DEFAULT_ROUTE;
        for (Object branchObj : branches) {
            if (!(branchObj instanceof Map)) {
                continue;
            }
            Map<String, Object> branch = (Map<String, Object>) branchObj;
            if (matchBranch(branch, ctx)) {
                Object target = branch.get("target");
                if (target != null) {
                    route = String.valueOf(target);
                    break;
                }
            }
        }
        // 分支没给 target 或都不命中时，用 config.default；仍为空则走 DEFAULT_ROUTE 由条件边兜底
        if (DEFAULT_ROUTE.equals(route)) {
            String def = node.configStr("default");
            if (def != null && !def.isEmpty()) {
                route = def;
            }
        }
        log.info("[execute][condition.rule 节点({}) 执行({}) 路由({})]",
                node.getId(), ctx.getExecutionId(), route);
        return Map.of(NodeExecutor.routeKey(node.getId()), route);
    }

    /**
     * 判定一个分支是否命中：conditionGroups 优先，否则回落到扁平 conditions
     */
    @SuppressWarnings("unchecked")
    private boolean matchBranch(Map<String, Object> branch, WorkflowContext ctx) {
        Object groups = branch.get("conditionGroups");
        if (groups instanceof Map) {
            // 嵌套条件组：委托 infra 求值器（经 api 层），data = 顶层变量
            return infraLogicRuleApi.matchesConditionGroups((Map<String, Object>) groups, ctx.data());
        }
        return evaluateAll((List<Object>) branch.get("conditions"), ctx);
    }

    /**
     * 一个分支内所有条件 AND；空条件列表视为命中（true）
     */
    @SuppressWarnings("unchecked")
    private boolean evaluateAll(List<Object> conditions, WorkflowContext ctx) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Object condObj : conditions) {
            if (!(condObj instanceof Map)) {
                continue;
            }
            if (!evaluateOne((Map<String, Object>) condObj, ctx)) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateOne(Map<String, Object> cond, WorkflowContext ctx) {
        String var = str(cond.get("var"));
        String op = str(cond.get("op"));
        Object expected = cond.get("value");
        Object actual = var == null ? null : ctx.get(var);
        if (op == null) {
            return false;
        }
        return switch (op.toLowerCase()) {
            case "exists" -> actual != null;
            case "not_exists" -> actual == null;
            case "is_true" -> Boolean.TRUE.equals(actual) || "true".equalsIgnoreCase(str(actual));
            case "is_false" -> Boolean.FALSE.equals(actual) || "false".equalsIgnoreCase(str(actual));
            case "eq" -> eq(actual, expected);
            case "ne" -> !eq(actual, expected);
            case "contains" -> str(actual) != null && str(actual).contains(str(expected));
            case "not_contains" -> str(actual) == null || !str(actual).contains(str(expected));
            case "gt" -> numCompare(actual, expected, r -> r > 0);
            case "ge" -> numCompare(actual, expected, r -> r >= 0);
            case "lt" -> numCompare(actual, expected, r -> r < 0);
            case "le" -> numCompare(actual, expected, r -> r <= 0);
            default -> {
                log.warn("[evaluateOne][未知算子({})，按不命中处理]", op);
                yield false;
            }
        };
    }

    private boolean eq(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        // 数字按数值比，避免 "1" 与 1、Integer 与 Long 判不等
        Double an = asNumber(actual), en = asNumber(expected);
        if (an != null && en != null) {
            return an.compareTo(en) == 0;
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    /**
     * 数值比较算子（gt/ge/lt/le）的统一处理
     *
     * <p>🔴 任一操作数<b>非数值</b>时一律返回 false（不命中），而不是抛异常或强行比较。
     * 早期版本用一个 int 哨兵表达「不可比」，结果 {@code lt} 会把哨兵（MIN_VALUE）判成命中 ——
     * 一个配置写错的条件反而路由成功，是最难查的那种静默错误。改成显式的「非数值 → false」。
     */
    private boolean numCompare(Object actual, Object expected, java.util.function.IntPredicate test) {
        Double an = asNumber(actual), en = asNumber(expected);
        if (an == null || en == null) {
            log.warn("[numCompare][非数值比较 actual({}) expected({})，按不命中处理]", actual, expected);
            return false;
        }
        return test.test(an.compareTo(en));
    }

    private Double asNumber(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

}
