package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.def.AgentDefMapper;
import cn.enlev.ainb.module.agent.enums.WaitTypeEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.EdgeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.GraphReachability;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.TriggerDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.VariableDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.io.NodeIoContract;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.StateTemplateResolver;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeItem;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.DryRunResult;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ManifestArgs;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.UntrustedInputTracer;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.ToolKeySuggester;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.ToolResultShapes;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelCreateTool;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelParamResolver;
import cn.enlev.ainb.module.agent.tool.impl.form.FormFieldFactory;
import cn.enlev.ainb.module.agent.tool.impl.form.FormPublishBundleTool;
import cn.enlev.ainb.module.agent.tool.impl.form.FormSaveDraftTool;
import cn.enlev.ainb.module.agent.tool.impl.process.ProcessCreateAndDeployTool;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.bpm.api.definition.BpmModelApi;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelRespDTO;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import cn.enlev.ainb.module.system.api.menu.MenuApi;
import cn.enlev.ainb.module.system.api.menu.dto.MenuSimpleRespDTO;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workflow DSL 的<b>收集式</b>基础结构校验（增量7a，plan-as-workflow 生成器）
 *
 * <h3>为什么编译器里已有 fail-fast 校验，还要再写一个收集式的</h3>
 * 两者的<b>消费者不同</b>：
 * <ul>
 *   <li>{@code DslToStateGraphCompiler.validate} 是<b>执行守卫</b>——遇到第一个错就抛
 *       {@code WORKFLOW_DSL_INVALID}，因为它服务于「已经在跑真实执行」的场景，
 *       快速失败、绝不带病编译是对的；</li>
 *   <li>本类服务于 <b>LLM 自修正</b>——校验结果要回灌给模型让它一次改对。
 *       若也 fail-fast，模型改完一个错再撞下一个，{@code maxRounds} 几轮就烧光了，
 *       而每轮都是一次真实的模型调用、一次真实的计费（{@link AgentValidateResult} 类注释的同一条纪律）。
 *       所以这里<b>把基础结构问题一次全列出来</b>。</li>
 * </ul>
 *
 * <h3>为什么错误文案要写「怎么改」而不是只写「哪里错」</h3>
 * 反馈是写给 LLM 看的。「节点 id 不合法」不如「节点 id {@code OrderInfo} 不合法，
 * 应改为小写字母开头、仅含小写字母/数字/下划线，如 {@code order_info}」——
 * 后者模型照做就对了，前者它可能改成 {@code orderInfo} 或 {@code order-info} 再撞一次。
 *
 * <h3>只做「不必建图」的那一半，深校验交给编译器干跑</h3>
 * parallel/iteration/sub_workflow/wait/agent_loop 各自的 config 合法性、body 接线、
 * 子流程可加载性等<b>深层</b>校验，由 {@code DslToStateGraphCompiler.dryRunValidate}
 * 复用 {@code buildStateGraph} 兜住（那里是权威、且已随执行路径反复验证）。
 * 本类不重建那套结构校验，只补七类它够不着的：一类是「建图不看语义」的
 * （控件类型、工具风险级、写了个没人会执行的节点），一类是「建图不查库」的（表、列、流程标识、模型、子流程 code），
 * 一类是「建图不看触发器」的（无头流程里的人工闸口与外部唤醒方、匿名 webhook 入站面，见 V11/V13/V14/V15），
 * 一类是「建图不看运行期怎么重载状态」的（写进 state 却没在 {@code variables[]} 里声明的键，
 * 会在 resume 那一刻被 SAA 静默丢掉，见 V19/V23），
 * 一类是「建图不看聊天侧怎么把结果说给人听」的（走到 END 却没人写回复键的那条腿，见 V20），
 * 一类是「建图不看条件判据在运行期取不取得到值」的（{@code condition.rule} 用裸键名读<b>顶层</b> state，
 * 没人写就恒为空值、除 {@code not_exists} 外全部算子判不命中，于是那条分支图上有、运行期永远走不到，见 V24），
 * 还有一类是「建图<b>假设</b>了条件边只从条件节点出发、却从不检查这个假设」的
 * （条件边纯读 {@code __route_<节点id>}，而全平台只有两种条件节点写它；接在别的节点后面
 * ⟹ cases 恒不命中、每次执行都落 default，见 V25）。
 *
 * <p>下面这几条为什么非得在本类里做一遍（有的与深层<b>重复</b>、有的深层压根看不见），
 * 理由各自写在方法注释里：
 * <ul>
 *   <li>{@link #validateSaveDraftWidgets}：form_save_draft 的控件类型错在结构上和干跑上都看不出来
 *       （参数合法、图也建得起来），只有运行期执行到那一步才炸，而那时自修正回路已经结束；</li>
 *   <li>{@link #validateSaveDraftOptionSources}：同一个 args 里的<b>选项来源</b>（V29）。
 *       比控件类型那条更隐蔽 —— 控件名写错至少运行期会炸一次，而编出来的 {@code dictType}
 *       或「一个来源都没配」是<b>连炸都不炸</b>的：表单存得进、发布也过得去，
 *       用户拿到的是一个点开为空的下拉。干跑那层同样看不见（它只判 args 的形状）；</li>
 *   <li>{@link #validateToolRefs}：工具键的<b>声明风险级</b>。干跑那层确实也拦写工具进 agent_loop
 *       （{@code AgentLoopGraphBuilder}），但它是 fail-fast 的（只报第一个），且风险级要读
 *       <b>声明值</b>才推不动 —— 见 {@link ToolResolver#declaredRiskOf}；</li>
 *   <li>{@link #validateSaveDraftMainTable} 与 {@link #validateSaveDraftColumns}：绑不存在的表、
 *       映射不存在的列，在干跑上看不出来（建图不查库）；而「本流程自己建表」那种形状
 *       <b>连库都不用查</b>——建表参数就冻在同一份 DSL 里，跨节点对不上时干跑同样看不见，
 *       代价却比前两者重一档（DDL 不可回滚），见 {@link #declaredTableOfInDslCreator}（V30）；</li>
 *   <li>{@link #validateSaveDraftColumnPlaceholders}：列名写成占位符。清单层对
 *       {@code form_data_create/update} 的 {@code data} 有同型条目（{@code FormDataArgs#nestedPlaceholderFields}），
 *       但 {@code form_save_draft} 的 {@code fields[]} 那半边<b>没有</b>，干跑整项落 {@code NOT_PROBED}；</li>
 *   <li>{@link #validateSubWorkflowRefs}：深层那层<b>确实</b>报得出来（{@code addSubWorkflowNode}
 *       调的是同一个 {@code getSubWorkflowDef}），但它是 fail-fast 的，且要先把整棵子图建起来
 *       才轮得到第二个错的 —— 与 {@link #validateToolRefs} 那条同理由。</li>
 *   <li>{@link #validateTriggerRiskGates} / {@link #validateHeadlessHitlConfirm} /
 *       {@link #validateWaitEventWakeup} / {@link #validateWebhookAuth}：深层<b>压根看不见</b>这四条 ——
 *       图建得好好的，节点配置也都合法，问题出在「触发器决定有没有人在场、
 *       以及这个入口对谁敞开」这个<b>图外</b>的事实上。
 *       而运行期也不会拒：它老老实实挂起、写下 {@code status=INTERRUPTED}，
 *       然后永远等一个不会来的唤醒。表现是「流程发布成功、每次触发都静悄悄地卡死一条执行记录」。</li>
 *   <li>{@link #validateUnreachableNodes}：深层<b>看得见但不拒</b> —— 编译器把不可达节点照样
 *       {@code addNode} 进图（{@code DslToStateGraphCompiler#buildStateGraph}），只是运行期永远调度不到它。
 *       图建得起来、干跑也过，作者却以为那个节点会跑。</li>
 *   <li>{@link #validateWrittenVarsDeclared}：深层<b>永远看不见</b> —— 建图与干跑都在同一次运行内，
 *       而这一条的危害要到<b>挂起后恢复</b>那一刻才发生（{@code OverAllState.input(checkpointState)}
 *       只保留注册过 KeyStrategy 的键）。沙箱里跑十遍都是绿的。</li>
 *   <li>{@link #validateChatLegsWriteReply}：深层与运行期<b>都不拒</b> —— 图合法、干跑过、执行 SUCCEEDED、
 *       副作用也真生效了，唯一露馅的地方是聊天里那句 fail-soft 兜底文案
 *       （{@code WORKFLOW_NO_OUTPUT_HINT}）。而它是「建议」口气的，读的人只会以为流程没跑完，
 *       不会想到是<b>某一条腿</b>上少了一个总结节点。</li>
 * </ul>
 *
 * <h3>两个入口：一个只读，一个要跑干跑</h3>
 * 🔴 分界<b>不是</b>「查不查库」—— {@link #validate} 一直都要查：控件契约、物理表清单、
 * 表结构、工具目录、流程模型、可用模型、子流程定义。这些都是<b>只读</b>查询，
 * 一次几十毫秒，放在生成器那条最多 6 轮的自修正回路里跑得起。
 * 真正的分界是 {@link #foldDryRunRejections} 折的那份变更清单：建一份要跑最多 <b>15 秒</b>的干跑，
 * 塞进 {@link #validate} 就等于让每一轮自修正都重跑一遍，而且干跑结论模型改一轮也变不了
 * （表名撞车要人去决定换哪个名字）。故清单由调用方建、由调用方传进来。
 *
 * <p>{@link #foldUntrustedWrites}（V16）<b>曾经</b>是那份清单的第二个消费者，台账 C9 之后不再是：
 * 它读的 {@code untrustedInputs[]} 自己是纯静态的、跑起来不要钱，只是当时只有清单构建器会产出它。
 * 原文据此得出「调用方没传清单时 V16 不生效」，并给了一条<b>对</b>的理由 ——
 * 在校验器里另起一份溯源就是 {@code UntrustedInputTracer} 的第二个副本（D7）。
 * 🔴 但那条理由只否掉了「抄一份」，没否掉「多开一个入口」：现在 {@code trace(WorkflowDefDTO)}
 * 与 {@code trace(ManifestContext)} 汇进同一个 private 实现，结构上没有第二份可漂，
 * 于是 AI 自修正回路（不建清单）也能收到 V16 了。
 * ⚠️ 上面那段「贵的是干跑」的分界<b>没有被这条改动动摇</b>：清单仍然由调用方建、由调用方传进来，
 * {@link #foldDryRunRejections} 仍然在没清单时一句话不说。
 *
 * @author 走进新时代
 */
@Component
public class WorkflowDslValidator {

    /** 节点 id / 变量键的命名规范（与 {@link NodeDTO#getId()} 注释、DDL 约束一致） */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    /** 抓取 {@code {{$.root...}}} 里的根变量名，供「引用是否解析得到」校验用 */
    private static final Pattern PLACEHOLDER_ROOT = Pattern.compile("\\{\\{\\s*\\$\\.([a-zA-Z][a-zA-Z0-9_]*)");

    /**
     * script 代码里对 {@code tools.xxx} 的<b>调用</b>（V22 用）。第 1 组是被调的那个名字，供文案点名。
     *
     * <p>三处刻意收窄，同一条理由：<b>宁可漏报也不误报</b> —— 误报会让规划期的自修正回路
     * 去改一个没病的地方，而每一轮都是一次真实计费（见 {@link #validateScriptToolCalls} 的免报条件那段）。
     * <ul>
     *   <li>{@code (?<![\w$.])} 不认属性访问：{@code state.tools.save()} 里那个 {@code tools} 是作者
     *       自己放进 state 的数据。{@code \b} 挡不住它（{@code .} 本身就是词边界）。</li>
     *   <li>{@code \s*\(} 要求<b>调用</b>形状：否则字符串字面量里提一句「tools.foo 不可用」也算命中。
     *       代价是 {@code const f = tools.form_save_draft} 这种「取出来存着、后面再调」的写法漏掉。</li>
     *   <li>{@code [A-Za-z_$][\w$]*} 只认点号后的标识符名，不认 {@code tools["form_save_draft"]}
     *       —— 下标写法实测没出现过，要认它就得开始理解语义，那已经超出「形状判据」。</li>
     * </ul>
     * 允许 {@code tools . xxx} 这种带空格的排版，否则模型换个换行风格就绕过去了。
     */
    private static final Pattern SCRIPT_TOOLS_CALL =
            Pattern.compile("(?<![\\w$.])(tools\\s*\\.\\s*[A-Za-z_$][\\w$]*)\\s*\\(");

    /**
     * script 代码里作者<b>自己声明</b>了一个叫 tools 的东西（V22 的免报条件）。
     *
     * <p>三种写法：声明（{@code var/let/const/function tools}）、赋值（{@code tools = }）、
     * 形参（{@code (tools)} / {@code (a, tools)}）。命中任何一种就说明那个 {@code tools.}
     * 是作者自己的对象，不是沙箱里不存在的工具句柄 —— 报了就是假警报。
     *
     * <p>⚠️ 这一条只用来<b>抑制</b>报错，所以它的误命中只造成漏报、不造成假警报，
     * 故这里刻意写得比 {@link #SCRIPT_TOOLS_CALL} 宽（不要求紧跟调用括号）。
     */
    private static final Pattern SCRIPT_TOOLS_SELF_DECLARED = Pattern.compile(
            "\\b(?:var|let|const|function)\\s+tools\\b|\\btools\\s*=[^=]|[(,]\\s*tools\\s*[,)]");

    /**
     * 脚本里的字符串字面量（单引号 / 双引号 / 反引号），V22 在匹配前先把它们抠掉。
     *
     * <p>为什么要抠：{@code return '请改用 tools.form_save_draft(...)'} 这种<b>字符串里提一句</b>的写法
     * 不是调用，而 {@link #SCRIPT_TOOLS_CALL} 只看形状、分不出来。不抠就是一类假警报，
     * 而假警报的代价是规划期自修正回路去改一个没病的地方 —— 每轮都是一次真实计费。
     *
     * <p>⚠️ 它不是词法分析：注释里的引号、正则字面量里的引号都可能让它「吃掉」后面一段代码。
     * 后果一律是<b>漏报</b>（该报的没报），不会变成误报，故按「宁可漏报也不误报」那条纪律接受。
     */
    private static final Pattern SCRIPT_STRING_LITERAL =
            Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`");

    /**
     * script 代码里对 {@code pickRows(…)} 的<b>调用</b>（V26 形状一用）
     *
     * <p>{@code pickRows} <b>不是</b>沙箱内置函数：{@code ScriptSandbox#eval} 只往脚本里塞了
     * {@code __stateJson} 一个绑定，随后把作者代码原样裹进 {@code (function(){ var state = …; 代码 })()} ——
     * 没有任何前置定义（{@code ScriptNodeExecutor#execute} 也是把 {@code code} 原样传下去的）。
     * 它是提示词<b>教模型抄</b>的一段函数体，抄了才有。
     */
    private static final Pattern SCRIPT_PICKROWS_CALL =
            Pattern.compile("(?<![\\w$.])pickRows\\s*\\(");

    /**
     * script 代码里<b>自己定义</b>了 pickRows（V26 形状一的免报条件）
     *
     * <p>两种写法都认：函数声明与赋值（{@code var pickRows = function…} / 箭头函数）。
     * 与 {@link #SCRIPT_TOOLS_SELF_DECLARED} 同一条纪律 —— 它只用来<b>抑制</b>报错，
     * 所以刻意写得比 {@link #SCRIPT_PICKROWS_CALL} 宽：误命中只造成漏报，不造成假警报。
     */
    private static final Pattern SCRIPT_PICKROWS_DEFINED = Pattern.compile(
            "(?<![\\w$.])(?:function\\s+pickRows\\s*\\(|(?:var|let|const)\\s+pickRows\\s*=)");

    /**
     * {@code pickRows(state.X)} —— 只有一个实参、<b>没点名</b>（V26 形状二用）。第 1 组是变量名。
     *
     * <p>只认 {@code state.X} 这一种实参形状：{@code pickRows(rows)} / {@code pickRows(state.X.a)}
     * 里的 {@code rows}、{@code state.X.a} 都不是「某个工具的结果对象」，本条无从知道它的形状。
     * 点名了的（{@code pickRows(state.X, 'models')}）自然不匹配 —— 那正是本条要的写法。
     */
    private static final Pattern SCRIPT_PICKROWS_UNKEYED = Pattern.compile(
            "(?<![\\w$.])pickRows\\s*\\(\\s*state\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\)");

    /**
     * 直接对 {@code state.X} 调数组方法（V26 形状三用）。第 1 组变量名、第 2 组方法名。
     *
     * <p>{@code pickRows(state.X).map(…)} <b>不</b>匹配：{@code state.X} 后面跟的是 {@code )} 而不是 {@code .}，
     * 而那正是合法写法。
     */
    private static final Pattern SCRIPT_STATE_ARRAY_METHOD = Pattern.compile(
            "(?<![\\w$.])state\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\.\\s*"
                    + "(find|some|map|filter|forEach|reduce|every)\\s*\\(");

    /**
     * {@code state.X.<猜测的包裹键名>}（V26 形状四用）。第 1 组变量名、第 2 组键名。
     *
     * <p>键名清单取自 {@link ToolResultShapes#LIST_WRAPPER_KEYS}（{@code data} 刻意不在里面，
     * 理由与那条判据依赖的实测事实都写在该常量的注释里）。
     */
    private static final Pattern SCRIPT_STATE_WRAPPER_KEY = Pattern.compile(
            "(?<![\\w$.])state\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\.\\s*("
                    + String.join("|", ToolResultShapes.LIST_WRAPPER_KEYS) + ")(?![\\w$])");

    /** DSL 里的保留端点（编译期映射为 SAA 常量，不是节点 id） */
    private static final String START = "START";
    private static final String END = "END";

    /**
     * 「节点动作即子图」的三种类型：它们没有对应的 {@code NodeExecutor} bean，
     * 由 {@code DslToStateGraphCompiler.buildStateGraph} 现场合成子图内嵌。
     * 故「type 是否已知」的判定要把这三种一并放行，否则会误报成「执行器不存在」。
     */
    private static final Set<String> GRAPH_BUILDER_TYPES = Set.of("sub_workflow", "agent_loop", "iteration");

    /**
     * 静态可判定的<b>不可补偿</b>节点类型（设计方案「一之三」第 3 条 / DSL §6.3）。
     *
     * <p>{@code llm} 与 {@code knowledge_retrieval} 的副作用是「模型 token 已耗、召回已发生」，<b>无法语义撤销</b>，
     * 必须 {@code recovery: forward}。tool 节点是否可补偿取决于它调的具体工具（CRUD 可撤、已提交 HTTP/DDL 不可撤），
     * 静态看不出来，故不在此列——那类由编排者自己负责声明 compensation。
     */
    private static final Set<String> NON_COMPENSABLE_TYPES = Set.of("llm", "knowledge_retrieval");

    /**
     * 建表单草稿的工具 id：它的 {@code args.fields[]} 是规划期冻死的，
     * 见 {@link #validateSaveDraftWidgets}（控件名）、{@link #validateSaveDraftOptionSources}（选项来源）
     * 与 {@link #validateSaveDraftColumns}（列名）
     */
    private static final String SAVE_DRAFT_TOOL_ID = "form_save_draft";

    /** 会真正建/登记物理表的工具 id：见 validateSaveDraftMainTable */
    private static final Set<String> TABLE_CREATOR_TOOL_IDS = Set.of("datamodel_create", "datamodel_import");

    /**
     * 会真正部署审批流的工具 id：见 validateBpmApprovalRefs
     *
     * <p>本流程自己会部署的流程，不能要求它「此刻已部署」—— 与
     * {@link #TABLE_CREATOR_TOOL_IDS} 在 {@link #validateSaveDraftMainTable} 里的用法同理。
     */
    private static final String PROCESS_DEPLOYER_TOOL_ID = "process_create_and_deploy";

    /** 模型条件节点 type；与 {@code LlmConditionNodeExecutor#TYPE} 同源 */
    private static final String LLM_CONDITION_TYPE = "condition.llm";

    /**
     * 会调模型的两种节点 type：两者的 {@code config.modelId} 都是规划期冻死的，见 validateLlmModelRefs
     *
     * <p>与 {@link #NON_COMPENSABLE_TYPES} 刻意分开：那份说的是「副作用撤不回」，
     * 这份说的是「这一格填的是模型编号」，两者只有 {@code llm} 重合。
     */
    private static final Set<String> LLM_NODE_TYPES = Set.of("llm", LLM_CONDITION_TYPE);

    /** BPM 审批节点 type；与 {@code HitlBpmApprovalNodeExecutor#TYPE} 同源 */
    private static final String BPM_APPROVAL_TYPE = "hitl.bpm_approval";

    /** 子流程节点 type；与 {@code DslToStateGraphCompiler} 的子图分派同源 */
    private static final String SUB_WORKFLOW_TYPE = "sub_workflow";

    /** {@code AiModelApi#getEnabledModelList} 的模型类型：1=聊天、5=向量 */
    private static final Integer AI_MODEL_TYPE_CHAT = 1;

    /** DSL {@code trigger.type} 的统一前缀；与 {@code WorkflowTriggerExtractor#TYPE_PREFIX} 同源 */
    private static final String TRIGGER_TYPE_PREFIX = "trigger.";

    /**
     * 会话触发器的短名：四种触发器里<b>唯一</b>「跑起来时有人在场」的那一种
     *
     * <p>见 {@link #isHeadlessTrigger}：本类判「无头」的方式是「不是 chat 就是无头」，
     * 所以这个常量同时承担了「白名单」与「fail-close 的基准点」两个角色。
     */
    private static final String TRIGGER_TYPE_CHAT = "chat";

    /** 聊天确认卡节点 type；与 {@code HitlConfirmNodeExecutor#TYPE} 同源。只有聊天窗口能唤醒它 */
    private static final String HITL_CONFIRM_TYPE = "hitl.confirm";

    /** 等待节点 type；与 {@code WaitNodeExecutor#TYPE} 同源 */
    private static final String WAIT_NODE_TYPE = "wait";

    /**
     * 规则条件节点 type；与 {@code RuleConditionNodeExecutor#TYPE} 同源。V24 只判这一种
     *
     * <p>它是<b>唯一</b>一种「用裸变量名读 state」的节点：别的节点想读状态都得写 {@code {{$.x}}}，
     * 于是走 {@code StateTemplateResolver}、也就落在 {@link #validatePlaceholders}（V7/V8）的射程里；
     * 而它的 {@code branches[].conditions[].var} 是<b>字面量</b>（契约里 mode 就是 MODE_LITERAL），
     * 执行期直接 {@code ctx.get(var)} 取顶层键 —— 两条既有规则一条都碰不到它。
     */
    private static final String RULE_CONDITION_TYPE = "condition.rule";

    /**
     * 会往 state 写「路由键」{@code __route_<节点id>} 的<b>全部</b>节点 type —— 条件边只认这两种出发点（V25）
     *
     * <p>🔴 权威源是「谁调了 {@code NodeExecutor#routeKey}」，不是本集合：2026-10-09 全模块 grep
     * {@code routeKey(} / {@code __route_}，写入方只有 {@code RuleConditionNodeExecutor} 与
     * {@code LlmConditionNodeExecutor} 两处（其余命中全是注释与测试夹具）。
     * 新增第三种会写路由键的节点类型时<b>必须</b>同步这里，否则 V25 会把合法写法判成 error。
     *
     * <p>⚠️ 与 {@link #LLM_NODE_TYPES} 刻意分开：那份问「这一格填的是不是模型编号」（V8 用），
     * 这份问「这个节点会不会写出条件边要读的那个键」（V25 用），两者只有 {@code condition.llm} 重合。
     */
    private static final Set<String> ROUTE_KEY_WRITER_TYPES = Set.of(RULE_CONDITION_TYPE, LLM_CONDITION_TYPE);

    /**
     * V14 读的<b>纯声明</b>字段：{@code {owner, endpoint, note}}，说清「谁会来发这个事件」
     *
     * <p>运行期<b>不读</b>它 —— 它唯一的作用是逼作者在发布前回答那个问题。
     * 唤醒本身只有一条路：外部系统调 {@code POST /agent/workflow/event} 带上 {@code eventKey}，
     * 而平台内<b>零调用方</b>（前端也没有），所以「没写谁调」= 「没人会调」= 永久卡死。
     *
     * <p>⚠️ 设计器还没有这一格的表单（{@code nodeConfigSchema.ts} 由 S6 补），
     * 故今天的填法是直接写 DSL JSON —— 错误文案里带了可原样粘贴的那一段。
     */
    private static final String EXTERNAL_WAKEUP_KEY = "externalWakeup";

    /** webhook 触发器的短名。V15 只判这一种 —— 另三种触发器没有对外的匿名入站面 */
    private static final String TRIGGER_TYPE_WEBHOOK = "webhook";

    /** {@code trigger.webhook.config} 里放鉴权方式的那一格；与 {@code WorkflowTriggerExtractor#applyWebhook} 读的同一个键 */
    private static final String WEBHOOK_AUTH_TYPE_KEY = "authType";

    /**
     * {@code authType} 的「不鉴权」取值 —— 平台<b>已不再支持</b>（{@code WorkflowTriggerExtractor} 落库前就拒）
     *
     * <p>V15 仍然认得它，因为存量 DSL 和「直改数据库绕过落库校验」这两种来路都可能带着它进来。
     * 提取器对这一格做了 {@code toLowerCase()}，故本类也必须不区分大小写 —— 两处漂了的表现是
     * {@code authType:"NONE"} 在校验器这里被当成「配了鉴权」放行、而落库/入站两侧又照不鉴权处理。
     */
    private static final String WEBHOOK_AUTH_TYPE_NONE = "none";

    /**
     * {@code trigger.chat.config} 里放「这条 chatflow 驱动哪个 Agent」的那一格；V28 只读这一个键
     *
     * <p>🔴 键名叫 {@code agentId}，装的却是 Agent 的 <b>code</b>（{@code agent_def.code}，版本稳定键）
     * 而不是数字 id —— 这件事的权威说明在 {@code WorkflowTriggerExtractor#applyChat} 上，本类不重述。
     * 两侧读的必须是同一个键名：漂了的表现是「作者在 DSL 里填了 agentId、落库的 trigger_key 却是 null」，
     * 于是聊天入口反查永不命中，而校验器这边一片沉默。
     */
    private static final String CHAT_AGENT_ID_KEY = "agentId";

    /** {@code iteration} 节点 type；它的 {@code config.body} 里的节点被编译器从父图排除，见 V18 */
    private static final String ITERATION_TYPE = "iteration";

    /** {@code parallel} 节点 type；它的 {@code config.branches} 里的节点同样不进父图的 edges，见 V18 */
    private static final String PARALLEL_TYPE = "parallel";

    /**
     * resume 时<b>不需要</b>在 {@code variables[]} 里声明也能活下来的键，V19 对它们一律不报
     *
     * <p>🔴 口径<b>同源</b>自 {@link NodeIoContract#defaultOutputNames()}，不在这里另列一份：
     * 这个集合的含义就是「{@code KeyStrategyFactoryGenerator#generate} 无条件注册过 KeyStrategy 的键」，
     * 而 SAA 的 {@code OverAllState.input(checkpointState)} 只保留注册过的键。
     * 生成器那一侧（缺陷 C8）已经从三个硬编码的 hitl 键改成遍历契约的全部缺省输出名，
     * 这里若还留着那三个，V19 就会对<b>其余四个</b>缺省名报「恢复时会被丢弃」—— 那是假警报，
     * 而且它给的改法（「把这一格删掉」）会把一条本来正常的流程改坏。
     *
     * <p>⚠️ 两侧不同源的话，<b>两个方向都是缺陷</b>：注册了却不豁免 ⟹ 假警报（上面那种）；
     * 豁免了却没注册 ⟹ 值真的在恢复瞬间被丢掉，而校验器一路绿灯。后者更坏，且正是 C8 之前的形态。
     *
     * <p>🔴 引常量/引契约而不是抄字面量的理由不变：执行器改了默认名，抄的那份豁免就会指向一个
     * 再也不会被写的键，V19 的判据（「这个键 resume 后还在不在」）静默变成假的。
     */
    private static final Set<String> RESUME_REGISTERED_VARS = NodeIoContract.defaultOutputNames();

    /**
     * 会把这次执行<b>挂起</b>、因而将来会有一次 {@code resume} 的三种节点，V19 的风险判据
     *
     * <p>🔴 只有这三种：{@code OverAllState.input(checkpointState)} 那层过滤<b>只在 resume 时发生</b>，
     * 所以「这个键会不会被丢」等价于「它写进去之后还会不会有一次挂起」。一条从头跑到尾不挂起的流程，
     * 未声明的键一个都不会丢 —— 对它报 error 是把不存在的危害说成存在的。
     *
     * <p>三种之外，运行期没有第四条挂起路径：{@code wait(duration)} 也算（它同样落 INTERRUPTED、
     * 由 {@code WorkflowWaitResumeScheduler} 捞回来），故 {@link #WAIT_NODE_TYPE} 整类都在内、
     * 不按 {@code waitType} 分档。
     */
    private static final Set<String> SUSPEND_NODE_TYPES = Set.of(
            WAIT_NODE_TYPE, HITL_CONFIRM_TYPE, BPM_APPROVAL_TYPE);

    @Resource
    private NodeExecutorRegistry nodeExecutorRegistry;

    @Resource
    private FormSchemaValidator formSchemaValidator;

    /**
     * 只用于 {@link #validateSaveDraftOptionSources}：把 form_save_draft 冻在 args 里的
     * <b>字段描述</b>摊成一条 rule，好让规划期与运行期过<b>同一个</b>选项来源判据。
     *
     * <p>🔴 注入它而不是在这里重写一遍「dictType → props.dictSource、options → rule 顶层」：
     * 那份落点规则已经在 {@code FormFieldFactory#applyOptionSource} 里量着真实表单写好了，
     * 抄第二份的话两边一漂，表现就是「体检说没问题、建出来的下拉还是空的」——
     * 恰好是这条规则要消灭的那个缺陷，只是换到了体检这一侧。
     */
    @Resource
    private FormFieldFactory fieldFactory;

    @Resource
    private DataModelApi dataModelApi;

    /**
     * 只用于 {@link #validateSaveDraftColumnsAgainstDeclaredColumns}（V30）：<b>只读</b>地看一份
     * {@code datamodel_create.args.model} 声明了哪张表、哪些列
     *
     * <p>🔴 借的是运行期<b>同一个</b>解析器（{@code DataModelParamResolver#resolveCreate}），
     * 而不是在这里再写一遍 {@code JSONUtil.parseObj(model)}：那一份同时认「model 是对象」与
     * 「model 是模型序列化出来的 JSON 串」两种形态（{@code WorkflowCopyRewriter} 的类注释记着
     * 只认 Map 会漏掉后一种），哪天再加第三种形态，这边会自动跟上。
     *
     * <p>⚠️ 拿「执行用的解析器」做只读探测是安全的，两条理由都核过：
     * 它<b>不碰库</b>（{@code resolveCreate} 全程只做解析与固定值补齐，
     * 查库的是同类的 {@code resolveUpdate}）；它改的是<b>刚解析出来的那份 DTO</b>
     * （置空 id、恒 0 数据源、补 TENANT、归一列类型），而本条只读
     * {@code tableName} 与 {@code columns[].columnName}，这两格它一个都不动。
     */
    @Resource
    private DataModelParamResolver dataModelParamResolver;

    @Resource
    private AgentToolRegistry toolRegistry;

    /**
     * 只用于「内置注册表查不到这个键时，它是不是一个自定义工具」这一问。
     * 走 {@link AgentToolDefService#resolveForGateway}（网关自己那条解析），理由见 {@link ToolResolver}。
     */
    @Resource
    private AgentToolDefService toolDefService;

    /** 只用于 V7：{@code bpmProcessDefKey} 指的流程存不存在、部署过没有 */
    @Resource
    private BpmModelApi bpmModelApi;

    /** 只用于 V8：{@code modelId} 指的模型此刻可不可用 */
    @Resource
    private AiModelApi aiModelApi;

    /**
     * 只用于 {@link #validatePublishParentMenu}：{@code form_publish_bundle.parentMenuName}
     * 那个目录名在不在启用目录里。
     *
     * <p>🔴 与运行期 {@code FormPublishParamResolver#resolveParentMenuId} 读的是<b>同一个</b>
     * {@code getEnabledDirectoryList()}，所以不会出现「规划期放行、运行期翻脸」。
     * 本类<b>不</b>持有任何目录名清单：手抄一份必然漂移，而漂了的表现正是这条要拦的那个
     * 「跑到最后一步才炸、而建表的 DDL 已经不可回滚」。
     */
    @Resource
    private MenuApi menuApi;

    /**
     * 只用于 V10：{@code sub_workflow.workflowId} 那个 code 取不取得到可执行的定义。
     *
     * <p>⚠️ 必须 {@code @Lazy}：{@code AgentWorkflowDefServiceImpl} 注入了本类，
     * 直接注入会在启动时形成循环依赖，而 Spring Boot 3 默认不允许循环引用
     * （同 {@code AgentFailureAlertService} 对两个跨模块 api 的处理）。
     */
    @Resource
    @Lazy
    private AgentWorkflowDefService defService;

    /**
     * 只用于 {@link #validatePlaceholders} 判 {@code sub_workflow.outputMap} 的<b>取值表达式</b>：
     * 那一格运行期求值的是子图写回后的共享 state，故名字表必须取<b>子 def</b> 的那一份
     * （{@code defService} 拿到 DO，这里把它的 {@code dsl} 解析成 {@code WorkflowDefDTO}）。
     *
     * <p>⚠️ 复用 {@link DslParser} 而不是自己 {@code readValue}：DSL → DTO 只许有一份口径，
     * 而它把「JSON 解析不了」转成带码的 {@code WORKFLOW_DSL_INVALID}，本方法照 V10 的样子降级处理。
     */
    @Resource
    private DslParser dslParser;

    /**
     * 只用于 V28：{@code trigger.chat.config.agentId} 那个 Agent 编码在不在 {@code agent_def} 里。
     *
     * <p>🔴 用 Mapper 而<b>不是</b> {@code AgentDefService}：判据本身（含「只问存在、不问状态」那条口径）
     * 是 {@link AgentDefMapper#existsByCode}，发布闸门读的也是它，两边同一份实现。
     * 而走 service 会经 {@code AgentDefServiceImpl → AgentToolRegistry → 全部工具 bean} 这条链
     * 有机会绕回注入本类的宿主，理由与 {@link #defService} 那条 {@code @Lazy} 同源。
     */
    @Resource
    private AgentDefMapper agentDefMapper;

    /**
     * 收集式校验一份 DSL 的基础结构
     *
     * <p>图遍历只在这里建<b>一次</b>（{@link GraphReachability}），下面几个用到可达性的校验共用它。
     * 此前它们各自内联建一遍邻接表，且对「条件边通向哪里」的理解不一致（D-new7）：
     * 正向那套无条件收条件边的 {@code to}，而编译器从不读它 —— 于是「建表节点能否走到 save_draft」
     * 会多算一条可达，让 {@link #validateSaveDraftMainTable} 少报一个 error。
     * 统一到一个类之后，这类漂移在结构上就不可能再发生。
     *
     * @param def 已解析的 DSL（{@code DslParser.parse} 的产物）；为 null 直接判失败
     * @return 收集了<b>全部</b>基础错误的结果；{@link AgentValidateResult#isPassed()} 为真表示基础结构没问题
     */
    public AgentValidateResult validate(WorkflowDefDTO def) {
        AgentValidateResult result = new AgentValidateResult();
        if (def == null) {
            result.addError("DSL 为空：请输出一个完整的 Workflow JSON 对象（含 nodes/edges）");
            return result;
        }

        GraphReachability.Graph graph = GraphReachability.of(def);
        Set<String> declaredVars = collectDeclaredVars(def.getVariables());
        Set<String> nodeIds = validateNodes(def.getNodes(), result);
        validatePlaceholders(def, declaredVars, result);
        validateReferenceReachability(def, graph, result);
        // 🔴 紧挨着可达性那条放：两条共用 collectProducers（「这个键有人写吗」只许有一份口径），
        //    且问的是同一件事的两端 —— 那条管 {{$.x}} 占位符的读取方、本条管 condition.rule 裸键名的读取方。
        //    排在这个位置也让「分支不可达」这个根因出现在工具解析那些下游毛病之前，
        //    模型自修正时才不会去改没病的字段（与 validateToolArgsShape 那条排位同理）。
        validateRuleConditionVarsWritten(def, result);
        // 🔴 紧挨着 V24 放：两条读的是【同一批】条件（共用 collectRuleConditions，形状只许解析一次），
        //    问的是同一个键的两端 —— 那条管「压根没人写」，本条管「有个必经的写入者，写了等于白问」。
        //    两档互斥（本条对没人写的键沉默），所以同一个条件只会被其中一条点名。
        validateRuleConditionTautology(def, graph, result);
        validateEdges(def.getEdges(), nodeIds, result);
        // 🔴 紧挨着上面那条放：两条都在看 edges，且刻意不重叠 —— 那条问「端点解析得到吗、cases/default 有没有」，
        //    本条问「from 是不是真会写路由键的那种节点」。同一条坏边只会被其中一条点名，不会各说一遍。
        validateConditionalEdgeSources(def.getEdges(), def.getNodes(), result);
        // 🔴 紧挨着 V25 放：两条问的是同一条条件边的两半 —— 那条问「from 会不会写路由键」，
        //    本条问「它写出来的那个值 cases 里有没有」。V25 命中时本条一律沉默（豁免 ②），
        //    所以同一条坏边只会被其中一条点名。挨着放也让作者在体检面板上看出它们是同一格配置的两侧。
        validateRouteKeysCoveredByCases(def, result);
        // 🔴 仍在看 edges，但问的是<b>另一件事</b>：上面几条都盯着条件边，本条盯着「普通出边挂了几条」。
        //    与 V25/V26 不重叠 —— 那两条要求边是 conditional 才继续看，本条恰好只看非 conditional 的。
        validateSinglePlainOutEdge(def.getEdges(), def.getNodes(), nodeIds, result);
        validateUnreachableNodes(def, graph, nodeIds, result);
        validateWrittenVarsDeclared(def, graph, declaredVars, result);
        // 🔴 紧挨着 V19 放：两条规则互补而不重叠（V19 管「下游另有挂起点」的出站丢失，
        //    V23 管「节点自己就是挂起点」的入站 feedback 被滤），挨着放让同一个键的两档报出来时相邻，
        //    作者在体检面板上才看得出它们是一回事的两个方向，而不是两条独立毛病。
        validateSuspendFeedbackVarsDeclared(def, graph, declaredVars, result);
        // 工具解析器一次校验只建一个：解析结果按 toolId 缓存，
        // 「相近候选」用的自定义工具全集也只在真报了「工具不存在」时才查一次库
        ToolResolver tools = new ToolResolver(toolRegistry, toolDefService);
        validateToolRefs(def, tools, result);
        // 🔴 排在 save_draft 那三条之前：它们的入口都是 configMap("args")，args 形状一坏就<b>静默跳过</b>，
        //    于是「控件名写错」这类真问题一条也报不出来。先说根因，模型才不会去改那些没病的字段。
        validateToolArgsShape(def, result);
        // 🔴 V35 紧跟在 V21 之后：两条读的是<b>同一格</b> tool 节点的 args，而 V21 判的正是
        //    「args 到底是不是个对象」—— args 形状一坏，V35 的 configMap("args") 拿到 null 就整趟静默跳过。
        //    先让 V21 说根因，V35 才不至于在一个已经报过错的节点上再叠一条（一个根因只说一遍）。
        //    同样必须排在 ToolResolver 与 V1/V2 之后：认不出的工具归那两条说，V35 一律沉默。
        validateToolArgSubkeys(def.getNodes(), result, tools);
        // V22 与上面那条没有先后依赖（一个看 tool 的 args、一个看 script 的 code），挨着放只是同属「形状判据」
        validateScriptToolCalls(def, result);
        // 🔴 紧挨着 V22 放：两条读的是同一格 config（script 的 code），同一个节点被两条点名时
        //    相邻报出来，作者才看得出它们出自同一段脚本。本条要按 toolId 读工具的【声明】返回形状，
        //    故必须排在 ToolResolver 建出来之后、且排在 V1/V2（validateToolRefs）之后 ——
        //    认不出的工具归那两条说，本条一律沉默（一个根因只说一遍）。
        validateScriptResultShape(def, graph, tools, result);
        boolean widgetProbeOk = validateSaveDraftWidgets(def, result);
        // 🔴 紧挨着控件白名单那条放：两条读的是<b>同一格</b> args.fields[]，
        //    问的也是同一类问题（「冻进 DSL 的一个名字，运行期还解析得到吗」）。
        //    排在它后面是有意的 —— 控件名本身写错时那条先开口，本条对认不出的控件一律沉默，
        //    一个根因只说一遍。契约探测中止时那条也已认领了范围（含本条），故本条整趟不再探。
        validateSaveDraftOptionSources(def, result, widgetProbeOk);
        validateSaveDraftMainTable(def, graph, result);
        // 🔴 紧挨着 save_draft 那两条放：三条问的是同一件事 —— 「冻进 args 的一个名字，
        //    运行期还解析得到吗」。挨着放让同一个流程里两处报出来时相邻，作者才看得出是一类毛病。
        validatePublishParentMenu(def, result);
        validateBpmApprovalRefs(def, graph, result);
        // 🔴 紧挨着 V7 放：两条问的是同一件事的两个方向 ——「本流程自己会建/会部署的资源，
        //    能不能要求它此刻已经就位」。V7 对 bpmProcessDefKey 的答案是<b>能等</b>（运行期自己部署完就能发起），
        //    本条对 formDesignCode 的答案是<b>等不来</b>（表单要「已发布 + 主表两列」，本流程给不出）。
        //    结论相反而不矛盾，挨着放才看得出它们是同一格配置的两半，而不是两条各说各话的规则。
        validateProcessDeployFormSource(def, result);
        validateLlmModelRefs(def, result);
        validateSubWorkflowRefs(def, result);
        validateTriggerRiskGates(def, graph, tools, result);
        validateHeadlessHitlConfirm(def, result);
        validateWaitEventWakeup(def, result);
        validateWebhookAuth(def, result);
        // 🔴 紧挨着 V15 放：两条读的都是 trigger 块里<b>冻进 DSL、运行期不再问模型</b>的一格配置。
        //    排在这里也让「触发器配错」这一类毛病在体检面板上相邻报出来，而不是散在两条节点规则中间。
        validateChatBindingAgentExists(def, result);
        validateChatLegsWriteReply(def, graph, nodeIds, result);
        return result;
    }

    // ==================== 干跑结论折叠（V4/V5/V12） ====================

    /**
     * 把变更清单里<b>已被平台拒绝</b>的干跑结论折成 error（V4 建表/改表 DDL、V5 表名与表单编码撞车、
     * V12 在线表单主表缺 {@code process_instance_id}）
     *
     * <h3>🔴 折叠，不是重写第二份探针</h3>
     * 这三条的判据全都要<b>查平台</b>（infra 的 DDL 预演、表清单、表单体检），而
     * {@code ChangeManifestDryRunner} 已经把每一条都探过一遍、且探的是<b>与真执行同一条链</b>
     * （{@code dataModelParamResolver.resolveCreate} / {@code FormPublishParamResolver} /
     * {@code ProcessCreateAndDeployTool.parseSimpleModel} —— 全是运行期那份代码）。
     * 在校验器里再写一遍就是 {@code FormSaveDraftParamValidator} 类注释点名的那个坑：
     * 两份必然漂移，漂移的表现是「体检说没问题、执行时报错」，而这正是本模块要消灭的缺陷类。
     *
     * <h3>为什么折叠之后才算修好了缺陷</h3>
     * 折叠前，一份「表名已被占用」的 DSL 在 {@code /validate} 上返回
     * {@code passed=true} + 一份带红标的清单：前端那个「校验通过」的绿章读的是 {@code passed}，
     * 于是界面上同时写着「通过」和「这么改跑不通」。用户点保存、点发布，一路绿灯到执行才炸 ——
     * 而 V4/V5 防的恰恰是 IRREVERSIBLE 的运行期失败（表建了一半）。
     * 折叠之后只有<b>一个</b>阻断信号，与 {@code ChangeItem#conflict} 注释里
     * 「只能有一个阻断信号」那条纪律接上。
     *
     * <h3>⚠️ 只折 {@code REJECTED}，{@code NOT_PROBED} 一律不折</h3>
     * 方案 §6.1 定的分级是「<b>探测得出『不合法』才是 error，探测失败不是</b>」，
     * 而 {@code NOT_PROBED} 今天混着两种完全不同的东西：
     * <ul>
     *   <li><b>良性的</b>：{@code GENERIC_TOOL}/{@code DATA_INTERFACE} 这两类平台压根无从预演、
     *       还没有干跑接口的 kind、以及值要到运行期才定的占位符（{@code {{$.x}}}）；</li>
     *   <li><b>真出事的</b>：infra 抛了非 {@code ServiceException}、干跑总预算用尽。</li>
     * </ul>
     * 一律降成 warning 的话，一份用了三个通用写工具的 DSL 就会顶出三条「本次没校验」——
     * 而 {@code ChangeManifestDryRunner#tablesCreatedHere} 的注释已经写下过这个失败模式的代价：
     * 「用户学会的就是『这个红标不用看』」。要分开就得给 {@code DryRunResult} 加一个「为什么没探成」的
     * 判别位，那是 <b>S4b 发布闸门</b>的活（它才是真正需要 §6.1 那条策略的消费者），本刀不做。
     *
     * <h3>🔴 本方法纯计算：不查库、不联网、不抛业务异常</h3>
     * 清单是调用方<b>已经建好</b>传进来的（{@code AgentWorkflowDefController} 建一次、
     * 既喂这里又塞进 RespVO）。本方法绝不自己去 build 一份 —— 那会让 {@code /validate}
     * 每次跑两遍干跑（单份预算就有 15 秒），而生成器那条自修正回路最多 6 轮。
     * 同理，{@code executedCount} 也必须是<b>参数</b>：本方法一旦自己去数执行史，
     * 三个消费者（发布闸门 / 盘点 / {@code /validate}）就各拿一份口径了。
     *
     * @param manifest 已建好的变更清单；为 null 表示调用方没要清单（纯静态校验），本方法什么也不做
     * @param result   往它的 {@code errors} 里追加；<b>就地修改</b>，不返回新对象
     */
    public void foldDryRunRejections(ChangeManifest manifest, AgentValidateResult result) {
        foldDryRunRejections(manifest, result, 0L);
    }

    /**
     * 同上，但允许调用方交出「这条流程名下已经真跑过几次」，据此给<b>已落地的撞车</b>降一档
     * （台账 #81 = S0 裁定① 的 A 方案）
     *
     * <h3>为什么只降「撞车」，V4/V12 的 REJECTED 一律不降</h3>
     * {@link ChangeItem#getConflict()} 非空是这里唯一可用的判别位，它挑出来的恰好是 V5 那一类
     * （{@code TABLE_ALREADY_EXISTS} / {@code TABLE_EXISTS_UNREGISTERED} / {@code FORM_CODE_TAKEN}）：
     * 东西<b>已经在平台上了</b>。被拉起过 N 次 + 东西已经在，最合理的解释是<b>自己早先的版本建的</b> ——
     * 此时拦发布拦的不是「会出事」，而是「已经出过事且已经收拾好了」。
     * V4（DDL 不合法）与 V12（主表缺 {@code process_instance_id}）的 {@code conflict} 是 null：
     * 跑过多少次都不改变「这份参数本身写错了」，降档就是放一条真缺陷过去。
     *
     * <h3>🔴 {@code executedCount} 是<b>尝试</b>次数，不是成功次数</h3>
     * 取数口 {@code AgentWorkflowExecutionMapper#countByDefIds} 的 {@code COUNT(*)} 不带 status 过滤，
     * FAILED / INTERRUPTED 的每一次尝试都算在内（实测过某行 5 次里 3 次 FAILED）。
     * 这是<b>刻意</b>的：一次失败的执行完全可能已经建完了表、只是在后面的节点上炸的，HITL 挂起同理，
     * 改成只数成功会让这批行归零、重新犯台账 #81 那个病（「有执行史却被判从没跑过而拦下」）。
     * ⟹ 代价是这条推理只是「极可能」而不是「必然」，所以<b>降档后的文案必须把口径写出来</b>
     * （「已被拉起过 N 次（含失败与中断的尝试）」），不能让人把它读成「成功跑通过 N 次」。
     *
     * <h3>🔴 降档的文案必须说清「不等于修好了」</h3>
     * 降成 warning 最坏的结果是用户把它读成「没问题了」。所以文案里三件事一件都不能少：
     * 判据是什么（被拉起过几次、且这个数是<b>含失败与中断的尝试</b>，不是「我觉得应该没事」）、
     * 放行不等于重跑能过（真执行到那一节点仍会被拒，治法是台账 #92 的运行期幂等）、
     * 以及那东西若其实是<b>别人的</b>该怎么办（改名，不是继续发）。
     *
     * <p>{@code executedCount} 传 0（或负数）就是<b>不降任何一档</b>：两参重载走的就是这条路，
     * 调用方拿不到执行史时宁可维持 fail-close，也不能默认「大概跑过吧」。
     *
     * @param executedCount 该流程 code 名下<b>全部版本</b>的<b>尝试</b>次数合计（含 FAILED / INTERRUPTED）；由调用方查库后传入
     */
    public void foldDryRunRejections(ChangeManifest manifest, AgentValidateResult result, long executedCount) {
        if (manifest == null) {
            return;
        }
        for (ChangeItem item : manifest.getChanges()) {
            DryRunResult dryRun = item.getDryRun();
            // 清单由 builder 产出时 dryRun 必不为 null（enrich 逐项都写）；
            // 但本方法是 public 的，调用方可能自己拼了一份没 enrich 的清单 ——
            // 那时宁可少一条 error，也不能让体检端点 NPE 成 500（用户既看不到错、也发不出去）
            if (dryRun == null || dryRun.getOutcome() != DryRunResult.Outcome.REJECTED) {
                continue;
            }
            String nodeName = item.getNodeName();
            String kindLabel = item.getKind() == null ? "变更" : item.getKind().getLabel();
            if (executedCount > 0 && item.getConflict() != null) {
                result.addWarning("节点(%s) 的「%s」要用的东西平台上已经有了（%s），而本流程名下已被拉起过 %d 次"
                                + "（含失败与中断的尝试）—— 极可能就是它自己早先的版本建的，故本次不拦发布。但请注意两件事："
                                + "① 放行不等于修好了：真执行到这一节点时仍会被拒，治法是让建表/建表单这一步认得出「已经是我建的」；"
                                + "② 若那东西其实属于<b>另一个</b>流程，请改个名字再发，别接着复用。探测原话：%s",
                        nodeName, kindLabel, item.getConflict().getMessage(), executedCount, dryRun.getReason());
                continue;
            }
            // reason 原样透传、不改写：DryRunResult 的契约是「REJECTED 的 reason 就是探测拿回来的原话」，
            // 而那些原话（表名撞车的出路、表单体检的逐条不合规）本身就是「怎么改」，
            // 在这里换个说法等于造第二份文案
            result.addError("节点(%s) 的「%s」这么改跑不通 —— 这是发布前对平台做的只读预演结论：%s",
                    nodeName, kindLabel, dryRun.getReason());
        }
    }

    // ==================== 不可信数据流进写工具（V16） ====================

    /**
     * 无头流程里「外部能写的数据 → 写工具入参」的流向（V16）
     *
     * <h3>与 V11 是正交的两件事，不是同一件事说两遍</h3>
     * V11 问「这个写操作<b>有没有人把关</b>」，本条问「这个写操作要写的值<b>是谁给的</b>」。
     * 一个配了鉴权、也配了 BPM 闸口的 webhook 流程，V11 完全沉默，
     * 而「表名来自请求体」这件事仍然值得让审批人停下来看一眼 —— 那正是 G25 说的
     * 「五个 LLM 输入面零防护」里最要紧的一个。反过来，一个没有闸口的只读查询流程
     * V11 也不报（READ_ONLY 直接跳过），本条同样不报。
     *
     * <h3>🔴 判据来自 {@code untrustedInputs[]}，不在这里另写一套溯源</h3>
     * 方案 §9「一次遍历两用」：{@link UntrustedInputTracer} 已经把源、汇、传播路径全算好了，
     * 且算的是<b>传递闭包</b>（webhook body → llm 提示词 → 模型产出 → 工具入参也算）。
     * 在校验器里再写一遍就是第二个副本，而它一定比原版的传播深度浅 ——
     * 于是「体检说没有危险流向、清单上却列着三条」，两份自相矛盾。
     * 过滤口径也照 {@code SinkKind#TOOL_ARG} 的 javadoc：「V16 只关心这一个（且那个工具是写工具）」。
     *
     * <h3>🔴 一律 warning，不分「AI 生成 / 人工编排」—— 方案那一刀切不出来</h3>
     * 方案要求 AI 生成路径按 error 硬拦、人工编排路径给 warning，判据是 {@code agent_workflow_def.source}。
     * 但那一列在 <b>DO</b> 上、由服务端按「哪个 createDef 入口跑的」写入，
     * {@code validate(WorkflowDefDTO)} 手里只有 DSL，压根看不见它。
     * 而且方案给的前提「AI 生成的 DSL 走的是零校验路径」与代码不符：
     * {@code WorkflowPlannerServiceImpl#planToWorkflow} 正是在校验器的 {@code isPassed()} 上循环自修正，
     * {@code toFeedback()} 也把 warning 一并回灌给模型 —— 那条路径不是零校验。
     * 更要紧的是分级本身站不住：error 会挡住「带鉴权的 webhook 收到什么就存什么」
     * 这个 webhook 集成<b>最常见</b>的正当用法，而作者除了把整个集成删掉没有别的改法
     * （与 V11 那条教训同向：规则必须先承认哪些写法是合法的，才可满足）。
     * 真正该硬拦的那一格 —— 无头 + <b>不可逆</b> + 没闸口 —— V11 已经独家报成 error 了。
     *
     * <h3>⚠️ 只在会话之外的触发器上判</h3>
     * 会话触发下「用户说的话流进工具入参」是这条产品的<b>主路径</b>，
     * 每一条聊天流程都会命中，报出来只会把真信号淹掉 —— 与
     * {@code UntrustedInputTracer} 刻意不把 {@code trigger.schedule} 当源是同一条纪律
     * （假警报的代价是真警报没人看）。
     *
     * @implSpec ✅ <b>原「射程限制」已解除（台账 C9）</b>。原文说的是：本方法只读调用方传进来的清单，
     *     所以「调用方没建清单」时一句也不说；而 AI 自修正回路 {@code WorkflowPlannerServiceImpl#validateTwoLayer}
     *     <b>刻意不建</b>清单（建一份要跑最多 15 秒的干跑，那条回路最多 6 轮）⟹ V16 在 AI 生成的草稿上
     *     一句话都不说，兜底只剩 S4b 的发布闸门。原文最后自己指出了出路：
     *     「要让它也覆盖 AI 路径，得给 {@link UntrustedInputTracer} 开一个不依赖清单构建器的入口」—— 那条入口
     *     现在有了（{@code UntrustedInputTracer#trace(WorkflowDefDTO)}），本方法在 {@code manifest == null} 时
     *     就地调它。
     *     <p>🔴 关键事实是「贵的是<b>干跑</b>，不是<b>溯源</b>」：溯源零查询、零副作用，唯一要现算的
     *     可达性邻接表是纯内存的 O(边数)。原先把两者绑在一起，等于让一个便宜的静态分析替一个昂贵的
     *     只读预演陪葬 —— 于是「AI 路径上没有污点警告」这个后果，与「AI 路径上不跑 15 秒干跑」这个
     *     <b>正确</b>的取舍毫无必要地捆成了一件事。
     *     <p>⚠️ 但 {@link #foldDryRunRejections} 的射程限制<b>照旧</b>，别把两件事一起当成已修：
     *     那一条的判据本身就是干跑结论（表名撞车、DDL 不合规），没有清单就真没有判据，
     *     它继续「清单为 null 时什么也不做」。
     *
     * @param def      本次校验的 DSL；用来把 {@code sinkNodeId} 翻回工具键、并读触发器；
     *                 🔴 也是 {@code manifest == null} 时溯源的唯一输入
     * @param manifest 已建好的变更清单；为 null 表示调用方没要清单，此时本方法<b>自己追一遍</b>
     *                 （产物与清单里那份逐条相同，见 {@code UntrustedInputTracer} 的两个入口）
     * @param result   往它的 {@code warnings} 里追加；<b>就地修改</b>，不返回新对象
     */
    public void foldUntrustedWrites(WorkflowDefDTO def, ChangeManifest manifest, AgentValidateResult result) {
        if (def == null) {
            return;
        }
        // 🔴 C9：清单为 null 不再等于「本方法一句也不说」—— 溯源自己是纯静态的，缺清单时就地追一遍。
        //    两个来源产物必然相同（同一份实现、同一份 def），理由写在 UntrustedInputTracer#trace(WorkflowDefDTO)
        List<ChangeManifest.UntrustedInput> untrusted = manifest == null
                ? UntrustedInputTracer.trace(def)
                : manifest.getUntrustedInputs();
        if (untrusted == null || untrusted.isEmpty()) {
            return;
        }
        String triggerType = triggerTypeOf(def);
        if (!isHeadlessTrigger(triggerType)) {
            return;
        }
        Map<String, NodeDTO> nodeById = new HashMap<>();
        if (def.getNodes() != null) {
            for (NodeDTO node : def.getNodes()) {
                if (node != null && StrUtil.isNotBlank(node.getId())) {
                    nodeById.putIfAbsent(node.getId(), node);
                }
            }
        }
        ToolResolver tools = new ToolResolver(toolRegistry, toolDefService);
        String toolArgSink = UntrustedInputTracer.SinkKind.TOOL_ARG.name();
        List<String> flows = new ArrayList<>();
        for (ChangeManifest.UntrustedInput flow : untrusted) {
            if (flow == null || !toolArgSink.equals(flow.getSinkKind())) {
                continue;
            }
            NodeDTO sink = nodeById.get(flow.getSinkNodeId());
            if (sink == null || !"tool".equals(sink.getType())) {
                continue;
            }
            String toolId = sink.configStr("toolId");
            if (StrUtil.isBlank(toolId) || tools.declaredRiskOf(toolId) == RiskLevel.READ_ONLY) {
                continue;
            }
            flows.add(StrUtil.blankToDefault(flow.getSummary(),
                    "节点(" + flow.getSinkNodeId() + ") 的入参 " + flow.getSinkArg()));
        }
        if (flows.isEmpty()) {
            return;
        }
        result.addWarning("本流程由%s触发、跑起来时没有人在场，而下面 %d 处「外部能写的数据 → 写工具入参」"
                        + "的流向没人过目：%s。不阻断，但这个组合正是「远程改数据」的形状："
                        + "调用方能决定写进去什么，而执行以发布者身份进行。"
                        + "建议在这些写节点的上游加一个 %s，让审批人在待办里看清将写入什么再放行"
                        + "（变更清单会把这些流向逐条列出来）；"
                        + "若这些入参其实不该由外部决定，请改成字面值或平台内的受控来源",
                triggerLabel(triggerType), flows.size(), abbreviate(flows, "；"), BPM_APPROVAL_TYPE);
    }

    // ==================== 工具引用（V1/V2/V3） ====================

    /**
     * DSL 里引用的工具必须「认得出、启用了」，agent_loop 里的还必须「声明为只读」
     *
     * <h3>为什么这三条要在校验期报，而不是等运行期</h3>
     * 工具键是规划期<b>冻死</b>在 DSL 里的，运行期照抄、不会再问模型。键打错（{@code datamodel_lst}）
     * 的唯一暴露时机是用户点完确认之后的那次执行失败 —— 自修正回路根本够不着它
     * （与 {@link #validateSaveDraftWidgets} 同一条理由）。
     *
     * <h3>🔴 与 {@code AgentLoopGraphBuilder} 的编译期校验是什么关系</h3>
     * 那边<b>也</b>拦「agent_loop 放了写工具」，且因为 {@code dryRunValidate} 复用 {@code buildStateGraph}，
     * validate 端点今天就已经收得到那条错。这里仍要有一份，理由两条：
     * <ul>
     *   <li>那边 fail-fast（{@code dryRunValidate} 把第一个异常收成一条 error），
     *       一份 DSL 里三个 agent_loop 各放了一个写工具，模型只能一轮改一个；</li>
     *   <li>那边原先读的是 {@code isRequireConfirm()} = <b>租户生效</b>风险级，一行
     *       {@code agent_tool_override} 就能改写它（D11）。本刀已把它一并改成读声明值 ——
     *       两处同地基，才不会「体检放行、编译拒绝」或反过来。</li>
     * </ul>
     *
     * <p>🔴 方案里「这条约束目前没有任何强制点」的说法与实测不符：强制点是有的，
     * 真正的缺陷是它站在<b>可被数据推动</b>的地基上。见 {@code ToolResolver#declaredRiskOf}。
     *
     * <p>🔴 这里曾经有一条「刻意<b>不</b>复制的编译期规则」：{@code agent_loop.tools[]} 只认内置注册表，
     * 自定义工具要等干跑那层报「未登记」，故本方法按合流目录判定会与编译期口径不一致 ——
     * 当时靠「两层合起来仍然拦得住」把这笔账挂在那里。<b>B2 已把这笔账结清</b>：
     * {@code AgentLoopGraphBuilder} 改走 {@code AgentToolCatalog}（内置注册表 ∪ 当前租户的工具管理目录），
     * 与本方法同一份合流结果，启用的只读自定义工具两边都放行、写工具两边都拒。
     * 那段说明留着只会让下一个读者以为还有一处口径分叉，故删。
     */
    private void validateToolRefs(WorkflowDefDTO def, ToolResolver tools, AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null) {
                continue;
            }
            String label = displayId(node.getId(), i);
            if ("tool".equals(node.getType())) {
                checkToolRef(node.configStr("toolId"), label, tools, result, false);
            } else if ("agent_loop".equals(node.getType())) {
                // configList 在 tools 不是数组时返回空列表；「tools 为空」由编译期那道闸拒
                // （AgentLoopGraphBuilder），干跑会收成 error，这里不重复报
                for (Object item : node.configList("tools")) {
                    checkToolRef(item == null ? null : String.valueOf(item).trim(),
                            label, tools, result, true);
                }
            }
        }
    }

    /**
     * 校验单个工具引用
     *
     * @param requireReadOnly 该引用是否来自 {@code agent_loop.tools[]}
     *                        （自主循环里的模型调用不逐次过 Plan 确认，故只许只读工具）
     */
    private void checkToolRef(String toolId, String nodeLabel, ToolResolver tools,
                              AgentValidateResult result, boolean requireReadOnly) {
        if (StrUtil.isBlank(toolId)) {
            result.addError("节点(%s) %s：请填一个工具键（如 datamodel_list）。"
                            + "工具键在运行期原样查表、不做变量替换，写 {{$.x}} 会被当成一个字面工具名",
                    nodeLabel, requireReadOnly ? "的 tools[] 里有一项是空的" : "缺少 toolId");
            return;
        }
        ResolvedTool tool = tools.resolve(toolId);
        if (!tool.resolved()) {
            // 自定义工具表读不到时，既不能判「存在」也不能判「不存在」：
            // 判不存在 = 把本来能跑的流程挡在发布外；判存在 = 放过一个运行期必被网关拒的键。
            // 两者都比「说清这项没校验成」更坏，故降为 warning，并写明跳过的到底是哪几项。
            result.addWarning("节点(%s) 的工具「%s」只比对了内置注册表（查自定义工具失败：%s）；"
                            + "若它是自定义工具，其存在性 / 启用状态 / 风险级都未校验",
                    nodeLabel, toolId, tool.failure());
            return;
        }
        if (!tool.exists()) {
            result.addError("节点(%s) 引用的工具「%s」%s。请改正工具键，或先到「工具管理」登记它",
                    nodeLabel, toolId, tools.unknownReason(toolId));
            return;
        }
        if (!tool.enabled()) {
            result.addError("节点(%s) 引用的工具「%s」（%s）已停用：运行期网关会直接拒绝调用，"
                            + "流程走到这一步必失败。请改用其他工具，或到「工具管理」把它启用",
                    nodeLabel, toolId, tool.displayName());
            return;
        }
        if (requireReadOnly) {
            RiskLevel declared = tools.declaredRiskOf(toolId);
            if (declared.isRequireConfirm()) {
                result.addError("节点(%s) 的 agent_loop 只允许只读工具，「%s」（%s）的声明风险是「%s」。"
                                + "自主循环里的模型调用不逐次过 Plan 确认，把它放进去等于让模型无人值守地改数据。"
                                + "写操作请让 agent_loop 出结论后接 tool 节点，或在它前面加 hitl 确认",
                        nodeLabel, toolId, tool.displayName(), declared.getDescription());
            }
        }
    }

    // ==================== tool 节点的 args 不是对象（V21） ====================

    /**
     * {@code tool} 节点的 {@code config.args} 写了、但<b>不是</b>「参数名 → 取值模板」这个对象 ⟹ error
     *
     * <h3>危害的形状：体检全绿、存成草稿、发布通过，走到那一节点必失败</h3>
     * 典型写法是把 args <b>整体</b>写成占位符，让前置 script 节点去拼参数表：
     * {@code "args": "{{$.publish_bundle_input}}"}。执行期 {@code ToolNodeExecutor#execute}
     * 读它用的也是 {@link NodeDTO#configMap}，认不出类型就返回 null，而那里是
     * {@code if (args != null)} —— 于是<b>整个参数装配被跳过</b>，工具以<b>空参数表</b>被调用。
     * 这不是「探不出来」，是「一定跑不通」。
     * run8 的示例 55 与 59 各中一次（节点都是 {@code do_publish}，见缺陷 D4-14）。
     *
     * <h3>🔴 为什么是 error 而不是 warning</h3>
     * 与 {@link #foldDryRunRejections} 折进来的那三条同一档：结论是<b>确定的</b>失败，不是「可能」。
     * §3.1 的发布闸门是 fail-close（error 一律拒、没有强推后门），warning 放过去就等于
     * 把「注定跑不通的高风险变更」交给用户自己看出来 —— 而清单那一格的红标只在<b>建了清单</b>时才有。
     *
     * <h3>🔴 干跑那边已经 REJECTED 了，为什么还要一条静态规则</h3>
     * {@code ChangeManifestDryRunner#probeSafely} 确实在 switch 之前就判了这个形状、并给出 REJECTED，
     * 而 {@link #foldDryRunRejections} 会把 REJECTED 折成 error。但那条路有三处够不着：
     * <ol>
     *   <li><b>清单是可选的。</b> {@code foldDryRunRejections(manifest, …)} 拿到 null 就什么都不做，
     *       而<b>生成器的自修正回路走的正是纯静态 {@code validate(def)}</b> —— 压根没有清单。
     *       本类的消费者就是那个回路（见类注释），够不着它就等于这条形状在规划期永远修不掉。</li>
     *   <li><b>折叠只覆盖产出了 {@code ChangeItem} 的节点。</b> 一个 args 写坏的<b>只读</b>工具节点
     *       （{@code datamodel_list} 这类）不产清单项，永远进不了 {@code probeSafely}。</li>
     *   <li>折叠那条纪律是「判据要<b>查平台</b>的别在这里重写第二份探针」（V4/V5/V12）。
     *       本条判据<b>不查库、不联网</b>，纯节点内形状判断，那条纪律对它没有适用余地。</li>
     * </ol>
     *
     * <h3>🔴 文案从 {@link ManifestArgs#argsShapeProblem} 借，绝不在这边另写一份</h3>
     * 同一种形状会同时出现在「体检的 error」与「清单那一格的红标」上。两处各写一份的后果是
     * 用户把它当成两件事、改两遍 —— 与本类「不造第二份文案」那条纪律（{@code foldDryRunRejections}
     * 里 reason 原样透传的同一条）冲突。这也是本方法是 V 系列里<b>唯一一条从 manifest 包借文案</b>的，
     * 借的方向刻意是「校验器 → 清单」而不是反过来：那句话既要给模型看（怎么改），也要给审批人看。
     *
     * <h3>⚠️ 与折叠<b>刻意重叠</b>，不去重</h3>
     * 写工具节点会同时吃到本条的 error 与折叠出来的 error（后者的 reason 就是同一句话），
     * 于是一份 DSL 里出现两条同因的阻断信号。不去重，与 V15「与提取器/入站验签刻意重叠」同理：
     * 去重要给 {@code DryRunResult} 加一个「这条 REJECTED 是谁判的」判别位，
     * 而清单那一格的红标<b>必须留着</b>（审批人点「同意」前看的是清单，不是 errors 数组）。
     * 代价是多一行 error，收益是两侧都不依赖对方的存在。
     *
     * <h3>⚠️ 三处沉默条件，写错任何一处代价都是一屏假警报</h3>
     * <ul>
     *   <li><b>args 缺失或为 null 不报</b>：契约里 {@code args} 的 required=false，无参工具就该不写。</li>
     *   <li><b>只管 {@code type == "tool"}</b>：{@code args} 这个端口在 {@link NodeIoContract} 里
     *       <b>只有 tool 声明</b>（{@code T_OBJECT}）。别的节点类型 config 里混进一个 args 键不报 ——
     *       执行器压根不读它，报了就是噪音。</li>
     *   <li><b>是对象就一个字都不说</b>：对象里的<b>值</b>写错（控件名、列名）归 V6 那几条管，
     *       本条只看最外层形状，一个根因只说一遍。</li>
     * </ul>
     *
     * <p>🔴 <b>向后兼容的代价，认下来</b>：存量已发布的 def 里若有这种形状，重新发布会被本条拦住。
     * 这是刻意的 —— 那种 def 走到该节点必然以空参数表调工具，「当初发得出去」不是它正确的证据。
     *
     * <p>编号纪律（本条是 <b>V21</b>）见 {@link #validateChatLegsWriteReply} 规则头那段：
     * V17 缺号、V20 与迭代二否掉的那条撞号，故新规则从 V21 起 —— 本条占了 V21。
     * ⚠️ 这里<b>刻意不写</b>「下一个可用号是几」：那句话的权威源只有
     * {@link #validateChatLegsWriteReply} 一处，抄第二份就会在下一条规则落地时变成假话，
     * 而注释里的数字<b>不会让任何测试变红</b>。
     */
    private void validateToolArgsShape(WorkflowDefDTO def, AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !"tool".equals(node.getType())) {
                continue;
            }
            String problem = ManifestArgs.argsShapeProblem(node);
            if (problem != null) {
                result.addError("节点(%s) %s", displayId(node.getId(), i), problem);
            }
        }
    }

    // ==================== script 节点里调用沙箱不存在的 tools.*（V22） ====================

    /**
     * {@code script} 节点的 {@code code} 里调了 {@code tools.xxx} ⟹ error（缺陷 D4-19）
     *
     * <h3>危害的形状：变更清单空、体检 0 warning、草稿卡照出，运行期必炸</h3>
     * 沙箱只往脚本里塞了一个东西 —— {@code ScriptSandbox#doEval} 就一句
     * {@code putMember("__stateJson", …)}，<b>没有任何工具句柄</b>。所以
     * {@code tools.form_save_draft({...})} 一定在运行期抛
     * {@code ReferenceError: tools is not defined}。
     *
     * <p>但它在<b>规划期</b>一路绿灯，四件事同时成立：
     * <ol>
     *   <li>体检通过、warnings 0 条（本条落地之前没有任何规则看 script 的 code）；</li>
     *   <li>变更清单 {@code kinds=[]}、{@code riskLevel=READ_ONLY} ——
     *       {@code WorkflowChangeManifestBuilder#build} 明确把 {@code script} 归进「不产变更项」那一类，
     *       于是<b>一次建表单的写操作对用户完全隐形</b>；</li>
     *   <li>草稿卡照出，标着「待确认」；</li>
     *   <li>用户点确认之后才在执行日志里看到 ReferenceError。</li>
     * </ol>
     * 第 2 条是这里面最严重的：清单是审批人点「同意」前唯一看的东西（见 {@link #validateToolArgsShape}
     * 里那条「清单那一格的红标必须留着」），它说「一条变更都没有」而实际要建表，
     * 等于把不可逆动作伪装成了只读流程。
     *
     * <p>实测出处：run13（2026-10-05 08:43）T2 的示例 57「客户拜访记录表」，def #463，
     * 5 个节点，manifest {@code riskLevel=READ_ONLY, kinds=[]}，
     * 那个 script 节点里写着 {@code var saveResult = tools.form_save_draft({…})}。
     * 🔴 注意它与 D4-16 <b>不是同一个形状</b>：D4-16 是「计划是空的」（0 个写步骤），
     * 本条是「计划不空、写步骤被藏进了 script」。同一条示例、两种翻车方式，
     * 根因都在提示词没把 script 沙箱的契约讲清楚（已在 {@code WorkflowDslPlannerPrompt} 的 script 那一条补上）。
     *
     * <h3>🔴 为什么是 error 而不是 warning</h3>
     * 与 V21 同一档：结论是<b>确定的</b>失败（ReferenceError 不是「可能」），
     * 且它还污染了清单的风险判级。§3.1 的发布闸门 fail-close、没有强推后门，
     * 放 warning 过去就等于让一张「看着只读、其实要建表」的卡走到用户面前。
     *
     * <h3>🔴 为什么用正则而不是解析 JS</h3>
     * 判据不查库、不联网、也不跑脚本，纯文本形状。要真解析得在校验器里挂一个 JS 引擎，
     * 而校验器是规划期<b>每轮自修正都要跑一遍</b>的热路径。代价是它只认 {@code tools.} 这个名字 ——
     * 模型换个叫法（{@code toolKit.save()}）就绕过去了。认下这个代价：
     * {@code tools.*} 是<b>实测</b>出现过的唯一形状，而提示词那条禁令覆盖的是全部形状。
     * 判据另外还<b>刻意</b>漏掉两种写法（属性访问上的 {@code state.tools.x()}、取出来存着不立即调的
     * {@code const f = tools.save}），逐项理由见 {@link #SCRIPT_TOOLS_CALL} 的注释。
     *
     * <h3>⚠️ 免报条件：作者自己声明过 tools 就不报</h3>
     * {@code var tools = state.widget_list} 这种合法写法里 {@code tools.foo} 是作者自己的对象，
     * 报了就是假警报（见 {@link #SCRIPT_TOOLS_SELF_DECLARED}）。
     * 声明、赋值、形参三种写法都认。宁可漏报也不误报 —— 误报会让自修正回路
     * 去改一个没病的地方，而每轮都是一次真实计费。
     *
     * <p>同一条纪律下的另外两处收窄：匹配前先把<b>字符串字面量</b>抠掉
     * （见 {@link #SCRIPT_STRING_LITERAL}，{@code return '请改用 tools.save(...)'} 不是调用），
     * 以及不认<b>属性访问</b>上的那个 {@code tools}（{@code state.tools.save()} 是作者自己的数据）。
     *
     * <p>编号纪律（本条是 <b>V22</b>）见 {@link #validateChatLegsWriteReply} 规则头那段：
     * V17 缺号、V20 与迭代二否掉的那条撞号、V21 已被「tool 节点的 args 不是对象」占掉，
     * 故本条取 V22。⚠️ 「下一个可用号是几」只在权威源那一处说，本条不抄（理由见
     * {@link #validateToolArgsShape} 规则头同一段）。
     */
    private void validateScriptToolCalls(WorkflowDefDTO def, AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !"script".equals(node.getType())) {
                continue;
            }
            String code = node.configStr("code");
            // code 缺失/为空归 validateNodes 那条管，本条不重复报（一个根因只说一遍）
            if (StrUtil.isBlank(code)) {
                continue;
            }
            // 抠掉字符串字面量再匹配，见 SCRIPT_STRING_LITERAL
            String probe = SCRIPT_STRING_LITERAL.matcher(code).replaceAll("\"\"");
            Matcher call = SCRIPT_TOOLS_CALL.matcher(probe);
            if (!call.find()) {
                continue;
            }
            if (SCRIPT_TOOLS_SELF_DECLARED.matcher(probe).find()) {
                continue;
            }
            result.addError("节点(%s) 的脚本调用了 %s —— script 跑在沙箱里，只注入了一个只读的 state，"
                            + "没有任何工具句柄，运行期必抛 ReferenceError；更要紧的是变更清单只从 tool 节点提取，"
                            + "这次写操作对用户是隐形的（清单会显示成「一条变更都没有」的只读流程）。"
                            + "请把它改成一个 tool 节点（toolId + args）；需要把算好的值喂给工具，"
                            + "就让 script 先 return 出来，再由下游 tool 节点用 {{$.那个变量}} 取。",
                    displayId(node.getId(), i), call.group(1).replaceAll("\\s+", ""));
        }
    }

    // ==================== script 从工具结果里取列表的方式（V26） ====================

    /**
     * {@code state.X} 的<b>可知</b>返回形状（V26 用）
     *
     * @param toolId    产出该变量的工具键，只用于拼错误文案
     * @param arrayKeys 该工具声明的数组值键（<b>全部返回分支的并集</b>，见
     *                  {@link AgentToolFunction#resultArrayKeys()}）。🔴 空列表是一个<b>有内容的结论</b>
     *                  （「这工具确实不回数组」），与「形状不可知」（{@link #shapeOfVar} 返回 null）
     *                  是两件事 —— 混成一样就会对一个真回数组的工具沉默，或对形状未知的工具瞎判。
     */
    private record VarShape(String toolId, List<String> arrayKeys) {
    }

    /**
     * {@code state.X} 的形状；<b>不可知</b>时返回 null（本条一律不判）
     *
     * <p>五道免判，每道都是「本条给不出确定结论」而不是「懒得判」：
     * <ol>
     *   <li><b>产出者不唯一</b>：两条分支可能写不同工具的结果，取谁的形状来判都是赌 ——
     *       而本条报的是 error，赌错就把一份好 DSL 判死；</li>
     *   <li><b>产出者不是 tool 节点</b>：script / llm / 审批节点产出什么由作者的代码与模型决定，
     *       静态无从知道；</li>
     *   <li><b>产出者不在本节点的上游</b>：那 {@code state.X} 运行期是 null，真正的根因是<b>排序</b>，
     *       报「该点名 models」会把自修正回路引向一处没病的代码。⚠️ 这一档<b>没有</b>别的规则会说：
     *       {@link #collectAllRoots} 只认 {@code {{$.x}}} 占位符，而 {@code state.X} 是脚本里的裸属性访问；</li>
     *   <li><b>toolId 缺失 / 认不出 / 解析故障</b>：归 V1/V2，一个根因只说一遍；
     *       且 {@code resolved=false} 时 {@link ResolvedTool} 的其余字段一律不可信；</li>
     *   <li><b>{@code shapeKnown=false}</b>：自定义工具（{@code source=DATA_INTERFACE}）与
     *       {@code data_interface_query} / {@code data_interface_invoke} 的结果由那个接口自己决定，
     *       平台给不出键名 —— 提示词教的是「先用 script 把键名打出来看一眼」，本条不能反过来判它必抛。</li>
     * </ol>
     */
    private VarShape shapeOfVar(String var, Set<String> ancestors, Map<String, Set<String>> producers,
                                Map<String, NodeDTO> nodesById, ToolResolver tools) {
        Set<String> ids = producers.get(var);
        if (ids == null || ids.size() != 1) {
            return null;
        }
        String producerId = ids.iterator().next();
        if (!ancestors.contains(producerId)) {
            return null;
        }
        NodeDTO producer = nodesById.get(producerId);
        if (producer == null || !"tool".equals(producer.getType())) {
            return null;
        }
        String toolId = producer.configStr("toolId");
        if (StrUtil.isBlank(toolId)) {
            return null;
        }
        ResolvedTool tool = tools.resolve(toolId);
        if (!tool.resolved() || !tool.exists() || !tool.shapeKnown()) {
            return null;
        }
        return new VarShape(toolId, tool.resultArrayKeys());
    }

    /**
     * script 节点从工具结果里取列表的四种错法（V26，🔴 <b>全部 error</b>）
     *
     * <h3>危害的形状：规划期一路绿灯，运行期要么炸在别处、要么<b>压根不炸</b></h3>
     * 沙箱只往脚本里注入了一个只读的 {@code state}（{@code ScriptSandbox#eval} 就一句
     * {@code putMember("__stateJson", …)}），而工具结果是<b>对象</b>不是数组、
     * 且全平台<b>没有统一的列表包裹键</b>（{@code datamodel_list} 回 {@code models}、
     * {@code form_list} 回 {@code items}、{@code data_interface_list} 回 {@code list}）。
     * 规划期没有任何接口能查到返回契约，模型只能赌。赌错的四种形状：
     * <table border="1">
     *   <caption>V26 的四个形状</caption>
     *   <tr><th>#</th><th>形状</th><th>运行期后果</th></tr>
     *   <tr><td>一</td><td>调了 {@code pickRows(…)} 却没在本脚本里定义它</td>
     *       <td>{@code ReferenceError}（它不是内置函数，是提示词教模型<b>抄</b>的一段函数体）</td></tr>
     *   <tr><td>二</td><td>{@code pickRows(state.X)} 不点名，而 X 的工具回多个数组 / 不回数组</td>
     *       <td>{@code pickRows} throw「猜不出该取哪个数组」</td></tr>
     *   <tr><td>三</td><td>直接 {@code state.X.map(…)}，而 X 的工具回的是对象</td>
     *       <td>{@code TypeError}</td></tr>
     *   <tr><td>四</td><td>{@code state.X.items} 这类<b>猜包裹键名</b>，而声明里没有它</td>
     *       <td>🔴 <b>不报错</b>，恒为 {@code undefined}</td></tr>
     * </table>
     *
     * <h3>🔴 形状四是这条规则存在的<b>主要</b>理由：它是四种里唯一<b>静默</b>的</h3>
     * 前三种都在本节当场抛，错误消息里还带着实有的键名，排查的人一眼能对上；
     * 形状四不抛 —— 它安静地拿到 {@code undefined}，本节照样记 SUCCESS，
     * 真正的失败要等<b>下一节</b>才以一句和根因对不上的参数错冒出来。
     * 实测疤（提示词 NODE_CATALOG 里也记着同一条）：一条加字段流程写
     * {@code state.model_list_out.items || state.model_list_out.list}，
     * 而 {@code datamodel_list} 回的键叫 {@code models} ⟹ 取到 {@code []} ⟹ return null ⟹ 本节成功，
     * 下一节才报「工具(datamodel_structure)的参数不合法：id 必须是数字，收到的是：」。
     * 与 V19/V24 同一档危害分级：<b>静默</b>才是必须阻断的那一档。
     *
     * <h3>落地前的爆炸半径实测（2026-10-09，29 份活定义、38 个 script 节点）</h3>
     * <ul>
     *   <li>形状四：<b>7 处命中 / 5 份定义</b>（草稿 616、617，已发布 618、619、620），
     *       逐个人工核对<b>全是真缺陷</b>，且那 5 份正是已知跑不通的那几份 ——
     *       零假警报，这是本条敢定 error 的依据；</li>
     *   <li>形状一：<b>0 处</b>，但<b>不是空测</b> —— 38 个节点里有 11 个调了 {@code pickRows}，
     *       11 个都在同脚本里定义了它（提示词那句「先抄这个函数」目前是被遵守的）。
     *       故本形状是<b>回归闸</b>：挡的是「模型以为它是内置函数」那一天；</li>
     *   <li>形状二：<b>0 处</b>；同期有 15 次<b>点名</b>调用，说明模型确实在按表点名；</li>
     *   <li>形状三：<b>0 处</b>（38 个节点里没有一个直接对 {@code state.X} 调数组方法）。</li>
     * </ul>
     *
     * <h3>🔴 为什么是 error 而不是 warning：warning <b>不会</b>回灌给模型</h3>
     * {@code WorkflowPlannerServiceImpl#planToWorkflow} 只在 {@code !result.isPassed()}
     * （即至少一条 <b>error</b>）时才调 {@code result.toFeedback()}。放 warning 过去，
     * 规划期什么都不会变，用户点完确认之后照样撞运行期 —— 与「V21/V22 定 error」同一条理由：
     * <b>结论是确定的失败</b>，而 §3.1 的发布闸门 fail-close、没有强推后门。
     *
     * <h3>🔴 判据的<b>事实</b>来自工具的代码声明，本类不持有任何工具名单</h3>
     * 谁回哪些数组由 {@link AgentToolFunction#resultArrayKeys()} 声明、经
     * {@link AgentToolRegistry.ToolDefinition#getDeclaredResultArrayKeys()} 读进来，
     * 「不点名行不行」由 {@link ToolResultShapes} 判 —— 与提示词那张「返回值形状」表
     * 是<b>同一份声明的两个渲染</b>。两处各写一份的后果（提示词说能不点名、校验器说必须点名，
     * 同一段脚本规划期被判合法、运行期才抛）见 {@link ToolResultShapes} 类注释。
     * ⚠️ 读的一律是 <b>declared</b> 值：{@code agent_tool_override} 一行数据不许能换掉校验依据，
     * 与 {@link ToolResolver#declaredRiskOf} 那条纪律同源。
     *
     * <h3>⚠️ 形状四的判据依赖一条<b>实测且无守卫</b>的事实</h3>
     * 声明只覆盖<b>数组值</b>的键，所以「不在声明里」原则上推不出「不是键」。
     * 它之所以成立，靠的是 {@code ToolResultShapes#LIST_WRAPPER_KEYS} 注释里那次全量核对
     * （那五个名字在全平台只被当数组键用过）。将来哪个工具回一个标量 {@code list}，本形状就会误报 ——
     * 那条纪律写在该常量的注释里，改工具的人要在那儿看见它。
     *
     * <h3>刻意不判的两档（别当成漏了）</h3>
     * <ol>
     *   <li><b>{@code pickRows(rows)} 这种实参不是 {@code state.X} 的</b>：{@code rows} 是脚本里算出来的中间量，
     *       它的形状只有作者知道；</li>
     *   <li><b>{@code state.X.a.b} 这种深一层的</b>：要判它就得知道 {@code X.a} 是什么，
     *       那已经超出「工具返回形状」这份事实。</li>
     * </ol>
     *
     * <h3>编号纪律（本条是 <b>V26</b>）</h3>
     * 见 {@link #validateChatLegsWriteReply} 规则头那段权威源。⚠️ 「下一个可用号是几」只在那里说，本条不抄。
     */
    private void validateScriptResultShape(WorkflowDefDTO def, GraphReachability.Graph graph,
                                           ToolResolver tools, AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        // 「这个键有人写吗」只许有一份口径，故复用 collectProducers（V18/V24 用的就是它）
        Map<String, Set<String>> producers = collectProducers(nodes);
        if (producers.isEmpty()) {
            return;
        }
        Map<String, NodeDTO> nodesById = new HashMap<>();
        for (NodeDTO n : nodes) {
            if (n != null && StrUtil.isNotBlank(n.getId())) {
                nodesById.putIfAbsent(n.getId(), n);
            }
        }

        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !"script".equals(node.getType())) {
                continue;
            }
            String code = node.configStr("code");
            // code 缺失/为空归 validateNodes 那条管，本条不重复报（一个根因只说一遍）
            if (StrUtil.isBlank(code)) {
                continue;
            }
            // 抠掉字符串字面量再匹配，见 SCRIPT_STRING_LITERAL：脚本里 return 一句
            // 「请改用 pickRows(...)」的提示文案不是调用
            String probe = SCRIPT_STRING_LITERAL.matcher(code).replaceAll("\"\"");
            String who = displayId(node.getId(), i);

            // ---- 形状一：调了 pickRows 却没抄它（确定的 ReferenceError）----
            if (SCRIPT_PICKROWS_CALL.matcher(probe).find()
                    && !SCRIPT_PICKROWS_DEFINED.matcher(probe).find()) {
                result.addError("节点(%s) 的脚本调用了 pickRows(…) 却没有在【本脚本里】定义它 —— "
                                + "script 跑在沙箱里，只注入了一个只读的 state，没有任何预置函数，"
                                + "pickRows 也不是内置的，运行期必抛 ReferenceError。"
                                + "请把那段 pickRows 函数体整段抄进本节点的 code 再调用；"
                                + "沙箱不跨节点共享定义，所以每个用到它的 script 节点都要各自抄一份。",
                        who);
            }

            Set<String> ancestors = graph.ancestorsOf(node.getId());

            // ---- 形状二：不点名取值（确定的 throw）----
            Matcher unkeyed = SCRIPT_PICKROWS_UNKEYED.matcher(probe);
            while (unkeyed.find()) {
                String var = unkeyed.group(1);
                VarShape shape = shapeOfVar(var, ancestors, producers, nodesById, tools);
                if (shape == null) {
                    continue;
                }
                if (!ToolResultShapes.declaresArrays(shape.arrayKeys())) {
                    result.addError("节点(%s) 对 state.%s 用了 pickRows，但工具(%s) 的结果对象里"
                                    + "没有任何数组值的键，pickRows 一定 throw（候选键是空的）。"
                                    + "要读它的字段直接写 state.%s.字段名，别绕 pickRows。",
                            who, var, shape.toolId(), var);
                    continue;
                }
                if (!ToolResultShapes.mustName(shape.arrayKeys())) {
                    continue;
                }
                result.addError("节点(%s) 对 state.%s 不点名取值，但工具(%s) 的结果里有多个数组"
                                + "且没有恰好一个叫 items/list/models，pickRows 运行期必抛"
                                + "「猜不出该取哪个数组」。请补第二个参数点名。%s",
                        who, var, shape.toolId(), shapeFixHint(var, shape.arrayKeys()));
            }

            // ---- 形状三：把结果对象当数组遍历（确定的 TypeError）----
            Matcher arrayMethod = SCRIPT_STATE_ARRAY_METHOD.matcher(probe);
            while (arrayMethod.find()) {
                String var = arrayMethod.group(1);
                String method = arrayMethod.group(2);
                VarShape shape = shapeOfVar(var, ancestors, producers, nodesById, tools);
                // 🔴 只在「确实回对象」时判：声明为空有两种可能（对象里没有数组 / 结果本身就是个数组），
                //    后者 .map 是合法的，判了就是假警报
                if (shape == null || !ToolResultShapes.declaresArrays(shape.arrayKeys())) {
                    continue;
                }
                result.addError("节点(%s) 的脚本把 state.%s 当数组用了（.%s(…)），但它是工具(%s) 的"
                                + "返回【对象】—— 对象上没有 %s 方法，运行期必抛 TypeError。"
                                + "请先用 pickRows 把数组取出来再遍历。%s",
                        who, var, method, shape.toolId(), method,
                        shapeFixHint(var, shape.arrayKeys()));
            }

            // ---- 形状四：猜包裹键名（🔴 静默拿到 undefined，本条的主要目标）----
            // 同一个变量上猜错的多个键名合并成一条：它们是【一个】根因（不知道正确的键叫什么），
            // 实测形状就是 state.model_list_out.items || state.model_list_out.list 这种连着写两三个
            Map<String, Set<String>> guessed = new LinkedHashMap<>();
            Matcher wrapper = SCRIPT_STATE_WRAPPER_KEY.matcher(probe);
            while (wrapper.find()) {
                String var = wrapper.group(1);
                String key = wrapper.group(2);
                VarShape shape = shapeOfVar(var, ancestors, producers, nodesById, tools);
                if (shape == null || !ToolResultShapes.declaresArrays(shape.arrayKeys())
                        || shape.arrayKeys().contains(key)) {
                    continue;
                }
                guessed.computeIfAbsent(var, k -> new LinkedHashSet<>()).add(key);
            }
            for (Map.Entry<String, Set<String>> entry : guessed.entrySet()) {
                String var = entry.getKey();
                VarShape shape = shapeOfVar(var, ancestors, producers, nodesById, tools);
                result.addError("节点(%s) 的脚本读了 state.%s.%s，但工具(%s) 的结果对象上没有这个键 —— "
                                + "这个表达式恒为 undefined：不报错、只是安静地拿到空值，本节照样记成功，"
                                + "真正的失败要等下游才以一句和根因对不上的参数错冒出来。%s",
                        who, var, abbreviate(new ArrayList<>(entry.getValue())), shape.toolId(),
                        shapeFixHint(var, shape.arrayKeys()));
            }
        }
    }

    /**
     * V26 那三个形状共用的「该改成什么」半句：<b>只有一个确定答案时</b>才替模型写出来
     *
     * <p>🔴 判据是 {@link ToolResultShapes#autoResolvedKey}（与提示词渲染「不点名也对」那一栏印出的
     * 是同一个值），<b>不是</b>「取声明里的第一个」：{@code datamodel_structure} 的头一个数组是
     * <b>系统列</b>，建议它等于教模型拿 {@code creator} / {@code create_time} 去判业务列 ——
     * 静默判错还不报错，正是 {@link ToolResultShapes#MAIN_LIST_KEYS} 注释里点名不许放宽的那个反例。
     *
     * <p>抽成一处是因为三个形状要的是同一句话：各写一份就会在「给不给建议」上漂，
     * 而漂的方向恰好是最坏的那个（形状三给了建议、形状四没给 ⟹ 模型学到「有时不用点名」）。
     * <b>出路必须是通的</b>：文案只说「不行」的话，自修正回路最省事的改法是把这一节删掉，
     * 那等于把用户要的加工整个抹了（D4-16 那个方向）。
     */
    private static String shapeFixHint(String var, List<String> arrayKeys) {
        String auto = ToolResultShapes.autoResolvedKey(arrayKeys);
        return auto == null
                ? "它声明的数组键是 " + abbreviate(arrayKeys) + "，该点哪个要看你的业务意图，别默认取第一个。"
                : "请改用 pickRows(state." + var + ", '" + auto + "') 或 state." + var + "." + auto + "。";
    }

    /**
     * form_save_draft 节点冻在 DSL 里的控件类型，规划期就按运行期同一份白名单拦一遍
     *
     * <p>🔴 为什么结构校验里要混进这条「语义」校验：tool 节点的 args 是规划期<b>写死</b>的，
     * 运行期执行器照抄、<b>不会再问模型</b>。于是控件名写错（实测 {@code image}、{@code upload}）
     * 的唯一暴露时机就是用户点完确认之后的那次执行失败 —— 自修正回路根本够不着它。
     * 而本类的消费者正是规划期的 LLM 自修正（见类注释），错误回灌一轮就能改对。
     *
     * <p>判定复用 {@link FormSchemaValidator#checkWidgetType}：大小写归一、语义别名、
     * 停用白名单与运行期 {@code form_save_draft} 完全同口径，不会出现「规划期放行、运行期翻脸」。
     *
     * <p>🔴 那道白名单<b>比它的名字宽</b>：除了「存不存在 / 停没停用」，它还判
     * 「在白名单里、也确实可用，而<b>字段描述建不出来</b>」（实测 63 个可用控件里有 18 个是这种，
     * 如 {@code DictSelect} 要 {@code dictType}、{@code cascader} 的选项要写进 {@code props.options}）。
     * 判据与后果都在 {@code FormFieldFactory#missingWidgetConfig}，这里只是复用同一个入口 ——
     * 于是这道闸<b>一次额外探测都不花</b>（每个字段本来就要为白名单读一遍契约）。
     * 挂在这里而不是另起一条规则，也意味着它<b>不取新的 V 号</b>：报错点、section、消费者一个都没变，
     * 变的只是「控件白名单」这一条判些什么。号域的权威段在 {@link #validateChatLegsWriteReply}。
     *
     * @return 控件契约<b>读得到</b>返回 {@code true}；探测中止（已按真实范围报过 warn）返回
     *         {@code false}，此时 {@link #validateSaveDraftOptionSources} 整条跳过 ——
     *         它判「是不是选项类控件」也要读同一份契约，再探一遍只会多刷一条同因的黄标
     */
    private boolean validateSaveDraftWidgets(WorkflowDefDTO def, AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null) {
            return true;
        }
        // 探测中止时那句 warning 要能说出「已经查过几处」，否则它只能含糊地说「跳过了」
        int checked = 0;
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !"tool".equals(node.getType())
                    || !SAVE_DRAFT_TOOL_ID.equals(node.configStr("toolId"))) {
                continue;
            }
            Map<String, Object> args = node.configMap("args");
            Object fields = args == null ? null : args.get("fields");
            if (!(fields instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> field)) {
                    continue;
                }
                Object widget = field.get("widget");
                if (!(widget instanceof String type) || StrUtil.isBlank(type)) {
                    continue;
                }
                String fieldName = String.valueOf(field.get("field"));
                try {
                    String problem = formSchemaValidator.checkWidgetType(type);
                    checked++;
                    if (problem != null) {
                        result.addError("节点(%s) 的 form_save_draft 参数里字段 %s 的%s。"
                                        + "这份参数是冻进 DSL 的，运行期不会再问模型，必须在规划期改对",
                                displayId(node.getId(), i), fieldName, problem);
                    }
                } catch (Exception ex) {
                    // 控件契约读不到（infra 故障）时不拦规划：宁可运行期再报，
                    // 也不能让「校验的依赖」故障把整个规划打灭。
                    // 🔴 但文案必须认领<b>真实</b>的跳过范围：这里 return 掉的是<b>整趟</b>
                    //    —— 本节点剩下的字段，加上后面所有 form_save_draft 节点的字段。
                    //    原文案只点了当前这一个字段，读的人会以为其余字段都校验过了，
                    //    而「静默少校验一块」正是本类要消灭的那类缺陷（S2 全刀反复钉的那条）。
                    // 🔴 范围里还要带上「选项来源」：同一次故障也让 V29 整条停手（它读的是同一份契约），
                    //    漏写它 ⟹ 黄标只认领了一半的跳过范围，同样是「静默少校验一块」。
                    warnProbeAborted(result, "控件白名单校验", "读控件契约", ex,
                            String.format("已校验 %d 处，本次体检中其余 form_save_draft 字段的控件类型"
                                            + "与选项来源全部未校验（停在节点(%s) 的字段 %s，控件类型 %s）",
                                    checked, displayId(node.getId(), i), fieldName, type));
                    return false;
                }
            }
        }
        return true;
    }

    // ==================== tool.args 里占位符的子键不存在（V35） ====================

    /**
     * 抓 {@code {{$.root.sub…}}} 里的<b>根变量名</b>与<b>紧跟其后的第一个子键</b>（V35 用）。
     *
     * <p>🔴 与 {@link #PLACEHOLDER_ROOT} 的区别就是本条规则存在的全部理由：那个正则在第一个
     * {@code .} 处<b>停手</b>，只拿到根，{@code .model.id} 整段被丢掉 —— 于是根解析得到就放行，
     * 而运行期真正去走的是完整路径。子键这一段此前<b>谁都没看过</b>。
     *
     * <p>第 2 组刻意排除 {@code . [ ] } } 与空白：{@code StatePathNavigator} 支持 {@code arr[0]}、
     * {@code arr[-1]} 这类下标段，本条只判<b>第一段的键名</b>存不存在，下标对不对不归它管
     * （越界在运行期返回 null，与键名不存在是两件事，混在一起报会把人引向错的修法）。
     * 键名允许中文（{@code datamodel_structure} 真有个键叫 {@code 改表须知}）。
     */
    private static final Pattern PLACEHOLDER_SUBKEY = Pattern.compile(
            "\\{\\{\\s*\\$\\.([a-zA-Z][a-zA-Z0-9_]*)\\.([^.\\[\\]}\\s]+)");

    /**
     * {@code tool} 节点 {@code args} 里 {@code {{$.变量.子键}}} 的<b>子键</b>必须真在那个工具的返回里（V35）
     *
     * <h3>为什么是 error：写错的子键不报错，只安静地变成空串</h3>
     * 实测（execution 217 / def 900217，2026-10-18）：{@code datamodel_create} 的
     * {@code output_snapshot} 是 {@code {"create_out":{"id":358,"tableName":"biz_lab_sample",
     * "columnCount":13,"message":"表 biz_lab_sample 已创建"}}} —— <b>扁平</b>，{@code id} 在顶层；
     * 规划器却给下游 {@code datamodel_structure} 写了 {@code "id":"{{$.create_out.model.id}}"}。
     * {@code .model} 那一段取不到，而 {@code StatePathNavigator.navigate} <b>刻意不抛</b>、
     * 统一返回 null（见其类注释「任一段缺失、下标越界、路径畸形 → 统一 null」），
     * {@code StateTemplateResolver.stringify(null)} 再把它变成 {@code ""}，
     * 最后 {@code Long.parseLong("")} 抛出
     * 「工具(datamodel_structure)的参数不合法：id 必须是数字，收到的是：」——
     * 冒号后面是空的，看错误的人根本不知道那个值原本该是什么。
     * 同一份 DSL 里另两处 {@code {{$.draft_out.id}}} 是<b>对的</b>，
     * 所以这不是「模型不会写占位符」，是「它只能赌，而赌错没有任何回音」。
     *
     * <h3>模型为什么会赌 {@code .model.id}</h3>
     * 建表的<b>入参</b>确实整块嵌在 {@code args.model} 里（见 {@code DataModelParamResolver.CREATE_PARAMS_SCHEMA}，
     * 本文件 V21 那节也专门讲过这层嵌套），它把入参形状镜像成了出参形状。
     * 而提示词那张「返回值形状」表此前<b>只渲染数组键</b>，{@code datamodel_create} 只声明了
     * {@code warnings} —— 那个真正被下游要的标量 {@code id} 从来没被印给模型看过。
     * 这一半在 {@code AgentToolFunction#resultKeys()} 与本类 V35 一起补齐：
     * 提示词印出正确路径（让它一次写对），本条在体检阶段拦下写错的（不指望它每次都读对表）。
     *
     * <h3>🔴 为什么只判<b>已登记</b>的工具，未登记的一律沉默</h3>
     * 与 V26 同一条纪律：<b>宁可漏报，不可误报</b>。误报会让规划期的自修正回路去改一个没病的地方，
     * 每一轮都是一次真实计费；更坏的是它会把一份本来能跑的流程拦在体检外，
     * 而用户看到的是一句「子键不存在」——他没法证明平台说错了。
     * {@code resultKeys()} 返回空列表的意思是<b>「平台还没登记」而不是「它不回对象」</b>
     * （51 个内置工具目前只登记了会被下游链式引用的一部分），所以空列表在这里必须当「不知道」处理。
     * 这条边界写在 {@code AgentToolFunction#resultKeys()} 的 javadoc 里，改那边就要改这里。
     *
     * <h3>🔴 刻意<b>不</b>要求产出者是当前节点的祖先</h3>
     * V18（{@link #validateReferenceReachability}）管的就是「产出者不在上游」，且它自己承认
     * 「并行/循环/子流程的接线由编译器在编译期决定，静态可达性无法 100% 精确」，故只给警告。
     * 本条问的是<b>另一件事</b>：那个变量<b>不管什么时候</b>被写，写进去的都是同一个工具的返回，
     * 顶层键的集合与顺序无关。所以只要「全 DSL 里恰好有一个节点写这个键、且它是个已登记形状的 tool 节点」，
     * 判据就是确定的 —— 不引入可达性，就不会继承它那份不确定性。
     * 多于一个产出者时（同名 outputVar 被两个节点写）直接跳过：那时无法确定形状，猜就是误报。
     *
     * <h3>🔴 扫描范围只有 {@code args} 的<b>顶层字符串值</b>，与运行期逐字对齐</h3>
     * {@code ToolNodeExecutor} 只对 args 的顶层 String 值做模板替换，嵌在 Map/List 里的<b>原样当字面串</b>
     * 传下去（这条事实在 V21 那节也写着）。所以：
     * <ul>
     *   <li>扫得更宽（递归进 args 的嵌套对象）就会去判一些<b>运行期压根不解析</b>的字符串，
     *       那些串里的 {@code {{$.x.y}} 是字面量}，报了就是误报；</li>
     *   <li>扫 {@code llm} 节点的 {@code prompt} 同样能解析出空串，但后果轻得多
     *       （模型看到一处空白，不会抛），且它走的是 {@code DslToStateGraphCompiler} 另一条模板路径 ——
     *       要扩到那里得先核对那条路径的语法是否完全一致，<b>本轮没做</b>，登记为已知缺口。</li>
     * </ul>
     * 这个窄口径也是它与已被否决的 N17 的分界：N17 提的是「给 tool.args 的<b>任意深度</b>嵌套占位符加强制点」，
     * 被否决是因为任意深度静态不可判；本条只判<b>一个特定槽位</b>（顶层字符串） against
     * <b>一个可证明的事实</b>（工具代码里声明的顶层键），与 V6/V26 的窄口径同款。
     *
     * <h3>编号纪律（本条是 <b>V35</b>）</h3>
     * 见 {@link #validateChatLegsWriteReply} 规则头那段权威源。⚠️ 「下一个可用号是几」只在那里说，本条不抄。
     * 🔴 上面这一行<b>不能</b>改成别的句式：号域靠 grep 数它，换措辞就等于这条规则从号域里消失，
     * 而没有任何测试会为它变红（判据本身写在那段权威源里，本处刻意不抄一遍，多抄一份就多一处自指噪声）。
     */
    private void validateToolArgSubkeys(List<NodeDTO> nodes, AgentValidateResult result,
                                        ToolResolver tools) {
        if (nodes == null || nodes.size() < 2) {
            return;
        }
        // 「这个键有人写吗」只许有一份口径，故复用 collectProducers（V18/V24/V26 用的就是它）
        Map<String, Set<String>> producers = collectProducers(nodes);
        if (producers.isEmpty()) {
            return;
        }
        Map<String, NodeDTO> nodesById = new HashMap<>();
        for (NodeDTO n : nodes) {
            if (n != null && StrUtil.isNotBlank(n.getId())) {
                nodesById.putIfAbsent(n.getId(), n);
            }
        }
        // toolId → 已登记的顶层键。空列表 = 未登记，缓存下来避免同一个工具在多个节点上反复解析
        Map<String, List<String>> keysByTool = new HashMap<>();

        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !"tool".equals(node.getType())) {
                continue;
            }
            Map<String, Object> args = node.configMap("args");
            if (args == null || args.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Object> arg : args.entrySet()) {
                // 只判顶层字符串值：与 ToolNodeExecutor 的替换范围逐字对齐，理由见方法注释
                if (!(arg.getValue() instanceof String text) || !text.contains("{{")) {
                    continue;
                }
                Matcher m = PLACEHOLDER_SUBKEY.matcher(text);
                while (m.find()) {
                    String root = m.group(1);
                    String subKey = m.group(2);
                    Set<String> producerIds = producers.get(root);
                    if (producerIds == null || producerIds.size() != 1) {
                        continue;
                    }
                    NodeDTO producer = nodesById.get(producerIds.iterator().next());
                    if (producer == null || !"tool".equals(producer.getType())) {
                        continue;
                    }
                    String producerTool = producer.configStr("toolId");
                    if (StrUtil.isBlank(producerTool)) {
                        continue;
                    }
                    List<String> known = keysByTool.computeIfAbsent(producerTool, tid -> {
                        ResolvedTool t = tools.resolve(tid);
                        return t.resolved() && t.exists() && t.shapeKnown() ? t.resultKeys() : List.of();
                    });
                    // 未登记 ⟹ 沉默（宁可漏报，理由见方法注释）
                    if (known.isEmpty() || known.contains(subKey)) {
                        continue;
                    }
                    result.addError("节点(%s) 的参数 %s 写了 {{$.%s.%s}}，但 %s 是工具 %s 的返回，"
                                    + "它的顶层键只有：%s —— 没有 %s 这一层。"
                                    + "运行期这段路径取不到值时会安静地变成空串（不报错），"
                                    + "下游工具于是收到一个空值，报出来的那句往往指着别的地方"
                                    + "（实测是「id 必须是数字，收到的是：」，冒号后面空的）。"
                                    + "请从上面那份键名里挑一个；结果对象本身就是扁平的，没有中间层",
                            displayId(node.getId(), i), arg.getKey(), root, subKey,
                            root, producerTool, String.join(" / ", known), subKey);
                }
            }
        }
    }

    // ==================== form_save_draft 的选项来源（V29） ====================

    /**
     * form_save_draft 冻在 DSL 里的<b>选项来源</b>（{@code dictType} / {@code options}），规划期就拦一遍
     *
     * <h3>🔴 复现的缺陷：AI 建出来的下拉是空的，而一路没有任何一处会主动说</h3>
     * 用户说「状态：待处理 / 已处理」，模型给的是 {@code {"widget":"select","dictType":"handle_status"}} ——
     * 一个它凭直觉编出来的字典名。实测过一批：34 份定义里 7 个 dictType，<b>6 个是编的</b>，
     * 其中 2 个挂在<b>已发布</b>的定义上。
     *
     * <p>而修复前这条路上一共<b>四次静默</b>：
     * <ol>
     *   <li>{@code FormFieldFactory#toRule} <b>压根不写选项</b> —— {@code dictType} 唯一的消费者是
     *       {@code buildSearchItem}，且只在 {@code searchable=true} 时才被调到。于是产物是
     *       「列表查询区有下拉、表单里那一格是空下拉」；</li>
     *   <li>字段描述里<b>没有 options 这个键</b> —— 模型想给固定选项也无从表达，
     *       <b>这正是它去编 dictType 的成因</b>；</li>
     *   <li>草稿存得进、发布也过得去（infra 的发布关只查「配了字典的，字典还在不在」，
     *       不查「配没配」）；</li>
     *   <li>infra 的发布关确实会拒编出来的字典，但那已经是<b>用户点完确认、表已经建了、
     *       草稿已经存了</b>之后 —— 与 {@code image} 控件（{@link #validateSaveDraftWidgets}）、
     *       {@code parentMenuName}（{@link #validatePublishParentMenu}）那两个事故同一个形状。</li>
     * </ol>
     *
     * <h3>判据一份都不在这里：先用同一个 {@code applyOptionSource} 摊成 rule，再过同一个校验</h3>
     * 🔴 这条规则<b>刻意不自己判</b>「dictType 该写到哪、options 长什么样」：
     * 它把描述符交给 {@code FormFieldFactory#applyOptionSource}（运行期真正写库的那一份）摊成一条 rule，
     * 再交给 {@code FormSchemaValidator#validateOptionSource}（运行期 {@code form_save_draft} 也过的那一份）判。
     * 所以「规划期与运行期同口径」在这里是<b>结构上</b>成立的，不是靠注释互相提醒 ——
     * 抄第二份判据的话，两边一漂就正好<b>重演</b>本条要消灭的那个缺陷，只是换到体检这一侧。
     *
     * <h3>⚠️ 探测故障时整趟停手，且正常情况下<b>根本轮不到本条停</b></h3>
     * 本条唯一会抛的探测是<b>读控件契约</b>（判「是不是选项类控件」要先归一）—— 那是一次
     * <b>全局批量读</b>、不接受逐项参数，故障必然持续 ⟹ 整趟停手 + 一句认领真实范围的黄标
     * （{@link #warnProbeAborted}，判据见它自己的注释）。
     * 但这一路<b>正常情况下走不到</b>：{@link #validateSaveDraftWidgets} 排在前面、读的是同一份契约，
     * 它中止时返回 {@code false}，本条整趟直接跳过 —— 黄标也已由它认领（范围里点明了「选项来源」）。
     * 不这么串的代价不只是多一次 infra 调用，而是<b>两条同因的黄标</b>：
     * 「10 条相同的黄标不是提醒，是在教读者这个黄标不用看」。
     *
     * <p>🔴 至于<b>查字典</b>（{@code DictDataApi#getDictType}，入参就是那一个字典、
     * 故故障只与它有关）：它<b>抛不到这里</b> —— {@code FormSchemaValidator#validateOptionSource}
     * 内部已经把字典探测包成 fail-soft（查不动就 log.warn 跳过可用性校验、不拦），
     * 与「一次数据源抖动不该连坐别的字典」那条纪律接上。所以下面那个 catch 一律按整趟处理，
     * 不需要（也没法）从异常上区分两类探测。
     *
     * <h3>🔴 每个字段只许探一次契约，且探测必须在 try 里面</h3>
     * 归一每调一次就读一遍 {@code getWidgetContracts()}（infra 那侧每次调用都重建整份契约 DTO、没有缓存）。
     * 所以「是不是选项类控件」与「它的规范名」必须由 {@link FormSchemaValidator#optionWidgetCanonical}
     * <b>一次</b>拿到，规范名再交给 {@link FormSchemaValidator#validateOptionSource} 那个「已归一」的重载
     * （它自己不再归一）；只有「声明了选项来源、却不是选项类控件」这条罕见分支才多付一次
     * {@code checkWidgetType}（用来判断该不该闭嘴，见下）。
     * 更要紧的是探测<b>必须包在 try 里</b>：裸在外面的话契约一故障，异常直接穿出 {@code validate()}，
     * 把「探测失败不该拦规划」那条纪律整个打穿（体检接口 500 = 用户既看不到错、也发不出去）。
     *
     * <h3>🔴 向后兼容的代价，认下来（{@link #validateToolArgsShape} V21 那条同款）</h3>
     * 存量已发布的 def 里若有「select 没配任何选项来源」或「配了个编出来的字典」，
     * 重新发布会被本条拦住。这是刻意的 —— 实测那批里就有 3 个字段一个来源都没有
     * （其中 2 个已发布）、2 个已发布定义挂着编出来的字典，
     * 它们<b>今天渲染出来就是空下拉</b>，「当初发得出去」不是它正确的证据。
     *
     * <p>编号纪律（本条是 <b>V29</b>）见 {@link #validateChatLegsWriteReply} 规则头那段：
     * V17 缺号、V20 与迭代二否掉的那条撞号，故新规则接着最后一个号往下取。
     * ⚠️ 这里<b>刻意不写</b>「下一个可用号是几」：那句话的权威源只有
     * {@link #validateChatLegsWriteReply} 一处，抄第二份就会在下一条规则落地时变成假话。
     *
     * @param widgetProbeOk {@link #validateSaveDraftWidgets} 的返回值：{@code false} 表示控件契约读不动、
     *                      那条已按真实范围报过 warn，本条<b>一次都不许再探</b>
     */
    private void validateSaveDraftOptionSources(WorkflowDefDTO def, AgentValidateResult result,
                                                boolean widgetProbeOk) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null || !widgetProbeOk) {
            return;
        }
        // 探测中止时那句 warning 要能说出「已经查过几处」，与 validateSaveDraftWidgets 同款
        int checked = 0;
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !"tool".equals(node.getType())
                    || !SAVE_DRAFT_TOOL_ID.equals(node.configStr("toolId"))) {
                continue;
            }
            Map<String, Object> args = node.configMap("args");
            Object fields = args == null ? null : args.get("fields");
            if (!(fields instanceof List<?> list)) {
                continue;
            }
            String label = displayId(node.getId(), i);
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> field)) {
                    continue;
                }
                Object widget = field.get("widget");
                if (!(widget instanceof String type) || StrUtil.isBlank(type)) {
                    continue;
                }
                String fieldName = String.valueOf(field.get("field"));
                boolean declaresSource = field.containsKey(FormFieldFactory.DICT_TYPE_KEY)
                        || field.containsKey(FormFieldFactory.OPTIONS_KEY);
                AgentValidateResult probe = new AgentValidateResult();
                try {
                    String canonicalOption = formSchemaValidator.optionWidgetCanonical(type);
                    checked++;
                    if (canonicalOption == null) {
                        // 🔴 控件名本身有问题时闭嘴：那是 validateSaveDraftWidgets 的根因，
                        //    它已经报过一句（「控件类型 X 不存在」/「X 已停用」/「X 建不出来」——
                        //    最后那种正是 {@code cascader} 配了 dictType 的形状：白名单那道闸
                        //    现在连「字段描述表达不了它的必需配置」一起判）。这里再叠一句
                        //    「X 不是选项类控件」只是把同一个根因说两遍，
                        //    还会把模型的注意力从真正要改的那一格上引开。
                        //    ⚠️ 这一次 checkWidgetType 是整个方法里唯一的第二次契约读，
                        //    故只在「声明了来源却不是选项控件」这条罕见分支上付。
                        //    ⚠️ 也别把这道闸提到循环开头去「统一防重复」：那会让每个字段多读一次契约
                        //    （探测次数与中止点各翻倍），换来的只是「契约把 select 标成 supported=false」
                        //    那种平台级异常下少说一句 —— 而那种时候两句都说比只说一句更安全。
                        if (declaresSource && formSchemaValidator.checkWidgetType(type) == null) {
                            result.addError("节点(%s) 的 form_save_draft 参数里字段 %s 配了选项来源"
                                            + "（dictType / options），但 %s 不是选项类控件 —— 只有 %s 会读它。"
                                            + "写在别的控件上会被整个忽略：不报错，界面上也看不出任何变化。"
                                            + "要么把控件换成其中之一，要么把这两个键删掉",
                                    label, fieldName, type, FormFieldFactory.optionWidgetCatalog());
                        }
                        continue;
                    }
                    JSONObject rule = new JSONObject()
                            .set("type", canonicalOption)
                            .set("field", fieldName);
                    fieldFactory.applyOptionSource(rule, JSONUtil.parseObj(field));
                    // 🔴 requireOptionSource=true：save_draft 是【新建】，这一格是本次刚写的，
                    //    没有任何存量需要保护（与 form_update 那条只能是 warning 的理由正好相反，
                    //    见 FormSchemaValidator#validateOptionSource）
                    formSchemaValidator.validateOptionSource(fieldName, canonicalOption, rule, probe, true);
                } catch (Exception ex) {
                    // 能抛到这里的只有契约读（字典那一路在 validateOptionSource 内部已 fail-soft），
                    // 而契约是全局批量读 ⟹ 故障必然持续 ⟹ 整趟停手，作用域跟着入参走
                    warnProbeAborted(result, "选项来源校验", "读控件契约", ex,
                            String.format("已校验 %d 处，本次体检中其余 form_save_draft 字段的选项来源全部未校验"
                                            + "（停在节点(%s) 的字段 %s，控件类型 %s）",
                                    checked, label, fieldName, type));
                    return;
                }
                // 逐条搬过来而不是把 probe 直接当结果用：本类的报错一律要点名「是哪个节点」，
                // 而 validateOptionSource 只知道自己那一个字段（它服务的是运行期，那里没有节点概念）
                for (String error : probe.getErrors()) {
                    result.addError("节点(%s) 的 form_save_draft 参数里%s。"
                                    + "这份参数是冻进 DSL 的，运行期不会再问模型，必须在规划期改对",
                            label, error);
                }
                for (String warning : probe.getWarnings()) {
                    result.addWarning("节点(%s) 的 form_save_draft 参数里%s", label, warning);
                }
            }
        }
    }

    /**
     * 「探测这个动作本身故障」的统一出口（方案 §6.1：探测得出「不合法」才是 error，探测失败不是）
     *
     * <p>🔴 用它的每一处（控件白名单、选项来源、上级菜单、BPM 流程模型、可用模型）探测的都是
     * <b>一次全局批量读、不接受逐项参数</b>：{@code getWidgetContracts()}、读启用目录清单、
     * {@code getModelList(null)}、{@code getEnabledModelList(CHAT)}。所以故障必然是<b>持续性</b>的 ——
     * 逐项重试只会把同一个故障 API 再打 N 遍，再刷出 N 条一模一样的黄标，而「10 条相同的黄标」不是提醒，
     * 是在教读者「这个黄标不用看」（与 V11 把 LOW/MEDIUM/HIGH 合成一条同一条纪律）。
     * 故这几处一律<b>整趟停手</b>，代价是文案必须把真实跳过范围说全。
     *
     * <p>⚠️ 同一次故障<b>只许认领一次</b>：控件白名单与选项来源读的是<b>同一份</b>契约，
     * 前者中止时返回 {@code false}，后者据此整趟跳过、不再探也不再报 ——
     * 由前者那句黄标把两项跳过范围一起说全（{@link #validateSaveDraftWidgets} 的文案里点明了「选项来源」）。
     *
     * <p>⚠️ 与 {@link #mainTableExists} / {@link #validateSaveDraftColumns} 刻意<b>不同</b>：
     * 那两处的探测入参<b>就是那一张表</b>（{@code getPhysicalTableList(table)}、读表结构），
     * 故障只与这一张表有关，别的表照样查得动，故只跳过本节点（S4a-d 已改，并有用例钉住
     * 「节点1 查表失败不影响节点2」）。
     *
     * <p>🔴 判据是「<b>探测的作用域跟着它的入参走</b>」—— 不是「一律 continue」，
     * 也不是「一律 return」。D-new8 最初记成前者，照那个改会把三处全局探测各刷出 N 条重复黄标。
     *
     * @param checkName    这条校验的中文名，如「控件白名单校验」
     * @param probeName    失败的那个动作，如「读控件契约」
     * @param skippedScope <b>真实</b>跳过范围的中文描述（已算好的整句，本方法只当参数拼进去）
     */
    private static void warnProbeAborted(AgentValidateResult result, String checkName, String probeName,
                                         Exception ex, String skippedScope) {
        result.addWarning("%s中止（%s失败：%s）：%s。这一项本次体检给不出结论，"
                        + "给不出结论不等于它没问题；上面那个故障恢复后重新体检即可",
                checkName, probeName, ex.getMessage(), skippedScope);
    }

    /**
     * form_save_draft 绑的主表必须「此刻存在」或「本流程会在它之前建」
     *
     * <p>🔴 复现的事故：规划期 analyze 自己写着「物理表 total=0、需新建 biz_device」，
     * 产出的 DSL 却<b>没有建表节点</b>，save_draft 直接绑 biz_device —— 运行期一路 SUCCESS，
     * 表单也保存成功，只是绑了张不存在的表，用户发布后点列表才撞 SQL 异常。
     * 运行期 {@code FormSaveDraftTool.validateMainTableExists} 是最后一道闸，
     * 但在那里失败意味着 token 已烧、确认已点；规划期拦下能让自修正回路补一个 datamodel_create。
     *
     * <p>「会在它之前建」的判定：存在 datamodel_create / datamodel_import 节点，
     * 且沿 edges 能走到 save_draft（<b>不</b>解析建表节点的表名 —— 建错表名由运行期那道闸兜）。
     *
     * <p>🔴 但命中这条判定之后<b>不是</b>整项跳过：{@link #declaredColumnsOfInDslCreator}（V30）
     * 会去读那些建表节点声明的列，与 save_draft 的字段映射核一遍。
     * 「表还没建所以列无从查」只对<b>查库</b>那一路成立；建表参数就冻在同一份 DSL 里，
     * 静态判得了，而判不了的代价是一次不可回滚的 DDL 加一张发布不出去的表单。
     * V30 认不出这张表是谁建的（建的是别的表 / 是 datamodel_import / 参数读不出来）时，
     * 才落回下面查库那一路。
     *
     * <p>🔴 这条「能走到」的判定在 S2/D-new7 之前是<b>偏松</b>的：它内联的那套正向遍历
     * 无条件把条件边的 {@code to} 也当成一条通路，而编译器对条件边从不读 {@code to}
     * （{@code DslToStateGraphCompiler#buildStateGraph}）。由于这里是一句 {@code if (...) continue;}，
     * 偏松 = <b>少报一个 error</b>，正好放过了本校验存在的那个理由（上面记的那次真实事故）。
     * 收编到 {@link GraphReachability} 后与运行期同口径，等价性与这一处差异都由
     * {@code GraphReachabilityTest#conditionalEdgeWithStrayToIsIgnoredLikeTheCompiler} 钉住。
     */
    private void validateSaveDraftMainTable(WorkflowDefDTO def, GraphReachability.Graph graph,
                                            AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null) {
            return;
        }
        Set<String> creatorIds = new HashSet<>();
        for (NodeDTO node : nodes) {
            if (node == null || !"tool".equals(node.getType())) {
                continue;
            }
            // 🔴 先把 toolId 取出来再判空：TABLE_CREATOR_TOOL_IDS 是 Set.of(...) 的不可变集合，
            //    contains(null) 直接 NPE（ImmutableCollections.Set12.contains 上来就调 o.equals）。
            //    而「tool 节点没有 toolId」正是 LLM 产 DSL 的常见形状、V1 此刻正在报它 ——
            //    两条校验共用同一份 nodes，不能让一个节点缺键把整个 validate 打成 500
            //    （体检接口 500 = 用户既看不到错、也发不出去，比漏报更坏）。
            String creatorToolId = node.configStr("toolId");
            if (creatorToolId != null && TABLE_CREATOR_TOOL_IDS.contains(creatorToolId)) {
                creatorIds.add(node.getId());
            }
        }
        // V30 那一趟的系统列名单只探一次（探不动就整趟停手，理由见 SystemColumnProbe）
        SystemColumnProbe systemColumns = new SystemColumnProbe();
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !"tool".equals(node.getType())
                    || !SAVE_DRAFT_TOOL_ID.equals(node.configStr("toolId"))) {
                continue;
            }
            Map<String, Object> args = node.configMap("args");
            Object mainTable = args == null ? null : args.get("mainTable");
            if (!(mainTable instanceof String table) || StrUtil.isBlank(table)) {
                continue;
            }
            String label = displayId(node.getId(), i);
            // 🔴 占位符列名先扫、且无条件扫：这一格运行期<b>不</b>做变量替换，所以它的判定
            //    与「表在不在库里」「表是不是本流程自己建的」<b>全无关</b>——不需要读一次库就能定。
            //    放在下面那两道 continue 之前是必须的：最常见的形状恰恰是「本流程先 datamodel_create
            //    再 form_save_draft」，放后面就会被第一道 guard 整个跳过，等于这条校验在最需要它的
            //    那种流程上永远不开口（C6）。
            validateSaveDraftColumnPlaceholders(node, label, result);
            if (!creatorIds.isEmpty() && graph.reachesAny(creatorIds, node.getId())) {
                // 本流程会在 save_draft 之前建表：表此刻还不存在，查库那两项都无从谈起。
                // 建错表名由运行期那道闸兜。
                // 🔴 但「建漏了列」静态判得了 —— 建表参数就冻在<b>同一份 DSL</b> 里（V30）。
                //    这里原先是一句无条件 continue，注释断言「建漏了列由建表节点自己的参数负责」，
                //    而没有任何东西去核这句话：实测（访客来访登记）8 个表单字段配 7 个建表列，
                //    一路过体检、真建了表（DDL 不可回滚）、真存了草稿，直到发布那一步才报
                //    「表中不存在列(status)」—— 留下一个回不去的半成品。
                InDslTable declared = declaredTableOfInDslCreator(table, nodes, graph,
                        node.getId(), label, result);
                if (declared != null) {
                    if (declared.columns() != null) {
                        validateSaveDraftColumnsAgainstDeclaredColumns(node, label, table, declared,
                                systemColumns, result);
                    }
                    // 🔴 columns == null（有可达的建表节点、而它的参数读不出来）时<b>也</b> continue，
                    //    不落回查库那一路：那种情况下「这张表是不是它建的」压根没结论，
                    //    而落回去会报「数据库里没有这张表，请加一个 datamodel_create 节点」——
                    //    DSL 里<b>已经有一个</b>了。一句假话加一条错修法，
                    //    模型照它改就是往一个已经存在的节点上再叠一个。
                    //    黄标已在 declaredTableOfInDslCreator 里认领过「这一项给不出结论」，够了
                    continue;
                }
                // 走得到的建表节点里<b>没有一个</b>声明这张表（它建的是别的表、或它是 datamodel_import
                // 只登记既有表）⟹ 「本流程会把它建出来」这个借口对<b>这张表</b>不成立，
                // 落回查库那一路：那种情况下表要么本来就在库里，
                // 要么就该由上面那条「数据库里没有这张表」的 error 去说
            }
            // 🔴 表都不在就别再校验列了：那条 error 已经把「加一个 datamodel_create」说清楚，
            //    再叠一条「列也对不上」只是把同一个根因说两遍，白占模型一轮注意力
            if (mainTableExists(table, label, result)) {
                validateSaveDraftColumns(node, label, table, result);
            }
        }
    }

    /**
     * 主表此刻在不在库里
     *
     * @return {@code true} 表示确认存在。表不存在（已报 error）、「查表这个动作本身故障」
     *         （已报 warning）与「表名要到运行期才确定」（已报 warning）都返回 {@code false}：
     *         后两者按方案 §6.1 那条纪律不算「不合法」，但它们同样让列校验无从继续，
     *         故与「不存在」走同一条出路
     */
    private boolean mainTableExists(String table, String label, AgentValidateResult result) {
        // 🔴 表名是占位符时不能拿字面串去查库（#80）：mainTable 是 tool.args 的<b>顶层字符串值</b>，
        //    运行期 ToolNodeExecutor#execute 会对它调 resolve ⟹ 「运行期才算出表名」是个真能跑通的形状
        //    （存量 id=134 这么跑了 13 次全 SUCCEEDED），而字面串 {{$.x}} 在库里当然查不到。
        //    不跳过的后果不只是多一条假 error：那条 error 的文案还教作者「加一个 datamodel_create
        //    把 {{$.x}} 建出来」—— 一个照着做就把数据建进一张垃圾表名的修法。
        // ⚠️ 谓词刻意用 StateTemplateResolver.containsPlaceholder、<b>不用</b>本类那个 isPlaceholder：
        //    后者是 contains("{{")，服务的是「这一格压根不做变量替换」的三个端口
        //    （bpmProcessDefKey / workflowId / modelId），那里「看起来像占位符」就该拦；
        //    而这里要问的是「运行期会不会把它替换掉」，答案必须与真正做替换的那个正则同源。
        //    两者只在畸形输入上分岔：`{{unclosed` 宽松谓词当占位符跳过（漏掉一个真错），
        //    同源谓词认出它不是占位符 ⟹ 运行期原样传下去 ⟹ 该报 error。
        //    这也正是干跑层 ChangeManifestDryRunner#probeFormCreate 用的同一个谓词（经 ManifestArgs.isPending）——
        //    两层共用一个口径，才不会出现「静态说表不存在、干跑说无从判定」这种自相矛盾的结论。
        if (StateTemplateResolver.containsPlaceholder(table)) {
            result.addWarning("节点(%s) 的 form_save_draft 主表名要到运行期才确定（%s），"
                            + "所以它存不存在、以及字段映射的那几个列在不在表里，体检期都无从判定，"
                            + "本次这两项都跳过了",
                    label, table);
            return false;
        }
        try {
            boolean exists = dataModelApi.getPhysicalTableList(table).stream()
                    .anyMatch(item -> table.equalsIgnoreCase(item.getTableName()));
            if (!exists) {
                result.addError("节点(%s) 的 form_save_draft 绑定主表 %s，但数据库里没有这张表，"
                                + "流程里也没有会在它之前执行的 datamodel_create/datamodel_import 节点。"
                                + "表单发布后列表与提交都直接读写这张表，绑不存在的表只会「保存成功、一用就炸」。"
                                + "请在 save_draft 之前加一个 datamodel_create 节点把 %s 建出来",
                        label, table, table);
            }
            return exists;
        } catch (Exception ex) {
            // 与运行期同一条纪律：查表这个动作故障时不拦规划。
            // 🔴 只跳过<b>本节点</b>（D-new8）：这里原先是 return，一次数据源抖动会把后面所有
            //    save_draft 节点的主表与列校验一起静默跳过 —— 而「静默少校验」正是本类要消灭的那类缺陷，
            //    且它与 warning 文案说的「%s 未校验」对不上（文案只认领了这一张表）
            // ⚠️ 刻意<b>不</b>走 {@link #warnProbeAborted}：那个出口是给「探测是一次全局批量读、
            //    故障必然持续」的三处用的，而本处探测的入参<b>就是这张表</b>，别的表照样查得动。
            //    判据是「探测的作用域跟着它的入参走」，详见那个方法的注释
            result.addWarning("主表存在性校验跳过（查物理表失败：%s），%s 未校验", ex.getMessage(), table);
            return false;
        }
    }

    // ==================== form_publish_bundle 的上级菜单（V27） ====================

    /**
     * form_publish_bundle 冻在 DSL 里的上级菜单，规划期就按运行期同一份目录清单拦一遍
     *
     * <h3>🔴 复现的事故：整条流程跑到<b>最后一步</b>才炸，而建表的 DDL 已经不可回滚</h3>
     * 实测（「帮我做一张员工入职登记表…」）模型产出的 DSL 是
     * widget_contract_get → datamodel_create → form_save_draft → form_inspect → form_publish_bundle，
     * 最后一格写着 {@code "parentMenuName":"人事管理"} —— 一个它凭「这是人事用的表单」直觉编出来的名字，
     * 库里没有这个目录。运行期 {@code FormPublishParamResolver} 如实拒绝并列出可选目录，
     * 但那时表<b>已经建了</b>、草稿<b>已经存了</b>、体检<b>已经过了</b>：
     * 用户拿到的是一张失败卡片，外加一张建出来却没发布的表单和一张回滚不掉的物理表。
     *
     * <p>这与 {@link #validateSaveDraftWidgets} 记的那个 {@code image} 是同一类缺陷
     * （args 是规划期<b>写死</b>的，运行期照抄、不会再问模型），教训也同一条：
     * 规划期不拦，就是把「自修正回路够不着的失败」原样甩给用户。
     *
     * <h3>判据都不是这里发明的</h3>
     * 目录清单取自 {@link MenuApi#getEnabledDirectoryList()}、比对用 {@code String.equals}，
     * 与运行期 {@code FormPublishParamResolver#resolveParentMenuId} 逐字同源，
     * 故不会出现「规划期放行、运行期翻脸」。本类<b>不</b>持有任何目录名清单：
     * 手抄一份必然漂移，而漂了的表现正是这条要拦的那个静默失败。
     *
     * <p>⚠️ 占位符谓词用 {@code StateTemplateResolver#containsPlaceholder}，与
     * {@link #mainTableExists} 同源而<b>不是</b>宽松的 {@link #isPlaceholder}：
     * 这一格是 args 的<b>顶层字符串值</b>，运行期会被替换掉，所以「运行期才算出菜单名」
     * 是个真能跑通的形状，该跳过而不是拦下。
     *
     * <h3>编号纪律（本条是 <b>V27</b>）</h3>
     * 见 {@link #validateChatLegsWriteReply} 规则头那段权威源。⚠️ 「下一个可用号是几」只在那里说，本条不抄。
     *
     * <p>🔴 本条落地时<b>漏了取号</b>（section 分隔条与规则头都没写号），是补 V28 时才发现、回填的。
     * 漏号的后果不是「少一个数字」：那份权威源明写「号数怎么重新数」的判据是
     * {@code grep -c "本条是 <b>V"}，不取号的规则对它<b>完全不可见</b> ——
     * 与权威源自己花三轮才删掉的那个「恒绿验证脚本」是同一类缺陷，只是这次漏的是被数的那一侧。
     */
    private void validatePublishParentMenu(WorkflowDefDTO def, AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null) {
            return;
        }
        List<String> directories = null;
        boolean readFailed = false;
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !"tool".equals(node.getType())
                    || !FormPublishBundleTool.NAME.equals(node.configStr("toolId"))) {
                continue;
            }
            Map<String, Object> args = node.configMap("args");
            if (args == null) {
                continue;
            }
            String label = displayId(node.getId(), i);
            checkParentMenuId(args, label, result);
            Object rawName = args.get(FormPublishBundleTool.PARAM_PARENT_MENU_NAME);
            if (!(rawName instanceof String name) || StrUtil.isBlank(name)) {
                // 不写名字是合法的：改传 parentMenuId，或两者都不传表示挂到顶层
                continue;
            }
            if (StateTemplateResolver.containsPlaceholder(name)) {
                result.addWarning("节点(%s) 的 form_publish_bundle 上级菜单名要到运行期才确定（%s），"
                                + "所以它在不在启用目录里，体检期无从判定，本次这一项跳过了",
                        label, name);
                continue;
            }
            if (readFailed) {
                continue;   // 已经报过一条中止 warning 了，一个根因只说一遍
            }
            if (directories == null) {
                // ⚠️ 读到的清单先落局部变量，<b>成功了才</b>赋给 directories，于是有个不变式：
                //    directories != null ⟺ 真的读到过一份清单。
                //    反面写法（在 try 里就地 {@code directories = new ArrayList<>()}）会在读失败时
                //    把它留成<b>空表</b>，而空表在下面那个判据里恰好等于「一个启用目录都没有」——
                //    探测故障就被翻译成一条假 error，还附送「两个参数都别传」的假修法。
                //    🔴 今天上面那个 {@code readFailed} 已经挡住了这件事，所以这条不变式
                //    <b>没有用例能单独钉住它</b>（实测：改回反面写法，243 条全绿）。留着它的理由是
                //    让两道闸各管一件事、互不依赖：哪天有人为了别的原因去掉 readFailed，
                //    退化只是「多几条重复黄标」，而不是「一条教人挂错地方的假 error」
                List<String> read;
                try {
                    read = new ArrayList<>();
                    for (MenuSimpleRespDTO dir : menuApi.getEnabledDirectoryList()) {
                        read.add(dir.getName());
                    }
                } catch (Exception ex) {
                    readFailed = true;
                    // 与运行期同一条纪律：查目录这个动作故障时不拦规划。
                    // ⚠️ 这里的作用域是<b>整趟</b>而不是「本节点」（故与 mainTableExists 那个出口刻意不同）：
                    //    探测的入参不含节点信息，一次故障必然对每个节点都故障，
                    //    所以文案要如实认领「其余节点全部未校验」
                    warnProbeAborted(result, "上级菜单校验", "读启用目录清单", ex,
                            String.format("本次体检中其余 form_publish_bundle 节点的 parentMenuName 全部未校验"
                                    + "（停在节点(%s)，目录名 %s）", label, name));
                    continue;
                }
                directories = read;
            }
            if (!directories.contains(name.trim())) {
                result.addError("节点(%s) 的 form_publish_bundle 要把表单挂到目录「%s」下，"
                                + "但没有这个名字的启用目录。可选的目录名有：%s。"
                                + "这一格是冻进 DSL 的，运行期不会再问模型，必须在规划期改对 —— "
                                + "留到运行期的话，流程会跑完建表、存草稿、体检，直到最后一步发布才失败，"
                                + "而那时 DDL 已经执行完、回滚不掉了",
                        label, name, directories.isEmpty()
                                ? "（当前一个启用的目录都没有，请改成不传 parentMenuName 也不传 parentMenuId，挂到顶层）"
                                : String.join("、", directories));
            }
        }
    }

    /**
     * {@code parentMenuId} 写的是字面量就必须是数字（占位符留给运行期替换）
     *
     * <p>与 {@link #validatePublishParentMenu} 同一个理由：运行期那个解析器对非数字直接抛
     * {@code AGENT_TOOL_PARAM_INVALID}，而那已经是流程的最后一步。
     * 这一条不查库、没有误报面，故顺手一起拦。
     */
    private static void checkParentMenuId(Map<String, Object> args, String label, AgentValidateResult result) {
        Object raw = args.get(FormPublishBundleTool.PARAM_PARENT_MENU_ID);
        if (raw == null) {
            return;
        }
        String value = raw.toString().trim();
        if (value.isEmpty() || StateTemplateResolver.containsPlaceholder(value)) {
            return;
        }
        try {
            Long.parseLong(value);
        } catch (NumberFormatException ex) {
            result.addError("节点(%s) 的 form_publish_bundle parentMenuId 必须是数字"
                    + "（不传或传 0 表示挂到顶层菜单），实际收到：%s", label, value);
        }
    }

    /**
     * form_save_draft 每个字段要落的那个物理列，必须在主表里真的存在（V6）
     *
     * <h3>🔴 今天没有任何一层查这件事</h3>
     * <ul>
     *   <li>运行期 {@code FormFieldFactory#loadPhysicalSchema} <b>读的就是这份列结构</b>，
     *       但它只拿来把表单的 required 与列的 NOT NULL 对齐、把 binding 的 jdbcType 归真：
     *       列不存在时它取到的是 {@code null}，
     *       于是「对齐」安静地什么都不做，字段照样写进 fieldMapping；</li>
     *   <li>{@code FormSchemaValidator} 校验的是展开后的 rule 与 fieldMapping，查的是
     *       column 非空、jdbcType 非空、不许映射 {@code tenant_id} —— 它从不查库；</li>
     *   <li>{@code ChangeManifestDryRunner#probeFormCreate} 的注释明确写了这半边「探不了」
     *       （要探就得重跑工具的字段展开逻辑），故整项落 {@code NOT_PROBED}。</li>
     * </ul>
     * 三层都不管的下场与主表那条一模一样：表单<b>保存成功、发布成功</b>，
     * 直到用户提交或打开列表才撞一句「Unknown column」——
     * AGENTS.md 点名的「配置保存成功但不生效且无提示」。
     *
     * <h3>⚠️ 列名口径必须与工具逐字一致</h3>
     * {@code FormSaveDraftTool#build} 是 {@code blankToDefault(field.column, field.field)} ——
     * <b>column 缺省时用 field 顶</b>。这里若只读 {@code column}，模型省略 column 的那些字段
     * （实测是多数）一个都校验不到，而它们恰恰是最容易写错的一批。
     *
     * <p>表没登记进数据模型时读不到列结构（{@code getDataModelList} 只返回已登记的），
     * 按 §6.1 落 warning 而不是 error：那是个合法且常见的状态 ——
     * {@code getPhysicalTableList} 存在的理由就是「库里有一批物理表从没登记过」。
     */
    private void validateSaveDraftColumns(NodeDTO node, String label, String mainTable,
                                          AgentValidateResult result) {
        Map<String, String> wanted = wantedColumnsOf(node);
        if (wanted.isEmpty()) {
            return;
        }
        Set<String> actual;
        try {
            actual = loadTableColumns(mainTable);
        } catch (Exception ex) {
            result.addWarning("列存在性校验跳过（读表 %s 结构失败：%s），%d 个字段的列名未校验",
                    mainTable, ex.getMessage(), wanted.size());
            return;
        }
        if (actual == null) {
            result.addWarning("列存在性校验跳过：表 %s 没登记进数据模型，读不到它的列结构。"
                            + "先加一个 datamodel_import 节点把它登记进来，这 %d 个字段的列名才校验得了",
                    mainTable, wanted.size());
            return;
        }
        List<String> missing = missingColumns(wanted, actual);
        if (missing.isEmpty()) {
            return;
        }
        result.addError("节点(%s) 的 form_save_draft 把 %d 个字段映射到主表 %s 上不存在的列：%s。"
                        + "表单会照样保存成功、照样发布成功，直到用户提交或打开列表才撞一句「Unknown column」。"
                        + "请改成表里真实存在的列名（现有列：%s），"
                        + "或先加一个 datamodel_update 节点把缺的列补上",
                label, missing.size(), mainTable, String.join("、", missing), String.join(", ", actual));
    }

    /**
     * 一个 form_save_draft 节点<b>想要</b>的那些列：字段显示名 → 它要落到的物理列名
     *
     * <p>🔴 两个消费者（{@link #validateSaveDraftColumns} 查库、
     * {@link #validateSaveDraftColumnsAgainstDeclaredColumns} 查同 DSL 的建表参数）必须共用这一份：
     * 各自写一遍的话，「哪个键算列名、空的怎么办、占位符怎么办」就会分岔，
     * 而分岔的表现是「查库那条说列在、查建表参数那条说列不在」—— 两条互相矛盾的结论同时出现在一张体检单上。
     *
     * <p>跳过的两类都<b>不是</b>漏检：空列名交给运行期那道必填校验；
     * 占位符列名由 {@link #validateSaveDraftColumnPlaceholders} 报 error（一个根因只说一遍）。
     *
     * @return 用 {@link LinkedHashMap} 保住模型给的顺序，报错时按原序念
     */
    private static Map<String, String> wantedColumnsOf(NodeDTO node) {
        Map<String, Object> args = node.configMap("args");
        Object rawFields = args == null ? null : args.get("fields");
        Map<String, String> wanted = new LinkedHashMap<>();
        if (!(rawFields instanceof List<?> list)) {
            return wanted;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> field)) {
                continue;
            }
            String fieldName = strOf(field.get("field"));
            String column = columnNameOf(field);
            if (StrUtil.isBlank(column) || isPlaceholder(column)) {
                continue;
            }
            wanted.put(StrUtil.blankToDefault(fieldName, column), column);
        }
        return wanted;
    }

    /**
     * {@code wanted} 里落不到 {@code actual} 上的那些，按 {@code wanted} 的原序念
     *
     * @return 形如 {@code 状态 → status} 的条目；{@code actual} 一律按<b>小写</b>比对
     *         （MySQL 列名大小写不敏感，两个调用方给的集合都已小写过）
     */
    private static List<String> missingColumns(Map<String, String> wanted, Set<String> actual) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : wanted.entrySet()) {
            if (!actual.contains(entry.getValue().trim().toLowerCase(Locale.ROOT))) {
                missing.add(entry.getKey() + " → " + entry.getValue());
            }
        }
        return missing;
    }

    /**
     * form_save_draft 的字段里，<b>列名写成了占位符</b>的那几个（V6 的前半，C6）
     *
     * <h3>🔴 这一格运行期<b>不</b>替换占位符 —— 所以这不是「静态无从判定」，是「静态判定得了、而且判得死」</h3>
     * {@code ToolNodeExecutor#execute} 解析 args 时<b>只替换顶层字符串值</b>：
     * {@code v instanceof String s ? StateTemplateResolver.resolve(s, ctx) : v}，Map / List 原样透传。
     * {@code fields} 是个<b>数组</b> ⟹ 嵌在里面的 {@code column} <b>永远不会被替换</b>；
     * {@code FormSaveDraftTool} 自己也不做任何解析（全文没有 {@code StateTemplateResolver}），
     * 拿到什么就把什么写进 {@code fieldMapping}。
     * 同一个陷阱在 {@code FormDataArgs#nestedPlaceholderFields} 的 javadoc 里已经写明白过
     * （那边讲的是 {@code form_data_create} 的 {@code data}），清单层也照它出条目 —— 只有校验层这一格漏了。
     *
     * <p>所以 {@code {{$.x}}} 在这里<b>不是</b>「要到运行期才有值」，而是一个<b>字面的垃圾列名</b>：
     * 表单保存成功、发布成功，直到用户提交或打开列表才撞一句「Unknown column」。
     *
     * <h3>⚠️ 谓词刻意用宽松的 {@link #isPlaceholder}，<b>不是</b>同源的 {@code containsPlaceholder}</h3>
     * 判据要跟着「这一格运行期到底替不替换」走：
     * <ul>
     *   <li>{@link #mainTableExists} 那一格<b>会</b>替换（{@code mainTable} 是 args 的顶层字符串值），
     *       所以它问的是「运行期会不会把它换掉」⟹ 必须与真做替换的那个正则<b>同源</b>；</li>
     *   <li>本方法这一格<b>不会</b>替换 ⟹ 问的是「看起来像不像占位符」，与 {@code bpmProcessDefKey} /
     *       {@code workflowId} 那两个端口同类，用宽松谓词。畸形形状 `{{unclosed` 在这里
     *       <b>同样该拦</b>（它既不会被替换、也不是合法列名）。</li>
     * </ul>
     * 🔴 若照原方案换成同源谓词，`{{unclosed` 会掉进下面的列存在性检查，被报成「列不存在」，
     * 而那条文案的出路是「加一个 datamodel_update 把缺的列补上」——
     * 照着做就会去建一个名叫 `{{unclosed` 的列。与 {@link #mainTableExists} 注释里
     * 点名的那个「教作者把垃圾表名建出来」是同一个坑，只是换到了列上。
     *
     * <h3>🔴 这与 N17「不给 tool.args 的嵌套占位符加强制点」不冲突</h3>
     * N17 否决的是<b>通用</b>强制点：任意深度、任意形状、值还可能是运行期才展开的 Map ⟹ 静态不可判定。
     * 本方法不是通用规则，它是 V6 这条<b>已有的、专属某一格</b>的校验：V6 本来就要拿
     * {@code fields[].column} 去比物理列，而 {@code {{$.x}}} <b>可证明</b>不是任何一个真实列名。
     * 换句话说这里没有新增判定点，只是让一个<b>已经在判这一格</b>的校验不再拿一条假理由跳过它。
     *
     * <h3>🟡 与 {@link #validatePlaceholders} 的分工（本轮实测撞出来的，不是推断）</h3>
     * 那条引用可达性规则的 {@code collectUnresolvableRoots} <b>会递归进 Map 与 List</b> ⟹
     * 嵌在 {@code args.fields[]} 里的占位符它<b>也看得见</b>。所以两者会重叠，但重叠是有条件的：
     * <ul>
     *   <li>根变量<b>没声明</b>（{@code col} 既不在 {@code variables[]} 也不是任何 {@code outputVar}）
     *       ⟹ <b>两条都报</b>。这是<b>刻意留着</b>的：两条的出路不同（那条说「改引用或写字面值」，
     *       本条说「这一格压根不替换，写了字面值也不许是占位符」），而模型自修正回路读的是整份
     *       feedback，多一条同向的 error 只会让它更不容易挑错方向。真要去重，代价是给
     *       {@code validatePlaceholders} 加一个「这一格属于 form_save_draft 的 fields[].column 就跳过」
     *       的例外 —— 把一条通用规则的口径绑到一个具体工具的参数名上，不划算。</li>
     *   <li>根变量<b>已声明</b> ⟹ 那条<b>不报</b>，本方法是<b>唯一</b>抓住它的地方。
     *       这正是 C6 的残留价值所在：模型写 {@code {{$.col}}} 时通常<b>会</b>把 {@code col}
     *       一起声明进 {@code variables[]}（它以为声明了就能到运行期取值），于是旧实现下
     *       这份 DSL 一路绿到发布。</li>
     * </ul>
     */
    private void validateSaveDraftColumnPlaceholders(NodeDTO node, String label, AgentValidateResult result) {
        Map<String, Object> args = node.configMap("args");
        Object rawFields = args == null ? null : args.get("fields");
        if (!(rawFields instanceof List<?> list)) {
            return;
        }
        List<String> placeholders = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> field)) {
                continue;
            }
            String column = columnNameOf(field);
            if (StrUtil.isBlank(column) || !isPlaceholder(column)) {
                continue;
            }
            placeholders.add(StrUtil.blankToDefault(strOf(field.get("field")), column) + " → " + column);
        }
        if (placeholders.isEmpty()) {
            return;
        }
        result.addError("节点(%s) 的 form_save_draft 把 %d 个字段的列名写成了占位符：%s。"
                        + "这一格嵌在 args.fields 数组里，运行期<b>不做</b>变量替换"
                        + "（只替换 args 的顶层字符串值，嵌在对象/数组里的原样透传），"
                        + "所以它会被当成字面列名写进字段映射：表单照样保存成功、照样发布成功，"
                        + "直到用户提交或打开列表才撞一句「Unknown column」。"
                        + "请直接写表里真实存在的列名 —— 想让列名到运行期才定，这个工具现在做不到",
                label, placeholders.size(), String.join("、", placeholders));
    }

    /**
     * 一个字段要落到的<b>物理列名</b>
     *
     * <p>🔴 口径逐字照 {@code FormSaveDraftTool#build} 的 {@code blankToDefault(field.column, field.field)}：
     * <b>column 缺省时用 field 顶</b>。两个消费者（列存在性、列名占位符）必须共用这一份，
     * 否则会出现「一边认这个列名、另一边认那个」——而那正是这条口径的注释里点名要防的事。
     */
    private static String columnNameOf(Map<?, ?> field) {
        return StrUtil.blankToDefault(strOf(field.get("column")), strOf(field.get("field")));
    }

    /**
     * 读一张已登记表的列名（小写、按库里的顺序）
     *
     * <p>🔴 口径逐字照 {@code FormFieldFactory#loadPhysicalSchema}：
     * {@code getDataModelList()} 按表名大小写不敏感匹配出 id，再 {@code getStructure(id)}。
     * 那边是「读了结构但不拿它判合法性」，这边是「读同一份结构来判合法性」——
     * 两处必须一起改，否则会出现「校验说列在、运行期 required 对齐读的是另一张表」。
     *
     * @return 列名集合；表<b>没登记进数据模型</b>（因而结构读不到）时返回 {@code null}，
     *         与「这张表一列都没有」严格区分开 —— 后者是该报 error 的
     */
    private Set<String> loadTableColumns(String mainTable) {
        DataModelApi.DataModelSimpleRespDTO model = dataModelApi.getDataModelList().stream()
                .filter(item -> item != null && mainTable.equalsIgnoreCase(item.getTableName()))
                .findFirst().orElse(null);
        if (model == null) {
            return null;
        }
        DataModelStructureRespDTO structure = dataModelApi.getStructure(model.getId());
        if (structure == null || structure.getColumns() == null) {
            return null;
        }
        Set<String> columns = new LinkedHashSet<>();
        for (DataModelStructureRespDTO.ColumnDTO column : structure.getColumns()) {
            if (column != null && StrUtil.isNotBlank(column.getColumnName())) {
                columns.add(column.getColumnName().trim().toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    // ==================== 同 DSL 建表节点声明的列（V30） ====================

    /**
     * 本流程<b>自己建</b>的那张表：它冻在 DSL 里的建表参数声明了哪些列
     *
     * @param columns  声明的<b>业务</b>列（小写）；系统列不在里面，由
     *                 {@link #systemColumnsOrAbort} 那一份名单补。
     *                 {@code null} = 有一个可达的 {@code datamodel_create}，而它的建表参数读不出来 ——
     *                 于是「这张表是不是它建的、建了哪些列」两个问题都<b>给不出结论</b>
     *                 （读不出参数就连表名都不知道，无从判断它建的是不是这一张）。
     *                 此时 {@link #creators} 无意义，调用方只看 {@code columns} 是否为 null
     * @param creators 声明了这张表的建表节点的对外称呼，报错时点名用
     */
    private record InDslTable(Set<String> columns, List<String> creators) {
    }

    /**
     * V30 一趟体检里的系统列名单缓存
     *
     * <p>名单取自 {@code DataModelApi#getTableConstraints()} —— 一次<b>不接受任何入参</b>的全局读，
     * 故障必然是持续性的，故按 {@link #warnProbeAborted} 那条纪律整趟停手、且只认领一次。
     * 缓存同时也是为了不把「每个 save_draft 节点探一遍」变成 N 次 infra 调用。
     *
     * <p>🔴 用「探到一份名单」而不是「探过」做缓存键（{@code columns != null} 才算探到）：
     * 反面写法会在读失败时把它留成<b>空集</b>，而空集在下面那个判据里恰好等于「一个系统列都没有」——
     * 于是一次 infra 抖动被翻译成一批「表单字段映射到不存在的列」的假 error，
     * 附送一个教模型去建 {@code create_time} 的假修法。
     * 与 {@link #validatePublishParentMenu} 里那条 {@code directories != null} 不变式同一个理由。
     *
     * <p>⚠️ 名单是<b>全部 profile 的并集</b>（{@code SystemColumnInjector#SYSTEM_COLUMNS} 就是这么定义的，
     * 因为 profile 不落库），所以 {@code version}（只 LEGACY 注入）与 {@code tenant_id}
     * （只 TENANT 注入）在任何 profile 下都会被放行。这是刻意选的那一侧：
     * 本条判的是「列不存在」，多放行一个系统列名只是少报一个极罕见的错，
     * 而少放行会把合法写法判成 error、白烧一轮自修正。
     */
    private static final class SystemColumnProbe {
        private Set<String> columns;
        private boolean aborted;
    }

    /**
     * 找出「会在 save_draft 之前建出 {@code mainTable}」的那个 datamodel_create 节点，读出它声明的列（V30）
     *
     * <h3>🔴 复现的事故：DDL 不可回滚，而失败发生在<b>最后一步</b></h3>
     * 实测（「帮我做一个访客来访登记…」）模型产的 DSL 是
     * widget_contract_get → datamodel_create(biz_visitor_registration，<b>7</b> 列)
     * → form_save_draft(<b>8</b> 个字段，多一个 {@code status}) → form_inspect → form_publish_bundle。
     * 体检<b>全绿</b>，运行期一路执行：表真建出来了（{@code SHOW COLUMNS} 数下来 7 个业务列 + 系统列，
     * 没有 status）、表单草稿也真存了，直到 {@code form_publish_bundle} 里那道发布关才报
     * 「表(biz_visitor_registration)中不存在列(status)」。
     * 用户拿到的是一张失败卡片、一张回滚不掉的物理表和一份发布不出去的草稿。
     *
     * <p>这与 {@link #validatePublishParentMenu}（V27）记的是同一类缺陷、同一条教训：
     * args 是规划期<b>写死</b>的，运行期照抄、不会再问模型；规划期不拦，
     * 就是把「自修正回路够不着的失败」原样甩给用户。差别只在 V27 要读库（启用目录清单），
     * 而本条<b>一个字都不用读库</b> —— 建表参数就在同一份 DSL 里。
     *
     * <h3>为什么原来那句「建漏了列由建表节点自己的参数负责」是空的</h3>
     * 它说的其实是「{@code datamodel_create} 的参数由 {@code DataModelCreateValidator} 负责」——
     * 那个校验器确实查了表名、列名规范、类型、索引，但它<b>只看一个节点的参数</b>，
     * 从来不知道另一个节点上有 8 个字段等着落进这 7 列。跨节点这件事<b>只有本类做得了</b>
     * （它手上同时有整份 {@code nodes} 与 {@code edges}）。
     *
     * <h3>⚠️ 三处刻意认不出，而「认不出」分两档</h3>
     * <ol>
     *   <li><b>{@code datamodel_import}</b>：它登记一张<b>既有</b>物理表，有哪些列静态看不见。
     *       而既有表本来就在库里，落回 {@link #mainTableExists} + {@link #validateSaveDraftColumns}
     *       正是对的判据 ⟹ 返回 {@code null}；</li>
     *   <li><b>建的是别的表</b>：一张表单配一张子表是常见编排，子表的建表节点不该拿来判主表的列。
     *       同样返回 {@code null} 落回查库 ⟹ 主表若真不存在，报的就是「数据库里没有这张表」那条（根因正确）；</li>
     *   <li><b>参数读不出来</b>（{@code model} 缺失或不是合法建表定义）：那是
     *       {@code datamodel_create} 自己的参数错，由它的干跑与运行期去报。
     *       🔴 这一档<b>不</b>落回查库：连表名都读不出来，就无从知道这个节点建的是不是这一张表，
     *       而落回去会报「数据库里没有这张表，请加一个 datamodel_create」—— DSL 里已经有一个了，
     *       那句话是假话，还会把模型推去叠第二个建表节点。
     *       所以返回一个 {@code columns == null} 的 {@link InDslTable}（两项都跳过），
     *       并补一句黄标认领「列核对跳过了」—— 静默跳过正是本类要消灭的那类缺陷。</li>
     * </ol>
     *
     * <h3>编号纪律（本条是 <b>V30</b>）</h3>
     * 见 {@link #validateChatLegsWriteReply} 规则头那段权威源。⚠️ 「下一个可用号是几」只在那里说，本条不抄。
     *
     * @return 认出了建表节点 ⟹ 它声明的业务列与节点称呼；
     *         有可达的建表节点而参数读不出来 ⟹ {@code columns == null} 的实例（调用方两项都跳过）；
     *         <b>没有一个建表节点认下这张表</b> ⟹ {@code null}（调用方落回查库那一路）
     */
    private InDslTable declaredTableOfInDslCreator(String mainTable, List<NodeDTO> nodes,
                                                   GraphReachability.Graph graph, String saveDraftId,
                                                   String label, AgentValidateResult result) {
        Set<String> columns = null;
        List<String> creators = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO creator = nodes.get(i);
            if (creator == null || !"tool".equals(creator.getType())) {
                continue;
            }
            // 🔴 与上面那句 reachesAny 同一个 NPE 陷阱：先取出来判空，再交给不可变集合 contains
            String toolId = creator.configStr("toolId");
            if (toolId == null || !TABLE_CREATOR_TOOL_IDS.contains(toolId)
                    || !graph.reaches(creator.getId(), saveDraftId)) {
                continue;
            }
            if (!DataModelCreateTool.NAME.equals(toolId)) {
                continue;   // datamodel_import：见规则头那三处的第 ①
            }
            DataModelSaveReqDTO model = declaredModelOf(creator);
            if (model == null) {
                unreadable.add(displayId(creator.getId(), i));
                continue;
            }
            if (!mainTable.equalsIgnoreCase(StrUtil.trimToEmpty(model.getTableName()))) {
                continue;   // 建的是别的表：见规则头那三处的第 ②
            }
            if (columns == null) {
                columns = new LinkedHashSet<>();
            }
            creators.add(displayId(creator.getId(), i));
            for (DataModelSaveReqDTO.ColumnDTO column : model.getColumns() == null
                    ? List.<DataModelSaveReqDTO.ColumnDTO>of() : model.getColumns()) {
                if (column != null && StrUtil.isNotBlank(column.getColumnName())) {
                    columns.add(column.getColumnName().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!unreadable.isEmpty()) {
            // 见规则头那三处的第 ③。⚠️ 与「认出来了」并不互斥：同一份 DSL 里可能既有读得出来的
            //    建表节点、也有读不出来的，故这句一律照实认领，不因为 columns != null 就闭嘴
            result.addWarning("节点(%s) 的 form_save_draft 主表 %s 的列核对只做了一半："
                            + "本流程里 %d 个 datamodel_create 节点的建表参数读不出来（%s），"
                            + "它们声明的列没能参与核对。读不出来本身是那些节点的参数错，"
                            + "由建表那一项去报",
                    label, mainTable, unreadable.size(), String.join("、", unreadable));
        }
        if (columns != null) {
            return new InDslTable(columns, creators);
        }
        // 🔴 「参数读不出来」与「没有一个建表节点声明这张表」必须分成两种返回值，不能都返回 null：
        //    前者连<b>表名</b>都不知道，这张表完全可能就是它建的 ⟹ 调用方据此两项都跳过；
        //    后者是真的没人认下这张表 ⟹ 落回查库那一路才是对的判据。
        //    合并成一个 null 的表现是一句假 error：「数据库里没有这张表，请加一个 datamodel_create」——
        //    而 DSL 里已经有一个了
        return unreadable.isEmpty() ? null : new InDslTable(null, unreadable);
    }

    /**
     * 一个 datamodel_create 节点冻在 args 里的建表定义；读不出来返回 {@code null}
     *
     * <p>🔴 借运行期<b>同一个</b>解析器（{@code DataModelParamResolver#resolveCreate}），
     * 不在这里重写一遍 {@code JSONUtil.parseObj(model)}。那一份同时认「{@code model} 是对象」
     * 与「{@code model} 是模型序列化出来的 JSON 串」两种形态，只认前者的后果
     * {@code WorkflowCopyRewriter#rewriteDataModelCreate} 的注释里记着：
     * 同一份 DSL 在执行期能跑通、在这一侧却被当成「读不出来」。
     *
     * <p>⚠️ 它抛异常时<b>不当故障、当结论</b>（返回 {@code null}）：抛的是
     * {@code AGENT_TOOL_PARAM_INVALID}「model 不是合法的建表定义」，那是这份 DSL 自己的错，
     * 不是探测环境坏了 —— 与 {@link #mainTableExists} 那个 catch（infra 抖动）不是一类，
     * 故不走 {@link #warnProbeAborted}。
     */
    private DataModelSaveReqDTO declaredModelOf(NodeDTO creator) {
        Map<String, Object> args = creator.configMap("args");
        if (args == null || args.get(DataModelParamResolver.PARAM_MODEL) == null) {
            return null;
        }
        try {
            return dataModelParamResolver.resolveCreate(args, DataModelCreateTool.NAME);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * form_save_draft 的每个字段，都要能在「本流程自己建的那张表」上找到落点（V30）
     *
     * <p>判据 = 建表节点声明的业务列 ∪ 平台注入的系统列。两半都不是这里发明的：
     * 前者取自同一份 DSL 的 {@code datamodel_create.args.model.columns}，
     * 后者取自 {@code DataModelApi#getTableConstraints()}（infra 侧的
     * {@code SystemColumnInjector#SYSTEM_COLUMNS}，javadoc 明写「规则只有一份，在这边；agent 读过去用」）。
     * 系统列那一半<b>必须</b>并进来：建表参数里写系统列会被 {@code DataModelCreateValidator} 直接拦掉
     * （「与系统列同名，禁止使用」），但表单字段<b>可以</b>映射它们（如把创建时间显示出来），
     * 不并的话每个映射 {@code create_time} 的字段都是一条假 error。
     *
     * <p>字段 → 列名的口径与 {@link #validateSaveDraftColumns} 共用 {@link #wantedColumnsOf}，
     * 理由写在那里。
     */
    private void validateSaveDraftColumnsAgainstDeclaredColumns(NodeDTO node, String label, String mainTable,
                                                                InDslTable declared,
                                                                SystemColumnProbe probe,
                                                                AgentValidateResult result) {
        Map<String, String> wanted = wantedColumnsOf(node);
        if (wanted.isEmpty()) {
            return;
        }
        Set<String> systemColumns = systemColumnsOrAbort(probe, label, result);
        if (systemColumns == null) {
            return;   // 名单读不到 ⟹ 判据缺一半，不能拿它去报 error（黄标已由上面那句认领）
        }
        Set<String> allowed = new LinkedHashSet<>(declared.columns());
        allowed.addAll(systemColumns);
        List<String> missing = missingColumns(wanted, allowed);
        if (missing.isEmpty()) {
            return;
        }
        result.addError("节点(%s) 的 form_save_draft 把 %d 个字段映射到主表 %s 上没有的列：%s。"
                        + "这张表由本流程的建表节点（%s）建，它声明的业务列只有：%s"
                        + "（%s 这些系统列由平台自动补，不用写进建表参数）。"
                        + "🔴 建表在前、DDL 不可回滚：真跑起来会先把表建出来、把表单草稿存进去，"
                        + "一路成功到最后一步发布才报「表中不存在列」，"
                        + "留给用户的是一张删不掉的半成品表和一份发布不出去的草稿。"
                        + "请把缺的列补进<b>同一个</b> datamodel_create 节点的 columns（列名要与这里写的完全一致），"
                        + "或把这些字段从 form_save_draft 的 fields 里去掉",
                label, missing.size(), mainTable, String.join("、", missing),
                String.join("、", declared.creators()),
                declared.columns().isEmpty() ? "（一个业务列都没声明）" : String.join(", ", declared.columns()),
                String.join(", ", systemColumns));
    }

    /**
     * 系统列名单：一趟体检里只探一次，探不动就整趟停手
     *
     * @return 小写的系统列名；探不到时返回 {@code null}（黄标已在本方法里认领过一次，
     *         后续节点据此静默跳过 —— 同一次故障只许认领一次）
     */
    private Set<String> systemColumnsOrAbort(SystemColumnProbe probe, String label,
                                             AgentValidateResult result) {
        if (probe.aborted) {
            return null;
        }
        if (probe.columns != null) {
            return probe.columns;
        }
        List<String> names;
        try {
            DataModelApi.TableConstraintsRespDTO constraints = dataModelApi.getTableConstraints();
            names = constraints == null ? null : constraints.getSystemColumnNames();
        } catch (Exception ex) {
            names = null;
            probe.aborted = true;
            warnProbeAborted(result, "同 DSL 建表列核对", "读建表约束", ex,
                    String.format("本次体检中其余「本流程自己建表」的 form_save_draft 节点的列核对全部未校验"
                            + "（停在节点(%s)）", label));
            return null;
        }
        if (names == null) {
            // 读到了约束但没有名单：与「这张表一个系统列都没有」无法区分，
            // 按探测失败处理（理由同 SystemColumnProbe 那条不变式）
            probe.aborted = true;
            result.addWarning("同 DSL 建表列核对中止（读到的建表约束里没有系统列名单）："
                    + "本次体检中「本流程自己建表」的 form_save_draft 节点的列核对全部未校验。"
                    + "这一项给不出结论不等于它没问题");
            return null;
        }
        Set<String> read = new LinkedHashSet<>();
        for (String name : names) {
            if (StrUtil.isNotBlank(name)) {
                read.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        probe.columns = read;
        return read;
    }

    // ==================== 平台引用（V7/V8/V10） ====================

    /**
     * {@code hitl.bpm_approval.bpmProcessDefKey} 必须指向一个<b>已部署</b>的流程（V7）
     *
     * <h3>🔴 这条为什么不能像 V4/V5/V12 那样折叠干跑结论</h3>
     * {@code WorkflowChangeManifestBuilder#build} 把 {@code hitl.*} 节点放进的是
     * {@code approvals[]}，不是 {@code changes[]} —— 干跑器只探 {@code changes[]}，
     * 于是清单里根本不存在一条属于审批节点的 {@code REJECTED} 可折。
     *
     * <h3>为什么运行期那道闸不够</h3>
     * {@code AgentBpmApprovalService#createApproval} 把 key 原样交给
     * {@code createProcessInstance}，key 不对时的失败点是<b>流程已经跑到审批那一步</b>：
     * 前面的建表、存草稿都真做了、token 也烧了，用户拿到的是一次半途而废的执行。
     *
     * <h3>⚠️ 本流程自己会部署的，不要求「此刻已部署」</h3>
     * 「建表 → 建表单 → 部署审批流 → 发起审批」是一条合法的单流程编排。
     * 判定与 {@link #validateSaveDraftMainTable} 对建表节点的处理同口径：
     * 存在 {@code process_create_and_deploy} 节点且沿 edges 能走到审批节点就跳过，
     * <b>不解析</b>那个节点的参数（部署时 key 写错由它自己的干跑与运行期兜）。
     *
     * <p>🔴 但这句话只对<b>本条判的那一格</b>（{@code bpmProcessDefKey}）成立，别拿它当整条链的通行证：
     * 流程标识在运行期部署完就能发起，中间不隔任何「要已发布」的判据；
     * 而 {@code process_create_and_deploy} 绑定的<b>在线表单</b>隔着 ——
     * 它要「已发布 + 主表有 process_instance_id 与 process_status 两列」，本流程自己建的表单给不出，
     * 于是那条链在「部署审批流」这一步是断的。见 {@link #validateProcessDeployFormSource}（V31）。
     *
     * <p>⚠️ key 是<b>字面量</b>：{@code NodeIoContract} 里 {@code hitl.bpm_approval.bpmProcessDefKey} 把它登记为 {@code MODE_LITERAL}，
     * {@code AgentBpmApprovalService#resolveConfig} 也是 {@code configStr} 直取、不做变量替换。
     * 写 {@code {{$.x}}} 会被原样当成流程标识去发起，故单独给一条文案 ——
     * 让它落到「查不到这个流程」那句上会把模型往「换个 key」引，而它该改的是「别用占位符」。
     */
    private void validateBpmApprovalRefs(WorkflowDefDTO def, GraphReachability.Graph graph,
                                         AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null) {
            return;
        }
        // 🔴 数一遍而不只是 noneMatch：探测中止时那句 warning 要能说出「几个节点没校验」。
        //    这里是一次全局批量读（getModelList(null) 不接受节点参数），故故障必然持续，
        //    停手是对的 —— 但「bpmProcessDefKey 未校验」这种单数说法会让人以为只漏了一处
        long approvalCount = nodes.stream()
                .filter(node -> node != null && BPM_APPROVAL_TYPE.equals(node.getType())).count();
        if (approvalCount == 0) {
            return;
        }
        Set<String> deployerIds = new HashSet<>();
        for (NodeDTO node : nodes) {
            // 🔴 先取出来再判空：与 validateSaveDraftMainTable 里那条同理由，
            //    toolId 缺失是 LLM 产 DSL 的常见形状，不能让 Set.of(...).contains(null) 把体检打成 500
            String toolId = node == null ? null : node.configStr("toolId");
            if (toolId != null && PROCESS_DEPLOYER_TOOL_ID.equals(toolId)) {
                deployerIds.add(node.getId());
            }
        }
        List<BpmModelRespDTO> models;
        try {
            // 传 null = 不按名称过滤：这里要匹配的是流程<b>标识</b>（key），
            // 而那个入参过滤的是流程<b>名称</b>，拿 key 去当名称过滤只会漏
            models = bpmModelApi.getModelList(null);
        } catch (Exception ex) {
            warnProbeAborted(result, "BPM 流程校验", "查流程模型", ex,
                    String.format("本次体检中 %d 个 hitl.bpm_approval 节点的 bpmProcessDefKey 全部未校验",
                            approvalCount));
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !BPM_APPROVAL_TYPE.equals(node.getType())) {
                continue;
            }
            String label = displayId(node.getId(), i);
            String key = node.configStr("bpmProcessDefKey");
            if (StrUtil.isBlank(key)) {
                result.addError("节点(%s) 的 hitl.bpm_approval 缺少 bpmProcessDefKey："
                                + "请填一个已部署流程的流程标识（如 oa_leave）。"
                                + "不填的话运行期走到这一步会直接抛「hitl.bpm_approval 节点缺少 bpmProcessDefKey」",
                        label);
                continue;
            }
            if (isPlaceholder(key)) {
                result.addError("节点(%s) 的 bpmProcessDefKey 写成了 %s，但这一格是字面量、运行期不做变量替换，"
                                + "占位符会被原样当成流程标识去发起审批。请直接写流程标识",
                        label, key);
                continue;
            }
            if (!deployerIds.isEmpty() && graph.reachesAny(deployerIds, node.getId())) {
                continue;
            }
            BpmModelRespDTO model = models == null ? null : models.stream()
                    .filter(item -> item != null && key.equals(item.getKey()))
                    .findFirst().orElse(null);
            if (model == null) {
                result.addError("节点(%s) 的 bpmProcessDefKey「%s」在 BPM 里查不到对应的流程模型。"
                                + "请改用一个已存在的流程标识，"
                                + "或在本节点之前加一个 process_create_and_deploy 节点把它建出来并部署",
                        label, key);
            } else if (!Boolean.TRUE.equals(model.getDeployed())) {
                result.addError("节点(%s) 的 bpmProcessDefKey「%s」（流程名「%s」）建好了但从未部署："
                                + "未部署的流程发不起实例，也不会出现在用户的发起列表里，"
                                + "运行期走到这一步必然失败。请先在 BPM 里部署它",
                        label, key, model.getName());
            }
        }
    }

    // ==================== 审批流绑的在线表单是本流程自己建的（V31） ====================

    /**
     * {@code process_create_and_deploy} 绑的在线表单，不能是<b>本流程自己建</b>的那一张（V31）
     *
     * <h3>🔴 复现的事故：规划期全绿，发布那一刻才拒，而拒绝的原因被说反了</h3>
     * 实测（「帮我搭一个设备报修登记流程…提交后先由设备主管确认要不要修」）模型产的 DSL 是
     * datamodel_list → script → datamodel_create(biz_device_repair)
     * → form_save_draft(code=device_repair_register) → script
     * → process_create_and_deploy(formDesignCode=device_repair_register) → llm。
     * 规划期 <b>2 轮收敛、warnings 为空</b>；用户点发布，拿到的是
     * 「在线表单 device_repair_register 不可用于流程：表单(device_repair_register)不存在，
     * 可能已被删除」（{@code FormDesignApiImpl#validateFlowReady} 的原话）。
     * 🔴 那句把原因说<b>反</b>了：没有人删过它，它只是<b>还没被建出来</b> ——
     * 建它的那个节点就在这份 DSL 里，而发布体检是对平台<b>此刻</b>的只读快照
     * （{@code ChangeManifestDryRunner#checkMainForm} 调的就是上面那个方法），那一刻它必然不存在。
     * 用户与模型都被这句话引向一次从未发生的删除，而真正该改的是「别把审批流绑在这张表单上」。
     *
     * <h3>🔴 为什么这一档是「怎么排都发布不了」，而不是「把发布那一步排到前面就行」</h3>
     * 因为发布体检跑在<b>发布那一刻</b>，读的是平台当前状态。本流程将建的表单在那一刻还不存在，
     * 与流程里有没有 {@code form_publish_bundle}、排在哪一步<b>全都无关</b> —— 那些节点要到运行期才跑。
     * 所以只要 {@code formDesignCode} 是个字面量、而它由本流程内某个 {@code form_save_draft} 声明，
     * 这份定义就<b>永远发布不出去</b>。
     *
     * <p>判据的这一半靠一个事实撑住：{@link FormSaveDraftTool} 是<b>只建不改</b>的 ——
     * 编码被占用时它直接抛（{@code FormSaveDraftTool#codeTakenMessage}），不会去改那张既有表单。
     * 于是「本流程里有一个 save_draft 声明了这个编码」就等价于「这个编码此刻还不存在」。
     *
     * <h3>⚠️ 编码写成<b>占位符</b>的那一档，本条刻意不判</h3>
     * 写 {@code {{$.save_form.code}}} 时发布体检落 {@code NOT_PROBED}（{@code ManifestArgs#isPending}）、
     * 发布得过，而运行期部署那一步调的是<b>同一个</b> {@code validateFlowReady}：
     * {@code form_save_draft} 只建<b>草稿</b>，而它的第一条判据就是「必须已发布」
     * （{@code FormDesignApiImpl#resolveUnavailableReason}），于是照样被拒 ——
     * 且那时表已经建出来了，DDL 回滚不掉。这一档<b>更坏</b>，但本条不判它，理由是它<b>不是</b>死路：
     * 「建表时带上 process_instance_id 与 process_status 两列 → save_draft → form_publish_bundle
     * → 部署审批流」这条编排是真能跑通的，静态判它要同时证成「中间有一步真发布了」与
     * 「那两列真在建表参数里」，误判的代价是一条合法流程被拒。
     * 🔴 但别把模型留在那一档里踩坑：{@code ProcessCreateAndDeployTool#paramsSchema} 原先写着
     * 「formDesignCode…来自 form_save_draft 的结果」，正是把它往占位符那一档引的那句话，已随本条一并改掉。
     *
     * <h3>⚠️ 与 V7（{@link #validateBpmApprovalRefs}）结论相反，两者都对</h3>
     * V7 对 {@code bpmProcessDefKey} 明写「本流程自己会部署的，不要求此刻已部署」，那条<b>仍然成立</b>：
     * 流程标识在运行期部署完就能发起，中间不隔任何「要已发布」的判据。
     * 本条判的是<b>另一格</b>（绑定的在线表单），它隔着的恰恰是那一判据。
     * 而 V7 javadoc 里那句「建表 → 建表单 → 部署审批流 → 发起审批 是一条合法的单流程编排」
     * 对<b>它判的那一格</b>成立、对整条链不成立，已就地限定，别拿它当本条的反例。
     *
     * <h3>⚠️ 刻意不看可达性</h3>
     * 不要求那个 {@code form_save_draft} 沿 edges 能走到部署节点：发布体检拒的是「此刻不存在」，
     * 这个结论与两个节点之间有没有边<b>无关</b>。save_draft 挂在一个不可达分支上时，
     * 那张表单<b>永远</b>建不出来，绑定它照样是错的（那个分支本身由
     * {@link #validateUnreachableNodes} 另行点名）。加一道可达性只会让本条在该开口时闭嘴。
     *
     * <h3>编号纪律（本条是 <b>V31</b>）</h3>
     * 见 {@link #validateChatLegsWriteReply} 规则头那段权威源。⚠️ 「下一个可用号是几」只在那里说，本条不抄。
     */
    private void validateProcessDeployFormSource(WorkflowDefDTO def, AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null) {
            return;
        }
        // 本流程里 form_save_draft 声明的表单编码（小写）-> 节点称呼。
        // 同一个编码被两个节点声明时把称呼都留着：那本身就是错的，报错时一起点名，不替作者挑一个
        Map<String, List<String>> createdHere = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !"tool".equals(node.getType())
                    || !SAVE_DRAFT_TOOL_ID.equals(node.configStr("toolId"))) {
                continue;
            }
            Map<String, Object> args = node.configMap("args");
            Object code = args == null ? null : args.get(FormSaveDraftTool.PARAM_CODE);
            // 🔴 只认字面量：编码是占位符时「它建的是哪一张」静态给不出结论 ——
            //    而那正是规则头里刻意不判的那一档，此处一并跳过
            if (!(code instanceof String literal) || StrUtil.isBlank(literal) || isPlaceholder(literal)) {
                continue;
            }
            createdHere.computeIfAbsent(literal.trim().toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                    .add(displayId(node.getId(), i));
        }
        if (createdHere.isEmpty()) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !"tool".equals(node.getType())
                    || !PROCESS_DEPLOYER_TOOL_ID.equals(node.configStr("toolId"))) {
                continue;
            }
            Map<String, Object> args = node.configMap("args");
            Object bound = args == null ? null : args.get(ProcessCreateAndDeployTool.PARAM_FORM_DESIGN_CODE);
            // 没绑在线表单（走 formId 那一档）、或这一格空着：都不是本条判的形状，
            // 由它自己的参数校验与干跑说。
            // ⚠️ 这一行【不】负责挡占位符，别读成它挡了：绑定侧写成占位符时，
            //    它匹配不上 createdHere 里任何字面量编码，下一行 creators == null 自然跳过 ——
            //    规则头「刻意不判」那一档是靠「按编码相等来匹配」落地的，不是靠这里再加一道判断。
            //    真正显式跳过占位符的是上面创建侧那个 isPlaceholder（那里非跳不可：
            //    否则 {{$.x.code}} 会当成一个字面量编码进表，凭空多出永不匹配的键）
            if (!(bound instanceof String literal) || StrUtil.isBlank(literal)) {
                continue;
            }
            List<String> creators = createdHere.get(literal.trim().toLowerCase(Locale.ROOT));
            if (creators == null) {
                continue;
            }
            result.addError("节点(%s) 的 process_create_and_deploy 把在线表单绑在「%s」上，"
                            + "而这张表单是<b>本流程自己建的</b>（由 %s 建）—— 这么编排<b>永远发布不了</b>："
                            + "发布体检是对平台<b>此刻</b>的只读快照，那一刻它还不存在，"
                            + "报出来的还是「表单不存在，可能已被删除」这句把原因说反的话"
                            + "（没人删过它，建它的那个节点就在本流程里，只是要到运行期才跑）。"
                            + "把 form_publish_bundle 排到它前面也没用 —— 那些节点同样要到运行期才跑。"
                            + "两条出路：①别为了拿一个人工闸口现造 BPM 流程："
                            + "会话触发用 hitl.confirm，无头触发用 hitl.bpm_approval 绑一个用户<b>点名的既有</b>流程标识；"
                            + "②用户确实要走一条新审批流时，把两件事拆开 —— 先让这张表单在【表单设计】里建好并发布"
                            + "（主表要有 process_instance_id（STRING，长度不小于 64）"
                            + "与 process_status（整数型：TINYINT/INT/LONG）两列 —— 类型写错不报错，"
                            + "只会让这张表单在流程那一步被标成「不可用」），"
                            + "本流程只负责发起审批，不负责建表单",
                    displayId(node.getId(), i), literal, String.join("、", creators));
        }
    }

    /**
     * {@code llm} / {@code condition.llm} 的 {@code modelId} 必须可用（V8）
     *
     * <h3>⚠️ 留空<b>不</b>报 —— 这是本条最容易写错的一半</h3>
     * {@code LlmNodeExecutor#execute} 的注释写得很清楚：modelId 留空 = <b>动态默认 + 故障切换</b>
     * （api 层按「启用 + 密钥启用 + sort 最前」选，不可用自动切下一个候选）。
     * 那比钉死一个 id 更健壮，把它报成问题等于逼模型往差的那种写法上改。
     *
     * <h3>为什么「查不到这个模型」只是 warning</h3>
     * 模型可能被删、被停用、密钥可能过期，但那是<b>运维状态</b>，不是这份 DSL 写错了；
     * 而且删掉这一格就能绕过，阻断发布没有意义（方案 §3-L3 对 V8/V9 定的同一档）。
     *
     * <h3>🔴 但「modelId 不是个数字」是 error</h3>
     * 两个执行器都是 {@code node.configLong("modelId")}，而 {@code configLong} 对非数字直接
     * {@code Long.parseLong} 抛 {@code NumberFormatException} —— 运行期<b>必炸</b>，
     * 与「模型可能被删」不是一回事。写 {@code {{$.x}}} 也一样炸：这一格不走
     * {@code StateTemplateResolver}（只有 prompt/systemPrompt 走），占位符不会被替换。
     *
     * <p>也正因如此，本方法读的是 {@code configStr} 而<b>不是</b> {@code configLong}：
     * 用后者会让「modelId 写成了占位符」这份 DSL 把体检端点自己打成 500 ——
     * 用户既看不到错、也发不出去，与 {@code Set.of(...).contains(null)} 是同一类雷。
     */
    private void validateLlmModelRefs(WorkflowDefDTO def, AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null) {
            return;
        }
        // 先扫一遍：一个钉了 modelId 的都没有，就一次库都不查（留空是合法且更健壮的写法）
        // 🔴 数一遍而不只是 anyMatch：与 validateBpmApprovalRefs 同理由，探测中止时要报得出真实范围
        long pinnedCount = nodes.stream().filter(node -> node != null
                && isLlmNodeType(node.getType()) && StrUtil.isNotBlank(node.configStr("modelId"))).count();
        if (pinnedCount == 0) {
            return;
        }
        Set<Long> available;
        try {
            available = new HashSet<>();
            for (AiModelApi.AiModelSimpleRespDTO model : aiModelApi.getEnabledModelList(AI_MODEL_TYPE_CHAT)) {
                if (model != null && model.getId() != null) {
                    available.add(model.getId());
                }
            }
        } catch (Exception ex) {
            warnProbeAborted(result, "模型可用性校验", "查可用模型", ex,
                    String.format("本次体检中 %d 个钉了 modelId 的节点全部未校验", pinnedCount));
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !isLlmNodeType(node.getType())) {
                continue;
            }
            String raw = node.configStr("modelId");
            if (StrUtil.isBlank(raw)) {
                continue;
            }
            String label = displayId(node.getId(), i);
            Long modelId;
            try {
                modelId = Long.valueOf(raw.trim());
            } catch (NumberFormatException ex) {
                result.addError("节点(%s) 的 modelId 写成了「%s」，但运行期是按数字读这一格的、且不做变量替换，"
                                + "执行到这一步会直接抛 NumberFormatException。"
                                + "请填一个模型编号（数字），或把这一格整个删掉、改用平台的动态默认模型",
                        label, raw);
                continue;
            }
            if (!available.contains(modelId)) {
                result.addWarning("节点(%s) 的 modelId %d 不在当前可用的聊天模型里"
                                + "（模型被删/停用，或它的密钥被停用），运行期会失败。"
                                + "请改成一个可用的模型编号，或把这一格整个删掉、改用平台的动态默认模型",
                        label, modelId);
            }
        }
    }

    /**
     * {@code sub_workflow.workflowId} 必须能取到一个可执行的子流程定义（V10）
     *
     * <h3>与编译器那道闸是什么关系</h3>
     * {@code DslToStateGraphCompiler#addSubWorkflowNode} 调的正是同一个
     * {@code getSubWorkflowDef}，而 {@code dryRunValidate} 复用 {@code buildStateGraph}，
     * 所以深层那层<b>今天就已经报得出来</b>。这里再报一遍的理由与 {@link #validateToolRefs} 那条一样：
     * 深层是 fail-fast 的，两个子流程都填错时模型只能一轮改一个，
     * 而且它得先把第一棵子图整棵建起来才轮得到第二个。
     *
     * <h3>🔴 用 {@code getSubWorkflowDef} 而不是 {@code getPublishedByCode}</h3>
     * 前者才是运行期真正走的那一个，且它多管一件事：pin 了 {@code version}
     * 而那一版还是草稿时同样判「不可执行」。用后者就会漏掉这种「code 对、版本不对」的形状，
     * 给出一次假绿灯。
     *
     * <p>⚠️ 键名叫 {@code workflowId}、装的其实是子流程的 <b>code</b>
     * （{@code WorkflowChangeManifestBuilder} 的同名常量记着同一件事）。
     */
    private void validateSubWorkflowRefs(WorkflowDefDTO def, AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !SUB_WORKFLOW_TYPE.equals(node.getType())) {
                continue;
            }
            String label = displayId(node.getId(), i);
            String code = node.configStr("workflowId");
            if (StrUtil.isBlank(code)) {
                result.addError("节点(%s) 的 sub_workflow 缺少 workflowId：请填子流程的 code"
                        + "（键名叫 workflowId，装的是 agent_workflow_def.code，不是数字编号）", label);
                continue;
            }
            if (isPlaceholder(code)) {
                result.addError("节点(%s) 的 workflowId 写成了 %s，但这一格运行期不做变量替换，"
                        + "占位符会被原样当成子流程 code 去查。请直接写 code", label, code);
                continue;
            }
            if (code.equals(def.getId())) {
                result.addError("节点(%s) 的 sub_workflow 引用了它自己（%s）：子流程不能是本流程，"
                        + "编译期会以「检测到循环引用」拒绝。请改引用另一个已发布的流程", label, code);
                continue;
            }
            try {
                // 🔴 version 先安全转一遍再传：configInt 对非数字直接 Integer.parseInt 抛
                //    NumberFormatException，会把体检端点自己打成 500。填错 version 那一格由深层干跑报
                //    （编译器 :735 调的正是 configInt，它收得住），这里只当「没 pin 版本」查
                defService.getSubWorkflowDef(code, intOrNull(node.configStr("version")));
            } catch (ServiceException ex) {
                result.addError("节点(%s) 的子流程用不了：%s。请改成一个已发布的流程 code"
                        + "（草稿与停用都不算），或先把那个流程发布掉", label, ex.getMessage());
            } catch (Exception ex) {
                result.addWarning("子流程校验跳过（查 %s 失败：%s）", code, ex.getMessage());
            }
        }
    }

    // ==================== 触发器 × 人工闸口（V11/V13/V14） ====================

    /**
     * 按触发器分型的「不可逆动作上游必须有人工闸口」（V11）
     *
     * <h3>🔴 为什么必须分型，而不是一刀切「含 IRREVERSIBLE 就要 hitl」</h3>
     * 一刀切在 fail-close（「error 一律拒」）下<b>不可满足</b>：无头流程里唯一能被唤醒的人工闸口是
     * {@code hitl.bpm_approval}（走 BPM 待办，有承办人、有待办列表），而 {@code hitl.confirm}
     * 推的是聊天确认卡 —— 定时任务跑起来时没有聊天窗口，卡推给谁？于是「无头 + 不可逆」这个组合
     * 只有<b>一条</b>合法写法。规则不先把这件事说出来，就会把所有 schedule/webhook/form_event
     * 流程一律挡在发布外，用户得到的提示还是「请加人工确认」——照做，然后继续被拒。
     *
     * <h3>🔴 风险级读<b>声明</b>值（D11）</h3>
     * 走 {@link ToolResolver#declaredRiskOf}，禁止 {@code getRisk()}：后者是租户<b>生效</b>值，
     * {@code agent_tool_override} 一行数据就能把 {@code datamodel_create} 从 IRREVERSIBLE 改写成
     * READ_ONLY，本方法于是看不见任何不可逆工具、直接放行「定时触发 → 无人值守建表」。
     * 整个分型矩阵会被一行数据静默击败，而界面上什么都看不出来。
     *
     * <h3>为什么 error 逐个节点报、warning 却合成一条</h3>
     * error 是「这一处必须改」，每处改法不同（换工具还是加闸口、加在哪），逐个点名模型才能一轮改对；
     * warning 是「建议加个人工把关」，一条流程里 10 个写操作节点会刷出 10 句一模一样的建议 ——
     * 那不是提醒，是在教读者「这个黄标不用看」（与本类不折 {@code NOT_PROBED} 是同一条理由）。
     * 故合并成一条，带上处数、最高风险级与前几处的位置。
     *
     * <h3>两处刻意够不着</h3>
     * <ul>
     *   <li>{@code agent_loop.tools[]} 里的工具不参与本判定：V3 已经把那一格限死为<b>只读</b>，
     *       而只读档在矩阵里本就「无要求」。</li>
     *   <li>{@code sub_workflow} 里的工具也不参与：子流程是<b>另一份 DSL、有自己的触发器</b>，
     *       发布它的时候会各自判一遍。在这里替它判，就得假设它的触发器与本流程相同 ——
     *       而「无头父流程调一个 chat 子流程」与反过来的合法写法完全不同。</li>
     * </ul>
     *
     * <h3>🔴 没有 trigger 块时本条整个不适用（不是「按 chat 那一行判」）</h3>
     * 矩阵的行标题就是<b>触发器</b>：{@code chat} 一行、三种无头一行，{@code none} 没有行。
     * 这不是偷懒，是因为那一格的事实完全不同 —— 没有 trigger 的定义不会被任何自动入口拉起来，
     * 只能由人从编辑器/执行历史点 {@code run-stream}，而那条路径上<b>本来就有人在看</b>
     * （S3 的 {@code WORKFLOW_CONFIRM} 事件正是为它建的），再要求 DSL 里自带一个闸口是重复设防。
     * 反过来，作者一旦给它配上触发器，本条立刻按对应那一行生效 —— 判定的输入变了，结论就该跟着变。
     *
     * <p>⚠️ 与 {@link #isHeadlessTrigger} 对 {@code null} 的处理方向一致但理由不同：
     * 那里 {@code null} 返回 false 是因为 V13/V14 问的是「无头吗」，答案确实是「不是」；
     * 这里 {@code null} 直接 return 是因为 V11 问的是「该按哪一行判」，答案是「没有这一行」。
     *
     * <p>⚠️ 本方法只判「上游有没有闸口」，<b>不判闸口本身能不能用</b>：
     * 「无头流程里放了 {@code hitl.confirm}」由 {@link #validateHeadlessHitlConfirm}（V13）报，
     * 「{@code hitl.bpm_approval} 绑的流程没部署」由 {@link #validateBpmApprovalRefs}（V7）报。
     * 三条各说一件事，不互相代劳 —— 否则一条 error 里塞三个根因，模型改一个还红着两个。
     */
    private void validateTriggerRiskGates(WorkflowDefDTO def, GraphReachability.Graph graph,
                                          ToolResolver tools, AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        String triggerType = triggerTypeOf(def);
        if (triggerType == null) {
            return;
        }
        // 先把闸口按「本触发器下唤不唤得醒」分成两堆，避免每个工具节点都重扫一遍 nodes。
        // bpm_approval 进两堆（有无头都能唤醒），confirm 只进「有人在场」那堆 —— 这正是 V13 的判定依据
        Set<String> attendedGates = new LinkedHashSet<>();
        Set<String> headlessGates = new LinkedHashSet<>();
        for (NodeDTO node : nodes) {
            if (node == null || StrUtil.isBlank(node.getId())) {
                continue;
            }
            if (BPM_APPROVAL_TYPE.equals(node.getType())) {
                headlessGates.add(node.getId());
                attendedGates.add(node.getId());
            } else if (HITL_CONFIRM_TYPE.equals(node.getType())) {
                attendedGates.add(node.getId());
            }
        }
        boolean headless = isHeadlessTrigger(triggerType);
        Set<String> usableGates = headless ? headlessGates : attendedGates;

        List<String> unguardedWrites = new ArrayList<>();
        RiskLevel unguardedMax = null;
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !"tool".equals(node.getType())) {
                continue;
            }
            String toolId = node.configStr("toolId");
            if (StrUtil.isBlank(toolId)) {
                // 空 toolId 由 V1 报；这里连它是只读还是不可逆都判不出来，没什么可说的
                continue;
            }
            RiskLevel declared = tools.declaredRiskOf(toolId);
            if (declared == RiskLevel.READ_ONLY) {
                continue;
            }
            // 节点 id 不合法时 ancestorsOf 回空集 ⟹ 判成「无闸口」。刻意不跳过：
            // 本类的消费者是自修正回路，一次把「id 不合法」和「不可逆动作没人把关」都列出来，
            // 模型一轮改对；跳过就等于让它改完 id 再撞一次、白烧一轮真实计费
            Set<String> upstream = graph.ancestorsOf(node.getId());
            if (containsAny(upstream, usableGates)) {
                continue;
            }
            String label = displayId(node.getId(), i);
            if (declared == RiskLevel.IRREVERSIBLE) {
                result.addError("节点(%s) 的工具「%s」声明风险是「%s」，但它的上游没有人工闸口。%s"
                                + "请在它前面加一个 %s 节点，让人看清将发生什么再放行；"
                                + "或把这个动作换成可逆的（如先存草稿、由人确认后再发布）",
                        label, toolId, declared.getDescription(),
                        headless
                                ? "本流程由" + triggerLabel(triggerType) + "触发、跑起来时没有人在场，"
                                        + "而不可逆动作执行了就回不去（DDL 没有事务，DdlExecutor 的类注释写得很直白：不假装能回滚）。"
                                : "执行到这一步会直接落库，而不可逆动作执行了就回不去"
                                        + "（DDL 没有事务，DdlExecutor 的类注释写得很直白：不假装能回滚）。",
                        headless ? BPM_APPROVAL_TYPE : HITL_CONFIRM_TYPE + "（或 " + BPM_APPROVAL_TYPE + "）");
            } else {
                unguardedWrites.add(label + "（" + toolId + "）");
                unguardedMax = RiskLevel.higher(unguardedMax, declared);
            }
        }
        if (!unguardedWrites.isEmpty()) {
            result.addWarning("本流程有 %d 个写操作节点的上游没有人工闸口（最高声明风险「%s」）：%s。"
                            + "不阻断，但建议在动手改数据之前加一个 %s，让人有机会拦一下",
                    unguardedWrites.size(), unguardedMax.getDescription(),
                    abbreviate(unguardedWrites),
                    headless ? BPM_APPROVAL_TYPE : HITL_CONFIRM_TYPE);
        }
    }

    /**
     * 无头触发里不许出现 {@code hitl.confirm}（V13）
     *
     * <h3>为什么是 error 而不是 warning：这条挂起<b>永远</b>醒不过来</h3>
     * {@code hitl.confirm} 靠编译器 {@code interruptBefore(id)} 挂起，恢复只有两条路：
     * 人在聊天窗口点确认卡（{@code AgentAssistantServiceImpl}），或人调 {@code resume-stream}。
     * 而 schedule/webhook/form_event 触发的执行<b>没有聊天窗口</b>，
     * 落库时 {@code resume_at} 与 {@code event_key} 都是 NULL ——
     * 连「到点自动醒」和「被事件叫醒」这两个兜底都不存在。
     * 表现是流程发布成功、每次触发都多一条静静躺在 INTERRUPTED 的执行记录，谁也不知道。
     *
     * <h3>⚠️ 与 S4a 那枚「恢复」按钮的关系：可恢复 ≠ 有人值守</h3>
     * 执行历史抽屉会给 INTERRUPTED 行加一个恢复入口，于是严格说这条流程<b>不再</b>「永久」卡死。
     * 但那需要有人先<b>发现</b>它卡住了 —— 定时流程恰恰没有观察者，
     * 而 BPM 待办有承办人、有待办列表、有超时会话，是「有人被指派去看一眼」的唯一机制。
     * 故本条不因那枚按钮降级：按钮是救援，不是唤醒方。
     *
     * <p>文案必须给出路（{@code AgentValidateResult} 类注释的纪律）：
     * 只说「无头流程不能有 hitl.confirm」会让作者把闸口整个删掉 —— 那正是 V11 要拦的方向。
     */
    private void validateHeadlessHitlConfirm(WorkflowDefDTO def, AgentValidateResult result) {
        String triggerType = triggerTypeOf(def);
        if (!isHeadlessTrigger(triggerType)) {
            return;
        }
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !HITL_CONFIRM_TYPE.equals(node.getType())) {
                continue;
            }
            result.addError("节点(%s) 是 %s，但本流程由%s触发、跑起来时没有人在场："
                            + "它会推一张聊天确认卡然后挂起，而这次执行既没有聊天窗口可点、"
                            + "resume_at 与 event_key 也都是空的，没有任何唤醒方，会一直停在 INTERRUPTED。"
                            + "无头流程的人工介入请改用 %s（走 BPM 待办唤醒，有承办人、不依赖聊天窗口）",
                    displayId(node.getId(), i), HITL_CONFIRM_TYPE,
                    triggerLabel(triggerType), BPM_APPROVAL_TYPE);
        }
    }

    /**
     * 无头触发里的 {@code wait(waitType=event)} 必须声明外部唤醒方（V14）
     *
     * <h3>与 V13 是同一种死法，但根因不同</h3>
     * V13 是「唤醒方存在、但唤醒方需要人在聊天窗口里」；本条是「唤醒方<b>压根没有</b>」：
     * {@code waitType=event} 挂起时只把 {@code eventKey} 写进 {@code agent_workflow_execution.event_key}，
     * 等着外部系统调 {@code POST /agent/workflow/event} 把它捞起来 —— 而平台内<b>零调用方</b>，
     * 前端也没有。所以「作者没说谁会调」在实际效果上就等于「没人会调」。
     *
     * <h3>为什么 {@code duration} 那一档不受约束</h3>
     * 它有平台内的自动唤醒方：{@code WorkflowWaitResumeScheduler} 扫 {@code resume_at} 到点续跑。
     * 一刀切禁掉所有 {@code wait} 会把「等 10 分钟再发通知」这种完全正常的自动化挡在门外 ——
     * 与 V11 分型是同一条教训：<b>规则必须先承认哪些写法是合法的，才可满足</b>。
     *
     * <h3>🔴 声明了也只降到 warning，不降成沉默</h3>
     * {@code externalWakeup} 是<b>纯声明</b>（运行期不读），平台无法核实那个系统真的会调。
     * 留一条黄标是为了让「发布时说过谁来唤醒」这件事在界面上看得见、事后追得到；
     * 而它<b>不该</b>是 error：声明了唤醒方却仍被拒，作者只会学会把这一段抄来抄去，
     * 那就同时废掉了本条与那条黄标的意义。声明原文进变更清单由 S6 交付。
     *
     * <h3>只认 owner / endpoint，光写 note 不算声明</h3>
     * 本条要逼作者回答的问题就是「<b>谁</b>来发这个事件、<b>从哪</b>发」。
     * 只填一句 note 等于把问题抄了一遍当答案，故按「未声明」判。
     */
    private void validateWaitEventWakeup(WorkflowDefDTO def, AgentValidateResult result) {
        String triggerType = triggerTypeOf(def);
        if (!isHeadlessTrigger(triggerType)) {
            return;
        }
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !WAIT_NODE_TYPE.equals(node.getType())) {
                continue;
            }
            // 🔴 刻意大小写<b>敏感</b>、逐字对齐编译器 {@code validateWaitConfig} 那个 switch：
            //    「waitType 的取值合不合法」是编译器的权威（{@code EVENT} 会被它判「未知，仅支持
            //    duration/event」）。这里若放宽成不敏感，同一份 DSL 就会同时收到「未知 waitType」
            //    和「没声明唤醒方」两条 —— 一个根因说两遍，正是本类一直在消灭的那种噪音。
            //    E2 之后这把尺子就是 {@link WaitTypeEnum#parse(String)} 本体（trim、大小写敏感、
            //    认不出来返回 null），编译器与本条共用它 ⟹ 「逐字对齐」不再靠人肉抄同一份字面量
            if (WaitTypeEnum.parse(node.configStr("waitType")) != WaitTypeEnum.EVENT) {
                // duration 有自动唤醒方；hitl / 非法值 / 大小写不对都由编译器拒，深层干跑会报
                continue;
            }
            String label = displayId(node.getId(), i);
            String eventKey = node.configStr("eventKey");
            String declared = wakeupDeclarationOf(node.configMap(EXTERNAL_WAKEUP_KEY));
            if (declared == null) {
                result.addError("节点(%s) 是 wait(waitType=event)，但本流程由%s触发、且该节点没有声明外部唤醒方："
                                + "它挂起后只把 eventKey（%s）写进执行记录，等外部系统调 "
                                + "POST /agent/workflow/event 把它捞起来 —— 而平台内没有任何调用方，"
                                + "这次执行会一直停在 INTERRUPTED。"
                                + "请在该节点 config 里补一段纯声明（运行期不读，只为了把唤醒方写下来）："
                                + "\"externalWakeup\":{\"owner\":\"哪个系统/哪个人\",\"endpoint\":\"它从哪调\",\"note\":\"约定\"}；"
                                + "若其实只是要等一段时间，请把 waitType 改成 duration"
                                + "（由 WorkflowWaitResumeScheduler 到点自动唤醒）",
                        label, triggerLabel(triggerType),
                        StrUtil.isBlank(eventKey) ? "还没填" : eventKey);
            } else {
                result.addWarning("节点(%s) 的 wait(waitType=event) 靠外部系统唤醒（声明：%s）。"
                                + "平台无法核实它真的会调 POST /agent/workflow/event（eventKey=%s），"
                                + "请自行确认，否则这次执行会一直停在 INTERRUPTED",
                        label, declared, StrUtil.blankToDefault(eventKey, "未填"));
            }
        }
    }

    /**
     * webhook 触发器必须配鉴权（V15）
     *
     * <h3>为什么 {@code authType=none} 是 error</h3>
     * {@code AgentWebhookController} 那个入口是 {@code @PermitAll} 的
     * {@code POST /agent/webhook/{path}}，租户只从 {@code tenant-id} 头上取；请求体原样落进
     * {@code trigger.inputVar()}，流程随即以<b>发布者身份</b>无头跑起来。
     * 所以「不鉴权」等于：知道 path 的人 = 能用发布者的权限执行这条流程里的任何工具。
     *
     * <h3>🔴 「path 不可猜」这个前提在本平台<b>不成立</b></h3>
     * path 是作者手填的一格（{@code WorkflowForm.vue} 的普通输入框，两个仓库都<b>没有</b>任何随机串生成），
     * 而且它会进日志、进前端 URL、进浏览器历史与代理缓存。文案不能照抄「安全全靠不可猜的 path」那句话 ——
     * 那是一句假话，而作者正是被这句话说服才选了 {@code none}。本条要把这层说破，
     * 否则作者只会换个更像随机的 path 再交一次。
     *
     * <h3>⚠️ 只给 hmac / token 两条出路，不提 IP 白名单</h3>
     * {@code WorkflowTriggerExtractor#WEBHOOK_AUTH_TYPES} 只有 {@code hmac/token} 两个取值，
     * 平台没有 IP 白名单这个机制。文案里点一个不存在的选项，作者会照着找、找不到，
     * 然后以为体检在骗他 —— 与「文案必须给可执行建议」那条纪律正好相反。
     *
     * <h3>🔴 V15 与提取器/入站验签对 {@code none} 的拒绝是<b>刻意重叠</b>的，不要去重</h3>
     * 提取器（{@code applyWebhook}）在 {@code createDef/updateDef 落库前}就拒，入站验签
     * （{@code verifyAuth}）对 {@code none} 也一律拒收。V15 留着是因为它管的是<b>另外两种来路</b>：
     * ① 提取器收紧之前就已入库的存量 DSL（它们跑不了，但作者在管理页看见的必须是「为什么跑不了」
     * 而不是一句静默）；② 直改数据库绕过落库校验的 DSL。这两处只有 V15 会说人话，
     * 而 D7「一个根因只报一遍」针对的是<b>同一时刻会同时触发的副本</b>，不是纵深防御。
     *
     * <h3>⚠️ 未知的 authType 与「配了 hmac/token 却没 secret」都不在这里报</h3>
     * 两条都是 {@code applyWebhook} 在<b>落库前</b>（{@code createDef/updateDef}，不是 {@code publishDef} ——
     * 后者只翻状态、不调提取器）以 {@code WORKFLOW_TRIGGER_CONFIG_INVALID} 拒的规则。
     * 抄一份过来就是 D7「同一规则多处副本」，而这两条的权威判定还带着
     * {@code WEBHOOK_PATH_PATTERN} 之类的常量，两份必然漂移。
     */
    private void validateWebhookAuth(WorkflowDefDTO def, AgentValidateResult result) {
        TriggerDTO trigger = def.getTrigger();
        if (trigger == null || !TRIGGER_TYPE_WEBHOOK.equals(triggerTypeOf(def))) {
            return;
        }
        Map<String, Object> config = trigger.getConfig();
        String authType = strOf(config == null ? null : config.get(WEBHOOK_AUTH_TYPE_KEY));
        // 与提取器同口径地归一化（trim + 小写）：它就是这么做的，
        // 于是 "NONE" 同样按不鉴权落地，本条也必须把它认出来
        String normalized = authType == null ? "" : authType.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty() && !WEBHOOK_AUTH_TYPE_NONE.equals(normalized)) {
            return;
        }
        result.addError("webhook 触发器没有配鉴权（authType=%s）：入口 POST /agent/webhook/{path} 是免登录接口，"
                        + "打得到这个 path 的人就能把请求体原样灌进变量「%s」、"
                        + "并以发布者身份无头拉起这条流程 —— 流程里有写工具的话，这就是一个远程执行入口。"
                        + "⚠️ 别指望「path 猜不到」：path 是作者在设计器里手填的一格，平台不生成随机串，"
                        + "而且它会进日志、进前端 URL、进浏览器历史与代理缓存。"
                        + "请把 authType 改成 hmac（校验请求头 X-Webhook-Signature："
                        + "对原始请求体做 HMAC-SHA256 的十六进制串，可带 sha256= 前缀）"
                        + "或 token（校验请求头 X-Webhook-Token 与 secret 是否一致），两者都必须配 secret。"
                        + "（平台已不再支持不鉴权：这样的 DSL 在落库前就会被 WorkflowTriggerExtractor 拒，"
                        + "存量行或直改数据库塞进来的也会在入站时被 verifyAuth 一律拒收）",
                StrUtil.isBlank(authType) ? "未填" : authType.trim(),
                StrUtil.blankToDefault(trigger.inputVar(), "input"));
    }

    /**
     * chat 触发器绑的 Agent 编码必须存在（V28）
     *
     * <h3>🔴 复现的事故：报告说 clean，闸门却拒</h3>
     * 一份 AI 生成的会议室预订流程，{@code trigger.chat.config.agentId} 填的是<b>模型自己编的</b>
     * {@code agent_meeting_room}（用户从没要求绑任何 Agent）。规划期 3 轮体检全绿、落草稿、
     * 点发布 —— 才撞上 {@code WORKFLOW_TRIGGER_CHAT_AGENT_NOT_FOUND}。
     * 而同一份 DSL 走 {@code auditAll} 盘点时<b>一条都不报</b>：那边只跑 {@code doValidateDsl}，
     * 压根不调发布路径上的 {@code AgentWorkflowDefServiceImpl#validateChatBinding}。
     * 于是那份闸门 javadoc 自己声明的同一性（「盘点报告里 failed 的那一行」=「发布被拒的那一行」
     * 是<b>结构保证</b>，不是巧合）就分了岔，而报告是给人做开闸决定用的。
     *
     * <h3>🔴 与 {@code validateChatBinding} 是<b>刻意重叠</b>的两道，不要去重</h3>
     * 那道读<b>投影列</b> {@code trigger_key}（聊天入口反查的就是它），本条读 <b>DSL 里的</b>
     * {@code agentId}（作者与盘点报告唯一看得见的东西）。两列会不一致：提取器收紧之前入库的存量行、
     * 直改数据库绕过投影的行。V15 与 {@code applyWebhook} 是同一套处置，D7「一个根因只说一遍」
     * 针对的是<b>同一时刻会同时触发的副本</b>，不是纵深防御。
     *
     * <p>共用的只有<b>判据</b>那一句：{@link AgentDefMapper#existsByCode}（含「只问存在、不问状态」的口径）。
     *
     * <h3>🔴 占位符用严格的 {@link #isPlaceholder}，不是 {@code StateTemplateResolver#containsPlaceholder}</h3>
     * 判据是「运行期会不会把它换掉」，而这一格<b>压根不做变量替换</b>：{@code applyChat} 在
     * {@code createDef/updateDef} 落库时就把它原样搬进 {@code trigger_key}，运行期再没人读 DSL 这一格。
     * 所以 `{{unclosed` 这种畸形形状同样要拦 —— 用那个宽松判据会把它放过去，
     * 而它落地后就是一个永远反查不命中的字面量。
     *
     * <h3>🔴 错误文案<b>刻意不列</b>「有哪些 Agent 编码可用」</h3>
     * 与 V27（{@link #validatePublishParentMenu}）那条相反：那边列出启用目录名是<b>唯一出路</b>，
     * 因为表单总得挂在某个目录下。这边不是 —— 聊天入口是<b>按 Agent 反查 chatflow</b> 的
     * （{@code selectPublishedChatTriggerByAgentCode}），把一条不相干的流程绑到某个真实存在的 Agent 上，
     * 等于抢走那个 Agent 的会话入口。列出候选就是在教模型做这件事。
     * 故只给一条出路：<b>把 agentId 这一格删掉</b>（留空合法，见下）。
     *
     * <h3>⚠️ 留空是合法的，本条必须沉默</h3>
     * {@code applyChat} 明写「留空 = 该 chat 流程不绑定任何 Agent（仅手动 run-stream / 规划草稿用）」，
     * 前端新建骨架 {@code WorkflowDefFormDialog.DEFAULT_DSL} 就没有这一格。
     * 对空白报 error 会把全部存量骨架与绝大多数规划产物一起打死。
     *
     * <h3>编号纪律（本条是 <b>V28</b>）</h3>
     * 见 {@link #validateChatLegsWriteReply} 规则头那段权威源。⚠️ 「下一个可用号是几」只在那里说，本条不抄。
     */
    private void validateChatBindingAgentExists(WorkflowDefDTO def, AgentValidateResult result) {
        TriggerDTO trigger = def.getTrigger();
        if (trigger == null || !TRIGGER_TYPE_CHAT.equals(triggerTypeOf(def))) {
            return;
        }
        Map<String, Object> config = trigger.getConfig();
        String agentCode = strOf(config == null ? null : config.get(CHAT_AGENT_ID_KEY));
        if (StrUtil.isBlank(agentCode)) {
            return;
        }
        if (isPlaceholder(agentCode)) {
            result.addError("trigger.chat.config.agentId 写成了 %s，但这一格运行期不做变量替换："
                            + "落库时 WorkflowTriggerExtractor 把它原样投影进 trigger_key，"
                            + "聊天入口再拿那个字面量去反查 Agent —— 永远查不到，这条流程就静静躺着永不触发。"
                            + "请把 agentId 这一格整个删掉（留空 = 这条 chat 流程不绑定任何 Agent，合法，"
                            + "它仍可由人在编辑器/执行历史里手动运行）；只有用户明确点名了一个已存在的智能体编码时，"
                            + "才把那个编码原样写进来",
                    agentCode);
            return;
        }
        String code = agentCode.trim();
        try {
            if (!agentDefMapper.existsByCode(code)) {
                result.addError("trigger.chat.config.agentId 填的是「%s」，但 agent_def 里没有这个编码的 Agent。"
                                + "这一格会被原样投影进 trigger_key，聊天入口拿它反查"
                                + "「哪条已发布的 chatflow 驱动这个 Agent」—— 查不到就等于这条流程永远不会被拉起，"
                                + "而界面上不会有任何提示（正是本平台头号缺陷「配了不生效且无提示」）。"
                                + "修法只有一条：把 agentId 这一格整个删掉。"
                                + "除非用户在需求里明确点名了一个已存在的智能体编码，否则不要自己编一个填进去。"
                                + "⚠️ 也不要为了「填上一个存在的编码」而去挑别的 Agent："
                                + "聊天入口是按 Agent 反查 chatflow 的，把一条不相干的流程绑到某个真实 Agent 上，"
                                + "等于抢走那个 Agent 的会话入口",
                        code);
            }
        } catch (Exception ex) {
            // 探测失败不是违规（§6.1），且这一格的探测入参就是它自己那一个编码 —— 作用域跟着入参走，
            // 故用 V10（validateSubWorkflowRefs）那套单项跳过的写法，而不是 warnProbeAborted 的整趟停手
            result.addWarning("chat 触发器绑定校验跳过（查 Agent 编码 %s 失败：%s）", code, ex.getMessage());
        }
    }

    /**
     * 归一化后的触发器短名；DSL 里没有 trigger 块（或 type 为空）时返回 {@code null}
     *
     * <p>🔴 口径逐字照 {@code WorkflowTriggerExtractor#normalizeType}（剥 {@code trigger.} 前缀、
     * trim、转小写）：落库的 {@code trigger_type} 列存的是短名，本方法判的也必须是短名。
     * 两处漂了的表现是「DSL 写全名 {@code trigger.schedule}、校验按全名比对 chat」——
     * 于是无头流程被当成有人在场的，V11/V13/V14 三条一起静默失效。改一处必须改两处。
     */
    private static String triggerTypeOf(WorkflowDefDTO def) {
        TriggerDTO trigger = def.getTrigger();
        if (trigger == null || StrUtil.isBlank(trigger.getType())) {
            return null;
        }
        String raw = trigger.getType().trim().toLowerCase(Locale.ROOT);
        return raw.startsWith(TRIGGER_TYPE_PREFIX) ? raw.substring(TRIGGER_TYPE_PREFIX.length()) : raw;
    }

    /**
     * 这个触发器跑起来时是不是「没有人在场」
     *
     * <p>🔴 两处刻意的判定，方向相反，各自都有理由：
     * <ul>
     *   <li>{@code null}（DSL 里没有 trigger 块）<b>不算</b>无头。这种定义只能由人点
     *       {@code run-stream} 跑起来（沙箱、执行历史的重跑），那个人就在场，
     *       聊天确认卡有地方推。判成无头会把沙箱里调 {@code hitl.confirm} 这条主路径整个堵死。</li>
     *   <li>认不出来的类型<b>算</b>无头（fail-close）。四种已知触发器里只有 {@code chat} 有人在场，
     *       一个平台不认识的触发器不可能是它；而放行等于给一个谁都没审过的入口开绿灯。
     *       从严不会造成「本来能发的发不出去」：这类 DSL 到发布时会被
     *       {@code WorkflowTriggerExtractor} 以 {@code WORKFLOW_TRIGGER_CONFIG_INVALID} 拒掉。
     *       ⚠️ 「触发器类型不认识」这件事本身<b>不在这里报</b> —— 那是提取器的规则，
     *       抄一份过来就是 D7「同一规则多处副本」，两份必然漂移。</li>
     * </ul>
     */
    private static boolean isHeadlessTrigger(String triggerType) {
        return triggerType != null && !TRIGGER_TYPE_CHAT.equals(triggerType);
    }

    /** 触发器在错误文案里的中文说法；认不出来的类型原样带上，好让作者看出自己写错了什么 */
    private static String triggerLabel(String triggerType) {
        if (triggerType == null) {
            return "手动调用";
        }
        return switch (triggerType) {
            case "schedule" -> "定时任务";
            case "webhook" -> "Webhook 入站";
            case "form_event" -> "表单数据变更";
            case TRIGGER_TYPE_CHAT -> "会话";
            default -> "「" + triggerType + "」（平台不认识的触发器，按无头处理）";
        };
    }

    /**
     * 把 {@code externalWakeup} 那段声明摘成一行可读的中文；<b>没声明</b>（或只写了 note）返回 {@code null}
     *
     * <p>{@code configMap} 在这一格不是对象时返回 {@code null}（写成字符串、写成数组都算没声明）——
     * 与本类对 {@code args} 的处理同口径，不额外报「类型不对」：那是深层干跑的事。
     */
    private static String wakeupDeclarationOf(Map<String, Object> wakeup) {
        if (wakeup == null) {
            return null;
        }
        String owner = strOf(wakeup.get("owner"));
        String endpoint = strOf(wakeup.get("endpoint"));
        String note = strOf(wakeup.get("note"));
        if (StrUtil.isBlank(owner) && StrUtil.isBlank(endpoint)) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        if (StrUtil.isNotBlank(owner)) {
            text.append("唤醒方=").append(owner.trim());
        }
        if (StrUtil.isNotBlank(endpoint)) {
            text.append(text.length() == 0 ? "" : "，").append("入口=").append(endpoint.trim());
        }
        if (StrUtil.isNotBlank(note)) {
            text.append(text.length() == 0 ? "" : "，").append("约定=").append(note.trim());
        }
        return text.toString();
    }

    /** {@code haystack} 里有没有 {@code needles} 中的任意一个；两者都小，直接扫 */
    private static boolean containsAny(Set<String> haystack, Collection<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 文案里的清单最多列 5 项：再长就没人读了，而「还有 N 处」这句话本身就够作者去画布上找 */
    private static String abbreviate(List<String> items) {
        return abbreviate(items, "、");
    }

    /**
     * 同上，但可指定分隔符：列「节点(id)」这种短标签用顿号；
     * 列 {@code UntrustedInput#summary} 那种<b>整句</b>用分号 —— 整句之间用顿号会读成一串没有断句的长句
     */
    private static String abbreviate(List<String> items, String separator) {
        if (items.size() <= 5) {
            return String.join(separator, items);
        }
        return String.join(separator, items.subList(0, 5)) + " 等 " + items.size() + " 处";
    }

    /**
     * 节点参数里 {@code {{$.x}}} 的根变量必须解析得到
     *
     * <p>模型很爱写 {@code {{$.tableName}}} 这种「看起来像引用、其实谁都没产出」的占位符。
     * 运行期解析不到时它会被替成<b>空串</b>（{@code StatePathNavigator} 刻意「失败返回 null、绝不抛异常」，
     * 类注释写着「应当替成空串让流程继续」；{@code StateTemplateResolver.stringify(null)} 再把它落成 {@code ""}），
     * 表现是工具报「表名不合法」而引号里空的 —— 用户看到的是一次莫名其妙的失败，
     * 而根因在 DSL 生成期就该拦下。这里把不可解析的引用列成错误回灌给模型，
     * 让它在自修正回路里改引用或写字面值。
     *
     * <p>🔴 别把这句写成「占位符会被原样当成值传下去」—— 那是<b>错的</b>，且错了很久没人发现：
     * 「原样传下去」只对<b>压根不做模板替换</b>的格子成立（{@code toolId}、
     * {@code bpm_approval.bpmProcessDefKey}、{@code sub_workflow.workflowId} 三处，各自的规则头都点明了这一点），
     * 本条判的恰恰是<b>会</b>被替换的格子。两种错法在界面上长得像（都是一次失败的执行），
     * 但改法完全相反：前者要「别用占位符」，后者要「把路径写对」。
     * 子键写错（{@code {{$.var.不存在的键}}}）是同一族里的第三种，归 {@link #validateToolArgSubkeys}（V35）：
     * 它的根解析得到、故本条沉默，运行期同样落成空串。
     *
     * <h3>🔴 唯一的例外：{@code sub_workflow.outputMap} 的<b>取值表达式</b>按「父 ∪ 子」判（缺陷 E3-2）</h3>
     * 那一格的语义与其余所有格子<b>相反</b>：契约把它标成 {@code MODE_LITERAL} 并明写
     * 「这里的 {@code {{$.x}}} 指的是<b>子流程</b>里的 x」（{@code NodeIoContract} 的 {@code sub_workflow} 段）。
     * 运行期也确实是这么求值的 —— {@code DslToStateGraphCompiler} 把配了映射的 sw 改写成
     * {@code X→_<sw>_in→sw→_<sw>_out→Y}，而 {@code _<sw>_out} 的 {@code resolveMapValue} 跑在<b>子图之后</b>、
     * 读的是子图写回后的<b>共享 state</b>（SAA 原生子图整包喂入、共享 key 空间）。
     * 于是「父 def 的名字表」对这一格是一份<b>错的</b>判据：
     * <ul>
     *   <li>作者写 {@code outputMap:{"parentStruct":"{{$.childOut}}"}}（{@code childOut} 是子流程那个
     *       capture 的 {@code outputVar}）—— 这是<b>唯一正确</b>的写法，旧口径却报
     *       「{{$.childOut}} 运行期解析不到，请改引用已声明的变量或写字面值」。
     *       🔴 照它给的出路改（写字面值 / 改引父变量）会把一个本来能跑的流程改坏。</li>
     *   <li>反过来，父的键在这一格<b>也</b>解析得到（共享 state 里父子同名同域），所以是<b>并集</b>、
     *       不是「换成子表」—— 换成子表就会把 {@code {{$.父变量}}} 这类合法写法报成假警报。</li>
     *   <li>再加上本节点 {@code inputMap} 的键：{@code _<sw>_in} 在子图<b>之前</b>就把它们写进了同一份
     *       共享 state，故 {@code _<sw>_out} 求值时它们在场。这些名字是父作者写在<b>同一个节点</b>里的，
     *       不查库就能拿到。</li>
     * </ul>
     *
     * <h3>⚠️ 子 def 取不到时这一格<b>整格不判</b>（fail-open，且是有意的）</h3>
     * 子流程 code 填错 / 未发布 / pin 到草稿 / 库连不上 / 子 DSL 解析不了 —— 这些情形下拿不到子名字表，
     * 此时<b>退回按父表判</b>等于把上面那条假警报原样留着，只是换了个触发条件。
     * 根因已经由 {@link #validateSubWorkflowRefs}（V10）用一句可操作的话报出来了
     * （「子流程用不了：…请改成一个已发布的流程 code」），子流程都取不到时 outputMap 里引用什么
     * 本来就无从判起，故这里静默跳过、<b>不</b>再叠一条同向噪音。
     *
     * <h3>⚠️ 射程：本例外只碰 outputMap 的<b>值</b></h3>
     * <ul>
     *   <li><b>键不在本规则射程内</b>（改动前后都一样）：{@link #collectUnresolvableRoots} 只递归
     *       {@code map.values()}、从不看键。键是「写回父流程哪个变量名」，由编译期
     *       {@code DslToStateGraphCompiler#validateMapping} 按 {@code VAR_NAME} 判格式
     *       （占位符当键会被编译期拒），而它的<b>声明</b>问题归 V19 那一支
     *       （{@link #validateSubWorkflowOutputMapDeclared}）。</li>
     *   <li>{@code inputMap} 的<b>值</b>照旧按父表判，且这是<b>对的</b>：{@code _<sw>_in} 跑在子图之前，
     *       那一刻共享 state 里只有父的东西。</li>
     *   <li>{@link #validateReferenceReachability} 那条 warning 级的可达性规则<b>没有</b>跟着改，
     *       它仍按父图的产出者判 outputMap 的值。刻意留着：它自己那段 javadoc 已经声明
     *       「并行/循环/子流程的接线由编译器在编译期决定，静态可达性无法 100% 精确」，
     *       且只在「父图里正好有个节点产出同名变量、又不在 sw 上游」时才响 —— 与本条那种
     *       <b>每个正确写法都稳定报 error</b> 的形状不是一回事。</li>
     * </ul>
     *
     * <p>🔴 一次 {@code validate} 内按 {@code code@version} 缓存子名字表：同一份子流程被两个 sw 节点
     * 引用时只查一次库（V10 那边各查各的，两条规则不共享解析器 —— 共享要把 V10 的
     * 「ServiceException 就地转 error」拆出来，代价大于省下的那一次查询）。
     */
    private void validatePlaceholders(WorkflowDefDTO def, Set<String> declaredVars,
                                      AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null) {
            return;
        }
        Set<String> resolvable = collectResolvableRoots(def, declaredVars);
        Map<String, Set<String>> childRootsCache = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || node.getConfig() == null) {
                continue;
            }
            // sub_workflow 的 outputMap 值要换一份名字表判，故先把它从「按父表判」这趟里摘出去
            Map<String, Object> outputMap = SUB_WORKFLOW_TYPE.equals(node.getType())
                    ? node.configMap("outputMap") : null;
            Object configToScan = node.getConfig();
            if (outputMap != null && !outputMap.isEmpty()) {
                Map<String, Object> rest = new LinkedHashMap<>(node.getConfig());
                rest.remove("outputMap");
                configToScan = rest;
            } else {
                outputMap = null;
            }
            Set<String> unresolvable = new LinkedHashSet<>();
            collectUnresolvableRoots(configToScan, resolvable, unresolvable);
            if (outputMap != null) {
                Set<String> childRoots = childResolvableRoots(def.getId(), node, childRootsCache);
                if (childRoots != null) {
                    Set<String> union = new HashSet<>(resolvable);
                    union.addAll(childRoots);
                    collectUnresolvableRoots(outputMap, union, unresolvable);
                }
            }
            for (String root : unresolvable) {
                result.addError("节点(%s) 的参数引用了 {{$.%s}}，但 %s 既不在 variables[] 里、"
                                + "也不是任何节点的 outputVar，运行期这一格会被替成**空串**"
                                + "（不是原样传下去），工具于是收到一个空值、报出来的那句往往指着别的地方。"
                                + "请改引用已声明的变量或上游节点的 outputVar，或直接写字面值",
                        displayId(node.getId(), i), root, root);
            }
        }
    }

    /**
     * 一份 DSL 里「{@code {{$.x}}} 的 {@code x} 解析得到」的完整名字表
     *
     * <p>🔴 抽出来是因为现在有<b>两份</b> DSL 要按同一口径建表：父 def（本规则的主判据）与
     * {@code sub_workflow} 的子 def（outputMap 的值那一格）。各写一遍必然漂移，
     * 而漂移的表现是「同一份写法在父流程里绿、抄进子流程就红」——最难查的一类假警报。
     *
     * <p>四项来源与运行期同源：顶层 {@code variables[]}、字面 {@code input}、触发器的 {@code inputVar}、
     * 以及任意节点的 {@code outputVar}（含 iteration 的 {@code itemVar} 之外的一切产出名）。
     */
    private Set<String> collectResolvableRoots(WorkflowDefDTO def, Set<String> declaredVars) {
        Set<String> resolvable = new HashSet<>(declaredVars);
        resolvable.add("input");
        if (def.getTrigger() != null && StrUtil.isNotBlank(def.getTrigger().inputVar())) {
            resolvable.add(def.getTrigger().inputVar());
        }
        if (def.getNodes() != null) {
            for (NodeDTO node : def.getNodes()) {
                if (node != null && StrUtil.isNotBlank(node.configStr("outputVar"))) {
                    resolvable.add(node.configStr("outputVar"));
                }
            }
        }
        return resolvable;
    }

    /**
     * 取 {@code sub_workflow} 节点所指子流程的名字表，供 outputMap 的取值表达式判可达
     *
     * @param parentDefId 本流程的 {@code id}，用来复刻 V10 那条「自己引自己压根不用查库」的短路
     * @param cache       一次 {@code validate} 内按 {@code code@version} 复用（见 {@link #validatePlaceholders} 的 🔴 段）
     * @return 子 def 的名字表（并上本节点 {@code inputMap} 的键）；<b>取不到就返回 {@code null}</b>，
     *         调用方据此整格跳过（fail-open 的理由写在 {@link #validatePlaceholders} 的 ⚠️ 段）
     */
    private Set<String> childResolvableRoots(String parentDefId, NodeDTO node, Map<String, Set<String>> cache) {
        String code = node.configStr("workflowId");
        // 🔴 这三个短路必须与 V10（validateSubWorkflowRefs）逐条对齐：那三种形状 V10 各有专属文案，
        //    且其中两种「压根不用查库」已被 verify(defService, never()) 钉住 —— 这里多查一次就把它们弄红了
        if (StrUtil.isBlank(code) || isPlaceholder(code) || code.equals(parentDefId)) {
            return null;
        }
        String version = node.configStr("version");
        String cacheKey = code + "@" + StrUtil.nullToEmpty(version);
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }
        Set<String> roots = null;
        try {
            AgentWorkflowDefDO childDo = defService.getSubWorkflowDef(code, intOrNull(version));
            WorkflowDefDTO childDef = dslParser.parse(childDo.getDsl());
            roots = collectResolvableRoots(childDef, collectDeclaredVars(childDef.getVariables()));
            // _<sw>_in 在子图之前就把 inputMap 的键写进同一份共享 state，故 _<sw>_out 求值时它们在场
            Map<String, Object> inputMap = node.configMap("inputMap");
            if (inputMap != null) {
                for (String key : inputMap.keySet()) {
                    if (StrUtil.isNotBlank(key)) {
                        roots.add(key.trim());
                    }
                }
            }
        } catch (Exception ex) {
            // 子流程查不到 / 是草稿 / 库连不上 / 子 DSL 解析不了：一律降级成「这一格不判」。
            // 🔴 不 addWarning：根因 V10 已经报过，再叠一条只会把真那句话挤下去
            roots = null;
        }
        cache.put(cacheKey, roots);
        return roots;
    }

    private void collectUnresolvableRoots(Object value, Set<String> resolvable, Set<String> sink) {
        if (value instanceof String text) {
            Matcher matcher = PLACEHOLDER_ROOT.matcher(text);
            while (matcher.find()) {
                String root = matcher.group(1);
                if (!resolvable.contains(root)) {
                    sink.add(root);
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                collectUnresolvableRoots(item, resolvable, sink);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collectUnresolvableRoots(item, resolvable, sink);
            }
        }
    }

    /**
     * 引用可达性（🔴 警告，不阻断）：节点引用的 {{$.x}} 若由「不在其上游」的节点产出，运行到该节点时 x 很可能还没有值。
     *
     * <p>为什么是<b>警告而非错误</b>：已发布流程常引用并行汇合后的变量、或编译器自动接线的子图变量，
     * 静态可达性判断无法 100% 精确（并行/循环/子流程的接线由编译器在编译期决定）。故只做「温和提示」，
     * 绝不阻断保存/发布，避免让存量流程体检不过（Req6 向后兼容红线）。
     *
     * <p>判定口径：反向 BFS 求每个节点的「祖先集」；引用变量 x 若存在产出者、且<b>没有任何</b>产出者是祖先，
     * 才警告。声明变量 / input / 触发变量 / iteration 的 itemVar（未在契约登记为输出）都不算「节点产出」，直接跳过，
     * 从而并行汇合（两分支都是 join 节点的祖先）与循环体（引用未登记的 itemVar）都不会误报。
     *
     * <p>反向 BFS 本身已收进 {@link GraphReachability#ancestorsOf}（S2/D-new7），本方法只负责
     * 「谁产出、谁引用、祖先集里有没有」这层语义。
     */
    private void validateReferenceReachability(WorkflowDefDTO def, GraphReachability.Graph graph,
                                               AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        // ⚠️ edges == null 时直接返回是<b>刻意保留</b>的既有行为，不要因为「反正 graph 也能查」就删掉：
        // 没有边的 DSL 上每个节点的祖先集只有它自己，于是所有跨节点引用都会报警告，
        // 而这份 DSL 此时已经被 validateEdges 判成「缺 START 边」了 —— 再刷一屏警告只是噪音。
        List<EdgeDTO> edges = def.getEdges();
        if (nodes == null || edges == null || nodes.isEmpty()) {
            return;
        }

        // 变量名 → 产出它的节点 id 集合（仅取契约登记的输出端口：outputVar / approveVar / statusVar / citationsVar）
        Map<String, Set<String>> producers = collectProducers(nodes);
        if (producers.isEmpty()) {
            return;
        }

        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO n = nodes.get(i);
            if (n == null || n.getConfig() == null) {
                continue;
            }
            Set<String> ancestors = graph.ancestorsOf(n.getId());
            Set<String> refs = new LinkedHashSet<>();
            collectAllRoots(n.getConfig(), refs);
            for (String ref : refs) {
                Set<String> prod = producers.get(ref);
                if (prod == null || prod.isEmpty()) {
                    continue; // 非「节点产出」变量（声明变量 / input / itemVar 等）：可达性不适用，跳过
                }
                boolean reachable = prod.stream().anyMatch(ancestors::contains);
                if (!reachable) {
                    result.addWarning("节点(%s) 引用了 {{$.%s}}，但产出该变量的节点不在它的上游——"
                                    + "运行到本节点时 %s 很可能还没有值。请把产出该变量的节点排到本节点之前，"
                                    + "或改为引用已声明变量 / 字面值",
                            displayId(n.getId(), i), ref, ref);
                }
            }
        }
    }

    /** 收集任意 config 值里 {{$.x}} 的全部根变量名（不限可解析性，供可达性判定用） */
    private void collectAllRoots(Object value, Set<String> sink) {
        if (value instanceof String text) {
            Matcher matcher = PLACEHOLDER_ROOT.matcher(text);
            while (matcher.find()) {
                sink.add(matcher.group(1));
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                collectAllRoots(item, sink);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collectAllRoots(item, sink);
            }
        }
    }

    /**
     * 「哪个变量名由哪些节点产出」—— 变量名 → 产出它的节点 id 集合
     *
     * <p>只取<b>契约登记的输出端口</b>（{@code outputVar} / {@code approveVar} / {@code statusVar} /
     * {@code citationsVar} / {@code trajectoryVar}），所以「新增一种节点类型」时两个消费者自动跟着覆盖。
     *
     * <p>🔴 抽出来是因为有<b>两个</b>消费者要按同一口径回答「这个键有人写吗」：
     * {@link #validateReferenceReachability}（警告级，还要拿 id 集合去比祖先）与本文件的 V24
     * （{@link #validateRuleConditionVarsWritten}，阻断级，只关心键名在不在）。
     * 各写一份必然漂移，而漂移的表现是「同一个键，一条规则说没人写、另一条说有人写」——
     * 与 {@link #writtenVarOf} 被抽成一处是同一个理由（那份的强制点是 {@code WrittenVarOfSingleSourceTest}）。
     * 键名口径照旧走 {@code writtenVarOf}：作者没填那一格时算的是<b>契约缺省名</b>，
     * 于是「用缺省 {@code bpm_approved} 的审批节点」也算产出者，不会误报。
     */
    private Map<String, Set<String>> collectProducers(List<NodeDTO> nodes) {
        Map<String, Set<String>> producers = new HashMap<>();
        for (NodeDTO n : nodes) {
            if (n == null || StrUtil.isBlank(n.getId()) || n.getConfig() == null) {
                continue;
            }
            NodeIoContract.Contract c = NodeIoContract.of(n.getType());
            if (c == null) {
                continue;
            }
            for (NodeIoContract.OutPort op : c.outputs()) {
                // 与 V19 共用 writtenVarOf：两处对「这个节点写哪个键」必须是同一个答案，
                // 否则同一份 DSL 会一边说「tool_result 未声明」、一边说「产出 tool_result 的不是你的祖先」
                String varName = writtenVarOf(n, op);
                if (StrUtil.isNotBlank(varName)) {
                    producers.computeIfAbsent(varName, k -> new HashSet<>()).add(n.getId());
                }
            }
        }
        return producers;
    }

    // ==================== 规则条件读的键没人写（V24） ====================

    /**
     * {@code condition.rule} 的 {@code branches[].conditions[].var} 必须真有节点写（V24，🔴 <b>仅会话触发</b>）
     *
     * <h3>为什么是 error：这条分支<b>结构性不可达</b>，而执行状态照样报成功</h3>
     * {@code RuleConditionNodeExecutor#evaluateOne} 是 {@code ctx.get(var)}，而
     * {@code WorkflowContext#get} 只做 {@code state.value(key)} —— <b>顶层</b>键，不做 {@code $.a.b} 那种嵌套取值。
     * 取不到就是 null，于是除 {@code not_exists} 之外的<b>全部</b>算子都判不命中
     * （{@code is_true}/{@code is_false} 都要求非 null，{@code numCompare} 遇 null 明确 yield false），
     * 整条分支永远走不到；都不命中时落 {@code config.default}，流程图上那条腿就此变成装饰。
     * 与 V19 同一档危害：<b>静默</b>。没有异常、没有告警，只有「跑成功了，但少做了一半事」。
     *
     * <h3>🔴 为什么{@link #collectResolvableRoots} 那套（V7/V8）碰不到它</h3>
     * 别的节点读状态一律写 {@code {{$.x}}}，走 {@code StateTemplateResolver}，于是「x 有没有出处」
     * 是 V7/V8 的射程；而本处 {@code var} 在契约里登记的 mode 就是 <b>MODE_LITERAL</b>
     * （{@code NodeIoContract} 那条 hint 明写「变量名只认顶层键」），压根不是占位符。
     * V24 之前，{@code condition.rule} 的 {@code var} 是全 DSL <b>唯一</b>一处
     * 「读了一个 state 键、却没有任何规则问它有没有出处」的地方。
     *
     * <h3>🔴 判「恒为 null」的前提，以及为什么<b>只对会话触发</b>成立</h3>
     * 前提是「起跑时 state 里除了触发器那一个键，什么都没有」。三条来路只有一条给得出这个前提：
     * <table border="1">
     *   <caption>三条来路的初始 state</caption>
     *   <tr><th>来路</th><th>初始 state</th><th>本条</th></tr>
     *   <tr><td>会话触发（{@code AgentAssistantServiceImpl} 那句
     *       {@code Map.of(vars.inputVar(), question)}）</td><td><b>恰好一个键</b></td><td>✅ 生效</td></tr>
     *   <tr><td>助手「确认并运行」（{@code AssistantChat.vue} 调 run-stream 时传的是 {@code {}}）</td>
     *       <td><b>空</b></td><td>✅ 生效（更严）</td></tr>
     *   <tr><td>{@code POST /agent/workflow/run-stream} 直接调</td>
     *       <td>调用方给什么就是什么，且<b>整个 map 被摊平进顶层</b></td><td>❌ 不判</td></tr>
     * </table>
     * 第三行是本条必须按触发器分型的全部理由：{@code AgentWorkflowExecutionServiceImpl#run} 是
     * {@code new HashMap<>(input)} 之后原样 {@code graph.stream(inputs, config)}，而
     * {@code KeyStrategyFactoryGenerator} 挂了 {@code defaultStrategy(REPLACE)} ——
     * <b>未声明</b>的键照样收。所以「没人写」在手动那档推不出「恒为 null」：
     * 调用方完全可能自己撒一个进来。对那一档报 error 就是把「可能」说成「必然」，
     * 与 V11/V19 那条纪律同向：<b>规则必须先承认哪些写法是合法的，才可满足</b>。
     *
     * <p>⚠️ 判据用的是 {@code TRIGGER_TYPE_CHAT.equals(triggerTypeOf(def))}，<b>不是</b>
     * {@code !isHeadlessTrigger(...)}：后者对 {@code trigger == null} 也返回真，而那种定义
     * （{@code starterDsl.ts} 的 {@code none} 档）<b>只能</b>由人点 run-stream 跑起来，
     * 正是上表第三行 —— 拿它当会话判就是把最不该判的那一档判了。
     *
     * <h3>🔴 {@code variables[]} 声明<b>不</b>豁免（这是本条最容易写错的一格）</h3>
     * 声明只注册合并策略，<b>不赋值</b>：{@code KeyStrategyFactoryGenerator#generate} 只对每个声明键
     * 调 {@code builder.addStrategy(key, …)}，一个初始值都不塞。所以「声明了但没人写」的键
     * 在会话那一档<b>照样恒为 null</b>。实测形状（2026-10-09，AI 生成的草稿 610）：
     * {@code variables[]} 里老老实实写了 {@code confirm_publish}，全图却只有一个 {@code hitl.confirm}
     * 且它的 {@code approveVar} 填的是<b>另一个</b>名字 {@code confirm_create}；
     * 下游 {@code gate_publish} 拿 {@code confirm_publish} 判 {@code is_true}/{@code is_false} ——
     * 两个分支同时死，恒定落 {@code default="cancel"}，于是「发布表单」那条腿一次都走不到，
     * 而草稿摘要写着「最后由人工确认后发布」。
     * 拿声明当豁免，本条对这个形状<b>完全沉默</b>。
     *
     * <h3>豁免集（四来源，都是「运行期真会往顶层 state 写、但不在契约 {@code outputs()} 里」的键）</h3>
     * <ol>
     *   <li>{@code trigger.inputVar()}（缺省 {@code input}）—— 会话那一档唯一被塞进来的键；</li>
     *   <li>{@code iteration.itemVar} —— 契约登记的<b>入</b>端口，运行期真写当前项
     *       （V19 的 javadoc 记着同一件事，那边不判的理由与本处不同）；</li>
     *   <li>{@code sub_workflow.inputMap} / {@code outputMap} 的<b>键</b> ——
     *       编译器合成的 {@code _<sw>_in} / {@code _<sw>_out} 逐个 {@code delta.put(键, …)}
     *       写进<b>父流程</b>的共享 state（台账 E3 那段实测）；</li>
     *   <li>契约 {@code outputs()} 的全部产出名 —— 走 {@link #collectProducers}，
     *       含作者没填时回落的缺省名。</li>
     * </ol>
     * 键名一律 {@code trim} 后比：与运行期同源（{@code DslToStateGraphCompiler#validateMapping}
     * 也是 trim 之后才 put 的），否则作者写 {@code {" parentStruct ": …}} 时两边对不上。
     *
     * <h3>⚠️ 刻意留在射程外的三档（别当成漏了）</h3>
     * <ol>
     *   <li><b>{@code conditionGroups} 的 {@code field}</b>：嵌套 AND/OR 那一档同样只认顶层键
     *       （{@code RuleConditionNodeExecutor#matchBranch} 把 {@code ctx.data()} 直接交给 infra 求值器），
     *       但它<b>形状归 infra</b>（{@code LogicRule.ConditionGroup}），在这里再解析一遍就是
     *       跨模块口径的第二份副本，而 infra 那侧被前端 logic-cases.json 双端一致性测试绑着、改不动。
     *       规划器提示词教的、四份实测草稿用的全是扁平 {@code conditions}，故先只判扁平那一档。</li>
     *   <li><b>「有人写、但写它的节点不在上游」</b>：那是可达性问题，要判准得枚举路径，
     *       代价与 {@link #validateReferenceReachability} 只出 warning 的理由完全相同
     *       （见它规则头「祖先集里有任何一个节点写了这个键就不报」那一节）。
     *       ⚠️ 别把这一档读成「位置一概不判」：<b>反</b>方向（写入者<b>支配</b>闸门，
     *       于是键恒被写过、{@code exists}/{@code not_exists} 恒真/恒假）由
     *       {@link #validateRuleConditionTautology}（V32）判，两条共用 {@link #collectRuleConditions}
     *       这一份形状解析器。</li>
     *   <li><b>{@code op} 是不是合法算子</b>：未知算子执行期只 {@code log.warn} 后 yield false，
     *       同样是静默死分支，但那是<b>另一</b>个根因（写错算子 vs 读错键），
     *       且 {@code conditionGroups} 那一档的算子集是 infra 的 15 种、与扁平那 12 种<b>不同</b> ——
     *       在这里判就要抄两份算子表。一个根因只说一遍。</li>
     * </ol>
     *
     * <h3>编号纪律（本条是 <b>V24</b>）</h3>
     * 见 {@link #validateChatLegsWriteReply} 规则头那段权威源：V17 缺号、V20 与迭代二否掉的那条撞号、
     * V21 = tool 的 args 不是对象、V22 = script 里调 {@code tools.*}、V23 = 挂起节点自己的 feedback 键未声明，
     * 故本条取 V24。⚠️ 「下一个可用号是几」只在权威源那一处说，本条不抄。
     */
    private void validateRuleConditionVarsWritten(WorkflowDefDTO def, AgentValidateResult result) {
        // 🔴 分型判据见方法注释那张表：只有会话触发给得出「起跑时 state 只有触发器那一个键」这个前提
        if (!TRIGGER_TYPE_CHAT.equals(triggerTypeOf(def))) {
            return;
        }
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        Set<String> written = new HashSet<>(collectProducers(nodes).keySet());
        written.addAll(collectNonContractStateKeys(def));
        // 🔴 排序后再拼进文案：豁免集是 HashSet，迭代序不作保证 —— 同一份草稿点两次「体检」
        //    给出两种措辞，用户会当成两次不同的结论，单测也没法断言这句话
        List<String> options = written.stream().sorted().toList();

        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !RULE_CONDITION_TYPE.equals(node.getType())) {
                continue;
            }
            for (String var : collectRuleConditionVars(node)) {
                if (written.contains(var)) {
                    continue;
                }
                result.addError("节点(%s) 的分支条件读了「%s」，但本流程里没有任何节点写这个键："
                                + "condition.rule 的 var 只认【顶层】state 键（不做 $.a.b 那种嵌套取值），"
                                + "而会话触发的流程起跑时 state 里只有触发变量一个键，"
                                + "所以它恒为空值 —— 除 not_exists 外所有算子都判不命中，"
                                + "这条分支永远走不到，流程却照样报执行成功。"
                                + "在 variables[] 里声明它【没用】：声明只注册合并策略、不赋值。请二选一："
                                + "①让上游节点真写出这个顶层键 —— 一个 outputVar 只装一个值，"
                                + "所以 script 节点要 return 那个布尔量本身（不要 return 一个对象再去取它的字段），"
                                + "要人工决定就加 hitl.confirm 并把它的 approveVar 填成「%s」；"
                                + "②改判一个真被写过的键，本流程现有可选：%s",
                        displayId(node.getId(), i), var, var, abbreviate(options));
            }
        }
    }

    /**
     * 摘出一个 {@code condition.rule} 节点里全部扁平条件读的键名（trim 后、去重、保持文档顺序）
     *
     * <p>只走 {@code conditions} 那一档；{@code conditionGroups} 刻意不在射程内，理由见
     * {@link #validateRuleConditionVarsWritten} 的「刻意留在射程外」第 1 条。
     *
     * <p>🔴 本方法是 {@link #collectRuleConditions} 的<b>投影</b>，刻意不自己再解析一遍形状：
     * V24（键没人写）与 V32（键恒被写）问的是同一批条件的两面，
     * 两份解析器必然漂移 —— 漂移的表现是「一条规则看得见这个条件、另一条看不见」，
     * 而两条都不报错，谁也发现不了。
     */
    private Set<String> collectRuleConditionVars(NodeDTO node) {
        Set<String> vars = new LinkedHashSet<>();
        for (RuleCondition condition : collectRuleConditions(node)) {
            vars.add(condition.var());
        }
        return vars;
    }

    /**
     * {@code condition.rule} 的一条<b>扁平</b>条件：读哪个键、用什么算子（V24 与 V32 共用）
     *
     * <p>{@code op} 为 {@code null} = DSL 里没写这一格。执行期
     * {@code RuleConditionNodeExecutor#evaluateOne} 对缺失/未知算子只 {@code log.warn} 后 yield false，
     * 那是<b>另一个</b>根因（写错算子 vs 读错键），本类不判，理由见
     * {@link #validateRuleConditionVarsWritten} 的「刻意留在射程外」第 3 条。
     */
    private record RuleCondition(String var, String op) {
    }

    /** 只判「存在性」的两个算子：它们的结论<b>只看键在不在</b>，不看值，所以能被静态判定 */
    private static final Set<String> EXISTENCE_OPS = Set.of("exists", "not_exists");

    /**
     * 摘出一个 {@code condition.rule} 节点里全部扁平条件（含算子），保持文档顺序
     *
     * <p>只走 {@code conditions} 那一档；{@code conditionGroups} 刻意不在射程内，理由见
     * {@link #validateRuleConditionVarsWritten} 的「刻意留在射程外」第 1 条。
     *
     * <p>⚠️ 形状判据与 {@code RuleConditionNodeExecutor#evaluateAll} 逐条对齐：branch 不是 Map 就跳过、
     * condition 不是 Map 就跳过、{@code var} 缺失或空白就跳过 —— 那三种形状执行期同样静默略过，
     * 本处若报「读了个空键」就是把执行期不存在的行为说成存在的。
     */
    private List<RuleCondition> collectRuleConditions(NodeDTO node) {
        List<RuleCondition> conditions = new ArrayList<>();
        for (Object branchObj : node.configList("branches")) {
            if (!(branchObj instanceof Map<?, ?> branch)) {
                continue;
            }
            // conditionGroups 在场时执行期以它为准、压根不看 conditions（matchBranch 的短路），
            // 那一档本条不判，故这里也不去读它的 conditions —— 报了也不是执行期真走的那条路
            if (branch.get("conditionGroups") instanceof Map) {
                continue;
            }
            if (!(branch.get("conditions") instanceof List<?> rawConditions)) {
                continue;
            }
            for (Object condObj : rawConditions) {
                if (!(condObj instanceof Map<?, ?> cond)) {
                    continue;
                }
                Object raw = cond.get("var");
                if (raw == null || StrUtil.isBlank(String.valueOf(raw))) {
                    continue;
                }
                Object rawOp = cond.get("op");
                conditions.add(new RuleCondition(String.valueOf(raw).trim(),
                        rawOp == null || StrUtil.isBlank(String.valueOf(rawOp))
                                ? null : String.valueOf(rawOp).trim()));
            }
        }
        return conditions;
    }

    /**
     * 运行期真会往<b>顶层</b> state 写、却不在契约 {@code outputs()} 里的键（V24 的豁免集第 1~3 项）
     *
     * <p>与 {@link #collectResolvableRoots} 是<b>两张不同的表</b>，别合并：那张回答
     * 「{@code {{$.x}}} 的 x 解析得到吗」（含 {@code variables[]} 声明，因为占位符解析确实认声明），
     * 本方法回答「起跑后有没有人真写出这个键」（<b>不含</b>声明，理由见 V24 的
     * 「{@code variables[]} 声明不豁免」那一节）。两者差的那一格正是缺陷本体。
     */
    private Set<String> collectNonContractStateKeys(WorkflowDefDTO def) {
        Set<String> keys = new HashSet<>();
        keys.add(TriggerDTO.DEFAULT_INPUT_VAR);
        TriggerDTO trigger = def.getTrigger();
        if (trigger != null && StrUtil.isNotBlank(trigger.inputVar())) {
            keys.add(trigger.inputVar().trim());
        }
        if (def.getNodes() == null) {
            return keys;
        }
        for (NodeDTO node : def.getNodes()) {
            if (node == null || node.getConfig() == null) {
                continue;
            }
            if (ITERATION_TYPE.equals(node.getType())) {
                addTrimmedKey(keys, node.configStr("itemVar"));
            }
            if (SUB_WORKFLOW_TYPE.equals(node.getType())) {
                addTrimmedKeys(keys, node.configMap("inputMap"));
                addTrimmedKeys(keys, node.configMap("outputMap"));
            }
        }
        return keys;
    }

    private void addTrimmedKey(Set<String> sink, String key) {
        if (StrUtil.isNotBlank(key)) {
            sink.add(key.trim());
        }
    }

    private void addTrimmedKeys(Set<String> sink, Map<String, Object> map) {
        if (map == null) {
            return;
        }
        for (String key : map.keySet()) {
            addTrimmedKey(sink, key);
        }
    }

    // ==================== 存在性算子撞上支配它的写入者（V32） ====================

    /**
     * {@code condition.rule} 拿 {@code exists}/{@code not_exists} 去判一个<b>必然已经写过</b>的键（V32）
     *
     * <h3>危害的形状：分支结论由<b>图</b>定死，与运行期数据无关</h3>
     * 写入者若是闸门的必经之路，那么走到闸门时那个键<b>一定</b>已经被写过 ——
     * {@code ScriptNodeExecutor} / {@code ToolNodeExecutor} 都是无条件 {@code delta.put(outputVar, result)}，
     * 不看结果是不是 null。于是 {@code exists} 恒真、{@code not_exists} 恒假，
     * 对应那条腿要么每次都走、要么永远走不到，而<b>执行状态照样报成功</b>。
     * 与 V19/V24 同一档危害：静默。
     *
     * <h3>🔴 复现的事故（2026-10-10 实跑「图书借阅登记」，执行号 197）</h3>
     * 编排器产的图上，{@code decide_table}（script，{@code outputVar=table_decision}，
     * 三个 return 分支各返回一个对象、<b>永不返回 null</b>）→ {@code extract_table_name} → {@code check_decision}
     * （{@code branches=[{target:"reuse_path", conditions:[{var:"table_decision", op:"exists"}]}]}，
     * {@code default="create_table"}），而 {@code create_table} 是全图<b>唯一</b>的建表节点。
     * {@code decide_table} 在从 START 到 {@code check_decision} 的<b>每条</b>路上 ⟹ {@code exists} 恒真
     * ⟹ 恒走 {@code reuse_path}，{@code create_table} 成了死代码。
     * 实跑的 state 把这件事钉死了：{@code table_decision={"use":false,"name":"biz_library_borrow"}} ——
     * 脚本<b>确实</b>算出了「别复用、去建表」，而闸门只问「有没有写过」，把这个结论整个扔掉，
     * 路由键落成 {@code __route_check_decision="reuse_path"}；下一步 {@code save_draft} 报
     * 「主表 biz_library_borrow 在数据库里不存在」，{@code agent_workflow_execution.status=FAILED}。
     * 这次侥幸没有不可逆副作用（{@code form_save_draft} 只建不改，且建表节点排在闸门<b>之后</b>，
     * DDL 压根没跑），但发布出去的是一张<b>永远跑不通</b>的流程，而报错指向的是表、不是那条死分支。
     *
     * <h3>🔴 本条推翻了一处有据可查的旧判断</h3>
     * {@link #validateConditionalEdgeSources} 的「编号纪律」那段记着：迭代七<b>否掉</b>过一条
     * 「对恒被写入的 outputVar 用 {@code exists} 判空」的规则，理由是「提示词已经教了，不必再拦」，
     * 于是它一个号都没占、只落进了 {@code WorkflowDslPlannerPrompt}，那份提示词至今写着
     * 「② 体检<b>看不出来</b>（那个键确实有人写），只能靠你别这么写」。
     * 上面那次实跑<b>就是②</b>：提示词教了、模型照样这么写、体检沉默、跑到一半才炸。
     * 「模型是否采纳提示」这一环验不了（{@link #validateChatLegsWriteReply} 的规则头记着同一件事），
     * 而本条<b>能</b>静态判准 ⟹ 该拦。
     * ⚠️ 所以落地本条时<b>必须</b>同时改掉提示词里那句话：它是模型的输入，
     * 留着「体检看不出来」就是教模型「这么写不会被拦」。
     *
     * <h3>🔴 判据是<b>支配</b>，不是{@link GraphReachability.Graph#ancestorsOf 祖先}</h3>
     * 祖先集答的是「有<b>某条</b>路经过它」，本条要的是「<b>每条</b>路都经过它」。
     * 差别不是理论上的：写入者在<b>兄弟分支</b>上（例如 {@code try_cache} 命中才写 {@code cache_hit}，
     * 闸门在汇合点之后判 {@code exists}）是<b>完全合法</b>的写法 —— 那时写入者确实是祖先，
     * 但<b>不支配</b>（另一条路绕开它），{@code exists} 的结论真的随运行期数据变。
     * 拿祖先集判就会把这一档报成假警报。
     * 支配的算法是「摘掉写入者后从 START 还走不走得到闸门」，落在
     * {@link GraphReachability.Graph#reachableFromAvoiding}（那里记着为什么不能用「可达集减一个元素」）。
     * 多个写入者时，<b>任一</b>支配即报（支配的那一个已足以让键恒在）。
     *
     * <h3>⚠️ 为什么是 error 而不是 warning</h3>
     * warning <b>不会单独逼出一轮自修正</b>：{@code WorkflowPlannerServiceImpl#planToWorkflow}
     * 的闸门是 {@code !result.isPassed()}，而 {@code AgentValidateResult#isPassed} 只看 {@code errors} ——
     * 一份只带 warning 的 DSL 直接进 {@code buildDraft} 落库，{@code toFeedback()} 压根不被调用。
     * ⚠️ 但别把这句读成「warning 永远到不了模型」：{@code toFeedback()} 里确实也拼 warnings，
     * 只是那发生在<b>别的</b> error 已经逼出这一轮的时候。靠别人的错误顺带捎一句话，
     * 等于本条自己没有判据 —— 而本条要改的正是模型的产物，判 warning 就等于不判。
     * 且它不像 V20 那样「算不算缺陷取决于业务」：
     * 作者写这一档时<b>必然</b>是误会 —— 他以为在问值，其实在问「有没有跑过」。
     *
     * <h3>⚠️ 已承认的代价：唯一的假警报形状</h3>
     * {@code exists} 的执行期语义是 {@code actual != null}（{@code RuleConditionNodeExecutor}），
     * <b>不是</b>「键在不在」—— 所以「支配闸门的写入者<b>故意返回 null</b> 来表示『没有』」这一档，
     * 本条会报错，而它其实能工作。认下这一档，三条理由：
     * <ol>
     *   <li>提示词与 V24 的文案教的都是「script 要 return 那个<b>布尔量</b>本身，
     *       再按 {@code is_true}/{@code is_false} 路由」，拿 null 当信号从来不是被承认的写法；</li>
     *   <li>它脆弱到不该被承认：同一个键换个写入者（tool 节点永远写非 null 的 Map）语义就翻面，
     *       而这件事在图上看不出来；</li>
     *   <li>报错文案给的是<b>出路</b>（改成布尔量 + {@code is_true}/{@code is_false}），照做即通，不把人堵死。</li>
     * </ol>
     *
     * <h3>⚠️ 与触发器无关（不同于 V24 只对会话触发生效）</h3>
     * V24 的前提是「起跑时 state 里只有触发器那一个键」，那个前提只在会话那一档成立；
     * 本条的前提是「闸门之前必有一个节点写过这个键」，靠的是<b>图</b>，与初始 state 里有什么无关：
     * 手动 {@code run-stream} 传进来的键也改不了「写入者无条件覆盖它」这件事。故三档来路一律判。
     *
     * <h3>⚠️ 四处刻意不判（别当成漏了）</h3>
     * <ol>
     *   <li><b>{@code conditionGroups} 那一档</b>：与 V24 同一个理由（形状归 infra，
     *       在这里再解析一遍就是跨模块口径的第二份副本）；</li>
     *   <li><b>算子本身合不合法</b>：未知算子执行期 {@code log.warn} 后 yield false，
     *       是<b>另一个</b>根因（写错算子 vs 判错键），见 V24 射程外第 3 条；</li>
     *   <li><b>不可达的闸门</b>：直接跳过 —— 那是 {@link #validateUnreachableNodes}（V18）的根因，
     *       一个根因只说一遍；且它恒不支配（从 START 压根走不到），判了也是错话；</li>
     *   <li><b>内层图里的闸门</b>（{@code iteration.body} / {@code parallel.branches}）：
     *       它们不在父图 {@code edges[]} 里，{@code reachableFromAvoiding} 对它们<b>恒</b>给出「不可达」，
     *       于是每个写入者都会被误判成支配。走 {@link #collectInnerGraphOwners} 跳过
     *       （与 V19/V20 同一处置）。</li>
     * </ol>
     *
     * <h3>编号纪律（本条是 <b>V32</b>）</h3>
     * 见 {@link #validateChatLegsWriteReply} 规则头那段权威源。⚠️ 「下一个可用号是几」只在那里说，本条不抄。
     */
    private void validateRuleConditionTautology(WorkflowDefDTO def, GraphReachability.Graph graph,
                                                AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        // 🔴 复用 collectProducers（V18/V24 用的就是它）：「这个键谁写」只许有一份口径，
        //    而那份口径是从 NodeIoContract 渲染出来的 —— 新增一种节点类型时本条自动跟着覆盖
        Map<String, Set<String>> producers = collectProducers(nodes);
        if (producers.isEmpty()) {
            return;
        }
        Set<String> fromStart = graph.reachableFrom(START);
        Map<String, String> innerOwners = collectInnerGraphOwners(def);
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !RULE_CONDITION_TYPE.equals(node.getType())) {
                continue;
            }
            String gateId = node.getId();
            if (StrUtil.isBlank(gateId) || innerOwners.containsKey(gateId) || !fromStart.contains(gateId)) {
                continue;
            }
            for (RuleCondition condition : collectRuleConditions(node)) {
                String op = condition.op();
                if (op == null || !EXISTENCE_OPS.contains(op)) {
                    continue;
                }
                String writer = dominatingWriter(condition.var(), gateId, producers, graph);
                if (writer == null) {
                    continue;
                }
                // 🔴 三处随算子翻面的话必须共用这一个布尔量：它们的真值来源是同一件事
                //    （RuleConditionNodeExecutor 里 exists ⟺ actual != null、not_exists ⟺ actual == null），
                //    各写一遍 "exists".equals(op) 迟早漂移 —— 这一格已经漂过一次：
                //    「在执行期问的是……」那句当时写死了 exists 的语义，
                //    于是 not_exists 那档把执行期判据说反，而它恰恰是模型自修正时唯一读到的东西
                boolean existsOp = "exists".equals(op);
                result.addError("节点(%s) 的分支条件「%s %s」%s：键「%s」由节点(%s) 写出，"
                                + "而它在通往本闸门的【每条】路上（把它摘掉，从 START 就走不到本节点），"
                                + "所以走到这里时那个键必然已经写过 —— 这个条件的结论由【图】定死、与运行期数据无关，"
                                + "%s流程却照样报执行成功。"
                                + "⚠️ %s 在执行期问的只是「%s」，它【看不见】那个节点算出来的结论。"
                                + "请改成判【值】：让写这个键的节点 return 一个布尔量本身"
                                + "（一个 outputVar 只装一个值，不要 return 一个对象再去取它的字段），"
                                + "本闸门的算子换成 is_true / is_false；"
                                + "若本意确实是「问那个节点跑没跑过」，就别把它放在所有路的必经之处 —— "
                                + "挪到只有它会走到的那条分支上，条件才有信息量",
                        displayId(gateId, i), condition.var(), op,
                        existsOp ? "恒为真" : "恒为假",
                        condition.var(), displayId(writer),
                        existsOp ? "这条分支每次都命中、default 那条腿永远走不到，" : "这条分支永远不命中、每次都落 default，",
                        op, existsOp ? "值不为 null" : "值为 null");
            }
        }
    }

    /**
     * 返回<b>支配</b>闸门的那个写入者 id；没有任何写入者支配时返回 null
     *
     * <p>🔴 排序后再挑：{@code producers} 的值是 {@code HashSet}，迭代序不作保证 ——
     * 多个写入者都支配时，同一份草稿点两次「体检」会点名两个不同的节点，
     * 用户当成两次不同的结论，单测也没法断言这句话（V24 拼豁免集时踩过同一格）。
     */
    private static String dominatingWriter(String var, String gateId,
                                           Map<String, Set<String>> producers,
                                           GraphReachability.Graph graph) {
        Set<String> writers = producers.get(var);
        if (writers == null || writers.isEmpty()) {
            return null; // 没人写 ⟹ 那是 V24 的根因，本条沉默（一个根因只说一遍）
        }
        for (String writer : writers.stream().sorted().toList()) {
            // condition.rule 在契约里不登记输出端口，故写入者不会是闸门自己；
            // 这一格防的是日后给它加了端口 —— 那时「自己支配自己」会报出一句没人看得懂的话
            if (writer.equals(gateId)) {
                continue;
            }
            if (!graph.reachableFromAvoiding(START, writer).contains(gateId)) {
                return writer;
            }
        }
        return null;
    }

    // ==================== 节点 ====================

    /**
     * 校验节点列表，返回<b>合法节点 id 的集合</b>（供边校验解析端点用）
     *
     * <p>即便某些节点 id 非法/重复，也照样把「能识别的 id」收进集合——
     * 边校验只关心「端点能不能落到某个已知节点或 START/END」，
     * 不因节点自身报错就放弃对边的检查（收集式：一次把所有问题都报出来）。
     *
     * <p>🔴 这里<b>刻意不判</b>「写进 state 的键有没有声明」（V19）：那一条要问
     * 「这个节点下游还有没有挂起点」，得先有整张图，故独立成
     * {@link #validateWrittenVarsDeclared} 一趟。
     */
    private Set<String> validateNodes(List<NodeDTO> nodes, AgentValidateResult result) {
        Set<String> nodeIds = new LinkedHashSet<>();
        if (nodes == null || nodes.isEmpty()) {
            result.addError("nodes 为空：至少要有一个节点，请补上形如 "
                    + "{\"id\":\"start_task\",\"type\":\"tool\",\"config\":{...}} 的节点");
            return nodeIds;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null) {
                result.addError("第 %d 个节点为 null：请补一个完整的节点对象", i + 1);
                continue;
            }
            String id = node.getId();
            boolean idUsable = true;
            if (StrUtil.isBlank(id)) {
                result.addError("第 %d 个节点缺少 id：请补一个符合 ^[a-z][a-z0-9_]{0,62}$ 的小写标识符，如 step_1",
                        i + 1);
                idUsable = false;
            } else if (!ID_PATTERN.matcher(id).matches()) {
                result.addError("节点 id「%s」不合法：必须小写字母开头、仅含小写字母/数字/下划线、长度≤63，"
                        + "请改成如 %s", id, suggestId(id));
                idUsable = false;
            } else if (!seen.add(id)) {
                result.addError("节点 id「%s」重复：同一份 DSL 内 id 必须唯一，请给其中一个换个名字", id);
                idUsable = false;
            }
            if (idUsable) {
                nodeIds.add(id);
            }

            String type = node.getType();
            if (StrUtil.isBlank(type)) {
                result.addError("节点(%s) 缺少 type：请指定为 %s 之一",
                        displayId(id, i), knownTypesHint());
            } else if (!isKnownType(type)) {
                result.addError("节点(%s) 的类型「%s」不被支持：可选类型有 %s，请改用其中之一",
                        displayId(id, i), type, knownTypesHint());
            }

            // 不可补偿节点声明了反做：token/召回已耗无法撤销，compensation 不会生效——这是「配了不生效」的典型，
            // 必须显式点破（设计方案「一之三」第 3 条：llm/knowledge_retrieval 必须 recovery:forward）。
            if (isNonCompensableType(type) && declaresCompensation(node)) {
                result.addWarning("节点(%s) 类型「%s」不可补偿（模型 token 已耗、无法退回），"
                        + "声明 recovery:compensate / compensation 不会生效，请改用 recovery:forward 并移除 compensation",
                        displayId(id, i), type);
            }
        }
        return nodeIds;
    }

    private boolean isKnownType(String type) {
        return GRAPH_BUILDER_TYPES.contains(type) || nodeExecutorRegistry.supports(type);
    }

    // ==================== 写而未声明（V19） ====================

    /**
     * 节点写进 state 的键必须在顶层 {@code variables[]} 里声明（V19）
     *
     * <h3>为什么是 error：这个值<b>会</b>丢，而丢的那一刻没有任何提示</h3>
     * SAA 重载状态走 {@code OverAllState.input(checkpointState)}，而 {@code input()} 只保留
     * <b>注册过 KeyStrategy</b> 的键（{@code KeyStrategyFactoryGenerator} 的
     * {@code defaultStrategy(REPLACE)} <b>不</b>给未声明键补注册，{@code WaitNodeRuntimeTest} 已实测）。
     * 于是未声明的键能活过第一次运行 —— 沙箱里一切正常 —— 却在<b>挂起后恢复</b>的那一瞬间被静默丢弃，
     * 下游读到 null。表现是「恢复成功、流程拿着半个 state 继续跑」，
     * 而 S4a 正在给执行历史抽屉加那枚「恢复」按钮：按钮一上线，这条路就有人走了。
     *
     * <h3>🔴 原来这一格是 warning、而且只覆盖 {@code outputVar}，两处都不够</h3>
     * 旧文案的理由是「引擎对未声明键按 REPLACE 兜底、值不会丢」—— 那句<b>只在同一次运行内成立</b>，
     * 跨 resume 就是假的（{@code KeyStrategyFactoryGenerator} 类注释「只兜「同一次运行内」」那一节把这条写得很直白）。
     * 覆盖面也不够：契约登记的输出端口还有 {@code approveVar}/{@code statusVar}/{@code citationsVar}，
     * 它们同样写 state、同样会被丢。本方法改成遍历 {@link NodeIoContract.Contract#outputs()}，
     * 于是「新增一种节点类型」时这一条自动跟着覆盖，不必再回来补一遍（D7）。
     *
     * <h3>🔴 两个前提，缺一个这条就只是噪音</h3>
     * <b>①「写进去之后还会有一次挂起」</b>：{@code input()} 那层过滤<b>只在 resume 时发生</b>，
     * 所以只有下游有挂起点的写入才真的会丢。一条从头跑到尾不挂起的流程，未声明的键一个都不会丢 ——
     * 对它报 error 是把不存在的危害说成存在的，而 error 阻断发布，代价是作者学会无视红字。
     * 挂起点按 {@link #SUSPEND_NODE_TYPES} 判，且<b>不含节点自己</b>：{@code hitl.*} 是在自己那次
     * resume <b>之后</b>才把 {@code approveVar} 写进 state 的，它自己的挂起伤不到自己的<b>写入</b>。
     *
     * <p>🔴 但「伤不到自己」这句<b>只对出站成立</b>，别把它读成「挂起节点自己那一格没人管」：
     * 同一个键在<b>入站</b>方向另有一条死法 —— 调用方按自定义 {@code approveVar} 传 feedback 时，
     * 那个值要在<b>进 state 之前</b>先过一遍 {@code input()}，没注册就地被滤掉，
     * 执行器读到 null 再回退默认键，于是「同意」被静默判成「拒绝」。那一档由
     * {@link #validateSuspendFeedbackVarsDeclared} (V23) 覆盖，两条规则<b>互补而不重叠</b>：
     * V19 报「下游另有挂起点」的键，V23 报「下游没有挂起点、但节点自己就是挂起点」的键。
     *
     * <p><b>②「这个名字是作者显式填的」</b>：用的是契约缺省名（{@code tool_result} 等）时<b>不报</b>。
     * 要求作者为了拿到<b>缺省</b>行为先去 {@code variables[]} 里把 {@code tool_result} 抄一遍，
     * 是把平台的活儿推给每一个作者，且几乎每一条存量流程都会因此体检不过（Req6 向后兼容红线）——
     * 与 V11 那条教训同向：规则必须先承认哪些写法是合法的，才可满足。
     * ✅ 缺省名这一档的根治点<b>已经落地</b>（缺陷 C8）：{@code KeyStrategyFactoryGenerator#generate}
     * 现在遍历 {@code NodeIoContract.defaultOutputNames()} 把全部缺省输出名无条件注册，
     * 于是本前提②的「不报」从<b>知情不报</b>（洞真实存在，只是不该由作者补）变成了<b>确实不会丢</b>。
     * 豁免集 {@link #RESUME_REGISTERED_VARS} 同源取自那一个方法，两侧不可能再各漂一份。
     *
     * <h3>⚠️ {@code iteration.itemVar} 刻意不在射程内</h3>
     * 它是契约登记的<b>入</b>端口，但运行期确实会把当前项写进 state。不判它的理由不是漏了，
     * 而是这条危害够不着它：{@code body} 只允许 plain 节点（llm/tool/knowledge_retrieval，
     * {@code DslToStateGraphCompiler#collectIterationBodyIds}），里面放不进 {@code wait} 或 {@code hitl.*}，
     * 于是<b>循环体永远不会挂起</b>，也就永远不会有「恢复」这一刻来把 {@code itemVar} 丢掉。
     *
     * <h3>🔴 {@code sub_workflow.outputMap} 在射程内，但走<b>专用分支</b>（台账 E3）</h3>
     * 它同样是契约登记的<b>入</b>端口、同样真会写 state，与 {@code itemVar} 不同的是<b>它的危害够得着</b>
     * （子流程出参写回父流程之后，下游完全可以再挂起）。而遍历 {@code outputs()} 这个通用形状
     * 对它结构性失明（{@code sub_workflow} 的 {@code outputs()} 是空的），
     * 故循环末尾按类型分派到 {@link #validateSubWorkflowOutputMapDeclared} ——
     * 射程边界、运行期三档实测、以及「为什么 inputMap 的键刻意不报」都写在那个方法的 javadoc 里，此处不抄。
     */
    private void validateWrittenVarsDeclared(WorkflowDefDTO def, GraphReachability.Graph graph,
                                             Set<String> declaredVars, AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        List<String> suspendOrder = collectSuspendOrder(nodes);
        if (suspendOrder.isEmpty()) {
            return;
        }
        Map<String, String> innerOwners = collectInnerGraphOwners(def);
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null) {
                continue;
            }
            NodeIoContract.Contract contract = NodeIoContract.of(node.getType());
            if (contract == null) {
                // 未知 type 上面已经报过「类型不被支持」，深层干跑也会拒；这里没有端口清单可查
                continue;
            }
            String suspend = suspendAfterWrite(graph, innerOwners, node.getId(), suspendOrder);
            if (suspend == null) {
                continue;
            }
            for (NodeIoContract.OutPort port : contract.outputs()) {
                // 前提②：这一格作者没填 ⟹ 执行器落的是契约缺省名，那一档不报（见方法注释）
                if (StrUtil.isBlank(node.configStr(port.configKey()))) {
                    continue;
                }
                String written = writtenVarOf(node, port);
                // 三个端口的 defaultName 是 null（citationsVar / condition.llm.outputVar / agent_loop.outputVar）：
                // 填了纯空白时 writtenVarOf 会原样返回空白，那种键执行器确实会写，但报出来也没法粘贴，
                // 交给「变量名不合法」那条口径（#48 已登记）
                if (StrUtil.isBlank(written) || RESUME_REGISTERED_VARS.contains(written)
                        || declaredVars.contains(written)) {
                    continue;
                }
                result.addError("节点(%s) 的 %s「%s」没在 variables[] 里声明：执行器会照这个名字把结果写进 state，"
                                + "而它的下游还有一个挂起点(%s) —— 恢复时 SAA 走 OverAllState.input(checkpointState)，"
                                + "只保留注册过合并策略的键，于是这个值同一次运行内看不出问题、"
                                + "恢复的那一刻被静默丢弃，下游读到的是空值。"
                                + "请在顶层 variables[] 里补一条 {\"key\":\"%s\",\"type\":\"%s\"}"
                                + "（需要跨轮累积就把 merge 写成 append）",
                        displayId(node.getId(), i), port.configKey(), written, suspend, written, port.dataType());
            }
            // 🔴 sub_workflow 走专用分支（台账 E3）：它的 outputMap 是<b>入</b>端口，
            //    上面那趟遍历 outputs() 结构上看不见它，而它运行期确实往父流程 state 写键。
            if (SUB_WORKFLOW_TYPE.equals(node.getType())) {
                validateSubWorkflowOutputMapDeclared(node, i, suspend, declaredVars, result);
            }
        }
    }

    /**
     * {@code sub_workflow.outputMap} 的<b>键</b>同样受 V19 约束（台账 E3）
     *
     * <h3>为什么契约遍历看不见它，而它偏偏真会写 state</h3>
     * {@code outputMap} 在契约里登记成<b>入</b>端口（{@code sub_workflow} 的 {@code outputs()} 是空的），
     * 因为它的<b>值</b>是「从子流程 state 取哪个结果」的表达式，读者视角是入。
     * 但它的<b>键</b>是「写回父流程哪个变量名」，运行期由编译器合成的出参节点
     * （{@code DslToStateGraphCompiler#mappingAction}，节点 id 形如 {@code _<sw>_out}）
     * 逐个 {@code delta.put(键, 求值结果)} 写进<b>父流程的共享 state</b> —— 与 {@code outputVar} 同族。
     * 于是「遍历 {@code contract.outputs()}」这个通用形状对它<b>结构性失明</b>：
     * 配了 outputMap + 下游有挂起点 + 没在 {@code variables[]} 声明的流程能一路发布成功，
     * 而子流程结果会在 resume 那一刻被 {@code OverAllState.input()} 静默丢空。
     *
     * <h3>🔴 机制已运行期实测（三档对照，2026-10-07；探针原文归档 logs/16、可读版 logs/17）</h3>
     * 父图 {@code sw(outputMap:{parentStruct:"{{$.structOut}}"}) → wait → cap}，cap 读 {@code parentStruct}：
     * <table border="1">
     *   <caption>三档<b>只差</b>「{@code variables[]} 里有没有 parentStruct」与「下游有没有挂起点」两个维度</caption>
     *   <tr><th>档</th><th>声明了 parentStruct</th><th>下游有 wait</th><th>挂起点差集含它</th><th>cap 读到</th></tr>
     *   <tr><td>A</td><td>✗</td><td>✓</td><td><b>是</b></td><td><b>null</b>（缺陷本体）</td></tr>
     *   <tr><td>B</td><td>✓</td><td>✓</td><td>否</td><td>子流程结果（出路实测有效）</td></tr>
     *   <tr><td>C</td><td>✗</td><td>✗</td><td>—</td><td>子流程结果（无挂起点则无害）</td></tr>
     * </table>
     * C 档正是本规则<b>必须</b>挂在 {@link #suspendAfterWrite} 后面的理由：没有下游挂起点时报 error
     * 就是把不存在的危害说成存在的（V19 前提①）。常驻强制点是
     * {@code SubWorkflowOutputMapResumeRuntimeTest}。
     *
     * <h3>🔴 键名口径：用<b>trim 之后</b>的名字</h3>
     * 与运行期同源 —— {@code DslToStateGraphCompiler#validateMapping} 是
     * {@code String.valueOf(e.getKey()).trim()} 之后才按 {@code VAR_NAME} 校格式、再 {@code put} 的，
     * 所以真被写进 state 的键必然已 trim。V19 若不 trim，作者写 {@code {" parentStruct ": …}} 时
     * 报出来的是一个运行期压根不存在的键，且文案给的那条 {@code variables[]} 声明也补不对。
     * ⚠️ 顺带说明：{@code configMap} 直接返回 Jackson 的 Map，故键序 = 作者书写序，
     * 报错文案挑到的「第一个未声明的键」是确定的（同 {@link #collectSuspendOrder} 那条理由）。
     *
     * <h3>⚠️ 射程：本支<b>只</b>管 outputMap 的键，另外三类同样会被丢、但刻意不在这里报</h3>
     * 实测（档 A/D）挂起点差集里除了 {@code parentStruct} 还有三个键，各有各的不报理由：
     * <ol>
     *   <li><b>{@code inputMap} 的键</b>（实测档 D：{@code modelId}=5 进差集、下游 cap 读到 null）。
     *       🔴 危害是真的，但<b>不报</b>：那个名字是<b>子流程的入参词汇</b>，不是父流程的产出变量，
     *       要求父作者在父流程 {@code variables[]} 里声明子流程的入参名，会在
     *       「每个带 inputMap + 下游挂起点的 sub_workflow」上<b>稳定</b>报错（Req6 向后兼容红线），
     *       而按契约语义父流程下游节点<b>不该</b>读它 —— 能读到只是 SAA 原生子图共享 key 空间的副作用
     *       （{@code addSubWorkflowNode} 的 javadoc 明写「这是重命名/显式契约，不是硬命名空间隔离」）。
     *       真要靠它，正确的修法是让 {@code KeyStrategyFactoryGenerator} 把子 def 的声明并进来，
     *       而不是让每个父作者手抄一遍子流程的入参名。</li>
     *   <li><b>子流程自己写的键</b>（实测 {@code structOut}，子流程那个 capture 的 outputVar）。
     *       V19 <b>拿不到子 def</b>：校验器刻意不查库（{@code validateMapping} 的 fail-fast 注释同一条理由），
     *       而子 def 在库里 ⟹ 结构性不可判。</li>
     *   <li><b>调用方喂进来的入口键</b>（实测 {@code theId}/{@code shared}）。
     *       它们不是任何节点的 config 端口写的，V19 的判据（「这个端口会写哪个键」）压根不覆盖；
     *       那一档属 B3-7「resume 差集过滤器」的域。</li>
     * </ol>
     * 于是本支<b>不</b>等于「sub_workflow 的 resume 丢键问题已闭合」，只等于
     * 「作者<b>显式填在父流程 DSL 里、且语义上属于父流程</b>的那个写入名纳入了 V19」。
     *
     * <p>两个豁免与 V19 同源、理由也同源：{@link #RESUME_REGISTERED_VARS}（缺省名已被
     * {@code KeyStrategyFactoryGenerator#generate} 无条件注册，报了就是假警报 ——
     * 作者把 outputMap 的键正好起成 {@code tool_result} 这类缺省名时命中）、
     * {@code declaredVars}（B 档实测证明声明了就真的活）。
     * 空白键同样跳过，交给「变量名不合法」那条口径（与上面 outputs() 那趟一致）。
     *
     * <p>⚠️ 文案刻意<b>不给</b> {@code "type":"…"}：outputMap 的值走
     * {@code DslToStateGraphCompiler#resolveMapValue}，单整占位符保<b>原始类型</b>（List/Map/Number 不被 stringify），
     * 所以类型只有作者知道，编一个填进去就是让人照错方向改。
     * 这不是偷懒：{@code KeyStrategyFactoryGenerator#generate} 只读 {@code getKey()}/{@code getMerge()}，
     * type 不参与注册，本条危害与它无关。
     */
    private void validateSubWorkflowOutputMapDeclared(NodeDTO node, int index, String suspend,
                                                      Set<String> declaredVars, AgentValidateResult result) {
        Map<String, Object> outputMap = node.configMap("outputMap");
        if (outputMap == null || outputMap.isEmpty()) {
            return;
        }
        for (String rawKey : outputMap.keySet()) {
            String written = rawKey == null ? null : rawKey.trim();
            if (StrUtil.isBlank(written) || RESUME_REGISTERED_VARS.contains(written)
                    || declaredVars.contains(written)) {
                continue;
            }
            result.addError("节点(%s) 的 outputMap 键「%s」没在 variables[] 里声明：子流程跑完后，"
                            + "编译器合成的出参节点会照这个名字把子流程结果写回父流程 state，"
                            + "而它的下游还有一个挂起点(%s) —— 恢复时 SAA 走 OverAllState.input(checkpointState)，"
                            + "只保留注册过合并策略的键，于是这个值同一次运行内看不出问题、"
                            + "恢复的那一刻被静默丢弃，下游读到的是空值。"
                            + "请在顶层 variables[] 里补一条 {\"key\":\"%s\"}"
                            + "（type 按子流程结果的实际类型填，它不参与注册；需要跨轮累积就把 merge 写成 append）",
                    displayId(node.getId(), index), written, suspend, written);
        }
    }

    /**
     * 按<b>文档顺序</b>收集这份 DSL 里全部挂起点的 id（V19 与 V23 共用，别各写一遍）
     *
     * <p>🔴 必须是 List 而不是 Set：它同时用来给报错文案挑一个<b>确定的</b>代表节点，
     * 而 {@code HashSet} 的迭代序会让同一份 DSL 每次报出不同的节点名 ——
     * 测试没法断言、作者也没法复现（「我照你说的改了怎么还报」）。
     */
    private static List<String> collectSuspendOrder(List<NodeDTO> nodes) {
        List<String> suspendOrder = new ArrayList<>();
        for (NodeDTO node : nodes) {
            if (node != null && StrUtil.isNotBlank(node.getId())
                    && SUSPEND_NODE_TYPES.contains(node.getType())) {
                suspendOrder.add(node.getId());
            }
        }
        return suspendOrder;
    }

    /**
     * 这个节点写完 state 之后还会遇到的第一个挂起点 id；一个都没有则返回 {@code null}
     *
     * <p>🔴 子图节点要<b>顺着容器问</b>：{@code iteration.body} / {@code parallel.branches} 里的节点
     * 被编译器从父图排除、也<b>不得</b>写进 {@code edges[]}，于是 {@code reachableFrom(它)} 恒等于它自己，
     * 直接问就会得到「下游没有挂起点」这个假答案 —— 而它写的键其实一直活到容器跑完，
     * 容器之后若挂着 {@code wait}，那个键照样会在 resume 时被丢。
     *
     * <p>🔴 挖掉 {@code probe} 自己：{@code reachableFrom} 恒含起点，而 {@code hitl.*} 节点
     * 是在<b>自己那次 resume 之后</b>才写 {@code approveVar} 的，自己伤不到自己（见方法注释的前提①）。
     *
     * <p>⚠️ 但「自己伤不到自己」这句<b>只覆盖出站方向</b>（节点写出去的值会不会被后来那次 resume 丢掉）。
     * 还有一个<b>入站</b>方向：挂起节点自己那次 resume 注入的 feedback，同样要过
     * {@code OverAllState.input()}，键没注册就在<b>进 state 之前</b>被滤掉 —— 那一档由
     * {@link #validateSuspendFeedbackVarsDeclared} (V23) 管，本方法返回 {@code null} 正是它的入口条件。
     * 🔴 于是本方法的 {@code null} <b>不等于</b>「这个节点没有 resume 风险」，只等于「没有<b>下游</b>挂起点」。
     * 拿它当「安全」用就是把 V23 那个洞重新挖开。
     */
    private static String suspendAfterWrite(GraphReachability.Graph graph, Map<String, String> innerOwners,
                                            String nodeId, List<String> suspendOrder) {
        String probe = innerOwners.getOrDefault(nodeId, nodeId);
        Set<String> downstream = graph.reachableFrom(probe);
        for (String suspendId : suspendOrder) {
            if (!suspendId.equals(probe) && downstream.contains(suspendId)) {
                return suspendId;
            }
        }
        return null;
    }

    /**
     * 这个端口在执行期<b>真正写进 state</b> 的键名；不写则返回 {@code null}
     *
     * <p>🔴 用 {@code configStr(key, default)} 而<b>不是</b> {@code StrUtil.blankToDefault}：
     * 两者在「这一格填了纯空白」时分岔，而四个执行器（{@code ToolNodeExecutor#execute}、
     * {@code LlmNodeExecutor#execute}、{@code ScriptNodeExecutor#execute}、{@code KnowledgeRetrievalNodeExecutor#execute}）
     * 用的都是前者 —— 空白串不是 {@code isEmpty()}，会被<b>原样当成键名</b>写进 state。
     * 按 blankToDefault 判的话，算出来的就是一个压根不会被写的键。
     *
     * <p>🔴 <b>为什么是 public：它是「这个节点会往 state 写哪个键」的唯一权威，域外两个消费者都调它</b>
     * <ol>
     *   <li>{@code DslToStateGraphCompiler.validateStateKeyNames}（台账 E12）—— 只有拿到这个名字
     *       才能判它合不合法；</li>
     *   <li>{@code UntrustedInputTracer.writesOf}（台账 C3，本轮收编）—— 污点传播的写入集必须与
     *       执行期真写的键逐字相同，否则少报（真正被写脏的键没人播种 ⟹ 污点整条断流）
     *       与多报（认定它产出了缺省名 ⟹ 下游读那个名字被误报）同时发生。</li>
     * </ol>
     * 两处都<b>必须</b>调本方法而不是自己再写一遍 {@code configStr(port.configKey(), port.defaultName())}，
     * 两个理由，缺一个都足以让复制品悄悄分岔：
     * <ol>
     *   <li>上面那段「为什么不用 blankToDefault」的推理只有这一份。第二份复制品不会带上它，
     *       而下一个人「顺手统一成 blankToDefault」时看不出这会算出一个压根不会被写的键
     *       —— 溯源侧就是这么滞后了一整轮（它当时还拿「宁可多登记也不留缺口」当理由，
     *       而它恰恰留了缺口，见 {@code writesOf} 的 javadoc）；</li>
     *   <li>编译器住在 {@code engine} 域内，而 {@code NodeIoContractRuntimeReadScanTest} 要求域内的
     *       config 读取点<b>键名必须是字面量</b>（否则「运行期读了哪些键」就没法从源码量出来，
     *       它那套正向差集全建立在这个可量性上），并且<b>明令禁止</b>为泛型键名开白名单。
     *       顺着 {@code outputs()} 走的通用循环天生给不出字面量 ⟹ 这一读必须落在域外，
     *       与本类另外两处通用循环（V19、挂起点那条）作伴。</li>
     * </ol>
     *
     * <p>✅ <b>本处原来那段「⚠️ 不要把 {@code UntrustedInputTracer.writesOf} 改成调本方法」已删</b>：
     * 它给的理由是「那一侧刻意用 blankToDefault，宁可多登记也不留缺口，判据不同是有意的」。
     * 后半句站不住 —— 那一侧登记的是<b>缺省名</b>、丢掉的是<b>真被写的空白键</b>，
     * 所以它既多报又少报，「不留缺口」正是它没做到的那件事。台账 C3 的裁定就是抽成一处、两侧都调。
     * 强制点是 {@code WrittenVarOfSingleSourceTest}（钉「只有一处定义 + 两侧逐字相同」）。
     */
    public static String writtenVarOf(NodeDTO node, NodeIoContract.OutPort port) {
        return node.configStr(port.configKey(), port.defaultName());
    }

    // ==================== 挂起节点自己的 feedback 键未声明（V23） ====================

    /**
     * 挂起节点<b>自己</b>那一次 resume 要读的键，必须在顶层 {@code variables[]} 里声明（V23）
     *
     * <h3>这条堵的是 V19 结构性看不见的那一档：方向相反</h3>
     * V19 问的是<b>出站</b> ——「节点写进 state 的值，会不会被<b>后来</b>那次 resume 的
     * {@code OverAllState.input()} 丢掉」，所以它<b>必须</b>挖掉节点自己（{@code hitl.*} 是在自己那次
     * resume 之后才写 {@code approveVar} 的，自己伤不到自己的写入）。那个自排除对 V19 是对的。
     *
     * <p>但同一个 {@code approveVar} 在<b>入站</b>方向另有一条死法，而 V19 的自排除恰好把它整档让了出去：
     * 调用方按作者填的那个自定义键名传 feedback 时，这个值要在<b>进 state 之前</b>先过一遍
     * {@code input()} —— 键没注册就地被滤掉，执行器读到 null、回退默认键、默认键也没人写，
     * 于是<b>「同意」被静默判成「拒绝」，而执行状态照样报 SUCCEEDED</b>。
     * 这正是 {@code HitlConfirmNodeExecutor#execute} 与 {@code HitlBpmApprovalNodeExecutor#execute}
     * 两段回退注释各自点名的那个缺陷（「审批通过了却没干活」），只是那两段注释讨论的是
     * 「监听器/聊天侧只会写死默认键」，而<b>没</b>讨论「调用方老老实实按自定义键传」这一档 ——
     * 那一档两个执行器的回退都救不了，因为回退的方向是「自定义 → 默认」，不是「默认 → 自定义」。
     *
     * <h3>🔴 机制已运行期实测，不是推理（三档对照已折成常驻测试，探针原文归档 logs/56）</h3>
     * 强制点是 {@code KeyStrategyResumeRuntimeTest} 第 ③ 组：同一份 DSL
     * （{@code hitl.confirm} 配自定义 {@code approveVar}），只改两个维度：
     * <table border="1">
     *   <caption>2026-10-07 实测。注册集 9 键 = 7 个契约缺省名 + 引擎入口键 + 夹具声明的 done</caption>
     *   <tr><th>档</th><th>variables[] 声明了自定义键</th><th>feedback 用的键</th><th>节点判成</th></tr>
     *   <tr><td>A</td><td>✗</td><td>自定义名</td><td><b>rejected</b>（同意被丢）</td></tr>
     *   <tr><td>B</td><td>✗</td><td>默认名</td><td>approved</td></tr>
     *   <tr><td>C</td><td>✓</td><td>自定义名</td><td>approved</td></tr>
     * </table>
     * A 与 C 的<b>唯一</b>差别就是那一条声明，挂起点注册集从 9 键变 10 键 ⟹ 本规则文案给的出路
     * （补一条 {@code variables[]}）是<b>实测有效</b>的，不是安慰。B 档解释了它为什么至今没炸：
     * 前端 {@code ExecutionHistoryDrawer.vue} 的 {@code resumeFeedback} 刻意把两个 HITL 默认键都写上
     * （它自己的注释管这叫「对冲」），把 A 档那条路整个盖住了。
     * 🔴 但盖住的只有那一个调用方：工作台 resume-stream 与任何外部集成都可以按自定义键传，
     * 而 {@code AgentBpmApprovalListener} 之外的调用方<b>没有</b>任何理由去猜默认名。
     *
     * <h3>🔴 与 V19 互补，且刻意做成不重叠</h3>
     * 同一个键被两条规则各报一次，作者会以为是两件事 —— 所以本方法在
     * {@link #suspendAfterWrite} 已经找到下游挂起点时<b>整段闭嘴</b>，那一档归 V19。
     * 两条的并集覆盖「这个自定义键名没声明」的全部情形，交集为空。
     * ⚠️ 于是 {@code suspendAfterWrite} 返回 {@code null} 在这里的含义是
     * 「下游没有挂起点」而<b>不是</b>「没有 resume 风险」—— 节点自己就是那个挂起点。
     *
     * <h3>射程：{@link #SUSPEND_NODE_TYPES} ∩ 契约的 {@code outputs()}</h3>
     * 判据是「这个端口写的键，同时是本节点在 resume 之后<b>回头读</b>的那个键」。
     * 当前恰好就是两个 {@code hitl.*} 类型的三个端口（{@code approveVar} ×2、{@code statusVar} ×1），
     * 而 {@code wait} 的契约 {@code outputs()} 是空的 ⟹ 循环体压根不执行，不需要为它写特例。
     *
     * <p>⚠️ <b>失效条件写在明处</b>：将来若给某个挂起节点登记一个「只写、执行器不回头读」的输出端口，
     * 本规则会对它<b>误报</b>（那个键丢了确实无害）。届时该给 {@code NodeIoContract.OutPort}
     * 加一个「resume 时会回读」的标记、让本方法只遍历带标记的端口，<b>不要</b>在这里按类型开白名单 ——
     * 白名单是第二份口径，会和契约漂。眼下没有反例，故不加标记（不给假想需求做设计）。
     *
     * <p>两个豁免与 V19 同源，理由也同源：{@link #RESUME_REGISTERED_VARS}（缺省名已被
     * {@code KeyStrategyFactoryGenerator#generate} 无条件注册，滤不掉，报了就是假警报）、
     * {@code declaredVars}（C 档实测证明声明了就真的活）。
     * 「作者没填这一格」同样不报：那时执行器落的是缺省名，走的是 B 档那条活路。
     *
     * <h3>编号纪律（本条是 <b>V23</b>）</h3>
     * 见 {@link #validateChatLegsWriteReply} 规则头那段：V17 缺号、V20 与迭代二否掉的那条撞号、
     * V21 = tool 的 args 不是对象、V22 = script 里调 {@code tools.*}，故本条取 V23。
     * ⚠️ 「下一个可用号是几」只在权威源那一处说，本条不抄（理由见
     * {@link #validateToolArgsShape} 规则头同一段）。
     */
    private void validateSuspendFeedbackVarsDeclared(WorkflowDefDTO def, GraphReachability.Graph graph,
                                                     Set<String> declaredVars, AgentValidateResult result) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        List<String> suspendOrder = collectSuspendOrder(nodes);
        Map<String, String> innerOwners = collectInnerGraphOwners(def);
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            // 只有挂起节点自己才有「那一次 resume」；普通节点没有入站 feedback 这回事
            if (node == null || !SUSPEND_NODE_TYPES.contains(node.getType())) {
                continue;
            }
            NodeIoContract.Contract contract = NodeIoContract.of(node.getType());
            if (contract == null) {
                // 未知 type 上面已经报过「类型不被支持」；这里没有端口清单可查
                continue;
            }
            // 🔴 与 V19 分家：下游另有挂起点时那个键归 V19 报，本条闭嘴，免得同一个键报两条
            if (suspendAfterWrite(graph, innerOwners, node.getId(), suspendOrder) != null) {
                continue;
            }
            for (NodeIoContract.OutPort port : contract.outputs()) {
                if (StrUtil.isBlank(node.configStr(port.configKey()))) {
                    continue;
                }
                String written = writtenVarOf(node, port);
                if (StrUtil.isBlank(written) || RESUME_REGISTERED_VARS.contains(written)
                        || declaredVars.contains(written)) {
                    continue;
                }
                result.addError("节点(%s) 的 %s「%s」没在 variables[] 里声明，而这个节点自己就是挂起点："
                                + "恢复时调用方按「%s」传进来的决定要先过 OverAllState.input()，"
                                + "它只保留注册过合并策略的键，于是这个值进不了 state —— 节点读到空值、"
                                + "回退默认键也没人写，用户的「同意」会被静默判成「拒绝」，"
                                + "而执行状态照样报成功。请二选一：①在顶层 variables[] 里补一条 "
                                + "{\"key\":\"%s\",\"type\":\"%s\"}；②把这一格删掉、改用平台缺省名"
                                + "（缺省名已被无条件注册，滤不掉），但下游所有引用 {{$.%s}} 的地方要一并改。",
                        displayId(node.getId(), i), port.configKey(), written, written,
                        written, port.dataType(), written);
            }
        }
    }

    private boolean isNonCompensableType(String type) {
        return type != null && NON_COMPENSABLE_TYPES.contains(type);
    }

    /** 节点是否声明了反做：节点级 {@code recovery:compensate}（大小写不敏感，与 {@code WorkflowRecoveryEnum.parse} 同口径）或非空 {@code compensation} */
    private boolean declaresCompensation(NodeDTO node) {
        if ("compensate".equalsIgnoreCase(node.getRecovery())) {
            return true;
        }
        Map<String, Object> compensation = node.getCompensation();
        return compensation != null && !compensation.isEmpty();
    }

    /**
     * 把已登记的全部节点类型拼成一句提示，供「type 未知」的错误文案用。
     *
     * 🔴 派生自 {@link NodeIoContract#all()}，<b>不手抄</b>：原先这里手抄了 10 个字面量，
     * 与契约那 13 型之间没有任何比对（台账 #101(a)）—— 契约加第 14 型时这里会静默少提示一个，
     * 而少提示的后果是模型照着提示改、改完仍报「不被支持」，来回打转。
     * 今天两个方向差集都为空（手抄 10 + {@code GRAPH_BUILDER_TYPES} 3 = 契约 13），
     * 所以这不是活缺陷，缺的是一条<b>派生</b>：改成引用之后，契约加第 14 型这里自动跟上、不必再改一行；
     * 而「不许改回同值手抄」由 {@code KnownTypesHintSingleSourceTest} 的源码扫描钉住。
     *
     * ⚠️ 必须用 {@code all()} 而<b>不能</b>用 {@code types()}：前者是 {@code List.copyOf(values())}，
     * <b>保持声明顺序</b>（按「基础 / 分支与并行 / 人工介入 / 高级」分组，与前端目录同序）；
     * 后者是 {@code Set.copyOf(keySet())}，<b>无序</b> —— 用它就等于把顺序抖动从 3 个扩到 13 个。
     * 🔴 抖动是实测出来的，不是推演：{@code Set.of} 的迭代顺序掺了每次 JVM 启动都不同的 SALT，
     * 把原实现原样跑 12 次得到 <b>5 种不同的串</b>（那 3 个的排列计数 3+3+1+4+1=12，
     * 而 {@code List.of} 那 10 个恒为 1 种）。这句文案是喂给模型自我纠错的，
     * 顺序不稳还会让任何想逐字钉住它的测试变成偶发红（flaky 通常被当环境问题忽略掉）。
     */
    private String knownTypesHint() {
        List<String> all = new ArrayList<>();
        for (NodeIoContract.Contract contract : NodeIoContract.all()) {
            all.add(contract.type());
        }
        return String.join(" / ", all);
    }

    // ==================== 边 ====================

    private void validateEdges(List<EdgeDTO> edges, Set<String> nodeIds, AgentValidateResult result) {
        if (edges == null || edges.isEmpty()) {
            result.addError("edges 为空：节点之间要用边连起来，至少要有 "
                    + "{\"from\":\"START\",\"to\":\"<首节点id>\"} 与 {\"from\":\"<末节点id>\",\"to\":\"END\"}");
            return;
        }
        boolean fromStart = false;
        boolean toEnd = false;
        for (int i = 0; i < edges.size(); i++) {
            EdgeDTO edge = edges.get(i);
            if (edge == null) {
                result.addError("第 %d 条边为 null：请补一条 {\"from\":\"...\",\"to\":\"...\"}", i + 1);
                continue;
            }
            String from = edge.getFrom();
            if (!isResolvable(from, nodeIds)) {
                result.addError("第 %d 条边的 from「%s」解析不到任何节点：from 必须是 START 或某个已声明的节点 id",
                        i + 1, StrUtil.blankToDefault(from, "(空)"));
            } else if (START.equals(from)) {
                fromStart = true;
            }

            if (edge.isConditional()) {
                Map<String, String> cases = edge.getCases();
                String defaultTarget = edge.getDefaultTarget();
                boolean hasCases = cases != null && !cases.isEmpty();
                if (!hasCases && StrUtil.isBlank(defaultTarget)) {
                    result.addError("节点(%s) 出发的条件边既没有 cases 也没有 default："
                                    + "请至少给出 default 兜底目标，形如 {\"type\":\"conditional\",\"cases\":{...},\"default\":\"END\"}",
                            StrUtil.blankToDefault(from, "(空)"));
                }
                if (hasCases) {
                    for (Map.Entry<String, String> entry : cases.entrySet()) {
                        if (!isResolvable(entry.getValue(), nodeIds)) {
                            result.addError("节点(%s) 条件边 cases[「%s」] 的目标「%s」解析不到任何节点："
                                            + "目标必须是某个已声明的节点 id 或 END",
                                    StrUtil.blankToDefault(from, "(空)"), entry.getKey(),
                                    StrUtil.blankToDefault(entry.getValue(), "(空)"));
                        }
                        if (END.equals(entry.getValue())) {
                            toEnd = true;
                        }
                    }
                }
                if (StrUtil.isNotBlank(defaultTarget)) {
                    if (!isResolvable(defaultTarget, nodeIds)) {
                        result.addError("节点(%s) 条件边 default 的目标「%s」解析不到任何节点：目标必须是节点 id 或 END",
                                StrUtil.blankToDefault(from, "(空)"), defaultTarget);
                    }
                    if (END.equals(defaultTarget)) {
                        toEnd = true;
                    }
                }
            } else {
                String to = edge.getTo();
                if (!isResolvable(to, nodeIds)) {
                    result.addError("第 %d 条边的 to「%s」解析不到任何节点：to 必须是 END 或某个已声明的节点 id",
                            i + 1, StrUtil.blankToDefault(to, "(空)"));
                } else if (END.equals(to)) {
                    toEnd = true;
                }
            }
        }
        // 图必须有入口与出口，否则编译期/运行期会「跑不起来」或「跑不完」——这里提前一次性点明
        if (!fromStart) {
            result.addError("没有任何一条边从 START 出发：请补 {\"from\":\"START\",\"to\":\"<首节点id>\"} 作为流程入口");
        }
        if (!toEnd) {
            result.addError("没有任何一条边指向 END：请补 {\"from\":\"<末节点id>\",\"to\":\"END\"} 作为流程出口");
        }
    }

    /** 端点是否可解析：保留字 START/END，或命中某个已声明节点 id */
    private boolean isResolvable(String endpoint, Set<String> nodeIds) {
        if (StrUtil.isBlank(endpoint)) {
            return false;
        }
        return START.equals(endpoint) || END.equals(endpoint) || nodeIds.contains(endpoint);
    }

    // ==================== 条件边的出发点（V25） ====================

    /**
     * 条件边只能从 {@code condition.rule} / {@code condition.llm} 出发（V25）
     *
     * <h3>为什么是 error：cases 一条都命中不了，而执行状态照样报成功</h3>
     * 条件边是<b>纯读</b>的 —— {@code DslToStateGraphCompiler#addConditionalEdge} 那句
     * {@code state.value(routeKey(edge.getFrom())).orElse(DEFAULT_ROUTE)}：读不到路由键就落 default。
     * 而<b>全模块只有两个</b>执行器写这个键（{@link #ROUTE_KEY_WRITER_TYPES} 的权威源那段记着核实方式）。
     * 于是把条件边接在任何别的节点后面，等于给它一个永远读不到的键：cases 写得再对也一条不命中，
     * 每次执行都走 default，没有异常、没有告警、执行记录是成功。与 V19/V24 同一档危害：<b>静默</b>。
     *
     * <h3>🔴 实测疤（2026-10-09，AI 生成的草稿 618、执行 186）</h3>
     * 需求是「给表加两个字段，DDL 预览出来等我确认再执行」。生成的图是
     * 查结构 → 预览 DDL → {@code hitl.confirm}（id 为 {@code human_confirm}）→
     * 条件边 {@code cases={"true"=execute_update, "false"=report_cancel}}、{@code default=report_cancel}。
     * 用户点了同意、resume 喂进 {@code {"confirmed":true}}，执行报 DONE，而终态里
     * {@code update_result} / {@code publish_result} 全是 null、{@code output} 是
     * 「已收到您的取消请求，DDL 未执行，表结构保持不变」。
     * 根因就在 from：{@code HitlConfirmNodeExecutor#execute} 写的是 {@code approveVar}（归一化成布尔）
     * 与 {@code <节点id>_confirmed}（{@code "approved"} / {@code "rejected"}），<b>一个路由键都不写</b>。
     * 用户看到的是「我同意了，它按拒绝走完，还告诉我任务完成了」。
     *
     * <h3>🔴 为什么既有那几层都拦不住</h3>
     * <ol>
     *   <li>{@link #validateEdges}：只问「cases/default 有没有、端点解析得到吗」，<b>从不问 from 是什么类型</b>；</li>
     *   <li>深层干跑（{@code DslToStateGraphCompiler#dryRunValidate}）：图建得好好的。
     *       {@code addConditionalEdge} 里那句注释甚至<b>断言</b>「源恒为 condition 节点（不会是 sub_workflow）」——
     *       一条没有任何东西强制的假设。本条就是把它变成 error，让假设第一次有人守；</li>
     *   <li>运行期：见上，静默走 default。</li>
     * </ol>
     *
     * <h3>豁免（三档，都不是漏）</h3>
     * <ol>
     *   <li>{@code from} 解析不到任何已声明节点 —— {@link #validateEdges} 已报「from 解析不到任何节点」，
     *       这里再说一遍就是同一个根因报两条错（本类「一个根因只说一遍」的纪律）；</li>
     *   <li>{@code from} 是 {@code parallel} 节点 —— 编译器<b>当场拒绝</b>
     *       （{@code DslToStateGraphCompiler#buildStateGraph} 那句「parallel 节点的出边不能是条件边
     *       （MVP 只支持静态汇聚目标）」），是响的、且文案已经说清怎么改，不必再叠一条「静默走 default」
     *       —— 那句在它身上根本不是最坏后果。⚠️ 顺带一个实测约束：本类 V7/V8 那节的既有夹具
     *       （{@code WorkflowDslValidatorTest#validate_parallelJoinReferenceBothBranches_noWarning}）
     *       正是用「parallel 出发的条件边」表达 fan-out 的，不豁免会把一份绿夹具判红。</li>
     *   <li>非条件边 —— 普通边压根不读路由键。</li>
     * </ol>
     * ⚠️ 但 {@code from} 是 START / END <b>照样报</b>：{@code routeKey("START")} 同样没人写，
     * 而 {@code addConditionalEdge} <b>不看 from 的类型</b>（它只在 {@code parallel} 那一档被上游拦住），
     * 编译器对「一条非条件的 START 出边都没有」的处置是<b>自己合成</b>一条入口边
     * （{@code DslToStateGraphCompiler#buildStateGraph} 里 hasStartEdge 那段）—— 入口有没有是<b>另一个</b>根因，
     * 本条只管「cases 恒不命中」这一件事。
     *
     * <h3>与 V24 的关系：本条负责「谁写路由键」，V24 负责「condition.rule 读的键谁写」</h3>
     * 两条不重叠但<b>必须一起看</b>：把 from 从 {@code hitl.confirm} 改成 {@code condition.rule} 只修好一半，
     * 那个 {@code condition.rule} 的 {@code var} 还得真有人写（填 hitl 的 {@code approveVar} 就对）——
     * 否则静默的根因只是从「路由键没人写」换成「判据键没人写」，症状一个字都不变（恒走 default）。
     * 所以本条文案里直接把 {@code approveVar} 这个正确填法说出来，不给模型留第二次撞墙的机会。
     *
     * <h3>编号纪律（本条是 <b>V25</b>）</h3>
     * 见 {@link #validateChatLegsWriteReply} 规则头那段权威源。⚠️ 迭代七曾<b>否掉</b>过一条也叫 V25 的规则
     * （「对恒被写入的 outputVar 用 {@code exists} 判空」），那条一个号都没占用、只落进了规划器提示词；
     * 本条与它<b>不是同一条</b>，是 V25 这个号第一次真正落地。
     * 🔴 后记：那条被否掉的规则已在 2026-10-10 落地为 {@link #validateRuleConditionTautology}（<b>V32</b>），
     * 起因是一次实跑证明「只写进提示词」拦不住它 —— 推翻它的理由与实测证据记在 V32 的规则头，这里不抄。
     */
    private void validateConditionalEdgeSources(List<EdgeDTO> edges, List<NodeDTO> nodes,
                                                AgentValidateResult result) {
        if (edges == null || edges.isEmpty() || nodes == null || nodes.isEmpty()) {
            return;
        }
        Map<String, String> typeById = new HashMap<>();
        for (NodeDTO node : nodes) {
            if (node != null && StrUtil.isNotBlank(node.getId())) {
                // 重复 id 由 validateNodes 报；这里留第一个，只为拿到 type
                typeById.putIfAbsent(node.getId(), node.getType());
            }
        }
        for (EdgeDTO edge : edges) {
            if (edge == null || !edge.isConditional()) {
                continue;
            }
            String from = edge.getFrom();
            if (StrUtil.isBlank(from)) {
                continue;
            }
            boolean reserved = START.equals(from) || END.equals(from);
            String type = typeById.get(from);
            if (type == null && !reserved) {
                // 解析不到任何已声明节点：validateEdges 已经报过 from 不合法，不重复说
                continue;
            }
            if (type != null && (ROUTE_KEY_WRITER_TYPES.contains(type) || PARALLEL_TYPE.equals(type))) {
                continue;
            }
            // START/END 不是节点，没有 type 可点名 —— 但路由键同样没人写，故照样报（理由见方法注释）。
            // 🔴 保留字那一档自带结尾空格、节点那一档不带：中文与「」之间不该有空格，
            //    而拉丁字母与中文之间该有。统一加或统一不加都会让其中一档读起来是错的。
            String source = reserved ? "保留字 " + from + " " : "节点(" + from + ") 的类型「" + type + "」";
            String defaultLeg = StrUtil.isBlank(edge.getDefaultTarget())
                    ? "（这条边没写 default，连兜底目标都没有）"
                    : "「" + edge.getDefaultTarget() + "」";
            result.addError("%s出发的条件边永远不会命中 cases："
                            + "条件边是纯读的，它只认路由键 __route_<节点id>，"
                            + "而全平台只有 condition.rule 与 condition.llm 这两种节点会写这个键；"
                            + "别的出发点（包括 hitl.confirm 与保留字 START/END）一个都不写，"
                            + "于是这条边每次执行都落 default%s，cases 里那些目标一个也到不了，"
                            + "执行状态却照样报成功 —— 典型后果是「用户点了同意，流程按拒绝那条腿走完」。"
                            + "改法：把这条边的 from 换成一个 condition.rule 节点，"
                            + "由它去判上游真写出来的那个顶层键（var 只认顶层键、且必须真有节点写）；"
                            + "上游是 hitl.confirm 时 var 就填它的 approveVar —— "
                            + "hitl.confirm 会把归一化后的布尔值写进这个键，"
                            + "用 is_true 命中「同意」那条 target、config.default 填「拒绝」那条",
                    source, defaultLeg);
        }
    }

    // ==================== 条件节点能给出的路由值没有对应的 case（V33） ====================

    /**
     * 条件节点<b>能给出的每个路由值</b>，都必须是它那条条件边 {@code cases} 的一个键（V33）
     *
     * <h3>为什么是 error：这条腿一次也走不到，而执行记录报成功</h3>
     * 条件边是<b>纯读</b>的：{@code DslToStateGraphCompiler#addConditionalEdge} 拿节点写出的路由值去
     * {@code routeMap} 里查目标，查不到就 {@code routeMap.containsKey(key) ? key : DEFAULT_ROUTE} —— 落 default。
     * 于是 {@code cases} 里少一个键，对应的不是「报个错」，而是<b>那条分支永远走不到</b>，
     * 没有异常、没有告警、执行状态是成功。与 V24/V25 同一档危害：<b>静默</b>。
     *
     * <h3>🔴 这条规矩早就写在提示词里了，只是从来没人校验</h3>
     * {@code WorkflowDslPlannerPrompt} 的「边（edges[]）」一节明写：
     * 「cases 的「路由键」要与该条件节点 config.branches[].target（或 condition.llm 的 choices）<b>逐字一致</b>」。
     * 同一节里 V25 那半边（「from 只能是两种条件节点」）已落地成 error，<b>「逐字一致」这半边一直只是一句散文</b>：
     * <ol>
     *   <li>{@link #validateEdges} 只遍历 {@code cases} 的<b>值</b>问「这个目标解析得到吗」，
     *       <b>从不看 cases 的键</b>，更不去问「源节点给得出这个键吗」；</li>
     *   <li>深层干跑（{@code DslToStateGraphCompiler#dryRunValidate}）：{@code routeMap} 建得好好的，
     *       键少一个不影响建图，只在运行期查不到时静默兜底；</li>
     *   <li>运行期：见上。</li>
     * </ol>
     * 所以模型（或人）把 {@code target} 拼错一个字母、多打一个空格、或者<b>把 target 当成节点 id 来填</b>，
     * 三层全都放过。最后一种是这里最常见的错法 —— {@code config.default} 与 {@code branches[].target}
     * 装的都是<b>路由值</b>，只有 {@code cases} 的<b>值</b>和边的 {@code default} 才是节点 id，
     * 提示词第「条件边」那一行与「config.default 填「拒绝」那条」那句说的正是这个区分。
     *
     * <h3>🔴 比对必须【逐字】，一个字符都不能宽容 —— 别 trim</h3>
     * 三处写入/读取方全都不 trim：{@code RuleConditionNodeExecutor#execute} 是
     * {@code route = String.valueOf(target)}、{@code LlmConditionNodeExecutor#matchChoice} 返回的是
     * {@code String.valueOf(choice)} 原样（它对<b>模型输出</b>做 {@code equalsIgnoreCase}/{@code contains} 容错，
     * 但返回的仍是 config 里写的那个字符串）、{@code addConditionalEdge} 建 {@code routeMap} 用的是
     * {@code edge.getCases()} 的原样键。
     * 于是本条也<b>只能</b>原样比：一旦在这里 trim 或忽略大小写，
     * 「配置里差一个空格」这个<b>真缺陷</b>就会被体检判绿，而运行期照样落 default ——
     * 校验器比运行期宽容，等于亲手造一个假绿。
     * ⚠️ 也因此<b>不要</b>复用 {@link #addTrimmedKey}：那个是给 state 键名用的（键名确实到处 trim），
     * 路由值不 trim，两者口径相反。
     *
     * <h3>🔴 只看 {@code branches[].target} 与 {@code choices}，<b>不看</b> {@code config.default}</h3>
     * 提示词那句「逐字一致」点名的就是这两格。{@code config.default} 是「一个分支都没命中」的兜底路由值，
     * 兜底的自然接手方本来就是<b>边的</b> {@code default}：作者没把它写进 cases，
     * 说明他没打算给它专属目标，落边 default 正是他要的 —— <b>没有被顶替掉的意图</b>，也就没有静默损失。
     * 而 {@code branches[].target} 是作者<b>逐条列出</b>的预期结果，列了却没映射就是那条腿丢了。
     * ⚠️ 这一格必须留在射程外还有个实测理由：库里 def 416（PUBLISHED）正是
     * {@code default:"rejected"} 而 cases 里没有 {@code rejected} 的形状，且跑得好好的 ——
     * 算进来会让本条<b>拦住一个正在用的流程重新发布</b>。详见 {@link #collectRouteKeys}。
     *
     * <h3>豁免（四档，都不是漏）</h3>
     * <ol>
     *   <li><b>这个节点没有条件出边</b> —— 普通边压根不读路由键，节点写什么都无害；</li>
     *   <li>{@code from} 解析不到已声明节点，或不是 {@link #ROUTE_KEY_WRITER_TYPES} 里那两种 ——
     *       那是 V25 的根因（「cases 一条都命中不了」），本条沉默，一个根因只说一遍。
     *       ⚠️ 顺带保住一份既有绿夹具：{@code WorkflowDslValidatorTest#validate_parallelJoinReferenceBothBranches_noWarning}
     *       用的正是「{@code parallel} 出发的条件边」，{@code parallel} 不在写入方集合里，故本条不碰它；</li>
     *   <li><b>哨兵 {@code __default__}</b> —— 它是「一个分支都没命中」的信号，
     *       由<b>边的</b> {@code default}（{@code addConditionalEdge} 里 {@code routeMap.put(DEFAULT_ROUTE, ...)}）接手，
     *       本来就不该出现在 {@code cases} 的键里。所以 {@link #collectRouteKeys} 末尾会把它摘掉。
     *       ⚠️ 与之相邻但<b>刻意不在射程内</b>的另一件事：节点没写 {@code config.default} 时它给出的就是
     *       {@code __default__}，若这条边<b>也没写</b> {@code default}，{@code routeMap} 里就没有这个键，
     *       运行期会<b>响着</b>失败（SAA 查不到映射）—— 那不是静默缺陷，且修法与「补一个 case」不同，
     *       混进同一条文案会让作者不知道该动哪一格；</li>
     *   <li><b>内层图里的条件节点</b>（{@code iteration.body} / {@code parallel.branches}）——
     *       按规范它们<b>不许</b>出现在 {@code edges[]}（V18 那节记着编译器硬性拒绝），
     *       故天然落在豁免 ① 里，不需要专门判。</li>
     * </ol>
     *
     * <h3>🔴 刻意不做的一件事：别去判「target 不许是布尔字面量」</h3>
     * {@code target:"true"} + {@code cases={"true":...}} 是<b>能正常跑</b>的写法
     * （路由值只是一个字符串标签，取什么值都行），仓库里已有这样的流程在跑。
     * 把它判红会与提示词第「用 is_true 命中「同意」那条 target」那句自相矛盾，
     * 也会让作者收到一条「你写错了」而其实他没写错 —— 假警报的代价是真警报没人看。
     *
     * <h3>编号纪律（本条是 <b>V33</b>）</h3>
     * 见 {@link #validateChatLegsWriteReply} 规则头那段权威源。
     */
    private void validateRouteKeysCoveredByCases(WorkflowDefDTO def, AgentValidateResult result) {
        List<EdgeDTO> edges = def.getEdges();
        List<NodeDTO> nodes = def.getNodes();
        if (edges == null || edges.isEmpty() || nodes == null || nodes.isEmpty()) {
            return;
        }
        Map<String, NodeDTO> nodeById = new HashMap<>();
        for (NodeDTO node : nodes) {
            if (node != null && StrUtil.isNotBlank(node.getId())) {
                // 重复 id 由 validateNodes 报；这里留第一个，只为拿到 config
                nodeById.putIfAbsent(node.getId(), node);
            }
        }
        for (EdgeDTO edge : edges) {
            if (edge == null || !edge.isConditional()) {
                continue;
            }
            NodeDTO source = edge.getFrom() == null ? null : nodeById.get(edge.getFrom());
            // 豁免 ②：from 不合法 / 不是会写路由键的那两种节点 —— 归 V25 说
            if (source == null || !ROUTE_KEY_WRITER_TYPES.contains(source.getType())) {
                continue;
            }
            Set<String> missing = collectRouteKeys(source);
            if (missing.isEmpty()) {
                // 这个节点压根没列出路由值（branches 全空 / choices 全空），运行期它每次都给出
                // __default__，由边的 default 接手 —— 没有「列了却没映射」的腿，本条无从判起
                continue;
            }
            if (edge.getCases() != null) {
                missing.removeAll(edge.getCases().keySet());
            }
            if (missing.isEmpty()) {
                continue;
            }
            // 🔴 排序后再拼进文案：missing 是 LinkedHashSet（文档序），但 branches 与 config.default
            //    来自两个不同的读取点，文档序对作者没有意义 —— 同一份草稿点两次「体检」若给出两种措辞，
            //    用户会当成两次不同的结论，单测也没法断言这句话（V24 里同一条纪律）
            List<String> shown = missing.stream().sorted()
                    .map(key -> "「" + StrUtil.blankToDefault(key, "(空)") + "」")
                    .toList();
            // 一条边只报一条：修法是「把这几个键一起补进 cases」这一个动作，
            // 按键拆成 N 条只是把同一格配置说 N 遍
            String defaultLeg = StrUtil.isBlank(edge.getDefaultTarget())
                    ? "（这条边没写 default，运行期会直接失败）"
                    : "「" + edge.getDefaultTarget() + "」";
            result.addError("节点(%s) 能给出的路由值 %s 在这条条件边的 cases 里没有对应的键："
                            + "条件边是纯读的，编译器拿节点写出的路由值去 cases 里查目标，"
                            + "查不到就落这条边的 default%s —— 于是那条分支一次也走不到，"
                            + "而执行记录照样报成功。比对是【逐字】的（不 trim、不忽略大小写）："
                            + "多一个空格、大小写不同、或把路由值写成了节点 id，都会落 default。"
                            + "改法二选一：①把这些路由值补进 cases（键=路由值，值=目标节点 id）；"
                            + "②改这个节点列出的路由值 —— condition.rule 改 branches[].target，"
                            + "condition.llm 改 choices —— 让它们与 cases 已有的键逐字一致。"
                            + "⚠️ 这两格装的都是【路由值】而不是节点 id："
                            + "只有 cases 的值与边的 default 才是节点 id",
                    source.getId(), abbreviate(shown), defaultLeg);
        }
    }

    /**
     * 摘出一个条件节点<b>运行期可能写出的全部路由值</b>（原样，不 trim）
     *
     * <p>形状判据与两个执行器逐条对齐：
     * <ul>
     *   <li>{@code condition.rule}：{@code RuleConditionNodeExecutor#execute} 对每个 Map 分支取
     *       {@code branch.get("target")}，非 null 就 {@code String.valueOf(target)} 并 break。
     *       静态看不出哪个分支会命中，所以这里把<b>所有</b>分支的 target 都算成「可能给出」——
     *       宁可多列一个待核对的键，也不能漏掉那条真会走却走不到的腿；</li>
     *   <li>{@code condition.llm}：{@code LlmConditionNodeExecutor#matchChoice} 只会返回
     *       {@code String.valueOf(choice)}（{@code config.choices} 里的原样字符串）或 {@code defaultRoute}。</li>
     * </ul>
     *
     * <p>🔴 <b>刻意不含 {@code config.default}</b>，两个理由：
     * <ol>
     *   <li><b>契约就是这么写的</b>：{@code WorkflowDslPlannerPrompt} 那句「cases 的「路由键」要与该条件节点
     *       {@code config.branches[].target}（或 {@code condition.llm} 的 {@code choices}）逐字一致」
     *       <b>只点名这两格</b>。本条是把那句散文变成 error，不是替它加一条它没说的要求；</li>
     *   <li><b>语义上它本该落 default</b>：{@code config.default} 是「一个分支都没命中」的兜底路由值，
     *       而兜底的自然接手方就是<b>边的</b> {@code default}。作者没把它写进 cases，
     *       说明他压根没打算给它一个专属目标 —— 落边 default 正是他要的，<b>没有被顶替掉的意图</b>。
     *       这与 {@code branches[].target} 相反：那一格是作者<b>逐条列出</b>的预期结果，
     *       列了却没映射，就是那条腿丢了。</li>
     * </ol>
     * ⚠️ 这不是为了让测试变绿才收窄的：库里 def 416「设备管理表单创建与体检」（PUBLISHED）就是
     * {@code branches=[{target:"approved"}]} + {@code default:"rejected"} + {@code cases={"approved":…}}
     * 这个形状，且跑得好好的。把 {@code config.default} 算进来会让本条<b>拦住一个正在用的流程重新发布</b>，
     * 而 {@code error} 是拦发布的 —— 那正是本文件一直在消灭的假警报。
     *
     * <p>🔴 末尾摘掉哨兵 {@code __default__}：它由<b>边的</b> {@code default} 接手，
     * 本来就不该是 {@code cases} 的键，理由见 {@link #validateRouteKeysCoveredByCases} 豁免 ③。
     * （{@code condition.llm} 没写 {@code config.default} 时给出的就是它；
     * {@code condition.rule} 的分支 target 理论上也能写成它，一并摘掉。）
     */
    private Set<String> collectRouteKeys(NodeDTO node) {
        Set<String> keys = new LinkedHashSet<>();
        if (RULE_CONDITION_TYPE.equals(node.getType())) {
            for (Object branchObj : node.configList("branches")) {
                if (!(branchObj instanceof Map<?, ?> branch)) {
                    continue;
                }
                Object target = branch.get("target");
                // ⚠️ 只判 null、不判空白：执行器同样只判 null，
                //    target 写成空串时它照样把 "" 当路由值写出去（于是恒落 default）—— 那正是该报的
                if (target != null) {
                    keys.add(String.valueOf(target));
                }
            }
        } else {
            for (Object choice : node.configList("choices")) {
                keys.add(String.valueOf(choice));
            }
        }
        keys.remove(NodeExecutor.DEFAULT_ROUTE);
        return keys;
    }

    // ==================== 一个节点最多一条普通出边（V34） ====================

    /**
     * 同一个 {@code from} 上挂着<b>两条及以上普通（非条件）出边</b> ⟹ error
     *
     * <h3>为什么是 error，而且为什么非得在体检里报</h3>
     *
     * SAA 把「一个节点有多条出边」当成 <b>parallel fan-out</b>，而 {@code CompiledGraph} 要求各分支
     * 收敛到<b>同一个</b> target，否则 {@code compile()} 抛
     * {@code GraphStateException: parallel node [X] must have only one target, but [A, B] have been found!}。
     *
     * <p>🔴 要害在<b>它哪一步才炸</b>：发布前体检走的是
     * {@code DslToStateGraphCompiler#dryRunValidate} → {@code buildStateGraph}，<b>只建图、不 compile</b>。
     * 于是这种 DSL 一路绿灯 —— 体检通过、发布成功、草稿卡上九个节点排得整整齐齐 ——
     * 直到用户点「确认并运行」，{@code compile()} 才把上面那句框架原文甩出来：执行记录 FAILED，
     * 而 {@code failed_at_node} 还是 NULL（图压根没跑起来，谈不上「失败在哪个节点」）。
     * 用户看到的就是「AI 说计划没问题，我一确认它就崩，还不告诉我哪儿崩了」。
     *
     * <p>实测形状：定义 900215（AI 规划器把 {@code hitl.confirm} 的「同意 / 拒绝」画成了两条普通出边
     * {@code confirm_publish→publish} 与 {@code confirm_publish→report_cancel}）。
     *
     * <h3>正确形状</h3>
     *
     * 分叉只能用<b>条件边</b>表达，而条件边的 from 只能是 condition.rule / condition.llm（V25）。
     * hitl.confirm 要分「同意 / 拒绝」两条腿，就在它后面接一个 condition.rule，
     * {@code var} 填 hitl 的 {@code approveVar}（确认节点会把归一化后的 approve/reject 写进那个键），
     * 再由这个 condition.rule 出发画条件边。
     *
     * <h3>豁免（三档）</h3>
     * <ol>
     *   <li>{@code parallel} 节点：它的 fan-out 写在 {@code config.branches} 里，DSL 层面<b>只允许一条</b>
     *       出边（join 目标），编译器另有一档「parallel 节点只能有一条出边」当场拒绝 —— 本条不重复报
     *       （本类「一个根因只说一遍」的纪律）；</li>
     *   <li>条件边：一条条件边指向多个目标是它的本职，不算分叉；</li>
     *   <li>{@code from} 解析不到任何已声明节点：{@link #validateEdges} 已经报过。</li>
     * </ol>
     *
     * <h3>编号纪律（本条是 <b>V34</b>）</h3>
     * 见 {@link #validateChatLegsWriteReply} 规则头那段权威源。
     */
    private void validateSinglePlainOutEdge(List<EdgeDTO> edges, List<NodeDTO> nodes,
                                            Set<String> nodeIds, AgentValidateResult result) {
        if (edges == null || edges.size() < 2) {
            return;
        }
        Set<String> parallelIds = new HashSet<>();
        for (NodeDTO node : nodes) {
            if (node != null && PARALLEL_TYPE.equals(node.getType()) && StrUtil.isNotBlank(node.getId())) {
                parallelIds.add(node.getId());
            }
        }
        // 用 LinkedHashMap：报错里的目标顺序要跟 edges[] 里的书写顺序一致，
        // 模型自修正时才认得出自己写的是哪两条
        Map<String, List<String>> plainTargets = new LinkedHashMap<>();
        for (EdgeDTO edge : edges) {
            if (edge == null || edge.isConditional()) {
                continue;
            }
            String from = edge.getFrom();
            // 豁免 ③：from 解析不到节点时 validateEdges 已经报过，这里再叠一条是同一个根因说两遍
            if (!isResolvable(from, nodeIds) || parallelIds.contains(from)) {
                continue;
            }
            plainTargets.computeIfAbsent(from, k -> new ArrayList<>())
                    .add(StrUtil.blankToDefault(edge.getTo(), "(空)"));
        }
        for (Map.Entry<String, List<String>> entry : plainTargets.entrySet()) {
            List<String> targets = entry.getValue();
            if (targets.size() < 2) {
                continue;
            }
            result.addError("节点(%s) 有 %d 条普通出边（分别指向 %s）：一个节点最多一条普通出边。"
                            + "多条会被编译成并行分叉，而并行要求各分支收敛到同一个目标，"
                            + "运行期会直接抛异常（体检阶段看不出来，点了运行才炸）。"
                            + "要分叉就在它后面接一个 condition.rule，再由那个条件节点出发画一条条件边；"
                            + "hitl.confirm 分「同意/拒绝」也是这个形状 —— condition.rule 的 var 填 approveVar",
                    entry.getKey(), targets.size(), String.join("、", targets));
        }
    }

    // ==================== 不可达节点（V18） ====================

    /**
     * 从 START 沿 edges 走不到的节点（V18）
     *
     * <h3>为什么是 error：这是「配置保存成功但不生效且无提示」的教科书案例</h3>
     * 编译器对不可达节点<b>一句话都不说</b> —— {@code DslToStateGraphCompiler#buildStateGraph} 照样把它
     * {@code addNode} 进图，只是运行期永远调度不到；深层干跑因此也过。发布也过。
     * 于是作者把一个节点拖到画布上、忘了连线，看到的是一条全绿的体检和一次「成功」的执行，
     * 而那个节点里写的动作（往往正是要紧的那一步）一次也没发生。
     *
     * <h3>🔴 文案必须说「这个节点永远不会执行」，不能说「图不连通」</h3>
     * 后者是图论术语，作者读完不知道该干什么；前者直接给出后果，且对写工具节点尤其要紧 ——
     * 「你以为它会写库，它其实一次都没跑」。本条<b>不</b>按工具风险级分档：
     * 一个节点跑不跑与它做什么无关，而「不可达 + 是写工具」那半边已由 V11 的闸口判定覆盖，
     * 在这里再分一次档只会让同一个节点收到两条说法不同的提示。
     *
     * <h3>⚠️ 两处必须排除，否则每一条循环体和并行分支都会被误报</h3>
     * {@code iteration.config.body} 与 {@code parallel.config.branches} 引用的节点<b>写在顶层
     * {@code nodes[]}</b>，但编译器刻意把它们从父图排除、并要求作者<b>不要</b>把它们连进 {@code edges[]}
     * （{@code DslToStateGraphCompiler#collectIterationBodyIds} 与 {@code DslToStateGraphCompiler#expandParallel}
     * 都硬性拒绝它们出现在 {@code edges[]}：连边由子图内部生成）。
     * 于是它们在 {@code reachableFrom(START)} 里天然缺席 —— 不排除就是把「按规范写对了」判成错，
     * 而这类假警报的代价是真警报没人看（{@code UntrustedInputTracer} 排除 schedule 源时写的同一条）。
     *
     * <h3>⚠️ 没有一条边从 START 出发时整条跳过</h3>
     * 那个根因 {@link #validateEdges} 已经报过（「没有任何一条边从 START 出发」），
     * 而编译器在这种情况下另有兜底（{@code DslToStateGraphCompiler#buildStateGraph} 直接接上第一个非循环体节点）。
     * 这里再刷一屏「所有节点都不可达」是同一个根因说 N 遍 —— 正是本类一直在消灭的那种噪音。
     */
    private void validateUnreachableNodes(WorkflowDefDTO def, GraphReachability.Graph graph,
                                         Set<String> nodeIds, AgentValidateResult result) {
        // nodeIds 为空 ⟺ nodes 为空、或每个节点的 id 都非法/重复：两种情况 validateNodes 都已经报过，
        // 而这里也没有「合法的节点」可判
        if (nodeIds.isEmpty()) {
            return;
        }
        Set<String> reachable = graph.reachableFrom(START);
        if (reachable.size() <= 1) {
            // reachableFrom 恒含起点自身，故 size<=1 ⟺ 没有任何一条边从 START 出发（见方法注释）
            return;
        }
        Set<String> innerGraphIds = collectInnerGraphOwners(def).keySet();
        List<NodeDTO> nodes = def.getNodes();
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !nodeIds.contains(node.getId())
                    || reachable.contains(node.getId()) || innerGraphIds.contains(node.getId())) {
                continue;
            }
            result.addError("节点(%s) 从 START 沿 edges 走不到，它永远不会执行："
                            + "编译器会照样把它加进图里（所以深层校验与发布都不会拦），"
                            + "但运行期一次也不会调度到它，不报错、执行记录里也没有它的痕迹。"
                            + "请补一条连到它的边，形如 {\"from\":\"<上游节点id>\",\"to\":\"%s\"}；"
                            + "若这个节点已经废弃，请把它从 nodes[] 里删掉",
                    displayId(node.getId(), i), node.getId());
        }
    }

    /**
     * 被 {@code iteration.body} / {@code parallel.branches} 收进<b>子图</b>的节点 id → 收它的那个容器节点 id
     *
     * <p>🔴 这里刻意比编译器<b>宽容</b>：{@code branches} 的规范形状是 {@code List<List<String>>}
     * （每个元素一条 id 链，MVP 只许单节点），写成扁平的 {@code List<String>} 会被编译器以
     * 「每条 branch 必须是非空的节点 id 链」拒掉。那种 DSL 上本方法照样把这些 id 当成子图节点排除 ——
     * 于是作者只收到编译器那<b>一条</b>「形状不对」，而不是再叠上 N 条「节点不可达」。
     * 一个根因只说一遍。
     *
     * <p>返回<b>映射</b>而不只是一个 id 集合，是因为两个消费者要的东西不一样：
     * {@link #validateUnreachableNodes} 只要 keySet（「这些 id 不在父图的 edges 里，别判它们不可达」），
     * 而 {@link #validateWrittenVarsDeclared} 要 value（子图节点<b>不在</b> {@code edges[]} 里，
     * 于是 {@code reachableFrom(它)} 恒等于它自己 —— 顺着容器节点问「后面有没有挂起点」才是真答案）。
     *
     * <p>本方法<b>不校验</b> body/branches 的内容合法性（空、引用不存在的节点、分支数超上限…）——
     * 那是 {@code collectIterationBodyIds} 与 {@code expandParallel} 的权威，深层干跑会报（D7）。
     */
    private static Map<String, String> collectInnerGraphOwners(WorkflowDefDTO def) {
        Map<String, String> owners = new HashMap<>();
        for (NodeDTO node : def.getNodes()) {
            if (node == null || StrUtil.isBlank(node.getId())) {
                continue;
            }
            if (ITERATION_TYPE.equals(node.getType())) {
                for (Object item : node.configList("body")) {
                    putOwner(owners, item, node.getId());
                }
            } else if (PARALLEL_TYPE.equals(node.getType())) {
                for (Object branch : node.configList("branches")) {
                    if (branch instanceof List<?> chain) {
                        for (Object item : chain) {
                            putOwner(owners, item, node.getId());
                        }
                    } else {
                        putOwner(owners, branch, node.getId());
                    }
                }
            }
        }
        return owners;
    }

    private static void putOwner(Map<String, String> owners, Object raw, String ownerId) {
        if (raw == null) {
            return;
        }
        String id = String.valueOf(raw).trim();
        if (!id.isEmpty()) {
            owners.putIfAbsent(id, ownerId);
        }
    }

    // ==================== 会话腿没人写回复键（V20） ====================

    /**
     * 会话可跑的流程里，「走到 END 却一路上没人写回复键」的那条腿（V20，<b>警告</b>，不阻断发布）
     *
     * <h3>危害的形状：执行成功、副作用生效、用户在聊天里只收到一句兜底</h3>
     * {@code AgentAssistantServiceImpl.WORKFLOW_NO_OUTPUT_HINT} 是 fail-soft 的（DD6）：DONE 事件的 state
     * 里没有回复键就发「（流程已执行完成，但未产出文本回复…）」。于是执行记录是 SUCCEEDED、
     * 体检全绿、发布也过 —— <b>没有任何一处说「这一支是哑的」</b>。
     * 这正是本模块最典型的那类缺陷：配置保存成功、但不生效、且无提示。
     *
     * <h3>🔴 实测疤（不是推演）：分支图只给一条腿配了总结节点</h3>
     * 库里 def 416「设备管理表单创建与体检」（PUBLISHED，10 节点 / 11 边）：
     * <pre>
     * confirm_proposal(hitl.confirm) -&gt; check_confirm(condition.rule)
     *   cases={"approved":"create_table_node"}  同意腿 -&gt; … -&gt; show_inspect(llm 写 output) -&gt; END
     *   default="END"                            驳回腿 —— 一路上没有任何节点写 output
     * </pre>
     * 用户点了「驳回」之后只收到那句兜底，<b>他不知道自己驳回成功了没有</b>。
     * 编译器与执行器都没错（路由语义见 §6 P3-1 末尾那条核实），缺的是生成出来的图上那条腿的话术。
     *
     * <h3>为什么是警告而不是 error</h3>
     * 这正是 {@link AgentValidateResult} 里 warnings 那一档的定义：「后端不报错、保存成功、
     * 但跑出来的不是你要的」。判 error 会阻断发布，而「某条腿不说话」到底算不算缺陷取决于业务
     * （静默丢弃的分支是存在的），平台不该替作者裁定；与 V11 那条教训同向 ——
     * <b>规则必须先承认哪些写法是合法的，才可满足</b>。
     *
     * <p>🔴 代价要写清楚：警告<b>不会</b>回灌给模型。{@code WorkflowPlannerServiceImpl#planToWorkflow} 只在
     * {@code !result.isPassed()}（即有 error）时才调 {@code toFeedback()}，所以 V20 单靠自己
     * <b>改不了编排器的产物</b>，它只让<b>人</b>在草稿卡与体检面板上看得见。
     * 让模型别再产这种图的那一半在提示词侧（{@code WorkflowDslPlannerPrompt} 的「聊天回复约定」
     * 已改成「<b>每一条</b>走到 END 的腿」）—— 而按方案 §2-9，「模型是否采纳提示」这一环验不了，
     * 所以两侧都得有，谁也不替谁。
     *
     * <h3>⚠️ 四处刻意不报，否则假警报会把真警报淹掉</h3>
     * <ol>
     *   <li><b>无头触发</b>（schedule / webhook / form_event）整条跳过：回复键是<b>会话</b>约定
     *       （{@link TriggerDTO#outputVar()}），无头执行没有聊天窗口，没人会读它。
     *       🔴 {@code trigger == null} <b>要</b>报 —— {@link #isHeadlessTrigger} 对 null 返回 false，
     *       而编排器产的 BUILD 草稿恰恰<b>不落 trigger 却能被会话拉起</b>（def 416 就是这个形状）。</li>
     *   <li>🔴 <b>一条「会说话」的腿都没有时整条跳过</b>：本条只报<b>不对称</b>的那种漏 ——
     *       有的腿配了总结节点、有的腿没有，说明作者（或编排器）知道要写回复，只是漏了一条。
     *       而「整张图一个总结节点都没有」是另一档：那种流程<b>每次</b>跑都只回兜底句，症状明显得多，
     *       且按 Req6 向后兼容红线，报它等于让<b>存量流程集体亮黄</b>（{@link #validateWrittenVarsDeclared}
     *       对「缺省名」那一档不报，写的正是同一条理由）。体检面板上一片黄等于没有黄。</li>
     *   <li><b>祖先集里有任何一个节点写了这个键就不报</b>：本条只问「有没有可能写」，不逐条路径求证。
     *       「祖先写了、但这条腿恰好绕开它」的图确实存在，要判准就得枚举路径，
     *       代价是把大量正常图也刷一屏 —— 与 V18 排除子图节点、{@code UntrustedInputTracer}
     *       排除 schedule 源是同一条纪律。</li>
     *   <li><b>容器节点的循环体 / 并行分支算进祖先</b>：{@code iteration.body} 里的 llm 写了回复键，
     *       运行期就真写了；而这些节点<b>不在</b>父图 edges 里（见 {@link #collectInnerGraphOwners}），
     *       不顺着 owner 补一遍就会得到「没人写」的假答案 —— V19 的 {@code suspendAfterWrite}
     *       踩过同一个坑，那里的注释记着理由。</li>
     * </ol>
     *
     * <p>⚠️ {@code START -> END} 那种退化图天然不进本条（START 不在 {@code nodes[]} 里，循环碰不到它），
     * 那是刻意的：一张什么都不做的图，该报的是「没有节点」，不是「没写回复键」—— 一个根因只说一遍。
     * 同理，一条指向 END 的边都没有时整条跳过，那个根因 {@link #validateEdges} 已经报过。
     *
     * <h3>🔴 编号纪律：这个 V20 与迭代二「明确不做」里那个 V20 <b>不是同一条</b></h3>
     * 迭代二否掉的那条 V20 是「iteration 循环体内含写工具且无幂等键 → warning」，
     * 并且当时就<b>明写</b>了：若日后要做，正确落点<b>不是 V20</b>，而是给 {@code NodeIoContract}
     * 增一个 {@code idempotencyKey} 声明位。本条（会话腿没人写回复键）是迭代三独立落地的，
     * 落地时压根不知道那个号被预留过 —— 迭代三全文没出现过「幂等」或 {@code idempotencyKey}，
     * 所以这个撞号<b>从来没被发现过</b>。
     *
     * <p>处置口径（迭代四 §12.1 定的，日后照此执行）：
     * <ol>
     *   <li><b>本条不改名。</b> 它已落地、已有测试，改名纯属折腾，还会让两份文档同时指向第三个东西。</li>
     *   <li>🔴 <b>幂等键规则若日后要做，编号取「本段钉的下一个可用号」</b>（V21 已被「tool 节点的
     *       args 不是对象」占掉，见 {@link #validateToolArgsShape}；V22 已被「script 里调
     *       {@code tools.*}」占掉，见 {@link #validateScriptToolCalls}），且落点是
     *       {@code NodeIoContract.idempotencyKey} <b>声明位</b>，不是校验器规则。</li>
     *   <li>🔴 <b>别与既有字段接错</b>：{@code AgentNodeExecutionDO} 已经有一个 {@code idempotencyKey}
     *       （注释「幂等键，反做重放时去重。对应库里的 unique key」）—— 那是<b>重放去重</b>用的，
     *       与迭代二说的声明位<b>不是一回事</b>。</li>
     * </ol>
     *
     * <p>🔴 顺带钉一个实测口径（缺陷 D4-07）：本文件的<b>校验规则号</b>是
     * <b>V1~V16、V18~V35 —— 共 34 个号，唯一缺的是 V17</b>。
     * V17 从来不是一条规则：它「结构上不可触发」这件事记在 {@code ChangeManifest#untrustedInputs} 与
     * {@code UntrustedInputTracer} 类注释「🔴 只报运行期真会流动的路径」那一节里，校验器这边压根没落地过。
     * 所以别处文档写的「V1~V19」「19 条校验」一律按
     * 「<b>号有 34 个，其中 V17 结构上不可触发</b>」来读 —— 那个说法既不等于「V1 到 V19 都在」，
     * 也<b>不含 V20</b>（本条）、<b>V21</b>、<b>V22</b>、<b>V23</b>、<b>V24</b>、<b>V25</b>、
     * <b>V26</b>、<b>V27</b>、<b>V28</b>、<b>V29</b>、<b>V30</b>、<b>V31</b>、<b>V32</b>、<b>V33</b>、
     * <b>V34</b> 与 <b>V35</b>。
     * ⚠️ 这个数在补 V32 之前<b>就是错的</b>（写着 29，而当时号域已到 V31 = 30 个）：
     * 它正是本段自己警告的那类假话 —— 注释里的数字，没有任何测试会为它变红。
     *
     * <p>🔴 <b>下一个可用号 = V36</b>，且<b>只在本段说这一次</b>。
     * 本文件原先有 <b>4 份</b>「再加新规则从 Vxx 起」的副本（V21 与 V22 的规则头、上面幂等键那条、本段），
     * 每落地一条新规则就要同时改 4 处、漏一处就留下一句假话 —— 而这类假话<b>不会让任何测试变红</b>
     * （它是注释里的数字，{@code CommentLineRefBanTest} 管的是行号不是号数）。
     * 迭代七第 05 轮加 V23 时已把那三份副本删成「见本段」，只留这一处。
     * ⚠️ 落地新规则时<b>必须</b>同步改<b>两处</b>：① 本段的号域与「下一个可用号」；
     * ② 新规则自己 javadoc 里的「本条是 Vxx」（section 分隔条上有号的一并改）。
     * <b>不要</b>在新规则的 javadoc 里再抄一遍「下一个从 Vxx 起」。
     *
     * <p>🔴 还有<b>第三种</b>漏法，2026-10-10 实测过：新规则<b>压根不取号</b>。
     * {@link #validatePublishParentMenu} 落地时 section 分隔条与规则头都没写号，
     * 于是它对下面那段「号数怎么重新数」的判据（{@code grep -c "本条是 <b>V"}）<b>完全不可见</b> ——
     * 号域看着仍是 25 个、grep 也仍与它自洽，没有任何一处会对不上。
     * 是补 V28 时才发现、把 V27 回填给它的。⚠️ 所以 ② 那一处不是「可选的文档美化」：
     * 它是这条规则<b>存在于号域里</b>的唯一凭证。
     *
     * <p>🔴 这份清单<b>曾经有第 ③ 处</b>：「端点验证脚本的规则全集」——
     * {@code script/verify-workflow-orchestration/verify_dsl_gate.py} 里的 {@code LIVE_RULES}。
     * 它是 2026-10-07 才补进来的，补的原因是<b>已经连漏三轮</b>：V20、V21、V22 落地时各自都只补了单测，
     * 没人动那张表，而<b>覆盖台账的宇宙就是那张表</b> —— 不在表里的号台账看不见、也不会为它退 2，
     * 于是那个脚本一路打印「双向都量到 15 条」＋退出码 0，而实际少算 3 条生效规则。
     * 这与上面那三份副本是<b>同一类</b>缺陷（漏改一处 ⟹ 留下一句不会变红的假话），但更坏一档：
     * 副本漏改留下的是注释里的错数字，那张表漏改留下的是<b>一个恒绿的验证脚本</b>，
     * 而脚本的全部意义就是「让人不必自己判断闸门好不好」。
     *
     * <p>✅ <b>2026-10-09 加 V24 时核实：那个脚本连同整个 {@code script/verify-workflow-orchestration/}
     * 目录已在「清理垃圾文件」那次提交里删除</b>（{@code find} 全仓无 {@code verify_dsl_gate.py}），
     * 本仓当下<b>没有第二份规则全集</b>，故 ③ 无实物可改、从清单里去掉。
     * ⚠️ 但<b>上面那段教训一个字都别删</b>：它记的不是那个文件，而是
     * 「若日后再出现任何一份规则全集（脚本、台账、前端夹具表），就必须把它加回这个 ①②」。
     * 同理，下次再发现「落地新规则还得改第 ③ 处」，就把它补进清单，
     * <b>不要</b>另起一段写第二份清单 —— 那正是本段花了三轮才删掉的东西。
     *
     * <p>🔴 但「号数」不等于「一次校验能报出来的条数」：有的号只出 warning（如 V20 本条），
     * 有的号刻意在部分分支上沉默（V4/V5/V12 那条折叠、V24 与 V28 只对会话触发生效、
     * V25 只看条件边且放过 parallel 出发点），
     * 所以别拿号数去断言「任何 def 都会报出那么多行」。
     *
     * <p>🔴 号数怎么<b>重新数</b>（别信上面那个数字，自己数一遍）：数 section 分隔条
     * （{@code // ==== …（V..）====}）与各规则 javadoc 标题行里的「本条是 <b>Vxx</b>」，
     * 判据是 {@code grep -c "本条是 <b>V"} 加上没这么写的老规则。
     * ⚠️ <b>不要</b>拿全文 {@code grep 'V[0-9]'} 去数：规则号在本文件里<b>只存在于注释中</b>
     * （没有一处写在字符串字面量或常量名里，2026-10-04 核过），而<b>本段注释自己就写着若干个号</b>
     * （V17、V26，以及 R-09 ⑥ 那两节里被点名的 V19、V20~V25 —— 🔴 这个列举本身也会随补充而漏，
     * 所以别拿它当清单）—— 全文 grep 必然把它们一并数进去，「缺 V17」这条判据会被它自己的说明文字
     * 证伪。
     *
     * <p>⚠️ 上面这个口径与前端三处文案<b>已经对不上了</b>，且对不上是从 V20 落地那天就有的、
     * V21、V22 各又拉开一格（2026-10-05 核过，都没改，因为改哪一处都得先定「对外说号还是说条数」）：
     * {@code WorkflowCopyDialog.vue} 里那条告警的<b>用户可见</b>文案「不跑发布前那 19 条检查」、
     * {@code editor/dslGateFixtures.spec.ts} 文件头「⚠️ 关于 V9 与 V17」那节的「编号到 19，但只有 17 条生效」、
     * {@code editor/triggerCases.spec.ts} 文件头「不是后端 WorkflowDslValidator 的离线复刻」那段的「V1~V19 落地后…19 条里 17 条生效」。
     * 三处都是<b>描述性</b>文字（没有一条断言钉着这些数字，故不会变红），
     * 但「19 条」这个数会一直漂 —— 要治就得让它不再依赖条数，已报给用户裁定。
     *
     * <p>🟢 <b>裁定结果（R-09 ⑥，2026-10-06；迭代七第 05 轮 §3.5 附带项已照此落地）：不再依赖条数。</b>
     * 前端凡是说 V 系列<b>规模</b>的地方（一共几条 / 到哪一号为止 / 几条生效）一律改成指向本段 javadoc，
     * 不再写数字；<b>单条规则号</b>（{@code V19}、{@code MARK.V19}、夹具 id 与 {@code rule} 字段那种
     * 指某一条具体规则的）<b>照旧保留</b> —— V19 是真规则，不会因为多了 V20~V24 而变假。
     * 🔴 <b>本段是权威源，号数在这里钉死是对的，不要跟着「统一」成不带数字。</b>
     *
     * <p>🔴 <b>顺带更正上面那句「三处」——它自己也算少了。</b>那三处是按<b>文件</b>数的，
     * 而实际过期的<b>范围表述</b>是 <b>6 处病灶 / 4 个文件</b>：漏了 {@code PublishGateDialog.vue}
     * 那条「任何二次确认强推的口子都会把 V1~V19 从闸门降格成建议」的注释（整个文件上面压根没提到），
     * 以及 {@code triggerCases.spec.ts} 里「用带全 V1~V19 的构建逐条重跑过」那一句。
     * 这不是要改写上面的历史核过记录，而是留一条教训：<b>这类清点必须自己重新 grep，
     * 不能照抄任何一份现成清单</b>（连本文件这份也不行）。复核判据是
     * {@code grep -rn "19 条\|V1~V19\|编号到 19\|17 条生效" src/} <b>降到 0</b>，不是「变少」。
     *
     * <p>⚠️ 但「6 处」与那条 grep 的<b>命中行数不是同一个数，别把它们当成一回事</b>：
     * 6 处数的是<b>病灶</b>，而 {@code dslGateFixtures.spec.ts}「⚠️ 关于 V9 与 V17」那一处病灶
     * <b>横跨两行</b>（「编号到 19，但只有 17 条生效」与下面的「凑不齐 19 条」）⟹ 改前那条 grep
     * 实测命中 <b>7 行</b>（2026-10-07 核过，改后 0 行）。方案文档把「6 处」直接写成了
     * 「命中数从 6 降到 0」，那是它自己把两个口径混了；两个数都对，只是别混用。
     * 🔴 这一格是本轮「假话改实话」最容易翻车的地方 —— 顺手把 6 改成 7 会让「病灶数」变成假的，
     * 保留 6 又会让人以为 grep 该命中 6 行。唯一诚实的写法是把两个口径都写出来。
     * ⚠️ 同一轮还顺手治掉了同一段注释里另外两个会漂的数（「其余 14 条生效规则」= 17−3 的派生值、
     * 「{@code WorkflowDslValidatorTest}（145 条）」—— 2026-10-07 实测那个数是 182，且它<b>每加一条
     * 用例就漂一次</b>，故已改成不写数字：要数就 {@code grep -c "@Test" WorkflowDslValidatorTest.java}）：
     * 它们不在上面那个 grep 的口径里，但病根一模一样。
     */
    private void validateChatLegsWriteReply(WorkflowDefDTO def, GraphReachability.Graph graph,
                                           Set<String> nodeIds, AgentValidateResult result) {
        if (nodeIds.isEmpty() || isHeadlessTrigger(triggerTypeOf(def))) {
            return;
        }
        Set<String> endSources = graph.sourcesOf(END);
        if (endSources.isEmpty()) {
            return;
        }
        TriggerDTO trigger = def.getTrigger();
        String replyKey = trigger == null ? TriggerDTO.DEFAULT_OUTPUT_VAR : trigger.outputVar();
        if (StrUtil.isBlank(replyKey)) {
            return;
        }
        List<NodeDTO> nodes = def.getNodes();
        Map<String, NodeDTO> byId = new HashMap<>();
        for (NodeDTO node : nodes) {
            if (node != null && StrUtil.isNotBlank(node.getId())) {
                byId.putIfAbsent(node.getId(), node);
            }
        }
        Map<String, String> innerOwners = collectInnerGraphOwners(def);
        // 按文档顺序收集：既为了文案里那个 displayId 的下标，也为了同一份 DSL 每次报出<b>同样</b>的腿
        //（HashSet 的迭代序会让作者复现不出来，见 validateWrittenVarsDeclared 里 suspendOrder 的同一条理由）
        List<EndLeg> legs = new ArrayList<>();
        boolean anySpeaks = false;
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null || !nodeIds.contains(node.getId()) || !endSources.contains(node.getId())) {
                continue;
            }
            boolean speaks = ancestorsWriteReply(graph.ancestorsOf(node.getId()), byId, innerOwners, replyKey);
            legs.add(new EndLeg(node, i, speaks));
            anySpeaks |= speaks;
        }
        if (!anySpeaks) {
            return;
        }
        for (EndLeg leg : legs) {
            if (leg.speaks()) {
                continue;
            }
            result.addWarning("节点(%s) 有腿直接走到 END，而从 START 到它一路上没有任何节点把结果写进「%s」键："
                            + "这条腿跑完，执行记录是成功的、副作用也确实生效了，但用户在聊天里只会收到那句兜底"
                            + "「（流程已执行完成，但未产出文本回复…）」—— 他看不出这一支到底做成了什么。"
                            + "同一张图的别的腿是写了这个键的，所以这不是「整张图不要回复」，而是漏了这一条。"
                            + "请在这条腿上补一个 llm 或 script 节点，把要对用户说的话写进 {\"outputVar\":\"%s\"}",
                    displayId(leg.node().getId(), leg.index()), replyKey, replyKey);
        }
    }

    /** V20 的中间结论：一条收在 END 上的腿、它在 {@code nodes[]} 里的下标、以及它「有没有人写回复键」 */
    private record EndLeg(NodeDTO node, int index, boolean speaks) {
    }

    /**
     * 祖先集里有没有人把结果写进 {@code replyKey}（含容器的循环体 / 并行分支，理由见 V20 的第三条不报）
     */
    private static boolean ancestorsWriteReply(Set<String> ancestors, Map<String, NodeDTO> byId,
                                              Map<String, String> innerOwners, String replyKey) {
        for (String id : ancestors) {
            if (writesReplyKey(byId.get(id), replyKey)) {
                return true;
            }
        }
        for (Map.Entry<String, String> inner : innerOwners.entrySet()) {
            if (ancestors.contains(inner.getValue()) && writesReplyKey(byId.get(inner.getKey()), replyKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这个节点执行期会不会把结果写进 {@code replyKey}
     *
     * <p>🔴 按契约的<b>每个</b>出端口问一遍，且<b>缺省名也算</b>（不像 V19 那样跳过作者没填的格子）：
     * V19 问的是「作者填的名字声明了没有」，本条问的是「运行期到底会不会写这个键」——
     * 后者必须把 {@code writtenVarOf} 回落出来的缺省名也算上，否则一个把 outputVar 显式写成
     * 回复键的节点会被判成「没写」。
     */
    private static boolean writesReplyKey(NodeDTO node, String replyKey) {
        if (node == null) {
            return false;
        }
        NodeIoContract.Contract contract = NodeIoContract.of(node.getType());
        if (contract == null) {
            // 未知 type：V1 已经报过「类型不被支持」，这里没有端口清单可查
            return false;
        }
        for (NodeIoContract.OutPort port : contract.outputs()) {
            if (replyKey.equals(writtenVarOf(node, port))) {
                return true;
            }
        }
        return false;
    }

    // ==================== 工具方法 ====================

    private Set<String> collectDeclaredVars(List<VariableDTO> variables) {
        Set<String> keys = new HashSet<>();
        if (variables != null) {
            for (VariableDTO var : variables) {
                if (var != null && StrUtil.isNotBlank(var.getKey())) {
                    keys.add(var.getKey());
                }
            }
        }
        return keys;
    }

    private String displayId(String id, int index) {
        return StrUtil.isBlank(id) ? ("第 " + (index + 1) + " 个") : displayId(id);
    }

    /**
     * 同 {@link #displayId(String, int)}，供文案里点名<b>另一个</b>节点时用：那种场合手上只有 id
     * （它来自 {@link #collectProducers}，而那里<b>跳过</b>了 id 为空的节点），没有下标可给。
     *
     * <p>🔴 {@code "id="} 这个前缀<b>只在本方法里出现一次</b>，两参那档委托过来 ——
     * 否则同一句话里点名两个节点会印出两种格式（{@code 节点(id=gate)} 与 {@code 节点(decide)}），
     * 而 {@code WorkflowDslValidatorTest} 里断言 {@code id=xxx} 的那些用例只会红一半。
     */
    private static String displayId(String id) {
        return "id=" + id;
    }

    /** 给一个非法 id 生成一个「像样」的建议值，让模型照抄即可（避免它自己再猜一个仍不合法的） */
    private String suggestId(String raw) {
        // 先在 camelCase 边界插下划线（OrderInfo → Order_Info），再整体小写 + 非法字符转下划线，
        // 让建议值落到 DSL 惯用的 snake_case（OrderInfo → order_info），模型照抄即合法、且比 orderinfo 更像样
        String snaked = raw.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        String lowered = snaked.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        if (lowered.isEmpty()) {
            return "node_1";
        }
        if (!Character.isLetter(lowered.charAt(0))) {
            lowered = "n_" + lowered;
        }
        return lowered.length() > 63 ? lowered.substring(0, 63) : lowered;
    }

    /** 把一个 config 原值当字符串读出来；{@code null} 原样返回（不写成 "null" 这个字面量） */
    private static String strOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 这个值<b>看起来像不像</b>占位符
     *
     * <p>🔴 措辞刻意是「看起来像不像」而不是「是不是一个要到运行期才有值的占位符」——
     * 本方法服务的每一格<b>压根不做变量替换</b>，所以那些值<b>永远</b>不会有「运行期才算出来」的一天，
     * 它们只是长得像占位符的<b>字面量</b>。写成前一种说法，下一个读者就会推出
     * 「那静态判不了、跳过吧」—— 这正是 C6 那条缺陷的成因（原先 {@code fields[].column}
     * 就是被这么跳过的，而那一格同样不替换）。
     *
     * <p>刻意只看值里有没有「两个左花括号」这个开头、不用 {@link #PLACEHOLDER_ROOT} 那套严格正则：
     * 本方法服务的几条校验（{@code bpmProcessDefKey} / {@code workflowId} / {@code modelId} /
     * {@code form_save_draft} 的 {@code fields[].column}）都是「这一格压根不做变量替换」的，
     * 只要看起来是占位符就该拦下，
     * 写成 {@code {{ $.x }}} 这种带空格的宽松形状、乃至 `{{unclosed` 这种畸形形状也一样要拦。
     *
     * <p>⚠️ 反过来，<b>会</b>做替换的那一格（{@code mainTable}，它是 args 的顶层字符串值）
     * 必须用同源的 {@code StateTemplateResolver#containsPlaceholder}，不能用本方法 ——
     * 判据是「运行期会不会把它换掉」，理由写在 {@link #mainTableExists} 上。
     */
    private static boolean isPlaceholder(String value) {
        return value.contains("{{");
    }

    private static boolean isLlmNodeType(String type) {
        return type != null && LLM_NODE_TYPES.contains(type);
    }

    /**
     * 把一个「应当是整数」的 config 原值安全地转成 {@link Integer}
     *
     * <p>🔴 存在的全部理由是不抛异常：{@code NodeDTO#configInt} 对任何非 {@code Number}
     * 的值都直接 {@code Integer.parseInt}，而「{@code version} 写成 {@code {{$.v}}}」
     * 正是 LLM 产 DSL 的常见形状 —— 不拦一下，体检接口自己就成了 500。
     * 转不了就当「没 pin 版本」，那一格填错由深层干跑报（编译器读的是同一个键）。
     */
    private static Integer intOrNull(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ==================== 工具解析（合流 + 缓存） ====================

    /**
     * 一个工具引用的解析结果
     *
     * @param resolved        是否解析成功；{@code false} = 依赖故障，此时其余字段一律不可信
     * @param exists          合流目录里认不认得这个键
     * @param enabled         <b>生效</b>的启用状态（网关要读的就是它，故这里也用生效值）
     * @param declaredRisk    <b>声明</b>风险级；{@code exists=false} 时无意义
     * @param displayName     给人看的名字，只用于拼错误文案
     * @param resultArrayKeys <b>声明</b>的数组值键（全部分支的并集）；仅 {@code shapeKnown=true} 时有意义
     * @param resultKeys      <b>声明</b>的顶层键（全部分支的并集，V35 的唯一依据）。
     *                        🔴 空列表 = 「平台还没登记」而<b>不是</b>「它不回对象」，故 V35 见空即沉默；
     *                        与 {@code resultArrayKeys} 的空列表语义<b>相反</b>（那边的空是「确实没有数组」，
     *                        是 V26 敢断言的依据）。两者别搞混，理由见
     *                        {@code AgentToolFunction#resultKeys()} 的 javadoc
     * @param shapeKnown      返回形状能不能静态声明（V26 的唯一依据）
     * @param failure         {@code resolved=false} 时的原因
     */
    private record ResolvedTool(boolean resolved, boolean exists, boolean enabled,
                                RiskLevel declaredRisk, String displayName,
                                List<String> resultArrayKeys, List<String> resultKeys,
                                boolean shapeKnown, String failure) {

        static ResolvedTool failed(String failure) {
            return new ResolvedTool(false, false, false, RiskLevel.HIGH, null,
                    List.of(), List.of(), false, failure);
        }

        static ResolvedTool missing() {
            return new ResolvedTool(true, false, false, RiskLevel.HIGH, null,
                    List.of(), List.of(), false, null);
        }

        static ResolvedTool found(boolean enabled, RiskLevel declaredRisk, String displayName,
                                  List<String> resultArrayKeys, List<String> resultKeys,
                                  boolean shapeKnown) {
            return new ResolvedTool(true, true, enabled, declaredRisk, displayName,
                    resultArrayKeys == null ? List.of() : resultArrayKeys,
                    resultKeys == null ? List.of() : resultKeys, shapeKnown, null);
        }
    }

    /**
     * 一次 {@link #validate} 内的工具解析器：合流「内置注册表 + 当前租户自定义工具」并缓存结果
     *
     * <h3>为什么回落走 {@code resolveForGateway}，而不是 {@code getEnabledToolDefList}</h3>
     * 后者只返回<b>启用中</b>的行，于是「已停用的自定义工具」与「压根没这个键」长得一模一样 ——
     * V2 就永远报不出来，只能报 V1 的「不存在」，把用户引向错误的修法（去登记一个已存在的东西）。
     * {@code resolveForGateway} 是<b>网关自己</b>在注册表查不到时走的那条解析，看得到停用行；
     * 而 V2 的断言恰恰就是「停用的工具运行期会被网关拒」—— 直接问网关怎么解析，两处不可能漂移。
     *
     * <h3>为什么缓存的生命周期是「一次校验」而不是成员变量</h3>
     * 本类是单例 bean，成员缓存会变成跨请求、跨<b>租户</b>的共享态：A 租户解析过的工具会被 B 租户读到，
     * 那是比「多查一次库」严重得多的问题。与 S1 名称目录明确「不加缓存」是同一条纪律。
     */
    private static final class ToolResolver {

        private final AgentToolRegistry toolRegistry;
        private final AgentToolDefService toolDefService;
        /** toolId → 解析结果：同一个键在多个节点里出现时不重复查库 */
        private final Map<String, ResolvedTool> cache = new HashMap<>();
        /** 「相近候选」用的已知键全集；懒加载 —— 只有真报了「工具不存在」才值得查一次自定义工具表 */
        private Collection<String> knownIds;

        ToolResolver(AgentToolRegistry toolRegistry, AgentToolDefService toolDefService) {
            this.toolRegistry = toolRegistry;
            this.toolDefService = toolDefService;
        }

        ResolvedTool resolve(String toolId) {
            return cache.computeIfAbsent(toolId, this::doResolve);
        }

        /**
         * 🔴 取风险级的<b>唯一</b>入口：一律返回<b>声明</b>风险级
         *
         * <p>内置工具读 {@code ToolDefinition#getDeclaredRisk()}（{@code @AgentTool} 注解里写死的那个），
         * 自定义工具读它自己的 {@code risk_level} 列 —— 自定义工具没有覆盖表，那一列就是它的声明。
         *
         * <p><b>校验路径上禁止出现 {@code getRisk()}</b>：那是租户<b>生效</b>值，
         * {@code agent_tool_override} 一行数据就能把 IRREVERSIBLE 改写成 READ_ONLY（D11）。
         * 改完之后 V3/V11 就看不见危险工具、直接放行「定时触发 → 无人值守建表」，
         * 整个分型矩阵静默失效，而且界面上什么都看不出来。
         * 「这工具危不危险」是代码里的判断，不该被数据推动 —— 这与「启用状态读生效值」不矛盾：
         * 停用是<b>更安全</b>的方向，被数据推动不构成绕过。
         *
         * <p>认不出的工具按 {@link RiskLevel#HIGH} 兜，与 {@code RiskLevel.parseOrHigh} 同方向：
         * 宁可多说风险，不能把写操作标成只读。
         */
        RiskLevel declaredRiskOf(String toolId) {
            ResolvedTool tool = resolve(toolId);
            return tool.resolved() && tool.exists() ? tool.declaredRisk() : RiskLevel.HIGH;
        }

        private ResolvedTool doResolve(String toolId) {
            AgentToolRegistry.ToolDefinition builtin = toolRegistry.get(toolId);
            if (builtin != null) {
                // declaredRisk 理论上不会为 null（注解必填）；真为 null 按 HIGH 兜，
                // 与 WorkflowChangeManifestBuilder#resolveTool 的兜底方向一致
                RiskLevel declared = builtin.getDeclaredRisk() == null
                        ? RiskLevel.HIGH : builtin.getDeclaredRisk();
                return ResolvedTool.found(builtin.isEnabled(), declared, builtin.getDisplayName(),
                        builtin.getDeclaredResultArrayKeys(), builtin.getDeclaredResultKeys(),
                        builtin.isDeclaredResultShapeKnown());
            }
            AgentToolDefService.GatewayView view;
            try {
                view = toolDefService.resolveForGateway(toolId);
            } catch (Exception ex) {
                return ResolvedTool.failed(ex.getMessage());
            }
            if (view == null) {
                return ResolvedTool.missing();
            }
            // 自定义工具没有展示名以外的合并逻辑；riskLevel 已由 resolveForGateway 走 parseOrHigh。
            // 🔴 形状一律按「不可知」：source=DATA_INTERFACE 的结果就是那个数据接口回的东西，
            //    平台无从静态声明。与 WorkflowDslPlannerPrompt#customTools 传 List.of(), false 是同一口径 ——
            //    两边不一致的后果是提示词说「查不出形状，先打印键名看一眼」而校验器按某个具体形状判必抛。
            return ResolvedTool.found(view.enabled(), view.riskLevel(), toolId,
                    List.of(), List.of(), false);
        }

        /**
         * 「工具认不出」的中文原因，带相近候选
         *
         * <p>候选的挑选口径与变更清单的 {@code unresolved[]} 共用 {@link ToolKeySuggester}，
         * 两处不会给出不同的候选。
         *
         * <p>⚠️ 但<b>这句兜底文案与清单那句故意不同</b>：清单按 {@code getEnabledToolDefList()} 认工具
         * （只看得到启用中的），所以它说「也不是当前租户启用中的自定义工具」是准确的；
         * 本处按 {@code resolveForGateway} 认（停用的行也看得到），走到这里就意味着
         * 「已停用」那条已经被 V2 报过了、这个键<b>压根没登记</b> —— 照抄清单那句会让模型
         * 以为「启用一下就行」，而实际要去登记一个新的。
         */
        String unknownReason(String toolId) {
            List<String> candidates = ToolKeySuggester.suggest(toolId, knownToolIds());
            return candidates.isEmpty()
                    ? "既不在内置工具注册表里，当前租户也没登记过它"
                    : "不存在，相近的有 " + String.join("、", candidates);
        }

        private Collection<String> knownToolIds() {
            if (knownIds == null) {
                Set<String> ids = new LinkedHashSet<>();
                for (AgentToolRegistry.ToolDefinition def : toolRegistry.list()) {
                    ids.add(def.getName());
                }
                try {
                    for (AgentToolDefDO row : toolDefService.getEnabledToolDefList()) {
                        if (row != null && StrUtil.isNotBlank(row.getToolKey())) {
                            ids.add(row.getToolKey());
                        }
                    }
                } catch (Exception ex) {
                    // 候选只从内置里找。少给一个候选只是提示弱一点，
                    // 绝不能因此让 V1 的 error 本身消失（那就是 fail-open 了）
                }
                knownIds = ids;
            }
            return knownIds;
        }
    }

}
