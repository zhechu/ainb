package cn.enlev.ainb.module.infra.framework.formdesign.core.system;

import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 系统值控件提交值剥离的对抗性测试
 *
 * 这一组防的是最要紧的一类伪造：把创建人改成别人。
 * 创建人往往是数据权限、审批流转、责任追溯的依据 —— 能改它，
 * 就等于能把别人的数据变成自己的、或把自己的操作记到别人名下。
 *
 * @author 走进新时代
 */
class SystemValueGuardTest {

    private final SystemValueGuard guard = new SystemValueGuard();

    private String fields(String... rules) {
        return FormFieldsCodec.write(List.of(rules));
    }

    private String systemValueRule(String field, String source) {
        return String.format(
                "{\"type\":\"SystemValue\",\"field\":\"%s\",\"title\":\"%s\","
                        + "\"props\":{\"source\":\"%s\"}}",
                field, field, source);
    }

    private Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    // ==================== 剥离 ====================

    @Test
    @DisplayName("⚠️ 伪造创建人 → 提交值被剥离，落库交给后端自动填充")
    void testForgedCreatorStripped() {
        Map<String, Object> submitted = data(
                "creator", "我把创建人改成了别人",
                "remark", "正常字段");

        Map<String, Object> result = guard.strip(
                fields(systemValueRule("creator", "CREATOR")), submitted);

        assertFalse(result.containsKey("creator"),
                "创建人是数据权限与责任追溯的依据，前端提交的值绝不能进入落库路径");
        assertEquals("正常字段", result.get("remark"), "普通字段不受影响");
    }

    @Test
    @DisplayName("多个系统值字段一并剥离")
    void testMultipleFieldsStripped() {
        Map<String, Object> submitted = data(
                "creator", "伪造",
                "createTime", "2000-01-01",
                "updater", "伪造",
                "normal", 1);

        Map<String, Object> result = guard.strip(
                fields(
                        systemValueRule("creator", "CREATOR"),
                        systemValueRule("createTime", "CREATE_TIME"),
                        systemValueRule("updater", "UPDATER")),
                submitted);

        assertFalse(result.containsKey("creator"));
        assertFalse(result.containsKey("createTime"));
        assertFalse(result.containsKey("updater"));
        assertEquals(1, result.get("normal"));
    }

    @Test
    @DisplayName("⚠️ 展示型来源（当前登录人/当前时间）的字段同样被剥离")
    void testDisplayOnlySourcesStripped() {
        // 这些来源不落库，但它们同样是只读展示 —— 前端提交的值没有任何采纳的理由。
        // 放过去会让它们进入后续的联动判断与公式计算，等于给了前端一个可控输入
        Map<String, Object> result = guard.strip(
                fields(
                        systemValueRule("me", "CURRENT_USER"),
                        systemValueRule("now", "NOW")),
                data("me", "伪造的当前用户", "now", "伪造的时间"));

        assertFalse(result.containsKey("me"));
        assertFalse(result.containsKey("now"));
    }

    @Test
    @DisplayName("⚠️ 藏在布局容器里的系统值控件也要被剥离")
    void testNestedInLayoutContainer() {
        String nested = "{\"type\":\"fcRow\",\"children\":[{\"type\":\"col\",\"children\":["
                + systemValueRule("creator", "CREATOR") + "]}]}";

        Map<String, Object> result = guard.strip(fields(nested), data("creator", "伪造"));

        assertFalse(result.containsKey("creator"),
                "系统值控件常被放进栅格，不递归就等于这些字段完全没有保护");
    }

    // ==================== 不该动的情况 ====================

    @Test
    @DisplayName("表单里没有系统值控件时原样返回")
    void testNoSystemValueFields() {
        Map<String, Object> result = guard.strip(
                fields("{\"type\":\"input\",\"field\":\"a\"}"), data("a", 1));
        assertEquals(1, result.get("a"));
    }

    @Test
    @DisplayName("入参数据不被修改 —— 调用方还要用原始提交值做比对")
    void testInputNotMutated() {
        Map<String, Object> submitted = data("creator", "伪造");
        guard.strip(fields(systemValueRule("creator", "CREATOR")), submitted);
        assertEquals("伪造", submitted.get("creator"), "剥离不得就地修改入参");
    }

    @Test
    @DisplayName("formFields 为空或坏掉时不抛异常")
    void testBrokenFormFields() {
        assertDoesNotThrow(() -> guard.strip(null, data("a", 1)));
        assertDoesNotThrow(() -> guard.strip("不是 JSON", data("a", 1)));
        assertDoesNotThrow(() -> guard.strip(fields(systemValueRule("c", "CREATOR")), null));
    }

    // ==================== 来源解析 ====================

    @Test
    @DisplayName("落库型来源能解析出对应的自动填充项")
    void testAutoFillSourceParsing() {
        assertEquals(SystemValueGuard.AutoFillSource.CREATOR,
                SystemValueGuard.AutoFillSource.parse("CREATOR"));
        assertEquals(SystemValueGuard.AutoFillSource.CREATE_TIME,
                SystemValueGuard.AutoFillSource.parse("create_time"));
        assertEquals(SystemValueGuard.AutoFillSource.UPDATER,
                SystemValueGuard.AutoFillSource.parse("UPDATER"));
    }

    @Test
    @DisplayName("展示型来源解析为 null —— 它们不落库，不参与自动填充校验")
    void testDisplayOnlySourceParsesToNull() {
        assertNull(SystemValueGuard.AutoFillSource.parse("CURRENT_USER"));
        assertNull(SystemValueGuard.AutoFillSource.parse("CURRENT_DEPT"));
        assertNull(SystemValueGuard.AutoFillSource.parse("NOW"));
        assertNull(SystemValueGuard.AutoFillSource.parse(null));
        assertNull(SystemValueGuard.AutoFillSource.parse("胡写的"));
    }

    @Test
    @DisplayName("没配 source 的系统值控件照样被剥离 —— 剥离不依赖来源")
    void testFieldWithoutSourceStillStripped() {
        String rule = "{\"type\":\"SystemValue\",\"field\":\"x\",\"props\":{}}";
        Map<String, Object> result = guard.strip(fields(rule), data("x", "伪造"));
        assertFalse(result.containsKey("x"));
    }

}
