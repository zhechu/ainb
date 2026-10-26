package cn.enlev.ainb.module.infra.framework.formdesign.core.logic;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 联动规则的环形赋值检测
 *
 * 保存/发布时检测，而不是等运行时才发现。
 *
 * <h3>为什么要单独做这件事</h3>
 *
 * 求值器已经能容忍不收敛（跑满轮数后标记 stable=false 并记日志，不抛异常），
 * 所以环形规则不会让系统崩掉。但对用户来说，那意味着「我配的联动有时生效有时不生效」，
 * 而日志在服务器上、他看不到。在保存阶段直接告诉他「这两条规则互相赋值」，
 * 是把问题反馈提前到他还记得自己刚配了什么的时候。
 *
 * <h3>只检测赋值环，不检测状态环</h3>
 *
 * 只有 SET_VALUE / CLEAR_VALUE 会改数据、进而改变其它规则的条件判断，
 * 才可能形成反复触发。显隐必填这类状态动作不参与条件判断，
 * 每轮都从基础状态重算，不会形成环。
 *
 * 因此建图只看：规则条件引用的字段 → 规则赋值的字段。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class LogicRuleCycleDetector {

    /**
     * 检测环
     *
     * @param rules 规则列表
     * @return 找到的环，每个元素形如 "a → b → a"。没有环时返回空列表
     */
    public List<String> detect(List<LogicRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return Collections.emptyList();
        }

        // 建图：被赋值的字段 → 该赋值所依赖的条件字段
        // 方向取「依赖」而不是「影响」：找到的环读起来就是「a 依赖 b，b 依赖 a」
        Map<String, Set<String>> dependsOn = new HashMap<>();
        for (LogicRule rule : rules) {
            if (rule == null || !rule.isEnabled()) {
                continue;
            }
            Set<String> conditionFields = new LinkedHashSet<>();
            collectConditionFields(rule.getWhen(), conditionFields);
            if (conditionFields.isEmpty()) {
                // 无条件规则不会被别的字段触发，不可能成环
                continue;
            }
            Set<String> assignedFields = new LinkedHashSet<>();
            collectAssignedFields(rule.getThenOrEmpty(), assignedFields);
            collectAssignedFields(rule.getOtherwiseOrEmpty(), assignedFields);

            for (String assigned : assignedFields) {
                dependsOn.computeIfAbsent(assigned, k -> new LinkedHashSet<>())
                        .addAll(conditionFields);
            }
        }

        return findCycles(dependsOn);
    }

    /**
     * 收集条件里引用到的字段
     *
     * valueType=FIELD 的右值也算引用 —— 那同样会让该字段的变化影响本条件的结果。
     */
    private void collectConditionFields(LogicRule.ConditionGroup group, Set<String> result) {
        if (group == null) {
            return;
        }
        for (LogicRule.ConditionNode node : group.getItemsOrEmpty()) {
            if (node == null) {
                continue;
            }
            if (node.isGroup()) {
                collectConditionFields(node.asGroup(), result);
                continue;
            }
            if (StrUtil.isNotBlank(node.getField())) {
                result.add(node.getField());
            }
            if (node.getValueTypeOrDefault() == LogicEnums.ValueType.FIELD) {
                String ref = node.getValue() == null ? null : String.valueOf(node.getValue());
                if (StrUtil.isNotBlank(ref)) {
                    result.add(ref);
                }
            }
        }
    }

    /**
     * 收集动作里被赋值的字段
     *
     * 只看会改数据的动作，理由见类注释。
     */
    private void collectAssignedFields(List<LogicRule.Action> actions, Set<String> result) {
        for (LogicRule.Action action : actions) {
            if (action == null || StrUtil.isBlank(action.getTarget())) {
                continue;
            }
            LogicEnums.ActionType type = action.resolveType();
            if (type == LogicEnums.ActionType.SET_VALUE
                    || type == LogicEnums.ActionType.CLEAR_VALUE) {
                result.add(action.getTarget());
            }
        }
    }

    /**
     * 在依赖图上找环
     *
     * 用带路径的深度优先搜索。递归栈里再次遇到同一个节点即成环，
     * 此时从路径里把环截出来 —— 只报「环上有哪些字段」，不报「路径是怎么走到环上的」，
     * 后者对用户没有意义。
     */
    private List<String> findCycles(Map<String, Set<String>> graph) {
        List<String> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        // 同一个环可能从不同起点被走到，用规范化后的表示去重
        Set<String> reported = new HashSet<>();

        for (String node : graph.keySet()) {
            if (!visited.contains(node)) {
                dfs(node, graph, visited, onStack, path, cycles, reported);
            }
        }
        return cycles;
    }

    private void dfs(String node, Map<String, Set<String>> graph, Set<String> visited,
                     Set<String> onStack, Deque<String> path, List<String> cycles,
                     Set<String> reported) {
        visited.add(node);
        onStack.add(node);
        path.addLast(node);

        for (String next : graph.getOrDefault(node, Collections.emptySet())) {
            if (onStack.contains(next)) {
                String cycle = extractCycle(path, next);
                // 规范化：环上字段排序后作为去重键，避免同一个环报多遍
                String key = String.join(",", new java.util.TreeSet<>(List.of(cycle.split(" → "))));
                if (reported.add(key)) {
                    cycles.add(cycle);
                }
                continue;
            }
            if (!visited.contains(next)) {
                dfs(next, graph, visited, onStack, path, cycles, reported);
            }
        }

        path.removeLast();
        onStack.remove(node);
    }

    /**
     * 从当前路径里截出环
     */
    private String extractCycle(Deque<String> path, String start) {
        List<String> nodes = new ArrayList<>(path);
        int idx = nodes.indexOf(start);
        List<String> cycleNodes = idx >= 0 ? nodes.subList(idx, nodes.size()) : nodes;
        return String.join(" → ", cycleNodes) + " → " + start;
    }

}
