package cn.enlev.ainb.module.ai.dal.mysql.model;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.framework.mybatis.core.query.QueryWrapperX;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelPageReqVO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import org.apache.ibatis.annotations.Mapper;

import javax.annotation.Nullable;
import java.util.List;

/**
 * API 模型 Mapper
 *
 * @author fansili
 */
@Mapper
public interface AiChatMapper extends BaseMapperX<AiModelDO> {

    /**
     * 🔴 两个「取默认模型」的查询都<b>必须</b>带 {@code id} 兜底排序，否则默认模型是<b>抽签</b>
     *
     * <p>{@code ORDER BY sort} 在 sort 相同的行之间<b>没有确定顺序</b>：MySQL 返回哪一行取决于当次
     * 选的访问路径（走了哪个索引、buffer 里是什么），同一条 SQL 连跑两次可以给出不同结果。
     * 开发库里 {@code status=启用} 的 TongYi 聊天模型<b>八个都填 sort=0</b>，于是「默认聊天模型」
     * 每次调用都在 qwen3-max / qwen3.6-plus / qwen3.5-flash… 之间随机漂移。
     *
     * <p>实测疤（2026-10-04，模板矩阵 e2e 整轮作废）：同一条 e2e 连发 7 条消息，落到的模型依次是
     * 76 → 74 → 74 → 75 → 69 → 69 → 72。其中 qwen3.5-* 三个模型每条要跑 100~295 秒
     * （id 928 那条 295 秒只产出 63 个补全 token），而 qwen3-max 只要 2~18 秒。
     * 前端 300 秒的用例上限先到，于是消息全被前端 abort 成 {@code CANCELLED}，
     * 界面上表现为「AI 不回答」，而<b>后端一条 warn 都没有</b> —— 没有调用失败，故障切换压根没被触发，
     * 慢模型是在<b>持续吐字</b>的（120 秒的流式 timeout 也就永远不会到）。
     * 排查的人只能从 {@code agent_usage_log.model_id} 反推出「原来是模型在变」。
     *
     * <p>加 {@code id} 兜底不改变「sort 最前」的既有语义，只是让它<b>可复现</b>：
     * 真要指定默认模型，就把那个模型的 sort 填小 —— 而不是指望并列时数据库替你挑。
     * 顺带这也让「同样一句话两次跑出不同质量/不同计费」这种无从复现的问题不再发生。
     */
    default AiModelDO selectFirstByStatus(Integer type, Integer status) {
        return selectOne(new QueryWrapperX<AiModelDO>()
                .eq("type", type)
                .eq("status", status)
                .limitN(1)
                .orderByAsc("sort", "id"));
    }

    default PageResult<AiModelDO> selectPage(AiModelPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AiModelDO>()
                .likeIfPresent(AiModelDO::getName, reqVO.getName())
                .eqIfPresent(AiModelDO::getModel, reqVO.getModel())
                .eqIfPresent(AiModelDO::getPlatform, reqVO.getPlatform())
                .eqIfPresent(AiModelDO::getStatus, reqVO.getStatus())
                .orderByAsc(AiModelDO::getSort));
    }

    /**
     * 取某类型下指定状态的全部模型，按「sort 升序 + id 升序」排列
     *
     * <p>🔴 这里的顺序<b>就是故障切换候选链的顺序</b>（{@code AiModelFailoverServiceImpl#buildCandidateChain}
     * 直接拿它当链，链首即「不指定 modelId 时用的那个」）。所以 id 兜底排序的理由与
     * {@link #selectFirstByStatus} 完全同源，见那条的注释：sort 并列时不加兜底，默认模型就成了抽签。
     * 两个查询<b>必须</b>同口径 —— 只改一个的话，「simple-list 显示的默认」与「实际跑的默认」会各说各话。
     */
    default List<AiModelDO> selectListByStatusAndType(Integer status, Integer type,
                                                      @Nullable String platform) {
        return selectList(new LambdaQueryWrapperX<AiModelDO>()
                .eq(AiModelDO::getStatus, status)
                .eq(AiModelDO::getType, type)
                .eqIfPresent(AiModelDO::getPlatform, platform)
                .orderByAsc(AiModelDO::getSort)
                .orderByAsc(AiModelDO::getId));
    }

}
