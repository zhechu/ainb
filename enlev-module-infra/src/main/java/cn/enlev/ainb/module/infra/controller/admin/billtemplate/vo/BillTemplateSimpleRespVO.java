package cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 单据模板的精简信息
 *
 * 供表单设计器的「单据组件」选模板用 —— 只给下拉需要的字段，
 * 不把流水值、备注这些无关信息暴露给一个下拉框。
 */
@Schema(description = "管理后台 - 取号管理 - 精简信息 Response VO")
@Data
public class BillTemplateSimpleRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "模板名称", example = "采购订单号")
    private String name;

    @Schema(description = "模板编码", example = "purchase_order")
    private String code;

    @Schema(description = "分类", example = "business")
    private String category;

    @Schema(description = "范例", example = "CG202609060001")
    private String example;

}
