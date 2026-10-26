package cn.enlev.ainb.module.ai.dal.mysql.knowledge;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.ai.controller.admin.knowledge.vo.knowledge.AiKnowledgePageReqVO;
import cn.enlev.ainb.module.ai.dal.dataobject.knowledge.AiKnowledgeDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * AI 知识库 Mapper
 *
 * @author xiaoxin
 */
@Mapper
public interface AiKnowledgeMapper extends BaseMapperX<AiKnowledgeDO> {

    default PageResult<AiKnowledgeDO> selectPage(AiKnowledgePageReqVO pageReqVO) {
        return selectPage(pageReqVO, new LambdaQueryWrapperX<AiKnowledgeDO>()
                .likeIfPresent(AiKnowledgeDO::getName, pageReqVO.getName())
                .eqIfPresent(AiKnowledgeDO::getStatus, pageReqVO.getStatus())
                .betweenIfPresent(AiKnowledgeDO::getCreateTime, pageReqVO.getCreateTime())
                .orderByDesc(AiKnowledgeDO::getId));
    }

    default List<AiKnowledgeDO> selectListByStatus(Integer status) {
        return selectList(AiKnowledgeDO::getStatus, status);
    }

    /**
     * 把知识库钉住的向量模型换掉，<b>但只在它还是我们以为的那个的时候才换</b>
     *
     * <h3>🔴 这条 {@code WHERE embedding_model_id = expected} 是一把跨进程的乐观锁，不是防御性冗余</h3>
     * 一次知识库灌库会为每篇文档起一个 {@code @Async} 任务（实测内置语料 125 篇），
     * 它们会在同一瞬间全部发现「钉住的模型额度没了」并各自决定换一个。
     * 没有这个条件，125 个任务会写出 125 次 {@code embedding_model_id} ——
     * 而每次写都伴随一次「清空全部 vector_id + 全库重嵌」，等于同一个知识库被重嵌 125 遍。
     * 加上它，只有第一个 {@code UPDATE} 影响行数为 1，其余全是 0，各自跟随库里的现值。
     *
     * <p>刻意用 SQL 条件而不是进程内的标记集合：多实例部署下进程内的「已经迁过了」
     * 对另一个实例不成立，而这条 {@code WHERE} 天然跨实例。
     *
     * <p>{@code embedding_model}（模型标识）是 {@code embedding_model_id} 的冗余列，
     * 两列必须一起写 —— 只写 id 的话，界面上显示的还是旧模型名，而按名字排查的人
     * 会去查一个这个知识库其实已经不再使用的模型。
     *
     * @param id               知识库编号
     * @param expectedModelId  迁移前库里的向量模型编号（乐观锁的比较值）
     * @param newModelId       要迁到的向量模型编号
     * @param newModel         要迁到的向量模型标识
     * @return 影响行数：1 = 本次迁移由调用方赢得；0 = 已经有人迁过了
     */
    default int updateEmbeddingModelIfMatch(Long id, Long expectedModelId, Long newModelId, String newModel) {
        return update(new LambdaUpdateWrapper<AiKnowledgeDO>()
                .set(AiKnowledgeDO::getEmbeddingModelId, newModelId)
                .set(AiKnowledgeDO::getEmbeddingModel, newModel)
                .eq(AiKnowledgeDO::getId, id)
                .eq(AiKnowledgeDO::getEmbeddingModelId, expectedModelId));
    }

}
