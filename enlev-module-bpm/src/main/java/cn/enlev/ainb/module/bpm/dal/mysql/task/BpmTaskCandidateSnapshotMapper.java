package cn.enlev.ainb.module.bpm.dal.mysql.task;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.bpm.dal.dataobject.task.BpmTaskCandidateSnapshotDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 任务候选人快照 Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface BpmTaskCandidateSnapshotMapper extends BaseMapperX<BpmTaskCandidateSnapshotDO> {

    default List<BpmTaskCandidateSnapshotDO> selectListByTaskId(String taskId) {
        return selectList(BpmTaskCandidateSnapshotDO::getTaskId, taskId);
    }

    default List<BpmTaskCandidateSnapshotDO> selectListByProcessInstanceId(String processInstanceId) {
        return selectList(BpmTaskCandidateSnapshotDO::getProcessInstanceId, processInstanceId);
    }

    /**
     * 查某个用户被算成候选人的全部快照
     *
     * 离职交接的入口：先找出「他还担着哪些在办任务的候选人身份」，再逐条重算
     */
    default List<BpmTaskCandidateSnapshotDO> selectListByResolvedUserId(Long userId) {
        return selectList(BpmTaskCandidateSnapshotDO::getResolvedUserId, userId);
    }

    /**
     * 判断某条快照是否已存在
     *
     * 幂等去重。候选人计算会被重复触发（Flowable 事件重试、并行网关多分支），
     * 不去重的话同一个任务同一个人会累积出多行
     */
    default BpmTaskCandidateSnapshotDO selectByTaskAndSubjectAndUser(
            String taskId, String subjectType, Long subjectRefId, Long resolvedUserId) {
        return selectOne(new LambdaQueryWrapperX<BpmTaskCandidateSnapshotDO>()
                .eq(BpmTaskCandidateSnapshotDO::getTaskId, taskId)
                .eq(BpmTaskCandidateSnapshotDO::getSubjectType, subjectType)
                .eqIfPresent(BpmTaskCandidateSnapshotDO::getSubjectRefId, subjectRefId)
                .eq(BpmTaskCandidateSnapshotDO::getResolvedUserId, resolvedUserId));
    }

    default void deleteByTaskId(String taskId) {
        delete(BpmTaskCandidateSnapshotDO::getTaskId, taskId);
    }

}
