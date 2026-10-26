package cn.enlev.ainb.module.infra.dal.redis.billtemplate;

import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.infra.dal.redis.RedisKeyConstants;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/**
 * 单据号的 Redis DAO
 *
 * <h3>为什么权威计数放 Redis 而不是数据库</h3>
 * 把当前流水存在模板表的某一列上、靠 UPDATE 递增，会让同一模板的所有并发请求
 * 都去抢这一行的行锁。出号是业务保存路径上的必经步骤，这里排队会直接拖慢整个表单提交。
 * Redis {@code INCR} 是单线程原子操作，天然无锁且不会重号。
 *
 * <h3>周期重置不需要定时任务</h3>
 * 周期标记直接编码在 key 里。跨天/跨月时 key 变成新的，{@code INCR} 从 1 开始，
 * 旧 key 靠 TTL 自然消亡。整个过程不依赖任何已生成号码的字符串形态，
 * 因此前后缀、日期格式怎么调整都不会影响重置行为。
 *
 * <h3>跳号是有意的取舍</h3>
 * 号在事务提交前就已消耗，若后续 SQL 失败导致事务回滚，该号即跳过。
 * 不做「回滚归还」—— 归还在并发下会导致两个请求拿到同一个号，重号的代价远大于跳号。
 * 这与项目里 ERP / CRM / Trade 既有的做法一致。
 *
 * @author 走进新时代
 */
@Repository
public class BillTemplateRedisDAO {

    /**
     * 预占的存活时长
     *
     * 30 分钟：够用户从容填完一张表单，又不至于让放弃填写的号长期占着。
     */
    public static final Duration OCCUPY_TTL = Duration.ofMinutes(30);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 取下一个流水序号
     *
     * @param scope  计数作用域
     * @param group  分组段
     * @param period 周期段
     * @param ttl    key 存活时长，null 表示永不过期（不重置周期时）
     * @return 从 1 开始的序号
     */
    public long nextSequence(String scope, String group, String period, Duration ttl) {
        String key = buildNoKey(scope, group, period);
        Long seq = stringRedisTemplate.opsForValue().increment(key);
        if (ttl != null) {
            // 每次都续期而不是只在首次创建时设置：若进程在 INCR 与 EXPIRE 之间崩溃，
            // 只设一次会留下永不过期的 key。重复续期的代价仅仅是 key 多活一个周期
            stringRedisTemplate.expire(key, ttl);
        }
        return seq != null ? seq : 1L;
    }

    /**
     * 读取当前流水序号，不消耗
     *
     * 供管理页展示「当前流水到了多少」。key 不存在时返回 0。
     */
    public long currentSequence(String scope, String group, String period) {
        String value = stringRedisTemplate.opsForValue().get(buildNoKey(scope, group, period));
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ==================== 预占 ====================

    /**
     * 读取当前用户已预占的号码，没有则返回 null
     */
    public String getOccupied(Long userId, String scope) {
        return stringRedisTemplate.opsForValue().get(buildOccupyKey(userId, scope));
    }

    /**
     * 预占号码
     */
    public void occupy(Long userId, String scope, String no) {
        stringRedisTemplate.opsForValue().set(buildOccupyKey(userId, scope), no, OCCUPY_TTL);
    }

    /**
     * 核销预占
     *
     * 提交成功后调用。只在预占值与提交上来的号码一致时才删除 ——
     * 否则用户在两个标签页各开一张表单时，先提交的那张会把后开的那张的预占抹掉，
     * 导致后者提交时拿不到预占、白白重新生成一个号。
     *
     * @return 是否真的核销了
     */
    public boolean release(Long userId, String scope, String no) {
        String key = buildOccupyKey(userId, scope);
        String occupied = stringRedisTemplate.opsForValue().get(key);
        if (occupied == null || !occupied.equals(no)) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.delete(key));
    }

    // ==================== key 拼装 ====================

    private String buildNoKey(String scope, String group, String period) {
        return RedisKeyConstants.BILL_TEMPLATE_NO + tenantId() + ":" + scope + ":" + group + ":" + period;
    }

    private String buildOccupyKey(Long userId, String scope) {
        return RedisKeyConstants.BILL_TEMPLATE_OCCUPY + tenantId() + ":" + userId + ":" + scope;
    }

    /**
     * 当前租户编号
     *
     * 单据模板已按租户隔离（{@code BillTemplateDO} 继承 TenantBaseDO），
     * 各租户的流水同样必须彼此独立 —— 否则 A 租户出了 001，B 租户就只能从 002 开始。
     * 无租户上下文（如定时任务）时归到 0。
     */
    private Object tenantId() {
        Long tenantId = TenantContextHolder.getTenantId();
        return tenantId != null ? tenantId : 0L;
    }

}
