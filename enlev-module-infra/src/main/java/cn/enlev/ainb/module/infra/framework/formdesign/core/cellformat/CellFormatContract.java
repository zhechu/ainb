package cn.enlev.ainb.module.infra.framework.formdesign.core.cellformat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 一种列格式化的契约
 *
 * 与 {@code cell-format-contracts.json} 的 formatters 条目一一对应。
 *
 * <p>⚠️ 字段只增不改语义：契约文件里带 {@code _comment}，
 * 而 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 让它们被忽略 ——
 * 注释是给人看的，不该逼着模型上多一个用不到的字段。
 *
 * @author 走进新时代
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CellFormatContract {

    /**
     * 格式化类型：NONE / DICT / TAG / DATE / DATETIME / NUMBER / PERCENT / BOOLEAN / LINK / IMAGE / FILE
     */
    private String type;

    /**
     * 设计器下拉里的文案
     */
    private String label;

    /**
     * 导出规则
     */
    private ExportRule export;

    /**
     * 导出规则
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExportRule {

        /**
         * TEXT：出一个字符串。
         * VALUE_WITH_FMT：出「真值 + 数字格式」——
         * Excel 里显示格式与值是两层，于是「看起来和列表一样」与「能改完导回来」同时成立
         */
        private String mode;

        /**
         * TEXT 模式的取值来源：RAW / DICT_LABEL / BOOLEAN_TEXT / FILE_URL
         */
        private String source;

        /**
         * VALUE_WITH_FMT 模式的值类型：DATE / DATETIME / NUMBER
         */
        private String valueKind;

        /**
         * 固定的数字格式串。日期类用它
         */
        private String numFmt;

        /**
         * 数字格式串按 numberFormat 规则动态拼。数值类用它
         */
        private String numFmtFrom;

        /**
         * 拼出来的格式串后面再接一段。百分比是 "%"
         */
        private String numFmtSuffix;

        /**
         * ⚠️ 百分比专用：列配了 alreadyScaled（库里存的是 13 而不是 0.13）时，
         * 导出要先除以 100 —— Excel 的 % 格式会自动乘 100 显示，
         * 不除的话会印出 1300.00%
         */
        private Boolean divideBy100WhenAlreadyScaled;

        /**
         * 标签色是否用单元格填充色表达
         */
        private Boolean fillByDictColor;

        /**
         * 是否跟随列表的显示形态。
         *
         * 为 false 表示<b>故意不跟随</b>（超链接、图片、附件三种），
         * 理由写在契约文件头。这个字段存在的意义是让「不跟随」是一个
         * 被声明过的决定，而不是某处实现忘了做。
         */
        private Boolean followsList;
    }

    /**
     * 数字格式串的拼装规则
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NumberFormat {
        private String integer = "0";
        private String integerThousands = "#,##0";
        private String decimalSeparator = ".";
        private String decimalDigit = "0";
        private Integer defaultPrecision = 2;

        /** 列没配 precision 时用契约里的默认值 */
        public int precisionOr(Integer precision) {
            if (precision != null && precision >= 0) {
                return precision;
            }
            return defaultPrecision == null ? 2 : defaultPrecision;
        }
    }

    /**
     * 布尔的文案与判真规则
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BooleanText {
        private String trueText = "是";
        private String falseText = "否";
        private List<Object> trueValues = List.of(true, 1, "1");
    }

    /**
     * 「视图产物」行的标记
     *
     * 合计行、分组行跟随列表导出，但它们<b>不是数据</b> ——
     * 同一份文件导回来时必须被识别并跳过，否则合计行会被当成一条记录。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RowKinds {
        private String summaryLabel = "合计";
        private String groupLabelPrefix = "▸ ";
        private Boolean skipOnImport = true;

        public boolean isSkipOnImport() {
            return !Boolean.FALSE.equals(skipOnImport);
        }
    }

}
