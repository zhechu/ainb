package cn.enlev.ainb.module.infra.framework.formdesign.core.formula;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 公式求值器（服务端权威侧）
 *
 * <h3>为什么服务端必须重算一遍</h3>
 *
 * 只有前端算的话，构造一个请求就能写入任意金额 —— 对财务类表单这是实打实的资损漏洞。
 * 所以提交时服务端用【同一份 AST】重算，并覆盖前端提交的值。
 *
 * 两边算得不一样则更糟：用户看到的合计与入库的合计不同，且没有任何报错。
 * 一致性由 form-design/formula-cases.json 保证：前端 Vitest 与后端 JUnit 读同一份用例。
 *
 * <h3>架构：一个 parser，两个 evaluator</h3>
 *
 * 表达式只在前端解析一次（设计期），产出与语言无关的 AST JSON 存进表单规则。
 * 本类只求值，不解析 —— 少一套 parser 就少一半漂移面。也因此这里【不存在】
 * 任何形式的表达式字符串执行：没有脚本引擎、没有反射调用，只有一棵受控的树。
 *
 * <h3>数值一律走 BigDecimal</h3>
 *
 * 不用 double：0.1 + 0.2 会算出 0.30000000000000004 并原样入库，
 * 1.1 * 3 会算出 3.3000000000000003。金额字段上这是硬伤。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormulaEvaluator {

    /**
     * 中间运算的精度
     *
     * 除法必须给定精度，否则 10/3 这类无限小数会抛 ArithmeticException。
     * 取 10 位：足够覆盖金额、税率、汇率的常见需求，最终精度由字段配置再收一次。
     */
    private static final int SCALE = 10;

    /**
     * 求值
     *
     * @param ast  公式 AST
     * @param data 表单数据。子表数据是对象数组
     * @return 计算结果；任何求值错误返回 null
     */
    public Object evaluate(FormulaNode ast, Map<String, Object> data) {
        if (ast == null) {
            return null;
        }
        try {
            return eval(ast, data);
        } catch (Exception e) {
            // 任何求值错误都返回 null 而不是抛异常：一个配歪的公式不该让整张表单
            // 提交失败。记 debug 而不是 warn —— 用户边填边算时空值参与运算很常见，
            // 按 warn 记会把日志刷满
            log.debug("[evaluate][公式求值失败，返回 null]", e);
            return null;
        }
    }

    /**
     * 求值并按配置收敛精度
     *
     * @param precision 保留小数位，null 表示不处理
     * @param rounding  舍入方式：HALF_UP / CEIL / FLOOR，null 按 HALF_UP
     */
    public Object evaluate(FormulaNode ast, Map<String, Object> data,
                           Integer precision, String rounding) {
        Object result = evaluate(ast, data);
        if (precision == null || !(result instanceof BigDecimal decimal)) {
            return result;
        }
        return decimal.setScale(precision, resolveRounding(rounding)).stripTrailingZeros();
    }

    /**
     * 解析舍入方式
     *
     * 默认 HALF_UP 而不是语言默认的 HALF_EVEN（银行家舍入）：
     * 后者会把 1.005 舍成 1.00，与用户对「四舍五入」的直觉不符。
     */
    private RoundingMode resolveRounding(String rounding) {
        if (rounding == null) {
            return RoundingMode.HALF_UP;
        }
        return switch (rounding.toUpperCase()) {
            case "CEIL" -> RoundingMode.CEILING;
            case "FLOOR" -> RoundingMode.FLOOR;
            default -> RoundingMode.HALF_UP;
        };
    }

    // ==================== 求值主体 ====================

    private Object eval(FormulaNode node, Map<String, Object> data) {
        if (node == null || node.getType() == null) {
            return null;
        }
        return switch (node.getType()) {
            case "num" -> toDecimal(node.getValue());
            case "str" -> node.getValue() == null ? "" : String.valueOf(node.getValue());
            case "bool" -> Boolean.TRUE.equals(node.getValue())
                    || "true".equalsIgnoreCase(String.valueOf(node.getValue()));
            case "field" -> data == null ? null : data.get(node.getName());
            // subfield 只在聚合函数的参数位置有意义，单独求值没有语义
            case "subfield" -> null;
            case "unary" -> evalUnary(node, data);
            case "binary" -> evalBinary(node, data);
            case "call" -> evalCall(node, data);
            // 未知节点类型返回 null，不抛异常：AST 可能是旧版本前端写的
            default -> null;
        };
    }

    private Object evalUnary(FormulaNode node, Map<String, Object> data) {
        Object operand = eval(node.getOperand(), data);
        if ("-".equals(node.getOp())) {
            BigDecimal d = toDecimalOrZero(operand);
            return d.negate();
        }
        if ("NOT".equalsIgnoreCase(node.getOp())) {
            return !toBoolean(operand);
        }
        return null;
    }

    private Object evalBinary(FormulaNode node, Map<String, Object> data) {
        String op = node.getOp();
        if (op == null) {
            return null;
        }
        // 逻辑运算先短路求值，避免右侧白算一遍
        if ("AND".equalsIgnoreCase(op)) {
            return toBoolean(eval(node.getLeft(), data)) && toBoolean(eval(node.getRight(), data));
        }
        if ("OR".equalsIgnoreCase(op)) {
            return toBoolean(eval(node.getLeft(), data)) || toBoolean(eval(node.getRight(), data));
        }

        Object left = eval(node.getLeft(), data);
        Object right = eval(node.getRight(), data);

        return switch (op) {
            // 空值参与算术按 0 处理 —— 表单里没填的数字字段拿到的是 null，
            // 按 0 算才符合「合计栏应该显示已填部分的和」这一直觉
            case "+" -> toDecimalOrZero(left).add(toDecimalOrZero(right));
            case "-" -> toDecimalOrZero(left).subtract(toDecimalOrZero(right));
            case "*" -> toDecimalOrZero(left).multiply(toDecimalOrZero(right)).stripTrailingZeros();
            case "/" -> divide(toDecimalOrZero(left), toDecimalOrZero(right));
            case "%" -> {
                BigDecimal r = toDecimalOrZero(right);
                yield r.signum() == 0 ? null : toDecimalOrZero(left).remainder(r);
            }
            case "=", "==" -> looseEquals(left, right);
            case "!=", "<>" -> !looseEquals(left, right);
            case ">" -> compareNumbers(left, right) > 0;
            case ">=" -> compareNumbers(left, right) >= 0;
            case "<" -> compareNumbersReverse(left, right) < 0;
            case "<=" -> compareNumbersReverse(left, right) <= 0;
            default -> null;
        };
    }

    /**
     * 除法。除数为 0 返回 null 而不是抛异常
     */
    private BigDecimal divide(BigDecimal left, BigDecimal right) {
        if (right.signum() == 0) {
            return null;
        }
        return left.divide(right, SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    // ==================== 函数 ====================

    private Object evalCall(FormulaNode node, Map<String, Object> data) {
        String name = node.getName() == null ? "" : node.getName().toUpperCase();
        List<FormulaNode> args = node.getArgsOrEmpty();

        // 聚合函数：参数是 subfield 时走聚合分支。
        // MAX/MIN 两种形态都支持，靠参数类型区分
        if (isAggregate(name) && args.size() == 1 && isSubfield(args.get(0))) {
            return aggregate(name, args.get(0), data);
        }

        return switch (name) {
            case "ROUND" -> round(args, data);
            case "CEIL" -> applyOne(args, data, d -> d.setScale(0, RoundingMode.CEILING));
            case "FLOOR" -> applyOne(args, data, d -> d.setScale(0, RoundingMode.FLOOR));
            case "ABS" -> applyOne(args, data, BigDecimal::abs);
            case "MAX" -> minMax(args, data, true);
            case "MIN" -> minMax(args, data, false);
            case "SUM", "AVG", "COUNT" ->
                // 到这里说明参数不是 subfield，聚合函数没有标量形态
                    null;
            case "CONCAT" -> concat(args, data);
            case "LEN" -> args.size() == 1
                    ? BigDecimal.valueOf(asString(eval(args.get(0), data)).length()) : null;
            case "TRIM" -> args.size() == 1 ? asString(eval(args.get(0), data)).trim() : null;
            case "SUBSTR" -> substr(args, data);
            case "IF" -> args.size() == 3
                    ? (toBoolean(eval(args.get(0), data)) ? eval(args.get(1), data)
                    : eval(args.get(2), data)) : null;
            case "IFNULL" -> {
                if (args.size() != 2) {
                    yield null;
                }
                Object first = eval(args.get(0), data);
                yield isNullish(first) ? eval(args.get(1), data) : first;
            }
            case "TODAY" -> LocalDate.now().toString();
            case "DATEDIFF" -> dateDiff(args, data);
            case "YEAR" -> datePart(args, data, LocalDate::getYear);
            case "MONTH" -> datePart(args, data, LocalDate::getMonthValue);
            case "DAY" -> datePart(args, data, LocalDate::getDayOfMonth);
            // 未知函数返回 null，不抛异常
            default -> null;
        };
    }

    private boolean isAggregate(String name) {
        return "SUM".equals(name) || "AVG".equals(name) || "COUNT".equals(name)
                || "MAX".equals(name) || "MIN".equals(name);
    }

    private boolean isSubfield(FormulaNode node) {
        return node != null && "subfield".equals(node.getType());
    }

    /**
     * 子表聚合
     *
     * 空值的处理是三条不同的规则，都有用例钉住：
     * <ul>
     *   <li>SUM 忽略空值（不是整体算成 null）</li>
     *   <li>COUNT 数所有行（包括值为空的行）</li>
     *   <li>AVG 按有值的行数平均，空值不计入分母</li>
     * </ul>
     */
    private Object aggregate(String name, FormulaNode subfield, Map<String, Object> data) {
        List<BigDecimal> values = new ArrayList<>();
        int rowCount = 0;

        Object rows = data == null ? null : data.get(subfield.getSub());
        if (rows instanceof Collection<?> collection) {
            for (Object row : collection) {
                rowCount++;
                if (row instanceof Map<?, ?> map) {
                    Object value = map.get(subfield.getField());
                    BigDecimal d = toDecimal(value);
                    if (d != null) {
                        values.add(d);
                    }
                }
            }
        }

        return switch (name) {
            // 子表为空时得 0，而不是 null：合计栏显示 0 比显示空白更符合直觉，
            // 也避免下游运算被 null 传染
            case "SUM" -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros();
            case "COUNT" -> BigDecimal.valueOf(rowCount);
            case "AVG" -> values.isEmpty() ? null
                    : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            case "MAX" -> values.stream().max(BigDecimal::compareTo).orElse(null);
            case "MIN" -> values.stream().min(BigDecimal::compareTo).orElse(null);
            default -> null;
        };
    }

    private Object round(List<FormulaNode> args, Map<String, Object> data) {
        if (args.isEmpty() || args.size() > 2) {
            return null;
        }
        BigDecimal value = toDecimal(eval(args.get(0), data));
        if (value == null) {
            return null;
        }
        int scale = args.size() == 2 ? toDecimalOrZero(eval(args.get(1), data)).intValue() : 0;
        // HALF_UP 而非 HALF_EVEN，理由见 resolveRounding
        return value.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private Object applyOne(List<FormulaNode> args, Map<String, Object> data,
                            java.util.function.UnaryOperator<BigDecimal> fn) {
        if (args.size() != 1) {
            return null;
        }
        BigDecimal value = toDecimal(eval(args.get(0), data));
        return value == null ? null : fn.apply(value).stripTrailingZeros();
    }

    private Object minMax(List<FormulaNode> args, Map<String, Object> data, boolean max) {
        if (args.isEmpty()) {
            return null;
        }
        BigDecimal result = null;
        for (FormulaNode arg : args) {
            BigDecimal d = toDecimal(eval(arg, data));
            if (d == null) {
                continue;
            }
            if (result == null) {
                result = d;
            } else {
                result = max ? result.max(d) : result.min(d);
            }
        }
        return result;
    }

    private Object concat(List<FormulaNode> args, Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        for (FormulaNode arg : args) {
            // 空值当空串，不输出 "null" 字样
            sb.append(asString(eval(arg, data)));
        }
        return sb.toString();
    }

    private Object substr(List<FormulaNode> args, Map<String, Object> data) {
        if (args.size() != 3) {
            return null;
        }
        String s = asString(eval(args.get(0), data));
        // 起始位从 1 开始计，与用户直觉一致
        int start = toDecimalOrZero(eval(args.get(1), data)).intValue() - 1;
        int len = toDecimalOrZero(eval(args.get(2), data)).intValue();
        if (start < 0 || start >= s.length() || len <= 0) {
            return "";
        }
        return s.substring(start, Math.min(s.length(), start + len));
    }

    private Object dateDiff(List<FormulaNode> args, Map<String, Object> data) {
        if (args.size() != 2) {
            return null;
        }
        LocalDate a = toDate(eval(args.get(0), data));
        LocalDate b = toDate(eval(args.get(1), data));
        if (a == null || b == null) {
            return null;
        }
        return BigDecimal.valueOf(ChronoUnit.DAYS.between(b, a));
    }

    private Object datePart(List<FormulaNode> args, Map<String, Object> data,
                            java.util.function.ToIntFunction<LocalDate> fn) {
        if (args.size() != 1) {
            return null;
        }
        LocalDate date = toDate(eval(args.get(0), data));
        return date == null ? null : BigDecimal.valueOf(fn.applyAsInt(date));
    }

    // ==================== 类型转换原语 ====================
    //
    // 这些原语的行为必须与前端逐条对齐，formula-cases.json 里都有用例。

    private boolean isNullish(Object value) {
        return value == null || (value instanceof CharSequence cs && cs.isEmpty());
    }

    /**
     * 转 BigDecimal；转不动返回 null
     */
    BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof Boolean b) {
            return b ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 转 BigDecimal，转不动按 0
     */
    private BigDecimal toDecimalOrZero(Object value) {
        BigDecimal d = toDecimal(value);
        return d != null ? d : BigDecimal.ZERO;
    }

    private boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        BigDecimal d = toDecimal(value);
        if (d != null) {
            return d.signum() != 0;
        }
        return StrUtil.isNotBlank(String.valueOf(value));
    }

    private String asString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal d) {
            // 去掉尾随零再输出，避免 1001 被写成 1001.0
            return d.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    private boolean looseEquals(Object left, Object right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        BigDecimal ln = toDecimal(left);
        BigDecimal rn = toDecimal(right);
        if (ln != null && rn != null) {
            return ln.compareTo(rn) == 0;
        }
        return asString(left).equals(asString(right));
    }

    /**
     * 数值比较，用于 &gt; 与 &gt;=
     *
     * 任一侧不是数字时返回 -1，使条件判为不满足。
     */
    private int compareNumbers(Object left, Object right) {
        BigDecimal ln = toDecimal(left);
        BigDecimal rn = toDecimal(right);
        if (ln == null || rn == null) {
            return -1;
        }
        return ln.compareTo(rn);
    }

    /**
     * 数值比较，用于 &lt; 与 &lt;=
     *
     * 与 {@link #compareNumbers} 的区别只在「无法比较时返回什么」：
     * 若也返回 -1，&lt; 就会变成「不是数字反而判为满足」，方向正好错了。
     * 这与联动规则求值器里的同名取舍是一致的。
     */
    private int compareNumbersReverse(Object left, Object right) {
        BigDecimal ln = toDecimal(left);
        BigDecimal rn = toDecimal(right);
        if (ln == null || rn == null) {
            return 1;
        }
        return ln.compareTo(rn);
    }

    private LocalDate toDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            // 只认 ISO 日期与「日期 时间」前缀，不做模糊猜测 ——
            // 猜错日期格式比算不出来更危险（月日颠倒不会报错，只会算错天数）
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ==================== AST 节点 ====================

    /**
     * 公式 AST 节点
     *
     * 一个扁平类承载所有节点形态，而不是搞多态反序列化：Jackson 的多态需要类型标记
     * 字段，那会让 AST JSON 里多出纯技术性的 {@code @type}，而 type 字段本身就是判别键。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FormulaNode {

        /**
         * 节点类型：num / str / bool / field / subfield / unary / binary / call
         */
        private String type;
        /**
         * 字面量的值
         */
        private Object value;
        /**
         * 字段名（field）或函数名（call）
         */
        private String name;
        /**
         * 子表控件字段（subfield）
         */
        private String sub;
        /**
         * 子表内部字段（subfield）
         */
        private String field;
        /**
         * 运算符（unary / binary）
         */
        private String op;
        /**
         * 一元运算的操作数
         */
        private FormulaNode operand;
        /**
         * 二元运算的左操作数
         */
        private FormulaNode left;
        /**
         * 二元运算的右操作数
         */
        private FormulaNode right;
        /**
         * 函数参数
         */
        private List<FormulaNode> args;

        public List<FormulaNode> getArgsOrEmpty() {
            return args != null ? args : List.of();
        }

    }

}
