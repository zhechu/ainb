package cn.enlev.ainb.module.ai.framework.ai.core.model;

import cn.hutool.core.io.FileUtil;
import cn.hutool.cache.Cache;
import cn.hutool.cache.CacheUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.lang.func.Func0;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.enlev.ainb.framework.common.util.spring.SpringUtils;
import cn.enlev.ainb.module.ai.enums.model.AiPlatformEnum;
import cn.enlev.ainb.module.ai.framework.ai.config.AiAutoConfiguration;
import cn.enlev.ainb.module.ai.framework.ai.config.EnlevAiProperties;
import cn.enlev.ainb.module.ai.framework.ai.core.model.baichuan.BaiChuanChatModel;
import cn.enlev.ainb.module.ai.framework.ai.core.model.dashscope.DashScopeMultiModalEmbeddingModel;
import cn.enlev.ainb.module.ai.framework.ai.core.model.doubao.DouBaoChatModel;
import cn.enlev.ainb.module.ai.framework.ai.core.model.gemini.GeminiChatModel;
import cn.enlev.ainb.module.ai.framework.ai.core.model.grok.GrokChatModel;
import cn.enlev.ainb.module.ai.framework.ai.core.model.hunyuan.HunYuanChatModel;
import cn.enlev.ainb.module.ai.framework.ai.core.model.midjourney.api.MidjourneyApi;
import cn.enlev.ainb.module.ai.framework.ai.core.model.minimax.MiniMaxChatModel;
import cn.enlev.ainb.module.ai.framework.ai.core.model.moonshot.MoonshotChatModel;
import cn.enlev.ainb.module.ai.framework.ai.core.model.siliconflow.SiliconFlowApiConstants;
import cn.enlev.ainb.module.ai.framework.ai.core.model.siliconflow.SiliconFlowChatModel;
import cn.enlev.ainb.module.ai.framework.ai.core.model.siliconflow.SiliconFlowImageApi;
import cn.enlev.ainb.module.ai.framework.ai.core.model.siliconflow.SiliconFlowImageModel;
import cn.enlev.ainb.module.ai.framework.ai.core.model.stepfun.StepFunChatModel;
import cn.enlev.ainb.module.ai.framework.ai.core.model.suno.api.SunoApi;
import cn.enlev.ainb.module.ai.framework.ai.core.model.xinghuo.XingHuoChatModel;
import cn.enlev.ainb.module.ai.framework.ai.core.model.yiyan.YiYanChatModel;
import cn.enlev.ainb.module.ai.framework.ai.core.model.zhipu.ZhiPuChatModel;
import cn.enlev.ainb.module.ai.util.AiUtils;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeImageAutoConfiguration;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.api.DashScopeImageApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.common.DashScopeApiConstants;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.alibaba.cloud.ai.dashscope.image.DashScopeImageModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.model.RerankModel;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.KeyCredential;
import io.micrometer.observation.ObservationRegistry;
import io.milvus.client.MilvusServiceClient;
import io.netty.channel.ChannelOption;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiEmbeddingModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationConvention;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiChatAutoConfiguration;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiEmbeddingProperties;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.stabilityai.autoconfigure.StabilityAiImageAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiImageApi;
import org.springframework.ai.openai.api.common.OpenAiApiConstants;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.stabilityai.StabilityAiImageModel;
import org.springframework.ai.stabilityai.api.StabilityAiApi;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusServiceClientConnectionDetails;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusServiceClientProperties;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreProperties;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration;
import org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreProperties;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreAutoConfiguration;
import org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import redis.clients.jedis.JedisPooled;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;

/**
 * AI Model 模型工厂的实现类
 *
 * @author 芋道源码
 */
@Slf4j
public class AiModelFactoryImpl implements AiModelFactory {

    /**
     * 客户端实例缓存的容量上限
     *
     * 一个实例对应一组 (类型, 平台, apiKey, url)。正常部署下同时在用的平台不过十来个，
     * 64 足够覆盖，同时把「密钥轮换若干次之后」的内存占用钉死在一个常数上。
     */
    private static final int CLIENT_CACHE_SIZE = 64;

    /**
     * 客户端实例缓存（缺陷 B6）
     *
     * <h3>为什么不再用 hutool 的 {@code Singleton}</h3>
     * {@code Singleton} 底层是一个<b>永不淘汰</b>的 Map。而这里的缓存键含 apiKey ——
     * 每轮换一次密钥就多出一个再也用不到的客户端实例，连同它持有的 HTTP 连接池
     * <b>永久驻留</b>。长期运行的实例上这是一条只增不减的内存占用。
     *
     * <p>⚠️ 它<b>不</b>是「会误用旧密钥」的正确性缺陷 —— 新密钥会正确地建出新实例。
     * 换成 LRU 之后行为完全一致，只是给驻留量加了个上限。
     */
    private static final Cache<String, Object> CLIENT_CACHE = CacheUtil.newLRUCache(CLIENT_CACHE_SIZE);

    /**
     * 取缓存，没有就建一个
     *
     * <p>双重检查 + 同步：建客户端是重操作（要建 HTTP 连接池），
     * 并发首次调用时不做同步会建出好几个、然后互相覆盖，多出来的那些连接池没人关。
     */
    @SuppressWarnings("unchecked")
    private static <T> T getOrCreateClient(String cacheKey, Func0<T> supplier) {
        Object cached = CLIENT_CACHE.get(cacheKey, false);
        if (cached != null) {
            return (T) cached;
        }
        synchronized (CLIENT_CACHE) {
            cached = CLIENT_CACHE.get(cacheKey, false);
            if (cached != null) {
                return (T) cached;
            }
            T created;
            try {
                created = supplier.call();
            } catch (Exception ex) {
                // 与 Singleton 的行为保持一致：建失败就往上抛，不缓存失败结果
                throw ExceptionUtil.wrapRuntime(ex);
            }
            CLIENT_CACHE.put(cacheKey, created);
            return created;
        }
    }

    @Override
    public ChatModel getOrCreateChatModel(AiPlatformEnum platform, String rawApiKey, String rawUrl) {
        final String apiKey = resolveSpringPlaceholders(rawApiKey);
        final String url = resolveSpringPlaceholders(rawUrl);
        String cacheKey = buildClientCacheKey(ChatModel.class, platform, apiKey, url);
        // ⚠️ Hutool Singleton 无淘汰机制：密钥轮换后旧实例会长期驻留内存（泄漏），
        // 但新密钥会正确建出新实例，不存在「误用旧密钥」的行为问题。
        // 若将来需要支持热更新密钥，可改用 Caffeine 并配置过期策略。
        return getOrCreateClient(cacheKey, (Func0<ChatModel>) () -> {
            // noinspection EnhancedSwitchMigration
            switch (platform) {
                case TONG_YI:
                    return buildTongYiChatModel(apiKey, url);
                case YI_YAN:
                    return buildYiYanChatModel(apiKey);
                case DEEP_SEEK:
                    return buildDeepSeekChatModel(apiKey);
                case DOU_BAO:
                    return buildDouBaoChatModel(apiKey);
                case HUN_YUAN:
                    return buildHunYuanChatModel(apiKey, url);
                case SILICON_FLOW:
                    return buildSiliconFlowChatModel(apiKey);
                case ZHI_PU:
                    return buildZhiPuChatModel(apiKey, url);
                case MINI_MAX:
                    return buildMiniMaxChatModel(apiKey, url);
                case MOONSHOT:
                    return buildMoonshotChatModel(apiKey, url);
                case STEP_FUN:
                    return buildStepFunChatModel(apiKey, url);
                case XING_HUO:
                    return buildXingHuoChatModel(apiKey);
                case BAI_CHUAN:
                    return buildBaiChuanChatModel(apiKey);
                case OPENAI:
                    return buildOpenAiChatModel(apiKey, url);
                case AZURE_OPENAI:
                    return buildAzureOpenAiChatModel(apiKey, url);
                case ANTHROPIC:
                    return buildAnthropicChatModel(apiKey, url);
                case GEMINI:
                    return buildGeminiChatModel(apiKey);
                case OLLAMA:
                    return buildOllamaChatModel(url);
                case GROK:
                    return buildGrokChatModel(apiKey, url);
                default:
                    throw new IllegalArgumentException(StrUtil.format("未知平台({})", platform));
            }
        });
    }

    @Override
    public ChatModel getDefaultChatModel(AiPlatformEnum platform) {
        // noinspection EnhancedSwitchMigration
        switch (platform) {
            case TONG_YI:
                return SpringUtil.getBean(DashScopeChatModel.class);
            case YI_YAN:
                return SpringUtil.getBean(YiYanChatModel.class);
            case DEEP_SEEK:
                return SpringUtil.getBean(DeepSeekChatModel.class);
            case DOU_BAO:
                return SpringUtil.getBean(DouBaoChatModel.class);
            case HUN_YUAN:
                return SpringUtil.getBean(HunYuanChatModel.class);
            case SILICON_FLOW:
                return SpringUtil.getBean(SiliconFlowChatModel.class);
            case ZHI_PU:
                return SpringUtil.getBean(ZhiPuChatModel.class);
            case MINI_MAX:
                return SpringUtil.getBean(MiniMaxChatModel.class);
            case MOONSHOT:
                return SpringUtil.getBean(MoonshotChatModel.class);
            case STEP_FUN:
                return SpringUtil.getBean(StepFunChatModel.class);
            case XING_HUO:
                return SpringUtil.getBean(XingHuoChatModel.class);
            case BAI_CHUAN:
                return SpringUtil.getBean(BaiChuanChatModel.class);
            case OPENAI:
                return SpringUtil.getBean(OpenAiChatModel.class);
            case AZURE_OPENAI:
                return SpringUtil.getBean(AzureOpenAiChatModel.class);
            case ANTHROPIC:
                return SpringUtil.getBean(AnthropicChatModel.class);
            case GEMINI:
                return SpringUtil.getBean(GeminiChatModel.class);
            case OLLAMA:
                return SpringUtil.getBean(OllamaChatModel.class);
            case GROK:
                return SpringUtil.getBean(GrokChatModel.class);
            default:
                throw new IllegalArgumentException(StrUtil.format("未知平台({})", platform));
        }
    }

    @Override
    public ImageModel getDefaultImageModel(AiPlatformEnum platform) {
        // noinspection EnhancedSwitchMigration
        switch (platform) {
            case TONG_YI:
                return SpringUtil.getBean(DashScopeImageModel.class);
            case SILICON_FLOW:
                return SpringUtil.getBean(SiliconFlowImageModel.class);
            case OPENAI:
                return SpringUtil.getBean(OpenAiImageModel.class);
            case STABLE_DIFFUSION:
                return SpringUtil.getBean(StabilityAiImageModel.class);
            default:
                throw new IllegalArgumentException(StrUtil.format("未知平台({})", platform));
        }
    }

    @Override
    public ImageModel getOrCreateImageModel(AiPlatformEnum platform, String rawApiKey, String rawUrl) {
        String apiKey = resolveSpringPlaceholders(rawApiKey);
        String url = resolveSpringPlaceholders(rawUrl);
        // noinspection EnhancedSwitchMigration
        switch (platform) {
            case TONG_YI:
                return buildTongYiImagesModel(apiKey);
            case OPENAI:
                return buildOpenAiImageModel(apiKey, url);
            case SILICON_FLOW:
                return buildSiliconFlowImageModel(apiKey, url);
            case STABLE_DIFFUSION:
                return buildStabilityAiImageModel(apiKey, url);
            default:
                throw new IllegalArgumentException(StrUtil.format("未知平台({})", platform));
        }
    }

    @Override
    public MidjourneyApi getOrCreateMidjourneyApi(String rawApiKey, String rawUrl) {
        final String apiKey = resolveSpringPlaceholders(rawApiKey);
        final String url = resolveSpringPlaceholders(rawUrl);
        String cacheKey = buildClientCacheKey(MidjourneyApi.class, AiPlatformEnum.MIDJOURNEY.getPlatform(),
                apiKey, url);
        return getOrCreateClient(cacheKey, (Func0<MidjourneyApi>) () -> {
            EnlevAiProperties.Midjourney properties = SpringUtil.getBean(EnlevAiProperties.class)
                    .getMidjourney();
            return new MidjourneyApi(url, apiKey, properties.getNotifyUrl());
        });
    }

    @Override
    public SunoApi getOrCreateSunoApi(String rawApiKey, String rawUrl) {
        final String apiKey = resolveSpringPlaceholders(rawApiKey);
        final String url = resolveSpringPlaceholders(rawUrl);
        String cacheKey = buildClientCacheKey(SunoApi.class, AiPlatformEnum.SUNO.getPlatform(), apiKey, url);
        return getOrCreateClient(cacheKey, (Func0<SunoApi>) () -> new SunoApi(url));
    }

    @Override
    @SuppressWarnings("EnhancedSwitchMigration")
    public EmbeddingModel getOrCreateEmbeddingModel(AiPlatformEnum platform, String rawApiKey, String rawUrl, String model) {
        final String apiKey = resolveSpringPlaceholders(rawApiKey);
        final String url = resolveSpringPlaceholders(rawUrl);
        String cacheKey = buildClientCacheKey(EmbeddingModel.class, platform, apiKey, url, model);
        return getOrCreateClient(cacheKey, (Func0<EmbeddingModel>) () -> {
            switch (platform) {
                case TONG_YI:
                    return buildTongYiEmbeddingModel(apiKey, url, model);
                case OPENAI:
                    return buildOpenAiEmbeddingModel(apiKey, url, model);
                case AZURE_OPENAI:
                    return buildAzureOpenAiEmbeddingModel(apiKey, url, model);
                case OLLAMA:
                    return buildOllamaEmbeddingModel(url, model);
                default:
                    throw new IllegalArgumentException(StrUtil.format("未知平台({})", platform));
            }
        });
    }

    @Override
    public RerankModel getOrCreateRerankModel(AiPlatformEnum platform, String rawApiKey, String rawUrl, String model) {
        final String apiKey = resolveSpringPlaceholders(rawApiKey);
        final String url = resolveSpringPlaceholders(rawUrl);
        String cacheKey = buildClientCacheKey(RerankModel.class, platform, apiKey, url, model);
        return getOrCreateClient(cacheKey, (Func0<RerankModel>) () -> {
            if (platform != AiPlatformEnum.TONG_YI) {
                // 明确抛而不是回落成别的平台：重排序只有通义有 SDK 支持，
                // 拿别的平台的名字建一个「看着像能用」的客户端，失败点会推迟到厂商那边、更难归因
                throw new IllegalArgumentException(StrUtil.format("平台({})暂不支持重排序模型", platform));
            }
            return buildTongYiRerankModel(apiKey, url, model);
        });
    }

    @Override
    public VectorStore getOrCreateVectorStore(Class<? extends VectorStore> type,
                                              EmbeddingModel embeddingModel,
                                              String embeddingModelName,
                                              Map<String, Class<?>> metadataFields) {
        String cacheKey = buildClientCacheKey(VectorStore.class, embeddingModel, embeddingModelName, type);
        return getOrCreateClient(cacheKey, (Func0<VectorStore>) () -> {
            if (type == SimpleVectorStore.class) {
                return buildSimpleVectorStore(embeddingModel, embeddingModelName);
            }
            if (type == QdrantVectorStore.class) {
                return buildQdrantVectorStore(embeddingModel);
            }
            if (type == RedisVectorStore.class) {
                return buildRedisVectorStore(embeddingModel, metadataFields);
            }
            if (type == MilvusVectorStore.class) {
                return buildMilvusVectorStore(embeddingModel);
            }
            throw new IllegalArgumentException(StrUtil.format("未知类型({})", type));
        });
    }

    private static String buildClientCacheKey(Class<?> clazz, Object... params) {
        if (ArrayUtil.isEmpty(params)) {
            return clazz.getName();
        }
        return StrUtil.format("{}#{}", clazz.getName(), ArrayUtil.join(params, "_"));
    }

    private static String resolveSpringPlaceholders(String value) {
        // yml 配置的占位符由 Spring 自动解析；DB 里保存的 ${xxx} 需要在这里手动解析。
        return AiUtils.resolveSpringPlaceholders(value);
    }

    // ========== 带超时的 HTTP 客户端构造（A4 / 缺陷 B18） ==========
    //
    // ⚠️ 覆盖面（逐个数过，不是「大概都接上了」）：
    //   已接：TONG_YI（chat / image / embedding）、DEEP_SEEK、OPENAI（chat / image / embedding）、
    //         ANTHROPIC、OLLAMA（chat / embedding）。判据是「该平台的 *Api.Builder 是否暴露
    //         restClientBuilder / webClientBuilder」，已用 javap 对 spring-ai 1.1.8 与
    //         spring-ai-alibaba-dashscope 1.1.2.2 逐个核对；image 那两个 Api 只有 restClientBuilder。
    //   未接：YI_YAN / DOU_BAO / HUN_YUAN / SILICON_FLOW / ZHI_PU / MINI_MAX / MOONSHOT /
    //         STEP_FUN / XING_HUO / BAI_CHUAN / GEMINI / GROK / AZURE_OPENAI。
    //         前 12 个走 new AiAutoConfiguration().buildXxxChatClient(properties)，
    //         客户端在那边内联构造，本文件拿不到 builder；AZURE_OPENAI 用的是 Azure SDK 自己的
    //         OpenAIClientBuilder，不吃 Spring 的 RestClient/WebClient。
    //         🔴 这不是「假装覆盖」：那 12 个平台要生效，前提是 yml 里把 enlev.ai.<platform>.enable
    //         打开、由 AiAutoConfiguration 出 bean，而唯一的取用入口 getDefaultChatModel
    //         在 src/main 下<b>零调用点</b>（已 grep 核实）。也就是说线上真正会走的只有上面「已接」那批。
    //         若哪天有人给 getDefaultChatModel 接上调用方，必须同时去 AiAutoConfiguration 补超时。

    /**
     * 带超时的 {@link RestClient.Builder}，给所有<b>同步</b>厂商调用用
     *
     * <h3>为什么非加不可</h3>
     * 本文件原本<b>零超时设置</b>。厂商侧一挂起，占住的就是一个 Tomcat 线程，而且是<b>永久</b>：
     * Reactor 的 {@code doOnComplete} / {@code doOnError} / {@code doOnCancel} 三条都不触发，
     * 服务端既不报错也不留日志，界面上是一个永远转不完的圈。
     * {@code EnlevAiProperties.Stream#timeout} 只兜住流式那条路（量的是相邻分片间隔），
     * 同步 {@code call} 完全没人管 —— 本方法补的就是这一层。
     *
     * <h3>🔴 为什么手写 {@code SimpleClientHttpRequestFactory} 而不是 {@code detect()}</h3>
     * 因为 {@code detect()} 的结果<b>由依赖树决定，而依赖树会变</b> —— 超时语义不该跟着它漂。
     * 实测（2026-10-26，抓 {@code DashScopeRerankModel} 的出线请求头）本模块当前探到的是
     * <b>HttpComponents</b>：{@code User-Agent: Apache-HttpClient/5.5.2}，
     * 由 {@code software.amazon.awssdk:apache5-client} 传递进来（{@code dependency:tree} 可复现）。
     * 它的 socketTimeout 是 socket 级的，能兜住「等响应头」这一步，所以<b>今天</b>换成 detect() 不会出事。
     *
     * <p>但 awssdk 是 infra 文件存储带进来的传递依赖，跟 AI 调用毫无关系：哪天它被裁掉或换了实现，
     * {@code detect()} 会<b>静默</b>退到 JDK 的 {@code JdkClientHttpRequestFactory}，
     * 而 JDK HttpClient 的 read-timeout 只兜「响应头已到达之后」的读 —— 对着一个
     * 「TCP 连上、一个字都不回」的挂起服务端，请求卡在等响应头那一步，read-timeout 永远不触发
     * （实测：本地桩服务 + read-timeout=3s，一路耗满调用方自己的 30 秒硬超时）。
     * {@code SimpleClientHttpRequestFactory} 的 read-timeout 同样是 socket SO_TIMEOUT，
     * 等响应头那次 read 也在它的计量范围内，且<b>不受 classpath 上有什么 HTTP 客户端影响</b>。
     * 代价是没有连接池、不支持 PATCH：厂商对话调用全是 POST/GET 且本就一次一连接，可接受。
     *
     * <p>（本段曾写反过一次，断言「dependency:tree 里根本没有 httpclient5」并据此说探到的是 JDK 那档 ——
     * 那句话是错的，已按上面的实测更正。留着这句是为了提醒：这类断言必须附可复现的测法。）
     *
     * <p>刻意<b>不</b>复用 Boot 自动配置的 {@code RestClient.Builder} bean：
     * 厂商调用的 read-timeout 需要远大于普通 Web 请求（见 {@code EnlevAiProperties.Http#readTimeout}
     * 上那条「实测单次 284 秒」），跟着全局配置走反而会把长思考误杀。
     * 已核实本仓库没有任何 {@code RestClientCustomizer}，也没有 {@code spring.http.client.*}，
     * 所以另起一个不丢任何既有行为。
     */
    private static RestClient.Builder timedRestClientBuilder() {
        EnlevAiProperties.Http http = SpringUtil.getBean(EnlevAiProperties.class).getHttp();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(http.getConnectTimeout());
        requestFactory.setReadTimeout(http.getReadTimeout());
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * 带<b>连接</b>超时的 {@link WebClient.Builder}，给所有<b>流式</b>厂商调用用
     *
     * <p>🔴 只设连接超时，<b>绝不</b>设 {@code responseTimeout} 或 {@code ReadTimeoutHandler}：
     * 那两者量的是「整个响应」，会掐断长时间的 SSE 流，把一次正常的长回答误杀成超时。
     * 流式挂起由 {@code EnlevAiProperties.Stream#timeout}（相邻分片 120 秒）兜 ——
     * 它量的是「相邻两个元素之间」的间隔，正好对应「TCP 连着但不再发数据」这个故障形态，
     * 位置是对的，不要挪到这里来。
     *
     * <p>连接超时仍然要设：厂商域名不可达时，应当在 10 秒内失败好让故障切换去试下一个候选，
     * 而不是等 reactor-netty 那个 30 秒默认值。
     */
    private static WebClient.Builder timedWebClientBuilder() {
        EnlevAiProperties.Http http = SpringUtil.getBean(EnlevAiProperties.class).getHttp();
        HttpClient httpClient = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                (int) http.getConnectTimeout().toMillis());
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }

    // ========== 各种创建 spring-ai 客户端的方法 ==========

    /**
     * 可参考 {@link DashScopeChatAutoConfiguration} 的 dashScopeChatModel 方法
     *
     * <p>🔴 A4 之前本方法<b>连 url 都不吃</b>：{@code AiApiKeyDO.url} 配了也被静默忽略，
     * 于是「私有化部署的通义网关」这类场景根本没法用，而界面上看不出来（配置项就在那儿，看着像生效了）。
     * 已核实当前库里唯一的通义密钥（id=6）{@code url} 是空串，所以接上默认值对现有行为是 no-op。
     */
    private static DashScopeChatModel buildTongYiChatModel(String key, String url) {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .baseUrl(StrUtil.blankToDefault(url, DashScopeApiConstants.DEFAULT_BASE_URL))
                .apiKey(key)
                .restClientBuilder(timedRestClientBuilder())
                .webClientBuilder(timedWebClientBuilder())
                .build();
        DashScopeChatOptions options = DashScopeChatOptions.builder().withModel(DashScopeApi.DEFAULT_CHAT_MODEL)
                .withTemperature(0.7).build();
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(options)
                .toolCallingManager(getToolCallingManager())
                .build();
    }

    /**
     * 可参考 {@link DashScopeImageAutoConfiguration} 的 dashScopeImageModel 方法
     *
     * <p>{@code DashScopeImageApi.Builder} 只暴露 {@code restClientBuilder}（无 webClient 那条路），
     * 已用 javap 逐一核对过，不是漏接。
     */
    private static DashScopeImageModel buildTongYiImagesModel(String key) {
        DashScopeImageApi dashScopeImageApi = DashScopeImageApi.builder().apiKey(key)
                .restClientBuilder(timedRestClientBuilder()).build();
        return DashScopeImageModel.builder()
                .dashScopeApi(dashScopeImageApi)
                .build();
    }

    private ChatModel buildYiYanChatModel(String apiKey) {
        EnlevAiProperties.YiYan properties = new EnlevAiProperties.YiYan()
                .setApiKey(apiKey);
        return new AiAutoConfiguration().buildYiYanChatClient(properties);
    }

    /**
     * 可参考 {@link DeepSeekChatAutoConfiguration} 的 deepSeekChatModel 方法
     */
    private static DeepSeekChatModel buildDeepSeekChatModel(String apiKey) {
        DeepSeekApi deepSeekApi = DeepSeekApi.builder().apiKey(apiKey)
                .restClientBuilder(timedRestClientBuilder())
                .webClientBuilder(timedWebClientBuilder())
                .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder().model(DeepSeekApi.DEFAULT_CHAT_MODEL)
                .temperature(0.7).build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(options)
                .toolCallingManager(getToolCallingManager())
                .build();
    }

    /**
     * 可参考 {@link AiAutoConfiguration#douBaoChatClient(EnlevAiProperties)}
     */
    private ChatModel buildDouBaoChatModel(String apiKey) {
        EnlevAiProperties.DouBao properties = new EnlevAiProperties.DouBao()
                .setApiKey(apiKey);
        return new AiAutoConfiguration().buildDouBaoChatClient(properties);
    }

    /**
     * 可参考 {@link AiAutoConfiguration#hunYuanChatClient(EnlevAiProperties)}
     */
    private ChatModel buildHunYuanChatModel(String apiKey, String url) {
        EnlevAiProperties.HunYuan properties = new EnlevAiProperties.HunYuan()
                .setBaseUrl(url).setApiKey(apiKey);
        return new AiAutoConfiguration().buildHunYuanChatClient(properties);
    }

    /**
     * 可参考 {@link AiAutoConfiguration#siliconFlowChatClient(EnlevAiProperties)}
     */
    private ChatModel buildSiliconFlowChatModel(String apiKey) {
        EnlevAiProperties.SiliconFlow properties = new EnlevAiProperties.SiliconFlow()
                .setApiKey(apiKey);
        return new AiAutoConfiguration().buildSiliconFlowChatClient(properties);
    }

    /**
     * 可参考 {@link AiAutoConfiguration#zhiPuChatClient(EnlevAiProperties)}
     */
    private ZhiPuChatModel buildZhiPuChatModel(String apiKey, String url) {
        EnlevAiProperties.ZhiPu properties = new EnlevAiProperties.ZhiPu()
                .setBaseUrl(url).setApiKey(apiKey);
        return new AiAutoConfiguration().buildZhiPuChatClient(properties);
    }

    /**
     * 可参考 {@link AiAutoConfiguration#miniMaxChatClient(EnlevAiProperties)}
     */
    private MiniMaxChatModel buildMiniMaxChatModel(String apiKey, String url) {
        EnlevAiProperties.MiniMax properties = new EnlevAiProperties.MiniMax()
                .setBaseUrl(url).setApiKey(apiKey);
        return new AiAutoConfiguration().buildMiniMaxChatClient(properties);
    }

    /**
     * 可参考 {@link AiAutoConfiguration#moonshotChatClient(EnlevAiProperties)}
     */
    private MoonshotChatModel buildMoonshotChatModel(String apiKey, String url) {
        EnlevAiProperties.Moonshot properties = new EnlevAiProperties.Moonshot()
                .setBaseUrl(url).setApiKey(apiKey);
        return new AiAutoConfiguration().buildMoonshotChatClient(properties);
    }

    /**
     * 可参考 {@link AiAutoConfiguration#stepFunChatClient(EnlevAiProperties)}
     */
    private StepFunChatModel buildStepFunChatModel(String apiKey, String url) {
        EnlevAiProperties.StepFun properties = new EnlevAiProperties.StepFun()
                .setBaseUrl(url).setApiKey(apiKey);
        return new AiAutoConfiguration().buildStepFunChatClient(properties);
    }

    /**
     * 可参考 {@link AiAutoConfiguration#xingHuoChatClient(EnlevAiProperties)}
     */
    private static XingHuoChatModel buildXingHuoChatModel(String apiKey) {
        EnlevAiProperties.XingHuo properties = new EnlevAiProperties.XingHuo()
                .setApiKey(apiKey).setModel(XingHuoChatModel.MODEL_DEFAULT);
        return new AiAutoConfiguration().buildXingHuoChatClient(properties);
    }

    /**
     * 可参考 {@link AiAutoConfiguration#baiChuanChatClient(EnlevAiProperties)}
     */
    private BaiChuanChatModel buildBaiChuanChatModel(String apiKey) {
        EnlevAiProperties.BaiChuan properties = new EnlevAiProperties.BaiChuan()
                .setApiKey(apiKey);
        return new AiAutoConfiguration().buildBaiChuanChatClient(properties);
    }

    /**
     * 可参考 {@link OpenAiChatAutoConfiguration} 的 openAiChatModel 方法
     */
    private static OpenAiChatModel buildOpenAiChatModel(String openAiToken, String url) {
        url = StrUtil.blankToDefault(url, OpenAiApiConstants.DEFAULT_BASE_URL);
        OpenAiApi openAiApi = OpenAiApi.builder().baseUrl(url).apiKey(openAiToken)
                .restClientBuilder(timedRestClientBuilder())
                .webClientBuilder(timedWebClientBuilder())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .toolCallingManager(getToolCallingManager())
                .build();
    }

    /**
     * 可参考 {@link AzureOpenAiChatAutoConfiguration}
     */
    private static AzureOpenAiChatModel buildAzureOpenAiChatModel(String apiKey, String url) {
        // TODO @芋艿：使用前，请测试，暂时没密钥！！！
        OpenAIClientBuilder openAIClientBuilder = new OpenAIClientBuilder()
                .endpoint(url).credential(new KeyCredential(apiKey));
        return AzureOpenAiChatModel.builder()
                .openAIClientBuilder(openAIClientBuilder)
                .toolCallingManager(getToolCallingManager())
                .build();
    }

    /**
     * 可参考 {@link AnthropicChatAutoConfiguration} 的 anthropicApi 方法
     */
    private static AnthropicChatModel buildAnthropicChatModel(String apiKey, String url) {
        AnthropicApi.Builder builder = AnthropicApi.builder().apiKey(apiKey)
                .restClientBuilder(timedRestClientBuilder())
                .webClientBuilder(timedWebClientBuilder());
        if (StrUtil.isNotEmpty(url)) {
            builder.baseUrl(url);
        }
        AnthropicApi anthropicApi = builder.build();
        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .toolCallingManager(getToolCallingManager())
                .build();
    }

    /**
     * 可参考 {@link AiAutoConfiguration#buildGeminiChatClient(EnlevAiProperties.Gemini)}
     */
    private static GeminiChatModel buildGeminiChatModel(String apiKey) {
        EnlevAiProperties.Gemini properties = SpringUtil.getBean(EnlevAiProperties.class)
                .getGemini().setApiKey(apiKey);
        return new AiAutoConfiguration().buildGeminiChatClient(properties);
    }

    /**
     * 可参考 {@link OpenAiImageAutoConfiguration} 的 openAiImageModel 方法
     */
    private OpenAiImageModel buildOpenAiImageModel(String openAiToken, String url) {
        url = StrUtil.blankToDefault(url, OpenAiApiConstants.DEFAULT_BASE_URL);
        // OpenAiImageApi.Builder 只暴露 restClientBuilder（无流式那条路），javap 核对过，不是漏接
        OpenAiImageApi openAiApi = OpenAiImageApi.builder().baseUrl(url).apiKey(openAiToken)
                .restClientBuilder(timedRestClientBuilder()).build();
        return new OpenAiImageModel(openAiApi);
    }

    /**
     * 创建 SiliconFlowImageModel 对象
     */
    private SiliconFlowImageModel buildSiliconFlowImageModel(String apiToken, String url) {
        url = StrUtil.blankToDefault(url, SiliconFlowApiConstants.DEFAULT_BASE_URL);
        SiliconFlowImageApi openAiApi = new SiliconFlowImageApi(url, apiToken);
        return new SiliconFlowImageModel(openAiApi);
    }

    /**
     * 可参考 {@link OllamaChatAutoConfiguration} 的 ollamaChatModel 方法
     */
    private static OllamaChatModel buildOllamaChatModel(String url) {
        OllamaApi ollamaApi = OllamaApi.builder().baseUrl(url)
                .restClientBuilder(timedRestClientBuilder())
                .webClientBuilder(timedWebClientBuilder())
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .toolCallingManager(getToolCallingManager())
                .build();
    }

    /**
     * 可参考 {@link StabilityAiImageAutoConfiguration} 的 stabilityAiImageModel 方法
     */
    private StabilityAiImageModel buildStabilityAiImageModel(String apiKey, String url) {
        url = StrUtil.blankToDefault(url, StabilityAiApi.DEFAULT_BASE_URL);
        StabilityAiApi stabilityAiApi = new StabilityAiApi(apiKey, StabilityAiApi.DEFAULT_IMAGE_MODEL, url);
        return new StabilityAiImageModel(stabilityAiApi);
    }

    private GrokChatModel buildGrokChatModel(String apiKey, String url) {
        EnlevAiProperties.Grok properties = new EnlevAiProperties.Grok()
                .setBaseUrl(url)
                .setApiKey(apiKey);
        return new AiAutoConfiguration().buildGrokChatClient(properties);
    }

    // ========== 各种创建 EmbeddingModel 的方法 ==========

    /**
     * 可参考 {@link DashScopeEmbeddingAutoConfiguration} 的 dashscopeEmbeddingModel 方法
     *
     * <h3>🔴 通义的向量端点有<b>两个</b>，必须按模型名分档</h3>
     * SDK 只给了文本向量那一个（{@code DashScopeEmbeddingModel} 把 URL 钉死成
     * {@code TEXT_EMBEDDING_RESTFUL_URL}），jar 里零个多模态向量类、常量表里也没有那个 URL。
     * 于是 {@code qwen3-vl-embedding} / {@code tongyi-embedding-vision-*} 这类模型被登记成「向量」之后
     * 一律去撞文本端点，厂商回 {@code 400 InvalidParameter "url error, please check url！"}，
     * 界面上是「连不上」。2026-10-26 三端点逐个实测：这些模型<b>本身都是好的</b>，
     * 只是端点不对（明细见 {@link DashScopeMultiModalEmbeddingModel} 的类注释），
     * 所以修法是补端点，不是把它们停用。分档清单与对话侧同源同纪律：实测归类 + 可配置整体替换。
     *
     * <h3>🔴 {@code url} 现在也吃了</h3>
     * 与 {@link #buildTongYiChatModel} 那次（A4）同一个缺陷：{@code AiApiKeyDO.url} 配了也被静默忽略，
     * 「私有化部署的通义网关」这类场景根本没法用，而界面上看着像生效了。
     * 已核实当前库里唯一的通义密钥（id=6）{@code url} 是空串，所以接上默认值对现有行为是 no-op。
     */
    private EmbeddingModel buildTongYiEmbeddingModel(String apiKey, String url, String model) {
        String baseUrl = StrUtil.blankToDefault(url, DashScopeApiConstants.DEFAULT_BASE_URL);
        if (AiUtils.isTongYiMultiModalEmbeddingModel(model)) {
            return new DashScopeMultiModalEmbeddingModel(timedRestClientBuilder(), baseUrl, apiKey,
                    model, MetadataMode.EMBED);
        }
        DashScopeApi dashScopeApi = DashScopeApi.builder().baseUrl(baseUrl).apiKey(apiKey)
                .restClientBuilder(timedRestClientBuilder())
                .webClientBuilder(timedWebClientBuilder())
                .build();
        DashScopeEmbeddingOptions dashScopeEmbeddingOptions = DashScopeEmbeddingOptions.builder().withModel(model).build();
        return new DashScopeEmbeddingModel(dashScopeApi, MetadataMode.EMBED, dashScopeEmbeddingOptions);
    }

    /**
     * 重排序模型（{@code ai_model.type=6}），可参考 SDK 的 {@code DashScopeRerankAutoConfiguration}
     *
     * <p>🔴 它<b>不出向量</b>，所以既不能替代 {@link #buildTongYiEmbeddingModel}，也不能被它替代：
     * 两者是不同端点（{@code TEXT_RERANK_RESTFUL_URL} vs {@code TEXT_EMBEDDING_RESTFUL_URL}）。
     * 把 {@code gte-rerank-v2} 登记成「向量」类型、再拿它去建 EmbeddingModel，得到的正是上面那句
     * 字面上像在怪「你填的 URL 错了」的 400 —— 库里 103/104 两行曾长期是这个形态。
     *
     * <p>🔴 这里配的 {@code model} 只是<b>兜底</b>，调用方在请求级 options 上写的值会压过它。
     * 而 {@code DashScopeRerankOptions} 的无参构造自带 {@code model="gte-rerank"}，
     * 所以「builder 出来但没调 withModel」并不是「不表态」而是「表态成 gte-rerank」——
     * 本账号没开通那个旧模型，厂商回 403 {@code AccessDenied}，看着像权限问题、其实是模型名被改了。
     * 调用方必须在 {@code RerankRequest} 的 options 上<b>再写一遍</b> {@code withModel}。
     * 出线请求体的逐字证据见 {@code DashScopeRerankWireContractTest}。
     */
    private RerankModel buildTongYiRerankModel(String apiKey, String url, String model) {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .baseUrl(StrUtil.blankToDefault(url, DashScopeApiConstants.DEFAULT_BASE_URL))
                .apiKey(apiKey)
                .restClientBuilder(timedRestClientBuilder())
                .webClientBuilder(timedWebClientBuilder())
                .build();
        return new DashScopeRerankModel(dashScopeApi,
                DashScopeRerankOptions.builder().withModel(model).build());
    }

    private OllamaEmbeddingModel buildOllamaEmbeddingModel(String url, String model) {
        OllamaApi ollamaApi = OllamaApi.builder().baseUrl(url)
                .restClientBuilder(timedRestClientBuilder())
                .webClientBuilder(timedWebClientBuilder())
                .build();
        OllamaEmbeddingOptions ollamaOptions = OllamaEmbeddingOptions.builder().model(model).build();
        return OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(ollamaOptions)
                .build();
    }

    /**
     * 可参考 {@link OpenAiEmbeddingAutoConfiguration} 的 openAiEmbeddingModel 方法
     */
    private OpenAiEmbeddingModel buildOpenAiEmbeddingModel(String openAiToken, String url, String model) {
        url = StrUtil.blankToDefault(url, OpenAiApiConstants.DEFAULT_BASE_URL);
        OpenAiApi openAiApi = OpenAiApi.builder().baseUrl(url).apiKey(openAiToken)
                .restClientBuilder(timedRestClientBuilder())
                .webClientBuilder(timedWebClientBuilder())
                .build();
        OpenAiEmbeddingOptions openAiEmbeddingProperties = OpenAiEmbeddingOptions.builder().model(model).build();
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, openAiEmbeddingProperties);
    }

    /**
     * 可参考 {@link AzureOpenAiEmbeddingAutoConfiguration} 的 azureOpenAiEmbeddingModel 方法
     */
    private AzureOpenAiEmbeddingModel buildAzureOpenAiEmbeddingModel(String apiKey, String url, String model) {
        // TODO @芋艿：手头暂时没密钥，使用建议再测试下
        AzureOpenAiEmbeddingAutoConfiguration azureOpenAiAutoConfiguration = new AzureOpenAiEmbeddingAutoConfiguration();
        // 创建 OpenAIClientBuilder 对象
        OpenAIClientBuilder openAIClientBuilder = new OpenAIClientBuilder()
                .endpoint(url).credential(new KeyCredential(apiKey));
        // 获取 AzureOpenAiChatProperties 对象
        AzureOpenAiEmbeddingProperties embeddingProperties = SpringUtil.getBean(AzureOpenAiEmbeddingProperties.class);
        return azureOpenAiAutoConfiguration.azureOpenAiEmbeddingModel(openAIClientBuilder, embeddingProperties,
                getObservationRegistry(), getEmbeddingModelObservationConvention());
    }

    // ========== 各种创建 VectorStore 的方法 ==========

    /**
     * 注意：仅适合本地测试使用，生产建议还是使用 Qdrant、Milvus 等
     *
     * <h3>🔴 文件名按【模型】区分，不按 Java 类名</h3>
     * 原先这里用的是 {@code embeddingModel.getClass().getSimpleName()}，于是所有通义向量模型
     * 共用一个 {@code simple_DashScopeEmbeddingModel.json}。而 {@link #getOrCreateVectorStore} 的
     * 缓存键里带着 EmbeddingModel 实例，两个模型必然拿到两个 VectorStore 实例 ——
     * 两个实例各持一个每分钟落盘的 Timer，互相覆写同一个文件：A 刚写进去的向量被 B 的快照抹掉，
     * 段落表里 vector_id 还在、文件里已经没有了，检索静默返回空、且没有任何报错。
     * 「额度耗尽自动换下一个向量模型」在这之上根本无从谈起，所以先修这里。
     *
     * <h3>旧文件怎么办</h3>
     * 按类名命名的那个文件如果还在，是升级前留下的。它里面的向量属于哪个模型已不可考
     * （这正是原 bug 的症状），但<b>直接丢掉它一定是回归</b>：段落表的 vector_id 还指着它，
     * 一升级就从「能召回」变成「召回全空」。所以让第一个来要的模型把它认领过去，其余模型从空文件开始。
     * 🔴 用 rename 而不是 copy：copy 会让两个模型各持一份同样的混合向量、然后分头写下去，
     * 分歧只会越来越大；rename 保证认领最多发生一次，且认领会打一条 warn 说清是谁认的。
     */
    @SneakyThrows
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private SimpleVectorStore buildSimpleVectorStore(EmbeddingModel embeddingModel, String embeddingModelName) {
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        // 启动加载
        String dir = StrUtil.format("{}/vector_store", FileUtil.getUserHomePath());
        File file = new File(StrUtil.format("{}/simple_{}.json", dir,
                sanitizeForFileName(embeddingModelName, embeddingModel)));
        if (!file.exists()) {
            FileUtil.mkParentDirs(file);
            File legacy = new File(StrUtil.format("{}/simple_{}.json", dir,
                    embeddingModel.getClass().getSimpleName()));
            if (legacy.exists() && legacy.length() > 0) {
                // hutool 的 rename 返回的是改名后的 File 而不是成功与否，所以判据只能是「目标在不在」。
                // 两个模型并发认领时只有一个能成：另一个的源文件已经没了，rename 之后目标依然不存在
                FileUtil.rename(legacy, file.getName(), false);
            }
            if (file.exists()) {
                log.warn("[buildSimpleVectorStore][旧向量文件({}) 已认领给模型({})，改名为({})。"
                                + "旧命名不区分模型，里面的向量可能来自多个模型；若召回结果异常，"
                                + "删掉该文件并对知识库重新索引即可]",
                        legacy.getAbsolutePath(), embeddingModelName, file.getName());
            } else {
                file.createNewFile();
            }
        }
        if (file.length() > 0) {
            vectorStore.load(file);
        }
        // 定时持久化，每分钟一次
        Timer timer = new Timer("SimpleVectorStoreTimer-" + file.getAbsolutePath());
        timer.scheduleAtFixedRate(new TimerTask() {

            @Override
            public void run() {
                vectorStore.save(file);
            }

        }, Duration.ofMinutes(1).toMillis(), Duration.ofMinutes(1).toMillis());
        // 关闭时，进行持久化
        RuntimeUtil.addShutdownHook(() -> vectorStore.save(file));
        return vectorStore;
    }

    /**
     * 把向量模型的标识收敛成可以当文件名的形式
     *
     * <p>现实中的模型标识只有字母、数字、{@code -} 与 {@code .}（如 {@code qwen3.7-text-embedding-flash}），
     * 一个都不用转义；这里替换掉的是 Windows 保留字符与空白，防的是「有人把一整条 URL 填进了模型名」
     * 那种情况 —— 那时 {@code :} 与 {@code /} 会让 {@code new File} 指向完全不相干的路径。
     *
     * <p>{@code .} 必须原样保留（模型名里就有），所以不做「去掉所有点」这类粗暴处理。
     * 路径穿越不成立：{@code /} 与 {@code \} 已被替换掉，没有分隔符，{@code ..} 只是文件名的一部分。
     *
     * @param name           模型标识，可能为空
     * @param embeddingModel 模型标识为空时的兜底来源
     * @return 文件名安全的标识
     */
    private static String sanitizeForFileName(String name, EmbeddingModel embeddingModel) {
        if (StrUtil.isBlank(name)) {
            return embeddingModel.getClass().getSimpleName();
        }
        return name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }

    /**
     * 参考 {@link QdrantVectorStoreAutoConfiguration} 的 vectorStore 方法
     */
    @SneakyThrows
    private QdrantVectorStore buildQdrantVectorStore(EmbeddingModel embeddingModel) {
        QdrantVectorStoreAutoConfiguration configuration = new QdrantVectorStoreAutoConfiguration();
        QdrantVectorStoreProperties properties = SpringUtil.getBean(QdrantVectorStoreProperties.class);
        // 参考 QdrantVectorStoreAutoConfiguration 实现，创建 QdrantClient 对象
        QdrantGrpcClient.Builder grpcClientBuilder = QdrantGrpcClient.newBuilder(
                properties.getHost(), properties.getPort(), properties.isUseTls());
        if (StrUtil.isNotEmpty(properties.getApiKey())) {
            grpcClientBuilder.withApiKey(properties.getApiKey());
        }
        QdrantClient qdrantClient = new QdrantClient(grpcClientBuilder.build());
        // 创建 QdrantVectorStore 对象
        QdrantVectorStore vectorStore = configuration.vectorStore(embeddingModel, properties, qdrantClient,
                getObservationRegistry(), getCustomObservationConvention(), getBatchingStrategy());
        // 初始化索引
        vectorStore.afterPropertiesSet();
        return vectorStore;
    }

    /**
     * 参考 {@link RedisVectorStoreAutoConfiguration} 的 vectorStore 方法
     */
    private RedisVectorStore buildRedisVectorStore(EmbeddingModel embeddingModel,
                                                   Map<String, Class<?>> metadataFields) {
        // 创建 JedisPooled 对象
        RedisProperties redisProperties = SpringUtils.getBean(RedisProperties.class);
        JedisPooled jedisPooled = new JedisPooled(redisProperties.getHost(), redisProperties.getPort(),
                redisProperties.getUsername(), redisProperties.getPassword());
        // 创建 RedisVectorStoreProperties 对象
        RedisVectorStoreProperties properties = SpringUtil.getBean(RedisVectorStoreProperties.class);
        RedisVectorStore redisVectorStore = RedisVectorStore.builder(jedisPooled, embeddingModel)
                .indexName(properties.getIndexName()).prefix(properties.getPrefix())
                .initializeSchema(properties.isInitializeSchema())
                .metadataFields(convertList(metadataFields.entrySet(), entry -> {
                    String fieldName = entry.getKey();
                    Class<?> fieldType = entry.getValue();
                    if (Number.class.isAssignableFrom(fieldType)) {
                        return RedisVectorStore.MetadataField.numeric(fieldName);
                    }
                    if (Boolean.class.isAssignableFrom(fieldType)) {
                        return RedisVectorStore.MetadataField.tag(fieldName);
                    }
                    return RedisVectorStore.MetadataField.text(fieldName);
                }))
                .observationRegistry(getObservationRegistry().getObject())
                .customObservationConvention(getCustomObservationConvention().getObject())
                .batchingStrategy(getBatchingStrategy())
                .build();
        // 初始化索引
        redisVectorStore.afterPropertiesSet();
        return redisVectorStore;
    }

    /**
     * 参考 {@link MilvusVectorStoreAutoConfiguration} 的 vectorStore 方法
     */
    @SneakyThrows
    private MilvusVectorStore buildMilvusVectorStore(EmbeddingModel embeddingModel) {
        MilvusVectorStoreAutoConfiguration configuration = new MilvusVectorStoreAutoConfiguration();
        // 获取配置属性
        MilvusVectorStoreProperties serverProperties = SpringUtil.getBean(MilvusVectorStoreProperties.class);
        MilvusServiceClientProperties clientProperties = SpringUtil.getBean(MilvusServiceClientProperties.class);

        // 创建 MilvusServiceClient 对象
        MilvusServiceClient milvusClient = configuration.milvusClient(serverProperties, clientProperties,
                new MilvusServiceClientConnectionDetails() {

                    @Override
                    public String getHost() {
                        return clientProperties.getHost();
                    }

                    @Override
                    public int getPort() {
                        return clientProperties.getPort();
                    }

                }
        );
        // 创建 MilvusVectorStore 对象
        MilvusVectorStore vectorStore = configuration.vectorStore(milvusClient, embeddingModel, serverProperties,
                getBatchingStrategy(), getObservationRegistry(), getCustomObservationConvention());

        // 初始化索引
        vectorStore.afterPropertiesSet();
        return vectorStore;
    }

    private static ObjectProvider<ObservationRegistry> getObservationRegistry() {
        return new ObjectProvider<>() {

            @Override
            public ObservationRegistry getObject() throws BeansException {
                return SpringUtil.getBean(ObservationRegistry.class);
            }

        };
    }

    private static ObjectProvider<VectorStoreObservationConvention> getCustomObservationConvention() {
        return new ObjectProvider<>() {

            @Override
            public VectorStoreObservationConvention getObject() throws BeansException {
                return new DefaultVectorStoreObservationConvention();
            }

        };
    }

    private static BatchingStrategy getBatchingStrategy() {
        return SpringUtil.getBean(BatchingStrategy.class);
    }

    private static ToolCallingManager getToolCallingManager() {
        return SpringUtil.getBean(ToolCallingManager.class);
    }

    private static ObjectProvider<EmbeddingModelObservationConvention> getEmbeddingModelObservationConvention() {
        return new ObjectProvider<>() {

            @Override
            public EmbeddingModelObservationConvention getObject() throws BeansException {
                return SpringUtil.getBean(EmbeddingModelObservationConvention.class);
            }

        };
    }

}
