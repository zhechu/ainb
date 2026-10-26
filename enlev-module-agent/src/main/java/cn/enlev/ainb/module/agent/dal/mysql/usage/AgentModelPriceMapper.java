package cn.enlev.ainb.module.agent.dal.mysql.usage;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.dal.dataobject.usage.AgentModelPriceDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 助手模型单价 Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentModelPriceMapper extends BaseMapperX<AgentModelPriceDO> {

    /**
     * 取某个模型在<b>指定时刻</b>生效的单价
     *
     * 🔴 判据是 {@code effective_from <= 调用时间} 里最新的那一行，
     * 不是「最新的一行」。差别在涨价之后重算历史账单时才显现出来：
     * 取最新价会让三个月前的账单变成今天的价钱。
     *
     * @param platform  平台
     * @param modelName 模型标识
     * @param at        调用时刻
     * @return 单价，没有配置时返回 null
     */
    default AgentModelPriceDO selectEffectivePrice(String platform, String modelName,
                                                   LocalDateTime at) {
        List<AgentModelPriceDO> list = selectList(new LambdaQueryWrapperX<AgentModelPriceDO>()
                .eqIfPresent(AgentModelPriceDO::getPlatform, platform)
                .eq(AgentModelPriceDO::getModelName, modelName)
                .le(AgentModelPriceDO::getEffectiveFrom, at)
                .orderByDesc(AgentModelPriceDO::getEffectiveFrom)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

}
