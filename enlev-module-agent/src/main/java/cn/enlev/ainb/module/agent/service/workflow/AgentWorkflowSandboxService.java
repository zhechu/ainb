package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService.WorkflowEvent;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Workflow 沙箱测试 Service（流程编排设计器「沙箱测试」）
 *
 * <h3>🔴 沙箱 = 真跑草稿 DSL，但写操作 mock、不落库、不计配额</h3>
 * 与 {@link AgentWorkflowExecutionService#run} 的区别：run 按 code 跑「已发布」定义、建执行实例、落 trace、
 * 计 token 配额；沙箱<b>直传草稿 DSL 原文</b>（无需先保存/发布），用测试输入真跑一遍图，但：
 * <ul>
 *   <li><b>不</b>建 {@code agent_workflow_execution}、<b>不</b> settle、<b>不</b>写 {@code agent_node_execution}
 *       （{@code NodeTraceRecorder} 在 sandbox 上下文里 no-op）；</li>
 *   <li><b>不</b>计工作流配额（草稿试跑不该吃掉生产额度）；</li>
 *   <li>风险等级非 {@code READ_ONLY} 的工具在 {@code AgentToolExecutor} 处被 mock（占位结果、无副作用）；
 *       只读工具与大模型<b>真实执行</b>（会消耗 token，前端须显式提示）；</li>
 *   <li>用<b>内存</b> checkpoint（{@code MemorySaver}）而非持久 {@code MysqlSaver}，跑完即弃；</li>
 *   <li>命中 hitl / wait 等中断节点即<b>停止本次沙箱</b>（v1 不支持沙箱内 resume），发 {@code INTERRUPTED} 事件，
 *       前端据此显示「沙箱在此停止」banner。</li>
 * </ul>
 *
 * <h3>🔴 身份与租户纪律与 run 完全一致</h3>
 * 沙箱上下文 = {@code context.withSandbox()}，仍要求身份完整（fail-close 不放宽）；投递纪律
 * （{@code Sinks.unicast} + {@code boundedElastic} + {@code tryEmitNext} + {@code context.execute} 贴身份）
 * 照搬 {@link AgentWorkflowExecutionService}，只是去掉所有落库/收尾。事件类型复用 {@link WorkflowEvent}，
 * 前端事件模型不变。
 *
 * @author 走进新时代
 */
public interface AgentWorkflowSandboxService {

    /**
     * 用测试输入沙箱试跑一段草稿 DSL，逐节点流式回传状态/结果
     *
     * @param dsl     草稿 DSL 原文（直传，无需先落库/发布）
     * @param input   测试输入变量（写进沙箱执行上下文，可为 null）
     * @param context Servlet 线程上捕获的身份快照（内部 {@code withSandbox()} 复制出沙箱上下文）
     * @return 事件流（META → NODE* →（INTERRUPTED | DONE | ERROR））
     */
    Flux<WorkflowEvent> runSandbox(String dsl, Map<String, Object> input, AgentExecutionContext context);

}
