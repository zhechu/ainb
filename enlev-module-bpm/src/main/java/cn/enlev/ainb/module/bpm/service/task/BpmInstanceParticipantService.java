package cn.enlev.ainb.module.bpm.service.task;

import cn.enlev.ainb.module.bpm.dal.dataobject.task.BpmInstanceParticipantDO;
import cn.enlev.ainb.module.bpm.enums.task.BpmParticipationEnum;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;

import java.util.Collection;
import java.util.List;

/**
 * 流程实例参与人 Service 接口
 *
 * <h3>写入时机</h3>
 *
 * 参与关系必须在<b>发生的那一刻</b>落库，事后推导不出来。六个时机：
 *
 * <table border="1">
 *   <tr><th>时机</th><th>入口</th><th>写什么</th></tr>
 *   <tr><td>创建</td><td>{@link #recordStarter}</td><td>发起人</td></tr>
 *   <tr><td>任务分配</td><td>{@link #recordApprover}</td><td>审批人</td></tr>
 *   <tr><td>转办 / 委派 / 加签</td><td>{@link #recordHandover}</td><td><b>原</b>受理人</td></tr>
 *   <tr><td>抄送</td><td>{@link #recordCopy}</td><td>被抄送人</td></tr>
 *   <tr><td>任务办结</td><td>{@link #closeTask}</td><td>补 end_time</td></tr>
 *   <tr><td>流程办结</td><td>{@link #closeInstance}</td><td>补 end_time</td></tr>
 * </table>
 *
 * <h3>⚠️ 转办 / 委派 / 加签只记「原受理人」，不记新受理人</h3>
 *
 * 这三个动作最终都会调到 Flowable 的 {@code setAssignee} / {@code delegateTask}，
 * 而那会触发任务分配事件 → {@link #recordApprover} 自动把<b>新</b>受理人记下来。
 *
 * 在这里再记一次新受理人的话，同一个人在同一个任务上会出现两行，
 * 且两行的 {@code participation} 还不一样 —— 后面判 writable 时以哪行为准就说不清了。
 *
 * @author 走进新时代
 */
public interface BpmInstanceParticipantService {

    /**
     * 记录发起人（流程实例创建时）
     *
     * @param instance 流程实例
     */
    void recordStarter(ProcessInstance instance);

    /**
     * 记录审批人（任务分配时）
     *
     * @param task 任务
     */
    void recordApprover(Task task);

    /**
     * 记录任务移交出去的<b>原</b>受理人（转办 / 委派 / 加签）
     *
     * @param processInstanceId 流程实例编号
     * @param taskId            任务编号
     * @param activityId        活动编号，可为空
     * @param fromUserId        原受理人
     * @param participation     参与方式，只接受
     *                          {@link BpmParticipationEnum#TRANSFER_FROM} /
     *                          {@link BpmParticipationEnum#DELEGATE_FROM} /
     *                          {@link BpmParticipationEnum#SIGN_FROM}
     */
    void recordHandover(String processInstanceId, String taskId, String activityId,
                        Long fromUserId, BpmParticipationEnum participation);

    /**
     * 记录抄送人
     *
     * @param processInstanceId 流程实例编号
     * @param taskId            任务编号，可为空
     * @param activityId        活动编号，可为空（仿钉钉抄送节点只有 activityId）
     * @param userIds           被抄送人
     */
    void recordCopy(String processInstanceId, String taskId, String activityId, Collection<Long> userIds);

    /**
     * 任务办结：收口该任务上仍在参与中的记录
     *
     * @param taskId 任务编号
     */
    void closeTask(String taskId);

    /**
     * 流程办结：收口整条实例上仍在参与中的记录
     *
     * @param processInstanceId 流程实例编号
     */
    void closeInstance(String processInstanceId);

    /**
     * 该用户是不是这条实例的参与人
     *
     * ⚠️ 判定会<b>上溯祖先实例</b>：父流程的参与人能看子流程（v9 §4.3）。
     * 但不会横向扩散到兄弟子流程
     *
     * @param processInstanceId 流程实例编号
     * @param userId            用户编号
     */
    boolean isParticipant(String processInstanceId, Long userId);

    /**
     * 查一条主单据下的全部参与人（含各层子流程）
     *
     * @param rootProcessInstanceId 顶层流程实例编号
     */
    List<BpmInstanceParticipantDO> getParticipantsByRoot(String rootProcessInstanceId);

    /**
     * 查某个用户在某张表单下参与过的全部记录
     *
     * 派生可见性（S5c）的取数口
     *
     * @param userId   用户编号
     * @param formCode 表单编码，可为空表示不限
     */
    List<BpmInstanceParticipantDO> getParticipantsByUserIdAndFormCode(Long userId, String formCode);

}
