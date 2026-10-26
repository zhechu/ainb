package cn.enlev.ainb.module.agent.dal.mysql.workflow;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentPlannerAdoptionDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 规划采纳率埋点 Mapper（迭代七 B5-3）
 *
 * <p>租户隔离由 {@code TenantBaseDO} + TenantLineInnerInterceptor 自动拼 {@code tenant_id=?} 保证：
 * 采纳率是<b>每租户各算各的</b>，A 租户绝数不到 B 租户的草稿。
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentPlannerAdoptionMapper extends BaseMapperX<AgentPlannerAdoptionDO> {

    /**
     * 按 code 取<b>待确认</b>（adopted=false）的最新一行，供确认（publish）时 UPDATE。
     *
     * <p>同一 code 理论上至多一条待确认行（生成时 INSERT、确认时翻 adopted）；
     * 取 {@code id DESC LIMIT 1} 是防御性兜底（万一重试生成落了多条，认最新那条）。
     * 已确认的行（adopted=true）不再被选中 —— 重复点「确认并运行」不会把一条已采纳的行改来改去。
     */
    default AgentPlannerAdoptionDO selectPendingByCode(String code) {
        return selectOne(new LambdaQueryWrapperX<AgentPlannerAdoptionDO>()
                .eq(AgentPlannerAdoptionDO::getWorkflowCode, code)
                .eq(AgentPlannerAdoptionDO::getAdopted, false)
                .orderByDesc(AgentPlannerAdoptionDO::getId)
                .last("LIMIT 1"));
    }

}
