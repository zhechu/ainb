package cn.enlev.ainb.module.agent.framework.agent.core.react;

import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.react.ReactJsonCodec.ExtractedJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReactJsonCodec} 的单元测试：JSON 抠取、解析失败回灌、截断诊断
 *
 * <h3>为什么这几件事值得测</h3>
 * 平台没有原生 function-calling / JSON-mode，结构化输出全靠「提示词约束 + 从自然语言里抠 JSON
 * + 解析失败回灌自修正」。这条链上每一环都踩过实测坑，且错了的表现都极具迷惑性：
 *
 * <ul>
 *   <li><b>抠取</b>：模型<b>不会</b>因为 prompt 写了「只输出 JSON」就真的只输出 JSON——它会先补一段
 *       「库里没有可复用的表，我来新建一张，字段设计如下：」再给结构。抠取逻辑错了，模型明明把活干对了，
 *       却因多说了两句话整轮作废，用户看到「AI 没能给出方案」。这是最冤的一种失败。</li>
 *   <li><b>回灌</b>：模型在 {@code "params":{"model":{…}}} 这里少写一个 {@code &#125;}，解析必然失败。
 *       若每轮只回灌「你无法解析，请只输出 JSON」，它会<b>连续三轮在同一位置照原样再错一遍</b>，
 *       预算烧光。解析器本就知道错在第几个字符——把出错点附近原文摆给它，一轮就能自愈。</li>
 *   <li><b>诊断</b>：截断（该调大 maxTokens）和写坏（该调 prompt）表现都是「解析失败」，处理方式相反。
 *       判据只能是 completionTokens 有没有顶到 maxTokens，不能靠「大括号配平吗」猜——那报过假案。</li>
 * </ul>
 *
 * <h3>为什么测 codec 而不是某个调用方</h3>
 * 抠取/诊断逻辑原先是线性 planner（{@code AgentPlannerServiceImpl}）的私有方法，增量7b 把 planner 删除、
 * 逻辑沉淀到这里（plan-as-workflow 生成器与 agent_loop 共用）。这些用例是钉住行为不漂移的护栏，
 * 故直接测 codec 的公有入口，不走反射。协议专属文案（{@code formatSpec}/{@code extraGuidance}）
 * 由调用方传入，本测试只验证「传进来的会被如实拼进回灌」，不绑定某个具体协议。
 *
 * @author 走进新时代
 */
public class ReactJsonCodecTest {

    // ==================== extractJsonObject：从夹杂文字的输出里抠出配平的 JSON ====================

    private ExtractedJson extract(String text) {
        return ReactJsonCodec.extractJsonObject(text);
    }

    private String jsonOf(String text) {
        ExtractedJson extracted = extract(text);
        assertNotNull(extracted, "应当能抠出 JSON：" + text);
        return extracted.json();
    }

    @Test
    @DisplayName("纯 JSON：原样返回，且判定为配平")
    public void testPureJson() {
        String json = "{\"type\":\"answer\",\"text\":\"好的\"}";
        ExtractedJson extracted = extract(json);
        assertEquals(json, extracted.json());
        assertTrue(extracted.balanced());
    }

    /**
     * 🔴 实测踩过的形态
     */
    @Test
    @DisplayName("🔴 前面有一段说明文字：要能抠出 JSON")
    public void testPrefixedByProse() {
        String text = """
                库里没有可复用的巡检相关表，我来新建一张。字段设计如下：

                - 设备编号：短文本
                - 巡检人：短文本

                {"type":"answer","summary":"建表"}
                """;
        assertEquals("{\"type\":\"answer\",\"summary\":\"建表\"}", jsonOf(text));
    }

    @Test
    @DisplayName("JSON 后面还有话：不能把尾巴一起吞进来")
    public void testTrailingProse() {
        String text = "{\"type\":\"answer\",\"text\":\"好的\"}\n\n以上，请确认。";
        assertEquals("{\"type\":\"answer\",\"text\":\"好的\"}", jsonOf(text));
    }

    @Test
    @DisplayName("代码围栏包着：也能抠出来")
    public void testCodeFence() {
        String text = "```json\n{\"type\":\"answer\",\"text\":\"好的\"}\n```";
        assertEquals("{\"type\":\"answer\",\"text\":\"好的\"}", jsonOf(text));
    }

    @Test
    @DisplayName("嵌套对象：要找配平的那个右括号，不是第一个")
    public void testNested() {
        String json = "{\"a\":{\"b\":{\"c\":1}},\"d\":2}";
        assertEquals(json, jsonOf(json));
    }

    /**
     * 🔴 建表注释里出现大括号是完全可能的
     */
    @Test
    @DisplayName("🔴 字符串内部的大括号不算数")
    public void testBraceInsideString() {
        String json = "{\"comment\":\"格式形如 {x}\",\"type\":\"answer\"}";
        assertEquals(json, jsonOf(json));
    }

    @Test
    @DisplayName("转义引号不能把字符串状态搞乱")
    public void testEscapedQuote() {
        String json = "{\"text\":\"他说\\\"你好\\\"\",\"type\":\"answer\"}";
        assertEquals(json, jsonOf(json));
    }

    @Test
    @DisplayName("被截断：返回残缺部分，判定为不配平")
    public void testTruncated() {
        String text = "{\"type\":\"answer\",\"summary\":\"建表\",\"steps\":[{\"tool\":\"datamo";
        ExtractedJson extracted = extract(text);
        assertNotNull(extracted);
        assertTrue(extracted.json().startsWith("{"));
        assertFalse(extracted.balanced(), "截断的输出不配平，调用方据此报「该调大 maxTokens」");
        assertEquals(text, extracted.json());
    }

    /**
     * 🔴 为什么不能靠「结尾是不是 }」判断截断 —— 这是原来那段代码的 bug，
     * 它会把截断误报成「模型格式不合法」，于是照着表象去改 prompt，越改越糊涂。
     *
     * <p>截断点落在一个内层对象的 {@code &#125;} 之后是完全可能的：
     * 输出正好停在 {@code {"columns":[{"columnName":"a"},} 这种位置。
     */
    @Test
    @DisplayName("🔴 截断点恰好在 } 之后：仍要判定为不配平，不能被结尾字符骗过")
    public void testTruncatedRightAfterBrace() {
        String text = "{\"type\":\"answer\",\"steps\":[{\"tool\":\"datamodel_create\",\"params\":{\"model\":{\"columns\":[{\"columnName\":\"a\"}}}}";
        ExtractedJson extracted = extract(text);
        assertNotNull(extracted);
        assertTrue(extracted.json().endsWith("}"), "这个用例的前提就是：结尾确实是 }");
        assertFalse(extracted.balanced(), "结尾是 } 也可能是截断，必须按配平判定");
    }

    @Test
    @DisplayName("完全没有 JSON：返回 null")
    public void testNoJson() {
        assertNull(extract("我不太确定你想要什么，能再说详细一点吗？"));
    }

    // ==================== quoteBarePlaceholders：把裸写在串外的 {{...}} 补上引号 ====================

    /**
     * 🔴 实测原文（{@code _scratch/bad_dsl.txt}）的最小复现：节点入参的占位符没加引号。
     * 两份独立产出（含一次故障转移弱模型）都写成了这个样子，且自修正救不回来。
     */
    private static final String BARE_PLACEHOLDER =
            "{\"nodes\":[{\"id\":\"inspect\",\"type\":\"tool\",\"name\":\"表单体检\","
                    + "\"config\":{\"toolId\":\"form_inspect\",\"args\":{\"id\":{{$.formDraft.id}}},"
                    + "\"outputVar\":\"formDraft\"}}]}";

    @Test
    @DisplayName("🔴 裸占位符补引号后能被真解析器吃下，且值就是那个占位符")
    public void testBarePlaceholderQuoted() {
        assertThrows(Exception.class, () -> JSONUtil.parseObj(BARE_PLACEHOLDER),
                "用例前提：这份原文确实解析不了");

        String fixed = ReactJsonCodec.quoteBarePlaceholders(BARE_PLACEHOLDER);
        String id = JSONUtil.parseObj(fixed).getJSONArray("nodes").getJSONObject(0)
                .getJSONObject("config").getJSONObject("args").getStr("id");
        assertEquals("{{$.formDraft.id}}", id,
                "补完引号必须正好是运行期 StateTemplateResolver 认的那种形态");
    }

    @Test
    @DisplayName("🔴 已经引好的占位符不能再补一层 —— 把对的改错比不改更糟")
    public void testAlreadyQuotedPlaceholderUntouched() {
        String json = "{\"config\":{\"prompt\":\"请用一句话汇总：{{$.getOut}}\",\"args\":{\"code\":\"{{$.input.code}}\"}}}";
        assertEquals(json, ReactJsonCodec.quoteBarePlaceholders(json));
    }

    @Test
    @DisplayName("一份里有多处裸占位符：全都要补（实测第二份就有两处，分属两个节点）")
    public void testMultipleBarePlaceholders() {
        String json = "{\"a\":{\"id\":{{$.draft.id}}},\"b\":{\"id\":{{$.draft.id}},\"n\":[{{$.x}},1]}}";
        String fixed = ReactJsonCodec.quoteBarePlaceholders(json);
        assertEquals("{\"a\":{\"id\":\"{{$.draft.id}}\"},\"b\":{\"id\":\"{{$.draft.id}}\","
                + "\"n\":[\"{{$.x}}\",1]}}", fixed);
    }

    /**
     * 转义引号若没被吃掉，串尾判断就会错位，后面的正文会被当成「串外」而遭补引号
     */
    @Test
    @DisplayName("转义引号不能把串内/串外判断搞乱")
    public void testEscapedQuoteDoesNotConfuseScan() {
        String json = "{\"note\":\"他写的是 \\\"{{$.x}}\\\" 这个\",\"id\":{{$.draft.id}}}";
        String fixed = ReactJsonCodec.quoteBarePlaceholders(json);
        assertEquals("{\"note\":\"他写的是 \\\"{{$.x}}\\\" 这个\",\"id\":\"{{$.draft.id}}\"}", fixed);
        assertEquals("{{$.draft.id}}", JSONUtil.parseObj(fixed).getStr("id"));
    }

    @Test
    @DisplayName("不含占位符：原样返回同一实例，不额外分配")
    public void testNoPlaceholderShortCircuit() {
        String json = "{\"type\":\"answer\",\"text\":\"好的\"}";
        assertSame(json, ReactJsonCodec.quoteBarePlaceholders(json));
    }

    /**
     * 被 maxTokens 截断的 DSL 正会长这样：占位符只写了个开头。
     * 此时该原样交给解析器报错，好让 {@code diagnose} 认出「这是截断」。
     */
    @Test
    @DisplayName("占位符没有收尾 }}：不炸、不硬补，留给解析器判截断")
    public void testUnterminatedPlaceholderLeftAlone() {
        String json = "{\"args\":{\"id\":{{$.formDraft.i";
        assertEquals(json, ReactJsonCodec.quoteBarePlaceholders(json));
    }

    // ==================== parseFailureHint：把出错位置附近的原文回灌给模型 ====================

    /**
     * 实测那份坏 JSON 的最小复现：{@code params} 少一个右括号，
     * 于是第二个 step 嵌进了第一个 step 里，Hutool 解析必然在相接处报错。
     */
    private static final String MISSING_BRACE = """
            {"type":"answer","summary":"建表并建表单",
            "steps":[{"tool":"datamodel_create","params":{"model":{"tableName":"biz_demo","columns":[]}},\
            {"tool":"form_save_draft","params":{"name":"demo","code":"demo","mainTable":"biz_demo","fields":[]}}]}""";

    /**
     * 用真实的 Hutool 解析异常驱动 {@code parseFailureHint}——只有真报错才带「at N [character」位置，
     * 才能验证「出错点附近原文被摆出来」这条自愈关键路径。
     */
    private String hintFromRealParseError(String text, String formatSpec, String extraGuidance) {
        Exception parseError = assertThrows(Exception.class, () -> JSONUtil.parseObj(text),
                "这个用例的前提是：这段 JSON 确实解析不了");
        return ReactJsonCodec.parseFailureHint(text, parseError, formatSpec, extraGuidance);
    }

    @Test
    @DisplayName("🔴 真解析错：提示带上出错点标记、附近原文、回显 formatSpec、追加 extraGuidance")
    public void testHintCarriesErrorPositionAndProtocol() {
        String formatSpec = "{\"type\":\"tool_calls\"|\"answer\", ...}";
        String extraGuidance = "循环里只调查、不改数据，需要写就交给后续流程。";
        String result = hintFromRealParseError(MISSING_BRACE, formatSpec, extraGuidance);

        assertTrue(result.contains("⟵出错点⟶"), "必须标出错在哪，实际：" + result);
        // 出错点就落在两个 step 相接处，附近那段原文必须真的被带上 ——
        // 只给一个字符下标是没用的，模型看不到自己写的那一段就改不对
        assertTrue(result.contains("form_save_draft"),
                "出错点附近应当能看到第二个 step，实际：" + result);
        assertTrue(result.contains(formatSpec), "formatSpec 应被如实拼进回灌，实际：" + result);
        assertTrue(result.contains(extraGuidance), "extraGuidance 应被追加，实际：" + result);
        assertTrue(result.contains("只"), "应强调「只输出一个 JSON 对象」，实际：" + result);
    }

    @Test
    @DisplayName("🔴 Jackson 报错（column: N）：也要带出错点标记 —— DslParser 走的就是 Jackson")
    public void testHintCarriesJacksonPosition() {
        // 实测疤：项目审核流程连错 6 轮出不了草稿。根因是 DSL 解析器 DslParser 用 Jackson
        // （ObjectMapper.readValue），报错形如「... line: 1, column: 643] ...」，
        // 而回灌定位原先只认 Hutool 的「at N [character」——永远匹配不到，出错点附近原文从没回灌给模型，
        // 于是模型每轮都在同一处把括号写坏。这里用一条真实形态的 Jackson 报错钉住「位置能被识别」。
        //
        // 🔴 这条用例原本是**空断言**，被自查抓出来的：它当时用的原文只有 66 个字符，
        //    而 HINT_WINDOW 是 60 —— 不管定位算出 19 还是 44，窗口都是 [0,66] 整份原文，
        //    `contains("nodes")` 恒真。也就是说「取第一个匹配还是最后一个匹配」这件真正要紧的事，
        //    它一个都判不出来（Jackson 报错里第一个 line/column 是辅助信息「for Array starting at」，
        //    **最后一个**才是出错点）。
        //    所以现在把两个候选位置拉远（column 20 与 column 450，相距 430 字符 ≫ 2×HINT_WINDOW），
        //    各埋一个只属于它的标记，并**双向**断言：该出现的出现、不该出现的不出现。
        String prefix = "{\"dslVersion\":\"1.0\"";
        assertEquals(19, prefix.length(),
                "构造前提：prefix 必须恰好 19 个字符，这样 column: 20 才落在 NEAR_FIRST 上；"
                        + "改了 prefix 就得同步改下面报错里的两个 column");
        StringBuilder sb = new StringBuilder(prefix).append("NEAR_FIRST");
        while (sb.length() < 449) {
            sb.append('x');
        }
        String text = sb.append("NEAR_LAST]}").toString();
        assertEquals(449, text.indexOf("NEAR_LAST"),
                "构造前提：NEAR_LAST 必须从下标 449 开始（= column 450 换算的 0-based 下标）");

        Exception jacksonError = new RuntimeException("JSON 解析失败：Unexpected close marker '}': "
                + "expected ']' (for Array starting at [Source: (byte[]); line: 1, column: 20])\n"
                + " at [Source: (byte[]); line: 1, column: 450] "
                + "(through reference chain: WorkflowDefDTO[\"nodes\"])");
        String result = ReactJsonCodec.parseFailureHint(text, jacksonError, "{\"dslVersion\":\"1.0\",...}", null);

        assertTrue(result.contains("⟵出错点⟶"),
                "Jackson 的 column 位置也必须被识别、摆出出错点附近原文，实际：" + result);
        assertTrue(result.contains("NEAR_LAST"),
                "🔴 该取的是**最后一个** line/column（真正的出错点）。若取成了第一个（辅助信息 "
                        + "「for Array starting at」那个 column: 20），这条就红 —— 回灌会把模型指到"
                        + "一个完全正确的位置上，比不给位置更害人。实际：" + result);
        assertFalse(result.contains("NEAR_FIRST"),
                "窗口跑到了第一个匹配（column: 20）附近 —— 那正是「取第一个而不是最后一个」的症状。实际：" + result);
        assertTrue(result.contains("不是合法的 JSON"), "仍要讲清这是 JSON 解析失败，实际：" + result);
    }

    @Test
    @DisplayName("🔴 Jackson 报错（(char N) 绝对下标）：多行 JSON 时不能把「行内列号」当成绝对下标")
    public void testHintPrefersJacksonAbsoluteCharOffsetOverColumn() {
        // 实测疤（2026-10-04，A4-d 建表编排连错 6 轮）：模型产的是<b>带缩进换行</b>的 DSL，
        // Jackson 报「Expecting property name ...: line: 18 column: 3 (char 1999)」。
        // 定位原先只认 column，于是把那个「3」当成了绝对下标 —— 回灌窗口截的是整份 JSON 的<b>开头 60 个字符</b>
        // （{"dslVersion":"1.0","id":…），离真正的出错点差了两千个字符。
        // 模型收到一份「指着开头说这里错了」的提示，自然改不到点子上：
        // 它换了一整套方案重出，却在同一处又把括号数错，第 3~6 轮原文 md5 完全相同，白烧约 6.4 万 prompt token。
        //
        // 🔴 单行 JSON 时 column 恰好等于绝对下标（line: 1），所以这个 bug 只在模型换行排版时现形 ——
        //    上面那条 column 用例仍然是绿的，看不出问题。两种排版都得对，故这里刻意构造多行场景。
        //
        // ⚠️ 但**别把这条当成「多行 JSON 已修好」的证据**：它用的 {@code (char N)} 形态在生产上
        //    根本不会出现 —— 本项目 {@code StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION} 是关闭的，
        //    Jackson 只给 {@code Source: REDACTED ... line: L, column: C}。
        //    钉住生产形态的是下面那条 testHintLocatesProductionShapedLineColumnOnMultilineJson；
        //    曾经只有本条，于是真缺陷（只有 line/column 且多行）被它掩盖了一整轮。
        String nearError = "COLUMNS_CLOSE_HERE";
        String head = "HEADMARK{\"dslVersion\":\"1.0\",\n" + " ".repeat(1999 - "HEADMARK{\"dslVersion\":\"1.0\",\n".length());
        assertEquals(1999, head.length(), "用例前提：填充后出错点应正好落在下标 1999");
        String text = head + nearError + ",\"outputVar\":\"createOut\"}]}";

        Exception jacksonError = new RuntimeException("JSON 解析失败：Expecting property name "
                + "enclosed in double quotes: line: 18 column: 3 (char 1999)");
        String result = ReactJsonCodec.parseFailureHint(text, jacksonError, "{\"dslVersion\":\"1.0\",...}", null);

        assertTrue(result.contains("⟵出错点⟶"), "位置必须被识别，实际：" + result);
        // 判据是「窗口里有没有出错点附近的原文」，而不是「有没有位置数字」：
        // 只有真按 (char 1999) 截窗，才可能带上这个标记；按 column:3 截窗带上的必然是开头的 HEADMARK
        assertTrue(result.contains(nearError),
                "回灌窗口必须落在 (char 1999) 附近，实际：" + result);
        assertFalse(result.contains("HEADMARK"),
                "不能退化成截整份 JSON 的开头（那正是把 column 当绝对下标的症状），实际：" + result);
    }

    @Test
    @DisplayName("🔴 生产真实形态（Source REDACTED、没有 (char N)、多行）：仍须定位到出错点")
    public void testHintLocatesProductionShapedLineColumnOnMultilineJson() {
        // 🔴 上面那条 (char 1999) 用例钉的是一条**生产上走不到**的路径：
        //    本项目 StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION 是关闭的，Jackson 压根不输出 (char N)。
        //    只有它一条，会给人「多行 JSON 已经修好了」的虚假安全感 —— 而真缺陷原封不动。
        // 实测（2026-10-04 run2 第 1 轮）报错原文就是下面这个样子：Source 被 REDACTED、只有 line/column，
        // 而模型产的 DSL 是 36 行带缩进的。只读 column 会把那个「3」当成绝对下标，指着 JSON 开头说这里错了。
        String marker = "MARKER_ON_LINE_18";
        StringBuilder sb = new StringBuilder("{\n");
        for (int i = 0; i < 16; i++) {
            sb.append("  \"pad").append(i).append("\":0,\n");
        }
        sb.append("  ").append(marker).append("\n}");
        String text = sb.toString();
        // 用例前提：marker 必须正好落在第 18 行第 3 列，否则这条测试测的不是它声称的东西
        String[] lines = text.split("\n");
        assertEquals("  " + marker, lines[17], "用例前提：marker 应在第 18 行、前面两个空格（即第 3 列）");

        Exception jacksonError = new RuntimeException("JSON 解析失败：Unexpected character ('{' (code 123)): "
                + "was expecting double-quote to start field name  at [Source: REDACTED "
                + "(`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 18, column: 3] "
                + "(through reference chain: cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl"
                + ".WorkflowDefDTO[\"nodes\"]->java.util.ArrayList[4])");
        String result = ReactJsonCodec.parseFailureHint(text, jacksonError, "{\"dslVersion\":\"1.0\",...}", null);

        assertTrue(result.contains("⟵出错点⟶"), "位置必须被识别，实际：" + result);
        assertTrue(result.contains(marker),
                "必须按 line:18 + column:3 走位换算到绝对下标，实际：" + result);
        assertFalse(result.contains("pad0"),
                "不能退化成截 JSON 开头（那正是把行内列号当绝对下标的症状），实际：" + result);
    }

    @Test
    @DisplayName("🔴 少一个 } 时：必须说清缺的括号在出错点**之前**，并给出可核对的计数")
    public void testHintSaysMissingCloserIsBeforeErrorPoint() {
        // 复刻 run2 的真实形态：tool 节点 config→args→model→columns[] 四层嵌套少闭合了**节点自己**那个 }，
        // Jackson 却要到 900 字符之后「下一个节点开头的 {」才察觉。
        // 于是回灌窗口给模型看到的是：…}}, ⟵出错点⟶ {"id":"approve",… —— 左右两边都挑不出毛病，
        // 模型无从下手，第 3 轮一字不差地重出同一份，一路撞到定点检测才止损。
        // 光有「出错点附近 60 字符」对这类错是**无效回灌**，必须额外给出「往前数括号」这个可核对的判据。
        String text = "{\"nodes\":[{\"id\":\"create_table\",\"config\":{\"args\":{\"model\":{\"columns\":[]}}},"
                + "{\"id\":\"approve\"}]}";
        int pos = text.indexOf("{\"id\":\"approve\"");
        assertTrue(pos > 0, "用例前提：文本里要有第二个节点");

        Exception jacksonError = new RuntimeException("JSON 解析失败：Unexpected character ('{' (code 123)): "
                + "was expecting double-quote to start field name  at [Source: REDACTED "
                + "(`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: "
                + (pos + 1) + "]");
        String result = ReactJsonCodec.parseFailureHint(text, jacksonError, "{\"dslVersion\":\"1.0\",...}", null);

        assertTrue(result.contains("⟵出错点⟶"), "出错点仍要照旧标出来，实际：" + result);
        // 计数必须是**算出来的事实**，且要与实测那份坏 JSON 一致（未闭合 { = 2、[ = 1）
        assertTrue(result.contains("未闭合的 { 有 2 个"), "要给出未闭合花括号数，实际：" + result);
        assertTrue(result.contains("[ 有 1 个"), "要给出未闭合方括号数，实际：" + result);
        assertTrue(result.contains("漏掉了 1 个 }"), "要算出到底缺几个，实际：" + result);
        assertTrue(result.contains("**之前**"), "必须说清缺的括号在出错点之前，实际：" + result);
        assertTrue(result.contains("别去改出错点附近那一段"),
                "窗口里那段本身是对的，必须明说别改它，否则模型会在正确的那段上瞎改，实际：" + result);
    }

    @Test
    @DisplayName("没有多开的花括号时：不硬给「缺 }」的结论（宁缺毋滥）")
    public void testHintOmitsMissingCloserWhenNothingIsOverOpen() {
        // 根对象还开着、就在它里面直接写了个 { —— 未闭合的 { 只有 1 个（根对象自己），属正常，
        // 报错点**就是**出错点。此时硬套「前面少了 }」会把一个本来指得准的定位说成不准，
        // 把模型往别处引 —— 那比不补更害人（先前 790/8000 报假案是同一类危害）。
        String text = "{\"a\":1,{\"b\":2}}";
        int pos = text.indexOf("{\"b\"");
        Exception jacksonError = new RuntimeException("JSON 解析失败：Unexpected character ('{' (code 123)): "
                + "was expecting double-quote to start field name  at [Source: REDACTED; line: 1, column: "
                + (pos + 1) + "]");
        String result = ReactJsonCodec.parseFailureHint(text, jacksonError, "{}", null);

        assertTrue(result.contains("⟵出错点⟶"), "位置本身要照旧标出来，实际：" + result);
        assertFalse(result.contains("未闭合的 {"), "没有多开的花括号时不该硬给计数结论，实际：" + result);
        assertFalse(result.contains("不是缺括号的地方"), "同上，不该出现那段补充说明，实际：" + result);
    }

    @Test
    @DisplayName("报错里没有位置信息时，不能炸，仍要给出基本提示")
    public void testHintWithoutPosition() {
        String result = ReactJsonCodec.parseFailureHint(
                "{\"a\":1}", new RuntimeException("就是不对"), "{\"type\":\"answer\"}", null);
        assertFalse(result.contains("⟵出错点⟶"), "无位置信息时不该硬造出错点标记");
        assertTrue(result.contains("不是合法的 JSON"), "仍要讲清这是 JSON 解析失败，实际：" + result);
        assertTrue(result.contains("就是不对"), "原始异常信息应被带上，实际：" + result);
    }

    @Test
    @DisplayName("extraGuidance 为空：不追加，但 formatSpec 仍在")
    public void testHintWithoutExtraGuidance() {
        String result = hintFromRealParseError(MISSING_BRACE, "{\"type\":\"answer\"}", "  ");
        assertTrue(result.contains("{\"type\":\"answer\"}"), "formatSpec 应被拼进回灌");
        assertTrue(result.contains("⟵出错点⟶"), "位置标记与 extraGuidance 无关，仍应存在");
    }

    // ==================== diagnose：区分「被截断」还是「模型写坏了」 ====================

    /**
     * 🔴 判据只能是 completionTokens 顶没顶到 maxTokens。
     * 两者表现都是解析失败，但截断要调大 maxTokens、写坏才该调 prompt —— 处理完全相反。
     */
    @Test
    @DisplayName("🔴 completionTokens 顶到 maxTokens：判定为被截断，该调大 max-tokens")
    public void testDiagnoseTruncated() {
        ExtractedJson unbalanced = new ExtractedJson("{\"type\":\"answer\",\"text\":\"说到一半", false);
        String result = ReactJsonCodec.diagnose(unbalanced, 8000, 8000);
        // 🔴 断言必须钉「已顶到」而不是「截断」：反向分支的原文是「所以**不是截断**」，
        //    它同样含「截断」二字 —— contains("截断") 在判据完全反转时也绿，等于没断言。
        //    这个空断言是被变异探针抓出来的（把 TRUNCATION_BAND 调回 1.0，它照样过）。
        assertTrue(result.contains("已顶到"), "顶到上限就该说「已顶到」，实际：" + result);
        assertFalse(result.contains("未顶到"), "不能同时出现反向结论，实际：" + result);
        assertTrue(result.contains("max-tokens"), "要给出「调大 max-tokens」的处置建议，实际：" + result);
    }

    /**
     * 🔴 这两条不是「判据正确」的证明，是「判据在思考模型上无法正确」的钉子（方案 §6.3-23：验不了就明说验不了）。
     *
     * <p>背景：思考模式下 DashScope 的 {@code max_tokens} 只约束回复、思考另有 budget，
     * 回传的 {@code completion_tokens = 思考 + 回复}，<b>可以超过 maxTokens</b>。
     * 于是「completionTokens 顶没顶到 maxTokens」这个判据在两个方向上都会误判，
     * 而现有 DTO 拿不到 {@code finish_reason}（全 ai 模块搜不到 {@code finishReason}），无从分辨。
     *
     * <p>🔴 下面那批实测数采自「{@code AiUtils.buildChatOptions} 对通义硬开
     * {@code enableThinking(true)}」的年代；该硬开已改成可配置，agent 的 PLAN 路径现在显式关思考。
     * 但这两条<b>不要跟着删</b>：本 codec 的另两个调用方 {@code AgentChatToolLoop} 与
     * {@code AgentLoopGraphBuilder} 没关思考，判据在它们身上依旧不成立。详见 {@code ReactJsonCodec} 同名小节。
     *
     * <p>把它们钉成测试的目的不是断言结论对，而是：<b>一旦哪天有人把 finish_reason 透传上来、
     * 判据换成权威信号，这两条会立刻变红</b>，提醒改写的人「这里曾经只能猜，现在不用猜了」。
     */
    @Test
    @DisplayName("🔴 实测 7977/8000 + 大括号不配平：判成「写坏了」，但这结论无法证实也无法证伪")
    public void testDiagnoseNearMissIsUnverifiable() {
        // 实测疤（2026-10-04 无头建表编排 run2 第 1 轮）：completion 7977、上限 8000，差 0.29%，
        // 且 JSON 大括号不配平（输出停在结构中间）。
        // 两种解释都自洽：① 回复真被掐断，思考很少；② 思考吃掉一大半、回复写完了但少个括号。
        // 曾经据此把判据放宽成「达上限 95% 即算截断」，后来发现同一轮编排里第 3 轮 completion=12315
        // 已经**超过** 8000 —— 说明这个数根本不受上限约束，逼近上限不携带任何信息，改动已回退。
        ExtractedJson unbalanced = new ExtractedJson(
                "{\"dslVersion\":\"1.0\",\"nodes\":[{\"id\":\"create_table\",\"args\":{\"model\":{", false);
        String result = ReactJsonCodec.diagnose(unbalanced, 7977, 8000);
        assertTrue(result.contains("未顶到"), "现状：严格 >= 判它没顶到，实际：" + result);
        assertTrue(result.contains("写坏"), "现状：于是归因为模型写坏 JSON，实际：" + result);
        assertFalse(result.contains("max-tokens"), "现状下不会建议调大 max-tokens，实际：" + result);
    }

    @Test
    @DisplayName("🔴 completionTokens 超过 maxTokens（实测 12315 > 8000）：判「截断」是已知假阳性，必须自带警示")
    public void testDiagnoseOverCapCarriesThinkingCaveat() {
        // 实测疤（同上 run2 第 3 轮）：completion=12315 而 maxTokens=8000。
        // 上限若真约束这个数，12315 不可能出现 —— 它就是「含思考 token」的铁证。
        // 此时回复完全可能已经写完（只是括号写坏了），判「截断、去调大 max-tokens」是假阳性，
        // 照它去调配置调多大都不会好，正是先前 790/8000 那桩假案的同一类危害。
        // 拿不到 finish_reason 之前消不掉这个假阳性，但至少结论里必须自带警示，
        // 不能让人把它当成厂商给出的事实。
        ExtractedJson unbalanced = new ExtractedJson("{\"dslVersion\":\"1.0\",\"nodes\":[{\"id\":\"a\"", false);
        String result = ReactJsonCodec.diagnose(unbalanced, 12315, 8000);
        assertTrue(result.contains("已顶到"), "现状：超过上限就判截断，实际：" + result);
        assertTrue(result.contains("思考"),
                "必须自报「这个计数含思考 token，可能虚高」，否则读的人会当成厂商给的事实，实际：" + result);
    }

    @Test
    @DisplayName("completionTokens 远未顶到 maxTokens：不是截断，是模型把 JSON 写坏了")
    public void testDiagnoseWroteBad() {
        // 配平与否只作佐证：这里给一份不配平、但 completionTokens 很小的输出，
        // 正是先前「按配平猜截断」报假案的形态 —— 结论必须是「写坏了」，不是「截断」
        ExtractedJson unbalanced = new ExtractedJson("{\"type\":\"answer\",", false);
        String result = ReactJsonCodec.diagnose(unbalanced, 790, 8000);
        assertTrue(result.contains("不是截断"), "completionTokens 离上限远，不能报截断，实际：" + result);
        assertTrue(result.contains("写坏"), "应判定为模型写坏 JSON，实际：" + result);
    }

    @Test
    @DisplayName("completionTokens 为 null（厂商没回传）：按未顶到处理，不炸")
    public void testDiagnoseNullTokens() {
        ExtractedJson balanced = new ExtractedJson("{\"type\":\"answer\"}", true);
        String result = ReactJsonCodec.diagnose(balanced, null, 8000);
        assertTrue(result.contains("不是截断"), "null token 无法证明截断，按未截断处理，实际：" + result);
        assertTrue(result.contains("配平"), "配平状态应作为佐证写进诊断，实际：" + result);
    }

}
