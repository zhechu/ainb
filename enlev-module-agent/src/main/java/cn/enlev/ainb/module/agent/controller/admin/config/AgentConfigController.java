package cn.enlev.ainb.module.agent.controller.admin.config;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.module.agent.dal.dataobject.config.AgentConfigDO;
import cn.enlev.ainb.module.agent.dal.dataobject.quota.AgentQuotaDO;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolCatalog;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.service.config.AgentConfigService;
import cn.enlev.ainb.module.agent.service.quota.AgentQuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * AI 助手管理员配置
 *
 * <h3>🔴 工具清单在这里是只读快照，维护入口在「工具管理」页</h3>
 * {@code GET /agent/config/tool-list} 返回的是<b>内置工具 ∪ 当前租户的自定义工具</b>的当前生效值
 * （含展示名/权限/风险/启用/是否需确认），供总开关页与诊断快速一览，也是 {@code agent_def} 编辑器
 * 与流程设计器那两个工具下拉的数据源。它本身<b>不提供修改</b>——内置工具的在线维护（改风险/权限/启用等）
 * 已迁到工具管理页（{@code /agent/tool-def/registered-*}，见 {@code AgentToolDefController} 与
 * {@code AgentToolOverrideService}），自定义工具的增删改在同一页的另一半（{@code /agent/tool-def/*}）。
 *
 * <p>历史沿革：早期设计刻意「不给工具开关」，理由是「把权限串/风险等级做成可运行时改写的数据 = 把安全约束交给数据」
 * （想象把不可逆的 {@code datamodel_create} 在界面上改成低风险，它就不再要求用户确认）。
 * 2026-09-28 产品决策推翻了这条底线：<b>内置工具全字段自由维护</b>。为守住安全，放松（降风险/删权限）
 * 虽不拦截，但 {@code AgentToolOverrideService.warnIfRelaxed} 每次 log.warn 留操作人 + 前后值的审计痕迹，
 * 且覆盖按<b>当前租户</b>生效、绝不跨租户污染。详见 {@code AgentToolRegistry} 类注释。
 * ⚠️ 自定义工具<b>没有</b>这套「声明值 vs 生效值」的分裂：它没有覆盖表，{@code agent_tool_def} 那一行
 * 就是它的全部定义，故本端点给它的 {@code risk} 与 {@code declaredRisk} 恒等。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - AI 助手配置")
@RestController
@RequestMapping("/agent/config")
@Validated
public class AgentConfigController {

    @Resource
    private AgentConfigService configService;
    @Resource
    private AgentQuotaService quotaService;
    @Resource
    private AgentToolCatalog toolCatalog;

    // ==================== 总开关 ====================

    @GetMapping("/get")
    @Operation(summary = "获得 AI 助手的租户级配置")
    @PreAuthorize("@ss.hasPermission('agent:config:query')")
    public CommonResult<Map<String, Object>> getConfig() {
        AgentConfigDO config = configService.getConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        // 从未配置过 = 未启用。这里如实返回 false 而不是 null，
        // 免得前端把「没配过」渲染成一个半开半关的开关
        result.put("enabled", config != null && Boolean.TRUE.equals(config.getEnabled()));
        result.put("remark", config == null ? "" : config.getRemark());
        result.put("configured", config != null);
        result.put("updateTime", config == null ? null : config.getUpdateTime());
        result.put("updater", config == null ? null : config.getUpdater());
        return success(result);
    }

    @PutMapping("/update")
    @Operation(summary = "开启或关闭 AI 助手")
    @PreAuthorize("@ss.hasPermission('agent:config:update')")
    public CommonResult<Boolean> updateConfig(@Valid @RequestBody ConfigUpdateReqVO reqVO) {
        configService.updateEnabled(reqVO.getEnabled(), reqVO.getRemark());
        return success(true);
    }

    @Data
    public static class ConfigUpdateReqVO {

        @jakarta.validation.constraints.NotNull(message = "启用状态不能为空")
        private Boolean enabled;

        /**
         * 备注：记录开启的缘由与范围
         *
         * 不是可有可无的装饰 —— AI 助手能改库里的表和流程，
         * 「谁在什么时候因为什么把它打开了」是需要能回答的问题。
         */
        private String remark;

    }

    // ==================== 工具清单（只读快照，维护入口在工具管理页） ====================

    @GetMapping("/tool-list")
    @Operation(summary = "工具的当前生效值快照（内置 ∪ 自定义；只读，在线维护走 /agent/tool-def/registered-*）")
    @PreAuthorize("@ss.hasPermission('agent:config:query')")
    public CommonResult<List<Map<String, Object>>> toolList() {
        List<Map<String, Object>> result = new ArrayList<>();
        // 🔴 取的是 AgentToolCatalog（内置注册表 ∪ 当前租户 agent_tool_def），不是 registry.list()（B2）。
        //    本端点有三个消费方：本页那屏只读快照、agent_def 编辑器的「绑定工具」下拉、
        //    流程设计器 agent_loop.tools 的下拉。此前只列内置，于是自定义工具<b>在下拉里压根不出现</b>，
        //    而运行期的网关与执行器早就支持它 —— 用户看不出是「不能绑」还是「界面没给」，
        //    只能去反复检查那个工具是不是没登记成功。
        //    ⚠️ 与后端发布闸（AgentDefServiceImpl#validateToolKeys）必须是同一份合流结果，
        //    否则就是「界面指反方向」：下拉里没有的东西发布闸却认、或下拉里给的东西发布时才被拒。
        for (AgentToolCatalog.ToolView tool : toolCatalog.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name());
            item.put("displayName", tool.displayName());
            // 分组名此前没下发，前端只能自己按工具名前缀猜 —— 猜不出来的就全堆在一个平铺列表里。
            // 名称目录（/agent/workflow-def/name-catalog）也带这一列，两处同源同一个 getter，不会漂移。
            item.put("groupName", tool.groupName());
            item.put("description", tool.description());
            item.put("permissions", tool.permissions());
            RiskLevel effectiveRisk = tool.effectiveRisk();
            item.put("risk", effectiveRisk.getLevel());
            item.put("riskDescription", effectiveRisk.getDescription());
            item.put("enabled", tool.enabled());
            item.put("requireConfirm", tool.requireConfirm());
            item.put("overridden", tool.overridden());
            /*
             * 🔴 声明风险三件套（D4-22）。本端点的定位是「生效值快照」，AI 配置管理页照原样展示没问题；
             * 但它还有第二个消费方 —— agent_def 编辑页拿 requireConfirm 过滤「只读可用工具」，
             * 而后端那道闸（AgentDefServiceImpl 与发布期校验 V3/V11）读的是<b>声明</b>值。
             * 降过险的工具于是会被前端放进下拉、点保存/发布时才被拒 —— 界面指了反方向。
             * 修法不是把 risk 改成声明值（那会让本页的「生效值快照」名不副实），
             * 而是两份都给、让预测闸门行为的那一方自己去读 declared*。
             * declaredRequireConfirm 也不能复用生效值那个：两者走的是不同的 getter。
             * 🔴 自定义工具没有这个分裂（它没有覆盖表，目录行<b>就是</b>它的声明），故两份恒等、
             *    riskOverridden 恒 false、overridden 恒 false。
             */
            RiskLevel declaredRisk = tool.declaredRisk();
            item.put("declaredRisk", declaredRisk.getLevel());
            item.put("declaredRiskDescription", declaredRisk.getDescription());
            item.put("declaredRequireConfirm", declaredRisk.isRequireConfirm());
            item.put("riskOverridden", tool.riskOverridden());
            // 🔴 这两列是 B2 新加的：自定义工具进了同一份清单之后，前端得能区分「这是代码里的工具」
            //    与「这是我在工具管理页登记的」。source 取 BUILTIN 或 agent_tool_def.source 的值，
            //    与名称目录 AgentNameCatalogRespVO.ToolItem#source 同一个取值域。
            //    executable=false（source=FORM/SCRIPT 预留位）的行<b>照样下发</b>但不该被放进可选下拉 ——
            //    藏起来的话存量绑定就退化成一串裸机器键（D4-25 那条取舍），故交给前端置灰并说明原因。
            item.put("source", tool.source());
            item.put("executable", tool.executable());
            result.add(item);
        }
        return success(result);
    }

    // ==================== 配额 ====================

    @GetMapping("/quota/list")
    @Operation(summary = "配额列表")
    @PreAuthorize("@ss.hasPermission('agent:quota:query')")
    public CommonResult<List<AgentQuotaDO>> quotaList() {
        return success(quotaService.listQuotas());
    }

    @PostMapping("/quota/save")
    @Operation(summary = "新增或更新配额")
    @PreAuthorize("@ss.hasPermission('agent:quota:update')")
    public CommonResult<Long> saveQuota(@Valid @RequestBody AgentQuotaDO quota) {
        return success(quotaService.saveQuota(quota));
    }

    @DeleteMapping("/quota/delete")
    @Operation(summary = "删除配额（该维度恢复为不限制）")
    @PreAuthorize("@ss.hasPermission('agent:quota:update')")
    public CommonResult<Boolean> deleteQuota(@RequestParam("id") Long id) {
        quotaService.deleteQuota(id);
        return success(true);
    }

}
