package cn.enlev.ainb.module.agent.framework.agent.core.tool;

import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentSecurityGateway;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_NOT_FOUND;

/**
 * 工具执行器
 *
 * <b>所有工具调用的唯一入口</b>，把三件必须一起发生的事固定在一处：
 * <ol>
 *   <li>过权限网关（六步断言，fail-close）</li>
 *   <li>用快照显式重建身份上下文</li>
 *   <li>执行工具</li>
 * </ol>
 *
 * <h3>为什么要收口到一个执行器</h3>
 * 这三件事若散落在各个工具实现里，必然有人漏写其中一步 ——
 * 而漏掉网关是无声的越权、漏掉上下文重建是无声的跨租户泄漏。
 * 收口之后，工具实现只管做事，「进得来就说明已经过了闸门」。
 *
 * <h3>每次调用都重新校验</h3>
 * 网关在<b>每一次</b>执行前都跑一遍，不缓存判定结果。
 * Plan 从生成到确认之间可能隔着几分钟，期间权限完全可能变。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class AgentToolExecutor {

    @Resource
    private AgentSecurityGateway gateway;
    @Resource
    private AgentToolRegistry toolRegistry;
    /**
     * 用户自定义工具目录（增量5a）
     *
     * <p>仅当 {@link #toolRegistry} 查不到工具名时，才回落到这里现场构造执行体（如 HTTP 工具）。
     * 代码工具路径逐字节不变（registry-first）。
     */
    @Resource
    private AgentToolDefService toolDefService;

    /**
     * 执行一个工具
     *
     * @param context  执行上下文快照（必须在 Servlet 线程上捕获）
     * @param toolName 工具名
     * @param params   参数，可为 null
     * @return 执行结果
     */
    public Object execute(AgentExecutionContext context, String toolName, Map<String, Object> params) {
        long begin = System.currentTimeMillis();
        try {
            // 🔴 校验与执行<b>一起</b>放进重建后的上下文里，顺序仍是「先闸门、后执行」。
            //
            // 最初的写法是先在<b>当前线程</b>过网关、再 context.execute 执行工具。
            // 那样有个隐藏前提：当前线程本来就有身份上下文。Servlet 线程上成立，
            // 所以一直没暴露；而网关自己要做两件依赖上下文的事 ——
            //   · 第 4 步查 agent_config（分租户表，没有 tenantId 直接
            //     「TenantContextHolder 不存在租户编号！」）
            //   · 第 6 步 hasPermission 读的是 SecurityContext 这个 ThreadLocal
            // 于是从预热线程池上调用时，工具还没跑就先炸在网关里，
            // 而且报的是数据库异常，跟真实原因（线程上没有身份）毫无关系。
            //
            // 实测见 /agent/diagnostic/sse-tool-context 与
            // script/verify-ai-permission/verify_stream_context.py。
            // 生产路径当时没出事，只是因为调用方（AgentAssistantServiceImpl#runToolLoop）
            // 自己在外面包了一层 context.execute —— 那是<b>调用方的自觉</b>，
            // 不是执行器的保证。本类的存在意义就是不依赖调用方的自觉。
            Object result = context.execute(() -> {
                // 1. 先过闸门。任一条不满足直接抛异常终止，不降级
                gateway.check(context, toolName);

                // 🔴 沙箱 mock 卡点（流程编排「沙箱测试」）：闸门照过（权限语义不放宽），
                // 但凡风险等级非 READ_ONLY 的工具，一律返回占位结果、绝不调用 function.execute ——
                // 写操作在沙箱里不产生任何真实副作用。只读工具与大模型（不走本执行器）仍真实执行。
                // 卡在这里而非 ToolNodeExecutor，是因为 agent_loop 子图的工具调用也汇聚到本入口，不会绕过。
                if (context.isSandbox() && isNonReadOnly(toolName)) {
                    log.info("[execute][沙箱 mock 写操作工具({}) 用户({})]", toolName, context.getUserId());
                    return mockResult(toolName, params);
                }

                // registry-first：代码工具优先；查不到才回落到用户自定义工具目录（增量5a）。
                // 网关已校验过「存在 + 启用 + 权限」，这里只负责拿到执行体。
                AgentToolRegistry.ToolDefinition definition = toolRegistry.get(toolName);
                AgentToolFunction function = (definition != null)
                        ? definition.getFunction()
                        : toolDefService.buildFunction(toolName);
                if (function == null) {
                    // 代码工具「只有声明没有执行体」（测试用注册），或目录里也查不到 → 工具不存在
                    throw exception(AGENT_TOOL_NOT_FOUND, toolName);
                }
                // 2~3. 执行。无论当前在哪个线程（Servlet、调度器、还是自建线程池），
                // 身份都是显式贴上去的
                return function.execute(params == null ? Map.of() : params);
            });
            log.info("[execute][工具({}) 用户({}) 租户({}) 耗时({}ms)]",
                    toolName, context.getUserId(), context.getTenantId(),
                    System.currentTimeMillis() - begin);
            return result;
        } catch (Exception ex) {
            // 失败也要留痕：工具执行失败是 AI 自修正的输入，不能静默吞掉
            log.warn("[execute][工具({}) 用户({}) 执行失败：{}]",
                    toolName, context.getUserId(), ex.getMessage());
            throw ex;
        }
    }

    /**
     * 工具是否「非只读」（写操作 / 不可逆）—— 沙箱据此决定 mock
     *
     * <p>registry-first：代码工具取 {@link AgentToolRegistry.ToolDefinition#getDeclaredRisk()}；
     * 查不到再回落到自定义工具目录的 {@code GatewayView.riskLevel()}。
     * 两处都拿不到风险等级时<b>保守当作非只读</b>（宁可多 mock，不可漏 mock 真跑出副作用）。
     *
     * <p>🔴 内置工具这一支读<b>声明</b>值而不是 {@code getRisk()}（租户生效值），理由就是上面那句
     * 「宁可多 mock」：{@code agent_tool_override} 一行数据能把 IRREVERSIBLE 压成 READ_ONLY，
     * 压完之后按生效值判定就会让沙箱<b>真的去建表</b> —— 沙箱的全部承诺是「不产生真实副作用」，
     * 而这个承诺不该由一行可在线维护的数据来决定。与其余几道只读闸同口径。
     */
    private boolean isNonReadOnly(String toolName) {
        AgentToolRegistry.ToolDefinition definition = toolRegistry.get(toolName);
        if (definition != null) {
            RiskLevel risk = definition.getDeclaredRisk();
            return risk == null || risk != RiskLevel.READ_ONLY;
        }
        AgentToolDefService.GatewayView catalogTool = toolDefService.resolveForGateway(toolName);
        if (catalogTool == null) {
            return true;
        }
        RiskLevel risk = catalogTool.riskLevel();
        return risk == null || risk != RiskLevel.READ_ONLY;
    }

    /**
     * 沙箱写操作的占位结果：结构化、可被下游节点继续引用，明确标注「已 mock」
     *
     * <p>下游 {@code {{$.var}} 引用取到的就是这张 Map}，不会因 null 断链；前端据 {@code __sandboxMock}
     * 把该节点标成「写操作已跳过」。
     */
    private Object mockResult(String toolName, Map<String, Object> params) {
        Map<String, Object> mock = new LinkedHashMap<>();
        mock.put("__sandboxMock", true);
        mock.put("tool", toolName);
        mock.put("message", "沙箱已跳过该写操作，未产生任何真实副作用");
        mock.put("params", params == null ? Map.of() : params);
        return mock;
    }

}
