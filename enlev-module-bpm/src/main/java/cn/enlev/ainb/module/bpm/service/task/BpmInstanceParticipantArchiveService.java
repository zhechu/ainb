package cn.enlev.ainb.module.bpm.service.task;

import cn.enlev.ainb.framework.datapermission.core.annotation.DataPermission;
import cn.enlev.ainb.module.bpm.framework.bpm.config.BpmParticipantArchiveProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 流程参与表的归档（S9 / v9 §8.3）
 *
 * <h3>为什么必须做</h3>
 *
 * <pre>
 * 日均 1000 单 × 5 节点 × 平均 2 人 ≈ 1 万行/天 ≈ 365 万行/年
 * </pre>
 *
 * v9 原话：「这一条不做，两年后查待办会慢到不可用。」
 *
 * <h3>🔴 归档会改变可见性</h3>
 *
 * 派生可见性（{@code BUILT_IN_BPM_PARTICIPANT}）是从参与表反推
 * 「他能看哪些业务行」的。所以一行参与记录被归档 =
 * <b>他从此看不到那条老单据了</b>。
 *
 * 这不是副作用，这正是「保留期」的含义 —— 但它必须是一个有人做过的决定。
 * 三条因此而来的设计：
 *
 * <ol>
 *   <li><b>只归档已办结的行</b>（{@code end_time} 非空）。还在办的参与记录永不归档 ——
 *       归档它等于把一个人手上的活从他眼前拿走</li>
 *   <li><b>单行可以用 {@code retain_until} 覆盖全局保留期</b>。
 *       合规要求「这类单据保留 10 年」时写这一列，
 *       而不是把全局保留期调到 10 年（那会让所有单据都留 10 年，等于没归档）</li>
 *   <li><b>搬走不是删掉</b>。归档表结构与主表一致，需要时能搬回来</li>
 * </ol>
 *
 * <h3>⚠️ 默认关闭</h3>
 *
 * {@code enlev.bpm.participant-archive.enabled=true} 才会跑。
 * 一个会让用户「看不到老单据」的动作，不该因为升级了一版就自动开始。
 *
 * <h3>为什么用 JdbcTemplate 而不是 MyBatis</h3>
 *
 * 归档是 {@code INSERT ... SELECT} + {@code DELETE}，两条都是集合操作。
 * 走 MyBatis 要先把几十万行查进内存再逐行写回 —— 那既慢又会把堆打满。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class BpmInstanceParticipantArchiveService {

    /**
     * 主表与归档表共有的列。两处必须完全一致，所以只写一份
     *
     * ⚠️ 加列时这里要跟着改。漏改的表现是「归档之后那一列的值没了」，
     * 而归档表几乎没人看，所以会很久之后才被发现
     */
    private static final String COLUMNS =
            "id, process_instance_id, root_process_instance_id, parent_process_instance_id, "
                    + "form_code, business_key, user_id, participation, task_id, activity_id, "
                    + "writable, start_time, end_time, retain_until, "
                    + "creator, create_time, updater, update_time, deleted, tenant_id";

    @Resource
    private javax.sql.DataSource dataSource;

    /**
     * 延迟初始化，避免在测试上下文里要求 JdbcTemplate 被预先注册为 Bean
     */
    private JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(dataSource);
    }
    @Resource
    private BpmParticipantArchiveProperties properties;

    /**
     * 归档一批到期的参与记录
     *
     * <h3>分批搬，每批一个事务</h3>
     *
     * 一次搬几百万行会把 undo 撑爆、把主表锁很久。分批之后即使中途失败，
     * 已搬走的那些也是完整的（INSERT 与 DELETE 在同一个事务里）。
     *
     * @return 本次归档的行数
     */
    public int archive() {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            return 0;
        }
        LocalDateTime deadline = LocalDateTime.now().minusDays(properties.getRetainDays());
        int total = 0;
        while (true) {
            int moved = archiveBatch(deadline, properties.getBatchSize());
            total += moved;
            if (moved < properties.getBatchSize()) {
                break;
            }
            if (total >= properties.getMaxRowsPerRun()) {
                // ⚠️ 单次上限只是「别把一晚上都占住」，不是「只归档这么多」——
                // 剩下的下次接着搬。这里必须记日志，否则会出现
                // 「跑了归档但表没怎么变小」而没人知道为什么
                log.warn("[archive][本次已达单次上限 {} 行，剩余的下次继续。"
                                + "若长期到顶说明保留期内的增量大于归档速度，要调大 max-rows-per-run]",
                        properties.getMaxRowsPerRun());
                break;
            }
        }
        if (total > 0) {
            log.info("[archive][参与表归档完成：搬走 {} 行，保留期 {} 天（截止 {}）]",
                    total, properties.getRetainDays(), deadline);
        }
        return total;
    }

    /**
     * 搬一批
     *
     * ⚠️ {@code @DataPermission(enable = false)}：归档是系统行为，
     * 不该被「当前登录人能看到哪些行」过滤 —— 定时任务里根本没有登录人，
     * 真过滤起来会一行都搬不动
     */
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false)
    public int archiveBatch(LocalDateTime deadline, int batchSize) {
        // 1. 先选出这一批的 id。
        //
        // ⚠️ 不能直接 INSERT...SELECT + DELETE 用同样的 WHERE ——
        // 两条语句之间数据可能变（有人刚办结了一条），
        // 于是 DELETE 会删掉 INSERT 没搬走的行。按 id 锁定才是一致的
        java.util.List<Long> ids = jdbcTemplate().queryForList(
                "SELECT id FROM bpm_instance_participant "
                        // 只归档【已办结】的：还在办的参与记录归档掉，
                        // 等于把人家手上的活从眼前拿走
                        + "WHERE end_time IS NOT NULL "
                        // retain_until 优先于全局保留期：配了就听它的，没配才看 end_time
                        + "AND (CASE WHEN retain_until IS NOT NULL "
                        + "          THEN retain_until <= NOW() "
                        + "          ELSE end_time <= ? END) "
                        + "ORDER BY id LIMIT ?",
                Long.class, deadline, batchSize);
        if (ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] params = ids.toArray();

        // 2. 搬过去。INSERT IGNORE：上一轮搬到一半失败重跑时，已经在归档表里的不算错
        jdbcTemplate().update(String.format(
                "INSERT IGNORE INTO bpm_instance_participant_archive (%s) "
                        + "SELECT %s FROM bpm_instance_participant WHERE id IN (%s)",
                COLUMNS, COLUMNS, placeholders), params);
        // 3. 从主表删掉
        return jdbcTemplate().update(String.format(
                "DELETE FROM bpm_instance_participant WHERE id IN (%s)", placeholders), params);
    }

    /**
     * 把某条流程实例的参与记录从归档表搬回主表
     *
     * <h3>为什么要有这个口子</h3>
     *
     * 归档是按时间一刀切的，而「这条单子要复查」是按业务来的 ——
     * 审计、纠纷、监管调档都会要求让某个人重新看到某条老单据。
     * 没有这个口子的话，唯一的办法是手工写 SQL，而那件事没人敢做。
     *
     * @return 搬回来的行数
     */
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false)
    public int restore(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            return 0;
        }
        jdbcTemplate().update(String.format(
                "INSERT IGNORE INTO bpm_instance_participant (%s) "
                        + "SELECT %s FROM bpm_instance_participant_archive "
                        + "WHERE process_instance_id = ?", COLUMNS, COLUMNS), processInstanceId);
        int restored = jdbcTemplate().update(
                "DELETE FROM bpm_instance_participant_archive WHERE process_instance_id = ?",
                processInstanceId);
        if (restored > 0) {
            log.info("[restore][流程实例({})的 {} 行参与记录已从归档表搬回]",
                    processInstanceId, restored);
        }
        return restored;
    }

}
