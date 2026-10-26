package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.service.db.DatabaseTableService;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多值列（JSON 数组 / 分隔串）作查询条件时生成的 SQL
 *
 * <h3>这批测试守的是什么</h3>
 *
 * 多选控件落库不是一个标量：JSON 列存 {@code ["微生物","重金属"]}，
 * varchar 列存 {@code 微生物,重金属}。改动之前查询侧对此一无所知，
 * 一律按标量拼 {@code col = ?} / {@code col IN (?, ?)} —— 拿整串与单项比。
 *
 * <p>失败形态是<b>静默的</b>：不报错、不告警，只是查不出数据。
 * varchar 形态下更坏 —— 存量里「只勾了一项」的行存的就是裸值，它<b>能</b>被 IN 命中，
 * 于是表现是「有时查得到、有时查不到」，比一律查不到更难被当成 bug 报上来。
 *
 * <h3>🔴 另一半 equally 重要：不许回归</h3>
 *
 * {@code storedShape} 默认 null，走的是与改动前逐字节相同的分支。
 * 这不是「顺手加的兼容」，而是一道真实的保护：服务内部有三处也构造 EQ 条件
 * （业务主键校验、唯一性校验、导入判重），它们传的是<b>已 serialize 的整串</b>，
 * 语义就是「逐字相等」。给它们打上形态标记，判据会从「整串相等」松成「交集非空」，
 * 两条勾了部分相同选项的记录就被判成不重复 —— 静默放过脏数据。
 * {@link Regression} 那一组钉的就是这件事。
 *
 * @author 走进新时代
 */
class MultiValueQueryConditionTest {

    private static final Long DS_MAIN = 0L;
    private static final String TABLE = "biz_sample";

    private FormDesignSqlBuilder builder;

    @BeforeEach
    void setUp() {
        DatabaseTableService databaseTableService = mock(DatabaseTableService.class);
        builder = SqlBuilderTestFactory.create(databaseTableService);

        TableInfo tableInfo = mock(TableInfo.class);
        List<TableField> fields = Arrays.stream(new String[]{"id", "tags", "deleted",
                        "creator", "create_time", "updater", "update_time"})
                .map(col -> {
                    TableField field = mock(TableField.class);
                    when(field.getName()).thenReturn(col);
                    return field;
                })
                .toList();
        when(tableInfo.getFields()).thenReturn(fields);
        when(databaseTableService.getTable(eq(DS_MAIN), eq(TABLE))).thenReturn(tableInfo);
    }

    private TableBinding binding() {
        TableBinding binding = new TableBinding();
        binding.setMainTable(TABLE);
        binding.setMainPk("id");
        binding.setPkStrategy("AUTO");
        Map<String, FieldMapping> mapping = new LinkedHashMap<>();
        FieldMapping tags = new FieldMapping();
        tags.setTable(TABLE);
        tags.setColumn("tags");
        tags.setJdbcType("VARCHAR");
        mapping.put("tags", tags);
        binding.setFieldMapping(mapping);
        return binding;
    }

    private FormDesignSqlBuilder.QueryCondition condition(String operator, Object value,
                                                         MultiValueShape shape) {
        FormDesignSqlBuilder.QueryCondition c = new FormDesignSqlBuilder.QueryCondition();
        c.setField("tags");
        c.setOperator(operator);
        c.setValue(value);
        c.setStoredShape(shape);
        return c;
    }

    private FormDesignSqlBuilder.SqlWithParams page(FormDesignSqlBuilder.QueryCondition... conditions) {
        return builder.buildPage(DS_MAIN, binding(), Arrays.asList(conditions), null, null, 1, 10);
    }

    private FormDesignSqlBuilder.SqlWithParams count(FormDesignSqlBuilder.QueryCondition... conditions) {
        return builder.buildCount(DS_MAIN, binding(), Arrays.asList(conditions));
    }

    private static int placeholders(String sql) {
        return sql.length() - sql.replace("?", "").length();
    }

    /** 片段与参数必须一一对应：多一个少一个都是运行时必然报错 */
    private static void assertBalanced(FormDesignSqlBuilder.SqlWithParams result) {
        assertEquals(placeholders(result.getSql()), result.getParams().size(),
                "占位符数与参数数不一致，这条 SQL 跑起来必然报错：" + result.getSql());
    }

    // ==================== JSON 列 ====================

    @Nested
    @DisplayName("JSON 列：用 JSON_CONTAINS，不是 = 也不是 IN")
    class Json {

        @Test
        @DisplayName("IN 多项：逐项 OR 起来")
        void inMultipleValues() {
            FormDesignSqlBuilder.SqlWithParams result = page(
                    condition("IN", List.of("微生物", "重金属"), MultiValueShape.ofJson()));

            assertTrue(result.getSql().contains(
                            "(JSON_CONTAINS(`tags`, JSON_QUOTE(?)) OR JSON_CONTAINS(`tags`, JSON_QUOTE(?)))"),
                    result.getSql());
            assertTrue(result.getParams().containsAll(List.of("微生物", "重金属")),
                    "绑的应是裸字符串，JSON_QUOTE 在 SQL 里把它转成 JSON 标量");
            assertBalanced(result);
        }

        @Test
        @DisplayName("EQ 单值：一个 JSON_CONTAINS")
        void eqSingleValue() {
            FormDesignSqlBuilder.SqlWithParams result =
                    page(condition("EQ", "微生物", MultiValueShape.ofJson()));

            assertTrue(result.getSql().contains("JSON_CONTAINS(`tags`, JSON_QUOTE(?))"), result.getSql());
            // 单值不该套一层多余的括号
            assertFalse(result.getSql().contains("(JSON_CONTAINS"), result.getSql());
            assertBalanced(result);
        }

        @Test
        @DisplayName("🔴 不再出现拿整串比单项的 `tags` IN (?, ?)")
        void doesNotFallBackToScalarIn() {
            FormDesignSqlBuilder.SqlWithParams result =
                    page(condition("IN", List.of("微生物", "重金属"), MultiValueShape.ofJson()));

            assertFalse(result.getSql().contains("`tags` IN ("),
                    "JSON 列上的 IN 是拿整串与单项比，永远命中不了：" + result.getSql());
        }

        @Test
        @DisplayName("NE：整体取反，且括号把每一项都包住")
        void neNegatesWholeGroup() {
            FormDesignSqlBuilder.SqlWithParams result =
                    page(condition("NE", List.of("微生物", "重金属"), MultiValueShape.ofJson()));

            assertTrue(result.getSql().contains(
                            "(NOT (JSON_CONTAINS(`tags`, JSON_QUOTE(?)) OR JSON_CONTAINS(`tags`, JSON_QUOTE(?))))"),
                    "漏了外层括号的话，取反只作用到第一项 —— whereClauses 之间是 AND 连的："
                            + result.getSql());
            assertBalanced(result);
        }
    }

    // ==================== 分隔串列 ====================

    @Nested
    @DisplayName("分隔串列：逗号用 FIND_IN_SET，其它分隔符用边界匹配")
    class Delimited {

        @Test
        @DisplayName("IN 多项 + 逗号分隔：逐项 FIND_IN_SET")
        void inWithCommaSeparator() {
            FormDesignSqlBuilder.SqlWithParams result = page(
                    condition("IN", List.of("微生物", "重金属"), MultiValueShape.ofDelimited(",")));

            assertTrue(result.getSql().contains(
                            "(FIND_IN_SET(?, `tags`) > 0 OR FIND_IN_SET(?, `tags`) > 0)"),
                    result.getSql());
            assertBalanced(result);
        }

        @Test
        @DisplayName("EQ 单值 + 逗号分隔")
        void eqWithCommaSeparator() {
            FormDesignSqlBuilder.SqlWithParams result =
                    page(condition("EQ", "微生物", MultiValueShape.ofDelimited(",")));

            assertTrue(result.getSql().contains("FIND_IN_SET(?, `tags`) > 0"), result.getSql());
            assertBalanced(result);
        }

        @Test
        @DisplayName("🔴 分隔符不是逗号时不能仍用 FIND_IN_SET —— 它只认逗号")
        void nonCommaSeparatorUsesBoundaryMatch() {
            FormDesignSqlBuilder.SqlWithParams result =
                    page(condition("EQ", "微生物", MultiValueShape.ofDelimited("|")));

            assertFalse(result.getSql().contains("FIND_IN_SET"),
                    "FIND_IN_SET 硬编码逗号，分隔符是 | 时它会把整串当成一项：" + result.getSql());
            assertTrue(result.getSql().contains("CONCAT(?, `tags`, ?) LIKE CONCAT('%', ?, ?, ?, '%')"),
                    result.getSql());
            // 分隔符与值全部走绑定，一个字都不内联进 SQL ——
            // 分隔符来自控件契约声明（可改的配置），内联等于把配置值拼进语句。
            // 参数【顺序】也要钉住：CONCAT(?, col, ?) 的两个分隔符必须在值之前，
            // 顺序错了 SQL 语法上照样合法，只是永远匹配不上 —— 又是一次静默查空
            assertFalse(result.getSql().contains("|"), "分隔符不应出现在 SQL 文本里：" + result.getSql());
            assertEquals(List.of("|", "|", "|", "微生物", "|"),
                    result.getParams().subList(0, 5),
                    "条件自己的参数顺序不对（末尾还会有 LIMIT/OFFSET 两个）");
            assertBalanced(result);
        }

        @Test
        @DisplayName("分隔符为 null 时兜成逗号，口径与 FormValueSerializer#separator 一致")
        void nullSeparatorFallsBackToComma() {
            FormDesignSqlBuilder.SqlWithParams result =
                    page(condition("EQ", "微生物", MultiValueShape.ofDelimited(null)));

            assertTrue(result.getSql().contains("FIND_IN_SET(?, `tags`) > 0"), result.getSql());
        }
    }

    // ==================== 边界与共性 ====================

    @Test
    @DisplayName("🔴 值为空列表时整个条件被跳过，不生成 () 这种非法 SQL")
    void emptyValueListProducesNothing() {
        FormDesignSqlBuilder.SqlWithParams json =
                page(condition("IN", new ArrayList<>(), MultiValueShape.ofJson()));
        FormDesignSqlBuilder.SqlWithParams delimited =
                page(condition("IN", new ArrayList<>(), MultiValueShape.ofDelimited(",")));

        for (FormDesignSqlBuilder.SqlWithParams result : List.of(json, delimited)) {
            assertFalse(result.getSql().contains("()"), "空括号是非法 SQL：" + result.getSql());
            assertFalse(result.getSql().contains("JSON_CONTAINS"), result.getSql());
            assertFalse(result.getSql().contains("FIND_IN_SET"), result.getSql());
        }
    }

    @Test
    @DisplayName("🔴 值全是空白时同样跳过 —— 前端把空选项发上来是常态")
    void blankValuesProduceNothing() {
        FormDesignSqlBuilder.SqlWithParams result =
                page(condition("IN", Arrays.asList("", "  ", null), MultiValueShape.ofJson()));

        assertFalse(result.getSql().contains("JSON_CONTAINS"), result.getSql());
        assertFalse(result.getSql().contains("()"), result.getSql());
    }

    @Test
    @DisplayName("🔴 buildCount 与 buildPage 对同一个条件必须生成完全一致的 WHERE")
    void countAndPageAgreeOnWhere() {
        // 这是 FormDesignSqlBuilder 里已写明的不变式。两边一旦分叉，
        // 表现是分页器说「共 12 条」而页面只显示 3 条 —— 用户看到的是一堆空行
        MultiValueShape shape = MultiValueShape.ofJson();
        String pageWhere = whereOf(page(condition("IN", List.of("a", "b"), shape)));
        String countWhere = whereOf(count(condition("IN", List.of("a", "b"), shape)));

        assertEquals(pageWhere, countWhere);
    }

    private static String whereOf(FormDesignSqlBuilder.SqlWithParams result) {
        String sql = result.getSql();
        int from = sql.indexOf("WHERE");
        int to = sql.indexOf("LIMIT");
        return sql.substring(from, to < 0 ? sql.length() : to).trim();
    }

    @Test
    @DisplayName("IS_NULL / IS_NOT_NULL 在两种形态下写法相同，不必特殊处理")
    void nullChecksAreShapeIndependent() {
        String jsonSql = page(condition("IS_NULL", null, MultiValueShape.ofJson())).getSql();
        String scalarSql = page(condition("IS_NULL", null, null)).getSql();

        assertTrue(jsonSql.contains("`tags` IS NULL"), jsonSql);
        assertTrue(scalarSql.contains("`tags` IS NULL"), scalarSql);
    }

    @Test
    @DisplayName("LIKE 收到集合时逐项 OR，不再拼出 %[a, b]% 这种永远不会命中的模式")
    void likeWithCollectionDoesNotStringifyTheList() {
        FormDesignSqlBuilder.QueryCondition c = new FormDesignSqlBuilder.QueryCondition();
        c.setField("tags");
        c.setOperator("LIKE");
        c.setValue(List.of("微生物", "重金属"));

        FormDesignSqlBuilder.SqlWithParams result = page(c);

        assertTrue(result.getSql().contains("(`tags` LIKE ? OR `tags` LIKE ?)"), result.getSql());
        assertTrue(result.getParams().containsAll(List.of("%微生物%", "%重金属%")),
                result.getParams().toString());
        // 🔴 这条是原缺陷的字面形状：List.toString() 被当成一个值塞进模式里
        assertFalse(result.getParams().stream()
                        .anyMatch(p -> String.valueOf(p).contains("[") || String.valueOf(p).contains("]")),
                "参数里出现了集合的 toString()，这个模式永远不会命中：" + result.getParams());
        assertBalanced(result);
    }

    // ==================== 🔴 回归：不许改到标量列 ====================

    @Nested
    @DisplayName("🔴 回归：storedShape 为 null 时行为与改动前逐字节一致")
    class Regression {

        @Test
        @DisplayName("标量列的 IN 仍是 col IN (?, ?)")
        void scalarInUnchanged() {
            FormDesignSqlBuilder.SqlWithParams result =
                    page(condition("IN", List.of("微生物", "重金属"), null));

            assertTrue(result.getSql().contains("`tags` IN (?, ?)"), result.getSql());
            assertFalse(result.getSql().contains("JSON_CONTAINS"), result.getSql());
            assertFalse(result.getSql().contains("FIND_IN_SET"), result.getSql());
            assertBalanced(result);
        }

        @Test
        @DisplayName("标量列的 EQ 仍是 col = ? —— 判重与唯一性校验靠的就是这个语义")
        void scalarEqUnchanged() {
            // 服务内部三处（业务主键校验、唯一性校验、导入判重）传的是已 serialize 的整串，
            // 它们要的正是「逐字相等」。这条一旦变成 FIND_IN_SET，
            // 判据就松成「交集非空」，两条勾了部分相同选项的记录会被判成不重复
            FormDesignSqlBuilder.SqlWithParams result =
                    page(condition("EQ", "微生物,重金属", null));

            assertTrue(result.getSql().contains("`tags` = ?"), result.getSql());
            assertEquals(List.of("微生物,重金属"),
                    result.getParams().stream().filter("微生物,重金属"::equals).toList());
        }

        @Test
        @DisplayName("标量列的 LIKE 仍是单个 col LIKE ?")
        void scalarLikeUnchanged() {
            FormDesignSqlBuilder.QueryCondition c = new FormDesignSqlBuilder.QueryCondition();
            c.setField("tags");
            c.setOperator("LIKE");
            c.setValue("微");

            FormDesignSqlBuilder.SqlWithParams result = page(c);

            assertTrue(result.getSql().contains("`tags` LIKE ?"), result.getSql());
            assertTrue(result.getParams().contains("%微%"), result.getParams().toString());
        }
    }
}
