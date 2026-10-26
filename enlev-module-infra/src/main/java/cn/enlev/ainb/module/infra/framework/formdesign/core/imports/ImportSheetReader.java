package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;
import cn.idev.excel.metadata.data.ReadCellData;
import cn.hutool.core.util.StrUtil;
import cn.idev.excel.metadata.data.DataFormatData;
import lombok.Getter;
import org.apache.poi.ss.usermodel.DateUtil;

import java.time.LocalDateTime;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逐行读取 Excel 的监听器
 *
 * <h3>为什么不用「先把整份文件读成 List」的写法</h3>
 *
 * {@code ExcelUtils.read(file, Class)} 那种要求先有一个 VO 类 ——
 * 而导入的表单是运行时的、字段随时会变，根本没有对应的类。
 * 框架里另有一个 {@code read(MultipartFile)} 返回 {@code List<Map<Integer,String>>}，
 * 但它: 一是把整份文件读进内存，二是**丢掉了单元格的原始类型** ——
 * 日期会变成一串本地化过的文本，本来好好的日期列反而变难解析。
 *
 * 所以这里直接用监听器：边读边转，只保留需要的行，并且拿得到
 * {@code ReadCellData} 的原始类型。
 *
 * <h3>行号</h3>
 *
 * 用 {@code context.readRowHolder().getRowIndex()}（0 基）加 1 得到「Excel 里的行号」。
 * 自己数行会错 —— 空行、被跳过的行都会让计数漂移，
 * 而用户是拿 Excel 行号去找那一行的。
 *
 * @author 走进新时代
 */
public class ImportSheetReader extends AnalysisEventListener<Map<Integer, ReadCellData<?>>> {

    /**
     * 一行里最多认多少列
     *
     * 用户常在右侧留一堆空列或临时批注列，不设上限的话
     * 每一行都要为几百个空列做一遍匹配
     */
    private static final int MAX_COLUMNS = 200;

    /**
     * 表头：列序号 → 文本
     */
    @Getter
    private List<String> headers = new ArrayList<>();

    /**
     * 读到的数据行：文件行号（1 基，含表头）→ 列序号 → 单元格
     */
    @Getter
    private final List<RawLine> lines = new ArrayList<>();

    /**
     * 文件里出现过的最大的列序号
     */
    @Getter
    private int maxColumnIndex = -1;

    /**
     * 是否已经停止收集（超过上限时）
     */
    @Getter
    private boolean stopped;

    /**
     * 最多读多少行。0 表示不限
     */
    private final int maxRows;

    public ImportSheetReader(int maxRows) {
        this.maxRows = maxRows;
    }

    /**
     * 一行原始数据
     */
    public static class RawLine {
        /**
         * 文件行号，从 1 开始（含表头行）
         */
        public int rowNo;
        /**
         * 列序号 → 单元格的值（已转成合适的形态）
         */
        public Map<Integer, Object> cells = new LinkedHashMap<>();
        /**
         * 列序号 → 单元格的展示文本
         */
        public Map<Integer, String> texts = new LinkedHashMap<>();

        /**
         * 这一行是不是完全空的
         */
        public boolean isEmpty() {
            return texts.values().stream().allMatch(StrUtil::isBlank);
        }
    }

    /**
     * 表头行
     *
     * ⚠️ 表头**不走** {@link #invoke}
     *
     * FastExcel 把「第 headRowNumber 行之前的行」全部当表头，通过这个回调交出来；
     * 数据行才走 invoke。在 invoke 里按 rowIndex == 0 判表头是错的 ——
     * 那一行根本不会进 invoke，结果是表头永远为空、而数据行数又是对的，
     * 报出来的是「没读到表头行」，看起来像文件有问题。
     */
    @Override
    public void invokeHead(Map<Integer, ReadCellData<?>> headMap, AnalysisContext context) {
        // 多行表头时只认最后一次回调（也就是最靠近数据的那一行）——
        // 模板的第二行是填写说明，用它当表头会一列都匹配不上
        List<String> parsed = readHeader(headMap);
        if (!parsed.isEmpty() && parsed.stream().anyMatch(h -> h != null && !h.isBlank())) {
            headers = parsed;
        }
    }

    @Override
    public void invoke(Map<Integer, ReadCellData<?>> row, AnalysisContext context) {
        int rowNo = context.readRowHolder().getRowIndex() + 1;

        RawLine line = new RawLine();
        line.rowNo = rowNo;
        for (Map.Entry<Integer, ReadCellData<?>> entry : row.entrySet()) {
            int column = entry.getKey();
            if (column >= MAX_COLUMNS) {
                continue;
            }
            ReadCellData<?> cell = entry.getValue();
            if (cell == null) {
                continue;
            }
            String text = cellText(cell);
            if (StrUtil.isBlank(text)) {
                continue;
            }
            line.texts.put(column, text);
            line.cells.put(column, cellValue(cell));
            maxColumnIndex = Math.max(maxColumnIndex, column);
        }

        // 空行跳过。用户常在数据之间留空行分段，把它当一行数据会得到一堆
        // 「必填项没填」的报错，把真正的错误淹掉。
        // ⚠️ 空行判断要在上限判断【之前】：Excel 文件尾部常挂着一堆被编辑过又清空的行，
        // 拿它们去顶上限的话，一个正好 MAX_ROWS 行的文件会被报成「被截断了」
        if (line.isEmpty()) {
            return;
        }

        // 收满之后还能读到有内容的一行 —— 这才说明文件里确实还有，而不是刚好这么多。
        // 读满就停的写法分不出这两种情况，于是恰好 maxRows 行的文件也会带上截断警告
        if (maxRows > 0 && lines.size() >= maxRows) {
            stopped = true;
            return;
        }
        lines.add(line);
    }

    /**
     * 还要不要继续读
     *
     * ⚠️ 用它来停，而不是 {@code context.interrupt()}
     *
     * 这里原先调的是 {@code interrupt()}，而那个方法抛的是
     * {@code ExcelAnalysisException("interrupt error")} —— <b>不是</b>
     * FastExcel 在 {@code ExcelAnalyserImpl.analysis} 里捕获的
     * {@code ExcelAnalysisStopException}（后者是前者的子类，catch 子类接不住父类）。
     * 于是异常一路穿出 {@code read()}：
     *
     * <ul>
     *   <li>超过 {@code MAX_ROWS} 行的文件<b>整个导入直接报错</b>，
     *       而不是按设计截断并提示</li>
     *   <li>{@code ImportDataResolver} 里那句 {@code parsed.setTruncated(reader.isStopped())}
     *       是走不到的死代码 —— 截断标记从来没有真的传出去过</li>
     *   <li>而界面第一步还写着「最多 5000 行」，用户会以为自己是按规则做的</li>
     * </ul>
     *
     * 截断恰恰是要<b>读到结果并且告诉用户</b>的场景，不该表现成一次读取失败。
     */
    @Override
    public boolean hasNext(AnalysisContext context) {
        return !stopped;
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // 不需要收尾动作。数据已经在 lines 里了
    }

    /**
     * 读表头行
     */
    private List<String> readHeader(Map<Integer, ReadCellData<?>> row) {
        List<String> result = new ArrayList<>();
        if (row == null) {
            return result;
        }
        int max = -1;
        for (Integer column : row.keySet()) {
            max = Math.max(max, column);
        }
        for (int i = 0; i <= Math.min(max, MAX_COLUMNS - 1); i++) {
            ReadCellData<?> cell = row.get(i);
            result.add(cell == null ? null : cellText(cell));
        }
        return result;
    }

    /**
     * 单元格 → 展示文本
     *
     * 日期单元格要按日期读，不能读它的序列号（45915 这种）——
     * 那个数字对用户毫无意义，而且比「没识别」更糟：它是个合法的数字，
     * 会被当成真值存进去。
     */
    public static String cellText(ReadCellData<?> cell) {
        if (cell == null) {
            return null;
        }
        Object value = rawValue(cell);
        return value == null ? null : ImportCellReader.toText(value);
    }

    /**
     * 单元格 → 原始值
     *
     * 数值保留 BigDecimal 而不是转字符串：19 位的银行账号、
     * 带很多位的金额，转成字符串再转回来会丢精度或变形。
     */
    public static Object cellValue(ReadCellData<?> cell) {
        if (cell == null) {
            return null;
        }
        return rawValue(cell);
    }

    /**
     * 从 ReadCellData 里取到真正的值
     */
    private static Object rawValue(ReadCellData<?> cell) {
        if (cell.getType() == null) {
            return null;
        }
        // ⚠️ 必须先判「这一格是不是日期格式」，再按类型分发
        //
        // xlsx 里日期就是【带日期格式的数字】，没有独立的日期类型。
        // FastExcel 多数情况下把它作为 NUMBER 交出来，于是不判格式的话，
        // 2024-03-15 读到的是 45366 —— 一个合法的数字，
        // 于是日期列报「45366 无法识别」，而用户在 Excel 里看到的明明是日期。
        //
        // 这条路径在导出还是写文本的时候不会触发；导出改成写真日期之后，
        // 「导出的文件导不回自己」就成了必然。
        LocalDateTime temporal = readTemporal(cell);
        if (temporal != null) {
            return temporal;
        }
        return switch (cell.getType()) {
            case STRING, DIRECT_STRING, RICH_TEXT_STRING -> cell.getStringValue();
            case NUMBER -> cell.getNumberValue();
            case BOOLEAN -> cell.getBooleanValue();
            // DATE 类型：FastExcel 把它放在 data 或 numberValue 里。
            // stringValue 此时是它格式化过的那份，正是我们要的展示形态
            case DATE -> cell.getStringValue() != null
                    ? cell.getStringValue() : cell.getNumberValue();
            case EMPTY, ERROR -> null;
        };
    }

    /**
     * 单元格是不是日期，是就还原成时间
     *
     * <h3>判据是【数字格式】，不是值的类型</h3>
     *
     * xlsx 里日期就是带日期格式的数字。{@code 45366} 配上 {@code yyyy-mm-dd}
     * 才是 2024-03-15，同一个 45366 配上 {@code #,##0} 就是四万五千多。
     * 所以只能看格式。
     *
     * 用 POI 的 {@code DateUtil.isADateFormat} 判，不自己维护一张格式号清单 ——
     * 内置日期格式号是分段的（14–22、45–47…），自己列容易漏。
     *
     * <h3>序列号还原也用 POI</h3>
     *
     * Excel 的日期原点是 1899-12-30，而且有个「1900 年当闰年」的历史 bug
     * 要一起兼容。自己算差一天是很常见的错误，而差一天在业务上没人能立刻发现。
     *
     * @return 不是日期单元格时返回 null
     */
    private static LocalDateTime readTemporal(ReadCellData<?> cell) {
        DataFormatData format = cell.getDataFormatData();
        if (format == null) {
            return null;
        }
        short index = format.getIndex() != null ? format.getIndex() : -1;
        if (!DateUtil.isADateFormat(index, format.getFormat())) {
            return null;
        }
        // 日期格式下 FastExcel 可能已经解析好了，优先用它的
        if (cell.getData() instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (cell.getData() instanceof java.util.Date date) {
            return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
        BigDecimal serial = cell.getNumberValue();
        if (serial == null) {
            return null;
        }
        return DateUtil.getLocalDateTime(serial.doubleValue());
    }

}
