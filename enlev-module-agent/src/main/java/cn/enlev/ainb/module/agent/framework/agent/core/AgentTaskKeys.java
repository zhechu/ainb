package cn.enlev.ainb.module.agent.framework.agent.core;

import cn.hutool.core.util.IdUtil;

/**
 * 「任务键」的唯一产地（缺陷 D4-28 / 裁定 R-01）
 *
 * <h3>为什么要有这个类</h3>
 * ai 模块的 {@code AiModelApi} 用 {@code AiChatReqDTO.taskKey} 表达「同一个任务只能用同一个模型」。
 * 键值本身没有业务含义，但它必须满足两条硬约束：
 * <ol>
 *   <li><b>同一任务内每一次模型调用传的值完全相同</b> —— 差一个字符，粘滞就失效，
 *       模型又会在任务中途漂走；</li>
 *   <li><b>不同任务之间不能撞</b> —— 撞了两个任务会共用一个钉子，
 *       一个任务的额度耗尽会连累另一个任务直接失败。</li>
 * </ol>
 * 这两条靠「各调用点自己拼字符串」是保不住的，所以前缀与拼法收在这一个类里。
 * 🟢 缺陷 E2（{@code waitType} 裸字面量散在 5 个文件）已按同款思路收口成 {@code WaitTypeEnum}。
 * 那次散开的实际后果值得记下来：有的读取点 {@code trim}、有的逐字比，于是 {@code " event "} 能过发布闸门、
 * 却在运行期认不出来，流程静默卡在 INTERRUPTED —— 字面量一散开，后来者只会照抄最近看到的那一处，没人知道全貌。
 *
 * <h3>三个场景，三种边界</h3>
 * <ul>
 *   <li>{@link #ofExecution}：一次流程执行 —— 有天然唯一标识 {@code executionId}，直接用它。
 *       🔴 一次执行里的多个 LLM 节点<b>可以各配不同 modelId</b>（{@code LlmNodeExecutor} /
 *       {@code LlmConditionNodeExecutor} 都读 {@code node.configLong("modelId")}），却共用这一个键 ——
 *       这不冲突：ai 侧的钉子是按「taskKey + 请求的 modelId」分档的，
 *       显式配置各走各档，只有「都没配、由故障切换挑」的那些节点才共享一个钉子；</li>
 *   <li>{@link #newPlanRun}：一次「需求 → 流程」规划 —— 含多轮自修正回灌，
 *       入口上没有天然标识（{@code wf_plan_xxx} 那个 code 要等校验通过才生成），故现取一个；</li>
 *   <li>{@link #newToolLoopRun}：一次聊天工具循环 —— 循环内每轮都调模型。
 *       🔴 <b>刻意不按 conversationId 钉</b>：一个会话有很多轮提问，按会话钉会让
 *       「模型额度耗尽」把整个会话锁死到 TTL 到期，而按轮钉正好落在 R-01 说的
 *       「换下一个模型只准发生在任务边界」上。conversationId 仍拼进键里，是为了日志可检索。</li>
 * </ul>
 *
 * @author 走进新时代
 */
public final class AgentTaskKeys {

    private AgentTaskKeys() {
    }

    /**
     * 一次流程执行的任务键
     *
     * @param executionId 执行编号，来自 {@code WorkflowContext#getExecutionId()}
     * @return 任务键；{@code executionId} 为空时返回 null（= 不做粘滞，退回逐次调用各自选模型）
     */
    public static String ofExecution(Long executionId) {
        return executionId == null ? null : "EXEC:" + executionId;
    }

    /**
     * 新开一次规划任务
     *
     * <p>🔴 必须在 {@code planToWorkflow} <b>入口处调一次</b>、存进局部变量，
     * 然后把那个变量传给每一轮 {@code callModel}。在 {@code callModel} 里现调会得到
     * 每轮都不同的键 —— 那样粘滞一次都不生效，而且不会报错，只会静默退回旧行为。
     */
    public static String newPlanRun() {
        return "PLAN:" + IdUtil.fastSimpleUUID();
    }

    /**
     * 新开一次聊天工具循环任务
     *
     * <p>同 {@link #newPlanRun}：在 {@code run} 入口处调一次。
     *
     * @param conversationId 会话编号，仅用于日志可检索，可为 null
     */
    public static String newToolLoopRun(Long conversationId) {
        return "TOOLLOOP:" + conversationId + ":" + IdUtil.fastSimpleUUID();
    }

}
