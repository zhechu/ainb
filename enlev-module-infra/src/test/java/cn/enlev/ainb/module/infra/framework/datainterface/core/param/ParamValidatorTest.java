package cn.enlev.ainb.module.infra.framework.datainterface.core.param;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ParamValidator} 的单元测试
 *
 * 不依赖 Spring 上下文，只测校验与类型转换本身。
 * SqlParamBinder 的绑定测试需要登录上下文（内置变量），放到集成测试里。
 *
 * @author 走进新时代
 */
class ParamValidatorTest {

    private ParamDefinition def(String name, String type, boolean required) {
        ParamDefinition d = new ParamDefinition();
        d.setName(name);
        d.setLabel(name);
        d.setType(type);
        d.setRequired(required);
        return d;
    }

    private Map<String, Object> args(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("类型转换：字符串按定义转成目标类型")
    void testConvert() {
        List<ParamDefinition> defs = List.of(
                def("id", "LONG", false),
                def("qty", "INT", false),
                def("amount", "DECIMAL", false),
                def("enabled", "BOOLEAN", false),
                def("name", "STRING", false));
        Map<String, Object> result = ParamValidator.validateAndConvert(defs,
                args("id", "100", "qty", "3", "amount", "12.50", "enabled", "1", "name", "abc"));

        assertEquals(100L, result.get("id"));
        assertEquals(3, result.get("qty"));
        assertEquals(new BigDecimal("12.50"), result.get("amount"));
        assertEquals(true, result.get("enabled"));
        assertEquals("abc", result.get("name"));
    }

    @Test
    @DisplayName("必填校验：未传且无默认值则报错")
    void testRequired() {
        List<ParamDefinition> defs = List.of(def("status", "INT", true));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ParamValidator.validateAndConvert(defs, args()));
        assertTrue(ex.getMessage().contains("status"), ex.getMessage());
    }

    @Test
    @DisplayName("必填校验：空字符串也算未传")
    void testRequired_blankString() {
        List<ParamDefinition> defs = List.of(def("status", "INT", true));
        assertThrows(ServiceException.class,
                () -> ParamValidator.validateAndConvert(defs, args("status", "  ")));
    }

    @Test
    @DisplayName("默认值兜底：必填项有默认值时不报错")
    void testDefaultValue() {
        ParamDefinition d = def("status", "INT", true);
        d.setDefaultValue("1");
        Map<String, Object> result = ParamValidator.validateAndConvert(List.of(d), args());
        assertEquals(1, result.get("status"), "应用默认值兜底");
    }

    @Test
    @DisplayName("默认值不覆盖已传的值")
    void testDefaultValue_notOverride() {
        ParamDefinition d = def("status", "INT", false);
        d.setDefaultValue("1");
        Map<String, Object> result = ParamValidator.validateAndConvert(List.of(d), args("status", "2"));
        assertEquals(2, result.get("status"), "已传值优先");
    }

    @Test
    @DisplayName("非必填未传：值为 null，不报错")
    void testOptional() {
        List<ParamDefinition> defs = List.of(def("status", "INT", false));
        Map<String, Object> result = ParamValidator.validateAndConvert(defs, args());
        assertTrue(result.containsKey("status"), "key 应存在");
        assertNull(result.get("status"), "值为 null");
    }

    @Test
    @DisplayName("类型不匹配：报错而非静默转换")
    void testTypeInvalid() {
        List<ParamDefinition> defs = List.of(def("qty", "INT", false));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ParamValidator.validateAndConvert(defs, args("qty", "abc")));
        assertTrue(ex.getMessage().contains("qty"), ex.getMessage());
    }

    @Test
    @DisplayName("定义为空：返回空结果，不报错")
    void testEmptyDefinitions() {
        assertTrue(ParamValidator.validateAndConvert(null, args("a", "1")).isEmpty());
        assertTrue(ParamValidator.validateAndConvert(List.of(), args("a", "1")).isEmpty());
    }

    @Test
    @DisplayName("请求参数为 null：必填项仍报错")
    void testNullRequestArgs() {
        List<ParamDefinition> defs = List.of(def("status", "INT", true));
        assertThrows(ServiceException.class,
                () -> ParamValidator.validateAndConvert(defs, null));
    }

    @Test
    @DisplayName("⚠️ 未在定义中声明的请求参数被忽略，不会流入 SQL")
    void testUndeclaredParamIgnored() {
        List<ParamDefinition> defs = List.of(def("status", "INT", false));
        Map<String, Object> result = ParamValidator.validateAndConvert(defs,
                args("status", "1", "evil", "' OR 1=1 --"));
        assertFalse(result.containsKey("evil"), "未声明的参数不该出现在结果里");
        assertEquals(1, result.size());
    }

}
