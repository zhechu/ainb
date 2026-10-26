package cn.enlev.ainb.module.agent.framework.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI 助手的对话配置
 *
 * @author 走进新时代
 */
@ConfigurationProperties(prefix = "enlev.agent.chat")
@Validated
@Data
public class AgentChatProperties {

    /**
     * 历史上下文的 token 预算
     *
     * <h3>🔴 为什么按 token 而不是按轮数</h3>
     * 上游 {@code AiChatMessageServiceImpl#filterContextMessages} 按<b>条数</b>截断
     * （最多 {@code maxContexts * 2} 条）。问题是一条消息可能是 50 字，
     * 也可能是塞了 5 段手册的 4000 token —— 实测第一次 RAG 提问，输入就占了 4076。
     * <b>按条数截断等于假装每条一样大</b>，长会话照样会超模型窗口，
     * 而超窗口的报错来自厂商，信息含糊、事后极难定位到「是哪一轮撑爆的」。
     *
     * <p>给 12000 是留足余量的保守值：qwen3.7-plus 的窗口远不止这个数，
     * 但历史占得越多，留给当轮检索结果与回答的就越少。
     * 助手的价值在于<b>答准当前这个问题</b>，不是记住二十轮之前说过什么。
     */
    private Integer historyTokenBudget = 12000;

    /**
     * 单次回答的最大 token
     */
    private Integer maxTokens = 2000;

    /**
     * 采样温度（缺陷 D4-18）
     *
     * <h3>🔴 为什么必须显式钉住：不钉就等于用库里的 {@code ai_model.temperature}</h3>
     * 本路径原先<b>压根没设</b> temperature。留空<b>不是</b>「不表态、走厂商默认」——
     * {@code AiModelApiImpl#buildPrompt} 有一层兜底：入参为 null 就取
     * {@code model.getTemperature()}，即该模型在 {@code ai_model} 表里存的那个数。
     * 而实测这张表里 CHAT 用的 {@code 76/qwen3.5-plus-2026-02-15} 存的是
     * <b>{@code temperature=1}</b>（同批 70/71 也都是 1）—— 通义可选区间的<b>最上端</b>，
     * 比厂商默认的 ~0.7 还要随机得多。
     *
     * <p>🔴 所以别把这个缺陷记成「厂商默认值不合适」：病因是<b>一行可以直接 SELECT 出来的库配置</b>，
     * 且它被一层静默兜底挡住了，从代码上看只像「忘了传」。
     * 而模块里其余每一条调模型的路径都显式钉了：工具循环 {@code 0.1}
     * （{@code AgentLoopProperties.temperature}）、意图分型 {@code 0.0}
     * （{@code AgentIntentClassifier#classify}）、llm / llm_condition 节点取自身 config。
     * 也就是说<b>唯独用户问答这一条在满随机采样</b>。
     *
     * <p>实测代价（2026-10-05，同一条提问、同一份检索结果、同一个模型
     * {@code 76/qwen3.5-plus-2026-02-15}，两轮全量跑对比）：
     * <ul>
     *   <li>id=3「三十人的设备运维班组」：run9 答 <b>313 字</b> → run12 只剩 <b>11 字</b>的
     *       「手册里没有覆盖这个问题」</li>
     *   <li>id=7「存培训记录还要能查询统计」：run9 答 <b>609 字</b> → run12 同样 11 字拒答</li>
     *   <li>id=73「金额超一万时显示审批理由」：反过来，run9 拒答 → run12 实答</li>
     * </ul>
     * 已逐个排掉别的病因：检索<b>没有</b>失败（{@code [retrieve][检索失败} 0 次，
     * {@code doc_search} 每次都 300~900ms 正常返回）；<b>没有</b>换模型（两轮 CHAT 都是
     * model 76、各 40 次调用；那 19 次 {@code AllocationQuota} 403 全落在 model 70 上，
     * 被 {@code AiModelFailoverServiceImpl} 吸收，不在问答路径上）；知识库与提示词两轮之间一个字没动。
     *
     * <p>🔴 <b>连带后果比「答案不稳定」严重</b>：T1 的拒答棘轮数的是「拒答条数」，
     * 而每条的答/拒答在默认温度下接近掷骰子。35 条可比总体、拒答率 ~63% ⟹ 二项标准差 ≈ 2.9，
     * <b>净 ±1 完全落在 1σ 之内</b> —— 一个 {@code <= 基线} 的硬闸门于是不管有没有人改坏东西，
     * 都按构造红掉约一半的轮次。run9 报 22、run12 报 23，红点两次都是它，两次都不是真回归。
     *
     * <p>取 0.1 与工具循环一致：问答要的是<b>忠于检索到的手册片段</b>（提示词里就写着
     * 「若片段里没有答案，直接说…不要编造」），不是文采。要调回高温度改配置即可，不必改代码。
     */
    private Double temperature = 0.1;

    /**
     * 是否开启对话后建议（方案 §9）
     *
     * 方案 §9.3 要求「用户可在设置里关闭」。先做成租户级配置项，
     * 用户级偏好等前端设置页一起做。
     */
    private Boolean suggestionEnabled = true;

    /**
     * 流式响应的超时
     *
     * <h3>为什么必须显式兜底</h3>
     * {@code AiModelApi.chatStream} 的注释里写明了：厂商连接挂起时
     * （连接还在、就是不发数据），{@code doOnComplete}/{@code doOnError}/{@code doOnCancel}
     * <b>三条都不触发</b>。那样这次调用既不会结束、也不会落计量日志，
     * 前端界面会一直转圈 —— 而且一个挂死的 SSE 连接会一直占着线程。
     */
    private Integer streamTimeoutSeconds = 120;

    /**
     * 会话标题取首条提问的前几个字
     */
    private Integer titleMaxLength = 20;

}
