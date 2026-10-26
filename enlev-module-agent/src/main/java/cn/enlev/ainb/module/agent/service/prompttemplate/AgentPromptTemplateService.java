package cn.enlev.ainb.module.agent.service.prompttemplate;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplatePageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate.AgentPromptTemplateDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 提示词模板 Service（需求7）
 *
 * <p>纯 CRUD + 两个读列表。模板不参与 Agent 运行链路（不进系统提示词、不经安全网关、不计费），
 * 它只是聊天输入框的「起手式」，故没有发布/版本/校验绑定这类机制。
 *
 * @author 走进新时代
 */
public interface AgentPromptTemplateService {

    Long createPromptTemplate(@Valid AgentPromptTemplateSaveReqVO createReqVO);

    void updatePromptTemplate(@Valid AgentPromptTemplateSaveReqVO updateReqVO);

    void deletePromptTemplate(Long id);

    AgentPromptTemplateDO getPromptTemplate(Long id);

    PageResult<AgentPromptTemplateDO> getPromptTemplatePage(AgentPromptTemplatePageReqVO pageReqVO);

    /**
     * 聊天面板的模板列表：当前租户、仅开启中，按分类 + 排序号升序
     */
    List<AgentPromptTemplateDO> getEnabledPromptTemplateList();

    /**
     * 当前租户已用过的分类名（去重升序），供管理页筛选与编辑表单下拉
     */
    List<String> getCategoryList();

}
