package cn.enlev.ainb.module.agent.service.usage;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.usage.AgentModelPriceDO;
import cn.enlev.ainb.module.agent.dal.dataobject.usage.AgentUsageLogDO;
import cn.enlev.ainb.module.agent.dal.mysql.usage.AgentModelPriceMapper;
import cn.enlev.ainb.module.agent.dal.mysql.usage.AgentUsageLogMapper;
import cn.enlev.ainb.module.agent.enums.AgentUsageSceneEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计费逻辑的单元测试（方案 §8.6）
 *
 * @author 走进新时代
 */
public class AgentUsagePricingTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentUsageLogServiceImpl usageLogService;

    @Mock
    private AgentUsageLogMapper usageLogMapper;
    @Mock
    private AgentModelPriceMapper modelPriceMapper;
    @Mock
    private cn.enlev.ainb.module.agent.framework.agent.config.AgentUsageProperties usageProperties;

    private AgentUsageLogDO captureInserted() {
        ArgumentCaptor<AgentUsageLogDO> captor = ArgumentCaptor.forClass(AgentUsageLogDO.class);
        verify(usageLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    private void record(int promptTokens, int completionTokens, LocalDateTime beginTime) {
        usageLogService.record(AgentUsageLogService.UsageRecord.builder()
                .userId(1L)
                .tenantId(1L)
                .scene(AgentUsageSceneEnum.CHAT)
                .platform("TongYi")
                .modelName("qwen3.7-plus")
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens)
                .beginTime(beginTime)
                .endTime(beginTime)
                .build());
    }

    private AgentModelPriceDO price(long id, String prompt, String completion,
                                    LocalDateTime effectiveFrom) {
        AgentModelPriceDO dto = new AgentModelPriceDO();
        dto.setId(id);
        dto.setPlatform("TongYi");
        dto.setModelName("qwen3.7-plus");
        dto.setPromptPrice(new BigDecimal(prompt));
        dto.setCompletionPrice(new BigDecimal(completion));
        dto.setCurrency("CNY");
        dto.setEffectiveFrom(effectiveFrom);
        return dto;
    }

    @Test
    @DisplayName("按「元/1000 token」口径算钱")
    public void testCalculatesCost() {
        LocalDateTime at = LocalDateTime.of(2026, 9, 23, 10, 0);
        when(modelPriceMapper.selectEffectivePrice(eq("TongYi"), eq("qwen3.7-plus"), any()))
                .thenReturn(price(1L, "0.000800", "0.002000", LocalDateTime.of(2026, 1, 1, 0, 0)));

        // 10000 输入 * 0.0008/1000 = 0.008；5000 输出 * 0.002/1000 = 0.010
        record(10000, 5000, at);

        AgentUsageLogDO log = captureInserted();
        assertEquals(0, new BigDecimal("0.018000").compareTo(log.getCost()),
                "实际算出 " + log.getCost());
    }

    @Test
    @DisplayName("🔴 单价快照要带上来源行，否则「为什么算出这个数」说不清")
    public void testPriceSnapshot() {
        when(modelPriceMapper.selectEffectivePrice(any(), any(), any()))
                .thenReturn(price(7L, "0.000800", "0.002000", LocalDateTime.of(2026, 1, 1, 0, 0)));

        record(1000, 1000, LocalDateTime.of(2026, 9, 23, 10, 0));

        String snapshot = captureInserted().getPriceSnapshot();
        assertTrue(snapshot.contains("\"priceId\":7"), snapshot);
        assertTrue(snapshot.contains("0.000800"), snapshot);
        assertTrue(snapshot.contains("per1kTokens"), "单位要写明，否则快照本身会有歧义：" + snapshot);
    }

    @Test
    @DisplayName("🔴 没配单价时算 0，但快照里要说明原因")
    public void testNoPriceConfigured() {
        when(modelPriceMapper.selectEffectivePrice(any(), any(), any())).thenReturn(null);

        record(1000, 1000, LocalDateTime.of(2026, 9, 23, 10, 0));

        AgentUsageLogDO log = captureInserted();
        assertEquals(0, BigDecimal.ZERO.compareTo(log.getCost()));
        // 不留痕的话，「金额是 0」会被理解成「没花钱」
        assertTrue(log.getPriceSnapshot().contains("未配置"), log.getPriceSnapshot());
        // token 用量必须照常记录 —— 计量日志是收费证据，不能因为价目表没维护就出现空洞
        assertEquals(2000, log.getTotalTokens());
    }

    @Test
    @DisplayName("🔴 取单价抛异常时，计量记录仍然必须写进去")
    public void testPricingFailureMustNotLoseRecord() {
        // 实测踩过：租户拦截器给不分租户的单价表拼了 tenant_id，
        // SQL 异常一路冒到 record()，那次对话一条收费证据都没留下 ——
        // token 已经烧掉了，账上却查无此事
        when(modelPriceMapper.selectEffectivePrice(any(), any(), any()))
                .thenThrow(new RuntimeException("Unknown column 'tenant_id' in 'where clause'"));

        record(1000, 500, LocalDateTime.of(2026, 9, 23, 10, 0));

        AgentUsageLogDO log = captureInserted();
        assertEquals(1500, log.getTotalTokens(), "token 用量不能丢");
        assertEquals(0, BigDecimal.ZERO.compareTo(log.getCost()));
        assertTrue(log.getPriceSnapshot().contains("取单价失败"), log.getPriceSnapshot());
    }

    @Test
    @DisplayName("🔴 取价用的是调用时刻，不是当前时刻 —— 否则历史账单会被涨价污染")
    public void testUsesCallTimeNotNow() {
        LocalDateTime callTime = LocalDateTime.of(2026, 6, 1, 10, 0);
        when(modelPriceMapper.selectEffectivePrice(any(), any(), any()))
                .thenReturn(price(1L, "0.000800", "0.002000", LocalDateTime.of(2026, 1, 1, 0, 0)));

        record(1000, 0, callTime);

        // 断言传给 mapper 的时刻就是调用时刻
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(modelPriceMapper).selectEffectivePrice(any(), any(), captor.capture());
        assertEquals(callTime, captor.getValue(),
                "必须按调用时刻取价，取当前时刻会让三个月前的账单变成今天的价钱");
    }

}
