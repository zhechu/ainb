package cn.enlev.ainb.module.infra.service.formdesign.runtime;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.relation.RelationFormResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 关联表单带出字段的对抗性测试
 *
 * 关联表单的典型用法是「选一个客户，带出客户的信用额度、结算方式」。
 * 这些带出值随表单一起提交 —— 也就意味着可以被随意改掉：
 * 选一个信用额度 1 万的客户，把带出的额度字段改成 100 万再提交。
 *
 * 这些用例验证服务端是不是真的重新读了一遍并覆盖。
 *
 * @author 走进新时代
 */
public class RelationFormFillerTest extends BaseMockitoUnitTest {

    @InjectMocks
    private RelationFormFiller filler;

    /** 用真实解析器：要守的是「映射表怎么配，服务端就怎么带出」这条链路 */
    @Spy
    private RelationFormResolver resolver = new RelationFormResolver();

    @Mock
    private FormDesignRuntimeService runtimeService;

    /**
     * 造一张带关联控件的表单
     *
     * @param mappings 形如 "sourceField:targetField:updateMode"
     */
    private FormDesignDO formWithRelation(String relationField, String targetCode,
                                          String... mappings) {
        StringBuilder mappingJson = new StringBuilder("[");
        for (int i = 0; i < mappings.length; i++) {
            String[] parts = mappings[i].split(":");
            if (i > 0) {
                mappingJson.append(",");
            }
            mappingJson.append(String.format(
                    "{\"sourceField\":\"%s\",\"targetField\":\"%s\",\"updateMode\":\"%s\"}",
                    parts[0], parts[1], parts.length > 2 ? parts[2] : "ALWAYS"));
        }
        mappingJson.append("]");

        String rule = String.format(
                "{\"type\":\"RelationForm\",\"field\":\"%s\",\"title\":\"关联\","
                        + "\"props\":{\"targetFormCode\":\"%s\",\"displayField\":\"name\","
                        + "\"fieldMappings\":%s}}",
                relationField, targetCode, mappingJson);

        FormDesignDO design = new FormDesignDO();
        design.setCode("current_form");
        design.setFormFields(FormFieldsCodec.write(List.of(rule)));
        return design;
    }

    private Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    // ==================== 篡改必须被覆盖 ====================

    @Test
    @DisplayName("⚠️ 篡改带出字段的提交值 → 服务端重新读一遍并覆盖")
    void testTamperedMappedValueOverwritten() {
        FormDesignDO design = formWithRelation("customerId", "customer", "creditLimit:credit");
        // 服务端读到的真实额度是 10000
        when(runtimeService.get(eq("customer"), any()))
                .thenReturn(data("creditLimit", 10000, "name", "某客户"));

        // 攻击者把带出的额度改成 1000000
        Map<String, Object> submitted = data("customerId", 1, "credit", 1000000);

        Map<String, Object> result = filler.fill(design, submitted);

        assertEquals(10000, result.get("credit"),
                "带出值必须以服务端读到的为准，否则选个小额度客户改一下就能提交大额单据");
        assertEquals(1, result.get("customerId"), "关联主键本身是合法提交内容，不动它");
    }

    @Test
    @DisplayName("前端漏传带出字段时，服务端补上")
    void testMissingMappedValueFilled() {
        FormDesignDO design = formWithRelation("customerId", "customer", "settleType:settle");
        when(runtimeService.get(eq("customer"), any())).thenReturn(data("settleType", "月结"));

        Map<String, Object> result = filler.fill(design, data("customerId", 1));

        assertEquals("月结", result.get("settle"));
    }

    @Test
    @DisplayName("⚠️ 关联主键被清空时，带出字段一并清空")
    void testClearedRelationClearsMappedFields() {
        FormDesignDO design = formWithRelation("customerId", "customer", "name:customerName");

        Map<String, Object> result = filler.fill(design,
                data("customerId", null, "customerName", "上次带出的客户名"));

        assertNull(result.get("customerName"),
                "留着上次带出的值会出现「关联已清空但客户名还在」这种自相矛盾的数据");
        // 没选关联记录时不该去查库
        verify(runtimeService, never()).get(anyString(), any());
    }

    // ==================== 更新方式 ====================

    @Test
    @DisplayName("ONCE 模式：目标字段已有值时不覆盖")
    void testOnceModeKeepsExistingValue() {
        FormDesignDO design = formWithRelation("customerId", "customer", "remark:note:ONCE");
        when(runtimeService.get(eq("customer"), any())).thenReturn(data("remark", "客户默认备注"));

        Map<String, Object> result = filler.fill(design,
                data("customerId", 1, "note", "用户手工改过的备注"));

        assertEquals("用户手工改过的备注", result.get("note"),
                "ONCE 的语义就是「只在空的时候带出」，覆盖掉用户手填的内容是数据丢失");
    }

    @Test
    @DisplayName("ONCE 模式：目标字段为空时照样带出")
    void testOnceModeFillsWhenEmpty() {
        FormDesignDO design = formWithRelation("customerId", "customer", "remark:note:ONCE");
        when(runtimeService.get(eq("customer"), any())).thenReturn(data("remark", "客户默认备注"));

        Map<String, Object> result = filler.fill(design, data("customerId", 1, "note", ""));

        assertEquals("客户默认备注", result.get("note"));
    }

    @Test
    @DisplayName("更新方式写错时按 ALWAYS 处理 —— 那是最安全的语义")
    void testUnknownUpdateModeFallsBackToAlways() {
        FormDesignDO design = formWithRelation("customerId", "customer", "remark:note:NOT_A_MODE");
        when(runtimeService.get(eq("customer"), any())).thenReturn(data("remark", "服务端的值"));

        Map<String, Object> result = filler.fill(design, data("customerId", 1, "note", "前端的值"));

        assertEquals("服务端的值", result.get("note"),
                "带出值始终与关联记录一致，不会留下过期数据");
    }

    // ==================== 容错 ====================

    @Test
    @DisplayName("⚠️ 关联记录读不到（被删或无权）时保持原值，不让整次提交失败")
    void testUnreadableSourceRowTolerated() {
        FormDesignDO design = formWithRelation("customerId", "customer", "name:customerName");
        // 模拟无权限或记录不存在
        when(runtimeService.get(eq("customer"), any())).thenThrow(new RuntimeException("无权限"));

        Map<String, Object> result = assertDoesNotThrow(() ->
                filler.fill(design, data("customerId", 999, "customerName", "原值")));

        assertEquals("原值", result.get("customerName"),
                "主键值本身是合法提交内容，拿不到带出值不该让整次提交失败");
    }

    @Test
    @DisplayName("没配关联表单编码的控件被跳过")
    void testUnconfiguredRelationSkipped() {
        FormDesignDO design = new FormDesignDO();
        design.setCode("current_form");
        design.setFormFields(FormFieldsCodec.write(List.of(
                "{\"type\":\"RelationForm\",\"field\":\"r\",\"props\":{}}")));

        assertDoesNotThrow(() -> filler.fill(design, data("other", 1)));
        verify(runtimeService, never()).get(anyString(), any());
    }

    @Test
    @DisplayName("表单里没有关联控件时原样返回")
    void testNoRelationFields() {
        FormDesignDO design = new FormDesignDO();
        design.setFormFields(FormFieldsCodec.write(List.of("{\"type\":\"input\",\"field\":\"a\"}")));

        Map<String, Object> result = filler.fill(design, data("a", 1));

        assertEquals(1, result.get("a"));
        verify(runtimeService, never()).get(anyString(), any());
    }

    @Test
    @DisplayName("入参数据不被修改")
    void testInputNotMutated() {
        FormDesignDO design = formWithRelation("customerId", "customer", "name:customerName");
        when(runtimeService.get(eq("customer"), any())).thenReturn(data("name", "服务端"));

        Map<String, Object> submitted = data("customerId", 1, "customerName", "前端");
        filler.fill(design, submitted);

        assertEquals("前端", submitted.get("customerName"), "带出不得就地修改入参");
    }

    @Test
    @DisplayName("⚠️ 藏在布局容器里的关联控件也要被处理")
    void testRelationInsideLayoutContainer() {
        String inner = "{\"type\":\"RelationForm\",\"field\":\"customerId\",\"props\":"
                + "{\"targetFormCode\":\"customer\",\"fieldMappings\":"
                + "[{\"sourceField\":\"name\",\"targetField\":\"customerName\"}]}}";
        String nested = "{\"type\":\"fcRow\",\"children\":[{\"type\":\"col\",\"children\":["
                + inner + "]}]}";
        FormDesignDO design = new FormDesignDO();
        design.setCode("current_form");
        design.setFormFields(FormFieldsCodec.write(List.of(nested)));

        when(runtimeService.get(eq("customer"), any())).thenReturn(data("name", "服务端客户名"));

        Map<String, Object> result = filler.fill(design, data("customerId", 1, "customerName", "伪造"));

        assertEquals("服务端客户名", result.get("customerName"),
                "关联控件常被放进栅格，不递归就等于这些字段完全没有服务端保护");
    }

}
