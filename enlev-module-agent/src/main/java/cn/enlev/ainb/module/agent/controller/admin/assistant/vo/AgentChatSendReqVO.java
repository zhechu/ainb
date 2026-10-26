package cn.enlev.ainb.module.agent.controller.admin.assistant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - AI 助手发送消息 Request VO")
@Data
public class AgentChatSendReqVO {

    @Schema(description = "会话编号，为空则新建会话", example = "1024")
    private Long conversationId;

    /**
     * 指定用哪个<b>已发布</b>的 Agent 定义对话；为空 = 默认平台助手。
     *
     * <p>🔴 仅<b>新建会话</b>时生效：会话一旦建立，用哪个 Agent 以 {@code agent_conversation.agent_def_id}
     * 为准，后续请求再带别的 agentDefId 会被忽略（防中途换 Agent 混人设，见 AgentAssistantServiceImpl.chat）。
     */
    @Schema(description = "Agent 定义编号，为空则用默认平台助手（仅新建会话时生效）", example = "2048")
    private Long agentDefId;

    /**
     * 上限 2000 字
     *
     * 在入口就拦住而不是等模型报「超出上下文」：后者的报错来自厂商、信息含糊，
     * 而且那时 token 已经花掉了。用户粘进来一整篇文档时，
     * 「最多 2000 字」是他立刻能理解并处理的提示。
     */
    @Schema(description = "问题", example = "为什么我配的联动不生效")
    @NotBlank(message = "问题不能为空")
    @Size(max = 2000, message = "问题不能超过 2000 字")
    private String content;

}
