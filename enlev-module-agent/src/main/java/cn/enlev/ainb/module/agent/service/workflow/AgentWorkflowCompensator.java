package cn.enlev.ainb.module.agent.service.workflow;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentNodeExecutionDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentNodeExecutionMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowExecutionStatusEnum;
import cn.enlev.ainb.module.agent.enums.WorkflowRecoveryEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.StateTemplateResolver;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolExecutor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saga 逆序补偿调度器（设计方案「一之三」第 2 条 / DSL §6.3 / 待办 #5）
 *
 * <h3>它解决什么</h3>
 * 工作流失败时，已经跑成功的副作用节点（插了记录、发了 HTTP）不会自动撤销 —— 本平台<b>不承诺 ACID 回滚</b>。
 * 只有显式声明 {@code recovery: compensate} 的流程，失败后才由本调度器按 {@code agent_node_execution} 的
 * <b>逆序</b>逐个调用 {@code compensate_ref} 指向的工具做「语义反做」。这是<b>尽力而为</b>的撤销，不是事务 rollback。
 *
 * <h3>🔴 三条不可动摇的口径</h3>
 * <ol>
 *   <li><b>逆序</b>：后做的先撤（{@link AgentNodeExecutionMapper#selectCompensableDesc}，id DESC）。
 *       正序撤会出现「先删 A 才发现 B 依赖 A」的悬空。</li>
 *   <li><b>只撤跑成功的</b>：仅 {@code status=SUCCESS} 且 {@code compensate_ref} 非空的节点纳入。
 *       失败/跳过节点的副作用未知，保守不动（宁可漏撤一个未完成的，不误撤一个没发生的）。</li>
 *   <li><b>尽力而为、不中途放弃</b>：单个补偿失败只记录、继续撤后面的（能撤多少撤多少），
 *       最终只要有一个失败就落 {@code COMPENSATION_FAILED}（= 有写操作生效又没撤干净，必须人工核对，
 *       见 {@link WorkflowExecutionStatusEnum} 对该终态的说明）。</li>
 * </ol>
 *
 * <h3>幂等</h3>
 * 触发点 {@code settle()} 有 settle-once CAS 保证「一次执行只补偿一次」；补偿动作本身可能因工具重放而重复，
 * 故把 {@code idempotency_key}（按 state 解析后）作为保留参数 {@value #IDEMPOTENCY_PARAM} 透传给工具，
 * 由工具侧去重（删除类操作天然幂等）。本调度器<b>不</b>自建去重表 —— 那超出 MVP，且删除按 id 本就幂等。
 *
 * <h3>身份与线程</h3>
 * 全程包在 {@code context.execute} 里贴回租户身份（与 {@code NodeTraceRecorder} 同纪律）；工具调用再经
 * {@link AgentToolExecutor} 的六步权限闸门 —— 补偿走的仍是「唯一工具入口」，不绕权限。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentWorkflowCompensator {

    /** 透传给补偿工具的保留参数名：解析后的幂等键（工具侧据此去重重放） */
    public static final String IDEMPOTENCY_PARAM = "__idempotencyKey";

    @Resource
    private AgentToolExecutor toolExecutor;
    @Resource
    private AgentNodeExecutionMapper nodeExecutionMapper;
    @Resource
    private AgentWorkflowExecutionMapper executionMapper;

    /**
     * 对一次已 FAILED 的执行做逆序补偿。调用方（{@code settle()}）已保证 {@code recovery==COMPENSATE} 且只调一次。
     *
     * @return 补偿结果（总数 / 成功数 / 失败数 / 终态），供调用方记日志与单测断言
     */
    public CompensationResult compensate(AgentWorkflowExecutionDO exec, AgentExecutionContext context) {
        Long executionId = exec.getId();
        return context.execute(() -> {
            // 1. FAILED → COMPENSATING（让看板/轮询一眼看出「正在回滚」，而不是停在 FAILED）
            updateStatus(executionId, WorkflowExecutionStatusEnum.COMPENSATING.getStatus(), null);

            // 2. 取最新执行行：variable_snapshot 是「最后一个成功节点后的全量 state」，作为前像模板的解析基准。
            //    （settle 走 FAILED 分支时不覆盖 snapshot，故库里存的是 updateCurrentNode 逐节点写入的最新全量态）
            AgentWorkflowExecutionDO fresh = executionMapper.selectById(executionId);
            Map<String, Object> baseState = parseState(fresh == null ? exec.getVariableSnapshot() : fresh.getVariableSnapshot());

            // 3. 逆序取「可补偿」节点
            List<AgentNodeExecutionDO> targets = nodeExecutionMapper.selectCompensableDesc(executionId);
            if (targets.isEmpty()) {
                log.info("[compensate][执行({}) recovery=COMPENSATE 但无可补偿节点（没有 SUCCESS 且声明 compensate_ref 的节点），直接置 COMPENSATED]",
                        executionId);
                updateStatus(executionId, WorkflowExecutionStatusEnum.COMPENSATED.getStatus(), null);
                return new CompensationResult(0, 0, 0, WorkflowExecutionStatusEnum.COMPENSATED.getStatus(), null);
            }

            int compensated = 0;
            int failed = 0;
            String firstError = null;
            for (AgentNodeExecutionDO node : targets) {
                // 每个节点用自己的 output 快照覆盖全量态：补偿所需前像（如刚创建的 recordId）既可能在全量态、
                // 也可能只在该节点 delta 里，叠加后解析最稳。
                Map<String, Object> state = mergeState(baseState, parseState(node.getOutputSnapshot()));
                Map<String, Object> params = resolveBeforeImage(node.getBeforeImage(), state, executionId, node.getNodeId());
                String idemKey = resolveTemplate(node.getIdempotencyKey(), state);
                if (StrUtil.isNotBlank(idemKey)) {
                    params.put(IDEMPOTENCY_PARAM, idemKey);
                }
                try {
                    toolExecutor.execute(context, node.getCompensateRef(), params);
                    compensated++;
                    log.info("[compensate][执行({}) 节点({}) 反做成功，工具({}) 幂等键({})]",
                            executionId, node.getNodeId(), node.getCompensateRef(), idemKey);
                } catch (Exception ex) {
                    failed++;
                    if (firstError == null) {
                        firstError = StrUtil.maxLength("节点(" + node.getNodeId() + ") 反做失败："
                                + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()), 500);
                    }
                    // 尽力而为：不 rethrow，继续撤后面的节点
                    log.error("[compensate][执行({}) 节点({}) 反做失败，工具({})：{}]",
                            executionId, node.getNodeId(), node.getCompensateRef(), ex.getMessage(), ex);
                }
            }

            String finalStatus = failed > 0
                    ? WorkflowExecutionStatusEnum.COMPENSATION_FAILED.getStatus()
                    : WorkflowExecutionStatusEnum.COMPENSATED.getStatus();
            updateStatus(executionId, finalStatus, firstError);
            log.info("[compensate][执行({}) 反做结束：应撤({}) 成功({}) 失败({}) 终态({})]",
                    executionId, targets.size(), compensated, failed, finalStatus);
            return new CompensationResult(targets.size(), compensated, failed, finalStatus, firstError);
        });
    }

    /**
     * 解析 {@code before_image}（持久化在 trace 行里的 JSON）成工具参数。
     *
     * <p>契约：{@code before_image} 是一个<b>对象</b> {@code {参数名: 模板}}，模板用与 tool 节点 args 相同的
     * {@code {{...}}} 语法（{@link StateTemplateResolver}），按 state 解析后作为补偿工具的入参。
     * 例：{@code {"recordId": "{{$.contract.recordId}}"}} → {@code {recordId: 123}}。
     *
     * <p>非对象（标量/字符串）、空、或解析失败 → 返回空参数并 {@code log.warn}：补偿工具若确实需要前像却拿不到，
     * 会在工具侧报错并被计入 {@code COMPENSATION_FAILED}，不会静默「假装撤成功」。
     */
    private Map<String, Object> resolveBeforeImage(String beforeImageJson, Map<String, Object> state,
                                                   Long executionId, String nodeId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (StrUtil.isBlank(beforeImageJson)) {
            return params;
        }
        try {
            Object parsed = JSONUtil.parse(beforeImageJson);
            if (parsed instanceof JSONObject obj) {
                for (Map.Entry<String, Object> entry : obj.entrySet()) {
                    Object v = entry.getValue();
                    params.put(entry.getKey(), v instanceof String s
                            ? StateTemplateResolver.resolve(s, state)
                            : v);
                }
            } else {
                log.warn("[resolveBeforeImage][执行({}) 节点({}) 的 before_image 不是对象（应为 参数名→模板 的 JSON 对象），已按空参数处理：{}]",
                        executionId, nodeId, beforeImageJson);
            }
        } catch (Exception ex) {
            log.warn("[resolveBeforeImage][执行({}) 节点({}) 的 before_image 解析失败，已按空参数处理：{}]",
                    executionId, nodeId, ex.getMessage());
        }
        return params;
    }

    private String resolveTemplate(String template, Map<String, Object> state) {
        if (StrUtil.isBlank(template)) {
            return null;
        }
        return StateTemplateResolver.resolve(template, state);
    }

    private void updateStatus(Long executionId, String status, String errorMsg) {
        AgentWorkflowExecutionDO update = new AgentWorkflowExecutionDO();
        update.setId(executionId);
        update.setStatus(status);
        if (StrUtil.isNotBlank(errorMsg)) {
            update.setErrorMsg(errorMsg);
        }
        executionMapper.updateById(update);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseState(String json) {
        if (StrUtil.isBlank(json)) {
            return new HashMap<>();
        }
        try {
            Object parsed = JSONUtil.parse(json);
            if (parsed instanceof Map<?, ?> map) {
                return new HashMap<>((Map<String, Object>) map);
            }
        } catch (Exception ex) {
            log.debug("[parseState][state 快照解析失败，按空处理：{}]", ex.getMessage());
        }
        return new HashMap<>();
    }

    /** overlay 覆盖 base（overlay 优先），两者皆可变副本，不污染入参 */
    private Map<String, Object> mergeState(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> merged = new HashMap<>(base);
        merged.putAll(overlay);
        return merged;
    }

    /**
     * 补偿结果（供调用方记日志、供单测断言）
     *
     * @param total       纳入补偿的节点数
     * @param compensated 反做成功数
     * @param failed      反做失败数
     * @param status      终态：COMPENSATED / COMPENSATION_FAILED
     * @param firstError  首个失败原因（全成功时为 null）
     */
    public record CompensationResult(int total, int compensated, int failed, String status, String firstError) {
    }

    /**
     * 该执行是否应在失败时触发补偿（{@code recovery==COMPENSATE}）。收口一处，避免调用方散写字符串比较。
     */
    public static boolean shouldCompensate(AgentWorkflowExecutionDO exec) {
        return exec != null
                && WorkflowRecoveryEnum.COMPENSATE.getRecovery().equals(exec.getRecovery());
    }

}
