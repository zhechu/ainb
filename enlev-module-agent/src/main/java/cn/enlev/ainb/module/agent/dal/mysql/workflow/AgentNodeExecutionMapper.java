package cn.enlev.ainb.module.agent.dal.mysql.workflow;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentNodeExecutionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Workflow 节点执行 trace Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentNodeExecutionMapper extends BaseMapperX<AgentNodeExecutionDO> {

    /**
     * 查某次执行的全部节点 trace，按 id 升序（即执行先后）
     */
    default List<AgentNodeExecutionDO> selectListByExecutionId(Long executionId) {
        return selectList(new LambdaQueryWrapperX<AgentNodeExecutionDO>()
                .eq(AgentNodeExecutionDO::getExecutionId, executionId)
                .orderByAsc(AgentNodeExecutionDO::getId));
    }

    /**
     * 查某节点当前最大 attempt（重试时新增行要先知道上一次是第几次）
     *
     * 没有记录时返回 null，调用方按 1 起算。
     */
    default Integer selectMaxAttempt(Long executionId, String nodeId) {
        AgentNodeExecutionDO last = selectOne(new LambdaQueryWrapperX<AgentNodeExecutionDO>()
                .eq(AgentNodeExecutionDO::getExecutionId, executionId)
                .eq(AgentNodeExecutionDO::getNodeId, nodeId)
                .orderByDesc(AgentNodeExecutionDO::getAttempt)
                .last("LIMIT 1"));
        return last == null ? null : last.getAttempt();
    }

    /**
     * Saga 反做：查某次执行里<b>需要且能够</b>被补偿的节点 trace，按 id <b>降序</b>（即执行的逆序）。
     *
     * <p>筛选口径（三条同时成立）：
     * <ul>
     *   <li>{@code compensate_ref} 非空 —— 节点声明了反向操作（DSL {@code compensation.compensate_ref}）；</li>
     *   <li>{@code status = SUCCESS} —— 只有<b>真正跑成功</b>的节点其副作用才确定已生效、才需要撤销；
     *       失败/跳过/仍在跑的节点副作用未知，不纳入（保守：宁可漏撤一个未完成的，不误撤一个没发生的）。</li>
     * </ul>
     * 逆序（id DESC）是 Saga 的硬要求：后做的先撤，避免「先删了 A 才发现 B 依赖 A」。
     * 租户隔离由 {@code TenantLineInnerInterceptor} 自动拼 {@code tenant_id=?}（与其它查询同款）。
     */
    default List<AgentNodeExecutionDO> selectCompensableDesc(Long executionId) {
        return selectList(new LambdaQueryWrapperX<AgentNodeExecutionDO>()
                .eq(AgentNodeExecutionDO::getExecutionId, executionId)
                .eq(AgentNodeExecutionDO::getStatus, "SUCCESS")
                .isNotNull(AgentNodeExecutionDO::getCompensateRef)
                .ne(AgentNodeExecutionDO::getCompensateRef, "")
                .orderByDesc(AgentNodeExecutionDO::getId));
    }

    /**
     * 汇总某条 <b>工作流（按 code）</b> 在某周期内烧掉的 token（P7 / 待办 #6b：工作流维度配额）
     *
     * <h3>🔴 只有 token、没有金额</h3>
     * 工作流用量来自本表（节点级 trace），而本表<b>没有 cost 列</b>——成本只在计费表 {@code agent_usage_log}
     * 上算，且工作流的 LLM 节点用量<b>不</b>写进计费表（见 {@code LlmNodeExecutor}：落 ctx.metrics → 本表）。
     * 按用户选定「不改计费语义」，本期<b>不</b>给工作流新增计费写入口径，故工作流配额只能限 token；
     * {@code AgentQuotaDimensionEnum.WORKFLOW.costSupported=false}，金额上限对它是死配置（前后端都禁用）。
     *
     * <h3>🔴 按 code 而非 id 归因：跨版本合并</h3>
     * agent_workflow_def 版本化（同 code 多行）。JOIN 到 {@code wd.code = #{code}} 把该工作流<b>所有版本</b>
     * 的 token 合并计入本周期，否则一发新版旧配额就漏统计。与 Agent 维度、chat 触发器绑定同口径。
     *
     * <p>只对 trace 行自身加 {@code ne.deleted=0}（防御，本表无删除入口）；不过滤 {@code we.deleted}，
     * 理由同 Agent 维度（已烧的 token 不因执行记录后续状态而抹掉）。租户隔离由
     * {@code TenantLineInnerInterceptor} 自动给三张表各拼 {@code tenant_id=?}；逻辑删除对原生 {@code @Select}
     * 不自动生效，故 {@code ne.deleted} 手动写死。
     *
     * @param code  工作流定义的 code（非 numeric id）
     * @param begin 周期起点
     * @return 本周期累计 token，没有记录时为 0
     */
    @Select("SELECT IFNULL(SUM(ne.total_tokens), 0) "
            + "FROM agent_node_execution ne "
            + "JOIN agent_workflow_execution we ON ne.execution_id = we.id "
            + "JOIN agent_workflow_def wd ON we.workflow_def_id = wd.id "
            + "WHERE wd.code = #{code} AND ne.begin_time >= #{begin} AND ne.deleted = 0")
    Long sumTokensByWorkflowCode(@Param("code") String code, @Param("begin") LocalDateTime begin);

}
