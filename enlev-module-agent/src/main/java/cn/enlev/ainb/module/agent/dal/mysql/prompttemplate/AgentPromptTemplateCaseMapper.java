package cn.enlev.ainb.module.agent.dal.mysql.prompttemplate;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateCasePageReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate.AgentPromptTemplateCaseDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 提示词模板用例 Mapper（S7 / L5）
 *
 * <p>🔴 租户隔离同 {@link AgentPromptTemplateMapper}：本表是 {@code TenantBaseDO}，
 * 拦截器自动拼 {@code tenant_id=?}，A 租户的用例不会串到 B 租户。
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentPromptTemplateCaseMapper extends BaseMapperX<AgentPromptTemplateCaseDO> {

    /**
     * 聊天面板「示例」页签的数据源：只取开启中的，按模板分组所需的两级排序一次给全
     *
     * <p>🔴 不分页、不设条数上限，理由与 {@link AgentPromptTemplateMapper#selectEnabledList()} 同：
     * 前端要按 {@code templateId} 就地分组，悄悄截断会让某些模板的用例整组消失且毫无提示。
     * 用例是人工维护的短文本，量级天然只有几十条。
     *
     * <p>排序键带上 {@code templateId}：前端按模板分组时希望同一模板的用例在结果里连续，
     * 否则要么额外排一次、要么分组顺序跟着 id 乱跳。
     */
    default List<AgentPromptTemplateCaseDO> selectEnabledList() {
        return selectList(new LambdaQueryWrapperX<AgentPromptTemplateCaseDO>()
                .eq(AgentPromptTemplateCaseDO::getStatus, CommonStatusEnum.ENABLE.getStatus())
                .orderByAsc(AgentPromptTemplateCaseDO::getTemplateId)
                .orderByAsc(AgentPromptTemplateCaseDO::getSort)
                .orderByAsc(AgentPromptTemplateCaseDO::getId));
    }

    /**
     * 管理页某条模板下的用例子表：<b>含关闭态</b>
     *
     * <p>与 {@link #selectEnabledList()} 的差别是有意的 —— 管理页要能看见并重新打开
     * 一条被暂时下架的用例，按 {@code status=0} 过滤会让它「删不掉也找不到」。
     */
    default List<AgentPromptTemplateCaseDO> selectListByTemplateId(Long templateId) {
        return selectList(new LambdaQueryWrapperX<AgentPromptTemplateCaseDO>()
                .eq(AgentPromptTemplateCaseDO::getTemplateId, templateId)
                .orderByAsc(AgentPromptTemplateCaseDO::getSort)
                .orderByAsc(AgentPromptTemplateCaseDO::getId));
    }

    /**
     * 删模板时连带删它的用例（软删：{@code BaseDO#deleted} 上有 {@code @TableLogic}）
     *
     * <p>🔴 必须与删模板在同一个事务里，见
     * {@code AgentPromptTemplateServiceImpl#deletePromptTemplate}。分开做的话，
     * 中途失败会留下一批指向已删模板的用例：分页接口数得出它们，页面上却没有任何入口能点到。
     */
    default int deleteByTemplateId(Long templateId) {
        return delete(new LambdaQueryWrapperX<AgentPromptTemplateCaseDO>()
                .eq(AgentPromptTemplateCaseDO::getTemplateId, templateId));
    }

    default PageResult<AgentPromptTemplateCaseDO> selectPage(AgentPromptTemplateCasePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AgentPromptTemplateCaseDO>()
                .eqIfPresent(AgentPromptTemplateCaseDO::getTemplateId, reqVO.getTemplateId())
                .likeIfPresent(AgentPromptTemplateCaseDO::getTitle, reqVO.getTitle())
                .likeIfPresent(AgentPromptTemplateCaseDO::getContent, reqVO.getContent())
                .eqIfPresent(AgentPromptTemplateCaseDO::getExpectIntent, reqVO.getExpectIntent())
                .eqIfPresent(AgentPromptTemplateCaseDO::getStatus, reqVO.getStatus())
                .orderByAsc(AgentPromptTemplateCaseDO::getTemplateId)
                .orderByAsc(AgentPromptTemplateCaseDO::getSort)
                .orderByDesc(AgentPromptTemplateCaseDO::getId));
    }

}
