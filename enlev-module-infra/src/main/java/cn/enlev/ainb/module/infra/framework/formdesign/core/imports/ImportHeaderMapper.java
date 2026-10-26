package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表头映射 —— 把 Excel 的列头对应到表单字段
 *
 * <h3>为什么必须按名字匹配，不能按位置</h3>
 *
 * 按位置对应（第 1 列表头之后就永远是第 1 个字段）在参考实现里就是这么做的，
 * 它带来的失败形态是最糟的一种：**表头顺序一变，数据就串到别的字段上去，
 * 而且不报错**。用户拿到的是一个「导入成功」的提示和一堆错位的数据，
 * 要自己去列表里比对才发现。
 *
 * 所以这里一律按名字匹配。匹配不上的列**不猜**，原样报告给用户。
 *
 * <h3>匹配的优先级</h3>
 *
 * <ol>
 *   <li>列表列的 label —— 用户多半是「导出、改一改、再导回来」，表头就是这个</li>
 *   <li>表单字段的 title</li>
 *   <li>字段名本身（project_code）</li>
 *   <li>归一化后的模糊匹配（去空格、全角转半角、去星号与尾括号）</li>
 * </ol>
 *
 * 前三条各自先走「归一化后完全相等」。这样可以避免一种尴尬：
 * 归一化把「金额（含税）」压成「金额」，而另有一个字段真叫「金额」——
 * 精确匹配要优先，否则真名「金额」的那个永远匹配不上。
 *
 * @author 走进新时代
 */
public class ImportHeaderMapper {

    /**
     * 匹配结果
     */
    @Data
    public static class Result {

        /**
         * Excel 第几列（从 0 开始）→ 字段名
         */
        private Map<Integer, String> columnToField = new LinkedHashMap<>();

        /**
         * 没识别出来的列。**不报错**，但要告诉用户 ——
         * 静默忽略会让人以为「那一列的数据进去了」
         */
        private List<UnmatchedColumn> unmatchedColumns = new ArrayList<>();

        /**
         * 重复匹配到同一个字段的列
         */
        private List<DuplicateColumn> duplicateColumns = new ArrayList<>();

        /**
         * 必填字段在表头里找不到
         */
        private List<String> missingRequiredFields = new ArrayList<>();

        /**
         * 匹配上的字段数
         */
        public int matchedCount() {
            return columnToField.size();
        }
    }

    /**
     * 一列没被识别出来
     */
    @Data
    public static class UnmatchedColumn {
        /**
         * 列序号（从 0 开始）
         */
        private int columnIndex;
        /**
         * 列字母（A、B、C…），报错时给用户看这个
         */
        private String columnLetter;
        /**
         * 原始表头文本
         */
        private String header;
    }

    /**
     * 一列重复匹配了已用过的字段
     */
    @Data
    public static class DuplicateColumn {
        private int columnIndex;
        private String columnLetter;
        private String header;
        /**
         * 它撞上的字段
         */
        private String field;
        /**
         * 该字段已经在第几列被用掉了（列字母）
         */
        private String firstColumnLetter;
    }

    /**
     * 建立映射
     *
     * @param headers 表头行（每个元素是一个单元格的文本，允许 null）
     * @param widgets 可导入字段，保持表单定义顺序
     * @return 映射结果
     */
    public Result map(List<String> headers, Map<String, ImportWidget> widgets) {
        Result result = new Result();

        // 字段名 → 归一化后的候选名（可能有多个：列名、标题、字段名）
        // 精确表与模糊表分开，保证「精确」永远赢过「模糊」
        Map<String, List<String>> exactNames = new LinkedHashMap<>();
        Map<String, List<String>> fuzzyNames = new LinkedHashMap<>();
        for (ImportWidget widget : widgets.values()) {
            List<String> exact = new ArrayList<>();
            List<String> fuzzy = new ArrayList<>();
            addName(widget.getColumnLabel(), exact, fuzzy);
            addName(widget.getTitle(), exact, fuzzy);
            addName(widget.getField(), exact, fuzzy);
            exactNames.put(widget.getField(), exact);
            fuzzyNames.put(widget.getField(), fuzzy);
        }

        // 已经被占用的字段 → 首次出现的列字母。用于报告重复列
        Map<String, String> takenFieldColumn = new LinkedHashMap<>();

        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            String normalized = ImportHeaderText.normalize(header);
            if (normalized.isEmpty()) {
                // 空表头列直接跳过。用户常在右侧留几列空列，报出来全是噪音
                continue;
            }

            // ⚠️ 匹配时**不**跳过已占用的字段
            //
            // 跳过的话，第二列「supplier_code」会匹配不到任何字段，
            // 于是被报成「这一列没有对应的表单字段，数据会被忽略」——
            // 而真相是「它对应的字段已经在 A 列用过了」。
            // 前者会让用户去改列名（改不对，因为列名本来就是对的），
            // 后者他一眼就知道该删掉哪一列。
            String field = matchExact(normalized, exactNames);
            if (field == null) {
                field = matchFuzzy(normalized, fuzzyNames);
            }

            if (field == null) {
                UnmatchedColumn unmatched = new UnmatchedColumn();
                unmatched.setColumnIndex(i);
                unmatched.setColumnLetter(columnLetter(i));
                unmatched.setHeader(header);
                result.getUnmatchedColumns().add(unmatched);
                continue;
            }

            if (takenFieldColumn.containsKey(field)) {
                // 已经用过了：**保留第一次的**，第二次报重复。
                // 反过来（后者覆盖前者）会让「A 列的值被 D 列悄悄覆盖」——
                // 用户看到的是 A 列明明填了却没进去
                DuplicateColumn duplicate = new DuplicateColumn();
                duplicate.setColumnIndex(i);
                duplicate.setColumnLetter(columnLetter(i));
                duplicate.setHeader(header);
                duplicate.setField(field);
                duplicate.setFirstColumnLetter(takenFieldColumn.get(field));
                result.getDuplicateColumns().add(duplicate);
                continue;
            }

            takenFieldColumn.put(field, columnLetter(i));
            result.getColumnToField().put(i, field);
        }

        // 必填字段没有匹配到任何一列
        for (ImportWidget widget : widgets.values()) {
            if (!widget.isRequired() || widget.isFormula()) {
                continue;
            }
            if (!takenFieldColumn.containsKey(widget.getField())) {
                result.getMissingRequiredFields().add(widget.label());
            }
        }
        return result;
    }

    /**
     * 精确匹配：归一化后完全相等
     */
    private String matchExact(String normalized, Map<String, List<String>> names) {
        for (Map.Entry<String, List<String>> entry : names.entrySet()) {
            if (entry.getValue().contains(normalized)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 模糊匹配：归一化后的前缀/包含关系
     *
     * 只在精确匹配全军覆没之后才走这里。典型场景是用户在表头后面接了说明：
     * 「项目编号（8 位）」而字段叫「项目编号」—— 归一化去括号能救回一部分，
     * 但「项目编号8位」这种去不掉的只能靠包含关系。
     *
     * ⚠️ 多条命中时**不猜**，返回 null 让它落到「未识别列」里。
     * 猜错的代价是数据串字段，而那个后果用户几乎发现不了。
     */
    private String matchFuzzy(String normalized, Map<String, List<String>> names) {
        String matched = null;
        for (Map.Entry<String, List<String>> entry : names.entrySet()) {
            boolean hit = false;
            for (String name : entry.getValue()) {
                // 长度太短的候选不参与模糊匹配：「编号」「金额」这种两个字的名字
                // 包含在几乎任何表头里，会大面积误命中
                if (name.length() < 3) {
                    continue;
                }
                if (normalized.contains(name) || name.contains(normalized)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            if (matched != null) {
                // 撞了不止一个字段，无法确定是哪个
                return null;
            }
            matched = entry.getKey();
        }
        return matched;
    }

    private void addName(String name, List<String> exact, List<String> fuzzy) {
        if (StrUtil.isBlank(name)) {
            return;
        }
        String normalized = ImportHeaderText.normalize(name);
        if (normalized.isEmpty() || exact.contains(normalized)) {
            return;
        }
        exact.add(normalized);
        fuzzy.add(normalized);
    }

    /**
     * 列序号 → 列字母
     *
     * 报错时给用户看列字母比给序号有用：序号 0 是 A 列还是 B 列，
     * 用户得自己数；而 Excel 自己的地址栏上写的就是字母。
     */
    public static String columnLetter(int index) {
        StringBuilder sb = new StringBuilder();
        int value = index;
        while (value >= 0) {
            sb.insert(0, (char) ('A' + value % 26));
            value = value / 26 - 1;
        }
        return sb.toString();
    }

}
