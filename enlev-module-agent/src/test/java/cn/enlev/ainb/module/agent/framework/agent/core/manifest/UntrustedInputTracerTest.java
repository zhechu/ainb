package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.EdgeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.GraphReachability;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.TriggerDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ToolNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.io.NodeIoContract;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.UntrustedInput;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.UntrustedInputTracer.SinkKind;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.UntrustedInputTracer.SourceKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UntrustedInputTracer} 的单测
 *
 * <h3>🔴 每条「0 条流向」的断言都配了一条正向对照</h3>
 * 溯源的天然失败模式是<b>什么都不报</b>：读集算空了、播种没播上、可达性判反了，
 * 表现出来都是「测试绿着、清单上干干净净」。而「探通了没事」与「崩了」共用同一个绿色 ——
 * 这正是 {@code validateReferences → 0 warnings} 那条二义断言踩过的坑。
 * 故本类里每个否定式断言前面都先跑一遍「同样的 DSL、只差触发器类型 / 只差嵌套层级 / 只差节点顺序」
 * 的正向用例，先证明探针是通的。
 *
 * <h3>为什么有一条遍历契约的用例（{@link #everyReferenceableInPortIsTracedToAKnownSink}）</h3>
 * 它是 {@link UntrustedInputTracer} 类注释里承诺的那个强制点：契约新增一个可引用入端口而
 * {@code sinkKindOf} / {@code addUnregisteredReads} 忘了登记时，那条流向会静默消失
 * （或退化成兜底分类）。名单由 {@code NodeIoContract.all()} 现场生成，不手抄 ——
 * 抄一份就是第二份口径，新增端口时没人会想起回来补，测试会继续绿着放过缺口。
 *
 * @author 走进新时代
 */
class UntrustedInputTracerTest {

    /** 夹具的 {@code context()} 不配触发器；溯源的三个触发器源全靠它，故这里补一个 */
    private static ManifestContext withTrigger(String type, ManifestContext ctx) {
        TriggerDTO trigger = new TriggerDTO();
        trigger.setType(type);
        ctx.def().setTrigger(trigger);
        return ctx;
    }

    private static ManifestContext chatContext(NodeDTO... nodes) {
        return withTrigger("trigger.chat", ManifestTestFixtures.context(nodes));
    }

    /** 造一个通用节点。夹具只有 {@code tool} 与 {@code hitl}，溯源要覆盖 13 种类型 */
    private static NodeDTO node(String id, String type, Map<String, Object> config) {
        NodeDTO node = new NodeDTO();
        node.setId(id);
        node.setType(type);
        node.setName(id);
        node.setConfig(config);
        return node;
    }

    /** 保序的 config 构造器：{@code Map.of} 的迭代序每个 JVM 实例都不同，出流顺序会被它带乱 */
    private static Map<String, Object> config(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static NodeDTO toolNode(String id, Map<String, Object> args) {
        Map<String, Object> config = config("toolId", "some_tool");
        if (args != null) {
            config.put("args", args);
        }
        return node(id, "tool", config);
    }

    private static List<UntrustedInput> trace(ManifestContext ctx) {
        return UntrustedInputTracer.trace(ctx);
    }

    /** 把一条流向压成一行，断言失败时能直接看出差在哪 */
    private static String brief(UntrustedInput flow) {
        return flow.getSinkNodeId() + "#" + flow.getSinkArg() + "#" + flow.getSourceKind()
                + "#depth=" + flow.getDepth();
    }

    private static List<String> briefs(List<UntrustedInput> flows) {
        List<String> result = new ArrayList<>();
        for (UntrustedInput flow : flows) {
            result.add(brief(flow));
        }
        return result;
    }

    // ==================== ① 强制点：契约的每个可引用入端口都被追到 ====================

    @Test
    @DisplayName("🔴 契约里每个 mode != literal 的入端口都真产出一条流向、不落到兜底 SinkKind；每个 literal 端口都不产出")
    void everyReferenceableInPortIsTracedToAKnownSink() {
        Set<String> expected = new LinkedHashSet<>();
        Map<String, String> typeOfId = new LinkedHashMap<>();
        List<NodeDTO> nodes = new ArrayList<>();
        int index = 0;
        for (NodeIoContract.Contract contract : NodeIoContract.all()) {
            String id = "n" + index++;
            typeOfId.put(id, contract.type());
            Map<String, Object> config = new LinkedHashMap<>();
            for (NodeIoContract.InPort port : contract.inputs()) {
                // 🔴 literal 端口也一律填上带占位符的值。这是本用例的<b>反向</b>那一半：
                //    只填可引用端口的话，「literal 端口被误当成汇」这种失效就测不出来 ——
                //    而 formVars 恰好是 literal（S2-e2 把它从 both 降下来的），
                //    误当成汇就是报一条运行期压根不会发生的流向
                boolean referenceable = !NodeIoContract.MODE_LITERAL.equals(port.mode());
                if (referenceable) {
                    expected.add(contract.type() + "#" + port.key());
                }
                // REFERENCE 型端口的值本身就是变量名；其余走模板解析
                config.put(port.key(), NodeIoContract.MODE_REFERENCE.equals(port.mode())
                        ? "input" : "{{$.input}}");
            }
            nodes.add(node(id, contract.type(), config));
        }
        // 先确认遍历本身是有效的：核到 0 个端口时下面两条断言都会「假绿」。
        // 🔴 用等号而不是 >=：这条断言原本写 {@code >= 8} 而消息里写「当前应是 8 个」—— 补契约（台账 #97）
        //    新增三个可引用端口之后它涨到 11，下界照样绿，于是那句「应是 8」成了<b>没人发现的说谎</b>。
        //    改成等号后，新增可引用端口必须回来数一遍并改这个数（与 NodeIoContractTest 末尾
        //    {@code assertEquals(7, registeredDefaultNames().size())} 同一个套路：域自检用等号，逼着回来点名）。
        //    这个数不许凭记忆填，用这条命令现数：在仓库根下
        //    grep -c 数 NodeIoContract 里 mode 不是 MODE_LITERAL 的 in(...) 行。
        assertEquals(11, expected.size(),
                "从契约里核到 " + expected.size() + " 个可引用入端口，与登记的 11 个不符：" + expected
                        + "。多了 = 有人给契约加了可引用端口（那就要确认 sinkKindOf 认得它，"
                        + "否则下面那条兜底断言会红）；少了 = 遍历口径坏了");

        List<UntrustedInput> flows = trace(chatContext(nodes.toArray(new NodeDTO[0])));

        List<String> fallback = new ArrayList<>();
        for (UntrustedInput flow : flows) {
            if (SinkKind.OTHER_CONFIG.name().equals(flow.getSinkKind())) {
                fallback.add(typeOfId.get(flow.getSinkNodeId()) + "#" + flow.getSinkArg());
            }
        }
        assertTrue(fallback.isEmpty(),
                "这些入端口落到了兜底分类，说明 sinkKindOf 没登记它们，用户在清单上看不出这是哪一类汇："
                        + fallback);

        // 脚本沙箱那条读的是整个 state（sinkArg 恒为 "state"），由 scriptNodeReadsTheWholeState 专门看
        Set<String> actual = new LinkedHashSet<>();
        for (UntrustedInput flow : flows) {
            if (!SourceKind.TRIGGER_CHAT.name().equals(flow.getSourceKind())
                    || flow.getDepth() != 0
                    || SinkKind.SCRIPT_SANDBOX.name().equals(flow.getSinkKind())) {
                continue;
            }
            actual.add(typeOfId.get(flow.getSinkNodeId()) + "#" + flow.getSinkArg());
        }
        assertEquals(expected, actual,
                "溯源真读到的入端口与契约登记的可引用端口不一致。"
                        + "少了 = 那个端口上的注入不会被报（清单说安全、其实不安全）；"
                        + "多了 = 报了一条运行期不会流动的（多半是某个 literal 端口被误当成了汇）");
    }

    // ==================== ② 源：触发器 ====================

    @Test
    @DisplayName("webhook 请求体流进工具入参：sinkArg 带子键、viaNodeId 留空、summary 逐字符合留白规则")
    void webhookBodyFlowingIntoAToolArg() {
        NodeDTO save = toolNode("save", config("tableName", "{{$.input.body}}"));
        save.setName("写库");
        ManifestContext ctx = ManifestTestFixtures.context(save);
        TriggerDTO trigger = new TriggerDTO();
        trigger.setType("trigger.webhook");
        ctx.def().setTrigger(trigger);

        List<UntrustedInput> flows = trace(ctx);

        assertEquals(1, flows.size(), "应恰好一条：" + briefs(flows));
        UntrustedInput flow = flows.get(0);
        assertEquals(SourceKind.TRIGGER_WEBHOOK.name(), flow.getSourceKind());
        assertEquals("webhook 请求体", flow.getSourceLabel());
        assertEquals(SinkKind.TOOL_ARG.name(), flow.getSinkKind());
        assertEquals("工具入参", flow.getSinkLabel());
        assertEquals("save", flow.getSinkNodeId());
        assertEquals("写库", flow.getSinkNodeName());
        assertEquals("args.tableName", flow.getSinkArg());
        assertEquals("input", flow.getViaVar());
        assertNull(flow.getViaNodeId(), "input 没有任何节点产出它，不该指认一个产出者");
        assertEquals(0, flow.getDepth());
        assertEquals("webhook 请求体（input）会流进节点「写库」的工具入参 args.tableName",
                flow.getSummary());
    }

    @Test
    @DisplayName("🔴 schedule 触发不是不可信源（载荷由平台按 cron 生成）；同一份 DSL 换成 webhook 必须报出来")
    void scheduleTriggerIsNotASource() {
        NodeDTO save = toolNode("save", config("tableName", "{{$.input}}"));

        // 正向对照：先证明这个探针本身是通的，否则下面的「0 条」什么也说明不了
        List<UntrustedInput> webhook = trace(
                withTrigger("trigger.webhook", ManifestTestFixtures.context(save)));
        assertEquals(1, webhook.size(), "webhook 触发下本该报一条：" + briefs(webhook));

        List<UntrustedInput> schedule = trace(
                withTrigger("trigger.schedule", ManifestTestFixtures.context(save)));
        assertTrue(schedule.isEmpty(),
                "定时触发的载荷里没有任何人能写的东西，报出来全是假警报，而假警报的代价是真警报没人看："
                        + briefs(schedule));
    }

    @Test
    @DisplayName("form_event 报它自己的源，且认自定义 inputVar")
    void formEventUsesItsOwnSourceKind() {
        NodeDTO use = node("use", "llm", config("prompt", "{{$.payload}}"));

        ManifestContext formEvent = ManifestTestFixtures.context(use);
        TriggerDTO trigger = new TriggerDTO();
        trigger.setType("trigger.form_event");
        trigger.setConfig(config("inputVar", "payload"));
        formEvent.def().setTrigger(trigger);

        List<UntrustedInput> flows = trace(formEvent);
        assertEquals(1, flows.size(), briefs(flows).toString());
        assertEquals(SourceKind.TRIGGER_FORM_EVENT.name(), flows.get(0).getSourceKind());
        assertEquals("表单事件提交的值", flows.get(0).getSourceLabel());
        assertEquals("payload", flows.get(0).getSourceVar());
    }

    // ==================== ③ 源：工具返回值与检索片段 ====================

    @Test
    @DisplayName("工具返回值本身是新鲜源；而引用一个查无产出者的键不会凭空报出流向")
    void toolResultIsAFreshSource() {
        NodeDTO fetch = toolNode("fetch", null);
        NodeDTO use = node("use", "llm",
                config("prompt", "{{$." + ToolNodeExecutor.DEFAULT_OUTPUT_VAR + "}}"));

        List<UntrustedInput> flows = trace(ManifestTestFixtures.context(fetch, use));
        assertEquals(1, flows.size(), "无触发器、只有一个工具源，该报一条：" + briefs(flows));
        assertEquals(SourceKind.TOOL_RESULT.name(), flows.get(0).getSourceKind());
        assertEquals("fetch", flows.get(0).getViaNodeId(), "tool_result 只有 fetch 一个产出者，应指认它");
        assertEquals(0, flows.get(0).getDepth());

        // 反向对照：换成一个从没被写过的键，必须 0 条
        NodeDTO dangling = node("use", "llm", config("prompt", "{{$.never_written}}"));
        assertTrue(trace(ManifestTestFixtures.context(fetch, dangling)).isEmpty(),
                "没有任何源流到 never_written 上，不该报流向");
    }

    @Test
    @DisplayName("两个工具都写 tool_result 时只报一条、且 viaNodeId 留空：指认其中一个等于指认错人")
    void ambiguousProducerIsNotNamed() {
        NodeDTO a = toolNode("a", null);
        NodeDTO b = toolNode("b", null);
        NodeDTO use = node("use", "llm",
                config("prompt", "{{$." + ToolNodeExecutor.DEFAULT_OUTPUT_VAR + "}}"));

        List<UntrustedInput> flows = trace(ManifestTestFixtures.context(a, b, use));

        assertEquals(1, flows.size(),
                "两条流的 summary 会逐字相同，报两遍只是噪音（这正是 Taint 不带源节点 id 的原因）："
                        + briefs(flows));
        assertEquals(SourceKind.TOOL_RESULT.name(), flows.get(0).getSourceKind());
        assertNull(flows.get(0).getViaNodeId(), "tool_result 有两个可达产出者，不该随便挑一个");
    }

    @Test
    @DisplayName("🔴 C3：outputVar 填纯空白时登记的是那个空白键、不是缺省名 —— 少报与多报两头一起钉")
    void blankOutputVarWritesTheBlankKeyNotTheDefault() {
        // 运行期事实：ToolNodeExecutor 用 configStr("outputVar", DEFAULT_OUTPUT_VAR) 取键（只在 null / 空串回落），
        // 随后无条件 delta.put ⟹ 这一格填两个空格时，真被写进 state 的键名就是那两个空格。
        // 改造前溯源侧走 blankToDefault，把它算成 tool_result：真被写脏的键没人播种（少报 = 污点整条断流），
        // 而压根没人写的 tool_result 反倒被播种（多报）。两头都是错的，故两头都断言。
        NodeDTO producer = node("producer", "tool", config("toolId", "some_tool", "outputVar", "  "));
        // 🔴 读取侧不能直接写「$.  」这种占位符：PLACEHOLDER 正则（\{\{\s*([^}]+?)\s*}}）两侧的 \s*
        //    会把首尾空白吃掉，捕获组只剩「$.」，rootKeyOf 返回 null ⟹ 那个键在读侧压根不可表达
        //    （addUnregisteredWrites 的 javadoc 里那条「假理由」讲的就是这个）。
        //    挂一段路径即可绕开：空白成了表达式<b>内部</b>的字符，正则碰不到，rootKeyOf 截到 "  " 为止。
        NodeDTO readsBlank = node("reads_blank", "llm", config("prompt", "{{$.  .probe}}"));
        NodeDTO readsDefault = node("reads_default", "llm",
                config("prompt", "{{$." + ToolNodeExecutor.DEFAULT_OUTPUT_VAR + "}}"));

        List<UntrustedInput> flows = trace(ManifestTestFixtures.context(producer, readsBlank, readsDefault));

        assertEquals(1, flows.size(), "只该有「读空白键」这一条流向：" + briefs(flows));
        UntrustedInput intoBlank = flows.get(0);
        assertEquals("reads_blank", intoBlank.getSinkNodeId(), briefs(flows).toString());
        assertEquals(SourceKind.TOOL_RESULT.name(), intoBlank.getSourceKind());
        assertEquals("  ", intoBlank.getViaVar(),
                "登记的键名必须与执行器真写的那个逐字相同（这里是两个空格）—— 改前它是 tool_result，"
                        + "于是从空白键流向下游的污点整条断掉，清单会说「没有不可信数据流进下游」");
        assertEquals("producer", intoBlank.getViaNodeId(), "那个空白键只有 producer 一个产出者");
        assertEquals(0, intoBlank.getDepth(), "直接读产出者写下的键，depth 该是 0");
    }

    // ==================== ④ 传播 ====================

    @Test
    @DisplayName("🔴 跨两跳后 depth=1、viaNodeId 指认中间节点；把读取节点排到产出者之前时必须不报")
    void propagationRespectsReachability() {
        NodeDTO first = node("first", "llm", config("prompt", "{{$.input}}"));
        NodeDTO second = node("second", "llm", config("prompt", "{{$.llm_output}}"));

        List<UntrustedInput> forward = trace(chatContext(first, second));
        UntrustedInput propagated = forward.stream()
                .filter(f -> "second".equals(f.getSinkNodeId())).findFirst().orElse(null);
        assertNotNull(propagated, "下游节点本该读到传播来的污点：" + briefs(forward));
        assertEquals(SourceKind.TRIGGER_CHAT.name(), propagated.getSourceKind());
        assertEquals(1, propagated.getDepth());
        assertEquals("llm_output", propagated.getViaVar());
        assertEquals("first", propagated.getViaNodeId());
        assertEquals("聊天用户输入（input）经 1 跳传播后会流进节点「second」的提示词 prompt",
                propagated.getSummary());

        // 反向对照：同一份 DSL 把两个节点换个顺序，second 就跑在 first 前面了。
        // 🔴 这一半是本用例存在的理由 —— 少了它，「forward 报出来了」也可能只是因为
        //    可达性检查根本没在跑（那样两个方向都会报，测试照样绿）
        List<UntrustedInput> backward = trace(chatContext(second, first));
        assertTrue(backward.stream().noneMatch(f -> "second".equals(f.getSinkNodeId())),
                "产出者在下游时，运行到 second 那一刻 llm_output 还没有值，那条流向不会发生："
                        + briefs(backward));
        assertFalse(backward.isEmpty(), "first 读的是触发变量，那一条该照报（否则上面一条是假绿）");
    }

    @Test
    @DisplayName("🔴 节点不读自己即将写出的键：ancestorsOf 刻意含自身，不排除就会凭空多报一条")
    void nodeDoesNotReadItsOwnOutput() {
        // 自引用：llm 的 prompt 引用了它自己的 outputVar
        NodeDTO selfRef = node("self_ref", "llm",
                config("prompt", "{{$.llm_output}}", "outputVar", "llm_output"));
        assertTrue(trace(chatContext(selfRef)).isEmpty(),
                "自己产出、自己引用不构成一条数据流（运行期解析 prompt 时那个键还没被本节点写过）");

        // 正向对照：同一段引用放到下游节点上就必须报出来，否则上面那个「0 条」说明不了任何事
        NodeDTO producer = node("producer", "llm", config("prompt", "{{$.input}}"));
        NodeDTO consumer = node("consumer", "llm", config("prompt", "{{$.llm_output}}"));
        List<UntrustedInput> downstream = trace(chatContext(producer, consumer));
        UntrustedInput intoConsumer = downstream.stream()
                .filter(f -> "consumer".equals(f.getSinkNodeId())).findFirst().orElse(null);
        assertNotNull(intoConsumer, "下游读同一个键本该报出来：" + briefs(downstream));
        assertEquals("llm_output", intoConsumer.getViaVar());
        // 🔴 producer 与 consumer 都用缺省的 llm_output，故这个键有<b>两个</b>产出者。
        //    仍应指认 producer，因为 consumer 自己那个不算（它读的是写入之前的值）
        assertEquals("producer", intoConsumer.getViaNodeId(),
                "llm_output 有两个产出者，但 consumer 自己那个不算，故仍应指认 producer");
    }

    @Test
    @DisplayName("🔴 节点读自己即将写出的那个键也不算可达：运行期它拿到的是写入之前的旧值，不是自己的产出")
    void nodeDoesNotConsumeItsOwnFreshOutput() {
        // tool 的 args 引用了它自己的 outputVar。ToolNodeExecutor 先解析 args、后把结果作为 delta 写回，
        // 故「工具吃到自己的返回值」这条流向运行期不会发生（首次执行时那个键压根还没值）
        NodeDTO selfFeed = node("self_feed", "tool", config("toolId", "some_tool",
                "args", config("x", "{{$.tool_result}}")));
        assertTrue(trace(ManifestTestFixtures.context(selfFeed)).isEmpty(),
                "工具不会吃到自己的返回值：" + briefs(trace(ManifestTestFixtures.context(selfFeed))));

        // 正向对照：同一段引用放到下游节点上必须报出来，否则上面那个「0 条」什么也说明不了
        NodeDTO upstream = toolNode("upstream", null);
        NodeDTO downstream = toolNode("downstream", config("x", "{{$.tool_result}}"));
        List<UntrustedInput> flows = trace(ManifestTestFixtures.context(upstream, downstream));
        assertTrue(flows.stream().anyMatch(f -> "downstream".equals(f.getSinkNodeId())),
                "下游读同一个键本该报出来：" + briefs(flows));
    }

    @Test
    @DisplayName("🔴 环上收敛：depth 只减不增故不动点会停，且报出的是最小跳数")
    void cyclesConvergeToTheMinimalDepth() {
        NodeDTO x = node("x", "llm", config("prompt", "{{$.input}}", "systemPrompt", "{{$.y_out}}",
                "outputVar", "x_out"));
        NodeDTO y = node("y", "llm", config("prompt", "{{$.x_out}}", "outputVar", "y_out"));

        // 手工连成 START → x → y → x 的环（夹具只会串成一条链）
        WorkflowDefDTO def = new WorkflowDefDTO();
        def.setDslVersion("1.0");
        def.setId("wf_cycle");
        def.setName("环");
        def.setNodes(List.of(x, y));
        def.setEdges(List.of(edge("START", "x"), edge("x", "y"), edge("y", "x"), edge("y", "END")));
        TriggerDTO trigger = new TriggerDTO();
        trigger.setType("trigger.chat");
        def.setTrigger(trigger);
        Map<String, NodeDTO> nodeById = new HashMap<>();
        nodeById.put("x", x);
        nodeById.put("y", y);
        ManifestContext ctx = new ManifestContext(def, GraphReachability.of(def), Map.copyOf(nodeById),
                new ManifestLabels(null));

        // 若合并规则里含 depth，这里会因为一轮一轮推出 depth+1、+2…… 而永不返回
        List<UntrustedInput> flows = trace(ctx);

        assertEquals(List.of("x#prompt#0", "x#systemPrompt#2", "y#prompt#1"),
                flows.stream().map(f -> f.getSinkNodeId() + "#" + f.getSinkArg() + "#" + f.getDepth()).toList(),
                "环上的 depth 应是各条路径里的最小值，且顺序按 DSL 节点序、同节点内按契约端口声明序");
    }

    @Test
    @DisplayName("iteration 的 itemVar 虽未在契约登记为输出，也必须当成写入：否则污点在循环体那一跳凭空断流")
    void iterationItemVarCarriesTaint() {
        NodeDTO each = node("each", "iteration", config("itemsVar", "input", "itemVar", "row"));
        NodeDTO use = toolNode("use", config("v", "{{$.row}}"));

        List<UntrustedInput> flows = trace(chatContext(each, use));

        // 正向对照：itemsVar 那条必须先成立，它证明 each 确实读到了 input，否则 depth=1 无从谈起
        assertTrue(flows.stream().anyMatch(f -> SinkKind.LOOP_ITEMS.name().equals(f.getSinkKind())),
                "itemsVar 该报一条 LOOP_ITEMS：" + briefs(flows));

        UntrustedInput intoTool = flows.stream()
                .filter(f -> SinkKind.TOOL_ARG.name().equals(f.getSinkKind())).findFirst().orElse(null);
        assertNotNull(intoTool, "循环体里的工具入参本该被追到：" + briefs(flows));
        assertEquals(1, intoTool.getDepth());
        assertEquals("row", intoTool.getViaVar());
        assertEquals("each", intoTool.getViaNodeId());
    }

    @Test
    @DisplayName("🔴 iteration.itemVar 带首尾空格：分析侧仍多报一条边（E5 已让这份 DSL 建不起图，故 trim 保留为绊线）")
    void itemVarWithSurroundingSpacesOverReportsByDesign() {
        NodeDTO each = node("each", "iteration", config("itemsVar", "input", "itemVar", " row "));
        NodeDTO use = toolNode("use", config("v", "{{$.row}}"));

        List<UntrustedInput> flows = trace(chatContext(each, use));

        // 实测（2026-10-03）：addIfPresent 保留 trim ⟹ 2 条；把 trim 去掉 ⟹ 只剩 itemsVar 那 1 条。
        // 🔴 这条断言就是那个 trim 的<b>唯一</b>强制点：改 trim 的那次实测里，当时已有的 20 条测试全绿。
        // 🟢 E5（台账 #103）之后本条的<b>理由变了、数字没变</b>：IterationGraphBuilder 现在按
        //    DslToStateGraphCompiler.VAR_NAME 拒绝 " row " ⟹ 这份 DSL 根本建不起图 ⟹ 报出的那条边
        //    指向一个不存在的运行期。仍是多报（trace() 是静态分析，不建图，故拦不住），与本类取舍一致。
        assertEquals(2, flows.size(), """
                itemVar 填了 " row "（首尾带空格）时应报 2 条。
                少了 = addIfPresent 的 trim 被去掉：那样 writes 里是 " row "，而读取侧的 {{$.row}}
                经 StateTemplateResolver.PLACEHOLDER 解析出的根键是 row，两者对不上 ⟹ 循环体那一跳整条消失。
                ⚠️ 这条边在 E5 之后已经是<b>不可能发生</b>的了：IterationGraphBuilder 会先按 VAR_NAME
                拒绝 " row "，图压根建不起来（见 IterationGraphBuilderTest#iteration_itemVarNotLegalVarName_rejected）。
                所以「报 2 条」是<b>多报</b>，而多报正是本类的既定取舍（宁可传播更宽，不留缺口）。
                🔴 它同时是<b>绊线</b>：本条要一直红着才对的前提是「校验在编译期、分析在建图之前」。
                哪天有人把校验挪进分析路径、或把 trim 去掉，这条会先红 —— 那时要重新判断，不要直接改数字。
                """ + briefs(flows));

        UntrustedInput intoTool = flows.stream()
                .filter(f -> SinkKind.TOOL_ARG.name().equals(f.getSinkKind())).findFirst().orElse(null);
        assertNotNull(intoTool, "循环体里那条本该在（现状 = 多报，理由见上一条消息）：" + briefs(flows));
        assertEquals("row", intoTool.getViaVar(),
                "viaVar 应是 trim 之后的 row。E5 之前这是「指错键名的传播边」（运行期真写的键是 \" row \"）；"
                        + "E5 之后运行期压根不会有这个图，故它只是多报的那条边的键名");
        assertEquals(1, intoTool.getDepth(), "污点经 each 的 itemsVar 传进来，应是 1 跳：" + briefs(flows));
    }

    @Test
    @DisplayName("🔴 agent_loop 的 trajectoryVar 带污点：专门分支已删、改由契约的 outputs 驱动，这一条就是它的强制点")
    void agentLoopTrajectoryVarCarriesTaint() {
        // 台账 #97 把 trajectoryVar 登记进契约的 outputs() 之后，addUnregisteredWrites 里那一支专门分支被删了。
        // 删之前它<b>没有</b>测试；删之后若契约那一行被误删（或 configKey 写错一个字），
        // 污点就在「循环产出 → 下游引用」这一跳凭空断流，而清单会说「没有不可信数据流进下游」。
        NodeDTO loop = node("loop", "agent_loop", config(
                "goal", "{{$.input}}", "outputVar", "answer", "trajectoryVar", "traj"));
        NodeDTO use = toolNode("use", config("v", "{{$.traj}}"));

        List<UntrustedInput> flows = trace(chatContext(loop, use));

        // 正向对照：goal 那条先成立，它证明 loop 确实读到了 input，否则下面 depth=1 无从谈起
        assertTrue(flows.stream().anyMatch(f -> SinkKind.LLM_PROMPT.name().equals(f.getSinkKind())
                        && "goal".equals(f.getSinkArg())),
                "goal 该报一条 LLM_PROMPT：" + briefs(flows));

        UntrustedInput intoTool = flows.stream()
                .filter(f -> SinkKind.TOOL_ARG.name().equals(f.getSinkKind())).findFirst().orElse(null);
        assertNotNull(intoTool, "trajectoryVar 本该把污点带到下游工具入参（断流了）：" + briefs(flows));
        assertEquals(1, intoTool.getDepth(), "隔一个节点，depth 应是 1 而不是 0");
        assertEquals("traj", intoTool.getViaVar(), "指认的中间变量应是用户填的那个名");
        assertEquals("loop", intoTool.getViaNodeId());
    }

    // ==================== ⑤ 汇：哪些算、哪些刻意不算 ====================

    @Test
    @DisplayName("脚本节点整取 state 进沙箱：报一条 SCRIPT_SANDBOX，sinkArg 用 state 而不是 *")
    void scriptNodeReadsTheWholeState() {
        NodeDTO fetch = toolNode("fetch", null);
        NodeDTO clean = node("clean", "script", config("code", "return state;"));

        List<UntrustedInput> flows = trace(ManifestTestFixtures.context(fetch, clean));

        // 🔴 逐条比对而不只是数个数：脚本沙箱那条的形状最特殊（sinkArg / viaVar 都是 "state"、
        //    viaNodeId 恒空、label 里带全角括号），只数个数的话这些全都可以悄悄漂掉。
        //    ⚠️ 它<b>不</b>能证明「节点不读自己的产出」：clean 的 script_result 也被污染了，
        //    但那份污点与 tool_result 同源且 depth 更大，被 dedupe 的「同源留最小」吃掉了 ——
        //    那条排除由 nodeDoesNotConsumeItsOwnFreshOutput 钉住
        assertEquals(List.of("clean#state#TOOL_RESULT#depth=0"), briefs(flows),
                "沙箱拿到的是写入之前的 state（ScriptNodeExecutor 先 eval 再把结果作为 delta 返回）");

        UntrustedInput flow = flows.get(0);
        assertEquals(SinkKind.SCRIPT_SANDBOX.name(), flow.getSinkKind());
        assertEquals("脚本沙箱（整个 state）", flow.getSinkLabel());
        assertEquals("state", flow.getSinkArg());
        assertEquals("state", flow.getViaVar());
        assertNull(flow.getViaNodeId(), "整取 state 时指认单个产出者没有意义");
        assertEquals("工具返回值（tool_result）会流进节点「clean」的脚本沙箱（整个 state）",
                flow.getSummary());
    }

    @Test
    @DisplayName("🔴 hitl.bpm_approval 的 formVars 不是汇：它压根不过模板解析，报出来就是报一条不会发生的流向")
    void bpmApprovalFormVarsIsNotASink() {
        NodeDTO ask = node("ask", "llm", config("prompt", "{{$.input}}"));
        NodeDTO approve = node("approve", "hitl.bpm_approval", config(
                "bpmProcessDefKey", "oa_leave",
                "formVars", config("reason", "{{$.input}}")));

        List<UntrustedInput> flows = trace(chatContext(ask, approve));

        // 正向对照在同一份 DSL 里：ask 必须报出来，否则「approve 没报」也可能只是溯源整体没跑
        assertEquals(1, flows.size(), "只该有 ask 那一条：" + briefs(flows));
        assertEquals("ask", flows.get(0).getSinkNodeId());
    }

    @Test
    @DisplayName("🔴 tool.args 只看顶层字符串值：嵌在对象里的占位符运行期不替换，故不报")
    void nestedToolArgsAreNotSinks() {
        NodeDTO nested = toolNode("nested", config("filter", config("name", "{{$.input}}")));
        assertTrue(trace(chatContext(nested)).isEmpty(),
                "ToolNodeExecutor 只替换 args 的顶层 String 值，嵌套的占位符原样传给工具，不该报流向");

        // 正向对照：同一个占位符放到顶层必须报出来
        NodeDTO flat = toolNode("flat", config("name", "{{$.input}}"));
        assertEquals(1, trace(chatContext(flat)).size(),
                "顶层的同一个占位符必须报出来；报不出来说明上面那条 0 是假绿");
    }

    @Test
    @DisplayName("子流程入参是汇（污点跨过了流程边界），而 outputMap 只为传播而读、不作为汇报出")
    void subWorkflowInputMapIsASinkButOutputMapIsNot() {
        NodeDTO sub = node("sub", "sub_workflow", config(
                "workflowId", "wf_child",
                "inputMap", config("payload", "{{$.input}}"),
                "outputMap", config("result", "{{$.input}}")));

        List<UntrustedInput> flows = trace(chatContext(sub));

        assertEquals(1, flows.size(), """
                应恰好一条（inputMap 那一条）。多出来时，看下面两条是否<b>一模一样</b>就能分开两种病因：
                  ① 两条不同 ⟹ outputMap 被当成了汇报出的汇。它写回的是父流程自己的 state，
                     没跨任何信任边界，报出来只是噪音；
                  ② 两条一模一样 ⟹ inputMap 那条被读了两次：addUnregisteredReads 里又多了一支，
                     而契约的通用循环已经 reproduce 过它（台账 #97 删掉的就是这一支）。
                🔴 本条断言是那支「已删的 inputMap 补充支」的<b>唯一</b>失效探针。
                遍历断言 everyReferenceableInPortIsTracedToAKnownSink 抓不到重复：它的 actual 是 LinkedHashSet，
                且它给 inputMap 填的 fixture 值是字符串、而 addMappingReads 只认 Map 值 —— 两点都已实测。
                """ + briefs(flows));
        assertEquals(SinkKind.SUB_WORKFLOW_INPUT.name(), flows.get(0).getSinkKind());
        assertEquals("子流程入参", flows.get(0).getSinkLabel());
        assertEquals("inputMap.payload", flows.get(0).getSinkArg());
    }

    @Test
    @DisplayName("agent_loop 的 inputVar 是汇：goal 为空时它被原样塞进对话历史的第一条 user 消息")
    void agentLoopInputVarIsASink() {
        NodeDTO loop = node("loop", "agent_loop", config("inputVar", "input"));

        List<UntrustedInput> flows = trace(chatContext(loop));

        assertEquals(1, flows.size(), briefs(flows).toString());
        assertEquals(SinkKind.LLM_PROMPT.name(), flows.get(0).getSinkKind());
        assertEquals("inputVar", flows.get(0).getSinkArg());
        assertEquals("循环目标", flows.get(0).getSinkLabel());
    }

    @Test
    @DisplayName("🔴 工具的 outputVar 撞了触发器入口名时两份污点并存：抹掉触发器那份就是少报一条真会发生的流向")
    void freshSourceDoesNotWipeTheTriggerTaint() {
        NodeDTO overwrite = node("overwrite", "tool",
                config("toolId", "some_tool", "outputVar", "input"));
        NodeDTO before = node("before", "llm", config("prompt", "{{$.input}}"));
        NodeDTO after = node("after", "llm", config("prompt", "{{$.input}}"));

        // before 排在 overwrite 前面：它读到的是触发器写进去的原值；after 读到的说不准是哪一个
        List<UntrustedInput> flows = trace(chatContext(before, overwrite, after));

        Set<String> beforeKinds = kindsAt(flows, "before");
        assertEquals(Set.of(SourceKind.TRIGGER_CHAT.name()), beforeKinds,
                "触发变量被某个节点的 outputVar 撞名之后就凭空有了产出者，"
                        + "若照产出者可达性一刀切，排在它前面的读取会被误判成不可达 —— 那是少报");

        assertEquals(Set.of(SourceKind.TRIGGER_CHAT.name(), SourceKind.TOOL_RESULT.name()),
                kindsAt(flows, "after"),
                "运行期谁也覆盖不了谁说不准（取决于分支），两份都该报");
    }

    private static Set<String> kindsAt(List<UntrustedInput> flows, String sinkNodeId) {
        Set<String> kinds = new LinkedHashSet<>();
        for (UntrustedInput flow : flows) {
            if (sinkNodeId.equals(flow.getSinkNodeId())) {
                kinds.add(flow.getSourceKind());
            }
        }
        return kinds;
    }

    // ==================== ⑥ 输出契约 ====================

    @Test
    @DisplayName("同一份 DSL 每次溯源逐字节相同（清单要进审批记录，不能每刷一次换个说法）")
    void outputIsDeterministic() {
        NodeDTO fetch = toolNode("fetch", config("url", "{{$.input}}"));
        NodeDTO use = node("use", "llm", config("prompt", "{{$.tool_result}}"));
        ManifestContext ctx = chatContext(fetch, use);

        List<String> first = trace(ctx).stream().map(UntrustedInput::getSummary).toList();
        List<String> second = trace(ctx).stream().map(UntrustedInput::getSummary).toList();

        assertFalse(first.isEmpty(), "这份 DSL 本该报出流向，否则下面的相等毫无意义");
        assertEquals(first, second);
    }

    @Test
    @DisplayName("溯源不抛：null 上下文、无 nodes、无 config、重复 id 都安全退化")
    void neverThrowsOnBrokenDsl() {
        assertEquals(List.of(), trace(null), "ctx 为 null 时该回空列表");
        // ⚠️ 那个显式转型不是装饰：C9 之后有两个重载，裸写 trace(null) 是编译期二义
        assertEquals(List.of(), UntrustedInputTracer.trace((WorkflowDefDTO) null),
                "不建清单的那个入口同样要对 null 退化");
        assertEquals(List.of(), trace(ManifestTestFixtures.context()), "一个节点都没有");

        NodeDTO noConfig = node("bare", "llm", null);
        assertTrue(trace(chatContext(noConfig)).isEmpty(), "没有 config 就没有读集，该回空");

        // 重复 id 是校验器的 error；溯源按「先到先得」跳过，否则同一条流向会被报两遍
        NodeDTO first = node("dup", "llm", config("prompt", "{{$.input}}"));
        NodeDTO second = node("dup", "llm", config("systemPrompt", "{{$.input}}"));
        List<UntrustedInput> flows = trace(chatContext(first, second));
        assertEquals(1, flows.size(), "重复 id 只算第一个：" + briefs(flows));
        assertEquals("prompt", flows.get(0).getSinkArg());
    }

    // ==================== ⑦ 两个入口一份实现（台账 C9） ====================

    /**
     * 把一条流向的<b>全部</b>字段压成一行。
     *
     * <p>🔴 刻意不用 {@link #brief}：那个只带四个字段，而 C9 要钉的是「两个入口的产物<b>逐字段</b>相同」。
     * 只比 summary 的话，{@code viaNodeId} 或 {@code depth} 在某个入口上算歪了照样绿 ——
     * 而 {@code depth} 恰恰是最容易分岔的那个（它由可达性图决定，两个入口的图来源不同：
     * 一个是 builder 建的、一个是本类现算的）。
     */
    private static String full(UntrustedInput flow) {
        return String.join("|",
                String.valueOf(flow.getSourceKind()), String.valueOf(flow.getSourceLabel()),
                String.valueOf(flow.getSourceVar()), String.valueOf(flow.getSinkKind()),
                String.valueOf(flow.getSinkLabel()), String.valueOf(flow.getSinkNodeId()),
                String.valueOf(flow.getSinkNodeName()), String.valueOf(flow.getSinkArg()),
                String.valueOf(flow.getViaVar()), String.valueOf(flow.getViaNodeId()),
                String.valueOf(flow.getDepth()), String.valueOf(flow.getSummary()));
    }

    private static List<String> fulls(List<UntrustedInput> flows) {
        return flows.stream().map(UntrustedInputTracerTest::full).toList();
    }

    /**
     * 🔴 C9 的强制点：{@code trace(WorkflowDefDTO)} 与 {@code trace(ManifestContext)} 的产物<b>逐字段相同</b>
     *
     * <p>为什么这条是 C9 的真判据、而不是「有个新重载能用就行」：新入口存在的<b>全部</b>意义是
     * 「没有清单的地方也能说同一句话」。若两个入口对同一份 DSL 给出不同产物，
     * 那就是 D7 那个病换了个位置复发 —— AI 自修正回路上模型看到的警告，
     * 与发布闸门上审批人看到的清单<b>不是同一份结论</b>，而两边都写着「webhook 请求体」，
     * 没人会怀疑它们其实算的不是一回事。
     *
     * <p>结构上它们不可能分岔（两个公开入口都只负责备好图，此后汇进同一个 private 实现），
     * 但「结构上不会」正是本项目最不信的那种保证：builder 建图用的是 {@code GraphReachability.of(def)}，
     * 新入口用的也是它 —— 这句「也是它」是<b>两处代码</b>，本条测试就是它的强制点。
     */
    @Test
    @DisplayName("🔴 C9：不建清单的入口 trace(def) 与清单入口 trace(ctx) 的产物逐字段相同")
    void bothEntriesProduceByteIdenticalFlows() {
        // 一份刻意「富」的 DSL：三种汇（工具入参 / 提示词 / 脚本沙箱）、两个源（webhook 请求体 + 工具返回值）、
        // 两档 depth（0 与 1）。比「逐字段相同」必须在这种形状上比 —— 单条 depth=0 的流向太容易碰巧相同。
        // ⚠️ 别指望在这里造出 depth=2：整取 state 的那个汇走 dedupe，同 (源, 源变量) 只留 depth 最小的那条，
        //    于是 sandbox 上所有传播来的 depth≥1 都会被 depth=0 那份盖掉（实测就是 5 条、depth 只有 0 与 1）。
        //    要更高档得靠 LLM_PROMPT 那种「单键读」的汇，本夹具的 think#prompt#depth=1 已经是传播那一半的证据。
        // ⚠️ toolNode 的第二参就是 args 本身（它会替你补 toolId），别再把 args/outputVar 包一层进去：
        //    包了之后 fetch 的读集是空的（tool.args 只看顶层字符串值），下面三条域自检会当场红 ——
        //    2026-10-07 第一版正是这么写错的，红出来的产物只剩两条 sandbox#state
        NodeDTO fetch = toolNode("fetch", config("tableName", "{{$.input.name}}"));
        NodeDTO think = node("think", "llm", config("prompt", "请总结 {{$.tool_result}}"));
        NodeDTO sandbox = node("sandbox", "script", config("code", "print(state)"));
        ManifestContext ctx = withTrigger("trigger.webhook",
                ManifestTestFixtures.context(fetch, think, sandbox));

        List<UntrustedInput> viaCtx = trace(ctx);
        List<UntrustedInput> viaDef = UntrustedInputTracer.trace(ctx.def());

        // 夹具自检：两个空列表也「逐字段相同」，那种绿比不测更坏。
        // 🔴 用相等而不是 >=：域自检要逼着改夹具的人回来数一遍（与本类 ① 那条 assertEquals(11, …) 同一套路）
        assertEquals(Set.of("TOOL_ARG", "LLM_PROMPT", "SCRIPT_SANDBOX"), sinkKindsOf(viaCtx),
                "夹具本该覆盖三种汇，实际：" + briefs(viaCtx));
        assertEquals(Set.of(0, 1), depthsOf(viaCtx),
                "夹具本该同时产出 depth=0 与 depth=1，否则相等断言测不出传播那一半：" + briefs(viaCtx));
        assertEquals(Set.of(SourceKind.TRIGGER_WEBHOOK.name(), SourceKind.TOOL_RESULT.name()),
                sourceKindsOf(viaCtx), "夹具本该同时追到两个源，实际：" + briefs(viaCtx));

        assertEquals(fulls(viaCtx), fulls(viaDef),
                "两个入口对同一份 DSL 必须逐字段相同；不同 = 图或播种在某一侧算歪了");
    }

    private static Set<String> sinkKindsOf(List<UntrustedInput> flows) {
        Set<String> kinds = new LinkedHashSet<>();
        for (UntrustedInput flow : flows) {
            kinds.add(flow.getSinkKind());
        }
        return kinds;
    }

    private static Set<String> sourceKindsOf(List<UntrustedInput> flows) {
        Set<String> kinds = new LinkedHashSet<>();
        for (UntrustedInput flow : flows) {
            kinds.add(flow.getSourceKind());
        }
        return kinds;
    }

    private static Set<Integer> depthsOf(List<UntrustedInput> flows) {
        Set<Integer> depths = new LinkedHashSet<>();
        for (UntrustedInput flow : flows) {
            depths.add(flow.getDepth());
        }
        return depths;
    }

    /**
     * 新入口<b>不需要</b>清单构建器那三样贵的东西（{@code ManifestLabels} 要 api、{@code nodeById} 要遍历），
     * 而产物照样完整 —— 这条钉的是「轻量」不是「缩水」。
     *
     * <p>⚠️ 具体的强制点是 {@code sinkLabel}：它是印在确认卡上给人看的中文名，
     * 取自<b>契约</b>的 {@code InPort#label()} 而不是 {@code ManifestLabels}。
     * 若哪天有人把汇名改成查 {@code ManifestLabels}，新入口就会在没有 api 的地方印出 null，
     * 而这条断言会先红。
     */
    @Test
    @DisplayName("🔴 C9：不建清单的入口照样填得出汇的中文名（sinkLabel 来自契约、不来自 ManifestLabels）")
    void manifestFreeEntryStillFillsChineseSinkLabels() {
        NodeDTO fetch = toolNode("fetch", config("tableName", "{{$.input.name}}"));
        ManifestContext ctx = withTrigger("trigger.webhook", ManifestTestFixtures.context(fetch));

        List<UntrustedInput> flows = UntrustedInputTracer.trace(ctx.def());

        assertEquals(1, flows.size(), briefs(flows).toString());
        assertNotNull(flows.get(0).getSinkLabel(), "汇的中文名不许是 null：" + full(flows.get(0)));
        assertFalse(flows.get(0).getSinkLabel().isBlank(), "汇的中文名不许是空串：" + full(flows.get(0)));
        assertEquals(fulls(trace(ctx)), fulls(flows), "与清单入口逐字段相同");
    }

    private static EdgeDTO edge(String from, String to) {
        EdgeDTO edge = new EdgeDTO();
        edge.setFrom(from);
        edge.setTo(to);
        return edge;
    }

}
