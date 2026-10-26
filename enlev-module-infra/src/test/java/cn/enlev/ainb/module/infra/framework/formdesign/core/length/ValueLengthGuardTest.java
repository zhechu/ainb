package cn.enlev.ainb.module.infra.framework.formdesign.core.length;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.JdbcTypeFamily;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import cn.enlev.ainb.module.infra.framework.formdesign.core.meta.TableMetadataCache;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import org.apache.ibatis.type.JdbcType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提交期超长值拦截的测试
 *
 * 这些用例守的是设计方案 §9.3 第 14 条：向 varchar(255) 提交 10KB 内容，
 * 必须在服务端拦下并给出可读错误，而不是抛一个数据库层的 500。
 *
 * 重点不在「超了会被拦」那一条，而在三件容易做错的：
 *
 * <ol>
 *   <li><b>单位</b>：varchar 的上限是字符数、TEXT 的上限是字节数。
 *       混用的话，两万字的中文富文本会被判成「没超」，然后在写库时报 1406</li>
 *   <li><b>不误拦</b>：正好卡在边界上的值、emoji、没提交的字段、非字符串值</li>
 *   <li><b>报错要能定位</b>：用字段标题不用列名，子表多行要合并成一条</li>
 * </ol>
 *
 * @author 走进新时代
 */
public class ValueLengthGuardTest extends BaseMockitoUnitTest {

    private static final String MAIN = "biz_order";
    private static final String SUB = "biz_order_item";

    @InjectMocks
    private ValueLengthGuard guard;

    @Mock
    private TableMetadataCache metadataCache;
    @Mock
    private WidgetContractRegistry contractRegistry;

    @BeforeEach
    public void setUp() {
        // 类型族的判定本身有 WidgetContractRegistry 自己的用例守着，这里只关心分支
        lenient().when(contractRegistry.resolveJdbcTypeFamily("VARCHAR"))
                .thenReturn(JdbcTypeFamily.STRING);
        lenient().when(contractRegistry.resolveJdbcTypeFamily("LONGVARCHAR"))
                .thenReturn(JdbcTypeFamily.TEXT);
        lenient().when(contractRegistry.resolveJdbcTypeFamily("DECIMAL"))
                .thenReturn(JdbcTypeFamily.DECIMAL);
    }

    // ==================== 造数据 ====================

    /**
     * 造一个列
     *
     * ⚠️ 用 mock 而不是 new：{@code TableField.MetaInfo} 只有一个
     * {@code (DatabaseMetaDataWrapper.Column, TableInfo)} 构造器、没有 setter ——
     * 那个 Column 要从真实的 JDBC 元数据里来。为了两个字段（jdbcType、length）
     * 去搭一套真实元数据不值得，本类要验的也不是 MyBatis-Plus 怎么解析表结构。
     */
    private TableField column(String name, JdbcType jdbcType, int length) {
        TableField.MetaInfo meta = mock(TableField.MetaInfo.class);
        lenient().when(meta.getJdbcType()).thenReturn(jdbcType);
        lenient().when(meta.getLength()).thenReturn(length);
        TableField field = mock(TableField.class);
        lenient().when(field.getName()).thenReturn(name);
        lenient().when(field.getMetaInfo()).thenReturn(meta);
        return field;
    }

    private void givenColumns(String table, TableField... fields) {
        Map<String, TableField> map = new LinkedHashMap<>();
        for (TableField field : fields) {
            map.put(field.getName().toLowerCase(), field);
        }
        when(metadataCache.columns(any(), eq(table))).thenReturn(map);
    }

    private TableBinding binding(Map<String, FieldMapping> mapping) {
        TableBinding binding = new TableBinding();
        binding.setMainTable(MAIN);
        binding.setMainPk("id");
        binding.setFieldMapping(mapping);
        return binding;
    }

    private Map<String, FieldMapping> mapping(String field, String column) {
        FieldMapping fm = new FieldMapping();
        fm.setColumn(column);
        Map<String, FieldMapping> map = new LinkedHashMap<>();
        map.put(field, fm);
        return map;
    }

    private String formFields(String field, String title) {
        return FormFieldsCodec.write(List.of(
                String.format("{\"type\":\"input\",\"field\":\"%s\",\"title\":\"%s\"}", field, title)));
    }

    private String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    // ==================== varchar：按字符数 ====================

    @Test
    @DisplayName("varchar(255) 收到 10KB 内容：拦下，并说清是哪个字段、超了多少、上限多少")
    public void testVarcharTooLong() {
        givenColumns(MAIN, column("remark", JdbcType.VARCHAR, 255));

        ValueLengthGuard.ValidationResult result = guard.validate(0L,
                formFields("remark", "备注"), binding(mapping("remark", "remark")),
                Map.of("remark", repeat("a", 10240)));

        assertFalse(result.isPassed());
        String message = result.toMessage();
        // 用字段标题而不是列名 —— 用户在界面上看到的是「备注」
        assertTrue(message.contains("「备注」"), message);
        assertTrue(message.contains("10240 字符"), message);
        assertTrue(message.contains("上限 255 字符"), message);
        // 列名也要给：实施人员据此决定要不要改表
        assertTrue(message.contains("remark"), message);
    }

    @Test
    @DisplayName("正好等于上限：放行。边界差一个就是一次「配对了却存不进去」")
    public void testExactlyAtLimit() {
        givenColumns(MAIN, column("remark", JdbcType.VARCHAR, 255));

        ValueLengthGuard.ValidationResult result = guard.validate(0L,
                formFields("remark", "备注"), binding(mapping("remark", "remark")),
                Map.of("remark", repeat("a", 255)));

        assertTrue(result.isPassed(), result.toMessage());
    }

    @Test
    @DisplayName("中文按字符算，不按字节 —— varchar(10) 放得下 10 个汉字")
    public void testChineseCountsAsChars() {
        givenColumns(MAIN, column("name", JdbcType.VARCHAR, 10));

        // 10 个汉字在 utf8mb4 下是 30 字节，但 varchar(10) 的 10 是字符数。
        // 按字节比的话这里会被误拦，而它其实存得进去
        ValueLengthGuard.ValidationResult result = guard.validate(0L,
                formFields("name", "名称"), binding(mapping("name", "name")),
                Map.of("name", repeat("中", 10)));

        assertTrue(result.isPassed(), result.toMessage());
    }

    @Test
    @DisplayName("emoji 算 1 个字符，不是 2 —— String.length() 数的是 UTF-16 代码单元")
    public void testEmojiCountsAsOneCodePoint() {
        givenColumns(MAIN, column("name", JdbcType.VARCHAR, 4));

        // "😀😀😀😀" 的 String.length() 是 8（4 个代理对），码点数是 4。
        // MySQL 算 4，所以 varchar(4) 放得下。用 length() 的话这里会被误拦
        ValueLengthGuard.ValidationResult result = guard.validate(0L,
                formFields("name", "名称"), binding(mapping("name", "name")),
                Map.of("name", repeat("😀", 4)));

        assertTrue(result.isPassed(), result.toMessage());
    }

    // ==================== TEXT：按字节数 ====================

    @Test
    @DisplayName("⚠️ TEXT 的上限是字节数：三万字中文超出 65535，按字符数比会漏判")
    public void testTextCountsBytes() {
        givenColumns(MAIN, column("detail", JdbcType.LONGVARCHAR, 65535));

        // 30000 个汉字 = 30000 字符（< 65535，按字符数比「没超」）
        //             = 90000 字节（> 65535，实际写库会报 1406）
        String value = repeat("中", 30000);
        ValueLengthGuard.ValidationResult result = guard.validate(0L,
                formFields("detail", "详情"), binding(mapping("detail", "detail")),
                Map.of("detail", value));

        assertFalse(result.isPassed(), "按字符数比会漏掉这一条 —— 那正是这条用例要防的");
        assertTrue(result.toMessage().contains("90000 字节"), result.toMessage());
        assertTrue(result.toMessage().contains("上限 65535 字节"), result.toMessage());
    }

    // ==================== 不该管的 ====================

    @Test
    @DisplayName("非字符串值不量：数字列即便很长也不是「内容过长」")
    public void testNonStringSkipped() {
        // 刻意不 stub 元数据：非字符串值应当在取列信息【之前】就被跳过。
        // 这一条最初是 stub 了的，跑出来报 UnnecessaryStubbing —— 那恰好证明了
        // 这条短路是有效的，于是改成直接断言「一次元数据都没读」
        ValueLengthGuard.ValidationResult result = guard.validate(0L,
                formFields("amount", "金额"), binding(mapping("amount", "amount")),
                Map.of("amount", 123456789012345L));

        assertTrue(result.isPassed(), result.toMessage());
        verify(metadataCache, never()).columns(any(), any());
    }

    @Test
    @DisplayName("没提交的字段不量 —— 更新是局部的，没提交的键压根不会进 SQL")
    public void testAbsentFieldSkipped() {
        ValueLengthGuard.ValidationResult result = guard.validate(0L,
                formFields("remark", "备注"), binding(mapping("remark", "remark")),
                Map.of("other", "x"));

        assertTrue(result.isPassed());
        // 更要紧的是：一次都没去读元数据。绝大多数提交里没有需要量的字段，
        // 那种情况下不该有任何额外开销
        verify(metadataCache, never()).columns(any(), any());
    }

    @Test
    @DisplayName("元数据里没有长度（有些驱动对 TEXT 返回 0）：放过，不凭猜测拦")
    public void testUnknownLengthSkipped() {
        givenColumns(MAIN, column("detail", JdbcType.LONGVARCHAR, 0));

        ValueLengthGuard.ValidationResult result = guard.validate(0L,
                formFields("detail", "详情"), binding(mapping("detail", "detail")),
                Map.of("detail", repeat("a", 100000)));

        assertTrue(result.isPassed(), result.toMessage());
    }

    @Test
    @DisplayName("表读不到时不拦：表不存在由别的校验负责报错，这里不能因此把提交挡下来")
    public void testTableMissing() {
        when(metadataCache.columns(any(), eq(MAIN))).thenReturn(Map.of());

        ValueLengthGuard.ValidationResult result = guard.validate(0L,
                formFields("remark", "备注"), binding(mapping("remark", "remark")),
                Map.of("remark", repeat("a", 10240)));

        assertTrue(result.isPassed());
    }

    // ==================== 汇总与定位 ====================

    @Test
    @DisplayName("多个字段超长一次性全报，不是报一条就返回")
    public void testAggregate() {
        givenColumns(MAIN,
                column("remark", JdbcType.VARCHAR, 10),
                column("memo", JdbcType.VARCHAR, 10));
        Map<String, FieldMapping> mapping = mapping("remark", "remark");
        mapping.putAll(mapping("memo", "memo"));
        String fields = FormFieldsCodec.write(List.of(
                "{\"type\":\"input\",\"field\":\"remark\",\"title\":\"备注\"}",
                "{\"type\":\"input\",\"field\":\"memo\",\"title\":\"说明\"}"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("remark", repeat("a", 50));
        data.put("memo", repeat("b", 50));
        ValueLengthGuard.ValidationResult result =
                guard.validate(0L, fields, binding(mapping), data);

        assertFalse(result.isPassed());
        // 改完一处再提交又被拦下一处，来回好几轮 —— 这是本模块反复在避免的
        assertEquals(2, result.getErrors().size(), result.toMessage());
        assertTrue(result.toMessage().contains("「备注」"));
        assertTrue(result.toMessage().contains("「说明」"));
    }

    @Test
    @DisplayName("⚠️ 子表同一列多行超长合并成一条，不是一行一条")
    public void testSubTableRowsMerged() {
        givenColumns(SUB, column("note", JdbcType.VARCHAR, 5));

        SubTableBinding sub = new SubTableBinding();
        sub.setField("items");
        sub.setTable(SUB);
        sub.setFieldMapping(mapping("note", "note"));
        TableBinding binding = binding(new LinkedHashMap<>());
        binding.setSubTables(List.of(sub));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(Map.of("note", repeat("x", 50)));
        }
        String fields = FormFieldsCodec.write(List.of(
                "{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":["
                        + "{\"type\":\"input\",\"field\":\"note\",\"title\":\"备注\"}]}}"));

        ValueLengthGuard.ValidationResult result =
                guard.validate(0L, fields, binding, Map.of("items", rows));

        assertFalse(result.isPassed());
        // 30 条错误是刷屏，用户反而找不到重点。行号文案与子表行数校验共用
        // FormErrorText.rows —— 各写一套的话用户会以为是两套不同的东西
        assertEquals(1, result.getErrors().size(), result.toMessage());
        assertTrue(result.toMessage().contains("等 30 行"), result.toMessage());
        // 子表控件的标题与明细列的标题都要有：只报「备注」的话，
        // 一张表单里有两个子表时用户不知道是哪一个
        assertTrue(result.toMessage().contains("明细"), result.toMessage());
        assertTrue(result.toMessage().contains("「备注」"), result.toMessage());
    }

    @Test
    @DisplayName("报错里带值的开头 —— 富文本看不出是哪一段内容")
    public void testPreview() {
        givenColumns(MAIN, column("remark", JdbcType.VARCHAR, 5));

        ValueLengthGuard.ValidationResult result = guard.validate(0L,
                formFields("remark", "备注"), binding(mapping("remark", "remark")),
                Map.of("remark", "这是一段很长的说明文字，用来验证报错里会回显开头"));

        assertFalse(result.isPassed());
        assertTrue(result.toMessage().contains("这是一段很长的说明文字"), result.toMessage());
        // 但不能把整段塞进去：拦下来的值动辄几 KB
        assertTrue(result.toMessage().length() < 200, result.toMessage());
    }

}
