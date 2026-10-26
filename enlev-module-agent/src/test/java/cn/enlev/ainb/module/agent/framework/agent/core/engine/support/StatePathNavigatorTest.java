package cn.enlev.ainb.module.agent.framework.agent.core.engine.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link StatePathNavigator} 单测
 *
 * <p>覆盖 DSL §3.2 的读导航子集：顶层键、{@code $.a.b} 逐层下钻、{@code arr[i]}（含负下标）、组合路径。
 * 核心约束是「取不到一律 null，绝不抛」——每个失败分支都要钉住，否则模板替换会把异常泄进整条流程。
 *
 * @author 走进新时代
 */
class StatePathNavigatorTest {

    private static final Map<String, Object> ROOT = Map.of(
            "name", "张三",
            "contract", Map.of(
                    "partyA", "甲方公司",
                    "amount", 1000),
            "messages", List.of("m0", "m1", "m2"),
            "orders", List.of(
                    Map.of("id", "o1", "qty", 2),
                    Map.of("id", "o2", "qty", 5)));

    @Test
    void navigate_bareTopLevelKey() {
        assertEquals("张三", StatePathNavigator.navigate(ROOT, "name"));
    }

    @Test
    void navigate_dollarPrefixedNested() {
        assertEquals("甲方公司", StatePathNavigator.navigate(ROOT, "$.contract.partyA"));
        assertEquals(1000, StatePathNavigator.navigate(ROOT, "$.contract.amount"));
    }

    @Test
    void navigate_nestedWithoutDollar() {
        // $.a.b 与 a.b 等价
        assertEquals("甲方公司", StatePathNavigator.navigate(ROOT, "contract.partyA"));
    }

    @Test
    void navigate_returnsIntermediateMap() {
        assertEquals(Map.of("partyA", "甲方公司", "amount", 1000),
                StatePathNavigator.navigate(ROOT, "$.contract"));
    }

    @Test
    void navigate_arrayPositiveIndex() {
        assertEquals("m0", StatePathNavigator.navigate(ROOT, "$.messages[0]"));
        assertEquals("m2", StatePathNavigator.navigate(ROOT, "messages[2]"));
    }

    @Test
    void navigate_arrayNegativeIndex_fromTail() {
        assertEquals("m2", StatePathNavigator.navigate(ROOT, "$.messages[-1]"));
        assertEquals("m1", StatePathNavigator.navigate(ROOT, "$.messages[-2]"));
    }

    @Test
    void navigate_combinedPath_arrayOfObjects() {
        assertEquals("o1", StatePathNavigator.navigate(ROOT, "$.orders[0].id"));
        assertEquals(5, StatePathNavigator.navigate(ROOT, "$.orders[-1].qty"));
    }

    @Test
    void navigate_missingSegment_returnsNull() {
        assertNull(StatePathNavigator.navigate(ROOT, "$.contract.gone"));
        assertNull(StatePathNavigator.navigate(ROOT, "$.nope.deep"));
    }

    @Test
    void navigate_typeMismatch_downIntoScalar_returnsNull() {
        // name 是字符串，继续按名/下标下钻 → null，不抛
        assertNull(StatePathNavigator.navigate(ROOT, "$.name.child"));
        assertNull(StatePathNavigator.navigate(ROOT, "$.name[0]"));
    }

    @Test
    void navigate_indexOutOfBounds_returnsNull() {
        assertNull(StatePathNavigator.navigate(ROOT, "$.messages[9]"));
        assertNull(StatePathNavigator.navigate(ROOT, "$.messages[-9]"));
    }

    @Test
    void navigate_malformedPath_returnsNull() {
        assertNull(StatePathNavigator.navigate(ROOT, "$.messages[0"));   // 有 [ 无 ]
        assertNull(StatePathNavigator.navigate(ROOT, "$.messages[x]"));  // 非整数下标
        assertNull(StatePathNavigator.navigate(ROOT, "$.messages[]"));   // 空下标
    }

    @Test
    void navigate_nullOrEmptyInputs_returnNull() {
        assertNull(StatePathNavigator.navigate(null, "$.name"));
        assertNull(StatePathNavigator.navigate(ROOT, null));
        assertNull(StatePathNavigator.navigate(ROOT, ""));
        assertNull(StatePathNavigator.navigate(ROOT, "   "));
    }

    // ==================== 下钻 JSON 文本（llm 节点只能产出文本） ====================

    /** 复刻真实故障形态：llm 节点把模型输出原样写进 state，下游工具要 {@code {{$.analysis.mainTable}}} */
    private static final String ANALYSIS_JSON =
            "{\"mainTable\":\"device\",\"fields\":[{\"field\":\"name\",\"widget\":\"input\"},{\"field\":\"memo\"}]}";

    private static final Map<String, Object> LLM_ROOT = Map.of(
            "analysis", ANALYSIS_JSON,
            "fenced", "```json\n" + ANALYSIS_JSON + "\n```",
            "prose", "好的，以下是结果：{\"mainTable\":\"device\"} 希望有帮助",
            "broken", "{\"mainTable\":");

    @Test
    void navigate_downIntoJsonText_readsFields() {
        assertEquals("device", StatePathNavigator.navigate(LLM_ROOT, "$.analysis.mainTable"),
                "下钻 JSON 文本应取到字段（否则工具只会收到空参数）");
    }

    @Test
    void navigate_downIntoJsonText_arrayIndexAndNested() {
        assertEquals("input", StatePathNavigator.navigate(LLM_ROOT, "$.analysis.fields[0].widget"));
        assertEquals("memo", StatePathNavigator.navigate(LLM_ROOT, "$.analysis.fields[-1].field"),
                "负下标对 JSON 文本里的数组同样生效");
    }

    @Test
    void navigate_downIntoFencedJsonText_stripsCodeFence() {
        assertEquals("device", StatePathNavigator.navigate(LLM_ROOT, "$.fenced.mainTable"),
                "模型爱用 ```json 围栏包 JSON，剥掉后应能下钻");
    }

    @Test
    void navigate_wholeJsonTextVar_returnsRawText() {
        // 向后兼容：整取不做解析，照旧拿到原始文本（下游当字符串用的一律不受影响）
        assertEquals(ANALYSIS_JSON, StatePathNavigator.navigate(LLM_ROOT, "$.analysis"));
    }

    @Test
    void navigate_nonJsonOrBrokenJsonText_returnsNull() {
        assertNull(StatePathNavigator.navigate(LLM_ROOT, "$.prose.mainTable"),
                "JSON 混在散文里不算 JSON 文本，仍按取不到处理");
        assertNull(StatePathNavigator.navigate(LLM_ROOT, "$.broken.mainTable"),
                "残缺 JSON 不抛异常，按取不到处理");
        assertNull(StatePathNavigator.navigate(LLM_ROOT, "$.analysis[0]"),
                "对 JSON 对象取下标取不到");
    }

}
