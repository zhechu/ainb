package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HitlBpmApprovalNodeExecutor} 单测（P6 收口，0 token）
 *
 * <p>本执行器<b>不发起审批、不等待</b>——那些由 interruptBefore 挂起 + BPM 回调 resume 完成
 * （见 {@code AgentBpmApprovalService}/{@code AgentBpmApprovalListener}）。它只在 resume 后跑，
 * 职责是「把审批结果读出来、归一化写回 state」。故这里钉住五件事：
 * <ol>
 *   <li>三态归一：APPROVE(2)→approved、REJECT(3)→rejected、CANCEL(4)→canceled（即便 approved 为真也判取消）</li>
 *   <li>approveVar 归一化成布尔（字符串 "true" 也算批准），供下游 condition.rule 用 is_true/is_false 判定</li>
 *   <li>自定义 approveVar/statusVar 名生效；状态码缺失时不写 {@code <id>_status} 键（保持精简 state）</li>
 *   <li>🔴 读不到自定义键时<b>回退读监听器写死的默认键</b>——这才是 {@code AgentBpmApprovalListener}
 *       的真实契约（它在审批人线程上发事件、拿不到节点 config）；显式 feedback 优先级更高。
 *       端到端形状见 {@code HitlBpmApprovalResumeRuntimeTest}</li>
 *   <li>不碰 LLM：无 ChatModel 依赖，token 恒 0（结构上无从记 usage）</li>
 * </ol>
 *
 * @author 走进新时代
 */
@DisplayName("agent HitlBpmApprovalNodeExecutor 审批结果归一化")
class HitlBpmApprovalNodeExecutorTest {

    private final HitlBpmApprovalNodeExecutor executor = new HitlBpmApprovalNodeExecutor();

    private static WorkflowContext ctx(Map<String, Object> data) {
        OverAllState state = new OverAllState(new HashMap<>(data));
        return new WorkflowContext(state, 1L, "t1:1", AgentExecutionContext.ofForTest(1L, 1L));
    }

    private static NodeDTO node(Map<String, Object> config) {
        NodeDTO n = new NodeDTO();
        n.setId("approval");
        n.setType(HitlBpmApprovalNodeExecutor.TYPE);
        n.setConfig(config);
        return n;
    }

    @Test
    @DisplayName("getType 逐字对齐 DSL 节点类型")
    void getType_matchesDsl() {
        assertEquals("hitl.bpm_approval", executor.getType());
    }

    @Test
    @DisplayName("审批通过(2) → approveVar=true、_result=approved、_status=2")
    void execute_approved() {
        Map<String, Object> delta = executor.execute(node(Map.of()),
                ctx(Map.of("bpm_approved", true, "bpm_status", 2)));

        assertEquals(true, delta.get("bpm_approved"));
        assertEquals("approved", delta.get("approval_result"));
        assertEquals(2, delta.get("approval_status"));
    }

    @Test
    @DisplayName("审批驳回(3) → approveVar=false、_result=rejected")
    void execute_rejected() {
        Map<String, Object> delta = executor.execute(node(Map.of()),
                ctx(Map.of("bpm_approved", false, "bpm_status", 3)));

        assertEquals(false, delta.get("bpm_approved"));
        assertEquals("rejected", delta.get("approval_result"));
        assertEquals(3, delta.get("approval_status"));
    }

    @Test
    @DisplayName("🔴 取消(4) → _result=canceled，即便 approved 为真也判取消（状态码优先）")
    void execute_canceled_overridesApprovedTrue() {
        Map<String, Object> delta = executor.execute(node(Map.of()),
                ctx(Map.of("bpm_approved", true, "bpm_status", 4)));

        assertEquals("canceled", delta.get("approval_result"));
        assertEquals(4, delta.get("approval_status"));
    }

    @Test
    @DisplayName("approveVar 字符串 \"true\" 也归一化成布尔 true（监听器/反馈可能传字符串）")
    void execute_stringTrueNormalized() {
        Map<String, Object> delta = executor.execute(node(Map.of()),
                ctx(Map.of("bpm_approved", "true", "bpm_status", 2)));

        assertEquals(true, delta.get("bpm_approved"), "应归一化成布尔 true，而非原样字符串");
        assertEquals("approved", delta.get("approval_result"));
    }

    @Test
    @DisplayName("自定义 approveVar/statusVar 名生效（读同名键、写同名 approveVar）")
    void execute_customVarNames() {
        Map<String, Object> config = new HashMap<>();
        config.put("approveVar", "myApproved");
        config.put("statusVar", "myStatus");

        Map<String, Object> delta = executor.execute(node(config),
                ctx(Map.of("myApproved", true, "myStatus", 2)));

        assertEquals(true, delta.get("myApproved"));
        assertEquals(2, delta.get("myStatus"),
                "statusVar 是 NodeIoContract 登记的输出，必须按配的名字写回，否则下游 {{$.myStatus}} 恒为 null");
        assertEquals("approved", delta.get("approval_result"));
        assertEquals(2, delta.get("approval_status"));
        assertFalse(delta.containsKey("bpm_approved"), "用了自定义名就不该再写默认名");
        assertFalse(delta.containsKey("bpm_status"), "用了自定义名就不该再写默认名");
    }

    @Test
    @DisplayName("🔴 自定义 approveVar/statusVar，但回调只写默认键 → 回退读到，通过仍是通过")
    void execute_customVarNames_fallsBackToListenerDefaultKeys() {
        // 这才是 AgentBpmApprovalListener 的真实契约：BPM 完成事件在审批人线程上发布，
        // 监听器拿不到节点 config，只能写死 bpm_approved / bpm_status 两个默认名。
        // 不回退就读到 null → 「通过」被判成「驳回」→ 流程静默走拒绝分支还报 SUCCEEDED。
        Map<String, Object> config = new HashMap<>();
        config.put("approveVar", "myApproved");
        config.put("statusVar", "myStatus");

        Map<String, Object> delta = executor.execute(node(config),
                ctx(Map.of("bpm_approved", true, "bpm_status", 2)));

        assertEquals(true, delta.get("myApproved"), "应回退采纳默认键并归一化写回自定义 approveVar");
        assertEquals(2, delta.get("myStatus"), "状态码同理：回退读到后要写回自定义 statusVar");
        assertEquals("approved", delta.get("approval_result"), "回退后必须仍判通过，而非默认的 rejected");
        assertEquals(2, delta.get("approval_status"));
        assertFalse(delta.containsKey("bpm_approved"), "用了自定义名就不该再写默认名");
    }

    @Test
    @DisplayName("自定义 approveVar + 显式反馈与默认键并存 → 自己的键优先（回退只在读不到时生效）")
    void execute_customVarNames_explicitWinsOverDefaultFallback() {
        // 工作台 /resume-stream 显式按 approveVar 传 feedback 时，压过监听器的默认键
        // （这里故意让两者相反）—— 与 HitlConfirmNodeExecutor 同款优先级纪律，既有调用方零影响。
        Map<String, Object> config = new HashMap<>();
        config.put("approveVar", "myApproved");
        config.put("statusVar", "myStatus");

        Map<String, Object> delta = executor.execute(node(config),
                ctx(Map.of("myApproved", true, "myStatus", 2, "bpm_approved", false, "bpm_status", 3)));

        assertEquals(true, delta.get("myApproved"), "显式自定义键应压过默认键");
        assertEquals("approved", delta.get("approval_result"));
        assertEquals(2, delta.get("approval_status"), "状态码同理取自定义键的值");
    }

    @Test
    @DisplayName("🔴 只自定义 statusVar（approveVar 用默认）→ 状态码仍能回退读到默认键")
    void execute_customStatusVarOnly_fallsBackToDefaultStatusKey() {
        // 回退是两个变量各自独立的：只改一个名时，另一个走默认路径，改的那个仍须回退
        Map<String, Object> config = new HashMap<>();
        config.put("statusVar", "myStatus");

        Map<String, Object> delta = executor.execute(node(config),
                ctx(Map.of("bpm_approved", true, "bpm_status", 4)));

        assertEquals(true, delta.get("bpm_approved"));
        assertEquals("canceled", delta.get("approval_result"), "回退读到状态码 4，应判取消");
        assertEquals(4, delta.get("approval_status"));
    }

    @Test
    @DisplayName("状态码缺失 → 不写 _status 键，_result 仍按 approved 判定")
    void execute_missingStatus_noStatusKey() {
        Map<String, Object> delta = executor.execute(node(Map.of()),
                ctx(Map.of("bpm_approved", true)));

        assertEquals("approved", delta.get("approval_result"));
        assertFalse(delta.containsKey("approval_status"), "无状态码时不写 _status，保持精简 state");
    }

    @Test
    @DisplayName("approveVar 缺失（未批准）→ 归一化成 false、_result=rejected")
    void execute_missingApproveVar_defaultsFalse() {
        Map<String, Object> delta = executor.execute(node(Map.of()), ctx(Map.of("bpm_status", 3)));

        assertEquals(false, delta.get("bpm_approved"));
        assertEquals("rejected", delta.get("approval_result"));
    }

    @Test
    @DisplayName("state 里字符串状态码 \"2\" 也能解析成整数")
    void execute_stringStatusParsed() {
        Map<String, Object> delta = executor.execute(node(Map.of()),
                ctx(Map.of("bpm_approved", true, "bpm_status", "2")));

        assertEquals(2, delta.get("approval_status"));
        assertTrue(delta.containsKey("approval_status"));
    }

}
