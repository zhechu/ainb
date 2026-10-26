package cn.enlev.ainb.module.ai.service.knowledge.splitter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MarkdownQaSplitter} 单测
 *
 * <p>本类守的是一条很窄但后果很重的不变量：<b>切分不许丢内容、也不许改写内容</b>。
 * 切片器出问题的表现是「段落数看着正常、向量化 100%、检索也不报错」，
 * 唯一症状是用户觉得「知识库怎么没收录开头那段」—— 没有任何日志能指向它。
 *
 * <h3>两条真实缺陷（都是 2026-10-25 端到端实测向量模型迁移时顺带挖出来的）</h3>
 * <ol>
 *   <li><b>第一个 {@code ## } 之前的内容整段丢失。</b>{@code parseQaPairs} 从
 *       {@code headingPositions.get(0)} 开始切，标题与导语压根没进任何片段。
 *       实测一篇 122 字的文档只切出 65 字，开头那句标记句彻底消失。</li>
 *   <li><b>句末标点被吃掉。</b>{@code SENTENCE_PATTERN.split()} 连分隔符一起删，
 *       于是超长段落切出来的片段少了 {@code 。！？.!?} —— 不是丢句子，是<b>改写</b>了原文。</li>
 * </ol>
 *
 * <p>🔴 别以为「本类只在 QA 文档上跑」就可以放松：判据是
 * {@code .md + ≥2 个 ## + ## 行占比 > 10%}（见 {@code AiKnowledgeSegmentServiceImpl#isMarkdownQaFormat}），
 * 一篇小节多、正文短的普通手册就会被误判进来。所以第 1 条丢的可能是一整段导语，不是一个标题。
 *
 * @author 走进新时代
 */
@DisplayName("Markdown QA 切片器：不丢内容、不改写内容")
class MarkdownQaSplitterTest {

    /** 宽松到不会触发任何二次切分的 token 上限，让断言只盯着「有没有丢」这一件事 */
    private static final MarkdownQaSplitter LOOSE = new MarkdownQaSplitter(800);

    /**
     * 端到端实测里那篇文档的原样：标题 + 导语 + 两个小节
     *
     * 🔴 它的 {@code ## } 行占比刚好越过 10%，是「普通文档被误判成 QA」的真实样本，
     * 不是为测试编出来的形状
     */
    private static final String MARKER = "紫色河马在星期四下午三点十七分跳了一支探戈";
    private static final String DOC_WITH_PREAMBLE = """
            # 向量模型迁移探测

            启凌智搭的向量模型迁移探测标记句：%s。

            ## 空代码块

            ```
            ```

            ## 连续空行之后的一段

            这一段前面有四个连续空行，切分器可能产出空片段。
            """.formatted(MARKER);

    @Test
    @DisplayName("🔴 第一个 ## 之前的标题与导语必须留在片段里")
    void testPreambleBeforeFirstH2IsNotDropped() {
        List<String> chunks = LOOSE.splitText(DOC_WITH_PREAMBLE);

        String joined = String.join("\n\n", chunks);
        assertTrue(joined.contains(MARKER),
                "导语里那句标记句没了 —— 这正是 parseQaPairs 从第一个 ## 开始切导致的整段丢失。"
                        + "实际切出：" + chunks);
        assertTrue(joined.contains("# 向量模型迁移探测"), "文档标题没了。实际切出：" + chunks);
        // 导语单独成片，而不是被塞进第一个 QA 对里：塞进去会让那一片既有标题又有问答，
        // 检索命中它时回灌的上下文与用户点开原文看到的结构对不上
        assertTrue(chunks.get(0).contains(MARKER) && !chunks.get(0).contains("## 空代码块"),
                "导语应当单独成片。实际第一片：" + chunks.get(0));
    }

    @Test
    @DisplayName("🔴 逐行不变量：输入的每一行非空文本都必须在输出里找得到")
    void testNoLineIsEverLost() {
        List<String> samples = new ArrayList<>();
        samples.add(DOC_WITH_PREAMBLE);
        // 真 QA 文档：没有导语，第一个 ## 就在开头
        samples.add("""
                ## 怎么新建一个知识库

                进入 AI 大模型 - 知识库管理，点新增，选一个向量模型。

                ## 向量模型怎么选

                选启用中的、且免费额度还没用完的那个。
                """);
        // 完全不像 QA 的普通手册，但 ## 占比高 ⟹ 会被误判进来，走 fallbackSplit
        samples.add("""
                ## 一

                甲。

                ## 二

                乙。
                """);
        // 只有一个 ## ⟹ 仍会解析出 1 个 QA 对（parseQaPairs 只在【一个 ## 都没有】时才返回空），
        // 所以这条真正测的是「导语 + 单个 QA 对」的组合
        samples.add("# 标题\n\n导语一段。\n\n## 唯一小节\n\n正文一段。\n");
        // 一个 ## 都没有 ⟹ 走 fallbackSplit
        samples.add("# 标题\n\n完全没有二级标题的一篇文档。\n\n第二段。\n");

        for (String sample : samples) {
            String joined = String.join("\n", LOOSE.splitText(sample));
            for (String line : sample.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                assertTrue(joined.contains(trimmed),
                        "有一行在切分后找不到了 —— 切片器丢了内容。\n丢失的行：" + trimmed
                                + "\n输入：\n" + sample + "\n输出：\n" + joined);
            }
        }
    }

    @Test
    @DisplayName("🔴 超长段落按句切分时，句末标点必须留着（split 会把它吃掉）")
    void testSentencePunctuationSurvivesLongParagraphSplit() {
        // chunkSize 收到 12：单个段落必然超限，从而走进 splitLongParagraph 的按句切分
        MarkdownQaSplitter tight = new MarkdownQaSplitter(12);
        String doc = """
                ## 一个很长的小节

                第一句话在这里。第二句话在这里。第三句话在这里。第四句话在这里。第五句话在这里。
                """;

        List<String> chunks = tight.splitText(doc);

        assertTrue(chunks.size() > 2, "没有真的按句切开，这条测试就没测到东西：" + chunks);
        String joined = String.join("", chunks);
        // 五句话五个句号。用 split() 的实现会得到 0 个 —— 那就是「改写原文」的证据
        long periods = joined.chars().filter(c -> c == '。').count();
        assertEquals(5, periods, "句末句号被吃掉了（原文 5 个）。实际片段：" + chunks);
        for (String sentence : List.of("第一句话在这里。", "第三句话在这里。", "第五句话在这里。")) {
            assertTrue(joined.contains(sentence), "句子没有连着标点一起保留：" + sentence + "，实际：" + chunks);
        }
    }

    @Test
    @DisplayName("末句没有标点结尾时也不能丢")
    void testTrailingSentenceWithoutPunctuationIsKept() {
        MarkdownQaSplitter tight = new MarkdownQaSplitter(12);
        List<String> chunks = tight.splitText("## 小节\n\n第一句话在这里。结尾没有标点的一句\n");

        String joined = String.join("", chunks);
        assertTrue(joined.contains("结尾没有标点的一句"),
                "没有标点收尾的末句被丢了。实际片段：" + chunks);
    }

    @Test
    @DisplayName("空输入与无标题输入不炸")
    void testDegenerateInputs() {
        assertEquals(List.of(), LOOSE.splitText(""));
        // 没有任何 ## ⟹ 识别不出 QA 对，走降级切分，但内容必须在
        List<String> chunks = LOOSE.splitText("# 只有标题\n\n正文一段。\n");
        assertFalse(chunks.isEmpty(), "降级切分返回了空列表");
        assertTrue(String.join("", chunks).contains("正文一段。"), "降级切分丢了正文：" + chunks);
    }

}
