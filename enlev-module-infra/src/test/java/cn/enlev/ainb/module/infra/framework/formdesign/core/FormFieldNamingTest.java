package cn.enlev.ainb.module.infra.framework.formdesign.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FormFieldNaming} 的单元测试
 *
 * 命名规则是整套字段映射的地基，反向拆分一旦有歧义，副表数据就会错位到别的字段上。
 * 这里重点覆盖「表名或列名自身含下划线」的场景 —— 那正是单下划线分隔符会失效的地方。
 *
 * @author 走进新时代
 */
class FormFieldNamingTest {

    @Test
    @DisplayName("生成副表字段名")
    void testSideField() {
        assertEquals("yd_biz_order_ext_yd_tax_no",
                FormFieldNaming.sideField("biz_order_ext", "tax_no"));
    }

    @Test
    @DisplayName("⚠️ 表名与列名都含下划线时仍能正确拆分（单下划线分隔符会在这里失效）")
    void testRoundTripWithUnderscores() {
        String field = FormFieldNaming.sideField("test_customer_ext", "bank_account");

        assertTrue(FormFieldNaming.isSideField(field));
        assertEquals("test_customer_ext", FormFieldNaming.extractTable(field));
        assertEquals("bank_account", FormFieldNaming.extractColumn(field));
    }

    @Test
    @DisplayName("主表字段不带前缀，extractColumn 原样返回")
    void testMainTableField() {
        assertFalse(FormFieldNaming.isSideField("contact_phone"));
        assertNull(FormFieldNaming.extractTable("contact_phone"));
        // 主表 field 就等于列名，直接可用
        assertEquals("contact_phone", FormFieldNaming.extractColumn("contact_phone"));
    }

    @Test
    @DisplayName("⚠️ 列名本身以 yd_ 开头不会被误判为副表字段")
    void testColumnStartingWithPrefixNotMisjudged() {
        // 没有 _yd_ 分隔标记，只是恰好以 yd_ 开头
        assertFalse(FormFieldNaming.isSideField("yd_amount"));
        assertEquals("yd_amount", FormFieldNaming.extractColumn("yd_amount"));
    }

    @Test
    @DisplayName("列名里含 _yd_ 时按最后一个分隔标记拆分")
    void testColumnContainingSeparator() {
        // 构造极端情况：列名自身含 _yd_
        String field = FormFieldNaming.sideField("t1", "a_yd_b");

        // 按最后一个 _yd_ 拆，表名会多吃一段 —— 这是已知边界，
        // 实际业务列名不会含 _yd_，此处仅锁定行为不抛异常
        assertTrue(FormFieldNaming.isSideField(field));
        assertEquals("b", FormFieldNaming.extractColumn(field));
    }

    @Test
    @DisplayName("空值与畸形输入不抛异常")
    void testBlankAndMalformed() {
        assertFalse(FormFieldNaming.isSideField(null));
        assertFalse(FormFieldNaming.isSideField(""));
        assertFalse(FormFieldNaming.isSideField("yd_"));
        assertNull(FormFieldNaming.extractTable(null));
        assertNull(FormFieldNaming.extractColumn(null));
    }

}
