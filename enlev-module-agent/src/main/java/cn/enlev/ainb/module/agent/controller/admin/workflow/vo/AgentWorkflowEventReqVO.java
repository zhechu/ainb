package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 发布外部事件 Request VO（增量4b，唤醒 wait(waitType=event) 挂起的执行）
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 发布外部事件 Request VO")
@Data
public class AgentWorkflowEventReqVO {

    @Schema(description = "事件键，匹配 wait(event) 节点的 eventKey；唤醒本租户下所有等待它的挂起执行",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "demo_ready")
    @NotBlank(message = "事件键不能为空")
    @Size(max = 128, message = "事件键长度不能超过 128 个字符")
    private String eventKey;

    @Schema(description = "事件载荷，作为 resume 的 feedback 写进 checkpoint。"
            + "🔴 想被恢复后下游节点读到的键，必须在该 Workflow 定义的 variables[] 声明，否则 resume 重载时会被丢弃",
            example = "{\"theId\": 6}")
    private Map<String, Object> payload;

}
