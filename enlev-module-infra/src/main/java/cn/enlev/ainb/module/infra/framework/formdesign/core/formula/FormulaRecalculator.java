package cn.enlev.ainb.module.infra.framework.formdesign.core.formula;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 公式字段的服务端权威重算
 *
 * <h3>它防的是什么</h3>
 *
 * 前端算出来的公式值只是即时反馈。绕过界面直接构造一个提交请求，就能给
 * 「金额合计」「税额」这类公式字段塞任意值 —— 对财务类表单这是实打实的资损漏洞。
 *
 * 所以提交时服务端用同一份 AST 重算，并【覆盖】前端提交的值。
 * 不是「校验前端算得对不对然后报错」，而是直接以服务端的结果为准：
 * 前端版本可能比后端旧（用户开着老页面没刷新），报错会让正常操作莫名失败，
 * 而覆盖既安全又不打扰用户。
 *
 * <h3>依赖顺序</h3>
 *
 * 公式可以引用别的公式字段（税额 = 小计 * 税率，小计 = SUM(明细.金额)）。
 * 按依赖拓扑排序后依次计算，保证被引用的公式先算完。
 * 有环时跳过环上的字段并记日志 —— 发布校验本该拦住环，这里是第二道防线。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormulaRecalculator {

    /**
     * 公式控件在 form-create 规则里的 type
     */
    public static final String FORMULA_WIDGET_TYPE = "Formula";

    @Resource
    private FormulaEvaluator evaluator;

    @Resource
    private WidgetContractRegistry contractRegistry;

    /**
     * 重算表单里的全部公式字段
     *
     * @param formFieldsJson 表单字段规则 JSON
     * @param data           提交数据。本方法不修改入参
     * @return 重算后的数据
     */
    public Map<String, Object> recalculate(String formFieldsJson, Map<String, Object> data) {
        List<FormulaField> formulas = parseFormulaFields(formFieldsJson);
        if (formulas.isEmpty()) {
            return data != null ? new LinkedHashMap<>(data) : new LinkedHashMap<>();
        }

        Map<String, Object> result = data != null ? new LinkedHashMap<>(data) : new LinkedHashMap<>();

        // 子表内的公式先算：主表公式可能用聚合函数引用明细金额，
        // 顺序反了的话主表拿到的是还没算过的明细
        recalculateSubTables(formulas, result);

        List<FormulaField> mainFormulas = formulas.stream()
                .filter(formula -> formula.getSubField() == null)
                .toList();
        if (mainFormulas.isEmpty()) {
            return result;
        }
        List<FormulaField> ordered = topologicalSort(mainFormulas);

        for (FormulaField formula : ordered) {
            Object computed = evaluator.evaluate(
                    formula.getAst(), result, formula.getPrecision(), formula.getRounding());
            Object submitted = result.get(formula.getField());
            // 覆盖前端提交的值。相等时也照样写，保证类型统一（前端可能提交字符串 "35"）
            result.put(formula.getField(), normalize(computed));

            if (!valueEquals(submitted, computed)) {
                // 记 info 而不是 warn：用户开着老页面、或前端还没来得及重算就提交，
                // 都会让两边不一致，这是常态而非异常。真正的越权尝试也会落在这条日志里，
                // 需要时可以按字段名检索
                log.info("[recalculate][公式字段({})的提交值({})与服务端重算值({})不一致，已按服务端结果覆盖]",
                        formula.getField(), submitted, computed);
            }
        }
        return result;
    }

    /**
     * 把结果收敛成适合入库的类型
     *
     * BigDecimal 直接留着 —— JDBC 能正确写入数值列，转成 double 反而会引回精度问题。
     */
    private Object normalize(Object value) {
        return value;
    }

    private boolean valueEquals(Object submitted, Object computed) {
        if (submitted == null || computed == null) {
            return submitted == null && computed == null;
        }
        BigDecimal a = evaluator.toDecimal(submitted);
        BigDecimal b = evaluator.toDecimal(computed);
        if (a != null && b != null) {
            return a.compareTo(b) == 0;
        }
        return Objects.equals(String.valueOf(submitted), String.valueOf(computed));
    }

    // ==================== 解析 ====================

    /**
     * 从表单字段规则里提取全部公式字段
     *
     * 要递归：公式控件可能被放在栅格、标签页等布局容器里。
     */
    public List<FormulaField> parseFormulaFields(String formFieldsJson) {
        List<FormulaField> result = new ArrayList<>();
        List<String> rules = FormFieldsCodec.read(formFieldsJson);
        for (String rule : rules) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collect(node, result);
            }
        }
        return result;
    }

    /**
     * 子表内公式的逐行重算
     *
     * 作用域是【这一行明细】：{@code {qty} * {price}} 里的 qty/price 指的是同一行上的
     * 两个值，而不是主表上的同名字段。不区分作用域的话，它会去主表上找，
     * 找不到就恒定算出 0 —— 配了公式却永远是 0，而且不报错。
     *
     * 明细行不是 Map（脏数据）时跳过而不是抛异常：一行坏数据不该让整次提交失败，
     * 子表校验那一步会用更准确的话报出来。
     */
    @SuppressWarnings("unchecked")
    private void recalculateSubTables(List<FormulaField> formulas, Map<String, Object> result) {
        Map<String, List<FormulaField>> bySub = new LinkedHashMap<>();
        for (FormulaField formula : formulas) {
            if (formula.getSubField() != null) {
                bySub.computeIfAbsent(formula.getSubField(), key -> new ArrayList<>()).add(formula);
            }
        }
        for (Map.Entry<String, List<FormulaField>> entry : bySub.entrySet()) {
            Object rows = result.get(entry.getKey());
            if (!(rows instanceof List<?> rowList)) {
                continue;
            }
            List<FormulaField> ordered = topologicalSort(entry.getValue());
            for (Object row : rowList) {
                if (!(row instanceof Map<?, ?> rowMap)) {
                    continue;
                }
                Map<String, Object> rowData = (Map<String, Object>) rowMap;
                for (FormulaField formula : ordered) {
                    Object computed = evaluator.evaluate(formula.getAst(), rowData,
                            formula.getPrecision(), formula.getRounding());
                    // 与主表一致：相等时也照样写，保证类型统一（前端可能提交字符串）
                    rowData.put(formula.getField(), normalize(computed));
                }
            }
        }
    }

    /**
     * 解析「子表控件字段名 → 它内部的字段名集合」
     *
     * 供发布校验用：子表里的公式引用的是【同一行明细】上的字段，
     * 拿主表字段集去校验会把正常配置误判成「引用了不存在的字段」。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Set<String>> parseSubTableFields(String formFieldsJson) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String rule : FormFieldsCodec.read(formFieldsJson)) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collectSubTableFields(node, result);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void collectSubTableFields(Map<String, Object> node, Map<String, Set<String>> result) {
        if (StrUtil.equals(subTableWidgetType(), StrUtil.toStringOrNull(node.get("type")))
                && node.get("field") instanceof String subField && StrUtil.isNotBlank(subField)
                && node.get("props") instanceof Map<?, ?> propsMap) {
            Set<String> fields = result.computeIfAbsent(subField, key -> new LinkedHashSet<>());
            Object innerRules = ((Map<String, Object>) propsMap).get("rule");
            if (innerRules instanceof List<?> innerList) {
                for (Object inner : innerList) {
                    if (inner instanceof Map<?, ?> innerMap) {
                        collectInnerFieldNames((Map<String, Object>) innerMap, fields);
                    }
                }
            }
        }
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collectSubTableFields((Map<String, Object>) childMap, result);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectInnerFieldNames(Map<String, Object> node, Set<String> out) {
        if (node.get("field") instanceof String field && StrUtil.isNotBlank(field)) {
            out.add(field);
        }
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collectInnerFieldNames((Map<String, Object>) childMap, out);
                }
            }
        }
    }

    /** 子表控件的 type。契约是它的唯一来源，取不到时退回内核默认值 */
    private String subTableWidgetType() {
        String type = contractRegistry != null ? contractRegistry.getSubTableWidgetType() : null;
        return StrUtil.isNotBlank(type) ? type : "group";
    }

    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> node, List<FormulaField> result) {
        collect(node, result, null);
    }

    /**
     * 递归收集公式字段
     *
     * @param owner 所属子表控件的字段名。为 null 表示主表作用域
     */
    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> node, List<FormulaField> result, String owner) {
        if (FORMULA_WIDGET_TYPE.equals(node.get("type"))
                && node.get("field") instanceof String field && StrUtil.isNotBlank(field)) {
            Object props = node.get("props");
            if (props instanceof Map<?, ?> propsMap) {
                FormulaField formula = readFormula(field, (Map<String, Object>) propsMap);
                if (formula != null) {
                    formula.setSubField(owner);
                    result.add(formula);
                }
            }
        }
        // 布局容器的子控件在 children 里，与外层同作用域
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collect((Map<String, Object>) childMap, result, owner);
                }
            }
        }
        // ⚠️ 子表控件的内部规则挂在 props.rule 下（不是 children），且属于【明细行】作用域。
        // 不走这一支的话，子表里的公式根本不会被收集到 —— 服务端从不重算它们，
        // 前端提交什么就存什么，而这与本模块「推导值一律服务端重算覆盖」的原则相悖
        // 用 equals(常量, 值) 的顺序：契约没装配好时 getSubTableWidgetType() 可能为 null，
        // 反过来写会 NPE，而「契约缺一项」不该让整次提交炸掉
        if (StrUtil.equals(subTableWidgetType(), StrUtil.toStringOrNull(node.get("type")))
                && node.get("field") instanceof String subField && StrUtil.isNotBlank(subField)
                && node.get("props") instanceof Map<?, ?> propsMap) {
            Object innerRules = ((Map<String, Object>) propsMap).get("rule");
            if (innerRules instanceof List<?> innerList) {
                for (Object inner : innerList) {
                    if (inner instanceof Map<?, ?> innerMap) {
                        collect((Map<String, Object>) innerMap, result, subField);
                    }
                }
            }
        }
    }

    private FormulaField readFormula(String field, Map<String, Object> props) {
        Object astRaw = props.get("ast");
        if (astRaw == null) {
            // 没有 AST 说明用户还没写公式，或者写了但没解析成功（前端会拦住保存）。
            // 跳过而不是报错 —— 一个没配好的公式字段不该让整张表单提交不了
            return null;
        }
        FormulaEvaluator.FormulaNode ast = JsonUtils.parseObjectQuietly(
                JsonUtils.toJsonString(astRaw), FormulaEvaluator.FormulaNode.class);
        if (ast == null) {
            log.warn("[readFormula][公式字段({})的 AST 解析失败，本次提交跳过该字段的重算]", field);
            return null;
        }
        FormulaField formula = new FormulaField();
        formula.setField(field);
        formula.setAst(ast);
        formula.setExpression(props.get("expression") instanceof String s ? s : null);
        if (props.get("precision") instanceof Number n) {
            formula.setPrecision(n.intValue());
        }
        formula.setRounding(props.get("rounding") instanceof String s ? s : null);
        formula.setDependencies(collectFieldRefs(ast));
        return formula;
    }

    /**
     * 收集 AST 里引用到的普通字段
     *
     * 子表字段不算依赖：它来自明细数据，不会被别的公式赋值。
     */
    private Set<String> collectFieldRefs(FormulaEvaluator.FormulaNode node) {
        Set<String> refs = new HashSet<>();
        walkRefs(node, refs);
        return refs;
    }

    private void walkRefs(FormulaEvaluator.FormulaNode node, Set<String> refs) {
        if (node == null) {
            return;
        }
        if ("field".equals(node.getType()) && StrUtil.isNotBlank(node.getName())) {
            refs.add(node.getName());
        }
        walkRefs(node.getOperand(), refs);
        walkRefs(node.getLeft(), refs);
        walkRefs(node.getRight(), refs);
        for (FormulaEvaluator.FormulaNode arg : node.getArgsOrEmpty()) {
            walkRefs(arg, refs);
        }
    }

    // ==================== 依赖排序 ====================

    /**
     * 按依赖拓扑排序
     *
     * 公式可以引用别的公式字段。不排序的话，「税额 = 小计 * 0.06」可能在「小计」
     * 还没算出来时就先算，拿到的是前端提交的旧值 —— 而那正是我们不信任的值。
     *
     * 有环时把环上的字段追加在末尾并记日志。发布校验本该拦住环，
     * 这里是第二道防线 —— 直接抛异常会让一张有历史遗留问题的表单彻底提交不了。
     */
    private List<FormulaField> topologicalSort(List<FormulaField> formulas) {
        Map<String, FormulaField> byField = new LinkedHashMap<>();
        formulas.forEach(f -> byField.put(f.getField(), f));

        List<FormulaField> ordered = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        Set<String> inCycle = new HashSet<>();

        for (FormulaField formula : formulas) {
            visit(formula, byField, done, visiting, inCycle, ordered);
        }
        // 环上的字段照样算一遍，只是顺序无保证
        for (FormulaField formula : formulas) {
            if (!done.contains(formula.getField())) {
                ordered.add(formula);
            }
        }
        if (!inCycle.isEmpty()) {
            log.warn("[topologicalSort][公式字段存在循环引用：{}，这些字段的计算顺序不确定，"
                    + "结果可能不符合预期。发布校验应当拦住这种配置]", inCycle);
        }
        return ordered;
    }

    private void visit(FormulaField formula, Map<String, FormulaField> byField,
                       Set<String> done, Set<String> visiting, Set<String> inCycle,
                       List<FormulaField> ordered) {
        String field = formula.getField();
        if (done.contains(field)) {
            return;
        }
        if (visiting.contains(field)) {
            inCycle.add(field);
            return;
        }
        visiting.add(field);
        for (String dep : formula.getDependencies()) {
            FormulaField depFormula = byField.get(dep);
            // 只有依赖也是公式字段才需要排序；普通字段的值是现成的
            if (depFormula != null) {
                visit(depFormula, byField, done, visiting, inCycle, ordered);
            }
        }
        visiting.remove(field);
        if (done.add(field)) {
            ordered.add(formula);
        }
    }

    // ==================== 模型 ====================

    /**
     * 一个公式字段
     */
    @Data
    public static class FormulaField {

        /**
         * 字段名
         */
        private String field;
        /**
         * 原始表达式，仅用于报错提示
         */
        private String expression;
        /**
         * 公式 AST
         */
        private FormulaEvaluator.FormulaNode ast;
        /**
         * 结果保留小数位
         */
        private Integer precision;
        /**
         * 舍入方式
         */
        private String rounding;
        /**
         * 所属子表控件的字段名。为 null 表示这是主表上的公式
         *
         * 子表里的公式按【行】求值，作用域是那一行明细。
         */
        private String subField;
        /**
         * 依赖的普通字段（含其它公式字段）
         */
        private Set<String> dependencies = new HashSet<>();

    }

}
