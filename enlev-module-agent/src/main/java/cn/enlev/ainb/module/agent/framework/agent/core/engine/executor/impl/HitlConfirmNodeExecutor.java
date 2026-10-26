package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code hitl.confirm} 节点执行器：轻确认（DSL §4.4，P1 只做「能挂起 + 能恢复」的最小证明）
 *
 * <h3>🔴 挂起发生在节点<b>之前</b>，本执行器只在恢复后跑</h3>
 * 编译器对每个 hitl.* 节点调 {@code interruptBefore(id)}：SAA 在执行到该节点前挂起、
 * checkpoint 落库、stream 正常结束（末元素是 InterruptionMetadata）。
 * 前端确认 → {@code updateState(config, {approveVar: true})} → {@code withResume()} →
 * {@code stream(null, config)}，此时本执行器才被调用，读到的 approveVar 就是用户的决定。
 *
 * <p>所以这里<b>不做任何等待/轮询</b> —— 等待是 checkpoint 挂起天然实现的（可以等几天，
 * 跨进程跨重启都在，SPIKE 验证①②）。执行器只负责「把决定读出来、写个确认标记」。
 *
 * <h3>P1 范围</h3>
 * 只做 approveVar 布尔确认。超时自动拒绝（timeoutSeconds）、PlanCard 富交互、
 * bpm_approval 重审批都是 P2+。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class HitlConfirmNodeExecutor implements NodeExecutor {

    public static final String TYPE = "hitl.confirm";

    /**
     * 默认确认变量名，也是<b>聊天内恢复</b>唯一会写的键（{@code AgentAssistantServiceImpl.HITL_APPROVE_VAR}）
     *
     * <p>{@code KeyStrategyFactoryGenerator} 无条件注册这个键：SAA resume 的 {@code OverAllState.input()}
     * 只保留已注册 KeyStrategy 的键，不注册的话聊天续跑喂进来的决定会在恢复瞬间被丢掉
     * —— 表现为「点了同意却按拒绝走完，状态还报成功」。
     */
    public static final String DEFAULT_APPROVE_VAR = "hitl_approved";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
        String approveVar = node.configStr("approveVar", DEFAULT_APPROVE_VAR);
        // 聊天内恢复（AgentAssistantServiceImpl）只会写死键 hitl_approved，不认 DSL 里的自定义 approveVar。
        // 不回退就会读到 null → 判成拒绝 → 流程静默走 default 分支还报 SUCCEEDED，用户以为「同意了却没干活」。
        Object approved = ctx.get(approveVar);
        if (approved == null && !DEFAULT_APPROVE_VAR.equals(approveVar)) {
            approved = ctx.get(DEFAULT_APPROVE_VAR);
        }
        boolean isApproved = Boolean.TRUE.equals(approved)
                || "true".equalsIgnoreCase(String.valueOf(approved));
        log.info("[execute][hitl.confirm 节点({}) 执行({}) 确认结果({})]",
                node.getId(), ctx.getExecutionId(), isApproved);

        Map<String, Object> delta = new HashMap<>();
        // 归一化成布尔写回，下游 condition.rule 可用 is_true/is_false 判定
        delta.put(approveVar, isApproved);
        delta.put(node.getId() + "_confirmed", isApproved ? "approved" : "rejected");
        return delta;
    }

}
