package cn.enlev.ainb.module.ai.api.model.dto;

import lombok.Data;

/**
 * AI 对话响应 DTO
 *
 * @author 走进新时代
 */
@Data
public class AiChatRespDTO {

    /**
     * 回复内容
     */
    private String content;

    /**
     * 思维链内容，仅推理模型有
     */
    private String reasoningContent;

    /**
     * 实际使用的模型编号
     *
     * 故障切换后可能与请求的 modelId 不同，落计量日志（agent_usage_log.model_id）用，保证账单可追溯。
     */
    private Long modelId;

    /**
     * 实际使用的模型标识（如 qwen-max-latest），落计量日志用
     */
    private String model;

    /**
     * 平台（如 TongYi），落计量日志用
     */
    private String platform;

    /**
     * token 用量
     *
     * 🔴 <b>在 api 层就把它带出来，是刻意的。</b>
     *
     * <p>现状是全模块零统计 —— {@code ChatResponse} 的 usage 元数据直接被丢弃，
     * 消息表也没有 token 字段。若等到 P4 做用量看板时再补，
     * 前面几期的所有调用点都要重新翻一遍，而且期间的用量数据<b>永久缺失</b>，账单会有空洞。
     *
     * <p>⚠️ 可能为 null：并非所有厂商都在流式的末块回传 usage。
     * 取不到时调用方应按 tokenizer 估算，并把来源标记为 ESTIMATED，
     * 账单上对估算值单独说明。
     */
    private Usage usage;

    @Data
    public static class Usage {

        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;

        public static Usage of(Integer prompt, Integer completion, Integer total) {
            Usage usage = new Usage();
            usage.setPromptTokens(prompt);
            usage.setCompletionTokens(completion);
            usage.setTotalTokens(total);
            return usage;
        }
    }

}
