package cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 取号管理 - 分页 Request VO")
@Data
public class BillTemplatePageReqVO extends PageParam {

    @Schema(description = "模板名称，模糊匹配", example = "采购单")
    private String name;

    @Schema(description = "模板编码，模糊匹配", example = "purchase")
    private String code;

    @Schema(description = "分类", example = "business")
    private String category;

    @Schema(description = "生成方式：1-时间流水 2-随机数 3-UUID", example = "1")
    private Integer genType;

    @Schema(description = "状态：0-开启 1-停用", example = "0")
    private Integer status;

}
