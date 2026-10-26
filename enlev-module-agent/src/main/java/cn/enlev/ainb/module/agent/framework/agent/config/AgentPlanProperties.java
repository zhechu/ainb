package cn.enlev.ainb.module.agent.framework.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * plan-as-workflow 生成器 + 聊天规划路径的配置
 *
 * <p>增量7b：线性 planner（{@code AgentPlannerService} / {@code AgentPlanService}）已删除，
 * 原属它的 {@code expireMinutes} / {@code maxPlanRounds} / {@code toolResultMaxChars} 随之移除
 * （agent_loop 有自己的 {@code AgentLoopProperties.toolResultMaxChars}，不受影响）。
 * 这里只保留 plan-as-workflow 生成器与聊天 BUILD 路径仍需要的三项。
 *
 * <p>🔴 {@code plannerModelId}（把编排钉到某个指定模型的口子）已移除，
 * 编排用哪个模型一律由 <b>AI 模型配置列表</b>决定：启用状态为「开启」的模型里按排序值取最前的那个。
 * 移除的理由与替代做法见 {@code WorkflowPlannerServiceImpl#callModel}。
 *
 * @author 走进新时代
 */
@ConfigurationProperties(prefix = "enlev.agent.plan")
@Validated
@Data
public class AgentPlanProperties {

    /**
     * 规划阶段单次回复的最大 token
     *
     * <h3>🔴 必须显式给，不能用模型自身的配置</h3>
     * 实测踩过：模型配的是 {@code max_tokens=2000}，对聊天足够，
     * 但一份含 5 个中文字段的建表计划 JSON 就超了 —— 输出<b>从中间断掉</b>，
     * 变成一段不合法的 JSON。
     *
     * <p>而这个故障的表现极具误导性：日志里是「模型返回的不是合法 JSON」，
     * 看起来像模型不听话、不按格式输出，实际上它输出得完全正确，
     * 只是<b>没让它说完</b>。照着表象去调 prompt 会越调越糊涂。
     *
     * <p>规划的输出结构远比对话长（要带完整的建表定义），所以单独给预算。
     */
    private Integer maxTokens = 8000;

    /**
     * 整次规划的超时（秒）
     *
     * <h3>为什么不能复用对话的 120 秒</h3>
     * 对话是一次模型调用，规划最多 {@link #asWorkflowMaxRounds} 轮，
     * 每轮都可能是一次几十秒的调用 —— 用对话的超时去卡规划，
     * 卡掉的会是<b>正常跑着的</b>规划，而且恰好卡在轮次最多、也就是需求最复杂的那些请求上。
     *
     * <p>但也必须有个上限：规划跑在 {@code boundedElastic} 线程上，
     * 厂商连接挂起时（连接在、不发数据）那个线程会一直被占着，
     * 而这种挂起<b>不会</b>触发任何回调 —— 与对话路径同一个坑。
     */
    private Integer timeoutSeconds = 300;

    // ========== plan-as-workflow 生成器（增量7a） ==========

    /**
     * plan-as-workflow 生成-校验-自修正循环的最大轮次
     *
     * <p>纪律：每轮一次真实计费，超限即 fail-close 抛
     * {@code WORKFLOW_PLAN_DSL_GENERATION_FAILED}，绝不拿最后一轮的非法草稿凑合。
     * 本循环没有「只读工具探索」轮次，全部预算都用于「产 DSL→校验→回灌自修正」，
     * 6 轮通常够；真不稳时的做法是改 AI 模型配置列表的排序值，而不是无限加轮次。
     *
     * <p>⚠️ 换模型现在<b>连带</b>换掉对话与意图分类那两路的默认模型 ——
     * {@code plannerModelId} 移除后不再有「只换编排」的口子。这不是疏漏而是取舍：
     * 一个配置项能同时存在两种「哪个模型更强」的判断时，管理员调了一处、另一处没跟上，
     * 表现出来就是「明明换了强模型还是不稳」，排查方向直接被带偏。
     */
    private Integer asWorkflowMaxRounds = 6;

}
