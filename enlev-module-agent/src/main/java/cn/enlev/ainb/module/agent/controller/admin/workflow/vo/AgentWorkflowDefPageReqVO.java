package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理后台 - Workflow 定义分页 Request VO（增量8 / P4 管理页）
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow 定义分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentWorkflowDefPageReqVO extends PageParam {

    @Schema(description = "业务编码，模糊匹配", example = "wf_plan")
    private String code;

    @Schema(description = "名称，模糊匹配", example = "客户跟进")
    private String name;

    @Schema(description = "状态：DRAFT / PUBLISHED / DISABLED", example = "PUBLISHED")
    private String status;

    @Schema(description = "触发器类型：form_event / schedule / chat / webhook", example = "schedule")
    private String triggerType;

    @Schema(description = "来源：MANUAL（手动添加）/ AUTO（自动生成），不传=全部", example = "MANUAL")
    private String source;

}
