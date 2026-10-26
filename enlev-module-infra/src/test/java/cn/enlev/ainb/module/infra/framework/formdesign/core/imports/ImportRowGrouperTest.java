package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportModels.RawRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImportRowGrouper} 的单元测试
 *
 * <h3>这一层在守什么</h3>
 *
 * 把「一行一条明细」的扁平表格还原成「一条主记录 + N 条明细」。
 * 判据是<b>相邻行父键相同即归为一组</b>，而不是全表按父键归组。
 *
 * 全表归组看起来更宽容，但它会掩盖一个真问题：文件里
 * {@code XM001 / XM002 / XM001} 这种顺序，多半是用户漏改了编码。
 * 全表归组会把两段 XM001 合成一张主单，用户看到「成功了」，
 * 却不知道自己的表被悄悄合并过 —— 等他下次导一份真的分段的表，
 * 得到的结果就完全不对了。
 *
 * 所以这里的用例集中在两件事：<b>该合的要合上</b>、<b>该报的不能猜</b>。
 *
 * @author 走进新时代
 */
public class ImportRowGrouperTest extends BaseMockitoUnitTest {

    private final ImportRowGrouper grouper = new ImportRowGrouper();
    private final ImportWidgetRegistry registry = new ImportWidgetRegistry();

    /** 主表两个字段 + 一张带两列的子表 */
    private Map<String, ImportWidget> widgets() {
        return registry.parse(FormFieldsCodec.write(List.of(
                "{\"type\":\"input\",\"field\":\"code\",\"title\":\"编码\",\"$required\":true}",
                "{\"type\":\"input\",\"field\":\"name\",\"title\":\"名称\"}",
                "{\"type\":\"group\",\"field\":\"contacts\",\"title\":\"联系人\",\"props\":{\"rule\":["
                        + "{\"type\":\"input\",\"field\":\"person\",\"title\":\"姓名\"},"
                        + "{\"type\":\"input\",\"field\":\"tel\",\"title\":\"电话\"}]}}",
                "{\"type\":\"group\",\"field\":\"banks\",\"title\":\"账户\",\"props\":{\"rule\":["
                        + "{\"type\":\"input\",\"field\":\"account\",\"title\":\"账号\"}]}}")),
                null);
    }

    private RawRow row(int rowNo, String code, String name, String person, String tel,
                       String account) {
        RawRow raw = new RawRow();
        raw.setRowNo(rowNo);
        put(raw, "code", code);
        put(raw, "name", name);
        put(raw, "person", person);
        put(raw, "tel", tel);
        put(raw, "account", account);
        return raw;
    }

    private void put(RawRow raw, String field, String value) {
        if (value != null && !value.isEmpty()) {
            raw.getData().put(field, value);
            raw.getRawTexts().put(field, value);
        }
    }

    private static final List<String> PARENT = List.of("code");

    // ==================== 该合的要合上 ====================

    @Test
    @DisplayName("相邻同父键的行合并成一条主记录，明细各成一行")
    void testAdjacentRowsMerge() {
        List<RawRow> rows = List.of(
                row(2, "A001", "甲公司", "王建国", "13800000001", "6222001"),
                row(3, "A001", null, "李海燕", "13800000002", null),
                row(4, "A001", null, "张志强", "13800000003", null),
                row(5, "A002", "乙公司", "刘晓明", "13800000004", "6222002"));

        ImportRowGrouper.Result result = grouper.group(rows, widgets(), PARENT);

        assertEquals(2, result.getGroups().size());
        assertTrue(result.getTableErrors().isEmpty());

        ImportRowGrouper.Group first = result.getGroups().get(0);
        assertEquals("A001", first.getMainData().get("code"));
        assertEquals("甲公司", first.getMainData().get("name"));
        assertEquals(3, first.getSubData().get("contacts").size());
        // 账户只在第一行填了，不该跟着开出三条空账户
        assertEquals(1, first.getSubData().get("banks").size());
        assertEquals("第 2~4 行", first.rowRangeText());
    }

    @Test
    @DisplayName("⚠️ 两张子表各自独立累积 —— 短的那张不跟着开空行")
    void testSubTablesAccumulateIndependently() {
        List<RawRow> rows = List.of(
                row(2, "A001", "甲公司", "王建国", "138", "6222001"),
                row(3, "A001", null, "李海燕", "139", null),
                row(4, "A001", null, null, null, "6222002"));

        ImportRowGrouper.Result result = grouper.group(rows, widgets(), PARENT);
        ImportRowGrouper.Group group = result.getGroups().get(0);

        assertEquals(2, group.getSubData().get("contacts").size());
        assertEquals(2, group.getSubData().get("banks").size());
    }

    @Test
    @DisplayName("没有子表的表单一行一组，不做合并")
    void testNoSubTableOneRowPerGroup() {
        List<RawRow> rows = List.of(
                row(2, "A001", "甲公司", null, null, null),
                row(3, "A001", "甲公司又一条", null, null, null));

        // 父键传空 = 单表表单
        ImportRowGrouper.Result result = grouper.group(rows, widgets(), List.of());

        assertEquals(2, result.getGroups().size());
    }

    @Test
    @DisplayName("同组内主表列第一行空、后面填了，用后面的补上")
    void testMainFieldFilledLater() {
        List<RawRow> rows = List.of(
                row(2, "A001", null, "王建国", "138", null),
                row(3, "A001", "甲公司", "李海燕", "139", null));

        ImportRowGrouper.Result result = grouper.group(rows, widgets(), PARENT);

        assertEquals("甲公司", result.getGroups().get(0).getMainData().get("name"));
        assertFalse(result.getGroups().get(0).hasError());
    }

    // ==================== 该报的不能猜 ====================

    @Test
    @DisplayName("⚠️ 父键乱序要报错并指出冲突行号 —— 不猜")
    void testUnsortedParentKeyReported() {
        List<RawRow> rows = List.of(
                row(2, "A001", "甲公司", "王建国", "138", null),
                row(3, "A002", "乙公司", "李海燕", "139", null),
                row(4, "A001", null, "张志强", "137", null));

        ImportRowGrouper.Result result = grouper.group(rows, widgets(), PARENT);

        assertEquals(1, result.getTableErrors().size());
        String message = result.getTableErrors().get(0).getMessage();
        assertTrue(message.contains("A001"));
        assertTrue(message.contains("第 2 行"));
        assertTrue(message.contains("必须按主记录排在一起"));
    }

    @Test
    @DisplayName("⚠️ 同组内主表列值不一致要报出来 —— 静默取第一行会丢掉用户填的值")
    void testMainFieldConflictReported() {
        List<RawRow> rows = List.of(
                row(2, "A001", "甲公司", "王建国", "138", null),
                row(3, "A001", "乙公司", "李海燕", "139", null));

        ImportRowGrouper.Result result = grouper.group(rows, widgets(), PARENT);
        ImportRowGrouper.Group group = result.getGroups().get(0);

        assertTrue(group.hasError());
        String message = group.getErrors().get(0).getMessage();
        assertTrue(message.contains("名称"));
        assertTrue(message.contains("甲公司"));
        assertTrue(message.contains("乙公司"));
    }

    @Test
    @DisplayName("⚠️ 父键为空的行单独成组并报错，不塞进上一组")
    void testBlankParentKeyReported() {
        List<RawRow> rows = List.of(
                row(2, "A001", "甲公司", "王建国", "138", null),
                row(3, null, null, "李海燕", "139", null));

        ImportRowGrouper.Result result = grouper.group(rows, widgets(), PARENT);

        assertEquals(2, result.getGroups().size());
        ImportRowGrouper.Group orphan = result.getGroups().get(1);
        assertTrue(orphan.hasError());
        assertTrue(orphan.getErrors().get(0).getMessage().contains("编码"));
    }

    @Test
    @DisplayName("⚠️ 分隔符用不可见字符 —— 「A|B」与「A」+「B」不能拼出同一个键")
    void testCompositeKeyNoCollision() {
        Map<String, ImportWidget> ws = registry.parse(FormFieldsCodec.write(List.of(
                "{\"type\":\"input\",\"field\":\"a\",\"title\":\"甲\"}",
                "{\"type\":\"input\",\"field\":\"b\",\"title\":\"乙\"}",
                "{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":["
                        + "{\"type\":\"input\",\"field\":\"x\",\"title\":\"项\"}]}}")), null);

        List<RawRow> rows = new ArrayList<>();
        RawRow r1 = new RawRow();
        r1.setRowNo(2);
        r1.getData().put("a", "A|B");
        r1.getData().put("b", "");
        r1.getData().put("x", "第一条");
        rows.add(r1);

        RawRow r2 = new RawRow();
        r2.setRowNo(3);
        r2.getData().put("a", "A");
        r2.getData().put("b", "B");
        r2.getData().put("x", "第二条");
        rows.add(r2);

        ImportRowGrouper.Result result = grouper.group(rows, ws, List.of("a", "b"));

        // 第一条的 b 是空 → 归不了组，单独成组并报错；
        // 关键是两者【没有】被合并成一条
        assertEquals(2, result.getGroups().size());
    }

    @Test
    @DisplayName("空的行集合返回空结果，不抛异常")
    void testEmptyRows() {
        ImportRowGrouper.Result result = grouper.group(List.of(), widgets(), PARENT);
        assertTrue(result.getGroups().isEmpty());
        assertTrue(result.getTableErrors().isEmpty());
    }

}
