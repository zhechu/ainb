package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.ai.api.rag.AiRagApi;
import cn.enlev.ainb.module.ai.api.rag.dto.AiRagSegmentRespDTO;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeRetrievalNodeExecutor} 单测
 *
 * <p>mock {@link AiRagApi}，验证节点的四件事：
 * <ol>
 *   <li>query 来源：queryVar 优先于 query 模板串（模板串走 StateTemplateResolver，支持嵌套路径）</li>
 *   <li>outputVar 写的是<b>各段 content 的 List&lt;String&gt;</b>（不是整个 DTO），默认键名 {@code retrieved}</li>
 *   <li>topK/similarityThreshold 透传给 api；空召回 → 空列表（非错误）；content 为 null 的段被跳过</li>
 *   <li>fail-close：缺 knowledgeBaseId → 抛 ServiceException；retrieve 异常 → 冒泡</li>
 * </ol>
 *
 * @author 走进新时代
 */
class KnowledgeRetrievalNodeExecutorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private KnowledgeRetrievalNodeExecutor executor;

    @Mock
    private AiRagApi aiRagApi;

    private static WorkflowContext ctx(Map<String, Object> data) {
        OverAllState state = new OverAllState(new HashMap<>(data));
        return new WorkflowContext(state, 1L, "t1:1", AgentExecutionContext.ofForTest(1L, 1L));
    }

    private static NodeDTO node(Map<String, Object> config) {
        NodeDTO n = new NodeDTO();
        n.setId("rag");
        n.setType(KnowledgeRetrievalNodeExecutor.TYPE);
        n.setConfig(config);
        return n;
    }

    private static AiRagSegmentRespDTO seg(String content) {
        AiRagSegmentRespDTO s = new AiRagSegmentRespDTO();
        s.setContent(content);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> out(Map<String, Object> delta, String key) {
        assertTrue(delta.containsKey(key), "delta 应含 outputVar " + key);
        return (List<String>) delta.get(key);
    }

    @Test
    void execute_queryFromQueryVar_writesContentList() {
        when(aiRagApi.retrieve(eq(7L), eq("验收标准"), isNull(), isNull()))
                .thenReturn(List.of(seg("段A"), seg("段B")));
        Map<String, Object> config = new HashMap<>();
        config.put("knowledgeBaseId", 7L);
        config.put("queryVar", "user_input");

        Map<String, Object> delta = executor.execute(node(config), ctx(Map.of("user_input", "验收标准")));

        assertEquals(List.of("段A", "段B"), out(delta, "retrieved"));
    }

    @Test
    void execute_queryFromTemplateWithNestedPath() {
        // queryVar 缺省时用 query 模板串，经 StateTemplateResolver 解析嵌套路径
        when(aiRagApi.retrieve(eq(7L), eq("关于 软件开发 的验收"), isNull(), isNull()))
                .thenReturn(List.of(seg("命中")));
        Map<String, Object> config = new HashMap<>();
        config.put("knowledgeBaseId", 7L);
        config.put("query", "关于 {{$.contract.subject}} 的验收");

        Map<String, Object> delta = executor.execute(node(config),
                ctx(Map.of("contract", Map.of("subject", "软件开发"))));

        assertEquals(List.of("命中"), out(delta, "retrieved"));
    }

    @Test
    void execute_queryVarTakesPrecedenceOverQueryTemplate() {
        when(aiRagApi.retrieve(any(), any(), any(), any())).thenReturn(List.of());
        Map<String, Object> config = new HashMap<>();
        config.put("knowledgeBaseId", 7L);
        config.put("queryVar", "q");
        config.put("query", "不该用我");
        executor.execute(node(config), ctx(Map.of("q", "用我")));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(aiRagApi).retrieve(eq(7L), captor.capture(), isNull(), isNull());
        assertEquals("用我", captor.getValue());
    }

    @Test
    void execute_topKAndThresholdPassedThrough() {
        when(aiRagApi.retrieve(any(), any(), any(), any())).thenReturn(List.of(seg("x")));
        Map<String, Object> config = new HashMap<>();
        config.put("knowledgeBaseId", 7L);
        config.put("queryVar", "q");
        config.put("topK", 5);
        config.put("similarityThreshold", 0.8);
        config.put("outputVar", "docs");
        executor.execute(node(config), ctx(Map.of("q", "查询")));

        verify(aiRagApi).retrieve(eq(7L), eq("查询"), eq(5), eq(0.8));
    }

    @Test
    void execute_emptyRecall_writesEmptyList() {
        when(aiRagApi.retrieve(any(), any(), any(), any())).thenReturn(List.of());
        Map<String, Object> config = new HashMap<>();
        config.put("knowledgeBaseId", 7L);
        config.put("queryVar", "q");
        Map<String, Object> delta = executor.execute(node(config), ctx(Map.of("q", "无结果")));
        assertTrue(out(delta, "retrieved").isEmpty(), "空召回应写空列表，不是错误");
    }

    @Test
    void execute_nullApiResult_writesEmptyList() {
        when(aiRagApi.retrieve(any(), any(), any(), any())).thenReturn(null);
        Map<String, Object> config = new HashMap<>();
        config.put("knowledgeBaseId", 7L);
        config.put("queryVar", "q");
        Map<String, Object> delta = executor.execute(node(config), ctx(Map.of("q", "x")));
        assertTrue(out(delta, "retrieved").isEmpty());
    }

    @Test
    void execute_skipsSegmentsWithNullContent() {
        when(aiRagApi.retrieve(any(), any(), any(), any()))
                .thenReturn(List.of(seg("有效"), seg(null)));
        Map<String, Object> config = new HashMap<>();
        config.put("knowledgeBaseId", 7L);
        config.put("queryVar", "q");
        Map<String, Object> delta = executor.execute(node(config), ctx(Map.of("q", "x")));
        assertEquals(List.of("有效"), out(delta, "retrieved"));
    }

    @Test
    void execute_missingKnowledgeBaseId_throws() {
        Map<String, Object> config = new HashMap<>();
        config.put("queryVar", "q"); // 没有 knowledgeBaseId
        assertThrows(ServiceException.class,
                () -> executor.execute(node(config), ctx(Map.of("q", "x"))));
    }

    @Test
    void execute_retrieveThrows_propagates() {
        when(aiRagApi.retrieve(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("库不存在"));
        Map<String, Object> config = new HashMap<>();
        config.put("knowledgeBaseId", 7L);
        config.put("queryVar", "q");
        assertThrows(RuntimeException.class,
                () -> executor.execute(node(config), ctx(Map.of("q", "x"))));
    }

    // ==================== 引用出处 citationsVar（增量16C） ====================

    private static AiRagSegmentRespDTO seg(String content, Long id, Long docId, String docName, Double score) {
        AiRagSegmentRespDTO s = new AiRagSegmentRespDTO();
        s.setContent(content);
        s.setId(id);
        s.setDocumentId(docId);
        s.setDocumentName(docName);
        s.setKnowledgeId(7L);
        s.setScore(score);
        return s;
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_citationsVarSet_writesCitationListAlignedToSegmentDto() {
        when(aiRagApi.retrieve(any(), any(), any(), any()))
                .thenReturn(List.of(seg("段A", 11L, 37L, "常见坑.md", 0.91), seg(null, 12L, 38L, "空.md", 0.5)));
        Map<String, Object> config = new HashMap<>();
        config.put("knowledgeBaseId", 7L);
        config.put("queryVar", "q");
        config.put("citationsVar", "citations");

        Map<String, Object> delta = executor.execute(node(config), ctx(Map.of("q", "x")));

        // outputVar 仍只含有效 content（null 段被跳过），行为不变
        assertEquals(List.of("段A"), out(delta, "retrieved"));
        // citationsVar 写一份带出处的段落，字段名逐字对齐 AiRagSegmentRespDTO；null-content 段同样跳过
        assertTrue(delta.containsKey("citations"));
        List<Map<String, Object>> cites = (List<Map<String, Object>>) delta.get("citations");
        assertEquals(1, cites.size());
        Map<String, Object> c = cites.get(0);
        assertEquals(11L, c.get("id"));
        assertEquals(37L, c.get("documentId"));
        assertEquals("常见坑.md", c.get("documentName"));
        assertEquals("段A", c.get("content"));
        assertEquals(0.91, c.get("score"));
    }

    @Test
    void execute_noCitationsVar_doesNotWriteCitationsKey() {
        when(aiRagApi.retrieve(any(), any(), any(), any())).thenReturn(List.of(seg("段A")));
        Map<String, Object> config = new HashMap<>();
        config.put("knowledgeBaseId", 7L);
        config.put("queryVar", "q");
        // 未配 citationsVar

        Map<String, Object> delta = executor.execute(node(config), ctx(Map.of("q", "x")));

        assertEquals(List.of("段A"), out(delta, "retrieved"));
        assertTrue(delta.size() == 1 && !delta.containsKey("citations"),
                "未配 citationsVar 时不应写额外键（保持精简 state）");
    }

}
