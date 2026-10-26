package cn.enlev.ainb.module.infra.api.logic;

import java.util.Map;

/**
 * 逻辑规则求值 API 接口
 *
 * <h3>为什么要有这个 api（而不是让调用方直接用 infra 的 LogicRuleEvaluator）</h3>
 *
 * agent 模块的 Workflow 引擎在 {@code condition.rule} 节点上需要「嵌套 AND/OR 条件组」求值能力。
 * 这套语义 infra 早就有了 —— {@code LogicRuleEvaluator.matches} 是一个<b>独立的递归求值器</b>，
 * 15 种算子、任意深度嵌套，且与前端 {@code logic-cases.json} 双端一致性测试绑定，行为可信。
 *
 * <p>但 agent 模块有一条架构红线（{@code AgentArchitectureTest.testOnlyDependOnApiLayer}）：
 * <b>跨模块只能依赖对方的 api/ 层，不得 import 内部实现</b>。这条红线不是分层洁癖 ——
 * 它保证 agent 拿不到任何返回业务行数据的内部方法，从而让「行权限 fail-open」这条攻击面
 * 对 AI 不可达。所以 {@code LogicRuleEvaluator} 不能直接注入到 agent。
 *
 * <p>本接口就是这条红线的合规出口（同 {@code AiRagApi} 的先例）：把「嵌套条件组求值」
 * 这一个纯函数能力，以 api 形式暴露出去。它<b>只吃两个 Map、只吐一个 boolean</b>，
 * 不碰数据库、不返回任何业务行数据，因此行权限安全前提原样保留。
 *
 * <h3>契约：conditionGroups 的形状 = infra LogicRule 形状</h3>
 *
 * 入参 {@code conditionGroups} 是 infra {@code LogicRule.ConditionGroup} 的 Map/JSON 表示，
 * 由调用方（P4 前端设计器）按该形状产出，本实现内部 convertValue 成对象后交给求值器：
 * <pre>
 * {
 *   "logic": "AND",            // 缺省 AND
 *   "items": [
 *     { "field": "amount", "operator": "GT", "valueType": "CONST", "value": 1000 },
 *     { "logic": "OR", "items": [ ... 叶子或再嵌套组 ... ] }
 *   ]
 * }
 * </pre>
 * 叶子：{@code {field, operator, valueType(CONST|FIELD), value}}；
 * operator 大小写不敏感（EQ/NE/GT/GE/LT/LE/IN/NOT_IN/CONTAINS/NOT_CONTAINS/
 * STARTS_WITH/ENDS_WITH/BETWEEN/EMPTY/NOT_EMPTY）。
 *
 * <p>⚠️ {@code field} 是 {@code data} 里的<b>顶层键</b>（求值器做扁平 {@code data.get(field)}，
 * 不支持 JSONPath）。需要按嵌套路径取值时，由调用方在传入 {@code data} 前先把路径解析成顶层键。
 *
 * @author 走进新时代
 */
public interface InfraLogicRuleApi {

    /**
     * 求值一个嵌套条件组
     *
     * @param conditionGroups 条件组（infra LogicRule.ConditionGroup 的 Map/JSON 表示）；
     *                        为 null 或空视为「恒真」——与 infra「when 为空视为恒真」语义一致
     * @param data            求值数据，field 按顶层键命中
     * @return 条件组是否命中
     */
    boolean matchesConditionGroups(Map<String, Object> conditionGroups, Map<String, Object> data);

}
