package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.EdgeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.GraphReachability;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.TriggerDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.io.NodeIoContract;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.UntrustedInput;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ManifestContext;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ManifestLabels;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.UntrustedInputTracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code WorkflowDslValidator#writtenVarOf} 的「单一出处」强制点（台账 C3，迭代七第 06 轮）
 *
 * <h3>它守的那句话</h3>
 * 「这个节点在执行期真会往 state 写哪个键」只允许有<b>一处</b>答案。这句话听着像洁癖，
 * 而它挡的缺陷是<b>静默</b>的：分岔的两半各自都不报错，只在结果上骗人。
 *
 * <p>改造前的实况就是活的例子。权威那一侧（本类所在文件的 {@code writtenVarOf}）经 {@code fc0a7bd26d}
 * 改成了 {@code configStr(key, default)}，与四个执行器同口径；而污点溯源侧
 * （{@code UntrustedInputTracer#writesOf}）仍走 {@code StrUtil.blankToDefault}，
 * 并且<b>拿「宁可多登记也不留缺口」当理由</b>。那个理由是假的：{@code outputVar} 填纯空白时，
 * 执行器真把值写进一个名为 {@code "  "} 的键，溯源侧却登记成缺省名 {@code tool_result} ——
 * <b>多报</b>一个压根没人写的键、同时<b>少报</b>真正被写脏的那个，从它流向下游的污点整条断掉，
 * 清单于是说「没有不可信数据流进下游」。缺口就在「不留缺口」那一侧。
 *
 * <h3>本类六条断言各守什么</h3>
 * <ol>
 *   <li>{@link #writtenVarOfIsDefinedExactlyOnceAndNobodyRecomputesIt} 钉<b>①</b>：
 *       全 src/main 只剩一处定义、且全模块只剩一处「自己算一遍」的表达式。</li>
 *   <li>{@link #everyConsumerOutsideTheAuthorityCallsIt} 钉<b>调用面</b>：域外三个消费者逐个点名。
 *       少了这一条，「统一」可以靠<b>删掉调用方</b>达成 —— 第 1 条照绿，缺陷原样回来。</li>
 *   <li>{@link #theAuthorityPinsTheBlankBoundary} 钉<b>口径本身</b>：矩阵里每一格的期望值都是字面量。
 *       没有它，第 4 条就只是「两侧一起漂」也能过的相等断言。</li>
 *   <li>{@link #tracerRegistersTheByteIdenticalKey} 钉<b>②</b>：溯源真登记的那个键与权威的答案
 *       逐字相同（含纯空白、含首尾带空格、含非字符串值三档边界）。</li>
 *   <li>{@link #blankGuardedPortsOverReportByDesign} 钉住改造<b>新添</b>的那一处多报，
 *       与第 4 条互为绊线：谁想用 {@code isNotBlank} 把它抹平，第 4 条立刻红。</li>
 *   <li>{@link #scannerIsNotVacuous} 是第 1 条的自检：把改造前那段原文喂给探测器，必须正好扫出 1 条。</li>
 * </ol>
 *
 * <h3>⚠️ 为什么第 4 条要绕到 {@code trace()} 外面去观察，而不是反射读 {@code writesOf}</h3>
 * {@code writesOf} 是 private static，反射能读，但读到的只是「它算了什么」，
 * 读不到「算出来的东西真被当成写入用了吗」。走 {@code trace()} 的公开产物（{@code viaVar}）
 * 才把两件事一起钉住 —— 这正是方案 §8 说的③行为层：判据落在真实场景的结果上。
 *
 * <p>🔴 读取侧表达空白键的写法（{@code {{$.  .probe}}}）不是笔误，理由钉在
 * {@code UntrustedInputTracerTest#blankOutputVarWritesTheBlankKeyNotTheDefault} 的注释里：
 * 占位符正则两侧的 {@code \s*} 会吃掉首尾空白，故那个键<b>只能</b>靠挂一段路径从内部读到。
 *
 * @author 走进新时代
 */
class WrittenVarOfSingleSourceTest {

    /** surefire 的工作目录就是模块根，与 {@code KnownTypesHintSingleSourceTest} / {@code NodeIoContractTest} 同一口径 */
    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    private static final String AGENT_PREFIX = "cn/enlev/ainb/module/agent/";

    /** 权威所在文件（相对 {@link #SOURCE_ROOT}，正斜杠 —— Windows 上 {@code Path#toString()} 是反斜杠） */
    private static final String AUTHORITY_REL =
            AGENT_PREFIX + "framework/agent/core/validate/WorkflowDslValidator.java";

    private static final String DEFINITION_SIGNATURE =
            "public static String writtenVarOf(NodeDTO node, NodeIoContract.OutPort port)";

    private static final String QUALIFIED_CALL = "WorkflowDslValidator.writtenVarOf(";

    /**
     * 「自己再算一遍」的形状：{@code configStr(…, port.defaultName())} 与改造前那个
     * {@code blankToDefault(…, port.defaultName())} 一并覆盖。
     *
     * <p>🔴 刻意<b>不</b>收窄成裸 {@code defaultName()}：{@code NodeIoContract#defaultOutputNames()}
     * 里有三处合法用法（问的是「哪些缺省名要注册 keyStrategy」，台账 C8），与「这个节点写哪个键」
     * 是两个问题。按裸 token 扫就会逼着开白名单，而白名单正是新违规的藏身处。
     * 也刻意<b>不</b>放宽成「出现 {@code configKey()} 就算」：本文件另有两处
     * {@code isBlank(node.configStr(port.configKey()))}，问的是「作者填了这一格没有」（V19/V23 的触发条件），
     * 与「填了什么」是两件事，扫进来就是误报。
     */
    private static final Pattern RECOMPUTE =
            Pattern.compile("(?:configStr|blankToDefault)\\s*\\([^;]*defaultName\\s*\\(\\s*\\)");

    /**
     * 域外三个消费者，逐个点名。数目与名单都钉死：多一个 = 有人新开了读取点（要回来核它是不是也该走权威），
     * 少一个 = 有人把调用删了（那正是「靠删调用方达成统一」）。
     */
    private static final List<String> EXPECTED_CALLER_RELS = List.of(
            AGENT_PREFIX + "framework/agent/core/engine/compiler/DslToStateGraphCompiler.java",
            AGENT_PREFIX + "framework/agent/core/manifest/UntrustedInputTracer.java",
            AGENT_PREFIX + "service/workflow/AgentWorkflowExecutionServiceImpl.java");

    /** 纯空白那一格。两个空格，与 {@code UntrustedInputTracer} javadoc 里举例的那份 DSL 同形 */
    private static final String BLANK = "  ";

    /** 一处源码命中：哪个文件、哪一行、原文 */
    private record Hit(String rel, int line, String text) {

        String where() {
            return rel + ":" + line;
        }
    }

    /** 一格边界：某个类型的某个输出端口填了什么 → 权威该答什么 */
    private record Case(String type, String configKey, Object value, String expected) {

        String describe() {
            return type + "." + configKey + "="
                    + (value == null ? "< absent >" : "\"" + value + "\"");
        }
    }

    /**
     * ②「逐字相同」的边界矩阵。
     *
     * <p>🔴 前六格是本次分岔的正面：未填 / 空串回落成缺省名，纯空白与首尾带空格<b>原样</b>当键名，
     * 非字符串值走 {@code String.valueOf}。后两格换到 {@code defaultName} 为 null 的端口上
     * （{@code citationsVar}），验「没填 ⟹ 答 null ⟹ 两侧都不登记」这半边也对得上。
     */
    private static final List<Case> MATRIX = List.of(
            new Case("tool", "outputVar", null, "tool_result"),
            new Case("tool", "outputVar", "", "tool_result"),
            new Case("tool", "outputVar", BLANK, BLANK),
            new Case("tool", "outputVar", " x ", " x "),
            new Case("tool", "outputVar", "myVar", "myVar"),
            new Case("tool", "outputVar", 42, "42"),
            new Case("knowledge_retrieval", "outputVar", BLANK, BLANK),
            new Case("knowledge_retrieval", "citationsVar", "cites", "cites"));

    // ==================== ① 只有一处定义、没有第二份计算 ====================

    @Test
    @DisplayName("🔴 判据①：writtenVarOf 全 src/main 只剩一处定义，且「自己算一遍」的表达式也只剩权威那一处")
    void writtenVarOfIsDefinedExactlyOnceAndNobodyRecomputesIt() throws IOException {
        List<Hit> definitions = new ArrayList<>();
        List<Hit> recomputes = new ArrayList<>();
        for (Path file : mainSources()) {
            String rel = relOf(file);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (isCommentLine(line)) {
                    continue;
                }
                if (line.contains(DEFINITION_SIGNATURE)) {
                    definitions.add(new Hit(rel, i + 1, line.trim()));
                }
                Matcher matcher = RECOMPUTE.matcher(line);
                while (matcher.find()) {
                    recomputes.add(new Hit(rel, i + 1, line.trim()));
                }
            }
        }

        int authorityLine = definitionLine();
        assertEquals(List.of(AUTHORITY_REL + ":" + authorityLine),
                definitions.stream().map(Hit::where).toList(),
                """
                writtenVarOf 的定义处与钉住的不一致。

                0 条 = 方法被改名 / 改了可见性 / 删了：那三个域外消费者会一起编译不过，
                      请把 DEFINITION_SIGNATURE 与 AUTHORITY_REL 同步到新位置。
                2 条以上 = 有人又抄了一份。抄一份的代价不是「重复代码」，而是<b>静默分岔</b>：
                      两份对「纯空白那一格」的答案可以不同，而两边都不报错 ——
                      台账 C3 就是这么来的（溯源侧还拿「宁可多登记也不留缺口」当理由，
                      而它恰恰漏掉了真被写脏的那个键）。
                """);

        assertEquals(1, recomputes.size(),
                "全 src/main 里「自己算一遍键名」的表达式应只剩权威那一处，实际扫到 " + recomputes.size()
                        + " 处：" + recomputes.stream().map(Hit::where).toList()
                        + "。多出来的那一处就是下一份会漂的副本 —— 请改成调 writtenVarOf");
        Hit only = recomputes.get(0);
        assertEquals(AUTHORITY_REL, only.rel(),
                "唯一那处计算不在权威文件里，而在 " + only.where() + " —— 那 writtenVarOf 就成了没人用的摆设");
        assertEquals(authorityLine + 1, only.line(),
                "唯一那处计算落在 " + only.where() + "，而权威签名在 :" + authorityLine
                        + "。方法体目前只有一行，故它必须正好是签名的下一行；"
                        + "落在别处 = 权威自己内部又开了第二份口径。"
                        + "若你确实把方法写长了，请回来核这一处仍是唯一那份计算，再把这个数改成新行号");
    }

    @Test
    @DisplayName("🔴 域外三个消费者逐个点名调权威 —— 少一个就说明「统一」是靠删调用方达成的")
    void everyConsumerOutsideTheAuthorityCallsIt() throws IOException {
        Map<String, Integer> callsPerFile = new LinkedHashMap<>();
        for (Path file : mainSources()) {
            String rel = relOf(file);
            int count = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!isCommentLine(line)) {
                    count += occurrences(line, QUALIFIED_CALL);
                }
            }
            if (count > 0) {
                callsPerFile.put(rel, count);
            }
        }

        assertEquals(EXPECTED_CALLER_RELS, List.copyOf(callsPerFile.keySet()),
                """
                调 WorkflowDslValidator.writtenVarOf 的文件名单与钉住的不一致（顺序按扫描序）。

                少了 = 有人把调用删了 / 改回自己算：那条口径就脱离了权威，而脱离的表现是静默的
                      （两侧各自都不报错，只在结果上骗人）。若确实是整体废弃，请连本条名单一起改并说明理由。
                多了 = 新开了一个读取点：先回本类 javadoc 核一遍它问的是不是同一个问题
                      （「这个节点写哪个键」）。⚠️ 别把「作者填了这一格没有」那种 isBlank(configStr(configKey))
                      的存在性判断也并进来 —— 那是 V19/V23 的触发条件，判据不同，合并会让两边都说谎。
                """);
        for (String rel : EXPECTED_CALLER_RELS) {
            assertTrue(callsPerFile.get(rel) >= 1, rel + " 里一次都没调，名单是假的");
        }
    }

    // ==================== ② 两侧产物逐字相同 ====================

    @Test
    @DisplayName("🔴 权威自己的口径钉死：未填/空串回落缺省名，纯空白与首尾空格原样当键名，非字符串走 valueOf")
    void theAuthorityPinsTheBlankBoundary() {
        for (Case testCase : MATRIX) {
            NodeDTO node = producer(testCase);
            String actual = WorkflowDslValidator.writtenVarOf(node, outPort(testCase.type(), testCase.configKey()));
            assertEquals(testCase.expected(), actual,
                    "边界矩阵 " + testCase.describe() + " 的答案变了。两种成因都要人来判："
                            + "① 执行器的取键口径真的改了（那就同步这里的字面量，并去核四个执行器）；"
                            + "② 有人把它「顺手统一成 blankToDefault / 加个 trim」—— 那会让算出来的键"
                            + "与运行期真写的键分岔，而分岔<b>不报错</b>：下游按算出来的名字取值，静默拿到空");
        }
    }

    @Test
    @DisplayName("🔴 判据②：溯源真登记成写入的那个键，与权威的答案逐字相同（含纯空白这个边界）")
    void tracerRegistersTheByteIdenticalKey() {
        for (Case testCase : MATRIX) {
            NodeDTO producer = producer(testCase);
            String expected = testCase.expected();

            // 下游读那个键。挂一段 .probe 路径是必须的：键名带首尾空白时，
            // 裸占位符会被 PLACEHOLDER 正则两侧的 \s* 吃掉，读侧压根表达不出来
            NodeDTO consumer = new NodeDTO();
            consumer.setId("consumer");
            consumer.setType("llm");
            consumer.setName("consumer");
            consumer.setConfig(config("prompt", "{{$." + expected + ".probe}}"));

            List<UntrustedInput> flows = UntrustedInputTracer.trace(context(null, producer, consumer));

            assertEquals(1, flows.size(),
                    "边界矩阵 " + testCase.describe() + "：本该正好报出「producer 写下的键流进 consumer」这一条，"
                            + "实报 " + briefs(flows));
            assertEquals(expected, flows.get(0).getViaVar(),
                    "边界矩阵 " + testCase.describe() + "：溯源登记的键名与权威的答案不逐字相同。"
                            + "期望长度 " + lengthOf(expected) + "、实得长度 " + lengthOf(flows.get(0).getViaVar())
                            + "（空白在断言输出里看不见，故比长度）。"
                            + "改前这里正是分岔点：权威答「" + BLANK + "」而溯源答缺省名，"
                            + "于是真被写脏的键没人播种、污点整条断流");
            assertEquals("producer", flows.get(0).getViaNodeId(),
                    "边界矩阵 " + testCase.describe() + "：那个键只有 producer 一个产出者，该指认它");
        }
    }

    @Test
    @DisplayName("🔴 两个用 isNotBlank 守卫的端口：空白键被多报一档，这是有意的取舍，与上一条互为绊线")
    void blankGuardedPortsOverReportByDesign() {
        // condition.llm.outputVar 与 agent_loop.trajectoryVar 的执行侧是 StrUtil.isNotBlank 守卫
        //（LlmConditionNodeExecutor#execute、AgentLoopGraphBuilder 的两处 delta.put）⟹ 填纯空白时压根不写。
        // 溯源侧现在照样登记它：多报。这两个节点不是「新鲜源」，故要靠 chat 触发器 + 一个可引用入端口
        // 把污点送进去，再顺着写集传到下游 —— 走的是 propagate 那条路，与上一条的 seeding 那条互补。
        for (String[] testCase : List.of(
                new String[]{"condition.llm", "prompt", "outputVar"},
                new String[]{"agent_loop", "goal", "trajectoryVar"})) {
            String type = testCase[0];
            NodeDTO producer = new NodeDTO();
            producer.setId("producer");
            producer.setType(type);
            producer.setName("producer");
            producer.setConfig(config(testCase[1], "{{$.input}}", testCase[2], BLANK));

            NodeDTO consumer = new NodeDTO();
            consumer.setId("consumer");
            consumer.setType("llm");
            consumer.setName("consumer");
            consumer.setConfig(config("prompt", "{{$." + BLANK + ".probe}}"));

            List<UntrustedInput> flows = UntrustedInputTracer.trace(
                    context("trigger.chat", producer, consumer));

            UntrustedInput intoConsumer = flows.stream()
                    .filter(f -> "consumer".equals(f.getSinkNodeId())).findFirst().orElse(null);
            assertNotNull(intoConsumer,
                    type + "." + testCase[2] + " 填纯空白时，溯源本该把那个空白键登记成写入并把污点传下去，"
                            + "实报 " + briefs(flows));
            assertEquals(BLANK, intoConsumer.getViaVar(),
                    type + "." + testCase[2] + "：登记的键名该与权威的答案逐字相同（两个空格）");

            // 🔴 这一条与 tracerRegistersTheByteIdenticalKey 互为绊线，别单独「修」任何一边：
            //    想把这里的过度登记抹平，唯一的办法是把 writesOf 那个过滤换成 isNotBlank ——
            //    而那会同时把 tool / llm / script / knowledge_retrieval 四个执行器的空白键滤掉，
            //    也就是把台账 C3 刚关掉的「少报」原样请回来（上一条会红）。
            //    多报这一档到不了运行期：DslToStateGraphCompiler#validateStateKeyNames（台账 E12）
            //    顺着同一份 outputs() 把纯空白的名字一律拒掉，能带着它走到溯源的只有清单那条路
            //    （干跑 fail-soft），那时作者同时看得见「名字不合法」与一条多出来的流向，两条指向同一格。
            assertEquals(1, intoConsumer.getDepth(), "经一跳传播而来，depth 该是 1");
        }
    }

    // ==================== 探测器自检 ====================

    @Test
    @DisplayName("🔴 探测器自检：改造前那段原文必须正好扫出 1 条，改造后与契约里那三处合法用法必须扫出 0 条")
    void scannerIsNotVacuous() {
        // 改造前 UntrustedInputTracer#writesOf 的真实写法（C3 之前的原文）
        String before = """
                String name = port.configKey() == null ? null : node.configStr(port.configKey());
                name = StrUtil.blankToDefault(name, port.defaultName());
                if (StrUtil.isNotBlank(name)) {
                    writes.add(name);
                }""";
        List<String> hits = recomputeHitsIn(before);
        assertEquals(1, hits.size(),
                "探测器在改造前那段原文里扫出 " + hits.size() + " 条，期望正好 1 条：" + hits
                        + "。扫少了 ⟹ 上面那条「只剩一处计算」是假绿（它正是靠这个探测器数的）；"
                        + "扫多了 ⟹ 探测器把「作者填了这一格没有」那种存在性判断也算进来了");
        assertTrue(hits.get(0).contains("blankToDefault"), "扫出来的该是 blankToDefault 那一行：" + hits);

        // 改造后：调权威，不自己算
        assertEquals(0, recomputeHitsIn("""
                String name = WorkflowDslValidator.writtenVarOf(node, port);
                if (StrUtil.isNotEmpty(name)) {
                    writes.add(name);
                }""").size(), "改造后的写法被误判成「自己算一遍」，探测器口径太宽");

        // NodeIoContract#defaultOutputNames() 里那三处 defaultName() 问的是另一件事（台账 C8），不许误判
        assertEquals(0, recomputeHitsIn("""
                if (port.defaultName() != null && !port.defaultName().isBlank()) {
                    names.add(port.defaultName());
                }""").size(), "契约里「缺省名要不要注册 keyStrategy」那三处被误判，探测器口径太宽");

        // 本文件里 V19/V23 那两处存在性判断也不许误判
        assertEquals(0, recomputeHitsIn("""
                if (StrUtil.isBlank(node.configStr(port.configKey()))) {
                    continue;
                }""").size(), "「作者填了这一格没有」的存在性判断被误判成「自己算键名」，探测器口径太宽");
    }

    // ==================== 夹具与工具 ====================

    private static NodeDTO producer(Case testCase) {
        NodeDTO node = new NodeDTO();
        node.setId("producer");
        node.setType(testCase.type());
        node.setName("producer");
        Map<String, Object> config = new LinkedHashMap<>();
        // 🔴 null 与「键在、值是空串」是两档不同的退化形态，故 null 时压根不放这个键
        if (testCase.value() != null) {
            config.put(testCase.configKey(), testCase.value());
        }
        node.setConfig(config);
        return node;
    }

    private static NodeIoContract.OutPort outPort(String type, String configKey) {
        NodeIoContract.Contract contract = NodeIoContract.of(type);
        assertNotNull(contract, "契约里没有 " + type + " —— 矩阵写错了类型名");
        for (NodeIoContract.OutPort port : contract.outputs()) {
            if (configKey.equals(port.configKey())) {
                return port;
            }
        }
        throw new AssertionError("契约 " + type + " 里没有 configKey=" + configKey + " 的输出端口 —— 矩阵写错了端口名");
    }

    /** 把这些节点串成 {@code START → n0 → n1 → … → END} 的一条链。构造方式与 {@code ManifestTestFixtures} 一致 */
    private static ManifestContext context(String triggerType, NodeDTO... nodes) {
        WorkflowDefDTO def = new WorkflowDefDTO();
        def.setDslVersion("1.0");
        def.setId("wf_written_var");
        def.setName("writtenVarOf 夹具");
        if (triggerType != null) {
            TriggerDTO trigger = new TriggerDTO();
            trigger.setType(triggerType);
            def.setTrigger(trigger);
        }
        def.setNodes(List.of(nodes));

        List<EdgeDTO> edges = new ArrayList<>();
        String previous = "START";
        for (NodeDTO node : nodes) {
            edges.add(edge(previous, node.getId()));
            previous = node.getId();
        }
        edges.add(edge(previous, "END"));
        def.setEdges(edges);

        Map<String, NodeDTO> nodeById = new HashMap<>();
        for (NodeDTO node : nodes) {
            nodeById.putIfAbsent(node.getId(), node);
        }
        return new ManifestContext(def, GraphReachability.of(def), Map.copyOf(nodeById),
                new ManifestLabels(null));
    }

    private static EdgeDTO edge(String from, String to) {
        EdgeDTO edge = new EdgeDTO();
        edge.setFrom(from);
        edge.setTo(to);
        return edge;
    }

    /** 保序 config：{@code Map.of} 的迭代序每个 JVM 实例都不同，会把流向顺序带乱 */
    private static Map<String, Object> config(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static List<String> briefs(List<UntrustedInput> flows) {
        List<String> result = new ArrayList<>();
        for (UntrustedInput flow : flows) {
            result.add(flow.getSinkNodeId() + "#" + flow.getSinkArg() + "#" + flow.getSourceKind()
                    + "#viaVar=" + flow.getViaVar() + "#depth=" + flow.getDepth());
        }
        return result;
    }

    /** 空白在断言输出里看不见，故失败信息里一律带长度 */
    private static String lengthOf(String text) {
        return text == null ? "<null>" : String.valueOf(text.length());
    }

    private static List<String> recomputeHitsIn(String snippet) {
        List<String> hits = new ArrayList<>();
        for (String line : snippet.split("\n", -1)) {
            Matcher matcher = RECOMPUTE.matcher(line);
            while (matcher.find()) {
                hits.add(line.trim());
            }
        }
        return hits;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int hit = text.indexOf(needle, from);
            if (hit < 0) {
                return count;
            }
            count++;
            from = hit + needle.length();
        }
    }

    private static String relOf(Path file) {
        return SOURCE_ROOT.relativize(file).toString().replace('\\', '/');
    }

    /** 与 {@code AgentArchitectureTest#isCommentLine} 同一口径：整行是注释就跳过（行尾注释不跳，宁可误报也别漏报） */
    private static boolean isCommentLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> stream = Files.walk(SOURCE_ROOT)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            // 🔴 下限自检：路径口径写窄只会静少扫，而少扫的强制点看着一样绿。
            //    本模块 src/main 有几百个 .java，取 100 只在整体坏掉时才红。
            assertTrue(files.size() >= 100,
                    "只扫到 " + files.size() + " 个源文件（工作目录应是模块根）—— 本类三条源码断言全部不作数");
            return files;
        }
    }

    /** 权威那一行的行号（1 起），用于把「唯一那处计算」钉在方法体里而不是同文件的别处 */
    private static int definitionLine() {
        try {
            List<String> lines = Files.readAllLines(SOURCE_ROOT.resolve(AUTHORITY_REL), StandardCharsets.UTF_8);
            // 下限自检：真实 2400+ 行，取 1500 只在扫错文件时才红
            assertTrue(lines.size() >= 1500,
                    AUTHORITY_REL + " 只有 " + lines.size() + " 行 —— 扫到的不是那个文件，本条断言不作数");
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(DEFINITION_SIGNATURE)) {
                    return i + 1;
                }
            }
        } catch (IOException ex) {
            throw new AssertionError("读不到 " + AUTHORITY_REL, ex);
        }
        throw new AssertionError("在 " + AUTHORITY_REL + " 里找不到签名「" + DEFINITION_SIGNATURE + "」");
    }

}
