package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.idev.excel.FastExcelFactory;
import cn.idev.excel.enums.ReadDefaultReturnEnum;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ImportSheetReader} 的单元测试
 *
 * <h3>这个类补的是一个「设计好了但从来没生效过」的行为</h3>
 *
 * 行数上限原本是用 {@code context.interrupt()} 实现的，而那个方法抛的是
 * {@code ExcelAnalysisException("interrupt error")} —— <b>不是</b> FastExcel 在
 * {@code ExcelAnalyserImpl.analysis} 里捕获的 {@code ExcelAnalysisStopException}
 * （后者是前者的子类，catch 子类接不住父类）。异常一路穿出 {@code read()}，
 * 于是：
 *
 * <ul>
 *   <li>超过 {@code MAX_ROWS} 行的文件整个导入直接报一个看不懂的错，
 *       而不是按设计截断并提示</li>
 *   <li>{@code ImportDataResolver} 那句 {@code setTruncated(reader.isStopped())} 是死代码</li>
 *   <li>{@code FormImportServiceImpl} 里那段「文件超过 N 行，只读取了前 N 行」的提示
 *       从来没有机会出现过</li>
 * </ul>
 *
 * 这类缺陷单靠「跑一遍正常文件」发现不了 —— 正常文件根本不会触发它。
 *
 * @author 走进新时代
 */
public class ImportSheetReaderTest extends BaseMockitoUnitTest {

    @Test
    @DisplayName("读表头与数据行，行号是 Excel 里的真实行号")
    void testReadLines() {
        ImportSheetReader reader = read(0,
                Arrays.asList("供应商编码", "供应商名称"),
                Arrays.asList("SUP001", "甲公司"),
                Arrays.asList("SUP002", "乙公司"));

        assertEquals(Arrays.asList("供应商编码", "供应商名称"), reader.getHeaders());
        assertEquals(2, reader.getLines().size());
        // 表头是第 1 行，所以第一条数据是第 2 行
        assertEquals(2, reader.getLines().get(0).rowNo);
        assertEquals(3, reader.getLines().get(1).rowNo);
        assertEquals("SUP001", reader.getLines().get(0).texts.get(0));
        assertEquals(1, reader.getMaxColumnIndex());
        assertFalse(reader.isStopped());
    }

    @Test
    @DisplayName("空行跳过，但后面那些行的行号不能跟着往前挪")
    void testSkipBlankLinesKeepsRowNo() {
        ImportSheetReader reader = read(0,
                Arrays.asList("供应商编码", "供应商名称"),
                Arrays.asList("SUP001", "甲公司"),
                Arrays.asList("", ""),
                Arrays.asList("SUP002", "乙公司"));

        assertEquals(2, reader.getLines().size());
        // ⚠️ 这里是 4 不是 3。用户在数据之间留空行分段是常事，
        // 按「第几条数据」报行号的话，他照着行号回 Excel 里找会找到别人
        assertEquals(4, reader.getLines().get(1).rowNo);
    }

    @Test
    @DisplayName("超过上限时【正常读完并标记截断】，而不是抛异常把整个导入炸掉")
    void testTruncatedInsteadOfThrowing() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(Arrays.asList("SUP" + i, "公司" + i));
        }

        // ⚠️ 这一句本身就是断言：改回 context.interrupt() 的话，
        // 这里会抛 ExcelAnalysisException，用例直接红
        ImportSheetReader reader = assertDoesNotThrow(() ->
                read(3, Arrays.asList("供应商编码", "供应商名称"), rows.toArray(new List[0])));

        assertEquals(3, reader.getLines().size());
        assertTrue(reader.isStopped());
    }

    @Test
    @DisplayName("行数【正好等于】上限时不算截断 —— 边界上多报一次，用户就会去做一次没必要的拆分")
    void testNotTruncatedWhenExactlyAtLimit() {
        ImportSheetReader reader = read(3, Arrays.asList("供应商编码"),
                Arrays.asList("SUP1"), Arrays.asList("SUP2"), Arrays.asList("SUP3"));

        assertEquals(3, reader.getLines().size());
        assertFalse(reader.isStopped());
    }

    @Test
    @DisplayName("尾部的空行不占上限 —— Excel 文件尾巴上挂一堆清空过的行是常事")
    void testTrailingBlankLinesDoNotCountAsTruncation() {
        ImportSheetReader reader = read(2, Arrays.asList("供应商编码"),
                Arrays.asList("SUP1"), Arrays.asList("SUP2"),
                Arrays.asList(""), Arrays.asList(""));

        assertEquals(2, reader.getLines().size());
        assertFalse(reader.isStopped());
    }

    @Test
    @DisplayName("上限传 0 表示不限")
    void testNoLimit() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rows.add(Arrays.asList("SUP" + i));
        }
        ImportSheetReader reader = read(0, Arrays.asList("供应商编码"), rows.toArray(new List[0]));

        assertEquals(20, reader.getLines().size());
        assertFalse(reader.isStopped());
    }

    // ==================== 造一份真的 xlsx ====================

    @SafeVarargs
    private final ImportSheetReader read(int maxRows, List<String> header, List<?>... rows) {
        ImportSheetReader reader = new ImportSheetReader(maxRows);
        // ⚠️ readDefaultReturn(READ_CELL_DATA) 不能省，理由见 ImportDataResolver 里那段注释
        FastExcelFactory.read(new ByteArrayInputStream(write(header, rows)), reader)
                .readDefaultReturn(ReadDefaultReturnEnum.READ_CELL_DATA)
                .autoCloseStream(true).headRowNumber(1).sheet().doRead();
        return reader;
    }

    /**
     * 写一份内存里的 xlsx
     *
     * 不 mock 掉 FastExcel：这个类的价值全在「FastExcel 到底怎么回调、
     * 以及它怎么处理监听器抛出来的东西」上，mock 掉之后测的就只剩我自己写的赋值了 ——
     * 而这次的缺陷恰恰在 FastExcel 那一侧
     */
    private byte[] write(List<String> header, List<?>[] rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<List<String>> head = new ArrayList<>();
        header.forEach(it -> head.add(Collections.singletonList(it)));
        List<List<Object>> data = new ArrayList<>();
        for (List<?> row : rows) {
            data.add(new ArrayList<>(row));
        }
        FastExcelFactory.write(out).head(head).sheet("数据").doWrite(data);
        return out.toByteArray();
    }

}
