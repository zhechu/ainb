package cn.enlev.ainb.module.agent.framework.agent.core.engine.io;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.LlmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ToolNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolExecutor;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 契约登记的「输出默认名」↔ 执行器「实际写进 state 的键」对齐测试
 *
 * <h3>为什么不塞进 {@link NodeIoContractTest}</h3>
 * 两者证的不是一回事。{@code NodeIoContractTest} 最多证「契约里写的 == 执行器的那个常量」，
 * 而常量本身可能压根没人用；本类<b>真跑一次执行器</b>，证「没配 {@code outputVar} 时，落进 state 的键
 * 就是契约登记给用户看的那个默认名」。前者是文本比文本，后者是文本比行为。
 *
 * <h3>🔴 这条缝已经漏过两次，两次都是「只比对文本、不比对行为」</h3>
 * <ol>
 *   <li>{@code tool}/{@code llm} 的 {@code defaultName} 曾写着 {@code null}（= 必须用户命名），
 *       而两个执行器一直在用 {@code tool_result}/{@code llm_output} 兜底。后果不是「少个提示」：
 *       {@code WorkflowDslValidator} 照契约建 producers 表，看不见这两个键，于是合法的
 *       {@code {{$.tool_result}}} 被误报成悬空引用，污点溯源同理漏报；</li>
 *   <li>{@code agent_loop} 的 {@code goal} 三个作者面都在写（契约入端口、前端 defaultConfig、
 *       给模型的 DSL 样板），运行期只读 {@code prompt} ⟹ 设计器拖出来的节点<b>一定</b>编译失败
 *       （见 {@code AgentLoopGraphBuilderTest} 第⑤节）。</li>
 * </ol>
 *
 * <h3>⚠️ 不在本类的 11 种类型（穷举，不是举例）</h3>
 * 本类只跑 {@code tool} 与 {@code llm}。剩下 11 种按「为什么跑不了/不必跑」分三组 ——
 * 🔴 这段原来是「举例式」的（只点了 {@code script}/{@code knowledge_retrieval} 两个，
 * 真实排除是 11 个），正是方案 §0.8.4.1③ 警告的形态：读者会把「举了两个」当成「只排除两个」。
 * <ol>
 *   <li><b>契约压根没有输出端口（5 种）</b>：{@code condition.rule}、{@code parallel}、{@code iteration}、
 *       {@code wait}、{@code sub_workflow} —— 没有「写进 state 的键」可断言。
 *       ⚠️ 但 {@code sub_workflow.outputMap} 与 {@code iteration.itemVar} 运行期<b>确实</b>写父 state，
 *       只是契约没登记端口（台账 #99），别把「本组无输出」读成「运行期不写」；</li>
 *   <li><b>有输出端口、但契约给的默认名是 null（2 种）</b>：{@code condition.llm}、{@code agent_loop} ——
 *       运行期也不兜底（前者仅在配了名字时才写、后者不配直接编译失败），
 *       所以「没配 outputVar 时落哪个键」这个问题对它们不存在；</li>
 *   <li><b>有默认名、但本类的形状装不下（4 种）</b>：{@code script}、{@code knowledge_retrieval}、
 *       {@code hitl.confirm}、{@code hitl.bpm_approval}。前两个的默认键名已被各自的执行器测试钉住
 *       （{@code ScriptNodeExecutorTest} 的 {@code script_result}、
 *       {@code KnowledgeRetrievalNodeExecutorTest} 的 {@code retrieved}），再抄一遍只是第二份会漂的副本；
 *       🔴 后两个不是因为「跑不了」（它们的 {@code execute()} 确实会 {@code delta.put} 那个默认键），
 *       而是因为它们<b>还写了契约刻意不登记的审计键</b>（{@code <id>_confirmed}、{@code <id>_result}、
 *       {@code <id>_status}，见 {@code NodeIoContract} 类注释「这份契约只登记给人当产出引用的键」）
 *       ⟹ 本类那句「应恰好写入一个键」的前提对它们不成立。它们的默认名由
 *       {@code HitlConfirmResumeRuntimeTest}/{@code HitlBpmApprovalResumeRuntimeTest} 在 resume 路径上钉，
 *       而「生产代码不许抄字面量」那一半由 {@code NodeIoContractTest#mainSourcesHaveNoDefaultNameLiterals}
 *       扫 src/main 全部源码守（本类在 src/test，<b>刻意</b>不在它的域内：测试的职责相反，必须钉字面量，
 *       这样改常量的<b>值</b>才会红）。</li>
 * </ol>
 *
 * @author 走进新时代
 */
@DisplayName("agent 契约输出默认名 ↔ 执行器实际写入键")
class NodeIoContractRuntimeAlignmentTest {

    // ==================== tool ====================

    @Test
    @DisplayName("tool 未配 outputVar：实际写入的键 == 契约登记的默认名")
    void tool_blankOutputVar_writesTheKeyTheContractRegisters() {
        ToolNodeExecutor executor = new ToolNodeExecutor();
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        when(toolExecutor.execute(any(), anyString(), any())).thenReturn(Map.of("rows", "两行"));
        ReflectionTestUtils.setField(executor, "toolExecutor", toolExecutor);

        Map<String, Object> config = new HashMap<>();
        config.put("toolId", "data_interface_list");
        Map<String, Object> delta = executor.execute(node(ToolNodeExecutor.TYPE, config), ctx());

        // 🔴 先肯定「确实写了一个键」，再断言是哪个键。否则「什么都没写」与「写错了键」会共用同一个绿色。
        assertEquals(1, delta.size(), "tool 应恰好写入一个键，实际：" + delta.keySet());
        String registered = defaultNameOf(ToolNodeExecutor.TYPE);
        // 🔴 钉**字面量**，不钉常量。契约登记的那个默认名就是 ToolNodeExecutor.DEFAULT_OUTPUT_VAR 本身
        //    （NodeIoContract:167 直接引的常量），拿常量去比常量是 assertEquals(X, X) 恒真 ——
        //    把常量的值从 "tool_result" 改成别的，那行照样绿，而存量 DSL 里的 {{$.tool_result}}
        //    会当场全变悬空引用（正是本类头注释记的第 1 次漏，只是方向反过来）。
        //    头注释第 3 组末尾写的就是这条纪律：src/main 引常量、**src/test 必须钉字面量**，一松一紧。
        assertEquals("tool_result", registered, "契约登记给用户看的默认产出名漂了");
        assertTrue(delta.containsKey(registered),
                "未配 outputVar 时应写进契约登记的默认名(" + registered + ")，实际写了：" + delta.keySet());
        assertEquals(Map.of("rows", "两行"), delta.get(registered));
    }

    @Test
    @DisplayName("tool 配了 outputVar：契约登记的 configKey 就是执行器读的那个键")
    void tool_customOutputVar_movesTheWriteToTheContractConfigKey() {
        ToolNodeExecutor executor = new ToolNodeExecutor();
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        when(toolExecutor.execute(any(), anyString(), any())).thenReturn("ok");
        ReflectionTestUtils.setField(executor, "toolExecutor", toolExecutor);

        String configKey = contractOf(ToolNodeExecutor.TYPE).outputs().get(0).configKey();
        Map<String, Object> config = new HashMap<>();
        config.put("toolId", "t");
        config.put(configKey, "myOut");
        Map<String, Object> delta = executor.execute(node(ToolNodeExecutor.TYPE, config), ctx());

        assertEquals(Map.of("myOut", "ok"), delta,
                "配了 " + configKey + " 就该写进它，而不是默认名");
    }

    // ==================== llm ====================

    @Test
    @DisplayName("llm 未配 outputVar：实际写入的键 == 契约登记的默认名")
    void llm_blankOutputVar_writesTheKeyTheContractRegisters() {
        LlmNodeExecutor executor = new LlmNodeExecutor();
        AiModelApi aiModelApi = mock(AiModelApi.class);
        when(aiModelApi.chat(any())).thenReturn(resp("答复正文"));
        ReflectionTestUtils.setField(executor, "aiModelApi", aiModelApi);

        Map<String, Object> config = new HashMap<>();
        config.put("prompt", "总结一下");
        Map<String, Object> delta = executor.execute(node(LlmNodeExecutor.TYPE, config), ctx());

        assertEquals(1, delta.size(), "llm 应恰好写入一个键，实际：" + delta.keySet());
        String registered = defaultNameOf(LlmNodeExecutor.TYPE);
        // 🔴 同上：钉字面量。NodeIoContract:177 引的就是 LlmNodeExecutor.DEFAULT_OUTPUT_VAR，
        //    拿常量比常量恒真。且 "llm_output" 在整个 src/test 里原先只被 UntrustedInputTracerTest
        //    **顺带**写着（那份用例的 config 显式配了 outputVar，改常量它也不红）⟹ 一个字面量钉子都没有
        assertEquals("llm_output", registered, "契约登记给用户看的默认产出名漂了");
        assertTrue(delta.containsKey(registered),
                "未配 outputVar 时应写进契约登记的默认名(" + registered + ")，实际写了：" + delta.keySet());
        assertEquals("答复正文", delta.get(registered));
    }

    @Test
    @DisplayName("llm 配了 outputVar：契约登记的 configKey 就是执行器读的那个键")
    void llm_customOutputVar_movesTheWriteToTheContractConfigKey() {
        LlmNodeExecutor executor = new LlmNodeExecutor();
        AiModelApi aiModelApi = mock(AiModelApi.class);
        when(aiModelApi.chat(any())).thenReturn(resp("答复正文"));
        ReflectionTestUtils.setField(executor, "aiModelApi", aiModelApi);

        String configKey = contractOf(LlmNodeExecutor.TYPE).outputs().get(0).configKey();
        Map<String, Object> config = new HashMap<>();
        config.put("prompt", "总结一下");
        config.put(configKey, "summary");
        Map<String, Object> delta = executor.execute(node(LlmNodeExecutor.TYPE, config), ctx());

        assertEquals(Map.of("summary", "答复正文"), delta,
                "配了 " + configKey + " 就该写进它，而不是默认名");
    }

    // ==================== 🔴 formVars 的 mode 是行为约束，不是文案 ====================

    @Test
    @DisplayName("hitl.bpm_approval.formVars 必须是 literal：标成 both 会让前端毁掉这个对象字段")
    void bpmFormVars_mustNotBeReferenceable() {
        NodeIoContract.InPort formVars = contractOf("hitl.bpm_approval").inputs().stream()
                .filter(p -> "formVars".equals(p.key())).findFirst().orElse(null);
        assertNotNull(formVars, "契约应登记 formVars 入端口");

        // 🔴 本用例断言的是**契约的声明**，不是运行期行为 —— 与下面 toolArgs 那条相反，别读混了。
        //    声明所依据的运行期事实是：AgentBpmApprovalService.resolveConfig 用 configMap("formVars")
        //    原样取、createApproval 原样 setVariables，中间没有任何 StateTemplateResolver。
        //    ⚠️ 这半条**今天没有任何测试执行它**（方案 §2-9：验不了就明说验不了，别拿一条绿测试盖过去）。
        //    补不上不是因为难写，而是因为在这两个方法里补是**假凭据**：两者都拿不到流程 state，
        //    「没把 {{$.x}} 替换掉」是签名决定的、结构上不可能失败 ⟹ 补了也是一条新的空断言。
        //    真能接上解析器的位置是 AgentWorkflowExecutionServiceImpl.runGraph 的中断收尾
        //    （HitlBpmApprovalNodeExecutor:18 记的那处调用点），要钉就得在那里钉。
        assertEquals(NodeIoContract.MODE_LITERAL, formVars.mode(),
                "formVars 的值不过模板解析，标成 both/reference 就是骗用户");
        assertTrue(formVars.hint().contains("不会被替换"),
                "hint 必须明说占位符不生效，否则用户填了 {{$.变量}} 只会拿到字面串，实际：" + formVars.hint());
    }

    @Test
    @DisplayName("对照：tool.args 确实会解析（顶层字符串值），所以它可以是 both")
    void toolArgs_isReferenceable_becauseItReallyResolves() {
        // 🔴 上一条断言的是「不许引用」，这条断言的是「同类形状的那个端口确实可以引用」。
        //    没有它，把两个端口一起降成 literal 也能让上一条变绿 —— 那就把真能力也砍了。
        NodeIoContract.InPort args = contractOf(ToolNodeExecutor.TYPE).inputs().stream()
                .filter(p -> "args".equals(p.key())).findFirst().orElse(null);
        assertNotNull(args, "契约应登记 args 入端口");
        assertEquals(NodeIoContract.MODE_BOTH, args.mode(),
                "ToolNodeExecutor 会对 args 的顶层字符串值做模板替换，故可引用");

        ToolNodeExecutor executor = new ToolNodeExecutor();
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        when(toolExecutor.execute(any(), anyString(), any())).thenReturn("ok");
        ReflectionTestUtils.setField(executor, "toolExecutor", toolExecutor);

        Map<String, Object> argsCfg = new HashMap<>();
        argsCfg.put("keyword", "{{$.q}}");
        argsCfg.put("nested", Map.of("inner", "{{$.q}}"));
        Map<String, Object> config = new HashMap<>();
        config.put("toolId", "t");
        config.put("args", argsCfg);
        executor.execute(node(ToolNodeExecutor.TYPE, config), ctx(Map.of("q", "采购")));

        @SuppressWarnings("unchecked")
        Map<String, Object> passed = capture(toolExecutor);
        assertEquals("采购", passed.get("keyword"), "顶层字符串值应被替换");
        // ⚠️ 嵌在对象里的不替换 —— hint 里那句警告钉的就是这个行为，别把它当成待修的 bug。
        //    这里刻意写成肯定式并断言到具体值：写成「assertFalse(被替换了)」的话，
        //    nested 万一整个丢了（null）也一样绿，那就等于没守。
        Object nested = passed.get("nested");
        assertTrue(nested instanceof Map<?, ?>, "嵌套对象应原样传下去，实际：" + nested);
        assertEquals("{{$.q}}", ((Map<?, ?>) nested).get("inner"),
                "嵌套值里的占位符应保持字面串（与 hint 的告警一致），实际：" + nested);
    }

    // ==================== 工具方法 ====================

    private static NodeIoContract.Contract contractOf(String type) {
        NodeIoContract.Contract c = NodeIoContract.of(type);
        assertNotNull(c, "契约缺类型: " + type);
        return c;
    }

    private static String defaultNameOf(String type) {
        return contractOf(type).outputs().get(0).defaultName();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> capture(AgentToolExecutor toolExecutor) {
        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(toolExecutor).execute(any(), anyString(), captor.capture());
        return captor.getValue();
    }

    private static AiChatRespDTO resp(String content) {
        AiChatRespDTO r = new AiChatRespDTO();
        r.setContent(content);
        r.setModelId(67L);
        r.setUsage(AiChatRespDTO.Usage.of(10, 20, 30));
        return r;
    }

    private static NodeDTO node(String type, Map<String, Object> config) {
        NodeDTO n = new NodeDTO();
        n.setId("n1");
        n.setType(type);
        n.setConfig(config);
        return n;
    }

    private static WorkflowContext ctx() {
        return ctx(Map.of());
    }

    private static WorkflowContext ctx(Map<String, Object> data) {
        OverAllState state = new OverAllState(new HashMap<>(data));
        return new WorkflowContext(state, 1L, "t1:1", AgentExecutionContext.ofForTest(1L, 1L));
    }

}
