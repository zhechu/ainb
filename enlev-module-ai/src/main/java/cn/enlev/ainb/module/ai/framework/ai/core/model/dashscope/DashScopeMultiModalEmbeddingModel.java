package cn.enlev.ainb.module.ai.framework.ai.core.model.dashscope;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 通义千问<b>多模态向量</b>模型（{@code EmbeddingModel} 适配）
 *
 * <h3>为什么非自己写一个不可</h3>
 * {@code spring-ai-alibaba-dashscope-1.1.2.2} 里只有<b>一个</b>向量实现
 * （{@code DashScopeEmbeddingModel}），它把请求固定打到
 * {@code DashScopeApiConstants.TEXT_EMBEDDING_RESTFUL_URL}
 * （{@code /api/v1/services/embeddings/text-embedding/text-embedding}）；
 * 整个 jar 里<b>零个</b>多模态向量类，常量表里也<b>没有</b>多模态向量的 URL
 * （已用 {@code javap -p -constants} 逐个核对）。
 * 于是 {@code qwen3-vl-embedding}、{@code tongyi-embedding-vision-*} 这类模型
 * 被登记成「向量」之后，一律被送去撞文本向量端点，厂商回一句
 * <pre>400 - {"code":"InvalidParameter","message":"url error, please check url！"}</pre>
 * 界面上就是「连不上」。
 *
 * <p>🔴 2026-10-26 直连 {@code dashscope.aliyuncs.com} 三端点逐个实测（同一个密钥）：
 * <pre>
 *   gte-rerank-v2                  仅 rerank 端点通（文本/多模态向量均 url error）
 *   qwen3-vl-embedding             仅多模态向量端点通，维度 2560
 *   qwen2.5-vl-embedding           仅多模态向量端点通，维度 1024
 *   tongyi-embedding-vision-flash  仅多模态向量端点通，维度 768
 *   tongyi-embedding-vision-plus   仅多模态向量端点通，维度 1152
 *   text-embedding-v4              仅文本向量端点通，维度 1024（对照）
 * </pre>
 * 也就是说这些模型<b>本身都是好的</b>，坏的只是端点路由 —— 所以修法是补端点，不是把它们停用。
 *
 * <h3>它只被当「文本向量模型」用，这不是浪费</h3>
 * 多模态端点接受 {@code contents:[{text:...}]}，纯文本进去照样出向量（上面那 4 个维度就是
 * 用纯文本实测出来的）。知识库流水线是纯文本的，所以接上它等于<b>白捡 4 个可用的向量模型</b>：
 * 「额度耗尽自动切下一个」（{@code AiEmbeddingFailoverServiceImpl}）此前实际只有
 * {@code text-embedding-v4} 与 {@code qwen3.7-text-embedding-flash} 两个候选，
 * 另外 4 个启用的向量模型每次都被探成 CONFIG 失败、白烧一次厂商请求再冷却 30 分钟。
 *
 * <h3>🔴 批量必须按 {@code index} 归位，不能按数组下标取</h3>
 * {@code SimpleVectorStore#add(List)} 走 {@code embed(List<Document>)} 一次提交整批，
 * 返回的第 i 条向量会被挂到第 i 个段落上。若厂商某天不按请求顺序返回，按下标取就是
 * <b>把向量挂到错误的段落</b> —— 检索照常返回结果、分数看着也正常，只是内容是别人的，
 * 没有任何报错能发现。实测（3 条与 12 条各一轮）当前是按序的、且每条都带 {@code index}，
 * 所以这里按 {@code index} 归位并<b>逐格校验齐整</b>：少一条、多一条、下标越界、重复，
 * 一律抛异常而不是凑合着用。宁可响亮地失败，也不要静默地错位。
 *
 * @author 走进新时代
 * @see cn.enlev.ainb.module.ai.util.AiUtils#isTongYiMultiModalEmbeddingModel(String)
 */
public class DashScopeMultiModalEmbeddingModel extends AbstractEmbeddingModel {

    /**
     * 多模态向量端点。SDK 的 {@code DashScopeApiConstants} 里没有这一条，只能自己钉住
     */
    public static final String MULTIMODAL_EMBEDDING_RESTFUL_URL =
            "/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";

    private final RestClient restClient;

    private final String model;

    private final MetadataMode metadataMode;

    /**
     * @param restClientBuilder 必须用 {@code AiModelFactoryImpl#timedRestClientBuilder()}：
     *                          厂商挂起时没有超时就是永久占住一个 Tomcat 线程（见那处的 javadoc）
     * @param baseUrl           已由调用方 {@code blankToDefault(url, DEFAULT_BASE_URL)} 兜过
     */
    public DashScopeMultiModalEmbeddingModel(RestClient.Builder restClientBuilder, String baseUrl, String apiKey,
                                             String model, MetadataMode metadataMode) {
        Assert.notBlank(baseUrl, "多模态向量模型的 baseUrl 不能为空");
        Assert.notBlank(model, "多模态向量模型的 model 不能为空");
        this.restClient = restClientBuilder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.model = model;
        this.metadataMode = metadataMode;
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getFormattedContent(metadataMode));
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        if (texts == null || texts.isEmpty()) {
            return new EmbeddingResponse(List.of());
        }
        // 🔴 不吞非 2xx：RestClient 默认的状态处理器会抛 HttpClientErrorException / HttpServerErrorException，
        //    其 message 里带着厂商响应体原文。AiModelFailureReason.classify 与模型测试弹窗都靠这段原文
        //    分型（QUOTA / CONFIG / TRANSIENT），自己包一层就等于把病因抹掉
        MultiModalEmbeddingResponse response = restClient.post()
                .uri(MULTIMODAL_EMBEDDING_RESTFUL_URL)
                .body(new MultiModalEmbeddingRequest(model,
                        new MultiModalInput(texts.stream().map(MultiModalContent::new).toList())))
                .retrieve()
                .body(MultiModalEmbeddingResponse.class);
        return toEmbeddingResponse(texts.size(), response);
    }

    /**
     * 厂商响应 → spring-ai 的 {@link EmbeddingResponse}，按 {@code index} 归位并逐格校验齐整
     */
    private EmbeddingResponse toEmbeddingResponse(int expected, MultiModalEmbeddingResponse response) {
        List<MultiModalEmbeddingItem> items = response == null || response.output() == null
                ? null : response.output().embeddings();
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException(StrUtil.format(
                    "多模态向量端点({}) 返回了空响应，期望 {} 条向量", model, expected));
        }
        float[][] aligned = new float[expected][];
        for (MultiModalEmbeddingItem item : items) {
            Integer index = item == null ? null : item.index();
            if (index == null || index < 0 || index >= expected) {
                throw new IllegalStateException(StrUtil.format(
                        "多模态向量端点({}) 返回的 index({}) 越界，期望落在 [0,{}) 内："
                                + "不敢按下标猜对应关系，猜错就是把向量挂到别的段落上", model, index, expected));
            }
            if (item.embedding() == null || item.embedding().length == 0) {
                throw new IllegalStateException(StrUtil.format(
                        "多模态向量端点({}) 第 {} 条没有向量内容", model, index));
            }
            if (aligned[index] != null) {
                throw new IllegalStateException(StrUtil.format(
                        "多模态向量端点({}) 返回了重复的 index({})", model, index));
            }
            aligned[index] = item.embedding();
        }
        List<Embedding> embeddings = new ArrayList<>(expected);
        for (int i = 0; i < expected; i++) {
            if (aligned[i] == null) {
                throw new IllegalStateException(StrUtil.format(
                        "多模态向量端点({}) 少返回了第 {} 条向量（请求 {} 条、返回 {} 条）",
                        model, i, expected, items.size()));
            }
            embeddings.add(new Embedding(aligned[i], i));
            // 顺手把维度记进 AbstractEmbeddingModel 的缓存位，dimensions() 就不必再发一次探测请求
            embeddingDimensions.set(aligned[i].length);
        }
        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata(model, toUsage(response.usage())));
    }

    /**
     * 厂商用量 → spring-ai 的 {@link Usage}
     *
     * <p>🔴 多模态向量端点的 {@code usage} 字段名<b>随模型而变</b>，2026-10-26 直连逐字实测：
     * <pre>
     *   qwen3-vl-embedding              {"image_tokens":0,"input_tokens":31,"total_tokens":31}
     *   qwen2.5-vl-embedding            {"image_tokens":0,"input_tokens":31}          ← 没有 total_tokens
     *   tongyi-embedding-vision-flash   {"input_tokens":12,"input_tokens_details":{…},
     *                                    "output_tokens":1,"total_tokens":13}          ← 多一层 details，还报了 output
     *   tongyi-embedding-vision-plus    同上
     * </pre>
     * 所以两个字段都得当可空处理，{@code ignoreUnknown} 也不是保险而是必需。
     *
     * <p>🔴 为什么在<b>这里</b>做兜底、而不是丢给下游：{@code DefaultUsage} 的 4 参构造把
     * null 一律按 0 处理（已 javap 核实字节码：{@code promptTokens = arg ?? 0}），
     * 「厂商没报」到下游就变成「输入 0」—— 一个厂商从没说过的数字，而且下游再也分不出来。
     * 因此趁还拿得到原始 record：{@code input_tokens} 缺了就退到 {@code total_tokens}
     * （向量模型不生成 token，实测两者相等），两个都缺就整个返回 null，
     * 让 {@code EmbeddingResponseMetadata} 顶上 {@code EmptyUsage}、下游按「厂商没报」处理。
     */
    private static Usage toUsage(MultiModalUsage usage) {
        if (usage == null) {
            return null;
        }
        Integer promptTokens = usage.inputTokens() != null ? usage.inputTokens() : usage.totalTokens();
        if (promptTokens == null) {
            return null;
        }
        return new DefaultUsage(promptTokens, null, usage.totalTokens(), usage);
    }

    // ========== 厂商 DTO（多模态向量端点的报文形状与文本向量端点不同，不能共用 SDK 的 spec） ==========

    /**
     * 请求体：{@code {"model":"...","input":{"contents":[{"text":"..."}]}}}
     */
    public record MultiModalEmbeddingRequest(String model, MultiModalInput input) {
    }

    public record MultiModalInput(List<MultiModalContent> contents) {
    }

    public record MultiModalContent(String text) {
    }

    /**
     * 响应体：{@code {"output":{"embeddings":[{"embedding":[...],"index":0,"type":"text"}]},"usage":{...}}}
     *
     * <p>{@code ignoreUnknown} 不是保险而是必需：实测同一端点在不同模型上回的 {@code usage}
     * 字段集合并不一致（{@code image_tokens} / {@code input_tokens_details} / {@code output_tokens}
     * 各有出没），不放过未知字段就会因为厂商加了一个计费维度而整体解析失败
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MultiModalEmbeddingResponse(MultiModalOutput output, MultiModalUsage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MultiModalOutput(List<MultiModalEmbeddingItem> embeddings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MultiModalEmbeddingItem(@JsonProperty("embedding") float[] embedding,
                                          @JsonProperty("index") Integer index,
                                          @JsonProperty("type") String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MultiModalUsage(@JsonProperty("input_tokens") Integer inputTokens,
                                  @JsonProperty("total_tokens") Integer totalTokens) {
    }

}
