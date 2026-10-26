package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表格导入的公共模型
 *
 * @author 走进新时代
 */
public final class ImportModels {

    private ImportModels() {
    }

    /**
     * 一条待导入的行在库里的状态
     */
    public enum RowStatus {
        /**
         * 库里没有，将新增
         */
        NEW,
        /**
         * 按更新键命中了库里的记录，将更新（或跳过，取决于所选策略）
         */
        EXISTS,
        /**
         * 与文件里的另一行冲突
         */
        DUPLICATE_IN_FILE,
        /**
         * 校验不通过
         */
        INVALID
    }

    /**
     * 重复数据的处理策略
     */
    public enum DuplicateStrategy {
        /**
         * 跳过并报告（默认）。
         *
         * 选它当默认值是因为另外两个都会改数据，而「改数据」这件事
         * 用户应当在看清楚有多少条命中之后再做决定。
         */
        SKIP,
        /**
         * 按更新键覆盖已有记录
         */
        UPDATE,
        /**
         * 不管有没有，一律新增
         */
        APPEND
    }

    /**
     * 错误的级别
     */
    public enum ErrorLevel {
        /**
         * 这一行进不去
         */
        ERROR,
        /**
         * 这一行能进去，但有需要用户知道的情况（比如文件里的公式值与服务端算的不一样）
         */
        WARN
    }

    /**
     * 一条具体的错误
     */
    @Data
    public static class RowError {

        /**
         * 出自文件的第几行（**从 1 开始，且含表头**）。
         *
         * 用它是因为用户是拿 Excel 行号去找的 —— 报数组下标（从 0 开始、
         * 不含表头）他得自己换算，而换算错了就会改错行。
         */
        private int rowNo;

        /**
         * 列字母。主表字段之外（比如整行级错误）为空
         */
        private String columnLetter;

        /**
         * 出错的字段名
         */
        private String field;

        /**
         * 字段标题。报给用户看的是这个
         */
        private String fieldLabel;

        /**
         * 原始单元格文本，便于用户对照
         */
        private String rawValue;

        /**
         * 错误原因
         */
        private String message;

        private ErrorLevel level = ErrorLevel.ERROR;
    }

    /**
     * 解析出来的一行原始数据
     */
    @Data
    public static class RawRow {

        /**
         * 文件里的行号（从 1 开始，含表头行）
         */
        private int rowNo;

        /**
         * 字段名 → 值
         */
        private Map<String, Object> data = new LinkedHashMap<>();

        /**
         * 字段名 → 原始单元格文本。报错时回显用
         */
        private Map<String, String> rawTexts = new LinkedHashMap<>();

        /**
         * 这一行是怎么坏掉的
         */
        private List<RowError> errors = new ArrayList<>();

        /**
         * 不需要用户确认的提示
         */
        private List<RowError> warnings = new ArrayList<>();

        public boolean hasError() {
            return errors.stream().anyMatch(e -> e.getLevel() == ErrorLevel.ERROR);
        }
    }

    /**
     * 主表字段的声明
     */
    @Data
    public static class FieldSpec {
        private String field;
        private String title;
    }

}
