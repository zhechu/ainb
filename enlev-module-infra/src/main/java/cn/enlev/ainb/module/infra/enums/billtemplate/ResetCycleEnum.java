package cn.enlev.ainb.module.infra.enums.billtemplate;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * 流水号的重置周期枚举
 *
 * 周期决定「流水号什么时候归 1」。本实现把周期编码进 Redis 计数 key 的一个段里
 * （见 {@code BillTemplateRedisDAO}），周期切换时 key 自然变成新的，计数天然从 1 开始，
 * 既不需要定时任务去重置，也不需要回头解析已生成的号码。
 *
 * <p>后一点尤其重要：任何「从上次输出的号码里按长度截取日期段再比对」的做法，
 * 都会在前后缀长度变化时截错位置，而这类配置变更恰恰是随时可能发生的。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum ResetCycleEnum {

    /**
     * 跟随日期格式
     *
     * 默认值。日期格式含「日」就按天重置，含「月」就按月，以此类推 ——
     * 这是绝大多数场景想要的行为，不必让用户配两遍。
     */
    AUTO("AUTO", "跟随日期格式", null, null),

    /**
     * 不重置
     *
     * 流水号一直累加。适合「客户编号」这类不希望归零的场景。
     */
    NONE("NONE", "不重置", null, null),

    DAY("DAY", "按天重置", "yyyyMMdd", Duration.ofDays(2)),

    MONTH("MONTH", "按月重置", "yyyyMM", Duration.ofDays(40)),

    YEAR("YEAR", "按年重置", "yyyy", Duration.ofDays(400));

    /**
     * 周期不重置时，period 段的占位符
     *
     * 用固定串而非空串，避免 key 里出现连续两个冒号导致可读性变差。
     */
    public static final String NO_PERIOD = "_";

    /**
     * 周期代码
     */
    private final String code;
    /**
     * 描述
     */
    private final String name;
    /**
     * 该周期对应的 period 段格式。{@link #AUTO} 与 {@link #NONE} 为 null
     */
    private final String periodPattern;
    /**
     * Redis 计数 key 的存活时长，必须长于一个周期
     *
     * 取「周期长度 + 富余」：按天给 2 天，按月给 40 天，按年给 400 天。
     * 富余是为了避免临界时刻（比如 23:59:59 生成、00:00:00 才写入）把 key 提前清掉。
     * {@link #NONE} 为 null，表示永不过期。
     */
    private final Duration ttl;

    public static ResetCycleEnum codeOf(String code) {
        if (StrUtil.isBlank(code)) {
            return AUTO;
        }
        return Arrays.stream(values())
                .filter(item -> item.getCode().equalsIgnoreCase(code))
                .findFirst().orElse(AUTO);
    }

    /**
     * 把 {@link #AUTO} 解析成具体周期
     *
     * 解析依据是日期格式里出现的最小时间单位：
     * <ul>
     *   <li>含 {@code dd} → 按天。注意 {@code yyyyMMddHHmmss} 也归为按天 ——
     *       日期段虽然每秒都在变，但「流水号每秒归 1」没有任何业务含义，
     *       那样每张单子的流水都会是 0001，等于没有流水。</li>
     *   <li>含 {@code MM} → 按月</li>
     *   <li>含 {@code yyyy} → 按年</li>
     *   <li>都不含（日期格式为 NONE 或空） → 不重置</li>
     * </ul>
     *
     * @param dateFormat 日期格式
     * @return 具体周期，绝不返回 {@link #AUTO}
     */
    public ResetCycleEnum resolve(String dateFormat) {
        if (this != AUTO) {
            return this;
        }
        if (StrUtil.isBlank(dateFormat) || BillDateFormats.NONE.equalsIgnoreCase(dateFormat)) {
            return NONE;
        }
        if (dateFormat.contains("dd")) {
            return DAY;
        }
        if (dateFormat.contains("MM")) {
            return MONTH;
        }
        if (dateFormat.contains("yyyy")) {
            return YEAR;
        }
        return NONE;
    }

    /**
     * 计算当前时刻的 period 段
     *
     * @param dateFormat 日期格式，用于解析 {@link #AUTO}
     * @param now        当前时刻
     * @return period 段，不重置时返回 {@link #NO_PERIOD}
     */
    public String period(String dateFormat, LocalDateTime now) {
        ResetCycleEnum resolved = resolve(dateFormat);
        if (resolved.getPeriodPattern() == null) {
            return NO_PERIOD;
        }
        return DateTimeFormatter.ofPattern(resolved.getPeriodPattern()).format(now);
    }

    /**
     * 计算 Redis 计数 key 的存活时长
     *
     * @param dateFormat 日期格式，用于解析 {@link #AUTO}
     * @return 存活时长，null 表示永不过期
     */
    public Duration ttl(String dateFormat) {
        return resolve(dateFormat).getTtl();
    }

}
