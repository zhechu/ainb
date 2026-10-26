package cn.enlev.ainb.module.infra.framework.datainterface.core.executor;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceDO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HttpExecutor} 的单元测试
 *
 * 重点是 SSRF 防护：不做这一层，用户配个 http://127.0.0.1:8080/actuator/env
 * 就能读到应用的全部配置（含数据库密码）。
 *
 * 不发真实请求 —— 只验证「URL 校验」这道关卡，请求发出后的行为需要外部服务，
 * 不适合放在单测里。
 *
 * @author 走进新时代
 */
class HttpExecutorTest {

    private final HttpExecutor executor = new HttpExecutor();

    private InterfaceExecuteContext context(String url) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("url", url);
        config.put("method", "GET");
        DataInterfaceDO entity = new DataInterfaceDO();
        entity.setConfig(JsonUtils.toJsonString(config));
        return new InterfaceExecuteContext().setDataInterface(entity);
    }

    // ==================== SSRF 防护 ====================

    @ParameterizedTest
    @DisplayName("⚠️ 内网地址一律拒绝")
    @ValueSource(strings = {
            "http://127.0.0.1:8080/actuator/env",   // 回环，最典型的攻击目标
            "http://localhost:8080/api",            // 域名形式的回环
            "http://0.0.0.0:8080/api",              // 任意本地地址
            "http://10.0.0.1/api",                  // A 类私有
            "http://172.16.0.1/api",                // B 类私有
            "http://192.168.1.1/api",               // C 类私有
            "http://169.254.169.254/latest/meta-data", // 云厂商元数据服务
    })
    void testSsrf_internalDenied(String url) {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> executor.execute(context(url)), "应拒绝内网地址：" + url);
        assertTrue(ex.getMessage().contains("内网") || ex.getMessage().contains("不合法"),
                "错误信息应说明原因：" + ex.getMessage());
    }

    @ParameterizedTest
    @DisplayName("⚠️ 非 http/https 协议拒绝")
    @ValueSource(strings = {
            "file:///etc/passwd",
            "ftp://example.com/file",
            "jar:file:///tmp/x.jar!/",
            "gopher://example.com",
    })
    void testSsrf_schemeDenied(String url) {
        assertThrows(ServiceException.class, () -> executor.execute(context(url)),
                "应拒绝非 http/https：" + url);
    }

    @Test
    @DisplayName("缺少主机名拒绝")
    void testSsrf_noHost() {
        assertThrows(ServiceException.class, () -> executor.execute(context("http:///api")));
    }

    @Test
    @DisplayName("无法解析的主机名：保守拒绝")
    void testSsrf_unresolvableHost() {
        assertThrows(ServiceException.class, () ->
                executor.execute(context("http://this-host-definitely-does-not-exist-zz.invalid/api")));
    }

    @Test
    @DisplayName("显式允许时可以访问内网地址")
    void testSsrf_internalAllowedWhenExplicitlyEnabled() {
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                executor, "validateUrl", "http://127.0.0.1:48080/api", true));
    }

    @Test
    @DisplayName("大写 HTTP scheme 不得绕过内网地址校验")
    void testSsrf_uppercaseSchemeStillDenied() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> executor.execute(context("HTTP://127.0.0.1:8080/actuator/env")));
        assertTrue(ex.getMessage().contains("内网") || ex.getMessage().contains("不合法"));
    }

    @Test
    @DisplayName("HEAD 请求使用 HEAD 方法")
    void testHeadRequest_usesHeadMethod() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/head", exchange -> {
            method.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            DataInterfaceDO entity = new DataInterfaceDO();
            entity.setAllowInternalUrl(1);
            entity.setConfig(JsonUtils.toJsonString(Map.of(
                    "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/head",
                    "method", "HEAD")));

            executor.execute(new InterfaceExecuteContext().setDataInterface(entity));

            assertEquals("HEAD", method.get());
        } finally {
            server.stop(0);
        }
    }

    // ==================== 配置校验 ====================

    @Test
    @DisplayName("配置为空拒绝")
    void testEmptyConfig() {
        DataInterfaceDO entity = new DataInterfaceDO();
        entity.setConfig("");
        assertThrows(ServiceException.class, () ->
                executor.execute(new InterfaceExecuteContext().setDataInterface(entity)));
    }

    @Test
    @DisplayName("配置不是合法 JSON 拒绝")
    void testInvalidConfig() {
        DataInterfaceDO entity = new DataInterfaceDO();
        entity.setConfig("这不是 JSON");
        assertThrows(ServiceException.class, () ->
                executor.execute(new InterfaceExecuteContext().setDataInterface(entity)));
    }

    @Test
    @DisplayName("URL 为空拒绝")
    void testEmptyUrl() {
        DataInterfaceDO entity = new DataInterfaceDO();
        entity.setConfig("{\"method\":\"GET\"}");
        assertThrows(ServiceException.class, () ->
                executor.execute(new InterfaceExecuteContext().setDataInterface(entity)));
    }

    @Test
    @DisplayName("执行器类型正确")
    void testType() {
        assertEquals(3, executor.getType().getType());
    }

}
