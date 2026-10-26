package cn.enlev.ainb.module.agent.controller.admin.diagnostic;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentDocProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentPromptBuilder;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import cn.enlev.ainb.module.ai.api.rag.AiRagApi;
import cn.enlev.ainb.module.ai.api.rag.dto.AiRagSegmentRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * 带文档依据的问答诊断
 *
 * 验证 P1 的产出判据：<b>能正确回答「为什么我配的联动不生效」并给出文档依据</b>。
 *
 * <p>这是「检索 → 拼 prompt → 作答」的最小闭环，还不是完整的 Agent 循环
 * （没有多轮、没有工具选择、没有会话），但足以验证 RAG 这条链路通不通。
 *
 * <p>🔴 prompt 装配<b>一律走 {@link AgentPromptBuilder}</b>，本类不留自己的拷贝 ——
 * 见 {@code ragChat} 里那段的注释。诊断面也要吃同一套围栏，
 * 否则它就是一条「与主链路检索同一批手册、却不设防」的旁路。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - AI 助手问答诊断")
@RestController
@RequestMapping("/agent/diagnostic")
@Validated
public class AgentRagChatDiagnosticController {

    @Resource
    private AiRagApi ragApi;
    @Resource
    private AiModelApi aiModelApi;
    @Resource
    private AgentDocProperties docProperties;
    @Resource
    private AgentPromptBuilder promptBuilder;
    @Resource
    private cn.enlev.ainb.module.agent.service.usage.AgentUsageLogService usageLogService;

    @GetMapping("/rag-chat")
    @Operation(summary = "带文档依据的问答：先检索手册，再让模型基于检索结果作答")
    @Parameter(name = "q", description = "问题", required = true)
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<Map<String, Object>> ragChat(@RequestParam("q") String q) {
        java.time.LocalDateTime begin = java.time.LocalDateTime.now();
        // 1. 检索
        Long knowledgeId = ragApi.findKnowledgeIdByName(docProperties.getKnowledgeName());
        List<AiRagSegmentRespDTO> segments = knowledgeId == null
                ? List.of() : ragApi.retrieve(knowledgeId, q, null, null);

        // 2. 拼 prompt —— 🔴 复用 AgentPromptBuilder，本类不再自己拼一份
        //    B2-3 之前这里有一份与 AgentPromptBuilder#buildUserContent 逐字相同的拷贝
        //    （同款「手册片段：」+「【片段N】」+「问题：」，同样对片段内容原样 append）。
        //    两份拷贝的坏处不是「多几行」，是<b>加固只加到一份上</b>：
        //    本轮给 AgentPromptBuilder 接了 PromptDataFence，若这里还留着自己那份，
        //    它就成了一条绕过围栏的旁路 —— 而这条旁路能检索到的手册正文与主链路完全相同。
        //    系统提示词也跟着收敛到 BASE_PROMPT（措辞与本类原先那份仅首句不同，约束部分逐字一致）。
        //    tokenBudget 传 0：历史为空，预算根本不参与计算（build 里空历史直接返回空列表）。
        AiChatReqDTO reqDTO = promptBuilder.build(null, true, List.of(), q, segments, 0, false);

        AiChatRespDTO resp;
        try {
            resp = aiModelApi.chat(reqDTO);
        } catch (Exception ex) {
            // 🔴 失败也要计量：只记成功会让「调用失败但已消耗 token」查不到账，
            // 也会让中断的流式对话不计费 —— 那是可以被刷的
            usageLogService.recordFailure(
                    cn.enlev.ainb.module.agent.enums.AgentUsageSceneEnum.CHAT,
                    q, begin, ex.getMessage());
            throw ex;
        }
        usageLogService.recordSuccess(
                cn.enlev.ainb.module.agent.enums.AgentUsageSceneEnum.CHAT, resp, q, begin);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", q);
        result.put("knowledgeId", knowledgeId);
        result.put("retrievedSegments", segments.size());
        result.put("answer", resp.getContent());
        result.put("usage", resp.getUsage());
        return success(result);
    }

    @GetMapping("/usage-logs")
    @Operation(summary = "查看最近的计量日志")
    @PreAuthorize("@ss.hasPermission('agent:usage:query')")
    public CommonResult<List<cn.enlev.ainb.module.agent.dal.dataobject.usage.AgentUsageLogDO>> usageLogs() {
        return success(usageLogService.getRecentLogs(20));
    }

}
