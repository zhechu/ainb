package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.AutoFillConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.JoinTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.LogicDeleteConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SideTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.service.db.DatabaseTableService;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FormDesignSqlBuilder} 的单元测试
 *
 * 重点验证两件事：
 * 1. 生成的 SQL 结构正确（列数与占位符数一致，否则运行时必然报错）
 * 2. 值全部走参数绑定，SQL 里不出现字面量
 *
 * 用真实的 SqlIdentifierGuard 配 mock 的元数据服务，这样能顺带验证两者协作。
 *
 * @author 走进新时代
 */
class FormDesignSqlBuilderTest {

    private static final Long DS_MAIN = 0L;
    private static final String TABLE = "biz_customer";
    private static final Long USER_ID = 1L;

    private FormDesignSqlBuilder builder;
    private DatabaseTableService databaseTableService;

    @BeforeEach
    void setUp() {
        databaseTableService = mock(DatabaseTableService.class);

        // 协作者较多，装配统一放在 SqlBuilderTestFactory
        builder = SqlBuilderTestFactory.create(databaseTableService);
        mockTable(TABLE, "id", "name", "level", "deleted",
                "creator", "create_time", "updater", "update_time");
    }

    private void mockTable(String tableName, String... columns) {
        TableInfo tableInfo = mock(TableInfo.class);
        List<TableField> fields = Arrays.stream(columns)
                .map(col -> {
                    TableField field = mock(TableField.class);
                    when(field.getName()).thenReturn(col);
                    return field;
                })
                .toList();
        when(tableInfo.getFields()).thenReturn(fields);
        when(databaseTableService.getTable(eq(DS_MAIN), eq(tableName))).thenReturn(tableInfo);
    }

    /**
     * 构造基础绑定配置：主表 + 两个字段映射
     */
    private TableBinding binding() {
        TableBinding binding = new TableBinding();
        binding.setMainTable(TABLE);
        binding.setMainPk("id");
        binding.setPkStrategy("AUTO");

        Map<String, FieldMapping> mapping = new LinkedHashMap<>();
        mapping.put("customerName", fieldMapping("name", "VARCHAR"));
        mapping.put("customerLevel", fieldMapping("level", "TINYINT"));
        binding.setFieldMapping(mapping);
        return binding;
    }

    private FieldMapping fieldMapping(String column, String jdbcType) {
        FieldMapping mapping = new FieldMapping();
        mapping.setTable(TABLE);
        mapping.setColumn(column);
        mapping.setJdbcType(jdbcType);
        return mapping;
    }

    /**
     * 逻辑删除配置。不显式设 valueType，用默认的 NUMBER —— 与项目里 bit/tinyint 的惯例一致
     */
    private LogicDeleteConfig logicDelete() {
        LogicDeleteConfig ld = new LogicDeleteConfig();
        ld.setEnabled(true);
        ld.setColumn("deleted");
        ld.setDeletedValue("1");
        ld.setNormalValue("0");
        return ld;
    }

    private AutoFillConfig autoFill() {
        AutoFillConfig af = new AutoFillConfig();
        af.setCreatorColumn("creator");
        af.setCreateTimeColumn("create_time");
        af.setUpdaterColumn("updater");
        af.setUpdateTimeColumn("update_time");
        return af;
    }

    /**
     * 统计 SQL 里的占位符数量
     */
    private int countPlaceholders(String sql) {
        return (int) sql.chars().filter(c -> c == '?').count();
    }

    // ==================== INSERT ====================

    @Test
    @DisplayName("INSERT：列数与占位符数一致，参数顺序对应")
    void testInsertStructure() {
        Map<String, Object> data = Map.of("customerName", "张三", "customerLevel", 2);
        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildInsert(DS_MAIN, binding(), data, USER_ID, null);

        assertTrue(result.getSql().startsWith("INSERT INTO `biz_customer`"));
        assertEquals(countPlaceholders(result.getSql()), result.getParams().size(),
                "占位符数量必须等于参数数量，否则运行时报错");
        assertEquals(2, result.getParams().size(), "两个字段映射对应两个参数");
    }

    @Test
    @DisplayName("INSERT：值不出现在 SQL 里，全部走参数绑定")
    void testInsertValuesNotInlined() {
        Map<String, Object> data = Map.of("customerName", "'; DROP TABLE users--");
        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildInsert(DS_MAIN, binding(), data, USER_ID, null);

        assertFalse(result.getSql().contains("DROP"),
                "注入串必须作为参数传递，不能出现在 SQL 中：" + result.getSql());
        assertTrue(result.getParams().contains("'; DROP TABLE users--"));
    }

    @Test
    @DisplayName("INSERT：主键策略非 AUTO 时带上主键列")
    void testInsertWithExplicitPk() {
        TableBinding binding = binding();
        binding.setPkStrategy("SNOWFLAKE");
        Map<String, Object> data = Map.of("customerName", "张三");

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildInsert(DS_MAIN, binding, data, USER_ID, 123456L);

        assertTrue(result.getSql().contains("`id`"), "应包含主键列");
        assertTrue(result.getParams().contains(123456L));
        assertEquals(countPlaceholders(result.getSql()), result.getParams().size());
    }

    @Test
    @DisplayName("INSERT：自动填充四个审计字段")
    void testInsertWithAutoFill() {
        TableBinding binding = binding();
        binding.setAutoFill(autoFill());
        Map<String, Object> data = Map.of("customerName", "张三");

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildInsert(DS_MAIN, binding, data, USER_ID, null);

        String sql = result.getSql();
        assertTrue(sql.contains("`creator`"));
        assertTrue(sql.contains("`create_time`"));
        assertTrue(sql.contains("`updater`"));
        assertTrue(sql.contains("`update_time`"));
        assertEquals(countPlaceholders(sql), result.getParams().size());
    }

    @Test
    @DisplayName("INSERT：启用逻辑删除时填入未删除值")
    void testInsertWithLogicDelete() {
        TableBinding binding = binding();
        binding.setLogicDelete(logicDelete());
        Map<String, Object> data = Map.of("customerName", "张三");

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildInsert(DS_MAIN, binding, data, USER_ID, null);

        assertTrue(result.getSql().contains("`deleted`"));
        assertTrue(result.getParams().contains(0L),
                "新增时逻辑删除列应为未删除值，且已按 valueType 转成数字 —— "
                        + "绑字符串给 bit/tinyint 列会报 Data truncation");
    }

    // ==================== UPDATE ====================

    @Test
    @DisplayName("UPDATE：SET 与 WHERE 结构正确，主键在最后")
    void testUpdateStructure() {
        Map<String, Object> data = Map.of("customerName", "李四", "customerLevel", 3);
        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildUpdate(DS_MAIN, binding(), data, USER_ID, 1L);

        String sql = result.getSql();
        assertTrue(sql.startsWith("UPDATE `biz_customer` SET"));
        assertTrue(sql.contains("WHERE `id` = ?"));
        assertEquals(countPlaceholders(sql), result.getParams().size());
        assertEquals(1L, result.getParams().get(result.getParams().size() - 1),
                "主键参数应在最后（SET 的参数在前）");
    }

    @Test
    @DisplayName("UPDATE：启用逻辑删除时 WHERE 追加未删除条件")
    void testUpdateWithLogicDelete() {
        TableBinding binding = binding();
        binding.setLogicDelete(logicDelete());
        Map<String, Object> data = Map.of("customerName", "李四");

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildUpdate(DS_MAIN, binding, data, USER_ID, 1L);

        assertTrue(result.getSql().contains("`deleted` = ?"),
                "已逻辑删除的记录不该被更新");
        assertEquals(countPlaceholders(result.getSql()), result.getParams().size());
    }

    @Test
    @DisplayName("⚠️ UPDATE：没有可更新的列时报错，而不是生成非法 SQL")
    void testUpdateWithNoColumnsThrows() {
        TableBinding binding = binding();
        binding.setFieldMapping(new HashMap<>());

        assertThrows(IllegalArgumentException.class,
                () -> builder.buildUpdate(DS_MAIN, binding, Map.of(), USER_ID, 1L));
    }

    // ==================== DELETE ====================

    @Test
    @DisplayName("DELETE：未配逻辑删除时生成物理 DELETE")
    void testPhysicalDelete() {
        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildDelete(DS_MAIN, binding(), USER_ID, 1L);

        assertTrue(result.getSql().startsWith("DELETE FROM `biz_customer`"));
        assertTrue(result.getSql().contains("WHERE `id` = ?"));
        assertEquals(1, result.getParams().size());
    }

    @Test
    @DisplayName("DELETE：配了逻辑删除时改为 UPDATE 标记")
    void testLogicDelete() {
        TableBinding binding = binding();
        binding.setLogicDelete(logicDelete());
        binding.setAutoFill(autoFill());

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildDelete(DS_MAIN, binding, USER_ID, 1L);

        String sql = result.getSql();
        assertTrue(sql.startsWith("UPDATE `biz_customer` SET"), "逻辑删除应生成 UPDATE");
        assertFalse(sql.contains("DELETE"), "不应出现物理删除");
        assertTrue(sql.contains("`deleted` = ?"));
        assertTrue(result.getParams().contains(1L), "应填入已删除值（数字形态）");
        assertEquals(countPlaceholders(sql), result.getParams().size());
    }

    // ==================== 系统字段的填充方式 ====================

    /**
     * 这组测试对应需求 4 暴露出的既有缺陷：改动前 buildInsert 无条件追加 autoFill 的列，
     * 若用户又在表单里把「创建人」控件映射到 creator，同一列会被拼两次，
     * 生成 INSERT INTO t (creator, creator) VALUES (?, ?)，MySQL 直接报错。
     * 修复方式是按 mode 决定后端写不写。
     */
    @Test
    @DisplayName("填充方式 MANUAL：后端跳过该列，交给表单字段映射")
    void testAutoFillModeManualSkipsColumn() {
        TableBinding binding = binding();
        AutoFillConfig af = autoFill();
        af.setCreatorMode("MANUAL");
        binding.setAutoFill(af);
        // 表单把「创建人」控件映射到 creator 列
        binding.getFieldMapping().put("createdBy", fieldMapping("creator", "BIGINT"));

        FormDesignSqlBuilder.SqlWithParams result = builder.buildInsert(
                DS_MAIN, binding, Map.of("customerName", "张三", "createdBy", 99L), USER_ID, null);

        String sql = result.getSql();
        assertEquals(1, countOccurrences(sql, "`creator`"),
                "creator 只能出现一次，否则是 INSERT INTO t (creator, creator) 这种非法 SQL：" + sql);
        assertTrue(result.getParams().contains(99L), "该列的值应取表单填的值");
        assertEquals(countPlaceholders(sql), result.getParams().size());
    }

    @Test
    @DisplayName("填充方式 NONE：两边都不写，保持数据库默认值")
    void testAutoFillModeNoneSkipsColumn() {
        TableBinding binding = binding();
        AutoFillConfig af = autoFill();
        af.setCreateTimeMode("NONE");
        binding.setAutoFill(af);

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildInsert(DS_MAIN, binding, Map.of("customerName", "张三"), USER_ID, null);

        assertFalse(result.getSql().contains("`create_time`"),
                "NONE 模式不该写该列，让数据库默认值生效：" + result.getSql());
    }

    @Test
    @DisplayName("填充方式 AUTO：后端照旧填充（默认行为）")
    void testAutoFillModeAutoFillsColumn() {
        TableBinding binding = binding();
        binding.setAutoFill(autoFill());

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildInsert(DS_MAIN, binding, Map.of("customerName", "张三"), USER_ID, null);

        assertTrue(result.getSql().contains("`creator`"));
        assertTrue(result.getParams().contains(USER_ID), "AUTO 模式应填当前登录人");
    }

    @Test
    @DisplayName("⚠️ 填充方式缺失：回退 AUTO，保持历史配置的原有行为")
    void testAutoFillModeMissingFallsBackToAuto() {
        TableBinding binding = binding();
        AutoFillConfig af = autoFill();
        // 模拟本次改动之前保存的配置：只有列名没有 mode
        af.setCreatorMode(null);
        af.setCreateTimeMode(null);
        af.setUpdaterMode(null);
        af.setUpdateTimeMode(null);
        binding.setAutoFill(af);

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildInsert(DS_MAIN, binding, Map.of("customerName", "张三"), USER_ID, null);

        assertTrue(result.getSql().contains("`creator`"), "缺失时应按 AUTO 处理");
        assertTrue(result.getParams().contains(USER_ID));
    }

    @Test
    @DisplayName("UPDATE：MANUAL 的 updater 不被后端覆盖")
    void testUpdateRespectsManualMode() {
        TableBinding binding = binding();
        AutoFillConfig af = autoFill();
        af.setUpdaterMode("MANUAL");
        binding.setAutoFill(af);
        binding.getFieldMapping().put("updatedBy", fieldMapping("updater", "BIGINT"));

        FormDesignSqlBuilder.SqlWithParams result = builder.buildUpdate(
                DS_MAIN, binding, Map.of("customerName", "李四", "updatedBy", 88L), USER_ID, 1L);

        assertEquals(1, countOccurrences(result.getSql(), "`updater`"),
                "updater 在 SET 里只能出现一次：" + result.getSql());
        assertTrue(result.getParams().contains(88L));
    }

    /**
     * 统计子串出现次数
     */
    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    // ==================== 逻辑删除的值类型 ====================

    /**
     * 这组测试对应一个实测缺陷：test_customer 表的 deleted 列是 BIT，
     * 而配置里的删除值是字符串 "0"，绑参时 MySQL 抛
     * 「Data truncation: Data too long for column 'deleted'」，新增数据直接失败。
     * 修复方式是按 valueType 在绑参前转换类型。
     */
    @Test
    @DisplayName("⚠️ 逻辑删除 NUMBER：字符串配置值转成 Long，避免 bit 列 Data truncation")
    void testLogicDeleteValueTypeNumber() {
        TableBinding binding = binding();
        LogicDeleteConfig ld = logicDelete();
        ld.setValueType("NUMBER");
        binding.setLogicDelete(ld);

        FormDesignSqlBuilder.SqlWithParams insert =
                builder.buildInsert(DS_MAIN, binding, Map.of("customerName", "张三"), USER_ID, null);

        assertTrue(insert.getParams().contains(0L), "未删除值应为 Long 0 而非字符串 \"0\"");
        assertFalse(insert.getParams().contains("0"), "不该再绑字符串，否则 bit 列报错");
    }

    @Test
    @DisplayName("逻辑删除 BOOLEAN：转成 Boolean")
    void testLogicDeleteValueTypeBoolean() {
        TableBinding binding = binding();
        LogicDeleteConfig ld = logicDelete();
        ld.setValueType("BOOLEAN");
        binding.setLogicDelete(ld);

        FormDesignSqlBuilder.SqlWithParams insert =
                builder.buildInsert(DS_MAIN, binding, Map.of("customerName", "张三"), USER_ID, null);
        assertTrue(insert.getParams().contains(Boolean.FALSE), "未删除值 \"0\" 应转为 false");

        FormDesignSqlBuilder.SqlWithParams delete =
                builder.buildDelete(DS_MAIN, binding, USER_ID, 1L);
        assertTrue(delete.getParams().contains(Boolean.TRUE), "已删除值 \"1\" 应转为 true");
    }

    @Test
    @DisplayName("逻辑删除 STRING：保持字符串，兼容 'Y'/'N' 约定的老表")
    void testLogicDeleteValueTypeString() {
        TableBinding binding = binding();
        LogicDeleteConfig ld = logicDelete();
        ld.setValueType("STRING");
        ld.setDeletedValue("Y");
        ld.setNormalValue("N");
        binding.setLogicDelete(ld);

        FormDesignSqlBuilder.SqlWithParams insert =
                builder.buildInsert(DS_MAIN, binding, Map.of("customerName", "张三"), USER_ID, null);
        assertTrue(insert.getParams().contains("N"));
    }

    @Test
    @DisplayName("⚠️ 逻辑删除 valueType 缺失：回退 NUMBER，历史配置不至于运行时崩掉")
    void testLogicDeleteValueTypeMissingFallsBackToNumber() {
        TableBinding binding = binding();
        LogicDeleteConfig ld = logicDelete();
        ld.setValueType(null); // 模拟本次改动之前保存的配置
        binding.setLogicDelete(ld);

        FormDesignSqlBuilder.SqlWithParams insert =
                builder.buildInsert(DS_MAIN, binding, Map.of("customerName", "张三"), USER_ID, null);

        assertTrue(insert.getParams().contains(0L), "缺失时应按 NUMBER 处理");
    }

    // ==================== SELECT ====================

    @Test
    @DisplayName("SELECT 单条：不用 * 而是列出声明过的列")
    void testGetSelectsExplicitColumns() {
        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildGet(DS_MAIN, binding(), 1L);

        String sql = result.getSql();
        assertFalse(sql.contains("SELECT *"), "不该用 SELECT *，避免返回未声明的敏感列");
        assertTrue(sql.contains("`id`"), "主键必查，前端编辑删除需要");
        assertTrue(sql.contains("`name`"));
        assertTrue(sql.contains("`level`"));
    }

    @Test
    @DisplayName("字段名≠列名时 AS 成字段名：否则脱敏/形态还原/列表取值全部静默失效")
    void testSelectAliasesColumnToFieldName() {
        // binding() 里 customerName -> name、customerLevel -> level，本就是异名映射
        String sql = builder.buildGet(DS_MAIN, binding(), 1L).getSql();

        assertTrue(sql.contains("`name` AS `customerName`"),
                "列名与表单字段名不一致时必须 AS 成字段名。下游的 FormDataMasker、"
                        + "FormValueSerializer、FormDataChangeDiffer 与前端 row[col.field] "
                        + "全都按字段名取值，不 AS 的话它们只会静默跳过");
        assertTrue(sql.contains("`level` AS `customerLevel`"));
    }

    @Test
    @DisplayName("分页同样 AS：列表页与详情页必须用同一套键，否则两处表现不一致")
    void testPageAliasesColumnToFieldName() {
        String sql = builder.buildPage(DS_MAIN, binding(), null, null, null, 1, 10).getSql();

        assertTrue(sql.contains("`name` AS `customerName`"));
        assertTrue(sql.contains("`level` AS `customerLevel`"));
    }

    @Test
    @DisplayName("字段名与列名相同时不加别名：既有映射几乎全是同名，加了只是噪音")
    void testSameNameMappingHasNoAlias() {
        TableBinding binding = binding();
        Map<String, FieldMapping> mapping = new LinkedHashMap<>();
        mapping.put("name", fieldMapping("name", "VARCHAR"));
        binding.setFieldMapping(mapping);

        String sql = builder.buildGet(DS_MAIN, binding, 1L).getSql();

        assertTrue(sql.contains("`name`"));
        assertFalse(sql.contains("AS `name`"), "同名映射不该产生 `name` AS `name` 这种冗余别名");
    }

    @Test
    @DisplayName("字段名不是合法标识符时退回不加别名，而不是让整个查询抛异常")
    void testInvalidIdentifierFieldNameDegradesInsteadOfThrowing() {
        TableBinding binding = binding();
        Map<String, FieldMapping> mapping = new LinkedHashMap<>();
        // 字段名不受发布校验约束，可能含中文或连字符
        mapping.put("客户-名称", fieldMapping("name", "VARCHAR"));
        binding.setFieldMapping(mapping);

        String sql = assertDoesNotThrow(() -> builder.buildGet(DS_MAIN, binding, 1L).getSql(),
                "非法标识符不该把「这一个字段脱敏失效」放大成「整个列表页 500」");
        assertTrue(sql.contains("`name`"));
        assertFalse(sql.contains("客户-名称"), "非法标识符绝不能拼进 SQL，那是注入点");
    }

    @Test
    @DisplayName("分页：LIMIT 与 OFFSET 走参数绑定而非拼接")
    void testPagePlaceholders() {
        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding(), null, null, null, 3, 20);

        String sql = result.getSql();
        assertTrue(sql.contains("LIMIT ? OFFSET ?"), "分页参数应绑定：" + sql);
        // 倒数第二个是 pageSize，最后一个是 offset
        List<Object> params = result.getParams();
        assertEquals(20, params.get(params.size() - 2));
        assertEquals(40, params.get(params.size() - 1), "第 3 页每页 20 条，offset 应为 40");
    }

    @Test
    @DisplayName("分页：排序字段经白名单校验后拼入")
    void testPageWithSort() {
        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding(), null, "customerName", "desc", 1, 10);

        assertTrue(result.getSql().contains("ORDER BY `name` DESC"),
                "排序字段应映射为物理列并归一化方向：" + result.getSql());
    }

    @Test
    @DisplayName("⚠️ 分页：排序字段未声明时忽略排序，不报错也不拼接")
    void testPageWithUnknownSortFieldIgnored() {
        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding(), null, "notDeclared", "DESC", 1, 10);

        assertFalse(result.getSql().contains("ORDER BY"),
                "未声明的排序字段应被忽略：" + result.getSql());
    }

    @Test
    @DisplayName("⚠️ 分页：排序方向含注入时拒绝")
    void testPageSortDirectionInjectionBlocked() {
        assertThrows(ServiceException.class,
                () -> builder.buildPage(DS_MAIN, binding(), null,
                        "customerName", "DESC; DROP TABLE users", 1, 10));
    }

    // ==================== 查询条件 ====================

    @Test
    @DisplayName("查询条件 LIKE：值加通配符后绑定")
    void testConditionLike() {
        FormDesignSqlBuilder.QueryCondition condition = new FormDesignSqlBuilder.QueryCondition();
        condition.setField("customerName");
        condition.setOperator("LIKE");
        condition.setValue("张");

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding(), List.of(condition), null, null, 1, 10);

        assertTrue(result.getSql().contains("`name` LIKE ?"));
        assertTrue(result.getParams().contains("%张%"), "LIKE 的通配符应加在参数上而非 SQL 里");
    }

    @Test
    @DisplayName("查询条件 IN：占位符数量与值数量一致")
    void testConditionIn() {
        FormDesignSqlBuilder.QueryCondition condition = new FormDesignSqlBuilder.QueryCondition();
        condition.setField("customerLevel");
        condition.setOperator("IN");
        condition.setValue(List.of(1, 2, 3));

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding(), List.of(condition), null, null, 1, 10);

        assertTrue(result.getSql().contains("`level` IN (?, ?, ?)"),
                "IN 的占位符数应与值数一致：" + result.getSql());
        assertEquals(countPlaceholders(result.getSql()), result.getParams().size());
    }

    @Test
    @DisplayName("查询条件 BETWEEN：两个值都绑定")
    void testConditionBetween() {
        FormDesignSqlBuilder.QueryCondition condition = new FormDesignSqlBuilder.QueryCondition();
        condition.setField("customerLevel");
        condition.setOperator("BETWEEN");
        condition.setValue(1);
        condition.setValue2(5);

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding(), List.of(condition), null, null, 1, 10);

        assertTrue(result.getSql().contains("`level` BETWEEN ? AND ?"));
        assertTrue(result.getParams().containsAll(List.of(1, 5)));
    }

    @Test
    @DisplayName("查询条件 IS_NULL：不产生参数")
    void testConditionIsNull() {
        FormDesignSqlBuilder.QueryCondition condition = new FormDesignSqlBuilder.QueryCondition();
        condition.setField("customerLevel");
        condition.setOperator("IS_NULL");

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding(), List.of(condition), null, null, 1, 10);

        assertTrue(result.getSql().contains("`level` IS NULL"));
        // 只剩分页的两个参数
        assertEquals(2, result.getParams().size());
    }

    @Test
    @DisplayName("⚠️ 查询条件字段未声明：静默忽略，不让整个列表打不开")
    void testUnknownConditionFieldIgnored() {
        FormDesignSqlBuilder.QueryCondition condition = new FormDesignSqlBuilder.QueryCondition();
        condition.setField("hackerField");
        condition.setOperator("EQ");
        condition.setValue("x");

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding(), List.of(condition), null, null, 1, 10);

        assertFalse(result.getSql().contains("hackerField"));
        assertEquals(2, result.getParams().size(), "只剩分页参数");
    }

    @Test
    @DisplayName("⚠️ 查询条件操作符含注入：拒绝")
    void testConditionOperatorInjectionBlocked() {
        FormDesignSqlBuilder.QueryCondition condition = new FormDesignSqlBuilder.QueryCondition();
        condition.setField("customerName");
        condition.setOperator("EQ OR 1=1");
        condition.setValue("x");

        assertThrows(ServiceException.class,
                () -> builder.buildPage(DS_MAIN, binding(), List.of(condition), null, null, 1, 10));
    }

    @Test
    @DisplayName("查询条件值为空：跳过该条件")
    void testBlankConditionValueSkipped() {
        FormDesignSqlBuilder.QueryCondition condition = new FormDesignSqlBuilder.QueryCondition();
        condition.setField("customerName");
        condition.setOperator("EQ");
        condition.setValue("");

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding(), List.of(condition), null, null, 1, 10);

        assertFalse(result.getSql().contains("`name` = ?"), "空值条件应跳过");
    }

    // ==================== COUNT ====================

    @Test
    @DisplayName("COUNT：条件与分页查询一致但不含 LIMIT")
    void testCount() {
        FormDesignSqlBuilder.QueryCondition condition = new FormDesignSqlBuilder.QueryCondition();
        condition.setField("customerName");
        condition.setOperator("LIKE");
        condition.setValue("张");

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildCount(DS_MAIN, binding(), List.of(condition));

        assertTrue(result.getSql().startsWith("SELECT COUNT(*)"));
        assertTrue(result.getSql().contains("`name` LIKE ?"));
        assertFalse(result.getSql().contains("LIMIT"), "COUNT 不该有 LIMIT");
        assertEquals(1, result.getParams().size());
    }

    // ==================== 副表（1:1，参与写入） ====================

    private static final String SIDE_TABLE = "biz_customer_ext";

    /**
     * 构造副表配置：外键 customer_id 指回主表，映射两个扩展字段
     */
    private SideTableBinding sideTable() {
        SideTableBinding side = new SideTableBinding();
        side.setTable(SIDE_TABLE);
        side.setPk("id");
        side.setForeignKey("customer_id");
        side.setRelationField("id");

        Map<String, FieldMapping> mapping = new LinkedHashMap<>();
        mapping.put("extRemark", fieldMapping("ext_remark", "VARCHAR"));
        mapping.put("extScore", fieldMapping("ext_score", "INTEGER"));
        side.setFieldMapping(mapping);
        return side;
    }

    /**
     * 把副表挂到 binding 上，并 mock 副表的元数据
     */
    private TableBinding bindingWithSideTable(SideTableBinding side) {
        mockTable(SIDE_TABLE, "id", "customer_id", "ext_remark", "ext_score", "deleted");
        TableBinding binding = binding();
        binding.setSideTables(List.of(side));
        return binding;
    }

    @Test
    @DisplayName("⚠️ 白名单：副表必须能通过 validateTable，否则多表绑定根本用不了")
    void testSideTableInWhitelist() {
        SideTableBinding side = sideTable();
        TableBinding binding = bindingWithSideTable(side);

        // 改动前 buildDeclaredTables 只放主表，这里会抛 IDENTIFIER_INVALID
        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableSelect(
                DS_MAIN, binding, side, 1L);

        assertTrue(result.getSql().contains("`" + SIDE_TABLE + "`"));
    }

    @Test
    @DisplayName("副表 SELECT：查主键、外键与映射列，按外键过滤")
    void testSideTableSelect() {
        SideTableBinding side = sideTable();
        TableBinding binding = bindingWithSideTable(side);

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableSelect(
                DS_MAIN, binding, side, 100L);

        String sql = result.getSql();
        assertTrue(sql.startsWith("SELECT "));
        assertTrue(sql.contains("`ext_remark`"));
        assertTrue(sql.contains("`ext_score`"));
        assertTrue(sql.contains("WHERE `customer_id` = ?"));
        assertEquals(List.of(100L), result.getParams());
    }

    @Test
    @DisplayName("⚠️ 副表映射列必须 AS 成表单字段名，与写入侧的取值键一致")
    void testSideTableSelectAliasesToFormField() {
        SideTableBinding side = sideTable();
        TableBinding binding = bindingWithSideTable(side);

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableSelect(
                DS_MAIN, binding, side, 100L);

        String sql = result.getSql();
        // 写入侧按 fieldMapping 的键（表单字段名）取值，读取侧就必须按同一个键输出。
        // 曾经这里 AS 成派生的 yd_表_yd_列，字段名不叫这个的表单写得进读不出，
        // 详情与列表里副表字段全空，而数据库里是有值的
        assertTrue(sql.contains("`ext_remark` AS `extRemark`"),
                "副表列应 AS 成表单字段名：" + sql);
        assertTrue(sql.contains("`ext_score` AS `extScore`"), sql);
        // 外键是内部键（通常不进表单），仍用带表名前缀的名字，避免与主表列撞名
        assertTrue(sql.contains("`customer_id` AS `yd_" + SIDE_TABLE + "_yd_customer_id`"), sql);
    }

    @Test
    @DisplayName("⚠️ 副表读写命名必须对称：SELECT 的别名要能覆盖 UPDATE 的取值键")
    void testSideTableReadWriteSymmetry() {
        SideTableBinding side = sideTable();
        TableBinding binding = bindingWithSideTable(side);

        String selectSql = builder.buildSideTableSelect(DS_MAIN, binding, side, 100L).getSql();

        // UPDATE 用 fieldMapping 的键从提交数据里取值；SELECT 必须以同名别名把值还回去。
        // 两侧只要有一个键对不上，就是「存进去了但读不出来」
        for (String formField : side.getFieldMapping().keySet()) {
            assertTrue(selectSql.contains(" AS `" + formField + "`"),
                    "字段 " + formField + " 写得进却读不出，SELECT 缺少同名别名：" + selectSql);
        }
    }

    @Test
    @DisplayName("⚠️ 主表与副表同名列不互相覆盖：表单字段名本就唯一")
    void testSideTableSameColumnNameIsolated() {
        // 副表也有 name 列，与主表 name 同名；但它在表单里的字段名是 extName
        mockTable(SIDE_TABLE, "id", "customer_id", "name", "ext_remark", "ext_score", "deleted");
        SideTableBinding side = sideTable();
        Map<String, FieldMapping> mapping = new LinkedHashMap<>(side.getFieldMapping());
        mapping.put("extName", fieldMapping("name", "VARCHAR"));
        side.setFieldMapping(mapping);

        TableBinding binding = binding();
        binding.setSideTables(List.of(side));

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableSelect(
                DS_MAIN, binding, side, 100L);

        // 副表的 name 以 extName 返回，不会覆盖主表的 name
        assertTrue(result.getSql().contains("`name` AS `extName`"), result.getSql());
    }

    // ==================== 副表批量回查（列表分页用） ====================

    @Test
    @DisplayName("副表批量查询：IN 占位符数与主键数一致，映射列按表单字段名返回")
    void testSideTableSelectBatch() {
        SideTableBinding side = sideTable();
        TableBinding binding = bindingWithSideTable(side);

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableSelectBatch(
                DS_MAIN, binding, side, List.of(1L, 2L, 3L));

        String sql = result.getSql();
        assertTrue(sql.contains("`customer_id` IN (?, ?, ?)"), sql);
        // 映射列按表单字段名返回，列表页才能按列配置里的 field 取到值
        assertTrue(sql.contains("`ext_remark` AS `extRemark`"), sql);
        // 外键仍用内部前缀名：批量回查靠它把行分组挂回主记录
        assertTrue(sql.contains("`customer_id` AS `yd_" + SIDE_TABLE + "_yd_customer_id`"), sql);
        assertEquals(List.of(1L, 2L, 3L), result.getParams());
    }

    @Test
    @DisplayName("⚠️ 副表批量查询必须查出外键列，否则无法把行拼回对应的主表记录")
    void testSideTableSelectBatchAlwaysSelectsForeignKey() {
        SideTableBinding side = sideTable();
        // 字段映射里不含外键，外键仍应被补进 SELECT
        side.setFieldMapping(Map.of("extRemark", fieldMapping("ext_remark", "VARCHAR")));
        TableBinding binding = bindingWithSideTable(side);

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableSelectBatch(
                DS_MAIN, binding, side, List.of(1L));

        assertTrue(result.getSql().contains("`customer_id` AS `yd_" + SIDE_TABLE + "_yd_customer_id`"),
                "外键是归位的依据，必须查出来：" + result.getSql());
    }

    @Test
    @DisplayName("副表批量查询带逻辑删除条件")
    void testSideTableSelectBatchWithLogicDelete() {
        SideTableBinding side = sideTable();
        side.setLogicDelete(logicDelete());
        TableBinding binding = bindingWithSideTable(side);

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableSelectBatch(
                DS_MAIN, binding, side, List.of(1L, 2L));

        assertTrue(result.getSql().contains("`deleted` = ?"), result.getSql());
        // 参数顺序：两个主键值 + 未删除值
        assertEquals(List.of(1L, 2L, 0L), result.getParams());
    }

    @Test
    @DisplayName("⚠️ 空主键集合被拒绝，避免生成 IN () 这种非法 SQL")
    void testSideTableSelectBatchRejectsEmpty() {
        SideTableBinding side = sideTable();
        TableBinding binding = bindingWithSideTable(side);

        assertThrows(ServiceException.class,
                () -> builder.buildSideTableSelectBatch(DS_MAIN, binding, side, List.of()));
        assertThrows(ServiceException.class,
                () -> builder.buildSideTableSelectBatch(DS_MAIN, binding, side, null));
    }

    @Test
    @DisplayName("副表 INSERT：外键填主表主键，值全部走参数绑定")
    void testSideTableInsert() {
        SideTableBinding side = sideTable();
        TableBinding binding = bindingWithSideTable(side);

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableInsert(
                DS_MAIN, binding, side,
                Map.of("extRemark", "备注内容", "extScore", 88), 100L);

        String sql = result.getSql();
        assertTrue(sql.startsWith("INSERT INTO `" + SIDE_TABLE + "`"));
        assertTrue(sql.contains("`customer_id`"), "必须写入外键");
        assertEquals(countPlaceholders(sql), result.getParams().size(),
                "占位符数与参数数必须一致：" + sql);
        assertTrue(result.getParams().contains(100L), "外键值应为主表主键");
        assertTrue(result.getParams().contains("备注内容"));
        assertFalse(sql.contains("备注内容"), "值不该出现在 SQL 字面量里");
    }

    @Test
    @DisplayName("副表 UPDATE：按外键定位而非副表主键")
    void testSideTableUpdate() {
        SideTableBinding side = sideTable();
        TableBinding binding = bindingWithSideTable(side);

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableUpdate(
                DS_MAIN, binding, side,
                Map.of("extRemark", "改后", "extScore", 99), 100L);

        String sql = result.getSql();
        assertTrue(sql.startsWith("UPDATE `" + SIDE_TABLE + "` SET "));
        assertTrue(sql.contains("WHERE `customer_id` = ?"),
                "副表更新只能按外键定位 —— 调用方只持有主表主键：" + sql);
        assertEquals(countPlaceholders(sql), result.getParams().size());
    }

    @Test
    @DisplayName("⚠️ 副表 UPDATE 无映射列：返回 null 而不是生成 SET 为空的非法 SQL")
    void testSideTableUpdateWithoutMappingReturnsNull() {
        SideTableBinding side = sideTable();
        side.setFieldMapping(null);
        TableBinding binding = bindingWithSideTable(side);

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableUpdate(
                DS_MAIN, binding, side, Map.of(), 100L);

        assertNull(result, "没有可更新的列时应返回 null，让调用方跳过执行");
    }

    @Test
    @DisplayName("副表 DELETE：未配逻辑删除时物理删除，按外键定位")
    void testSideTableDeletePhysical() {
        SideTableBinding side = sideTable();
        TableBinding binding = bindingWithSideTable(side);

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableDelete(
                DS_MAIN, binding, side, 100L);

        assertEquals("DELETE FROM `" + SIDE_TABLE + "` WHERE `customer_id` = ?", result.getSql());
        assertEquals(List.of(100L), result.getParams());
    }

    @Test
    @DisplayName("副表 DELETE：配了逻辑删除时改为 UPDATE 标记，且不重复删已删除的行")
    void testSideTableDeleteLogical() {
        SideTableBinding side = sideTable();
        side.setLogicDelete(logicDelete());
        TableBinding binding = bindingWithSideTable(side);

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableDelete(
                DS_MAIN, binding, side, 100L);

        String sql = result.getSql();
        assertTrue(sql.startsWith("UPDATE `" + SIDE_TABLE + "` SET `deleted` = ?"));
        assertTrue(sql.contains("`customer_id` = ?"));
        assertTrue(sql.contains("AND `deleted` = ?"), "已删除的行不该再删一次：" + sql);
        // 参数顺序：已删除值、外键值、未删除值
        assertEquals(List.of(1L, 100L, 0L), result.getParams());
    }

    @Test
    @DisplayName("副表启用逻辑删除时，INSERT 填未删除值")
    void testSideTableInsertWithLogicDelete() {
        SideTableBinding side = sideTable();
        side.setLogicDelete(logicDelete());
        TableBinding binding = bindingWithSideTable(side);

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableInsert(
                DS_MAIN, binding, side, Map.of("extRemark", "x"), 100L);

        assertTrue(result.getSql().contains("`deleted`"));
        assertTrue(result.getParams().contains(0L), "新增的副表行应标记为未删除");
    }

    @Test
    @DisplayName("副表 SELECT 会带上逻辑删除条件，不查已删除的行")
    void testSideTableSelectSkipsDeleted() {
        SideTableBinding side = sideTable();
        side.setLogicDelete(logicDelete());
        TableBinding binding = bindingWithSideTable(side);

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSideTableSelect(
                DS_MAIN, binding, side, 100L);

        assertTrue(result.getSql().contains("`deleted` = ?"));
        assertTrue(result.getParams().contains(0L));
    }

    // ==================== 关联表 JOIN（只读） ====================

    private static final String JOIN_TABLE = "system_users";

    /**
     * 构造关联表配置：客户表的 owner_id 关联到用户表，展示用户昵称
     */
    private JoinTableBinding joinTable(String alias) {
        mockTable(JOIN_TABLE, "id", "nickname", "mobile");
        JoinTableBinding join = new JoinTableBinding();
        join.setTable(JOIN_TABLE);
        join.setAlias(alias);
        join.setJoinType("LEFT");

        JoinTableBinding.JoinCondition on = new JoinTableBinding.JoinCondition();
        on.setLeft(TABLE + ".owner_id");
        on.setRight((alias != null ? alias : JOIN_TABLE) + ".id");
        join.setOn(List.of(on));

        JoinTableBinding.SelectColumn sc = new JoinTableBinding.SelectColumn();
        sc.setColumn("nickname");
        sc.setAs("ownerName");
        join.setSelectColumns(List.of(sc));
        return join;
    }

    /**
     * 主表要多一个 owner_id 列才能做关联
     */
    private TableBinding bindingWithJoinTable(JoinTableBinding join) {
        mockTable(TABLE, "id", "name", "level", "deleted", "owner_id",
                "creator", "create_time", "updater", "update_time");
        TableBinding binding = binding();
        binding.getFieldMapping().put("ownerId", fieldMapping("owner_id", "BIGINT"));
        binding.setJoinTables(List.of(join));
        return binding;
    }

    @Test
    @DisplayName("分页带 JOIN：生成 LEFT JOIN 与 AS 别名")
    void testPageWithJoin() {
        TableBinding binding = bindingWithJoinTable(joinTable("u"));

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding, null, null, null, 1, 10);

        String sql = result.getSql();
        assertTrue(sql.contains("LEFT JOIN `" + JOIN_TABLE + "` AS `u`"), sql);
        assertTrue(sql.contains("ON `" + TABLE + "`.`owner_id` = `u`.`id`"), sql);
        assertTrue(sql.contains("`u`.`nickname` AS `ownerName`"), sql);
    }

    @Test
    @DisplayName("⚠️ 有 JOIN 时主表列必须带表前缀，否则同名列会报 ambiguous")
    void testJoinQualifiesMainTableColumns() {
        TableBinding binding = bindingWithJoinTable(joinTable("u"));

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding, null, null, null, 1, 10);

        String sql = result.getSql();
        String selectPart = sql.substring(0, sql.indexOf(" FROM "));
        // 主表的 name 与关联表都可能有同名列，必须限定
        assertTrue(selectPart.contains("`" + TABLE + "`.`name`"),
                "主表列要带表前缀：" + selectPart);
        // 逐段检查：每个主表列都应带前缀，不能有裸的 `name`
        assertFalse(selectPart.contains(", `name`") || selectPart.startsWith("`name`"),
                "不该出现未限定的裸列名：" + selectPart);
    }

    @Test
    @DisplayName("有 JOIN 时 WHERE 的逻辑删除条件也要带前缀")
    void testJoinQualifiesWhereClause() {
        TableBinding binding = bindingWithJoinTable(joinTable("u"));
        binding.setLogicDelete(logicDelete());

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding, null, null, null, 1, 10);

        assertTrue(result.getSql().contains("`" + TABLE + "`.`deleted` = ?"),
                "逻辑删除条件要限定到主表，否则关联表也有 deleted 列时会歧义：" + result.getSql());
    }

    @Test
    @DisplayName("有 JOIN 时 ORDER BY 也要带前缀")
    void testJoinQualifiesOrderBy() {
        TableBinding binding = bindingWithJoinTable(joinTable("u"));

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding, null, "customerName", "DESC", 1, 10);

        assertTrue(result.getSql().contains("ORDER BY `" + TABLE + "`.`name` DESC"),
                result.getSql());
    }

    @Test
    @DisplayName("⚠️ COUNT 必须与分页用同一套 JOIN，否则总数与列表条数不一致")
    void testCountIncludesJoin() {
        TableBinding binding = bindingWithJoinTable(joinTable("u"));

        FormDesignSqlBuilder.SqlWithParams result = builder.buildCount(DS_MAIN, binding, null);

        assertTrue(result.getSql().startsWith("SELECT COUNT(*)"));
        assertTrue(result.getSql().contains("LEFT JOIN `" + JOIN_TABLE + "`"),
                "COUNT 漏了 JOIN，INNER JOIN 场景下分页器会出现空白页：" + result.getSql());
    }

    @Test
    @DisplayName("无关联表时不生成 JOIN，列也不加前缀（保持原有 SQL 形态）")
    void testNoJoinKeepsPlainColumns() {
        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding(), null, null, null, 1, 10);

        String sql = result.getSql();
        assertFalse(sql.contains("JOIN"), sql);
        assertTrue(sql.contains("SELECT `id`, `name`"), "无 JOIN 时不该加前缀：" + sql);
    }

    @Test
    @DisplayName("⚠️ ON 条件全无效时拒绝生成裸 JOIN —— 那会变成笛卡尔积")
    void testJoinWithoutValidOnRejected() {
        JoinTableBinding join = joinTable("u");
        JoinTableBinding.JoinCondition broken = new JoinTableBinding.JoinCondition();
        broken.setLeft("");
        broken.setRight("");
        join.setOn(List.of(broken));
        TableBinding binding = bindingWithJoinTable(join);

        assertThrows(ServiceException.class,
                () -> builder.buildPage(DS_MAIN, binding, null, null, null, 1, 10),
                "ON 条件无效时必须报错，不能生成无 ON 的 JOIN");
    }

    @Test
    @DisplayName("⚠️ 非法 JOIN 类型被拒绝，不允许拼接任意关键字")
    void testInvalidJoinTypeRejected() {
        JoinTableBinding join = joinTable("u");
        join.setJoinType("RIGHT OUTER");
        TableBinding binding = bindingWithJoinTable(join);

        assertThrows(ServiceException.class,
                () -> builder.buildPage(DS_MAIN, binding, null, null, null, 1, 10));
    }

    @Test
    @DisplayName("⚠️ 别名含非法字符被拒绝 —— 别名会直接拼进 SQL，是注入点")
    void testInvalidAliasRejected() {
        JoinTableBinding join = joinTable("u`; DROP TABLE x; --");
        TableBinding binding = bindingWithJoinTable(join);

        assertThrows(ServiceException.class,
                () -> builder.buildPage(DS_MAIN, binding, null, null, null, 1, 10));
    }

    @Test
    @DisplayName("不填别名时用表名作限定符")
    void testJoinWithoutAlias() {
        TableBinding binding = bindingWithJoinTable(joinTable(null));

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildPage(DS_MAIN, binding, null, null, null, 1, 10);

        String sql = result.getSql();
        assertTrue(sql.contains("LEFT JOIN `" + JOIN_TABLE + "`"), sql);
        // 只看 JOIN 子句有没有 AS —— SELECT 里的列别名（AS `ownerName`）是另一回事
        String joinClause = sql.substring(sql.indexOf("LEFT JOIN"), sql.indexOf(" ON "));
        assertFalse(joinClause.contains(" AS "), "没有表别名时 JOIN 不该生成 AS：" + joinClause);
        assertTrue(sql.contains("`" + JOIN_TABLE + "`.`nickname`"), sql);
    }

    // ==================== 去重值查询（左侧树用） ====================

    @Test
    @DisplayName("去重值：SELECT DISTINCT + 排序 + 数量上限")
    void testDistinctValues() {
        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildDistinctValues(DS_MAIN, binding(), "customerLevel", 500);

        String sql = result.getSql();
        assertTrue(sql.startsWith("SELECT DISTINCT `level`"), sql);
        assertTrue(sql.contains("FROM `" + TABLE + "`"), sql);
        assertTrue(sql.contains("`level` IS NOT NULL"), "NULL 不该成为树节点：" + sql);
        assertTrue(sql.contains("ORDER BY `level`"), sql);
        assertTrue(sql.endsWith("LIMIT ?"), sql);
        assertEquals(List.of(500), result.getParams());
    }

    @Test
    @DisplayName("去重值：带上逻辑删除条件，不统计已删除的行")
    void testDistinctValuesSkipsDeleted() {
        TableBinding binding = binding();
        binding.setLogicDelete(logicDelete());

        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildDistinctValues(DS_MAIN, binding, "customerLevel", 500);

        assertTrue(result.getSql().contains("`deleted` = ?"), result.getSql());
        assertTrue(result.getParams().contains(0L));
    }

    @Test
    @DisplayName("⚠️ 去重值：未声明的字段被拒绝，防止拿它探测任意列")
    void testDistinctValuesRejectsUndeclaredField() {
        assertThrows(ServiceException.class,
                () -> builder.buildDistinctValues(DS_MAIN, binding(), "notDeclaredField", 500),
                "字段必须能在 fieldMapping 里解析出物理列");
    }

    // ==================== 子表查询 ====================

    private static final String SUB_TABLE = "biz_customer_contact";

    /**
     * 这两个测试对应端到端实测发现的缺陷：运行时的 buildSubColumns 漏了外键列，
     * 导致 buildSubTableSelect 校验 WHERE 条件的列时抛 IDENTIFIER_INVALID，
     * 配了子表的表单查不出详情。单测原先只测 buildSubTableSelect 本身
     * （白名单由测试自己构造、含外键），没覆盖运行时的组装路径。
     */
    @Test
    @DisplayName("⚠️ 子表查询：白名单必须含外键列，否则 WHERE 条件校验不过")
    void testSubTableSelectRequiresForeignKeyInWhitelist() {
        mockTable(SUB_TABLE, "id", "customer_id", "contact_name", "deleted");
        // 白名单漏了外键 customer_id，模拟修复前的 buildSubColumns
        Set<String> withoutFk = Set.of("id", "contact_name");

        assertThrows(ServiceException.class,
                () -> builder.buildSubTableSelect(DS_MAIN, SUB_TABLE, "customer_id",
                        withoutFk, 1L, null),
                "外键不在白名单时应报错，这正是实测踩到的坑");
    }

    @Test
    @DisplayName("子表查询：带逻辑删除配置时过滤已删除的明细")
    void testSubTableSelectFiltersDeleted() {
        mockTable(SUB_TABLE, "id", "customer_id", "contact_name", "deleted");
        Set<String> subColumns = Set.of("id", "customer_id", "contact_name", "deleted");

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSubTableSelect(
                DS_MAIN, SUB_TABLE, "customer_id", subColumns, 100L, logicDelete());

        String sql = result.getSql();
        assertTrue(sql.contains("`customer_id` = ?"), sql);
        assertTrue(sql.contains("`deleted` = ?"),
                "已逻辑删除的明细不该查出来：" + sql);
        assertEquals(List.of(100L, 0L), result.getParams());
    }

    @Test
    @DisplayName("子表查询：未配逻辑删除时只按外键过滤")
    void testSubTableSelectWithoutLogicDelete() {
        mockTable(SUB_TABLE, "id", "customer_id", "contact_name");
        Set<String> subColumns = Set.of("id", "customer_id", "contact_name");

        FormDesignSqlBuilder.SqlWithParams result = builder.buildSubTableSelect(
                DS_MAIN, SUB_TABLE, "customer_id", subColumns, 100L, null);

        assertFalse(result.getSql().contains("`deleted`"), result.getSql());
        assertEquals(List.of(100L), result.getParams());
    }

    // ==================== 租户隔离 ====================

    @Test
    @DisplayName("INSERT 自动写入当前租户 ID")
    void testInsertAutoFillsTenantId() {
        mockTable(TABLE, "id", "name", "level", "tenant_id", "creator", "create_time", "updater", "update_time", "deleted");
        TenantContextHolder.setTenantId(100L);
        try {
            FormDesignSqlBuilder.SqlWithParams result = builder.buildInsert(
                    DS_MAIN, binding(), Map.of("customerName", "test"), 1L, 1000L);
            assertTrue(result.getSql().contains("`tenant_id`"), result.getSql());
            assertTrue(result.getParams().contains(100L));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("UPDATE WHERE 追加 tenant_id 条件")
    void testUpdateAppendsTenantWhere() {
        mockTable(TABLE, "id", "name", "level", "tenant_id", "updater", "update_time", "deleted");
        TenantContextHolder.setTenantId(100L);
        try {
            FormDesignSqlBuilder.SqlWithParams result = builder.buildUpdate(
                    DS_MAIN, binding(), Map.of("customerName", "updated"), 1L, 100L);
            assertTrue(result.getSql().contains("`tenant_id` = ?"), result.getSql());
            assertTrue(result.getParams().contains(100L));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("DELETE 追加 tenant_id 条件（逻辑删除与物理删除）")
    void testDeleteAppendsTenantWhere() {
        mockTable(TABLE, "id", "name", "level", "tenant_id", "deleted", "updater", "update_time");
        TenantContextHolder.setTenantId(100L);
        try {
            TableBinding b = binding();
            b.setLogicDelete(logicDelete());
            FormDesignSqlBuilder.SqlWithParams r1 = builder.buildDelete(DS_MAIN, b, 1L, 100L);
            assertTrue(r1.getSql().contains("`tenant_id` = ?"), r1.getSql());

            b.setLogicDelete(null);
            FormDesignSqlBuilder.SqlWithParams r2 = builder.buildDelete(DS_MAIN, b, 1L, 100L);
            assertTrue(r2.getSql().contains("`tenant_id` = ?"), r2.getSql());
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("查询（GET/Page/Count）追加 tenant_id 条件")
    void testQueryAppendsTenantWhere() {
        mockTable(TABLE, "id", "name", "level", "tenant_id", "deleted");
        TenantContextHolder.setTenantId(100L);
        try {
            TableBinding b = binding();
            b.setLogicDelete(logicDelete());
            assertTrue(builder.buildGet(DS_MAIN, b, 100L).getSql().contains("`tenant_id` = ?"));
            assertTrue(builder.buildPage(DS_MAIN, b, List.of(), null, null, 1, 10).getSql().contains("`tenant_id` = ?"));
            assertTrue(builder.buildCount(DS_MAIN, b, List.of()).getSql().contains("`tenant_id` = ?"));
            assertTrue(builder.buildDistinctValues(DS_MAIN, b, "customerName", 100).getSql().contains("`tenant_id` = ?"));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("副表查询追加 tenant_id 条件")
    void testSideTableAppendsTenantWhere() {
        SideTableBinding side = sideTable();
        TableBinding binding = binding();
        binding.setSideTables(List.of(side));
        mockTable(SIDE_TABLE, "id", "customer_id", "ext_remark", "ext_score", "tenant_id", "deleted");
        TenantContextHolder.setTenantId(100L);
        try {
            assertTrue(builder.buildSideTableSelect(DS_MAIN, binding, side, 1L)
                    .getSql().contains("`tenant_id` = ?"), "副表单条查询应带租户过滤");
            assertTrue(builder.buildSideTableSelectBatch(DS_MAIN, binding, side, List.of(1L, 2L))
                    .getSql().contains("`tenant_id` = ?"), "副表批量查询应带租户过滤");
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("⚠️ 副表 INSERT 必须写 tenant_id，否则写得进读不出")
    void testSideTableInsertFillsTenant() {
        SideTableBinding side = sideTable();
        TableBinding binding = binding();
        binding.setSideTables(List.of(side));
        mockTable(SIDE_TABLE, "id", "customer_id", "ext_remark", "ext_score", "tenant_id", "deleted");
        TenantContextHolder.setTenantId(100L);
        try {
            FormDesignSqlBuilder.SqlWithParams insert = builder.buildSideTableInsert(
                    DS_MAIN, binding, side, Map.of("extRemark", "备注"), 1L);

            // 曾经这里漏填 tenant_id：副表行以 NULL 落库，而所有副表查询都带
            // `tenant_id = ?` 过滤，于是数据写得进去、却永远查不出来 ——
            // 表单详情与列表里副表字段全空，数据库里却明明有值
            assertTrue(insert.getSql().contains("`tenant_id`"),
                    "副表 INSERT 缺少 tenant_id，会导致副表数据永远读不出来：" + insert.getSql());
            assertTrue(insert.getParams().contains(100L), insert.getParams().toString());

            // 列数与占位符数必须一致，否则运行时直接报错
            long placeholders = insert.getSql().chars().filter(c -> c == '?').count();
            assertEquals(insert.getParams().size(), placeholders, insert.getSql());
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("⚠️ 副表读写的租户处理必须对称：写入填了才查得到")
    void testSideTableTenantSymmetry() {
        SideTableBinding side = sideTable();
        TableBinding binding = binding();
        binding.setSideTables(List.of(side));
        mockTable(SIDE_TABLE, "id", "customer_id", "ext_remark", "ext_score", "tenant_id", "deleted");
        TenantContextHolder.setTenantId(100L);
        try {
            boolean writesTenant = builder.buildSideTableInsert(
                    DS_MAIN, binding, side, Map.of("extRemark", "x"), 1L).getSql().contains("`tenant_id`");
            boolean filtersTenant = builder.buildSideTableSelect(
                    DS_MAIN, binding, side, 1L).getSql().contains("`tenant_id` = ?");
            assertEquals(filtersTenant, writesTenant,
                    "查询按租户过滤却不在写入时填充（或反之），必然导致数据写得进读不出");
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("子表查询追加 tenant_id 条件")
    void testSubTableAppendsTenantWhere() {
        mockTable(SUB_TABLE, "id", "customer_id", "contact_name", "tenant_id", "deleted");
        TenantContextHolder.setTenantId(100L);
        try {
            String sql = builder.buildSubTableSelect(DS_MAIN, SUB_TABLE, "customer_id",
                    Set.of("id", "customer_id", "contact_name", "tenant_id", "deleted"), 100L, logicDelete())
                    .getSql();
            assertTrue(sql.contains("`tenant_id` = ?"), "子表查询应包含 tenant_id: " + sql);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("⚠️ 无 tenant_id 列时不追加条件")
    void testNoTenantColumnNoFilter() {
        mockTable(TABLE, "id", "name", "level", "deleted");
        TenantContextHolder.setTenantId(100L);
        try {
            TableBinding b = binding();
            b.setLogicDelete(logicDelete());
            FormDesignSqlBuilder.SqlWithParams result = builder.buildGet(DS_MAIN, b, 100L);
            assertFalse(result.getSql().contains("tenant_id"), result.getSql());
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("⚠️ TenantContextHolder.isIgnore() 时不追加条件")
    void testIgnoreTenantWhenContextIgnore() {
        mockTable(TABLE, "id", "name", "level", "tenant_id", "deleted");
        TenantContextHolder.setTenantId(100L);
        TenantContextHolder.setIgnore(true);
        try {
            TableBinding b = binding();
            b.setLogicDelete(logicDelete());
            FormDesignSqlBuilder.SqlWithParams result = builder.buildGet(DS_MAIN, b, 100L);
            assertFalse(result.getSql().contains("tenant_id"), result.getSql());
        } finally {
            TenantContextHolder.setIgnore(false);
            TenantContextHolder.clear();
        }
    }

}
