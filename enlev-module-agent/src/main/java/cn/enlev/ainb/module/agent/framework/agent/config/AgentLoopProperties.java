package cn.enlev.ainb.module.agent.framework.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * agent_loop 节点（自主 ReAct 循环）的默认配置
 *
 * <p>镜像 {@link AgentPlanProperties} 的组织方式：这里放<b>全局默认值</b>，
 * 单个节点的 {@code config} 可覆盖 {@code maxSteps}/{@code modelId}/{@code systemPrompt}。
 * 与规划器共用「显式给 maxTokens、低温度、工具结果截断」这几条实测纪律，
 * 但轮次语义不同 —— 规划器是「探索 N 轮后交 plan」，agent_loop 是「think→act→observe 循环到 answer」。
 *
 * @author 走进新时代
 */
@ConfigurationProperties(prefix = "enlev.agent.loop")
@Validated
@Data
public class AgentLoopProperties {

    /**
     * 自主循环的默认最大步数（一次 act 记一步）
     *
     * <h3>🔴 这是局部精确上界，不是靠全局 recursionLimit 兜</h3>
     * S1 spike 实测：SAA 的 {@code recursionLimit} 超限时是<b>静默截断</b>而非抛错 ——
     * 拿它当收敛保证，用户会收到一个「跑了一半、没有结论」的结果还不知情。
     * 所以 agent_loop 自带 step 计数器：到 {@code maxSteps} 仍没 {@code answer} 就
     * fail-close 抛 {@code WORKFLOW_AGENT_LOOP_NOT_CONVERGED}。recursionLimit 只作全局兜底。
     *
     * <p>给 6：与 plan-as-workflow 生成循环的 {@code asWorkflowMaxRounds} 同量级。太小（如 2）模型来不及「查两轮再答」，
     * 太大则每步都是一次真实模型调用 + 计费，同时也是单节点最长耗时的上界。
     */
    private Integer defaultMaxSteps = 6;

    /**
     * 单轮回复的最大 token
     *
     * <p>🔴 必须显式给，不能落到模型自身配置上 —— 与规划器同一个坑：
     * 模型默认 {@code max_tokens} 偏小时，一份带工具调用的 JSON 会从中间断掉，
     * 表现为「解析失败」，看着像模型不听话，实际是没让它说完。
     */
    private Integer maxTokens = 8000;

    /**
     * 采样温度
     *
     * <p>循环要的是稳定的工具调用与收敛，不是创意，温度给低。
     */
    private Double temperature = 0.1;

    /**
     * 回灌给模型的单个工具结果的字符上限
     *
     * <p>{@code datamodel_list} 这类工具在实测库里能返回几百条，整份塞回 prompt
     * 会把后面几步的预算全吃掉，而模型真正需要的往往只是「有没有像样的候选」。
     * 仅对 {@code isTruncateResult()==true} 的工具生效（白名单类工具不截断，
     * 否则模型拿到「看似完整、实则残缺」的清单会照着编）。
     */
    private Integer toolResultMaxChars = 4000;

    /**
     * 解析失败自修正的独立小上限
     *
     * <h3>🔴 为什么要和 maxSteps 分开</h3>
     * 解析失败回灌让模型重来时<b>不计入 step</b>（那是格式问题，不是「又探索了一步」）。
     * 若不给独立上限，一个反复输出坏 JSON 的模型会在「回灌→再坏」之间无限打转、
     * 既不推进 step 也不收敛。这个小上限专门防这种死循环：超过就 fail-close。
     */
    private Integer maxParseRetries = 2;

}
