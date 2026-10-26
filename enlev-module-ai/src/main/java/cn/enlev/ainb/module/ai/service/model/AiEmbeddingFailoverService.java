package cn.enlev.ainb.module.ai.service.model;

import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;

/**
 * 向量模型故障切换 Service 接口
 *
 * <h3>为什么不复用 {@link AiModelFailoverService}</h3>
 * 那套是<b>对话专用</b>的，尽管它的 {@code buildCandidateChain(requestedModelId, type, taskKey)}
 * 收一个看着通用的 {@code type} 参数：{@link AiModelCandidate} 持有的是 {@code ChatModel}，
 * 而建链第 5 步会在<b>调用线程上急切预构建对话客户端</b>。喂 {@code MODEL_TYPE_EMBEDDING} 进去，
 * 得到的是一串用向量模型标识构建出来的对话客户端 —— 拿它发请求必然 400，
 * 而那 400 会被分类成「配置错误」，于是所有向量模型被轮流拉黑 30 分钟。
 *
 * <h3>🔴 与对话侧的根本差别：向量切换是<b>迁移</b>，不是<b>兜底</b></h3>
 * 对话模型换了，下一次调用照样出字，用户看不出区别。向量模型换了，
 * 已经写进向量库的那些向量全都作废 —— 它们是在另一个语义空间里算出来的，
 * 维度还可能不一样。所以：
 * <ol>
 *   <li><b>本接口只负责「选」</b>，不碰知识库、不碰段落、不碰向量库。
 *       迁移（改 {@code ai_knowledge.embedding_model_id} + 清空 {@code vector_id} + 重嵌）
 *       是知识库那侧的事，因为只有它知道重嵌的代价；</li>
 *   <li><b>查询侧绝不切换</b>：检索时换一个模型，查询向量落在另一个空间里，
 *       相似度算出来的是垃圾，而且<b>不报错</b> —— 那比检索失败坏得多；</li>
 *   <li>切换只发生在<b>写入边界</b>（给知识库建索引的时候），与裁定 R-01
 *       「换下一个模型只准发生在任务边界」是同一个口径，只是这里的「任务」是一个知识库。</li>
 * </ol>
 *
 * <h3>🔴 为什么必须真实探测，不能只按启用状态挑</h3>
 * 实测（2026-10-25，本机 local）：{@code ai_model} 里 {@code type=5} 且启用的有 9 个，
 * 而其中真能走本仓向量代码路径的只有 3 个，3 个里还有 1 个额度已尽 ——
 * <b>可用的是 2 个</b>。剩下 6 个分两类：
 * <ul>
 *   <li>{@code gte-rerank-v2}、{@code qwen3-vl-rerank} 是<b>重排序</b>模型，被登记成了向量类型；</li>
 *   <li>{@code qwen3-vl-embedding}、{@code qwen2.5-vl-embedding}、
 *       {@code tongyi-embedding-vision-flash}、{@code tongyi-embedding-vision-plus}
 *       是多模态向量模型，DashScope 给它们的是<b>另一个端点</b>，
 *       而 {@code buildTongYiEmbeddingModel} 只构建文本向量端点的客户端。</li>
 * </ul>
 * 这 6 个的厂商原文都是 {@code 400 InvalidParameter「url error, please check url」}。
 * 所以「按 sort 挑下一个启用的」必然先撞上它们；不真实探测一次，
 * 所谓「自动切换」只是把一次失败换成六次失败。
 *
 * @author 走进新时代
 */
public interface AiEmbeddingFailoverService {

    /**
     * 选一个「现在真能用」的向量模型
     *
     * <p><b>快路径</b>：{@code preferredModelId} 对应的模型启用、密钥启用、不在失败冷却中，
     * <b>且本进程近期探测过它、探通了</b> ⇒ 原样返回，一次厂商请求都不发。
     * 这条路每索引一篇文档都要走一遍，所以命中之后不能再带网络往返。
     *
     * <p><b>补探一次</b>：条件都满足、只是「本进程还没试过它」（刚启动，或探测结果已过 TTL）
     * ⇒ 先对它发一次最小向量请求。探通就写进正向记忆并返回；探不通就记进冷却、转慢路径。
     * 🔴 这一步不能省。「失败记忆里没有它」只说明<b>没人试过</b>，不说明它打得通 ——
     * 省掉它的话，一个钉在额度耗尽模型上的知识库，第一篇文档会在 {@code writeVectorStore} 里撞上 403，
     * 而调用方 {@code createKnowledgeSegmentBySplitContentAsync} 是 {@code @Async}，异常被吞、
     * 接口照样返回 200，管理员看到的是「文档建好了、向量化 0%」且没有任何提示；
     * 只有一篇文档的知识库更是永远回不来（迁移要等下一次写入才触发）。
     * 代价是每个知识库每进程最多一次厂商请求，探通后 30 分钟内都走快路径。
     *
     * <p><b>慢路径</b>：首选不可用 / 在冷却中 / 补探失败（或传了 null）⇒
     * 按 sort 升序遍历其余启用中的向量模型，跳过记忆里的坏模型，
     * 对剩下的<b>逐个发一次最小向量请求</b>，返回第一个真出向量的；
     * 探坏的按分型记进冷却记忆，下次不再白撞。
     *
     * <p>🔴 即便补探通过，返回的模型也<b>不保证</b>后续调用一定成功（探测与真实调用之间额度可能正好用完）。
     * 调用方仍必须把真实调用的结果通过 {@link #recordSuccess} / {@link #recordFailure} 回传，
     * 否则「第一次撞上额度耗尽」之后没有任何东西被记住，下一篇文章会原样再撞一次。
     *
     * @param preferredModelId 首选模型编号，通常是知识库钉住的那个；可为 null（= 直接走慢路径）
     * @return 选中的模型（含 id / model / platform / keyId）
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException
     *         {@code EMBEDDING_MODEL_ALL_UNAVAILABLE}：启用中的向量模型一个都探不通。
     *         消息里逐个列出试过哪些、各自的厂商原文
     */
    AiModelDO selectUsableEmbeddingModel(Long preferredModelId);

    /**
     * 记一次<b>真实调用</b>的成功：清掉这个模型的全部失败记忆
     *
     * @param model 实际调用成功的模型
     */
    void recordSuccess(AiModelDO model);

    /**
     * 记一次<b>真实调用</b>的失败，冷却时长由 {@link AiModelFailureReason#classify} 决定
     *
     * <p>与对话侧共用同一份记忆（{@link AiModelFailoverService#getFailureMemory()}），
     * 所以「把模型禁用再启用」这个既有出路在向量侧同样有效：
     * {@code update_time} 一变，配置指纹对不上，冷却立刻作废。
     *
     * @param model 实际调用失败的模型
     * @param error 厂商异常，用于分型；可为 null（按 {@link AiModelFailureReason#TRANSIENT} 处理）
     */
    void recordFailure(AiModelDO model, Throwable error);

}
