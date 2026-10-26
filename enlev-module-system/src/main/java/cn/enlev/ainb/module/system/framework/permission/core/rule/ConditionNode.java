package cn.enlev.ainb.module.system.framework.permission.core.rule;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 条件树节点（DSL 数据结构）
 *
 * 一棵条件树可以表示任意复杂的行过滤条件，如：
 * <pre>
 *   {owner} = :currentUserId
 *   OR ({dept} = :currentDeptId AND {status} IN (1, 2))
 * </pre>
 *
 * JSON 格式（存在 sys_rule.params 的 conditionTree 字段里）：
 * <pre>
 * {
 *   "type": "GROUP",
 *   "logic": "OR",
 *   "children": [
 *     {"type": "CONDITION", "slot": "owner", "operator": "EQ", "value": ":currentUserId"},
 *     {"type": "GROUP", "logic": "AND", "children": [
 *       {"type": "CONDITION", "slot": "dept", "operator": "EQ", "value": ":currentDeptId"},
 *       {"type": "CONDITION", "slot": "status", "operator": "IN", "value": ":allowedStatuses"}
 *     ]}
 *   ]
 * }
 * </pre>
 *
 * ## 防注入设计
 *
 * slot 字段只是语义槽位名（如 "owner"），不是真实列名。
 * 真实列名从 sys_slot_mapping 里按白名单取，不从用户输入里取。
 * 操作符来自 ConditionOperatorEnum 枚举，不允许任意字符串。
 * 参数值要么是变量引用（以 : 开头），要么是字面量 —— S3b 的解析器统一做参数化。
 *
 * @author 走进新时代
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ConditionNode {

    /**
     * 节点类型：GROUP（逻辑组）或 CONDITION（单个条件）
     */
    private NodeType type;

    // ===== GROUP 类型 =====

    /**
     * 逻辑连接：AND 或 OR
     * type = GROUP 时有效
     */
    private LogicOperator logic;

    /**
     * 子节点列表
     * type = GROUP 时有效
     */
    private List<ConditionNode> children;

    // ===== CONDITION 类型 =====

    /**
     * 语义槽位名，如 owner / dept / status
     * type = CONDITION 时有效
     * ⚠️ 这里只存槽位名，不存真实列名！真实列名从 sys_slot_mapping 里白名单取
     */
    private String slot;

    /**
     * 操作符，见 ConditionOperatorEnum
     * type = CONDITION 时有效
     */
    private String operator;

    /**
     * 条件值：变量引用（如 :currentUserId）或字面量（如 "1" / "[1,2,3]"）
     * type = CONDITION 时有效
     */
    private String value;

    /**
     * 快捷建 GROUP 节点
     */
    public static ConditionNode group(LogicOperator logic, List<ConditionNode> children) {
        ConditionNode node = new ConditionNode();
        node.setType(NodeType.GROUP);
        node.setLogic(logic);
        node.setChildren(children);
        return node;
    }

    /**
     * 快捷建 CONDITION 节点
     */
    public static ConditionNode condition(String slot, String operator, String value) {
        ConditionNode node = new ConditionNode();
        node.setType(NodeType.CONDITION);
        node.setSlot(slot);
        node.setOperator(operator);
        node.setValue(value);
        return node;
    }

    public boolean isGroup() {
        return NodeType.GROUP.equals(type);
    }

    public boolean isCondition() {
        return NodeType.CONDITION.equals(type);
    }

    /** 节点类型 */
    public enum NodeType {
        GROUP, CONDITION
    }

    /** 逻辑连接 */
    public enum LogicOperator {
        AND, OR
    }

}
