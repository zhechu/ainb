package cn.enlev.ainb.module.infra.framework.billtemplate.core;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.enlev.ainb.module.infra.enums.billtemplate.BillDateFormats;
import cn.enlev.ainb.module.infra.enums.billtemplate.ResetCycleEnum;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 单据号生成器
 *
 * 纯逻辑，不依赖 Spring、不碰数据库、不碰 Redis —— 流水序号由调用方传进来。
 * 这样两种配置模式共用同一套算法，且全部分支都能用普通单元测试覆盖。
 *
 * <pre>
 * 号码 = 前缀 + 主体 + 后缀
 *
 * 主体按生成方式：
 *   TIME   ：日期段 + 左补零的流水号
 *   RANDOM ：指定位数的随机串
 *   UUID   ：大写 UUID
 * </pre>
 *
 * @author 走进新时代
 */
@Slf4j
public final class BillNumberGenerator {

    /**
     * 分组 key 的哈希长度
     *
     * 前缀可能很长、可能含冒号等会污染 Redis key 结构的字符，因此取哈希而不是原文。
     * 8 位十六进制 = 32 bit，对「一个表单里有多少个不同前缀」这个量级来说碰撞概率可忽略；
     * 即便碰撞，后果也只是两个前缀共用一个计数器（号码依然唯一，只是流水不连续）。
     */
    private static final int GROUP_KEY_HASH_LENGTH = 8;

    /**
     * 不分组时，group 段的占位符
     */
    public static final String NO_GROUP = "_";

    private BillNumberGenerator() {
    }

    /**
     * 生成单据号
     *
     * @param spec 生成规格
     * @param now  生成时刻。由调用方传入而非方法内部取 now()，
     *             是为了让「算 period 的时刻」与「格式化日期段的时刻」严格一致 ——
     *             否则跨零点的一次调用可能拿到昨天的 period 配今天的日期段
     * @param seq  流水序号，从 1 开始。非 TIME 方式传任意值（不会被使用）
     * @return 单据号
     */
    public static String generate(BillNumberSpec spec, LocalDateTime now, long seq) {
        StringBuilder sb = new StringBuilder();
        sb.append(StrUtil.nullToEmpty(spec.getPrefix()));
        sb.append(body(spec, now, seq));
        sb.append(StrUtil.nullToEmpty(spec.getSuffix()));
        return sb.toString();
    }

    /**
     * 号码主体
     */
    private static String body(BillNumberSpec spec, LocalDateTime now, long seq) {
        if (spec.getGenType() == null) {
            return "";
        }
        switch (spec.getGenType()) {
            case RANDOM:
                return random(spec);
            case UUID:
                // 保留连字符的大写 UUID（36 位）。
                // 注意它加上前后缀后很容易超过 varchar(64)，发布校验会给出长度提示
                return IdUtil.randomUUID().toUpperCase();
            case TIME:
            default:
                return datePart(spec, now) + sequencePart(spec, seq);
        }
    }

    /**
     * 日期段
     */
    private static String datePart(BillNumberSpec spec, LocalDateTime now) {
        if (!BillDateFormats.hasDate(spec.getDateFormat())) {
            return "";
        }
        return DateTimeFormatter.ofPattern(spec.getDateFormat()).format(now);
    }

    /**
     * 流水段
     *
     * 实际值 = 起始值 + (序号 - 1) × 步长。序号从 1 开始，因此起始值配 1 时首号就是 1。
     *
     * <p>位数溢出时【不截断】直接输出更长的号码 —— 截断会让 9999 与 19999 都变成 9999，
     * 必然产生重复号。这里只打 WARN 提示管理员调大位数。
     */
    private static String sequencePart(BillNumberSpec spec, long seq) {
        int start = spec.getStartNumber() != null ? spec.getStartNumber() : 1;
        int step = spec.getStep() != null && spec.getStep() > 0 ? spec.getStep() : 1;
        int digit = spec.getDigit() != null && spec.getDigit() > 0 ? spec.getDigit() : 1;

        long value = start + (seq - 1) * step;
        String text = String.valueOf(value);
        if (text.length() > digit) {
            log.warn("[sequencePart][单据规则({}) 流水值({}) 已超过配置位数({})，号码将变长，请调大流水位数]",
                    spec.getCounterScope(), value, digit);
            return text;
        }
        return StrUtil.padPre(text, digit, '0');
    }

    /**
     * 随机段
     */
    private static String random(BillNumberSpec spec) {
        int digit = spec.getRandomDigit() != null && spec.getRandomDigit() > 0 ? spec.getRandomDigit() : 16;
        // 1-纯数字，其余按「字母+数字」处理
        if (spec.getRandomType() == null || spec.getRandomType() == 1) {
            return RandomUtil.randomNumbers(digit);
        }
        return RandomUtil.randomStringUpper(digit);
    }

    /**
     * 计算流水计数的分组段
     *
     * 决定「哪些号码共用一个计数器」：
     * <ul>
     *   <li>不分组（模板模式默认）：所有号码共用一个计数器。前缀是固定字符串时，
     *       分组与否生成的号码完全一样，没必要多维护一个计数维度</li>
     *   <li>按前缀分组（内联模式默认）：前缀取自「客户编号」这类字段时，
     *       每个客户各自从 1 开始，而不是全局一个计数器</li>
     * </ul>
     *
     * @param spec 生成规格
     * @return 分组段
     */
    public static String groupKey(BillNumberSpec spec) {
        if (!spec.isGroupByPrefix()) {
            return NO_GROUP;
        }
        String prefix = StrUtil.nullToEmpty(spec.getPrefix());
        if (prefix.isEmpty()) {
            return NO_GROUP;
        }
        return DigestUtil.md5Hex(prefix).substring(0, GROUP_KEY_HASH_LENGTH);
    }

    /**
     * 试算范例
     *
     * 供后台配置页预览用，【不消耗流水】—— 固定按序号 1 计算。
     * 前端不自己算一遍，就是为了让预览与真实出号走同一段代码，避免两侧算法漂移。
     *
     * @param spec 生成规格
     * @return 范例号码
     */
    public static String example(BillNumberSpec spec) {
        return generate(spec, LocalDateTime.now(), 1L);
    }

    /**
     * 计算周期段，便于调用方与 {@link #generate} 使用同一时刻
     */
    public static String period(BillNumberSpec spec, LocalDateTime now) {
        ResetCycleEnum cycle = spec.resetCycle();
        return cycle.period(spec.getDateFormat(), now);
    }

}
