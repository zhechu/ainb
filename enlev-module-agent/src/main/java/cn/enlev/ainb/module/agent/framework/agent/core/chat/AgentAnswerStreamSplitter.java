package cn.enlev.ainb.module.agent.framework.agent.core.chat;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式回答的「正文 / 建议」切分器
 *
 * <h3>要解决什么</h3>
 * 方案 §9.2 定的是：对话后建议与主回答<b>同一次 LLM 调用</b>返回
 * （单独再调一次会让每次对话成本近乎翻倍，而建议的质量提升有限）。
 * 但主回答要<b>流式</b>吐给用户，建议却是个结构化数组 ——
 * 如果让模型直接输出 {@code {"answer": "...", "suggestions": [...]}}，
 * 用户就会眼睁睁看着一串 JSON 逐字打出来，打字机效果彻底废掉。
 *
 * <p>所以约定模型这样输出：
 * <pre>
 *   正文正文正文……
 *   &lt;&lt;&lt;SUGGESTIONS&gt;&gt;&gt;
 *   [{"text": "怎么排查子表的联动"}, ...]
 * </pre>
 * 本类在流上做增量扫描：marker 之前的原样放行，marker 之后的缓冲起来，
 * 流结束时再解析成建议。用户看到的始终是干净的正文。
 *
 * <h3>🔴 marker 会被 chunk 边界切开</h3>
 * 这不是理论风险，是流式必然会遇到的情况：厂商按 token 切块，
 * {@code <<<SUGGESTIONS>>>} 极可能被拆成 {@code <<<SUG} 与 {@code GESTIONS>>>} 两块到达。
 * 逐块独立地 {@code contains} 判断<b>永远匹配不上</b>，
 * 结果就是整段 marker 和后面的 JSON 一起被当成正文打给用户。
 *
 * <p>所以每块处理完都要检查：缓冲区<b>末尾</b>是不是 marker 的某个前缀？
 * 是就先扣住不发，等下一块拼上再判断。扣住的最多是 marker 长度减一个字符，
 * 对打字机的观感没有影响。
 *
 * <p>本类<b>不是线程安全的</b>，一次对话一个实例，只在流的消费线程上用。
 *
 * @author 走进新时代
 */
public class AgentAnswerStreamSplitter {

    /**
     * 正文与建议的分隔标记
     *
     * 选这个形状是因为它<b>几乎不可能在正常回答里出现</b>：
     * 三个尖括号加全大写英文单词，中文技术问答里不会自然写出来。
     * 若用 {@code ---} 或 {@code ###} 这类 Markdown 记号，正文里一旦出现就会被误切，
     * 表现为「回答被莫名截断」—— 而且只在某些问题上偶发，极难复现。
     */
    public static final String MARKER = "<<<SUGGESTIONS>>>";

    /**
     * 已经切到建议部分了吗
     */
    private boolean inSuggestions = false;

    /**
     * 扣住的尾巴：可能是 marker 的前缀，等下一块拼上来再判断
     */
    private String pending = "";

    /**
     * marker 之后的内容
     */
    private final StringBuilder suggestionBuffer = new StringBuilder();

    /**
     * 完整正文（用于落库，与逐块吐给前端的内容一致）
     */
    private final StringBuilder answerBuffer = new StringBuilder();

    /**
     * 喂入一块流式增量
     *
     * @param chunk 本次增量，可为 null
     * @return 可以立刻吐给前端的正文片段；没有可吐的就返回空串（<b>不是 null</b>）
     */
    public String feed(String chunk) {
        if (StrUtil.isEmpty(chunk)) {
            return "";
        }
        // 已经进入建议区，后面的全部是建议，一个字都不再吐给前端
        if (inSuggestions) {
            suggestionBuffer.append(chunk);
            return "";
        }

        String combined = pending + chunk;
        int index = combined.indexOf(MARKER);
        if (index >= 0) {
            // 撞上完整 marker：前面的是正文，后面的进建议缓冲
            inSuggestions = true;
            pending = "";
            suggestionBuffer.append(combined.substring(index + MARKER.length()));
            return emit(combined.substring(0, index));
        }

        // 没撞上。检查末尾是不是 marker 的前缀 —— 这一步就是防「marker 被切两半」的
        int holdBack = suffixPrefixLength(combined);
        pending = combined.substring(combined.length() - holdBack);
        return emit(combined.substring(0, combined.length() - holdBack));
    }

    /**
     * 流结束，把扣住的尾巴放出来
     *
     * <p>走到这里说明扣住的那截<b>不是</b> marker 的开头（否则早就匹配上了），
     * 是正常正文，必须补发 —— 漏发的表现是「回答最后少了几个字」，
     * 而且只在回答恰好以 {@code <} 之类字符结尾时偶现。
     *
     * @return 剩余的正文片段，没有则为空串
     */
    public String finish() {
        if (inSuggestions || pending.isEmpty()) {
            return "";
        }
        String rest = pending;
        pending = "";
        return emit(rest);
    }

    /**
     * 完整正文
     */
    public String getAnswer() {
        return answerBuffer.toString();
    }

    /**
     * marker 之后的原始文本，未解析
     */
    public String getSuggestionsRaw() {
        return suggestionBuffer.toString();
    }

    /**
     * 模型有没有按约定输出 marker
     *
     * 没输出不算错误 —— 模型不总是听话，而<b>建议缺失远不如回答出错严重</b>。
     * 调用方据此决定是否记一条 warn，但绝不能因此让整次对话失败。
     */
    public boolean hasSuggestionSection() {
        return inSuggestions;
    }

    private String emit(String text) {
        answerBuffer.append(text);
        return text;
    }

    /**
     * 求 {@code text} 的最长后缀，使它同时是 {@link #MARKER} 的前缀
     *
     * 例：text 以 {@code "……好的<<<SUG"} 结尾时返回 6（{@code "<<<SUG"} 的长度），
     * 这 6 个字符要扣住等下一块。
     *
     * <p>从长到短试：只关心<b>最长</b>的那个 —— 取短了会把 marker 的一部分当正文发出去。
     */
    private static int suffixPrefixLength(String text) {
        int max = Math.min(MARKER.length() - 1, text.length());
        for (int len = max; len > 0; len--) {
            if (text.regionMatches(text.length() - len, MARKER, 0, len)) {
                return len;
            }
        }
        return 0;
    }

    /**
     * 一条建议
     *
     * @param text 展示给用户、点击后直接发送的文本
     * @param tool 该建议若要动用工具，这里是工具名；纯追问类建议为 null。
     *             方案 §9.3 要求建议必须在用户的工具授权范围内 ——
     *             这个字段就是权限预筛的依据（见 {@code AgentSuggestionParser}）
     */
    public record Suggestion(String text, String tool) {

        public static Suggestion of(String text) {
            return new Suggestion(text, null);
        }
    }

    /**
     * 空建议列表的共享实例，避免各处重复 new
     */
    public static List<Suggestion> emptySuggestions() {
        return new ArrayList<>();
    }

}
