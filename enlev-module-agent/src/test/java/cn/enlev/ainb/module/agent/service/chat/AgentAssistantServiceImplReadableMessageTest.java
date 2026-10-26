package cn.enlev.ainb.module.agent.service.chat;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentChatProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_MESSAGE_EMPTY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AgentAssistantServiceImpl#toReadableMessage} 的<b>成因枚举</b>单测（台账 D4-29）
 *
 * <h3>缺陷是什么：一条报错串顶着另一个成因的名字出现</h3>
 * 这个方法原先有一支 {@code instanceof IllegalStateException → AGENT_NO_CHAT_MODEL}，
 * 把「没有可用的聊天模型，请联系管理员启用一个」回给<b>所有</b> {@code IllegalStateException}。
 * 而那一句的原始成因（旧的 {@code AiModelApiImpl.resolveChatModel}）早在 b0bdd0933b 就被删了 ——
 * 「一个聊天模型都没启用」现在走 {@code ServiceException(MODEL_ALL_UNAVAILABLE, …)}，
 * 由第一个分支原样透传，指引一个字没丢。于是那一支剩下的唯一作用是<b>贴错标签</b>：
 * 模型明明启用着、只是密钥没填，界面却说「没有可用的聊天模型」，
 * 管理员照着去反复启停一个本来就启用着的模型（红线 §10.2 第 7 条：不替 error 编病因）。
 *
 * <h3>🔴 判据不是「改好了」，而是「同一条报错串不再对应多个成因」</h3>
 * 所以本类<b>每个成因各造一次、各一条断言</b>，最后再断一次两两互不相同。
 * 修法是「ai 侧把密钥为空升成带码的 {@code ServiceException(API_KEY_BLANK)}，agent 侧删掉这一支」——
 * 两侧各有一半判据：文案互不相同在 {@code AiUtilsValidateApiKeyTest}（那边才准引 ai 的错误码，
 * 红线 §10.1 第 3 条禁止 agent 引 {@code ai.enums.ErrorCodeConstants}），
 * 本类只断<b>机制</b>：带码的异常原样透传、不带的落兜底、而兜底那句<b>不指认任何成因</b>。
 *
 * <p>⚠️ 于是「兜底那句被多个未知成因共用」<b>不违反</b>上面那条判据，理由要说清楚：
 * 判据要防的是「一句话让人照错方向排查」，而兜底那句压根不提任何成因 ——
 * 它把人推向日志，而不是推向某个配置项。见 {@link #genericFallbackNamesNoCause}。
 *
 * @author 走进新时代
 */
public class AgentAssistantServiceImplReadableMessageTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentAssistantServiceImpl assistantService;

    /**
     * 只用到这一个协作者（兜底文案里要印超时秒数），其余字段留 null 即可 ——
     * {@code toReadableMessage} 不碰它们。用 {@code @Spy} 而不是 {@code @Mock}：
     * 要的是它<b>真实的默认值</b>（120 秒），mock 会返回 0，于是断言变成在钉一个假数。
     */
    @Spy
    private AgentChatProperties chatProperties = new AgentChatProperties();

    /**
     * 兜底那句。
     *
     * <p>🔴 与产品源码里的字面量重复一次是<b>刻意的</b>：本类要断的正是「哪些成因会落到这句上」，
     * 若改成从源码里读出来比对，那「把兜底文案换成一句错标签」这种改法反而测不出来。
     * 改了文案这里会红，红的出路是回来看一下新文案有没有开始指认成因 —— 那正是想要的停顿。
     */
    private static final String GENERIC = "AI 助手暂时无法回答，请稍后重试";

    private String readable(Throwable error) {
        return ReflectionTestUtils.invokeMethod(assistantService, "toReadableMessage", error);
    }

    // ==================== ① 带码的异常：原样透传，一个字不改 ====================

    @Test
    @DisplayName("ServiceException 的 message 原样返回 —— 这就是「不替 error 编病因」的实现方式")
    void serviceExceptionMessageIsPassedThroughVerbatim() {
        // 故意用一段带书名号、括号、百分号与空格的串：任何「顺手规整一下」的改动都会在这里露出来
        String upstream = "模型(qwen3.5-plus)额度耗尽，本次调用判失败：403 「quota」exceeded 100%";
        assertEquals(upstream, readable(new ServiceException(1_040_001_005, upstream)),
                "上游把成因说清楚了，本方法唯一的职责就是一个字都别动");
    }

    @Test
    @DisplayName("本模块自己的码也一样透传（不因为「是自家码」就换成别的说法）")
    void ownServiceExceptionIsAlsoVerbatim() {
        ServiceException ex = new ServiceException(AGENT_MESSAGE_EMPTY);
        assertEquals(AGENT_MESSAGE_EMPTY.getMsg(), readable(ex));
    }

    // ==================== ② 超时：自成一句，且秒数跟着配置走 ====================

    @Test
    @DisplayName("TimeoutException 有自己的文案，且秒数取自配置而不是写死")
    void timeoutGetsItsOwnMessageFollowingConfig() {
        assertEquals("模型响应超时（超过 120 秒），请重试", readable(new TimeoutException()),
                "AgentChatProperties.streamTimeoutSeconds 的出厂值是 120");

        chatProperties.setStreamTimeoutSeconds(7);
        assertEquals("模型响应超时（超过 7 秒），请重试", readable(new TimeoutException()),
                "改了配置文案得跟着改，否则界面上那个秒数是假的");
    }

    // ==================== ③ 🔴 D4-29 的要害：IllegalStateException 不再被贴成「没有可用的聊天模型」 ====================

    /**
     * 🔴 这一条就是 D4-29 本身。
     *
     * <p>两个成因各造一次：一个是历史上真被贴错标签的那个（{@code AiUtils#validateApiKey} 的
     * 「apiKey 不能为空」，现已在 ai 侧升成带码的 {@code ServiceException(API_KEY_BLANK)}，
     * 走不到这里了 —— 但万一有人把它改回去，本条会红），
     * 一个是厂商 SDK 内部随手抛的 {@code IllegalStateException}。
     *
     * <p>⚠️ 断言不止「等于兜底那句」，还要断「不含『聊天模型』」：
     * 前者在兜底文案被改写时会红（那是另一件事），后者才是本条要守的东西。
     * 两条都留着，红的消息各自把人指向对的去处。
     */
    @Test
    @DisplayName("🔴 IllegalStateException 落到不指认成因的兜底，绝不再说「没有可用的聊天模型」")
    void illegalStateNoLongerBlamesModelAvailability() {
        for (String raw : new String[]{"apiKey 不能为空", "[AiBoChaWebSearchClient] 调用失败！"}) {
            String readable = readable(new IllegalStateException(raw));
            assertEquals(GENERIC, readable, "该落到兜底那句：" + raw);
            assertFalse(readable.contains("聊天模型"),
                    "🔴 D4-29 回归：这句话又在替「没有可用的聊天模型」这个成因说话了 —— "
                            + "而真实成因是「" + raw + "」，两者排查方向完全不同。实际文案：" + readable);
            assertFalse(readable.contains("启用"),
                "同上：「请启用一个」是给「模型没启用」那个成因的出路，别塞给别的成因：" + readable);
        }
    }

    @Test
    @DisplayName("兜底那句不许把异常原文吐出去（内部结构不该出现在界面上）")
    void unknownExceptionFallsBackWithoutLeakingInternals() {
        String readable = readable(new NullPointerException(
                "Cannot invoke \"AiModelFailoverServiceImpl.buildCandidateChain()\" because \"this.failoverService\" is null"));
        assertEquals(GENERIC, readable);
        assertFalse(readable.contains("AiModelFailoverServiceImpl"), "泄漏了内部类名：" + readable);
        assertFalse(readable.contains("NullPointerException"), "泄漏了异常类型：" + readable);
    }

    // ==================== ④ 成因枚举：两两互不相同 ====================

    /**
     * 本方法<b>能分辨</b>的三档成因，各造一次。
     *
     * <p>🔴 key 是成因、value 是用户会看见的那句话。刻意<b>只</b>放这三档：
     * 再往下的成因本方法分辨不了（都落兜底），把它们塞进来会让「两两互不相同」这条断言
     * 变成一句假话 —— 而假话比没有断言更坏（它让人以为覆盖到了）。
     */
    private Map<String, String> distinguishableCauses() {
        Map<String, String> causes = new LinkedHashMap<>();
        causes.put("上游把成因说清了（带码 ServiceException）",
                readable(new ServiceException(1_040_001_004, "没有可用的聊天模型，请在「AI 模型」里启用一个")));
        causes.put("模型响应超时", readable(new TimeoutException()));
        causes.put("成因不明（任何不带码的异常）", readable(new IllegalStateException("apiKey 不能为空")));
        return causes;
    }

    @Test
    @DisplayName("🔴 三档成因的文案两两互不相同 —— 「同一条报错串不再对应多个成因」")
    void distinguishableCausesHavePairwiseDistinctMessages() {
        Map<String, String> causes = distinguishableCauses();
        // 域自检：条数写死。少一档时下面那个两两循环「更绿」（少比一次），故先钉条数
        assertEquals(3, causes.size(), "档位被改动了？请确认新档位的话术没跟别人撞车");
        for (Map.Entry<String, String> a : causes.entrySet()) {
            for (Map.Entry<String, String> b : causes.entrySet()) {
                if (a.getKey().compareTo(b.getKey()) >= 0) {
                    continue;
                }
                assertFalse(a.getValue().equals(b.getValue()),
                        "两档成因共用了同一句话，用户无从分辨：\n  甲「" + a.getKey()
                                + "」\n  乙「" + b.getKey() + "」\n  共用文案：" + a.getValue());
            }
        }
    }

    /**
     * 🔴 兜底那句<b>不指认任何成因</b> —— 这是「多个未知成因共用一句」能成立的前提。
     *
     * <p>方案 §7.5 的判据写的是「报错串互不相同」，而本方法与 {@link #distinguishableCausesHavePairwiseDistinctMessages}
     * 一起把它落成了两半：<b>指认了成因的</b>必须互不相同；<b>不指认成因的</b>可以共用。
     * 分界线就是本条 —— 只要兜底那句开始提到某个具体成因（模型 / 密钥 / 额度 / 超时 / 去启用），
     * 它就又变成一句猜测，本条会红。
     */
    @Test
    @DisplayName("兜底那句不提任何成因，所以它可以被未知成因共用而不算误标签")
    void genericFallbackNamesNoCause() {
        for (String cause : new String[]{"模型", "密钥", "额度", "超时", "启用", "禁用", "webhook"}) {
            assertFalse(GENERIC.contains(cause),
                    "兜底文案里出现了「" + cause + "」，它就不再是「我不知道」而变成了一句猜测：" + GENERIC);
            assertFalse(readable(new IllegalStateException("x")).contains(cause),
                    "实际返回的兜底文案里出现了「" + cause + "」：" + readable(new IllegalStateException("x")));
        }
    }

    // ==================== ⑤ 静态判据：那一支不许悄悄回来 ====================

    /** 本模块的 main 源码根目录（与 {@code AgentArchitectureTest} 同口径） */
    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    private static final Path ASSISTANT_IMPL = SOURCE_ROOT.resolve(
            "cn/enlev/ainb/module/agent/service/chat/AgentAssistantServiceImpl.java");

    private static boolean isCommentLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    private static List<String> codeHits(Path file, String fragment) throws IOException {
        List<String> hits = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            // ⚠️ 只扫代码行：{@code toReadableMessage} 的 javadoc 里<b>必须</b>提到这两个串
            //    （那段注释就是「为什么刻意没有这一支」的说明书），把它们算成违规等于禁止解释
            if (!isCommentLine(line) && line.contains(fragment)) {
                hits.add(file.getFileName() + ":" + (i + 1) + "  " + line.trim());
            }
        }
        return hits;
    }

    /**
     * 🔴 删掉一支的代码，最安静的复活方式就是「过一阵子有人照着旧 commit 加回来」。
     * 运行期断言拦不住它（加回来之后，只要没人真造一次 {@code IllegalStateException}，③ 那些断言照样绿），
     * 所以这里补一条源码扫描。
     *
     * <p>🔴 带下限自检：文件被改名/挪走时 {@code Files.readAllLines} 会抛，但若哪天扫描路径写窄成
     * 一个不存在的目录、又被 try 吞掉，禁令就会「越删越绿」。故先钉住文件在、行数够、
     * 且<b>要守的那个方法确实还在里面</b> —— 三条都是「必须还存在」方向的断言，
     * 与「不许出现」那两条配成一对（口径同 {@code AgentArchitectureTest} 的反向自检）。
     */
    @Test
    @DisplayName("🔴 agent 侧不许再有第二句「没有可用的聊天模型」，toReadableMessage 里也不许再有 instanceof IllegalStateException")
    void agentMainHasNoSecondModelAvailabilityMessage() throws IOException {
        assertTrue(Files.isRegularFile(ASSISTANT_IMPL),
                "扫描目标不在了（改名/挪走？）：把 ASSISTANT_IMPL 跟着改到新位置，别把本条删掉让它闭嘴");
        List<String> lines = Files.readAllLines(ASSISTANT_IMPL, StandardCharsets.UTF_8);
        assertTrue(lines.size() >= 1000,
                "这个文件实测有 1500+ 行，只剩 " + lines.size() + " 行说明扫描路径或文件本身出问题了");
        assertTrue(lines.stream().anyMatch(l -> l.contains("private String toReadableMessage(Throwable error)")),
                "要守的那个方法不在文件里了 —— 本条禁令会因此假绿，请把它指到新位置");
        assertTrue(lines.stream().anyMatch(l -> l.contains("serviceException.getMessage()")),
                "「原样透传」那一支必须还在：它是 D4-29 的正解所在（ai 侧说清成因、agent 侧一个字不改）");

        assertEquals(List.of(), codeHits(ASSISTANT_IMPL, "instanceof IllegalStateException"),
                "🔴 toReadableMessage 里那一支回来了。它唯一的作用是给别的成因贴错标签，"
                        + "要区分成因请去 ai 侧升成带码的 ServiceException，别在这里按异常类型猜");

        List<String> constantHits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(SOURCE_ROOT)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toList()) {
                constantHits.addAll(codeHits(file, "AGENT_NO_CHAT_MODEL"));
            }
        }
        assertEquals(List.of(), constantHits,
                "🔴 AGENT_NO_CHAT_MODEL（1_041_003_002）已随 D4-29 删除且号段不复用；"
                        + "「没有可用的聊天模型」这个成因由 ai 侧 MODEL_ALL_UNAVAILABLE 自己说，"
                        + "agent 侧不许再有第二句去替它说");
    }

}
