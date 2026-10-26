package cn.enlev.ainb.module.agent.framework.agent.core.gateway;

import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 执行上下文的单元测试
 *
 * <p>核心用例是「在预热线程池上重建」—— 真实服务实测中，未经处理的池化线程
 * 读到的 userId 与 tenantId <b>都是 null</b>（见 AgentDiagnosticController 类注释）。
 * 快照机制要解决的就是这一档。
 *
 * @author 走进新时代
 */
public class AgentExecutionContextTest {

    private LoginUser buildUser(Long id) {
        LoginUser user = new LoginUser();
        user.setId(id);
        user.setUserType(2);
        return user;
    }

    /**
     * 设置登录用户
     *
     * 不能用 SecurityFrameworkUtils.setLoginUser(user, null)：它内部走
     * WebAuthenticationDetailsSource.buildDetails(request)，传 null 会 NPE。
     * 这正是 AgentExecutionContext 要自己构造 Authentication 的原因。
     */
    private void setUser(Long id) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(buildUser(id), null, java.util.Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("快照捕获当前线程的身份")
    void testSnapshot() {
        TenantContextHolder.setTenantId(7L);
        setUser(66L);

        AgentExecutionContext ctx = AgentExecutionContext.snapshot();
        assertEquals(66L, ctx.getUserId());
        assertEquals(7L, ctx.getTenantId());
        assertTrue(ctx.isComplete());
    }

    @Test
    @DisplayName("身份不全时 isComplete 为 false —— 调用方据此 fail-close")
    void testIncomplete() {
        // 只有租户没有用户
        TenantContextHolder.setTenantId(7L);
        assertFalse(AgentExecutionContext.snapshot().isComplete());

        // 什么都没有
        TenantContextHolder.clear();
        assertFalse(AgentExecutionContext.snapshot().isComplete());
    }

    @Test
    @DisplayName("🔴 在预热线程池上重建：实测中这种线程读到的是 null，快照必须能补回来")
    void testRebuildOnPrestartedPool() throws Exception {
        // 线程池先于「有上下文」创建，因此继承不到任何东西 —— 与真实 HTTP 客户端 IO 线程同构
        ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        pool.prestartAllCoreThreads();
        try {
            // 先确认这个池确实是「干净」的，否则本用例证明不了任何事
            AtomicReference<Long> beforeTenant = new AtomicReference<>(-1L);
            AtomicReference<Long> beforeUser = new AtomicReference<>(-1L);
            pool.submit(() -> {
                beforeTenant.set(TenantContextHolder.getTenantId());
                beforeUser.set(SecurityFrameworkUtils.getLoginUserId());
            }).get(5, TimeUnit.SECONDS);
            assertNull(beforeTenant.get(), "前提不成立：池化线程本不该有租户上下文");
            assertNull(beforeUser.get(), "前提不成立：池化线程本不该有用户上下文");

            // 在「有上下文」的当前线程捕获快照
            TenantContextHolder.setTenantId(7L);
            setUser(66L);
            AgentExecutionContext ctx = AgentExecutionContext.snapshot();

            // 到池化线程上显式重建
            AtomicReference<Long> gotTenant = new AtomicReference<>();
            AtomicReference<Long> gotUser = new AtomicReference<>();
            pool.submit(() -> ctx.execute(() -> {
                gotTenant.set(TenantContextHolder.getTenantId());
                gotUser.set(SecurityFrameworkUtils.getLoginUserId());
            })).get(5, TimeUnit.SECONDS);

            assertEquals(7L, gotTenant.get(), "重建后应当能拿到租户");
            assertEquals(66L, gotUser.get(), "重建后应当能拿到用户");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("执行完要还原调用方原本的身份，不能把它抹掉")
    void testRestorePreviousIdentity() {
        // 当前线程本来是用户 1 / 租户 1
        TenantContextHolder.setTenantId(1L);
        setUser(1L);

        // 用另一个身份的快照执行
        AgentExecutionContext other = buildContext(2L, 99L);
        Long inner = other.execute(() -> SecurityFrameworkUtils.getLoginUserId());

        assertEquals(99L, inner, "执行期间应当是快照里的身份");
        assertEquals(1L, SecurityFrameworkUtils.getLoginUserId(), "执行完应当还原成调用方原本的身份");
        assertEquals(1L, TenantContextHolder.getTenantId());
    }

    @Test
    @DisplayName("调用方原本没有身份时，执行完要清干净，不留残留")
    void testClearWhenNoPreviousIdentity() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            AgentExecutionContext ctx = buildContext(7L, 66L);
            AtomicReference<Long> after = new AtomicReference<>(-1L);
            pool.submit(() -> {
                ctx.execute(() -> null);
                // 执行完，这个线程不该留下任何身份 —— 否则下一个任务会捡到别人的
                after.set(SecurityFrameworkUtils.getLoginUserId());
            }).get(5, TimeUnit.SECONDS);
            assertNull(after.get(), "执行完必须清干净，残留会让后续任务拿到别人的身份");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("有返回值与无返回值两种重载都可用")
    void testBothOverloads() {
        AgentExecutionContext ctx = buildContext(7L, 66L);
        assertEquals("ok", ctx.execute(() -> "ok"));

        AtomicReference<Long> got = new AtomicReference<>();
        ctx.execute(() -> got.set(TenantContextHolder.getTenantId()));
        assertEquals(7L, got.get());
    }

    @Test
    @DisplayName("🔴 异常类型不能被改写 —— TenantUtils.execute(Callable) 会包成 RuntimeException，本类刻意不用它")
    void testExceptionTypePreserved() {
        AgentExecutionContext ctx = buildContext(7L, 66L);
        // ServiceException 若被包装，全局异常处理器就认不出来，
        // 用户看到的会是「系统异常」而不是具体原因；AI 也拿不到可自修正的错误信息
        cn.enlev.ainb.framework.common.exception.ServiceException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        cn.enlev.ainb.framework.common.exception.ServiceException.class,
                        () -> ctx.execute(() -> {
                            throw new cn.enlev.ainb.framework.common.exception.ServiceException(403, "没权限");
                        }));
        assertEquals("没权限", ex.getMessage());

        IllegalStateException ise = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> ctx.execute(() -> { throw new IllegalStateException("业务失败"); }));
        assertEquals("业务失败", ise.getMessage());
    }

    @Test
    @DisplayName("异常发生时同样要还原上下文，不能留残留")
    void testContextRestoredOnException() {
        TenantContextHolder.setTenantId(1L);
        setUser(1L);
        AgentExecutionContext other = buildContext(7L, 66L);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> other.execute(() -> { throw new IllegalStateException("x"); }));
        assertEquals(1L, TenantContextHolder.getTenantId(), "异常后租户要还原");
        assertEquals(1L, SecurityFrameworkUtils.getLoginUserId(), "异常后用户要还原");
    }

    /** 造一个指定身份的快照：先设上下文、捕获、再清掉，避免影响后续断言 */
    private AgentExecutionContext buildContext(Long tenantId, Long userId) {
        Long prevTenant = TenantContextHolder.getTenantId();
        LoginUser prevUser = SecurityFrameworkUtils.getLoginUser();
        try {
            TenantContextHolder.setTenantId(tenantId);
            setUser(userId);
            return AgentExecutionContext.snapshot();
        } finally {
            if (prevTenant != null) {
                TenantContextHolder.setTenantId(prevTenant);
            } else {
                TenantContextHolder.clear();
            }
            if (prevUser != null) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(prevUser, null, java.util.Collections.emptyList()));
            } else {
                SecurityContextHolder.clearContext();
            }
        }
    }

}
