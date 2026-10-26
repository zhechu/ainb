package cn.enlev.ainb.module.agent.listener;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlBpmApprovalNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.workflow.AgentBpmApprovalService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService;
import cn.enlev.ainb.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * BPM 审批完成 → 唤醒 {@code hitl.bpm_approval} 挂起的工作流（P6 收口）
 *
 * <h3>为什么直接实现 {@link ApplicationListener} 而非继承 {@code BpmProcessInstanceStatusEventListener}</h3>
 * 那个基类按<b>单个</b> processDefinitionKey 过滤（一个业务配一个监听器）；而 bpm_approval 是通用能力，
 * 要覆盖所有由 agent 工作流发起的审批（processDefinitionKey 各不相同）。故照 {@code BpmOnlineFormStatusListener}
 * 的做法直接实现 ApplicationListener，按 <b>businessKey 前缀</b>过滤——只有 {@code agent-wf:} 开头的才是本模块发起的。
 *
 * <h3>🔴 租户：事件在审批人的同步线程上发布，租户上下文即审批人租户</h3>
 * {@code BpmProcessInstanceServiceImpl.processProcessInstanceCompleted} 用 {@code ApplicationEventPublisher.publishEvent}
 * <b>同步</b>发事件（bpm 模块无 {@code @TransactionalEventListener}），故本监听器跑在审批完成的那个事务/线程里，
 * {@code TenantContextHolder} 已是审批人租户。BPM 实例由 Flowable 在<b>发起时</b>的租户里创建，而发起发生在
 * 工作流执行所有者租户——同租户审批（本平台 BPM 不跨租户），故审批人租户 == 执行所有者租户 == 待唤醒执行的租户。
 * 因此可直接调 {@code publishEvent}，它内部按 {@code event_key} 查 INTERRUPTED 执行（TenantBaseDO 自动拼 tenant_id），
 * 并以<b>行身份</b>（执行所有者）resume。<b>全程不用 executeIgnore</b>（agent 架构红线）。
 *
 * <h3>幂等与去重</h3>
 * 审批完成事件理论上可能重发；{@code publishEvent} → {@code resume} 内有 CAS 认领
 * （{@code UPDATE ... SET status=RUNNING WHERE id=? AND status=INTERRUPTED}），已恢复的执行影响 0 行、被安静跳过，
 * 故重复事件不会双跑工作流。非终态事件（RUNNING）直接忽略——只在 APPROVE/REJECT/CANCEL 唤醒。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class AgentBpmApprovalListener implements ApplicationListener<BpmProcessInstanceStatusEvent> {

    /**
     * BPM 流程实例状态码，镜像 bpm 的 {@code BpmProcessInstanceStatusEnum}（RUNNING=1/APPROVE=2/REJECT=3/CANCEL=4）。
     *
     * <p>🔴 刻意不 import 那个枚举：它在 {@code bpm.enums} 包，而 agent 跨模块只能走 {@code <module>.api.*}
     * （{@code AgentArchitectureTest#testOnlyDependOnApiLayer} 红线），bpm 的 api 层又没有暴露状态枚举。
     * 这与本模块 {@code BpmProcessValidator.TYPE_APPROVE} / {@code ExplainProcessTool.TYPE_APPROVE} 是同款做法——
     * 本地常量镜像对方的稳定状态码。这些码是<b>持久化契约</b>（bpm 流程实例状态列 + 本事件 status 字段都用它），
     * 改动会破坏所有既有消费方，故本地镜像不会漂移。
     */
    private static final int STATUS_APPROVE = 2;
    private static final int STATUS_REJECT = 3;
    private static final int STATUS_CANCEL = 4;

    @Resource
    private AgentWorkflowExecutionService executionService;

    /** 流程是否已到终态（通过/驳回/取消）；RUNNING 等中间态返回 false —— 审批还没结束，不唤醒 */
    private static boolean isProcessEndStatus(int status) {
        return status == STATUS_APPROVE || status == STATUS_REJECT || status == STATUS_CANCEL;
    }

    @Override
    public void onApplicationEvent(BpmProcessInstanceStatusEvent event) {
        String businessKey = event.getBusinessKey();
        // 只认 agent 工作流发起的审批（businessKey 前缀 agent-wf:），其余（在线表单/OA 等）原样放过
        if (!AgentBpmApprovalService.isAgentWorkflowBusinessKey(businessKey)) {
            return;
        }
        Integer status = event.getStatus();
        // 只在流程终态唤醒；RUNNING/NOT_START 是中间态，审批还没结束
        if (status == null || !isProcessEndStatus(status)) {
            return;
        }
        // businessKey == 挂起时落的 event_key（agent-wf:executionId:nodeId），直接广播唤醒
        boolean approved = STATUS_APPROVE == status;
        Map<String, Object> payload = new HashMap<>();
        // 🔴 只能写这两个<b>默认</b>键名，别想着「按节点 config 的 approveVar 写」：本监听器手里只有 businessKey，
        //   事件跑在审批人线程上，无从拿到挂起节点的 config（要拿就得反查 def+node，还得处理多节点同名）。
        //   约定是「监听器写死默认名 + 执行器读不到自定义名时回退默认名」，两半都在
        //   HitlBpmApprovalNodeExecutor / KeyStrategyFactoryGenerator 里钉着单测，改一边必须改另一边。
        payload.put(HitlBpmApprovalNodeExecutor.DEFAULT_APPROVE_VAR, approved);
        payload.put(HitlBpmApprovalNodeExecutor.DEFAULT_STATUS_VAR, status);

        try {
            // publishEvent 的 context 只用于 requireIdentity 守卫 + 租户边界；事件已在审批人（==执行所有者）租户线程上，
            // 用 snapshot() 捕获当前身份即可。命中执行后内部以「行身份」resume，归属正确。
            AgentExecutionContext ctx = AgentExecutionContext.snapshot();
            if (!ctx.isComplete()) {
                // 审批完成事件理论上总在有登录用户的请求线程上（审批人点了通过/驳回）；若拿不到完整身份，
                // fail-close：不降级、不匿名唤醒，留明确日志，工作流停在 INTERRUPTED 等人工 resume-stream。
                log.warn("[onApplicationEvent][BPM 审批完成 businessKey({}) 但捕获不到完整登录身份，跳过自动唤醒，"
                        + "工作流停在挂起态等人工 resume]", businessKey);
                return;
            }
            int dispatched = executionService.publishEvent(businessKey, payload, ctx);
            log.info("[onApplicationEvent][BPM 审批完成 businessKey({}) 状态({}) 唤醒({}) 条工作流执行]",
                    businessKey, status, dispatched);
        } catch (Exception ex) {
            // 唤醒失败不能让 BPM 审批本身回滚——审批结果以 Flowable 为准，工作流可事后人工 resume。
            // 但必须留明确日志，否则「审批通过了但工作流没继续」将无从排查（平台头号缺陷形态）。
            log.error("[onApplicationEvent][BPM 审批完成 businessKey({}) 状态({}) 唤醒工作流失败]",
                    businessKey, status, ex);
        }
    }

}
