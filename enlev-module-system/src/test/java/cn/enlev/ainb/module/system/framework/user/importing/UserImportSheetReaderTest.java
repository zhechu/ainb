package cn.enlev.ainb.module.system.framework.user.importing;

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
 * {@link UserImportSheetReader} 的单元测试
 *
 * 这个类存在的理由是两件用别的写法拿不到的事：<b>真实行号</b>与<b>有没有被截断</b>。
 * 两件都只在「用户对着 Excel 找那一行」时才暴露问题，所以必须在这里钉住。
 *
 * @author 芋道源码
 */
public class UserImportSheetReaderTest extends BaseMockitoUnitTest {

    @Test
    @DisplayName("读表头与数据行，行号是 Excel 里的真实行号")
    void testReadRows() {
        UserImportSheetReader reader = read(0,
                Arrays.asList("登录名称", "用户名称", "部门编号"),
                Arrays.asList("zhangsan", "张三", "100"),
                Arrays.asList("lisi", "李四", "101"));

        assertEquals(Arrays.asList("登录名称", "用户名称", "部门编号"), reader.getHeaders());
        assertEquals(2, reader.getRows().size());
        // 表头是第 1 行，所以第一条数据是第 2 行
        assertEquals(2, reader.getRows().get(0).getRowNo());
        assertEquals(3, reader.getRows().get(1).getRowNo());
        assertEquals("zhangsan", reader.getRows().get(0).cell(0));
        assertEquals("张三", reader.getRows().get(0).cell(1));
        assertFalse(reader.isTruncated());
    }

    @Test
    @DisplayName("空行跳过，但后面那些行的行号不能跟着往前挪")
    void testSkipBlankRowsKeepsRowNo() {
        UserImportSheetReader reader = read(0,
                Arrays.asList("登录名称", "用户名称"),
                Arrays.asList("zhangsan", "张三"),
                Arrays.asList("", ""),
                Arrays.asList("lisi", "李四"));

        assertEquals(2, reader.getRows().size());
        assertEquals(2, reader.getRows().get(0).getRowNo());
        // ⚠️ 这里是 4 不是 3。用户在 Excel 里数据之间留空行分段是常事，
        // 按「第几条数据」报行号的话，他照着行号回去找，找到的是别人
        assertEquals(4, reader.getRows().get(1).getRowNo());
    }

    @Test
    @DisplayName("读满上限就停，并且【说出来】—— 静默截断是改造前的真实缺口")
    void testTruncated() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(Arrays.asList("user" + i, "用户" + i));
        }
        UserImportSheetReader reader = read(3, Arrays.asList("登录名称", "用户名称"),
                rows.toArray(new List[0]));

        assertEquals(3, reader.getRows().size());
        assertTrue(reader.isTruncated());
    }

    @Test
    @DisplayName("没读满就不算截断 —— 不能让正常的文件也带上警告")
    void testNotTruncatedWhenUnderLimit() {
        UserImportSheetReader reader = read(100,
                Arrays.asList("登录名称"), Arrays.asList("zhangsan"));

        assertEquals(1, reader.getRows().size());
        assertFalse(reader.isTruncated());
    }

    @Test
    @DisplayName("行数【正好等于】上限时不算截断 —— 边界上多报一次警告，用户就会去做一次没必要的拆分")
    void testNotTruncatedWhenExactlyAtLimit() {
        UserImportSheetReader reader = read(3, Arrays.asList("登录名称"),
                Arrays.asList("a"), Arrays.asList("b"), Arrays.asList("c"));

        assertEquals(3, reader.getRows().size());
        assertFalse(reader.isTruncated());
    }

    @Test
    @DisplayName("尾部的空行不占上限 —— Excel 文件尾巴上挂一堆清空过的行是常事")
    void testTrailingBlankRowsDoNotCountAsTruncation() {
        UserImportSheetReader reader = read(2, Arrays.asList("登录名称"),
                Arrays.asList("a"), Arrays.asList("b"), Arrays.asList(""), Arrays.asList(""));

        assertEquals(2, reader.getRows().size());
        assertFalse(reader.isTruncated());
    }

    @Test
    @DisplayName("数字单元格不能读成 1.0 或 1E+2，否则部门编号会解析失败")
    void testNumberCellText() {
        UserImportSheetReader reader = read(0,
                Arrays.asList("登录名称", "部门编号"),
                Arrays.asList("zhangsan", 100),
                Arrays.asList("lisi", 1));

        assertEquals("100", reader.getRows().get(0).cell(1));
        assertEquals("1", reader.getRows().get(1).cell(1));
    }

    @Test
    @DisplayName("空文件不炸，读出来就是没有表头、没有数据")
    void testEmptySheet() {
        UserImportSheetReader reader = read(0, Collections.emptyList());
        assertTrue(reader.getHeaders().isEmpty());
        assertTrue(reader.getRows().isEmpty());
    }

    // ==================== 造一个真的 xlsx ====================

    @SafeVarargs
    private final UserImportSheetReader read(int maxRows, List<String> header, List<?>... rows) {
        byte[] bytes = write(header, rows);
        UserImportSheetReader reader = new UserImportSheetReader(maxRows);
        if (bytes.length == 0) {
            return reader;
        }
        FastExcelFactory.read(new ByteArrayInputStream(bytes), reader)
                // 不加这一句的话，FastExcel 把每格都当 String 交出来，
                // 监听器里声明的 ReadCellData 在运行期就是 ClassCastException
                .readDefaultReturn(ReadDefaultReturnEnum.READ_CELL_DATA)
                .autoCloseStream(true).headRowNumber(1).sheet().doRead();
        return reader;
    }

    /**
     * 写一份内存里的 xlsx
     *
     * 不用 mock 掉 FastExcel：这个类的价值全在「FastExcel 到底怎么回调」上，
     * 把它 mock 掉之后测的就只剩我自己写的那几行赋值了
     */
    private byte[] write(List<String> header, List<?>[] rows) {
        if (header.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<List<String>> head = new ArrayList<>();
        header.forEach(it -> head.add(Collections.singletonList(it)));
        List<List<Object>> data = new ArrayList<>();
        for (List<?> row : rows) {
            data.add(new ArrayList<>(row));
        }
        FastExcelFactory.write(out).head(head).sheet("用户列表").doWrite(data);
        return out.toByteArray();
    }

}
