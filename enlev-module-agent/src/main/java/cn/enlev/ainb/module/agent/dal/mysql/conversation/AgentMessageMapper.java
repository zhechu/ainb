package cn.enlev.ainb.module.agent.dal.mysql.conversation;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentMessageDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * AI 助手消息 Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentMessageMapper extends BaseMapperX<AgentMessageDO> {

    /**
     * 按会话查全部消息，按 id 正序（即时间正序）
     *
     * 用 id 而不是 create_time 排序：同一秒内插入的多条消息用时间排不出稳定顺序，
     * 而用户提问与助手回答恰恰经常落在同一秒。顺序错了，多轮上下文就串了。
     */
    default List<AgentMessageDO> selectListByConversationId(Long conversationId) {
        return selectList(new LambdaQueryWrapperX<AgentMessageDO>()
                .eq(AgentMessageDO::getConversationId, conversationId)
                .orderByAsc(AgentMessageDO::getId));
    }

    /**
     * 删除某个会话下的全部消息
     */
    default int deleteByConversationId(Long conversationId) {
        return delete(new LambdaQueryWrapperX<AgentMessageDO>()
                .eq(AgentMessageDO::getConversationId, conversationId));
    }

}
