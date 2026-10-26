package cn.enlev.ainb.module.agent.framework.agent.core.engine.io;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约 ↔ 运行期实读键的<b>双向</b>差集扫描（台账 #97-b 的强制点）
 *
 * <h3>它补的是哪条缝</h3>
 * 台账 #97 量出 {@link NodeIoContract} 少登记了 <b>13 个运行期真的在读的 config 键</b>（分布在 5 种类型上），
 * #97-a 把它们补齐了。但补齐只是把差集<b>清零一次</b>，没有任何东西阻止它再长出来：
 * 下次有人给某个执行器加一个 {@code node.configStr("newKey")}，契约不会跟着动，
 * 而所有「读契约做分析」的消费者（{@code WorkflowDslValidator} 的 producers 表、{@code UntrustedInputTracer}
 * 的读集、前端端口渲染）都继续看不见这个键 —— 表现是<b>合法的引用被误报成悬空</b>、或<b>污点漏报</b>，
 * 两边都不报错。本类就是把「差集为零」变成持续成立的强制点。
 *
 * <h3>为什么必须扫源码，而不是靠反射或跑一遍</h3>
 * 键是<b>字符串字面量</b>，运行期只有配了那个键的 DSL 才会走到读取分支；
 * 反射看得到方法签名、看不到 {@code configStr("outputVar")} 里那个字符串。
 * 已有的 {@code NodeIoContractRuntimeAlignmentTest} 走的是「真跑执行器」，
 * 但它只跑得动 {@code tool}/{@code llm} 两个类型（其余 11 种跑不了的理由在它自己的 javadoc 里逐条列着），
 * 而且它证的是「默认输出名」那一半，不是「实读键全集」这一半。
 *
 * <h3>🔴 域：五块，每一块都是量出来的、且各带一条自检</h3>
 * 「扫描域没列全」是本模块反复撞上的病（台账 #102、#83 §0.8.4.1③，
 * 以及 #97 <b>第一次测量自己</b>那节「三类漏网风险」）。缺任何一块，整套断言就退化成「看起来很好的 0」：
 * <ol>
 *   <li><b>文件域</b>：{@link #RUNTIME_DIRS} 三个目录下的 .java，<b>减去</b>契约自己（它是权威、不是读者）。
 *       自检：文件数下限 + 「有读取点的文件#方法」必须与归属映射的键集合相等
 *       （{@link #readerAttributionIsComplete}）。</li>
 *   <li><b>读法域</b>：五种形态 —— A {@code .configXxx("k")}、A' {@code .configXxx(非字面量)}、
 *       B {@code getConfig().get("k")}、C {@code getConfig().get(形参)}（按调用点位置解出字面量）、
 *       D 局部变量间接 {@code Map c = node.getConfig(); c.get("k")}、
 *       E 键以形参到达某个 Map 助手 {@code str(config, "k")}。
 *       家族成员从 {@code NodeDTO} 源码<b>现场派生</b>（{@link #configAccessorFamily()}），不手抄 ——
 *       🔴 #97 第一次测量正是手抄错了家族成员（把 {@code configKey} 当成第 7 个成员、漏了 {@code configBool}），
 *       而<b>个数凑巧也是 7</b>，于是没人发现。</li>
 *   <li><b>归属域</b>：读取点 → 节点类型。执行器那一半<b>派生</b>自类自己的 {@code TYPE} 常量；
 *       非执行器读者（编译器 / 两个 GraphBuilder / 两个 service）用<b>显式的「文件#方法 → 类型」映射</b>，
 *       且映射的完备性与「无僵尸条目」都被自检。</li>
 *   <li><b>未识别形态域</b>：{@link #noiseSweep} 把三个目录里<b>每一个</b>「小写驼峰形状」的字符串字面量都抓出来，
 *       凡不在已识别读取点上的，必须落进 {@link #NOISE_CALLEES}（按被调方法名认领，带理由）。
 *       🔴 这一条是被「第五种形态」逼出来的：#97-b 测量时在 {@code WorkflowTriggerExtractor} 撞到
 *       私有助手 {@code str(config, "formCode")}，四种正则一个都没抓到 ——
 *       <b>正向差集的假绿恰恰来自漏形态</b>（反向漏形态只会产生假红，而假红看得见）。</li>
 *   <li><b>同名不同命名空间域</b>：{@link #NON_NODE_CONFIG_KEY_LITERALS} 与 {@link #KEY_PARAM_HELPERS} ——
 *       触发器的 {@code config} 与节点的 {@code config} 是两个 map，
 *       而 {@code inputVar}/{@code outputVar} 两边<b>同名不同义</b>
 *       （与 {@code parallel.branches} vs {@code condition.*.branches} 同型，见 #97 裁定②）。</li>
 * </ol>
 *
 * <h3>⚠️ 本类<b>不</b>覆盖的（照 §0.8.4.1③「『真跑』也有域」写明，别让读者以为它管得更多）</h3>
 * <ul>
 *   <li><b>不含分析面</b>：{@code validate/} 与 {@code manifest/} 读同一批键（实测 18 处），
 *       但它们读错只会让报告不准、不会让流程跑错 ⟹ 混进来会让差集塞满噪声。
 *       ⚠️ 代价是「分析面读了一个契约没登记的键」本类看不见（例如 {@code WorkflowDslValidator} 读的
 *       {@code externalWakeup} 今天就不在契约里）—— 那属 S9-②。</li>
 *   <li><b>不含 src/test</b>：测试的职责相反，必须钉字面量，这样改常量的<b>值</b>才会红
 *       （与 #98 那条 {@code mainSourcesHaveNoDefaultNameLiterals} 同一取舍，两侧一松一紧）。</li>
 *   <li><b>只量「读」不量「写」</b>：运行期写进 state 的键（{@code __route_<id>}、{@code <id>_confirmed}、
 *       {@code sub_workflow.outputMap} 的目标名…）由 {@code NodeIoContractRuntimeAlignmentTest} 与各自的
 *       执行器测试守；契约刻意不登记其中的路由/审计键（见 {@link NodeIoContract} 类注释）。</li>
 *   <li><b>只保证「键被登记」</b>，不保证 label/hint/mode/dataType 对：那四个字段没有运行期读者可比。
 *       前端镜像那侧由 {@code _evidence/p97-mirror-diff.py} 做结构比对（一次性取证，尚未进 CI）。</li>
 *   <li><b>多类型归属的方法是松散处</b>：{@code computeRecursionLimit} 一个方法里读三种类型的键，
 *       它的读取点只能要求「被并集里某一个类型登记」。这一处被 {@link #weakTierIsBoundedToOneMethod}
 *       点名钉住，不许悄悄变多。</li>
 * </ul>
 *
 * @author 走进新时代
 */
@DisplayName("agent 契约 ↔ 运行期实读键：双向差集扫描")
class NodeIoContractRuntimeReadScanTest {

    // ==================== 域 ====================

    /** 与 {@code NodeIoContractTest#mainSources()} 同一口径：surefire 的工作目录就是模块根 */
    private static final Path AGENT_ROOT =
            Paths.get("src", "main", "java", "cn", "enlev", "ainb", "module", "agent");

    /**
     * 「运行期」= 编译/执行流程时真的会跑的代码（实测 49 个 .java，减去契约自己 = 48）。
     *
     * <p>⚠️ 这三个目录里也住着<b>不是</b>节点配置读者的东西（触发器、DSL DTO、脚本沙箱、trace），
     * 它们靠 {@link #NOISE_CALLEES} 与 {@link #NON_NODE_CONFIG_KEY_LITERALS} 认领，<b>不靠缩小目录</b> ——
     * 缩小目录就等于把「将来有人在别处读节点配置」这件事变成盲区。
     */
    private static final List<String> RUNTIME_DIRS = List.of(
            "framework/agent/core/engine",
            "framework/agent/core/loop",
            "service/workflow");

    /** 契约自己是权威、不是读者；它里面全是键名字面量，扫进来就是 41 条假违规 */
    private static final String AUTHORITY_FILE = "framework/agent/core/engine/io/NodeIoContract.java";

    private static final String EXECUTOR_IMPL_PREFIX = "framework/agent/core/engine/executor/impl/";
    private static final String NODE_DTO_FILE = "framework/agent/core/engine/dsl/NodeDTO.java";
    private static final String COMPILER_FILE = "DslToStateGraphCompiler.java";

    // ==================== 归属映射（非执行器读者） ====================

    /**
     * 「文件名#方法名 → 节点类型」的显式映射（今天 16 条）。
     *
     * <h3>为什么执行器不在这里</h3>
     * 执行器的类型<b>派生</b>自它自己的 {@code TYPE} 常量（{@link #executorTypes()}）；
     * 抄一份就是第二份会漂的副本。剩下这 5 个文件没有 {@code TYPE} 可派生：
     * 编译器一个文件里合成 4 种类型的子图，两个 service 各只服务一种类型而从类名看不出来 ⟹ 只能显式写。
     *
     * <h3>为什么粒度是「方法」而不是「文件」</h3>
     * 文件级的话，往 {@code AgentWorkflowExecutionServiceImpl}（1000+ 行、只管 wait 的两处读取点）里
     * 加一个读别的类型键的方法，本类照样绿。方法级把「归属」变成一个必须显式做出的决定：
     * 新方法里出现读取点 → {@link #readerAttributionIsComplete} 红 → 作者必须回来写一行映射。
     *
     * <h3>⚠️ 键里必须带文件名，因为方法名会撞</h3>
     * {@code resolveWaitEventKey} 在 {@code DslToStateGraphCompiler} 与
     * {@code AgentWorkflowExecutionServiceImpl} 里<b>各有一个</b>，两者都读 {@code eventKey}。
     * 只按方法名建表的话两条会合成一条，而其中一个搬家时另一个会被误判成「已归属」。
     */
    private static final Map<String, Set<String>> METHOD_TYPES = Map.ofEntries(
            // ---- DslToStateGraphCompiler：一个文件里合成 4 种类型的子图 ----
            Map.entry(COMPILER_FILE + "#expandParallel", Set.of("parallel")),
            Map.entry(COMPILER_FILE + "#collectIterationBodyIds", Set.of("iteration")),
            Map.entry(COMPILER_FILE + "#resolveIterationBodyNodes", Set.of("iteration")),
            Map.entry(COMPILER_FILE + "#addSubWorkflowNode", Set.of("sub_workflow")),
            Map.entry(COMPILER_FILE + "#validateMapping", Set.of("sub_workflow")),
            Map.entry(COMPILER_FILE + "#validateWaitConfig", Set.of("wait")),
            Map.entry(COMPILER_FILE + "#resolveWaitDurationSeconds", Set.of("wait")),
            Map.entry(COMPILER_FILE + "#resolveWaitEventKey", Set.of("wait")),
            // 🔴 唯一一个「一个方法读多种类型的键」的地方：它按 node.getType() 分三支算 recursionLimit 预算。
            //    ⟹ 它的读取点只能归属到三个类型的<b>并集</b>，于是这三种类型拿不到「精确相等」那半保证
            //    （见 weakTier() / multiTypeMethods()，被 weakTierIsBoundedToOneMethod 钉住）。
            //    想收紧就得把这段按类型拆成三个私有方法。
            Map.entry(COMPILER_FILE + "#computeRecursionLimit", Set.of("agent_loop", "iteration", "sub_workflow")),
            // ---- 两个 GraphBuilder：合成的子图没有 NodeExecutor ----
            Map.entry("AgentLoopGraphBuilder.java#resolveSpec", Set.of("agent_loop")),
            Map.entry("IterationGraphBuilder.java#build", Set.of("iteration")),
            // ---- 两个 service：读取点离执行器很远（审批发起、等待唤醒） ----
            Map.entry("AgentBpmApprovalService.java#resolveConfig", Set.of("hitl.bpm_approval")),
            Map.entry("AgentWorkflowExecutionServiceImpl.java#resolveWaitResumeAt", Set.of("wait")),
            Map.entry("AgentWorkflowExecutionServiceImpl.java#resolveWaitEventKey", Set.of("wait")),
            // ---- 迭代七 D3：强制删除前的「还有谁的 sub_workflow pin 着它」扫描 ----
            // 🔴 归属到 sub_workflow：读的键只有 workflowId / version，两个都是 sub_workflow 的契约 InPort。
            //    它不是执行器、也不合成子图，而是**删除闸门**在读别人 DSL 里的引用关系 ——
            //    与运行期权威 DslToStateGraphCompiler#addSubWorkflowNode 读的是同一份契约键，
            //    判据必须同源，否则「扫描说没人 pin、真跑起来却查不到子流程」。
            Map.entry("AgentWorkflowDefServiceImpl.java#findSubWorkflowReferencers", Set.of("sub_workflow")),
            // ---- trace 旁路：迭代六 D2 起，落库时要顺手记下「这个 tool 节点调的是哪个工具」 ----
            // 🔴 归属到 tool 而不是「所有类型」：读的键只有 toolId，那是 tool 的契约键。
            //    代码本身刻意不按 node.getType() 做白名单（别的类型哪天也带 toolId 就不用改这里），
            //    但契约归属说的是「这个键属于谁」，与代码是否设防是两件事。
            // 🔴 begin 有两个重载（2 参 / 4 参），本表只按「文件名#方法名」建索引 ⟹ 两个重载共用这一条。
            //    2 参那个只是转发、自身不读 config，所以合并没有歧义；哪天 2 参重载自己开始读键，
            //    要把它拆成不同名的方法，否则这条会把它一起「已归属」掉。
            Map.entry("NodeTraceRecorder.java#begin", Set.of("tool")));

    /**
     * 契约里「运行期不读、只有<b>分析面</b>读」的键（S6 起，今天 1 个）。
     *
     * <p>反向差集「契约登记的键必须有人读」是对的，但它的「读」特指运行期目录里的读。
     * 有一类键天生只有分析面读者：{@code wait.externalWakeup} 被 V14
     * （{@code WorkflowDslValidator#validateWaitEventWakeup}）读，用来判「无头流程里这个等待节点唤不唤得醒」；
     * 运行期唤醒走的是 {@code eventKey}，与这个纯声明字段无关。把它登记进契约是因为前端面板
     * 要有一个能填的格子（S6），不登记就会出现「V14 要求声明一个界面上没法填的字段」。
     *
     * <p>🔴 这里只允许「分析面读、运行期不读」这一种形状。运行期<b>该读</b>却没人读的键不能进这里，
     * 那会重新打开 #41（agent_loop.goal 死键）的口子 —— 见 {@link #analysisOnlyKeysAreNecessaryAndNotZombie}。
     */
    private static final Map<String, Set<String>> ANALYSIS_ONLY_KEYS = Map.of(
            "wait", Set.of("externalWakeup"));

    /** 类型 {@code type} 在 {@link #ANALYSIS_ONLY_KEYS} 里被豁免的键集合（无豁免返回空集） */
    private static Set<String> analysisOnlyKeysOf(String type) {
        return ANALYSIS_ONLY_KEYS.getOrDefault(type, Set.of());
    }

    // ==================== 未识别形态的认领清单 ====================

    /** 字面量不在任何调用的实参位上（case 标签、常量右值、注解外的裸串） */
    private static final String BARE = "<裸字面量>";

    /**
     * 「小写驼峰形状的字符串字面量、但不在已识别读取点上」的合法来源，按<b>被调方法名</b>认领
     * （实测今天 21 条 = 20 个真方法名 + {@link #BARE} 这个「不在实参位上」的伪 callee）。
     *
     * <p>为什么按方法名而不按字面量：按字面量要写 100+ 条（实测运行期这种字面量有 123 个不同值、222 处），
     * 那份清单本身会变成没人读的墙；按方法名只有二十来条，且每条都能给出一句<b>为什么它不是读 config</b> 的理由。
     *
     * <p>🔴 这个粒度是<b>粗</b>的（{@code get} 一律放行），所以它单独用会漏：
     * 有人写 {@code someMap.get("outputVar")} 读节点配置，本表看不出区别。
     * 兜它的是三条<b>派生</b>规则，都不靠这张表：
     * <ol>
     *   <li>噪声位上的字面量若<b>等于契约登记过的键</b> → 必须在 {@link #NON_NODE_CONFIG_KEY_LITERALS} 里逐处认领；</li>
     *   <li>形态 D：{@code Map c = x.getConfig()} 的 owner 声明类型若是 {@code NodeDTO} → 直接红；</li>
     *   <li>形态 E：键参数化的 Map 助手集合被 {@link #KEY_PARAM_HELPERS} 钉住，且它的调用点
     *       一个字面量都不许是契约登记的键。</li>
     * </ol>
     * ⟹ 这张表负责「让报告短」，那三条负责「不让它假绿」。
     */
    private static final Map<String, String> NOISE_CALLEES = Map.ofEntries(
            Map.entry("get", "Map/JSON 取值：分支规则的子字段（target/op/value/conditionGroups…）、"
                    + "state 键、补偿三件套（compensate_ref/before_image/idempotency_key）—— 都不是节点 config 的顶层键"),
            Map.entry("put", "往 state / JSON / 日志上下文里<b>写</b>，不是读 config"),
            Map.entry("of", "Map.of / List.of / Set.of 的字面量元素（角色名、事件名、子图节点名）"),
            Map.entry("str", "触发器侧的私有助手，读的是<b>触发器</b>配置 —— 见 KEY_PARAM_HELPERS"),
            Map.entry("getStr", "hutool JSONObject 取字段（模型返回的 type/text/tool）"),
            Map.entry("getJSONArray", "同上，取数组字段（calls）"),
            Map.entry("getJSONObject", "同上，取对象字段（params）"),
            Map.entry("msg", "拼对话历史用的 role 名（assistant/user）"),
            Map.entry("addEdge", "StateGraph 的节点名（agent_loop 子图里的 reason/act）"),
            Map.entry("addNode", "同上：agent_loop 合成子图的两个内部节点 id（reason/act）；"
                    + "其余 addNode 调用点传的都是 node.getId()，压根不是字面量"),
            Map.entry("addConditionalEdges", "同上：条件边的<b>源节点 id</b>（reason），不是 config 键"),
            Map.entry("tracedAction", "AgentLoopGraphBuilder 的私有包装，第一个实参是内部节点 id（reason/act），"
                    + "只用来拼 span 名"),
            Map.entry("checkEndpoint", "边端点的<b>字段名</b>（from/to），只用来拼「边缺少 from」这句错误文案；"
                    + "EdgeDTO 不是 NodeDTO，与节点 config 无关"),
            Map.entry("setType", "给合成节点设<b>类型名</b>（llm/tool）—— 是类型，不是 config 键"),
            Map.entry("eval", "GraalVM 的语言名 js"),
            Map.entry("getBindings", "同上"),
            Map.entry("newBuilder", "同上"),
            Map.entry("StateGraph", "子图名前缀 iteration_"),
            Map.entry("fence", "PromptDataFence 的<b>数据段标签</b>（提示词命名空间：给模型看的段落名，"
                    + "如 tool_observation / tool_error / choices）—— 标签由平台在代码里写死，"
                    + "既不来自 DSL 也不写回 state，与节点 config 无关。"
                    + "其中两个标签恰好与契约键同名，已按第 ③ 条逐处认领"),
            Map.entry("declareAndFence", "同上：PromptDataFence 的数据段标签（今天 1 处，agent_loop 的 goal），"
                    + "只是多前置一份 CONTRACT。已按第 ③ 条逐处认领"),
            Map.entry(BARE, "裸字面量（非方法实参）：case 标签 / 常量右值 / 集合元素"));

    /**
     * 噪声位上出现了<b>契约登记过的键名</b>时，逐处认领（实测今天 5 处）。
     *
     * <p>这张表小是好事：它小，说明「运行期那些同名字面量」与「契约的键」几乎不重叠。
     * 5 处分三类，都是<b>同名不同义</b>：
     * <ul>
     *   <li>2 处在 {@code TriggerDTO}：触发器的 {@code config} 与节点的 {@code config} 是两个 map，
     *       而它们各自都有一个 {@code inputVar}/{@code outputVar}；</li>
     *   <li>1 处是「别的命名空间的<b>值</b>恰好等于某个键名」：iteration 子图的路由名 {@code body}
     *       （与 iteration 的 {@code body} 键同名）；</li>
     *   <li>2 处是 {@code PromptDataFence} 的<b>数据段标签</b>与契约键同名（{@code choices} / {@code goal}）：
     *       键都由执行器/Builder 用形态 A 直读过一次，标签只是给模型看的段落名，属提示词命名空间。</li>
     * </ul>
     *
     * <p>🔴 这条规则是 {@link #NOISE_CALLEES} 那个粗粒度的唯一兜底，别为了少写一行把它删了：
     * 删掉之后 {@code someMap.get("outputVar")} 这种「用间接读法读节点配置」的写法就再也拦不住，
     * 而那正是 #97 那 13 个键里最容易再长出来的形态。
     * 反过来，它也<b>真的会响</b>：本类第一次跑就靠它撞出了 3 处值/键同名
     * （{@code waitType} 的取值 {@code duration} 两处、iteration 的路由名 {@code body} 一处），
     * 三处都逐个人工核过命名空间才认领的（不是为了让测试变绿而加白名单）。
     *
     * <p>🟢 其中 {@code duration} 那两处后来<b>不是被认领掉、而是被消灭掉的</b>：E2 抽出
     * {@code WaitTypeEnum} 后，编译器改成 {@code switch (waitType)}、Service 改成
     * {@code WaitTypeEnum.parse(...) != DURATION}，两处裸字面量都不存在了 ⟹ 认领条目按
     * {@link #readFormDomainIsProven} 第 ② 条的僵尸口径断言删掉。这是这张表<b>唯一</b>正确的缩小方式：
     * 让同名不再发生，而不是给同名发通行证。
     */
    private static final Map<String, String> NON_NODE_CONFIG_KEY_LITERALS = Map.of(
            "TriggerDTO.java#get#inputVar",
            "触发器 config 的「入口变量名」（TriggerDTO#inputVar，缺省 input），与 agent_loop.inputVar 同名不同义",
            "TriggerDTO.java#get#outputVar",
            "触发器 config 的「出口变量名」（TriggerDTO#outputVar，缺省 output），与各类型 OutPort 的 outputVar 同名不同义",
            "IterationGraphBuilder.java#" + BARE + "#body",
            "builder:84 的 ROUTE_BODY=\"body\" 是 iteration 合成子图里 guard 的<b>路由名</b>"
                    + "（guard→body 链 / guard→END），走的是 state 命名空间；"
                    + "iteration 的 body 键（循环体节点 id 列表）由 compiler 用形态 A 读，3 处都已识别",
            "LlmConditionNodeExecutor.java#fence#choices",
            "condition.llm 的 choices 键（候选分支数组）已由同文件 execute() 用形态 A 直读（node.configList）；"
                    + "这里的 fence(\"choices\", choicesText) 是给<b>已经读出来并渲染成文本</b>的那份白名单"
                    + "起的提示词数据段标签（系统提示词里那句「只能从下面标签为 choices 的数据段里选一个」"
                    + "靠它指认），属提示词命名空间，不是第二次读 config",
            "AgentLoopGraphBuilder.java#declareAndFence#goal",
            "agent_loop 的 goal 键已由同文件 buildLoopSpec 用形态 A 直读（与旧写法 prompt 一起 blankToDefault）；"
                    + "resolveGoal 的 declareAndFence(\"goal\", v) 包的是<b>运行期从 state 取到的目标值</b>"
                    + "（inputVar 那一支，v 来自 ctx.getString，压根不是 config），标签沿用语义名，属提示词命名空间");

    /**
     * 运行期域里「键以形参到达某个 Map 助手」的方法 —— 形态 E（今天 2 个，都在触发器侧）。
     *
     * <p>派生方式见 {@link #keyParamHelpers}：形参里有一个「可能装着 {@code getConfig()} 返回值」的 Map、
     * 体内 {@code m.get(k)} 而 {@code k} 也是形参，且<b>不是</b>形态 C（体内没有 {@code getConfig()}）。
     * 钉住个数是因为那种助手的 Map 从哪来<b>静态看不出来</b>，它完全可能是节点配置；
     * 配套那条派生规则（它的调用点字面量一个都不许是契约登记的键）保证「就算多出来一个，也读不到契约的键」。
     *
     * <p>🔴 这张表第一版只列了 1 个（{@code WorkflowTriggerExtractor#str}），因为量它的那个脚本
     * 是<b>按被调方法名</b>归组的，而两个助手恰好都叫 {@code str} ⟹ 9 个调用点被当成 1 个助手的。
     * 与「家族成员抄错而个数凑巧是 7」同型：个数对得上不代表域列全了。
     */
    private static final Map<String, String> KEY_PARAM_HELPERS = Map.of(
            "WorkflowTriggerExtractor.java#str",
            "读<b>触发器</b>配置（TriggerDTO.getConfig()，与节点 config 是两个 map）："
                    + "formCode/eventType/cron/agentId/path/authType/secret，7 处调用点",
            "AgentWebhookTriggerServiceImpl.java#str",
            "同样读<b>触发器</b>配置 —— webhook 验签那一侧自己又写了一个<b>同名</b>私有助手："
                    + "authType/secret，2 处调用点。⚠️ 两个助手同名，所以形态 E 的调用点必须按可见性收窄到本文件，"
                    + "否则 9 个调用点会算到每一个头上（见 keyParamHelpers/isPrivate）");

    // ==================== 扫描结果（一次算好，多个 @Test 共用） ====================

    private static List<Source> sources;
    private static Set<String> configFamily;
    private static String configMapValueType = "Object";
    private static Map<String, String> executorTypeByFile;
    private static List<Site> sites;
    private static Sweep sweep;
    /** 形态 C 找调用点时的搜索范围；探测器自检把它换成只含那段合成源码，否则会搜到真编译器里去 */
    private static List<Source> callSiteScope;

    @BeforeAll
    static void scan() throws IOException {
        sources = loadSources();
        callSiteScope = sources;
        configFamily = configAccessorFamily();
        configMapValueType = configMapValueType();
        executorTypeByFile = executorTypes();
        sites = new ArrayList<>();
        for (Source s : sources) {
            sites.addAll(readSites(s));
        }
        sweep = noiseSweep();
    }

    // ==================== 断言 ====================

    @Test
    @DisplayName("🔴 正向：运行期读的每个 config 键都被契约登记（#97 那 13 个键不许再长出来）")
    void runtimeReadsAreAllRegistered() {
        Map<String, Set<String>> contract = contractKeys();
        List<String> violations = new ArrayList<>();
        for (Site site : sites) {
            if (!site.resolved || site.types == null) {
                continue;               // 解不出 / 未归属：分别由 readFormDomainIsProven、readerAttributionIsComplete 报
            }
            boolean registered = site.types.stream()
                    .anyMatch(t -> contract.getOrDefault(t, Set.of()).contains(site.key));
            if (!registered) {
                violations.add("%s:%d [%s] 读 \"%s\"（归属 %s）".formatted(
                        site.file.fileName, site.line, site.form, site.key, new TreeSet<>(site.types)));
            }
        }
        assertTrue(violations.isEmpty(), """
                运行期读了、契约没登记的 config 键（正向差集）：
                """ + violations + """

                后果不是「少个提示」：WorkflowDslValidator 照契约建 producers 表，看不见这个键，
                于是合法的 {{$.这个键}} 被误报成悬空引用；UntrustedInputTracer 同理漏报。
                ⟹ 按 NodeIoContract 的登记判据「运行期读了就得登记」补进去（与面板上有没有格子无关），
                  并同步前端镜像 nodeIoContract.ts（结构比对见 _evidence/p97-mirror-diff.py）。
                ⚠️ 若这个读取点其实不属你归属的那个类型，要改的是 METHOD_TYPES，不是契约。
                """);
    }

    @Test
    @DisplayName("🔴 反向：契约登记的每个键运行期真的有人读（不许留死键；分析面键见豁免表）")
    void registeredKeysAreAllReadAtRuntime() {
        Map<String, Set<String>> contract = contractKeys();
        Map<String, Set<String>> read = keysReadByType();
        Map<String, List<String>> violations = new TreeMap<>();
        for (String type : new TreeSet<>(NodeIoContract.types())) {
            Set<String> dead = new TreeSet<>(contract.getOrDefault(type, Set.of()));
            dead.removeAll(read.getOrDefault(type, Set.of()));
            dead.removeAll(analysisOnlyKeysOf(type));
            if (!dead.isEmpty()) {
                violations.put(type, new ArrayList<>(dead));
            }
        }
        assertTrue(violations.isEmpty(), """
                契约登记了、运行期没人读的键（反向差集，已扣掉 ANALYSIS_ONLY_KEYS 豁免的分析面键）：
                """ + violations + """

                死键的代价是「作者填了、不生效、也不报错」—— 台账 #41 那个 agent_loop.goal 就是这副样子
                （三个作者面都在写 goal、运行期只读 prompt ⟹ 设计器拖出来的节点一定编译失败）。
                ⟹ 要么运行期真的该读它（那就是读取点漏了/被删了），要么把它从契约里删掉，
                  要么它确实是「只有分析面读」的键 —— 那要进 ANALYSIS_ONLY_KEYS，并过
                  analysisOnlyKeysAreNecessaryAndNotZombie 那条自检。
                ⚠️ 删之前先确认它不是「键以形参/间接方式到达」—— 那几种读法本类的形态 C/D/E 已经在扫；
                  若你的读法更新，先扩读法域，别先删契约。
                """);
    }

    @Test
    @DisplayName("🔴 分析面键豁免自检：每条都在契约里、运行期确实没人读（豁免是必要的，不是白名单）")
    void analysisOnlyKeysAreNecessaryAndNotZombie() {
        Map<String, Set<String>> contract = contractKeys();
        Map<String, Set<String>> read = keysReadByType();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : ANALYSIS_ONLY_KEYS.entrySet()) {
            String type = e.getKey();
            for (String key : e.getValue()) {
                if (!contract.getOrDefault(type, Set.of()).contains(key)) {
                    problems.add(type + "." + key + " 不在契约里，豁免是一句空话（键都不存在，差集本来就不会报它）");
                } else if (read.getOrDefault(type, Set.of()).contains(key)) {
                    problems.add(type + "." + key + " 运行期已经有人读，豁免条目是僵尸 —— 删掉豁免，让它走正常差集");
                }
            }
        }
        assertTrue(problems.isEmpty(), """
                分析面键豁免表有问题：
                """ + problems + """

                ANALYSIS_ONLY_KEYS 只该装「分析面读、运行期不读」的键。
                「不在契约里」的条目是空话；「运行期有人读」的条目是僵尸 —— 两种都让反向差集变松。
                """);
    }

    @Test
    @DisplayName("归属完备：读取点都归得到类型，映射里没有僵尸条目，13 种类型都被覆盖")
    void readerAttributionIsComplete() {
        // ① 每个读取点都必须能归属（执行器由 TYPE 派生；其余查 METHOD_TYPES）
        List<String> unattributed = sites.stream()
                .filter(s -> s.resolved && s.types == null)
                .map(s -> "%s:%d [%s] \"%s\" → 方法 %s".formatted(
                        s.file.fileName, s.line, s.form, s.key, s.method))
                .distinct().toList();
        assertTrue(unattributed.isEmpty(), """
                这些读取点归属不到任何节点类型：
                """ + unattributed + """

                ⟹ 给 METHOD_TYPES 加一条「文件名#方法名 → 类型」。别图省事只按文件归属：
                  文件级归属会让「往这个文件里加一个读别的类型键的方法」静默通过。
                ⚠️ 若它读的其实不是节点 config（例如触发器配置），要认领的是
                  NON_NODE_CONFIG_KEY_LITERALS / KEY_PARAM_HELPERS，不是 METHOD_TYPES。
                """);

        // ② 映射里没有僵尸条目（抄进来却再也用不上 = 第二份会漂的口径）
        Set<String> used = new HashSet<>();
        for (Site s : sites) {
            if (s.resolved && !s.file.rel.startsWith(EXECUTOR_IMPL_PREFIX)) {
                used.add(s.file.fileName + "#" + s.method);
            }
        }
        Set<String> stale = new TreeSet<>(METHOD_TYPES.keySet());
        stale.removeAll(used);
        assertTrue(stale.isEmpty(),
                "METHOD_TYPES 里这些条目一个读取点都没归属到（方法被改名/搬走了？）：" + stale
                        + "。留着就是僵尸口径 —— 将来真有个同名方法出现，会被误判成「已归属」");

        // ③ 映射里的类型名必须都是契约认识的（打错一个字母 = 那个方法的读取点全归到不存在的类型上，
        //    而反向差集只会报「契约的键没人读」，把读者指向错的方向）
        List<String> unknownTypes = METHOD_TYPES.values().stream()
                .flatMap(Set::stream)
                .filter(t -> NodeIoContract.of(t) == null)
                .distinct().sorted().toList();
        assertTrue(unknownTypes.isEmpty(), "METHOD_TYPES 里写了契约不认识的类型：" + unknownTypes);

        // ④ 13 种类型每一种都至少要有一个读取点，否则「反向差集为空」是空集上的空话
        Set<String> uncovered = new TreeSet<>(NodeIoContract.types());
        uncovered.removeAll(keysReadByType().keySet());
        assertTrue(uncovered.isEmpty(),
                "这些类型一个运行期读取点都没有：" + uncovered
                        + "。要么它的读取点全在分析面（那它就不该有契约条目），要么归属映射漏了");

        // ⑤ 域自检：执行器 impl 目录今天 10 个类，各派生出一个 TYPE
        assertEquals(10, executorTypeByFile.size(),
                "执行器 impl 目录今天应有 10 个类、各派生出一个 TYPE，实际 " + executorTypeByFile.size()
                        + "：" + new TreeMap<>(executorTypeByFile) + "。数量变了就要回来看归属是否还成立");
    }

    @Test
    @DisplayName("🔴 松散处只有一个方法：多类型归属必须被点名，不许悄悄变多")
    void weakTierIsBoundedToOneMethod() {
        // 多类型归属的方法：这些方法里的读取点只能要求「被归属类型之一登记」，
        // 于是「A 类型的键被登记在 B 类型上」这种错，本类查不出来。
        assertEquals(Set.of(COMPILER_FILE + "#computeRecursionLimit"), multiTypeMethods(), """
                「一个方法读多种类型的键」的地方变了。

                这类方法是本类<b>唯一</b>的松散处：它的读取点只能归属到类型的并集，
                于是正向差集只要求「键被并集里某一个类型登记」，查不出「登记到了错的那个类型上」。
                ⟹ 多了一个：先把那个方法按 node.getType() 拆成每类型一个私有方法（拆完就能归属到单一类型）；
                   真拆不动，才把它加进这条断言，并在 METHOD_TYPES 的条目上写清为什么拆不动。
                ⟹ 少了一个（更好）：说明 computeRecursionLimit 被拆开了，把这条断言改成空集。
                """);

        // 松散处波及的类型：今天正好是那三个「没有 NodeExecutor、由编译器/GraphBuilder 合成子图」的类型
        assertEquals(Set.of("agent_loop", "iteration", "sub_workflow"), weakTier(),
                "受多类型归属波及的类型应是这三个（正好是没有 NodeExecutor、由编译器合成子图的那三个）");

        // 其余 10 种类型的归属是<b>单一</b>的 —— 单一归属下「正向 ⊆ + 反向 ⊇」合起来就等于精确相等。
        // 🔴 下面这行**不是**独立判据，别把它当成第三道守卫（P2-3 扫出来的：它原先的注释就是这么声称的）：
        //    10 = 13 − 3，两个操作数分别被上面第 2 条与第 1 条钉死，任何能让它红的输入必然先让那两条红。
        //    「把某个类型也塞进多类型方法里」这个场景，红的是上面 weakTier() 那条集合相等，不是这条。
        //    留着它只为让失败文案把这笔算式印出来，省得下一个人自己减一遍。
        assertEquals(13, NodeIoContract.types().size(), "契约今天登记 13 种类型");
        assertEquals(10, NodeIoContract.types().size() - weakTier().size(),
                "单一归属（⟹ 双向差集等价于精确相等）的类型应是 10 个，弱档 " + new TreeSet<>(weakTier()));
    }

    @Test
    @DisplayName("裁定③：OutPort.configKey 也在扫描域里（#98 留下的那个问题）")
    void outputConfigKeysAreInTheScanDomain() {
        // 「只出现在 OutPort.configKey、不作为任何 InPort.key」的键 —— 这就是裁定③要扩进来的那部分域。
        Set<String> inputKeys = new HashSet<>();
        Set<String> configKeys = new HashSet<>();
        for (NodeIoContract.Contract c : NodeIoContract.all()) {
            c.inputs().forEach(p -> inputKeys.add(p.key()));
            c.outputs().stream().map(NodeIoContract.OutPort::configKey)
                    .filter(k -> k != null && !k.isBlank()).forEach(configKeys::add);
        }
        Set<String> onlyOut = new TreeSet<>(configKeys);
        onlyOut.removeAll(inputKeys);
        assertEquals(Set.of("approveVar", "citationsVar", "outputVar", "statusVar", "trajectoryVar"), onlyOut,
                "「只作为 OutPort.configKey 出现」的键应是这 5 个，实际：" + onlyOut
                        + "。它们不在任何 InPort 上 ⟹ 『只扫 inputs() 的键』的实现会整块漏掉它们");

        // 这 5 个键必须真的被扫到（而不是只在上面那个集合运算里出现过）
        Set<String> scanned = new HashSet<>();
        sites.stream().filter(s -> s.resolved).forEach(s -> scanned.add(s.key));
        Set<String> missing = new TreeSet<>(onlyOut);
        missing.removeAll(scanned);
        assertTrue(missing.isEmpty(),
                "这些 configKey 一个运行期读取点都没扫到：" + missing
                        + " ⟹ 扫描域实际上没覆盖 OutPort.configKey，裁定③没落地");

        // 且反向差集真的覆盖到它们：每个 configKey 都要有归属到「登记它的那个类型」的读取点
        Map<String, Set<String>> read = keysReadByType();
        List<String> notRead = new ArrayList<>();
        for (NodeIoContract.Contract c : NodeIoContract.all()) {
            for (NodeIoContract.OutPort p : c.outputs()) {
                if (p.configKey() == null || p.configKey().isBlank()) {
                    continue;
                }
                if (!read.getOrDefault(c.type(), Set.of()).contains(p.configKey())) {
                    notRead.add(c.type() + "." + p.configKey());
                }
            }
        }
        assertTrue(notRead.isEmpty(),
                "这些 OutPort.configKey 没有被归属到自己类型的运行期读取点：" + notRead);
    }

    @Test
    @DisplayName("🔴 读法域：未识别的字面量必须被认领；认领清单不许有僵尸条目、不许认领契约的键")
    void readFormDomainIsProven() {
        // ① 未被认领的字面量 = 可能的漏形态（正向差集的假绿就出在这里）
        assertTrue(sweep.unrecognized.isEmpty(), """
                运行期源码里这些「小写驼峰形状」的字符串字面量，既不在已识别的读取点上、也没被认领：
                """ + sweep.unrecognized + """

                ⟹ 三种可能，按这个顺序排：
                  1. 它是一种本类还不认识的 config 读法（#97-b 就是这么撞出第五种形态 str(config,"formCode") 的）
                     —— 那就扩读法域（readSites 里加一种形态），<b>不要</b>把它加进 NOISE_CALLEES；
                  2. 它确实与 config 无关 —— 给 NOISE_CALLEES 加一条「被调方法名 → 为什么不是读 config」；
                  3. 它读的是别的命名空间（触发器 / state / JSON）—— 同上，理由里写明是哪个命名空间。
                ⚠️ 若这个字面量正好等于契约登记的键，光认领 callee 不够，
                  还得在 NON_NODE_CONFIG_KEY_LITERALS 里逐处认领（下面第 ③ 条会单独红）。
                """);

        // ② 三张认领清单都不许有僵尸条目
        Set<String> staleCallees = new TreeSet<>(NOISE_CALLEES.keySet());
        staleCallees.removeAll(sweep.usedCallees);
        assertTrue(staleCallees.isEmpty(),
                "NOISE_CALLEES 里这些被调方法名一处都没命中：" + staleCallees
                        + "。留着就是僵尸口径（代码搬走/改名之后，同名方法再出现会被静默放行）");
        Set<String> staleKeyLits = new TreeSet<>(NON_NODE_CONFIG_KEY_LITERALS.keySet());
        staleKeyLits.removeAll(sweep.usedKeyLiterals);
        assertTrue(staleKeyLits.isEmpty(),
                "NON_NODE_CONFIG_KEY_LITERALS 里这些条目一处都没命中：" + staleKeyLits);
        assertEquals(KEY_PARAM_HELPERS.keySet(), sweep.helpers,
                "运行期域里「键以形参到达某个 Map 助手」的方法集合变了，实际：" + new TreeSet<>(sweep.helpers)
                        + "。这种助手的 Map 从哪来静态看不出来 ⟹ 多出一个就得回来看它读的是谁的配置，"
                        + "并在 KEY_PARAM_HELPERS 里写清是哪个命名空间；少了（更好）说明触发器那侧改成了直读。"
                        + "⚠️ 别按<b>方法名</b>数：两个助手可以同名（今天两个都叫 str）");

        // ③ 派生规则（不靠清单）：噪声位上的字面量若等于契约登记过的键，必须逐处认领
        Set<String> universe = contractKeyUniverse();
        List<String> unclaimed = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : sweep.noiseSites.entrySet()) {
            if (e.getValue().stream().anyMatch(universe::contains)
                    && !NON_NODE_CONFIG_KEY_LITERALS.containsKey(e.getKey())) {
                unclaimed.add(e.getKey() + " → " + new TreeSet<>(e.getValue()));
            }
        }
        assertTrue(unclaimed.isEmpty(), """
                这些「未识别读取点」上的字面量正好是契约登记过的键名，必须逐处认领：
                """ + unclaimed + """

                为什么这条不能省：NOISE_CALLEES 是按<b>被调方法名</b>放行的（get/put/of…），粒度很粗，
                单靠它拦不住 someMap.get("outputVar") 这种「用间接读法读节点配置」。
                ⟹ 要么改成直读形态 node.configStr("outputVar")（本类认得），
                  要么在 NON_NODE_CONFIG_KEY_LITERALS 里写清它属哪个命名空间、与契约那个同名键为什么不是一回事。
                """);

        // ④ 形态 E 的配套派生规则：键参数化助手的调用点，一个字面量都不许是契约登记的键
        List<String> helperKeyHits = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : sweep.helperLiterals.entrySet()) {
            e.getValue().stream().filter(universe::contains)
                    .forEach(lit -> helperKeyHits.add(e.getKey() + " → " + lit));
        }
        assertTrue(helperKeyHits.isEmpty(),
                "这些键参数化助手的调用点传了一个契约登记过的键名：" + helperKeyHits
                        + " ⟹ 它可能正在读节点配置，而本类解不出它的归属。改成直读形态，或把它归属到某个类型");

        // ⑤ 形态 A'：家族访问器的第一个实参必须是字面量，否则键名解不出来
        assertTrue(sweep.unresolvedKeyForms.isEmpty(), """
                这些读取点的键名不是字面量，本扫描解不出来：
                """ + sweep.unresolvedKeyForms + """

                ⟹ 改成 .configXxx("键名") 的直读形态。<b>不要</b>为它加白名单：
                  键名一旦来自常量/变量，「运行期读了哪些键」就没法从源码量出来，
                  而本类整个正向差集都建立在这个可量性上（#98 那条「值比对在引用常量后恒真」是同一道理的反面）。
                ⚠️ 唯一的例外是形态 C（getConfig().get(形参)），它按调用点位置解出字面量 —— 见 resolveParamKeys。
                """);
        List<String> unresolvedSites = sites.stream().filter(s -> !s.resolved)
                .map(s -> "%s:%d [%s] %s".formatted(s.file.fileName, s.line, s.form, s.key))
                .distinct().toList();
        assertTrue(unresolvedSites.isEmpty(),
                "这些读取点的键名解不出来（形参没有字面量调用点 / 不在任何方法里）：" + unresolvedSites);

        // ⑥ 形态 D：把 getConfig() 存进局部变量。owner 的声明类型若是 NodeDTO，本类就看不见它的键
        assertTrue(sweep.nodeConfigBinds.isEmpty(), """
                这些「把 getConfig() 存进局部变量再读」的地方，owner 的声明类型是 NodeDTO：
                """ + sweep.nodeConfigBinds + """

                ⟹ 改回 node.configXxx("键名") 的直读形态。间接读法让「读了哪些键」散落在另一个变量的使用点上，
                  本类扫不到（今天节点侧 0 处；实测命中的两处都是 TriggerDTO.getConfig()，属触发器命名空间）。
                """);
    }

    // ==================== 探测器自检 ====================

    @Test
    @DisplayName("🔴 探测器自检：五种形态各自必须被扫出来")
    void scannerDetectsEveryForm() {
        // 逐条独立命名（#101(a) 的教训：JUnit 断言短路，「有这条断言」≠「这条断言被证明能红」）
        assertEquals(List.of("k1"), keysIn("void f(NodeDTO node) { node.configStr(\"k1\"); }"),
                "形态 A（单参）扫不出来 ⟹ 正向差集是假绿");
        assertEquals(List.of("k2"), keysIn("void f(NodeDTO node) { node.configInt(\"k2\", 5); }"),
                "形态 A（两参）扫不出来 —— 🔴 #97 第一次测量正是漏了它，于是 maxSteps/join/concurrency/language/timeoutMs"
                        + " 五个键看着像「契约登记了但没人读的死键」，差点被写进结论");
        assertEquals(List.of("k3"), keysIn("void f(NodeDTO node) { Object v = node.getConfig().get(\"k3\"); }"),
                "形态 B 扫不出来");
        assertEquals(List.of("inputMap", "outputMap"), keysIn("""
                        private static Map<String, String> validateMapping(NodeDTO node, String field, String id) {
                            Object raw = node.getConfig().get(field);
                            return Map.of();
                        }
                        void caller(NodeDTO node, String id) {
                            validateMapping(node, "inputMap", id);
                            validateMapping(node, "outputMap", id);
                        }
                        """),
                "形态 C（键以形参到达）解不出调用点的字面量 —— 这正是 #83 那轮的盲区②，"
                        + "且第一版还踩过两个坑：形参位置要逐个记（否则抓到的是 subNodeId 而不是 field）、"
                        + "方法声明行本身长得像一次调用（必须按签名区间剔掉）");
        assertEquals(List.of("Snippet.java:2 Map … c = node.getConfig()"), bindsIn("""
                        void f(NodeDTO node) {
                            Map<String, Object> c = node.getConfig();
                            Object v = c.get("k4");
                        }
                        """),
                "形态 D（局部变量间接、owner 是 NodeDTO）必须被<b>报出来</b>，别静默跳过");
        assertTrue(bindsIn("void f(TriggerDTO trigger) { Map<String, Object> c = trigger.getConfig(); }").isEmpty(),
                "形态 D 的 owner 不是 NodeDTO 时不该报（触发器配置不在契约域内），否则今天就有 2 条假红");
        assertEquals(Set.of("Snippet.java#myHelper"), helpersIn("""
                        private String myHelper(Map<String, Object> config, String key) {
                            Object v = config.get(key);
                            return v == null ? null : String.valueOf(v);
                        }
                        void f(Map<String, Object> config) { myHelper(config, "someKey"); }
                        """),
                "形态 E（键以形参到达某个 Map 助手）扫不出来 ⟹ 触发器那侧的 9 处读取就成了永久盲区");
        // 🔴 形态 E 判据里那刀「值类型必须是 getConfig() 那个」的自检：
        //    只看结构会把 DslToStateGraphCompiler#redirectFrom/#redirectTo 也算进来（实测撞上过），
        //    它们的 Map 是「子流程端点重定向表」Map<String, String[]>、键是节点 id —— 纯形状巧合。
        assertEquals(Set.of(), helpersIn("""
                        private String redirectTo(String id, Map<String, String[]> swRedirect) {
                            String[] r = swRedirect.get(id);
                            return r != null ? r[0] : id;
                        }
                        """),
                "形态 E 的判据没收窄到「Map 的值类型 = getConfig() 那个」⟹ 重定向表这类形状巧合会被当成 config 助手，"
                        + "KEY_PARAM_HELPERS 那条钉住个数的断言就会天天红，红久了就没人看了");
        assertEquals(Set.of("Snippet.java#rawHelper"), helpersIn("""
                        private String rawHelper(Map config, String key) {
                            Object v = config.get(key);
                            return v == null ? null : String.valueOf(v);
                        }
                        """),
                "裸 Map 形参没被当成形态 E ⟹ 收窄收过头了：裸 Map 什么都装得下，"
                        + "node.getConfig() 完全可以传进去，这是最该报的一种");
        assertEquals(Set.of("Snippet.java#wildcardHelper"), helpersIn("""
                        private String wildcardHelper(Map<String, ?> config, String key) {
                            Object v = config.get(key);
                            return v == null ? null : String.valueOf(v);
                        }
                        """),
                "Map<String, ?> 形参没被当成形态 E ⟹ 同上，通配符也接得住 getConfig() 的返回值");
        // 🔴 调用点范围必须按可见性收窄：今天两个助手恰好<b>同名</b>（都叫 str），
        //    只按方法名跨全域找调用点，就会把 9 个调用点算到每一个头上
        //    （第一版实测报成「各 7 个字面量」，而 AgentWebhookTriggerServiceImpl#str 只有 authType/secret 两个）。
        assertEquals(Map.of("A.java#str", Set.of("kA"), "B.java#str", Set.of("kB")),
                helperLiteralsIn(Map.of(
                        "A.java", """
                                class A {
                                    private String str(Map<String, Object> c, String k) {
                                        return String.valueOf(c.get(k));
                                    }
                                    void f(Map<String, Object> c) { str(c, "kA"); }
                                }
                                """,
                        "B.java", """
                                class B {
                                    private String str(Map<String, Object> c, String k) {
                                        return String.valueOf(c.get(k));
                                    }
                                    void f(Map<String, Object> c) { str(c, "kB"); }
                                }
                                """)),
                "private 助手的调用点没按文件收窄 ⟹ 同名助手互相串台；对第 ④ 条断言只是偏严，"
                        + "但报告里的数就是错的，而「量错了还写进文档」正是这条线上反复犯的病");
        assertEquals(Set.of("kB"),
                helperLiteralsIn(Map.of(
                        "A.java", """
                                class A {
                                    String pub(Map<String, Object> c, String k) {
                                        return String.valueOf(c.get(k));
                                    }
                                }
                                """,
                        "B.java", """
                                class B {
                                    void f(A a, Map<String, Object> c) { a.pub(c, "kB"); }
                                }
                                """)).get("A.java#pub"),
                "非 private 的助手也被收窄到本文件 ⟹ 跨文件的调用点整块漏掉，第 ④ 条就<b>变松</b>了（这才是假绿）");
    }

    @Test
    @DisplayName("🔴 探测器自检：注释/字符串/文本块里的假键必须不被扫出来，未认领的字面量必须被扫出来")
    void scannerIsNotTooWide() {
        // 反向：注释里的假键必须扫不出来（这正是本类相对 #98 那条扫描的优势 —— 那条分不清注释与代码，
        // 于是 javadoc 里点名键名时不能带引号；本类分得清，所以契约的 javadoc 想怎么写就怎么写）
        assertEquals(List.of(), keysIn("// node.configStr(\"commentKey\")"),
                "行注释里的读取被当成真读取点 ⟹ 反向差集会假绿（以为有人读）");
        assertEquals(List.of(), keysIn("/** {@code node.configStr(\"javadocKey\")} */"),
                "javadoc 里的读取被当成真读取点");
        assertEquals(List.of(), keysIn("/* node.configMap(\"blockKey\") */"),
                "块注释里的读取被当成真读取点");
        assertEquals(List.of(), keysIn("void f() { String s = \"node.configStr(\\\"strKey\\\")\"; }"),
                "字符串正文里的读取被当成真读取点。⚠️ 这条守的是「inString 守卫 + FORM_A 的正则宽度」<b>一对</b>，"
                        + "不是守卫单独一件：普通字符串里的引号必然转义成 \\\"，而 \\s* 跨不过反斜杠 ⟹ 只摘守卫它照样绿（实测过）");
        // 🔴 文本块：本域里 AgentLoopPrompt / AgentLoopGraphBuilder 真的在用 """，
        //    提示词正文里出现 configStr("x") 这种示例是完全可能的。第一版没处理 """ 会把它当成空串+代码。
        assertEquals(List.of(), keysIn("void f() { String p = \"\"\"\n        node.configStr(\"textBlockKey\")\n        \"\"\"; }"),
                "文本块里的内容没被整块识别成字符串 ⟹ 提示词里的示例会变成假读取点");
        assertEquals(List.of("realKey"), keysIn("void f(NodeDTO node) { String p = \"\"\"\n        提示词\n        \"\"\";\n node.configStr(\"realKey\"); }"),
                "处理文本块时把真代码一起吃掉了 ⟹ 口径太宽的反面：漏扫");

        // 花括号配对不能被字符串带偏：StateTemplateResolver 的模板正则里 { 与 } 数目就不等（"{{\s*([^}]+?)\s*}}"），而它在扫描域内
        assertEquals(List.of("f#afterKey", "f#beforeKey"), keysWithMethodIn("""
                        void f(NodeDTO node) {
                            String tpl = "{{" + node.configStr("beforeKey");
                            node.configStr("afterKey");
                        }
                        """),
                "字符串里的 \"{{\" 把方法边界/括号配对带偏了 ⟹ 归属退化成 <非方法体>");

        // 反向：未认领的字面量必须被扫出来（NOISE_CALLEES 之外的一切）
        assertEquals(List.of("Snippet.java:1 myHelper(\"someKey\")"),
                unrecognizedIn("void f() { myHelper(config, \"someKey\"); }"),
                "未认领的字面量扫不出来 ⟹ readFormDomainIsProven 那条是假绿");
        assertEquals(List.of(), unrecognizedIn("@SuppressWarnings(\"unchecked\") void f() {}"),
                "注解实参被当成未认领字面量 ⟹ 口径太宽（今天运行期有 8 处 @SuppressWarnings）");
        assertEquals(List.of(), unrecognizedIn("void f() { switch (t) { case \"formEvent\" -> g(); } }"),
                "case 标签没被归到「裸字面量」桶里（BARE 是认领过的）");

        // 两参访问器的「默认值」实参：形状与键无法区分，但不是键（今天 1 处 compiler:508 configStr("join","all")）
        assertEquals(List.of(), unrecognizedIn("void f(NodeDTO n) { n.configStr(\"join\", \"all\"); }"),
                "两参访问器的默认值被当成未认领字面量 ⟹ 假红；它不是键，键是第一个实参");
        assertEquals(Set.of("Snippet.java#configStr#default"),
                noiseKeysIn("void f(NodeDTO n) { n.configStr(\"k\", \"default\"); }"),
                "默认值没进噪声桶 ⟹ 它恰好等于契约登记的键（condition.rule 的 default）时，"
                        + "readFormDomainIsProven 第 ③ 条的逐处认领会失效（假绿）");
        assertEquals(List.of("join"), keysIn("void f(NodeDTO n) { n.configStr(\"join\", \"all\"); }"),
                "两参访问器只该读出第一个实参那个键；把默认值也当键 ⟹ 正向差集会多出一个不存在的键");
    }

    @Test
    @DisplayName("派生自检：config 家族与执行器类型都是现场派生的，不是手抄的")
    void derivationsAreNotHandCopied() {
        assertTrue(configFamily.containsAll(Set.of("configStr", "configInt", "configLong",
                        "configDouble", "configBool", "configMap", "configList")),
                "从 NodeDTO 派生出的 config 访问器家族不全，实际：" + new TreeSet<>(configFamily)
                        + "。🔴 #97 第一次测量手抄这张表时把 configKey 当成了成员、漏了 configBool，而个数凑巧也是 7");
        assertFalse(configFamily.contains("configKey"),
                "configKey 不是读 config 的方法（它是 NodeIoContract.OutPort 的 record 访问器）；"
                        + "派生把它带进来了 ⟹ 口径太宽");
        assertEquals("tool", executorTypeByFile.get(EXECUTOR_IMPL_PREFIX + "ToolNodeExecutor.java"),
                "执行器 → 类型的派生坏了（应由类里的 public static final String TYPE 派生）");
        assertEquals("parallel", executorTypeByFile.get(EXECUTOR_IMPL_PREFIX + "ParallelNodeExecutor.java"),
                "ParallelNodeExecutor 一个 config 键都不读（它是 fan-out 锚点，边由编译器建），"
                        + "但它的 TYPE 仍必须派生出来 —— 否则 parallel 的归属就只剩编译器那一半，"
                        + "而「parallel 的实读键在编译器里」正是 #97 第一次测量的第二个口径陷阱");
        // 契约的 13 种类型里，10 种有执行器、3 种由编译器合成子图
        Set<String> withExecutor = new TreeSet<>(executorTypeByFile.values());
        assertEquals(10, withExecutor.size(), "有 NodeExecutor 的类型应是 10 个，实际：" + withExecutor);
        Set<String> noExecutor = new TreeSet<>(NodeIoContract.types());
        noExecutor.removeAll(withExecutor);
        assertEquals(Set.of("agent_loop", "iteration", "sub_workflow"), noExecutor,
                "没有 NodeExecutor 的类型应是这三个（由编译器/GraphBuilder 合成子图）");
    }

    /**
     * 测量报告（存证用）。默认静默 —— {@code mvn test -Dtest=NodeIoContractRuntimeReadScanTest -Dp97b.dump=true}
     * 才打印，输出直接抄进 {@code _evidence/p97b-scan-java.txt}。
     *
     * <p>为什么要它：断言只说「差集为空」，而回写文档要说清<b>域有多大</b>（几个文件、几个读取点、
     * 每型几个键）。「域没列全就先下结论」是这条线上反复犯的病（#102、#83 §0.8.4.1③），
     * 所以把量到的数打出来、和断言同一份代码算，比另写一个 Python 脚本再对一遍更不容易分岔。
     */
    @Test
    @DisplayName("测量报告：-Dp97b.dump=true 时打印域与差集的实测数（默认静默）")
    void dumpReportWhenAsked() {
        if (!"true".equals(System.getProperty("p97b.dump"))) {
            return;
        }
        StringBuilder b = new StringBuilder();
        int lines = sources.stream().mapToInt(s -> s.text.split("\n", -1).length).sum();
        b.append("\n## 1. 扫描域\n");
        b.append("运行期目录 ").append(RUNTIME_DIRS.size()).append(" 个：").append(RUNTIME_DIRS).append('\n');
        b.append("文件 ").append(sources.size()).append(" 个 / 合计 ").append(lines).append(" 行")
                .append("（含权威副本 NodeIoContract.java，它不参与差集）\n");
        b.append("执行器 impl ").append(executorTypeByFile.size()).append(" 个，各由类里的 TYPE 常量派生出一个类型\n");
        b.append("非执行器读者的方法级归属映射 METHOD_TYPES：").append(METHOD_TYPES.size()).append(" 条\n");

        Map<String, Integer> byForm = new TreeMap<>();
        sites.forEach(s -> byForm.merge(s.form, 1, Integer::sum));
        b.append("\n## 2. 读取点\n");
        b.append("合计 ").append(sites.size()).append(" 处；键名解不出来的 ")
                .append(sites.stream().filter(s -> !s.resolved).count()).append(" 处\n");
        byForm.forEach((f, n) -> b.append("  ").append(f).append(" = ").append(n).append('\n'));
        Set<String> usedAccessors = new TreeSet<>();
        byForm.keySet().stream().filter(f -> f.startsWith("A.")).forEach(f -> usedAccessors.add(f.substring(2)));
        Set<String> deadAccessors = new TreeSet<>(configFamily);
        deadAccessors.removeAll(usedAccessors);
        b.append("config 访问器家族（由 NodeDTO 现场派生）= ").append(new TreeSet<>(configFamily)).append('\n');
        b.append("  其中运行期 0 个调用点的 = ").append(deadAccessors)
                .append("（声明了没人用；本类仍把它算进域，否则将来有人用了就扫不到）\n");
        b.append("形态 A'（访问器第一个实参不是字面量）= ").append(sweep.unresolvedKeyForms.size()).append('\n');
        b.append("形态 D（getConfig() 存进局部变量、owner 声明类型是 NodeDTO）= ")
                .append(sweep.nodeConfigBinds.size()).append('\n');
        b.append("形态 E（键以形参到达某个 Map 助手）= ").append(sweep.helpers.size()).append(" 个助手：")
                .append(sweep.helpers).append('\n');
        sweep.helperLiterals.forEach((h, lits) ->
                b.append("    ").append(h).append(" 的调用点字面量 ").append(lits.size()).append(" 个：")
                        .append(lits).append('\n'));
        b.append("未认领的字面量 = ").append(sweep.unrecognized.size()).append('\n');
        b.append("噪声桶 ").append(sweep.noiseSites.size()).append(" 个位置；命中的 NOISE_CALLEES ")
                .append(sweep.usedCallees.stream().filter(NOISE_CALLEES::containsKey).count())
                .append('/').append(NOISE_CALLEES.size()).append(" 条\n");
        b.append("噪声位上恰好等于契约键、已逐处认领的 = ").append(sweep.usedKeyLiterals.stream()
                .filter(NON_NODE_CONFIG_KEY_LITERALS::containsKey).count())
                .append('/').append(NON_NODE_CONFIG_KEY_LITERALS.size()).append(" 条\n");

        Map<String, Set<String>> contract = contractKeys();
        Map<String, Set<String>> read = keysReadByType();
        Map<String, Set<String>> readStrong = new TreeMap<>();
        for (Site s : sites) {
            if (s.resolved && s.types != null && s.types.size() == 1) {
                readStrong.computeIfAbsent(s.types.iterator().next(), k -> new TreeSet<>()).add(s.key);
            }
        }
        Set<String> weak = weakTier();
        b.append("\n## 3. 逐类型双向差集\n");
        b.append("「实读键数(并集)」把弱档读取点算进它归属到的<b>每一个</b>类型 ⟹ 弱档那一行的正向差集\n");
        b.append("不是断言真正在查的东西（断言查的是「并集里<b>有人</b>登记」）；强档那一行两者一致，\n");
        b.append("「正向空 + 反向空」就等价于精确相等。\n");
        b.append("type | 契约键数 | 实读键数(并集) | 仅单一归属 | 档 | 正向差集(仅单一归属) | 反向差集\n");
        int totalContractKeys = 0;
        for (String type : new TreeSet<>(NodeIoContract.types())) {
            Set<String> ck = new TreeSet<>(contract.getOrDefault(type, Set.of()));
            Set<String> rk = new TreeSet<>(read.getOrDefault(type, Set.of()));
            Set<String> sk = new TreeSet<>(readStrong.getOrDefault(type, Set.of()));
            totalContractKeys += ck.size();
            Set<String> fwd = new TreeSet<>(sk);
            fwd.removeAll(ck);
            Set<String> rev = new TreeSet<>(ck);
            rev.removeAll(rk);
            b.append(type).append(" | ").append(ck.size()).append(" | ").append(rk.size())
                    .append(" | ").append(sk.size())
                    .append(" | ").append(weak.contains(type) ? "弱" : "强")
                    .append(" | ").append(fwd).append(" | ").append(rev).append('\n');
        }
        b.append("契约键位合计（按类型数，含重复登记的同名键）= ").append(totalContractKeys)
                .append("；去重后 = ").append(contractKeyUniverse().size()).append('\n');
        b.append("多类型归属的方法（本类唯一的松散处）= ").append(multiTypeMethods()).append('\n');

        b.append("\n## 3b. 弱档读取点逐条（断言真正查的就是这张表：每行都必须有「登记方」）\n");
        for (Site s : sites) {
            if (!s.resolved || s.types == null || s.types.size() < 2) {
                continue;
            }
            Set<String> owners = new TreeSet<>(s.types.stream()
                    .filter(t -> contract.getOrDefault(t, Set.of()).contains(s.key)).toList());
            b.append("  ").append(s.file.fileName).append(':').append(s.line)
                    .append(" \"").append(s.key).append("\" 归属 ").append(new TreeSet<>(s.types))
                    .append(" → 登记方 ").append(owners).append(owners.isEmpty() ? "  ❌" : "").append('\n');
        }

        Map<String, Set<String>> readersByKey = new TreeMap<>();
        for (Site s : sites) {
            if (s.resolved) {
                readersByKey.computeIfAbsent(s.key, k -> new TreeSet<>()).add(s.file.fileName);
            }
        }
        b.append("\n## 4. 键 → 运行期读者文件（").append(readersByKey.size()).append(" 个键；")
                .append(readersByKey.values().stream().filter(v -> v.size() > 1).count())
                .append(" 个有 ≥2 个读者文件）\n");
        readersByKey.forEach((k, v) -> b.append("  ").append(k).append(" ← ").append(v).append('\n'));

        Set<String> inputKeys = new HashSet<>();
        Set<String> outKeys = new HashSet<>();
        for (NodeIoContract.Contract c : NodeIoContract.all()) {
            c.inputs().forEach(p -> inputKeys.add(p.key()));
            c.outputs().stream().map(NodeIoContract.OutPort::configKey)
                    .filter(k -> k != null && !k.isBlank()).forEach(outKeys::add);
        }
        Set<String> onlyOut = new TreeSet<>(outKeys);
        onlyOut.removeAll(inputKeys);
        b.append("\n## 5. 裁定③扩进来的域：只作为 OutPort.configKey 出现的键（").append(onlyOut.size())
                .append(" 个）\n  ").append(onlyOut).append('\n');

        b.append("\n## 6. 三张认领清单（NOISE_CALLEES 是粗粒度的，后两张是它的兜底）\n");
        b.append("NOISE_CALLEES ").append(NOISE_CALLEES.size()).append(" 条，全部命中：\n");
        new TreeMap<>(NOISE_CALLEES).forEach((k, v) -> b.append("  ").append(k).append(" — ").append(v).append('\n'));
        b.append("NON_NODE_CONFIG_KEY_LITERALS ").append(NON_NODE_CONFIG_KEY_LITERALS.size())
                .append(" 条（噪声位上恰好等于契约键的，逐处认领）：\n");
        new TreeMap<>(NON_NODE_CONFIG_KEY_LITERALS).forEach((k, v) ->
                b.append("  ").append(k).append(" — ").append(v).append('\n'));
        b.append("KEY_PARAM_HELPERS ").append(KEY_PARAM_HELPERS.size()).append(" 条：\n");
        new TreeMap<>(KEY_PARAM_HELPERS).forEach((k, v) -> b.append("  ").append(k).append(" — ").append(v).append('\n'));
        b.append("METHOD_TYPES ").append(METHOD_TYPES.size()).append(" 条（非执行器读者的方法级归属）：\n");
        new TreeMap<>(METHOD_TYPES).forEach((k, v) ->
                b.append("  ").append(k).append(" → ").append(new TreeSet<>(v)).append('\n'));
        System.out.println(b);
    }

    // ==================== 扫描实现 ====================
    /** 一份源码的两个视图：{@code code} 用来做结构分析，{@code text} 用来取键名 */
    private static final class Source {
        final String rel;
        final String fileName;
        /** 注释<b>与字符串正文</b>都换成等长空格 ⟹ 括号配对不会被 {@code "{"} 这类字面量带偏 */
        final String code;
        /** 只把注释换成等长空格、字符串保留 ⟹ 键名取得到 */
        final String text;
        /** text 里每个字符串字面量（含文本块）的 [start, end) */
        final List<int[]> strings = new ArrayList<>();
        final List<Method> methods = new ArrayList<>();
        private int[] lineStarts;

        Source(String rel, String code, String text) {
            this.rel = rel;
            this.fileName = rel.substring(rel.lastIndexOf('/') + 1);
            this.code = code;
            this.text = text;
        }

        int lineOf(int offset) {
            if (lineStarts == null) {
                List<Integer> ls = new ArrayList<>();
                ls.add(0);
                for (int i = 0; i < text.length(); i++) {
                    if (text.charAt(i) == '\n') {
                        ls.add(i + 1);
                    }
                }
                lineStarts = ls.stream().mapToInt(Integer::intValue).toArray();
            }
            int lo = 0;
            int hi = lineStarts.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (lineStarts[mid] <= offset) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo + 1;
        }

        boolean inString(int offset) {
            for (int[] sp : strings) {
                if (offset >= sp[0] && offset < sp[1]) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Method(String name, int sigStart, int bodyStart, int bodyEnd, List<String> params) {
    }

    /** 一个 config 读取点 */
    private static final class Site {
        final Source file;
        final int line;
        /** 键名字面量在 {@code file.text} 里的偏移（形态 C 有两个：读取点 + 调用点，见 extraOffsets） */
        final List<Integer> keyOffsets;
        final String method;
        final String form;
        final String key;
        /** false = 键名解不出来（由 readFormDomainIsProven 报，不参与差集） */
        final boolean resolved;
        /** 归属到的节点类型；null = 归属不到（由 readerAttributionIsComplete 报） */
        final Set<String> types;

        Site(Source file, int keyOffset, String method, String form, String key, boolean resolved, Set<String> types) {
            this.file = file;
            this.line = file.lineOf(keyOffset);
            this.keyOffsets = new ArrayList<>(List.of(keyOffset));
            this.method = method;
            this.form = form;
            this.key = key;
            this.resolved = resolved;
            this.types = types;
        }
    }

    private record Sweep(Map<String, Set<String>> noiseSites,
                         Set<String> usedCallees,
                         Set<String> usedKeyLiterals,
                         Set<String> helpers,
                         Map<String, Set<String>> helperLiterals,
                         List<String> unrecognized,
                         List<String> unresolvedKeyForms,
                         List<String> nodeConfigBinds) {
    }

    private static List<Source> loadSources() throws IOException {
        assertTrue(Files.isDirectory(AGENT_ROOT),
                "找不到 " + AGENT_ROOT.toAbsolutePath() + "（工作目录应是模块根，与 NodeIoContractTest 同一口径）");
        List<Path> files = new ArrayList<>();
        for (String dir : RUNTIME_DIRS) {
            Path base = AGENT_ROOT.resolve(dir);
            // 🔴 域自检：目录写错时 Files.walk 会抛，但过滤条件写窄时只会静少扫 ——
            //    少扫的强制点看着一样绿（_evidence/p97b-forms.py 第一版正是这么报出「0 处助手式读取」的，
            //    而当时已知触发器侧有 9 处）
            assertTrue(Files.isDirectory(base), "运行期目录不存在：" + base.toAbsolutePath());
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .forEach(files::add);
            }
        }
        List<Source> out = new ArrayList<>();
        for (Path p : files.stream().sorted().toList()) {
            String rel = AGENT_ROOT.relativize(p).toString().replace('\\', '/');
            if (rel.equals(AUTHORITY_FILE)) {
                continue;
            }
            out.add(parse(rel, Files.readString(p, StandardCharsets.UTF_8)));
        }
        // 下限 40：今天实测 48（49 个 .java 减去契约自己）。
        // ⚠️ 不是从 NodeIoContractTest#mainSources() 那个 100 抄来的 —— 那边扫整个 src/main/java（265 个），
        //    域不同阈值就不同；p97b-forms.py 第一版抄了 100，断言立刻红，红得对（阈值是从别的域抄来的）。
        assertTrue(out.size() >= 40,
                "只扫到 " + out.size() + " 个运行期源文件，少于 40 ⟹ 扫描口径坏了（别把这条断言删掉了事）");
        return out;
    }

    /**
     * 一次遍历产出两个视图。
     *
     * <p>为什么要两个：结构分析（括号配对、方法边界）必须看不见字符串正文，
     * 否则提示词里的 {@code "{"} 会把配对带偏（本域里 {@code AgentLoopPrompt} 有大段文本块，
     * {@code StateTemplateResolver} 有 {@code "{{"}）；而取键名又必须看得见字符串正文。
     * 两个视图都保持<b>等长</b>（换行原样保留），于是偏移量与行号在两边完全一致。
     *
     * <p>⚠️ 不能用 {@code line.replaceAll("//.*$", "")} 这种逐行去注释：
     * 它会吃掉字符串里的 {@code //}（提示词里的 URL），也保不住 javadoc 的行号。
     *
     * <p>⚠️ 必须处理文本块 {@code """}：本域里 {@code AgentLoopGraphBuilder} / {@code AgentLoopPrompt} 在用，
     * 而「三个引号」按普通字符串处理会解析成「空串 + 一段裸代码 + 空串」，
     * 提示词正文里的键名示例就全变成假读取点。
     */
    private static Source parse(String rel, String raw) {
        int n = raw.length();
        char[] code = raw.toCharArray();
        char[] text = raw.toCharArray();
        List<int[]> strings = new ArrayList<>();
        // 状态：0=代码 1=行注释 2=块注释 3=字符串 5=字符字面量（4 留给文本块，它在状态 0 里整块跳过）
        int st = 0;
        int strStart = -1;
        int i = 0;
        while (i < n) {
            char c = raw.charAt(i);
            char nx = i + 1 < n ? raw.charAt(i + 1) : '\0';
            char nx2 = i + 2 < n ? raw.charAt(i + 2) : '\0';
            switch (st) {
                case 0 -> {
                    if (c == '/' && nx == '/') {
                        blank(text, code, i, 2);
                        st = 1;
                        i += 2;
                    } else if (c == '/' && nx == '*') {
                        blank(text, code, i, 2);
                        st = 2;
                        i += 2;
                    } else if (c == '"' && nx == '"' && nx2 == '"') {
                        int[] sp = textBlockSpan(raw, i);
                        strings.add(sp);
                        for (int j = sp[0]; j < sp[1]; j++) {
                            boolean delim = j < sp[0] + 3 || j >= sp[1] - 3;
                            code[j] = raw.charAt(j) == '\n' ? '\n' : ' ';
                            if (!delim && raw.charAt(j) != '\n') {
                                text[j] = ' ';
                            }
                        }
                        i = sp[1];
                    } else if (c == '"') {
                        strStart = i;
                        code[i] = ' ';
                        st = 3;
                        i++;
                    } else if (c == '\'') {
                        code[i] = ' ';
                        st = 5;
                        i++;
                    } else {
                        i++;
                    }
                }
                case 1 -> {
                    if (c == '\n') {
                        st = 0;
                        i++;
                    } else {
                        text[i] = ' ';
                        code[i] = ' ';
                        i++;
                    }
                }
                case 2 -> {
                    if (c == '*' && nx == '/') {
                        blank(text, code, i, 2);
                        st = 0;
                        i += 2;
                    } else {
                        if (c != '\n') {
                            text[i] = ' ';
                        }
                        code[i] = c == '\n' ? '\n' : ' ';
                        i++;
                    }
                }
                case 3 -> {
                    code[i] = ' ';
                    if (c == '\\') {
                        if (i + 1 < n) {
                            code[i + 1] = ' ';
                        }
                        i += 2;
                    } else {
                        if (c == '"') {
                            strings.add(new int[]{strStart, i + 1});
                            st = 0;
                        }
                        i++;
                    }
                }
                default -> {              // 5 = 字符字面量
                    code[i] = ' ';
                    if (c == '\\') {
                        i += 2;
                    } else {
                        if (c == '\'') {
                            st = 0;
                        }
                        i++;
                    }
                }
            }
        }
        Source out = new Source(rel, new String(code), new String(text));
        out.strings.addAll(strings);
        out.methods.addAll(methods(out.code));
        return out;
    }

    private static void blank(char[] text, char[] code, int from, int len) {
        for (int j = from; j < from + len; j++) {
            text[j] = ' ';
            code[j] = ' ';
        }
    }

    /** 文本块 {@code """ … """} 的 [start,end)；未闭合就吃到文件尾（宁可整块当字符串，也不要漏出假键） */
    private static int[] textBlockSpan(String raw, int from) {
        int j = from + 3;
        while (j < raw.length()) {
            if (raw.charAt(j) == '\\' && j + 1 < raw.length()) {
                j += 2;
                continue;
            }
            if (raw.charAt(j) == '"' && j + 2 < raw.length()
                    && raw.charAt(j + 1) == '"' && raw.charAt(j + 2) == '"') {
                return new int[]{from, j + 3};
            }
            j++;
        }
        return new int[]{from, raw.length()};
    }

    private static final Set<String> KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "try", "do", "else", "return", "new", "assert");

    /**
     * 方法边界（签名起点、体起点、体终点、形参名列表）。
     *
     * <p>在 {@code code}（字符串正文已抹平）上做括号配对，所以 {@code "}"} 之类字面量不会带偏。
     * 判据是「{@code )} 之后（可跨 {@code throws} 子句）紧跟 <code>{</code>」——
     * 于是抽象方法、接口声明、普通调用都被排除，而多行签名（本域的编译器/GraphBuilder 大量这么写）能认出来。
     *
     * <p>⚠️ 第一版按行匹配「以 {@code ) &#123;} 结尾的行」，在 {@code addSubWorkflowNode} 这种
     * 签名跨 4 行、{@code throws} 单独一行的地方直接认错方法
     * （把 {@code workflowId}/{@code version} 两个读取点归给了上一个方法 {@code isControlFlowBodyType}）——
     * <b>个数没错、归属错了</b>，与「家族成员抄错而个数凑巧是 7」同型。
     */
    private static List<Method> methods(String code) {
        List<Method> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(").matcher(code);
        while (m.find()) {
            String name = m.group(1);
            if (KEYWORDS.contains(name)) {
                continue;
            }
            int open = m.end() - 1;
            int close = matchPair(code, open, '(', ')');
            if (close < 0) {
                continue;
            }
            int i = close + 1;
            while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
                i++;
            }
            int bodyStart = -1;
            if (i < code.length() && code.charAt(i) == '{') {
                bodyStart = i;
            } else if (code.startsWith("throws", i)) {
                int j = i;
                while (j < code.length() && code.charAt(j) != '{' && code.charAt(j) != ';') {
                    j++;
                }
                if (j < code.length() && code.charAt(j) == '{') {
                    bodyStart = j;
                }
            }
            if (bodyStart < 0) {
                continue;                       // 抽象方法 / 接口声明 / 普通调用
            }
            int bodyEnd = matchPair(code, bodyStart, '{', '}');
            if (bodyEnd < 0) {
                continue;
            }
            out.add(new Method(name, m.start(), bodyStart, bodyEnd,
                    paramNames(code.substring(open + 1, close))));
        }
        return out;
    }

    private static int matchPair(String s, int openIdx, char open, char close) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> paramNames(String signature) {
        List<String> names = new ArrayList<>();
        for (String arg : splitTopLevel(signature)) {
            String a = arg.trim();
            if (a.isEmpty()) {
                continue;
            }
            int lt = a.indexOf('<');
            int gt = lt < 0 ? -1 : a.indexOf('>', lt);
            if (lt >= 0 && gt > lt) {                   // 抹掉泛型实参，只留「类型 名字」
                a = a.substring(0, lt) + a.substring(gt + 1);
            }
            String[] toks = a.trim().split("\\s+");
            if (toks.length >= 2) {
                names.add(toks[toks.length - 1].replaceAll("\\.{3}$", ""));
            }
        }
        return names;
    }

    /** 按顶层逗号切分实参/形参（尊重括号与引号） */
    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '(' || c == '[' || c == '{' || c == '<') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}' || c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    // ---------- 家族与类型：一律现场派生 ----------

    /** {@code NodeDTO} 里声明的 config 访问器名（今天 7 个） */
    private static Set<String> configAccessorFamily() throws IOException {
        Source dto = parse(NODE_DTO_FILE,
                Files.readString(AGENT_ROOT.resolve(NODE_DTO_FILE), StandardCharsets.UTF_8));
        Set<String> family = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\bpublic\\s+[\\w<>,.\\s\\[\\]]+?\\s(config[A-Z]\\w*)\\s*\\(").matcher(dto.code);
        while (m.find()) {
            family.add(m.group(1));
        }
        assertTrue(family.contains("configStr") && family.contains("configMap"),
                "从 NodeDTO 派生 config 访问器家族失败（一个都没派生出来，或漏了最常用的两个）：" + family
                        + " ⟹ 派生正则坏了。家族一旦派生不全，正向差集就在残缺的域上做，而它只会<b>显得</b>更干净");
        assertFalse(family.contains("configKey"),
                "configKey 不是读 config 的方法（它是 NodeIoContract.OutPort 的 record 访问器）；"
                        + "派生把它带进来了 ⟹ 口径太宽。🔴 #97 第一次测量手抄时正是抄进了它、漏了 configBool");
        return family;
    }

    /**
     * {@code NodeDTO.config} 字段声明的值类型（今天 {@code Object}），供 {@link #couldBeConfigMap} 用。
     *
     * <p>派生而不是写死：这个值是「哪些 Map 形参<b>有可能</b>装着节点配置」的唯一判据，
     * 写死了就等于把形态 E 的域钉在一个会随 DTO 漂移的假设上。
     */
    private static String configMapValueType() throws IOException {
        Source dto = parse(NODE_DTO_FILE,
                Files.readString(AGENT_ROOT.resolve(NODE_DTO_FILE), StandardCharsets.UTF_8));
        Matcher m = Pattern.compile("\\bprivate\\s+Map\\s*<\\s*String\\s*,\\s*([\\w.]+)\\s*>\\s+config\\s*;")
                .matcher(dto.code);
        assertTrue(m.find(), "NodeDTO 里找不到 private Map<String, X> config; 的声明 ⟹ 形态 E 的判据无从派生。"
                + "字段改名/换类型的话，把 couldBeConfigMap 一起改，别留着一条恒假的收窄");
        return m.group(1);
    }

    /** 执行器文件 → 节点类型，由类自己的 {@code TYPE} 常量派生；顺带核对 {@code getType()} 返回的就是它 */
    private static Map<String, String> executorTypes() {
        Map<String, String> out = new LinkedHashMap<>();
        Pattern type = Pattern.compile("public\\s+static\\s+final\\s+String\\s+TYPE\\s*=\\s*\"([^\"]*)\"");
        Pattern getter = Pattern.compile("public\\s+String\\s+getType\\s*\\(\\s*\\)\\s*\\{\\s*return\\s+TYPE\\s*;");
        for (Source s : sources) {
            if (!s.rel.startsWith(EXECUTOR_IMPL_PREFIX)) {
                continue;
            }
            Matcher tm = type.matcher(s.text);
            assertTrue(tm.find(), s.rel + " 是 NodeExecutor 实现却没有 public static final String TYPE 常量，"
                    + "归属无从派生 ⟹ 要么补上 TYPE，要么把它写进 METHOD_TYPES");
            String t = tm.group(1);
            assertNotNull(NodeIoContract.of(t),
                    s.rel + " 的 TYPE=" + t + " 不是契约登记的类型 ⟹ 契约少了一种类型，或这个执行器已废弃");
            assertTrue(getter.matcher(s.text).find(),
                    s.rel + " 的 getType() 没有直接 return TYPE ⟹ 从 TYPE 派生出的类型可能与运行期注册的不一致");
            out.put(s.rel, t);
        }
        return out;
    }

    /** 契约登记的键：{@code InPort.key} ∪ {@code OutPort.configKey}（后者就是裁定③扩进来的那半） */
    private static Map<String, Set<String>> contractKeys() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (NodeIoContract.Contract c : NodeIoContract.all()) {
            Set<String> keys = new LinkedHashSet<>();
            c.inputs().forEach(p -> keys.add(p.key()));
            c.outputs().stream().map(NodeIoContract.OutPort::configKey)
                    .filter(k -> k != null && !k.isBlank()).forEach(keys::add);
            out.put(c.type(), keys);
        }
        return out;
    }

    private static Set<String> contractKeyUniverse() {
        Set<String> universe = new TreeSet<>();
        contractKeys().values().forEach(universe::addAll);
        return universe;
    }

    // ---------- 读取点 ----------

    private static final Pattern FORM_A = Pattern.compile("\\.(config[A-Z]\\w*)\\s*\\(\\s*\"([A-Za-z_]\\w*)\"");
    private static final Pattern FORM_A_ANY = Pattern.compile("\\.(config[A-Z]\\w*)\\s*\\(");
    private static final Pattern FORM_B =
            Pattern.compile("getConfig\\s*\\(\\s*\\)\\s*\\.\\s*get\\s*\\(\\s*\"([A-Za-z_]\\w*)\"");
    private static final Pattern FORM_B_ID =
            Pattern.compile("getConfig\\s*\\(\\s*\\)\\s*\\.\\s*get\\s*\\(\\s*([A-Za-z_]\\w*)\\s*\\)");
    private static final Pattern FORM_D_BIND =
            Pattern.compile("Map\\s*<[^;{]*?>\\s+(\\w+)\\s*=\\s*(\\w+)\\s*\\.\\s*getConfig\\s*\\(\\s*\\)");

    private static List<Site> readSites(Source s) {
        List<Site> out = new ArrayList<>();
        Matcher a = FORM_A.matcher(s.text);
        while (a.find()) {
            if (s.inString(a.start()) || !configFamily.contains(a.group(1))) {
                continue;                       // 字符串里的示例 / 别的类上恰好叫 configXxx 的方法
            }
            Site site = new Site(s, a.start(2), methodAt(s, a.start(2)), "A." + a.group(1), a.group(2), true, null);
            out.add(attributed(s, site));
        }
        Matcher b = FORM_B.matcher(s.text);
        while (b.find()) {
            if (s.inString(b.start())) {
                continue;
            }
            Site site = new Site(s, b.start(1), methodAt(s, b.start(1)), "B.getConfig().get", b.group(1), true, null);
            out.add(attributed(s, site));
        }
        Matcher c = FORM_B_ID.matcher(s.text);
        while (c.find()) {
            if (s.inString(c.start())) {
                continue;
            }
            out.addAll(resolveParamKeys(s, c.start(1), c.group(1)));
        }
        return out;
    }

    /** 归属在构造之后补：Site 是不可变的，但 types 依赖 method，而 method 依赖 offset，故分两步 */
    private static Site attributed(Source s, Site draft) {
        return new Site(s, draft.keyOffsets.get(0), draft.method, draft.form, draft.key, draft.resolved,
                attribute(s, draft.method));
    }

    private static Set<String> attribute(Source s, String method) {
        if (s.rel.startsWith(EXECUTOR_IMPL_PREFIX)) {
            String t = executorTypeByFile.get(s.rel);
            return t == null ? null : Set.of(t);
        }
        return METHOD_TYPES.get(s.fileName + "#" + method);
    }

    /**
     * 形态 C：{@code getConfig().get(形参)} —— 找到形参所在方法的签名 → 形参<b>位置</b> →
     * 该方法在运行期域里的所有调用点 → 取同一位置的字面量。
     *
     * <p>🔴 两处坑，都是第一版踩过的：
     * ① 形参位置必须逐个记下来再按位置取实参。第一版写「签名里第一个 String 形参」，
     *    在 {@code validateMapping(NodeDTO node, String field, String subNodeId)} 上抓到的是 {@code subNodeId}
     *    ⟹ 那处读取点被报成「形参解不出」。<b>个数对得上、原因写错了</b>，
     *    与「家族成员抄错而个数凑巧是 7」同型。
     * ② 方法<b>声明行</b>本身长得就像一次调用（{@code validateMapping(node, String field, …)}），
     *    必须按签名起点剔掉，否则会多报一条「实参不是字面量」。
     */
    private static List<Site> resolveParamKeys(Source s, int offset, String param) {
        Method owner = innermost(s, offset);
        String form = "C.getConfig().get(形参)";
        if (owner == null) {
            return List.of(new Site(s, offset, NO_METHOD, form + " —— 不在任何方法里", param, false, null));
        }
        int idx = owner.params.indexOf(param);
        if (idx < 0) {
            return List.of(new Site(s, offset, owner.name,
                    form + " —— 签名里没有形参 " + param, param, false, attribute(s, owner.name)));
        }
        List<Site> out = new ArrayList<>();
        Pattern call = Pattern.compile("\\b" + Pattern.quote(owner.name) + "\\s*\\(");
        Set<Integer> declOffsets = new HashSet<>();
        for (Method m : s.methods) {
            if (m.name.equals(owner.name)) {
                declOffsets.add(m.sigStart);
            }
        }
        for (Source other : callSiteScope) {
            Matcher cm = call.matcher(other.code);
            while (cm.find()) {
                if (other == s && declOffsets.contains(cm.start())) {
                    continue;                       // 声明本身
                }
                int open = cm.end() - 1;
                int close = matchPair(other.code, open, '(', ')');
                if (close < 0) {
                    continue;
                }
                List<String> args = splitTopLevel(other.text.substring(open + 1, close));
                if (idx >= args.size()) {
                    continue;
                }
                Matcher lit = Pattern.compile("\"([A-Za-z_]\\w*)\"").matcher(args.get(idx).trim());
                int argKeyOffset = nthArgOffset(other.text, open + 1, idx);
                String where = other.fileName + ":" + other.lineOf(cm.start());
                if (lit.matches()) {
                    Site site = new Site(s, offset, owner.name,
                            form + "→" + where + " 的字面量", lit.group(1), true, attribute(s, owner.name));
                    if (argKeyOffset >= 0) {
                        site.keyOffsets.add(argKeyOffset);      // 调用点那个字面量也算「已识别」，别在噪声扫描里再报一遍
                    }
                    out.add(site);
                } else {
                    out.add(new Site(s, offset, owner.name,
                            form + " —— " + where + " 的实参不是字面量：" + args.get(idx).trim(),
                            param, false, attribute(s, owner.name)));
                }
            }
        }
        if (out.isEmpty()) {
            out.add(new Site(s, offset, owner.name, form + " —— 全运行期域里找不到 " + owner.name
                    + " 的调用点", param, false, attribute(s, owner.name)));
        }
        return out;
    }

    /** 最内层包住 offset 的方法；不在任何方法里（字段初始化 / static 块）返回 null */
    private static Method innermost(Source s, int offset) {
        Method best = null;
        for (Method m : s.methods) {
            if (m.sigStart <= offset && offset < m.bodyEnd && (best == null || m.sigStart > best.sigStart)) {
                best = m;
            }
        }
        return best;
    }

    private static final String NO_METHOD = "<非方法体>";

    /** 第 idx 个顶层实参里第一个字符串字面量的<b>内容</b>起点偏移；解不出返回 -1 */
    private static int nthArgOffset(String text, int argsStart, int idx) {
        int depth = 0;
        int seen = 0;
        boolean inStr = false;
        for (int i = argsStart; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                if (seen == idx) {
                    return i + 1;
                }
                inStr = true;
            } else if (c == '(' || c == '[' || c == '{' || c == '<') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}' || c == '>') {
                if (depth == 0) {
                    return -1;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                seen++;
            }
        }
        return -1;
    }

    // ---------- 未识别形态的扫描（域自检） ----------

    private static final Pattern KEY_SHAPED = Pattern.compile("[a-z][A-Za-z0-9_]*");
    private static final Pattern CALLEE = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(\\s*$");
    private static final Pattern LITERAL = Pattern.compile("\"([A-Za-z_]\\w*)\"");

    private static Sweep noiseSweep() {
        Set<String> recognized = new HashSet<>();
        for (Site s : sites) {
            for (int off : s.keyOffsets) {
                recognized.add(s.file.rel + "@" + off);
            }
        }
        Map<String, Set<String>> noiseSites = new TreeMap<>();
        Set<String> usedCallees = new TreeSet<>();
        Set<String> usedKeyLiterals = new TreeSet<>();
        List<String> unrecognized = new ArrayList<>();
        List<String> unresolvedKeyForms = new ArrayList<>();

        for (Source s : sources) {
            // 形态 A'：家族访问器的第一个实参不是字面量 ⟹ 键名解不出来
            Matcher any = FORM_A_ANY.matcher(s.text);
            while (any.find()) {
                if (s.inString(any.start()) || !configFamily.contains(any.group(1))) {
                    continue;
                }
                int j = any.end();
                while (j < s.text.length() && Character.isWhitespace(s.text.charAt(j))) {
                    j++;
                }
                if (j < s.text.length() && s.text.charAt(j) != '"') {
                    unresolvedKeyForms.add("%s:%d .%s(…) —— 第一个实参不是字符串字面量".formatted(
                            s.fileName, s.lineOf(any.start()), any.group(1)));
                }
            }
            collectUnrecognized(s, recognized, noiseSites, usedCallees, usedKeyLiterals, unrecognized);
        }

        // 形态 D：把 getConfig() 存进局部变量。owner 的声明类型是 NodeDTO 的话，本类看不见它的键
        List<String> nodeConfigBinds = new ArrayList<>();
        for (Source s : sources) {
            nodeConfigBinds.addAll(nodeConfigBindsIn(s));
        }

        // 形态 E：键以形参到达某个 Map 助手
        HelperScan formE = formEScan();
        return new Sweep(noiseSites, usedCallees, usedKeyLiterals, formE.helpers(), formE.literals(),
                unrecognized, unresolvedKeyForms, nodeConfigBinds);
    }

    /** 形态 E 的两件产出：助手 id 集合 + 每个助手调用点上传进来的键字面量 */
    private record HelperScan(Set<String> helpers, Map<String, Set<String>> literals) {
    }

    /**
     * 形态 E 的<b>唯一</b>一份实现：{@link #noiseSweep}（真扫描）与 {@link #helpersIn}/{@link #helperLiteralsIn}
     * （探测器自检）共用，避免「自检绿而真扫描坏」。
     */
    private static HelperScan formEScan() {
        Set<String> helpers = new TreeSet<>();
        Map<String, Set<String>> helperLiterals = new TreeMap<>();
        for (Source s : callSiteScope) {
            for (Helper h : keyParamHelpers(s)) {
                String id = s.fileName + "#" + h.method().name;
                helpers.add(id);
                Pattern call = Pattern.compile("\\b" + Pattern.quote(h.method().name) + "\\s*\\(");
                Set<Integer> declOffsets = new HashSet<>();
                for (Method dm : s.methods) {
                    if (dm.name.equals(h.method().name)) {
                        declOffsets.add(dm.sigStart);
                    }
                }
                for (Source other : h.fileScoped() ? List.of(s) : callSiteScope) {
                    Matcher cm = call.matcher(other.code);
                    while (cm.find()) {
                        if (other == s && declOffsets.contains(cm.start())) {
                            continue;
                        }
                        int open = cm.end() - 1;
                        int close = matchPair(other.code, open, '(', ')');
                        if (close < 0) {
                            continue;
                        }
                        List<String> args = splitTopLevel(other.text.substring(open + 1, close));
                        if (h.keyArgIndex() >= args.size()) {
                            continue;
                        }
                        Matcher lit = LITERAL.matcher(args.get(h.keyArgIndex()).trim());
                        if (lit.matches()) {
                            helperLiterals.computeIfAbsent(id, k -> new TreeSet<>()).add(lit.group(1));
                        }
                    }
                }
            }
        }
        return new HelperScan(helpers, helperLiterals);
    }

    /** 一个形态 E 候选：{@code method} 的 {@code mapParam} 用第 {@code keyArgIndex} 个实参当键去取值 */
    private record Helper(Method method, String mapParam, int keyArgIndex, boolean fileScoped) {
    }

    private static final Pattern MAP_PARAM = Pattern.compile("(Map\\s*(?:<[^>;{}]*>)?)\\s+");

    /**
     * 找出「键以形参到达某个 Map 助手」的方法 —— 形态 E 的<b>唯一</b>判据（noiseSweep 与自检共用这一份）。
     *
     * <p>🔴 判据里那刀「Map 的值类型必须是 {@code getConfig()} 那个」不是可有可无的收窄：
     * 只看结构（有 Map 形参 + 体内 {@code map.get(另一个形参)}）会把
     * {@code DslToStateGraphCompiler#redirectFrom/#redirectTo} 也算进来，而它们的 Map 是
     * 「子流程端点重定向表」{@code Map<String, String[]>}、键是<b>节点 id</b> —— 纯形状巧合。
     * 排除它靠的是类型系统而不是约定：{@code NodeDTO.config} 声明为 {@code Map<String, Object>}，
     * Java 不允许把它传给 {@code Map<String, String[]>} 形参。
     */
    private static List<Helper> keyParamHelpers(Source s) {
        List<Helper> out = new ArrayList<>();
        for (Method m : s.methods) {
            String signature = s.text.substring(m.sigStart, m.bodyStart);
            String mapParam = null;
            for (String p : m.params) {
                Matcher pm = MAP_PARAM.matcher(signature);
                while (pm.find()) {
                    if (signature.regionMatches(pm.end(), p, 0, p.length())
                            && !Character.isJavaIdentifierPart(charAt(signature, pm.end() + p.length()))
                            && couldBeConfigMap(pm.group(1))) {
                        mapParam = p;
                        break;
                    }
                }
                if (mapParam != null) {
                    break;
                }
            }
            if (mapParam == null) {
                continue;
            }
            String body = s.code.substring(m.bodyStart, m.bodyEnd);
            if (body.contains("getConfig()")) {
                continue;                           // 形态 C，已由 readSites 解出
            }
            Matcher gm = Pattern.compile("\\b" + Pattern.quote(mapParam)
                    + "\\s*\\.\\s*get\\s*\\(\\s*([A-Za-z_]\\w*)\\s*\\)").matcher(body);
            if (!gm.find() || !m.params.contains(gm.group(1))) {
                continue;                           // 键是字面量（形态 D 的语义）或压根没读
            }
            out.add(new Helper(m, mapParam, m.params.indexOf(gm.group(1)), isPrivate(s, m)));
        }
        return out;
    }

    /**
     * 这个方法是不是 {@code private} —— 决定形态 E 找调用点时的搜索范围。
     *
     * <p>🔴 不分可见性会量错：两个助手今天恰好<b>同名</b>（都叫 {@code str}），
     * 只按方法名找调用点就会把 9 个调用点算到每一个头上（实测报成「各 7 个字面量」，
     * 而 {@code AgentWebhookTriggerServiceImpl#str} 其实只有 authType/secret 两个）。
     * 对第 ④ 条断言来说这是<b>偏严</b>（不会假绿），但报告里的数就是错的，
     * 而「量错了还写进文档」正是这条线上反复犯的病。
     *
     * <p>只在 {@code private} 时收窄到本文件是安全的：private 方法不可能被别的文件调用。
     * 窗口截到上一个 {@code }}/{@code ;} 之后，且取 {@code code}（注释已抹平）——
     * 否则 javadoc 里出现「private」这个词会误判。
     */
    private static boolean isPrivate(Source s, Method m) {
        String prefix = s.code.substring(Math.max(0, m.sigStart - 120), m.sigStart);
        int cut = Math.max(prefix.lastIndexOf('}'), prefix.lastIndexOf(';'));
        return prefix.substring(cut + 1).contains("private");
    }

    private static char charAt(String s, int i) {
        return i < s.length() ? s.charAt(i) : ' ';
    }

    /** 这个 Map 声明有没有可能装着 {@code getConfig()} 的返回值（见 {@link #keyParamHelpers}） */
    private static boolean couldBeConfigMap(String declared) {
        String t = declared.replaceAll("\\s+", "");
        if (t.equals("Map")) {
            return true;                            // 裸 Map：什么都能装，宁可报出来
        }
        Matcher m = Pattern.compile("Map<([^,<>]*),([^,<>]*)>").matcher(t);
        if (!m.matches()) {
            return false;
        }
        String key = m.group(1);
        String val = m.group(2);
        return (key.equals("String") || key.equals("?"))
                && (val.startsWith("?") || val.equals(configMapValueType));
    }

    /** 把「小写驼峰形状、且不在已识别读取点上」的字面量按 {@code 文件#callee#键} 归桶 */
    private static void collectUnrecognized(Source s, Set<String> recognized,
                                            Map<String, Set<String>> noiseSites,
                                            Set<String> usedCallees, Set<String> usedKeyLiterals,
                                            List<String> unrecognized) {
        for (int[] sp : s.strings) {
            if (sp[1] - sp[0] > 200) {
                continue;                           // 文本块：正文已抹平，剩下的不可能是键
            }
            String lit = s.text.substring(sp[0] + 1, sp[1] - 1);
            if (!KEY_SHAPED.matcher(lit).matches() || recognized.contains(s.rel + "@" + (sp[0] + 1))) {
                continue;
            }
            String callee = calleeOf(s, sp[0]);
            if (callee == null) {
                continue;                           // 注解实参：不可能是读法
            }
            String where = s.fileName + "#" + callee + "#" + lit;
            noiseSites.computeIfAbsent(where, k -> new TreeSet<>()).add(lit);
            if (configFamily.contains(callee)) {
                // 派生规则（不进 NOISE_CALLEES，那 7 个名字是从 NodeDTO 现场量的）：
                // 访问器的第一个实参才是键，而它已经在 sites 的 keyOffsets 里被 recognized 掉了 ⟹
                // 走到这里的一定是「非第一个实参」，也就是两参重载的<b>默认值</b>。
                // 今天 1 处：compiler:508 parNode.configStr("join", "all") 的 "all"。
                // ⚠️ 仍然进 noiseSites：默认值恰好等于契约键时（configStr("k","default")）第 ③ 条照样要求逐处认领。
                usedCallees.add(callee);
                usedKeyLiterals.add(where);
            } else if (NOISE_CALLEES.containsKey(callee)) {
                usedCallees.add(callee);
                usedKeyLiterals.add(where);
            } else {
                unrecognized.add("%s:%d %s(\"%s\")".formatted(s.fileName, s.lineOf(sp[0]), callee, lit));
            }
        }
    }

    /** owner 声明类型是 {@code NodeDTO} 的 {@code getConfig()} 局部绑定（形态 D 的危险形态） */
    private static List<String> nodeConfigBindsIn(Source s) {
        List<String> out = new ArrayList<>();
        Matcher d = FORM_D_BIND.matcher(s.text);
        while (d.find()) {
            if (s.inString(d.start())) {
                continue;
            }
            if ("NodeDTO".equals(declaredTypeOf(s, d.start(), d.group(2)))) {
                out.add("%s:%d Map … %s = %s.getConfig()".formatted(
                        s.fileName, s.lineOf(d.start()), d.group(1), d.group(2)));
            }
        }
        return out;
    }

    /**
     * 字面量所在那个调用的被调方法名；不在任何调用的实参位上就是 {@link #BARE}；注解实参返回 {@code null}。
     *
     * <p>从字面量往回走到第一个「深度 0」的左括号。括号深度用 {@code code}（字符串正文已抹平）算，
     * 所以实参里的 {@code ")"} 不会把深度带偏。
     */
    private static String calleeOf(Source s, int literalStart) {
        int depth = 0;
        for (int i = literalStart - 1; i >= 0; i--) {
            char c = s.code.charAt(i);
            if (c == ')' || c == ']' || c == '}') {
                depth++;
            } else if (c == '(' || c == '[' || c == '{') {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                if (c != '(') {
                    return BARE;
                }
                int at = s.code.lastIndexOf('@', i);
                if (at >= 0 && s.code.substring(at, i).trim().matches("@\\w*")) {
                    return null;                    // 注解实参
                }
                Matcher m = CALLEE.matcher(s.code.substring(Math.max(0, i - 80), i + 1));
                if (!m.find()) {
                    return BARE;
                }
                String name = m.group(1);
                return KEYWORDS.contains(name) ? BARE : name;
            } else if (c == ';' && depth == 0) {
                return BARE;                        // 上一条语句已结束 ⟹ 裸字面量
            }
        }
        return BARE;
    }

    /** 变量 {@code name} 在 offset 处的声明类型（局部声明或形参）；找不到返回 null */
    private static String declaredTypeOf(Source s, int offset, String name) {
        Matcher m = Pattern.compile("\\b([A-Z]\\w*)\\s*(?:<[^;{]*?>)?\\s+" + Pattern.quote(name) + "\\b")
                .matcher(s.code.substring(0, offset));
        String type = null;
        while (m.find()) {
            type = m.group(1);
        }
        return type;
    }

    // ---------- 派生的中间结果 ----------

    private static Map<String, Set<String>> keysReadByType() {
        Map<String, Set<String>> out = new TreeMap<>();
        for (Site s : sites) {
            if (!s.resolved || s.types == null) {
                continue;
            }
            for (String t : s.types) {
                out.computeIfAbsent(t, k -> new TreeSet<>()).add(s.key);
            }
        }
        return out;
    }

    /** 「一个方法读多种类型的键」的方法集合 —— 本类唯一的松散处 */
    private static Set<String> multiTypeMethods() {
        Set<String> out = new TreeSet<>();
        for (Site s : sites) {
            if (s.types != null && s.types.size() > 1) {
                out.add(s.file.fileName + "#" + s.method);
            }
        }
        METHOD_TYPES.forEach((k, v) -> {
            if (v.size() > 1) {
                out.add(k);
            }
        });
        return out;
    }

    /** 受多类型归属波及的类型（拿不到「精确相等」那半保证的那几个） */
    private static Set<String> weakTier() {
        Set<String> out = new TreeSet<>();
        for (Site s : sites) {
            if (s.types != null && s.types.size() > 1) {
                out.addAll(s.types);
            }
        }
        return out;
    }

    // ---------- 探测器自检用的最小入口 ----------

    /**
     * 对一段合成源码跑<b>同一套</b>提取逻辑 —— 探测器自检用它，不走 {@link #sources}。
     *
     * <p>🔴 必须走同一套代码：另写一份「测试用的小扫描器」就等于造了第二个口径，
     * 两个口径分岔时自检绿而真扫描坏（与 #98 那条「值比对在引用常量后恒真」同型）。
     *
     * <p>⚠️ 期间把 {@link #callSiteScope} 换成只含这段合成源码：形态 C 要全域找调用点，
     * 而自检片段里那个方法名（{@code validateMapping}）在真编译器里<b>也存在</b>，
     * 不换范围就会把真调用点的字面量一起收进来，自检的期望值变成两份之和。
     */
    private static Source snippet(String src) {
        return snippets(Map.of("Snippet.java", src)).get(0);
    }

    /**
     * 多文件版的 {@link #snippet}：形态 E 的调用点范围要按<b>可见性</b>收窄，
     * 而「private 只在本文件找 / 非 private 跨文件找」这件事在单文件片段里根本观察不到。
     */
    private static List<Source> snippets(Map<String, String> files) {
        List<Source> parsed = new ArrayList<>();
        new TreeMap<>(files).forEach((name, src) -> parsed.add(parse(name, src)));
        if (configFamily == null) {
            configFamily = Set.of("configStr", "configInt", "configLong",
                    "configDouble", "configBool", "configMap", "configList");
        }
        callSiteScope = parsed;
        return parsed;
    }

    private static void restore(Source s) {
        callSiteScope = sources == null ? List.of(s) : sources;
    }

    private static List<String> keysIn(String src) {
        Source s = snippet(src);
        try {
            List<String> keys = new ArrayList<>();
            for (Site site : readSites(s)) {
                if (site.resolved) {
                    keys.add(site.key);
                }
            }
            keys.sort(String::compareTo);
            return keys;
        } finally {
            restore(s);
        }
    }

    /**
     * {@link #keysIn} 的带归属版：返回排序后的 {@code 方法名#键}。
     *
     * <p>🔴 验「括号配对没被字符串带偏」必须用它，不能用 {@code keysIn}：{@code keysIn} 只看键在不在，
     * 而方法边界算错只会让 {@link #methodAt} 退化成 {@code <非方法体>}，键照样一个不少 ⟹ 那条断言恒绿。
     * 本类第一次跑就是这么假绿过去的（探针 R19 变异生效、断言不动）。
     */
    private static List<String> keysWithMethodIn(String src) {
        Source s = snippet(src);
        try {
            List<String> keys = new ArrayList<>();
            for (Site site : readSites(s)) {
                if (site.resolved) {
                    keys.add(site.method + "#" + site.key);
                }
            }
            keys.sort(String::compareTo);
            return keys;
        } finally {
            restore(s);
        }
    }

    private static List<String> bindsIn(String src) {
        Source s = snippet(src);
        try {
            return nodeConfigBindsIn(s);
        } finally {
            restore(s);
        }
    }

    private static Set<String> helpersIn(String src) {
        Source s = snippet(src);
        try {
            return formEScan().helpers();
        } finally {
            restore(s);
        }
    }

    private static Map<String, Set<String>> helperLiteralsIn(String src) {
        Source s = snippet(src);
        try {
            return formEScan().literals();
        } finally {
            restore(s);
        }
    }

    private static Map<String, Set<String>> helperLiteralsIn(Map<String, String> files) {
        List<Source> parsed = snippets(files);
        try {
            return formEScan().literals();
        } finally {
            restore(parsed.get(0));
        }
    }

    private static List<String> unrecognizedIn(String src) {
        Source s = snippet(src);
        try {
            List<String> out = new ArrayList<>();
            collectUnrecognized(s, recognizedIn(s), new TreeMap<>(), new TreeSet<>(), new TreeSet<>(), out);
            return out;
        } finally {
            restore(s);
        }
    }

    /** 同一段合成源码里「进了噪声桶」的字面量（{@code 文件#被调方法#字面量}） */
    private static Set<String> noiseKeysIn(String src) {
        Source s = snippet(src);
        try {
            Map<String, Set<String>> noise = new TreeMap<>();
            collectUnrecognized(s, recognizedIn(s), noise, new TreeSet<>(), new TreeSet<>(), new ArrayList<>());
            return noise.keySet();
        } finally {
            restore(s);
        }
    }

    /** 与 {@link #noiseSweep} 同一口径地算出「已识别读取点」的偏移，自检才不会把键本身也算成噪声 */
    private static Set<String> recognizedIn(Source s) {
        Set<String> recognized = new HashSet<>();
        for (Site site : readSites(s)) {
            for (int off : site.keyOffsets) {
                recognized.add(s.rel + "@" + off);
            }
        }
        return recognized;
    }

    /** 偏移 → 所在方法名；不在任何方法里（字段初始化器等）返回 {@link #NO_METHOD} */
    private static String methodAt(Source s, int offset) {
        Method m = innermost(s, offset);
        return m == null ? NO_METHOD : m.name;
    }
}
