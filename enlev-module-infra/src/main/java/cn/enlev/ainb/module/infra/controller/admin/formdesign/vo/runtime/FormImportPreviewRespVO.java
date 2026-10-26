package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表格导入预览 Response VO
 *
 * 这一份的每一块都有明确用途，不是为了「信息全」而堆的：
 *
 * <ul>
 *   <li>{@code mappings} —— 让用户**看见**表头是怎么对上的。
 *       参考实现没有这一步，于是「哪一列对到了哪个字段」只能靠猜</li>
 *   <li>{@code rows} —— 前若干行的解析结果与逐行状态</li>
 *   <li>{@code summary} —— 全量统计。行数大时不能把全部行都发给前端</li>
 *   <li>{@code tableErrors} —— 整表级问题（比如明细没排序）</li>
 *   <li>{@code importToken} —— 执行时必须带上，且只能用一次</li>
 * </ul>
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 表格导入预览 Response VO")
@Data
public class FormImportPreviewRespVO {

    @Schema(description = "预览令牌。执行导入时必须原样带回", requiredMode = Schema.RequiredMode.REQUIRED)
    private String importToken;

    @Schema(description = "表头映射：Excel 列 → 字段")
    private List<ColumnMapping> mappings = new ArrayList<>();

    @Schema(description = "没识别出来的列")
    private List<UnmatchedColumn> unmatchedColumns = new ArrayList<>();

    @Schema(description = "重复匹配的列")
    private List<UnmatchedColumn> duplicateColumns = new ArrayList<>();

    @Schema(description = "必填字段在表头里找不到")
    private List<String> missingRequiredFields = new ArrayList<>();

    @Schema(description = "预览行（只回前若干行）")
    private List<PreviewRow> rows = new ArrayList<>();

    @Schema(description = "全量统计")
    private Summary summary = new Summary();

    @Schema(description = "整表级错误，如「明细行必须按主记录排序」")
    private List<PreviewError> tableErrors = new ArrayList<>();

    @Schema(description = "更新键的字段名。为空表示这张表单无法判断「已存在」，"
            + "此时覆盖策略不可用")
    private List<String> updateKeyFields = new ArrayList<>();

    @Schema(description = "模板里一共有多少条主记录（分组之后）")
    private Integer groupCount;

    /**
     * 一列的对应关系
     */
    @Schema(description = "列映射")
    @Data
    public static class ColumnMapping {

        @Schema(description = "列字母：A、B、C…", example = "A")
        private String columnLetter;

        @Schema(description = "Excel 里的表头原文", example = "供应商编码")
        private String header;

        @Schema(description = "匹配上的字段名", example = "supplier_code")
        private String field;

        @Schema(description = "字段标题", example = "供应商编码")
        private String fieldLabel;

        @Schema(description = "所属子表的标题。主表字段为空", example = "联系人明细")
        private String subTitle;

        @Schema(description = "是否必填")
        private Boolean required;

        @Schema(description = "是否公式字段（文件里的值会被服务端重算覆盖）")
        private Boolean formula;
    }

    /**
     * 没识别出来的列
     */
    @Schema(description = "未识别的列")
    @Data
    public static class UnmatchedColumn {

        @Schema(description = "列字母", example = "H")
        private String columnLetter;

        @Schema(description = "表头原文", example = "备注2")
        private String header;

        @Schema(description = "说明", example = "这一列没有对应的表单字段，数据会被忽略")
        private String reason;

        @Schema(description = "该字段已在哪一列被用掉了（仅重复列有值）", example = "A")
        private String firstColumnLetter;
    }

    /**
     * 预览里的一行
     */
    @Schema(description = "预览行")
    @Data
    public static class PreviewRow {

        @Schema(description = "文件里的行号（含表头，从 1 开始）", example = "2")
        private Integer rowNo;

        @Schema(description = "行结束行号（主子表分组时可能跨多行）", example = "4")
        private Integer endRowNo;

        @Schema(description = "状态：NEW / EXISTS / DUPLICATE_IN_FILE / INVALID",
                example = "NEW")
        private String status;

        @Schema(description = "状态的中文说明", example = "将新增")
        private String statusText;

        @Schema(description = "这一行解析出来的值（字段名 → 值）")
        private Map<String, Object> data;

        @Schema(description = "子表明细，子表控件字段名 → 明细行数组")
        private Map<String, List<Map<String, Object>>> subData;

        @Schema(description = "错误")
        private List<PreviewError> errors = new ArrayList<>();

        @Schema(description = "提示")
        private List<PreviewError> warnings = new ArrayList<>();
    }

    /**
     * 一条错误 / 提示
     */
    @Schema(description = "导入错误")
    @Data
    public static class PreviewError {

        @Schema(description = "文件里的行号", example = "7")
        private Integer rowNo;

        @Schema(description = "列字母", example = "I")
        private String columnLetter;

        @Schema(description = "字段名", example = "reg_capital")
        private String field;

        @Schema(description = "字段标题", example = "注册资本")
        private String fieldLabel;

        @Schema(description = "错误原因", example = "必须是数字，「待定」无法识别")
        private String message;

        @Schema(description = "单元格原始文本", example = "待定")
        private String rawValue;

        @Schema(description = "级别：ERROR / WARN", example = "ERROR")
        private String level;
    }

    /**
     * 全量统计
     */
    @Schema(description = "导入预览统计")
    @Data
    public static class Summary {

        @Schema(description = "文件里的数据行数（不含表头）", example = "120")
        private Integer totalRows;

        @Schema(description = "合并成多少条主记录", example = "100")
        private Integer totalGroups;

        @Schema(description = "校验通过、库里没有的条数", example = "95")
        private Integer newCount;

        @Schema(description = "校验通过、库里已存在的条数", example = "5")
        private Integer existsCount;

        @Schema(description = "文件内部重复的条数", example = "1")
        private Integer duplicateCount;

        @Schema(description = "校验不通过的条数", example = "4")
        private Integer invalidCount;

        @Schema(description = "预览实际返回的行数", example = "200")
        private Integer previewRowCount;
    }

}
