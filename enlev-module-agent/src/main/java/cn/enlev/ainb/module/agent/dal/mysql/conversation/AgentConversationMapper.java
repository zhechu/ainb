package cn.enlev.ainb.module.agent.dal.mysql.conversation;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentConversationDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 助手会话 Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentConversationMapper extends BaseMapperX<AgentConversationDO> {

    /**
     * 分页查某个用户的会话
     *
     * 🔴 <b>userId 是必传的，不是 {@code eqIfPresent}。</b>
     * 租户隔离由框架自动加 {@code tenant_id} 条件保证，但它只挡住「跨租户」——
     * 同租户里的另一个用户仍然会看到别人的会话。会话内容是私有数据，
     * 漏掉这个条件就是同租户内的越权浏览，而且界面上完全看不出异常。
     *
     * <p>用 {@code eq} 而不是 {@code eqIfPresent} 是刻意的：
     * 传 null 时应该查不出东西（{@code user_id IS NULL} 匹配不到任何行），
     * 而不是「不过滤」把全租户的会话都端出来。
     * 上游 {@code AiChatMapper#selectFirstByStatus} 正好是反过来的教训 —— 那里的 {@code type} 用了
     * {@code eq}，调用方以为传 null 是「不过滤」，实际查出空集。
     * 同一个行为在这里是安全的默认，在那里是缺陷 —— 区别在于<b>失败的方向</b>。
     */
    default PageResult<AgentConversationDO> selectPageByUserId(
            Long userId, Integer pageNo, Integer pageSize) {
        return selectPage(new cn.enlev.ainb.framework.common.pojo.PageParam()
                        .setPageNo(pageNo).setPageSize(pageSize),
                new LambdaQueryWrapperX<AgentConversationDO>()
                        .eq(AgentConversationDO::getUserId, userId)
                        // 置顶的排前面，其余按最后活跃时间倒序。
                        // last_active_time 可能为 null（刚建还没说话），用 id 兜底
                        .orderByDesc(AgentConversationDO::getPinned)
                        .orderByDesc(AgentConversationDO::getLastActiveTime)
                        .orderByDesc(AgentConversationDO::getId));
    }

    /**
     * 按编号 + 用户编号查
     *
     * 所有「按 id 取会话」的地方都必须走这个方法，<b>不要用 selectById</b>。
     * 理由同上：id 是可枚举的，只按 id 查等于把同租户其他人的会话开放给任何人猜。
     */
    default AgentConversationDO selectByIdAndUserId(Long id, Long userId) {
        return selectOne(new LambdaQueryWrapperX<AgentConversationDO>()
                .eq(AgentConversationDO::getId, id)
                .eq(AgentConversationDO::getUserId, userId));
    }

}
