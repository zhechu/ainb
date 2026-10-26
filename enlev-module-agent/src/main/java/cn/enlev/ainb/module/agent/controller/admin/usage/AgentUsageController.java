package cn.enlev.ainb.module.agent.controller.admin.usage;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.module.agent.dal.dataobject.usage.AgentUsageLogDO;
import cn.enlev.ainb.module.agent.service.quota.AgentQuotaService;
import cn.enlev.ainb.module.agent.service.usage.AgentUsageLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

/**
 * AI 助手用量看板
 *
 * <h3>🔴 两个权限点是刻意分开的</h3>
 * <ul>
 *   <li>{@code agent:usage:query} —— 看<b>聚合</b>用量：谁用了多少、花在哪类场景上</li>
 *   <li>{@code agent:usage:detail} —— 看<b>输入原文</b>：用户当时具体问了什么</li>
 * </ul>
 * 后者是隐私敏感的。看账单不需要读别人问过什么，
 * 而「为了对账把所有人的提问都翻一遍」是个很容易滑过去的越界。
 * 初期可以都授给租户管理员，但<b>不要合并成一个权限点</b> ——
 * 合了以后再拆要改代码、改授权数据、改前端。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - AI 助手用量")
@RestController
@RequestMapping("/agent/usage")
@Validated
public class AgentUsageController {

    @Resource
    private AgentUsageLogService usageLogService;
    @Resource
    private AgentQuotaService quotaService;

    @GetMapping("/summary")
    @Operation(summary = "用量汇总（可按用户/场景/模型/日期下钻）")
    @Parameter(name = "dimension", description = "分组维度：USER / SCENE / MODEL / DAY")
    @PreAuthorize("@ss.hasPermission('agent:usage:query')")
    public CommonResult<List<AgentUsageLogService.UsageGroupBO>> summary(
            @RequestParam(value = "dimension", defaultValue = "SCENE") String dimension,
            @RequestParam(value = "beginTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime beginTime,
            @RequestParam(value = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @RequestParam(value = "userId", required = false) Long userId) {
        return success(usageLogService.summarize(dimension, beginTime, endTime, userId));
    }

    @GetMapping("/my-quota")
    @Operation(summary = "我的配额使用情况")
    @PreAuthorize("@ss.hasPermission('agent:assistant:use')")
    public CommonResult<List<AgentQuotaService.QuotaUsageBO>> myQuota() {
        // 用助手的权限而不是看板的权限：这是<b>自查</b>，
        // 「我还能问几次」不该要求一个管理权限才看得到
        return success(quotaService.getQuotaUsage(getLoginUserId()));
    }

    @GetMapping("/recent")
    @Operation(summary = "最近的调用明细（含输入原文）")
    @Parameter(name = "limit", description = "条数，最多 200")
    @PreAuthorize("@ss.hasPermission('agent:usage:detail')")
    public CommonResult<List<Map<String, Object>>> recent(
            @RequestParam(value = "limit", defaultValue = "50") Integer limit) {
        List<AgentUsageLogDO> logs = usageLogService.getRecentLogs(limit);
        List<Map<String, Object>> result = new ArrayList<>(logs.size());
        for (AgentUsageLogDO log : logs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("userId", log.getUserId());
            item.put("scene", log.getScene());
            item.put("modelName", log.getModelName());
            item.put("totalTokens", log.getTotalTokens());
            item.put("tokenSource", log.getTokenSource());
            item.put("cost", log.getCost());
            item.put("status", log.getStatus());
            item.put("durationMs", log.getDurationMs());
            item.put("beginTime", log.getBeginTime());
            item.put("inputText", log.getInputText());
            result.add(item);
        }
        return success(result);
    }

}
