package cn.enlev.ainb.module.agent.framework.agent.core.security;

import cn.hutool.crypto.SecureUtil;
import cn.enlev.ainb.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentWebhookProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_WEBHOOK_RATE_LIMITED;

/**
 * Webhook 匿名入站的双维度限流闸（B2-5 / R-10 ①）
 *
 * <h3>它守在整条入站链路的最外层，比验签更靠前</h3>
 * 顺序是<b>有意</b>的：限流 → 验签 → 反查流程 → 无头拉起。
 * 把限流放在验签之后，就等于让「验签失败」这条路径不限流——
 * 而爆破签名恰恰是最该被限流的一种流量（它同样消耗 CPU、同样打日志、同样是匿名可达）。
 * 放在最外层，则连「path 存不存在」都不必查就能把量挡回去。
 *
 * <h3>🔴 为什么是两层 AND，而不是一层就够</h3>
 * 两层各护一样东西，谁也不能替谁兜底：
 * <ul>
 *   <li><b>IP 维度</b>护「一个来源别刷太狠」。但它的来源 IP 取自客户端可控的
 *       {@code X-Forwarded-For} / {@code X-Real-IP} 等头（{@code ServletUtils.getClientIP()} 的默认行为），
 *       在没有可信反代兜住这些头的部署里，<b>直连客户端改一个头就能换掉自己的「IP」</b>。</li>
 *   <li><b>path 维度</b>护「一个 hook 背后那条流程的 token 预算与写库次数」，按<b>被保护的资源</b>计数、
 *       跨所有来源合计。它不看来源，所以伪造 IP 对它无效。</li>
 * </ul>
 * ⟹ 任一层超限即拒。若只留 IP 层，一百台机器各发 50 个就能把一条流程拉起五千次；
 * 若只留 path 层，则「一个来源打满所有 path」这种扫射没人管。
 *
 * <h3>🔴 Redis 异常时 fail-close，与同端点的验签同一口径</h3>
 * 限流器自己出问题（Redis 不可达、Redisson 抛异常）时，<b>拒绝</b>而不是放行。
 * 理由不是「保守」，而是一致性：{@code AgentWebhookTriggerServiceImpl} 的验签已经是
 * 「缺失/不符/未知模式一律拒，绝不降级放行——否则匿名入站面就等于裸奔」。
 * 同一个匿名面上的两道闸，一道 fail-close、一道 fail-open，等于把整条链路降级到 fail-open 那一道的水平。
 * 且 Redis 在本平台还承载着登录态与租户过滤，它真挂了的话整站已经不可用，
 * 「为了可用性放行 webhook」换不回什么。
 * 需要「Redis 故障时仍要入站通行」是运营决策，走 {@code rate-limit-enabled=false}，不走这里的静默降级。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class AgentWebhookRateLimiter {

    /**
     * Redis key 前缀。两层各自独立命名空间，避免「IP 的计数」与「path 的计数」撞进同一个 limiter。
     *
     * <p>{@code RateLimiterRedisDAO} 会再套一层 {@code rate_limiter:%s}，
     * 故最终 key 形如 {@code rate_limiter:agent_webhook_ip:<md5>}。
     */
    private static final String KEY_PREFIX_IP = "agent_webhook_ip:";
    private static final String KEY_PREFIX_PATH = "agent_webhook_path:";

    /**
     * 取不到来源 IP 时的兜底桶名。
     *
     * <p>🔴 它<b>不是</b>「跳过限流」的意思：拿不到 IP 的请求会全部落进这一个桶，
     * 于是这一层退化成一道全局闸。宁可误伤也不留空档——匿名面上「识别不出来源」
     * 恰恰是最该被拦的情形。正常走 controller 进来时取不到 IP 是几乎不可能的
     * （{@code getClientIP()} 至少能拿到 socket 远端地址），所以这个桶平时是空的。
     */
    private static final String UNKNOWN_IP = "unknown";

    @Resource
    private RateLimiterRedisDAO rateLimiterRedisDAO;

    @Resource
    private AgentWebhookProperties webhookProperties;

    /**
     * 放行或拒绝一个入站请求。
     *
     * <p>🔴 无返回值：通过就静默返回，超限直接抛 {@code WORKFLOW_WEBHOOK_RATE_LIMITED}。
     * 调用方（controller）不需要、也不应该有机会「看一眼结果再决定要不要拦」——
     * 那正是安全闸门最容易被绕过的形状。
     *
     * @param clientIp 来源 IP，由 web 层取出后显式传入（🔴 不在这里读 request：
     *                 本组件要能被单测直接调用，且「IP 只有 web 层拿得到」这件事应该体现在签名上）
     * @param path     webhook 键，即 URL 里的那一段
     */
    public void check(String clientIp, String path) {
        if (!Boolean.TRUE.equals(webhookProperties.getRateLimitEnabled())) {
            return;
        }
        // 🔴 IP 层先过：它同时把 path 层的 key 基数封住了 ——
        //    攻击者想用「每次换一个 path」把 Redis 撑满，先得过 IP 层这一关，
        //    故一个窗口内单个来源最多只能造出 ipCount 个 path limiter key（且每个 key 60 秒后过期）。
        checkIp(clientIp);
        checkPath(path);
    }

    private void checkIp(String clientIp) {
        String ip = clientIp == null || clientIp.isBlank() ? UNKNOWN_IP : clientIp;
        int count = webhookProperties.getRateLimitIpCount();
        int window = webhookProperties.getRateLimitIpWindowSeconds();
        if (!tryAcquire(KEY_PREFIX_IP + SecureUtil.md5(ip), count, window)) {
            log.warn("[checkIp][webhook 入站被 IP 维度限流：ip({}) 窗口({}s) 内超过 {} 次]", ip, window, count);
            throw exception(WORKFLOW_WEBHOOK_RATE_LIMITED, window);
        }
    }

    private void checkPath(String path) {
        int count = webhookProperties.getRateLimitPathCount();
        int window = webhookProperties.getRateLimitPathWindowSeconds();
        // 🔴 必须 hash：path 是调用方完全可控的字符串，直接拼进 Redis key 会让 key 长度无上界
        //    （框架自带的 ClientIpRateLimiterKeyResolver 用 MD5 也是同一个理由）。
        //    原始 path 已在上面的 warn 日志里，排障时不缺信息。
        if (!tryAcquire(KEY_PREFIX_PATH + SecureUtil.md5(path == null ? "" : path), count, window)) {
            log.warn("[checkPath][webhook 入站被 path 维度限流：path({}) 窗口({}s) 内超过 {} 次]", path, window, count);
            throw exception(WORKFLOW_WEBHOOK_RATE_LIMITED, window);
        }
    }

    /**
     * 取一个令牌。
     *
     * <p>🔴 这里 catch 住 Redis 异常并按 <b>false（拒绝）</b> 处理，即上文说的 fail-close。
     * 异常原文进 error 日志（红线第 7 条：不替 error alert 编病因，只准原样引用原文）。
     */
    private boolean tryAcquire(String key, int count, int windowSeconds) {
        try {
            return Boolean.TRUE.equals(
                    rateLimiterRedisDAO.tryAcquire(key, count, windowSeconds, TimeUnit.SECONDS));
        } catch (Exception ex) {
            log.error("[tryAcquire][限流器不可用，按 fail-close 拒绝本次入站：key({})]", key, ex);
            return false;
        }
    }

}
