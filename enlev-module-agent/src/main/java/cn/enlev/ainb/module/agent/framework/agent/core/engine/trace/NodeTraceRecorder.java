package cn.enlev.ainb.module.agent.framework.agent.core.engine.trace;

import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentNodeExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentNodeExecutionMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentTextUtils;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.NodeMetrics;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 节点级 trace 记录器：把每次节点执行写进 {@code agent_node_execution}
 *
 * <h3>🔴 为什么 trace 落平台自己的表，而不是读 SAA 的 GRAPH_CHECKPOINT</h3>
 * checkpoint 的 state_data 是不透明二进制（Java 序列化+deflate+Base64），<b>无法按业务字段 SQL 查询</b>，
 * 也没有 tenant_id 列。「按用户查执行历史」「按节点归因 token」「Saga 找前像反做」都需要可查询的结构化行，
 * 所以平台另设本表作为 trace 事实来源，GRAPH_* 只当引擎的不透明续跑机制（DSL §6.2）。
 *
 * <h3>token 归因只信执行器填的 metrics，不裸读 NodeOutput.tokenUsage()</h3>
 * 后者在 GraphRunnerContext 上粘滞（非 LLM 节点会继承上一个 LLM 节点的 usage，SPIKE SR-2）。
 * 本记录器只读 {@link NodeMetrics} —— 由真正调模型的执行器在拿到厂商 usage 时填，从根上避开粘滞。
 *
 * <h3>写库一律 context.execute 贴回身份</h3>
 * 节点虽在同步订阅线程执行（租户本可见），但显式重建身份是「不依赖调用方自觉」的兜底，
 * 与 {@code AgentToolExecutor} 同一条纪律：哪天 SAA 把节点挪到别的线程，这里也不会写出脏租户数据。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class NodeTraceRecorder {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILURE = "FAILURE";
    public static final String STATUS_SKIPPED = "SKIPPED";

    /** 与 agent_node_execution.error_msg 列宽一致 */
    private static final int ERROR_MSG_MAX_LENGTH = 1000;

    /** 与 agent_node_execution.node_id / parent_node_id 列宽一致（子图命名空间 id 可能超，见 truncate） */
    private static final int NODE_ID_MAX_LENGTH = 64;

    /** 与 agent_node_execution.node_name / tool_id 列宽一致：两者都是自由文本（用户在 DSL 里起的名字 / 工具标识） */
    private static final int NAME_MAX_LENGTH = 128;

    /** 沙箱执行时 begin 返回的哨兵 traceId（不落库，success/failure 亦被 isSandbox 守卫，不会用它写库） */
    private static final Long SANDBOX_SENTINEL_TRACE_ID = -1L;

    @Resource
    private AgentNodeExecutionMapper nodeExecutionMapper;

    /** 当前节点执行是否处于沙箱（设计器「沙箱测试」）—— 是则 trace 一律不落库 */
    private static boolean isSandbox(WorkflowContext ctx) {
        return ctx != null && ctx.getAgentContext() != null && ctx.getAgentContext().isSandbox();
    }

    /**
     * 节点开始：插一行 RUNNING，返回行 id 供收尾更新
     */
    public Long begin(WorkflowContext ctx, NodeDTO node) {
        return begin(ctx, node, node.getId(), null);
    }

    /**
     * 节点开始（可指定落库的 effective node id 与所属父节点）
     *
     * <p>子图（sub_workflow / iteration / agent_loop）节点用 {@code <父id>-<childId>} 命名空间：子图节点以<b>父</b>
     * executionId 落 trace，若子/父有同名节点（都叫 {@code llm1} 很常见）则 {@code (execution_id, node_id)}
     * 撞行、{@code selectMaxAttempt} 归因会混。分隔符 {@code -} 对齐 SAA 子图的 {@code formatId}（{@code %s-%s}），
     * 使 trace 的 node_id 与父 SSE 里上浮的子节点事件 id 一致。
     *
     * <p>🔴 {@code parentNodeId} 必须由调用方<b>显式</b>传（迭代六 B5-1 / 裁定 A-Ⅱ：加列，不继续用
     * 字符串命名空间），不从 {@code effectiveNodeId} 里切出来。切分那套做法绑死两件事：
     * 分隔符永远是 {@code -}；以及 {@code effectiveNodeId} 没被 {@link #truncate} 削过 ——
     * DSL 节点 id 上限 63 字符且不含 {@code -}（{@code ^[a-z][a-z0-9_]{0,62}$}），拼接后最长 127，
     * 而 node_id 列宽只有 64，所以截断真会发生，被削掉的是<b>子 id 那一段</b>
     * （截断后仍能猜出父是谁，却再也说不清是哪个 body 节点）。
     *
     * @param parentNodeId 所属 sub_workflow / iteration / agent_loop 节点的<b>裸</b> id；顶层为 {@code null}
     */
    public Long begin(WorkflowContext ctx, NodeDTO node, String effectiveNodeId, String parentNodeId) {
        // 🔴 沙箱执行不落 trace：设计器「沙箱测试」真跑草稿 DSL，但绝不能往 agent_node_execution 写脏行
        //（executionId 是合成的 -1，落库会污染执行历史/归因）。返回哨兵 -1，success/failure 同样被守卫，
        // 不会拿这个哨兵去 updateById。
        if (isSandbox(ctx)) {
            return SANDBOX_SENTINEL_TRACE_ID;
        }
        AgentNodeExecutionDO trace = new AgentNodeExecutionDO();
        trace.setExecutionId(ctx.getExecutionId());
        trace.setNodeId(truncate(effectiveNodeId, NODE_ID_MAX_LENGTH, "节点 id"));
        trace.setParentNodeId(truncate(parentNodeId, NODE_ID_MAX_LENGTH, "父节点 id"));
        // 迭代六 D2：显示名与工具标识都在这一刻可得（node 就是当时那份 DSL 的节点），过了就只剩 id。
        // 🔴 node_name 是快照，流程改名不回填 —— 这是刻意的：trace 要还原的是「当时跑的是什么」
        trace.setNodeName(truncate(node.getName(), NAME_MAX_LENGTH, "节点名"));
        // 🔴 只对声明了 toolId 的节点有值（今天只有 tool 节点用它，ToolNodeExecutor 还强校验非空）。
        //    刻意不按 node.getType() 做白名单：哪天别的节点类型也带 toolId，这里不用改
        trace.setToolId(truncate(node.configStr("toolId"), NAME_MAX_LENGTH, "工具标识"));
        trace.setNodeType(node.getType());
        trace.setStatus(STATUS_RUNNING);
        // P1 不做重试循环（retry 是 P2），attempt 固定 1；字段先留着，加列容易回填难
        trace.setAttempt(1);
        trace.setPromptTokens(0);
        trace.setCompletionTokens(0);
        trace.setTotalTokens(0);
        trace.setTokenSource("ESTIMATED");
        trace.setInputSnapshot(toJson(ctx.data()));
        trace.setBeginTime(LocalDateTime.now());
        // 反做三件套：仅声明了 compensation 的节点填，供 Saga 逆序撤销用
        Map<String, Object> compensation = node.getCompensation();
        if (compensation != null) {
            trace.setCompensateRef(str(compensation.get("compensate_ref")));
            trace.setBeforeImage(toJson(compensation.get("before_image")));
            trace.setIdempotencyKey(str(compensation.get("idempotency_key")));
        }
        return insert(ctx, trace);
    }

    /**
     * 节点成功：回填度量、耗时、输出快照
     */
    public void success(Long traceId, WorkflowContext ctx, Map<String, Object> delta, long durationMs) {
        if (isSandbox(ctx)) {
            return;
        }
        AgentNodeExecutionDO update = new AgentNodeExecutionDO();
        update.setId(traceId);
        update.setStatus(STATUS_SUCCESS);
        update.setOutputSnapshot(toJson(delta));
        update.setDurationMs((int) durationMs);
        update.setEndTime(LocalDateTime.now());
        NodeMetrics metrics = ctx.getMetrics();
        update.setModelId(metrics.getModelId());
        update.setPromptTokens(nvl(metrics.getPromptTokens()));
        update.setCompletionTokens(nvl(metrics.getCompletionTokens()));
        update.setTotalTokens(nvl(metrics.getTotalTokens()));
        update.setTokenSource(metrics.getTokenSource());
        updateById(ctx, update);
    }

    /**
     * 节点失败：回填耗时与原因
     */
    public void failure(Long traceId, WorkflowContext ctx, long durationMs, Throwable ex) {
        if (isSandbox(ctx)) {
            return;
        }
        AgentNodeExecutionDO update = new AgentNodeExecutionDO();
        update.setId(traceId);
        update.setStatus(STATUS_FAILURE);
        update.setDurationMs((int) durationMs);
        update.setEndTime(LocalDateTime.now());
        // 🔴 必须用 truncateForColumn 而不是 StrUtil.maxLength：后者返回 n+3 个字符，
        //    写 varchar(1000) 的列会撞 Data truncation。实测炸过 —— save_draft 校验失败时
        //    错误文本很长，写不进去，整个执行被一条 DataIntegrityViolationException 收尾，
        //    真正的失败原因（哪个控件不合法）反而一个字都没留下
        update.setErrorMsg(AgentTextUtils.truncateForColumn(
                ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                ERROR_MSG_MAX_LENGTH));
        updateById(ctx, update);
    }

    private Long insert(WorkflowContext ctx, AgentNodeExecutionDO trace) {
        ctx.getAgentContext().execute(() -> {
            nodeExecutionMapper.insert(trace);
            return null;
        });
        return trace.getId();
    }

    private void updateById(WorkflowContext ctx, AgentNodeExecutionDO update) {
        ctx.getAgentContext().execute(() -> {
            nodeExecutionMapper.updateById(update);
            return null;
        });
    }

    private static Integer nvl(Integer v) {
        return v == null ? 0 : v;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 超列宽时截断并留 WARN：保留前缀（仍能定位到是哪个节点/哪个工具），比撞行或整条 INSERT 失败更可取。
     *
     * <p>node_id 之所以会超：子图命名空间 id {@code <父id>-<childId>} 两段各可达 63，极端拼接远超 64。
     * node_name / tool_id 是用户与工具注册表可控的自由文本，同理不能假定它们守列宽。
     *
     * <p>🔴 不用 {@code StrUtil.maxLength}：它返回 n+3 个字符（带省略号），写满列宽会撞 Data truncation
     * —— 同一个坑在 error_msg 上真炸过，见 {@link #failure}。也不用
     * {@code AgentTextUtils.truncateForColumn}：它把 null 归一成空串，而这三列要的就是 NULL
     * （空串会让「本来就没有」和「记成了空」在库里长得一样）。
     */
    private static String truncate(String value, int maxLength, String what) {
        if (value != null && value.length() > maxLength) {
            log.warn("[truncate][{}({}) 超过列宽 {}，已截断]", what, value, maxLength);
            return value.substring(0, maxLength);
        }
        return value;
    }

    private static String toJson(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return JSONUtil.toJsonStr(v);
        } catch (Exception ex) {
            // 快照序列化失败不该让节点执行本身失败：trace 是旁路观测，少一份快照可以接受，
            // 把业务流程打断不行
            log.debug("[toJson][快照序列化失败，已跳过：{}]", ex.getMessage());
            return null;
        }
    }

}
