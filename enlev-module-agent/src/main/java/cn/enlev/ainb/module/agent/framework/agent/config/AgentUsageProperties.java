package cn.enlev.ainb.module.agent.framework.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI 助手的计量日志配置
 *
 * @author 走进新时代
 */
@ConfigurationProperties(prefix = "enlev.agent.usage")
@Validated
@Data
public class AgentUsageProperties {

    /**
     * 用户输入原文的保留天数（方案 §16 第 6 条，2026-09-23 定为 180 天）
     *
     * <h3>它只管 {@code input_text} 这一列</h3>
     * 到期后被清空的<b>只有用户提问原文</b>，行本身与全部计量字段
     * （token 数、金额、场景、时间）一个都不动 —— 那些是计费证据，
     * 删了账上就有空洞。两者的保留诉求正好相反，所以只能置空不能删行。
     *
     * <h3>为什么做成配置而不是常量</h3>
     * 这是个<b>合规口径</b>，不是技术参数。将来法务要求改成 90 天或 365 天时，
     * 改配置就能生效、且能按环境给不同值；写成常量就得改代码、走发布。
     *
     * <h3>0 表示不清理</h3>
     * 清理任务的判据是每行自己的 {@code retention_days}（写入时从这里取值快照）。
     * 配 0 的话新写入的记录永不清理 —— 这是 2026-09-23 之前的行为。
     *
     * <p>⚠️ 改这个值只影响<b>之后新写入</b>的记录。已有记录的保留期是写入时快照下来的，
     * 要一并调整得直接改库（见 {@code sql/mysql/xpy-2026-09-23-agent-usage-retention-job.sql}）。
     * 这是刻意的：保留期是「当时承诺了什么」，不该被后来的配置追溯改写。
     */
    private Integer inputTextRetentionDays = 180;

}
