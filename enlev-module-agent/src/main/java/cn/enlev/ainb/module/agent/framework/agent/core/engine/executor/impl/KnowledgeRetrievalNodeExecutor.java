package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.StateTemplateResolver;
import cn.enlev.ainb.module.ai.api.rag.AiRagApi;
import cn.enlev.ainb.module.ai.api.rag.dto.AiRagSegmentRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;

/**
 * {@code knowledge_retrieval} 节点执行器：知识库检索（DSL §4.2）
 *
 * <h3>走 {@link AiRagApi#retrieve}，不自己碰 VectorStore</h3>
 * RAG 基建（知识库、向量存储、分段召回）ai 模块早已就绪，agent 里的 {@code DocSearchTool}
 * 就是复用它。本节点做的是同一件事，只是把「查询」从工具入参换成从流程状态取，
 * 把「结果」写进 outputVar 供下游（通常是 llm 节点）模板进 prompt。
 * 跨模块只走 api/ 层（架构红线），故注入 {@link AiRagApi} 而非内部 Service。
 *
 * <h3>query 的两种来源：queryVar 优先，其次 query 模板串</h3>
 * <ul>
 *   <li>{@code queryVar}：直接取某个状态变量的字符串值当查询（如把用户原话 {@code user_input} 丢进来）</li>
 *   <li>{@code query}：模板串，经 {@link StateTemplateResolver} 解析，支持 {@code {{$.a.b}}} 嵌套路径
 *       （如「关于 {{$.contract.subject}} 的验收标准」）</li>
 * </ul>
 * 两者都没给则查询为空串 —— 交由 RAG 侧决定行为，不在这里额外报错。
 *
 * <h3>outputVar 写的是 {@code List<String>}（各段 content），不是整个 DTO</h3>
 * 三个理由：① JSON 可序列化，checkpoint 安全（DSL §3.2 硬约束）；② 下游 llm 直接
 * {@code {{retrieved}}} 模板进 prompt 就是拼好的上下文，无需再解结构；③ 不把 documentId/score
 * 这些内部字段泄进流程状态。需要引用出处的场景（列文档名）留后续增量按需扩展。
 *
 * <h3>本节点不调 LLM，token 恒为 0</h3>
 * 检索是向量相似度匹配，不产生模型 usage。与 condition.rule 同理，根本不碰 metrics.usage，
 * 规避 SR-2 的 tokenUsage 粘滞 —— trace 如实记 0，而不是继承上一个 llm 节点的数字。
 *
 * <h3>错误处理：fail-close</h3>
 * {@code knowledgeBaseId} 缺失属 DSL 配置错误，直接抛 {@code WORKFLOW_DSL_INVALID}；
 * 库不存在/检索异常由 {@link AiRagApi} 抛出、经编译器 wrap 记 trace 后冒泡（与其他执行器一致）。
 * 空召回<b>不是</b>错误，写空列表。
 *
 * <h3>租户</h3>
 * 节点在订阅线程同步执行、租户 ThreadLocal 可见（P1 已验证），AiRagApi 走分租户的
 * AiKnowledgeSegmentService，无需额外传播。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class KnowledgeRetrievalNodeExecutor implements NodeExecutor {

    public static final String TYPE = "knowledge_retrieval";
    /** 未配 {@code outputVar} 时检索段落落进的 state 键；public 的理由见 {@code ToolNodeExecutor#DEFAULT_OUTPUT_VAR} */
    public static final String DEFAULT_OUTPUT_VAR = "retrieved";

    /**
     * 引用出处（citations）写入的 state 键的<b>约定值</b>（增量16C）
     *
     * <p>{@code citationsVar} 是 opt-in：仅当节点显式配了它才额外写一份带出处的段落。约定用 {@code citations}
     * 这个键，是因为聊天侧（{@code AgentAssistantServiceImpl} 的 workflow 桥接）DONE 时固定读 state 的
     * {@code citations} 键、把它渲染成聊天里的「参考资料」——与 slice-3c 的 {@code input}/{@code output}
     * 固定约定同哲学。作者把 {@code citationsVar} 配成别的名字，则仅供下游节点自用、不进聊天引用。
     */
    public static final String CITATIONS_VAR = "citations";

    @Resource
    private AiRagApi aiRagApi;

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
        Long knowledgeBaseId = node.configLong("knowledgeBaseId");
        if (knowledgeBaseId == null) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "knowledge_retrieval 节点(" + node.getId() + ") 缺少 knowledgeBaseId");
        }
        String outputVar = node.configStr("outputVar", DEFAULT_OUTPUT_VAR);

        // query：queryVar 优先，其次 query 模板串
        String queryVar = node.configStr("queryVar");
        String query;
        if (queryVar != null && !queryVar.isEmpty()) {
            query = ctx.getString(queryVar, "");
        } else {
            query = StateTemplateResolver.resolve(node.configStr("query", ""), ctx);
        }

        Integer topK = node.configInt("topK", null);
        Double similarityThreshold = node.configDouble("similarityThreshold");
        List<AiRagSegmentRespDTO> segments =
                aiRagApi.retrieve(knowledgeBaseId, query, topK, similarityThreshold);

        // 只取各段 content，写成 List<String>（JSON 可序列化、可直接模板进 prompt）
        List<String> contents = new ArrayList<>();
        // 引用出处（增量16C）：仅当配了 citationsVar 才写，字段名逐字对齐 AiRagSegmentRespDTO
        // （id/documentId/documentName/knowledgeId/content/score/documentUrl/documentTitle），
        // 故下游与聊天侧能直接反序列化复用。🔴 加这两个字段时两边要一起改：
        // 聊天侧的「参考资料」chip 要拿 documentUrl 渲染成可点链接，缺了它就退化成纯文本。
        String citationsVar = node.configStr("citationsVar");
        List<Map<String, Object>> citations = new ArrayList<>();
        boolean wantCitations = citationsVar != null && !citationsVar.isEmpty();
        if (segments != null) {
            for (AiRagSegmentRespDTO seg : segments) {
                if (seg == null || seg.getContent() == null) {
                    continue;
                }
                contents.add(seg.getContent());
                if (wantCitations) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", seg.getId());
                    c.put("documentId", seg.getDocumentId());
                    c.put("documentName", seg.getDocumentName());
                    c.put("knowledgeId", seg.getKnowledgeId());
                    c.put("content", seg.getContent());
                    c.put("score", seg.getScore());
                    c.put("documentUrl", seg.getDocumentUrl());
                    c.put("documentTitle", seg.getDocumentTitle());
                    citations.add(c);
                }
            }
        }
        log.info("[execute][knowledge_retrieval 节点({}) 执行({}) 知识库({}) 命中({})段 citations({})]",
                node.getId(), ctx.getExecutionId(), knowledgeBaseId, contents.size(), wantCitations);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(outputVar, contents);
        if (wantCitations) {
            result.put(citationsVar, citations);
        }
        return result;
    }

}
