package cn.enlev.ainb.module.ai.api.model;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import cn.enlev.ainb.module.ai.enums.model.AiModelTypeEnum;
import cn.enlev.ainb.module.ai.enums.model.AiPlatformEnum;
import cn.enlev.ainb.module.ai.framework.ai.config.EnlevAiProperties;
import cn.enlev.ainb.module.ai.service.model.AiApiKeyService;
import cn.enlev.ainb.module.ai.service.model.AiEmbeddingFailoverService;
import cn.enlev.ainb.module.ai.service.model.AiModelFailoverService;
import cn.enlev.ainb.module.ai.service.model.AiModelService;
import cn.enlev.ainb.module.ai.service.model.bo.AiModelCandidate;
import cn.enlev.ainb.module.ai.service.model.bo.AiModelInvocationBO;
import cn.enlev.ainb.module.ai.util.AiUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_ALL_UNAVAILABLE;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_TASK_PINNED_UNAVAILABLE;

/**
 * AI 模型调用 API 实现类
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class AiModelApiImpl implements AiModelApi {

    /**
     * 不传 type 时要遍历的模型类型
     *
     * 取自 {@code AiModelTypeEnum}：1=聊天、2=图像、3=音乐、4=视频、5=向量、6=重排。
     * agent 只关心聊天与向量，其余就算开着也用不上，不必查出来干扰调用方。
     */
    private static final List<Integer> QUERYABLE_MODEL_TYPES = List.of(1, 5);

    /**
     * ⚠️ {@code @Lazy} 的理由同 {@code AiRagApiImpl}：
     * ai 模块内部存在循环依赖，新 bean 普通注入会在循环未完成包装时取走 raw 实例，
     * 导致<b>整个应用起不来</b>。
     */
    @Resource
    @Lazy
    private AiModelService modelService;
    @Resource
    @Lazy
    private AiApiKeyService apiKeyService;
    @Resource
    @Lazy
    private AiModelFailoverService failoverService;
    @Resource
    @Lazy
    private AiEmbeddingFailoverService embeddingFailoverService;

    /** 单候选流式超时（连接挂起也能触发切换），复用 B18 的兜底配置 */
    @Resource
    private EnlevAiProperties aiProperties;

    @Override
    public AiChatRespDTO chat(AiChatReqDTO reqDTO) {
        List<AiModelCandidate> candidates = buildChatCandidates(reqDTO.getModelId(), reqDTO.getTaskKey());
        AiModelInvocationBO<ChatResponse> invocation = failoverService.callWithFallback(candidates,
                candidate -> candidate.getChatModel().call(buildPrompt(reqDTO, candidate.getModel())));
        // 🔴 钉的是「实际成功的那个」，不是链首：链首可能因为瞬时失败被切走了，
        // 钉错会让任务内后续调用又去撞那个刚失败的模型
        failoverService.pinTaskModel(reqDTO.getTaskKey(), reqDTO.getModelId(), invocation.getModel().getId());
        return buildResp(invocation.getPayload(), invocation.getModel());
    }

    @Override
    public Flux<AiChatRespDTO> chatStream(AiChatReqDTO reqDTO) {
        List<AiModelCandidate> candidates = buildChatCandidates(reqDTO.getModelId(), reqDTO.getTaskKey());
        return failoverService.streamWithFallback(candidates,
                        // timeout 下推到单候选：厂商连接挂起（连接在、不发数据）也能触发切换；
                        // 调用方（如 agent）外层的总兜底 timeout 保持不动
                        candidate -> candidate.getChatModel().stream(buildPrompt(reqDTO, candidate.getModel()))
                                .timeout(aiProperties.getStream().getTimeout()))
                // 首包到达才钉：流式在首包前仍允许切换，钉早了等于把「还没定下来」的模型当成定论。
                // pinTaskModel 内部是 putIfAbsent，后续每个 chunk 重复调用无副作用
                .doOnNext(invocation -> failoverService.pinTaskModel(
                        reqDTO.getTaskKey(), reqDTO.getModelId(), invocation.getModel().getId()))
                .map(invocation -> buildResp(invocation.getPayload(), invocation.getModel()));
    }

    /**
     * 构建聊天模型的候选链
     *
     * 语义：不传 modelId 时默认「启用 + 密钥启用 + sort 最前」；传了则作为链首，
     * 不可用（禁用/密钥禁用/瞬时调用失败）时自动切换到下一个候选 —— 切换细节在
     * {@link AiModelFailoverService}，调用方无需关心。
     *
     * <p>传了 {@code taskKey} 则多一条口径：<b>任务内只用同一个模型</b>，额度耗尽算这次调用失败，
     * 换模型只发生在任务边界（裁定 R-01 / 缺陷 D4-28）。
     */
    private List<AiModelCandidate> buildChatCandidates(Long modelId, String taskKey) {
        List<AiModelCandidate> candidates = failoverService.buildCandidateChain(
                modelId, AiModelTypeEnum.CHAT.getType(), taskKey);
        if (candidates.isEmpty()) {
            // 两种成因必须分开报，否则「额度没了」会被误报成「环境没配好」，
            // 管理员照着错的方向排查（红线 §10.2：不替 error 编病因）
            // ⚠️ 这份名单<b>不含</b>「密钥没填 / ${} 占位符解析不出来」：那类配置错误
            // 在 buildCandidateChain 里就带着码抛出来了，压根走不到这个分支 ——
            // 走到这里说明模型是「真没有」，而不是「有但配错了」
            Long pinnedModelId = failoverService.getPinnedModelId(taskKey, modelId);
            if (pinnedModelId != null) {
                throw exception(MODEL_TASK_PINNED_UNAVAILABLE, pinnedModelId);
            }
            throw exception(MODEL_ALL_UNAVAILABLE,
                    "没有可用的聊天模型，请在「AI 模型」里启用一个，并确认它关联的密钥也是启用状态");
        }
        return candidates;
    }

    @Override
    public List<AiModelSimpleRespDTO> getEnabledModelList(Integer type) {
        // 🔴 两处都踩过坑，写清楚免得再犯：
        //
        // ① CommonStatusEnum 是 0=开启、1=关闭，不是直觉上的 1=启用。
        //    第一版按直觉写 1，筛出来的恰好是「已关闭」的模型 —— 当时实测得到「0 个可用模型」，
        //    还以为是环境没配密钥，其实是把结论看反了。
        //
        // ② 上游 Mapper 的 type 用的是 eq 而非 eqIfPresent（AiChatMapper:42），
        //    传 null 会变成 type IS NULL 查出空集，不能指望它当「不过滤」处理。
        //    所以 type 为空时逐个类型查后合并。不改上游 Mapper，改动只留在 api 层。
        List<AiModelDO> models = new ArrayList<>();
        for (Integer each : type != null ? List.of(type) : QUERYABLE_MODEL_TYPES) {
            models.addAll(modelService.getModelListByStatusAndType(
                    CommonStatusEnum.ENABLE.getStatus(), each, null));
        }
        // 密钥也必须是启用的 —— 模型开着但密钥禁用时调用会在运行时才失败，
        // 提前过滤能省一轮无谓的重试
        return models.stream()
                .filter(m -> {
                    AiApiKeyDO apiKey = m.getKeyId() == null ? null : apiKeyService.getApiKey(m.getKeyId());
                    return apiKey != null && CommonStatusEnum.isEnable(apiKey.getStatus());
                })
                .map(m -> {
                    AiModelSimpleRespDTO dto = new AiModelSimpleRespDTO();
                    dto.setId(m.getId());
                    dto.setName(m.getName());
                    dto.setModel(m.getModel());
                    dto.setPlatform(m.getPlatform());
                    dto.setType(m.getType());
                    return dto;
                }).toList();
    }

    @Override
    public Long selectUsableEmbeddingModelId(Long preferredModelId) {
        return embeddingFailoverService.selectUsableEmbeddingModel(preferredModelId).getId();
    }

    /**
     * 组装 Prompt
     *
     * 刻意不复用 {@code AiChatMessageServiceImpl.buildPrompt} —— 那个方法耦合了会话、
     * 知识库召回与联网搜索，而这里要的是纯粹的模型调用。
     * 上下文组装是 agent 自己的职责（它要按 token 预算截断、回灌工具结果）。
     */
    private Prompt buildPrompt(AiChatReqDTO reqDTO, AiModelDO model) {
        List<Message> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(reqDTO.getSystemPrompt())) {
            messages.add(new SystemMessage(reqDTO.getSystemPrompt()));
        }
        for (AiChatReqDTO.Message message : CollUtil.emptyIfNull(reqDTO.getMessages())) {
            messages.add(AiUtils.buildMessage(message.getRole(), message.getContent()));
        }
        // 入参没给就用模型自身配置，与 ai 模块聊天路径的口径一致
        Double temperature = reqDTO.getTemperature() != null
                ? reqDTO.getTemperature() : model.getTemperature();
        Integer maxTokens = reqDTO.getMaxTokens() != null
                ? reqDTO.getMaxTokens() : model.getMaxTokens();
        return new Prompt(messages, AiUtils.buildChatOptions(
                AiPlatformEnum.validatePlatform(model.getPlatform()),
                model.getModel(), temperature, maxTokens, null, null, reqDTO.getEnableThinking()));
    }

    private AiChatRespDTO buildResp(ChatResponse response, AiModelDO model) {
        AiChatRespDTO respDTO = new AiChatRespDTO();
        respDTO.setContent(AiUtils.getChatResponseContent(response));
        respDTO.setReasoningContent(AiUtils.getChatResponseReasoningContent(response));
        respDTO.setModelId(model.getId());
        respDTO.setModel(model.getModel());
        respDTO.setPlatform(model.getPlatform());
        respDTO.setUsage(extractUsage(response));
        return respDTO;
    }

    /**
     * 抽取 token 用量
     *
     * 🔴 现状是全模块<b>零统计</b>：{@code ChatResponse} 的 usage 元数据一直被直接丢弃。
     * 在 api 层就取出来，后续计量才有数据源，不必等到做用量看板时再翻一遍所有调用点。
     *
     * <p>取不到返回 null（流式的中间块通常没有，部分厂商末块也不给），
     * 调用方据此决定是否退化成 tokenizer 估算。
     */
    private AiChatRespDTO.Usage extractUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null
                || response.getMetadata().getUsage() == null) {
            return null;
        }
        org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
        if (usage.getTotalTokens() == null || usage.getTotalTokens() == 0) {
            return null;
        }
        return AiChatRespDTO.Usage.of(usage.getPromptTokens(),
                usage.getCompletionTokens(), usage.getTotalTokens());
    }

}
