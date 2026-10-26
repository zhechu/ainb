package cn.enlev.ainb.module.agent.service.quota;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI 助手配额 Service
 *
 * @author 走进新时代
 */
public interface AgentQuotaService {

    /**
     * 检查配额，超限则抛业务异常
     *
     * <h3>🔴 只能在请求<b>入口</b>调用</h3>
     * 方案 §8.5 的硬要求是「拒绝新请求，<b>不中断进行中的会话</b>」。
     * 中断会产生「扣了 token 但没拿到结果」的争议 ——
     * 而这种争议没法自证清白：用户说没拿到，日志说调用成功了，谁也说服不了谁。
     *
     * <p>所以这个方法<b>不能</b>放进 Agent 循环、流式回调或工具执行里。
     * 一旦放行，这一轮就要让它跑完。
     *
     * @param userId 用户编号
     */
    void checkQuota(Long userId);

    /**
     * 检查某个 <b>Agent（按 code）</b> 的配额，超限则抛业务异常（P7 / 待办 #6b）
     *
     * <p>与 {@link #checkQuota} 同款纪律：<b>只在请求入口调用</b>（聊天路径解析出绑定 Agent 之后、
     * 真正烧 token 之前），一旦放行这一轮就跑完。用量按 code 跨版本合并、跨该 Agent 的所有用户合并，
     * 从计费表 JOIN 会话表归因（不改计费语义，见 {@code AgentUsageLogMapper.selectUsageSumByAgent}）。
     * 没给该 Agent 配额度时直接放行（= 不限制）。
     *
     * @param agentCode Agent 定义的 code（非 numeric id）；为空时不检查
     */
    void checkAgentQuota(String agentCode);

    /**
     * 检查某条 <b>工作流（按 code）</b> 的配额，超限则抛业务异常（P7 / 待办 #6b）
     *
     * <p>在工作流 {@code run()} 入口调用（建执行实例之前）。🔴 只限 <b>token</b>：工作流用量从节点级
     * trace（{@code agent_node_execution}）汇总，那张表没有 cost 列，且按「不改计费语义」不新增工作流计费
     * 写入口径，故金额上限对工作流维度是死配置（{@code AgentQuotaDimensionEnum.WORKFLOW.costSupported=false}）。
     * 没给该工作流配额度时直接放行（= 不限制）。
     *
     * @param workflowCode 工作流定义的 code（非 numeric id）；为空时不检查
     */
    void checkWorkflowQuota(String workflowCode);

    /**
     * 查询某个用户当前的配额使用情况（不抛异常，供看板与自查用）
     *
     * @param userId 用户编号
     * @return 使用情况；没有配置任何配额时返回空列表
     */
    List<QuotaUsageBO> getQuotaUsage(Long userId);

    /**
     * 列出当前租户的全部配额配置（管理页用）
     */
    List<cn.enlev.ainb.module.agent.dal.dataobject.quota.AgentQuotaDO> listQuotas();

    /**
     * 新增或更新一条配额
     *
     * @param quota 配额；{@code id} 为空表示新增
     * @return 配额编号
     */
    Long saveQuota(cn.enlev.ainb.module.agent.dal.dataobject.quota.AgentQuotaDO quota);

    /**
     * 删除一条配额
     *
     * 删掉即「不限制」（§8.5 的兜底），不是「限额为 0」。
     */
    void deleteQuota(Long id);

    /**
     * 配额使用情况
     */
    @Data
    class QuotaUsageBO {

        /**
         * 配额层级：TENANT / USER（{@link #getQuotaUsage} 自查只返回这两档；
         * AGENT / WORKFLOW 走各自的 check 方法，不进用户自查看板）
         */
        private String level;
        /**
         * 配额维度：TENANT / USER / AGENT / WORKFLOW
         */
        private String dimension;
        /**
         * 维度目标键（AGENT / WORKFLOW 时为定义 code；TENANT / USER 时为 null）
         */
        private String targetKey;
        /**
         * 周期：DAY / MONTH
         */
        private String period;
        /**
         * 本周期已用 token
         */
        private Long usedTokens;
        /**
         * token 上限，null 表示不限
         */
        private Long tokenLimit;
        /**
         * 本周期已用金额
         */
        private BigDecimal usedCost;
        /**
         * 金额上限，null 表示不限
         */
        private BigDecimal costLimit;
        /**
         * 是否已超限
         */
        private Boolean exceeded;

    }

}
