package cn.enlev.ainb.module.ai.framework.ai.core.webserch.bocha;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.module.ai.framework.ai.core.webserch.AiWebSearchClient;
import cn.enlev.ainb.module.ai.framework.ai.core.webserch.AiWebSearchRequest;
import cn.enlev.ainb.module.ai.framework.ai.core.webserch.AiWebSearchResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.WEB_SEARCH_FAIL;

/**
 * 博查 {@link AiWebSearchClient} 实现类
 *
 * <h3>🔴 三处失败一律抛<b>带码</b>的 {@code ServiceException(WEB_SEARCH_FAIL)}，并把厂商原文带上</h3>
 * 原先抛的是 {@code IllegalStateException}：没有码，于是它落到
 * {@code GlobalExceptionHandler#defaultExceptionHandler} 那一支 —— 那一支<b>不透传 message</b>，
 * 返回 {@code INTERNAL_SERVER_ERROR(500, "系统异常")}。用户勾了「联网搜索」问一句话，
 * 博查那边密钥失效 / 余额不足 / 限流，界面上只有「系统异常」四个字：
 * 既看不出是搜索这一步，也看不出厂商说了什么，而厂商原文只在服务端日志里。
 * 「系统异常」这句话还把排查方向指向了平台自身（红线 §10.2 第 7 条：不替 error 编病因 ——
 * 反过来，把厂商说清了的病因压成一句通用话，同样是让人照错方向排查）。
 *
 * <p>⚠️ 带的是<b>引用</b>不是<b>解释</b>：状态码 + 响应体原文，截断到
 * {@link #VENDOR_TEXT_MAX_LENGTH} 字，防止代理/网关返回的整页 HTML 灌进界面与
 * {@code infra_api_error_log}。<b>不</b>去猜「大概是密钥不对」—— 猜错比不猜更贵。
 *
 * @see <a href="https://open.bochaai.com/overview">博查 AI 开放平台</a>
 *
 * @author 芋道源码
 */
@Slf4j
public class AiBoChaWebSearchClient implements AiWebSearchClient {

    public static final String BASE_URL = "https://api.bochaai.com";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 报错里带的厂商原文长度上限（字符）
     *
     * <p>正常的博查错误体是一两行 JSON；会超长的是「中间还有一层代理/CDN」时返回的整页 HTML。
     * 那种页面给用户看没有信息量，却会把界面撑破、把 {@code infra_api_error_log} 的
     * {@code exception_message} 灌满，所以截断。截断只砍尾巴，状态码与前几百字都在。
     */
    private static final int VENDOR_TEXT_MAX_LENGTH = 500;

    private final WebClient webClient;

    private final Predicate<HttpStatusCode> STATUS_PREDICATE = status -> !status.is2xxSuccessful();

    private final Function<Object, Function<ClientResponse, Mono<? extends Throwable>>> EXCEPTION_FUNCTION =
            reqParam -> response -> response.bodyToMono(String.class).handle((responseBody, sink) -> {
                log.error("[AiBoChaWebSearchClient] 调用失败！请求参数:[{}]，响应数据: [{}]", reqParam, responseBody);
                // 🔴 厂商原文进 message，不只进日志：日志要「先猜到去翻、再猜到哪一条」，
                // 而这一句直接出现在界面上（详见类注释）
                sink.error(exception(WEB_SEARCH_FAIL, StrUtil.format("HTTP {}，厂商响应：{}",
                        response.statusCode(), StrUtil.maxLength(responseBody, VENDOR_TEXT_MAX_LENGTH))));
            });

    public AiBoChaWebSearchClient(String apiKey) {
        this(apiKey, BASE_URL);
    }

    /**
     * @param baseUrl 厂商基址。生产一律走单参重载（{@link #BASE_URL}）。
     *                开这个重载只有一个理由：三处失败<b>全在 HTTP 应答之后</b>，
     *                不把请求指到一个本地假服务上，「报错里到底有没有厂商原文」就只能靠读代码相信
     *                （红线 §10.2：不把文字描述当结论）。故它是包级可见，不对外成为一份 API
     */
    AiBoChaWebSearchClient(String apiKey, String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders((headers) -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.add(AUTHORIZATION_HEADER, BEARER_PREFIX + apiKey);
                })
                .build();
    }

    @Override
    public AiWebSearchResponse search(AiWebSearchRequest request) {
        // 转换请求参数
        WebSearchRequest webSearchRequest = new WebSearchRequest(
                request.getQuery(),
                request.getSummary(),
                request.getCount()
        );
        // 调用博查 API
        CommonResult<WebSearchResponse> response = this.webClient.post()
                .uri("/v1/web-search")
                .bodyValue(webSearchRequest)
                .retrieve()
                .onStatus(STATUS_PREDICATE, EXCEPTION_FUNCTION.apply(webSearchRequest))
                .bodyToMono(new ParameterizedTypeReference<CommonResult<WebSearchResponse>>() {})
                .block();
        if (response == null) {
            // block() 拿到 null：HTTP 是 2xx（否则走上面的 EXCEPTION_FUNCTION），但响应体是空的。
            // 成因照样引用事实、不编：说清「2xx 且没有响应体」，比一句「搜索结果为空」更能定位
            throw exception(WEB_SEARCH_FAIL, "厂商返回 2xx 但响应体为空");
        }
        if (response.getData() == null) {
            throw exception(WEB_SEARCH_FAIL, StrUtil.maxLength(StrUtil.format(
                    "厂商业务码 {}：{}", response.getCode(), response.getMsg()), VENDOR_TEXT_MAX_LENGTH));
        }
        WebSearchResponse data = response.getData();

        // 转换结果
        AiWebSearchResponse result = new AiWebSearchResponse();
        if (data.webPages() == null || CollUtil.isEmpty(data.webPages().value())) {
            return result.setTotal(0L).setLists(List.of());
        }
        return result.setTotal(data.webPages().totalEstimatedMatches())
                .setLists(convertList(data.webPages().value(), page -> new AiWebSearchResponse.WebPage()
                        .setName(page.siteName()).setIcon(page.siteIcon())
                        .setTitle(page.name()).setUrl(page.url())
                        .setSnippet(page.snippet()).setSummary(page.summary())));
    }

    /**
     * 网页搜索请求参数
     */
    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record WebSearchRequest(
            String query,
            Boolean summary,
            Integer count
    ) {
        public WebSearchRequest {
            Assert.notBlank(query, "query 不能为空");
        }
    }

    /**
     * 网页搜索响应
     */
    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record WebSearchResponse(
            WebSearchWebPages webPages
    ) {
    }

    /**
     * 网页搜索结果
     */
    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record WebSearchWebPages(
            String webSearchUrl,
            Long totalEstimatedMatches,
            List<WebPageValue> value,
            Boolean someResultsRemoved
    ) {

        /**
         * 网页结果值
         */
        @JsonInclude(value = JsonInclude.Include.NON_NULL)
        public record WebPageValue(
                String id,
                String name,
                String url,
                String displayUrl,
                String snippet,
                String summary,
                String siteName,
                String siteIcon,
                String datePublished,
                String dateLastCrawled,
                String cachedPageUrl,
                String language,
                Boolean isFamilyFriendly,
                Boolean isNavigational
        ) {
        }

    }

}
