package cn.enlev.ainb.module.infra.framework.billtemplate.core;

import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FormBillFieldResolver} 的单元测试
 *
 * 解析漏一个字段的后果是「配了但静默不生效」—— 用户要等到第一张单子存进去、
 * 发现单号是空的才察觉，那时错误数据已经产生。因此重点覆盖：
 * 布局容器嵌套、子表归属判定、以及各种脏数据下的降级行为。
 *
 * @author 走进新时代
 */
class FormBillFieldResolverTest {

    private FormBillFieldResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new FormBillFieldResolver();
    }

    /**
     * 把若干条规则包成 DO 里存的那种「JSON 字符串数组的 JSON」
     */
    private static String formFields(String... rules) {
        return JsonUtils.toJsonString(Arrays.asList(rules));
    }

    private static String billRule(String field, String title, String templateCode) {
        return String.format(
                "{\"type\":\"BillNumberInput\",\"field\":\"%s\",\"title\":\"%s\","
                        + "\"props\":{\"ruleMode\":1,\"templateCode\":\"%s\"}}",
                field, title, templateCode);
    }

    private static String inputRule(String field) {
        return String.format("{\"type\":\"input\",\"field\":\"%s\",\"title\":\"普通输入\"}", field);
    }

    private static TableBinding mainOnlyBinding() {
        TableBinding binding = new TableBinding();
        binding.setMainTable("biz_order");
        binding.setFieldMapping(new HashMap<>());
        return binding;
    }

    // ==================== 基础解析 ====================

    @Test
    @DisplayName("解析出顶层的单据字段")
    void testResolveTopLevel() {
        String fields = formFields(inputRule("remark"), billRule("order_no", "订单编号", "purchase_order"));

        List<BillFieldDefinition> result = resolver.resolve("f1", 1, fields, mainOnlyBinding());

        assertEquals(1, result.size());
        BillFieldDefinition definition = result.get(0);
        assertEquals("order_no", definition.getField());
        assertEquals("订单编号", definition.getTitle());
        assertEquals("purchase_order", definition.getTemplateCode());
        assertTrue(definition.isTemplateMode());
        assertTrue(definition.isMainTable());
    }

    @Test
    @DisplayName("没有单据字段时返回空列表")
    void testResolveWithoutBillField() {
        String fields = formFields(inputRule("a"), inputRule("b"));

        assertTrue(resolver.resolve("f1", 1, fields, mainOnlyBinding()).isEmpty());
    }

    @Test
    @DisplayName("同一表单里的多个单据字段都要解析出来")
    void testResolveMultiple() {
        String fields = formFields(
                billRule("order_no", "订单号", "tpl_a"),
                inputRule("remark"),
                billRule("contract_no", "合同号", "tpl_b"));

        List<BillFieldDefinition> result = resolver.resolve("f1", 1, fields, mainOnlyBinding());

        assertEquals(2, result.size());
        assertEquals(Set.of("order_no", "contract_no"),
                Set.of(result.get(0).getField(), result.get(1).getField()));
    }

    // ==================== 嵌套 ====================

    @Test
    @DisplayName("⚠️ 布局容器里的单据字段也要解析出来 —— 只扫顶层的话拖进栅格就静默失效")
    void testResolveNestedInLayout() {
        String nested = "{\"type\":\"fcRow\",\"children\":["
                + "{\"type\":\"col\",\"children\":[" + billRule("order_no", "订单编号", "tpl_a") + "]}"
                + "]}";
        String fields = formFields(inputRule("remark"), nested);

        List<BillFieldDefinition> result = resolver.resolve("f1", 1, fields, mainOnlyBinding());

        assertEquals(1, result.size());
        assertEquals("order_no", result.get(0).getField());
    }

    @Test
    @DisplayName("props.rule 下的子控件同样要扫到")
    void testResolveNestedInPropsRule() {
        String nested = "{\"type\":\"subForm\",\"field\":\"items\",\"props\":{\"rule\":["
                + billRule("item_no", "明细编号", "tpl_item") + "]}}";
        String fields = formFields(nested);

        List<BillFieldDefinition> result = resolver.resolve("f1", 1, fields, mainOnlyBinding());

        assertEquals(1, result.size());
        assertEquals("item_no", result.get(0).getField());
    }

    @Test
    @DisplayName("容器里混着非对象节点（如纯文本）时不应崩")
    void testResolveWithNonObjectChildren() {
        String nested = "{\"type\":\"fcRow\",\"children\":[\"一段说明文字\","
                + billRule("order_no", "订单编号", "tpl_a") + "]}";

        List<BillFieldDefinition> result =
                resolver.resolve("f1", 1, formFields(nested), mainOnlyBinding());

        assertEquals(1, result.size());
    }

    // ==================== 子表归属 ====================

    @Test
    @DisplayName("⚠️ 子表归属由表绑定判定，与它在规则树里被哪种容器包着无关")
    void testSubTableOwnershipFromBinding() {
        TableBinding binding = mainOnlyBinding();
        SubTableBinding subTable = new SubTableBinding();
        subTable.setField("items");
        subTable.setTable("biz_order_item");
        Map<String, FieldMapping> subMapping = new HashMap<>();
        FieldMapping mapping = new FieldMapping();
        mapping.setColumn("item_no");
        subMapping.put("item_no", mapping);
        subTable.setFieldMapping(subMapping);
        binding.setSubTables(List.of(subTable));

        // 注意：这条规则写在顶层，容器上看不出它属于子表
        String fields = formFields(
                billRule("order_no", "订单编号", "tpl_a"),
                billRule("item_no", "明细编号", "tpl_b"));

        List<BillFieldDefinition> result = resolver.resolve("f1", 1, fields, binding);

        Map<String, BillFieldDefinition> byField = new HashMap<>();
        result.forEach(item -> byField.put(item.getField(), item));

        assertTrue(byField.get("order_no").isMainTable());
        assertFalse(byField.get("item_no").isMainTable());
        assertEquals("items", byField.get("item_no").getSubTableField());
    }

    // ==================== 脏数据容错 ====================

    @Test
    @DisplayName("单条规则不是合法 JSON 时跳过它，其余照常解析")
    void testResolveWithBrokenRule() {
        String fields = formFields("{不是合法 JSON", billRule("order_no", "订单编号", "tpl_a"));

        List<BillFieldDefinition> result = resolver.resolve("f1", 1, fields, mainOnlyBinding());

        assertEquals(1, result.size());
        assertEquals("order_no", result.get(0).getField());
    }

    @Test
    @DisplayName("formFields 整体为空或非法时返回空列表，不抛异常")
    void testResolveWithBlankFields() {
        assertTrue(resolver.resolve("f1", 1, null, mainOnlyBinding()).isEmpty());
        assertTrue(resolver.resolve("f1", 1, "", mainOnlyBinding()).isEmpty());
        assertTrue(resolver.resolve("f1", 1, "不是 JSON", mainOnlyBinding()).isEmpty());
    }

    @Test
    @DisplayName("缺少 field 的单据组件被跳过 —— 没有 field 就存不下值")
    void testResolveWithoutField() {
        String broken = "{\"type\":\"BillNumberInput\",\"title\":\"没字段\","
                + "\"props\":{\"ruleMode\":1,\"templateCode\":\"tpl_a\"}}";

        assertTrue(resolver.resolve("f1", 1, formFields(broken), mainOnlyBinding()).isEmpty());
    }

    @Test
    @DisplayName("缺少 props 时按「模板模式但未绑定模板」处理，交给发布校验去拦")
    void testResolveWithoutProps() {
        String noProps = "{\"type\":\"BillNumberInput\",\"field\":\"order_no\",\"title\":\"订单编号\"}";

        List<BillFieldDefinition> result =
                resolver.resolve("f1", 1, formFields(noProps), mainOnlyBinding());

        assertEquals(1, result.size());
        assertTrue(result.get(0).isTemplateMode());
        assertNull(result.get(0).getTemplateCode());
    }

    @Test
    @DisplayName("ruleMode 是字符串数字时也能识别 —— JSON 里的数字类型不总是可控")
    void testRuleModeAsString() {
        String rule = "{\"type\":\"BillNumberInput\",\"field\":\"order_no\","
                + "\"props\":{\"ruleMode\":\"2\",\"ruleConfig\":{\"genType\":1}}}";

        List<BillFieldDefinition> result =
                resolver.resolve("f1", 1, formFields(rule), mainOnlyBinding());

        assertEquals(1, result.size());
        assertTrue(result.get(0).isInlineMode());
        assertNotNull(result.get(0).getRuleConfig());
    }

    @Test
    @DisplayName("没有 title 时用 field 兜底，报错信息不至于是空的")
    void testDisplayNameFallback() {
        String rule = "{\"type\":\"BillNumberInput\",\"field\":\"fc_a1b2c3\","
                + "\"props\":{\"ruleMode\":1,\"templateCode\":\"tpl_a\"}}";

        List<BillFieldDefinition> result =
                resolver.resolve("f1", 1, formFields(rule), mainOnlyBinding());

        assertEquals("fc_a1b2c3", result.get(0).displayName());
    }

    // ==================== 缓存 ====================

    @Test
    @DisplayName("同一表单、内容没变时命中缓存，返回同一个实例")
    void testCacheHit() {
        String fields = formFields(billRule("order_no", "订单编号", "tpl_a"));

        List<BillFieldDefinition> first = resolver.resolve("f1", 1, fields, mainOnlyBinding());
        List<BillFieldDefinition> second = resolver.resolve("f1", 1, fields, mainOnlyBinding());

        assertSame(first, second);
    }

    @Test
    @DisplayName("⚠️ 版本号变了就重新解析 —— 表单发布后配置必须立即生效")
    void testCacheInvalidatedByVersion() {
        String v1 = formFields(billRule("order_no", "订单编号", "tpl_a"));
        String v2 = formFields(billRule("order_no", "订单编号", "tpl_b"));

        List<BillFieldDefinition> first = resolver.resolve("f1", 1, v1, mainOnlyBinding());
        List<BillFieldDefinition> second = resolver.resolve("f1", 2, v2, mainOnlyBinding());

        assertEquals("tpl_a", first.get(0).getTemplateCode());
        assertEquals("tpl_b", second.get(0).getTemplateCode());
    }

    @Test
    @DisplayName("⚠️ 版本号没变但内容变了，也要重新解析")
    void testCacheInvalidatedByContentWithSameVersion() {
        // 这是一个真实发生过的缺陷：缓存键原来是「表单编码:版本号」，
        // 而两个调用方传的都是【已发布】版本号，解析的却是【当前草稿】。
        //
        // 发布校验因此失效两次：
        //  1. 单据字段没绑模板 → 发布被拒，这份「templateCode 为空」的解析结果
        //     被缓存进 code:N；用户把模板配好再发一次，命中的还是旧结果，
        //     一直报同一句「未绑定单据模板」，直到重启。
        //  2. 反过来更糟：已发布的表单新加一个没绑模板的单据字段，
        //     校验拿到的是上个版本的清单，这条校验直接失效。
        String before = formFields(billRule("order_no", "订单编号", "tpl_before"));
        String after = formFields(billRule("order_no", "订单编号", "tpl_fixed"));

        List<BillFieldDefinition> first = resolver.resolve("f1", 1, before, mainOnlyBinding());
        List<BillFieldDefinition> second = resolver.resolve("f1", 1, after, mainOnlyBinding());

        assertEquals("tpl_before", first.get(0).getTemplateCode());
        assertEquals("tpl_fixed", second.get(0).getTemplateCode(),
                "版本号没变但内容变了，必须重新解析");
    }

    @Test
    @DisplayName("⚠️ 未发布的草稿反复校验，每次都按当时的内容解析")
    void testCacheWithNullVersion() {
        // 未发布时版本号是 null，旧实现会把它归一成 0，于是每次校验都是同一个键
        String before = formFields(billRule("order_no", "订单编号", "tpl_before"));
        String after = formFields(billRule("order_no", "订单编号", "tpl_fixed"));

        assertEquals("tpl_before",
                resolver.resolve("f1", null, before, mainOnlyBinding()).get(0).getTemplateCode());
        assertEquals("tpl_fixed",
                resolver.resolve("f1", null, after, mainOnlyBinding()).get(0).getTemplateCode());
    }

    @Test
    @DisplayName("不同表单之间互不影响")
    void testCacheIsolatedByFormCode() {
        String a = formFields(billRule("order_no", "订单编号", "tpl_a"));
        String b = formFields(billRule("order_no", "订单编号", "tpl_b"));

        assertEquals("tpl_a", resolver.resolve("f1", 1, a, mainOnlyBinding()).get(0).getTemplateCode());
        assertEquals("tpl_b", resolver.resolve("f2", 1, b, mainOnlyBinding()).get(0).getTemplateCode());
        // 回头再问 f1，不能被 f2 顶掉
        assertEquals("tpl_a", resolver.resolve("f1", 1, a, mainOnlyBinding()).get(0).getTemplateCode());
    }

    @Test
    @DisplayName("返回的列表不可变，调用方改不了缓存里的内容")
    void testResultIsImmutable() {
        String fields = formFields(billRule("order_no", "订单编号", "tpl_a"));

        List<BillFieldDefinition> result = resolver.resolve("f1", 1, fields, mainOnlyBinding());

        assertThrows(UnsupportedOperationException.class, () -> result.add(new BillFieldDefinition()));
    }

}
