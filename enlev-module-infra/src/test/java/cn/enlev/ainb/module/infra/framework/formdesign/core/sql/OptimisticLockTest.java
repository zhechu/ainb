package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.OptimisticLockConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.service.db.DatabaseTableService;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * 乐观并发锁的 SQL 构建
 *
 * <h3>它防的是「后写覆盖」</h3>
 *
 * 两个人同时打开同一条记录，甲改金额、乙改备注，谁后点保存谁的整份数据落库 ——
 * 先保存那个人的修改被静默覆盖，双方都看到「保存成功」。没有任何一方会发现。
 *
 * 这些用例守的是 SQL 那一层：版本条件在不在 WHERE 里、版本号有没有自增。
 * 「影响行数为 0 时报什么错」在运行时服务里，由 e2e 覆盖。
 *
 * @author 走进新时代
 */
class OptimisticLockTest {

    private static final Long DS_MAIN = 0L;
    private static final String TABLE = "biz_customer";
    private static final Long USER_ID = 1L;

    private FormDesignSqlBuilder builder;
    private DatabaseTableService databaseTableService;

    @BeforeEach
    void setUp() {
        databaseTableService = mock(DatabaseTableService.class);
        builder = SqlBuilderTestFactory.create(databaseTableService);
        mockTable(TABLE, "id", "name", "remark", "version");
    }

    private void mockTable(String tableName, String... columns) {
        TableInfo tableInfo = mock(TableInfo.class);
        List<TableField> fields = Arrays.stream(columns).map(col -> {
            TableField field = mock(TableField.class);
            when(field.getName()).thenReturn(col);
            return field;
        }).toList();
        when(tableInfo.getFields()).thenReturn(fields);
        when(databaseTableService.getTable(eq(DS_MAIN), eq(tableName))).thenReturn(tableInfo);
    }

    private FieldMapping mapping(String column) {
        FieldMapping fieldMapping = new FieldMapping();
        fieldMapping.setTable(TABLE);
        fieldMapping.setColumn(column);
        return fieldMapping;
    }

    private TableBinding binding(boolean lockEnabled) {
        TableBinding binding = new TableBinding();
        binding.setMainTable(TABLE);
        binding.setMainPk("id");
        binding.setPkStrategy("AUTO");
        Map<String, FieldMapping> fieldMapping = new LinkedHashMap<>();
        fieldMapping.put("customerName", mapping("name"));
        fieldMapping.put("customerRemark", mapping("remark"));
        binding.setFieldMapping(fieldMapping);
        if (lockEnabled) {
            OptimisticLockConfig lock = new OptimisticLockConfig();
            lock.setEnabled(true);
            lock.setColumn("version");
            binding.setOptimisticLock(lock);
        }
        return binding;
    }

    private Map<String, Object> submitted() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerName", "张三");
        return data;
    }

    // ==================== UPDATE ====================

    @Test
    @DisplayName("启用后：版本号进 WHERE，版本列在 SET 里自增")
    void testUpdateWithVersion() {
        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildUpdate(DS_MAIN, binding(true), submitted(), USER_ID, 1L, 7);

        assertTrue(result.getSql().contains("`version` = `version` + 1"), result.getSql());
        assertTrue(result.getSql().contains("`version` = ?"), result.getSql());
        // 参数顺序：SET 的值 → 主键 → 版本号
        assertEquals(List.of("张三", 1L, 7), result.getParams());
    }

    @Test
    @DisplayName("⚠️ 自增用「列 = 列 + 1」，不绑一个算好的值")
    void testIncrementIsExpression() {
        // 绑算好的值（version + 1）在并发下可能已经过期；
        // 交给数据库在行锁内求值才是对的
        String sql = builder.buildUpdate(DS_MAIN, binding(true), submitted(), USER_ID, 1L, 7).getSql();
        assertFalse(sql.contains("`version` = ?, "), "版本列不该以绑参的形式出现在 SET 里：" + sql);
        assertTrue(sql.contains("`version` = `version` + 1"), sql);
    }

    @Test
    @DisplayName("未启用时：SQL 里不出现版本列，传了版本号也忽略")
    void testUpdateWithoutLock() {
        FormDesignSqlBuilder.SqlWithParams result =
                builder.buildUpdate(DS_MAIN, binding(false), submitted(), USER_ID, 1L, 7);

        assertFalse(result.getSql().contains("version"), result.getSql());
        assertEquals(List.of("张三", 1L), result.getParams());
    }

    @Test
    @DisplayName("勾了开关但没填列名时按未启用处理，不拼出语法错的 SQL")
    void testEnabledWithoutColumn() {
        // 这种配置由发布校验拦下。SQL 层要能安全兜住 ——
        // 拼出 `SET  =  + 1` 的话，报的是一个看不懂的语法错误
        TableBinding binding = binding(false);
        OptimisticLockConfig lock = new OptimisticLockConfig();
        lock.setEnabled(true);
        lock.setColumn("  ");
        binding.setOptimisticLock(lock);

        String sql = builder.buildUpdate(DS_MAIN, binding, submitted(), USER_ID, 1L, 7).getSql();
        assertTrue(sql.contains("`name` = ?"), sql);
        assertFalse(sql.contains("+ 1"), sql);
    }

    @Test
    @DisplayName("⚠️ 什么字段都没提交时照旧报错，不能因为版本自增就变成合法更新")
    void testNoFieldsStillFails() {
        // 版本自增必须排在「SET 为空」判断之后。排在之前的话，一次空提交会因为
        // 多了这一条 SET 而变成合法 SQL，把版本号白白推进一格 ——
        // 别人手上的版本号随之作废，下次保存被莫名拦下
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> builder.buildUpdate(DS_MAIN, binding(true), Map.of(), USER_ID, 1L, 7));
    }

    // ==================== SELECT ====================

    @Test
    @DisplayName("⚠️ 启用后查询必须把版本列带出来")
    void testSelectIncludesVersion() {
        // 不带出来的话前端拿不到版本号，回传不了，乐观锁等于没启用 ——
        // 而界面上开关是亮的
        String sql = builder.buildGet(DS_MAIN, binding(true), 1L).getSql();
        assertTrue(sql.contains("`version`"), sql);
    }

    @Test
    @DisplayName("未启用时查询里不出现版本列")
    void testSelectWithoutLock() {
        assertFalse(builder.buildGet(DS_MAIN, binding(false), 1L).getSql().contains("version"));
    }

}
