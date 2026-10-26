package cn.enlev.ainb.module.agent.service.workflow;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowExecutionPageReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentNodeExecutionDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentNodeExecutionMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WaitTypeEnum;
import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import cn.enlev.ainb.module.agent.enums.WorkflowExecutionStatusEnum;
import cn.enlev.ainb.module.agent.enums.WorkflowRecoveryEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentTextUtils;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.KeyStrategyFactoryGenerator;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.VariableDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlBpmApprovalNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlConfirmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.io.NodeIoContract;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;
import cn.enlev.ainb.module.agent.service.quota.AgentQuotaService;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_NO_LOGIN_CONTEXT;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_DISABLED_NOT_RESUMABLE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_EXECUTION_NOT_EXISTS;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_EXECUTION_STATE_INVALID;

/**
 * Workflow 执行 Service 实现
 *
 * <h3>🔴 投递纪律完全照搬 AgentAssistantServiceImpl（有实测支撑，不是抄着玩）</h3>
 * <ul>
 *   <li>{@code Sinks.unicast().onBackpressureBuffer()} + {@code boundedElastic().schedule()}，
 *       <b>不用</b> {@code Flux.create + subscribeOn} —— 后者预取会把阻塞执行与 request 派到同一 worker，
 *       导致「事件攒到最后一次性涌出」（那边实测静默 4 分 54 秒）</li>
 *   <li>{@code tryEmitNext} 而非 {@code emitNext}：下游终止时安静丢弃，不把异常抛回执行线程</li>
 *   <li>阻塞执行 + 所有 DB 写都包在 {@code context.execute(...)} 里贴回租户身份</li>
 *   <li>settle-once：{@code AtomicBoolean} CAS，成功/失败/中断/超时只有一处生效</li>
 * </ul>
 *
 * <h3>🔴 租户传播靠「同步执行 + 显式快照」双保险</h3>
 * SAA {@code stream()} 在订阅线程同步执行节点（SPIKE 验证②），而订阅发生在
 * {@code context.execute} 贴好身份的 boundedElastic 线程上 —— 节点内 ThreadLocal 租户天然可见。
 * 即便如此，trace/状态回写仍走 {@code context.execute}，不依赖「当前线程恰好有上下文」。
 *
 * <h3>P1 范围</h3>
 * run + resume（hitl 挂起/恢复）+ 逐节点 SSE 进度 + 节点级 trace + token 计量。
 * 失败收尾时，若 {@code recovery==COMPENSATE}，由 {@link AgentWorkflowCompensator} 逆序调度 Saga 反做
 * （FAILED → COMPENSATING → COMPENSATED / COMPENSATION_FAILED），见 {@link #settle}。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentWorkflowExecutionServiceImpl implements AgentWorkflowExecutionService {

    /** 与 agent_workflow_execution.error_msg 列宽一致 */
    private static final int ERROR_MSG_MAX_LENGTH = 1000;
    /** 与 agent_workflow_execution.failed_at_node 列宽一致（B5-2：把死列真用起来时按列宽截断，防撞 Data truncation） */
    private static final int FAILED_AT_NODE_MAX_LENGTH = 64;
    /** settings.executionTimeoutSeconds 缺省值 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;

    /**
     * 取消落库时写进 {@code error_msg} 的原因文案。
     *
     * <p>🔴 两条取消路径<b>共用这一句</b>：SSE 断流触发的 {@code .doOnCancel}（用户关了页面 / 前端
     * {@code AbortController.abort()}）与 {@code POST /agent/workflow/cancel}（管理员在抽屉上点「取消」）。
     * 两处各写一遍字面量的话，将来改文案必然只改一处，于是同一个动作在库里留下两种说法 ——
     * 而这一列是事后唯一能回答「这条执行是怎么结束的」的地方。
     */
    private static final String CANCEL_REASON = "用户中断";

    @Resource
    private AgentWorkflowDefService defService;
    @Resource
    private DslParser dslParser;
    @Resource
    private DslToStateGraphCompiler compiler;
    @Resource
    private AgentWorkflowExecutionMapper executionMapper;
    @Resource
    private AgentNodeExecutionMapper nodeExecutionMapper;
    @Resource
    private AgentBpmApprovalService bpmApprovalService;
    @Resource
    private AgentWorkflowCompensator compensator;
    @Resource
    private AgentFailureAlertService failureAlertService;
    /**
     * 工作流维度配额（P7 / 待办 #6b）：run() 入口按 code 拦截，只限 token（不改计费语义）
     */
    @Resource
    private AgentQuotaService quotaService;

    @Resource(name = "workflowCheckpointSaver")
    private BaseCheckpointSaver checkpointSaver;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<ObservationRegistry> observationRegistryProvider;

    // ==================== 对外入口 ====================

    @Override
    public Flux<WorkflowEvent> run(String code, Map<String, Object> input, AgentExecutionContext context) {
        // 第一段：还在 Servlet 线程上，同步做完校验与建实例（失败带错误码原样抛，Controller 转成流元素）
        requireIdentity(context);
        AgentWorkflowDefDO def = defService.getPublishedByCode(code);
        // 🔴 工作流维度配额（P7 / 待办 #6b）：建执行实例<b>之前</b>拦截，超限抛 AGENT_QUOTA_EXCEEDED，
        //    不留半成品执行行。只限 token（节点 trace 无 cost 列、按「不改计费语义」不新增工作流计费口径）；
        //    没给该 code 配额度则直接放行（= 不限制）。与 requireIdentity/getPublishedByCode 同在 Servlet 线程
        //    同步抛，调用方（REST Controller / 聊天 workflowRunStream 桥接）接住转成错误，绝不在图跑到一半才拒。
        quotaService.checkWorkflowQuota(def.getCode());
        WorkflowDefDTO dto = dslParser.parse(def.getDsl());

        Long tenantId = context.getTenantId();
        Map<String, Object> inputs = input == null ? new HashMap<>() : new HashMap<>(input);

        // 建执行实例：先 insert 拿 id，再用 id 拼 threadName 回写（threadName 是 checkpoint 关联键）
        AgentWorkflowExecutionDO exec = new AgentWorkflowExecutionDO();
        exec.setWorkflowDefId(def.getId());
        exec.setWorkflowVersion(def.getVersion());
        exec.setStatus(WorkflowExecutionStatusEnum.CREATED.getStatus());
        exec.setUserId(context.getUserId());
        exec.setRecovery(resolveRecovery(dto));
        exec.setInputJson(JSONUtil.toJsonStr(inputs));
        exec.setBeginTime(LocalDateTime.now());
        exec.setThreadName(""); // 占位，insert 后立即回写真实值（列 NOT NULL）
        executionMapper.insert(exec);

        Long executionId = exec.getId();
        // 🔴 B3-5：格式只在 AgentWorkflowThreadName 里定义一次。此处曾硬编码拼接，
        //    与沙箱那半各拼各的、分成两种形状 —— 「格式里必须含租户」就此退化成一处口头约定。
        String threadName = AgentWorkflowThreadName.threadName(tenantId, executionId);
        exec.setThreadName(threadName);
        exec.setStatus(WorkflowExecutionStatusEnum.RUNNING.getStatus());
        executionMapper.updateById(exec);

        log.info("[run][启动 Workflow code({}) defId({}) v({}) executionId({}) thread({})]",
                code, def.getId(), def.getVersion(), executionId, threadName);
        return buildEventFlux(exec, dto, inputs, false, context, System.currentTimeMillis());
    }

    @Override
    public Flux<WorkflowEvent> resume(Long executionId, Map<String, Object> feedback,
                                      AgentExecutionContext context) {
        requireIdentity(context);
        AgentWorkflowExecutionDO exec = executionMapper.selectById(executionId);
        if (exec == null) {
            throw exception(WORKFLOW_EXECUTION_NOT_EXISTS);
        }
        // 只有挂起中的执行能恢复；对已结束的调 resume 是调用方逻辑错，明确报出来
        if (!WorkflowExecutionStatusEnum.isResumable(exec.getStatus())) {
            throw exception(WORKFLOW_EXECUTION_STATE_INVALID, exec.getStatus());
        }
        AgentWorkflowDefDO def = defService.getDef(exec.getWorkflowDefId());
        // 🔴 B3-3：定义状态检查。刻意放在 CAS 认领<b>之前</b> —— 认领会把 INTERRUPTED 改成 RUNNING，
        //    先认领再拒就会在库里留一行「状态说在跑、其实没人跑」的僵尸。放在这里，行原样停在 INTERRUPTED，
        //    与事实一致（它确实还挂在 checkpoint 上），管理员要清场走抽屉里的「取消」。
        requireDefResumable(def, exec);
        WorkflowDefDTO dto = dslParser.parse(def.getDsl());

        Map<String, Object> inputs = feedback == null ? new HashMap<>() : new HashMap<>(feedback);
        // 🔴 增量4a：原子认领。把「setStatus(RUNNING)+updateById」换成条件更新
        //   UPDATE ... SET status=RUNNING, resume_at=NULL WHERE id=? AND status=INTERRUPTED。
        //   轮询器（WorkflowWaitResumeScheduler）到点自动 resume 与人工 resume 可能并发撞同一条执行，
        //   谁先把 INTERRUPTED CAS 成 RUNNING 谁赢，另一个影响 0 行→抛 STATE_INVALID 放弃，杜绝同一执行被双跑。
        //   顺带清掉 resume_at，避免被轮询器再次扫到。此加固对 hitl 的 resume 同样成立（等价，只增并发安全）。
        if (!executionMapper.casInterruptedToRunning(executionId)) {
            throw exception(WORKFLOW_EXECUTION_STATE_INVALID, exec.getStatus());
        }
        exec.setStatus(WorkflowExecutionStatusEnum.RUNNING.getStatus());

        log.info("[resume][恢复 Workflow executionId({}) thread({}) feedback({})]",
                executionId, exec.getThreadName(), inputs.keySet());
        return buildEventFlux(exec, dto, inputs, true, context, System.currentTimeMillis());
    }

    @Override
    public void cancel(Long executionId, AgentExecutionContext context) {
        requireIdentity(context);
        AgentWorkflowExecutionDO exec = executionMapper.selectById(executionId);
        if (exec == null) {
            throw exception(WORKFLOW_EXECUTION_NOT_EXISTS);
        }
        // 🔴 准入闸取自枚举的<b>白名单</b>（isCancellable），不是「!isFinal」—— 后者会多放一档进来：
        //    · COMPENSATING：正在逆序调反做工具，判它「已取消」= 宣称撤销完成而撤销只做了一半。
        //      半撤销比卡死更难查（卡死看得见，半撤销看着像成功），故宁可让人等它跑完。
        //    （B3-4 之后 FAILED 已在 isFinal 里，「!isFinal」这条路对 FAILED 已不再漏；但白名单另有一层
        //     保险 —— 新加状态默认落进「不可取消」，与「!isFinal 再补例外」的默认方向相反。）
        //    完整理由见 WorkflowExecutionStatusEnum#isCancellable 的注释（与 casToCancelled 的 SQL 同源）。
        if (!WorkflowExecutionStatusEnum.isCancellable(exec.getStatus())) {
            throw exception(WORKFLOW_EXECUTION_STATE_INVALID, exec.getStatus());
        }
        LocalDateTime endTime = LocalDateTime.now();
        // 🔴 耗时一并落：SSE 断流那条路（settle）本来就写 duration_ms，这里不写就会让同一个「取消」动作
        //    经两条路径落成两种形状，而抽屉「耗时(ms)」那一列正是本轮要给人看的。
        //    beginTime 由 run() 建实例时写入、恒非空；仍留一道 null 判断是因为它来自库、不是本地不变量。
        Integer durationMs = exec.getBeginTime() == null ? null
                : (int) Math.max(0, Duration.between(exec.getBeginTime(), endTime).toMillis());
        int rows = executionMapper.casToCancelled(executionId, CANCEL_REASON, endTime, durationMs);
        if (rows == 0) {
            // 撞车：上面预检之后、CAS 之前，别人改了状态（跑图那条流的 settle、另一个管理员、
            // wait 轮询器的自动 resume 都可能）。
            // 🔴 必须<b>重读</b>再报，不能拿预检时那个旧值糊过去：否则用户看到的是
            //    「当前状态（RUNNING）不允许该操作」，而那一行其实早已 SUCCEEDED ——
            //    把「你没取消成」说成了「它还在跑」，正是本子系统最典型的「操作无效且提示指向错处」。
            AgentWorkflowExecutionDO latest = executionMapper.selectById(executionId);
            if (latest == null) {
                throw exception(WORKFLOW_EXECUTION_NOT_EXISTS);
            }
            throw exception(WORKFLOW_EXECUTION_STATE_INVALID, latest.getStatus());
        }
        // 🔴 这里<b>刻意</b>不调 compensator、不调 failureAlertService：
        //    取消不是失败（B3-1 的三层错里，「行为错」与「告警错」两条就是靠不调它们消掉的）。
        log.info("[cancel][中止 Workflow executionId({}) 原状态({}) → CANCELLED，不补偿不告警]",
                executionId, exec.getStatus());
    }

    @Override
    public AgentWorkflowExecutionDO getExecution(Long executionId, AgentExecutionContext context) {
        requireIdentity(context);
        AgentWorkflowExecutionDO exec = executionMapper.selectById(executionId);
        if (exec == null) {
            throw exception(WORKFLOW_EXECUTION_NOT_EXISTS);
        }
        return exec;
    }

    @Override
    public List<AgentNodeExecutionDO> getNodeTraces(Long executionId, AgentExecutionContext context) {
        requireIdentity(context);
        // 先确认执行存在（顺带过租户隔离），再查 trace
        getExecution(executionId, context);
        return nodeExecutionMapper.selectListByExecutionId(executionId);
    }

    @Override
    public PageResult<AgentWorkflowExecutionDO> getExecutionPage(AgentWorkflowExecutionPageReqVO pageReqVO,
                                                                 AgentExecutionContext context) {
        requireIdentity(context);
        // 管理页视图：本租户全量（不按 userId 收窄），租户隔离由 executionMapper.selectPage 的
        // TenantLineInnerInterceptor 自动拼 tenant_id=? 保证。requireIdentity 只作「有合法身份」守卫，
        // 与其它执行读接口一致；真正决定「只看本租户」的是 ambient 租户上下文 + Mapper 拦截器。
        return executionMapper.selectPage(pageReqVO);
    }

    @Override
    public int publishEvent(String eventKey, Map<String, Object> payload, AgentExecutionContext context) {
        requireIdentity(context);
        // 广播：唤醒本租户下所有等待该 eventKey 的挂起执行。租户隔离由 selectInterruptedByEventKey 的
        // TenantLineInnerInterceptor 自动拼 tenant_id=? 保证——发布者的 Servlet 租户上下文即查询边界，
        // 故租户 A 的事件绝匹配不到租户 B 的执行。LIMIT 50 是 MVP 单批上界。
        List<AgentWorkflowExecutionDO> matched = executionMapper.selectInterruptedByEventKey(eventKey, 50);
        if (matched.isEmpty()) {
            log.info("[publishEvent][事件({}) 无匹配的挂起执行]", eventKey);
            return 0;
        }
        int dispatched = 0;
        for (AgentWorkflowExecutionDO exec : matched) {
            // 唤醒身份用「行身份」（执行所有者），与 WorkflowWaitResumeScheduler 一致——发布者可能是另一个系统账号，
            // 恢复后的 trace 落库/状态回写应归属执行所有者。userId 为 NULL 无法合成身份，跳过+告警（不 fail-open）。
            if (exec.getUserId() == null) {
                log.warn("[publishEvent][执行({}) 无 userId，无法合成恢复身份，跳过]", exec.getId());
                continue;
            }
            AgentExecutionContext rowCtx = AgentExecutionContext.ofSystem(exec.getUserId(), exec.getTenantId());
            // 🔴 payload 经 resume 的 feedback→updateState 写进 checkpoint；想被恢复后下游节点读到的键，
            //   必须在该 def 的 variables[] 声明（SAA OverAllState.input() 只保留已注册 KeyStrategy 的键，增量4a 已证）。
            Map<String, Object> inputs = payload == null ? new HashMap<>() : new HashMap<>(payload);
            try {
                // fire-and-forget：resume 返回 lazy Flux，subscribe 后异步在 boundedElastic 跑；本端点只 ack「已派发」。
                resume(exec.getId(), inputs, rowCtx)
                        .subscribe(x -> {
                        }, err -> log.warn("[publishEvent][唤醒执行({}) 失败：{}]", exec.getId(), err.getMessage(), err));
                dispatched++;
            } catch (ServiceException se) {
                // CAS 认领失败=已被别的调用方（并发发布/人工 resume）恢复，benign race，记 info 不冒泡
                log.info("[publishEvent][执行({}) 已被恢复/状态不符，跳过：{}]", exec.getId(), se.getMessage());
            } catch (Exception ex) {
                log.warn("[publishEvent][执行({}) 唤醒异常，跳过]", exec.getId(), ex);
            }
        }
        log.info("[publishEvent][事件({}) 命中({}) 条，派发({}) 条恢复]", eventKey, matched.size(), dispatched);
        return dispatched;
    }

    // ==================== 流式执行 ====================

    private Flux<WorkflowEvent> buildEventFlux(AgentWorkflowExecutionDO exec, WorkflowDefDTO dto,
                                               Map<String, Object> inputs, boolean isResume,
                                               AgentExecutionContext context, long startMillis) {
        Long executionId = exec.getId();
        String threadName = exec.getThreadName();
        Sinks.Many<WorkflowEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean settled = new AtomicBoolean(false);
        // 🔴 B5-2：跟踪「最后一个推进过的节点 id」，供两条 FAILED 收尾路径写进 failed_at_node（那根曾经的死列）。
        //    在 buildEventFlux 这一层建、传进 runGraph：节点异常走 runGraph 的 catch、流超时走这里的 doOnError，
        //    两条路都要能读到同一个「卡在哪个节点」，故 ref 必须建在两者的共同外层。
        AtomicReference<String> lastNode = new AtomicReference<>();

        Flux<WorkflowEvent> head = Flux.just(WorkflowEvent.meta(executionId, threadName));
        // doOnSubscribe 里才派发：没人订阅就不白跑一趟图、也不留下没人认领的执行
        Flux<WorkflowEvent> body = sink.asFlux()
                .doOnSubscribe(subscription -> Schedulers.boundedElastic().schedule(() ->
                        runGraph(sink, settled, lastNode, dto, inputs, isResume, exec, context, startMillis)));

        return Flux.concat(head, body)
                // timeout 是相邻两事件间隔上限：节点卡住（厂商连接挂起）时甩掉，占住的是 elastic 线程
                .timeout(Duration.ofSeconds(resolveTimeout(dto)))
                .doOnError(error -> settle(settled, exec, context,
                        WorkflowExecutionStatusEnum.FAILED.getStatus(), readable(error), null, startMillis,
                        lastNode.get()))
                // 🔴 取消<b>不是</b>失败（迭代七 B3-1）。这里原先写的是 FAILED + 「用户中断」，后果有三层：
                //    ① 数据错——「我按了停」与「它崩了」在库里长得一模一样，事后分不出来；
                //    ② 行为错——settle 见 FAILED 且 recovery=COMPENSATE 就触发 Saga 反做，
                //       用户想停住的动作反倒被反做一遍；③ 告警错——FAILED 进失败告警面，
                //       每次有人按「停」都给租户联系人发一条告警（第 07 轮补全告警后噪音更大）。
                //    改成 CANCELLED 之后 ②③ 自动消失：settle 的补偿与告警两个分支本来就只认 FAILED。
                //    🔴 裁定 A-Ⅰ：本轮只做「不再判 FAILED、不再触发补偿」，<b>不做</b>「取消转可恢复」。
                //    🔴 取消不写 failed_at_node（传 null）：它不是失败，没有「失败所在节点」这件事。
                .doOnCancel(() -> settle(settled, exec, context,
                        WorkflowExecutionStatusEnum.CANCELLED.getStatus(), CANCEL_REASON, null, startMillis, null))
                .onErrorResume(error -> {
                    log.warn("[buildEventFlux][执行({}) 流中断：{}]", executionId, error.getMessage());
                    return Flux.just(WorkflowEvent.error(executionId, readable(error)));
                });
    }

    /**
     * 在 boundedElastic 上编译并跑图，逐节点推进度到 sink
     *
     * <p>整个方法体包在 {@code context.execute} 里：编译、stream 订阅、节点执行、trace/状态回写
     * 全在这一个贴好身份的线程上同步发生。
     */
    private void runGraph(Sinks.Many<WorkflowEvent> sink, AtomicBoolean settled, AtomicReference<String> lastNode,
                          WorkflowDefDTO dto,
                          Map<String, Object> inputs, boolean isResume, AgentWorkflowExecutionDO exec,
                          AgentExecutionContext context, long startMillis) {
        Long executionId = exec.getId();
        String threadName = exec.getThreadName();
        context.execute(() -> {
            AtomicReference<Map<String, Object>> finalState = new AtomicReference<>(Map.of());
            AtomicBoolean interrupted = new AtomicBoolean(false);
            try {
                CompiledGraph graph = compiler.compile(dto, executionId, threadName, context,
                        checkpointSaver, observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP));
                RunnableConfig config = RunnableConfig.builder().threadId(threadName).build();

                Flux<NodeOutput> stream;
                if (isResume) {
                    // 🔴 B3-7：灌 state 之前算差集。graph.stateOf(config) 读的是 checkpoint 里
                    //    挂起时存下的 OverAllState（含 data + keyStrategies），与 SAA resume 时
                    //    OverAllState.input() 真正用来过滤的是同一份。
                    //    🔴 传 dto 做白名单过滤：原始差集恒非空（引擎簿记键），见 computeDroppedKeys 注释。
                    Set<String> dropped = graph.stateOf(config)
                            .map(snapshot -> computeDroppedKeys(snapshot.state(), dto))
                            .orElse(Set.of());
                    if (!dropped.isEmpty()) {
                        log.warn("[runGraph][执行({}) resume：{} 个作者写的 state 键将被静默丢弃"
                                        + "（KeyStrategy 没注册，恢复后下游读到 null）: {}]",
                                executionId, dropped.size(), dropped);
                        String droppedJson = serializeDroppedKeys(dropped);
                        context.execute(() -> {
                            AgentWorkflowExecutionDO dkUpdate = new AgentWorkflowExecutionDO();
                            dkUpdate.setId(executionId);
                            dkUpdate.setDroppedKeys(droppedJson);
                            executionMapper.updateById(dkUpdate);
                            return null;
                        });
                    }
                    // 把人工反馈写进 checkpoint，再从断点恢复（DSL §6.2、SPIKE 验证②）
                    RunnableConfig updated = graph.updateState(config, inputs);
                    stream = graph.stream(null, updated.withResume());
                } else {
                    stream = graph.stream(inputs, config);
                }

                NodeOutput last = stream.doOnNext(output -> {
                    if (output instanceof InterruptionMetadata) {
                        interrupted.set(true);
                        // 🔴 不在这里 emit interrupted：此刻 status 还是 RUNNING，
                        //    事件一旦发出去，聊天端就会渲染出可点的「同意/拒绝」卡片，
                        //    手快的用户（以及 e2e）点下去会撞上 resume 的状态校验，
                        //    收到「Workflow 执行实例当前状态（RUNNING）不允许该操作」。
                        //    改为等下面 settle 把 INTERRUPTED 落库之后再发，见 interrupted 分支。
                        // 🔴 也不在这里取「挂起在哪个节点」：output.node() 指的是<b>最后一个跑完的</b>节点，
                        //    只有挂起节点正好是 START 之后第一个时才碰巧等于挂起节点。真值要等流跑完、
                        //    checkpoint 落定之后从快照的 next() 取，见下面 resolveSuspendNodeId。
                    } else {
                        Map<String, Object> snapshot = safeData(output);
                        finalState.set(snapshot);
                        // 🔴 B5-2：记下「最后一个推进到的节点」。updateCurrentNode 把它落进 current_node（给执行历史看），
                        //    lastNode 则留给收尾：一旦下一个节点抛错 / 流超时，settle 用它写 failed_at_node。
                        //    ⚠️ 语义诚实说明：这是<b>最后一个跑完的</b>节点，节点内抛错时真正炸的是它的<b>下一个</b>——
                        //    而下一个还没 emit 过 NodeOutput，id 拿不到。对流超时那一档则恰好精确（就是卡在 lastNode 不动）。
                        //    与 current_node 同口径（DO 注释：「当前/最后停留的节点」），是本事件流能给出的最好信号。
                        lastNode.set(output.node());
                        updateCurrentNode(executionId, output.node(), snapshot);
                        emit(sink, WorkflowEvent.node(executionId, output.node(), snapshot));
                    }
                }).blockLast();

                if (last != null) {
                    finalState.set(safeData(last));
                }

                // 收尾：挂起 or 成功，二选一，且只生效一次
                if (interrupted.get()) {
                    // 增量4a/4b：按中断节点的 waitType 落「唤醒凭据」——duration 算 resume_at（轮询器到点自动恢复）、
                    // event 落 event_key（外部 POST /agent/workflow/event 按 key 匹配恢复）；hitl 等非 wait 节点两者皆
                    // null → resume_at/event_key 保持 NULL → 轮询器扫不到、事件也匹配不到 → 仍靠人工 resume-stream。
                    String nodeId = DslToStateGraphCompiler.resolveSuspendNodeId(graph, config);
                    if (nodeId == null) {
                        // 取不到快照 ⟹ 下面三个 resolveXxx 一律返回 null ⟹ resume_at/event_key 都不落库，
                        // 这一实例只能靠人工 resume-stream。留 WARN 而不是静默：这正是「卡住且无提示」的成因形态。
                        log.warn("[runGraph][执行({}) 挂起了，但从 checkpoint 快照取不到挂起节点 id，"
                                + "resume_at/event_key 均不会落库]", executionId);
                    }
                    LocalDateTime resumeAt = resolveWaitResumeAt(dto, nodeId);
                    String waitEventKey = resolveWaitEventKey(dto, nodeId);
                    // P6：hitl.bpm_approval 挂起——此刻发起 BPM 审批实例，并把 businessKey 落成 event_key。
                    // 审批完成由 AgentBpmApprovalListener 按 businessKey 走 publishEvent 唤醒（复用 wait(event) 链路）。
                    // 发起动作必须在这里（挂起时）而非执行器里：执行器只在 resume 后才跑，见 AgentBpmApprovalService 类注释。
                    String bpmEventKey = resolveBpmApprovalEventKey(dto, nodeId, exec, context);
                    // 一个节点只可能是 wait(event) 或 hitl.bpm_approval 之一，故二者互斥；bpm 的 businessKey 优先。
                    // 用三元一次性赋值（而非先算再改写）保持 eventKey effectively-final，供下方 context.execute 的 lambda 捕获。
                    String eventKey = bpmEventKey != null ? bpmEventKey : waitEventKey;
                    // 🔴 B3-7：挂起时算一次 droppedKeys，供前端确认框在 resume 之前就能展示。
                    //    last 此刻是 InterruptionMetadata（extends NodeOutput），.state() 给的是
                    //    挂起瞬间的 OverAllState（含 data + keyStrategies），与 resume 时 SAA 用来过滤的是同一份。
                    String droppedJson = (last != null)
                            ? serializeDroppedKeys(computeDroppedKeys(last.state(), dto))
                            : null;
                    // 🔴 这段 UPDATE 不再挂在「resumeAt != null || eventKey != null」里，因为 suspend_node
                    //   是无条件要落的（迭代六 D1）：hitl.confirm 挂起时那两个都是 null，而它恰恰是最需要
                    //   说清卡在哪一档的（纯人工恢复，没有轮询器也没有事件端点会来救它）。
                    //   挂在里面就等于「只有自动唤醒的那两档才记录挂在哪」，正好漏掉最坏的一档。
                    context.execute(() -> {
                        AgentWorkflowExecutionDO wake = new AgentWorkflowExecutionDO();
                        wake.setId(executionId);
                        // 🔴 MP updateById 默认 NOT_NULL 策略：为 null 的那个不会被写进 UPDATE，
                        //   故 duration 行只落 resume_at、event 行只落 event_key、hitl 行只落 suspend_node，
                        //   三者互不覆盖。这也是 nodeId 为 null（取不到快照）时的正确行为：
                        //   suspend_node 保持 NULL，而不是被写成空串 —— 与 resolveSuspendNodeId
                        //   刻意不回落成错值同一个取舍。
                        wake.setSuspendNode(nodeId);
                        wake.setResumeAt(resumeAt);
                        wake.setEventKey(eventKey);
                        wake.setDroppedKeys(droppedJson);
                        executionMapper.updateById(wake);
                        return null;
                    });
                    if (resumeAt != null || eventKey != null) {
                        log.info("[runGraph][执行({}) 挂起于 wait 节点({})，resume_at({})/event_key({}) 等待唤醒]",
                                executionId, nodeId, resumeAt, eventKey);
                    }
                    if (settle(settled, exec, context,
                            WorkflowExecutionStatusEnum.INTERRUPTED.getStatus(), null,
                            finalState.get(), startMillis, null)) {
                        // 🔴 事件必须在 INTERRUPTED 落库之后才发。发早了，聊天端会立刻渲染出可点的
                        //    「同意/拒绝」卡片，而 resume 的状态校验读到的还是 RUNNING ——
                        //    用户手一点就是「Workflow 执行实例当前状态（RUNNING）不允许该操作」。
                        //    e2e 每次都点得比人快，于是稳定复现；真人只是偶发。
                        //    settle 返回 false＝已被超时/取消抢先收尾，此时不该再挂一张点不动的卡。
                        emit(sink, WorkflowEvent.interrupted(executionId, nodeId));
                    }
                } else {
                    settle(settled, exec, context,
                            WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), null,
                            finalState.get(), startMillis, null);
                    emit(sink, WorkflowEvent.done(executionId, finalState.get()));
                }
            } catch (Exception ex) {
                log.warn("[runGraph][执行({}) 失败：{}]", executionId, ex.getMessage(), ex);
                // 🔴 B5-2：节点异常这一档把 lastNode（最后一个推进到的节点）写进 failed_at_node，
                //    让「失败在哪一步」不再是死列。语义诚实说明见 lastNode.set 处注释。
                settle(settled, exec, context,
                        WorkflowExecutionStatusEnum.FAILED.getStatus(), readable(ex), null, startMillis,
                        lastNode.get());
                emit(sink, WorkflowEvent.error(executionId, readable(ex)));
            } finally {
                sink.tryEmitComplete();
            }
            return null;
        });
    }

    // ==================== B3-7：droppedKeys 差集计算 ====================

    /**
     * 算出「resume 时会被 {@code OverAllState.input()} 静默丢弃、<b>且作者能自己修</b>」的键集。
     *
     * <p>🔴 注册集直接问 checkpoint 自己（{@code OverAllState.keyStrategies()}），不拿 def 重算：
     * 实测两者同集（{@code KeyStrategyResumeRuntimeTest#droppedKeysAtSuspend_neverEmpty_evenWhenEverythingIsDeclared}
     * 钉住），而问 checkpoint 才是真实取数路径 —— resume 时 SAA 用的就是 checkpoint 里那份 keyStrategies，
     * def 可能在挂起后改了版本，重算会给出一个与 SAA 实际过滤行为不一致的答案。
     *
     * <h3>🔴 为什么必须过一道「作者写过没有」的滤网（少了它就是本方法最严重的错法）</h3>
     * 裸差集（state 键 − 注册键）<b>恒非空</b>，这不是 bug 而是实测事实：引擎自己往 state 写的键
     * 没人注册 —— 条件节点的路由键、框架保留的 token 用量键、SAA 的执行号键，
     * 外加执行器自己写的审计键（{@code <nodeId> + "_confirmed"} 那一类，无常量、无统一前缀）。
     * {@code KeyStrategyResumeRuntimeTest} 的类注释把这条写成性质断言，并明说
     * 「差集恒非空正是 B3-7 <b>必须先有过滤器</b>的根据」。
     *
     * <p>于是若把裸差集直接报出去，后果是<b>每次恢复都弹一次假警报</b>，而它给的出路
     * （「补上 {@code variables[]} 声明后再发布」）对那些键<b>根本无效</b> ——
     * 作者没法声明一个引擎内部键，声明了也照样不是执行器会去读的那个名字。
     * 那比原来的「沉默」更坏：沉默只是没说，假警报会把运维支去改一个改不动的地方，还让他以为自己在修缺陷。
     *
     * <p>🔴 滤网刻意写成<b>正向白名单</b>（只留作者在 DSL 里真写过的键），而不是反向黑名单
     * （列举 {@code _graph_execution_id_} 这类内部键名再剔掉）。理由与
     * {@code KeyStrategyResumeRuntimeTest} 拒绝断言字面内部键名同一条：钉字面名等于把
     * 「SAA 升级改了个内部键名」变成本仓的红灯，而那是与 B3-7 毫无关系的理由。
     * 白名单则相反 —— 引擎改名只会让它继续被滤掉，本方法一声不响，正是想要的行为。
     *
     * <p>🔴 白名单的口径<b>复用 V19 那一份</b>，不另立：{@link WorkflowDslValidator#writtenVarOf}
     * 是「这个端口在执行期真正写进 state 的键名」的唯一权威（它的 javadoc 明令调用方不要自己再写一遍
     * {@code configStr(configKey, defaultName)}）。两个理由：① 那段「为什么不用 blankToDefault」的推理
     * 只有一份；② 与 V19 同源意味着「发布闸门放过的」与「这里会报的」永远互补 ——
     * V19 已经在发布期拦住「写了 state、下游有挂起、却没声明」，本方法是它在<b>运行期</b>的兜底
     * （存量定义、或 V19 的两个前提不成立时漏下来的那一档）。
     *
     * <p>🔴 豁免 {@code HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR}：它是 HITL 通道自带的键，
     * {@code KeyStrategyFactoryGenerator} 无条件注册，正常不会进差集；显式豁免是一道地板 ——
     * 万一将来注册逻辑变了，报出来也只是噪音（V-c 钉的就是这一条）。
     * 🔴 引用常量而不是写字面量：{@code NodeIoContractTest#mainSourcesHaveNoDefaultNameLiterals}
     * 扫 src/main 全部源码文本行，带引号的字面量（含注释里）会被判红。
     *
     * <p>包可见：供 {@code AgentWorkflowExecutionServiceImplDroppedKeysTest} 直接验证三条判据。
     */
    static Set<String> computeDroppedKeys(OverAllState state, WorkflowDefDTO def) {
        Set<String> dropped = new LinkedHashSet<>(state.data().keySet());
        dropped.removeAll(state.keyStrategies().keySet());
        if (dropped.isEmpty()) {
            return dropped;
        }
        dropped.retainAll(authorWrittenKeys(def));
        dropped.remove(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR);
        return dropped;
    }

    /**
     * 作者在 DSL 里<b>真写过</b>的那些 state 键名 = 顶层 {@code variables[]} 的 key
     * ∪ 每个节点各输出端口的实际写入名（{@link WorkflowDslValidator#writtenVarOf}）。
     *
     * <p>🔴 这一份是「作者能不能自己修」的判据：落在这个集合里的键，作者补一条 {@code variables[]}
     * 声明就真的能救；落在外面的（引擎簿记键、执行器审计键）作者无从下手，报出来只是噪音。
     *
     * <p>⚠️ 顺着 {@code outputs()} 走的通用循环天生给不出字面量键名，故这一读<b>必须</b>走
     * {@code writtenVarOf} 那个域外的 sanctioned 读点，不要在这里直接 {@code node.configStr(...)}。
     */
    static Set<String> authorWrittenKeys(WorkflowDefDTO def) {
        Set<String> keys = new LinkedHashSet<>();
        if (def == null) {
            return keys;
        }
        if (def.getVariables() != null) {
            for (VariableDTO var : def.getVariables()) {
                if (StrUtil.isNotBlank(var.getKey())) {
                    keys.add(var.getKey());
                }
            }
        }
        if (def.getNodes() != null) {
            for (NodeDTO node : def.getNodes()) {
                NodeIoContract.Contract contract = NodeIoContract.of(node.getType());
                if (contract == null) {
                    continue;
                }
                for (NodeIoContract.OutPort port : contract.outputs()) {
                    String written = WorkflowDslValidator.writtenVarOf(node, port);
                    if (StrUtil.isNotBlank(written)) {
                        keys.add(written);
                    }
                }
            }
        }
        return keys;
    }

    /**
     * 把 droppedKeys 序列化成 JSON 数组字符串落库；空集返回 null，让列保持 NULL。
     *
     * <p>🔴 NULL 的语义是「<b>滤网后</b>为空」，不是「差集为空」，更不是「一个键都不会丢」：
     * 引擎簿记键与执行器审计键照样在 resume 那一瞬被 SAA 丢掉，只是作者改不动它们、
     * 报出来是噪音（见 {@link #computeDroppedKeys}）。这一格曾写着「列保持 NULL = 差集为空」，
     * 是滤网落地时漏改的第 5 处自述（前 4 处：SQL 脚本、RespVO 的 {@code @Schema} 例子、
     * DO 字段注释、前端 api 注释）—— 它比另外 4 处更容易被当成权威读，因为它是<b>唯一</b>
     * 一处写在算这个值的方法旁边的说明。
     *
     * <p>包可见：供单测直接验证。
     */
    static String serializeDroppedKeys(Set<String> dropped) {
        return dropped.isEmpty() ? null : JSONUtil.toJsonStr(new ArrayList<>(dropped));
    }

    // ==================== 状态回写与收尾 ====================

    /**
     * settle-once：成功/失败/中断/取消/超时几处只能有一处生效
     *
     * <p>🔴 失败收尾顺带触发 Saga：当本次落 {@code FAILED} 且执行声明 {@code recovery==COMPENSATE} 时，
     * 交给 {@link AgentWorkflowCompensator} 逆序反做已生效的副作用节点（FAILED→COMPENSATING→COMPENSATED/
     * COMPENSATION_FAILED）。放在这里是<b>唯一收口</b>——节点失败与流超时两条 FAILED 路径都经过 settle，
     * 补偿因此不会漏触发；settle-once 的 CAS 又保证「一次执行只补偿一次」。补偿是同步尽力而为，
     * 会占用当前收尾线程若干工具调用的时间（与 settle 本就在此线程做阻塞 DB 写一致）。
     *
     * <p>🔴 <b>「用户中断」已经不在上面那句里了</b>（迭代七 B3-1）：取消改落 {@code CANCELLED}，
     * 而下面补偿与告警两个分支都只认 {@code FAILED} ⟹ 取消<b>既不反做也不告警</b>，无需在这里加任何判断。
     * 这正是「把取消升成独立终态」比「在 FAILED 分支里加一个 if 排除取消」更好的地方：
     * 后者每加一个消费 FAILED 的分支都要记得再排除一次，漏一次就把用户想停住的动作又跑一遍。
     *
     * <p>🔴 同一收口再据「最终终态」发失败告警（{@link AgentFailureAlertService}，待办 #6 P7 治理）：
     * {@code FAILED}（FORWARD 不补偿）与 {@code COMPENSATION_FAILED}（补偿没撤干净）→ 通知租户联系人；
     * {@code COMPENSATED}（已自愈回滚）→ 只记日志不打扰。无头触发（定时/表单事件）的执行用户不在场，
     * 靠这条告警让失败终态可观测。告警失败只打日志，绝不影响收尾。
     *
     * <p>🔴 落库走 {@code updateByIdUnlessCancelled}：本行可能已被 {@code POST /agent/workflow/cancel}
     * 判成 CANCELLED，而那条图还在 boundedElastic 上跑（取消停不住已经在跑的节点，见 {@code cancel} 的 javadoc）。
     * 晚到的收尾因此写不进库，返回 0 行 ⟹ 本方法返回 false，补偿与告警一并跳过。
     *
     * @return 本次收尾是否<b>真的落进了库</b>（既要抢到 settle-once 的 CAS，又要求那次 UPDATE 影响了 1 行）。
     *         需要「状态确实已落库」这个保证的调用方要看它 —— 中断分支只有拿到 true 才发
     *         {@code INTERRUPTED} 事件，否则聊天端会渲染出一张点了必然被拒的确认卡。
     *         🔴 被取消挡下的那次收尾也返回 false，理由同上：那一条执行不该再挂出任何可点的卡。
     */
    private boolean settle(AtomicBoolean settled, AgentWorkflowExecutionDO exec, AgentExecutionContext context,
                           String status, String errorMsg, Map<String, Object> finalState, long startMillis,
                           String failedAtNode) {
        if (!settled.compareAndSet(false, true)) {
            return false;
        }
        Long executionId = exec.getId();
        int rows = context.execute(() -> {
            AgentWorkflowExecutionDO update = new AgentWorkflowExecutionDO();
            update.setId(executionId);
            update.setStatus(status);
            update.setEndTime(LocalDateTime.now());
            update.setDurationMs((int) (System.currentTimeMillis() - startMillis));
            if (finalState != null && !finalState.isEmpty()) {
                String json = JSONUtil.toJsonStr(finalState);
                update.setVariableSnapshot(json);
                update.setOutputJson(json);
            }
            if (StrUtil.isNotBlank(errorMsg)) {
                // 🔴 truncateForColumn 而非 StrUtil.maxLength：后者返回 n+3 字符，写 varchar(1000) 会撞
                //    Data truncation，把「执行为什么失败」这条最该留下的证据换成一条数据库异常
                update.setErrorMsg(AgentTextUtils.truncateForColumn(errorMsg, ERROR_MSG_MAX_LENGTH));
            }
            if (StrUtil.isNotBlank(failedAtNode)) {
                // 🔴 B5-2：failedAtNode 从「死列」变成真写。两处都写——
                //    update 落库（执行历史页 / RespVO 读它），exec 是内存态，紧接着下面
                //    alertExecutionFailure(exec,...) → doAlert 读的是 exec.getFailedAtNode()。
                //    只写 update 的话，告警那一侧仍拿到 null，「写→读→展示」链断在中间。
                //    列宽 varchar(64)，同 errorMsg 一样用 truncateForColumn 防撞 Data truncation。
                String node = AgentTextUtils.truncateForColumn(failedAtNode, FAILED_AT_NODE_MAX_LENGTH);
                update.setFailedAtNode(node);
                exec.setFailedAtNode(node);
            }
            // 🔴 不是裸 updateById：本行可能已被 cancel 判成 CANCELLED，晚到的收尾不许覆盖它，
            //    见 AgentWorkflowExecutionMapper#updateByIdUnlessCancelled
            return executionMapper.updateByIdUnlessCancelled(update);
        });
        if (rows == 0) {
            // 该行已经是 CANCELLED（取消赢了这次并发）。🔴 必须返回 false：runGraph 的中断分支靠这个返回值
            // 决定发不发 INTERRUPTED 事件，返回 true 会让聊天端渲染出一张点了必然被拒的确认卡 ——
            // 那正是这个返回值当初被引进来要防的缺陷。补偿与告警也一并跳过：
            // 这条执行的结局是「用户按了停」，既不该反做（B3-1 的行为错），也不该进告警面（B3-1 的告警错）。
            log.info("[settle][执行({}) 已被取消，本次收尾（{}）不覆盖 CANCELLED，也不补偿不告警]",
                    executionId, status);
            return false;
        }
        // 失败收尾：先按 recovery 决定是否补偿，再据「最终终态」决定是否告警（在 context.execute 之外单独调，
        // compensate / alertExecutionFailure 各自会贴身份）。
        // 🔴 到这里只剩**两条** FAILED 路径（节点失败 / 流超时）—— 迭代七 B3-1 之前是三条，
        //    第三条「用户中断」已改落 CANCELLED，它不进这个 if，故不补偿也不告警。
        //    这正是 B3-1 那三层错里「行为错」「告警错」的消除点：不是新增判断把它们挡掉，
        //    而是取消从此不满足下面这个 equals(FAILED)，两个分支自然都不再触发。
        if (WorkflowExecutionStatusEnum.FAILED.getStatus().equals(status)) {
            if (AgentWorkflowCompensator.shouldCompensate(exec)) {
                // recovery=COMPENSATE：逆序反做已生效的副作用节点（FAILED→COMPENSATING→COMPENSATED/COMPENSATION_FAILED）
                String finalStatus = status;
                String finalError = errorMsg;
                try {
                    AgentWorkflowCompensator.CompensationResult result = compensator.compensate(exec, context);
                    if (result != null) {
                        finalStatus = result.status();
                        finalError = StrUtil.blankToDefault(result.firstError(), errorMsg);
                    }
                } catch (Exception ex) {
                    // 补偿调度自身炸了（非单个工具失败）也不能把异常抛回 reactor 收尾链；终态保持 FAILED，交人工核对
                    log.error("[settle][执行({}) 触发 Saga 补偿时异常：{}]", executionId, ex.getMessage(), ex);
                }
                // COMPENSATED=已自愈回滚干净、无残留 → 不打扰管理员（仅日志）；FAILED/COMPENSATION_FAILED=需人工介入 → 告警
                if (WorkflowExecutionStatusEnum.COMPENSATED.getStatus().equals(finalStatus)) {
                    log.info("[settle][执行({}) 失败已自动补偿回滚（COMPENSATED），不发告警]", executionId);
                } else {
                    failureAlertService.alertExecutionFailure(exec, context, finalStatus, finalError);
                }
            } else {
                // recovery=FORWARD：失败即终态，副作用可能已部分生效且无自动撤销 → 告警
                failureAlertService.alertExecutionFailure(exec, context, status, errorMsg);
            }
        }
        return true;
    }

    private void updateCurrentNode(Long executionId, String nodeId, Map<String, Object> snapshot) {
        AgentWorkflowExecutionDO update = new AgentWorkflowExecutionDO();
        update.setId(executionId);
        update.setCurrentNode(nodeId);
        if (snapshot != null && !snapshot.isEmpty()) {
            update.setVariableSnapshot(JSONUtil.toJsonStr(snapshot));
        }
        executionMapper.updateById(update);
    }

    // ==================== 工具方法 ====================

    private void requireIdentity(AgentExecutionContext context) {
        // fail-close：拿不到身份就罢工，绝不降级放行（与 AgentExecutionContext 类注释一致）
        if (context == null || !context.isComplete()) {
            throw exception(AGENT_NO_LOGIN_CONTEXT);
        }
    }

    /**
     * B3-3：恢复执行前查<b>定义本身</b>还启不启用。
     *
     * <h3>🔴 为什么 resume 需要这道检查、而 run 不需要</h3>
     * {@code run()} 走 {@code getPublishedByCode}，DISABLED 的定义压根选不出来。
     * {@code resume()} 是按 executionId 取执行行、再按行里的 {@code workflow_def_id} 取定义 ——
     * 这条路<b>不经过「已发布」那道筛</b>，所以改前一个已停用的定义照样能被恢复执行。
     * 而挂起中的执行恰恰最容易绕过「停用」这个承诺：它可以停在 hitl 上几天，等停用之后才被人点恢复，
     * 一恢复就重新编译 DSL、重新调模型与工具、重新产生副作用。
     *
     * <p>⚠️ 这与 {@code WorkflowDefStatusEnum} 类注释那句「在途实例仍能按快照跑完」<b>不冲突</b>：
     * 那句讲的是<b>已经在跑</b>的图不因定义被停用而中途夭折；resume 不是「跑完」，是重新发起一次执行。
     *
     * <h3>🔴 三个恢复入口共用这一处</h3>
     * 人工点恢复（REST）、{@code WorkflowWaitResumeScheduler} 到点自动恢复、{@code publishEvent} 事件唤醒
     * —— 后两者都是调本类的 {@code resume()}，所以检查只有这一份、不会漏掉某个入口。
     * 两者对 {@code ServiceException} 都已有 catch 分支（记 info/warn 后跳过下一条），
     * 一条被停用的执行不会把整批唤醒带崩，代价只是它每轮被扫到时各记一条日志
     * —— 与轮询器对「失效租户到期行原样留着」的既有取舍同量级。
     *
     * <h3>🟡 告警面刻意只到日志（第 07 轮 B5-4 落定后复核仍如此）</h3>
     * 刻意<b>不</b>复用 {@link AgentFailureAlertService} 的任何一条通道：
     * <ul>
     *   <li>{@code alertExecutionFailure} 的语义与模板都是「执行进了 FAILED / COMPENSATION_FAILED 终态」，
     *       而本处执行<b>仍是 INTERRUPTED</b>、并没有失败 —— 借它发站内信等于替一个没失败的执行编失败病因
     *       （撞红线第 7 条）。</li>
     *   <li>{@code alertDispatchFailure}（B5-4 新增）的语义是「<b>触发</b>没能派发出去」，落点是
     *       {@code WorkflowTriggerInvoker} 那两条 {@code return false} 路径（owner 缺失 / run 同步段抛错）。
     *       本处是<b>恢复</b>被拒，不是触发派发：既没有「一次触发」这件事，执行行也早就建好了。
     *       硬套上去会让「未派发」占位（executionId=（未派发））与一个真实存在的 executionId 自相矛盾。</li>
     * </ul>
     * 故 B5-4 落定后，这里<b>仍然</b>只落一条可 grep 的 WARN：它既非派发失败也非执行失败，
     * 没有现成的告警面能诚实容纳它。真要主动推送，得另立一条「挂起执行无法恢复」的语义与模板，
     * 不在本轮范围（登记为残余面，见总账）。
     */
    private void requireDefResumable(AgentWorkflowDefDO def, AgentWorkflowExecutionDO exec) {
        if (!WorkflowDefStatusEnum.DISABLED.getStatus().equals(def.getStatus())) {
            return;
        }
        log.warn("[requireDefResumable][恢复被拒：执行({}) 绑定的定义({}) 已停用（defId={} v={}），"
                        + "该行保持 INTERRUPTED 不动；要清场请在执行历史里取消它，要放行请出新版本再发布。"
                        + "本处既非派发失败也非执行失败，无现成告警面可诚实容纳，只落日志]",
                exec.getId(), def.getCode(), def.getId(), def.getVersion());
        throw exception(WORKFLOW_DEF_DISABLED_NOT_RESUMABLE, def.getCode());
    }

    private void emit(Sinks.Many<WorkflowEvent> sink, WorkflowEvent event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.debug("[emit][事件({}) 未发出，下游已终止：{}]", event.getType(), result);
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

    /**
     * 增量4a：从挂起节点 id 反查 DSL 里的 wait(duration) 配置，算出到期自动唤醒时刻。
     *
     * <p>interruptBefore(wait) 挂起时 wait 节点<b>尚未执行</b>，故 duration 无法从执行结果拿，
     * 只能凭挂起节点 id 回到 {@link WorkflowDefDTO#getNodes()} 里查其 config。
     * 🔴 那个 id 取自 {@link DslToStateGraphCompiler#resolveSuspendNodeId}，<b>不是</b>
     * {@code InterruptionMetadata.node()}：后者指的是<b>最后一个跑完的</b>节点（只有挂起节点正好是
     * START 之后第一个时才碰巧相等），拿它来查，本方法与另两个 resolveXxx 会<b>全部匹配不上</b>、
     * 静默返回 null ⟹ {@code resume_at} 不落库 ⟹ 轮询器永远扫不到 ⟹ 流程静默卡在 INTERRUPTED。
     * 实测表与全部后果见那个方法的 javadoc。
     * 非 wait 节点（如 hitl.confirm）或查不到 → 返回 null（resume_at 保持 NULL，靠人工 resume）。
     *
     * <p>duration 解析委托 {@link DslToStateGraphCompiler#resolveWaitDurationSeconds(NodeDTO)} ——
     * 与编译期校验共用同一处解析，杜绝「编译期放行、运行期算错」的两套口径。
     */
    private LocalDateTime resolveWaitResumeAt(WorkflowDefDTO dto, String nodeId) {
        if (nodeId == null || dto.getNodes() == null) {
            return null;
        }
        for (NodeDTO n : dto.getNodes()) {
            if (nodeId.equals(n.getId()) && "wait".equals(n.getType())) {
                // 🔴 只有 duration 型才算 resume_at；event 型走 event_key（见 resolveWaitEventKey）。
                //   不守卫就对 event 节点调 resolveWaitDurationSeconds，会因缺 duration 抛 WORKFLOW_DSL_INVALID。
                // 走 WaitTypeEnum.parse 而不是裸 equals：parse 会 trim，与编译期 validateWaitConfig 同口径。
                // 原来这里是逐字比较，于是 " event " 能过发布闸门却在运行期认不出来（见下 resolveWaitEventKey）。
                if (WaitTypeEnum.parse(n.configStr("waitType")) != WaitTypeEnum.DURATION) {
                    return null;
                }
                long seconds = DslToStateGraphCompiler.resolveWaitDurationSeconds(n);
                return LocalDateTime.now().plusSeconds(seconds);
            }
        }
        return null;
    }

    /**
     * 增量4b：从挂起节点 id 反查 DSL 里的 wait(event) 配置，取出它等待的 eventKey。
     *
     * <p>与 {@link #resolveWaitResumeAt} 镜像：interruptBefore(wait) 挂起时 wait 节点<b>尚未执行</b>，只能凭挂起
     * 节点 id（🔴 同样取自 {@link DslToStateGraphCompiler#resolveSuspendNodeId}，理由见那一条）
     * 回到 {@link WorkflowDefDTO#getNodes()} 里查其 config。命中 wait 节点
     * 且 {@code waitType==event} → 返回其 eventKey（落 event_key 列，供 {@code POST /agent/workflow/event} 匹配唤醒）；
     * 其余（duration/hitl/非 wait/查不到）→ null。解析委托 {@link DslToStateGraphCompiler#resolveWaitEventKey(NodeDTO)}，
     * 与编译期校验共用同一处口径。
     *
     * <p>🔴 判 {@code waitType} 走 {@link WaitTypeEnum#parse(String)}（<b>会 trim</b>）而不是裸 {@code equals}。
     * 编译期 {@code validateWaitConfig} 与校验器 V14 都 trim，这一处过去不 trim，于是 {@code " event "} 能一路
     * 发布成功、V14 也照样要求作者声明唤醒事件，但挂起时 event_key <b>不落库</b> ⟹ {@code POST /event} 永远匹配不到，
     * 流程静默卡在 INTERRUPTED、既不报错也不自动唤醒。这正是本子系统最常见的缺陷形态：「配置存下了，运行期不生效，且无提示」。
     */
    private String resolveWaitEventKey(WorkflowDefDTO dto, String nodeId) {
        if (nodeId == null || dto.getNodes() == null) {
            return null;
        }
        for (NodeDTO n : dto.getNodes()) {
            if (nodeId.equals(n.getId()) && "wait".equals(n.getType())
                    && WaitTypeEnum.parse(n.configStr("waitType")) == WaitTypeEnum.EVENT) {
                return DslToStateGraphCompiler.resolveWaitEventKey(n);
            }
        }
        return null;
    }

    private String resolveRecovery(WorkflowDefDTO dto) {
        String recovery = dto.getSettings() == null ? null : dto.getSettings().getRecovery();
        return WorkflowRecoveryEnum.parse(recovery).getRecovery();
    }

    /**
     * P6：从挂起节点 id 反查 DSL 里的 {@code hitl.bpm_approval} 配置，<b>此刻发起 BPM 审批实例</b>，
     * 返回用作唤醒凭据的 businessKey（= event_key）。
     *
     * <p>与 {@link #resolveWaitEventKey} 镜像：interruptBefore 挂起时节点<b>尚未执行</b>，只能凭挂起节点 id
     * （🔴 取自 {@link DslToStateGraphCompiler#resolveSuspendNodeId}）回到 {@link WorkflowDefDTO#getNodes()}
     * 里查其 config。命中 hitl.bpm_approval 节点 →
     * 用执行所有者身份发起审批（{@code AgentBpmApprovalService.createApproval}，跨模块只走 bpm.api），
     * 返回 {@code agent-wf:executionId:nodeId} 形式的 businessKey 落 event_key；其余节点 → null。
     *
     * <p>🔴 这一条是三个 resolveXxx 里<b>后果最重的</b>：id 拿错 ⟹ 匹配不上 ⟹ 静默返回 null ⟹
     * <b>审批实例压根不发起</b>，审批人永远看不到任务，而执行停在 INTERRUPTED 且<b>无人能恢复</b>
     * （聊天端的「同意/拒绝」对 bpm_approval 无效，它等的是 BPM 的完成事件）。
     *
     * <p>🔴 发起人用 {@code exec.getUserId()}（执行所有者）而非当前审批人——审批应由流程触发者发起，
     * 与 wait 轮询器 {@code ofSystem(exec.getUserId(), tenantId)} 同口径。userId 为 null 无法发起，
     * 返回 null（fail-close：宁可停在挂起态等人工，也不匿名发起审批）。本方法已在 {@code context.execute}
     * 贴好身份的线程上调用，createApproval 内的 Flowable 租户即当前租户，无需额外传播。
     */
    private String resolveBpmApprovalEventKey(WorkflowDefDTO dto, String nodeId,
                                              AgentWorkflowExecutionDO exec, AgentExecutionContext context) {
        if (nodeId == null || dto.getNodes() == null) {
            return null;
        }
        for (NodeDTO n : dto.getNodes()) {
            if (nodeId.equals(n.getId())
                    && HitlBpmApprovalNodeExecutor.TYPE.equals(n.getType())) {
                Long userId = exec.getUserId();
                if (userId == null) {
                    log.warn("[resolveBpmApprovalEventKey][执行({}) 无 userId，无法发起 BPM 审批，停在挂起态等人工]",
                            exec.getId());
                    return null;
                }
                AgentBpmApprovalService.BpmApprovalConfig cfg = AgentBpmApprovalService.resolveConfig(n);
                String businessKey = AgentBpmApprovalService.buildBusinessKey(exec.getId(), nodeId);
                // S6：把变更清单冻结进流程变量。injectManifest=false 时传 null（作者主动关掉，页签不出现是对的）；
                // 构建失败时传**降级清单**而不是 null——两者在审批页上必须长得不一样，见 buildFrozenManifestTree。
                Object manifestTree = cfg.injectManifest() ? buildFrozenManifestTree(exec) : null;
                bpmApprovalService.createApproval(userId, cfg.processDefKey(), businessKey, cfg.formVars(), manifestTree);
                return businessKey;
            }
        }
        return null;
    }

    /**
     * S6：为「发起 BPM 审批」构建冻结用的变更清单对象树。
     *
     * <p>按执行实例的 {@code workflow_def_id} 取已存 DSL 再构建——这正是审批人要审的那份（已发布、已冻结）。
     * 载体是 JDK 类型树（见 {@code AgentBpmApprovalService#toManifestTree}），不是 POJO、不是 JSON 字符串。
     *
     * <p>🔴 构建失败<b>不再</b>返回 {@code null}，而是返回一份 {@link ChangeManifest#degraded(String)}：
     * 原先失败与「作者关掉了注入」同样表现为不注入，审批人无从分辨自己是在正常审批还是在盲批一个
     * 不可逆动作，而唯一的痕迹是服务端一行 {@code log.warn}（交接评审发现 ⑩ / 台账 #90）。
     *
     * <p>不变的底线：清单是附加信息，<b>任何情况下都不许反过来把审批卡死在挂起态</b>。
     * 故本方法吞掉一切异常；{@code degraded()} 产出的是一份固定的、Jackson 可直接序列化的形状，
     * 所以降级这一路本身不再套第二层 try——真要为它兜底，就等于给不会发生的场景加代码，
     * 而那段代码还测不到（见 {@code AgentWorkflowExecutionServiceImplManifestDegradeTest}）。
     */
    private Object buildFrozenManifestTree(AgentWorkflowExecutionDO exec) {
        try {
            ChangeManifest manifest = defService.buildManifestOrNull(null, exec.getWorkflowDefId(), null);
            if (manifest == null) {
                log.warn("[buildFrozenManifestTree][执行({}) 的变更清单构建失败，改注入降级清单]", exec.getId());
                return AgentBpmApprovalService.toManifestTree(
                        ChangeManifest.degraded("清单构建器返回空（多半是 DSL 读不出来或解析失败）"));
            }
            return AgentBpmApprovalService.toManifestTree(manifest);
        } catch (Exception ex) {
            log.warn("[buildFrozenManifestTree][执行({}) 的变更清单冻结失败，改注入降级清单：{}]",
                    exec.getId(), readable(ex));
            return AgentBpmApprovalService.toManifestTree(ChangeManifest.degraded(readable(ex)));
        }
    }

    private int resolveTimeout(WorkflowDefDTO dto) {
        if (dto.getSettings() != null && dto.getSettings().getExecutionTimeoutSeconds() != null
                && dto.getSettings().getExecutionTimeoutSeconds() > 0) {
            return dto.getSettings().getExecutionTimeoutSeconds();
        }
        return DEFAULT_TIMEOUT_SECONDS;
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
