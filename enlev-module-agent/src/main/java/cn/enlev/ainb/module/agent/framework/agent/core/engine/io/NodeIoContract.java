package cn.enlev.ainb.module.agent.framework.agent.core.engine.io;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlBpmApprovalNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlConfirmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.KnowledgeRetrievalNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.LlmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ScriptNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ToolNodeExecutor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 节点「输入 / 输出端口」契约登记表。
 *
 * <h3>它是什么、不是什么</h3>
 * 这是一层<b>只描述、不执行</b>的元数据：告诉编排者「某类节点该往哪个 config 键填什么（输入端口）、
 * 跑完会往哪个变量名写什么（输出端口）、类型是什么」。前端据此画出「类型化端口」、让用户在下游输入框里
 * 「点选上游产出」而不是手打 <code>{{$.xxx}}</code>，从而非技术人员也能快速把前序节点的输出接到后序节点的输入。
 *
 * <p>🔴 关键纪律：本登记表<b>绝不参与运行时</b>。节点仍按各自 Executor 从 DSL 的 config / variables 里读写
 * 扁平状态；这里描述的「端口」最终都被编译/填写成既有的 <code>{{$.var}}</code> 引用与 <code>config.outputVar</code>。
 * 因此对存量已发布流程 100% 向后兼容（DSL DTO 也 ignoreUnknown，附加字段不影响解析）。
 *
 * <p>键名逐字对齐前端 {@code nodeConfigSchema.ts} / {@code nodeSummary.ts} 与各 NodeExecutor 实际读取的键，不臆造。
 * 数据类型（dataType）仅用于 UI 着色/提示与「弱类型」可达性提醒，<b>非</b>运行期强类型约束。
 *
 * <h3>🔴 输出端口的默认名一律引用执行器的常量，不在这里抄字面量</h3>
 * 抄的那份会漂，而且已经漂过：{@code tool}/{@code llm} 的 {@code defaultName} 曾写着 null（= 必须用户命名），
 * 而两个执行器一直在用 {@code tool_result}/{@code llm_output} 兜底。于是所有「读契约做分析」的代码都看不见
 * 这两个键——{@code WorkflowDslValidator} 建 producers 表时漏掉它们，合法的 {@code {{$.tool_result}}} 被误报成
 * 悬空引用；污点溯源同理漏报。引用常量后，改执行器的默认名会连带改契约，编译器保证两者不会分家。
 *
 * <p>这<b>不</b>违反「绝不参与运行时」：那几个常量是 {@code static final String} 字面量，属编译期常量表达式，
 * javac 会把值内联进本类的常量池（JLS 15.28 / 13.1），本类字节码里不留对执行器类的任何引用
 * （已用 {@code javap -c} 核实：本类字节码 0 处 {@code NodeExecutor} 引用，七个字面量直接躺在 {@code <clinit>} 里）。
 *
 * <p>🔴 这条纪律曾被自己违反过：三个 hitl 回调键（{@code hitl_approved}/{@code bpm_approved}/{@code bpm_status}）
 * 一直抄的是字面量，而同样这三个键在 {@code WorkflowDslValidator.RESUME_REGISTERED_VARS} 与
 * {@code KeyStrategyFactoryGenerator} 里引的都是执行器常量 —— 两处都写明了理由（回调方在审批线程上发布事件、
 * 拿不到节点 config，只能写死默认名，故默认名一改，豁免与注册必须跟着改）。契约是第三个读者，没跟上；
 * 第四个读者 {@code AgentAssistantServiceImpl.HITL_APPROVE_VAR}（聊天内恢复）同样抄着字面量，
 * 靠一句「必须与执行器一致」的注释当链接，也已改成引用。
 *
 * <p>强制点是 {@code NodeIoContractTest#mainSourcesHaveNoDefaultNameLiterals}，它扫 src/main <b>全部</b>源码
 * 而不只是本文件：只扫契约的话，第四个读者改完就没有强制点了，谁把它改回同值字面量全套测试照样绿 ——
 * 而那一处的后果最重（写错键 = 用户点了同意却按拒绝走完、执行状态还报 SUCCEEDED）。
 * 之所以必须扫源码：值比对在引用常量之后会退化成「常量等于常量」的<b>恒真</b>断言，
 * 拦不住「有人把常量引用改回同值字面量」。
 *
 * <h3>⚠️ 对齐是部分的：这份契约只登记「给人当产出引用」的键</h3>
 * 运行期还写了一批内部键，刻意不登记，因为它们是路由/审计用的，不该出现在下游的引用选择器里：
 * 条件节点的 {@code __route_<id>}、人工节点的 {@code <id>_confirmed} 等、等待节点的 {@code <id>_waited} 等。
 * 另外三个<b>没有 NodeExecutor</b> 的类型（{@code agent_loop}/{@code iteration}/{@code sub_workflow}）由编译器合成子图，
 * 它们的实读实写键在各自的 GraphBuilder / 编译器里。
 *
 * <p>🔴 入端口曾<b>少登记 13 个运行期实读键</b>（台账 #97，分布在 5 种类型上），现已全部补齐。
 * 这一段原先写的是「本契约只覆盖用户在属性面板上填的那几个」，并举 {@code agent_loop} 的 {@code goal}
 * 死键为例 —— 那句在 #41 之后陈了两处：① 运行期 {@code goal} 与 {@code prompt} <b>两个都读</b>
 * （{@code AgentLoopGraphBuilder#resolveSpec}：goal 优先、prompt 兜底存量 DSL），不再是「只读 prompt」；
 * ② 更要紧的是「只覆盖面板上填的那几个」正是漏登记的<b>成因</b>、不是它的<b>边界</b>——
 * {@code modelId}/{@code temperature}/{@code maxTokens} 这类键面板上没有格子、运行期却真的生效，
 * 作者既改不动、也不知道能改（这正是 #97 把 {@code agent_loop} 那 7 个定成<b>错答</b>而非少承诺的理由）。
 * ⟹ 登记判据改成「<b>运行期读了就得登记</b>」，与面板上有没有格子无关。
 *
 * <p>⚠️ 补齐之后有一个连带后果要知道：本契约的<b>输出</b>端口同时是
 * {@code WorkflowDslValidator#validateWrittenVarsDeclared}（V19「写而未声明」）与
 * {@code UntrustedInputTracer} 的派生源，故给某类型新增一个 {@code OutPort} 就等于<b>打开</b>这两处对它的判定
 * （本次 {@code agent_loop.trajectoryVar} 即如此，属台账 #99 的域）。入端口没有这个效应
 * ——它们只影响 {@code mode}/{@code dataType}/{@code required} 三个反查结果与污点的读集。
 *
 * <p>🔴 「契约 ↔ 运行期实读实写键」的一致性，**强制点是分三档的，别一概而论**：
 * <ol>
 *   <li><b>实读键全集 —— 已有强制点</b>：{@code NodeIoContractRuntimeReadScanTest}（台账 #97-b）
 *       扫源码做<b>双向</b>差集，契约少登记一个运行期真在读的键、或多登记一个没人读的键，都会红。
 *       它刻意扫源码而不是跑执行器：键是字符串字面量，运行期只有配了那个键的 DSL 才走到读取分支。</li>
 *   <li><b>默认输出名 —— 只有 {@code tool} / {@code llm} 两个类型有强制点</b>：
 *       {@code NodeIoContractRuntimeAlignmentTest} 走「真跑执行器」比对实际写入键，
 *       其余类型跑不了的理由在它自己的 javadoc 里逐条列着。</li>
 *   <li><b>实写键全集（其余类型）—— 仍无强制点</b>，属 S9-①。这一档写错一个 {@code defaultName}，
 *       除 {@code NodeIoContractTest} 抽查到的那几条外不会有测试变红。</li>
 * </ol>
 *
 * @author 走进新时代
 */
public final class NodeIoContract {

    /** 端口可承载的数据类型（仅用于 UI 展示与提示，不做运行期强校验） */
    public static final String T_STRING = "string";
    public static final String T_NUMBER = "number";
    public static final String T_BOOLEAN = "boolean";
    public static final String T_OBJECT = "object";
    public static final String T_ARRAY = "array";

    /**
     * 输入端口的填写模式：literal=只填字面量，reference=只引用上游变量，both=两者皆可
     *
     * <p>🔴 这里的「上游变量」一律指<b>本流程（sub_workflow 场景下是父流程）的 state</b>，
     * 两个消费者都按这个含义用：前端 {@code referenceForm()} 据此决定要不要挂「引用上游变量」选择器，
     * {@code UntrustedInputTracer#accessOf} 据此决定「读它会不会让污点流动」（literal 直接跳过）。
     *
     * <p>⟹ 于是有一类键<b>只能</b>登记成 {@code MODE_LITERAL}，尽管它的值语法上就是个模板：
     * 值对<b>另一个作用域</b>的状态求值。目前只有两个，各自的原因写在端口的 hint 里 ——
     * {@code hitl.bpm_approval.formVars}（压根不过 {@code StateTemplateResolver}，原样进审批表单）与
     * {@code sub_workflow.outputMap}（过解析，但解析的是<b>子流程</b>的 state，写父流程的 {@code {{$.x}}} 取不到值）。
     * ⚠️ 三值词汇表表达不了「可解析但换个作用域」这件事，这是<b>已知的建模缺口</b>，不是笔误：
     * 真要表达得加第四个 mode，而两个消费者（前端选择器、污点跳过规则）对第四值的处置都与 literal 相同，
     * 加它只多一个要同步的枚举、不多一份信息。
     */
    public static final String MODE_LITERAL = "literal";
    public static final String MODE_REFERENCE = "reference";
    public static final String MODE_BOTH = "both";

    private NodeIoContract() {
    }

    /**
     * 一个输入端口：对应节点 config 里的一个键。
     *
     * @param key      config 键名（逐字对齐后端读取的键）
     * @param label    面向业务人员的中文名
     * @param dataType {@link #T_STRING} / number / boolean / object / array
     * @param mode     可填字面量 / 只可引用 / 皆可
     * @param required 是否必填
     * @param group    分组名（如「基础」「模型」），供 UI 折叠分组
     * @param hint     一句话说明（非技术用户看得懂）
     */
    public record InPort(String key, String label, String dataType, String mode,
                         boolean required, String group, String hint) {
    }

    /**
     * 一个输出端口：节点跑完把结果写进哪个变量名。
     *
     * @param configKey   存放「变量名」的 config 键（如 outputVar / approveVar）；null = 无配置键、恒用默认名
     * @param defaultName 未配置时的默认变量名（null = 必须由用户命名）
     * @param dataType    产出数据类型
     * @param label       面向业务人员的中文名
     */
    public record OutPort(String configKey, String defaultName, String dataType, String label) {
    }

    /**
     * 某节点类型的完整 I/O 契约。
     *
     * @param type    节点类型（与 NODE_TYPE_META / NodeExecutor 一致）
     * @param label   中文名
     * @param group   分组（基础 / 分支与并行 / 人工介入 / 高级）
     * @param inputs  输入端口
     * @param outputs 输出端口
     */
    public record Contract(String type, String label, String group,
                           List<InPort> inputs, List<OutPort> outputs) {
    }

    private static InPort in(String key, String label, String dataType, String mode,
                             boolean required, String group, String hint) {
        return new InPort(key, label, dataType, mode, required, group, hint);
    }

    private static OutPort out(String configKey, String defaultName, String dataType, String label) {
        return new OutPort(configKey, defaultName, dataType, label);
    }

    private static final Map<String, Contract> CONTRACTS = new LinkedHashMap<>();

    static {
        register(new Contract("tool", "工具", "基础",
                List.of(
                        in("toolId", "工具", T_STRING, MODE_LITERAL, true, "基础", "选择要调用的工具（键名）"),
                        // 🔴 编号纪律（缺陷 D4-06）：日后要给「iteration 循环体内含写工具」补幂等键，
                        //    落点是**这个 args 端口旁**——给 NodeIoContract 增一个 idempotencyKey
                        //    **声明位**，而不是再加一条校验器规则；且**编号从 V23 起**
                        //    （V21 已被「tool 节点的 args 不是对象」占掉，见 WorkflowDslValidator#validateToolArgsShape；
                        //    V22 已被「script 里调 tools.*」占掉，见 WorkflowDslValidator#validateScriptToolCalls）。
                        //    🔴 本处原先写的是「从 V22 起」，而 V22 后来真被占了 —— 权威口径在校验器的
                        //    V20 规则头（validateChatLegsWriteReply 的 javadoc），改号先改那里，别只改这一份副本。
                        //    迭代二「明确不做」里否掉的那条本来叫 V20（「循环体内含写工具且无幂等键 → warning」），
                        //    而 V20 这个号已经被迭代三的「会话腿没人写回复键」占掉了 —— 两份文档各指一个东西，
                        //    撞号从来没被发现过。口径与处置见 WorkflowDslValidator 的 V20 规则头（迭代四 §12.1）。
                        //    🔴 别与 AgentNodeExecutionDO 已有的 idempotencyKey 接错：那个是**重放去重**用的
                        //    （对应库里的 unique key），与这里要声明的「写工具幂等键」**不是一回事**。
                        //
                        // 🔴 「嵌套位置的 {{$.x}} 不替换」这件事，**三侧**的口径各不相同、而**三侧都对**（缺陷 E10）。
                        //    写成「两侧」正是 C6 那个缺陷的成因，故这里必须把三侧列全：
                        //      · **输入侧** ToolNodeExecutor#execute —— **不替换**。那个 forEach 只对「顶层的
                        //        String 值」调 StateTemplateResolver#resolve，非 String（含 Map/List）原样透传。
                        //        这是**事实、不是选择**：嵌套的占位符压根没进过解析器。
                        //      · **分析侧** UntrustedInputTracer#addTemplateReads —— **刻意不递归**（连 List 值都不看）。
                        //        它问的是「数据会不会真的流过去」，而嵌套占位符不会流动，递归进去扫就是
                        //        **报一条不会发生的流向**（该类自己的红线：误报和漏报一样坏）。
                        //      · **校验侧** WorkflowDslValidator#collectUnresolvableRoots —— 🔴 **确实递归**
                        //        （进 Map 也进 List）。它问的是**另一个问题**：「引用的根变量声明了没有」。
                        //        没声明的嵌套引用本身就是作者错误，与它会不会流动无关。
                        //    🔴 于是有一句推论是**错的**，C6 就是被它害的：「校验侧递归」≠「校验侧能判
                        //    『这一格会不会被替换』」。根变量声明了，递归那条就绿；而这一格照样不替换。
                        //    form_save_draft 的 fields[].column 那一格**可证明**不是任何真实列名
                        //    （V6 本来就在拿它比物理列），所以它判得死 —— 现成的例子与完整论证见
                        //    WorkflowDslValidator#validateSaveDraftColumnPlaceholders 的 javadoc，这里不重复一遍。
                        //    ⚠️ 输入侧因此**不设**通用强制点（裁定 N17）：任意深度、任意形状、值还可能是
                        //    运行期才展开的 Map ⟹ **静态不可判定**。🔴 理由必须是「判不了」，**不是**「写下来就够了」
                        //    —— 理由错，同型缺陷就会照着那个错理由再犯一次。
                        //    🔴 别处还有三处提到这件事，**各只覆盖一部分**，全口径以本段为准：
                        //      · UntrustedInputTracer#addTemplateReads 的 javadoc 已经把输入侧与分析侧讲对了，
                        //        但它对校验侧的描述是「它问『解析不到会不会静默失效』，嵌套的确实会」——
                        //        🔴 这句只在**根变量没声明**时成立。声明了它就绿，而这一格照样不替换。
                        //        读成「嵌套位置有校验器兜着」正是 C6 赖以一路绿到发布的那份假安心。
                        //      · NodeIoContractRuntimeAlignmentTest 里「别把它当成待修的 bug」是**禁令**、没给理由。
                        //      · WorkflowDslValidator#mainTableExists 那句「要到运行期才确定、体检期无从判定」
                        //        是**对的**，但它讲的是 mainTable —— 那是 args 的**顶层字符串值**，运行期真会替换。
                        //        🔴 别把那句挪来当 args 嵌套位置的挡箭牌，两格恰好相反。
                        in("args", "工具入参", T_OBJECT, MODE_BOTH, false, "基础",
                                "参数名 → 值；顶层的字符串值可用 {{$.变量}} 引用上游结果"
                                        + "（⚠️ 嵌在对象/数组里的不替换）")),
                List.of(out("outputVar", ToolNodeExecutor.DEFAULT_OUTPUT_VAR, T_OBJECT, "工具返回结果"))));

        register(new Contract("llm", "大模型", "基础",
                List.of(
                        in("prompt", "提示词", T_STRING, MODE_BOTH, true, "提示",
                                "可用 {{$.变量}} 把上游结果拼进提示词"),
                        in("systemPrompt", "系统提示词", T_STRING, MODE_BOTH, false, "提示", "可空；设定模型角色"),
                        in("modelId", "模型", T_NUMBER, MODE_LITERAL, false, "模型", "留空=用默认模型"),
                        in("temperature", "温度", T_NUMBER, MODE_LITERAL, false, "模型", "越高越有创造力（0~2）"),
                        in("maxTokens", "最大 tokens", T_NUMBER, MODE_LITERAL, false, "模型", "限制单次输出长度")),
                List.of(out("outputVar", LlmNodeExecutor.DEFAULT_OUTPUT_VAR, T_STRING, "模型生成的文本"))));

        register(new Contract("knowledge_retrieval", "知识检索", "基础",
                List.of(
                        in("knowledgeBaseId", "知识库", T_NUMBER, MODE_LITERAL, true, "基础", "选择要检索的知识库"),
                        in("query", "查询内容", T_STRING, MODE_BOTH, false, "查询",
                                "检索问题；可用 {{$.变量}}。与「查询变量」二选一"),
                        in("queryVar", "查询变量", T_STRING, MODE_REFERENCE, false, "查询",
                                "直接取某个变量的值当查询（优先于查询内容）"),
                        in("topK", "召回条数", T_NUMBER, MODE_LITERAL, false, "高级", "取最相关的 N 段"),
                        in("similarityThreshold", "相似度阈值", T_NUMBER, MODE_LITERAL, false, "高级", "0~1，越高越严")),
                List.of(
                        out("outputVar", KnowledgeRetrievalNodeExecutor.DEFAULT_OUTPUT_VAR, T_ARRAY, "检索到的资料段落"),
                        out("citationsVar", null, T_ARRAY, "引用出处（可选，填后聊天可展示来源）"))));

        register(new Contract("script", "脚本", "基础",
                List.of(
                        in("code", "脚本内容", T_STRING, MODE_LITERAL, true, "基础",
                                "JS 代码，用 state.变量名 读上游结果，return 出结果"),
                        in("language", "语言", T_STRING, MODE_LITERAL, false, "高级", "目前仅支持 javascript"),
                        in("timeoutMs", "超时（毫秒）", T_NUMBER, MODE_LITERAL, false, "高级", "缺省 2000")),
                List.of(out("outputVar", ScriptNodeExecutor.DEFAULT_OUTPUT_VAR, T_OBJECT, "脚本返回值"))));

        register(new Contract("condition.rule", "规则条件", "分支与并行",
                List.of(
                        in("branches", "分支规则", T_ARRAY, MODE_LITERAL, true, "分支",
                                "按变量值分组；命中哪组就走哪条线"
                                        + "（⚠️ 比较值按字面量处理，{{$.变量}} 不替换；变量名只认顶层键）"),
                        in("default", "默认分支", T_STRING, MODE_LITERAL, false, "分支", "都不命中时走哪条")),
                List.of()));

        register(new Contract("condition.llm", "LLM 条件", "分支与并行",
                List.of(
                        in("prompt", "判定提示词", T_STRING, MODE_BOTH, true, "基础",
                                "可用 {{$.变量}}；让模型据此判断走哪个分支"),
                        in("choices", "候选分支", T_ARRAY, MODE_LITERAL, true, "基础", "模型只能从这些候选里选一个"),
                        in("default", "默认分支", T_STRING, MODE_LITERAL, false, "基础", "都没命中时的兜底"),
                        in("modelId", "模型", T_NUMBER, MODE_LITERAL, false, "模型", "留空=用默认模型"),
                        // 🔴 这两个曾漏登记（台账 #97）：运行期 LlmConditionNodeExecutor 真的把它们塞进请求，
                        //    而前端 schema 早就有这两个格子 ⟹ 用户在面板上改了、生效了，契约却看不见它们，
                        //    于是 isRequiredField/referenceForm/fieldDataType 三个反查一律拿到兜底值。
                        in("temperature", "温度", T_NUMBER, MODE_LITERAL, false, "模型", "越高越有创造力（0~2）"),
                        in("maxTokens", "最大 tokens", T_NUMBER, MODE_LITERAL, false, "模型",
                                "限制单次输出长度；⚠️ 缺省 32 —— 判定只需吐一个分支名，故比 llm 节点（缺省不限）小得多")),
                List.of(out("outputVar", null, T_STRING, "模型选中的分支"))));

        register(new Contract("parallel", "并行", "分支与并行",
                List.of(
                        in("join", "汇聚方式", T_STRING, MODE_LITERAL, false, "基础",
                                "当前仅支持「全部完成后汇聚」（填别的值编译期就拒，不静默降级）"),
                        // 🔴 与 condition.* 的 branches <b>同名不同义</b>：那边是「分支判定规则」的对象数组
                        //    （{target, conditions[]}，target 是路由键），这边是「节点 id 链」的数组的数组。
                        //    故 hint 必须分开写、不能共用一句（台账 #97 裁定②）。
                        //    ⚠️ 不改键名：两处运行期读者读的都是 branches 这个字面量，改名要么破坏存量已发布 DSL、
                        //    要么得再加一层双读兼容（就是 agent_loop 的 goal/prompt 现在这副样子）。
                        in("branches", "并行分支", T_ARRAY, MODE_LITERAL, true, "基础",
                                "每个元素是一条「节点 id 链」，即数组的数组（如 [[\"a\",\"b\"],[\"c\"]]）；"
                                        + "⚠️ 与条件节点的「分支规则」同名不同义 —— 这里填节点 id，不是判定规则。"
                                        + "分支节点的连边由引擎生成，不要手写进 edges[]")),
                List.of()));

        register(new Contract("iteration", "循环遍历", "分支与并行",
                List.of(
                        in("itemsVar", "要遍历的列表", T_STRING, MODE_REFERENCE, true, "基础", "一个数组变量名"),
                        // 🔴 required 原是 false，而运行期 IterationGraphBuilder 缺它<b>直接编译失败</b>
                        //    （与 itemsVar 同款 fail-fast）。后端 src/main 里<b>零个</b> "item" 兜底字面量 ——
                        //    ⚠️ 但前端有一个：editorModel.defaultConfig('iteration') 把 itemVar 预填成 "item"。
                        //    所以设计器拖出来的节点不会编译失败，而 AI 生成的 / 手写的 DSL 拿不到这个预填 ⟹
                        //    「没有缺省值」这句是对<b>后端</b>说的，前端 schema 的 placeholder 已改成「拖出来时已预填 item」。
                        //
                        //    🔴 同一条错话原有<b>四份副本</b>（本端口的 hint、前端 schema 的 hint、前端契约镜像的 hint、
                        //    UntrustedInputTracer 里那句注释），外加两处配套错值（本端口的 required=false、
                        //    前端 schema 的 placeholder「缺省 item」）：hint 教作者引用一个<b>字面名叫 itemVar 的键</b>，
                        //    而当前项其实写在作者自己填的那个名字下（预填 item 时才该写 {{$.item}}）——
                        //    照 hint 写必然取到空值，且<b>不报错</b>（模板解析不出来就是空串）。
                        //    四份副本已全部改掉；「改回去会不会红」的强制点在前端 nodeConfigSchema.spec.ts
                        //    那条 not.toContain 上（后端这侧的 hint 没有消费者，钉它没有意义）。
                        //    ⚠️ mode 仍是 LITERAL 而不是 REFERENCE：这一格是给新变量<b>起名</b>（写入目标），
                        //    不是「取哪个上游变量」（读取来源），挂引用选择器只会让用户挑一个不相干的上游名。
                        in("itemVar", "每项存到", T_STRING, MODE_LITERAL, true, "基础",
                                "给每轮取出的那一项起个变量名（必填，没有缺省值）；循环体节点用 {{$.你填的这个名}} 取当前项"),
                        in("body", "循环体节点", T_ARRAY, MODE_LITERAL, true, "高级",
                                "参与循环的节点 id；不要把它们连进主 edges"),
                        in("concurrency", "并发度", T_NUMBER, MODE_LITERAL, false, "高级",
                                "⚠️ 暂只支持 1（串行遍历）；填 >1 编译期明确拒绝、不静默降成串行"
                                        + "（并发 foreach 需运行时按列表长度动态 fan-out，而引擎的并行分支是编译期静态的）")),
                List.of()));

        register(new Contract("hitl.confirm", "人工确认", "人工介入",
                List.of(),
                List.of(out("approveVar", HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR,
                        T_BOOLEAN, "用户是否同意"))));

        register(new Contract("hitl.bpm_approval", "BPM 审批", "人工介入",
                List.of(
                        in("bpmProcessDefKey", "BPM 流程标识", T_STRING, MODE_LITERAL, true, "基础", "指向已部署的审批流程"),
                        // 🔴 MODE_LITERAL 而不是 BOTH：这些值压根不过 StateTemplateResolver。
                        //    AgentBpmApprovalService.resolveConfig 用 node.configMap("formVars") 原样取，
                        //    createApproval 再原样 setVariables 交给 Flowable，于是 {{$.变量}} 会以字面串进审批表单。
                        //    标成 BOTH 的代价不止「提示骗人」：前端据 mode 给这个**对象**字段挂上「引用上游变量」
                        //    选择器，而选择器 emit 的是字符串、父级 onPickRef 直接整体赋值 ⟹ formVars 被换成一个串
                        //    ⟹ configMap 返回 null ⟹ 用户配的变量全丢，且全程无提示。
                        //    真要让它可引用，得在挂起点从 checkpoint 取出状态再解析——那是运行期改动，不是改这份契约。
                        in("formVars", "审批表单参数", T_OBJECT, MODE_LITERAL, false, "表单",
                                "发起审批时传的变量；⚠️ 值按字面量原样传给审批流程，{{$.变量}} 不会被替换"),
                        // S6：挂起发起审批时，把变更清单冻结进流程变量 agent_change_manifest（载体是对象，不是 JSON 串）。
                        // 默认 true = 不填也注入；false = 刻意不注入（例如纯审批、与变更无关的流程）。
                        in("injectManifest", "注入变更清单", T_BOOLEAN, MODE_LITERAL, false, "审批",
                                "挂起发起审批时，把这份流程「将要改什么」的变更清单冻结进流程变量 agent_change_manifest，"
                                        + "审批人在 BPM 详情页能看到。默认开启")),
                List.of(
                        out("approveVar", HitlBpmApprovalNodeExecutor.DEFAULT_APPROVE_VAR,
                                T_BOOLEAN, "审批是否通过"),
                        out("statusVar", HitlBpmApprovalNodeExecutor.DEFAULT_STATUS_VAR,
                                T_NUMBER, "审批状态码（2 通过/3 驳回/4 取消）"))));

        register(new Contract("wait", "等待", "人工介入",
                List.of(
                        in("waitType", "等待方式", T_STRING, MODE_LITERAL, true, "基础", "定时等待或等外部事件"),
                        in("duration", "等待时长", T_STRING, MODE_LITERAL, false, "基础", "定时方式的时长（秒或 ISO-8601）"),
                        in("eventKey", "等待的事件", T_STRING, MODE_LITERAL, false, "基础", "等外部事件时的键"),
                        // S6：V14（无头流程体检）读的纯声明字段。运行期不读它——它只回答「谁会来发这个事件」，
                        // 让校验器判断「唤不唤得醒」，不参与执行。登记进契约是给前端面板一个能填的格子。
                        in("externalWakeup", "外部唤醒方声明", T_OBJECT, MODE_LITERAL, false, "基础",
                                "等外部事件时声明谁会来唤醒：{ owner, endpoint, note }。无头流程体检（V14）读它判断"
                                        + "这个等待节点唤不唤得醒")),
                List.of()));

        register(new Contract("agent_loop", "自主循环", "高级",
                List.of(
                        in("goal", "目标", T_STRING, MODE_BOTH, true, "基础",
                                "模型要自主完成什么；可用 {{$.变量}}。⚠️ 与「初始目标变量」二选一：两个都不填编译期就拒"),
                        // 🔴 prompt 是 goal 的<b>兼容别名</b>，运行期两个都读（goal 优先、prompt 兜底存量 DSL）。
                        //    登记它不是因为界面上有这个格子（前端 schema 只有 goal），而是因为「运行期读了就得登记」：
                        //    存量 DSL 里写 prompt 的流程真实存在，漏登记会让分析面看不见这条读边。
                        in("prompt", "目标（旧写法）", T_STRING, MODE_BOTH, false, "高级",
                                "goal 的兼容别名，两者都填时以 goal 为准；新流程请填「目标」，不要再写这个键"),
                        // 🔴 label 逐字是「循环目标」：UntrustedInputTracer 把它当汇的标签用，
                        //    而 UntrustedInputTracerTest 钉着这四个字（改成「初始目标变量」那条测试会红）。
                        in("inputVar", "循环目标", T_STRING, MODE_REFERENCE, false, "基础",
                                "「目标」留空时，直接取这个变量的值当初始调查目标（原样进对话历史的第一条 user 消息）"),
                        in("tools", "可用只读工具", T_ARRAY, MODE_LITERAL, false, "基础", "只能放只读（查询类）工具"),
                        in("maxSteps", "最大步数", T_NUMBER, MODE_LITERAL, false, "高级", "防止无限循环，缺省 5"),
                        in("modelId", "模型", T_NUMBER, MODE_LITERAL, false, "模型", "留空=用默认模型"),
                        in("temperature", "温度", T_NUMBER, MODE_LITERAL, false, "模型", "越高越有创造力（0~2）"),
                        in("maxTokens", "最大 tokens", T_NUMBER, MODE_LITERAL, false, "模型", "限制单步输出长度"),
                        // 🔴 MODE_LITERAL 而不是 BOTH，理由与 llm.systemPrompt <b>相反</b>：同名不同义。
                        //    LlmNodeExecutor 对 systemPrompt 走 StateTemplateResolver，而 AgentLoopGraphBuilder
                        //    把它原样拼在通用 ReAct 协议后面（「## 本次任务的补充说明」）—— 中间没有任何解析。
                        //    标成 BOTH 的代价是作者写 {{$.变量}} 只会把字面串喂给模型，且全程无提示（与 formVars 同型）。
                        in("systemPrompt", "补充说明", T_STRING, MODE_LITERAL, false, "提示",
                                "追加在通用循环协议之后的任务说明；⚠️ 不过模板解析，{{$.变量}} 不会被替换"
                                        + "（与「大模型」节点的系统提示词同名不同义，那个会替换）")),
                List.of(
                        out("outputVar", null, T_STRING, "最终答案"),
                        // 🔴 登记成<b>输出</b>端口（它是「轨迹写进哪个变量名」，与 outputVar 同族），
                        //    连带把它接进了两个从 outputs() 派生的读者：校验器 V19「写而未声明」与污点传播的写集。
                        //    前者是台账 #99 的域 —— 在此之前 V19 结构性地看不见这个键，
                        //    于是「配了 trajectoryVar + 下游有挂起点 + 没在 variables[] 声明」的流程
                        //    能一路发布成功，而它的轨迹会在 resume 那一刻被 SAA 的 input() 过滤掉、静默丢空。
                        out("trajectoryVar", null, T_ARRAY, "调查轨迹（可选，不配就不输出）"))));

        register(new Contract("sub_workflow", "子流程", "高级",
                List.of(
                        in("workflowId", "子流程", T_STRING, MODE_LITERAL, true, "基础", "选择一条已发布的流程"),
                        in("version", "指定版本", T_NUMBER, MODE_LITERAL, false, "高级", "留空=用主版本"),
                        // 🔴 label 逐字是「子流程入参」：UntrustedInputTracer 用它当汇标签，且测试钉着这四个字。
                        in("inputMap", "子流程入参", T_OBJECT, MODE_BOTH, false, "高级",
                                "{子流程里的变量名: 父流程取值表达式}；值可用 {{$.父流程变量}}，"
                                        + "整个值就是一个占位符时保原始类型（数组/对象/数字不被转成字符串）"),
                        // 🔴 MODE_LITERAL 是<b>刻意的</b>，且与 inputMap 相反：outputMap 的值同样是模板、同样过解析，
                        //    但解析的是<b>子流程跑完之后的 state</b>，写 {{$.父流程变量}} 取不到值。
                        //    三值词汇表表达不了「可解析但换作用域」（见 MODE_LITERAL 的 javadoc），
                        //    而两个消费者对 LITERAL 的处置恰好都对：前端不挂「引用上游变量」选择器（挂了必然填错），
                        //    污点溯源不把它当父流程的汇（子流程结果写回父流程自己的 state，没跨信任边界，报出来只是噪音）。
                        in("outputMap", "子流程出参", T_OBJECT, MODE_LITERAL, false, "高级",
                                "{父流程变量名: 子流程结果表达式}；⚠️ 这里的 {{$.x}} 指的是<b>子流程里</b>的 x，"
                                        + "不能用来引用父流程的上游结果")),
                List.of()));
    }

    private static void register(Contract c) {
        CONTRACTS.put(c.type(), c);
    }

    /** 全部节点类型的 I/O 契约（保持声明顺序，与前端目录顺序一致） */
    public static List<Contract> all() {
        return List.copyOf(CONTRACTS.values());
    }

    /** 取某类型的契约；未知类型返回 null */
    public static Contract of(String type) {
        return type == null ? null : CONTRACTS.get(type);
    }

    /** 已登记的全部类型（与 13 种合法节点类型一致） */
    public static Set<String> types() {
        return Set.copyOf(CONTRACTS.keySet());
    }

    /**
     * 全部<b>非空</b>的输出端口默认名（去重、按契约声明顺序）。
     *
     * <p>🔴 这是「哪些键不经声明也能活过 resume」的<b>唯一口径</b>，两个消费者都从它派生，别各自列一遍：
     * <ol>
     *   <li>{@code KeyStrategyFactoryGenerator#generate} —— 逐个 {@code addStrategy(REPLACE)}。
     *       不注册的话，resume 时 {@code OverAllState.input(checkpointState)} 会把它过滤掉。</li>
     *   <li>{@code WorkflowDslValidator.RESUME_REGISTERED_VARS} —— V19「写而未声明」对它们一律不报。
     *       第 1 条一注册，这些键就真能活下来，再报 error 就是把不存在的危害说成存在的。</li>
     * </ol>
     *
     * <p>两处必须同源的理由不是「省一次复制粘贴」：它们的<b>差集就是缺陷</b>。
     * 注册了却不豁免 ⟹ V19 恒报假警报，作者照文案把这一格删掉、把流程改坏；
     * 豁免了却没注册 ⟹ 值在恢复瞬间被静默丢弃而校验器一路绿灯（这正是 C8 要修的原始形态：
     * 三个 hitl 回调键<b>两处都硬编码了同样三个名字</b>，靠人肉同步）。
     *
     * <p>⚠️ 只收<b>非空</b>的 {@code defaultName}：{@code condition.llm.outputVar}、
     * {@code agent_loop} 的两个端口、{@code knowledge_retrieval.citationsVar} 是 null
     * （= 必须由作者命名），它们没有「默认名」这回事，注册什么键都无从谈起。
     * 那几个端口填了纯空白的处置见 {@code WorkflowDslValidator#validateWrittenVarsDeclared} 里的说明。
     *
     * <p>🔴 本方法的域是<b>契约自己派生</b>的（{@code all()} → {@code outputs()} → {@code defaultName()}），
     * 所以给某类型新增一个带默认名的输出端口，注册与豁免会<b>自动</b>跟着覆盖 —— 不需要再回来改任何清单。
     * 代价是：契约里写错一个 {@code defaultName}，会连带让校验器对一个真实会丢的键保持沉默。
     * 那一档的强制点是 {@code NodeIoContractRuntimeAlignmentTest}（目前只覆盖 tool / llm 两类，见类头第 3 条）。
     */
    public static Set<String> defaultOutputNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Contract contract : CONTRACTS.values()) {
            for (OutPort port : contract.outputs()) {
                if (port.defaultName() != null && !port.defaultName().isBlank()) {
                    names.add(port.defaultName());
                }
            }
        }
        // 不用 Set.copyOf：它不保证迭代序，而这里刻意留一份「按契约声明顺序」的确定序
        // —— 两个消费者都是逐个处理，将来谁拿它拼文案（如「已豁免的键：a/b/c」）不必自己再排序。
        return Collections.unmodifiableSet(names);
    }
}
