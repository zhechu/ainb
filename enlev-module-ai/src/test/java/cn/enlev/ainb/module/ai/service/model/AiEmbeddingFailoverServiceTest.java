package cn.enlev.ainb.module.ai.service.model;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import cn.enlev.ainb.module.ai.dal.mysql.model.AiChatMapper;
import cn.enlev.ainb.module.ai.enums.model.AiModelTypeEnum;
import cn.enlev.ainb.module.ai.framework.ai.core.model.AiModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.EMBEDDING_MODEL_ALL_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link AiEmbeddingFailoverServiceImpl} 单测
 *
 * <p>覆盖五块语义，每一块都对应一个<b>实测到的</b>故障形态，不是假想：
 * <ol>
 *   <li><b>快路径零厂商请求</b>：选链在写入侧每篇文档都要走一遍（实测内置语料 125 篇），
 *       带网络往返的「选一个模型」是不可接受的；</li>
 *   <li><b>额度耗尽落到下一个</b>：本轮需求的正面判据。知识库钉住的模型 403 之后，
 *       下一篇文档必须落到另一个探得通的模型上，而不是每篇重撞一次；</li>
 *   <li><b>必须真探，不能只按启用状态挑</b>：实测 {@code type=5} 且启用的 9 个里
 *       有 6 个是被登记成向量类型的重排序 / 多模态模型，厂商一律回 400「url error」；</li>
 *   <li><b>全员不可用时逐个列出厂商原文</b>：只给最后一个的话，「额度耗尽」会被后面的
 *       「url error」盖掉，而前者才是真正要处置的那条（充值 / 关掉「仅用免费额度」）；</li>
 *   <li><b>正向记忆与失败记忆互相压制</b>：探通过一次的不再重探；真调用失败后立刻作废。</li>
 * </ol>
 *
 * <p>🔴 变异探针靶点：把快路径改成「也探一次」、把 {@code recordFailure} 里那句
 * {@code probedUsableAt.remove} 删掉、把汇总消息改成只带最后一个异常、
 * 或把「全员冷却则放弃过滤」那段删掉，都必须让本类变红。
 *
 * <p>失败<b>记忆</b>本身（三段冷却、配置指纹、指纹不匹配即作废）不在这里重测 ——
 * 它与对话侧共用 {@link AiModelFailureMemory}，那部分由 {@code AiModelFailoverServiceTest} 钉住。
 *
 * @author 走进新时代
 */
class AiEmbeddingFailoverServiceTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AiEmbeddingFailoverServiceImpl embeddingFailoverService;

    @Mock
    private AiChatMapper modelMapper;
    @Mock
    private AiApiKeyService apiKeyService;
    @Mock
    private AiModelFactory modelFactory;
    @Mock
    private AiModelFailoverService failoverService;

    /**
     * 与对话侧<b>共用同一份</b>记忆。用真对象而不是 mock：
     * 冷却时长、配置指纹这些判据是本类的行为前提，mock 掉就等于什么都没验
     */
    private AiModelFailureMemory failureMemory;

    private static final Integer TYPE_EMBEDDING = AiModelTypeEnum.EMBEDDING.getType();

    /**
     * 厂商 403 额度耗尽响应体
     *
     * <p>🔴 逐字取自 2026-10-25 现场后端日志（模型 65 {@code qwen3.7-text-embedding} 的测试按钮实测），
     * 与 {@code AiModelFailoverServiceTest} 里那份同源。用真实报文而不是随手编一句 "quota exceeded"，
     * 是为了让「分类判据」和「现场那条」之间始终只差一次字符串匹配
     */
    private static final String QUOTA_403_BODY = "403 - {\"code\":\"AllocationQuota.FreeTierOnly\","
            + "\"message\":\"Free quota exhausted. To continue accessing the model on a paid basis, "
            + "please add funds or disable the \\\"use free tier only\\\" mode in the management console.\"}";

    /**
     * 厂商「端点选错」的原文
     *
     * <p>🔴 逐字取自同一轮实测：{@code gte-rerank-v2}、{@code qwen3-vl-embedding} 等 6 个
     * 被登记成 {@code type=5} 的模型全部回这一句。它们不是「坏了」，是<b>登记错了类型</b> ——
     * 而这句话本身完全看不出这一点，正是「必须真探一次」的由来
     */
    private static final String URL_ERROR_BODY = "400 - {\"code\":\"InvalidParameter\","
            + "\"message\":\"url error, please check url！\"}";

    /** 实测可用的两个向量模型（本机 local，都是通义、都是 1024 维） */
    private static final long MODEL_QUOTA_DEAD = 65L;
    private static final long MODEL_WORKING = 102L;
    private static final long MODEL_MISREGISTERED = 103L;

    private final Map<String, EmbeddingModel> embeddingMocks = new HashMap<>();

    @BeforeEach
    void setUpFailureMemory() {
        failureMemory = new AiModelFailureMemory();
        when(failoverService.getFailureMemory()).thenReturn(failureMemory);
    }

    // ========== ① 快路径与「补探一次」 ==========

    @Test
    void testPinnedModelIsProbedOnceThenServedFromPositiveMemory() {
        AiModelDO pinned = embeddingModel(MODEL_WORKING, 1L, "text-embedding-v4");
        stubKeys(key(1L, CommonStatusEnum.ENABLE));
        stubSelectById(pinned);
        stubProbe("text-embedding-v4", null);

        AiModelDO first = embeddingFailoverService.selectUsableEmbeddingModel(MODEL_WORKING);
        AiModelDO second = embeddingFailoverService.selectUsableEmbeddingModel(MODEL_WORKING);

        assertEquals(MODEL_WORKING, first.getId());
        assertEquals(MODEL_WORKING, second.getId());
        // 🔴 第一次必须探：「失败记忆里没有它」只代表本进程还没试过它，不代表它打得通。
        //    省掉这一探，钉在坏模型上的知识库第一篇文档就会在 @Async 里静默失败（接口照样 200）
        // 🔴 第二次必须不探：本方法在写入侧每篇文档都要走一遍，125 篇文档就是 125 次厂商请求
        verify(embeddingMocks.get("text-embedding-v4"), times(1)).call(any(EmbeddingRequest.class));
        // 首选可用时压根不该去查候选列表
        verify(modelMapper, never()).selectListByStatusAndType(any(), any(), any());
    }

    @Test
    void testPinnedModelFailingTheMakeupProbeFallsToSlowPathWithoutReprobingIt() {
        // 这一条就是「刚重启的后端 + 钉在额度耗尽模型上的知识库」的现场：
        // 失败记忆是空的（进程级、不持久化），所以它不在冷却中，只能靠补探发现它是坏的
        AiModelDO quotaDead = embeddingModel(MODEL_QUOTA_DEAD, 1L, "qwen3.7-text-embedding");
        AiModelDO misregistered = embeddingModel(MODEL_MISREGISTERED, 1L, "gte-rerank-v2");
        AiModelDO working = embeddingModel(MODEL_WORKING, 1L, "text-embedding-v4");
        stubKeys(key(1L, CommonStatusEnum.ENABLE));
        stubSelectById(quotaDead);
        stubCandidates(quotaDead, misregistered, working);
        stubProbe("qwen3.7-text-embedding", new RuntimeException(QUOTA_403_BODY));
        stubProbe("gte-rerank-v2", new RuntimeException(URL_ERROR_BODY));
        stubProbe("text-embedding-v4", null);

        AiModelDO selected = embeddingFailoverService.selectUsableEmbeddingModel(MODEL_QUOTA_DEAD);

        assertEquals(MODEL_WORKING, selected.getId(),
                "补探发现首选额度耗尽之后，必须落到下一个探得通的模型 —— 而不是把坏模型返回给调用方去撞");
        // 🔴 只准探一次：补探失败已经把它记进冷却，慢路径必须跳过它。
        //    探两次等于对同一个已知没额度的模型白打一次厂商请求，还可能触发厂商侧限流
        verify(embeddingMocks.get("qwen3.7-text-embedding"), times(1)).call(any(EmbeddingRequest.class));
        assertTrue(failureMemory.isQuotaExhausted(MODEL_QUOTA_DEAD),
                "补探的 403 原文必须被分型成 QUOTA 并记进冷却，否则下一篇文档还要再撞一次");
    }

    // ========== ② 额度耗尽落到下一个（本轮需求的正面判据） ==========

    @Test
    void testPinnedQuotaExhaustedFallsToNextProbedModel() {
        AiModelDO quotaDead = embeddingModel(MODEL_QUOTA_DEAD, 1L, "qwen3.7-text-embedding");
        AiModelDO misregistered = embeddingModel(MODEL_MISREGISTERED, 1L, "gte-rerank-v2");
        AiModelDO working = embeddingModel(MODEL_WORKING, 1L, "text-embedding-v4");
        stubKeys(key(1L, CommonStatusEnum.ENABLE));
        stubSelectById(quotaDead);
        stubCandidates(quotaDead, misregistered, working);
        // 上一次的真实调用已经把额度耗尽记进了记忆（403 原文由 AiModelFailureReason 分型）
        embeddingFailoverService.recordFailure(quotaDead, new RuntimeException(QUOTA_403_BODY));
        stubProbe("gte-rerank-v2", new RuntimeException(URL_ERROR_BODY));
        stubProbe("text-embedding-v4", null);

        AiModelDO selected = embeddingFailoverService.selectUsableEmbeddingModel(MODEL_QUOTA_DEAD);

        assertEquals(MODEL_WORKING, selected.getId(),
                "钉住的模型额度耗尽后必须落到下一个【探得通】的，而不是下一个【启用着】的");
        // 登记错类型的那个必须被真探一次才知道它不行 —— 「启用中」完全看不出它是重排序模型
        verifyProbed("gte-rerank-v2");
        verifyProbed("text-embedding-v4");
        // 探坏的按分型记进长冷却，下次不再白撞
        assertTrue(failureMemory.isConfigBroken(MODEL_MISREGISTERED),
                "url error 是确定性失败，应判 CONFIG（30 分钟冷却），不是 60 秒的瞬时");
        assertTrue(failureMemory.isQuotaExhausted(MODEL_QUOTA_DEAD));
    }

    // ========== ③ 全员不可用：逐个列出厂商原文 ==========

    @Test
    void testAllUnusableListsEveryAttemptWithVendorText() {
        AiModelDO quotaDead = embeddingModel(MODEL_QUOTA_DEAD, 1L, "qwen3.7-text-embedding");
        AiModelDO misregistered = embeddingModel(MODEL_MISREGISTERED, 1L, "gte-rerank-v2");
        stubKeys(key(1L, CommonStatusEnum.ENABLE));
        stubCandidates(quotaDead, misregistered);
        stubProbe("qwen3.7-text-embedding", new RuntimeException(QUOTA_403_BODY));
        stubProbe("gte-rerank-v2", new RuntimeException(URL_ERROR_BODY));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> embeddingFailoverService.selectUsableEmbeddingModel(null));

        assertEquals(EMBEDDING_MODEL_ALL_UNAVAILABLE.getCode(), ex.getCode());
        // 🔴 这两条是本用例的全部意义：额度耗尽排在前面、url error 排在后面，
        //    只保留「最后一个异常」的写法会让真正要处置的那条（充值 / 关免费额度模式）被盖掉
        assertTrue(ex.getMessage().contains("Free quota exhausted"),
                "额度耗尽的厂商原文必须留在消息里，不能被后面的 url error 盖掉：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("url error"), "配置类失败的原文也要在：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("qwen3.7-text-embedding"), "要能看出是哪个模型：" + ex.getMessage());
    }

    @Test
    void testAllCoolingDownStillReprobesToSurfaceVendorText() {
        AiModelDO misregistered = embeddingModel(MODEL_MISREGISTERED, 1L, "gte-rerank-v2");
        stubKeys(key(1L, CommonStatusEnum.ENABLE));
        stubCandidates(misregistered);
        stubProbe("gte-rerank-v2", new RuntimeException(URL_ERROR_BODY));

        // 第一次：探坏，记进 30 分钟 CONFIG 冷却
        assertThrows(ServiceException.class, () -> embeddingFailoverService.selectUsableEmbeddingModel(null));
        // 第二次：唯一候选仍在冷却中
        ServiceException ex = assertThrows(ServiceException.class,
                () -> embeddingFailoverService.selectUsableEmbeddingModel(null));

        // 🔴 冷却是优化不是熔断：全员冷却时照旧真探，为的是让消息带上厂商原文。
        //    若这里改成「冷却中就直接抛」，消息里只剩一句「冷却中，本次未再探测」，
        //    管理员看不出是「额度没了」还是「模型类型登记错了」—— 而这两件事的修法完全相反
        assertTrue(ex.getMessage().contains("url error"),
                "全员冷却时仍须重探并把原文带出来：" + ex.getMessage());
        assertFalse(ex.getMessage().contains("冷却中"), "重探过就不该说「未再探测」：" + ex.getMessage());
        verify(modelFactory, times(2)).getOrCreateEmbeddingModel(any(), anyString(), anyString(),
                eq("gte-rerank-v2"));
    }

    // ========== ④ 正向记忆：探通过的不重探，真失败了立刻作废 ==========

    @Test
    void testRecentlyProbedModelIsNotProbedAgain() {
        AiModelDO working = embeddingModel(MODEL_WORKING, 1L, "text-embedding-v4");
        stubKeys(key(1L, CommonStatusEnum.ENABLE));
        stubCandidates(working);
        stubProbe("text-embedding-v4", null);

        assertEquals(MODEL_WORKING, embeddingFailoverService.selectUsableEmbeddingModel(null).getId());
        assertEquals(MODEL_WORKING, embeddingFailoverService.selectUsableEmbeddingModel(null).getId());

        // 🔴 一次灌库 125 篇文档 = 125 次选链。没有正向记忆的话这里会是 125 次厂商请求，
        //    而它们的结论完全相同
        verify(modelFactory, times(1)).getOrCreateEmbeddingModel(any(), anyString(), anyString(),
                eq("text-embedding-v4"));
    }

    @Test
    void testRecordFailureClearsPositiveMemory() {
        AiModelDO working = embeddingModel(MODEL_WORKING, 1L, "text-embedding-v4");
        AiModelDO backup = embeddingModel(106L, 1L, "qwen3.7-text-embedding-flash");
        stubKeys(key(1L, CommonStatusEnum.ENABLE));
        stubCandidates(working, backup);
        stubProbe("text-embedding-v4", null);
        stubProbe("qwen3.7-text-embedding-flash", null);

        assertEquals(MODEL_WORKING, embeddingFailoverService.selectUsableEmbeddingModel(null).getId());
        // 真实调用撞上额度耗尽：正向记忆必须立刻作废，否则「30 分钟前探通过」会一直盖住它
        embeddingFailoverService.recordFailure(working, new RuntimeException(QUOTA_403_BODY));

        assertEquals(106L, embeddingFailoverService.selectUsableEmbeddingModel(null).getId(),
                "额度耗尽之后必须落到下一个，而不是继续拿旧的正向记忆顶住");
        assertTrue(failureMemory.isQuotaExhausted(MODEL_WORKING));
    }

    // ========== ⑤ 选择期过滤 ==========

    @Test
    void testKeyDisabledModelIsNeverProbed() {
        AiModelDO working = embeddingModel(MODEL_WORKING, 1L, "text-embedding-v4");
        AiModelDO keyDisabled = embeddingModel(MODEL_MISREGISTERED, 2L, "gte-rerank-v2");
        stubKeys(key(1L, CommonStatusEnum.ENABLE), key(2L, CommonStatusEnum.DISABLE));
        stubCandidates(working, keyDisabled);
        stubProbe("text-embedding-v4", null);

        assertEquals(MODEL_WORKING, embeddingFailoverService.selectUsableEmbeddingModel(null).getId());
        // 模型开着但密钥禁用 ⇒ 运行时才在 validateApiKey 炸掉。提前剔除，别浪费一次探测
        verify(modelFactory, never()).getOrCreateEmbeddingModel(any(), anyString(), anyString(),
                eq("gte-rerank-v2"));
    }

    @Test
    void testPinnedModelOfWrongTypeIsNotReturned() {
        // 知识库的 embedding_model_id 指到了一个【对话】模型：历史上手工改库能做到这件事。
        // 原样返回它的话，写入侧会拿对话模型标识去建向量客户端，厂商回 400，
        // 而这 400 会被判成 CONFIG ⟹ 一个本来好好的对话模型被拉黑 30 分钟
        AiModelDO chatModel = AiModelDO.builder().id(MODEL_WORKING).keyId(1L).sort(1)
                .name("对话模型").model("qwen3.7-plus").platform("TongYi")
                .type(AiModelTypeEnum.CHAT.getType()).status(CommonStatusEnum.ENABLE.getStatus())
                .build();
        AiModelDO working = embeddingModel(106L, 1L, "qwen3.7-text-embedding-flash");
        stubKeys(key(1L, CommonStatusEnum.ENABLE));
        stubSelectById(chatModel);
        stubCandidates(working);
        stubProbe("qwen3.7-text-embedding-flash", null);

        assertEquals(106L, embeddingFailoverService.selectUsableEmbeddingModel(MODEL_WORKING).getId(),
                "钉住的模型类型不对时必须走探测，不能原样返回");
    }

    // ========== 桩与断言工具 ==========

    private void stubKeys(AiApiKeyDO... keys) {
        when(apiKeyService.getApiKeyList()).thenReturn(List.of(keys));
    }

    private void stubSelectById(AiModelDO model) {
        when(modelMapper.selectById(model.getId())).thenReturn(model);
    }

    /** 候选列表：真实 Mapper 按 sort 升序返回，这里按调用方给的顺序原样回 */
    private void stubCandidates(AiModelDO... models) {
        lenient().when(modelMapper.selectListByStatusAndType(
                CommonStatusEnum.ENABLE.getStatus(), TYPE_EMBEDDING, null)).thenReturn(List.of(models));
    }

    /**
     * 桩一次探测：{@code error == null} 即探通
     *
     * <p>刻意 {@code lenient}：哪些模型<b>没被</b>探到正是好几个用例要断言的东西，
     * 用 STRICT_STUBS 的话「多余桩」报错会盖掉真正要看的 {@code verify(never())}
     */
    private void stubProbe(String modelIdentifier, Throwable error) {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        embeddingMocks.put(modelIdentifier, embeddingModel);
        lenient().when(modelFactory.getOrCreateEmbeddingModel(any(), anyString(), anyString(),
                eq(modelIdentifier))).thenReturn(embeddingModel);
        if (error == null) {
            lenient().when(embeddingModel.call(any(EmbeddingRequest.class)))
                    .thenReturn(new EmbeddingResponse(List.of()));
        } else {
            lenient().when(embeddingModel.call(any(EmbeddingRequest.class))).thenThrow(error);
        }
    }

    private void verifyProbed(String modelIdentifier) {
        verify(embeddingMocks.get(modelIdentifier)).call(any(EmbeddingRequest.class));
    }

    private AiModelDO embeddingModel(Long id, Long keyId, String identifier) {
        return AiModelDO.builder().id(id).keyId(keyId).sort(Math.toIntExact(id))
                .name("模型" + id).model(identifier).platform("TongYi")
                .type(TYPE_EMBEDDING).status(CommonStatusEnum.ENABLE.getStatus())
                .build();
    }

    private AiApiKeyDO key(Long id, CommonStatusEnum status) {
        return AiApiKeyDO.builder().id(id).status(status.getStatus())
                .platform("TongYi").apiKey("sk-" + id).url("http://localhost")
                .build();
    }

}
