package cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.TriggerDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_TRIGGER_CONFIG_INVALID;

/**
 * 触发器提取器：把 DSL 里的 {@code trigger} 投影成 {@code agent_workflow_def} 的可 SQL 查询列（增量6）
 *
 * <h3>为什么需要这层投影</h3>
 * DSL 是整份原文落库（见 {@link AgentWorkflowDefDO} 类注释），无法按里面的 trigger 检索。
 * 而触发器家族需要两类反向查询：① form_event——来了一条表单变更事件，按 trigger_key 反查
 * 「哪些已发布流程在等这个事件」；② schedule——轮询器按 {@code trigger_next_fire_at<=now} 捞到点该触发的流程。
 * 这两类查询都必须走索引列，不能把全表 DSL 捞出来在应用层解析。本提取器在 {@code createDef} 落库前，
 * 把 trigger 的关键信息「摊平」到 {@code trigger_type / trigger_key / trigger_next_fire_at / owner_user_id} 四列。
 *
 * <h3>🔴 归一化：DSL 存全名，列里存短名</h3>
 * DSL 的 {@code trigger.type} 是全名 {@code "trigger.form_event"} / {@code "trigger.schedule"}（见 {@link TriggerDTO}），
 * 但 {@code AgentWorkflowDefMapper} 的查询条件用短名 {@code "form_event"} / {@code "schedule"}。
 * 本提取器剥掉 {@code trigger.} 前缀后落库，保证「写进去的」与「查出来的」逐字一致——这是触发器能否命中的命门。
 *
 * <h3>fail-close 时机：发布（createDef）即校验，而非触发时</h3>
 * 触发器是无头启动的，运行期没有「人」在旁边看报错。所以配置错误（form_event 缺 formCode、schedule 的 cron 非法、
 * eventType 写错）必须在<b>发布时</b>就抛 {@code WORKFLOW_TRIGGER_CONFIG_INVALID} 拦下——
 * 否则就是本项目头号缺陷「配了却不生效、且无任何提示」：流程静静躺着，永远不被触发，作者毫不知情。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class WorkflowTriggerExtractor {

    /**
     * DSL {@code trigger.type} 的统一前缀，落库时剥掉（列里存短名）
     */
    private static final String TYPE_PREFIX = "trigger.";

    /**
     * form_event 支持的 eventType 取值，与 infra {@code FormDataChangedEvent} 的 {@code TYPE_*} 常量逐字一致。
     * 提前校验取值：写错（如 "insert"）会导致「配了却永远匹配不到事件、且无提示」，发布时就该拦下
     */
    private static final Set<String> FORM_EVENT_TYPES = Set.of("create", "update", "delete");

    /**
     * webhook 的 path 白名单：单段 URL 安全字符（字母/数字/下划线/连字符），1~64 位。
     * 🔴 刻意不含 {@code / . % ? # 空格} 等——path 直接拼进入站 URL {@code /agent/webhook/{path}}，
     * 含斜杠会跨路由段、含 {@code .} 可能触发后缀匹配/路径穿越、含空白/百分号会破坏 URL 语义。
     * 发布时就卡死字符集，杜绝「配了个畸形 path 却已落库、入站时 404 或误匹配」。
     *
     * <p>🔴 public 是为了让 {@code WorkflowCopyRewriter} 用它<b>削</b>出合法 path 而不是抄一份「64」：
     * 复制流程时 path 必须改（否则两条流程抢同一个匿名入口），加后缀就会超长，
     * 而超长在 {@link #applyWebhook} 是硬拒 —— 前端复制对话框只让填 newCode/newName，
     * 用户<b>没有格子能改 path</b>，所以只能在改写时就把它削到匹配本正则为止（同
     * {@code ToolNodeExecutor.DEFAULT_OUTPUT_VAR} 那条 public 的理由）。
     */
    public static final Pattern WEBHOOK_PATH_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /**
     * webhook 支持的鉴权方式：{@code hmac}（HMAC-SHA256 签名）/ {@code token}（静态令牌）。必填、缺省即拒。
     *
     * <p>🔴 刻意<b>不含 {@code none}（不鉴权）</b>——「靠不可猜的 path 保安全」这个前提是假的：path 会被
     * 日志打印、被前端 URL 暴露、被浏览器历史和代理缓存留下，拿到 path 的人就能以<b>发布者身份</b>无头拉起
     * 整条流程（入站 body 进 state → 进 prompt → 进 BPM formVars）。这条攻击面收不住，只能禁掉。
     *
     * <p>未知值同样即拒（fail-loud，防「配了个不认识的 authType 却静默放行」——那等于把本应鉴权的 hook 裸奔）。
     *
     * <p>注意：本白名单在 {@code createDef/updateDef 落库前}生效（见 {@link #apply}），比发布期的 DSL 校验
     * （V15）更早；两处对 {@code none} 的拒绝是<b>刻意重叠</b>的纵深防御，不要去重。
     */
    private static final Set<String> WEBHOOK_AUTH_TYPES = Set.of("hmac", "token");

    // ==================== trigger.config 的键名 ====================
    // 🔴 只把「有第二个读者」的 4 个键提成常量：复制改写器（WorkflowCopyRewriter）要按同样的键名
    //    去 DSL 里找它们，两处各写一份字面量就是方案 §2-D7 禁止的第二份副本（台账 #100 那一族）。
    //    只有本类读的 eventType / agentId / authType 仍是字面量 —— 给单读者的键建常量只是噪声。

    /** {@code trigger.config.formCode}：form_event 订阅的表单编码 */
    public static final String CONFIG_FORM_CODE = "formCode";
    /** {@code trigger.config.cron}：schedule 的 6 段 cron 原文 */
    public static final String CONFIG_CRON = "cron";
    /** {@code trigger.config.path}：webhook 的入站路径段 */
    public static final String CONFIG_PATH = "path";
    /** {@code trigger.config.secret}：webhook 的验签密钥。⚠️ 只校验存在、绝不投影进列、绝不写日志 */
    public static final String CONFIG_SECRET = "secret";

    @Resource
    private DslParser dslParser;

    /**
     * 从 {@code def.getDsl()} 解析 trigger，把投影列填进 {@code def}（就地修改，供调用方随后 insert）
     *
     * <p>无 trigger（纯手动 run-stream 的定义）→ 三个 trigger_* 列留 null；ownerUserId 仍无条件记录。
     * 触发列的查询都带 {@code trigger_type=?} 过滤，故 null 行的历史 def 自然被排除，不受影响。
     *
     * @param def 待落库的定义（须已 setDsl）；本方法就地补齐 triggerType/triggerKey/triggerNextFireAt/ownerUserId
     */
    public void apply(AgentWorkflowDefDO def) {
        // ownerUserId 无条件记录：它是「谁发布的这条自动化」，触发时以此身份运行（ofSystem，权限由 DB 派生）。
        // 播种/测试上下文无登录用户时为 null —— fire 时 invoker fail-close 跳过 + 告警，绝不降级为匿名/系统放行
        def.setOwnerUserId(SecurityFrameworkUtils.getLoginUserId());

        WorkflowDefDTO parsed = dslParser.parse(def.getDsl());
        TriggerDTO trigger = parsed.getTrigger();
        if (trigger == null || StrUtil.isBlank(trigger.getType())) {
            return;
        }
        String type = normalizeType(trigger.getType());
        def.setTriggerType(type);
        Map<String, Object> config = trigger.getConfig();
        switch (type) {
            case "form_event" -> applyFormEvent(def, config);
            case "schedule" -> applySchedule(def, config);
            // chat：会话入口（增量12 / slice-3c 接线）——triggerKey 落「绑定的 Agent code」，
            // 供聊天路径按选中 Agent 的 code 反查「哪条已发布 chatflow 驱动它」（见 applyChat）
            case "chat" -> applyChat(def, config);
            // webhook：匿名 HTTP 入站面（增量14c / P6 接线）——triggerKey 落 URL 安全的 path，
            // 供 AgentWebhookController 按 /agent/webhook/{path} 反查「哪条已发布流程接这个 hook」（见 applyWebhook）
            case "webhook" -> applyWebhook(def, config);
            // 未知类型：fail-loud 拒发布。宁可让作者立刻看到「这个 type 不认识」，
            // 也不要静默记一个永远不触发的 trigger_type（同「配了不生效无提示」缺陷）
            default -> throw exception(WORKFLOW_TRIGGER_CONFIG_INVALID,
                    "不支持的触发器类型：" + trigger.getType());
        }
    }

    /**
     * form_event：triggerKey = {@code {formCode}:{eventType}}（eventType 小写），
     * 与 {@code FormDataChangedTriggerListener} 从事件构造的键逐字一致。无「下次触发时间」（事件驱动），next_fire 留 null
     */
    private void applyFormEvent(AgentWorkflowDefDO def, Map<String, Object> config) {
        String formCode = str(config, CONFIG_FORM_CODE);
        String eventType = str(config, "eventType");
        if (StrUtil.isBlank(formCode) || StrUtil.isBlank(eventType)) {
            throw exception(WORKFLOW_TRIGGER_CONFIG_INVALID, "form_event 触发器缺少 formCode 或 eventType");
        }
        String normalizedEvent = eventType.toLowerCase();
        if (!FORM_EVENT_TYPES.contains(normalizedEvent)) {
            throw exception(WORKFLOW_TRIGGER_CONFIG_INVALID,
                    "form_event 的 eventType 只能是 create/update/delete，实际为：" + eventType);
        }
        def.setTriggerKey(formCode + ":" + normalizedEvent);
    }

    /**
     * schedule：triggerKey = cron 原文（展示/去重标识），triggerNextFireAt = cron 的下次触发时间。
     * 轮询器扫 next_fire<=now 的行，触发前先 CAS 推进到再下一个 next(now)（先认领后触发，at-most-once）
     */
    private void applySchedule(AgentWorkflowDefDO def, Map<String, Object> config) {
        String cron = str(config, CONFIG_CRON);
        if (StrUtil.isBlank(cron)) {
            throw exception(WORKFLOW_TRIGGER_CONFIG_INVALID, "schedule 触发器缺少 cron");
        }
        // 🔴 用 Spring CronExpression 校验 + 算下次触发时间。它是 6 段（秒 分 时 日 月 周），
        //    不支持 Quartz 的 ?/L/W/#（本增量刻意不引 Quartz 动态注册，见计划 R8）。非法 cron 在此抛，拒发布
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException ex) {
            throw exception(WORKFLOW_TRIGGER_CONFIG_INVALID,
                    "schedule 的 cron 不合法（需 Spring 6 段格式，不支持 Quartz 的 ?/L/W/#）：" + cron);
        }
        LocalDateTime next = expression.next(LocalDateTime.now());
        if (next == null) {
            // 合法的 6 段 cron 理论上总有下次触发时间；极端算不出时兜底拒发布，避免落一个 null next_fire 永不触发
            throw exception(WORKFLOW_TRIGGER_CONFIG_INVALID, "schedule 的 cron 算不出下次触发时间：" + cron);
        }
        def.setTriggerKey(cron);
        def.setTriggerNextFireAt(next);
    }

    /**
     * chat：会话入口（增量12 / slice-3c 接线）。triggerKey = 绑定的 Agent 编码（{@code agent_def.code}），
     * 供聊天路径按「选中 Agent 的 code」反查「哪条已发布 chatflow 驱动它」
     * （{@code AgentWorkflowDefMapper.selectPublishedChatTriggerByAgentCode}），命中即把用户消息作为工作流输入跑起来。
     *
     * <p>🔴 {@code agentId} 承载的是 Agent 的 <b>code</b>（版本稳定键）而非 numeric id：{@code agent_def} 与
     * {@code agent_workflow_def} 都是「同 code 多版本行」，numeric id 指向单一版本、发新版即失效，code 才是稳定执行键
     * （DSL §4.1 把该 config 键命名为 {@code agentId}，此处按 code 语义使用，见计划 DD2）。
     *
     * <p>{@code agentId} 可空：留空 = 该 chat 流程不绑定任何 Agent（仅手动 run-stream / 规划草稿用），trigger_key 保持
     * null，与前端新建骨架 {@code WorkflowDefFormDialog.DEFAULT_DSL}（无 agentId）向后兼容——故不 fail-close。
     *
     * <p>🔴 发布期<b>不</b>校验「该 code 是否有对应已发布 Agent」：工作流可能先于 Agent 发布，强校验会引入发布顺序耦合。
     * 填错 code 的后果是聊天路径反查不命中、静默回落普通聊天（计划 RK2），故此处 log 记录绑定便于排查；
     * 更强的发布期校验 / Agent 侧「由工作流驱动」标识记后续。
     */
    private void applyChat(AgentWorkflowDefDO def, Map<String, Object> config) {
        String agentCode = str(config, "agentId");
        if (StrUtil.isBlank(agentCode)) {
            log.info("[apply][chat 触发器未绑定 Agent（agentId 为空），仅记类型 code({})]", def.getCode());
            return;
        }
        def.setTriggerKey(agentCode);
        log.info("[apply][chat 触发器绑定：workflow code({}) → 驱动 Agent code({})]", def.getCode(), agentCode);
    }

    /**
     * webhook：匿名 HTTP 入站面（增量14c / P6 接线）。triggerKey = URL 安全的 {@code path}，
     * 供 {@code AgentWebhookController} 按 {@code POST /agent/webhook/{path}} 反查「哪条已发布流程接这个 hook」
     * （{@code AgentWorkflowDefMapper.selectPublishedByWebhookKey}），命中即以发布者身份无头拉起。
     *
     * <p>🔴 落库前（{@code createDef/updateDef}）fail-close 校验——触发器是无头的，运行期没人看报错，
     * 故配置错误必须在 DSL 进库之前就拦下。注意{@code publishDef} <b>不</b>调用本提取器（它只翻状态），
     * 所以这里的措辞是「落库前」而不是「发布期」；发布期的同族校验是 DSL 校验器的 V15。
     * <ul>
     *   <li>{@code path} 必填，且必须匹配 {@link #WEBHOOK_PATH_PATTERN}（单段 URL 安全字符）——
     *       畸形 path 拼进 URL 会 404 或误匹配，正是「配了不生效无提示」缺陷。</li>
     *   <li>{@code authType} <b>必填</b>，且必须在 {@link #WEBHOOK_AUTH_TYPES}（{@code hmac}/{@code token}）内。
     *       缺省、空串、{@code none} 一律拒——不鉴权的 hook 等于把发布者身份挂在公网上（见该常量的 Javadoc）。</li>
     *   <li>{@code secret} <b>无条件必填</b>（因为 authType 已只剩签名/令牌两种）——配了签名模式却没密钥，
     *       入站时只能一律拒（等于 hook 永不生效），不如落库前就拦下并说清原因。</li>
     * </ul>
     *
     * <p>🔴 {@code secret} 只<b>校验存在</b>、<b>不投影进 trigger_key</b>（trigger_key 仅存 path）。
     * 密钥仍留在整份 DSL 里，入站时由 {@code AgentWebhookTriggerService} 解析 DSL 取出做验签。
     * 日志只记 path/authType，绝不打印 secret。
     */
    private void applyWebhook(AgentWorkflowDefDO def, Map<String, Object> config) {
        String path = str(config, CONFIG_PATH);
        if (StrUtil.isBlank(path)) {
            throw exception(WORKFLOW_TRIGGER_CONFIG_INVALID, "webhook 触发器缺少 path");
        }
        if (!WEBHOOK_PATH_PATTERN.matcher(path).matches()) {
            throw exception(WORKFLOW_TRIGGER_CONFIG_INVALID,
                    "webhook 的 path 只能是字母/数字/下划线/连字符（1~64 位、不含斜杠或点）：" + path);
        }
        String rawAuthType = str(config, "authType");
        if (StrUtil.isBlank(rawAuthType)) {
            throw exception(WORKFLOW_TRIGGER_CONFIG_INVALID,
                    "webhook 触发器必须配置鉴权方式：authType 只能是 hmac/token（不再支持不鉴权）");
        }
        String authType = rawAuthType.toLowerCase();
        if (!WEBHOOK_AUTH_TYPES.contains(authType)) {
            throw exception(WORKFLOW_TRIGGER_CONFIG_INVALID,
                    "webhook 的 authType 只能是 hmac/token（不再支持 none 不鉴权），实际为：" + authType);
        }
        // authType 已只剩签名/令牌两种，故 secret 无条件必填：缺密钥则入站验签只能一律拒（配了等于没配）
        if (StrUtil.isBlank(str(config, CONFIG_SECRET))) {
            throw exception(WORKFLOW_TRIGGER_CONFIG_INVALID,
                    "webhook 的 authType=" + authType + " 需要配置 secret（验签密钥）");
        }
        def.setTriggerKey(path);
        log.info("[apply][webhook 触发器绑定：workflow code({}) → 入站 path({}) authType({})]",
                def.getCode(), path, authType);
    }

    /**
     * 归一化触发器类型：剥掉 {@code trigger.} 前缀、转小写、去空白。
     * DSL 写全名 {@code trigger.form_event} → {@code form_event}；也兼容已写短名的情况（防御）
     */
    private String normalizeType(String rawType) {
        String t = rawType.startsWith(TYPE_PREFIX) ? rawType.substring(TYPE_PREFIX.length()) : rawType;
        return t.trim().toLowerCase();
    }

    /**
     * 从 config 取字符串值（trim），缺失/空返回 null。config 为 null 时安全返回 null
     */
    private String str(Map<String, Object> config, String key) {
        if (config == null) {
            return null;
        }
        Object v = config.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

}
