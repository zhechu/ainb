package cn.enlev.ainb.module.infra.dal.redis.billtemplate;

import cn.enlev.ainb.framework.test.core.ut.BaseRedisUnitTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BillTemplateRedisDAO} 的单元测试
 *
 * 这是整个功能唯一「不能出错」的地方：序号重复 = 单号重复 = 业务数据撞车。
 * 因此除了常规断言，还专门做了并发验证 —— 单线程测试无法暴露计数器的竞态。
 *
 * @author 走进新时代
 */
@Import(BillTemplateRedisDAO.class)
public class BillTemplateRedisDAOTest extends BaseRedisUnitTest {

    private static final String GROUP = "_";
    private static final Duration TTL = Duration.ofDays(2);

    @Resource
    private BillTemplateRedisDAO billTemplateRedisDAO;

    // ==================== 流水计数 ====================

    @Test
    @DisplayName("序号从 1 开始逐次递增")
    void testNextSequence() {
        String scope = "seq_basic";

        assertEquals(1L, billTemplateRedisDAO.nextSequence(scope, GROUP, "20260906", TTL));
        assertEquals(2L, billTemplateRedisDAO.nextSequence(scope, GROUP, "20260906", TTL));
        assertEquals(3L, billTemplateRedisDAO.nextSequence(scope, GROUP, "20260906", TTL));
    }

    @Test
    @DisplayName("不同周期段各自独立计数 —— 这就是「跨天归 1」的全部实现")
    void testSequenceIsolatedByPeriod() {
        String scope = "seq_period";

        assertEquals(1L, billTemplateRedisDAO.nextSequence(scope, GROUP, "20260906", TTL));
        assertEquals(2L, billTemplateRedisDAO.nextSequence(scope, GROUP, "20260906", TTL));
        // 次日：换了 period 段，计数器是新的
        assertEquals(1L, billTemplateRedisDAO.nextSequence(scope, GROUP, "20260907", TTL));
    }

    @Test
    @DisplayName("⚠️ 不同分组段各自独立计数 —— 内联模式「每个客户各自从 1 开始」靠它实现")
    void testSequenceIsolatedByGroup() {
        String scope = "seq_group";

        assertEquals(1L, billTemplateRedisDAO.nextSequence(scope, "aaaaaaaa", "20260906", TTL));
        assertEquals(2L, billTemplateRedisDAO.nextSequence(scope, "aaaaaaaa", "20260906", TTL));
        // 换一个前缀分组，重新从 1 开始
        assertEquals(1L, billTemplateRedisDAO.nextSequence(scope, "bbbbbbbb", "20260906", TTL));
    }

    @Test
    @DisplayName("不同作用域各自独立计数")
    void testSequenceIsolatedByScope() {
        assertEquals(1L, billTemplateRedisDAO.nextSequence("scope_a", GROUP, "20260906", TTL));
        assertEquals(1L, billTemplateRedisDAO.nextSequence("scope_b", GROUP, "20260906", TTL));
    }

    @Test
    @DisplayName("TTL 为 null 时不设过期，计数器长期保留")
    void testNextSequenceWithoutTtl() {
        String scope = "seq_no_ttl";

        assertEquals(1L, billTemplateRedisDAO.nextSequence(scope, GROUP, "_", null));
        assertEquals(2L, billTemplateRedisDAO.nextSequence(scope, GROUP, "_", null));
    }

    @Test
    @DisplayName("currentSequence 只读不消耗，key 不存在时返回 0")
    void testCurrentSequence() {
        String scope = "seq_current";

        assertEquals(0L, billTemplateRedisDAO.currentSequence(scope, GROUP, "20260906"));

        billTemplateRedisDAO.nextSequence(scope, GROUP, "20260906", TTL);
        billTemplateRedisDAO.nextSequence(scope, GROUP, "20260906", TTL);

        assertEquals(2L, billTemplateRedisDAO.currentSequence(scope, GROUP, "20260906"));
        // 再读一次仍是 2，证明确实没有消耗
        assertEquals(2L, billTemplateRedisDAO.currentSequence(scope, GROUP, "20260906"));
    }

    // ==================== 并发 ====================

    @Test
    @DisplayName("⚠️ 并发取号不重号：200 个线程各取一次，结果恰好是 1..200")
    void testNextSequenceConcurrently() throws Exception {
        String scope = "seq_concurrent";
        int threads = 200;

        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            // 用栅栏让所有任务尽量同时开跑，否则线程池会把它们排成近似串行，测不出竞态
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return billTemplateRedisDAO.nextSequence(scope, GROUP, "20260906", TTL);
                    }))
                    .collect(Collectors.toList());
            start.countDown();

            Set<Long> results = new HashSet<>();
            for (Future<Long> future : futures) {
                assertTrue(results.add(future.get(30, TimeUnit.SECONDS)), "出现了重复序号");
            }

            assertEquals(threads, results.size());
            assertEquals(1L, Collections.min(results));
            assertEquals((long) threads, Collections.max(results));
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 预占 ====================

    @Test
    @DisplayName("未预占时读取返回 null")
    void testGetOccupiedWhenAbsent() {
        assertNull(billTemplateRedisDAO.getOccupied(1L, "occupy_absent"));
    }

    @Test
    @DisplayName("预占后可读回同一个号")
    void testOccupy() {
        billTemplateRedisDAO.occupy(1L, "occupy_basic", "CG202609060001");

        assertEquals("CG202609060001", billTemplateRedisDAO.getOccupied(1L, "occupy_basic"));
    }

    @Test
    @DisplayName("预占按用户隔离：A 的预占不影响 B")
    void testOccupyIsolatedByUser() {
        billTemplateRedisDAO.occupy(1L, "occupy_user", "CG202609060001");

        assertEquals("CG202609060001", billTemplateRedisDAO.getOccupied(1L, "occupy_user"));
        assertNull(billTemplateRedisDAO.getOccupied(2L, "occupy_user"));
    }

    @Test
    @DisplayName("核销后预占消失")
    void testRelease() {
        billTemplateRedisDAO.occupy(1L, "occupy_release", "CG202609060001");

        assertTrue(billTemplateRedisDAO.release(1L, "occupy_release", "CG202609060001"));
        assertNull(billTemplateRedisDAO.getOccupied(1L, "occupy_release"));
    }

    @Test
    @DisplayName("⚠️ 号码不匹配时不核销 —— 否则多标签页场景下会误删他人的预占")
    void testReleaseWithMismatchedNumber() {
        billTemplateRedisDAO.occupy(1L, "occupy_mismatch", "CG202609060001");

        assertFalse(billTemplateRedisDAO.release(1L, "occupy_mismatch", "CG202609060099"));
        // 预占必须原样保留
        assertEquals("CG202609060001", billTemplateRedisDAO.getOccupied(1L, "occupy_mismatch"));
    }

    @Test
    @DisplayName("重复核销返回 false，不抛异常")
    void testReleaseTwice() {
        billTemplateRedisDAO.occupy(1L, "occupy_twice", "CG202609060001");

        assertTrue(billTemplateRedisDAO.release(1L, "occupy_twice", "CG202609060001"));
        assertFalse(billTemplateRedisDAO.release(1L, "occupy_twice", "CG202609060001"));
    }

}
