package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.module.agent.enums.WaitTypeEnum;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentLoopProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.EdgeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.VariableDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.io.NodeIoContract;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.StatePathNavigator;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.StateTemplateResolver;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trace.NodeTraceRecorder;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.core.loop.AgentLoopGraphBuilder;
import cn.enlev.ainb.module.agent.framework.agent.core.loop.IterationGraphBuilder;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.observation.GraphObservationLifecycleListener;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/**
 * DSL JSON → SAA {@link CompiledGraph} 的编译器（适配层核心，DSL §1 原则 2「引擎中立的可编译性」）
 *
 * <h3>🔴 每次执行编译一张新图，不缓存</h3>
 * 节点 lambda 要闭包捕获<b>本次执行</b>的 executionId / threadName / 身份快照，才能把 trace
 * 落到正确的执行实例上。缓存图会让这些执行级标识串台。StateGraph 的构建+编译是纯内存操作，
 * 开销可忽略；真正重的 MysqlSaver 是共享 bean，不随图重建。
 *
 * <h3>编译期即校验，不留到运行时</h3>
 * 节点类型没有对应执行器 → {@code registry.get} 抛 WORKFLOW_NODE_EXECUTOR_NOT_FOUND；
 * 边指向不存在的节点、节点 id 重复、图为空 → 抛 WORKFLOW_DSL_INVALID。
 * 这些都是「配置错误」，必须在跑第一个节点<b>之前</b>就报清楚，而不是执行到一半 NPE。
 *
 * <h3>条件节点 = SAA 节点（算路由）+ 条件边（读路由）</h3>
 * 见 {@link NodeExecutor#ROUTE_KEY_PREFIX} 的说明：把「可能调模型、要记 trace」的决策放进节点，
 * 边只做纯读映射。这样 condition.llm 的 token 能正常归因，条件边保持廉价。
 *
 * <h3>parallel 节点 = 标记节点（锚定分叉）+ 编译期展开 fan-out/fan-in 边</h3>
 * 并行是<b>建图期</b>结构，执行器加不了 SAA 边，故 {@code parallel} 保留为一张真实节点作 fan-out 锚点
 * （任何前驱都能指向它，无需重写条件边 routeMap），其出边在 {@link #expandParallel} 里展开成
 * {@code addEdge(par, branchNodes)} 分发 + {@code addEdge(branchNodes, joinTarget)} 汇聚屏障（=join=all）。
 * 🔴 受 SAA 原生约束，MVP 分支为<b>单节点</b>（多节点链需子图 → 增量2b sub_workflow）。
 * 见该方法与 {@code ParallelNodeExecutor} 的说明。
 *
 * <h3>sub_workflow 节点 = 把子 DSL 递归构建成子 StateGraph 并 {@code addNode(id, childGraph)}</h3>
 * 与 parallel 不同，sub_workflow 的「节点动作」<b>就是子图本身</b>（无 no-op 执行器）：{@link #buildStateGraph}
 * 被顶层图与子图共用，子图交给 SAA 原生 {@code addNode(String, StateGraph)}，由 SAA 在父图 compile 时用父
 * config（含共享 MysqlSaver）编译。子图<b>共享父状态</b>（同名变量双向流动）、子节点事件实时上浮父 SSE、
 * checkpoint 线程名自动隔离。串行链 {@code X→sw→Y} 里 sw 是普通节点 id，连边照常。
 * 🔴 但 sub_workflow <b>不能作 parallel 分支</b>：SAA compile 会把子图内联展开成多节点链，破坏
 * 「各分支直接汇聚同一 join」的收敛规则（{@code findParallelNodeTargets}），故多节点并行分支在 SAA 1.1.2.2
 * 下仍无解，{@link #expandParallel} 对此编译期拒绝。见 {@link #addSubWorkflowNode}。
 *
 * <h3>sub_workflow 的 inputMap/outputMap = 父侧合成 {@code _in}/{@code _out} 映射节点夹住子图（增量2c）</h3>
 * SAA 原生子图整包喂入、共享 key 空间，<b>无入/出参过滤钩子</b>，故显式变量映射不改子图，而是在<b>父侧</b>合成两个
 * 无 trace 的 plumbing 节点（同 iteration 的 {@code _guard}/{@code _advance}）：{@code _<sw>_in} 按 inputMap
 * 把父值写进子流程入参名（子图跑前），{@code _<sw>_out} 按 outputMap 把子结果写回父变量名（子图跑后）。第 2 步据此把
 * {@code X→sw→Y} 改写成 {@code X→_in→sw→_out→Y}。这样子流程能用<b>自己声明的变量名</b>作者化、被不同父流程复用。
 * 🔴 这是<b>重命名/显式契约</b>而非<b>硬隔离</b>（子图技术上仍见未映射的父键，SAA 整包喂入所致）；硬物理隔离需放弃
 * 原生子图（丢子图 SSE 上浮），留后续。见 {@link #addSubWorkflowNode}。
 *
 * <h3>agent_loop 节点 = 编译器<b>合成</b>一张 reason↔act 环形子图并 {@code addNode(id, loopGraph)}</h3>
 * 与 sub_workflow 的关键区别：sub_workflow 的子图来自<b>另一份已发布 DSL</b>（{@code buildStateGraph(childDef)}），
 * agent_loop 的子图<b>没有 childDef</b>，是 {@link AgentLoopGraphBuilder} 按节点 config 现场程序化搭出来的环形图
 * （规格 §4.2 L107「内部 llm↔tool 循环子图，maxSteps 作循环上界」）。环只能藏在子图里——顶层图必须无环（§L43）。
 * 它同样<b>无需执行器 bean</b>（其「节点动作」即合成子图），在父图里是普通节点 id，连边照常。
 * 🔴 收敛/不收敛<b>不依赖 SAA recursionLimit</b>：spike 实测 SAA 命中上限是静默截断而非抛错，
 * 故循环上界由子图自己的 step 计数器 + 条件出口精确约束，非收敛时由 reason 节点主动抛
 * {@code WORKFLOW_AGENT_LOOP_NOT_CONVERGED}（fail-close，轨迹已逐轮落 trace）。
 * 🔴 只允许<b>只读工具</b>（自主循环无人工确认，平台铁律「用户确认前不执行任何写操作」）：
 * {@code tools[]} 里的写工具在编译期即拒。见 {@link AgentLoopGraphBuilder}。
 *
 * <h3>iteration 节点 = 编译器<b>合成</b>一张 {@code _guard→body链→_advance} 环形子图并 {@code addNode(id, iterGraph)}</h3>
 * 与 agent_loop 同属「环形子图」家族（DSL §4.3 L118、§6 L237），区别在循环体与收敛条件：agent_loop 是<b>限步数自主</b>
 * 循环（合成的 reason↔act，模型决定何时停），iteration 是<b>确定次数</b>的 foreach（遍历 {@code itemsVar} 列表，
 * 每项把当前项写进 {@code itemVar} 后跑一遍 {@code body} 子链，{@code index≥size} 时出口）。body 节点是<b>真实 DSL 节点</b>
 * （写在顶层 nodes[]、用既有执行器），由本编译器用 {@link #wrap} 预包成 action（trace 前缀 {@code <iterId>-}）后交给
 * {@link IterationGraphBuilder} 接线——故<b>每 item × 每 body 节点各落一行独立 trace</b>（{@code <iterId>-<bodyId>}，
 * 逐 item token 归因天然成立）。累积靠 body 写 {@code merge:append} 变量（SAA APPEND 跨轮累积，S1-iteration spike 已证），
 * iteration 自身<b>无 outputVar</b>。🔴 body 节点<b>从父图排除</b>（第 1 步 {@code continue}）、<b>不得写进 edges[]</b>
 * （其连边由子图内部生成），与 parallel 分支同纪律；🔴 body 仅允许 plain 节点（llm/tool/knowledge_retrieval），
 * 控制流/子图/hitl 一律编译期拒（留后续增量）；🔴 {@code concurrency>1} 拒（SAA 无原生动态 fan-out）；
 * 🔴 循环上界 = {@code settings.maxIterations}（默认 100），guard 运行期发现列表超长<b>fail-close 抛错</b>而非静默截断。
 * 它同样<b>无需执行器 bean</b>（其「节点动作」即合成子图），在父图里是普通节点 id，连边照常。见 {@link IterationGraphBuilder}。
 *
 * <h3>wait 节点 = 普通执行器节点 + 编译期登记 {@code interruptBefore}（增量4a，只 waitType=duration）</h3>
 * 与 hitl.* 复用<b>同一条已证的挂起/恢复链</b>：wait 是普通节点（{@code WaitNodeExecutor} pass-through，有 trace），
 * 编译期把它的 id 收进 {@code interruptSink} → {@code CompileConfig.interruptsBefore}，SAA 跑到它<b>之前</b>挂起、
 * checkpoint 落库、stream 以 {@code InterruptionMetadata} 收尾 → Service 置 INTERRUPTED 并按 {@code duration} 算
 * {@code resume_at} 落库。与 hitl 的唯一区别是「谁来 resume」：hitl 等人工点确认，wait 由
 * {@code WorkflowWaitResumeScheduler} 扫 {@code resume_at<=now} 到点<b>自动</b> resume（CAS 认领防双跑）。
 * 🔴 挂起期不占线程、不触发 executionTimeoutSeconds（该 timeout 是相邻 SSE 事件间隔上限，挂起时 stream 已结束）。
 * duration 解析/校验见 {@link #resolveWaitDurationSeconds}（数字秒或 ISO-8601，>0 且 <=7 天）；event/hitl 唤醒、
 * 子图内 wait 均编译期拒（留增量4b / 增量2d）。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class DslToStateGraphCompiler {

    /** sub_workflow 最大嵌套深度，防失控递归把编译栈打爆（环引用另有 buildPath 检测） */
    private static final int MAX_SUB_WORKFLOW_DEPTH = 8;

    /** wait(duration) 的 duration 上限（秒）= 7 天，防手误写出「等一万年」把执行永久挂起 */
    private static final long MAX_WAIT_SECONDS = 7L * 24 * 3600;

    /** wait(event) 的 eventKey 上限（字符），与 DDL 里 event_key varchar(128) 对齐，超长入库会被截断/报错 */
    private static final int MAX_EVENT_KEY_LENGTH = 128;

    /**
     * 子图节点落 trace 时的命名空间分隔符：{@code <父节点id>-<子节点id>}
     *
     * <p>🔴 值不是随便挑的，必须对齐 SAA 子图的 {@code formatId}（{@code %s-%s}），
     * 否则 trace 里的 node_id 与父 SSE 上浮的子节点事件 id 对不上，前端按事件 id 找 trace 行会全落空。
     * 全仓拼这个串的只有本类（{@link #wrap} 与 agent_loop 那一处 {@code build} 调用），两处都走本常量；
     * 改值要同时想清 SAA 那边并不会跟着改。
     */
    private static final String TRACE_NAMESPACE_SEPARATOR = "-";

    /**
     * 合法<b>变量名</b>（state 的 key）：字母开头，后跟字母/数字/下划线（snake_case 与 camelCase 均放行）。
     *
     * <p>🔴 为什么是「字母开头」而非照搬节点 id 的 {@code ^[a-z]...}（仅小写）：本编译器对 {@code outputVar}、
     * {@code variables[].key} <b>并不强制</b>小写（{@link VariableDTO} 只在 javadoc 里约定，运行期不校验），
     * camelCase 变量名在全系统都能跑。若这里单独对映射键强制小写，会造成「{@code outputVar:"myVar"} 合法、
     * 但 {@code inputMap:{"myVar":...}} 被拒」的割裂。故只要求「字母开头」——这一条即足以<b>防撞内部键</b>
     * （{@code __route_}/{@code __iter_*}/{@code __agentloop_*} 等均 {@code __} 前缀，字母开头永不冲突），
     * 同时与系统其它位置对变量名的宽松口径保持一致。
     *
     * <h3>🔴 这是<b>public</b> 的：它是「什么名字可以当 state 的键写进去」在全仓的<b>唯一</b>权威</h3>
     * 两个调用点，问的是同一个问题（「这个名字会被原样 {@code put} 进 state 当键」）：
     * <ul>
     *   <li>{@link #validateMapping} —— sub_workflow 的 {@code inputMap}/{@code outputMap} 的键；</li>
     *   <li>{@code IterationGraphBuilder#build} —— iteration 的 {@code itemVar}（E5 补的那道校验，台账 #103）。</li>
     * </ul>
     * 补 E5 之前只有第一处有它，第二处只查 {@code isBlank} 就原样 {@code put} ⟹ 填 {@code " row "} 时运行期
     * 写进去的键<b>带空格</b>，而循环体的 {@code {{$.row}}} 永远取不到（占位符正则 {@code \{\{\s*([^}]+?)\s*}}
     * 吃掉的是<b>读取侧</b>的空白，救不了<b>写入侧</b>的键名），静默拿到空。
     *
     * <p>⚠️ 两处报错文案里的正则一律用 {@code VAR_NAME.pattern()} 现取，<b>绝不</b>再手抄一遍字面量 ——
     * 手抄的那一份正是「改了 Pattern 忘了改文案」的温床，而文案是给作者看的<b>唯一</b>补救指引，
     * 抄错就等于教人写一个仍会被拒的名字。
     */
    public static final Pattern VAR_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,62}$");

    /**
     * 「单个整占位符」判定：整个表达式恰好是一个 {@code {{ expr }}}（两侧无其它文本）。
     * 与 {@link StateTemplateResolver} 的 {@code PLACEHOLDER} 同源（同样惰性捕获到第一个 {@code }}），
     * 但加了 {@code ^...$} 锚定 —— 命中时走 {@link StatePathNavigator} 取<b>原始对象</b>（保 List/Map/Number 类型），
     * 不命中（混合模板如 {@code "{{$.a}}-x"} 或纯文本）才走 {@link StateTemplateResolver} 做字符串插值。
     */
    private static final Pattern SINGLE_PLACEHOLDER = Pattern.compile("^\\{\\{\\s*([^}]+?)\\s*}}$");

    /**
     * SAA 默认 recursionLimit（S1 spike 实测 = 100）
     *
     * <p>🔴 SAA 命中 recursionLimit 时是<b>静默截断而非抛错</b>（spike 实测），故它只能当全局兜底：
     * agent_loop 的收敛由 {@link AgentLoopGraphBuilder} 自己的 step 计数器 + fail-close 保证。
     * 这里显式设限只为「合法的长流程（大步数 / 多个 agent_loop）不被默认值误截」，
     * 计算结果恒 ≥ 本默认值，绝不把无环图的上限调低。
     */
    private static final int SAA_DEFAULT_RECURSION_LIMIT = 100;

    @Resource
    private NodeExecutorRegistry registry;
    @Resource
    private NodeTraceRecorder traceRecorder;
    // sub_workflow：按 (code,version) 取子 def + 解析其 DSL（均 agent 模块内，无跨模块依赖）
    @Resource
    private AgentWorkflowDefService defService;
    @Resource
    private DslParser dslParser;
    // agent_loop：把节点 config 现场合成环形子图（reason↔act），其「节点动作」即子图，无需执行器 bean
    @Resource
    private AgentLoopGraphBuilder agentLoopGraphBuilder;
    // iteration：把节点 config + body 子链现场合成环形子图（_guard→body→_advance），其「节点动作」即子图，无需执行器 bean
    @Resource
    private IterationGraphBuilder iterationGraphBuilder;
    // 仅供 compile() 估算 recursionLimit 时取 agent_loop 的默认步数 / 解析重试上限
    @Resource
    private AgentLoopProperties loopProperties;

    /**
     * 编译一份 DSL 为可执行图
     *
     * @param def                 DSL
     * @param executionId         本次执行实例 id（落 trace 用）
     * @param threadName          SAA checkpoint 线程名，由调用方经 {@code AgentWorkflowThreadName} 生成（B3-5 唯一定义处）
     * @param agentContext        身份快照（节点内调工具/写库时贴回租户）
     * @param saver               共享的 checkpoint saver
     * @param observationRegistry Micrometer 观测注册表，可为 NOOP
     */
    public CompiledGraph compile(WorkflowDefDTO def, Long executionId, String threadName,
                                 AgentExecutionContext agentContext, BaseCheckpointSaver saver,
                                 ObservationRegistry observationRegistry) throws GraphStateException {
        List<String> interruptNodes = new ArrayList<>();
        StateGraph graph = buildStateGraph(def, executionId, threadName, agentContext,
                new ArrayDeque<>(), null, interruptNodes);

        // 编译（只有顶层图组 CompileConfig：saver / observation / interrupts / recursionLimit；
        // 子图由 SAA 在父图 compile 时用父 config 编译，见 addSubWorkflowNode）
        CompileConfig.Builder builder = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                // 🔴 显式设 recursionLimit：保证「2*maxSteps + 父图其余 superstep」不触顶被静默截断。
                //    仅按顶层 def 的 agent_loop 估算（嵌套在 sub_workflow 子图里的 agent_loop 由下面的
                //    默认值下限兜底 —— 单个 loop 约 2*maxSteps+2 个 superstep，默认 100 足够）。
                .recursionLimit(computeRecursionLimit(def));
        if (observationRegistry != null && observationRegistry != ObservationRegistry.NOOP) {
            // observationRegistry 只提供注册表；观测点由 LifecycleListener 实际触发（SPIKE 验证③）
            builder.observationRegistry(observationRegistry)
                    .withLifecycleListener(new GraphObservationLifecycleListener(observationRegistry));
        }
        if (!interruptNodes.isEmpty()) {
            builder.interruptsBefore(interruptNodes);
        }
        return graph.compile(builder.build());
    }

    /**
     * 干跑校验（增量7a，plan-as-workflow 生成器）：<b>只建图、不编译、不执行</b>，把深层结构问题探出来
     *
     * <h3>为什么能零重复地复用 {@link #buildStateGraph}</h3>
     * {@code buildStateGraph} 跑完<b>全部</b>结构校验（{@link #validate} 基础项、节点 id 唯一、
     * {@code registry.get} 校验 type 有执行器、边端点、conditional cases/default、
     * parallel/iteration/sub_workflow/wait/agent_loop 各自的 config 与接线），而它<b>不需要 saver</b>
     * ——saver 只在 {@link #compile} 组 {@code CompileConfig} 时才用。且干跑路径<b>无 DB 写、无 LLM 调用</b>
     * （sub_workflow 仅只读加载子 def），故拿一个 {@code executionId=-1} 的废弃标识跑一遍即可，无副作用。
     *
     * <h3>🔴 这是只读薄封装，绝不动 {@link #compile}/{@link #buildStateGraph}/{@link #validate} 的既有行为</h3>
     * 执行路径仍 fail-fast 抛 {@code WORKFLOW_DSL_INVALID}/{@code GraphStateException}；本方法只是把
     * 「首个深校验错误」捕获成 {@link AgentValidateResult} 的一条 error，供 LLM 自修正回灌。
     * 与 {@code WorkflowDslValidator}（收集式基础校验）配合：基础层一次列全常见错，深层复用编译器 fail-fast
     * （深错较少，首个即够模型定位）。
     *
     * @param def 已解析的 DSL
     * @param ctx 身份快照（{@code buildStateGraph} 里 {@code wrap} 会捕获它，但干跑不执行节点，故仅占位）
     * @return 校验结果；{@link AgentValidateResult#isPassed()} 为真表示能建图（深层结构合法）
     */
    public AgentValidateResult dryRunValidate(WorkflowDefDTO def, AgentExecutionContext ctx) {
        AgentValidateResult result = new AgentValidateResult();
        try {
            buildStateGraph(def, -1L, "dry-run", ctx,
                    new ArrayDeque<>(), null, new ArrayList<>());
        } catch (GraphStateException | ServiceException ex) {
            result.addError("DSL 深层结构不合法：%s", ex.getMessage());
        } catch (RuntimeException ex) {
            // 兜底：建图期任何未预料的运行时异常也收成一条可回灌的错误，而不是让生成循环崩掉
            result.addError("DSL 深层结构校验时发生异常：%s", ex.getMessage());
        }
        return result;
    }

    /**
     * 构建（<b>不编译</b>）一份 DSL 的 {@link StateGraph} —— 顶层图与 sub_workflow 子图共用此逻辑
     *
     * <p>抽出来是为了让 {@link #addSubWorkflowNode} 能把子 def 递归构建成子 StateGraph，再
     * {@code graph.addNode(id, childStateGraph)} 交给 SAA（SAA 在父图 compile 时用父 config 编译子图）。
     *
     * @param buildPath     当前 sub_workflow 嵌套链（{@code code@version} 序列），用于环检测；顶层传空
     * @param traceParentNodeId 子图节点所属的 sw 节点<b>裸</b> id（落 trace 时拼成 {@code <父id>-<子id>}，
     *                      并原样写进 {@code parent_node_id}）；顶层传 {@code null}。见 {@link #wrap}
     * @param interruptSink 收集需 {@code interruptBefore} 挂起的节点 id（hitl.* 人工确认 + wait 定时挂起）：
     *                      顶层用于 {@code CompileConfig.interruptsBefore}；子图传废弃 list
     *                      （子图含 hitl/wait 已在 addSubWorkflowNode 编译期拒绝，留增量2d）
     */
    private StateGraph buildStateGraph(WorkflowDefDTO def, Long executionId, String threadName,
                                       AgentExecutionContext agentContext, Deque<String> buildPath,
                                       String traceParentNodeId, List<String> interruptSink) throws GraphStateException {
        validate(def);

        KeyStrategyFactory keyStrategyFactory = KeyStrategyFactoryGenerator.generate(def);
        String graphName = def.getName() != null && !def.getName().isEmpty() ? def.getName() : def.getId();
        StateGraph graph = new StateGraph(graphName, keyStrategyFactory);

        Set<String> nodeIds = new HashSet<>();
        for (NodeDTO node : def.getNodes()) {
            nodeIds.add(node.getId());
        }

        // iteration：body 节点写在顶层 nodes[] 但只进 iteration 子图、不进父图（与 parallel 分支同纪律）。
        // 先按 id 建索引，再收集所有 iteration 的 body id 并校验（存在 / 非保留字 / 非控制流类型 /
        // 不跨 iteration 重复 / 不在 edges[]），供第 1 步排除、addIterationNode 按序解析。
        Map<String, NodeDTO> nodeById = new LinkedHashMap<>();
        for (NodeDTO node : def.getNodes()) {
            nodeById.put(node.getId(), node);
        }
        Set<String> iterationBodyIds = collectIterationBodyIds(def, nodeById);

        // sub_workflow 映射（增量2c）：配了 inputMap/outputMap 的 sw 节点会被父侧 _in/_out 合成节点夹住，
        // 这里记录「sw id → [inId|null, outId|null]」，供第 2 步把 X→sw→Y 改写成 X→_in→sw→_out→Y。
        // 每层图（顶层 / 各子图）各持一份，互不干扰。未配映射的 sw 不记，边不改写（增量2b 原样）。
        Map<String, String[]> swRedirect = new LinkedHashMap<>();

        // 1. 加节点（registry.get 顺带校验「该 type 有没有执行器」）
        for (NodeDTO node : def.getNodes()) {
            // iteration 的 body 节点：只进子图（由 addIterationNode 预包接线），不进父图、也不在此 wrap
            if (iterationBodyIds.contains(node.getId())) {
                continue;
            }
            // sub_workflow：节点动作「就是」子图，直接 addNode(id, childStateGraph)，不走执行器（无对应 bean）
            if ("sub_workflow".equals(node.getType())) {
                addSubWorkflowNode(graph, node, executionId, threadName, agentContext, buildPath, swRedirect);
                continue;
            }
            // agent_loop：节点动作「就是」现场合成的<b>环形</b>子图（reason↔act 自主 ReAct 循环），
            // 同样 addNode(id, loopGraph) 内嵌、不走执行器（无对应 bean）。在父图里它是普通节点 id，
            // 第 2 步加边 X→id→Y 照常，无需特判。只读工具限步数，见 AgentLoopGraphBuilder。
            if ("agent_loop".equals(node.getType())) {
                // 🔴 这一处传的是拼好的<b>前缀</b>（{@code <loopId>-}），而 wrap 传的是<b>裸</b>父 id ——
                //    不对称是刻意的：AgentLoopGraphBuilder 的 LoopSpec 本来就同时持有 nodeId 与 tracePrefix，
                //    它落 parent_node_id 时直接用 nodeId，不需要从前缀里切。别为了「统一」把这里也改成裸 id
                //    再让 builder 自己拼 —— 那只是把分隔符抄到第二个类里。
                StateGraph loopGraph = agentLoopGraphBuilder.build(
                        node, executionId, threadName, agentContext,
                        node.getId() + TRACE_NAMESPACE_SEPARATOR);
                graph.addNode(node.getId(), loopGraph);
                continue;
            }
            // iteration：节点动作「就是」现场合成的 foreach <b>环形</b>子图（_guard→body链→_advance），
            // 同样 addNode(id, iterGraph) 内嵌、不走执行器（无对应 bean）。body 子链由本编译器用 wrap 预包
            // （trace 前缀 <iterId>-）后交给 builder 接线，见 addIterationNode / IterationGraphBuilder。
            if ("iteration".equals(node.getType())) {
                addIterationNode(graph, node, nodeById, def, executionId, threadName, agentContext);
                continue;
            }
            NodeExecutor executor = registry.get(node.getType());
            // wait：编译期 fail-fast 校验，按 waitType 路由（duration 校验秒数、event 校验 eventKey、hitl 拒）；
            // 解析结果不在这里用 —— 真正算 resume_at / event_key 是 Service 在中断时刻做（见 resolveWait* 调用方）
            if ("wait".equals(node.getType())) {
                validateWaitConfig(node);
            }
            graph.addNode(node.getId(),
                    wrap(node, executor, executionId, threadName, agentContext, traceParentNodeId));
            // hitl.*（人工确认）与 wait（定时挂起）都在节点<b>之前</b> interruptBefore 挂起，等 resume（DSL §4.4/§4.3、§6.2）：
            // hitl 靠人工 resume-stream，wait 靠 WorkflowWaitResumeScheduler 到点自动 resume（增量4a）
            if (node.getType() != null
                    && (node.getType().startsWith("hitl.") || "wait".equals(node.getType()))) {
                interruptSink.add(node.getId());
            }
        }

        // 2. 加边
        //    parallel 节点的出边不直接连，而是展开成 SAA 原生 fan-out/fan-in（DSL §4.3、§5）：
        //    分支节点仍写在顶层 nodes[]（第 1 步已照常 addNode），这里只按 config.branches 生成边。
        Map<String, NodeDTO> parallelNodes = new LinkedHashMap<>();
        for (NodeDTO node : def.getNodes()) {
            if ("parallel".equals(node.getType())) {
                parallelNodes.put(node.getId(), node);
            }
        }
        Set<String> parallelIds = parallelNodes.keySet();

        boolean hasStartEdge = false;
        Set<String> expandedParallel = new HashSet<>();
        for (EdgeDTO edge : safeEdges(def)) {
            validateEdgeEndpoints(edge, nodeIds);
            String from = edge.getFrom();
            if (parallelIds.contains(from)) {
                // parallel 的出边 = join 目标：展开 fan-out + 链内 + fan-in barrier
                if (edge.isConditional()) {
                    throw exception(WORKFLOW_DSL_INVALID,
                            "parallel 节点(" + from + ") 的出边不能是条件边（MVP 只支持静态汇聚目标）");
                }
                if (!expandedParallel.add(from)) {
                    throw exception(WORKFLOW_DSL_INVALID,
                            "parallel 节点(" + from + ") 只能有一条出边（join 目标）");
                }
                expandParallel(graph, parallelNodes.get(from), edge, def, nodeIds, parallelIds, swRedirect);
                continue;
            }
            if (edge.isConditional()) {
                addConditionalEdge(graph, edge, swRedirect);
            } else {
                // hasStartEdge 仍看原始 from 是否 START（redirect 只改 sw，不会把 sw 变成 START）
                if (StateGraph.START.equals(mapEndpoint(from))) {
                    hasStartEdge = true;
                }
                // 增量2c：mapped sw 的入边接到 _in、出边接到 _out（redirectFrom/To），其余节点原样
                graph.addEdge(mapEndpoint(redirectFrom(from, swRedirect)),
                        mapEndpoint(redirectTo(edge.getTo(), swRedirect)));
            }
        }
        // parallel 节点必须恰有一条出边（上面保证 ≤1 且已展开，这里补「0 条出边」的漏网）
        for (String parId : parallelIds) {
            if (!expandedParallel.contains(parId)) {
                throw exception(WORKFLOW_DSL_INVALID, "parallel 节点(" + parId + ") 缺少出边（join 目标）");
            }
        }

        // 增量2c：为每个 mapped sw 直接补两条内部边 _in→sw、sw→_out（绕过 redirect，否则 sw 会被二次改写）。
        // 与上面「X→sw 改写成 X→_in」「sw→Y 改写成 _out→Y」拼成完整链 X→_in→sw→_out→Y。
        for (Map.Entry<String, String[]> e : swRedirect.entrySet()) {
            String sw = e.getKey();
            String inId = e.getValue()[0];
            String outId = e.getValue()[1];
            if (inId != null) {
                graph.addEdge(inId, sw);
            }
            if (outId != null) {
                graph.addEdge(sw, outId);
            }
        }

        // 3. 兜底入口：DSL 没显式画 START 边时，连到第一个节点
        //    （trigger 语义上就是「从哪开始」，§4.1；显式 START 边优先）
        if (!hasStartEdge && !def.getNodes().isEmpty()) {
            // 跳过 iteration 的 body 节点：它们不在父图里，连过去会指向不存在的父节点（SAA 编译报错）
            String entryId = def.getNodes().stream()
                    .map(NodeDTO::getId)
                    .filter(id -> !iterationBodyIds.contains(id))
                    .findFirst()
                    .orElse(def.getNodes().get(0).getId());
            // 第一个节点恰是 mapped sw 时，入口连到它的 _in（而非 sw 本身）
            graph.addEdge(StateGraph.START, redirectTo(entryId, swRedirect));
        }
        return graph;
    }

    /**
     * 把一个 DSL 节点包成 SAA node action：执行器调用 + trace 收尾
     *
     * <p>trace 的 begin/success/failure 三段包在执行器外面，执行器本身只管业务、不碰 trace ——
     * 这样新增节点类型不必重复写记录逻辑，也保证「失败也留痕」（failure 分支）。
     *
     * @param traceParentNodeId 所属 sub_workflow / iteration 节点的<b>裸</b> id；顶层传 {@code null}。
     *                          落 trace 的 node_id = {@code 父id + "-" + node.getId()}（对齐 SAA 子图
     *                          {@code formatId}，避免子/父同名节点撞行），同时原样落进
     *                          {@code parent_node_id} 列 —— 🔴 传裸 id 而不是传拼好的前缀，就是为了
     *                          让这两件事<b>只能</b>同源：早先这里收的是前缀 {@code <父id>-}，
     *                          而 B5-1 要的父 id 得从它里面切回来（迭代六 裁定 A-Ⅱ 明确不要这种做法）。
     */
    private AsyncNodeAction wrap(NodeDTO node, NodeExecutor executor, Long executionId,
                                 String threadName, AgentExecutionContext agentContext, String traceParentNodeId) {
        boolean namespaced = traceParentNodeId != null && !traceParentNodeId.isEmpty();
        String traceNodeId = namespaced
                ? traceParentNodeId + TRACE_NAMESPACE_SEPARATOR + node.getId() : node.getId();
        return AsyncNodeAction.node_async(state -> {
            WorkflowContext ctx = new WorkflowContext(state, executionId, threadName, agentContext);
            Long traceId = traceRecorder.begin(ctx, node, traceNodeId, namespaced ? traceParentNodeId : null);
            long begin = System.currentTimeMillis();
            try {
                Map<String, Object> delta = executor.execute(node, ctx);
                traceRecorder.success(traceId, ctx, delta, System.currentTimeMillis() - begin);
                return delta == null ? Map.of() : delta;
            } catch (Exception ex) {
                traceRecorder.failure(traceId, ctx, System.currentTimeMillis() - begin, ex);
                // NodeAction.apply 声明 throws Exception，原样抛出，由 SAA 终止 stream
                throw ex;
            }
        });
    }

    private void addConditionalEdge(StateGraph graph, EdgeDTO edge, Map<String, String[]> swRedirect)
            throws GraphStateException {
        Map<String, String> routeMap = new HashMap<>();
        if (edge.getCases() != null) {
            edge.getCases().forEach((routeKey, target) ->
                    routeMap.put(routeKey, mapEndpoint(redirectTo(target, swRedirect))));
        }
        if (edge.getDefaultTarget() != null && !edge.getDefaultTarget().isEmpty()) {
            routeMap.put(NodeExecutor.DEFAULT_ROUTE, mapEndpoint(redirectTo(edge.getDefaultTarget(), swRedirect)));
        }
        if (routeMap.isEmpty()) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "条件边(" + edge.getFrom() + ") 既无 cases 也无 default，无处可路由");
        }
        String routeStateKey = NodeExecutor.routeKey(edge.getFrom());
        // 条件边纯读：取条件节点算好的路由键，取不到走 DEFAULT_ROUTE。
        // 源恒为 condition 节点（不会是 sub_workflow），故 from 不过 redirect；只有 target 可能是 mapped sw。
        graph.addConditionalEdges(edge.getFrom(), edge_async(state -> {
            Object route = state.value(routeStateKey).orElse(NodeExecutor.DEFAULT_ROUTE);
            String key = String.valueOf(route);
            // 路由键不在 routeMap 里（模型/规则给出了非法值）→ 落默认，避免路由到不存在的节点
            return routeMap.containsKey(key) ? key : NodeExecutor.DEFAULT_ROUTE;
        }), routeMap);
    }

    /**
     * 把一个 parallel 节点展开成 SAA 原生 fan-out/fan-in（DSL §4.3、§5、§7）
     *
     * <p>设 {@code par} 的 {@code config.branches=[[a],[b]]}、出边 {@code par→merge}，生成：
     * <ol>
     *   <li>fan-out：{@code addEdge(par, [a, b])} —— 各分支节点，同一 superstep 并发激活；</li>
     *   <li>fan-in barrier：{@code addEdge([a, b], merge)} —— 各分支节点全部完成才激活 join 目标，
     *       这就是 {@code join=all} 的原生实现。</li>
     * </ol>
     *
     * <h3>🔴 MVP 只支持单节点分支</h3>
     * SAA 原生 fan-out/fan-in 要求各分支的<b>直接后继</b>收敛到同一 join（{@code CompiledGraph} 校验
     * 「parallel node 只能有一个 target」，见其 {@code findParallelNodeTargets}）。故每条 branch 只能是
     * 一个节点 id；多节点链需把整条链封成子图作为单一分支节点 → 增量2b 的 {@code sub_workflow}，本增量不做，
     * 编译期对多节点链报清晰错。
     *
     * <h3>join 仅支持 all</h3>
     * {@code any}（任一分支完成即汇聚）SAA 无对应原语，本增量编译期直接拒绝，绝不当成 all 静默跑。
     *
     * <h3>状态写冲突由 KeyStrategy 承载，不在这里裁决</h3>
     * 并行分支同时写 state，同键 {@code replace} 是不确定竞态；多写者键应用 {@code append}/{@code merge}
     * （由 {@code variables[].merge} → {@link KeyStrategyFactoryGenerator} 既有机制处理），
     * 最稳妥是各分支写不同 outputVar。
     *
     * <h3>编译期校验（fail-fast）</h3>
     * branches 非空、分支 id 存在于顶层 nodes[]、非 START/END 保留字、非 parallel（不支持嵌套）、
     * 不跨分支重复、不手写进 edges[]、分支数不超 {@code settings.maxParallelism}。
     */
    private void expandParallel(StateGraph graph, NodeDTO parNode, EdgeDTO outEdge, WorkflowDefDTO def,
                                Set<String> nodeIds, Set<String> parallelIds, Map<String, String[]> swRedirect)
            throws GraphStateException {
        String parId = parNode.getId();

        // join 策略：仅支持 all
        String join = parNode.configStr("join", "all");
        if (!"all".equalsIgnoreCase(join)) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "parallel 节点(" + parId + ") 暂只支持 join=all，收到：" + join);
        }

        // branches：List<List<String>>（每个元素是一条节点 id 链）
        List<Object> rawBranches = parNode.configList("branches");
        if (rawBranches.isEmpty()) {
            throw exception(WORKFLOW_DSL_INVALID, "parallel 节点(" + parId + ") 的 branches 为空");
        }

        // maxParallelism：并行分支数上限（防资源打爆，DSL §2 settings）
        Integer maxParallelism = def.getSettings() == null ? null : def.getSettings().getMaxParallelism();
        if (maxParallelism != null && rawBranches.size() > maxParallelism) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "parallel 节点(" + parId + ") 并行分支数 " + rawBranches.size()
                            + " 超过 maxParallelism " + maxParallelism);
        }

        // 收集所有 edges 端点：分支节点的连边全由本方法生成，故它们不得手写进 edges[]
        Set<String> edgeEndpoints = collectEdgeEndpoints(def);

        // 分支 id → type，用于挡住 sub_workflow 分支（见下方 SAA 原生并行限制）
        Map<String, String> nodeTypeById = new HashMap<>();
        for (NodeDTO n : def.getNodes()) {
            nodeTypeById.put(n.getId(), n.getType());
        }

        List<String> branchNodes = new ArrayList<>();
        Set<String> seenBranchIds = new HashSet<>();
        for (Object rawBranch : rawBranches) {
            if (!(rawBranch instanceof List<?> chain) || chain.isEmpty()) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "parallel 节点(" + parId + ") 的每条 branch 必须是非空的节点 id 链");
            }
            // 🔴 MVP 只支持单节点分支：SAA 原生 fan-out/fan-in 要求各分支的<b>直接后继</b>收敛到同一 join
            //    （CompiledGraph 校验「parallel node 只能有一个 target」，见 findParallelNodeTargets）。
            //    多节点链需把整条链封成子图作为单一分支节点 → 增量2b 的 sub_workflow，本增量不做。
            if (chain.size() > 1) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "parallel 节点(" + parId + ") 的分支暂只支持单节点（SAA 原生并行要求各分支直接汇聚到同一 join）；"
                                + "多节点链请改用 sub_workflow（增量2b）。分支：" + chain);
            }
            String id = String.valueOf(chain.get(0));
            if (!nodeIds.contains(id)) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "parallel 节点(" + parId + ") 的分支引用了不存在的节点：" + id);
            }
            // 🔴 sub_workflow 子图不能作 parallel 分支：父图 compile 时 SAA 会把子图<b>内联展开</b>成多节点链，
            //    分支的「直接 target」变成子图内部节点（如 sw_a-subllm），两条分支各不相同 → 触发 SAA
            //    「parallel node must have only one target」的 GraphStateException。这里提前给清晰中文错，
            //    而不是把 SAA 的英文栈抛给编排者。多节点并行分支在 SAA 1.1.2.2 下无解，留待换底层原语时再议。
            if ("sub_workflow".equals(nodeTypeById.get(id))) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "parallel 节点(" + parId + ") 的分支不能是 sub_workflow 子图（SAA 原生并行要求各分支直接汇聚到"
                                + "同一 join，子图会被内联展开成多节点链而破坏收敛）；请把子图出口收敛为单节点或改串行。分支：" + id);
            }
            if ("START".equals(id) || "END".equals(id)) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "parallel 节点(" + parId + ") 的分支不能是保留端点：" + id);
            }
            if (parallelIds.contains(id)) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "parallel 节点(" + parId + ") 不支持嵌套并行，分支引用了 parallel 节点：" + id);
            }
            if (!seenBranchIds.add(id)) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "parallel 节点(" + parId + ") 的分支节点重复（不能出现在两条分支）：" + id);
            }
            if (edgeEndpoints.contains(id)) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "parallel 节点(" + parId + ") 的分支节点 " + id
                                + " 不能出现在 edges 里（其连边由 parallel 自动生成）");
            }
            branchNodes.add(id);
        }

        // fan-out：par → 各分支节点（同一 superstep 并发激活）
        graph.addEdge(parId, branchNodes);
        // fan-in barrier：各分支节点 → join 目标（outEdge.to，可为普通节点或 END）；全部完成才激活 = join=all。
        // join 目标若是 mapped sw（增量2c），汇聚到它的 _in（而非 sw 本身），保证 inputMap 生效。
        graph.addEdge(branchNodes, mapEndpoint(redirectTo(outEdge.getTo(), swRedirect)));
    }

    private Set<String> collectEdgeEndpoints(WorkflowDefDTO def) {
        Set<String> endpoints = new HashSet<>();
        for (EdgeDTO e : safeEdges(def)) {
            if (e.getFrom() != null) {
                endpoints.add(e.getFrom());
            }
            if (e.getTo() != null) {
                endpoints.add(e.getTo());
            }
            if (e.getCases() != null) {
                endpoints.addAll(e.getCases().values());
            }
            if (e.getDefaultTarget() != null) {
                endpoints.add(e.getDefaultTarget());
            }
        }
        return endpoints;
    }

    /**
     * 收集所有 iteration 节点的 body id（并集），同时做编译期 fail-fast 校验（DSL §4.3 L118、结构约束 §2 L43）
     *
     * <p>body 节点写在顶层 nodes[] 但<b>只进 iteration 子图、不进父图</b>，其连边由子图内部生成 —— 与 parallel 分支
     * 同纪律（见 {@link #expandParallel}）。故这里逐条校验：
     * <ul>
     *   <li>body 非空；每个 id 非空、存在于 nodes[]、非 START/END 保留字；</li>
     *   <li>🔴 类型必须是「plain」节点 —— 拒绝 condition.* / hitl.* / parallel / iteration / sub_workflow /
     *       agent_loop / wait（子图内嵌条件边、嵌套子图、中断恢复都是大增，留后续增量）；</li>
     *   <li>🔴 不跨 iteration 重复、同一 iteration 内不重复（一个节点只能属于一条 body 链）；</li>
     *   <li>🔴 不得手写进 edges[]（连边由子图生成，手写会与排除逻辑冲突）。</li>
     * </ul>
     * 校验通过后返回 body id 并集，供第 1 步把这些节点从父图 {@code continue} 排除。
     */
    private Set<String> collectIterationBodyIds(WorkflowDefDTO def, Map<String, NodeDTO> nodeById) {
        Set<String> edgeEndpoints = collectEdgeEndpoints(def);
        Set<String> allBodyIds = new HashSet<>();
        for (NodeDTO node : def.getNodes()) {
            if (!"iteration".equals(node.getType())) {
                continue;
            }
            String iterId = node.getId();
            List<Object> rawBody = node.configList("body");
            if (rawBody.isEmpty()) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "iteration 节点(" + iterId + ") 的 body 为空（至少一个节点 id）");
            }
            for (Object o : rawBody) {
                String id = o == null ? null : String.valueOf(o).trim();
                if (id == null || id.isEmpty()) {
                    throw exception(WORKFLOW_DSL_INVALID, "iteration 节点(" + iterId + ") 的 body 含空 id");
                }
                NodeDTO bodyNode = nodeById.get(id);
                if (bodyNode == null) {
                    throw exception(WORKFLOW_DSL_INVALID,
                            "iteration 节点(" + iterId + ") 的 body 引用了不存在的节点：" + id);
                }
                if ("START".equals(id) || "END".equals(id)) {
                    throw exception(WORKFLOW_DSL_INVALID,
                            "iteration 节点(" + iterId + ") 的 body 不能是保留端点：" + id);
                }
                if (isControlFlowBodyType(bodyNode.getType())) {
                    throw exception(WORKFLOW_DSL_INVALID,
                            "iteration 节点(" + iterId + ") 的 body 仅支持 plain 节点(llm/tool/knowledge_retrieval)，"
                                    + "不支持节点(" + id + ") 的类型 " + bodyNode.getType()
                                    + "（控制流/子图/hitl 留后续增量）");
                }
                if (!allBodyIds.add(id)) {
                    throw exception(WORKFLOW_DSL_INVALID,
                            "iteration 的 body 节点不能重复（同一节点不能被多个 iteration、或同一 iteration 多次引用）：" + id);
                }
                if (edgeEndpoints.contains(id)) {
                    throw exception(WORKFLOW_DSL_INVALID,
                            "iteration 节点(" + iterId + ") 的 body 节点 " + id
                                    + " 不能出现在 edges 里（其连边由 iteration 子图自动生成）");
                }
            }
        }
        return allBodyIds;
    }

    /**
     * body 节点类型是否为「不可进 iteration 子图」的控制流/子图/中断类型（MVP 仅放行 plain 节点）。
     * 用<b>黑名单</b>而非白名单：新增的 plain 执行器（如未来的 code/script）无需改这里即可作 body；
     * 未知类型仍由 {@code registry.get} 在 wrap 时报 WORKFLOW_NODE_EXECUTOR_NOT_FOUND。
     */
    private static boolean isControlFlowBodyType(String type) {
        if (type == null) {
            return true; // 防御：validate 已保证 type 非空，null 一律挡掉
        }
        if (type.startsWith("condition.") || type.startsWith("hitl.")) {
            return true;
        }
        return switch (type) {
            case "parallel", "iteration", "sub_workflow", "agent_loop", "wait" -> true;
            default -> false;
        };
    }

    /**
     * 把 sub_workflow 节点展开成 SAA 原生子图（DSL §4.3 L119、§7 L274）
     *
     * <p>与 parallel（标记节点 + 展开边）不同：sub_workflow 的「节点动作」<b>就是子图本身</b>，
     * 故不保留 no-op 执行器，而是把子 def 递归构建成 {@link StateGraph} 后 {@code addNode(id, childGraph)}。
     * 子图在父图里以 {@code id} 注册，父图连边 {@code X→id→Y} 照常工作（第 2 步加边无需特判）。
     *
     * <h3>状态共享（SAA 原生，字节码已核实）</h3>
     * SAA {@code SubCompiledGraphNodeAction} 用 {@code subGraph.updateState(cfg, parentState.data())} 把父状态
     * 整包喂进子图、在共享 key 空间上运行；子图节点事件经返回的 Flux 实时上浮到父 SSE；子图 checkpoint
     * 线程名 {@code <id>-<父thread>} 自动隔离。故<b>同名变量双向自然流动</b> —— 这是未配映射时的默认通信方式。
     *
     * <h3>inputMap / outputMap：父侧合成映射节点做「显式变量重命名」（增量2c）</h3>
     * SAA 原生子图<b>没有入/出参过滤钩子</b>（整包喂入、共享 key 空间），故映射不改子图，而是在<b>父侧</b>合成两个
     * 无 trace 的 plumbing 节点夹住子图：配了 {@code inputMap} 时生成 {@code _<sw>_in}（子图跑前按
     * {@code {子入参名: 父取值表达式}} 把父值写进子入参名），配了 {@code outputMap} 时生成 {@code _<sw>_out}
     * （子图跑后按 {@code {父变量名: 子结果表达式}} 把子结果写回父变量名）。第 2 步据此把 {@code X→sw→Y}
     * 改写成 {@code X→_in→sw→_out→Y}（见 {@code swRedirect}）。这样子流程能用<b>自己声明的变量名</b>作者化，
     * 被不同父流程用各自的变量名复用。取值表达式：单个整占位符 {@code {{$.x}}} 走 {@link StatePathNavigator}
     * 保<b>原始类型</b>（List/Map/Number），混合模板走 {@link StateTemplateResolver} 插值成串（见 {@link #resolveMapValue}）。
     * <p>🔴 这是<b>重命名 / 显式契约</b>，<b>不是硬命名空间隔离</b>：子图技术上仍能看到未映射的父键（SAA 整包喂入所致），
     * 子流程作者应只读自己声明的入参（由设计器 / planner 约束）。硬物理隔离（子图只见映射键）需放弃原生子图、改成
     * 「compiled-child-in-lambda」，代价是子图 SSE 粒度损失 + reactor 阻塞风险，留后续增量（需用户明确要）。
     *
     * <h3>MVP 边界（编译期 fail-fast 拒绝，绝不静默降级）</h3>
     * <ul>
     *   <li>{@code inputMap}/{@code outputMap} 若存在须是对象、键为合法变量名、值为非空字符串表达式，否则拒绝。</li>
     *   <li>子流程含 {@code hitl.*} → 拒绝（子图中断恢复留后续）。</li>
     *   <li>循环引用（A→B→A）、嵌套深度超 {@code MAX_SUB_WORKFLOW_DEPTH} → 拒绝。</li>
     * </ul>
     *
     * @param swRedirect 出参：记录本层图里「配了映射的 sub_workflow 节点 id → {@code [inId|null, outId|null]}」，
     *                   供 {@link #buildStateGraph} 第 2 步改写连边（{@code X→sw→Y} 变 {@code X→_in→sw→_out→Y}）。
     */
    private void addSubWorkflowNode(StateGraph graph, NodeDTO node, Long executionId, String threadName,
                                    AgentExecutionContext agentContext, Deque<String> buildPath,
                                    Map<String, String[]> swRedirect)
            throws GraphStateException {
        String subNodeId = node.getId();
        String code = node.configStr("workflowId");
        if (code == null || code.isEmpty()) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "sub_workflow 节点(" + subNodeId + ") 缺少 workflowId（子流程 code）");
        }
        Integer version = node.configInt("version", null);

        // 校验映射（在取子 def 之前 fail-fast：非法映射无需查库即可报错，也便于单测不 stub defService）
        Map<String, String> inputMap = validateMapping(node, "inputMap", subNodeId);
        Map<String, String> outputMap = validateMapping(node, "outputMap", subNodeId);

        // 深度守卫（环引用另有 buildPath 检测）
        if (buildPath.size() >= MAX_SUB_WORKFLOW_DEPTH) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "sub_workflow 节点(" + subNodeId + ") 嵌套过深（>" + MAX_SUB_WORKFLOW_DEPTH + "）：" + buildPath);
        }

        // 取子 def（查不到 / pin 到 DRAFT → WORKFLOW_SUB_WORKFLOW_NOT_FOUND，由 Service 抛）
        AgentWorkflowDefDO childDo = defService.getSubWorkflowDef(code, version);
        String childKey = childDo.getCode() + "@" + childDo.getVersion();
        if (buildPath.contains(childKey)) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "sub_workflow 节点(" + subNodeId + ") 检测到循环引用：" + buildPath + " → " + childKey);
        }

        WorkflowDefDTO childDef = dslParser.parse(childDo.getDsl());
        // 🔴 SAA 墙（增量16D 已核实、诚实 fail-fast，不伪装支持）：子图中断节点（hitl.* / wait）无法跨子图恢复。
        //    机制：interrupt 靠 CompileConfig.interruptsBefore(nodeId) 注册，而子图在父图 compile 时共用<b>父</b> config；
        //    SAA 只按顶层节点 id 匹配 interruptsBefore，子图内部节点 id 不在其列 → 子图里的 hitl/wait 中断无处登记，
        //    resume 时找不到断点（要么静默不挂起、要么挂起后无法恢复）。且本方法递归时把 interruptSink 丢弃
        //    （{@code new ArrayList<>()}），子图中断信号也无处上报。解锁前置：SAA 提供「子图中断向父图冒泡 +
        //    父图 resume 下钻子图断点」的原生能力（当前 1.1.2.x 无）。故在递归前挡掉，宁可编译期明确拒，不运行期静默失控。
        if (childDef.getNodes() != null) {
            for (NodeDTO cn : childDef.getNodes()) {
                String ct = cn.getType();
                if (ct != null && (ct.startsWith("hitl.") || "wait".equals(ct))) {
                    throw exception(WORKFLOW_DSL_INVALID,
                            "sub_workflow 节点(" + subNodeId + ") 的子流程(" + childKey
                                    + ") 含 " + ct + " 节点，暂不支持子图中断（hitl/wait 的跨子图恢复受 SAA 框架限制："
                                    + "interruptsBefore 只对顶层节点 id 生效，子图内部中断无法登记/恢复；需 SAA 提供子图中断冒泡能力后解锁）");
                }
            }
        }

        // 递归构建子 StateGraph（不 compile）；子图节点的 trace 命名空间 = <subNodeId>-<childId>，
        // 对齐 SAA 子图 formatId(%s-%s) 约定，同时 subNodeId 原样落 parent_node_id（迭代六 B5-1）
        buildPath.push(childKey);
        StateGraph childGraph;
        try {
            childGraph = buildStateGraph(childDef, executionId, threadName, agentContext,
                    buildPath, subNodeId, new ArrayList<>());
        } finally {
            buildPath.pop();
        }
        // SAA 原生子图：父图 compile 时用父 config（含共享 MysqlSaver）编译子图
        graph.addNode(subNodeId, childGraph);

        // 增量2c：配了映射才合成父侧 _in/_out plumbing 节点（无 trace），夹住子图做显式变量重命名。
        // 未配映射 = 增量2b 原样直挂（swRedirect 不记，边不改写，零行为变化）。
        String inId = inputMap.isEmpty() ? null : "_" + subNodeId + "_in";
        String outId = outputMap.isEmpty() ? null : "_" + subNodeId + "_out";
        if (inId != null) {
            graph.addNode(inId, mappingAction(inputMap, executionId, threadName, agentContext));
        }
        if (outId != null) {
            graph.addNode(outId, mappingAction(outputMap, executionId, threadName, agentContext));
        }
        if (inId != null || outId != null) {
            swRedirect.put(subNodeId, new String[]{inId, outId});
        }
        log.info("[addSubWorkflowNode][sub_workflow 节点({}) 内嵌子流程({}) 作为 SAA 子图，映射(in={} out={})，执行({})]",
                subNodeId, childKey, inId, outId, executionId);
    }

    /**
     * 合成一个「按映射表把值写进 state」的父侧 plumbing 节点动作（无 trace，同 iteration 的 {@code _guard}/{@code _advance}）。
     *
     * <p>每次执行新建 {@link WorkflowContext}（复用 {@code wrap} 同款门面），遍历 {@code mapping}
     * （{@code {目标变量名: 取值表达式}}），用 {@link #resolveMapValue} 求值后 REPLACE 写进共享 state 的 delta。
     * 对 {@code _in}：目标名是子流程入参名、表达式从父 state 取值（子图随后经 updateState 读到）；
     * 对 {@code _out}：目标名是父变量名、表达式从子图写回的共享 state 取子结果（依赖增量2b 已证的 merge-back）。
     */
    private AsyncNodeAction mappingAction(Map<String, String> mapping, Long executionId,
                                          String threadName, AgentExecutionContext agentContext) {
        return AsyncNodeAction.node_async(state -> {
            WorkflowContext ctx = new WorkflowContext(state, executionId, threadName, agentContext);
            Map<String, Object> delta = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                delta.put(e.getKey(), resolveMapValue(e.getValue(), ctx));
            }
            return delta;
        });
    }

    /**
     * 求一个映射取值表达式（inputMap/outputMap 的 value）：
     * <ul>
     *   <li><b>单个整占位符</b>（{@code {{$.x}}}，整个表达式就是一个占位符）→ {@link StatePathNavigator#navigate}
     *       取<b>原始对象</b>，保住 List/Map/Number 类型（🔴 不能用 {@link StateTemplateResolver#resolve}，它会把
     *       对象/数组 stringify 成 JSON 串、数字变字符串，丢掉类型）；</li>
     *   <li>其余（混合模板 {@code "{{$.a}}-x"}、纯文本、多占位符）→ {@link StateTemplateResolver#resolve} 字符串插值。</li>
     * </ul>
     * 两者对「取不到的路径」口径一致（返回 null / 空串），不会因一个占位符炸掉整条流程。
     */
    private static Object resolveMapValue(String expr, WorkflowContext ctx) {
        if (expr == null) {
            return null;
        }
        Matcher m = SINGLE_PLACEHOLDER.matcher(expr.trim());
        if (m.matches()) {
            return StatePathNavigator.navigate(ctx.data(), m.group(1));
        }
        return StateTemplateResolver.resolve(expr, ctx);
    }

    /**
     * 校验 sub_workflow 的 {@code inputMap}/{@code outputMap}（DSL §4.3 L119），返回规整后的 {@code {目标变量名: 取值表达式}}。
     *
     * <p>契约：字段缺省或为 {@code null} → 空表（无映射）；存在则必须是对象，键非空且为合法变量名
     * （{@link #VAR_NAME}，键是写入 state 的目标名），值必须是非空字符串（取值表达式/模板）。
     * 空对象 {@code {}} 合法且等价于「无映射」。任何违约 → {@code WORKFLOW_DSL_INVALID}（清晰中文，绝不静默忽略）。
     */
    private static Map<String, String> validateMapping(NodeDTO node, String field, String subNodeId) {
        Object raw = node.getConfig() == null ? null : node.getConfig().get(field);
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "sub_workflow 节点(" + subNodeId + ") 的 " + field
                            + " 必须是对象（键=目标变量名，值=取值表达式），收到：" + raw);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = e.getKey() == null ? null : String.valueOf(e.getKey()).trim();
            if (key == null || key.isEmpty()) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "sub_workflow 节点(" + subNodeId + ") 的 " + field + " 含空键（键必须是目标变量名）");
            }
            if (!VAR_NAME.matcher(key).matches()) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "sub_workflow 节点(" + subNodeId + ") 的 " + field + " 键(" + key
                                + ") 不是合法变量名（须字母开头，仅含字母/数字/下划线，" + VAR_NAME.pattern() + "）");
            }
            Object v = e.getValue();
            if (!(v instanceof String s) || s.trim().isEmpty()) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "sub_workflow 节点(" + subNodeId + ") 的 " + field + " 键(" + key
                                + ") 的值必须是非空字符串（取值表达式/模板），收到：" + v);
            }
            result.put(key, s);
        }
        return result;
    }

    private static boolean isNotEmptyMap(Map<?, ?> m) {
        return m != null && !m.isEmpty();
    }

    /**
     * 把 iteration 节点展开成 SAA 原生<b>环形</b>子图（DSL §4.3 L118、§6 L237）
     *
     * <p>与 agent_loop 同属「合成环形子图」家族，区别在循环体是<b>真实 DSL body 节点链</b>（而非合成的 reason/act）。
     * body 节点写在顶层 nodes[] 但已被 {@link #collectIterationBodyIds} 从父图排除；这里按 {@code config.body} 的顺序
     * 取出、逐个用 {@link #wrap} 预包成 action（trace 前缀 {@code <iterId>-} → <b>每 item × 每 body 节点各落一行独立
     * trace</b>，逐 item token 归因天然成立），交给 {@link IterationGraphBuilder} 接线成 {@code _guard→body链→_advance}
     * 环，再 {@code graph.addNode(iterId, subGraph)} 内嵌。父图连边 {@code X→iterId→Y} 照常（第 2 步无需特判）。
     *
     * <p>🔴 body 节点在此才 {@code registry.get}（校验有无执行器）并 wrap —— 它们<b>不</b>走第 1 步的父图 addNode，
     * 故其执行器调用、trace、身份贴回全发生在这层预包里，与父图普通节点完全一致，只是落进了子图命名空间。
     */
    private void addIterationNode(StateGraph graph, NodeDTO node, Map<String, NodeDTO> nodeById,
                                  WorkflowDefDTO def, Long executionId, String threadName,
                                  AgentExecutionContext agentContext) throws GraphStateException {
        String iterId = node.getId();
        List<NodeDTO> bodyNodes = resolveIterationBodyNodes(node, nodeById); // 有序；id 已由 collectIterationBodyIds 校验
        Map<String, AsyncNodeAction> bodyActions = new LinkedHashMap<>();
        for (NodeDTO bodyNode : bodyNodes) {
            NodeExecutor executor = registry.get(bodyNode.getType());
            bodyActions.put(bodyNode.getId(),
                    wrap(bodyNode, executor, executionId, threadName, agentContext, iterId));
        }
        StateGraph iterGraph = iterationGraphBuilder.build(node, bodyNodes, bodyActions, def);
        graph.addNode(iterId, iterGraph);
        log.info("[addIterationNode][iteration 节点({}) 内嵌 foreach 环形子图（body={}），执行({})]",
                iterId, bodyNodes.stream().map(NodeDTO::getId).toList(), executionId);
    }

    /**
     * 按 {@code config.body} 的顺序把 id 解析成 {@link NodeDTO} 链（id 合法性已由 {@link #collectIterationBodyIds} 前置校验，
     * 这里的 null 兜底纯防御）
     */
    private List<NodeDTO> resolveIterationBodyNodes(NodeDTO iterNode, Map<String, NodeDTO> nodeById) {
        List<NodeDTO> bodyNodes = new ArrayList<>();
        for (Object o : iterNode.configList("body")) {
            String id = o == null ? null : String.valueOf(o).trim();
            NodeDTO n = id == null ? null : nodeById.get(id);
            if (n == null) {
                throw exception(WORKFLOW_DSL_INVALID,
                        "iteration 节点(" + iterNode.getId() + ") 的 body 引用了不存在的节点：" + id);
            }
            bodyNodes.add(n);
        }
        return bodyNodes;
    }

    // ==================== 校验 ====================

    private void validate(WorkflowDefDTO def) {
        if (def == null) {
            throw exception(WORKFLOW_DSL_INVALID, "DSL 为空");
        }
        if (def.getNodes() == null || def.getNodes().isEmpty()) {
            throw exception(WORKFLOW_DSL_INVALID, "DSL 至少要有一个节点");
        }
        Set<String> seen = new HashSet<>();
        for (NodeDTO node : def.getNodes()) {
            if (node.getId() == null || node.getId().isEmpty()) {
                throw exception(WORKFLOW_DSL_INVALID, "存在缺少 id 的节点");
            }
            if (!seen.add(node.getId())) {
                throw exception(WORKFLOW_DSL_INVALID, "节点 id 重复：" + node.getId());
            }
            if (node.getType() == null || node.getType().isEmpty()) {
                throw exception(WORKFLOW_DSL_INVALID, "节点(" + node.getId() + ") 缺少 type");
            }
        }
        // 写进 state 的名字必须合法（台账 E12）。放在 validate 末尾 ⟹ 编译与「发布闸门的深层干跑」
        // 两条路径同时受保护（dryRunValidate 复用 buildStateGraph），且 sub_workflow 的子 def
        // 递归进来时也会各扫一遍。
        validateStateKeyNames(def);
    }

    /**
     * 校验「会被写进 state 的名字」全部合法（台账 E12）
     *
     * <p>🔴 <b>为什么必须有这一条</b>：state 的键就是变量名，而<b>写入侧此前全仓没有任何合法性校验</b>。
     * 四个执行器都用 {@code configStr(port.configKey(), port.defaultName())} 取 outputVar
     * （{@link NodeDTO#configStr(String)} 是 {@code String.valueOf(v)}，<b>不 trim</b>），
     * 随后原样 {@code delta.put} 进 state。于是 outputVar 填 {@code " x "} 时运行期写的键就是 {@code " x "}，
     * 而下游 {@code {{$.x}}} 取不到值 —— 🔴 <b>静默拿到空、且不报错</b>。
     * 这正是本平台头号缺陷「配了不生效且无提示」，而 outputVar 是<b>每个</b>
     * llm / tool / script / knowledge_retrieval 节点都有的必填高频格。
     *
     * <p><b>判据只有一个</b>：{@link #VAR_NAME}，即「什么名字可以当 state 键」的唯一权威。
     * 🔴 刻意<b>不</b>在 {@code WorkflowDslValidator} 里另写一条规则 —— 那样正则就有两份，
     * 而两份正则迟早分岔（{@code iteration.itemVar} 那一格就是这么被发现的）。
     *
     * <p>🔴 <b>顺着 {@link NodeIoContract.Contract#outputs()} 走，不自己列键名</b>：
     * 手写一份「哪些 config 键算 output 端口」的清单，就会漏掉执行器自己 {@code configStr} 的私有键，
     * 而漏掉的那一格恰恰最难查（它不报错）。
     *
     * <p>⚠️ <b>null 与空串一律放过</b>，不在本条里报：
     * {@code defaultName} 为 null 的端口（citationsVar / condition.llm.outputVar / agent_loop.outputVar）
     * 作者没填时解析结果就是 null；{@code variables[]} 里的空 key 则由
     * {@link KeyStrategyFactoryGenerator} 静默跳过（与本方法同口径，两处不会各报一次）。
     * 「没填该不该回落成缺省名」是另一件事（台账 C3 的分岔：校验器说要报、溯源侧说回落），
     * 混进本条会让报错指向错误的修法。
     * 🔴 本行原先还并列举了「C8 的缺省名没注册 keyStrategy」当作本条放过 null/空串的理由之一，
     * 那半<b>已经落地</b>（{@code KeyStrategyFactoryGenerator} 遍历
     * {@link NodeIoContract#defaultOutputNames()} 无条件注册），删掉这句以免读者以为洞还在。
     * 但「没填 → 回落成缺省名 → 那个键能不能活过 resume」这条链本方法<b>始终</b>不管：
     * 它只判名字合法性，活不活得下来由注册侧决定，两者判据不同、不能合并。
     * 🔴 但<b>纯空白不放过</b>：{@code configStr} 只在 null / 空串时回落，填 {@code "   "} 时执行器
     * 真的会拿它当键写进 state ⟹ 它就是一个非法名，正是本条要拦的。
     *
     * <p>🟡 <b>与 C3 的关系</b>：本条落地后，纯空白 outputVar 在编译期就被拒，
     * 于是 C3 那个「校验器说要报、溯源侧说回落」的分岔<b>在运行期不再可达</b>。
     * 但 C3 仍是代码层的一致性债（两处判据不同源），不因本条而自动闭合。
     */
    private static void validateStateKeyNames(WorkflowDefDTO def) {
        // 1. 顶层 variables[].key —— 它们同样是要注册进 state 的键
        if (def.getVariables() != null) {
            for (VariableDTO variable : def.getVariables()) {
                if (variable != null) {
                    checkStateKeyName(variable.getKey(), "variables[] 的 key");
                }
            }
        }
        // 2. 每个节点的每个 output 端口。iteration.body 与 parallel.branches 的节点也写在顶层 nodes[]，
        //    所以这一趟就全覆盖了；sub_workflow 的子 def 由递归的 buildStateGraph 各自扫。
        for (NodeDTO node : def.getNodes()) {
            NodeIoContract.Contract contract = NodeIoContract.of(node.getType());
            if (contract == null) {
                // 未知 type：第 1 步加节点时 registry.get 会报「该 type 没有执行器」，这里没有端口清单可查
                continue;
            }
            for (NodeIoContract.OutPort port : contract.outputs()) {
                // 🔴 取「这个端口执行期真正写进 state 的键名」必须走校验器那个 writtenVarOf，
                //    不要在这里内联 node.configStr(port.configKey(), port.defaultName())。
                //    本类住在 engine 域内，而 NodeIoContractRuntimeReadScanTest 要求域内 config 读取点的
                //    键名<b>是字面量</b>（否则「运行期读了哪些键」就没法从源码量出来，它那套正向差集
                //    全建立在这个可量性上），且它明令禁止为泛型键名开白名单 ⟹ 这一读只能落在域外。
                //    顺带的好处是「执行期到底写哪个键」的推理只有一份，不会与本条悄悄分岔。
                //    完整理由记在 writtenVarOf 自己的 javadoc 里。
                checkStateKeyName(WorkflowDslValidator.writtenVarOf(node, port),
                        "节点(" + node.getId() + ") 的 " + port.configKey());
            }
        }
    }

    /**
     * 单个名字的合法性判定。报错文案刻意带上<b>后果</b>而不只是<b>规则</b> ——
     * 只说「名字不合法」的话，作者看不出为什么一个多余的空格值得拦下来。
     *
     * <p>🔴 {@code where} 必须传<b>一整句人话</b>（如 {@code 节点(n3) 的 outputVar}），
     * 不要把「哪一格」拆成单独的标识符形状字面量当第二个参数传进来。
     * 本类住在 {@code engine} 域内，而 {@code NodeIoContractRuntimeReadScanTest} 会把域内
     * 每一个「小写驼峰形状的字符串字面量」都当成疑似 config 键的读取点来盘问 ——
     * 它没有词汇表达「这只是错误文案里的一个词」，于是会要求你去认领清单里登记。
     * 折成一句就压根不进它的视野（它只认纯标识符形状的字面量）。这是那套扫描自己推崇的处置：
     * <b>让同名不再发生，而不是给同名发通行证</b>。
     *
     * @param where 出错那一格的可读描述，如 {@code 节点(n3) 的 outputVar} / {@code variables[] 的 key}
     */
    private static void checkStateKeyName(String name, String where) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (!VAR_NAME.matcher(name).matches()) {
            throw exception(WORKFLOW_DSL_INVALID,
                    where + "(\"" + name + "\") 不是合法变量名（须字母开头，"
                            + "仅含字母/数字/下划线，" + VAR_NAME.pattern() + "）："
                            + "执行器会照这个名字原样把结果写进 state，而下游按它取值时取不到 —— "
                            + "静默拿到空值且不报错。请去掉多余的空格或改成合法名字");
        }
    }

    private void validateEdgeEndpoints(EdgeDTO edge, Set<String> nodeIds) {
        checkEndpoint(edge.getFrom(), nodeIds, "from");
        if (!edge.isConditional()) {
            checkEndpoint(edge.getTo(), nodeIds, "to");
        }
    }

    private void checkEndpoint(String endpoint, Set<String> nodeIds, String field) {
        if (endpoint == null || endpoint.isEmpty()) {
            throw exception(WORKFLOW_DSL_INVALID, "边缺少 " + field);
        }
        String mapped = mapEndpoint(endpoint);
        if (!StateGraph.START.equals(mapped) && !StateGraph.END.equals(mapped) && !nodeIds.contains(endpoint)) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "边的 " + field + "(" + endpoint + ") 指向不存在的节点");
        }
    }

    private List<EdgeDTO> safeEdges(WorkflowDefDTO def) {
        return def.getEdges() == null ? List.of() : def.getEdges();
    }

    /**
     * 保留端点映射：DSL 的 "START"/"END" → SAA 常量；其余原样（节点 id）
     */
    private String mapEndpoint(String endpoint) {
        if ("START".equals(endpoint)) {
            return StateGraph.START;
        }
        if ("END".equals(endpoint)) {
            return StateGraph.END;
        }
        return endpoint;
    }

    /**
     * 增量2c：把「指向 mapped sw 的入边端点」重定向到它的 {@code _in} 合成节点。
     * id 是配了 inputMap 的 sw → 返回 {@code _<id>_in}；否则（含未配映射的 sw、普通节点）原样返回。
     * 与 {@link #mapEndpoint} 组合用：{@code mapEndpoint(redirectTo(to, swRedirect))}。
     */
    private static String redirectTo(String id, Map<String, String[]> swRedirect) {
        String[] r = swRedirect.get(id);
        return (r != null && r[0] != null) ? r[0] : id;
    }

    /**
     * 增量2c：把「mapped sw 的出边端点」重定向到它的 {@code _out} 合成节点。
     * id 是配了 outputMap 的 sw → 返回 {@code _<id>_out}；否则原样返回。
     */
    private static String redirectFrom(String id, Map<String, String[]> swRedirect) {
        String[] r = swRedirect.get(id);
        return (r != null && r[1] != null) ? r[1] : id;
    }

    /**
     * 估算本次编译的 recursionLimit（全局兜底，非收敛保证）
     *
     * <p>普通节点各占约 1 个 superstep；一个 agent_loop 子图最坏占
     * {@code 2*maxSteps}（每步 reason+act）{@code + maxParseRetries}（解析失败重回 reason，step 不++）
     * {@code + 2}（末轮收口的 reason、以及 START/END 余量）。一个 iteration 子图最坏占
     * {@code maxIterations*(bodyLen+2)}（每轮 guard + bodyLen 个 body 节点 + advance）{@code + 2}（余量）——
     * 迭代次数 = 列表长度（编译期未知），故按 {@code settings.maxIterations} 上界估；guard 运行期 fail-close
     * 保证实际轮数 ≤ maxIterations，与 recursionLimit 预算同源（都走 {@link IterationGraphBuilder#resolveMaxIterations}），
     * 不会出现「guard 放行但 recursionLimit 不够被静默截断」的错配。再加固定边余量，并与 SAA 默认值取大 ——
     * <b>只上调、不下调</b>，保证无循环节点的图沿用默认 100、不被误降。
     *
     * <p>⚠️ 仅统计<b>顶层</b> def 的 agent_loop / iteration：嵌套在 sub_workflow 子图里的循环不在此列，
     * 由默认值下限（100）兜底。单个 loop（maxSteps≤6）约 16 个 superstep、单个 iteration（maxIterations≤100、
     * bodyLen 小）最坏约 300 个 superstep，除非子流程里塞了很多大循环，否则 100 足够；真要支持那种极端编排，
     * 应改为递归统计子 def（本增量不做）。
     */
    private int computeRecursionLimit(WorkflowDefDTO def) {
        int budget = 0;
        if (def.getNodes() != null) {
            for (NodeDTO node : def.getNodes()) {
                if ("agent_loop".equals(node.getType())) {
                    int maxSteps = node.configInt("maxSteps", loopProperties.getDefaultMaxSteps());
                    budget += 2 * Math.max(1, maxSteps) + loopProperties.getMaxParseRetries() + 2;
                } else if ("iteration".equals(node.getType())) {
                    int bodyLen = node.configList("body").size();
                    int maxIterations = IterationGraphBuilder.resolveMaxIterations(def);
                    budget += maxIterations * (bodyLen + 2) + 2;
                } else if ("sub_workflow".equals(node.getType())) {
                    // sw 节点本身占 1（子图内部 superstep 由默认下限兜底，同增量2b）；
                    // 配了映射再 +2（父侧 _in / _out 两个合成节点各占 1 个 superstep）
                    budget += 1;
                    if (isNotEmptyMap(node.configMap("inputMap")) || isNotEmptyMap(node.configMap("outputMap"))) {
                        budget += 2;
                    }
                } else {
                    budget += 1;
                }
            }
        }
        return Math.max(SAA_DEFAULT_RECURSION_LIMIT, budget + 20);
    }

    /**
     * 校验 {@code wait} 节点配置并按 {@code waitType} 路由（编译期 step-1 fail-fast 的唯一入口）。
     *
     * <p>三态（DSL §4.3，取值全集见 {@link WaitTypeEnum}）：{@code duration}（定时，增量4a）→ 校验并解析
     * {@code duration}；{@code event}（外部事件，增量4b）→ 校验 {@code eventKey}；{@code hitl}（人工）→ <b>拒</b>，
     * 因为它与现成的 {@code hitl.confirm} 节点完全等价（挂起、resume_at/event_key 皆 NULL、等人工 resume-stream），
     * 做它纯属重复表面，请作者直接用 {@code hitl.confirm}。缺失/未知 waitType 同样拒。
     *
     * <p>🔴 这里是「waitType 的取值合不合法」在全仓的<b>唯一权威</b>：{@link WaitTypeEnum#parse}
     * <b>trim 但大小写敏感、认不出来返回 null</b>，故 {@code EVENT} 走的是「未知」那一支而不是 event 那一支。
     * {@code WorkflowDslValidator} 的 V14 刻意与它逐字同口径（那一侧的注释写明了理由：放宽会让同一个根因
     * 被两个校验器各说一遍）。别在这里换成 {@code equalsIgnoreCase} —— 那会让 V14 与前端
     * {@code triggerCases.spec.ts} 钉住的「大小写敏感」同时变成假话。
     *
     * <p>🟡 E2 之前这里的 {@code switch} 直接写在<b>字符串</b>上（{@code case "duration" ->}），
     * 而 {@code duration} 又是 wait 的一个 config <b>键名</b>（见 {@link #resolveWaitDurationSeconds}）——
     * 一字两义。改成 switch 在枚举上之后，case 标签是<b>枚举常量</b>而不是字符串，
     * 那个歧义在语法上就不存在了；{@code NodeIoContractRuntimeReadScanTest} 里为此认领的两条
     * 「这个 duration 是取值不是键」的白名单条目也随之删掉。
     */
    public static void validateWaitConfig(NodeDTO node) {
        String raw = node.configStr("waitType");
        WaitTypeEnum waitType = WaitTypeEnum.parse(raw);
        if (waitType == null) {
            // 空白与「填了但认不出来」是两条不同的错：前者的出路是补上这一格，后者是改掉这个值。
            // 合并成一条会让作者对着「未知 waitType()」猜自己到底是漏填还是填错（与 E5 的 itemVar 同一条纪律）
            throw exception(WORKFLOW_DSL_INVALID, raw == null || raw.trim().isEmpty()
                    ? "wait 节点(" + node.getId() + ") 缺少 waitType（" + WaitTypeEnum.supportedValuesText() + "）"
                    : "wait 节点(" + node.getId() + ") 的 waitType(" + raw.trim() + ") 未知，仅支持 "
                    + WaitTypeEnum.supportedValuesText());
        }
        // 🔴 刻意写成 switch <b>表达式</b>而不是 switch 语句：表达式对枚举做<b>穷尽性检查</b>，
        //    将来往 WaitTypeEnum 里加了第四档却忘了回来改这里，结果是<b>编译不过</b> ——
        //    而不是「静默跳过配置校验、建出一张没验过的图、到运行期才炸」。
        //    这条性质是变异探针逼出来的：原先这里放的是一条 {@code default -> throw IllegalStateException}，
        //    把它删掉之后 297 条用例<b>全绿</b>（三个常量都覆盖了，那一支根本不可达）⟹ 它测不到，
        //    而测不到的守卫等于没有守卫。穷尽性交给编译器，才不需要用例去钉。
        //    左边这个变量只为把 switch 变成表达式（表达式不能当语句用），它的值没人要。
        @SuppressWarnings("unused")
        Object validated = switch (waitType) {
            case DURATION -> resolveWaitDurationSeconds(node);
            case EVENT -> resolveWaitEventKey(node);
            case HITL -> throw exception(WORKFLOW_DSL_INVALID,
                    "wait 节点(" + node.getId() + ") 的 waitType=" + WaitTypeEnum.HITL.getValue()
                            + " 等价 hitl.confirm 节点，请直接用 hitl.confirm");
        };
    }

    /**
     * 解析并校验 {@code wait(waitType=duration)} 节点的 {@code duration}，返回等待秒数（增量4a）。
     *
     * <p>🔴 只该被 {@link #validateWaitConfig} 的 duration 分支、或 Service 已确认 {@code waitType==duration} 后调用
     * （event 节点没有 duration，误走这里会报「缺少 duration」）。编译期与 Service 中断时刻共用一份解析，
     * 杜绝「编译放过、运行炸」的口径分裂。
     *
     * <p>契约：{@code duration} 必填，可为<b>数字（秒）</b>或 <b>ISO-8601 串</b>（如 {@code "PT3S"}、{@code "PT1M30S"}），
     * 必须 {@code >0} 且 {@code <=}{@link #MAX_WAIT_SECONDS}。任何违约 → {@code WORKFLOW_DSL_INVALID}（清晰中文）。
     */
    public static long resolveWaitDurationSeconds(NodeDTO node) {
        Object raw = node.getConfig() == null ? null : node.getConfig().get("duration");
        if (raw == null) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "wait 节点(" + node.getId() + ") 的 waitType=duration 缺少 duration");
        }
        long seconds;
        if (raw instanceof Number n) {
            seconds = n.longValue();
        } else {
            String s = String.valueOf(raw).trim();
            if (s.isEmpty()) {
                throw exception(WORKFLOW_DSL_INVALID, "wait 节点(" + node.getId() + ") 的 duration 为空");
            }
            try {
                seconds = Long.parseLong(s);                       // 纯数字串 = 秒
            } catch (NumberFormatException ignore) {
                try {
                    seconds = Duration.parse(s).getSeconds();      // ISO-8601，如 PT3S / PT1M30S
                } catch (RuntimeException e) {
                    throw exception(WORKFLOW_DSL_INVALID,
                            "wait 节点(" + node.getId() + ") 的 duration 无法解析（须数字秒或 ISO-8601 如 PT3S）：" + s);
                }
            }
        }
        if (seconds <= 0) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "wait 节点(" + node.getId() + ") 的 duration 必须 > 0 秒，当前：" + seconds);
        }
        if (seconds > MAX_WAIT_SECONDS) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "wait 节点(" + node.getId() + ") 的 duration 超过上限 " + MAX_WAIT_SECONDS + " 秒，当前：" + seconds);
        }
        return seconds;
    }

    /**
     * 解析并校验 {@code wait(waitType=event)} 节点的 {@code eventKey}，返回该键（增量4b）。
     *
     * <p>编译期（{@link #validateWaitConfig} 的 event 分支）调它 fail-fast；Service 在<b>中断时刻</b>再调它取键落
     * {@code event_key} 列，供 {@code POST /agent/workflow/event} 按 key 匹配唤醒。两处共用一份解析。
     *
     * <p>契约：{@code eventKey} 必填、trim 后非空、长度 {@code <=}{@link #MAX_EVENT_KEY_LENGTH}（与 DDL varchar(128) 对齐）。
     * 违约 → {@code WORKFLOW_DSL_INVALID}（清晰中文）。
     */
    public static String resolveWaitEventKey(NodeDTO node) {
        Object raw = node.getConfig() == null ? null : node.getConfig().get("eventKey");
        String key = raw == null ? null : String.valueOf(raw).trim();
        if (key == null || key.isEmpty()) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "wait 节点(" + node.getId() + ") 的 waitType=event 缺少 eventKey（或为空白）");
        }
        if (key.length() > MAX_EVENT_KEY_LENGTH) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "wait 节点(" + node.getId() + ") 的 eventKey 超过上限 " + MAX_EVENT_KEY_LENGTH
                            + " 字符，当前：" + key.length());
        }
        return key;
    }

    /**
     * 🔴 取「这一次究竟挂起在哪个节点」的<b>唯一正确口径</b>：checkpoint 快照的 {@code next()}
     *
     * <h3>为什么不能用 {@code InterruptionMetadata.node()}</h3>
     * 实测（SAA 1.1.2.2）：{@code InterruptionMetadata.node()} 给的是
     * <b>最后一个跑完的节点</b>，而不是 {@code StateSnapshot.next()}（= 真正被 {@code interruptsBefore}
     * 拦下的那个）。于是<b>形状决定对错</b>：
     * <table border="1">
     *   <caption>五种形状的实测值（✅ = 碰巧对，❌ = 指到了前一个节点）。前四种由
     *   {@code SuspendNodeIdRuntimeTest} 长期钉住；第五种是当时用一次性探针量的，
     *   只为说明「两个 wait 串联时停在第一个」，未进常驻测试。</caption>
     *   <tr><th>DSL 形状</th><th>{@code InterruptionMetadata.node()}</th><th>{@code StateSnapshot.next()}</th></tr>
     *   <tr><td>{@code START → w(wait)}</td><td>w ✅</td><td>w</td></tr>
     *   <tr><td>{@code START → t(tool) → w(wait)}</td><td><b>t</b> ❌</td><td>w</td></tr>
     *   <tr><td>{@code START → t → m(llm) → w}</td><td><b>m</b> ❌</td><td>w</td></tr>
     *   <tr><td>{@code START → t → gate(hitl.confirm)}</td><td><b>t</b> ❌</td><td>gate</td></tr>
     *   <tr><td>{@code START → w(duration) → w2(event)}</td><td>w ✅（停在第一个）</td><td>w</td></tr>
     * </table>
     *
     * <p>即：<b>只有挂起节点正好是 START 之后的第一个节点时，它才碰巧是对的</b>。
     * 而既有的三个运行时单测（{@code WaitNodeRuntimeTest} / {@code HitlConfirmResumeRuntimeTest} /
     * {@code HitlBpmApprovalResumeRuntimeTest}）的 DSL <b>恰好全是那一个形状</b>，
     * 于是这个前提从来没被证伪过 —— 真实的编排里挂起节点前面几乎总有 llm/tool/condition。
     *
     * <p>⚠️ 别把上表推广成「{@code InterruptionMetadata.node()} 就是 {@code StateSnapshot.node()}」：
     * 形状 S1（{@code START → w}）下快照的 {@code node()} 实测是 {@code __START__}，而
     * {@code InterruptionMetadata.node()} 是 {@code w}，两者并不相等。SAA 内部究竟怎么造的这个对象，
     * 本仓拿不到源码、也没必要去猜 —— 本方法只依赖<b>实测成立</b>的那一条：
     * {@code next()} 在全部五种形状下都等于挂起节点。
     *
     * <h3>拿错 id 会怎样（三条全是「配置存下了、运行期不生效、且无提示」）</h3>
     * 这个 id 要回 {@link WorkflowDefDTO#getNodes()} 里按 {@code id} + {@code type} 双匹配查节点，
     * 拿成前一个节点就<b>一条都匹配不上</b>，而三处 resolveXxx 匹配不上时都是<b>静默返回 null</b>：
     * <ul>
     *   <li>{@code wait(duration)}：{@code resume_at} 不落库 ⟹ 轮询器永远扫不到 ⟹ 静默卡在 INTERRUPTED</li>
     *   <li>{@code wait(event)}：{@code event_key} 不落库 ⟹ {@code POST /agent/workflow/event} 永远匹配不上 ⟹ 同上</li>
     *   <li>{@code hitl.bpm_approval}：<b>审批实例压根不发起</b> ⟹ 审批人看不到任务 ⟹ 卡死且<b>无人能恢复</b></li>
     * </ul>
     * <p>⚠️ {@code hitl.confirm} 反倒没事：聊天内恢复走 {@code resume-stream}，这三样一个都不需要。
     * 它因此成了掩护 —— 手工确认流程一直好用，让人以为挂起/恢复整条链都是好的。
     *
     * @return 真正被拦下的那个节点 id；取不到快照时返回 {@code null}。
     *         🔴 <b>刻意不回落成 {@code output.node()}</b>：那是个<b>错值</b>。落库/回显一个错节点比留 NULL 更坏 ——
     *         NULL 只是「说不清在哪恢复」，错值会让救援的人去恢复一个<b>早就跑完了</b>的节点，还确信自己是对的。
     */
    public static String resolveSuspendNodeId(CompiledGraph graph, RunnableConfig config) {
        return graph.stateOf(config).map(StateSnapshot::next).orElse(null);
    }

}
