package cn.enlev.ainb.module.agent.dal.dataobject.conversation;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * AI 助手会话 DO
 *
 * <h3>为什么不复用 ai 模块的 {@code AiChatConversationDO}</h3>
 * 三个理由，任何一个单独都足够：
 * <ol>
 *   <li>它绑定「聊天角色(roleId)」与「知识库(knowledgeId)」两个概念，助手都不需要；
 *       反过来助手需要的 workflowDefId / suggestions 它没有</li>
 *   <li>它的历史拼接按<b>轮数</b>截断，助手要按 <b>token 预算</b>截断。
 *       这两种口径没法共存 —— 前者不需要在消息上存 token 数，后者需要</li>
 *   <li>混用会让「用户删聊天记录」意外删掉助手会话 —— 两者生命周期本就不同</li>
 * </ol>
 *
 * <h3>🔴 分租户，不照抄 {@code @TenantIgnore}</h3>
 * {@code infra_form_design} 用的是 {@code @TenantIgnore}，因为表单设计是平台级资产。
 * 会话内容是<b>用户私有数据</b>，跨租户可见等于泄漏 —— 所以继承 {@link TenantBaseDO}。
 *
 * @author 走进新时代
 */
@TableName("agent_conversation")
@KeySequence("agent_conversation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentConversationDO extends TenantBaseDO {

    private Long id;

    /**
     * 用户编号（会话归属人）
     *
     * 查询一律带上它 —— 租户隔离只保证「不跨租户」，
     * 同租户的另一个用户仍然会看到别人的会话，那同样是泄漏。
     */
    private Long userId;

    /**
     * 本会话绑定的 Agent 定义编号（{@code agent_def.id}）。可空：null = 默认平台助手。
     *
     * <p>🔴 创建时定、之后以会话为准：新会话用请求里的 agentDefId 建绑定；既有会话一律读这一列决定
     * 用哪个 Agent 的 persona/model，<b>忽略</b>后续请求新带的 agentDefId —— 防一轮对话中途换 Agent
     * 导致历史里混两套人设（见 {@code AgentAssistantServiceImpl.chat}）。
     */
    private Long agentDefId;

    /**
     * 标题，默认取首条提问的前若干字
     */
    private String title;

    /**
     * 场景：CHAT 问答（P2 起扩展建表、建流程）
     *
     * 从第一天就带上这个维度：加字段容易，回填历史难。
     */
    private String scene;

    /**
     * 状态：0 进行中，1 已归档
     */
    private Integer status;

    /**
     * 是否置顶
     */
    private Boolean pinned;

    /**
     * 最后活跃时间（收到回复时刷新）
     *
     * 列表排序用。放库里而不是前端算，因为分页时前端手上只有当前页，排不出全局顺序。
     */
    private LocalDateTime lastActiveTime;

    /**
     * 消息条数（含用户与助手）
     */
    private Integer messageCount;

}
