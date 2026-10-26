package cn.enlev.ainb.module.agent.service.chat;

import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.enums.AgentIntentEnum;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentChatProperties;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentPlanProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentIntentClassifier;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentSecurityGateway;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolExecutor;
import cn.enlev.ainb.module.agent.service.conversation.AgentConversationService;
import cn.enlev.ainb.module.agent.service.def.AgentDefService;
import cn.enlev.ainb.module.agent.service.usage.AgentUsageLogService;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentPromptBuilder;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentSuggestionParser;
import cn.enlev.ainb.module.agent.tool.impl.doc.DocSearchTool;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 助手问答的计量单元测试
 *
 * <h3>只测一件事，但这件事错了没人会发现</h3>
 * 计量表的定位是<b>「对租户出示收费证据」</b>。既然是证据，
 * 「多记」和「少记」都是错的，而且两者都不会报错、不会有异常、不会让任何用例变红 ——
 * 只会在某天有人来问「这笔钱是怎么算的」时才暴露。
 *
 * @author 走进新时代
 */
public class AgentAssistantServiceImplUsageTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentAssistantServiceImpl assistantService;

    @Mock
    private AgentSecurityGateway gateway;
    @Mock
    private AgentToolExecutor toolExecutor;
    @Mock
    private AgentConversationService conversationService;
    @Spy
    private AgentPromptBuilder promptBuilder = new AgentPromptBuilder();
    @Mock
    private AgentSuggestionParser suggestionParser;
    @Mock
    private AgentUsageLogService usageLogService;
    @Mock
    private AiModelApi aiModelApi;
    @Spy
    private AgentChatProperties chatProperties = new AgentChatProperties();
    @Mock
    private AgentIntentClassifier intentClassifier;
    @Mock
    private AgentPlanProperties planProperties;
    /**
     * 配额检查在 chat() 入口就会跑，不 mock 的话这里直接 NPE。
     * 默认行为（什么都不做）正好等于「没配置配额 = 不限制」
     */
    @Mock
    private cn.enlev.ainb.module.agent.service.quota.AgentQuotaService quotaService;
    /**
     * 需求10 起，「未选 Agent」这条路会去读内置行拿平台助手 persona，故必须 mock（否则字段为 null 直接 NPE）。
     * 🔴 刻意不 stub：未打桩的 {@code getPlatformAssistant()} 返回 null，
     * 而 null 正是「种子迁移没跑」的真实场景 —— {@code AgentPromptBuilder} 会回落到代码里的出厂提示词。
     * 计量断言与 persona 无关，让它走回落路径最省事、也顺带覆盖了回落分支。
     */
    @Mock
    private AgentDefService agentDefService;

    @BeforeEach
    public void setUp() {
        // 上下文快照要在「有身份」的线程上捕获，否则网关会直接罢工
        LoginUser loginUser = new LoginUser().setId(1L).setTenantId(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
        TenantContextHolder.setTenantId(1L);

        // chat() 建新会话走三参重载（agentDefId=null=默认助手），故桩三参版
        when(conversationService.createConversation(any(), any(), any())).thenReturn(100L);
        when(conversationService.getMessageList(any(), any())).thenReturn(Collections.emptyList());
        when(conversationService.appendMessage(any())).thenReturn(200L);
        // 计量测试只测 CHAT 路径，分类器一律回落 CHAT
        lenient().when(intentClassifier.classify(any(), any(), any())).thenReturn(AgentIntentEnum.CHAT);
        // lenient：失败路径走不到解析建议那一步（流在 onComplete 之前就断了），
        // 严格模式会把这个共用桩报成 UnnecessaryStubbing
        lenient().when(suggestionParser.parse(anyString(), any())).thenReturn(Collections.emptyList());
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    private AgentAssistantService.AgentChatReqBO request() {
        AgentAssistantService.AgentChatReqBO reqBO = new AgentAssistantService.AgentChatReqBO();
        reqBO.setContent("为什么我配的联动不生效");
        return reqBO;
    }

    private AgentUsageLogService.UsageRecord captureRecord() {
        ArgumentCaptor<AgentUsageLogService.UsageRecord> captor =
                ArgumentCaptor.forClass(AgentUsageLogService.UsageRecord.class);
        verify(usageLogService).record(captor.capture());
        return captor.getValue();
    }

    /**
     * 🔴 请求没发出去就失败 → 厂商零消耗 → 必须记 0
     *
     * 实测过这个坑：把模型停用后问一句，估算出 2098 token 并写进了计量表。
     * 用户要是较真问「我连回答都没看到，凭什么扣钱」，我们无从解释。
     */
    @Test
    @DisplayName("一个 chunk 都没收到（请求没发出去）：token 必须记 0，不能凭空估算")
    public void testNoTokensWhenRequestNeverReachedProvider() {
        when(toolExecutor.execute(any(), anyString(), any()))
                .thenReturn(new DocSearchTool.DocSearchResult("q", 0, List.of(), ""));
        when(aiModelApi.chatStream(any()))
                .thenReturn(Flux.error(new IllegalStateException("模型已禁用")));

        assistantService.chat(request(), AgentExecutionContext.snapshot())
                .onErrorResume(e -> Flux.empty()).blockLast();

        AgentUsageLogService.UsageRecord record = captureRecord();
        assertEquals("FAILURE", record.getStatus());
        assertEquals(0, record.getTotalTokens(), "请求没到厂商，不该产生任何 token");
        assertEquals(0, record.getPromptTokens());
        assertEquals(0, record.getCompletionTokens());
    }

    /**
     * 与上一条相反：已经收到过响应，说明 token 在厂商那边已经烧掉了，必须照记。
     */
    @Test
    @DisplayName("收到过 chunk 后才失败：token 已消耗，必须按估算记上")
    public void testEstimateWhenProviderAlreadyResponded() {
        when(toolExecutor.execute(any(), anyString(), any()))
                .thenReturn(new DocSearchTool.DocSearchResult("q", 0, List.of(), ""));
        AiChatRespDTO chunk = new AiChatRespDTO();
        chunk.setContent("已经答了一半");
        chunk.setModel("qwen3.7-plus");
        chunk.setPlatform("TongYi");
        when(aiModelApi.chatStream(any()))
                .thenReturn(Flux.just(chunk).concatWith(Flux.error(new RuntimeException("断了"))));

        assistantService.chat(request(), AgentExecutionContext.snapshot())
                .onErrorResume(e -> Flux.empty()).blockLast();

        AgentUsageLogService.UsageRecord record = captureRecord();
        assertEquals("FAILURE", record.getStatus());
        assertTrue(record.getTotalTokens() > 0, "已经收到过响应，token 必须记上");
        assertEquals("ESTIMATED", record.getTokenSource());
        assertEquals("qwen3.7-plus", record.getModelName());
    }

    @Test
    @DisplayName("厂商回传了 usage：原样采用，来源标 PROVIDER")
    public void testUseProviderUsage() {
        when(toolExecutor.execute(any(), anyString(), any()))
                .thenReturn(new DocSearchTool.DocSearchResult("q", 0, List.of(), ""));
        AiChatRespDTO chunk = new AiChatRespDTO();
        chunk.setContent("回答");
        chunk.setModel("qwen3.7-plus");
        chunk.setPlatform("TongYi");
        chunk.setUsage(AiChatRespDTO.Usage.of(2687, 741, 3428));
        when(aiModelApi.chatStream(any())).thenReturn(Flux.just(chunk));

        assistantService.chat(request(), AgentExecutionContext.snapshot()).blockLast();

        AgentUsageLogService.UsageRecord record = captureRecord();
        assertEquals("SUCCESS", record.getStatus());
        assertEquals("PROVIDER", record.getTokenSource());
        assertEquals(2687, record.getPromptTokens());
        assertEquals(741, record.getCompletionTokens());
        assertEquals(3428, record.getTotalTokens());
    }

    /**
     * 🔴 三处落库点只能有一处生效
     *
     * 没有这个闸，「先 error 后 cancel」之类的组合会写出两条记录 —— 那是重复计费。
     */
    @Test
    @DisplayName("一次对话只写一条计量记录")
    public void testSettleOnlyOnce() {
        when(toolExecutor.execute(any(), anyString(), any()))
                .thenReturn(new DocSearchTool.DocSearchResult("q", 0, List.of(), ""));
        AiChatRespDTO chunk = new AiChatRespDTO();
        chunk.setContent("回答");
        when(aiModelApi.chatStream(any())).thenReturn(Flux.just(chunk));

        assistantService.chat(request(), AgentExecutionContext.snapshot()).blockLast();

        // verify 默认就是 times(1)，多写一条这里会直接红
        captureRecord();
    }

}
