package cn.enlev.ainb.module.bpm.framework.bpm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 流程参与表归档的配置（S9 / v9 §8.3）
 *
 * @author 走进新时代
 */
@ConfigurationProperties(prefix = "enlev.bpm.participant-archive")
@Component
@Data
public class BpmParticipantArchiveProperties {

    /**
     * 是否启用归档
     *
     * ⚠️ 默认<b>关闭</b>。归档会让用户看不到保留期之外的老单据
     * （派生可见性是从参与表反推的），这种动作不该因为升级了一版就自动开始。
     *
     * 打开之前先看一眼 {@code bpm_instance_participant} 有多少行、
     * 以及业务上能接受多长的保留期
     */
    private Boolean enabled = false;

    /**
     * 保留天数：{@code end_time} 早于「今天 - 保留天数」的已办结记录会被归档
     *
     * 默认 730 天（两年）。v9 §8.3 算的是「365 万行/年」，
     * 两年就是 730 万行 —— 这个量级还撑得住，再往上就该搬了。
     *
     * ⚠️ 单行的 {@code retain_until} 优先于本配置。
     * 合规要求「这类单据保留 10 年」时写那一列，
     * 而不是把这里调到 3650 —— 那会让<b>所有</b>单据都留 10 年，等于没归档
     */
    private Integer retainDays = 730;

    /**
     * 每批搬多少行
     *
     * 一次搬几百万行会把 undo 撑爆、把主表锁很久。
     * 分批之后即使中途失败，已搬走的那些也是完整的
     */
    private Integer batchSize = 5000;

    /**
     * 单次运行最多搬多少行
     *
     * 这只是「别把一晚上都占住」，不是「只归档这么多」—— 剩下的下次接着搬。
     * 长期到顶说明保留期内的增量大于归档速度，那时要调大它（会有 WARN 日志）
     */
    private Integer maxRowsPerRun = 500_000;

}
