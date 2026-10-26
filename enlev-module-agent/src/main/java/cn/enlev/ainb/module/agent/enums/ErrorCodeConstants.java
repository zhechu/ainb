package cn.enlev.ainb.module.agent.enums;

import cn.enlev.ainb.framework.common.exception.ErrorCode;

/**
 * agent 模块的错误码
 *
 * 号段 1-041-000-000 起（system 用 1-002、infra 用 1-001、bpm 用 1-009、ai 用 1-040）。
 *
 * @author 走进新时代
 */
public interface ErrorCodeConstants {

    // ========== 权限网关 1-041-000-000 ==========
    // 这几条刻意分得很细而不是笼统一个「无权限」：
    // 排查时「拿不到身份」与「有身份但没授权」是完全不同的两类问题，合并会让诊断变难。

    ErrorCode AGENT_NO_LOGIN_CONTEXT = new ErrorCode(1_041_000_000,
            "AI 助手无法确认当前用户身份，已拒绝执行。这通常是异步链路上丢了登录上下文，请重试；若持续出现请联系管理员");
    ErrorCode AGENT_NO_TENANT_CONTEXT = new ErrorCode(1_041_000_001,
            "AI 助手无法确认当前租户，已拒绝执行");
    ErrorCode AGENT_CROSS_TENANT_DENIED = new ErrorCode(1_041_000_002,
            "AI 助手不支持跨租户操作（当前访问租户 {}，登录租户 {}）");
    ErrorCode AGENT_DISABLED = new ErrorCode(1_041_000_003,
            "AI 助手尚未启用，请联系管理员在后台开启");
    ErrorCode AGENT_TOOL_NOT_FOUND = new ErrorCode(1_041_000_004, "AI 助手工具({})不存在");
    ErrorCode AGENT_TOOL_DISABLED = new ErrorCode(1_041_000_005, "AI 助手工具({})已被停用");
    ErrorCode AGENT_PERMISSION_DENIED = new ErrorCode(1_041_000_006,
            "您没有执行该操作的权限：{}（需要 {}）");

    // ========== 工具执行 1-041-002-000 ==========

    /**
     * 工具参数不合法
     *
     * 🔴 必须是业务异常而不是 {@code IllegalArgumentException}：
     * 后者会被全局异常处理器当成未知异常，返回「系统异常」——
     * 而 AI 的自修正完全依赖错误信息的可读性，「系统异常」什么也说明不了，
     * 只会让它原样重试一遍。
     */
    ErrorCode AGENT_TOOL_PARAM_INVALID = new ErrorCode(1_041_002_000, "工具({})的参数不合法：{}");

    /**
     * 没有「某张表单」的数据操作权限
     *
     * 🔴 与网关的 {@link #AGENT_PERMISSION_DENIED} <b>分开</b>：那条是「你能不能用这个工具」
     * （静态工具权限），这条是「你能不能对<b>这张表单</b>的数据做<b>这个动作</b>」
     * （资源实例级的动态数据权限，格式 {@code infra:form-design-run:{code}:{action}}，
     * 与 infra {@code FormDesignRuntimeController.checkPermission} 逐字一致）。
     *
     * <p>占位符 (code, action, permission) 让 AI 知道是「这张表单的数据没授权」而非「工具用不了」——
     * 否则它会误判成工具故障而原样重试，白白烧一轮 token。修复动作是找管理员在角色里勾选
     * 该表单对应的数据权限，AI 自己重试无解。
     */
    ErrorCode AGENT_FORM_DATA_PERMISSION_DENIED = new ErrorCode(1_041_002_001,
            "您没有表单({})数据的「{}」权限（需要 {}），无法完成该操作。请联系管理员在角色里勾选该表单对应的数据权限");

    // ========== 助手配置 1-041-001-000 ==========
    ErrorCode AGENT_CONFIG_NOT_EXISTS = new ErrorCode(1_041_001_000, "AI 助手配置不存在");

    // ========== 会话与对话 1-041-003-000 ==========

    /**
     * 会话不存在
     *
     * ⚠️ 「别人的会话」与「不存在的会话」<b>刻意共用这一条</b>。
     * 分开报会泄漏「这个 id 存在，只是不属于你」——
     * id 是连续可枚举的，这等于把同租户里有多少会话、分布在哪些号段全告诉了对方。
     */
    ErrorCode AGENT_CONVERSATION_NOT_EXISTS = new ErrorCode(1_041_003_000, "会话不存在");

    ErrorCode AGENT_MESSAGE_EMPTY = new ErrorCode(1_041_003_001, "请先输入问题");

    // 🔴 1_041_003_002（原 AGENT_NO_CHAT_MODEL）已于台账 D4-29 删除，号段<b>不复用</b>。
    //    它原先是 toReadableMessage 里 IllegalStateException 那一支的兜底文案，而那一支唯一还在起的作用
    //    就是「给别的成因贴错标签」（密钥没填 → 界面说「没有可用的聊天模型」）。
    //    「没有可用的聊天模型」这个成因现在由 ai 侧 MODEL_ALL_UNAVAILABLE 自己说，经 ServiceException 原样透传；
    //    「密钥没填」由 ai 侧 API_KEY_BLANK 说。两句各归各的成因，agent 侧不再有第三句去猜。

    // ========== 配额 1-041-005-000 ==========

    /**
     * 用量超限
     *
     * 🔴 只在<b>请求入口</b>抛，不在流中途抛。
     * 中途拒绝会产生「扣了 token 但没拿到结果」的争议，而这种争议没法自证清白 ——
     * 用户说没拿到，日志说调用成功了，谁也说服不了谁。
     *
     * <p>提示语里要说清楚<b>三件事</b>：谁超了、什么周期、上限是多少。
     * 只说「超限了」的话，用户既不知道该等到什么时候，也不知道该找谁加额度。
     */
    ErrorCode AGENT_QUOTA_EXCEEDED = new ErrorCode(1_041_005_000,
            "{}本{}的 AI 助手用量已达上限（{}）。进行中的对话不受影响，"
                    + "新的提问请等下个周期，或联系管理员调整额度");

    /**
     * 同一维度、同一目标重复配置配额
     *
     * <h3>🔴 为什么在保存时就拦，而不是靠数据库唯一键</h3>
     * {@code agent_quota} 的唯一键含可为 NULL 的列（user_id / target_key），而 MySQL 复合唯一键
     * 对含 NULL 的行<b>不去重</b>——租户级、Agent 级、工作流级配额的 user_id 都为 NULL，唯一键拦不住重复。
     * 真放两条同维度同目标的配额进去，{@code selectOne} 会抛 {@code TooManyResultsException}，
     * 表现为该租户/Agent 一提问就 500「系统异常」——比「配了不生效」更糟。故在 saveQuota 应用层先拦下。
     * 占位符放维度名与目标，告诉管理员「去编辑那条，别重复新增」。
     */
    ErrorCode AGENT_QUOTA_DUPLICATE = new ErrorCode(1_041_005_001,
            "该维度（{}）已存在一条配额，请直接编辑它，不要重复新增");

    /**
     * 配额缺少维度目标（USER 缺用户、AGENT/WORKFLOW 缺 code）
     *
     * <p>🔴 拦在保存时：缺目标的配额行永远匹配不到任何用量（{@code selectUserQuota(null)} /
     * {@code selectAgentQuota(空)} 都不会命中），等于「配了不生效」——正是本平台头号缺陷。
     * 与其让它静静躺着，不如保存时就报清楚缺哪一项。占位符放维度名。
     */
    ErrorCode AGENT_QUOTA_TARGET_REQUIRED = new ErrorCode(1_041_005_002,
            "{}配额缺少目标：请指定用户编号或定义 code");

    // ========== 变更计划 1-041-004-000（增量7b 起整段空闲）==========
    // 线性 planner（AgentPlannerService/AgentPlanService）已删除，原属它的 5 条错误码
    // （AGENT_PLAN_NOT_EXISTS/_EXPIRED/_NOT_CONFIRMABLE/_NO_STEP_SELECTED/_STEP_NOT_WRITABLE）随之移除。
    // 🔴 号段刻意不回收、不复用：1_041_004_* 曾表达「变更计划」语义，留空避免新码撞上旧含义造成误读。
    // plan-as-workflow 的失败走下面的 WORKFLOW_PLAN_DSL_GENERATION_FAILED（1_041_006_010）。

    // ========== Workflow 执行引擎 1-041-006-000 ==========

    /**
     * 定义不存在 / 没有可执行版本
     *
     * ⚠️ 与其它资源同理：「别的租户的定义」与「不存在」共用一条，
     * 避免用连续可枚举的 id 探测他租户有哪些流程。
     */
    ErrorCode WORKFLOW_DEF_NOT_EXISTS = new ErrorCode(1_041_006_000, "Workflow 定义不存在或无已发布版本");

    /**
     * DSL 不合法
     *
     * 🔴 必须是业务异常：DSL 由编排器/用户 JSON 提供，属系统边界输入。
     * 报「系统异常」的话，编排者拿不到「哪个节点、哪条边错了」，只能盲改。
     * 第二个占位符放具体原因（节点 id 缺失、边指向不存在的节点、条件分支不互斥等）。
     */
    ErrorCode WORKFLOW_DSL_INVALID = new ErrorCode(1_041_006_001, "Workflow DSL 不合法：{}");

    /**
     * 执行实例不存在
     */
    ErrorCode WORKFLOW_EXECUTION_NOT_EXISTS = new ErrorCode(1_041_006_002, "Workflow 执行实例不存在");

    /**
     * 实例当前状态不允许该操作
     *
     * 例如对一个已经 SUCCEEDED 的实例调 resume。占位符带上当前状态，
     * 免得用户对着「不允许」猜自己到底处在哪一步。
     */
    ErrorCode WORKFLOW_EXECUTION_STATE_INVALID = new ErrorCode(1_041_006_003,
            "Workflow 执行实例当前状态（{}）不允许该操作");

    /**
     * 节点执行失败
     *
     * 与 TOOL 段分开：这里的失败可能是 DSL 配错（引用了不存在的变量/工具），
     * 不一定是工具本身报错。占位符依次是 节点 id、原因。
     */
    ErrorCode WORKFLOW_NODE_FAILED = new ErrorCode(1_041_006_004, "节点({})执行失败：{}");

    /**
     * 找不到对应类型的节点执行器
     *
     * 🔴 这是<b>部署/版本</b>问题而不是用户输入问题：DSL 声明了一个 type，
     * 但引擎里没有注册它的执行器（多半是节点类型在 P2 才做、或注册表漏了）。
     * 单独一条，方便一眼区分「用户写错 type」与「这个 type 还没实现」。
     */
    ErrorCode WORKFLOW_NODE_EXECUTOR_NOT_FOUND = new ErrorCode(1_041_006_005,
            "找不到节点类型({})的执行器，可能该类型尚未实现");

    /**
     * sub_workflow 引用的子流程不存在或不可执行
     *
     * ⚠️ 与 {@code WORKFLOW_DEF_NOT_EXISTS} 同理：跨租户共用「不存在」文案，
     * 避免用 code/version 探测他租户有哪些流程。占位符是 {@code code} 或 {@code code@version}。
     * 指定版本 pin 到 DRAFT（草稿）也算「不可执行」，一并走这条。
     */
    ErrorCode WORKFLOW_SUB_WORKFLOW_NOT_FOUND = new ErrorCode(1_041_006_006, "子流程({})不存在或不可执行");

    /**
     * agent_loop 达到最大步数仍未收敛出 answer
     *
     * <h3>🔴 为什么是 fail-close 抛错，而不是「拿最后一次结果凑合」</h3>
     * 自主 ReAct 循环里模型可能一直 {@code tool_calls} 查个不停、始终不给 {@code answer}。
     * 到 {@code maxSteps} 还没收敛时，<b>不能</b>硬把某个中间观察当成最终答复返回 ——
     * 那会产出一个看似合理、实则没收口的结果，而下游节点无从判断。
     * 与规划器超限「暴露问题而不硬猜」是同一条纪律（方案 §7）。
     *
     * <p>轨迹不会丢：每轮 reason/act 都已各落一行 {@code agent_node_execution}，
     * 失败后仍可逐轮回看模型查了什么、卡在哪一步。
     * 占位符依次是节点 id、maxSteps。
     */
    ErrorCode WORKFLOW_AGENT_LOOP_NOT_CONVERGED = new ErrorCode(1_041_006_007,
            "agent_loop({})达到最大步数({})仍未收敛出 answer");

    /**
     * iteration（foreach）的待遍历列表项数超过 settings.maxIterations
     *
     * <h3>🔴 为什么是 fail-close 抛错，而不是「只跑前 maxIterations 项」</h3>
     * iteration 的迭代次数 = 列表长度（编译期未知），运行期才能确定。若列表超长，
     * <b>不能</b>悄悄截断只跑前 N 项 —— 那会产出一个「看似跑完、实则漏了大半」的结果，
     * 而下游节点与作者都无从判断（正是平台头号缺陷「配了不生效且无提示」的翻版）。
     * 与 agent_loop 超限「暴露问题而不硬猜」是同一条纪律（方案 §7）。
     *
     * <p>列表确实很长时，作者应显式调大 {@code settings.maxIterations}，或先用前置节点过滤列表。
     * 占位符依次是节点 id、列表项数、maxIterations。
     */
    ErrorCode WORKFLOW_ITERATION_TOO_MANY_ITEMS = new ErrorCode(1_041_006_008,
            "iteration({})的列表({})项数超过 maxIterations({})，请调大 settings.maxIterations 或先过滤列表");

    /**
     * 触发器配置不合法（增量6：form_event 缺 formCode/eventType、schedule 的 cron 无法解析等）
     *
     * <h3>🔴 为什么在「落定义」时就 fail-close，而不是等触发时才炸</h3>
     * 触发器是<b>无头</b>的：到点 / 数据变更时后台自动拉起，那一刻没有用户在屏幕前看报错。
     * 若把非法配置（如解析不了的 cron）留到触发时才发现，表现就是「配了触发器却从不触发、且无声无息」
     * —— 正是本平台头号缺陷形态（配了不生效且无提示）。故在 createDef 提取触发列时就校验、就抛错，
     * 让编排者立刻看到。占位符放具体原因。
     */
    ErrorCode WORKFLOW_TRIGGER_CONFIG_INVALID = new ErrorCode(1_041_006_009,
            "Workflow 触发器配置不合法：{}");

    /**
     * 复杂需求拆解为 Workflow DSL 失败（增量7a：plan-as-workflow 生成器轮次耗尽）
     *
     * <h3>🔴 为什么是 fail-close 抛错，而不是「拿最后一轮的草稿凑合」</h3>
     * 生成-校验-自修正循环里，模型可能反复产出结构非法的 DSL，到 {@code maxRounds} 仍没过双层校验。
     * 此时<b>不能</b>把最后一轮那份「看着像、实则编译不过」的 DSL 落库发布 —— 那会产出一个执行必炸的流程，
     * 而调用方无从判断。与规划器超限、agent_loop/iteration 超限「暴露问题而不硬猜」是同一条纪律（方案 §7）。
     *
     * <p>占位符放最后一轮的校验反馈（{@code AgentValidateResult.toFeedback()}），
     * 让调用方/日志能看到「到底卡在哪条结构规则上」，而不是一个笼统的失败。
     */
    ErrorCode WORKFLOW_PLAN_DSL_GENERATION_FAILED = new ErrorCode(1_041_006_010,
            "复杂需求拆解为 Workflow DSL 失败（轮次耗尽）：{}");

    /**
     * 定义非草稿，不可编辑 / 重新发布（增量8 / P4 管理页）
     *
     * <h3>🔴 为什么编辑只放行 DRAFT</h3>
     * 执行实例绑定「启动时的定义版本快照」（见 {@code agent_workflow_execution.workflow_version}），
     * 就地改一个 PUBLISHED 定义的 DSL，会让在途实例在 resume 时对不上节点——正是 DO 注释里的红线。
     * 要改已发布的流程，正确做法是 create 出一个新版本，而不是原地改旧版本。
     *
     * <h3>🔴 删除<b>不再</b>走这条判据，走 {@code WORKFLOW_DEF_NOT_DELETABLE}</h3>
     * 可删的状态是「草稿 + 已停用」（{@code WorkflowDefStatusEnum#isDeletable}），比可编辑多一档。
     * 原文案里的「不可编辑/删除/重新发布」因此去掉了「删除」二字：留着就是一句假话，
     * 会让照着码表排查的人去找一个不存在的强制点。
     * 顺带订正旧注释的另一处不准确表述——它说「DRAFT 从未被执行入口选中，故 DRAFT 必无引用」：
     * 前半句仍真（{@code run()} 只走 {@code getPublishedByCode}），后半句<b>不成立</b>，
     * 因为 sub_workflow 的 pin 写在<b>别人的 DSL 里</b>、可以按 (code,version) 精确指向一条草稿；
     * 只是运行期解析不到（{@code getSubWorkflowDef} 对 DRAFT 抛 {@code WORKFLOW_SUB_WORKFLOW_NOT_FOUND}）。
     * 故草稿的删除同样要过 pin 闸门，不存在「草稿一律安全」的快速通道。
     *
     * <p>与 {@code WORKFLOW_DEF_NOT_EXISTS} 分开：那条是「查不到 / 跨租户防探测」，这条是「查得到但状态不允许改」，
     * 处置动作完全不同（前者核对 id，后者请出新版本）。占位符放当前状态。
     */
    ErrorCode WORKFLOW_DEF_NOT_DRAFT = new ErrorCode(1_041_006_011,
            "Workflow 定义当前状态（{}）非草稿，不可编辑/重新发布；请基于它新建一个版本");

    /**
     * 定义未发布，不可停用（增量8 / P4 管理页）
     *
     * <p>停用（PUBLISHED→DISABLED）的语义是「曾发布过、现在不让新执行选中」。
     * 一个从没发布过的 DRAFT 谈不上「停用」——不想要它直接删除即可。占位符放当前状态。
     */
    ErrorCode WORKFLOW_DEF_NOT_PUBLISHED = new ErrorCode(1_041_006_012,
            "Workflow 定义当前状态（{}）未发布，不可停用；草稿请直接删除");

    /**
     * chat 触发器绑定的 Agent 编码不存在（增量12 / slice-3c 批次 B2：发布期 fail-close）
     *
     * <h3>🔴 为什么在「发布」时才校验，而不在 createDef/updateDef 落草稿时</h3>
     * 草稿允许半成品——作者可能先起草流程、稍后才建对应 Agent，落草稿就拦会阻断这种正常编辑顺序。
     * 但<b>发布</b>是「这条流程要真正生效」的动作：此时若 {@code trigger.chat.config.agentId} 指向一个
     * 不存在的 Agent 编码，聊天路径反查永远不命中、流程静静躺着永不触发，作者毫不知情
     * ——正是本平台头号缺陷「配了不生效且无提示」（计划 RK2）。故在 publishDef/publishDefById 翻状态前拦下。
     *
     * <p>只校验「该 code 有对应 agent_def 行」（任意状态），不要求 Agent 已发布：Agent 可能随后发布，
     * 强要求 PUBLISHED 会引入更硬的发布顺序耦合。占位符放填错的 agentId。
     */
    ErrorCode WORKFLOW_TRIGGER_CHAT_AGENT_NOT_FOUND = new ErrorCode(1_041_006_013,
            "chat 触发器绑定的 Agent 编码（{}）不存在，无法发布；请先创建该 Agent，或修正 trigger.chat.config.agentId");

    /**
     * Webhook 不存在或未启用（增量14c / P6：匿名入站触发）
     *
     * <h3>🔴 为什么文案刻意笼统、且「不存在」与「未发布」共用一条</h3>
     * 这是<b>匿名可达</b>的入站面：调用方无需登录即可 POST。若区分「path 不存在」与「path 存在但流程是草稿/停用」，
     * 就等于让任意外部方用可枚举的 path 探测「本租户配了哪些 webhook、哪些还没生效」。故一律回一句笼统的
     * 「不存在或未启用」，与其它资源「跨租户防探测」是同一纪律。反查只认 PUBLISHED（草稿/停用天然不命中）。
     */
    ErrorCode WORKFLOW_WEBHOOK_NOT_FOUND = new ErrorCode(1_041_006_014, "Webhook 不存在或未启用");

    /**
     * Webhook 签名 / 令牌校验失败（增量14c / P6：HMAC-SHA256 或静态令牌，fail-close）
     *
     * <h3>🔴 为什么 fail-close 且文案不透露「差在哪」</h3>
     * 配了 {@code authType=hmac/token} 的 webhook，签名/令牌不符（缺失、算法错、密钥不符、body 被篡改）时
     * <b>一律拒绝、绝不降级放行</b>——否则匿名入站面就等于裸奔，任何人拿到 path 就能拉起流程（可能烧 token、改数据）。
     * 文案只说「校验失败」，不区分「没带签名」还是「签名不对」，避免给攻击者反馈信号（padding-oracle 式的试探）。
     * 校验用<b>定长时间比较</b>（{@code MessageDigest.isEqual}），防按字节早退的时序侧信道。
     */
    ErrorCode WORKFLOW_WEBHOOK_SIGNATURE_INVALID = new ErrorCode(1_041_006_015, "Webhook 签名校验失败");

    /**
     * script 节点脚本执行失败（P7 收口：GraalJS 沙箱内脚本抛错 / 返回值非法）
     *
     * <h3>🔴 为什么单列一条而不是复用 {@code WORKFLOW_NODE_FAILED}</h3>
     * 脚本是本模块唯一「执行用户提供源码」的节点，失败原因（语法错、运行期异常、返回不可序列化值）
     * 与其它节点的「工具/模型调用失败」性质不同，作者排查时要一眼看出「是我写的 JS 挂了」。占位符放脚本错误摘要。
     */
    ErrorCode WORKFLOW_SCRIPT_EXECUTE_FAIL = new ErrorCode(1_041_006_016, "script 节点脚本执行失败：{}");

    /**
     * script 节点脚本执行超时（沙箱内死循环 / 超长计算被强制中断）
     *
     * <p>与 infra 数据接口后处理脚本同款纪律：独立线程执行 + 到点 {@code Context#close(true)} 强制中断，
     * 占位符放超时毫秒数，便于作者知道该收敛脚本还是调大 timeoutMs。
     */
    ErrorCode WORKFLOW_SCRIPT_TIMEOUT = new ErrorCode(1_041_006_017, "script 节点脚本执行超时（超过 {} ms 被中断）");

    /**
     * 沙箱测试禁止关闭「写操作 mock」
     *
     * <p>沙箱的定义就是「真跑草稿 DSL 但写操作被 mock、无真实副作用」。显式传 {@code mockWrites=false}
     * 等于要求在沙箱里真跑写操作 —— 那是脚枪（误改生产数据），v1 直接拒绝，不给关闭 mock 的口子。
     */
    ErrorCode AGENT_SANDBOX_MOCK_WRITES_REQUIRED = new ErrorCode(1_041_006_018,
            "沙箱测试必须 mock 写操作，不支持关闭（mockWrites=false）");

    /**
     * 变更清单的入参不合法：{@code dsl} 与 {@code id} 必须<b>恰好给一个</b>
     *
     * <p>🔴 两个都给时<b>不许自己挑一个</b>：清单是给人做发布/审批决定用的，
     * 「我传了手上的草稿 dsl，看到的却是库里 id 那一版的影响面」比报错坏得多 ——
     * 他会据一份不是自己那份的清单点同意。同理两个都不给也不能返回一份空清单
     * （空清单的 headline 是「本次不产生任何写操作」，那是一句假话）。
     *
     * <p>占位符是具体原因，故「都没给」与「都给」共用一条码但文案不同。
     */
    ErrorCode WORKFLOW_MANIFEST_SOURCE_INVALID = new ErrorCode(1_041_006_019, "变更清单的入参不合法：{}");

    /**
     * 发布闸门拒绝：DSL 未通过 V1~V19 体检（S4b，fail-close）
     *
     * <p>🔴 <b>文案必须逐条列出 error，不能只给条数</b>：这条码是「用户点了发布、什么都没发生」时
     * 唯一能到达他的文字。只说「有 3 项不通过」等于让他回去逐个节点猜；而 {@code AgentValidateResult}
     * 的类注释已经要求每条 error 自带「怎么改」，那份工夫只有在这里被原样带出去才算没白花。
     *
     * <p>⚠️ 但要知道它在今天的前端能走多远：{@code axios/service.ts} 对业务错误码只做
     * {@code ElNotification.error({title: msg})} 然后 {@code Promise.reject('error')}（字面量，不带 msg）
     * ⟹ 调用方拿不到这段文字，用户看到的是一个 4.5 秒自动消失的通知（台账 #91）。
     * 所以可读的那条路由前端预检承担（{@code /validate} 返回 200 + 结构化 errors），
     * 本条码是<b>权威判定</b>与兜底通道，不是主展示通道。
     *
     * <p>🔴 <b>刻意不提供 force / skipValidation 入参，也没有 yaml 降级开关</b>（用户决定 1）。
     * 想发布只有一条路：把 DSL 改到真的通过。任何「二次确认强推」的按钮都会让 V1~V19 变成建议。
     */
    ErrorCode WORKFLOW_DEF_VALIDATE_FAILED = new ErrorCode(1_041_006_020,
            "发布被拒绝：流程未通过发布体检，请逐条修掉后再发布。原因：{}");

    /**
     * 业务编码已被占用：新建定义 / 复制定义时 {@code code} 撞上了同租户已有的流程（S5-b，修 G12）
     *
     * <p>🔴 <b>查重只落在「新开一个 code」的两条路径上</b>：{@code createDef(SaveReqVO)} 与 {@code copyDef}。
     * <b>绝不能下沉进 {@code insertNewVersion}</b> —— 它有 3 个调用方，其中 {@code rollbackToVersion}
     * 是<b>故意</b>传同一个 code 的（回滚 = 给这条流程加一个新版本），下沉进去回滚当场打死；
     * 而 {@code insertNewVersion} 里那句 {@code selectList(code)} 只为算 {@code version = max+1}，不是查重。
     *
     * <p>⚠️ 不查重的后果不是「报错难看」而是<b>静默串味</b>：撞上已有 code 会表现为
     * 「给别人的流程加了个版本」，两个作者此后互相覆盖对方的最新版。
     *
     * <p>🔴 与 {@code AGENT_DEF_CODE_EXISTS} 同文案、不同码：两者是不同的资源（{@code agent_def} vs
     * {@code agent_workflow_def}），前端按码分派提示与埋点，共用一条码会把「哪个资源重名」丢掉。
     */
    ErrorCode WORKFLOW_DEF_CODE_DUPLICATE = new ErrorCode(1_041_006_021,
            "业务编码({})已被占用，请换一个编码");

    /**
     * Webhook 入站被限流（B2-5 / R-10 ①：匿名入口收口）
     *
     * <h3>🔴 为什么这条是「安全缺陷」而不是「体验优化」</h3>
     * {@code AgentWebhookController} 是本模块<b>唯一的匿名可达面</b>（挂 {@code @PermitAll}），
     * 在它前面没有任何按调用方身份计量的手段。没有限流，就是一个谁都能打的无鉴权入口：
     * 打满它能反复无头拉起流程（烧 token、写业务数据），也能把图执行线程池占死。
     *
     * <h3>🔴 为什么文案不透露「是哪一层限的」</h3>
     * 与 {@link #WORKFLOW_WEBHOOK_SIGNATURE_INVALID} 同一纪律：限流分 IP 与 path 两层，
     * 若回文案区分开，等于把「换一个来源 IP 就能继续打同一个 path」这条绕过路径直接告诉对方。
     * 故两层共用这一条笼统文案，占位符只放建议的重试等待秒数（对合法调用方有用，对攻击者无信息增量）。
     */
    ErrorCode WORKFLOW_WEBHOOK_RATE_LIMITED = new ErrorCode(1_041_006_022,
            "Webhook 请求过于频繁，请 {} 秒后重试");

    /**
     * Webhook 请求体超过整体上限（B2-4 / R-10 ②：入站处置的「第一道闸」）
     *
     * <h3>🔴 与 {@code UntrustedInputSanitizer} 那道「按叶子限长」的分工，别混为一谈</h3>
     * 两道闸管的是<b>不同的量</b>，缺一不可：
     * <table border="1">
     *   <caption>两道闸的分工</caption>
     *   <tr><th></th><th>本条（第一道，HTTP 层）</th><th>sanitizer（第二道，进 state 前）</th></tr>
     *   <tr><td>量什么</td><td><b>整个原始 body</b> 的字符数</td><td><b>每一个字符串叶子</b>的字符数</td></tr>
     *   <tr><td>阈值</td><td>{@code enlev.agent.webhook.max-body-length}（默认 1 MiB）</td>
     *       <td>{@code enlev.agent.sanitize.*}，按污点源分档</td></tr>
     *   <tr><td>动作</td><td><b>拒</b>：抛本条码，body 一个字符都不进 state</td>
     *       <td><b>截</b>：超出的尾巴丢掉，前面的部分照常进 state</td></tr>
     *   <tr><td>调用方是否知情</td><td><b>知情</b>：拿到非零 code</td>
     *       <td><b>不知情</b>：仍是 HTTP 200 + {@code data:true}，只有服务端 warn</td></tr>
     *   <tr><td>覆盖范围</td><td>只有 webhook（另两个源的载荷由平台自己从库行拼，不经 HTTP）</td>
     *       <td>webhook / form_event / schedule 三源全覆盖</td></tr>
     * </table>
     *
     * <h3>🔴 为什么必须存在这一道，只留 sanitizer 那道会出现「假绿」</h3>
     * 只靠 sanitizer 的话，一个 500 MB 的 body 会被<b>完整解析成 JSON、完整遍历每个叶子</b>，
     * 然后每个叶子各自截断 —— 我们为一个注定要被削减的请求付出了全部的 CPU 与内存，
     * 而调用方拿到的是 {@code 200 + data:true}：它<b>完全不知道自己的数据被削掉了 99%</b>。
     * 那正是本平台头号缺陷形态「配了不生效且无提示」的入站翻版。
     * 所以「削」这件事必须有一个<b>会出声</b>的版本，出声的方式就是拒掉整条请求。
     *
     * <h3>🔴 为什么拦在「反查流程定义」之前</h3>
     * 两个理由，第二个是可测性：
     * <ol>
     *   <li>匿名方不该能用一个超大 body 换到我们去做一次 DB 查询 + 一次 DSL 解析 + 一次验签；</li>
     *   <li>拦在最前面意味着<b>无论 path 存不存在都会先返回本条码</b>，
     *       故「超大 body 被拒」可以拿一个不存在的路径真发 HTTP 请求实测，
     *       不必为此去发布一条真的 webhook 流程、也不必真的拉起执行（不烧 token）。</li>
     * </ol>
     *
     * <h3>⚠️ 本条<b>不</b>承诺「护住内存」，别把它当 OOM 防线</h3>
     * 走到这里时 Spring 的 {@code StringHttpMessageConverter} <b>已经把整个 body 读进一个 String 了</b>。
     * 而对 {@code application/json} 这种非表单编码的请求，Tomcat 的 {@code maxPostSize} 并不生效，
     * 也就是说<b>容器层默认没有字节上限</b>。真要按字节早拒，得在 body 被读之前
     * （Filter 里看 {@code Content-Length}）就动手 —— 而 {@code Content-Length} 是客户端可控的、
     * 且 chunked 传输根本没有它。本轮<b>不做</b>这件事：本条闸门的职责是
     * 「别为一个注定被削减的请求付出解析代价」+「让调用方知情」，不是内存保护。
     *
     * <p>🔴 上面那句「别付出解析代价」也要按<b>比较级</b>读，不是「一分不付」：
     * 本条省掉的是 <b>JSON 解析 + 逐叶子遍历 + 逐叶子截断</b>，
     * 省不掉的是<b>已经发生</b>的两次 body 物化 —— 一次是上面说的 converter，
     * 另一次是框架 {@code ApiAccessLogInterceptor#preHandle} 在非 prod 环境为打日志
     * 而调的 {@code ServletUtils.getBody}（该拦截器由
     * {@code EnlevApiLogAutoConfiguration#addInterceptors} <b>无条件</b>注册，
     * 不看 {@code enlev.access-log.enable}；那个开关只管 {@code ApiAccessLogFilter} 的落库链，
     * 本仓 local / prod 两份 profile 都显式关着，故<b>落库那半边实测没有发生</b>）。
     * 实测（{@code _logs/b2-backend-start-2.log}）：被本条拒掉的 3 个 body 仍被原样打成了
     * 1048561 / 1048562 / 1199985 字符的日志行，合计约 3.3 MB。
     * 🔴 这一条落账 <b>N32</b>，本轮不修：它跨到 {@code enlev-framework}，
     * 且最省事的一行修法（给 webhook 端点加 {@code @ApiAccessLog(requestEnable = false)}）
     * 有真实取舍 —— 那会同时抹掉这个<b>最值得留审计的匿名入口</b>的请求体审计。
     *
     * <p>占位符依次是 实际字符数、上限。
     */
    ErrorCode WORKFLOW_WEBHOOK_BODY_TOO_LARGE = new ErrorCode(1_041_006_023,
            "Webhook 请求体过大（{} 字符，上限 {}），已拒绝；请拆分后重发");

    /**
     * 执行实例绑定的 Workflow 定义已停用（{@code DISABLED}），不允许再恢复
     *
     * <h3>🔴 为什么 resume 要拦、而 run 不需要这条</h3>
     * {@code run()} 走 {@code getPublishedByCode}，DISABLED 的定义压根选不出来，天然拦住了。
     * {@code resume()} 不一样：它按 executionId 直接取执行行，再按行里存的 {@code workflow_def_id}
     * 取定义 —— 这条路<b>不经过「已发布」那道筛</b>，所以改造前一个已停用的定义照样能被恢复执行。
     * 「停用」这个动作对管理员的承诺是「别再跑它了」，而挂起中的执行正是最容易绕过承诺的那一条：
     * 它可以停在那儿几天，等停用之后才被人点恢复。
     *
     * <h3>⚠️ 与 {@code WorkflowDefStatusEnum} 类注释那句「在途实例仍能按快照跑完」不冲突</h3>
     * 那句讲的是<b>已经在跑</b>的图不因为定义被停用而中途夭折（它连的是自己启动时的版本快照）。
     * resume 不是「跑完」，是<b>重新发起一次</b>图执行（重新编译 DSL、重新向模型/工具发起调用、
     * 重新产生副作用）—— 那属于「停用之后还让它跑」，正是要拦的。
     *
     * <p>🔴 刻意<b>不</b>顺手把执行行改成终态：它确实还挂在 checkpoint 上，
     * 写成 FAILED 会让「定义被停用」和「执行自己失败了」在库里长成同一个样子（撞红线第 7 条：
     * 不替 error alert 编病因）。行保持 INTERRUPTED，管理员要清场请用执行历史抽屉里的「取消」。
     *
     * <p>占位符是定义的 {@code code}。
     */
    ErrorCode WORKFLOW_DEF_DISABLED_NOT_RESUMABLE = new ErrorCode(1_041_006_024,
            "Workflow 定义({})已停用，挂起中的执行不能再恢复；如需清场请在执行历史里取消它");

    /**
     * 删除被<b>活定义的 sub_workflow pin</b> 拦住（迭代七 D3 引入；迭代八起常规删除档也走这道闸门）
     *
     * <h3>🔴 号段不变，只去掉名字与文案里的「强制」二字</h3>
     * 改名不改号：这三个号进过历史日志，改号会让旧日志对不上码表。
     * 去掉「强制」是因为闸门本身<b>不再只属于管理员档</b>——常规删除（{@code deleteDef} /
     * {@code deleteDefList}）现在走的是同一份 {@code AgentWorkflowDefServiceImpl#assertNotPinned}，
     * 名字里留着 {@code FORCE_DELETE} 就成了代码里的假话。
     *
     * <h3>🔴 为什么连「强制」档也要拦这一条</h3>
     * 「强制」强制的是<b>状态</b>（PUBLISHED/DISABLED 也能删）与<b>执行史</b>（连带级联清掉），
     * 不是「无视引用关系」。sub_workflow 的 pin 写在<b>别人的 DSL 里</b>：删掉被 pin 的那一行，
     * 引用方下一次编译就抛 {@code WORKFLOW_SUB_WORKFLOW_NOT_FOUND} —— 那是一条<b>活定义</b>被你弄坏了，
     * 而它的作者对此毫不知情，正是本平台头号缺陷「配了、存在了、但不生效」的删除版。
     *
     * <h3>🔴 为什么不改成「顺手把引用方的 DSL 也改掉」</h3>
     * 那要写别人的 PUBLISHED 定义，直接撞历史快照不可变纪律（见 {@code WORKFLOW_DEF_NOT_DRAFT}）；
     * 而且「改成指向哪一版」是个只有引用方作者能回答的问题。故这里只做<b>拒绝 + 点名</b>：
     * 处置动作留给人（先删/先改引用方，再回来删这一条）。
     *
     * <h3>⚠️ 批量删除时「同批一起删的行」不算引用方</h3>
     * A 的 DSL pin 住了 B，而 A、B 在同一次批删里 ⟹ 放行：批删之后 A 也没了，那条 pin 不会悬空。
     * 判据是把<b>整批 id 集合</b>同时用于「跳过自己」与「同 code 还有没有别的活 PUBLISHED 行」两处比较——
     * 漏掉后一处是真 bug：同 code 的 v1+v2 一起勾删、外部有一条未 pin 版本的引用时，
     * v2 会被当成存活者而放行，批删之后那条外部引用就悬空了。见 {@code #findSubWorkflowReferencers}。
     *
     * <p>占位符依次是：被删定义的 {@code code@version}、引用方条数、引用方逐条清单
     * （形如 {@code #123 wf_parent@2 的节点(call_sub)}）。
     */
    ErrorCode WORKFLOW_DEF_DELETE_PINNED = new ErrorCode(1_041_006_025,
            "Workflow 定义({})仍被 {} 条活定义的 sub_workflow 节点 pin 住，不能删除：{}");

    /**
     * 删除被<b>未终态执行</b>拦住（迭代七 D3 引入；迭代八起常规删除档也走这道闸门）
     *
     * <h3>🔴 号段不变，只去掉名字与文案里的「强制」二字</h3>
     * 理由同 {@code WORKFLOW_DEF_DELETE_PINNED}：常规删除档（DRAFT / DISABLED）现在也走
     * {@code AgentWorkflowDefServiceImpl#assertNoUnfinishedExecutions}，判据只有一份。
     * DISABLED 行是「曾发布过」的，名下完全可能还有在途或挂起的执行——这道闸门正是为此存在。
     *
     * <h3>🔴 判据用 {@code WorkflowExecutionStatusEnum#isFinal} 的正面形态，不是 {@code !isCancellable}</h3>
     * 非终态 = CREATED / RUNNING / INTERRUPTED / COMPENSATING 四档，外加「status 是 NULL 或未知值」
     * （{@code isFinal} 对它们返回 false，故一并拦住 = fail-close）。
     * 拦住的理由分两类：RUNNING/INTERRUPTED/COMPENSATING 是<b>还会有人写这几行</b>
     * （图跑完要 settle、反做要落 COMPENSATED），删了等于把它脚下的地抽走；
     * NULL/未知值则是「这行到底是什么状态我说不清」，说不清就不许删。
     *
     * <p>处置动作是现成的：{@code POST /agent/workflow/cancel} 能把 CREATED/RUNNING/INTERRUPTED
     * 三档 CAS 成 CANCELLED（终态），COMPENSATING 只能等它自己落定。
     *
     * <p>占位符依次是：被删定义的 {@code code@version}、未终态条数、逐条 {@code #executionId=STATUS}。
     */
    ErrorCode WORKFLOW_DEF_DELETE_HAS_UNFINISHED = new ErrorCode(1_041_006_026,
            "Workflow 定义({})名下还有 {} 条未终态执行，不能删除：{}");

    /**
     * 删除时<b>释放 checkpoint 真失败</b>（迭代七 D3 引入；迭代八起常规删除档也走同一份级联）
     *
     * <h3>🔴 号段不变，只去掉名字与文案里的「强制」二字</h3>
     * 理由同 {@code WORKFLOW_DEF_DELETE_PINNED}。
     *
     * <h3>🔴 为什么这一步失败要整单中止，而不是「跳过它继续删」</h3>
     * {@code GRAPH_THREAD} / {@code GRAPH_CHECKPOINT} 是 SAA 的表，<b>既没有 tenant_id 也没有本平台任何业务外键</b> ——
     * 「哪个 def 的哪次执行对应哪个 thread」只记录在执行行的 {@code thread_name} 上。
     * 先删执行行、再释放不成的话，那些快照就成了<b>永远无法寻址</b>的孤儿：查不出属于谁、
     * 也没有任何接口能再删它（{@code release} 要的正是那个已经没了的 thread_name）。
     * 故顺序固定为「先逐条释放 checkpoint，再动任何一行业务数据」。
     *
     * <h3>🔴 什么样的失败才走到这个错误码：只有「真失败」</h3>
     * {@code MysqlSaver#releasedCheckpoints} 只发一条
     * {@code UPDATE GRAPH_THREAD SET is_released = TRUE WHERE thread_name = ? AND is_released = FALSE}；
     * 影响 0 行时它抛 {@code IllegalStateException("Thread '%s' not found or already released")}，
     * 连不上库/SQL 报错时抛 {@code Exception("Unable to release checkpoint", cause)}（2026-10-08 字节码实测）。
     * 前者意味着<b>目标状态已达成</b>，实现里记一行 warn 后继续，<b>不</b>落到本错误码；
     * 只有后者才中止。否则一单里两条执行、第二条失败就会造成死结：
     * 中止 ⟹ 重跑时第一条已 released ⟹ 再抛 ⟹ 这行定义永远删不掉。
     *
     * <h3>⚠️ 中止后是<b>可重入</b>的</h3>
     * 第 3 条真失败时，前 2 条的 thread 已标 {@code is_released}，但<b>业务行一行没动</b>；
     * 修好原因后重跑同一次删除即可收尾（前 2 条这次走上面那条 warn 分支）。
     * ⚠️ 注意「释放」不删 {@code graph_checkpoint} 的行，只让 SAA 读不出来 —— 见
     * {@code AgentWorkflowDefService#forceDeleteDef} 的「级联顺序」那一节。
     *
     * <p>占位符依次是 {@code thread_name} 与底层原因（原样带上，不编病因）。
     */
    ErrorCode WORKFLOW_DEF_DELETE_CHECKPOINT_FAILED = new ErrorCode(1_041_006_027,
            "释放 checkpoint({})失败，本次删除已中止（业务数据未改动）：{}");

    /**
     * 定义状态不可删除（迭代八：常规删除档的状态闸门）
     *
     * <h3>🔴 判据在 {@code WorkflowDefStatusEnum#isDeletable}，不在这里</h3>
     * 草稿与已停用放行、已发布拒删。与 {@code WORKFLOW_DEF_NOT_DRAFT}（编辑闸门，只放行草稿）
     * 是两个不同的结论，故必须是两个码：合成一个会让「点保存被拒」和「点删除被拒」给出同一句提示，
     * 而两者的处置动作完全不同（前者出新版本，后者先停用）。
     *
     * <h3>为什么已发布一律拒删、而不是「没有执行史就放行」</h3>
     * PUBLISHED 是<b>正在被选用</b>的那一版：触发入口（webhook / 定时 / 手动）按 code 回落到它。
     * 删掉等于把一个对外承诺过的入口悄悄拔掉——调用方拿到「流程不存在」，运营侧只看到「列表少了一行」。
     * 要删就先停用，让「不再接受新执行」这件事显式发生过一次，在途实例也有机会跑完。
     * 确实要连已发布的一起抹掉，走管理员档 {@code POST /agent/workflow-def/force-delete}
     * （单独权限点，仅 super_admin）。
     *
     * <p>占位符放当前状态（与 {@code _011} / {@code _012} 同形）。
     */
    ErrorCode WORKFLOW_DEF_NOT_DELETABLE = new ErrorCode(1_041_006_028,
            "Workflow 定义当前状态（{}）不可删除，仅草稿与已停用可删；已发布的请先停用");

    // ========== 工具目录 / 用户自定义工具 1-041-007-000 ==========
    // 增量5a：agent_tool_def 承载「无 Java 代码」的用户自定义工具（本期 HTTP）。
    // 代码工具仍由 AgentToolRegistry 权威、只读暴露，不落这张表——见 AgentToolRegistry 类注释。

    /**
     * 用户自定义工具不存在
     *
     * ⚠️ 与其它资源同理：跨租户共用「不存在」文案，避免用 tool_key 探测他租户注册了哪些工具。
     */
    ErrorCode AGENT_TOOL_DEF_NOT_EXISTS = new ErrorCode(1_041_007_000, "工具({})不存在");

    /**
     * tool_key 同租户内重复
     */
    ErrorCode AGENT_TOOL_DEF_KEY_DUPLICATE = new ErrorCode(1_041_007_001, "工具键({})已存在，请换一个");

    /**
     * tool_key 与代码工具重名
     *
     * 🔴 单独一条而不是并进「重复」：代码工具由 {@code AgentToolRegistry} 权威解析（registry-first），
     * 用户工具永远抢不到这个名字。与其让它登记成功却永远调不到（平台头号缺陷「配了不生效且无提示」），
     * 不如登记时就拦下并说清原因。
     */
    ErrorCode AGENT_TOOL_DEF_KEY_CONFLICT_CODE_TOOL = new ErrorCode(1_041_007_002,
            "工具键({})与内置代码工具重名，代码工具优先，请换一个名字");

    // 🔴 1_041_007_003 ~ 005 曾表达 HTTP 工具语义（config 不合法 / SSRF 拦截 / 调用失败），
    // 2026-09-29 随 HTTP 来源一并删除（自定义工具改为关联数据接口，见 ToolSourceEnum 类注释）。
    // 号段刻意不回收、不复用，避免新码撞上旧含义造成误读。

    /**
     * 工具来源暂不支持执行
     *
     * source 枚举里 FORM/SCRIPT 是预留位（尚无执行器），当前只放行 DATA_INTERFACE。
     * 遇到预留来源单独报这条，方便区分「没这个工具」与「这类工具还没实现执行」。
     */
    ErrorCode AGENT_TOOL_SOURCE_UNSUPPORTED = new ErrorCode(1_041_007_006, "工具来源({})暂不支持执行");

    /**
     * 风险等级缺失或非法
     *
     * 🔴 登记<b>必填、无默认</b>：会改数据的接口不该被默认当成只读，否则一个写型数据接口
     * 会因为「没填风险」而被当作 READ_ONLY 放进 Agent 循环自动执行、不进 Plan 确认。
     * 强制管理员显式选一个合法的 {@code RiskLevel}。占位符是收到的非法值。
     */
    ErrorCode AGENT_TOOL_DEF_RISK_INVALID = new ErrorCode(1_041_007_007,
            "风险等级({})不合法，必须是 READ_ONLY/LOW/MEDIUM/HIGH/IRREVERSIBLE 之一");

    /**
     * 关联的数据接口不合法：编码为空/JSON 解析失败/mode 非法，或该编码在数据接口目录里查不到、已停用
     *
     * <p>🔴 为什么登记时就查一遍而不是等调用失败：这正是平台头号缺陷「配置保存成功但不生效且无提示」
     * 的形状——绑了个拼错的 code，工具登记得好好的，直到某天 AI 调它才报 infra 的中文异常，
     * 而那时报错的人（AI/终端用户）跟配错的人（管理员）通常不是同一个。
     * 占位符依次是 tool_key、具体原因。
     */
    ErrorCode AGENT_TOOL_DEF_INTERFACE_INVALID = new ErrorCode(1_041_007_008,
            "工具({})关联的数据接口不合法：{}");

    /**
     * 绑定了可写的数据接口却没声明 {@code infra:data-interface:invoke} 权限
     *
     * <p>🔴 这是<b>安全边界</b>，不是便利性校验：{@code DataInterfaceApi} 本身不做任何权限校验
     * （见其类注释约定 3），写门完全靠工具行上的 permissions 由 {@code AgentSecurityGateway} 把守。
     * 少了这个权限点，一个只登录的用户就能经 AI 执行 INSERT/UPDATE/DELETE。
     * 故登记时直接拒（而不是悄悄替管理员补上——静默改安全字段比报错更难发现）。占位符是 tool_key。
     */
    ErrorCode AGENT_TOOL_DEF_INVOKE_PERMISSION_MISSING = new ErrorCode(1_041_007_009,
            "工具({})关联的是可写数据接口（全能力执行），所需权限里必须包含 infra:data-interface:invoke");

    // ========== Agent 定义 / Agent Builder 1-041-008-000 ==========
    // P5 第一刀：agent_def 承载「智能体」目录（人设 + 版本化 + 发布/停用），
    // 生命周期与错误码语义刻意与 workflow_def（1_041_006_*）对齐，方便两套定义管理互相印证。

    /**
     * Agent 定义不存在
     *
     * ⚠️ 与其它资源同理：「别的租户的定义」与「不存在」<b>刻意共用这一条</b>，
     * 避免用连续可枚举的 id 探测他租户有哪些 Agent。
     */
    ErrorCode AGENT_DEF_NOT_EXISTS = new ErrorCode(1_041_008_000, "Agent 定义不存在");

    /**
     * Agent 定义非草稿，不可就地编辑
     *
     * <h3>🔴 为什么就地编辑只放行 DRAFT</h3>
     * 已发布版本是「不可变快照」：就地改一个 PUBLISHED Agent 的人设，会让按该版本路由的在途对话
     * 前后不一致（同一会话中途换了人设）。要改已发布 Agent，正确做法是<b>派生一个新草稿版本</b>
     * （需求11 起前端「编辑」按钮对非草稿自动走 {@code deriveDef}，并先问一句「将派生新版本」），
     * 而不是原地改。
     *
     * <p>删除<b>不</b>走这条：草稿直接删，已发布要先停用再删（见 {@link #AGENT_DEF_PUBLISHED_NOT_DELETABLE}）。
     * <p>发布也<b>不</b>走这条：DISABLED 可以重新发布上线（配置是完整快照，「临时下线排障再放回来」是常态），
     * 逼它派生一个内容完全相同的 v(n+1) 纯属多余动作。
     * <p>与 {@link #AGENT_DEF_NOT_EXISTS} 分开：那条是「查不到 / 跨租户防探测」，这条是「查得到但状态不允许改」，
     * 处置动作完全不同（前者核对 id，后者请出新版本）。占位符放当前状态。
     */
    ErrorCode AGENT_DEF_NOT_DRAFT = new ErrorCode(1_041_008_001,
            "Agent 定义当前状态（{}）非草稿，编辑将基于它派生一个新草稿版本；删除请先停用");

    /**
     * Agent 定义未发布，不可停用
     *
     * <p>停用（PUBLISHED→DISABLED）的语义是「曾发布过、现在不让选用」。
     * 一个从没发布过的 DRAFT 谈不上「停用」——不想要它直接删除即可。占位符放当前状态。
     */
    ErrorCode AGENT_DEF_NOT_PUBLISHED = new ErrorCode(1_041_008_002,
            "Agent 定义当前状态（{}）未发布，不可停用；草稿请直接删除");

    /**
     * Agent 绑定的工具不合法（增量12 / slice-3b 批次 B4：发布期 fail-close）
     *
     * <h3>🔴 为什么发布时才校验、且只放行只读工具</h3>
     * 聊天里的 agentic 工具循环<b>无人工确认</b>，放开写工具等于让模型无人监督地建表/改表
     * （违背平台铁律「用户确认前不执行任何写操作」）。与 {@code agent_loop} 节点「编译期 + 运行期两道只读闸」同理，
     * 这里在 Agent <b>发布</b>时先拦一道：{@code tool_keys} 里若有未登记的工具、或<b>声明</b>风险级
     * （{@code getDeclaredRisk()}）要求确认的写工具，直接拒发布。草稿不拦（允许半成品），
     * 发布是「要真正生效」的动作，必须 fail-loud。占位符放具体原因。
     *
     * <p>🔴 判据读的是声明值而不是 {@code isRequireConfirm()}（= 租户生效值）：
     * {@code agent_tool_override} 一行数据就能把 IRREVERSIBLE 压成 READ_ONLY，
     * 而聊天工具循环是<b>无人值守</b>的，没有 Plan 确认兜着（D11）。详见
     * {@code AgentDefServiceImpl#validateToolKeys} 与 {@code AgentChatToolLoop} 运行期那道同款闸。
     */
    ErrorCode AGENT_DEF_TOOL_INVALID = new ErrorCode(1_041_008_003,
            "Agent 绑定的工具不合法：{}");

    /**
     * 聊天 agentic 工具循环达到最大步数仍未收敛出 answer（增量12 / slice-3b 批次 B4b）
     *
     * <p>与 {@link #WORKFLOW_AGENT_LOOP_NOT_CONVERGED} 同款 fail-close 纪律（方案 §7：暴露问题而不硬猜），
     * 只是发生在聊天路径而非工作流 agent_loop 节点。到 {@code maxSteps} 还在 tool_calls 查个不停、始终不给 answer 时，
     * 绝不把某个中间观察当成最终答复返回。占位符放 maxSteps。
     */
    ErrorCode AGENT_CHAT_TOOL_LOOP_NOT_CONVERGED = new ErrorCode(1_041_008_004,
            "AI 助手调查了 {} 步仍未能给出结论，请缩小问题范围或补充信息后重试");

    /**
     * 系统内置智能体不可删除 / 停用 / 派生新版本（需求10）
     *
     * <h3>🔴 为什么内置行是例外</h3>
     * 「默认平台助手」不是一个被谁选用的业务 Agent，而是「聊天入口没选 Agent 时用哪段系统提示词」的平台设置
     * （{@code AgentAssistantServiceImpl} 在 {@code boundAgentDefId == null} 时读它的 persona）。
     * 停用或删除它，所有未选 Agent 的对话都会失去系统提示词、退化成「模型自由发挥」——
     * 那正是接 RAG 之前最难排查的状态（回答看起来通顺，但没有一句有手册依据）。
     * 所以它<b>只允许就地改</b>（persona / name / description），改完对下一句话立即生效。
     */
    ErrorCode AGENT_DEF_BUILTIN_READONLY = new ErrorCode(1_041_008_005,
            "「{}」是系统内置智能体，只能就地修改人设与名称，不可删除、停用或派生新版本");

    /**
     * 已发布的 Agent 不能直接删除，要先停用（需求11）
     *
     * <h3>🔴 为什么多这一步而不是直接删</h3>
     * PUBLISHED 意味着它此刻正出现在聊天入口的选择器里，可能有人正在用它对话。直接删会同时造成
     * 「选择器里消失但已开的会话仍绑着它」和「用量看板里出现归因不到的 code」。
     * 停用先把他从选择器摘掉、让在途对话自然收敛，再删就是安全的。草稿从未被选用，直接删。
     */
    ErrorCode AGENT_DEF_PUBLISHED_NOT_DELETABLE = new ErrorCode(1_041_008_006,
            "「{}」当前是已发布状态，不能直接删除；请先「停用」再删除");

    /**
     * 业务编码已被占用（需求11 复制查重 + 需求10 保留编码）
     *
     * <h3>🔴 为什么复制要查重、而新增不查</h3>
     * 新增时填一个已存在的 code 是<b>有意</b>的：同 code = 该 Agent 的新版本（见 {@code AgentDefDO} 类注释）。
     * 但「复制」的意图明确是「照这个做一个<b>另一个</b> Agent」，此时撞上已有 code 会静默变成
     * 「给别的 Agent 加了一个版本」——用户以为建了个新智能体，实际改动了别人的版本序列。
     * 这类「操作成功但结果不是我以为的那样」比报错贵得多，故在入口挡掉。
     *
     * <p>另用于挡住保留编码 {@code platform_assistant}（系统内置的默认平台助手）：允许再建一个同 code 的
     * 普通 Agent，列表上就会出现两行同名记录，而只有内置那行真的驱动聊天入口，改另一行毫无效果。
     * 占位符放冲突的 code。
     */
    ErrorCode AGENT_DEF_CODE_EXISTS = new ErrorCode(1_041_008_007,
            "业务编码({})已被占用，请换一个编码");

    // ========== 内置工具在线维护 1-041-009-000（2026-09-28 增量）==========
    // agent_tool_override 承载「内置（已注册）@AgentTool 工具」的覆盖值；执行体与默认值仍在代码里。
    // 见 AgentToolRegistry / AgentToolOverrideService 类注释。

    /**
     * 内置工具不存在于注册表
     *
     * 🔴 与 {@link #AGENT_TOOL_NOT_FOUND} 分开：那条是运行期网关「AI 调了一个查不到的工具」，
     * 这条是管理页维护时「提交的 toolName 根本不是一个已注册的内置工具」（多半是代码删了工具、
     * 库里还留着残覆盖行，或前端传错）。占位符放 toolName，提示管理员核对该工具是否仍在代码里。
     */
    ErrorCode AGENT_TOOL_OVERRIDE_TOOL_NOT_REGISTERED = new ErrorCode(1_041_009_000,
            "内置工具({})未注册，无法维护；请确认该工具仍存在于代码中");

    /**
     * 维护内置工具时放松了安全约束（风险调低 / 权限删少），但没有带上二次确认
     *
     * <p>占位符①放 toolName，②放一句<b>说清代价</b>的话（例如「风险 IRREVERSIBLE→READ_ONLY，
     * requireConfirm true→false」），不是干巴巴的「请确认」。
     *
     * <p>🔴 这条<b>不是</b>拦截放松 —— 2026-09-28 的产品决策（迭代四 §11 的 A-Ⅲ 又确认了一次）
     * 是「保留放松能力 + 落库审计 + 二次确认」，所以带上 {@code confirmRelax=true} 重提就能过。
     * 它拦的是<b>没看见代价就放松</b>：界面上有确认框，但 curl / 别的后台 / 将来的批量脚本
     * 都能绕过界面直接打这个接口。占位符②那句必须按放松的<b>真实</b>后果说话 ——
     * 删权限是真放松（{@code AgentSecurityGateway} 读生效权限串，删空后 AI 立刻只需登录就能调），
     * 降风险在迭代三把闸改读声明值之后只改界面标注（台账 D4-22），别把两者说成一样重。
     * 与 §3.1 那条「发布闸门没有强推后门」不冲突：那边是<b>去掉</b>一道判定，这边是<b>要求表态</b>，
     * 表完态照样落审计行。
     */
    ErrorCode AGENT_TOOL_OVERRIDE_RELAX_NEEDS_CONFIRM = new ErrorCode(1_041_009_001,
            "内置工具({})的本次维护会放松安全约束：{}。请确认已知晓上述后果后重新提交（确认动作会留审计痕迹）");

    // ========== 提示词模板 1-041-010-000（需求7，2026-09-29）==========
    // agent_prompt_template：管理员维护的可复用提问模板，聊天面板一键插入到输入框。

    /**
     * 提示词模板不存在
     *
     * <p>占位符放 id。含「已被别人删掉」与「他租户的行」两种情况——本表是 {@code TenantBaseDO}，
     * {@code selectById} 自带 {@code tenant_id=?}，故跨租户访问与不存在同样表现为这条错误，
     * 不泄漏「该 id 在别的租户存在」这一信息。
     */
    ErrorCode AGENT_PROMPT_TEMPLATE_NOT_EXISTS = new ErrorCode(1_041_010_000,
            "提示词模板({})不存在");

    // ========== 提示词模板用例 1-041-011-000（S7 / L5，2026-10-03）==========
    // agent_prompt_template_case：模板的「填好了的样例」。模板带 {{占位符}}，业务人员往往
    // 卡在「这个占位符里到底该写什么」；用例直接给一段可以照着发的完整正文。

    /**
     * 提示词模板用例不存在
     *
     * <p>与 {@link #AGENT_PROMPT_TEMPLATE_NOT_EXISTS} 同款口径：本表是 {@code TenantBaseDO}，
     * 跨租户访问与「真没有」同样表现为这条错误，不泄漏该 id 在别的租户存在。
     */
    ErrorCode AGENT_PROMPT_TEMPLATE_CASE_NOT_EXISTS = new ErrorCode(1_041_011_000,
            "提示词模板用例({})不存在");

    /**
     * 用例要挂的模板不存在
     *
     * <p>🔴 必须拦。{@code template_id} 是 {@code NOT NULL}：放过一个不存在的 id，
     * 这条用例就永远不会出现在任何模板的用例子表里 —— 建的时候返回成功、列表里也确实有它，
     * 只是没有任何入口能点到。这正是本平台头号缺陷「配了但不生效且无提示」的形状。
     */
    ErrorCode AGENT_PROMPT_TEMPLATE_CASE_TEMPLATE_NOT_EXISTS = new ErrorCode(1_041_011_001,
            "用例要挂的提示词模板({})不存在");

    /**
     * 期望分型取值不合法
     *
     * <p>只接受 {@code AgentIntentEnum} 的两个值或留空。留空的语义是「这条用例正处在
     * 问怎么做 / 让你去做的边界上，两种分型都算对」，不是「忘了填」——故校验时不拦空。
     *
     * <p>🔴 拦住拼错的值：分型校验脚本是按这一列挑用例真发一次的，一个 {@code Build} / {@code 变更}
     * 这样的值会让脚本静默跳过这条用例，看起来「全部通过」，实际一条都没验。
     */
    ErrorCode AGENT_PROMPT_TEMPLATE_CASE_INTENT_INVALID = new ErrorCode(1_041_011_002,
            "期望分型({})不合法，只能是 CHAT、BUILD，或留空表示「两种都算对」");

    // ========== 文档语料同步 1-041-012-000（手册知识库，2026-10-19）==========
    // enlev.agent.doc.root-path 指向手册仓库的 docs/ 目录。它是内置知识库唯一的语料来源，
    // 配错的表现是「同步返回 200、文档数 0」—— 与「跑成功了、只是恰好没文档」在界面上无法区分，
    // 所以这两种情况都必须抛错，不能降级成警告。

    /**
     * 文档语料根目录未配置
     *
     * <p>🔴 第一版靠标记目录逐级向上探测，探测必然落空（那个标记目录压根不存在），
     * 于是同步永久空转且不报错 —— 正是本平台头号缺陷「配了但不生效且无提示」的形状。
     * 现在改成必填：没配就抛这条，让人一眼知道要去配什么。
     */
    ErrorCode AGENT_DOC_ROOT_PATH_MISSING = new ErrorCode(1_041_012_000,
            "文档语料根目录未配置，请设置 enlev.agent.doc.root-path（手册仓库的 docs 目录）");

    /**
     * 文档语料根目录不是一个存在的目录
     *
     * <p>与 {@link #AGENT_DOC_ROOT_PATH_MISSING} 分开报错，是为了区分「忘了配」与「配错了路径」——
     * 后者在 Windows 上尤其常见（盘符、反斜杠、相对路径随启动方式变化）。
     */
    ErrorCode AGENT_DOC_ROOT_PATH_INVALID = new ErrorCode(1_041_012_001,
            "文档语料根目录({})不存在或不是目录，请检查 enlev.agent.doc.root-path");

}
