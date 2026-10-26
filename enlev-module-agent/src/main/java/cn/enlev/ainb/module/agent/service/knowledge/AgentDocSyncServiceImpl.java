package cn.enlev.ainb.module.agent.service.knowledge;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentDocProperties;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.rag.AiRagApi;
import cn.enlev.ainb.module.ai.api.rag.dto.AiRagSegmentRespDTO;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.PatternMatchUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DOC_ROOT_PATH_INVALID;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DOC_ROOT_PATH_MISSING;

/**
 * 文档语料同步 Service 实现类
 *
 * <h3>三件事，缺一件就会「印着成功、其实没做」</h3>
 * <ol>
 *   <li><b>增量判据落在库里</b>（{@link AiRagApi#getDocumentContentHashes}）——
 *       存进程内存的话重启即失效，之后每次重启都全量重灌、白花上千次 embedding 调用</li>
 *   <li><b>删除也同步</b>（REMOVED 差集）—— 只增不删会留下僵尸文档，
 *       它们照样被召回，AI 拿着已经不存在的手册页言之凿凿，给的链接点开还是 404</li>
 *   <li><b>末尾轮询到收敛才报完成</b> —— 上游的切分与向量化是 {@code @Async} 的，
 *       {@code createKnowledgeSegmentBySplitContentAsync} 的返回值<b>不是</b>完成信号</li>
 * </ol>
 *
 * <h3>🔴 以及一件反过来的事：扫不到语料时<b>什么都不许删</b></h3>
 * REMOVED 差集是「库里的文档名 − 本次扫到的文档名」。这个减法的右项一旦因为
 * {@code root-path} 配错而变成空集，左项就会<b>整个</b>变成待删清单 —— 点一次同步等于
 * 把知识库删空，而向量库的写入不在事务里、删掉补不回来。{@link #doSync()} 开头因此有
 * 一道硬守卫（扫到 0 篇即中止），详见那里的注释与 {@link DocSyncPlan#getSkippedDirectoryCount()}。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentDocSyncServiceImpl implements AgentDocSyncService {

    private static final String STATUS_ADDED = "ADDED";
    private static final String STATUS_CHANGED = "CHANGED";
    private static final String STATUS_UNCHANGED = "UNCHANGED";
    /**
     * 正文没变、但向量没灌全 ⟹ 要重灌
     *
     * <p>🔴 这个状态是「同步可重复跑」这句话成立的前提。增量判据是 hash，
     * 而 embedding 失败（限速 429、超时）<b>不改变正文</b> —— 只靠 hash，
     * 那批 {@code vector_id} 为空的段落永远不会被补上，重跑多少次都报 UNCHANGED。
     *
     * <p>处置上与 CHANGED 完全一样（先删后建），所以同步循环里不需要为它单开分支：
     * {@code switch} 只显式列出「跳过」与「删除」，其余一律落到重灌那条路。
     * 单独立一个名字只是为了让 {@code doc-sync-plan} 能把「语料真改了」和
     * 「上次灌坏了」区分开报给运维。
     */
    private static final String STATUS_REEMBED = "REEMBED";
    private static final String STATUS_REMOVED = "REMOVED";

    /**
     * 收敛轮询间隔。太短只是空转数据库，太长会让接口挂着不放
     *
     * <p>🔴 这三条刻意是<b>包级实例字段</b>而不是 {@code private static final}：
     * 「超时不收敛时如实报 {@code settled=false}、绝不把『还在跑』说成『完成了』」是本次改造
     * 最要紧的一条行为，而它必须能被单测覆盖 —— 若阈值写死成 5 分钟，验证它就得手工等 5 分钟，
     * 于是没人验，回归时也不会有用例变红。单测把它们缩到毫秒级，生产走的就是下面这些默认值，
     * <b>没有对外暴露任何配置项</b>（多一个旋钮就多一种配错的可能，而这三个值没有按环境调整的理由）。
     */
    long settlePollIntervalMs = 2000L;

    /** 连续多少轮「段落数与已向量数都不变」才算稳定。1 轮不够 —— 异步池排队时会有假停顿 */
    int settleStableRounds = 3;

    /** 收敛总超时。108 篇 × 7 段 ≈ 750 次 embedding，按限速留足余量 */
    long settleTimeoutMs = 300_000L;

    /** 每灌多少篇打一行进度。108 篇不打日志的话，卡在哪一篇只能靠猜 */
    private static final int PROGRESS_LOG_STEP = 10;

    /** 召回自检的问句。都是手册里真写了答案的问题，命中不了就说明灌库或向量有问题 */
    private static final List<String> VERIFY_QUERIES = List.of(
            "怎么给角色授权某个页面的按钮权限",
            "AI 助手回答时怎么带出手册来源链接",
            "AI 流程编排里的知识检索节点怎么配",
            "自定义工具怎么注册给智能体用",
            "数据行权限（只能看自己的数据）怎么配",
            "表单设计器怎么搭一个复杂表单",
            "怎么新增一个业务模块（前后端）",
            "单据编号规则怎么配置");

    @Resource
    private AgentDocProperties docProperties;
    @Resource
    private AiRagApi ragApi;
    @Resource
    private AiModelApi modelApi;

    /**
     * 上次同步的诊断快照
     *
     * <p>{@code volatile} 而不是加锁：它只在 {@link #doSync()} 末尾整体替换一次，
     * 读侧拿到的是完整对象，不存在半更新。重启即空 —— 没为它建表，
     * 因为它是诊断信息而不是业务事实（库里的文档数/段落数才是事实，那些现查）。
     */
    private volatile DocSyncStatus lastStatus;

    // ========== planSync ==========

    @Override
    public DocSyncPlan planSync() {
        DocSyncPlan plan = new DocSyncPlan();
        List<String> warnings = new ArrayList<>();
        Path root = resolveRoot();
        plan.setRootPath(root.toString());

        List<DocItem> scanned = new ArrayList<>();
        int skippedDirectories = 0;
        for (String dir : docProperties.getDirectories()) {
            Path dirPath = root.resolve(dir).normalize();
            // 防目录穿越：配置项被改坏时不该让它读到语料根之外的东西
            if (!dirPath.startsWith(root)) {
                warnings.add("跳过越界目录：" + dir);
                skippedDirectories++;
                continue;
            }
            if (!Files.isDirectory(dirPath)) {
                warnings.add("子目录不存在，已跳过：" + dir);
                skippedDirectories++;
                continue;
            }
            collectMarkdown(root, dirPath, scanned, warnings);
        }

        // 库里的实况：文档名 → 清洗后正文的 sha256
        Long knowledgeId = ragApi.findKnowledgeIdByName(docProperties.getKnowledgeName());
        Map<String, String> existing = knowledgeId == null
                ? Map.of()
                : ragApi.getDocumentContentHashes(knowledgeId);
        if (knowledgeId == null) {
            warnings.add("知识库「" + docProperties.getKnowledgeName() + "」还没建出来，"
                    + "所有文档都会按新增处理（同步时会自动建库）");
        }

        // 向量灌全了没有，是独立于 hash 的第二个判据：
        // hash 只能回答「正文要不要重灌」，回答不了「上次灌的这一篇向量化成功了没有」
        Set<String> unembedded = knowledgeId == null
                ? Set.of()
                : ragApi.listUnembeddedDocumentNames(knowledgeId);

        for (DocItem item : scanned) {
            String previous = existing.get(item.getPath());
            if (previous == null) {
                item.setStatus(STATUS_ADDED);
            } else if (!previous.equals(item.getContentHash())) {
                item.setStatus(STATUS_CHANGED);
            } else if (unembedded.contains(item.getPath())) {
                item.setStatus(STATUS_REEMBED);
            } else {
                item.setStatus(STATUS_UNCHANGED);
            }
        }

        // REMOVED 差集：库里有、这次没扫到的。改名与删页都落在这里
        List<DocItem> documents = new ArrayList<>(scanned);
        Set<String> scannedPaths = new HashSet<>();
        for (DocItem item : scanned) {
            scannedPaths.add(item.getPath());
        }
        for (String name : existing.keySet()) {
            if (!scannedPaths.contains(name)) {
                DocItem gone = new DocItem();
                gone.setPath(name);
                gone.setTitle(AgentDocMarkdownSanitizer.extractTitle(null, name));
                gone.setStatus(STATUS_REMOVED);
                documents.add(gone);
            }
        }

        plan.setDocuments(documents);
        plan.setAddedCount((int) documents.stream().filter(d -> STATUS_ADDED.equals(d.getStatus())).count());
        plan.setChangedCount((int) documents.stream().filter(d -> STATUS_CHANGED.equals(d.getStatus())).count());
        plan.setUnchangedCount((int) documents.stream().filter(d -> STATUS_UNCHANGED.equals(d.getStatus())).count());
        plan.setReembedCount((int) documents.stream().filter(d -> STATUS_REEMBED.equals(d.getStatus())).count());
        plan.setRemovedCount((int) documents.stream().filter(d -> STATUS_REMOVED.equals(d.getStatus())).count());
        plan.setMarkdownQaCount((int) scanned.stream().filter(AgentDocSyncServiceImpl::looksLikeMarkdownQa).count());
        plan.setSkippedDirectoryCount(skippedDirectories);
        plan.setWarnings(warnings);
        return plan;
    }

    /**
     * 按上游 {@code AiKnowledgeSegmentServiceImpl#isMarkdownQaFormat} 的口径复算一遍
     *
     * <p>🔴 只用于<b>报告</b>，不参与实际切分 —— 那是上游自己在灌库时判的。
     * 复算的价值是让人在同步前就看到「这批语料会被切成什么粒度」，
     * 而不是灌完发现召回的段落莫名其妙。实测 108 篇只有 4 篇过线，
     * 原因见 {@link AgentDocMarkdownSanitizer#toPublicUrl} 上那段注释。
     */
    private static boolean looksLikeMarkdownQa(DocItem item) {
        if (item.getH2Count() < 2 || item.getCharCount() <= 0) {
            return false;
        }
        int lines = item.getLineCount();
        return lines > 0 && (double) item.getH2Count() / lines > 0.1;
    }

    // ========== doSync ==========

    @Override
    public DocSyncResult doSync() {
        long begin = System.currentTimeMillis();
        DocSyncResult result = new DocSyncResult();
        List<String> failures = new ArrayList<>();
        result.setFailures(failures);

        DocSyncPlan plan = planSync();
        List<DocItem> documents = plan.getDocuments() == null ? List.of() : plan.getDocuments();

        // 0. 🔴 守卫：一篇语料都没扫到 ⟹ 整个同步中止，连知识库都不建。
        //    root-path 配成「是目录、但底下没有语料」的路径时（实测三个 profile 都配成了 `/`，
        //    它能过 resolveRoot 的校验），扫描必然为空，而库里既有的上百篇会**全部**落进
        //    REMOVED 差集 —— 不拦住这一步，点一次同步就是把知识库删空。
        //    同样不能退化成报「同步完成、0 篇」：那与「跑成功了、只是恰好没文档」在界面上无法区分。
        int scannedCount = documents.size() - plan.getRemovedCount();
        if (scannedCount == 0) {
            failures.add(StrUtil.format(
                    "语料根目录 {} 下一篇 markdown 都没扫到（配置的子目录 {}，其中 {} 个不存在或越界）。"
                            + "已中止同步：库里既有的 {} 篇文档一篇都没删。请检查 enlev.agent.doc.root-path",
                    plan.getRootPath(), docProperties.getDirectories(),
                    plan.getSkippedDirectoryCount(), plan.getRemovedCount()));
            result.setDurationMs(System.currentTimeMillis() - begin);
            recordStatus(result, failures, begin, null);
            return result;
        }

        // 🔴 配置的子目录有缺失 ⟹ 本次扫描不完整，REMOVED 差集里会混进「其实还在、只是没扫到」的文档。
        //    这里**不**擅自拦下删除：拦了就等于「真的删掉整个目录」永远传播不到库里，
        //    僵尸文档那个缺陷原地复活（那比误删更难发现，因为它是慢慢累积的）。
        //    但必须把这件事说进执行结果 —— DocSyncResult 不带 warnings，
        //    只在 planSync 里报的话，直接调 doc-sync 的人根本看不见。
        if (plan.getSkippedDirectoryCount() > 0 && plan.getRemovedCount() > 0) {
            failures.add(StrUtil.format(
                    "配置的语料子目录有 {} 个不存在或越界，本次扫描不完整，却仍按差集删了 {} 篇文档。"
                            + "若这不是你预期的（例如 root-path 指到了只 checkout 一半的仓库），"
                            + "请修正 enlev.agent.doc.root-path 后重灌",
                    plan.getSkippedDirectoryCount(), plan.getRemovedCount()));
        }

        // 1. 选向量模型：逐个真探一遍，取第一个探通的。
        //    🔴 不再是「取启用列表的第一个」—— 那只代表管理员勾了开关，不代表打得通。
        //    实测（2026-10-25）type=5 且启用的有 9 个，真能走本仓向量代码路径的只有 3 个
        //    （其中 1 个额度已尽），剩下 6 个是被登记成向量类型的【重排序模型】与【多模态向量模型】，
        //    厂商一律回 400 InvalidParameter「url error, please check url」。
        //    所以 get(0) 是抽签，而抽中的多半是坏的；知识库一旦钉上坏模型，
        //    第一篇文档索引时就会触发一次「迁移 + 全库重嵌」，白跑一轮。
        //    一个都探不通就直接失败并说清楚 —— 灌库没有 embedding 是做不了的，
        //    不要静默跳过留下一个「空知识库」让人以为灌成功了
        Long embeddingModelId;
        try {
            embeddingModelId = modelApi.selectUsableEmbeddingModelId(null);
        } catch (ServiceException ex) {
            // 逐字带回，不另编一句「请去启用一个」：本码的消息里已经逐个列出了
            // 试过哪些模型、各自的厂商原文，以及「启用 N 个 / 其中密钥可用 M 个」。
            // 换成自己那句等于把「额度没了」「模型类型登记错了」「密钥被禁用」压成同一种病
            failures.add("没有可用的向量模型：" + ex.getMessage());
            result.setDurationMs(System.currentTimeMillis() - begin);
            recordStatus(result, failures, begin, null);
            return result;
        }

        // 2. 知识库按名称幂等
        Long knowledgeId = ragApi.getOrCreateKnowledge(
                docProperties.getKnowledgeName(),
                "平台产品手册，由 " + docProperties.getDirectories() + " 自动同步，请勿手工编辑",
                embeddingModelId, docProperties.getTopK(), docProperties.getSimilarityThreshold());
        result.setKnowledgeId(knowledgeId);

        // 3. 逐篇同步：UNCHANGED 跳过，REMOVED 删掉，其余（ADDED / CHANGED / REEMBED）先删后建
        int done = 0;
        for (DocItem item : documents) {
            done++;
            if (done % PROGRESS_LOG_STEP == 0 || done == documents.size()) {
                log.info("[doSync][知识库({}) 进度 {}/{} 新增 {} 更新 {} 跳过 {} 删除 {} 失败 {}]",
                        knowledgeId, done, documents.size(), result.getAddedCount(),
                        result.getUpdatedCount(), result.getSkippedCount(),
                        result.getRemovedCount(), failures.size());
            }
            try {
                switch (item.getStatus()) {
                    case STATUS_UNCHANGED -> result.setSkippedCount(result.getSkippedCount() + 1);
                    case STATUS_REMOVED -> {
                        // 走 Service 删：它连带清段落与向量。只删文档记录的话，
                        // 旧向量会永远留在库里被召回，指向一篇已经不存在的手册页
                        int removed = ragApi.deleteDocumentByName(knowledgeId, item.getPath());
                        result.setRemovedCount(result.getRemovedCount() + (removed > 0 ? 1 : 0));
                        if (removed == 0) {
                            failures.add(item.getPath() + "：标记为待删除，但库里没删掉任何记录");
                        }
                    }
                    default -> {
                        String sanitized = readSanitized(item.getPath());
                        if (StrUtil.isBlank(sanitized)) {
                            failures.add(item.getPath() + "：清洗后正文为空，已跳过");
                            continue;
                        }
                        // 变更的先删旧的：只覆盖记录会在向量库里留下无主的旧向量
                        int removed = ragApi.deleteDocumentByName(knowledgeId, item.getPath());
                        ragApi.createDocumentByContent(knowledgeId, item.getPath(),
                                item.getPublicUrl(), sanitized, docProperties.getSegmentMaxTokens());
                        if (removed > 0) {
                            result.setUpdatedCount(result.getUpdatedCount() + 1);
                        } else {
                            result.setAddedCount(result.getAddedCount() + 1);
                        }
                    }
                }
            } catch (Exception ex) {
                // 单篇失败不中断：一篇坏文档不该让另外 107 篇灌不进去
                log.warn("[doSync][文档({}) 同步失败]", item.getPath(), ex);
                failures.add(item.getPath() + "：" + ex.getMessage());
            }
        }

        // 4. 🔴 轮询到收敛才敢说「完成」
        settle(result, knowledgeId);

        result.setDurationMs(System.currentTimeMillis() - begin);
        log.info("[doSync][知识库({}) 新增 {} 更新 {} 跳过 {} 删除 {} 失败 {} 段落 {}/{} 收敛({}) 耗时 {}ms]",
                knowledgeId, result.getAddedCount(), result.getUpdatedCount(), result.getSkippedCount(),
                result.getRemovedCount(), failures.size(), result.getEmbeddedSegmentCount(),
                result.getSegmentCount(), result.isSettled(), result.getDurationMs());
        recordStatus(result, failures, begin, knowledgeId);
        return result;
    }

    /**
     * 轮询段落数直到收敛
     *
     * <p>收敛的判据是两条同时成立：<b>已向量化数 == 段落总数</b>，且这两个数
     * 连续 {@link #settleStableRounds} 轮不变。只看第一条会在「切分还没开始」时误判
     * （那一刻两个数都是 0，相等）；只看第二条会在向量化刚跑完一批时提前收手。
     *
     * <p>超时不抛异常：文档已经灌进去了，抛异常会让人以为整次同步失败、再跑一遍。
     * 如实报 {@code settled=false} 与两个计数，让调用方稍后查 {@link #getSyncStatus()}。
     */
    private void settle(DocSyncResult result, Long knowledgeId) {
        long deadline = System.currentTimeMillis() + settleTimeoutMs;
        int stable = 0;
        int lastSegments = -1;
        int lastEmbedded = -1;
        while (true) {
            int segments = ragApi.countSegments(knowledgeId);
            int embedded = ragApi.countEmbeddedSegments(knowledgeId);
            result.setSegmentCount(segments);
            result.setEmbeddedSegmentCount(embedded);

            if (segments == lastSegments && embedded == lastEmbedded) {
                stable++;
            } else {
                stable = 0;
                lastSegments = segments;
                lastEmbedded = embedded;
            }
            if (segments > 0 && embedded == segments && stable >= settleStableRounds) {
                result.setSettled(true);
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                result.setSettled(false);
                result.setSettleNote(StrUtil.format(
                        "等待 {}s 后仍未收敛：段落 {} 条，已向量化 {} 条（差 {} 条还召回不到）。"
                                + "文档已入库，切分/向量化仍在后台跑，稍后查 doc-sync-status",
                        settleTimeoutMs / 1000, segments, embedded, segments - embedded));
                return;
            }
            sleep(settlePollIntervalMs);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把本次结果存成诊断快照；实况计数留给 {@link #getSyncStatus()} 现查 */
    private void recordStatus(DocSyncResult result, List<String> failures, long begin, Long knowledgeId) {
        DocSyncStatus status = new DocSyncStatus();
        status.setSyncedInThisProcess(true);
        status.setLastSyncAt(LocalDateTime.now());
        status.setKnowledgeId(result.getKnowledgeId() != null ? result.getKnowledgeId() : knowledgeId);
        status.setKnowledgeName(docProperties.getKnowledgeName());
        status.setAddedCount(result.getAddedCount());
        status.setUpdatedCount(result.getUpdatedCount());
        status.setSkippedCount(result.getSkippedCount());
        status.setRemovedCount(result.getRemovedCount());
        status.setFailures(new ArrayList<>(failures));
        status.setDurationMs(System.currentTimeMillis() - begin);
        this.lastStatus = status;
    }

    // ========== getSyncStatus ==========

    @Override
    public DocSyncStatus getSyncStatus() {
        DocSyncStatus snapshot = this.lastStatus;
        DocSyncStatus status = new DocSyncStatus();
        if (snapshot != null) {
            status.setSyncedInThisProcess(true);
            status.setLastSyncAt(snapshot.getLastSyncAt());
            status.setAddedCount(snapshot.getAddedCount());
            status.setUpdatedCount(snapshot.getUpdatedCount());
            status.setSkippedCount(snapshot.getSkippedCount());
            status.setRemovedCount(snapshot.getRemovedCount());
            status.setFailures(snapshot.getFailures());
            status.setDurationMs(snapshot.getDurationMs());
        }
        status.setKnowledgeName(docProperties.getKnowledgeName());
        status.setRootPath(StrUtil.blankToDefault(docProperties.getRootPath(), null));
        status.setBaseUrl(docProperties.getBaseUrl());

        Long knowledgeId = snapshot != null && snapshot.getKnowledgeId() != null
                ? snapshot.getKnowledgeId()
                : ragApi.findKnowledgeIdByName(docProperties.getKnowledgeName());
        status.setKnowledgeId(knowledgeId);
        if (knowledgeId != null) {
            List<String> names = ragApi.listDocumentNames(knowledgeId);
            status.setDocumentCount(names == null ? 0 : names.size());
            status.setSegmentCount(ragApi.countSegments(knowledgeId));
            status.setEmbeddedSegmentCount(ragApi.countEmbeddedSegments(knowledgeId));
        }
        fillVectorFiles(status);
        return status;
    }

    /**
     * 列出 {@code SimpleVectorStore} 的落盘文件
     *
     * <p>路径口径抄自 {@code AiModelFactoryImpl#buildSimpleVectorStore}：
     * {@code ${user.home}/vector_store/simple_<模型类名>.json}。
     * 之所以要看这个文件而不是只看数据库 —— 段落行在 MySQL 里、向量在这个文件里，
     * <b>两者可以不一致</b>：文件没落盘（进程被 kill、容器重建）时库里有段、召回恒为 0。
     */
    private void fillVectorFiles(DocSyncStatus status) {
        File dir = new File(FileUtil.getUserHomePath(), "vector_store");
        status.setVectorStoreDir(dir.getAbsolutePath());
        File[] files = dir.listFiles();
        if (files == null) {
            status.setVectorFiles(List.of());
            return;
        }
        List<String> names = new ArrayList<>();
        for (File file : files) {
            names.add(file.getName() + " (" + FileUtil.readableFileSize(file.length()) + ")");
        }
        names.sort(String::compareTo);
        status.setVectorFiles(names);
    }

    // ========== verify ==========

    @Override
    public DocSyncVerify verify() {
        DocSyncVerify verify = new DocSyncVerify();
        List<VerifyItem> items = new ArrayList<>();
        verify.setItems(items);

        Long knowledgeId = ragApi.findKnowledgeIdByName(docProperties.getKnowledgeName());
        verify.setKnowledgeId(knowledgeId);
        if (knowledgeId == null) {
            for (String query : VERIFY_QUERIES) {
                items.add(buildVerifyItem(query, List.of(), "NOT_SYNCED"));
            }
            verify.setAllHit(false);
            verify.setSummary("知识库「" + docProperties.getKnowledgeName() + "」还不存在，请先执行 doc-sync");
            return verify;
        }

        boolean allHit = true;
        for (String query : VERIFY_QUERIES) {
            List<AiRagSegmentRespDTO> segments;
            try {
                segments = ragApi.retrieve(knowledgeId, query, docProperties.getTopK(),
                        docProperties.getSimilarityThreshold());
            } catch (Exception ex) {
                log.warn("[verify][问句({}) 召回失败]", query, ex);
                items.add(buildVerifyItem(query, List.of(), "ERROR"));
                allHit = false;
                continue;
            }
            if (segments == null || segments.isEmpty()) {
                items.add(buildVerifyItem(query, List.of(), "NO_HIT"));
                allHit = false;
                continue;
            }
            AiRagSegmentRespDTO top = segments.get(0);
            // 🔴 命中了但没来源 = 需求 1 没做到：用户看到答案却点不进去核实
            String verdict = StrUtil.isBlank(top.getDocumentUrl()) ? "NO_SOURCE" : "OK";
            if (!"OK".equals(verdict)) {
                allHit = false;
            }
            VerifyItem item = buildVerifyItem(query, segments, verdict);
            item.setTopTitle(top.getDocumentTitle());
            item.setTopUrl(top.getDocumentUrl());
            item.setTopScore(top.getScore());
            items.add(item);
        }
        verify.setAllHit(allHit);
        long ok = items.stream().filter(i -> "OK".equals(i.getVerdict())).count();
        verify.setSummary(StrUtil.format("{}/{} 条问句召回到了带来源的段落", ok, items.size()));
        return verify;
    }

    private static VerifyItem buildVerifyItem(String query, List<AiRagSegmentRespDTO> segments, String verdict) {
        VerifyItem item = new VerifyItem();
        item.setQuery(query);
        item.setHitCount(segments.size());
        item.setVerdict(verdict);
        return item;
    }

    // ========== 扫描与读取 ==========

    /**
     * 解析文档根目录：<b>只认显式配置</b>，未配或不合法直接抛错
     *
     * <p>🔴 为什么不再向上探测：第一版靠标记目录逐级向上找，那个目录压根不存在，
     * 于是同步永久空转 —— 而空转<b>不报错</b>，接口返回 200、计数全是 0，
     * 看着像「跑成功了、只是没文档」。这正是本平台头号缺陷「配了但不生效且无提示」的形状。
     */
    private Path resolveRoot() {
        if (StrUtil.isBlank(docProperties.getRootPath())) {
            throw exception(AGENT_DOC_ROOT_PATH_MISSING);
        }
        Path configured = Paths.get(docProperties.getRootPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(configured)) {
            throw exception(AGENT_DOC_ROOT_PATH_INVALID, configured.toString());
        }
        return configured;
    }

    private void collectMarkdown(Path root, Path dirPath, List<DocItem> documents, List<String> warnings) {
        try (Stream<Path> stream = Files.walk(dirPath)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                String relPath = root.relativize(file).toString().replace('\\', '/');
                if (isExcluded(relPath)) {
                    continue;
                }
                try {
                    String raw = Files.readString(file, StandardCharsets.UTF_8);
                    String sanitized = AgentDocMarkdownSanitizer.sanitize(
                            raw, relPath, docProperties.getBaseUrl());
                    DocItem item = new DocItem();
                    item.setPath(relPath);
                    item.setTitle(AgentDocMarkdownSanitizer.extractTitle(raw, relPath));
                    item.setPublicUrl(AgentDocMarkdownSanitizer.toPublicUrl(relPath, docProperties.getBaseUrl()));
                    // 🔴 hash 算的是<b>清洗后</b>的正文 —— 库里存的就是它，
                    // 拿原文的 hash 去比会篇篇都算「变了」，等于每次都全量重灌
                    item.setContentHash(DigestUtil.sha256Hex(sanitized));
                    item.setCharCount(sanitized.length());
                    item.setLineCount(countLines(sanitized));
                    item.setH2Count(countH2(sanitized));
                    item.setEstimatedTokens(estimateTokens(sanitized));
                    documents.add(item);
                } catch (IOException ex) {
                    warnings.add("读取失败：" + relPath + "（" + ex.getMessage() + "）");
                }
            }
        } catch (IOException ex) {
            warnings.add("遍历失败：" + dirPath + "（" + ex.getMessage() + "）");
        }
    }

    /**
     * 重新读一遍并清洗
     *
     * <p>{@link #planSync()} 只留 hash 与计数、不留正文，是为了让 {@code doc-sync-plan}
     * 这个诊断接口的响应体不至于一口气带上 1MB markdown。代价是这里再读一次盘 ——
     * 百来篇、每篇万字符量级，可忽略。
     */
    private String readSanitized(String relPath) {
        try {
            String raw = Files.readString(resolveRoot().resolve(relPath), StandardCharsets.UTF_8);
            return AgentDocMarkdownSanitizer.sanitize(raw, relPath, docProperties.getBaseUrl());
        } catch (IOException ex) {
            log.warn("[readSanitized][文档({}) 读取失败]", relPath, ex);
            return null;
        }
    }

    /** {@code excludePatterns} 走 Spring 的简单通配（{@code *} 可跨路径分隔符） */
    private boolean isExcluded(String relPath) {
        List<String> patterns = docProperties.getExcludePatterns();
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        return PatternMatchUtils.simpleMatch(patterns.toArray(new String[0]), relPath);
    }

    private static int countLines(String content) {
        if (content.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static int countH2(String content) {
        int count = 0;
        for (String line : content.split("\n", -1)) {
            if (line.stripLeading().startsWith("## ")) {
                count++;
            }
        }
        return count;
    }

    /**
     * 粗估 token 数
     *
     * 中文按 1 字≈1 token、其余按 4 字符≈1 token。
     * <b>只用于评估灌库成本量级</b>，不作计费依据 —— 真实计费一律以厂商回传的 usage 为准。
     */
    private static int estimateTokens(String content) {
        int chinese = 0;
        int other = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) >= 0x4E00 && content.charAt(i) <= 0x9FFF) {
                chinese++;
            } else {
                other++;
            }
        }
        return chinese + other / 4;
    }

}
