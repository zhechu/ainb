package cn.enlev.ainb.module.ai.api.rag;

import cn.enlev.ainb.module.ai.api.rag.dto.AiRagSegmentRespDTO;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 知识库检索 API
 *
 * <p>⚠️ <b>本目录（{@code api/}）是 agent 模块对 ai 模块的唯一改动。</b>
 * ai 模块的提交全部来自上游且仍在活跃维护，所以这里只做<b>纯新增</b> ——
 * 不改它任何一个既有文件，将来 rebase 只需处理这一个目录。
 *
 * <p>实现是对 {@code AiKnowledgeSegmentService} 的薄封装，不复制检索逻辑
 * （rerank 过采样、相似度阈值那些都在 Service 里，复制一份必然漂移）。
 *
 * @author 走进新时代
 */
public interface AiRagApi {

    /**
     * 在指定知识库里检索
     *
     * @param knowledgeId         知识库编号
     * @param query               查询内容
     * @param topK                最大返回数量，null 用知识库自身配置
     * @param similarityThreshold 相似度阈值，null 用知识库自身配置
     * @return 命中的段落，按相关度排序
     */
    List<AiRagSegmentRespDTO> retrieve(Long knowledgeId, String query,
                                       Integer topK, Double similarityThreshold);

    /**
     * 按名称查找知识库，不存在则创建
     *
     * @param name              知识库名称，作为幂等键
     * @param description       描述
     * @param embeddingModelId  向量模型编号
     * @param topK              召回数量
     * @param similarityThreshold 相似度阈值
     * @return 知识库编号
     */
    Long getOrCreateKnowledge(String name, String description, Long embeddingModelId,
                              Integer topK, Double similarityThreshold);

    /**
     * 直接用<b>内容</b>创建文档并切片
     *
     * 🔴 <b>为什么需要这个方法</b>：上游的 {@code createKnowledgeDocument} 只接受 URL，
     * 内部走 {@code HttpUtil.downloadBytes} 下载 —— 它假设语料来自网络。
     * 而 agent 的语料是<b>本地仓库里的 markdown 文件</b>，既没有 URL、
     * 也不该为了灌库先把内部文档传到某个可下载的地方。
     *
     * <p>实现仍复用上游的切分与向量化能力，只是换了个内容来源。
     *
     * @param knowledgeId 知识库编号
     * @param name        文档名
     * @param content     文档内容
     * @return 文档编号
     */
    Long createDocumentByContent(Long knowledgeId, String name, String content);

    /**
     * 直接用<b>内容</b>创建文档并切片，且由调用方指定公开地址与分片大小
     *
     * <p>与三参版的区别只在两个原本被写死的值：
     * <ul>
     *   <li>{@code url}：三参版一律写 {@code "local://"+name}，那是「内容来自本地」的标记，
     *       <b>不是用户能点开的地址</b>。而引用要能点，就得存手册站的真实 URL。</li>
     *   <li>{@code segmentMaxTokens}：三参版写死 800。它是切分粒度，属于语料的属性而不是 api 的属性
     *       —— 手册与「订单客服」的专属资料合理的粒度并不一样。</li>
     * </ul>
     *
     * <p>🔴 这两个值原先硬编码在实现里（{@code AiRagApiImpl} 的 {@code setUrl} / {@code setSegmentMaxTokens}），
     * 改法是<b>新增重载</b>而不是改三参版的签名：本目录是 agent 对 ai 模块的唯一改动，
     * 只做纯新增，将来 rebase 上游时不必处理既有方法的调用方。
     *
     * @param knowledgeId      知识库编号
     * @param name             文档名（幂等键，手册同步用的是相对路径，如 {@code guide/01-system/user.md}）
     * @param url              文档的公开地址；null 时回落成 {@code "local://"+name}
     * @param content          文档内容
     * @param segmentMaxTokens 分片最大 token 数；null 时用 800
     * @return 文档编号
     */
    Long createDocumentByContent(Long knowledgeId, String name, String url,
                                 String content, Integer segmentMaxTokens);

    /**
     * 取知识库下现有文档的「文档名 → 内容 sha256」
     *
     * <h3>🔴 为什么增量判据要落在库里，而不是进程内存</h3>
     * 第一版把上次同步的 hash 存在一个 {@code HashMap} 字段里，<b>重启即失效</b> ——
     * 于是每次重启后所有文档都被当成新增、重新切分并向量化一遍，白花上千次 embedding 调用。
     * 而这个失效是<b>静默</b>的：接口照常返回 200、计数照常好看，只有账单和向量库知道。
     *
     * <p>改成从库里 {@code content} 现算：天然持久、天然跨重启、零 DDL。
     * 代价是一次全量哈希 —— 百来篇、每篇万字符量级，毫秒级，可忽略。
     *
     * <p>⚠️ 比对口径必须两边一致：库里存的是<b>清洗后</b>的正文，
     * 所以调用方要比对的也得是清洗后的 hash，拿原始文件 hash 去比会篇篇都算「变了」。
     *
     * @param knowledgeId 知识库编号
     * @return 文档名 → 内容 sha256；库不存在或没有文档时返回空 Map
     */
    Map<String, String> getDocumentContentHashes(Long knowledgeId);

    /**
     * 统计知识库下的段落数
     *
     * @param knowledgeId 知识库编号
     * @return 段落数
     */
    int countSegments(Long knowledgeId);

    /**
     * 统计知识库里<b>已向量化</b>的段落数
     *
     * <p>与 {@link #countSegments} 的差额就是「已切分、还没写进向量库」的部分 ——
     * 这部分<b>召回不到</b>。所以它是「同步到底做完了没有」的唯一可靠信号：
     * 只报段落数会把「切了但没向量化」说成完成，而那正是「印着成功、其实没做」的形状。
     *
     * @param knowledgeId 知识库编号
     * @return 已向量化（{@code vector_id} 非空且非占位值）的段落数
     */
    int countEmbeddedSegments(Long knowledgeId);

    /**
     * 列出知识库里<b>向量没灌全</b>的文档名
     *
     * <h3>🔴 为什么必须有它：hash 幂等会让「向量化失败」永远无法自愈</h3>
     * 增量判据是 {@code sha256(清洗后正文)}，正文没变就是 UNCHANGED、整篇跳过。
     * 于是 embedding 被限速/超时打断的那批段落（{@code vector_id} 为空）
     * <b>再跑多少次同步都补不上</b> —— 它们所属文档的 hash 一直是匹配的。
     * 实测形状：一次全量灌 514 段，配额在 322 段处耗尽，之后每次重跑都报
     * 「108 篇全 UNCHANGED」，那 192 段静静躺在库里，永远召回不到。
     * 这比整库没灌更难发现，因为所有计数都是绿的。
     *
     * <p>所以同步前要挑出这批、按「需重灌」处理（先删后建），
     * {@code doc-sync 可重复跑}这句话才成立。
     *
     * <p>判定口径是「段落数 != 已向量化段落数」，<b>含段落数为 0</b> 的文档：
     * 一篇文档一个段落都没有同样召回不到，而 {@code 0 == 0} 会被「相等」漏掉。
     *
     * @param knowledgeId 知识库编号
     * @return 需要重灌的文档名；库不存在或全都灌完了返回空 Set
     */
    Set<String> listUnembeddedDocumentNames(Long knowledgeId);

    /**
     * 按名称删除知识库下的文档（用于重新灌库）
     *
     * @param knowledgeId 知识库编号
     * @param name        文档名
     * @return 删除的数量
     */
    int deleteDocumentByName(Long knowledgeId, String name);

    /**
     * 按名称查知识库编号，不存在返回 null
     *
     * 与 {@link #getOrCreateKnowledge} 的区别：这个<b>不会建库</b>。
     * 检索路径要用它 —— 查询时把知识库建出来是没道理的，
     * 那只会得到一个空库然后「召回 0 条」，掩盖掉「还没灌库」这个真正的问题。
     *
     * @param name 知识库名称
     * @return 知识库编号
     */
    Long findKnowledgeIdByName(String name);

    /**
     * 列出知识库下的文档名
     *
     * @param knowledgeId 知识库编号
     * @return 文档名列表
     */
    List<String> listDocumentNames(Long knowledgeId);

}
