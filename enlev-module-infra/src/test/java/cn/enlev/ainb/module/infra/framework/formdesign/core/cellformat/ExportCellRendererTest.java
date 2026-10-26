package cn.enlev.ainb.module.infra.framework.formdesign.core.cellformat;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ExportCellRenderer} 的单元测试
 *
 * <h3>这一层在守什么</h3>
 *
 * 「列表怎么显示，导出就怎么导」这句话能不能兑现，全看这里。
 * 改造前导出只读 label 不读 formatter，于是：
 * 字典列导出 {@code MAKE}、布尔列导出 {@code 1.0}、
 * 金额列配了 2 位小数却导出 1 位 —— 后两个已经不是「不好看」，是数据错了。
 *
 * <h3>为什么数值/日期要断言「值 + 格式」而不是文本</h3>
 *
 * 导出的文件还要能改完导回来。把 {@code 8,500,000.00} 当文本写进去，
 * 回导时要再解析一遍，而解析必然有歧义（{@code 13.00%} 是 0.13 还是 13？）。
 * 写真值 + numFmt 则两全：用户看到的和列表一样，单元格真值仍是 8500000。
 *
 * <p>字典翻译依赖 Spring 的字典缓存，不在这里测 —— 它由接口层冒烟覆盖。
 * 这里只测「翻不出来时原样返回编码」这条兜底。
 *
 * @author 走进新时代
 */
public class ExportCellRendererTest extends BaseMockitoUnitTest {

    private ExportCellRenderer renderer;

    @BeforeEach
    void setUp() throws Exception {
        CellFormatContractRegistry registry = new CellFormatContractRegistry();
        registry.init();
        renderer = new ExportCellRenderer();
        Field field = ExportCellRenderer.class.getDeclaredField("registry");
        field.setAccessible(true);
        field.set(renderer, registry);
    }

    private Map<String, Object> fmt(Object... kv) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    // ==================== 数值：真值 + 格式 ====================

    @Test
    @DisplayName("⚠️ 金额写真值 + 千分位格式，不是写带逗号的文本")
    void testNumberKeepsRealValue() {
        ExportCellRenderer.Cell cell = renderer.render(
                new BigDecimal("8500000"), fmt("type", "NUMBER", "precision", 2, "thousands", true));

        // 值是数字，用户能对这一列求和；格式让它显示成 8,500,000.00
        assertInstanceOf(BigDecimal.class, cell.getValue());
        assertEquals(0, new BigDecimal("8500000").compareTo((BigDecimal) cell.getValue()));
        assertEquals("#,##0.00", cell.getNumFmt());
    }

    @Test
    @DisplayName("数字列不配千分位时格式串里没有逗号")
    void testNumberWithoutThousands() {
        ExportCellRenderer.Cell cell = renderer.render(
                123, fmt("type", "NUMBER", "precision", 0));
        assertEquals("0", cell.getNumFmt());
    }

    @Test
    @DisplayName("⚠️ 百分比写小数 + % 格式 —— Excel 的 % 会自己乘 100")
    void testPercentKeepsDecimal() {
        ExportCellRenderer.Cell cell = renderer.render(
                new BigDecimal("0.13"), fmt("type", "PERCENT", "precision", 2));

        // 真值仍是 0.13，显示成 13.00%。写 13 的话会印出 1300.00%
        assertEquals(0, new BigDecimal("0.13").compareTo((BigDecimal) cell.getValue()));
        assertEquals("0.00%", cell.getNumFmt());
    }

    @Test
    @DisplayName("⚠️ 列配了 alreadyScaled（库里存 13）时要先除以 100")
    void testPercentAlreadyScaled() {
        ExportCellRenderer.Cell cell = renderer.render(
                new BigDecimal("13"), fmt("type", "PERCENT", "precision", 2, "alreadyScaled", true));

        // 不除的话 Excel 会把 13 显示成 1300.00%
        assertEquals(0, new BigDecimal("0.13").compareTo((BigDecimal) cell.getValue()));
        assertEquals("0.00%", cell.getNumFmt());
    }

    @Test
    @DisplayName("数值列填了非数字时退回文本，而不是写坏值")
    void testNumberFallsBackToText() {
        ExportCellRenderer.Cell cell = renderer.render("待定", fmt("type", "NUMBER"));
        assertEquals("待定", cell.getValue());
        assertNull(cell.getNumFmt());
    }

    // ==================== 日期 ====================

    @Test
    @DisplayName("⚠️ 日期写 LocalDate + 日期格式，不是写字符串")
    void testDateKeepsTemporalType() {
        ExportCellRenderer.Cell cell = renderer.render("2026-09-14", fmt("type", "DATE"));

        // 写字符串的话单元格是文本，numFmt 不起作用，那一列也没法排序
        assertInstanceOf(java.time.LocalDate.class, cell.getValue());
        assertEquals(java.time.LocalDate.of(2026, 9, 14), cell.getValue());
        assertEquals("yyyy-mm-dd", cell.getNumFmt());
    }

    @Test
    @DisplayName("日期时间写 LocalDateTime")
    void testDateTime() {
        ExportCellRenderer.Cell cell = renderer.render(
                "2026-09-14 10:30:00", fmt("type", "DATETIME"));
        assertEquals(java.time.LocalDateTime.of(2026, 9, 14, 10, 30, 0), cell.getValue());
        assertEquals("yyyy-mm-dd hh:mm:ss", cell.getNumFmt());
    }

    @Test
    @DisplayName("日期列拿到完整时间时只取日期部分")
    void testDateFromDateTime() {
        ExportCellRenderer.Cell cell = renderer.render(
                "2026-09-14 10:30:00", fmt("type", "DATE"));
        assertEquals(java.time.LocalDate.of(2026, 9, 14), cell.getValue());
    }

    @Test
    @DisplayName("认不出的日期退回文本，不写坏值让 Excel 提示文件损坏")
    void testBadDateFallsBackToText() {
        ExportCellRenderer.Cell cell = renderer.render("去年三月", fmt("type", "DATE"));
        assertEquals("去年三月", cell.getValue());
    }

    // ==================== 布尔 ====================

    @Test
    @DisplayName("⚠️ 布尔导出「是/否」，而不是 1.0")
    void testBooleanText() {
        assertEquals("是", renderer.render(1, fmt("type", "BOOLEAN")).getValue());
        assertEquals("是", renderer.render(true, fmt("type", "BOOLEAN")).getValue());
        assertEquals("是", renderer.render(new BigDecimal("1"), fmt("type", "BOOLEAN")).getValue());
        assertEquals("否", renderer.render(0, fmt("type", "BOOLEAN")).getValue());
    }

    // ==================== 字典 ====================

    @Test
    @DisplayName("字典翻不出来时原样返回编码，不返回空")
    void testDictFallsBackToCode() {
        // 返回空会让人以为这条数据没填，而实际是字典里少了一项
        ExportCellRenderer.Cell cell = renderer.render(
                "MAKE", fmt("type", "DICT", "dictType", "a_dict_that_does_not_exist"));
        assertEquals("MAKE", cell.getValue());
    }

    @Test
    @DisplayName("没配 dictType 的字典列原样输出")
    void testDictWithoutType() {
        assertEquals("MAKE", renderer.render("MAKE", fmt("type", "DICT")).getValue());
    }

    // ==================== 文件 / 链接 ====================

    @Test
    @DisplayName("⚠️ 附件导出 URL 而不是文件名 —— 文件名定位不到文件，导不回来")
    void testFileExportsUrl() {
        Object value = List.of(Map.of("name", "营业执照.pdf", "url", "https://x.com/a.pdf"));
        assertEquals("https://x.com/a.pdf",
                renderer.render(value, fmt("type", "FILE")).getValue());
    }

    @Test
    @DisplayName("多个附件用逗号连接")
    void testMultipleFiles() {
        Object value = List.of(
                Map.of("name", "a", "url", "https://x.com/a.pdf"),
                Map.of("name", "b", "url", "https://x.com/b.pdf"));
        assertEquals("https://x.com/a.pdf,https://x.com/b.pdf",
                renderer.render(value, fmt("type", "FILE")).getValue());
    }

    @Test
    @DisplayName("⚠️ 超链接导出 URL 原文，而不是列表上的「打开」")
    void testLinkExportsUrl() {
        assertEquals("https://example.com/x",
                renderer.render("https://example.com/x",
                        fmt("type", "LINK", "linkText", "打开")).getValue());
    }

    // ==================== 空值与兜底 ====================

    @Test
    @DisplayName("⚠️ 空值写空单元格，不写占位符「-」")
    void testEmptyWritesNull() {
        // 写「-」会让整列变成文本，用户没法对这一列求和
        assertNull(renderer.render(null, fmt("type", "NUMBER")).getValue());
        assertNull(renderer.render("", fmt("type", "DICT")).getValue());
        assertNull(renderer.render(List.of(), fmt("type", "FILE")).getValue());
    }

    @Test
    @DisplayName("没配格式化的列原样输出")
    void testNoFormatter() {
        assertEquals("原样", renderer.render("原样", null).getValue());
        assertEquals("原样", renderer.render("原样", fmt("type", "NONE")).getValue());
    }

    @Test
    @DisplayName("多值字段用逗号连接，与列表一致")
    void testMultiValue() {
        assertEquals("A, B", renderer.render(List.of("A", "B"), null).getValue());
    }

    @Test
    @DisplayName("认不出的格式化按无格式化处理，不让整张表导不出来")
    void testUnknownFormatter() {
        assertEquals("x", renderer.render("x", fmt("type", "SOMETHING_NEW")).getValue());
    }

}
