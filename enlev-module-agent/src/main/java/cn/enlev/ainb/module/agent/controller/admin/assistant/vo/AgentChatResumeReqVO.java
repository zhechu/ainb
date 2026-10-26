package cn.enlev.ainb.module.agent.controller.admin.assistant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理后台 - AI 助手「聊天内恢复挂起工作流」Request VO（增量13）
 *
 * <p>工作流驱动的 Agent 绑定的 chatflow 在 {@code hitl.confirm} 节点挂起后，用户在聊天里的确认卡片点
 * 「同意/拒绝」即发这个请求，走 {@code /agent/assistant/resume-workflow-stream} 从断点续跑。
 *
 * <p>🔴 与工作台用的 {@code AgentWorkflowResumeReqVO} 区别：那边 {@code feedback} 是自由 Map（管理员可传任意
 * 人工反馈、支持自定义 approveVar）；这里只暴露一个布尔 {@code approved}，服务端固定映射成 {@code hitl_approved}
 * 变量 —— 聊天内确认是「同意/拒绝」的二元闸口，不给用户递一个能塞任意键的 Map（也更难配错）。
 */
@Schema(description = "管理后台 - AI 助手聊天内恢复工作流 Request VO")
@Data
public class AgentChatResumeReqVO {

    /**
     * 挂起的执行实例编号
     *
     * 前端从 WORKFLOW_CONFIRM 事件（或刷新后 {@code agent_message.executionId}）取得，原样回传。
     */
    @Schema(description = "挂起的执行实例编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "执行实例编号不能为空")
    private Long executionId;

    /**
     * 人工决定：true=同意续跑，false=拒绝
     *
     * <p>服务端写进 {@code hitl_approved} 变量（{@code HitlConfirmNodeExecutor} 默认读的键）。
     * 闸口在 DSL 里自定义了 {@code config.approveVar} 时也认：执行器读不到自己的 approveVar 会回退读这个键，
     * 并把归一化后的布尔写回 approveVar 供下游 condition.rule 判定。
     */
    @Schema(description = "人工决定：true=同意续跑，false=拒绝", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    @NotNull(message = "确认结果不能为空")
    private Boolean approved;

    /**
     * 会话编号：续跑产出的回复落进这个会话
     *
     * <p>🔴 服务端会校验会话归属当前用户（{@code getConversation(id, userId)}），防越权往别人会话塞消息。
     */
    @Schema(description = "会话编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "2048")
    @NotNull(message = "会话编号不能为空")
    private Long conversationId;

}
