package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RecordShareDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.RecordShareMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 记录共享（S7b-2）
 *
 * <h3>它与 sys_grant 的分工，以及为什么不能混</h3>
 *
 * <pre>
 * sys_grant         「谁能对这【类】对象做什么」 —— 按属性、按规则圈一批行
 * sys_record_share  「谁能看这【条】记录」       —— 点名放行，逐条
 * </pre>
 *
 * 混在一起（一条共享 = 一行资源 + 一行授权）会让资源表随共享次数爆炸，
 * 而且资源树被一堆「某条具体记录」的叶子淹没，授权界面没法看（v9 §5.1）。
 *
 * <h3>这组用例守的两条</h3>
 *
 * <ol>
 *   <li><b>幂等</b> —— 重复分享只更新，不堆同义行。堆了的话「取消共享」删哪一行就成了问题</li>
 *   <li><b>过期口径与 sys_grant 完全一致</b> —— 两处对「过期」的理解不一样，
 *       会出现「授权过期了但共享还在」这种没人能解释的状态</li>
 * </ol>
 *
 * @author 走进新时代
 */
@Import({RecordShareService.class})
public class RecordShareServiceTest extends BaseDbUnitTest {

    private static final String DATASET = "test_leave";
    private static final String RECORD = "1001";

    @Resource
    private RecordShareService recordShareService;
    @Resource
    private RecordShareMapper recordShareMapper;
    @Resource
    private ResourceMapper resourceMapper;

    /**
     * 归属人解析器要读真实业务表，单测里没有那张表 —— mock 掉。
     * 它自己的行为（拼 SQL、判标识符）由 RecordOwnerResolver 的用例覆盖
     */
    @MockitoBean
    private RecordOwnerResolver recordOwnerResolver;

    // ==================== 发起权：谁能分享 ====================

    /**
     * 造一个数据集资源 + 槽位映射，让 checkShareable 有东西可读
     */
    private Long givenDataset(String sharePolicy) {
        ResourceDO dataset = new ResourceDO();
        dataset.setPermission("infra:form-design-run:" + DATASET);
        dataset.setName("请假单");
        dataset.setType(ResourceTypeEnum.DATASET.getCode());
        dataset.setParentId(ResourceDO.ID_ROOT);
        dataset.setSort(0);
        dataset.setDefaultEffect("ALLOW");
        dataset.setStatus(0);
        dataset.setDatasetTable("test_leave");
        dataset.setSharePolicy(sharePolicy);
        resourceMapper.insert(dataset);
        return dataset.getId();
    }

    @Test
    @DisplayName("🔴 默认档：不是归属人就不能分享")
    void testOwnerOnlyByDefault() {
        givenDataset(null); // NULL = 默认 OWNER_ONLY
        // 归属人解析器 mock 成「这条记录属于 250」
        when(recordOwnerResolver.resolveOwnerUserId(any(), eq(RECORD))).thenReturn(250L);

        assertNull(recordShareService.checkShareable(250L, DATASET, RECORD), "归属人自己可以分享");
        assertNotNull(recordShareService.checkShareable(999L, DATASET, RECORD), "别人不行");
    }

    @Test
    @DisplayName("🔴 判不出归属人 → 拒绝，不是放行")
    void testUnknownOwnerDenies() {
        givenDataset(null);
        when(recordOwnerResolver.resolveOwnerUserId(any(), any())).thenReturn(null);

        String reason = recordShareService.checkShareable(250L, DATASET, RECORD);
        assertNotNull(reason, "判不出归属人时放行，等于让没配好槽位的数据集悄悄变成「人人可分享」");
        assertTrue(reason.contains("归属人"), reason);
    }

    @Test
    @DisplayName("ANY_VIEWER：不校验归属，但仍要求看得到")
    void testAnyViewerChecksVisibilityNotOwnership() {
        givenDataset("ANY_VIEWER");
        when(recordOwnerResolver.canSeeRecord(eq(999L), any(), eq(RECORD))).thenReturn(true);

        assertNull(recordShareService.checkShareable(999L, DATASET, RECORD));
        verify(recordOwnerResolver, never()).resolveOwnerUserId(any(), any());
    }

    @Test
    @DisplayName("🔴 ANY_VIEWER 下看不到这条的人也不能分享 —— 否则这一档的名字就是假的")
    void testAnyViewerDeniesNonViewer() {
        givenDataset("ANY_VIEWER");
        when(recordOwnerResolver.canSeeRecord(any(), any(), any())).thenReturn(false);

        assertNotNull(recordShareService.checkShareable(999L, DATASET, RECORD),
                "不校验可见性的话，「可见者均可分享」实际是「任何人都能分享」");
    }

    @Test
    @DisplayName("DISABLED：一律禁止")
    void testDisabledForbids() {
        givenDataset("DISABLED");
        when(recordOwnerResolver.resolveOwnerUserId(any(), any())).thenReturn(250L);

        assertNotNull(recordShareService.checkShareable(250L, DATASET, RECORD),
                "禁用档连归属人自己也不能分享");
    }

    @Test
    @DisplayName("⚠️ 数据集没登记 → 拒绝。没登记的数据集恰恰是最不该随便扩散的")
    void testUnregisteredDatasetDenies() {
        assertNotNull(recordShareService.checkShareable(250L, "never_registered", RECORD));
    }

    @Test
    @DisplayName("⚠️ 库里出现不认识的 share_policy → 退回最保守档，不能静默放开")
    void testUnknownPolicyFallsBackToStrictest() {
        givenDataset("SOMETHING_WEIRD");
        when(recordOwnerResolver.resolveOwnerUserId(any(), any())).thenReturn(250L);

        assertNull(recordShareService.checkShareable(250L, DATASET, RECORD));
        assertNotNull(recordShareService.checkShareable(999L, DATASET, RECORD),
                "认不出来的配置值退化成 ANY_VIEWER 的话，就是一次静默放开");
    }

    @Test
    @DisplayName("未登录不能分享")
    void testAnonymousCannotShare() {
        givenDataset(null);
        assertNotNull(recordShareService.checkShareable(null, DATASET, RECORD));
    }

    // ==================== 共享本身 ====================

    @Test
    @DisplayName("分享给用户后，该用户的主体闭包能查到这条记录")
    void testShareToUser() {
        recordShareService.shareToUser(DATASET, RECORD, 250L, null, "转给你看一下");

        List<RecordShareDO> list = recordShareMapper.selectListBySubjects(
                DATASET, Set.of(SubjectKey.ofUser(250L)));
        assertEquals(1, list.size());
        assertEquals(RECORD, list.get(0).getRecordId());
        assertEquals("转给你看一下", list.get(0).getRemark());
    }

    @Test
    @DisplayName("没分享给他的人查不到")
    void testOtherSubjectSeesNothing() {
        recordShareService.shareToUser(DATASET, RECORD, 250L, null, null);

        assertTrue(recordShareMapper.selectListBySubjects(
                DATASET, Set.of(SubjectKey.ofUser(999L))).isEmpty());
    }

    @Test
    @DisplayName("分享给角色 → 该角色在闭包里的人都查得到（主体统一寻址的意义）")
    void testShareToRole() {
        recordShareService.share(DATASET, RECORD, SubjectKey.ofRole(2L), null, null);

        assertEquals(1, recordShareMapper.selectListBySubjects(
                DATASET, Set.of(SubjectKey.ofUser(250L), SubjectKey.ofRole(2L))).size());
        // 没有那个角色的人查不到
        assertTrue(recordShareMapper.selectListBySubjects(
                DATASET, Set.of(SubjectKey.ofUser(250L))).isEmpty());
    }

    @Test
    @DisplayName("🔴 幂等：重复分享只更新，不堆同义行 —— 堆了的话「取消共享」删哪一行就成了问题")
    void testShareIsIdempotent() {
        Long first = recordShareService.shareToUser(DATASET, RECORD, 250L, null, "第一次");
        Long second = recordShareService.shareToUser(DATASET, RECORD, 250L, null, "改了说明");

        assertEquals(first, second);
        List<RecordShareDO> list = recordShareMapper.selectListByRecord(DATASET, RECORD);
        assertEquals(1, list.size());
        assertEquals("改了说明", list.get(0).getRemark());
    }

    @Test
    @DisplayName("🔴 过期的共享查不到 —— 口径与 sys_grant.expire_time 逐字一致")
    void testExpiredShareIsInvisible() {
        recordShareService.shareToUser(DATASET, RECORD, 250L,
                LocalDateTime.now().minusSeconds(1), "已过期");

        assertTrue(recordShareMapper.selectListBySubjects(
                DATASET, Set.of(SubjectKey.ofUser(250L))).isEmpty());
    }

    @Test
    @DisplayName("未过期的共享照常可见")
    void testUnexpiredShareVisible() {
        recordShareService.shareToUser(DATASET, RECORD, 250L,
                LocalDateTime.now().plusDays(1), null);

        assertEquals(1, recordShareMapper.selectListBySubjects(
                DATASET, Set.of(SubjectKey.ofUser(250L))).size());
    }

    @Test
    @DisplayName("⚠️ 管理界面看得到已过期的共享（好续期）—— 过滤掉的话用户会以为自己从没分享过")
    void testManagementViewIncludesExpired() {
        recordShareService.shareToUser(DATASET, RECORD, 250L,
                LocalDateTime.now().minusDays(1), "过期了但还在");

        assertEquals(1, recordShareService.listByRecord(DATASET, RECORD).size());
    }

    @Test
    @DisplayName("取消分享")
    void testRevoke() {
        recordShareService.shareToUser(DATASET, RECORD, 250L, null, null);

        assertTrue(recordShareService.revoke(DATASET, RECORD, SubjectKey.ofUser(250L)));
        assertTrue(recordShareService.listByRecord(DATASET, RECORD).isEmpty());
        // 没分享过的取消返回 false，而不是抛异常
        assertFalse(recordShareService.revoke(DATASET, RECORD, SubjectKey.ofUser(999L)));
    }

    @Test
    @DisplayName("数据集之间互不影响 —— 同一个主键在两张表里是两条不同的记录")
    void testDatasetsAreIsolated() {
        recordShareService.shareToUser(DATASET, RECORD, 250L, null, null);

        assertTrue(recordShareMapper.selectListBySubjects(
                "another_form", Set.of(SubjectKey.ofUser(250L))).isEmpty());
    }

    @Test
    @DisplayName("批量分享")
    void testShareToAll() {
        int count = recordShareService.shareToAll(DATASET, RECORD,
                List.of(SubjectKey.ofUser(250L), SubjectKey.ofRole(2L)), null, null);

        assertEquals(2, count);
        assertEquals(2, recordShareService.listByRecord(DATASET, RECORD).size());
    }

    @Test
    @DisplayName("主体为空 / 主键为空时明确报错，不写脏数据")
    void testInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> recordShareService.share(DATASET, RECORD, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> recordShareService.share(DATASET, "", SubjectKey.ofUser(1L), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> recordShareService.share("", RECORD, SubjectKey.ofUser(1L), null, null));
    }

    @Test
    @DisplayName("空主体集合查出空，不要退化成「查全部」")
    void testEmptySubjectsReturnsEmpty() {
        recordShareService.shareToUser(DATASET, RECORD, 250L, null, null);

        assertTrue(recordShareMapper.selectListBySubjects(DATASET, Set.of()).isEmpty());
        assertTrue(recordShareMapper.selectListBySubjects(DATASET, null).isEmpty());
    }

}
