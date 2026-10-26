package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Map;

/**
 * Workflow 沙箱测试 Request VO（流程编排设计器「沙箱测试」）
 *
 * <p>与 {@link AgentWorkflowRunReqVO} 的关键区别：run 按 <b>code</b> 跑「已发布」定义并落执行实例；
 * 沙箱直传 <b>草稿 DSL 原文</b>，用测试输入真跑一遍，但<b>不落库、不计配额、写操作被 mock</b>。
 * 因此无需先保存/发布即可试跑当前画布。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow 沙箱测试 Request VO")
@Data
public class AgentWorkflowSandboxReqVO {

    @Schema(description = "草稿 DSL 原文（直传，无需先落库/发布）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "Workflow DSL 不能为空")
    private String dsl;

    @Schema(description = "测试输入变量，写进沙箱执行上下文；变量名见 DSL variables / trigger.inputVar",
            example = "{\"input\": \"帮我起草一份客户合同\"}")
    private Map<String, Object> input;

    @Schema(description = "是否 mock 写操作；仅接受 true 或缺省。显式传 false 直接拒绝"
            + "（沙箱的定义就是写操作无真实副作用，不给关闭 mock 的口子）", example = "true")
    private Boolean mockWrites;

}
