package cn.enlev.ainb.module.agent.framework.agent.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentTaskKeys} 的键形与两条硬约束（缺陷 D4-28 / 裁定 R-01）
 *
 * <h3>为什么一个「拼字符串」的工具类也要测</h3>
 * 因为它守的两条约束**违反时都不报错**：
 * <ol>
 *   <li><b>同一任务内每次调用的键必须完全相同</b> —— 差一个字符，粘滞就静默失效，
 *       模型又会在任务中途漂走，而轨迹里看不出来；</li>
 *   <li><b>不同任务之间不能撞</b> —— 撞了两个任务共用一个钉子，
 *       一个任务的额度耗尽会连累另一个任务直接失败。</li>
 * </ol>
 * 这两条在 {@code AgentTaskKeys} 的类注释里写着，但注释不是强制点。
 *
 * <p>🔴 本类只钉「键本身」。**「键有没有被正确复用」是另一回事**，
 * 由 {@code AgentChatToolLoopTest} 第 ② 条与 {@code WorkflowPlannerServiceImplTest} 第 ② 条守 ——
 * 那两条断言的是「一次 run 的多轮共用同一个键」，把键挪进循环里就会红（已用变异探针实测过）。
 * 两边分工：这里管**形状**，那里管**复用**。
 *
 * @author 走进新时代
 */
public class AgentTaskKeysTest {

    @Test
    @DisplayName("🔴 ofExecution(null) 必须返回 null，绝不返回「EXEC:null」")
    public void testOfExecution_null_returnsNullNotLiteral() {
        // null = 不做粘滞，退回「逐次调用各自选模型」，这是刻意的。
        // 若「顺手」改成返回 "EXEC:null"，所有拿不到 executionId 的调用会**共用同一个钉子** ——
        // 一个不相干的任务额度耗尽，会把所有无 executionId 的调用一起锁死到 TTL 到期。
        assertNull(AgentTaskKeys.ofExecution(null));
    }

    @Test
    @DisplayName("ofExecution：同一 executionId 每次拿到的键完全相同（约束①的前提）")
    public void testOfExecution_deterministic() {
        assertEquals("EXEC:123", AgentTaskKeys.ofExecution(123L));
        assertEquals(AgentTaskKeys.ofExecution(123L), AgentTaskKeys.ofExecution(123L),
                "同一次执行里多个 LLM 节点各调一次，必须拿到逐字相同的键");
    }

    @Test
    @DisplayName("🔴 ofExecution：不同 executionId 的键必不同（约束②，撞了就是跨任务串钉子）")
    public void testOfExecution_differentIds_neverCollide() {
        assertNotEquals(AgentTaskKeys.ofExecution(1L), AgentTaskKeys.ofExecution(2L));
        // 前缀粘连形态也要挡住：EXEC:1 与 EXEC:12 不能因为拼法不当而相等
        assertNotEquals(AgentTaskKeys.ofExecution(1L), AgentTaskKeys.ofExecution(12L));
    }

    @Test
    @DisplayName("newPlanRun：连续两次必不同（一次规划一个键，含多轮自修正回灌）")
    public void testNewPlanRun_uniquePerCall() {
        String first = AgentTaskKeys.newPlanRun();
        String second = AgentTaskKeys.newPlanRun();

        assertTrue(first.startsWith("PLAN:"), "前缀是 PLAN:，实际：" + first);
        assertNotEquals(first, second, "两次规划必须各拿一个键，否则前一次钉住的模型会漏到后一次");
    }

    @Test
    @DisplayName("newToolLoopRun：连续两次必不同，且把 conversationId 拼进键里供日志检索")
    public void testNewToolLoopRun_uniqueAndCarriesConversationId() {
        String first = AgentTaskKeys.newToolLoopRun(100L);
        String second = AgentTaskKeys.newToolLoopRun(100L);

        assertTrue(first.startsWith("TOOLLOOP:100:"), "键形如 TOOLLOOP:<conversationId>:<uuid>，实际：" + first);
        assertNotEquals(first, second,
                "🔴 刻意按「轮」钉而不是按会话钉：按会话钉会让一次额度耗尽把整个会话锁死到 TTL 到期");
    }

    @Test
    @DisplayName("🔴 三个场景的键互不相撞（跨场景撞键 = 两个不相干的任务共用一个钉子）")
    public void testCrossScene_neverCollide() {
        // executionId 取 1，planRun / toolLoopRun 各取一次：三者的前缀必须让它们不可能相等。
        // 这条守的是「前缀不是装饰」—— 少了前缀，PLAN 与 TOOLLOOP 都靠 uuid 区分尚可，
        // 但 EXEC:1 与某个裸 "1" 就会撞上，而那种撞法在日志里几乎看不出来。
        String exec = AgentTaskKeys.ofExecution(1L);
        String plan = AgentTaskKeys.newPlanRun();
        String toolLoop = AgentTaskKeys.newToolLoopRun(1L);

        assertNotEquals(exec, plan);
        assertNotEquals(exec, toolLoop);
        assertNotEquals(plan, toolLoop);
    }

}
