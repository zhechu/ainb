package cn.enlev.ainb.module.infra.framework.billtemplate.core;

import cn.enlev.ainb.module.infra.enums.billtemplate.BillDateFormats;
import cn.enlev.ainb.module.infra.enums.billtemplate.BillGenTypeEnum;
import cn.enlev.ainb.module.infra.enums.billtemplate.SegmentSourceEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InlineRuleEvaluator} 的单元测试
 *
 * 内联规则的核心是「前后缀从哪儿取值」。取错行、取错作用域，单号就会张冠李戴 ——
 * 且不会报错，只会静默出一个看起来像模像样但归属错误的号。
 * 因此重点覆盖作用域规则与各种缺值场景。
 *
 * @author 走进新时代
 */
class InlineRuleEvaluatorTest {

    private static InlineRuleConfig.RuleSegment constant(String value) {
        InlineRuleConfig.RuleSegment segment = new InlineRuleConfig.RuleSegment();
        segment.setSourceType(SegmentSourceEnum.CONSTANT.getType());
        segment.setValue(value);
        return segment;
    }

    private static InlineRuleConfig.RuleSegment fieldRef(String field) {
        InlineRuleConfig.RuleSegment segment = new InlineRuleConfig.RuleSegment();
        segment.setSourceType(SegmentSourceEnum.FIELD.getType());
        segment.setValue(field);
        return segment;
    }

    private static InlineRuleConfig config(List<InlineRuleConfig.RuleSegment> prefix,
                                           List<InlineRuleConfig.RuleSegment> suffix) {
        InlineRuleConfig config = new InlineRuleConfig();
        config.setPrefixSegments(prefix);
        config.setSuffixSegments(suffix);
        config.setGenType(BillGenTypeEnum.TIME.getType());
        config.setDateFormat(BillDateFormats.DAY);
        config.setDigit(3);
        config.setStartNumber(1);
        config.setStep(1);
        return config;
    }

    // ==================== 段求值 ====================

    @Test
    @DisplayName("常量段原样输出，按配置顺序拼接")
    void testConstantSegments() {
        InlineRuleConfig config = config(List.of(constant("CG"), constant("-")), List.of(constant("#")));

        BillNumberSpec spec = InlineRuleEvaluator.evaluate("f1", "no", config, new HashMap<>(), null);

        assertEquals("CG-", spec.getPrefix());
        assertEquals("#", spec.getSuffix());
    }

    @Test
    @DisplayName("字段段取表单字段的值")
    void testFieldSegment() {
        InlineRuleConfig config = config(List.of(fieldRef("customer_no"), constant("-")), null);
        Map<String, Object> data = new HashMap<>();
        data.put("customer_no", "C001");

        BillNumberSpec spec = InlineRuleEvaluator.evaluate("f1", "no", config, data, null);

        assertEquals("C001-", spec.getPrefix());
    }

    @Test
    @DisplayName("被引用字段没有值时该段输出空串，不阻断保存")
    void testFieldSegmentMissingValue() {
        InlineRuleConfig config = config(List.of(constant("CG"), fieldRef("customer_no")), null);

        BillNumberSpec spec = InlineRuleEvaluator.evaluate("f1", "no", config, new HashMap<>(), null);

        assertEquals("CG", spec.getPrefix());
    }

    @Test
    @DisplayName("数字与日期类型的字段值都能转成可读的段")
    void testFieldSegmentTypeConversion() {
        Map<String, Object> data = new HashMap<>();
        data.put("num", 42);
        data.put("date", LocalDate.of(2026, 9, 6));
        InlineRuleConfig config = config(List.of(fieldRef("num"), fieldRef("date")), null);

        BillNumberSpec spec = InlineRuleEvaluator.evaluate("f1", "no", config, data, null);

        // 日期不能是 2026-09-06 这种带分隔符的形式，塞进单号里既难看又容易超长
        assertEquals("4220260906", spec.getPrefix());
    }

    @Test
    @DisplayName("空段与空值段被跳过，不会在号码里留下空洞")
    void testBlankSegmentsSkipped() {
        // 用 Arrays.asList 而非 List.of：后者不接受 null 元素，而这里要测的正是 null 段
        InlineRuleConfig config = config(
                Arrays.asList(constant("A"), constant(""), null, constant("B")), null);

        BillNumberSpec spec = InlineRuleEvaluator.evaluate("f1", "no", config, new HashMap<>(), null);

        assertEquals("AB", spec.getPrefix());
    }

    // ==================== 作用域 ====================

    @Test
    @DisplayName("⚠️ 子表行取值先本行，本行有就不看主表 —— 否则明细会串到别的行")
    void testScopeRowFirst() {
        Map<String, Object> row = new HashMap<>();
        row.put("code", "ROW");
        Map<String, Object> main = new HashMap<>();
        main.put("code", "MAIN");
        InlineRuleConfig config = config(List.of(fieldRef("code")), null);

        BillNumberSpec spec = InlineRuleEvaluator.evaluate("f1", "no", config, row, main);

        assertEquals("ROW", spec.getPrefix());
    }

    @Test
    @DisplayName("子表行找不到时回退到主表")
    void testScopeFallbackToMain() {
        Map<String, Object> row = new HashMap<>();
        Map<String, Object> main = new HashMap<>();
        main.put("order_no", "SO001");
        InlineRuleConfig config = config(List.of(fieldRef("order_no")), null);

        BillNumberSpec spec = InlineRuleEvaluator.evaluate("f1", "no", config, row, main);

        assertEquals("SO001", spec.getPrefix());
    }

    @Test
    @DisplayName("本行的值是 null 时不再回退 —— 用户显式清空该字段，不该拿主表的值顶上")
    void testScopeExplicitNullDoesNotFallback() {
        Map<String, Object> row = new HashMap<>();
        row.put("code", null);
        Map<String, Object> main = new HashMap<>();
        main.put("code", "MAIN");
        InlineRuleConfig config = config(List.of(fieldRef("code")), null);

        BillNumberSpec spec = InlineRuleEvaluator.evaluate("f1", "no", config, row, main);

        assertEquals("", spec.getPrefix());
    }

    // ==================== 生成规格 ====================

    @Test
    @DisplayName("计数作用域由表单编码与字段名构成，与模板模式的作用域不会撞车")
    void testCounterScope() {
        InlineRuleConfig config = config(null, null);

        BillNumberSpec spec = InlineRuleEvaluator.evaluate("purchase_form", "order_no", config,
                new HashMap<>(), null);

        assertEquals("inline:purchase_form:order_no", spec.getCounterScope());
    }

    @Test
    @DisplayName("⚠️ 内联模式默认按前缀分组 —— 前缀取自客户编号时每个客户各自从 1 开始")
    void testGroupByPrefixDefaultsOn() {
        InlineRuleConfig config = config(List.of(fieldRef("customer_no")), null);
        assertNull(config.getGroupByPrefix());

        Map<String, Object> data = new HashMap<>();
        data.put("customer_no", "C001");
        BillNumberSpec spec = InlineRuleEvaluator.evaluate("f1", "no", config, data, null);

        assertTrue(spec.isGroupByPrefix());
    }

    @Test
    @DisplayName("显式关闭分组时以用户配置为准")
    void testGroupByPrefixExplicitlyOff() {
        InlineRuleConfig config = config(List.of(constant("CG")), null);
        config.setGroupByPrefix(false);

        BillNumberSpec spec = InlineRuleEvaluator.evaluate("f1", "no", config, new HashMap<>(), null);

        assertFalse(spec.isGroupByPrefix());
    }

    @Test
    @DisplayName("生成方式缺失时按时间流水兜底，不至于生成一个只有前后缀的号")
    void testGenTypeFallback() {
        InlineRuleConfig config = new InlineRuleConfig();
        config.setDigit(4);

        BillNumberSpec spec = InlineRuleEvaluator.evaluate("f1", "no", config, new HashMap<>(), null);

        assertEquals(BillGenTypeEnum.TIME, spec.getGenType());
    }

    @Test
    @DisplayName("求值结果能直接交给生成器拼出完整号码")
    void testEndToEndWithGenerator() {
        Map<String, Object> data = new HashMap<>();
        data.put("customer_no", "C001");
        InlineRuleConfig config = config(List.of(fieldRef("customer_no"), constant("-")), List.of(constant("-A")));

        BillNumberSpec spec = InlineRuleEvaluator.evaluate("f1", "no", config, data, null);
        String no = BillNumberGenerator.generate(spec,
                java.time.LocalDateTime.of(2026, 9, 6, 10, 0), 7L);

        assertEquals("C001-20260906007-A", no);
    }

    // ==================== 解析 ====================

    @Test
    @DisplayName("原始 Map 配置能解析成强类型")
    void testParse() {
        Map<String, Object> segment = new HashMap<>();
        segment.put("sourceType", 1);
        segment.put("value", "customer_no");
        Map<String, Object> raw = new HashMap<>();
        raw.put("prefixSegments", List.of(segment));
        raw.put("genType", 1);
        raw.put("digit", 4);

        InlineRuleConfig config = InlineRuleEvaluator.parse(raw);

        assertNotNull(config);
        assertEquals(1, config.getPrefixSegments().size());
        assertEquals("customer_no", config.getPrefixSegments().get(0).getValue());
        assertEquals(4, config.getDigit());
    }

    @Test
    @DisplayName("配置为空或结构不认识时返回 null，交由调用方跳过而不是崩掉")
    void testParseWithBadInput() {
        assertNull(InlineRuleEvaluator.parse(null));
        assertNull(InlineRuleEvaluator.parse(new HashMap<>()));
    }

    @Test
    @DisplayName("多出来的未知字段不影响解析 —— 设计器加了新配置项，旧后端也不该崩")
    void testParseIgnoresUnknownFields() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("genType", 1);
        raw.put("digit", 4);
        raw.put("someFutureOption", "whatever");

        InlineRuleConfig config = InlineRuleEvaluator.parse(raw);

        assertNotNull(config);
        assertEquals(4, config.getDigit());
    }

}
