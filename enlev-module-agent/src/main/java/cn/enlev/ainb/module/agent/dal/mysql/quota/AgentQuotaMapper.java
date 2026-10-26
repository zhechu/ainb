package cn.enlev.ainb.module.agent.dal.mysql.quota;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.dal.dataobject.quota.AgentQuotaDO;
import cn.enlev.ainb.module.agent.enums.AgentQuotaDimensionEnum;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * AI 助手配额 Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentQuotaMapper extends BaseMapperX<AgentQuotaDO> {

    /**
     * 查当前租户的租户级配额
     *
     * ⚠️ 用 {@code isNull} 而不是 {@code eq(0L)} —— 见 {@link AgentQuotaDO#getUserId()}。
     * 🔴 自 P7 起必须再叠 {@code dimension=TENANT}：Agent / 工作流级配额的 user_id 同样为 null，
     * 只按 isNull(userId) 会把它们误当成租户级配额捞出来。
     */
    default AgentQuotaDO selectTenantQuota() {
        return selectOne(new LambdaQueryWrapperX<AgentQuotaDO>()
                .eq(AgentQuotaDO::getDimension, AgentQuotaDimensionEnum.TENANT.getDimension())
                .isNull(AgentQuotaDO::getUserId));
    }

    /**
     * 查某个用户的专属配额
     */
    default AgentQuotaDO selectUserQuota(Long userId) {
        return selectOne(new LambdaQueryWrapperX<AgentQuotaDO>()
                .eq(AgentQuotaDO::getDimension, AgentQuotaDimensionEnum.USER.getDimension())
                .eq(AgentQuotaDO::getUserId, userId));
    }

    /**
     * 查某个 <b>Agent（按 code）</b> 的配额；没有则返回 null（= 不限制）。
     *
     * <p>🔴 target_key 存的是 code（非 id），跨版本稳定 —— 见 {@link AgentQuotaDO#getTargetKey()}。
     */
    default AgentQuotaDO selectAgentQuota(String agentCode) {
        return selectOne(new LambdaQueryWrapperX<AgentQuotaDO>()
                .eq(AgentQuotaDO::getDimension, AgentQuotaDimensionEnum.AGENT.getDimension())
                .eq(AgentQuotaDO::getTargetKey, agentCode));
    }

    /**
     * 查某条 <b>工作流（按 code）</b> 的配额；没有则返回 null（= 不限制）。
     */
    default AgentQuotaDO selectWorkflowQuota(String workflowCode) {
        return selectOne(new LambdaQueryWrapperX<AgentQuotaDO>()
                .eq(AgentQuotaDO::getDimension, AgentQuotaDimensionEnum.WORKFLOW.getDimension())
                .eq(AgentQuotaDO::getTargetKey, workflowCode));
    }

    /**
     * 查当前租户的全部配额
     */
    default List<AgentQuotaDO> selectListAll() {
        return selectList(new LambdaQueryWrapperX<AgentQuotaDO>()
                .orderByAsc(AgentQuotaDO::getDimension)
                .orderByAsc(AgentQuotaDO::getUserId)
                .orderByAsc(AgentQuotaDO::getTargetKey));
    }

}
