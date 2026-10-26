package cn.enlev.ainb.module.agent.service.knowledge;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentDocProperties;
import cn.enlev.ainb.module.agent.service.knowledge.AgentDocSyncService.DocItem;
import cn.enlev.ainb.module.agent.service.knowledge.AgentDocSyncService.DocSyncPlan;
import cn.enlev.ainb.module.agent.service.knowledge.AgentDocSyncService.DocSyncResult;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.rag.AiRagApi;
import cn.hutool.crypto.digest.DigestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DOC_ROOT_PATH_INVALID;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DOC_ROOT_PATH_MISSING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentDocSyncServiceImpl} 的单元测试
 *
 * <p>🔴 <b>纯 Mockito + {@code @TempDir}，不碰 Spring 上下文也不碰真库</b>：
 * {@code enlev-module-agent} 没有 {@code src/test/resources/application-unit-test.yaml}，
 * 这个模块的测试一律是纯 Mockito（要加 DB 测试得先补那份配置）。
 * 语料目录用 JUnit 的临时目录现造几个 markdown，正好也把「扫盘 → 清洗 → 比对」这条链完整跑一遍。
 *
 * <h3>这组用例守的是六个「不报错但结果是错的」形态</h3>
 * 同步这件事的每一种失败都是<b>静默</b>的 —— 接口返回 200、计数照常好看：
 * <ol>
 *   <li><b>空转</b>：根目录没配对，扫到 0 篇，报「同步完成」；</li>
 *   <li><b>全量重灌</b>：hash 口径与库里不一致（原文 vs 清洗后），篇篇都算「变了」，
 *       每次重启白花上千次 embedding 调用；</li>
 *   <li><b>僵尸文档</b>：只增不删，改名/删页之后旧文档照样被召回，给的链接点开是 404；</li>
 *   <li><b>假完成</b>：上游切分与向量化是 {@code @Async} 的，返回时向量还没写完就报「完成」，
 *       于是召回是 0；</li>
 *   <li><b>向量化失败不自愈</b>：embedding 被打断不改变正文，只靠 hash 比对那批段落永远补不上；</li>
 *   <li><b>把库删空</b>：语料一篇没扫到时，REMOVED 差集的右项是空集，库里既有文档整个变成待删清单。
 *       前五种只是白烧钱或召回不准，<b>这一种是不可逆的</b>。注意这里的取舍是「只硬拦 0 篇」：
 *       扫到了一部分时删除照常做，只把「扫描不完整」报出来 —— 拦宽了第 3 条缺陷就会复活，
 *       而那条恰好是本文件上面已经有用例钉住的。</li>
 * </ol>
 * 每一条都有一个用例钉住，且断言的是<b>行为</b>（抛哪个错、调了哪个方法、传了什么参数），
 * 不是内部实现细节。
 *
 * @author 走进新时代
 */
public class AgentDocSyncServiceImplTest extends BaseMockitoUnitTest {

    private static final Long KNOWLEDGE_ID = 7L;
    private static final Long EMBEDDING_MODEL_ID = 21L;
    private static final String BASE_URL = "https://doc.enlev.cn";

    /** 原文（注意结尾那个换行：清洗会把它去掉，这正是「hash 必须算清洗后正文」的由头） */
    private static final String USER_MD_RAW = "# 用户管理\n\n正文。\n";
    /** 清洗后的正文 —— 与 {@link AgentDocMarkdownSanitizer} 的输出逐字一致 */
    private static final String USER_MD_SANITIZED = "# 用户管理\n\n正文。";
    private static final String USER_MD_PATH = "guide/01-system/user.md";
    private static final String USER_MD_URL = BASE_URL + "/guide/01-system/user";

    /** 第二篇语料。REEMBED 的用例必须有「向量灌全了的」那一篇做对照，否则断言是空的 */
    private static final String ROLE_MD_RAW = "# 角色管理\n\n正文。\n";
    private static final String ROLE_MD_SANITIZED = "# 角色管理\n\n正文。";
    private static final String ROLE_MD_PATH = "guide/01-system/role.md";

    @Mock
    private AiRagApi ragApi;
    @Mock
    private AiModelApi modelApi;
    @Spy
    private AgentDocProperties docProperties = new AgentDocProperties();

    @InjectMocks
    private AgentDocSyncServiceImpl docSyncService;

    @TempDir
    private Path tempRoot;

    @BeforeEach
    public void setUp() {
        docProperties.setRootPath(tempRoot.toString());
        // 收敛阈值缩到毫秒级：生产默认是 2s/3 轮/5 分钟，等不起也没必要等（见那三个字段的注释）
        docSyncService.settlePollIntervalMs = 1L;
        docSyncService.settleStableRounds = 1;
        docSyncService.settleTimeoutMs = 200L;
    }

    // ==================== 缺陷形态一：空转 ====================

    @Test
    @DisplayName("root-path 没配 → 抛错，而不是返回「同步完成、0 篇」")
    public void testMissingRootPathThrows() {
        // 🔴 第一版靠标记目录逐级向上探测，探测必然落空且不报错 ⟹ 知识库永久空转，
        //    而接口返回 200、计数全是 0，与「跑成功了、只是恰好没文档」在界面上无法区分。
        //    这就是本平台头号缺陷「配了但不生效且无提示」的形状，故必须抛错。
        docProperties.setRootPath(null);
        assertServiceException(() -> docSyncService.planSync(), AGENT_DOC_ROOT_PATH_MISSING);

        docProperties.setRootPath("   ");
        assertServiceException(() -> docSyncService.planSync(), AGENT_DOC_ROOT_PATH_MISSING);
    }

    @Test
    @DisplayName("root-path 指向不存在的目录 → 抛错并把路径带出来（区分「忘了配」与「配错了」）")
    public void testInvalidRootPathThrows() {
        Path missing = tempRoot.resolve("不存在的目录");
        docProperties.setRootPath(missing.toString());
        // 🔴 这里不用 assertServiceException：它连消息一起逐字比，而消息里带的是
        //    toAbsolutePath().normalize() 之后的路径 —— 在 Windows 上与 tempRoot 的字面形态
        //    是否逐字相同取决于 JUnit 怎么造临时目录，那种断言会在换机器时莫名变红。
        //    「抛哪个错」才是要守的行为，路径出现在消息里由下面这条 contains 守。
        ServiceException ex = assertThrows(ServiceException.class, () -> docSyncService.planSync());
        assertEquals(AGENT_DOC_ROOT_PATH_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("不存在的目录"), "消息要带上是哪个路径，否则还得去翻配置");
    }

    @Test
    @DisplayName("excludePatterns 生效：develop/04-upstream 是上游芋道的说明，不该灌进本平台的手册库")
    public void testExcludePatternsApplied() throws IOException {
        writeDoc(USER_MD_PATH, USER_MD_RAW);
        writeDoc("develop/03-agent/overview.md", "# Agent 模块\n\n自研。");
        writeDoc("develop/04-upstream/ai.md", "# 上游 AI 模块\n\n芋道自带。");

        DocSyncPlan plan = docSyncService.planSync();

        List<String> paths = plan.getDocuments().stream().map(DocItem::getPath).toList();
        assertTrue(paths.contains(USER_MD_PATH), "guide 下的要扫到");
        assertTrue(paths.contains("develop/03-agent/overview.md"), "develop 下非排除目录的要扫到");
        assertFalse(paths.contains("develop/04-upstream/ai.md"),
                "🔴 上游说明灌进去，AI 会拿「上游怎么做」回答「本平台怎么用」");
        assertEquals(2, plan.getDocuments().size());
    }

    // ==================== 缺陷形态二：hash 口径 ====================

    @Test
    @DisplayName("hash 算的是【清洗后】正文：口径一致才算 UNCHANGED，存原文 hash 的会被判成 CHANGED")
    public void testHashIsOverSanitizedContent() throws IOException {
        writeDoc(USER_MD_PATH, USER_MD_RAW);
        writeDoc("guide/01-system/role.md", "# 角色管理\n\n正文。\n");
        when(ragApi.findKnowledgeIdByName(docProperties.getKnowledgeName())).thenReturn(KNOWLEDGE_ID);
        when(ragApi.getDocumentContentHashes(KNOWLEDGE_ID)).thenReturn(Map.of(
                // 库里存的是清洗后的正文 ⟹ 这一篇该判 UNCHANGED
                USER_MD_PATH, DigestUtil.sha256Hex(USER_MD_SANITIZED),
                // 🔴 反面：这一篇存的是【原文】的 hash（结尾多一个 \n）。若同步侧也算原文，
                //    它会同样被判 UNCHANGED，那这条对照就抓不到口径漂移
                "guide/01-system/role.md", DigestUtil.sha256Hex("# 角色管理\n\n正文。\n")));

        DocSyncPlan plan = docSyncService.planSync();

        assertEquals("UNCHANGED", statusOf(plan, USER_MD_PATH),
                "库里存的就是清洗后的正文，hash 对得上 ⟹ 跳过，不白花 embedding");
        assertEquals("CHANGED", statusOf(plan, "guide/01-system/role.md"),
                "🔴 hash 必须两边同口径（都是清洗后）：原文的 hash 对不上，说明这篇要重灌");
        assertEquals(1, plan.getUnchangedCount());
        assertEquals(1, plan.getChangedCount());
    }

    @Test
    @DisplayName("库还没建出来时全部按新增处理，并把这个事实写进 warnings（不静默）")
    public void testFirstSyncWhenKnowledgeMissing() throws IOException {
        writeDoc(USER_MD_PATH, USER_MD_RAW);
        when(ragApi.findKnowledgeIdByName(docProperties.getKnowledgeName())).thenReturn(null);

        DocSyncPlan plan = docSyncService.planSync();

        assertEquals(1, plan.getAddedCount());
        assertEquals(0, plan.getRemovedCount());
        assertTrue(plan.getWarnings().stream().anyMatch(w -> w.contains("还没建出来")),
                "「库不存在」与「库里没有文档」是两件事，要在计划里说清楚");
        verify(ragApi, never()).getDocumentContentHashes(anyLong());
        verify(ragApi, never()).listUnembeddedDocumentNames(anyLong());
    }

    // ==================== 缺陷形态三：僵尸文档 ====================

    @Test
    @DisplayName("REMOVED 差集：库里有、语料里没有的文档要被标出来（改名与删页都落在这里）")
    public void testRemovedDiff() throws IOException {
        writeDoc(USER_MD_PATH, USER_MD_RAW);
        when(ragApi.findKnowledgeIdByName(docProperties.getKnowledgeName())).thenReturn(KNOWLEDGE_ID);
        when(ragApi.getDocumentContentHashes(KNOWLEDGE_ID)).thenReturn(Map.of(
                USER_MD_PATH, DigestUtil.sha256Hex(USER_MD_SANITIZED),
                // 语料里早就删掉的两篇：它们的段落与向量不会自己消失，还会继续被召回
                "form-design-guide/授权.md", DigestUtil.sha256Hex("旧内容"),
                "permission-guide/概览.md", DigestUtil.sha256Hex("旧内容")));

        DocSyncPlan plan = docSyncService.planSync();

        assertEquals(2, plan.getRemovedCount());
        assertEquals("REMOVED", statusOf(plan, "form-design-guide/授权.md"));
        assertEquals("REMOVED", statusOf(plan, "permission-guide/概览.md"));
        assertEquals(3, plan.getDocuments().size(), "计划里要同时看得到扫到的与要删的");
        // 🔴 只增不删的后果：AI 拿着一篇已经不存在的手册页言之凿凿，给的链接点开是 404
        assertEquals(0, plan.getAddedCount());
        assertEquals(1, plan.getUnchangedCount());
    }

    @Test
    @DisplayName("doSync 真的把 REMOVED 删掉（走 Service 删，连带清段落与向量）")
    public void testDoSyncRemovesZombies() throws IOException {
        writeDoc(USER_MD_PATH, USER_MD_RAW);
        stubKnowledgeWith(Map.of(
                USER_MD_PATH, DigestUtil.sha256Hex(USER_MD_SANITIZED),
                "form-design-guide/授权.md", DigestUtil.sha256Hex("旧内容")));
        when(ragApi.deleteDocumentByName(KNOWLEDGE_ID, "form-design-guide/授权.md")).thenReturn(1);
        stubCounts(3, 3);

        DocSyncResult result = docSyncService.doSync();

        verify(ragApi).deleteDocumentByName(KNOWLEDGE_ID, "form-design-guide/授权.md");
        assertEquals(1, result.getRemovedCount());
        assertEquals(1, result.getSkippedCount(), "没变的那篇不该被重灌");
        assertEquals(0, result.getAddedCount());
        verify(ragApi, never()).createDocumentByContent(anyLong(), anyString(), anyString(), anyString(), anyInt());
    }

    // ==================== 缺陷形态四：假完成 ====================

    @Test
    @DisplayName("向量化还没写完就超时 → settled=false 且说清差多少条（绝不说成「完成」）")
    public void testNotSettledWhenEmbeddingLags() throws IOException {
        writeDoc(USER_MD_PATH, USER_MD_RAW);
        stubKnowledgeWith(Map.of());
        when(ragApi.deleteDocumentByName(anyLong(), anyString())).thenReturn(0);
        // 🔴 段落行已经写进 MySQL 了，向量还在 SimpleVectorStore 那边排队 ——
        //    这一刻「库里有 10 段」是真的，「能召回」是假的。只看段落数就会把它报成完成
        stubCounts(10, 3);

        DocSyncResult result = docSyncService.doSync();

        assertFalse(result.isSettled());
        assertNotNull(result.getSettleNote());
        assertTrue(result.getSettleNote().contains("7"), "要说清还差几条召回不到");
        assertEquals(10, result.getSegmentCount());
        assertEquals(3, result.getEmbeddedSegmentCount());
        assertEquals(1, result.getAddedCount(), "文档确实灌进去了，失败的是「等收敛」这一步，不是同步本身");
    }

    @Test
    @DisplayName("段落数与已向量化数相等且稳定 → settled=true")
    public void testSettledWhenCountsConverge() throws IOException {
        writeDoc(USER_MD_PATH, USER_MD_RAW);
        stubKnowledgeWith(Map.of());
        when(ragApi.deleteDocumentByName(anyLong(), anyString())).thenReturn(0);
        stubCounts(8, 8);

        DocSyncResult result = docSyncService.doSync();

        assertTrue(result.isSettled());
        assertEquals(8, result.getSegmentCount());
        assertEquals(8, result.getEmbeddedSegmentCount());
    }

    // ==================== 缺陷形态五：向量化失败不自愈 ====================

    /**
     * 🔴 这一组钉的是本次改造里唯一一条「靠跑一次真实同步才暴露出来」的缺陷。
     *
     * <p>实测形状：108 篇灌进去，embedding 配额在 322/514 段处被限速打断（HTTP 429）。
     * 之后每次重跑 {@code doc-sync} 都报「全 UNCHANGED」，那 192 段 {@code vector_id} 为空的
     * 段落静静躺在库里、永远召回不到 —— 因为增量判据只有 hash，而 embedding 失败不改变正文。
     * 所有计数都是绿的，只有召回结果知道。
     */
    @Test
    @DisplayName("hash 没变但向量没灌全 → REEMBED（与 UNCHANGED 分开报），灌全了的仍判 UNCHANGED")
    public void testReembedWhenHashUnchangedButVectorMissing() throws IOException {
        writeDoc(USER_MD_PATH, USER_MD_RAW);
        writeDoc(ROLE_MD_PATH, ROLE_MD_RAW);
        // planSync 用不到向量模型与建库，故不用 stubKnowledgeWith —— STRICT_STUBS 会把多余桩判成失败
        when(ragApi.findKnowledgeIdByName(docProperties.getKnowledgeName())).thenReturn(KNOWLEDGE_ID);
        // 两篇的 hash 都对得上 ⟹ 只看 hash 的话两篇都会被判 UNCHANGED、都跳过
        when(ragApi.getDocumentContentHashes(KNOWLEDGE_ID)).thenReturn(Map.of(
                USER_MD_PATH, DigestUtil.sha256Hex(USER_MD_SANITIZED),
                ROLE_MD_PATH, DigestUtil.sha256Hex(ROLE_MD_SANITIZED)));
        when(ragApi.listUnembeddedDocumentNames(KNOWLEDGE_ID)).thenReturn(Set.of(USER_MD_PATH));

        DocSyncPlan plan = docSyncService.planSync();

        assertEquals("REEMBED", statusOf(plan, USER_MD_PATH),
                "🔴 正文没变不等于灌好了：向量缺的那篇必须重灌，否则它永远召回不到");
        assertEquals("UNCHANGED", statusOf(plan, ROLE_MD_PATH),
                "对照：向量灌全了的仍要跳过，否则每次同步都在全量重灌、白烧 embedding");
        assertEquals(1, plan.getReembedCount());
        assertEquals(1, plan.getUnchangedCount());
        assertEquals(0, plan.getChangedCount(), "语料一个字没改，不能报成「内容变了」");
        assertEquals(0, plan.getAddedCount());
    }

    @Test
    @DisplayName("doSync 真的把 REEMBED 那篇先删后建（只补向量而不删旧记录，会留下重复向量）")
    public void testDoSyncRecreatesUnembeddedDocument() throws IOException {
        writeDoc(USER_MD_PATH, USER_MD_RAW);
        stubKnowledgeWith(Map.of(USER_MD_PATH, DigestUtil.sha256Hex(USER_MD_SANITIZED)));
        when(ragApi.listUnembeddedDocumentNames(KNOWLEDGE_ID)).thenReturn(Set.of(USER_MD_PATH));
        when(ragApi.deleteDocumentByName(KNOWLEDGE_ID, USER_MD_PATH)).thenReturn(1);
        stubCounts(1, 1);

        DocSyncResult result = docSyncService.doSync();

        InOrder inOrder = inOrder(ragApi);
        inOrder.verify(ragApi).deleteDocumentByName(KNOWLEDGE_ID, USER_MD_PATH);
        inOrder.verify(ragApi).createDocumentByContent(KNOWLEDGE_ID, USER_MD_PATH, USER_MD_URL,
                USER_MD_SANITIZED, docProperties.getSegmentMaxTokens());
        assertEquals(1, result.getUpdatedCount(), "重灌的是已有文档，计入更新而不是新增");
        assertEquals(0, result.getSkippedCount(), "🔴 绝不能因为它 hash 没变就跳过");
        assertEquals(0, result.getAddedCount());
    }

    @Test
    @DisplayName("向量灌全之后重跑 → 全 UNCHANGED、REEMBED 归零（同步真的可重复跑）")
    public void testSecondRunIsIdempotentAfterEmbeddingCompletes() throws IOException {
        writeDoc(USER_MD_PATH, USER_MD_RAW);
        stubKnowledgeWith(Map.of(USER_MD_PATH, DigestUtil.sha256Hex(USER_MD_SANITIZED)));
        when(ragApi.listUnembeddedDocumentNames(KNOWLEDGE_ID)).thenReturn(Set.of());
        stubCounts(1, 1);

        DocSyncResult result = docSyncService.doSync();

        assertEquals(1, result.getSkippedCount());
        assertEquals(0, result.getAddedCount());
        assertEquals(0, result.getUpdatedCount());
        assertEquals(0, result.getRemovedCount());
        assertTrue(result.isSettled());
        verify(ragApi, never()).createDocumentByContent(anyLong(), anyString(), anyString(), anyString(), anyInt());
    }

    // ==================== 灌库时到底传了什么 ====================

    @Test
    @DisplayName("灌库传的是【清洗后正文 + 公开 URL + 配置的分片大小】，且变更的先删后建")
    public void testDoSyncPassesSanitizedContentAndPublicUrl() throws IOException {
        writeDoc(USER_MD_PATH, "---\ntitle: 用户管理\n---\n\n# 用户管理\n\n![抽屉](/x.png)\n\n正文。\n");
        stubKnowledgeWith(Map.of(USER_MD_PATH, "过时的 hash"));
        when(ragApi.deleteDocumentByName(KNOWLEDGE_ID, USER_MD_PATH)).thenReturn(1);
        stubCounts(6, 6);

        docSyncService.doSync();

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(ragApi).createDocumentByContent(eq(KNOWLEDGE_ID), eq(USER_MD_PATH), eq(USER_MD_URL),
                content.capture(), eq(800));
        // 🔴 frontmatter 与图片语法必须已经被洗掉：上游切分器一个字都不改，
        //    脏内容会原样进 chunk、原样进 embedding
        assertFalse(content.getValue().contains("title: 用户管理"), "frontmatter 不能进语料");
        assertFalse(content.getValue().contains("/x.png"), "图片地址不能进语料");
        assertTrue(content.getValue().contains("# 用户管理"), "标题必须留着 —— 它是唯一的结构信号");
        assertTrue(content.getValue().contains("（图：抽屉）"), "图片的 alt 是有价值的界面词，要留");

        // 先删后建：只覆盖记录会在向量库里留下无主的旧向量，那些内容永远召回得到、却已经不存在了
        InOrder order = inOrder(ragApi);
        order.verify(ragApi).deleteDocumentByName(KNOWLEDGE_ID, USER_MD_PATH);
        order.verify(ragApi).createDocumentByContent(eq(KNOWLEDGE_ID), eq(USER_MD_PATH), eq(USER_MD_URL),
                anyString(), eq(800));
    }

    @Test
    @DisplayName("单篇失败不中断：坏的那篇进 failures，其余照常灌完")
    public void testSingleFailureDoesNotAbort() throws IOException {
        writeDoc(USER_MD_PATH, USER_MD_RAW);
        writeDoc("guide/01-system/role.md", "# 角色管理\n\n正文。");
        stubKnowledgeWith(Map.of());
        when(ragApi.deleteDocumentByName(anyLong(), anyString())).thenReturn(0);
        doThrow(new RuntimeException("向量库连不上"))
                .when(ragApi).deleteDocumentByName(KNOWLEDGE_ID, USER_MD_PATH);
        stubCounts(4, 4);

        DocSyncResult result = docSyncService.doSync();

        assertEquals(1, result.getFailures().size());
        assertTrue(result.getFailures().get(0).contains(USER_MD_PATH), "失败清单要能定位到是哪一篇");
        assertTrue(result.getFailures().get(0).contains("向量库连不上"), "要把原因带上，不是只说「失败了」");
        assertEquals(1, result.getAddedCount(), "🔴 另一篇照常灌完：一篇坏文档不该让另外 107 篇灌不进去");
    }

    // ==================== 缺陷形态六：语料扫不到时把库删空 ====================

    /**
     * 🔴 这一组钉的是<b>唯一一条会不可逆毁数据</b>的缺陷，前五种都只是「白花embedding」或「召回不准」。
     *
     * <p>实测形状：三个 profile 的 {@code root-path} 都配成了 {@code /}。它<b>是</b>目录，
     * 能过 {@code resolveRoot} 的校验；但底下没有 {@code guide}/{@code develop}，
     * 于是扫描结果为空，而 REMOVED 差集是「库里的文档名 − 扫到的文档名」——
     * 右项为空集时，左项<b>整个</b>变成待删清单。点一次 doc-sync 就是把知识库删空，
     * 且向量库的写入不在事务里，删掉补不回来。
     */
    @Test
    @DisplayName("root-path 指向空目录 → doSync 中止，库里既有的文档一篇都不删，也不建空知识库")
    public void testDoSyncAbortsWhenCorpusEmpty() {
        // tempRoot 由 @TempDir 造出来、本身存在但没有任何子目录，正好是 `/` 的形状
        when(ragApi.findKnowledgeIdByName(docProperties.getKnowledgeName())).thenReturn(KNOWLEDGE_ID);
        when(ragApi.getDocumentContentHashes(KNOWLEDGE_ID)).thenReturn(Map.of(
                USER_MD_PATH, DigestUtil.sha256Hex(USER_MD_SANITIZED),
                "develop/00-intro/setup.md", DigestUtil.sha256Hex("旧内容")));

        // 🔴 下面这一串刻意用 lenient 桩满「守卫不存在时会走到的每一步」。
        //    少了它们，doSync 会在第 1 步「没有可用的向量模型」就提前返回 —— 那样一来
        //    用例验的是「模型缺失时不删」，而不是「语料扫不到时不删」，看着绿其实白测
        //    （本次改造的反向验证就是这么把它抓出来的）。守卫生效时它们一次都不该被调用，
        //    故必须 lenient，否则 STRICT_STUBS 会把它们判成多余桩。
        lenient().when(modelApi.selectUsableEmbeddingModelId(null)).thenReturn(EMBEDDING_MODEL_ID);
        lenient().when(ragApi.getOrCreateKnowledge(anyString(), anyString(), eq(EMBEDDING_MODEL_ID),
                anyInt(), anyDouble())).thenReturn(KNOWLEDGE_ID);
        lenient().when(ragApi.deleteDocumentByName(anyLong(), anyString())).thenReturn(1);
        lenient().when(ragApi.countSegments(KNOWLEDGE_ID)).thenReturn(0);
        lenient().when(ragApi.countEmbeddedSegments(KNOWLEDGE_ID)).thenReturn(0);

        DocSyncPlan plan = docSyncService.planSync();
        assertEquals(2, plan.getSkippedDirectoryCount(),
                "guide 与 develop 都不存在，两个都得记成「跳过」——这是差集不可信的唯一判据");
        assertEquals(2, plan.getRemovedCount(),
                "计划阶段如实报「2 篇待删」是对的：它是诊断接口，要让人在执行前就看到");

        DocSyncResult result = docSyncService.doSync();

        verify(ragApi, never()).deleteDocumentByName(anyLong(), anyString());
        assertEquals(0, result.getRemovedCount(), "没有守卫的话这里会是 2 —— 整个知识库被删空");
        // 🔴 建库与选模型也不该发生：既然一篇都灌不进去，就别留下一个空知识库让人以为同步成功了。
        //    用 any() 而不是 anyLong()/anyInt()：never() 的语义是「一次都没调过」，
        //    而 anyLong() 不匹配 null，万一将来传了 null 进来，这条断言会假绿
        verify(ragApi, never()).getOrCreateKnowledge(any(), any(), any(), any(), any());
        verify(modelApi, never()).selectUsableEmbeddingModelId(any());
        assertEquals(1, result.getFailures().size());
        assertTrue(result.getFailures().get(0).contains("root-path"),
                "要说清去改哪个配置项，不是只说「失败了」");
        assertTrue(result.getFailures().get(0).contains("2"),
                "要带上「拦下了几篇的删除」，否则看不出这次守卫挡掉的是多大的动作");
    }

    @Test
    @DisplayName("只缺一个子目录 → 删除照常执行，但「本次扫描不完整」必须出现在结果里")
    public void testDoSyncReportsIncompleteScanWhenDirectoryMissing() throws IOException {
        // 语料里只有 guide，develop 整个目录不存在（例如 root-path 指到了只 checkout 一半的仓库）。
        // 🔴 这里刻意**不**拦删除：拦了就等于「真的删掉整个目录」永远传播不到库里，
        //    上面那条僵尸文档的缺陷原地复活 —— 而它比误删更难发现，因为它是慢慢累积的。
        //    要守的是另一件事：DocSyncResult 不带 warnings，只在 planSync 里报的话，
        //    直接调 doc-sync 的人根本看不到「扫描不完整」，只会看到一堆绿色的计数。
        writeDoc(USER_MD_PATH, USER_MD_RAW);
        stubKnowledgeWith(Map.of(
                USER_MD_PATH, DigestUtil.sha256Hex(USER_MD_SANITIZED),
                "develop/00-intro/setup.md", DigestUtil.sha256Hex("旧内容")));
        when(ragApi.deleteDocumentByName(KNOWLEDGE_ID, "develop/00-intro/setup.md")).thenReturn(1);
        stubCounts(5, 5);

        DocSyncPlan plan = docSyncService.planSync();
        assertEquals(1, plan.getSkippedDirectoryCount(), "develop 不存在，要被记成跳过");

        DocSyncResult result = docSyncService.doSync();

        verify(ragApi).deleteDocumentByName(KNOWLEDGE_ID, "develop/00-intro/setup.md");
        assertEquals(1, result.getRemovedCount());
        assertTrue(result.getFailures().stream().anyMatch(f -> f.contains("扫描不完整")),
                "🔴 删了就必须说出「这次扫描是不完整的」，否则运维无从判断这几篇该不该删");
    }

    // ==================== 诊断状态 ====================

    @Test
    @DisplayName("getSyncStatus：本进程没同步过时如实说，同时把库里的实况现查出来")
    public void testSyncStatusBeforeAnySync() {
        when(ragApi.findKnowledgeIdByName(docProperties.getKnowledgeName())).thenReturn(KNOWLEDGE_ID);
        when(ragApi.listDocumentNames(KNOWLEDGE_ID)).thenReturn(List.of("a.md", "b.md"));
        stubCounts(9, 4);

        var status = docSyncService.getSyncStatus();

        assertFalse(status.isSyncedInThisProcess(),
                "🔴 计数全是 0 时不能让人误读成「同步过且啥也没干」");
        assertEquals(KNOWLEDGE_ID, status.getKnowledgeId());
        assertEquals(2, status.getDocumentCount());
        assertEquals(9, status.getSegmentCount());
        assertEquals(4, status.getEmbeddedSegmentCount());
        assertEquals(BASE_URL, status.getBaseUrl());
        assertNotNull(status.getVectorStoreDir(), "向量落盘目录要能查到 —— 段落行在 MySQL、向量在这个文件里，两者可以不一致");
    }

    // ==================== 辅助 ====================

    private void writeDoc(String relPath, String content) throws IOException {
        Path file = tempRoot.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** 知识库已存在 + 一个探得通的向量模型 + 库里的「文档名 → hash」 */
    private void stubKnowledgeWith(Map<String, String> hashes) {
        when(ragApi.findKnowledgeIdByName(docProperties.getKnowledgeName())).thenReturn(KNOWLEDGE_ID);
        when(ragApi.getDocumentContentHashes(KNOWLEDGE_ID)).thenReturn(hashes);
        when(modelApi.selectUsableEmbeddingModelId(null)).thenReturn(EMBEDDING_MODEL_ID);
        when(ragApi.getOrCreateKnowledge(anyString(), anyString(), eq(EMBEDDING_MODEL_ID),
                anyInt(), anyDouble())).thenReturn(KNOWLEDGE_ID);
    }

    /** 段落数与已向量化段落数（收敛判据的两个输入） */
    private void stubCounts(int segments, int embedded) {
        when(ragApi.countSegments(KNOWLEDGE_ID)).thenReturn(segments);
        when(ragApi.countEmbeddedSegments(KNOWLEDGE_ID)).thenReturn(embedded);
    }

    private static String statusOf(DocSyncPlan plan, String path) {
        return plan.getDocuments().stream()
                .filter(d -> path.equals(d.getPath()))
                .map(DocItem::getStatus)
                .findFirst()
                .orElse(null);
    }

}
