package cn.enlev.ainb.module.agent.controller.admin.assistant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - AI 助手会话 Response VO")
@Data
public class AgentConversationRespVO {

    @Schema(description = "会话编号", example = "1024")
    private Long id;

    @Schema(description = "绑定的 Agent 定义编号，为空表示默认平台助手", example = "2048")
    private Long agentDefId;

    @Schema(description = "标题", example = "为什么我配的联动不生效")
    private String title;

    @Schema(description = "场景", example = "CHAT")
    private String scene;

    @Schema(description = "是否置顶", example = "false")
    private Boolean pinned;

    @Schema(description = "消息条数", example = "6")
    private Integer messageCount;

    @Schema(description = "最后活跃时间")
    private LocalDateTime lastActiveTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
