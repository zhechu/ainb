package cn.enlev.ainb.module.agent.service.workflow;

import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentWebhookProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.TriggerDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerInvoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_WEBHOOK_BODY_TOO_LARGE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_WEBHOOK_NOT_FOUND;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_WEBHOOK_SIGNATURE_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentWebhookTriggerServiceImpl} 单测（增量14c / P6，纯 mock，0 token）
 *
 * <p>钉住匿名入站面的四件事：① 反查不命中 → 抛 {@code WORKFLOW_WEBHOOK_NOT_FOUND}、绝不拉起；
 * ② 验签 fail-close——hmac/token 缺失或不符一律拒（抛 {@code WORKFLOW_WEBHOOK_SIGNATURE_INVALID}）、invoker 一次都不调；
 * ③ 验签通过（含 {@code sha256=} 前缀）→ 以发布者身份无头拉起；④ 请求体 JSON 对象摊平进 payload、非 JSON 包 {@code raw}。
 *
 * <p>B2-4 追加 ⓪：body 整体超上限 → 抛 {@code WORKFLOW_WEBHOOK_BODY_TOO_LARGE}，
 * 且这道闸<b>排在反查与验签之前</b>（顺序本身是被断言的，见该节注释）。
 *
 * <p>🔴 这里只验「服务层的路由与验签逻辑」，不碰真 DB / 真 HTTP：mapper、invoker、dslParser 全 mock。
 * 租户隔离（ambient tenant-id 头）与 {@code @PermitAll} 免登录属框架/Controller 层，由运行时 curl 差分验（见计划）。
 *
 * @author 走进新时代
 */
@DisplayName("agent Webhook 入站触发服务")
class AgentWebhookTriggerServiceTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentWebhookTriggerServiceImpl webhookService;

    @Mock
    private AgentWorkflowDefMapper defMapper;
    @Mock
    private WorkflowTriggerInvoker triggerInvoker;
    @Mock
    private DslParser dslParser;
    /**
     * 🔴 必须是 {@code @Spy} 而不是 {@code @Mock}：mock 的 {@code getMaxBodyLength()} 返回 null，
     * 那道闸就变成「全拒」，本节之外所有既有用例都会连带变红，而红的原因与被测逻辑无关。
     * spy 挂在真对象上 ⟹ 拿到真默认值（1 MiB），只在需要改档位的用例里显式 set。
     */
    @Spy
    private AgentWebhookProperties webhookProperties = new AgentWebhookProperties();

    private static final String SECRET = "s3cr3t-key";

    /** 造一条已发布 webhook 流程 DO（不落桩，纯构造）；给 {@link #stubDef} 与需要 lenient 的用例共用 */
    private static AgentWorkflowDefDO buildDef(String path) {
        AgentWorkflowDefDO def = new AgentWorkflowDefDO();
        def.setId(100L);
        def.setCode("wf_hook");
        def.setDsl("{\"id\":\"wf_hook\"}");
        def.setTriggerType("webhook");
        def.setTriggerKey(path);
        def.setOwnerUserId(7L);
        return def;
    }

    /** 造一个带指定 webhook config 的解析结果（不落桩，纯构造） */
    private static WorkflowDefDTO buildDto(Map<String, Object> config) {
        TriggerDTO trigger = new TriggerDTO();
        trigger.setType("trigger.webhook");
        trigger.setConfig(config);
        WorkflowDefDTO dto = new WorkflowDefDTO();
        dto.setTrigger(trigger);
        return dto;
    }

    /** 让 mapper 按 path 反查返回一条已发布 webhook 流程（dsl 内容无所谓，config 由 dslParser mock 提供） */
    private AgentWorkflowDefDO stubDef(String path) {
        AgentWorkflowDefDO def = buildDef(path);
        when(defMapper.selectPublishedByWebhookKey(path)).thenReturn(def);
        return def;
    }

    /** 让 dslParser 解析任意 dsl 都返回带指定 webhook config 的 trigger */
    private void stubConfig(Map<String, Object> config) {
        when(dslParser.parse(anyString())).thenReturn(buildDto(config));
    }

    private static String hmacHex(String secret, String body) {
        return new HMac(HmacAlgorithm.HmacSHA256, secret.getBytes(StandardCharsets.UTF_8)).digestHex(body);
    }

    // ==================== ⓪ body 整体超限（B2-4 第一道闸）====================
    // 🔴 本节真正要钉的不只是「超了会拒」，而是【这道闸排在反查与验签之前】这个顺序本身：
    //    顺序对了，才有「拿一个不存在的 path 就能实测超大 body 被拒」这个性质 ——
    //    不必为它发布一条真的 webhook 流程、不必写执行历史、更不会烧 token。
    //    所以下面第一条用例刻意【不 stub 任何 def】，靠 NOT_FOUND 没被抛出来证明闸门更靠前。

    @Test
    @DisplayName("🔴 B2-4：body 超整体上限 → 抛 BODY_TOO_LARGE，且【反查都没发生】（闸门排在最前）")
    void handleWebhook_bodyOverCeiling_rejectedBeforeLookup() {
        webhookProperties.setMaxBodyLength(16);
        String oversized = "0123456789ABCDEFG"; // 17 字符

        // 🔴 注意：这里【没有】stubDef("ghost")，mapper 对 "ghost" 会返回 null。
        //    若闸门排在反查之后，本用例拿到的会是 NOT_FOUND —— 断言就会红。
        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("ghost", oversized, null, null));
        assertEquals(WORKFLOW_WEBHOOK_BODY_TOO_LARGE.getCode(), ex.getCode(),
                "拿到 NOT_FOUND 说明闸门被挪到反查之后了；那道顺序是刻意排的，别改");
        verify(defMapper, never()).selectPublishedByWebhookKey(anyString());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("🔴 B2-4：对一条真实存在、配了 hmac 的已发布流程，超大 body 同样先被拒（反查与验签都没轮到）")
    void handleWebhook_bodyOverCeiling_rejectedBeforeSignatureCheck() {
        // 🔴 这两条桩【刻意用 lenient】：它们注定不会被消费，而「不会被消费」正是本用例要断言的结论。
        //    严格模式下 Mockito 会以 UnnecessaryStubbing 把它报成错误 —— 那个报错在这儿不是缺陷，是证据。
        //    保留桩（而不是干脆不 stub）是为了让「path 是真的、hmac 也是真的配了」这个前提在代码里看得见：
        //    本用例要说的是「即使对面是一条货真价实的已发布流程，超大 body 也照样在最前面被挡掉」。
        lenient().when(defMapper.selectPublishedByWebhookKey("secure_hook")).thenReturn(buildDef("secure_hook"));
        lenient().when(dslParser.parse(anyString()))
                .thenReturn(buildDto(Map.of("authType", "hmac", "secret", SECRET)));
        webhookProperties.setMaxBodyLength(16);

        // 🔴 刻意【不带签名】：若闸门排在验签之后，这里会是 SIGNATURE_INVALID 而不是 BODY_TOO_LARGE。
        //    这条顺序有实际意义 —— 匿名方不该能用一个超大 body 换到我们去做一次 DB 查询 + DSL 解析 + HMAC 计算
        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("secure_hook", "0123456789ABCDEFG", null, null));
        assertEquals(WORKFLOW_WEBHOOK_BODY_TOO_LARGE.getCode(), ex.getCode());
        // 「反查与验签都没轮到」的直接证据：两个协作者一次都没被碰过
        verify(defMapper, never()).selectPublishedByWebhookKey(anyString());
        verify(dslParser, never()).parse(anyString());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("🔴 B2-4：上限取自配置而非写死的常量（把档位调到 16，17 字符就该被拒）")
    void handleWebhook_bodyCeilingReadFromConfig() {
        // 默认档是 1 MiB，17 字符本该轻松通过；本用例把它压到 16 后必须被拒 ⟹ 证明读的是配置
        webhookProperties.setMaxBodyLength(16);
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("ghost", "0123456789ABCDEFG", null, null));
        assertEquals(WORKFLOW_WEBHOOK_BODY_TOO_LARGE.getCode(), rejected.getCode());

        // 反向：把档位放宽后同样这 17 字符就不再被这道闸拦（会继续往下走到 NOT_FOUND）
        webhookProperties.setMaxBodyLength(1024);
        ServiceException passedTheGate = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("ghost", "0123456789ABCDEFG", null, null));
        assertEquals(WORKFLOW_WEBHOOK_NOT_FOUND.getCode(), passedTheGate.getCode(),
                "放宽档位后应换成 NOT_FOUND —— 说明上一条真的是被【这道闸】拦下的，不是别的什么原因");
    }

    @Test
    @DisplayName("B2-4 边界：body 长度【恰好等于】上限 → 放行（比较是 > 不是 >=）")
    void handleWebhook_bodyExactlyAtCeiling_passes() {
        webhookProperties.setMaxBodyLength(16);
        String exactlyAtCeiling = "0123456789ABCDEF"; // 16 字符，与上限相等

        // 没 stub def ⟹ 走过闸门后会撞上 NOT_FOUND。抛的是 NOT_FOUND 而不是 BODY_TOO_LARGE，就是「放行了」的证据
        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("ghost", exactlyAtCeiling, null, null));
        assertEquals(WORKFLOW_WEBHOOK_NOT_FOUND.getCode(), ex.getCode(),
                "恰好等于上限不算超：合法调用方按文档上限凑满一个 body 时不该被拒");
    }

    @Test
    @DisplayName("B2-4：body 为 null（@RequestBody(required=false) 时可能）→ 不算超限，照常往下走")
    void handleWebhook_nullBody_notRejectedByCeiling() {
        webhookProperties.setMaxBodyLength(16);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("ghost", null, null, null));
        assertEquals(WORKFLOW_WEBHOOK_NOT_FOUND.getCode(), ex.getCode(),
                "null body 长度为 0，不该被这道闸拦；也不该在这里 NPE");
    }

    // ==================== ① 反查不命中 ====================

    @Test
    @DisplayName("path 无对应已发布流程 → 抛 NOT_FOUND，绝不拉起（防匿名枚举探测）")
    void handleWebhook_notFound_throws() {
        when(defMapper.selectPublishedByWebhookKey("ghost")).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("ghost", "{}", null, null));
        assertEquals(WORKFLOW_WEBHOOK_NOT_FOUND.getCode(), ex.getCode());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("path 空白 → 抛 NOT_FOUND（不查库）")
    void handleWebhook_blankPath_throws() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("  ", "{}", null, null));
        assertEquals(WORKFLOW_WEBHOOK_NOT_FOUND.getCode(), ex.getCode());
        verify(defMapper, never()).selectPublishedByWebhookKey(anyString());
    }

    // ============ ② authType=none / 缺省：一律拒（#72 起运行期不再有免签档） ============

    /**
     * 🔴 #72 反转：这一节原来叫「authType=none：免签放行」，三条断言全是「放行」。
     * 「靠不可猜的 path 保安全」那个前提是假的（path 会进日志、进前端 URL、进浏览器历史与代理缓存），
     * 而免签的 hook 会以<b>发布者身份</b>把请求体一路灌进 state → prompt → BPM formVars ⟹ 只能删掉这一档。
     *
     * <p>原来那两条 payload 断言（JSON 摊平、非 JSON 包 raw）本身是对的，只是借 none 抄近路到达；
     * 已改挂到合法的 hmac 配置上，见下面 ③ 节的 {@code handleWebhook_hmac_jsonBodyFlattenedIntoPayload}
     * 与 {@code handleWebhook_hmac_nonJsonBody_wrappedRaw}。
     */
    @Test
    @DisplayName("authType=none → 抛 SIGNATURE_INVALID，且绝不无头拉起")
    void handleWebhook_authNone_throws() {
        stubDef("order_hook");
        stubConfig(Map.of("authType", "none", "secret", SECRET));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("order_hook", "{\"orderId\":\"A1\"}", null, null));
        assertEquals(WORKFLOW_WEBHOOK_SIGNATURE_INVALID.getCode(), ex.getCode());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("authType 缺省（config 无这一格）→ 同样抛，不再归一化成免签放行")
    void handleWebhook_missingAuthType_throws() {
        stubDef("hook_x");
        stubConfig(Map.of("path", "hook_x"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("hook_x", "{}", null, null));
        assertEquals(WORKFLOW_WEBHOOK_SIGNATURE_INVALID.getCode(), ex.getCode());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    /** 空 config：authType 取出来是 null，必须落进 default 被拒，而不是抛 NPE（switch(null) 的老坑） */
    @Test
    @DisplayName("config 为空 map（DSL 无 trigger/config）→ 抛 SIGNATURE_INVALID，不是 NPE")
    void handleWebhook_emptyConfig_throws() {
        stubDef("hook_x");
        stubConfig(Map.of());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("hook_x", "{}", null, null));
        assertEquals(WORKFLOW_WEBHOOK_SIGNATURE_INVALID.getCode(), ex.getCode());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("大写 NONE / 前后带空白 → 抛（大小写与空白都不能成为绕过验签的路）")
    void handleWebhook_noneVariants_throw() {
        stubDef("hook_x");
        stubConfig(Map.of("authType", "NONE", "secret", SECRET));
        ServiceException upper = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("hook_x", "{}", null, null));
        assertEquals(WORKFLOW_WEBHOOK_SIGNATURE_INVALID.getCode(), upper.getCode());

        stubConfig(Map.of("authType", "  none  ", "secret", SECRET));
        ServiceException padded = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("hook_x", "{}", null, null));
        assertEquals(WORKFLOW_WEBHOOK_SIGNATURE_INVALID.getCode(), padded.getCode());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    // ==================== ③ authType=hmac：fail-close ====================

    @Test
    @DisplayName("hmac + 正确签名 → 拉起")
    void handleWebhook_hmac_validSignature_dispatches() {
        stubDef("secure_hook");
        stubConfig(Map.of("authType", "hmac", "secret", SECRET));
        when(triggerInvoker.invokeHeadless(any(), any())).thenReturn(true);
        String body = "{\"orderId\":\"A1\"}";

        boolean dispatched = webhookService.handleWebhook(
                "secure_hook", body, hmacHex(SECRET, body), null);

        assertTrue(dispatched);
        verify(triggerInvoker).invokeHeadless(any(), any());
    }

    /** 原挂在 authType=none 上的 payload 断言，#72 后改挂到合法 hmac 上（断言本身没变，只是不再借免签抄近路） */
    @Test
    @DisplayName("hmac 通过后：JSON 对象体摊平进 payload（作者用 input.orderId 取）")
    void handleWebhook_hmac_jsonBodyFlattenedIntoPayload() {
        AgentWorkflowDefDO def = stubDef("secure_hook");
        stubConfig(Map.of("authType", "hmac", "secret", SECRET));
        when(triggerInvoker.invokeHeadless(any(), any())).thenReturn(true);
        String body = "{\"orderId\":\"A1\",\"amount\":5}";

        assertTrue(webhookService.handleWebhook("secure_hook", body, hmacHex(SECRET, body), null));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(triggerInvoker).invokeHeadless(org.mockito.ArgumentMatchers.eq(def), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertEquals("A1", payload.get("orderId"), "JSON 对象字段摊平进 payload，作者用 input.orderId 取");
        assertEquals(5, payload.get("amount"));
    }

    @Test
    @DisplayName("hmac 通过后：非 JSON 体包成 {raw: body}（不丢原文，交流程自解析）")
    void handleWebhook_hmac_nonJsonBody_wrappedRaw() {
        stubDef("secure_hook");
        stubConfig(Map.of("authType", "hmac", "secret", SECRET));
        when(triggerInvoker.invokeHeadless(any(), any())).thenReturn(true);
        String body = "plain-text-payload";

        webhookService.handleWebhook("secure_hook", body, hmacHex(SECRET, body), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(triggerInvoker).invokeHeadless(any(), captor.capture());
        assertEquals("plain-text-payload", captor.getValue().get("raw"));
    }

    @Test
    @DisplayName("hmac + sha256= 前缀签名 → 剥前缀后照样通过")
    void handleWebhook_hmac_signatureWithPrefix_ok() {
        stubDef("secure_hook");
        stubConfig(Map.of("authType", "hmac", "secret", SECRET));
        when(triggerInvoker.invokeHeadless(any(), any())).thenReturn(true);
        String body = "{\"orderId\":\"A1\"}";

        assertTrue(webhookService.handleWebhook(
                "secure_hook", body, "sha256=" + hmacHex(SECRET, body), null));
        verify(triggerInvoker).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("hmac + 错误签名 → 抛 SIGNATURE_INVALID，绝不拉起（body 被篡改/密钥不符）")
    void handleWebhook_hmac_invalidSignature_throws() {
        stubDef("secure_hook");
        stubConfig(Map.of("authType", "hmac", "secret", SECRET));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("secure_hook", "{\"orderId\":\"A1\"}", "deadbeef", null));
        assertEquals(WORKFLOW_WEBHOOK_SIGNATURE_INVALID.getCode(), ex.getCode());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("hmac + 缺签名头 → 抛 SIGNATURE_INVALID（fail-close，不退化成免签）")
    void handleWebhook_hmac_missingSignature_throws() {
        stubDef("secure_hook");
        stubConfig(Map.of("authType", "hmac", "secret", SECRET));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("secure_hook", "{}", null, null));
        assertEquals(WORKFLOW_WEBHOOK_SIGNATURE_INVALID.getCode(), ex.getCode());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("hmac 但 DSL 无 secret（直改绕过落库校验）→ 抛 SIGNATURE_INVALID，绝不裸奔")
    void handleWebhook_hmac_noSecret_throws() {
        stubDef("secure_hook");
        stubConfig(Map.of("authType", "hmac"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("secure_hook", "{}", "whatever", null));
        assertEquals(WORKFLOW_WEBHOOK_SIGNATURE_INVALID.getCode(), ex.getCode());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    // ==================== ④ authType=token ====================

    @Test
    @DisplayName("token + 正确令牌 → 拉起")
    void handleWebhook_token_validToken_dispatches() {
        stubDef("token_hook");
        stubConfig(Map.of("authType", "token", "secret", "tok-123"));
        when(triggerInvoker.invokeHeadless(any(), any())).thenReturn(true);

        assertTrue(webhookService.handleWebhook("token_hook", "{}", null, "tok-123"));
        verify(triggerInvoker).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("token + 错误令牌 → 抛 SIGNATURE_INVALID")
    void handleWebhook_token_invalidToken_throws() {
        stubDef("token_hook");
        stubConfig(Map.of("authType", "token", "secret", "tok-123"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("token_hook", "{}", null, "wrong"));
        assertEquals(WORKFLOW_WEBHOOK_SIGNATURE_INVALID.getCode(), ex.getCode());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    // ==================== ⑤ 未知 authType：fail-close ====================

    @Test
    @DisplayName("运行期未知 authType（DSL 被直改）→ 抛 SIGNATURE_INVALID，绝不默认放行")
    void handleWebhook_unknownAuthType_throws() {
        stubDef("weird_hook");
        stubConfig(Map.of("authType", "oauth2", "secret", "x"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> webhookService.handleWebhook("weird_hook", "{}", "sig", "tok"));
        assertEquals(WORKFLOW_WEBHOOK_SIGNATURE_INVALID.getCode(), ex.getCode());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    // ==================== ⑥ owner 缺失：invoker 内部 fail-close，返回 false ====================

    @Test
    @DisplayName("invoker 返回 false（发布者身份缺失等）→ 服务原样返回 false，不抛")
    void handleWebhook_invokerReturnsFalse_propagates() {
        stubDef("order_hook");
        // #72 后没有免签档可借：要走完验签、只让 invoker 这一环返回 false，才能测到「原样透传」这件事
        stubConfig(Map.of("authType", "hmac", "secret", SECRET));
        when(triggerInvoker.invokeHeadless(any(), any())).thenReturn(false);
        String body = "{}";

        assertEquals(false, webhookService.handleWebhook("order_hook", body, hmacHex(SECRET, body), null));
    }

}
