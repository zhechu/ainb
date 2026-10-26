package cn.enlev.ainb.module.bpm.service.task;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import cn.enlev.ainb.module.bpm.dal.dataobject.task.BpmInstanceParticipantDO;
import cn.enlev.ainb.module.bpm.dal.mysql.task.BpmInstanceParticipantMapper;
import cn.enlev.ainb.module.bpm.enums.task.BpmParticipationEnum;
import cn.enlev.ainb.module.bpm.service.definition.BpmProcessDefinitionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 流程实例参与人 Service 实现
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class BpmInstanceParticipantServiceImpl implements BpmInstanceParticipantService {

    /**
     * 祖先链的最大上溯深度
     *
     * 正常的子流程嵌套不会超过个位数。设上限不是为了性能，而是<b>兜住脏数据</b>：
     * 父子关系一旦成环（历史库被手工改过、迁移脚本写错），不设限就是死循环，
     * 表现为某个接口整个线程池被占满
     */
    private static final int MAX_LINEAGE_DEPTH = 16;

    @Resource
    private BpmInstanceParticipantMapper participantMapper;
    @Resource
    private RuntimeService runtimeService;
    @Resource
    private HistoryService historyService;
    @Resource
    private BpmProcessDefinitionService processDefinitionService;

    @Override
    public void recordStarter(ProcessInstance instance) {
        if (instance == null || StrUtil.isBlank(instance.getStartUserId())) {
            return;
        }
        Long starterId = parseUserId(instance.getStartUserId());
        if (starterId == null) {
            return;
        }
        // 发起人这条是整条实例的第一行，血缘直接从 instance 上取 ——
        // 此时运行态一定还在，getRootProcessInstanceId() 可用，不必走上溯
        InstanceContext context = buildContextFromInstance(instance);
        saveIfAbsent(context, null, null, starterId, BpmParticipationEnum.STARTER);
    }

    @Override
    public void recordApprover(Task task) {
        if (task == null || StrUtil.isBlank(task.getAssignee())) {
            return;
        }
        Long assigneeId = parseUserId(task.getAssignee());
        if (assigneeId == null) {
            return;
        }
        InstanceContext context = resolveContext(task.getProcessInstanceId());
        if (context == null) {
            return;
        }
        saveIfAbsent(context, task.getId(), task.getTaskDefinitionKey(), assigneeId,
                BpmParticipationEnum.APPROVER);
    }

    @Override
    public void recordHandover(String processInstanceId, String taskId, String activityId,
                               Long fromUserId, BpmParticipationEnum participation) {
        if (StrUtil.isBlank(processInstanceId) || fromUserId == null || participation == null) {
            return;
        }
        // 只接受三种移交方式。传错的话写进去的是一条语义不明的记录，
        // 而它会静默影响后续的可写性判定 —— 宁可当场拒绝
        if (!participation.isHandover()) {
            log.error("[recordHandover][参与方式({})不是任务移交，已忽略。实例({})任务({})]",
                    participation, processInstanceId, taskId);
            return;
        }
        InstanceContext context = resolveContext(processInstanceId);
        if (context == null) {
            return;
        }
        saveIfAbsent(context, taskId, activityId, fromUserId, participation);
    }

    @Override
    public void recordCopy(String processInstanceId, String taskId, String activityId,
                           Collection<Long> userIds) {
        if (StrUtil.isBlank(processInstanceId) || CollUtil.isEmpty(userIds)) {
            return;
        }
        InstanceContext context = resolveContext(processInstanceId);
        if (context == null) {
            return;
        }
        for (Long userId : userIds) {
            if (userId != null) {
                saveIfAbsent(context, taskId, activityId, userId, BpmParticipationEnum.COPY);
            }
        }
    }

    @Override
    public void closeTask(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            return;
        }
        participantMapper.closeByTaskId(taskId, LocalDateTime.now());
    }

    @Override
    public void closeInstance(String processInstanceId) {
        if (StrUtil.isBlank(processInstanceId)) {
            return;
        }
        participantMapper.closeByProcessInstanceId(processInstanceId, LocalDateTime.now());
    }

    @Override
    public boolean isParticipant(String processInstanceId, Long userId) {
        if (StrUtil.isBlank(processInstanceId) || userId == null) {
            return false;
        }
        // ⚠️ 三条规则统一用一个口径：【被查看的那条实例】的配置说了算。
        //
        // 换句话说，「谁被看，谁做主」。用查看者那边的配置也说得通，
        // 但两边混用必然出现「同一对父子，从上往下看和从下往上看结论不一致」——
        // 那种不一致没人能凭直觉推出来，排查时只能读代码。
        // 所以宁可统一成一个略显武断的规则，也不要两套。

        // 1. 直接参与：他就在这条实例上。不受任何可见性配置影响 ——
        //    配置管的是「跨实例能不能看」，而他根本没跨
        if (participantMapper.existsByProcessInstanceIdsAndUserId(Set.of(processInstanceId), userId)) {
            return true;
        }
        BpmModelMetaInfoVO.ChildProcessVisibilitySetting setting =
                resolveVisibilitySetting(processInstanceId);

        // 2. 祖先链：父流程的参与人看这条子流程（parentCanViewChild，默认可见）
        //    ⚠️ 不能用 root 一把查 —— 那会把兄弟子流程也放开
        if (isEnabled(setting == null ? null : setting.getParentCanViewChild(), true)) {
            Set<String> ancestors = resolveAncestorChain(processInstanceId);
            ancestors.remove(processInstanceId); // 第 1 步已经查过自己
            if (!ancestors.isEmpty()
                    && participantMapper.existsByProcessInstanceIdsAndUserId(ancestors, userId)) {
                return true;
            }
        }

        // 3. 子孙链：子流程的参与人看这条父流程（childCanViewParent，默认可见）
        //    否则审批人不知道自己在审什么的上游
        if (isEnabled(setting == null ? null : setting.getChildCanViewParent(), true)) {
            Set<String> descendants = resolveDescendants(processInstanceId);
            if (!descendants.isEmpty()
                    && participantMapper.existsByProcessInstanceIdsAndUserId(descendants, userId)) {
                return true;
            }
        }

        // 4. 兄弟：同一主单据下的其它子流程（childCanViewSibling，⚠️ 默认【不】可见）
        //    开启它等于让同一主单据下所有子流程互相透明，v9 说这一条几乎不该开。
        //    默认关闭意味着下面这段查询正常情况下根本不会执行
        if (isEnabled(setting == null ? null : setting.getChildCanViewSibling(), false)) {
            InstanceContext context = resolveContext(processInstanceId);
            if (context != null && StrUtil.isNotBlank(context.rootProcessInstanceId())) {
                Set<String> sameRoot = new LinkedHashSet<>(
                        participantMapper.selectInstanceIdsByRoot(context.rootProcessInstanceId()));
                sameRoot.remove(processInstanceId);
                if (!sameRoot.isEmpty()
                        && participantMapper.existsByProcessInstanceIdsAndUserId(sameRoot, userId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 可见性开关的取值
     *
     * ⚠️ null 表示「没配过」→ 走默认值，而不是 false。
     * 当成 false 的话，所有没配过这一项的存量流程会在升级瞬间失去父子可见性 ——
     * 那不是收紧权限，是功能下线
     */
    private boolean isEnabled(Boolean configured, boolean defaultValue) {
        return configured == null ? defaultValue : configured;
    }

    /**
     * 取某条实例所属流程的父子可见性配置
     *
     * ⚠️ 读不到时返回 null（由 {@link #isEnabled} 走默认值），不能因此判否
     */
    private BpmModelMetaInfoVO.ChildProcessVisibilitySetting resolveVisibilitySetting(
            String processInstanceId) {
        try {
            BpmProcessDefinitionInfoDO info = resolveDefinitionInfo(processInstanceId);
            return info == null ? null : info.getChildProcessVisibilitySetting();
        } catch (Exception e) {
            log.warn("[resolveVisibilitySetting][实例({})的可见性配置读取失败，按默认处理]",
                    processInstanceId, e);
            return null;
        }
    }

    /**
     * 求一条实例的全部子孙实例
     *
     * 逐层向下展开。深度上限与祖先链一致 —— 同样是为了兜住父子关系成环的脏数据
     */
    private Set<String> resolveDescendants(String processInstanceId) {
        Set<String> result = new LinkedHashSet<>();
        Set<String> frontier = new LinkedHashSet<>(List.of(processInstanceId));
        for (int depth = 0; depth < MAX_LINEAGE_DEPTH && !frontier.isEmpty(); depth++) {
            Set<String> next = new LinkedHashSet<>();
            for (String current : frontier) {
                for (String child : participantMapper.selectChildInstanceIds(current)) {
                    if (result.add(child)) {
                        next.add(child);
                    }
                }
            }
            frontier = next;
        }
        return result;
    }

    private BpmProcessDefinitionInfoDO resolveDefinitionInfo(String processInstanceId) {
        ProcessInstance running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (running != null) {
            return processDefinitionService.getProcessDefinitionInfo(running.getProcessDefinitionId());
        }
        HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        return historic == null ? null
                : processDefinitionService.getProcessDefinitionInfo(historic.getProcessDefinitionId());
    }

    @Override
    public List<BpmInstanceParticipantDO> getParticipantsByRoot(String rootProcessInstanceId) {
        if (StrUtil.isBlank(rootProcessInstanceId)) {
            return List.of();
        }
        return participantMapper.selectListByRootProcessInstanceId(rootProcessInstanceId);
    }

    @Override
    public List<BpmInstanceParticipantDO> getParticipantsByUserIdAndFormCode(Long userId, String formCode) {
        if (userId == null) {
            return List.of();
        }
        return participantMapper.selectListByUserIdAndFormCode(userId, formCode);
    }

    // ==================== 内部方法 ====================

    /**
     * 写入一条参与记录，已存在则跳过
     *
     * 去重而不是直接插：Flowable 的事件在重试、补偿、自动通过等场景下会重复触发，
     * 不去重的话同一个人在同一个任务上会累积出多行，
     * 而「已办」列表按参与记录展开 —— 表现是同一条单子在列表里出现好几次
     */
    private void saveIfAbsent(InstanceContext context, String taskId, String activityId,
                              Long userId, BpmParticipationEnum participation) {
        BpmInstanceParticipantDO existing = participantMapper.selectByTaskAndUserAndParticipation(
                context.processInstanceId(), taskId, userId, participation.getParticipation());
        if (existing != null) {
            return;
        }
        participantMapper.insert(BpmInstanceParticipantDO.builder()
                .processInstanceId(context.processInstanceId())
                .rootProcessInstanceId(context.rootProcessInstanceId())
                .parentProcessInstanceId(context.parentProcessInstanceId())
                .formCode(context.formCode())
                .businessKey(context.businessKey())
                .userId(userId)
                .participation(participation.getParticipation())
                .taskId(taskId)
                .activityId(activityId)
                .writable(participation.isDefaultWritable())
                .startTime(LocalDateTime.now())
                .build());
    }

    /**
     * 解析一条实例的血缘与业务标识
     *
     * 三级回落，代价从低到高：
     * <ol>
     *   <li><b>本表已有记录</b> —— 同一条实例的血缘是固定的，第一行写入时已经算过</li>
     *   <li><b>运行态</b> —— {@code ProcessInstance.getRootProcessInstanceId()} 直接可用</li>
     *   <li><b>历史态</b> —— ⚠️ {@code HistoricProcessInstance} <b>没有</b>
     *       {@code getRootProcessInstanceId()}，只能沿 {@code superProcessInstanceId}
     *       一层层上溯，深度 N 就是 N 次查询</li>
     * </ol>
     *
     * 第 1 级不只是省查询，更是让「流程已办结后补记参与人」这件事仍然拿得到正确的 root
     */
    private InstanceContext resolveContext(String processInstanceId) {
        if (StrUtil.isBlank(processInstanceId)) {
            return null;
        }
        // 1. 本表已算过
        BpmInstanceParticipantDO existing = participantMapper.selectFirstByProcessInstanceId(processInstanceId);
        if (existing != null) {
            return new InstanceContext(processInstanceId, existing.getRootProcessInstanceId(),
                    existing.getParentProcessInstanceId(), existing.getFormCode(), existing.getBusinessKey());
        }
        // 2. 运行态
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (instance != null) {
            return buildContextFromInstance(instance);
        }
        // 3. 历史态
        HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (historic == null) {
            log.warn("[resolveContext][流程实例({})在运行态与历史态都查不到，参与人未记录]", processInstanceId);
            return null;
        }
        String parentId = historic.getSuperProcessInstanceId();
        return new InstanceContext(processInstanceId,
                resolveRootByWalkingUp(processInstanceId, parentId), parentId,
                resolveFormCode(historic.getProcessDefinitionId()), historic.getBusinessKey());
    }

    private InstanceContext buildContextFromInstance(ProcessInstance instance) {
        // 顶层实例的 root 补成自身，而不是留 null（理由见 DO 的字段注释）
        String rootId = StrUtil.blankToDefault(instance.getRootProcessInstanceId(), instance.getId());
        return new InstanceContext(instance.getId(), rootId,
                resolveParentByExecution(instance.getSuperExecutionId()),
                resolveFormCode(instance.getProcessDefinitionId()), instance.getBusinessKey());
    }

    /**
     * 由 superExecutionId 反查父流程实例编号
     *
     * Flowable 的父子关系挂在 <b>execution</b> 上而不是 instance 上，
     * 所以要多一跳。与 {@code BpmProcessInstanceServiceImpl#processProcessInstanceCompleted}
     * 里处理子流程拒绝时是同一个路数
     */
    private String resolveParentByExecution(String superExecutionId) {
        if (StrUtil.isBlank(superExecutionId)) {
            return null;
        }
        Execution execution = runtimeService.createExecutionQuery()
                .executionId(superExecutionId).singleResult();
        return execution == null ? null : execution.getProcessInstanceId();
    }

    /**
     * 历史态下沿父链上溯求 root
     *
     * 最后一个没有父实例的祖先就是 root。自身没有父实例时 root 是它自己
     */
    private String resolveRootByWalkingUp(String selfId, String parentId) {
        if (StrUtil.isBlank(parentId)) {
            return selfId;
        }
        String current = parentId;
        for (int depth = 0; depth < MAX_LINEAGE_DEPTH; depth++) {
            HistoricProcessInstance parent = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(current).singleResult();
            if (parent == null || StrUtil.isBlank(parent.getSuperProcessInstanceId())) {
                return current;
            }
            current = parent.getSuperProcessInstanceId();
        }
        log.error("[resolveRootByWalkingUp][实例({})上溯超过{}层仍未到顶，疑似父子关系成环，"
                + "按当前层({})当作 root]", selfId, MAX_LINEAGE_DEPTH, current);
        return current;
    }

    /**
     * 求一条实例的祖先链，含自身
     *
     * 顺序是「自己 → 父 → 祖父 …」。只用于 {@link #isParticipant} 的 IN 查询，
     * 所以顺序本身不重要，去重才重要（成环时靠 Set 兜底）
     */
    private Set<String> resolveAncestorChain(String processInstanceId) {
        Set<String> chain = new LinkedHashSet<>();
        String current = processInstanceId;
        for (int depth = 0; depth < MAX_LINEAGE_DEPTH && StrUtil.isNotBlank(current); depth++) {
            if (!chain.add(current)) {
                log.error("[resolveAncestorChain][实例({})的祖先链成环，已在({})处截断]",
                        processInstanceId, current);
                break;
            }
            InstanceContext context = resolveContext(current);
            if (context == null) {
                break;
            }
            current = context.parentProcessInstanceId();
        }
        return chain;
    }

    private String resolveFormCode(String processDefinitionId) {
        if (StrUtil.isBlank(processDefinitionId)) {
            return null;
        }
        BpmProcessDefinitionInfoDO info = processDefinitionService.getProcessDefinitionInfo(processDefinitionId);
        return info == null ? null : info.getFormDesignCode();
    }

    /**
     * Flowable 里的用户标识是字符串，本系统用的是数字主键
     *
     * 解析不出来时返回 null 并留下日志，而不是抛异常 ——
     * 参与人记录失败不该让审批本身失败（与 S4c 审计同一条取舍）
     */
    private Long parseUserId(String userId) {
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException e) {
            log.warn("[parseUserId][用户标识({})不是数字，参与人未记录]", userId);
            return null;
        }
    }

    /**
     * 一条实例的血缘与业务标识
     *
     * 五个值总是一起取、一起用，单独传参的话六个写入时机各传五个参数，
     * 漏传一个就是一列静默为空
     */
    private record InstanceContext(String processInstanceId,
                                   String rootProcessInstanceId,
                                   String parentProcessInstanceId,
                                   String formCode,
                                   String businessKey) {
    }

}
