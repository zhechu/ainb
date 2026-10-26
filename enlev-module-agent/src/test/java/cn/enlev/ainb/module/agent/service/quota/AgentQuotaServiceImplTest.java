package cn.enlev.ainb.module.agent.service.quota;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.quota.AgentQuotaDO;
import cn.enlev.ainb.module.agent.dal.mysql.quota.AgentQuotaMapper;
import cn.enlev.ainb.module.agent.dal.mysql.usage.AgentUsageLogMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentNodeExecutionMapper;
import cn.enlev.ainb.module.system.api.notify.NotifyMessageSendApi;
import cn.enlev.ainb.module.system.api.tenant.TenantContactApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_QUOTA_DUPLICATE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_QUOTA_EXCEEDED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_QUOTA_TARGET_REQUIRED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentQuotaServiceImpl} 的单元测试
 *
 * @author 走进新时代
 */
public class AgentQuotaServiceImplTest extends BaseMockitoUnitTest {

    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 100L;

    /**
     * 注册 {@link AgentQuotaDO} 的 TableInfo
     *
     * <p>{@code saveQuota} 的更新分支要构造 {@code LambdaUpdateWrapper}
     * （见 {@code AgentQuotaServiceImpl#clearableColumnsWrapper}），而 lambda 形式的列名
     * 得靠 {@code TableInfoHelper} 的缓存反解。本类是纯 Mockito 测试，没有 MyBatis 的启动过程
     * 去注册它，所以这里手动补一次 —— 与 {@code LambdaQueryWrapperXTest} 同一写法。
     */
    @BeforeAll
    public static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentQuotaDO.class);
    }

    @InjectMocks
    private AgentQuotaServiceImpl quotaService;

    @Mock
    private AgentQuotaMapper quotaMapper;
    @Mock
    private AgentUsageLogMapper usageLogMapper;
    @Mock
    private AgentNodeExecutionMapper nodeExecutionMapper;
    @Mock
    private NotifyMessageSendApi notifyMessageSendApi;
    @Mock
    private TenantContactApi tenantContactApi;

    @BeforeEach
    public void setUp() {
        TenantContextHolder.setTenantId(TENANT_ID);
    }

    @AfterEach
    public void tearDown() {
        TenantContextHolder.clear();
    }

    // ==================== 兜底 ====================

    @Test
    @DisplayName("🔴 没配置配额 = 不限制")
    public void testNoQuotaConfigured() {
        when(quotaMapper.selectUserQuota(eq(USER_ID))).thenReturn(null);
        when(quotaMapper.selectTenantQuota()).thenReturn(null);

        assertDoesNotThrow(() -> quotaService.checkQuota(USER_ID));
        // 没配置时不该去查用量 —— 那是白白多一次聚合查询，而它在每次提问前都会跑
        verify(usageLogMapper, never()).selectUsageSum(any(), any());
    }

    @Test
    @DisplayName("配额被关闭时等同于不限制")
    public void testQuotaDisabled() {
        AgentQuotaDO quota = tenantQuota(1000L, null);
        quota.setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(quotaMapper.selectTenantQuota()).thenReturn(quota);
        when(quotaMapper.selectUserQuota(any())).thenReturn(null);

        assertDoesNotThrow(() -> quotaService.checkQuota(USER_ID));
    }

    // ==================== 拒绝 ====================

    @Test
    @DisplayName("🔴 token 用满时拒绝新请求")
    public void testTokenExceeded() {
        when(quotaMapper.selectUserQuota(any())).thenReturn(null);
        when(quotaMapper.selectTenantQuota()).thenReturn(tenantQuota(1000L, null));
        mockUsage(1000L, BigDecimal.ZERO);

        assertServiceException(() -> quotaService.checkQuota(USER_ID),
                AGENT_QUOTA_EXCEEDED, "本租户", "月", "1000 token");
    }

    @Test
    @DisplayName("金额用满时拒绝")
    public void testCostExceeded() {
        when(quotaMapper.selectUserQuota(any())).thenReturn(null);
        when(quotaMapper.selectTenantQuota())
                .thenReturn(tenantQuota(null, new BigDecimal("10.00")));
        mockUsage(0L, new BigDecimal("10.50"));

        assertServiceException(() -> quotaService.checkQuota(USER_ID),
                AGENT_QUOTA_EXCEEDED, "本租户", "月", "10.00 元");
    }

    @Test
    @DisplayName("没用满时放行")
    public void testUnderLimit() {
        when(quotaMapper.selectUserQuota(any())).thenReturn(null);
        when(quotaMapper.selectTenantQuota()).thenReturn(tenantQuota(1000L, null));
        mockUsage(500L, BigDecimal.ZERO);

        assertDoesNotThrow(() -> quotaService.checkQuota(USER_ID));
    }

    @Test
    @DisplayName("🔴 用户级配额优先于租户级报出 —— 它是给某人单独设的更低上限")
    public void testUserQuotaTakesPrecedence() {
        AgentQuotaDO userQuota = tenantQuota(100L, null);
        userQuota.setUserId(USER_ID);
        when(quotaMapper.selectUserQuota(eq(USER_ID))).thenReturn(userQuota);
        when(quotaMapper.selectTenantQuota()).thenReturn(tenantQuota(100000L, null));
        // 用户维度已用满，租户维度还早得很
        when(usageLogMapper.selectUsageSum(eq(USER_ID), any())).thenReturn(usageSum(150L, BigDecimal.ZERO));
        lenient().when(usageLogMapper.selectUsageSum(isNull(), any()))
                .thenReturn(usageSum(150L, BigDecimal.ZERO));

        assertServiceException(() -> quotaService.checkQuota(USER_ID),
                AGENT_QUOTA_EXCEEDED, "您", "月", "100 token");
    }

    // ==================== 预警 ====================

    @Test
    @DisplayName("🔴 达到预警线通知一次，并落去重标记")
    public void testWarnOnce() {
        AgentQuotaDO quota = tenantQuota(1000L, null);
        when(quotaMapper.selectUserQuota(any())).thenReturn(null);
        when(quotaMapper.selectTenantQuota()).thenReturn(quota);
        mockUsage(850L, BigDecimal.ZERO);
        when(tenantContactApi.getContactUserId(eq(TENANT_ID))).thenReturn(9L);

        quotaService.checkQuota(USER_ID);

        verify(notifyMessageSendApi, times(1)).sendSingleMessageToAdmin(any());
        // 去重标记必须落库，否则下次请求还会再发一条
        ArgumentCaptor<AgentQuotaDO> captor = ArgumentCaptor.forClass(AgentQuotaDO.class);
        // BaseMapper 的 updateById 有 (T) 与 (Collection<T>) 两个重载，必须显式定型
        verify(quotaMapper).updateById((AgentQuotaDO) captor.capture());
        assertEquals(AgentQuotaDO.class, captor.getValue().getClass());
        org.junit.jupiter.api.Assertions.assertNotNull(captor.getValue().getWarnedPeriod());
    }

    @Test
    @DisplayName("🔴 同一周期内不重复预警 —— 否则管理员会把通知关掉")
    public void testWarnDedupInSamePeriod() {
        AgentQuotaDO quota = tenantQuota(1000L, null);
        // 本周期已经发过了
        quota.setWarnedPeriod(java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
        when(quotaMapper.selectUserQuota(any())).thenReturn(null);
        when(quotaMapper.selectTenantQuota()).thenReturn(quota);
        mockUsage(900L, BigDecimal.ZERO);

        quotaService.checkQuota(USER_ID);

        verify(notifyMessageSendApi, never()).sendSingleMessageToAdmin(any());
        verify(quotaMapper, never()).updateById(any(AgentQuotaDO.class));
    }

    @Test
    @DisplayName("没到预警线不通知")
    public void testNoWarnBelowRatio() {
        when(quotaMapper.selectUserQuota(any())).thenReturn(null);
        when(quotaMapper.selectTenantQuota()).thenReturn(tenantQuota(1000L, null));
        mockUsage(500L, BigDecimal.ZERO);

        quotaService.checkQuota(USER_ID);
        verify(notifyMessageSendApi, never()).sendSingleMessageToAdmin(any());
    }

    @Test
    @DisplayName("🔴 通知发送失败不能影响主流程")
    public void testWarnFailureDoesNotBreakChat() {
        when(quotaMapper.selectUserQuota(any())).thenReturn(null);
        when(quotaMapper.selectTenantQuota()).thenReturn(tenantQuota(1000L, null));
        mockUsage(850L, BigDecimal.ZERO);
        when(tenantContactApi.getContactUserId(any())).thenReturn(9L);
        when(notifyMessageSendApi.sendSingleMessageToAdmin(any()))
                .thenThrow(new RuntimeException("站内信模板不存在"));

        // 预警是附加价值，发不出去不该让用户连提问都提不了
        assertDoesNotThrow(() -> quotaService.checkQuota(USER_ID));
    }

    @Test
    @DisplayName("查不到收件人时跳过通知，不抛异常")
    public void testNoRecipient() {
        when(quotaMapper.selectUserQuota(any())).thenReturn(null);
        when(quotaMapper.selectTenantQuota()).thenReturn(tenantQuota(1000L, null));
        mockUsage(850L, BigDecimal.ZERO);
        when(tenantContactApi.getContactUserId(any())).thenReturn(null);

        assertDoesNotThrow(() -> quotaService.checkQuota(USER_ID));
        verify(notifyMessageSendApi, never()).sendSingleMessageToAdmin(any());
    }

    // ==================== Agent 维度（#6b）====================

    @Test
    @DisplayName("🔴 Agent 维度：用量按 code 归因，超 token 上限即拒")
    public void testAgentQuotaExceeded() {
        when(quotaMapper.selectAgentQuota(eq("order_cs"))).thenReturn(agentQuota("order_cs", 1000L, null));
        when(usageLogMapper.selectUsageSumByAgent(eq("order_cs"), any()))
                .thenReturn(usageSum(1500L, BigDecimal.ZERO));

        assertServiceException(() -> quotaService.checkAgentQuota("order_cs"),
                AGENT_QUOTA_EXCEEDED, "Agent（order_cs）", "月", "1000 token");
        // 🔴 走 JOIN 归因口径，绝不碰按用户/租户的 selectUsageSum
        verify(usageLogMapper, never()).selectUsageSum(any(), any());
    }

    @Test
    @DisplayName("Agent 维度：没用到上限放行")
    public void testAgentQuotaUnderLimit() {
        when(quotaMapper.selectAgentQuota(eq("order_cs"))).thenReturn(agentQuota("order_cs", 1000L, null));
        when(usageLogMapper.selectUsageSumByAgent(eq("order_cs"), any()))
                .thenReturn(usageSum(500L, BigDecimal.ZERO));

        assertDoesNotThrow(() -> quotaService.checkAgentQuota("order_cs"));
    }

    @Test
    @DisplayName("🔴 Agent 维度：没配额度 = 不限制，且不去查用量")
    public void testAgentQuotaNotConfigured() {
        when(quotaMapper.selectAgentQuota(any())).thenReturn(null);

        assertDoesNotThrow(() -> quotaService.checkAgentQuota("order_cs"));
        verify(usageLogMapper, never()).selectUsageSumByAgent(any(), any());
    }

    @Test
    @DisplayName("Agent 维度：code 为空时直接跳过（默认助手无 Agent 维度）")
    public void testAgentQuotaBlankCode() {
        assertDoesNotThrow(() -> quotaService.checkAgentQuota("  "));
        verify(quotaMapper, never()).selectAgentQuota(any());
    }

    // ==================== 工作流维度（#6b）====================

    @Test
    @DisplayName("🔴 工作流维度：从节点 trace 汇总 token，超限即拒")
    public void testWorkflowQuotaExceeded() {
        when(quotaMapper.selectWorkflowQuota(eq("wf_order"))).thenReturn(workflowQuota("wf_order", 2000L));
        when(nodeExecutionMapper.sumTokensByWorkflowCode(eq("wf_order"), any())).thenReturn(2500L);

        assertServiceException(() -> quotaService.checkWorkflowQuota("wf_order"),
                AGENT_QUOTA_EXCEEDED, "工作流（wf_order）", "月", "2000 token");
    }

    @Test
    @DisplayName("🔴 工作流维度只限 token：误配的金额上限被强制忽略（trace 无 cost、不改计费语义）")
    public void testWorkflowQuotaIgnoresCost() {
        AgentQuotaDO quota = workflowQuota("wf_order", 100000L);
        quota.setCostLimit(new BigDecimal("1.00")); // 作者手滑填了金额上限
        when(quotaMapper.selectWorkflowQuota(eq("wf_order"))).thenReturn(quota);
        when(nodeExecutionMapper.sumTokensByWorkflowCode(eq("wf_order"), any())).thenReturn(10L);

        assertDoesNotThrow(() -> quotaService.checkWorkflowQuota("wf_order"));
    }

    @Test
    @DisplayName("工作流维度：没配额度 = 不限制")
    public void testWorkflowQuotaNotConfigured() {
        when(quotaMapper.selectWorkflowQuota(any())).thenReturn(null);

        assertDoesNotThrow(() -> quotaService.checkWorkflowQuota("wf_order"));
        verify(nodeExecutionMapper, never()).sumTokensByWorkflowCode(any(), any());
    }

    // ==================== 保存归一化与判重（#6b）====================

    @Test
    @DisplayName("🔴 保存工作流配额时强制清空金额上限 —— 它是「配了不生效」的死配置")
    public void testSaveWorkflowQuotaClearsCost() {
        AgentQuotaDO quota = workflowQuota("wf_order", 2000L);
        quota.setCostLimit(new BigDecimal("9.99"));
        when(quotaMapper.selectWorkflowQuota(eq("wf_order"))).thenReturn(null);

        quotaService.saveQuota(quota);

        ArgumentCaptor<AgentQuotaDO> captor = ArgumentCaptor.forClass(AgentQuotaDO.class);
        verify(quotaMapper).insert(captor.capture());
        assertNull(captor.getValue().getCostLimit());
        assertEquals("WORKFLOW", captor.getValue().getDimension());
        // user_id 被归一化为 null（工作流维度跨用户合并计量）
        assertNull(captor.getValue().getUserId());
    }

    @Test
    @DisplayName("🔴 同维度同目标重复新增被拦下 —— 否则 selectOne 会抛、该维度一用就 500")
    public void testSaveDuplicateRejected() {
        AgentQuotaDO existing = agentQuota("order_cs", 1000L, null);
        existing.setId(7L);
        when(quotaMapper.selectAgentQuota(eq("order_cs"))).thenReturn(existing);
        AgentQuotaDO fresh = agentQuota("order_cs", 2000L, null); // id 为空 = 新增

        assertServiceException(() -> quotaService.saveQuota(fresh),
                AGENT_QUOTA_DUPLICATE, "指定 Agent：order_cs");
        verify(quotaMapper, never()).insert(any(AgentQuotaDO.class));
    }

    @Test
    @DisplayName("编辑同一条配额不算重复（id 相同放行）")
    public void testSaveEditSameRowNotDuplicate() {
        AgentQuotaDO existing = agentQuota("order_cs", 1000L, null);
        existing.setId(7L);
        when(quotaMapper.selectAgentQuota(eq("order_cs"))).thenReturn(existing);
        AgentQuotaDO editing = agentQuota("order_cs", 2000L, null);
        editing.setId(7L); // 编辑同一条

        assertDoesNotThrow(() -> quotaService.saveQuota(editing));
        // 更新走 update(entity, wrapper) 而不是 updateById：理由见下面两条测试
        verify(quotaMapper).update(any(AgentQuotaDO.class), any());
    }

    @Test
    @DisplayName("🔴 USER 改成 TENANT 时 user_id 必须被显式清成 null —— 否则这条配额再也查不出来、等于不限流")
    public void testSaveDimensionSwitchClearsUserId() {
        // 库里这一行还挂着具体用户，管理员把它改成「整租户配额」
        AgentQuotaDO existing = tenantQuota(1000L, null);
        existing.setDimension("TENANT");
        existing.setUserId(USER_ID);
        when(quotaMapper.selectTenantQuota()).thenReturn(existing);
        AgentQuotaDO editing = tenantQuota(2000L, null);
        editing.setId(1L);
        editing.setDimension("TENANT");
        editing.setUserId(USER_ID); // 前端把原值带回来了，由 normalize 抹掉

        quotaService.saveQuota(editing);

        LambdaUpdateWrapper<AgentQuotaDO> wrapper = captureUpdateWrapper();
        // AgentQuotaMapper#selectTenantQuota 以 isNull(user_id) 为判据：
        // 这一列残留旧值，改完之后的配额就永远查不出来，而接口是返回成功的
        assertNull(sqlSetValue(wrapper, "user_id"));
        assertNull(sqlSetValue(wrapper, "target_key"));
    }

    @Test
    @DisplayName("🔴 上限改回「不限」必须真的写 null 进 SET —— 传了值的列不重复 set")
    public void testSaveClearsTokenLimit() {
        AgentQuotaDO existing = agentQuota("order_cs", 1000L, new BigDecimal("5.00"));
        existing.setId(7L);
        when(quotaMapper.selectAgentQuota(eq("order_cs"))).thenReturn(existing);
        AgentQuotaDO editing = agentQuota("order_cs", null, null); // 两个上限都取消
        editing.setId(7L);

        quotaService.saveQuota(editing);

        LambdaUpdateWrapper<AgentQuotaDO> wrapper = captureUpdateWrapper();
        assertNull(sqlSetValue(wrapper, "token_limit"));
        assertNull(sqlSetValue(wrapper, "cost_limit"));
        // warnRatio 这次传了值，由实体自己那一支进 SET；wrapper 再 set 一次会写出两遍同名列
        assertFalse(wrapper.getSqlSet().contains("warn_ratio="),
                "传了值的列不该出现在 wrapper 的 SET 里：" + wrapper.getSqlSet());
    }

    @Test
    @DisplayName("🔴 Agent/工作流配额缺 target（code）时拦下 —— 缺目标永远匹配不到、等于配了不生效")
    public void testSaveTargetRequired() {
        AgentQuotaDO quota = new AgentQuotaDO();
        quota.setDimension("AGENT");
        quota.setPeriod("MONTH");
        quota.setTokenLimit(1000L);
        quota.setWarnRatio(new BigDecimal("0.800"));
        quota.setStatus(CommonStatusEnum.ENABLE.getStatus());
        // 刻意不设 targetKey

        assertServiceException(() -> quotaService.saveQuota(quota),
                AGENT_QUOTA_TARGET_REQUIRED, "指定 Agent");
        verify(quotaMapper, never()).insert(any(AgentQuotaDO.class));
    }

    // ==================== 断言 wrapper 的 SET 子句 ====================

    /**
     * 取出 {@code saveQuota} 更新分支交给 mapper 的那个 wrapper
     */
    @SuppressWarnings("rawtypes")
    private LambdaUpdateWrapper<AgentQuotaDO> captureUpdateWrapper() {
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(quotaMapper).update(any(AgentQuotaDO.class), captor.capture());
        return captor.getValue();
    }

    /**
     * 从 wrapper 的 SET 子句里取出某一列绑定的值
     *
     * <p>MP 生成的 SET 形如 {@code user_id=#{ew.paramNameValuePairs.MPGENVAL1}}，
     * 列名与参数名的对应关系就写在这一串里，照着取即可 —— 不必真的跑一条 SQL。
     *
     * <p>这一列压根不在 SET 里时直接 fail：那正是 {@code updateById} 的旧行为
     * （实体上为 null 的列被整个从 SET 里省掉，SQL 语义是「别动这列」而不是「清成 null」），
     * 也是本次修复要保证不再发生的事。
     */
    private static Object sqlSetValue(LambdaUpdateWrapper<AgentQuotaDO> wrapper, String column) {
        Matcher matcher = Pattern.compile(column + "=#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}")
                .matcher(wrapper.getSqlSet());
        if (!matcher.find()) {
            fail(column + " 没出现在 SET 子句里，这一列的清空不会落库。SET = " + wrapper.getSqlSet());
        }
        return wrapper.getParamNameValuePairs().get(matcher.group(1));
    }

    // ==================== 造数据 ====================

    private AgentQuotaDO tenantQuota(Long tokenLimit, BigDecimal costLimit) {
        AgentQuotaDO quota = new AgentQuotaDO();
        quota.setId(1L);
        quota.setUserId(null);
        quota.setPeriod("MONTH");
        quota.setTokenLimit(tokenLimit);
        quota.setCostLimit(costLimit);
        quota.setWarnRatio(new BigDecimal("0.800"));
        quota.setStatus(CommonStatusEnum.ENABLE.getStatus());
        quota.setTenantId(TENANT_ID);
        return quota;
    }

    private AgentQuotaDO agentQuota(String code, Long tokenLimit, BigDecimal costLimit) {
        AgentQuotaDO quota = new AgentQuotaDO();
        quota.setDimension("AGENT");
        quota.setTargetKey(code);
        quota.setPeriod("MONTH");
        quota.setTokenLimit(tokenLimit);
        quota.setCostLimit(costLimit);
        quota.setWarnRatio(new BigDecimal("0.800"));
        quota.setStatus(CommonStatusEnum.ENABLE.getStatus());
        quota.setTenantId(TENANT_ID);
        return quota;
    }

    private AgentQuotaDO workflowQuota(String code, Long tokenLimit) {
        AgentQuotaDO quota = new AgentQuotaDO();
        quota.setDimension("WORKFLOW");
        quota.setTargetKey(code);
        quota.setPeriod("MONTH");
        quota.setTokenLimit(tokenLimit);
        quota.setWarnRatio(new BigDecimal("0.800"));
        quota.setStatus(CommonStatusEnum.ENABLE.getStatus());
        quota.setTenantId(TENANT_ID);
        return quota;
    }

    private void mockUsage(long tokens, BigDecimal cost) {
        lenient().when(usageLogMapper.selectUsageSum(any(), any(LocalDateTime.class)))
                .thenReturn(usageSum(tokens, cost));
    }

    private AgentUsageLogMapper.UsageSumBO usageSum(long tokens, BigDecimal cost) {
        AgentUsageLogMapper.UsageSumBO sum = new AgentUsageLogMapper.UsageSumBO();
        sum.setTotalTokens(tokens);
        sum.setTotalCost(cost);
        return sum;
    }

}
