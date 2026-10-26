package cn.enlev.ainb.module.ai.service.knowledge;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.ai.dal.dataobject.knowledge.AiKnowledgeDO;
import cn.enlev.ainb.module.ai.dal.dataobject.knowledge.AiKnowledgeSegmentDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import cn.enlev.ainb.module.ai.dal.mysql.knowledge.AiKnowledgeMapper;
import cn.enlev.ainb.module.ai.dal.mysql.knowledge.AiKnowledgeSegmentMapper;
import cn.enlev.ainb.module.ai.service.model.AiEmbeddingFailoverService;
import cn.enlev.ainb.module.ai.service.model.AiModelService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiKnowledgeSegmentServiceImpl} 的<b>向量模型迁移</b>单测
 *
 * <p>只钉一件事：迁移时旧模型的向量必须<b>真被删掉</b>，而且必须从<b>旧</b>模型的向量库里删。
 *
 * <p>为什么单独开一个类而不是并进选链的单测：这里的故障形态是<b>静默的资源泄漏</b>，
 * 不抛异常、不写日志、召回结果也依然正确（命中的 vector_id 在库里已无对应行，会被 join 掉），
 * 唯一症状是 {@code SimpleVectorStore} 的那份 JSON 整份载入内存、每迁移一次就永久多出一份全库向量。
 * 这种 BUG 端到端测不出来（实测时新建的一次性知识库在迁移那一刻还没有任何段落，purge 数是 0），
 * 只能靠单测把「用哪个模型编号去解析向量库」这一步钉死。
 *
 * <p>🔴 最容易写错的地方：{@code getVectorStoreById(Long)} 会<b>重新查库</b>，
 * 而迁移的 UPDATE 已经先跑过了，于是拿到的是<b>新</b>模型的库 ——
 * 拿新库去删旧 id，一条也删不到，还一声不响。下面第一条测试就是守这个。
 */
public class AiKnowledgeSegmentServiceImplMigrationTest extends BaseMockitoUnitTest {

    private static final long KNOWLEDGE_ID = 9L;
    /** 迁移前钉住的模型，实测是额度已耗尽的那个 */
    private static final long OLD_MODEL_ID = 65L;
    private static final String OLD_MODEL_NAME = "qwen3.7-text-embedding";
    /** 迁移后落到的模型 */
    private static final long NEW_MODEL_ID = 102L;
    private static final String NEW_MODEL_NAME = "text-embedding-v4";

    @Mock
    private AiKnowledgeSegmentMapper segmentMapper;
    @Mock
    private AiKnowledgeMapper knowledgeMapper;
    @Mock
    private AiKnowledgeService knowledgeService;
    @Mock
    private AiModelService modelService;
    @Mock
    private AiEmbeddingFailoverService embeddingFailoverService;
    @Mock
    private AiKnowledgeSegmentService self;

    @InjectMocks
    private AiKnowledgeSegmentServiceImpl segmentService;

    private final VectorStore staleStore = mock(VectorStore.class);
    private final VectorStore freshStore = mock(VectorStore.class);

    // ========== ① 从【旧】模型的库里删，且在清 vector_id 之前删 ==========

    @Test
    void testMigrationPurgesOldVectorsFromTheOldModelStoreBeforeClearingIds() {
        stubMigration();
        when(segmentMapper.selectListByKnowledgeId(KNOWLEDGE_ID)).thenReturn(List.of(
                segment(1L, "v-old-1"), segment(2L, "v-old-2")));
        when(modelService.getOrCreateVectorStore(eq(OLD_MODEL_ID), anyMap())).thenReturn(staleStore);
        when(modelService.getOrCreateVectorStore(eq(NEW_MODEL_ID), anyMap())).thenReturn(freshStore);
        // 让重嵌立刻短路：本测试只关心迁移那一段，不想再 stub 写入侧的一整套
        when(segmentMapper.selectListByKnowledgeIdAndStatus(KNOWLEDGE_ID, CommonStatusEnum.ENABLE.getStatus()))
                .thenReturn(Collections.emptyList());

        segmentService.reindexKnowledgeSegmentByKnowledgeId(KNOWLEDGE_ID);

        // 🔴 核心断言：解析旧向量库用的是【旧】模型编号。
        //    写成 getVectorStoreById(knowledgeId) 的话这里只会看到 102，65 一次都不出现
        verify(modelService).getOrCreateVectorStore(eq(OLD_MODEL_ID), anyMap());
        verify(staleStore).delete(List.of("v-old-1", "v-old-2"));
        // 顺序也是判据：先清 vector_id 再删，就再也查不出该删哪些了
        InOrder order = inOrder(staleStore, segmentMapper);
        order.verify(staleStore).delete(anyList());
        order.verify(segmentMapper).clearVectorIdByKnowledgeId(KNOWLEDGE_ID);
    }

    // ========== ② 删不掉也不能把迁移打断 ==========

    @Test
    void testPurgeFailureDoesNotAbortMigration() {
        stubMigration();
        when(segmentMapper.selectListByKnowledgeId(KNOWLEDGE_ID))
                .thenReturn(List.of(segment(1L, "v-old-1")));
        when(modelService.getOrCreateVectorStore(eq(OLD_MODEL_ID), anyMap())).thenReturn(staleStore);
        when(modelService.getOrCreateVectorStore(eq(NEW_MODEL_ID), anyMap())).thenReturn(freshStore);
        when(segmentMapper.selectListByKnowledgeIdAndStatus(KNOWLEDGE_ID, CommonStatusEnum.ENABLE.getStatus()))
                .thenReturn(Collections.emptyList());
        doThrow(new RuntimeException("向量文件被占用")).when(staleStore).delete(anyList());

        // 旧向量残留只是浪费磁盘，不值得为它把整个索引流程打断
        assertDoesNotThrow(() -> segmentService.reindexKnowledgeSegmentByKnowledgeId(KNOWLEDGE_ID));
        verify(segmentMapper).clearVectorIdByKnowledgeId(KNOWLEDGE_ID);
        verify(knowledgeMapper).updateEmbeddingModelIfMatch(
                KNOWLEDGE_ID, OLD_MODEL_ID, NEW_MODEL_ID, NEW_MODEL_NAME);
    }

    // ========== ③ 没嵌过的段落不该被提交删除 ==========

    @Test
    void testBlankAndSentinelVectorIdsAreNotSubmitted() {
        stubMigration();
        // VECTOR_ID_EMPTY 是空串哨兵而不是 NULL，convertList 只过滤 null，滤不干净就会拿空串去删
        when(segmentMapper.selectListByKnowledgeId(KNOWLEDGE_ID)).thenReturn(List.of(
                segment(1L, AiKnowledgeSegmentDO.VECTOR_ID_EMPTY), segment(2L, null)));
        when(modelService.getOrCreateVectorStore(eq(NEW_MODEL_ID), anyMap())).thenReturn(freshStore);
        when(segmentMapper.selectListByKnowledgeIdAndStatus(KNOWLEDGE_ID, CommonStatusEnum.ENABLE.getStatus()))
                .thenReturn(Collections.emptyList());

        segmentService.reindexKnowledgeSegmentByKnowledgeId(KNOWLEDGE_ID);

        // 一个有效 id 都没有 ⟹ 连旧向量库都不该去解析（省一次文件载入）
        verify(modelService, never()).getOrCreateVectorStore(eq(OLD_MODEL_ID), anyMap());
        verify(segmentMapper).clearVectorIdByKnowledgeId(KNOWLEDGE_ID);
    }

    // ========== 夹具 ==========

    /**
     * 把「钉在旧模型上的知识库 + 选链给出新模型 + 乐观锁抢到」这三件事 stub 好
     *
     * <p>🔴 {@code validateKnowledgeExists} 必须<b>先返回旧行、之后返回已迁移的新行</b>，
     * 不能图省事一直返回同一个对象。真实时序是：reindex 开头读到旧的（钉着 65）⟹
     * 迁移的 UPDATE 落库 ⟹ 此后任何一次重查都读到新的（钉着 102）。
     * 一直返回旧对象的话，「误用 {@code getVectorStoreById(knowledgeId)} 去解析旧向量库」
     * 这个 BUG 在测试里根本显不出来 —— 它会重查、拿到的还是旧对象、于是恰好删对了库。
     * 本测试第一版就是这么假绿的，把桩改成两次不同返回之后变异体才被抓到。
     */
    private void stubMigration() {
        AiKnowledgeDO knowledge = new AiKnowledgeDO();
        knowledge.setId(KNOWLEDGE_ID);
        knowledge.setEmbeddingModelId(OLD_MODEL_ID);
        knowledge.setEmbeddingModel(OLD_MODEL_NAME);
        AiKnowledgeDO migrated = new AiKnowledgeDO();
        migrated.setId(KNOWLEDGE_ID);
        migrated.setEmbeddingModelId(NEW_MODEL_ID);
        migrated.setEmbeddingModel(NEW_MODEL_NAME);
        when(knowledgeService.validateKnowledgeExists(KNOWLEDGE_ID)).thenReturn(knowledge, migrated);
        when(embeddingFailoverService.selectUsableEmbeddingModel(OLD_MODEL_ID)).thenReturn(newModel());
        when(knowledgeMapper.updateEmbeddingModelIfMatch(
                KNOWLEDGE_ID, OLD_MODEL_ID, NEW_MODEL_ID, NEW_MODEL_NAME)).thenReturn(1);
        when(segmentMapper.clearVectorIdByKnowledgeId(KNOWLEDGE_ID)).thenReturn(2);
    }

    private static AiModelDO newModel() {
        AiModelDO model = new AiModelDO();
        model.setId(NEW_MODEL_ID);
        model.setModel(NEW_MODEL_NAME);
        return model;
    }

    private static AiKnowledgeSegmentDO segment(Long id, String vectorId) {
        AiKnowledgeSegmentDO segment = new AiKnowledgeSegmentDO();
        segment.setId(id);
        segment.setKnowledgeId(KNOWLEDGE_ID);
        segment.setVectorId(vectorId);
        segment.setContent("段落 " + id);
        return segment;
    }

}
