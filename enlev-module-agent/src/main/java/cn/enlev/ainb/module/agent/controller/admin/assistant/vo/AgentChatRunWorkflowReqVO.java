package cn.enlev.ainb.module.agent.controller.admin.assistant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 管理后台 - AI 助手「聊天内按 code 跑一条已发布工作流」Request VO（A1）
 *
 * <p>助手聊天里的流程草稿卡点「确认并运行」即发这个请求，走 {@code /agent/assistant/run-workflow-stream}。
 *
 * <p>🔴 与 {@code AgentWorkflowRunReqVO}（{@code /agent/workflow/run-stream}）的唯一实质差别就是多了
 * {@code conversationId}：那边不属于任何会话，只写 {@code agent_workflow_execution}；
 * 这边要把回复<b>落进会话</b>，没有会话编号就不知道落到哪 —— 那个协议缺口正是「点了没回复、刷新即失忆、
 * 追问一句模型完全不知道刚才跑过什么」的根因（聊天历史与 LLM 记忆都来自 {@code agent_message}）。
 * 两个 VO 刻意分开而不是给旧的那个加字段：旧端点是「按 code 起一条执行、结果只进执行历史」的通用入口
 * （沙箱另走 {@code sandbox-stream}），它的调用方没有会话，加了也只会传空。
 */
@Schema(description = "管理后台 - AI 助手聊天内运行工作流 Request VO")
@Data
public class AgentChatRunWorkflowReqVO {

    /**
     * 工作流编码
     *
     * <p>草稿卡的 {@code workflowCode}，由前端在「确认并运行」前先调 publish-workflow 发布，再原样回传。
     * 服务端按它取<b>已发布</b>那一版；不存在 / 未发布一律抛错，不静默回落草稿。
     */
    @Schema(description = "工作流编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "wf_plan_a1b2c3")
    @NotEmpty(message = "工作流编码不能为空")
    private String code;

    /**
     * 流程输入变量
     *
     * <p>草稿卡这一击传空（前端历来传 {@code {}}，与旧通道行为逐字一致）—— 本次只修「回复看不见、
     * 刷新即失忆」，不顺手改运行的语义。服务端把 null 归一成空表，避免下游流程变量表拿到 null。
     */
    @Schema(description = "流程输入变量", example = "{}")
    private Map<String, Object> input;

    /**
     * 会话编号：运行产出的回复落进这个会话
     *
     * <p>🔴 服务端会校验会话归属当前用户（{@code getConversation(id, userId)}），防越权往别人会话塞消息
     * —— 与 {@link AgentChatResumeReqVO} 同款。
     */
    @Schema(description = "会话编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "2048")
    @NotNull(message = "会话编号不能为空")
    private Long conversationId;

}
