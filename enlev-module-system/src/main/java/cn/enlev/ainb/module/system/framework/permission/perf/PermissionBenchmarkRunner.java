package cn.enlev.ainb.module.system.framework.permission.perf;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.bpm.BpmParticipantCommonApi;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.framework.permission.core.rule.ConditionNode;
import cn.enlev.ainb.module.system.framework.permission.core.rule.ConditionTreeEvaluator;
import cn.enlev.ainb.module.system.service.permission.GrantDecisionService;
import cn.enlev.ainb.module.system.service.permission.SubjectClosureService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import cn.enlev.ainb.module.system.dal.redis.RedisKeyConstants;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 权限体系压测（S9 / v9 §8.2 的五项门槛）
 *
 * <h3>为什么是 Runner 而不是脚本或接口</h3>
 *
 * 五项门槛量的都是<b>进程内单次调用</b>的耗时（主体闭包、祖先链、决策、条件树），
 * 从 HTTP 打进来量到的是「整条链路」，里面混着序列化、拦截器、网络，
 * 结论会被稀释到看不出是哪一层慢。
 *
 * 脚本（{@code script/perf/gen_perf_data.py}）负责造数，本类负责量 —— 分工清楚。
 *
 * <h3>⚠️ 默认关闭，且不该在生产打开</h3>
 *
 * {@code enlev.permission.benchmark-enabled=true} 才装配。它会：
 * 反复调用真实的判定逻辑（只读）、往日志里打一张表。
 * 生产打开的唯一后果是启动慢几秒 + 一堆日志，但没有理由那么做。
 *
 * <h3>怎么用</h3>
 *
 * <pre>
 * python script/perf/gen_perf_data.py --yes        # 造 5 万用户 / 5 万资源 / 365 万参与记录
 * mvn -pl enlev-server spring-boot:run -Dspring-boot.run.arguments=--yes[m]udao.permission.benchmark-enabled=true
 * python script/perf/gen_perf_data.py --clean --yes
 * </pre>
 *
 * @author 走进新时代
 */
@Component
@ConditionalOnProperty(prefix = "enlev.permission", name = "benchmark-enabled", havingValue = "true")
@Order(100) // 排在所有登记/迁移 Runner 之后，量的才是稳定状态
@Slf4j
public class PermissionBenchmarkRunner implements ApplicationRunner {

    /**
     * 每项测量的采样次数。取中位数与 P95，不取平均 ——
     * 平均会被第一次的冷启动（连接池、MyBatis 一级编译）拉偏，
     * 而那一次不代表稳态
     */
    private static final int SAMPLES = 200;
    /**
     * 预热次数。不预热的话量到的是 JIT 解释执行的耗时，比稳态慢一个量级
     */
    private static final int WARMUP = 20;

    @Resource
    private SubjectClosureService subjectClosureService;
    @Resource
    private GrantDecisionService grantDecisionService;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private ConditionTreeEvaluator conditionTreeEvaluator;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private ObjectProvider<BpmParticipantCommonApi> bpmParticipantApiProvider;
    @Resource
    private CacheManager cacheManager;

    @Override
    public void run(ApplicationArguments args) {
        // 压测跑在应用启动阶段，这时没有 HTTP 请求 → 没有租户上下文。
        // 用系统租户 1 跑 —— 与 gen_perf_data.py 造数时写的 tenant_id 对齐
        cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder.setTenantId(1L);
        try {
            runBenchmark();
        } finally {
            cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder.clear();
        }
    }

    private void runBenchmark() {
        log.info("[benchmark][===== 权限体系压测开始（v9 §8.2 五项门槛）=====]");
        Fixture fx = loadFixture();
        if (fx == null) {
            log.error("[benchmark][找不到压测数据，先跑 python script/perf/gen_perf_data.py --yes]");
            return;
        }
        log.info("[benchmark][样本：userId={}, 深层资源={}(depth={}), 参与表用户={}, 表单={}]",
                fx.userId, fx.deepPermission, fx.deepDepth, fx.participantUserId, fx.formCode);

        List<Result> results = new ArrayList<>();
        results.add(measure("主体闭包（首次，未命中缓存）", 5.0,
                () -> subjectClosureService.getSubjectKeysByUserId(fx.userId), true));
        results.add(measure("主体闭包（命中缓存）", 0.5,
                () -> subjectClosureService.getSubjectKeysByUserId(fx.userId), false));
        results.add(measure("资源祖先链（物化路径上溯）", 2.0,
                () -> grantDecisionService.resolveResourceChain(fx.deepPermission), false));
        results.add(measure("决策：单次鉴权（深层资源）", 10.0,
                () -> grantDecisionService.hasPermission(fx.userId, fx.deepPermission, null), false));
        results.add(measure("条件树 → SQL（5 组条件）", 3.0,
                () -> conditionTreeEvaluator.evaluate(fiveConditions(), fx.datasetResourceId,
                        variables(fx.userId), ""), false));
        results.add(measure("参与表：派生可见性查询", 100.0,
                () -> participatedKeys(fx), false));
        results.add(measure("参与表：待办分页（user+form+未办结）", 100.0,
                () -> todoPage(fx), false));

        report(results);
    }

    // ==================== 测量 ====================

    private Result measure(String name, double thresholdMs, Supplier<?> action, boolean coldEachTime) {
        try {
            for (int i = 0; i < WARMUP; i++) {
                action.get();
            }
            long[] nanos = new long[SAMPLES];
            for (int i = 0; i < SAMPLES; i++) {
                if (coldEachTime) {
                    // 「未命中缓存」那一项必须每次都清掉缓存，否则量到的是命中路径 ——
                    // 两项都量到 0.1ms 的话，这张表就没有任何意义了
                    evictSubjectCache();
                }
                long start = System.nanoTime();
                action.get();
                nanos[i] = System.nanoTime() - start;
            }
            java.util.Arrays.sort(nanos);
            double median = nanos[SAMPLES / 2] / 1_000_000.0;
            double p95 = nanos[(int) (SAMPLES * 0.95)] / 1_000_000.0;
            return new Result(name, thresholdMs, median, p95, null);
        } catch (Exception ex) {
            // 一项测不了不该把整轮压测带走 —— 剩下四项的数字同样有价值
            log.warn("[benchmark][{} 测量失败]", name, ex);
            return new Result(name, thresholdMs, -1, -1, ex.getClass().getSimpleName());
        }
    }

    /**
     * 清掉主体闭包缓存
     *
     * ⚠️ 走 {@link CacheManager} 而不是给 Service 加一个 evict 方法：
     * 生产代码里缓存失效已经由 {@code SubjectServiceImpl} 的 {@code @CacheEvict} 管好了，
     * 为压测再开一个公共失效入口，等于给业务代码留了一个「谁都能清全局缓存」的口子。
     *
     * 清不掉时退化成「量命中路径」，报告里那一行会与下一行一样 —— 看得出来，不会误导
     */
    private void evictSubjectCache() {
        Cache cache = cacheManager.getCache(RedisKeyConstants.SUBJECT_ANCESTORS);
        if (cache != null) {
            cache.clear();
        }
    }

    private void report(List<Result> results) {
        log.info("[benchmark][{}]", "-".repeat(78));
        log.info("[benchmark][{:<34} {:>10} {:>10} {:>10}  {}]".replace("{:", "%").replace("}", "s"),
                "项", "中位数", "P95", "门槛", "结论");
        StringBuilder sb = new StringBuilder("\n")
                .append(String.format("%-36s %10s %10s %10s  %s%n",
                        "项", "中位数(ms)", "P95(ms)", "门槛(ms)", "结论"));
        int failed = 0;
        for (Result r : results) {
            String verdict;
            if (r.error != null) {
                verdict = "测不了（" + r.error + "）";
            } else if (r.p95 <= r.thresholdMs) {
                verdict = "达标";
            } else {
                verdict = "🔴 超标";
                failed++;
            }
            sb.append(String.format("%-36s %10.3f %10.3f %10.1f  %s%n",
                    r.name, r.median, r.p95, r.thresholdMs, verdict));
        }
        sb.append(String.format("%n共 %d 项，超标 %d 项", results.size(), failed));
        log.info("[benchmark][压测结果：{}]", sb);
        log.info("[benchmark][===== 压测结束 =====]");
    }

    // ==================== 被测对象 ====================

    /**
     * 5 组条件的条件树。与 v9 §8.2 的「5 组条件」对齐
     */
    private ConditionNode fiveConditions() {
        ConditionNode group = new ConditionNode();
        group.setLogic(ConditionNode.LogicOperator.AND);
        List<ConditionNode> children = new ArrayList<>();
        children.add(cond("owner", "EQ", ":currentUserId"));
        children.add(cond("dept", "IN", ":currentDeptAndChildren"));
        children.add(cond("status", "EQ", "1"));
        children.add(cond("bizKey", "NE", "0"));
        children.add(cond("owner", "NE", "0"));
        group.setChildren(children);
        return group;
    }

    private ConditionNode cond(String slot, String op, String value) {
        ConditionNode node = new ConditionNode();
        node.setSlot(slot);
        node.setOperator(op);
        node.setValue(value);
        return node;
    }

    private Map<String, Object> variables(Long userId) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("currentUserId", userId);
        vars.put("currentDeptAndChildren", List.of(1L, 2L, 3L));
        return vars;
    }

    private Object participatedKeys(Fixture fx) {
        BpmParticipantCommonApi api = bpmParticipantApiProvider.getIfAvailable();
        return api == null ? null : api.getParticipatedBusinessKeys(fx.participantUserId, fx.formCode);
    }

    /**
     * 「待办分页」的形态：按 (user_id, form_code, end_time) 走 idx_user_form_end
     *
     * ⚠️ 直接用 JdbcTemplate 而不是走 BPM 的 Service —— 那边混着 Flowable 的查询，
     * 量到的不是参与表的代价
     */
    private Object todoPage(Fixture fx) {
        return jdbcTemplate.queryForList(
                "SELECT id, process_instance_id, business_key FROM bpm_instance_participant "
                        + "WHERE user_id = ? AND form_code = ? AND end_time IS NOT NULL "
                        + "ORDER BY start_time DESC LIMIT 20",
                fx.participantUserId, fx.formCode);
    }

    // ==================== 样本 ====================

    private Fixture loadFixture() {
        Fixture fx = new Fixture();
        // 深层资源：depth 最大的那个，祖先链才有东西可上溯
        List<Map<String, Object>> deep = jdbcTemplate.queryForList(
                "SELECT permission, depth FROM sys_resource WHERE deleted = 0 "
                        + "ORDER BY depth DESC, id ASC LIMIT 1");
        if (deep.isEmpty()) {
            return null;
        }
        fx.deepPermission = (String) deep.get(0).get("permission");
        fx.deepDepth = ((Number) deep.get(0).get("depth")).intValue();

        // 用户：取「system_users 里真实存在 + 角色最多」的那个 ——
        // 主体闭包要读 system_users 拿 dept_id 和 post_ids；
        // 压测用户只有 system_user_role 行，没有 system_users 行，闭包会报错
        List<Map<String, Object>> user = jdbcTemplate.queryForList(
                "SELECT ur.user_id, COUNT(*) c FROM system_user_role ur "
                        + "JOIN system_users u ON u.id = ur.user_id "
                        + "WHERE ur.deleted = 0 AND u.deleted = 0 "
                        + "GROUP BY ur.user_id ORDER BY c DESC LIMIT 1");
        fx.userId = user.isEmpty() ? 1L : ((Number) user.get(0).get("user_id")).longValue();

        // 参与表：挑记录最多的那个用户（长尾里的重度用户），量的才是最坏情况
        List<Map<String, Object>> p = jdbcTemplate.queryForList(
                "SELECT user_id, form_code, COUNT(*) c FROM bpm_instance_participant "
                        + "GROUP BY user_id, form_code ORDER BY c DESC LIMIT 1");
        if (!p.isEmpty()) {
            fx.participantUserId = ((Number) p.get(0).get("user_id")).longValue();
            fx.formCode = (String) p.get(0).get("form_code");
            fx.participantRows = ((Number) p.get(0).get("c")).intValue();
        }

        // 条件树求值要一个带槽位映射的数据集资源
        List<Map<String, Object>> ds = jdbcTemplate.queryForList(
                "SELECT r.id FROM sys_resource r JOIN sys_slot_mapping m ON m.resource_id = r.id "
                        + "WHERE r.deleted = 0 GROUP BY r.id ORDER BY COUNT(*) DESC LIMIT 1");
        fx.datasetResourceId = ds.isEmpty() ? null : ((Number) ds.get(0).get("id")).longValue();
        return fx;
    }

    private static class Fixture {
        Long userId;
        String deepPermission;
        int deepDepth;
        Long participantUserId = 1L;
        String formCode = "none";
        int participantRows;
        Long datasetResourceId;
    }

    private record Result(String name, double thresholdMs, double median, double p95, String error) {
    }

}
