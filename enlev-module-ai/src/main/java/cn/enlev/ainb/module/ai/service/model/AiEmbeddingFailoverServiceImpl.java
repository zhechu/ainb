package cn.enlev.ainb.module.ai.service.model;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import cn.enlev.ainb.module.ai.dal.mysql.model.AiChatMapper;
import cn.enlev.ainb.module.ai.enums.model.AiModelTypeEnum;
import cn.enlev.ainb.module.ai.enums.model.AiPlatformEnum;
import cn.enlev.ainb.module.ai.framework.ai.core.model.AiModelFactory;
import cn.enlev.ainb.module.ai.util.AiUtils;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.EMBEDDING_MODEL_ALL_UNAVAILABLE;

/**
 * 向量模型故障切换 Service 实现类
 *
 * <p>选链口径、为什么必须真实探测、以及「向量切换是迁移不是兜底」这三件事，
 * 全部记在 {@link AiEmbeddingFailoverService} 的接口注释里，本类不重复。
 * 这里只补两条实现层面的取舍。
 *
 * <h3>🔴 探测结果要缓存正向结论，否则「每篇文档一次探测」</h3>
 * 选链在<b>写入路径</b>上，一次知识库灌库会为每篇文档调一次本方法（实测内置语料 125 篇）。
 * 冷却记忆只记<b>失败</b>，记不住「这个模型刚才探通了」，于是慢路径会为每篇文档重探一遍 ——
 * 一次灌库凭空多出上百次厂商请求，而它们的结论完全相同。{@link #probedUsableAt} 就是补这个正向记忆。
 *
 * <p>它<b>过期或失效都不会导致错误</b>，只会导致多探一次：探通了刷新时间戳；
 * 真调用失败时 {@link #recordFailure} 立刻把它摘掉。所以 TTL 只是「多久重新确认一次额度还在」。
 *
 * <h3>🔴 全员冷却时放弃过滤，与对话侧同一条纪律</h3>
 * 冷却是优化不是熔断。若所有向量模型都在冷却中就直接抛「都不可用」，那条消息里
 * <b>一个厂商原文都没有</b> —— 管理员看不出是「额度没了」还是「模型类型登记错了」，
 * 而这两件事的修法完全相反（前者去充值 / 关免费额度模式，后者改 {@code ai_model.type}）。
 * 所以全员冷却时照旧逐个真探一遍，多花几次注定失败的往返，换一条能指路的消息。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class AiEmbeddingFailoverServiceImpl implements AiEmbeddingFailoverService {

    /**
     * 探测用的文本
     *
     * <p>刻意短且固定：探测只为拿到「厂商肯不肯为这个模型算向量」这个事实，
     * 内容长只会白烧 token（向量按输入 token 计费，而额度耗尽正是本类要处置的场景之一）。
     */
    private static final String PROBE_TEXT = "启凌智搭向量模型连通性探测";

    /**
     * 一次探测成功的免重探存活期（毫秒）
     *
     * <p>取与额度冷却同档的 30 分钟：够长，一次灌库（实测单篇数秒、全量约 5 分钟）
     * 全程只会真探一次；够短，厂商把额度收回去之后最多半小时就会重新探到。
     * 中途真调用失败由 {@link #recordFailure} 立刻摘除，不必等 TTL。
     */
    private static final long PROBE_TTL_MILLIS = 30 * 60 * 1000L;

    /**
     * 汇总消息里单个模型那段厂商原文的最大长度
     *
     * <p>9 个模型逐个带全文会把一句错误消息撑到几 KB，而它最终要写进
     * {@code agent_usage_log.error_msg} 之类的列。留 200 字足够放下
     * {@code 400 - {"code":"InvalidParameter","message":"url error, please check url！"}}
     * 这种真实原文，多出来的部分对判断病因没有增量。
     */
    private static final int ATTEMPT_ERROR_MAX_LENGTH = 200;

    /**
     * modelId -> 最近一次<b>探测成功</b>的时刻
     *
     * <p>内存态、按 JVM，与 {@link AiModelFailureMemory} 同一档：多实例各自记忆，
     * 跨实例最多多探一次，不会更坏。容量受模型总数限制，无需淘汰策略。
     */
    private final Map<Long, Long> probedUsableAt = new ConcurrentHashMap<>();

    @Resource
    private AiChatMapper modelMapper;

    @Resource
    private AiApiKeyService apiKeyService;

    @Resource
    private AiModelFactory modelFactory;

    /**
     * 只为拿那份<b>共用</b>的失败冷却记忆，不用它的候选链（理由见接口注释）
     */
    @Resource
    private AiModelFailoverService failoverService;

    @Override
    public AiModelDO selectUsableEmbeddingModel(Long preferredModelId) {
        AiModelFailureMemory memory = failoverService.getFailureMemory();
        Integer embeddingType = AiModelTypeEnum.EMBEDDING.getType();
        Map<Long, AiApiKeyDO> keyMap = buildKeyMap();

        // 1. 快路径：知识库钉住的那个「本进程内已经探通过、且没被记坏」才原样返回。
        //    🔴 registerFingerprint 必须在 isBelievedUsable 之前，理由见 AiModelFailureMemory#registerFingerprint
        //    （顺序反了会让冷却中的模型永远刷不到新指纹 ⟹ 管理员改好配置也解不了锁）
        if (preferredModelId != null) {
            AiModelDO preferred = modelMapper.selectById(preferredModelId);
            AiApiKeyDO apiKey = preferred == null ? null : keyMap.get(preferred.getKeyId());
            if (preferred == null || !Objects.equals(preferred.getType(), embeddingType)
                    || !CommonStatusEnum.isEnable(preferred.getStatus()) || !isKeyEnabled(apiKey)) {
                log.warn("[selectUsableEmbeddingModel][首选向量模型({}) 不可用（不存在/类型不符/禁用/密钥禁用），改为逐个探测]",
                        preferredModelId);
            } else {
                memory.registerFingerprint(preferred, apiKey);
                if (!memory.isBelievedUsable(preferred.getId())) {
                    log.warn("[selectUsableEmbeddingModel][首选向量模型({}/{}) 处于失败冷却中，改为逐个探测]",
                            preferred.getId(), preferred.getModel());
                } else if (isRecentlyProbedUsable(preferred.getId())) {
                    return preferred;
                } else {
                    // 🔴 「失败记忆里没有它」≠「它打得通」，只代表本进程还没试过它（刚启动、或冷却已过）。
                    //    这种情况下直接返回，第一篇文档就会在 writeVectorStore 里撞上厂商 403，
                    //    而调用方 createKnowledgeSegmentBySplitContentAsync 是 @Async —— 异常被吞掉，
                    //    接口照样返回 200，管理员看到的是「文档建好了、向量化 0%」且没有任何提示。
                    //    只有一篇文档的知识库更是永远回不来（迁移要等下一次写入才触发）。
                    //    所以这里补一次探测：代价是每个知识库每进程最多一次厂商请求，
                    //    探通即写进 probedUsableAt，后面 30 分钟内都走上面那条 return。
                    Probe probe = probe(preferred, apiKey);
                    if (probe.error() == null) {
                        recordSuccess(preferred);
                        return preferred;
                    }
                    recordFailure(preferred, probe.error());
                    log.warn("[selectUsableEmbeddingModel][首选向量模型({}/{}) 探测未通过，改为逐个探测]",
                            preferred.getId(), preferred.getModel());
                }
            }
        }

        // 2. 慢路径：按 sort 升序逐个探测，返回第一个真出向量的
        return probeFirstUsable(embeddingType, keyMap, memory);
    }

    /**
     * 逐个探测启用中的向量模型，返回第一个真能出向量的
     *
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException 一个都探不通
     */
    private AiModelDO probeFirstUsable(Integer type, Map<Long, AiApiKeyDO> keyMap, AiModelFailureMemory memory) {
        List<AiModelDO> models = new ArrayList<>(modelMapper.selectListByStatusAndType(
                CommonStatusEnum.ENABLE.getStatus(), type, null));
        int enabledCount = models.size();
        // 「模型启用但密钥禁用」必须在选择期剔除：否则运行时才在 validateApiKey 炸掉。
        // 与对话侧 buildCandidateChain 第 2 步同一判据（那处注释记着这个隐患曾有三处同病）
        models.removeIf(model -> !isKeyEnabled(keyMap.get(model.getKeyId())));
        if (models.isEmpty()) {
            throw exception(EMBEDDING_MODEL_ALL_UNAVAILABLE, enabledCount, 0,
                    enabledCount == 0 ? "一个启用中的向量模型都没有，请去【AI 大模型】页启用"
                            : "启用中的向量模型挂的密钥全被禁用了，请去【AI 密钥】页启用");
        }

        // 🔴 指纹逐个登记，同样必须在冷却判断之前；纯内存操作，一次 DB 都不多打
        for (AiModelDO model : models) {
            memory.registerFingerprint(model, keyMap.get(model.getKeyId()));
        }

        // 第一轮只探「记忆里没被记坏」的；全员冷却则放弃过滤重探，
        // 为的是让汇总消息带上厂商原文而不是只剩一句「都不可用」（见类注释）
        List<AiModelDO> believedUsable = models.stream()
                .filter(model -> memory.isBelievedUsable(model.getId()))
                .toList();
        List<AiModelDO> candidates;
        List<AiModelDO> skippedByMemory;
        if (believedUsable.isEmpty()) {
            log.warn("[probeFirstUsable][全部 {} 个启用中的向量模型都处于失败冷却中，本次放弃过滤重新探测；"
                    + "若这条持续出现，说明配置尚未修好而不是厂商在抖]", models.size());
            candidates = models;
            skippedByMemory = List.of();
        } else {
            candidates = believedUsable;
            skippedByMemory = models.stream()
                    .filter(model -> !memory.isBelievedUsable(model.getId()))
                    .toList();
        }

        List<String> attempts = new ArrayList<>(models.size());
        for (AiModelDO model : candidates) {
            // 近期探通过的直接复用结论：慢路径在写入侧每篇文档都会走到，
            // 没有这一步，一次灌库会凭空多出上百次结论完全相同的厂商请求
            if (isRecentlyProbedUsable(model.getId())) {
                return model;
            }
            Probe probe = probe(model, keyMap.get(model.getKeyId()));
            if (probe.error() == null) {
                recordSuccess(model);
                log.info("[probeFirstUsable][向量模型({}/{}) 探测通过，本次索引改用它]",
                        model.getId(), model.getModel());
                return model;
            }
            recordFailure(model, probe.error());
            attempts.add(StrUtil.format("{} → {}", describe(model),
                    StrUtil.maxLength(AiUtils.describeError(probe.error()), ATTEMPT_ERROR_MAX_LENGTH)));
        }
        // 被冷却跳过的也要列出来：不列的话管理员会以为「只有这几个模型」，
        // 而真正该处置的那个（额度耗尽的）恰好可能就在跳过的那批里
        for (AiModelDO model : skippedByMemory) {
            attempts.add(StrUtil.format("{} → 冷却中，本次未再探测", describe(model)));
        }
        throw exception(EMBEDDING_MODEL_ALL_UNAVAILABLE, enabledCount, models.size(),
                String.join("；", attempts));
    }

    /**
     * 真发一次最小向量请求
     *
     * <p>🔴 形态照抄生产（{@code AiModelServiceImpl#testEmbeddingModel} 与本仓的入库/检索路径），
     * 不自己发明：{@code SimpleVectorStore} 入库走 {@code embed(Document)}、检索走 {@code embed(String)}，
     * 两者最终都落到 {@code call(new EmbeddingRequest(texts, EmbeddingOptions.builder().build()))}
     * （已用 javap 对 spring-ai 1.1.8 的 {@code EmbeddingModel} 默认方法逐条核对）。
     *
     * <p>🔴 不另加超时线程池：{@code buildTongYiEmbeddingModel} / {@code buildOllamaEmbeddingModel} /
     * {@code buildOpenAiEmbeddingModel} 都用 {@code timedRestClientBuilder()} + {@code timedWebClientBuilder()}
     * 建客户端，HTTP 层已经带超时。再套一层等于给同一次调用配两个互相不知道的预算。
     *
     * @return 探测结果；{@code error == null} 即通过
     */
    private Probe probe(AiModelDO model, AiApiKeyDO apiKey) {
        try {
            // 密钥空/占位值在这里就拦掉，别等 SDK 去建客户端：各平台反应并不一致
            // （有的 Assert 抛、有的照建不误、要到真发请求才吃 401）。照建不误的那种最坏 ——
            // 401 会被 AiModelFailureReason 当成「厂商抖动」，把「没填密钥」说成「厂商挂了」
            AiUtils.validateApiKey(apiKey.getApiKey());
            EmbeddingModel embeddingModel = modelFactory.getOrCreateEmbeddingModel(
                    AiPlatformEnum.validatePlatform(apiKey.getPlatform()), apiKey.getApiKey(), apiKey.getUrl(),
                    model.getModel());
            embeddingModel.call(new EmbeddingRequest(List.of(PROBE_TEXT), EmbeddingOptions.builder().build()));
            return new Probe(null);
        } catch (Exception ex) {
            return new Probe(ex);
        }
    }

    @Override
    public void recordSuccess(AiModelDO model) {
        if (model == null) {
            return;
        }
        probedUsableAt.put(model.getId(), System.currentTimeMillis());
        failoverService.getFailureMemory().recordSuccess(model.getId());
    }

    @Override
    public void recordFailure(AiModelDO model, Throwable error) {
        if (model == null) {
            return;
        }
        // 🔴 摘掉正向记忆，否则一次真实的额度耗尽会被「30 分钟前探通过」继续盖住
        probedUsableAt.remove(model.getId());
        AiModelFailureReason reason = AiModelFailureReason.classify(error);
        failoverService.getFailureMemory().recordFailure(model.getId(), reason);
        log.warn("[recordFailure][向量模型({}/{}) 调用失败，判为 {}，冷却 {} 分钟：{}]",
                model.getId(), model.getModel(), reason, cooldownMinutes(reason),
                error == null ? null : AiUtils.describeError(error));
    }

    private boolean isRecentlyProbedUsable(Long modelId) {
        Long at = probedUsableAt.get(modelId);
        return at != null && System.currentTimeMillis() - at < PROBE_TTL_MILLIS;
    }

    private Map<Long, AiApiKeyDO> buildKeyMap() {
        // 一次取全建 Map，避免逐个模型 selectById 造成 N+1
        return apiKeyService.getApiKeyList().stream()
                .collect(Collectors.toMap(AiApiKeyDO::getId, key -> key, (a, b) -> a));
    }

    private boolean isKeyEnabled(AiApiKeyDO apiKey) {
        return apiKey != null && CommonStatusEnum.isEnable(apiKey.getStatus());
    }

    /**
     * 错误消息里对模型的称呼：{@code 编号/厂商标识}
     *
     * <p>与对话侧 {@code AiModelFailoverServiceImpl#describe} 同口径、同理由：
     * 显示名可以被管理员改成任何东西，而「两轮跑的是不是同一个模型」这个判断
     * 必须建立在不会被人改掉的标识上。
     */
    private String describe(AiModelDO model) {
        return model.getId() + "/" + model.getModel();
    }

    private static long cooldownMinutes(AiModelFailureReason reason) {
        return switch (reason) {
            case QUOTA -> AiModelFailureMemory.QUOTA_COOLDOWN_MILLIS / 60_000L;
            case CONFIG -> AiModelFailureMemory.CONFIG_COOLDOWN_MILLIS / 60_000L;
            case TRANSIENT -> AiModelFailureMemory.COOLDOWN_MILLIS / 60_000L;
        };
    }

    /**
     * 一次探测的结果：{@code error == null} 即通过
     *
     * <p>用 record 而不是抛异常穿过循环，是因为「探不通」在慢路径上是<b>预期内</b>的
     * 正常分支（实测 9 个启用中的向量模型里有 6 个必然探不通），不是需要中断的错误。
     */
    private record Probe(Throwable error) {
    }

}
