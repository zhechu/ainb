package cn.enlev.ainb.module.agent.service.usage;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.agent.dal.dataobject.usage.AgentUsageLogDO;
import cn.enlev.ainb.module.agent.dal.mysql.usage.AgentUsageLogMapper;
import cn.enlev.ainb.module.agent.enums.AgentUsageSceneEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentTextUtils;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import cn.enlev.ainb.module.agent.dal.dataobject.usage.AgentModelPriceDO;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 计量日志 Service 实现类
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentUsageLogServiceImpl implements AgentUsageLogService {

    /** 输入原文的存储上限：超长截断，避免个别超大输入把表撑爆 */
    private static final int INPUT_TEXT_MAX_LENGTH = 10_000;
    /**
     * 单价快照的列宽上限。
     * 🔴 有列宽还要再截一次：超长时 MySQL 抛的是 Data truncation，
     *    而它会把【整条计量记录】一起带走 —— token 烧了、账上查无此事。
     *    列宽是容量，截断是保险，两层都要有
     */
    private static final int PRICE_SNAPSHOT_MAX_LENGTH = 500;

    /** 与 {@code agent_usage_log.error_msg} 的列宽一致 */
    private static final int ERROR_MSG_MAX_LENGTH = 500;

    public static final String TOKEN_SOURCE_PROVIDER = "PROVIDER";
    public static final String TOKEN_SOURCE_ESTIMATED = "ESTIMATED";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILURE = "FAILURE";

    /** 单价口径是「元 / 1000 token」 */
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    @Resource
    private AgentUsageLogMapper usageLogMapper;
    @Resource
    private cn.enlev.ainb.module.agent.dal.mysql.usage.AgentModelPriceMapper modelPriceMapper;
    /** 只用于把 user_id 翻成昵称，取不到不影响看板 */
    @Resource
    @org.springframework.context.annotation.Lazy
    private cn.enlev.ainb.module.system.api.user.AdminUserApi adminUserApi;
    @Resource
    private cn.enlev.ainb.module.agent.framework.agent.config.AgentUsageProperties usageProperties;

    @Override
    public void record(UsageRecord record) {
        try {
            AgentUsageLogDO log = new AgentUsageLogDO();
            log.setUserId(record.getUserId() != null
                    ? record.getUserId() : SecurityFrameworkUtils.getLoginUserId());
            log.setTenantId(record.getTenantId() != null
                    ? record.getTenantId() : TenantContextHolder.getTenantId());
            log.setConversationId(record.getConversationId());
            log.setMessageId(record.getMessageId());
            log.setModelId(record.getModelId());
            log.setPlatform(StrUtil.emptyIfNull(record.getPlatform()));
            log.setModelName(StrUtil.emptyIfNull(record.getModelName()));
            log.setScene(record.getScene() == null
                    ? AgentUsageSceneEnum.CHAT.getScene() : record.getScene().getScene());
            log.setPromptTokens(nullToZero(record.getPromptTokens()));
            log.setCompletionTokens(nullToZero(record.getCompletionTokens()));
            log.setTotalTokens(nullToZero(record.getTotalTokens()));
            log.setTokenSource(StrUtil.blankToDefault(record.getTokenSource(), TOKEN_SOURCE_PROVIDER));
            applyPrice(log, record);
            log.setInputText(AgentTextUtils.truncateForColumn(record.getInputText(),
                    INPUT_TEXT_MAX_LENGTH));
            LocalDateTime begin = record.getBeginTime() != null
                    ? record.getBeginTime() : LocalDateTime.now();
            LocalDateTime end = record.getEndTime() != null ? record.getEndTime() : LocalDateTime.now();
            log.setBeginTime(begin);
            log.setEndTime(end);
            log.setDurationMs((int) Duration.between(begin, end).toMillis());
            log.setStatus(StrUtil.blankToDefault(record.getStatus(), STATUS_SUCCESS));
            // 🔴 必须用 truncateForColumn 而不是 StrUtil.maxLength：
            //    后者返回 n+3 个字符（末尾补三个点），写进 varchar(500) 会插入失败。
            //    而这一行在异常路径上 —— 报错信息越长越写不进去，
            //    恰好在最需要留证据的时候丢掉整条计量记录
            log.setErrorMsg(AgentTextUtils.truncateForColumn(record.getErrorMsg(), ERROR_MSG_MAX_LENGTH));
            // 保留期在写入时【快照】下来，而不是清理时现读配置。
            // 理由：保留期是「当时对用户承诺了什么」，不该被后来改的配置追溯改写
            log.setRetentionDays(usageProperties.getInputTextRetentionDays());
            usageLogMapper.insert(log);
        } catch (Exception ex) {
            // 🔴 计量失败绝不能让业务调用失败：用户问了话、模型也答了，
            // 结果因为写日志出错收到「系统异常」—— 那是本末倒置。
            // 但也不能静默吞掉：写不进去意味着账单有空洞，所以打 ERROR 让它在监控里可见。
            log.error("[record][计量日志写入失败，账单将出现空洞] scene={} user={} tokens={}",
                    record.getScene(), record.getUserId(), record.getTotalTokens(), ex);
        }
    }

    @Override
    public void recordSuccess(AgentUsageSceneEnum scene, AiChatRespDTO resp,
                              String inputText, LocalDateTime beginTime) {
        AiChatRespDTO.Usage usage = resp == null ? null : resp.getUsage();
        record(UsageRecord.builder()
                .scene(scene)
                .platform(resp == null ? null : resp.getPlatform())
                .modelName(resp == null ? null : resp.getModel())
                .promptTokens(usage == null ? null : usage.getPromptTokens())
                .completionTokens(usage == null ? null : usage.getCompletionTokens())
                .totalTokens(usage == null ? null : usage.getTotalTokens())
                // 厂商没回传 usage 时标记为估算 —— 账单上要对估算值单独说明，
                // 混为一谈的话事后没法区分哪些数字是可信的
                .tokenSource(usage == null ? TOKEN_SOURCE_ESTIMATED : TOKEN_SOURCE_PROVIDER)
                .inputText(inputText)
                .beginTime(beginTime)
                .endTime(LocalDateTime.now())
                .status(STATUS_SUCCESS)
                .build());
    }

    @Override
    public void recordFailure(AgentUsageSceneEnum scene, String inputText,
                              LocalDateTime beginTime, String errorMsg) {
        record(UsageRecord.builder()
                .scene(scene)
                .inputText(inputText)
                .beginTime(beginTime)
                .endTime(LocalDateTime.now())
                .status(STATUS_FAILURE)
                .errorMsg(errorMsg)
                .build());
    }

    private Integer nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 按<b>调用当时</b>的单价算钱，并把单价快照一起存下来
     *
     * <h3>为什么要存快照，价目表里不是已经有了吗</h3>
     * 两者不是冗余：价目表是「现在什么价」，快照是「这笔账当时按什么价算的」。
     * 万一价目表被改错、或者某次取价逻辑有 bug，快照是唯一的现场证据。
     * 没有它的话，「为什么这笔算出来是这个数」永远说不清。
     *
     * <h3>取不到单价时算 0，但要在快照里说明</h3>
     * 不能因为没配单价就拒绝记录 —— token 用量本身是准确的、也是有价值的，
     * 而且计量日志是收费证据，不该因为价目表没维护就出现空洞。
     * 但必须留下痕迹，否则「账单金额是 0」会被理解成「没花钱」。
     */
    private void applyPrice(AgentUsageLogDO logDO, UsageRecord record) {
        LocalDateTime at = record.getBeginTime() != null
                ? record.getBeginTime() : LocalDateTime.now();
        AgentModelPriceDO price;
        try {
            price = StrUtil.isBlank(record.getModelName()) ? null
                    : modelPriceMapper.selectEffectivePrice(
                            record.getPlatform(), record.getModelName(), at);
        } catch (Exception ex) {
            // 🔴 计价只是<b>给计量记录做增强</b>，绝不能反过来把记录本身弄丢。
            //    实测踩过：租户拦截器给不分租户的单价表拼了 tenant_id，
            //    抛出的 SQL 异常一路冒到 record()，结果那次对话【一条收费证据都没留下】。
            //    token 已经烧掉了，账上却查无此事 —— 这比算错金额严重得多。
            log.warn("[applyPrice][取单价失败，本条按未计价记录：{}]", ex.getMessage());
            logDO.setPriceSnapshot("{\"priced\":false,\"reason\":\"取单价失败\"}");
            logDO.setCost(BigDecimal.ZERO);
            return;
        }
        if (price == null) {
            logDO.setPriceSnapshot("{\"priced\":false,\"reason\":\"未配置该模型的单价\"}");
            logDO.setCost(BigDecimal.ZERO);
            return;
        }

        // 单价口径是「元 / 1000 token」，所以要除以 1000
        BigDecimal promptCost = BigDecimal.valueOf(logDO.getPromptTokens())
                .multiply(price.getPromptPrice())
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);
        BigDecimal completionCost = BigDecimal.valueOf(logDO.getCompletionTokens())
                .multiply(price.getCompletionPrice())
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);
        logDO.setCost(promptCost.add(completionCost));

        // 快照里带上单价来源行的 id 与生效时间：对账时能直接定位到是哪一条价目
        logDO.setPriceSnapshot(AgentTextUtils.truncateForColumn(String.format(
                "{\"priceId\":%d,\"promptPrice\":%s,\"completionPrice\":%s,"
                        + "\"currency\":\"%s\",\"effectiveFrom\":\"%s\",\"unit\":\"per1kTokens\"}",
                price.getId(), price.getPromptPrice().toPlainString(),
                price.getCompletionPrice().toPlainString(),
                price.getCurrency(), price.getEffectiveFrom()),
                PRICE_SNAPSHOT_MAX_LENGTH));
    }

    @Override
    public java.util.List<UsageGroupBO> summarize(String dimension,
                                                  LocalDateTime begin, LocalDateTime end,
                                                  Long userId) {
        AgentUsageLogMapper.GroupDimension groupDimension =
                AgentUsageLogMapper.GroupDimension.parse(dimension);
        java.util.List<java.util.Map<String, Object>> rows =
                usageLogMapper.selectUsageGroupBy(groupDimension, begin, end, userId);

        // 按用户分组时把昵称补上：看板上一列 user_id 等于没有看板，
        // 谁也记不住 250 是哪个人
        java.util.Map<Long, String> userNicknames = groupDimension
                == AgentUsageLogMapper.GroupDimension.USER
                ? resolveUserNicknames(rows) : java.util.Map.of();

        java.util.List<UsageGroupBO> result = new java.util.ArrayList<>(rows.size());
        for (java.util.Map<String, Object> row : rows) {
            UsageGroupBO item = new UsageGroupBO();
            String key = row.get("groupKey") == null ? "" : row.get("groupKey").toString();
            item.setGroupKey(key);
            // ⚠️ 非用户维度的 groupKey 不是数字（如 CHAT、qwen3.7-plus），
            //    parseLongOrNull 会返回 null，而 Map.of() 是不可变 Map，
            //    getOrDefault(null, ...) 直接抛 NPE。必须先判
            Long numericKey = parseLongOrNull(key);
            item.setGroupLabel(numericKey == null ? key
                    : userNicknames.getOrDefault(numericKey, key));
            item.setCallCount(toLong(row.get("callCount")));
            item.setPromptTokens(toLong(row.get("promptTokens")));
            item.setCompletionTokens(toLong(row.get("completionTokens")));
            item.setTotalTokens(toLong(row.get("totalTokens")));
            Object cost = row.get("totalCost");
            item.setTotalCost(cost == null ? java.math.BigDecimal.ZERO
                    : new java.math.BigDecimal(cost.toString()));
            result.add(item);
        }
        return result;
    }

    private java.util.Map<Long, String> resolveUserNicknames(
            java.util.List<java.util.Map<String, Object>> rows) {
        java.util.List<Long> userIds = rows.stream()
                .map(row -> parseLongOrNull(
                        row.get("groupKey") == null ? null : row.get("groupKey").toString()))
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (userIds.isEmpty()) {
            return java.util.Map.of();
        }
        try {
            return adminUserApi.getUserList(userIds).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            cn.enlev.ainb.module.system.api.user.dto.AdminUserRespDTO::getId,
                            cn.enlev.ainb.module.system.api.user.dto.AdminUserRespDTO::getNickname,
                            (a, b) -> a));
        } catch (Exception ex) {
            // 取昵称失败不该让整个看板打不开 —— 退化成显示用户编号即可
            log.warn("[summarize][取用户昵称失败，看板退化为显示用户编号：{}]", ex.getMessage());
            return java.util.Map.of();
        }
    }

    private Long parseLongOrNull(String value) {
        try {
            return value == null || value.isEmpty() ? null : Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    @Override
    public java.util.List<AgentUsageLogDO> getRecentLogs(int limit) {
        return usageLogMapper.selectList(
                new cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX<AgentUsageLogDO>()
                        .orderByDesc(AgentUsageLogDO::getId)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    @Override
    public int clearExpiredInputText(int limit) {
        int count = usageLogMapper.clearExpiredInputText(Math.max(1, Math.min(limit, 1000)));
        if (count > 0) {
            log.info("[clearExpiredInputText][清理已过保留期的输入原文 ({}) 条]", count);
        }
        return count;
    }

}
