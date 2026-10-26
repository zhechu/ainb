package cn.enlev.ainb.module.agent.framework.agent.core.chat;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentAnswerStreamSplitter.Suggestion;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentSecurityGateway;
import cn.enlev.ainb.module.agent.framework.agent.core.security.OutputModerator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话后建议的解析与权限预筛（方案 §9）
 *
 * <h3>为什么解析失败一律返回空列表，而不是抛异常</h3>
 * 建议是<b>锦上添花</b>：用户问了一句、模型也答了，结果因为建议的 JSON 少了个括号
 * 就让整次对话报错 —— 那是本末倒置。模型不总是听话，这里必须对它的输出格式宽容。
 *
 * <p>但也不静默：解析失败打 warn，让「模型总是不按格式输出」这件事在日志里可见，
 * 而不是表现为「建议时有时无」这种没人会去查的玄学现象。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class AgentSuggestionParser {

    /**
     * 最多保留几条
     *
     * 方案 §9.3 定的 3 条。超过会变成噪声 —— 建议的意义是<b>降低表达成本</b>，
     * 列出七八条反而让用户要先读一遍再挑，比不给还累。
     */
    private static final int MAX_SUGGESTIONS = 3;

    /**
     * 单条建议的长度上限
     *
     * 建议是要显示成可点击的小标签的，太长会撑破布局。
     * 在后端截断而不是靠前端 CSS 省略号：省略号只是看不见，
     * 点下去仍然会把一整段话填进输入框。
     */
    private static final int MAX_TEXT_LENGTH = 40;

    @Resource
    private AgentSecurityGateway gateway;

    /**
     * 解析并预筛
     *
     * @param raw     marker 之后的原始文本
     * @param context 执行上下文快照，用于权限预筛；为 null 时跳过预筛（仅限无 tool 的建议）
     * @return 建议列表，最多 {@link #MAX_SUGGESTIONS} 条；任何异常都返回已解析的部分
     */
    public List<Suggestion> parse(String raw, AgentExecutionContext context) {
        List<Suggestion> result = new ArrayList<>();
        String json = stripCodeFence(raw);
        if (StrUtil.isBlank(json)) {
            return result;
        }
        try {
            JSONArray array = JSONUtil.parseArray(json);
            for (Object item : array) {
                if (result.size() >= MAX_SUGGESTIONS) {
                    break;
                }
                Suggestion suggestion = toSuggestion(item);
                if (suggestion == null) {
                    continue;
                }
                // 🔴 方案 §9.3：不得凭空建议做不到的事。
                // 建议里涉及的操作必须在当前用户的授权范围内，否则用户点了才被拒 ——
                // 那比不给建议体验更差：他会以为是系统坏了，而不是自己没权限
                if (!isAllowed(suggestion, context)) {
                    log.debug("[parse][建议({})涉及工具({})，当前用户无权限，已过滤]",
                            suggestion.text(), suggestion.tool());
                    continue;
                }
                result.add(suggestion);
            }
        } catch (Exception ex) {
            // 解析失败不影响主回答。打 warn 是为了让「模型总是不按格式输出」可见
            log.warn("[parse][建议解析失败，已忽略。原始内容：{}]", StrUtil.maxLength(raw, 200), ex);
        }
        return result;
    }

    /**
     * 单条建议的权限预筛
     *
     * <h3>为什么是 try/catch 而不是给网关加一个 {@code canUse} 方法</h3>
     * 网关的六步断言是安全边界，<b>只能有一份实现</b>。
     * 另写一个「不抛异常的判断版」看起来更干净，但两份逻辑必然漂移 ——
     * 而漂移的方向恰恰是最坏的那种：预筛说「能用」、真执行时被拒，
     * 或者反过来预筛过严把用户能做的事藏起来。
     *
     * <p>所以这里复用同一个 {@code check}，把异常当成「不允许」。
     * 代价是一次异常构造的开销，而建议最多 3 条，可以忽略。
     */
    private boolean isAllowed(Suggestion suggestion, AgentExecutionContext context) {
        if (StrUtil.isBlank(suggestion.tool())) {
            return true; // 纯追问类建议，不动用工具，无需预筛
        }
        if (context == null) {
            // 拿不到身份就不敢放行带工具的建议 —— 与网关同样的 fail-close 语义
            return false;
        }
        try {
            gateway.check(context, suggestion.tool());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private Suggestion toSuggestion(Object item) {
        // 兼容两种形态：模型可能返回 ["问题1", "问题2"]，也可能返回 [{"text": "..."}]。
        // 两种都收下 —— 与其反复调 prompt 逼它统一，不如这里多认一种格式
        if (item instanceof CharSequence) {
            return build(item.toString(), null);
        }
        if (item instanceof JSONObject obj) {
            return build(obj.getStr("text"), obj.getStr("tool"));
        }
        return null;
    }

    private Suggestion build(String text, String tool) {
        String trimmed = StrUtil.trimToEmpty(text);
        if (trimmed.isEmpty()) {
            return null;
        }
        // 🔴 【B2-2 · 口径 B 第 7 项】suggestions 与主回答<b>同一次 LLM 调用</b>产出，同源同险，照审。
        //    收口点选在这里而不在 AgentAssistantServiceImpl 的那两行投递处：本方法是 Suggestion
        //    记录的<b>唯一构造口</b>（toSuggestion 的两个分支都汇到它），审一次就同时盖住
        //    SSE 事件与落进 agent_message.suggestions 的那份 —— 两处用的是同一个 list 对象。
        //
        // 🔴 顺序是「先按列宽截断、后审核」，<b>不是</b>反过来：建议是要显示成可点击小标签的，
        //    MAX_TEXT_LENGTH=40 那个上限服务的是布局；而审核命中时会往最前面加 9 个字符的
        //    「⚠️【疑似注入】」前缀。若先审后截，前缀会被截成「⚠️【疑似注…」——
        //    一个被切断的警告标记看着像乱码，用户学不到「这段文字可疑」，标记就白打了。
        //    故让前缀完整保留，代价是被标记的建议会比 40 字略长（≤49 字），撑不破布局。
        // ⚠️ 已知副作用：点击建议会把它的文字填进输入框（见 MAX_TEXT_LENGTH 的注释），
        //    于是被标记的那条<b>连前缀一起</b>成为下一轮的提问。这是刻意的 ——
        //    与 OutputModerator「打标不拦截」的总策略一致：建议是锦上添花，
        //    直接丢掉可疑的那几条等于让「模型输出被操纵」这件事在界面上完全看不出来。
        return new Suggestion(OutputModerator.moderateString(
                        AgentTextUtils.truncateForColumn(trimmed, MAX_TEXT_LENGTH)),
                StrUtil.trimToNull(tool));
    }

    /**
     * 剥掉 Markdown 代码围栏
     *
     * 模型很爱把 JSON 包在 ```json ... ``` 里，哪怕 prompt 里明确说了不要。
     * 与其和它较劲，不如这里剥一层 —— 这是实测反复出现的行为，不是防御性冗余。
     */
    private static String stripCodeFence(String raw) {
        String text = StrUtil.trimToEmpty(raw);
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return "";
        }
        text = text.substring(firstLineEnd + 1);
        int fenceEnd = text.lastIndexOf("```");
        return fenceEnd >= 0 ? text.substring(0, fenceEnd).trim() : text.trim();
    }

}
