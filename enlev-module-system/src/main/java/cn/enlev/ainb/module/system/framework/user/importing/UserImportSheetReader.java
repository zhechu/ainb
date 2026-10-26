package cn.enlev.ainb.module.system.framework.user.importing;

import cn.hutool.core.util.StrUtil;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;
import cn.idev.excel.metadata.data.ReadCellData;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户导入：逐行读取 Excel 的监听器
 *
 * <h3>为什么不直接用 {@code ExcelUtils.read(file, UserImportExcelVO.class, max)}</h3>
 *
 * 那个重载把整份文件读成一串 VO，读完之后<b>行号就没了</b> ——
 * 而导入预检最重要的输出就是「第几行有什么问题」。
 * 上游的实现只好拿用户名当 key 报错，用户名为空时退化成「第 N 行」，
 * 那个 N 还是按 VO 在 List 里的下标数出来的：文件里有空行、有被跳过的行时它就漂了，
 * 用户拿着这个行号回 Excel 里找，找到的是另一行。
 *
 * 这里用监听器边读边收，行号取 {@code context.readRowHolder().getRowIndex()}，
 * 那是 FastExcel 从文件里读到的真实行号。
 *
 * <h3>还顺带解决了「截断静默」</h3>
 *
 * 读满上限就 {@link AnalysisContext#interrupt()} 并把 {@link #truncated} 置上。
 * 上游那个重载只是 {@code hasNext} 返回 false 停下来，调用方拿到的还是一个
 * 普通的 List，<b>看不出它是读完的还是读一半停的</b> ——
 * 用户导一份 3 万行的文件只会看到 2 万条的结果，而他以为那就是全部。
 *
 * @author 芋道源码
 */
@Getter
public class UserImportSheetReader extends AnalysisEventListener<Map<Integer, ReadCellData<?>>> {

    /**
     * 一行里最多认多少列
     *
     * 用户常在右侧留一堆空列或临时批注列，不设上限的话每一行都要为几百个空列做一遍匹配
     */
    private static final int MAX_COLUMNS = 64;

    /**
     * 表头文本，下标即列序号（0 基）
     */
    private List<String> headers = new ArrayList<>();

    /**
     * 读到的数据行
     */
    private final List<RawRow> rows = new ArrayList<>();

    /**
     * 是不是因为读满上限才停的
     */
    private boolean truncated;

    /**
     * 最多读多少行。0 表示不限
     */
    private final int maxRows;

    public UserImportSheetReader(int maxRows) {
        this.maxRows = maxRows;
    }

    /**
     * 一行原始数据
     */
    @Getter
    public static class RawRow {

        /**
         * Excel 里的行号，从 1 开始（表头是第 1 行，所以数据行从 2 开始）
         */
        private final int rowNo;

        /**
         * 列序号 → 单元格文本。空单元格不放进来
         */
        private final Map<Integer, String> cells = new LinkedHashMap<>();

        public RawRow(int rowNo) {
            this.rowNo = rowNo;
        }

        public String cell(Integer columnIndex) {
            return columnIndex == null ? null : cells.get(columnIndex);
        }

        public boolean isEmpty() {
            return cells.values().stream().allMatch(StrUtil::isBlank);
        }
    }

    /**
     * 表头行
     *
     * ⚠️ 表头<b>不走</b> {@link #invoke}：FastExcel 把表头行通过这个回调单独交出来。
     * 在 invoke 里按「rowIndex == 0 就是表头」判是错的 —— 那一行根本不会进 invoke，
     * 结果是表头永远为空，而报出来的现象是「一列都没识别」，看起来像用户的文件有问题。
     */
    @Override
    public void invokeHead(Map<Integer, ReadCellData<?>> headMap, AnalysisContext context) {
        List<String> parsed = readHeader(headMap);
        if (parsed.stream().anyMatch(StrUtil::isNotBlank)) {
            headers = parsed;
        }
    }

    @Override
    public void invoke(Map<Integer, ReadCellData<?>> row, AnalysisContext context) {
        RawRow rawRow = new RawRow(context.readRowHolder().getRowIndex() + 1);
        row.forEach((column, cell) -> {
            if (column == null || column >= MAX_COLUMNS || cell == null) {
                return;
            }
            String text = cellText(cell);
            if (StrUtil.isNotBlank(text)) {
                rawRow.cells.put(column, text);
            }
        });

        // 整行都空就跳过。用户常用空行给数据分段，把它当一行数据会得到一堆
        // 「必填项没填」，把真正的错误淹掉。
        // ⚠️ 空行判断要在上限判断【之前】：Excel 文件尾部常挂着一堆被编辑过又清空的行，
        // 拿它们去顶上限的话，一个正好 20000 行的文件会被报成「被截断了」
        if (rawRow.isEmpty()) {
            return;
        }

        // 收满之后还能读到有内容的一行 —— 这才说明文件里确实还有，而不是刚好这么多。
        // 读满就停的写法分不出这两种情况，于是恰好 maxRows 行的文件也会带上截断警告
        if (maxRows > 0 && rows.size() >= maxRows) {
            truncated = true;
            return;
        }
        rows.add(rawRow);
    }

    /**
     * 还要不要继续读
     *
     * ⚠️ 用它来停，而不是 {@code context.interrupt()}
     *
     * {@code interrupt()} 是抛异常实现的，那个异常会一路穿出 {@code doRead()} ——
     * 也就是说「文件太大」会表现成一次读取失败，而不是一次带警告的成功读取。
     * 而截断恰恰是要<b>读到结果并且告诉用户</b>的场景
     */
    @Override
    public boolean hasNext(AnalysisContext context) {
        return !truncated;
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // 不需要收尾动作，数据已经在 rows 里了
    }

    private List<String> readHeader(Map<Integer, ReadCellData<?>> row) {
        List<String> result = new ArrayList<>();
        if (row == null || row.isEmpty()) {
            return result;
        }
        int max = row.keySet().stream().filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue).max().orElse(-1);
        for (int i = 0; i <= Math.min(max, MAX_COLUMNS - 1); i++) {
            result.add(StrUtil.trimToNull(cellText(row.get(i))));
        }
        return result;
    }

    /**
     * 单元格 → 文本
     *
     * 用户导入的七列全是文本、整数或字典标签，没有日期，所以这里不做日期还原 ——
     * 需要的话见表单设计那边的 {@code ImportSheetReader}，它按数字格式判日期。
     *
     * 数字要去掉 FastExcel 给的科学计数与多余小数位：部门编号读成 {@code 1.0}
     * 的话，后面 {@code Long.parseLong} 会失败，而用户在 Excel 里看到的明明是 1。
     */
    public static String cellText(ReadCellData<?> cell) {
        if (cell == null || cell.getType() == null) {
            return null;
        }
        return switch (cell.getType()) {
            case STRING, DIRECT_STRING, RICH_TEXT_STRING -> cell.getStringValue();
            case NUMBER -> numberText(cell.getNumberValue());
            case BOOLEAN -> cell.getBooleanValue() == null ? null : String.valueOf(cell.getBooleanValue());
            case DATE -> cell.getStringValue() != null
                    ? cell.getStringValue() : numberText(cell.getNumberValue());
            case EMPTY, ERROR -> null;
        };
    }

    private static String numberText(BigDecimal number) {
        if (number == null) {
            return null;
        }
        // stripTrailingZeros 会把 100 变成 1E+2，所以要 toPlainString
        return number.stripTrailingZeros().toPlainString();
    }

}
