package cn.enlev.ainb.module.agent.controller.admin.assistant.vo;

import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentAnswerStreamSplitter.Suggestion;
import cn.enlev.ainb.module.ai.api.rag.dto.AiRagSegmentRespDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - AI 助手消息 Response VO")
@Data
public class AgentMessageRespVO {

    @Schema(description = "消息编号", example = "2048")
    private Long id;

    @Schema(description = "会话编号", example = "1024")
    private Long conversationId;

    @Schema(description = "角色：user / assistant", example = "assistant")
    private String role;

    @Schema(description = "正文")
    private String content;

    @Schema(description = "思维链")
    private String reasoningContent;

    /**
     * 引用片段
     *
     * ⚠️ 库里存的是 JSON 字符串，这里<b>解析成对象</b>再给前端。
     * 让前端 JSON.parse 一个字符串字段是能跑，但那样这个字段的结构就只存在于约定里，
     * 接口文档上是个 string，改了字段名两边都不会报错。
     */
    @Schema(description = "本轮引用的手册片段")
    private List<AiRagSegmentRespDTO> references;

    @Schema(description = "对话后建议")
    private List<Suggestion> suggestions;

    @Schema(description = "状态：SUCCESS / FAILURE / CANCELLED", example = "SUCCESS")
    private String status;

    @Schema(description = "失败原因")
    private String errorMsg;

    /**
     * 关联的 Workflow 定义编号（plan-as-workflow）
     *
     * <p>🔴 这个字段是刷新页面后重建草稿卡片的<b>唯一钥匙</b>，漏掉就会静默退化：
     * 前端 {@code loadMessages} 靠 {@code item.workflowDefId} 去调
     * {@code /agent/planner/workflow-draft} 取 DSL 重建卡片，拿不到它就走了「普通消息」分支 ——
     * 用户刷新之后，刚才那张「待确认」的草稿卡片<b>变成一段纯文字</b>，
     * 没有节点预览、没有确认按钮，流程再也发布不了，而且没有任何报错。
     *
     * <p>曾名 {@code planId}（线性 planner 时代，对应 {@code /agent/plan/get}）。
     * DO 与 VO 同改字段名，{@code toMessageVO} 的 {@code copyProperties} 自动映射即保持。
     */
    @Schema(description = "关联的 Workflow 定义编号；为空表示这条消息不是草稿卡片", example = "16")
    private Long workflowDefId;

    /**
     * 关联的 Workflow 执行实例编号（增量13 / 聊天内 HITL 确认）
     *
     * <p>🔴 与 {@link #workflowDefId} 对称，是刷新页面后重建<b>确认卡片</b>的唯一钥匙：前端 {@code loadMessages}
     * 靠 {@code item.executionId} 调 {@code /agent/workflow/execution/get} 查执行状态 —— 仍 INTERRUPTED 则重建
     * 「待确认」卡片（按钮可点，续跑走 {@code /agent/assistant/resume-workflow-stream}），已结束则渲染只读「已处理」。
     * 漏掉它就会静默退化：刷新后确认卡片变成一段纯文字、按钮消失、流程再也无法在聊天里恢复，且无任何报错。
     * DO 与 VO 同名同型，{@code toMessageVO} 的 {@code copyProperties} 自动映射即保持。
     */
    @Schema(description = "关联的 Workflow 执行实例编号；为空表示这条消息不是中断确认卡片", example = "1024")
    private Long executionId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
