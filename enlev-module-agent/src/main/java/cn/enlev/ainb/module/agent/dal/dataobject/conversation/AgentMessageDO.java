package cn.enlev.ainb.module.agent.dal.dataobject.conversation;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 助手消息 DO
 *
 * @author 走进新时代
 */
@TableName("agent_message")
@KeySequence("agent_message_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentMessageDO extends TenantBaseDO {

    /**
     * 角色：用户提问
     */
    public static final String ROLE_USER = "user";

    /**
     * 角色：助手回答
     */
    public static final String ROLE_ASSISTANT = "assistant";

    private Long id;

    private Long conversationId;

    /**
     * 用户编号
     *
     * 冗余一份，免得查消息还要 join 会话。
     */
    private Long userId;

    /**
     * 角色：{@link #ROLE_USER} / {@link #ROLE_ASSISTANT}
     */
    private String role;

    private String content;

    /**
     * 思维链（推理模型才有）
     *
     * 与正文分开存：前端要把它折叠起来单独渲染（复用 {@code MessageReasoning}），
     * 拼在 content 里就没法只折叠其中一段。
     */
    private String reasoningContent;

    /**
     * 本轮检索到的手册片段 JSON
     *
     * <h3>🔴 这一列不是「顺手记一下」，是功能的组成部分</h3>
     * 方案 §7 指出上游 {@code AiChatMessageServiceImpl#buildPrompt} 有个已知 TODO：
     * <b>历史轮不回灌知识库结果</b>。表现是第二轮追问时模型看不到第一轮检索到的手册，
     * 于是要么答非所问、要么用通用知识补 —— 而后者恰恰是 RAG 要消灭的东西
     * （实测：没接 RAG 时问「联动为什么不生效」，模型答的是通用的
     * 「条件未满足 / 字段标识错误」，正确但对这个平台毫无用处）。
     *
     * <p>要回灌就得先存得下来。
     */
    private String referenceJson;

    /**
     * 对话后建议 JSON 数组，最多 3 条（方案 §9）
     *
     * 与主回答<b>同一次 LLM 调用</b>产出，不单独再调一次 —— 单独调用会让每次对话的
     * 成本近乎翻倍，而建议的质量提升有限。
     */
    private String suggestions;

    /**
     * 关联的 Workflow 定义编号（plan-as-workflow，增量7b 起）
     *
     * <p>🔴 这一列是「聊天里那张草稿卡片」的<b>重建钥匙</b>。BUILD 意图产出的不再是线性计划，
     * 而是一份 Workflow DSL 草稿，落成 {@code agent_workflow_def} 的一条 DRAFT 记录，其 id 存这里。
     * 刷新页面后，前端拿 {@code workflow_def_id} 去 {@code /agent/planner/workflow-draft}
     * 取回 DSL 渲染节点预览、重建待确认卡片。为空表示这条消息不是草稿卡片。
     *
     * <p>曾名 {@code plan_id}（线性 planner 时代）；线性 planner 已删除，值语义完全不同
     * （plan ≠ workflow def），故旧值不迁移，见 {@code xpy-2026-09-26-agent-plan-to-workflow.sql}。
     */
    private Long workflowDefId;

    /**
     * 关联的 Workflow 执行实例编号（增量13 / 聊天内 HITL 确认）
     *
     * <p>🔴 与 {@link #workflowDefId} 对称，是「聊天里那张<b>确认卡片</b>」的重建钥匙。工作流驱动的 Agent
     * 绑定的 chatflow 在 {@code hitl.confirm} 节点挂起时，本轮不发纯文案，而落一条带 execution_id 的
     * assistant 消息（见 {@code AgentAssistantServiceImpl.bridgeWorkflowEvent} 的 INTERRUPTED 分支）。
     * 刷新页面后，前端凭它调 {@code /agent/workflow/execution/get} 查执行状态重建卡片：仍 INTERRUPTED →
     * 「待确认」（按钮可点，调 {@code /agent/assistant/resume-workflow-stream} 续跑）；已结束 → 只读「已处理」。
     *
     * <p>只在中断待确认的那条消息上写值，正常回复/草稿消息保持 {@code null} —— 前端据此区分普通气泡与确认卡片。
     */
    private Long executionId;

    /**
     * 工具调用记录 JSON（P2 起）
     */
    private String toolCalls;

    /**
     * 本条消息的 token 数
     *
     * <h3>为什么要存而不是用的时候算</h3>
     * 方案 §7 要求历史<b>按 token 预算</b>截断。上游按轮数截断
     * （{@code AiChatMessageServiceImpl#filterContextMessages}，最多 {@code maxContexts * 2} 条），
     * 长会话仍会超模型窗口 —— 因为一条消息可能是 50 字，
     * 也可能是塞了 5 段手册的 4000 token（实测第一次 RAG 提问输入就占了 4076）。
     * <b>按条数截断等于假装每条一样大。</b>
     */
    private Integer tokenCount;

    /**
     * token 来源：PROVIDER 厂商回传 / ESTIMATED 本地估算
     *
     * 口径与 {@code agent_usage_log.token_source} 一致。
     */
    private String tokenSource;

    /**
     * 状态：SUCCESS / FAILURE / CANCELLED
     */
    private String status;

    private String errorMsg;

}
