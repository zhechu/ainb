package cn.enlev.ainb.module.infra.framework.formdesign.core.print;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 打印模板里的 {@code {字段}} 占位符
 *
 * <h3>为什么用 {@code {字段}} 而不是照抄 bpm</h3>
 *
 * bpm 的打印模板用的是 wangEditor 的 mention 节点
 * （{@code <span data-w-e-type="mention" data-info="%7B%22id%22...">}）。本模块不照抄：
 * 已经有三处在用 {@code {字段}}（超链接、条码、自定义按钮的跳转地址），
 * 再引入第二套占位符语法，用户会在两种写法之间猜 —— 而猜错的表现是
 * 「占位符原样印在单据上」，没有任何报错。
 *
 * 复用同一套语法的副产品：插值直接用前端已有的 {@code interpolateTemplate}，
 * 不必给编辑器写 mention 插件。
 *
 * <h3>本类只负责「找出模板引用了哪些字段」</h3>
 *
 * 插值本身在前端做（模板是一整页 HTML，而打印窗口本来就在浏览器里）。
 * 服务端要这份清单只为一件事：<b>发布时校验引用的字段真的存在</b>。
 * 不校验的话，改了字段 ID 之后模板上那一格会静默变成空白 ——
 * 这正是本模块反复在拦的那类「配了但不生效，且不报错」。
 *
 * ⚠️ 占位符必须是完整落在一个文本节点里的。用户在编辑器里给 {@code {no}} 的中间
 * 一个字符加了粗，存下来是 {@code {n<b>o</b>}}，本类找不到它，前端插值也换不掉它。
 * 表现是占位符原样印出来 —— 这一条只能靠「用字段清单点击插入」来避免，
 * 所以设计器那边刻意提供了插入按钮，而不是只让人手打。
 *
 * @author 走进新时代
 */
public final class PrintTemplate {

    /**
     * 占位符
     *
     * ⚠️ 必须与前端 {@code interpolateTemplate} 的正则一致：
     * {@code /\{([^{}]+)\}/g}。两边不一致的表现是「设计器说字段不存在、
     * 打印出来却是好的」，或者反过来 —— 都会让人怀疑校验本身坏了。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    private PrintTemplate() {
    }

    /**
     * 子表明细的行标记
     *
     * <p>把 {@code {#子表字段}} 放进**要重复的那一行**的任意一个单元格里，
     * 这一整行就按明细行数重复，行内的 {@code {列}} 取当前明细行的值。
     *
     * <h3>⚠️ 为什么不是 {@code {#costs}…{/costs}} 这种成对的写法</h3>
     *
     * 一开始就是成对的，标记写在 {@code </tr>} 与 {@code <tr>} 之间。它在字符串层面
     * 完全正常，打印也印得对 —— 但只要在设计器里打开一次模板再存回去就废了：
     *
     * <p>HTML 的 <b>foster parenting</b> 规则规定，{@code <table>} 里不在单元格内的
     * 文本节点要被整段挪到表格<b>前面</b>。于是富文本编辑器读进来再吐出去之后，
     * 两个标记挨在一起跑到了表格外，中间什么都不剩，明细行落在了循环之外。
     * 现象是「模板没动过，打开保存一下，明细就印不出来了」—— 没有任何报错。
     *
     * <p>标记放进单元格里则不受这条规则影响。而且单标记天然没有「前后对不上」
     * 与「少写一半」这两类错误可犯。
     *
     * <p>⚠️ 必须与前端 {@code printTemplate.ts} 的 {@code ROW_MARK} 一致。
     */
    private static final Pattern ROW_MARK = Pattern.compile("\\{#([^{}/#]+)}");

    private static final String ROW_OPEN = "<tr";
    private static final String ROW_CLOSE = "</tr>";

    /** 一处行标记：它自己的位置，以及所在那一行的范围（不在行里时 rowStart 为 -1） */
    private record RowMark(String field, int markStart, int markEnd, int rowStart, int rowEnd) {
    }

    /**
     * 找出模板里的全部行标记
     *
     * <p>行边界用「从标记往两边找最近的 {@code <tr>} 与 {@code </tr>}」来定，
     * 而不是用一条匹配整行的正则 —— 后者在嵌套表格上会错。
     */
    private static List<RowMark> findRowMarks(String template) {
        List<RowMark> marks = new ArrayList<>();
        Matcher matcher = ROW_MARK.matcher(template);
        while (matcher.find()) {
            String field = matcher.group(1).trim();
            if (field.isEmpty()) {
                continue;
            }
            int markStart = matcher.start();
            int markEnd = matcher.end();
            int rowStart = template.lastIndexOf(ROW_OPEN, markStart);
            int closeAt = template.indexOf(ROW_CLOSE, markEnd);
            int rowEnd = closeAt == -1 ? -1 : closeAt + ROW_CLOSE.length();
            // 最后一条不能少：写在表格**后面**的标记，lastIndexOf 会找到上一行的
            // <tr，indexOf 又找不到 </tr> 之后的东西 —— 不校验的话会把上一行
            // 错认成标记所在的行
            boolean inRow = rowStart != -1 && rowEnd != -1
                    && template.indexOf(ROW_CLOSE, rowStart) >= markEnd;
            marks.add(new RowMark(field, markStart, markEnd,
                    inRow ? rowStart : -1, inRow ? rowEnd : -1));
        }
        return marks;
    }

    /**
     * 找出模板引用的<b>主表</b>字段
     *
     * <p>明细行整行排除在外 —— 行内的 {@code {列}} 是明细表的列，不是主表字段。
     * 混在一起校验的话会报「模板引用了不存在的字段 cost_item」，而它其实完全正常。
     *
     * @param template 模板 HTML
     * @return 字段名，按出现顺序去重。模板为空时返回空集合
     */
    public static Set<String> collectFields(String template) {
        Set<String> fields = new LinkedHashSet<>();
        if (StrUtil.isBlank(template)) {
            return fields;
        }
        Matcher matcher = PLACEHOLDER.matcher(stripDetailRows(template));
        while (matcher.find()) {
            // trim 与前端一致：用户在花括号里多打了空格时，两边取到的是同一个字段名。
            // 不 trim 的话前端能插出值、服务端却报「字段不存在」
            String field = matcher.group(1).trim();
            // 行标记自己（`#子表`）不是字段引用
            if (!field.isEmpty() && !field.startsWith("#")) {
                fields.add(field);
            }
        }
        return fields;
    }

    /** 把全部明细行整行挖掉，剩下的才是主表作用域 */
    private static String stripDetailRows(String template) {
        List<RowMark> marks = findRowMarks(template);
        StringBuilder result = new StringBuilder(template);
        // 从后往前删，前面那些标记的下标才不会被挪动
        for (int i = marks.size() - 1; i >= 0; i--) {
            RowMark mark = marks.get(i);
            if (mark.rowStart() != -1) {
                result.delete(mark.rowStart(), mark.rowEnd());
            }
        }
        return result.toString();
    }

    /**
     * 找出模板里的明细行：子表字段 -> 该行引用的列
     *
     * @param template 模板 HTML
     * @return 子表字段 -> 列名（按出现顺序去重）。没有明细行时返回空 Map
     */
    public static Map<String, Set<String>> collectLoopFields(String template) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (StrUtil.isBlank(template)) {
            return result;
        }
        for (RowMark mark : findRowMarks(template)) {
            if (mark.rowStart() == -1) {
                continue;
            }
            // 同一个子表可以有多行（比如两种版式），列要合并去重
            Set<String> columns = result.computeIfAbsent(mark.field(), key -> new LinkedHashSet<>());
            String rowHtml = template.substring(mark.rowStart(), mark.markStart())
                    + template.substring(mark.markEnd(), mark.rowEnd());
            Matcher inner = PLACEHOLDER.matcher(rowHtml);
            while (inner.find()) {
                String column = inner.group(1).trim();
                if (!column.isEmpty() && !column.startsWith("#")) {
                    columns.add(column);
                }
            }
        }
        return result;
    }

    /**
     * 找出「没放在表格行里」的行标记
     *
     * <p>值得单独拦，因为它的失败方式同样是【没有失败】：标记原样印在单据上，
     * 或者（更常见）什么都不发生 —— 设计者只看到明细印不出来，而他会先去怀疑数据。
     *
     * @return 出问题的子表字段名，按出现顺序去重。都正常时返回空集合
     */
    public static Set<String> collectStrayMarks(String template) {
        Set<String> stray = new LinkedHashSet<>();
        if (StrUtil.isBlank(template)) {
            return stray;
        }
        for (RowMark mark : findRowMarks(template)) {
            if (mark.rowStart() == -1) {
                stray.add(mark.field());
            }
        }
        return stray;
    }

}
