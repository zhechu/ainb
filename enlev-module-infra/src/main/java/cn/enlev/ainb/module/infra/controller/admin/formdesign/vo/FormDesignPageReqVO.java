package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 表单设计分页 Request VO")
@Data
public class FormDesignPageReqVO extends PageParam {

    @Schema(description = "表单名称，模糊匹配", example = "客户")
    private String name;

    @Schema(description = "表单编码，模糊匹配", example = "customer")
    private String code;

    @Schema(description = "分类", example = "business")
    private String category;

    @Schema(description = "表单类型：1-纯表单 2-表单+列表", example = "2")
    private Integer formType;

    @Schema(description = "状态：0-草稿 1-已发布 2-已发布有改动", example = "1")
    private Integer status;

}
