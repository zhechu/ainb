package cn.enlev.ainb.module.agent.controller.admin.prompttemplate;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateCasePageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateCaseRespVO;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateCaseSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate.AgentPromptTemplateCaseDO;
import cn.enlev.ainb.module.agent.service.prompttemplate.AgentPromptTemplateCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * 提示词模板<b>用例</b>管理（S7 / L5）
 *
 * <h3>🔴 刻意<b>不</b>新起一套 {@code agent:prompt-template-case:*} 权限点</h3>
 * 全部复用模板那四个（{@code query/create/update/delete}）。理由不是省事：
 * 用例子表就长在「提示词模板」这个管理页里面，能被授到这一页的人，天然就该能维护页里的子表。
 * 另起一套权限点的话，只被授了模板管理的角色会看到一个「用例」页签、点编辑就 403 ——
 * 按钮看得见、点了报错，正是 {@code AgentPromptTemplateController} 类注释里骂过的那种缺陷。
 * 因此本次<b>没有</b>任何新菜单、新按钮、新授权 SQL。
 *
 * <h3>两个读列表端点，受众不同</h3>
 * <ul>
 *   <li>{@code /simple-list} 是<b>聊天面板</b>「示例」页签的数据源，与模板的 {@code /simple-list}
 *       同款用 {@code hasAnyPermissions('agent:assistant:use', 'agent:prompt-template:query')}：
 *       只写 query 会让普通聊天用户 403，只写 assistant:use 会让模板管理员没法在页内预览。</li>
 *   <li>{@code /list-by-template} 是<b>管理页</b>子表的数据源，含关闭态（管理员要能重新打开
 *       一条下架的用例），故走 query。</li>
 * </ul>
 *
 * <h3>🔴 与模板一样，用例不进模型系统提示词</h3>
 * 它只是「插入到输入框的一段文本」，走普通用户消息通道：不绕过安全网关、不影响工具选择、
 * 不能覆盖 Agent 的 persona。所以这里的增删改<b>没有</b>安全影响面。
 * {@code expectHint} / {@code expectIntent} 两列同样只给管理页和验收脚本看，绝不进上下文 ——
 * 把「合格答案该长什么样」喂给模型，等于把考卷和答案一起发下去。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - Agent 提示词模板用例")
@RestController
@RequestMapping("/agent/prompt-template-case")
@Validated
public class AgentPromptTemplateCaseController {

    @Resource
    private AgentPromptTemplateCaseService promptTemplateCaseService;

    @PostMapping("/create")
    @Operation(summary = "创建提示词模板用例")
    @PreAuthorize("@ss.hasPermission('agent:prompt-template:create')")
    public CommonResult<Long> createCase(@Valid @RequestBody AgentPromptTemplateCaseSaveReqVO createReqVO) {
        return success(promptTemplateCaseService.createCase(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新提示词模板用例")
    @PreAuthorize("@ss.hasPermission('agent:prompt-template:update')")
    public CommonResult<Boolean> updateCase(@Valid @RequestBody AgentPromptTemplateCaseSaveReqVO updateReqVO) {
        promptTemplateCaseService.updateCase(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除提示词模板用例")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:prompt-template:delete')")
    public CommonResult<Boolean> deleteCase(@RequestParam("id") Long id) {
        promptTemplateCaseService.deleteCase(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得提示词模板用例")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:prompt-template:query')")
    public CommonResult<AgentPromptTemplateCaseRespVO> getCase(@RequestParam("id") Long id) {
        AgentPromptTemplateCaseDO entity = promptTemplateCaseService.getCase(id);
        return success(BeanUtils.toBean(entity, AgentPromptTemplateCaseRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得提示词模板用例分页")
    @PreAuthorize("@ss.hasPermission('agent:prompt-template:query')")
    public CommonResult<PageResult<AgentPromptTemplateCaseRespVO>> getCasePage(
            @Valid AgentPromptTemplateCasePageReqVO pageReqVO) {
        PageResult<AgentPromptTemplateCaseDO> pageResult = promptTemplateCaseService.getCasePage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, AgentPromptTemplateCaseRespVO.class));
    }

    @GetMapping("/list-by-template")
    @Operation(summary = "获得某条模板下的全部用例（管理页子表，含关闭态，不分页）")
    @Parameter(name = "templateId", description = "模板编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:prompt-template:query')")
    public CommonResult<List<AgentPromptTemplateCaseRespVO>> getCaseListByTemplate(
            @RequestParam("templateId") Long templateId) {
        return success(BeanUtils.toBean(promptTemplateCaseService.getCaseListByTemplateId(templateId),
                AgentPromptTemplateCaseRespVO.class));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获得聊天面板「示例」页签该显示的用例（用例与所属模板都开启，不分页）")
    @PreAuthorize("@ss.hasAnyPermissions('agent:assistant:use', 'agent:prompt-template:query')")
    public CommonResult<List<AgentPromptTemplateCaseRespVO>> getSimpleCaseList() {
        return success(BeanUtils.toBean(promptTemplateCaseService.getEnabledCaseList(),
                AgentPromptTemplateCaseRespVO.class));
    }

}
