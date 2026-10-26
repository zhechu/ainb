package cn.enlev.ainb.module.infra.enums.billtemplate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ResetCycleEnum} 的单元测试
 *
 * 周期判定错了，流水号该归 1 的时候不归、不该归的时候归了，都会直接体现在单号上。
 * 这里验证判定完全由 period 段决定，不接触已生成号码的字符串形态 ——
 * 任何「按长度从号码里截取日期段再比对」的实现，都会在前后缀长度变化时截错位置。
 *
 * @author 走进新时代
 */
class ResetCycleEnumTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 14, 30, 45);

    // ==================== AUTO 解析 ====================

    @Test
    @DisplayName("AUTO 按日期格式的最小时间单位解析")
    void testResolveAuto() {
        assertEquals(ResetCycleEnum.DAY, ResetCycleEnum.AUTO.resolve(BillDateFormats.DAY));
        assertEquals(ResetCycleEnum.MONTH, ResetCycleEnum.AUTO.resolve(BillDateFormats.MONTH));
        assertEquals(ResetCycleEnum.YEAR, ResetCycleEnum.AUTO.resolve(BillDateFormats.YEAR));
        assertEquals(ResetCycleEnum.NONE, ResetCycleEnum.AUTO.resolve(BillDateFormats.NONE));
        assertEquals(ResetCycleEnum.NONE, ResetCycleEnum.AUTO.resolve(null));
    }

    @Test
    @DisplayName("⚠️ 秒级、毫秒级日期格式仍按天重置 —— 「流水号每秒归 1」没有业务含义")
    void testResolveAutoForFineGrainedFormats() {
        assertEquals(ResetCycleEnum.DAY, ResetCycleEnum.AUTO.resolve(BillDateFormats.HOUR));
        assertEquals(ResetCycleEnum.DAY, ResetCycleEnum.AUTO.resolve(BillDateFormats.MINUTE));
        assertEquals(ResetCycleEnum.DAY, ResetCycleEnum.AUTO.resolve(BillDateFormats.SECOND));
        assertEquals(ResetCycleEnum.DAY, ResetCycleEnum.AUTO.resolve(BillDateFormats.MILLIS));
    }

    @Test
    @DisplayName("显式指定的周期不受日期格式影响")
    void testExplicitCycleIgnoresDateFormat() {
        // 日期格式精确到天，但用户要求按年重置 —— 应当尊重用户的显式配置
        assertEquals(ResetCycleEnum.YEAR, ResetCycleEnum.YEAR.resolve(BillDateFormats.DAY));
        assertEquals(ResetCycleEnum.NONE, ResetCycleEnum.NONE.resolve(BillDateFormats.DAY));
    }

    // ==================== period 段 ====================

    @Test
    @DisplayName("period 段按周期粒度格式化")
    void testPeriod() {
        assertEquals("20260906", ResetCycleEnum.AUTO.period(BillDateFormats.DAY, NOW));
        assertEquals("202609", ResetCycleEnum.AUTO.period(BillDateFormats.MONTH, NOW));
        assertEquals("2026", ResetCycleEnum.AUTO.period(BillDateFormats.YEAR, NOW));
    }

    @Test
    @DisplayName("不重置时 period 为固定占位符")
    void testPeriodWhenNoReset() {
        assertEquals(ResetCycleEnum.NO_PERIOD, ResetCycleEnum.NONE.period(BillDateFormats.DAY, NOW));
        assertEquals(ResetCycleEnum.NO_PERIOD, ResetCycleEnum.AUTO.period(BillDateFormats.NONE, NOW));
    }

    @Test
    @DisplayName("跨周期时 period 段改变，从而换到新的计数器")
    void testPeriodChangesAcrossCycle() {
        LocalDateTime lastSecondOfDay = LocalDateTime.of(2026, 9, 6, 23, 59, 59);
        LocalDateTime firstSecondOfNextDay = LocalDateTime.of(2026, 9, 7, 0, 0, 0);

        assertNotEquals(ResetCycleEnum.DAY.period(BillDateFormats.DAY, lastSecondOfDay),
                ResetCycleEnum.DAY.period(BillDateFormats.DAY, firstSecondOfNextDay));
        // 同一天内的不同时刻必须落在同一个计数器上
        assertEquals(ResetCycleEnum.DAY.period(BillDateFormats.DAY, NOW),
                ResetCycleEnum.DAY.period(BillDateFormats.DAY, lastSecondOfDay));
    }

    @Test
    @DisplayName("按月重置时，月末与次月初分属不同周期")
    void testPeriodAcrossMonth() {
        assertNotEquals(
                ResetCycleEnum.MONTH.period(BillDateFormats.MONTH, LocalDateTime.of(2026, 9, 30, 23, 0)),
                ResetCycleEnum.MONTH.period(BillDateFormats.MONTH, LocalDateTime.of(2026, 10, 1, 1, 0)));
    }

    // ==================== TTL ====================

    @Test
    @DisplayName("TTL 长于对应周期，避免临界时刻把计数器提前清掉")
    void testTtl() {
        assertTrue(ResetCycleEnum.AUTO.ttl(BillDateFormats.DAY).compareTo(Duration.ofDays(1)) > 0);
        assertTrue(ResetCycleEnum.AUTO.ttl(BillDateFormats.MONTH).compareTo(Duration.ofDays(31)) > 0);
        assertTrue(ResetCycleEnum.AUTO.ttl(BillDateFormats.YEAR).compareTo(Duration.ofDays(366)) > 0);
    }

    @Test
    @DisplayName("不重置时 TTL 为 null，计数器永不过期")
    void testTtlWhenNoReset() {
        assertNull(ResetCycleEnum.NONE.ttl(BillDateFormats.DAY));
        assertNull(ResetCycleEnum.AUTO.ttl(BillDateFormats.NONE));
    }

    // ==================== codeOf ====================

    @Test
    @DisplayName("codeOf 大小写不敏感，非法值与空值回退到 AUTO")
    void testCodeOf() {
        assertEquals(ResetCycleEnum.DAY, ResetCycleEnum.codeOf("DAY"));
        assertEquals(ResetCycleEnum.DAY, ResetCycleEnum.codeOf("day"));
        // 历史配置可能没有这个字段，脏数据不该让出号直接崩掉
        assertEquals(ResetCycleEnum.AUTO, ResetCycleEnum.codeOf(null));
        assertEquals(ResetCycleEnum.AUTO, ResetCycleEnum.codeOf(""));
        assertEquals(ResetCycleEnum.AUTO, ResetCycleEnum.codeOf("WEEKLY"));
    }

}
