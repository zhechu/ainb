package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格导入执行 Response VO
 *
 * <h3>为什么成功、更新、跳过分开计数</h3>
 *
 * 只报一个「成功 100 条」的话，用户没法确认自己选的那个重复策略真的生效了 ——
 * 而「策略没生效」恰恰是这类功能最需要被看出来的事。
 * 三个数加起来等于总行数，用户自己就能核对。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 表格导入执行 Response VO")
@Data
public class FormImportRespVO {

    @Schema(description = "新增成功的条数", example = "95")
    private Integer createCount = 0;

    @Schema(description = "更新成功的条数", example = "4")
    private Integer updateCount = 0;

    @Schema(description = "按重复策略跳过的条数", example = "1")
    private Integer skipCount = 0;

    @Schema(description = "失败的条数", example = "2")
    private Integer failCount = 0;

    @Schema(description = "失败明细")
    private List<Failure> failures = new ArrayList<>();

    @Schema(description = "整表级问题，如明细乱序。有值时整批都没导")
    private List<FormImportPreviewRespVO.PreviewError> tableErrors = new ArrayList<>();

    /**
     * 一条失败记录
     */
    @Schema(description = "导入失败明细")
    @Data
    public static class Failure {

        @Schema(description = "文件里的行号", example = "7")
        private Integer rowNo;

        @Schema(description = "列字母", example = "I")
        private String columnLetter;

        @Schema(description = "字段名", example = "reg_capital")
        private String field;

        @Schema(description = "字段标题", example = "注册资本")
        private String fieldLabel;

        @Schema(description = "失败原因", example = "必须是数字")
        private String message;

        @Schema(description = "单元格原始文本", example = "待定")
        private String rawValue;

        @Schema(description = "业务键的值，便于用户在 Excel 里定位到这一条", example = "SUP003")
        private String businessKey;

    }

}
