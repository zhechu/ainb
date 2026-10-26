package cn.enlev.ainb.module.agent.service.conversation;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentConversationDO;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentMessageDO;
import cn.enlev.ainb.module.agent.dal.mysql.conversation.AgentConversationMapper;
import cn.enlev.ainb.module.agent.dal.mysql.conversation.AgentMessageMapper;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentChatProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_CONVERSATION_NOT_EXISTS;

/**
 * AI 助手会话 Service 实现
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class AgentConversationServiceImpl implements AgentConversationService {

    /**
     * 新会话的默认标题
     */
    private static final String DEFAULT_TITLE = "新对话";

    @Resource
    private AgentConversationMapper conversationMapper;
    @Resource
    private AgentMessageMapper messageMapper;
    @Resource
    private AgentChatProperties chatProperties;

    @Override
    public Long createConversation(Long userId, String title) {
        return createConversation(userId, title, null);
    }

    @Override
    public Long createConversation(Long userId, String title, Long agentDefId) {
        AgentConversationDO conversation = new AgentConversationDO();
        conversation.setUserId(userId);
        conversation.setAgentDefId(agentDefId); // 空=默认平台助手；创建时定死，之后以会话为准
        conversation.setTitle(StrUtil.blankToDefault(title, DEFAULT_TITLE));
        conversation.setScene("CHAT");
        conversation.setStatus(0);
        conversation.setPinned(false);
        conversation.setMessageCount(0);
        // 建的时候就填活跃时间，否则新会话的 last_active_time 为 null，
        // 在「按活跃时间倒序」的列表里会沉到最底下 —— 用户刚建的会话反而找不到
        conversation.setLastActiveTime(LocalDateTime.now());
        conversationMapper.insert(conversation);
        return conversation.getId();
    }

    @Override
    public PageResult<AgentConversationDO> getConversationPage(Long userId, Integer pageNo, Integer pageSize) {
        return conversationMapper.selectPageByUserId(userId, pageNo, pageSize);
    }

    @Override
    public AgentConversationDO getConversation(Long id, Long userId) {
        AgentConversationDO conversation = conversationMapper.selectByIdAndUserId(id, userId);
        if (conversation == null) {
            // 「别人的会话」与「不存在」共用这一条，不泄漏 id 的存在性
            throw exception(AGENT_CONVERSATION_NOT_EXISTS);
        }
        return conversation;
    }

    @Override
    public void updateConversationTitle(Long id, Long userId, String title) {
        getConversation(id, userId); // 校验归属
        AgentConversationDO update = new AgentConversationDO();
        update.setId(id);
        update.setTitle(StrUtil.blankToDefault(title, DEFAULT_TITLE));
        conversationMapper.updateById(update);
    }

    @Override
    public void updateConversationPinned(Long id, Long userId, Boolean pinned) {
        getConversation(id, userId);
        AgentConversationDO update = new AgentConversationDO();
        update.setId(id);
        update.setPinned(Boolean.TRUE.equals(pinned));
        conversationMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long id, Long userId) {
        getConversation(id, userId);
        messageMapper.deleteByConversationId(id);
        conversationMapper.deleteById(id);
        // ⚠️ 刻意不动 agent_usage_log：收费证据不随会话删除（方案 §8.2）
    }

    @Override
    public List<AgentMessageDO> getMessageList(Long conversationId, Long userId) {
        getConversation(conversationId, userId); // 先校验会话归属，再查消息
        return messageMapper.selectListByConversationId(conversationId);
    }

    /**
     * 🔴 <b>先确认会话还活着，再落消息</b> —— 顺序反过来就是孤儿消息的成因
     *
     * <p>实测疤（方案 §9-6 第 1 条，两条真实孤儿 `agent_message` id 681 / 769）：
     * 会话被删之后，<b>仍在收尾</b>的那条 assistant 消息照样插进了库。
     * 时序对得很死 —— 消息比会话的 {@code update_time} 晚 19s / 45s：
     * <pre>
     *   消息 681 ← 会话 346（已软删）  消息 01:46:13 / 会话 01:45:54  晚 19s
     *   消息 769 ← 会话 390（已软删）  消息 02:48:06 / 会话 02:47:21  晚 45s
     * </pre>
     * 原因不是「忘了级联」（{@link #deleteConversation} 是级联的，还带 {@code @Transactional}），
     * 而是本方法<b>原先无条件 insert</b>，把「会话在不在」的判断留给了后面的
     * {@link #refreshConversation} —— 那时行已经落库了，它只能 warn 一声、静默影响 0 行。
     *
     * <p>后果有两层，第二层比第一层贵：
     * <ul>
     *   <li>{@code agent_message} 会无界攒下<b>任何查询都捞不出来</b>的行
     *       （所有读路径都先过 {@link #getConversation} 校验归属，会话没了就走不到消息）；</li>
     *   <li>🔴 残留核账<b>永远到不了空</b>。测试资产的 {@code afterAll} 是「跑前快照 → 跑后差集」，
     *       而差集对历史积压天生免疫，于是「本轮不新增」永远绿、库里却越来越脏 ——
     *       方案 §9-5 那条「残留为空」的验收判据会被这个缺陷单方面否掉。</li>
     * </ul>
     *
     * <p><b>为什么是「跳过」而不是抛异常</b>：会话是用户自己删的，这条消息本来就没人要看；
     * 而此刻 SSE 流已经跑完，抛出去只会变成 Reactor 的 {@code onErrorDropped} 淹没在日志里
     * （{@code settleWorkflowAnswer} 的 catch 分支记的就是这条理由）。
     * 🔴 计量<b>不</b>跟着跳过：账单是 {@code AgentAssistantServiceImpl.recordUsage} 自己写的，
     * 与本方法无关 —— 与 {@link #deleteConversation} 「收费证据不随会话删除」同一条纪律。
     *
     * <p>⚠️ <b>残余竞态，如实记下</b>：查完到插入之间仍有一个窗口，删除正好落在这里就还是会产生孤儿。
     * 本改动把这个窗口从「整条回答的流式时长」（实测 19~45 秒）压到「一次 insert 的耗时」（毫秒级），
     * 但<b>没有消除</b>它。真要消除得让插入本身带条件（{@code INSERT ... SELECT ... WHERE deleted=0}），
     * 而那会绕过 MyBatis-Plus 的自动插入与 id 回填；逻辑删除下外键也兜不住（行物理上还在）。
     * 代价与收益不匹配，故不做 —— 但别再把这个窗口当成「已经修好了」。
     *
     * @return 消息编号；<b>会话已不存在时返回 {@code null} 且不落库</b>。
     *         调用方本来就容忍 null（{@code settleWorkflowAnswer} 的「已结算过」与 catch 两条分支都返回 null）
     */
    @Override
    public Long appendMessage(AgentMessageDO message) {
        AgentConversationDO conversation = conversationMapper.selectById(message.getConversationId());
        if (conversation == null) {
            log.warn("[appendMessage][会话({})已不存在，这条 {} 消息(status={})不落库 —— "
                            + "会话是用户自己删的，留下来就是一行任何查询都捞不出来的孤儿]",
                    message.getConversationId(), message.getRole(), message.getStatus());
            return null;
        }
        messageMapper.insert(message);
        refreshConversation(message, conversation);
        return message.getId();
    }

    @Override
    public void updateMessage(AgentMessageDO message) {
        messageMapper.updateById(message);
    }

    /**
     * 刷新会话的活跃时间、消息计数，并在首次提问时回填标题
     *
     * <p>标题取首条<b>用户提问</b>的前若干字：用助手的回答当标题会很怪
     * （回答往往以「根据手册片段……」开头，一列会话看过去全是同一句话）。
     *
     * <p>{@code conversation} 由 {@link #appendMessage} 查好后传进来，本方法<b>不再自己查一次</b>：
     * 一次 append 两次 selectById 是白花的，而且「判空」这件事只能有一个地方做 ——
     * 两处各判一次，就没人说得清哪一处才是权威（D7）。
     */
    private void refreshConversation(AgentMessageDO message, AgentConversationDO conversation) {
        AgentConversationDO update = new AgentConversationDO();
        update.setId(conversation.getId());
        update.setLastActiveTime(LocalDateTime.now());
        update.setMessageCount((conversation.getMessageCount() == null ? 0 : conversation.getMessageCount()) + 1);
        if (AgentMessageDO.ROLE_USER.equals(message.getRole())
                && (StrUtil.isBlank(conversation.getTitle())
                    || DEFAULT_TITLE.equals(conversation.getTitle()))) {
            update.setTitle(StrUtil.maxLength(message.getContent(), chatProperties.getTitleMaxLength()));
        }
        conversationMapper.updateById(update);
    }

}
