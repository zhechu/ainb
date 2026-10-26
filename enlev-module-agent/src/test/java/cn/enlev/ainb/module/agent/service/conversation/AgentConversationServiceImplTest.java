package cn.enlev.ainb.module.agent.service.conversation;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentConversationDO;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentMessageDO;
import cn.enlev.ainb.module.agent.dal.mysql.conversation.AgentConversationMapper;
import cn.enlev.ainb.module.agent.dal.mysql.conversation.AgentMessageMapper;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentChatProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentConversationServiceImpl} 单测（0 token，全 mock）
 *
 * <p>钉两件互不相干、但都<b>错了不报错</b>的事：
 * <ol>
 *   <li><b>会话-Agent 绑定</b>（增量10 D3）：新会话落库时把 {@code agent_def_id} 带进去。
 *       这条错了表现为「选了 Agent，刷新页面后又变回默认助手」，人设跨轮/跨刷新丢失。
 *       二参重载必须委派成 agentDefId=null（默认助手），否则 controller 那个老调用方
 *       会意外把会话绑到某个 Agent 上。</li>
 *   <li><b>孤儿消息</b>（方案 §9-6 第 1 条）：会话被删之后，仍在收尾的那条 assistant 消息
 *       不许再落库。这条错了表现为「库里攒下任何查询都捞不出来的行、残留核账永远到不了空」，
 *       而界面上一切正常。</li>
 * </ol>
 *
 * @author 走进新时代
 */
class AgentConversationServiceImplTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentConversationServiceImpl conversationService;

    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private AgentMessageMapper messageMapper;
    @Mock
    private AgentChatProperties chatProperties;

    /** 让 mock 的 insert 回填自增 id，好断言 createConversation 的返回值 */
    private void stubInsertAssignsId(long id) {
        when(conversationMapper.insert(any(AgentConversationDO.class))).thenAnswer(inv -> {
            ((AgentConversationDO) inv.getArgument(0)).setId(id);
            return 1;
        });
    }

    @Test
    @DisplayName("三参建会话：agent_def_id 落库，人设跨刷新可恢复")
    void createConversation_withAgentDefId_persistsBinding() {
        stubInsertAssignsId(300L);

        Long id = conversationService.createConversation(1L, "订单咨询", 88L);

        assertEquals(300L, id);
        ArgumentCaptor<AgentConversationDO> captor = ArgumentCaptor.forClass(AgentConversationDO.class);
        verify(conversationMapper).insert(captor.capture());
        AgentConversationDO inserted = captor.getValue();
        assertEquals(88L, inserted.getAgentDefId(), "🔴 绑定的 Agent 必须落库，否则刷新后丢失人设");
        assertEquals(1L, inserted.getUserId());
        assertEquals("订单咨询", inserted.getTitle());
    }

    @Test
    @DisplayName("二参建会话：委派三参、agentDefId=null（默认平台助手），老调用方不受影响")
    void createConversation_twoArg_delegatesWithNullAgentDefId() {
        stubInsertAssignsId(301L);

        Long id = conversationService.createConversation(1L, "随便问问");

        assertEquals(301L, id);
        ArgumentCaptor<AgentConversationDO> captor = ArgumentCaptor.forClass(AgentConversationDO.class);
        verify(conversationMapper).insert(captor.capture());
        assertNull(captor.getValue().getAgentDefId(), "二参版=默认助手，不该意外绑定 Agent");
    }

    @Test
    @DisplayName("三参但 agentDefId 传 null：等价默认助手（chat() 新会话未选 Agent 时正是这条）")
    void createConversation_nullAgentDefId_isDefaultAssistant() {
        stubInsertAssignsId(302L);

        conversationService.createConversation(1L, null, null);

        ArgumentCaptor<AgentConversationDO> captor = ArgumentCaptor.forClass(AgentConversationDO.class);
        verify(conversationMapper).insert(captor.capture());
        assertNull(captor.getValue().getAgentDefId());
        assertEquals("新对话", captor.getValue().getTitle(), "空标题回落默认标题");
    }

    // ==================== appendMessage：会话没了就不许再落消息 ====================

    /**
     * 🔴 这一节钉的是方案 §9-6 第 1 条那个**孤儿消息**缺陷。
     *
     * <p>改之前 {@code appendMessage} 是<b>无条件 insert</b>，把「会话在不在」留给了后面的
     * {@code refreshConversation} —— 那时行已经落库了。实测攒出两条孤儿（消息 681 / 769，
     * 分别比会话的 update_time 晚 19s / 45s），后果是残留核账永远到不了空。
     *
     * <p>⚠️ 这个方法此前<b>一条用例都没有</b>（本文件只覆盖了 createConversation），
     * 所以缺陷能一直活着。
     */
    private AgentMessageDO message(String role, String content) {
        AgentMessageDO m = new AgentMessageDO();
        m.setConversationId(346L);
        m.setUserId(1L);
        m.setRole(role);
        m.setContent(content);
        m.setStatus("SUCCESS");
        return m;
    }

    private AgentConversationDO conversation(String title, Integer messageCount) {
        AgentConversationDO c = new AgentConversationDO();
        c.setId(346L);
        c.setUserId(1L);
        c.setTitle(title);
        c.setMessageCount(messageCount);
        return c;
    }

    /** 让 mock 的 insert 回填自增 id，好断言 appendMessage 的返回值 */
    private void stubMessageInsertAssignsId(long id) {
        when(messageMapper.insert(any(AgentMessageDO.class))).thenAnswer(inv -> {
            ((AgentMessageDO) inv.getArgument(0)).setId(id);
            return 1;
        });
    }

    @Test
    @DisplayName("会话已被删：不落库、返回 null —— 会咬的那条断言是 insert 没被调用")
    void appendMessage_conversationGone_skipsInsert() {
        when(conversationMapper.selectById(346L)).thenReturn(null);

        Long id = conversationService.appendMessage(
                message(AgentMessageDO.ROLE_ASSISTANT, "根据手册片段……"));

        // 🔴 `verify(never())` 才是会咬的断言，`assertNull(id)` 单独写是**空断言**：
        //    旧代码是「先 insert、再 return message.getId()」，而 mock 的 insert 不回填 id，
        //    于是旧代码在这一条上**同样**返回 null —— 断言在改前改后都绿，等于没测（§9-8 那一类）。
        verify(messageMapper, never()).insert(any(AgentMessageDO.class));
        verify(conversationMapper, never()).updateById(any(AgentConversationDO.class));
        assertNull(id, "契约见 AgentConversationService.appendMessage：会话没了返回 null");
    }

    @Test
    @DisplayName("会话还活着：照常落库，计数 +1、刷新活跃时间，且只查一次会话")
    void appendMessage_conversationAlive_insertsAndRefreshes() {
        when(conversationMapper.selectById(346L)).thenReturn(conversation("订单咨询", 4));
        stubMessageInsertAssignsId(681L);

        Long id = conversationService.appendMessage(
                message(AgentMessageDO.ROLE_ASSISTANT, "根据手册片段……"));

        assertEquals(681L, id);
        verify(messageMapper).insert(any(AgentMessageDO.class));
        ArgumentCaptor<AgentConversationDO> captor = ArgumentCaptor.forClass(AgentConversationDO.class);
        verify(conversationMapper).updateById(captor.capture());
        assertEquals(346L, captor.getValue().getId());
        assertEquals(5, captor.getValue().getMessageCount(), "计数要 +1");
        assertNotNull(captor.getValue().getLastActiveTime(), "活跃时间要刷新，否则会话列表排序不动");
        // 🔴 判空与查询只能有一个地方做：appendMessage 查好了传给 refreshConversation，
        //    后者不再自己查一次。两处各查各判，就没人说得清哪一处是权威（D7），还白花一次 selectById
        verify(conversationMapper, times(1)).selectById(346L);
    }

    @Test
    @DisplayName("首条用户提问且标题还是默认值：回填标题（会话列表不该一排「新对话」）")
    void appendMessage_firstUserQuestion_backfillsTitle() {
        when(chatProperties.getTitleMaxLength()).thenReturn(20);
        when(conversationMapper.selectById(346L)).thenReturn(conversation("新对话", 0));

        conversationService.appendMessage(message(AgentMessageDO.ROLE_USER, "报价单怎么配审批？"));

        ArgumentCaptor<AgentConversationDO> captor = ArgumentCaptor.forClass(AgentConversationDO.class);
        verify(conversationMapper).updateById(captor.capture());
        assertEquals("报价单怎么配审批？", captor.getValue().getTitle());
    }

    @Test
    @DisplayName("助手回答不许当标题：update 对象里 title 保持 null（不覆盖已有标题）")
    void appendMessage_assistantMessage_doesNotTouchTitle() {
        // 刻意不 stub getTitleMaxLength：这一条压根不该走到算标题那步
        when(conversationMapper.selectById(346L)).thenReturn(conversation("订单咨询", 4));

        conversationService.appendMessage(
                message(AgentMessageDO.ROLE_ASSISTANT, "根据手册片段……"));

        ArgumentCaptor<AgentConversationDO> captor = ArgumentCaptor.forClass(AgentConversationDO.class);
        verify(conversationMapper).updateById(captor.capture());
        assertNull(captor.getValue().getTitle(),
                "用助手回答当标题会很怪：回答往往以「根据手册片段……」开头，一列会话看过去全是同一句话");
    }

}
