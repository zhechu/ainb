package cn.enlev.ainb.module.system.framework.permission.core.rule;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SlotMappingDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.SlotMappingMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 条件树求值器
 *
 * 把 {@link ConditionNode} 条件树求值为 SQL WHERE 片段（带参数化占位符）。
 *
 * ## 防注入设计（⚠️ 关键）
 *
 * 三道防线：
 *
 * 1. 操作符白名单：操作符只允许 {@link ConditionOperatorEnum} 里定义的值，
 *    SQL 片段里的操作符来自枚举的 sqlOp，不来自用户输入。
 *
 * 2. 列名白名单：真实列名只从 {@link SlotMappingDO#getColumnName()} 取，
 *    不来自 ConditionNode.slot 字段（slot 是语义名，不是列名）。
 *    若 slot 在 sys_slot_mapping 里找不到对应的列名，条件求不出来，
 *    整条规则被标成 {@link SqlFragment#unevaluable()}，由调用方按 fail-closed 处理
 *    （{@code DataRowFilterResolver}：ALLOW 落恒假、DENY 落全拒）。
 *
 * 3. 参数化：值要么是变量引用（:variableName，解析后的 Java 对象），
 *    要么是字面量（用 ? 占位，值放进 params 列表）。
 *    无论哪种，列名 + 操作符 + 值，三者都不拼接用户输入。
 *
 * ## 生成 SQL 格式
 *
 * evaluate 返回 {@link SqlFragment}，包含：
 * - sql：带 ? 占位符的 WHERE 条件片段，如 `(create_user_id = ? AND dept_id IN (?,?,?))`
 * - params：按位置对应的参数值列表
 * - unevaluable：树里<b>有没有条件求不出来</b>（槽位没配映射、列名非法、
 *   槽位绑的表不在本次查询里、操作符不在白名单里）。⚠️ 它与 {@code isEmpty()} 是两件事，见下面
 *
 * 调用方（{@code RuleDataPermissionRule} 与 {@code DataRowFilterResolver}）把 sql
 * 追加到过滤条件里，params 走参数化路径，不拼 SQL。
 *
 * ## 🔴 「求不出来」必须能被调用方看见，不能被静默丢掉
 *
 * 早先的实现是：某个条件求不出来就返回空串，{@link #buildGroupSql} 把它从 parts 里滤掉。
 * 看着无害，实际是个提权缺陷 —— 一棵 {@code AND(owner, dept)} 的树，
 * 若 dept 槽位没配映射，产出就成了 {@code (owner = ?)}，
 * <b>比管理员配的那条规则宽</b>；只有整棵树全求不出来时才会产出空片段、
 * 才会被调用方按恒假处理。也就是说「配错一半」比「全配错」放得更开，且全程不报错。
 *
 * 所以现在多带一个 {@code unevaluable} 标记：<b>只要有一个条件求不出来，
 * 整条规则就算求不出来</b>，由调用方落恒假/全拒。
 * 看不到数据会有人来报，悄悄放开没有人会发现。
 *
 * ⚠️ 刻意<b>不</b>把「变量在上下文里取不到」也算进 unevaluable：
 * 那说的是<b>当前这个用户</b>的数据状况，不是配置错误。
 * 比如 {@code OWNER_OR_DEPT} 遇上一个没有部门的用户，
 * 退化成「只看自己的」正是想要的行为；判成求不出来会变成一行都看不到。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class ConditionTreeEvaluator {

    /**
     * 合法标识符。列名即便来自 DB 里的可审计配置也再过一道 ——
     * 数据污染时宁可让规则失效，也不能把它拼进 SQL
     */
    private static final String IDENTIFIER_REGEX = "[a-zA-Z_][a-zA-Z0-9_]*";

    @Resource
    private SlotMappingMapper slotMappingMapper;

    /**
     * 求值结果
     *
     * @param unevaluable 树里是否有条件求不出来。为 true 时 {@code sql} 可能<b>非空</b>
     *                    （其余条件求出来了），调用方仍必须按「整条规则求不出来」处理 ——
     *                    拿那半截 SQL 去过滤比不过滤更危险，因为它看着像生效了
     */
    public record SqlFragment(String sql, List<Object> params, boolean unevaluable) {

        public SqlFragment(String sql, List<Object> params) {
            this(sql, params, false);
        }

        public boolean isEmpty() {
            return StrUtil.isBlank(sql);
        }
    }

    /**
     * 把条件树求值为 SQL 片段
     *
     * @param node       条件树根节点
     * @param resourceId 资源编号（用于查槽位映射）
     * @param variables  变量上下文（由 RuleVariableProvider 聚合提供）
     * @return SQL 片段；若整棵树无有效条件，返回 isEmpty()=true 的片段
     */
    public SqlFragment evaluate(ConditionNode node, Long resourceId, Map<String, Object> variables) {
        return evaluate(node, resourceId, variables, null);
    }

    /**
     * 把条件树求值为 SQL 片段，并给列名加上表限定前缀
     *
     * <h3>为什么前缀要在这一层注入，而不是事后正则替换</h3>
     *
     * 早期实现是在生成完整片段之后，用
     * {@code ([a-zA-Z_]\w*)\s*(=|IN|…)} 这类正则把裸列名替换成 {@code alias.列名}。
     * 那种做法在含函数调用、子查询、字符串字面量的片段上会误伤，
     * 而误伤的结果是<b>一条语法正确但语义错误的 SQL</b> —— 不报错，只是过滤错了行。
     *
     * 列名本来就只在 {@link #resolveColumn} 这一个地方产生（来自槽位映射白名单），
     * 在那里带上前缀是确定的、不会认错的。
     *
     * @param columnPrefix 列名前缀，形如 {@code `biz_order`.}；为空则不加
     */
    public SqlFragment evaluate(ConditionNode node, Long resourceId,
                                Map<String, Object> variables, String columnPrefix) {
        return evaluate(node, resourceId, variables, columnPrefix, null);
    }

    /**
     * 同上，并额外告知「本次查询里每张表的合法限定符」
     *
     * <h3>为什么光有 {@code columnPrefix} 不够</h3>
     *
     * 那个前缀说的只是<b>主表</b>。而槽位映射现在可以绑到关联表上
     * （{@link SlotMappingDO#getTableName()}），在线表单的列表查询也确实会 JOIN
     * （{@code TableBinding.joinTables}）。拿主表前缀去限定一个关联表的列，
     * 结果是<b>一条语法合法、语义错误的 SQL</b> —— 匹配到主表的同名列，
     * 或者报 Unknown column。两种都不该发生。
     *
     * <h3>为什么 key 是物理表名、value 是「限定符」而不是「别名」</h3>
     *
     * JOIN 的关联表可以带别名，带了别名之后<b>物理表名就不是合法的限定符了</b>。
     * 谁知道这次查询里它叫什么？只有拼 FROM/JOIN 的那一层
     * （{@code FormDesignSqlBuilder}）。所以由它现场算好交进来，本类只管查表 ——
     * 存进 DB 的物理表名因此永远不需要、也永远不会被拼进 SQL。
     *
     * @param tableQualifiers 物理表名（小写）→ 该表在本次查询里合法的限定前缀
     *                        （含结尾的点，无 JOIN 时是空串）。
     *                        ⚠️ 调用方<b>必须</b>把主表也放进去（值就是 columnPrefix）。
     *                        传 null 表示「调用方不知道本次查询有哪些表」，
     *                        此时一律用 columnPrefix —— 与加这个参数之前逐字节一致
     */
    public SqlFragment evaluate(ConditionNode node, Long resourceId, Map<String, Object> variables,
                                String columnPrefix, Map<String, String> tableQualifiers) {
        if (node == null) {
            return new SqlFragment("", List.of());
        }
        Evaluation evaluation = new Evaluation(columnPrefix, tableQualifiers);
        String sql = buildSql(node, resourceId, variables, evaluation);
        return new SqlFragment(sql, evaluation.params, evaluation.unevaluable);
    }

    /**
     * 一次求值的可变上下文
     *
     * 把参数表、限定符、以及「有没有条件求不出来」收在一起，
     * 免得这四样东西在递归的每个方法上都各占一个形参
     */
    private static final class Evaluation {

        final List<Object> params = new ArrayList<>();
        final String columnPrefix;
        final Map<String, String> tableQualifiers;
        boolean unevaluable;

        Evaluation(String columnPrefix, Map<String, String> tableQualifiers) {
            this.columnPrefix = columnPrefix;
            this.tableQualifiers = tableQualifiers;
        }

    }

    private String buildSql(ConditionNode node, Long resourceId,
                            Map<String, Object> variables, Evaluation evaluation) {
        if (node.isGroup()) {
            return buildGroupSql(node, resourceId, variables, evaluation);
        } else if (node.isCondition()) {
            return buildConditionSql(node, resourceId, variables, evaluation);
        }
        return "";
    }

    private String buildGroupSql(ConditionNode node, Long resourceId,
                                 Map<String, Object> variables, Evaluation evaluation) {
        if (node.getChildren() == null || node.getChildren().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (ConditionNode child : node.getChildren()) {
            String part = buildSql(child, resourceId, variables, evaluation);
            if (StrUtil.isNotBlank(part)) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        String joiner = node.getLogic() == ConditionNode.LogicOperator.AND ? " AND " : " OR ";
        return "(" + String.join(joiner, parts) + ")";
    }

    private String buildConditionSql(ConditionNode node, Long resourceId,
                                     Map<String, Object> variables, Evaluation evaluation) {
        // 1. 列名白名单校验（防注入关键）
        String columnName = resolveColumn(resourceId, node.getSlot(), evaluation);
        if (columnName == null) {
            // 🔴 不能只是「丢掉这个条件」：AND 组里少一个条件 = 过滤变宽 = 提权。
            //    标成求不出来，让调用方按整条规则失效处理（理由见类注释）
            evaluation.unevaluable = true;
            return "";
        }

        // 2. 操作符白名单校验（防注入关键）
        ConditionOperatorEnum op = ConditionOperatorEnum.parse(node.getOperator());
        if (op == null) {
            // 🔴 与上面「列名求不出来」同一处置：操作符不在白名单里说明这条规则本身配坏了
            //    （枚举里没有这个 code），那是配置错误，不是当前用户的数据状况。
            //    静默丢掉它 ⟹ AND 组少一个条件 ⟹ 过滤变宽 ⟹ 提权
            log.warn("[ConditionTreeEvaluator][未知操作符 '{}'，整条规则按求不出来处理]", node.getOperator());
            evaluation.unevaluable = true;
            return "";
        }

        // 3. 一元操作符（IS NULL / IS NOT NULL），无需值
        if (op.isUnary()) {
            return columnName + " " + op.getSqlOp();
        }

        // 4. 解析值（变量引用或字面量）
        Object resolvedValue = resolveValue(node.getValue(), variables);
        if (resolvedValue == null) {
            // ⚠️ 刻意【不】标 unevaluable：变量取不到说的是当前用户的数据状况
            //    （比如他没有部门），不是配置错误。理由见类注释
            log.warn("[ConditionTreeEvaluator][变量 '{}' 在上下文里找不到对应值，跳过此条件]", node.getValue());
            return "";
        }

        // 5. 构建 SQL 片段
        if (op.isMultiValue()) {
            // IN / NOT IN：值是集合
            Collection<?> values = toCollection(resolvedValue);
            if (values.isEmpty()) {
                // IN 空集合 = 永远为 false，NOT IN 空集合 = 永远为 true
                return op == ConditionOperatorEnum.IN ? "1=0" : "1=1";
            }
            String placeholders = values.stream().map(v -> "?").collect(Collectors.joining(", "));
            evaluation.params.addAll(values);
            return columnName + " " + op.getSqlOp() + " (" + placeholders + ")";
        }

        if (op == ConditionOperatorEnum.LIKE || op == ConditionOperatorEnum.NOT_LIKE) {
            evaluation.params.add("%" + resolvedValue + "%");
        } else {
            evaluation.params.add(resolvedValue);
        }
        return columnName + " " + op.getSqlOp() + " ?";
    }

    /**
     * 从 sys_slot_mapping 里取真实列名（列白名单），并带上它在本次查询里的限定符
     *
     * ⚠️ 这里是防注入的核心：列名来自 DB 里的可审计配置，不来自用户输入；
     * 限定符来自调用方现场给的表，{@link SlotMappingDO#getTableName()} 只当 key 用
     *
     * @return 可直接拼进 SQL 的列引用；求不出来时返回 null
     */
    private String resolveColumn(Long resourceId, String slot, Evaluation evaluation) {
        if (resourceId == null || StrUtil.isBlank(slot)) {
            return null;
        }
        SlotMappingDO mapping = slotMappingMapper.selectByResourceIdAndSlot(resourceId, slot);
        if (mapping == null) {
            log.warn("[resolveColumn][槽位 '{}' 在资源 {} 里找不到列名映射]", slot, resourceId);
            return null;
        }
        // 额外校验：列名不允许包含任何特殊字符（即使 DB 里存了坏数据也不让它通过）
        String columnName = mapping.getColumnName();
        if (columnName == null || !columnName.matches(IDENTIFIER_REGEX)) {
            log.error("[resolveColumn][列名 '{}' 包含非法字符，拒绝使用，可能是数据污染]", columnName);
            return null;
        }
        String qualifier = resolveQualifier(mapping.getTableName(), slot, resourceId, evaluation);
        return qualifier == null ? null : qualifier + columnName;
    }

    /**
     * 这个槽位的列该用哪个限定符
     *
     * @return 限定前缀（含结尾的点，无 JOIN 时是空串）；该表不在本次查询里时返回 null
     */
    private String resolveQualifier(String slotTable, String slot, Long resourceId,
                                    Evaluation evaluation) {
        if (evaluation.tableQualifiers == null) {
            // 调用方没告诉我们本次查询有哪些表（老调用口）：一律用主表前缀，
            // 与加 tableQualifiers 之前逐字节一致
            return StrUtil.nullToEmpty(evaluation.columnPrefix);
        }
        String table = StrUtil.isBlank(slotTable) ? null : slotTable.trim().toLowerCase();
        if (table == null) {
            // 空 = 主表。这是存量行的语义（见 SlotMappingDO#tableName）
            return StrUtil.nullToEmpty(evaluation.columnPrefix);
        }
        String qualifier = evaluation.tableQualifiers.get(table);
        if (qualifier == null) {
            // 🔴 槽位绑的表压根不在本次查询里。此时能做的只有让它失效 ——
            //    拿主表前缀去凑会匹配到主表的同名列（错误的过滤），
            //    拿物理表名去凑在带别名的 JOIN 上是非法 SQL。两种都比看不到数据糟糕
            log.error("[resolveQualifier][槽位 '{}'（资源 {}）绑在表 '{}' 上，"
                            + "而这张表不在本次查询里，条件求不出来。"
                            + "请把它改绑到主表、或查询真的会 JOIN 的关联表上]",
                    slot, resourceId, table);
            return null;
        }
        return qualifier;
    }

    /**
     * 解析值：变量引用（:varName）从上下文取，否则作为字面量
     */
    private Object resolveValue(String value, Map<String, Object> variables) {
        if (value == null) {
            return null;
        }
        if (value.startsWith(":")) {
            String varName = value.substring(1);
            return variables.getOrDefault(varName, null);
        }
        // 字面量：直接返回字符串，MyBatis 会做类型转换
        return value;
    }

    private Collection<?> toCollection(Object value) {
        if (value instanceof Collection<?>) {
            return (Collection<?>) value;
        }
        if (value instanceof Object[]) {
            return Arrays.asList((Object[]) value);
        }
        return List.of(value);
    }

}
