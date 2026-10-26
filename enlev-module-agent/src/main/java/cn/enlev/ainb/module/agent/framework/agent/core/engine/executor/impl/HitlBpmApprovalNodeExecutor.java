package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code hitl.bpm_approval} 节点执行器：重审批（DSL §4.4，P6 收口）
 *
 * <h3>🔴 与 {@code hitl.confirm} 同机制——挂起在节点之前，本执行器只在恢复后跑</h3>
 * 编译器对 {@code hitl.*} 前缀节点登记 {@code interruptBefore(id)}（{@code DslToStateGraphCompiler} L337-340），
 * SAA 执行到该节点<b>之前</b>挂起、checkpoint 落库、stream 结束。<b>发起 BPM 审批流程实例</b>这个副作用
 * 由 {@code AgentWorkflowExecutionServiceImpl.runGraph} 在中断收尾处调 {@code AgentBpmApprovalService.createApproval}
 * 完成（与算 resume_at/event_key 同一处），并把 businessKey 落成执行实例的 {@code event_key}。
 * 审批人在 BPM 里通过/驳回 → {@code BpmProcessInstanceStatusEvent} → {@code AgentBpmApprovalListener}
 * 按 businessKey 走 {@code publishEvent(eventKey, payload)} 把审批结果写进 checkpoint 并 resume。
 *
 * <p>所以本执行器<b>不创建实例、不等待、不轮询</b>——那些已由「interruptBefore 挂起 + BPM 回调 resume」实现。
 * 它只负责「把审批结果读出来、归一化写回 state」，与 {@code HitlConfirmNodeExecutor} 的职责完全对称。
 *
 * <h3>approveVar 归一化</h3>
 * resume 的 feedback 由监听器写入布尔值 + 原始状态码（2=通过/3=驳回/4=取消）。本执行器把 approveVar
 * 归一化成布尔、把状态码归一化成整数，<b>都按节点配的变量名写回 state</b>（NodeIoContract 登记的输出），
 * 并额外写 {@code <nodeId>_result} 便于下游 condition.rule 用字符串判定（approved/rejected/canceled）、
 * {@code <nodeId>_status} 供审计/展示。
 *
 * <h3>🔴 监听器只会写死<b>默认</b>键名，所以自定义 approveVar/statusVar 必须回退读默认键</h3>
 * {@code AgentBpmApprovalListener} 跑在审批人点「通过/驳回」的那个线程上，手里只有一个 businessKey，
 * <b>拿不到节点 config</b>，因此 payload 的键恒为 {@code bpm_approved}/{@code bpm_status}。
 * 而 resume 的 payload 要能被本节点读到，键还必须在 {@code KeyStrategyFactoryGenerator} 注册过
 * （SAA {@code OverAllState.input()} 只保留注册过 KeyStrategy 的键）—— 两处都以默认名为准。
 * 于是「配了自定义 approveVar」的流程若只读自己的名字，读到的是 null → 判成驳回 →
 * 流程静默走拒绝分支还把执行状态报成 SUCCEEDED，用户看到的就是「审批通过了却没干活」。
 * 回退与 {@code HitlConfirmNodeExecutor} 对 {@code hitl_approved} 的同款处理对称；
 * 显式按 approveVar 传 feedback 的调用方（工作台 resume-stream）优先级更高，回退只在读不到时生效。
 *
 * <h3>本节点不调 LLM，token 恒为 0</h3>
 * 与 {@code hitl.confirm}/{@code knowledge_retrieval} 同理，根本不碰 metrics.usage，规避 SR-2 的 tokenUsage 粘滞。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class HitlBpmApprovalNodeExecutor implements NodeExecutor {

    public static final String TYPE = "hitl.bpm_approval";

    /**
     * 审批结果写入的默认变量名，也是<b>监听器唯一会写</b>的键（拿不到节点 config，见类注释）
     *
     * <p>{@code KeyStrategyFactoryGenerator} 无条件注册这个键，否则审批结果在 resume 瞬间就被丢掉。
     */
    public static final String DEFAULT_APPROVE_VAR = "bpm_approved";
    /** 监听器写入原始 BPM 状态码的默认变量名，同样是<b>监听器唯一会写</b>的键 */
    public static final String DEFAULT_STATUS_VAR = "bpm_status";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
        String approveVar = node.configStr("approveVar", DEFAULT_APPROVE_VAR);
        String statusVar = node.configStr("statusVar", DEFAULT_STATUS_VAR);

        // 🔴 读不到自定义键时回退默认键：AgentBpmApprovalListener 在审批线程上发事件，拿不到节点 config，
        //    只会写死 bpm_approved / bpm_status 两个默认名。所以自定义了 approveVar 的流程，
        //    resume 喂进来的仍是默认键。不回退就读到 null → 判成驳回 → 流程静默走拒绝分支还报 SUCCEEDED，
        //    用户看到的就是「审批通过了却没干活」。与 HitlConfirmNodeExecutor 的同款回退对称。
        Object approved = ctx.get(approveVar);
        if (approved == null && !DEFAULT_APPROVE_VAR.equals(approveVar)) {
            approved = ctx.get(DEFAULT_APPROVE_VAR);
        }
        boolean isApproved = Boolean.TRUE.equals(approved) || "true".equalsIgnoreCase(String.valueOf(approved));
        Object rawStatus = ctx.get(statusVar);
        if (rawStatus == null && !DEFAULT_STATUS_VAR.equals(statusVar)) {
            rawStatus = ctx.get(DEFAULT_STATUS_VAR);
        }
        Integer bpmStatus = toInt(rawStatus);
        String result = resolveResult(isApproved, bpmStatus);

        log.info("[execute][hitl.bpm_approval 节点({}) 执行({}) 审批结果({}) 状态码({})]",
                node.getId(), ctx.getExecutionId(), result, bpmStatus);

        Map<String, Object> delta = new HashMap<>();
        // 归一化成布尔写回，下游 condition.rule 可用 is_true/is_false 判定
        delta.put(approveVar, isApproved);
        delta.put(node.getId() + "_result", result);
        if (bpmStatus != null) {
            // 🔴 statusVar 也必须写回：NodeIoContract 把它登记成本节点的<b>输出</b>（out("statusVar", …)），
            //    设计器据此让作者配名字、下游据此写 {{$.my_status}}。只写 <nodeId>_status 的话，
            //    配了自定义 statusVar 的流程里那个变量永远是 null —— 又是「配了但不生效且无提示」。
            //    用默认名时看不出问题（resume payload 本来就把 bpm_status 落进了 state），只有自定义名会露。
            delta.put(statusVar, bpmStatus);
            delta.put(node.getId() + "_status", bpmStatus);
        }
        return delta;
    }

    /** 审批结果三态：通过 / 驳回 / 取消（状态码 4=CANCEL 时即便 approved 为真也判取消） */
    private String resolveResult(boolean isApproved, Integer bpmStatus) {
        if (bpmStatus != null && bpmStatus == 4) {
            return "canceled";
        }
        return isApproved ? "approved" : "rejected";
    }

    private Integer toInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

}
