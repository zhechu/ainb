package cn.enlev.ainb.module.agent.service.workflow;

/**
 * Workflow checkpoint 线程名（{@code thread_name}）格式的<b>唯一定义处</b>
 *
 * <h3>🔴 为什么必须只有一个定义处（迭代七 B3-5）</h3>
 * SAA 的 {@code GRAPH_THREAD} / {@code GRAPH_CHECKPOINT} 两张表<b>既没有 tenant_id 列、
 * 也没有本平台的 executionId</b> —— {@code thread_name} 是把「这条 checkpoint 属于谁」
 * 这件事带进 SAA 的<b>唯一</b>载体。所以它同时是：
 * <ul>
 *   <li>resume 的寻址键（SAA 按 {@code thread_name} 找快照）；</li>
 *   <li>{@code agent_workflow_execution.thread_name} 那个 unique key 的取值。</li>
 * </ul>
 *
 * <p>改前的现状是<b>两处各拼各的</b>：正式路径在 {@code AgentWorkflowExecutionServiceImpl} 里硬编码拼接，
 * 沙箱路径拼 sandbox: 前缀加随机 UUID。两种形状并存本身还不致命（沙箱用内存 saver、跑完即弃），
 * 但它把「格式里必须含租户」这条不变量变成了<b>一处口头约定</b>：
 * 谁照着沙箱那半写新调用点，谁就写出一条<b>不含租户</b>的 thread_name，
 * 而后果不是报错 —— 是 resume 静默命中<b>另一个租户</b>的 checkpoint（数据串号级别）。
 * 收敛成一个静态方法后，这条不变量由唯一实现来守，新调用点无从写歪。
 *
 * <h3>🔴 沙箱为什么可以直接用同一格式、不必再挂 UUID</h3>
 * 沙箱每次 {@code runSandbox} 都 {@code new MemorySaver()}，checkpoint 存储<b>随本次调用生灭</b>，
 * 并发两次沙箱即便 thread_name 逐字相同也各自寻址各自的 saver，不存在共享状态；
 * 且沙箱既不建执行实例、也不落 trace（{@code NodeTraceRecorder} 三处写库点均被 {@code isSandbox} 守卫），
 * 这个值<b>永远不会进库</b>，撞不上那道 unique key。
 *
 * @author 走进新时代
 * @see cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO#getThreadName()
 */
public final class AgentWorkflowThreadName {

    private AgentWorkflowThreadName() {
    }

    /**
     * 按 {@code t{tenantId}:{executionId}} 生成线程名
     *
     * <p>🔴 租户<b>必须</b>在格式里：它是 checkpoint 跨租户隔离的唯一屏障，
     * 少了它，两个租户的同号执行会寻址到同一条快照。
     *
     * @param tenantId    租户编号，调用方须已确保非空（正式路径与沙箱路径都在 {@code requireIdentity} 之后调用）
     * @param executionId 执行实例编号；沙箱传其合成 id {@code SANDBOX_EXECUTION_ID}
     * @return 线程名
     */
    public static String threadName(Long tenantId, Long executionId) {
        return "t" + tenantId + ":" + executionId;
    }

}
