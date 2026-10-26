package cn.enlev.ainb.module.agent.tool.impl.datainterface;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DataInterfaceParamResolver} 的单元测试
 *
 * <p>钉住：① code 缺失的报错口径（含工具名，模型自修正靠它）；② params 的三种形态
 * （缺省→空 Map、Map 直取、JSON 串兼容）与非法形态拒绝；③ limit 钳制 [1,50] 缺省 20
 * （目录 380+ 条，这是防 token 炸弹的闸门）；④ plan 期校验器复用的静态判断口径与执行期一致。
 *
 * @author 走进新时代
 */
public class DataInterfaceParamResolverTest extends BaseMockitoUnitTest {

    private final DataInterfaceParamResolver resolver = new DataInterfaceParamResolver();

    // ==================== resolveCode ====================

    @Test
    @DisplayName("resolveCode：正常 trim；缺失/空白 → PARAM_INVALID，消息含工具名与 code")
    public void testResolveCode() {
        assertEquals("q_read_sys", resolver.resolveCode(Map.of("code", " q_read_sys "), "data_interface_query"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> resolver.resolveCode(Map.of(), "data_interface_query"));
        assertTrue(ex.getMessage().contains("code"), ex.getMessage());
        assertTrue(ex.getMessage().contains("data_interface_query"), ex.getMessage());

        assertThrows(ServiceException.class,
                () -> resolver.resolveCode(Map.of("code", "  "), "data_interface_invoke"));
        assertThrows(ServiceException.class,
                () -> resolver.resolveCode(null, "data_interface_invoke"));
    }

    // ==================== resolveArgs ====================

    @Test
    @DisplayName("resolveArgs：缺省/空白 → 空 Map（很多接口无参，不抛）")
    public void testResolveArgsDefaultsToEmpty() {
        assertTrue(resolver.resolveArgs(Map.of(), "t").isEmpty());
        assertTrue(resolver.resolveArgs(Map.of("params", "  "), "t").isEmpty());
        assertTrue(resolver.resolveArgs(null, "t").isEmpty());
    }

    @Test
    @DisplayName("resolveArgs：Map 直取（workflow 原样透传形态），键转 String、保序")
    public void testResolveArgsFromMap() {
        Map<Object, Object> raw = new LinkedHashMap<>();
        raw.put("status", 1);
        raw.put(2, "x"); // 非 String 键也要收（转成字符串）
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("params", raw);

        Map<String, Object> args = resolver.resolveArgs(params, "t");

        assertEquals(2, args.size());
        assertEquals(1, args.get("status"));
        assertEquals("x", args.get("2"));
    }

    @Test
    @DisplayName("resolveArgs：JSON 串兼容（助手聊天里模型序列化形态）")
    public void testResolveArgsFromJsonString() {
        Map<String, Object> args = resolver.resolveArgs(
                Map.of("params", "{\"status\":1,\"name\":\"张三\"}"), "t");

        assertEquals(2, args.size());
        assertEquals(1, args.get("status"));
        assertEquals("张三", args.get("name"));
    }

    @Test
    @DisplayName("resolveArgs：非法形态（不是对象也不是 JSON 对象）→ PARAM_INVALID")
    public void testResolveArgsInvalid() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> resolver.resolveArgs(Map.of("params", "not-a-json"), "t"));
        assertTrue(ex.getMessage().contains("params"), ex.getMessage());

        assertThrows(ServiceException.class,
                () -> resolver.resolveArgs(Map.of("params", "[1,2,3]"), "t"));
    }

    // ==================== list 参数 ====================

    @Test
    @DisplayName("resolveKeyword：可选，trim；缺失/空白 → null")
    public void testResolveKeyword() {
        assertEquals("q_read", resolver.resolveKeyword(Map.of("keyword", " q_read ")));
        assertEquals(null, resolver.resolveKeyword(Map.of()));
        assertEquals(null, resolver.resolveKeyword(Map.of("keyword", "  ")));
        assertEquals(null, resolver.resolveKeyword(null));
    }

    @Test
    @DisplayName("resolveOnlyReadOnly：缺省 false；Boolean 与字符串都认")
    public void testResolveOnlyReadOnly() {
        assertFalse(resolver.resolveOnlyReadOnly(Map.of()));
        assertTrue(resolver.resolveOnlyReadOnly(Map.of("onlyReadOnly", true)));
        assertTrue(resolver.resolveOnlyReadOnly(Map.of("onlyReadOnly", "true")));
        assertFalse(resolver.resolveOnlyReadOnly(Map.of("onlyReadOnly", "false")));
        assertFalse(resolver.resolveOnlyReadOnly(null));
    }

    @Test
    @DisplayName("🔴 resolveLimit：缺省 20、钳制 [1,50]、非法值 PARAM_INVALID")
    public void testResolveLimit() {
        assertEquals(20, resolver.resolveLimit(Map.of(), "t"));
        assertEquals(20, resolver.resolveLimit(null, "t"));
        assertEquals(5, resolver.resolveLimit(Map.of("limit", 5), "t"));
        assertEquals(5, resolver.resolveLimit(Map.of("limit", "5"), "t"));
        assertEquals(50, resolver.resolveLimit(Map.of("limit", 100), "t"));  // 超上限钳到 50
        assertEquals(1, resolver.resolveLimit(Map.of("limit", 0), "t"));     // 低于下限钳到 1
        assertEquals(1, resolver.resolveLimit(Map.of("limit", -5), "t"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> resolver.resolveLimit(Map.of("limit", "many"), "t"));
        assertTrue(ex.getMessage().contains("limit"), ex.getMessage());
    }

    // ==================== plan 期静态判断（口径须与执行期一致） ====================

    @Test
    @DisplayName("hasText / hasValidParamsShape：校验器复用的静态判断")
    public void testStaticChecks() {
        assertTrue(DataInterfaceParamResolver.hasText(Map.of("code", "x"), "code"));
        assertFalse(DataInterfaceParamResolver.hasText(Map.of("code", " "), "code"));
        assertFalse(DataInterfaceParamResolver.hasText(null, "code"));

        // params 没给 → 合法（无参接口）
        assertTrue(DataInterfaceParamResolver.hasValidParamsShape(Map.of()));
        assertTrue(DataInterfaceParamResolver.hasValidParamsShape(null));
        // Map / JSON 对象串 → 合法
        assertTrue(DataInterfaceParamResolver.hasValidParamsShape(Map.of("params", Map.of("a", 1))));
        assertTrue(DataInterfaceParamResolver.hasValidParamsShape(Map.of("params", "{\"a\":1}")));
        // 非法形态 → 不合法（plan 期就该拦）
        assertFalse(DataInterfaceParamResolver.hasValidParamsShape(Map.of("params", "oops")));
    }

}
