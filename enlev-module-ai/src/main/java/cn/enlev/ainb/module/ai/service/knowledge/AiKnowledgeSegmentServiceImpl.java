package cn.enlev.ainb.module.ai.service.knowledge;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.ai.controller.admin.knowledge.vo.segment.AiKnowledgeSegmentPageReqVO;
import cn.enlev.ainb.module.ai.controller.admin.knowledge.vo.segment.AiKnowledgeSegmentProcessRespVO;
import cn.enlev.ainb.module.ai.controller.admin.knowledge.vo.segment.AiKnowledgeSegmentSaveReqVO;
import cn.enlev.ainb.module.ai.controller.admin.knowledge.vo.segment.AiKnowledgeSegmentUpdateStatusReqVO;
import cn.enlev.ainb.module.ai.dal.dataobject.knowledge.AiKnowledgeDO;
import cn.enlev.ainb.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import cn.enlev.ainb.module.ai.dal.dataobject.knowledge.AiKnowledgeSegmentDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import cn.enlev.ainb.module.ai.dal.mysql.knowledge.AiKnowledgeMapper;
import cn.enlev.ainb.module.ai.dal.mysql.knowledge.AiKnowledgeSegmentMapper;
import cn.enlev.ainb.module.ai.enums.AiDocumentSplitStrategyEnum;
import cn.enlev.ainb.module.ai.service.knowledge.bo.AiKnowledgeSegmentSearchReqBO;
import cn.enlev.ainb.module.ai.service.knowledge.bo.AiKnowledgeSegmentSearchRespBO;
import cn.enlev.ainb.module.ai.service.knowledge.splitter.MarkdownQaSplitter;
import cn.enlev.ainb.module.ai.service.knowledge.splitter.SemanticTextSplitter;
import cn.enlev.ainb.module.ai.service.model.AiEmbeddingFailoverService;
import cn.enlev.ainb.module.ai.service.model.AiModelService;
import cn.enlev.ainb.module.ai.util.AiUtils;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.*;
import static org.springframework.ai.vectorstore.SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL;

/**
 * AI 知识库分片 Service 实现类
 *
 * @author xiaoxin
 */
@Service
@Slf4j
public class AiKnowledgeSegmentServiceImpl implements AiKnowledgeSegmentService {

    private static final String VECTOR_STORE_METADATA_KNOWLEDGE_ID = "knowledgeId";
    private static final String VECTOR_STORE_METADATA_DOCUMENT_ID = "documentId";
    private static final String VECTOR_STORE_METADATA_SEGMENT_ID = "segmentId";

    private static final Map<String, Class<?>> VECTOR_STORE_METADATA_TYPES = Map.of(
            VECTOR_STORE_METADATA_KNOWLEDGE_ID, String.class,
            VECTOR_STORE_METADATA_DOCUMENT_ID, String.class,
            VECTOR_STORE_METADATA_SEGMENT_ID, String.class);

    /**
     * Rerank 在向量检索时，检索数量 * 该系数，目的是为了提升 Rerank 的效果
     */
    private static final Integer RERANK_RETRIEVAL_FACTOR = 4;

    @Resource
    private AiKnowledgeSegmentMapper segmentMapper;

    @Resource
    private AiKnowledgeMapper knowledgeMapper;

    @Resource
    private AiKnowledgeService knowledgeService;
    @Resource
    @Lazy // 延迟加载，避免循环依赖
    private AiKnowledgeDocumentService knowledgeDocumentService;
    @Resource
    private AiModelService modelService;
    @Resource
    private AiEmbeddingFailoverService embeddingFailoverService;

    /**
     * 自己的代理，只为让 {@code reindexByKnowledgeIdAsync} 上的 {@code @Async} 真的生效
     *
     * <p>🔴 直接 {@code this.reindexByKnowledgeIdAsync(...)} 是<b>不走代理</b>的，
     * 于是「异步」变成同步：迁移会在灌库那条线程上把整个知识库重嵌完才返回。
     * 而本类的调用方 {@code createKnowledgeSegmentBySplitContent} 自己就跑在 {@code @Async} 线程上，
     * 一次灌库 125 篇文档，同步重嵌会把异步线程池占满。
     * 自注入 + {@code @Lazy} 与本类既有的 {@code knowledgeDocumentService} 同一写法。
     */
    @Resource
    @Lazy
    private AiKnowledgeSegmentService self;

    @Resource
    private TokenCountEstimator tokenCountEstimator;

    @Autowired(required = false) // 由于 spring.ai.model.rerank 配置项，可以关闭 RerankModel 的功能，所以这里只能不强制注入
    private RerankModel rerankModel;

    @Override
    public PageResult<AiKnowledgeSegmentDO> getKnowledgeSegmentPage(AiKnowledgeSegmentPageReqVO pageReqVO) {
        return segmentMapper.selectPage(pageReqVO);
    }

    @Override
    public void createKnowledgeSegmentBySplitContent(Long documentId, String content) {
        // 1. 校验
        AiKnowledgeDocumentDO documentDO = knowledgeDocumentService.validateKnowledgeDocumentExists(documentId);
        AiKnowledgeDO knowledgeDO = knowledgeService.validateKnowledgeExists(documentDO.getKnowledgeId());
        // 🔴 走写入侧解析：知识库钉住的向量模型额度耗尽时，允许迁移到另一个探通的模型。
        //    查询侧（searchDocument）永远用钉住的那个，理由见 resolveWritableVectorStore
        EmbeddingTarget target = resolveWritableVectorStore(knowledgeDO, true);

        // 2. 文档切片（使用自动检测策略）
        List<Document> documentSegments = splitContentByStrategy(content, documentDO.getSegmentMaxTokens(),
                AiDocumentSplitStrategyEnum.AUTO, documentDO.getUrl());

        // 3.1 存储切片
        // 🔴 空文本的切片必须【连着它的 Document 一起】丢掉，不能只在转 DO 的时候返回 null：
        //    convertList 会把 null 过滤掉，于是 segmentDOs 比 documentSegments 短，
        //    而下面按下标配对用的却是 documentSegments 的长度。一旦真切出空片段，两种后果都坏：
        //    ① 空的在末尾 → segmentDOs.get(i) 抛 IndexOutOfBoundsException，
        //       而本方法的调用方是 @Async（异常被吞），接口照样返回 200、进度条照样爬到 100%；
        //    ② 空的在中间 → 整体错位，第 i 段的正文被写进第 j 段的 vector_id，
        //       于是检索命中的向量与返回给用户的正文【不是同一段】，而且一声不响。
        //    ②比①坏得多：它不报错，只会让知识库看起来「召回不准」
        List<Document> validSegments = documentSegments.stream()
                .filter(segment -> StrUtil.isNotEmpty(segment.getText()))
                .toList();
        List<AiKnowledgeSegmentDO> segmentDOs = convertList(validSegments, segment ->
                new AiKnowledgeSegmentDO().setKnowledgeId(documentDO.getKnowledgeId()).setDocumentId(documentId)
                        .setContent(segment.getText()).setContentLength(segment.getText().length())
                        .setVectorId(AiKnowledgeSegmentDO.VECTOR_ID_EMPTY)
                        .setTokens(tokenCountEstimator.estimate(segment.getText()))
                        .setStatus(CommonStatusEnum.ENABLE.getStatus()));
        segmentMapper.insertBatch(segmentDOs);
        // 3.2 切片向量化
        try {
            for (int i = 0; i < validSegments.size(); i++) {
                writeVectorStore(target.store(), segmentDOs.get(i), validSegments.get(i));
            }
        } catch (RuntimeException ex) {
            // 🔴 真实调用的结果必须回传给选链，否则「第一次撞上额度耗尽」之后什么都没被记住，
            //    下一篇文档会原样再撞一次（快路径只认记忆，而记忆只由这里喂）
            embeddingFailoverService.recordFailure(target.model(), ex);
            throw ex;
        }
        embeddingFailoverService.recordSuccess(target.model());
    }

    @Override
    public void updateKnowledgeSegment(AiKnowledgeSegmentSaveReqVO reqVO) {
        // 1. 校验
        AiKnowledgeSegmentDO oldSegment = validateKnowledgeSegmentExists(reqVO.getId());

        // 2. 删除向量
        VectorStore vectorStore = getVectorStoreById(oldSegment.getKnowledgeId());
        deleteVectorStore(vectorStore, oldSegment);

        // 3.1 更新切片
        AiKnowledgeSegmentDO newSegment = BeanUtils.toBean(reqVO, AiKnowledgeSegmentDO.class);
        segmentMapper.updateById(newSegment);
        // 3.2 重新向量化，必须开启状态
        if (CommonStatusEnum.isEnable(oldSegment.getStatus())) {
            newSegment.setKnowledgeId(oldSegment.getKnowledgeId()).setDocumentId(oldSegment.getDocumentId());
            writeVectorStore(vectorStore, newSegment, new Document(newSegment.getContent()));
        }
    }

    @Override
    public void deleteKnowledgeSegment(Long id) {
        // 1. 校验段落存在
        AiKnowledgeSegmentDO segment = validateKnowledgeSegmentExists(id);

        // 2. 删除向量
        VectorStore vectorStore = getVectorStoreById(segment.getKnowledgeId());
        deleteVectorStore(vectorStore, segment);

        // 3. 删除段落记录
        segmentMapper.deleteById(id);
    }

    @Override
    public void deleteKnowledgeSegmentByDocumentId(Long documentId) {
        // 1. 查询需要删除的段落
        List<AiKnowledgeSegmentDO> segments = segmentMapper.selectListByDocumentId(documentId);
        if (CollUtil.isEmpty(segments)) {
            return;
        }

        // 2. 先删向量，再删记录（与 deleteKnowledgeSegment 单条删除保持一致）
        // 若向量删除失败，记录仍在，下次可重试；反过来记录先删则会留下无主向量永远召回到已删内容
        VectorStore vectorStore = getVectorStoreById(segments.get(0).getKnowledgeId());
        vectorStore.delete(convertList(segments, AiKnowledgeSegmentDO::getVectorId));

        // 3. 批量删除段落记录
        segmentMapper.deleteByIds(convertList(segments, AiKnowledgeSegmentDO::getId));
    }

    @Override
    public void updateKnowledgeSegmentStatus(AiKnowledgeSegmentUpdateStatusReqVO reqVO) {
        // 1. 校验
        AiKnowledgeSegmentDO segment = validateKnowledgeSegmentExists(reqVO.getId());

        // 2. 获取知识库向量实例
        VectorStore vectorStore = getVectorStoreById(segment.getKnowledgeId());

        // 3. 更新状态
        segmentMapper.updateById(new AiKnowledgeSegmentDO().setId(reqVO.getId()).setStatus(reqVO.getStatus()));

        // 4. 更新向量
        if (CommonStatusEnum.isEnable(reqVO.getStatus())) {
            writeVectorStore(vectorStore, segment, new Document(segment.getContent()));
        } else {
            deleteVectorStore(vectorStore, segment);
        }
    }

    @Override
    public void reindexKnowledgeSegmentByKnowledgeId(Long knowledgeId) {
        // 1.1 校验知识库存在
        AiKnowledgeDO knowledge = knowledgeService.validateKnowledgeExists(knowledgeId);
        // 1.2 获取知识库向量实例
        //     🔴 reindexAfterMigration 传 false：本方法【就是】那次重嵌，
        //        再触发一次异步重嵌等于同一个知识库被嵌两遍
        EmbeddingTarget target = resolveWritableVectorStore(knowledge, false);

        // 2.1 查询知识库下的所有启用状态的段落
        List<AiKnowledgeSegmentDO> segments = segmentMapper.selectListByKnowledgeIdAndStatus(
                knowledgeId, CommonStatusEnum.ENABLE.getStatus());
        if (CollUtil.isEmpty(segments)) {
            return;
        }
        // 2.2 遍历所有段落，重新索引
        try {
            for (AiKnowledgeSegmentDO segment : segments) {
                // 删除旧的向量
                deleteVectorStore(target.store(), segment);
                // 重新创建向量
                writeVectorStore(target.store(), segment, new Document(segment.getContent()));
            }
        } catch (RuntimeException ex) {
            embeddingFailoverService.recordFailure(target.model(), ex);
            throw ex;
        }
        embeddingFailoverService.recordSuccess(target.model());
        log.info("[reindexKnowledgeSegmentByKnowledgeId][知识库({}) 重新索引完成，共处理 {} 个段落，向量模型({})]",
                knowledgeId, segments.size(), target.model().getModel());
    }

    private void writeVectorStore(VectorStore vectorStore, AiKnowledgeSegmentDO segmentDO, Document segment) {
        // 1. 向量存储
        // 为什么要 toString 呢？因为部分 VectorStore 实现，不支持 Long 类型，例如说 QdrantVectorStore
        segment.getMetadata().put(VECTOR_STORE_METADATA_KNOWLEDGE_ID, segmentDO.getKnowledgeId().toString());
        segment.getMetadata().put(VECTOR_STORE_METADATA_DOCUMENT_ID, segmentDO.getDocumentId().toString());
        segment.getMetadata().put(VECTOR_STORE_METADATA_SEGMENT_ID, segmentDO.getId().toString());
        vectorStore.add(List.of(segment));

        // 2. 更新向量 ID
        segmentMapper.updateById(new AiKnowledgeSegmentDO().setId(segmentDO.getId()).setVectorId(segment.getId()));
    }

    private void deleteVectorStore(VectorStore vectorStore, AiKnowledgeSegmentDO segmentDO) {
        // 1. 更新向量 ID
        if (StrUtil.isEmpty(segmentDO.getVectorId())) {
            return;
        }
        segmentMapper.updateById(new AiKnowledgeSegmentDO().setId(segmentDO.getId())
                .setVectorId(AiKnowledgeSegmentDO.VECTOR_ID_EMPTY));

        // 2. 删除向量
        vectorStore.delete(List.of(segmentDO.getVectorId()));
    }

    @Override
    public List<AiKnowledgeSegmentSearchRespBO> searchKnowledgeSegment(AiKnowledgeSegmentSearchReqBO reqBO) {
        // 1. 校验
        AiKnowledgeDO knowledge = knowledgeService.validateKnowledgeExists(reqBO.getKnowledgeId());

        // 2. 检索
        List<Document> documents = searchDocument(knowledge, reqBO);
        if (CollUtil.isEmpty(documents)) {
            return ListUtil.empty();
        }

        // 3.1 段落召回
        List<AiKnowledgeSegmentDO> segments = segmentMapper
                .selectListByVectorIds(convertList(documents, Document::getId));
        if (CollUtil.isEmpty(segments)) {
            return ListUtil.empty();
        }
        // 3.2 增加召回次数
        segmentMapper.updateRetrievalCountIncrByIds(convertList(segments, AiKnowledgeSegmentDO::getId));

        // 4. 构建结果
        List<AiKnowledgeSegmentSearchRespBO> result = convertList(segments, segment -> {
            Document document = CollUtil.findOne(documents, // 找到对应的文档
                    doc -> Objects.equals(doc.getId(), segment.getVectorId()));
            if (document == null) {
                return null;
            }
            return BeanUtils.toBean(segment, AiKnowledgeSegmentSearchRespBO.class)
                    .setScore(document.getScore());
        });
        result.sort((o1, o2) -> Double.compare(o2.getScore(), o1.getScore())); // 按照分数降序排序
        return result;
    }

    /**
     * 基于 Embedding + Rerank Model，检索知识库中的文档
     *
     * @param knowledge 知识库
     * @param reqBO 检索请求
     * @return 文档列表
     */
    private List<Document> searchDocument(AiKnowledgeDO knowledge, AiKnowledgeSegmentSearchReqBO reqBO) {
        VectorStore vectorStore = getVectorStoreById(knowledge);
        Integer topK = ObjUtil.defaultIfNull(reqBO.getTopK(), knowledge.getTopK());
        Double similarityThreshold = ObjUtil.defaultIfNull(reqBO.getSimilarityThreshold(), knowledge.getSimilarityThreshold());

        // 1. 向量检索
        int searchTopK = rerankModel != null ? topK * RERANK_RETRIEVAL_FACTOR : topK;
        double searchSimilarityThreshold = rerankModel != null ? SIMILARITY_THRESHOLD_ACCEPT_ALL : similarityThreshold;
        SearchRequest.Builder searchRequestBuilder = SearchRequest.builder()
                .query(reqBO.getContent())
                .topK(searchTopK).similarityThreshold(searchSimilarityThreshold)
                .filterExpression(new FilterExpressionBuilder()
                        .eq(VECTOR_STORE_METADATA_KNOWLEDGE_ID, reqBO.getKnowledgeId().toString()).build());
        List<Document> documents = vectorStore.similaritySearch(searchRequestBuilder.build());
        if (CollUtil.isEmpty(documents)) {
            return documents;
        }

        // 2. Rerank 重排序
        if (rerankModel != null) {
            // 🔴 已知缺陷（2026-10-26 实测钉住，见 DashScopeRerankWireContractTest）：
            //    这里 builder 出来的请求级 options <b>没有「model 为 null」这一档</b> ——
            //    DashScopeRerankOptions 的无参构造把 model 初始化成了 "gte-rerank"，
            //    而 DashScopeRerankModel#mergeOptions 是「请求级非 null 就压过构造时默认值」，
            //    于是 spring.ai.dashscope.rerank.options.model 配成什么都会被静默改写成
            //    gte-rerank（一个本账号没开通的旧模型 ⟹ 厂商回 403 AccessDenied）。
            //    修法是在这行补 .withModel(<配置里的模型名>)，但「模型名从哪来」是个待定决策：
            //    本项目其余地方的模型一律存在 ai_model 表里按知识库选（type=6 就是重排序），
            //    只有这条走 Spring 的 yaml 自动配置。两条口径该统一到哪边，不由这次改动决定。
            //    当前 spring.ai.model.rerank=false（application.yaml:190），rerankModel 恒为 null，
            //    本分支进不来 —— 所以它是雷而不是火。谁要开启 rerank，先把上面这句决策做掉。
            RerankResponse rerankResponse = rerankModel.call(new RerankRequest(reqBO.getContent(), documents,
                    DashScopeRerankOptions.builder().withTopN(topK).build()));
            documents = convertList(rerankResponse.getResults(),
                    documentWithScore -> documentWithScore.getScore() >= similarityThreshold
                            ? documentWithScore.getOutput() : null);
        }
        return documents;
    }

    @Override
    public List<AiKnowledgeSegmentDO> splitContent(String url, Integer segmentMaxTokens) {
        // 1. 读取 URL 内容
        String content = knowledgeDocumentService.readUrl(url);

        // 2.1 自动检测文档类型并选择策略
        AiDocumentSplitStrategyEnum strategy = detectDocumentStrategy(content, url);
        // 2.2 文档切片
        List<Document> documentSegments = splitContentByStrategy(content, segmentMaxTokens, strategy, url);

        // 3. 转换为段落对象
        return convertList(documentSegments, segment -> {
            if (StrUtil.isEmpty(segment.getText())) {
                return null;
            }
            return new AiKnowledgeSegmentDO()
                    .setContent(segment.getText())
                    .setContentLength(segment.getText().length())
                    .setTokens(tokenCountEstimator.estimate(segment.getText()));
        });
    }

    /**
     * 校验段落是否存在
     *
     * @param id 文档编号
     * @return 段落信息
     */
    private AiKnowledgeSegmentDO validateKnowledgeSegmentExists(Long id) {
        AiKnowledgeSegmentDO knowledgeSegment = segmentMapper.selectById(id);
        if (knowledgeSegment == null) {
            throw exception(KNOWLEDGE_SEGMENT_NOT_EXISTS);
        }
        return knowledgeSegment;
    }

    private VectorStore getVectorStoreById(AiKnowledgeDO knowledge) {
        return modelService.getOrCreateVectorStore(knowledge.getEmbeddingModelId(), VECTOR_STORE_METADATA_TYPES);
    }

    private VectorStore getVectorStoreById(Long knowledgeId) {
        AiKnowledgeDO knowledge = knowledgeService.validateKnowledgeExists(knowledgeId);
        return getVectorStoreById(knowledge);
    }

    /**
     * <b>写入侧</b>解析向量库：知识库钉住的模型现在探不通，就迁移到另一个探通的
     *
     * <h3>🔴 只有写入侧可以迁移，查询侧永远用钉住的那个</h3>
     * 检索时换一个向量模型，查询向量就落在<b>另一个语义空间</b>里，
     * 与库里那些向量的相似度算出来是垃圾 —— 而且<b>不报错</b>，只是召回变得莫名其妙。
     * 所以 {@link #searchDocument} 一律走 {@link #getVectorStoreById}，不走本方法。
     *
     * <h3>🔴 迁移是「换模型」的唯一正确形态，没有「悄悄换个模型接着写」这一档</h3>
     * 换模型之后旧向量<b>整个作废</b>：它们是在另一个空间里算出来的，维度还可能不一样。
     * 所以迁移必须连带把 {@code vector_id} 清空并重嵌全库，
     * 而不是只把新段落写进新模型 —— 那样一个知识库里会同时存在两个空间的向量，
     * 而检索会把它们放在一起排序。
     *
     * <h3>为什么只挂在本方法与灌库路径上，不挂到每个写入点</h3>
     * 单段落的管理员操作（改一段、加一段、启停一段）都是<b>先删旧向量再写新向量</b>，
     * 删要用旧模型的库、写要用新模型的库。在那里迁移意味着一个方法里同时持有两个
     * {@code VectorStore}，而漏掉任何一个都会让旧向量变成删不掉的孤儿。
     * 那几处的失败是<b>看得见</b>的（厂商原文直接抛给管理员），
     * 出路也现成：点一次「重新索引」就走到本方法来了。
     * 两害相权，把迁移收在批量路径上比在每个写入点各拆一遍删除/写入更不容易出错。
     *
     * @param knowledge             知识库
     * @param reindexAfterMigration 迁移之后要不要触发全库异步重嵌。
     *                              {@link #reindexKnowledgeSegmentByKnowledgeId} 自己就是那次重嵌，传 {@code false}
     * @return 本次写入要用的向量库 + 它背后的模型（调用方要把真实结果回传给选链）
     */
    private EmbeddingTarget resolveWritableVectorStore(AiKnowledgeDO knowledge, boolean reindexAfterMigration) {
        AiModelDO selected = embeddingFailoverService.selectUsableEmbeddingModel(knowledge.getEmbeddingModelId());
        if (!Objects.equals(selected.getId(), knowledge.getEmbeddingModelId())) {
            selected = migrateEmbeddingModel(knowledge, selected, reindexAfterMigration);
        }
        return new EmbeddingTarget(
                modelService.getOrCreateVectorStore(selected.getId(), VECTOR_STORE_METADATA_TYPES), selected);
    }

    /**
     * 把知识库从旧向量模型迁到新模型，并把它已有的向量全部作废
     *
     * @return 迁移之后知识库<b>实际</b>钉着的模型（并发下可能不是 {@code selected}，见方法体）
     */
    private AiModelDO migrateEmbeddingModel(AiKnowledgeDO knowledge, AiModelDO selected,
                                            boolean reindexAfterMigration) {
        // 🔴 这条 UPDATE 的 WHERE 里带着旧模型编号，是一把【跨进程】的乐观锁：
        //    一次灌库会为每篇文档起一个 @Async 任务（实测内置语料 125 篇），
        //    它们会在同一瞬间全部发现「钉住的模型额度没了」。没有这个条件，
        //    125 个任务会各自迁一次、各自触发一次全库重嵌
        int updated = knowledgeMapper.updateEmbeddingModelIfMatch(knowledge.getId(),
                knowledge.getEmbeddingModelId(), selected.getId(), selected.getModel());
        AiKnowledgeDO latest = knowledgeService.validateKnowledgeExists(knowledge.getId());
        if (updated == 0) {
            // 并发下已经有人迁过了。跟随库里的现值：各写各的会让同一个知识库的向量
            // 落在两个语义空间里，而检索不会报错，只会给出垃圾结果
            log.warn("[migrateEmbeddingModel][知识库({}) 已被并发任务迁移到模型({})，本次跟随它，不重复迁移]",
                    knowledge.getId(), latest.getEmbeddingModelId());
            AiModelDO winner = modelService.getModel(latest.getEmbeddingModelId());
            if (winner == null) {
                throw exception(MODEL_NOT_EXISTS);
            }
            return winner;
        }

        // 🔴 先真删【旧模型】向量文件里的那些向量，再清 vector_id。顺序反了就永远删不掉了：
        //    vector_id 一清，库里再没有任何地方记着旧向量叫什么，而 SimpleVectorStore 是整份
        //    JSON 载入内存的 —— 于是每迁移一次就永久多出一份全库向量，文件只涨不落、启动只慢不快。
        //    检索不会因此给出错答案（命中的 vector_id 在库里已无对应行，会被 join 掉），
        //    所以这个泄漏是完全静默的，只能靠这里主动清。
        int purged = purgeVectorsOfModel(knowledge);

        // 旧向量整个作废。清空 vector_id 而不是留着等重嵌覆盖：
        // ① 详情页的「已嵌入」进度是按 vector_id <> '' 统计的，留着旧 id 会在整个重嵌期间
        //    一直显示 100%，而那份向量其实属于一个已经调不通的模型；
        // ② 留着旧 id 的话，重嵌里的 deleteVectorStore 会拿它去【新】库删一个不存在的 id
        int cleared = segmentMapper.clearVectorIdByKnowledgeId(knowledge.getId());
        log.warn("[migrateEmbeddingModel][知识库({}) 的向量模型({}/{}) 不可用，已迁移到({}/{})；"
                        + "原有 {} 个段落的向量作废（其中 {} 个已从旧模型的向量文件里删除）{}]",
                knowledge.getId(), knowledge.getEmbeddingModelId(), knowledge.getEmbeddingModel(),
                selected.getId(), selected.getModel(), cleared, purged,
                reindexAfterMigration ? "，正在异步重新索引" : "，由本次重嵌接着写入");
        if (reindexAfterMigration) {
            // 🔴 必须走 self（代理），this. 调用会让 @Async 失效、变成同步重嵌，见字段注释
            self.reindexByKnowledgeIdAsync(knowledge.getId());
        }
        return selected;
    }

    /**
     * 把知识库在<b>旧模型</b>下已经写出去的那些向量，从旧模型的向量文件里删掉
     *
     * <p>🔴 必须用 {@code knowledge.getEmbeddingModelId()}（内存里那个<b>旧</b>值）来解析向量库，
     * <b>不能</b>用 {@link #getVectorStoreById(Long)}：那个重载会重新查库，而调用本方法之前
     * {@code updateEmbeddingModelIfMatch} 已经把库里的模型编号改成新的了 ——
     * 于是拿到的是<b>新</b>模型的库，拿新库去删旧 id 一条也删不到，还一声不响。
     *
     * <p>删失败只告警不抛：迁移的目的是让写入能继续，旧向量残留只是浪费磁盘，
     * 不值得为它把整个索引流程打断。
     *
     * <h3>什么情况下会删不掉，以及为什么可以接受</h3>
     * {@code getOrCreateVectorStore} 内部走 {@code validateModel}，模型被<b>禁用</b>就抛
     * {@code MODEL_DISABLE}，被<b>删除</b>就抛 {@code MODEL_NOT_EXISTS}。所以管理员把坏模型
     * 停掉/删掉之后再迁移，这里的清理必然失败、旧向量留在文件里。这是刻意接受的：
     * 那种情况下<b>已经没有任何知识库指向旧模型</b>，而 {@code SimpleVectorStore} 是按需构造、
     * 整份 JSON 载入内存的 —— 没人再指向它，就没人再把它载进内存，残留只占磁盘、不占内存，
     * 更不影响召回正确性。反过来，真正需要清理的场景是「模型还启用着、只是额度耗尽或配置坏了」
     * （此时别的知识库可能还指着它，文件会被反复载入），而那正是本方法能删成功的情况。
     * 为「禁用/已删」那一档专门开一个绕过状态校验的取库接口，代价大于收益。
     *
     * @return 实际提交删除的向量数（旧模型压根没写出过向量时为 0）
     */
    private int purgeVectorsOfModel(AiKnowledgeDO knowledge) {
        List<String> vectorIds = convertList(segmentMapper.selectListByKnowledgeId(knowledge.getId()),
                AiKnowledgeSegmentDO::getVectorId);
        // vector_id 的「没嵌过」是空串哨兵而不是 NULL，convertList 只过滤 null，得自己再滤一遍
        vectorIds = vectorIds.stream()
                .filter(vectorId -> StrUtil.isNotBlank(vectorId)
                        && !Objects.equals(vectorId, AiKnowledgeSegmentDO.VECTOR_ID_EMPTY))
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(vectorIds)) {
            return 0;
        }
        try {
            VectorStore staleStore = modelService.getOrCreateVectorStore(
                    knowledge.getEmbeddingModelId(), VECTOR_STORE_METADATA_TYPES);
            staleStore.delete(vectorIds);
            return vectorIds.size();
        } catch (Exception ex) {
            log.warn("[purgeVectorsOfModel][知识库({}) 迁移时，旧模型({}/{}) 的 {} 个向量没能删掉，"
                            + "它们会留在旧向量文件里成为孤儿（不影响召回正确性，只占磁盘）：{}]",
                    knowledge.getId(), knowledge.getEmbeddingModelId(), knowledge.getEmbeddingModel(),
                    vectorIds.size(), AiUtils.describeError(ex));
            return 0;
        }
    }

    /**
     * 一次写入要用的向量库，以及它背后那个<b>真实被调用</b>的模型
     *
     * <p>为什么把模型一起带出来：选链的快路径只认失败记忆，而记忆只能由真实调用的结果喂。
     * 只返回 {@code VectorStore} 的话，调用方手里没有模型可回传 ——
     * 而 {@code EmbeddingModel} 实例上<b>取不到</b>模型标识（已用 javap 对 spring-ai 1.1.8 核对：
     * 它没有 {@code getDefaultOptions()}），所以这个信息只能从选链那一步带下来。
     */
    private record EmbeddingTarget(VectorStore store, AiModelDO model) {
    }

    /**
     * 根据策略切分内容
     *
     * @param content 文档内容
     * @param segmentMaxTokens 分段的最大 Token 数
     * @param strategy 切片策略
     * @param url 文档 URL（用于自动检测文件类型）
     * @return 切片后的文档列表
     */
    @SuppressWarnings("EnhancedSwitchMigration")
    private List<Document> splitContentByStrategy(String content, Integer segmentMaxTokens,
                                                  AiDocumentSplitStrategyEnum strategy, String url) {
        // 自动检测策略
        if (strategy == AiDocumentSplitStrategyEnum.AUTO) {
            strategy = detectDocumentStrategy(content, url);
            log.info("[splitContentByStrategy][自动检测到文档策略: {}]", strategy.getName());
        }
        // 根据策略切分
        TextSplitter textSplitter;
        switch (strategy) {
            case MARKDOWN_QA:
                textSplitter = new MarkdownQaSplitter(segmentMaxTokens);
                break;
            case SEMANTIC:
                textSplitter = new SemanticTextSplitter(segmentMaxTokens);
                break;
            case PARAGRAPH:
                textSplitter = new SemanticTextSplitter(segmentMaxTokens, 0); // 段落切分，无重叠
                break;
            case TOKEN:
            default:
                textSplitter = buildTokenTextSplitter(segmentMaxTokens);
                break;
        }
        // 执行切分
        return textSplitter.apply(Collections.singletonList(new Document(content)));
    }

    /**
     * 自动检测文档类型并选择切片策略
     *
     * @param content 文档内容
     * @param url 文档 URL
     * @return 推荐的切片策略
     */
    private AiDocumentSplitStrategyEnum detectDocumentStrategy(String content, String url) {
        if (StrUtil.isEmpty(content)) {
            return AiDocumentSplitStrategyEnum.TOKEN;
        }
        // 1. 检测 Markdown QA 格式
        if (isMarkdownQaFormat(content, url)) {
            return AiDocumentSplitStrategyEnum.MARKDOWN_QA;
        }
        // 2. 检测普通 Markdown 文档
        if (isMarkdownDocument(url)) {
            return AiDocumentSplitStrategyEnum.SEMANTIC;
        }
        // 3. 默认使用语义切分（比 Token 切分更智能）
        return AiDocumentSplitStrategyEnum.SEMANTIC;
    }

    /**
     * 检测是否为 Markdown QA 格式
     * 特征：包含多个二级标题（## ）且标题后紧跟答案内容
     */
    private boolean isMarkdownQaFormat(String content, String url) {
        // 文件扩展名判断
        if (StrUtil.isNotEmpty(url) && !url.toLowerCase().endsWith(".md")) {
            return false;
        }

        // 统计二级标题数量
        long h2Count = content.lines()
                .filter(line -> line.trim().startsWith("## "))
                .count();

        // 要求一：至少包含 2 个二级标题才认为是 QA 格式
        if (h2Count < 2) {
            return false;
        }

        // 要求二：检查标题占比（QA 文档标题行数相对较多），如果二级标题占比超过 10%，认为是 QA 格式
        long totalLines = content.lines().count();
        double h2Ratio = (double) h2Count / totalLines;
        return h2Ratio > 0.1;
    }

    /**
     * 检测是否为 Markdown 文档
     */
    private boolean isMarkdownDocument(String url) {
        return StrUtil.endWithAnyIgnoreCase(url, ".md", ".markdown");
    }

    /**
     * 构建基于 Token 的文本切片器（原有逻辑保留）
     */
    private static TextSplitter buildTokenTextSplitter(Integer segmentMaxTokens) {
        return TokenTextSplitter.builder()
                .withChunkSize(segmentMaxTokens)
                .withMinChunkSizeChars(Integer.MAX_VALUE) // 忽略字符的截断
                .withMinChunkLengthToEmbed(1) // 允许的最小有效分段长度
                .withMaxNumChunks(Integer.MAX_VALUE)
                .withKeepSeparator(true) // 保留分隔符
                .build();
    }

    @Override
    public List<AiKnowledgeSegmentProcessRespVO> getKnowledgeSegmentProcessList(List<Long> documentIds) {
        if (CollUtil.isEmpty(documentIds)) {
            return Collections.emptyList();
        }
        return segmentMapper.selectProcessList(documentIds);
    }

    @Override
    public Long createKnowledgeSegment(AiKnowledgeSegmentSaveReqVO createReqVO) {
        // 1.1 校验文档是否存在
        AiKnowledgeDocumentDO document = knowledgeDocumentService
                .validateKnowledgeDocumentExists(createReqVO.getDocumentId());
        // 1.2 获取知识库信息
        AiKnowledgeDO knowledge = knowledgeService.validateKnowledgeExists(document.getKnowledgeId());
        // 1.3 校验 token 熟练
        Integer tokens = tokenCountEstimator.estimate(createReqVO.getContent());
        if (tokens > document.getSegmentMaxTokens()) {
            throw exception(KNOWLEDGE_SEGMENT_CONTENT_TOO_LONG, tokens, document.getSegmentMaxTokens());
        }

        // 2. 保存段落
        AiKnowledgeSegmentDO segment = BeanUtils.toBean(createReqVO, AiKnowledgeSegmentDO.class)
                .setKnowledgeId(knowledge.getId()).setDocumentId(document.getId())
                .setContentLength(createReqVO.getContent().length()).setTokens(tokens)
                .setVectorId(AiKnowledgeSegmentDO.VECTOR_ID_EMPTY)
                .setRetrievalCount(0).setStatus(CommonStatusEnum.ENABLE.getStatus());
        segmentMapper.insert(segment);

        // 3. 向量化
        writeVectorStore(getVectorStoreById(knowledge), segment, new Document(segment.getContent()));
        return segment.getId();
    }

    @Override
    public AiKnowledgeSegmentDO getKnowledgeSegment(Long id) {
        return segmentMapper.selectById(id);
    }

    @Override
    public List<AiKnowledgeSegmentDO> getKnowledgeSegmentList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return segmentMapper.selectByIds(ids);
    }

}
