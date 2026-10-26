package cn.enlev.ainb.module.bpm.dal.dataobject.task;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 任务候选人快照 DO
 *
 * <h3>为什么要「表达式 + 快照」双存（v9 §7.2）</h3>
 *
 * 候选人现在是<b>算完就扔</b>的：策略算出一组 userId，交给 Flowable 当 assignee/candidate，
 * 算式本身不留痕。这带来三个问题：
 *
 * <ol>
 *   <li><b>说不清「他为什么是审批人」</b>。事后只看得到结果是张三，
 *       看不到他是因为「研发部负责人」还是因为「被点名」—— 而这两者的后续处理完全不同</li>
 *   <li><b>离职交接做不了</b>。张三离职，哪些在办任务该转给谁？
 *       要重算就得知道当初的算式，而算式没存</li>
 *   <li><b>组织调整后无从判断影响面</b>。部门负责人换人了，
 *       哪些在办任务的审批人应该跟着变？</li>
 * </ol>
 *
 * 所以两样都存：
 *
 * <ul>
 *   <li><b>表达式</b>（{@code strategy} + {@code param}）—— 「按什么规则算的」，可重算</li>
 *   <li><b>快照</b>（{@code subject_type} + {@code subject_ref_id} + {@code resolved_user_id}）
 *       —— 「当时算出来是谁」，是任务归属的<b>既成事实</b></li>
 * </ul>
 *
 * <h3>⚠️ 重算不会自动改变已分配的任务</h3>
 *
 * 快照是既成事实，不能因为组织变了就悄悄把别人的待办挪走 ——
 * 那会让「我昨天看到的单子今天不见了」。重算只产出<b>差异建议</b>，
 * 真正的转办由人确认后执行（见 {@code BpmTaskCandidateRecalculateService}）。
 *
 * @author 走进新时代
 */
@TableName("bpm_task_candidate_snapshot")
@KeySequence("bpm_task_candidate_snapshot_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BpmTaskCandidateSnapshotDO extends BaseDO {

    @TableId
    private Long id;

    /**
     * 流程实例编号
     */
    private String processInstanceId;

    /**
     * 任务编号
     *
     * 未生成任务时（按活动预算候选人）为空
     */
    private String taskId;

    /**
     * 流程活动编号（BPMN XML 的节点 id）
     */
    private String activityId;

    /**
     * 候选策略
     *
     * 对应 {@code BpmTaskCandidateStrategyEnum}。这是「表达式」的一半
     */
    private Integer strategy;

    /**
     * 候选参数
     *
     * 策略的入参，如角色编号、岗位编号、表达式串。这是「表达式」的另一半
     */
    private String param;

    /**
     * 主体类型
     *
     * {@code USER} / {@code ROLE} / {@code DEPT} / {@code POST} / {@code GROUP}。
     *
     * ⚠️ 它回答的是「他<b>为什么</b>是审批人」。只存 userId 的话，
     * 「张三是被点名的」与「张三是研发部负责人」完全无法区分 ——
     * 而张三离职时，前者要人工指定接手人，后者只要换了负责人就自动有解
     */
    private String subjectType;

    /**
     * 主体编号
     *
     * 角色编号 / 部门编号 / 岗位编号 / 用户组编号；{@code subject_type=USER} 时等于用户编号
     */
    private Long subjectRefId;

    /**
     * 解析出的用户编号
     *
     * 快照的核心：当时这个主体展开成了哪个具体的人
     */
    private Long resolvedUserId;

    /**
     * 快照时间
     */
    private LocalDateTime snapshotTime;

}
