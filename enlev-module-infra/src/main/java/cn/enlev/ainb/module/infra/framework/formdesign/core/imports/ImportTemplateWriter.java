package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.hutool.core.util.StrUtil;
import cn.idev.excel.FastExcelFactory;
import cn.enlev.ainb.framework.dict.core.DictFrameworkUtils;
import cn.idev.excel.ExcelWriter;
import cn.idev.excel.write.handler.SheetWriteHandler;
import cn.idev.excel.write.metadata.holder.WriteSheetHolder;
import cn.idev.excel.write.metadata.holder.WriteWorkbookHolder;
import cn.idev.excel.write.metadata.WriteSheet;
import cn.idev.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入模板的生成
 *
 * <h3>为什么模板必须按字段动态生成，不能是一份静态文件</h3>
 *
 * 静态模板换个表单就不能用了 —— 而本模块的表单是用户在界面上随时新建的。
 * 更要紧的是：静态模板里的表头一旦与表单字段对不上，
 * 用户照着填完却导不进去，而他会认为是导入功能有问题。
 *
 * 这里生成的模板与表单字段是**同一份定义的两个视图**，
 * 表头取的就是字段标题，不可能对不上。
 *
 * <h3>模板的三行结构</h3>
 *
 * <pre>
 * 第 1 行：字段标题（必填的带 *），加粗、灰底、冻结、自动筛选
 * 第 2 行：填写说明（浅灰斜体）—— 日期格式是什么、字典有哪些选项、数值范围
 * 第 3 行：一行示例数据 —— 用户最容易理解「该怎么填」的方式是看一行示例
 * </pre>
 *
 * 三行都保留，用户填的时候把第 3 行改成自己的数据或用它当参照都行。
 * 导入时第 2 行会被忽略 —— 它不在数据区里（表头只认第 1 行）。
 *
 * <h3>⚠️ 示例数据里不能放脱敏字段的真实值</h3>
 *
 * 脱敏字段的示例值一律留空。放一个假的手机号看起来无害，
 * 但用户很可能直接把它改成真的，而那个假号看上去像真的 ——
 * 于是「张三 138****5678」这种从库里脱敏出来的值有可能被当成示例抄进模板。
 *
 * @author 走进新时代
 */
@Slf4j
@Component
public class ImportTemplateWriter {

    /**
     * 表头里的必填标记
     */
    private static final String REQUIRED_MARK = " *";

    @Resource
    private ImportWidgetRegistry widgetRegistry;

    /**
     * 生成并写出模板
     *
     * @param response        HTTP 响应
     * @param formName        表单名，做文件名与 sheet 名
     * @param formFieldsJson  表单字段规则
     * @param listConfJson    列表配置
     */
    public void write(HttpServletResponse response, String formName, String formFieldsJson,
                      String listConfJson) throws IOException {
        // 布尔示例的交替计数每次从头开始 —— 同一张表单两次下载必须得到同一份模板
        booleanCounter.get()[0] = 0;

        Map<String, ImportWidget> widgets = widgetRegistry.parse(formFieldsJson, listConfJson);
        // 不进模板的两类字段：
        //   * 公式字段 —— 值由服务端算，让用户填只会造成「我填了怎么没生效」
        //   * 只读字段 —— 同理。排序键这类由脚本维护的字段就属于这一类，
        //     放进模板会让用户以为自己该填点什么
        List<ImportWidget> columns = widgets.values().stream()
                .filter(w -> !w.isFormula() && !w.isDisabled())
                .toList();

        List<String> headers = new ArrayList<>(columns.size());
        List<String> hints = new ArrayList<>(columns.size());
        List<String> samples = new ArrayList<>(columns.size());
        Map<Integer, List<String>> selectOptions = new LinkedHashMap<>();

        for (int i = 0; i < columns.size(); i++) {
            ImportWidget widget = columns.get(i);
            headers.add(widget.label() + (widget.isRequired() ? REQUIRED_MARK : ""));
            hints.add(buildHint(widget));
            samples.add(buildSample(widget));

            List<String> options = dictOptions(widget);
            if (!options.isEmpty()) {
                selectOptions.put(i, options);
            }
        }

        String fileName = (StrUtil.isBlank(formName) ? "导入模板" : formName + "-导入模板")
                + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument."
                + "spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''"
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8));

        try (ExcelWriter writer = FastExcelFactory.write(response.getOutputStream())
                .autoCloseStream(false)
                .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                .registerWriteHandler(new ImportTemplateSelectHandler(selectOptions))
                .registerWriteHandler(new TemplateStyleHandler(headers.size()))
                .build()) {
            WriteSheet sheet = FastExcelFactory.writerSheet()
                    .sheetName(sheetName(formName))
                    .head(headList(headers, hints))
                    .build();
            // 只写这一行示例数据。它同时占住了第 3 行，让用户看得见该填成什么样
            writer.write(List.of(samples), sheet);
        }
        // ⚠️ 不在这里设 Content-Type/Disposition —— 理由与框架里其它导出一致：
        //    写出后再设已经来不及。上面已经先设好了
    }

    /**
     * 表头：两行，第一行标题第二行说明
     *
     * FastExcel 的多行表头是「每个表头格一个 List」，内层每个元素一行。
     */
    private List<List<String>> headList(List<String> headers, List<String> hints) {
        List<List<String>> head = new ArrayList<>(headers.size());
        for (int i = 0; i < headers.size(); i++) {
            head.add(List.of(headers.get(i), hints.get(i)));
        }
        return head;
    }

    /**
     * 一列的填写说明
     *
     * 说清「该填什么格式」，用户就不用试错了。这一行是模板里最省钱的一处设计：
     * 没有它，日期列会有百分之几十的人填成 {@code 2026/9/14}、
     * 字典列会填成字典里没有的词，然后回头找管理员问。
     */
    private String buildHint(ImportWidget widget) {
        List<String> parts = new ArrayList<>();
        if (widget.isRequired()) {
            parts.add("必填");
        }

        String type = widget.getType();
        if (List.of("datePicker", "dateRange").contains(type)) {
            parts.add("datetime".equalsIgnoreCase(String.valueOf(widget.getDateType()))
                    ? "格式 2026-09-14 10:30:00" : "格式 2026-09-14");
        } else if (List.of("timePicker", "timeRange").contains(type)) {
            parts.add("格式 10:30:00");
        } else if (List.of("select", "radio", "DictSelect", "checkbox", "elTreeSelect",
                "tree", "cascader", "elTransfer").contains(type)) {
            if (widget.getDictType() != null) {
                parts.add("从下拉里选");
            }
        } else if ("inputNumber".equals(type) || "slider".equals(type)
                || "rate".equals(type)) {
            parts.add(widget.getPrecision() != null && widget.getPrecision() > 0
                    ? "数字，最多 " + widget.getPrecision() + " 位小数" : "整数");
        } else if ("switch".equals(type)) {
            parts.add("填 是 / 否");
        } else if (List.of("UploadFile", "UploadImg", "UploadImgs", "Signature",
                "signaturePad", "upload").contains(type)) {
            parts.add("填文件地址（本列不支持导入内嵌图片）");
        }

        if (widget.isSubField()) {
            parts.add("「" + (StrUtil.isBlank(widget.getSubTitle())
                    ? "明细" : widget.getSubTitle()) + "」的明细列");
        }
        if (widget.isMasked()) {
            parts.add("已脱敏，改动后请填完整值");
        }
        if (widget.isUniqueCheck()) {
            parts.add("不能与已有数据重复");
        }
        return String.join("；", parts);
    }

    /**
     * 一列的示例值
     *
     * 脱敏字段留空（理由见类注释）。其余按控件类型给一个「一眼就知道怎么填」的值。
     */
    private String buildSample(ImportWidget widget) {
        if (widget.isMasked()) {
            return "";
        }
        String type = widget.getType();
        if (List.of("datePicker", "dateRange").contains(type)) {
            return "datetime".equalsIgnoreCase(String.valueOf(widget.getDateType()))
                    ? "2026-09-14 10:30:00" : "2026-09-14";
        }
        if (List.of("timePicker", "timeRange").contains(type)) {
            return "10:30:00";
        }
        if ("switch".equals(type)) {
            // 交替给「是」与「否」：两个布尔列都给「是」的话，
            // 用户只学到了一半 —— 而「否」该怎么写恰恰是更容易填错的那个
            return booleanSampleFlip() ? "是" : "否";
        }
        if ("inputNumber".equals(type) || "slider".equals(type) || "rate".equals(type)) {
            return numberSample(widget);
        }
        if (List.of("UploadFile", "UploadImg", "UploadImgs", "Signature",
                "signaturePad", "upload").contains(type)) {
            return "https://example.com/file.pdf";
        }
        // 字典列与文本列都留空 —— 字典列给一个选项里的值会诱导用户照抄，
        // 而那不是真实数据；文本列给什么都会变成用户的实际数据
        return "";
    }

    /**
     * 数值列的示例值
     *
     * ⚠️ 必须落在字段声明的范围内。统一给 1000.00 的话，
     *    「税率」这种上限是 1 的列会得到一个非法示例 ——
     *    用户照着填一定被拦下，而他会认为是导入功能有问题。
     *
     * 有上限时取上限的一半（再按精度定标），没上限就用一个常见的量级。
     */
    private String numberSample(ImportWidget widget) {
        int precision = widget.getPrecision() == null ? 0 : Math.max(widget.getPrecision(), 0);
        java.math.BigDecimal max = widget.getMax();
        java.math.BigDecimal value;
        if (max != null) {
            value = max.divide(java.math.BigDecimal.valueOf(2), precision,
                    java.math.RoundingMode.HALF_UP);
            // 上限很小（比如 max = 1、precision = 0）时会算出 0，给 0 当示例没有信息量，
            // 直接用上限本身
            if (value.signum() == 0) {
                value = max;
            }
        } else {
            value = precision > 0 ? new java.math.BigDecimal("1000") : new java.math.BigDecimal("10");
        }
        return value.setScale(precision, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 布尔示例值的交替计数
     *
     * ⚠️ 用实例字段而不是静态：这个类是单例，静态计数会让两次下载的模板不一样，
     *    而「同一张表单下两次模板不同」会让人怀疑模板是不是随机生成的。
     *    实例字段在一次 write 里从 0 开始 —— 见 write() 里的重置。
     */
    private final ThreadLocal<int[]> booleanCounter = ThreadLocal.withInitial(() -> new int[1]);

    private boolean booleanSampleFlip() {
        int[] counter = booleanCounter.get();
        return counter[0]++ % 2 == 0;
    }

    /**
     * 字典选项
     *
     * 走框架的字典工具取，与单元格解析用的是同一份数据 ——
     * 下拉里给出的选项与「填写时能通过校验的选项」必须是同一个集合，
     * 否则会出现「下拉里选得到、导进去报不在字典范围」这种自相矛盾的情况。
     */
    private List<String> dictOptions(ImportWidget widget) {
        if (widget.getDictType() == null) {
            return List.of();
        }
        try {
            return DictFrameworkUtils.getDictDataLabelList(widget.getDictType()).stream()
                    .filter(StrUtil::isNotBlank).toList();
        } catch (Exception e) {
            // 字典不存在或取不到：模板照常出，只是没有下拉。
            // 让模板生成失败的话，一个字典配错会连累整张表单导不进来
            return List.of();
        }
    }

    private String sheetName(String formName) {
        String name = StrUtil.isBlank(formName) ? "数据" : formName;
        // Excel 的 sheet 名不能超过 31 字符，也不能含 : \ / ? * [ ]
        name = name.replaceAll("[:\\\\/?*\\[\\]]", "_");
        return name.length() > 28 ? name.substring(0, 28) : name;
    }

    /**
     * 表头的样式与冻结
     *
     * FastExcel 的样式注解也要一个类，这里没有类可用，只能用 POI 直接改。
     */
    private static class TemplateStyleHandler implements SheetWriteHandler {

        private final int columnCount;

        TemplateStyleHandler(int columnCount) {
            this.columnCount = columnCount;
        }

        @Override
        public void afterSheetCreate(WriteWorkbookHolder writeWorkbookHolder,
                                     WriteSheetHolder writeSheetHolder) {
            Sheet sheet = writeSheetHolder.getSheet();

            CellStyle headerStyle = writeWorkbookHolder.getWorkbook().createCellStyle();
            Font headerFont = writeWorkbookHolder.getWorkbook().createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorder(headerStyle);

            CellStyle hintStyle = writeWorkbookHolder.getWorkbook().createCellStyle();
            Font hintFont = writeWorkbookHolder.getWorkbook().createFont();
            hintFont.setItalic(true);
            hintFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            hintStyle.setFont(hintFont);
            hintStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorder(hintStyle);

            // ⚠️ FastExcel 写完后单元格可能还没创建出来（它按需建行）。
            //    所以要显式建出这两行再改样式，不能只遍历已存在的
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                headerRow = sheet.createRow(0);
            }
            Row hintRow = sheet.getRow(1);
            if (hintRow == null) {
                hintRow = sheet.createRow(1);
            }
            for (int i = 0; i < columnCount; i++) {
                Cell headerCell = headerRow.getCell(i);
                if (headerCell == null) {
                    headerCell = headerRow.createCell(i);
                }
                headerCell.setCellStyle(headerStyle);

                Cell hintCell = hintRow.getCell(i);
                if (hintCell == null) {
                    hintCell = hintRow.createCell(i);
                }
                hintCell.setCellStyle(hintStyle);
            }
            headerRow.setHeightInPoints(24f);

            // 冻结前两行（表头 + 说明）与前三列之外的部分不做冻结 ——
            // 用户填到第 60 行时仍然看得见列名
            sheet.createFreezePane(0, 2);
        }

        private void applyBorder(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
    }

}
