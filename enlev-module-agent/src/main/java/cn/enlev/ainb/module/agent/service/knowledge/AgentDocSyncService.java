package cn.enlev.ainb.module.agent.service.knowledge;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档语料同步 Service
 *
 * 把手册站（{@code xpy-blog/docs} 下的 {@code guide} + {@code develop}，共百来篇）
 * 同步进内置知识库，供 AI 回答「怎么配」「为什么不生效」这类问题。
 *
 * <h3>🔴 为什么必须有 hash 校验与增量同步</h3>
 * 「文档改了、知识库还是旧的」是<b>「配了但不生效」的变体</b> ——
 * 而且比那更隐蔽：用户看到的是一个<b>言之凿凿但过时</b>的回答，
 * 比回答不上来更糟，因为他不会去怀疑。
 *
 * <p>所以同步不是「灌一次就完事」：每次同步都比对内容 hash，
 * 只有变了的文档才重新切分与向量化。没变的跳过，既省 embedding 费用，
 * 也避免向量库里堆重复内容。
 *
 * <h3>🔴 hash 的对照物是<b>库里已有的正文</b>，不是进程内存</h3>
 * 第一版把上次的 hash 存在一个 {@code HashMap} 字段里，重启即失效 ——
 * 于是每次重启后全部文档都按「新增」重灌一遍，白花上千次 embedding 调用，
 * 而接口照常返回 200、计数照常好看，只有账单知道。
 * 现在改成现算库里 {@code content} 的 sha256 来比：天然持久、跨重启、零 DDL。
 *
 * <h3>🔴 删除也要同步（只增不删会留僵尸）</h3>
 * 手册删页或改名之后，库里那篇旧文档和它的段落、向量<b>不会自己消失</b>，
 * 还会继续被召回 —— 于是 AI 拿着一篇已经不存在的手册页面言之凿凿，
 * 而且给的链接点开是 404。所以每次同步都要做「库里的文档名 − 本次扫到的文档名」这个差集。
 *
 * <h3>切分策略：实测落在 SEMANTIC，不是 MARKDOWN_QA</h3>
 * 上游按 url 后缀与「二级标题占行数 &gt; 10%」自动判 {@code MARKDOWN_QA}。
 * 手册的公开地址没有 {@code .md} 后缀（VitePress {@code cleanUrls}），且每页固定 7 个小节、
 * 页均 130 行 ⟹ 占比只有 5% 上下，<b>108 篇里只有 4 篇</b>能满足那个比例。
 * 而 {@code detectDocumentStrategy} 的两条回落分支返回的都是 {@code SEMANTIC}，
 * 所以实际粒度由 {@code segment-max-tokens} 决定。详见
 * {@link AgentDocMarkdownSanitizer#toPublicUrl} 上那段注释。
 *
 * @author 走进新时代
 */
public interface AgentDocSyncService {

    /**
     * 扫描文档目录，返回待同步的清单（<b>不实际写入</b>）
     *
     * 先看再做：灌库要花 embedding 费用，而且向量库的写入不在事务里、
     * 出错不能回滚。所以把「要同步什么」与「真的同步」分成两步。
     *
     * @return 同步计划
     */
    DocSyncPlan planSync();

    /**
     * 按计划执行同步（真的灌库）
     *
     * 只处理 ADDED / CHANGED / REMOVED 的文档：没变的跳过，既省 embedding 费用，
     * 也避免向量库里堆重复内容。
     *
     * <p>CHANGED 与 REMOVED 都<b>走 Service 删</b>，它会连带清理段落与向量。
     * 只删文档记录的话，旧向量会留在库里永远召回得到、却指向已经不存在的内容。
     *
     * <p>🔴 返回时向量<b>可能还没写完</b>：上游的切分与向量化是 {@code @Async} 的。
     * 本方法会在末尾轮询段落数直到稳定（或超时），并如实把结果放进
     * {@link DocSyncResult#settled} —— 超时就报 {@code false}，绝不把「还在跑」说成「完成了」。
     *
     * <p>🔴 <b>防误删守卫</b>：一篇语料都没扫到时<b>整个同步中止</b>，连知识库都不建。
     * {@code root-path} 配成一个「是目录、但底下没有语料」的路径时（实测三个 profile 都配成了
     * {@code /}，它能过 {@code resolveRoot} 的校验），扫描必然为空，而库里既有的上百篇会
     * <b>全部</b>落进 REMOVED 差集 —— 不拦住就是把知识库删空。这里也不能只报
     * 「同步完成、0 篇」：那与「跑成功了、只是恰好没文档」在界面上无法区分。
     *
     * <p>扫到了<b>一部分</b>、但配置的子目录有缺失时（{@link DocSyncPlan#getSkippedDirectoryCount()}
     * 大于 0），删除<b>照常执行</b>、只把「本次扫描不完整」写进 {@link DocSyncResult#getFailures()}：
     * 擅自拦下删除会让「真的删掉整个目录」永远传播不到库里，僵尸文档那个缺陷原地复活。
     *
     * @return 同步结果
     */
    DocSyncResult doSync();

    /**
     * 上次同步的情况 + 当前库里的实况
     *
     * <p>两半来源不同，别混：计数与失败清单来自<b>本进程</b>上次 {@link #doSync()} 的结果
     * （重启即空，因为没为它建表 —— 它是诊断信息，不是业务事实）；
     * 文档数、段落数、已向量化段落数与向量文件是<b>现查</b>的，重启后照样准。
     */
    DocSyncStatus getSyncStatus();

    /**
     * 用几条固定问句实召回一次，验证「灌进去的东西真的能被查出来」
     *
     * <p>🔴 为什么要有这一步：段落行在 MySQL 里、向量在 {@code SimpleVectorStore} 的本地文件里，
     * <b>两者可以不一致</b>。换过 embedding 模型、容器重建过、向量文件没落盘，
     * 都会出现「库里有 900 段、召回恒为 0」。只看计数看不出这件事，只有真查一次才知道。
     */
    DocSyncVerify verify();

    /**
     * 同步结果
     */
    @Data
    class DocSyncResult {

        /** 知识库编号 */
        private Long knowledgeId;
        /** 新灌入的文档数 */
        private int addedCount;
        /** 重新灌入的文档数 */
        private int updatedCount;
        /** 跳过的文档数（内容未变） */
        private int skippedCount;
        /** 删掉的文档数（库里还有、语料里已经没有了） */
        private int removedCount;
        /** 失败的文档及原因 */
        private List<String> failures;
        /** 耗时（毫秒） */
        private long durationMs;
        /** 段落总数（返回时刻的实况） */
        private int segmentCount;
        /** 已向量化段落数（返回时刻的实况） */
        private int embeddedSegmentCount;
        /**
         * 切分与向量化是否已收敛
         *
         * {@code false} 表示还在跑或超时未收敛 —— 此时 {@link #embeddedSegmentCount} 小于
         * {@link #segmentCount}，召回会漏。稍后再查 {@link AgentDocSyncService#getSyncStatus()}。
         */
        private boolean settled;
        /** 未收敛时的说明（收敛了就是 null） */
        private String settleNote;
    }

    /**
     * 同步状态（诊断用）
     */
    @Data
    class DocSyncStatus {

        /** 本进程是否跑过同步。false 时下面那些计数都是 0，别当成「同步过且啥也没干」 */
        private boolean syncedInThisProcess;
        /** 上次同步完成时间 */
        private LocalDateTime lastSyncAt;
        /** 知识库编号 */
        private Long knowledgeId;
        /** 知识库名称（幂等键） */
        private String knowledgeName;
        /** 上次同步：新增 */
        private int addedCount;
        /** 上次同步：更新 */
        private int updatedCount;
        /** 上次同步：跳过 */
        private int skippedCount;
        /** 上次同步：删除 */
        private int removedCount;
        /** 上次同步：失败清单 */
        private List<String> failures;
        /** 上次同步耗时（毫秒） */
        private long durationMs;
        /** 库里现有文档数（现查） */
        private int documentCount;
        /** 库里现有段落数（现查） */
        private int segmentCount;
        /** 已向量化段落数（现查）。与 {@link #segmentCount} 的差额就是「切了但还召回不到」的部分 */
        private int embeddedSegmentCount;
        /** 向量落盘目录（{@code SimpleVectorStore} 的本地文件所在处） */
        private String vectorStoreDir;
        /** 该目录下的向量文件，形如 {@code simple_DashScopeEmbeddingModel.json (1.1 MB)} */
        private List<String> vectorFiles;
        /** 语料根目录；未配置时为 null（此时同步会直接报错，不是空转） */
        private String rootPath;
        /** 引用链接的地址前缀 */
        private String baseUrl;
    }

    /**
     * 召回自检结果
     */
    @Data
    class DocSyncVerify {

        /** 知识库编号；库还没建出来时为 null，此时所有问句都判 NOT_SYNCED */
        private Long knowledgeId;
        /** 逐条问句的结果 */
        private List<VerifyItem> items;
        /** 是否全部命中 */
        private boolean allHit;
        /** 一句话结论，可直接给人看 */
        private String summary;
    }

    /**
     * 单条问句的召回结果
     */
    @Data
    class VerifyItem {

        /** 问句 */
        private String query;
        /** 命中段数 */
        private int hitCount;
        /** 最相关那段的文档标题 */
        private String topTitle;
        /** 最相关那段的公开地址 */
        private String topUrl;
        /** 最相关那段的得分 */
        private Double topScore;
        /** OK / NO_HIT / NO_SOURCE / NOT_SYNCED */
        private String verdict;
    }

    /**
     * 同步计划
     */
    @Data
    class DocSyncPlan {

        /** 文档根目录（绝对路径），便于排查「到底扫的哪里」 */
        private String rootPath;
        /** 扫描到的全部文档，含库里多出来的（状态 REMOVED） */
        private List<DocItem> documents;
        /** 新增的文档数 */
        private int addedCount;
        /** 内容变了、需要重新切分的文档数 */
        private int changedCount;
        /** 未变化、可跳过的文档数 */
        private int unchangedCount;
        /**
         * 正文没变、但向量没灌全，需要重灌的文档数
         *
         * <p>🔴 与 {@link #unchangedCount} 分开报，是因为这两者的处置<b>相反</b>：
         * UNCHANGED 跳过、REEMBED 先删后建。混在一起的话，运维看到「有文档要重灌」
         * 分不清是语料真改了还是上次 embedding 被打断了 —— 前者是预期，后者是要查的事故。
         *
         * <p>典型来源：embedding 被限速（HTTP 429）或超时打断，切分出了段落但
         * {@code vector_id} 没写上。这批段落召回不到，且<b>只靠 hash 比对永远不会被补上</b>。
         */
        private int reembedCount;
        /** 库里还有、语料里已经没有了的文档数 */
        private int removedCount;
        /**
         * 配置里声明了、但这次没能扫的子目录数（不存在，或越界被拦）
         *
         * <p>🔴 大于 0 就意味着<b>本次扫描是不完整的</b>，此时 {@link #removedCount} 这个差集里
         * 会混进「其实还在、只是没扫到」的文档。{@link AgentDocSyncService#doSync()} 会把这个
         * 事实写进 {@link DocSyncResult#getFailures()}，但<b>不</b>拦下删除 —— 拦了就等于
         * 「真的删掉整个目录」永远传播不到库里，只增不删的僵尸缺陷（见类注释）原地复活。
         * 唯一硬拦的是「一篇都没扫到」，那种情况下差集的右项是空集、左项必然全灭。
         *
         * <p>为什么不直接靠 {@link #warnings} 判断：那是给人看的自由文本，
         * 拿字符串匹配来驱动一个删除决策，改一次措辞就把守卫改没了。
         */
        private int skippedDirectoryCount;
        /**
         * 能满足上游 {@code MARKDOWN_QA} 判据的篇数
         *
         * 口径抄自 {@code AiKnowledgeSegmentServiceImpl#isMarkdownQaFormat}：二级标题 ≥2 个
         * 且占行数 &gt; 10%。<b>只用于报告</b>，不影响实际切分（那是上游自己判的）——
         * 上游改了阈值这里要跟着改，否则报告会说谎。
         */
        private int markdownQaCount;
        /** 扫描或配置上的问题（目录不存在、文件读不了等） */
        private List<String> warnings;
    }

    /**
     * 单篇文档
     */
    @Data
    class DocItem {

        /** 相对路径，作为文档的稳定标识（{@code ai_knowledge_document.name} 就是它） */
        private String path;
        /** 标题，取自正文首个一级标题，回落文件名 */
        private String title;
        /** 手册站的公开地址（引用里给用户点的那个） */
        private String publicUrl;
        /** <b>清洗后</b>正文的 sha256。🔴 必须与库里存的正文同口径，否则篇篇都算「变了」 */
        private String contentHash;
        /** 清洗后字符数 */
        private int charCount;
        /** 清洗后行数（与 {@link #h2Count} 一起决定上游会不会判成 MARKDOWN_QA） */
        private int lineCount;
        /** 预估 token 数（粗算，用于评估灌库成本） */
        private int estimatedTokens;
        /** 二级标题数（决定上游会不会判成 MARKDOWN_QA） */
        private int h2Count;
        /** 状态：ADDED / CHANGED / UNCHANGED / REEMBED / REMOVED */
        private String status;
    }

}
