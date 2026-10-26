package cn.enlev.ainb.module.bpm.service.task.listener;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import cn.enlev.ainb.module.bpm.service.definition.BpmProcessDefinitionService;
import cn.enlev.ainb.module.bpm.service.task.BpmOnlineFormDataService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 在线表单的流程状态回写监听器
 *
 * 流程状态变化时，把状态写回业务表的 process_status 列。
 *
 * 为什么不继承 BpmProcessInstanceStatusEventListener：那个基类按单个 processDefinitionKey 过滤，
 * 适用于「一个业务表单配一个监听器」的场景（如 OA 请假）；而在线表单是通用能力，
 * 需要覆盖所有以在线表单发起的流程，因此直接实现 ApplicationListener 并按表单类型过滤。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class BpmOnlineFormStatusListener implements ApplicationListener<BpmProcessInstanceStatusEvent> {

    @Resource
    private BpmProcessDefinitionService processDefinitionService;
    @Resource
    private BpmOnlineFormDataService onlineFormDataService;
    @Resource
    private RuntimeService runtimeService;
    @Resource
    private HistoryService historyService;

    @Override
    public void onApplicationEvent(BpmProcessInstanceStatusEvent event) {
        String businessKey = event.getBusinessKey();
        if (StrUtil.isEmpty(businessKey)) {
            // 非在线表单发起的流程没有 businessKey，直接跳过
            return;
        }
        // 🔴 #106：businessKey 不是数字主键就不回写。在线表单的 businessKey 是业务表行的数字主键；
        //    而 agent 模块的 hitl.bpm_approval 用 `agent-wf:<executionId>:<nodeId>` 这种命名空间键发起审批
        //    （见 AgentBpmApprovalService#resolveBpmApprovalEventKey）。若放它过去，updateStatus 会拿这个串
        //    去 UPDATE 业务表主键列，抛异常把事务标成 rollback-only —— 而本方法后面的 try/catch 拦不住
        //    这种标记（catch 在事务内、标记已经打上），审批照样 500、事务回滚。与基类
        //    BpmProcessInstanceStatusEventListener 的守卫同一判据、同一理由（台账 #59 那次），
        //    log.debug 不刷屏：对 agent 流程这是每次审批都会走到的正常路径。
        if (!NumberUtil.isLong(businessKey)) {
            log.debug("[onApplicationEvent][流程实例({}) 的 businessKey({}) 不是数字主键，跳过在线表单状态回写"
                    + "（本监听器只服务「businessKey = 业务表行数字主键」的在线表单流程）]",
                    event.getId(), businessKey);
            return;
        }
        BpmProcessDefinitionInfoDO definitionInfo = getProcessDefinitionInfo(event.getId());
        if (!onlineFormDataService.isOnlineForm(definitionInfo)) {
            return;
        }
        try {
            onlineFormDataService.updateStatus(definitionInfo, businessKey, event.getStatus());
        } catch (Exception ex) {
            // 状态回写失败不能让流程流转失败 —— 流程状态以 Flowable 为准，业务表的 process_status 是冗余展示用。
            // 但必须留下明确日志，否则业务表状态与流程不一致时无从排查
            log.error("[onApplicationEvent][流程({}) 表单({}) 业务主键({}) 回写状态({}) 失败]",
                    event.getId(), definitionInfo.getFormDesignCode(), businessKey, event.getStatus(), ex);
        }
    }

    /**
     * 获得流程定义信息
     *
     * 先查运行时实例；流程结束时实例已从运行时表移除，再退回历史表。
     * 不能用「按 processDefinitionKey 取最新定义」兜底 —— 流程若在实例运行期间重新部署过，
     * 最新定义的 formDesignCode 可能已经变了，会把状态写到另一张表去。
     */
    private BpmProcessDefinitionInfoDO getProcessDefinitionInfo(String processInstanceId) {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (instance != null) {
            return processDefinitionService.getProcessDefinitionInfo(instance.getProcessDefinitionId());
        }
        HistoricProcessInstance historicInstance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (historicInstance != null) {
            return processDefinitionService.getProcessDefinitionInfo(historicInstance.getProcessDefinitionId());
        }
        return null;
    }

}
