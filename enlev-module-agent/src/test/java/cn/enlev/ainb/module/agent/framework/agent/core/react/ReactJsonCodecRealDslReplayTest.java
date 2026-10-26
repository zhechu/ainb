package cn.enlev.ainb.module.agent.framework.agent.core.react;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.react.ReactJsonCodec.ExtractedJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用<b>真实抓到的畸形 DSL 原文</b>离线回放「解析失败回灌定位」那两刀（P1-2）
 *
 * <h3>🔴 为什么要单开一个类，而不是往 {@code ReactJsonCodecTest} 里加用例</h3>
 * {@code ReactJsonCodecTest} 里所有定位用例的异常消息都是<b>手写的</b>
 * （形如 {@code new RuntimeException("... at 1872 [character 1814 line 2]")}）。
 * 手写消息只能证明「正则认得我自己编的那串字」，证不了「认得 Jackson 真吐出来的那串字」——
 * 而这两者恰恰<b>对不上过一次</b>：{@code PARSE_POSITION} 只认 Hutool 的 {@code at N [character} 写法，
 * 可 {@code DslParser} 走的是 Jackson（{@code ObjectMapper.readValue}），
 * 结果 planner 解析失败时永远匹配不到位置，那段「出错点附近原文」<b>从来没回灌给模型过</b>，
 * 模型每轮都在同一处把括号写坏、烧光 6 轮仍出不了草稿（实测疤：项目审核流程连错 6 轮）。
 *
 * <p>所以本类<b>不手写任何异常消息</b>：一律让真 {@link DslParser} + 真 {@link ObjectMapper}
 * 去解析真原文，再拿它抛出的那个异常去驱动 {@link ReactJsonCodec#parseFailureHint}。
 * 这样「Jackson 的报错格式」与「我们的定位正则」是被同一次运行串起来的，
 * 厂商/Jackson 版本一改格式，这里立刻变红 —— 手写消息的用例则会继续绿着骗人。
 *
 * <h3>原文的来历</h3>
 * 2026-10-04 无头建表编排真发 run2（A4-d），Planner 第 2、3 轮各吐出一份畸形 DSL，
 * 后端日志 {@code [parseDsl]} 把整段原文记了下来，固化在
 * {@code ainb-ui-admin-vue3/_evidence/a4d-run2-round-dsl.txt}，
 * 再原样拷进本模块 {@code src/test/resources/dsl-replay/}
 * （🔴 Java 测试不该反向去读前端仓库的文件，故拷一份而不是引用路径）。
 * 两轮的畸形形状<b>完全相同</b>：{@code preview_ddl} 节点的
 * {@code config→args→model→columns[]} 四层嵌套少数了一个 &#125;，
 * 于是 {@code nodes} 数组里下一个元素 {@code {"id":"approve"…} 的 &#123; 被 Jackson 当成字段名开头。
 * 第 3 轮（单行、4038 字符）与第 2 轮（多行）逐字同形，
 * 是一路撞到定点检测才止损的 —— 也就是说<b>回灌确实没起作用</b>。
 *
 * <h3>🔴 本方案验不了的那一环，明写在这里（§2-9）</h3>
 * 这两刀只保证「<b>回灌给模型的提示里，定位与括号数是对的</b>」。
 * <b>「模型看了这段提示之后是否真改对」这一环，离线回放验不了</b> ——
 * 那需要真发一轮、真花 token，且结果带随机性（同一条提示，这轮改对下轮也可能不改）。
 * 本类断言的是「该给的信息给全给对了」，不是「模型一定会采纳」。
 * 谁要拿本类的绿灯去宣称「自修正已经生效」，那是把话说过头了。
 *
 * @author 走进新时代
 */
class ReactJsonCodecRealDslReplayTest {

    /** 调用方（planner）传进来的协议形态说明；本类只验「原样拼进回灌」，不绑定具体协议文案 */
    private static final String FORMAT_SPEC =
            "{\"dslVersion\":\"1.0\",\"id\":\"…\",\"name\":\"…\",\"nodes\":[…],\"edges\":[…]}";

    /** 调用方追加的协议专属提醒；同样只验原样透传 */
    private static final String EXTRA_GUIDANCE =
            "🔴 你的括号没配平。请重新数一遍嵌套层级，尤其是多层嵌套的对象/数组收尾。";

    /** 出错点右边那一小段应当以它开头 —— 这是「定位落到了真位置」的判据 */
    private static final String NEXT_NODE = "{\"id\":\"approve\",\"type\":\"hitl.bpm_approval\"";

    /** 出错点左边那一小段应当以它结尾（{@code preview_ddl} 节点的收尾，本身是对的） */
    private static final String PREV_TAIL = "\"outputVar\":\"preview_result\"}},";

    private DslParser dslParser;

    @BeforeEach
    void setUp() {
        // 🔴 用裸 new ObjectMapper()，与 DslParserTest / WorkflowCopyRewriterTest 同款：
        //    项目里没有任何地方改过 INCLUDE_SOURCE_IN_LOCATION，Jackson 2.13+ 默认就是「REDACTED」，
        //    故裸 mapper 的报错格式与生产一致 —— 生产上真正走到的定位分支是 line/column，不是 (char N)
        dslParser = new DslParser();
        ReflectionTestUtils.setField(dslParser, "objectMapper", new ObjectMapper());
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = ReactJsonCodecRealDslReplayTest.class
                .getResourceAsStream("/dsl-replay/" + name)) {
            assertNotNull(in, "测试资源缺失：/dsl-replay/" + name);
            // 去掉尾部换行：日志里 content.length() 记的是模型原文长度，不含我们落盘时多出来的行尾
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("[\\r\\n]+$", "");
        }
    }

    /** 走一遍真实链路：抠 JSON → 真 Jackson 解析（必失败）→ 用真异常生成回灌提示 */
    private Replay replay(String text) {
        ExtractedJson extracted = ReactJsonCodec.extractJsonObject(text);
        assertNotNull(extracted, "原文里连 { 都找不到？资源拷坏了");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> dslParser.parse(extracted.json()),
                "🔴 阳性对照失效：这份原文居然被真 DslParser 解析成功了 —— "
                        + "那它就不是坏 DSL，本类证不了任何事。请核对资源文件是否被改过");
        String hint = ReactJsonCodec.parseFailureHint(text, ex, FORMAT_SPEC, EXTRA_GUIDANCE);
        String diagnosis = ReactJsonCodec.diagnose(extracted, 790, 8000);
        return new Replay(extracted, ex, hint, diagnosis);
    }

    private record Replay(ExtractedJson extracted, ServiceException ex, String hint, String diagnosis) {
    }

    // ==================== 第 2 轮（多行，4129 字符）====================

    @Test
    @DisplayName("🔴 真发 run2 第 2 轮原文：真 Jackson 报「该写字段名却出现 {」，定位落在 approve 节点开头")
    void round2Multiline_locatesRealErrorPosition() throws Exception {
        String text = resource("a4d-run2-round1-malformed.json");
        Replay r = replay(text);

        // ① 真异常的消息确实是这一类错（不是我们手编的），否则 missingCloserHint 压根不会触发
        assertTrue(r.ex().getMessage().contains("JSON 解析失败"), "应是 DslParser 包装过的消息：" + r.ex().getMessage());
        assertTrue(r.ex().getMessage().contains("expecting double-quote to start field name"),
                "🔴 Jackson 的报错文案变了？那 FIELD_NAME_EXPECTED 正则与本用例都得跟着改。实际："
                        + r.ex().getMessage());

        // ② 定位真的落到了那个位置。这是本类的<b>核心</b>断言：
        //    旧 bug（正则只认 Hutool 的 at N [character）会让 pos==null，
        //    于是整个「出错位置附近」段落被跳过 —— 提示里连 ⟵出错点⟶ 都不会有
        assertTrue(r.hint().contains(" ⟵出错点⟶ "),
                "🔴 回灌里根本没有出错点标记 ⟹ 定位没匹配上（正是那桩「Jackson 报错认不出」的旧案）。提示：" + r.hint());
        int mark = r.hint().indexOf(" ⟵出错点⟶ ");
        String right = r.hint().substring(mark + " ⟵出错点⟶ ".length());
        assertTrue(right.startsWith(NEXT_NODE),
                "🔴 出错点右边不是 approve 节点的开头 ⟹ 定位指错了地方（旧退化症状是「指着 JSON 开头说这里错了」）。右边："
                        + right.substring(0, Math.min(80, right.length())));
        String left = r.hint().substring(0, mark);
        assertTrue(left.endsWith(PREV_TAIL + "\n  ") || left.endsWith(PREV_TAIL),
                "🔴 出错点左边不是 preview_ddl 的正常收尾 ⟹ 窗口截错了地方。左边末尾："
                        + left.substring(Math.max(0, left.length() - 80)));
    }

    @Test
    @DisplayName("🔴 真发 run2 第 2 轮原文：括号数点得对（未闭合 { 2 个、[ 1 个 ⟹ 漏掉 1 个 }）")
    void round2Multiline_countsUnclosedBracketsCorrectly() throws Exception {
        String text = resource("a4d-run2-round1-malformed.json");
        Replay r = replay(text);

        // 🔴 这两个数是整刀的价值所在：光给「出错点附近 60 字符」是<b>无效回灌</b> ——
        //    窗口里左边是个正常的节点结尾、右边是个完美的新节点，没有一处看起来是错的，
        //    模型无从下手（第 3 轮一字不差重出同一份，4038 字符全等）。
        //    补的是一个模型能自己核对的数：根级数组 nodes 里开始新元素时，未闭合的 { 本该恰好 1 个（只有根对象），
        //    这里是 2 个 ⟹ 前面漏了 1 个 }
        assertTrue(r.hint().contains("未闭合的 { 有 2 个、[ 有 1 个"),
                "🔴 括号数点错了，回灌给模型的就是个假定位（比没定位更害人）。提示：" + r.hint());
        assertTrue(r.hint().contains("所以前面漏掉了 1 个 }"),
                "🔴 没把「多出来的那个就是漏掉的 }」这层换算说出来。提示：" + r.hint());

        // 🔴 必须明确指出「缺括号在出错点<b>之前</b>」，否则模型会去改出错点附近那一段（那段本身是对的）
        assertTrue(r.hint().contains("**不是**缺括号的地方"),
                "提示要说清这个位置只是解析器<b>察觉</b>到不对的地方。提示：" + r.hint());
        assertTrue(r.hint().contains("数一下出错点**之前**的括号"), "提示：" + r.hint());
        assertTrue(r.hint().contains("别去改出错点附近那一段"), "提示：" + r.hint());

        // 调用方传进来的两段文案必须原样拼进回灌（codec 不吞、不改写）
        assertTrue(r.hint().contains(FORMAT_SPEC), "formatSpec 没被原样拼进回灌：" + r.hint());
        assertTrue(r.hint().contains(EXTRA_GUIDANCE), "extraGuidance 没被原样拼进回灌：" + r.hint());
    }

    @Test
    @DisplayName("🔴 真发 run2 第 2 轮原文：诊断为「写坏了」而非「被截断」—— 790/8000 那桩假案不许重演")
    void round2Multiline_diagnosesCorruptNotTruncated() throws Exception {
        String text = resource("a4d-run2-round1-malformed.json");
        Replay r = replay(text);

        // 🔴 这条钉的是「判据只能是 completionTokens 有没有顶到 maxTokens，不能靠大括号配平猜」。
        //    这份原文 4129 字符、大括号<b>不配平</b>，而 completionTokens 只有 790（上限 8000）——
        //    按「配平吗」推断会报成「maxTokens 不够」，那就是一桩假案：
        //    处置完全相反（截断要调大 maxTokens 并让模型写短点，写坏了才该去数括号）。
        //    真发 run2 第 3 轮就是被这个假诊断带偏过一次的。
        assertFalse(r.extracted().balanced(),
                "阳性对照：这份原文的大括号本就不配平（少一个 }），若配平了则资源被改过");
        assertFalse(r.diagnosis().contains("已顶到"),
                "🔴 790 远没顶到 8000，却被诊断成截断 —— 假案重演。诊断：" + r.diagnosis());
        assertTrue(r.diagnosis().contains("所以不是截断"), "诊断：" + r.diagnosis());
        assertTrue(r.diagnosis().contains("大括号不配平"), "诊断里要带上配平与否作为佐证：" + r.diagnosis());
        assertTrue(r.diagnosis().contains("属于模型把 JSON 写坏了"), "诊断：" + r.diagnosis());
    }

    // ==================== 第 3 轮（单行，4038 字符）====================

    /**
     * 🔴 单行那份必须单独回放一遍：它走的是<b>另一条</b>定位分支。
     *
     * <p>多行 JSON 的 {@code line: 18, column: 3} 要靠 {@code lineColumnToOffset} 逐行数换行符；
     * 单行 JSON 的 {@code line: 1, column: 2045} 直接就是 {@code column - 1}。
     * 两条分支只测一条，另一条烂了没人知道 —— 而模型换行与否<b>不由我们控制</b>
     * （planner 甚至会在截断时反过来要求它「整份 JSON 挤在一行里最省长度」），
     * 所以两种排版都会在生产上出现。
     *
     * <p>另外单行那份还有一条独立的退化风险：{@code JACKSON_POSITION}（光秃秃的 {@code column: N}）
     * 只在单行时才恰好等于绝对下标。它排在 {@code line: L, column: C} 之后，
     * 若顺序被换上去，多行 JSON 会把行内列号当绝对下标 —— 指着开头说「这里错了」。
     * 本用例与上面那条多行用例<b>成对</b>存在，正是为了钉住这个顺序。
     */
    @Test
    @DisplayName("🔴 真发 run2 第 3 轮原文（单行）：定位与括号数与多行那份完全一致")
    void round3SingleLine_sameDiagnosisAsMultiline() throws Exception {
        String text = resource("a4d-run2-round2-malformed.json");
        assertEquals(-1, text.indexOf('\n'), "这份原文本该是单行的（第 3 轮模型把整份 JSON 挤在了一行）");
        Replay r = replay(text);

        assertTrue(r.ex().getMessage().contains("expecting double-quote to start field name"),
                "实际：" + r.ex().getMessage());
        assertTrue(r.hint().contains(" ⟵出错点⟶ "), "单行分支没定位上。提示：" + r.hint());
        int mark = r.hint().indexOf(" ⟵出错点⟶ ");
        assertTrue(r.hint().substring(mark + " ⟵出错点⟶ ".length()).startsWith(NEXT_NODE),
                "🔴 单行 JSON 的列号被当成了别的东西 ⟹ 定位指错。提示：" + r.hint());
        assertTrue(r.hint().contains("未闭合的 { 有 2 个、[ 有 1 个"), "提示：" + r.hint());
        assertTrue(r.hint().contains("所以前面漏掉了 1 个 }"), "提示：" + r.hint());
        assertFalse(r.diagnosis().contains("已顶到"), "诊断：" + r.diagnosis());
    }

    /**
     * 🔴 两轮同形 ⟹ 第 3 轮就是「照原样重出」，这正是定点检测该拦住的形状。
     *
     * <p>这条把「回灌没起作用」这件事本身钉下来：两份原文去掉排版差异后<b>逐字相同</b>，
     * 说明模型第 3 轮一个字都没改。它是 planner 定点检测（与上一轮逐字节相同 ⟹ 直接抛，
     * 不再进 parseDsl）存在的理由，也是「无效回灌比没回灌更贵」的实证。
     */
    @Test
    @DisplayName("🔴 两轮原文去掉排版后逐字相同：第 3 轮是照原样重出，回灌确实没起作用")
    void bothRoundsAreTheSameCorruptDsl() throws Exception {
        String multiline = resource("a4d-run2-round1-malformed.json");
        String singleLine = resource("a4d-run2-round2-malformed.json");

        // 只归一化排版（换行与缩进），不动任何字符：两者的差异<b>仅</b>在于模型有没有换行缩进
        String normalize = multiline.replaceAll("\\s+", "");
        assertEquals(normalize, singleLine.replaceAll("\\s+", ""),
                "🔴 两轮原文在去掉空白后并不相同 ⟹ 它们不是「同一种错重犯两次」，"
                        + "本类关于定点检测的那段说明就不成立了。请重新核对 _evidence 里的原文");
        assertFalse(multiline.equals(singleLine), "排版确有差异（一份多行一份单行），否则上面那条归一化就是空转");
    }
}
