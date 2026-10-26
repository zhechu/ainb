package cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理后台 - 提示词模板分页 Request VO（需求7）
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 提示词模板分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentPromptTemplatePageReqVO extends PageParam {

    @Schema(description = "模板名称，模糊匹配", example = "订单")
    private String name;

    @Schema(description = "模板正文，模糊匹配（找「哪条模板里提到过 xx」）", example = "发货")
    private String content;

    @Schema(description = "分类，精确匹配", example = "数据查询")
    private String category;

    @Schema(description = "状态，见 CommonStatusEnum：0 开启 / 1 关闭", example = "0")
    private Integer status;

}
