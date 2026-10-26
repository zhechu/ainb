package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImportHeaderMapper} 的单元测试
 *
 * <h3>这一层为什么值得单独测</h3>
 *
 * 表头匹配是导入功能里<b>最危险</b>的一环：按位置对应的实现（参考产品就是这么做的）
 * 在表头顺序变化时会把数据串到别的字段上，<b>而且不报错</b>。用户拿到的是
 * 一个「导入成功」的提示和一堆错位的数据，只有逐条比对才发现得了。
 *
 * 所以这里的用例分两类：
 * <ul>
 *   <li><b>该匹配上的</b> —— 顺序打乱、带装饰、全半角差异，都要能对上</li>
 *   <li><b>不该乱猜的</b> —— 模糊匹配撞了多个字段时必须放弃，宁可报「未识别」</li>
 * </ul>
 *
 * @author 走进新时代
 */
public class ImportHeaderMapperTest extends BaseMockitoUnitTest {

    private final ImportHeaderMapper mapper = new ImportHeaderMapper();
    private final ImportWidgetRegistry registry = new ImportWidgetRegistry();

    /** 造一份可导入字段清单 */
    private Map<String, ImportWidget> widgets(String... rules) {
        return registry.parse(FormFieldsCodec.write(List.of(rules)), null);
    }

    private static final String CODE =
            "{\"type\":\"input\",\"field\":\"supplier_code\",\"title\":\"供应商编码\",\"$required\":true}";
    private static final String NAME =
            "{\"type\":\"input\",\"field\":\"supplier_name\",\"title\":\"供应商名称\",\"$required\":true}";
    private static final String AMOUNT =
            "{\"type\":\"inputNumber\",\"field\":\"annual_amount\",\"title\":\"年营业额\"}";

    // ==================== 该匹配上的 ====================

    @Test
    @DisplayName("表头顺序打乱照样能对上 —— 这是按名字匹配的全部意义")
    void testShuffledHeaderMatches() {
        ImportHeaderMapper.Result result = mapper.map(
                Arrays.asList("年营业额", "供应商编码", "供应商名称"),
                widgets(CODE, NAME, AMOUNT));

        assertEquals("annual_amount", result.getColumnToField().get(0));
        assertEquals("supplier_code", result.getColumnToField().get(1));
        assertEquals("supplier_name", result.getColumnToField().get(2));
        assertTrue(result.getUnmatchedColumns().isEmpty());
    }

    @Test
    @DisplayName("表头带必填星号、括号说明、多余空格都能对上")
    void testDecoratedHeaderMatches() {
        ImportHeaderMapper.Result result = mapper.map(
                Arrays.asList("供应商编码 *", "供 应 商 名 称", "年营业额（元）"),
                widgets(CODE, NAME, AMOUNT));

        assertEquals("supplier_code", result.getColumnToField().get(0));
        assertEquals("supplier_name", result.getColumnToField().get(1));
        assertEquals("annual_amount", result.getColumnToField().get(2));
    }

    @Test
    @DisplayName("全角括号、全角冒号与半角一视同仁")
    void testFullWidthHeaderMatches() {
        ImportHeaderMapper.Result result = mapper.map(
                Arrays.asList("供应商编码：", "供应商名称（必填）"),
                widgets(CODE, NAME));

        assertEquals("supplier_code", result.getColumnToField().get(0));
        assertEquals("supplier_name", result.getColumnToField().get(1));
    }

    @Test
    @DisplayName("表头直接填字段名也能对上 —— 导出文件与技术人员手写的都要能进")
    void testFieldNameHeaderMatches() {
        ImportHeaderMapper.Result result = mapper.map(
                Arrays.asList("supplier_code", "SUPPLIER_NAME"),
                widgets(CODE, NAME));

        assertEquals("supplier_code", result.getColumnToField().get(0));
        assertEquals("supplier_name", result.getColumnToField().get(1));
    }

    @Test
    @DisplayName("列表列的 label 与字段 title 不同时，两个都要能匹配")
    void testColumnLabelMatches() {
        String listConf = "{\"columns\":[{\"field\":\"supplier_code\",\"label\":\"编号（含年份）\"}]}";
        Map<String, ImportWidget> ws =
                registry.parse(FormFieldsCodec.write(List.of(CODE, NAME)), listConf);

        // 用导出文件里的表头（列 label）
        ImportHeaderMapper.Result byLabel = mapper.map(List.of("编号（含年份）"), ws);
        assertEquals("supplier_code", byLabel.getColumnToField().get(0));

        // 用字段标题
        ImportHeaderMapper.Result byTitle = mapper.map(List.of("供应商编码"), ws);
        assertEquals("supplier_code", byTitle.getColumnToField().get(0));
    }

    // ==================== 不该乱猜的 ====================

    @Test
    @DisplayName("⚠️ 认不出的列进「未识别」，不猜也不报错")
    void testUnmatchedColumnReported() {
        ImportHeaderMapper.Result result = mapper.map(
                Arrays.asList("供应商编码", "内部备注2"), widgets(CODE, NAME));

        assertEquals("supplier_code", result.getColumnToField().get(0));
        assertEquals(1, result.getUnmatchedColumns().size());
        assertEquals("B", result.getUnmatchedColumns().get(0).getColumnLetter());
        assertEquals("内部备注2", result.getUnmatchedColumns().get(0).getHeader());
    }

    @Test
    @DisplayName("⚠️ 同一个字段匹配到两列时保留第一列，第二列报重复")
    void testDuplicateColumnKeepsFirst() {
        ImportHeaderMapper.Result result = mapper.map(
                Arrays.asList("供应商编码", "供应商名称", "supplier_code"),
                widgets(CODE, NAME));

        // 第一列胜出。反过来（后者覆盖前者）会让 A 列的值被 C 列悄悄覆盖
        assertEquals("supplier_code", result.getColumnToField().get(0));
        assertNull(result.getColumnToField().get(2));
        assertEquals(1, result.getDuplicateColumns().size());
        assertEquals("C", result.getDuplicateColumns().get(0).getColumnLetter());
        assertEquals("A", result.getDuplicateColumns().get(0).getFirstColumnLetter());
    }

    @Test
    @DisplayName("⚠️ 必填字段在表头里找不到时要报出来")
    void testMissingRequiredReported() {
        ImportHeaderMapper.Result result = mapper.map(
                List.of("供应商编码"), widgets(CODE, NAME));

        assertEquals(List.of("供应商名称"), result.getMissingRequiredFields());
    }

    @Test
    @DisplayName("⚠️ 模糊匹配撞上多个字段时放弃匹配 —— 猜错的代价是数据串字段")
    void testAmbiguousFuzzyMatchGivesUp() {
        String start = "{\"type\":\"datePicker\",\"field\":\"start_date\",\"title\":\"开始日期时间\"}";
        String end = "{\"type\":\"datePicker\",\"field\":\"end_date\",\"title\":\"结束日期时间\"}";
        // 「日期时间」同时是两个字段标题的子串
        ImportHeaderMapper.Result result = mapper.map(
                List.of("日期时间"), widgets(start, end));

        assertTrue(result.getColumnToField().isEmpty());
        assertEquals(1, result.getUnmatchedColumns().size());
    }

    @Test
    @DisplayName("空表头列直接跳过，不当成「未识别」刷屏")
    void testBlankHeaderSkipped() {
        ImportHeaderMapper.Result result = mapper.map(
                Arrays.asList("供应商编码", "", null, "   "), widgets(CODE));

        assertEquals(1, result.getColumnToField().size());
        assertTrue(result.getUnmatchedColumns().isEmpty());
    }

    @Test
    @DisplayName("公式字段缺列不算「缺必填」—— 它的值本来就由服务端算")
    void testFormulaFieldNotRequired() {
        String formula = "{\"type\":\"Formula\",\"field\":\"limit\",\"title\":\"授信额度\","
                + "\"$required\":true,\"props\":{\"expression\":\"{annual_amount} * 0.3\"}}";
        ImportHeaderMapper.Result result = mapper.map(
                List.of("供应商编码"), widgets(CODE, formula));

        assertFalse(result.getMissingRequiredFields().contains("授信额度"));
    }

    // ==================== 列字母 ====================

    @Test
    @DisplayName("列字母按 Excel 的规则算 —— 报错时用户是拿它去定位的")
    void testColumnLetter() {
        assertEquals("A", ImportHeaderMapper.columnLetter(0));
        assertEquals("Z", ImportHeaderMapper.columnLetter(25));
        assertEquals("AA", ImportHeaderMapper.columnLetter(26));
        assertEquals("AB", ImportHeaderMapper.columnLetter(27));
        assertEquals("BA", ImportHeaderMapper.columnLetter(52));
    }

}
