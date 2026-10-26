package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.idev.excel.write.handler.SheetWriteHandler;
import cn.idev.excel.write.metadata.holder.WriteSheetHolder;
import cn.idev.excel.write.metadata.holder.WriteWorkbookHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;

import java.util.List;
import java.util.Map;

/**
 * 给字典列加数据有效性下拉
 *
 * <h3>为什么不能复用框架现成的 {@code SelectSheetWriteHandler}</h3>
 *
 * 那个是**基于注解**的：它反射扫一个 VO 类的 {@code @ExcelColumnSelect} 注解，
 * 从而知道第几列该放什么选项。而导入模板的列是**运行时**的 ——
 * 字段随时会变，根本没有对应的类。所以只能另写一个按列号直接接管的。
 *
 * <h3>为什么必须加内联选项而不是引用另一个 sheet</h3>
 *
 * Excel 的数据有效性如果引用了另一个 sheet 的区域，那个 sheet 必须存在且名字固定。
 * 用户拿到模板后**另存为 xlsx** 或**复制粘贴到自己的表里**时，
 * 这个引用很容易断掉 —— 断了之后下拉框静默消失，用户以为本来就没有。
 * 内联选项则跟着单元格走，不会失效。
 *
 * ⚠️ 内联选项的总长度有上限（Excel 约束约 255 字符）。
 * 选项过多时**放弃加下拉但不报错** —— 加一个被截断的选项列表
 * 比没有下拉更糟：用户会选到一个不存在的值。
 *
 * @author 走进新时代
 */
@Slf4j
public class ImportTemplateSelectHandler implements SheetWriteHandler {

    /**
     * 下拉生效的最大行数
     *
     * 设成 2000：模板是给用户填的，一次填几千行不现实；
     * 而给整列（1048576 行）加有效性会让文件变大很多、打开变慢。
     */
    private static final int LAST_ROW = 2000;

    /**
     * 内联选项串的最大长度
     *
     * Excel 对数据有效性里的公式串有长度上限，各家实现略有差异。
     * 取 250 留出余量 —— 超了会得到「文件损坏」这种最难查的报错。
     */
    private static final int MAX_INLINE_LENGTH = 250;

    /**
     * 列序号 → 选项文本列表
     */
    private final Map<Integer, List<String>> optionsByColumn;

    public ImportTemplateSelectHandler(Map<Integer, List<String>> optionsByColumn) {
        this.optionsByColumn = optionsByColumn;
    }

    @Override
    public void afterSheetCreate(WriteWorkbookHolder writeWorkbookHolder,
                                 WriteSheetHolder writeSheetHolder) {
        if (optionsByColumn == null || optionsByColumn.isEmpty()) {
            return;
        }
        Sheet sheet = writeSheetHolder.getSheet();
        DataValidationHelper helper = sheet.getDataValidationHelper();

        for (Map.Entry<Integer, List<String>> entry : optionsByColumn.entrySet()) {
            List<String> options = entry.getValue();
            if (options == null || options.isEmpty()) {
                continue;
            }

            // 选项里不能出现引号（会破坏公式串）。字典标签里带引号的情况很罕见，
            // 遇到就跳过这个下拉 —— 加一个坏掉的选项串会让整个文件打不开
            List<String> safe = options.stream()
                    .filter(o -> o != null && !o.isBlank() && !o.contains("\""))
                    .distinct().toList();
            if (safe.isEmpty()) {
                continue;
            }

            String formula = "\"" + String.join(",", safe) + "\"";
            if (formula.length() > MAX_INLINE_LENGTH) {
                log.info("[afterSheetCreate][字典列选项过多({} 项)，跳过下拉以保住文件可打开]",
                        safe.size());
                continue;
            }

            DataValidationConstraint constraint =
                    helper.createFormulaListConstraint(formula);
            // 第 0 行是表头，所以从第 1 行开始
            CellRangeAddressList range = new CellRangeAddressList(
                    1, LAST_ROW, entry.getKey(), entry.getKey());
            DataValidation validation = helper.createValidation(constraint, range);
            // 只做提示不做强制：用户粘贴进来的值不满足选项时，
            // 强制会直接拒绝并弹一个英文的 Excel 错误框，那对用户毫无帮助。
            // 非法值交给服务端的字典反查去拦，报错信息是中文的、能说清可选范围
            validation.setShowErrorBox(false);
            validation.setSuppressDropDownArrow(true);
            validation.setShowPromptBox(false);
            sheet.addValidationData(validation);
        }
    }

}
