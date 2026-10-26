package cn.enlev.ainb.module.agent.framework.agent.core.security;

import cn.enlev.ainb.module.agent.framework.agent.config.AgentSanitizeProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.UntrustedInputTracer.SourceKind;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可信输入的处置（B2-1）：外部内容<b>进 state 之前</b>剥控制字符 + 按源分档限长
 *
 * <h3>它与 {@link OutputModerator} 是输入/输出两半 —— 这句承诺到这里才成真</h3>
 * {@code OutputModerator} 的类 javadoc 第 13 行从 S6 起就写着「它与 {@code UntrustedInputSanitizer}
 * 是输入/输出两半」，而那个类<b>直到本轮之前都不存在</b>（仓里的 {@code UntrustedInputTracer}
 * 是同名诱饵：它做的是变更清单的<b>静态</b>污点追踪，一个字符都不改）。
 * 本类是那半边的落地，两者共用同一份污点源词表 {@link SourceKind}。
 *
 * <h3>🔴 它<b>不做</b>「打标」，且这是有意的 —— 与方案措辞的一处偏离，理由如下</h3>
 * 方案说 B2-1 要「打标 + 限长 + 剥控制字符」。本类做的是<b>限长 + 剥控制字符 + 把标记所需的事实
 * 作为元数据返回</b>（{@link Sanitized}），而<b>不往值里写任何可见标记</b>。
 *
 * <p>原因是这些值的下游<b>不止 prompt 一个</b>：同一份 state 里的值会被
 * {@code ToolNodeExecutor} 拼成 HTTP 请求体、被 {@code FormDataCreateTool} 写进业务表、
 * 被 BPM 塞进审批表单。往值里写「⚠️【不可信:webhook】」这种带内标记，
 * 结果是<b>业务库里真的存下这串前缀</b> —— 那是把安全提示写成了脏数据，
 * 而且它既不报错也不告警，只会在几个月后被人当成「这字段怎么老带个感叹号」来查。
 *
 * <p>所以分工是：<b>本类负责把值弄干净、并说清弄掉了什么；</b>
 * 「这段是不可信数据」这句话由 B2-3 的统一包裹组件在<b>装配 prompt 的那一刻</b>写在
 * 数据段外面（而不是数据里面）。两边共用 {@link SourceKind}，
 * 故方案要求的「标记形态必须能被统一包裹组件识别，两边不能各定一套」是靠
 * <b>共用同一份枚举 + 同一个 {@link Sanitized} 类型</b>满足的，不是靠共用一段字面量。
 *
 * <h3>🔴 剥控制字符这一支刻意<b>不照抄</b> {@code OutputModerator#stripInvisibleChars}</h3>
 * 三处实质差异，每一处都有理由：
 * <ol>
 *   <li><b>连 ZWNJ（U+200C）/ ZWJ（U+200D）一起剥</b>。输出侧刻意保留它们（波斯语/印地语正字法、
 *       emoji 组合序列），那份取舍在输出侧是对的；但输入侧不一样——
 *       B2-3 的定界符中和是<b>字面替换</b>，只有「数据里不存在任何不可见字符」这个前提成立时，
 *       字面替换才<b>可证明</b>是完备的。留着 ZWJ，攻击者就能把定界符拆成 {@code <ZWJ<<}：
 *       字面替换看不见它，而模型是模糊匹配的，它照样可能被读成结构。
 *       <b>代价（如实记）</b>：不可信入站里的 emoji 组合序列（👨‍👩‍👧）会被拆成各自的分量。
 *       这条代价只落在五个污点源上，平台自己生成的文本不过这里。</li>
 *   <li><b>补上 astral 平面的 TAG 码位（U+E0000~U+E007F）</b>。输出侧那份是<b>逐 char</b> 扫的，
 *       看不见代理对，于是这一整段不可见码位它一个都剥不掉 ——
 *       而「把 ASCII 藏进 TAG 码位」正是已知的提示注入手法之一（推断：本仓未实测到此类样本，
 *       补它是按码位表推的，不是按事故推的）。
 *       注意<b>只剥 U+E0000~U+E007F</b>，不碰 U+E0100~U+E01EF（变体选择符补充区）：
 *       后者在 CJK 兼容表意文字里是<b>有意义</b>的，剥掉会把合法中文改坏。</li>
 *   <li><b>剥完<b>不</b>在值里补「…（已截断）」</b>。输出侧把截断后缀写进值里是对的，
 *       因为那份值的唯一去处就是给人看；输入侧的值还要去当工具入参、去写库，
 *       写进去就成了脏数据。故这里<b>硬截断 + 元数据记 {@code truncated=true}</b>，
 *       要不要告诉人、怎么告诉，交给投递面自己决定。</li>
 * </ol>
 *
 * <h3>🔴 顺序是「剥 → 截断」，但理由与输出侧<b>不是</b>同一条</h3>
 * 输出侧（{@code OutputModerator} 类注释「审核做什么（打标，不拦截）」那一节）先剥后截，
 * 是为了让 {@code contains} 比对不被零宽字符拆开。
 * 本类<b>不做任何 {@code contains} 比对</b>，所以那条理由在这儿不适用。这里的理由是：
 * <b>先剥，上限才等于「N 个可见字符」</b>，而可见字符数才是真正要约束的东西
 * （它决定 token 花费，也是模型唯一读得到的部分）。
 *
 * <p>反过来（先截后剥）不会让进 state 的字符<b>变多</b>——只会变少，但那同样是个洞：
 * 攻击者在合法载荷前面垫一堆零宽字符，截断就会先把<b>垫料</b>切进配额里、把真实载荷挤出上限，
 * 于是「零成本毁掉对方数据」变成可行。样本见
 * {@code UntrustedInputSanitizerTest#sanitize_stripsBeforeTruncating_soPaddingCannotEvictRealContent}
 * —— 🔴 那条用例是<b>唯一</b>能抓到顺序写反的样本，其余用例在两种顺序下都是绿的。
 *
 * <h3>🔴 嵌套超限的处置是<b>丢弃</b>，不是「原样保留」</h3>
 * 见 {@link AgentSanitizeProperties#getMaxDepth()}。原样保留等于让一个<b>没被处置过</b>的值进了 state，
 * 那是这道闸唯一不能有的形状；丢弃 + warn 至少是 fail-close 且可观测的。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class UntrustedInputSanitizer {

    @Resource
    private AgentSanitizeProperties sanitizeProperties;

    /**
     * 处置一个字符串值
     *
     * @param source 污点源。<b>不可为 null</b>：档位是按源取的，
     *               给个 null 就只能落进某一档兜底，而「兜底档」恰恰是一刀切的那种错法
     * @param raw    原值；null 原样返回 null（调用方的 null 语义由调用方自己负责，这里不替它编一个空串）
     * @return 处置结果，含元数据
     */
    public Sanitized sanitize(SourceKind source, String raw) {
        if (raw == null) {
            return new Sanitized(null, source, 0, 0, false, maxLengthFor(source));
        }
        StripResult stripped = stripInvisible(raw);
        int max = maxLengthFor(source);
        String value = stripped.text;
        boolean truncated = false;
        if (value.length() > max) {
            value = value.substring(0, max);
            truncated = true;
        }
        Sanitized result = new Sanitized(value, source, raw.length(), stripped.count, truncated, max);
        // 🔴 只在「截断」时出声：剥控制字符是常态（CRLF 归一就会触发），逐值记日志会把日志刷满，
        //    而截断意味着<b>有数据被丢掉了</b>，那是运维必须看见的一件事。
        //    剥掉了多少，调用方从返回的元数据里拿（sanitizePayload 会在末尾汇总记一条）。
        if (truncated) {
            log.warn("[sanitize][{}：原长({}) 超过上限({})，已硬截断，超出部分未进 state]",
                    source.getLabel(), raw.length(), max);
        }
        return result;
    }

    /**
     * 处置一整个触发载荷（webhook body / form_event 值 / schedule 载荷都是这个形状）
     *
     * <p>🔴 返回<b>新建的</b> map，入参树在任何深度上都不会被改写 ——
     * 与 {@code OutputModerator#moderateFormVars} 同一条纪律（入参可能是 DSL 里那个活 map）。
     *
     * <p>非字符串叶子（数字/布尔/null）原样透传：它们没有「藏控制字符」的能力，
     * 也没有「超长文本」的形状，过一遍只是白花时间。
     */
    public Map<String, Object> sanitizePayload(SourceKind source, Map<String, Object> payload) {
        if (payload == null) {
            return new LinkedHashMap<>();
        }
        Counter counter = new Counter();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            out.put(e.getKey(), sanitizeValue(source, e.getValue(), 1, counter));
        }
        if (counter.modified()) {
            log.warn("[sanitizePayload][{}：共 {} 个字符串叶子，剥掉 {} 个控制字符，截断 {} 个，丢弃超深子树 {} 个]",
                    source.getLabel(), counter.leaves, counter.stripped, counter.truncated, counter.dropped);
        }
        return out;
    }

    /**
     * 递归处置一个值。返回新对象；超深时返回 null（= 丢弃该子树）
     */
    private Object sanitizeValue(SourceKind source, Object value, int depth, Counter counter) {
        if (value == null) {
            return null;
        }
        int maxDepth = sanitizeProperties.getMaxDepth();
        if (depth > maxDepth) {
            counter.dropped++;
            log.warn("[sanitizeValue][{}：嵌套超过 {} 层，该子树被丢弃（fail-close，不原样放行）]",
                    source.getLabel(), maxDepth);
            return null;
        }
        if (value instanceof String s) {
            counter.leaves++;
            Sanitized r = sanitize(source, s);
            counter.stripped += r.strippedChars();
            if (r.truncated()) {
                counter.truncated++;
            }
            return r.value();
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()),
                        sanitizeValue(source, e.getValue(), depth + 1, counter));
            }
            return out;
        }
        if (value instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object o : c) {
                out.add(sanitizeValue(source, o, depth + 1, counter));
            }
            return out;
        }
        // 🔴 用 instanceof 而不是 getClass().isArray() + 强转：基本类型数组（int[] 等）
        //    不是 Object[]，强转会抛 ClassCastException —— 而这段跑在图执行线程里，
        //    一个 CCE 就能把整次执行打死。走不到这个分支的类型一律原样透传。
        if (value instanceof Object[] arr) {
            List<Object> out = new ArrayList<>(arr.length);
            for (Object o : arr) {
                out.add(sanitizeValue(source, o, depth + 1, counter));
            }
            return out;
        }
        // 数字 / 布尔 / 其它标量：没有藏控制字符的能力，原样透传
        return value;
    }

    /**
     * 按源取长度上限。🔴 五个源五个数，走配置（{@link AgentSanitizeProperties}），不写死
     */
    private int maxLengthFor(SourceKind source) {
        return switch (source) {
            case TRIGGER_CHAT -> sanitizeProperties.getChatMaxLength();
            case TRIGGER_WEBHOOK -> sanitizeProperties.getWebhookBodyMaxLength();
            case TRIGGER_FORM_EVENT -> sanitizeProperties.getFormEventValueMaxLength();
            case RETRIEVAL -> sanitizeProperties.getRetrievalSegmentMaxLength();
            case TOOL_RESULT -> sanitizeProperties.getToolResultMaxLength();
        };
    }

    /**
     * 剥控制/不可见字符，并把 CRLF 归一成 LF
     *
     * <p>🔴 逐<b>码位</b>扫而不是逐 char：astral 平面的 TAG 码位是代理对，
     * 逐 char 扫的话每一半都落在 U+D800~U+DFFF，看着「不是控制字符」就放过去了 ——
     * 这正是 {@code OutputModerator} 那份实现漏掉 U+E0000~U+E007F 的原因。
     *
     * <p>没有可剥的东西时返回<b>原实例</b>（不 new String）：这一支在热路径上，
     * 绝大多数正常输入是干净的，白建一个对象没意义。
     */
    private StripResult stripInvisible(String s) {
        StringBuilder sb = null;
        int count = 0;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            int width = 1;
            boolean drop;
            if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                int cp = Character.toCodePoint(c, s.charAt(i + 1));
                width = 2;
                // TAG 码位：把 ASCII 藏进不可见码位的已知手法。
                // 🔴 只剥这一段，不碰 U+E0100~U+E01EF（变体选择符补充区，CJK 里是有意义的）
                drop = cp >= 0xE0000 && cp <= 0xE007F;
            } else {
                drop = isInvisibleChar(c);
            }
            if (drop) {
                count++;
                if (sb == null) {
                    sb = new StringBuilder(n);
                    sb.append(s, 0, i);
                }
            } else if (sb != null) {
                sb.append(s, i, i + width);
            }
            i += width;
        }
        return new StripResult(sb == null ? s : sb.toString(), count);
    }

    /**
     * 输入侧的不可见字符判定
     *
     * <p>🔴 与 {@code OutputModerator#isInvisible}（{@code :194-204}）<b>刻意不同</b>，
     * 差异与理由见类 javadoc「三处实质差异」。别把两份合并成一份「共用工具方法」——
     * 它们是两侧不同的<b>策略</b>，只是长得像；合并之后任何一侧想调策略都会连带改掉另一侧，
     * 而那正是「输出侧为了保 emoji 留了 ZWJ、输入侧为了保中和完备性必须剥 ZWJ」这对矛盾会被
     * 悄悄抹平的地方。
     *
     * <p>🟢 <b>B2-3 起对「同侧」开放复用</b>（{@code public} 的唯一理由）：
     * {@link PromptDataFence#neutralize} 要用它判断「两个尖括号之间是否只隔了不可见字符」——
     * 那条中和规则的<b>完备性证明</b>直接建立在「哪些字符算不可见」上，
     * 故两边必须读<b>同一张码位表</b>。分叉的后果是静默的：
     * 只有「数据里含定界符 + 不可见字符」这一种样本能抓到，其余用例在两种表下都是绿的。
     * 🔴 <b>跨侧（输出侧）仍然禁止复用</b>：上面那段矛盾正是靠两份表分开才守得住。
     */
    public static boolean isInvisibleChar(char c) {
        // C0（保留 \t 与 \n；\r 也在被剥之列，故 CRLF 自然归一成 LF）
        return c < 0x20 && c != '\t' && c != '\n'
                || c >= 0x7F && c <= 0x9F                  // DEL + C1
                || c == 0x061C                             // ARABIC LETTER MARK
                || c == 0x180E                             // MONGOLIAN VOWEL SEPARATOR
                // 🔴 ZWSP ZWNJ ZWJ LRM RLM 一起剥 —— 输出侧保留 ZWNJ/ZWJ，输入侧不保留
                || c >= 0x200B && c <= 0x200F
                || c >= 0x202A && c <= 0x202E              // LRE RLE PDF LRO RLO
                || c == 0x2028 || c == 0x2029              // 行分隔符 / 段分隔符
                || c == 0x2060                             // WORD JOINER
                || c >= 0x2061 && c <= 0x2064              // 不可见运算符
                || c >= 0x2066 && c <= 0x2069              // LRI RLI FSI PDI
                || c >= 0x206A && c <= 0x206F              // 已废弃的 bidi 控制符
                || c == 0xFEFF;                            // BOM / 零宽不换行空格
    }

    /** 剥字符的中间结果 */
    private record StripResult(String text, int count) {
    }

    /** 一次 {@link #sanitizePayload} 的累计计数，只为最后那条 warn 日志服务 */
    private static final class Counter {
        private int leaves;
        private int stripped;
        private int truncated;
        private int dropped;

        private boolean modified() {
            return stripped > 0 || truncated > 0 || dropped > 0;
        }
    }

    /**
     * 一次处置的结果：干净的值 + 「弄掉了什么」的事实
     *
     * <h3>🔴 这个类型就是 B2-1 与 B2-3 之间的那份共用词表</h3>
     * 方案要求「标记形态必须能被统一包裹组件识别，两边不能各定一套」。
     * 满足方式不是共用一段字面量，而是共用这个类型 + {@link SourceKind}：
     * 包裹组件拿到它，就能在<b>数据段外面</b>把「这段来自哪个源、被截断过、被剥过几个字符」
     * 写成人话给模型看，而不必往值里面塞任何东西。
     *
     * @param value          处置后的值。null 只在入参本身是 null 时出现
     * @param source         污点源
     * @param originalLength 原值长度（字符数）。入参为 null 时是 0
     * @param strippedChars  被剥掉的控制/不可见字符个数（含被归一掉的 {@code \r}）
     * @param truncated      是否因超过该源的上限而被硬截断
     * @param maxLength      当时生效的上限。<b>记下来是为了让日志能自证</b>：
     *                       只写「已截断」而不写「上限是多少」，事后没法判断是配置被人调小了还是数据真超了
     */
    public record Sanitized(String value, SourceKind source, int originalLength,
                            int strippedChars, boolean truncated, int maxLength) {

        /** 有没有动过。没动过时 {@link #value} 就是原实例（同一性可判） */
        public boolean modified() {
            return strippedChars > 0 || truncated;
        }

        /** 一句话说明，供投递面/日志直接用。没动过时返回「未修改」而不是空串——空串读起来像出了 bug */
        public String describe() {
            if (!modified()) {
                return source.getLabel() + "：未修改";
            }
            StringBuilder sb = new StringBuilder(source.getLabel()).append("：");
            if (strippedChars > 0) {
                sb.append("剥掉 ").append(strippedChars).append(" 个控制/不可见字符；");
            }
            if (truncated) {
                sb.append("原长 ").append(originalLength).append(" 超过上限 ")
                        .append(maxLength).append("，已截断；");
            }
            return sb.toString();
        }
    }

}
