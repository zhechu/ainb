package cn.enlev.ainb.module.bpm.service.task;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.bpm.dal.dataobject.task.BpmTaskCandidateSnapshotDO;
import cn.enlev.ainb.module.bpm.dal.mysql.task.BpmTaskCandidateSnapshotMapper;
import cn.enlev.ainb.module.bpm.framework.flowable.core.candidate.BpmCandidateSubjectTypes;
import cn.enlev.ainb.module.bpm.framework.flowable.core.candidate.BpmTaskCandidateInvoker;
import cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmTaskCandidateStrategyEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 候选人快照与离职交接重算
 *
 * <h3>这批用例守的核心不变量</h3>
 *
 * <blockquote>
 * 算不出接手人时，必须明确说「请人工指定」，<b>绝不能硬算一个出来</b>。
 * </blockquote>
 *
 * 交接是把别人的待办转走 —— 转错了，单据落到不相干的人手里，
 * 而原本该处理的人再也看不到它。一个「看似有依据」的错误接手人，
 * 比一句「需要人工指定」危险得多。
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class BpmTaskCandidateSnapshotServiceImplTest extends BaseMockitoUnitTest {

    private static final String PID = "proc-1";
    private static final String TASK_ID = "task-1";
    private static final Long LEAVING = 1000L;
    private static final Long SUCCESSOR = 2000L;

    @Mock
    private BpmTaskCandidateSnapshotMapper snapshotMapper;
    @Mock
    private BpmTaskCandidateInvoker candidateInvoker;

    private BpmTaskCandidateSnapshotServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BpmTaskCandidateSnapshotServiceImpl();
        ReflectionTestUtils.setField(service, "snapshotMapper", snapshotMapper);
        ReflectionTestUtils.setField(service, "candidateInvoker", candidateInvoker);
        when(snapshotMapper.selectByTaskAndSubjectAndUser(any(), any(), any(), any())).thenReturn(null);
    }

    private BpmTaskCandidateSnapshotDO snapshot(BpmTaskCandidateStrategyEnum strategy, String param) {
        return BpmTaskCandidateSnapshotDO.builder()
                .id(1L).processInstanceId(PID).taskId(TASK_ID)
                .strategy(strategy.getStrategy()).param(param)
                .subjectType(BpmCandidateSubjectTypes.of(strategy))
                .resolvedUserId(LEAVING)
                .build();
    }

    // ==================== 快照写入 ====================

    @Test
    @DisplayName("写快照：表达式与主体一起落库，每个候选人一行")
    void testSnapshotRecordsExpressionAndSubject() {
        service.snapshot(PID, TASK_ID, "node-1",
                BpmTaskCandidateStrategyEnum.ROLE.getStrategy(), "101,102",
                List.of(LEAVING, SUCCESSOR));

        ArgumentCaptor<BpmTaskCandidateSnapshotDO> captor =
                ArgumentCaptor.forClass(BpmTaskCandidateSnapshotDO.class);
        verify(snapshotMapper, times(2)).insert(captor.capture());
        BpmTaskCandidateSnapshotDO first = captor.getAllValues().get(0);
        assertEquals(BpmTaskCandidateStrategyEnum.ROLE.getStrategy(), first.getStrategy(), "表达式：策略");
        assertEquals("101,102", first.getParam(), "表达式：参数");
        assertEquals(BpmCandidateSubjectTypes.ROLE, first.getSubjectType(),
                "主体类型要落库，否则说不清「他为什么是审批人」");
        assertEquals(101L, first.getSubjectRefId(), "主体编号取参数里的第一个");
        assertNotNull(first.getSnapshotTime());
    }

    @Test
    @DisplayName("写快照：重复触发不产生第二行")
    void testSnapshotIsIdempotent() {
        when(snapshotMapper.selectByTaskAndSubjectAndUser(any(), any(), any(), any()))
                .thenReturn(new BpmTaskCandidateSnapshotDO());

        service.snapshot(PID, TASK_ID, "node-1",
                BpmTaskCandidateStrategyEnum.ROLE.getStrategy(), "101", List.of(LEAVING));

        verify(snapshotMapper, never()).insert(any(BpmTaskCandidateSnapshotDO.class));
    }

    // ==================== 离职交接重算 ====================

    @Test
    @DisplayName("角色来源：按原规则重算出接手人，且排除离职者本人")
    void testRecalculateByRole() {
        when(snapshotMapper.selectListByResolvedUserId(LEAVING))
                .thenReturn(List.of(snapshot(BpmTaskCandidateStrategyEnum.ROLE, "101")));
        when(candidateInvoker.calculateUsersByParam(BpmTaskCandidateStrategyEnum.ROLE, "101"))
                .thenReturn(Set.of(LEAVING, SUCCESSOR));

        List<BpmTaskCandidateSnapshotService.RecalculateItem> items = service.recalculateByUser(LEAVING);

        assertEquals(1, items.size());
        assertTrue(items.get(0).recalculable());
        assertEquals(List.of(SUCCESSOR), items.get(0).suggestedUsers(),
                "接手人里绝不能再有离职的那个人");
    }

    @Test
    @DisplayName("🔴 表达式来源：不重算，明确要求人工指定")
    void testExpressionRequiresManual() {
        when(snapshotMapper.selectListByResolvedUserId(LEAVING))
                .thenReturn(List.of(snapshot(BpmTaskCandidateStrategyEnum.EXPRESSION, "${x}")));

        List<BpmTaskCandidateSnapshotService.RecalculateItem> items = service.recalculateByUser(LEAVING);

        assertFalse(items.get(0).recalculable());
        assertTrue(items.get(0).suggestedUsers().isEmpty(),
                "脱离当时的流程变量硬算，得到的是看似有依据的错误答案");
        assertTrue(items.get(0).reason().contains("人工"));
        verify(candidateInvoker, never()).calculateUsersByParam(any(), any());
    }

    @Test
    @DisplayName("🔴 点名到人：不重算 —— 「张三」这个参数推不出任何接手人")
    void testDirectUserRequiresManual() {
        when(snapshotMapper.selectListByResolvedUserId(LEAVING))
                .thenReturn(List.of(snapshot(BpmTaskCandidateStrategyEnum.USER, String.valueOf(LEAVING))));

        List<BpmTaskCandidateSnapshotService.RecalculateItem> items = service.recalculateByUser(LEAVING);

        assertFalse(items.get(0).recalculable());
        assertTrue(items.get(0).reason().contains("人工"));
    }

    @Test
    @DisplayName("🔴 重算后只剩他自己：不能交接，要人工指定")
    void testOnlySelfLeftRequiresManual() {
        when(snapshotMapper.selectListByResolvedUserId(LEAVING))
                .thenReturn(List.of(snapshot(BpmTaskCandidateStrategyEnum.ROLE, "101")));
        when(candidateInvoker.calculateUsersByParam(any(), any())).thenReturn(Set.of(LEAVING));

        List<BpmTaskCandidateSnapshotService.RecalculateItem> items = service.recalculateByUser(LEAVING);

        assertFalse(items.get(0).recalculable());
        assertTrue(items.get(0).suggestedUsers().isEmpty(),
                "该角色目前只有他一个人，硬给个空接手人会让任务无人可办");
    }

    @Test
    @DisplayName("重算抛异常时该条转成「人工指定」，不影响其余条目")
    void testOneFailureDoesNotBreakOthers() {
        BpmTaskCandidateSnapshotDO bad = snapshot(BpmTaskCandidateStrategyEnum.ROLE, "101");
        BpmTaskCandidateSnapshotDO good = snapshot(BpmTaskCandidateStrategyEnum.POST, "201");
        good.setId(2L);
        when(snapshotMapper.selectListByResolvedUserId(LEAVING)).thenReturn(List.of(bad, good));
        when(candidateInvoker.calculateUsersByParam(BpmTaskCandidateStrategyEnum.ROLE, "101"))
                .thenThrow(new UnsupportedOperationException("该分配方法未实现"));
        when(candidateInvoker.calculateUsersByParam(BpmTaskCandidateStrategyEnum.POST, "201"))
                .thenReturn(Set.of(SUCCESSOR));

        List<BpmTaskCandidateSnapshotService.RecalculateItem> items = service.recalculateByUser(LEAVING);

        assertEquals(2, items.size());
        assertFalse(items.get(0).recalculable(), "失败的那条转成人工指定");
        assertTrue(items.get(1).recalculable(), "一条失败不该让整个交接流程中断");
    }

    @Test
    @DisplayName("用户为空时返回空列表，不抛异常")
    void testNullUser() {
        assertTrue(service.recalculateByUser(null).isEmpty());
    }

}
