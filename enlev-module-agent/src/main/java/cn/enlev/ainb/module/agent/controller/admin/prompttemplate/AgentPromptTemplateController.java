package cn.enlev.ainb.module.agent.controller.admin.prompttemplate;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplatePageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateRespVO;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate.AgentPromptTemplateDO;
import cn.enlev.ainb.module.agent.service.prompttemplate.AgentPromptTemplateService;
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
 * 提示词模板管理（需求7）
 *
 * <h3>两套权限，按「谁在用」而不是「在哪个页」划分</h3>
 * <ul>
 *   <li>管理端点（{@code /create /update /delete /get /page /category-list}）用
 *       {@code agent:prompt-template:*}，与「提示词模板」菜单页一一对应。</li>
 *   <li>{@code /simple-list} 是<b>聊天面板</b>的模板下拉数据源，用
 *       {@code hasAnyPermissions('agent:assistant:use', 'agent:prompt-template:query')}。
 *       🔴 这里若图省事写成 {@code agent:prompt-template:query}，普通聊天用户（只被授了「智能助手」菜单、
 *       没有模板管理权限）点模板按钮就会 403 —— 按钮看得见、点了报错，是典型的「配了但不生效」。
 *       反过来只写 {@code agent:assistant:use} 也不行：只被授了模板管理页的管理员无法在页内预览效果。
 *       两者取并集才是这个端点的真实受众。</li>
 * </ul>
 *
 * <h3>🔴 模板不进模型系统提示词</h3>
 * 它只是「插入到输入框的一段文本」，走的是普通用户消息通道：不绕过安全网关、不影响工具选择、
 * 不能覆盖 Agent 的 persona。所以这里的增删改<b>没有</b>任何安全影响面，也就无需 riskLevel/permissions 那套声明。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - Agent 提示词模板")
@RestController
@RequestMapping("/agent/prompt-template")
@Validated
public class AgentPromptTemplateController {

    @Resource
    private AgentPromptTemplateService promptTemplateService;

    @PostMapping("/create")
    @Operation(summary = "创建提示词模板")
    @PreAuthorize("@ss.hasPermission('agent:prompt-template:create')")
    public CommonResult<Long> createPromptTemplate(@Valid @RequestBody AgentPromptTemplateSaveReqVO createReqVO) {
        return success(promptTemplateService.createPromptTemplate(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新提示词模板")
    @PreAuthorize("@ss.hasPermission('agent:prompt-template:update')")
    public CommonResult<Boolean> updatePromptTemplate(@Valid @RequestBody AgentPromptTemplateSaveReqVO updateReqVO) {
        promptTemplateService.updatePromptTemplate(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除提示词模板")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:prompt-template:delete')")
    public CommonResult<Boolean> deletePromptTemplate(@RequestParam("id") Long id) {
        promptTemplateService.deletePromptTemplate(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得提示词模板")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:prompt-template:query')")
    public CommonResult<AgentPromptTemplateRespVO> getPromptTemplate(@RequestParam("id") Long id) {
        AgentPromptTemplateDO template = promptTemplateService.getPromptTemplate(id);
        return success(BeanUtils.toBean(template, AgentPromptTemplateRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得提示词模板分页")
    @PreAuthorize("@ss.hasPermission('agent:prompt-template:query')")
    public CommonResult<PageResult<AgentPromptTemplateRespVO>> getPromptTemplatePage(
            @Valid AgentPromptTemplatePageReqVO pageReqVO) {
        PageResult<AgentPromptTemplateDO> pageResult = promptTemplateService.getPromptTemplatePage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, AgentPromptTemplateRespVO.class));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获得开启中的提示词模板列表（聊天面板的模板下拉数据源，不分页）")
    @PreAuthorize("@ss.hasAnyPermissions('agent:assistant:use', 'agent:prompt-template:query')")
    public CommonResult<List<AgentPromptTemplateRespVO>> getSimplePromptTemplateList() {
        return success(BeanUtils.toBean(promptTemplateService.getEnabledPromptTemplateList(),
                AgentPromptTemplateRespVO.class));
    }

    @GetMapping("/category-list")
    @Operation(summary = "获得已用过的模板分类（管理页筛选与编辑表单下拉）")
    @PreAuthorize("@ss.hasPermission('agent:prompt-template:query')")
    public CommonResult<List<String>> getCategoryList() {
        return success(promptTemplateService.getCategoryList());
    }

}
