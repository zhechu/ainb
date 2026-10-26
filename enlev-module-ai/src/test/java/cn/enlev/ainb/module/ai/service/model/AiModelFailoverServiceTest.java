package cn.enlev.ainb.module.ai.service.model;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import cn.enlev.ainb.module.ai.dal.mysql.model.AiChatMapper;
import cn.enlev.ainb.module.ai.framework.ai.core.model.AiModelFactory;
import cn.enlev.ainb.module.ai.service.model.bo.AiModelCandidate;
import cn.enlev.ainb.module.ai.service.model.bo.AiModelInvocationBO;
import cn.enlev.ainb.module.ai.util.AiUtils;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.API_CONFIG_PLACEHOLDER_NOT_RESOLVED;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.API_KEY_BLANK;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_ALL_UNAVAILABLE;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_QUOTA_EXHAUSTED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiModelFailoverServiceImpl} 单测
 *
 * 覆盖六块核心语义：
 *  ① 候选链构建：密钥过滤、requested 置顶、不可用静默降级、失败冷却与「全员冷却回退原链」
 *  ①' 配置类失败（CONFIG）：切换下一个候选（与 QUOTA 的关键差别）+ 30 分钟长冷却，
 *     且冷却记忆<b>自带配置指纹</b> —— 改了 url / 换了密钥 / 禁用再启用之后立刻解锁
 *  ② 同步切换：单候选失败切下一个、全部失败抛 MODEL_ALL_UNAVAILABLE、成功清除失败记忆
 *  ③ 流式切换边界：首包前失败自动切换；首包后失败原样传播且不订阅下一候选
 *  ④ 额度耗尽（D4-28）：判本次调用失败、绝不切换下一个候选，且下一个任务落到下一个候选
 *  ⑤ 任务级粘滞（D4-28）：任务内只用钉住的那个模型；它不可用时给空链，不回退默认链；
 *     钉的键含 requestedModelId ⟹ 同一任务里显式配了不同模型的节点各自独立钉
 *  ⑥ 配置错误（D4-29 第二段）：密钥没填 / ${} 解析不出来时<b>不</b>降级成空链，
 *     而是把带码的真实病因抛出去；同时钉住两条边界 —— 单个模型配错不影响其余候选，
 *     不带码的异常仍旧只给空链（由调用方决定文案）
 *
 * 🔴 ④⑤⑥ 三节是裁定 R-01 / 台账 D4-29 的判据落点，其中标注「变异探针靶点」的几条是 §11.2 双判据的靶子：
 * 把「额度判失败」退回「静默切换」、把粘滞那段删掉、把 pinKey 里的 requestedModelId 去掉、
 * 或把「链空则抛配置病因」那句删掉，都必须让本类变红。
 * ①' 那三条同理：把 CONFIG 退回 TRANSIENT、或把指纹判定整个删掉，都必须让本类变红。
 *
 * @author 走进新时代
 */
class AiModelFailoverServiceTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AiModelFailoverServiceImpl failoverService;

    @Mock
    private AiChatMapper modelMapper;
    @Mock
    private AiApiKeyService apiKeyService;
    @Mock
    private AiModelFactory modelFactory;

    private static final Integer TYPE_CHAT = 1;

    /** 任务标识，模拟 agent 侧的 {@code EXEC:{executionId}} */
    private static final String TASK_KEY = "EXEC:20261005-run-1";

    /**
     * 厂商 403 额度耗尽响应体
     *
     * 🔴 逐字取自现场后端日志（实测，非构造）。用它而不是随手编一句 "quota exceeded"，
     * 是为了让「分类判据」和「真实报文」之间始终只差一次字符串匹配 ——
     * 拿构造的短句测出来的绿，证明不了现场那条能被判对。
     */
    private static final String QUOTA_403_BODY = "403 - {\"code\":\"AllocationQuota.FreeTierOnly\","
            + "\"message\":\"Free quota exhausted. To continue accessing the model on a paid basis, "
            + "please add funds or disable the \\\"use free tier only\\\" mode in the management console.\"}";

    /**
     * 厂商「端点选错」的原文
     *
     * 🔴 逐字取自 2026-10-10 现场后端日志（模型 77 的链首失败）。它是 CONFIG 分型的起因：
     * 确定性失败被判成瞬时 ⟹ 60 秒冷却 ⟹ 19:24:11 把同一场 23 秒风暴原样重演
     */
    private static final String URL_ERROR_BODY = "400 - {\"code\":\"InvalidParameter\","
            + "\"message\":\"url error, please check url！\"}";

    /** 厂商 401「密钥无效」的原文。CONFIG_MARKERS 认的是这句逐字措辞，不是裸 401 */
    private static final String INVALID_API_KEY_BODY = "401 - {\"code\":\"InvalidApiKey\","
            + "\"message\":\"Invalid API-key provided\"}";

    /**
     * 配置指纹用的两个 {@code update_time}
     *
     * <p>刻意用真实的时间类型而不是字符串：指纹取自 {@code BaseDO#updateTime}，
     * 而 MyBatis-Plus 的 {@code FieldFill.INSERT_UPDATE} 保证任何一次 {@code updateById}
     * 都会刷新它 —— 包括只写 status 一个字段的启用/停用端点
     */
    private static final LocalDateTime CONFIG_V1 = LocalDateTime.of(2026, 10, 10, 19, 20, 0);
    private static final LocalDateTime CONFIG_V2 = LocalDateTime.of(2026, 10, 10, 19, 35, 0);

    // ==================== ① 候选链构建 ====================

    @Test
    void testBuildCandidateChain_filterDisabledKey_keepSortOrder() {
        // m2 的密钥被禁用：必须在选择期剔除，而不是等运行时 validateApiKey 才炸
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m2 = model(2L, 20L, 2);
        AiModelDO m3 = model(3L, 10L, 3);
        mockModels(m1, m2, m3);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(
                apiKey(10L, CommonStatusEnum.ENABLE), apiKey(20L, CommonStatusEnum.DISABLE)));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenReturn(mock(ChatModel.class));

        List<AiModelCandidate> chain = failoverService.buildCandidateChain(null, TYPE_CHAT);

        assertEquals(List.of(1L, 3L), convertList(chain, c -> c.getModel().getId()));
    }

    @Test
    void testBuildCandidateChain_requestedModelFirst() {
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m3 = model(3L, 10L, 3);
        mockModels(m1, m3);
        when(modelMapper.selectById(3L)).thenReturn(m3);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(apiKey(10L, CommonStatusEnum.ENABLE)));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenReturn(mock(ChatModel.class));

        List<AiModelCandidate> chain = failoverService.buildCandidateChain(3L, TYPE_CHAT);

        assertEquals(List.of(3L, 1L), convertList(chain, c -> c.getModel().getId()));
    }

    @Test
    void testBuildCandidateChain_requestedUnavailable_degradeSilently() {
        // 会话快照的模型密钥被禁用：不抛异常，静默降级到默认链
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m2 = model(2L, 20L, 2);
        mockModels(m1);
        when(modelMapper.selectById(2L)).thenReturn(m2);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(
                apiKey(10L, CommonStatusEnum.ENABLE), apiKey(20L, CommonStatusEnum.DISABLE)));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenReturn(mock(ChatModel.class));

        List<AiModelCandidate> chain = failoverService.buildCandidateChain(2L, TYPE_CHAT);

        assertEquals(List.of(1L), convertList(chain, c -> c.getModel().getId()));
    }

    @Test
    void testBuildCandidateChain_cooldown_andFallbackWhenAllCooling() {
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m3 = model(3L, 10L, 3);
        mockModels(m1, m3);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(apiKey(10L, CommonStatusEnum.ENABLE)));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenReturn(mock(ChatModel.class));

        // m1 调用失败 → 冷却期内被跳过
        assertThrows(ServiceException.class, () -> failoverService.callWithFallback(
                List.of(candidate(m1)), c -> {
                    throw new RuntimeException("timeout");
                }));
        assertEquals(List.of(3L), chainIds());

        // m3 也失败 → 全员冷却，放弃过滤回退原链（冷却是优化，不能自我打击成服务不可用）
        assertThrows(ServiceException.class, () -> failoverService.callWithFallback(
                List.of(candidate(m3)), c -> {
                    throw new RuntimeException("timeout");
                }));
        assertEquals(List.of(1L, 3L), chainIds());

        // m1 成功 → 清除失败记忆；m3 仍在冷却 → 链只剩 m1
        failoverService.callWithFallback(List.of(candidate(m1)), c -> "ok");
        assertEquals(List.of(1L), chainIds());
    }

    // ==================== ①' 配置类失败：长冷却 + 配置指纹解锁 ====================

    @Test
    void testConfigFailure_switchesCandidate_andIsExcludedFromNextChain() {
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m3 = model(3L, 10L, 3);
        mockModels(m1, m3);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(apiKey(10L, CommonStatusEnum.ENABLE)));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenReturn(mock(ChatModel.class));
        // 生产路径上 recordXxx 一定发生在建链之后，所以先把指纹登记进去
        assertEquals(List.of(1L, 3L), chainIds());

        // 🔴 与 QUOTA 的关键差别是「切不切」：配置错误是【模型级】事实，换一个就对了
        AiModelInvocationBO<String> result = failoverService.callWithFallback(
                List.of(candidate(m1), candidate(m3)),
                c -> {
                    if (c.getModel().getId() == 1L) {
                        throw new RuntimeException(URL_ERROR_BODY);
                    }
                    return "ok-" + c.getModel().getId();
                });
        assertEquals("ok-3", result.getPayload());

        // 而同一份配置重试必然同样失败 ⟹ 下一次建链必须把它拦在链外（60 秒后重演风暴是纯浪费）
        assertEquals(List.of(3L), chainIds());
    }

    @Test
    void testConfigCooldown_invalidatedAsSoonAsConfigFingerprintChanges() {
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m3 = model(3L, 10L, 3);
        AiApiKeyDO key = apiKey(10L, CommonStatusEnum.ENABLE);
        m1.setUpdateTime(CONFIG_V1);
        m3.setUpdateTime(CONFIG_V1);
        key.setUpdateTime(CONFIG_V1);
        mockModels(m1, m3);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(key));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenReturn(mock(ChatModel.class));
        assertEquals(List.of(1L, 3L), chainIds());

        assertThrows(ServiceException.class, () -> failoverService.callWithFallback(
                List.of(candidate(m1)), c -> {
                    throw new RuntimeException(URL_ERROR_BODY);
                }));
        // 配置一个字没改 → 冷却必须仍然生效（这一格证明指纹没有把冷却整个抹掉）
        assertEquals(List.of(3L), chainIds());

        // 管理员改好了 url。updateById 一律刷新 update_time —— 这正是列表页「禁用 → 启用」
        // 两个按钮能当解锁开关用的原因
        m1.setUpdateTime(CONFIG_V2);
        assertEquals(List.of(1L, 3L), chainIds(),
                "改完配置必须【立刻】回到候选链：否则「测试按钮一点就通、生产链却还锁着它 30 分钟」，"
                        + "那比没有测试按钮更让人抓狂");
    }

    @Test
    void testConfigCooldown_alsoInvalidatedWhenTheApiKeyChanges() {
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m3 = model(3L, 10L, 3);
        AiApiKeyDO key = apiKey(10L, CommonStatusEnum.ENABLE);
        m1.setUpdateTime(CONFIG_V1);
        m3.setUpdateTime(CONFIG_V1);
        key.setUpdateTime(CONFIG_V1);
        mockModels(m1, m3);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(key));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenReturn(mock(ChatModel.class));
        assertEquals(List.of(1L, 3L), chainIds());

        assertThrows(ServiceException.class, () -> failoverService.callWithFallback(
                List.of(candidate(m1)), c -> {
                    throw new RuntimeException(INVALID_API_KEY_BODY);
                }));
        assertEquals(List.of(3L), chainIds());

        // 模型自己没动，只换了密钥：指纹里含 apiKey.update_time，所以照样解锁
        key.setUpdateTime(CONFIG_V2);
        assertEquals(List.of(1L, 3L), chainIds(),
                "换密钥同样必须解锁 —— 401 的病根在密钥上，只盯 model.update_time 会解不开");
    }

    @Test
    void testBuildChain_configFailureWhileBuilding_isConfigCooledNotTransient() {
        // 建链期的配置失败（密钥没填）字面上就是配置错误，与运行期 CONFIG 同档：
        // 原先记的是 60 秒瞬时冷却，于是每个请求都要重演一遍
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m3 = model(3L, 10L, 3);
        mockModels(m1, m3);
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenReturn(mock(ChatModel.class));
        // m1/m3 挂的密钥是空的：第 5 步会在建链时就判掉它们
        AiApiKeyDO blankKey = apiKey(10L, CommonStatusEnum.ENABLE, "");
        blankKey.setUpdateTime(CONFIG_V1);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(blankKey));

        // 全员密钥为空 ⟹ 一个候选都不剩 ⟹ 按既有纪律把带码的真实病因抛出去，不返回空链
        ServiceException ex = assertThrows(ServiceException.class,
                () -> failoverService.buildCandidateChain(null, TYPE_CHAT));
        assertEquals(API_KEY_BLANK.getCode(), ex.getCode());

        // 管理员填好了密钥（update_time 随之刷新）⟹ 配置类冷却立刻失效，不必等 30 分钟。
        // 🔴 这一格是「指对出路」的判据：病因说的是「去填密钥」，填完却还要干等，
        //    就等于让人以为填错了、再去反复启停模型
        AiApiKeyDO filledKey = apiKey(10L, CommonStatusEnum.ENABLE);
        filledKey.setUpdateTime(CONFIG_V2);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(filledKey));
        assertEquals(List.of(1L, 3L), chainIds());
    }

    // ==================== ② 同步切换 ====================

    @Test
    void testCallWithFallback_switchToNextOnFailure() {
        AiModelInvocationBO<String> result = failoverService.callWithFallback(
                List.of(candidate(model(1L, 10L, 1)), candidate(model(2L, 10L, 2))),
                c -> {
                    if (c.getModel().getId() == 1L) {
                        throw new RuntimeException("401 Unauthorized");
                    }
                    return "ok-" + c.getModel().getId();
                });

        assertEquals("ok-2", result.getPayload());
        assertEquals(2L, result.getModel().getId());
    }

    @Test
    void testCallWithFallback_allFail_throwsModelAllUnavailable() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> failoverService.callWithFallback(
                        List.of(candidate(model(1L, 10L, 1)), candidate(model(2L, 10L, 2))),
                        c -> {
                            throw new RuntimeException("boom");
                        }));
        assertEquals(MODEL_ALL_UNAVAILABLE.getCode(), ex.getCode());
    }

    // ==================== ③ 流式切换边界 ====================

    @Test
    void testStreamWithFallback_switchBeforeFirstElement() {
        List<AiModelInvocationBO<String>> items = failoverService.streamWithFallback(
                        List.of(candidate(model(1L, 10L, 1)), candidate(model(2L, 10L, 2))),
                        c -> c.getModel().getId() == 1L
                                ? Flux.error(new RuntimeException("connect refused"))
                                : Flux.just("chunk-1", "chunk-2"))
                .collectList().block();

        assertNotNull(items);
        assertEquals(2, items.size());
        // 全部元素都来自候选 2
        assertTrue(items.stream().allMatch(item -> item.getModel().getId() == 2L));
    }

    @Test
    void testStreamWithFallback_errorAfterFirstElement_propagatesAndNoSwitch() {
        AtomicBoolean subscribedSecond = new AtomicBoolean(false);
        Flux<AiModelInvocationBO<String>> flux = failoverService.streamWithFallback(
                List.of(candidate(model(1L, 10L, 1)), candidate(model(2L, 10L, 2))),
                c -> {
                    if (c.getModel().getId() == 1L) {
                        return Flux.concat(Flux.just("first-chunk"),
                                Flux.error(new RuntimeException("mid-stream broken")));
                    }
                    subscribedSecond.set(true);
                    return Flux.just("should-not-appear");
                });

        // 首包已发射：无法撤回前端已收到的内容，异常必须原样传播，且不得订阅下一候选
        RuntimeException ex = assertThrows(RuntimeException.class, () -> flux.collectList().block());
        assertTrue(ex.getMessage().contains("mid-stream broken"));
        assertFalse(subscribedSecond.get());
    }

    @Test
    void testStreamWithFallback_allFail_throwsModelAllUnavailable() {
        Flux<AiModelInvocationBO<String>> flux = failoverService.streamWithFallback(
                List.of(candidate(model(1L, 10L, 1)), candidate(model(2L, 10L, 2))),
                c -> Flux.error(new RuntimeException("down")));

        ServiceException ex = assertThrows(ServiceException.class, () -> flux.collectList().block());
        assertEquals(MODEL_ALL_UNAVAILABLE.getCode(), ex.getCode());
    }

    // ==================== ④ 额度耗尽：判失败、不切换（D4-28 / R-01） ====================

    @Test
    void testCallWithFallback_quotaExhausted_judgedAsFailureAndNoSwitch() {
        // 🔴 变异探针靶点：把 QUOTA 分支删掉（退回「一律切换」）本条必红。
        // 这正是 D4-28 要修的行为 —— 额度没了不许被下一个模型静默救回来
        AtomicBoolean invokedSecond = new AtomicBoolean(false);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> failoverService.callWithFallback(
                        List.of(candidate(model(1L, 10L, 1)), candidate(model(2L, 10L, 2))),
                        c -> {
                            if (c.getModel().getId() == 1L) {
                                throw new RuntimeException(QUOTA_403_BODY);
                            }
                            invokedSecond.set(true);
                            return "should-not-happen";
                        }));

        assertEquals(MODEL_QUOTA_EXHAUSTED.getCode(), ex.getCode());
        assertFalse(invokedSecond.get(), "额度耗尽时绝不能调用下一个候选");
    }

    @Test
    void testCallWithFallback_quotaExhausted_messageCarriesModelAndVendorText() {
        // 失败要「落库可查」靠的就是这条消息：agent 侧三条主链路把 ex.getMessage()
        // 原样写进 agent_usage_log.error_msg，所以消息里必须同时有【哪个模型】和【厂商原文】。
        // 刻意不加列 —— 本模块已有 failed_at_node 那根死列的前车之鉴
        ServiceException ex = assertThrows(ServiceException.class,
                () -> failoverService.callWithFallback(List.of(candidate(model(1L, 10L, 1))),
                        c -> {
                            throw new RuntimeException(QUOTA_403_BODY);
                        }));

        assertTrue(ex.getMessage().contains("1/m-1"), "要带模型编号/厂商标识，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("AllocationQuota.FreeTierOnly"),
                "要原样带上厂商错误码，不许改写或编病因，实际：" + ex.getMessage());
    }

    @Test
    void testStreamWithFallback_quotaBeforeFirstElement_noSwitch() {
        AtomicBoolean subscribedSecond = new AtomicBoolean(false);
        Flux<AiModelInvocationBO<String>> flux = failoverService.streamWithFallback(
                List.of(candidate(model(1L, 10L, 1)), candidate(model(2L, 10L, 2))),
                c -> {
                    if (c.getModel().getId() == 1L) {
                        return Flux.error(new RuntimeException(QUOTA_403_BODY));
                    }
                    subscribedSecond.set(true);
                    return Flux.just("should-not-appear");
                });

        ServiceException ex = assertThrows(ServiceException.class, () -> flux.collectList().block());
        assertEquals(MODEL_QUOTA_EXHAUSTED.getCode(), ex.getCode());
        assertFalse(subscribedSecond.get(), "额度耗尽时绝不能订阅下一个候选");
    }

    @Test
    void testStreamWithFallback_quotaAfterFirstElement_propagatesRawAndNoSwitch() {
        // 首包已发射 ⟹ 前端已经收到内容，撤不回来，异常原样传播。
        // 🔴 这一半粘滞早于 R-01，红线 §10.9 第 30 条明写「不要动」，本条把它钉住。
        // 注意它传播的是【厂商原文】而不是 MODEL_QUOTA_EXHAUSTED：emitted 分支先判，
        // 那里不做分型，也不记额度冷却 —— 刻意保持原样
        AtomicBoolean subscribedSecond = new AtomicBoolean(false);
        Flux<AiModelInvocationBO<String>> flux = failoverService.streamWithFallback(
                List.of(candidate(model(1L, 10L, 1)), candidate(model(2L, 10L, 2))),
                c -> {
                    if (c.getModel().getId() == 1L) {
                        return Flux.concat(Flux.just("first-chunk"),
                                Flux.error(new RuntimeException(QUOTA_403_BODY)));
                    }
                    subscribedSecond.set(true);
                    return Flux.just("should-not-appear");
                });

        RuntimeException ex = assertThrows(RuntimeException.class, () -> flux.collectList().block());
        assertTrue(ex.getMessage().contains("AllocationQuota.FreeTierOnly"));
        assertFalse(subscribedSecond.get());
    }

    @Test
    void testBuildCandidateChain_quotaExhaustedModelSkippedForNextTask() {
        // 「换下一个模型」只准发生在任务边界：额度耗尽的模型被长冷却过滤掉，
        // 于是【下一个任务】自然落到下一个候选，而不是每次调用都先白撞一次 403
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m2 = model(2L, 10L, 2);
        AiModelDO m3 = model(3L, 10L, 3);
        mockModels(m1, m2, m3);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(apiKey(10L, CommonStatusEnum.ENABLE)));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenReturn(mock(ChatModel.class));

        assertThrows(ServiceException.class, () -> failoverService.callWithFallback(
                List.of(candidate(m1)), c -> {
                    throw new RuntimeException(QUOTA_403_BODY);
                }));

        assertEquals(List.of(2L, 3L), chainIds());
    }

    @Test
    void testCallWithFallback_transientFailure_stillSwitches() {
        // 反向护栏：分型不能顺手把瞬时失败也变成「判失败不切换」。
        // 限流（Throttling.RateQuota）字面量里带 Quota，最容易被误收进额度类
        AiModelInvocationBO<String> result = failoverService.callWithFallback(
                List.of(candidate(model(1L, 10L, 1)), candidate(model(2L, 10L, 2))),
                c -> {
                    if (c.getModel().getId() == 1L) {
                        throw new RuntimeException("{\"code\":\"Throttling.RateQuota\"}");
                    }
                    return "ok-" + c.getModel().getId();
                });

        assertEquals("ok-2", result.getPayload());
    }

    // ==================== ⑤ 任务级粘滞（D4-28 / R-01） ====================

    @Test
    void testBuildCandidateChain_taskPinned_usesOnlyPinnedModel() {
        // 🔴 变异探针靶点：把「0. 任务级粘滞」那段删掉，本条必红（会得到 [1,2,3]）。
        // 语义不是「优先用钉住的那个」，是「只用它」—— 连默认链都不看
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m2 = model(2L, 10L, 2);
        mockModels(m1, m2);
        when(modelMapper.selectById(2L)).thenReturn(m2);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(apiKey(10L, CommonStatusEnum.ENABLE)));
        when(apiKeyService.getApiKey(10L)).thenReturn(apiKey(10L, CommonStatusEnum.ENABLE));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenReturn(mock(ChatModel.class));

        // 请求的是 1、实际成功的是 2（1 瞬时失败被切走）⟹ 钉子落在 (TASK_KEY, 请求=1) 这一档
        failoverService.pinTaskModel(TASK_KEY, 1L, 2L);

        assertEquals(List.of(2L), convertList(
                failoverService.buildCandidateChain(1L, TYPE_CHAT, TASK_KEY),
                c -> c.getModel().getId()));
        // 不传 taskKey 的调用完全不受影响：ai 模块自己的四条链路走的就是这个入口
        assertEquals(List.of(1L, 2L), chainIds());
    }

    @Test
    void testBuildCandidateChain_pinnedModelDisabled_returnsEmptyChainNotFallback() {
        // 🔴 空链是「这个任务没法继续了」的信号，不是「退回去挑一个别的」的信号。
        // 回退就是在任务中途换模型，正是 R-01 要禁掉的行为。
        // 刻意不 stub 默认链的读取（selectListByStatusAndType / getApiKeyList）：
        // 粘滞命中后压根不该走到那一步，走到就说明回退了 —— 严格 stub 会替我盯着这件事
        AiModelDO m2 = AiModelDO.builder().id(2L).keyId(10L).sort(2)
                .name("模型2").model("m-2").platform("TongYi")
                .type(TYPE_CHAT).status(CommonStatusEnum.DISABLE.getStatus()).build();
        when(modelMapper.selectById(2L)).thenReturn(m2);

        failoverService.pinTaskModel(TASK_KEY, null, 2L);

        assertTrue(failoverService.buildCandidateChain(null, TYPE_CHAT, TASK_KEY).isEmpty());
        assertEquals(2L, failoverService.getPinnedModelId(TASK_KEY, null));
    }

    @Test
    void testBuildCandidateChain_pinnedModelQuotaExhausted_returnsEmptyChain() {
        AiModelDO m1 = model(1L, 10L, 1);
        when(modelMapper.selectById(1L)).thenReturn(m1);
        when(apiKeyService.getApiKey(10L)).thenReturn(apiKey(10L, CommonStatusEnum.ENABLE));

        failoverService.pinTaskModel(TASK_KEY, null, 1L);
        assertThrows(ServiceException.class, () -> failoverService.callWithFallback(
                List.of(candidate(m1)), c -> {
                    throw new RuntimeException(QUOTA_403_BODY);
                }));

        assertTrue(failoverService.buildCandidateChain(null, TYPE_CHAT, TASK_KEY).isEmpty());
    }

    @Test
    void testPinTaskModel_firstWins_neverOverwritten() {
        // 「任务内只用一个模型」意味着第一个成功的就是唯一那个。
        // 允许改写的话，一次瞬时切换就能把钉子挪走，粘滞形同虚设
        failoverService.pinTaskModel(TASK_KEY, null, 1L);
        failoverService.pinTaskModel(TASK_KEY, null, 2L);

        assertEquals(1L, failoverService.getPinnedModelId(TASK_KEY, null));
    }

    @Test
    void testPinTaskModel_differentRequestedModel_pinsIndependently() {
        // 🔴 变异探针靶点：把 pinKey 里的 requestedModelId 去掉（退回只按 taskKey 钉），本条必红。
        // 场景是一次流程执行里两个 LLM 节点各配了不同模型 —— LlmNodeExecutor /
        // LlmConditionNodeExecutor 都读 node.configLong("modelId")，却共用 EXEC:<executionId>。
        // 只按任务钉的话，后一个节点会被前一个钉住的模型【静默顶掉】：
        // 用户明明配了 76，跑出来是 1，而且不报错
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m76 = model(76L, 20L, 2);
        mockModels(m1, m76);
        when(modelMapper.selectById(76L)).thenReturn(m76);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(
                apiKey(10L, CommonStatusEnum.ENABLE), apiKey(20L, CommonStatusEnum.ENABLE)));
        when(apiKeyService.getApiKey(20L)).thenReturn(apiKey(20L, CommonStatusEnum.ENABLE));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenReturn(mock(ChatModel.class));

        // 节点 A 没配模型：故障切换选中了 1，钉在「请求=null」这一档
        failoverService.pinTaskModel(TASK_KEY, null, 1L);

        // 节点 B 显式配了 76 ⟹ 必须走自己的候选链，拿到的是 76 而不是被顶成 1
        assertEquals(List.of(76L, 1L), convertList(
                failoverService.buildCandidateChain(76L, TYPE_CHAT, TASK_KEY),
                c -> c.getModel().getId()));
        // 节点 A 那一档的钉子没被 B 动过；B 成功后也各自记各自的
        failoverService.pinTaskModel(TASK_KEY, 76L, 76L);
        assertEquals(1L, failoverService.getPinnedModelId(TASK_KEY, null));
        assertEquals(76L, failoverService.getPinnedModelId(TASK_KEY, 76L));
        assertEquals(List.of(76L), convertList(
                failoverService.buildCandidateChain(76L, TYPE_CHAT, TASK_KEY),
                c -> c.getModel().getId()));
    }

    @Test
    void testPinTaskModel_blankKeyOrNullModel_isNoOp() {
        failoverService.pinTaskModel(null, null, 1L);
        failoverService.pinTaskModel("  ", null, 1L);
        failoverService.pinTaskModel(TASK_KEY, null, null);

        assertNull(failoverService.getPinnedModelId(TASK_KEY, null));
        assertNull(failoverService.getPinnedModelId(null, null));
        assertNull(failoverService.getPinnedModelId("unknown-task", null));
    }

    @Test
    void testRecordSuccess_clearsQuotaMemory() {
        // 额度回来了（管理员充值 / 关掉「仅用免费额度」）就不该继续被长冷却拦在候选链外。
        // 30 分钟是误判时的自愈上限，但成功必须立刻自愈，不能等
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m2 = model(2L, 10L, 2);
        mockModels(m1, m2);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(apiKey(10L, CommonStatusEnum.ENABLE)));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenReturn(mock(ChatModel.class));

        assertThrows(ServiceException.class, () -> failoverService.callWithFallback(
                List.of(candidate(m1)), c -> {
                    throw new RuntimeException(QUOTA_403_BODY);
                }));
        assertEquals(List.of(2L), chainIds());

        failoverService.callWithFallback(List.of(candidate(m1)), c -> "ok");
        assertEquals(List.of(1L, 2L), chainIds());
    }

    // ==================== ⑥ 配置错误不降级成「没有可用模型」（D4-29 第二段） ====================

    /**
     * 🔴 变异探针靶点：密钥全为空时，报的必须是「密钥没填」，不是「没有可用的聊天模型」
     *
     * <p>修之前的实测形态：{@code AiUtils.validateApiKey} 在 {@code src/main} 下零调用点，
     * 空密钥要等 SDK 建客户端时才炸（各平台反应还不一致），而那一炸被第 5 步的
     * {@code catch (Exception)} 吞掉、跳过候选 ⟹ 链为空 ⟹ 三个调用方各报各的
     * 「没有可用的聊天模型，请在「AI 模型」里启用一个」/「找不到默认模型」。
     * 模型<b>本来就启用着</b>，管理员于是去反复启停它（红线 §10.2 第 7 条：不替 error 编病因）。
     *
     * <p>把实现里那句 {@code if (candidates.isEmpty() && configFailure != null) throw} 删掉，
     * 本用例必须变红。
     */
    @Test
    void testBuildCandidateChain_allKeysBlank_throwsApiKeyBlankNotEmptyChain() {
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m2 = model(2L, 20L, 2);
        mockModels(m1, m2);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(
                apiKey(10L, CommonStatusEnum.ENABLE, ""), apiKey(20L, CommonStatusEnum.ENABLE, null)));
        // 刻意不 stub getOrCreateChatModel：空密钥该在建客户端<b>之前</b>就被判掉，
        // 走到工厂就说明判据没生效（严格 stub 会替我盯着「白 stub 了」这件事）

        ServiceException ex = assertThrows(ServiceException.class,
                () -> failoverService.buildCandidateChain(null, TYPE_CHAT));

        assertEquals(API_KEY_BLANK.getCode(), ex.getCode(),
                "成因要靠码认：调用方（含 agent 的 toReadableMessage）只认 ServiceException 的 message，"
                        + "码错了就会又变成一句没人能分流的话");
        assertEquals(API_KEY_BLANK.getMsg(), ex.getMessage(),
                "文案必须逐字来自 API_KEY_BLANK，别在抛点另拼一句可漂的副本");
    }

    @Test
    void testBuildCandidateChain_placeholderKey_treatedAsBlank() {
        // SDK 的示例值 sk-xxxx 留在配置里等于没填：判据必须与「空」同档，
        // 否则它会一路建出客户端、真发请求、吃 401，被当成「厂商抖动」静默切换
        AiModelDO m1 = model(1L, 10L, 1);
        mockModels(m1);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(
                apiKey(10L, CommonStatusEnum.ENABLE, AiUtils.API_KEY_PLACEHOLDER)));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> failoverService.buildCandidateChain(null, TYPE_CHAT));

        assertEquals(API_KEY_BLANK.getCode(), ex.getCode());
    }

    @Test
    void testBuildCandidateChain_oneKeyBlank_otherFine_failoverPreserved() {
        // 🔴 反方向的判据：单个模型配错<b>不该</b>拖垮整条链。
        // 把「链空才抛」写成「一遇到配置错就抛」，本用例变红 —— 那会把故障切换本身废掉
        AiModelDO m1 = model(1L, 10L, 1);
        AiModelDO m2 = model(2L, 20L, 2);
        mockModels(m1, m2);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(
                apiKey(10L, CommonStatusEnum.ENABLE, "  "), apiKey(20L, CommonStatusEnum.ENABLE, "sk-real-20")));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenReturn(mock(ChatModel.class));

        assertEquals(List.of(2L), chainIds());
    }

    @Test
    void testBuildCandidateChain_codedFactoryFailure_survivesInsteadOfNoCandidateModel() {
        // ${} 占位符解析不出来（API_CONFIG_PLACEHOLDER_NOT_RESOLVED）同属配置错误：
        // 它也带码、也「模型启用着」，被吞掉之后同样只能报「去启用一个模型」
        AiModelDO m1 = model(1L, 10L, 1);
        mockModels(m1);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(
                apiKey(10L, CommonStatusEnum.ENABLE, "${OPENAI_API_KEY}")));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenThrow(exception(API_CONFIG_PLACEHOLDER_NOT_RESOLVED, "${OPENAI_API_KEY}"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> failoverService.buildCandidateChain(null, TYPE_CHAT));

        assertEquals(API_CONFIG_PLACEHOLDER_NOT_RESOLVED.getCode(), ex.getCode());
    }

    @Test
    void testBuildCandidateChain_uncodedFactoryFailure_stillReturnsEmptyChain() {
        // 🔴 钉住「没改到的那一半」：不带码的异常（未知平台、SDK 内部 NPE 之类）
        // 仍旧返回空链、由调用方决定文案。本用例的存在是为了让「顺手把它也抛出去」变红 ——
        // 那会把 SDK 内部术语直接吐到界面上（红线 §10.2：一句不指认成因的话好过一句指认错成因的话，
        // 但一句内部术语两样都不是）
        AiModelDO m1 = model(1L, 10L, 1);
        mockModels(m1);
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(apiKey(10L, CommonStatusEnum.ENABLE, "sk-real-10")));
        when(modelFactory.getOrCreateChatModel(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("boom"));

        assertTrue(failoverService.buildCandidateChain(null, TYPE_CHAT).isEmpty());
    }

    /**
     * 钉住的模型密钥被清空：报「密钥没填」而不是「开新任务」
     *
     * <p>{@code MODEL_TASK_PINNED_UNAVAILABLE} 给的出路是「开一个新任务」，而密钥没填时
     * 开新任务会<b>一模一样地</b>再失败一次。真正的出路是去填密钥，填完钉子还在
     * （下面第二条断言就是在钉这件事），同一个任务能就地续上。
     */
    @Test
    void testBuildCandidateChain_pinnedModelKeyBlank_throwsApiKeyBlankAndKeepsPin() {
        AiModelDO m1 = model(1L, 10L, 1);
        when(modelMapper.selectById(1L)).thenReturn(m1);
        when(apiKeyService.getApiKey(10L)).thenReturn(apiKey(10L, CommonStatusEnum.ENABLE, ""));
        // 刻意不 stub 默认链的读取：粘滞命中后压根不该走到那一步（同 pinnedModelDisabled 那条）

        failoverService.pinTaskModel(TASK_KEY, null, 1L);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> failoverService.buildCandidateChain(null, TYPE_CHAT, TASK_KEY));
        assertEquals(API_KEY_BLANK.getCode(), ex.getCode());
        assertEquals(1L, failoverService.getPinnedModelId(TASK_KEY, null),
                "填完密钥后必须还是同一个模型 —— 掉钉就等于在任务中途换了模型，正是 R-01 要禁的");
    }

    // ==================== 工具方法 ====================

    private List<Long> chainIds() {
        return convertList(failoverService.buildCandidateChain(null, TYPE_CHAT),
                c -> c.getModel().getId());
    }

    private void mockModels(AiModelDO... models) {
        when(modelMapper.selectListByStatusAndType(CommonStatusEnum.ENABLE.getStatus(), TYPE_CHAT, null))
                .thenReturn(List.of(models));
    }

    private AiModelCandidate candidate(AiModelDO model) {
        return new AiModelCandidate(model, mock(ChatModel.class));
    }

    private AiModelDO model(Long id, Long keyId, Integer sort) {
        return AiModelDO.builder().id(id).keyId(keyId).sort(sort)
                .name("模型" + id).model("m-" + id).platform("TongYi")
                .type(TYPE_CHAT).status(CommonStatusEnum.ENABLE.getStatus())
                .build();
    }

    private AiApiKeyDO apiKey(Long id, CommonStatusEnum status) {
        return apiKey(id, status, "sk-" + id);
    }

    private AiApiKeyDO apiKey(Long id, CommonStatusEnum status, String keyValue) {
        return AiApiKeyDO.builder().id(id).status(status.getStatus())
                .platform("TongYi").apiKey(keyValue).url("http://localhost")
                .build();
    }

}
