package cn.enlev.ainb.module.system.controller.admin.user.vo.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户导入预检 Response VO
 *
 * <h3>为什么导入要先有这一步</h3>
 *
 * 改造前是「传完直接落库，然后告诉你成功几条失败几条」。这种形态最糟的地方不是失败本身，
 * 而是<b>失败与成功混在一次不可撤销的写入里</b>：一份 500 行的文件里有 3 行部门编号填错，
 * 用户点完确定才知道，此时 497 行已经进库了，他只能拿着一串用户名去猜哪几行要补。
 *
 * 先预检就把「会发生什么」和「真的发生」分开了 —— 用户在第二步看到的是
 * 每一行的判定结果，确认无误再落库。
 *
 * 这一份里的每一块都有明确用途，不是为了信息全而堆的：
 *
 * <ul>
 *   <li>{@code mappings} —— 让用户<b>看见</b>表头是怎么对上的。没有这一步的话，
 *       「哪一列对到了哪个字段」只能靠猜</li>
 *   <li>{@code rows} —— 逐行的解析结果与状态</li>
 *   <li>{@code summary} —— 全量统计，用户第一眼看的就是它</li>
 *   <li>{@code tableErrors} —— 整表级问题（缺必填列、文件被行数上限截断）</li>
 *   <li>{@code importToken} —— 执行时必须带回，且只能用一次</li>
 * </ul>
 *
 * @author 芋道源码
 */
@Schema(description = "管理后台 - 用户导入预检 Response VO")
@Data
public class UserImportPreviewRespVO {

    @Schema(description = "预检令牌。执行导入时必须原样带回", requiredMode = Schema.RequiredMode.REQUIRED)
    private String importToken;

    @Schema(description = "表头映射：Excel 列 → 用户字段")
    private List<ColumnMapping> mappings = new ArrayList<>();

    @Schema(description = "没识别出来的列")
    private List<UnmatchedColumn> unmatchedColumns = new ArrayList<>();

    @Schema(description = "同一个字段被多列匹配到，只有第一列生效")
    private List<UnmatchedColumn> duplicateColumns = new ArrayList<>();

    @Schema(description = "必填列在表头里找不到")
    private List<String> missingRequiredFields = new ArrayList<>();

    @Schema(description = "逐行预检结果（最多回前若干行）")
    private List<PreviewRow> rows = new ArrayList<>();

    @Schema(description = "全量统计")
    private Summary summary = new Summary();

    @Schema(description = "整表级问题。有值时基本上一条都导不了")
    private List<PreviewError> tableErrors = new ArrayList<>();

    @Schema(description = "判断「库中已存在」用的字段。用户导入固定是登录名")
    private List<String> updateKeyFields = new ArrayList<>();

    /**
     * 一列的对应关系
     */
    @Schema(description = "列映射")
    @Data
    public static class ColumnMapping {

        @Schema(description = "列字母：A、B、C…", example = "A")
        private String columnLetter;

        @Schema(description = "Excel 里的表头原文", example = "登录名称")
        private String header;

        @Schema(description = "匹配上的字段名", example = "username")
        private String field;

        @Schema(description = "字段标题", example = "登录名称")
        private String fieldLabel;

        @Schema(description = "是否必填")
        private Boolean required;

        @Schema(description = "是否字典列（填中文标签，服务端反查成编码）")
        private Boolean dict;
    }

    /**
     * 没识别出来 / 重复的列
     */
    @Schema(description = "未识别的列")
    @Data
    public static class UnmatchedColumn {

        @Schema(description = "列字母", example = "H")
        private String columnLetter;

        @Schema(description = "表头原文", example = "备注2")
        private String header;

        @Schema(description = "说明", example = "这一列没有对应的用户字段，数据会被忽略")
        private String reason;

        @Schema(description = "该字段已在哪一列被用掉了（仅重复列有值）", example = "A")
        private String firstColumnLetter;
    }

    /**
     * 预检里的一行
     */
    @Schema(description = "预检行")
    @Data
    public static class PreviewRow {

        @Schema(description = "Excel 里的行号（表头是第 1 行）", example = "2")
        private Integer rowNo;

        @Schema(description = "状态：NEW-将新增 / EXISTS-库中已存在 / "
                + "DUPLICATE_IN_FILE-文件内重复 / INVALID-校验未通过", example = "NEW")
        private String status;

        @Schema(description = "状态的中文说明", example = "将新增")
        private String statusText;

        @Schema(description = "这一行解析出来的值（字段名 → 值）")
        private Map<String, Object> data;

        @Schema(description = "错误。有错误的行状态一定是 INVALID")
        private List<PreviewError> errors = new ArrayList<>();

        @Schema(description = "提示。不阻断导入")
        private List<PreviewError> warnings = new ArrayList<>();
    }

    /**
     * 一条错误 / 提示
     */
    @Schema(description = "导入问题")
    @Data
    public static class PreviewError {

        @Schema(description = "Excel 里的行号", example = "7")
        private Integer rowNo;

        @Schema(description = "列字母", example = "C")
        private String columnLetter;

        @Schema(description = "字段名", example = "deptId")
        private String field;

        @Schema(description = "字段标题", example = "部门编号")
        private String fieldLabel;

        @Schema(description = "原因", example = "部门不存在")
        private String message;

        @Schema(description = "单元格原文", example = "999")
        private String rawValue;

        @Schema(description = "级别：ERROR / WARN", example = "ERROR")
        private String level;
    }

    /**
     * 全量统计
     *
     * 五个数加起来能对上：{@code totalRows == newCount + existsCount +
     * duplicateCount + invalidCount}。对不上说明判定逻辑漏了分支，
     * 而用户自己就能看出来 —— 这比在服务端加断言管用
     */
    @Schema(description = "用户导入预检统计")
    @Data
    public static class Summary {

        @Schema(description = "文件里的数据行数（不含表头，不含空行）", example = "120")
        private Integer totalRows = 0;

        @Schema(description = "校验通过、库里没有的条数", example = "98")
        private Integer newCount = 0;

        @Schema(description = "校验通过、库里已存在的条数", example = "15")
        private Integer existsCount = 0;

        @Schema(description = "文件内部登录名重复的条数", example = "0")
        private Integer duplicateCount = 0;

        @Schema(description = "校验不通过的条数", example = "7")
        private Integer invalidCount = 0;

        @Schema(description = "预检实际返回了多少行（rows 的长度）", example = "200")
        private Integer previewRowCount = 0;

        @Schema(description = "文件是不是被行数上限截断了", example = "false")
        private Boolean truncated = false;

        @Schema(description = "生效的行数上限", example = "20000")
        private Integer maxRows;
    }

}
