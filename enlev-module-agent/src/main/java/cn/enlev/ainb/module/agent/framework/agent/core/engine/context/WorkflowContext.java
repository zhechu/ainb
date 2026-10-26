package cn.enlev.ainb.module.agent.framework.agent.core.engine.context;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.StatePathNavigator;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.Getter;

import java.util.Map;

/**
 * 单次节点执行的上下文门面（DSL §3.3 的 typed facade）
 *
 * <p>把三类东西收口到一个对象，避免 NodeExecutor 的签名散成一长串参数：
 * <ol>
 *   <li><b>SAA state 的 typed 读写</b>（{@link #get}/{@link #getString}/{@link #data}）——
 *       验证④结论是「同构 Map，映射成本 LOW」，本类就是那层薄门面</li>
 *   <li><b>执行态标识</b>（executionId / threadName）——落 trace、关联 checkpoint 用</li>
 *   <li><b>身份快照</b>（{@link AgentExecutionContext}）——节点内调工具/写库时贴回租户身份</li>
 * </ol>
 *
 * <h3>🔴 它是<b>每次节点调用新建</b>的，不是跨节点共享的单例</h3>
 * SAA 的 node lambda 各自收到当前 OverAllState，而 executionId/身份在一次执行内是常量。
 * 编译器在每个节点 lambda 里 new 一个 WorkflowContext（state 绑定当次的，其余从执行级作用域捕获），
 * 这样 {@link #metrics} 天然是「本节点独占」的，不会串到别的节点上。
 *
 * <h3>服务句柄不放这里</h3>
 * ChatModel / Mapper / ToolExecutor 由 Spring 注入到各 NodeExecutor（它们是 bean），
 * <b>绝不进 state、也不进本类</b> —— 否则 checkpoint 序列化会失败（DSL §3.2 约束）。
 *
 * @author 走进新时代
 */
@Getter
public class WorkflowContext {

    private final OverAllState state;
    private final Long executionId;
    private final String threadName;
    private final AgentExecutionContext agentContext;

    /**
     * 本节点的度量，执行器填充、trace 收尾读取
     */
    private final NodeMetrics metrics = new NodeMetrics();

    public WorkflowContext(OverAllState state, Long executionId, String threadName,
                           AgentExecutionContext agentContext) {
        this.state = state;
        this.executionId = executionId;
        this.threadName = threadName;
        this.agentContext = agentContext;
    }

    /**
     * 读顶层变量，不存在返回 null
     */
    public Object get(String key) {
        return state.value(key).orElse(null);
    }

    /**
     * 读顶层变量并转型，不存在或类型不符返回 null
     */
    public <T> T get(String key, Class<T> type) {
        return state.value(key, type).orElse(null);
    }

    /**
     * 读字符串变量，不存在返回 null。非字符串按 toString 兜底
     */
    public String getString(String key) {
        Object v = get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 读字符串变量，不存在返回缺省值
     */
    public String getString(String key, String def) {
        String v = getString(key);
        return v == null || v.isEmpty() ? def : v;
    }

    /**
     * 整个 state 的只读视图
     */
    public Map<String, Object> data() {
        return state.data();
    }

    /**
     * 按 JSONPath-lite 读嵌套值（DSL §3.2：{@code $.a.b}、{@code arr[-1]}），取不到返回 null。
     *
     * <p>委托 {@link StatePathNavigator}，与模板占位符 {@code {{...}}} 共用同一套导航语义 ——
     * 「读一个嵌套上下文值」在模板里和在节点代码里应当行为一致，不该有两套解析。
     */
    public Object getByPath(String path) {
        return StatePathNavigator.navigate(state.data(), path);
    }

    public Long tenantId() {
        return agentContext == null ? null : agentContext.getTenantId();
    }

    public Long userId() {
        return agentContext == null ? null : agentContext.getUserId();
    }

}
