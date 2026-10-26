package cn.enlev.ainb.module.agent.tool.impl.formdata;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDataPageReqDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FormDataParamResolver} 的单元测试
 *
 * <p>这个类是五个 {@code form_data_*} 工具<b>共用</b>的入参解析。它一旦出错，五个工具一起出错。
 * 用例重点钉两类事：① 缺参数时报的是<b>业务异常</b>且带上工具名与缺的键（AI 靠它自修正）；
 * ② {@code data}/{@code conditions} 的<b>两种入参形态</b>（workflow 直传对象 vs 模型给的 JSON 串）都要认。
 *
 * @author 走进新时代
 */
public class FormDataParamResolverTest extends BaseMockitoUnitTest {

    private final FormDataParamResolver resolver = new FormDataParamResolver();

    // ==================== resolveCode ====================

    @Test
    @DisplayName("code：正常解析并 trim")
    public void testResolveCode() {
        assertEquals("customer", resolver.resolveCode(Map.of("code", "  customer  "), "form_data_get"));
    }

    @Test
    @DisplayName("code 缺失：业务异常，消息含工具名与 code")
    public void testResolveCodeMissing() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> resolver.resolveCode(Map.of(), "form_data_page"));
        assertTrue(ex.getMessage().contains("form_data_page"), ex.getMessage());
        assertTrue(ex.getMessage().contains("code"), ex.getMessage());
    }

    @Test
    @DisplayName("code 空白：等同缺失")
    public void testResolveCodeBlank() {
        assertThrows(ServiceException.class,
                () -> resolver.resolveCode(Map.of("code", "   "), "form_data_get"));
    }

    // ==================== resolvePk ====================

    @Test
    @DisplayName("pk：规范名 pk 解析")
    public void testResolvePkByName() {
        assertEquals("42", resolver.resolvePk(Map.of("pk", 42), "form_data_get"));
    }

    @Test
    @DisplayName("pk：别名 id 也认（Web UI 与 create 返回值用的都是 id）")
    public void testResolvePkByIdAlias() {
        assertEquals("7", resolver.resolvePk(Map.of("id", "7"), "form_data_update"));
    }

    @Test
    @DisplayName("pk：pk 与 id 都缺 → 业务异常，消息含 pk")
    public void testResolvePkMissing() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> resolver.resolvePk(Map.of("code", "customer"), "form_data_delete"));
        assertTrue(ex.getMessage().contains("pk"), ex.getMessage());
    }

    // ==================== resolveData ====================

    @Test
    @DisplayName("data：Map 形态（workflow 直传对象）原样解析，保留嵌套子表")
    public void testResolveDataFromMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "张三");
        data.put("age", 30);
        data.put("items", List.of(Map.of("sku", "A")));

        Map<String, Object> resolved = resolver.resolveData(Map.of("data", data), "form_data_create");

        assertEquals("张三", resolved.get("name"));
        assertEquals(30, resolved.get("age"));
        // 嵌套子表结构原样带下去，交给 infra 运行时处理
        assertTrue(resolved.get("items") instanceof List);
        assertEquals(1, ((List<?>) resolved.get("items")).size());
    }

    @Test
    @DisplayName("data：JSON 串形态（模型有时序列化后传）也能解析")
    public void testResolveDataFromJsonString() {
        Map<String, Object> data = resolver.resolveData(
                Map.of("data", "{\"name\":\"李四\",\"age\":18}"), "form_data_create");

        assertEquals("李四", data.get("name"));
        assertEquals(18, ((Number) data.get("age")).intValue());
    }

    @Test
    @DisplayName("data 缺失：业务异常，消息含 data")
    public void testResolveDataMissing() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> resolver.resolveData(Map.of("code", "customer"), "form_data_create"));
        assertTrue(ex.getMessage().contains("data"), ex.getMessage());
    }

    @Test
    @DisplayName("data 为空对象：等同缺失（新增/更新至少给一个字段）")
    public void testResolveDataEmpty() {
        assertThrows(ServiceException.class,
                () -> resolver.resolveData(Map.of("data", new LinkedHashMap<>()), "form_data_update"));
    }

    @Test
    @DisplayName("data 非法串：业务异常，不抛成系统错误")
    public void testResolveDataMalformed() {
        assertThrows(ServiceException.class,
                () -> resolver.resolveData(Map.of("data", "not-a-json"), "form_data_create"));
    }

    // ==================== resolvePageReq ====================

    @Test
    @DisplayName("分页：conditions（List<Map> 形态）逐字段搬运，operator 原样透传不校验")
    public void testResolvePageReqConditionsFromList() {
        Map<String, Object> cond = new LinkedHashMap<>();
        cond.put("field", "name");
        cond.put("operator", "LIKE");
        cond.put("value", "张");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("conditions", List.of(cond));

        FormDataPageReqDTO reqDTO = resolver.resolvePageReq(params, "form_data_page");

        assertEquals(1, reqDTO.getConditions().size());
        FormDataPageReqDTO.Condition c = reqDTO.getConditions().get(0);
        assertEquals("name", c.getField());
        assertEquals("LIKE", c.getOperator());
        assertEquals("张", c.getValue());
        assertNull(c.getValue2());
    }

    @Test
    @DisplayName("分页：conditions（JSON 串形态）也能解析；BETWEEN 带 value2")
    public void testResolvePageReqConditionsFromJsonString() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("conditions",
                "[{\"field\":\"age\",\"operator\":\"BETWEEN\",\"value\":18,\"value2\":60}]");

        FormDataPageReqDTO reqDTO = resolver.resolvePageReq(params, "form_data_page");

        assertEquals(1, reqDTO.getConditions().size());
        FormDataPageReqDTO.Condition c = reqDTO.getConditions().get(0);
        assertEquals("age", c.getField());
        assertEquals("BETWEEN", c.getOperator());
        assertEquals(18, ((Number) c.getValue()).intValue());
        assertEquals(60, ((Number) c.getValue2()).intValue());
    }

    @Test
    @DisplayName("分页：IN 的 value 是数组，原样带下去（infra 按 operator 解释）")
    public void testResolvePageReqInOperatorArrayValue() {
        Map<String, Object> cond = new LinkedHashMap<>();
        cond.put("field", "status");
        cond.put("operator", "IN");
        cond.put("value", List.of("A", "B"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("conditions", List.of(cond));

        FormDataPageReqDTO reqDTO = resolver.resolvePageReq(params, "form_data_page");

        Object value = reqDTO.getConditions().get(0).getValue();
        assertTrue(value instanceof List, "IN 的值应保持为列表，实际：" + value);
        assertEquals(2, ((List<?>) value).size());
    }

    @Test
    @DisplayName("分页：sort/pageNo/pageSize 解析；缺省时留 null 由 Impl 兜默认")
    public void testResolvePageReqSortAndPaging() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sortField", "create_time");
        params.put("sortOrder", "DESC");
        params.put("pageNo", 2);
        params.put("pageSize", "20");   // 字符串数字也要认

        FormDataPageReqDTO reqDTO = resolver.resolvePageReq(params, "form_data_page");

        assertEquals("create_time", reqDTO.getSortField());
        assertEquals("DESC", reqDTO.getSortOrder());
        assertEquals(2, reqDTO.getPageNo());
        assertEquals(20, reqDTO.getPageSize());
    }

    @Test
    @DisplayName("分页：什么都没给 → 全 null（pageNo/pageSize 的默认交给 Impl，不在解析层写死）")
    public void testResolvePageReqAllDefaultsNull() {
        FormDataPageReqDTO reqDTO = resolver.resolvePageReq(Map.of("code", "customer"), "form_data_page");

        assertNull(reqDTO.getConditions());
        assertNull(reqDTO.getSortField());
        assertNull(reqDTO.getPageNo());
        assertNull(reqDTO.getPageSize());
    }

    @Test
    @DisplayName("分页：pageSize 非整数 → 业务异常（不静默变默认，那会让模型困惑）")
    public void testResolvePageReqBadPageSize() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> resolver.resolvePageReq(Map.of("pageSize", "abc"), "form_data_page"));
        assertTrue(ex.getMessage().contains("pageSize"), ex.getMessage());
    }

    @Test
    @DisplayName("分页：conditions 非数组 → 业务异常")
    public void testResolvePageReqBadConditions() {
        assertThrows(ServiceException.class,
                () -> resolver.resolvePageReq(Map.of("conditions", "not-an-array"), "form_data_page"));
    }

    // ==================== 结构性存在检查（供 plan 期校验器用） ====================

    @Test
    @DisplayName("hasText/hasPk/hasNonEmptyData：只判断不抛异常")
    public void testPresenceHelpers() {
        assertTrue(FormDataParamResolver.hasText(Map.of("code", "customer"), "code"));
        assertFalse(FormDataParamResolver.hasText(Map.of("code", "  "), "code"));
        assertFalse(FormDataParamResolver.hasText(Map.of(), "code"));

        assertTrue(FormDataParamResolver.hasPk(Map.of("pk", "1")));
        assertTrue(FormDataParamResolver.hasPk(Map.of("id", "1")));   // 别名
        assertFalse(FormDataParamResolver.hasPk(Map.of("code", "x")));

        assertTrue(FormDataParamResolver.hasNonEmptyData(Map.of("data", Map.of("a", 1))));
        assertTrue(FormDataParamResolver.hasNonEmptyData(Map.of("data", "{\"a\":1}")));  // JSON 串
        assertFalse(FormDataParamResolver.hasNonEmptyData(Map.of("data", Map.of())));    // 空
        assertFalse(FormDataParamResolver.hasNonEmptyData(Map.of("data", "bad")));       // 非法
        assertFalse(FormDataParamResolver.hasNonEmptyData(Map.of()));                    // 缺失
    }

}
