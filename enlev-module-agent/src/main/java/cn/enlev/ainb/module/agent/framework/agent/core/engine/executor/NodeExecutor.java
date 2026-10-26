package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;

import java.util.Map;

/**
 * 节点执行器 SPI
 *
 * <p>一种 DSL 节点类型对应一个实现（llm / tool / condition.rule / condition.llm / hitl.confirm ...）。
 * 编译器按 {@link #getType()} 从 {@link NodeExecutorRegistry} 取对应执行器，包成 SAA 的 node action。
 *
 * <h3>🔴 执行线程与租户</h3>
 * SAA {@code stream()} 在<b>订阅线程同步执行</b>节点（验证②），而订阅发生在
 * Service 用 {@code context.execute(...)} 贴好身份的 boundedElastic 线程上 ——
 * 所以 {@code execute} 内 ThreadLocal 里的租户/身份天然可见，可直接读写分租户表。
 * 唯一例外：若执行器内部再订阅异步流（如 ChatModel 的 token 流跑到 Netty 线程），
 * 须在回调里显式贴回租户（SPIKE SR-1）。P1 的 llm 节点走<b>同步</b> {@code AiModelApi.chat}，不触发该风险。
 *
 * <h3>返回值即 state delta</h3>
 * 返回的 Map 由 SAA 按 KeyStrategy 合并进 OverAllState。只能放 JSON 可序列化值，
 * 服务句柄绝不入内（否则 checkpoint 序列化失败）。度量（token/modelId）走
 * {@link WorkflowContext#getMetrics()}，不进 delta。
 *
 * @author 走进新时代
 */
public interface NodeExecutor {

    /**
     * 条件节点把选中的路由键写进 state 时用的键前缀。
     *
     * <p>为什么条件节点要「先写路由键、再由条件边读」而不是直接在边里算：
     * SAA 的条件边（EdgeAction）应当是<b>廉价、纯读</b>的；而 condition.llm 要调模型、
     * condition.rule 要评估规则，还可能要记 trace 与 token。把这些放进「节点执行」里，
     * 边只负责读节点算好的结果 —— 职责清晰，也让 condition.llm 的 token 能正常归因。
     */
    String ROUTE_KEY_PREFIX = "__route_";

    /**
     * 条件节点没有任何分支命中时写入的路由键。
     *
     * <p>编译器会把它映射到条件边的 {@code default} 目标节点。单独一个哨兵值而不是直接写默认目标，
     * 是为了让「路由决策」（执行器）与「路由键→节点的映射」（编译器的 routeMap）解耦：
     * 执行器不需要知道目标节点 id，只管说「都不匹配，走默认」。
     */
    String DEFAULT_ROUTE = "__default__";

    /**
     * 某条件节点对应的路由键名
     */
    static String routeKey(String nodeId) {
        return ROUTE_KEY_PREFIX + nodeId;
    }

    /**
     * 支持的节点类型，与 DSL 的 {@code node.type} 一致
     */
    String getType();

    /**
     * 执行节点
     *
     * @param node DSL 节点定义（含 config）
     * @param ctx  本次节点执行的上下文（state 门面 + 执行态标识 + 身份 + 度量）
     * @return state delta（要合并回上下文的键值）
     * @throws Exception 执行失败抛出，由编译器包装层记 trace 后向上冒泡（SAA 会终止 stream）
     */
    Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) throws Exception;

}
