package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.idev.excel.ExcelReader;
import cn.idev.excel.FastExcelFactory;
import cn.idev.excel.read.metadata.ReadSheet;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.framework.formdesign.core.cellformat.CellFormatContract;
import cn.enlev.ainb.module.infra.framework.formdesign.core.cellformat.CellFormatContractRegistry;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportModels.RawRow;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportModels.RowError;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * 导入文件的解析
 *
 * 把一份 Excel 变成「一组可直接提交给运行时的数据」，
 * 中间经过表头映射、单元格解析、主子表分组三步。
 *
 * <h3>字典值在这里翻译</h3>
 *
 * 「中文标签 → 字典编码」必须在解析阶段做完。留到执行阶段做的话，
 * 预览里显示的会是用户填的中文、而执行时才发现某个词不在字典里 ——
 * 恰恰是「预览说没问题、执行报错」那种最伤信任的情况。
 *
 * @author 走进新时代
 */
@Slf4j
@Component
public class ImportDataResolver {

    /**
     * 一次解析能处理的最大数据行数
     *
     * 上限本身要能在预览阶段就告诉用户，而不是读到一半截断 ——
     * 截断的话用户以为全导进去了。
     */
    public static final int MAX_ROWS = 5000;

    @Resource
    private ImportWidgetRegistry widgetRegistry;

    /**
     * 列格式化契约。导入侧只用它认出「合计行」这类视图产物 ——
     * 那些行是导出时跟随列表带上的，标记文本两端共用同一份声明
     */
    @Resource
    private CellFormatContractRegistry cellFormatRegistry;

    @Resource
    private ImportCellReader cellReader;

    @Resource
    private ImportRowGrouper rowGrouper;

    /**
     * 表头映射器。无状态，直接持有实例 —— 它没有需要注入的依赖，
     * 做成 bean 只会让「它到底依赖什么」这件事更难看清
     */
    private final ImportHeaderMapper headerMapper = new ImportHeaderMapper();

    /**
     * 解析结果
     */
    @Data
    public static class Parsed {

        /**
         * Excel 的表头
         */
        private List<String> headers = new ArrayList<>();

        /**
         * 表头映射结果
         */
        private ImportHeaderMapper.Result mapping;

        /**
         * 可导入字段
         */
        private Map<String, ImportWidget> widgets = new LinkedHashMap<>();

        /**
         * 分组后的数据
         */
        private ImportRowGrouper.Result groups;

        /**
         * 文件里的数据行数（不含表头）
         */
        private int dataRowCount;

        /**
         * 文件实际行数超过上限被截断
         */
        private boolean truncated;

        /**
         * 整表级错误（表头层面）
         */
        private List<RowError> tableErrors = new ArrayList<>();

        /**
         * 因为填的是掩码而被当成「没填」的脱敏列
         */
        private Set<String> maskedSkippedFields = new LinkedHashSet<>();

        /**
         * 被当成「视图产物」跳过的行数（合计行、分组行）
         *
         * 要回给用户看：跳过了却不说，用户会发现「文件里 101 行、只导了 100 条」，
         * 而那一条差额他找不到原因
         */
        private int skippedViewRows;
    }

    /**
     * 解析一份 Excel
     *
     * @param input        Excel 输入流。调用方负责关闭
     * @param formFieldsJson 表单字段规则
     * @param listConfJson   列表配置
     * @param parentFields   父行键字段名。为空表示单表表单
     * @return 解析结果
     */
    public Parsed resolve(InputStream input, String formFieldsJson, String listConfJson,
                          List<String> parentFields) throws IOException {
        Parsed parsed = new Parsed();
        parsed.setWidgets(widgetRegistry.parse(formFieldsJson, listConfJson));

        // 1. 读表头与数据行
        ImportSheetReader reader = new ImportSheetReader(MAX_ROWS);
        // ⚠️ readDefaultReturn(READ_CELL_DATA) 不能省
        //
        // 不加的话 FastExcel 把每个单元格都当成 String 交给监听器（默认行为），
        // 于是监听器里声明的 Map<Integer, ReadCellData<?>> 在运行期是一堆 String，
        // 第一次取值就 ClassCastException —— 而且是编译期完全看不出来的那种：
        // 泛型擦除之后没人核对过。
        //
        // 更要紧的是即使不崩，String 形态已经**丢掉了单元格的原始类型**：
        // 日期变成一串本地化过的文本、长数字变成科学计数法，
        // 而这两样恰恰是导入最需要正确识别的。
        try (ExcelReader excelReader = FastExcelFactory.read(input, reader)
                .readDefaultReturn(cn.idev.excel.enums.ReadDefaultReturnEnum.READ_CELL_DATA)
                .build()) {
            // 只读第一个 sheet。多 sheet 的「主表 + 明细」形态在后面的版本再做 ——
            // 现在明确只认第一个，而不是猜用户想导哪个
            ReadSheet sheet = FastExcelFactory.readSheet(0).build();
            excelReader.read(sheet);
        }
        parsed.setHeaders(reader.getHeaders());
        parsed.setDataRowCount(reader.getLines().size());
        parsed.setTruncated(reader.isStopped());

        if (reader.getHeaders().isEmpty()) {
            parsed.getTableErrors().add(tableError(
                    "这个文件没有读到表头行。请确认第一行是列名，且文件不是空的"));
            parsed.setMapping(new ImportHeaderMapper.Result());
            parsed.setGroups(new ImportRowGrouper.Result());
            return parsed;
        }

        // 2. 表头映射
        ImportHeaderMapper.Result mapping = headerMapper.map(reader.getHeaders(), parsed.getWidgets());
        parsed.setMapping(mapping);

        // 3. 逐行解析单元格。
        //    ⚠️ 先剔掉「视图产物」行 —— 合计行、分组行是导出时跟随列表带上的，
        //    它们不是数据。不剔的话合计行会被当成一条记录，
        //    报「供应商编码是必填项」，而用户完全不知道那一行是自己导出来的
        List<RawRow> rows = new ArrayList<>(reader.getLines().size());
        int skippedViewRows = 0;
        // 被当成「没填」跳过的脱敏列。要回给用户看 ——
        // 悄悄跳过的话，用户会以为那一列导进去了
        Set<String> maskedSkips = new LinkedHashSet<>();
        for (ImportSheetReader.RawLine line : reader.getLines()) {
            if (isViewArtifactRow(line)) {
                skippedViewRows++;
                continue;
            }
            rows.add(parseLine(line, mapping, parsed.getWidgets(), maskedSkips));
        }
        parsed.setSkippedViewRows(skippedViewRows);
        parsed.getMaskedSkippedFields().addAll(maskedSkips);

        // 4. 主子表分组
        parsed.setGroups(rowGrouper.group(rows, parsed.getWidgets(), parentFields));
        return parsed;
    }

    /**
     * 解析一行
     *
     * ⚠️ 一个单元格解析失败**不中断这一行**：继续把其它单元格解析完，
     * 这样用户一次能看到这一行的全部问题，而不是改一个、导一次、再发现一个。
     */
    private RawRow parseLine(ImportSheetReader.RawLine line, ImportHeaderMapper.Result mapping,
                             Map<String, ImportWidget> widgets, Set<String> maskedSkips) {
        RawRow row = new RawRow();
        row.setRowNo(line.rowNo);

        for (Map.Entry<Integer, String> entry : line.texts.entrySet()) {
            int column = entry.getKey();
            String field = mapping.getColumnToField().get(column);
            if (field == null) {
                // 没识别出来的列：值直接丢掉，错误在表头映射的报告里已经说过了，
                // 每一行再报一遍会刷屏
                continue;
            }
            ImportWidget widget = widgets.get(field);
            if (widget == null) {
                continue;
            }

            String rawText = entry.getValue();
            row.getRawTexts().put(field, rawText);

            // 公式字段与只读字段：不读文件里的值。
            // 但仍然记下原始文本 —— 公式重算后要拿它跟算出来的值比，
            // 不一样时提示用户「你填的没生效」
            if (!ImportWidgetRegistry.isValueFromFile(widget)) {
                continue;
            }

            // ⚠️ 脱敏列的掩码值当成「这一列没填」
            //
            // 导出是带脱敏的（138****8000）—— 那是对的，导出是泄露量最大的出口。
            // 但这也意味着「导出→改→导回」时这一列是掩码，它过不了格式校验。
            //
            // 语义与编辑页保持一致：提交的是掩码 = 不改这个字段。于是
            //   * 覆盖更新时该列不参与更新，库里的真实值保留
            //   * 新增时该列为空 —— 这是对的，掩码里还原不出真手机号
            // 两种都比「报一句正则不匹配」有意义。
            if (widget.isMasked() && looksMasked(rawText)) {
                maskedSkips.add(widget.label());
                continue;
            }

            Object cellValue = line.cells.get(column);
            ImportCellReader.Outcome outcome = cellReader.read(cellValue, widget);
            if (outcome.isError()) {
                RowError error = ImportDryRunValidator.buildError(
                        line.rowNo, ImportHeaderMapper.columnLetter(column), field,
                        widget.label(), outcome.getError(), rawText);
                row.getErrors().add(error);
                continue;
            }
            row.getData().put(field, outcome.getValue());
        }
        return row;
    }

    /**
     * 这一行是不是「视图产物」（合计行 / 分组行）
     *
     * 判据是<b>首个非空单元格等于契约里的标记文本</b>，
     * 而不是「关键列为空」—— 后者会把用户真的漏填编码的行一起跳过，
     * 而那一行本该报错。
     *
     * 导出时合计行的首列写的就是这个标记（见 exportExcel 的 buildSummaryRow）。
     */
    private boolean isViewArtifactRow(ImportSheetReader.RawLine line) {
        String first = null;
        for (String text : line.texts.values()) {
            if (StrUtil.isNotBlank(text)) {
                first = text.trim();
                break;
            }
        }
        if (first == null) {
            return false;
        }
        CellFormatContract.RowKinds rowKinds = cellFormatRegistry.getRowKinds();
        if (!rowKinds.isSkipOnImport()) {
            return false;
        }
        if (first.equals(rowKinds.getSummaryLabel())) {
            return true;
        }
        return StrUtil.isNotBlank(rowKinds.getGroupLabelPrefix())
                && first.startsWith(rowKinds.getGroupLabelPrefix());
    }

    /**
     * 这个值看起来是不是掩码
     *
     * 判据是「含连续两个及以上的掩码填充符」。只对<b>配了脱敏的字段</b>用这个判据 ——
     * 对普通字段用会把用户真想填的 {@code a**b} 当成掩码吃掉。
     *
     * 而配了脱敏的字段（手机号、身份证、银行卡）本来就不该出现连续星号，
     * 所以在这个范围内误判的风险可以接受。
     */
    private boolean looksMasked(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        return MASK_PATTERN.matcher(text).find();
    }

    /**
     * 掩码填充符。与 {@link cn.enlev.ainb.module.infra.framework.formdesign.core.mask.MaskRule}
     * 的默认 filler 一致
     */
    private static final java.util.regex.Pattern MASK_PATTERN =
            java.util.regex.Pattern.compile("[*＊]{2,}");

    private RowError tableError(String message) {
        RowError error = new RowError();
        error.setMessage(message);
        return error;
    }

}
