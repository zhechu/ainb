package cn.enlev.ainb.module.agent.dal.mysql.prompttemplate;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplatePageReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate.AgentPromptTemplateDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 提示词模板 Mapper（需求7）
 *
 * <p>🔴 租户隔离：本表是 {@code TenantBaseDO}，TenantLineInnerInterceptor 自动给每条查询拼
 * {@code tenant_id=?}。故聊天面板取到的模板只可能是<b>当前租户</b>的，A 租户的口径不会串到 B 租户。
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentPromptTemplateMapper extends BaseMapperX<AgentPromptTemplateDO> {

    /**
     * 聊天面板的模板下拉：只取开启中的，按分类再按排序号升序
     *
     * <p>🔴 不分页、不设条数上限：这个列表会被前端按 {@code category} 就地分组渲染成标签页，
     * 悄悄截断（比如只取前 50 条）会让某些分类整个消失且毫无提示——用户以为「没有这类模板」，
     * 实际是被截掉了。模板是人工维护的短文本，量级天然只有几十条。
     */
    default List<AgentPromptTemplateDO> selectEnabledList() {
        return selectList(new LambdaQueryWrapperX<AgentPromptTemplateDO>()
                .eq(AgentPromptTemplateDO::getStatus, CommonStatusEnum.ENABLE.getStatus())
                .orderByAsc(AgentPromptTemplateDO::getCategory)
                .orderByAsc(AgentPromptTemplateDO::getSort)
                .orderByAsc(AgentPromptTemplateDO::getId));
    }

    /**
     * 管理页「分类」筛选与编辑表单分类下拉的候选项：当前租户已用过的分类，去重升序
     *
     * <p>含已关闭模板的分类（管理页要能筛到它们），不含空串与 NULL。
     */
    default List<AgentPromptTemplateDO> selectListForCategories() {
        return selectList(new LambdaQueryWrapperX<AgentPromptTemplateDO>()
                .isNotNull(AgentPromptTemplateDO::getCategory)
                .ne(AgentPromptTemplateDO::getCategory, "")
                .orderByAsc(AgentPromptTemplateDO::getCategory));
    }

    default PageResult<AgentPromptTemplateDO> selectPage(AgentPromptTemplatePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AgentPromptTemplateDO>()
                .likeIfPresent(AgentPromptTemplateDO::getName, reqVO.getName())
                .likeIfPresent(AgentPromptTemplateDO::getContent, reqVO.getContent())
                .eqIfPresent(AgentPromptTemplateDO::getCategory, reqVO.getCategory())
                .eqIfPresent(AgentPromptTemplateDO::getStatus, reqVO.getStatus())
                .orderByAsc(AgentPromptTemplateDO::getSort)
                .orderByDesc(AgentPromptTemplateDO::getId));
    }

}
