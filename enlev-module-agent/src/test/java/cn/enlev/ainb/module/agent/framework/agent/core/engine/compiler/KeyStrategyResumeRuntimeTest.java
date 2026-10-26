package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.VariableDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlConfirmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.LlmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ParallelNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ToolNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.WaitNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.io.NodeIoContract;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trace.NodeTraceRecorder;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 「注册过 KeyStrategy 才活得过 resume」这条约束的<b>真挂起/真恢复</b>单测（0 模型 token）
 *
 * <h3>① 钉住缺陷 C8：非 hitl 的契约缺省输出名同样会死在恢复瞬间</h3>
 *
 * <p>已实测的机制（{@code WaitNodeRuntimeTest} 的类 javadoc 也记着）：SAA resume 走
 * {@code OverAllState.input(checkpointState)} 重载状态，而 {@code input()} <b>只保留注册过
 * KeyStrategy 的键</b>；{@code defaultStrategy(REPLACE)} 只兜「同一次运行内」，不给未声明键补注册。
 *
 * <p>C8 之前只有三个 hitl 回调键被无条件注册，于是 {@code tool_result} / {@code llm_output} 这类
 * 「作者没填 outputVar、执行器回落到缺省名」的键，只要下游还有一个挂起点就会<b>静默变 null</b> ——
 * 而 {@code KeyStrategyFactoryGeneratorTest} 那种纯 Map 断言证明不了「真的活过了 resume」，
 * 必须端到端跑一遍。本类第 ① 组就是那一遍。
 *
 * <h3>② 钉住 B3-7 的前置事实：即便 C8 落地、且作者声明齐了，差集仍然恒非空</h3>
 *
 * <p>迭代六 B3-7 要求「resume 时把会被丢掉的键算出差集、落日志并回给前端」，并断言
 * 「C8 不改，差集会恒非空」。2026-10-06 用一次性探针实测三种形状后结论是：<b>这句话不完整</b> ——
 * C8 改完了差集<b>照样</b>恒非空，因为 state 里还有一批<b>从来没人注册</b>的引擎簿记键：
 *
 * <table border="1">
 *   <caption>2026-10-06 实测。这三种形状下注册集都是 9 个键（= 7 个契约缺省名 + 引擎自补的
 *   {@code OverAllState.DEFAULT_INPUT_KEY} + 夹具声明的 done）；🔴 换一份声明不同的 DSL 这个数就变，
 *   所以常驻测试<b>刻意不断言它</b>，改成断言「挂起点的注册集 − 拿 def 重算的注册集 = 恰好那个入口键」，
 *   见 {@link #droppedKeysAtSuspend_neverEmpty_evenWhenEverythingIsDeclared}。</caption>
 *   <tr><th>形状</th><th>挂起点 state 的键</th><th>差集（会被 input() 丢掉的）</th></tr>
 *   <tr><td>A {@code tool → hitl.bpm_approval → capture}</td>
 *       <td>{@code _graph_execution_id_} / input / tool_result</td>
 *       <td>{@code _graph_execution_id_}</td></tr>
 *   <tr><td>B = A 再在上游加一个 {@code condition.rule}</td>
 *       <td>{@code __route_c1} / {@code _graph_execution_id_} / input / tool_result</td>
 *       <td>{@code __route_c1} / {@code _graph_execution_id_}</td></tr>
 *   <tr><td>C {@code hitl.confirm → wait → capture}，在<b>第二个</b>挂起点测</td>
 *       <td>{@code _graph_execution_id_} / {@code gate_confirmed} / {@code hitl_approved} / input</td>
 *       <td>{@code _graph_execution_id_} / {@code gate_confirmed}</td></tr>
 * </table>
 *
 * <p>三类来源，只有第一类是我们自己的：
 * <ol>
 *   <li>{@code _graph_execution_id_} —— SAA 框架自己写的，本仓<b>没有任何一处</b>引用这个名字，
 *       也没有常量可引；它在<b>三种形状里全部出现</b> ⟹ B3-7 若不过滤，就是<b>每一次 resume 都报警</b>，
 *       把框架簿记说成「作者的变量会被静默丢掉」。那正是校验器自己注释里反复提防的
 *       「教作者无视红字」。</li>
 *   <li>{@code __route_<nodeId>}（{@code NodeExecutor.ROUTE_KEY_PREFIX}）、{@code __iter_*}、
 *       {@code __agentloop_*} —— 我们自己的内部键，有 {@code __} 前缀可循。</li>
 *   <li>{@code <nodeId>_confirmed} / {@code _waited} / {@code _result} / {@code _status} ——
 *       三个执行器各自 {@code delta.put} 的审计键，<b>没有统一前缀也没有常量</b>，散在三个类里。
 *       形状 C 证明它不是「resume 之后才写、所以无害」：第一次挂起前写的审计键，
 *       会一路活到<b>第二个</b>挂起点并进差集。</li>
 * </ol>
 *
 * <p>🔴 所以第 ② 组刻意<b>只钉性质、不钉字面名</b>：断言差集非空、且差集里<b>一个</b>作者声明过的变量
 * 和<b>一个</b>契约缺省名都没有。这样 SAA 哪天把 {@code _graph_execution_id_} 改名，本测试不会因为
 * 一个与本仓无关的理由变红；而 B3-7 真要落地时，「必须先有过滤器」这个前置条件被钉在测试里而不是只写在文档里。
 *
 * <h3>③ 钉住 V23 的前提：过滤是<b>双向</b>的，入站 feedback 同样会被滤掉</h3>
 *
 * <p>①② 讨论的都是<b>出站</b>——节点写进 state 的值活不活得过下一次 resume。V19 的判据据此
 * 挖掉了挂起节点自己（{@code hitl.*} 是在自己那次 resume 之后才写 {@code approveVar} 的，
 * 自己伤不到自己的写入）。那个自排除对出站是对的，但它让出了整整一档<b>入站</b>危害：
 * 调用方按作者配的自定义 {@code approveVar} 传 feedback 时，那个值要在<b>进 state 之前</b>
 * 先过同一道 {@code input()}，没注册就地被滤掉 ⟹ 执行器读到 null ⟹ 回退默认键也没人写
 * ⟹ 「同意」被静默判成「拒绝」，而 stream 正常结束、执行状态报 SUCCEEDED。
 *
 * <p>第 ③ 组就是这一档的三形状对照（未声明+自定义键 → rejected；已声明+自定义键 → approved；
 * 未声明+默认键 → approved）。三者<b>只差两个维度</b>，所以它同时证明了「危害是真的」
 * 与「校验器文案给的那条出路是真的有效」—— 后者尤其要紧：给一条救不了的出路比不给更坏。
 * 校验器那一侧的规则是 {@code WorkflowDslValidator#validateSuspendFeedbackVarsDeclared}（V23）。
 *
 * @author 走进新时代
 */
@DisplayName("agent KeyStrategy 注册：缺省输出名必须真的活过 resume（C8），差集恒非空（B3-7 前置），"
        + "且入站 feedback 同样会被滤掉（V23）")
class KeyStrategyResumeRuntimeTest {

    private DslToStateGraphCompiler compiler;
    private DslParser parser;
    private CaptureExecutor captureExecutor;

    /** 桩：记录恢复后自己读到的键（等价于下游节点看到的变量），并写 outputVar */
    private static class CaptureExecutor implements NodeExecutor {
        final Map<String, Object> captured = new LinkedHashMap<>();
        final AtomicInteger runs = new AtomicInteger(0);

        @Override
        public String getType() {
            return "capture";
        }

        @Override
        public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
            runs.incrementAndGet();
            for (Object k : node.configList("captureKeys")) {
                captured.put(String.valueOf(k), ctx.get(String.valueOf(k)));
            }
            Map<String, Object> delta = new LinkedHashMap<>();
            String outputVar = node.configStr("outputVar");
            if (outputVar != null && !outputVar.isEmpty()) {
                delta.put(outputVar, "captured");
            }
            return delta;
        }
    }

    /**
     * 桩：按<b>真执行器的取键方式</b>写一个键 —— {@code configStr("outputVar", 缺省名)}。
     *
     * <p>🔴 必须复刻这个回落，不能直接写死缺省名：本类第 ① 组要证的正是「作者<b>没填</b> outputVar 时
     * 落的那个键」活得过 resume。写死缺省名会顺带把「作者填了自定义名」的情形也冒充成缺省名，
     * 于是执行器哪天不再回落，本测试照样绿。
     */
    private static class WritingStubExecutor implements NodeExecutor {
        private final String type;
        private final String defaultKey;

        WritingStubExecutor(String type, String defaultKey) {
            this.type = type;
            this.defaultKey = defaultKey;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
            return Map.of(node.configStr("outputVar", defaultKey), node.configStr("stubValue", type));
        }
    }

    @BeforeEach
    void setUp() {
        parser = new DslParser();
        ReflectionTestUtils.setField(parser, "objectMapper", new ObjectMapper());

        captureExecutor = new CaptureExecutor();
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        registry.setExecutors(List.of(
                new ParallelNodeExecutor(),
                new WaitNodeExecutor(),
                new HitlConfirmNodeExecutor(),
                captureExecutor,
                new WritingStubExecutor("tool", ToolNodeExecutor.DEFAULT_OUTPUT_VAR),
                new WritingStubExecutor("llm", LlmNodeExecutor.DEFAULT_OUTPUT_VAR)));

        compiler = new DslToStateGraphCompiler();
        ReflectionTestUtils.setField(compiler, "registry", registry);
        ReflectionTestUtils.setField(compiler, "traceRecorder", mock(NodeTraceRecorder.class));
        ReflectionTestUtils.setField(compiler, "defService", mock(AgentWorkflowDefService.class));
        ReflectionTestUtils.setField(compiler, "dslParser", parser);
    }

    private WorkflowDefDTO parse(String dsl) throws Exception {
        return parser.parse(dsl);
    }

    private CompiledGraph compile(WorkflowDefDTO def) throws Exception {
        return compiler.compile(def, 1L, "t1:1",
                AgentExecutionContext.ofForTest(1L, 1L),
                new MemorySaver(), ObservationRegistry.NOOP);
    }

    /**
     * 跑到挂起，断言挂起点是指定节点、且下游 capture 确实没跑。
     *
     * <p>🔴 判「挂在哪个节点」走 {@link DslToStateGraphCompiler#resolveSuspendNodeId}（checkpoint 快照的
     * {@code next()}），<b>不用</b> {@code InterruptionMetadata.node()} —— 后者指的是最后一个跑完的节点，
     * 本类的 DSL 里挂起节点前面都有 tool/llm/hitl，用它会一路指错。实测表见 {@code SuspendNodeIdRuntimeTest}。
     */
    private InterruptionMetadata runUntilSuspended(CompiledGraph graph, RunnableConfig config, String gateId) {
        List<NodeOutput> first = graph.stream(Map.of(), config).collectList().block();
        assertNotNull(first, "挂起时 stream 应正常结束，而非抛错");
        NodeOutput last = first.get(first.size() - 1);
        assertTrue(last instanceof InterruptionMetadata,
                "末元素应是 InterruptionMetadata（interruptBefore 挂起），实际：" + last.getClass());
        assertEquals(gateId, DslToStateGraphCompiler.resolveSuspendNodeId(graph, config), "挂起节点不符");
        assertEquals(0, captureExecutor.runs.get(), "挂起时下游 capture 不应执行");
        return (InterruptionMetadata) last;
    }

    private List<NodeOutput> resume(CompiledGraph graph, RunnableConfig config,
                                    Map<String, Object> feedback) throws Exception {
        RunnableConfig updated = graph.updateState(config, feedback);
        List<NodeOutput> resumed = graph.stream(null, updated.withResume()).collectList().block();
        assertNotNull(resumed, "恢复流应正常结束");
        return resumed;
    }

    // ========== ① C8：未声明的契约缺省输出名必须真的活过 resume ==========

    /**
     * {@code variables[]} <b>只声明 done</b>：{@code tool_result} / {@code llm_output} 都没声明，
     * 靠 C8 那一趟「遍历契约缺省名无条件注册」才活得下来。这才是编排器/AI 造出来的真实形状 ——
     * 缺省输出名是引擎内部约定，作者不会去声明它。
     */
    private static final String UNDECLARED_DEFAULTS_DSL = """
            {"dslVersion":"1.0","id":"wf_c8_undeclared","name":"未声明的契约缺省输出名",
             "variables":[
               {"key":"done","type":"string","merge":"replace"}
             ],
             "nodes":[
               {"id":"t","type":"tool","config":{"stubValue":"T"}},
               {"id":"m","type":"llm","config":{"stubValue":"L"}},
               {"id":"w","type":"wait","config":{"waitType":"duration","duration":3}},
               {"id":"cap","type":"capture",
                "config":{"captureKeys":["tool_result","llm_output"],"outputVar":"done"}}
             ],
             "edges":[
               {"from":"START","to":"t"},
               {"from":"t","to":"m"},
               {"from":"m","to":"w"},
               {"from":"w","to":"cap"},
               {"from":"cap","to":"END"}
             ]}
            """;

    /**
     * 🔴 自证非空洞：这份 DSL 的 {@code variables[]} 里<b>确实没有</b>那两个键。
     *
     * <p>少了这条，将来有人为了让下面那条变绿而把它们补进 {@code variables[]}，测试照样通过，
     * 而它证明的已经变成「声明过的键活得过 resume」（{@code WaitNodeRuntimeTest} 早就证过了），
     * C8 那一趟被整段删掉也不会红 —— <b>恒绿的负控比没有负控更坏</b>，这里同理。
     */
    @Test
    @DisplayName("夹具自检：tool_result / llm_output 确实没被 variables[] 声明")
    void fixture_reallyLeavesContractDefaultsUndeclared() throws Exception {
        Set<String> declared = declaredKeys(parse(UNDECLARED_DEFAULTS_DSL));

        assertFalse(declared.contains(ToolNodeExecutor.DEFAULT_OUTPUT_VAR),
                "夹具被改坏了：tool_result 一旦声明，下面那条就退化成「声明过的键活得过 resume」，证不了 C8");
        assertFalse(declared.contains(LlmNodeExecutor.DEFAULT_OUTPUT_VAR),
                "夹具被改坏了：llm_output 同上");
        assertEquals(Set.of("done"), declared, "夹具应只声明 done，实际：" + declared);
    }

    /**
     * C8 的<b>运行期</b>强制点：两个非 hitl 的缺省输出名在挂起前写入、恢复后仍被下游读到。
     *
     * <p>为什么必须是运行期测试：{@code KeyStrategyFactoryGeneratorTest} 断的是
     * 「工厂产出的 Map 里有这个键」，而缺陷发生在<b>之后</b>——
     * {@code OverAllState.input(checkpointState)} 拿这份 Map 过滤 checkpoint。
     * Map 里有键 ⟹ 过滤后还在，这一步是 SAA 的行为，只有真跑一遍才算证到。
     *
     * <p>🔴 负控（M5，2026-10-06 实测）：把 {@code KeyStrategyFactoryGenerator.generate} 里遍历
     * {@code NodeIoContract.defaultOutputNames()} 那一趟删掉，本条<b>第一条断言就红</b>
     * （captured 里是 null，{@code expected: <T> but was: <null>}）—— assertEquals 快速失败，
     * 后面 llm_output 那次断言<b>轮不到执行</b>，别把「只报一条」误读成「只坏了一个键」。
     * 同时红：{@code KeyStrategyFactoryGeneratorTest} 的
     * {@code generate_registersEveryContractDefaultName_evenWhenVariablesDoNotDeclareThem} 与
     * {@code generate_registeredDefaultsStayInSyncWithValidatorExemption}（C8 的单元级钉子）。
     * 同时<b>全绿</b>：本类另三条、三条 hitl 运行期测试、{@code WaitNodeRuntimeTest}、
     * {@code SuspendNodeIdRuntimeTest}、{@code NodeIoContractTest} —— 那正是 C8 之前的形态：
     * 缺省名没注册只坑「作者没声明」的那一档，而三个 hitl 键因为另有硬编码注册而侥幸活着。
     */
    @Test
    @DisplayName("🔴 C8：未声明的 tool_result / llm_output 活得过 wait 挂起 → 恢复")
    void resume_undeclaredNonHitlContractDefaults_surviveInputFilter() throws Exception {
        WorkflowDefDTO def = parse(UNDECLARED_DEFAULTS_DSL);
        CompiledGraph graph = compile(def);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();
        runUntilSuspended(graph, config, "w");

        List<NodeOutput> resumed = resume(graph, config, Map.of());

        assertEquals(1, captureExecutor.runs.get(), "恢复后应越过 wait 跑到下游 capture");
        assertEquals("T", captureExecutor.captured.get(ToolNodeExecutor.DEFAULT_OUTPUT_VAR),
                "tool 的缺省输出名未在 variables[] 声明，注册缺失时会被 OverAllState.input() 过滤掉、"
                        + "下游读到 null（C8 之前的形态）；实际："
                        + captureExecutor.captured.get(ToolNodeExecutor.DEFAULT_OUTPUT_VAR));
        assertEquals("L", captureExecutor.captured.get(LlmNodeExecutor.DEFAULT_OUTPUT_VAR),
                "llm 的缺省输出名同上；实际："
                        + captureExecutor.captured.get(LlmNodeExecutor.DEFAULT_OUTPUT_VAR));
        assertEquals("captured", resumed.get(resumed.size() - 1).state().value("done").orElse(null),
                "capture 应正常写出 done");
    }

    // ========== ② B3-7 前置：差集恒非空，且丢的绝不是作者的变量 ==========

    /**
     * 与上面那份的<b>唯一</b>区别：{@code tool_result} 被显式声明了。
     * 于是「作者该做的都做了」，差集里剩下的只可能是引擎自己的簿记键。
     */
    private static final String FULLY_DECLARED_DSL = """
            {"dslVersion":"1.0","id":"wf_c8_declared","name":"写入键全部已声明",
             "variables":[
               {"key":"tool_result","type":"string","merge":"replace"},
               {"key":"done","type":"string","merge":"replace"}
             ],
             "nodes":[
               {"id":"t","type":"tool","config":{"stubValue":"T"}},
               {"id":"w","type":"wait","config":{"waitType":"duration","duration":3}},
               {"id":"cap","type":"capture","config":{"captureKeys":["tool_result"],"outputVar":"done"}}
             ],
             "edges":[
               {"from":"START","to":"t"},
               {"from":"t","to":"w"},
               {"from":"w","to":"cap"},
               {"from":"cap","to":"END"}
             ]}
            """;

    /** 两次挂起：第一次 hitl.confirm，第二次 wait。用来钉「审计键会跨挂起点活下来」 */
    private static final String TWO_SUSPENDS_DSL = """
            {"dslVersion":"1.0","id":"wf_c8_two_suspends","name":"两次挂起",
             "variables":[
               {"key":"done","type":"string","merge":"replace"}
             ],
             "nodes":[
               {"id":"gate","type":"hitl.confirm","config":{}},
               {"id":"w","type":"wait","config":{"waitType":"duration","duration":3}},
               {"id":"cap","type":"capture","config":{"captureKeys":["hitl_approved"],"outputVar":"done"}}
             ],
             "edges":[
               {"from":"START","to":"gate"},
               {"from":"gate","to":"w"},
               {"from":"w","to":"cap"},
               {"from":"cap","to":"END"}
             ]}
            """;

    private static Set<String> declaredKeys(WorkflowDefDTO def) {
        if (def.getVariables() == null) {
            return Set.of();
        }
        return def.getVariables().stream().map(VariableDTO::getKey).collect(Collectors.toSet());
    }

    /**
     * 挂起点上「会被 resume 丢掉的键」= 状态里的键 − 注册过 KeyStrategy 的键。
     *
     * <p>🔴 注册集直接问 checkpoint 自己（{@code OverAllState.keyStrategies()}），不再拿 def 重算一遍：
     * 实测两者同集，而问 checkpoint 才是 B3-7 落地时的真实取数路径 ——
     * 那时手里只有一个 threadId 恢复出来的状态，重算需要 def，而 def 可能已经改过版本了。
     */
    private static Set<String> droppedAtSuspend(OverAllState state) {
        Set<String> dropped = new LinkedHashSet<>(state.data().keySet());
        dropped.removeAll(state.keyStrategies().keySet());
        return dropped;
    }

    /**
     * 三条形状共用的<b>性质</b>断言：差集非空，且里面一个作者声明过的变量、一个契约缺省名都没有。
     *
     * <p>🔴 刻意不断言 {@code _graph_execution_id_} 这类字面名。它是 SAA 框架的键，本仓无任何引用，
     * 钉字面名等于把「SAA 升级改了个内部键名」变成本仓的红灯 —— 一个与 C8/B3-7 毫无关系的理由。
     * 而「差集非空」这个性质正是 B3-7 必须先有过滤器的根据，它不随框架改名而失效。
     */
    private void assertOnlyEngineBookkeepingIsDropped(OverAllState state, WorkflowDefDTO def, String shape) {
        Set<String> dropped = droppedAtSuspend(state);
        Set<String> declared = declaredKeys(def);

        assertFalse(dropped.isEmpty(),
                "形状 " + shape + "：作者该声明的都声明了、契约缺省名也全注册了，差集竟为空？"
                        + "那说明 B3-7 的「恒非空」前提已经不成立（或本夹具没跑到真挂起点），"
                        + "state=" + state.data().keySet() + "，registered=" + state.keyStrategies().keySet());

        for (String key : dropped) {
            assertFalse(declared.contains(key),
                    "形状 " + shape + "：作者在 variables[] 里声明过的 " + key + " 竟会被 resume 丢掉 —— "
                            + "这就不是引擎簿记噪声，而是真缺陷了。差集=" + dropped);
            assertFalse(NodeIoContract.defaultOutputNames().contains(key),
                    "形状 " + shape + "：契约缺省输出名 " + key + " 竟会被 resume 丢掉 —— C8 回退了。差集=" + dropped);
        }
    }

    /**
     * B3-7 的前置事实（形状 A/B 的代表）：写入键<b>全部已声明</b>时差集仍非空。
     *
     * <p>顺带钉住实测的注册集规模，防「注册集悄悄变大把差集吞掉」这种变绿方式。
     */
    @Test
    @DisplayName("🔴 B3-7 前置：写入键全声明了，挂起点的差集仍非空（引擎簿记键没人注册）")
    void droppedKeysAtSuspend_neverEmpty_evenWhenEverythingIsDeclared() throws Exception {
        WorkflowDefDTO def = parse(FULLY_DECLARED_DSL);
        CompiledGraph graph = compile(def);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();

        InterruptionMetadata interrupt = runUntilSuspended(graph, config, "w");

        assertOnlyEngineBookkeepingIsDropped(interrupt.state(), def, "全声明");

        // 挂起点自带的注册集 = 拿 def 重算的那一份 + 引擎自补的入口键（OverAllState.DEFAULT_INPUT_KEY，
        // 实测就是它，generate() 不注册）。🔴 刻意写成「差集恰好是这一个键」而不是「size == 缺省名数 + N」：
        // 后者把实测数字钉成常量，契约增减一个缺省名、作者多声明一个变量都会让它红得莫名其妙；
        // 前者红了就一定说明「checkpoint 里的注册集与拿 def 重算的不同源」这件事本身变了 ——
        // 而这一条正是 B3-7「用哪条取数路径都行」的依据。
        Set<String> fromDef = KeyStrategyFactoryGenerator.generate(def).apply().keySet();
        Set<String> onlyAtSuspend = new LinkedHashSet<>(interrupt.state().keyStrategies().keySet());
        onlyAtSuspend.removeAll(fromDef);
        assertEquals(Set.of(OverAllState.DEFAULT_INPUT_KEY), onlyAtSuspend,
                "挂起点的注册集减去拿 def 重算的，应恰好只剩引擎自补的入口键。fromDef=" + fromDef
                        + "，挂起点=" + interrupt.state().keyStrategies().keySet());
        assertTrue(interrupt.state().keyStrategies().keySet().containsAll(fromDef),
                "def 里注册过的键在挂起点竟少了：fromDef=" + fromDef + "，挂起点="
                        + interrupt.state().keyStrategies().keySet());
    }

    /**
     * 形状 C：第一次挂起<b>前</b>由执行器写的审计键，会活到<b>第二个</b>挂起点并进差集。
     *
     * <p>🔴 这一条推翻了一个看起来很自然的推断：「审计键是节点执行时才写的，而 interruptBefore 挂起在
     * 节点执行之前，所以挂起点上看不到审计键」。单次挂起确实看不到，但<b>恢复之后</b>闸口执行了、
     * 写了 {@code gate_confirmed}，紧接着第二个挂起点就到了 —— 它就在状态里，而且没人注册。
     * 只测单挂起点的用例会漏掉整整一类噪声。
     */
    @Test
    @DisplayName("🔴 B3-7 前置：上一个挂起点的执行器审计键会跨到第二个挂起点的差集里")
    void droppedKeysAtSecondSuspend_includeFirstGateAuditKey() throws Exception {
        WorkflowDefDTO def = parse(TWO_SUSPENDS_DSL);
        CompiledGraph graph = compile(def);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();

        runUntilSuspended(graph, config, "gate");
        // 聊天内恢复喂的就是这一个写死的键（AgentAssistantServiceImpl.HITL_APPROVE_VAR）
        List<NodeOutput> resumed = resume(graph, config,
                Map.of(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR, true));

        NodeOutput last = resumed.get(resumed.size() - 1);
        assertTrue(last instanceof InterruptionMetadata,
                "第一次恢复后应停在第二个挂起点（wait），实际：" + last.getClass());
        assertEquals("w", DslToStateGraphCompiler.resolveSuspendNodeId(graph, config),
                "第二个挂起点应是 wait（InterruptionMetadata.node() 在这里会指回刚跑完的 gate，"
                        + "见 SuspendNodeIdRuntimeTest）");

        OverAllState state = last.state();
        assertOnlyEngineBookkeepingIsDropped(state, def, "两次挂起");

        Set<String> dropped = droppedAtSuspend(state);
        // HitlConfirmNodeExecutor 写的审计键：<nodeId> + "_confirmed"（无常量、无统一前缀，散在三个执行器里）
        assertTrue(dropped.contains("gate_confirmed"),
                "第一个闸口的审计键应出现在第二个挂起点的差集里，实际差集=" + dropped);
        // 而 hitl_approved 是契约缺省名（已注册）⟹ 它绝不能出现在差集里，这正是 C8 那一半在运行期的样子
        assertFalse(dropped.contains(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR),
                "hitl_approved 已无条件注册，不该被丢掉；差集=" + dropped);
        assertEquals(Boolean.TRUE, state.value(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR).orElse(null),
                "恢复注入的同意决定应还在状态里");
    }

    // ========== ③ V23：挂起节点<b>自己</b>那次 resume 的入站 feedback 同样会被滤掉 ==========

    /** 作者给 {@code approveVar} 起的自定义名。🔴 刻意<b>不是</b>任何契约缺省名，否则它就被无条件注册了 */
    private static final String CUSTOM_APPROVE_VAR = "user_approved";

    /**
     * @param declareCustomVar true = 在 {@code variables[]} 里补一条声明；两份 DSL 除此之外<b>逐字相同</b>
     */
    private static String customApproveVarDsl(boolean declareCustomVar) {
        String vars = declareCustomVar
                ? "{\"key\":\"done\",\"type\":\"string\",\"merge\":\"replace\"},"
                        + "{\"key\":\"" + CUSTOM_APPROVE_VAR + "\",\"type\":\"boolean\",\"merge\":\"replace\"}"
                : "{\"key\":\"done\",\"type\":\"string\",\"merge\":\"replace\"}";
        return """
                {"dslVersion":"1.0","id":"wf_v23","name":"自定义 approveVar",
                 "variables":[%s],
                 "nodes":[
                   {"id":"gate","type":"hitl.confirm","config":{"approveVar":"%s"}},
                   {"id":"cap","type":"capture","config":{"captureKeys":["%s","gate_confirmed"],"outputVar":"done"}}
                 ],
                 "edges":[
                   {"from":"START","to":"gate"},
                   {"from":"gate","to":"cap"},
                   {"from":"cap","to":"END"}
                 ]}
                """.formatted(vars, CUSTOM_APPROVE_VAR, CUSTOM_APPROVE_VAR);
    }

    /** 跑到挂起 → 按给定键名注入「同意」→ 恢复，返回闸口自己归一化出来的那个判定 */
    private Object resumeAndReadVerdict(boolean declareCustomVar, String feedbackKey) throws Exception {
        WorkflowDefDTO def = parse(customApproveVarDsl(declareCustomVar));
        CompiledGraph graph = compile(def);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();
        InterruptionMetadata interrupt = runUntilSuspended(graph, config, "gate");

        // 🔴 机制的那一半：这个键在挂起点到底注没注册。断言它，而不是只断言后果 ——
        //    只断后果的话，将来别人用别的方式（比如给执行器再加一层回退）把后果抹平，
        //    本类就再也说不出「危害来自注册缺失」这件事了。
        assertEquals(declareCustomVar, interrupt.state().keyStrategies().containsKey(CUSTOM_APPROVE_VAR),
                "自定义 approveVar 该不该被注册，只取决于 variables[] 里有没有那一条声明。挂起点注册集="
                        + interrupt.state().keyStrategies().keySet());

        resume(graph, config, Map.of(feedbackKey, true));
        assertEquals(1, captureExecutor.runs.get(), "恢复后应跑到下游 capture");
        return captureExecutor.captured.get("gate_confirmed");
    }

    /**
     * 🔴 <b>缺陷本体</b>：调用方老老实实按作者配的自定义键名传「同意」，这个值在
     * {@code graph.updateState} → {@code OverAllState.input()} 那一步就地被滤掉，
     * 执行器读到 null、回退默认键也没人写，于是判成<b>拒绝</b> —— 而 stream 正常结束，
     * 上层会把这次执行报成 SUCCEEDED。这就是「同意了却没干活，还一路绿灯」。
     *
     * <p>🔴 两个执行器的回退<b>救不了这一档</b>：回退的方向是「自定义键读不到 → 读默认键」，
     * 而这里默认键同样没人写。回退只兜住了下面 {@link #v23_feedbackUnderDefaultKey_approved} 那一档。
     *
     * <p>⚠️ <b>本条断的是当前运行期的缺陷行为，不是期望行为。</b>若哪天它变红，
     * 说明危害已从结构上消失（例如生成器开始注册自定义 approveVar）——那时该动的是
     * <b>校验器的 V23</b>（连同本条一起改成断 approved），<b>不是</b>把本条删掉让它闭嘴。
     */
    @Test
    @DisplayName("🔴 V23：未声明的自定义 approveVar，按它传的「同意」会被静默判成「拒绝」")
    void v23_undeclaredCustomApproveVar_consentSilentlyBecomesRejection() throws Exception {
        assertEquals("rejected", resumeAndReadVerdict(false, CUSTOM_APPROVE_VAR),
                "调用方传的是「同意」，闸口却判成拒绝 —— 这就是 V23 要在发布期拦住的那一档");
    }

    /**
     * 🔴 <b>出路①实测有效</b>：与上一条的 DSL 只差 {@code variables[]} 里那一条声明，判定从
     * rejected 翻成 approved。
     *
     * <p>少了这一条对照，V23 文案里「请补一条声明」就只是断言而没被验证过 ——
     * 而<b>给一条其实救不了的出路，比不给更坏</b>：作者照做了、问题还在、于是不再信红字。
     */
    @Test
    @DisplayName("🔴 V23 出路①：补一条 variables[] 声明，同一个 feedback 就真能被读到")
    void v23_declaredCustomApproveVar_consentSurvives() throws Exception {
        assertEquals("approved", resumeAndReadVerdict(true, CUSTOM_APPROVE_VAR),
                "声明了就注册了，注册了就滤不掉");
    }

    /**
     * 🔴 这一档解释了缺陷<b>为什么至今没在现网炸</b>：前端 {@code ExecutionHistoryDrawer.vue} 的
     * {@code resumeFeedback} 刻意把两个 HITL 默认键都写上（它自己的注释管这叫「对冲」），
     * 而默认键是无条件注册的 ⟹ 滤不掉 ⟹ 执行器的回退读得到 ⟹ approved。
     *
     * <p>⚠️ 但被盖住的只有那<b>一个</b>调用方。工作台 resume-stream 与任何外部集成都可以按自定义键传，
     * 而它们<b>没有</b>任何理由去猜平台内部的默认名 —— 作者填了什么，调用方就照什么传，这才是契约。
     * 所以「今天没炸」不构成「不用拦」的理由，V23 照样是 error 而不是 warning。
     */
    @Test
    @DisplayName("V23 对照：feedback 走默认键时，未声明的自定义 approveVar 也照样能过（前端「对冲」兜的就是这条）")
    void v23_feedbackUnderDefaultKey_approved() throws Exception {
        assertEquals("approved", resumeAndReadVerdict(false, HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR),
                "默认键已无条件注册，执行器的回退读得到它");
    }

}
