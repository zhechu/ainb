package cn.enlev.ainb.module.infra.framework.formdesign.core.meta;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.service.db.DatabaseTableService;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 表结构元数据缓存的测试
 *
 * 这一层存在的唯一理由是性能：{@code DatabaseTableService.getTable} 每次调用都
 * 新建连接 + 用 Generator 解析整库结构（实测 50–80ms），而
 * {@code SqlIdentifierGuard#validateColumn} <b>每校验一列就调一次</b>。
 *
 * 所以用例盯的是三件「没做到就等于没加缓存」的事：
 *
 * <ol>
 *   <li>命中缓存时<b>不回源</b>—— 这是全部收益的来源</li>
 *   <li>并发下<b>不重复回源</b>—— 冷启动时一批请求同时进来，只能有 1 次真正的读取，
 *       否则连接池照样被打满，缓存等于白加</li>
 *   <li>回源失败<b>不进缓存</b>—— 缓存住异常的话，数据库恢复之后还要再等一个 TTL</li>
 * </ol>
 *
 * @author 走进新时代
 */
public class TableMetadataCacheTest extends BaseMockitoUnitTest {

    private static final String TABLE = "biz_order";

    @InjectMocks
    private TableMetadataCache cache;

    @Mock
    private DatabaseTableService databaseTableService;

    /**
     * 造一张有若干列的表
     *
     * ⚠️ 用 doReturn 而不是 when(...).thenReturn(...)：在 stream 的 lambda 里嵌套
     * when() 会让 Mockito 把「上一次 stubbing 还没结束」和「这一次开始」搞混，
     * 报 UnfinishedStubbing —— 而报错位置指向 lambda，看不出跟嵌套有关。
     * doReturn 形式不进入那套 ongoing-stubbing 状态机。
     */
    private TableInfo tableInfo(String... columns) {
        List<TableField> fields = new java.util.ArrayList<>();
        for (String name : columns) {
            TableField field = mock(TableField.class);
            doReturn(name).when(field).getName();
            fields.add(field);
        }
        TableInfo info = mock(TableInfo.class);
        doReturn(fields).when(info).getFields();
        return info;
    }

    @Test
    @DisplayName("第二次取命中缓存，不再回源")
    public void testCacheHit() {
        TableInfo info = mock(TableInfo.class);
        when(databaseTableService.getTable(0L, TABLE)).thenReturn(info);

        assertSame(info, cache.get(0L, TABLE));
        assertSame(info, cache.get(0L, TABLE));
        assertSame(info, cache.get(0L, TABLE));

        verify(databaseTableService, times(1)).getTable(0L, TABLE);
    }

    @Test
    @DisplayName("按 (dataSourceId, tableName) 分键 —— 只按表名分的话多数据源会串")
    public void testKeyIncludesDataSource() {
        TableInfo main = mock(TableInfo.class);
        TableInfo other = mock(TableInfo.class);
        when(databaseTableService.getTable(0L, TABLE)).thenReturn(main);
        when(databaseTableService.getTable(7L, TABLE)).thenReturn(other);

        assertSame(main, cache.get(0L, TABLE));
        assertSame(other, cache.get(7L, TABLE));
        // 同名表在两个库里结构可以完全不同，串了会让 SQL 拿着另一个库的列去拼
        assertSame(main, cache.get(0L, TABLE));
        assertSame(other, cache.get(7L, TABLE));
        verify(databaseTableService, times(1)).getTable(0L, TABLE);
        verify(databaseTableService, times(1)).getTable(7L, TABLE);
    }

    @Test
    @DisplayName("「表不存在」也要缓存 —— 否则一个写错的表名会让每次请求都读一遍整库")
    public void testNullIsCached() {
        when(databaseTableService.getTable(0L, "no_such")).thenReturn(null);

        assertNull(cache.get(0L, "no_such"));
        assertNull(cache.get(0L, "no_such"));

        verify(databaseTableService, times(1)).getTable(0L, "no_such");
    }

    @Test
    @DisplayName("⚠️ 回源失败不进缓存：数据库恢复后下一次调用要能立刻再试")
    public void testFailureNotCached() {
        when(databaseTableService.getTable(0L, TABLE))
                .thenThrow(new IllegalStateException("数据源连不上"))
                .thenReturn(mock(TableInfo.class));

        // 第一次把异常原样抛出去 —— 包一层 CompletionException 会让调用方的 catch 落空
        assertThrows(IllegalStateException.class, () -> cache.get(0L, TABLE));
        // 第二次不该还在吃上一次的失败
        assertTrue(cache.get(0L, TABLE) != null);
        verify(databaseTableService, times(2)).getTable(0L, TABLE);
    }

    @Test
    @DisplayName("invalidate 之后重新回源 —— 发布校验靠它保证看到的是最新结构")
    public void testInvalidate() {
        when(databaseTableService.getTable(0L, TABLE)).thenReturn(mock(TableInfo.class));

        cache.get(0L, TABLE);
        cache.invalidate(0L, TABLE);
        cache.get(0L, TABLE);

        verify(databaseTableService, times(2)).getTable(0L, TABLE);
    }

    @Test
    @DisplayName("⚠️ 并发下只回源一次 —— 做不到的话连接池照样被打满，缓存等于白加")
    public void testSingleFlightUnderConcurrency() throws Exception {
        int threads = 16;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        TableInfo info = mock(TableInfo.class);
        when(databaseTableService.getTable(any(), eq(TABLE))).thenAnswer(invocation -> {
            loads.incrementAndGet();
            // 模拟真实的 50–80ms：不睡的话第一个线程可能在别人进来之前就完成了，
            // 这条用例会变成「碰巧只回源一次」而不是「保证只回源一次」
            Thread.sleep(80);
            return info;
        });

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<TableInfo>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return cache.get(0L, TABLE);
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<TableInfo> future : futures) {
                assertSame(info, future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, loads.get(), "16 个线程同时要同一张表，只应该有 1 次真正的元数据读取");
    }

    @Test
    @DisplayName("columns() 按小写列名索引 —— 与发布校验的 buildColumnMap 一致")
    public void testColumnsLowerCased() {
        // ⚠️ 先把 TableInfo 造好再 stub。写成
        // when(service.getTable(...)).thenReturn(tableInfo("ID","Remark")) 的话，
        // tableInfo() 里的 stubbing 会在外层 when() 进行中执行，Mockito 报
        // UnfinishedStubbing，而报错位置指向 tableInfo 内部，看不出是嵌套导致的
        TableInfo info = tableInfo("ID", "Remark");
        when(databaseTableService.getTable(0L, TABLE)).thenReturn(info);

        Map<String, TableField> columns = cache.columns(0L, TABLE);

        // MySQL 在 Windows 上不区分大小写、在 Linux 上区分，而配置里的列名是人填的。
        // 两处索引方式不一致会出现「发布通过、运行时报列不存在」
        assertTrue(columns.containsKey("id"));
        assertTrue(columns.containsKey("remark"));
        assertEquals(2, columns.size());
    }

    @Test
    @DisplayName("表不存在时 columns() 返回空 Map，不抛异常")
    public void testColumnsOfMissingTable() {
        when(databaseTableService.getTable(0L, "no_such")).thenReturn(null);

        // 调用方（长度校验）据此「跳过」而不是拦住提交：表不存在由别的校验负责报错
        assertTrue(cache.columns(0L, "no_such").isEmpty());
    }

}
