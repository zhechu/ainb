package cn.enlev.ainb.module.infra.api.formdesign.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FormDataChangedEvent} 事件契约单测（增量6，纯 POJO，0 token）
 *
 * <p>钉住三件事：① 三个 eventType 常量的字面值——它们必须与 agent 侧 form_event 触发器构造的
 * {@code triggerKey} 后缀、以及 {@code WorkflowTriggerExtractor} 允许的 eventType 取值逐字一致，
 * 任何一侧改了字面值都会让触发器静默失配；② 全参构造 + getter（{@code @RequiredArgsConstructor} 按字段声明序）；
 * ③ toString 含关键字段（日志排查用）。
 *
 * <p>⚠️ 发布链（infra create/update/delete 真实广播本事件）由 e2e-A 用真实表单写入覆盖，
 * 此处不 mock 重服务 {@code FormDesignRuntimeServiceImpl}（见计划 R6）。
 *
 * @author 走进新时代
 */
@DisplayName("infra FormDataChangedEvent 事件契约")
class FormDataChangedEventTest {

    @Test
    @DisplayName("eventType 常量字面值与 agent 触发器键后缀一致")
    void eventTypeConstants_matchTriggerKeySemantics() {
        assertEquals("create", FormDataChangedEvent.TYPE_CREATE);
        assertEquals("update", FormDataChangedEvent.TYPE_UPDATE);
        assertEquals("delete", FormDataChangedEvent.TYPE_DELETE);
    }

    @Test
    @DisplayName("全参构造按声明序 (formCode,eventType,dataId,tenantId)，getter 逐字段返回")
    void constructor_exposesAllFieldsViaGetter() {
        FormDataChangedEvent event =
                new FormDataChangedEvent("test_demo_customer", "create", "123", 1L);

        assertEquals("test_demo_customer", event.getFormCode());
        assertEquals("create", event.getEventType());
        assertEquals("123", event.getDataId());
        assertEquals(1L, event.getTenantId().longValue());
    }

    @Test
    @DisplayName("dataId 为字符串化主键（对齐 FormDataChangeLogDO.dataId 口径）")
    void dataId_isStringifiedPk() {
        FormDataChangedEvent event =
                new FormDataChangedEvent("fc", "delete", String.valueOf(9007199254740993L), 2L);

        assertEquals("9007199254740993", event.getDataId());
    }

    @Test
    @DisplayName("toString 含 formCode 与 eventType，便于日志排查")
    void toString_containsKeyFields() {
        FormDataChangedEvent event = new FormDataChangedEvent("my_form", "update", "9", 2L);

        String text = event.toString();
        assertTrue(text.contains("my_form"), "toString 应含 formCode");
        assertTrue(text.contains("update"), "toString 应含 eventType");
    }

}
