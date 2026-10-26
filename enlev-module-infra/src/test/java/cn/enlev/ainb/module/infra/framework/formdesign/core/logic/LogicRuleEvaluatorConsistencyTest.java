package cn.enlev.ainb.module.infra.framework.formdesign.core.logic;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 联动规则求值器的跨端一致性测试
 *
 * 用例来自 form-design/logic-cases.json —— 前端 Vitest 读的是同一份。
 * 任一侧行为漂移，那一侧就会红。
 *
 * 这不是「顺手也测一下」：服务端复校验的前提就是两边算出同样的结果。
 * 前端说某字段必填、后端提交时算出不必填，用户会遇到「该拦的没拦住」；
 * 反过来则是「界面上填完了但提交被拒」。两种都是很难查的问题。
 *
 * 用 @TestFactory 而不是把用例塞进一个 @Test 循环：这样每条用例在报告里是独立一行，
 * 失败时直接看到是哪条用例的哪个字段不对，不用去翻断言里的拼接消息。
 *
 * @author 走进新时代
 */
class LogicRuleEvaluatorConsistencyTest {

    private static final String CASES_RESOURCE = "form-design/logic-cases.json";

    private static CaseFile caseFile;
    private final LogicRuleEvaluator evaluator = new LogicRuleEvaluator();

    @BeforeAll
    static void loadCases() {
        String json = ResourceUtil.readUtf8Str(CASES_RESOURCE);
        caseFile = JsonUtils.parseObject(json, CaseFile.class);
        assertNotNull(caseFile, "用例集解析失败：" + CASES_RESOURCE);
        assertNotNull(caseFile.getCases(), "用例集里没有 cases");
        assertFalse(caseFile.getCases().isEmpty(), "用例集是空的");
    }

    @Test
    @DisplayName("⚠️ 迭代轮数必须与用例集声明的一致，两端也必须一致")
    void testMaxPassesMatches() {
        assertEquals(caseFile.getMaxPasses(), LogicRuleEvaluator.MAX_PASSES,
                "求值器的 MAX_PASSES 与用例集声明不一致。两边轮数不同会让"
                        + "「刚好在第 N 轮收敛」的规则集在一侧算出结果、另一侧算不出");
    }

    @TestFactory
    @DisplayName("共享用例集")
    Collection<DynamicTest> runSharedCases() {
        List<DynamicTest> tests = new ArrayList<>();
        for (LogicCase c : caseFile.getCases()) {
            tests.add(DynamicTest.dynamicTest(c.getName(), () -> runCase(c)));
        }
        return tests;
    }

    private void runCase(LogicCase c) {
        LogicEnums.Trigger trigger = c.getTrigger() == null
                ? LogicEnums.Trigger.CHANGE : LogicEnums.Trigger.valueOf(c.getTrigger());

        Map<String, LogicRuleEvaluator.FieldState> baseState = new LinkedHashMap<>();
        if (c.getBaseState() != null) {
            c.getBaseState().forEach((field, expected) -> baseState.put(field,
                    LogicRuleEvaluator.FieldState.of(
                            expected.getVisible() == null || expected.getVisible(),
                            expected.getEditable() == null || expected.getEditable(),
                            expected.getRequired() != null && expected.getRequired())));
        }

        Map<String, Object> data = c.getData() != null
                ? new LinkedHashMap<>(c.getData()) : new LinkedHashMap<>();
        Map<String, Object> dataSnapshot = new LinkedHashMap<>(data);

        LogicRuleEvaluator.EvaluationResult result =
                evaluator.evaluate(c.getRules(), trigger, data, baseState);

        // 求值器不得修改入参 data —— 调用方常把请求体直接传进来，
        // 就地修改会让「原始提交值」这个信息丢掉，服务端复校验时就无法比对哪些值被规则改过
        assertEquals(dataSnapshot, data, "[" + c.getName() + "] 求值器修改了入参 data");

        Expectation expect = c.getExpect();
        assertNotNull(expect, "[" + c.getName() + "] 用例没写 expect");

        if (expect.getStable() != null) {
            assertEquals(expect.getStable(), result.isStable(),
                    "[" + c.getName() + "] stable 不符");
        }
        if (expect.getState() != null) {
            expect.getState().forEach((field, want) -> {
                LogicRuleEvaluator.FieldState got = result.stateOf(field);
                if (want.getVisible() != null) {
                    assertEquals(want.getVisible(), got.isVisible(),
                            "[" + c.getName() + "] 字段 " + field + " 的 visible 不符");
                }
                if (want.getEditable() != null) {
                    assertEquals(want.getEditable(), got.isEditable(),
                            "[" + c.getName() + "] 字段 " + field + " 的 editable 不符");
                }
                if (want.getRequired() != null) {
                    assertEquals(want.getRequired(), got.isRequired(),
                            "[" + c.getName() + "] 字段 " + field + " 的 required 不符");
                }
            });
        }
        if (expect.getData() != null) {
            expect.getData().forEach((field, want) -> assertTrue(
                    looseSame(want, result.getData().get(field)),
                    "[" + c.getName() + "] 字段 " + field + " 的值不符，期望 " + want
                            + "，实际 " + result.getData().get(field)));
        }
    }

    /**
     * 宽松比较期望值与实际值
     *
     * JSON 里的 2 会反序列化成 Integer，而规则赋的值原样透传也是 Integer，
     * 但用例里若写 2.0 就成了 Double。用数值语义比较，避免用例被数字字面量的写法绑住。
     */
    private boolean looseSame(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        if (expected instanceof Number && actual instanceof Number) {
            return Double.compare(((Number) expected).doubleValue(), ((Number) actual).doubleValue()) == 0;
        }
        return expected.equals(actual);
    }

    // ==================== 用例集的反序列化结构 ====================

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CaseFile {
        private Integer casesVersion;
        private int maxPasses;
        private List<LogicCase> cases;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LogicCase {
        private String name;
        private String trigger;
        private Map<String, ExpectedState> baseState;
        private Map<String, Object> data;
        private List<LogicRule> rules;
        private Expectation expect;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Expectation {
        private Map<String, ExpectedState> state;
        private Map<String, Object> data;
        private Boolean stable;
    }

    /**
     * 期望的字段状态。三个维度都是可选的 —— 用例只写关心的那些
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ExpectedState {
        private Boolean visible;
        private Boolean editable;
        private Boolean required;
    }

    /** 让 TypeReference 的导入不被优化掉，保持与项目其它测试一致的写法 */
    @SuppressWarnings("unused")
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

}
