package cn.enlev.ainb.module.bpm.service.definition;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.common.util.object.PageUtils;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.process.BpmProcessDefinitionPageReqVO;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmFormDO;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import cn.enlev.ainb.module.bpm.dal.mysql.definition.BpmProcessDefinitionInfoMapper;
import cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmnModelConstants;
import cn.enlev.ainb.module.bpm.framework.flowable.core.util.FlowableUtils;
import cn.enlev.ainb.module.system.api.user.AdminUserApi;
import cn.enlev.ainb.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.common.engine.impl.db.SuspensionState;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.*;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.addIfNotNull;
import static cn.enlev.ainb.module.bpm.enums.ErrorCodeConstants.*;
import static java.util.Collections.emptyList;

/**
 * 流程定义实现
 * 主要进行 Flowable {@link ProcessDefinition} 和 {@link Deployment} 的维护
 *
 * @author yunlongn
 * @author ZJQ
 * @author 芋道源码
 */
@Service
@Validated
@Slf4j
public class BpmProcessDefinitionServiceImpl implements BpmProcessDefinitionService {

    @Resource
    private RepositoryService repositoryService;

    @Resource
    private BpmProcessDefinitionInfoMapper processDefinitionMapper;

    @Resource
    private AdminUserApi adminUserApi;
    @Resource
    private cn.enlev.ainb.module.bpm.framework.permission.BpmProcessPermissionChecker processPermissionChecker;

    @Override
    public ProcessDefinition getProcessDefinition(String id) {
        return repositoryService.getProcessDefinition(id);
    }

    @Override
    public List<ProcessDefinition> getProcessDefinitionList(Set<String> ids) {
        return repositoryService.createProcessDefinitionQuery().processDefinitionIds(ids).list();
    }

    @Override
    public ProcessDefinition getProcessDefinitionByDeploymentId(String deploymentId) {
        if (StrUtil.isEmpty(deploymentId)) {
            return null;
        }
        return repositoryService.createProcessDefinitionQuery().deploymentId(deploymentId).singleResult();
    }

    @Override
    public List<ProcessDefinition> getProcessDefinitionListByDeploymentIds(Set<String> deploymentIds) {
        if (CollUtil.isEmpty(deploymentIds)) {
            return emptyList();
        }
        return repositoryService.createProcessDefinitionQuery().deploymentIds(deploymentIds).list();
    }

    @Override
    public ProcessDefinition getActiveProcessDefinition(String key) {
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(FlowableUtils.getTenantId())
                .processDefinitionKey(key).active().singleResult();
    }

    @Override
    public boolean canUserStartProcessDefinition(BpmProcessDefinitionInfoDO processDefinition, Long userId) {
        if (processDefinition == null) {
            return false;
        }

        // 判据只有一条：流程资源上的 START 授权（S7b / TODO #30 摘掉了旧列表回落）
        //
        // 三个 ID 列表现在是「编辑界面」而不是判据 —— 每次部署时由
        // ProcessResourceCommonApi#syncProcessAuthorization 投影成授权，
        // 包括「两个范围都为空 → 给 ALL:all-users 一条 START 授权」。
        //
        // ⚠️ 这也意味着从别的环境导入流程定义、或绕过部署接口直接改
        // bpm_process_definition_info 时，必须补跑一次投影
        //（sql/mysql/xpy-2026-09-17-process-scope-projection.sql），
        // 否则那些流程会变成「谁都发不了」
        return processPermissionChecker.canStart(userId, processDefinition.getProcessDefinitionId());
    }

    @Override
    public List<Deployment> getDeploymentList(Set<String> ids) {
        if (CollUtil.isEmpty(ids)) {
            return emptyList();
        }
        List<Deployment> list = new ArrayList<>(ids.size());
        for (String id : ids) {
            addIfNotNull(list, getDeployment(id));
        }
        return list;
    }

    @Override
    public Deployment getDeployment(String id) {
        if (StrUtil.isEmpty(id)) {
            return null;
        }
        return repositoryService.createDeploymentQuery().deploymentId(id).singleResult();
    }

    @Override
    public String createProcessDefinition(Model model, BpmModelMetaInfoVO modelMetaInfo,
                                          byte[] bpmnBytes, String simpleJson, BpmFormDO form) {
        // 创建 Deployment 部署
        Deployment deploy = repositoryService.createDeployment()
                .key(model.getKey()).name(model.getName()).category(model.getCategory())
                .addBytes(model.getKey() + BpmnModelConstants.BPMN_FILE_SUFFIX, bpmnBytes)
                .tenantId(FlowableUtils.getTenantId())
                .disableSchemaValidation() // 禁用 XML Schema 验证，因为有自定义的属性
                .deploy();

        // 设置 ProcessDefinition 的 category 分类
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploy.getId()).singleResult();
        repositoryService.setProcessDefinitionCategory(definition.getId(), model.getCategory());
        // 注意 1，ProcessDefinition 的 key 和 name 是通过 BPMN 中的 <bpmn2:process /> 的 id 和 name 决定
        // 注意 2，目前该项目的设计上，需要保证 Model、Deployment、ProcessDefinition 使用相同的 key，保证关联性。
        //          否则，会导致 ProcessDefinition 的分页无法查询到。
        if (!Objects.equals(definition.getKey(), model.getKey())) {
            throw exception(PROCESS_DEFINITION_KEY_NOT_MATCH, model.getKey(), definition.getKey());
        }
        if (!Objects.equals(definition.getName(), model.getName())) {
            throw exception(PROCESS_DEFINITION_NAME_NOT_MATCH, model.getName(), definition.getName());
        }

        // 插入拓展表
        BpmProcessDefinitionInfoDO definitionDO = BeanUtils.toBean(modelMetaInfo, BpmProcessDefinitionInfoDO.class)
                .setModelId(model.getId()).setCategory(model.getCategory()).setProcessDefinitionId(definition.getId())
                .setModelType(modelMetaInfo.getType()).setSimpleModel(simpleJson);
        if (form != null) {
            definitionDO.setFormFields(form.getFields()).setFormConf(form.getConf());
        }
        processDefinitionMapper.insert(definitionDO);
        return definition.getId();
    }

    @Override
    public void updateProcessDefinitionState(String id, Integer state) {
        ProcessDefinition processDefinition = repositoryService.getProcessDefinition(id);
        if (processDefinition == null) {
            throw exception(PROCESS_DEFINITION_NOT_EXISTS);
        }

        // 激活
        if (Objects.equals(SuspensionState.ACTIVE.getStateCode(), state)) {
            if (processDefinition.isSuspended()) {
                repositoryService.activateProcessDefinitionById(id, false, null);
            }
            return;
        }
        // 挂起
        if (Objects.equals(SuspensionState.SUSPENDED.getStateCode(), state)) {
            // suspendProcessInstances = false，进行中的任务，不进行挂起。
            // 原因：只要新的流程不允许发起即可，老流程继续可以执行。
            if (!processDefinition.isSuspended()) {
                repositoryService.suspendProcessDefinitionById(id, false, null);
            }
            return;
        }
        log.error("[updateProcessDefinitionState][流程定义({}) 修改未知状态({})]", id, state);
    }

    @Override
    public void updateProcessDefinitionSortByModelId(String modelId, Long sort) {
        processDefinitionMapper.updateByModelId(modelId, new BpmProcessDefinitionInfoDO().setSort(sort));
    }

    @Override
    public BpmnModel getProcessDefinitionBpmnModel(String id) {
        return repositoryService.getBpmnModel(id);
    }

    @Override
    public BpmProcessDefinitionInfoDO getProcessDefinitionInfo(String id) {
        return processDefinitionMapper.selectByProcessDefinitionId(id);
    }

    @Override
    public List<BpmProcessDefinitionInfoDO> getProcessDefinitionInfoList(Collection<String> ids) {
        return processDefinitionMapper.selectListByProcessDefinitionIds(ids);
    }

    @Override
    public PageResult<ProcessDefinition> getProcessDefinitionPage(BpmProcessDefinitionPageReqVO pageVO) {
        ProcessDefinitionQuery query = repositoryService.createProcessDefinitionQuery();
        query.processDefinitionTenantId(FlowableUtils.getTenantId());
        if (StrUtil.isNotBlank(pageVO.getKey())) {
            query.processDefinitionKey(pageVO.getKey());
        }
        // 执行查询
        long count = query.count();
        if (count == 0) {
            return PageResult.empty(count);
        }
        List<ProcessDefinition> list = query.orderByProcessDefinitionVersion().desc()
                .listPage(PageUtils.getStart(pageVO), pageVO.getPageSize());
        return new PageResult<>(list, count);
    }

    @Override
    public List<ProcessDefinition> getProcessDefinitionListBySuspensionState(Integer suspensionState) {
        // 拼接查询条件
        ProcessDefinitionQuery query = repositoryService.createProcessDefinitionQuery();
        if (Objects.equals(SuspensionState.SUSPENDED.getStateCode(), suspensionState)) {
            query.suspended();
        } else if (Objects.equals(SuspensionState.ACTIVE.getStateCode(), suspensionState)) {
            query.active();
        }
        // 执行查询
        query.processDefinitionTenantId(FlowableUtils.getTenantId());
        return query.list();
    }

}
