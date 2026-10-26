package cn.enlev.ainb.module.bpm.service.task;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.bpm.dal.dataobject.task.BpmTaskCandidateSnapshotDO;
import cn.enlev.ainb.module.bpm.dal.mysql.task.BpmTaskCandidateSnapshotMapper;
import cn.enlev.ainb.module.bpm.framework.flowable.core.candidate.BpmCandidateSubjectTypes;
import cn.enlev.ainb.module.bpm.framework.flowable.core.candidate.BpmTaskCandidateInvoker;
import cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmTaskCandidateStrategyEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 任务候选人快照 Service 实现
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class BpmTaskCandidateSnapshotServiceImpl implements BpmTaskCandidateSnapshotService {

    @Resource
    private BpmTaskCandidateSnapshotMapper snapshotMapper;

    @Resource
    @Lazy // 候选人计算会回调本服务做快照，直接注入会成环
    private BpmTaskCandidateInvoker candidateInvoker;

    @Override
    public void snapshot(String processInstanceId, String taskId, String activityId,
                         Integer strategy, String param, Collection<Long> userIds) {
        if (StrUtil.isBlank(processInstanceId) || CollUtil.isEmpty(userIds)) {
            return;
        }
        BpmTaskCandidateStrategyEnum strategyEnum = BpmTaskCandidateStrategyEnum.valueOf(strategy);
        String subjectType = BpmCandidateSubjectTypes.of(strategyEnum);
        Long subjectRefId = parseSubjectRefId(subjectType, param);
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            if (snapshotMapper.selectByTaskAndSubjectAndUser(taskId, subjectType, subjectRefId, userId) != null) {
                continue;
            }
            snapshotMapper.insert(BpmTaskCandidateSnapshotDO.builder()
                    .processInstanceId(processInstanceId)
                    .taskId(taskId)
                    .activityId(activityId)
                    .strategy(strategy)
                    .param(param)
                    .subjectType(subjectType)
                    .subjectRefId(subjectRefId)
                    .resolvedUserId(userId)
                    .snapshotTime(LocalDateTime.now())
                    .build());
        }
    }

    @Override
    public List<BpmTaskCandidateSnapshotDO> getSnapshotsByTaskId(String taskId) {
        return StrUtil.isBlank(taskId) ? List.of() : snapshotMapper.selectListByTaskId(taskId);
    }

    @Override
    public List<RecalculateItem> recalculateByUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<BpmTaskCandidateSnapshotDO> snapshots = snapshotMapper.selectListByResolvedUserId(userId);
        List<RecalculateItem> result = new ArrayList<>(snapshots.size());
        for (BpmTaskCandidateSnapshotDO snapshot : snapshots) {
            result.add(recalculateOne(snapshot, userId));
        }
        return result;
    }

    private RecalculateItem recalculateOne(BpmTaskCandidateSnapshotDO snapshot, Long leavingUserId) {
        // 1. 来源不稳定的一律不重算。
        //    表达式依赖流程变量、自选依赖当时那个人的选择 ——
        //    离开当时的上下文重算出来的结果是错的，给出个「看似有依据」的错误接手人
        //    比直接说「需要人工指定」危险得多
        if (!BpmCandidateSubjectTypes.recalculable(snapshot.getSubjectType())) {
            return new RecalculateItem(snapshot, false, List.of(),
                    "候选人来自表达式/表单字段/运行时自选，脱离当时的流程上下文无法重算，请人工指定接手人");
        }
        // 2. 点名到人的也不能重算 —— 「张三」这个参数里推不出任何接手人
        if (BpmCandidateSubjectTypes.USER.equals(snapshot.getSubjectType())) {
            return new RecalculateItem(snapshot, false, List.of(),
                    "候选人是被直接点名的，没有任何规则能推出接手人，请人工指定");
        }
        // 3. 角色/部门/岗位/用户组：按原表达式重算一次，看现在是谁
        try {
            BpmTaskCandidateStrategyEnum strategyEnum =
                    BpmTaskCandidateStrategyEnum.valueOf(snapshot.getStrategy());
            List<Long> users = new ArrayList<>(
                    candidateInvoker.calculateUsersByParam(strategyEnum, snapshot.getParam()));
            users.remove(leavingUserId); // 接手人里不该再有离职的那个人
            if (users.isEmpty()) {
                return new RecalculateItem(snapshot, false, List.of(),
                        "按原规则重算后没有其他候选人（该角色/部门/岗位目前只有他一个人），请人工指定");
            }
            return new RecalculateItem(snapshot, true, users,
                    "按原规则重算得出，来源：" + snapshot.getSubjectType());
        } catch (Exception e) {
            // 重算失败不能让整个交接流程中断 —— 其余条目还要继续算
            log.warn("[recalculateOne][快照({})重算失败]", snapshot.getId(), e);
            return new RecalculateItem(snapshot, false, List.of(),
                    "按原规则重算失败：" + e.getMessage() + "，请人工指定");
        }
    }

    /**
     * 从候选参数里解析主体编号
     *
     * 角色/部门/岗位/用户组策略的参数就是对应的编号（多个用逗号分隔）。
     * 取第一个即可 —— 主体编号只用于「他为什么是审批人」的归因展示，
     * 精确到哪一个角色不影响重算（重算用的是完整的 param）
     */
    private Long parseSubjectRefId(String subjectType, String param) {
        if (BpmCandidateSubjectTypes.DYNAMIC.equals(subjectType) || StrUtil.isBlank(param)) {
            return null;
        }
        String first = StrUtil.subBefore(param, ",", false);
        try {
            return Long.valueOf(StrUtil.trim(first));
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
