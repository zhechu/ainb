package cn.enlev.ainb.module.bpm.service.task;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import cn.enlev.ainb.module.bpm.enums.definition.BpmModelFormTypeEnum;
import cn.enlev.ainb.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmnVariableConstants;
import cn.enlev.ainb.module.bpm.framework.formdata.core.handler.BpmOnlineFormDataHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 在线表单的业务数据编排 Service
 *
 * 负责把「表单设计」的业务数据读写，编织进流程的发起、审批、状态回写三条链路。
 *
 * 事务说明：本类的方法不自己开事务，而是要求调用方（如 BpmProcessInstanceServiceImpl#createProcessInstance）
 * 已处于事务中。这样业务表写入与流程启动 / 任务完成才在同一个事务里，任一失败都能整体回滚。
 * 底层 FormDesignRuntimeService 的方法是 REQUIRED 传播，会自动加入外层事务。
 *
 * 注意：Flowable 的异步执行器（async job）在事务提交后独立运行，其失败不会回滚业务数据。
 * 涉及延迟器、定时触发器、异步子流程的流程，业务数据一致性需靠补偿而非事务保证。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class BpmOnlineFormDataService {

    @Resource
    private BpmOnlineFormDataHandler onlineFormDataHandler;
    @Resource
    private RuntimeService runtimeService;

    /**
     * 是否为在线表单类型的流程
     */
    public boolean isOnlineForm(BpmProcessDefinitionInfoDO definitionInfo) {
        return definitionInfo != null
                && Objects.equals(definitionInfo.getFormType(), BpmModelFormTypeEnum.ONLINE.getType())
                && StrUtil.isNotEmpty(definitionInfo.getFormDesignCode());
    }

    /**
     * 发起流程前：写入主表单的业务数据
     *
     * 顺序上必须先写业务表再启流程 —— 业务主键要作为流程的 businessKey，
     * 而主键只有写入后才拿得到（自增或雪花都由写入环节产生）。
     *
     * @param definitionInfo 流程定义信息
     * @param formVariables  表单数据。就是流程变量，在线表单同时把它落到业务表
     * @return 业务主键
     */
    public Object createMainFormData(BpmProcessDefinitionInfoDO definitionInfo, Map<String, Object> formVariables) {
        String formCode = definitionInfo.getFormDesignCode();
        Object pk = onlineFormDataHandler.create(formCode, formVariables);
        if (pk == null) {
            // 主键拿不到就没法作 businessKey，也没法在后续节点定位这行数据，必须中断
            throw new IllegalStateException(StrUtil.format(
                    "在线表单({})写入业务表后未返回主键，请检查该表单的主键策略配置", formCode));
        }
        return pk;
    }

    /**
     * 发起流程后：把流程实例编号与初始状态回写到业务表
     *
     * process_instance_id 不参与运行时的数据定位（那走 businessKey 与流程变量），它的价值在于
     * 从业务表能反查到流程 —— 排障与报表都依赖它。
     *
     * process_status 也必须在这里写：状态监听器只在流程状态**发生变化**时触发，发起这一刻不会触发，
     * 不在这里补写的话业务表的 process_status 会一直是 NULL，直到流程结束才第一次有值。
     */
    public void bindProcessInstance(BpmProcessDefinitionInfoDO definitionInfo, Object pk, String processInstanceId) {
        Map<String, Object> columns = new HashMap<>(2);
        columns.put(BpmOnlineFormDataHandler.COLUMN_PROCESS_INSTANCE_ID, processInstanceId);
        columns.put(BpmOnlineFormDataHandler.COLUMN_PROCESS_STATUS, BpmProcessInstanceStatusEnum.RUNNING.getStatus());
        onlineFormDataHandler.updateSystemColumns(definitionInfo.getFormDesignCode(), pk, columns);
    }

    /**
     * 记录节点独立表单的业务主键
     *
     * 节点表单绑定了与主表单不同的表时，其业务主键存入流程变量，供后续节点与详情页定位数据。
     *
     * @param processInstanceId 流程实例编号
     * @param formCode          节点表单编码
     * @param pk                业务主键
     */
    public void recordNodeFormPk(String processInstanceId, String formCode, Object pk) {
        if (StrUtil.isEmpty(processInstanceId) || StrUtil.isEmpty(formCode) || pk == null) {
            return;
        }
        runtimeService.setVariable(processInstanceId, buildNodeFormPkVariable(formCode), pk);
    }

    /**
     * 获得节点独立表单的业务主键
     *
     * @param processInstanceId 流程实例编号
     * @param formCode          节点表单编码
     * @return 业务主键。该节点表单尚未产生数据时返回 null
     */
    public Object getNodeFormPk(String processInstanceId, String formCode) {
        if (StrUtil.isEmpty(processInstanceId) || StrUtil.isEmpty(formCode)) {
            return null;
        }
        return runtimeService.getVariable(processInstanceId, buildNodeFormPkVariable(formCode));
    }

    /**
     * 保存节点表单的数据
     *
     * 分两种情况：
     * 1. 节点表单就是主表单（未配置独立表单，或配置的编码与主表单相同）：更新主表单那一行
     * 2. 节点表单绑定了其它表：首次提交 INSERT 并把主键记入流程变量，再次提交 UPDATE
     *
     * @param definitionInfo    流程定义信息
     * @param processInstanceId 流程实例编号
     * @param businessKey       主表单的业务主键
     * @param nodeFormCode      节点独立表单编码。为空表示沿用主表单
     * @param data              已按节点表单权限裁剪过的表单数据
     */
    public void saveNodeFormData(BpmProcessDefinitionInfoDO definitionInfo, String processInstanceId,
                                 Object businessKey, String nodeFormCode, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        String mainFormCode = definitionInfo.getFormDesignCode();
        // 情况一：沿用主表单
        if (StrUtil.isEmpty(nodeFormCode) || Objects.equals(nodeFormCode, mainFormCode)) {
            onlineFormDataHandler.update(mainFormCode, businessKey, data);
            return;
        }
        // 情况二：节点表单绑定了其它表
        Object nodePk = getNodeFormPk(processInstanceId, nodeFormCode);
        if (nodePk == null) {
            Map<String, Object> createData = new HashMap<>(data);
            Object pk = onlineFormDataHandler.create(nodeFormCode, createData);
            if (pk == null) {
                throw new IllegalStateException(StrUtil.format(
                        "节点在线表单({})写入业务表后未返回主键，请检查该表单的主键策略配置", nodeFormCode));
            }
            // 与主表单一样回写流程实例编号，保证从这张表也能反查到流程
            onlineFormDataHandler.updateProcessInstanceId(nodeFormCode, pk, processInstanceId);
            recordNodeFormPk(processInstanceId, nodeFormCode, pk);
            return;
        }
        onlineFormDataHandler.update(nodeFormCode, nodePk, data);
    }

    /**
     * 读取主表单的业务数据
     *
     * @param definitionInfo 流程定义信息
     * @param businessKey    主表单的业务主键
     * @return 业务数据
     */
    public Map<String, Object> getMainFormData(BpmProcessDefinitionInfoDO definitionInfo, Object businessKey) {
        return onlineFormDataHandler.get(definitionInfo.getFormDesignCode(), businessKey);
    }

    /**
     * 读取节点表单的数据
     *
     * @param definitionInfo    流程定义信息
     * @param processInstanceId 流程实例编号
     * @param businessKey       主表单的业务主键
     * @param nodeFormCode      节点独立表单编码。为空表示沿用主表单
     * @return 业务数据
     */
    public Map<String, Object> getNodeFormData(BpmProcessDefinitionInfoDO definitionInfo, String processInstanceId,
                                               Object businessKey, String nodeFormCode) {
        String mainFormCode = definitionInfo.getFormDesignCode();
        if (StrUtil.isEmpty(nodeFormCode) || Objects.equals(nodeFormCode, mainFormCode)) {
            return onlineFormDataHandler.get(mainFormCode, businessKey);
        }
        Object nodePk = getNodeFormPk(processInstanceId, nodeFormCode);
        return nodePk == null ? null : onlineFormDataHandler.get(nodeFormCode, nodePk);
    }

    /**
     * 回写流程状态到主表单的业务表
     *
     * @param definitionInfo 流程定义信息
     * @param businessKey    主表单的业务主键
     * @param status         流程状态，见 BpmProcessInstanceStatusEnum
     */
    public void updateStatus(BpmProcessDefinitionInfoDO definitionInfo, Object businessKey, Integer status) {
        onlineFormDataHandler.updateStatus(definitionInfo.getFormDesignCode(), businessKey, status);
    }

    private String buildNodeFormPkVariable(String formCode) {
        return String.format(BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_ONLINE_FORM_PK, formCode);
    }

}
