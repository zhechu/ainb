package cn.enlev.ainb.module.agent.dal.dataobject.workflow;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Workflow 执行实例 DO（一次运行一行）
 *
 * <h3>🔴 {@link #threadName} 是与 SAA checkpoint 的唯一关联键</h3>
 * SAA 的 GRAPH_THREAD / GRAPH_CHECKPOINT 没有 tenant_id 列，也没有本平台的 executionId。
 * 把租户与执行 id 编码进 thread_name 后，
 * 既能 resume（SAA 按 thread_name 找 checkpoint），又能反查租户与业务实例。
 * 这一列一旦写错，resume 会静默找到别人的 checkpoint —— 所以它是 unique key。
 *
 * <p>🔴 格式的<b>权威不在这份 javadoc 里</b>，是
 * {@link cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowThreadName#threadName}（B3-5）——
 * 这里刻意不复述形状：复述一遍就多一处将来会各自漂移的副本，而这一列漂了是跨租户串号。
 *
 * <h3>variableSnapshot 不是 resume 源</h3>
 * resume 的真相源是 GRAPH_CHECKPOINT 里的 opaque 二进制 state_data。
 * 本列只是给查询/展示用的结构化投影，两者可能短暂不一致，一切以 checkpoint 为准。
 *
 * @author 走进新时代
 */
@TableName("agent_workflow_execution")
@KeySequence("agent_workflow_execution_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentWorkflowExecutionDO extends TenantBaseDO {

    private Long id;

    private Long workflowDefId;

    /**
     * 绑定的定义版本（快照）
     *
     * 存下来而不是查时 join def：def 会出新版，在途实例必须按启动时的版本跑完。
     */
    private Integer workflowVersion;

    /**
     * SAA checkpoint 线程名；取值口径见类注释里指的那个唯一权威
     */
    private String threadName;

    /**
     * 状态，枚举 {@link cn.enlev.ainb.module.agent.enums.WorkflowExecutionStatusEnum}
     */
    private String status;

    /**
     * 当前/最后停留的节点 id
     */
    private String currentNode;

    /**
     * 挂起时<b>正在等待的那道闸口</b>的节点 id（迭代六 D1）
     *
     * <p>🔴 与 {@link #currentNode} <b>不是一回事</b>，两列并列正是为了把这件事摆在明面上：
     * {@code currentNode} 记的是「最后一个跑完的节点」，本列记的是「被 {@code interruptsBefore} 拦下、
     * 还没跑的那个」。两者只在挂起节点正好是 START 之后第一个时才相等。
     * 缺了本列，救援按钮就说不清要恢复哪里 —— 前端只能把挂起节点暂存在内存里（刷新即丢），
     * 执行历史抽屉里连这一格都没有。
     *
     * <p>🔴 取值口径是 {@code DslToStateGraphCompiler.resolveSuspendNodeId}（checkpoint 快照的
     * {@code next()}），<b>不是</b> {@code InterruptionMetadata.node()}（那个给的是最后一个跑完的节点，
     * 实测五种形状里有三种会给错值）。取不到快照时该口径返回 null，本列随之保持 NULL ——
     * 刻意不回落成错值：NULL 只是「说不清在哪恢复」，错值会让救援的人去恢复一个早就跑完了的节点，
     * 还确信自己是对的。
     *
     * <p>与 {@link #resumeAt} / {@link #eventKey} 同在挂起时刻写、同在 CAS 认领
     * （{@code AgentWorkflowExecutionMapper#casInterruptedToRunning}）时清回 NULL。
     * 🔴 但本列是<b>无条件</b>落的：{@code hitl.confirm} 挂起时那两列都是 NULL，
     * 若把本列挂在「{@code resumeAt != null || eventKey != null}」那个判断里，
     * 最需要说清卡在哪的纯人工挂起反倒不落库。
     */
    private String suspendNode;

    /**
     * resume 时会被 {@code OverAllState.input()} 静默丢弃、<b>且作者补一条声明就能救</b>的
     * state 键集（JSON 数组字符串）。
     *
     * <p>🔴 迭代七 B3-7：SAA 的 {@code OverAllState.input(checkpointState)} 只保留注册过
     * KeyStrategy 的键；没注册的键在 resume 瞬间被静默丢掉，既不落日志也不告诉用户。
     * 本列在<b>挂起时</b>算一次（供前端确认框展示），在<b>resume 时</b>重算覆盖
     * （因为 def 可能在挂起后改了版本，重算的值才是本次 resume 真正会丢的）。
     *
     * <p>🔴 取值口径<b>不在这里复述</b>，权威是
     * {@code AgentWorkflowExecutionServiceImpl#computeDroppedKeys} 的 javadoc（那里连着
     * 「为什么必须过滤」的推理与三条判据）。这里只说本列语义上最要紧的一句：
     * 落到本列的<b>不是</b>「state 里有但没注册」的全集 —— 那个集合恒非空、且全是引擎与执行器的内部键，
     * 作者既声明不了也改不动；本列已经过一道「作者真写过没有」的正向白名单，
     * 所以<b>列里的每个键，补一条 {@code variables[]} 声明就真的能救</b>。
     *
     * <p>与 {@link #suspendNode} / {@link #resumeAt} / {@link #eventKey} 同在挂起时刻写、
     * 同在 CAS 认领（{@code casInterruptedToRunning}）时清回 NULL。
     * NULL 表示「非挂起态」或「滤网后为空（没有作者能自己修的键会被丢）」——
     * 🔴 后者<b>不等于</b>「一个键都不会丢」：引擎内部键照丢，只是丢了不影响作者、也不该拿它去吓人。
     */
    private String droppedKeys;

    /**
     * 变量快照 JSON（可查询投影，非 resume 源）
     */
    private String variableSnapshot;

    /**
     * 失败恢复策略，枚举 {@link cn.enlev.ainb.module.agent.enums.WorkflowRecoveryEnum}
     */
    private String recovery;

    private String failedAtNode;

    private Long userId;

    /**
     * 入参 JSON 快照
     *
     * 🔴 字段名/列名刻意用 {@code inputJson}（列 {@code input_json}）而不是 {@code input}：
     * MyBatis-Plus 的 TenantLineInnerInterceptor 用 jsqlparser 解析每条 SELECT 拼 tenant_id，
     * jsqlparser 把裸词 {@code input}/{@code output} 当关键字。若列名改 {@code input_json}
     * 但字段仍叫 {@code input}，MP 会生成 {@code input_json AS input}，别名里的 {@code input}
     * 同样让 jsqlparser 抛 ParseException（实测 column 131）。字段与列同名才不产生 AS 别名。
     */
    private String inputJson;

    /**
     * 出参 JSON 快照（命名原因同 {@link #inputJson}）
     */
    private String outputJson;

    private String errorMsg;

    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private Integer durationMs;

    /**
     * wait(duration) 到期自动唤醒时刻（增量4a）
     *
     * <p>仅当执行停在 {@code wait} 节点（waitType=duration）被挂起时写入 = 中断时刻 + duration；
     * {@link cn.enlev.ainb.module.agent.job.WorkflowWaitResumeScheduler} 扫到 {@code resume_at<=now}
     * 的 INTERRUPTED 行后自动 resume，认领时把本列清回 NULL。
     * 🔴 为 NULL 表示「非定时挂起」（hitl.confirm 等人工确认），轮询器不会自动唤醒，仍靠 resume-stream 人工触发。
     */
    private LocalDateTime resumeAt;

    /**
     * wait(event) 等待的外部事件键（增量4b）
     *
     * <p>仅当执行停在 {@code wait} 节点（waitType=event）被挂起时写入 = 该节点 config 的 {@code eventKey}；
     * 外部系统调 {@code POST /agent/workflow/event {eventKey,payload}} 后，Service 按本列匹配本租户下所有
     * INTERRUPTED 行并逐条 resume，认领时把本列清回 NULL。
     * 🔴 与 {@link #resumeAt} 互斥：事件挂起 event_key 非空、resume_at 为 NULL（轮询器不碰它）；
     * duration 挂起 resume_at 非空、event_key 为 NULL（不会被事件匹配）；hitl 两者皆 NULL（纯人工）。
     */
    private String eventKey;

}
