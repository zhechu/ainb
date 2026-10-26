package cn.enlev.ainb.module.bpm.dal.mysql.task;

import cn.hutool.core.collection.CollUtil;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.bpm.dal.dataobject.task.BpmInstanceParticipantDO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 流程实例参与人 Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface BpmInstanceParticipantMapper extends BaseMapperX<BpmInstanceParticipantDO> {

    /**
     * 查询某条实例上某个用户的参与记录
     *
     * 可能有多条 —— 同一个人可以既是发起人又是某个节点的审批人
     */
    default List<BpmInstanceParticipantDO> selectListByProcessInstanceIdAndUserId(
            String processInstanceId, Long userId) {
        return selectList(new LambdaQueryWrapperX<BpmInstanceParticipantDO>()
                .eq(BpmInstanceParticipantDO::getProcessInstanceId, processInstanceId)
                .eq(BpmInstanceParticipantDO::getUserId, userId));
    }

    /**
     * 该用户是不是这批实例中任意一条的参与人
     *
     * ⚠️ 入参是<b>实例集合</b>而不是单个实例，是为了让调用方能一次性传入
     * 「目标实例 + 它的全部祖先实例」。v9 §4.3 定的可见性里
     * 「父流程参与者能看子流程」正是这个形状：用户的参与记录挂在<b>父</b>实例上，
     * 而要判定的是<b>子</b>实例。
     *
     * 反过来用 {@code root_process_instance_id} 一把查会<b>过度放开</b> ——
     * 那样「子流程参与者」连同一主单据下<b>其它兄弟子流程</b>都能看见，
     * 而 v9 明确规定那是不可见的
     */
    default boolean existsByProcessInstanceIdsAndUserId(Collection<String> processInstanceIds, Long userId) {
        if (processInstanceIds == null || processInstanceIds.isEmpty() || userId == null) {
            return false;
        }
        return selectCount(new LambdaQueryWrapperX<BpmInstanceParticipantDO>()
                .in(BpmInstanceParticipantDO::getProcessInstanceId, processInstanceIds)
                .eq(BpmInstanceParticipantDO::getUserId, userId)) > 0;
    }

    /**
     * 取这条实例上任意一条参与记录
     *
     * 用途是<b>复用已经算好的血缘</b>：同一条实例的 root/parent 是固定的，
     * 第一条记录写入时已经从 Flowable 解析过，后续时机直接读这一行即可，
     * 不必每次都再去 runtime/history 查一遍（历史态取 root 还要沿父链上溯 N 次）
     */
    default BpmInstanceParticipantDO selectFirstByProcessInstanceId(String processInstanceId) {
        return selectFirstOne(BpmInstanceParticipantDO::getProcessInstanceId, processInstanceId);
    }

    /**
     * 判断某条参与记录是否已存在
     *
     * 幂等去重用。Flowable 的事件监听在重试、补偿等场景下会重复触发，
     * 不去重的话同一个人在同一个任务上会累积出多行
     */
    default BpmInstanceParticipantDO selectByTaskAndUserAndParticipation(
            String processInstanceId, String taskId, Long userId, Integer participation) {
        // ⚠️ taskId 用 eqIfPresent：发起人与抄送节点没有 taskId，
        // 此时按「实例 + 人 + 参与方式」判重，这恰好就是它们的唯一性口径
        return CollUtil.getFirst(selectList(new LambdaQueryWrapperX<BpmInstanceParticipantDO>()
                .eq(BpmInstanceParticipantDO::getProcessInstanceId, processInstanceId)
                .eqIfPresent(BpmInstanceParticipantDO::getTaskId, taskId)
                .eq(BpmInstanceParticipantDO::getUserId, userId)
                .eq(BpmInstanceParticipantDO::getParticipation, participation)));
    }

    /**
     * 任务办结：把该任务上仍在参与中的记录收口
     *
     * 只收 {@code end_time IS NULL} 的行 —— 已经收过的不要再动，
     * 否则重复触发会把结束时间一路往后刷，「已办」列表的排序就乱了
     */
    default int closeByTaskId(String taskId, LocalDateTime endTime) {
        return update(null, Wrappers.<BpmInstanceParticipantDO>lambdaUpdate()
                .set(BpmInstanceParticipantDO::getEndTime, endTime)
                .set(BpmInstanceParticipantDO::getWritable, false)
                .eq(BpmInstanceParticipantDO::getTaskId, taskId)
                .isNull(BpmInstanceParticipantDO::getEndTime));
    }

    /**
     * 流程办结：把整条实例上仍在参与中的记录全部收口
     *
     * ⚠️ 收的是 {@code end_time}，<b>不是删除</b>。参与关系是「他曾经看得到这张单子」的凭据，
     * 删掉的话已办结流程的参与人会立刻失去可见性 —— 表现是
     * 「昨天还能查到的历史审批，今天打不开了」
     */
    default int closeByProcessInstanceId(String processInstanceId, LocalDateTime endTime) {
        return update(null, Wrappers.<BpmInstanceParticipantDO>lambdaUpdate()
                .set(BpmInstanceParticipantDO::getEndTime, endTime)
                .set(BpmInstanceParticipantDO::getWritable, false)
                .eq(BpmInstanceParticipantDO::getProcessInstanceId, processInstanceId)
                .isNull(BpmInstanceParticipantDO::getEndTime));
    }

    /**
     * 查某个用户在某张表单下参与过的全部业务主键
     *
     * 派生可见性（S5c）的取数口：「他参与过哪些流程」→「因此能看业务表的哪几行」
     */
    default List<BpmInstanceParticipantDO> selectListByUserIdAndFormCode(Long userId, String formCode) {
        return selectList(new LambdaQueryWrapperX<BpmInstanceParticipantDO>()
                .eq(BpmInstanceParticipantDO::getUserId, userId)
                .eqIfPresent(BpmInstanceParticipantDO::getFormCode, formCode));
    }

    /**
     * 查一条实例下的全部参与人（含各层子流程）
     *
     * 这是 root 冗余字段唯一无可替代的用途：主单据视角的「参与人全景」
     */
    default List<BpmInstanceParticipantDO> selectListByRootProcessInstanceId(String rootProcessInstanceId) {
        return selectList(BpmInstanceParticipantDO::getRootProcessInstanceId, rootProcessInstanceId);
    }

    /**
     * 查某条实例的【直接子实例】编号
     *
     * 「子流程参与人能看父流程」要往下找，而祖先链只能往上。
     * 深层嵌套由调用方迭代 —— 嵌套深度是个位数，迭代次数可忽略
     */
    default List<String> selectChildInstanceIds(String parentProcessInstanceId) {
        return selectList(new LambdaQueryWrapperX<BpmInstanceParticipantDO>()
                .eq(BpmInstanceParticipantDO::getParentProcessInstanceId, parentProcessInstanceId)
                .select(BpmInstanceParticipantDO::getProcessInstanceId))
                .stream().map(BpmInstanceParticipantDO::getProcessInstanceId).distinct().toList();
    }

    /**
     * 查同一主单据下的全部实例编号
     *
     * 「子流程参与人能看兄弟子流程」用。⚠️ 这一条默认是<b>关闭</b>的，
     * 所以这个查询正常情况下根本不会执行 —— 开启它等于让同一主单据下
     * 所有子流程互相透明
     */
    default List<String> selectInstanceIdsByRoot(String rootProcessInstanceId) {
        return selectList(new LambdaQueryWrapperX<BpmInstanceParticipantDO>()
                .eq(BpmInstanceParticipantDO::getRootProcessInstanceId, rootProcessInstanceId)
                .select(BpmInstanceParticipantDO::getProcessInstanceId))
                .stream().map(BpmInstanceParticipantDO::getProcessInstanceId).distinct().toList();
    }

    default void deleteByProcessInstanceId(String processInstanceId) {
        delete(BpmInstanceParticipantDO::getProcessInstanceId, processInstanceId);
    }

}
