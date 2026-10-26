package cn.enlev.ainb.module.agent.framework.agent.core.security;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentWebhookProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_WEBHOOK_RATE_LIMITED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentWebhookRateLimiter} 单测（B2-5 / R-10 ①：匿名入口双维度限流）
 *
 * <p>钉住六件事：
 * <ul>
 *   <li>① <b>两层真的都被调用</b>——「IP 与 path 双维度」最容易做成假的：
 *       只调一层也照样能编译、照样能过冒烟测试，而另一层的阈值就成了装饰。
 *       所以这里用 {@code times(2)} 把「一次 check 打两次 tryAcquire」钉死。</li>
 *   <li>② <b>任一层超限即拒</b>——两层各测一次，且测 path 层时让 IP 层<b>放行</b>，
 *       否则验的其实是 IP 层（这正是方案 §5 第 ③ 层判据要求 HTTP 实测「换 IP 打同一个 path」的同一个理由）。</li>
 *   <li>③ <b>阈值取自配置、不是写死在 java 里</b>——把配置改成一组非默认值，
 *       断言传给 {@code tryAcquire} 的 count/window 就是那组值。
 *       🔴 这一条是防「配置项加了但没人读」的假绿：字段存在 ≠ 字段生效。</li>
 *   <li>④ <b>fail-close</b>——Redis 抛异常时拒绝，不放行。与同端点的验签同一口径。</li>
 *   <li>⑤ <b>IP 层拒了就短路</b>——不再去碰 path 层的计数器。
 *       否则「一个刷量的来源」会顺带把那个 hook 的 path 预算一起吃掉，
 *       于是合法对接方在攻击期间被 path 层误伤：攻击者用别人的配额打你。</li>
 *   <li>⑥ <b>path 进 Redis key 前被 hash</b>——path 是调用方完全可控的字符串，
 *       直接拼进 key 会让 key 长度无上界。</li>
 * </ul>
 *
 * @author 走进新时代
 */
@DisplayName("agent Webhook 入站双维度限流")
class AgentWebhookRateLimiterTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentWebhookRateLimiter rateLimiter;

    @Mock
    private RateLimiterRedisDAO rateLimiterRedisDAO;

    /** 用真实的 Properties（不 mock）：要验的正是「配置值真的被读到并传下去」 */
    @Spy
    private AgentWebhookProperties webhookProperties = new AgentWebhookProperties();

    private static final String IP = "203.0.113.7";
    private static final String PATH = "order-paid-hook";

    /** IP 层的 key 前缀（与产品代码逐字一致；hash 之后拼在它后面） */
    private static final String IP_KEY_PREFIX = "agent_webhook_ip:";
    private static final String PATH_KEY_PREFIX = "agent_webhook_path:";

    @Test
    @DisplayName("①两层都被查：一次 check 恰好打两次 tryAcquire")
    void check_bothLayersQueried() {
        when(rateLimiterRedisDAO.tryAcquire(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(true);

        assertDoesNotThrow(() -> rateLimiter.check(IP, PATH));

        verify(rateLimiterRedisDAO, times(2))
                .tryAcquire(anyString(), anyInt(), anyInt(), any(TimeUnit.class));
        // 且两层的 key 落在各自独立的命名空间，不会撞进同一个 limiter
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(rateLimiterRedisDAO, times(2)).tryAcquire(keys.capture(), anyInt(), anyInt(), any());
        List<String> captured = keys.getAllValues();
        assertTrue(captured.get(0).startsWith(IP_KEY_PREFIX), "第一次必须是 IP 层，实际：" + captured.get(0));
        assertTrue(captured.get(1).startsWith(PATH_KEY_PREFIX), "第二次必须是 path 层，实际：" + captured.get(1));
    }

    @Test
    @DisplayName("②IP 层超限即拒，path 层此时不被查（短路）")
    void check_ipLayerExceeded_rejects() {
        when(rateLimiterRedisDAO.tryAcquire(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(false);

        ServiceException ex = assertThrows(ServiceException.class, () -> rateLimiter.check(IP, PATH));

        assertEquals(WORKFLOW_WEBHOOK_RATE_LIMITED.getCode(), ex.getCode());
        // ⑤ 短路：IP 层已经拒了，就不该再去消耗 path 层的配额
        verify(rateLimiterRedisDAO, times(1))
                .tryAcquire(anyString(), anyInt(), anyInt(), any(TimeUnit.class));
        verify(rateLimiterRedisDAO, never())
                .tryAcquire(startsWith(PATH_KEY_PREFIX), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("②path 层超限即拒——且测它时 IP 层必须放行")
    void check_pathLayerExceeded_rejects() {
        // 🔴 IP 层放行、path 层拒绝：若两层都返回 false，验到的其实是 IP 层，
        //    path 层那条阈值就是没人守的装饰（方案 §5 第 ③ 层判据的同一个陷阱）
        when(rateLimiterRedisDAO.tryAcquire(
                startsWith(IP_KEY_PREFIX), anyInt(), anyInt(), any()))
                .thenReturn(true);
        when(rateLimiterRedisDAO.tryAcquire(
                startsWith(PATH_KEY_PREFIX), anyInt(), anyInt(), any()))
                .thenReturn(false);

        ServiceException ex = assertThrows(ServiceException.class, () -> rateLimiter.check(IP, PATH));

        assertEquals(WORKFLOW_WEBHOOK_RATE_LIMITED.getCode(), ex.getCode());
        verify(rateLimiterRedisDAO, times(2))
                .tryAcquire(anyString(), anyInt(), anyInt(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("③阈值取自配置：改成非默认值后，传下去的就是那个值")
    void check_thresholdsComeFromConfig_notHardcoded() {
        // 刻意用一组与默认值（60/60、300/60）完全不同的数，
        // 这样「产品代码写死了默认值」也会让本条变红
        webhookProperties.setRateLimitIpCount(7);
        webhookProperties.setRateLimitIpWindowSeconds(11);
        webhookProperties.setRateLimitPathCount(13);
        webhookProperties.setRateLimitPathWindowSeconds(17);
        when(rateLimiterRedisDAO.tryAcquire(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(true);

        rateLimiter.check(IP, PATH);

        ArgumentCaptor<Integer> counts = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> windows = ArgumentCaptor.forClass(Integer.class);
        verify(rateLimiterRedisDAO, times(2)).tryAcquire(
                anyString(), counts.capture(), windows.capture(), any(TimeUnit.class));
        assertEquals(List.of(7, 13), counts.getAllValues(), "IP 层与 path 层的阈值必须各自来自配置");
        assertEquals(List.of(11, 17), windows.getAllValues(), "窗口长度同样必须来自配置");
    }

    @Test
    @DisplayName("④Redis 抛异常时 fail-close：拒绝，不放行")
    void check_redisFailure_failsClosed() {
        when(rateLimiterRedisDAO.tryAcquire(anyString(), anyInt(), anyInt(), any()))
                .thenThrow(new IllegalStateException("Unable to connect to Redis"));

        ServiceException ex = assertThrows(ServiceException.class, () -> rateLimiter.check(IP, PATH),
                "限流器自己坏了不等于匿名面可以裸奔：必须 fail-close");

        assertEquals(WORKFLOW_WEBHOOK_RATE_LIMITED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("④关掉开关后一次 Redis 都不碰（止血路径真的通）")
    void check_disabled_skipsRedisEntirely() {
        webhookProperties.setRateLimitEnabled(false);

        assertDoesNotThrow(() -> rateLimiter.check(IP, PATH));

        verify(rateLimiterRedisDAO, never())
                .tryAcquire(anyString(), anyInt(), anyInt(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("⑥path 被 hash 后才进 key：原始 path 不出现在 Redis key 里")
    void check_pathIsHashedInKey() {
        String hostile = "a".repeat(500) + "{{"; // 超长 + 含模板定界符，直接拼进 key 会让 key 无上界
        when(rateLimiterRedisDAO.tryAcquire(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(true);

        rateLimiter.check(IP, hostile);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(rateLimiterRedisDAO, times(2)).tryAcquire(keys.capture(), anyInt(), anyInt(), any());
        String pathKey = keys.getAllValues().get(1);
        assertTrue(pathKey.startsWith(PATH_KEY_PREFIX), "key 前缀应保留，便于运维按前缀扫");
        assertFalse(pathKey.contains(hostile), "原始 path 不该进 key，实际：" + pathKey);
        assertEquals(PATH_KEY_PREFIX.length() + 32, pathKey.length(), "MD5 十六进制恰好 32 位，key 长度因此有上界");
    }

    @Test
    @DisplayName("取不到 IP 时落进 unknown 桶，而不是跳过限流")
    void check_nullIp_stillRateLimited() {
        when(rateLimiterRedisDAO.tryAcquire(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(true);

        rateLimiter.check(null, PATH);
        rateLimiter.check("   ", PATH);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(rateLimiterRedisDAO, times(4)).tryAcquire(keys.capture(), anyInt(), anyInt(), any());
        // 两次的 IP key 必须相同（同一个 unknown 桶）——「识别不出来源」恰恰是最该被拦的情形，
        // 若各落一个桶，等于给匿名请求发了一张「换个空值就重新计数」的通行证
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(2),
                "null IP 与空白 IP 必须落进同一个桶");
    }

    @Test
    @DisplayName("窗口单位固定为秒：与配置字段的语义一致")
    void check_timeUnitIsSeconds() {
        when(rateLimiterRedisDAO.tryAcquire(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(true);

        rateLimiter.check(IP, PATH);

        verify(rateLimiterRedisDAO, times(2)).tryAcquire(
                anyString(), anyInt(), anyInt(), eq(TimeUnit.SECONDS));
    }

}
