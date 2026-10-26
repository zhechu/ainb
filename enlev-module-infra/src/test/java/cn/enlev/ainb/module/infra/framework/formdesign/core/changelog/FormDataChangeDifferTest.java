package cn.enlev.ainb.module.infra.framework.formdesign.core.changelog;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Spy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段级变更对比的测试
 *
 * <h3>要守的不是「能不能比出差异」，而是「不比出假差异」</h3>
 *
 * 库里读出来的值与前端提交的值类型天然不同：decimal 列读出来是 BigDecimal("10.00")、
 * 前端提交的是 10；date 列读出来是 java.sql.Date、前端提交的是 "2026-09-10"。
 * 直接 toString 比对的话，**每次保存都会记下一堆没改过的字段**。
 *
 * 那种日志比没有更糟：真正的变更被噪音埋掉，看日志的人无法判断哪条是真的。
 * 下面一半的用例都在守这件事。
 *
 * @author 走进新时代
 */
public class FormDataChangeDifferTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDataChangeDiffer differ;

    @Spy
    private WidgetContractRegistry contractRegistry = new WidgetContractRegistry();

    @BeforeEach
    void setUp() {
        contractRegistry.init();
    }

    private String widget(String type, String field, String title) {
        return String.format("{\"type\":\"%s\",\"field\":\"%s\",\"title\":\"%s\"}", type, field, title);
    }

    private String fields(String... rules) {
        return FormFieldsCodec.write(List.of(rules));
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ==================== 真的变了 ====================

    @Test
    @DisplayName("值变了：记下字段标题与新旧值")
    void testChanged() {
        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(
                fields(widget("input", "reason", "原因")),
                map("reason", "旧原因"),
                map("reason", "新原因"));

        assertEquals(1, changes.size());
        // 日志给人看，用标题而不是字段 ID
        assertEquals("原因", changes.get(0).getTitle());
        assertEquals("旧原因", changes.get(0).getOldValue());
        assertEquals("新原因", changes.get(0).getNewValue());
    }

    @Test
    @DisplayName("从空改成有值、从有值改成空，都算变更")
    void testFromAndToEmpty() {
        String form = fields(widget("input", "reason", "原因"));
        assertEquals(1, differ.diff(form, map(), map("reason", "填上了")).size());
        assertEquals(1, differ.diff(form, map("reason", "原来有"), map("reason", null)).size());
    }

    // ==================== 假变更：这几条是重点 ====================

    @Test
    @DisplayName("⚠️ decimal 列的 10.00 与前端提交的 10 不算变更")
    void testDecimalScaleIsNotChange() {
        // 不处理的话，每次保存都会记一条「金额：10.00 → 10」
        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(
                fields(widget("inputNumber", "amount", "金额")),
                map("amount", new BigDecimal("10.00")),
                map("amount", 10));

        assertTrue(changes.isEmpty(), "10.00 与 10 是同一个数：" + changes);
    }

    @Test
    @DisplayName("⚠️ 数值真的变了还是要记")
    void testDecimalRealChange() {
        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(
                fields(widget("inputNumber", "amount", "金额")),
                map("amount", new BigDecimal("10.00")),
                map("amount", 20));

        assertEquals(1, changes.size());
    }

    @Test
    @DisplayName("⚠️ date 列读出来的日期与前端提交的 yyyy-MM-dd 不算变更")
    void testDateFormatIsNotChange() {
        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(
                fields(widget("datePicker", "start_date", "开始日期")),
                map("start_date", java.sql.Date.valueOf(LocalDate.of(2026, 9, 10))),
                map("start_date", "2026-09-10"));

        assertTrue(changes.isEmpty(), "同一个日期的两种形态不该算变更：" + changes);
    }

    @Test
    @DisplayName("null 与空串视为同一件事")
    void testNullAndEmpty() {
        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(
                fields(widget("input", "reason", "原因")),
                map("reason", null),
                map("reason", ""));

        assertTrue(changes.isEmpty(), "字段本来是空的，提交空串不算改动：" + changes);
    }

    // ==================== 范围 ====================

    @Test
    @DisplayName("⚠️ 没提交的字段不算变更")
    void testUntouchedFieldIgnored() {
        // 更新是局部的：没提交的键压根不参与更新。算进来会让审批节点每次提交
        // 都「改」了一堆它看不见的字段
        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(
                fields(widget("input", "reason", "原因"), widget("input", "memo", "备注")),
                map("reason", "旧原因", "memo", "旧备注"),
                map("reason", "新原因"));

        assertEquals(1, changes.size());
        assertEquals("原因", changes.get(0).getTitle());
    }

    @Test
    @DisplayName("⚠️ 子表明细不进变更日志")
    void testSubTableExcluded() {
        // 明细是一整张表的行增删改，塞进「字段：旧值 → 新值」只能变成两大段 JSON 的
        // 对比，读的人得不到任何有用信息
        String form = fields("{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":["
                + widget("input", "name", "名称") + "]}}");

        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(form,
                map("items", List.of(map("name", "旧"))),
                map("items", List.of(map("name", "新")), "name", "新"));

        assertTrue(changes.isEmpty(), "子表控件与它的明细列都不该出现：" + changes);
    }

    @Test
    @DisplayName("展示型控件不进变更日志：它们没有值")
    void testDisplayWidgetExcluded() {
        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(
                fields(widget("elDivider", "line1", "分割线")),
                map("line1", "a"),
                map("line1", "b"));

        assertTrue(changes.isEmpty(), changes.toString());
    }

    @Test
    @DisplayName("表单里没有的键一律忽略：版本号这类不是表单字段")
    void testUnknownKeyIgnored() {
        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(
                fields(widget("input", "reason", "原因")),
                map("version", 1),
                map("version", 2));

        assertTrue(changes.isEmpty(), changes.toString());
    }

    @Test
    @DisplayName("旧值整行读不到时（记录刚建）视为全部从空改起")
    void testNullOldRow() {
        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(
                fields(widget("input", "reason", "原因")), null, map("reason", "值"));

        assertEquals(1, changes.size());
        assertEquals("", changes.get(0).getOldValue());
    }

    @Test
    @DisplayName("数组值序列化成 JSON 展示，而不是 ArrayList 的 toString")
    void testCollectionDisplay() {
        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(
                fields(widget("checkbox", "tags", "标签")),
                map("tags", List.of("a")),
                map("tags", List.of("a", "b")));

        assertEquals(1, changes.size());
        assertEquals("[\"a\",\"b\"]", changes.get(0).getNewValue());
    }

    // ==================== time 列 ====================
    //
    // 这三条守的是一次真实故障：控件总览示例里有个 time 列（occur_time），
    // 表单开了变更日志，于是**编辑任何一条记录都返回 500 系统异常**，新增却是好的。
    //
    // 原因是 java.sql.Time 继承自 java.util.Date，却既不是 java.sql.Date
    // 也不是 java.sql.Timestamp，于是落进了 `instanceof Date` 的兜底分支，
    // 在 toInstant() 上炸开 —— 而 JDK 里 java.sql.Time.toInstant() 是
    // 无条件抛 UnsupportedOperationException 的。

    @Test
    @DisplayName("⚠️ time 列读出来的 java.sql.Time 不能让对比抛异常")
    void testSqlTimeDoesNotThrow() {
        // 改了时间：既要比得出来，更要比得下去（不抛异常）
        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(
                fields(widget("timePicker", "occur_time", "发生时间")),
                map("occur_time", java.sql.Time.valueOf(LocalTime.of(14, 5, 0))),
                map("occur_time", "15:30:00"));

        assertEquals(1, changes.size());
        assertEquals("14:05:00", changes.get(0).getOldValue());
        assertEquals("15:30:00", changes.get(0).getNewValue());
    }

    @Test
    @DisplayName("⚠️ 同一个时间的 java.sql.Time 与前端字符串不算变更")
    void testSqlTimeFormatIsNotChange() {
        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(
                fields(widget("timePicker", "occur_time", "发生时间")),
                map("occur_time", java.sql.Time.valueOf(LocalTime.of(14, 5, 0))),
                map("occur_time", "14:05:00"));

        assertTrue(changes.isEmpty(), "同一个时间的两种形态不该算变更：" + changes);
    }

    @Test
    @DisplayName("⚠️ 整点时间：LocalTime 要补齐秒，否则与库里的 08:30:00 对不上")
    void testLocalTimeKeepsSeconds() {
        // 写入侧会把毫秒数归一成 LocalTime（TemporalValueCoercer），
        // 而 LocalTime.toString() 在秒为 0 时省略秒 —— 不显式格式化的话，
        // 一个没被碰过的字段每次保存都会记一条「08:30:00 → 08:30」
        List<FormDataChangeDiffer.FieldChange> changes = differ.diff(
                fields(widget("timePicker", "start_time", "开始时间")),
                map("start_time", java.sql.Time.valueOf(LocalTime.of(8, 30, 0))),
                map("start_time", LocalTime.of(8, 30, 0)));

        assertTrue(changes.isEmpty(), "整点时间的两种形态不该算变更：" + changes);
    }

}
