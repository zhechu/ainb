package cn.enlev.ainb.module.infra.framework.formdesign.core.meta;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.service.db.DatabaseTableService;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;

/**
 * 表结构元数据缓存
 *
 * <h3>为什么必须有这一层</h3>
 *
 * {@link DatabaseTableService#getTable} 的实现（{@code DatabaseTableServiceImpl#getTableList0}）
 * 是<b>每次调用都新建一个数据源连接、用 MyBatis-Plus Generator 解析一遍表结构</b>，
 * 没有任何缓存。实测单次 50–80ms。
 *
 * 而运行时的调用频度远不止「每次请求一次」：{@code SqlIdentifierGuard#validateColumn}
 * <b>每校验一列就调一次</b>，一次 SELECT / INSERT 有多少个映射列就调多少次。
 * 实测一张只有 4 个字段的表单，一次运行时写入要 160–335ms，其中绝大部分耗在这里。
 * 字段越多越慢，且每次都新建连接 —— 并发提交时会把连接池打满。
 *
 * 所以这一层不是「为了新加的超长值校验」才加的优化，它修的是一个<b>已经存在</b>
 * 的问题；超长值校验只是又一个需要读列长度的调用方，没有它反而会把问题放大。
 *
 * <h3>三条要求（来自交接文档第十八节）</h3>
 *
 * <ol>
 *   <li>按 {@code (dataSourceId, tableName)} 缓存 —— 不是按表名，多数据源下会串</li>
 *   <li>有过期时间：DBA 改了表要能反映出来。见 {@link #TTL_MILLIS}</li>
 *   <li>并发下不重复回源：同一个键同时来 10 个请求，只能有 1 次真正的元数据读取</li>
 * </ol>
 *
 * <h3>为什么不直接在 DatabaseTableServiceImpl 上加缓存</h3>
 *
 * 那个服务的另一个调用方是代码生成器的「导入表」——用户点它的时候，
 * 期待的恰恰是<b>刚刚改过的</b>表结构。在那一层加缓存会让「改完表、点导入、
 * 结构还是旧的」，而用户只会以为自己改错了。
 *
 * <h3>为什么可以全局共享（不按租户分）</h3>
 *
 * 元数据是物理表的结构，与租户无关 —— 多租户是同库同表加 tenant_id 列。
 * 按租户分只会让同一份结构被读 N 遍。
 *
 * <h3>失败不进缓存</h3>
 *
 * 回源抛异常（数据源连不上）时把这一项移掉，下次调用重新试。缓存住异常的话，
 * 数据库恢复之后还要再等一个 TTL 才能用，而那时人已经在查「为什么还是连不上」。
 * ⚠️ 但<b>「表不存在」是要缓存的</b>：它是一个有效答案，不缓存的话一个写错的表名
 * 会让每次请求都去读一遍整库元数据 —— 那正是配置出错时最不该出现的放大。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class TableMetadataCache {

    /**
     * 缓存有效期
     *
     * 取值的权衡：这是「DBA 改表之后，运行时最久多长时间还在用旧结构」。
     *
     * <ul>
     *   <li>调长：运行时更快，但改表后的不一致窗口更长。表现是新加的列暂时用不了，
     *       或者刚放宽的 varchar 长度暂时还按旧上限拦 —— 都会自愈，只是要等</li>
     *   <li>调短：不一致窗口小，但每个窗口的第一次请求都要付 50–80ms</li>
     * </ul>
     *
     * 5 分钟是一个折中。真正需要「立刻生效」的场合是<b>发布</b>——
     * 那一步会先 {@link #invalidate} 再校验，看到的一定是最新结构，
     * 所以这个 TTL 不影响「发布时核实表与列真的存在」这条约束。
     */
    private static final long TTL_MILLIS = 5 * 60 * 1000L;

    @Resource
    private DatabaseTableService databaseTableService;

    /**
     * 缓存本体
     *
     * 值是 {@link Holder} 而不是 {@code TableInfo}：并发下第二个线程要能拿到
     * 「第一个线程正在读」这个状态并等它，而不是自己也去读一遍。
     */
    private final ConcurrentHashMap<Key, Holder> cache = new ConcurrentHashMap<>();

    /**
     * 取一张表的结构
     *
     * @return 表结构；表不存在时返回 null（与 {@link DatabaseTableService#getTable} 一致）
     */
    public TableInfo get(Long dataSourceId, String tableName) {
        if (StrUtil.isBlank(tableName)) {
            return null;
        }
        Key key = new Key(dataSourceId, tableName);
        long now = System.currentTimeMillis();

        // compute 里【只做判断和建对象】，不做真正的读取 ——
        // ConcurrentHashMap 的 compute 会锁住这个桶，把一次 50–80ms 的
        // 元数据读放进去，会连带卡住哈希到同一个桶的其它表
        Holder[] mine = new Holder[1];
        Holder holder = cache.compute(key, (k, existing) -> {
            if (existing != null && !existing.isExpired(now)) {
                return existing;
            }
            Holder fresh = new Holder(now + TTL_MILLIS);
            mine[0] = fresh;
            return fresh;
        });

        // 只有创建了这个 Holder 的那个线程去回源，其余线程在下面 join 上等它。
        // 这就是「并发下不重复回源」
        if (mine[0] == holder) {
            try {
                TableInfo loaded = databaseTableService.getTable(dataSourceId, tableName);
                holder.future.complete(loaded);
            } catch (RuntimeException e) {
                // 失败不留在缓存里，否则要等一个 TTL 才会再试
                cache.remove(key, holder);
                holder.future.completeExceptionally(e);
                throw e;
            }
        }
        try {
            return holder.future.join();
        } catch (CompletionException e) {
            // 等在别人那次失败的回源上。把原始异常抛出去，
            // 包一层 CompletionException 会让调用方的 catch 全部落空
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    /**
     * 取一张表的列，按列名（小写）索引
     *
     * 列名大小写：MySQL 在 Windows 上不区分、在 Linux 上区分，而配置里的列名是人填的。
     * 统一转小写比对，与 {@code FormDesignPublishValidator#buildColumnMap} 一致 ——
     * 两处不一致的话，会出现「发布通过、运行时报列不存在」。
     *
     * @return 列名（小写）-> 列。表不存在时返回空 Map
     */
    public Map<String, TableField> columns(Long dataSourceId, String tableName) {
        TableInfo table = get(dataSourceId, tableName);
        if (table == null || table.getFields() == null) {
            return Map.of();
        }
        Map<String, TableField> result = new LinkedHashMap<>();
        for (TableField field : table.getFields()) {
            if (field != null && StrUtil.isNotBlank(field.getName())) {
                result.put(field.getName().toLowerCase(), field);
            }
        }
        return result;
    }

    /**
     * 让一张表的缓存立刻失效
     *
     * 调用方目前只有发布校验：它的职责就是「真去核实表与列存在」，
     * 拿一份可能过期的结构去核实等于没核实 —— 那会让一次
     * 「DBA 刚加了列、用户马上发布」被误拒，或者反过来放过一个刚被删掉的列。
     */
    public void invalidate(Long dataSourceId, String tableName) {
        if (StrUtil.isNotBlank(tableName)) {
            cache.remove(new Key(dataSourceId, tableName));
        }
    }

    /**
     * 清空
     *
     * ⚠️ 只在测试里用。生产上清空会让接下来一批请求同时回源 ——
     * 单表有 {@link Holder} 挡着不会重复读，但 N 张表就是 N 次 50–80ms 一起来
     */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * 缓存键
     *
     * dataSourceId 允许为 null（历史配置里可能没填，服务层会当主库处理），
     * 所以用 {@link Objects#equals} 而不是拆箱比较。
     */
    private record Key(Long dataSourceId, String tableName) {
    }

    /**
     * 缓存项
     *
     * 用 CompletableFuture 而不是直接存 TableInfo：它同时表达了
     * 「已经有值」与「有人正在读，你等着」两种状态，后者是不重复回源的关键。
     */
    private static final class Holder {

        private final CompletableFuture<TableInfo> future = new CompletableFuture<>();
        private final long expireAt;

        private Holder(long expireAt) {
            this.expireAt = expireAt;
        }

        private boolean isExpired(long now) {
            return now >= expireAt;
        }

    }

}
