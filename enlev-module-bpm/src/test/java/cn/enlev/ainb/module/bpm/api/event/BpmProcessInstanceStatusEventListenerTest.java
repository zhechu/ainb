package cn.enlev.ainb.module.bpm.api.event;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.SimpleApplicationEventMulticaster;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BpmProcessInstanceStatusEventListener} 的派发前置过滤
 *
 * <h3>为什么这条守卫值得单独测</h3>
 *
 * 三个子类（{@code BpmOALeaveStatusListener}、{@code CrmContractStatusListener}、
 * {@code CrmReceivableStatusListener}）都在 {@code onEvent} 里无条件
 * {@code Long.parseLong(event.getBusinessKey())}。而 agent 模块的 {@code hitl.bpm_approval}
 * 节点用 {@code agent-wf:<executionId>:<nodeId>} 这种<b>命名空间复合键</b>发起 BPM 实例
 * （它必须携带 executionId + nodeId 才能把审批结果路由回正确的挂起节点）。
 *
 * <p>于是只要作者把 {@code bpmProcessDefKey} 配成 {@code oa_leave}（仓库自带的演示流程定义，
 * 也是最容易被选中的那个），审批人一点「通过」就抛 {@link NumberFormatException}，后果是<b>两重</b>的：
 * <ul>
 *   <li>① Spring 的 {@link SimpleApplicationEventMulticaster} 默认<b>没有 ErrorHandler</b>，
 *       第一个抛异常的监听器会中止整条链 ⟹ {@code AgentBpmApprovalListener} 压根没跑
 *       ⟹ 工作流永远停在 INTERRUPTED，且<b>没有任何日志</b>；</li>
 *   <li>② Flowable 的命令上下文一起中止 ⟹ 审批操作返回 500 且<b>事务回滚</b>，
 *       审批人看到「操作失败」，重试同样失败。</li>
 * </ul>
 *
 * <p>后果 ① 是本文件最后一条用例专门钉的：它把「抛异常的监听器会拖死同链其他监听器」这个
 * Spring 行为做成可执行断言。这条断言是<b>缺陷严重性定级的依据</b> —— 若 Spring 其实会
 * 隔离异常，那本缺陷只是「OA 请假状态没回写」这种局部噪音，而不是「工作流永久卡死 + 审批 500」。
 *
 * @author 走进新时代
 */
public class BpmProcessInstanceStatusEventListenerTest extends BaseMockitoUnitTest {

    /** agent 的 businessKey 形状（见 AgentBpmApprovalService#resolveBpmApprovalEventKey） */
    private static final String AGENT_BUSINESS_KEY = "agent-wf:113:gate";
    private static final String OA_LEAVE_KEY = "oa_leave";

    /**
     * 存量子类的形状：只关心一个流程定义 key，onEvent 里直接 parseLong。
     * 与 BpmOALeaveStatusListener 逐字同构（把「更新请假单状态」换成「记下收到的 id」）。
     */
    private static class EntityIdListener extends BpmProcessInstanceStatusEventListener {

        private final List<Long> received = new ArrayList<>();

        @Override
        protected String getProcessDefinitionKey() {
            return OA_LEAVE_KEY;
        }

        @Override
        protected void onEvent(BpmProcessInstanceStatusEvent event) {
            // 🔴 刻意保留无条件 parseLong：这正是被基类守卫保护的那行代码
            received.add(Long.parseLong(event.getBusinessKey()));
        }

    }

    /** agent 侧监听器的形状：不继承基类，靠 businessKey 前缀自己路由 */
    private static class AgentStyleListener implements ApplicationListener<BpmProcessInstanceStatusEvent> {

        private int ran = 0;

        @Override
        public void onApplicationEvent(BpmProcessInstanceStatusEvent event) {
            ran++;
        }

    }

    private static BpmProcessInstanceStatusEvent event(String processDefinitionKey, String businessKey) {
        BpmProcessInstanceStatusEvent event = new BpmProcessInstanceStatusEvent("test-source");
        event.setId("proc-inst-1");
        event.setProcessDefinitionKey(processDefinitionKey);
        event.setStatus(2); // BPM 的「通过」
        event.setBusinessKey(businessKey);
        return event;
    }

    @Test
    @DisplayName("businessKey 是数字主键 → 照常派发（存量三个子类的行为一个字节没变）")
    void testNumericBusinessKeyIsDispatched() {
        EntityIdListener listener = new EntityIdListener();

        listener.onApplicationEvent(event(OA_LEAVE_KEY, "123"));

        assertEquals(List.of(123L), listener.received);
    }

    @Test
    @DisplayName("🔴 businessKey 是 agent 的命名空间键 → 不派发、也不抛异常（修复前这里必抛 NumberFormatException）")
    void testAgentNamespacedBusinessKeyIsSkippedWithoutThrowing() {
        EntityIdListener listener = new EntityIdListener();

        assertDoesNotThrow(() -> listener.onApplicationEvent(event(OA_LEAVE_KEY, AGENT_BUSINESS_KEY)),
                "抛出去的 NumberFormatException 会中止整条监听器链并回滚 Flowable 事务");
        assertTrue(listener.received.isEmpty(), "非数字主键不该被当成实体 id 派发下去");
    }

    @Test
    @DisplayName("businessKey 为 null / 空串 / 纯空白 → 不派发、不抛（事件里这个字段本就允许为 null）")
    void testBlankBusinessKeyIsSkipped() {
        EntityIdListener listener = new EntityIdListener();

        for (String blank : new String[]{null, "", "   "}) {
            assertDoesNotThrow(() -> listener.onApplicationEvent(event(OA_LEAVE_KEY, blank)));
        }
        assertTrue(listener.received.isEmpty());
    }

    @Test
    @DisplayName("流程定义 key 不匹配 → 照旧不派发（守卫没把原有的第一道过滤顶掉）")
    void testProcessDefinitionKeyMismatchIsStillFiltered() {
        EntityIdListener listener = new EntityIdListener();

        listener.onApplicationEvent(event("some_other_process", "123"));

        assertTrue(listener.received.isEmpty());
    }

    @Test
    @DisplayName("🔴 同一条链上：实体监听器遇到命名空间键不再拖死 agent 监听器（后果 ① 的可执行化）")
    void testAgentListenerStillRunsOnTheSameMulticasterChain() {
        EntityIdListener entityListener = new EntityIdListener();
        AgentStyleListener agentListener = new AgentStyleListener();
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        // 🔴 实体监听器**先**注册：修复前它先跑、先抛，链在它这里断掉，agent 那个压根轮不到
        multicaster.addApplicationListener(entityListener);
        multicaster.addApplicationListener(agentListener);

        multicaster.multicastEvent(event(OA_LEAVE_KEY, AGENT_BUSINESS_KEY));

        assertEquals(1, agentListener.ran,
                "agent 监听器没跑 = 工作流永远停在 INTERRUPTED，且没有任何日志可查");
        assertTrue(entityListener.received.isEmpty(), "命名空间键不该被派发成实体 id");
    }

    @Test
    @DisplayName("同一条链上：数字主键照常派发到实体监听器，agent 监听器也照常被通知")
    void testNumericKeyStillReachesBothListenersOnTheChain() {
        EntityIdListener entityListener = new EntityIdListener();
        AgentStyleListener agentListener = new AgentStyleListener();
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        multicaster.addApplicationListener(entityListener);
        multicaster.addApplicationListener(agentListener);

        multicaster.multicastEvent(event(OA_LEAVE_KEY, "456"));

        assertEquals(List.of(456L), entityListener.received, "守卫不许把合法的数字主键也拦掉");
        assertEquals(1, agentListener.ran);
    }

}
