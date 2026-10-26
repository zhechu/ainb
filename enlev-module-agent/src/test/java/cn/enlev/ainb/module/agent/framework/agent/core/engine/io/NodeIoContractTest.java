package cn.enlev.ainb.module.agent.framework.agent.core.engine.io;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlBpmApprovalNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlConfirmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.KnowledgeRetrievalNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.LlmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ScriptNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ToolNodeExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NodeIoContract} 契约登记表的单元测试。
 *
 * <p>契约是「前端画类型化端口 + 下游点选上游产出」的权威依据，且被 {@code WorkflowDslValidator}
 * 用来判断「引用是否由上游产出」。它必须覆盖全部 13 种合法节点类型、每个端口字段自洽，
 * 否则前端端口会缺、可达性校验会漏判。
 *
 * @author 走进新时代
 */
public class NodeIoContractTest {

    /** 前端 nodeSummary.NODE_TYPE_META 的 13 种合法类型，逐字对齐 */
    private static final Set<String> LEGAL_TYPES = Set.of(
            "tool", "llm", "knowledge_retrieval", "script",
            "condition.rule", "condition.llm", "parallel", "iteration",
            "hitl.confirm", "hitl.bpm_approval", "wait", "agent_loop", "sub_workflow");

    private static final Set<String> LEGAL_DATA_TYPES = Set.of(
            NodeIoContract.T_STRING, NodeIoContract.T_NUMBER, NodeIoContract.T_BOOLEAN,
            NodeIoContract.T_OBJECT, NodeIoContract.T_ARRAY);

    private static final Set<String> LEGAL_MODES = Set.of(
            NodeIoContract.MODE_LITERAL, NodeIoContract.MODE_REFERENCE, NodeIoContract.MODE_BOTH);

    @Test
    @DisplayName("覆盖全部 13 种合法节点类型，且不多不少")
    void allTypesCovered() {
        assertEquals(LEGAL_TYPES, NodeIoContract.types(),
                "契约登记的类型必须与 13 种合法节点类型完全一致");
        assertEquals(13, NodeIoContract.all().size());
    }

    @Test
    @DisplayName("每种类型的契约字段自洽（type/label/group 非空，端口非 null 且合法）")
    void everyContractWellFormed() {
        for (NodeIoContract.Contract c : NodeIoContract.all()) {
            assertNotNull(c, "契约不能为 null");
            assertTrue(LEGAL_TYPES.contains(c.type()), "未登记类型: " + c.type());
            assertFalse(isBlank(c.label()), "类型缺中文名: " + c.type());
            assertFalse(isBlank(c.group()), "类型缺分组: " + c.type());
            assertNotNull(c.inputs(), "inputs 不能为 null: " + c.type());
            assertNotNull(c.outputs(), "outputs 不能为 null: " + c.type());

            for (NodeIoContract.InPort p : c.inputs()) {
                assertFalse(isBlank(p.key()), "输入端口缺 key: " + c.type());
                assertFalse(isBlank(p.label()), "输入端口缺 label: " + c.type());
                assertTrue(LEGAL_DATA_TYPES.contains(p.dataType()),
                        "输入端口 dataType 非法: " + c.type() + "." + p.key() + "=" + p.dataType());
                assertTrue(LEGAL_MODES.contains(p.mode()),
                        "输入端口 mode 非法: " + c.type() + "." + p.key() + "=" + p.mode());
            }
            for (NodeIoContract.OutPort p : c.outputs()) {
                assertFalse(isBlank(p.label()), "输出端口缺 label: " + c.type());
                assertTrue(LEGAL_DATA_TYPES.contains(p.dataType()),
                        "输出端口 dataType 非法: " + c.type() + "=" + p.dataType());
                assertTrue(!isBlank(p.configKey()) || !isBlank(p.defaultName()),
                        "输出端口须至少有 configKey 或 defaultName: " + c.type());
            }
        }
    }

    @Test
    @DisplayName("of() 命中已登记类型、未知类型返回 null")
    void ofLookup() {
        assertNotNull(NodeIoContract.of("tool"));
        assertNull(NodeIoContract.of("unknown_type"));
        assertNull(NodeIoContract.of(null));
    }

    @Test
    @DisplayName("关键产出端口：tool/llm/script/condition.llm/agent_loop 都有 outputVar")
    void outputVarPorts() {
        for (String type : List.of("tool", "llm", "script", "condition.llm", "agent_loop")) {
            NodeIoContract.Contract c = NodeIoContract.of(type);
            assertTrue(c.outputs().stream().anyMatch(o -> "outputVar".equals(o.configKey())),
                    type + " 应有 outputVar 输出端口");
        }
    }

    @Test
    @DisplayName("人工节点产出布尔批准变量；knowledge_retrieval 默认输出名 retrieved")
    void specificPorts() {
        NodeIoContract.Contract confirm = NodeIoContract.of("hitl.confirm");
        assertTrue(confirm.inputs().isEmpty(), "hitl.confirm 无输入端口");
        assertEquals(1, confirm.outputs().size());
        assertEquals("approveVar", confirm.outputs().get(0).configKey());
        assertEquals(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR, confirm.outputs().get(0).defaultName(),
                "默认批准变量名必须等于执行器常量；「不许抄字面量」那一半由 mainSourcesHaveNoDefaultNameLiterals 守");
        assertEquals(NodeIoContract.T_BOOLEAN, confirm.outputs().get(0).dataType());

        NodeIoContract.Contract kr = NodeIoContract.of("knowledge_retrieval");
        assertEquals(2, kr.outputs().size());
        assertEquals(KnowledgeRetrievalNodeExecutor.DEFAULT_OUTPUT_VAR, kr.outputs().get(0).defaultName());
        assertEquals("citationsVar", kr.outputs().get(1).configKey());

        NodeIoContract.Contract bpm = NodeIoContract.of("hitl.bpm_approval");
        assertEquals(2, bpm.outputs().size(), "bpm_approval 产出 approveVar + statusVar");
    }

    @Test
    @DisplayName("有默认输出名的 7 个端口（6 种类型），契约一律引用执行器常量")
    void defaultNamesReferenceExecutorConstants() {
        // 🔴 这四个曾有两个（tool/llm）写着 null，而执行器一直有兜底默认名。运行期写进 state 的那个键
        //    由 NodeIoContractRuntimeAlignmentTest 真跑执行器验证；这里守的是另一半 —— 契约不许自己抄字面量，
        //    否则改执行器常量时契约不会跟着动，两边又分家。
        assertEquals(ToolNodeExecutor.DEFAULT_OUTPUT_VAR, defaultNameOf("tool"),
                "tool 的默认输出名必须引用 ToolNodeExecutor.DEFAULT_OUTPUT_VAR");
        assertEquals(LlmNodeExecutor.DEFAULT_OUTPUT_VAR, defaultNameOf("llm"),
                "llm 的默认输出名必须引用 LlmNodeExecutor.DEFAULT_OUTPUT_VAR");
        assertEquals(ScriptNodeExecutor.DEFAULT_OUTPUT_VAR, defaultNameOf("script"),
                "script 的默认输出名必须引用 ScriptNodeExecutor.DEFAULT_OUTPUT_VAR");
        assertEquals(KnowledgeRetrievalNodeExecutor.DEFAULT_OUTPUT_VAR, defaultNameOf("knowledge_retrieval"),
                "knowledge_retrieval 的默认输出名必须引用 KnowledgeRetrievalNodeExecutor.DEFAULT_OUTPUT_VAR");

        // 🔴 三个 hitl 回调键曾漏在本方法之外（本方法原标题写「四个」，真实域是 6 种类型 / 7 个端口）。
        //    它们比上面四个更要紧：写回方（AgentAssistantServiceImpl 的聊天内恢复、AgentBpmApprovalListener
        //    的审批完成事件）都在<b>拿不到节点 config</b> 的位置上，只能写死默认名。
        //    ⚠️ 这一段原先接着写「所以 KeyStrategyFactoryGenerator 与 RESUME_REGISTERED_VARS
        //    都无条件注册/豁免这三个键 —— <b>两处引的都是执行器常量</b>，只有契约抄了字面量」。
        //    C8 之后那句<b>陈了</b>：两处都改成从 {@link NodeIoContract#defaultOutputNames()} 派生
        //    （生成器遍历它注册、校验器直接拿它当豁免集），不再各自引常量 —— 这正是 C8 的目的：
        //    「注册集」与「豁免集」同源，漂移才是缺陷。于是<b>直接</b>引常量的读者从四个降到两个
        //    （契约 + {@code AgentAssistantServiceImpl}），另两个变成经契约的<b>间接</b>读者。
        //    生成器里仍留着那三条显式 {@code addStrategy}（当「地板」，理由写在它自己那儿），
        //    所以本扫描的口径没变：它拦的是<b>字面量</b>，不是「引了几处常量」。
        assertEquals(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR, defaultNameOf("hitl.confirm", "approveVar"),
                "hitl.confirm 的默认批准键必须引用 HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR");
        assertEquals(HitlBpmApprovalNodeExecutor.DEFAULT_APPROVE_VAR,
                defaultNameOf("hitl.bpm_approval", "approveVar"),
                "hitl.bpm_approval 的默认批准键必须引用 HitlBpmApprovalNodeExecutor.DEFAULT_APPROVE_VAR");
        assertEquals(HitlBpmApprovalNodeExecutor.DEFAULT_STATUS_VAR,
                defaultNameOf("hitl.bpm_approval", "statusVar"),
                "hitl.bpm_approval 的默认状态键必须引用 HitlBpmApprovalNodeExecutor.DEFAULT_STATUS_VAR");

        // 对照：这两个类型的 outputVar 运行期<b>没有</b>兜底（LlmConditionNodeExecutor 仅在非空时才写；
        // agent_loop 的 outputVar 为空直接编译失败），故契约必须继续是 null，别顺手给它们编一个默认名。
        assertNull(defaultNameOf("condition.llm"), "condition.llm 无默认输出名：不配就不写");
        assertNull(defaultNameOf("agent_loop"), "agent_loop 的 outputVar 必填，没有默认名");

        // 🔴 域自检：上面点名的 7 个必须就是契约里全部的默认名。少了这条，
        //    将来新增一个带默认名的端口时本方法照样绿，而它压根没被点名过 —— 与「四个」那个原标题同一种烂法。
        assertEquals(7, registeredDefaultNames().size(),
                "契约里带默认名的输出端口应是 7 个，实际：" + registeredDefaultNames()
                        + "。数量变了就要回来给新端口补一条断言（本方法的标题曾写着「四个」而真实域是 7）");
    }

    /**
     * 🔴 {@code defaultOutputNames()} 是 C8 之后<b>三处</b>共用的唯一口径
     * （生成器的注册 / 校验器的豁免 / 本文件的字面量扫描域），所以它自己的三条性质必须钉住。
     *
     * <p>为什么值得单开一条：那三个消费者都是「拿到集合就逐个处理」，
     * 集合里混进一个 {@code null} 或空串时，<b>没有一个会在源头把话说清楚</b> ——
     * 生成器那侧 {@code KeyStrategyFactoryBuilder.addStrategy(null, REPLACE)} <b>不抛</b>
     * （已用 {@code javap -c} 核实：内部就是个 {@code HashMap}，{@code addStrategy} 就是 {@code Map.put}），
     * 只是静默多出一个永不命中的注册；校验器那侧不抛、只是白豁免一个不存在的名字；
     * 而本文件的扫描会拿 {@code "\"" + name + "\""} 去 src/main 里找 —— 空串那一档的探针退化成
     * {@code ""}，<b>命中源码里每一个空字符串字面量</b>，报出满屏与真实缺陷无关的假违规。
     */
    @Test
    @DisplayName("🔴 defaultOutputNames()：无空白项、去重、不可变、与逐端口枚举同集")
    void defaultOutputNamesIsTheSingleSourceOfTruth() {
        Set<String> names = NodeIoContract.defaultOutputNames();

        // ① 无 null / 无空白：三种失败形态见方法 javadoc，没有一种指向这里
        for (String name : names) {
            assertNotNull(name, "缺省输出名里有 null：" + names);
            assertFalse(isBlank(name), "缺省输出名里有空白项（" + name + "）：" + names);
        }

        // ② 与「自己逐个端口枚举」同集 —— 这条防的是<b>遍历写窄</b>：
        //    只看每种类型 outputs().get(0) 的话，hitl.bpm_approval 的 statusVar 与
        //    knowledge_retrieval 的 citationsVar（后者是 null，故不影响集合）都会被漏掉。
        //    🔴 它同时是 ① 的<b>非空地板</b>：域从契约现场算出来，契约被清空时两侧一起变空、
        //    本方法仍然绿，但那时 assertEquals(7, …) 那条会红 —— 两处一松一紧。
        Set<String> enumerated = new LinkedHashSet<>();
        for (NodeIoContract.Contract c : NodeIoContract.all()) {
            for (NodeIoContract.OutPort p : c.outputs()) {
                if (p.defaultName() != null && !p.defaultName().isBlank()) {
                    enumerated.add(p.defaultName());
                }
            }
        }
        assertEquals(enumerated, names,
                "defaultOutputNames() 与逐端口枚举的结果不同集：遍历写窄/写宽了。实际 " + names);

        // ③ 数量钉住：今天 7 个端口正好 7 个<b>互不相同</b>的默认名。
        //    去重是生产方法该有的性质（两个端口共用一个默认名时只该注册一次），
        //    但真发生那种共用时本条会红 —— 那是有意的：共用意味着两个节点类型往同一个键写，
        //    互相覆盖，值得停下来看一眼是不是写错了，而不是让去重把它悄悄抹平。
        assertEquals(7, names.size(), "缺省输出名去重后应是 7 个，实际 " + names);

        // ④ 不可变：三个消费者都把它当常量持有（校验器那份是 static final 字段），
        //    谁能改它，改完的影响面是<b>全 JVM</b>、且看不出是谁改的
        assertThrows(UnsupportedOperationException.class,
                () -> names.add("x"), "defaultOutputNames() 返回了可变的集合");
    }

    /** 唯一可以合法持有默认名字面量的行形态：执行器里的常量声明本身 */
    private static final Pattern CONSTANT_DECLARATION =
            Pattern.compile("public\\s+static\\s+final\\s+String\\s+DEFAULT_\\w+\\s*=");

    /**
     * 🔴 扫 src/main 全部源码：除执行器的常量声明行外，不许出现默认名的<b>字符串字面量</b>
     *
     * <p>为什么值比对不够：{@link #defaultNamesReferenceExecutorConstants} 比的是「契约的默认名 == 执行器常量」，
     * 而契约改成引用常量之后，那条断言在编译期就是 {@code assertEquals(X, X)} —— <b>恒真</b>。
     * 有人把常量引用改回一个<b>同值</b>的字面量，值比对照样绿，纪律却已经破了：下一次改执行器常量时
     * 那个读者不会跟着动，读者们又分家。<b>只有扫源码能区分「引用」与「同值字面量」</b>。
     *
     * <p>⚠️ 读者的<b>拓扑</b>在 C8 之后变了，本段原先写「四个读者（契约 / {@code RESUME_REGISTERED_VARS}
     * / {@code KeyStrategyFactoryGenerator} / {@code AgentAssistantServiceImpl}）」—— 那是<b>直接</b>引常量的四个。
     * 现在后两个里只剩生成器还直接引（三条当「地板」的显式 {@code addStrategy}），
     * {@code RESUME_REGISTERED_VARS} 已改成<b>等于</b> {@link NodeIoContract#defaultOutputNames()}。
     * ⟹ 直接读者 2 个（契约 / {@code AgentAssistantServiceImpl}）+ 生成器的地板 1 处，
     * 另有两个<b>间接</b>读者（生成器的遍历、校验器的豁免集）经契约取值。
     * 🔴 间接读者<b>正是本扫描仍然要扫全部 src/main 的理由</b>：它们不再持有名字，
     * 所以「谁把某个 {@code defaultName} 改成 null」这种改法对它们是静默的 ——
     * 生成器会少注册一个键、校验器会少豁免一个键，两侧<b>一起</b>错，值比对与差集断言都看不出来。
     * 守住那一档的是 {@code KeyStrategyFactoryGeneratorTest} 里的两条地板断言（size ≥ 7 + 点名 tool_result）。
     *
     * <h3>为什么扫整个 src/main，而不是只扫契约那一个文件</h3>
     * 本刀原先只改契约的 3 处，是变异探针 M2 顺手 grep 出<b>第 4 处</b>
     * （{@code AgentAssistantServiceImpl.HITL_APPROVE_VAR} 也抄着 {@code "hitl_approved"}）。
     * 只扫契约的话，那一处改完就<b>没有强制点</b>了 —— 谁把它改回同值字面量，全套测试照样绿，
     * 而它是最要紧的一处：写错键的表现是「用户点了同意却按拒绝走完、执行状态还报 SUCCEEDED」。
     * 故把域放到全部生产源码，让所有直接读者一次守住
     * （直接/间接读者的拓扑见上面「为什么值比对不够」那一段的 ⚠️）。
     *
     * <h3>⚠️ 域的三个边界（照 §0.8.4.1③「『真跑』也有域」写明，别让读者以为它管得更多）</h3>
     * <ol>
     *   <li><b>不含 src/test</b> —— 而且是<b>刻意</b>不含：测试的职责恰恰相反，
     *       {@code HitlConfirmResumeRuntimeTest} 等处<b>必须</b>钉字面量，这样改执行器常量的<b>值</b>才会红。
     *       生产代码引常量、测试钉字面量，两侧一松一紧，缺任何一侧改名都会静默通过。</li>
     *   <li><b>不含 src/main/resources</b> —— 提示词模板里出现这些键名是在教模型，不是副本。</li>
     *   <li><b>扫的是文本行，分不清注释与代码</b> —— 故 javadoc 里点名这三个键时必须写
     *       {@code {@code hitl_approved}} 这种不带引号的形式；带引号会被判成字面量。
     *       这不是缺陷而是取舍：真去解析注释就得写个 Java 词法器，而它带来的盲区
     *       （行尾注释里藏一个字面量）比它挡住的误报更贵。</li>
     * </ol>
     *
     * <p>豁免用<b>行形态</b>表达，不抄文件名单（抄名单就是第二份口径）。
     * 末尾那条数量断言给这个豁免兜底：声明行数必须正好等于默认名个数，
     * 于是「在别的类里新声明一个 {@code DEFAULT_XXX = "hitl_approved"} 再引它」会被数出来 —— 那正是
     * 本模块反复撞上的「名单出现第二份副本」（见 {@code AgentArchitectureTest#testNoCustomTablePrefixConstants}）。
     */
    @Test
    @DisplayName("🔴 src/main 里零个默认名字面量（执行器声明行除外）—— 值比对在引用常量后恒真，只有扫源码拦得住")
    void mainSourcesHaveNoDefaultNameLiterals() throws Exception {
        List<String> violations = new ArrayList<>();
        List<String> declarations = new ArrayList<>();
        for (Path file : mainSources()) {
            String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                List<String> hits = defaultNameLiteralsIn(lines[i]);
                if (hits.isEmpty()) {
                    continue;
                }
                String where = file.getFileName() + ":" + (i + 1);
                if (CONSTANT_DECLARATION.matcher(lines[i]).find()) {
                    declarations.add(where + " " + hits);
                } else {
                    violations.add(where + " → " + hits);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                """
                src/main 里出现了默认名的字符串字面量，应改成引用执行器常量：
                """ + violations + """

                同值字面量能骗过值比对（引用常量之后 assertEquals(X, X) 恒真），\
                但改执行器常量时这个读者不会跟着动。
                三个 hitl 键的写回方都拿不到节点 config、只能写死默认名，故分家的后果是运行期静默走错分支。
                ⚠️ 若命中的是 javadoc，把引号去掉即可（本扫描分不清注释与代码，见方法 javadoc 第 3 条）。
                """);
        assertEquals(registeredDefaultNames().size(), declarations.size(),
                "豁免的常量声明行应正好是 " + registeredDefaultNames().size() + " 行（每个默认名一处），实际 "
                        + declarations.size() + "：" + declarations
                        + "。多了说明有人在执行器之外新声明了一个同值常量 —— 那就是名单的第二份副本；"
                        + "少了说明有声明行被误判成违规");
    }

    @Test
    @DisplayName("🔴 探测器自检：换回同值字面量必须被扫出来，而引用常量与 javadoc 点名必须不被扫出来")
    void literalDetectorIsNotVacuous() {
        // 🔴 名字从契约现场取，不在这里抄一个。抄了的话，改执行器常量的<b>值</b>会让本自检红，
        //    而它红的原因其实是「键改名了」，报出来的却是「探测器扫不出来」—— 一句指错方向的失败信息。
        //    这条是变异探针 M2 逼出来的：把 DEFAULT_STATUS_VAR 的值改成 bpm_stat，
        //    原本写死 "bpm_status" 的自检就红了，而它本该对改名无感（改名由别处钉字面量的测试去红）。
        String name = registeredDefaultNames().get(0);
        String quoted = "\"" + name + "\"";
        // 正向：这正是「有人图省事改回去」的形态 —— 值一模一样，值比对看不出来
        assertEquals(List.of(quoted),
                defaultNameLiteralsIn("List.of(out(\"anyVar\", " + quoted + ", T_OBJECT, \"结果\")))"),
                "探测器连一条同值字面量都扫不出来，上一条测试是假绿");
        // 反向：契约今天的真实写法（引用常量）必须扫不出来
        assertTrue(defaultNameLiteralsIn(
                        "out(\"outputVar\", ToolNodeExecutor.DEFAULT_OUTPUT_VAR, T_OBJECT, \"工具返回结果\")")
                        .isEmpty(),
                "引用常量的写法被误判成字面量，探测器口径太宽");
        // 反向：javadoc 里不带引号地点名这些键（契约自己的注释就是这么写的），也必须扫不出来
        assertTrue(defaultNameLiteralsIn(" * {@code " + name + "} 一直抄的是字面量").isEmpty(),
                "javadoc 里点名被误判成字面量，探测器口径太宽");
        // 反向：执行器的声明行必须被豁免规则认出来，否则 7 行声明会被当成 7 条违规
        assertTrue(CONSTANT_DECLARATION.matcher(
                        "    public static final String DEFAULT_APPROVE_VAR = " + quoted + ";").find(),
                "豁免规则认不出执行器的常量声明行");
    }

    /**
     * src/main/java 下全部 .java 文件
     *
     * <p>带一条<b>数量下限</b>的自检：口径写错（比如工作目录不是模块根）时 {@code Files.walk} 会抛异常，
     * 但过滤条件写窄时只会静少扫 —— 少扫的强制点看着一样绿。下限取 100，远低于本模块真实文件数，
     * 只在口径整体坏掉时才红。
     */
    private static List<Path> mainSources() throws IOException {
        Path root = Paths.get("src", "main", "java");
        assertTrue(Files.isDirectory(root),
                "找不到 " + root.toAbsolutePath() + "（工作目录应是模块根，与 AgentArchitectureTest 同一口径）");
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
            assertTrue(files.size() >= 100,
                    "只扫到 " + files.size() + " 个源文件，少于 100，扫描口径坏了（别把这条测试删掉了事）");
            return files;
        }
    }

    private static List<String> defaultNameLiteralsIn(String src) {
        return registeredDefaultNames().stream()
                .map(name -> "\"" + name + "\"")
                .filter(src::contains)
                .toList();
    }

    /**
     * 契约里全部「有默认名」的输出端口的默认名 —— <b>域从契约现场派生，不手抄</b>
     *
     * <p>这样新增一个带默认名的端口时，{@link #mainSourcesHaveNoDefaultNameLiterals} 的扫描域
     * 自动跟着变大；而 {@link #defaultNamesReferenceExecutorConstants} 末尾那条数量断言会红，
     * 逼着回来给它补一条点名断言。两者一松一紧，正好覆盖「域变大」这件事的两个后果。
     *
     * <p>🔴 C8 之后<b>直接委托</b> {@link NodeIoContract#defaultOutputNames()}，不再自己写一遍
     * {@code all() → outputs() → defaultName() → 过滤空白} 那套流：本文件原先那份与生产代码
     * 是<b>两份同逻辑的实现</b>，而生产那一份现在同时喂着 {@code KeyStrategyFactoryGenerator} 的注册
     * 与 {@code WorkflowDslValidator} 的豁免。两份逻辑一旦分岔（比如生产侧多加一个过滤条件），
     * 本文件的扫描域会与真实注册集<b>静默不同</b> —— 扫得比实际宽只是多几条误报，
     * 扫得比实际窄就是漏掉一个读者的强制点。
     *
     * <p>⚠️ 排序是为了 {@link #literalDetectorIsNotVacuous} 取 {@code get(0)} 时结果稳定；
     * 生产方法刻意保留契约声明序（它那两个消费者逐个处理，将来拼文案不必自己再排）。
     */
    private static List<String> registeredDefaultNames() {
        return NodeIoContract.defaultOutputNames().stream().sorted().toList();
    }

    private static String defaultNameOf(String type) {
        return NodeIoContract.of(type).outputs().get(0).defaultName();
    }

    /** 多输出端口的类型（{@code hitl.bpm_approval} / {@code knowledge_retrieval}）要按键取，不能一律 get(0) */
    private static String defaultNameOf(String type, String configKey) {
        return NodeIoContract.of(type).outputs().stream()
                .filter(p -> configKey.equals(p.configKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(type + " 没有输出端口 " + configKey))
                .defaultName();
    }

    @Test
    @DisplayName("纯控制流节点（parallel/condition.rule/iteration/wait/sub_workflow）无数据输出端口")
    void controlFlowNodesHaveNoDataOutput() {
        for (String type : List.of("parallel", "condition.rule", "iteration", "wait", "sub_workflow")) {
            assertTrue(NodeIoContract.of(type).outputs().isEmpty(),
                    type + " 是控制流/无数据产出节点，outputs 应为空");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
