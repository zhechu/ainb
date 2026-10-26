package cn.enlev.ainb.module.infra.framework.formdesign.core.print;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打印模板占位符解析的测试
 *
 * 这些用例守的是一件事：**服务端认出的占位符集合，与前端插值时替换掉的那一批，
 * 必须是同一批**。两边不一致的表现很误导人 —— 设计器说「字段不存在」而打印出来是好的，
 * 或者反过来发布通过、单据上一片空白。所以每一条边界（空格、嵌套、跨标签）
 * 都对着前端 `interpolateTemplate` 的正则 `/\{([^{}]+)\}/g` 钉一条。
 *
 * @author 走进新时代
 */
class PrintTemplateTest {

    @Test
    @DisplayName("提取占位符，按出现顺序去重")
    void testCollectFields() {
        Set<String> fields = PrintTemplate.collectFields(
                "<p>单号：{bill_no}</p><p>客户：{customer}</p><p>再来一次：{bill_no}</p>");
        assertEquals(List.of("bill_no", "customer"), List.copyOf(fields));
    }

    @Test
    @DisplayName("花括号里的空白被去掉 —— 与前端的 trim 一致")
    void testTrim() {
        // 不 trim 的话，前端能插出值、服务端却报「字段不存在」
        assertEquals(Set.of("amount"), PrintTemplate.collectFields("{ amount }"));
        assertEquals(Set.of("amount"), PrintTemplate.collectFields("{\namount\t}"));
    }

    @Test
    @DisplayName("空模板与不含占位符的模板都返回空集合")
    void testBlank() {
        assertTrue(PrintTemplate.collectFields(null).isEmpty());
        assertTrue(PrintTemplate.collectFields("").isEmpty());
        assertTrue(PrintTemplate.collectFields("   ").isEmpty());
        assertTrue(PrintTemplate.collectFields("<h1>出库单</h1>").isEmpty());
    }

    @Test
    @DisplayName("空花括号不算占位符")
    void testEmptyPlaceholder() {
        // `{}` 与 `{  }` 在前端会被替换成空串（字段名是空的，取不到值），
        // 但它们不构成「引用了某个字段」，不该被当成一次字段引用去校验存在性
        assertTrue(PrintTemplate.collectFields("{}").isEmpty());
        assertTrue(PrintTemplate.collectFields("{   }").isEmpty());
    }

    @Test
    @DisplayName("嵌套花括号只取最内层 —— 与前端的 [^{}]+ 一致")
    void testNested() {
        // 正则不允许内容里含花括号，所以 `{{a}}` 里被认出的是 `a`，外层的花括号原样留下。
        // 这一条不是「想要的行为」，而是「前端就是这么替换的」—— 服务端必须跟它一样，
        // 否则会出现「发布通过、印出来带一对多余的花括号」或反过来
        assertEquals(Set.of("a"), PrintTemplate.collectFields("{{a}}"));
    }

    @Test
    @DisplayName("跨标签被撕开的占位符认不出来 —— 这是已知边界")
    void testSplitAcrossTags() {
        // 用户在编辑器里给 {no} 的中间一个字符加了粗，存下来是 {n<b>o</b>}。
        // 服务端认出的字段名是 `n<b>o</b>`，前端也一样取不到值 —— 两边一致，
        // 但发布会因为「字段 n<b>o</b> 不存在」被拦下，而那恰恰是想要的：
        // 放过去的话，占位符会原样印在单据上
        Set<String> fields = PrintTemplate.collectFields("{n<b>o</b>}");
        assertEquals(Set.of("n<b>o</b>"), fields);
    }

    @Test
    @DisplayName("换行与多段 HTML 之间的占位符都能找到")
    void testAcrossHtml() {
        String template = """
                <table>
                  <tr><td>单号</td><td>{bill_no}</td></tr>
                  <tr><td>金额</td><td>{amount}</td></tr>
                </table>
                """;
        assertEquals(List.of("bill_no", "amount"),
                List.copyOf(PrintTemplate.collectFields(template)));
    }

    // ================= 子表明细的行标记 =================
    //
    // 与前端 printTemplate.spec.ts 的同名一组是同一批边界。
    // 两边不一致的表现与占位符那条一样：设计器说没问题、发布被拒，或反过来。

    /** 与设计器「插入明细表格」产出的结构一致 */
    private static final String ROW_TPL =
            "<table><thead><tr><th>项目</th><th>金额</th></tr></thead>"
                    + "<tbody><tr><td>{#costs}{cost_item}</td><td>{cost_amount}</td></tr></tbody>"
                    + "</table>";

    @Test
    @DisplayName("明细行里的列不算主表字段 —— 否则会报「字段不存在」，而它其实正常")
    void testDetailColumnsExcludedFromMain() {
        String template = "<p>{project_code}</p>" + ROW_TPL + "<p>{owner_name}</p>";
        assertEquals(List.of("project_code", "owner_name"),
                List.copyOf(PrintTemplate.collectFields(template)));
    }

    @Test
    @DisplayName("行标记自己不算一次字段引用")
    void testMarkIsNotField() {
        assertFalse(PrintTemplate.collectFields(ROW_TPL).contains("#costs"));
    }

    @Test
    @DisplayName("明细行的子表与行内的列单独收集")
    void testCollectLoopFields() {
        Map<String, Set<String>> loops = PrintTemplate.collectLoopFields(ROW_TPL);
        assertEquals(Set.of("costs"), loops.keySet());
        assertEquals(List.of("cost_item", "cost_amount"), List.copyOf(loops.get("costs")));
    }

    @Test
    @DisplayName("同一个子表有两行时，行内的列合并去重")
    void testLoopFieldsMerged() {
        String template = "<table><tbody>"
                + "<tr><td>{#c}{a}</td></tr><tr><td>{#c}{a}{b}</td></tr>"
                + "</tbody></table>";
        assertEquals(List.of("a", "b"),
                List.copyOf(PrintTemplate.collectLoopFields(template).get("c")));
    }

    @Test
    @DisplayName("两张明细表各自收集，互不干扰")
    void testTwoDetailTables() {
        String template = "<table><tbody><tr><td>{#ms}{n}</td></tr></tbody></table>"
                + "<table><tbody><tr><td>{#cost}{c}</td></tr></tbody></table>";
        Map<String, Set<String>> loops = PrintTemplate.collectLoopFields(template);
        assertEquals(Set.of("ms", "cost"), loops.keySet());
        assertEquals(Set.of("n"), loops.get("ms"));
        assertEquals(Set.of("c"), loops.get("cost"));
    }

    @Test
    @DisplayName("没有明细行时返回空 Map")
    void testNoDetailRow() {
        assertTrue(PrintTemplate.collectLoopFields(null).isEmpty());
        assertTrue(PrintTemplate.collectLoopFields("<p>{a}{b}</p>").isEmpty());
    }

    @Test
    @DisplayName("⚠️ 标记没放在表格行里时算游离，留给发布校验拦")
    void testStrayMark() {
        // 放在表格外面不会报错，只是明细一行都印不出来
        assertEquals(Set.of("costs"), PrintTemplate.collectStrayMarks("<p>{#costs}</p>"));
        assertTrue(PrintTemplate.collectLoopFields("<p>{#costs}</p>").isEmpty());
    }

    @Test
    @DisplayName("⚠️ 表格后面的游离标记不会把上一行错认成自己所在的行")
    void testStrayMarkAfterTable() {
        String template = "<table><tbody><tr><td>{a}</td></tr></tbody></table><p>{#costs}</p>";
        assertEquals(Set.of("costs"), PrintTemplate.collectStrayMarks(template));
    }

    @Test
    @DisplayName("放对位置的标记不算游离")
    void testNoStrayMark() {
        assertTrue(PrintTemplate.collectStrayMarks(ROW_TPL).isEmpty());
        assertTrue(PrintTemplate.collectStrayMarks(null).isEmpty());
    }

}
