package cn.enlev.ainb.module.agent.service.workflow;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.tenant.core.util.TenantUtils;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowExecutionStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.security.OutputModerator;
import cn.enlev.ainb.module.system.api.notify.NotifyMessageSendApi;
import cn.enlev.ainb.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;
import cn.enlev.ainb.module.system.api.tenant.TenantContactApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工作流失败告警 Service（设计方案「一之三」第 3 条 / 待办 #6 P7 治理）
 *
 * <h3>它解决什么：失败终态「可观测」</h3>
 * 工作流跑失败（{@code FAILED}）或补偿没撤干净（{@code COMPENSATION_FAILED}）时，
 * 若没人盯着「执行历史」页，就<b>没人知道</b>——尤其定时/表单事件触发的无头执行，用户根本不在场。
 * 本 Service 在 {@code settle()} 收尾漏斗里给租户联系人发一条站内信，把这些终态<b>主动推出去</b>。
 *
 * <h3>🔴 两条告警面：执行失败 vs 派发前失败（迭代七 B5-4）</h3>
 * <ul>
 *   <li>{@link #alertExecutionFailure}：执行行<b>已建</b>、进了需人工介入的终态（FAILED / COMPENSATION_FAILED）。
 *       载荷带 {@code failedAtNode}（失败所在节点，B5-2）。</li>
 *   <li>{@link #alertDispatchFailure}：触发<b>没能派发出去</b>——{@code WorkflowTriggerInvoker} 的两条派发前失败路径
 *       （owner 缺失 fail-close、{@code run()} 同步段抛错）都<b>不建执行行、不经 settle()</b>，
 *       故 {@code alertExecutionFailure(exec=null,...)} 对它们恒静默。这是本轮之前<b>唯一真正无告警的失败面</b>，
 *       由本方法补上。它复用同一张模板 {@link #TEMPLATE_CODE}，只是把 {@code executionId}/{@code status}/
 *       {@code failedAtNode} 填成「未派发」占位——因为压根没有执行行、没有节点跑过。</li>
 * </ul>
 * 🔴 两条面<b>共用</b> {@link #acquireAlertPermit} 的 30 分钟节流：一次故障（坏 owner、坏 DSL）被定时/表单事件
 * 反复触发时，不会把站内信刷爆。
 *
 * <h3>🔴 只对「需人工介入」的终态告警</h3>
 * <ul>
 *   <li>{@code FAILED}（recovery=FORWARD，不补偿）：副作用可能已部分生效且无自动撤销 → 告警</li>
 *   <li>{@code COMPENSATION_FAILED}：有写操作生效又没撤干净（{@link WorkflowExecutionStatusEnum} 里
 *       「最需要人工介入的终态」）→ 告警</li>
 *   <li>{@code COMPENSATED}：失败已被 Saga 自动回滚干净、无残留 → <b>不告警</b>（仅日志）。
 *       自愈了还轰炸管理员，只会训练他把通知关掉</li>
 *   <li>{@code CANCELLED}：用户主动中止，<b>不是失败</b>（迭代七 B3-1）→ 不进本告警面。
 *       取消既不满足 {@code settle()} 里那个 {@code equals(FAILED)}，也就到不了这里。</li>
 * </ul>
 * 是否告警的判定在调用方（{@code settle()}）——它才知道补偿后的真实终态；本 Service 只管「发」。
 *
 * <h3>去重纪律照搬 {@code AgentQuotaServiceImpl.maybeWarn}，但用内存节流而非落库标记</h3>
 * maybeWarn 把「本周期已预警」落在配额行上（跨重启）；这里没有天然的「每工作流告警状态」列，
 * 为一个附加价值功能给执行/计费表加列不划算，故用 {@code ConcurrentHashMap<workflowDefId, 上次告警时刻>}
 * 做 <b>30 分钟内存节流</b>：同一个坏掉的流程被反复触发（定时每分钟、表单事件风暴）时，
 * 30 分钟内只发一条。代价是<b>重启即清空</b>——可接受：告警是尽力而为，重启后顶多多发一条，
 * 不影响正确性，也不该为它引入 DDL。先落标记再发（mark-then-send），与 maybeWarn 同款：
 * 宁可漏一条，也不要发送慢/失败时每次收尾都轰一条。
 *
 * <h3>🔴 告警失败只打日志，绝不冒泡</h3>
 * 与 maybeWarn 同一哲学：告警是<b>附加价值</b>，不是主流程。模板被删、收件人被停用、通知服务抖动，
 * 都不该让一次工作流执行的收尾（乃至 reactor 收尾链）抛异常。故 {@link #alertExecutionFailure}
 * 与 {@link #alertDispatchFailure} 全程 try/catch。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentFailureAlertService {

    /** 站内信模板编码，须与 {@code sql/mysql} 里播种的模板一致 */
    public static final String TEMPLATE_CODE = "agent_workflow_fail";

    /** 同一工作流两次告警的最小间隔：30 分钟（内存节流，重启即清空，见类注释） */
    private static final long THROTTLE_MILLIS = 30 * 60 * 1000L;

    /** 失败原因截断长度：站内信是给人看的摘要，不灌整段堆栈 */
    private static final int REASON_MAX_LENGTH = 300;

    /** 执行失败但取不到失败节点时的占位（B5-2：failedAtNode 曾是死列，取不到也要说清「不知道在哪」） */
    private static final String UNKNOWN_NODE = "（未知节点）";

    /**
     * 派发前失败（B5-4）复用同一张模板时的三个占位值。
     * 模板 {@link #TEMPLATE_CODE} 的四个（现五个）参数都是字符串插值，故「没有执行行 / 没有节点跑过」
     * 就填这几个字面量——🔴 不另建模板：派发前失败与执行失败对收信人是同一件事「这个流程没能跑起来，去看看」，
     * 分两张模板只会让运维多记一个编码。
     */
    private static final String DISPATCH_EXECUTION_ID = "（未派发）";
    private static final String DISPATCH_STATUS = "未派发";
    private static final String DISPATCH_FAILED_AT_NODE = "（未派发，无节点执行）";

    /** workflowDefId → 上次告警时刻（epoch millis）。内存态、best-effort，见类注释 */
    private final ConcurrentHashMap<Long, Long> lastAlertAt = new ConcurrentHashMap<>();

    @Resource
    private AgentWorkflowDefMapper defMapper;
    /**
     * ⚠️ 必须 {@code @Lazy}：agent 依赖 system，直接注入会在启动时形成过早的依赖解析（同 AgentQuotaServiceImpl）
     */
    @Resource
    @Lazy
    private NotifyMessageSendApi notifyMessageSendApi;
    @Resource
    @Lazy
    private TenantContactApi tenantContactApi;

    /**
     * 对一次进入「需人工介入」终态的执行发告警。调用方（{@code settle()}）已判定该不该发，本方法只管发 + 节流。
     *
     * @param exec        执行行（取 workflowDefId / version / tenantId / id / <b>failedAtNode</b>）
     * @param context     身份快照：告警内的 def 名称查询与站内信发送都需 ambient 租户，故包在 {@code context.execute} 里
     * @param finalStatus 终态（{@code FAILED} / {@code COMPENSATION_FAILED}），用于文案
     * @param errorMsg    失败原因（可空）
     */
    public void alertExecutionFailure(AgentWorkflowExecutionDO exec, AgentExecutionContext context,
                                      String finalStatus, String errorMsg) {
        if (exec == null) {
            return;
        }
        Long defId = exec.getWorkflowDefId();
        // 节流：先原子 check-and-mark（mark-then-send）。defId 为 null 是异常态（执行必绑定义），不节流、照常告警
        if (defId != null && !acquireAlertPermit(defId)) {
            log.info("[alertExecutionFailure][工作流({}) 失败告警在 {} 分钟节流窗口内，跳过（executionId={} status={}）]",
                    defId, THROTTLE_MILLIS / 60000, exec.getId(), finalStatus);
            return;
        }
        try {
            context.execute(() -> {
                doAlert(exec, finalStatus, errorMsg);
                return null;
            });
        } catch (Exception ex) {
            // 告警是附加价值：发送链路任何异常都只记日志，绝不抛回 reactor 收尾链（同 maybeWarn）
            log.warn("[alertExecutionFailure][执行({}) 失败告警发送异常：{}]", exec.getId(), ex.getMessage());
        }
    }

    /**
     * 对一次「<b>没能派发出去</b>」的触发失败发告警（迭代七 B5-4）。
     *
     * <h3>🔴 为什么必须是新方法，而不是复用 {@link #alertExecutionFailure}</h3>
     * {@code WorkflowTriggerInvoker} 的两条派发前失败路径——① owner 缺失 fail-close、② {@code run()} 同步段抛错——
     * 都<b>不建执行行</b>，{@code exec} 恒为 {@code null}；而 {@code alertExecutionFailure} 见 {@code exec==null}
     * 直接 {@code return}（那条行为被 {@code AgentFailureAlertServiceTest#alert_nullExec_noop} 钉死，不许改）。
     * 于是派发前失败今天<b>完全没有告警</b>。本方法绕开那个 null 闸：它只需要一份 {@code def}（有 tenantId 能找收件人、
     * 有 id 能节流），不需要执行行。
     *
     * <h3>身份：只用租户，不伪造登录用户</h3>
     * 收件人是<b>租户联系人</b>（按 {@code def.tenantId} 查），站内信发送本身不需要某个登录用户在场
     * （同 {@code AgentQuotaServiceImpl.maybeWarn}  reliance on ambient 租户）。owner 可能正是缺失的那个（路径①），
     * 故这里刻意<b>不</b>用 {@code AgentExecutionContext.ofSystem(owner,...)} 去贴一个 id 为 null 的 LoginUser，
     * 而是用 {@code TenantUtils.execute(def.tenantId, ...)} 只把 ambient 租户设对。
     * 🔴 agent 模块禁用 {@code executeIgnore}（会关掉整个租户隔离，{@code AgentArchitectureTest} 会红），这里只用 {@code execute}。
     *
     * @param def    匹配到但没能派发的定义（取 id 节流 / tenantId 找收件人 / name·code·version 做文案）
     * @param reason 失败原因，🔴 <b>原样透传</b>（红线第 7 条：不替 error alert 编病因）。
     *               路径②传 {@code ex.getMessage()} 原文；路径①传那句 fail-close 的事实描述。
     */
    public void alertDispatchFailure(AgentWorkflowDefDO def, String reason) {
        if (def == null) {
            return;
        }
        Long defId = def.getId();
        // 🔴 复用同一把节流锁：派发前失败与执行失败共享 workflowDefId 维度的 30 分钟窗口，
        //    坏 owner / 坏 DSL 被定时每分钟触发时不会刷爆站内信。defId 为 null 是异常态，不节流、照常告警。
        if (defId != null && !acquireAlertPermit(defId)) {
            log.info("[alertDispatchFailure][工作流({}) 派发前失败告警在 {} 分钟节流窗口内，跳过]",
                    defId, THROTTLE_MILLIS / 60000);
            return;
        }
        try {
            // 只贴租户，不伪造登录用户（理由见方法注释）；executeIgnore 是 agent 红线，绝不使用
            TenantUtils.execute(def.getTenantId(), () -> doDispatchAlert(def, reason));
        } catch (Exception ex) {
            // 告警是附加价值：发送链路任何异常都只记日志，绝不抛回触发器（否则会污染监听器/轮询器循环）
            log.warn("[alertDispatchFailure][定义({}) 派发前失败告警发送异常：{}]", defId, ex.getMessage());
        }
    }

    /**
     * 原子领取「本次可发告警」的许可：距上次告警不足 {@link #THROTTLE_MILLIS} 则拒绝。
     *
     * <p>用 {@code compute} 保证 check-and-set 原子——同一 workflowDefId 的多次执行可能在不同线程并发收尾，
     * 只有第一个把时刻推进的调用拿到许可，其余被节流。
     */
    private boolean acquireAlertPermit(Long defId) {
        long now = System.currentTimeMillis();
        AtomicBoolean acquired = new AtomicBoolean(false);
        lastAlertAt.compute(defId, (key, prev) -> {
            if (prev == null || now - prev >= THROTTLE_MILLIS) {
                acquired.set(true);
                return now;
            }
            return prev;
        });
        return acquired.get();
    }

    /** 真正组装并发送（已在 {@code context.execute} 贴好租户身份的线程上） */
    private void doAlert(AgentWorkflowExecutionDO exec, String finalStatus, String errorMsg) {
        Long tenantId = exec.getTenantId();
        // 收件人=租户联系人（平台没有专门的「租户管理员」概念，联系人最接近，同 AgentQuotaServiceImpl.resolveTenantAdmin）
        Long recipient = tenantId == null ? null : tenantContactApi.getContactUserId(tenantId);
        if (recipient == null) {
            log.warn("[doAlert][执行({}) 失败但租户({}) 无联系人，跳过告警]", exec.getId(), tenantId);
            return;
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workflow", describeWorkflow(exec));
        params.put("executionId", String.valueOf(exec.getId()));
        params.put("status", describeStatus(finalStatus));
        // 🔴 B5-2：failedAtNode 从「一根死列」变成真的被读、被显示。它由 settle() 在落 FAILED 时写进 exec
        //    （见 AgentWorkflowExecutionServiceImpl#settle），这里读出来当<b>文本</b>放进载荷。
        //    ⚠️ 裁定 A-Ⅳ（N16）：站内信详情页/列表页都是纯文本插值，渲染不了链接，故只能带文本、不做深链。
        params.put("failedAtNode", StrUtil.blankToDefault(exec.getFailedAtNode(), UNKNOWN_NODE));
        params.put("reason", StrUtil.maxLength(StrUtil.blankToDefault(errorMsg, "（无错误信息）"), REASON_MAX_LENGTH));

        sendModerated(recipient, params, "执行(" + exec.getId() + ")");
        log.info("[doAlert][执行({}) 失败告警已发 → 租户({}) 联系人({})，终态({})，失败节点({})]",
                exec.getId(), tenantId, recipient, finalStatus, params.get("failedAtNode"));
    }

    /** 派发前失败（B5-4）的组装并发送：三个「未派发」占位 + 原样透传的 reason */
    private void doDispatchAlert(AgentWorkflowDefDO def, String reason) {
        Long tenantId = def.getTenantId();
        Long recipient = tenantId == null ? null : tenantContactApi.getContactUserId(tenantId);
        if (recipient == null) {
            log.warn("[doDispatchAlert][定义({}) 派发前失败但租户({}) 无联系人，跳过告警]", def.getId(), tenantId);
            return;
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workflow", describeWorkflowDef(def) + describeVersion(def.getVersion()));
        params.put("executionId", DISPATCH_EXECUTION_ID);
        params.put("status", DISPATCH_STATUS);
        params.put("failedAtNode", DISPATCH_FAILED_AT_NODE);
        params.put("reason", StrUtil.maxLength(StrUtil.blankToDefault(reason, "（无错误信息）"), REASON_MAX_LENGTH));

        sendModerated(recipient, params, "定义(" + def.getId() + ")");
        log.info("[doDispatchAlert][定义({}) 派发前失败告警已发 → 租户({}) 联系人({})]",
                def.getId(), tenantId, recipient);
    }

    /**
     * 出站前对整份 params 过一遍输出审核，再交给站内信。两条告警面共用这一处收口。
     *
     * <p>🔴 【B2-2 · 口径 B 第 6 项】两个键的文字都不是平台自己写的：workflow 取 def.getName()（AI 起草的流程名），
     *    reason 取 errorMsg（可能原样含 tool_result / LLM 输出）。收信人是租户联系人 ——
     *    他收到告警之后要决定「这个流程还放不放行」，正是 G26 说的那个决策者。
     *    一次审全部键，比逐键各调一次 moderateString 少一处漏键的机会；
     *    moderateFormVars <b>不改键名</b>（只报路径），故 ${workflow} 这类模板占位符照常替换。
     * <p>⚠️ 审核命中时会给值加 9 个字符的前缀，而这一份 params 是要序列化进
     *    system_notify_message.template_params 的 —— 那个列<b>只有 varchar(255)</b>，
     *    而 reason 单独就已经给到 303 字（StrUtil.maxLength 返回 n+3）。
     *    🔴 这个溢出是<b>本轮之前就存在</b>的缺陷，不是审核引入的，实测数据与处置见总账 N34。
     */
    private void sendModerated(Long recipient, Map<String, Object> params, String subject) {
        OutputModerator.Moderation moderated = OutputModerator.moderateFormVars(params);
        if (!moderated.flaggedKeys().isEmpty()) {
            // 🔴 只记路径不记内容：告警文字本身就是不可信产物，整条打进日志等于再造一个投递面（N32 的形状）。
            log.warn("[sendModerated][{} 失败告警的站内信参数经输出审核被标记/截断 paths({})]",
                    subject, moderated.flaggedKeys());
        }

        NotifySendSingleToUserReqDTO reqDTO = new NotifySendSingleToUserReqDTO();
        reqDTO.setUserId(recipient);
        reqDTO.setTemplateCode(TEMPLATE_CODE);
        reqDTO.setTemplateParams(moderated.variables());
        notifyMessageSendApi.sendSingleMessageToAdmin(reqDTO);
    }

    /**
     * 工作流的可读标识：名称（退化到 code、再退化到 id）+ 版本。best-effort，取不到不影响告警本身。
     */
    private String describeWorkflow(AgentWorkflowExecutionDO exec) {
        Long defId = exec.getWorkflowDefId();
        String version = describeVersion(exec.getWorkflowVersion());
        if (defId == null) {
            return "（未知工作流）";
        }
        try {
            AgentWorkflowDefDO def = defMapper.selectById(defId);
            if (def != null) {
                return describeWorkflowDef(def) + version;
            }
        } catch (Exception ex) {
            log.debug("[describeWorkflow][取工作流({}) 名称失败，退化用 id：{}]", defId, ex.getMessage());
        }
        return "定义#" + defId + version;
    }

    /** 版本后缀（{@code null} → 空串）。两条告警面共用，保证「（v3）」这一截口径一致 */
    private String describeVersion(Integer version) {
        return version != null ? "（v" + version + "）" : "";
    }

    /**
     * 直接由 def 组装可读标识（派发前失败手里就有 def，无需再查库）。名称退化到 code、再退化到 id。
     * 🔴 不含版本后缀——版本由调用方用 {@link #describeVersion} 按需拼：
     * {@link #describeWorkflow} 用执行行快照的版本，{@link #doDispatchAlert} 用 def 当前版本。
     */
    private String describeWorkflowDef(AgentWorkflowDefDO def) {
        String label = StrUtil.blankToDefault(def.getName(), def.getCode());
        return StrUtil.blankToDefault(label, "定义#" + def.getId());
    }

    /** 终态字符串 → 中文描述（取不到枚举则原样返回） */
    private String describeStatus(String status) {
        for (WorkflowExecutionStatusEnum e : WorkflowExecutionStatusEnum.values()) {
            if (e.getStatus().equals(status)) {
                return e.getDescription();
            }
        }
        return StrUtil.blankToDefault(status, "失败");
    }

}
