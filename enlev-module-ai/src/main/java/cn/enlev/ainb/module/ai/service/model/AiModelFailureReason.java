package cn.enlev.ainb.module.ai.service.model;

import cn.hutool.core.util.StrUtil;

/**
 * 模型调用失败的分型（缺陷 D4-28 / 红线 §10.9 第 30 条）
 *
 * <h3>为什么必须分型</h3>
 * 分型之前，{@code AiModelFailoverServiceImpl} 对任何异常一律切换下一个候选，
 * 它自己的注释写的是「不做异常分类（4xx 不切换之类）：各厂商异常包装不一致，首版一律切换」。
 * 后果是<b>额度耗尽被静默吸收</b>：一轮 e2e 里 CHAT 从 model 76 漂到 78 再漂到 80
 * （实测 {@code 76×17 → 78×2 → 80×19}），而库里看不出发生过切换 ——
 * 于是「两轮跑的是不是同一个东西」无法保证，所有「跑两轮对比数字」的验收跑多少次都不算数。
 *
 * <p>裁定 R-01 的口径是：<b>同一个任务只能用同一个模型；额度没了就算失败；
 * 换下一个模型只准发生在任务边界。</b> 这条枚举就是那个口径的判据入口。
 *
 * <h3>🔴 兜不住的时候一律按 {@link #TRANSIENT}（fail-open 到切换）</h3>
 * 判据是<b>错误码字符串</b>，不是 HTTP 状态码 —— 各厂商把状态码包在不同的异常类型里，
 * 且 403 既可能是额度、也可能是鉴权，按状态码判会误伤。
 * 认不出来时按「瞬时」处理，代价是「多切一次候选」；
 * 反过来按「额度」处理，代价是「把一次网络抖动判成额度耗尽、整个功能停摆」——
 * 后者的破坏力大得多，所以宁可 fail-open。
 *
 * @author 走进新时代
 */
public enum AiModelFailureReason {

    /**
     * 额度 / 余额类失败：<b>该次调用判失败，绝不切换下一个候选</b>
     *
     * <p>换模型只准发生在任务边界：本模型额度没了，下一个<b>任务</b>才落到下一个候选。
     */
    QUOTA,

    /**
     * 配置类失败：<b>切换下一个候选</b>，但冷却 30 分钟而不是 60 秒
     *
     * <h3>为什么必须从 {@link #TRANSIENT} 里分出来</h3>
     * 2026-10-10 19:20 那一轮，候选链是 {@code [77, 79, 82, 83, 84, 85, 86, 87, 88, 89, 75]}：
     * 11 个模型全部启用、密钥也启用，选择期两道过滤全过，直到真发请求才吃到厂商的
     * {@code 400 url error} / {@code 404 Model not exist} / {@code 400 enable_thinking restricted to True}。
     * <b>前 10 个接连失败烧掉 23 秒</b>，最后靠 sort=1 的 75 兜底才跑通。
     * 而 60 秒冷却一过期，19:24:11 又把同一场风暴原样重演了一遍 ——
     * 因为这些都是<b>确定性</b>失败：同一份配置重试必然同样失败，重演是纯浪费。
     *
     * <h3>与 {@link #QUOTA} 的关键差别是「切不切」</h3>
     * 额度是<b>账户级</b>事实（下一个模型多半也没额度，且静默切换会破坏「同一任务同一模型」）；
     * 配置错误是<b>模型级</b>事实（换一个就对了）—— 切换正是故障切换存在的意义。
     * 所以 CONFIG 切换、QUOTA 不切换，两者共用长冷却。
     *
     * <p>🔴 冷却记忆以 {@code update_time} 为配置指纹：管理员改好 url、或把模型禁用再启用之后，
     * 那 30 分钟立刻作废，不必干等。否则会出现「测试按钮一点就通、生产链却还锁着它」——
     * 那比没有测试按钮更让人抓狂。
     */
    CONFIG,

    /**
     * 瞬时失败（网络抖动、超时、限流、5xx……）：按候选链切换下一个
     *
     * <p>这是分型之前的唯一行为，保留不动 —— 它本来就是对的，问题只出在把额度也当成了它。
     */
    TRANSIENT;

    /**
     * 额度类错误的特征串，<b>大小写不敏感</b>子串匹配
     *
     * <p>🔴 前两条是<b>现场实测</b>到的原文（2026-10 的厂商 403 响应，逐字取自后端日志）：
     * <pre>
     * 403 - {"code":"AllocationQuota.FreeTierOnly","message":"Free quota exhausted.
     *        To continue accessing the model on a paid basis, please add funds or
     *        disable the \"use free tier only\" mode in the management console."}
     * </pre>
     * 其余几条是按各厂商<b>公开错误码</b>收进来的（推断，现场未出现过），
     * 收它们是因为「换一家厂商就认不出额度耗尽」会让本防线静默退化回旧行为。
     *
     * <p>刻意<b>不</b>收 {@code "quota"} 这种裸词：它会撞上 {@code Throttling.RateQuota}
     * （每秒限流）—— 那是瞬时的，切下一个候选是对的，判成额度耗尽反而会把好模型长期拉黑。
     */
    private static final String[] QUOTA_MARKERS = {
            "AllocationQuota.FreeTierOnly",
            "Free quota exhausted",
            "insufficient_quota",
            "QuotaExceeded",
            "Arrearage",
            "balance is insufficient",
    };

    /**
     * 配置类错误的特征串，<b>大小写不敏感</b>子串匹配
     *
     * <p>🔴 只收<b>逐字实测到的原文</b>或<b>厂商公开错误码</b>，与 {@link #QUOTA_MARKERS} 同一纪律：
     * <ul>
     *   <li>{@code url error} —— DashScope 端点不匹配，2026-10-10 实测原文
     *       {@code 400 - {"code":"InvalidParameter","message":"url error, please check url！"}}</li>
     *   <li>{@code Model not exist} —— DashScope 模型标识写错，实测
     *       {@code 404 - {"code":"ModelNotFound","message":"Model not exist"}}</li>
     *   <li>{@code model_not_found} —— OpenAI 家族的公开错误码，同一种病</li>
     *   <li>{@code enable_thinking} —— 实测
     *       {@code 400 - enable_thinking parameter is restricted to True}（模型只支持思考模式）</li>
     *   <li>鉴权类五条 —— 实测 401 原文 {@code Invalid API-key} / {@code Token is invalid}，
     *       加上 OpenAI 公开码 {@code invalid_api_key} / {@code Incorrect API key provided}
     *       与 DashScope 公开码 {@code InvalidApiKey}</li>
     *   <li>{@code Model.AccessDenied} —— DashScope 控制台未开通该模型</li>
     * </ul>
     *
     * <p>🔴 刻意<b>不</b>收裸 {@code InvalidParameter}、裸 {@code 400}、裸 {@code 401}：
     * {@code InvalidParameter} 同时覆盖<b>请求级</b>参数错误（这一次调用写错了），
     * 按它判会把一个本来好的模型锁死 30 分钟。
     * <b>误判成 CONFIG 的代价（30 分钟锁死）远大于误判成 TRANSIENT（60 秒后多试一次）</b>，
     * 所以这一档收得比 QUOTA 更紧。
     */
    private static final String[] CONFIG_MARKERS = {
            "url error",
            "Model not exist",
            "model_not_found",
            "enable_thinking",
            "Invalid API-key",
            "Incorrect API key provided",
            "invalid_api_key",
            "InvalidApiKey",
            "Token is invalid",
            "Model.AccessDenied",
    };

    /**
     * 给一次失败分型
     *
     * <p>🔴 必须<b>遍历整条 cause 链</b>：厂商 SDK 常把真正的响应体包在两三层异常里
     * （{@code RuntimeException → WebClientResponseException → …}），只看最外层的
     * message 往往只有一句「调用失败」，那样额度耗尽会被误判成瞬时、防线等于没加。
     *
     * <p>🔴 QUOTA 与 CONFIG 是<b>两轮独立的全链扫描</b>，不是在同一个循环里谁先命中谁赢：
     * 外层异常带一句配置类措辞、而内层 cause 才是真正的额度响应，这种情况按「先命中」判
     * 会把额度耗尽降级成可切换 —— 那正是裁定 R-01 要防的静默漂移。额度优先，逐字保住既有行为。
     *
     * @param error 调用抛出的异常，可为 null
     * @return 分型；认不出来一律 {@link #TRANSIENT}
     */
    public static AiModelFailureReason classify(Throwable error) {
        if (containsAnyMarkerInChain(error, QUOTA_MARKERS)) {
            return QUOTA;
        }
        if (containsAnyMarkerInChain(error, CONFIG_MARKERS)) {
            return CONFIG;
        }
        return TRANSIENT;
    }

    private static boolean containsAnyMarkerInChain(Throwable error, String[] markers) {
        // 限深而不是 while(true)：cause 自引用（ex.initCause(ex)）在真实代码里出现过，
        // 那样会在这儿转出栈溢出 —— 一个「分类工具」把调用方打挂是不可接受的
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 10; depth++) {
            String message = cursor.getMessage();
            if (StrUtil.isNotBlank(message)) {
                for (String marker : markers) {
                    if (StrUtil.containsIgnoreCase(message, marker)) {
                        return true;
                    }
                }
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return false;
    }

}
