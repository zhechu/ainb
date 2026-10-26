package cn.enlev.ainb.module.bpm.service.task;

import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.bpm.dal.dataobject.task.BpmInstanceParticipantDO;
import cn.enlev.ainb.module.bpm.dal.mysql.task.BpmInstanceParticipantMapper;
import cn.enlev.ainb.module.bpm.framework.bpm.config.BpmParticipantArchiveProperties;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流程参与表归档（S9 / v9 §8.3）
 *
 * <h3>🔴 这组用例守的最重要的不变量</h3>
 *
 * 归档会让用户看不到保留期之外的老单据（派生可见性从参与表反推）。
 * 这不是副作用，但它必须是一个有人做过的决定。三条因此而来的约束：
 *
 * <ol>
 *   <li>只归档已办结的行（{@code end_time} 非空）</li>
 *   <li>单行 {@code retain_until} 优先于全局保留期</li>
 *   <li>搬走不是删掉（归档表能搬回来）</li>
 * </ol>
 *
 * @author 走进新时代
 */
@Import({BpmInstanceParticipantArchiveService.class, BpmParticipantArchiveProperties.class})
public class BpmInstanceParticipantArchiveServiceTest extends BaseDbUnitTest {

    @Resource
    private BpmInstanceParticipantArchiveService archiveService;
    @Resource
    private BpmInstanceParticipantMapper participantMapper;
    @Resource
    private BpmParticipantArchiveProperties properties;
    @Resource
    private javax.sql.DataSource dataSource;

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpJdbc() {
        jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUpProperties() {
        properties.setEnabled(true);
        properties.setRetainDays(365);
        properties.setBatchSize(5000);
        properties.setMaxRowsPerRun(1_000_000);
    }

    @Test
    @DisplayName("🔴 已办结且超过保留期 → 搬走")
    void testArchiveExpiredFinished() {
        long id = insert("pi-1", 100L, ago(400));
        assertInMain(id);

        archiveService.archive();

        assertNotInMain(id);
        assertInArchive(id);
    }

    @Test
    @DisplayName("🔴 还在办（end_time 为空） → 永不归档，即使很老")
    void testNeverArchiveInProgress() {
        long id = insertInProgress("pi-in", 100L, ago(400));
        assertInMain(id);

        archiveService.archive();

        assertInMain(id);
        assertNotInArchive(id);
    }

    @Test
    @DisplayName("🔴 已办结但在保留期内 → 不归档")
    void testDoNotArchiveWithinRetentionPeriod() {
        long id = insert("pi-fresh", 100L, ago(100));
        assertInMain(id);

        archiveService.archive();

        assertInMain(id);
        assertNotInArchive(id);
    }

    @Test
    @DisplayName("🔴 retain_until 比全局保留期长 → 按 retain_until 来")
    void testRetainUntilOverridesGlobal() {
        // 全局保留 365 天，但这行设了 retain_until 明年 → 不该被归档
        long id = insertWithRetainUntil("pi-long", 100L, ago(400), future(365));
        assertInMain(id);

        archiveService.archive();

        assertInMain(id);
        assertNotInArchive(id);
    }

    @Test
    @DisplayName("retain_until 到期 → 按它归档（优先于 end_time 距今的时长）")
    void testRetainUntilExpired() {
        // end_time 才 10 天前（在全局保留期内），但 retain_until 已经到期
        long id = insertWithRetainUntil("pi-expired", 100L, ago(10), ago(1));
        assertInMain(id);

        archiveService.archive();

        assertNotInMain(id);
        assertInArchive(id);
    }

    @Test
    @DisplayName("归档是搬走不是删掉，而且可以搬回来")
    void testRestoreFromArchive() {
        long id = insert("pi-restore", 100L, ago(400));
        archiveService.archive();
        assertInArchive(id);

        archiveService.restore("pi-restore");

        assertInMain(id);
        assertNotInArchive(id);
    }

    @Test
    @DisplayName("搬回来之后可以再次归档 —— 幂等")
    void testRestoreAndArchiveAgain() {
        long id = insert("pi-loop", 100L, ago(400));
        archiveService.archive();
        archiveService.restore("pi-loop");

        archiveService.archive();

        assertNotInMain(id);
        assertInArchive(id);
    }

    @Test
    @DisplayName("enabled=false → 什么都不动")
    void testDisabledSkipsAll() {
        properties.setEnabled(false);
        long id = insert("pi-disabled", 100L, ago(400));

        int count = archiveService.archive();

        assertEquals(0, count);
        assertInMain(id);
    }

    @Test
    @DisplayName("archived_time 被写入 —— 排查「他为什么突然看不到」时第一个看的就是它")
    void testArchivedTimeIsSet() {
        long id = insert("pi-ts", 100L, ago(400));
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        archiveService.archive();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT archived_time FROM bpm_instance_participant_archive WHERE id = ?", id);
        assertFalse(rows.isEmpty());
        // archived_time 必须是这次归档时的时间，不是 create_time
        LocalDateTime archivedTime = ((java.sql.Timestamp) rows.get(0).get("archived_time"))
                .toLocalDateTime();
        assertTrue(archivedTime.isAfter(before), "archived_time 应当晚于测试开始时间");
    }

    @Test
    @DisplayName("INSERT IGNORE 幂等：上一轮搬了一半，重跑不报错")
    void testIdempotentRerun() {
        long id = insert("pi-idem", 100L, ago(400));
        archiveService.archiveBatch(LocalDateTime.now().minusDays(1), 1);
        // 再跑一次（主表已删、归档表已有）
        assertDoesNotThrow(() -> archiveService.archiveBatch(
                LocalDateTime.now().minusDays(1), 1));
        assertInArchive(id);
    }

    // ==================== helpers ====================

    private long insert(String pi, long userId, LocalDateTime endTime) {
        BpmInstanceParticipantDO d = new BpmInstanceParticipantDO();
        d.setProcessInstanceId(pi);
        d.setRootProcessInstanceId(pi);
        d.setUserId(userId);
        d.setParticipation(1);
        d.setStartTime(endTime.minusDays(1));
        d.setEndTime(endTime);
        participantMapper.insert(d);
        return d.getId();
    }

    private long insertInProgress(String pi, long userId, LocalDateTime startTime) {
        BpmInstanceParticipantDO d = new BpmInstanceParticipantDO();
        d.setProcessInstanceId(pi);
        d.setRootProcessInstanceId(pi);
        d.setUserId(userId);
        d.setParticipation(1);
        d.setStartTime(startTime);
        d.setEndTime(null); // 还在办
        participantMapper.insert(d);
        return d.getId();
    }

    private long insertWithRetainUntil(String pi, long userId, LocalDateTime endTime,
                                       LocalDateTime retainUntil) {
        BpmInstanceParticipantDO d = new BpmInstanceParticipantDO();
        d.setProcessInstanceId(pi);
        d.setRootProcessInstanceId(pi);
        d.setUserId(userId);
        d.setParticipation(1);
        d.setStartTime(endTime.minusDays(1));
        d.setEndTime(endTime);
        d.setRetainUntil(retainUntil);
        participantMapper.insert(d);
        return d.getId();
    }

    private LocalDateTime ago(int days) {
        return LocalDateTime.now().minusDays(days);
    }

    private LocalDateTime future(int days) {
        return LocalDateTime.now().plusDays(days);
    }

    private void assertInMain(long id) {
        assertFalse(jdbcTemplate.queryForList(
                "SELECT id FROM bpm_instance_participant WHERE id = ?", id).isEmpty(),
                id + " 应在主表");
    }

    private void assertNotInMain(long id) {
        assertTrue(jdbcTemplate.queryForList(
                "SELECT id FROM bpm_instance_participant WHERE id = ?", id).isEmpty(),
                id + " 不应在主表");
    }

    private void assertInArchive(long id) {
        assertFalse(jdbcTemplate.queryForList(
                "SELECT id FROM bpm_instance_participant_archive WHERE id = ?", id).isEmpty(),
                id + " 应在归档表");
    }

    private void assertNotInArchive(long id) {
        assertTrue(jdbcTemplate.queryForList(
                "SELECT id FROM bpm_instance_participant_archive WHERE id = ?", id).isEmpty(),
                id + " 不应在归档表");
    }

}
