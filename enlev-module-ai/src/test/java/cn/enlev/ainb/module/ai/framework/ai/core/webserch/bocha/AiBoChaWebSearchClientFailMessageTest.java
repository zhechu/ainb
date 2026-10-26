package cn.enlev.ainb.module.ai.framework.ai.core.webserch.bocha;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.ai.framework.ai.core.webserch.AiWebSearchRequest;
import cn.enlev.ainb.module.ai.framework.ai.core.webserch.AiWebSearchResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.WEB_SEARCH_FAIL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiBoChaWebSearchClient} 三处失败的<b>报错内容</b>单测（台账 D4-29 同一条纪律）
 *
 * <h3>为什么必须起一个本地假服务，而不是读代码相信</h3>
 * 三处失败全在 HTTP 应答之后：非 2xx、2xx 但响应体空、2xx 但业务码非 0。
 * 不真的让 {@code WebClient} 撞上这三种应答，「报错里到底有没有厂商原文」就只是一句文字描述
 * （红线 §10.2：不把文字描述当结论）。这里用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer}
 * 而不是 MockWebServer —— 后者不在本模块的依赖里，为一个单测拉一份新依赖不值当。
 *
 * <h3>它守的失效形态</h3>
 * 改之前这三处抛的是<b>没有码</b>的 {@code IllegalStateException}，落到
 * {@code GlobalExceptionHandler#defaultExceptionHandler} 那一支 —— 那一支<b>不透传 message</b>，
 * 返回 {@code INTERNAL_SERVER_ERROR(500, "系统异常")}。于是「博查密钥失效」在界面上是「系统异常」，
 * 厂商原文只在服务端日志里。把 {@code exception(WEB_SEARCH_FAIL, …)} 改回
 * {@code new IllegalStateException(…)}，本类必须变红。
 *
 * @author 走进新时代
 */
class AiBoChaWebSearchClientFailMessageTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("非 2xx → 带码，且报错里带上厂商的状态码与响应体原文（不再是一句「系统异常」）")
    void httpError_carriesVendorStatusAndBody() {
        AiBoChaWebSearchClient client = clientResponding(401,
                "{\"code\":\"invalid_api_key\",\"message\":\"Authentication failed\"}");

        ServiceException ex = assertThrows(ServiceException.class, () -> client.search(query()));

        assertEquals(WEB_SEARCH_FAIL.getCode(), ex.getCode(),
                "成因要靠码认：没有码就会被兜底成 INTERNAL_SERVER_ERROR，界面上只剩「系统异常」四个字");
        assertTrue(ex.getMessage().contains("401"), "该带上厂商的状态码：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("invalid_api_key"),
                "🔴 该逐字带上厂商响应体 —— 它原先只进 log.error，用户永远看不到：" + ex.getMessage());
        assertFalse(ex.getMessage().contains("系统异常"),
                "不许退回那句什么都不指认的通用话：" + ex.getMessage());
    }

    @Test
    @DisplayName("2xx 但业务码非 0 → 带码，且带上厂商自己的 code/msg")
    void businessFailure_carriesVendorCodeAndMsg() {
        AiBoChaWebSearchClient client = clientResponding(200,
                "{\"code\":40001,\"data\":null,\"msg\":\"账户余额不足\"}");

        ServiceException ex = assertThrows(ServiceException.class, () -> client.search(query()));

        assertEquals(WEB_SEARCH_FAIL.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("40001"), "该带上厂商业务码：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("账户余额不足"),
                "该逐字引用厂商的话，而不是自己编一个成因（红线 §10.2 第 7 条）：" + ex.getMessage());
    }

    @Test
    @DisplayName("2xx 但响应体为空 → 说清这个事实，不猜成因")
    void emptyBody_reportsFactNotGuess() {
        AiBoChaWebSearchClient client = clientResponding(200, null);

        ServiceException ex = assertThrows(ServiceException.class, () -> client.search(query()));

        assertEquals(WEB_SEARCH_FAIL.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("响应体为空"), "该说清是「2xx 且没有体」：" + ex.getMessage());
    }

    @Test
    @DisplayName("厂商返回超长内容（代理/网关的整页 HTML）→ 截断，不把界面与 api_error_log 撑破")
    void longVendorBody_isTruncated() {
        String huge = "<html>" + "A".repeat(4000) + "</html>";
        AiBoChaWebSearchClient client = clientResponding(502, huge);

        ServiceException ex = assertThrows(ServiceException.class, () -> client.search(query()));

        assertEquals(WEB_SEARCH_FAIL.getCode(), ex.getCode());
        assertTrue(ex.getMessage().length() < 700,
                "该被截断到 500 字量级，实际长度=" + ex.getMessage().length());
        assertTrue(ex.getMessage().endsWith("..."), "截断要留下可看出被截断的痕迹：" + ex.getMessage());
        assertFalse(ex.getMessage().contains("</html>"), "尾巴该被砍掉：" + ex.getMessage());
    }

    @Test
    @DisplayName("正向对照：搜到了就照常返回结果 —— 否则「把 search 改成一律抛」也能全绿")
    void success_stillReturnsResult() {
        // ⚠️ 字段对应关系按实现走：WebPage.name ← 厂商的 siteName、WebPage.title ← 厂商的 name。
        // 第一版把两者当成同名，断言写成 getName()=="阿里巴巴" 结果拿到 null —— 是本用例写错，不是产品缺陷
        AiBoChaWebSearchClient client = clientResponding(200,
                "{\"code\":0,\"data\":{\"webPages\":{\"totalEstimatedMatches\":1,"
                        + "\"value\":[{\"name\":\"阿里巴巴：2024 年 ESG 报告\",\"siteName\":\"阿里巴巴\","
                        + "\"url\":\"https://example.com\",\"snippet\":\"s\"}]}},"
                        + "\"msg\":\"\"}");

        AiWebSearchResponse response = client.search(query());

        assertNotNull(response);
        assertEquals(1L, response.getTotal());
        assertEquals(1, response.getLists().size());
        AiWebSearchResponse.WebPage page = response.getLists().get(0);
        assertEquals("阿里巴巴", page.getName());
        assertEquals("阿里巴巴：2024 年 ESG 报告", page.getTitle());
        assertEquals("https://example.com", page.getUrl());
        assertEquals("s", page.getSnippet());
    }

    // ==================== 工具方法 ====================

    /**
     * 起一个只回一种应答的本地服务，并返回指向它的客户端
     *
     * @param body {@code null} = 2xx 且不带响应体（{@code sendResponseHeaders(len = -1)}）
     */
    private AiBoChaWebSearchClient clientResponding(int status, String body) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            server.createContext("/v1/web-search", exchange -> {
                if (body != null) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
                }
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
                exchange.close();
            });
            server.setExecutor(null);
            server.start();
            return new AiBoChaWebSearchClient("sk-test-only",
                    "http://127.0.0.1:" + server.getAddress().getPort());
        } catch (Exception ex) {
            throw new IllegalStateException("本地假服务起不来，本类无法给出结论", ex);
        }
    }

    private AiWebSearchRequest query() {
        return new AiWebSearchRequest().setQuery("阿里巴巴").setCount(3);
    }

}
