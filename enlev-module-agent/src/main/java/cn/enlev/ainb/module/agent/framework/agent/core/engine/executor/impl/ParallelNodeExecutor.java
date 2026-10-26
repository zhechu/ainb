package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code parallel} 节点执行器：并行分支的<b>结构标记</b>（DSL §4.3）
 *
 * <h3>🔴 本执行器是 no-op pass-through，并行语义不在这里</h3>
 * fan-out/fan-in 是<b>建图期</b>的结构，由 {@code DslToStateGraphCompiler} 读 parallel 节点的
 * {@code config.branches} 展开成 SAA 原生边：{@code addEdge(par, branchNodes)} 分发、
 * {@code addEdge(branchNodes, joinTarget)} 汇聚屏障（=join=all）。受 SAA 原生约束，MVP 分支为<b>单节点</b>
 * （多节点链需子图封装 → 增量2b sub_workflow）。执行器<b>不能</b>加 SAA
 * 节点/边（SPI 的硬约束：1 个 DSL 节点 ↔ 1 个 SAA 节点），所以它只负责：
 * <ul>
 *   <li>作为 fan-out 的<b>单一锚点</b>——图里真实存在的一个节点，任何前驱（静态边 / 条件边 routeMap /
 *       START 兜底）都能指向它，无需为并行改写路由表；</li>
 *   <li>在 {@code agent_node_execution} 留一条 0-token 的 trace，作为用户在设计器里看得见的「分叉点」。</li>
 * </ul>
 * 这与 {@code condition.*} 的分工同构：执行器管节点自身语义，编译器管结构性建边。
 *
 * <h3>execute 返回空 delta，token 恒 0</h3>
 * 不改任何状态（分支节点各自写自己的 outputVar）；不碰 metrics.usage，规避 SR-2 的 tokenUsage 粘滞。
 *
 * <h3>join / branches / maxParallelism 的校验与展开全在编译器</h3>
 * 见 {@code DslToStateGraphCompiler#expandParallel}：join 仅支持 {@code all}（{@code any} 编译期拒绝）、
 * 分支 id 必须存在于顶层 {@code nodes[]}、不跨分支重复、不手写进 {@code edges[]}、分支数不超
 * {@code settings.maxParallelism}。执行器这里不重复校验，保持「谁建图谁校验」。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class ParallelNodeExecutor implements NodeExecutor {

    public static final String TYPE = "parallel";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
        log.info("[execute][parallel 节点({}) 执行({})：分发锚点，实际 fan-out/fan-in 由编译器建边]",
                node.getId(), ctx.getExecutionId());
        return Map.of();
    }

}
