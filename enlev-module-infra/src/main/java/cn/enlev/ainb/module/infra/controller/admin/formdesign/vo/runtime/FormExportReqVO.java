package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 表单运行时导出 Request VO
 *
 * 导出不分页，不继承 PageParam，避免 @Max(200) 校验拦住导出请求。
 * 后端 exportExcel 内部直接使用 MAX_EXPORT_ROWS 限制行数。
 */
@Schema(description = "管理后台 - 表单运行时导出 Request VO")
@Data
public class FormExportReqVO {

    @Schema(description = "查询条件列表")
    private List<FormDataPageReqVO.Condition> conditions;

    @Schema(description = "排序字段", example = "customerName")
    private String sortField;

    @Schema(description = "排序方向，只允许 ASC / DESC", example = "DESC")
    private String sortOrder;

    @Schema(description = "用户在列设置里保留下来的列，含顺序。明细列用「子表::字段」。"
            + "不传表示用设计期的列",
            example = "[\"supplier_code\", \"supplier_name\", \"contacts::contact_name\"]")
    private List<String> visibleFields;

    @Schema(description = "导出范围：CURRENT_PAGE-当前页 / ALL-按当前查询条件的全部。默认 ALL",
            example = "ALL")
    private String scope;

    @Schema(description = "页码。仅 CURRENT_PAGE 用", example = "2")
    private Integer pageNo;

    @Schema(description = "每页条数。仅 CURRENT_PAGE 用", example = "20")
    private Integer pageSize;

    @Schema(description = "要不要导出合计行。不传表示跟随列表 —— "
            + "列表上有合计行就导、没有就不导。要拿去做透视表时可以显式关掉",
            example = "true")
    private Boolean includeSummary;

}
