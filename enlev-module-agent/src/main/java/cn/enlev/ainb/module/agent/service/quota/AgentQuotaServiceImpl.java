package cn.enlev.ainb.module.agent.service.quota;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.agent.dal.dataobject.quota.AgentQuotaDO;
import cn.enlev.ainb.module.agent.dal.mysql.quota.AgentQuotaMapper;
import cn.enlev.ainb.module.agent.dal.mysql.usage.AgentUsageLogMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentNodeExecutionMapper;
import cn.enlev.ainb.module.agent.enums.AgentQuotaDimensionEnum;
import cn.enlev.ainb.module.agent.enums.AgentQuotaPeriodEnum;
import cn.enlev.ainb.module.system.api.notify.NotifyMessageSendApi;
import cn.enlev.ainb.module.system.api.tenant.TenantContactApi;
import cn.enlev.ainb.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_QUOTA_DUPLICATE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_QUOTA_EXCEEDED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_QUOTA_TARGET_REQUIRED;

/**
 * AI 助手配额 Service 实现类
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentQuotaServiceImpl implements AgentQuotaService {

    /** 用户级配额的层级标识，用于超限文案里把主语说成「您」（租户级则说「本租户」） */
    private static final String LEVEL_USER = "USER";

    private static final String WARN_TEMPLATE_CODE = "agent_quota_warn";

    @Resource
    private AgentQuotaMapper quotaMapper;
    @Resource
    private AgentUsageLogMapper usageLogMapper;
    /**
     * 工作流维度配额从节点级 trace 汇总 token（那张表只有 token、没有 cost）——见 {@link #evaluate}
     */
    @Resource
    private AgentNodeExecutionMapper nodeExecutionMapper;
    /**
     * ⚠️ 必须 {@code @Lazy}：agent 依赖 system，直接注入会在启动时形成过早的依赖解析
     */
    @Resource
    @Lazy
    private NotifyMessageSendApi notifyMessageSendApi;
    @Resource
    @Lazy
    private TenantContactApi tenantContactApi;

    @Override
    public void checkQuota(Long userId) {
        for (QuotaUsageBO usage : getQuotaUsage(userId)) {
            if (Boolean.TRUE.equals(usage.getExceeded())) {
                throw exception(AGENT_QUOTA_EXCEEDED,
                        LEVEL_USER.equals(usage.getLevel()) ? "您" : "本租户",
                        describePeriod(usage.getPeriod()),
                        describeLimit(usage));
            }
        }
    }

    @Override
    public void checkAgentQuota(String agentCode) {
        if (StrUtil.isBlank(agentCode)) {
            return;
        }
        checkTargetQuota(quotaMapper.selectAgentQuota(agentCode), "Agent（" + agentCode + "）");
    }

    @Override
    public void checkWorkflowQuota(String workflowCode) {
        if (StrUtil.isBlank(workflowCode)) {
            return;
        }
        checkTargetQuota(quotaMapper.selectWorkflowQuota(workflowCode), "工作流（" + workflowCode + "）");
    }

    /**
     * Agent / 工作流维度共用的「查配额 → 评估 → 超限即拒」三步。
     *
     * <p>没配额度（{@code quota==null}）或已关闭 → 直接放行（= 不限制，§8.5 兜底）。
     * 与 {@link #checkQuota} 同款纪律：只在请求入口调，一旦放行这一轮就跑完。
     */
    private void checkTargetQuota(AgentQuotaDO quota, String subject) {
        if (!isEnabled(quota)) {
            return;
        }
        QuotaUsageBO usage = evaluate(quota);
        if (Boolean.TRUE.equals(usage.getExceeded())) {
            throw exception(AGENT_QUOTA_EXCEEDED, subject,
                    describePeriod(usage.getPeriod()), describeLimit(usage));
        }
    }

    @Override
    public List<QuotaUsageBO> getQuotaUsage(Long userId) {
        List<QuotaUsageBO> result = new ArrayList<>(2);
        // 用户级在前：它是「租户内给某人单独设的更低上限」，
        // 先判它，超限时报出来的也是更贴切的那一条
        AgentQuotaDO userQuota = userId == null ? null : quotaMapper.selectUserQuota(userId);
        if (isEnabled(userQuota)) {
            result.add(evaluate(userQuota));
        }
        AgentQuotaDO tenantQuota = quotaMapper.selectTenantQuota();
        if (isEnabled(tenantQuota)) {
            result.add(evaluate(tenantQuota));
        }
        return result;
    }

    @Override
    public List<AgentQuotaDO> listQuotas() {
        return quotaMapper.selectListAll();
    }

    @Override
    public Long saveQuota(AgentQuotaDO quota) {
        // 归一化维度与各维度专属字段（含「WORKFLOW 清空金额上限」），并校验目标齐全
        normalize(quota);
        // 🔴 同维度同目标不得重复：唯一键含可为 NULL 的列、拦不住，放两条进去会让 selectOne 抛
        //    TooManyResultsException（该维度一提问就 500）。见 AGENT_QUOTA_DUPLICATE 注释。
        AgentQuotaDO existing = findSameTarget(quota);
        if (existing != null && !Objects.equals(existing.getId(), quota.getId())) {
            throw exception(AGENT_QUOTA_DUPLICATE, describeDimensionTarget(quota));
        }
        // 🔴 改配额时清掉预警去重标记：额度从 10 万调到 100 万之后，
        //    旧的「本月已预警过」会让新额度的预警一直发不出来 ——
        //    而这正是最需要预警的时候（额度刚放宽，用量往往会涨）
        quota.setWarnedPeriod("");
        if (quota.getId() == null) {
            quotaMapper.insert(quota);
        } else {
            quotaMapper.update(quota, clearableColumnsWrapper(quota));
        }
        log.info("[saveQuota][配额({})已保存，维度({}) 用户({}) 目标({}) token 上限({})]",
                quota.getId(), quota.getDimension(), quota.getUserId(),
                quota.getTargetKey(), quota.getTokenLimit());
        return quota.getId();
    }

    /**
     * 把「本次要清空的列」显式写进 SET 子句
     *
     * <p>🔴 <b>为什么不能直接 {@code updateById(quota)}</b>：MyBatis-Plus 的默认字段策略是
     * {@code NOT_NULL}，实体上为 null 的字段会被<b>整个从 SET 子句里省掉</b> ——
     * 那在 SQL 里的含义是「别动这一列」，而不是「把这一列清成 null」。
     * 全仓没有任何模块改过 {@code update-strategy}，所以这条默认值到处生效。
     *
     * <p>而 {@link #normalize} 干的<b>全部</b>工作就是把不该有的字段抹成 null
     * （TENANT 抹 userId / targetKey，USER 抹 targetKey，AGENT / WORKFLOW 抹 userId，
     * 不支持金额的维度抹 costLimit），管理员把上限改回「不限」同样是靠传 null。
     * 走 {@code updateById} 的话，<b>这些清空一个都落不了库</b>：
     * 接口返回成功、日志照打、库里旧值原封不动。
     *
     * <p>后果最重的一条是维度切换：{@code AgentQuotaMapper#selectTenantQuota} 以
     * {@code isNull(user_id)} 为判据，一行配额从 USER 改成 TENANT 之后 user_id 残留，
     * 这条配额就<b>再也查不出来</b> —— 配额静默失效，等于不限流。
     * 反过来把 tokenLimit 清成「不限」也不生效，仍按旧上限拦人。
     *
     * <p>⚠️ 同一个方法里 {@code setWarnedPeriod("")} 却是<b>好使</b>的：空串不是 null，
     * 默认策略会把它带上。真假两种写法挨在一起，正是这个坑最难在 review 里看出来的原因。
     *
     * <p>🔴 <b>也不能改成在 DO 上打 {@code @TableField(updateStrategy = ALWAYS)}</b>：
     * {@link #maybeWarn} 用 {@code new AgentQuotaDO()} 只填 id + warnedPeriod 做<b>局部</b>更新，
     * ALWAYS 会让那一次更新把 userId / targetKey / tokenLimit / costLimit 一并清成 null ——
     * 把一个潜伏缺陷换成一个每次预警都触发的现行缺陷。局部更新与全量更新共用同一个 DO，
     * 所以「哪些列允许写 null」只能在<b>调用点</b>说清楚。
     * 与 {@code FormDesignServiceImpl#clearableColumnsWrapper} 同一口径。
     */
    private LambdaUpdateWrapper<AgentQuotaDO> clearableColumnsWrapper(AgentQuotaDO quota) {
        LambdaUpdateWrapper<AgentQuotaDO> wrapper = new LambdaUpdateWrapper<AgentQuotaDO>()
                .eq(AgentQuotaDO::getId, quota.getId());
        // 条件都是「这一次没传值」—— 传了值的由 quota 自己那一支写进 SET，不重复 set
        wrapper.set(quota.getUserId() == null, AgentQuotaDO::getUserId, null);
        wrapper.set(quota.getTargetKey() == null, AgentQuotaDO::getTargetKey, null);
        wrapper.set(quota.getTokenLimit() == null, AgentQuotaDO::getTokenLimit, null);
        wrapper.set(quota.getCostLimit() == null, AgentQuotaDO::getCostLimit, null);
        wrapper.set(quota.getWarnRatio() == null, AgentQuotaDO::getWarnRatio, null);
        return wrapper;
    }

    /**
     * 归一化维度字段，并校验「目标齐全」。
     *
     * <p>把「哪个维度该有哪些字段」这条规则收在一处：TENANT 清空 user_id/target_key；USER 清空 target_key；
     * AGENT/WORKFLOW 清空 user_id（它们跨用户合并计量）。🔴 WORKFLOW 顺带清空 cost_limit ——
     * 工作流用量只有 token（trace 表无 cost 列），金额上限对它是「配了不生效」的死配置，落库前就抹掉。
     *
     * <p>校验目标齐全（USER 必须有 user_id、AGENT/WORKFLOW 必须有 target_key）：缺目标的配额行
     * 永远匹配不到用量，等于静默失效（平台头号缺陷），故保存时就拦下（{@code AGENT_QUOTA_TARGET_REQUIRED}）。
     */
    private void normalize(AgentQuotaDO quota) {
        AgentQuotaDimensionEnum dim = dimensionOf(quota);
        quota.setDimension(dim.getDimension());
        switch (dim) {
            case TENANT -> {
                quota.setUserId(null);
                quota.setTargetKey(null);
            }
            case USER -> {
                quota.setTargetKey(null);
                if (quota.getUserId() == null) {
                    throw exception(AGENT_QUOTA_TARGET_REQUIRED, dim.getName());
                }
            }
            case AGENT, WORKFLOW -> {
                quota.setUserId(null);
                if (StrUtil.isBlank(quota.getTargetKey())) {
                    throw exception(AGENT_QUOTA_TARGET_REQUIRED, dim.getName());
                }
            }
        }
        if (!dim.isCostSupported()) {
            quota.setCostLimit(null);
        }
    }

    /**
     * 查同维度同目标的既有配额（用于重复校验）。目标不全时返回 null（无从判重）。
     */
    private AgentQuotaDO findSameTarget(AgentQuotaDO quota) {
        AgentQuotaDimensionEnum dim = AgentQuotaDimensionEnum.parse(quota.getDimension());
        if (dim == null) {
            return null;
        }
        return switch (dim) {
            case TENANT -> quotaMapper.selectTenantQuota();
            case USER -> quota.getUserId() == null ? null : quotaMapper.selectUserQuota(quota.getUserId());
            case AGENT -> StrUtil.isBlank(quota.getTargetKey()) ? null
                    : quotaMapper.selectAgentQuota(quota.getTargetKey());
            case WORKFLOW -> StrUtil.isBlank(quota.getTargetKey()) ? null
                    : quotaMapper.selectWorkflowQuota(quota.getTargetKey());
        };
    }

    private String describeDimensionTarget(AgentQuotaDO quota) {
        AgentQuotaDimensionEnum dim = AgentQuotaDimensionEnum.parse(quota.getDimension());
        String name = dim == null ? quota.getDimension() : dim.getName();
        return StrUtil.isBlank(quota.getTargetKey()) ? name : name + "：" + quota.getTargetKey();
    }

    @Override
    public void deleteQuota(Long id) {
        quotaMapper.deleteById(id);
        log.info("[deleteQuota][配额({})已删除，该维度恢复为不限制]", id);
    }

    /**
     * 未配置或已关闭 = 不限制（但仍然全量计量）
     *
     * 方案 §8.5 的兜底。默认放行而不是默认拦截：配额是<b>成本控制</b>手段，
     * 不是安全边界 —— 没配就把所有人挡在门外，只会让人以为系统坏了。
     */
    private boolean isEnabled(AgentQuotaDO quota) {
        return quota != null
                && Objects.equals(quota.getStatus(), CommonStatusEnum.ENABLE.getStatus());
    }

    /**
     * 算出某条配额当前的使用情况，并在跨过预警线时通知管理员。
     *
     * <p>🔴 计量来源按<b>维度</b>分流（这是 #6b 的核心）：
     * <ul>
     *   <li>TENANT / USER：计费表 {@code agent_usage_log} 直接汇总（token + 金额），口径与历史一致；</li>
     *   <li>AGENT：计费表 JOIN 会话表按 {@code agent_def.code} 归因（token + 金额）——不加列、不改计费写入口径；</li>
     *   <li>WORKFLOW：节点级 trace {@code agent_node_execution} JOIN 执行表按 {@code code} 汇总，<b>只有 token</b>
     *       （trace 无 cost 列），金额一律记 0、且上限被强制忽略（{@code dim.isCostSupported()==false}）。</li>
     * </ul>
     */
    private QuotaUsageBO evaluate(AgentQuotaDO quota) {
        AgentQuotaPeriodEnum period = AgentQuotaPeriodEnum.parse(quota.getPeriod());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime begin = period.periodStart(now);
        AgentQuotaDimensionEnum dim = dimensionOf(quota);

        long usedTokens;
        BigDecimal usedCost;
        switch (dim) {
            case USER -> {
                AgentUsageLogMapper.UsageSumBO sum = usageLogMapper.selectUsageSum(quota.getUserId(), begin);
                usedTokens = sum.getTotalTokens();
                usedCost = sum.getTotalCost();
            }
            case AGENT -> {
                AgentUsageLogMapper.UsageSumBO sum = usageLogMapper.selectUsageSumByAgent(quota.getTargetKey(), begin);
                usedTokens = sum.getTotalTokens();
                usedCost = sum.getTotalCost();
            }
            case WORKFLOW -> {
                Long tokens = nodeExecutionMapper.sumTokensByWorkflowCode(quota.getTargetKey(), begin);
                usedTokens = tokens == null ? 0L : tokens;
                usedCost = BigDecimal.ZERO;
            }
            default -> { // TENANT
                AgentUsageLogMapper.UsageSumBO sum = usageLogMapper.selectUsageSum(null, begin);
                usedTokens = sum.getTotalTokens();
                usedCost = sum.getTotalCost();
            }
        }

        QuotaUsageBO usage = new QuotaUsageBO();
        usage.setLevel(dim.getDimension());
        usage.setDimension(dim.getDimension());
        usage.setTargetKey(quota.getTargetKey());
        usage.setPeriod(period.getPeriod());
        usage.setUsedTokens(usedTokens);
        usage.setUsedCost(usedCost);
        usage.setTokenLimit(quota.getTokenLimit());
        // 🔴 WORKFLOW 不支持金额上限：强制置 null，让 describeLimit/usedRatio/costExceeded 自然忽略它，
        //    杜绝「配了金额上限却从不生效」的死配置（saveQuota 落库前也会清掉，这里是运行期双保险）
        usage.setCostLimit(dim.isCostSupported() ? quota.getCostLimit() : null);

        // 两个上限可以并存，任一触顶即算超限
        boolean tokenExceeded = quota.getTokenLimit() != null && usedTokens >= quota.getTokenLimit();
        boolean costExceeded = usage.getCostLimit() != null
                && usedCost.compareTo(usage.getCostLimit()) >= 0;
        usage.setExceeded(tokenExceeded || costExceeded);

        if (!usage.getExceeded()) {
            maybeWarn(quota, usage, period, now);
        }
        return usage;
    }

    /**
     * 取配额的维度；{@code dimension} 列空时按 user_id 推断（兼容未回填的历史行与测试造的旧数据）。
     *
     * <p>推断只覆盖 TENANT/USER（user_id 空=租户级、非空=用户级）；AGENT/WORKFLOW 必须显式带 dimension
     * （前端新增时一定带、saveQuota 也会归一化落库），推断不会凭空造出它们。
     */
    private AgentQuotaDimensionEnum dimensionOf(AgentQuotaDO quota) {
        AgentQuotaDimensionEnum dim = AgentQuotaDimensionEnum.parse(quota.getDimension());
        if (dim != null) {
            return dim;
        }
        return quota.getUserId() != null ? AgentQuotaDimensionEnum.USER : AgentQuotaDimensionEnum.TENANT;
    }

    /**
     * 跨过预警线时通知一次
     *
     * <h3>为什么要按周期去重</h3>
     * 越过预警线之后，<b>每一次请求都满足预警条件</b>。不去重的话管理员一天收几百条，
     * 结果一定是他把通知关掉 —— 于是真正触顶时也没人知道。
     * 去重标记落在配额行上，跨重启有效。
     *
     * <h3>为什么失败只打日志</h3>
     * 预警是<b>附加价值</b>，不是主流程。通知发不出去（模板被删、收件人被停用）
     * 不该让用户连提问都提不了。
     */
    private void maybeWarn(AgentQuotaDO quota, QuotaUsageBO usage,
                           AgentQuotaPeriodEnum period, LocalDateTime now) {
        BigDecimal ratio = usedRatio(usage);
        if (ratio == null || quota.getWarnRatio() == null
                || ratio.compareTo(quota.getWarnRatio()) < 0) {
            return;
        }
        String periodKey = period.periodKey(now);
        if (Objects.equals(quota.getWarnedPeriod(), periodKey)) {
            return;
        }

        // 先落去重标记再发：反过来的话，发送成功但落标记失败，下次又会发一遍。
        // 宁可漏一条预警，也不要每次请求都轰一条
        AgentQuotaDO update = new AgentQuotaDO();
        update.setId(quota.getId());
        update.setWarnedPeriod(periodKey);
        quotaMapper.updateById(update);

        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("period", describePeriod(usage.getPeriod()));
            params.put("ratio", ratio.multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.DOWN).toPlainString());
            params.put("used", describeUsed(usage));
            params.put("limit", describeLimit(usage));
            NotifySendSingleToUserReqDTO reqDTO = new NotifySendSingleToUserReqDTO();
            // 租户级配额通知租户的联系人；用户级配额直接通知本人
            reqDTO.setUserId(quota.getUserId() != null ? quota.getUserId() : resolveTenantAdmin());
            reqDTO.setTemplateCode(WARN_TEMPLATE_CODE);
            reqDTO.setTemplateParams(params);
            if (reqDTO.getUserId() == null) {
                log.warn("[maybeWarn][配额({})达到预警线但找不到收件人，跳过通知]", quota.getId());
                return;
            }
            notifyMessageSendApi.sendSingleMessageToAdmin(reqDTO);
            log.info("[maybeWarn][配额({})达到预警线({}%)，已通知用户({})]",
                    quota.getId(), params.get("ratio"), reqDTO.getUserId());
        } catch (Exception ex) {
            log.warn("[maybeWarn][配额({})的预警通知发送失败：{}]", quota.getId(), ex.getMessage());
        }
    }

    /**
     * 取租户的联系人作为「租户管理员」
     *
     * 没有专门的「租户管理员」概念，联系人是最接近的那个。
     */
    private Long resolveTenantAdmin() {
        Long tenantId = TenantContextHolder.getTenantId();
        return tenantId == null ? null : tenantContactApi.getContactUserId(tenantId);
    }

    /**
     * 已用比例，取 token 与金额两个维度里更高的那个
     */
    private BigDecimal usedRatio(QuotaUsageBO usage) {
        BigDecimal ratio = null;
        if (usage.getTokenLimit() != null && usage.getTokenLimit() > 0) {
            ratio = BigDecimal.valueOf(usage.getUsedTokens())
                    .divide(BigDecimal.valueOf(usage.getTokenLimit()), 4, RoundingMode.HALF_UP);
        }
        if (usage.getCostLimit() != null && usage.getCostLimit().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal costRatio = usage.getUsedCost()
                    .divide(usage.getCostLimit(), 4, RoundingMode.HALF_UP);
            ratio = ratio == null ? costRatio : ratio.max(costRatio);
        }
        return ratio;
    }

    private String describePeriod(String period) {
        return AgentQuotaPeriodEnum.parse(period).getName();
    }

    private String describeUsed(QuotaUsageBO usage) {
        List<String> parts = new ArrayList<>(2);
        if (usage.getTokenLimit() != null) {
            parts.add(usage.getUsedTokens() + " token");
        }
        if (usage.getCostLimit() != null) {
            parts.add(usage.getUsedCost().toPlainString() + " 元");
        }
        return StrUtil.join("、", parts);
    }

    private String describeLimit(QuotaUsageBO usage) {
        List<String> parts = new ArrayList<>(2);
        if (usage.getTokenLimit() != null) {
            parts.add(usage.getTokenLimit() + " token");
        }
        if (usage.getCostLimit() != null) {
            parts.add(usage.getCostLimit().toPlainString() + " 元");
        }
        return StrUtil.join("、", parts);
    }

}
