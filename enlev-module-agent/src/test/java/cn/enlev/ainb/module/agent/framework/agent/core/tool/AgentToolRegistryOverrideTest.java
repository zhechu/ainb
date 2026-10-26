package cn.enlev.ainb.module.agent.framework.agent.core.tool;

import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentToolRegistry} 覆盖值（内置工具在线维护）单测
 *
 * <p>锁死四件事：① 未覆盖 / 无租户上下文时 getter 读的是代码声明值；② 某租户 refreshTenantOverrides 后，
 * 网关/确认风险/提示词读的生效值立即跟随（含「风险调低 → 不再要求确认」「权限删空 → 只需登录」两条产品决策放开的口子）；
 * ③ 🔴 <b>跨租户隔离</b>：租户 A 的覆盖绝不污染租户 B（这是本模块红线，也是把生效值按租户现算而非写死单例的原因）；
 * ④ 传空 map（恢复默认）能干净退回声明值，不留残值。
 *
 * @author 走进新时代
 */
public class AgentToolRegistryOverrideTest {

    @AgentTool(name = "override_demo", description = "代码默认说明",
            permission = "infra:data-model:query", extraPermissions = {"infra:data-model:update"},
            risk = RiskLevel.HIGH)
    static class DemoTool {
    }

    private static final Long TENANT_A = 1L;
    private static final Long TENANT_B = 2L;

    private AgentToolRegistry registry;

    @BeforeEach
    public void setUp() {
        registry = new AgentToolRegistry();
        registry.register(DemoTool.class);
        TenantContextHolder.setTenantId(TENANT_A);
    }

    @AfterEach
    public void tearDown() {
        TenantContextHolder.clear();
    }

    private AgentToolRegistry.ToolDefinition def() {
        return registry.get("override_demo");
    }

    private void refreshA(AgentToolRegistry.ToolOverride o) {
        registry.refreshTenantOverrides(TENANT_A, Map.of("override_demo", o));
    }

    @Test
    public void testDefaultEqualsDeclared() {
        assertEquals(List.of("infra:data-model:query", "infra:data-model:update"), def().getPermissions());
        assertEquals(RiskLevel.HIGH, def().getRisk());
        assertTrue(def().isEnabled());
        assertTrue(def().isRequireConfirm(), "HIGH 声明值要求确认");
        assertEquals("override_demo", def().getDisplayName(), "未覆盖展示名=工具名");
        assertEquals("代码默认说明", def().getDescription());
        assertFalse(def().isOverridden());
    }

    @Test
    public void testNoTenantContextFallsBackToDeclared() {
        // 先给租户 A 装一份放松覆盖
        refreshA(new AgentToolRegistry.ToolOverride(
                "维护版", "维护说明", "分组", RiskLevel.READ_ONLY, List.of(), false, "无参数"));
        assertTrue(def().isOverridden());
        // 清掉租户上下文（模拟启动/异步链路无租户）：必须回落声明值，绝不套用任何租户的覆盖
        TenantContextHolder.clear();
        assertFalse(def().isOverridden());
        assertEquals(RiskLevel.HIGH, def().getRisk());
        assertTrue(def().isEnabled());
        assertEquals("override_demo", def().getDisplayName());
    }

    @Test
    public void testOverrideTakesEffectImmediately() {
        refreshA(new AgentToolRegistry.ToolOverride(
                "数据模型维护版", "维护后的说明", "数据建模",
                RiskLevel.READ_ONLY, List.of("infra:data-model:query"), false, "无参数"));

        assertEquals("数据模型维护版", def().getDisplayName());
        assertEquals("维护后的说明", def().getDescription());
        assertEquals("数据建模", def().getGroupName());
        assertEquals(RiskLevel.READ_ONLY, def().getRisk());
        assertEquals(List.of("infra:data-model:query"), def().getPermissions(), "extraPermissions 被覆盖掉");
        assertFalse(def().isEnabled());
        assertFalse(def().isRequireConfirm(), "风险调低后不再要求确认（产品决策放开的口子）");
        assertEquals("无参数", def().getParamsSchema());
        assertTrue(def().isOverridden());
        // 声明值不受覆盖影响（管理页回显默认用）
        assertEquals(RiskLevel.HIGH, def().getDeclaredRisk());
        assertEquals(List.of("infra:data-model:query", "infra:data-model:update"), def().getDeclaredPermissions());
    }

    @Test
    public void testOverridePermissionsToEmptyMeansLoginOnly() {
        refreshA(new AgentToolRegistry.ToolOverride(null, null, null, null, List.of(), null, null));
        assertTrue(def().getPermissions().isEmpty(), "空列表=调用只需登录");
        assertTrue(def().isOverridden());
    }

    @Test
    public void testCrossTenantIsolation() {
        // 🔴 核心红线：租户 A 停用并降风险，租户 B 必须毫发无损地读到声明值
        refreshA(new AgentToolRegistry.ToolOverride(
                "A 的维护版", "A 的说明", "A 分组", RiskLevel.READ_ONLY, List.of(), false, "A 参数"));

        TenantContextHolder.setTenantId(TENANT_A);
        assertFalse(def().isEnabled(), "租户 A 看到自己被停用");
        assertEquals(RiskLevel.READ_ONLY, def().getRisk());

        TenantContextHolder.setTenantId(TENANT_B);
        assertTrue(def().isEnabled(), "租户 B 不受租户 A 覆盖影响，仍启用");
        assertEquals(RiskLevel.HIGH, def().getRisk(), "租户 B 风险仍是声明值");
        assertTrue(def().isRequireConfirm());
        assertEquals("override_demo", def().getDisplayName(), "租户 B 展示名仍是工具名");
        assertFalse(def().isOverridden(), "租户 B 从未维护过本工具");
    }

    @Test
    public void testRefreshWithEmptyMapResetsToDeclared() {
        refreshA(new AgentToolRegistry.ToolOverride(
                "x", "y", "z", RiskLevel.LOW, List.of(), false, "p"));
        assertTrue(def().isOverridden());
        // 恢复默认 = 传空 map（Service 删行后重建该租户覆盖得到空）
        registry.refreshTenantOverrides(TENANT_A, Map.of());
        assertFalse(def().isOverridden());
        assertEquals(RiskLevel.HIGH, def().getRisk());
        assertTrue(def().isEnabled());
        assertEquals("override_demo", def().getDisplayName());
    }

    @Test
    public void testOverridePointingToUnknownToolIsIgnored() {
        registry.refreshTenantOverrides(TENANT_A, Map.of("ghost_tool", new AgentToolRegistry.ToolOverride(
                "x", null, null, RiskLevel.LOW, null, null, null)));
        // 指向未注册工具的覆盖不得让任何已注册工具变成「维护中」
        assertFalse(def().isOverridden());
        assertEquals(RiskLevel.HIGH, def().getRisk());
    }

}
