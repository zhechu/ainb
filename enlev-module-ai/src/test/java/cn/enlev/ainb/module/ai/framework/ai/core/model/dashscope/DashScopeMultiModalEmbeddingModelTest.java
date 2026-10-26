package cn.enlev.ainb.module.ai.framework.ai.core.model.dashscope;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DashScopeMultiModalEmbeddingModel} 的报文契约与<b>批量对齐</b>单测
 *
 * <h3>为什么必须起一个本地假服务</h3>
 * 本类要钉的是「厂商报文 → spring-ai {@link EmbeddingResponse}」这一段映射，
 * 而它的失效形态<b>全部</b>发生在应答之后：少一条、多一条、下标越界、顺序颠倒、
 * 没有 {@code usage}、非 2xx。不真的让 {@code RestClient} 撞上这些应答，
 * 「按 index 归位」就只是一句注释（红线 §10.2：不把文字描述当结论）。
 * 用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer}，与本模块
 * {@code AiBoChaWebSearchClientFailMessageTest} 同一套做法，不为一个单测拉新依赖。
 *
 * <h3>它守的失效形态（🔴 这一条是静默的，所以必须有测试）</h3>
 * {@code SimpleVectorStore#add(List)} 一次提交整批，返回的第 i 条向量被挂到第 i 个段落上。
 * 若实现按<b>数组下标</b>取而不是按厂商给的 {@code index} 归位，那么厂商哪天不按请求顺序返回，
 * 就会<b>把向量挂到别的段落</b> —— 检索照常返回结果、分数看着也正常，只是内容是别人的，
 * 没有任何日志或报错。所以 {@link #testBatchIsAlignedByVendorIndexNotArrayOrder()} 让假服务
 * 故意<b>乱序</b>返回：把归位改成按下标取，那条必须变红。
 *
 * @author 走进新时代
 */
class DashScopeMultiModalEmbeddingModelTest {

    private static final String MODEL = "qwen3-vl-embedding";

    private static final String API_KEY = "sk-test-only";

    private HttpServer server;

    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();

    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("🔴 批量按厂商的 index 归位，不按数组下标取 —— 假服务故意乱序返回")
    void testBatchIsAlignedByVendorIndexNotArrayOrder() {
        // 请求 3 条，应答按 index=2,0,1 的顺序给；每条向量的分量就等于它的 index+1，
        // 于是「第 i 条文本拿到的是不是第 i 条向量」可以直接用数值断言
        DashScopeMultiModalEmbeddingModel model = modelResponding(200,
                "{\"output\":{\"embeddings\":["
                        + "{\"embedding\":[3,3],\"index\":2,\"type\":\"text\"},"
                        + "{\"embedding\":[1,1],\"index\":0,\"type\":\"text\"},"
                        + "{\"embedding\":[2,2],\"index\":1,\"type\":\"text\"}]},"
                        + "\"usage\":{\"input_tokens\":30,\"total_tokens\":30}}");

        EmbeddingResponse response = model.call(new EmbeddingRequest(
                List.of("甲", "乙", "丙"), EmbeddingOptions.builder().build()));

        List<Embedding> results = response.getResults();
        assertEquals(3, results.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i + 1.0f, results.get(i).getOutput()[0], 0.0f,
                    "第 " + i + " 条文本必须拿到 index=" + i + " 的那条向量；"
                            + "若这里是 " + (4 - i) + " 就说明实现按数组下标取了");
            assertEquals(i, results.get(i).getIndex(), "回填给 spring-ai 的 index 应是归位后的下标");
        }
        // 请求报文形状也要钉：多模态端点收的是 contents[{text}]，不是文本端点那个 texts[]
        assertEquals("{\"model\":\"" + MODEL + "\",\"input\":{\"contents\":["
                        + "{\"text\":\"甲\"},{\"text\":\"乙\"},{\"text\":\"丙\"}]}}",
                lastRequestBody.get(), "报文形状变了就等于换了个端点，厂商只会回一句 url error");
        assertEquals("Bearer " + API_KEY, lastAuthorization.get());
        assertEquals(DashScopeMultiModalEmbeddingModel.MULTIMODAL_EMBEDDING_RESTFUL_URL, lastPath.get());
        // 维度顺手记进 AbstractEmbeddingModel 的缓存位，dimensions() 就不必再发一次探测
        assertEquals(2, model.dimensions(), "维度应来自上一次成功调用，而不是再打一次厂商");
    }

    @Test
    @DisplayName("少返回一条 → 抛，绝不拿别人的向量凑数")
    void testMissingIndexThrows() {
        DashScopeMultiModalEmbeddingModel model = modelResponding(200,
                "{\"output\":{\"embeddings\":["
                        + "{\"embedding\":[1],\"index\":0},{\"embedding\":[2],\"index\":1}]}}");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> model.call(new EmbeddingRequest(List.of("甲", "乙", "丙"),
                        EmbeddingOptions.builder().build())));

        assertTrue(ex.getMessage().contains("少返回了第 2 条向量"), ex.getMessage());
        assertTrue(ex.getMessage().contains("请求 3 条、返回 2 条"), ex.getMessage());
    }

    @Test
    @DisplayName("index 重复 / 越界 / 缺失 → 一律抛，不猜对应关系")
    void testBrokenIndexesThrow() {
        // 重复
        assertBrokenIndexThrows("{\"output\":{\"embeddings\":["
                        + "{\"embedding\":[1],\"index\":0},{\"embedding\":[2],\"index\":0}]}}",
                "重复的 index(0)");
        // 越界
        assertBrokenIndexThrows("{\"output\":{\"embeddings\":["
                        + "{\"embedding\":[1],\"index\":0},{\"embedding\":[2],\"index\":7}]}}",
                "index(7) 越界");
        // index 字段整个没有
        assertBrokenIndexThrows("{\"output\":{\"embeddings\":["
                        + "{\"embedding\":[1]},{\"embedding\":[2]}]}}",
                "index(null) 越界");
        // 有 index 但没有向量内容
        assertBrokenIndexThrows("{\"output\":{\"embeddings\":["
                        + "{\"embedding\":[],\"index\":0},{\"embedding\":[2],\"index\":1}]}}",
                "第 0 条没有向量内容");
    }

    @Test
    @DisplayName("空 output / 没有 embeddings → 抛，且报错里说清期望几条")
    void testEmptyOutputThrows() {
        assertEmptyOutputThrows("{\"output\":{\"embeddings\":[]}}");
        assertEmptyOutputThrows("{\"output\":{}}");
        assertEmptyOutputThrows("{}");
    }

    @Test
    @DisplayName("厂商整个不回 usage 也不能炸 —— 落到 spring-ai 那边必须是 EmptyUsage")
    void testUsageIsOptional() {
        DashScopeMultiModalEmbeddingModel model = modelResponding(200,
                "{\"output\":{\"embeddings\":[{\"embedding\":[1,2],\"index\":0}]}}");

        EmbeddingResponse response = model.call(new EmbeddingRequest(
                List.of("甲"), EmbeddingOptions.builder().build()));

        assertEquals(1, response.getResults().size());
        assertNotNull(response.getMetadata(), "元数据对象本身要有，否则调用方取 model 名会 NPE");
        assertEquals(MODEL, response.getMetadata().getModel());
        // 🔴 不是 null：spring-ai 的 EmbeddingResponseMetadata#getUsage 在字段为 null 时
        //    每次 new 一个 EmptyUsage 顶上（已用 javap 核过它的字节码）。这是框架行为，
        //    本类要钉的是「我们没往里塞一个编出来的用量」—— 传进去的必须是 null，
        //    于是 getter 只能回 EmptyUsage；若实现塞了 DefaultUsage(0,0,0)，这条就红
        assertTrue(response.getMetadata().getUsage() instanceof EmptyUsage,
                "厂商没回 usage，落到 spring-ai 那边就该是它的「没有用量」标记，实际是 "
                        + response.getMetadata().getUsage().getClass().getName());
    }

    @Test
    @DisplayName("厂商回了 usage → promptTokens 取 input_tokens；completionTokens 保持 null")
    void testUsageIsCarried() {
        DashScopeMultiModalEmbeddingModel model = modelResponding(200,
                "{\"output\":{\"embeddings\":[{\"embedding\":[1],\"index\":0}]},"
                        + "\"usage\":{\"input_tokens\":38,\"image_tokens\":0,\"total_tokens\":38,"
                        + "\"input_tokens_details\":{\"text_tokens\":38}}}");

        EmbeddingResponse response = model.call(new EmbeddingRequest(
                List.of("甲"), EmbeddingOptions.builder().build()));

        assertEquals(38, response.getMetadata().getUsage().getPromptTokens());
        assertEquals(38, response.getMetadata().getUsage().getTotalTokens());
    }

    /**
     * 🔴 只回 {@code total_tokens} 时退到 total，而不是被 {@code DefaultUsage} 抹成 0
     *
     * <p>这一档在通义上是<b>真实存在</b>的：2026-10-26 直连实测，重排序端点的
     * {@code gte-rerank-v2} 回 {@code {"total_tokens":79}}、文本向量端点的
     * {@code text-embedding-v4} 回 {@code {"total_tokens":12}}，都没有 {@code input_tokens}。
     * 多模态端点今天四个模型都回 {@code input_tokens}，但字段名本来就不统一，
     * 而 {@code DefaultUsage} 的构造把 null 一律按 0 处理（javap 核实）——
     * 不在这里退一步，下游拿到的就是「输入 0」，一个厂商从没说过的数字，且再也分不出来。
     */
    @Test
    @DisplayName("usage 只有 total_tokens → promptTokens 退到 total，不是 0")
    void testUsageWithOnlyTotalTokensFallsBackToTotal() {
        DashScopeMultiModalEmbeddingModel model = modelResponding(200,
                "{\"output\":{\"embeddings\":[{\"embedding\":[1],\"index\":0}]},"
                        + "\"usage\":{\"total_tokens\":79}}");

        EmbeddingResponse response = model.call(new EmbeddingRequest(
                List.of("甲"), EmbeddingOptions.builder().build()));

        assertEquals(79, response.getMetadata().getUsage().getPromptTokens(),
                "厂商报了 total_tokens=79，不能因为字段名不叫 input_tokens 就变成 0");
        assertEquals(79, response.getMetadata().getUsage().getTotalTokens());
    }

    /**
     * {@code usage} 对象在、但两个 token 字段都不在 —— 与「整个 usage 都不回」是同一档结论：
     * 没数字可报，就不许报。{@code qwen2.5-vl-embedding} 实测回的是
     * {@code {"image_tokens":0,"input_tokens":31}}，所以这一档目前是防御性的，不是实测形态。
     */
    @Test
    @DisplayName("usage 里一个 token 字段都没有 → 仍是 EmptyUsage，不塞 DefaultUsage(0)")
    void testUsageWithoutAnyTokenFieldIsEmpty() {
        DashScopeMultiModalEmbeddingModel model = modelResponding(200,
                "{\"output\":{\"embeddings\":[{\"embedding\":[1],\"index\":0}]},"
                        + "\"usage\":{\"image_tokens\":0}}");

        EmbeddingResponse response = model.call(new EmbeddingRequest(
                List.of("甲"), EmbeddingOptions.builder().build()));

        assertTrue(response.getMetadata().getUsage() instanceof EmptyUsage,
                "两个 token 字段都缺，落到 spring-ai 那边就该是它的「没有用量」标记，实际是 "
                        + response.getMetadata().getUsage().getClass().getName());
    }

    @Test
    @DisplayName("非 2xx 不吞：异常里带厂商原文，否则测试弹窗只剩一句「连不上」")
    void testVendorErrorIsNotSwallowed() {
        DashScopeMultiModalEmbeddingModel model = modelResponding(400,
                "{\"code\":\"InvalidParameter\",\"message\":\"url error, please check url！\"}");

        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class,
                () -> model.call(new EmbeddingRequest(List.of("甲"), EmbeddingOptions.builder().build())));

        String body = ex.getResponseBodyAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("InvalidParameter"), body);
        assertTrue(body.contains("url error"), body);
        assertTrue(ex.getMessage().contains("url error"),
                "AiModelFailureReason.classify 与模型测试弹窗都靠 message 里的厂商原文分型：" + ex.getMessage());
    }

    @Test
    @DisplayName("空输入不发请求（SimpleVectorStore 删空批次时会走到）")
    void testEmptyInputShortCircuits() {
        DashScopeMultiModalEmbeddingModel model = modelResponding(200, "{}");

        EmbeddingResponse response = model.call(new EmbeddingRequest(
                List.of(), EmbeddingOptions.builder().build()));

        assertTrue(response.getResults().isEmpty());
        assertNull(lastRequestBody.get(), "空输入不该发出任何 HTTP 请求");
    }

    @Test
    @DisplayName("embed(String) / embed(Document) 两条便捷入口与 call 同口径")
    void testConvenienceEntryPoints() {
        DashScopeMultiModalEmbeddingModel model = modelResponding(200,
                "{\"output\":{\"embeddings\":[{\"embedding\":[7,8],\"index\":0}]}}");

        assertEquals(7.0f, model.embed("甲")[0], 0.0f);
        assertEquals("{\"model\":\"" + MODEL + "\",\"input\":{\"contents\":[{\"text\":\"甲\"}]}}",
                lastRequestBody.get());

        assertEquals(8.0f, model.embed(new Document("甲"))[1], 0.0f);
    }

    // ==================== 工具方法 ====================

    private void assertBrokenIndexThrows(String body, String expectedFragment) {
        DashScopeMultiModalEmbeddingModel model = modelResponding(200, body);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> model.call(new EmbeddingRequest(List.of("甲", "乙"), EmbeddingOptions.builder().build())),
                expectedFragment);
        assertTrue(ex.getMessage().contains(expectedFragment),
                "期望报错里含「" + expectedFragment + "」，实际是：" + ex.getMessage());
    }

    private void assertEmptyOutputThrows(String body) {
        DashScopeMultiModalEmbeddingModel model = modelResponding(200, body);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> model.call(new EmbeddingRequest(List.of("甲"), EmbeddingOptions.builder().build())));
        assertTrue(ex.getMessage().contains("返回了空响应"), ex.getMessage());
        assertTrue(ex.getMessage().contains("期望 1 条向量"), ex.getMessage());
    }

    /**
     * 起一个只回一种应答的本地假服务，并返回指向它的模型
     */
    private DashScopeMultiModalEmbeddingModel modelResponding(int status, String body) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            server.createContext(DashScopeMultiModalEmbeddingModel.MULTIMODAL_EMBEDDING_RESTFUL_URL, exchange -> {
                lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                lastPath.set(exchange.getRequestURI().getPath());
                exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
                exchange.close();
            });
            server.setExecutor(null);
            server.start();
            return new DashScopeMultiModalEmbeddingModel(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), API_KEY, MODEL, MetadataMode.EMBED);
        } catch (Exception ex) {
            throw new IllegalStateException("本地假服务起不来，本类无法给出结论", ex);
        }
    }

}
