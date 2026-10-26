package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentNodeExecutionMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowExecutionStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trace.NodeTraceRecorder;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.quota.AgentQuotaService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService.EventType;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService.WorkflowEvent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AgentWorkflowExecutionServiceImpl#buildEventFlux} 的<b>真跑图</b>夹具（迭代七第 04 轮硬前置，0 token）
 *
 * <h3>🔴 为什么必须有这一份（探针 H 的实测结论）</h3>
 * 把 {@code buildEventFlux} 末端的 {@code .onErrorResume(...)} <b>整段删掉</b>，
 * {@code mvn -o -pl enlev-module-agent test} 依然 {@code EXIT_CODE=0}、1574 个测试全绿 ——
 * 删掉一条容错分支而全量单测一个都不红，说明这条流当时处在<b>零测试覆盖</b>状态。
 * 成因是全仓 {@code src/test} 下零处引用 {@code buildEventFlux}：那几个看着在断言
 * {@code EventType.ERROR} 的用例（{@code AgentAssistantServiceImplWorkflowRouteTest} 等）
 * 全都 {@code when(executionService.run(...)).thenReturn(Flux.just(WorkflowEvent.error(...)))} ——
 * 它们测的是「mock 返回了什么」，不是「产品代码产出了什么」。
 *
 * <h3>🔴 本类是「真跑图」，不是 mock</h3>
 * 与上面那批用例的分界线就在这里：本类用<b>真</b> {@link DslParser} + <b>真</b> {@link DslToStateGraphCompiler}
 * 把 DSL 编译成 {@code StateGraph}，再让 {@code run(...)} 返回的那条流<b>真订阅、真跑完</b>。
 * 只有节点执行器是桩（{@code ok}/{@code boom}/{@code slow}）—— 因为真执行器要调大模型与外部工具，
 * 那样夹具就既烧 token 又联网、还不可重复。桩只替换「节点里干了什么」，
 * <b>不</b>替换「事件怎么产出、终态怎么落库」，而后者正是本夹具要钉的东西。
 * 构造配方与 {@code WaitNodeRuntimeTest} 同源（真编译器 + {@code MemorySaver} + mock 的 traceRecorder）。
 *
 * <p>被 mock 的只有持久化与旁路依赖（executionMapper / compensator / failureAlertService / quotaService）。
 * 故 F-2「该行落 FAILED」在本类里的判据是 <b>settle 交给 mapper 的那个 DO 的 status</b>，
 * 不是真库里的行；真库那一层由验收判据③（只读 SQL 查 {@code agent_workflow_execution}）覆盖。
 * 两者缺一不可：只断事件等于不管落库，只断落库等于不管用户看到了什么。
 *
 * <h3>钉住的四条性质</h3>
 * <ol>
 *   <li><b>T1 事件顺序基线</b>：正常跑完 → META 打头、DONE 收尾、NODE 在 DONE 之前、零个 ERROR、落 SUCCEEDED。</li>
 *   <li><b>T2 = F-1 + F-2</b>：节点炸 → 事件流里<b>恰好一个</b> ERROR（数量也钉，不许只钉「有」），
 *       且同一次失败落 FAILED、带上可读原因。事件与落库一起断言。</li>
 *   <li><b>T3 兜底超时</b>：{@code .timeout} 甩掉卡住的流 → 由 {@code .onErrorResume} 转成<b>恰好一个</b> ERROR
 *       正常收尾（订阅方拿到的是完整流，不是异常），并落 FAILED。
 *       🔴 <b>这一条就是探针 H 的判据</b>：删掉 {@code .onErrorResume} 后 {@code collectList().block()} 会抛，本测变红。</li>
 *   <li><b>T4 = F-3</b>：跑到一半取消 → 落<b>独立终态</b>（本轮改动后必须是 {@code CANCELLED}）、
 *       reason 记「用户中断」、<b>不触发补偿</b>、<b>不发失败告警</b>（取消不是故障，见第 07 轮告警面交接）。</li>
 * </ol>
 *
 * @author 走进新时代
 */
class AgentWorkflowExecutionServiceImplEventFluxRuntimeTest {

    /** run() 建实例时 mock 的 insert 回填给 DO 的执行 id */
    private static final Long EXECUTION_ID = 77L;
    private static final String CODE = "wf_evtflux";

    private AgentWorkflowExecutionServiceImpl service;
    private AgentWorkflowExecutionMapper executionMapper;
    private AgentWorkflowCompensator compensator;
    private AgentFailureAlertService failureAlertService;

    private final AgentExecutionContext context = AgentExecutionContext.ofForTest(9L, 1L);

    // ==================== 节点执行器桩（唯一被替换掉的一层） ====================

    /** 正常节点：写一个键就返回 */
    private static class OkExecutor implements NodeExecutor {
        @Override
        public String getType() {
            return "ok";
        }

        @Override
        public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
            return Map.of("ok", true);
        }
    }

    /** 炸掉的节点：制造「真跑图里节点失败」这条路（不靠 mock 抛异常，靠真执行器抛） */
    private static class BoomExecutor implements NodeExecutor {
        @Override
        public String getType() {
            return "boom";
        }

        @Override
        public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
            throw new IllegalStateException("节点炸了");
        }
    }

    /**
     * 慢节点：睡 {@code config.sleepMs} 再返回。
     *
     * <p>存在的唯一理由是<b>制造时间窗</b> —— 取消与兜底超时都只能发生在「图还在跑」的那段时间里。
     * 🔴 它睡的是 boundedElastic 线程，而 {@code runGraph} 里的 {@code blockLast()} 与外层流的
     * {@code .timeout()}/{@code dispose()} <b>都不会打断它</b>（{@code Schedulers.boundedElastic().schedule(...)}
     * 返回的 Disposable 被丢弃了）。所以「取消」在本子系统里是<b>记账语义</b>：判终态、且拒绝被后续收尾覆盖，
     * 而不是把已经在跑的图掐断 —— 那条限制写在 {@code AgentWorkflowExecutionService#cancel} 的 javadoc 里。
     */
    private static class SlowExecutor implements NodeExecutor {
        @Override
        public String getType() {
            return "slow";
        }

        @Override
        public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) throws Exception {
            Thread.sleep(node.configInt("sleepMs", 1000));
            return Map.of("slowDone", true);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executionMapper = mock(AgentWorkflowExecutionMapper.class);
        compensator = mock(AgentWorkflowCompensator.class);
        failureAlertService = mock(AgentFailureAlertService.class);

        // 真 parser + 真 compiler（配方同 WaitNodeRuntimeTest）：DSL 真编译成 StateGraph 真跑
        DslParser parser = new DslParser();
        ReflectionTestUtils.setField(parser, "objectMapper", new ObjectMapper());
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        registry.setExecutors(List.of(new OkExecutor(), new BoomExecutor(), new SlowExecutor()));
        DslToStateGraphCompiler compiler = new DslToStateGraphCompiler();
        ReflectionTestUtils.setField(compiler, "registry", registry);
        ReflectionTestUtils.setField(compiler, "traceRecorder", mock(NodeTraceRecorder.class));
        ReflectionTestUtils.setField(compiler, "defService", mock(AgentWorkflowDefService.class));
        ReflectionTestUtils.setField(compiler, "dslParser", parser);

        AgentWorkflowDefService defService = mock(AgentWorkflowDefService.class);
        // run() 的第一段（Servlet 线程上的校验与建实例）需要这两格：取已发布定义、过配额闸门。
        // 🔴 用 anyString() 而不是只桩 CODE：四份 DSL 各有各的 code，桩窄了另外三条会拿到 null 定义直接 NPE
        when(defService.getPublishedByCode(anyString())).thenAnswer(inv -> def(inv.getArgument(0)));
        // insert 是真库才有 id 的动作，mock 里回填一个固定 id，否则 threadName 会拼成 "t1:null"
        when(executionMapper.insert(any(AgentWorkflowExecutionDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, AgentWorkflowExecutionDO.class).setId(EXECUTION_ID);
            return 1;
        });
        // 🔴 这一格<b>必须</b>桩，且是本夹具最容易被后人删掉的一行。
        //    settle 收尾走的是 mapper 的 default 方法 updateByIdUnlessCancelled（B3-1：不许覆盖已取消的行），
        //    而 Mockito 的 mock <b>不执行</b> default 方法体 —— 不桩就恒返回 0，
        //    settle 会以为「本行已被取消」而直接 return false：不写库、不补偿、不告警、INTERRUPTED 事件也不发。
        //    表现是四条测全部报「实际：[]」（一次收尾都没捕获到），看起来像夹具坏了，其实是它诚实地
        //    测了另一条分支。真库上这一格由 SQL 的 status<>'CANCELLED' 决定，属验收③的活。
        when(executionMapper.updateByIdUnlessCancelled(any(AgentWorkflowExecutionDO.class))).thenReturn(1);

        ObjectProvider<ObservationRegistry> observationRegistryProvider = mock(ObjectProvider.class);
        when(observationRegistryProvider.getIfAvailable(any(Supplier.class)))
                .thenReturn(ObservationRegistry.NOOP);

        service = new AgentWorkflowExecutionServiceImpl();
        ReflectionTestUtils.setField(service, "defService", defService);
        ReflectionTestUtils.setField(service, "dslParser", parser);
        ReflectionTestUtils.setField(service, "compiler", compiler);
        ReflectionTestUtils.setField(service, "executionMapper", executionMapper);
        ReflectionTestUtils.setField(service, "nodeExecutionMapper", mock(AgentNodeExecutionMapper.class));
        ReflectionTestUtils.setField(service, "bpmApprovalService", mock(AgentBpmApprovalService.class));
        ReflectionTestUtils.setField(service, "compensator", compensator);
        ReflectionTestUtils.setField(service, "failureAlertService", failureAlertService);
        ReflectionTestUtils.setField(service, "quotaService", mock(AgentQuotaService.class));
        // 生产上是 MysqlSaver（落 GRAPH_* 表）；测试一律换内存实现，与 WaitNodeRuntimeTest 同口径
        ReflectionTestUtils.setField(service, "checkpointSaver", new MemorySaver());
        ReflectionTestUtils.setField(service, "observationRegistryProvider", observationRegistryProvider);
    }

    /** 按 code 造一份已发布定义：dsl 正文取自同名的 DSL 常量（本夹具只有 4 份，全在下面） */
    private static AgentWorkflowDefDO def(String code) {
        AgentWorkflowDefDO def = new AgentWorkflowDefDO();
        def.setId(500L);
        def.setCode(code);
        def.setVersion(1);
        def.setStatus("PUBLISHED");
        def.setDsl(DSLS.get(code));
        return def;
    }

    // ==================== DSL（每份都是线性两节点，跑得完、不联网、0 token） ====================

    private static final String OK_DSL = """
            {"dslVersion":"1.0","id":"wf_evtflux","name":"事件流夹具·正常跑完",
             "variables":[{"key":"ok","type":"boolean","merge":"replace"}],
             "nodes":[{"id":"a","type":"ok","config":{}}],
             "edges":[{"from":"START","to":"a"},{"from":"a","to":"END"}]}
            """;

    private static final String BOOM_DSL = """
            {"dslVersion":"1.0","id":"wf_evtflux_boom","name":"事件流夹具·节点炸",
             "variables":[{"key":"ok","type":"boolean","merge":"replace"}],
             "nodes":[{"id":"a","type":"boom","config":{}}],
             "edges":[{"from":"START","to":"a"},{"from":"a","to":"END"}]}
            """;

    /**
     * 慢节点 + {@code recovery=compensate}。
     *
     * 🔴 compensate 是<b>刻意</b>声明的：取消这条路今天会落 FAILED，而 settle 见 FAILED+COMPENSATE 就触发
     * Saga 反做 —— 于是「用户按了停」反倒把他想停住的写操作又反做了一遍。T4 据此断言
     * {@code compensator} 一次都不该被调用；这条断言在改动前是红的，红得正是 B3-1 要修的那个行为。
     * 兜底超时给足 600 秒，保证 T4 里唯一能收尾的是「取消」而不是超时。
     */
    private static final String SLOW_DSL = """
            {"dslVersion":"1.0","id":"wf_evtflux_slow","name":"事件流夹具·慢且声明反做",
             "settings":{"recovery":"compensate","executionTimeoutSeconds":600},
             "variables":[{"key":"slowDone","type":"boolean","merge":"replace"}],
             "nodes":[{"id":"a","type":"slow","config":{"sleepMs":1500}}],
             "edges":[{"from":"START","to":"a"},{"from":"a","to":"END"}]}
            """;

    /** 慢节点（2.5s）+ 兜底超时 1s：专门制造「流被 .timeout 甩掉」这条路（T3） */
    private static final String SLOW_TIMEOUT_DSL = """
            {"dslVersion":"1.0","id":"wf_evtflux_timeout","name":"事件流夹具·慢且超时",
             "settings":{"executionTimeoutSeconds":1},
             "variables":[{"key":"slowDone","type":"boolean","merge":"replace"}],
             "nodes":[{"id":"a","type":"slow","config":{"sleepMs":2500}}],
             "edges":[{"from":"START","to":"a"},{"from":"a","to":"END"}]}
            """;

    /**
     * code → dsl。用 code 区分而不是一个方法传参，是为了让 {@code run(code)} 这条入口保持与生产同形。
     *
     * 🔴 本常量必须排在上面四个 DSL 常量<b>之后</b>：Java 的静态字段按声明顺序初始化，
     * 排在前面的话 {@code Map.of} 读到的是四个 null，而 {@code Map.of} 不接受 null 值 —— 会在类加载期直接抛。
     */
    private static final Map<String, String> DSLS = Map.of(
            "wf_evtflux", OK_DSL,
            "wf_evtflux_boom", BOOM_DSL,
            "wf_evtflux_slow", SLOW_DSL,
            "wf_evtflux_timeout", SLOW_TIMEOUT_DSL);

    // ==================== 断言工具 ====================

    /**
     * 从 mapper 收到的所有 UPDATE 里挑出「settle 的那一次收尾写」。
     *
     * <p>🔴 捕获点是 {@code updateByIdUnlessCancelled} 而<b>不是</b> {@code updateById}（B3-1 之后改的）：
     * settle 现在走前者，为的是「本行可能已被 cancel 判成 CANCELLED，晚到的收尾不许覆盖它」。
     * 继续捕 {@code updateById} 的话一次收尾都抓不到（实测四条测全报「实际：[]」）。
     *
     * <p>🔴 仍要过滤，不能直接 {@code verify(mapper).updateByIdUnlessCancelled(...)} 了事 —— 一次执行里
     * mapper 至少被写三次，而其中<b>两次也带 status</b>：
     * <ul>
     *   <li>{@code run()} 建完实例后回写 {@code thread_name} 时顺手把 CREATED 改成 <b>RUNNING</b>（带 status）；</li>
     *   <li>{@code runGraph} 每跑完一个节点调 {@code updateCurrentNode}（status 为 null）；</li>
     *   <li>{@code settle} 收尾（带 status，且<b>唯一</b>会写 end_time 的那一次）。</li>
     * </ul>
     * 只按「status 非空」过滤会把 RUNNING 那次也算进来（实测 {@code [RUNNING, FAILED]}，两条）——
     * 那是本夹具刚写出来时真抓到的一个坑，不是假想。故判据是「status 非空 <b>且</b> end_time 非空」。
     *
     * <p>⚠️ 眼下前两格走的是 {@code updateById}、与本方法的捕获点不同，故过滤条件<b>暂时是空转的</b>。
     * 刻意保留：它挡的是「将来有人把别的写也挪到 {@code updateByIdUnlessCancelled} 上」那一刻，
     * 而那一刻静悄悄、不会有任何编译错误提醒。
     */
    private List<AgentWorkflowExecutionDO> settleWrites() {
        ArgumentCaptor<AgentWorkflowExecutionDO> captor =
                ArgumentCaptor.forClass(AgentWorkflowExecutionDO.class);
        verify(executionMapper, atLeastOnce()).updateByIdUnlessCancelled(captor.capture());
        return captor.getAllValues().stream()
                .filter(u -> u.getStatus() != null && u.getEndTime() != null).toList();
    }

    private static long countType(List<WorkflowEvent> events, EventType type) {
        return events.stream().filter(e -> e.getType() == type).count();
    }

    private Flux<WorkflowEvent> run(String code) {
        return service.run(code, Map.of(), context);
    }

    // ==================== T1 事件顺序基线 ====================

    @Test
    @DisplayName("正常跑完：META 打头 → NODE → DONE 收尾，零个 ERROR，落 SUCCEEDED")
    void run_happyPath_emitsMetaNodeDoneInOrder_andSettlesSucceeded() {
        List<WorkflowEvent> events = run(CODE).collectList().block(Duration.ofSeconds(20));

        assertNotNull(events, "正常跑完的流应当正常结束，而不是抛错");
        assertEquals(EventType.META, events.get(0).getType(), "第一个事件必须是 META（前端靠它拿 executionId）");
        assertEquals(EXECUTION_ID, events.get(0).getExecutionId(), "META 必须带上 run() 建出来的执行 id");
        assertEquals(1, countType(events, EventType.DONE), "正常跑完应当恰好一个 DONE");
        assertEquals(0, countType(events, EventType.ERROR), "正常跑完不该有任何 ERROR 事件");

        // 顺序：NODE(a) 必须在 DONE 之前 —— 「事件顺序改坏」时这一条先红（验收判据②的变异探针靶子）
        int nodeIdx = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getType() == EventType.NODE && "a".equals(events.get(i).getNodeId())) {
                nodeIdx = i;
            }
        }
        int doneIdx = events.size() - 1;
        assertTrue(nodeIdx >= 0, "应当有节点 a 的 NODE 事件，实际事件：" + types(events));
        assertTrue(nodeIdx < doneIdx, "NODE(a) 必须在 DONE 之前，实际事件：" + types(events));

        List<AgentWorkflowExecutionDO> settles = settleWrites();
        assertEquals(1, settles.size(), "settle-once：正常跑完只该收尾一次，实际：" + statuses(settles));
        assertEquals(WorkflowExecutionStatusEnum.SUCCEEDED.getStatus(), settles.get(0).getStatus());
    }

    // ==================== T2 = F-1 + F-2 ====================

    @Test
    @DisplayName("F-1/F-2：节点炸 → 事件流里恰好一个 ERROR，且同一次失败落 FAILED 带可读原因")
    void run_nodeFails_emitsExactlyOneError_andSettlesFailed() {
        List<WorkflowEvent> events = run("wf_evtflux_boom").collectList().block(Duration.ofSeconds(20));

        assertNotNull(events, "节点失败被 runGraph 接住转成 ERROR 事件，流应当正常结束而不是抛错");
        // 🔴 数量也要钉：只断「有 ERROR」的话，同一次失败发两条 ERROR 也算过 ——
        //    而前端每收到一条就渲染一次，两条就是「同一个错弹两遍」
        assertEquals(1, countType(events, EventType.ERROR),
                "一次失败只该产出一个 ERROR 事件，实际事件：" + types(events));
        assertEquals(0, countType(events, EventType.DONE), "失败了就不该再发 DONE");
        WorkflowEvent error = events.stream()
                .filter(e -> e.getType() == EventType.ERROR).findFirst().orElseThrow();
        assertEquals(EXECUTION_ID, error.getExecutionId(), "ERROR 必须带上执行 id，否则前端不知道是哪次执行炸的");
        assertTrue(error.getErrorMsg() != null && error.getErrorMsg().contains("节点炸了"),
                "ERROR 必须带可读原因（真执行器抛的那句），实际：" + error.getErrorMsg());

        // F-2：事件与落库必须一起断言，只断一半等于没断
        List<AgentWorkflowExecutionDO> settles = settleWrites();
        assertEquals(1, settles.size(), "一次失败只该收尾一次，实际：" + statuses(settles));
        assertEquals(WorkflowExecutionStatusEnum.FAILED.getStatus(), settles.get(0).getStatus(),
                "节点失败必须落 FAILED");
        assertTrue(settles.get(0).getErrorMsg() != null && settles.get(0).getErrorMsg().contains("节点炸了"),
                "落库也要留下可读原因，实际：" + settles.get(0).getErrorMsg());
    }

    // ==================== T3 兜底超时（探针 H 的判据） ====================

    @Test
    @DisplayName("兜底超时：.timeout 甩掉卡住的流 → 恰好一个 ERROR 且正常收尾（删掉 .onErrorResume 本测必红）")
    void run_streamTimeout_isConvertedToSingleErrorEvent_andSettlesFailed() {
        // 🔴 这一行本身就是断言的一部分：末端 .onErrorResume 的作用是把「流以异常终止」
        //    换成「流以一条 ERROR 事件正常结束」。删掉它，block() 会抛 RuntimeException 而不是返回列表。
        List<WorkflowEvent> events =
                run("wf_evtflux_timeout").collectList().block(Duration.ofSeconds(20));

        assertNotNull(events, "超时后订阅方仍应拿到一个正常结束的流（由 .onErrorResume 兜），而不是异常");
        assertEquals(1, countType(events, EventType.ERROR),
                "超时只该产出一个 ERROR 事件，实际事件：" + types(events));
        assertEquals(0, countType(events, EventType.DONE), "超时不是跑完，不该发 DONE");
        WorkflowEvent error = events.stream()
                .filter(e -> e.getType() == EventType.ERROR).findFirst().orElseThrow();
        assertTrue(error.getErrorMsg() != null && error.getErrorMsg().contains("TimeoutException"),
                "ERROR 的原因应当能看出是超时，实际：" + error.getErrorMsg());

        List<AgentWorkflowExecutionDO> settles = settleWrites();
        assertEquals(1, settles.size(), "超时只该收尾一次，实际：" + statuses(settles));
        assertEquals(WorkflowExecutionStatusEnum.FAILED.getStatus(), settles.get(0).getStatus(),
                "超时属于失败，落 FAILED（与取消分开：取消是 T4）");
    }

    // ==================== T4 = F-3（B3-1 / B3-2） ====================

    @Test
    @DisplayName("F-3：跑到一半取消 → 落 CANCELLED、reason「用户中断」、不触发补偿、不发失败告警")
    void cancel_midRun_settlesCancelled_withoutCompensationOrAlert() throws Exception {
        List<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        Disposable subscription = run("wf_evtflux_slow").subscribe(events::add);
        // 等图真跑起来（META 已到、slow 节点正在睡 1.5s），此刻取消才有意义
        Thread.sleep(400);
        subscription.dispose(); // = SSE 客户端断开 / AbortController.abort()，触发链路末端的 .doOnCancel

        // 🔴 settle 在 dispose() 所在线程上同步执行（含阻塞 DB 写），故 dispose 返回后即可断言，无需等待
        List<AgentWorkflowExecutionDO> settles = settleWrites();
        assertEquals(1, settles.size(), "取消只该收尾一次，实际：" + statuses(settles));
        assertEquals(WorkflowExecutionStatusEnum.CANCELLED.getStatus(), settles.get(0).getStatus(),
                "🔴 用户主动中止必须落独立终态 CANCELLED，而不是 FAILED —— 落了 FAILED 就再也分不出"
                        + "「我按了停」和「它崩了」，实际：" + settles.get(0).getStatus());
        assertEquals("用户中断", settles.get(0).getErrorMsg(), "取消的原因应当直说「用户中断」");

        // 取消不是失败：不该触发 Saga 反做（否则把用户想停住的写操作又反做一遍），也不该进告警面
        verify(compensator, never()).compensate(any(), any());
        verify(failureAlertService, never()).alertExecutionFailure(any(), any(), any(), any());
    }

    // ==================== 断言失败时给人看的两个投影 ====================

    private static String types(List<WorkflowEvent> events) {
        return events.stream().map(e -> String.valueOf(e.getType())).toList().toString();
    }

    private static String statuses(List<AgentWorkflowExecutionDO> settles) {
        return settles.stream().map(AgentWorkflowExecutionDO::getStatus).toList().toString();
    }

}
