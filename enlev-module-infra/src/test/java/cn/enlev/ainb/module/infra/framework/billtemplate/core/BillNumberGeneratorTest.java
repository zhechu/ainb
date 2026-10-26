package cn.enlev.ainb.module.infra.framework.billtemplate.core;

import cn.enlev.ainb.module.infra.enums.billtemplate.BillDateFormats;
import cn.enlev.ainb.module.infra.enums.billtemplate.BillGenTypeEnum;
import cn.enlev.ainb.module.infra.enums.billtemplate.ResetCycleEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BillNumberGenerator} 的单元测试
 *
 * 拼号算法是整个功能的地基：算错了，错误的单号会直接落进业务数据，事后无法追回。
 * 这里重点覆盖三处容易出错的地方 —— 位数溢出、起始值与步长的换算，
 * 以及「按前缀分组」这个决定流水计数维度的开关。
 *
 * @author 走进新时代
 */
class BillNumberGeneratorTest {

    /**
     * 固定时刻，避免测试在跨天时刻偶发失败
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 14, 30, 45);

    private static BillNumberSpec.BillNumberSpecBuilder timeSpec() {
        return BillNumberSpec.builder()
                .counterScope("purchase_order")
                .genType(BillGenTypeEnum.TIME)
                .prefix("CG")
                .suffix("")
                .dateFormat(BillDateFormats.DAY)
                .digit(4)
                .startNumber(1)
                .step(1)
                .resetCycle(ResetCycleEnum.AUTO);
    }

    // ==================== 时间流水 ====================

    @Test
    @DisplayName("前缀 + 日期 + 补零流水 + 后缀")
    void testGenerateTime() {
        BillNumberSpec spec = timeSpec().suffix("-A").build();

        assertEquals("CG202609060001-A", BillNumberGenerator.generate(spec, NOW, 1L));
        assertEquals("CG202609060012-A", BillNumberGenerator.generate(spec, NOW, 12L));
    }

    @Test
    @DisplayName("日期格式为 NONE 时不输出日期段")
    void testGenerateWithoutDate() {
        BillNumberSpec spec = timeSpec().dateFormat(BillDateFormats.NONE).build();

        assertEquals("CG0001", BillNumberGenerator.generate(spec, NOW, 1L));
    }

    @Test
    @DisplayName("各粒度日期格式都按 Java pattern 输出")
    void testDateFormats() {
        assertEquals("CG20260001",
                BillNumberGenerator.generate(timeSpec().dateFormat(BillDateFormats.YEAR).build(), NOW, 1L));
        assertEquals("CG2026090001",
                BillNumberGenerator.generate(timeSpec().dateFormat(BillDateFormats.MONTH).build(), NOW, 1L));
        assertEquals("CG20260906143045" + "0001",
                BillNumberGenerator.generate(timeSpec().dateFormat(BillDateFormats.SECOND).build(), NOW, 1L));
    }

    @Test
    @DisplayName("起始值参与换算：起始 100、序号 1 时首号为 0100")
    void testStartNumber() {
        BillNumberSpec spec = timeSpec().startNumber(100).build();

        assertEquals("CG202609060100", BillNumberGenerator.generate(spec, NOW, 1L));
        assertEquals("CG202609060101", BillNumberGenerator.generate(spec, NOW, 2L));
    }

    @Test
    @DisplayName("步长参与换算：步长 10、起始 1 时依次为 0001、0011")
    void testStep() {
        BillNumberSpec spec = timeSpec().step(10).build();

        assertEquals("CG202609060001", BillNumberGenerator.generate(spec, NOW, 1L));
        assertEquals("CG202609060011", BillNumberGenerator.generate(spec, NOW, 2L));
    }

    @Test
    @DisplayName("⚠️ 位数溢出时不截断，输出更长的号码 —— 截断会让 9999 与 19999 撞号")
    void testDigitOverflowDoesNotTruncate() {
        BillNumberSpec spec = timeSpec().digit(4).build();

        assertEquals("CG202609069999", BillNumberGenerator.generate(spec, NOW, 9999L));
        // 第 10000 号：位数不够了，但绝不能截断成 0000 或 9999
        assertEquals("CG2026090610000", BillNumberGenerator.generate(spec, NOW, 10000L));
    }

    @Test
    @DisplayName("同一规格下，不同序号生成的号码互不相同")
    void testUniquenessAcrossSequences() {
        BillNumberSpec spec = timeSpec().build();
        Set<String> numbers = new HashSet<>();
        for (long seq = 1; seq <= 500; seq++) {
            assertTrue(numbers.add(BillNumberGenerator.generate(spec, NOW, seq)),
                    "序号 " + seq + " 生成了重复号码");
        }
    }

    // ==================== 随机数与 UUID ====================

    @Test
    @DisplayName("随机数：纯数字，长度等于配置位数")
    void testRandomNumeric() {
        BillNumberSpec spec = BillNumberSpec.builder()
                .counterScope("r").genType(BillGenTypeEnum.RANDOM)
                .prefix("R").suffix("").randomDigit(16).randomType(1).build();

        String no = BillNumberGenerator.generate(spec, NOW, 1L);
        assertTrue(no.startsWith("R"));
        assertEquals(1 + 16, no.length());
        assertTrue(no.substring(1).matches("\\d{16}"), "应为 16 位纯数字，实际：" + no);
    }

    @Test
    @DisplayName("随机数：字母+数字，长度等于配置位数")
    void testRandomAlphanumeric() {
        BillNumberSpec spec = BillNumberSpec.builder()
                .counterScope("r").genType(BillGenTypeEnum.RANDOM)
                .prefix("").suffix("").randomDigit(32).randomType(2).build();

        String no = BillNumberGenerator.generate(spec, NOW, 1L);
        assertEquals(32, no.length());
        assertTrue(no.matches("[0-9A-Z]{32}"), "应为 32 位大写字母与数字，实际：" + no);
    }

    @Test
    @DisplayName("UUID：大写、含连字符")
    void testUuid() {
        BillNumberSpec spec = BillNumberSpec.builder()
                .counterScope("u").genType(BillGenTypeEnum.UUID)
                .prefix("").suffix("").build();

        String no = BillNumberGenerator.generate(spec, NOW, 1L);
        assertEquals(36, no.length());
        assertEquals(no.toUpperCase(), no);
        assertTrue(no.matches("[0-9A-F-]{36}"), "应为大写 UUID，实际：" + no);
    }

    @Test
    @DisplayName("随机数与 UUID 不消耗流水计数")
    void testNonSequentialTypesNeedNoSequence() {
        assertFalse(BillNumberSpec.builder().genType(BillGenTypeEnum.RANDOM).build().needSequence());
        assertFalse(BillNumberSpec.builder().genType(BillGenTypeEnum.UUID).build().needSequence());
        assertTrue(BillNumberSpec.builder().genType(BillGenTypeEnum.TIME).build().needSequence());
    }

    // ==================== 分组 key ====================

    @Test
    @DisplayName("不分组时，任何前缀都归到同一个计数器")
    void testGroupKeyWithoutGrouping() {
        BillNumberSpec a = timeSpec().prefix("A").groupByPrefix(false).build();
        BillNumberSpec b = timeSpec().prefix("B").groupByPrefix(false).build();

        assertEquals(BillNumberGenerator.NO_GROUP, BillNumberGenerator.groupKey(a));
        assertEquals(BillNumberGenerator.groupKey(a), BillNumberGenerator.groupKey(b));
    }

    @Test
    @DisplayName("⚠️ 按前缀分组时，不同前缀落到不同计数器 —— 内联模式「每个客户各自从 1 开始」靠它实现")
    void testGroupKeyWithGrouping() {
        BillNumberSpec a = timeSpec().prefix("CUST-A-").groupByPrefix(true).build();
        BillNumberSpec b = timeSpec().prefix("CUST-B-").groupByPrefix(true).build();

        assertNotEquals(BillNumberGenerator.groupKey(a), BillNumberGenerator.groupKey(b));
        // 同一前缀必须稳定映射到同一个 key，否则每次请求都换计数器
        assertEquals(BillNumberGenerator.groupKey(a),
                BillNumberGenerator.groupKey(timeSpec().prefix("CUST-A-").groupByPrefix(true).build()));
    }

    @Test
    @DisplayName("按前缀分组但前缀为空时，退回不分组")
    void testGroupKeyWithBlankPrefix() {
        BillNumberSpec spec = timeSpec().prefix("").groupByPrefix(true).build();

        assertEquals(BillNumberGenerator.NO_GROUP, BillNumberGenerator.groupKey(spec));
    }

    @Test
    @DisplayName("分组 key 不含冒号，不会破坏 Redis key 的分段结构")
    void testGroupKeyIsRedisSafe() {
        BillNumberSpec spec = timeSpec().prefix("a:b:c").groupByPrefix(true).build();

        String groupKey = BillNumberGenerator.groupKey(spec);
        assertFalse(groupKey.contains(":"));
        assertTrue(groupKey.matches("[0-9a-f]{8}"));
    }

}
