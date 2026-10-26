package cn.enlev.ainb.module.agent.framework.agent.core.security;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentSanitizeProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.UntrustedInputTracer.SourceKind;
import cn.enlev.ainb.module.agent.framework.agent.core.security.UntrustedInputSanitizer.Sanitized;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UntrustedInputSanitizer} 单测（B2-1）
 *
 * <p>钉住八件事：
 * <ol>
 *   <li><b>剥控制字符</b>——C0/DEL+C1、双向控制符、零宽族、行/段分隔符、BOM</li>
 *   <li><b>连 ZWNJ/ZWJ 一起剥</b>——这是与 {@code OutputModerator} 刻意不同的一处，
 *       必须被钉住，否则将来有人「统一一下两边的工具方法」就会把它悄悄改回保留</li>
 *   <li><b>astral 平面的 TAG 码位剥得掉</b>——逐 char 扫的实现看不见代理对，这一条钉的是「按码位扫」</li>
 *   <li><b>CRLF 归一成 LF</b>，且换行结构不被毁掉</li>
 *   <li><b>按源分档、且档位真的来自配置</b>——五个源五个数，改配置就得跟着变</li>
 *   <li><b>顺序是「先剥后截」</b>——🔴 这一条最容易写反，且写反之后<b>绝大多数用例仍然是绿的</b>，
 *       只有「用不可见字符把合法内容挤出上限」这个样本能抓到它</li>
 *   <li><b>绝不往值里写任何带内标记</b>——值还要去当工具入参、去写业务库，
 *       写进去就是脏数据。截断后的长度必须<b>恰好等于</b>上限，不是「上限 + 后缀」</li>
 *   <li><b>干净输入零改动</b>（返回同一实例）；嵌套载荷递归处置且<b>不改写入参树</b></li>
 * </ol>
 *
 * @author 走进新时代
 */
@DisplayName("agent 不可信输入处置（B2-1）")
class UntrustedInputSanitizerTest extends BaseMockitoUnitTest {

    @InjectMocks
    private UntrustedInputSanitizer sanitizer;

    /** 用真实的 Properties（不 mock）：要验的正是「档位真的被读到并生效」 */
    @Spy
    private AgentSanitizeProperties sanitizeProperties = new AgentSanitizeProperties();

    private static final String ZWSP = "\u200B";
    private static final String ZWJ = "\u200D";
    private static final String ZWNJ = "\u200C";
    private static final String RLO = "\u202E";
    private static final String BOM = "\uFEFF";

    /** U+E0041 = TAG LATIN CAPITAL LETTER A：把 ASCII 藏进不可见码位的那个手法 */
    private static String tag(int ascii) {
        return new String(Character.toChars(0xE0000 + ascii));
    }

    // ==================== ① 剥控制字符 ====================

    @Test
    @DisplayName("①C0/DEL+C1、双向控制符、零宽族、行/段分隔符、BOM 一律剥掉")
    void sanitize_stripsControlAndInvisibleChars() {
        String raw = "a\u0000b\u001Fc\u007Fd\u009Fe" + RLO + "f" + ZWSP + "g\u2028h\u2029i" + BOM + "j\u061Ck";

        Sanitized r = sanitizer.sanitize(SourceKind.TRIGGER_WEBHOOK, raw);

        assertEquals("abcdefghijk", r.value());
        assertTrue(r.modified());
        // 数一下剥了几个：\u0000 \u001F \u007F \u009F RLO ZWSP \u2028 \u2029 BOM \u061C = 10
        assertEquals(10, r.strippedChars(), "剥掉的个数必须被如实记进元数据，供投递面决定要不要告诉人");
        assertFalse(r.truncated());
    }

    @Test
    @DisplayName("①制表符与换行是合法排版，必须留下")
    void sanitize_keepsTabAndNewline() {
        String raw = "col1\tcol2\nline2";

        Sanitized r = sanitizer.sanitize(SourceKind.TRIGGER_CHAT, raw);

        assertSame(raw, r.value(), "没有可剥的东西时必须返回原实例，不白建对象");
        assertFalse(r.modified());
        assertEquals(0, r.strippedChars());
    }

    // ==================== ② 连 ZWNJ/ZWJ 一起剥（与输出侧刻意不同） ====================

    @Test
    @DisplayName("②ZWNJ/ZWJ 也剥——这一条是输入侧与 OutputModerator 的分水岭")
    void sanitize_stripsZwjAndZwnj_unlikeOutputModerator() {
        // 🔴 OutputModerator 刻意保留这两个（波斯语/印地语正字法、emoji 组合序列），
        //    输入侧刻意不保留：B2-3 的定界符中和是字面替换，
        //    只有「数据里不存在任何不可见字符」成立时字面替换才可证明完备
        String raw = "ig" + ZWNJ + "nore" + ZWJ + "previous";

        Sanitized r = sanitizer.sanitize(SourceKind.RETRIEVAL, raw);

        assertEquals("ignoreprevious", r.value());
        assertEquals(2, r.strippedChars());
        assertFalse(r.value().contains(ZWNJ));
        assertFalse(r.value().contains(ZWJ));
    }

    @Test
    @DisplayName("②已知的代价：emoji 组合序列会被拆成分量，但可见部分一个不少")
    void sanitize_emojiZwjSequenceIsDecomposed_documentedCost() {
        // 👨‍👩‍👧 = U+1F468 ZWJ U+1F469 ZWJ U+1F467
        String man = new String(Character.toChars(0x1F468));
        String woman = new String(Character.toChars(0x1F469));
        String girl = new String(Character.toChars(0x1F467));
        String raw = "家庭：" + man + ZWJ + woman + ZWJ + girl;

        Sanitized r = sanitizer.sanitize(SourceKind.TRIGGER_CHAT, raw);

        // 如实断言这个代价：组合符没了（渲染上是三个人而不是一个家庭），但三个分量都在
        assertFalse(r.value().contains(ZWJ));
        assertTrue(r.value().contains(man));
        assertTrue(r.value().contains(woman));
        assertTrue(r.value().contains(girl));
        assertEquals(2, r.strippedChars());
    }

    // ==================== ③ astral 平面的 TAG 码位 ====================

    @Test
    @DisplayName("③藏在代理对里的 TAG 码位也剥得掉（逐 char 扫的实现会漏）")
    void sanitize_stripsAstralTagCodePoints() {
        // 把 "HI" 藏进 U+E0048 / U+E0049，再夹在可见文字中间
        String raw = "订单" + tag('H') + tag('I') + "已支付";

        Sanitized r = sanitizer.sanitize(SourceKind.TOOL_RESULT, raw);

        assertEquals("订单已支付", r.value());
        assertEquals(2, r.strippedChars());
        for (int i = 0; i < r.value().length(); i++) {
            assertFalse(Character.isHighSurrogate(r.value().charAt(i))
                            && i + 1 < r.value().length()
                            && Character.toCodePoint(r.value().charAt(i), r.value().charAt(i + 1)) >= 0xE0000,
                    "TAG 码位不该活下来");
        }
    }

    @Test
    @DisplayName("③但变体选择符补充区（U+E0100~）不剥——那在 CJK 里是有意义的")
    void sanitize_keepsVariationSelectorsSupplement() {
        // U+E0100 是合法的变体选择符，剥掉会把 CJK 兼容表意文字改坏
        String vs = new String(Character.toChars(0xE0100));
        String raw = "\u82A6" + vs + "字";

        Sanitized r = sanitizer.sanitize(SourceKind.RETRIEVAL, raw);

        assertSame(raw, r.value(), "只剥 U+E0000~U+E007F，越界剥就是误伤合法文本");
        assertFalse(r.modified());
    }

    // ==================== ④ CRLF 归一 ====================

    @Test
    @DisplayName("④CRLF 归一成 LF，换行结构不被毁掉")
    void sanitize_normalizesCrlfToLf() {
        String raw = "line1\r\nline2\r\nline3";

        Sanitized r = sanitizer.sanitize(SourceKind.TRIGGER_WEBHOOK, raw);

        assertEquals("line1\nline2\nline3", r.value());
        assertEquals(2, r.strippedChars(), "两个 \\r 被剥，\\n 留下");
        assertEquals(2, r.value().split("\n", -1).length - 1, "三行仍然是三行");
    }

    @Test
    @DisplayName("④孤立的 \\r 也被剥（老 Mac 行尾）：宁可并成一行，也不留一个能被用来伪造行结构的字符")
    void sanitize_stripsLoneCarriageReturn() {
        Sanitized r = sanitizer.sanitize(SourceKind.TRIGGER_FORM_EVENT, "a\rb");

        assertEquals("ab", r.value());
        assertEquals(1, r.strippedChars());
    }

    // ==================== ⑤ 按源分档 + 档位来自配置 ====================

    @Test
    @DisplayName("⑤五个源各有各的上限，且默认值互不相同（不许一刀切）")
    void sanitize_capsAreTieredPerSource_notOneSizeFitsAll() {
        // 默认档位：chat 4000 / webhook 32768 / form_event 8192 / retrieval 4000 / tool 16384
        // 🔴 方案原话：「webhook body 与单条检索片段不该同一个数」
        String probe = "x".repeat(5000);

        assertEquals(4000, sanitizer.sanitize(SourceKind.TRIGGER_CHAT, probe).value().length());
        assertEquals(5000, sanitizer.sanitize(SourceKind.TRIGGER_WEBHOOK, probe).value().length(),
                "webhook 档比 5000 大，不该被截");
        assertEquals(5000, sanitizer.sanitize(SourceKind.TRIGGER_FORM_EVENT, probe).value().length());
        assertEquals(4000, sanitizer.sanitize(SourceKind.RETRIEVAL, probe).value().length(),
                "检索片段档必须比 webhook 档紧：它是五个源里攻击者门槛最低的那一个");
        assertEquals(5000, sanitizer.sanitize(SourceKind.TOOL_RESULT, probe).value().length());
    }

    @Test
    @DisplayName("⑤改配置就跟着变：档位不是写死在 java 里的")
    void sanitize_capComesFromConfig_notHardcoded() {
        // 刻意用一组与默认值完全不同的数，这样「产品代码写死了默认值」也会让本条变红
        sanitizeProperties.setChatMaxLength(7);
        sanitizeProperties.setRetrievalSegmentMaxLength(11);
        String probe = "x".repeat(50);

        Sanitized chat = sanitizer.sanitize(SourceKind.TRIGGER_CHAT, probe);
        Sanitized retrieval = sanitizer.sanitize(SourceKind.RETRIEVAL, probe);

        assertEquals(7, chat.value().length());
        assertEquals(7, chat.maxLength(), "生效的上限必须被记进元数据：只写「已截断」而不写上限是多少，"
                + "事后没法判断是配置被人调小了还是数据真超了");
        assertEquals(11, retrieval.value().length());
        assertEquals(11, retrieval.maxLength());
        assertTrue(chat.truncated());
    }

    // ==================== ⑥ 先剥后截（顺序） ====================

    @Test
    @DisplayName("⑥先剥后截：不可见字符不能把合法内容挤出上限")
    void sanitize_stripsBeforeTruncating_soPaddingCannotEvictRealContent() {
        // 🔴 这一条专门抓「写反顺序」。样本必须覆盖这个自由度，否则反了也是绿的：
        //    10 个零宽空格 + 8 个可见字符，上限设 10。
        //    先剥后截 → 剥完剩 8 个可见字符，8 ≤ 10，一个不丢；
        //    先截后剥 → 截断先切走前 10 个（全是零宽空格），再剥 ⟹ 合法内容一个字都不剩。
        sanitizeProperties.setWebhookBodyMaxLength(10);
        String raw = ZWSP.repeat(10) + "ABCDEFGH";

        Sanitized r = sanitizer.sanitize(SourceKind.TRIGGER_WEBHOOK, raw);

        assertEquals("ABCDEFGH", r.value(),
                "填充用的不可见字符不许把真实载荷挤出上限——那是一种零成本的「毁内容」攻击");
        assertFalse(r.truncated(), "剥完之后只有 8 个字符，压根没到上限，不该报截断");
        assertEquals(10, r.strippedChars());
    }

    @Test
    @DisplayName("⑥上限约束的是「剥完之后的可见字符数」，堆不可见字符撑不破它")
    void sanitize_capAppliesToVisibleCharsAfterStripping() {
        sanitizeProperties.setToolResultMaxLength(10);
        // 20 个可见字符 + 30 个零宽空格穿插。剥完剩 20 > 10 ⟹ 截到 10
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append((char) ('a' + i)).append(ZWSP);
        }
        sb.append(ZWSP.repeat(10));

        Sanitized r = sanitizer.sanitize(SourceKind.TOOL_RESULT, sb.toString());

        assertEquals(10, r.value().length(), "进 state 的可见字符数必须恰好等于上限");
        assertEquals("abcdefghij", r.value());
        assertTrue(r.truncated());
        assertEquals(30, r.strippedChars());
        assertEquals(50, r.originalLength(), "原长也要记下来，否则「截了多少」无从复核");
    }

    // ==================== ⑦ 绝不写带内标记 ====================

    @Test
    @DisplayName("⑦截断后长度恰好等于上限：值里没有塞任何带内标记")
    void sanitize_writesNoInBandMarker() {
        sanitizeProperties.setChatMaxLength(10);
        String raw = "x".repeat(100);

        Sanitized r = sanitizer.sanitize(SourceKind.TRIGGER_CHAT, raw);

        // 🔴 这一条钉的是设计决定：OutputModerator（输出侧）会把「…（已截断）」写进值里，
        //    因为那份值的唯一去处是给人看；输入侧的值还要去当工具入参、去写业务库，
        //    写进去就是脏数据。故这里必须是硬截断，长度恰好等于上限而不是「上限 + 后缀」
        assertEquals(10, r.value().length(), "长度必须恰好是上限，多出来的就是被塞进去的标记");
        assertFalse(r.value().contains("截断"), "值里不该出现任何提示文案");
        assertFalse(r.value().contains("⚠"), "值里不该出现任何警示符号");
        assertFalse(r.value().contains("【"), "值里不该出现任何包裹用的方括号");
        assertEquals("x".repeat(10), r.value());
        // 「被截断了」这件事只能出现在元数据里，由投递面自己决定要不要告诉人
        assertTrue(r.truncated());
        assertTrue(r.describe().contains("已截断"), "元数据要能把这件事说成人话，供日志/投递面使用");
    }

    // ==================== ⑧ 干净输入零改动 + 嵌套载荷 ====================

    @Test
    @DisplayName("⑧干净输入返回同一实例：正常的业务数据一个字符都不改")
    void sanitize_cleanInputIsUntouched() {
        String raw = "{\"orderId\":\"A-1024\",\"amount\":99.5,\"备注\":\"中文、English、123！\"}";

        Sanitized r = sanitizer.sanitize(SourceKind.TRIGGER_WEBHOOK, raw);

        assertSame(raw, r.value());
        assertFalse(r.modified());
        assertEquals(SourceKind.TRIGGER_WEBHOOK, r.source());
        assertTrue(r.describe().contains("未修改"), "「未修改」要明说，返回空串读起来像出了 bug");
    }

    @Test
    @DisplayName("⑧null 原样返回 null：不替调用方编一个空串")
    void sanitize_nullStaysNull() {
        Sanitized r = sanitizer.sanitize(SourceKind.TRIGGER_CHAT, null);

        assertNull(r.value());
        assertFalse(r.modified());
        assertEquals(0, r.originalLength());
    }

    @Test
    @DisplayName("⑧嵌套载荷逐个叶子处置，且不改写入参树")
    void sanitizePayload_recursesAndDoesNotMutateInput() {
        sanitizeProperties.setRetrievalSegmentMaxLength(6);
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("text", "x".repeat(20));
        inner.put("score", 0.87);
        inner.put("flag", true);
        List<Object> list = new ArrayList<>();
        list.add("a" + ZWSP + "b");
        list.add(inner);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("raw", "订单" + tag('H') + "已支付");
        payload.put("items", list);
        Map<String, Object> snapshot = deepCopyForAssertion(payload);

        Map<String, Object> out = sanitizer.sanitizePayload(SourceKind.RETRIEVAL, payload);

        // 入参树一个字符都没被改（它可能是 DSL 里那个活 map）
        assertEquals(snapshot, payload, "入参树在任何深度上都不许被改写");
        assertNotSame(payload, out);
        assertEquals("订单已支付", out.get("raw"));
        @SuppressWarnings("unchecked")
        List<Object> outList = (List<Object>) out.get("items");
        assertEquals("ab", outList.get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> outInner = (Map<String, Object>) outList.get(1);
        assertEquals("xxxxxx", outInner.get("text"), "嵌套两层的字符串叶子同样要过档位");
        // 非字符串叶子原样透传：它们没有藏控制字符的能力
        assertEquals(0.87, outInner.get("score"));
        assertEquals(true, outInner.get("flag"));
    }

    @Test
    @DisplayName("⑧超过深度上限的子树被丢弃（返回 null），不是原样放行")
    void sanitizePayload_dropsSubtreeBeyondMaxDepth() {
        sanitizeProperties.setMaxDepth(3);
        // 造 6 层嵌套：depth 1=顶层值, 2, 3 允许；4 起丢弃
        Map<String, Object> deepest = new LinkedHashMap<>();
        deepest.put("leaf", "藏在这里的" + ZWSP + "控制字符");
        Map<String, Object> l5 = new LinkedHashMap<>();
        l5.put("d6", deepest);
        Map<String, Object> l4 = new LinkedHashMap<>();
        l4.put("d5", l5);
        Map<String, Object> l3 = new LinkedHashMap<>();
        l3.put("d4", l4);
        Map<String, Object> l2 = new LinkedHashMap<>();
        l2.put("d3", l3);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("d2", l2);
        payload.put("shallow", "这个在第 1 层，正常处置");

        Map<String, Object> out = sanitizer.sanitizePayload(SourceKind.TRIGGER_WEBHOOK, payload);

        assertEquals("这个在第 1 层，正常处置", out.get("shallow"));
        @SuppressWarnings("unchecked")
        Map<String, Object> outL2 = (Map<String, Object>) out.get("d2");
        @SuppressWarnings("unchecked")
        Map<String, Object> outL3 = (Map<String, Object>) outL2.get("d3");
        @SuppressWarnings("unchecked")
        Map<String, Object> outL4 = (Map<String, Object>) outL3.get("d4");
        // 🔴 maxDepth=3 ⟹ depth 1~3 允许，depth 4（也就是 d5 那一层）被丢弃。
        //    原样保留等于让一个没被处置过的值进 state —— 那是这道闸唯一不能有的形状，
        //    故这里必须是 null（丢弃）而不是那棵原样的子树
        assertTrue(outL4.containsKey("d5"), "键还在，只是值被丢了：丢掉与「这个字段本来就没有」必须可区分");
        assertNull(outL4.get("d5"), "超深子树必须被丢弃：无界递归抛的 StackOverflowError 不是 Exception，"
                + "执行层那些 catch(Exception) 一个都拦不住");
        // 且被丢弃的那棵子树里的内容一个字都没漏进结果
        assertFalse(String.valueOf(out).contains("藏在这里的"), "丢弃必须是真丢弃，不是「换个地方原样放着」");
    }

    @Test
    @DisplayName("⑧基本类型数组不会让这里抛 ClassCastException（那会打死图执行线程）")
    void sanitizePayload_primitiveArrayPassesThrough() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ints", new int[]{1, 2, 3});
        payload.put("strs", new String[]{"a" + ZWSP, "b"});

        Map<String, Object> out = sanitizer.sanitizePayload(SourceKind.TOOL_RESULT, payload);

        assertSame(payload.get("ints"), out.get("ints"), "int[] 不是 Object[]，只能原样透传");
        @SuppressWarnings("unchecked")
        List<Object> strs = (List<Object>) out.get("strs");
        assertEquals(List.of("a", "b"), strs);
    }

    private static Map<String, Object> deepCopyForAssertion(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) m;
                out.put(e.getKey(), deepCopyForAssertion(casted));
            } else if (v instanceof List<?> l) {
                List<Object> copy = new ArrayList<>();
                for (Object o : l) {
                    copy.add(o instanceof Map<?, ?> m2 ? deepCopyForAssertion(cast(m2)) : o);
                }
                out.put(e.getKey(), copy);
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

}
