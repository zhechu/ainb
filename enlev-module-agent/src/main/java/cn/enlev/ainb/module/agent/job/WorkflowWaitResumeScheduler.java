package cn.enlev.ainb.module.agent.job;

import cn.enlev.ainb.framework.common.biz.system.tenant.TenantCommonApi;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.tenant.core.util.TenantUtils;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * wait(duration) 到期自动唤醒轮询器（增量4a）
 *
 * <h3>它解决什么</h3>
 * {@code wait} 节点靠 {@code interruptBefore} 把执行挂在 checkpoint 上（status=INTERRUPTED、resume_at=到期时刻），
 * 到点后要有人把它 resume 起来 —— hitl 是「人点确认」触发 resume，wait 则是「本轮询器扫到到期」自动触发。
 * 二者共用同一条 {@link AgentWorkflowExecutionService#resume} 恢复链，只是触发者不同。
 *
 * <h3>🔴 为什么是 DB 轮询器而非 in-JVM 定时器</h3>
 * 方案 §L287 明定 MVP 用轮询。{@code resume_at} 落库即为事实源：进程重启后本任务继续扫得到到期挂起项，
 * 不丢任务；in-JVM {@code TaskScheduler} 定时器重启即丢、且非项目惯例，故弃用。代价是精度 = {@code fixedDelay}
 * （默认 5s），实际等待 ≈ duration + ≤5s（契约已写明，可接受）。
 *
 * <h3>🔴 跨租户扫描：逐租户设上下文，绝不 executeIgnore</h3>
 * {@code agent_workflow_execution} 是 {@code TenantBaseDO}（租户过滤），定时线程上没有当前租户，
 * 直接查会漏或错。故照 {@code @TenantJob} 切面的做法：{@code TenantCommonApi.getTenantIdList()} 拿全部租户，
 * 对每个租户 {@code TenantUtils.execute(tenantId, ...)} 设好上下文再扫。
 * <b>红线</b>：agent 模块禁用 {@code TenantUtils.executeIgnore}（{@code AgentArchitectureTest} 会红），
 * 这里只用允许的 {@code execute}。
 *
 * <h3>🔴 「全部租户」≠「全部合法租户」</h3>
 * {@code getTenantIdList()} 不带任何状态/有效期过滤，过期与停用的租户一样在名单里。
 * 替它们 resume 就是替一个已经不付费的客户调模型烧钱，故扫到到期项后先过
 * {@link #isTenantUsable}（{@code TenantCommonApi.validateTenant}），失效即整户跳过。
 *
 * <h3>🔴 多实例/并发安全靠 resume() 的原子认领</h3>
 * 本任务可能多实例并跑、或与人工 resume 撞同一条执行。{@code resume()} 内用 CAS
 * （{@code UPDATE ... SET status=RUNNING WHERE id=? AND status=INTERRUPTED}）认领，影响 0 行即抛
 * {@code WORKFLOW_EXECUTION_STATE_INVALID} —— 这里吞掉它当作「已被别人恢复」，不重复处理、不冒泡。
 *
 * @author 走进新时代
 */
@Slf4j
@Component
@ConditionalOnExpression(
        "'professional'.equals('${enlev.mode:public}')"
        + " && '${agent.workflow.wait-poll-enabled:true}' != 'false'")
public class WorkflowWaitResumeScheduler {

    /**
     * 单次每租户处理的到期挂起项上限
     *
     * 分批而非一次捞完：避免某租户积压过多时一轮占用过久，下一轮 fixedDelay 会继续扫。
     */
    private static final int SCAN_LIMIT = 50;

    /**
     * 已经播报过「失效」的租户，纯粹为日志去重（见 {@link #isTenantUsable}）。
     * 不参与任何判定 —— 合法性每轮都真查一次，所以租户续费后下一轮立刻恢复处理。
     */
    private final Set<Long> reportedUnusable = ConcurrentHashMap.newKeySet();

    @Resource
    private TenantCommonApi tenantCommonApi;
    @Resource
    private AgentWorkflowExecutionMapper executionMapper;
    @Resource
    private AgentWorkflowExecutionService executionService;

    /**
     * 定时扫描所有租户的到期 wait 挂起项并自动恢复。
     *
     * <p>{@code fixedDelay}（非 fixedRate）：上一轮跑完再计时，天然避免同一实例内重叠执行。
     * 每租户各自 try/catch，一个租户出错不影响其余租户。
     */
    @Scheduled(fixedDelayString = "${agent.workflow.wait-poll-interval-ms:5000}",
            initialDelayString = "${agent.workflow.wait-poll-initial-delay-ms:10000}")
    public void pollDueWaits() {
        List<Long> tenantIds;
        try {
            tenantIds = tenantCommonApi.getTenantIdList();
        } catch (Exception ex) {
            log.warn("[pollDueWaits][获取租户列表失败，本轮跳过：{}]", ex.getMessage());
            return;
        }
        if (tenantIds == null || tenantIds.isEmpty()) {
            return;
        }
        for (Long tenantId : tenantIds) {
            try {
                TenantUtils.execute(tenantId, () -> scanAndResumeDue(tenantId));
            } catch (Exception ex) {
                log.warn("[pollDueWaits][租户({}) 扫描到期 wait 失败：{}]", tenantId, ex.getMessage());
            }
        }
    }

    /**
     * 在<b>已设好租户上下文</b>的前提下扫描本租户到期挂起项并逐个恢复。
     */
    private void scanAndResumeDue(Long tenantId) {
        List<AgentWorkflowExecutionDO> due = executionMapper.selectDueWaits(LocalDateTime.now(), SCAN_LIMIT);
        if (due == null || due.isEmpty()) {
            return;
        }
        // 租户失效就不许再替它花 token（详见 isTenantUsable）
        if (!isTenantUsable(tenantId, due.size())) {
            return;
        }
        for (AgentWorkflowExecutionDO exec : due) {
            Long executionId = exec.getId();
            // userId 为空则无法构造合法身份快照（fail-close），跳过 + 告警，绝不降级放行
            if (exec.getUserId() == null) {
                log.warn("[scanAndResumeDue][执行({}) 无 userId，无法自动恢复，跳过]", executionId);
                continue;
            }
            try {
                AgentExecutionContext ctx = AgentExecutionContext.ofSystem(exec.getUserId(), tenantId);
                // resume 返回 Flux，必须订阅才真正触发执行；后台恢复无人消费事件，忽略 onNext、只在 onError 记日志
                executionService.resume(executionId, Map.of(), ctx)
                        .subscribe(x -> { },
                                err -> log.warn("[scanAndResumeDue][wait 自动恢复执行({}) 失败：{}]",
                                        executionId, err.getMessage()));
            } catch (ServiceException se) {
                // 最常见：已被别的实例/人工 CAS 认领走（STATE_INVALID）—— 属正常竞态，info 即可
                log.info("[scanAndResumeDue][执行({}) 已被恢复或状态不符，跳过：{}]", executionId, se.getMessage());
            } catch (Exception ex) {
                log.warn("[scanAndResumeDue][执行({}) 自动恢复异常：{}]", executionId, ex.getMessage());
            }
        }
    }

    /**
     * 这个租户还能不能替它干活（存在 / 未停用 / 未过期）。
     *
     * <p><b>为什么要这一道</b>：{@code getTenantIdList()} 返回的是<b>全部</b>租户
     * （{@code TenantServiceImpl#getTenantIdList} 就一句 {@code tenantMapper.selectList()}，不带任何状态/有效期过滤）。
     * 于是一个<b>已过期</b>的客户，它挂在 {@code wait} 上的执行照样每轮被扫到、被 {@code resume()} 拉起、
     * 照样调模型 —— 钱照烧，只是烧得不该烧。开发库里就有现成的一例：租户 122（test）
     * {@code expire_time=2023-04-29}，过期两年多仍在 {@code getTenantIdList()} 的返回里。
     *
     * <p>🔴 <b>fail-close</b>：{@code validateTenant} 自身抛任何异常（含 DB 抖动）都当「失效」跳过。
     * 代价只是这条 wait 晚一轮（{@code fixedDelay} 默认 5s）恢复 —— {@code resume_at} 原样留在库里、
     * 什么都不丢；而放行的代价是给过期客户白烧钱。两边不对称，所以选严的。
     *
     * <p>🔴 <b>为什么放在「扫到到期项之后」才校验</b>，而不是放在 {@link #pollDueWaits} 的租户循环里：
     * 本轮询器 5s 一轮，而失效租户的到期行会<b>一直</b>挂着（跳过既不 resume 也不动 {@code resume_at}）。
     * 放循环里 = 每 5 秒一行日志 = 每天上万行噪声；放这里 = 只有「真有钱要花」时才出声。
     * 再叠一层 {@link #reportedUnusable} 去重，保证<b>同一次失效只播一次</b>，租户续费恢复合法后自动摘标记。
     *
     * @param pendingCount 本租户这轮扫到的到期项条数，只为把日志说清楚
     */
    private boolean isTenantUsable(Long tenantId, int pendingCount) {
        try {
            tenantCommonApi.validateTenant(tenantId);
            reportedUnusable.remove(tenantId);
            return true;
        } catch (Exception ex) {
            if (reportedUnusable.add(tenantId)) {
                log.info("[isTenantUsable][租户({}) 已失效，暂停替它自动恢复 wait（{} 条到期项原样留着）：{}]",
                        tenantId, pendingCount, ex.getMessage());
            }
            return false;
        }
    }

}
