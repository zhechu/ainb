package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportModels.RawRow;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportModels.RowError;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主子表行的分组
 *
 * 把「一行一条明细」的扁平表格，还原成「一条主记录 + N 条明细」的嵌套结构。
 *
 * <h3>为什么用「相邻行同父键即归组」而不是「按父键全表归组」</h3>
 *
 * 全表归组（同一个父键的行不管隔多远都合并）看起来更宽容，但它会**掩盖一个真问题**：
 *
 * <pre>
 * XM001 | A项目 | 差旅费 | 1200
 * XM002 | B项目 | 差旅费 |  500
 * XM001 | A项目 | 材料费 |  800   ← 这里其实是用户漏改了编码
 * </pre>
 *
 * 全表归组会把两条 XM001 合成一张主单，用户看到「成功了」，
 * 却不知道自己的表里 XM001 被拆成了两段 —— 等他下次导一份真的分段的表，
 * 得到的合并结果就完全不对了。
 *
 * 相邻归组 + **发现乱序就报错**则把这个问题摆到台面上：要么改文件，
 * 要么明确知道自己在做什么。数据导入这件事上，「猜」的代价总是比「报错」高。
 *
 * <h3>明细行的拼装约定</h3>
 *
 * 组内**每一行对应一条明细**。行内填了哪些明细列就写哪些，
 * 全空的明细列不写（不是写 null）。同一组内多张子表各自独立累积：
 * 这一行填了联系人列没填银行列，就只在联系人里加一条，银行那边不动。
 *
 * @author 走进新时代
 */
@Component
public class ImportRowGrouper {

    /**
     * 一条主记录及其明细
     */
    @Data
    public static class Group {

        /**
         * 主表字段的值
         */
        private Map<String, Object> mainData = new LinkedHashMap<>();

        /**
         * 明细：子表控件的字段名 → 明细行列表
         */
        private Map<String, List<Map<String, Object>>> subData = new LinkedHashMap<>();

        /**
         * 这几行在文件里覆盖的行号（用于报错时指出「第 3~5 行」）
         */
        private List<Integer> rowNos = new ArrayList<>();

        /**
         * 主表字段名 → 原始文本，报错回显
         */
        private Map<String, String> rawTexts = new LinkedHashMap<>();

        /**
         * 组内所有行的问题汇总（明细行的错误也算这一组的）
         */
        private List<RowError> errors = new ArrayList<>();

        private List<RowError> warnings = new ArrayList<>();

        public int firstRowNo() {
            return rowNos.isEmpty() ? 0 : rowNos.get(0);
        }

        public int lastRowNo() {
            return rowNos.isEmpty() ? 0 : rowNos.get(rowNos.size() - 1);
        }

        /**
         * 「第 3~5 行」或「第 3 行」
         */
        public String rowRangeText() {
            if (rowNos.isEmpty()) {
                return "";
            }
            return firstRowNo() == lastRowNo()
                    ? "第 " + firstRowNo() + " 行"
                    : "第 " + firstRowNo() + "~" + lastRowNo() + " 行";
        }

        public boolean hasError() {
            return errors.stream().anyMatch(e -> e.getLevel() == ImportModels.ErrorLevel.ERROR);
        }
    }

    /**
     * 分组结果
     */
    @Data
    public static class Result {
        private List<Group> groups = new ArrayList<>();
        /**
         * 整表级的错误（比如「必须按父键排序」），不属于某一行
         */
        private List<RowError> tableErrors = new ArrayList<>();
    }

    /**
     * 分组
     *
     * @param rows         解析好的行，顺序与文件一致
     * @param widgets      可导入字段
     * @param parentFields 父行键的字段名列表。
     *                     为空时表示这张表单没有子表，每一行各成一组
     * @return 分组结果
     */
    public Result group(List<RawRow> rows, Map<String, ImportWidget> widgets,
                        List<String> parentFields) {
        Result result = new Result();
        if (rows.isEmpty()) {
            return result;
        }

        boolean hasSubTable = parentFields != null && !parentFields.isEmpty();

        // 没有父键（单表表单）—— 一行一组，不涉及合并
        if (!hasSubTable) {
            for (RawRow row : rows) {
                Group group = new Group();
                group.setMainData(new LinkedHashMap<>(row.getData()));
                group.setRawTexts(new LinkedHashMap<>(row.getRawTexts()));
                group.getRowNos().add(row.getRowNo());
                group.getErrors().addAll(row.getErrors());
                group.getWarnings().addAll(row.getWarnings());
                collectSubRows(group, row, widgets);
                result.getGroups().add(group);
            }
            return result;
        }

        // 有父键 —— 相邻同键的行归为一组，同时检测乱序
        Map<String, Integer> seenKeys = new LinkedHashMap<>();
        Group current = null;
        String currentKey = null;

        for (RawRow row : rows) {
            String key = buildKey(row, parentFields);

            if (key == null) {
                // 父键有空值：这一行归不到任何一组。
                // 单独成一组并报错，而不是塞进上一组 —— 塞进去的话用户完全看不出问题
                Group orphan = new Group();
                orphan.setMainData(new LinkedHashMap<>(row.getData()));
                orphan.setRawTexts(new LinkedHashMap<>(row.getRawTexts()));
                orphan.getRowNos().add(row.getRowNo());
                orphan.getErrors().addAll(row.getErrors());
                RowError error = new RowError();
                error.setRowNo(row.getRowNo());
                error.setMessage("这一行没有填「" + describeParentFields(parentFields, widgets)
                        + "」，无法判断它属于哪条主记录");
                orphan.getErrors().add(error);
                collectSubRows(orphan, row, widgets);
                result.getGroups().add(orphan);
                current = null;
                currentKey = null;
                continue;
            }

            if (!key.equals(currentKey)) {
                // 换了一条主记录。先查这个键之前出现过没有 —— 出现过就是乱序
                if (seenKeys.containsKey(key)) {
                    RowError error = new RowError();
                    error.setRowNo(row.getRowNo());
                    error.setLevel(ImportModels.ErrorLevel.ERROR);
                    error.setMessage(String.format(
                            "「%s」在第 %d 行已经出现过，这里又出现了 —— 明细行必须按主记录排在一起。"
                                    + "请先在 Excel 里按这一列排序，再重新导入",
                            key, seenKeys.get(key)));
                    result.getTableErrors().add(error);
                }
                seenKeys.putIfAbsent(key, row.getRowNo());

                current = new Group();
                // ⚠️ 主表的值取这一组的**第一行**。
                //    同一组内后续行的主表列若有不同值，reportMainFieldConflicts 会报出来
                current.setMainData(new LinkedHashMap<>(row.getData()));
                current.setRawTexts(new LinkedHashMap<>(row.getRawTexts()));
                result.getGroups().add(current);
                currentKey = key;
            } else {
                reportMainFieldConflicts(current, row, widgets);
            }

            current.getRowNos().add(row.getRowNo());
            current.getErrors().addAll(row.getErrors());
            current.getWarnings().addAll(row.getWarnings());

            // 这一行的明细列 → 这个组里对应子表的下一条明细
            collectSubRows(current, row, widgets);
        }
        return result;
    }

    /**
     * 把这一行的明细列挂到组里
     *
     * 组内每来一行就为**每张子表各开一条明细**。
     *
     * ⚠️ 两张子表各自独立开行，而不是按「第几行」对齐。
     *    一份表里联系人填了 3 行、银行只填了 1 行，那时候银行不该跟着
     *    开出 3 条空明细 —— 那会让导完之后冒出一堆空账户。
     *    所以判据是「这一行在这张子表上有没有填出至少一个非空值」。
     */
    private void collectSubRows(Group group, RawRow row, Map<String, ImportWidget> widgets) {
        // 子表控件字段名 → 该子表在这一行上收集到的列
        Map<String, Map<String, Object>> rowValues = new LinkedHashMap<>();

        for (ImportWidget widget : widgets.values()) {
            if (!widget.isSubField()) {
                continue;
            }
            Object value = row.getData().get(widget.getField());
            if (isEmptyValue(value)) {
                continue;
            }
            rowValues.computeIfAbsent(widget.getSubField(), k -> new LinkedHashMap<>())
                    .put(widget.getField(), value);
        }

        for (Map.Entry<String, Map<String, Object>> entry : rowValues.entrySet()) {
            List<Map<String, Object>> subRows =
                    group.getSubData().computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
            subRows.add(entry.getValue());
        }
    }

    /**
     * 值算不算「没填」
     *
     * 空串、空集合、空 Map 都算。数字 0 与布尔 false **不算** ——
     * 它们是有意义的值，当成空的话「把主联系人取消勾选」这类导入会失效。
     */
    private boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }

    /**
     * 同一组内主表列的值不一致
     *
     * 「第一条 XM001 叫 A项目、第二条 XM001 叫 B项目」——这是文件本身有问题。
     * 静默取第一行的话，用户填的 B项目 被无声丢掉。
     */
    private void reportMainFieldConflicts(Group current, RawRow row,
                                          Map<String, ImportWidget> widgets) {
        for (Map.Entry<String, Object> entry : row.getData().entrySet()) {
            ImportWidget widget = widgets.get(entry.getKey());
            if (widget == null || widget.isSubField()) {
                continue;
            }
            Object first = current.getMainData().get(entry.getKey());
            Object now = entry.getValue();
            if (first == null && now == null) {
                continue;
            }
            if (first != null && first.equals(now)) {
                continue;
            }
            // 后面留空是正常的：用户合并了单元格、或懒得重复填。
            // 不报错，也不清除已有的值
            if (now == null) {
                continue;
            }
            // 第一次是空、后面填了：用后面的补上。同一组内主表列本来就该一样
            if (first == null) {
                current.getMainData().put(entry.getKey(), now);
                String raw = row.getRawTexts().get(entry.getKey());
                if (raw != null) {
                    current.getRawTexts().put(entry.getKey(), raw);
                }
                continue;
            }
            RowError error = new RowError();
            error.setRowNo(row.getRowNo());
            error.setField(entry.getKey());
            error.setFieldLabel(widget.label());
            error.setMessage(String.format(
                    "同一条主记录（%s）里「%s」的值不一致：第 %d 行是「%s」，第 %d 行是「%s」。"
                            + "同一组内主表列必须相同",
                    current.rowRangeText(), widget.label(),
                    current.firstRowNo(), first, row.getRowNo(), now));
            current.getErrors().add(error);
        }
    }

    /**
     * 用父键字段拼出分组键
     *
     * ⚠️ 分隔符必须是**用户打不出来**的字符。
     *
     * 用「|」「-」「,」这类常见符号的话，「A|B」与「A」+「B」会拼出同一个键 ——
     * 于是两条不同的主记录被合成一条，用户看到的是「明细少了一半」。
     * 而  在任何输入法里都敲不出来。
     *
     * @return 任一字段为空时返回 null
     */
    private String buildKey(RawRow row, List<String> parentFields) {
        StringBuilder sb = new StringBuilder();
        for (String field : parentFields) {
            Object value = row.getData().get(field);
            if (value == null || (value instanceof String s && s.isBlank())) {
                return null;
            }
            sb.append(value).append('\u0001');
        }
        return sb.toString();
    }

    private String describeParentFields(List<String> parentFields,
                                        Map<String, ImportWidget> widgets) {
        List<String> labels = new ArrayList<>();
        for (String field : parentFields) {
            ImportWidget widget = widgets.get(field);
            labels.add(widget != null ? widget.label() : field);
        }
        return String.join(" + ", labels);
    }

}
