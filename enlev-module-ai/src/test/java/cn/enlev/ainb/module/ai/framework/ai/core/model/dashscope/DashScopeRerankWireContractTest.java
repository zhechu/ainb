package cn.enlev.ainb.module.ai.framework.ai.core.model.dashscope;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DashScopeRerankModel} 的<b>出线请求</b>契约测试
 *
 * <p>🔴 本类钉的是一个<b>已经真实发生过</b>的故障，不是假想敌：
 * {@code /ai/model/test} 对 {@code gte-rerank-v2}（ai_model id=103）回
 * {@code 403 {"code":"AccessDenied"}}，而同一条密钥、同一个端点、逐字相同的请求体
 * 从 Node 直连是 200（{@code _evidence/probe-rerank-shape.mjs}：4 种体形态 × 2 种
 * User-Agent 全 200）。把 SDK 真正发出去的那一发抓下来才看见差别 ——
 * 请求体里是 {@code "model":"gte-rerank"}，不是 {@code gte-rerank-v2}：
 * 一个本账号没开通的旧模型，厂商因此回 AccessDenied。
 *
 * <p>成因是 {@code DashScopeRerankOptions} 的<b>无参构造自带默认值</b>
 * （{@code model="gte-rerank"}、{@code topN=3}、{@code returnDocuments=false}），
 * 所以「builder 出来但没调 withModel」的 options <b>不存在 model 为 null 这一档</b>；
 * 而 {@code DashScopeRerankModel#mergeOptions} 的口径是「请求级非 null 就压过构造时的默认值」
 * （已用 javap 逐条核过字节码，且它走 {@code ModelOptionsUtils.copyToTarget} 的 JSON 往返，
 * 被省略的字段会落到目标类无参构造的默认值上，所以「传 null 让它省略」这条路也是堵死的）。
 * 两者叠加 ⟹ {@code AiModelFactoryImpl#buildTongYiRerankModel} 里
 * {@code DashScopeRerankOptions.builder().withModel(model).build()} 配的那个模型名
 * <b>永远不会生效</b>，除非调用方在<b>请求级</b> options 上也写一遍。
 *
 * <p>与 {@code AiUtilsTest#testThinkingHasNoOmitState} 钉的
 * 「enableThinking 没有『不发这个字段』这一档」是同一个陷阱形状：
 * 库把默认值写进了字段初始化，于是「不表态」不等于「省略」，而等于「表态成另一个值」。
 *
 * <p>🔴 全程<b>零厂商调用</b>：假服务只回一份固定的 200，本类测的是「我们组装出的请求长什么样」。
 *
 * @author 走进新时代
 */
class DashScopeRerankWireContractTest {

    private static final String RERANK_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";
    private static final String API_KEY = "sk-wire-contract-only";

    private HttpServer server;
    private String capturedPath;
    private String capturedAuthorization;
    private String capturedBody;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("🔴 请求级 options 上写了 withModel，出线请求体里才是那个模型名")
    void testModelOnRequestOptionsReachesTheWire() {
        RerankResponse response = callWithOptions(DashScopeRerankOptions.builder()
                .withModel("gte-rerank-v2").withTopN(2).withReturnDocuments(false).build());

        assertNotNull(response);
        assertEquals(1, response.getResults().size());
        assertEquals(RERANK_PATH, capturedPath, "端点写死在 SDK 里，密钥表的 url 为空时走厂商默认域名");
        assertEquals("Bearer " + API_KEY, capturedAuthorization);
        assertTrue(capturedBody.contains("\"model\":\"gte-rerank-v2\""),
                "模型名必须逐字出现在请求体里：" + capturedBody);
        assertTrue(capturedBody.contains("\"top_n\":2"), capturedBody);
        assertTrue(capturedBody.contains("\"return_documents\":false"), capturedBody);
        assertTrue(capturedBody.contains("\"documents\":[\"启凌智搭的知识库按向量模型分文件存向量"),
                "候选文档按 spring-ai Document 的正文逐条铺开：" + capturedBody);
    }

    /**
     * 🔴 反向对照：漏掉 {@code withModel} 会被静默改写成 {@code gte-rerank}
     *
     * <p>这条<b>刻意断言错误行为本身</b>。它不是「测我们希望的样子」，而是把那个陷阱钉在墙上：
     * 一旦哪天 SDK 把无参构造的默认值去掉（或换成别的名字），这条会红，
     * 那时就该回来把 {@code AiModelServiceImpl#testRerankModel} 里那句
     * 「withModel 不是冗余」的注释一起更新 —— 而不是让它悄悄失效。
     * 少了这条，「漏写 withModel ⟹ 403 AccessDenied」只能靠真机再踩一次才能发现。
     */
    @Test
    @DisplayName("🔴 漏掉 withModel：出线请求体会被静默改写成 gte-rerank（这就是那次 403 的成因）")
    void testOmittingModelOnRequestOptionsSilentlyFallsBackToVendorDefault() {
        callWithOptions(DashScopeRerankOptions.builder().withTopN(2).withReturnDocuments(false).build());

        assertTrue(capturedBody.contains("\"model\":\"gte-rerank\""),
                "构造时配的 gte-rerank-v2 被请求级默认值压掉了：" + capturedBody);
        assertEquals("gte-rerank", new DashScopeRerankOptions().getModel(),
                "默认值来自库自己的无参构造，不是本项目设的");
    }

    // ==================== 工具方法 ====================

    /**
     * 起一个只回一份固定 200 的假服务，用与 {@code AiModelFactoryImpl#buildTongYiRerankModel}
     * 同构的方式建出模型，并按 {@code AiModelServiceImpl#testRerankModel} 同构的方式发一发
     *
     * <p>{@code RestClient.builder()} 而非 {@code timedRestClientBuilder()}：后者是私有静态方法
     * 且要 Spring 上下文取超时配置，而它只影响 request factory（超时），不影响出线请求的形状。
     */
    private RerankResponse callWithOptions(DashScopeRerankOptions requestOptions) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(RERANK_PATH, exchange -> {
                capturedPath = exchange.getRequestURI().getPath();
                capturedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                capturedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                byte[] out = ("{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.9812}]},"
                        + "\"usage\":{\"total_tokens\":32},\"request_id\":\"wire-contract\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream out2 = exchange.getResponseBody()) {
                    out2.write(out);
                }
                exchange.close();
            });
            server.setExecutor(null);
            server.start();

            DashScopeApi dashScopeApi = DashScopeApi.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey(API_KEY)
                    .restClientBuilder(RestClient.builder())
                    .build();
            // 构造时就把模型名配好了 —— 这正是被请求级默认值压掉的那一份
            DashScopeRerankModel rerankModel = new DashScopeRerankModel(dashScopeApi,
                    DashScopeRerankOptions.builder().withModel("gte-rerank-v2").build());

            return rerankModel.call(new RerankRequest("启凌智搭的知识库支持哪些向量模型",
                    List.of(new Document("启凌智搭的知识库按向量模型分文件存向量，切换模型需要重新嵌入全部段落。"),
                            new Document("紫色河马在星期四下午三点十七分跳了一支探戈。")),
                    requestOptions));
        } catch (Exception ex) {
            throw new IllegalStateException("本地假服务起不来，本类无法给出结论", ex);
        }
    }
}
