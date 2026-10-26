package cn.enlev.ainb.module.infra.framework.datainterface.core.executor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.enums.datainterface.DataInterfaceTypeEnum;
import cn.enlev.ainb.module.infra.framework.datainterface.core.param.ParamValidator;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * HTTP 转发类型的执行器
 *
 * 把请求转发到第三方接口。三个必须处理的点：
 * <ol>
 *     <li><b>超时必配</b>：不设超时会让线程一直挂着，最终拖垮线程池</li>
 *     <li><b>SSRF 防护</b>：禁止转发到内网地址，否则这个功能会变成打内网的跳板</li>
 *     <li><b>响应大小上限</b>：防止对方返回超大响应打爆内存</li>
 * </ol>
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class HttpExecutor implements InterfaceExecutor {

    /**
     * 占位符：#{paramName}，与 SQL 类型保持一致的写法
     */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("#\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}");

    /**
     * 响应大小上限，10MB
     */
    private static final int MAX_RESPONSE_LENGTH = 10 * 1024 * 1024;

    private static final int DEFAULT_CONNECT_TIMEOUT = 3000;
    private static final int DEFAULT_READ_TIMEOUT = 10000;

    @Autowired
    private WebServerApplicationContext webServerApplicationContext;
    /**
     * HTTP 转发配置（最新设计，参考 POSTMAN）
     */
    @Data
    public static class HttpConfigModel {
        private String url;
        private String method = "GET";
        private Integer connectTimeout;
        private Integer readTimeout;

        // 新设计：参考 POSTMAN 的配置结构
        private java.util.List<HttpParam> queryParams;   // Query 参数
        private java.util.List<HttpParam> headers;       // Headers 参数
        private java.util.List<HttpParam> bodyParams;    // Body 参数（form-data/urlencoded）
        private String bodyType = "none";  // none/form-data/x-www-form-urlencoded/json/xml/raw
        private String bodyContent;  // JSON/XML/raw 格式的 body 内容
    }

    /**
     * HTTP 参数（Query/Header/Body）
     */
    @Data
    public static class HttpParam {
        private String key;
        private String value;
        private String description;
        private Boolean enabled;  // 是否启用
    }

    @Override
    public DataInterfaceTypeEnum getType() {
        return DataInterfaceTypeEnum.HTTP;
    }

    @Override
    public Object execute(InterfaceExecuteContext context) {
        HttpConfigModel config = parseConfig(context.getDataInterface().getConfig());
        // 参数按定义校验并转换类型，与 SQL 类型共用一套校验逻辑
        Map<String, Object> params = ParamValidator.validateAndConvert(
                context.getParamDefinitions(), context.getRequestArgs());

        String url = replacePlaceholders(config.getUrl(), params);

        // 记录原始 URL（用于判断是否为相对路径）
        String originalUrl = url;

        // 相对路径拼接
        url = resolveUrl(url, context.getDataInterface().getBaseUrl());
        boolean isRelativePath = isRelativePath(originalUrl);

        // 构建 Query 参数（新增功能，向后兼容）
        url = buildUrlWithQuery(url, config.getQueryParams(), params);

        // SSRF 检查（可选关闭）
        boolean allowInternal = context.getDataInterface().getAllowInternalUrl() != null
                && context.getDataInterface().getAllowInternalUrl() == 1;
        // 相对路径由本系统服务端地址拼接而来，属于受控的内部调用；
        // 外部绝对地址仍按 allowInternalUrl 执行 SSRF 校验。
        validateUrl(url, allowInternal || isRelativePath);

        // 构建请求体（支持新旧两种格式）
        String body = buildRequestBody(config, params);

        // 把最终请求信息记进上下文，便于在调用日志里排查
        context.setExecuteSql(config.getMethod() + " " + url);

        // 相对路径（本系统接口）需要透传 Authorization 头
        HttpResponse response = doRequest(config, url, body,
                isRelativePath ? context.getAuthorization() : null,
                isRelativePath ? context.getCookie() : null,
                isRelativePath ? context.getTenantId() : null,
                params);
        return parseResponse(response);
    }

    /**
     * 解析 URL：相对路径自动拼接本服务地址
     *
     * 如果 URL 不是 http/https 开头，则自动拼接本服务地址，实现相对路径访问本服务接口
     */
    private String resolveUrl(String url, String baseUrl) {
        if (StrUtil.isBlank(url)) {
            throw exception(DATA_INTERFACE_HTTP_URL_INVALID, "URL 为空");
        }

        // 已是绝对路径：只允许 http/https，避免把 file://、ftp:// 等地址当成相对路径。
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() != null) {
                if ("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme())) {
                    return url;
                }
                throw exception(DATA_INTERFACE_HTTP_URL_INVALID, "只支持 http/https：" + url);
            }
        } catch (IllegalArgumentException ex) {
            throw exception(DATA_INTERFACE_HTTP_URL_INVALID, url);
        }

        // 相对路径：自动拼接本服务地址
        String localBaseUrl = "http://localhost:" + webServerApplicationContext.getWebServer().getPort();

        // 拼接（注意去重 /）
        String resolvedUrl = localBaseUrl.replaceAll("/$", "") + "/" + url.replaceAll("^/", "");

        log.debug("[resolveUrl][相对路径转换] {} -> {}", url, resolvedUrl);
        return resolvedUrl;
    }

    private boolean isRelativePath(String url) {
        try {
            return URI.create(url).getScheme() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 构建带 Query 参数的 URL（新增功能）
     */
    private String buildUrlWithQuery(String url, java.util.List<HttpParam> queryParams, Map<String, Object> params) {
        if (queryParams == null || queryParams.isEmpty()) {
            return url;
        }

        StringBuilder sb = new StringBuilder(url);
        boolean hasQuery = url.contains("?");

        for (HttpParam param : queryParams) {
            // 跳过未启用的参数
            if (Boolean.FALSE.equals(param.getEnabled())) {
                continue;
            }
            if (StrUtil.isBlank(param.getKey())) {
                continue;
            }

            // 替换占位符
            String value = replacePlaceholders(StrUtil.blankToDefault(param.getValue(), ""), params);

            sb.append(hasQuery ? "&" : "?");
            try {
                sb.append(param.getKey()).append("=").append(java.net.URLEncoder.encode(value, "UTF-8"));
            } catch (Exception e) {
                sb.append(param.getKey()).append("=").append(value);
            }
            hasQuery = true;
        }

        return sb.toString();
    }

    /**
     * 构建请求体（最新设计）
     */
    private String buildRequestBody(HttpConfigModel config, Map<String, Object> params) {
        String bodyType = StrUtil.blankToDefault(config.getBodyType(), "none");

        switch (bodyType) {
            case "none":
                return null;

            case "form-data":
            case "x-www-form-urlencoded":
                return buildFormBody(config.getBodyParams(), params);

            case "json":
            case "xml":
            case "raw":
                String bodyContent = StrUtil.blankToDefault(config.getBodyContent(), "");
                return replacePlaceholders(bodyContent, params);

            default:
                return null;
        }
    }

    /**
     * 构建表单格式的请求体
     */
    private String buildFormBody(java.util.List<HttpParam> bodyParams, Map<String, Object> params) {
        if (bodyParams == null || bodyParams.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (HttpParam param : bodyParams) {
            // 跳过未启用的参数
            if (Boolean.FALSE.equals(param.getEnabled())) {
                continue;
            }
            if (StrUtil.isBlank(param.getKey())) {
                continue;
            }

            // 替换占位符
            String value = replacePlaceholders(StrUtil.blankToDefault(param.getValue(), ""), params);

            if (!first) {
                sb.append("&");
            }
            try {
                sb.append(param.getKey()).append("=").append(java.net.URLEncoder.encode(value, "UTF-8"));
            } catch (Exception e) {
                sb.append(param.getKey()).append("=").append(value);
            }
            first = false;
        }

        return sb.toString();
    }

    /**
     * 解析 HTTP 转发配置
     *
     * <p>public static：保存接口时走的是同一个方法
     * （见 {@code DataInterfaceServiceImpl#validateHttpConfigIfNeeded}）。
     * 存与跑共用一套解析，才能保证「存得进去」等价于「跑得起来」。
     *
     * <p>解析失败时不把 Jackson 的报文抛给前端。那句话里带着
     * {@code HttpExecutor$HttpParam} 这样的全限定类名和 {@code JsonToken.START_OBJECT}，
     * 管理员既看不懂也改不动。原始报文与堆栈由 {@code JsonUtils#parseObject} 记进日志，
     * 排查时在那里能看到是哪个字段、什么形状不对，所以这里不再重复记一遍。
     */
    public static HttpConfigModel parseConfig(String raw) {
        if (StrUtil.isBlank(raw)) {
            throw exception(DATA_INTERFACE_HTTP_CONFIG_INVALID, "配置为空");
        }
        HttpConfigModel config;
        try {
            config = JsonUtils.parseObject(raw, HttpConfigModel.class);
        } catch (Exception ex) {
            throw exception(DATA_INTERFACE_HTTP_CONFIG_INVALID,
                    "配置格式与当前版本不兼容，请重新配置请求参数后保存");
        }
        // 这一句必须在 try 外面：放在里面会被上面的 catch 捕获并二次包装，
        // 用户最终看到「配置不合法：配置无法解析：HTTP 地址不合法：地址为空」这种套娃
        if (config == null || StrUtil.isBlank(config.getUrl())) {
            throw exception(DATA_INTERFACE_HTTP_CONFIG_INVALID, "地址为空");
        }
        return config;
    }

    /**
     * 替换占位符
     *
     * 与 SQL 不同，HTTP 的 URL 与 body 无法用占位符参数化，只能做字符串替换。
     * 因此这里对值做了转义处理，避免用户输入破坏 URL 结构或注入额外参数。
     */
    private String replacePlaceholders(String template, Map<String, Object> params) {
        if (StrUtil.isBlank(template)) {
            return template;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = params.get(name);
            sb.append(template, last, matcher.start())
                    .append(value == null ? "" : encode(String.valueOf(value)));
            last = matcher.end();
        }
        sb.append(template.substring(last));
        return sb.toString();
    }

    /**
     * 值转义：去掉能改变 URL 结构的字符
     *
     * 不做完整 URL 编码 —— 那会把 JSON body 里的引号也编码掉。
     * 只挡住 & # ? 这几个能拼出额外参数的字符。
     */
    private String encode(String value) {
        return value.replace("&", "%26").replace("#", "%23").replace("?", "%3F");
    }

    /**
     * URL 校验：必须是 http/https，且默认不能指向内网
     *
     * 这是 SSRF 防护的核心。不做这一层，用户配个 http://127.0.0.1:8080/actuator/env
     * 就能读到应用的全部配置（含数据库密码）。
     *
     * @param allowInternal 是否允许内网地址（显式开启后可访问 localhost 等）
     */
    private void validateUrl(String url, boolean allowInternal) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception ex) {
            throw exception(DATA_INTERFACE_HTTP_URL_INVALID, url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw exception(DATA_INTERFACE_HTTP_URL_INVALID, "只支持 http/https：" + url);
        }
        String host = uri.getHost();
        if (StrUtil.isBlank(host)) {
            throw exception(DATA_INTERFACE_HTTP_URL_INVALID, "缺少主机名：" + url);
        }
        if (!allowInternal && isInternalHost(host)) {
            throw exception(DATA_INTERFACE_HTTP_URL_DENY, host);
        }
    }

    /**
     * 判断是否内网地址
     *
     * 用 InetAddress 解析后判断，而不是只比对字符串 —— 否则
     * http://localhost、http://0x7f.0.0.1、指向内网的域名都能绕过。
     */
    private boolean isInternalHost(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress()      // 127.x、::1
                    || address.isAnyLocalAddress()   // 0.0.0.0
                    || address.isLinkLocalAddress()  // 169.254.x
                    || address.isSiteLocalAddress()  // 10.x、172.16-31.x、192.168.x
                    || address.isMulticastAddress();
        } catch (Exception ex) {
            // 解析不了的主机名，保守地拒绝
            log.warn("[isInternalHost][主机名({})解析失败，按内网处理]", host);
            return true;
        }
    }

    private HttpResponse doRequest(HttpConfigModel config, String url, String body,
                                    String authorization, String cookie, String tenantId,
                                    Map<String, Object> params) {
        int connectTimeout = config.getConnectTimeout() == null
                ? DEFAULT_CONNECT_TIMEOUT : config.getConnectTimeout();
        int readTimeout = config.getReadTimeout() == null
                ? DEFAULT_READ_TIMEOUT : config.getReadTimeout();
        String method = StrUtil.blankToDefault(config.getMethod(), "GET").toUpperCase();

        HttpRequest request = switch (method) {
            case "POST" -> HttpRequest.post(url);
            case "PUT" -> HttpRequest.put(url);
            case "DELETE" -> HttpRequest.delete(url);
            case "HEAD" -> HttpRequest.head(url);
            default -> HttpRequest.get(url);
        };
        request.setConnectionTimeout(connectTimeout).setReadTimeout(readTimeout);

        // 设置用户配置的请求头（新格式：List<HttpParam>）
        if (config.getHeaders() != null && !config.getHeaders().isEmpty()) {
            for (HttpParam header : config.getHeaders()) {
                // 跳过未启用的 Header
                if (Boolean.FALSE.equals(header.getEnabled())) {
                    continue;
                }
                if (StrUtil.isBlank(header.getKey())) {
                    continue;
                }
                // 替换占位符
                String value = replacePlaceholders(StrUtil.blankToDefault(header.getValue(), ""), params);
                request.header(header.getKey(), value);
            }
        }

        // 相对路径（本系统接口）透传关键请求头
        if (StrUtil.isNotBlank(authorization)) {
            request.header("Authorization", authorization);
        }
        if (StrUtil.isNotBlank(cookie)) {
            request.header("Cookie", cookie);
        }
        if (StrUtil.isNotBlank(tenantId)) {
            request.header("tenant-id", tenantId);
        }

        // 设置请求体和 Content-Type
        if (StrUtil.isNotBlank(body) && !"GET".equals(method) && !"HEAD".equals(method)) {
            String bodyType = StrUtil.blankToDefault(config.getBodyType(), "");

            // 根据 bodyType 设置 Content-Type（如果用户没有手动设置）
            if (StrUtil.isNotBlank(bodyType) && !hasContentType(config)) {
                switch (bodyType) {
                    case "x-www-form-urlencoded":
                        request.header("Content-Type", "application/x-www-form-urlencoded");
                        break;
                    case "json":
                        request.header("Content-Type", "application/json");
                        break;
                    case "xml":
                        request.header("Content-Type", "application/xml");
                        break;
                    case "form-data":
                        request.header("Content-Type", "multipart/form-data");
                        break;
                }
            }

            request.body(body);
        }
        try {
            return request.execute();
        } catch (Exception ex) {
            throw exception(DATA_INTERFACE_EXECUTE_FAIL, "HTTP 请求失败：" + ex.getMessage());
        }
    }

    /**
     * 检查是否已设置 Content-Type
     */
    private boolean hasContentType(HttpConfigModel config) {
        if (config.getHeaders() == null || config.getHeaders().isEmpty()) {
            return false;
        }
        return config.getHeaders().stream()
                .filter(h -> Boolean.TRUE.equals(h.getEnabled()))
                .anyMatch(h -> "Content-Type".equalsIgnoreCase(h.getKey()));
    }

    /**
     * 解析响应
     *
     * 能解析成 JSON 就返回结构化对象，否则返回原始文本 ——
     * 对方接口不一定返回 JSON，不该因此报错。
     */
    private Object parseResponse(HttpResponse response) {
        String text = response.body();
        if (text != null && text.length() > MAX_RESPONSE_LENGTH) {
            throw exception(DATA_INTERFACE_EXECUTE_FAIL,
                    "响应过大：" + text.length() + " 字符");
        }
        if (!response.isOk()) {
            throw exception(DATA_INTERFACE_EXECUTE_FAIL,
                    "对方返回 HTTP " + response.getStatus() + "：" + StrUtil.maxLength(text, 200));
        }
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(text, Object.class);
        } catch (Exception ex) {
            // 非 JSON 响应，原样返回
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("raw", text);
            return wrapper;
        }
    }

}
