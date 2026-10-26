package cn.enlev.ainb.module.agent.framework.agent.core.chat;

import cn.hutool.core.util.StrUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本截断的单元测试
 *
 * <h3>为什么这么小的工具也要测</h3>
 * 它守的是一个<b>会静默吃掉数据</b>的边界：{@code StrUtil.maxLength(s, 500)}
 * 返回 503 个字符，写进 {@code varchar(500)} 直接插入失败。
 * 而调用点都在异常路径上，失败会被 catch 吞掉 ——
 * 表现是「出错的那几次连计量记录都没有」，没人会把这件事联想到一个截断函数上。
 *
 * @author 走进新时代
 */
public class AgentTextUtilsTest {

    @Test
    @DisplayName("🔴 结果长度绝不超过列宽（Hutool 的 maxLength 会超）")
    public void testNeverExceedColumnLength() {
        String longText = "错".repeat(1000);

        // 先把被替代的行为钉下来，说明这个类为什么存在
        assertEquals(503, StrUtil.maxLength(longText, 500).length(),
                "StrUtil.maxLength 返回 n+3，这正是本类要绕开的坑");

        assertEquals(500, AgentTextUtils.truncateForColumn(longText, 500).length());
    }

    @Test
    @DisplayName("刚好等于列宽：原样返回，不加省略号")
    public void testExactLength() {
        String text = "错".repeat(500);
        assertEquals(text, AgentTextUtils.truncateForColumn(text, 500));
    }

    @Test
    @DisplayName("短文本原样返回")
    public void testShortText() {
        assertEquals("短", AgentTextUtils.truncateForColumn("短", 500));
    }

    @Test
    @DisplayName("null 转空串：列多是 NOT NULL DEFAULT ''")
    public void testNull() {
        assertEquals("", AgentTextUtils.truncateForColumn(null, 500));
    }

    @Test
    @DisplayName("截断后带省略号，让用户知道后面还有内容")
    public void testEllipsisAppended() {
        String result = AgentTextUtils.truncateForColumn("内容".repeat(100), 50);
        assertEquals(50, result.length());
        assertTrue(result.endsWith("..."));
    }

}
