package cn.enlev.ainb.module.agent.service.conversation;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentConversationDO;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentMessageDO;

import java.util.List;

/**
 * AI 助手会话 Service
 *
 * <h3>🔴 所有方法都要带 userId</h3>
 * 会话内容是用户私有数据。租户隔离由框架保证，但它只挡「跨租户」——
 * 同租户里的另一个用户仍然会看到别人的会话，而 id 是连续可枚举的。
 * 所以这一层的每个入口都<b>显式要求 userId</b>，不给「按 id 直接取」留后门。
 *
 * @author 走进新时代
 */
public interface AgentConversationService {

    /**
     * 创建会话
     *
     * @param userId 用户编号
     * @param title  标题，为空时留空，等首条提问时回填
     * @return 会话编号
     */
    Long createConversation(Long userId, String title);

    /**
     * 创建会话并绑定 Agent 定义（增量10 / P5 第二刀）
     *
     * @param userId     用户编号
     * @param title      标题，为空时留空，等首条提问时回填
     * @param agentDefId 绑定的 Agent 定义编号；为空 = 默认平台助手。
     *                   🔴 绑定在创建时定死，之后一律以会话这一列为准（见 {@code AgentConversationDO.agentDefId}）
     * @return 会话编号
     */
    Long createConversation(Long userId, String title, Long agentDefId);

    /**
     * 分页查会话
     */
    PageResult<AgentConversationDO> getConversationPage(Long userId, Integer pageNo, Integer pageSize);

    /**
     * 取会话，取不到（含「不属于该用户」）抛 {@code AGENT_CONVERSATION_NOT_EXISTS}
     */
    AgentConversationDO getConversation(Long id, Long userId);

    /**
     * 改标题
     */
    void updateConversationTitle(Long id, Long userId, String title);

    /**
     * 置顶 / 取消置顶
     */
    void updateConversationPinned(Long id, Long userId, Boolean pinned);

    /**
     * 删除会话及其消息
     *
     * ⚠️ <b>不动 {@code agent_usage_log}</b>：那是收费证据，
     * 用户删对话不能把账一起删掉（方案 §8.2）。日志里的 conversationId 是弱引用，
     * 删完之后仍然指向一个已不存在的会话 —— 这是刻意的。
     */
    void deleteConversation(Long id, Long userId);

    /**
     * 取会话下的全部消息
     */
    List<AgentMessageDO> getMessageList(Long conversationId, Long userId);

    /**
     * 追加一条消息，并刷新会话的活跃时间与计数
     *
     * <p>🔴 会话<b>已被删除</b>时：不落库、返回 {@code null}。
     * 无条件插入会攒出「任何查询都捞不出来」的孤儿消息，且让残留核账永远到不了空
     * —— 实测疤与残余竞态见实现类的方法注释。
     *
     * @param message 消息，会就地回填 id
     * @return 消息编号；会话已不存在时为 {@code null}（调用方需容忍）
     */
    Long appendMessage(AgentMessageDO message);

    /**
     * 更新一条消息（流式回答结束时回填正文、建议、token 数）
     */
    void updateMessage(AgentMessageDO message);

}
