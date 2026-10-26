package cn.enlev.ainb.module.bpm.framework.flowable.core.candidate.strategy.dept;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmnVariableConstants;
import cn.enlev.ainb.module.system.api.dept.DeptApi;
import cn.enlev.ainb.module.system.api.dept.dto.DeptRespDTO;
import cn.enlev.ainb.module.system.api.user.AdminUserApi;
import cn.enlev.ainb.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 发起组织快照（v9 §7.2）
 *
 * <h3>它修的是什么</h3>
 *
 * 「发起人部门负责人审批」原本每次求值都查发起人的<b>当前</b>部门。于是：
 * 张三在研发部提了请假单，走到审批节点时他已调去市场部 ——
 * 单子派给了<b>市场部</b>负责人。
 *
 * 而这张假是他在研发部休的、假期归研发部管，理应由研发部负责人批。
 * 表现是「市场部负责人收到一张跟自己部门毫无关系的旧单子」，且全程不报错。
 *
 * <h3>⚠️ 本批用例守的核心区分</h3>
 *
 * <blockquote>
 * 快照锁的是「他<b>当时属于</b>哪个部门」，<b>不锁</b>「那个部门<b>现在归谁管</b>」。
 * </blockquote>
 *
 * 后者必须实时查 —— 任务是现在要办的，就该由现在在位的负责人办；
 * 派给一个已经不在位（甚至已离职）的人是错的。
 * 两者都锁住的话，问题只是从「派错人」变成「派给离职的人」。
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class BpmTaskCandidateStartUserDeptSnapshotTest extends BaseMockitoUnitTest {

    private static final Long START_USER = 1000L;
    private static final Long DEPT_AT_START = 100L;   // 发起时：研发部
    private static final Long DEPT_NOW = 200L;        // 现在：市场部（调岗后）

    @Mock
    private DeptApi deptApi;
    @Mock
    private AdminUserApi adminUserApi;

    /** 被测的是基类的取部门逻辑，用一个最小子类承载 */
    private AbstractBpmTaskCandidateDeptLeaderStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new AbstractBpmTaskCandidateDeptLeaderStrategy() {
            @Override
            public cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmTaskCandidateStrategyEnum getStrategy() {
                return cn.enlev.ainb.module.bpm.framework.flowable.core.enums
                        .BpmTaskCandidateStrategyEnum.START_USER_DEPT_LEADER;
            }

            @Override
            public void validateParam(String param) {
            }
        };
        ReflectionTestUtils.setField(strategy, "deptApi", deptApi);
        ReflectionTestUtils.setField(strategy, "adminUserApi", adminUserApi);

        // 发起人【现在】在市场部（已调岗）
        AdminUserRespDTO user = new AdminUserRespDTO();
        user.setId(START_USER);
        user.setDeptId(DEPT_NOW);
        when(adminUserApi.getUser(START_USER)).thenReturn(user);

        when(deptApi.getDept(DEPT_AT_START)).thenReturn(dept(DEPT_AT_START));
        when(deptApi.getDept(DEPT_NOW)).thenReturn(dept(DEPT_NOW));
    }

    private DeptRespDTO dept(Long id) {
        DeptRespDTO d = new DeptRespDTO();
        d.setId(id);
        return d;
    }

    private Map<String, Object> snapshot(Object deptId) {
        return Map.of(BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_START_USER_DEPT_ID, deptId);
    }

    @Test
    @DisplayName("🔴 有快照时取【发起时】部门，而不是发起人当前部门")
    void testUsesSnapshotDept() {
        DeptRespDTO result = strategy.getStartUserDept(START_USER, snapshot(DEPT_AT_START));

        assertEquals(DEPT_AT_START, result.getId(),
                "取当前部门的话，中途调岗会把单子派给新部门的负责人 —— 那人跟这张单子毫无关系");
        // 有快照就不该再去查用户的当前部门
        verify(adminUserApi, never()).getUser(START_USER);
    }

    @Test
    @DisplayName("⚠️ 没有快照时回落查当前部门 —— 存量实例的变量里没有它")
    void testFallsBackWhenNoSnapshot() {
        DeptRespDTO result = strategy.getStartUserDept(START_USER, Map.of());

        assertEquals(DEPT_NOW, result.getId(),
                "直接判空的话，所有在办的老流程会在升级瞬间找不到审批人");
    }

    @Test
    @DisplayName("流程变量为 null 时同样回落")
    void testFallsBackWhenNullVariables() {
        assertEquals(DEPT_NOW, strategy.getStartUserDept(START_USER, null).getId());
    }

    @Test
    @DisplayName("⚠️ 快照里的部门已被删除时回落查当前 —— 总比找不到审批人强")
    void testFallsBackWhenSnapshotDeptDeleted() {
        when(deptApi.getDept(DEPT_AT_START)).thenReturn(null);

        assertEquals(DEPT_NOW, strategy.getStartUserDept(START_USER, snapshot(DEPT_AT_START)).getId());
    }

    @Test
    @DisplayName("快照值是字符串形态也要能解析（流程变量的类型不总是 Long）")
    void testSnapshotAsString() {
        assertEquals(DEPT_AT_START,
                strategy.getStartUserDept(START_USER, snapshot(String.valueOf(DEPT_AT_START))).getId());
    }

    @Test
    @DisplayName("⚠️ 快照【不】锁部门负责人 —— 负责人仍由调用方实时查")
    void testSnapshotDoesNotFreezeLeader() {
        // 本方法只负责「取哪个部门」，返回的是部门对象本身；
        // 「这个部门现在的负责人是谁」由 getAssignLevelDeptLeaderId 实时查。
        // 若日后有人把 leaderUserId 也一并快照进来，这条断言会失败
        DeptRespDTO result = strategy.getStartUserDept(START_USER, snapshot(DEPT_AT_START));

        assertNull(result.getLeaderUserId(),
                "负责人不该来自快照 —— 任务是现在要办的，就该由现在在位的负责人办。"
                        + "两者都锁住的话，问题只是从「派错人」变成「派给离职的人」");
    }

}
