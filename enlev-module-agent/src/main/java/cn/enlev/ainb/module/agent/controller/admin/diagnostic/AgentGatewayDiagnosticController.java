package cn.enlev.ainb.module.agent.controller.admin.diagnostic;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentSecurityGateway;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * 权限网关诊断
 *
 * 让「网关到底拦不拦得住」可以在真实服务上验证，而不只是单测里的 Mock。
 *
 * <p>本控制器同时注册了两个<b>样例工具</b>（不做任何实事，只为走一遍网关）：
 * 一个只读、一个不可逆，权限串分别指向真实存在的权限点。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - AI 助手网关诊断")
@RestController
@RequestMapping("/agent/diagnostic")
@Validated
public class AgentGatewayDiagnosticController {

    @Resource
    private AgentSecurityGateway gateway;
    @Resource
    private AgentToolRegistry toolRegistry;
    @Resource
    private AgentToolExecutor toolExecutor;

    @Data
    public static class ToolInfo {
        private String name;
        private String description;
        private String permission;
        private String risk;
        private boolean enabled;
        private boolean requireConfirm;
    }

    @Data
    public static class CheckResult {
        /** 是否放行 */
        private boolean passed;
        /** 被拒时的原因 */
        private String reason;
        /** 快照里的身份，便于对照 */
        private Long userId;
        private Long tenantId;
    }

    @GetMapping("/tools")
    @Operation(summary = "列出已注册的工具及其权限声明")
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<List<ToolInfo>> tools() {
        return success(toolRegistry.list().stream().map(def -> {
            ToolInfo info = new ToolInfo();
            info.setName(def.getName());
            info.setDescription(def.getDescription());
            // 用逗号连全部权限串而不是只显示主权限：这个端点存在的意义就是回答
            // 「我为什么被拒了」，只显示一个会把「差的是第二个权限」这种情况藏起来
            info.setPermission(String.join(", ", def.getPermissions()));
            info.setRisk(def.getRisk().getLevel());
            info.setEnabled(def.isEnabled());
            info.setRequireConfirm(def.isRequireConfirm());
            return info;
        }).toList());
    }

    @GetMapping("/gateway-check")
    @Operation(summary = "对指定工具跑一遍权限网关，返回放行或拒绝原因")
    @Parameter(name = "tool", description = "工具名", required = true)
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<CheckResult> gatewayCheck(@RequestParam("tool") String tool) {
        // 在 Servlet 线程上捕获快照 —— 这正是生产路径的做法
        AgentExecutionContext context = AgentExecutionContext.snapshot();
        CheckResult result = new CheckResult();
        result.setUserId(context.getUserId());
        result.setTenantId(context.getTenantId());
        try {
            gateway.check(context, tool);
            result.setPassed(true);
        } catch (Exception ex) {
            // 刻意吞掉异常并把原因放进返回值：诊断端点要的是「拦没拦住、为什么」，
            // 抛出去会被全局异常处理器转成错误响应，反而看不清是哪一步拒的
            result.setPassed(false);
            result.setReason(ex.getMessage());
        }
        return success(result);
    }

    @PostMapping("/tool-execute")
    @Operation(summary = "走完整链路执行一个工具：网关校验 → 上下文重建 → 执行")
    @Parameter(name = "tool", description = "工具名", required = true)
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<Object> toolExecute(@RequestParam("tool") String tool,
                                            @RequestBody(required = false) Map<String, Object> params) {
        AgentExecutionContext context = AgentExecutionContext.snapshot();
        return success(toolExecutor.execute(context, tool, params));
    }

}
