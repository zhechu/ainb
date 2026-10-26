package cn.enlev.ainb.module.agent.service.usage;

import cn.enlev.ainb.module.agent.enums.AgentUsageSceneEnum;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计量日志 Service
 *
 * <h3>为什么记录方法不抛异常</h3>
 * 计量失败<b>绝不能让业务调用失败</b>：用户问了一句话、模型也答了，
 * 结果因为写日志出错而收到「系统异常」—— 那是本末倒置。
 *
 * <p>但也不能静默吞掉：写不进去意味着<b>账单会有空洞</b>，
 * 所以失败时打 ERROR 级日志，让它在监控里可见。
 *
 * @author 走进新时代
 */
public interface AgentUsageLogService {

    /**
     * 记录一次调用
     *
     * @param record 记录内容
     */
    void record(UsageRecord record);

    /**
     * 从模型响应构造记录（成功路径的便捷入口）
     *
     * @param scene     场景
     * @param resp      模型响应
     * @param inputText 用户输入原文
     * @param beginTime 调用开始时间
     */
    void recordSuccess(AgentUsageSceneEnum scene, AiChatRespDTO resp,
                       String inputText, LocalDateTime beginTime);

    /**
     * 记录一次失败的调用
     *
     * 🔴 失败也要记：否则「调用失败但已经消耗了 token」的情况就查不到账了。
     * 更重要的是，只记成功会让<b>中断的流式对话不计费</b>，那是可以被刷的。
     */
    void recordFailure(AgentUsageSceneEnum scene, String inputText,
                       LocalDateTime beginTime, String errorMsg);

    /**
     * 查最近的计量记录（本租户）
     *
     * @param limit 条数
     * @return 记录列表
     */
    java.util.List<cn.enlev.ainb.module.agent.dal.dataobject.usage.AgentUsageLogDO>
            getRecentLogs(int limit);

    /**
     * 按维度汇总用量（看板用）
     *
     * <h3>⚠️ 这个方法读的是「收费证据」表</h3>
     * {@code agent_usage_log} 的定位是对租户出示收费证据，不是运营看板的数据源。
     * 两者的要求不同：看板允许近似、允许采样，账单不允许。
     * 现在复用同一张表是因为数据量还小；<b>将来要加缓存或预聚合，只能加在这一层，
     * 不能动那张表</b> —— 一旦为了看板去改它的写入口径，账单就不可复算了。
     *
     * @param dimension 分组维度：USER / SCENE / MODEL / DAY
     * @param begin     起始时间，可为 null
     * @param end       结束时间，可为 null
     * @param userId    限定用户，可为 null
     * @return 汇总行，按 token 降序
     */
    java.util.List<UsageGroupBO> summarize(String dimension,
                                           java.time.LocalDateTime begin,
                                           java.time.LocalDateTime end,
                                           Long userId);

    /**
     * 分组汇总的一行
     */
    @Data
    class UsageGroupBO {

        /**
         * 分组键。按维度不同，可能是用户编号、场景名、模型名或日期
         */
        private String groupKey;
        /**
         * 分组键的可读名称，如用户昵称。取不到时与 groupKey 相同
         */
        private String groupLabel;
        private Long callCount;
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        private java.math.BigDecimal totalCost;

    }

    /**
     * 一次调用的计量内容
     */
    @Data
    @Builder
    class UsageRecord {

        private Long userId;
        private Long tenantId;
        private Long conversationId;
        private Long messageId;

        private Long modelId;
        private String platform;
        private String modelName;

        private AgentUsageSceneEnum scene;

        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
        /** PROVIDER 厂商回传 / ESTIMATED 本地估算 */
        private String tokenSource;

        private String inputText;

        private LocalDateTime beginTime;
        private LocalDateTime endTime;
        /** SUCCESS / FAILURE / CANCELLED */
        private String status;
        private String errorMsg;
    }

    /**
     * 清掉已过保留期的用户输入原文（方案 §16 第 6 条）
     *
     * ⚠️ 只清 {@code input_text}，行与计量字段一个都不动 —— 账不能有空洞。
     * 判据是每行自己的 {@code retention_days}，为 0（当前默认）表示不清理。
     *
     * @param limit 单次处理的行数上限
     * @return 实际清理的行数
     */
    int clearExpiredInputText(int limit);

}
