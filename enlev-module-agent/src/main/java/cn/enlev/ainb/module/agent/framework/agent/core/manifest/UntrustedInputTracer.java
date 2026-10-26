package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.GraphReachability;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.TriggerDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.KnowledgeRetrievalNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.LlmConditionNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.LlmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ScriptNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ToolNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.io.NodeIoContract;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.StateTemplateResolver;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.UntrustedInput;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 不可信输入的数据流溯源（方案 §3-L2 的 {@code untrustedInputs[]}，V16 / V17 / X2 的共同数据源）
 *
 * <h3>它回答的问题，与 {@code changes} 正交</h3>
 * {@code changes} 说「这条流程会改什么」；本类说「改的时候喂进去的值，有多少是<b>别人能写的</b>」。
 * 一条 webhook 触发的建表流程，清单上只有一行「将新建 1 张数据表」，看着人畜无害；
 * 而真正该让审批人停下来的那句是「表名来自请求体」。G25 认定的五个 LLM 输入面
 * （webhook body / form_event 表单值 / 知识库检索片段 / 工具返回值 / 聊天用户输入）
 * 至今<b>零防护</b>，全仓 grep 无 injection / sanitize / untrusted 相关实现 —— 本类是第一步：
 * 先让人<b>看得见</b>这条线，S12 才谈得上「进 state 前打标 + prompt 装配处做指令/数据分离」。
 *
 * <h3>🔴 一次遍历两用，故并入 S2 而不另开刀</h3>
 * 溯源要的三样东西 manifest 已经在算了：整份 DSL、{@link GraphReachability} 的图、
 * 以及「哪个节点产出哪个变量」（{@code WorkflowDslValidator#validateReferenceReachability}
 * 建 producers 表用的正是 {@link NodeIoContract} 的输出端口）。分开做要遍历两遍，
 * 而两遍的可达性判定会各自漂移 —— 那就是 D-new7 收编前的老毛病。
 * 所以本类<b>不是</b> {@code @Component}：它零依赖、零查询、纯静态，与 {@link ManifestScoper} 同形，
 * 由 {@code WorkflowChangeManifestBuilder} 在干跑之后调一次。
 *
 * <h3>🔴 两个公开入口，一份实现（台账 C9）</h3>
 * 上面那句「调一次」曾同时是一条<b>射程声明</b>：{@link #trace(ManifestContext)} 是唯一入口，
 * 于是「没有清单的地方就没有污点追踪」—— 而 AI 自修正回路 {@code WorkflowPlannerServiceImpl#validateTwoLayer}
 * 恰恰刻意不建清单（建一份要跑最多 15 秒的只读干跑，那条回路最多 6 轮），
 * 结果 V16 在 AI 生成的草稿上<b>一句话都不说</b>。
 * 但溯源自己<b>零查询、零干跑</b>：它要的那三样东西里，只有「图」需要现算，而
 * {@code GraphReachability.of(def)} 是一次纯内存的邻接表构造。
 * 所以「不建清单」与「不溯源」从来是两件事，把它们绑在一起才是缺陷。
 * 现在 {@link #trace(WorkflowDefDTO)} 是第二个入口，两个入口都汇进同一个 private 实现 ——
 * 不是两份代码「碰巧同值」，而是结构上没有第二份可漂。
 * 强制点是 {@code UntrustedInputTracerTest#bothEntriesProduceByteIdenticalFlows}
 * （同一份 DSL 走两个入口 → 逐条逐字段相同）。
 *
 * <h3>🔴 只报运行期真会流动的路径</h3>
 * 这条纪律与「宁可多报」并不矛盾，方向要分清：<b>报一条不会发生的流向</b>和<b>漏一条会发生的</b>
 * 一样坏，因为它同样让人做错决定（V16 永远是 warning、从不阻断，但它在 AI 生成路径上会随
 * {@code toFeedback()} 回灌给模型 —— 误报的代价是让模型白改一轮，并在草稿上留一条没人能修掉的噪音）。
 * 故两个刻意的排除：
 * <ul>
 *   <li>{@code hitl.bpm_approval.formVars} <b>不是</b>汇 —— 那些值压根不过
 *       {@link StateTemplateResolver}（{@code AgentBpmApprovalService.resolveConfig} 原样取、
 *       {@code createApproval} 原样 setVariables）。把它算成汇，等于告诉审批人
 *       「注入内容会出现在你眼前」，而实际他会看到字面串 {@code {{$.input}}}。
 *       <b>直接后果：V17 目前不可能触发</b>，已登记待裁定（要么让 formVars 真解析、要么把 V17 降级）。</li>
 *   <li>{@code tool.args} 只看<b>顶层</b>字符串值 —— {@code ToolNodeExecutor#execute} 只对顶层
 *       {@code String} 做替换，嵌在对象/数组里的占位符原样传下去。递归进去扫就是报一条不会流动的路径。</li>
 * </ul>
 *
 * <h3>🔴 读/写集一律从 {@link NodeIoContract} 派生，不另立第二份口径</h3>
 * 「哪个 config 键运行期会被解析」= 契约里 {@code mode != literal} 的入端口；
 * 「这个节点往哪些 state 键写」= 契约的输出端口（{@code configKey} 配了就用配的、否则用 {@code defaultName}）。
 * 两者都是既有事实的<b>唯一</b>登记处，且 {@code defaultName} 已经改成引用执行器常量
 * （{@code NodeIoContract} 类注释里「🔴 输出端口的默认名一律引用执行器的常量」那一节。
 * 这里刻意<b>不</b>写行号：原先写的 {@code :28-36} 已实测漂到 {@code :30-38}，台账 #102）
 * —— 那次修正的直接受益者之一就是本类：
 * {@code tool}/{@code llm} 的默认名曾写着 null，于是所有「读契约做分析」的代码都看不见
 * {@code tool_result}/{@code llm_output}，污点会在这两个键上凭空断流。
 *
 * <p>⚠️ 本类另有一份补充表（{@link #addUnregisteredReads} / {@link #addUnregisteredWrites}），
 * 原本是 S9-① 那条缝（契约登记不全）的临时替身。但台账 #97 补齐契约之后<b>实测删不掉、只能缩小</b>：
 * 现在留下的每一条，处理的都是契约<b>已登记、却刻意登记成 {@code MODE_LITERAL}</b> 的键
 * （{@code script.code}、{@code sub_workflow.outputMap}、{@code iteration.itemVar}）——
 * 通用循环的判据是「literal ⟹ 读它也不会流动」，而它把「压根不解析」与「解析、但换个作用域/换个语义」
 * 混成了一种，契约的三值 mode 分不开这两者。逐条理由写在那两个方法自己的注释里，这里不抄第二份。
 *
 * <p>🔴 于是「契约补齐 ⟹ 补充表可删」这个推论是<b>假的</b>。而当初写下它时还搭了一句更假的：
 * 说 {@code UntrustedInputTracerTest#everyReferenceableInPortIsTracedToAKnownSink} 那条遍历断言
 * 「同时也是补充表的失效探针 —— 哪天 {@code inputMap} 进了契约，那条断言会提醒来这里删」。
 * 2026-10-03 把 {@code inputMap} 那一支原样加回去实测：红的<b>不是</b>它，而是
 * {@code subWorkflowInputMapIsASinkButOutputMapIsNot} 的 {@code assertEquals(1, flows.size())}。
 * 遍历断言红不了，有两个各自独立、单独一个就足以让它哑掉的原因：
 * <ol>
 *   <li>它的 {@code actual} 是 {@code LinkedHashSet} ⟹ 多出来的<b>重复</b>流向被去重掉了；</li>
 *   <li>它给 {@code inputMap} 填的 fixture 值是<b>字符串</b> {@code "{{$.input}}"}，而
 *       {@link #addMappingReads} 只认 Map 值 ⟹ 那一支在这个 fixture 下压根不产出。</li>
 * </ol>
 * ⚠️ 真会红的那条，消息原本写的是「outputMap 报出来只是噪音」，指向<b>另一件事</b>；
 * 而实测打出来的是两条一模一样的 {@code sub#inputMap.payload}。消息已改成两种可能都点出来 ——
 * 否则探针虽然会响，却把人往错的方向带（与 #98「值比对在引用常量后恒真」同族：
 * 「有这条断言」和「这条断言能把读者指对」是两件事）。
 *
 * <h3>算法：播种 → 不动点传播 → 出流</h3>
 * <ol>
 *   <li><b>播种</b>：触发器入口变量、{@code tool} 与 {@code knowledge_retrieval} 的产出键，各记一个 depth 0 的污点；</li>
 *   <li><b>不动点</b>：某节点读到的键被污染 ⟹ 它的产出键也被污染（depth + 1）。
 *       🔴 用不动点而不是「按 DSL 顺序扫一遍」：节点在数组里的顺序与执行顺序无关
 *       （画布上完全可以把下游排在前面），一遍扫描会漏掉顺序反了的传播链。
 *       环安全靠「同一 (源, 源变量) 只留 depth 最小的那条」——
 *       depth 只减不增且源集有限，故必然收敛，不需要深度上限（同 {@link GraphReachability} 的 BFS 纪律）；</li>
 *   <li><b>出流</b>：再走一遍，凡是「汇」读到了污染键就产出一条 {@link UntrustedInput}。</li>
 * </ol>
 *
 * <p>传播与出流都受<b>上游可达性</b>约束：读的键若有产出者，则至少一个产出者必须在
 * {@code graph.ancestorsOf(本节点)} 里，否则「运行到这里时它还没有值」，那条流向不会发生。
 * 这与 {@code WorkflowDslValidator} 引用可达性用的是同一份 {@link GraphReachability}、同一条判据。
 *
 * <h3>⚠️ 已知的盲区（第 1~4 条是「少报」方向，第 5 条多报；
 * 逐个写在这里而不是藏在代码里）</h3>
 * <ol>
 *   <li><b>{@code iteration} 的循环体</b>：body 是顶层节点 id 列表、<b>不在</b>主 edges 上，
 *       故 {@code ancestorsOf} 连不起来；{@code itemVar} 也没在契约登记为输出。
 *       于是一个被污染的列表驱动循环体时，体内节点的入参不会被追到。</li>
 *   <li><b>{@code sub_workflow} 内部</b>：不展开（与 manifest 的 {@code unresolved} 同一个取舍）。
 *       本类只报到「污点跨进了子流程入参」为止。</li>
 *   <li><b>契约没登记的内部键</b>：{@code __route_<id>}、{@code <id>_confirmed}、{@code <id>_result}、
 *       {@code <id>_status}、{@code <id>_waited}。其中 {@code hitl} 那几个装的是用户点按钮的结果与留言，
 *       严格说也算外部输入，但它们的产出者不在契约里 ⟹ 污点在此断流。</li>
 *   <li><b>{@code condition.rule.branches} 不作为汇</b>：它确实读 state（{@code RuleConditionNodeExecutor#matchBranch}
 *       把 {@code ctx.data()} 交给规则求值器），但读的是<b>分支字段的值</b>、只影响走哪条线，
 *       数据不流进任何入参。控制流被不可信数据操纵是另一类问题（属 V16 的可达集判定），本类不报。</li>
 *   <li><b>同一个键有多个产出者时按「有一个可达就算读到」</b>：若一个 tainted 节点与一个干净节点都写
 *       {@code x}，则读 {@code x} 的下游一律按 tainted 报。over-approximation，方向是多报。
 *       同类的还有「工具的 {@code outputVar} 撞了触发器的入口名」：运行期后者会被覆盖，
 *       但静态看不出覆盖发生在哪一刻，故两份污点并存、下游都报（见 {@link #seedNodeSources}）。</li>
 * </ol>
 *
 * <p>✅ <b>原第 6 条（待办 #48）已闭合，别再当成盲区读</b>：它说的是「{@code outputVar} 填纯空白时
 * 本类按 {@code blankToDefault} 回落成缺省名，与执行器实际写进 state 的那个空白键分岔」，
 * 而 {@code writesOf} 现在<b>直接调</b> {@code WorkflowDslValidator#writtenVarOf}，两侧同一份实现。
 * 那条分岔当时是<b>两头都占</b>的 —— 既多报一个压根没人写的缺省名，又少报真正被写脏的空白键
 * （污点整条断流），所以它不是「宁可多报」能免责的一档。细节与新添的那一处多报见 {@code writesOf} 的注释。
 *
 * @author 走进新时代
 */
public final class UntrustedInputTracer {

    /**
     * 没有 {@code NodeExecutor} 的三个类型由编译器合成子图，故没有公共 TYPE 常量可引。
     * 字面量与 {@code WorkflowDslValidator.GRAPH_BUILDER_TYPES}（{@code :74}）及
     * {@code DslToStateGraphCompiler} 的分派逐字一致。
     */
    private static final String TYPE_AGENT_LOOP = "agent_loop";
    private static final String TYPE_ITERATION = "iteration";
    private static final String TYPE_SUB_WORKFLOW = "sub_workflow";

    /** DSL {@code trigger.type} 的统一前缀；与 {@code WorkflowTriggerExtractor.TYPE_PREFIX}（{@code :51}）同 */
    private static final String TRIGGER_TYPE_PREFIX = "trigger.";

    /**
     * 脚本沙箱整取 state，故它的「汇参数名」不是某个 config 键。
     * 用 {@code state} 而不是 {@code *}：这个串会直接印在确认卡上，{@code *} 对非技术人员是噪音
     */
    private static final String WHOLE_STATE = "state";

    /**
     * 唯一一个契约没登记的汇的中文名（契约登记的汇一律用 {@code InPort#label()}，不在这里重抄）
     *
     * <p>🔴 这里原有三个常量，另两个（{@code 子流程入参} / {@code 循环目标}）在台账 #97 补齐契约后
     * <b>已删</b> —— 它们成了 {@code sub_workflow.inputMap} 与 {@code agent_loop.inputVar} 的
     * {@code InPort#label()}，权威移到契约那份。⚠️ 那四个字是<b>逐字</b>搬过去的，
     * 而 {@code UntrustedInputTracerTest} 钉的就是这四个字 ⟹ 改契约里这两个 label 会让那条测试红，
     * 这正是想要的：确认卡上印给用户看的汇名不该有两个来源。
     * 原来那句「两个契约没登记的汇」也与实情不符（下面列着三个），一并订正。
     */
    private static final String LABEL_SCRIPT_SANDBOX = "脚本沙箱（整个 state）";

    private UntrustedInputTracer() {
    }

    /**
     * 不可信输入面。五个取值与 G25 点名的五个 LLM 输入面一一对应
     *
     * @param trigger 是不是「触发器播下来的」源。🔴 这类源的 depth 0 那一份<b>恒可见</b>，
     *                不受产出者可达性约束 —— 理由见 {@link #visible}
     */
    public enum SourceKind {

        /** 聊天用户输入（{@code trigger.chat}） */
        TRIGGER_CHAT("聊天用户输入", true),
        /**
         * webhook 请求体（{@code trigger.webhook}）。任意 authType 都算污点源：
         * 验签只证明<b>发送方持有密钥</b>，不证明<b>内容可信</b>（密钥泄露、上游被攻陷、
         * 或上游本就是第三方系统时，body 照样能一路流进 prompt / BPM formVars）
         */
        TRIGGER_WEBHOOK("webhook 请求体", true),
        /** 表单事件提交的值（{@code trigger.form_event}） */
        TRIGGER_FORM_EVENT("表单事件提交的值", true),
        /** 知识库检索片段（{@code knowledge_retrieval} 的 outputVar 与 citationsVar） */
        RETRIEVAL("知识库检索片段", false),
        /** 工具返回值（{@code tool} 的 outputVar） */
        TOOL_RESULT("工具返回值", false);

        private final String label;
        private final boolean trigger;

        SourceKind(String label, boolean trigger) {
            this.label = label;
            this.trigger = trigger;
        }

        public String getLabel() {
            return label;
        }

        public boolean fromTrigger() {
            return trigger;
        }

    }

    /**
     * 汇的分类。给 V16 / X2 用来过滤，免得它们去认 config 键名
     */
    public enum SinkKind {

        /** 工具入参。V16 只关心这一个（且那个工具是写工具） */
        TOOL_ARG,
        /** 喂给模型的文字（提示词 / 系统提示词 / 判定提示词 / 自主循环目标）。X2 的指令-数据分离要改的就是这些点 */
        LLM_PROMPT,
        /** 知识库检索的查询串 */
        RETRIEVAL_QUERY,
        /** 循环遍历的列表：污点在这里表现为「循环体被跑几次、每次的 item 是什么」都由外部决定 */
        LOOP_ITEMS,
        /** 脚本沙箱：整个 state 进去，静态看不出代码读了哪个键 */
        SCRIPT_SANDBOX,
        /** 子流程入参：污点跨过了流程边界，而本清单看不见子流程内部 */
        SUB_WORKFLOW_INPUT,
        /**
         * 兜底：契约里出现了本类没登记的可引用入端口。
         *
         * <p>🔴 存在它的理由与 {@link GenericToolChangeExtractor} 完全一样：宁可标签笼统，不可静默漏报。
         * 返回 null 会让那条流向<b>既不出现在清单上、也不留下任何计数</b> ——
         * 而「新增了一个节点类型，清单悄悄地不再覆盖它」正是本项目头号缺陷形态。
         * {@code UntrustedInputTracerTest} 有一条遍历全部契约的断言盯着它，正常情况下恒为空。
         */
        OTHER_CONFIG

    }

    /**
     * 溯源（清单入口）：产出的列表按 DSL 的节点顺序、同节点内按契约的入端口声明序，故同一份 DSL 每次逐字节相同
     *
     * <p>🔴 <b>本方法不抛异常</b>。它是清单构建的一环，而清单「自己绝不能成为新的失败点」
     * （见 {@code WorkflowChangeManifestBuilder#build} 的同名约定）：DSL 有问题该由校验器报错，
     * 溯源照跑、能追多少追多少。故全程对 null 宽容，不校验 DSL 合法性。
     *
     * @param ctx 本次构建的上下文，用它的 {@code def} 与 {@code graph}；为 null 返回空列表
     */
    public static List<UntrustedInput> trace(ManifestContext ctx) {
        if (ctx == null || ctx.def() == null) {
            return List.of();
        }
        return trace(ctx.def(), ctx.graph());
    }

    /**
     * 溯源（不建清单的入口，台账 C9）：手上只有 DSL 时用这一个
     *
     * <p>🔴 与上面那个重载<b>不是两套算法</b>：两者都汇进 {@link #trace(WorkflowDefDTO, GraphReachability.Graph)}，
     * 差别只在「图从哪来」。这里现算 {@code GraphReachability.of(def)}，
     * 而 {@code WorkflowChangeManifestBuilder} 建 ctx 时用的正是<b>同一个</b>调用、喂的正是<b>同一份</b> def
     * ⟹ 同一份 DSL 走两个入口的产物必然逐条相同（{@code bothEntriesProduceByteIdenticalFlows} 钉住这一点）。
     *
     * <p>为什么值得多开一个入口：原先只有清单入口，于是「没有清单」等价于「没有污点追踪」，
     * 而 AI 自修正回路刻意不建清单（干跑最多 15 秒 × 最多 6 轮）⟹ V16 在那条路径上一句也不说。
     * 可溯源本身<b>零查询、零干跑、零副作用</b>，唯一要现算的邻接表是纯内存的 O(边数) ——
     * 把它绑在清单上，等于让一个便宜的静态分析替一个昂贵的只读预演陪葬。
     * 代价说清楚：这条路上<b>没有</b> {@code ManifestLabels}（机器码→中文名），
     * 但本类压根不读 {@code ctx.labels()}，汇的中文名一律取自契约的 {@code InPort#label()}，
     * 故两个入口印出来的文字也完全一致。
     *
     * <p>⚠️ 两个重载都接受 null，于是 {@code trace(null)} 是<b>编译期二义</b>。调用方要么传有类型的变量，
     * 要么显式写 {@code trace((WorkflowDefDTO) null)}。
     *
     * @param def 整份 DSL；为 null 返回空列表
     */
    public static List<UntrustedInput> trace(WorkflowDefDTO def) {
        if (def == null) {
            return List.of();
        }
        return trace(def, GraphReachability.of(def));
    }

    /**
     * 唯一的一份实现。两个公开入口都只负责「把图备好」，此后的播种/传播/出流没有第二条路径
     */
    private static List<UntrustedInput> trace(WorkflowDefDTO def, GraphReachability.Graph graph) {
        List<NodeAccess> accesses = indexOf(def);

        // 变量名 → 产出它的节点 id。从各节点的写集反查而来，故与传播用的是同一份口径
        Map<String, Set<String>> producers = new HashMap<>();
        for (NodeAccess access : accesses) {
            for (String written : access.writes()) {
                producers.computeIfAbsent(written, k -> new LinkedHashSet<>()).add(access.nodeId());
            }
        }
        Map<String, Set<String>> ancestors = ancestorsOfAll(accesses, graph);

        // 键 → 已流到它上面的全部污点。List 而不是 Set：要保插入序（出流顺序确定），
        // 而「同源只留 depth 最小」的合并规则见 merge
        Map<String, List<Taint>> tainted = new LinkedHashMap<>();
        seedTrigger(def.getTrigger(), tainted);
        seedNodeSources(accesses, tainted);
        propagate(accesses, producers, ancestors, tainted);
        return collectFlows(accesses, producers, ancestors, tainted);
    }

    // ==================== 每个节点的读/写集 ====================

    /**
     * 一个节点在本分析里的样子：读哪些键、写哪些键、以及它自己是不是一个新的不可信源
     *
     * @param freshSource {@code tool} / {@code knowledge_retrieval} 的产出是<b>新鲜</b>的不可信数据
     *                    （工具返回什么、召回什么，都不是流程作者能预知的），故记一个 depth 0 的源；
     *                    其余类型为 null，它们的产出只由「读到了什么」决定
     */
    private record NodeAccess(String nodeId, String nodeName, String type, List<Read> reads,
                              List<String> writes, SourceKind freshSource) {
    }

    /**
     * 一次「读」
     *
     * @param var   读的 state 根键；{@code null} = 整取 state（脚本沙箱），此时按「所有可见的污染键」处理
     * @param arg   汇的参数名，会原样印在清单上；对象型端口带子键（{@code args.tableName}）
     * @param kind  {@code null} = <b>只为传播而读、不作为汇报出</b>。
     *              唯一的用例是 {@code sub_workflow.outputMap}：它确实求值取值，
     *              但写回的是父流程自己的 state，没跨任何信任边界，报出来只是噪音
     * @param label 汇的中文名；{@code kind} 为 null 时也为 null
     */
    private record Read(String var, String arg, SinkKind kind, String label) {

        static Read wholeState() {
            return new Read(null, WHOLE_STATE, SinkKind.SCRIPT_SANDBOX, LABEL_SCRIPT_SANDBOX);
        }

    }

    /**
     * 一个污点：谁的数据、落在哪个键上、经几跳到了这里
     *
     * <p>🔴 刻意<b>不</b>带「产出源的那个节点 id」。两个工具节点都用缺省的 {@code tool_result} 时，
     * 带上它就变成两条源不同、而 {@code summary} 逐字相同的流向 —— 用户看见重复的一行，
     * 分不出差别在哪，等于噪音。要定位产出者，{@code viaNodeId} 已经在做那件事（且只在唯一时填）。
     *
     * <p>「同源」的判据是前两个字段（{@link #sameSource}），<b>不含</b> {@code depth} ——
     * 那是不动点能收敛的唯一原因：环上的节点会一轮一轮地推出 depth+1、depth+2……
     * 而按同源合并后 depth 只减不增，有限步内必然稳定。
     */
    private record Taint(SourceKind source, String sourceVar, int depth) {

        boolean sameSource(Taint other) {
            return source == other.source && StrUtil.equals(sourceVar, other.sourceVar);
        }

    }

    private static List<NodeAccess> indexOf(WorkflowDefDTO def) {
        List<NodeAccess> accesses = new ArrayList<>();
        if (def.getNodes() == null) {
            return accesses;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (NodeDTO node : def.getNodes()) {
            // id 重复是校验器的 error，这里按「先到先得」跳过 —— 与
            // WorkflowChangeManifestBuilder#indexNodes 的 putIfAbsent 同口径，
            // 否则同一条流向会被报两遍
            if (node == null || StrUtil.isBlank(node.getId()) || !seen.add(node.getId())) {
                continue;
            }
            accesses.add(accessOf(node));
        }
        return accesses;
    }

    private static NodeAccess accessOf(NodeDTO node) {
        String type = node.getType();
        NodeIoContract.Contract contract = NodeIoContract.of(type);
        List<Read> reads = new ArrayList<>();
        List<NodeIoContract.InPort> ports = contract == null ? List.of() : contract.inputs();
        for (NodeIoContract.InPort port : ports) {
            // 🔴 mode == literal ⟹ 这个值运行期压根不过模板解析，读它也不会流动。
            //    这一条判据同时把 formVars 排除在汇之外（S2-e2 把它从 both 降成 literal 之后），
            //    故「不报不可能发生的流向」在这里是<b>结构性</b>的，不是靠一张手写黑名单。
            if (NodeIoContract.MODE_LITERAL.equals(port.mode())) {
                continue;
            }
            Object raw = node.getConfig() == null ? null : node.getConfig().get(port.key());
            SinkKind kind = sinkKindOf(type, port.key());
            if (NodeIoContract.MODE_REFERENCE.equals(port.mode())) {
                // 值本身就是变量名（KnowledgeRetrievalNodeExecutor#execute 直接 ctx.getString(queryVar)）
                if (raw instanceof String text && StrUtil.isNotBlank(text)) {
                    reads.add(new Read(text.trim(), port.key(), kind, port.label()));
                }
            } else {
                addTemplateReads(raw, port.key(), kind, port.label(), reads);
            }
        }
        addUnregisteredReads(node, type, reads);
        return new NodeAccess(node.getId(), StrUtil.blankToDefault(node.getName(), node.getId()), type,
                List.copyOf(reads), writesOf(node, contract, type), freshSourceOf(type));
    }

    /**
     * 从模板值里抽出引用。字符串整取；对象<b>只取顶层的字符串值</b>
     *
     * <p>🔴 不递归进嵌套结构是照着运行期抄的，不是偷懒：{@code ToolNodeExecutor#execute} 只对
     * {@code args} 的顶层 {@code String} 值做替换，嵌在对象/数组里的占位符原样传给工具。
     * 递归扫会报出「webhook body 流进了 args.filter.name」这种<b>不会发生</b>的流向。
     * （校验器那边是递归扫的，两者口径不同是<b>对的</b>：本类问「数据会不会真的流过去」，嵌套的确实不会；
     * 校验器那个 {@code collectUnresolvableRoots} 问的是「引用的根变量<b>声明了没有</b>」，
     * 🔴 与「会不会流动」无关，故嵌套位置它确实看得见 —— 但<b>只在根变量没声明时才报</b>。
     * 作者把根变量一并声明进 {@code variables[]}（他以为声明了就能到运行期取值）时它就绿了，
     * 而那一格照样不替换 ⟹ 🔴 别把它读成「嵌套位置有校验器兜着」，那份假安心正是缺陷 C6 的成因。
     * 输入侧 / 分析侧 / 校验侧三者的完整口径见 {@code NodeIoContract} 里 {@code tool.args} 那个入端口的注释。）
     */
    private static void addTemplateReads(Object raw, String key, SinkKind kind, String label, List<Read> reads) {
        if (raw instanceof String text) {
            for (String root : StateTemplateResolver.referencedRootKeys(text)) {
                reads.add(new Read(root, key, kind, label));
            }
        } else if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof String text) {
                    for (String root : StateTemplateResolver.referencedRootKeys(text)) {
                        reads.add(new Read(root, key + "." + entry.getKey(), kind, label));
                    }
                }
            }
        }
        // List 值不看：当前没有任何执行器会对「BOTH 型入端口的 List 值」做模板替换
        //（tool.args 传 List 时 configMap 直接返回 null，等于没配）
    }

    /**
     * 契约登记了入端口、但<b>不能</b>交给上面那个通用循环处理的两类读取
     *
     * <p>🔴 本方法原名叫「契约没登记的键」，javadoc 原话是「S9-① 把契约补齐后应删掉本方法」。
     * 台账 #97 把 13 个键补齐之后实测：<b>删不掉，只能缩小</b>。原来靠它兜的 5 个键里，
     * {@code agent_loop.inputVar} 与 {@code sub_workflow.inputMap} 已由通用循环原样 reproduce
     * （sinkArg / 汇分类 / 标签三者逐字相同，故那两条分支已删），剩下两条各有一个通用循环表达不了的理由：
     * <ol>
     *   <li>{@code script.code}：整个 state 进沙箱，读了哪个键<b>静态看不出来</b>（那是一段 JS 字符串）。
     *       契约把 {@code code} 登记成 {@code MODE_LITERAL}（它确实不过模板解析），
     *       而通用循环对 LITERAL 一律跳过 ⟹ 若不在这里补，script 的「读了全部」这条最宽的读边就消失了，
     *       污点会在 script 这一跳<b>凭空断流</b>；</li>
     *   <li>{@code sub_workflow.outputMap}：值确实是模板、确实过解析，但解析的是<b>子流程</b>的 state。
     *       它被登记成 LITERAL 正是为了让通用循环跳过（见 {@code NodeIoContract} 里那个端口的注释），
     *       这里仍以 {@code kind=null}/{@code label=null} 读它 —— null 的汇<b>只为传播、不汇报出</b>：
     *       子流程结果写回的是父流程自己的 state，没跨任何信任边界，报出来只是噪音
     *       （{@code UntrustedInputTracerTest} 钉着「只报 inputMap 那一条」）。</li>
     * </ol>
     *
     * <p>⚠️ 于是「契约补齐 ⟹ 本方法可删」这个推论是<b>假的</b>，而它假的方式值得记：
     * 通用循环的判据是「mode == literal ⟹ 读它也不会流动」，这条判据把两种情况混成了一种 ——
     * 「压根不解析」（{@code formVars}，该跳过）与「解析但换个作用域」（{@code outputMap}，该读但不该汇报）。
     * 契约的三值 mode 分不开它们，故这里必须留一个显式的、有测试钉住的特例，而不是靠 mode 兜。
     */
    private static void addUnregisteredReads(NodeDTO node, String type, List<Read> reads) {
        if (ScriptNodeExecutor.TYPE.equals(type)) {
            // ScriptNodeExecutor#execute scriptSandbox.eval(code, ctx.data(), timeoutMs) —— 整个 state 进沙箱。
            // 代码里到底读了哪个键，静态看不出来（那是一段 JS 字符串），故按「全都读了」处理
            reads.add(Read.wholeState());
            return;
        }
        if (TYPE_SUB_WORKFLOW.equals(type)) {
            // outputMap 的值按 {父变量名: 子结果表达式} 对子流程的 state 求值，故 kind/label 都是 null：
            // 只参与传播、不作为汇报出的汇（理由见方法 javadoc 第 2 条）
            addMappingReads(node.configMap("outputMap"), "outputMap", null, null, reads);
        }
    }

    private static void addMappingReads(Map<String, Object> mapping, String prefix,
                                        SinkKind kind, String label, List<Read> reads) {
        if (mapping == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            if (entry.getValue() instanceof String text) {
                for (String root : StateTemplateResolver.referencedRootKeys(text)) {
                    reads.add(new Read(root, prefix + "." + entry.getKey(), kind, label));
                }
            }
        }
    }

    /**
     * 这个节点往哪些 state 键写。契约的输出端口 + 下面那份「契约没登记」的补充
     *
     * <p>🔴 契约那半<b>直接调</b> {@link WorkflowDslValidator#writtenVarOf}，不再自己算一遍（C3 已修）。
     * 原先这里是 {@code blankToDefault(node.configStr(configKey), defaultName)}，与校验器侧的
     * {@code configStr(configKey, defaultName)} 在「这一格填了<b>纯空白</b>」时分岔。
     * 而分岔的<b>方向是两头都占</b>，不只是「多报」：
     * <ul>
     *   <li><b>多报</b>：本类认定该节点产出默认名（如 {@code tool_result}），
     *       于是下游读 {@code {{$.tool_result}}} 被报成有污点，而运行期那个键压根没人写；</li>
     *   <li>🔴 <b>少报</b>：真正被写脏的是名为 {@code "  "} 的那个键（四个执行器一律
     *       {@code configStr(key, default)} + 无条件 {@code delta.put}，只判空串不判空白），
     *       而本类没给它播种 ⟹ 从它流向下游的污点<b>整条断掉</b>，清单会说「没有不可信数据流进下游」。</li>
     * </ul>
     * 所以「宁可多登记也不留缺口」这个取舍<b>救不了它</b> —— 它本身就留了缺口。
     *
     * <p>🔴 收敛成一个静态方法之后，两侧不是「碰巧同值」而是<b>同一份实现</b>：
     * 将来谁改执行器的取键口径，只有 {@code writtenVarOf} 一处要跟着动。
     * 强制点是 {@code UntrustedInputTracerTest#blankOutputVarWritesTheBlankKeyNotTheDefault}
     * （钉少报那半）与 {@code WrittenVarOfSingleSourceTest}（钉「只有一处定义、两侧都调它」）。
     *
     * <p>⚠️ 顺带删掉了原先那个 {@code port.configKey() == null} 的前置判断：契约里<b>没有一个</b>
     * 输出端口的 configKey 是 null（{@code citationsVar} / {@code condition.llm.outputVar} /
     * {@code agent_loop} 那两个为 null 的是 {@code defaultName}，不是 configKey），
     * 而留着它两侧就不是同一份计算了 —— 一个「多一道自己才有的判断」的复制品正是分岔的起点。
     * {@code writtenVarOf} 对 null 键的退化行为由 {@code NodeDTO#configGet} 的 {@code config == null} 兜住。
     *
     * <p>🟡 <b>换成 {@code isNotEmpty} 之后新添的一处多报，方向是安全的、且运行期不可达</b>：
     * {@code condition.llm.outputVar} 与 {@code agent_loop.trajectoryVar} 这两个端口的执行侧
     * 用的是 {@code StrUtil.isNotBlank} 守卫（{@code LlmConditionNodeExecutor#execute}、
     * {@code AgentLoopGraphBuilder} 的两处 {@code delta.put}），填纯空白时<b>压根不写</b>；
     * 本类现在会把那个空白键登记成写入。旧的 {@code blankToDefault(name, null)} 恰好对这两个端口是对的
     * （回落成 null 后被滤掉），但它对得靠的是「defaultName 为 null」这个巧合，
     * 代价是四个执行器那半的少报 —— 两边不能同时满足，故按本类一贯的取舍选多报。
     * 而且这一档<b>到不了运行期</b>：{@code DslToStateGraphCompiler#validateStateKeyNames}（台账 E12）
     * 顺着同一份 {@code outputs()} 把纯空白的名字一律拒掉，能带着它走到本类的只有清单那条路
     * （干跑 fail-soft：报错记进 {@code dryRun} 而不中断溯源），那时作者同时看得见
     * 「这个名字不合法」与一条多出来的污点流向 —— 两条都指向同一处要改的格子。
     */
    private static List<String> writesOf(NodeDTO node, NodeIoContract.Contract contract, String type) {
        List<String> writes = new ArrayList<>();
        if (contract != null) {
            for (NodeIoContract.OutPort port : contract.outputs()) {
                String name = WorkflowDslValidator.writtenVarOf(node, port);
                // 🔴 是 isNotEmpty 而<b>不是</b> isNotBlank：纯空白的那一格，执行器真会把值写进
                //    一个名为 "  " 的键，所以它必须被登记成写入。用 isNotBlank 会把它滤掉，
                //    等于把上面刚修好的「少报」原样请回来。
                //    null / 空串仍要跳过 —— 那是「这个可选端口没配」（如 citationsVar），运行期确实不写。
                //    代价是两个用 isNotBlank 守卫的端口多报一档，取舍见本方法 javadoc 末尾那段。
                if (StrUtil.isNotEmpty(name)) {
                    writes.add(name);
                }
            }
        }
        addUnregisteredWrites(node, type, writes);
        return writes;
    }

    /**
     * 契约没登记的写入。漏了它们的后果是<b>污点凭空断流</b>：
     * 一个 {@code agent_loop} 把污染的调查目标写进 {@code trajectoryVar}、下游再引用它，
     * 中间那一跳就查无此人，于是清单说「没有不可信数据流进下游」而实际流了。
     * 故这里宁可多登记（多登记只会让传播更宽 = 多报），也不留缺口。
     *
     * <p>🔴 {@code agent_loop.trajectoryVar} 那一支<b>已删</b>（台账 #97）：它现在登记在契约的
     * {@code outputs()} 里，由上面的通用循环写进 writes，正是本方法开头那句「宁可在契约里登记」的落地。
     * ⚠️ 顺带改掉了一个口径差：旧的那一支走 {@link #addIfPresent}（<b>trim + 去重</b>），
     * 而通用循环与 {@code WorkflowDslValidator#writtenVarOf} 都是<b>原样不 trim</b> 的
     * （那一侧的注释写明了理由：四个执行器用 {@code configStr(key, default)}，纯空白会被原样当键名写进 state）。
     * 删除之后三处对「写进哪个键」的答案才是同一个 —— 填了 {@code " traj "} 时旧口径报 {@code traj}、
     * 运行期真写的是 {@code " traj "}，那是一条指错键名的传播边。
     *
     * <p>🔴 上面那句「口径差」曾搭着一个<b>假理由</b>，2026-10-03 实测推翻。原文说：要让它可观察，
     * 得让下游节点引用一个带空格的键名，而 {@code StateTemplateResolver.PLACEHOLDER} 的正则
     * （{@code \{\{\s*([^}]+?)\s*}}）会吃掉占位符首尾的空白，故从 {@code trace()} 外面造不出这条边。
     * 前半句对、结论错：带空格的名字不必填在<b>读取侧</b>（那里确实会被正则吃掉），填在<b>写入侧的 config
     * 格子</b>里就造得出来 —— {@code iteration.itemVar = " row "} 配循环体的 {@code {{$.row}}}，实测
     * trim 版报 <b>2</b> 条流向（多出一条 {@code use#args.v#depth=1}）、去掉 trim 报 <b>1</b> 条；
     * 而两个版本下当时的 20 条测试<b>全绿</b> ⟹ 「没有覆盖」是真的，「造不出来」是假的。
     * 现已由 {@code UntrustedInputTracerTest#itemVarWithSurroundingSpacesOverReportsByDesign} 钉住。
     *
     * <p>🟢 {@code itemVar} 这一支<b>曾经</b>是真的口径差，E5（台账 #103）已把它关掉。旧文说：
     * {@code IterationGraphBuilder} 对 itemVar 只做 {@code isBlank} 检查、随后原样 {@code delta.put(itemVar, …)}，
     * 既不 trim 也不按变量名校验 ⟹ 填 {@code " row "} 时运行期写的键是 {@code " row "}，
     * 循环体的 {@code {{$.row}}} 取不到值、静默拿到空；故这里的 trim 是「多报一条运行期不指这个键的边」，
     * 与不 trim 的「少报」两者都不对，该补的是运行期那道校验。
     * <b>现在校验补上了</b>：{@code IterationGraphBuilder#build} 按
     * {@code DslToStateGraphCompiler.VAR_NAME}（{@code ^[a-zA-Z][a-zA-Z0-9_]{0,62}$}）拒绝非法 itemVar，
     * 且是<b>拒绝而不是 trim</b> ⟹ 能活到运行期的 itemVar <b>必然不含首尾空白</b> ⟹
     * 这一支的 {@link #addIfPresent} 那个 trim 对任何<b>可建图</b>的 DSL 都是<b>恒等</b>操作。
     *
     * <p>对照：{@code sub_workflow.outputMap} 那一支从来就不是口径差 ——
     * {@code validateMapping} 先 {@code String.valueOf(key).trim()}、再按同一个 {@code VAR_NAME} 拒绝非法名，
     * 故运行期写回的键必然已 trim。<b>E5 之后两支结论一致了</b>，都是「与运行期同口径」，
     * 差别只剩一个细节：outputMap 那侧是 trim 后校验（宽松），itemVar 这侧是直接校验（严格）。
     *
     * <p>⚠️ 但 {@code trace()} 是<b>静态分析，不建图</b>：它照样能被喂进 {@code itemVar = " row "} 这种
     * E5 会拒的 DSL（校验发生在编译期，分析发生在编译之前）。此时 trim 报出的那条边指向一个
     * <b>永远建不起来的图</b>里的键 —— 仍是「多报」，与本类开头的取舍一致，故 trim <b>保留不动</b>。
     * 它同时是一道<b>绊线</b>：{@code itemVarWithSurroundingSpacesOverReportsByDesign} 里那份 DSL 现在
     * 已经是「编译期会被拒」的形状，那条测试报 2 条流向的前提正是 E5 的校验<b>不在</b>分析路径上生效。
     * 哪天有人把校验挪进分析路径、或把 trim 去掉，那条测试会先红。
     *
     * <p>「删掉专门分支之后 trajectoryVar 仍被当成写入」那一半由
     * {@code UntrustedInputTracerTest#agentLoopTrajectoryVarCarriesTaint} 钉住；
     * 「通用循环与 {@code writtenVarOf} 同源」这一半原先写的是<b>「没有专门的测试，由两处代码同形本身保证」</b>，
     * 台账 C3 之后这句话两头都陈了：通用循环不再与它「同形」而是<b>直接调它</b>（同形正是会漂的那种保证 ——
     * 漂了一整轮，见 {@code writesOf} 的 javadoc），而强制点也补上了：
     * {@code WrittenVarOfSingleSourceTest}（钉「只有一处定义、两侧都调、含纯空白这个边界逐字相同」）
     * 与 {@code UntrustedInputTracerTest#blankOutputVarWritesTheBlankKeyNotTheDefault}（钉行为）。
     */
    private static void addUnregisteredWrites(NodeDTO node, String type, List<String> writes) {
        if (TYPE_ITERATION.equals(type)) {
            // 每轮的当前项写进 itemVar 指定的那个变量名；循环体节点用 {{$.那个名}} 取它
            // （⚠️ 不是字面的 itemVar —— 这句曾写错，与契约、前端 schema 的 hint 是同一条错话的三份副本）
            addIfPresent(node.configStr("itemVar"), writes);
            return;
        }
        if (TYPE_SUB_WORKFLOW.equals(type)) {
            // outputMap 的键是父流程侧的目标变量名（DslToStateGraphCompiler#validateMapping）
            Map<String, Object> outputMap = node.configMap("outputMap");
            if (outputMap != null) {
                for (String key : outputMap.keySet()) {
                    addIfPresent(key, writes);
                }
            }
        }
    }

    private static void addIfPresent(String name, List<String> writes) {
        if (StrUtil.isNotBlank(name) && !writes.contains(name.trim())) {
            writes.add(name.trim());
        }
    }

    /**
     * 节点类型 + 入端口 → 汇的分类
     *
     * <p>🔴 兜底返回 {@link SinkKind#OTHER_CONFIG} 而不是 null：返回 null 会让那条流向静默消失
     * （既不上清单也不留计数）。契约新增一个可引用入端口而这里忘了登记时，
     * 用户看到的是一条标签笼统的流向，而不是什么都没有 —— 前者能查，后者查不出来。
     */
    private static SinkKind sinkKindOf(String type, String key) {
        if (ToolNodeExecutor.TYPE.equals(type)) {
            return SinkKind.TOOL_ARG;
        }
        if (LlmNodeExecutor.TYPE.equals(type) || LlmConditionNodeExecutor.TYPE.equals(type)
                || TYPE_AGENT_LOOP.equals(type)) {
            return SinkKind.LLM_PROMPT;
        }
        if (KnowledgeRetrievalNodeExecutor.TYPE.equals(type)) {
            return SinkKind.RETRIEVAL_QUERY;
        }
        if (TYPE_ITERATION.equals(type)) {
            return SinkKind.LOOP_ITEMS;
        }
        // 🔴 键名限定到 inputMap，不是「sub_workflow 一律 SUB_WORKFLOW_INPUT」：同类型的 outputMap
        //    登记成 MODE_LITERAL（值对<b>子流程</b>的 state 求值，见 NodeIoContract 里那个端口的注释），
        //    通用循环压根不会为它走到这里；真走到了（= 有人把它的 mode 改了），也不该顶着「子流程入参」这个
        //    分类静默汇报出去 —— 落到兜底的 OTHER_CONFIG 会让 UntrustedInputTracerTest 那条
        //    「outputMap 不作为汇报出」的断言红，而不是把噪音混进一个看起来合理的分类里。
        if (TYPE_SUB_WORKFLOW.equals(type) && "inputMap".equals(key)) {
            return SinkKind.SUB_WORKFLOW_INPUT;
        }
        return SinkKind.OTHER_CONFIG;
    }

    private static SourceKind freshSourceOf(String type) {
        if (ToolNodeExecutor.TYPE.equals(type)) {
            return SourceKind.TOOL_RESULT;
        }
        if (KnowledgeRetrievalNodeExecutor.TYPE.equals(type)) {
            return SourceKind.RETRIEVAL;
        }
        return null;
    }

    // ==================== 播种 ====================

    /**
     * 触发器入口变量。三个有外部载荷的类型各记一种源；{@code schedule} <b>不是</b>源
     *
     * <p>🔴 为什么排除 {@code trigger.schedule}：它的载荷是平台按 cron 自己生成的，
     * 里面没有任何人能写的东西。把它当源会让每一条定时流程的每个提示词都报一条
     * 「不可信数据流入」—— 全是假的，而假警报的代价是真警报没人看。
     * V16 之所以点名 schedule，是因为「无头触发 + 可达集里有写工具」这个<b>组合</b>本身危险，
     * 那个判定用的是 {@code triggerType} 与 {@link GraphReachability}，不需要本溯源的结果。
     */
    private static void seedTrigger(TriggerDTO trigger, Map<String, List<Taint>> tainted) {
        SourceKind kind = triggerSourceOf(trigger);
        if (kind == null) {
            return;
        }
        // inputVar() 恒非空（TriggerDTO#inputVar 缺省回落 "input"）
        String var = trigger.inputVar();
        if (StrUtil.isNotBlank(var)) {
            merge(tainted, var.trim(), new Taint(kind, var.trim(), 0));
        }
    }

    private static SourceKind triggerSourceOf(TriggerDTO trigger) {
        if (trigger == null || StrUtil.isBlank(trigger.getType())) {
            return null;
        }
        return switch (StrUtil.removePrefix(trigger.getType(), TRIGGER_TYPE_PREFIX)) {
            case "chat" -> SourceKind.TRIGGER_CHAT;
            case "webhook" -> SourceKind.TRIGGER_WEBHOOK;
            case "form_event" -> SourceKind.TRIGGER_FORM_EVENT;
            default -> null;
        };
    }

    /**
     * {@code tool} / {@code knowledge_retrieval} 的产出键是新鲜的不可信源
     *
     * <p>🔴 与触发器的污点<b>并存，不互相覆盖</b>（走的是同一条 {@link #merge}）。
     * 一个工具的 {@code outputVar} 完全可能被配成触发器的入口名（例如 {@code outputVar: "input"}），
     * 那时清空槽位就等于把「请求体流进了下游」这条真的流向抹掉 —— 少报一条会发生的流向。
     * 反过来也不必担心 depth 打架：本方法在 {@link #propagate} <b>之前</b>跑，
     * 播种的 depth 恒为 0，而 {@code mergeMin} 只认更小的 depth，故传播推来的 depth≥1 永远盖不掉它。
     */
    private static void seedNodeSources(List<NodeAccess> accesses, Map<String, List<Taint>> tainted) {
        for (NodeAccess access : accesses) {
            if (access.freshSource() == null) {
                continue;
            }
            for (String written : access.writes()) {
                merge(tainted, written, new Taint(access.freshSource(), written, 0));
            }
        }
    }

    // ==================== 不动点传播 ====================

    private static void propagate(List<NodeAccess> accesses, Map<String, Set<String>> producers,
                                  Map<String, Set<String>> ancestors, Map<String, List<Taint>> tainted) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (NodeAccess access : accesses) {
                List<Taint> incoming = readsOf(access, producers, ancestors, tainted);
                if (incoming.isEmpty()) {
                    continue;
                }
                for (String written : access.writes()) {
                    for (Taint taint : incoming) {
                        changed |= merge(tainted, written,
                                new Taint(taint.source(), taint.sourceVar(), taint.depth() + 1));
                    }
                }
            }
        }
    }

    /**
     * 本节点这次执行能读到的全部污点，按「同源只留 depth 最小」去重
     */
    private static List<Taint> readsOf(NodeAccess access, Map<String, Set<String>> producers,
                                       Map<String, Set<String>> ancestors, Map<String, List<Taint>> tainted) {
        Set<String> ancestor = ancestors.get(access.nodeId());
        List<Taint> found = new ArrayList<>();
        for (Read read : access.reads()) {
            found.addAll(taintsAt(read, access.nodeId(), ancestor, producers, tainted));
        }
        return dedupe(found);
    }

    /**
     * 一个「读」能读到的污点。{@code read.var() == null}（脚本沙箱整取 state）时收全部可见的
     */
    private static List<Taint> taintsAt(Read read, String selfId, Set<String> ancestor,
                                        Map<String, Set<String>> producers, Map<String, List<Taint>> tainted) {
        if (read.var() == null) {
            List<Taint> all = new ArrayList<>();
            for (Map.Entry<String, List<Taint>> entry : tainted.entrySet()) {
                all.addAll(visible(entry.getValue(), producers.get(entry.getKey()), ancestor, selfId));
            }
            return all;
        }
        return visible(tainted.getOrDefault(read.var(), List.of()),
                producers.get(read.var()), ancestor, selfId);
    }

    /**
     * 这个键上的污点里，本节点执行时<b>真读得到</b>的是哪些。两条判据满足其一即可见
     *
     * <ol>
     *   <li>{@link #available} 说这个键读得到 ⟹ 全部可见；</li>
     *   <li>🔴 否则只留下<b>触发器播种的那一份</b>（{@code fromTrigger() && depth == 0}）。
     *       没有这条就会少报：某个节点把 {@code outputVar} 配成了触发器的入口名
     *       （{@code outputVar: "input"} 是完全合法的写法），那个键于是凭空有了产出者，
     *       排在它<b>前面</b>的节点读 {@code {{$.input}}} 会被判成「运行到这里还没值」——
     *       而运行期它读的正是触发器写进去的原值，那条流向真会发生。
     *       传播来的那份（depth ≥ 1）不享受这个豁免：它本来就是某个节点写出来的，可达性该查。</li>
     * </ol>
     */
    private static List<Taint> visible(List<Taint> taints, Set<String> producersOfVar,
                                       Set<String> ancestor, String selfId) {
        if (available(producersOfVar, ancestor, selfId)) {
            return taints;
        }
        List<Taint> seeded = new ArrayList<>();
        for (Taint taint : taints) {
            if (taint.depth() == 0 && taint.source().fromTrigger()) {
                seeded.add(taint);
            }
        }
        return seeded;
    }

    /**
     * 这个键在本节点执行时读得到吗
     *
     * <p>没有产出者（触发变量、{@code variables[]} 声明的、契约没登记的内部键）一律视为读得到 ——
     * 与 {@code WorkflowDslValidator#validateReferenceReachability} 的「非节点产出变量，可达性不适用，跳过」同一条判据。
     * 有产出者则要求至少一个是<b>除自己以外</b>的祖先。
     *
     * <p>🔴 排除自己不是洁癖：{@code GraphReachability#ancestorsOf} 刻意<b>含自身</b>，
     * 于是不排除的话「节点读自己即将写出的键」也算可达。对脚本节点这是<b>必然发生</b>的误报 ——
     * 它整取 state，而 state 里也有它自己的 {@code script_result}，于是每个下游有源的脚本节点
     * 都会多报一条「工具返回值经 1 跳流进了脚本沙箱」，那条流的是它自己的产出。
     * 运行期沙箱拿到的是<b>写入之前</b>的 state（{@code ScriptNodeExecutor#execute} 先 eval 再写 outputVar）。
     * 校验器那边保留自环是对的（它问「这个引用解析不解析得到」，与本类问的不是同一件事）。
     */
    private static boolean available(Set<String> producersOfVar, Set<String> ancestor, String selfId) {
        if (producersOfVar == null || producersOfVar.isEmpty()) {
            return true;
        }
        if (ancestor == null) {
            return false;
        }
        for (String producer : producersOfVar) {
            if (!StrUtil.equals(producer, selfId) && ancestor.contains(producer)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 并进一个污点，委托 {@link #mergeMin} 保证两趟（传播 / 出流）用的是同一条合并规则
     *
     * @return 是否真的改变了（不动点靠它判停）
     */
    private static boolean merge(Map<String, List<Taint>> tainted, String key, Taint incoming) {
        List<Taint> slot = tainted.computeIfAbsent(key, k -> new ArrayList<>());
        List<Taint> before = List.copyOf(slot);
        mergeMin(slot, incoming);
        return !before.equals(slot);
    }

    private static List<Taint> dedupe(List<Taint> taints) {
        List<Taint> result = new ArrayList<>();
        for (Taint candidate : taints) {
            mergeMin(result, candidate);
        }
        return result;
    }

    /**
     * {@link #merge} 的 List 版：同 (源, 源变量) 只留 depth 最小的那条，保插入序
     */
    private static void mergeMin(List<Taint> slot, Taint incoming) {
        for (int i = 0; i < slot.size(); i++) {
            Taint existing = slot.get(i);
            if (existing.sameSource(incoming)) {
                if (incoming.depth() < existing.depth()) {
                    slot.set(i, incoming);
                }
                return;
            }
        }
        slot.add(incoming);
    }

    // ==================== 出流 ====================

    private static List<UntrustedInput> collectFlows(List<NodeAccess> accesses, Map<String, Set<String>> producers,
                                                     Map<String, Set<String>> ancestors,
                                                     Map<String, List<Taint>> tainted) {
        List<UntrustedInput> flows = new ArrayList<>();
        for (NodeAccess access : accesses) {
            Set<String> ancestor = ancestors.get(access.nodeId());
            for (Read read : access.reads()) {
                if (read.kind() == null) {
                    continue; // 只为传播而读（sub_workflow.outputMap），不作为汇报出
                }
                for (Taint taint : dedupe(taintsAt(read, access.nodeId(), ancestor, producers, tainted))) {
                    flows.add(flow(taint, access, read, producers, ancestor));
                }
            }
        }
        return flows;
    }

    private static UntrustedInput flow(Taint taint, NodeAccess access, Read read,
                                       Map<String, Set<String>> producers, Set<String> ancestor) {
        UntrustedInput flow = new UntrustedInput();
        flow.setSourceKind(taint.source().name());
        flow.setSourceLabel(taint.source().getLabel());
        flow.setSourceVar(taint.sourceVar());
        flow.setSinkKind(read.kind().name());
        flow.setSinkLabel(read.label());
        flow.setSinkNodeId(access.nodeId());
        flow.setSinkNodeName(access.nodeName());
        flow.setSinkArg(read.arg());
        flow.setViaVar(read.var() == null ? WHOLE_STATE : read.var());
        flow.setViaNodeId(singleProducer(read.var(), producers, ancestor, access.nodeId()));
        flow.setDepth(taint.depth());
        flow.setSummary(buildSummary(flow));
        return flow;
    }

    /**
     * {@code viaVar} 的唯一上游产出者；0 个或多个时返回 null
     *
     * <p>多个产出者时随便挑一个等于<b>指认错人</b>，而指认错人比不指认更坏 ——
     * 用户会去画布上盯着一个其实无关的节点看。故这里宁缺毋滥。
     * 自己也不计入（理由同 {@link #available}：节点读的是自己写入<b>之前</b>的值）。
     */
    private static String singleProducer(String var, Map<String, Set<String>> producers,
                                         Set<String> ancestor, String selfId) {
        if (var == null) {
            return null;
        }
        Set<String> all = producers.get(var);
        if (all == null || ancestor == null) {
            return null;
        }
        String only = null;
        for (String producer : all) {
            if (StrUtil.equals(producer, selfId) || !ancestor.contains(producer)) {
                continue;
            }
            if (only != null) {
                return null;
            }
            only = producer;
        }
        return only;
    }

    /**
     * 一句人话。措辞遵循本模块的留白规则（{@code SummarySpacingTest}）：
     * ASCII token 两侧留空格，全角「」（）前不留
     */
    private static String buildSummary(UntrustedInput flow) {
        StringBuilder sb = new StringBuilder(flow.getSourceLabel())
                .append("（").append(flow.getSourceVar()).append("）");
        if (flow.getDepth() > 0) {
            sb.append("经 ").append(flow.getDepth()).append(" 跳传播后");
        }
        sb.append("会流进节点「").append(flow.getSinkNodeName()).append("」的").append(flow.getSinkLabel());
        if (!WHOLE_STATE.equals(flow.getSinkArg())) {
            sb.append(" ").append(flow.getSinkArg());
        }
        return sb.toString();
    }

    // ==================== 图 ====================

    /**
     * 每个节点的祖先集，一次算好。传播是不动点、会反复访问同一批节点，
     * 每轮重算 {@code ancestorsOf} 就是把 O(n) 的 BFS 乘上轮数
     */
    private static Map<String, Set<String>> ancestorsOfAll(List<NodeAccess> accesses, GraphReachability.Graph graph) {
        Map<String, Set<String>> ancestors = new HashMap<>();
        if (graph == null) {
            return ancestors;
        }
        for (NodeAccess access : accesses) {
            ancestors.put(access.nodeId(), graph.ancestorsOf(access.nodeId()));
        }
        return ancestors;
    }

}
