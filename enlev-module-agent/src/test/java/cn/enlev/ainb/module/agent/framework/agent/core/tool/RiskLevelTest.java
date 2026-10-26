package cn.enlev.ainb.module.agent.framework.agent.core.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RiskLevel} 单测
 *
 * <p>钉两件事：① {@code severity} 与声明顺序当前一致 —— 有人重排常量时会先撞红这条，
 * 而不是撞红「manifest 把不可逆操作标成蓝色」的用户；② {@code higher} 的取大与 null 语义。
 *
 * <p>②「认不出的等级一律按 HIGH」由 {@code parseOrHigh} 保证，它是 S1 名称目录与
 * 自定义工具共用的唯一入口，本类一并钉住回落方向：宁可多说风险，不能把写操作标成只读。
 *
 * @author 走进新时代
 */
public class RiskLevelTest {

    @Test
    @DisplayName("🔴 severity 严格随声明顺序递增 —— 让「排版」与「语义」互相校验")
    public void testSeverityAscendingInDeclarationOrder() {
        RiskLevel[] values = RiskLevel.values();
        // 阳性对照：这条断言若在只剩一个常量的枚举上跑也会「通过」，故先钉住常量个数
        assertEquals(5, values.length, "风险等级应是 5 档（只读/低/中/高/不可逆），增减都要同步改本测试");
        for (int i = 1; i < values.length; i++) {
            assertTrue(values[i].getSeverity() > values[i - 1].getSeverity(),
                    values[i - 1] + " → " + values[i] + " 的 severity 没有递增；"
                            + "若你是刻意重排了声明顺序，请同步调整 severity，"
                            + "否则所有「取更严重那个」的调用点会静默改判");
        }
        assertEquals(0, RiskLevel.READ_ONLY.getSeverity());
        assertEquals(4, RiskLevel.IRREVERSIBLE.getSeverity());
    }

    @Test
    @DisplayName("higher 取更严重的一个，null 视为不严重")
    public void testHigher() {
        assertSame(RiskLevel.IRREVERSIBLE, RiskLevel.higher(RiskLevel.LOW, RiskLevel.IRREVERSIBLE));
        assertSame(RiskLevel.IRREVERSIBLE, RiskLevel.higher(RiskLevel.IRREVERSIBLE, RiskLevel.LOW));
        assertSame(RiskLevel.HIGH, RiskLevel.higher(RiskLevel.HIGH, RiskLevel.HIGH));
        assertSame(RiskLevel.MEDIUM, RiskLevel.higher(null, RiskLevel.MEDIUM));
        assertSame(RiskLevel.MEDIUM, RiskLevel.higher(RiskLevel.MEDIUM, null));
        assertNull(RiskLevel.higher(null, null));
    }

    @Test
    @DisplayName("🔴 parseOrHigh：缺失或非法一律回落 HIGH，绝不静默降级成只读")
    public void testParseOrHighFailsSafe() {
        assertSame(RiskLevel.HIGH, RiskLevel.parseOrHigh(null));
        assertSame(RiskLevel.HIGH, RiskLevel.parseOrHigh(""));
        assertSame(RiskLevel.HIGH, RiskLevel.parseOrHigh("   "));
        assertSame(RiskLevel.HIGH, RiskLevel.parseOrHigh("read_only"), "小写不是合法枚举名，按非法处理");
        assertSame(RiskLevel.HIGH, RiskLevel.parseOrHigh("DANGEROUS"));
        assertSame(RiskLevel.READ_ONLY, RiskLevel.parseOrHigh("READ_ONLY"));
        assertSame(RiskLevel.IRREVERSIBLE, RiskLevel.parseOrHigh(" IRREVERSIBLE "));
    }

    @Test
    @DisplayName("除只读外都要求确认 —— 「用户确认前不执行任何写操作」是铁律")
    public void testRequireConfirm() {
        assertEquals(false, RiskLevel.READ_ONLY.isRequireConfirm());
        for (RiskLevel level : RiskLevel.values()) {
            if (level == RiskLevel.READ_ONLY) {
                continue;
            }
            assertTrue(level.isRequireConfirm(), level + " 是写操作，必须进 Plan 等确认");
        }
    }

}
