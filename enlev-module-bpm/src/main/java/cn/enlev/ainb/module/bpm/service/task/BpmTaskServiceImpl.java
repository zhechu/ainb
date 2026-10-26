package cn.enlev.ainb.module.bpm.service.task;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.*;
import cn.hutool.extra.spring.SpringUtil;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.collection.CollectionUtils;
import cn.enlev.ainb.framework.common.util.date.DateUtils;
import cn.enlev.ainb.framework.common.util.number.NumberUtils;
import cn.enlev.ainb.framework.common.util.object.ObjectUtils;
import cn.enlev.ainb.framework.common.util.object.PageUtils;
import cn.enlev.ainb.framework.datapermission.core.annotation.DataPermission;
import cn.enlev.ainb.framework.web.core.util.WebFrameworkUtils;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO;
import cn.enlev.ainb.module.bpm.controller.admin.task.vo.task.*;
import cn.enlev.ainb.module.bpm.convert.task.BpmTaskConvert;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmFormDO;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import cn.enlev.ainb.module.bpm.enums.definition.*;
import cn.enlev.ainb.module.bpm.enums.task.*;
import cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmTaskCandidateStrategyEnum;
import cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmnVariableConstants;
import cn.enlev.ainb.module.bpm.framework.flowable.core.util.BpmHttpRequestUtils;
import cn.enlev.ainb.module.bpm.framework.flowable.core.util.BpmnModelUtils;
import cn.enlev.ainb.module.bpm.framework.flowable.core.util.FlowableUtils;
import cn.enlev.ainb.module.bpm.service.comment.BpmCommentService;
import cn.enlev.ainb.module.bpm.service.definition.BpmFormService;
import cn.enlev.ainb.module.bpm.service.definition.BpmModelService;
import cn.enlev.ainb.module.bpm.service.definition.BpmProcessDefinitionService;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormResourceDescriptorDTO;
import cn.enlev.ainb.module.bpm.service.message.BpmMessageService;
import cn.enlev.ainb.module.bpm.service.message.dto.BpmMessageSendWhenTaskTimeoutReqDTO;
import cn.enlev.ainb.module.system.api.dept.DeptApi;
import cn.enlev.ainb.module.system.api.dept.dto.DeptRespDTO;
import cn.enlev.ainb.module.system.api.user.AdminUserApi;
import cn.enlev.ainb.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.*;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.runtime.ActivityInstance;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Attachment;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskInfo;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.flowable.task.service.impl.persistence.entity.TaskEntityImpl;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.stream.Stream;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.*;
import static cn.enlev.ainb.module.bpm.enums.ErrorCodeConstants.*;
import static cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmnModelConstants.START_USER_NODE_ID;
import static cn.enlev.ainb.module.bpm.framework.flowable.core.util.BpmnModelUtils.*;

/**
 * 流程任务实例 Service 实现类
 *
 * @author 芋道源码
 * @author jason
 */
@Slf4j
@Service
public class BpmTaskServiceImpl implements BpmTaskService {

    /**
     * 子表明细字段在字段权限里的层级分隔符
     *
     * 明细列的权限 key 写成 {@code 子表控件field.明细字段}，如 {@code contacts.remark}。
     * ⚠️ 与前端 parseFormFields 递归解析出的 key 规则必须一致，两边对不上会导致
     * 配了权限却不生效（或反过来把有权限的字段裁掉）。
     *
     * 这里<b>不再是独立副本</b>：值引用 infra 的 {@link FormResourceDescriptorDTO#SUB_FIELD_SEPARATOR}，
     * 也就是 Java 侧唯一那份；真正的事实源仍是控件契约 JSON 的 structural.subFieldSeparator，
     * 由 SubFieldSeparatorSingleSourceTest 把这份 Java 常量钉到 JSON 上。
     *
     * 台账 #101(b) 之前这里是一个孤立的 "." 字面量：没有测试比对它与契约 JSON，
     * 当时写的理由「跨模块不互相依赖内部实现」经核查是假的 —— 本模块 pom 依赖 infra，
     * 而且下面两行本来就 import 了 infra 同一个 api 包里的两个类；被引的常量也确实在 api 包里。
     *
     * ⚠️ 编译期常量会被内联进本模块的 class 文件（JLS 15.28）。只重装 infra 的 jar 而
     * 不重装 bpm，两侧就会静默漂移，且单测看不见 —— 这正是 FormDesignApi#getSubFieldSeparator()
     * 这个运行时出口必须保留的原因。改完值要全量 mvn install。
     */
    // 公开是为了让 BpmModelApi 的流程规则能把它报出去，供诊断工具与表单契约里的
    // structural.subFieldSeparator 做比对 —— 不公开的话，诊断工具就只能再抄一份字面量，
    // 而「副本又多了一份」正是这个问题本身
    // 🔴 不许改回字面量：BpmSubFieldSeparatorReferenceTest 扫本模块 src/main 全部源码，
    // 只放过「引用 FormResourceDescriptorDTO」这一种形态。引用之后 assertEquals(两侧) 编译期恒真，
    // 拦不住「谁把它拆回去」的只有扫源码这一招（#98 的教训）
    public static final String SUB_FIELD_SEPARATOR = FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR;

    /**
     * 明细行里不受字段权限约束的键
     *
     * 主键是逐行 diff 的依据而非业务字段。被裁掉的话运行时会把每一行都当成新增，
     * 于是每次审批都把明细全删再全插，明细 ID 全部漂移，挂在明细上的附件等引用直接断掉。
     */
    private static final Set<String> SUB_ROW_IDENTITY_KEYS = Set.of("id");

    @Resource
    private TaskService taskService;
    @Resource
    private HistoryService historyService;
    @Resource
    private RuntimeService runtimeService;
    @Resource
    private ManagementService managementService;

    @Resource
    private BpmProcessInstanceService processInstanceService;
    @Resource
    private BpmProcessDefinitionService bpmProcessDefinitionService;
    @Resource
    private BpmOnlineFormDataService onlineFormDataService;
    @Resource
    private FormDesignApi formDesignApi;
    @Resource
    private BpmProcessInstanceCopyService processInstanceCopyService;
    @Resource
    @Lazy // 延迟加载，避免循环依赖
    private BpmCommentService commentService;
    @Resource
    private BpmModelService modelService;
    @Resource
    private BpmMessageService messageService;
    @Resource
    private BpmFormService formService;
    @Resource
    private BpmInstanceParticipantService instanceParticipantService;
    @Resource
    private cn.enlev.ainb.module.bpm.service.definition.BpmDelegationService delegationService;

    @Resource
    private AdminUserApi adminUserApi;
    @Resource
    private DeptApi deptApi;

    // ========== Query 查询相关方法 ==========

    @Override
    public PageResult<Task> getTaskTodoPage(Long userId, BpmTaskPageReqVO pageVO) {
        // 委托（晚绑定，S6b）：把「委托给我的人」的待办一并列出。
        //
        // ⚠️ 用 taskAssigneeIds 而不是在建委托时改写 assignee ——
        // 任务归属始终是委托人的，委托到期后这里自然不再并入，
        // 不需要任何「扫一遍改回来」的清理动作。委托【之前】就已存在的待办
        // 也因此天然可见，这正是晚绑定要解决的核心问题
        Set<Long> assigneeIds = new LinkedHashSet<>();
        assigneeIds.add(userId);
        assigneeIds.addAll(delegationService.getDelegatorUserIds(userId, null));
        TaskQuery taskQuery = taskService.createTaskQuery()
                .taskAssigneeIds(convertList(assigneeIds, String::valueOf)) // 自己 + 委托人
                .active()
                .includeProcessVariables()
                .taskTenantId(FlowableUtils.getTenantId())
                .orderByTaskCreateTime().desc(); // 创建时间倒序
        if (StrUtil.isNotBlank(pageVO.getName())) {
            taskQuery.taskNameLike("%" + pageVO.getName() + "%");
        }
        if (StrUtil.isNotEmpty(pageVO.getCategory())) {
            taskQuery.taskCategory(pageVO.getCategory());
        }
        if (StrUtil.isNotEmpty(pageVO.getProcessDefinitionKey())) {
            taskQuery.processDefinitionKey(pageVO.getProcessDefinitionKey());
        }
        if (ArrayUtil.isNotEmpty(pageVO.getCreateTime())) {
            taskQuery.taskCreatedAfter(DateUtils.of(pageVO.getCreateTime()[0]));
            taskQuery.taskCreatedBefore(DateUtils.of(pageVO.getCreateTime()[1]));
        }
        long count = taskQuery.count();
        if (count == 0) {
            return PageResult.empty();
        }
        List<Task> tasks = taskQuery.listPage(PageUtils.getStart(pageVO), pageVO.getPageSize());
        return new PageResult<>(tasks, count);
    }

    @Override
    public long getTaskPendingSignCount(Long userId) {
        return taskService.createTaskQuery()
                .taskOwner(String.valueOf(userId))
                .active()
                .taskTenantId(FlowableUtils.getTenantId())
                .count();
    }

    @Override
    public BpmTaskRespVO getTodoTask(Long userId, String taskId, String processInstanceId) {
        // 1.1 获取指定的用户待办任务
        Task todoTask = getMyTodoTask(userId, taskId);
        // 1.2 获取不到，则获取该流程实例下，第一个用户的待办任务
        if (todoTask == null) {
            todoTask = getMyFirstTodoTask(userId, processInstanceId);
        }
        if (todoTask == null) {
            return null;
        }

        // 2. 查询该任务的子任务
        List<Task> childrenTasks = getAllChildrenTaskListByParentTaskId(todoTask.getId(), CollUtil.newArrayList(todoTask));

        // 3. 转换返回
        BpmnModel bpmnModel = bpmProcessDefinitionService.getProcessDefinitionBpmnModel(todoTask.getProcessDefinitionId());
        Map<Integer, BpmTaskRespVO.OperationButtonSetting> buttonsSetting = BpmnModelUtils.parseButtonsSetting(
                bpmnModel, todoTask.getTaskDefinitionKey());
        Boolean signEnable = parseSignEnable(bpmnModel, todoTask.getTaskDefinitionKey());
        Boolean reasonRequire = parseReasonRequire(bpmnModel, todoTask.getTaskDefinitionKey());
        Integer nodeType = parseNodeType(BpmnModelUtils.getFlowElementById(bpmnModel, todoTask.getTaskDefinitionKey()));

        // 4. 任务表单
        BpmFormDO taskForm = null;
        if (StrUtil.isNotBlank(todoTask.getFormKey())) {
            taskForm = formService.getForm(NumberUtils.parseLong(todoTask.getFormKey()));
        }

        BpmTaskRespVO respVO = BpmTaskConvert.INSTANCE.buildTodoTask(todoTask, childrenTasks, buttonsSetting, taskForm)
                .setNodeType(nodeType).setSignEnable(signEnable).setReasonRequire(reasonRequire);
        // 5. 在线表单：本节点若绑定了独立表单，填入任务表单字段
        //    复用既有的「审批时补充信息表单」管道（前端 approveForm + 提交时并入 variables），
        //    不另建一套渲染与提交链路
        fillOnlineNodeForm(respVO, todoTask, bpmnModel);
        return respVO;
    }

    /**
     * 在线表单：把节点独立表单填入待办任务的表单字段
     *
     * 注意 formId 保持为空 —— 在线表单不来自 bpm_form，没有对应的数字编号。
     * 前端判断条件相应改为「formConf 非空」而非「formId 非空」。
     */
    private void fillOnlineNodeForm(BpmTaskRespVO respVO, Task todoTask, BpmnModel bpmnModel) {
        if (bpmnModel == null || respVO.getFormConf() != null) {
            return;
        }
        BpmProcessDefinitionInfoDO definitionInfo = bpmProcessDefinitionService
                .getProcessDefinitionInfo(todoTask.getProcessDefinitionId());
        if (!onlineFormDataService.isOnlineForm(definitionInfo)) {
            return;
        }
        String nodeFormCode = BpmnModelUtils.parseNodeFormCode(bpmnModel, todoTask.getTaskDefinitionKey());
        // 未配独立表单，或配的就是主表单：主表单已在详情页渲染，这里不重复
        if (StrUtil.isEmpty(nodeFormCode)
                || Objects.equals(nodeFormCode, definitionInfo.getFormDesignCode())) {
            return;
        }
        try {
            // S6d 收尾（TODO #35）：整段读取都在【节点上下文】里做。
            //
            // 里面有两件事，缺一不可：
            //   表单结构 getRuntimeFormDesign —— 决定「哪些字段显示、哪些只读」
            //   表单数据 getNodeFormData      —— 决定「显示出来的字段有没有值」
            //
            // 早先只把数据读取包进了上下文，结构取的是设计态那一份 ——
            // 于是节点上配的 HIDDEN 字段照样渲染（只是值空着）、READONLY 照样可编辑，
            // 用户填了、提交了，值被写路径的 stripNonWritable 丢掉，全程无提示。
            // 「节点字段权限」正是在这里才第一次有可见效果
            String nodeContext = buildNodeContextPermission(todoTask);
            FormDesignRespDTO nodeForm = cn.enlev.ainb.framework.common.biz.system.permission
                    .PermissionContextHolder.execute(nodeContext, () -> formDesignApi.getRuntimeFormDesign(nodeFormCode));
            if (nodeForm == null) {
                log.warn("[fillOnlineNodeForm][节点({}) 的在线表单({}) 不存在]",
                        todoTask.getTaskDefinitionKey(), nodeFormCode);
                return;
            }
            respVO.setFormConf(nodeForm.getFormConf());
            respVO.setFormFields(nodeForm.getFormFields());
            // ⚠️ 读路径漏接比写路径重：写路径漏接只是「该拦没拦住」，
            // 读路径漏接是「该藏的字段显示出来了」，属于泄露
            respVO.setFormVariables(cn.enlev.ainb.framework.common.biz.system.permission
                    .PermissionContextHolder.execute(nodeContext,
                            () -> onlineFormDataService.getNodeFormData(definitionInfo,
                                    todoTask.getProcessInstanceId(), null, nodeFormCode)));
        } catch (Exception ex) {
            log.error("[fillOnlineNodeForm][节点({}) 表单({}) 读取失败]",
                    todoTask.getTaskDefinitionKey(), nodeFormCode, ex);
        }
    }

    /**
     * 获得用户指定 taskId 任务编号的“待办”（未审批、且可审核）的任务
     *
     * @param userId 用户编号
     * @param taskId 任务编号
     * @return 任务
     */
    private Task getMyTodoTask(Long userId, String taskId) {
        if (StrUtil.isEmpty(taskId)) {
            return null;
        }
        Task task = getTask(taskId);
        if (task == null) {
            return null;
        }
        if (!isAssignUserTask(userId, task) && !isAddSignUserTask(userId, task)) {
            return null;
        }
        return task;
    }

    /**
     * 获得用户指定 processInstanceId 流程编号下的首个“待办”（未审批、且可审核）的任务
     *
     * @param userId            用户编号
     * @param processInstanceId 流程编号
     * @return 任务
     */
    private Task getMyFirstTodoTask(Long userId, String processInstanceId) {
        if (processInstanceId == null) {
            return null;
        }
        // 1. 查询所有任务
        List<Task> tasks = taskService.createTaskQuery()
                .active()
                .processInstanceId(processInstanceId)
                .includeTaskLocalVariables()
                .includeProcessVariables()
                .orderByTaskCreateTime().asc() // 按创建时间升序
                .list();

        // 2. 查询我的首个任务
        return CollUtil.findOne(tasks, task -> {
            return isAssignUserTask(userId, task) // 当前用户为审批人
                    || isAddSignUserTask(userId, task); // 当前用户为加签人（为了减签）
        });
    }

    @Override
    public PageResult<HistoricTaskInstance> getTaskDonePage(Long userId, BpmTaskPageReqVO pageVO) {
        HistoricTaskInstanceQuery taskQuery = historyService.createHistoricTaskInstanceQuery()
                .finished() // 已完成
                .taskAssignee(String.valueOf(userId)) // 分配给自己
                .includeTaskLocalVariables()
                .orderByHistoricTaskInstanceEndTime().desc(); // 审批时间倒序
        if (StrUtil.isNotBlank(pageVO.getName())) {
            taskQuery.taskNameLike("%" + pageVO.getName() + "%");
        }
        if (pageVO.getStatus() != null) {
            taskQuery.taskVariableValueEquals(BpmnVariableConstants.TASK_VARIABLE_STATUS, pageVO.getStatus());
        }
//        if (ArrayUtil.isNotEmpty(pageVO.getCreateTime())) {
//            taskQuery.taskCreatedAfter(DateUtils.of(pageVO.getCreateTime()[0]));
//            taskQuery.taskCreatedBefore(DateUtils.of(pageVO.getCreateTime()[1]));
//        }
        // 执行查询
        long count = taskQuery.count();
        if (count == 0) {
            return PageResult.empty();
        }
        List<HistoricTaskInstance> tasks = taskQuery.listPage(PageUtils.getStart(pageVO), pageVO.getPageSize());

        // 特殊：强制移除自动完成的“发起人”节点
        // 补充说明：由于 taskQuery 无法方面的过滤，所以暂时通过内存过滤
        tasks.removeIf(task -> task.getTaskDefinitionKey().equals(START_USER_NODE_ID));
        // TODO @芋艿：https://t.zsxq.com/MNzqp 【flowable bug】：taskCreatedAfter、taskCreatedBefore 拼接的是 OR
        if (ArrayUtil.isNotEmpty(pageVO.getCreateTime())) {
            tasks.removeIf(task -> task.getCreateTime() == null
                    || task.getCreateTime().before(DateUtils.of(pageVO.getCreateTime()[0]))
                    || task.getCreateTime().after(DateUtils.of(pageVO.getCreateTime()[1])));
        }
        return new PageResult<>(tasks, count);
    }

    @Override
    public PageResult<HistoricTaskInstance> getTaskPage(Long userId, BpmTaskPageReqVO pageVO) {
        HistoricTaskInstanceQuery taskQuery = historyService.createHistoricTaskInstanceQuery()
                .includeTaskLocalVariables()
                .taskTenantId(FlowableUtils.getTenantId())
                .orderByHistoricTaskInstanceEndTime().desc(); // 审批时间倒序
        if (StrUtil.isNotBlank(pageVO.getName())) {
            taskQuery.taskNameLike("%" + pageVO.getName() + "%");
        }
        if (StrUtil.isNotEmpty(pageVO.getCategory())) {
            taskQuery.taskCategory(pageVO.getCategory());
        }
//        if (ArrayUtil.isNotEmpty(pageVO.getCreateTime())) {
//            taskQuery.taskCreatedAfter(DateUtils.of(pageVO.getCreateTime()[0]));
//            taskQuery.taskCreatedBefore(DateUtils.of(pageVO.getCreateTime()[1]));
//        }
        // 执行查询
        long count = taskQuery.count();
        if (count == 0) {
            return PageResult.empty();
        }
        List<HistoricTaskInstance> tasks = taskQuery.listPage(PageUtils.getStart(pageVO), pageVO.getPageSize());
        // TODO @芋艿：https://t.zsxq.com/MNzqp 【flowable bug】：taskCreatedAfter、taskCreatedBefore 拼接的是 OR
        if (ArrayUtil.isNotEmpty(pageVO.getCreateTime())) {
            tasks.removeIf(task -> task.getCreateTime() == null
                    || task.getCreateTime().before(DateUtils.of(pageVO.getCreateTime()[0]))
                    || task.getCreateTime().after(DateUtils.of(pageVO.getCreateTime()[1])));
        }
        return new PageResult<>(tasks, count);
    }

    @Override
    public List<Task> getTasksByProcessInstanceIds(List<String> processInstanceIds) {
        if (CollUtil.isEmpty(processInstanceIds)) {
            return Collections.emptyList();
        }
        return taskService.createTaskQuery().processInstanceIdIn(processInstanceIds).list();
    }

    @Override
    public List<HistoricTaskInstance> getTaskListByProcessInstanceId(String processInstanceId, Boolean asc) {
        HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery()
                .includeTaskLocalVariables()
                .processInstanceId(processInstanceId);
        if (Boolean.TRUE.equals(asc)) {
            query.orderByHistoricTaskInstanceStartTime().asc();
        } else {
            query.orderByHistoricTaskInstanceStartTime().desc();
        }
        return query.list();
    }

    @Override
    public Task validateTask(Long userId, String taskId) {
        Task task = validateTaskExists(taskId);
        // 为什么判断 assignee 非空的情况下？
        // 例如说：在审批人为空时，我们会有“自动审批通过”的策略，此时 userId 为 null，允许通过
        if (StrUtil.isBlank(task.getAssignee())) {
            return task;
        }
        Long assigneeId = NumberUtils.parseLong(task.getAssignee());
        if (ObjectUtil.equal(userId, assigneeId)) {
            return task;
        }
        // 委托（晚绑定，S6b）：受托人可以办委托人的任务。
        //
        // ⚠️ 这里【必须】按流程 key 精确判定 —— 列表那头为了能一次查多个流程，
        // 对「限定了流程的委托」是放行的（见 BpmDelegationServiceImpl.matchesProcess）。
        // 真正的访问控制点在这里：列表可能多显示几条，但点进去办不了会被明确拒绝。
        // 反过来（列表少显示、点进去能办）才是难查的问题
        if (assigneeId != null
                && delegationService.isDelegatedBy(userId, assigneeId, parseProcessKeyOfTask(task))) {
            return task;
        }
        throw exception(TASK_OPERATE_FAIL_ASSIGN_NOT_SELF);
    }

    /**
     * 取任务所属流程的定义 key
     *
     * Flowable 的 definitionId 形如 {@code leave:3:uuid}，第一段就是 key。
     * 用 key 而不是 definitionId：definitionId 每次部署都变，
     * 拿它限定委托的话，流程一改一部署委托就失效了
     */
    private String parseProcessKeyOfTask(Task task) {
        String definitionId = task.getProcessDefinitionId();
        if (StrUtil.isBlank(definitionId)) {
            return null;
        }
        int idx = definitionId.indexOf(':');
        return idx > 0 ? definitionId.substring(0, idx) : definitionId;
    }

    @Override
    public Task validateTaskExists(String id) {
        Task task = getTask(id);
        if (task == null) {
            throw exception(TASK_NOT_EXISTS);
        }
        return task;
    }

    @Override
    public Task getTask(String id) {
        return taskService.createTaskQuery().taskId(id).includeTaskLocalVariables().singleResult();
    }

    @Override
    public HistoricTaskInstance getHistoricTask(String id) {
        return historyService.createHistoricTaskInstanceQuery().taskId(id).includeTaskLocalVariables().singleResult();
    }

    @Override
    public List<HistoricTaskInstance> getHistoricTasks(Collection<String> taskIds) {
        if (CollUtil.isEmpty(taskIds)) {
            return Collections.emptyList();
        }
        return historyService.createHistoricTaskInstanceQuery().taskIds(taskIds).includeTaskLocalVariables().list();
    }

    @Override
    public List<Task> getRunningTaskListByProcessInstanceId(String processInstanceId, Boolean assigned, String defineKey) {
        Assert.notNull(processInstanceId, "processInstanceId 不能为空");
        TaskQuery taskQuery = taskService.createTaskQuery().processInstanceId(processInstanceId).active()
                .includeTaskLocalVariables();
        if (BooleanUtil.isTrue(assigned)) {
            taskQuery.taskAssigned();
        } else if (BooleanUtil.isFalse(assigned)) {
            taskQuery.taskUnassigned();
        }
        if (StrUtil.isNotEmpty(defineKey)) {
            taskQuery.taskDefinitionKey(defineKey);
        }
        return taskQuery.list();
    }

    @Override
    public List<UserTask> getUserTaskListByReturn(String id) {
        // 1.1 校验当前任务 task 存在
        Task task = validateTaskExists(id);
        // 1.2 根据流程定义获取流程模型信息
        BpmnModel bpmnModel = modelService.getBpmnModelByDefinitionId(task.getProcessDefinitionId());
        FlowElement source = BpmnModelUtils.getFlowElementById(bpmnModel, task.getTaskDefinitionKey());
        if (source == null) {
            throw exception(TASK_NOT_EXISTS);
        }

        // 2.1 查询该任务的前置任务节点的 key 集合
        List<UserTask> previousUserList = BpmnModelUtils.getPreviousUserTaskList(source, null, null);
        if (CollUtil.isEmpty(previousUserList)) {
            return Collections.emptyList();
        }
        // 2.2 过滤：只有串行可到达的节点，才可以退回。类似非串行、子流程无法退回
        previousUserList.removeIf(userTask -> !BpmnModelUtils.isSequentialReachable(source, userTask, null));

        // 2.3 过滤：只能退回到已经处理过的节点（排除审批未经过的节点）。相关 issue：https://github.com/YunaiV/ruoyi-vue-pro/issues/982
        List<HistoricTaskInstance> finishedTasks = getFinishedTaskListByProcessInstanceIdWithoutCancel(task.getProcessInstanceId());
        Set<String> finishedTaskDefinitionKeys = convertSet(finishedTasks, HistoricTaskInstance::getTaskDefinitionKey);
        previousUserList.removeIf(userTask -> !finishedTaskDefinitionKeys.contains(userTask.getId()));
        return previousUserList;
    }

    @Override
    public <T extends TaskInfo> List<T> getAllChildrenTaskListByParentTaskId(String parentTaskId, List<T> tasks) {
        if (CollUtil.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        Map<String, List<T>> parentTaskMap = convertMultiMap(
                filterList(tasks, task -> StrUtil.isNotEmpty(task.getParentTaskId())), TaskInfo::getParentTaskId);
        if (CollUtil.isEmpty(parentTaskMap)) {
            return Collections.emptyList();
        }

        List<T> result = new ArrayList<>();
        // 1. 递归获取子级
        Stack<String> stack = new Stack<>();
        stack.push(parentTaskId);
        // 2. 递归遍历
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            if (stack.isEmpty()) {
                break;
            }
            // 2.1 获取子任务们
            String taskId = stack.pop();
            List<T> childTaskList = filterList(tasks, task -> StrUtil.equals(task.getParentTaskId(), taskId));
            // 2.2 如果非空，则添加到 stack 进一步递归
            if (CollUtil.isNotEmpty(childTaskList)) {
                stack.addAll(convertList(childTaskList, TaskInfo::getId));
                result.addAll(childTaskList);
            }
        }
        return result;
    }

    /**
     * 获得所有子任务列表
     *
     * @param parentTask 父任务
     * @return 所有子任务列表
     */
    private List<Task> getAllChildTaskList(Task parentTask) {
        List<Task> result = new ArrayList<>();
        // 1. 递归获取子级
        Stack<Task> stack = new Stack<>();
        stack.push(parentTask);
        // 2. 递归遍历
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            if (stack.isEmpty()) {
                break;
            }
            // 2.1 获取子任务们
            Task task = stack.pop();
            List<Task> childTaskList = getTaskListByParentTaskId(task.getId());
            // 2.2 如果非空，则添加到 stack 进一步递归
            if (CollUtil.isNotEmpty(childTaskList)) {
                stack.addAll(childTaskList);
                result.addAll(childTaskList);
            }
        }
        return result;
    }

    @Override
    public List<Task> getTaskListByParentTaskId(String parentTaskId) {
        String tableName = managementService.getTableName(TaskEntity.class);
        // taskService.createTaskQuery() 没有 parentId 参数，所以写 sql 查询
        String sql = "select ID_,NAME_,OWNER_,ASSIGNEE_ from " + tableName + " where PARENT_TASK_ID_=#{parentTaskId}";
        return taskService.createNativeTaskQuery().sql(sql).parameter("parentTaskId", parentTaskId).list();
    }

    /**
     * 获取子任务个数
     *
     * @param parentTaskId 父任务 ID
     * @return 剩余子任务个数
     */
    private Long getTaskCountByParentTaskId(String parentTaskId) {
        String tableName = managementService.getTableName(TaskEntity.class);
        String sql = "SELECT COUNT(1) from " + tableName + " WHERE PARENT_TASK_ID_=#{parentTaskId}";
        return taskService.createNativeTaskQuery().sql(sql).parameter("parentTaskId", parentTaskId).count();
    }

    /**
     * 获得任务根任务的父任务编号
     *
     * @param task 任务
     * @return 根任务的父任务编号
     */
    private String getTaskRootParentId(Task task) {
        if (task == null || task.getParentTaskId() == null) {
            return null;
        }
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            Task parentTask = getTask(task.getParentTaskId());
            if (parentTask == null) {
                return null;
            }
            if (parentTask.getParentTaskId() == null) {
                return parentTask.getId();
            }
            task = parentTask;
        }
        throw new IllegalArgumentException(String.format("Task(%s) 层级过深，无法获取父节点编号", task.getId()));
    }

    @Override
    public List<HistoricActivityInstance> getActivityListByProcessInstanceId(String processInstanceId) {
        return historyService.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId)
                .orderByHistoricActivityInstanceStartTime().asc().list();
    }

    @Override
    public List<HistoricActivityInstance> getHistoricActivityListByExecutionId(String executionId) {
        return historyService.createHistoricActivityInstanceQuery().executionId(executionId).list();
    }

    @Override
    public List<HistoricTaskInstance> getFinishedTaskListByProcessInstanceIdWithoutCancel(String processInstanceId) {
        return historyService.createHistoricTaskInstanceQuery()
                .finished()
                .includeTaskLocalVariables()
                .processInstanceId(processInstanceId)
                .taskVariableValueNotEquals(BpmnVariableConstants.TASK_VARIABLE_STATUS,
                        BpmTaskStatusEnum.CANCEL.getStatus())
                .orderByHistoricTaskInstanceStartTime().asc().list();
    }

    @Override
    public List<Attachment> getAttachments(String processInstanceId, Set<String> taskIds, BpmAttachmentTypeEnum attachmentType) {
        List<Attachment> result = taskService.getProcessInstanceAttachments(processInstanceId);
        if (CollUtil.isNotEmpty(taskIds)) {
            result = filterList(result, attachment -> taskIds.contains(attachment.getTaskId()));
        }
        if (attachmentType != null) {
            result = filterList(result, attachment -> attachmentType.getType().equals(attachment.getType()));
        }
        return result;
    }

    /**
     * 判断指定用户，是否是当前任务的审批人
     *
     * @param userId 用户编号
     * @param task   任务
     * @return 是否
     */
    private boolean isAssignUserTask(Long userId, Task task) {
        Long assignee = NumberUtil.parseLong(task.getAssignee(), null);
        return ObjectUtil.equals(userId, assignee);
    }

    /**
     * 判断指定用户，是否是当前任务的拥有人
     *
     * @param userId 用户编号
     * @param task   任务
     * @return 是否
     */
    private boolean isOwnerUserTask(Long userId, Task task) {
        Long assignee = NumberUtil.parseLong(task.getOwner(), null);
        return ObjectUtil.equal(userId, assignee);
    }

    /**
     * 判断指定用户，是否是当前任务的加签人
     *
     * @param userId 用户 Id
     * @param task   任务
     * @return 是否
     */
    private boolean isAddSignUserTask(Long userId, Task task) {
        return (isAssignUserTask(userId, task) || isOwnerUserTask(userId, task))
                && BpmTaskSignTypeEnum.of(task.getScopeType()) != null;
    }

    // ========== Update 写入相关方法 ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false) // 关闭数据权限，避免查询不到用户数据。相关案例：https://gitee.com/zhijiantianya/yudao-cloud/issues/ID1UYA
    public void approveTask(Long userId, @Valid BpmTaskApproveReqVO reqVO) {
        // 1.1 校验任务存在
        Task task = validateTask(userId, reqVO.getId());
        // S6d：整条审批链在【节点上下文】里执行。
        //
        // 审批会走到表单写入（submitProcessor → FormFieldCropper），
        // 而字段模式的三层合并需要知道「当前在哪个审批节点」。
        // 不设的话 contextId 恒为 null，节点级字段权限【配了等于没配】，
        // 且不报错、界面正常 —— 正是本项目最贵的那类缺陷。
        //
        // ⚠️ 用 ThreadLocal 而不是逐层传参：从这里到 FormFieldCropper 隔着四层、
        // 每层都有多个重载与分支，漏传一层就是一处静默失效
        cn.enlev.ainb.framework.common.biz.system.permission.PermissionContextHolder.execute(
                buildNodeContextPermission(task), () -> getSelf().approveTaskInContext(userId, reqVO, task));
    }

    /**
     * 节点上下文的权限标识
     *
     * 形如 {@code bpm:node:leave:Activity_xxx}。
     * ⚠️ 用流程 key 而不是 definitionId —— definitionId 每次部署都变，
     * 拿它当标识的话，流程一改一部署，节点上配的字段权限就全指向了旧版本
     */
    private String buildNodeContextPermission(Task task) {
        String definitionId = task.getProcessDefinitionId();
        if (StrUtil.isBlank(definitionId) || StrUtil.isBlank(task.getTaskDefinitionKey())) {
            return null;
        }
        int idx = definitionId.indexOf(':');
        String key = idx > 0 ? definitionId.substring(0, idx) : definitionId;
        return "bpm:node:" + key + ":" + task.getTaskDefinitionKey();
    }

    @Transactional(rollbackFor = Exception.class)
    public void approveTaskInContext(Long userId, BpmTaskApproveReqVO reqVO, Task task) {
        // 1.2 校验流程实例存在
        ProcessInstance instance = processInstanceService.getProcessInstance(task.getProcessInstanceId());
        if (instance == null) {
            throw exception(PROCESS_INSTANCE_NOT_EXISTS);
        }
        // 1.3 校验签名
        BpmnModel bpmnModel = modelService.getBpmnModelByDefinitionId(task.getProcessDefinitionId());
        Boolean signEnable = parseSignEnable(bpmnModel, task.getTaskDefinitionKey());
        if (signEnable && StrUtil.isEmpty(reqVO.getSignPicUrl())) {
            throw exception(TASK_SIGNATURE_NOT_EXISTS);
        }
        // 1.4 校验审批意见
        Boolean reasonRequire = parseReasonRequire(bpmnModel, task.getTaskDefinitionKey());
        if (reasonRequire && StrUtil.isEmpty(reqVO.getReason())) {
            throw exception(TASK_REASON_REQUIRE);
        }

        // 情况一：被委派的任务，不调用 complete 去完成任务
        if (DelegationState.PENDING.equals(task.getDelegationState())) {
            approveDelegateTask(reqVO, task);
            return;
        }

        // 情况二：审批有【后】加签的任务
        if (BpmTaskSignTypeEnum.AFTER.getType().equals(task.getScopeType())) {
            approveAfterSignTask(task, reqVO);
            return;
        }

        // 情况三：审批普通的任务。大多数情况下，都是这样
        // 2.1 更新 task 状态、原因、签字
        updateTaskStatusAndReason(task.getId(), BpmTaskStatusEnum.APPROVE.getStatus(), reqVO.getReason());
        if (signEnable) {
            taskService.setVariableLocal(task.getId(), BpmnVariableConstants.TASK_SIGN_PIC_URL, reqVO.getSignPicUrl());
        }
        // 2.2 添加评论
        commentService.createComment(task.getId(), task.getProcessInstanceId(), BpmCommentTypeEnum.APPROVE,
                reqVO.getReason());
        // 2.3 添加附件
        if (CollUtil.isNotEmpty(reqVO.getAttachments())) {
            reqVO.getAttachments().forEach(attachment -> taskService.createAttachment(BpmAttachmentTypeEnum.TASK_ATTACHMENT.getType(),
                    task.getId(), task.getProcessInstanceId(), FileUtil.getName(URLUtil.getPath(attachment)), null, attachment));
        }

        // 3. 设置流程变量。如果流程变量前端传空，需要从历史实例中获取，原因：前端表单如果在当前节点无可编辑的字段时 variables 一定会为空
        // 场景一：A 节点发起，B 节点表单无可编辑字段，审批通过时，C 节点需要流程变量获取下一个执行节点，但因为 B 节点无可编辑的字段，variables 为空，流程可能出现问题。
        // 场景二：A 节点发起，B 节点只有某一个字段可编辑（比如 day），但 C 节点需要多个节点。
        //       （比如 work + day 变量，在发起时填写，因为 B 节点只有 day 的编辑权限，在审批后，variables 会缺少 work 的值）
        Map<String, Object> processVariables = new HashMap<>();
        if (CollUtil.isNotEmpty(instance.getProcessVariables())) { // 获取历史中流程变量
            processVariables.putAll(instance.getProcessVariables());
        }
        if (CollUtil.isNotEmpty(reqVO.getVariables())) { // 合并前端传递的流程变量，以前端为准
            processVariables.putAll(reqVO.getVariables());
        }

        // 4. 校验并处理 APPROVE_USER_SELECT 当前审批人，选择下一节点审批人的逻辑
        validateAndSetNextAssignees(task.getTaskDefinitionKey(), processVariables,
                bpmnModel, reqVO.getNextAssignees(), instance);
        runtimeService.setVariables(task.getProcessInstanceId(), processVariables);

        // 4.5 在线表单：把本节点提交的数据写入业务表。与 complete 同事务，任一失败整体回滚
        saveOnlineFormData(task, instance, bpmnModel, reqVO.getVariables());

        // 5. 如果当前节点 Id 存在于需要预测的流程节点中，从中移除。 流程变量在回退操作中设置
        Object needSimulateTaskIds = runtimeService.getVariable(task.getProcessInstanceId(), BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_NEED_SIMULATE_TASK_IDS);
        Set<String> needSimulateTaskIdsByReturn = Convert.toSet(String.class, needSimulateTaskIds);
        if (needSimulateTaskIdsByReturn.contains(task.getTaskDefinitionKey())) {
            needSimulateTaskIdsByReturn.remove(task.getTaskDefinitionKey());
            runtimeService.setVariable(task.getProcessInstanceId(), BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_NEED_SIMULATE_TASK_IDS, needSimulateTaskIdsByReturn);
        }

        // 6. 清理退回设置的不自动通过的变量。仅在该标记存在时才删除，避免每次完成任务都产生无谓的 DB delete
        String returnFlagKey = String.format(BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_RETURN_FLAG, task.getTaskDefinitionKey());
        if (runtimeService.hasVariable(task.getProcessInstanceId(), returnFlagKey)) {
            log.info("[approveTask][taskId({}) 清理退回标记变量({})]", task.getId(), returnFlagKey);
            runtimeService.removeVariable(task.getProcessInstanceId(), returnFlagKey);
        }

        // 7. 调用 BPM complete 去完成任务
        Map<String,Object> taskVariables = MapUtil.emptyIfNull(reqVO.getVariables()); // task local variables. 一般用于任务内嵌流程表单
        taskService.complete(task.getId(), taskVariables, true);

        // 【加签专属】处理加签任务
        handleParentTaskIfSign(task.getParentTaskId());
    }

    /**
     * 在线表单：把节点提交的表单数据写入业务表
     *
     * 三件事：
     * 1. 判断本节点生效的表单 —— 节点配了独立表单用它，否则沿用流程主表单
     * 2. 按节点的字段权限做**白名单裁剪**，只保留「可编辑」的字段
     * 3. 委托 BpmOnlineFormDataService 落库
     *
     * 关于裁剪：按当前阶段的约定，字段权限的完整校验放在前端，后端只做白名单裁剪且不报错 ——
     * 越权字段被丢弃而非拒绝请求。这挡住了「改无权限字段」，但挡不住「有权限字段填入非法值」，
     * 后端的字段级校验是后续版本的补齐项。
     */
    private void saveOnlineFormData(Task task, ProcessInstance instance, BpmnModel bpmnModel,
                                    Map<String, Object> submitVariables) {
        if (CollUtil.isEmpty(submitVariables)) {
            return;
        }
        BpmProcessDefinitionInfoDO definitionInfo = bpmProcessDefinitionService
                .getProcessDefinitionInfo(task.getProcessDefinitionId());
        if (!onlineFormDataService.isOnlineForm(definitionInfo)) {
            return;
        }
        String businessKey = instance.getBusinessKey();
        if (StrUtil.isEmpty(businessKey)) {
            // 在线表单发起时一定会写入 businessKey；为空说明这条实例不是走在线表单发起的（如历史数据）
            log.warn("[saveOnlineFormData][流程实例({}) 是在线表单类型但没有 businessKey，跳过业务表写入]",
                    instance.getProcessInstanceId());
            return;
        }
        // 白名单裁剪：只保留本节点有编辑权限的字段
        Map<String, String> fieldsPermission = BpmnModelUtils.parseFormFieldsPermission(
                bpmnModel, task.getTaskDefinitionKey());
        Map<String, Object> writableData = filterWritableFields(submitVariables, fieldsPermission);
        if (CollUtil.isEmpty(writableData)) {
            return;
        }
        String nodeFormCode = BpmnModelUtils.parseNodeFormCode(bpmnModel, task.getTaskDefinitionKey());
        onlineFormDataService.saveNodeFormData(definitionInfo, instance.getProcessInstanceId(),
                businessKey, nodeFormCode, writableData);
    }

    /**
     * 按字段权限过滤出可写字段
     *
     * 未配置字段权限时视为全部可写 —— 与前端渲染的默认行为保持一致（不配置即默认可编辑）。
     *
     * <h3>子表字段用层级 key</h3>
     * 子表控件提交上来的是一个数组（{@code contacts: [{...}, {...}]}），整段只有一个顶层 key。
     * 若只按顶层 key 判权限，明细里的每一列就只能整体放行或整体禁止，做不到
     * 「审批人只能改明细的备注列」这种常见诉求。
     *
     * 所以明细列的权限用 {@code 子表控件field.明细字段} 表示（如 {@code contacts.remark}），
     * 裁剪时逐行按内部字段过滤。这与前端 parseFormFields 递归解析出的 key 是同一套写法。
     *
     * <p>没有为某个明细列配权限时保持可写，与顶层字段的处理一致。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> filterWritableFields(Map<String, Object> data, Map<String, String> fieldsPermission) {
        if (CollUtil.isEmpty(fieldsPermission)) {
            return data;
        }
        Map<String, Object> result = new HashMap<>();
        data.forEach((field, value) -> {
            if (!isWritable(fieldsPermission.get(field))) {
                return;
            }
            // 子表：整段可写的前提下，再按 `field.内部字段` 逐行裁剪
            if (value instanceof List<?> rows && hasNestedPermission(fieldsPermission, field)) {
                List<Object> filteredRows = new ArrayList<>(rows.size());
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> rowMap) {
                        filteredRows.add(filterSubRow((Map<String, Object>) rowMap, fieldsPermission, field));
                    } else {
                        filteredRows.add(row);
                    }
                }
                result.put(field, filteredRows);
                return;
            }
            result.put(field, value);
        });
        return result;
    }

    /**
     * 权限配置里是否存在该子表的明细列权限
     *
     * 没有的话就不必逐行裁剪，整段原样放行 —— 避免给未配明细权限的表单平白增加一轮遍历。
     */
    private boolean hasNestedPermission(Map<String, String> fieldsPermission, String subTableField) {
        String prefix = subTableField + SUB_FIELD_SEPARATOR;
        return fieldsPermission.keySet().stream().anyMatch(key -> key.startsWith(prefix));
    }

    /**
     * 裁剪单行明细
     *
     * ⚠️ 主键必须原样保留：运行时靠它做逐行 diff 判断这行是新增还是修改。
     * 被裁掉的话每次审批都会把明细全删再全插，明细 ID 全部漂移。
     */
    private Map<String, Object> filterSubRow(Map<String, Object> row, Map<String, String> fieldsPermission,
                                             String subTableField) {
        Map<String, Object> filtered = new HashMap<>();
        row.forEach((innerField, innerValue) -> {
            String permission = fieldsPermission.get(subTableField + SUB_FIELD_SEPARATOR + innerField);
            if (isWritable(permission)) {
                filtered.put(innerField, innerValue);
            }
        });
        // 主键不受字段权限约束，它不是业务字段，是 diff 的依据
        SUB_ROW_IDENTITY_KEYS.forEach(key -> {
            if (row.containsKey(key)) {
                filtered.put(key, row.get(key));
            }
        });
        return filtered;
    }

    /**
     * 权限值是否表示可写
     *
     * 权限里没声明的字段：保持可写。字段是表单新增的、而节点权限配置还没同步时属于这种情况。
     */
    private boolean isWritable(String permission) {
        return permission == null
                || Objects.equals(permission, String.valueOf(BpmFieldPermissionEnum.WRITE.getPermission()));
    }

    /**
     * 校验选择的下一个节点的审批人，是否合法
     * <p>
     * 1. 是否有漏选：没有选择审批人
     * 2. 是否有多选：非下一个节点
     *
     * @param taskDefinitionKey 当前任务节点标识
     * @param variables         流程变量
     * @param bpmnModel         流程模型
     * @param nextAssignees     下一个节点审批人集合（参数）
     * @param processInstance   流程实例
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> validateAndSetNextAssignees(String taskDefinitionKey, Map<String, Object> variables, BpmnModel bpmnModel,
                                                            Map<String, List<Long>> nextAssignees, ProcessInstance processInstance) {
        // simple 设计器第一个节点默认为发起人节点，不校验是否存在审批人
        if (Objects.equals(taskDefinitionKey, START_USER_NODE_ID)) {
            return variables;
        }
        // 1. 获取下一个将要执行的节点集合
        FlowElement flowElement = bpmnModel.getFlowElement(taskDefinitionKey);
        List<UserTask> nextFlowNodes = getNextUserTasks(flowElement, bpmnModel, variables);

        // 2. 校验选择的下一个节点的审批人，是否合法
        for (UserTask nextFlowNode : nextFlowNodes) {
            Integer candidateStrategy = parseCandidateStrategy(nextFlowNode);
            // 2.1 情况一：如果节点中的审批人策略为 发起人自选
            if (ObjUtil.equals(candidateStrategy, BpmTaskCandidateStrategyEnum.START_USER_SELECT.getStrategy())) {
                // 特殊：如果当前节点已经存在审批人，则不允许覆盖
                Map<String, List<Long>> startUserSelectAssignees = FlowableUtils.getStartUserSelectAssignees(processInstance.getProcessVariables());
                if (startUserSelectAssignees != null && CollUtil.isNotEmpty(startUserSelectAssignees.get(nextFlowNode.getId()))) {
                    continue;
                }
                // 如果节点存在，但未配置审批人
                List<Long> assignees = nextAssignees != null ? nextAssignees.get(nextFlowNode.getId()) : null;
                if (CollUtil.isEmpty(assignees)) {
                    throw exception(PROCESS_INSTANCE_START_USER_SELECT_ASSIGNEES_NOT_CONFIG, nextFlowNode.getName());
                }

                // 设置 PROCESS_INSTANCE_VARIABLE_START_USER_SELECT_ASSIGNEES
                if (startUserSelectAssignees == null) {
                    startUserSelectAssignees = new HashMap<>();
                }
                startUserSelectAssignees.put(nextFlowNode.getId(), assignees);
                variables.put(BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_START_USER_SELECT_ASSIGNEES, startUserSelectAssignees);
                continue;
            }

            // 2.2 情况二：如果节点中的审批人策略为 审批人，在审批时选择下一个节点的审批人，并且该节点的审批人为空
            if (ObjUtil.equals(candidateStrategy, BpmTaskCandidateStrategyEnum.APPROVE_USER_SELECT.getStrategy())) {
                // 特殊：如果当前节点已经存在审批人，则不允许覆盖。 例如并行节点后，设置的审批人自选节点。 https://t.zsxq.com/daxv1
                Map<String, List<Long>> approveUserSelectAssignees = FlowableUtils.getApproveUserSelectAssignees(processInstance.getProcessVariables());
                if (approveUserSelectAssignees != null && CollUtil.isNotEmpty(approveUserSelectAssignees.get(nextFlowNode.getId()))) {
                    continue;
                }
                // 如果节点存在，但未配置审批人
                List<Long> assignees = nextAssignees != null ? nextAssignees.get(nextFlowNode.getId()) : null;
                if (CollUtil.isEmpty(assignees)) {
                    throw exception(PROCESS_INSTANCE_APPROVE_USER_SELECT_ASSIGNEES_NOT_CONFIG, nextFlowNode.getName());
                }

                // 设置 PROCESS_INSTANCE_VARIABLE_APPROVE_USER_SELECT_ASSIGNEES
                if (approveUserSelectAssignees == null) {
                    approveUserSelectAssignees = new HashMap<>();
                }
                approveUserSelectAssignees.put(nextFlowNode.getId(), assignees);
                Map<String, List<Long>> existingApproveUserSelectAssignees = (Map<String, List<Long>>) variables.get(
                        BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_APPROVE_USER_SELECT_ASSIGNEES);
                if (CollUtil.isNotEmpty(existingApproveUserSelectAssignees)) {
                    approveUserSelectAssignees.putAll(existingApproveUserSelectAssignees);
                }
                variables.put(BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_APPROVE_USER_SELECT_ASSIGNEES, approveUserSelectAssignees);
            }
        }
        return variables;
    }

    /**
     * 审批通过存在“后加签”的任务。
     * <p>
     * 注意：该任务不能马上完成，需要一个中间状态（APPROVING），并激活剩余所有子任务（PROCESS）为可审批处理
     * 如果马上完成，则会触发下一个任务，甚至如果没有下一个任务则流程实例就直接结束了！
     *
     * @param task  当前任务
     * @param reqVO 前端请求参数
     */
    private void approveAfterSignTask(Task task, BpmTaskApproveReqVO reqVO) {
        // 更新父 task 状态 + 原因
        updateTaskStatusAndReason(task.getId(), BpmTaskStatusEnum.APPROVING.getStatus(), reqVO.getReason());

        // 2. 激活子任务
        List<Task> childrenTaskList = getTaskListByParentTaskId(task.getId());
        for (Task childrenTask : childrenTaskList) {
            taskService.resolveTask(childrenTask.getId());
            // 更新子 task 状态
            updateTaskStatus(childrenTask.getId(), BpmTaskStatusEnum.RUNNING.getStatus());
        }
    }

    /**
     * 如果父任务是有前后【加签】的任务，如果它【加签】出来的子任务都被处理，需要处理父任务：
     * <p>
     * 1. 如果是【向前】加签，则需要重新激活父任务，让它可以被审批
     * 2. 如果是【向后】加签，则需要完成父任务，让它完成审批
     *
     * @param parentTaskId 父任务编号
     */
    private void handleParentTaskIfSign(String parentTaskId) {
        if (StrUtil.isBlank(parentTaskId)) {
            return;
        }
        // 1.1 判断是否还有子任务。如果没有，就不处理
        Long childrenTaskCount = getTaskCountByParentTaskId(parentTaskId);
        if (childrenTaskCount > 0) {
            return;
        }
        // 1.2 只处理加签的父任务
        Task parentTask = validateTaskExists(parentTaskId);
        String scopeType = parentTask.getScopeType();
        if (BpmTaskSignTypeEnum.of(scopeType) == null) {
            return;
        }

        // 2. 子任务已处理完成，清空 scopeType 字段，修改 parentTask 信息，方便后续可以继续向前后向后加签
        TaskEntityImpl parentTaskImpl = (TaskEntityImpl) parentTask;
        parentTaskImpl.setScopeType(null);
        taskService.saveTask(parentTaskImpl);

        // 3.1 情况一：处理向【向前】加签
        if (BpmTaskSignTypeEnum.BEFORE.getType().equals(scopeType)) {
            // 3.1.1 owner 重新赋值给父任务的 assignee，这样它就可以被审批
            taskService.resolveTask(parentTaskId);
            // 3.1.2 更新流程任务 status
            updateTaskStatus(parentTaskId, BpmTaskStatusEnum.RUNNING.getStatus());
            // 3.2 情况二：处理向【向后】加签
        } else if (BpmTaskSignTypeEnum.AFTER.getType().equals(scopeType)) {
            // 只有 parentTask 处于 APPROVING 的情况下，才可以继续 complete 完成
            // 否则，一个未审批的 parentTask 任务，在加签出来的任务都被减签的情况下，就直接完成审批，这样会存在问题
            Integer status = (Integer) parentTask.getTaskLocalVariables().get(BpmnVariableConstants.TASK_VARIABLE_STATUS);
            if (ObjectUtil.notEqual(status, BpmTaskStatusEnum.APPROVING.getStatus())) {
                return;
            }
            // 3.2.2 完成自己（因为它已经没有子任务，所以也可以完成）
            updateTaskStatus(parentTaskId, BpmTaskStatusEnum.APPROVE.getStatus());
            taskService.complete(parentTaskId);
        }

        // 4. 递归处理父任务
        handleParentTaskIfSign(parentTask.getParentTaskId());
    }

    /**
     * 审批被委派的任务
     *
     * @param reqVO 前端请求参数，包含当前任务ID，审批意见等
     * @param task  当前被审批的任务
     */
    private void approveDelegateTask(BpmTaskApproveReqVO reqVO, Task task) {
        // 1. 添加审批意见
        AdminUserRespDTO currentUser = adminUserApi.getUser(WebFrameworkUtils.getLoginUserId());
        AdminUserRespDTO ownerUser = adminUserApi.getUser(NumberUtils.parseLong(task.getOwner())); // 发起委托的用户
        Assert.notNull(ownerUser, "委派任务找不到原审批人，需要检查数据");
        commentService.createComment(reqVO.getId(), task.getProcessInstanceId(), BpmCommentTypeEnum.DELEGATE_END,
                currentUser.getNickname(), ownerUser.getNickname(), reqVO.getReason());

        // 2.1 调用 resolveTask 完成任务。
        // 底层调用 TaskHelper.changeTaskAssignee(task, task.getOwner())：将 owner 设置为 assignee
        taskService.resolveTask(task.getId());
        // 2.2 更新 task 状态 + 原因
        updateTaskStatusAndReason(task.getId(), BpmTaskStatusEnum.RUNNING.getStatus(), reqVO.getReason());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false) // 关闭数据权限，避免查询不到用户数据。相关案例：https://gitee.com/zhijiantianya/yudao-cloud/issues/ID1UYA
    public void rejectTask(Long userId, @Valid BpmTaskRejectReqVO reqVO) {
        // 1.1 校验任务存在
        Task task = validateTask(userId, reqVO.getId());
        // 1.2 校验流程实例存在
        ProcessInstance instance = processInstanceService.getProcessInstance(task.getProcessInstanceId());
        if (instance == null) {
            throw exception(PROCESS_INSTANCE_NOT_EXISTS);
        }

        // 2.1 更新流程任务为不通过
        updateTaskStatusAndReason(task.getId(), BpmTaskStatusEnum.REJECT.getStatus(), reqVO.getReason());
        // 2.2 添加流程评论
        commentService.createComment(task.getId(), task.getProcessInstanceId(), BpmCommentTypeEnum.REJECT,
                reqVO.getReason());
        // 2.3 添加附件
        if (CollUtil.isNotEmpty(reqVO.getAttachments())) {
            reqVO.getAttachments().forEach(attachment -> taskService.createAttachment(BpmAttachmentTypeEnum.TASK_ATTACHMENT.getType(),
                    task.getId(), task.getProcessInstanceId(), FileUtil.getName(URLUtil.getPath(attachment)), null, attachment));
        }

        // 2.4 如果当前任务时被加签的，则加它的根任务也标记成未通过
        // 疑问：为什么要标记未通过呢？
        // 回答：例如说 A 任务被向前加签除 B 任务时，B 任务被审批不通过，此时 A 会被取消。而 ainb-ui-admin-vue3 不展示“已取消”的任务，导致展示不出审批不通过的细节。
        if (task.getParentTaskId() != null) {
            String rootParentId = getTaskRootParentId(task);
            updateTaskStatusAndReason(rootParentId, BpmTaskStatusEnum.REJECT.getStatus(),
                    BpmCommentTypeEnum.REJECT.formatComment("加签任务不通过"));
            commentService.createComment(rootParentId, task.getProcessInstanceId(), BpmCommentTypeEnum.REJECT,
                    "加签任务不通过");
        }

        // 3. 根据不同的 RejectHandler 处理策略
        BpmnModel bpmnModel = modelService.getBpmnModelByDefinitionId(task.getProcessDefinitionId());
        FlowElement userTaskElement = BpmnModelUtils.getFlowElementById(bpmnModel, task.getTaskDefinitionKey());
        // 3.1 情况一：驳回到指定的任务节点
        BpmUserTaskRejectHandlerTypeEnum userTaskRejectHandlerType = BpmnModelUtils.parseRejectHandlerType(userTaskElement);
        if (userTaskRejectHandlerType == BpmUserTaskRejectHandlerTypeEnum.RETURN_USER_TASK) {
            String returnTaskId = BpmnModelUtils.parseReturnTaskId(userTaskElement);
            Assert.notNull(returnTaskId, "退回的节点不能为空");
            returnTask(userId, new BpmTaskReturnReqVO().setId(task.getId())
                    .setTargetTaskDefinitionKey(returnTaskId).setReason(reqVO.getReason()));
            return;
        }

        // 3.2 情况二： 标记流程为不通过并结束流程
        processInstanceService.updateProcessInstanceReject(instance, reqVO.getReason()); // 标记不通过
        moveTaskToEnd(task.getProcessInstanceId(), BpmCommentTypeEnum.REJECT.formatComment(reqVO.getReason())); // 结束流程
    }

    /**
     * 更新流程任务的 status 状态
     *
     * @param id     任务编号
     * @param status 状态
     */
    private void updateTaskStatus(String id, Integer status) {
        taskService.setVariableLocal(id, BpmnVariableConstants.TASK_VARIABLE_STATUS, status);
    }

    /**
     * 更新流程任务的 status 状态、reason 理由
     *
     * @param id     任务编号
     * @param status 状态
     * @param reason 理由（审批通过、审批不通过的理由）
     */
    private void updateTaskStatusAndReason(String id, Integer status, String reason) {
        updateTaskStatus(id, status);
        taskService.setVariableLocal(id, BpmnVariableConstants.TASK_VARIABLE_REASON, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false) // 关闭数据权限，避免查询不到用户数据。相关案例：https://gitee.com/zhijiantianya/yudao-cloud/issues/ID1UYA
    public void returnTask(Long userId, BpmTaskReturnReqVO reqVO) {
        // 1.1 当前任务 task
        Task task = validateTask(userId, reqVO.getId());
        if (task.isSuspended()) {
            throw exception(TASK_IS_PENDING);
        }
        // 1.2 获取流程模型信息
        BpmnModel bpmnModel = modelService.getBpmnModelByDefinitionId(task.getProcessDefinitionId());
        // 1.3 校验源头和目标节点的关系，并返回目标元素
        FlowElement targetElement = validateTargetTaskCanReturn(bpmnModel, task.getTaskDefinitionKey(),
                reqVO.getTargetTaskDefinitionKey());

        // 2. 调用 Flowable 框架的退回逻辑
        returnTask(userId, bpmnModel, task, targetElement, reqVO);
    }

    /**
     * 退回流程节点时，校验目标任务节点是否可退回
     *
     * @param bpmnModel 流程模型
     * @param sourceKey 当前任务节点 Key
     * @param targetKey 目标任务节点 key
     * @return 目标任务节点元素
     */
    private FlowElement validateTargetTaskCanReturn(BpmnModel bpmnModel, String sourceKey, String targetKey) {
        // 1.1 获取当前任务节点元素
        FlowElement source = BpmnModelUtils.getFlowElementById(bpmnModel, sourceKey);
        // 1.2 获取跳转的节点元素
        FlowElement target = BpmnModelUtils.getFlowElementById(bpmnModel, targetKey);
        if (target == null) {
            throw exception(TASK_TARGET_NODE_NOT_EXISTS);
        }

        // 2. 只有串行可到达的节点，才可以退回。类似非串行、子流程无法退回
        if (!BpmnModelUtils.isSequentialReachable(source, target, null)) {
            throw exception(TASK_RETURN_FAIL_SOURCE_TARGET_ERROR);
        }
        return target;
    }

    /**
     * 执行退回逻辑
     *
     * @param userId        用户编号
     * @param bpmnModel     流程模型
     * @param currentTask   当前退回的任务
     * @param targetElement 需要退回到的目标任务
     * @param reqVO         前端参数封装
     */
    public void returnTask(Long userId, BpmnModel bpmnModel, Task currentTask, FlowElement targetElement, BpmTaskReturnReqVO reqVO) {
        // 1. 获得所有需要回撤的任务 taskDefinitionKey，用于稍后的 moveActivityIdsToSingleActivityId 回撤
        // 1.1 获取所有正常进行的任务节点 Key
        List<Task> taskList = taskService.createTaskQuery().processInstanceId(currentTask.getProcessInstanceId()).list();
        List<String> runTaskKeyList = convertList(taskList, Task::getTaskDefinitionKey);
        // 1.2 通过 targetElement 的出口连线，计算在 runTaskKeyList 有哪些 key 需要被撤回
        // 为什么不直接使用 runTaskKeyList 呢？因为可能存在多个审批分支，例如说：A -> B -> C 和 D -> F，而只要 C 撤回到 A，需要排除掉 F
        List<UserTask> returnUserTaskList = BpmnModelUtils.iteratorFindChildUserTasks(targetElement, runTaskKeyList, null, null);
        List<String> returnTaskKeyList = convertList(returnUserTaskList, UserTask::getId);
        // 2. 给当前要被退回的 task 数组，设置退回意见
        taskList.forEach(task -> {
            // 需要排除掉，不需要设置退回意见的任务
            if (!returnTaskKeyList.contains(task.getTaskDefinitionKey())) {
                return;
            }
            // 判断是否分配给自己任务，因为会签任务，一个节点会有多个任务
            if (isAssignUserTask(userId, task)) { // 情况一：自己的任务，进行 RETURN 标记
                // 2.1.1 添加评论
                commentService.createComment(task.getId(), currentTask.getProcessInstanceId(), BpmCommentTypeEnum.RETURN,
                        reqVO.getReason());
                // 2.1.2 更新 task 状态 + 原因
                updateTaskStatusAndReason(task.getId(), BpmTaskStatusEnum.RETURN.getStatus(), reqVO.getReason());
            } else { // 情况二：别人的任务，进行 CANCEL 标记
                processTaskCanceled(task.getId());
            }
        });

        // 3. 构建需要预测的任务流程变量
        Set<String> needSimulateTaskDefinitionKeys = getNeedSimulateTaskDefinitionKeys(bpmnModel, currentTask, targetElement);

        // 4. 执行驳回
        // ① 使用 moveExecutionsToSingleActivityId 替换 moveActivityIdsToSingleActivityId。原因：当多实例任务回退的时候有问题。
        //    相关 issue: https://github.com/flowable/flowable-engine/issues/3944
        // ② flowable 7.2.0 版本后，继续使用 moveActivityIdsToSingleActivityId 方法。原因：flowable 7.2.0 版本修复了该问题。
        //    相关 issue：https://github.com/YunaiV/ruoyi-vue-pro/issues/1018
        // ③ moveActivityIdsToSingleActivityId 使用遇到问题， 相关 issue https://gitee.com/zhijiantianya/yudao-cloud/issues/IJM8MS
        //  改成 moveExecutionsToSingleActivityId 好像并没有遇到 ② 提到的超时提醒失效的问题。暂时先改回 moveExecutionsToSingleActivityId
        // ④ moveExecutionsToSingleActivityId 回退多实例的时候不会去删除多实例根, 应改成 moveActivityIdsToSingleActivityId
        // flowable 8.0.0  修复上面相关问题， 还修复了并行分支回退的问题 https://t.zsxq.com/z4d9i。
        // ⑤ 使用 moveExecutionsToSingleActivityId 方法进行回退操作时，如果是多实例的用户任务【芋道用户任务默认为多实例】，不会删除多实例任务的根数据 ACT_RU_EXECUTION
        // 会导致有一些问题，所以使用 moveActivityIdsToSingleActivityId。 但是该方法在 flowable 6.8.1 ~ 7.1.0 的版本会有 bug 阻塞回退功能
        // 相关 issue: https://github.com/flowable/flowable-engine/issues/3944
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(currentTask.getProcessInstanceId())
                .moveActivityIdsToSingleActivityId(returnTaskKeyList, reqVO.getTargetTaskDefinitionKey())
                // 设置需要预测的任务 ids 的流程变量，用于辅助预测
                .processVariable(BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_NEED_SIMULATE_TASK_IDS, needSimulateTaskDefinitionKeys)
                // 设置流程变量节点退回标记, 用于退回到节点，不执行 BpmUserTaskAssignStartUserHandlerTypeEnum 策略，导致自动通过
                .processVariable(String.format(BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_RETURN_FLAG, reqVO.getTargetTaskDefinitionKey()), Boolean.TRUE)
                .changeState();
    }

    private Set<String> getNeedSimulateTaskDefinitionKeys(BpmnModel bpmnModel, Task currentTask, FlowElement targetElement) {
        // 1. 获取需要预测的任务的 definition key。因为当前任务还没完成，也需要预测
        Set<String> taskDefinitionKeys = CollUtil.newHashSet(currentTask.getTaskDefinitionKey());

        // 2.1 获取已结束任务按时间倒序排序
        List<HistoricTaskInstance> endTaskList = CollectionUtils.filterList(
                getTaskListByProcessInstanceId(currentTask.getProcessInstanceId(), Boolean.FALSE),
                item -> item.getEndTime() != null);
        // 2.2 从结束任务中找到最近一个的目标任务
        HistoricTaskInstance targetTask = findFirst(endTaskList,
                item -> item.getTaskDefinitionKey().equals(targetElement.getId()));
        if (targetTask == null) {
            return taskDefinitionKeys;
        }
        // 2.3 遍历已结束的任务，找到在 targetTask 之后生成的任务，且串行可达的任务
        endTaskList.forEach(item -> {
            FlowElement element = getFlowElementById(bpmnModel, item.getTaskDefinitionKey());
            // 如果已结束的任务在回退目标节点之后生成，且串行可达，则加到需要预测节点中
            // TODO 串行可达的方法需要和判断可回退节点 validateTargetTaskCanReturn 分开吗？ 并行网关可能会有问题。
            if (item.getCreateTime().compareTo(targetTask.getCreateTime()) > 0
                    && BpmnModelUtils.isSequentialReachable(element, targetElement, null)) {
                taskDefinitionKeys.add(item.getTaskDefinitionKey());
            }
        });
        return taskDefinitionKeys;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false) // 关闭数据权限，避免查询不到用户数据。相关案例：https://gitee.com/zhijiantianya/yudao-cloud/issues/ID1UYA
    public void delegateTask(Long userId, BpmTaskDelegateReqVO reqVO) {
        String taskId = reqVO.getId();
        // 1.1 校验任务
        Task task = validateTask(userId, reqVO.getId());
        if (task.getAssignee().equals(reqVO.getDelegateUserId().toString())) { // 校验当前审批人和被委派人不是同一人
            throw exception(TASK_DELEGATE_FAIL_USER_REPEAT);
        }
        // 1.2 校验目标用户存在
        AdminUserRespDTO delegateUser = adminUserApi.getUser(reqVO.getDelegateUserId());
        if (delegateUser == null) {
            throw exception(TASK_DELEGATE_FAIL_USER_NOT_EXISTS);
        }

        // 2. 添加委托意见
        AdminUserRespDTO currentUser = adminUserApi.getUser(userId);
        commentService.createComment(taskId, task.getProcessInstanceId(), BpmCommentTypeEnum.DELEGATE_START,
                currentUser.getNickname(), delegateUser.getNickname(), reqVO.getReason());

        // 3.1 设置任务所有人 (owner) 为原任务的处理人 (assignee)
        // 特殊：如果已经被委派（owner 非空），则不需要更新 owner：https://gitee.com/zhijiantianya/yudao-cloud/issues/ICJ153
        if (StrUtil.isEmpty(task.getOwner())) {
            taskService.setOwner(taskId, task.getAssignee());
        }
        // 3.2 执行委派，将任务委派给 delegateUser
        // 补充说明：委托不单独设置状态。如果需要，可通过 Task 的 DelegationState 字段，判断是否为 DelegationState.PENDING 委托中

        // 4. 记录【原】受理人的参与关系。
        // 只记原受理人：被委派人会由 delegateTask 触发的任务分配事件自动记成 APPROVER，
        // 这里再记一次就会出现同一人同一任务两行、且参与方式不同（见 Service 接口注释）。
        // ⚠️ 取的是 task.getAssignee()【委派执行前】的值 —— 执行后它已经变成被委派人了
        instanceParticipantService.recordHandover(task.getProcessInstanceId(), taskId,
                task.getTaskDefinitionKey(), parseAssigneeId(task.getAssignee()),
                BpmParticipationEnum.DELEGATE_FROM);
        taskService.delegateTask(taskId, reqVO.getDelegateUserId().toString());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false) // 关闭数据权限，避免查询不到用户数据。相关案例：https://gitee.com/zhijiantianya/yudao-cloud/issues/ID1UYA
    public void transferTask(Long userId, BpmTaskTransferReqVO reqVO) {
        String taskId = reqVO.getId();
        // 1.1 校验任务
        Task task = validateTask(userId, reqVO.getId());
        if (task.getAssignee().equals(reqVO.getAssigneeUserId().toString())) { // 校验当前审批人和被转派人不是同一人
            throw exception(TASK_TRANSFER_FAIL_USER_REPEAT);
        }
        // 1.2 校验目标用户存在
        AdminUserRespDTO assigneeUser = adminUserApi.getUser(reqVO.getAssigneeUserId());
        if (assigneeUser == null) {
            throw exception(TASK_TRANSFER_FAIL_USER_NOT_EXISTS);
        }

        // 2. 添加委托意见
        AdminUserRespDTO currentUser = adminUserApi.getUser(userId);
        commentService.createComment(taskId, task.getProcessInstanceId(), BpmCommentTypeEnum.TRANSFER,
                currentUser.getNickname(), assigneeUser.getNickname(), reqVO.getReason());

        // 3.1 设置任务所有人 (owner) 为原任务的处理人 (assignee)
        // 特殊：如果已经被转派（owner 非空），则不需要更新 owner：https://gitee.com/zhijiantianya/yudao-cloud/issues/ICJ153
        if (StrUtil.isEmpty(task.getOwner())) {
            taskService.setOwner(taskId, task.getAssignee());
        }
        // 3.2 执行转派（审批人），将任务转派给 assigneeUser
        // 委托（ delegate）和转派（transfer）的差别，就在这块的调用！！！！

        // 4. 记录【原】受理人的参与关系。与委派的差别只在参与方式：
        // 转办是把单子交出去不再回来，所以原受理人保留【只读】可见性；
        // 委派办完会退回原受理人，所以他保有可写（见 BpmParticipationEnum）
        instanceParticipantService.recordHandover(task.getProcessInstanceId(), taskId,
                task.getTaskDefinitionKey(), parseAssigneeId(task.getAssignee()),
                BpmParticipationEnum.TRANSFER_FROM);
        taskService.setAssignee(taskId, reqVO.getAssigneeUserId().toString());
    }

    /**
     * Flowable 的受理人是字符串，本系统用数字主键
     *
     * 解析不出来时返回 null（由参与人服务那侧忽略），不让一次审批因为记不了参与人而失败
     */
    private Long parseAssigneeId(String assignee) {
        if (StrUtil.isEmpty(assignee)) {
            return null;
        }
        return NumberUtil.isLong(assignee) ? Long.valueOf(assignee) : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false) // 关闭数据权限，避免查询不到用户数据。相关案例：https://gitee.com/zhijiantianya/yudao-cloud/issues/ID1UYA
    public void moveTaskToEnd(String processInstanceId, String reason) {
        List<Task> taskList = getRunningTaskListByProcessInstanceId(processInstanceId, null, null);
        if (CollUtil.isEmpty(taskList)) {
            return;
        }

        // 1. 其它未结束的任务，直接取消
        // 疑问：为什么不通过 updateTaskStatusWhenCanceled 监听取消，而是直接提前调用呢？
        // 回答：详细见 updateTaskStatusWhenCanceled 的方法，加签的场景
        taskList.forEach(task -> {
            Integer otherTaskStatus = (Integer) task.getTaskLocalVariables().get(BpmnVariableConstants.TASK_VARIABLE_STATUS);
            if (BpmTaskStatusEnum.isEndStatus(otherTaskStatus)) {
                return;
            }
            processTaskCanceled(task.getId());
        });

        // 2. 终止流程
        BpmnModel bpmnModel = modelService.getBpmnModelByDefinitionId(taskList.get(0).getProcessDefinitionId());
        List<String> activityIds = CollUtil.newArrayList(convertSet(taskList, Task::getTaskDefinitionKey));
        EndEvent endEvent = BpmnModelUtils.getEndEvent(bpmnModel);
        Assert.notNull(endEvent, "结束节点不能为空");
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstanceId)
                .moveActivityIdsToSingleActivityId(activityIds, endEvent.getId())
                .changeState();

        // 3. 特殊：如果跳转到 EndEvent 流程还未结束， 执行 deleteProcessInstance 方法
        // TODO 芋艿：目前发现并行分支情况下，会存在这个情况，后续看看有没更好的方案；
        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstanceId).list();
        if (CollUtil.isNotEmpty(executions)) {
            log.warn("[moveTaskToEnd][执行跳转到 EndEvent 后, 流程实例未结束，强制执行 deleteProcessInstance 方法]");
            runtimeService.deleteProcessInstance(processInstanceId, reason);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false) // 关闭数据权限，避免查询不到用户数据。相关案例：https://gitee.com/zhijiantianya/yudao-cloud/issues/ID1UYA
    public void createSignTask(Long userId, BpmTaskSignCreateReqVO reqVO) {
        // 1. 获取和校验任务
        TaskEntityImpl taskEntity = validateTaskCanCreateSign(userId, reqVO);
        List<AdminUserRespDTO> userList = adminUserApi.getUserList(reqVO.getUserIds());
        if (CollUtil.isEmpty(userList)) {
            throw exception(TASK_SIGN_CREATE_USER_NOT_EXIST);
        }

        // 2. 处理当前任务
        // 2.1 开启计数功能，主要用于为了让表 ACT_RU_TASK 中的 SUB_TASK_COUNT_ 字段记录下总共有多少子任务，后续可能有用
        taskEntity.setCountEnabled(true);
        // 2.2 向前加签，设置 owner，置空 assign。等子任务都完成后，再调用 resolveTask 重新将 owner 设置为 assign
        // 原因是：不能和向前加签的子任务一起审批，需要等前面的子任务都完成才能审批
        if (reqVO.getType().equals(BpmTaskSignTypeEnum.BEFORE.getType())) {
            taskEntity.setOwner(taskEntity.getAssignee());
            taskEntity.setAssignee(null);
        }
        // 2.4 记录加签方式，完成任务时需要用到判断
        taskEntity.setScopeType(reqVO.getType());
        // 2.5 保存当前任务修改后的值
        taskService.saveTask(taskEntity);
        // 2.6 更新 task 状态为 WAIT，只有在向前加签的时候
        if (reqVO.getType().equals(BpmTaskSignTypeEnum.BEFORE.getType())) {
            updateTaskStatus(taskEntity.getId(), BpmTaskStatusEnum.WAIT.getStatus());
        }

        // 3. 创建加签任务
        createSignTaskList(convertList(reqVO.getUserIds(), String::valueOf), taskEntity);

        // 3.1 记录加签发起人的参与关系。
        // ⚠️ 用入参 userId 而不是 taskEntity.getAssignee()：向前加签在 2.2 已经把
        // assignee 置空了，此刻读它只会拿到 null，参与关系就静默丢了
        instanceParticipantService.recordHandover(taskEntity.getProcessInstanceId(), reqVO.getId(),
                taskEntity.getTaskDefinitionKey(), userId, BpmParticipationEnum.SIGN_FROM);

        // 4. 记录加签的评论到 task 任务
        AdminUserRespDTO currentUser = adminUserApi.getUser(userId);
        commentService.createComment(reqVO.getId(), taskEntity.getProcessInstanceId(), BpmCommentTypeEnum.ADD_SIGN,
                currentUser.getNickname(), BpmTaskSignTypeEnum.nameOfType(reqVO.getType()),
                String.join(",", convertList(userList, AdminUserRespDTO::getNickname)), reqVO.getReason());
    }

    /**
     * 校验任务是否可以加签，主要校验加签类型是否一致：
     * <p>
     * 1. 如果存在“向前加签”的任务，则不能“向后加签”
     * 2. 如果存在“向后加签”的任务，则不能“向前加签”
     *
     * @param userId 当前用户 ID
     * @param reqVO  请求参数，包含任务 ID 和加签类型
     * @return 当前任务
     */
    private TaskEntityImpl validateTaskCanCreateSign(Long userId, BpmTaskSignCreateReqVO reqVO) {
        TaskEntityImpl taskEntity = (TaskEntityImpl) validateTask(userId, reqVO.getId());
        // 向前加签和向后加签不能同时存在
        if (taskEntity.getScopeType() != null
                && ObjectUtil.notEqual(taskEntity.getScopeType(), reqVO.getType())) {
            throw exception(TASK_SIGN_CREATE_TYPE_ERROR,
                    BpmTaskSignTypeEnum.nameOfType(taskEntity.getScopeType()), BpmTaskSignTypeEnum.nameOfType(reqVO.getType()));
        }

        // 同一个 key 的任务，审批人不重复
        List<Task> taskList = taskService.createTaskQuery().processInstanceId(taskEntity.getProcessInstanceId())
                .taskDefinitionKey(taskEntity.getTaskDefinitionKey()).list();
        List<Long> currentAssigneeList = convertListByFlatMap(taskList, task -> // 需要考虑 owner 的情况，因为向后加签时，它暂时没 assignee 而是 owner
                Stream.of(NumberUtils.parseLong(task.getAssignee()), NumberUtils.parseLong(task.getOwner())));
        if (CollUtil.containsAny(currentAssigneeList, reqVO.getUserIds())) {
            List<AdminUserRespDTO> userList = adminUserApi.getUserList(CollUtil.intersection(currentAssigneeList, reqVO.getUserIds()));
            throw exception(TASK_SIGN_CREATE_USER_REPEAT, String.join(",", convertList(userList, AdminUserRespDTO::getNickname)));
        }
        return taskEntity;
    }

    /**
     * 创建加签子任务
     *
     * @param userIds    被加签的用户 ID
     * @param taskEntity 被加签的任务
     */
    private void createSignTaskList(List<String> userIds, TaskEntityImpl taskEntity) {
        if (CollUtil.isEmpty(userIds)) {
            return;
        }
        // 创建加签人的新任务，全部基于 taskEntity 为父任务来创建
        for (String addSignId : userIds) {
            if (StrUtil.isBlank(addSignId)) {
                continue;
            }
            createSignTask(taskEntity, addSignId);
        }
    }

    /**
     * 创建加签子任务
     *
     * @param parentTask 父任务
     * @param assignee   子任务的执行人
     */
    private void createSignTask(TaskEntityImpl parentTask, String assignee) {
        // 1. 生成子任务
        TaskEntityImpl task = (TaskEntityImpl) taskService.newTask(IdUtil.fastSimpleUUID());
        BpmTaskConvert.INSTANCE.copyTo(parentTask, task);

        // 2.1 向前加签，设置审批人
        if (BpmTaskSignTypeEnum.BEFORE.getType().equals(parentTask.getScopeType())) {
            task.setAssignee(assignee);
            // 2.2 向后加签，设置 owner 不设置 assignee 是因为不能同时审批，需要等父任务完成
        } else {
            task.setOwner(assignee);
        }
        // 2.3 保存子任务
        taskService.saveTask(task);

        // 3. 向后前签，设置子任务的状态为 WAIT，因为需要等父任务审批完
        if (BpmTaskSignTypeEnum.AFTER.getType().equals(parentTask.getScopeType())) {
            updateTaskStatus(task.getId(), BpmTaskStatusEnum.WAIT.getStatus());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false) // 关闭数据权限，避免查询不到用户数据。相关案例：https://gitee.com/zhijiantianya/yudao-cloud/issues/ID1UYA
    @SuppressWarnings("DataFlowIssue")
    public void deleteSignTask(Long userId, BpmTaskSignDeleteReqVO reqVO) {
        // 1.1 校验 task 可以被减签
        Task task = validateTaskCanSignDelete(reqVO.getId());
        // 1.2 校验取消人存在
        AdminUserRespDTO cancelUser = null;
        if (StrUtil.isNotBlank(task.getAssignee())) {
            cancelUser = adminUserApi.getUser(NumberUtils.parseLong(task.getAssignee()));
        }
        if (cancelUser == null && StrUtil.isNotBlank(task.getOwner())) {
            cancelUser = adminUserApi.getUser(NumberUtils.parseLong(task.getOwner()));
        }
        Assert.notNull(cancelUser, "任务中没有所有者和审批人，数据错误");

        // 2.1 获得子任务列表，包括子任务的子任务
        List<Task> childTaskList = getAllChildTaskList(task);
        childTaskList.add(task);
        // 2.2 更新子任务为已取消
        String cancelReason = StrUtil.format("任务被取消，原因：由于[{}]操作[减签]，", cancelUser.getNickname());
        childTaskList.forEach(childTask -> updateTaskStatusAndReason(childTask.getId(), BpmTaskStatusEnum.CANCEL.getStatus(), cancelReason));
        // 2.3 删除任务和所有子任务
        taskService.deleteTasks(convertList(childTaskList, Task::getId));

        // 3. 记录日志到父任务中。先记录日志是因为，通过 handleParentTask 方法之后，任务可能被完成了，并且不存在了，会报异常，所以先记录
        AdminUserRespDTO user = adminUserApi.getUser(userId);
        commentService.createComment(task.getParentTaskId(), task.getProcessInstanceId(), BpmCommentTypeEnum.SUB_SIGN,
                user.getNickname(), cancelUser.getNickname());

        // 4. 处理当前任务的父任务
        handleParentTaskIfSign(task.getParentTaskId());
    }

    @Override
    public void copyTask(Long userId, BpmTaskCopyReqVO reqVO) {
        processInstanceCopyService.createProcessInstanceCopy(reqVO.getCopyUserIds(), reqVO.getReason(), reqVO.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false) // 关闭数据权限，避免查询不到用户数据。相关案例：https://gitee.com/zhijiantianya/yudao-cloud/issues/ID1UYA
    public void withdrawTask(Long userId, String taskId) {
        // 1.1 查询本人已办任务
        HistoricTaskInstance taskInstance = historyService.createHistoricTaskInstanceQuery()
                .taskId(taskId).taskAssignee(userId.toString()).finished().singleResult();
        if (ObjUtil.isNull(taskInstance)) {
            throw exception(TASK_WITHDRAW_FAIL_TASK_NOT_EXISTS);
        }
        // 1.2 校验流程是否结束
        ProcessInstance processInstance = processInstanceService.getProcessInstance(taskInstance.getProcessInstanceId());
        if (ObjUtil.isNull(processInstance)) {
            throw exception(TASK_WITHDRAW_FAIL_PROCESS_NOT_RUNNING);
        }
        // 1.3 判断此流程是否允许撤回
        BpmProcessDefinitionInfoDO processDefinitionInfo = bpmProcessDefinitionService.getProcessDefinitionInfo(
                processInstance.getProcessDefinitionId());
        if (ObjUtil.isNull(processDefinitionInfo) || !Boolean.TRUE.equals(processDefinitionInfo.getAllowWithdrawTask())) {
            throw exception(TASK_WITHDRAW_FAIL_NOT_ALLOW);
        }
        // 1.4 判断下一个节点是否被审批过，如果是则无法撤回
        BpmnModel bpmnModel = modelService.getBpmnModelByDefinitionId(taskInstance.getProcessDefinitionId());
        UserTask userTask = (UserTask) BpmnModelUtils.getFlowElementById(bpmnModel, taskInstance.getTaskDefinitionKey());
        List<String> nextUserTaskKeys = convertList(BpmnModelUtils.getNextUserTasks(userTask), UserTask::getId);
        if (CollUtil.isEmpty(nextUserTaskKeys)) {
            throw exception(TASK_WITHDRAW_FAIL_NEXT_TASK_NOT_ALLOW);
        }
        // TODO @芋艿：是否选择升级flowable版本解决taskCreatedAfter、taskCreatedBefore问题，升级7.1.0可以；包括 todo 和 done 那边的查询哇？？？ 是的！
        long nextUserTaskFinishedCount = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstance.getProcessInstanceId()).taskDefinitionKeys(nextUserTaskKeys)
                .taskCreatedAfter(taskInstance.getEndTime()).finished().count();
        if (nextUserTaskFinishedCount > 0) {
            throw exception(TASK_WITHDRAW_FAIL_NEXT_TASK_NOT_ALLOW);
        }
        // 1.5 获取需要撤回的运行任务
        List<Task> runningTasks = taskService.createTaskQuery().processInstanceId(processInstance.getProcessInstanceId())
                .taskDefinitionKeys(nextUserTaskKeys).active().list();
        if (CollUtil.isEmpty(runningTasks)) {
            throw exception(TASK_WITHDRAW_FAIL_NEXT_TASK_NOT_ALLOW);
        }

        // 2.1 取消当前任务
        List<String> withdrawExecutionIds = new ArrayList<>();
        for (Task task : runningTasks) {
            // 标记撤回任务为取消
            commentService.createComment(task.getId(), taskInstance.getProcessInstanceId(), BpmCommentTypeEnum.CANCEL,
                    "前一节点撤回");
            updateTaskStatusAndReason(task.getId(), BpmTaskStatusEnum.CANCEL.getStatus(), BpmReasonEnum.CANCEL_BY_WITHDRAW.getReason());
            withdrawExecutionIds.add(task.getExecutionId());
        }
        // 2.2 执行撤回操作
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getProcessInstanceId())
                .moveExecutionsToSingleActivityId(withdrawExecutionIds, taskInstance.getTaskDefinitionKey())
                .changeState();
    }

    /**
     * 校验任务是否能被减签
     *
     * @param id 任务编号
     * @return 任务信息
     */
    private Task validateTaskCanSignDelete(String id) {
        Task task = validateTaskExists(id);
        if (task.getParentTaskId() == null) {
            throw exception(TASK_SIGN_DELETE_NO_PARENT);
        }
        Task parentTask = getTask(task.getParentTaskId());
        if (parentTask == null) {
            throw exception(TASK_SIGN_DELETE_NO_PARENT);
        }
        if (BpmTaskSignTypeEnum.of(parentTask.getScopeType()) == null) {
            throw exception(TASK_SIGN_DELETE_NO_PARENT);
        }
        return task;
    }

    // ========== Event 事件相关方法 ==========

    @Override
    public void processTaskCreated(Task task) {
        // 1. 设置为待办中
        Integer status = (Integer) task.getTaskLocalVariables().get(BpmnVariableConstants.TASK_VARIABLE_STATUS);
        if (status != null) {
            log.error("[updateTaskStatusWhenCreated][taskId({}) 已经有状态({})]", task.getId(), status);
            return;
        }
        updateTaskStatus(task.getId(), BpmTaskStatusEnum.RUNNING.getStatus());

        ProcessInstance processInstance = processInstanceService.getProcessInstance(task.getProcessInstanceId());
        if (processInstance == null) {
            log.error("[processTaskCreated][taskId({}) 没有找到流程实例]", task.getId());
            return;
        }
        BpmProcessDefinitionInfoDO processDefinitionInfo = bpmProcessDefinitionService.
                getProcessDefinitionInfo(processInstance.getProcessDefinitionId());
        if (processDefinitionInfo == null) {
            log.error("[processTaskCreated][processDefinitionId({}) 没有找到流程定义]", processInstance.getProcessDefinitionId());
            return;
        }

        // 2. 任务前置通知
        if (ObjUtil.isNotNull(processDefinitionInfo.getTaskBeforeTriggerSetting())) {
            BpmModelMetaInfoVO.HttpRequestSetting setting = processDefinitionInfo.getTaskBeforeTriggerSetting();
            BpmHttpRequestUtils.executeBpmHttpRequest(processInstance,
                    setting.getUrl(), setting.getHeader(), setting.getBody(), true, setting.getResponse());
        }

        // 3. 处理自动通过的情况，例如说：1）无审批人时，是否自动通过、不通过；2）非【人工审核】时，是否自动通过、不通过
        BpmnModel bpmnModel = modelService.getBpmnModelByDefinitionId(processInstance.getProcessDefinitionId());
        FlowElement userTaskElement = BpmnModelUtils.getFlowElementById(bpmnModel, task.getTaskDefinitionKey());
        Integer approveType = BpmnModelUtils.parseApproveType(userTaskElement);
        Integer assignEmptyHandlerType = BpmnModelUtils.parseAssignEmptyHandlerType(userTaskElement);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            /**
             * 特殊情况：部分情况下，TransactionSynchronizationManager 注册 afterCommit 监听时，不会被调用，但是 afterCompletion 可以
             * 例如说：第一个 task 就是配置【自动通过】或者【自动拒绝】时
             * 参见 <a href="https://gitee.com/zhijiantianya/yudao-cloud/issues/IB7V7Q">issue</a> 反馈
             */
            @Override
            public void afterCompletion(int transactionStatus) {
                // 回滚情况，直接返回
                if (ObjectUtil.equal(transactionStatus, TransactionSynchronization.STATUS_ROLLED_BACK)) {
                    return;
                }
                // 特殊情况：第一个 task 【自动通过】时，第二个任务设置审批人时 transactionStatus 会为 STATUS_UNKNOWN，不知道啥原因
                if (ObjectUtil.equal(transactionStatus, TransactionSynchronization.STATUS_UNKNOWN)
                        && getTask(task.getId()) == null) {
                    return;
                }
                // 特殊情况一：【人工审核】审批人为空，根据配置是否要自动通过、自动拒绝
                if (ObjectUtil.equal(approveType, BpmUserTaskApproveTypeEnum.USER.getType())) {
                    // 如果有审批人、或者拥有人，则说明不满足情况一，不自动通过、不自动拒绝
                    if (!ObjectUtil.isAllEmpty(task.getAssignee(), task.getOwner())) {
                        return;
                    }
                    if (ObjectUtil.equal(assignEmptyHandlerType, BpmUserTaskAssignEmptyHandlerTypeEnum.APPROVE.getType())) {
                        getSelf().approveTask(null, new BpmTaskApproveReqVO()
                                .setId(task.getId()).setReason(BpmReasonEnum.ASSIGN_EMPTY_APPROVE.getReason()));
                    } else if (ObjectUtil.equal(assignEmptyHandlerType, BpmUserTaskAssignEmptyHandlerTypeEnum.REJECT.getType())) {
                        getSelf().rejectTask(null, new BpmTaskRejectReqVO()
                                .setId(task.getId()).setReason(BpmReasonEnum.ASSIGN_EMPTY_REJECT.getReason()));
                    }
                    // 特殊情况二：【自动审核】审批类型为自动通过、不通过
                } else {
                    if (ObjectUtil.equal(approveType, BpmUserTaskApproveTypeEnum.AUTO_APPROVE.getType())) {
                        getSelf().approveTask(null, new BpmTaskApproveReqVO()
                                .setId(task.getId()).setReason(BpmReasonEnum.APPROVE_TYPE_AUTO_APPROVE.getReason()));
                    } else if (ObjectUtil.equal(approveType, BpmUserTaskApproveTypeEnum.AUTO_REJECT.getType())) {
                        getSelf().rejectTask(null, new BpmTaskRejectReqVO()
                                .setId(task.getId()).setReason(BpmReasonEnum.APPROVE_TYPE_AUTO_REJECT.getReason()));
                    }
                }
            }

        });
    }

    /**
     * 重要补充说明：该方法目前主要有两个情况会调用到：
     * <p>
     * 1. 或签场景 + 审批通过：一个或签有多个审批时，如果 A 审批通过，其它或签 B、C 等任务会被 Flowable 自动删除，此时需要通过该方法更新状态为已取消
     * 2. 审批不通过：在 {@link #rejectTask(Long, BpmTaskRejectReqVO)} 不通过时，对于加签的任务，不会被 Flowable 删除，此时需要通过该方法更新状态为已取消
     */
    @Override
    public void processTaskCanceled(String taskId) {
        Task task = getTask(taskId);
        // 1. 可能只是活动，不是任务，所以查询不到
        if (task == null) {
            log.error("[updateTaskStatusWhenCanceled][taskId({}) 任务不存在]", taskId);
            return;
        }

        // 2. 更新 task 状态 + 原因
        Integer status = (Integer) task.getTaskLocalVariables().get(BpmnVariableConstants.TASK_VARIABLE_STATUS);
        if (BpmTaskStatusEnum.isEndStatus(status)) {
            log.error("[updateTaskStatusWhenCanceled][taskId({}) 处于结果({})，无需进行更新]", taskId, status);
            return;
        }
        updateTaskStatusAndReason(taskId, BpmTaskStatusEnum.CANCEL.getStatus(), BpmReasonEnum.CANCEL_BY_SYSTEM.getReason());
        // 补充说明：由于 Task 被删除成 HistoricTask 后，无法通过 Flowable Comment 添加理由，所以无法存储具体的取消理由
    }

    @Override
    @DataPermission(enable = false) // 忽略数据权限，避免因为过滤，导致找不到候选人
    public void processTaskAssigned(Task task) {
        // 发送通知。在事务提交时，批量执行操作，所以直接查询会无法查询到 ProcessInstance，所以这里是通过监听事务的提交来实现。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            /**
             * 特殊情况：部分情况下，TransactionSynchronizationManager 注册 afterCommit 监听时，不会被调用，但是 afterCompletion 可以
             * 例如说：第一个 task 就是配置【自动通过】或者【自动拒绝】时
             * 参见 <a href="https://gitee.com/zhijiantianya/yudao-cloud/issues/IB7V7Q">issue</a> 反馈
             */
            @Override
            public void afterCompletion(int transactionStatus) {
                // 回滚情况，直接返回
                if (ObjectUtil.equal(transactionStatus, TransactionSynchronization.STATUS_ROLLED_BACK)) {
                    return;
                }
                // 特殊情况：第一个 task 【自动通过】时，第二个任务设置审批人时 transactionStatus 会为 STATUS_UNKNOWN，不知道啥原因
                if (ObjectUtil.equal(transactionStatus, TransactionSynchronization.STATUS_UNKNOWN)
                        && getTask(task.getId()) == null) {
                    return;
                }
                if (StrUtil.isEmpty(task.getAssignee())) {
                    log.error("[processTaskAssigned][taskId({}) 没有分配到负责人]", task.getId());
                    return;
                }
                ProcessInstance processInstance = processInstanceService.getProcessInstance(task.getProcessInstanceId());
                if (processInstance == null) {
                    log.error("[processTaskAssigned][taskId({}) 没有找到流程实例]", task.getId());
                    return;
                }

                // 需要基于 instance 设置租户编号，避免 Flowable 内部异步执行时【例如：超时自动通过】 丢失租户编号
                FlowableUtils.execute(processInstance.getTenantId(), () -> {
                    // 记录审批人为参与人。
                    // ⚠️ 必须放在 FlowableUtils.execute 【之内】：参与表要写 tenant_id，
                    // 而超时自动通过这类 Flowable 异步执行里租户上下文是空的 ——
                    // 写在外面的话这些行会落到 tenant_id=0，之后谁都查不到
                    // （与 README 记的「手写 sys_grant 漏 tenant_id」是同一个坑）
                    instanceParticipantService.recordApprover(task);

                    // 自动去重，通过自动审批的方式
                    BpmProcessDefinitionInfoDO processDefinitionInfo = bpmProcessDefinitionService.getProcessDefinitionInfo(task.getProcessDefinitionId());
                    if (processDefinitionInfo == null) {
                        log.error("[processTaskAssigned][taskId({}) 没有找到流程定义({})]", task.getId(), task.getProcessDefinitionId());
                        return;
                    }
                    if (processDefinitionInfo.getAutoApprovalType() != null) {
                        HistoricTaskInstanceQuery approvedTaskQuery = historyService.createHistoricTaskInstanceQuery()
                                .processInstanceId(task.getProcessInstanceId())
                                .taskVariableValueEquals(BpmnVariableConstants.TASK_VARIABLE_STATUS, BpmTaskStatusEnum.APPROVE.getStatus())
                                .finished();
                        if (BpmAutoApproveTypeEnum.APPROVE_ALL.getType().equals(processDefinitionInfo.getAutoApprovalType())
                                && approvedTaskQuery.taskAssignee(task.getAssignee()).count() > 0) {
                            getSelf().approveTask(Long.valueOf(task.getAssignee()), new BpmTaskApproveReqVO().setId(task.getId())
                                    .setReason(BpmAutoApproveTypeEnum.APPROVE_ALL.getName()));
                            return;
                        }
                        // 连续审批的节点自动通过
                        if (BpmAutoApproveTypeEnum.APPROVE_SEQUENT.getType().equals(processDefinitionInfo.getAutoApprovalType())) {
                            BpmnModel bpmnModel = modelService.getBpmnModelByDefinitionId(processInstance.getProcessDefinitionId());
                            if (bpmnModel == null) {
                                log.error("[processTaskAssigned][taskId({}) 没有找到流程模型({})]", task.getId(), task.getProcessDefinitionId());
                                return;
                            }
                            List<String> sourceTaskIds = convertList(BpmnModelUtils.getElementIncomingUserTaskFlows( // 获取所有的上一个 UserTask 节点连线
                                            BpmnModelUtils.getFlowElementById(bpmnModel, task.getTaskDefinitionKey())),
                                    SequenceFlow::getSourceRef);
                            approvedTaskQuery.taskDefinitionKeys(sourceTaskIds).orderByTaskCreateTime().desc(); // 设置 taskIds, 并按创建时间倒序排序
                            HistoricTaskInstance firstHisTask = CollUtil.getFirst(approvedTaskQuery.list());
                            if (firstHisTask != null && StrUtil.equals(firstHisTask.getAssignee(), task.getAssignee())) {
                                getSelf().approveTask(Long.valueOf(task.getAssignee()), new BpmTaskApproveReqVO().setId(task.getId())
                                        .setReason(BpmAutoApproveTypeEnum.APPROVE_SEQUENT.getName()));
                                return;
                            }
                        }
                    }

                    // 获取发起人节点
                    BpmnModel bpmnModel = modelService.getBpmnModelByDefinitionId(processInstance.getProcessDefinitionId());
                    if (bpmnModel == null) {
                        log.error("[processTaskAssigned][taskId({}) 没有找到流程模型]", task.getId());
                        return;
                    }
                    FlowElement userTaskElement = BpmnModelUtils.getFlowElementById(bpmnModel, task.getTaskDefinitionKey());
                    // 判断是否为退回或者驳回：如果是退回或者驳回不走这个策略
                    Boolean returnTaskFlag = runtimeService.getVariable(processInstance.getProcessInstanceId(),
                            String.format(BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_RETURN_FLAG, task.getTaskDefinitionKey()), Boolean.class);
                    Boolean skipStartUserNodeFlag = Convert.toBool(runtimeService.getVariable(processInstance.getProcessInstanceId(),
                            BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_SKIP_START_USER_NODE, String.class));
                    if (userTaskElement.getId().equals(START_USER_NODE_ID)
                            && (skipStartUserNodeFlag == null // 目的：一般是“主流程”，发起人节点，自动通过审核
                            || BooleanUtil.isTrue(skipStartUserNodeFlag)) // 目的：一般是“子流程”，发起人节点，按配置自动通过审核
                            && ObjUtil.notEqual(returnTaskFlag, Boolean.TRUE)) {
                        getSelf().approveTask(Long.valueOf(task.getAssignee()), new BpmTaskApproveReqVO().setId(task.getId())
                                .setReason(BpmReasonEnum.ASSIGN_START_USER_APPROVE_WHEN_SKIP_START_USER_NODE.getReason()));
                        return;
                    }
                    // 当不为发起人节点时，审批人与提交人为同一人时，根据 BpmUserTaskAssignStartUserHandlerTypeEnum 策略进行处理
                    if (ObjectUtil.notEqual(userTaskElement.getId(), START_USER_NODE_ID)
                            && StrUtil.equals(task.getAssignee(), processInstance.getStartUserId())) {
                        if (ObjUtil.notEqual(returnTaskFlag, Boolean.TRUE)) {
                            Integer assignStartUserHandlerType = BpmnModelUtils.parseAssignStartUserHandlerType(userTaskElement);

                            // 情况一：自动跳过
                            if (ObjectUtils.equalsAny(assignStartUserHandlerType,
                                    BpmUserTaskAssignStartUserHandlerTypeEnum.SKIP.getType())) {
                                getSelf().approveTask(Long.valueOf(task.getAssignee()), new BpmTaskApproveReqVO().setId(task.getId())
                                        .setReason(BpmReasonEnum.ASSIGN_START_USER_APPROVE_WHEN_SKIP.getReason()));
                                return;
                            }
                            // 情况二：转交给部门负责人审批
                            if (ObjectUtils.equalsAny(assignStartUserHandlerType,
                                    BpmUserTaskAssignStartUserHandlerTypeEnum.TRANSFER_DEPT_LEADER.getType())) {
                                AdminUserRespDTO startUser = adminUserApi.getUser(Long.valueOf(processInstance.getStartUserId()));
                                Assert.notNull(startUser, "提交人({})信息为空", processInstance.getStartUserId());
                                DeptRespDTO dept = startUser.getDeptId() != null ? deptApi.getDept(startUser.getDeptId()) : null;
                                Assert.notNull(dept, "提交人({})部门({})信息为空", processInstance.getStartUserId(), startUser.getDeptId());
                                // 找不到部门负责人的情况下，自动审批通过
                                // noinspection DataFlowIssue
                                if (dept.getLeaderUserId() == null) {
                                    getSelf().approveTask(Long.valueOf(task.getAssignee()), new BpmTaskApproveReqVO().setId(task.getId())
                                            .setReason(BpmReasonEnum.ASSIGN_START_USER_APPROVE_WHEN_DEPT_LEADER_NOT_FOUND.getReason()));
                                    return;
                                }
                                // 找得到部门负责人的情况下，修改负责人
                                if (ObjectUtil.notEqual(dept.getLeaderUserId(), startUser.getId())) {
                                    getSelf().transferTask(Long.valueOf(task.getAssignee()), new BpmTaskTransferReqVO()
                                            .setId(task.getId()).setAssigneeUserId(dept.getLeaderUserId())
                                            .setReason(BpmReasonEnum.ASSIGN_START_USER_TRANSFER_DEPT_LEADER.getReason()));
                                    return;
                                }
                                // 如果部门负责人是自己，还是自己审批吧~
                            }
                        }
                    }

                    // 发送消息
                    AdminUserRespDTO startUser = adminUserApi.getUser(Long.valueOf(processInstance.getStartUserId()));
                    messageService.sendMessageWhenTaskAssigned(BpmTaskConvert.INSTANCE.convert(processInstance, startUser, task));
                });
            }

        });
    }

    @Override
    public void processTaskCompleted(Task task) {
        // 任务办结：收口该任务上仍在参与中的参与记录（补 end_time、置不可写）。
        // ⚠️ 必须排在下面两处 early return 之前 —— 流程定义查不到是通知类功能的问题，
        // 不该连带让「这个人已经办完了」这件事没被记下来
        instanceParticipantService.closeTask(task.getId());

        ProcessInstance processInstance = processInstanceService.getProcessInstance(task.getProcessInstanceId());
        if (processInstance == null) {
            log.error("[processTaskCompleted][taskId({}) 没有找到流程实例]", task.getId());
            return;
        }
        BpmProcessDefinitionInfoDO processDefinitionInfo = bpmProcessDefinitionService.
                getProcessDefinitionInfo(processInstance.getProcessDefinitionId());
        if (processDefinitionInfo == null) {
            log.error("[processTaskCompleted][processDefinitionId({}) 没有找到流程定义]", processInstance.getProcessDefinitionId());
            return;
        }

        // 任务后置通知
        if (ObjUtil.isNotNull(processDefinitionInfo.getTaskAfterTriggerSetting())) {
            BpmModelMetaInfoVO.HttpRequestSetting setting = processDefinitionInfo.getTaskAfterTriggerSetting();
            BpmHttpRequestUtils.executeBpmHttpRequest(processInstance,
                    setting.getUrl(), setting.getHeader(), setting.getBody(), true, setting.getResponse());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processTaskTimeout(String processInstanceId, String taskDefineKey, Integer handlerType) {
        ProcessInstance processInstance = processInstanceService.getProcessInstance(processInstanceId);
        if (processInstance == null) {
            log.error("[processTaskTimeout][processInstanceId({}) 没有找到流程实例]", processInstanceId);
            return;
        }
        List<Task> taskList = getRunningTaskListByProcessInstanceId(processInstanceId, true, taskDefineKey);
        // TODO 优化：未来需要考虑加签的情况
        if (CollUtil.isEmpty(taskList)) {
            log.error("[processTaskTimeout][processInstanceId({}) 定义Key({}) 没有找到任务]", processInstanceId, taskDefineKey);
            return;
        }

        taskList.forEach(task -> FlowableUtils.execute(task.getTenantId(), () -> {
            // 情况一：自动提醒
            if (Objects.equals(handlerType, BpmUserTaskTimeoutHandlerTypeEnum.REMINDER.getType())) {
                messageService.sendMessageWhenTaskTimeout(new BpmMessageSendWhenTaskTimeoutReqDTO()
                        .setProcessInstanceId(processInstanceId).setProcessInstanceName(processInstance.getName())
                        .setTaskId(task.getId()).setTaskName(task.getName()).setAssigneeUserId(Long.parseLong(task.getAssignee())));
                return;
            }

            // 情况二：自动同意
            if (Objects.equals(handlerType, BpmUserTaskTimeoutHandlerTypeEnum.APPROVE.getType())) {
                approveTask(Long.parseLong(task.getAssignee()),
                        new BpmTaskApproveReqVO().setId(task.getId()).setReason(BpmReasonEnum.TIMEOUT_APPROVE.getReason()));
                return;
            }

            // 情况三：自动拒绝
            if (Objects.equals(handlerType, BpmUserTaskTimeoutHandlerTypeEnum.REJECT.getType())) {
                rejectTask(Long.parseLong(task.getAssignee()),
                        new BpmTaskRejectReqVO().setId(task.getId()).setReason(BpmReasonEnum.REJECT_TASK.getReason()));
            }
        }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processChildProcessTimeout(String processInstanceId, String taskDefineKey) {
        List<ActivityInstance> activityInstances = runtimeService.createActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .activityId(taskDefineKey).list();
        activityInstances.forEach(activityInstance -> FlowableUtils.execute(activityInstance.getTenantId(),
                () -> moveTaskToEnd(activityInstance.getCalledProcessInstanceId(), BpmReasonEnum.TIMEOUT_APPROVE.getReason())));
    }

    @Override
    public void triggerTask(String processInstanceId, String taskDefineKey) {
        Execution execution = runtimeService.createExecutionQuery()
                .processInstanceId(processInstanceId)
                .activityId(taskDefineKey)
                .singleResult();
        if (execution == null) {
            log.error("[triggerTask][processInstanceId({}) activityId({}) 没有找到执行活动]", processInstanceId, taskDefineKey);
            return;
        }

        // 若存在直接触发接收任务，执行后续节点
        FlowableUtils.execute(execution.getTenantId(),
                () -> runtimeService.trigger(execution.getId()));
    }

    /**
     * 获得自身的代理对象，解决 AOP 生效问题
     *
     * @return 自己
     */
    private BpmTaskServiceImpl getSelf() {
        return SpringUtil.getBean(getClass());
    }

}
