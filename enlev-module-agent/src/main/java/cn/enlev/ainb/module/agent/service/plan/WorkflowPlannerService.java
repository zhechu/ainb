package cn.enlev.ainb.module.agent.service.plan;

import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;

/**
 * plan-as-workflow 生成器 Service（增量7a，P3 Planner 改造第一切片）
 *
 * <pre>
 *   复杂需求(自然语言)
 *     → for round in 1..maxRounds:
 *         ① 调 LLM（systemPrompt = WorkflowDslPlannerPrompt）产出一份 Workflow DSL JSON
 *         ② ReactJsonCodec 抠 JSON / 解析失败 → 回灌 parseFailureHint，continue
 *         ③ DslParser.parse → WorkflowDefDTO（失败回灌，continue）
 *         ④ 双层校验：WorkflowDslValidator（收集式基础）+ 通过则 compiler.dryRunValidate（深层）
 *            不通过 → 回灌 toFeedback，continue
 *         ⑤ 通过 → 注入服务端唯一 code → 返回 WorkflowDraftBO
 *     轮次耗尽 → 抛 WORKFLOW_PLAN_DSL_GENERATION_FAILED（带最后一轮反馈）
 * </pre>
 *
 * <h3>🔴 这是聊天 BUILD 意图唯一的规划器（增量7b 起）</h3>
 * 本 Service 产出「Plan 即 Workflow」的 DSL 草稿。增量7a 期间它与线性 planner（{@code AgentPlannerService}，
 * 产 {@code agent_plan_step} 线性列表）并存、由独立 REST 端点纯增量验证；增量7b 已把聊天 BUILD 入口切到
 * 本 Service（见 {@code AgentAssistantServiceImpl#workflowDraftStream}）并删除线性 planner ——
 * 本 Service 自此是唯一的规划路径。
 *
 * <h3>两条不可动摇的约束（沿用线性 planner 的纪律）</h3>
 * <ol>
 *   <li><b>每轮都计量</b>（scene={@code PLAN_DSL}）：一次生成可能多轮，账单要能解释清楚；</li>
 *   <li><b>超限 fail-close</b>：绝不拿最后一轮那份「看着像、实则编译不过」的 DSL 凑合返回。</li>
 * </ol>
 *
 * @author 走进新时代
 */
public interface WorkflowPlannerService {

    /**
     * 把一句复杂需求拆解成一份可执行的 Workflow DSL 草稿，并把过程回传
     *
     * @param question 用户的自然语言需求
     * @param context  身份快照（必须在 Servlet 线程上捕获；干跑校验 {@code dryRunValidate} 也要用它占位）
     * @param listener 进度回调，见 {@link ProgressListener}
     * @return 结构合法、已注入唯一 code 的 DSL 草稿
     */
    WorkflowDraftBO planToWorkflow(String question, AgentExecutionContext context, ProgressListener listener);

    /**
     * 生成进度回调
     *
     * <p>一次生成要 1~N 轮模型调用，期间界面上什么都没有会让用户以为卡住了。
     * REST 端点（{@code plan-workflow}）只把它记日志；聊天 BUILD 路径把它接成 PLANNING 事件（增量7b）。
     *
     * <p>⚠️ 回调在生成线程上同步调用，实现里不要做耗时的事。
     */
    @FunctionalInterface
    interface ProgressListener {

        /**
         * @param text 给人看的一句话，中文，不要出现工具名这类内部标识
         */
        void onProgress(String text);
    }

}
