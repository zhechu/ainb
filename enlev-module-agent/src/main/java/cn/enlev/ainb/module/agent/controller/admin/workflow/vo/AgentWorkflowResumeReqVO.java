package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 恢复 Workflow 执行 Request VO（hitl 确认后）
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 恢复 Workflow 执行 Request VO")
@Data
public class AgentWorkflowResumeReqVO {

    @Schema(description = "执行实例编号", example = "1024")
    @NotNull(message = "执行实例编号不能为空")
    private Long executionId;

    @Schema(description = "人工反馈，经 updateState 写进 checkpoint 后从断点恢复",
            example = "{\"hitl_approved\": true}")
    private Map<String, Object> feedback;

}
