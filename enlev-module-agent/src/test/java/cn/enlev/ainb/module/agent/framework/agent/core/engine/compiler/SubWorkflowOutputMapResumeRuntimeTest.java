package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.VariableDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ParallelNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.WaitNodeExecutor;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code sub_workflow.outputMap} 写回父流程的那个键「注册过 KeyStrategy 才活得过 resume」的
 * <b>真挂起/真恢复</b>单测（台账 E3 的运行期强制点，0 模型 token）
 *
 * <h3>为什么必须有这一类，而不是只在校验器那边加个单测</h3>
 * 校验器那一侧（{@code WorkflowDslValidator#validateSubWorkflowOutputMapDeclared}）断的是「报不报」，
 * 而报的依据是「这个键会在 resume 那一刻被 {@code OverAllState.input()} 丢掉」。
 * 那个依据是 SAA 的行为，只有真跑一遍才算证到 —— {@code KeyStrategyFactoryGeneratorTest}
 * 那种纯 Map 断言证明不了（{@code KeyStrategyResumeRuntimeTest} 的类头把同一条理由写过一遍）。
 *
 * <h3>四档对照（2026-10-07 实测；探针原文归档 logs/16、可读版 logs/17）</h3>
 * 父图 {@code sw → wait → cap}，sw 配 {@code outputMap:{parentStruct:"{{$.structOut}}"}}，
 * cap 读 {@code parentStruct}；子流程一个 capture 节点写 {@code structOut}。
 * <table border="1">
 *   <caption>四档<b>只差</b>三个维度：声明了没有、下游有没有挂起点、配没配 inputMap</caption>
 *   <tr><th>档</th><th>声明 parentStruct</th><th>下游 wait</th><th>挂起点差集含它</th><th>cap 读到</th></tr>
 *   <tr><td>A</td><td>✗</td><td>✓</td><td><b>是</b></td><td><b>null</b>（缺陷本体）</td></tr>
 *   <tr><td>B</td><td>✓</td><td>✓</td><td>否</td><td>子流程结果（出路实测有效）</td></tr>
 *   <tr><td>C</td><td>✗</td><td>✗</td><td>—</td><td>子流程结果（无挂起点则无害）</td></tr>
 *   <tr><td>D</td><td>✗</td><td>✓</td><td><b>是</b>（inputMap 的 modelId 也在差集里）</td>
 *       <td><b>null</b>（两个都 null）</td></tr>
 * </table>
 *
 * <p>A 与 B 的<b>唯一</b>差别就是那一条声明 ⟹ 危害是真的、且校验器文案给的出路
 * （补一条 {@code variables[]}）是<b>实测有效</b>的 —— 后者尤其要紧，给一条救不了的出路比不给更坏。
 * C 档正是 V19 那一支<b>必须</b>挂在「下游另有挂起点」判据后面的理由：
 * 对一条不挂起的流程报 error 是把不存在的危害说成存在的。
 *
 * <h3>🔴 档 D 钉的是「刻意不报 inputMap」这个取舍的<b>事实前提</b></h3>
 * {@code inputMap} 的键运行期同样写进父流程共享 state、同样进差集、下游同样读到 {@code null}
 * —— 也就是说校验器<b>不报</b>它并不是因为「它不会丢」（那是假话），而是一次<b>知情取舍</b>：
 * 那个名字是子流程的入参词汇，要求父作者在父流程 {@code variables[]} 里手抄一遍子流程的入参名，
 * 会在每个「带 inputMap + 下游挂起点」的 sub_workflow 上稳定报错（Req6 向后兼容红线）。
 * 完整理由写在 {@code WorkflowDslValidator#validateSubWorkflowOutputMapDeclared} 的 javadoc 里。
 * 少了本档，那句「不报」就只是断言，下一个人看不出它是取舍还是漏了。
 *
 * <p>⚠️ 于是本档<b>断的是当前运行期行为</b>。若哪天它变红，说明 inputMap 的键真的活过 resume 了
 * （例如生成器开始并入子 def 的声明）—— 那时该动的是校验器那一支的取舍
 * （可以开始报，因为不再有向后兼容代价），<b>不是</b>把本条删掉让它闭嘴。
 *
 * <h3>⚠️ 本类<b>不</b>覆盖的</h3>
 * 实测差集里还有两类键：子流程自己写的 {@code structOut}、调用方喂进来的入口键
 * （{@code theId}/{@code shared}）。前者 V19 结构性拿不到（校验器刻意不查子 def），
 * 后者不是任何节点的 config 端口写的、属 B3-7「resume 差集过滤器」的域。
 * 故本类<b>不</b>等于「sub_workflow 的 resume 丢键问题已闭合」。
 *
 * @author 走进新时代
 */
@DisplayName("agent sub_workflow：outputMap 写回父流程的键必须声明才活得过 resume（E3），"
        + "而 inputMap 的键同样会丢、只是刻意不报（档 D）")
class SubWorkflowOutputMapResumeRuntimeTest {

    private static final String CHILD_CODE = "wf_child";
    private static final String PARENT_VAR = "parentStruct";
    private static final String CHILD_OUT = "structOut";
    private static final String CHILD_IN = "modelId";
    private static final String CHILD_WROTE = "WROTE_cwork";

    private DslToStateGraphCompiler compiler;
    private DslParser parser;
    private AgentWorkflowDefService defService;
    private ProbeExecutor probe;

    /** 桩：记录自己跑过的节点 id，从共享 state 读 {@code captureKeys}，再往 {@code outputVar} 写一个可辨识的值 */
    private static class ProbeExecutor implements NodeExecutor {
        final Map<String, Object> captured = new LinkedHashMap<>();
        final List<String> ran = new ArrayList<>();

        @Override
        public String getType() {
            return "capture";
        }

        @Override
        public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
            ran.add(node.getId());
            for (Object k : node.configList("captureKeys")) {
                captured.put(String.valueOf(k), ctx.get(String.valueOf(k)));
            }
            Map<String, Object> delta = new LinkedHashMap<>();
            String outputVar = node.configStr("outputVar");
            if (outputVar != null && !outputVar.isEmpty()) {
                delta.put(outputVar, "WROTE_" + node.getId());
            }
            return delta;
        }
    }

    @BeforeEach
    void setUp() {
        parser = new DslParser();
        ReflectionTestUtils.setField(parser, "objectMapper", new ObjectMapper());

        probe = new ProbeExecutor();
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        registry.setExecutors(List.of(new ParallelNodeExecutor(), new WaitNodeExecutor(), probe));

        defService = mock(AgentWorkflowDefService.class);
        compiler = new DslToStateGraphCompiler();
        ReflectionTestUtils.setField(compiler, "registry", registry);
        ReflectionTestUtils.setField(compiler, "traceRecorder", mock(NodeTraceRecorder.class));
        ReflectionTestUtils.setField(compiler, "defService", defService);
        ReflectionTestUtils.setField(compiler, "dslParser", parser);
    }

    // ==================== 夹具 ====================

    /** 子流程：读同名的 {@code shared}、把结果写进自己的 {@code structOut}（子流程侧的词汇） */
    private static final String CHILD_DSL = """
            {"id":"%s","name":"子",
             "nodes":[{"id":"cwork","type":"capture",
                       "config":{"captureKeys":["shared"],"outputVar":"%s"}}],
             "edges":[{"from":"START","to":"cwork"},{"from":"cwork","to":"END"}]}
            """.formatted(CHILD_CODE, CHILD_OUT);

    /**
     * 父流程。三个维度就是四档的全部差别，除此之外逐字相同 —— 少了这一点，
     * 「A 与 B 只差那一条声明」这句话就只是文档里的说法。
     *
     * @param declareParentVar 在 {@code variables[]} 里补一条 {@code parentStruct}
     * @param withDownstreamWait sw 下游挂不挂 {@code wait}
     * @param withInputMap     额外配 {@code inputMap:{modelId:"{{$.theId}}"}}，且让 cap 也读它
     */
    private static String parentDsl(boolean declareParentVar, boolean withDownstreamWait, boolean withInputMap) {
        String extraVar = declareParentVar
                ? ",{\"key\":\"%s\",\"type\":\"string\",\"merge\":\"replace\"}".formatted(PARENT_VAR)
                : "";
        String inputMap = withInputMap ? "\"inputMap\":{\"%s\":\"{{$.theId}}\"},".formatted(CHILD_IN) : "";
        String captureKeys = withInputMap
                ? "[\"%s\",\"%s\"]".formatted(PARENT_VAR, CHILD_IN)
                : "[\"%s\"]".formatted(PARENT_VAR);
        String sw = """
                {"id":"sw","type":"sub_workflow","config":{
                    "workflowId":"%s","version":1,
                    %s"outputMap":{"%s":"{{$.%s}}"}}}""".formatted(CHILD_CODE, inputMap, PARENT_VAR, CHILD_OUT);
        String cap = """
                {"id":"cap","type":"capture","config":{"captureKeys":%s,"outputVar":"done"}}"""
                .formatted(captureKeys);
        String nodes = withDownstreamWait
                ? sw + ",\n" + "{\"id\":\"w\",\"type\":\"wait\",\"config\":{\"waitType\":\"duration\",\"duration\":3}},\n"
                        + cap
                : sw + ",\n" + cap;
        String edges = withDownstreamWait
                ? """
                        {"from":"START","to":"sw"},{"from":"sw","to":"w"},
                        {"from":"w","to":"cap"},{"from":"cap","to":"END"}"""
                : """
                        {"from":"START","to":"sw"},{"from":"sw","to":"cap"},{"from":"cap","to":"END"}""";
        return """
                {"id":"wf_parent","name":"父",
                 "variables":[{"key":"done","type":"string","merge":"replace"}%s],
                 "nodes":[%s],
                 "edges":[%s]}
                """.formatted(extraVar, nodes, edges);
    }

    private static AgentWorkflowDefDO childDef() {
        AgentWorkflowDefDO d = new AgentWorkflowDefDO();
        d.setCode(CHILD_CODE);
        d.setVersion(1);
        d.setStatus("PUBLISHED");
        d.setDsl(CHILD_DSL);
        return d;
    }

    /** 挂起点上「会被 resume 丢掉的键」= 状态里的键 − 注册过 KeyStrategy 的键（同 {@code KeyStrategyResumeRuntimeTest}） */
    private static Set<String> droppedAtSuspend(OverAllState state) {
        Set<String> dropped = new LinkedHashSet<>(state.data().keySet());
        dropped.removeAll(state.keyStrategies().keySet());
        return dropped;
    }

    private static Set<String> declaredKeys(WorkflowDefDTO def) {
        if (def.getVariables() == null) {
            return Set.of();
        }
        return def.getVariables().stream().map(VariableDTO::getKey).collect(Collectors.toSet());
    }

    /**
     * 跑到挂起（若 {@code withDownstreamWait}）→ 恢复 → 返回终态。
     * 顺带把「挂起点那一瞬间的注册情况」也带出来，好让断言钉<b>机制</b>而不只是<b>后果</b>。
     */
    private record Run(OverAllState atSuspend, OverAllState finalState, List<String> ranBeforeResume) {
    }

    private Run run(boolean declareParentVar, boolean withDownstreamWait, boolean withInputMap) throws Exception {
        probe.captured.clear();
        probe.ran.clear();
        when(defService.getSubWorkflowDef(CHILD_CODE, 1)).thenReturn(childDef());

        WorkflowDefDTO def = parser.parse(parentDsl(declareParentVar, withDownstreamWait, withInputMap));
        CompiledGraph graph = compiler.compile(def, 1L, "t1:1",
                AgentExecutionContext.ofForTest(1L, 1L), new MemorySaver(), ObservationRegistry.NOOP);
        RunnableConfig config = RunnableConfig.builder().threadId("t1:1").build();
        Map<String, Object> input = Map.of("shared", "in", "theId", 5);

        List<NodeOutput> first = graph.stream(input, config).collectList().block();
        assertNotNull(first, "stream 应正常结束而非抛错");
        NodeOutput last = first.get(first.size() - 1);

        if (!withDownstreamWait) {
            assertFalse(last instanceof InterruptionMetadata, "没有挂起点的父图不该挂起");
            return new Run(null, last.state(), List.copyOf(probe.ran));
        }

        assertTrue(last instanceof InterruptionMetadata,
                "末元素应是 InterruptionMetadata（interruptBefore 挂起），实际：" + last.getClass());
        assertEquals("w", DslToStateGraphCompiler.resolveSuspendNodeId(graph, config), "挂起点应是那个 wait");
        OverAllState atSuspend = ((InterruptionMetadata) last).state();
        List<String> ranBeforeResume = List.copyOf(probe.ran);
        // 子流程跑完了、下游 cap 还没跑 —— 这正是「值已经写进 state，等着被 resume 处置」的那一刻
        assertEquals(List.of("cwork"), ranBeforeResume,
                "挂起点上应只有子流程那个桩跑过，实际：" + ranBeforeResume);
        assertEquals(CHILD_WROTE, atSuspend.value(PARENT_VAR).orElse(null),
                "合成的出参节点确实把子流程结果写回了父流程 state（这一步与声明无关）");

        RunnableConfig updated = graph.updateState(config, Map.of());
        List<NodeOutput> resumed = graph.stream(null, updated.withResume()).collectList().block();
        assertNotNull(resumed, "恢复流应正常结束");
        return new Run(atSuspend, resumed.get(resumed.size() - 1).state(), ranBeforeResume);
    }

    // ==================== 夹具自检 ====================

    /**
     * 🔴 自证非空洞：A/C/D 三档的 {@code variables[]} 里<b>确实没有</b> {@code parentStruct}，
     * 且配了 inputMap 的那档确实把 {@code modelId} 写进了 config。
     *
     * <p>少了这条，将来有人为了让下面几条变绿而把声明补进夹具，测试照样通过，
     * 而它证明的已经变成「声明过的键活得过 resume」（{@code KeyStrategyResumeRuntimeTest} 早证过了），
     * E3 那一支被整段删掉也不会红 —— <b>恒绿的负控比没有负控更坏</b>。
     */
    @Test
    @DisplayName("夹具自检：A/C/D 三档确实没声明 parentStruct，B 档确实声明了")
    void fixture_reallyLeavesTheOutputMapKeyUndeclared() throws Exception {
        assertFalse(declaredKeys(parser.parse(parentDsl(false, true, false))).contains(PARENT_VAR),
                "夹具被改坏了：A 档一旦声明 parentStruct，它就退化成 B 档，证不了 E3");
        assertFalse(declaredKeys(parser.parse(parentDsl(false, false, false))).contains(PARENT_VAR),
                "夹具被改坏了：C 档同上");
        assertTrue(declaredKeys(parser.parse(parentDsl(true, true, false))).contains(PARENT_VAR),
                "夹具被改坏了：B 档必须真的声明了，否则「出路有效」这句话没有对照");

        // inputMap 那一档必须真的配上了，否则档 D 只是档 A 的重复
        WorkflowDefDTO withInput = parser.parse(parentDsl(false, true, true));
        NodeDTO sw = withInput.getNodes().stream().filter(n -> "sw".equals(n.getId())).findFirst().orElseThrow();
        assertTrue(sw.configMap("inputMap").containsKey(CHILD_IN),
                "夹具被改坏了：档 D 的 inputMap 没配上，它证不了「inputMap 的键同样会丢」");
    }

    // ==================== 档 A：缺陷本体 ====================

    /**
     * 🔴 <b>缺陷本体</b>：{@code outputMap} 的键没在 {@code variables[]} 里声明，子流程结果写回了父 state、
     * 在挂起点上也<b>还在</b>，却在恢复那一刻被 {@code OverAllState.input()} 静默滤掉，下游读到 null。
     * 这就是「同一次运行内（沙箱干跑）一切正常、点了恢复之后拿着半个 state 继续跑」。
     *
     * <p>🔴 断言<b>机制</b>（挂起点的注册集里没有它）而不只断<b>后果</b>（cap 读到 null）：
     * 只断后果的话，将来别人用别的方式把后果抹平（比如给出参节点加一层回退），
     * 本类就再也说不出「危害来自注册缺失」这件事了。
     *
     * <p>⚠️ <b>本条断的是当前运行期的缺陷行为，不是期望行为。</b>若哪天它变红，
     * 说明这个键已经无条件注册上了（危害从结构上消失）—— 那时该动的是<b>校验器的那一支</b>
     * （连同本条一起改成断「读得到」），<b>不是</b>把本条删掉让它闭嘴。
     */
    @Test
    @DisplayName("🔴 E3：未声明的 outputMap 键，子流程结果在恢复那一刻被静默丢空")
    void resume_undeclaredOutputMapKey_silentlyDropped() throws Exception {
        Run run = run(false, true, false);

        assertFalse(run.atSuspend().keyStrategies().containsKey(PARENT_VAR),
                "机制那一半：没声明就没注册。挂起点注册集=" + run.atSuspend().keyStrategies().keySet());
        assertTrue(droppedAtSuspend(run.atSuspend()).contains(PARENT_VAR),
                "机制那一半：它应在「会被 input() 丢掉」的差集里。差集=" + droppedAtSuspend(run.atSuspend()));

        assertEquals(List.of("cwork", "cap"), probe.ran, "恢复后应越过 wait 跑到下游 cap");
        assertNull(probe.captured.get(PARENT_VAR),
                "后果那一半：下游读到的应是 null（这就是 E3 要在发布期拦住的那一档）。captured=" + probe.captured);
        assertFalse(run.finalState().data().containsKey(PARENT_VAR),
                "终态里这个键整个没了（不是变成 null 占位）：" + run.finalState().data().keySet());
    }

    // ==================== 档 B：出路实测有效 ====================

    /**
     * 🔴 <b>出路实测有效</b>：与档 A 的 DSL 只差 {@code variables[]} 里那一条声明，
     * 注册集从「没有它」翻成「有它」，下游就读到了。
     *
     * <p>少了这一条对照，校验器文案里「请补一条 variables[] 声明」就只是断言而没被验证过 ——
     * 而<b>给一条其实救不了的出路，比不给更坏</b>：作者照做了、问题还在、于是不再信红字。
     */
    @Test
    @DisplayName("🔴 E3 出路：补一条 variables[] 声明，同一个键就真能活过 resume")
    void resume_declaredOutputMapKey_survives() throws Exception {
        Run run = run(true, true, false);

        assertTrue(run.atSuspend().keyStrategies().containsKey(PARENT_VAR),
                "声明了就注册了。挂起点注册集=" + run.atSuspend().keyStrategies().keySet());
        assertFalse(droppedAtSuspend(run.atSuspend()).contains(PARENT_VAR),
                "注册了就滤不掉，它不该在差集里。差集=" + droppedAtSuspend(run.atSuspend()));

        assertEquals(CHILD_WROTE, probe.captured.get(PARENT_VAR),
                "下游该读到子流程写回的原值。captured=" + probe.captured);
        assertEquals(CHILD_WROTE, run.finalState().value(PARENT_VAR).orElse(null), "终态里它还在");
    }

    // ==================== 档 C：前提①（没有挂起点就没有危害） ====================

    /**
     * 🔴 前提①的运行期根据：全程不挂起 ⟹ 一次 resume 都没有 ⟹ {@code input()} 那层过滤压根不发生
     * ⟹ 未声明的键照样活着。所以校验器那一支<b>必须</b>挂在「下游另有挂起点」的判据后面
     * （{@code suspendAfterWrite}），否则每一条不挂起的 sub_workflow 都会收到一条假警报，
     * 而 error 阻断发布，代价是作者学会无视红字。
     */
    @Test
    @DisplayName("🔴 E3 前提①：下游没有挂起点时，未声明的 outputMap 键照样活得好好的")
    void resume_noDownstreamSuspend_undeclaredKeyStillSurvives() throws Exception {
        Run run = run(false, false, false);

        assertNull(run.atSuspend(), "这一档压根不该挂起");
        assertEquals(CHILD_WROTE, probe.captured.get(PARENT_VAR),
                "没有 resume 就没有过滤，下游该读到原值。captured=" + probe.captured);
        assertEquals(CHILD_WROTE, run.finalState().value(PARENT_VAR).orElse(null));
    }

    // ==================== 档 D：inputMap 同样会丢，只是刻意不报 ====================

    /**
     * 🔴 「校验器不报 inputMap 的键」这个取舍的<b>事实前提</b>：它<b>不是</b>因为 inputMap 的键不会丢。
     *
     * <p>实测：{@code modelId} 在挂起点上是 5、在差集里、恢复后下游读到 {@code null} ——
     * 与 {@code parentStruct} 一模一样的死法（SAA 原生子图共享 key 空间，
     * 合成的入参节点写的也是<b>父流程</b>的 state）。
     *
     * <p>不报它的理由是<b>向后兼容代价</b>与<b>语义归属</b>，不是「无害」：那个名字是子流程的入参词汇，
     * 要求父作者手抄一遍子流程的入参名会在每个「带 inputMap + 下游挂起点」的 sub_workflow 上稳定报错。
     * 完整取舍见 {@code WorkflowDslValidator#validateSubWorkflowOutputMapDeclared} 的 javadoc，
     * 校验器那一侧的绊线是
     * {@code WorkflowDslValidatorTest#validate_subWorkflowInputMapKey_notReportedByDesign}
     * —— 两条一起看才完整：本条钉「它真的会丢」，那条钉「我们真的选择不报」。
     */
    @Test
    @DisplayName("🔴 档 D：inputMap 的键同样在恢复那一刻被丢空（所以「不报」是取舍，不是「不会丢」）")
    void resume_inputMapKeyAlsoDropped_soNotReportingItIsATradeoff() throws Exception {
        Run run = run(false, true, true);

        assertEquals(5, run.atSuspend().value(CHILD_IN).orElse(null),
                "合成的入参节点确实把值写进了父流程 state（保原始 Integer 类型）");
        Set<String> dropped = droppedAtSuspend(run.atSuspend());
        assertTrue(dropped.contains(CHILD_IN), "inputMap 的键应在差集里。差集=" + dropped);
        assertTrue(dropped.contains(PARENT_VAR), "outputMap 的键也在（这一档两个都没声明）。差集=" + dropped);

        assertNull(probe.captured.get(CHILD_IN),
                "inputMap 的键恢复后同样读不到 —— 这就是「不报它」必须写成知情取舍的原因。captured="
                        + probe.captured);
        assertNull(probe.captured.get(PARENT_VAR), "outputMap 的键同档 A。captured=" + probe.captured);
    }
}
