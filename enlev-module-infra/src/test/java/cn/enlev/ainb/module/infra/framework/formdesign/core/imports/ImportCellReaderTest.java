package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImportCellReader} 的单元测试
 *
 * <h3>为什么这一层要测得细</h3>
 *
 * 用户手里的 Excel 是自由的：日期可以是 {@code 2026-09-14}、{@code 2026/9/14}、
 * {@code 2026年9月14日}；金额可能带千分位与货币符号；布尔可能是「是」「Y」「√」。
 *
 * 不管的话只有两种结局，而两种都很糟：
 * <ul>
 *   <li>整行被拒 —— 用户不知道该怎么改</li>
 *   <li>被当成脏值存进去 —— 日期差一天、金额变成 0、布尔反过来</li>
 * </ul>
 *
 * 尤其是<b>布尔</b>：只认 true/false 的实现会把「是」当成一个非空值存进去，
 * 而读回来是 0（也就是「否」），与用户填的正好相反。这是导入里最危险的静默错误。
 *
 * <p>字典反查这一段依赖 Spring 上下文（走框架的字典缓存），不在这里测 ——
 * 它由接口层的冒烟（{@code smoke_ie.py}）覆盖。
 *
 * @author 走进新时代
 */
public class ImportCellReaderTest extends BaseMockitoUnitTest {

    private final ImportCellReader reader = new ImportCellReader();

    private ImportWidget widget(String type) {
        ImportWidget w = new ImportWidget();
        w.setType(type);
        w.setField("f");
        w.setTitle("测试字段");
        return w;
    }

    private ImportWidget number(Integer precision) {
        ImportWidget w = widget("inputNumber");
        w.setPrecision(precision);
        return w;
    }

    private ImportWidget date(String dateType) {
        ImportWidget w = widget("datePicker");
        w.setDateType(dateType);
        return w;
    }

    // ==================== 数字 ====================

    @Test
    @DisplayName("千分位、货币符号、全角数字都能读成数字")
    void testNumberCleansSeparators() {
        assertEquals("1234.5", reader.read("1,234.50", number(2)).getValue());
        assertEquals("1234.5", reader.read("￥1,234.50", number(2)).getValue());
        assertEquals("1234.5", reader.read("１，２３４．５０", number(2)).getValue());
        assertEquals("-80", reader.read("-80", number(0)).getValue());
    }

    @Test
    @DisplayName("⚠️ 小数位超过声明的精度要报错，而不是悄悄四舍五入")
    void testNumberPrecisionExceeded() {
        // 悄悄舍掉的话，用户填 1.234、库里存 1.23，对账对不上时没人会想到是导入时截的
        ImportCellReader.Outcome outcome = reader.read("1.234", number(2));
        assertTrue(outcome.isError());
        assertTrue(outcome.getError().contains("小数位最多 2 位"));
    }

    @Test
    @DisplayName("⚠️ 非数字要报错并把原文带出来")
    void testNumberInvalid() {
        ImportCellReader.Outcome outcome = reader.read("待定", number(2));
        assertTrue(outcome.isError());
        assertTrue(outcome.getError().contains("必须是数字"));
        assertTrue(outcome.getError().contains("待定"));
    }

    @Test
    @DisplayName("单个短横线当空值 —— 报表里常用它表示「无」")
    void testNumberDashIsBlank() {
        assertNull(reader.read("-", number(2)).getValue());
        assertNull(reader.read("—", number(2)).getValue());
    }

    @Test
    @DisplayName("尾零被去掉 —— 1.5000 与 1.5 落库一样，带着尾零会让变更对比误判")
    void testNumberStripsTrailingZeros() {
        assertEquals("1.5", reader.read("1.5000", number(4)).getValue());
    }

    // ==================== 日期 ====================

    @Test
    @DisplayName("五种常见日期写法都能读")
    void testDateFormats() {
        for (String text : List.of("2026-09-14", "2026/9/14", "2026.09.14",
                "20260914", "2026年9月14日")) {
            ImportCellReader.Outcome outcome = reader.read(text, date("date"));
            assertFalse(outcome.isError(), "读不了：" + text);
            assertEquals("2026-09-14", outcome.getValue(), "结果不对：" + text);
        }
    }

    @Test
    @DisplayName("日期列填了完整时间时取日期部分，不报错")
    void testDateFromDateTime() {
        assertEquals("2026-09-14",
                reader.read("2026-09-14 10:30:00", date("date")).getValue());
    }

    @Test
    @DisplayName("日期时间列只填了日期时补 00:00:00 —— 语义明确，不该为此让用户改文件")
    void testDateTimeFromDateOnly() {
        assertEquals("2026-09-14 00:00:00",
                reader.read("2026-09-14", date("datetime")).getValue());
    }

    @Test
    @DisplayName("日期时间列读完整时间")
    void testDateTimeFull() {
        assertEquals("2026-09-14 10:30:00",
                reader.read("2026-09-14 10:30:00", date("datetime")).getValue());
    }

    @Test
    @DisplayName("⚠️ 认不出的日期要报错并给出可用写法")
    void testDateInvalid() {
        ImportCellReader.Outcome outcome = reader.read("去年三月", date("date"));
        assertTrue(outcome.isError());
        assertTrue(outcome.getError().contains("日期格式"));
        assertTrue(outcome.getError().contains("2026-09-14"));
    }

    // ==================== 布尔 ====================

    @Test
    @DisplayName("六种「真」的写法都读成 1")
    void testBooleanTrue() {
        for (String text : List.of("是", "Y", "yes", "true", "1", "√", "启用")) {
            assertEquals(1, reader.read(text, widget("switch")).getValue(), "读错了：" + text);
        }
    }

    @Test
    @DisplayName("六种「假」的写法都读成 0")
    void testBooleanFalse() {
        for (String text : List.of("否", "N", "no", "false", "0", "×", "停用")) {
            assertEquals(0, reader.read(text, widget("switch")).getValue(), "读错了：" + text);
        }
    }

    @Test
    @DisplayName("⚠️ 认不出的布尔值必须报错，绝不能当成非空值放过")
    void testBooleanInvalidMustFail() {
        // 放过的话「大概吧」会被存成一个非空值，而读回来是 0（也就是「否」）——
        // 与用户填的正好相反，且完全看不出来。这是导入里最危险的静默错误
        ImportCellReader.Outcome outcome = reader.read("大概吧", widget("switch"));
        assertTrue(outcome.isError());
        assertTrue(outcome.getError().contains("布尔值"));
    }

    // ==================== 文本与文件 ====================

    @Test
    @DisplayName("空单元格一律返回 null，交给必填校验去判")
    void testBlankIsNull() {
        assertNull(reader.read(null, widget("input")).getValue());
        assertNull(reader.read("", widget("input")).getValue());
        assertNull(reader.read("   ", widget("input")).getValue());
    }

    @Test
    @DisplayName("⚠️ 长数字按文本读，不能变成科学计数法")
    void testLongNumberKeepsPlainText() {
        // 19 位银行账号。转成 double 再转回来会变成 1.2345678901234568E18，
        // 而那是不可逆的
        BigDecimal account = new BigDecimal("0123456789012345678");
        assertEquals("123456789012345678",
                ImportCellReader.toText(account));
    }

    @Test
    @DisplayName("文件列接受 URL")
    void testFileAcceptsUrl() {
        assertEquals("https://example.com/a.pdf",
                reader.read("https://example.com/a.pdf", widget("UploadFile")).getValue());
    }

    @Test
    @DisplayName("⚠️ 文件列填了别的东西要报错，并说清内嵌图片导不进来")
    void testFileRejectsPlainText() {
        ImportCellReader.Outcome outcome = reader.read("公司营业执照", widget("UploadImg"));
        assertTrue(outcome.isError());
        assertTrue(outcome.getError().contains("内嵌的图片无法导入"));
    }

    // ==================== 多值切分 ====================

    @Test
    @DisplayName("多选值支持中英文逗号、顿号、分号、竖线、空格")
    void testSplitMultiValue() {
        assertEquals(List.of("A", "B", "C"), ImportHeaderText.splitMultiValue("A,B,C"));
        assertEquals(List.of("A", "B", "C"), ImportHeaderText.splitMultiValue("A，B、C"));
        assertEquals(List.of("A", "B", "C"), ImportHeaderText.splitMultiValue("A; B | C"));
        assertTrue(ImportHeaderText.splitMultiValue("").isEmpty());
    }

    @Test
    @DisplayName("多选值去重 —— 同一个值写两遍不该变成两条")
    void testSplitMultiValueDedup() {
        assertEquals(List.of("A", "B"), ImportHeaderText.splitMultiValue("A,B,A"));
    }

}
