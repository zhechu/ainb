package cn.enlev.ainb.module.ai.service.model;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import cn.enlev.ainb.module.ai.dal.mysql.model.AiChatMapper;
import cn.enlev.ainb.module.ai.enums.model.AiPlatformEnum;
import cn.enlev.ainb.module.ai.framework.ai.core.model.AiModelFactory;
import cn.enlev.ainb.module.ai.service.model.bo.AiModelCandidate;
import cn.enlev.ainb.module.ai.service.model.bo.AiModelInvocationBO;
import cn.enlev.ainb.module.ai.util.AiUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_ALL_UNAVAILABLE;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_QUOTA_EXHAUSTED;

/**
 * AI 模型故障切换 Service 实现类
 *
 * <h3>🔴 三类失败的处置刻意不同（缺陷 D4-28 / 红线 §10.9 第 30 条）</h3>
 * 本类原先对任何异常一律切换下一个候选，注释写的是「不做异常分类（4xx 不切换之类）：
 * 各厂商异常包装不一致，首版一律切换」。代价实测到了：厂商免费额度耗尽后，
 * 一轮 e2e 里 CHAT 从 model 76 漂到 78 再漂到 80（{@code 76×17 → 78×2 → 80×19}），
 * 而库里只剩「最终成功那个模型」—— 事后无法看出这一轮跑的根本不是同一个东西，
 * 于是所有「跑两轮对比数字」的验收（A4 / A6 / A7 / A8）跑多少次都不算数。
 *
 * <p>现在的口径来自裁定 R-01：<b>额度耗尽 = 该次调用判失败，绝不静默换模型救回来；
 * 换下一个模型只准发生在任务边界。</b> 于是三档处置：
 * <ul>
 *   <li>{@link AiModelFailureReason#QUOTA} → 立刻抛 {@code MODEL_QUOTA_EXHAUSTED}，<b>不切换</b>，
 *       并把该模型记入一段长冷却，让<b>下一个任务</b>自然落到下一个候选；</li>
 *   <li>{@link AiModelFailureReason#CONFIG} → <b>切换</b>（配置错误是模型级事实，换一个就对了），
 *       但冷却与额度同档 30 分钟 —— 同一份配置重试必然同样失败，
 *       60 秒后重演一遍 2026-10-10 那场 23 秒的风暴是纯浪费；</li>
 *   <li>{@link AiModelFailureReason#TRANSIENT} → 维持原有的 60 秒冷却 + 切换，一个字没改。</li>
 * </ul>
 *
 * <p>🔴 三段冷却记忆都<b>自带配置指纹</b>（{@code update_time}），指纹不匹配即作废：
 * 管理员改好配置、或把模型禁用再启用之后，冷却<b>立刻</b>解除，不必干等 30 分钟。
 * 详见 {@link AiModelFailureMemory}。
 *
 * <p>失败的可查性刻意<b>不靠新增列</b>：{@code MODEL_QUOTA_EXHAUSTED} 的消息里带着
 * 「模型编号/厂商标识 + 厂商原文」，而 agent 侧三条主链路（INTENT / PLAN_DSL / CHAT）
 * 本来就把 {@code ex.getMessage()} 写进 {@code agent_usage_log.error_msg}
 * ⟹ 「哪个模型在哪一刻额度耗尽」直接落库可查。
 * 之所以不加列，是因为本模块已经有一根死列的前车之鉴（{@code failed_at_node} 存在但全仓无人读写），
 * 加一根没人读的列只是把同一个坑再挖一次。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class AiModelFailoverServiceImpl implements AiModelFailoverService {

    /**
     * 失败冷却记忆
     *
     * <p>🔴 与向量侧（{@code AiEmbeddingFailoverServiceImpl}）<b>共用同一份</b>，通过
     * {@link #getFailureMemory()} 暴露。搬进独立类而不是各留一份的理由见 {@link AiModelFailureMemory}。
     *
     * <p>刻意<b>直接 new</b> 而不是做成 Spring Bean：本类的单元测试用 {@code @InjectMocks} 装配，
     * 新增一个需要注入的协作者就得同步改测试的 {@code @Spy}/{@code @Mock} 声明，
     * 而漏改的表现是 21 个用例集体 NPE —— 与这次重构想验证的行为毫无关系。
     * 它没有任何依赖、也不需要被替换，一个纯内存对象没有做 Bean 的理由。
     */
    private final AiModelFailureMemory failureMemory = new AiModelFailureMemory();

    /**
     * 任务级粘滞的存活期（毫秒）
     *
     * <p>每次读到都会续期，所以「活跃中的任务」永不掉钉；掉钉的只有<b>已经被放弃</b>的任务
     * —— 那正是要回收的。不设 TTL 的话这张表会随任务数单调增长，而任务标识（执行编号、
     * 规划轮次）是<b>不会重复使用</b>的，等不到覆盖写来腾位置。
     */
    private static final long TASK_PIN_TTL_MILLIS = 30 * 60 * 1000L;

    /**
     * 超过这个条数才扫一遍过期项
     *
     * <p>逐次调用都全表扫会把一个 Map 操作变成 O(n)；而读取路径已经惰性判过期，
     * 过期的钉子<b>逻辑上已经失效</b>，留在这儿只占内存不产生错误行为。
     * 4096 条 × 每条约百字节 ≈ 数百 KB，是「不扫也不会出事」的量级。
     */
    private static final int TASK_PIN_PURGE_THRESHOLD = 4096;

    /**
     * taskKey -> 该任务钉住的模型
     *
     * <p>🔴 与失败冷却记忆一样是<b>内存态、按 JVM</b>：多实例部署下各自记忆。
     * 这里可以接受，因为粘滞的作用是「同一轮里别漂」，而一轮 e2e / 一次执行 / 一次规划
     * 都落在同一个实例上；跨实例漂了也只是回到「无粘滞」的旧行为，不会更坏。
     */
    private final Map<String, TaskPin> taskPins = new ConcurrentHashMap<>();

    @Resource
    private AiChatMapper modelMapper;

    @Resource
    private AiApiKeyService apiKeyService;

    @Resource
    private AiModelFactory modelFactory;

    @Override
    public List<AiModelCandidate> buildCandidateChain(Long requestedModelId, Integer type, String taskKey) {
        // 0. 任务级粘滞：这个任务已经钉住过模型，就只用它 —— 连默认链都不看。
        //    🔴 钉住的模型不可用时返回【空链】，绝不回退到默认链：
        //    回退就是在任务中途换模型，正是裁定 R-01 要禁掉的那个行为
        //    🔴 钉的键含 requestedModelId：同一任务里显式配了不同模型的节点各自独立钉，
        //    否则后一个节点的配置会被前一个节点钉住的模型静默顶掉（理由见接口类注释）
        Long pinnedModelId = takePinnedModelId(taskKey, requestedModelId);
        if (pinnedModelId != null) {
            return buildPinnedChain(pinnedModelId, type, taskKey);
        }

        // 1. 启用的模型，selectListByStatusAndType 已按 sort 升序
        List<AiModelDO> models = new ArrayList<>(modelMapper.selectListByStatusAndType(
                CommonStatusEnum.ENABLE.getStatus(), type, null));
        // 2. 密钥一次取全建 Map（避免 N+1）；「模型启用但密钥禁用」必须在选择期剔除——
        //    否则运行时才在 validateApiKey 炸掉。这隐患原先有三处同病，b0bdd0933b 收了另两处：
        //    resolveChatModel 换成 AiModelApiImpl.buildChatCandidates（即改走本方法），
        //    simple-list 也加了同样的过滤
        Map<Long, AiApiKeyDO> keyMap = apiKeyService.getApiKeyList().stream()
                .collect(Collectors.toMap(AiApiKeyDO::getId, key -> key, (a, b) -> a));
        models.removeIf(model -> !isKeyEnabled(keyMap.get(model.getKeyId())));

        // 2.5 刷新配置指纹：必须在第 4 步的冷却过滤【之前】，理由见 AiModelFailureMemory#registerFingerprint
        //     （放在第 5 步会让冷却中的模型永远刷不到新指纹 ⟹ 改好配置也解不了锁）。
        //     🔴 纯内存操作：model 与 apiKey 都已在手，一次 DB 都不多打。
        //     ⚠️ 已删除的模型会在这里留下死键，但它们再也进不了任何候选链、因此完全惰性；
        //        刻意【不】按「本次链里的模型」去清理 —— models 是按 type 过滤过的，
        //        那样会把别的类型（如 EMBEDDING）的冷却记忆一起抹掉。
        for (AiModelDO model : models) {
            failureMemory.registerFingerprint(model, keyMap.get(model.getKeyId()));
        }

        // 3. 显式指定的模型置顶；不可用则丢弃并静默降级到默认链。
        //    刻意不抛异常：会话快照的模型被管理员禁用后，用户消息不该直接报错，
        //    「切换到能用的模型」正是需求语义（切换会记 warn 日志，见 callWithFallback）
        if (requestedModelId != null) {
            AiModelDO requested = modelMapper.selectById(requestedModelId);
            if (requested != null && CommonStatusEnum.isEnable(requested.getStatus())
                    && Objects.equals(requested.getType(), type)
                    && isKeyEnabled(keyMap.get(requested.getKeyId()))) {
                models.removeIf(model -> model.getId().equals(requestedModelId));
                models.add(0, requested);
            } else {
                log.warn("[buildCandidateChain][请求模型({}) 不可用（不存在/禁用/类型不符/密钥禁用），降级使用默认候选链]",
                        requestedModelId);
            }
        }

        // 4. 冷却过滤：跳过近期失败的模型；若全员冷却则放弃过滤用原链——
        //    冷却是优化不是熔断，不能造成「所有模型都冷却中 → 服务不可用」的自我打击。
        //    🔴 额度耗尽走同一套「全员命中就放弃过滤」的兜底：那样调用会真的发出去、
        //    拿到 403、再由 callWithFallback 判 MODEL_QUOTA_EXHAUSTED 失败。
        //    多花一次注定失败的往返，换来的是【错误消息说清了真实原因】——
        //    在这里就返回空链的话，调用方只能报「没有可用的聊天模型，请去启用一个」，
        //    那是把「额度没了」误报成「环境没配好」，管理员会照着错的方向排查
        List<AiModelDO> notCooling = models.stream()
                .filter(model -> failureMemory.isBelievedUsable(model.getId()))
                .toList();
        if (!notCooling.isEmpty()) {
            models = new ArrayList<>(notCooling);
        } else if (!models.isEmpty()) {
            // 🔴 兜底行为一字不改（返回空链会让调用方报「请去启用一个模型」，把真实病因吞掉），
            //    但它有一个可预见的后果：所有模型都坏时，<b>每个请求</b>都要重演一遍完整风暴
            //    （2026-10-10 实测前 10 个候选接连失败烧掉 23 秒）。
            //    把一场静默的 23 秒变成一条可诊断的日志，是这里唯一能做且必须做的改进
            log.warn("[buildCandidateChain][全部 {} 个候选都处于失败冷却中（含配置类），本次放弃过滤重新探测；"
                    + "若这条持续出现，说明配置尚未修好而不是厂商在抖]", models.size());
        }

        // 5. 急切预取 ChatModel：只构建 HTTP 客户端对象、不发网络请求，LRU 缓存命中时近乎零成本。
        //    🔴 必须放在调用线程（租户上下文完整）；切换路径（IO 线程）禁止触 DB / 工厂，
        //    否则重蹈 AiChatMessageServiceImpl 里 IO 线程租户丢失的坑
        List<AiModelCandidate> candidates = new ArrayList<>(models.size());
        // 最后一次「配置类」失败（带码的 ServiceException：密钥没填、${} 占位符解析不出来）。
        // 🔴 不能像瞬时失败那样一扔了之 —— 链为空时它是<b>唯一</b>的真实病因。
        //    扔掉的后果实测过：三个调用方（AiModelApiImpl / AiChatMessageServiceImpl /
        //    AiChatConversationServiceImpl）拿到空链后只能报「没有可用的聊天模型，请去启用一个」，
        //    而模型<b>本来就启用着</b>，管理员于是去反复启停它（红线 §10.2 第 7 条：不替 error 编病因）。
        //    与第 4 步「额度耗尽也不返回空链」是同一条纪律：宁可把病因抛出去，也不让调用方去猜
        ServiceException configFailure = null;
        for (AiModelDO model : models) {
            AiApiKeyDO apiKey = keyMap.get(model.getKeyId());
            try {
                // 密钥没填就在这里判掉，不等 SDK 去建客户端：各平台对空密钥的反应并不一致
                // （有的 Assert 抛、有的照建不误、要到真发请求才吃 401）。照建不误的那种最坏 ——
                // 401 会被下面当成「厂商抖动」静默切到下一个候选，而下一个的密钥同样是空的，
                // 于是绕完整条链才报一句「所有 AI 模型均不可用」，把「没填密钥」说成「厂商挂了」
                AiUtils.validateApiKey(apiKey.getApiKey());
                ChatModel chatModel = modelFactory.getOrCreateChatModel(
                        AiPlatformEnum.validatePlatform(apiKey.getPlatform()), apiKey.getApiKey(), apiKey.getUrl());
                candidates.add(new AiModelCandidate(model, chatModel));
            } catch (ServiceException ex) {
                configFailure = ex;
                // 建链期的配置失败与运行期的 CONFIG 同档：它们字面上就是配置错误
                // （密钥没填、${} 占位符解析不出来），60 秒后重演一遍是纯浪费
                recordConfigBroken(model.getId());
                log.warn("[buildCandidateChain][模型({}/{}) 配置有误，跳过：{}]",
                        model.getId(), model.getName(), ex.getMessage());
            } catch (Exception ex) {
                recordConfigBroken(model.getId());
                log.warn("[buildCandidateChain][模型({}/{}) 客户端构建失败，跳过：{}]",
                        model.getId(), model.getName(), ex.getMessage());
            }
        }
        // 还剩候选就照常返回：单个模型配错不该拖垮整条链，故障切换的意义正在这里。
        // 🔴 一个都不剩时把病因原样抛出去，而不是返回空链 —— 空链会让调用方报「去启用一个模型」，
        //    而真实成因是「密钥没填」。留最后一个而不是第一个，与 callWithFallback 的 last 同口径
        if (candidates.isEmpty() && configFailure != null) {
            throw configFailure;
        }
        // 🔴 把「最终真的会去试哪几个、按什么顺序」记下来。第 2 步（密钥禁用）与第 4 步（冷却/额度）
        //    都是<b>静默</b>剔除：实测 DB 返回 15 个启用模型，链首两个接连失败被冷却 60 秒后，
        //    下一次建链就只剩 13 个 —— 这是冷却在按设计干活，但沿途原本一行日志都没有，
        //    事后想知道「为什么没落在链首那个模型上」就无从查起。
        //    实测代价：2026-10-08 轮10 的 T1 跑了 27 分钟后作废，只因 CHAT 落在 75 而基线是在 76 上量的，
        //    而「76 为什么没被试」查遍了 DB 查询日志、密钥状态、全部失败记录调用点都排除不掉，
        //    最后只能记 ⚠️ 无法判定。缺的就是这一行。
        log.info("[buildCandidateChain][请求模型({}) 任务({}) → 候选 {} 个：{}]",
                requestedModelId, taskKey, candidates.size(),
                candidates.stream().map(candidate -> candidate.getModel().getId()).toList());
        return candidates;
    }

    @Override
    public void pinTaskModel(String taskKey, Long requestedModelId, Long modelId) {
        if (StrUtil.isBlank(taskKey) || modelId == null) {
            return;
        }
        purgeExpiredTaskPins();
        // putIfAbsent：第一个成功的模型就是唯一那个。已经钉过之后不改写 ——
        // 「任务内只用一个模型」不允许「后一次成功把钉子挪走」
        taskPins.putIfAbsent(pinKey(taskKey, requestedModelId),
                new TaskPin(modelId, System.currentTimeMillis() + TASK_PIN_TTL_MILLIS));
    }

    @Override
    public Long getPinnedModelId(String taskKey, Long requestedModelId) {
        if (StrUtil.isBlank(taskKey)) {
            return null;
        }
        TaskPin pin = taskPins.get(pinKey(taskKey, requestedModelId));
        return pin == null || pin.expiresAt() < System.currentTimeMillis() ? null : pin.modelId();
    }

    /**
     * 钉的键 = 任务 + 本次请求的模型
     *
     * <p>{@code requestedModelId} 为空时用 {@code *} 占位，代表「平台默认 + 故障切换自己挑」那一档 ——
     * 这一档才是 R-01 要管的「模型自己换来换去」；显式配了编号的走各自那一档，
     * 那是用户的选择，不该被别的节点的钉子改掉。
     */
    private static String pinKey(String taskKey, Long requestedModelId) {
        return taskKey + "|" + (requestedModelId == null ? "*" : requestedModelId);
    }

    @Override
    public <T> AiModelInvocationBO<T> callWithFallback(List<AiModelCandidate> candidates,
                                                       Function<AiModelCandidate, T> invoker) {
        Exception last = null;
        for (AiModelCandidate candidate : candidates) {
            AiModelDO model = candidate.getModel();
            try {
                T result = invoker.apply(candidate);
                recordSuccess(model.getId());
                return new AiModelInvocationBO<>(model, result);
            } catch (Exception ex) {
                AiModelFailureReason reason = AiModelFailureReason.classify(ex);
                if (reason == AiModelFailureReason.QUOTA) {
                    recordQuotaExhausted(model.getId());
                    log.warn("[callWithFallback][模型({}/{}) 额度耗尽，本次调用判失败、不切换候选：{}]",
                            model.getId(), model.getName(), ex.getMessage());
                    throw exception(MODEL_QUOTA_EXHAUSTED, describe(model), ex.getMessage());
                }
                if (reason == AiModelFailureReason.CONFIG) {
                    // 🔴 与 QUOTA 的关键差别是「切不切」：额度是【账户级】事实（下一个多半也没额度，
                    //    且静默切换会破坏「同一任务同一模型」），配置错误是【模型级】事实（换一个就对了）
                    recordConfigBroken(model.getId());
                    last = ex;
                    log.warn("[callWithFallback][模型({}/{}) 配置类失败，切换下一候选并记 {} 分钟冷却：{}]",
                            model.getId(), model.getName(), AiModelFailureMemory.CONFIG_COOLDOWN_MILLIS / 60_000L, ex.getMessage());
                    continue;
                }
                // 瞬时失败：各厂商异常包装不一致，认不出来的一律按瞬时处理（fail-open 到切换），
                // 理由见 AiModelFailureReason 的类注释
                last = ex;
                recordFailure(model.getId());
                log.warn("[callWithFallback][模型({}/{}) 调用失败，尝试下一个候选：{}]",
                        model.getId(), model.getName(), ex.getMessage());
            }
        }
        throw exception(MODEL_ALL_UNAVAILABLE, last == null ? "无候选模型" : last.getMessage());
    }

    @Override
    public <T> Flux<AiModelInvocationBO<T>> streamWithFallback(List<AiModelCandidate> candidates,
                                                               Function<AiModelCandidate, Flux<T>> streamFactory) {
        return Flux.fromIterable(candidates)
                // concatMap(concurrency=1)：串行订阅，前一候选失败（Flux.empty）才轮到下一个
                .concatMap(candidate -> {
                    AiModelDO model = candidate.getModel();
                    // 每候选独立的「已发射」标记：onErrorResume 无法区分哪个候选发过首包，必须逐个记
                    AtomicBoolean emitted = new AtomicBoolean(false);
                    // defer 保证惰性：切换到该候选时才发起连接
                    return Flux.defer(() -> streamFactory.apply(candidate)
                                    .map(payload -> new AiModelInvocationBO<>(model, payload)))
                            .doOnNext(item -> emitted.set(true))
                            .onErrorResume(ex -> {
                                if (emitted.get()) {
                                    // 首包已发射：前端已收到内容，无法撤回重来，异常原样传播，
                                    // 走调用方既有的 doOnError 落库路径
                                    log.warn("[streamWithFallback][模型({}/{}) 首包后失败，不切换：{}]",
                                            model.getId(), model.getName(), ex.getMessage());
                                    return Flux.error(ex);
                                }
                                AiModelFailureReason reason = AiModelFailureReason.classify(ex);
                                if (reason == AiModelFailureReason.QUOTA) {
                                    recordQuotaExhausted(model.getId());
                                    log.warn("[streamWithFallback][模型({}/{}) 首包前额度耗尽，本次调用判失败、不切换候选：{}]",
                                            model.getId(), model.getName(), ex.getMessage());
                                    return Flux.error(exception(MODEL_QUOTA_EXHAUSTED,
                                            describe(model), ex.getMessage()));
                                }
                                if (reason == AiModelFailureReason.CONFIG) {
                                    // 与 callWithFallback 同一口径：配置错误是模型级事实，切换是对的；
                                    // 但同一份配置重试必然同样失败，所以记长冷却
                                    recordConfigBroken(model.getId());
                                    log.warn("[streamWithFallback][模型({}/{}) 首包前配置类失败，"
                                                    + "切换下一候选并记 {} 分钟冷却：{}]",
                                            model.getId(), model.getName(),
                                            AiModelFailureMemory.CONFIG_COOLDOWN_MILLIS / 60_000L, ex.getMessage());
                                    return Flux.empty();
                                }
                                recordFailure(model.getId());
                                log.warn("[streamWithFallback][模型({}/{}) 首包前失败，切换下一候选：{}]",
                                        model.getId(), model.getName(), ex.getMessage());
                                return Flux.empty();
                            });
                }, 1)
                .switchIfEmpty(Flux.error(() -> exception(MODEL_ALL_UNAVAILABLE, "流式调用全部候选失败")))
                // 首包到达即视为该模型恢复，清除失败记忆（内存操作，IO 线程安全）
                .doOnNext(item -> recordSuccess(item.getModel().getId()));
    }

    /**
     * 只解析被钉住的那一个模型；它现在不可用就返回空链
     *
     * <p>🔴 空链<b>不是</b>「退回去挑一个别的」的信号，而是「这个任务没法继续了」的信号 ——
     * 调用方据此报 {@code MODEL_TASK_PINNED_UNAVAILABLE}，文案要给出路（开新任务）。
     *
     * <p>⚠️ 但<b>配置类</b>失败（密钥没填 / 占位符解析不出来）不走空链，而是原样抛带码的
     * {@code ServiceException}：那条文案给的出路是「开新任务」，而密钥没填时开新任务会<b>一模一样地</b>
     * 再失败一次 —— 真正的出路是去填密钥，填完这个任务钉住的还是同一个模型（钉子不因填密钥而掉），
     * 任务能就地续上。指错出路的代价比不指出路更大。
     */
    private List<AiModelCandidate> buildPinnedChain(Long pinnedModelId, Integer type, String taskKey) {
        AiModelDO model = modelMapper.selectById(pinnedModelId);
        if (model == null || !CommonStatusEnum.isEnable(model.getStatus())
                || !Objects.equals(model.getType(), type)) {
            log.warn("[buildPinnedChain][任务({}) 钉住的模型({}) 已不可用（不存在/禁用/类型不符），按口径不切换]",
                    taskKey, pinnedModelId);
            return List.of();
        }
        AiApiKeyDO apiKey = model.getKeyId() == null ? null : apiKeyService.getApiKey(model.getKeyId());
        if (!isKeyEnabled(apiKey)) {
            log.warn("[buildPinnedChain][任务({}) 钉住的模型({}/{}) 密钥已禁用，按口径不切换]",
                    taskKey, model.getId(), model.getName());
            return List.of();
        }
        // 🔴 刷新配置指纹：让下面这段额度冷却在管理员改完配置（或把模型禁用再启用）后【立刻】作废，
        //    而不是干等 30 分钟。理由见 AiModelFailureMemory#registerFingerprint。
        //    刻意【不】在这里加「配置类冷却」门禁 —— 钉住的链只有一个模型，不存在风暴；
        //    而返回空链会把厂商原文（url error / Model not exist）压成一句「钉住的模型不可用」，
        //    与本类既有纪律「宁可把病因抛出去，也不让调用方去猜」相悖
        failureMemory.registerFingerprint(model, apiKey);
        if (failureMemory.isQuotaExhausted(pinnedModelId)) {
            log.warn("[buildPinnedChain][任务({}) 钉住的模型({}/{}) 额度耗尽，按口径不切换]",
                    taskKey, model.getId(), model.getName());
            return List.of();
        }
        try {
            // 与主链第 5 步同一把判据（AiUtils.isApiKeyBlank 是唯一权威）：
            // 密钥被管理员清空后，这里若不判，就要看各平台 SDK 对空密钥的反应碰运气
            AiUtils.validateApiKey(apiKey.getApiKey());
            ChatModel chatModel = modelFactory.getOrCreateChatModel(
                    AiPlatformEnum.validatePlatform(apiKey.getPlatform()), apiKey.getApiKey(), apiKey.getUrl());
            return List.of(new AiModelCandidate(model, chatModel));
        } catch (ServiceException ex) {
            log.warn("[buildPinnedChain][任务({}) 钉住的模型({}/{}) 配置有误，按口径不切换：{}]",
                    taskKey, model.getId(), model.getName(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.warn("[buildPinnedChain][任务({}) 钉住的模型({}/{}) 客户端构建失败，按口径不切换：{}]",
                    taskKey, model.getId(), model.getName(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * 取出任务钉住的模型，顺手续期
     *
     * <p>续期是刻意的：活跃中的任务不该因为跑得久而掉钉（一次流程执行可以跨越数十分钟），
     * 而已经被放弃的任务在最后一次调用之后自然到期回收。
     */
    private Long takePinnedModelId(String taskKey, Long requestedModelId) {
        if (StrUtil.isBlank(taskKey)) {
            return null;
        }
        String key = pinKey(taskKey, requestedModelId);
        TaskPin pin = taskPins.get(key);
        if (pin == null) {
            return null;
        }
        if (pin.expiresAt() < System.currentTimeMillis()) {
            // remove(key, value) 而不是 remove(key)：并发下别的线程可能已经重新钉过，
            // 那样删掉的会是一个刚生效的钉子
            taskPins.remove(key, pin);
            return null;
        }
        taskPins.put(key, new TaskPin(pin.modelId(), System.currentTimeMillis() + TASK_PIN_TTL_MILLIS));
        return pin.modelId();
    }

    private void purgeExpiredTaskPins() {
        if (taskPins.size() < TASK_PIN_PURGE_THRESHOLD) {
            return;
        }
        long now = System.currentTimeMillis();
        taskPins.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    private boolean isKeyEnabled(AiApiKeyDO apiKey) {
        return apiKey != null && CommonStatusEnum.isEnable(apiKey.getStatus());
    }

    /**
     * 错误消息里对模型的称呼：{@code 编号/厂商标识}
     *
     * <p>用厂商标识（如 {@code qwen3.5-plus-2026-02-15}）而不是显示名：
     * 显示名可以被管理员改成任何东西，而「两轮跑的是不是同一个模型」这个判断
     * 必须建立在不会被人改掉的标识上。
     */
    private String describe(AiModelDO model) {
        return model.getId() + "/" + model.getModel();
    }

    /**
     * 下面四个 record* 是 {@link AiModelFailureMemory} 的一层薄封装，为的是让调用点
     * 继续写「记一次额度耗尽」而不是「记一次失败，分型是 QUOTA」——
     * 前者在 {@code callWithFallback} / {@code streamWithFallback} 那种一屏三个分支的地方读起来不容易错。
     * 逻辑本身一行都不在这里，全部在 {@link AiModelFailureMemory}，向量侧共用同一份。
     */
    private void recordFailure(Long modelId) {
        failureMemory.recordFailure(modelId, AiModelFailureReason.TRANSIENT);
    }

    private void recordQuotaExhausted(Long modelId) {
        failureMemory.recordFailure(modelId, AiModelFailureReason.QUOTA);
    }

    private void recordConfigBroken(Long modelId) {
        failureMemory.recordFailure(modelId, AiModelFailureReason.CONFIG);
    }

    private void recordSuccess(Long modelId) {
        failureMemory.recordSuccess(modelId);
    }

    @Override
    public AiModelFailureMemory getFailureMemory() {
        return failureMemory;
    }

    /**
     * 一个任务钉住的模型 + 到期时刻
     */
    private record TaskPin(Long modelId, long expiresAt) {
    }

}
