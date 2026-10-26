package cn.enlev.ainb.module.ai.service.model;

import cn.enlev.ainb.module.ai.service.model.bo.AiModelCandidate;
import cn.enlev.ainb.module.ai.service.model.bo.AiModelInvocationBO;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * AI 模型故障切换 Service 接口
 *
 * 统一的「默认模型 + 智能切换」语义：
 * 1. 默认使用「启用 + 密钥启用 + sort 排序最前」的模型；
 * 2. 模型不可用（禁用、密钥禁用、客户端构建失败、<b>瞬时</b>调用失败）时，按 sort 顺序自动切换到下一个候选。
 *
 * agent 链路（{@code AiModelApiImpl}）与 ai 聊天链路（{@code AiChatMessageServiceImpl}）共用本组件。
 *
 * <h3>🔴 「切换」是有边界的，不是无条件的（缺陷 D4-28 / 红线 §10.9 第 30 条）</h3>
 * 裁定 R-01 的口径：<b>同一个任务只能用同一个模型；额度没了就算失败；
 * 换下一个模型只准发生在任务边界，而且换到哪个必须记下来。</b>
 * 落到本接口上是三条：
 * <ol>
 *   <li><b>额度耗尽不切换</b> —— 判 {@code MODEL_QUOTA_EXHAUSTED} 失败，判据见
 *       {@link AiModelFailureReason}；</li>
 *   <li><b>任务内粘滞</b> —— 传了 {@code taskKey} 的调用，任务内后续调用只用第一次成功选中的那个模型，
 *       连候选链都不再进；</li>
 *   <li><b>流式首包后不切换</b> —— 前端已收到内容，撤不回来（这条早于本裁定，保持不动）。</li>
 * </ol>
 *
 * <h3>🔴 粘滞管的是「故障切换」，不是「用户配了哪个模型」</h3>
 * 粘滞的键是 <b>{@code taskKey} + 本次请求的 {@code requestedModelId}</b> 两个维度，不是只按任务。
 * 只按任务钉会吃掉显式配置：一次流程执行里两个 LLM 节点可以各自配不同的 {@code modelId}
 * （{@code LlmNodeExecutor} / {@code LlmConditionNodeExecutor} 都读 {@code node.configLong("modelId")}，
 * 而它们共用 {@code EXEC:<executionId>} 这一个 taskKey），
 * 那样后一个节点会被前一个节点钉住的模型<b>静默顶掉</b> —— 用户明明配了 A，跑出来是 B，
 * 而且不报错。R-01 要禁的是「模型自己换来换去」，不是「把用户的选择改掉」。
 *
 * @author 走进新时代
 */
public interface AiModelFailoverService {

    /**
     * 构建候选链（不做任务级粘滞）
     *
     * <p>🔴 ai 模块自己的聊天 / 会话 / 思维导图 / 写作四条链路都走这个入口。
     * 本组件是<b>平台级</b>的，改口径不能顺手改到流程编排以外去 ——
     * 所以「任务」这个概念只存在于带 {@code taskKey} 的那个重载里。
     *
     * @param requestedModelId 请求指定的模型编号，可为 null（会话快照 / 显式选择的模型）
     * @param type             模型类型，见 {@code AiModelTypeEnum}（1=聊天）
     * @return 候选链；空列表表示当前无任何可用模型，由调用方决定抛什么错误。
     *         ⚠️ 但<b>配置类</b>失败不返回空链，见三参重载的同名说明
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException 一个候选都不剩、
     *         且成因是配置错误时原样抛出（如 {@code API_KEY_BLANK}）；见三参重载
     */
    default List<AiModelCandidate> buildCandidateChain(Long requestedModelId, Integer type) {
        return buildCandidateChain(requestedModelId, type, null);
    }

    /**
     * 构建候选链，并急切预取 ChatModel 客户端
     *
     * 链 = [requestedModelId（校验可用则置顶；不可用记 warn 后丢弃，静默降级不抛异常）]
     *     + [其余 启用 + 密钥启用 的同 type 模型，按 sort 升序]
     *
     * 🔴 所有 DB 读取与客户端构建都在调用线程完成（租户上下文完整）；
     * 后续切换路径（IO 线程）上只剩内存操作与惰性网络调用。
     *
     * <p>{@code taskKey} 非空且<b>这个任务在这个 {@code requestedModelId} 上已经钉住过模型</b>时，
     * 返回的链<b>只有被钉住的那一个</b>：
     * 这正是「同一个任务只能用同一个模型」的落点 —— 不是「优先用它」，是「只用它」。
     * 被钉住的模型若已不可用（被禁用 / 密钥被禁 / 额度耗尽），返回<b>空链</b>，
     * 由调用方报 {@code MODEL_TASK_PINNED_UNAVAILABLE}，<b>绝不悄悄换一个</b>。
     * 🔴 同一任务里<b>不同的</b> {@code requestedModelId} 各自独立钉 —— 见类注释最后一节。
     *
     * <h3>🔴 空链只表示「没有可用模型」，配置错误不返回空链（缺陷 D4-29 的第二段）</h3>
     * 密钥没填（或仍是 SDK 示例值 {@code sk-xxxx}）、{@code ${}} 占位符解析不出来 —— 这类
     * <b>带码的</b> {@code ServiceException} 在「一个候选都不剩」时<b>原样抛出</b>，不降级成空链。
     * 理由与第 4 步「额度耗尽也不返回空链」同一条纪律：空链到了调用方手上只会变成
     * 「没有可用的聊天模型，请在「AI 模型」里启用一个」，而模型<b>本来就启用着</b> ——
     * 那是把「密钥没填」误报成「环境没配模型」，管理员会照着错的方向反复启停一个正常的模型
     * （红线 §10.2 第 7 条：不替 error 编病因）。
     *
     * <p>还有候选时照常返回、不抛：单个模型配错不该拖垮整条链，故障切换的意义正在这里。
     *
     * @param requestedModelId 请求指定的模型编号，可为 null
     * @param type             模型类型
     * @param taskKey          任务标识，可为 null（= 不做粘滞，行为与历史完全一致）。
     *                         同一个任务的多次调用必须传同一个值
     * @return 候选链；空列表表示当前无任何可用模型（配置错误见上面的 throws）
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException 一个候选都不剩、
     *         且最后一次失败是配置错误时，把那个带码异常原样抛出（如 {@code API_KEY_BLANK}、
     *         {@code API_CONFIG_PLACEHOLDER_NOT_RESOLVED}）。调用方<b>不该</b>捕获它去改报别的成因
     */
    List<AiModelCandidate> buildCandidateChain(Long requestedModelId, Integer type, String taskKey);

    /**
     * 把本次实际用到的模型钉给这个任务
     *
     * <p>由调用方在<b>调用成功后</b>调一次（流式是首包到达后）。刻意不放进
     * {@link #callWithFallback} / {@link #streamWithFallback}：那两个方法是泛型的、
     * 也被 ai 模块自己的链路复用，把任务概念塞进去会逼着所有调用方凭空造一个 taskKey。
     *
     * <p>{@code taskKey} 为空是 no-op；🔴 已经钉过之后<b>不会被改写</b> ——
     * 「任务内只用一个模型」意味着第一个成功的就是唯一那个。
     *
     * @param taskKey          任务标识，可为 null（no-op）
     * @param requestedModelId 本次请求指定的模型编号（与 {@code taskKey} 一起构成钉的键），可为 null。
     *                         🔴 必须与 {@link #buildCandidateChain} 传的是同一个值，
     *                         否则钉进去和取出来对不上，粘滞会静默失效
     * @param modelId          实际调用成功的模型编号
     */
    void pinTaskModel(String taskKey, Long requestedModelId, Long modelId);

    /**
     * 查这个任务在这个请求模型上当前钉住的模型编号（纯读，不续期）
     *
     * <p>存在的唯一理由是让调用方把错误报准：{@link #buildCandidateChain} 返回空链有两种成因 ——
     * 「钉住的模型不可用」和「压根没有可用模型」，两者的文案完全不同
     * （前者要说清「本次任务钉住的那个模型没了、请开新任务」，后者要说「去启用一个模型」）。
     * 分不清就会把「额度没了」误报成「环境没配好」，管理员照着错的方向排查。
     *
     * @param taskKey          任务标识，可为 null
     * @param requestedModelId 本次请求指定的模型编号，可为 null
     * @return 钉住的模型编号；没钉过 / 已过期返回 null
     */
    Long getPinnedModelId(String taskKey, Long requestedModelId);

    /**
     * 同步调用：按候选链遍历，单个候选<b>瞬时</b>失败则记入冷却并切换下一个；
     * <b>额度耗尽则立刻判本次调用失败、不切换</b>；全部失败抛 {@code MODEL_ALL_UNAVAILABLE}
     *
     * @param candidates 候选链
     * @param invoker    针对单个候选的调用逻辑
     * @return 调用结果 + 实际使用的模型
     */
    <T> AiModelInvocationBO<T> callWithFallback(List<AiModelCandidate> candidates,
                                                Function<AiModelCandidate, T> invoker);

    /**
     * 流式调用：仅在【首个元素发射前】、且<b>只因瞬时失败</b>才允许切换候选；
     * 发射后异常原样传播（无法撤回前端已收到的内容）
     *
     * 单候选的超时应在 streamFactory 内部对返回的 Flux 加 timeout，
     * 这样「连接挂起不发数据」也能触发切换。
     *
     * @param candidates    候选链
     * @param streamFactory 针对单个候选的流式调用逻辑（惰性，切换到该候选时才订阅）
     * @return 元素流，每个元素携带实际使用的模型；全部候选失败时 onError MODEL_ALL_UNAVAILABLE
     */
    <T> Flux<AiModelInvocationBO<T>> streamWithFallback(List<AiModelCandidate> candidates,
                                                        Function<AiModelCandidate, Flux<T>> streamFactory);

    /**
     * 失败冷却记忆
     *
     * <p>暴露出来是给<b>向量侧</b>（{@code AiEmbeddingFailoverService}）共用的：
     * 向量模型不走候选链（{@link AiModelCandidate} 持有的是 {@code ChatModel}，
     * 建链时还会急切预构建对话客户端，喂不进向量模型），所以它没法通过本接口的其它方法
     * 把「这个向量模型额度没了」记下来。记忆本身是按 {@code modelId} 存的，
     * 而一个 {@code ai_model} 行只有一种 {@code type}，两侧共用不会串。
     *
     * <p>🔴 拿到它之后仍然要遵守 {@link AiModelFailureMemory#registerFingerprint} 的纪律：
     * <b>先登记指纹，再判可用性</b>。顺序反了的话，一个正在冷却的模型永远刷不到新指纹，
     * 管理员改好配置也解不了锁。
     *
     * @return 与对话侧同一份记忆实例（进程内单例）
     */
    AiModelFailureMemory getFailureMemory();

}
