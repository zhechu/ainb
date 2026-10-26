package cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger;

import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentSanitizeProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.TriggerDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.UntrustedInputTracer.SourceKind;
import cn.enlev.ainb.module.agent.framework.agent.core.security.UntrustedInputSanitizer;
import cn.enlev.ainb.module.agent.service.workflow.AgentFailureAlertService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService.WorkflowEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowTriggerInvoker} 单测（增量6，纯 mock，0 token）
 *
 * <p>钉住无头启动的三条纪律：① 🔴 owner 缺失 fail-close——绝不调 run（不降级为匿名/系统放行）；
 * ② owner 存在时以 {@code ofSystem(owner, def.tenant)} 身份、把 payload 包进 {@code state[inputVar]} 调 run，
 * 且<b>真的 subscribe</b>（run 返回 lazy Flux，不订阅就白构造一趟、图根本不跑）；
 * ③ run 同步抛错或 Flux onError 都被吞成日志、返回 false/true 而不外泄（触发失败绝不污染监听器/轮询器循环）。
 *
 * <h3>B2-4 追加：进 state 之前必须过 sanitizer，且污点源从 def.triggerType 派生</h3>
 * 本处是 webhook / form_event / schedule <b>三源共同的漏斗</b>，故这里的断言等价于「三个源都被盖住」。
 * 🔴 「派生对了没」一律用<b>行为</b>证明（把各档上限设成互不相同的值，看实际截到几），
 * 而不是只 {@code verify} 传了哪个枚举 —— 后者只能证明「调用方传了个值」，
 * 证明不了那个值真的决定了处置强度；而前者连「{@code maxLengthFor} 漏了某一档」都能一并抓到。
 *
 * @author 走进新时代
 */
@DisplayName("agent WorkflowTriggerInvoker 无头启动")
class WorkflowTriggerInvokerTest {

    private WorkflowTriggerInvoker invoker;
    private AgentWorkflowExecutionService executionService;
    private DslParser dslParser;
    /** 真 sanitizer（非 mock）：让上面 ② 那些既有断言继续走真实处置链，而不是走一个「原样返回」的桩 */
    private UntrustedInputSanitizer inputSanitizer;
    /** 各源上限。测试里按需改成互不相同的值，用「实际截到几」反推用的是哪一档 */
    private AgentSanitizeProperties sanitizeProperties;
    /** 🔴 B5-4：派发前失败告警。mock 掉，只验证「该发时发了一次、不该发时一次没发」 */
    private AgentFailureAlertService failureAlertService;

    @BeforeEach
    void setUp() {
        executionService = mock(AgentWorkflowExecutionService.class);
        dslParser = mock(DslParser.class);
        sanitizeProperties = new AgentSanitizeProperties();
        // spy 而非 mock：默认走真实处置（故下面 assertEquals(payload, ...) 那些既有断言仍然有意义），
        // 只在「sanitizer 自己抛错」那一条上 doThrow
        inputSanitizer = spy(new UntrustedInputSanitizer());
        ReflectionTestUtils.setField(inputSanitizer, "sanitizeProperties", sanitizeProperties);
        failureAlertService = mock(AgentFailureAlertService.class);
        invoker = new WorkflowTriggerInvoker();
        ReflectionTestUtils.setField(invoker, "executionService", executionService);
        ReflectionTestUtils.setField(invoker, "dslParser", dslParser);
        ReflectionTestUtils.setField(invoker, "inputSanitizer", inputSanitizer);
        ReflectionTestUtils.setField(invoker, "failureAlertService", failureAlertService);
    }

    private void stubInputVar(String inputVar) {
        TriggerDTO trigger = new TriggerDTO();
        trigger.setType("trigger.form_event");
        trigger.setConfig(inputVar == null ? Map.of() : Map.of("inputVar", inputVar));
        WorkflowDefDTO dto = new WorkflowDefDTO();
        dto.setTrigger(trigger);
        when(dslParser.parse(anyString())).thenReturn(dto);
    }

    private static AgentWorkflowDefDO def(Long owner) {
        return def(owner, null);
    }

    private static AgentWorkflowDefDO def(Long owner, String triggerType) {
        AgentWorkflowDefDO def = new AgentWorkflowDefDO();
        def.setCode("wf_demo");
        def.setOwnerUserId(owner);
        def.setTenantId(1L);
        def.setDsl("{}");
        def.setTriggerType(triggerType);
        return def;
    }

    /** 捕获真正传进 run() 的 inputs，即「落进 state 的那一份」。多次派发时取最后一次 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> captureInputs() {
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(executionService, atLeastOnce()).run(anyString(), cap.capture(), any());
        return cap.getValue();
    }

    /** 三档设成互不相同的值：截到 3=chat、4=form_event、5=webhook、都没截=没走 sanitizer */
    private void tierCapsDistinctly() {
        sanitizeProperties.setChatMaxLength(3);
        sanitizeProperties.setFormEventValueMaxLength(4);
        sanitizeProperties.setWebhookBodyMaxLength(5);
    }

    /** 跑一次 invokeHeadless 并返回落进 state[input] 的那个字符串值 */
    private String dispatchAndReadLeaf(String triggerType, String leafValue) {
        stubInputVar(null);
        when(executionService.run(anyString(), any(), any())).thenReturn(Flux.empty());
        invoker.invokeHeadless(def(7L, triggerType), Map.of("k", leafValue));
        @SuppressWarnings("unchecked")
        Map<String, Object> stateInput = (Map<String, Object>) captureInputs().get("input");
        return (String) stateInput.get("k");
    }

    @Test
    @DisplayName("owner 存在：以 ofSystem(owner,tenant) 身份、payload 包进 state[inputVar] 调 run 并 subscribe")
    void invokeHeadless_runsAsPublisher_andSubscribes() {
        stubInputVar(null); // 默认 inputVar = "input"
        AtomicBoolean subscribed = new AtomicBoolean(false);
        when(executionService.run(anyString(), any(), any()))
                .thenReturn(Flux.<WorkflowEvent>empty().doOnSubscribe(s -> subscribed.set(true)));
        Map<String, Object> payload = Map.of("formCode", "fc", "eventType", "create", "dataId", "123");

        boolean dispatched = invoker.invokeHeadless(def(7L), payload);

        assertTrue(dispatched, "成功派发应返回 true");
        assertTrue(subscribed.get(), "🔴 必须真的 subscribe，否则 lazy Flux 不执行、图根本不跑");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputsCap = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<AgentExecutionContext> ctxCap = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(executionService, times(1)).run(eq("wf_demo"), inputsCap.capture(), ctxCap.capture());
        // payload 整体写进 state[inputVar]
        assertEquals(payload, inputsCap.getValue().get("input"));
        assertEquals(1, inputsCap.getValue().size(), "inputs 只应含 inputVar 一个键");
        // 身份 = 发布者 owner + def 租户
        assertEquals(7L, ctxCap.getValue().getUserId().longValue(), "触发身份 userId 应为发布者 owner");
        assertEquals(1L, ctxCap.getValue().getTenantId().longValue(), "触发身份 tenantId 应取自 def");
        assertTrue(ctxCap.getValue().isComplete(), "ofSystem 身份须完整，否则 run 的 requireIdentity 会 fail-close");
    }

    @Test
    @DisplayName("自定义 inputVar：payload 写进该变量而非默认 input")
    void invokeHeadless_usesCustomInputVar() {
        stubInputVar("payload");
        when(executionService.run(anyString(), any(), any())).thenReturn(Flux.empty());
        Map<String, Object> payload = Map.of("cron", "0 0 * * * *");

        invoker.invokeHeadless(def(7L), payload);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputsCap = ArgumentCaptor.forClass(Map.class);
        verify(executionService).run(anyString(), inputsCap.capture(), any());
        assertEquals(payload, inputsCap.getValue().get("payload"));
    }

    @Test
    @DisplayName("🔴 owner 缺失：fail-close，绝不调 run，返回 false")
    void invokeHeadless_nullOwner_failClose() {
        boolean dispatched = invoker.invokeHeadless(def(null), Map.of("k", "v"));

        assertFalse(dispatched, "无发布者身份应 fail-close 返回 false");
        verify(executionService, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("run 同步抛错：吞成日志、返回 false，不外泄到调用方")
    void invokeHeadless_runThrows_swallowed() {
        stubInputVar(null);
        when(executionService.run(anyString(), any(), any())).thenThrow(new RuntimeException("boom"));

        boolean dispatched = assertDoesNotThrow(() -> invoker.invokeHeadless(def(7L), Map.of("k", "v")),
                "run 同步抛错不应外泄（否则污染监听器/轮询器循环）");
        assertFalse(dispatched);
    }

    @Test
    @DisplayName("Flux onError：图执行失败由 onError 兜住记日志，invokeHeadless 仍返回 true（已派发）且不抛")
    void invokeHeadless_fluxError_handledByOnError() {
        stubInputVar(null);
        when(executionService.run(anyString(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("graph fail")));

        boolean dispatched = assertDoesNotThrow(() -> invoker.invokeHeadless(def(7L), Map.of("k", "v")));
        assertTrue(dispatched, "已成功 subscribe 即算派发；图内失败是异步的，由 onError 记日志");
    }

    // ==================== B2-4：进 state 之前过 sanitizer ====================
    // 🔴 本处是 webhook / form_event / schedule 三源【共同的漏斗】，
    //    故下面这些断言等价于「三个触发源都被盖住了」，不必再去三个调用方各测一遍。

    @Test
    @DisplayName("🔴 B2-4 核心：控制字符 / 不可见字符在【进 state 之前】被剥掉，run() 收到的是干净值")
    void invokeHeadless_stripsInvisibleCharsBeforeState() {
        String landed = dispatchAndReadLeaf("webhook", "正常\u0000内容\u200b结尾\r\n换行");

        assertEquals("正常内容结尾\n换行", landed,
                "NUL 与 ZWSP 必须被剥掉、CRLF 必须归一成 LF；"
                        + "🔴 这一条是 B2-4 的存在理由：脏字符一旦进了 state，"
                        + "下游 prompt / 工具入参 / BPM formVars 三条路都会带着它走");
        assertFalse(landed.contains("\u200b"), "ZWSP 残留");
        assertFalse(landed.contains("\u0000"), "NUL 残留");
    }

    @Test
    @DisplayName("🔴 B2-4：超长叶子被硬截到该源的上限，超出部分不进 state")
    void invokeHeadless_truncatesOverCapLeaf() {
        sanitizeProperties.setWebhookBodyMaxLength(8);

        String landed = dispatchAndReadLeaf("webhook", "ABCDEFGHIJKL"); // 12 字符

        assertEquals("ABCDEFGH", landed, "应截到 webhook 档上限 8，尾巴那 4 个字符不许进 state");
    }

    @Test
    @DisplayName("🔴 B2-4：triggerType=webhook 派生到 webhook 档（用「截到几」证明，不只 verify 枚举）")
    void invokeHeadless_webhookTriggerTypeUsesWebhookTier() {
        tierCapsDistinctly(); // chat=3 form_event=4 webhook=5

        assertEquals("ABCDE", dispatchAndReadLeaf("webhook", "ABCDEFGHIJ"),
                "截到 5 = 用的是 webhook 档；截到 3 或 4 说明 resolveSourceKind 派生错了");
        verify(inputSanitizer).sanitizePayload(eq(SourceKind.TRIGGER_WEBHOOK), any());
    }

    @Test
    @DisplayName("🔴 B2-4：triggerType=chat 派生到 chat 档")
    void invokeHeadless_chatTriggerTypeUsesChatTier() {
        tierCapsDistinctly();

        assertEquals("ABC", dispatchAndReadLeaf("chat", "ABCDEFGHIJ"), "截到 3 = 用的是 chat 档");
        verify(inputSanitizer).sanitizePayload(eq(SourceKind.TRIGGER_CHAT), any());
    }

    @Test
    @DisplayName("🔴 B2-4：triggerType=form_event 派生到 form_event 档")
    void invokeHeadless_formEventTriggerTypeUsesFormEventTier() {
        tierCapsDistinctly();

        assertEquals("ABCD", dispatchAndReadLeaf("form_event", "ABCDEFGHIJ"), "截到 4 = 用的是 form_event 档");
        verify(inputSanitizer).sanitizePayload(eq(SourceKind.TRIGGER_FORM_EVENT), any());
    }

    @Test
    @DisplayName("🔴 B2-4：schedule 借用 form_event 档 —— 它当【金丝雀】，但绝不是「跳过处置」")
    void invokeHeadless_scheduleBorrowsFormEventTierAsCanary() {
        tierCapsDistinctly();

        String landed = dispatchAndReadLeaf("schedule", "ABCDEFGHIJ");

        assertEquals("ABCD", landed,
                "schedule 载荷本是平台自己拼的（triggerType 字面量 + 时间戳 + def 自己的 cron），正常情况下永远不会被截。"
                        + "它仍借 form_event 档走一遍处置，是为了当金丝雀：真被截了 / 真剥出控制字符了，"
                        + "就说明有人往定时载荷里塞了外部数据，sanitizer 那条 warn 会响。"
                        + "🔴 若这里返回原样的 ABCDEFGHIJ，说明有人加了「schedule 免处置」的分支 —— "
                        + "那种改动是静默的，不会有任何测试或日志提示它发生了");
        verify(inputSanitizer).sanitizePayload(eq(SourceKind.TRIGGER_FORM_EVENT), any());
    }

    @Test
    @DisplayName("🔴 B2-4：triggerType 为 null 或未知值时保守落 form_event 档，绝不落「不处置」")
    void invokeHeadless_nullOrUnknownTriggerTypeFallsBackConservatively() {
        tierCapsDistinctly();

        assertEquals("ABCD", dispatchAndReadLeaf(null, "ABCDEFGHIJ"),
                "triggerType=null（增量6 之前的老数据、或触发列没提取出来）也必须处置："
                        + "认不出来源就放行，等于给「把 triggerType 置空」留了一条绕过处置的路");
        assertEquals("ABCD", dispatchAndReadLeaf("将来新增的第四种触发", "ABCDEFGHIJ"),
                "未知类型落最保守的一档，而不是「认不出就不管」");
    }

    @Test
    @DisplayName("🔴 B2-4：调用方那份 payload 树不被就地改写（sanitizer 返回新建的 map）")
    void invokeHeadless_doesNotMutateCallerPayload() {
        stubInputVar(null);
        when(executionService.run(anyString(), any(), any())).thenReturn(Flux.empty());
        Map<String, Object> inner = new HashMap<>();
        inner.put("deep", "脏\u200b值");
        Map<String, Object> callerPayload = new HashMap<>();
        callerPayload.put("nested", inner);
        callerPayload.put("top", "脏\u0000值");

        invoker.invokeHeadless(def(7L, "webhook"), callerPayload);

        // 🔴 断言的是【调用方手里那一份】，不是落进 state 那一份：
        //    监听器/轮询器在 invokeHeadless 返回之后还要继续用这个 payload 记日志、做后续匹配，
        //    被就地改写成「剥过控制字符的版本」会让它们的日志与实际入站内容对不上 —— 排障时最要命的一种失真
        assertEquals("脏\u200b值", inner.get("deep"), "嵌套层被就地改写了");
        assertEquals("脏\u0000值", callerPayload.get("top"), "顶层被就地改写了");
    }

    @Test
    @DisplayName("🔴 B2-4：sanitizer 自己抛错时 fail-close —— 吞成 false，绝不「退回用原始 payload 继续跑」")
    void invokeHeadless_sanitizerThrows_failCloseNotFallbackToRaw() {
        stubInputVar(null);
        doThrow(new IllegalStateException("sanitizer boom"))
                .when(inputSanitizer).sanitizePayload(any(), any());

        boolean dispatched = assertDoesNotThrow(
                () -> invoker.invokeHeadless(def(7L, "webhook"), Map.of("k", "v")),
                "sanitizer 抛错不许外泄：调用方是后台轮询器/监听器，外泄会打断它继续处理其余定义");
        assertFalse(dispatched);
        // 🔴 这一条才是本测试的重点：处置失败时【一个字符都不许进 state】。
        //    「反正 sanitizer 挂了，那就拿原始 payload 凑合跑」是最诱人的错误修法 ——
        //    它把一次组件故障变成一次注入放行，而且现场看不出任何异常（流程照常跑起来了、日志照常绿）
        verify(executionService, never()).run(any(), any(), any());
    }

    // ==================== B5-4：派发前失败必须发告警（唯一曾经无告警的失败面） ====================
    // 🔴 这两条 return false 的路径都不建执行行、不经 settle，故 alertExecutionFailure(exec=null) 对它们恒静默。
    //    下面钉住「该发时发了一次、reason 是事实/原文、不该发时一次没发」。

    @Test
    @DisplayName("🔴 B5-4：owner 缺失 fail-close → 发一次派发前失败告警（reason 为事实描述）")
    void invokeHeadless_nullOwner_alertsDispatchFailure() {
        AgentWorkflowDefDO d = def(null);

        invoker.invokeHeadless(d, Map.of("k", "v"));

        verify(executionService, never()).run(any(), any(), any());
        verify(failureAlertService, times(1)).alertDispatchFailure(eq(d),
                eq("无 owner_user_id，无法合成触发身份，fail-close 跳过"));
    }

    @Test
    @DisplayName("🔴 B5-4：run 同步抛错 → 发一次派发前失败告警（reason 为 ex.getMessage() 原文）")
    void invokeHeadless_runThrows_alertsDispatchFailure() {
        stubInputVar(null);
        when(executionService.run(anyString(), any(), any())).thenThrow(new RuntimeException("DSL 解析失败"));

        invoker.invokeHeadless(def(7L), Map.of("k", "v"));

        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        verify(failureAlertService, times(1)).alertDispatchFailure(any(), reasonCap.capture());
        assertEquals("DSL 解析失败", reasonCap.getValue(), "reason 应为 ex.getMessage() 原文（红线第 7 条）");
    }

    @Test
    @DisplayName("B5-4：sanitizer 抛错 fail-close → 也发派发前失败告警")
    void invokeHeadless_sanitizerThrows_alertsDispatchFailure() {
        stubInputVar(null);
        doThrow(new IllegalStateException("sanitizer boom"))
                .when(inputSanitizer).sanitizePayload(any(), any());

        invoker.invokeHeadless(def(7L, "webhook"), Map.of("k", "v"));

        verify(failureAlertService, times(1)).alertDispatchFailure(any(), eq("sanitizer boom"));
    }

    @Test
    @DisplayName("B5-4：成功派发 → 不发派发前失败告警")
    void invokeHeadless_success_noDispatchAlert() {
        stubInputVar(null);
        when(executionService.run(anyString(), any(), any())).thenReturn(Flux.empty());

        invoker.invokeHeadless(def(7L), Map.of("k", "v"));

        verify(failureAlertService, never()).alertDispatchFailure(any(), any());
    }

    @Test
    @DisplayName("B5-4：Flux onError（图内异步失败）→ 不发派发前告警（那归 settle 的 alertExecutionFailure）")
    void invokeHeadless_fluxError_noDispatchAlert() {
        stubInputVar(null);
        when(executionService.run(anyString(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("graph fail")));

        invoker.invokeHeadless(def(7L), Map.of("k", "v"));

        // 派发本身成功了（已 subscribe），图内失败是异步的、由 settle 写执行行 + alertExecutionFailure 告警，
        // 不归派发前告警管 —— 否则同一次失败会被两条告警面各发一遍
        verify(failureAlertService, never()).alertDispatchFailure(any(), any());
    }

}
