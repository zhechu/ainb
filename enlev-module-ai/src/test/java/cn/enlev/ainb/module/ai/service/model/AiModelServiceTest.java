package cn.enlev.ainb.module.ai.service.model;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelTestRespVO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import cn.enlev.ainb.module.ai.dal.mysql.model.AiChatMapper;
import cn.enlev.ainb.module.ai.framework.ai.core.model.AiModelFactory;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeApiSpec;
import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.alibaba.cloud.ai.model.RerankResponseMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.util.List;

import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.API_KEY_BLANK;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.API_KEY_NOT_EXISTS;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AiModelServiceImpl#testModel} 单测
 *
 * <p>覆盖七块语义：
 * <ol>
 *   <li><b>结论结构化</b>：调通回 success + 回复片段 + token 用量；失败回 success=false + 厂商原文</li>
 *   <li><b>原文逐字、不编病因</b>：厂商说什么就带回什么，分型只复用现成的
 *       {@link AiModelFailureReason}。唯一的例外是那句 {@code url error} —— 它字面上在怪管理员
 *       填错 URL，而通义向量的端点压根不由管理员填，所以原文<b>逐字保留在前</b>、
 *       可照着做的解释追加在后（见 ⑦ 的两条对偶用例：url error 才追加、额度耗尽不追加）</li>
 *   <li><b>0 侵入生产链</b>：只调这一个模型一次，绝不切换候选、绝不触碰故障切换服务</li>
 *   <li><b>禁用也能测</b>：模型与密钥都是禁用态时照样发请求，状态当事实回显而不是当门槛</li>
 *   <li><b>参数与生产同口径</b>：enableThinking 不表态、maxTokens 压到 256、端点路由事实回显</li>
 *   <li><b>按类型分流</b>：向量打向量端点、重排打重排端点、图片/语音/视频明确说「不支持」，
 *       绝不拿它们的模型名去撞对话端点再把撞出来的 400 当结论</li>
 *   <li><b>厂商没说的数字不编</b>：{@code usage} 缺席时 token 格留 null，
 *       不出向量/不生成 token 的类型不填 0</li>
 * </ol>
 *
 * <p>🔴 全程<b>零厂商调用</b>：{@link ChatModel} / {@link EmbeddingModel} / {@link RerankModel}
 * 都是 mock，口径与 {@code AiModelApiImplTest} 一致。
 * 本类测的是「测试路径自己怎么组装与汇报」，不是厂商行为 —— 后者只能靠真机验收
 * （⑦ 那几条的模型名、分数形态与 {@code usage} 缺席形态全部取自 2026-10-26 直连厂商的实测）。
 *
 * @author 走进新时代
 */
class AiModelServiceTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AiModelServiceImpl modelService;

    @Mock
    private AiChatMapper modelMapper;
    @Mock
    private AiApiKeyService apiKeyService;
    @Mock
    private AiModelFactory modelFactory;
    /**
     * 🔴 变异探针
     *
     * <p>{@code AiModelServiceImpl} 现在<b>没有</b>这个依赖，{@code @InjectMocks} 会忽略它，
     * 于是下面每条 {@code verifyNoInteractions} 恒真 —— 这不是白写：它的靶子是「将来」。
     * 一旦有人把测试路径接到故障切换链上（{@code callWithFallback} 失败会 {@code recordFailure}
     * 给<b>生产链</b>上 60 秒冷却，一次测试就能把真模型拉黑），这个字段立刻被注入，
     * 每条探针同时变红。删掉它等于拆掉「0 侵入」这条设计取舍的唯一自动化判据。
     */
    @Mock
    private AiModelFailoverService failoverService;

    private static final Long MODEL_ID = 89L;
    private static final Long KEY_ID = 6L;
    private static final String PLATFORM_TONG_YI = "TongYi";
    private static final String API_KEY = "sk-test-0123456789";
    private static final Integer TYPE_CHAT = 1;
    /** 图片：本端点<b>不打</b>的类型，用来钉「明确说不支持」而不是拿它去撞对话端点 */
    private static final Integer TYPE_IMAGE = 2;
    private static final Integer TYPE_EMBEDDING = 5;
    private static final Integer TYPE_RERANK = 6;

    /**
     * 厂商「端点选错」的原文
     *
     * <p>特征串 {@code url error, please check url！} 逐字取自 {@code AiUtils#TONG_YI_MULTI_MODELS}
     * 的 javadoc（那里记的是 2026-09-29 直连两个端点的实测结果：{@code qwen3.7-max} 仅
     * text-generation 通，打到 multimodal 就报这一句）。外层 {@code 400 - {...}} 的包装形态
     * 照 {@code AiModelFailureReason#QUOTA_MARKERS} javadoc 里那条现场 403 原文的样式。
     */
    private static final String URL_ERROR_BODY = "400 - {\"code\":\"InvalidParameter\","
            + "\"message\":\"url error, please check url！\"}";

    /**
     * 厂商「模型名不存在」的原文
     *
     * <p>{@code Model not exist} 是<b>确定性</b>配置错误：同一份配置重试必然同样失败。
     * 它原先落在 fail-open 的 TRANSIENT 档 ⟹ 60 秒冷却 ⟹ 风暴原样重演，
     * 现在由 {@link AiModelFailureReason#CONFIG} 接住（切换 + 30 分钟冷却 + 配置指纹可解锁）。
     * 本端点不替它编病因，只把原文带回来。
     */
    private static final String MODEL_NOT_EXIST_BODY = "404 - {\"code\":\"ModelNotFound\","
            + "\"message\":\"Model not exist\"}";

    /**
     * 厂商 403 额度耗尽响应体
     *
     * <p>🔴 与 {@code AiModelFailoverServiceTest#QUOTA_403_BODY} 逐字一致（那份取自现场后端日志）。
     * 两处用同一份夹具是刻意的：本端点的分型直接复用 {@link AiModelFailureReason#classify}，
     * 判据与真实报文之间必须始终只差一次字符串匹配。
     */
    private static final String QUOTA_403_BODY = "403 - {\"code\":\"AllocationQuota.FreeTierOnly\","
            + "\"message\":\"Free quota exhausted. To continue accessing the model on a paid basis, "
            + "please add funds or disable the \\\"use free tier only\\\" mode in the management console.\"}";

    // ==================== ① 调通 ====================

    @Test
    void testTestModel_success() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "qwen3.7-max"),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        ChatModel chatModel = mockClient();
        when(chatModel.call(any(Prompt.class))).thenReturn(response("pong", 12, 3));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertTrue(resp.getSuccess());
        assertFalse(resp.getTimedOut());
        assertEquals("pong", resp.getReply());
        assertEquals(Integer.valueOf(12), resp.getPromptTokens());
        assertEquals(Integer.valueOf(3), resp.getCompletionTokens());
        assertNull(resp.getErrorMsg());
        assertNull(resp.getFailureReason());
        assertNotNull(resp.getDurationMs());
        // qwen3.7-max 是纯文本主干：这一格正是排查 url error 的入口，必须是 false 而不是 null
        assertEquals(Boolean.FALSE, resp.getMultiModel());
        // 🔴 A3：qwen3.7-max 命中「被厂商限死必须开思考」（实测原文
        //    400 - enable_thinking parameter is restricted to True，模型 79）。
        //    这一格回显的是 prompt 里那份<b>真正要发出去</b>的 options，不是在 Service 里重算一遍，
        //    所以它是「改了 enlev.ai.tongyi.thinking-only-models 到底生效没有」的唯一自动化判据
        assertEquals(Boolean.TRUE, resp.getEnableThinking());
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), resp.getModelStatus());
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), resp.getKeyStatus());
        verifyNoInteractions(failoverService);
    }

    @Test
    void testTestModel_successButEmptyReply_saysSoExplicitly() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "qwen3.7-max"),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        ChatModel chatModel = mockClient();
        when(chatModel.call(any(Prompt.class))).thenReturn(response("", 12, 0));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        // HTTP 层面厂商接受了这个平台/模型/端点组合，所以判调通；
        // 但「一个字都没回」必须显式说出来，不能让它看着像一次正常的成功
        assertTrue(resp.getSuccess());
        assertEquals("（厂商返回了空回复）", resp.getReply());
    }

    // ==================== ② 失败：原文逐字 + 不编病因 ====================

    @Test
    void testTestModel_vendorModelNotExist_reportsVerbatim() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "qwen3-vl-flash-2025-10-15"),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        ChatModel chatModel = mockClient();
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException(MODEL_NOT_EXIST_BODY));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertFalse(resp.getSuccess());
        assertFalse(resp.getTimedOut());
        // 🔴 逐字，一个字都不改：红线 §10.2 第 7 条禁止替 error 编病因
        assertEquals(MODEL_NOT_EXIST_BODY, resp.getErrorMsg());
        // 「模型名不存在」是确定性配置错误 → CONFIG（切换 + 30 分钟冷却）。
        // 这条断言钉的是「分型直接复用 classify」：本端点自己不判、不加档、不编病因
        assertEquals(AiModelFailureReason.CONFIG.name(), resp.getFailureReason());
        // 🔴 只调一次：绝不切换到别的候选，否则「测试通过」就成了别的模型替它通过的
        verify(chatModel, times(1)).call(any(Prompt.class));
        verifyNoInteractions(failoverService);
    }

    @Test
    void testTestModel_vendorUrlError_reportsVerbatim() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "qwen3.8-max"),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        ChatModel chatModel = mockClient();
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException(URL_ERROR_BODY));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertFalse(resp.getSuccess());
        assertEquals(URL_ERROR_BODY, resp.getErrorMsg());
        // 🔴 这一格就是 2026-10-10 那场 23 秒风暴的链首病因：原先判 TRANSIENT ⟹ 60 秒冷却 ⟹ 原样重演
        assertEquals(AiModelFailureReason.CONFIG.name(), resp.getFailureReason());
        // qwen3.8-max 不在 TONG_YI_MULTI_MODELS 里，快照后缀也剥不出一个在里面的基础版 → 文本端点
        assertEquals(Boolean.FALSE, resp.getMultiModel());
        // 也不在「限死必须开思考」里 → 仍旧关。这两条一起钉住「A3 没有把所有模型都翻成 true」
        assertEquals(Boolean.FALSE, resp.getEnableThinking());
        verifyNoInteractions(failoverService);
    }

    @Test
    void testTestModel_vendorQuotaExhausted_classifiedAsQuota() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "qwen3.7-max"),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        ChatModel chatModel = mockClient();
        // 包两层：厂商 SDK 常把响应体裹在两三层异常里，外层往往只有一句「调用失败」。
        // 这一条同时钉住 describeError 取<b>最深</b>那条 message，而不是最外层那句废话
        when(chatModel.call(any(Prompt.class))).thenThrow(
                new RuntimeException("调用失败", new RuntimeException(QUOTA_403_BODY)));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertFalse(resp.getSuccess());
        assertEquals(QUOTA_403_BODY, resp.getErrorMsg());
        assertEquals(AiModelFailureReason.QUOTA.name(), resp.getFailureReason());
        verifyNoInteractions(failoverService);
    }

    @Test
    void testTestModel_errorMsgTruncatedAndSaysSo() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "qwen3.7-max"),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        ChatModel chatModel = mockClient();
        String longBody = "400 - " + "x".repeat(900);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException(longBody));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertTrue(resp.getErrorMsg().startsWith("400 - xxxx"));
        assertTrue(resp.getErrorMsg().contains("已截断，原文共 " + longBody.length() + " 字"),
                "静默截断会让管理员以为看到了全部原文");
        assertTrue(resp.getErrorMsg().length() < longBody.length());
    }

    // ==================== ③ 硬超时 ====================

    @Test
    void testTestModel_timeout() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "qwen3.7-max"),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        ChatModel chatModel = mockClient();
        // 🔴 本条会真实等待 5 秒：服务端把超时夹到 [5, 120]，传 1 也按 5 算（下面顺带钉住这个夹取）。
        //    慢，但这是唯一能证明「硬超时真的生效」的办法 —— buildTongYiChatModel 不设任何超时，
        //    少了 Future.get(timeout) 这一层，厂商挂起就会永久占住一个 Tomcat 线程
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(60_000);
            return null;
        });

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 1);

        assertFalse(resp.getSuccess());
        // timedOut 与「厂商报错」是两件事，前端据此分文案
        assertTrue(resp.getTimedOut());
        assertTrue(resp.getErrorMsg().contains("超时（5 秒）"), resp.getErrorMsg());
        // 超时时根本没有厂商原文可分型。给一个 TRANSIENT 就是编病因
        assertNull(resp.getFailureReason());
        verifyNoInteractions(failoverService);
    }

    // ==================== ④ 禁用也能测 ====================

    @Test
    void testTestModel_disabledModelAndKey_stillTestable() {
        mockModelAndKey(model(CommonStatusEnum.DISABLE, "qwen3.7-max"),
                apiKey(CommonStatusEnum.DISABLE, API_KEY));
        ChatModel chatModel = mockClient();
        when(chatModel.call(any(Prompt.class))).thenReturn(response("pong", 12, 3));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        // 🔴 这条是「允许测已禁用模型」的判据：走 validateModel 会抛 MODEL_DISABLE、
        //    走 apiKeyService.validateApiKey 会抛 API_KEY_DISABLE，
        //    于是管理员永远问不出「我禁掉的这个到底还能不能用」
        assertTrue(resp.getSuccess());
        verify(chatModel, times(1)).call(any(Prompt.class));
        // 状态不是门槛，是事实：测禁用模型时这两格尤其重要
        assertEquals(CommonStatusEnum.DISABLE.getStatus(), resp.getModelStatus());
        assertEquals(CommonStatusEnum.DISABLE.getStatus(), resp.getKeyStatus());
    }

    // ==================== ⑤ 参数与生产同口径 ====================

    @Test
    void testTestModel_promptShapeMatchesProduction() {
        // 模型自身配的是 8192：探活必须压到 256，一次点按不该产生一次长对话的账单
        AiModelDO model = model(CommonStatusEnum.ENABLE, "qwen3.7-max");
        model.setMaxTokens(8192);
        mockModelAndKey(model, apiKey(CommonStatusEnum.ENABLE, API_KEY));
        ChatModel chatModel = mockClient();
        when(chatModel.call(any(Prompt.class))).thenReturn(response("pong", 12, 3));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        Prompt prompt = capturePrompt(chatModel);
        assertEquals(Integer.valueOf(256), prompt.getOptions().getMaxTokens());
        // 温度沿用模型自身配置，与 AiModelApiImpl#buildPrompt 在 reqDTO 未表态时一致
        assertEquals(model.getTemperature(), prompt.getOptions().getTemperature());

        List<Message> messages = prompt.getInstructions();
        assertEquals(1, messages.size());
        assertEquals(MessageType.USER, messages.get(0).getMessageType());
        assertEquals("ping", messages.get(0).getText());

        DashScopeChatOptions options = (DashScopeChatOptions) prompt.getOptions();
        // 🔴 enableThinking 刻意「不表态」：由 resolveTongYiThinking 三档决定。
        //    qwen3.7-max 是文本主干，旧的两档判定会给它 false，而厂商逐字回
        //    400 - enable_thinking parameter is restricted to True（模型 79，2026-10-10 实测）——
        //    这一条断言<b>原本写的就是 FALSE</b>，它钉住的正是 A3 那个缺陷。
        //    现在它命中第二档（TONG_YI_THINKING_ONLY_MODELS）⇒ true，端点仍是文本端点不变。
        //    显式传 false 会掩盖这类模型的真实形态，所以本端点始终不表态。
        assertEquals(Boolean.TRUE, options.getEnableThinking());
        assertEquals(Boolean.FALSE, options.getMultiModel());
        // 回显给管理员的那一格必须与真发出去的那一格逐位相同 —— 它读的就是这份 options，
        // 不是重算一遍（重算等于把三档逻辑抄第二份，加一档就只改得掉一处）
        assertEquals(options.getEnableThinking(), resp.getEnableThinking());
        assertEquals(options.getMultiModel(), resp.getMultiModel());
    }

    @Test
    void testTestModel_multiModelSnapshot_routedAsBaseModel() {
        // 快照版按基础版归类：qwen3-vl-flash-2025-10-15 → qwen3-vl-flash ∈ TONG_YI_MULTI_MODELS。
        // 漏掉这一层就会把多模态模型打到文本端点，报一句看不出根因的 url error
        AiModelDO model = model(CommonStatusEnum.DISABLE, "qwen3-vl-flash-2025-10-15");
        mockModelAndKey(model, apiKey(CommonStatusEnum.ENABLE, API_KEY));
        ChatModel chatModel = mockClient();
        when(chatModel.call(any(Prompt.class))).thenReturn(response("pong", 12, 3));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertEquals(Boolean.TRUE, resp.getMultiModel());
        assertEquals("qwen3-vl-flash-2025-10-15", resp.getModel());
        DashScopeChatOptions options = (DashScopeChatOptions) capturePrompt(chatModel).getOptions();
        assertEquals(Boolean.TRUE, options.getMultiModel());
        assertEquals(Boolean.TRUE, options.getEnableThinking());
    }

    @Test
    void testTestModel_nonTongYiPlatform_multiModelIsNull() {
        AiModelDO model = model(CommonStatusEnum.ENABLE, "deepseek-chat");
        model.setPlatform("DeepSeek");
        AiApiKeyDO apiKey = apiKey(CommonStatusEnum.ENABLE, API_KEY);
        apiKey.setPlatform("DeepSeek");
        mockModelAndKey(model, apiKey);
        ChatModel chatModel = mockClient();
        when(chatModel.call(any(Prompt.class))).thenReturn(response("pong", 12, 3));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertTrue(resp.getSuccess());
        // 端点分档是通义专属的事实；别的平台没有这一档，留 null 而不是 false —— 
        // false 会被读成「已确认走文本端点」，那是编造
        assertNull(resp.getMultiModel());
        // 思考模式同理：DeepSeekChatOptions 上根本没有这个字段，回显 null 而不是 false
        assertNull(resp.getEnableThinking());
        assertEquals("DeepSeek", resp.getPlatform());
    }

    // ==================== ⑥ 本地拦截：不发请求 ====================

    @Test
    void testTestModel_modelNotExists_throws() {
        when(modelMapper.selectById(MODEL_ID)).thenReturn(null);

        // 唯一一处抛异常：模型压根不存在时没有「明细」可报
        ServiceException ex = assertThrows(ServiceException.class,
                () -> modelService.testModel(MODEL_ID, 30));
        assertEquals(MODEL_NOT_EXISTS.getCode(), ex.getCode());
        verifyNoInteractions(modelFactory);
    }

    @Test
    void testTestModel_apiKeyNotExists_returnsDetail() {
        when(modelMapper.selectById(MODEL_ID)).thenReturn(model(CommonStatusEnum.ENABLE, "qwen3.7-max"));
        when(apiKeyService.getApiKey(KEY_ID)).thenReturn(null);

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        // 不抛：本端点的价值在明细里，抛出去会被压成一句 message
        assertFalse(resp.getSuccess());
        assertEquals(API_KEY_NOT_EXISTS.getMsg(), resp.getErrorMsg());
        verifyNoInteractions(modelFactory);
        verifyNoInteractions(failoverService);
    }

    @Test
    void testTestModel_blankApiKey_interceptedBeforeClientBuild() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "qwen3.7-max"),
                apiKey(CommonStatusEnum.ENABLE, ""));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertFalse(resp.getSuccess());
        assertEquals(API_KEY_BLANK.getMsg(), resp.getErrorMsg());
        // 🔴 必须在建客户端之前就拦掉：各平台对空密钥的反应并不一致
        //    （有的 Assert 抛、有的照建不误、要到真发请求才吃 401）。照建不误的那种最坏 ——
        //    那个 401 会被 AiModelFailureReason 判成「厂商抖动」，把「没填密钥」说成「厂商挂了」
        verifyNoInteractions(modelFactory);
        verifyNoInteractions(failoverService);
    }

    // ==================== ⑦ 按类型分流：向量 / 重排 / 不支持 ====================

    /**
     * 🔴 向量模型必须打<b>向量</b>端点，一个 chat 客户端都不许建
     *
     * <p>这是本轮改动的起点：改之前 {@code testModel} 无条件建 {@code ChatModel}，
     * 于是 {@code text-embedding-v4} 被拿它的 embedding 模型名去撞平台的【对话】端点，
     * 厂商回 400，前端显示「模型不可用」—— 把「测试方式不对」误报成「模型坏了」，
     * 管理员照着错的方向反复启停一个完全正常的模型。
     * 少了 {@code verifyNoInteractions} 那半句，「分流又退化回 chat」不会让任何断言变红。
     */
    @Test
    void testTestModel_embeddingType_probesEmbeddingEndpointNotChat() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "text-embedding-v4", TYPE_EMBEDDING),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        EmbeddingModel embeddingModel = mockEmbeddingClient();
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(embeddingResponse(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, 8));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertTrue(resp.getSuccess(), resp.getErrorMsg());
        assertEquals(Integer.valueOf(4), resp.getEmbeddingDimensions());
        assertEquals("向量生成成功，维度 4", resp.getReply());
        assertEquals(Integer.valueOf(8), resp.getPromptTokens());
        // 维度这一格不是装饰：向量文件按模型标识命名，换模型前先看这一格，
        // 两个模型维度不一致就是「必须重嵌、不能直接改 embedding_model_id」的硬证据
        assertNull(resp.getCompletionTokens(), "向量模型不生成 token，填 0 会被读成「厂商说输出是 0」");
        // 🔴 multiModel 只在对话分支里设：向量模型压根没有「多模态端点」这一档，
        //    给它回显一个 false 等于替厂商编一个它没说过的结论
        assertNull(resp.getMultiModel());
        verifyNoInteractions(failoverService);
    }

    /**
     * 🔴 厂商没报用量就留空，别把 {@code EmptyUsage} 那个 0 当成事实回显
     *
     * <p>spring-ai 的 {@code EmbeddingResponseMetadata#getUsage} 在字段为 null 时
     * 每次 {@code new} 一个 {@link EmptyUsage} 顶上（javap 核过字节码），
     * 所以「非 null」<b>不等于</b>「厂商报了」，直接取 {@code getPromptTokens()}
     * 会在弹窗上写「输入 Token 0」—— 一个厂商从没说过的数字。
     * 与本类「不编病因」是同一条纪律：宁可留空。
     *
     * <p>🔴 本用例的模型名换过口径。早先这里写的是「实测 {@code qwen2.5-vl-embedding}
     * 压根不回 {@code usage}」，2026-10-26 直连复测发现<b>那句是错的</b>：它回的是
     * {@code {"image_tokens":0,"input_tokens":31}} —— 有 usage，只是缺 {@code total_tokens}。
     * 「整个 usage 都不回」这一档至今没在通义上实测到，但它是 SDK 允许的形态
     * （{@code toUsage} 返回 null ⟹ 元数据顶上 {@code EmptyUsage}），所以照旧钉住。
     */
    @Test
    void testTestModel_embeddingUsageAbsent_promptTokensIsNullNotZero() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "qwen2.5-vl-embedding", TYPE_EMBEDDING),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        EmbeddingModel embeddingModel = mockEmbeddingClient();
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                // 显式转型：embeddingResponse 有 Integer 与 Usage 两个重载，裸 null 两边都匹配
                .thenReturn(embeddingResponse(new float[]{1f, 2f}, (Integer) null));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertTrue(resp.getSuccess(), resp.getErrorMsg());
        assertNull(resp.getPromptTokens(), "厂商没报用量，这一格必须是 null 而不是 0");
    }

    /**
     * 🔴 厂商只报了 {@code total_tokens}、没报 {@code input_tokens} 时，这一格要退到 total
     *
     * <p>2026-10-26 直连厂商逐字实测，三个端点的 {@code usage} 字段名互不统一：
     * {@code gte-rerank-v2} 回 {@code {"total_tokens":79}}、{@code text-embedding-v4} 回
     * {@code {"total_tokens":12}}、{@code qwen3.7-text-embedding-flash} 回的甚至叫
     * {@code prompt_tokens}（{@code TokenUsage} 里没有对应字段）。
     * 而 {@code DashScopeAiUsage#getPromptTokens} 只读 {@code input_tokens}（javap 核实），
     * 于是这 3 个模型明明报了用量，弹窗上 token 却空着一格。
     * 向量与重排序都不生成输出 token，实测每一档 total 都等于 input，所以退到 total 是安全的。
     */
    @Test
    void testTestModel_usageReportsOnlyTotal_promptTokensFallsBackToTotal() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "gte-rerank-v2", TYPE_RERANK),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        RerankModel rerankModel = mockRerankClient();
        // 🔴 usage 必须在 when(...) 之前造好：totalOnlyUsage 自己也要打桩，
        //    嵌在 thenReturn(...) 的参数位置上就是「打桩未完成时又开一个打桩」，Mockito 直接报 UnfinishedStubbing
        Usage usage = totalOnlyUsage(79);
        when(rerankModel.call(any(RerankRequest.class))).thenReturn(rerankResponse(usage));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertTrue(resp.getSuccess(), resp.getErrorMsg());
        assertEquals(79, resp.getPromptTokens(), "厂商报了 total_tokens=79，不能因为字段名不叫 input_tokens 就丢掉");
    }

    /** 同一档在向量分支上也成立：{@code text-embedding-v4} 实测回 {@code {"total_tokens":12}} */
    @Test
    void testTestModel_embeddingUsageReportsOnlyTotal_promptTokensFallsBackToTotal() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "text-embedding-v4", TYPE_EMBEDDING),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        EmbeddingModel embeddingModel = mockEmbeddingClient();
        Usage usage = totalOnlyUsage(12);
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(embeddingResponse(new float[]{1f, 2f}, usage));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertTrue(resp.getSuccess(), resp.getErrorMsg());
        assertEquals(12, resp.getPromptTokens(), "厂商报了 total_tokens=12，不能因为字段名不叫 input_tokens 就丢掉");
    }

    /**
     * 🔴 复刻 SDK 文本向量那条路的<b>真实</b>对象形状：数字只能从 {@code nativeUsage} 里捞
     *
     * <p>三层叠加，全部 javap 核实：
     * {@code DashScopeApiSpec.EmbeddingUsage} 把 {@code total_tokens} 解析进了 record 字段，
     * 但它的 {@code getPromptTokens()} / {@code getCompletionTokens()} 字节码就是
     * {@code aconst_null}；{@code DashScopeEmbeddingModel#getDefaultUsage} 又去调
     * {@code Usage} 接口的 default {@code getTotalTokens()}（= 两个 null 各按 0 相加 = 0）
     * 而不是 record 自己的 {@code totalTokens()} 访问器 —— <b>SDK 自己把厂商报的 12 算成了 0</b>；
     * 最后 {@code DefaultUsage} 的构造再把 null 抹成 0。
     * 唯一还活着的地方是它第 4 个构造参数留下的 {@code nativeUsage}。
     *
     * <p>所以本用例刻意不用 mock：线上走进来的就是这个 {@code DefaultUsage(0,0,0,EmbeddingUsage(12))}。
     */
    @Test
    void testTestModel_embeddingUsageLostBySdk_recoveredFromNativeUsage() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "text-embedding-v4", TYPE_EMBEDDING),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        EmbeddingModel embeddingModel = mockEmbeddingClient();
        Usage usage = new DefaultUsage(null, null, null,
                new DashScopeApiSpec.EmbeddingUsage(12L));
        assertEquals(0, usage.getPromptTokens(), "前置事实：SDK 那条路到这里 promptTokens 已被抹成 0");
        assertEquals(0, usage.getTotalTokens(), "前置事实：totalTokens 也被算成了 0，接口上取不到 12");
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(embeddingResponse(new float[]{1f, 2f}, usage));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertTrue(resp.getSuccess(), resp.getErrorMsg());
        assertEquals(12, resp.getPromptTokens(),
                "厂商报了 total_tokens=12，SDK 弄丢了也得从 nativeUsage 里捞回来，不能回显 0 或空");
    }

    /**
     * {@code total} 有值、{@code input} 缺 —— 不依赖 nativeUsage 的那一档退路
     *
     * <p>{@code DefaultUsage(null, null, 12)} 会让 {@code getPromptTokens()} 变 0、
     * {@code getTotalTokens()} 保持 12，所以这条走的是「退到 total」而不是「捞 nativeUsage」。
     */
    @Test
    void testTestModel_embeddingUsageCoercedToZero_fallsBackToTotal() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "text-embedding-v4", TYPE_EMBEDDING),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        EmbeddingModel embeddingModel = mockEmbeddingClient();
        Usage usage = new DefaultUsage(null, null, 12);
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(embeddingResponse(new float[]{1f, 2f}, usage));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertTrue(resp.getSuccess(), resp.getErrorMsg());
        assertEquals(12, resp.getPromptTokens(),
                "厂商报的是 total_tokens=12；被 DefaultUsage 抹成 0 之后仍要能取回来，不能回显「输入 0」");
    }

    /** 两个字段都取不到正数 ⟹ 留空，不许把 {@code DefaultUsage} 抹出来的 0 当成事实 */
    @Test
    void testTestModel_usageAllZeroedOut_promptTokensIsNull() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "text-embedding-v4", TYPE_EMBEDDING),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        EmbeddingModel embeddingModel = mockEmbeddingClient();
        Usage usage = new DefaultUsage(null, null, null);
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(embeddingResponse(new float[]{1f, 2f}, usage));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertTrue(resp.getSuccess(), resp.getErrorMsg());
        assertNull(resp.getPromptTokens(), "一格用量都没报，这一格必须是 null 而不是 0");
    }

    /**
     * 🔴 一格用量都没报时不许抛 NPE，也不许编数字
     *
     * <p>{@code DashScopeAiUsage#getTotalTokens} 在 {@code total} 与 {@code input} 都为 null 时
     * 会 NPE（字节码里是 {@code getPromptTokens().intValue() + getCompletionTokens().intValue()}），
     * 而探活是<b>成功</b>路径 —— 用量缺失不该把一次调通的测试变成失败。
     */
    @Test
    void testTestModel_usageReportsNothing_promptTokensIsNullAndProbeStillSucceeds() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "gte-rerank-v2", TYPE_RERANK),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        RerankModel rerankModel = mockRerankClient();
        Usage usage = nothingReportedUsage();
        when(rerankModel.call(any(RerankRequest.class))).thenReturn(rerankResponse(usage));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertTrue(resp.getSuccess(), resp.getErrorMsg());
        assertNull(resp.getPromptTokens(), "厂商什么都没报，这一格必须是 null");
        assertTrue(resp.getReply().contains("重排序成功"), "用量缺失不影响「调通」这个结论");
    }

    /**
     * 🔴 厂商那句「url error」必须<b>原文在前、解释在后</b>
     *
     * <p>这句原文是本项目排查代价最高的一句：字面上在说「你填的 URL 错了」，
     * 而通义向量调用的端点<b>压根不由管理员填</b>（代码按模型名路由，密钥 {@code url}
     * 为空时走厂商默认域名）。库里 103/104/105/107/108/109 六行曾长期停在这个形态，
     * 管理员照着字面反复改密钥 URL，一个都改不好。
     *
     * <p>两条断言缺一不可：只留解释就成了「替厂商编病因」（红线禁止），
     * 只留原文就是那个没人看得懂的局面。
     */
    @Test
    void testTestModel_embeddingUrlError_keepsVendorTextAndAddsActionableHint() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "gte-rerank-v2", TYPE_EMBEDDING),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        EmbeddingModel embeddingModel = mockEmbeddingClient();
        when(embeddingModel.call(any(EmbeddingRequest.class))).thenThrow(new RuntimeException(URL_ERROR_BODY));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertFalse(resp.getSuccess());
        assertTrue(resp.getErrorMsg().startsWith(URL_ERROR_BODY),
                "厂商原文必须逐字留在最前面：" + resp.getErrorMsg());
        assertTrue(resp.getErrorMsg().contains("不是在说你填的接口地址错了"));
        assertTrue(resp.getErrorMsg().contains("gte-rerank-v2"), "要点名是哪个模型名不由该端点提供");
        assertTrue(resp.getErrorMsg().contains("不要去改密钥里的 URL"));
        // 分型仍复用现成的 classify：url error 属确定性配置错误 → CONFIG（切换 + 30 分钟冷却）
        assertEquals(AiModelFailureReason.CONFIG.name(), resp.getFailureReason());
        verifyNoInteractions(failoverService);
    }

    /**
     * 🔴 那段解释<b>只</b>挂在 url error 上：额度耗尽是另一回事，加上去等于指错方向
     *
     * <p>库里第 9 行（{@code qwen3.7-text-embedding}，id=65）就是这个形态：
     * 它是真的免费额度耗尽，直连厂商复测回 403 {@code AllocationQuota.FreeTierOnly}。
     * 这一条是「诚实结论不该被修成绿、也不该被装饰」的判据 ——
     * 若哪天有人把解释挪到 {@code invokeProbe} 的公共失败路径上，这条会红。
     */
    @Test
    void testTestModel_embeddingQuotaError_isNotDecoratedWithUrlHint() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "qwen3.7-text-embedding", TYPE_EMBEDDING),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        EmbeddingModel embeddingModel = mockEmbeddingClient();
        when(embeddingModel.call(any(EmbeddingRequest.class))).thenThrow(new RuntimeException(QUOTA_403_BODY));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertFalse(resp.getSuccess());
        assertEquals(QUOTA_403_BODY, resp.getErrorMsg(), "额度耗尽是逐字原文，不加任何解释");
        assertEquals(AiModelFailureReason.QUOTA.name(), resp.getFailureReason());
        verifyNoInteractions(failoverService);
    }

    /**
     * 🔴 重排序模型必须打<b>重排</b>端点，向量与对话客户端一个都不许建
     *
     * <p>重排序不出向量：它收「一个查询 + 若干候选文档」、回「按相关度排序的结果」。
     * 拿重排序模型名去建 {@code EmbeddingModel} 只会得到厂商那句 {@code url error}
     * —— 也就是上一条测的那个形态。库里 103/104 两行（{@code gte-rerank-v2}、
     * {@code qwen3-vl-rerank}）直连厂商实测<b>只有 rerank 端点通</b>，模型本身是好的。
     *
     * <p>回显<b>首条是哪个候选</b>而不只回条数：条数对不代表排序对。
     * 探活的两个候选刻意一条相关、一条无关（见 {@code RERANK_TEST_*}），
     * 所以「首条是相关那条」才是模型真在干活的证据。
     */
    @Test
    void testTestModel_rerankType_probesRerankEndpointAndReportsTopCandidate() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "gte-rerank-v2", TYPE_RERANK),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        RerankModel rerankModel = mockRerankClient();
        when(rerankModel.call(any(RerankRequest.class))).thenReturn(rerankResponse(32));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertTrue(resp.getSuccess(), resp.getErrorMsg());
        assertTrue(resp.getReply().contains("重排序成功，返回 2 条"), resp.getReply());
        assertTrue(resp.getReply().contains("首条相关度 0.9812"), resp.getReply());
        assertTrue(resp.getReply().contains("知识库支持"), "要回显首条是哪个候选，光有条数看不出排序对不对");
        assertEquals(Integer.valueOf(32), resp.getPromptTokens());
        assertNull(resp.getEmbeddingDimensions(), "重排序不出向量");
        assertNull(resp.getCompletionTokens());
        assertNull(resp.getMultiModel());

        // 🔴 探活形态钉住：一个查询 + 两个候选、topN 给满候选数、不回传文档原文。
        //    topN 截断成 1 会让「少返回一条」这类异常形态看不出来
        ArgumentCaptor<RerankRequest> captor = ArgumentCaptor.forClass(RerankRequest.class);
        verify(rerankModel).call(captor.capture());
        RerankRequest request = captor.getValue();
        assertEquals("启凌智搭的知识库支持哪些向量模型", request.getQuery());
        assertEquals(2, request.getInstructions().size());
        // 🔴 请求级 options 上必须<b>再写一遍</b>模型名：DashScopeRerankOptions 的无参构造
        //    自带 model="gte-rerank"，而 SDK 的 mergeOptions 是「请求级非 null 就压过构造时默认值」，
        //    所以工厂里配的那个名字不写在这里就等于没配 —— 厂商会对 gte-rerank 回 403 AccessDenied。
        //    出线请求体的逐字证据见 DashScopeRerankWireContractTest
        assertEquals("gte-rerank-v2", ((DashScopeRerankOptions) request.getOptions()).getModel());
        assertEquals(Integer.valueOf(2), ((DashScopeRerankOptions) request.getOptions()).getTopN());
        verifyNoInteractions(failoverService);
    }

    /**
     * 重排模型建不出来（非通义平台）时，必须落成明细而不是 HTTP 500
     *
     * <p>{@code getOrCreateRerankModel} 对非通义平台抛 {@code IllegalArgumentException}。
     * 本端点的价值全在明细里，抛出去会被全局异常处理器压成一句 message，
     * 弹窗上就只剩「系统异常」—— 与 {@code testTestModel_apiKeyNotExists_returnsDetail} 同一条纪律。
     */
    @Test
    void testTestModel_rerankType_clientBuildFailureSurfacesAsDetailNotHttp500() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "gte-rerank-v2", TYPE_RERANK),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));
        when(modelFactory.getOrCreateRerankModel(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("平台(OpenAI)暂不支持重排序模型"));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertFalse(resp.getSuccess());
        assertFalse(resp.getTimedOut());
        assertTrue(resp.getErrorMsg().contains("暂不支持重排序模型"), resp.getErrorMsg());
        verifyNoInteractions(failoverService);
    }

    /**
     * 🔴 图片 / 语音 / 视频：明确说「不支持」，而不是拿它们的模型名去撞对话端点
     *
     * <p>撞出来的那个 400 会被当成结论回显，管理员读到的是「模型坏了」——
     * 这正是分流之前所有非对话类型的实际遭遇。{@code verifyNoInteractions(modelFactory)}
     * 是这条的着力点：只要有人把兜底改回「其余一律走 chat」，它就红。
     */
    @Test
    void testTestModel_unsupportedType_saysSoInsteadOfProbingChatEndpoint() {
        mockModelAndKey(model(CommonStatusEnum.ENABLE, "wan2.5-t2i", TYPE_IMAGE),
                apiKey(CommonStatusEnum.ENABLE, API_KEY));

        AiModelTestRespVO resp = modelService.testModel(MODEL_ID, 30);

        assertFalse(resp.getSuccess());
        assertTrue(resp.getErrorMsg().contains("暂不支持连通性测试"), resp.getErrorMsg());
        assertTrue(resp.getErrorMsg().contains("图片"), "要点名是哪一类，见 describeModelType");
        assertTrue(resp.getErrorMsg().contains("测试方式不对，不是模型坏了"));
        verifyNoInteractions(modelFactory);
        verifyNoInteractions(failoverService);
    }

    // ==================== 夹具 ====================

    private AiModelDO model(CommonStatusEnum status, String modelFlag) {
        return model(status, modelFlag, TYPE_CHAT);
    }

    private AiModelDO model(CommonStatusEnum status, String modelFlag, Integer type) {
        return AiModelDO.builder().id(MODEL_ID).keyId(KEY_ID).name("通义千问-Max")
                .model(modelFlag).platform(PLATFORM_TONG_YI).type(type)
                .sort(1).status(status.getStatus())
                .temperature(0.7).maxTokens(4096).maxContexts(20)
                .build();
    }

    private AiApiKeyDO apiKey(CommonStatusEnum status, String key) {
        return AiApiKeyDO.builder().id(KEY_ID).name("通义千问").apiKey(key)
                .platform(PLATFORM_TONG_YI).url("https://dashscope.aliyuncs.com")
                .status(status.getStatus())
                .build();
    }

    private void mockModelAndKey(AiModelDO model, AiApiKeyDO apiKey) {
        when(modelMapper.selectById(MODEL_ID)).thenReturn(model);
        when(apiKeyService.getApiKey(KEY_ID)).thenReturn(apiKey);
    }

    /**
     * 只 stub 客户端工厂 —— 用 {@code getApiKey}（非校验版）拿到的密钥与
     * {@code getOrCreateChatModel} 才是「禁用也照测」那条路径
     */
    private ChatModel mockClient() {
        ChatModel chatModel = mock(ChatModel.class);
        when(modelFactory.getOrCreateChatModel(any(), any(), any())).thenReturn(chatModel);
        return chatModel;
    }

    private static ChatResponse response(String content, Integer promptTokens, Integer completionTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))), metadata);
    }

    private static Prompt capturePrompt(ChatModel chatModel) {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        return captor.getValue();
    }

    private EmbeddingModel mockEmbeddingClient() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(modelFactory.getOrCreateEmbeddingModel(any(), any(), any(), any())).thenReturn(embeddingModel);
        return embeddingModel;
    }

    private RerankModel mockRerankClient() {
        RerankModel rerankModel = mock(RerankModel.class);
        when(modelFactory.getOrCreateRerankModel(any(), any(), any(), any())).thenReturn(rerankModel);
        return rerankModel;
    }

    /**
     * @param totalTokens {@code null} = 厂商压根没回 {@code usage}
     */
    private static EmbeddingResponse embeddingResponse(float[] vector, Integer totalTokens) {
        Usage usage = totalTokens == null ? null : new DefaultUsage(totalTokens, null, totalTokens);
        return embeddingResponse(vector, usage);
    }

    /**
     * 用量形态自己给的那一档
     *
     * <p>{@code DefaultUsage} 造不出「报了 total、没报 input」这一档（它的构造把 null 一律按 0 处理），
     * 所以字段名不统一那几个用例走 {@link #totalOnlyUsage}，从这里进来。
     */
    private static EmbeddingResponse embeddingResponse(float[] vector, Usage usage) {
        return new EmbeddingResponse(List.of(new Embedding(vector, 0)),
                new EmbeddingResponseMetadata("text-embedding-v4", usage));
    }

    /**
     * 两条结果，首条是<b>相关</b>那个候选、分高；第二条是无关候选、分低
     *
     * <p>顺序刻意与请求顺序相反（请求里相关那条也在前，但这里想钉的是「首条是谁」这件事
     * 由厂商的分数决定、不是由数组下标决定），所以第二条给了一个明显更低的分。
     */
    private static RerankResponse rerankResponse(Integer totalTokens) {
        Usage usage = totalTokens == null ? null : new DefaultUsage(totalTokens, null, totalTokens);
        return rerankResponse(usage);
    }

    private static RerankResponse rerankResponse(Usage usage) {
        DocumentWithScore related = DocumentWithScore.builder()
                .withDocument(new Document("启凌智搭的知识库支持通义千问的向量模型"))
                .withScore(0.98123).build();
        DocumentWithScore unrelated = DocumentWithScore.builder()
                .withDocument(new Document("紫色河马在星期四下午三点十七分跳了一支探戈。"))
                .withScore(0.0154).build();
        return new RerankResponse(List.of(related, unrelated), new RerankResponseMetadata(usage));
    }

    /**
     * 厂商只报了 {@code total_tokens}、没报 {@code input_tokens} 的用量形态
     *
     * <p>🔴 只能用 mock 造：{@code DefaultUsage} 的构造把 null 一律按 0 处理（javap 核实），
     * 于是 {@code getPromptTokens()} 永远非 null；而真正会返回 null 的
     * {@code DashScopeAiUsage} 构造是 {@code protected}，测试包里够不着。
     * 实测这一档真实存在：{@code gte-rerank-v2} 回 {@code {"total_tokens":79}}、
     * {@code text-embedding-v4} 回 {@code {"total_tokens":12}}。
     */
    private static Usage totalOnlyUsage(int totalTokens) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(null);
        when(usage.getTotalTokens()).thenReturn(totalTokens);
        return usage;
    }

    /**
     * 厂商一格用量都没报：{@code getTotalTokens()} 会 NPE
     *
     * <p>照 {@code DashScopeAiUsage} 的字节码复刻 —— 它在 {@code total} 与 {@code input}
     * 都为 null 时执行 {@code getPromptTokens().intValue() + getCompletionTokens().intValue()}。
     */
    private static Usage nothingReportedUsage() {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(null);
        when(usage.getTotalTokens()).thenThrow(new NullPointerException("total 与 input 都为 null"));
        return usage;
    }

}
