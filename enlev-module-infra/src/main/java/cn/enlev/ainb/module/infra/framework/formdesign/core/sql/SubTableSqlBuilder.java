package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.infra.enums.formdesign.LogicDeleteValueTypeEnum;
import cn.enlev.ainb.module.infra.enums.formdesign.OperatorEnum;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldNaming;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.AutoFillConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.JoinTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.LogicDeleteConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SideTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.FormDesignSqlBuilder.QueryCondition;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.FormDesignSqlBuilder.SqlWithParams;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * 子表（1:N 明细）的查询 SQL 构建
 *
 * 子表的写入走 {@link SubTableDiffEngine} 做增删改差分，这里只负责查询。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class SubTableSqlBuilder {

    @Resource
    private SqlIdentifierGuard guard;
    @Resource
    private SqlBuilderSupport support;

    /**
     * 构建子表明细查询语句
     *
     * @param subTableName 子表名
     * @param foreignKey   外键列名
     * @param subColumns   子表的列名集合（白名单），必须包含外键列
     * @param fkValue      主表主键值
     * @param logicDelete  子表的逻辑删除配置，为 null 时不过滤
     */
    public SqlWithParams buildSubTableSelect(Long dataSourceId, String subTableName,
                                             String foreignKey, Set<String> subColumns,
                                             Object fkValue, LogicDeleteConfig logicDelete) {
        String tableName = guard.validateTable(dataSourceId, subTableName, Set.of(subTableName), false);
        String fkCol = guard.validateColumn(dataSourceId, tableName, foreignKey, subColumns);

        List<String> quoted = new ArrayList<>();
        for (String col : subColumns) {
            quoted.add(guard.quote(guard.validateColumn(dataSourceId, tableName, col, subColumns)));
        }

        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        whereClauses.add(guard.quote(fkCol) + " = ?");
        params.add(fkValue);
        // 已逻辑删除的明细不该查出来
        support.appendLogicDeleteWhereFor(logicDelete, dataSourceId, tableName,
                subColumns, whereClauses, params);
        support.appendTenantWhere(dataSourceId, tableName, subColumns, whereClauses, params);

        String sql = String.format("SELECT %s FROM %s WHERE %s",
                String.join(", ", quoted), guard.quote(tableName),
                String.join(" AND ", whereClauses));
        return new SqlWithParams(sql, params);
    }

    /**
     * 构建子表明细查询语句（不过滤逻辑删除）
     *
     * @deprecated 保留给旧调用方。新代码请用带 logicDelete 参数的重载，
     *             否则已逻辑删除的明细也会被查出来。
     */
    @Deprecated
    public SqlWithParams buildSubTableSelect(Long dataSourceId, String subTableName,
                                             String foreignKey, Set<String> subColumns,
                                             Object fkValue) {
        return buildSubTableSelect(dataSourceId, subTableName, foreignKey, subColumns, fkValue, null);
    }

    /**
     * 构建单条主表记录的子表明细查询
     *
     * 与旧的松散参数重载相比，这里直接吃 {@link SubTableBinding}，
     * 主键、外键、逻辑删除都从配置里取，调用方不会漏传。
     * 并且【按主键排序】—— 明细顺序必须稳定，否则同一条单据两次打开
     * 明细行的先后会变，主子表列表里合并出来的行也会跳动。
     *
     * @param subColumns 子表的列名白名单，必须包含主键与外键
     */
    public SqlWithParams buildSubTableSelect(Long dataSourceId, SubTableBinding subTable,
                                             Set<String> subColumns, Object fkValue) {
        String tableName = guard.validateTable(dataSourceId, subTable.getTable(),
                Set.of(subTable.getTable()), false);
        String fkCol = guard.validateColumn(dataSourceId, tableName, subTable.getForeignKey(), subColumns);
        String pkCol = guard.validateColumn(dataSourceId, tableName,
                SubTableRowMapper.pkColumnOf(subTable), subColumns);

        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        whereClauses.add(guard.quote(fkCol) + " = ?");
        params.add(fkValue);
        support.appendLogicDeleteWhereFor(subTable.getLogicDelete(), dataSourceId, tableName,
                subColumns, whereClauses, params);
        support.appendTenantWhere(dataSourceId, tableName, subColumns, whereClauses, params);

        String sql = String.format("SELECT %s FROM %s WHERE %s ORDER BY %s ASC",
                quoteSubColumns(dataSourceId, tableName, subColumns), guard.quote(tableName),
                String.join(" AND ", whereClauses), guard.quote(pkCol));
        return new SqlWithParams(sql, params);
    }

    /**
     * 构建子表明细的批量查询
     *
     * 供主子表列表用：拿到当页主表主键后，用 {@code fk IN (...)} 一次查出所有明细，
     * 再按外键值分组挂回各主行。逐行查会让一页 20 条主记录发出 20 次查询。
     *
     * 与副表批量查询的区别：这里【不】给列加 AS 别名。副表的列要平铺进主行，
     * 必须靠前缀避免与主表同名列相撞；明细行是独立的嵌套数组，不存在这个问题，
     * 保持物理列名反而让 {@link SubTableRowMapper} 能统一做一次列名 → 字段名转换。
     *
     * @param fkValues 主表主键值集合，不能为空
     */
    public SqlWithParams buildSubTableSelectBatch(Long dataSourceId, SubTableBinding subTable,
                                                  Set<String> subColumns, Collection<?> fkValues) {
        if (fkValues == null || fkValues.isEmpty()) {
            // 空 IN 会生成 IN () 这种非法 SQL，调用方应先判空
            throw exception(FORM_DESIGN_RUNTIME_EXECUTE_FAIL, "子表批量查询的主键集合不能为空");
        }
        String tableName = guard.validateTable(dataSourceId, subTable.getTable(),
                Set.of(subTable.getTable()), false);
        String fkCol = guard.validateColumn(dataSourceId, tableName, subTable.getForeignKey(), subColumns);
        String pkCol = guard.validateColumn(dataSourceId, tableName,
                SubTableRowMapper.pkColumnOf(subTable), subColumns);

        List<Object> params = new ArrayList<>(fkValues);
        String placeholders = String.join(", ", Collections.nCopies(fkValues.size(), "?"));
        List<String> whereClauses = new ArrayList<>();
        whereClauses.add(guard.quote(fkCol) + " IN (" + placeholders + ")");
        support.appendLogicDeleteWhereFor(subTable.getLogicDelete(), dataSourceId, tableName,
                subColumns, whereClauses, params);
        support.appendTenantWhere(dataSourceId, tableName, subColumns, whereClauses, params);

        // 先按外键再按主键排序：同一主记录的明细连续且顺序固定，
        // 分组时不用再排一遍，合并出来的表格行也不会跳
        String sql = String.format("SELECT %s FROM %s WHERE %s ORDER BY %s ASC, %s ASC",
                quoteSubColumns(dataSourceId, tableName, subColumns), guard.quote(tableName),
                String.join(" AND ", whereClauses), guard.quote(fkCol), guard.quote(pkCol));
        return new SqlWithParams(sql, params);
    }

    /**
     * 校验并拼接子表的 SELECT 列
     */
    private String quoteSubColumns(Long dataSourceId, String tableName, Set<String> subColumns) {
        List<String> quoted = new ArrayList<>();
        for (String col : subColumns) {
            quoted.add(guard.quote(guard.validateColumn(dataSourceId, tableName, col, subColumns)));
        }
        return String.join(", ", quoted);
    }
}
