package cn.enlev.ainb.module.system.dal.redis.dict;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 字典缓存的 Redis DAO
 *
 * <h3>为什么字典要单独做一层缓存</h3>
 * 管理后台前端启动时会一次性拉取全部启用的字典数据，本地建成 Map 后供全站的 DictTag、
 * 下拉框同步取用。这个接口没有权限校验、每个登录用户都会打，而它背后是一次全表扫描加内存排序。
 * 字典是典型的「几乎不写、时刻在读」的数据，让每个用户的每次刷新都去查一遍库没有意义。
 *
 * <h3>版本号解决的是另一个问题</h3>
 * 前端原先给本地缓存设了 60 秒过期，到点就整包重拉。这带来两个都不理想的后果：
 * 字典明明没变也要反复传输整包；真改了字典又最多要等 60 秒才生效。
 * 版本号把「有没有变」和「变了什么」拆开——前端先花几十字节问一次版本，
 * 一致就直接用本地的，不一致才拉全量。
 *
 * <h3>三条保证正确性的约束</h3>
 * <ol>
 *   <li><b>版本号与数据存在同一个 KEY 里</b>（{@link DictDataCache}）。分成两个 KEY 存，
 *       就可能出现「新版本号配旧数据」并被前端永久固化，详见 {@link DictDataCache} 的注释。</li>
 *   <li><b>回源时先读版本号再查库</b>。查库期间若发生写入，这份数据带的是偏旧的版本号，
 *       前端下次比对会发现不一致并重新拉取——偏旧是安全方向，偏新则会导致永不刷新。</li>
 *   <li><b>命中缓存还要校验版本号是否仍是当前值</b>。并发写与回源交错时，缓存里可能残留
 *       一份「旧版本号 + 旧数据」；它本身是自洽的（前端能自愈），但没必要反复分发，
 *       比对一次就当作未命中重新回源。</li>
 * </ol>
 *
 * <h3>版本号只保证「不一致」，不保证「单调递增」</h3>
 * Redis 被清空后版本会从头开始，可能小于前端手上的值。因此前端必须用「不等于」判断，
 * 而不是「大于」——任一方向的不一致都应该触发一次全量拉取。
 *
 * @author 走进新时代
 */
@Repository
public class DictCacheRedisDAO {

    /**
     * 全量字典数据的缓存（含产生它的版本号）
     *
     * KEY 格式：system:dict:data_cache
     * VALUE 数据类型：String {@link DictDataCache} 的 JSON
     */
    private static final String DATA_CACHE_KEY = "system:dict:data_cache";

    /**
     * 字典版本号
     *
     * KEY 格式：system:dict:version
     * VALUE 数据类型：String 自增整数
     */
    private static final String VERSION_KEY = "system:dict:version";

    /**
     * 缓存与计数用两个 template：JSON 序列化会把数字写成带引号的字面量，
     * Redis 的 INCR 无法在其上做原子自增，所以版本号必须走 String 序列化。
     */
    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 获得缓存的字典数据
     *
     * @return 缓存条目；未命中时返回 null，由调用方负责回源
     */
    public DictDataCache getDataCache() {
        return (DictDataCache) redisTemplate.opsForValue().get(DATA_CACHE_KEY);
    }

    /**
     * 设置字典数据的缓存
     *
     * 不设置过期时间：缓存由写操作主动失效（见 {@link #clear()}），
     * 加 TTL 只会让缓存在没有任何变更时白白失效一次。
     *
     * @param cache 缓存条目，其 version 必须是查库「之前」读到的值
     */
    public void setDataCache(DictDataCache cache) {
        redisTemplate.opsForValue().set(DATA_CACHE_KEY, cache);
    }

    /**
     * 获得当前字典版本号
     *
     * 用 {@code INCRBY key 0} 而不是 {@code GET}：KEY 不存在时它会原子地创建并返回 0，
     * 省掉「先 GET、发现为空再初始化」这一段本身就存在并发窗口的逻辑。
     *
     * @return 版本号；从未发生过字典变更时为 0
     */
    public Long getVersion() {
        return stringRedisTemplate.opsForValue().increment(VERSION_KEY, 0L);
    }

    /**
     * 清空字典缓存，并递增版本号
     *
     * 任何会改变前端那份全量数据的写操作（字典数据的增删改、字典类型的增删改）都要调用它。
     * <p>
     * 先删缓存再自增版本：若两步之间进程崩溃，最坏结果是版本没变但缓存已空，
     * 下一次读会回源重建，数据仍是对的；反过来先自增则会出现「版本变了但缓存还是旧的」，
     * 而那份缓存携带的又是自增后的新版本号，前端拿到后不会再刷第二次。
     */
    public void clear() {
        redisTemplate.delete(DATA_CACHE_KEY);
        stringRedisTemplate.opsForValue().increment(VERSION_KEY);
    }

}
