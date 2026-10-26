package cn.enlev.ainb.module.ai.dal.mysql.knowledge;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.framework.mybatis.core.query.MPJLambdaWrapperX;
import cn.enlev.ainb.module.ai.controller.admin.knowledge.vo.segment.AiKnowledgeSegmentPageReqVO;
import cn.enlev.ainb.module.ai.controller.admin.knowledge.vo.segment.AiKnowledgeSegmentProcessRespVO;
import cn.enlev.ainb.module.ai.dal.dataobject.knowledge.AiKnowledgeSegmentDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * AI 知识库分片 Mapper
 *
 * @author xiaoxin
 */
@Mapper
public interface AiKnowledgeSegmentMapper extends BaseMapperX<AiKnowledgeSegmentDO> {

    default PageResult<AiKnowledgeSegmentDO> selectPage(AiKnowledgeSegmentPageReqVO reqVO) {
        // 🔴 documentId 必须用 eqIfPresent：它在 ReqVO 里没有声明 required，
        //    而 MP 的 eq(column, null) 会原样渲染成 `document_id = NULL` ⟹ 恒不成立 ⟹ 永远返回空列表。
        //    实测不带 documentId 调 /page 拿到 total=0，带上就有 9 条。
        //    管理端页面靠 onMounted 里的 route.query.documentId 守卫把这个坑挡住了（缺参直接跳走），
        //    所以界面上看不出来；但接口契约是「可选过滤条件」，任何别的调用方都会静默拿到空页。
        //    本仓 125 处分页查询用的都是 *IfPresent，只有这一处是裸 eq。
        return selectPage(reqVO, new LambdaQueryWrapperX<AiKnowledgeSegmentDO>()
                .eqIfPresent(AiKnowledgeSegmentDO::getDocumentId, reqVO.getDocumentId())
                .likeIfPresent(AiKnowledgeSegmentDO::getContent, reqVO.getContent())
                .eqIfPresent(AiKnowledgeSegmentDO::getStatus, reqVO.getStatus())
                .orderByDesc(AiKnowledgeSegmentDO::getId));
    }

    default List<AiKnowledgeSegmentDO> selectListByVectorIds(List<String> vectorIds) {
        return selectList(new LambdaQueryWrapperX<AiKnowledgeSegmentDO>()
                .in(AiKnowledgeSegmentDO::getVectorId, vectorIds)
                .orderByDesc(AiKnowledgeSegmentDO::getId));
    }

    default List<AiKnowledgeSegmentDO> selectListByDocumentId(Long documentId) {
        return selectList(new LambdaQueryWrapperX<AiKnowledgeSegmentDO>()
                .eq(AiKnowledgeSegmentDO::getDocumentId, documentId)
                .orderByDesc(AiKnowledgeSegmentDO::getId));
    }

    default List<AiKnowledgeSegmentDO> selectListByKnowledgeIdAndStatus(Long knowledgeId, Integer status) {
        return selectList(AiKnowledgeSegmentDO::getKnowledgeId, knowledgeId,
                AiKnowledgeSegmentDO::getStatus, status);
    }

    /**
     * 取一个知识库下的<b>全部</b>段落，不过滤状态
     *
     * <p>为什么不复用 {@link #selectListByKnowledgeIdAndStatus} 传 {@code status = null}：
     * {@code BaseMapperX.selectList(f1, v1, f2, v2)} 用的是裸 {@code eq}，
     * 传 null 会渲染成 {@code status = NULL} ⟹ 恒不成立 ⟹ 一条都取不到。
     * 迁移向量模型时要作废的是<b>停用</b>段落的向量也一样，所以这里必须不过滤状态。
     */
    default List<AiKnowledgeSegmentDO> selectListByKnowledgeId(Long knowledgeId) {
        return selectList(new LambdaQueryWrapperX<AiKnowledgeSegmentDO>()
                .eq(AiKnowledgeSegmentDO::getKnowledgeId, knowledgeId));
    }

    default List<AiKnowledgeSegmentProcessRespVO> selectProcessList(Collection<Long> documentIds) {
        MPJLambdaWrapper<AiKnowledgeSegmentDO> wrapper = new MPJLambdaWrapperX<AiKnowledgeSegmentDO>()
                .selectAs(AiKnowledgeSegmentDO::getDocumentId, AiKnowledgeSegmentProcessRespVO::getDocumentId)
                .selectCount(AiKnowledgeSegmentDO::getId, "count")
                .select("COUNT(CASE WHEN vector_id IS NOT NULL AND vector_id <> '" + AiKnowledgeSegmentDO.VECTOR_ID_EMPTY + "' THEN 1 ELSE NULL END) AS embeddingCount")
                .in(AiKnowledgeSegmentDO::getDocumentId, documentIds)
                .groupBy(AiKnowledgeSegmentDO::getDocumentId);
        return selectJoinList(AiKnowledgeSegmentProcessRespVO.class, wrapper);
    }

    default void updateRetrievalCountIncrByIds(List<Long> ids) {
        update(new LambdaUpdateWrapper<AiKnowledgeSegmentDO>()
                .setSql(" retrieval_count = retrieval_count + 1")
                .in(AiKnowledgeSegmentDO::getId, ids));
    }

    /**
     * 把一个知识库下所有段落的 {@code vector_id} 清空
     *
     * <p>🔴 只在<b>换向量模型</b>时调用。换模型意味着已有向量<b>整个作废</b> ——
     * 它们是在另一个语义空间里算出来的，维度还可能不一样。
     *
     * <p>为什么不等重嵌逐条覆盖，而要一次性清空：{@link #selectProcessList} 是按
     * {@code vector_id <> ''} 统计「已嵌入」的，留着旧 id 的话，
     * 知识库详情页会在整个重嵌期间一直显示 100% 已嵌入 —— 而那份向量其实属于一个
     * 已经调不通的模型。清空之后进度条诚实地从 0 开始爬，管理员一眼看得出「正在重嵌」。
     *
     * <p>代价是旧模型向量文件里那些向量成了删不掉的孤儿（新库里没有它们的 id）。
     * 这是刻意接受的：{@code SimpleVectorStore} 是一个 JSON 文件，孤儿只占磁盘、不影响检索
     * （检索是拿新库返回的 id 去 {@link #selectListByVectorIds} 反查，孤儿永远不会被返回）。
     *
     * @param knowledgeId 知识库编号
     * @return 被清空的段落数
     */
    default int clearVectorIdByKnowledgeId(Long knowledgeId) {
        return update(new LambdaUpdateWrapper<AiKnowledgeSegmentDO>()
                .set(AiKnowledgeSegmentDO::getVectorId, AiKnowledgeSegmentDO.VECTOR_ID_EMPTY)
                .eq(AiKnowledgeSegmentDO::getKnowledgeId, knowledgeId));
    }

}
