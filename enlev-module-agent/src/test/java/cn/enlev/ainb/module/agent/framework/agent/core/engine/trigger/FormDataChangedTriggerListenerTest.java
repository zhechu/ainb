package cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger;

import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.infra.api.formdesign.event.FormDataChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FormDataChangedTriggerListener} 单测（增量6，纯 mock，0 token）
 *
 * <p>钉住 form_event 触发的四条：① 命中 N 个订阅定义 → invoker 被调 N 次、payload 为
 * {@code {formCode, eventType(小写), dataId}}（广播语义）；② 🔴 triggerKey 后缀统一小写——事件 eventType
 * 大小写不敏感，与 extractor 落库的小写键逐字对齐，否则失配；③ 空匹配静默返回、不调 invoker；
 * ④ 🔴 invoker 抛错被监听器 try/catch 吞掉绝不外泄——这是「触发失败绝不波及用户已提交表单数据」的底线。
 *
 * <p>⚠️ 事务语义（AFTER_COMMIT、提交后才回调）无法在纯 mock 单测里验证，由 e2e-A 用真实表单写入覆盖；
 * 此处只验监听器被回调后的匹配/派发/容错逻辑。
 *
 * @author 走进新时代
 */
@DisplayName("agent FormDataChangedTriggerListener form_event 触发")
class FormDataChangedTriggerListenerTest {

    private FormDataChangedTriggerListener listener;
    private AgentWorkflowDefMapper defMapper;
    private WorkflowTriggerInvoker triggerInvoker;

    @BeforeEach
    void setUp() {
        defMapper = mock(AgentWorkflowDefMapper.class);
        triggerInvoker = mock(WorkflowTriggerInvoker.class);
        listener = new FormDataChangedTriggerListener();
        ReflectionTestUtils.setField(listener, "defMapper", defMapper);
        ReflectionTestUtils.setField(listener, "triggerInvoker", triggerInvoker);
    }

    private static AgentWorkflowDefDO def(String code) {
        AgentWorkflowDefDO def = new AgentWorkflowDefDO();
        def.setCode(code);
        def.setTriggerType("form_event");
        return def;
    }

    @Test
    @DisplayName("命中 2 个订阅定义 → invoker 调 2 次，payload={formCode,eventType小写,dataId}")
    void onFormChanged_broadcastsToAllSubscribers() {
        when(defMapper.selectPublishedByFormEventTrigger("test_demo_customer:create"))
                .thenReturn(List.of(def("wf_a"), def("wf_b")));
        FormDataChangedEvent event =
                new FormDataChangedEvent("test_demo_customer", "create", "123", 1L);

        listener.onFormChanged(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCap = ArgumentCaptor.forClass(Map.class);
        verify(triggerInvoker, times(2)).invokeHeadless(any(AgentWorkflowDefDO.class), payloadCap.capture());
        Map<String, Object> payload = payloadCap.getAllValues().get(0);
        assertEquals("test_demo_customer", payload.get("formCode"));
        assertEquals("create", payload.get("eventType"));
        assertEquals("123", payload.get("dataId"));
        assertEquals(payload, payloadCap.getAllValues().get(1), "两个订阅方拿到同一份 payload");
    }

    @Test
    @DisplayName("🔴 eventType 大写 → triggerKey 后缀小写，与 extractor 落库键对齐")
    void onFormChanged_lowercasesEventTypeInKey() {
        FormDataChangedEvent event = new FormDataChangedEvent("test_demo_customer", "CREATE", "9", 1L);

        listener.onFormChanged(event);

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(defMapper).selectPublishedByFormEventTrigger(keyCap.capture());
        assertEquals("test_demo_customer:create", keyCap.getValue());
    }

    @Test
    @DisplayName("空匹配（该表单无 form_event 订阅）→ 静默返回，不调 invoker")
    void onFormChanged_noMatch_noInvoke() {
        when(defMapper.selectPublishedByFormEventTrigger(anyString())).thenReturn(List.of());
        FormDataChangedEvent event = new FormDataChangedEvent("lonely_form", "create", "1", 1L);

        listener.onFormChanged(event);

        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

    @Test
    @DisplayName("🔴 invoker 抛错 → 监听器 try/catch 吞掉，绝不外泄（保护用户已提交的表单写入）")
    void onFormChanged_invokerThrows_swallowed() {
        when(defMapper.selectPublishedByFormEventTrigger(anyString())).thenReturn(List.of(def("wf_a")));
        when(triggerInvoker.invokeHeadless(any(), any())).thenThrow(new RuntimeException("boom"));
        FormDataChangedEvent event = new FormDataChangedEvent("fc", "create", "1", 1L);

        assertDoesNotThrow(() -> listener.onFormChanged(event),
                "触发失败绝不能外抛——此刻用户数据已提交，不能让它看到「表单提交失败」");
    }

    @Test
    @DisplayName("事件缺 formCode/eventType → 跳过，不查库")
    void onFormChanged_missingFormCodeOrEventType_skips() {
        listener.onFormChanged(new FormDataChangedEvent(null, "create", "1", 1L));
        listener.onFormChanged(new FormDataChangedEvent("fc", null, "1", 1L));

        verify(defMapper, never()).selectPublishedByFormEventTrigger(anyString());
        verify(triggerInvoker, never()).invokeHeadless(any(), any());
    }

}
