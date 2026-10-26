package cn.enlev.ainb.module.agent.framework.agent.core.engine.support;

import cn.hutool.json.JSONUtil;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * state 的 JSONPath-lite <b>读</b>导航器（DSL §3.2）
 *
 * <h3>为什么手写而不引 jayway JsonPath</h3>
 * §3.2 只需要一个很小的子集：顶层键、{@code $.a.b} 逐层下钻、{@code arr[i]} 数组下标（含负数）。
 * 引一个完整 JsonPath 库要么带来新依赖，要么把「过滤器/通配/递归下降」这些用不到的语法也放进来，
 * 反而给编排者「写了却不生效」的错觉（本项目最忌讳的缺陷形态）。手写 lite 版把支持范围钉死在文档子集，
 * 越界语法一律按「取不到」处理，行为可预期。
 *
 * <h3>🔴 失败返回 null，绝不抛异常</h3>
 * 与 {@link StateTemplateResolver} 的口径一致：模板里引用了一个不存在的路径，
 * 应当替成空串让流程继续，而不是让整条 workflow 因为一个占位符炸掉。
 * 任一段缺失、下标越界、路径畸形 → 统一 null。
 *
 * <h3>下钻遇到 JSON 文本会先解析（唯一的「类型宽容」）</h3>
 * 对标量继续下钻本该 null，但<b>JSON 文本</b>例外：{@code llm} 节点只能写文本，而「模型出 JSON、
 * 下游 {@code {{$.var.field}}} 取字段」是本产品的规范用法，见 {@link #parseJsonText}。
 * 除这一层之外不做任何隐式转换（数字/布尔仍按取不到处理），行为可预期。
 *
 * <h3>只做读，不做写</h3>
 * §3.2 还提到「输出 JSONPath → 顶层键 + 嵌套结构」的<b>写</b>摊平，那涉及 delta 与 KeyStrategy
 * 合并语义，风险更高，本增量不做（outputVar 仍是顶层键）。这里只负责把路径<b>读</b>出来。
 *
 * @author 走进新时代
 */
public final class StatePathNavigator {

    private StatePathNavigator() {
    }

    /**
     * 按路径从 root 取值
     *
     * <p>支持：{@code bare}（顶层键）、{@code $.a.b}、{@code a.b}、{@code arr[0]}、{@code arr[-1]}（负下标从尾部）、
     * 以及它们的组合 {@code $.a.b[0].c}。
     *
     * @param root state 的顶层数据（{@code OverAllState.data()}）
     * @param path 路径表达式
     * @return 命中的值；取不到返回 null
     */
    public static Object navigate(Map<String, Object> root, String path) {
        if (root == null || path == null) {
            return null;
        }
        String p = path.trim();
        if (p.isEmpty()) {
            return null;
        }
        // 去掉根标记 $（$.a 与 a 等价）
        if (p.charAt(0) == '$') {
            p = p.substring(1);
        }
        Object current = root;
        int i = 0;
        int n = p.length();
        while (i < n && current != null) {
            char c = p.charAt(i);
            if (c == '.') {
                // 分隔符，跳过（$.a 去掉 $ 后会以 . 开头）
                i++;
                continue;
            }
            if (c == '[') {
                int close = p.indexOf(']', i);
                if (close < 0) {
                    return null; // 畸形：有 [ 无 ]
                }
                Integer idx = parseInt(p.substring(i + 1, close).trim());
                if (idx == null) {
                    return null; // 非整数下标不在 lite 子集内
                }
                current = indexInto(current, idx);
                i = close + 1;
                continue;
            }
            // 读一段字段名，直到 . 或 [ 或结尾
            int start = i;
            while (i < n && p.charAt(i) != '.' && p.charAt(i) != '[') {
                i++;
            }
            String name = p.substring(start, i);
            if (name.isEmpty()) {
                return null; // 连续 .. 之类畸形
            }
            current = mapGet(current, name);
        }
        return current;
    }

    private static Object mapGet(Object current, String name) {
        if (current instanceof Map<?, ?> map) {
            return map.get(name);
        }
        if (current instanceof CharSequence text) {
            Object parsed = parseJsonText(text.toString());
            if (parsed instanceof Map<?, ?> map) {
                return map.get(name);
            }
        }
        return null; // 对数组/其它标量按名取值，取不到
    }

    private static Object indexInto(Object current, int index) {
        List<?> list = asList(current);
        if (list == null && current instanceof CharSequence text) {
            list = asList(parseJsonText(text.toString()));
        }
        if (list == null) {
            return null;
        }
        int i = index < 0 ? list.size() + index : index;
        if (i < 0 || i >= list.size()) {
            return null; // 越界
        }
        return list.get(i);
    }

    /**
     * JSON 文本 → 对象/数组；不是 JSON 就返回 null（调用方按「取不到」处理）
     *
     * <p>🔴 为什么读侧要兜这一层：{@code llm} 节点只能往 state 写<b>文本</b>（平台没有 JSON mode，
     * {@code NodeIoContract} 里 llm 的输出类型就是 string），而「让模型产出 JSON、下游用
     * {@code {{$.分析结果.字段}} 取字段喂工具」正是低代码编排最常规的用法，规划器提示词也按这个口径教
     * （{@code WorkflowDslPlannerPrompt} 里 tool.args 的 {@code {{$.变量名.字段}}} 说明）。
     * 不兜的话这类流程会静默拿到空参数 —— 表现是工具报「缺少 xxx」，或更糟：不报错但写了个空值。
     *
     * <p>只在<b>下钻</b>时解析：整取（{@code {{$.llm_analysis}}}）照旧返回原始文本，向后兼容。
     * 顺带剥掉模型爱加的 ```json 围栏。解析失败一律 null，与本类「绝不抛异常」的口径一致。
     */
    private static Object parseJsonText(String text) {
        String s = stripCodeFence(text.trim());
        if (s.isEmpty()) {
            return null;
        }
        try {
            if (JSONUtil.isTypeJSONObject(s)) {
                return JSONUtil.parseObj(s);
            }
            if (JSONUtil.isTypeJSONArray(s)) {
                return JSONUtil.parseArray(s);
            }
        } catch (Exception ignored) {
            // 不是合法 JSON：按取不到处理
        }
        return null;
    }

    private static String stripCodeFence(String s) {
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        String body = firstNewline > 0 ? s.substring(firstNewline + 1) : s.substring(3);
        int lastFence = body.lastIndexOf("```");
        return (lastFence >= 0 ? body.substring(0, lastFence) : body).trim();
    }

    private static List<?> asList(Object current) {
        if (current instanceof List<?> list) {
            return list;
        }
        if (current instanceof Object[] arr) {
            return List.of(arr);
        }
        if (current instanceof Collection<?> c) {
            return List.copyOf(c);
        }
        return null;
    }

    private static Integer parseInt(String s) {
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
