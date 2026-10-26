package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link LlmConditionNodeExecutor} 单测
 *
 * <p>核心约束：模型输出必须经白名单枚举收敛 —— 命中选项才路由，否则落 default，
 * 绝不把模型自由文本直接当路由键（否则多说一个字就路由到不存在的节点，图崩）。
 * 同时验证：路由键落在 {@code __route_<id>}、outputVar 存一份判定结果、token 度量正确归因（PROVIDER）。
 *
 * @author 走进新时代
 */
class LlmConditionNodeExecutorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private LlmConditionNodeExecutor executor;

    @Mock
    private AiModelApi aiModelApi;

    private static WorkflowContext ctx(Map<String, Object> data) {
        OverAllState state = new OverAllState(new HashMap<>(data));
        return new WorkflowContext(state, 1L, "t1:1", AgentExecutionContext.ofForTest(1L, 1L));
    }

    private static NodeDTO node(Map<String, Object> config) {
        NodeDTO n = new NodeDTO();
        n.setId("classify");
        n.setType(LlmConditionNodeExecutor.TYPE);
        n.setConfig(config);
        return n;
    }

    private static Map<String, Object> baseConfig() {
        Map<String, Object> c = new HashMap<>();
        c.put("choices", List.of("positive", "negative"));
        c.put("default", "negative");
        c.put("prompt", "判断这句话的情感：{{text}}");
        c.put("outputVar", "sentiment");
        return c;
    }

    private void mockModel(String content, Long modelId, AiChatRespDTO.Usage usage) {
        AiChatRespDTO resp = new AiChatRespDTO();
        resp.setContent(content);
        resp.setModelId(modelId);
        resp.setUsage(usage);
        when(aiModelApi.chat(any())).thenReturn(resp);
    }

    private String routeOf(Map<String, Object> delta) {
        assertTrue(delta.containsKey(NodeExecutor.routeKey("classify")), "delta 应含路由键");
        return String.valueOf(delta.get(NodeExecutor.routeKey("classify")));
    }

    @Test
    void execute_exactChoice_routesAndRecordsUsage() {
        mockModel("positive", 67L, AiChatRespDTO.Usage.of(10, 2, 12));
        WorkflowContext ctx = ctx(Map.of("text", "很好"));
        Map<String, Object> delta = executor.execute(node(baseConfig()), ctx);

        assertEquals("positive", routeOf(delta));
        // outputVar 存一份判定结果，便于排障「为什么走了这条」
        assertEquals("positive", delta.get("sentiment"));
        // token 度量归因到实际模型，来源 PROVIDER（厂商回传）
        assertEquals(67L, ctx.getMetrics().getModelId());
        assertEquals("PROVIDER", ctx.getMetrics().getTokenSource());
        assertEquals(12, ctx.getMetrics().getTotalTokens());
    }

    @Test
    void execute_contentNotInChoices_fallsToDefault() {
        // 模型自由发挥输出白名单外的词 → 必须落 default，不能当路由键
        mockModel("也许吧", 67L, AiChatRespDTO.Usage.of(5, 1, 6));
        Map<String, Object> delta = executor.execute(node(baseConfig()), ctx(Map.of("text", "x")));
        assertEquals("negative", routeOf(delta));
        assertEquals("negative", delta.get("sentiment"));
    }

    @Test
    void execute_contentContainsChoice_matchesIt() {
        // 模型多说了话但包含某个选项词 → 取第一个包含的
        mockModel("这句话整体是 positive 的", 67L, null);
        Map<String, Object> delta = executor.execute(node(baseConfig()), ctx(Map.of("text", "x")));
        assertEquals("positive", routeOf(delta));
    }

    @Test
    void execute_blankContent_fallsToDefault() {
        mockModel("   ", 67L, null);
        Map<String, Object> delta = executor.execute(node(baseConfig()), ctx(Map.of("text", "x")));
        assertEquals("negative", routeOf(delta));
    }

    @Test
    void execute_nullUsage_recordsModelIdButStaysEstimated() {
        // 厂商没回传 usage：记 modelId，tokenSource 保持默认 ESTIMATED、token 不填
        mockModel("negative", 66L, null);
        WorkflowContext ctx = ctx(Map.of("text", "x"));
        executor.execute(node(baseConfig()), ctx);

        assertEquals(66L, ctx.getMetrics().getModelId());
        assertEquals("ESTIMATED", ctx.getMetrics().getTokenSource());
        assertEquals(null, ctx.getMetrics().getTotalTokens());
    }

    @Test
    void execute_caseInsensitiveMatch() {
        mockModel("POSITIVE", 67L, AiChatRespDTO.Usage.of(3, 1, 4));
        Map<String, Object> delta = executor.execute(node(baseConfig()), ctx(Map.of("text", "x")));
        // 命中后返回白名单里的原词（而非模型输出的大写形态）
        assertEquals("positive", routeOf(delta));
    }

}
