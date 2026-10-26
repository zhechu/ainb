package cn.enlev.ainb.module.agent.job;

import cn.enlev.ainb.framework.common.biz.system.tenant.TenantCommonApi;
import cn.enlev.ainb.framework.tenant.core.util.TenantUtils;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerInvoker;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * schedule 触发器轮询器（增量6）：扫「到点该触发」的定时 Workflow 定义并无头拉起
 *
 * <h3>它解决什么</h3>
 * schedule 触发器的定义在 {@code agent_workflow_def} 上带 {@code trigger_next_fire_at}（cron 预先算好的下次触发时刻）。
 * 本轮询器每 {@code fixedDelay} 扫一次到期行（{@code trigger_next_fire_at<=now}），到点即以发布者身份无头启动执行。
 * 与 {@code WorkflowWaitResumeScheduler}（wait 到期自动唤醒）是同一范式的孪生：都是「DB 为事实源的定时扫描」。
 *
 * <h3>🔴 为什么是 DB 轮询器而非 Quartz 动态注册 / in-JVM 定时器</h3>
 * ① 不引 Quartz 动态注册：那要求发布/停用 def 时同步增删 {@code infra_job} 行，跨模块耦合重、易漂移。
 * ② 不用 in-JVM 定时器：进程重启即丢任务。DB 里的 {@code trigger_next_fire_at} 是事实源，重启后继续扫得到，不丢。
 * 代价是精度 = {@code fixedDelay}（默认 5s），实际触发 ≈ 到点 + ≤5s（文档写明，可接受）。
 *
 * <h3>🔴 跨租户扫描：逐租户设上下文，绝不 executeIgnore</h3>
 * {@code agent_workflow_def} 是 {@code TenantBaseDO}。定时线程上没有当前租户，故照 {@code WorkflowWaitResumeScheduler}：
 * {@code TenantCommonApi.getTenantIdList()} 拿全部租户，对每个 {@code TenantUtils.execute(tenantId, ...)} 设好上下文再扫。
 * <b>红线</b>：agent 模块禁用 {@code TenantUtils.executeIgnore}（{@code AgentArchitectureTest} 会红），这里只用 {@code execute}。
 * 这也满足 {@link WorkflowTriggerInvoker} 要求的「ambient 租户 == def.tenant」。
 *
 * <h3>🔴 「全部租户」≠「全部合法租户」</h3>
 * {@code getTenantIdList()} 不带任何状态/有效期过滤，过期与停用的租户一样在名单里。
 * 而本轮询器比它的孪生 {@code WorkflowWaitResumeScheduler} 更烧钱：它不是恢复一次挂起的执行，
 * 是<b>按 cron 反复无头拉起全新的执行</b> —— 一个过期客户挂了个每日定时，就会每天白烧一次，永远烧下去。
 * 故扫到到期定义后先过 {@link #isTenantUsable}，失效即整户跳过。
 *
 * <h3>🔴 多实例/重复扫描安全：先 CAS 推进 next_fire，成功后才触发（at-most-once）</h3>
 * 本轮询器可能多实例并跑、或同一实例上一轮还没跑完又扫到同一行。对每条到期 def，<b>先</b>
 * {@code casAdvanceSchedule(id, 旧next_fire, cron.next(now))} 把下次触发时间推进到未来——
 * {@code WHERE trigger_next_fire_at=旧值} 保证只有一个调用方能推进成功（影响 1 行），其余影响 0 行即
 * 「已被别的实例认领」→ 跳过、不触发。<b>CAS 成功后才 invokeHeadless</b>，杜绝同一到点被双跑。
 * 取舍：若 CAS 成功但随后触发失败/进程崩溃，这一次触发会丢（at-most-once，非 at-least-once）——
 * 对定时器而言「漏一次」优于「重复触发副作用」，是可接受的正确方向。
 *
 * @author 走进新时代
 */
@Slf4j
@Component
@ConditionalOnExpression(
        "'professional'.equals('${enlev.mode:public}')"
        + " && '${agent.workflow.schedule-trigger-enabled:true}' != 'false'")
public class WorkflowScheduleTriggerScheduler {

    /**
     * 单次每租户处理的到期定义上限。分批而非一次捞完：避免某租户积压过多时一轮占用过久，下一轮 fixedDelay 继续扫
     */
    private static final int SCAN_LIMIT = 50;

    /**
     * 已经播报过「失效」的租户，纯粹为日志去重（见 {@link #isTenantUsable}）。
     * 不参与任何判定 —— 合法性每轮都真查一次，所以租户续费后下一轮立刻恢复触发。
     */
    private final Set<Long> reportedUnusable = ConcurrentHashMap.newKeySet();

    @Resource
    private TenantCommonApi tenantCommonApi;
    @Resource
    private AgentWorkflowDefMapper defMapper;
    @Resource
    private WorkflowTriggerInvoker triggerInvoker;

    /**
     * 定时扫描所有租户的到期 schedule 定义并触发。
     *
     * <p>{@code fixedDelay}（非 fixedRate）：上一轮跑完再计时，天然避免同一实例内重叠执行。
     * 每租户各自 try/catch，一个租户出错不影响其余租户。
     */
    @Scheduled(fixedDelayString = "${agent.workflow.schedule-trigger-interval-ms:5000}",
            initialDelayString = "${agent.workflow.schedule-trigger-initial-delay-ms:10000}")
    public void pollDueSchedules() {
        List<Long> tenantIds;
        try {
            tenantIds = tenantCommonApi.getTenantIdList();
        } catch (Exception ex) {
            log.warn("[pollDueSchedules][获取租户列表失败，本轮跳过：{}]", ex.getMessage());
            return;
        }
        if (tenantIds == null || tenantIds.isEmpty()) {
            return;
        }
        for (Long tenantId : tenantIds) {
            try {
                TenantUtils.execute(tenantId, () -> scanAndFireDue(tenantId));
            } catch (Exception ex) {
                log.warn("[pollDueSchedules][租户({}) 扫描到期 schedule 失败：{}]", tenantId, ex.getMessage());
            }
        }
    }

    /**
     * 在<b>已设好租户上下文</b>的前提下扫描本租户到期的 schedule 定义，逐条「先 CAS 认领、后触发」
     */
    private void scanAndFireDue(Long tenantId) {
        LocalDateTime now = LocalDateTime.now();
        List<AgentWorkflowDefDO> due = defMapper.selectDueSchedules(now, SCAN_LIMIT);
        if (due == null || due.isEmpty()) {
            return;
        }
        // 租户失效就不许再替它花 token（详见 isTenantUsable）
        if (!isTenantUsable(tenantId, due.size())) {
            return;
        }
        for (AgentWorkflowDefDO def : due) {
            try {
                fireOne(def, now);
            } catch (Exception ex) {
                // 单条 def 出错（cron 损坏、触发异常等）不波及同租户其余到期 def
                log.warn("[scanAndFireDue][定义({}) 定时触发处理异常，跳过：{}]", def.getCode(), ex.getMessage());
            }
        }
    }

    /**
     * 触发单条到期定义：算下次触发时间 → CAS 认领 → 认领成功才无头启动
     */
    private void fireOne(AgentWorkflowDefDO def, LocalDateTime now) {
        // schedule 的 cron 存在 trigger_key（见 WorkflowTriggerExtractor.applySchedule）
        String cron = def.getTriggerKey();
        LocalDateTime oldNextFire = def.getTriggerNextFireAt();
        if (cron == null || oldNextFire == null) {
            log.warn("[fireOne][定义({}) 的 schedule 触发列不完整(cron={}, nextFire={})，跳过]",
                    def.getCode(), cron, oldNextFire);
            return;
        }
        LocalDateTime newNextFire;
        try {
            newNextFire = CronExpression.parse(cron).next(now);
        } catch (IllegalArgumentException ex) {
            // cron 损坏（正常发布路径已被 extractor 校验拦下；此处防 SQL 直插的脏数据）：
            // 无法算下次时间就不能安全推进，跳过——否则会每轮都触发它（next_fire 永远<=now）
            log.warn("[fireOne][定义({}) 的 cron({}) 不合法，无法推进下次触发时间，跳过：{}]",
                    def.getCode(), cron, ex.getMessage());
            return;
        }
        if (newNextFire == null) {
            log.warn("[fireOne][定义({}) 的 cron({}) 算不出下次触发时间，跳过]", def.getCode(), cron);
            return;
        }
        // 🔴 先认领后触发：CAS 把 next_fire 从旧值推进到未来。影响 0 行=已被别的实例/上一轮推进→放弃本次触发
        if (!defMapper.casAdvanceSchedule(def.getId(), oldNextFire, newNextFire)) {
            log.info("[fireOne][定义({}) 的本次到点已被其他实例认领，跳过]", def.getCode());
            return;
        }
        // 认领成功，构造载荷无头启动。scheduledFireTime=本次到点的原定时刻（旧 next_fire），全 String，JSON 安全
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("triggerType", "schedule");
        payload.put("scheduledFireTime", oldNextFire.toString());
        payload.put("cron", cron);
        boolean dispatched = triggerInvoker.invokeHeadless(def, payload);
        log.info("[fireOne][定义({}) 定时触发，派发={}，下次触发时间推进到 {}]",
                def.getCode(), dispatched, newNextFire);
    }

    /**
     * 这个租户还能不能替它干活（存在 / 未停用 / 未过期）。
     *
     * <p><b>为什么要这一道</b>：{@code getTenantIdList()} 返回的是<b>全部</b>租户
     * （{@code TenantServiceImpl#getTenantIdList} 就一句 {@code tenantMapper.selectList()}，不带任何状态/有效期过滤）。
     * 过期客户挂了 schedule 定义，就会被本轮询器按 cron <b>反复</b>无头拉起、反复调模型 ——
     * 钱照烧，只是烧得不该烧。开发库里就有现成的过期租户：122（test），{@code expire_time=2023-04-29}。
     *
     * <p>🔴 <b>fail-close</b>：{@code validateTenant} 自身抛任何异常（含 DB 抖动）都当「失效」跳过。
     * 代价只是这条定义晚一轮（{@code fixedDelay} 默认 5s）触发；而放行的代价是给过期客户白烧钱。
     * 两边不对称，所以选严的。
     *
     * <p>🔴 <b>跳过时不动 {@code trigger_next_fire_at}</b>，这是有意的：它保持「到期」状态，
     * 租户一旦恢复合法，下一轮 {@link #fireOne} 会按 {@code cron.next(now)} 补触发<b>一次</b>
     * 再把时间推进到未来 —— 是「补一次」而不是「把欠的 N 次全补上」，与定时器的 at-most-once 取舍一致。
     *
     * <p>🔴 <b>为什么放在「扫到到期定义之后」才校验</b>，而不是放在 {@link #pollDueSchedules} 的租户循环里：
     * 本轮询器 5s 一轮，而失效租户的到期行会<b>一直</b>挂着。放循环里 = 每 5 秒一行日志 = 每天上万行噪声；
     * 放这里 = 只有「真有钱要花」时才出声。再叠一层 {@link #reportedUnusable} 去重，
     * 保证<b>同一次失效只播一次</b>，租户续费恢复合法后自动摘标记。
     *
     * @param pendingCount 本租户这轮扫到的到期定义条数，只为把日志说清楚
     */
    private boolean isTenantUsable(Long tenantId, int pendingCount) {
        try {
            tenantCommonApi.validateTenant(tenantId);
            reportedUnusable.remove(tenantId);
            return true;
        } catch (Exception ex) {
            if (reportedUnusable.add(tenantId)) {
                log.info("[isTenantUsable][租户({}) 已失效，暂停替它定时触发（{} 条到期定义原样留着，恢复合法后补触发一次）：{}]",
                        tenantId, pendingCount, ex.getMessage());
            }
            return false;
        }
    }

}
