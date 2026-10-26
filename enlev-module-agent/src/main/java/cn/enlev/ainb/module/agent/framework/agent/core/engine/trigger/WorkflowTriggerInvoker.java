package cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger;

import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.TriggerDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.UntrustedInputTracer.SourceKind;
import cn.enlev.ainb.module.agent.framework.agent.core.security.UntrustedInputSanitizer;
import cn.enlev.ainb.module.agent.service.workflow.AgentFailureAlertService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 触发器无头启动器（增量6）：把「一次触发」翻译成「以发布者身份启动一次 Workflow 执行」
 *
 * <h3>它解决什么</h3>
 * form_event 监听器与 schedule 轮询器匹配到该触发的定义后，都需要同一件事：<b>在没有 HTTP 请求的后台</b>
 * 拉起 {@code run(code, inputs, ctx)}。这段逻辑（合成身份、路由 inputVar、fire-and-forget 订阅）两个触发源
 * 逐字相同，收进本类共用——正是 DSL 规范 §4.1 点名的 {@code WorkflowInvoker} 抽象的落地。
 *
 * <h3>🔴 身份模型：以定义发布者身份运行，owner 缺失即 fail-close</h3>
 * 触发执行用 {@link AgentExecutionContext#ofSystem}(owner, tenant) 合成身份：只塞 userId，<b>不塞权限集</b>。
 * enlev 的 {@code hasPermission} 会按 userId 从库实时派生角色/权限——所以「以发布者身份跑」= 网关按 owner_user_id
 * 实时校验其权限：admin 发布者→全权限，受限发布者→写工具被拒。语义即「谁发布的自动化，以谁的权限跑，责任归属清晰」。
 * <p>{@code owner_user_id} 为 null 时<b>绝不降级为匿名/系统身份放行</b>，而是 log.warn + 返回 false 跳过
 * （与 {@code WorkflowWaitResumeScheduler}、{@code publishEvent} 的「行身份 fail-close」先例逐字一致）。
 *
 * <h3>🔴 调用方须保证 ambient 租户 == def.tenantId</h3>
 * {@code run()} 的执行行 insert 与 def 查询都依赖<b>当前线程的 ambient 租户</b>（TenantContextHolder），
 * 而非 ctx.tenantId（ctx 只在图执行的 boundedElastic 线程上经 {@code execute()} 贴回）。故：
 * 监听器跑在请求线程（ambient=请求租户，def 由租户过滤查出，二者相等）；轮询器跑在
 * {@code TenantUtils.execute(tenantId,...)} 内（ambient=tenantId=def.tenant）。两条路径都满足，缺一不可。
 *
 * <h3>fire-and-forget</h3>
 * {@code run()} 返回 lazy Flux，{@code subscribe} 后图在 boundedElastic 异步跑；本方法<b>不等图跑完</b>即返回 true
 * （= 已成功派发）。要确认执行结果需另查 {@code agent_workflow_execution}。
 * <p>🔴 **图内失败不在本类留痕**，别在这里找：{@code buildEventFlux} 的末端算子是 {@code onErrorResume}，
 * 它把任何 error 转成 {@code WorkflowEvent.error(...)} 的正常 onNext ⟹ 这个 Flux **永不以 error 终止**，
 * 所以 {@code subscribe} 处**刻意不挂 error consumer**（挂了也一次都不会执行）。
 * 真正的留痕是 {@code onErrorResume} **之前**那道 {@code .doOnError(... settle(FAILED) ...)}，
 * 它把失败写进 {@code agent_workflow_execution}。
 * <p>「绝不外抛污染监听器/轮询器的循环」这条保证由**方法体的 try/catch** 兜（{@code run()} 的同步段
 * 会抛：requireIdentity 不满足、def 非发布态、DSL 解析失败等），不是由 error consumer 兜。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class WorkflowTriggerInvoker {

    @Resource
    private AgentWorkflowExecutionService executionService;
    @Resource
    private DslParser dslParser;
    @Resource
    private UntrustedInputSanitizer inputSanitizer;
    /**
     * 🔴 B5-4：派发前失败告警。本类的两条 {@code return false} 路径（owner 缺失 fail-close、{@code run()} 同步段抛错）
     * 都<b>不建执行行、不经 settle()</b>，故 {@code alertExecutionFailure(exec=null,...)} 对它们恒静默 ——
     * 这是本轮之前唯一真正无告警的失败面。改用 {@link AgentFailureAlertService#alertDispatchFailure} 补上。
     */
    @Resource
    private AgentFailureAlertService failureAlertService;

    /**
     * 无头启动一次触发执行
     *
     * @param def     匹配到的已发布定义（须带 ownerUserId / tenantId / code / dsl / <b>triggerType</b>）
     * @param payload 触发载荷，<b>经 {@link UntrustedInputSanitizer} 处置后</b>写进 {@code state[inputVar]}
     *                （form_event：{formCode,eventType,dataId}；schedule：{triggerType,scheduledFireTime,cron}）。
     *                值须 JSON 可序列化（全 String/Long）。
     *                🔴 入参树不会被改写（sanitizer 返回新建的 map），调用方拿到的还是自己那份
     * @return true=已成功派发（run 已 subscribe）；false=未派发（owner 缺失 fail-close，或启动同步抛错）
     */
    public boolean invokeHeadless(AgentWorkflowDefDO def, Map<String, Object> payload) {
        Long owner = def.getOwnerUserId();
        // 🔴 fail-close：无发布者身份绝不降级放行。触发是自动化的「反射弧」，权力来自发布者的权限，
        //   没有明确的责任人就不该跑——宁可跳过 + 告警，也不以匿名/系统身份偷偷执行
        if (owner == null) {
            log.warn("[invokeHeadless][定义({}) 无 owner_user_id，无法合成触发身份，fail-close 跳过]", def.getCode());
            // 🔴 B5-4：这条路径不建执行行，settle 那条告警链够不着它，故在此显式补一发派发前失败告警。
            //    reason 用与日志同一句<b>事实</b>描述（红线第 7 条：不替 error alert 编病因）。
            failureAlertService.alertDispatchFailure(def,
                    "无 owner_user_id，无法合成触发身份，fail-close 跳过");
            return false;
        }
        try {
            String inputVar = resolveInputVar(def);
            // 🔴 进 state 之前过 sanitizer（B2-4）。这一处是 webhook / form_event / schedule
            //    三个触发源【共同的漏斗】（AgentWebhookTriggerServiceImpl#handleWebhook、
            //    FormDataChangedTriggerListener#onFormChanged、WorkflowScheduleTriggerScheduler#fireOne
            //    都走这里），故接一处即三源全盖住。
            // 🔴 污点源类型从【已落库的 def.triggerType】派生，而不是让调用方传进来：
            //    ① 那三个调用方各自的触发类型本来就写在 def 上，是权威数据，不该由调用方再声明一遍；
            //    ② 改签名会波及 4 个测试类里 ~40 处调用，代价与收益不成比例；
            //    ③ 更重要的是——派生意味着【将来新增第四个调用方时不可能忘记声明源】，
            //       而「调用方传参」那种写法忘了就静默降级成不处置。
            Map<String, Object> inputs = Map.of(inputVar,
                    inputSanitizer.sanitizePayload(resolveSourceKind(def.getTriggerType()), payload));
            // run() 内会 new HashMap<>(input)，故这里用不可变 Map.of 无妨；键即 DSL trigger.inputVar（默认 "input"）。
            // sanitizePayload 同样返回新建的 map，故这条链上没有任何一层会写回调用方的 payload。
            AgentExecutionContext ctx = AgentExecutionContext.ofSystem(owner, def.getTenantId());
            // run() 的第一段（校验 + 建执行行）同步执行在【当前线程】（依赖 ambient 租户，见类注释）；
            // 返回的 lazy Flux 经 subscribe 才把图甩到 boundedElastic 异步跑。
            // 🔴 这里**刻意不给 error consumer**：buildEventFlux 的末端算子是 onErrorResume，
            //    它把任何 error 转成 WorkflowEvent.error(...) 的正常 onNext ⟹ 这个 Flux 永不以 error 终止。
            //    失败真正的留痕在它**之前**的 .doOnError(... settle(FAILED) ...)，写进 agent_workflow_execution。
            //    原先这里挂着一个 err -> log.warn，一次都没执行过，且会让读者误以为「失败是在这儿记的」。
            executionService.run(def.getCode(), inputs, ctx)
                    .subscribe(x -> { });
            log.info("[invokeHeadless][已无头派发触发 code({}) owner({}) tenant({}) inputVar({})]",
                    def.getCode(), owner, def.getTenantId(), inputVar);
            return true;
        } catch (Exception ex) {
            // run() 同步段可能抛（requireIdentity 不满足、def 非发布态、DSL 解析失败等）。
            // 吞下记日志、返回 false：触发失败绝不外泄到监听器/轮询器（后者会继续处理其余 def）
            log.warn("[invokeHeadless][定义({}) 无头触发启动失败：{}]", def.getCode(), ex.getMessage(), ex);
            // 🔴 B5-4：这条路径同样不建执行行，故在此补派发前失败告警。
            //    reason 用 ex.getMessage() <b>原文</b>（红线第 7 条：不替 error alert 编病因）；
            //    为 null 时 doDispatchAlert 内部 blankToDefault 兜成「（无错误信息）」。
            failureAlertService.alertDispatchFailure(def, ex.getMessage());
            return false;
        }
    }

    /**
     * 解析该定义 DSL 的入口变量名（{@code trigger.inputVar()}，默认 "input"）。
     * 触发载荷整体写进这个变量，故 DSL 必须在 {@code variables[]} 声明它
     */
    private String resolveInputVar(AgentWorkflowDefDO def) {
        WorkflowDefDTO dto = dslParser.parse(def.getDsl());
        TriggerDTO trigger = dto.getTrigger();
        return trigger == null ? "input" : trigger.inputVar();
    }

    /**
     * {@code def.trigger_type} → 污点源档位（{@link SourceKind}）
     *
     * <h3>映射表</h3>
     * <table border="1">
     *   <tr><th>trigger_type</th><th>SourceKind</th><th>载荷里到底有什么</th></tr>
     *   <tr><td>{@code webhook}</td><td>{@code TRIGGER_WEBHOOK}</td>
     *       <td>调用方 POST 的原始 body（{@code buildPayload} 解析后的整棵树）——<b>完全外部可控</b></td></tr>
     *   <tr><td>{@code form_event}</td><td>{@code TRIGGER_FORM_EVENT}</td>
     *       <td>{formCode, eventType, dataId}——标识符，但源头是一次用户表单写入</td></tr>
     *   <tr><td>{@code chat}</td><td>{@code TRIGGER_CHAT}</td>
     *       <td>用户在聊天里打的话（当前无生产调用方走本方法，映射先备着）</td></tr>
     *   <tr><td>{@code schedule} / 其它 / null</td><td>{@code TRIGGER_FORM_EVENT}</td>
     *       <td>见下</td></tr>
     * </table>
     *
     * <h3>🔴 为什么 schedule 也过 sanitizer，而它明明不在 5 个污点源里</h3>
     * schedule 的载荷是<b>平台自合成</b>的（{@code WorkflowScheduleTriggerScheduler#fireOne} 里那段：
     * {@code triggerType} 字面量 + {@code LocalDateTime.toString()} + def 自己的 cron），
     * 一个外部可控的字节都没有，所以 {@code SourceKind} 里<b>刻意没有</b> SCHEDULE 这一值
     * （方案 §4 第 5 条已结案：「5 = 污点源种类数」，不许为了这里凑出第 6 个）。
     *
     * <p>但它照样过一遍，借 {@code TRIGGER_FORM_EVENT} 那一档只为拿一个保守上限
     * （{@code enlev.agent.sanitize.form-event-value-max-length}，比 cron + 时间戳的实际长度高出两个数量级，
     * 永远撞不到）。这样做换来一个<b>有用的性质</b>：
     * 万一将来有人往 schedule 载荷里塞了外部数据、或平台自己生成了控制字符，
     * sanitizer 那条 warn 就会响 —— 它在这里的作用不是「处置」，是<b>金丝雀</b>。
     * 反之若为 schedule 单开一条「跳过处置」的分支，那种改动就是静默的，谁也不会发现。
     *
     * <p>未知类型 / null 落进同一档，是 fail-close：宁可多处置一遍，也不留一条「不处置」的路径。
     * （实际上到不了这里——{@code WorkflowTriggerExtractor#apply} 在发布期就把 type 校验过了。）
     */
    private SourceKind resolveSourceKind(String triggerType) {
        if (triggerType == null) {
            return SourceKind.TRIGGER_FORM_EVENT;
        }
        return switch (triggerType) {
            case "webhook" -> SourceKind.TRIGGER_WEBHOOK;
            case "chat" -> SourceKind.TRIGGER_CHAT;
            // form_event、schedule、以及任何未知类型：都落这一档，理由见上
            default -> SourceKind.TRIGGER_FORM_EVENT;
        };
    }

}
