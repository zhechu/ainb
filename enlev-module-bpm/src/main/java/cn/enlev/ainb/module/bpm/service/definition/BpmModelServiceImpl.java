package cn.enlev.ainb.module.bpm.service.definition;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.common.util.validation.ValidationUtils;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelSaveReqVO;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.simple.BpmSimpleModelNodeVO;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.simple.BpmSimpleModelUpdateReqVO;
import cn.enlev.ainb.module.bpm.convert.definition.BpmModelConvert;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmFormDO;
import cn.enlev.ainb.module.bpm.enums.definition.BpmModelFormTypeEnum;
import cn.enlev.ainb.module.bpm.enums.definition.BpmModelTypeEnum;
import cn.enlev.ainb.module.bpm.enums.task.BpmReasonEnum;
import cn.enlev.ainb.module.bpm.framework.flowable.core.candidate.BpmTaskCandidateInvoker;
import cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmTaskCandidateStrategyEnum;
import cn.enlev.ainb.module.bpm.framework.flowable.core.util.BpmnModelUtils;
import cn.enlev.ainb.module.bpm.framework.flowable.core.util.FlowableUtils;
import cn.enlev.ainb.module.bpm.framework.flowable.core.util.SimpleModelUtils;
import cn.enlev.ainb.module.bpm.service.task.BpmProcessInstanceCopyService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.*;
import org.flowable.common.engine.impl.db.SuspensionState;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ModelQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.*;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.enlev.ainb.module.bpm.enums.ErrorCodeConstants.*;
import static cn.enlev.ainb.module.bpm.framework.flowable.core.util.BpmnModelUtils.parseCandidateStrategy;

/**
 * 流程模型实现：主要进行 Flowable {@link Model} 的维护
 *
 * @author yunlongn
 * @author 芋道源码
 * @author jason
 */
@Service
@Validated
@Slf4j
public class BpmModelServiceImpl implements BpmModelService {

    @Resource
    private RepositoryService repositoryService;
    @Resource
    private BpmProcessDefinitionService processDefinitionService;
    @Resource
    private cn.enlev.ainb.framework.common.biz.system.permission.ProcessResourceCommonApi processResourceApi;
    @Resource
    private cn.enlev.ainb.module.bpm.framework.permission.BpmProcessPermissionChecker processPermissionChecker;
    @Resource
    private BpmFormService bpmFormService;
    @Resource
    private BpmOnlineFormValidator bpmOnlineFormValidator;

    @Resource
    private BpmTaskCandidateInvoker taskCandidateInvoker;

    @Resource
    private HistoryService historyService;
    @Resource
    private RuntimeService runtimeService;
    @Resource
    private TaskService taskService;
    @Resource
    private BpmProcessInstanceCopyService processInstanceCopyService;

    @Override
    public List<Model> getModelList(String name) {
        ModelQuery modelQuery = repositoryService.createModelQuery();
        if (StrUtil.isNotEmpty(name)) {
            modelQuery.modelNameLike("%" + name + "%");
        }
        modelQuery.modelTenantId(FlowableUtils.getTenantId());
        return modelQuery.list();
    }

    @Override
    public Long getModelCountByCategory(String category) {
        return repositoryService.createModelQuery()
                .modelCategory(category)
                .modelTenantId(FlowableUtils.getTenantId())
                .count();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createModel(@Valid BpmModelSaveReqVO createReqVO) {
        if (!ValidationUtils.isXmlNCName(createReqVO.getKey())) {
            throw exception(MODEL_KEY_VALID);
        }
        // 1. 校验流程标识已经存在
        Model keyModel = getModelByKey(createReqVO.getKey());
        if (keyModel != null) {
            throw exception(MODEL_KEY_EXISTS, createReqVO.getKey());
        }

        // 2. 创建 Model 对象
        createReqVO.setSort(System.currentTimeMillis()); // 使用当前时间，作为排序
        Model model = repositoryService.newModel();
        BpmModelConvert.INSTANCE.copyToModel(model, createReqVO);
        model.setTenantId(FlowableUtils.getTenantId());

        // 3. 保存模型
        saveModel(model, createReqVO);
        return model.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String importModel(@Valid BpmModelSaveReqVO reqVO) {
        if (!ValidationUtils.isXmlNCName(reqVO.getKey())) {
            throw exception(MODEL_KEY_VALID);
        }
        // 1. 校验流程标识已经存在
        Model keyModel = getModelByKey(reqVO.getKey());
        if (keyModel != null) {
            throw exception(MODEL_KEY_EXISTS, reqVO.getKey());
        }

        // 2. 创建 Model 对象，并归属到当前租户
        reqVO.setSort(System.currentTimeMillis());
        Model model = repositoryService.newModel();
        BpmModelConvert.INSTANCE.copyToModel(model, reqVO);
        model.setTenantId(FlowableUtils.getTenantId());

        // 3. 保存模型
        saveModel(model, reqVO);
        return model.getId();
    }

    @Override
    public BpmModelSaveReqVO exportModel(String id) {
        Model model = validateModelExists(id);
        BpmModelSaveReqVO result = BpmModelConvert.INSTANCE.convertToSaveReqVO(model);
        if (Objects.equals(result.getType(), BpmModelTypeEnum.BPMN.getType())) {
            byte[] bpmnBytes = getModelBpmnXML(id);
            result.setBpmnXml(ArrayUtil.isEmpty(bpmnBytes) ? null : StrUtil.utf8Str(bpmnBytes));
        } else if (Objects.equals(result.getType(), BpmModelTypeEnum.SIMPLE.getType())) {
            result.setSimpleModel(JsonUtils.parseObject(getModelSimpleJson(id), BpmSimpleModelNodeVO.class));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class) // 因为进行多个操作，所以开启事务
    public void updateModel(Long userId, BpmModelSaveReqVO updateReqVO) {
        // 1.1 校验流程模型存在 + 有设计权
        Model model = validateModelDesigner(updateReqVO.getId(), userId);
        // 1.2 只有设计权时，发起范围与管理员名单不许动 —— 它们是授权数据，见方法注释
        validateScopeWritable(model, updateReqVO, userId);

        // 2. 填充 Model 信息
        BpmModelConvert.INSTANCE.copyToModel(model, updateReqVO);

        // 3. 保存模型
        saveModel(model, updateReqVO);
    }

    /**
     * 保存模型的基本信息、流程图
     *
     * @param model 模型
     * @param saveReqVO 保存信息
     */
    private void saveModel(Model model, BpmModelSaveReqVO saveReqVO) {
        // 1. 保存模型的基础信息
        repositoryService.saveModel(model);

        // 2. 保存流程图
        if (ObjUtil.equals(BpmModelTypeEnum.BPMN.getType(), saveReqVO.getType())
                && StrUtil.isNotEmpty(saveReqVO.getBpmnXml())) {
            updateModelBpmnXml(model.getId(), saveReqVO.getBpmnXml());
        } else if (ObjUtil.equals(BpmModelTypeEnum.SIMPLE.getType(), saveReqVO.getType())
                && saveReqVO.getSimpleModel() != null) {
            // JSON 转换成 bpmnModel
            BpmnModel bpmnModel = SimpleModelUtils.buildBpmnModel(model.getKey(), model.getName(),
                    saveReqVO.getSimpleModel());
            // 保存 Bpmn XML
            updateModelBpmnXml(model.getId(), BpmnModelUtils.getBpmnXml(bpmnModel));
            // 保存 JSON 数据
            updateModelSimpleJson(model.getId(), saveReqVO.getSimpleModel());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateModelSortBatch(Long userId, List<String> ids) {
        // 1.1 校验流程模型存在
        List<Model> models = repositoryService.createModelQuery()
                .modelTenantId(FlowableUtils.getTenantId()).list();
        models.removeIf(model -> !ids.contains(model.getId()));
        if (ids.size() != models.size()) {
            throw exception(MODEL_NOT_EXISTS);
        }
        Map<String, Model> modelMap = convertMap(models, Model::getId);
        // 1.2 校验是否有设计权（排序是可回退操作，走宽档）
        ids.forEach(id -> validateModelDesigner(id, userId));

        // 保存排序
        long sort = System.currentTimeMillis(); // 使用时间戳 - i 作为排序
        for (int i = ids.size() - 1; i > 0; i--) {
            Model model = modelMap.get(ids.get(i));
            // 更新模型
            BpmModelMetaInfoVO metaInfo = BpmModelConvert.INSTANCE.parseMetaInfo(model).setSort(sort);
            model.setMetaInfo(JsonUtils.toJsonString(metaInfo));
            repositoryService.saveModel(model);
            // 更新排序
            processDefinitionService.updateProcessDefinitionSortByModelId(model.getId(), sort);
            sort--;
        }
    }

    private Model validateModelExists(String id) {
        Model model = repositoryService.createModelQuery()
                .modelId(id).modelTenantId(FlowableUtils.getTenantId()).singleResult();
        if (model == null) {
            throw exception(MODEL_NOT_EXISTS);
        }
        return model;
    }

    /**
     * 校验是否有流程模型的【管理】权限（只认 MANAGE）
     *
     * 供 {@link #deleteModel} / {@link #cleanModel} 使用 —— 这两个是不可逆的破坏性操作，
     * 刻意不给 DESIGN 放行，理由见 {@link #validateModelDesigner}
     *
     * @param id     流程模型编号
     * @param userId 用户编号
     * @return 流程模型
     */
    private Model validateModelManager(String id, Long userId) {
        return validateModelPermission(id, userId, false);
    }

    /**
     * 校验是否有流程模型的【设计】权限（MANAGE 或 DESIGN）
     *
     * 供 {@link #updateModel} / {@link #updateSimpleModel} / {@link #updateModelSortBatch} /
     * {@link #deployModel} / {@link #updateModelState} 使用，
     * 即「改流程图、部署、启停、排序」这一类可回退的操作
     *
     * <h3>🔴 为什么不能把 DESIGN 直接接进 {@link #validateModelManager}</h3>
     *
     * 七个写操作原本共用那一个校验方法。图省事的接法是在里面写成
     * {@code isManager(...) || canDesign(...)} —— 但那样一来，给某人配「设计」
     *（本意只是让他能改流程图）就顺带让他能删模型、能清掉全部流程数据。
     * 那是提权，不是细粒度。所以拆成两档：窄的那档继续守破坏性操作，
     * 宽的那档只守可回退操作。
     *
     * <h3>MANAGE 的能力一点没变</h3>
     *
     * {@code sys_grant} 里没有蕴含关系，一行 MANAGE 授权匹配不上 DESIGN 的查询，
     * 所以宽档必须显式写成 MANAGE || DESIGN，见
     * {@code BpmProcessPermissionChecker#canDesign} 的说明。
     *
     * <h3>⚠️ 宽档不等于「什么都能改」</h3>
     *
     * 发起范围与管理员名单是授权数据，只持有 DESIGN 的人改不了，
     * 否则他能给自己发 MANAGE —— 见 {@link #validateScopeWritable}
     *
     * @param id     流程模型编号
     * @param userId 用户编号
     * @return 流程模型
     */
    private Model validateModelDesigner(String id, Long userId) {
        return validateModelPermission(id, userId, true);
    }

    /**
     * 两档权限校验的公共实现
     *
     * @param id            流程模型编号
     * @param userId        用户编号
     * @param allowDesigner 是否放行只持有 DESIGN 的用户。{@code false} = 只认 MANAGE
     *                      （破坏性操作）；{@code true} = MANAGE 或 DESIGN 都算（可回退操作）
     * @return 流程模型
     */
    private Model validateModelPermission(String id, Long userId, boolean allowDesigner) {
        Model model = validateModelExists(id);
        if (hasManagerPower(userId, model)) {
            return model;
        }
        // 只有 DESIGN 的情况。⚠️ 资源行是部署成功之后才写的，所以从未部署的模型
        // 在这一档天然为空 —— 不是漏了回落，是那个窗口里根本没有可以授 DESIGN 的对象。
        // ⚠️ MANAGE 与 DESIGN 是两次独立查询（sys_grant 里没有蕴含关系），
        //    原因见 BpmProcessPermissionChecker#canDesign
        if (allowDesigner && StrUtil.isNotEmpty(model.getDeploymentId())
                && processPermissionChecker.canDesign(userId, model.getKey())) {
            return model;
        }
        throw exception(MODEL_UPDATE_FAIL_NOT_MANAGER, model.getName());
    }

    /**
     * 是否持有【管理】级权力
     *
     * <h3>🔴 为什么分「部署过」与「从未部署过」两条判据</h3>
     *
     * 常规判据是流程资源上的 MANAGE 授权（S7b / TODO #30 摘掉了 managerUserIds 回落）。
     * 但那条资源行（{@code bpm:process:<key>}）是在 {@link #deployModel} <b>成功之后</b>
     * 才由 {@code registerProcessResources} 写入的，于是模型从未部署过时：
     *
     * <pre>
     *   要部署 → 得先是管理员 → 得有资源行 → 得先部署成功
     * </pre>
     *
     * 这是个死锁，而且<b>超管也解不开</b>：{@code hasResourceAction} 直接调
     * {@code GrantDecisionService#hasPermission}，那个方法刻意不对超管短路
     *（短路只加在清单侧，见其 {@code isSuperAdmin} 的注释），
     * 资源行不存在时一律 {@code return false}。
     *
     * <p>实测（2026-09-22，超管 userId=1）：新建模型后 deploy 与 delete 双双报
     * {@code MODEL_UPDATE_FAIL_NOT_MANAGER}，模型变成既部署不了也删不掉的孤儿。
     * 这不是 AI 特有的问题 —— 任何人在流程设计器里新建流程都部署不了。
     *
     * <p>所以从未部署过时回落到 {@code managerUserIds}：这是该窗口内<b>唯一存在</b>的判据。
     * 它不等于「谁都能改」（原注释担心的情况）—— 判的仍是一份明确的管理员名单，
     * 只是这份名单此刻还没来得及投影进 {@code sys_grant}。
     * 模型一旦部署过，立刻回到资源判定，重构后的语义在其它任何地方都不变。
     *
     * <h3>为什么抽成方法</h3>
     *
     * 有两个调用方，且两处口径<b>必须一致</b>：{@link #validateModelPermission} 用它做宽档兜底，
     * {@link #validateScopeWritable} 用它判断能不能改授权投影字段。
     * 各写一份的后果见后者的注释
     */
    private boolean hasManagerPower(Long userId, Model model) {
        // 从未部署过：资源行还不存在，MANAGE 判定恒假，只能看 managerUserIds
        if (StrUtil.isEmpty(model.getDeploymentId())) {
            BpmModelMetaInfoVO metaInfo = BpmModelConvert.INSTANCE.parseMetaInfo(model);
            return metaInfo != null && CollUtil.contains(metaInfo.getManagerUserIds(), userId);
        }
        // 已部署过：走重构后的资源判定。
        // ⚠️ 用 model.getKey() 而不是 definitionId —— 资源标识本来就是按 key 取的
        return processPermissionChecker.isManager(userId, model.getKey());
    }

    /**
     * 校验「只有管理员能改」的那几个字段，没被只持有 DESIGN 的人动过
     *
     * <h3>🔴 为什么需要这一道</h3>
     *
     * {@code startUserIds} / {@code startDeptIds} / {@code managerUserIds} 看着是模型元数据，
     * 实际是<b>授权数据</b>：{@link #deployModel} 会经 {@code registerProcessResources} →
     * {@code syncProcessAuthorization} 把它们投影成 {@code sys_grant} 里的 START / MANAGE 行。
     * 于是「设计权能改这三个字段」等价于「设计权能给自己发管理员」：
     *
     * <pre>
     *   updateModel(managerUserIds=[自己]) → deployModel → 自己拿到 MANAGE 行
     *   → deleteModel / cleanModel 全部放行
     * </pre>
     *
     * 而且不必自己动手部署 —— 名单埋进 metaInfo 就行，等真正的管理员下次部署照样投影生效。
     * 两档拆分的全部意义会在这一步被抹平，所以必须挡住。
     *
     * <p>⚠️ 不能改成「悄悄保留库里的旧值」：{@code managerUserIds} 在
     * {@link BpmModelMetaInfoVO} 上是 {@code @NotEmpty} 必填，前端每次保存都会带上它。
     * 静默丢弃的结果是界面显示已保存、实际没保存，比报错更糟
     *
     * <p>按集合比而不是按列表比：这三个字段是「主体集合」，前端重新渲染可能换顺序，
     * 顺序变化不该被当成越权
     */
    private void validateScopeWritable(Model model, BpmModelSaveReqVO reqVO, Long userId) {
        if (hasManagerPower(userId, model)) {
            return;
        }
        BpmModelMetaInfoVO old = BpmModelConvert.INSTANCE.parseMetaInfo(model);
        if (!sameIdSet(old == null ? null : old.getStartUserIds(), reqVO.getStartUserIds())
                || !sameIdSet(old == null ? null : old.getStartDeptIds(), reqVO.getStartDeptIds())
                || !sameIdSet(old == null ? null : old.getManagerUserIds(), reqVO.getManagerUserIds())) {
            throw exception(MODEL_UPDATE_FAIL_NOT_MANAGER_FOR_SCOPE, model.getName());
        }
    }

    private boolean sameIdSet(List<Long> expected, List<Long> actual) {
        return new HashSet<>(CollUtil.emptyIfNull(expected)).equals(new HashSet<>(CollUtil.emptyIfNull(actual)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class) // 因为进行多个操作，所以开启事务
    public void deployModel(Long userId, String id) {
        // 1.1 校验流程模型存在 + 有设计权
        //
        // 部署放在宽档是安全的，两个前提缺一不可：
        //   ① 部署投影的是【库里存的】metaInfo，而只持有 DESIGN 的人改不了那三个授权字段
        //     （见 validateScopeWritable），所以投影不出他没被授过的权；
        //   ② DESIGN 判定要求 deploymentId 非空，即【首次】部署永远只能由
        //     managerUserIds 里的人做 —— 资源行本来就是那次部署才写出来的
        Model model = validateModelDesigner(id, userId);
        BpmModelMetaInfoVO metaInfo = BpmModelConvert.INSTANCE.parseMetaInfo(model);
        // 1.2 校验流程图
        byte[] bpmnBytes = getModelBpmnXML(model.getId());
        validateBpmnXml(bpmnBytes, metaInfo.getType());
        // 1.3 校验表单已配
        BpmFormDO form = validateFormConfig(metaInfo);
        // 1.4 校验任务分配规则已配置
        taskCandidateInvoker.validateBpmnConfig(bpmnBytes);
        // 1.5 获取仿钉钉流程设计器模型数据
        String simpleJson = getModelSimpleJson(model.getId());
        // 1.6 校验节点独立表单可用（闸口 ③：覆盖「模型保存后、部署前表结构被改」的时间窗口）
        if (Objects.equals(metaInfo.getFormType(), BpmModelFormTypeEnum.ONLINE.getType())
                && StrUtil.isNotEmpty(simpleJson)) {
            bpmOnlineFormValidator.validateNodeForms(
                    JsonUtils.parseObject(simpleJson, BpmSimpleModelNodeVO.class));
        }

        // 2.1 创建流程定义
        String definitionId = processDefinitionService.createProcessDefinition(model, metaInfo, bpmnBytes, simpleJson,
                form);

        // 2.2 将老的流程定义进行挂起。也就是说，只有最新部署的流程定义，才可以发起任务。
        updateProcessDefinitionSuspended(model.getDeploymentId());

        // 2.3 更新 model 的 deploymentId，进行关联
        ProcessDefinition definition = processDefinitionService.getProcessDefinition(definitionId);
        model.setDeploymentId(definition.getDeploymentId());
        repositoryService.saveModel(model);

        // 2.4 登记流程与审批节点资源（S6d 收尾）。
        //
        // 不登记的话，字段模式求值里的 contextId 解析为 null → 退回全局判定，
        // 于是在流程设计器里给某个节点配的字段权限「配了等于没配」，
        // 且不报错、界面正常 —— S6d 修的正是这个缺陷，而它的根就在这里。
        //
        // ⚠️ 失败不阻断部署：流程已经部署成功了，资源没登上只是节点级权限暂时不可配，
        // 重新发布一次即可补上。让部署因为登记失败而回滚是把小事放大成故障
        try {
            registerProcessResources(definition, bpmnBytes, metaInfo);
        } catch (Exception e) {
            log.error("[deployModel][流程({})的权限资源登记失败，节点级权限暂不可配，"
                    + "重新发布一次即可补上]", definition.getKey(), e);
        }
    }

    /**
     * 把流程与它的审批节点登记成可授权资源
     *
     * ⚠️ 用 {@code definition.getKey()} 而不是 definitionId —— 后者每次部署都变，
     * 拿它当资源标识的话，流程一改一部署，之前配的授权就全指向了没人用的旧版本，
     * 界面上权限还在、实际已失效
     */
    private void registerProcessResources(ProcessDefinition definition, byte[] bpmnBytes,
                                          BpmModelMetaInfoVO metaInfo) {
        BpmnModel bpmnModel = BpmnModelUtils.getBpmnModel(bpmnBytes);
        List<cn.enlev.ainb.framework.common.biz.system.permission.ProcessResourceCommonApi.NodeDescriptor>
                nodes = new java.util.ArrayList<>();
        if (bpmnModel != null && CollUtil.isNotEmpty(bpmnModel.getProcesses())) {
            for (org.flowable.bpmn.model.FlowElement element :
                    bpmnModel.getProcesses().get(0).getFlowElements()) {
                if (element instanceof org.flowable.bpmn.model.UserTask userTask) {
                    nodes.add(new cn.enlev.ainb.framework.common.biz.system.permission
                            .ProcessResourceCommonApi.NodeDescriptor(
                            userTask.getId(), userTask.getName()));
                }
            }
        }
        processResourceApi.registerProcess(definition.getKey(), definition.getName(),
                definition.getCategory(), nodes);

        // 🔴 再把三个 ID 列表投影成授权（S7b / TODO #30）
        //
        // 这一步是「摘掉判定处那条 OR」的前提。S5d 只做了一次性迁移脚本，
        // 判定处写成「授权 OR 旧列表」—— 那条 OR 摘不掉，因为前端仍在写这三个字段，
        // 摘了之后管理员在设计器里配的发起范围就再也不生效了，而界面上一切正常。
        //
        // 改成每次部署都同步之后：三个字段退化成「编辑界面」，
        // sys_grant 是唯一判据，前端一行不用改
        processResourceApi.syncProcessAuthorization(definition.getKey(),
                metaInfo != null ? metaInfo.getStartUserIds() : null,
                metaInfo != null ? metaInfo.getStartDeptIds() : null,
                metaInfo != null ? metaInfo.getManagerUserIds() : null);
    }

    private void validateBpmnXml(byte[] bpmnBytes, Integer type) {
        BpmnModel bpmnModel = BpmnModelUtils.getBpmnModel(bpmnBytes);
        if (bpmnModel == null) {
            throw exception(MODEL_NOT_EXISTS);
        }
        // 1. 没有 StartEvent
        StartEvent startEvent = BpmnModelUtils.getStartEvent(bpmnModel);
        if (startEvent == null) {
            throw exception(MODEL_DEPLOY_FAIL_BPMN_START_EVENT_NOT_EXISTS);
        }
        // 2. 校验 UserTask 的 name 都配置了
        List<UserTask> userTasks = BpmnModelUtils.getBpmnModelElements(bpmnModel, UserTask.class);
        userTasks.forEach(userTask -> {
            if (StrUtil.isEmpty(userTask.getName())) {
                throw exception(MODEL_DEPLOY_FAIL_BPMN_USER_TASK_NAME_NOT_EXISTS, userTask.getId());
            }
        });
        // 3. 校验第一个用户任务节点的规则类型是否为“审批人自选”，BPMN 设计器，校验第一个用户任务节点，SIMPLE 设计器，第一个节点固定为发起人所以校验第二个用户任务节点
        UserTask firUserTask = CollUtil.get(userTasks, BpmModelTypeEnum.BPMN.getType().equals(type) ? 0 : 1);
        if (firUserTask == null) {
            return;
        }
        Integer candidateStrategy = parseCandidateStrategy(firUserTask);
        if (Objects.equals(candidateStrategy, BpmTaskCandidateStrategyEnum.APPROVE_USER_SELECT.getStrategy())) {
            throw exception(MODEL_DEPLOY_FAIL_FIRST_USER_TASK_CANDIDATE_STRATEGY_ERROR, firUserTask.getName());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(Long userId, String id) {
        // 校验流程模型存在
        Model model = validateModelManager(id, userId);

        // 执行删除
        repositoryService.deleteModel(id);
        // 禁用流程定义
        updateProcessDefinitionSuspended(model.getDeploymentId());
    }

    @Override
    public void cleanModel(Long userId, String id) {
        // 1. 校验流程模型存在
        Model model = validateModelManager(id, userId);

        // 2. 清理所有流程数据
        // 2.1 先取消所有正在运行的流程
        List<ProcessInstance> processInstances = runtimeService.createProcessInstanceQuery()
                .processDefinitionKey(model.getKey()).list();
        processInstances.forEach(processInstance -> {
            runtimeService.deleteProcessInstance(processInstance.getId(),
                    BpmReasonEnum.CANCEL_BY_SYSTEM.getReason());
            historyService.deleteHistoricProcessInstance(processInstance.getId());
            processInstanceCopyService.deleteProcessInstanceCopy(processInstance.getId());
        });
        // 2.2 再从历史中删除所有相关的流程数据
        List<HistoricProcessInstance> historicProcessInstances = historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey(model.getKey()).list();
        historicProcessInstances.forEach(historicProcessInstance -> {
            historyService.deleteHistoricProcessInstance(historicProcessInstance.getId());
            processInstanceCopyService.deleteProcessInstanceCopy(historicProcessInstance.getId());
        });
        // 2.3 清理所有 Task
        List<Task> tasks = taskService.createTaskQuery()
                .processDefinitionKey(model.getKey()).list();
        tasks.forEach(task -> taskService.deleteTask(task.getId(),BpmReasonEnum.CANCEL_BY_PROCESS_CLEAN.getReason()));
    }

    @Override
    public void updateModelState(Long userId, String id, Integer state) {
        // 1.1 校验流程模型存在 + 有设计权（启停可回退，不需要管理员）
        Model model = validateModelDesigner(id, userId);
        // 1.2 校验流程定义存在
        ProcessDefinition definition = processDefinitionService
                .getProcessDefinitionByDeploymentId(model.getDeploymentId());
        if (definition == null) {
            throw exception(PROCESS_DEFINITION_NOT_EXISTS);
        }

        // 2. 更新状态
        processDefinitionService.updateProcessDefinitionState(definition.getId(), state);
    }

    @Override
    public BpmnModel getBpmnModelByDefinitionId(String processDefinitionId) {
        return repositoryService.getBpmnModel(processDefinitionId);
    }

    @Override
    public BpmSimpleModelNodeVO getSimpleModel(String modelId) {
        Model model = validateModelExists(modelId);
        // 通过 ACT_RE_MODEL 表 EDITOR_SOURCE_EXTRA_VALUE_ID_ ，获取仿钉钉快搭模型的 JSON 数据
        String json = getModelSimpleJson(model.getId());
        return JsonUtils.parseObject(json, BpmSimpleModelNodeVO.class);
    }

    @Override
    public void updateSimpleModel(Long userId, BpmSimpleModelUpdateReqVO reqVO) {
        // 1. 校验流程模型存在 + 有设计权。
        // 这个 VO 只带 id 与流程图 JSON，不含授权字段，所以不需要 validateScopeWritable
        Model model = validateModelDesigner(reqVO.getId(), userId);

        // 2.1 JSON 转换成 bpmnModel
        BpmnModel bpmnModel = SimpleModelUtils.buildBpmnModel(model.getKey(), model.getName(), reqVO.getSimpleModel());
        // 2.2 保存 Bpmn XML
        updateModelBpmnXml(model.getId(), BpmnModelUtils.getBpmnXml(bpmnModel));
        // 2.3 保存 JSON 数据
        updateModelSimpleJson(model.getId(), reqVO.getSimpleModel());
    }

    /**
     * 校验流程表单已配置
     *
     * @param metaInfo 流程模型元数据
     * @return 表单配置。仅 NORMAL 类型返回，用于部署时冗余表单快照；
     *         ONLINE 类型不返回（不冻结快照，始终取最新版本），CUSTOM 类型无表单
     */
    private BpmFormDO validateFormConfig(BpmModelMetaInfoVO metaInfo) {
        if (metaInfo == null || metaInfo.getFormType() == null) {
            throw exception(MODEL_DEPLOY_FAIL_FORM_NOT_CONFIG);
        }
        // 情况一：流程表单，校验表单存在
        if (Objects.equals(metaInfo.getFormType(), BpmModelFormTypeEnum.NORMAL.getType())) {
            if (metaInfo.getFormId() == null) {
                throw exception(MODEL_DEPLOY_FAIL_FORM_NOT_CONFIG);
            }
            BpmFormDO form = bpmFormService.getForm(metaInfo.getFormId());
            if (form == null) {
                throw exception(FORM_NOT_EXISTS);
            }
            return form;
        }
        // 情况二：在线表单，校验表单可用于流程（闸口 ②：模型保存 / 部署时的服务端校验，防止绕过前端）
        if (Objects.equals(metaInfo.getFormType(), BpmModelFormTypeEnum.ONLINE.getType())) {
            if (StrUtil.isEmpty(metaInfo.getFormDesignCode())) {
                throw exception(ONLINE_FORM_NOT_CONFIG);
            }
            bpmOnlineFormValidator.validateMainForm(metaInfo.getFormDesignCode());
            return null;
        }
        // 情况三：业务表单，校验自定义路径
        if (StrUtil.isEmpty(metaInfo.getFormCustomCreatePath())
                || StrUtil.isEmpty(metaInfo.getFormCustomViewPath())) {
            throw exception(MODEL_DEPLOY_FAIL_FORM_NOT_CONFIG);
        }
        return null;
    }

    /**
     * 写入流程图 XML。刻意 private、刻意不放在 {@link BpmModelService} 接口上：
     * 入参里没有 userId，因此它【无法】做逐流程的管理/设计权校验，
     * 只能由已经校验过的调用方在内部使用（saveModel、updateSimpleModel）。
     *
     * ⚠️ 把它放回接口，等于给「只持有全局 bpm:model:update」的人开一条改写任意模型
     * 流程图的路径 —— 全局权限说的是「能改流程」，不是「能改哪一个流程」，
     * 后者只能在带 userId 的那一层逐个判。
     * 要新增写入路径，请加一个带 userId 的方法并在其中校验，不要把这个提上来。
     */
    private void updateModelBpmnXml(String id, String bpmnXml) {
        if (StrUtil.isEmpty(bpmnXml)) {
            return;
        }
        repositoryService.addModelEditorSource(id, StrUtil.utf8Bytes(bpmnXml));
    }

    @SuppressWarnings("JavaExistingMethodCanBeUsed")
    private String getModelSimpleJson(String id) {
        byte[] bytes = repositoryService.getModelEditorSourceExtra(id);
        if (ArrayUtil.isEmpty(bytes)) {
            return null;
        }
        return StrUtil.utf8Str(bytes);
    }

    private void updateModelSimpleJson(String id, BpmSimpleModelNodeVO node) {
        if (node == null) {
            return;
        }
        byte[] bytes = JsonUtils.toJsonByte(node);
        repositoryService.addModelEditorSourceExtra(id, bytes);
    }

    /**
     * 挂起 deploymentId 对应的流程定义
     * <p>
     * 注意：这里一个 deploymentId 只关联一个流程定义
     *
     * @param deploymentId 流程发布Id
     */
    private void updateProcessDefinitionSuspended(String deploymentId) {
        if (StrUtil.isEmpty(deploymentId)) {
            return;
        }
        ProcessDefinition oldDefinition = processDefinitionService.getProcessDefinitionByDeploymentId(deploymentId);
        if (oldDefinition == null) {
            return;
        }
        processDefinitionService.updateProcessDefinitionState(oldDefinition.getId(),
                SuspensionState.SUSPENDED.getStateCode());
    }

    @Override
    public Model getModelByKey(String key) {
        return repositoryService.createModelQuery()
                .modelTenantId(FlowableUtils.getTenantId())
                .modelKey(key).singleResult();
    }

    @Override
    public Model getModel(String id) {
        return repositoryService.createModelQuery()
                .modelId(id).modelTenantId(FlowableUtils.getTenantId()).singleResult();
    }

    @Override
    public byte[] getModelBpmnXML(String id) {
        return repositoryService.getModelEditorSource(id);
    }

}
