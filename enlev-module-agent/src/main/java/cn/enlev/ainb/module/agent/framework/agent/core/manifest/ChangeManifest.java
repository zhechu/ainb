package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 变更清单：一份 Workflow DSL「将会对平台做什么」的结构化答案（方案 §3-L2，Req 3 核心）
 *
 * <h3>为什么必须有这个东西</h3>
 * 今天的确认链路只告诉用户「即将执行节点 n3」，用户点同意时并不知道那意味着
 * 「建一张无法回滚的表 + 发布一个只有超管看得见的菜单」。这不是文案问题：
 * 铁律是「用户确认前不执行任何写操作」，而<b>看不懂的确认等于没有确认</b>。
 *
 * <h3>🔴 不存库，实时从 DSL 派生</h3>
 * DSL 是唯一事实源。存一份清单副本必然漂移（本仓库已因双副本吃过多次亏：控件契约、
 * 节点 schema、系统表名单……）。派生成本是几次只读查询。
 * 唯一的例外是 BPM 审批：挂起时把本对象的 JSON 写进流程变量冻结，
 * 因为审批人要审的是「我点同意那一刻它要做什么」，事后 DSL 改了也不能篡改历史。
 *
 * <h3>序列化契约</h3>
 * 本对象会被塞进 BPM 流程变量、也会直接作为 {@code /agent/workflow-def/manifest} 的响应体，
 * 故所有字段必须是 Jackson 可直接序列化的（无循环引用、无 {@code Class}/{@code Function} 之类）。
 * {@link ChangeItem#getTarget()} / {@link ChangeItem#getDetail()} 里同理：只放字符串、数字、
 * 布尔、List 与 Map。
 *
 * @author 走进新时代
 */
@Data
public class ChangeManifest {

    /**
     * 清单结构版本。前端据此判断认不认得这份载荷 —— 加字段不动它，改字段含义才动。
     */
    public static final int VERSION = 1;

    private int manifestVersion = VERSION;

    /**
     * 这份清单是<b>降级品</b>：平台本该给出「将会做什么」，但没给出来（见 {@link #degraded(String)}）。
     *
     * <p>🔴 为什么不能靠「{@code changes} 是空的」来表达：空清单有两种完全相反的读法——
     * 「扫过了，确实不写」和「压根没扫成」。前者是清单可信的全部依据，后者是审批人在盲批。
     * 渲染层必须能分辨，所以这是一个显式布尔，不是一个待推断的空集合
     * （与 {@link ScopeInfo} 「不许静默裁」、{@code DryRunResult} 三态同一条道理）。
     *
     * <p>{@code false} = 正常清单。前端据此决定要不要显示「本次不产生任何写操作」那句话。
     */
    private boolean degraded;

    /**
     * 这份清单说的是哪个流程
     */
    private WorkflowRef workflow;

    /**
     * 本清单只覆盖整条流程的哪一段；{@code null} = 覆盖全流程，没有裁剪
     *
     * <p>🔴 裁剪过就必须留下这个对象，<b>不许静默裁</b>。审批人看到「将新建 1 张表」时，
     * 他没法从这一行分辨「整条流程只建 1 张表」与「另外 2 张表在上游、被裁掉了」——
     * 而这两句话对应的决定完全不同。与 {@code DryRunResult} 三态同一条道理：
     * 「没有」和「没看」在界面上长得一样时，就必须显式区分。
     *
     * <p>由 {@link ManifestScoper} 填。渲染层应当把 {@link ScopeInfo#getNote()} 原样显示在
     * headline 上方，而不是只在 tooltip 里给个图标。
     */
    private ScopeInfo scope;

    /**
     * 顶部一句话，如「将新建 1 张数据表、将新建 1 张表单」
     *
     * <p>四个渲染点里三个只显示这一行 + 风险色条。没有任何变更时为「本次不产生任何写操作」。
     */
    private String headline;

    /**
     * 全部变更里的<b>最高</b>风险等级标识；无变更时为 {@code READ_ONLY}
     */
    private String riskLevel;

    /**
     * 风险等级中文名（与 {@code AgentNameCatalogRespVO.ToolItem.riskDescription} 同义同名）
     */
    private String riskDescription;

    /**
     * 风险补充说明，如「含不可逆操作：建表后无法自动回滚」
     *
     * <p>🔴 方案原样例把这个长句叫 {@code riskDescription}，落地时改名 {@code riskNote}：
     * S1 已上线的名称目录里 {@code riskDescription} = 短中文标签（「不可逆」），
     * 同一个模块里同名字段有两种形状，前端迟早踩。长句另起一名，短标签沿用旧名。
     */
    private String riskNote;

    /**
     * 变更项，按 DSL 里节点的先后顺序（= 画布上的顺序）
     *
     * <p>刻意不按风险排序：用户此刻正在看画布，清单顺序与画布一致才对得上号。
     * 风险靠每项自带的色点表达，冲突与干跑失败由渲染层置顶。
     */
    private List<ChangeItem> changes = new ArrayList<>();

    /**
     * 人工介入点（{@code hitl.*} 节点）：谁会在这里被叫来确认、他能看到什么
     */
    private List<ApprovalItem> approvals = new ArrayList<>();

    /**
     * 外部事件等待点（{@code wait(event)} 节点）及其唤醒声明（裁定⑬，S6）
     *
     * <p>与 {@link #approvals} 是两件事：approvals 是「需要有人点头才能继续」，wait(event) 是
     * 「等外部系统发事件」，不一定需要人。审批人有权知道「我不在的时候谁会来唤醒它」——
     * 声明原文（{@link WaitItem#getOwner()}/{@link WaitItem#getEndpoint()}/{@link WaitItem#getNote()}）
     * 由 V14 读、由渲染层原样显示。
     */
    private List<WaitItem> waits = new ArrayList<>();

    /**
     * 清单说不清的部分
     *
     * <p>🔴 与 {@link #changes} 里的 {@link ChangeKind#GENERIC_TOOL} 是两件事：
     * GENERIC_TOOL 是「工具认得、但没有专用解读」，仍然是一项变更、仍然计入风险；
     * 本列表是「连这项变更是什么都定不下来」（工具没登记、子流程未展开），
     * 它们<b>不</b>计入 {@code changes}，因为算不出风险 —— 而算不出风险本身就是最该告诉用户的事。
     */
    private List<UnresolvedItem> unresolved = new ArrayList<>();

    /**
     * 不可信输入的数据流向：哪个外部输入面、经哪个变量、流进了哪个节点的哪个入参
     *
     * <p>方案 §3-L2 的 {@code untrustedInputs[]}，V16 / V17 与 X2 的共同数据源（§9「一次遍历两用」）。
     * 它回答的问题与 {@link #changes} 正交：changes 说「会改什么」，本列表说「改的时候喂进去的值
     * 有多少是<b>别人能写的</b>」。一条 webhook 触发的建表流程，changes 只有一行「将新建 1 张数据表」，
     * 而真正该让审批人停下来的是「表名来自请求体」—— 那正是 G25 说的「五个 LLM 输入面零防护」。
     *
     * <p>🔴 只报<b>运行期真会流动</b>的路径。{@code hitl.bpm_approval.formVars} 刻意<b>不</b>是汇：
     * 那些值压根不过 {@code StateTemplateResolver}（见 {@code NodeIoContract} 里 {@code hitl.bpm_approval.formVars} 那个入端口），
     * 用户写的 {@code {{$.input}}} 会以字面串进审批表单。把它算成汇等于报一条不可能发生的流向，
     * 而「清单说会流入、实际不会」与「清单说不会、实际会」一样都是让人做错决定。
     * 直接后果是 V17 目前<b>不可能触发</b>，已登记待裁定。
     *
     * <p>由 {@link UntrustedInputTracer} 填、{@link ManifestScoper} 按 {@link UntrustedInput#getSinkNodeId()}
     * 裁（只按<b>汇</b>裁，不按源，理由见它的类注释）。裁剪同样不许静默：裁掉的条数记进
     * {@link ScopeInfo#getHiddenUntrusted()}，并拼进那句能直接印在确认卡上的 {@code note}。
     *
     * <p>⚠️ 刻意<b>没有</b> {@code stats.untrustedInputs} 这个计数：它就等于本列表的长度，
     * 再存一份只会多一处能对不上的数（同 {@link ManifestStats} 里「PASSED 不计数」那条理由）。
     * {@code readToolCount} / {@code dryRunNotProbed} 那些计数存在的意义是「证明扫过了」，
     * 而本列表不需要自证：溯源是纯静态的，不打任何接口，也就没有 {@code NOT_PROBED} 这一态。
     *
     * <p>🔴 但这<b>不等于</b>「0 条就是真的没有外部输入在流」：溯源有几处已登记的盲区
     * （{@link UntrustedInputTracer} 类注释末尾逐条写着，含各自的方向）。
     * 0 条的准确含义是「按当前认得的读/写口径，没有可报的流向」——
     * 与干跑的 {@code NOT_PROBED} 不同，这里的「没看见」是<b>已知且写下来的</b>，
     * 而不是取决于某个外部服务当时通不通。
     */
    private List<UntrustedInput> untrustedInputs = new ArrayList<>();

    private ManifestStats stats = new ManifestStats();

    /**
     * 生成时刻。清单不落库，故这个时间就是「用户看到的那一版」的时间。
     */
    private LocalDateTime generatedAt;

    /**
     * 造一份<b>降级清单</b>：清单本该生成、但生成失败了，用它替代「什么都不给」。
     *
     * <h3>为什么要有这个东西（台账 #90 / 交接评审发现 ⑩）</h3>
     * 原先「构建失败」与「{@code injectManifest=false}」都表现为<b>不注入</b>，审批页上两者一模一样：
     * 「变更清单」页签压根不出现。于是审批人无法分辨三件事——
     * ① 这条流程不是 agent 流程（页签本就不该有）、② 作者主动关掉了注入、
     * ③ 平台答应了要给却没给出来。第 ③ 种情况下他正在<b>盲批一个不可逆动作</b>，
     * 而界面对此一言不发，只在服务端日志里留一行 {@code log.warn}。
     *
     * <p>🔴 所以失败时必须给一个**用户看得见**的信号，而不是退回沉默。做法是照样注入一份
     * 「形状正确、内容为空、并显式声明自己降级了」的清单：页签会出现，
     * {@link #isDegraded()} 让渲染层把「本次不产生任何写操作」那句安心话咽回去。
     *
     * <p>风险等级刻意给 {@code HIGH} 而不是 {@code IRREVERSIBLE}：后者是一句我们此刻<b>不知道</b>
     * 真伪的断言。能诚实说的是「说不清，请当心」——故 {@link #getRiskDescription()} 写「风险未知」。
     *
     * @param reason 失败原因（给人看的中文，别塞堆栈）
     */
    public static ChangeManifest degraded(String reason) {
        ChangeManifest m = new ChangeManifest();
        m.setDegraded(true);
        m.setHeadline("变更清单生成失败：本次审批看不到将要发生的变更");
        m.setRiskLevel("HIGH");
        m.setRiskDescription("风险未知");
        m.setRiskNote("平台没能算出「将会做什么」。这不代表本次没有影响——"
                + "请在看不清影响面的情况下谨慎决定，或让流程负责人修复后重跑。");
        UnresolvedItem item = new UnresolvedItem();
        item.setNodeName("整条流程");
        item.setType(UnresolvedItem.TYPE_MANIFEST_FAILED);
        item.setReason("变更清单生成失败：" + reason);
        m.getUnresolved().add(item);
        m.setGeneratedAt(LocalDateTime.now());
        return m;
    }

    // ==================== 嵌套结构 ====================

    /**
     * 流程标识
     */
    @Data
    public static class WorkflowRef {

        /**
         * 流程 code（DSL 的 {@code id} 字段，对应 {@code agent_workflow_def.code}）
         */
        private String code;

        private String name;

        /**
         * 流程版本号；由未落库的草稿 DSL 派生时为 null
         */
        private Integer version;

        /**
         * 触发类型（{@code trigger.chat} / {@code trigger.schedule} / …）
         *
         * <p>审批人有权知道「我不在的时候这个流程会不会自己跑起来」，
         * 无头触发（定时/表单事件）与对话触发的确认语义完全不同。
         */
        private String triggerType;

    }

    /**
     * 「本清单只覆盖哪一段」的记账
     *
     * <p>只在调用方传了 {@code fromNodeId} 时才有；{@link ChangeManifest#getScope()} 为 null
     * 就表示这份清单覆盖整条流程。
     */
    @Data
    public static class ScopeInfo {

        /**
         * 从哪个节点起算（含该节点自身）。传进来的 id 在 DSL 里不存在时，这里原样回填，
         * 而 {@link #pruned} 为 false —— 见 {@link ManifestScoper} 里那条 fail-safe 规则。
         */
        private String fromNodeId;

        /**
         * 该节点的显示名，取不到时回落成 id（与 {@code ChangeItem#nodeName} 同一条口径）
         */
        private String fromNodeName;

        /**
         * 是否真的裁掉了东西
         *
         * <p>🔴 与「{@code fromNodeId} 有没有传」不是一回事：找不到那个节点时本类<b>不裁</b>，
         * 宁可多报也不漏报。渲染层据此决定要不要显示「已裁剪」那条横幅 ——
         * 没裁却显示「已裁剪」，等于凭空造出一句假话。
         */
        private boolean pruned;

        /** 被裁掉的变更项数 */
        private int hiddenChanges;
        /** 被裁掉的「说不清的部分」数 */
        private int hiddenUnresolved;
        /** 被裁掉的其他人工介入点数 */
        private int hiddenApprovals;
        /** 被裁掉的只读工具节点数 */
        private int hiddenReadTools;
        /**
         * 被裁掉的不可信数据流向数（{@link ChangeManifest#getUntrustedInputs()}）
         *
         * <p>🔴 这一条比 {@link #hiddenChanges} 更容易被漏掉，而漏掉的后果同形：
         * 「没有外部输入流进这一步」与「有，只是流进的节点在你之前」在界面上是同一句话。
         * 前者可以放心点同意，后者意味着上游已经拿别人能写的值动过手 ——
         * 而审批人恰恰是因为「我只管这一步之后」才被叫来的，他更需要知道那之前发生过什么。
         */
        private int hiddenUntrusted;

        /**
         * 被裁掉的外部事件等待点数（{@link ChangeManifest#getWaits()}，S6）
         */
        private int hiddenWaits;

        /**
         * 给人看的一句话：本清单覆盖哪一段、漏了多少、去哪看全的
         *
         * <p>🔴 这句话必须自己说清「还有东西没列出来」，不能只靠 {@code hiddenChanges} 这些数字：
         * 数字要前端肯显示才有用，而这句话是直接印在确认卡上的。
         */
        private String note;

    }

    /**
     * 一个人工介入点
     */
    @Data
    public static class ApprovalItem {
        private String nodeId;

        private String nodeName;

        /**
         * 介入类型：{@code confirm}（聊天内确认）/ {@code bpm_approval}（走 BPM 待办）
         *
         * <p>= 节点 type 去掉 {@code hitl.} 前缀。两者机制相同（都是节点前挂起），
         * 但<b>叫醒的人不一样</b>：前者是发起对话的用户，后者是被指派的角色。
         */
        private String type;

        /**
         * 仅 {@code bpm_approval}：BPM 流程定义 key
         */
        private String bpmProcessDefKey;

        /**
         * 仅 {@code bpm_approval}：随审批一起带过去的表单变量名
         *
         * <p>只给<b>名字</b>不给值：值可能是上一步写库返回的主键，也可能是用户填的敏感内容，
         * 而这份清单会被冻结进流程变量、长期留在库里。
         */
        private List<String> formVarsKeys = new ArrayList<>();

        /**
         * 这个介入点<b>之后</b>会发生的变更类型（{@link ChangeItem#getKind()} 的名字，去重、按声明序）
         *
         * <p>由 {@code GraphReachability.reachableFrom(nodeId)} 算出。审批人不该被上游已完成的动作干扰 ——
         * 他同意的是「从这一步往后」。
         */
        private List<String> downstreamKinds = new ArrayList<>();

        /**
         * 这个人被叫来时，是否真有东西可看
         *
         * <p>🔴 单独给一个布尔而不是让前端判 {@code downstreamKinds.isEmpty()}：
         * 「确认点后面什么写操作都没有」是一种<b>编排错误</b>（白叫人一次），
         * 渲染层要据此显式提示，而不是默默显示一个空清单。
         */
        private boolean willSee;

    }

    /**
     * 一个外部事件等待点（{@code wait(event)} 节点，S6 / 裁定⑬）
     *
     * <p>字段直取自节点 config 的 {@code externalWakeup} 声明（{@code {owner, endpoint, note}}）。
     * 全部可空：作者填什么这里原样带什么，渲染层负责把「没填」说成「没声明」，而不是后端替他编一句。
     */
    @Data
    public static class WaitItem {

        private String nodeId;

        private String nodeName;

        /** 谁来唤醒（负责人/系统名），来自 {@code externalWakeup.owner} */
        private String owner;

        /** 调哪个端点，来自 {@code externalWakeup.endpoint} */
        private String endpoint;

        /** 补充说明，来自 {@code externalWakeup.note} */
        private String note;

        /**
         * 是否声明了 {@code externalWakeup}。false = 有 wait(event) 节点但没声明，
         * 无头流程体检（V14）会要求补声明 —— 渲染层据此提示「没声明谁来唤醒」，
         * 而不是安静地显示一行空白。
         */
        private boolean declared;

    }

    /**
     * 清单说不清的一项
     */
    @Data
    public static class UnresolvedItem {

        /** 工具没登记（拼写错误、已停用、或跨租户） */
        public static final String TYPE_TOOL_UNKNOWN = "TOOL_UNKNOWN";
        /** 节点未指定 toolId */
        public static final String TYPE_TOOL_MISSING = "TOOL_MISSING";
        /** 引用了子流程，其内部变更不在本份 DSL 里 */
        public static final String TYPE_SUB_WORKFLOW = "SUB_WORKFLOW";
        /**
         * 整份清单没生成出来（{@link ChangeManifest#degraded(String)}）。
         *
         * <p>🔴 与上面三个不同级：那三个说的是「清单里有一项说不清」，
         * 这个说的是「清单本身没成」。它只出现在降级清单上，且只有一条。
         */
        public static final String TYPE_MANIFEST_FAILED = "MANIFEST_FAILED";

        private String nodeId;

        private String nodeName;

        /**
         * 上面四个 {@code TYPE_*} 之一。给机器判，{@link #reason} 给人看。
         */
        private String type;

        /**
         * 涉及的工具键；{@link #TYPE_SUB_WORKFLOW} 时为 null
         */
        private String tool;

        /**
         * 中文原因，要说清「怎么改」而不只是「哪里错了」（与 {@code AgentValidateResult} 同一口径）
         */
        private String reason;

    }

    /**
     * 一条不可信输入的数据流向：{@code 源 → （传播若干跳）→ 汇}
     *
     * <p>粒度是「一个汇入参 × 一个源」，不是「一条完整路径」。同一个入参被两个不同的输入面喂到
     * （例如提示词里既拼了用户原话、又拼了知识库召回）就是两条 —— 它们的处置方式不同
     * （前者要做指令/数据分离，后者要先过召回内容审核），合成一条就看不出该做哪件事。
     *
     * <p>字段值一律是字符串/数字（见类注释的「序列化契约」）：{@code sourceKind} 与 {@code sinkKind}
     * 存的是 {@link UntrustedInputTracer.SourceKind} / {@link UntrustedInputTracer.SinkKind} 的
     * {@code name()}，与 {@code ApprovalItem#downstreamKinds} 存 {@code ChangeKind.name()} 同一个做法 ——
     * 枚举留在后端做唯一口径，JSON 里走稳定的名字，两边都不必解析中文。
     */
    @Data
    public static class UntrustedInput {

        /**
         * 一句话中文说明，如「聊天用户输入（input）会流进节点「写库」的工具入参 args.tableName」
         *
         * <p>四个渲染点里三个只显示这一行。与 {@code ChangeItem#summary} / {@code ScopeInfo#note}
         * 同一个约定：<b>后端把话说完整</b>，前端不拿六个字段自己拼句子 —— 拼出来的句子
         * 没人测，而后端这句有单测钉着。
         */
        private String summary;

        // ==================== 源：这份数据从哪来 ====================

        /**
         * {@link UntrustedInputTracer.SourceKind} 的 {@code name()}，即 G25 点名的五个输入面之一
         */
        private String sourceKind;

        /** 源的中文名，如「webhook 请求体」。渲染层直接显示，不再自己维护一份字典 */
        private String sourceLabel;

        /**
         * 承载它的 state 键。触发器源 = {@code trigger.inputVar()}（默认 {@code input}）；
         * 检索/工具源 = 那个节点解析后的输出变量名
         */
        private String sourceVar;

        // ==================== 汇：数据最终被谁吃掉 ====================

        /**
         * {@link UntrustedInputTracer.SinkKind} 的 {@code name()}。
         * V16 只关心 {@code TOOL_ARG}（且那个工具是写工具），X2 只关心喂给模型的那几个 ——
         * 有这个字段它们才不必去认 config 键名
         */
        private String sinkKind;

        /** 汇的中文名，取自 {@code NodeIoContract.InPort#label()}（如「工具入参」「提示词」） */
        private String sinkLabel;

        private String sinkNodeId;

        private String sinkNodeName;

        /**
         * 具体是哪个 config 键。对象型端口带上子键（{@code args.tableName}），
         * 因为运行期只替换<b>顶层</b>字符串值（{@code ToolNodeExecutor#execute}），
         * 报到子键才与那条行为逐字对得上；脚本沙箱整取 state，故为 {@code state}
         */
        private String sinkArg;

        // ==================== 中间：怎么流过去的 ====================

        /**
         * 汇真正读的那个 state 键（脚本沙箱为 {@code state}）
         *
         * <p>与 {@link #sourceVar} 常常不同：中间隔了「读污点 → 写自己的产出」的节点。
         * 给出它是为了让审批人能自己去画布上找那根线，而不是只能信一句话
         */
        private String viaVar;

        /**
         * 产出 {@link #viaVar} 的节点 id。<b>唯一</b>一个上游产出者时才填，0 个或多个填 {@code null} ——
         * 多个产出者时随便挑一个等于指认错人，而指认错人比不指认更坏
         */
        private String viaNodeId;

        /**
         * 传播跳数：0 = 汇直接读源变量；n = 中间经过 n 个「读污点、写产出」的节点
         *
         * <p>不是给用户数着玩的：跳数越多，中间越可能有一次「模型重写」把注入串洗掉或放大，
         * 而 {@code depth == 0} 的那条是<b>原样直送</b>，处置优先级不同
         */
        private int depth;

    }

    /**
     * 计数汇总，headline 与前端徽标都从这里取
     */
    @Data
    public static class ManifestStats {

        /** 新建表数（TABLE_CREATE） */
        private int tables;
        /** 改结构的表数（TABLE_ALTER） */
        private int alteredTables;
        /** 登记的已有表数（TABLE_REGISTER） */
        private int registeredTables;
        /** 新建表单数（FORM_CREATE） */
        private int forms;
        /** 修改的表单数（FORM_UPDATE） */
        private int updatedForms;
        /**
         * 发布动作数（FORM_PUBLISH 的项数）
         *
         * <p>🔴 它<b>不是</b>「会新建多少个菜单」，两件事对不上：一次首发建的是
         * <b>1 个组件菜单 + 7 个按钮权限 = 8 条 {@code system_menu} 记录</b>，
         * 而再发布<b>一条都不建</b>（判据是「这张表单当前有没有绑过菜单」，要查库才知道）。
         * 真数在每一项的 {@code detail.menu} / {@code detail.permissions} 里，
         * 由干跑器调 {@code previewPublishMenu} 填。
         * 这里之所以只数动作，是因为它派生自 {@code countByKind}，而 headline 与 stats
         * 必须同源（见 {@code WorkflowChangeManifestBuilder#finish}）——
         * 让干跑器再改一遍这个数，就多一处能对不上的地方。
         */
        private int menus;
        /**
         * 按钮权限串总数（各 FORM_PUBLISH 项 {@code detail.permissions} 之和）
         *
         * <p>由 {@code countPermissions} 从各项的 detail 求和，而 detail 是<b>干跑器</b>填的，
         * 所以这个数在 {@code finish} 之前必须先跑完干跑（顺序见
         * {@code ChangeManifestDryRunner#enrich} 的注释）。再发布的那些项不写
         * {@code detail.permissions}，故不会被算进来。
         */
        private int permissions;
        /** 部署的审批流数（PROCESS_DEPLOY） */
        private int processes;
        /** 业务数据写操作数（DATA_WRITE） */
        private int dataWrites;
        /** 数据接口调用数（DATA_INTERFACE） */
        private int dataInterfaces;
        /**
         * 落到兜底抽取器的项数（GENERIC_TOOL）
         *
         * <p>🔴 这个数不为 0 就是「有写工具没配专用抽取器」的信号。它不该被藏起来：
         * 前端要显式提示「N 项变更无法详细解读」，否则新增工具后清单会悄悄地变得不完整。
         */
        private int genericTools;
        /** 不可逆项数（riskLevel == IRREVERSIBLE） */
        private int irreversibleCount;
        /** 变更项总数 = changes.size() */
        private int writeToolCount;
        /**
         * 被跳过的只读工具节点数。给出来是为了证明「扫过了、不是没看见」
         *
         * <p>裁剪过的清单只数子图内的那些：「扫过了」这句话得对着用户真看得到的那一段说，
         * 否则它又变成一句查不出原因的大话。
         */
        private int readToolCount;

        /**
         * 干跑判定「这么改跑不通」的项数（{@code DryRunResult.Outcome.REJECTED}）
         *
         * <p>🔴 与 {@link #permissions} 一样由 {@code WorkflowChangeManifestBuilder#finish}
         * 从<b>最终留下来的</b> {@code changes} 数出来，而不是由干跑器边探边累加：
         * 按 hitl 节点裁剪（{@link ManifestScoper}）发生在干跑之后，
         * 边探边累加的话这两个数会把已经裁掉的项也算进来 ——
         * 于是「标题说 1 项、红标说 3 项干跑被拒」，而界面上根本找不到那 2 项。
         */
        private int dryRunRejected;

        /**
         * 没能完成干跑的项数（{@code DryRunResult.Outcome.NOT_PROBED}）
         *
         * <p>🔴 与上面那个数<b>必须分开</b>，理由见 {@link DryRunResult} 的三态说明。
         * 单看每一项也能看出来，但这两个数存在的意义是「一眼看到全貌」：
         * infra 挂掉时全部变更项都会落进本字段，此时清单看着一条红都没有 ——
         * 一个不为 0 的黄色计数是唯一能让人察觉「这份清单其实什么都没校验」的东西。
         */
        private int dryRunNotProbed;

    }

}
