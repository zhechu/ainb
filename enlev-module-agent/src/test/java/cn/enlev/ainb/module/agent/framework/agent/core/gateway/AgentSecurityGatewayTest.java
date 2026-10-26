package cn.enlev.ainb.module.agent.framework.agent.core.gateway;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.framework.security.core.service.SecurityFrameworkService;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.agent.dal.dataobject.config.AgentConfigDO;
import cn.enlev.ainb.module.agent.dal.mysql.config.AgentConfigMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权限网关的单元测试
 *
 * 六步断言每一步都要有「拒绝」用例 —— 网关的价值全在拒绝上，
 * 只测「放行」等于没测。
 *
 * @author 走进新时代
 */
public class AgentSecurityGatewayTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentSecurityGateway gateway;

    @Mock
    private SecurityFrameworkService securityFrameworkService;
    @Mock
    private AgentConfigMapper agentConfigMapper;
    @Mock
    private AgentToolDefService toolDefService;
    @Spy
    private AgentToolRegistry toolRegistry = new AgentToolRegistry();

    @AgentTool(name = "demo_read", permission = "infra:data-model:query", risk = RiskLevel.READ_ONLY)
    static class DemoReadTool {
    }

    @AgentTool(name = "demo_disabled", permission = "infra:data-model:query",
            risk = RiskLevel.READ_ONLY, enabled = false)
    static class DemoDisabledTool {
    }

    @BeforeEach
    void setUp() {
        toolRegistry.register(DemoReadTool.class);
        toolRegistry.register(DemoDisabledTool.class);
        // 默认：开关开着、权限有。各用例按需覆盖
        lenient().when(agentConfigMapper.selectCurrent()).thenReturn(new AgentConfigDO());
        lenient().when(securityFrameworkService.hasPermission(anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    /** 造一份完整的上下文快照 */
    private AgentExecutionContext contextOf(Long userId, Long tenantId, Long visitTenantId) {
        LoginUser user = new LoginUser();
        user.setId(userId);
        user.setUserType(2);
        user.setTenantId(tenantId);
        user.setVisitTenantId(visitTenantId);
        try {
            TenantContextHolder.setTenantId(tenantId);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()));
            return AgentExecutionContext.snapshot();
        } finally {
            TenantContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private AgentExecutionContext validContext() {
        return contextOf(1L, 1L, null);
    }

    // ==================== 放行 ====================

    @Test
    @DisplayName("身份完整 + 开关已开 + 有权限：放行")
    void testPass() {
        assertDoesNotThrow(() -> gateway.check(validContext(), "demo_read"));
    }

    @Test
    @DisplayName("visitTenantId 与 tenantId 相同：放行（不是跨租户）")
    void testSameTenantPass() {
        assertDoesNotThrow(() -> gateway.check(contextOf(1L, 1L, 1L), "demo_read"));
    }

    // ==================== 第 1~3 步：身份 ====================

    @Test
    @DisplayName("🔴 第 1 步：拿不到 userId → 拒绝，不降级放行")
    void testNoUserId() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gateway.check(contextOf(null, 1L, null), "demo_read"));
        assertTrue(ex.getMessage().contains("身份"), ex.getMessage());
    }

    @Test
    @DisplayName("🔴 第 1 步：上下文为 null → 拒绝")
    void testNullContext() {
        assertThrows(ServiceException.class, () -> gateway.check(null, "demo_read"));
    }

    @Test
    @DisplayName("🔴 第 2 步：拿不到 tenantId → 拒绝")
    void testNoTenantId() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gateway.check(contextOf(1L, null, null), "demo_read"));
        assertTrue(ex.getMessage().contains("租户"), ex.getMessage());
    }

    @Test
    @DisplayName("🔴 第 3 步：跨租户状态 → 拒绝（此时功能权限与数据权限会被一起短路）")
    void testCrossTenantDenied() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gateway.check(contextOf(1L, 1L, 122L), "demo_read"));
        assertTrue(ex.getMessage().contains("122") && ex.getMessage().contains("跨租户"),
                ex.getMessage());
    }

    // ==================== 第 4 步：总开关 ====================

    @Test
    @DisplayName("🔴 第 4 步：租户没开启 AI 助手 → 拒绝")
    void testDisabled() {
        when(agentConfigMapper.selectCurrent()).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gateway.check(validContext(), "demo_read"));
        assertTrue(ex.getMessage().contains("尚未启用"), ex.getMessage());
    }

    // ==================== 第 5~6 步：工具授权 ====================

    @Test
    @DisplayName("🔴 第 5 步：工具不存在 → 拒绝（LLM 可能产出幻觉工具名）")
    void testToolNotFound() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gateway.check(validContext(), "不存在的工具"));
        assertTrue(ex.getMessage().contains("不存在的工具"), ex.getMessage());
    }

    @Test
    @DisplayName("🔴 第 5 步：工具被停用 → 拒绝")
    void testToolDisabled() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gateway.check(validContext(), "demo_disabled"));
        assertTrue(ex.getMessage().contains("已被停用"), ex.getMessage());
    }

    @Test
    @DisplayName("🔴 第 6 步：没有工具声明的权限 → 拒绝，且错误信息带出需要哪个权限")
    void testPermissionDenied() {
        when(securityFrameworkService.hasPermission("infra:data-model:query")).thenReturn(false);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gateway.check(validContext(), "demo_read"));
        assertTrue(ex.getMessage().contains("infra:data-model:query"), ex.getMessage());
    }

    // ==================== 回落：用户自定义工具目录（增量5a） ====================
    // registry 查不到工具名时，网关回落到 agent_tool_def 目录，镜像同一套「存在→启用→逐个权限 AND」校验。
    // 这几条守的是：回落分支的拒绝语义必须和代码工具一致，且代码工具命中时绝不碰目录。

    @Test
    @DisplayName("🔴 回落：目录工具已启用且有权限 → 放行")
    void testCatalogToolPass() {
        when(toolDefService.resolveForGateway("catalog_http"))
                .thenReturn(new AgentToolDefService.GatewayView(true, List.of("infra:data-model:query"), RiskLevel.READ_ONLY));
        assertDoesNotThrow(() -> gateway.check(validContext(), "catalog_http"));
    }

    @Test
    @DisplayName("🔴 回落：目录工具被停用 → 拒绝")
    void testCatalogToolDisabled() {
        when(toolDefService.resolveForGateway("catalog_http"))
                .thenReturn(new AgentToolDefService.GatewayView(false, List.of(), RiskLevel.HIGH));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gateway.check(validContext(), "catalog_http"));
        assertTrue(ex.getMessage().contains("已被停用"), ex.getMessage());
    }

    @Test
    @DisplayName("🔴 回落：目录工具缺权限 → 拒绝，错误信息带出所需权限")
    void testCatalogToolPermissionDenied() {
        when(toolDefService.resolveForGateway("catalog_http"))
                .thenReturn(new AgentToolDefService.GatewayView(true, List.of("agent:tool-def:invoke"), RiskLevel.READ_ONLY));
        when(securityFrameworkService.hasPermission("agent:tool-def:invoke")).thenReturn(false);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gateway.check(validContext(), "catalog_http"));
        assertTrue(ex.getMessage().contains("agent:tool-def:invoke"), ex.getMessage());
    }

    @Test
    @DisplayName("🔴 回落：registry 与目录都查不到 → 工具不存在（LLM 幻觉工具名走这条）")
    void testCatalogToolNotFound() {
        when(toolDefService.resolveForGateway("ghost_tool")).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gateway.check(validContext(), "ghost_tool"));
        assertTrue(ex.getMessage().contains("ghost_tool"), ex.getMessage());
    }

    @Test
    @DisplayName("代码工具命中 registry 时不回落目录 —— registry-first，代码工具永远优先")
    void testCodeToolDoesNotConsultCatalog() {
        gateway.check(validContext(), "demo_read"); // registry 命中，直接走原路径
        verify(toolDefService, never()).resolveForGateway(anyString());
    }

    // ==================== 顺序：先身份后其它 ====================

    @Test
    @DisplayName("身份校验排在最前 —— 没身份时不该先报「工具不存在」")
    void testIdentityCheckedFirst() {
        // 同时制造「没身份」与「工具不存在」两个问题，应当报身份那个
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gateway.check(contextOf(null, null, null), "不存在的工具"));
        assertTrue(ex.getMessage().contains("身份"),
                "应优先报身份问题，实际：" + ex.getMessage());
    }

    // ==================== 注册表本身 ====================

    @Test
    @DisplayName("工具重名直接抛错 —— 「哪个生效」不能取决于扫描顺序")
    void testDuplicateToolName() {
        AgentToolRegistry registry = new AgentToolRegistry();
        registry.register(DemoReadTool.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.register(DemoReadTool.class));
        assertTrue(ex.getMessage().contains("demo_read"), ex.getMessage());
    }

    @Test
    @DisplayName("风险等级决定是否要进 Plan 确认")
    void testRequireConfirm() {
        assertEquals(false, RiskLevel.READ_ONLY.isRequireConfirm(), "只读工具不必确认");
        for (RiskLevel level : new RiskLevel[]{RiskLevel.LOW, RiskLevel.MEDIUM,
                RiskLevel.HIGH, RiskLevel.IRREVERSIBLE}) {
            assertTrue(level.isRequireConfirm(), level + " 是写操作，必须进 Plan 等确认");
        }
    }

}
