package cn.enlev.ainb.module.ai.framework.ai.core.model;

import cn.enlev.ainb.module.ai.enums.model.AiPlatformEnum;
import cn.enlev.ainb.module.ai.framework.ai.core.model.midjourney.api.MidjourneyApi;
import cn.enlev.ainb.module.ai.framework.ai.core.model.suno.api.SunoApi;
import com.alibaba.cloud.ai.model.RerankModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Map;

/**
 * AI Model 模型工厂的接口类
 *
 * @author fansili
 */
public interface AiModelFactory {

    /**
     * 基于指定配置，获得 ChatModel 对象
     *
     * 如果不存在，则进行创建
     *
     * @param platform 平台
     * @param apiKey API KEY
     * @param url API URL
     * @return ChatModel 对象
     */
    ChatModel getOrCreateChatModel(AiPlatformEnum platform, String apiKey, String url);

    /**
     * 基于默认配置，获得 ChatModel 对象
     *
     * 默认配置，指的是在 application.yaml 配置文件中的 spring.ai 相关的配置
     *
     * @param platform 平台
     * @return ChatModel 对象
     */
    ChatModel getDefaultChatModel(AiPlatformEnum platform);

    /**
     * 基于默认配置，获得 ImageModel 对象
     *
     * 默认配置，指的是在 application.yaml 配置文件中的 spring.ai 相关的配置
     *
     * @param platform 平台
     * @return ImageModel 对象
     */
    ImageModel getDefaultImageModel(AiPlatformEnum platform);

    /**
     * 基于指定配置，获得 ImageModel 对象
     *
     * 如果不存在，则进行创建
     *
     * @param platform 平台
     * @param apiKey API KEY
     * @param url API URL
     * @return ImageModel 对象
     */
    ImageModel getOrCreateImageModel(AiPlatformEnum platform, String apiKey, String url);

    /**
     * 基于指定配置，获得 MidjourneyApi 对象
     *
     * 如果不存在，则进行创建
     *
     * @param apiKey API KEY
     * @param url API URL
     * @return MidjourneyApi 对象
     */
    MidjourneyApi getOrCreateMidjourneyApi(String apiKey, String url);

    /**
     * 基于指定配置，获得 SunoApi 对象
     *
     * 如果不存在，则进行创建
     *
     * @param apiKey API KEY
     * @param url API URL
     * @return SunoApi 对象
     */
    SunoApi getOrCreateSunoApi(String apiKey, String url);

    /**
     * 基于指定配置，获得 EmbeddingModel 对象
     *
     * 如果不存在，则进行创建
     *
     * @param platform 平台
     * @param apiKey   API KEY
     * @param url      API URL
     * @param model     模型
     * @return ChatModel 对象
     */
    EmbeddingModel getOrCreateEmbeddingModel(AiPlatformEnum platform, String apiKey, String url, String model);

    /**
     * 基于指定配置，获得 RerankModel（重排序）对象
     *
     * 如果不存在，则进行创建
     *
     * <p>🔴 与 {@link #getOrCreateEmbeddingModel} 是<b>两个端点、两类模型</b>，不能互相替代：
     * 重排序模型（{@code ai_model.type=6}，如 {@code gte-rerank-v2}）不出向量，
     * 拿它的名字去建 EmbeddingModel 只会得到厂商一句
     * {@code 400 InvalidParameter "url error, please check url！"} —— 实测形态，
     * 而这句话字面上像是在说「你填的接口地址错了」，管理员会照着错的方向反复改配置。
     *
     * <p>目前只有通义平台有 SDK 支持（{@code DashScopeRerankModel}），其余平台抛
     * {@code IllegalArgumentException}：本项目的重排序只接了通义。
     * 唯一调用点是 {@code AiModelServiceImpl#testRerankModel}（模型配置页的「测试」按钮）；
     * 知识库检索那条重排序走的是 Spring 自动配置的 bean，不经过本方法
     * （见 {@code AiKnowledgeSegmentServiceImpl#searchDocument} 里那条 🔴 注释）。
     *
     * <p>🔴 <b>调用方的义务</b>：这里传的 {@code model} 只是兜底，
     * 组装 {@code RerankRequest} 时必须在请求级 options 上<b>再写一遍</b> {@code withModel}。
     * {@code DashScopeRerankOptions} 的无参构造自带 {@code model="gte-rerank"}，
     * 而 SDK 的合并口径是「请求级非 null 就压过构造时默认值」，
     * 漏写的后果是厂商回 403 {@code AccessDenied}（本账号没开通那个旧模型）——
     * 看着像权限问题，实际是模型名被静默改掉了。
     * 证据见 {@code DashScopeRerankWireContractTest}。
     *
     * @param platform 平台
     * @param apiKey   API KEY
     * @param url      API URL
     * @param model    模型
     * @return RerankModel 对象
     */
    RerankModel getOrCreateRerankModel(AiPlatformEnum platform, String apiKey, String url, String model);

    /**
     * 基于指定配置，获得 VectorStore 对象
     *
     * 如果不存在，则进行创建
     *
     * <p>🔴 为什么要把模型名单独传进来，而不是从 {@code embeddingModel} 上取：
     * Spring AI 的 {@code EmbeddingModel} 接口<b>没有</b>暴露模型标识的方法
     * （只有 {@code call} / {@code embed} / {@code dimensions}，{@code getDefaultOptions()} 也不存在，
     * 已用 javap 对着 spring-ai-model-1.1.8.jar 核过）。而本地向量库的文件名<b>必须</b>按模型区分 ——
     * 不同模型的向量在不同的语义空间里，维度还可能不一样，混进一个文件之后检索照样返回结果，
     * 只是结果是垃圾，且没有任何报错。
     *
     * @param type                向量存储类型
     * @param embeddingModel      向量模型
     * @param embeddingModelName  向量模型的<b>业务标识</b>（{@code ai_model.model}，如 {@code text-embedding-v4}）
     * @param metadataFields      元数据字段
     * @return VectorStore 对象
     */
    VectorStore getOrCreateVectorStore(Class<? extends VectorStore> type,
                                       EmbeddingModel embeddingModel,
                                       String embeddingModelName,
                                       Map<String, Class<?>> metadataFields);

}
