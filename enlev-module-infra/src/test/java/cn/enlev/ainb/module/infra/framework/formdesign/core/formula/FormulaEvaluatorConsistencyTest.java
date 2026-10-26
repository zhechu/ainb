package cn.enlev.ainb.module.infra.framework.formdesign.core.formula;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公式求值器的跨端一致性测试
 *
 * 用例来自 form-design/formula-cases.json —— 前端 Vitest 读的是同一份。
 *
 * 这些用例守的是一件事：用户看到的合计与入库的合计必须相同。
 * 两边算得不一样不会有任何报错，只会在对账时发现数字对不上，那时已经很难追溯。
 *
 * @author 走进新时代
 */
class FormulaEvaluatorConsistencyTest {

    private static final String CASES_RESOURCE = "form-design/formula-cases.json";

    private static CaseFile caseFile;
    private final FormulaEvaluator evaluator = new FormulaEvaluator();

    @BeforeAll
    static void loadCases() {
        String json = ResourceUtil.readUtf8Str(CASES_RESOURCE);
        caseFile = JsonUtils.parseObject(json, CaseFile.class);
        assertNotNull(caseFile, "用例集解析失败：" + CASES_RESOURCE);
        assertNotNull(caseFile.getCases());
        assertFalse(caseFile.getCases().isEmpty());
    }

    @TestFactory
    @DisplayName("共享用例集")
    Collection<DynamicTest> runSharedCases() {
        List<DynamicTest> tests = new ArrayList<>();
        for (FormulaCase c : caseFile.getCases()) {
            tests.add(DynamicTest.dynamicTest(c.getName(), () -> runCase(c)));
        }
        return tests;
    }

    private void runCase(FormulaCase c) {
        Object actual = c.getPrecision() != null
                ? evaluator.evaluate(c.getAst(), c.getData(), c.getPrecision(), c.getRounding())
                : evaluator.evaluate(c.getAst(), c.getData());

        Object expected = c.getExpect();
        String where = "[" + c.getName() + "]";

        if (expected == null) {
            assertNull(actual, where + " 期望 null，实际 " + actual);
            return;
        }
        if (expected instanceof Boolean b) {
            assertEquals(b, actual, where + " 布尔结果不符");
            return;
        }
        if (expected instanceof Number n) {
            assertNotNull(actual, where + " 期望数值 " + n + "，实际 null");
            BigDecimal want = new BigDecimal(n.toString());
            BigDecimal got = evaluator.toDecimal(actual);
            assertNotNull(got, where + " 结果不是数值：" + actual);
            // 用 compareTo 而不是 equals：BigDecimal 的 equals 会比 scale，
            // 3.3 与 3.30 判为不等，而它们在业务上是同一个数
            assertEquals(0, want.compareTo(got),
                    where + " 数值不符，期望 " + want + "，实际 " + got);
            return;
        }
        assertEquals(String.valueOf(expected), String.valueOf(actual), where + " 文本结果不符");
    }

    // ==================== 单独覆盖：不适合放进共享用例的服务端特性 ====================

    @Test
    @DisplayName("求值不修改入参数据")
    void testDataNotMutated() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("a", 1);
        String before = JsonUtils.toJsonString(data);

        FormulaEvaluator.FormulaNode ast = new FormulaEvaluator.FormulaNode();
        ast.setType("field");
        ast.setName("a");
        evaluator.evaluate(ast, data);

        assertEquals(before, JsonUtils.toJsonString(data));
    }

    @Test
    @DisplayName("data 为 null 时不抛异常")
    void testNullData() {
        FormulaEvaluator.FormulaNode ast = new FormulaEvaluator.FormulaNode();
        ast.setType("field");
        ast.setName("a");
        assertNull(assertDoesNotThrow(() -> evaluator.evaluate(ast, null)));
    }

    @Test
    @DisplayName("⚠️ 深层嵌套的 AST 不会栈溢出到把请求打挂")
    void testDeeplyNestedAst() {
        // 造一个 500 层的加法链。正常公式不会这么深，但 AST 是从请求体来的，
        // 构造一个超深的树是很容易的攻击方式
        FormulaEvaluator.FormulaNode node = new FormulaEvaluator.FormulaNode();
        node.setType("num");
        node.setValue(1);
        for (int i = 0; i < 500; i++) {
            FormulaEvaluator.FormulaNode parent = new FormulaEvaluator.FormulaNode();
            parent.setType("binary");
            parent.setOp("+");
            parent.setLeft(node);
            FormulaEvaluator.FormulaNode one = new FormulaEvaluator.FormulaNode();
            one.setType("num");
            one.setValue(1);
            parent.setRight(one);
            node = parent;
        }
        // 递归求值可能栈溢出，但 evaluate 里的 catch 捕获的是 Exception，
        // StackOverflowError 是 Error 而不是 Exception —— 这条用例的作用是
        // 把这个边界暴露出来。500 层在默认栈深下是安全的
        final FormulaEvaluator.FormulaNode root = node;
        Object result = assertDoesNotThrow(() -> evaluator.evaluate(root, Map.of()));
        assertEquals(0, new BigDecimal("501").compareTo(evaluator.toDecimal(result)));
    }

    // ==================== 用例集结构 ====================

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CaseFile {
        private Integer casesVersion;
        private List<FormulaCase> cases;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FormulaCase {
        private String name;
        private FormulaEvaluator.FormulaNode ast;
        private Map<String, Object> data;
        private Object expect;
        private Integer precision;
        private String rounding;
    }

}
