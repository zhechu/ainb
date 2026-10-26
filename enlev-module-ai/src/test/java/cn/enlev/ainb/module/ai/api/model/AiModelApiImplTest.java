package cn.enlev.ainb.module.ai.api.model;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import cn.enlev.ainb.module.ai.dal.mysql.model.AiChatMapper;
import cn.enlev.ainb.module.ai.framework.ai.core.model.AiModelFactory;
import cn.enlev.ainb.module.ai.service.model.AiApiKeyService;
import cn.enlev.ainb.module.ai.service.model.AiModelFailoverServiceImpl;
import cn.enlev.ainb.module.ai.service.model.AiModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_ALL_UNAVAILABLE;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_QUOTA_EXHAUSTED;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_TASK_PINNED_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiModelApiImpl} 的任务级粘滞实证（缺陷 D4-28 / 裁定 R-01）
 *
 * <h3>为什么要在 api 层再测一遍，而不是只测 {@code AiModelFailoverServiceImpl}</h3>
 * 粘滞由<b>两半</b>拼成：service 负责「钉住了就只用它」，api 负责「成功后把钉子钉上、
 * 并把 {@code taskKey} 从 DTO 传下去」。只测 service 的话，api 层漏调 {@code pinTaskModel}、
 * 或漏传 {@code reqDTO.getTaskKey()} 这两种接线错误<b>一个测试都不会红</b> ——
 * 整个特性静默失效而单测全绿。所以这里用<b>真的</b> {@link AiModelFailoverServiceImpl}
 * 串起 {@code AiModelApiImpl}，只 mock 掉 DB 与厂商客户端。
 *
 * <p>🔴 全程零厂商调用：{@code ChatModel} 是 mock，额度耗尽用现场那段 403 原文模拟。
 * 红线 §10.2 —— 不探测 AI 厂商，一次探测就是在共享账号上花一次钱。
 *
 * @author 走进新时代
 */
class AiModelApiImplTest extends BaseMockitoUnitTest {

    private AiModelApiImpl modelApi;

    /** 🔴 真实现，不是 mock：本测试要验的正是 api 层与 service 层接线之后的行为 */
    private AiModelFailoverServiceImpl failoverService;

    @Mock
    private AiChatMapper modelMapper;
    @Mock
    private AiApiKeyService apiKeyService;
    @Mock
    private AiModelFactory modelFactory;
    @Mock
    private AiModelService modelService;

    @Mock
    private ChatModel chatModel1;
    @Mock
    private ChatModel chatModel2;

    private static final Integer TYPE_CHAT = 1;
    private static final String TASK_A = "EXEC:90001";
    private static final String TASK_B = "EXEC:90002";

    /** 逐字取自现场后端日志的厂商 403（实测，非构造） */
    private static final String QUOTA_403_BODY = "403 - {\"code\":\"AllocationQuota.FreeTierOnly\","
            + "\"message\":\"Free quota exhausted. To continue accessing the model on a paid basis, "
            + "please add funds or disable the \\\"use free tier only\\\" mode in the management console.\"}";

    @BeforeEach
    void setUp() {
        failoverService = new AiModelFailoverServiceImpl();
        ReflectionTestUtils.setField(failoverService, "modelMapper", modelMapper);
        ReflectionTestUtils.setField(failoverService, "apiKeyService", apiKeyService);
        ReflectionTestUtils.setField(failoverService, "modelFactory", modelFactory);

        modelApi = new AiModelApiImpl();
        ReflectionTestUtils.setField(modelApi, "failoverService", failoverService);
        ReflectionTestUtils.setField(modelApi, "modelService", modelService);
        ReflectionTestUtils.setField(modelApi, "apiKeyService", apiKeyService);
    }

    /**
     * ①→②→③ 的完整链路：额度耗尽算这次失败 ⟹ 同一任务后续调用<b>不</b>切换 ⟹ 下一个任务落到下一个候选
     *
     * <p>这是 D4-28 的行为层判据。四步共用一套 mock 状态，刻意写成一个测试而不是拆开 ——
     * 拆开后每一步都能单独绿，而「额度耗尽之后同一个任务仍然被拦着、换一个任务才放行」
     * 这个<b>跨步</b>语义就没人验了，而那恰好就是本次要修的东西。
     */
    @Test
    void testChat_quotaExhausted_failsThenSticksThenNextTaskFallsToNextCandidate() {
        mockTwoModelsTwoKeys();
        when(chatModel1.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));
        when(chatModel2.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));

        // ---- 第 1 步：任务 A 首次调用，model 1 成功 ⟹ 任务 A 钉在 model 1 ----
        assertEquals(1L, modelApi.chat(req(TASK_A, null)).getModelId());

        // ---- 第 2 步：model 1 额度耗尽。本次调用判失败，且 model 2 一次都不许被调 ----
        when(chatModel1.call(any(Prompt.class))).thenThrow(new RuntimeException(QUOTA_403_BODY));
        ServiceException quotaError = assertThrows(ServiceException.class,
                () -> modelApi.chat(req(TASK_A, null)));
        assertEquals(MODEL_QUOTA_EXHAUSTED.getCode(), quotaError.getCode());
        // 失败要可查：消息里带模型编号/厂商标识 + 厂商原文，agent 侧原样落进 error_msg
        assertTrue(quotaError.getMessage().contains("1/m-1"), quotaError.getMessage());
        assertTrue(quotaError.getMessage().contains("AllocationQuota.FreeTierOnly"),
                quotaError.getMessage());

        // ---- 第 3 步：🔴 同一个任务的后续调用【仍然不切换】----
        // 钉子还在 model 1 上，而 model 1 正处于额度长冷却 ⟹ 空链 ⟹ 明确报「钉住的模型不可用」。
        // 这里就是 R-01 与旧行为的分水岭：旧行为会悄悄换成 model 2 把它救回来
        ServiceException pinnedError = assertThrows(ServiceException.class,
                () -> modelApi.chat(req(TASK_A, null)));
        assertEquals(MODEL_TASK_PINNED_UNAVAILABLE.getCode(), pinnedError.getCode());
        assertTrue(pinnedError.getMessage().contains("1"), pinnedError.getMessage());
        verify(chatModel2, never()).call(any(Prompt.class));

        // ---- 第 4 步：换一个新任务 ⟹ 落到下一个候选 model 2 ----
        assertEquals(2L, modelApi.chat(req(TASK_B, null)).getModelId());
        verify(chatModel2).call(any(Prompt.class));
    }

    /**
     * 钉子是<b>按「任务 + 请求的模型」分档</b>的：显式配置不许被别的档顶掉，同档内不许被改写
     *
     * <p>🔴 这条曾经断言的是相反的东西（「显式换 modelId 也不许改写钉子」），那是错的：
     * 一次流程执行里两个 LLM 节点可以各配不同 modelId（{@code LlmNodeExecutor} /
     * {@code LlmConditionNodeExecutor} 都读 {@code node.configLong("modelId")}），
     * 却共用 {@code EXEC:<executionId>} 一个 taskKey。只按任务钉 ⟹ 后一个节点被前一个
     * 钉住的模型<b>静默顶掉</b>：用户明明配了 2，跑出来是 1，而且不报错。
     * R-01 管的是「模型自己换来换去」，不是「把用户的选择改掉」。
     *
     * <p>同时要保住「不许绕过口径」那半边：<b>同一档内</b>钉子照旧不许被后一次调用挪走。
     */
    @Test
    void testChat_pinIsPerRequestedModel_explicitConfigNotOverridden() {
        mockTwoModelsTwoKeys();
        when(chatModel1.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));
        when(chatModel2.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));

        // 档 (TASK_A, 未指定)：第一次选中 1 并钉住，第二次仍是 1（同档内钉子挪不走）
        assertEquals(1L, modelApi.chat(req(TASK_A, null)).getModelId());
        assertEquals(1L, modelApi.chat(req(TASK_A, null)).getModelId());

        // 档 (TASK_A, 2)：显式配的 2 必须真的用上，不被上一档的钉子顶成 1
        assertEquals(2L, modelApi.chat(req(TASK_A, 2L)).getModelId());
        verify(chatModel2).call(any(Prompt.class));
        // 这一档也钉住了：再来一次仍是 2，不会漂回 1
        assertEquals(2L, modelApi.chat(req(TASK_A, 2L)).getModelId());
    }

    /**
     * 不传 taskKey ⟹ 行为与今天完全一致：逐次调用各自选模型、瞬时失败照旧切换
     *
     * <p>🔴 这条是给 ai 模块自己那四条链路（聊天 / 会话 / 思维导图 / 写作）兜底的。
     * 本组件是平台级的，改口径不能顺手改到流程编排以外去
     */
    @Test
    void testChat_withoutTaskKey_transientFailureStillSwitches() {
        mockTwoModelsTwoKeys();
        when(chatModel1.call(any(Prompt.class))).thenThrow(new RuntimeException("connection reset by peer"));
        when(chatModel2.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));

        assertEquals(2L, modelApi.chat(req(null, null)).getModelId());
    }

    /**
     * 空链的两种成因必须分开报，不能都退化成「去启用一个模型」
     *
     * <p>「额度没了」被报成「环境没配好」，管理员会照着错的方向排查（红线 §10.2：不编病因）
     */
    @Test
    void testChat_noModelAtAll_reportsConfigurationHint() {
        when(modelMapper.selectListByStatusAndType(CommonStatusEnum.ENABLE.getStatus(), TYPE_CHAT, null))
                .thenReturn(List.of());
        when(apiKeyService.getApiKeyList()).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class, () -> modelApi.chat(req(null, null)));
        assertEquals(MODEL_ALL_UNAVAILABLE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("请在「AI 模型」里启用一个"), ex.getMessage());
    }

    // ==================== 工具方法 ====================

    private AiChatReqDTO req(String taskKey, Long modelId) {
        AiChatReqDTO reqDTO = new AiChatReqDTO();
        reqDTO.setTaskKey(taskKey);
        reqDTO.setModelId(modelId);
        reqDTO.setMessages(List.of(AiChatReqDTO.Message.of("user", "你好")));
        return reqDTO;
    }

    /**
     * 两个启用的聊天模型，各挂一把启用的密钥
     *
     * <p>🔴 必须<b>各挂一把</b>：{@code AiModelFactory} 按（平台, 密钥, 地址）缓存客户端，
     * 两个模型共用一把密钥时会拿到同一个 {@code ChatModel} 实例，
     * 那就再也分不清「哪一次调用打到了哪个模型」—— 本测试的全部断言都建立在这个区分上。
     *
     * <p>这里的 stub 一律 {@code lenient}：走不走得到取决于该次调用有没有命中粘滞短路
     * （命中就连 {@code selectById} / {@code getApiKeyList} 都不会读），
     * 而「哪些读取被跳过」本身正是被测行为的一部分，不该被严格 stub 判成错误
     */
    private void mockTwoModelsTwoKeys() {
        Mockito.lenient().when(modelMapper.selectListByStatusAndType(
                        CommonStatusEnum.ENABLE.getStatus(), TYPE_CHAT, null))
                .thenReturn(List.of(model(1L, 10L, 1), model(2L, 20L, 2)));
        Mockito.lenient().when(modelMapper.selectById(1L)).thenReturn(model(1L, 10L, 1));
        Mockito.lenient().when(modelMapper.selectById(2L)).thenReturn(model(2L, 20L, 2));
        Mockito.lenient().when(apiKeyService.getApiKeyList()).thenReturn(List.of(apiKey(10L), apiKey(20L)));
        Mockito.lenient().when(apiKeyService.getApiKey(10L)).thenReturn(apiKey(10L));
        Mockito.lenient().when(apiKeyService.getApiKey(20L)).thenReturn(apiKey(20L));
        Mockito.lenient().when(modelFactory.getOrCreateChatModel(any(), eq("sk-10"), anyString()))
                .thenReturn(chatModel1);
        Mockito.lenient().when(modelFactory.getOrCreateChatModel(any(), eq("sk-20"), anyString()))
                .thenReturn(chatModel2);
    }

    private AiModelDO model(Long id, Long keyId, Integer sort) {
        return AiModelDO.builder().id(id).keyId(keyId).sort(sort)
                .name("模型" + id).model("m-" + id).platform("TongYi")
                .type(TYPE_CHAT).status(CommonStatusEnum.ENABLE.getStatus())
                .build();
    }

    private AiApiKeyDO apiKey(Long id) {
        return AiApiKeyDO.builder().id(id).status(CommonStatusEnum.ENABLE.getStatus())
                .platform("TongYi").apiKey("sk-" + id).url("http://localhost")
                .build();
    }

}
