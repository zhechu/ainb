package cn.enlev.ainb.module.agent.service.workflow;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.enums.ErrorCodeConstants;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService.WorkflowEvent;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * Workflow 沙箱测试 Service 实现
 *
 * <p>投递纪律、事件模型与 {@link AgentWorkflowExecutionServiceImpl} 同源，但<b>去掉所有持久化与配额</b>：
 * 不建执行实例、不 settle、不写 trace（{@code NodeTraceRecorder} 在 sandbox 上下文里 no-op）、不计配额。
 * 用内存 checkpoint（{@link MemorySaver}）而非持久 {@code MysqlSaver}，跑完即弃。
 *
 * <p>🔴 命中中断节点（hitl / wait）即<b>结束本次沙箱</b> —— v1 不支持沙箱内 resume，
 * 前端据 {@code INTERRUPTED} 事件显示「沙箱在此停止」banner。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentWorkflowSandboxServiceImpl implements AgentWorkflowSandboxService {

    /** 沙箱合成执行 id（绝不落库，仅占位使事件结构与正式执行一致） */
    private static final long SANDBOX_EXECUTION_ID = -1L;
    /** 沙箱整体超时（秒）：比正式执行的 600s 短，试跑不该长时间占线程 */
    private static final int SANDBOX_TIMEOUT_SECONDS = 120;

    @Resource
    private DslParser dslParser;
    @Resource
    private DslToStateGraphCompiler compiler;
    @Autowired
    private ObjectProvider<ObservationRegistry> observationRegistryProvider;

    @Override
    public Flux<WorkflowEvent> runSandbox(String dsl, Map<String, Object> input, AgentExecutionContext context) {
        // 第一段：还在 Servlet 线程上，同步做完校验与解析（失败带错误码原样抛，Controller 转成流元素）
        requireIdentity(context);
        if (StrUtil.isBlank(dsl)) {
            throw exception(ErrorCodeConstants.WORKFLOW_DSL_INVALID, "DSL 为空");
        }
        // 草稿 DSL 直传解析（无需 published）；结构非法在此抛 WORKFLOW_DSL_INVALID
        WorkflowDefDTO dto = dslParser.parse(dsl);
        Map<String, Object> inputs = input == null ? new HashMap<>() : new HashMap<>(input);

        // 🔴 沙箱上下文：保留身份、置 sandbox=true，使下游 mock 写操作、跳过 trace 落库
        AgentExecutionContext sandboxCtx = context.withSandbox();
        // 🔴 B3-5：与正式路径<b>同一个</b>静态方法，不再是 sandbox: 前缀挂随机 UUID 那种不含租户的另一种形状。
        //    去掉 UUID 不会让并发沙箱互相串：saver 是下面 runGraph 里每次 new 出来的 MemorySaver，
        //    存储随本次调用生灭；且沙箱不建执行实例、trace 三处写库点都被 isSandbox 守卫，此值永不进库。
        String threadName = AgentWorkflowThreadName.threadName(sandboxCtx.getTenantId(), SANDBOX_EXECUTION_ID);

        Sinks.Many<WorkflowEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        Flux<WorkflowEvent> head = Flux.just(WorkflowEvent.meta(SANDBOX_EXECUTION_ID, threadName));
        Flux<WorkflowEvent> body = sink.asFlux()
                .doOnSubscribe(subscription -> Schedulers.boundedElastic()
                        .schedule(() -> runGraph(sink, dto, inputs, threadName, sandboxCtx)));

        return Flux.concat(head, body)
                .timeout(Duration.ofSeconds(SANDBOX_TIMEOUT_SECONDS))
                .doOnError(error -> log.warn("[runSandbox][沙箱流中断：{}]", error.getMessage()))
                .onErrorResume(error -> Flux.just(
                        WorkflowEvent.error(SANDBOX_EXECUTION_ID, readable(error))));
    }

    /**
     * 在 boundedElastic 上编译并跑图，逐节点推进度到 sink。
     *
     * <p>与正式执行的 runGraph 同构，但：用内存 saver、不建/不更新执行实例、命中中断即停、结束不 settle。
     * 整个方法体包在 {@code ctx.execute} 里，与正式执行一致地贴回租户身份（节点内调工具/读库都依赖它）。
     */
    private void runGraph(Sinks.Many<WorkflowEvent> sink, WorkflowDefDTO dto, Map<String, Object> inputs,
                          String threadName, AgentExecutionContext ctx) {
        ctx.execute(() -> {
            AtomicReference<Map<String, Object>> finalState = new AtomicReference<>(Map.of());
            AtomicBoolean interrupted = new AtomicBoolean(false);
            try {
                CompiledGraph graph = compiler.compile(dto, SANDBOX_EXECUTION_ID, threadName, ctx,
                        new MemorySaver(), observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP));
                RunnableConfig config = RunnableConfig.builder().threadId(threadName).build();

                Flux<NodeOutput> stream = graph.stream(inputs, config);
                NodeOutput last = stream.doOnNext(output -> {
                    if (output instanceof InterruptionMetadata) {
                        interrupted.set(true);
                        // 🔴 不在这里 emit interrupted：output.node() 指的是<b>最后一个跑完的</b>节点，
                        //    挂起节点前面只要还有 llm/tool/condition，banner 就会指着那个早已跑完的节点。
                        //    真挂起点要等流跑完、checkpoint 落定之后从快照的 next() 取，见下面
                        //    DslToStateGraphCompiler.resolveSuspendNodeId（实测表在那个方法的 javadoc 里）。
                    } else {
                        Map<String, Object> snapshot = safeData(output);
                        finalState.set(snapshot);
                        emit(sink, WorkflowEvent.node(SANDBOX_EXECUTION_ID, output.node(), snapshot));
                    }
                }).blockLast();

                if (last != null) {
                    finalState.set(safeData(last));
                }
                // 命中中断即停：不发 done（v1 不支持沙箱内 resume），前端据 INTERRUPTED 显示 banner
                if (interrupted.get()) {
                    emit(sink, WorkflowEvent.interrupted(SANDBOX_EXECUTION_ID,
                            DslToStateGraphCompiler.resolveSuspendNodeId(graph, config)));
                } else {
                    emit(sink, WorkflowEvent.done(SANDBOX_EXECUTION_ID, finalState.get()));
                }
            } catch (Exception ex) {
                log.warn("[runSandbox][沙箱执行失败：{}]", ex.getMessage(), ex);
                emit(sink, WorkflowEvent.error(SANDBOX_EXECUTION_ID, readable(ex)));
            } finally {
                sink.tryEmitComplete();
            }
            return null;
        });
    }

    private void requireIdentity(AgentExecutionContext context) {
        if (context == null || !context.isComplete()) {
            throw exception(ErrorCodeConstants.AGENT_NO_LOGIN_CONTEXT);
        }
    }

    private void emit(Sinks.Many<WorkflowEvent> sink, WorkflowEvent event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.debug("[emit][沙箱事件({}) 未发出，下游已终止：{}]", event.getType(), result);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeData(NodeOutput output) {
        try {
            Map<String, Object> data = output.state() == null ? null : output.state().data();
            return data == null ? Map.of() : new HashMap<>((Map<String, Object>) data);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String readable(Throwable ex) {
        if (ex instanceof ServiceException se) {
            return se.getMessage();
        }
        String msg = ex.getMessage();
        return StrUtil.isBlank(msg) ? ex.getClass().getSimpleName()
                : ex.getClass().getSimpleName() + ": " + msg;
    }

}
