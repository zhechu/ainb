package cn.enlev.ainb.module.agent.framework.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Webhook 匿名入站端点的配置（B2-5 / R-10 ①：无头入口收口）
 *
 * <h3>这个端点为什么必须有限流</h3>
 * {@code AgentWebhookController} 是 agent 模块<b>唯一的匿名可达面</b>（挂 {@code @PermitAll}），
 * 调用方无需登录即可 POST。在它之前没有任何「按调用方身份计量」的手段：验签只能证明
 * 「这个请求来自持密钥的人」，证明不了「他没在一秒钟内发一万个」。
 * 于是每一次命中都是一次<b>无头拉起流程</b>——烧 token、写业务数据、占图执行线程。
 * 没有限流的匿名入口，等于把「谁能拉起我的流程」这个问题的答案从「持密钥者」放宽成「任何人」。
 *
 * <h3>为什么阈值一律走配置、不写死在 java 里</h3>
 * 限流阈值是<b>运营参数</b>不是<b>代码常量</b>：对接方从「一天几十个事件」长到「一秒几十个事件」时，
 * 该调的是配置而不是重新发版。写死在 java 里的阈值，等到真被业务量顶穿时，
 * 现场唯一的止血手段就是改代码 + 重启——那正是最慢的一种。
 *
 * @author 走进新时代
 */
@ConfigurationProperties(prefix = "enlev.agent.webhook")
@Validated
@Data
public class AgentWebhookProperties {

    /**
     * 是否开启入站限流
     *
     * <h3>留这个开关是为了「限流器自己成为故障源」时能止血</h3>
     * 限流依赖 Redis（Redisson {@code RRateLimiter}），而 Redis 异常时本组件按 <b>fail-close</b> 处理
     * （宁可拒掉合法回调也不让匿名面裸奔，理由见 {@code AgentWebhookRateLimiter}）。
     * 所以真出现「Redisson 在某种网络状态下把每个请求都拖慢两秒」这类问题时，
     * 现场需要一个不必回滚代码就能让入站恢复通行的手段，就是这个开关。
     *
     * <p>🔴 关掉之后本端点就<b>回到「谁都能打」的状态</b>，这是知情后的取舍，不是无副作用的开关。
     */
    private Boolean rateLimitEnabled = true;

    /**
     * IP 维度：单个来源 IP 在一个窗口内最多放行多少个请求
     *
     * <h3>为什么给 60/分钟（平均 1 秒 1 个）</h3>
     * 正当的对接方是「业务事件发生 → 回调一次」：订单支付、表单提交、监控告警。
     * 这类事件的自然频率远低于每秒一个，60 已经给足了突发余量（例如批量补推历史数据）。
     * 再往上放，挡不住任何东西——刷量脚本不在乎阈值是 60 还是 600。
     *
     * <p>🔴 **这一层是「挡老实人的误配置」，不是「挡攻击者」**：来源 IP 取自
     * {@code ServletUtils.getClientIP()}，而它按 hutool 的默认头序先读
     * {@code X-Forwarded-For} / {@code X-Real-IP} 等<b>客户端可控</b>的请求头。
     * 在没有可信反向代理兜住这些头的部署里，直连客户端改一个头就能换掉自己的「IP」。
     * 真正承重的是下面那层 path 维度——这正是两层必须是 AND 而不是 OR 的原因。
     */
    private Integer rateLimitIpCount = 60;

    /**
     * IP 维度的窗口长度（秒）
     *
     * <p>给 60 而不是 1：窗口太短会让「正常的一批事件同时到达」被误伤
     * （例如对接方每分钟整点批量推 30 条，用 1 秒窗口就会撞上瞬时峰值）；
     * 窗口太长则失去了「快速止血」的意义。一分钟是这两者的常规折中。
     */
    private Integer rateLimitIpWindowSeconds = 60;

    /**
     * path 维度：单个 webhook path 在一个窗口内最多放行多少个请求（<b>跨所有来源 IP 合计</b>）
     *
     * <h3>为什么必须有这一层，且它比 IP 层更重要</h3>
     * IP 层护的是「一个来源别刷太狠」，但它护不住<b>分散来源</b>：
     * 一百台机器各发 50 个，IP 层一个都不拦，而那个 hook 背后的流程已经被无头拉起了五千次。
     * path 维度按「被保护的资源」计数而不是按「请求的来源」计数，
     * 所以它护的正是本端点唯一真正稀缺的东西——<b>一个 hook 背后那条流程的 token 预算与写库次数</b>。
     *
     * <p>🔴 阈值刻意给得比 IP 层高（300 &gt; 60）：单个来源永远撞不到这一层，
     * 只有「多个来源打同一个 path」才会。这是有意的设计，不是疏漏——
     * 两层各管一件事，谁也不替谁兜底。
     */
    private Integer rateLimitPathCount = 300;

    /**
     * path 维度的窗口长度（秒）
     *
     * <p>与 IP 层同取 60，是为了让两层的窗口对齐：
     * 窗口不一致时，「哪一层先撞墙」会随时间漂移，现场排障时很难解释清楚。
     */
    private Integer rateLimitPathWindowSeconds = 60;

    /**
     * 入站 body 的<b>整体</b>字符数上限（B2-4 / R-10 ② 的「第一道闸」）
     *
     * <h3>🔴 它与 {@code AgentSanitizeProperties} 里那些「按叶子」的上限不是一回事，两个都要有</h3>
     * 那边量的是<b>单个字符串叶子</b>，这边量的是<b>整个原始 body</b>。
     * 一个 body 完全可以在每个叶子都不超档的前提下、靠「叶子特别多」把总量堆到几百 MB ——
     * 只按叶子限长时我们要把它整个解析、整个遍历完才发现每个都合规，代价已经付掉了。
     *
     * <h3>🔴 为什么默认给到 1 MiB，比叶子档（webhook 32 KiB）高一个数量级还多</h3>
     * 因为它护的不是「一个字段能有多长」，而是「一次回调能带多少东西」。
     * 合法的批量事件（例如一次推 200 条变更）本来就该被放行，把这道闸门收到跟叶子档一样紧，
     * 结果就是正当对接方天天撞墙、最后现场把它调大到形同虚设 —— 那比一开始就给个宽松值更糟。
     * 所以分工是：<b>这道管荒谬的量（拒），叶子档管单字段的量（截）</b>。
     *
     * <p>触发时的完整语义、以及「它<b>不</b>护内存」这条必须知情的限制，
     * 见 {@code ErrorCodeConstants.WORKFLOW_WEBHOOK_BODY_TOO_LARGE} 的注释。
     */
    private Integer maxBodyLength = 1048576;

}
