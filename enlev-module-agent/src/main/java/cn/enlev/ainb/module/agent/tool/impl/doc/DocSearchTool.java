package cn.enlev.ainb.module.agent.tool.impl.doc;

import cn.enlev.ainb.module.agent.framework.agent.config.AgentDocProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.ai.api.rag.AiRagApi;
import cn.enlev.ainb.module.ai.api.rag.dto.AiRagSegmentRespDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：检索产品手册
 *
 * 这是 AI 回答「怎么配」「为什么不生效」这类问题的<b>事实来源</b>。
 *
 * <h3>🔴 为什么一定要带文档出处</h3>
 * 模型凭通用知识也能对这类问题说出一堆听着合理的话 ——
 * 实测问「联动为什么不生效」，没接 RAG 时它答的是「条件未满足/字段标识错误/规则冲突」，
 * 通用、正确、但<b>对这个平台毫无用处</b>。
 *
 * <p>而手册里写的是真正的原因，比如「条件成立但只配了 then 没配 otherwise，
 * 字段会停在原状态」——这种只有读了文档才知道。
 * 所以返回值里<b>每段都带 documentName</b>，回答时必须引用，
 * 让用户能自己去核对。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "doc_search",
        displayName = "检索平台使用手册",
        groupName = "平台手册",
        description = "检索平台产品手册（表单设计、权限体系）。回答「怎么配」「为什么不生效」类问题前必须先调它，"
                + "并在回答中引用检索到的文档出处",
        permission = "agent:assistant:use",
        risk = RiskLevel.READ_ONLY)
public class DocSearchTool implements AgentToolFunction {

    /**
     * 参数名：要检索的问题
     */
    public static final String PARAM_QUERY = "query";

    /**
     * 参数名：召回条数，可选
     */
    public static final String PARAM_TOP_K = "topK";

    @Resource
    private AiRagApi ragApi;
    @Resource
    private AgentDocProperties docProperties;

    /**
     * 检索结果
     *
     * <h3>为什么返回强类型而不是 {@code Map}</h3>
     * 这个返回值有<b>两个</b>消费方，需求不同：
     * <ul>
     *   <li><b>LLM</b>：要的是可读的 JSON，Jackson 序列化这个 record 的结果与原来的 Map 一致</li>
     *   <li><b>助手服务</b>：要拿 {@code segments} 去落 {@code reference_json}（多轮回灌的前提），
     *       还要按它拼 prompt。若返回 Map，调用方就得把已经结构化的东西再解析回来</li>
     * </ul>
     * 更要紧的是：返回 Map 会诱使调用方<b>绕开</b>{@code AgentToolExecutor} 直接调 {@code ragApi}
     * （反正类型对得上），而绕开执行器就等于绕开权限网关 —— 那是无声的越权。
     * 强类型让「走执行器」成为最省事的路径。
     *
     * @param segments 召回的片段
     * @param citationRequirement 给模型的引用要求，随结果一起下发而不是只写在 system prompt 里 ——
     *                            贴着数据的约束比开头那段更难被长上下文冲淡
     */
    public record DocSearchResult(
            String query,
            int total,
            List<AiRagSegmentRespDTO> segments,
            @JsonProperty("引用要求") String citationRequirement) {
    }

    private static final String CITATION_REQUIREMENT =
            "回答时必须说明依据来自手册的哪一节，不要凭通用知识作答。"
                    + "若检索结果与问题无关，如实说明手册里没有覆盖，不要编造。";

    @Override
    public DocSearchResult execute(Map<String, Object> params) {
        Object query = params.get(PARAM_QUERY);
        if (query == null || query.toString().isBlank()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "doc_search", "缺少 query（要检索的问题）");
        }
        Long knowledgeId = ragApi.findKnowledgeIdByName(docProperties.getKnowledgeName());
        if (knowledgeId == null) {
            // 说清楚是「还没灌库」而不是「没有相关内容」—— 这两件事的处理方式完全不同
            throw exception(AGENT_TOOL_PARAM_INVALID, "doc_search",
                    "内置知识库「" + docProperties.getKnowledgeName() + "」不存在，说明文档还没灌库，"
                            + "请先执行文档同步");
        }
        Integer topK = params.get(PARAM_TOP_K) == null ? null
                : Integer.valueOf(params.get(PARAM_TOP_K).toString());
        List<AiRagSegmentRespDTO> segments = ragApi.retrieve(
                knowledgeId, query.toString(), topK, null);
        return new DocSearchResult(query.toString(), segments.size(), segments,
                CITATION_REQUIREMENT);
    }


    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("segments");
    }

    @Override
    public String paramsSchema() {
        return "query（必填，字符串）：要检索的问题；topK（可选，数字）：召回条数，默认 5";
    }

}
