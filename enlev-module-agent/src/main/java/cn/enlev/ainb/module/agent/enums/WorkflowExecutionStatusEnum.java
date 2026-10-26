package cn.enlev.ainb.module.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * Workflow 执行实例状态
 *
 * <pre>
 *   CREATED ──→ RUNNING ──→ SUCCEEDED
 *                  │  ├──→ INTERRUPTED ──(resume)──→ RUNNING
 *                  │  ├──→ FAILED ──(recovery=COMPENSATE)──→ COMPENSATING ──→ COMPENSATED
 *                  │  │                                        └──→ COMPENSATION_FAILED
 *                  │  └──→ CANCELLED（用户主动中止：SSE 断流 / POST cancel，终态，不反做不告警）
 * </pre>
 *
 * <p>🔴 INTERRUPTED 也能被取消（挂起中的执行没有活着的流可断，走 {@code POST /agent/workflow/cancel}
 * 直接把它 CAS 成 CANCELLED）—— 那是卡在闸口上没人管时的最后一条出路。
 *
 * <h3>为什么 INTERRUPTED 是独立状态而不是 RUNNING 的一个标记</h3>
 * 人工确认（hitl）会让流程停在 checkpoint 上等待，可能等几分钟到几天。
 * 这段时间里它既不占线程、也不算「在跑」，运维看板必须能一眼区分
 * 「卡住不动的 RUNNING」和「正常等确认的 INTERRUPTED」—— 混成一个的话，
 * 前者是故障、后者是设计，却被同一批告警扫出来。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum WorkflowExecutionStatusEnum {

    /** 已创建，尚未开始跑第一个节点 */
    CREATED("CREATED", "已创建"),

    /** 执行中 */
    RUNNING("RUNNING", "执行中"),

    /**
     * 已中断，停在 checkpoint 上等待
     *
     * 这是<b>正常挂起</b>，不是故障。resume 后回到 RUNNING。三种来源共用本状态，靠 {@code resume_at}/{@code event_key} 区分：
     * <ul>
     *   <li><b>hitl.confirm</b>：等人工确认，{@code resume_at} 与 {@code event_key} 均为 NULL ——
     *       只能靠人工调 resume-stream 唤醒</li>
     *   <li><b>wait(duration)</b>（增量4a）：等定时到期，{@code resume_at} 非空、{@code event_key} 为 NULL ——
     *       由 {@code WorkflowWaitResumeScheduler} 轮询扫到「{@code resume_at<=now}」后自动 resume</li>
     *   <li><b>wait(event)</b>（增量4b）：等外部事件，{@code event_key} 非空、{@code resume_at} 为 NULL ——
     *       由外部系统调 {@code POST /agent/workflow/event {eventKey,payload}} 按 key 匹配后 resume；
     *       轮询器因 {@code resume_at} 为 NULL 而<b>扫不到它</b>，两类挂起互不串扰。
     *       🔴 <b>hitl.bpm_approval</b>（P6）也落在这一档：挂起时发起 BPM 审批实例，把 businessKey 当
     *       {@code event_key} 存，审批完成由 {@code AgentBpmApprovalListener} 按同一个 key 唤醒 ——
     *       从本表的列上看与 wait(event) 完全同形，区分只能回 DSL 看节点 type</li>
     * </ul>
     * 三种 resume 都走 {@code casInterruptedToRunning} 原子认领：把本状态切回 RUNNING 并清空
     * {@code resume_at}+{@code event_key}+{@code suspend_node}（防多实例/人工与轮询与事件撞车双跑）。
     *
     * <p>🔴 「停在<b>哪个</b>闸口」看 {@code suspend_node}，不要看 {@code current_node}：
     * 后者记的是最后一个<b>跑完了</b>的节点，两者只在挂起节点正好是 START 之后第一个时才相等。
     * {@code suspend_node} 为 NULL 表示「说不清在哪恢复」（非挂起态，或挂起时取不到 checkpoint 快照）。
     */
    INTERRUPTED("INTERRUPTED", "等待确认"),

    /** 全部节点成功 */
    SUCCEEDED("SUCCEEDED", "执行成功"),

    /**
     * 失败（未触发反做）
     *
     * <p>🔴 这里原先还写着「或 recovery=FORWARD 续跑策略下停在断点」——**那条路径不存在**，已删。
     * 实测：{@code WorkflowRecoveryEnum.FORWARD} 除了 {@code parse} 的兜底返回值之外，
     * 全仓唯一的读者是 {@code AgentWorkflowCompensator#shouldCompensate}，而它只判 {@code COMPENSATE}；
     * {@code AgentWorkflowExecutionServiceImpl#settle} 的注释也明写「recovery=FORWARD：失败即终态」。
     * 所以 FORWARD 的实际语义是**「失败了就失败，不自动续跑也不反做」**，不是「停在断点等着续」。
     * （🟢 迭代七 B3-4 已把 FAILED 纳入 {@link #isFinal}，与本条注释里「失败即终态」的口径对齐。）
     */
    FAILED("FAILED", "执行失败"),

    /**
     * 已取消（用户主动中止）
     *
     * <h3>🔴 为什么必须与 FAILED 分开（迭代七 B3-1 / B3-2）</h3>
     * 本成员加上之前，「取消」在库里是不存在的：{@code buildEventFlux} 的 {@code .doOnCancel} 把用户中止
     * 直接写成 {@code FAILED} + reason「用户中断」，于是同一列里混着两件性质完全不同的事 ——
     * <ol>
     *   <li><b>数据错</b>：事后无法区分「我按了停」和「它崩了」。reason 那句中文是唯一线索，
     *       而它既不可枚举也没进任何查询条件；</li>
     *   <li><b>行为错</b>：{@code settle} 见 FAILED 且 {@code recovery=COMPENSATE} 就触发 Saga 反做
     *       —— 用户想停住的动作反倒被反做了一遍；</li>
     *   <li><b>告警错</b>：FAILED 会进失败告警面，于是每次有人按「停」都给租户联系人发一条告警。</li>
     * </ol>
     * 三条都是「把取消当失败」的直接后果，故本状态一旦落地，{@code settle} 的补偿与告警两个分支
     * 都<b>不需要改</b> —— 它们本来就只认 FAILED，取消自然绕开。
     *
     * <h3>它与 INTERRUPTED 也不是一回事</h3>
     * INTERRUPTED 是<b>正常挂起</b>（等人工确认/等定时到期/等外部事件），可被 {@code resume} 拉回来；
     * 本状态是<b>终态</b>，{@link #isResumable} 对它返回 false，恢复不了。
     * 裁定 A-Ⅰ：本轮只做「不再判 FAILED、不再触发补偿」，<b>不做</b>「取消转可恢复」。
     *
     * <h3>🔴 一个必须说清的限制：取消是<b>记账语义</b>，不是「把线程掐断」</h3>
     * {@code runGraph} 是被 {@code Schedulers.boundedElastic().schedule(...)} 派出去的，
     * 那个 {@code Disposable} 被丢弃了；而图在里头走的是阻塞的 {@code blockLast()}。
     * 所以取消订阅只能停住<b>事件投递</b>，停不住<b>已经在跑的那个节点</b> ——
     * 它会跑到自然结束，只是收尾时被 {@code AgentWorkflowExecutionMapper#updateByIdUnlessCancelled}
     * 挡住、不许覆盖本状态。详见 {@code AgentWorkflowExecutionService#cancel} 的 javadoc。
     */
    CANCELLED("CANCELLED", "已取消"),

    /** Saga 反做进行中 */
    COMPENSATING("COMPENSATING", "回滚中"),

    /** Saga 反做完成，所有已生效的写操作已语义撤销 */
    COMPENSATED("COMPENSATED", "已回滚"),

    /**
     * 反做本身也失败了
     *
     * 🔴 这是最需要人工介入的终态：意味着「有的写操作生效了、又没撤干净」。
     * 单独一个状态而不是并进 FAILED，是因为它的处置动作完全不同 ——
     * FAILED 可以直接重跑，而 COMPENSATION_FAILED 必须先人工核对残留数据。
     */
    COMPENSATION_FAILED("COMPENSATION_FAILED", "回滚失败"),
    ;

    private final String status;
    private final String description;

    /**
     * 是不是终态（不会再自己变了，除非人工介入）
     *
     * 收口成一个方法，避免在各处写状态字符串比较 —— 散开写的话，
     * 将来加状态必然漏掉某一处，而漏掉的那处会把一个已结束的执行又拉回 RUNNING。
     *
     * <p>🔴 {@link #CANCELLED} 在列（迭代七 B3-2）：取消是终态，恢复不了也续不了。
     * <p>🔴 {@link #FAILED} 也在列（迭代七 B3-4）：recovery=FORWARD 的实际语义就是「失败即终态」，
     * 而 recovery=COMPENSATE 那条路走的是 {@link #COMPENSATING} → {@link #COMPENSATED}/{@link #COMPENSATION_FAILED}，
     * 与 FAILED 无关；把 FAILED 排除在 isFinal 之外只是历史遗留，与代码里其他地方的口径不一致。
     * <p>⚠️ {@link #COMPENSATING} <b>刻意不在列</b> —— 反做过程中还没落定，
     * 完成后会自己变成 COMPENSATED 或 COMPENSATION_FAILED，故不是终态。
     * 这也是 {@link #isCancellable} 不能用 {@code !isFinal} 当准入闸的原因（详见其注释）。
     */
    public static boolean isFinal(String status) {
        return Arrays.asList(SUCCEEDED.status, FAILED.status, CANCELLED.status,
                        COMPENSATED.status, COMPENSATION_FAILED.status)
                .contains(status);
    }

    /**
     * 是不是「可被 resume」的状态
     *
     * 只有 INTERRUPTED 能恢复。
     *
     * <p>🔴 这里原先还写着「FAILED 走的是 recovery 续跑（另一条路径），不在这里」——**那条路径不存在**，已删。
     * FAILED 就是终态，没有任何自动续跑（证据见 {@link #FAILED} 的注释）。
     * 换句话说：本方法返回 false 的执行，**一个都恢复不了**，不存在「另一条路」。
     */
    public static boolean isResumable(String status) {
        return INTERRUPTED.status.equals(status);
    }

    /**
     * 能被 {@code POST /agent/workflow/cancel} 中止的状态：CREATED / RUNNING / INTERRUPTED（迭代七 B3-2）
     *
     * <h3>🔴 这一份集合是<b>唯一</b>的取数口，SQL 那边不许另写一遍字面量</h3>
     * {@code AgentWorkflowExecutionMapper#casToCancelled} 的 {@code status IN (...)} 由
     * {@link #cancellableStatuses()} 生成，与本方法<b>同源</b>。两处各写一遍的话必然漂移，
     * 而漂移的两个方向都难查：Java 宽 SQL 窄 ⟹ 预检放过去了、UPDATE 影响 0 行，用户收到一句
     * 指向真实状态的报错（还算能看懂）；Java 窄 SQL 宽 ⟹ 明明能取消却被自己的预检拦下，
     * 而那句报错说的是一个<b>压根没发给数据库</b>的判断。
     *
     * <h3>🔴 它<b>不是</b>「{@code !isFinal(status)}」，反例是 {@link #COMPENSATING}</h3>
     * 拿 isFinal 取反来当准入闸是这条判据最容易写错的地方：
     * <ul>
     *   <li>{@link #COMPENSATING} —— 不在 isFinal 里（反做完了还要再走一档），但那一档正在逆序调反做工具。
     *       判它「已取消」= 宣称撤销完成而撤销其实只做了一半。<b>半撤销比卡死更难查</b>
     *       （卡死看得见，半撤销看着像成功），故宁可让人等它跑完。</li>
     * </ul>
     * 至于 {@link #FAILED}：迭代七 B3-4 之后它<b>已在</b> isFinal 里，「!isFinal」这条路对 FAILED 已不再漏。
     * 但白名单另有一层保险 —— <b>加新状态时默认落进「不可取消」</b>，编译不报错、既有测全绿；
     * {@code WorkflowExecutionStatusEnumTest} 的分类表就是为此而设的绊网，
     * 「!isFinal 再补例外」这种写法一旦被人恢复，新状态默认反而<b>可取消</b>，事故会晚很多才被发现。
     * 所以这里写的是<b>白名单</b>而不是取反：只有「还可能有活着的流、或还挂在闸口上等」的三档才可中止。
     *
     * <h3>⚠️ 白名单的代价：加新状态时必须显式表态</h3>
     * 新成员默认落进「不可取消」，编译不报错、既有测全绿。
     * {@code WorkflowExecutionStatusEnumTest} 的分类表就是为此而设的绊网。
     *
     * <h3>🔴 那个 null 判断不是防御性废话，是 {@code List.of} 的一个坑</h3>
     * {@code List.of(...)} 返回的不可变列表，其 {@code contains(null)} <b>抛 NPE</b>
     * （{@code ImmutableCollections.ListN.indexOf} 里一句 {@code requireNonNull}），
     * 而 {@code Arrays.asList(...)} 的同名方法返回 false —— 两者对 null 的行为<b>相反</b>。
     * 本方法的入参是<b>从库里读出来的 status</b>，一旦某行是 NULL 就会把一次「取消」变成 500
     * 而不是一句干净的 STATE_INVALID。故这里显式挡掉（实测：加这道判断前，
     * {@code WorkflowExecutionStatusEnumTest#unknownStatusMatchesNoRuler} 以 NPE 报红）。
     * 旁边的 {@link #isFinal} 用的是 {@code Arrays.asList}，本来就 null 安全，两把尺子行为一致。
     */
    public static boolean isCancellable(String status) {
        return status != null && CANCELLABLE.contains(status);
    }

    /**
     * {@link #isCancellable} 的集合形态，供 {@code casToCancelled} 拼 SQL 的 {@code IN}。
     *
     * <p>🔴 返回的是不可变视图（{@code List.of}），调用方改不了；也<b>不要</b>在调用方再包一层
     * {@code new ArrayList<>(...)} 然后往里加 —— 那份副本一旦存在，「同源」就只是注释里的一句话。
     */
    public static List<String> cancellableStatuses() {
        return CANCELLABLE;
    }

    /**
     * 可中止集合的唯一字面量出处。
     *
     * <p>🔴 声明在全部枚举成员<b>之后</b>：枚举常量本身是最先初始化的一批 static 字段，
     * 写在它们前面会在类加载时读到 null（NPE，且发生在第一次碰这个枚举的任何代码上，离真正的错处很远）。
     */
    private static final List<String> CANCELLABLE =
            List.of(CREATED.status, RUNNING.status, INTERRUPTED.status);

}
