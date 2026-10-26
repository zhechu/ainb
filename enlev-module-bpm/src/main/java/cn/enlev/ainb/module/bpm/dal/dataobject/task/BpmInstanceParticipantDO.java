package cn.enlev.ainb.module.bpm.dal.dataobject.task;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import cn.enlev.ainb.module.bpm.enums.task.BpmParticipationEnum;
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
 * 流程实例参与人 DO
 *
 * <h3>这张表解决什么问题</h3>
 *
 * 「谁与这条流程实例有关」此前没有单一出处，要靠<b>五个分支</b>现算：
 * 发起人查实例、审批人遍历历史任务、转办人看任务 owner、抄送人查抄送表、
 * 管理员查流程定义（见 {@code BpmProcessInstanceServiceImpl#canUserViewProcessInstance}）。
 *
 * 现算有两个硬伤：
 * <ol>
 *   <li><b>查不动</b>。「我参与过的流程」这类列表要对每条实例跑一遍五分支判定，
 *       没有一个索引能支撑 —— 四个列表页（待办/已办/我发起/抄送我的）现在各写各的 SQL，
 *       口径还不一致</li>
 *   <li><b>子流程断链</b>。子流程是独立的 instance id，父流程发起人与子流程之间
 *       没有任何可查的关联。而 {@code inVariables} 已经把数据传过去了 ——
 *       <b>数据流过去了，权限没跟上</b></li>
 * </ol>
 *
 * 所以把「参与关系」在发生的那一刻<b>写下来</b>，而不是事后推导。
 *
 * <h3>⚠️ 冗余 root / parent 是有意的</h3>
 *
 * {@code root_process_instance_id} 与 {@code parent_process_instance_id} 都能由
 * Flowable 的 super execution 链<b>现推</b>，这里仍然冗余落库，理由有两条：
 *
 * <ul>
 *   <li>推导要沿父链上溯，而 {@code HistoricProcessInstance} <b>只有</b>
 *       {@code getSuperProcessInstanceId()}，<b>没有</b> {@code getRootProcessInstanceId()} ——
 *       历史态下取 root 是 N 次查询，N 是嵌套深度</li>
 *   <li>「查主单据下的所有参与人（含各层子流程）」是一句
 *       {@code WHERE root_process_instance_id = ?}，走索引。
 *       靠现推的话这个查询根本写不出来</li>
 * </ul>
 *
 * 代价是父子关系变更时要维护冗余 —— 而流程实例的父子关系<b>创建后不再变化</b>，
 * 这个代价实际为零。
 *
 * @author 走进新时代
 */
@TableName("bpm_instance_participant")
@KeySequence("bpm_instance_participant_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BpmInstanceParticipantDO extends BaseDO {

    /**
     * 编号
     */
    @TableId
    private Long id;

    /**
     * 流程实例的编号
     *
     * 关联 ProcessInstance 的 id 属性。参与关系<b>直接发生</b>在这条实例上
     */
    private String processInstanceId;

    /**
     * 顶层流程实例的编号
     *
     * 非子流程时等于 {@link #processInstanceId} 自身（而不是留空）——
     * 留空的话「查主单据下的全部参与人」要写成
     * {@code WHERE root = ? OR (root IS NULL AND process_instance_id = ?)}，
     * 既走不好索引，也迟早有人漏写后半截
     */
    private String rootProcessInstanceId;

    /**
     * 直接父流程实例的编号
     *
     * 顶层实例为 null。与 root 的区别：三层嵌套时中间层的 parent 是第二层，root 是第一层
     */
    private String parentProcessInstanceId;

    /**
     * 表单编码
     *
     * 冗余流程定义的 {@code formDesignCode}。用于「我参与过的某类单据」按表单筛选，
     * 也是与在线表单的数据集资源对上号的那把钥匙
     */
    private String formCode;

    /**
     * 业务主键
     *
     * 冗余 ProcessInstance 的 businessKey，即在线表单业务表的主键值。
     * 派生可见性（S5c）要靠它把「参与了流程」翻译成「能看业务表的哪几行」
     */
    private String businessKey;

    /**
     * 参与人的用户编号
     *
     * 关联 system_users 的 id 属性
     */
    private Long userId;

    /**
     * 参与方式
     *
     * 枚举 {@link BpmParticipationEnum}
     */
    private Integer participation;

    /**
     * 任务编号
     *
     * 关联 {@code HistoricTaskInstance#getId()}。发起人与抄送到节点的场景可能为空
     */
    private String taskId;

    /**
     * 流程活动编号
     *
     * 对应 BPMN XML 的节点编号。仿钉钉/飞书的抄送节点是 ServiceTask，没有 taskId 只有 activityId，
     * 所以两个都要留
     */
    private String activityId;

    /**
     * 是否可写
     *
     * ⚠️ 这一位决定「参与」是只读还是可改，<b>不能由参与方式单独推出来</b>：
     * 同样是 APPROVER，任务在办时可写、办结后只读。
     * 推导的话就会得出「审批过的人永远能改这张单子」
     */
    private Boolean writable;

    /**
     * 参与开始时间
     */
    private LocalDateTime startTime;

    /**
     * 参与结束时间
     *
     * 为 null 表示<b>仍在参与中</b>。四个列表页的「待办 / 已办」之分正是靠它，
     * 所以索引 {@code (user_id, form_code, end_time)} 把它放在最后一位
     */
    private LocalDateTime endTime;

    /**
     * 保留截止时间
     *
     * 归档用（S9）。参与表是只增不减的流水，跑几年之后是全库最大的表之一；
     * 到期行可以搬去归档表而不影响在办流程。这里先留字段，清理策略 S9 定
     */
    private LocalDateTime retainUntil;

}
