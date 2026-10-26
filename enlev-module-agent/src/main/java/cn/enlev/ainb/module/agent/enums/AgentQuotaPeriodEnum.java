package cn.enlev.ainb.module.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * 配额周期
 *
 * <h3>为什么是自然周期而不是滚动窗口</h3>
 * 滚动窗口（「过去 30 天」）在数学上更公平，但用户<b>算不清自己还剩多少</b> ——
 * 额度会随着时间推移一点点回来，没法回答「我这个月还能用几次」。
 * 而配额的首要作用是让人能预期，其次才是控制成本。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum AgentQuotaPeriodEnum {

    DAY("DAY", "天"),
    MONTH("MONTH", "月"),
    ;

    private final String period;
    private final String name;

    public static AgentQuotaPeriodEnum parse(String period) {
        return Arrays.stream(values())
                .filter(item -> item.getPeriod().equalsIgnoreCase(period))
                .findFirst()
                // 认不出来时按月 —— 比按天宽松。
                // 配额配错了，宁可少拦一些，也不要把人挡在门外还查不出原因
                .orElse(MONTH);
    }

    /**
     * 本周期的起点
     */
    public LocalDateTime periodStart(LocalDateTime now) {
        return this == DAY
                ? now.toLocalDate().atStartOfDay()
                : now.toLocalDate().withDayOfMonth(1).atStartOfDay();
    }

    /**
     * 本周期的标识，用于预警去重
     */
    public String periodKey(LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        return this == DAY
                ? date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                : date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

}
