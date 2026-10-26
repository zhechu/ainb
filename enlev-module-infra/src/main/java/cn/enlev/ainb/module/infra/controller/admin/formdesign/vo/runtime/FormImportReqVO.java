package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 表格导入执行 Request VO
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 表格导入执行 Request VO")
@Data
public class FormImportReqVO {

    @Schema(description = "预览令牌。上一次预览返回的 importToken，只能用一次",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String importToken;

    @Schema(description = "用户改过的行。只传改过的那些 —— "
            + "没传的行用预览时的数据。键是文件里的行号",
            example = "{\"7\": {\"reg_capital\": \"1000\"}}")
    private Map<String, Map<String, Object>> editedRows;

    @Schema(description = "用户删掉的行号（文件行号）。这些行不导入",
            example = "[9]")
    private List<String> removedRows;

    @Schema(description = "重复数据的处理策略：SKIP-跳过并报告 / UPDATE-按更新键覆盖 / "
            + "APPEND-一律新增。默认 SKIP",
            example = "SKIP")
    private String duplicateStrategy;

    @Schema(description = "是否「全对才导」。开启时只要有一行校验不过就整批不导",
            example = "false")
    private Boolean allOrNothing;

}
