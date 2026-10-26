package cn.enlev.ainb.module.agent.framework.agent.core.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式切分器的单元测试
 *
 * <h3>为什么这个类值得单独测</h3>
 * 它的失败方式全是<b>静默</b>的：要么把 {@code <<<SUGGESTIONS>>>} 和一串 JSON
 * 原样打给用户（看起来像「AI 抽风了」），要么把正文最后几个字吞掉
 * （看起来像「回答没说完」）。两种都不会抛异常，也不会在日志里留下任何痕迹 ——
 * 只能靠用例钉住。
 *
 * @author 走进新时代
 */
public class AgentAnswerStreamSplitterTest {

    /**
     * 把整段文本按固定长度切块喂进去，模拟流式到达
     */
    private static AgentAnswerStreamSplitter feedInChunks(String full, int chunkSize) {
        AgentAnswerStreamSplitter splitter = new AgentAnswerStreamSplitter();
        StringBuilder emitted = new StringBuilder();
        for (int i = 0; i < full.length(); i += chunkSize) {
            emitted.append(splitter.feed(full.substring(i, Math.min(i + chunkSize, full.length()))));
        }
        emitted.append(splitter.finish());
        // 逐块吐出去的内容，必须与累积的完整正文一致 ——
        // 两者不一致意味着前端看到的和库里存的不是同一段话
        assertEquals(splitter.getAnswer(), emitted.toString(),
                "逐块吐出的内容与累积正文不一致");
        return splitter;
    }

    @Test
    @DisplayName("没有 marker 时：全部是正文，一个字都不能丢")
    public void testNoMarker() {
        String text = "联动不生效通常是因为只配了 then 没配 otherwise。";
        AgentAnswerStreamSplitter splitter = feedInChunks(text, 7);
        assertEquals(text, splitter.getAnswer());
        assertFalse(splitter.hasSuggestionSection());
        assertEquals("", splitter.getSuggestionsRaw());
    }

    @Test
    @DisplayName("marker 完整落在一块里：正文与建议正确分开")
    public void testMarkerInSingleChunk() {
        AgentAnswerStreamSplitter splitter = new AgentAnswerStreamSplitter();
        String visible = splitter.feed("回答正文" + AgentAnswerStreamSplitter.MARKER + "[{\"text\":\"追问\"}]");
        assertEquals("回答正文", visible);
        assertEquals("回答正文", splitter.getAnswer());
        assertTrue(splitter.hasSuggestionSection());
        assertEquals("[{\"text\":\"追问\"}]", splitter.getSuggestionsRaw());
    }

    /**
     * 🔴 这是本类存在的主要理由
     *
     * 厂商按 token 切块，marker 极可能被拆成 {@code <<<SUG} 与 {@code GESTIONS>>>}。
     * 逐块独立地 contains 判断永远匹配不上，结果是整段 marker 加后面的 JSON
     * 一起被当成正文打给用户。
     *
     * <p>用 1..MARKER.length+3 的每一种块大小都跑一遍，保证不是「恰好这个切法能过」。
     */
    @Test
    @DisplayName("marker 被 chunk 边界切开：任何切法都要能正确识别")
    public void testMarkerSplitAcrossChunks() {
        String answer = "条件成立但只配了 then 没配 otherwise，字段会停在原状态。";
        String suggestionsJson = "[{\"text\":\"怎么排查子表的联动\"},{\"text\":\"服务端复校验会拦哪些\"}]";
        String full = answer + AgentAnswerStreamSplitter.MARKER + suggestionsJson;

        for (int chunkSize = 1; chunkSize <= AgentAnswerStreamSplitter.MARKER.length() + 3; chunkSize++) {
            AgentAnswerStreamSplitter splitter = feedInChunks(full, chunkSize);
            assertEquals(answer, splitter.getAnswer(),
                    "chunkSize=" + chunkSize + " 时正文被切错");
            assertTrue(splitter.hasSuggestionSection(),
                    "chunkSize=" + chunkSize + " 时没识别出 marker");
            assertEquals(suggestionsJson, splitter.getSuggestionsRaw(),
                    "chunkSize=" + chunkSize + " 时建议部分不完整");
        }
    }

    /**
     * marker 的前缀出现在正文里，但后面跟的不是 marker
     *
     * 切分器会先扣住这几个字符，发现拼不出 marker 之后必须<b>原样补发</b>。
     * 漏发的表现是「回答里少了几个字」，而且只在回答恰好含 {@code <} 时偶现。
     */
    @Test
    @DisplayName("正文里出现 marker 的前缀但不是 marker：扣住的字符要补发")
    public void testFalsePrefixInAnswer() {
        String text = "用 <<< 表示引用块，<<<SUG 不是完整标记，照常输出。";
        AgentAnswerStreamSplitter splitter = feedInChunks(text, 3);
        assertEquals(text, splitter.getAnswer());
        assertFalse(splitter.hasSuggestionSection());
    }

    @Test
    @DisplayName("正文以 marker 的前缀结尾：finish 必须把它放出来")
    public void testAnswerEndsWithMarkerPrefix() {
        AgentAnswerStreamSplitter splitter = new AgentAnswerStreamSplitter();
        // 喂进去的时候会被扣住，此刻还不能吐出来
        assertEquals("好的", splitter.feed("好的<<<"));
        // 流结束，扣住的 "<<<" 是正文，必须补发
        assertEquals("<<<", splitter.finish());
        assertEquals("好的<<<", splitter.getAnswer());
        assertFalse(splitter.hasSuggestionSection());
    }

    @Test
    @DisplayName("marker 之后再来的块，一个字都不能吐给用户")
    public void testNothingLeaksAfterMarker() {
        AgentAnswerStreamSplitter splitter = new AgentAnswerStreamSplitter();
        splitter.feed("正文" + AgentAnswerStreamSplitter.MARKER);
        assertEquals("", splitter.feed("[{\"text\":"));
        assertEquals("", splitter.feed("\"再问一个\"}]"));
        assertEquals("", splitter.finish());
        assertEquals("正文", splitter.getAnswer());
        assertEquals("[{\"text\":\"再问一个\"}]", splitter.getSuggestionsRaw());
    }

    @Test
    @DisplayName("空块与 null 块不影响结果")
    public void testEmptyChunks() {
        AgentAnswerStreamSplitter splitter = new AgentAnswerStreamSplitter();
        assertEquals("", splitter.feed(null));
        assertEquals("", splitter.feed(""));
        assertEquals("你好", splitter.feed("你好"));
        assertEquals("", splitter.feed(null));
        assertEquals("你好", splitter.getAnswer());
    }

    @Test
    @DisplayName("模型没按约定输出 marker：不算错误，建议为空即可")
    public void testModelIgnoredMarker() {
        AgentAnswerStreamSplitter splitter = feedInChunks("我直接回答了，没给建议。", 5);
        assertFalse(splitter.hasSuggestionSection());
        assertEquals("", splitter.getSuggestionsRaw());
        assertEquals(List.of(), AgentAnswerStreamSplitter.emptySuggestions());
    }

}
