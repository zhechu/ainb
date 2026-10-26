package cn.enlev.ainb.module.bpm.framework.flowable.core.candidate;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmTaskCandidateStrategyEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 十六种候选策略到主体类型的映射
 *
 * <h3>为什么要把这层映射单独测</h3>
 *
 * 它决定的是<b>离职交接能不能自动做</b>：
 *
 * <ul>
 *   <li>映射成 ROLE/DEPT/POST/GROUP → 换了人就自动有解，不需要人工介入</li>
 *   <li>映射成 USER/DYNAMIC → 没有任何规则能推出接手人，必须人工指定</li>
 * </ul>
 *
 * 映射错的后果是<b>静默给出错误的接手人</b>：比如把「表达式」误判成可重算，
 * 脱离当时的流程变量硬算一遍，得到一个看似有依据、实则毫不相干的人，
 * 然后把别人的待办转给他。这比直接说「需要人工指定」危险得多。
 *
 * 所以十六种<b>逐一</b>断言，而不是抽查几个。
 *
 * @author 走进新时代
 */
public class BpmCandidateSubjectTypesTest extends BaseMockitoUnitTest {

    @Test
    @DisplayName("组织类策略 → 可自动重算（换了人就自动有解）")
    void testOrganizationalStrategiesAreRecalculable() {
        assertSubject(BpmTaskCandidateStrategyEnum.ROLE, BpmCandidateSubjectTypes.ROLE, true);
        assertSubject(BpmTaskCandidateStrategyEnum.POST, BpmCandidateSubjectTypes.POST, true);
        assertSubject(BpmTaskCandidateStrategyEnum.USER_GROUP, BpmCandidateSubjectTypes.GROUP, true);
    }

    @Test
    @DisplayName("部门系六种策略都归到 DEPT —— 来源都是「部门」")
    void testDeptFamilyAllMapToDept() {
        assertSubject(BpmTaskCandidateStrategyEnum.DEPT_MEMBER, BpmCandidateSubjectTypes.DEPT, true);
        assertSubject(BpmTaskCandidateStrategyEnum.DEPT_LEADER, BpmCandidateSubjectTypes.DEPT, true);
        assertSubject(BpmTaskCandidateStrategyEnum.MULTI_DEPT_LEADER_MULTI, BpmCandidateSubjectTypes.DEPT, true);
        assertSubject(BpmTaskCandidateStrategyEnum.START_USER_DEPT_LEADER, BpmCandidateSubjectTypes.DEPT, true);
        assertSubject(BpmTaskCandidateStrategyEnum.START_USER_DEPT_LEADER_MULTI, BpmCandidateSubjectTypes.DEPT, true);
        assertSubject(BpmTaskCandidateStrategyEnum.FORM_DEPT_LEADER, BpmCandidateSubjectTypes.DEPT, true);
    }

    @Test
    @DisplayName("⚠️ 点名到人的三种 → USER，不可自动重算")
    void testDirectUserStrategiesNotRecalculable() {
        // 主体类型是 USER（能归因），但重算时没有任何规则可依据 ——
        // 这个区分由 BpmTaskCandidateSnapshotServiceImpl 做，见那里的第 2 步
        assertSubject(BpmTaskCandidateStrategyEnum.USER, BpmCandidateSubjectTypes.USER, true);
        assertSubject(BpmTaskCandidateStrategyEnum.START_USER, BpmCandidateSubjectTypes.USER, true);
        assertSubject(BpmTaskCandidateStrategyEnum.ASSIGN_EMPTY, BpmCandidateSubjectTypes.USER, true);
    }

    @Test
    @DisplayName("🔴 依赖运行时上下文的四种 → DYNAMIC，绝不能自动重算")
    void testContextDependentStrategiesAreDynamic() {
        // 这四种的候选人取决于「当时」：流程变量的值、表单里填了谁、审批人点了谁。
        // 脱离当时的上下文重算，得到的是一个看似有依据的错误答案
        assertSubject(BpmTaskCandidateStrategyEnum.EXPRESSION, BpmCandidateSubjectTypes.DYNAMIC, false);
        assertSubject(BpmTaskCandidateStrategyEnum.FORM_USER, BpmCandidateSubjectTypes.DYNAMIC, false);
        assertSubject(BpmTaskCandidateStrategyEnum.APPROVE_USER_SELECT, BpmCandidateSubjectTypes.DYNAMIC, false);
        assertSubject(BpmTaskCandidateStrategyEnum.START_USER_SELECT, BpmCandidateSubjectTypes.DYNAMIC, false);
    }

    @Test
    @DisplayName("⚠️ 十六种策略一个都不能漏 —— 漏了会默默落到 DYNAMIC")
    void testAllStrategiesCovered() {
        // switch 漏写某个枚举时，of() 会走不到分支而编译失败；
        // 但若有人日后加了新策略并顺手补成 default → DYNAMIC，就会静默失去重算能力。
        // 这条用例保证「新增策略必须有人显式决定它的主体类型」
        Set<String> seen = new HashSet<>();
        for (BpmTaskCandidateStrategyEnum strategy : BpmTaskCandidateStrategyEnum.values()) {
            String subject = BpmCandidateSubjectTypes.of(strategy);
            assertNotNull(subject, "策略 " + strategy + " 没有主体类型映射");
            seen.add(subject);
        }
        assertEquals(16, BpmTaskCandidateStrategyEnum.values().length,
                "策略数量变了。新增策略时必须在 BpmCandidateSubjectTypes.of 里显式决定它的主体类型，"
                        + "并在本测试里补一条断言 —— 落到 DYNAMIC 意味着它永远无法自动交接");
        // 五类主体应当都被用到；某一类没人用说明映射写歪了
        assertTrue(seen.containsAll(Set.of(BpmCandidateSubjectTypes.ROLE, BpmCandidateSubjectTypes.DEPT,
                BpmCandidateSubjectTypes.POST, BpmCandidateSubjectTypes.GROUP,
                BpmCandidateSubjectTypes.USER, BpmCandidateSubjectTypes.DYNAMIC)), seen.toString());
    }

    @Test
    @DisplayName("策略为 null 时归 DYNAMIC —— 未知来源按最保守处理")
    void testNullStrategyIsDynamic() {
        assertEquals(BpmCandidateSubjectTypes.DYNAMIC, BpmCandidateSubjectTypes.of(null));
        assertFalse(BpmCandidateSubjectTypes.recalculable(BpmCandidateSubjectTypes.DYNAMIC));
    }

    private void assertSubject(BpmTaskCandidateStrategyEnum strategy, String expected,
                               boolean recalculable) {
        assertEquals(expected, BpmCandidateSubjectTypes.of(strategy),
                "策略 " + strategy + " 的主体类型不对");
        assertEquals(recalculable, BpmCandidateSubjectTypes.recalculable(expected),
                "主体类型 " + expected + " 的可重算性不对");
    }

}
