package cn.enlev.ainb.module.agent.controller.admin.tooldef;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentRegisteredToolRespVO;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentRegisteredToolUpdateReqVO;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentToolDefPageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentToolDefRespVO;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentToolDefSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolOverrideService;
import cn.enlev.ainb.module.infra.api.datainterface.dto.DataInterfaceSimpleRespDTO;
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
 * Agent 工具管理（用户自定义工具 + 内置工具在线维护）
 *
 * <h3>两类工具，一套管理页</h3>
 * <ul>
 *   <li><b>用户自定义工具</b>（{@code /create /update /delete /get /page}）：「无 Java 代码」的工具，
 *       定义即数据，落 {@code agent_tool_def}。🔴 2026-09-29 起<b>只有「数据接口工具」一种</b>——
 *       把一个已治理的 {@code infra_data_interface} 接口以 AI 能理解的方式命名、描述并声明风险与权限，
 *       见 {@code ToolSourceEnum} 与 {@code DataInterfaceToolConfig} 的类注释（原自建 HTTP 出站已删除）。</li>
 *   <li><b>内置（已注册）工具</b>（{@code /registered-list /registered-update /registered-reset}）：
 *       {@code @AgentTool} 代码工具。<b>执行体与默认值仍在代码里</b>（{@code AgentToolRegistry}），
 *       不可经数据新建/删除；但产品决策放开了「运维旋钮」的在线维护——展示名/说明/分组/风险/权限/启用/参数说明
 *       可改，改完<b>立即生效、无需重启</b>（网关放行、是否需确认、LLM 提示词全部跟随）。
 *       覆盖值落 {@code agent_tool_override}（租户级），「恢复默认」= 删覆盖行。</li>
 * </ul>
 *
 * <h3>🔴 内置工具维护的安全取舍（产品决策，2026-09-28）</h3>
 * 全字段自由维护意味着管理员<b>可以调低风险、删空权限</b>（如把不可逆的建表工具降成只读、不再进 Plan 确认）。
 * 这是用户明确接受的取舍。放松<b>不拦截</b>，但 {@code AgentToolOverrideService.warnIfRelaxed} 每次 log.warn
 * 记录操作人 + 前后值留审计痕迹。覆盖按<b>当前租户</b>生效，绝不跨租户污染（注册表 getter 按租户现算）。
 *
 * <h3>专属权限点 {@code agent:tool-def:*}（AI助手 问题2 拆分）</h3>
 * 工具管理有独立菜单页，故不再复用 {@code agent:config:*}：读端点用 {@code agent:tool-def:query}，
 * 写端点用 {@code :create / :update / :delete}。内置工具的读用 {@code :query}、写（维护/恢复）复用 {@code :update}
 * （无新增按钮权限，见 {@code sql/mysql/xpy-2026-09-28-agent-tool-def-menu.sql}；super_admin 绕过一切权限点）。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - Agent 工具管理")
@RestController
@RequestMapping("/agent/tool-def")
@Validated
public class AgentToolDefController {

    @Resource
    private AgentToolDefService toolDefService;
    @Resource
    private AgentToolOverrideService toolOverrideService;

    @PostMapping("/create")
    @Operation(summary = "创建用户自定义工具")
    @PreAuthorize("@ss.hasPermission('agent:tool-def:create')")
    public CommonResult<Long> createToolDef(@Valid @RequestBody AgentToolDefSaveReqVO createReqVO) {
        return success(toolDefService.createToolDef(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新用户自定义工具")
    @PreAuthorize("@ss.hasPermission('agent:tool-def:update')")
    public CommonResult<Boolean> updateToolDef(@Valid @RequestBody AgentToolDefSaveReqVO updateReqVO) {
        toolDefService.updateToolDef(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除用户自定义工具")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:tool-def:delete')")
    public CommonResult<Boolean> deleteToolDef(@RequestParam("id") Long id) {
        toolDefService.deleteToolDef(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得用户自定义工具")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:tool-def:query')")
    public CommonResult<AgentToolDefRespVO> getToolDef(@RequestParam("id") Long id) {
        AgentToolDefDO toolDef = toolDefService.getToolDef(id);
        // 🔴 config 只含 {code,mode}，没有凭据（HTTP 来源已删除），故无需脱敏
        return success(BeanUtils.toBean(toolDef, AgentToolDefRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得用户自定义工具分页")
    @PreAuthorize("@ss.hasPermission('agent:tool-def:query')")
    public CommonResult<PageResult<AgentToolDefRespVO>> getToolDefPage(@Valid AgentToolDefPageReqVO pageReqVO) {
        PageResult<AgentToolDefDO> pageResult = toolDefService.getToolDefPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, AgentToolDefRespVO.class));
    }

    @GetMapping("/data-interface-list")
    @Operation(summary = "获得可关联的数据接口目录（自定义工具的下拉选项）")
    @Parameter(name = "keyword", description = "关键字，模糊匹配接口编码/名称", example = "order")
    @PreAuthorize("@ss.hasPermission('agent:tool-def:query')")
    public CommonResult<List<DataInterfaceSimpleRespDTO>> getBindableInterfaceList(
            @RequestParam(value = "keyword", required = false) String keyword) {
        return success(toolDefService.listBindableInterfaces(keyword));
    }

    // ==================== 内置（已注册）工具在线维护（2026-09-28 增量） ====================

    @GetMapping("/registered-list")
    @Operation(summary = "获得内置工具列表（合并生效值 + 代码默认值，供在线维护）")
    @PreAuthorize("@ss.hasPermission('agent:tool-def:query')")
    public CommonResult<List<AgentRegisteredToolRespVO>> getRegisteredToolList() {
        return success(toolOverrideService.listMerged());
    }

    @PutMapping("/registered-update")
    @Operation(summary = "维护内置工具（整份提交覆盖，改完立即生效、无需重启）")
    @PreAuthorize("@ss.hasPermission('agent:tool-def:update')")
    public CommonResult<Boolean> updateRegisteredTool(@Valid @RequestBody AgentRegisteredToolUpdateReqVO updateReqVO) {
        toolOverrideService.update(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/registered-reset")
    @Operation(summary = "恢复内置工具默认（删覆盖行，生效值退回代码声明）")
    @Parameter(name = "toolName", description = "工具名", required = true, example = "datamodel_create")
    @PreAuthorize("@ss.hasPermission('agent:tool-def:update')")
    public CommonResult<Boolean> resetRegisteredTool(@RequestParam("toolName") String toolName) {
        toolOverrideService.reset(toolName);
        return success(true);
    }

}
