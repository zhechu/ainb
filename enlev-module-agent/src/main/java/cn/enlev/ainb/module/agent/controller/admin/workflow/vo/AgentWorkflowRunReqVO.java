package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Map;

/**
 * 启动 Workflow 执行 Request VO
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 启动 Workflow 执行 Request VO")
@Data
public class AgentWorkflowRunReqVO {

    @Schema(description = "Workflow 定义 code", example = "wf_demo")
    @NotEmpty(message = "Workflow code 不能为空")
    private String code;

    @Schema(description = "初始变量，写进执行上下文；触发器输入变量名见 DSL trigger.inputVar",
            example = "{\"input\": \"帮我起草一份客户合同\"}")
    private Map<String, Object> input;

}
