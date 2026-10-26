package cn.enlev.ainb.module.agent.dal.dataobject.usage;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 助手计量日志 DO
 *
 * <h3>🔴 这张表的定位是「收费证据」，不是「运营看板的数据源」</h3>
 * 定位决定了三件事：
 * <ol>
 *   <li><b>数据必须完整</b> —— 每次 LLM 调用一行，包括失败与中断的。
 *       流式对话在 {@code doOnComplete}/{@code doOnError}/{@code doOnCancel}
 *       三处都要落库，否则中断的对话不计费、可被刷</li>
 *   <li><b>不可被用户抹掉</b> —— 用户删对话，收费证据不能跟着没。
 *       所以 {@link #conversationId}/{@link #messageId} 只是<b>弱引用</b>，
 *       不建外键、不级联删除；本模块也<b>不提供任何删除入口</b></li>
 *   <li><b>能还原到单次调用</b> —— 所以 {@link #inputText} 存快照，不靠 join 消息表。
 *       消息删了还要能出示「这次扣费对应的是哪句话」</li>
 * </ol>
 *
 * @author 走进新时代
 */
@TableName("agent_usage_log")
@KeySequence("agent_usage_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentUsageLogDO extends TenantBaseDO {

    private Long id;

    /**
     * 用户编号
     */
    private Long userId;

    /**
     * 会话编号
     *
     * ⚠️ 弱引用：会话被删除后本字段仍保留原值，不置空、不级联删除
     */
    private Long conversationId;

    /**
     * 消息编号，同样是弱引用
     */
    private Long messageId;

    private Long modelId;

    /**
     * 平台，如 TongYi
     */
    private String platform;

    /**
     * 模型标识，如 qwen3.7-plus
     */
    private String modelName;

    /**
     * 场景
     *
     * 枚举 {@link cn.enlev.ainb.module.agent.enums.AgentUsageSceneEnum}
     */
    private String scene;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    /**
     * token 来源：PROVIDER 厂商回传 / ESTIMATED 本地估算
     *
     * 并非所有厂商都在流式末块回传 usage。取不到时按 tokenizer 估算，
     * 而账单上要对估算值单独说明 —— 所以来源必须记下来，不能混为一谈。
     */
    private String tokenSource;

    /**
     * 调用当时的单价快照
     *
     * 存快照而不是查询时再算：单价会变，历史账单要能复算。
     */
    private String priceSnapshot;

    private BigDecimal cost;

    /**
     * 用户输入原文快照
     *
     * 🔴 独立成列，不与其它字段拼进同一个 JSON —— 将来要清理/脱敏/迁走只动这一列。
     * 这是零成本的后路，现在不留以后要改代码。
     *
     * <p>⚠️ 它意味着「租户管理员能看到本租户用户跟 AI 说过的每一句话」。
     * 访问策略已决定推迟（方案 §8.4），但**正式对外前要过一遍法务的告知同意义务**。
     */
    private String inputText;

    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private Integer durationMs;

    /**
     * 状态：SUCCESS / FAILURE / CANCELLED
     */
    private String status;

    private String errorMsg;

    /**
     * 保留天数，0 表示不清理
     *
     * 从第一天就带上，初期配 0。加字段容易，回填历史难。
     */
    private Integer retentionDays;

}
