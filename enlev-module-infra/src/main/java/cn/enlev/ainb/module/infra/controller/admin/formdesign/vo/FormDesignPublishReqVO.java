package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 表单设计发布 Request VO")
@Data
public class FormDesignPublishReqVO {

    @Schema(description = "表单设计编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "表单设计编号不能为空")
    private Long id;

    @Schema(description = "变更说明", example = "新增客户级别字段")
    private String changeSummary;

    /**
     * 发布时自动建菜单，需要用户指定上级菜单。
     * 首次发布时必填；已发布表单再次发布时此字段会被忽略（不重新建菜单）。
     */
    @Schema(description = "上级菜单编号（首次发布时必填）", example = "2")
    private Long parentMenuId;

    @Schema(description = "菜单排序（首次发布时可选，默认取 sort 字段）", example = "0")
    private Integer menuSort;

    @Schema(description = "是否同时发布到 App 端（移动端导航树）", example = "true")
    private Boolean publishToApp;

    /**
     * App 端导航节点的上级分组。首次发布到 App 时必填；
     * 已发布过 App 端（appNavNodeId 非空）时此字段被忽略（不挪父级）。
     */
    @Schema(description = "App 端导航节点的上级分组编号（首次发布到 App 时必填）", example = "23")
    private Long appParentNavNodeId;

    @Schema(description = "App 端导航节点排序（可选，默认取 sort 字段）", example = "0")
    private Integer appMenuSort;

}
