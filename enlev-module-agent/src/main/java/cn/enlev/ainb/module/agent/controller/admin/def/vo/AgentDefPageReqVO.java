package cn.enlev.ainb.module.agent.controller.admin.def.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理后台 - Agent 定义分页 Request VO（P5 Agent Builder 第一刀）
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Agent 定义分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentDefPageReqVO extends PageParam {

    @Schema(description = "业务编码，模糊匹配", example = "order_assistant")
    private String code;

    @Schema(description = "名称，模糊匹配", example = "订单客服")
    private String name;

    @Schema(description = "状态：DRAFT / PUBLISHED / DISABLED", example = "PUBLISHED")
    private String status;

}
