package cn.enlev.ainb.module.ai.service.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AiModelFailureReason} 单测
 *
 * 这个枚举是缺陷 D4-28 的唯一判据入口，它判错的两个方向代价完全不对称：
 * 把「额度耗尽」判成瞬时 ⟹ 静默换模型，两轮跑的不是同一个东西（本次要修的就是它）；
 * 把「网络抖动」判成额度 ⟹ 好模型被长冷却拉黑，整个功能停摆。
 * 所以判据本身必须被钉死，不能只测上层行为。
 *
 * @author 走进新时代
 */
class AiModelFailureReasonTest {

    // ==================== 额度类：必须判 QUOTA ====================

    @Test
    void testClassify_realWorldFreeTierExhausted_isQuota() {
        // 🔴 逐字取自现场后端日志的厂商 403 响应体（实测，非构造）。
        // 这条是 D4-28 的起因：它当时被判成「调用失败、换下一个」，于是一轮 e2e 里
        // CHAT 从 model 76 漂到 78 再漂到 80，库里只留下最后成功那个，看不出漂过。
        String body = "403 - {\"code\":\"AllocationQuota.FreeTierOnly\",\"message\":\"Free quota exhausted. "
                + "To continue accessing the model on a paid basis, please add funds or disable the "
                + "\\\"use free tier only\\\" mode in the management console.\"}";

        assertEquals(AiModelFailureReason.QUOTA,
                AiModelFailureReason.classify(new RuntimeException(body)));
    }

    @Test
    void testClassify_insufficientQuota_isQuota() {
        assertEquals(AiModelFailureReason.QUOTA, AiModelFailureReason.classify(
                new RuntimeException("{\"error\":{\"type\":\"insufficient_quota\",\"message\":\"exceeded\"}}")));
    }

    @Test
    void testClassify_markerIsCaseInsensitive() {
        assertEquals(AiModelFailureReason.QUOTA, AiModelFailureReason.classify(
                new RuntimeException("allocationquota.freetieronly")));
    }

    @Test
    void testClassify_quotaNestedInCauseChain_isQuota() {
        // 厂商 SDK 常把响应体包在两三层异常里，只看最外层 message 会漏判 ——
        // 漏判的后果就是防线等于没加，所以这条单独钉住
        Throwable deep = new RuntimeException("调用失败",
                new IllegalStateException("HTTP 403",
                        new RuntimeException("AllocationQuota.FreeTierOnly")));

        assertEquals(AiModelFailureReason.QUOTA, AiModelFailureReason.classify(deep));
    }

    // ==================== 配置类：必须判 CONFIG（切换 + 长冷却） ====================

    @Test
    void testClassify_realWorldUrlError_isConfig() {
        // 🔴 逐字取自 2026-10-10 现场后端日志：链首模型 77 的确定性失败原文。
        // 它当时被判成 TRANSIENT ⟹ 60 秒冷却 ⟹ 19:24:11 把同一场 23 秒风暴原样重演。
        // 同一份配置重试必然同样失败，所以必须单独分型、走长冷却。
        String body = "400 - {\"code\":\"InvalidParameter\",\"message\":\"url error, please check url！\"}";

        assertEquals(AiModelFailureReason.CONFIG,
                AiModelFailureReason.classify(new RuntimeException(body)));
    }

    @Test
    void testClassify_realWorldModelNotExist_isConfig() {
        assertEquals(AiModelFailureReason.CONFIG, AiModelFailureReason.classify(
                new RuntimeException("404 - {\"code\":\"ModelNotFound\",\"message\":\"Model not exist\"}")));
    }

    @Test
    void testClassify_realWorldThinkingRestricted_isConfig() {
        // 模型 79（qwen3.7-max-2026-05-17）只支持思考模式，而「文本端点 ⇒ 思考关」这条
        // 硬规则把它判死。厂商自己说出了病因，我们只需认出来
        assertEquals(AiModelFailureReason.CONFIG, AiModelFailureReason.classify(
                new RuntimeException("400 - enable_thinking parameter is restricted to True")));
    }

    @Test
    void testClassify_explicitInvalidApiKey_isConfig() {
        // 🔴 与下面那条「裸 401 仍判 TRANSIENT」的差别是刻意的：
        // 厂商逐字说出「密钥无效」是配置事实（这一个密钥/模型的事），
        // 而裸 401 也可能是网关抖动 —— 认不准时不许锁 30 分钟
        assertEquals(AiModelFailureReason.CONFIG, AiModelFailureReason.classify(
                new RuntimeException("401 - {\"code\":\"InvalidApiKey\",\"message\":\"Invalid API-key provided\"}")));
        assertEquals(AiModelFailureReason.CONFIG, AiModelFailureReason.classify(
                new RuntimeException("{\"error\":{\"type\":\"invalid_api_key\"}}")));
    }

    @Test
    void testClassify_configMarkerNestedInCauseChain_isConfig() {
        Throwable deep = new RuntimeException("调用失败",
                new IllegalStateException("HTTP 400", new RuntimeException("Model not exist")));

        assertEquals(AiModelFailureReason.CONFIG, AiModelFailureReason.classify(deep));
    }

    @Test
    void testClassify_quotaWinsOverConfigInTheSameChain() {
        // 🔴 两轮独立全链扫描、额度优先，不是「谁先命中谁赢」：
        // 外层带一句配置类措辞、内层 cause 才是真正的额度响应时，
        // 按先命中判会把额度耗尽降级成可切换 —— 那正是裁定 R-01 要防的静默漂移
        Throwable quotaInner = new RuntimeException("外层 enable_thinking 措辞",
                new RuntimeException("AllocationQuota.FreeTierOnly"));

        assertEquals(AiModelFailureReason.QUOTA, AiModelFailureReason.classify(quotaInner));
    }

    @Test
    void testClassify_bareInvalidParameter_isNotConfig() {
        // 🔴 最容易写错的一条：CONFIG_MARKERS 刻意不收裸 InvalidParameter / 裸 400 / 裸 401。
        // InvalidParameter 同时覆盖【请求级】参数错误（这一次调用写错了），
        // 按它判会把一个本来好的模型锁死 30 分钟 ——
        // 误判成 CONFIG 的代价远大于误判成 TRANSIENT（60 秒后多试一次）
        assertEquals(AiModelFailureReason.TRANSIENT, AiModelFailureReason.classify(
                new RuntimeException("400 - {\"code\":\"InvalidParameter\",\"message\":\"messages is empty\"}")));
    }

    // ==================== 瞬时类：必须判 TRANSIENT（照旧切换） ====================

    @Test
    void testClassify_authFailure_isTransient() {
        // 裸 401 认不准（可能是密钥写错，也可能是网关抖动），fail-open 到切换。
        // 厂商逐字说出密钥无效的那一档才判 CONFIG，见 testClassify_explicitInvalidApiKey_isConfig
        assertEquals(AiModelFailureReason.TRANSIENT,
                AiModelFailureReason.classify(new RuntimeException("401 Unauthorized")));
    }

    @Test
    void testClassify_timeout_isTransient() {
        assertEquals(AiModelFailureReason.TRANSIENT,
                AiModelFailureReason.classify(new RuntimeException("timeout")));
    }

    @Test
    void testClassify_rateLimiting_isTransient() {
        // 🔴 最容易写错的一条：这里的字面量带 "Quota"。
        // Throttling.RateQuota 是「每秒限流」，等一会儿就好，切下一个候选是对的；
        // 判成额度耗尽会把一个好模型长冷却 30 分钟。
        // QUOTA_MARKERS 刻意不收裸词 "quota"，就是为了不撞上它。
        assertEquals(AiModelFailureReason.TRANSIENT, AiModelFailureReason.classify(
                new RuntimeException("{\"code\":\"Throttling.RateQuota\",\"message\":\"Requests rate limit exceeded\"}")));
    }

    @Test
    void testClassify_unrecognized_failsOpenToTransient() {
        // 认不出来一律按瞬时：fail-open 到切换。代价是「多切一次候选」，
        // 反过来按额度处理会把一次网络抖动判成额度耗尽、整个功能停摆 —— 后者破坏力大得多
        assertEquals(AiModelFailureReason.TRANSIENT,
                AiModelFailureReason.classify(new RuntimeException("connection reset by peer")));
    }

    @Test
    void testClassify_nullAndBlankMessage_isTransient() {
        assertEquals(AiModelFailureReason.TRANSIENT, AiModelFailureReason.classify(null));
        assertEquals(AiModelFailureReason.TRANSIENT, AiModelFailureReason.classify(new RuntimeException()));
        assertEquals(AiModelFailureReason.TRANSIENT, AiModelFailureReason.classify(new RuntimeException("")));
    }

    // ==================== 分类工具自身不能把调用方打挂 ====================

    @Test
    void testClassify_cyclicCauseChain_terminatesInsteadOfStackOverflow() {
        // 限深 10 而不是 while(true)。Java 禁止 ex.initCause(ex) 的直接自引用，
        // 但两元素环是能构造出来的，真实代码里也出现过 —— 一个「分类工具」在异常路径上
        // 抛 StackOverflowError，会把原本的失败原因整个盖掉
        Throwable a = new RuntimeException("a");
        Throwable b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);

        assertEquals(AiModelFailureReason.TRANSIENT, AiModelFailureReason.classify(a));
    }

}
