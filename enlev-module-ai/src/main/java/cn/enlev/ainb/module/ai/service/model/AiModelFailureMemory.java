package cn.enlev.ainb.module.ai.service.model;

import cn.enlev.ainb.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型失败冷却记忆（对话侧与向量侧<b>共用同一份</b>）
 *
 * <h3>为什么从 {@code AiModelFailoverServiceImpl} 里搬出来</h3>
 * 原先这四张表是那个类的私有字段，于是只有走过<b>候选链</b>的模型（也就是对话模型）才会被记住。
 * 向量模型不经过候选链 —— 它由知识库钉住、直接用 —— 所以「额度耗尽」这件事在向量侧
 * 根本记不下来，每次索引都要重新撞一遍同一堵墙。
 *
 * <p>把记忆搬出来不是为了好看，是为了<b>不写第二份</b>：冷却时长、配置指纹、
 * 「指纹不匹配即作废」这三条规则一旦在向量侧再实现一遍，两份迟早漂移，
 * 而漂移的表现恰好是「一类模型记得住、另一类记不住」，最难归因。
 *
 * <h3>键是 modelId，对话与向量不会串</h3>
 * 一个 {@code ai_model} 行只有一种 {@code type}，所以按 modelId 存的记忆天然分域。
 * 这也是 {@code AiModelFailoverServiceImpl#buildCandidateChain} 第 2.5 步
 * <b>刻意不按本次链里的模型去清理死键</b>的原因 —— {@code models} 是按 type 过滤过的，
 * 那样清理会把向量侧的记忆一起抹掉。
 *
 * <h3>内存态、按 JVM</h3>
 * 多实例部署下各自记忆，跨实例最多浪费一次探测，不会更坏；不落库、不引入 Redis。
 *
 * @author 走进新时代
 */
public class AiModelFailureMemory {

    /**
     * 瞬时失败的冷却期（毫秒）
     *
     * 没有冷却时，每个请求都会先撞一遍已知坏模型——同步路径意味着一次完整的 HTTP 超时
     * （可能 10~60 秒）才轮到好模型，延迟不可接受。
     */
    public static final long COOLDOWN_MILLIS = 60_000L;

    /**
     * 额度耗尽的冷却期（毫秒）—— 刻意远长于 {@link #COOLDOWN_MILLIS}
     *
     * <p>额度不会在 60 秒内回来。用短冷却的话，每个新任务都要先白撞一次 403 才落到下一个候选；
     * 用长冷却，「换下一个模型」就真的发生在任务边界上，而不是每次调用的开头。
     *
     * <p>🔴 它<b>不是</b>「等额度回来」的机制 —— 裁定 R-01 已经把那条路关掉了
     * （「额度没了就算失败，然后换下一个模型继续做」）。这里只是不再反复去撞一堵已知的墙。
     * 30 分钟也是内存态的自愈上限：万一分类误判，最长 30 分钟后该模型自己回到候选链。
     */
    public static final long QUOTA_COOLDOWN_MILLIS = 30 * 60 * 1000L;

    /**
     * 配置类失败的冷却期（毫秒）—— 与额度同档，刻意远长于 {@link #COOLDOWN_MILLIS}
     *
     * <p>{@code url error} / {@code Model not exist} / {@code enable_thinking restricted to True}
     * 是<b>确定性</b>失败：同一份配置重试必然同样失败。2026-10-10 19:20 那一轮实测，
     * 前 10 个候选接连失败烧掉 23 秒才靠 sort=1 的兜底模型跑通；60 秒冷却一过期，
     * 19:24:11 又把<b>同一场风暴原样重演</b>了一遍。
     *
     * <p>🔴 长冷却带来一个新陷阱，靠<b>配置指纹</b>堵掉（见 {@link #configFingerprintAt}）：
     * 管理员改好 url、或把模型禁用再启用之后，这段冷却必须<b>立刻</b>作废。
     * 否则「测试按钮一点就通、生产链却还锁着它最长 30 分钟」—— 那比没有测试按钮更让人抓狂。
     */
    public static final long CONFIG_COOLDOWN_MILLIS = 30 * 60 * 1000L;

    /**
     * modelId -> 最近一次<b>瞬时</b>调用失败的记录（时间戳 + 当时的配置指纹）
     *
     * <p>容量天然受模型总数限制，无需淘汰策略
     */
    private final Map<Long, Failure> failureAt = new ConcurrentHashMap<>();

    /**
     * modelId -> 最近一次<b>额度耗尽</b>的记录。同样受模型总数限制
     */
    private final Map<Long, Failure> quotaExhaustedAt = new ConcurrentHashMap<>();

    /**
     * modelId -> 最近一次<b>配置类失败</b>的记录（{@link AiModelFailureReason#CONFIG}）
     */
    private final Map<Long, Failure> configBrokenAt = new ConcurrentHashMap<>();

    /**
     * modelId -> 最近一次读到该模型时的<b>配置指纹</b>（{@code model.update_time|apiKey.update_time}）
     *
     * <h3>🔴 为什么要有它</h3>
     * 三段冷却都是内存态，而配置是<b>会被管理员改的</b>。没有指纹时，「改好了 url」
     * 与「刚失败过」在冷却记忆里长得一模一样，于是生产链会把一个已经修好的模型
     * 继续锁在候选链外最长 30 分钟。指纹让冷却记忆<b>自带它说的是哪一份配置</b>：
     * 指纹不匹配 ⇒ 那次失败针对的是另一份配置 ⇒ 记忆作废。
     *
     * <h3>🔴 为什么不用「加一个 public clearFailure(modelId) 让 Service 去调」</h3>
     * 那需要在 {@code AiModelServiceImpl} / {@code AiApiKeyServiceImpl} 里注入
     * {@code AiModelFailoverService}，而它已经注入 {@code AiApiKeyService}
     * ⟹ Spring Boot 2.6+ 默认<b>禁止循环引用</b>，启动直接失败。
     * 指纹方案零新增公开 API、零事件、零循环。
     */
    private final Map<Long, String> configFingerprintAt = new ConcurrentHashMap<>();

    /**
     * 登记（刷新）一个模型当前的配置指纹
     *
     * <p>🔴 必须在做冷却判断<b>之前</b>调用：若只为「通过过滤的模型」刷新，
     * 一个正处于冷却中的模型会先被过滤掉、永远走不到刷新那一步 ⟹ 指纹永远是旧的
     * ⟹ 永远匹配 ⟹ 改好配置也解不了锁。
     *
     * <p>纯内存操作，调用方手里的 {@code model} 与 {@code apiKey} 直接传进来，一次 DB 都不多打。
     *
     * @param model  模型，不可为 null
     * @param apiKey 模型挂的密钥，可为 null（历史行可能取不到）
     */
    public void registerFingerprint(AiModelDO model, AiApiKeyDO apiKey) {
        configFingerprintAt.put(model.getId(), configFingerprint(model, apiKey));
    }

    /**
     * 记一次失败，冷却时长由分型决定
     *
     * @param modelId 模型编号
     * @param reason  分型，见 {@link AiModelFailureReason}；null 按 {@link AiModelFailureReason#TRANSIENT} 处理
     */
    public void recordFailure(Long modelId, AiModelFailureReason reason) {
        AiModelFailureReason actual = reason == null ? AiModelFailureReason.TRANSIENT : reason;
        Failure failure = new Failure(System.currentTimeMillis(), configFingerprintAt.get(modelId));
        switch (actual) {
            case QUOTA -> quotaExhaustedAt.put(modelId, failure);
            case CONFIG -> configBrokenAt.put(modelId, failure);
            case TRANSIENT -> failureAt.put(modelId, failure);
        }
    }

    /**
     * 记一次成功：清掉这个模型的全部失败记忆
     *
     * <p>三段一起清，不是只清瞬时那段：能调通就说明额度又有了（管理员充值 / 关掉了
     * 「仅用免费额度」）、配置也是对的，继续拿旧记忆拦人就是把一个已经恢复的模型锁在门外。
     */
    public void recordSuccess(Long modelId) {
        failureAt.remove(modelId);
        quotaExhaustedAt.remove(modelId);
        configBrokenAt.remove(modelId);
    }

    /**
     * 这个模型当前<b>是否没有任何生效中的失败记忆</b>
     *
     * <p>🔴 这是「记忆」不是「探测」：返回 true 只说明它近期没在本进程里失败过，
     * 不代表它真的能用 —— 从没试过的模型（例如被登记成向量类型、实际却是重排序模型的那几个）
     * 这里一律是 true。调用方若要把结论当事实用，必须自己真发一次请求。
     */
    public boolean isBelievedUsable(Long modelId) {
        return !isCoolingDown(modelId) && !isQuotaExhausted(modelId) && !isConfigBroken(modelId);
    }

    public boolean isCoolingDown(Long modelId) {
        return isMemoryActive(failureAt.get(modelId), modelId, COOLDOWN_MILLIS);
    }

    public boolean isQuotaExhausted(Long modelId) {
        return isMemoryActive(quotaExhaustedAt.get(modelId), modelId, QUOTA_COOLDOWN_MILLIS);
    }

    public boolean isConfigBroken(Long modelId) {
        return isMemoryActive(configBrokenAt.get(modelId), modelId, CONFIG_COOLDOWN_MILLIS);
    }

    /**
     * 一条失败记忆是否仍然生效：既要<b>没过期</b>，也要<b>说的还是当前这份配置</b>
     *
     * <p>🔴 指纹不匹配 ⇒ 记忆作废。那次失败针对的是另一份配置（改过 url、换过密钥、
     * 或只是被禁用又启用 —— {@code updateById} 一律刷新 {@code update_time}），
     * 拿它继续拦人就是把一个可能已经修好的模型锁在候选链外最长 30 分钟。
     *
     * <p>🔴 记录时指纹为 null（{@link #configFingerprintAt} 里还没有这个模型）⇒ <b>跳过指纹判定</b>，
     * 只按时间算。这是刻意的保守方向：认不出配置时宁可让冷却继续生效，
     * 也不能把一段本该生效的冷却凭空抹掉 —— 那等于把防线 fail-open 回「每个请求都重演一遍风暴」。
     * 对话侧的生产路径不会出现这种情况（候选一定来自建链，而建链第 2.5 步已登记指纹）；
     * 向量侧只要按 {@link #registerFingerprint} 的纪律先登记再判断，同样不会出现。
     *
     * <p>当前指纹为 null（历史行取不到 {@code update_time}）时靠 {@link Objects#equals} 比较，
     * null == null 仍然算匹配 —— 同样保守。
     */
    private boolean isMemoryActive(Failure failure, Long modelId, long cooldownMillis) {
        if (failure == null || System.currentTimeMillis() - failure.at() >= cooldownMillis) {
            return false;
        }
        return failure.fingerprint() == null
                || Objects.equals(failure.fingerprint(), configFingerprintAt.get(modelId));
    }

    /**
     * 配置指纹 = {@code model.update_time|apiKey.update_time}
     *
     * <p>用 {@code update_time} 而不是内容哈希：MyBatis-Plus 的 {@code FieldFill.INSERT_UPDATE}
     * 保证任何一次 {@code updateById} 都会刷新它，<b>包括只写 status 一个字段的</b>
     * {@code updateModelStatus} / {@code updateApiKeyStatus} ⟹ 「点一下启用」
     * 天然等于「清掉这个模型的失败记忆，立刻重新纳入候选」。
     * 这正是列表页那两个按钮与本机制的接合点。
     *
     * <p>⚠️ 秒级精度：同一秒内「失败 → 改配置」两步会让指纹看起来没变，
     * 后果只是这一个模型多冷却到上限，不影响正确性，不值得为它引入版本号列。
     */
    private static String configFingerprint(AiModelDO model, AiApiKeyDO apiKey) {
        return model.getUpdateTime() + "|" + (apiKey == null ? null : apiKey.getUpdateTime());
    }

    /**
     * 一条失败记忆：发生时刻 + 当时的配置指纹
     */
    private record Failure(long at, String fingerprint) {
    }

}
