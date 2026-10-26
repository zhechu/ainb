package cn.enlev.ainb.module.agent.service.prompttemplate;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateCasePageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateCaseSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate.AgentPromptTemplateCaseDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 提示词模板用例 Service（S7 / L5）
 *
 * <p>🔴 {@code @Valid} 只声明在这里（接口上），实现类不再标 —— 两处同时标会让
 * Hibernate Validator 抛 {@code ConstraintDeclarationException}(HV000151)，
 * 表现是该端点<b>永远</b>返回「请求参数不正确」。见 {@code AgentPromptTemplateServiceImpl} 的同款注释。
 *
 * @author 走进新时代
 */
public interface AgentPromptTemplateCaseService {

    /**
     * 创建用例
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createCase(@Valid AgentPromptTemplateCaseSaveReqVO createReqVO);

    /**
     * 更新用例
     *
     * @param updateReqVO 更新信息
     */
    void updateCase(@Valid AgentPromptTemplateCaseSaveReqVO updateReqVO);

    /**
     * 删除用例
     *
     * @param id 编号
     */
    void deleteCase(Long id);

    /**
     * 获得单条用例
     *
     * @param id 编号
     * @return 用例；不存在时抛 {@code AGENT_PROMPT_TEMPLATE_CASE_NOT_EXISTS}
     */
    AgentPromptTemplateCaseDO getCase(Long id);

    /**
     * 获得用例分页（管理页）
     *
     * @param pageReqVO 分页查询
     * @return 分页结果
     */
    PageResult<AgentPromptTemplateCaseDO> getCasePage(AgentPromptTemplateCasePageReqVO pageReqVO);

    /**
     * 获得某条模板下的全部用例，<b>含关闭态</b>（管理页的用例子表）
     *
     * @param templateId 模板编号
     * @return 用例列表，按 sort 升序
     */
    List<AgentPromptTemplateCaseDO> getCaseListByTemplateId(Long templateId);

    /**
     * 获得聊天面板「示例」页签该显示的用例：用例本身开启，<b>且它的模板也开启</b>
     *
     * <p>🔴 后半句是这条方法存在的理由，别简化成「查开启的用例」：
     * 模板被关闭意为「这条提问暂时下架」，此时它的示例还挂在聊天面板上，
     * 用户照着发出去，走的却是一条管理员刚刚决定不再引导的路。
     * 而这种不一致<b>没有任何报错</b> —— 两边接口都是绿的。
     *
     * @return 用例列表，按 templateId、sort 升序
     */
    List<AgentPromptTemplateCaseDO> getEnabledCaseList();

}
