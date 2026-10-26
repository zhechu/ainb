package cn.enlev.ainb.module.bpm.api.event;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;

/**
 * {@link BpmProcessInstanceStatusEvent} 的监听器
 *
 * 🔴 本基类只服务于「businessKey 就是业务实体的数字主键」这一类监听器（OA 请假、CRM 合同/回款）。
 *    派发前会先确认 businessKey 能被 {@link Long#parseLong} 接受，不能就直接 return —— 见
 *    {@link #onApplicationEvent} 里的注释，那不是在防一个假想情况，是在修一个已经发生的缺陷。
 *
 * ⚠️ 若你的业务模块用**非数字**的 businessKey（例如带命名空间前缀的复合键），**不要继承本类**：
 *    本类的过滤是按「数字主键」这个前提写的，继承它就等于声明了这个前提。
 *    参照同包的 {@code BpmOnlineFormStatusListener} —— 它自己 isEmpty 过滤、自己按流程定义过滤、
 *    并把回写整个包在 try/catch 里，注释明写「状态回写失败不能让流程流转失败」。
 *
 * @author 芋道源码
 */
@Slf4j
public abstract class BpmProcessInstanceStatusEventListener
        implements ApplicationListener<BpmProcessInstanceStatusEvent> {

    @Override
    public final void onApplicationEvent(BpmProcessInstanceStatusEvent event) {
        if (!StrUtil.equals(event.getProcessDefinitionKey(), getProcessDefinitionKey())) {
            return;
        }
        // 🔴 businessKey 不是数字主键就不派发。三个子类（BpmOALeaveStatusListener、CrmContractStatusListener、
        //    CrmReceivableStatusListener）都在 onEvent 里无条件 Long.parseLong(event.getBusinessKey())，
        //    而 agent 模块的 hitl.bpm_approval 节点用 `agent-wf:<executionId>:<nodeId>` 这种命名空间键发起审批
        //    （它必须携带 executionId + nodeId，才能把审批结果路由回正确的挂起节点，见
        //    AgentBpmApprovalService#resolveBpmApprovalEventKey）。于是只要作者把 bpmProcessDefKey 配成
        //    `oa_leave`（仓库自带的演示流程定义，也是最容易被选中的那个），审批人一点「通过」就抛
        //    NumberFormatException，后果是**两重**的：
        //      ① Spring 的 SimpleApplicationEventMulticaster 默认没有 ErrorHandler，第一个抛异常的监听器
        //         会**中止整条链** ⟹ AgentBpmApprovalListener 压根没跑 ⟹ 工作流永远停在 INTERRUPTED；
        //      ② Flowable 的命令上下文一起中止 ⟹ 审批操作返回 500 且**事务回滚**。
        //    🔴 放在基类而不是逐个守卫子类：这里是唯一能一次覆盖全部子类的地方（onApplicationEvent 已经是
        //    final、已经在做 processDefinitionKey 过滤），且「只派发数字键」不改变任何现有行为 ——
        //    现有三个子类的 businessKey 无一例外都是数字主键，非数字的那些今天只会抛异常。
        //    🔴 不要用 @Order 让 agent 的监听器先跑：那只治 ①、不治 ②，而且更坏 —— 等于在审批结果即将
        //    回滚时先唤醒工作流，制造「BPM 说没通过、agent 说通过了」的跨模块不一致，比一致地卡死更难查。
        //    ℹ️ log.debug 而不是 warn：对 agent 流程这是**正常**情况（每次审批都会走到），warn 会刷屏；
        //    但又不能全静默 —— 真出问题时（某个子类本该收到数字键却没收到）需要留一条可查的线索。
        if (!NumberUtil.isLong(event.getBusinessKey())) {
            log.debug("[onApplicationEvent][流程定义({}) 的实例({}) businessKey({}) 不是数字主键，"
                            + "不派发给 {}（本基类只服务「businessKey = 业务实体数字主键」的监听器）]",
                    event.getProcessDefinitionKey(), event.getId(), event.getBusinessKey(),
                    getClass().getSimpleName());
            return;
        }
        onEvent(event);
    }

    /**
     * @return 返回监听的流程定义 Key
     */
    protected abstract String getProcessDefinitionKey();

    /**
     * 处理事件
     *
     * 🔴 进来时已保证 {@code event.getBusinessKey()} 能被 {@link Long#parseLong} 接受，
     *    子类可以直接解析，不必再判空/判格式。
     *
     * @param event 事件
     */
    protected abstract void onEvent(BpmProcessInstanceStatusEvent event);

}
