package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.StateTemplateResolver;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolExecutor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;

/**
 * {@code tool} 节点执行器：调用工具目录（DSL §4.4）
 *
 * <h3>🔴 一切工具调用都过 {@link AgentToolExecutor}，绝不直连工具实现</h3>
 * 那个执行器是「权限网关六步断言 + 身份快照重建 + 执行」三件事的唯一收口。
 * 绕过它 = 绕过 fail-close 的权限校验，是无声的越权。Workflow 的 tool 节点与助手聊天
 * 走同一条闸门，这正是「Plan 即 Workflow、共用一套工具与权限」的意义。
 *
 * <h3>args 的解析</h3>
 * DSL 的 {@code args} 是「参数名 → 取值模板」。每个值经 {@link StateTemplateResolver} 做
 * {@code {{...}}} 替换；P2 增量1 起支持嵌套路径引用（{@code {{$.contract.partyA}}}、{@code {{$.arr[-1]}}}）。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class ToolNodeExecutor implements NodeExecutor {

    public static final String TYPE = "tool";
    /**
     * 未配 {@code outputVar} 时工具返回结果落进的 state 键
     *
     * <p>🔴 public 是为了让 {@code NodeIoContract} 引用它而不是抄一份字面量。抄的那份曾经写着
     * {@code defaultName=null}（= 必须用户命名），于是所有「读契约做分析」的代码都看不见这个默认名：
     * {@code WorkflowDslValidator} 建 producers 表时漏掉它，合法的 {@code {{$.tool_result}}} 被误报成
     * 悬空引用；污点溯源同理漏报。常量内联进契约，不构成运行期依赖。
     */
    public static final String DEFAULT_OUTPUT_VAR = "tool_result";

    @Resource
    private AgentToolExecutor toolExecutor;

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
        String toolId = node.configStr("toolId");
        if (toolId == null || toolId.isEmpty()) {
            throw exception(WORKFLOW_DSL_INVALID, "tool 节点(" + node.getId() + ") 缺少 toolId");
        }
        String outputVar = node.configStr("outputVar", DEFAULT_OUTPUT_VAR);

        // 解析 args：逐个值做模板替换
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> args = node.configMap("args");
        if (args != null) {
            args.forEach((k, v) -> params.put(k,
                    v instanceof String s ? StateTemplateResolver.resolve(s, ctx) : v));
        }

        AgentExecutionContext agentContext = ctx.getAgentContext();
        Object result = toolExecutor.execute(agentContext, toolId, params);
        log.info("[execute][tool 节点({}) 执行({}) 工具({})]", node.getId(), ctx.getExecutionId(), toolId);

        // 结果原样写进 outputVar。⚠️ 必须是 JSON 可序列化值（工具返回 Map/String/标量），
        // 否则 checkpoint 序列化会失败 —— 这是 DSL §3.2 的硬约束
        Map<String, Object> delta = new HashMap<>();
        delta.put(outputVar, result);
        return delta;
    }

}
