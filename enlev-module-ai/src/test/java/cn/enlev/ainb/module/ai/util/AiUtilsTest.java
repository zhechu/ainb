package cn.enlev.ainb.module.ai.util;

import cn.enlev.ainb.module.ai.enums.model.AiPlatformEnum;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通义千问的路由分档单测：端点选择（{@link AiUtils#isTongYiMultiModel(String)}）
 * 与思考模式三档（{@link AiUtils#resolveTongYiThinking}）
 *
 * <p>归类错的后果是 400「url error, please check url！」或 400
 * {@code enable_thinking parameter is restricted to True} —— 都是一句话看不出根因、
 * 且只在真发请求时才出现的错，所以这里把实测过的模型逐个钉住。
 * 尤其 qwen3.7-max：它曾被按「qwen3.7 系列」整批归成多模态，导致线上通义千问对话全部失败；
 * 修好端点之后它又因为「文本端点 ⇒ 思考关」这条硬规则被判成必然被拒（模型 79，2026-10-10 实测）。
 *
 * <p>🔴 本类<b>不</b>拉 Spring 上下文，所以走的全是「配置缺失 ⇒ 回落内置清单」那条路。
 * 配置生效那条路由由 {@link #testRoutingTables_configuredSetReplacesBuiltIn()} 直接打纯函数覆盖。
 *
 * @author 走进新时代
 */
class AiUtilsTest {

    @Test
    void testIsTongYiMultiModel_textTrunk() {
        // 实测：qwen3.7-max 仅 text-generation 端点可用，multimodal 端点报 url error
        assertFalse(AiUtils.isTongYiMultiModel("qwen3.7-max"));
        // 快照版跟随基础版归类
        assertFalse(AiUtils.isTongYiMultiModel("qwen3.7-max-2026-05-26"));
        assertFalse(AiUtils.isTongYiMultiModel("qwen-max-latest"));
        assertFalse(AiUtils.isTongYiMultiModel("qwen-turbo"));
        assertFalse(AiUtils.isTongYiMultiModel("deepseek-r1"));
    }

    @Test
    void testIsTongYiMultiModel_multiModalTrunk() {
        // 实测：qwen3.7-flash / qwen3.7-plus-2026-05-26 仅 multimodal 端点可用
        assertTrue(AiUtils.isTongYiMultiModel("qwen3.7-flash"));
        assertTrue(AiUtils.isTongYiMultiModel("qwen3.7-plus"));
        assertTrue(AiUtils.isTongYiMultiModel("qwen3.7-plus-2026-05-26"));
        assertTrue(AiUtils.isTongYiMultiModel("qwen3-vl-plus"));
        assertTrue(AiUtils.isTongYiMultiModel("qwen-vl-max-latest"));
        assertTrue(AiUtils.isTongYiMultiModel("qwen3-omni-flash"));
    }

    @Test
    void testIsTongYiMultiModel_blank() {
        assertFalse(AiUtils.isTongYiMultiModel(null));
        assertFalse(AiUtils.isTongYiMultiModel(""));
        assertFalse(AiUtils.isTongYiMultiModel("  "));
    }

    /**
     * 不表态时按端点分档：多模态开、文本端点关
     *
     * <p>多模态那一档守的是实测约束不是偏好：{@code TongYiChatModelTests#testStream_thinking}
     * 在「qwen3.6-plus + multiModel(true) + 流式」上注着 enableThinking「必须设置，否则会报错」。
     * 两个模型名都是实测在用的：CHAT 落在 qwen3.5-plus 的快照版上，INTENT / PLAN 落在
     * qwen-plus-2025-07-28 上（走文本端点）。
     *
     * <p>⚠️ 本条<b>不再</b>断言 qwen3.7-max —— 它已被 {@link #testThinkingDefault_thinkingOnlyBeatsTextTrunk()}
     * 接管。原来这里写着 {@code assertFalse(thinking("qwen3.7-max", null))}，那条断言本身就是 A3 那个
     * 缺陷的编码：它把「文本端点 ⇒ 思考关」钉成了对 qwen3.7-max 也成立，而厂商逐字回的是
     * {@code enable_thinking parameter is restricted to True}。
     */
    @Test
    void testThinkingDefault_multiModelOn_textTrunkOff() {
        assertEquals(Boolean.TRUE, thinking("qwen3.5-plus-2026-02-15", null));
        assertEquals(Boolean.TRUE, thinking("qwen3.6-plus", null));
        assertEquals(Boolean.FALSE, thinking("qwen-plus-2025-07-28", null));
    }

    /**
     * 🔴 第二档：被厂商限死「必须开思考」的模型，压过「文本端点 ⇒ 思考关」
     *
     * <p>修的是<b>具体故障</b>不是假设：模型 79（{@code qwen3.7-max-2026-05-17}）就躺在库里，
     * 2026-10-10 用「测试」按钮实测，厂商原文逐字是
     * <pre>400 - enable_thinking parameter is restricted to True</pre>
     * 它是纯文本主干（{@link AiUtils#isTongYiMultiModel(String)} 返回 false，
     * 上面 {@link #testIsTongYiMultiModel_textTrunk()} 钉着），
     * 于是旧的两档判定给它发 {@code enable_thinking=false} —— 一个<b>永远不可能成功</b>的请求，
     * 而每 60 秒冷却到期后还要原样重演一遍（A2 之前）。
     *
     * <p>快照版必须跟着基础版走，理由与端点分档一致：快照名是阿里云逐月新增的。
     */
    @Test
    void testThinkingDefault_thinkingOnlyBeatsTextTrunk() {
        assertTrue(AiUtils.isTongYiThinkingOnly("qwen3.7-max"));
        assertTrue(AiUtils.isTongYiThinkingOnly("qwen3.7-max-2026-05-17"));
        // 同为纯文本主干，但没被限死 ⇒ 仍旧关思考。这条是「第二档没有把第三档整个吃掉」的判据
        assertFalse(AiUtils.isTongYiThinkingOnly("qwen-plus-2025-07-28"));

        assertEquals(Boolean.TRUE, thinking("qwen3.7-max", null));
        assertEquals(Boolean.TRUE, thinking("qwen3.7-max-2026-05-17", null));
        assertEquals(Boolean.FALSE, thinking("qwen-plus-2025-07-28", null));
    }

    /**
     * 🔴 纯函数版的档位判定：三档的<b>全部</b>八种组合
     *
     * <p>打这个包级方法而不是 {@code buildChatOptions}，是为了让档位表本身可读、可穷举 ——
     * 走 {@code buildChatOptions} 每条都要 cast 一次 {@code DashScopeChatOptions}，
     * 而且读不出「这一格是哪一档定的」。
     */
    @Test
    void testResolveTongYiThinking_allTiers() {
        // 第一档：显式表态，压过下面两档（含压过「限死必须开」）
        assertTrue(AiUtils.resolveTongYiThinking(true, false, false));
        assertFalse(AiUtils.resolveTongYiThinking(false, true, true));
        // 第二档：限死必须开，压过端点分档
        assertTrue(AiUtils.resolveTongYiThinking(null, false, true));
        assertTrue(AiUtils.resolveTongYiThinking(null, true, true));
        // 第三档：按端点
        assertTrue(AiUtils.resolveTongYiThinking(null, true, false));
        assertFalse(AiUtils.resolveTongYiThinking(null, false, false));
    }

    /**
     * 🔴 配置是<b>整体替换</b>内置清单，不是追加；显式空集合等于清空
     *
     * <p>钉这条是因为 {@code EnlevAiProperties.TongYi} 的两个字段默认 null（= 用内置清单），
     * 而 null 与 {@code []} 在 yaml 里长得像、语义完全不同。若哪天有人把「null 也当空」写进
     * {@code configuredMultiModels()}，所有多模态模型会被静默打到文本端点 —— 全模块报
     * 400「url error, please check url！」，而单测（无 Spring 上下文）一条都不会红。
     * 这条直接打纯函数，不依赖 Spring，因此<b>能</b>红。
     */
    @Test
    void testRoutingTables_configuredSetReplacesBuiltIn() {
        // 配了一张只含 qwen-turbo 的表：内置那 20 个全部失效
        Set<String> configured = Set.of("qwen-turbo");
        assertTrue(AiUtils.isTongYiMultiModel("qwen-turbo", configured));
        assertFalse(AiUtils.isTongYiMultiModel("qwen3.6-plus", configured),
                "配置整体替换内置清单，不是追加");
        // 快照后缀照旧剥：配的是基础名，命中的是快照名
        assertTrue(AiUtils.isTongYiMultiModel("qwen-turbo-2026-01-01", configured));
        assertTrue(AiUtils.isTongYiMultiModel("qwen-turbo-latest", configured));
        // 显式空集合 = 清空，一个都不算多模态
        assertFalse(AiUtils.isTongYiMultiModel("qwen3.6-plus", Set.of()));
        assertFalse(AiUtils.isTongYiThinkingOnly("qwen3.7-max", Set.of()));
        // 空白模型名不炸
        assertFalse(AiUtils.isTongYiMultiModel(null, configured));
        assertFalse(AiUtils.isTongYiThinkingOnly("  ", configured));
    }

    /**
     * 无 Spring 上下文时（本类就是），两个无参版必须回落到内置清单
     *
     * <p>上面几条打的是纯函数，这一条打的是<b>回落路径</b>本身：
     * {@code tongYiProperties()} 里那个 {@code catch (RuntimeException)} 就是为它写的
     * （hutool {@code SpringUtil.getBean} 在没有上下文时抛 NPE）。
     * 少了这个 catch，本类<b>每一条</b>用无参版的断言都会以 NPE 失败 ——
     * 而生产上有上下文，所以这个坑只会在单测里露头，很容易被误判成「测试环境问题」而绕过。
     */
    @Test
    void testRoutingTables_fallBackToBuiltInWithoutSpring() {
        assertTrue(AiUtils.isTongYiMultiModel("qwen3.6-plus"));
        assertFalse(AiUtils.isTongYiMultiModel("qwen3.7-max"));
        assertTrue(AiUtils.isTongYiThinkingOnly("qwen3.7-max-2026-05-17"));
        // 回落之后 buildChatOptions 也必须是同一份结论
        assertEquals(Boolean.TRUE, multiModel("qwen3.6-plus"));
        assertEquals(Boolean.TRUE, thinking("qwen3.7-max", null));
    }

    /**
     * 🔴 钉住「没有『不发这个字段』这一档」，免得后人照 {@code @JsonInclude(NON_NULL)} 重新设计一版
     *
     * <p>请求体上那个字段确实标了 NON_NULL，看着像「传 null 就能省略」；
     * 但 {@code DashScopeChatOptions} 在字段初始化里就把 enableThinking 赋成了
     * {@code Boolean.valueOf(false)}，builder 不调 setter 也拿不到 null。
     * 曾经按「null = 省略字段、走服务端默认」设计过一版，就是被这条打回来的。
     */
    @Test
    void testThinkingHasNoOmitState() {
        DashScopeChatOptions untouched = DashScopeChatOptions.builder().model("qwen-plus-2025-07-28").build();
        assertEquals(Boolean.FALSE, untouched.getEnableThinking(),
                "库自己的初值就是 false，不存在 null（= 省略字段）这一档");
    }

    /**
     * 🔴 显式表态必须压过默认档 —— 这是整个改动的着力点
     *
     * <p>agent 的 CHAT 路径用的正是多模态模型，默认档会给它开思考（实测单次 284 秒）。
     * 若显式 false 压不过默认档，那三条路径等于没改，而单测全绿。
     */
    @Test
    void testThinkingExplicit_overridesDefault() {
        assertEquals(Boolean.FALSE, thinking("qwen3.5-plus-2026-02-15", false));
        assertEquals(Boolean.FALSE, thinking("qwen3.6-plus", false));
        assertEquals(Boolean.FALSE, thinking("qwen-plus-2025-07-28", false));
        assertEquals(Boolean.TRUE, thinking("qwen3.7-max", true));
        // 🔴 第一档压过第二档：qwen3.7-max 被厂商限死「必须开思考」，但显式 false 仍旧照发。
        //    这不是漏判，是刻意的 —— agent 的 CHAT/INTENT/PLAN 三条路径就是显式传 false，
        //    静默改写成 true 会让「实测单次 284 秒」那条预算约束在没人知道的情况下失效。
        //    厂商因此回的 400 由 A2 判成 CONFIG：立刻切候选 + 30 分钟冷却，不重演风暴。
        assertEquals(Boolean.FALSE, thinking("qwen3.7-max", false));
    }

    /**
     * 🔴 钉住「端点选择」这根线本身 —— 上面几条钉的全是 {@code enableThinking}，
     * <b>没有一条</b>断言 {@code buildChatOptions} 真的把 {@link AiUtils#isTongYiMultiModel}
     * 的结果写进了 {@code multiModel}
     *
     * <p>少这根线不会有任何测试变红：把 {@code .multiModel(multiModel)} 那行删掉，
     * 分档测试照样全绿，而线上所有多模态主干的模型都会被打到<b>文本</b>端点，
     * 厂商回一句 400「url error, please check url！」—— 正是本类头注释里那条
     * 「一句话看不出根因、排查代价极高」的错，而且它只会在线上出现。
     *
     * <p>顺带钉住 agent CHAT 路径那个曾被担心「有被厂商直接拒的可能」的组合
     * （见 {@code AgentAssistantServiceImpl} 里 {@code setEnableThinking(false)} 上方的注释）：
     * <b>多模态端点 + 关思考</b>两个标志必须同时成立、互不覆盖。
     * 该组合已在 run9 / run12 两个独立窗口各真跑 40 次 CHAT、80/80 SUCCESS
     * （{@code agent_usage_log}，model 76 = {@code qwen3.5-plus-2026-02-15}），
     * 担心是多余的；这里把出参钉住，免得日后有人按那句注释把 {@code true} 加回去。
     *
     * <p>三个模型名都是实测在用的：CHAT 曾落 {@code qwen3.5-plus-2026-02-15}（多模态主干）、
     * 现落 {@code qwen3.7-max-2026-05-20}（文本主干，qwen3.7 系列里的例外），
     * INTENT / PLAN 落 {@code qwen-plus-2025-07-28}（文本主干）。
     */
    @Test
    void testMultiModelFlagFollowsClassification() {
        DashScopeChatOptions chat = (DashScopeChatOptions) AiUtils.buildChatOptions(
                AiPlatformEnum.TONG_YI, "qwen3.5-plus-2026-02-15", 0.1, 2000, null, null, false);
        assertEquals(Boolean.TRUE, chat.getMultiModel(), "CHAT 用的快照版必须走多模态端点");
        assertEquals(Boolean.FALSE, chat.getEnableThinking(), "同一次调用上思考必须是关的");

        assertEquals(Boolean.FALSE, multiModel("qwen-plus-2025-07-28"));
        assertEquals(Boolean.FALSE, multiModel("qwen3.7-max-2026-05-20"),
                "qwen3.7-max 是 qwen3.7 系列里的纯文本主干例外，快照版跟随基础版");
        assertEquals(Boolean.TRUE, multiModel("qwen3.7-plus-2026-05-26"));
    }

    /**
     * 两个旧重载必须与「不表态」逐位同结果
     *
     * <p>ai 模块自己那三个调用方（写作 / 思维导图 / 会话）走的就是旧重载，
     * 这条钉住它们不受本次改动影响 —— 否则「改了 agent、顺手改了别人」不会被发现。
     *
     * <p>🔴 循环里<b>刻意</b>含 qwen3.7-max：它命中第二档（限死必须开思考），
     * 所以这条同时证明「A3 那一档对旧重载也生效」—— 而写作 / 思维导图 / 会话
     * 三个调用方恰恰只用旧重载。少了它，A3 可能只修好了 7 参重载那一条路。
     */
    @Test
    void testThinkingLegacyOverloads_sameAsUnspecified() {
        for (String model : new String[]{"qwen3.5-plus-2026-02-15", "qwen-plus-2025-07-28", "qwen3.7-max"}) {
            Boolean expected = thinking(model, null);
            ChatOptions four = AiUtils.buildChatOptions(AiPlatformEnum.TONG_YI, model, 0.7, 2000);
            assertEquals(expected, ((DashScopeChatOptions) four).getEnableThinking(), "四参重载：" + model);
            ChatOptions six = AiUtils.buildChatOptions(AiPlatformEnum.TONG_YI, model, 0.7, 2000, null, null);
            assertEquals(expected, ((DashScopeChatOptions) six).getEnableThinking(), "六参重载：" + model);
        }
    }

    /**
     * 🔴 向量端点的分档表必须与对话端点那张<b>互相独立</b>
     *
     * <p>两张表都叫「多模态」、都靠 {@code -vl-} 这类名字认，所以最容易看串：
     * {@code qwen3-vl-plus} 是<b>对话</b>模型（走多模态生成端点），
     * {@code qwen3-vl-embedding} 是<b>向量</b>模型（走多模态向量端点）。
     * 下面 4 个命中项全部是 2026-10-26 直连厂商三端点逐个实测出来的（明细见
     * {@code sql/mysql/update/2026-10-26-ai-model-rerank-type.sql} 头部那张表），
     * 不是按系列名推断的 —— 与对话侧「qwen3.7-max 是纯文本主干例外」那条纪律同源。
     *
     * <p>它守的失效形态：这张表若漏掉一个名字，该模型会被送去撞文本向量端点，
     * 厂商回 {@code 400 InvalidParameter "url error, please check url！"}，
     * 界面上是「模型连不上」，而模型本身是好的 —— 库里曾长期有 4 行是这个形态。
     */
    @Test
    void testMultiModalEmbeddingRoutingTable() {
        // 实测只有多模态向量端点通的 4 个
        for (String model : new String[]{"qwen3-vl-embedding", "qwen2.5-vl-embedding",
                "tongyi-embedding-vision-flash", "tongyi-embedding-vision-plus"}) {
            assertTrue(AiUtils.isTongYiMultiModalEmbeddingModel(model), model + " 应走多模态向量端点");
        }
        // 实测只有文本向量端点通的，必须留在文本端点（误判过去就是另一种 url error）
        assertFalse(AiUtils.isTongYiMultiModalEmbeddingModel("text-embedding-v4"));
        assertFalse(AiUtils.isTongYiMultiModalEmbeddingModel("qwen3.7-text-embedding-flash"));
        assertFalse(AiUtils.isTongYiMultiModalEmbeddingModel("qwen3.7-text-embedding"));
        // 重排序模型不出向量，不该进本表（它由 ai_model.type=6 分档，见上面那个脚本）
        assertFalse(AiUtils.isTongYiMultiModalEmbeddingModel("gte-rerank-v2"));
        assertFalse(AiUtils.isTongYiMultiModalEmbeddingModel("qwen3-vl-rerank"));
        // 🔴 对话侧那张表的名字一个都不能漏进向量侧：两张表都靠 -vl- 认，看串了就是这几条红
        assertFalse(AiUtils.isTongYiMultiModalEmbeddingModel("qwen3-vl-plus"));
        assertFalse(AiUtils.isTongYiMultiModalEmbeddingModel("qwen3.6-plus"));
        // 空白不炸
        assertFalse(AiUtils.isTongYiMultiModalEmbeddingModel(null));
        assertFalse(AiUtils.isTongYiMultiModalEmbeddingModel("  "));
    }

    /**
     * 快照后缀对向量侧同样要剥，且配置同样是<b>整体替换</b>内置清单
     *
     * <p>剥后缀这条与对话侧共用 {@code matchesIgnoringSnapshot}，钉它是为了不让
     * 日后有人给向量侧另写一套匹配（两套必然漂移）。配置整体替换的语义与
     * {@link #testRoutingTables_configuredSetReplacesBuiltIn()} 同源：null = 用内置，
     * {@code []} = 清空，两者在 yaml 里长得像而语义完全不同。
     */
    @Test
    void testMultiModalEmbeddingRoutingTable_snapshotAndConfigOverride() {
        assertTrue(AiUtils.isTongYiMultiModalEmbeddingModel("qwen3-vl-embedding-2026-01-01"),
                "阿里云逐月新增快照名，剥后缀是内置行为，不靠把快照名逐个补进清单");
        assertTrue(AiUtils.isTongYiMultiModalEmbeddingModel("tongyi-embedding-vision-plus-latest"));

        Set<String> configured = Set.of("my-private-embedding");
        assertTrue(AiUtils.isTongYiMultiModalEmbeddingModel("my-private-embedding", configured));
        assertFalse(AiUtils.isTongYiMultiModalEmbeddingModel("qwen3-vl-embedding", configured),
                "配置整体替换内置清单，不是追加");
        assertFalse(AiUtils.isTongYiMultiModalEmbeddingModel("qwen3-vl-embedding", Set.of()),
                "显式空集合 = 清空");
        assertFalse(AiUtils.isTongYiMultiModalEmbeddingModel(null, configured));
    }

    private static Boolean thinking(String model, Boolean requested) {
        return ((DashScopeChatOptions) AiUtils.buildChatOptions(AiPlatformEnum.TONG_YI, model,
                0.7, 2000, null, null, requested)).getEnableThinking();
    }

    private static Boolean multiModel(String model) {
        return ((DashScopeChatOptions) AiUtils.buildChatOptions(AiPlatformEnum.TONG_YI, model,
                0.7, 2000, null, null, null)).getMultiModel();
    }

}
