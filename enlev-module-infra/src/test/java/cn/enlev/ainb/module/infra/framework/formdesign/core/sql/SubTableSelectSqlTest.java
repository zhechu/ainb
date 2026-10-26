package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.LogicDeleteConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
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
 * 子表明细查询的 SQL 构建测试
 *
 * 覆盖详情接口的单条查询与主子表列表的批量查询，重点是：
 * 1. 明细顺序必须稳定 —— 不排序的话同一条单据两次打开明细行先后会变，
 *    列表里合并出来的行也会跟着跳
 * 2. 值全部走参数绑定，IN 子句的占位符数量与值数量一致
 *
 * @author 走进新时代
 */
class SubTableSelectSqlTest {

    private static final Long DS_MAIN = 0L;
    private static final String SUB_TABLE = "biz_order_item";

    private FormDesignSqlBuilder builder;
    private DatabaseTableService databaseTableService;

    @BeforeEach
    void setUp() {
        databaseTableService = mock(DatabaseTableService.class);

        // 协作者较多，装配统一放在 SqlBuilderTestFactory
        builder = SqlBuilderTestFactory.create(databaseTableService);
        mockTable(SUB_TABLE, "id", "order_id", "product_name", "qty", "deleted");
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

    private SubTableBinding subTable() {
        SubTableBinding subTable = new SubTableBinding();
        subTable.setTable(SUB_TABLE);
        subTable.setField("orderItems");
        subTable.setPk("id");
        subTable.setForeignKey("order_id");
        return subTable;
    }

    private Set<String> columns() {
        return new LinkedHashSet<>(List.of("id", "order_id", "product_name", "qty"));
    }

    @Test
    @DisplayName("单条查询按外键过滤，并按主键排序保证明细顺序稳定")
    void testSelectSingle() {
        FormDesignSqlBuilder.SqlWithParams sql =
                builder.buildSubTableSelect(DS_MAIN, subTable(), columns(), 1001L);

        assertTrue(sql.getSql().contains("FROM `biz_order_item`"));
        assertTrue(sql.getSql().contains("`order_id` = ?"));
        assertTrue(sql.getSql().endsWith("ORDER BY `id` ASC"), "必须按主键排序：" + sql.getSql());
        assertEquals(List.of(1001L), sql.getParams());
        assertFalse(sql.getSql().contains("1001"), "主键值必须走参数绑定");
    }

    @Test
    @DisplayName("批量查询用 IN，占位符数量与值数量一致")
    void testSelectBatch() {
        List<Object> fkValues = List.of(1L, 2L, 3L);
        FormDesignSqlBuilder.SqlWithParams sql =
                builder.buildSubTableSelectBatch(DS_MAIN, subTable(), columns(), fkValues);

        assertTrue(sql.getSql().contains("`order_id` IN (?, ?, ?)"), sql.getSql());
        assertEquals(fkValues, sql.getParams());
        // 先按外键再按主键：同一主记录的明细连续且顺序固定
        assertTrue(sql.getSql().endsWith("ORDER BY `order_id` ASC, `id` ASC"), sql.getSql());
    }

    @Test
    @DisplayName("批量查询的主键集合为空时直接拒绝，避免生成 IN () 这种非法 SQL")
    void testSelectBatchRejectsEmpty() {
        assertThrows(ServiceException.class,
                () -> builder.buildSubTableSelectBatch(DS_MAIN, subTable(), columns(), List.of()));
        assertThrows(ServiceException.class,
                () -> builder.buildSubTableSelectBatch(DS_MAIN, subTable(), columns(), null));
    }

    @Test
    @DisplayName("配了逻辑删除时，已删除的明细不会被查出来")
    void testLogicDeleteFiltered() {
        SubTableBinding subTable = subTable();
        LogicDeleteConfig logicDelete = new LogicDeleteConfig();
        logicDelete.setEnabled(true);
        logicDelete.setColumn("deleted");
        logicDelete.setNormalValue("0");
        logicDelete.setValueType("NUMBER");
        subTable.setLogicDelete(logicDelete);

        Set<String> columns = columns();
        columns.add("deleted");

        FormDesignSqlBuilder.SqlWithParams single =
                builder.buildSubTableSelect(DS_MAIN, subTable, columns, 1001L);
        assertTrue(single.getSql().contains("`deleted` = ?"), single.getSql());
        assertEquals(2, single.getParams().size(), "外键 + 未删除值两个参数");

        FormDesignSqlBuilder.SqlWithParams batch =
                builder.buildSubTableSelectBatch(DS_MAIN, subTable, columns, List.of(1L, 2L));
        assertTrue(batch.getSql().contains("`deleted` = ?"), batch.getSql());
        assertEquals(3, batch.getParams().size(), "两个外键值 + 未删除值");
    }

    @Test
    @DisplayName("主键列不叫 id 时同样能排序")
    void testNonDefaultPk() {
        SubTableBinding subTable = subTable();
        subTable.setPk("item_id");
        mockTable(SUB_TABLE, "item_id", "order_id", "product_name", "qty");

        Set<String> columns = new LinkedHashSet<>(List.of("item_id", "order_id", "product_name"));
        FormDesignSqlBuilder.SqlWithParams sql =
                builder.buildSubTableSelect(DS_MAIN, subTable, columns, 1L);

        assertTrue(sql.getSql().endsWith("ORDER BY `item_id` ASC"), sql.getSql());
    }

    @Test
    @DisplayName("白名单外的列不允许出现在 SELECT 里")
    void testColumnWhitelistEnforced() {
        Set<String> columns = new LinkedHashSet<>(List.of("id", "order_id", "password"));
        assertThrows(ServiceException.class,
                () -> builder.buildSubTableSelect(DS_MAIN, subTable(), columns, 1L),
                "表里不存在的列必须被元数据校验拦下");
    }

}
