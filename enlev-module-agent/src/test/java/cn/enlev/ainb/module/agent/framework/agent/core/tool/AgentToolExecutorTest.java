package cn.enlev.ainb.module.agent.framework.agent.core.tool;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentSecurityGateway;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具执行器的单元测试
 *
 * 守三条：<b>网关不过就绝不执行</b>、<b>执行时身份是显式重建的</b>、
 * <b>沙箱里的写工具只给占位结果、绝不动真数据</b>（含被 {@code agent_tool_override}
 * 压成只读的那种，D11）。
 * 这三条错了都不会报错，只会安静地越权、串租户，或在「沙箱测试」的名义下真建一张表。
 *
 * @author 走进新时代
 */
public class AgentToolExecutorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentToolExecutor executor;

    @Mock
    private AgentSecurityGateway gateway;
    @Mock
    private AgentToolDefService toolDefService;
    @Spy
    private AgentToolRegistry toolRegistry = new AgentToolRegistry();

    /** 记录工具是否被执行过，以及执行时读到的身份 */
    private final AtomicBoolean executed = new AtomicBoolean(false);
    private final AtomicReference<Long> seenTenant = new AtomicReference<>();
    private final AtomicReference<Long> seenUser = new AtomicReference<>();

    @AgentTool(name = "probe_tool", permission = "infra:data-model:query", risk = RiskLevel.READ_ONLY)
    class ProbeTool implements AgentToolFunction {
        @Override
        public Object execute(Map<String, Object> params) {
            executed.set(true);
            seenTenant.set(TenantContextHolder.getTenantId());
            seenUser.set(cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId());
            return Map.of("echo", params);
        }
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    private AgentExecutionContext contextOf(Long userId, Long tenantId) {
        LoginUser user = new LoginUser();
        user.setId(userId);
        user.setUserType(2);
        user.setTenantId(tenantId);
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

    @Test
    @DisplayName("网关放行后执行，参数原样传入")
    void testExecute() {
        toolRegistry.register(ProbeTool.class, new ProbeTool());
        Object result = executor.execute(contextOf(66L, 7L), "probe_tool", Map.of("k", "v"));
        assertEquals(Map.of("echo", Map.of("k", "v")), result);
    }

    @Test
    @DisplayName("🔴 网关拒绝时绝不执行工具 —— 校验必须在执行之前")
    void testGatewayRejectStopsExecution() {
        toolRegistry.register(ProbeTool.class, new ProbeTool());
        doThrow(new ServiceException(403, "没权限")).when(gateway).check(any(), anyString());

        assertThrows(ServiceException.class,
                () -> executor.execute(contextOf(66L, 7L), "probe_tool", Map.of()));
        assertFalse(executed.get(), "网关拒绝后工具绝不能被执行 —— 否则就是无声的越权");
    }

    @Test
    @DisplayName("🔴 在预热线程池上执行：身份靠快照显式重建，而不是碰运气")
    void testIdentityRebuiltOnPool() throws Exception {
        toolRegistry.register(ProbeTool.class, new ProbeTool());
        AgentExecutionContext context = contextOf(66L, 7L);

        ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        pool.prestartAllCoreThreads(); // 线程早于上下文创建，继承不到任何身份
        try {
            pool.submit(() -> executor.execute(context, "probe_tool", Map.of()))
                    .get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(7L, seenTenant.get(), "工具执行时应当能读到快照里的租户");
        assertEquals(66L, seenUser.get(), "工具执行时应当能读到快照里的用户");
    }

    @Test
    @DisplayName("🔴 网关校验本身也要跑在重建后的上下文里")
    void testGatewayChecksUnderRebuiltContext() throws Exception {
        // 网关自己要做两件依赖上下文的事：第 4 步查 agent_config（分租户表）、
        // 第 6 步 hasPermission 读 SecurityContext。
        // 早先的写法是在【当前线程】过网关、再重建上下文执行工具 ——
        // Servlet 线程上一直没事，从预热线程池上调用则工具还没跑就炸在网关里，
        // 报的还是「TenantContextHolder 不存在租户编号」这种跟真实原因无关的数据库异常。
        // 实测抓到过，见 script/verify-ai-permission/verify_stream_context.py。
        toolRegistry.register(ProbeTool.class, new ProbeTool());
        AtomicReference<Long> tenantAtCheck = new AtomicReference<>();
        AtomicReference<Long> userAtCheck = new AtomicReference<>();
        doAnswer(invocation -> {
            tenantAtCheck.set(TenantContextHolder.getTenantId());
            userAtCheck.set(SecurityFrameworkUtils.getLoginUserId());
            return null;
        }).when(gateway).check(any(), anyString());

        ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        pool.prestartAllCoreThreads();
        try {
            pool.submit(() -> executor.execute(contextOf(66L, 7L), "probe_tool", Map.of()))
                    .get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(7L, tenantAtCheck.get(), "网关校验时就该能读到租户，否则第 4 步查不了库");
        assertEquals(66L, userAtCheck.get(), "网关校验时就该能读到用户，否则第 6 步判定必然拒绝");
    }

    @Test
    @DisplayName("只有声明没有执行体的工具：报「不存在」而不是 NPE")
    void testDeclarationOnlyTool() {
        toolRegistry.register(ProbeTool.class); // 不给执行体
        assertThrows(ServiceException.class,
                () -> executor.execute(contextOf(66L, 7L), "probe_tool", Map.of()));
    }

    @Test
    @DisplayName("params 传 null 时按空 Map 处理，不 NPE")
    void testNullParams() {
        toolRegistry.register(ProbeTool.class, new ProbeTool());
        assertEquals(Map.of("echo", Map.of()),
                executor.execute(contextOf(66L, 7L), "probe_tool", null));
    }

    @Test
    @DisplayName("工具自身抛异常时原样向上抛 —— 失败信息是 AI 自修正的输入，不能吞")
    void testToolExceptionPropagates() {
        ExecutorService ignored = null;
        toolRegistry.register(FailingTool.class, new FailingTool());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> executor.execute(contextOf(66L, 7L), "failing_tool", Map.of()));
        assertEquals("工具内部失败", ex.getMessage());
    }

    // ==================== 回落：registry 查不到时用目录构造的执行体（增量5a） ====================

    @Test
    @DisplayName("🔴 registry 查不到时回落目录：buildFunction 产物被执行，结果原样透传")
    void testFallbackToCatalog() {
        // 刻意不在 registry 里注册 "catalog_http" —— registry.get 返回 null，触发回落到目录
        AtomicBoolean called = new AtomicBoolean(false);
        when(toolDefService.buildFunction("catalog_http")).thenReturn(params -> {
            called.set(true);
            return Map.of("http-result", params.get("id"));
        });

        Object result = executor.execute(contextOf(66L, 7L), "catalog_http", Map.of("id", "a-1"));

        assertTrue(called.get(), "回落后应当执行目录现场构造出来的执行体");
        assertEquals(Map.of("http-result", "a-1"), result);
        verify(toolDefService).buildFunction("catalog_http");
    }

    @Test
    @DisplayName("🔴 回落也发生在重建后的上下文里：目录工具同样读得到快照身份")
    void testFallbackRunsUnderRebuiltContext() throws Exception {
        // 回落到目录工具时，执行体仍在 context.execute 内跑 —— 身份必须靠快照重建，
        // 否则 HTTP 工具在预热线程池上会读不到租户/用户（和代码工具同一条纪律）
        AtomicReference<Long> tenantInFn = new AtomicReference<>();
        AtomicReference<Long> userInFn = new AtomicReference<>();
        when(toolDefService.buildFunction("catalog_http")).thenReturn(params -> {
            tenantInFn.set(TenantContextHolder.getTenantId());
            userInFn.set(SecurityFrameworkUtils.getLoginUserId());
            return "ok";
        });
        AgentExecutionContext context = contextOf(66L, 7L);

        ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        pool.prestartAllCoreThreads(); // 线程早于上下文创建，继承不到任何身份
        try {
            pool.submit(() -> executor.execute(context, "catalog_http", Map.of()))
                    .get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(7L, tenantInFn.get(), "回落到目录工具时，租户也应靠快照重建");
        assertEquals(66L, userInFn.get(), "回落到目录工具时，用户也应靠快照重建");
    }

    @Test
    @DisplayName("代码工具命中 registry 时不回落到目录 —— registry-first")
    void testCodeToolDoesNotConsultCatalog() {
        toolRegistry.register(ProbeTool.class, new ProbeTool());
        executor.execute(contextOf(66L, 7L), "probe_tool", Map.of("k", "v"));
        verify(toolDefService, never()).buildFunction(anyString());
    }

    @Test
    @DisplayName("🔴 registry 与目录都拿不到执行体 → 工具不存在，不 NPE")
    void testNeitherRegistryNorCatalog() {
        when(toolDefService.buildFunction("ghost_tool")).thenReturn(null);
        assertThrows(ServiceException.class,
                () -> executor.execute(contextOf(66L, 7L), "ghost_tool", Map.of()));
        verify(toolDefService).buildFunction("ghost_tool");
    }

    @AgentTool(name = "failing_tool", permission = "infra:data-model:query", risk = RiskLevel.READ_ONLY)
    static class FailingTool implements AgentToolFunction {
        @Override
        public Object execute(Map<String, Object> params) {
            throw new IllegalStateException("工具内部失败");
        }
    }

    // ==================== 🔴 沙箱 mock 卡点（P0-1 / D11）====================

    /** 沙箱里被 mock 掉的写工具是否<b>真的</b>被执行过 —— 本节的唯一判据 */
    private final AtomicBoolean writeExecuted = new AtomicBoolean(false);

    @AgentTool(name = "sandbox_write_tool", permission = "infra:data-model:create",
            risk = RiskLevel.IRREVERSIBLE)
    class SandboxWriteTool implements AgentToolFunction {
        @Override
        public Object execute(Map<String, Object> params) {
            writeExecuted.set(true);
            return Map.of("真的建了表", true);
        }
    }

    /**
     * 🔴 沙箱里写工具一律只给占位结果、绝不真执行（沙箱的全部承诺）。
     *
     * <p>这是 {@link #sandboxWriteToolDowngradedByOverride_stillMocked} 的<b>阳性对照</b>（§2-6）：
     * 没有它，那条用例即便「照样被 mock」也可能是因为压根没走进沙箱分支，
     * 于是永绿、什么也没证。这里先钉死「沙箱分支真的会拦下真执行」。
     */
    @Test
    @DisplayName("🔴 沙箱上下文里的写工具：返回占位结果、绝不调用 execute（阳性对照）")
    void sandboxMocksWriteTool() {
        toolRegistry.register(SandboxWriteTool.class, new SandboxWriteTool());

        Object result = executor.execute(contextOf(66L, 7L).withSandbox(),
                "sandbox_write_tool", Map.of("tableName", "t1"));

        assertFalse(writeExecuted.get(), "🔴 沙箱里真执行了写工具 —— 「不产生真实副作用」的承诺破了");
        assertTrue(result instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("__sandboxMock")),
                "应返回结构化占位结果，下游 {{$.var}} 引用才不会断链。实际：" + result);
    }

    /**
     * 🔴 沙箱里只读工具照样真执行 —— 上面那条的反面（§2-2 双向闸）。
     *
     * <p>没有它，把 {@code isNonReadOnly} 写成恒 {@code true}（「沙箱里什么都不执行」）
     * 也能让上面两条全绿，而那样沙箱测试就跑不出任何真实查询结果了。
     */
    @Test
    @DisplayName("🔴 沙箱上下文里的只读工具：照常真执行（双向闸的反面）")
    void sandboxStillExecutesReadOnlyTool() {
        toolRegistry.register(ProbeTool.class, new ProbeTool());

        Object result = executor.execute(contextOf(66L, 7L).withSandbox(),
                "probe_tool", Map.of("k", "v"));

        assertTrue(executed.get(), "只读工具在沙箱里被误 mock 了 —— 沙箱将查不到任何真实数据");
        assertEquals(Map.of("echo", Map.of("k", "v")), result);
    }

    /**
     * 🔴 P0-1 / D11：一行 {@code agent_tool_override} 把声明 IRREVERSIBLE 压成生效 READ_ONLY，
     * 沙箱照样必须 mock 掉它，<b>绝不能真去建表</b>。
     *
     * <p>{@code isNonReadOnly} 自己的 javadoc 写的就是「宁可多 mock，不可漏 mock 真跑出副作用」，
     * 可它原先读的是 {@code getRisk()}（租户<b>生效</b>值）—— 与自己那句注释正好相反。
     * {@code ToolOverride.risk} 的 javadoc 明写「允许低于声明值，产品决策」，
     * 而 {@code refreshTenantOverrides} → {@code sanitize} 只挡「指向未注册工具」的覆盖行，
     * <b>不挡向下调风险</b>；{@code AgentToolRegistryOverrideTest} 更是把「风险调低后不再要求确认」
     * 当成既定行为钉住了。所以这条路是通的、是产品刻意留的口子，不是假想。
     *
     * <p>后果比其他几道闸更硬：别的闸放行只是「多执行了一次写」，
     * 这里放行是<b>在用户以为「沙箱测试不会动真数据」的前提下真建了一张表</b> ——
     * 建表 IRREVERSIBLE，删不干净。而沙箱承诺不该由一行可在线维护的数据来决定。
     *
     * <p><b>🔴 变异探针</b>：把 {@code AgentToolExecutor#isNonReadOnly} 里的
     * {@code getDeclaredRisk()} 改回 {@code getRisk()}，本用例必须变红，
     * 且红在 {@code assertFalse(writeExecuted.get())} 那行（「沙箱真建表了」）。
     */
    @Test
    @DisplayName("🔴 被覆盖行压成只读的写工具：沙箱照样 mock，绝不真建表（D11）")
    void sandboxWriteToolDowngradedByOverride_stillMocked() {
        toolRegistry.register(SandboxWriteTool.class, new SandboxWriteTool());
        Long tenantId = 7L;
        toolRegistry.refreshTenantOverrides(tenantId, Map.of("sandbox_write_tool",
                new AgentToolRegistry.ToolOverride("建表（维护版）", "桩", "数据建模",
                        RiskLevel.READ_ONLY, java.util.List.of("infra:data-model:create"), true, "无参数")));
        // 🔴 阳性对照：先证明覆盖<b>真压下去了</b>，否则下面「照样 mock」证不了任何事。
        //    这里必须在租户上下文里读（生效值按当前租户现算）
        TenantContextHolder.setTenantId(tenantId);
        try {
            AgentToolRegistry.ToolDefinition def = toolRegistry.get("sandbox_write_tool");
            assertEquals(RiskLevel.READ_ONLY, def.getRisk(),
                    "阳性对照失效：覆盖行没把生效风险压成 READ_ONLY，本用例证不了任何事");
            assertFalse(def.isRequireConfirm(), "阳性对照失效：生效值仍要求确认");
            assertEquals(RiskLevel.IRREVERSIBLE, def.getDeclaredRisk(), "声明值必须搬不动");
        } finally {
            TenantContextHolder.clear();
        }

        Object result = executor.execute(contextOf(66L, tenantId).withSandbox(),
                "sandbox_write_tool", Map.of("tableName", "t1"));

        assertFalse(writeExecuted.get(),
                "🔴 覆盖行让沙箱真建表了 —— 沙箱「不产生真实副作用」的承诺不该由一行数据决定");
        assertTrue(result instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("__sandboxMock")),
                "应返回占位结果。实际：" + result);
    }

}
