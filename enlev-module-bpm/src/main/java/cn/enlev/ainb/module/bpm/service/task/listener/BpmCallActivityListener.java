package cn.enlev.ainb.module.bpm.service.task.listener;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.simple.BpmSimpleModelNodeVO;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import cn.enlev.ainb.module.bpm.enums.definition.BpmChildProcessStartUserEmptyTypeEnum;
import cn.enlev.ainb.module.bpm.enums.definition.BpmChildProcessStartUserTypeEnum;
import cn.enlev.ainb.module.bpm.framework.flowable.core.util.FlowableUtils;
import cn.enlev.ainb.module.bpm.service.definition.BpmProcessDefinitionService;
import cn.enlev.ainb.module.bpm.service.task.BpmProcessInstanceService;
import jakarta.annotation.Resource;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BPM 子流程监听器：设置流程的发起人
 *
 * @author Lesan
 */
@Component
@Slf4j
public class BpmCallActivityListener implements ExecutionListener {

    public static final String DELEGATE_EXPRESSION = "${bpmCallActivityListener}";

    @Setter
    private Expression listenerConfig;

    @Resource
    private BpmProcessDefinitionService processDefinitionService;

    @Resource
    private BpmProcessInstanceService processInstanceService;

    @Override
    public void notify(DelegateExecution execution) {
        String expressionText = listenerConfig.getExpressionText();
        Assert.notNull(expressionText, "监听器扩展字段({})不能为空", expressionText);
        BpmSimpleModelNodeVO.ChildProcessSetting.StartUserSetting startUserSetting = JsonUtils.parseObject(
                expressionText, BpmSimpleModelNodeVO.ChildProcessSetting.StartUserSetting.class);
        ProcessInstance processInstance = processInstanceService.getProcessInstance(execution.getRootProcessInstanceId());

        // ⚠️ 子流程调起授权（v9 §4.2）【不在这里】，见 BpmCallActivityBehavior。
        //
        // 它一度写在本方法首部，但这个监听器只有【钉钉式设计器】生成的子流程节点才挂
        // （SimpleModelUtils 手动挂的 EVENTNAME_START 监听器），原生 BPMN 的 callActivity
        // 根本走不到这里 —— 那道防线换个设计器就没有了，而界面上看不出任何区别。
        // 现在校验挂在 ActivityBehaviorFactory 上，那是所有 CallActivity 的必经之路（TODO #37）

        // 1. 当发起人来源为主流程发起人时，并兜底 startUserSetting 为空时
        if (startUserSetting == null
                || startUserSetting.getType().equals(BpmChildProcessStartUserTypeEnum.MAIN_PROCESS_START_USER.getType())) {
            FlowableUtils.setAuthenticatedUserId(Long.parseLong(processInstance.getStartUserId()));
            return;
        }

        // 2. 当发起人来源为表单时
        if (startUserSetting.getType().equals(BpmChildProcessStartUserTypeEnum.FROM_FORM.getType())) {
            String formFieldValue = MapUtil.getStr(processInstance.getProcessVariables(), startUserSetting.getFormField());
            // 2.1 当表单值为空时
            if (StrUtil.isEmpty(formFieldValue)) {
                // 2.1.1 来自主流程发起人
                if (startUserSetting.getEmptyType().equals(BpmChildProcessStartUserEmptyTypeEnum.MAIN_PROCESS_START_USER.getType())) {
                    FlowableUtils.setAuthenticatedUserId(Long.parseLong(processInstance.getStartUserId()));
                    return;
                }
                // 2.1.2 来自子流程管理员
                if (startUserSetting.getEmptyType().equals(BpmChildProcessStartUserEmptyTypeEnum.CHILD_PROCESS_ADMIN.getType())) {
                    BpmProcessDefinitionInfoDO processDefinition = processDefinitionService.getProcessDefinitionInfo(execution.getProcessDefinitionId());
                    List<Long> managerUserIds = processDefinition.getManagerUserIds();
                    FlowableUtils.setAuthenticatedUserId(managerUserIds.get(0));
                    return;
                }
                // 2.1.3 来自主流程管理员
                if (startUserSetting.getEmptyType().equals(BpmChildProcessStartUserEmptyTypeEnum.MAIN_PROCESS_ADMIN.getType())) {
                    BpmProcessDefinitionInfoDO processDefinition = processDefinitionService.getProcessDefinitionInfo(processInstance.getProcessDefinitionId());
                    List<Long> managerUserIds = processDefinition.getManagerUserIds();
                    FlowableUtils.setAuthenticatedUserId(managerUserIds.get(0));
                    return;
                }
            }
            // 2.2 使用表单值，并兜底字符串转 Long 失败时使用主流程发起人
            try {
                FlowableUtils.setAuthenticatedUserId(Long.parseLong(formFieldValue));
            } catch (NumberFormatException ex) {
                try {
                    List<Long> formFieldValues = JsonUtils.parseArray(formFieldValue, Long.class);
                    FlowableUtils.setAuthenticatedUserId(formFieldValues.get(0));
                } catch (Exception e) {
                    log.error("[notify][监听器：{}，子流程监听器设置流程的发起人字符串转 Long 失败，字符串：{}]",
                            DELEGATE_EXPRESSION, formFieldValue);
                    FlowableUtils.setAuthenticatedUserId(Long.parseLong(processInstance.getStartUserId()));
                }
            }
        }
    }

}
