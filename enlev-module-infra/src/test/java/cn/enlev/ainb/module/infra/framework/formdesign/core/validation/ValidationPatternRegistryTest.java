package cn.enlev.ainb.module.infra.framework.formdesign.core.validation;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置正则库的跨端一致性测试
 *
 * 用例来自 form-design/validation-patterns.json 的 cases 段 —— 前端 Vitest 读的是同一份。
 *
 * 这不是「顺手也测一下」：同一个正则字符串交给 Java 与 JS 两个引擎，未必得到同样的结果。
 * 一旦漂移，用户遇到的是「界面上填好了但提交失败」，或反过来「该拦的没拦住」，
 * 而现象里没有任何线索指向「两侧正则引擎行为不同」。
 *
 * 用 @TestFactory 而不是一个 @Test 里循环：每条用例在报告里独立一行，
 * 失败时直接看到是哪个正则的哪个值不对。
 *
 * @author 走进新时代
 */
class ValidationPatternRegistryTest {

    private static final String PATTERN_RESOURCE = "form-design/validation-patterns.json";

    private static PatternCaseFile caseFile;
    private static ValidationPatternRegistry registry;

    @BeforeAll
    static void loadCases() {
        registry = new ValidationPatternRegistry();
        registry.init();

        String json = ResourceUtil.readUtf8Str(PATTERN_RESOURCE);
        caseFile = JsonUtils.parseObject(json, PatternCaseFile.class);
        assertNotNull(caseFile, "正则库解析失败：" + PATTERN_RESOURCE);
        assertNotNull(caseFile.getCases(), "正则库里没有 cases");
        assertFalse(caseFile.getCases().isEmpty(), "用例集是空的");
    }

    @TestFactory
    @DisplayName("内置正则的匹配结果")
    Collection<DynamicTest> testPatterns() {
        List<DynamicTest> tests = new ArrayList<>();
        for (PatternCase item : caseFile.getCases()) {
            String name = String.format("%s: \"%s\" 应当%s", item.getKey(), item.getValue(),
                    item.isExpected() ? "匹配" : "不匹配");
            tests.add(DynamicTest.dynamicTest(name, () -> {
                ValidationPatternRegistry.BuiltinPattern pattern = registry.get(item.getKey());
                assertNotNull(pattern, "用例引用了不存在的正则 key：" + item.getKey());
                assertEquals(item.isExpected(), pattern.matches(item.getValue()));
            }));
        }
        return tests;
    }

    @Test
    @DisplayName("⚠️ 每条内置正则都要有用例 —— 没有用例的正则等于没有跨端一致性保障")
    void testEveryPatternCovered() {
        for (ValidationPatternRegistry.BuiltinPattern pattern : registry.listAll()) {
            boolean covered = caseFile.getCases().stream()
                    .anyMatch(item -> pattern.getKey().equalsIgnoreCase(item.getKey()));
            assertTrue(covered, "内置正则 " + pattern.getKey()
                    + " 没有任何用例。请在 validation-patterns.json 的 cases 里补上");
        }
    }

    @Test
    @DisplayName("⚠️ 正则必须避开 Java 与 JS 行为不一致的语法")
    void testNoEngineSpecificSyntax() {
        for (ValidationPatternRegistry.BuiltinPattern pattern : registry.listAll()) {
            String regex = pattern.getPattern();
            // 后顾断言：Java 支持，旧 JS 引擎不支持 —— 在前端会直接抛 SyntaxError，
            // 表现为整个表单的校验初始化失败，而不只是这一个字段
            assertFalse(regex.contains("(?<"),
                    pattern.getKey() + " 用了后顾断言或命名分组，JS 侧兼容性不可靠：" + regex);
            // \p{...} 的族名在两侧不通用
            assertFalse(regex.contains("\\p{"),
                    pattern.getKey() + " 用了 Unicode 属性，两侧族名不通用：" + regex);
        }
    }

    @Test
    @DisplayName("每条内置正则都有展示名与兜底提示")
    void testMetadataComplete() {
        for (ValidationPatternRegistry.BuiltinPattern pattern : registry.listAll()) {
            assertNotNull(pattern.getLabel(), pattern.getKey() + " 缺 label，设计器下拉框里会是空白");
            assertNotNull(pattern.getMessage(),
                    pattern.getKey() + " 缺 message，字段没配自定义提示时用户只会看到「格式不正确」");
        }
    }

    @Test
    @DisplayName("认不出来的 key 返回 null，由调用方决定放过还是拦下")
    void testUnknownKey() {
        assertEquals(null, registry.get("NOT_EXISTS"));
        assertEquals(null, registry.get(null));
    }

    @Test
    @DisplayName("语法错误的自定义正则返回 null 而不是抛异常")
    void testInvalidCustomRegex() {
        assertEquals(null, registry.compileCustom("^[a-"));
        assertNotNull(registry.compileCustom("^[a-z]+$"));
    }

    // ==================== 用例集的反序列化结构 ====================

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PatternCaseFile {

        private Integer libraryVersion;
        private List<PatternCase> cases;

    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PatternCase {

        private String key;
        private String value;
        private boolean expected;

    }

}
