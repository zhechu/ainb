package cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.TriggerDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_TRIGGER_CONFIG_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowTriggerExtractor} 单测（增量6，纯 mock，0 token）
 *
 * <p>钉住触发器投影的命门：① 🔴 归一化——DSL 全名 {@code trigger.form_event} 落库成短名 {@code form_event}
 * （与 mapper 查询逐字一致，写查不一致=触发器永远失配）；② form_event 的 {@code triggerKey={formCode}:{eventType小写}}；
 * ③ schedule 用 Spring CronExpression 校验 cron 并算出未来的 next_fire；④ 配置非法（缺 formCode、cron 不合法、
 * eventType 写错、未知类型）在<b>发布时</b> fail-loud 抛 {@code WORKFLOW_TRIGGER_CONFIG_INVALID}；
 * ⑤ chat 把 agentId 投影进 triggerKey（增量12 接线，未绑定则留 null）、webhook 把 path 投影进 triggerKey 并校验
 * 字符集/authType/secret（增量14c 接线）；⑥ 无 trigger 三个触发列留 null；⑦ ownerUserId 取自当前登录用户。
 *
 * @author 走进新时代
 */
@DisplayName("agent WorkflowTriggerExtractor 触发器提取")
class WorkflowTriggerExtractorTest {

    private WorkflowTriggerExtractor extractor;
    private DslParser dslParser;

    @BeforeEach
    void setUp() {
        dslParser = mock(DslParser.class);
        extractor = new WorkflowTriggerExtractor();
        ReflectionTestUtils.setField(extractor, "dslParser", dslParser);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 构造一份只含指定 trigger 的 DSL DTO，并让 mock 的 dslParser 解析任意 dsl 都返回它 */
    private void stubTrigger(String type, Map<String, Object> config) {
        TriggerDTO trigger = new TriggerDTO();
        trigger.setType(type);
        trigger.setConfig(config);
        WorkflowDefDTO dto = new WorkflowDefDTO();
        dto.setTrigger(trigger);
        when(dslParser.parse(anyString())).thenReturn(dto);
    }

    private void stubNoTrigger() {
        when(dslParser.parse(anyString())).thenReturn(new WorkflowDefDTO());
    }

    private static AgentWorkflowDefDO def() {
        AgentWorkflowDefDO def = new AgentWorkflowDefDO();
        def.setCode("wf_demo");
        def.setDsl("{}");
        return def;
    }

    private void setLoginUser(Long userId) {
        LoginUser user = new LoginUser();
        user.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()));
    }

    @Test
    @DisplayName("form_event：全名归一化为短名，triggerKey={formCode}:{eventType小写}，next_fire 为 null")
    void apply_formEvent_normalizesTypeAndBuildsKey() {
        stubTrigger("trigger.form_event", Map.of("formCode", "test_demo_customer", "eventType", "create"));

        AgentWorkflowDefDO def = def();
        extractor.apply(def);

        assertEquals("form_event", def.getTriggerType(), "🔴 必须落短名，与 mapper 查询逐字一致");
        assertEquals("test_demo_customer:create", def.getTriggerKey());
        assertNull(def.getTriggerNextFireAt(), "form_event 是事件驱动，无下次触发时间");
    }

    @Test
    @DisplayName("form_event：eventType 大小写不敏感，triggerKey 后缀统一小写")
    void apply_formEvent_lowercasesEventType() {
        stubTrigger("trigger.form_event", Map.of("formCode", "fc", "eventType", "UPDATE"));

        AgentWorkflowDefDO def = def();
        extractor.apply(def);

        assertEquals("fc:update", def.getTriggerKey());
    }

    @Test
    @DisplayName("form_event：缺 formCode → 发布时抛 WORKFLOW_TRIGGER_CONFIG_INVALID")
    void apply_formEvent_missingFormCode_throws() {
        stubTrigger("trigger.form_event", Map.of("eventType", "create"));

        ServiceException ex = assertThrows(ServiceException.class, () -> extractor.apply(def()));
        assertEquals(WORKFLOW_TRIGGER_CONFIG_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("form_event：eventType 写错(不在 create/update/delete) → 抛，防「配了永不触发且无提示」")
    void apply_formEvent_badEventType_throws() {
        stubTrigger("trigger.form_event", Map.of("formCode", "fc", "eventType", "insert"));

        ServiceException ex = assertThrows(ServiceException.class, () -> extractor.apply(def()));
        assertEquals(WORKFLOW_TRIGGER_CONFIG_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("schedule：triggerKey=cron，next_fire 由 CronExpression 算出且在未来")
    void apply_schedule_computesFutureNextFire() {
        stubTrigger("trigger.schedule", Map.of("cron", "0 0 * * * *"));
        LocalDateTime before = LocalDateTime.now();

        AgentWorkflowDefDO def = def();
        extractor.apply(def);

        assertEquals("schedule", def.getTriggerType());
        assertEquals("0 0 * * * *", def.getTriggerKey());
        assertNotNull(def.getTriggerNextFireAt(), "合法 cron 必须算出下次触发时间");
        assertTrue(def.getTriggerNextFireAt().isAfter(before), "下次触发时间应在未来");
    }

    @Test
    @DisplayName("schedule：cron 不合法(Quartz 的 ? 或乱写) → 抛，拒发布")
    void apply_schedule_badCron_throws() {
        stubTrigger("trigger.schedule", Map.of("cron", "not a cron"));

        ServiceException ex = assertThrows(ServiceException.class, () -> extractor.apply(def()));
        assertEquals(WORKFLOW_TRIGGER_CONFIG_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("schedule：缺 cron → 抛")
    void apply_schedule_missingCron_throws() {
        stubTrigger("trigger.schedule", Map.of());

        ServiceException ex = assertThrows(ServiceException.class, () -> extractor.apply(def()));
        assertEquals(WORKFLOW_TRIGGER_CONFIG_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("chat：triggerType=chat，agentId 投影进 triggerKey（供聊天路径反查驱动本 Agent 的 chatflow，增量12）")
    void apply_chat_projectsAgentIdToKey() {
        stubTrigger("trigger.chat", Map.of("agentId", "agent_x"));

        AgentWorkflowDefDO def = def();
        extractor.apply(def);

        assertEquals("chat", def.getTriggerType());
        assertEquals("agent_x", def.getTriggerKey(), "🔴 triggerKey=绑定的 Agent code，聊天路径按 code 反查");
        assertNull(def.getTriggerNextFireAt(), "chat 是会话驱动，无下次触发时间");
    }

    @Test
    @DisplayName("chat：无 agentId（兼容默认骨架）→ 仅记 triggerType，triggerKey 留 null，不抛")
    void apply_chat_noAgentId_recordsTypeOnly() {
        stubTrigger("trigger.chat", Map.of("inputVar", "input"));

        AgentWorkflowDefDO def = def();
        extractor.apply(def);

        assertEquals("chat", def.getTriggerType());
        assertNull(def.getTriggerKey(), "未绑定 Agent，trigger_key 留 null");
    }

    @Test
    @DisplayName("chat：agentId 为空白 → 归 null（视为未绑定，不落脏 key）")
    void apply_chat_blankAgentId_null() {
        stubTrigger("trigger.chat", Map.of("agentId", "   "));

        AgentWorkflowDefDO def = def();
        extractor.apply(def);

        assertEquals("chat", def.getTriggerType());
        assertNull(def.getTriggerKey());
    }

    @Test
    @DisplayName("webhook：triggerType=webhook，path 投影进 triggerKey（供匿名入站 /agent/webhook/{path} 反查，增量14c）")
    void apply_webhook_projectsPathToKey() {
        stubTrigger("trigger.webhook", Map.of("path", "order_hook", "authType", "hmac", "secret", "s3cr3t"));

        AgentWorkflowDefDO webhook = def();
        extractor.apply(webhook);

        assertEquals("webhook", webhook.getTriggerType());
        assertEquals("order_hook", webhook.getTriggerKey(), "🔴 triggerKey=path，入站按 path 反查");
        assertNull(webhook.getTriggerNextFireAt(), "webhook 是入站驱动，无下次触发时间");
    }

    @Test
    @DisplayName("webhook：authType=hmac + secret → 正常投影 path；secret 不进 triggerKey（只留 DSL）")
    void apply_webhook_hmacWithSecret_projectsPathOnly() {
        stubTrigger("trigger.webhook", Map.of("path", "secure_hook", "authType", "hmac", "secret", "s3cr3t"));

        AgentWorkflowDefDO webhook = def();
        extractor.apply(webhook);

        assertEquals("webhook", webhook.getTriggerType());
        assertEquals("secure_hook", webhook.getTriggerKey(), "triggerKey 只存 path，绝不含 secret");
    }

    @Test
    @DisplayName("webhook：缺 path → 落库前抛 WORKFLOW_TRIGGER_CONFIG_INVALID")
    void apply_webhook_missingPath_throws() {
        // 🔴 这里刻意带上合法鉴权：否则本条会因为「先撞上 authType 必填」而假绿，测不到 path 这一格
        stubTrigger("trigger.webhook", Map.of("authType", "hmac", "secret", "s3cr3t"));

        ServiceException ex = assertThrows(ServiceException.class, () -> extractor.apply(def()));
        assertEquals(WORKFLOW_TRIGGER_CONFIG_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("webhook：path 含斜杠/点（非单段 URL 安全字符）→ 抛，防畸形 path 拼进 URL")
    void apply_webhook_badPath_throws() {
        stubTrigger("trigger.webhook", Map.of("path", "/hook", "authType", "hmac", "secret", "s3cr3t"));
        ServiceException slash = assertThrows(ServiceException.class, () -> extractor.apply(def()));
        assertEquals(WORKFLOW_TRIGGER_CONFIG_INVALID.getCode(), slash.getCode());

        stubTrigger("trigger.webhook", Map.of("path", "a.b", "authType", "hmac", "secret", "s3cr3t"));
        ServiceException dot = assertThrows(ServiceException.class, () -> extractor.apply(def()));
        assertEquals(WORKFLOW_TRIGGER_CONFIG_INVALID.getCode(), dot.getCode());
    }

    @Test
    @DisplayName("webhook：authType=hmac 却无 secret → 抛（配了验签却没密钥，入站只能一律拒=等于没配）")
    void apply_webhook_hmacWithoutSecret_throws() {
        stubTrigger("trigger.webhook", Map.of("path", "secure_hook", "authType", "hmac"));

        ServiceException ex = assertThrows(ServiceException.class, () -> extractor.apply(def()));
        assertEquals(WORKFLOW_TRIGGER_CONFIG_INVALID.getCode(), ex.getCode());
    }

    /**
     * 🔴 #72：{@code authType=none} 已从白名单里删掉。原来这一档是<b>缺省值</b>，
     * 「靠不可猜的 path 保安全」那个前提在本平台是假的（path 会进日志、进前端 URL、进浏览器历史），
     * 而不鉴权的 hook 会以<b>发布者身份</b>无头拉起整条流程 ⟹ 只能禁掉，不能靠文案劝。
     */
    @Test
    @DisplayName("webhook：authType=none → 抛（平台已不支持不鉴权；这条原来是缺省值，现在连显式写都拒）")
    void apply_webhook_authTypeNone_throws() {
        stubTrigger("trigger.webhook", Map.of("path", "hook_x", "authType", "none", "secret", "s3cr3t"));

        ServiceException ex = assertThrows(ServiceException.class, () -> extractor.apply(def()));
        assertEquals(WORKFLOW_TRIGGER_CONFIG_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("hmac/token"), "文案要给出路，不能只说不行：" + ex.getMessage());
    }

    @Test
    @DisplayName("webhook：authType 缺省/空白 → 抛（缺省不再等于「不鉴权」，而是直接拒）")
    void apply_webhook_missingAuthType_throws() {
        stubTrigger("trigger.webhook", Map.of("path", "hook_x"));
        ServiceException missing = assertThrows(ServiceException.class, () -> extractor.apply(def()));
        assertEquals(WORKFLOW_TRIGGER_CONFIG_INVALID.getCode(), missing.getCode());

        stubTrigger("trigger.webhook", Map.of("path", "hook_x", "authType", "   ", "secret", "s3cr3t"));
        ServiceException blank = assertThrows(ServiceException.class, () -> extractor.apply(def()));
        assertEquals(WORKFLOW_TRIGGER_CONFIG_INVALID.getCode(), blank.getCode());
    }

    @Test
    @DisplayName("webhook：authType 大写 HMAC + secret → 放行（与运行期验签同样不区分大小写）")
    void apply_webhook_upperCaseAuthType_passes() {
        stubTrigger("trigger.webhook", Map.of("path", "hook_x", "authType", "HMAC", "secret", "s3cr3t"));

        AgentWorkflowDefDO webhook = def();
        extractor.apply(webhook);
        assertEquals("hook_x", webhook.getTriggerKey());
    }

    @Test
    @DisplayName("webhook：authType 未知值 → 抛，fail-loud（防静默放行本该鉴权的 hook）")
    void apply_webhook_unknownAuthType_throws() {
        stubTrigger("trigger.webhook", Map.of("path", "hook_x", "authType", "oauth2"));

        ServiceException ex = assertThrows(ServiceException.class, () -> extractor.apply(def()));
        assertEquals(WORKFLOW_TRIGGER_CONFIG_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("未知触发器类型 → 抛，fail-loud 拒落库")
    void apply_unknownType_throws() {
        stubTrigger("trigger.foo", Map.of());

        ServiceException ex = assertThrows(ServiceException.class, () -> extractor.apply(def()));
        assertEquals(WORKFLOW_TRIGGER_CONFIG_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("无 trigger：三个触发列留 null（历史 def/纯手动 run-stream）")
    void apply_noTrigger_leavesTriggerColumnsNull() {
        stubNoTrigger();

        AgentWorkflowDefDO def = def();
        extractor.apply(def);

        assertNull(def.getTriggerType());
        assertNull(def.getTriggerKey());
        assertNull(def.getTriggerNextFireAt());
    }

    @Test
    @DisplayName("ownerUserId 取自当前登录用户；无 trigger 时也记录（发布者优先）")
    void apply_setsOwnerFromLoginUser() {
        setLoginUser(7L);
        stubNoTrigger();

        AgentWorkflowDefDO def = def();
        extractor.apply(def);

        assertEquals(7L, def.getOwnerUserId().longValue());
    }

    @Test
    @DisplayName("无登录上下文（播种/测试）：ownerUserId 为 null，fire 时由 invoker fail-close")
    void apply_noLoginUser_ownerNull() {
        stubTrigger("trigger.form_event", Map.of("formCode", "fc", "eventType", "create"));

        AgentWorkflowDefDO def = def();
        extractor.apply(def);

        assertNull(def.getOwnerUserId());
        assertEquals("form_event", def.getTriggerType(), "触发列仍照常提取");
    }

}
