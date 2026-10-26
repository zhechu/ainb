package cn.enlev.ainb.module.bpm.service.task;

import cn.enlev.ainb.module.bpm.dal.dataobject.task.BpmTaskCandidateSnapshotDO;

import java.util.Collection;
import java.util.List;

/**
 * 任务候选人快照 Service
 *
 * <h3>它解决的问题</h3>
 *
 * 候选人现在是「算完就扔」的 —— 策略算出一组 userId 交给 Flowable，算式本身不留痕。
 * 于是「他为什么是审批人」「张三离职后这些任务该给谁」都无从回答。
 *
 * 本服务把<b>表达式</b>（策略 + 参数）与<b>快照</b>（当时算出的主体与人）一起存下来。
 *
 * @author 走进新时代
 */
public interface BpmTaskCandidateSnapshotService {

    /**
     * 记录一次候选人计算的结果
     *
     * 幂等：同一任务、同一主体、同一人只留一行。
     * 候选人计算会被重复触发（Flowable 事件重试、并行网关多分支），不去重会累积出多行。
     *
     * @param processInstanceId 流程实例编号
     * @param taskId            任务编号，可为空（按活动预算时没有）
     * @param activityId        活动编号
     * @param strategy          候选策略
     * @param param             候选参数
     * @param userIds           本次算出的候选用户
     */
    void snapshot(String processInstanceId, String taskId, String activityId,
                  Integer strategy, String param, Collection<Long> userIds);

    /**
     * 查某个任务的候选人快照
     */
    List<BpmTaskCandidateSnapshotDO> getSnapshotsByTaskId(String taskId);

    /**
     * 离职交接：算出某个用户名下待处理的候选人身份，并给出重算建议
     *
     * <h3>⚠️ 只产出建议，不自动改任务</h3>
     *
     * 快照是既成事实。因为组织变了就悄悄把别人的待办挪走，表现是
     * 「我昨天看到的单子今天不见了」—— 比不处理更糟。
     * 所以这里只回答「哪些任务受影响、能不能自动算出接手人」，
     * 真正的转办由人确认后走 {@code transferTask}。
     *
     * @param userId 离职/交接的用户编号
     * @return 每条待处理的候选人身份及其重算结果
     */
    List<RecalculateItem> recalculateByUser(Long userId);

    /**
     * 单条重算结果
     *
     * @param snapshot       原快照
     * @param recalculable   能否自动重算（表达式 / 表单字段 / 运行时自选不能，见
     *                       {@code BpmCandidateSubjectTypes.DYNAMIC}）
     * @param suggestedUsers 重算出的接手人；不可重算时为空
     * @param reason         给人看的说明，解释为什么可以/不可以自动重算
     */
    record RecalculateItem(BpmTaskCandidateSnapshotDO snapshot, boolean recalculable,
                           List<Long> suggestedUsers, String reason) {
    }

}
