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
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.OptimisticLockConfig;
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
 * 动态 SQL 构建的共享支撑
 *
 * 从 {@link FormDesignSqlBuilder} 里抽出来的公用部分：白名单构建、SELECT 列拼装、
 * 查询条件拼装、逻辑删除条件、租户条件、受保护列校验。
 *
 * 为什么要单独一个类：拆分前主表、副表、子表三段各自的构建逻辑与这些公用方法
 * 混在一个 1600 行的类里，任何一段的改动都要在整个文件里翻找依赖。
 * 抽出来之后三个构建器的边界才是清楚的。
 *
 * 方法都是包级可见 —— 只给同包的几个构建器用，不属于对外 API。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class SqlBuilderSupport {

    @Resource
    SqlIdentifierGuard guard;

    private static final Set<String> BACKEND_MANAGED_COLUMNS = Set.of("tenant_id");

    /**
     * 校验某个物理列是否允许被表单字段映射
     *
     * 选择直接抛错而不是静默跳过：正常配置永远不会映射到这些列，
     * 出现即意味着配置被人为改过，静默跳过会让越权尝试不留痕迹。
     */
    void assertMappable(String column) {
        if (column != null && BACKEND_MANAGED_COLUMNS.contains(column.trim().toLowerCase())) {
            throw exception(FORM_DESIGN_PROTECTED_COLUMN_MAPPED, column);
        }
    }

    void appendMappingColumns(Long dataSourceId, String tableName,
                                      Map<String, FieldMapping> fieldMapping, Map<String, Object> data,
                                      Set<String> declaredColumns,
                                      List<String> columns, List<Object> params) {
        if (fieldMapping == null) {
            return;
        }
        for (Map.Entry<String, FieldMapping> entry : fieldMapping.entrySet()) {
            FieldMapping mapping = entry.getValue();
            if (mapping == null || StrUtil.isBlank(mapping.getColumn())) {
                continue;
            }
            assertMappable(mapping.getColumn());
            String col = guard.validateColumn(dataSourceId, tableName, mapping.getColumn(), declaredColumns);
            columns.add(guard.quote(col));
            params.add(data.get(entry.getKey()));
        }
    }

    /**
     * 判断逻辑删除是否启用
     */
    boolean isLogicDeleteOn(LogicDeleteConfig ld) {
        return ld != null && ld.getEnabled() != null && ld.getEnabled()
                && StrUtil.isNotBlank(ld.getColumn());
    }

    /**
     * 为指定的逻辑删除配置追加 WHERE 条件
     *
     * 与 {@link #appendLogicDeleteWhere} 的区别：那个只处理主表，这个可用于任意从表。
     */
    void appendLogicDeleteWhereFor(LogicDeleteConfig ld, Long dataSourceId, String tableName,
                                           Set<String> declaredColumns,
                                           List<String> whereClauses, List<Object> params) {
        if (!isLogicDeleteOn(ld)) {
            return;
        }
        String col = guard.validateColumn(dataSourceId, tableName, ld.getColumn(), declaredColumns);
        whereClauses.add(guard.quote(col) + " = ?");
        params.add(normalValue(ld));
    }


    /**
     * 构建声明的表名集合（白名单）
     *
     * 三类从表都要加进来，否则 guard.validateTable 会拒绝它们。
     * 这是白名单机制的核心：只有在 table_binding 里显式声明过的表才允许出现在 SQL 里。
     */
    Set<String> buildDeclaredTables(TableBinding binding) {
        Set<String> tables = new HashSet<>();
        tables.add(binding.getMainTable());
        // 副表：参与写入
        if (binding.getSideTables() != null) {
            for (SideTableBinding side : binding.getSideTables()) {
                if (side != null && StrUtil.isNotBlank(side.getTable())) {
                    tables.add(side.getTable());
                }
            }
        }
        // 子表：参与写入
        if (binding.getSubTables() != null) {
            for (SubTableBinding sub : binding.getSubTables()) {
                if (sub != null && StrUtil.isNotBlank(sub.getTable())) {
                    tables.add(sub.getTable());
                }
            }
        }
        // 关联表：只读，但 SELECT 里要出现，也得进白名单
        if (binding.getJoinTables() != null) {
            for (JoinTableBinding join : binding.getJoinTables()) {
                if (join != null && StrUtil.isNotBlank(join.getTable())) {
                    tables.add(join.getTable());
                }
            }
        }
        return tables;
    }

    /**
     * 构建声明的列名集合（白名单）
     *
     * 注意：列白名单不区分表 —— guard.validateColumn 会再用元数据核实
     * 「该列是否真的属于该表」，所以这里合并所有表的列是安全的。
     * 拆成按表分组的 Map 会让调用方复杂很多，收益却只是重复一遍元数据校验。
     */
    Set<String> buildDeclaredColumns(TableBinding binding) {
        Set<String> columns = new HashSet<>();
        columns.add(binding.getMainPk());
        collectMappingColumns(binding.getFieldMapping(), columns);
        if (binding.getAutoFill() != null) {
            // 不分模式全加：MANUAL 的列由表单映射写入，NONE 的列仍需能出现在 SELECT 里
            columns.addAll(binding.getAutoFill().getAllColumns());
        }
        collectLogicDeleteColumn(binding.getLogicDelete(), columns);
        // 版本列要进白名单，否则 validateColumn 会把它当成「未声明的列」拦掉
        if (binding.getOptimisticLock() != null && binding.getOptimisticLock().isUsable()) {
            addIfNotBlank(columns, binding.getOptimisticLock().getColumn());
        }

        // 副表：主键、外键、关联列、映射列、逻辑删除列
        if (binding.getSideTables() != null) {
            for (SideTableBinding side : binding.getSideTables()) {
                if (side == null) {
                    continue;
                }
                addIfNotBlank(columns, side.getPk());
                addIfNotBlank(columns, side.getForeignKey());
                addIfNotBlank(columns, side.getRelationField());
                collectMappingColumns(side.getFieldMapping(), columns);
                collectLogicDeleteColumn(side.getLogicDelete(), columns);
            }
        }

        // 子表：主键、外键、映射列、审计列、逻辑删除列
        if (binding.getSubTables() != null) {
            for (SubTableBinding sub : binding.getSubTables()) {
                if (sub == null) {
                    continue;
                }
                addIfNotBlank(columns, sub.getPk());
                addIfNotBlank(columns, sub.getForeignKey());
                collectMappingColumns(sub.getFieldMapping(), columns);
                if (sub.getAutoFill() != null) {
                    columns.addAll(sub.getAutoFill().getAllColumns());
                }
                collectLogicDeleteColumn(sub.getLogicDelete(), columns);
            }
        }

        // 关联表：ON 条件两侧的列 + SELECT 出来的列
        if (binding.getJoinTables() != null) {
            for (JoinTableBinding join : binding.getJoinTables()) {
                if (join == null) {
                    continue;
                }
                if (join.getOn() != null) {
                    for (JoinTableBinding.JoinCondition on : join.getOn()) {
                        if (on == null) {
                            continue;
                        }
                        // ON 条件可能写成 "表名.列名" 或 "别名.列名"，取最后一段
                        addIfNotBlank(columns, stripQualifier(on.getLeft()));
                        addIfNotBlank(columns, stripQualifier(on.getRight()));
                    }
                }
                if (join.getSelectColumns() != null) {
                    for (JoinTableBinding.SelectColumn sc : join.getSelectColumns()) {
                        if (sc != null) {
                            addIfNotBlank(columns, sc.getColumn());
                        }
                    }
                }
            }
        }
        return columns;
    }

    /**
     * 收集字段映射里的物理列名
     */
    void collectMappingColumns(Map<String, FieldMapping> fieldMapping, Set<String> columns) {
        if (fieldMapping == null) {
            return;
        }
        for (FieldMapping mapping : fieldMapping.values()) {
            if (mapping != null && StrUtil.isNotBlank(mapping.getColumn())) {
                columns.add(mapping.getColumn());
            }
        }
    }

    /**
     * 收集逻辑删除列名（仅启用时）
     */
    void collectLogicDeleteColumn(LogicDeleteConfig ld, Set<String> columns) {
        if (ld != null && ld.getEnabled() != null && ld.getEnabled()) {
            addIfNotBlank(columns, ld.getColumn());
        }
    }

    void addIfNotBlank(Set<String> columns, String column) {
        if (StrUtil.isNotBlank(column)) {
            columns.add(column);
        }
    }

    /**
     * 去掉列名的表名/别名前缀
     *
     * ON 条件里的列写法是 "biz_customer.owner_id" 或 "u.id"，
     * 白名单只关心列名本身，表归属由 guard 用元数据核实。
     */
    String stripQualifier(String column) {
        if (StrUtil.isBlank(column)) {
            return column;
        }
        int idx = column.lastIndexOf('.');
        return idx >= 0 ? column.substring(idx + 1) : column;
    }

    /**
     * 追加逻辑删除 WHERE 条件
     */
    void appendLogicDeleteWhere(TableBinding binding, Long dataSourceId, String tableName,
                                        Set<String> declaredColumns, List<String> whereClauses, List<Object> params) {
        appendLogicDeleteWhere(binding, dataSourceId, tableName, declaredColumns, whereClauses, params, "");
    }

    /**
     * 追加逻辑删除 WHERE 条件
     *
     * @param prefix 表限定前缀（含末尾的点），有 JOIN 时必须传 —— 否则同名列歧义
     */
    void appendLogicDeleteWhere(TableBinding binding, Long dataSourceId, String tableName,
                                        Set<String> declaredColumns, List<String> whereClauses,
                                        List<Object> params, String prefix) {
        LogicDeleteConfig ld = binding.getLogicDelete();
        if (ld != null && ld.getEnabled() != null && ld.getEnabled()
                && StrUtil.isNotBlank(ld.getColumn())) {
            String col = guard.validateColumn(dataSourceId, tableName, ld.getColumn(), declaredColumns);
            whereClauses.add(prefix + guard.quote(col) + " = ?");
            params.add(normalValue(ld));
        }
    }

    /**
     * 取「未删除」的绑参值
     *
     * 必须按 valueType 转换后再绑参：配置里存的是字符串，物理列可能是 bit / tinyint，
     * 直接绑字符串会触发 MySQL 的 Data truncation。
     */
    Object normalValue(LogicDeleteConfig ld) {
        return LogicDeleteValueTypeEnum.parse(ld.getValueType()).convert(ld.getNormalValue());
    }

    /**
     * 取「已删除」的绑参值。转换原因同 {@link #normalValue}
     */
    Object deletedValue(LogicDeleteConfig ld) {
        return LogicDeleteValueTypeEnum.parse(ld.getValueType()).convert(ld.getDeletedValue());
    }

    /**
     * 构建 SELECT 的列部分
     *
     * 只查配置里声明过的列，不用 SELECT * —— 避免 DBA 加列后返回意外字段，
     * 也避免把敏感列暴露给前端。
     */
    String buildSelectColumns(Long dataSourceId, String tableName,
                                      TableBinding binding, Set<String> declaredColumns) {
        return buildSelectColumns(dataSourceId, tableName, binding, declaredColumns, false);
    }

    /**
     * 构建 SELECT 的列部分
     *
     * @param qualify 是否给列加表名前缀。有 JOIN 时必须加 ——
     *                主表与关联表可能有同名列（如两张表都有 name），
     *                不加前缀 MySQL 会报 Column 'name' in field list is ambiguous
     */
    String buildSelectColumns(Long dataSourceId, String tableName,
                                      TableBinding binding, Set<String> declaredColumns,
                                      boolean qualify) {
        List<String> quoted = new ArrayList<>();
        String prefix = qualify ? guard.quote(tableName) + "." : "";
        // 结果集里已经占用的键。审计列、版本列与字段别名可能撞名，
        // 撞了的话 queryForList 会让后者覆盖前者 —— 静默丢值，所以要挡
        Set<String> labels = new HashSet<>();
        // 主键必查，前端需要它做编辑与删除
        String pkCol = guard.validateColumn(dataSourceId, tableName, binding.getMainPk(), declaredColumns);
        quoted.add(prefix + guard.quote(pkCol));
        labels.add(pkCol);

        if (binding.getFieldMapping() != null) {
            for (Map.Entry<String, FieldMapping> entry : binding.getFieldMapping().entrySet()) {
                FieldMapping mapping = entry.getValue();
                if (mapping == null || StrUtil.isBlank(mapping.getColumn())) {
                    continue;
                }
                if (Objects.equals(mapping.getColumn(), pkCol)) {
                    continue;
                }
                // 映射到副表的字段不在主表 SELECT 里 —— 它们由 mergeSideTableData 单独查
                if (StrUtil.isNotBlank(mapping.getTable())
                        && !Objects.equals(mapping.getTable(), tableName)) {
                    continue;
                }
                String col = guard.validateColumn(dataSourceId, tableName, mapping.getColumn(), declaredColumns);
                String alias = fieldAlias(col, entry.getKey());
                quoted.add(prefix + guard.quote(col) + alias);
                labels.add(alias.isEmpty() ? col : entry.getKey());
            }
        }
        // 审计列一并查出，列表页常需要展示。不分模式 —— MANUAL 的列同样要能读回来
        AutoFillConfig af = binding.getAutoFill();
        if (af != null) {
            for (String candidate : af.getAllColumns()) {
                if (declaredColumns.contains(candidate)) {
                    String col = guard.validateColumn(dataSourceId, tableName, candidate, declaredColumns);
                    if (labels.add(col)) {
                        quoted.add(prefix + guard.quote(col));
                    }
                }
            }
        }

        // 乐观锁的版本列必查。它不是表单字段，不在 fieldMapping 里 ——
        // 不查出来的话前端根本拿不到版本号，回传不了，乐观锁就等于没启用
        OptimisticLockConfig lock = binding.getOptimisticLock();
        if (lock != null && lock.isUsable()) {
            String col = guard.validateColumn(dataSourceId, tableName, lock.getColumn(), declaredColumns);
            if (labels.add(col)) {
                quoted.add(prefix + guard.quote(col));
            }
        }

        // 关联表的展示列（只读）。带 AS 别名，前端按别名取值
        if (qualify && binding.getJoinTables() != null) {
            for (JoinTableBinding join : binding.getJoinTables()) {
                if (join == null || StrUtil.isBlank(join.getTable())
                        || join.getSelectColumns() == null) {
                    continue;
                }
                String joinTable = guard.validateTable(dataSourceId, join.getTable(),
                        buildDeclaredTables(binding), false);
                // 有别名用别名，否则用表名
                String joinPrefix = StrUtil.isNotBlank(join.getAlias())
                        ? guard.quote(join.getAlias()) : guard.quote(joinTable);
                for (JoinTableBinding.SelectColumn sc : join.getSelectColumns()) {
                    if (sc == null || StrUtil.isBlank(sc.getColumn())) {
                        continue;
                    }
                    String col = guard.validateColumn(dataSourceId, joinTable, sc.getColumn(), declaredColumns);
                    String expr = joinPrefix + "." + guard.quote(col);
                    if (StrUtil.isNotBlank(sc.getAs())) {
                        // AS 别名也要过标识符校验，否则是注入点
                        expr += " AS " + guard.quote(guard.validateAlias(sc.getAs()));
                    }
                    quoted.add(expr);
                }
            }
        }
        return String.join(", ", quoted);
    }

    /**
     * 「表单字段名 ≠ 物理列名」时，把列 AS 成表单字段名
     *
     * <h3>为什么必须做</h3>
     *
     * 运行时下游全都按【表单字段名】取值：脱敏（{@code FormDataMasker}）、
     * 落库形态还原（{@code FormValueSerializer}）、变更日志比对
     * （{@code FormDataChangeDiffer}）、导出取值、以及前端列表的 {@code row[col.field]}。
     * 而 SELECT 出来的键默认是物理列名。两者不一致时，上述环节全部【静默跳过】——
     * 不报错、不打日志，表现是「脱敏配了不生效、列表列一片空白、变更日志旧值恒为空」。
     *
     * 子表早就用 {@code SubTableRowMapper.toFieldKeyedRows} 做了同样的列名→字段名转换，
     * 这里是把主表补齐 —— 之前只有子表做、主表没做，是这个缺陷的根源。
     *
     * <h3>为什么同名时不加别名</h3>
     *
     * 既有的 639 个映射全是同名（设计器的「字段ID」是从物理列下拉选的）。
     * 给它们统一加 {@code `x` AS `x`} 只是噪音，且会让既有的 SQL 断言全部失配。
     *
     * @param column    物理列名，已过白名单校验
     * @param fieldName 表单字段名，即 fieldMapping 的键
     * @return 形如 {@code  AS `字段名`}；不需要别名时返回空串
     */
    private String fieldAlias(String column, String fieldName) {
        if (StrUtil.isBlank(fieldName) || Objects.equals(column, fieldName)) {
            return "";
        }
        // 字段名不受发布校验约束，可能不是合法 SQL 标识符（含中文、连字符等）。
        // 这时退回「不加别名」而不是抛异常 —— 让整个列表页 500，
        // 比这一个字段的脱敏失效严重得多。打 WARN 是为了让它可被发现而不是继续静默
        if (!guard.isValidIdentifier(fieldName)) {
            log.warn("[buildSelectColumns][字段({})映射到列({})，但字段名不是合法标识符，"
                    + "无法 AS 成字段名；该字段的脱敏、落库形态还原与列表显示将不生效]",
                    fieldName, column);
            return "";
        }
        return " AS " + guard.quote(fieldName);
    }

    /**
     * 追加查询条件
     *
     * 前端传来的 field 必须能在 fieldMapping 里解析出物理列，否则【静默忽略】该条件。
     * 忽略而非报错：前端可能带上历史遗留的查询参数，不该因此让整个列表打不开。
     */
    void appendConditions(Long dataSourceId, String tableName, TableBinding binding,
                                  Set<String> declaredColumns, List<QueryCondition> conditions,
                                  List<String> whereClauses, List<Object> params) {
        appendConditions(dataSourceId, tableName, binding, declaredColumns, conditions,
                whereClauses, params, "");
    }

    /**
     * 追加查询条件
     *
     * @param prefix 表限定前缀（含末尾的点），有 JOIN 时必须传
     */
    void appendConditions(Long dataSourceId, String tableName, TableBinding binding,
                                  Set<String> declaredColumns, List<QueryCondition> conditions,
                                  List<String> whereClauses, List<Object> params, String prefix) {
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        for (QueryCondition condition : conditions) {
            if (condition == null || StrUtil.isBlank(condition.getField())) {
                continue;
            }
            String column = resolveColumn(binding, condition.getField());
            if (column == null) {
                // 未在配置里声明的字段，忽略
                log.debug("忽略未声明的查询字段：{}", condition.getField());
                continue;
            }
            String col = guard.validateColumn(dataSourceId, tableName, column, declaredColumns);
            OperatorEnum operator = guard.validateOperator(condition.getOperator());
            appendOneCondition(prefix + guard.quote(col), operator, condition.getValue(),
                    condition.getValue2(), whereClauses, params, condition.getStoredShape());
        }
    }

    /**
     * 按操作符生成单个条件片段（标量列）
     *
     * 操作符来自枚举，值全部走 ? 参数绑定。
     */
    void appendOneCondition(String quotedCol, OperatorEnum operator,
                                    Object value, Object value2,
                                    List<String> whereClauses, List<Object> params) {
        appendOneCondition(quotedCol, operator, value, value2, whereClauses, params, null);
    }

    /**
     * 按操作符生成单个条件片段
     *
     * 操作符来自枚举，值全部走 ? 参数绑定。
     *
     * @param storedShape 该列的多值落库形态；null 表示按标量处理，行为与历史逐字节一致
     */
    void appendOneCondition(String quotedCol, OperatorEnum operator,
                                    Object value, Object value2,
                                    List<String> whereClauses, List<Object> params,
                                    MultiValueShape storedShape) {
        if (storedShape != null
                && appendMultiValueCondition(quotedCol, operator, value,
                        whereClauses, params, storedShape)) {
            return;
        }
        switch (operator) {
            case IS_NULL -> whereClauses.add(quotedCol + " IS NULL");
            case IS_NOT_NULL -> whereClauses.add(quotedCol + " IS NOT NULL");
            case LIKE -> appendLike(quotedCol, value, whereClauses, params, "%", "%");
            case LEFT_LIKE -> appendLike(quotedCol, value, whereClauses, params, "%", "");
            case RIGHT_LIKE -> appendLike(quotedCol, value, whereClauses, params, "", "%");
            case IN -> {
                List<?> values = toList(value);
                if (values.isEmpty()) return;
                whereClauses.add(quotedCol + " IN (" +
                        String.join(", ", Collections.nCopies(values.size(), "?")) + ")");
                params.addAll(values);
            }
            case BETWEEN -> {
                if (isBlankValue(value) || isBlankValue(value2)) return;
                whereClauses.add(quotedCol + " BETWEEN ? AND ?");
                params.add(value);
                params.add(value2);
            }
            default -> {
                if (isBlankValue(value)) return;
                whereClauses.add(quotedCol + " " + operator.getSqlFragment() + " ?");
                params.add(value);
            }
        }
    }

    /**
     * 模糊匹配。值是集合时逐项 OR —— 见 {@link #appendMultiValueCondition} 里同型的说明
     */
    private void appendLike(String quotedCol, Object value,
                            List<String> whereClauses, List<Object> params,
                            String prefix, String suffix) {
        if (isBlankValue(value)) return;
        List<?> values = value instanceof Collection<?> || value instanceof Object[]
                ? toList(value) : List.of(value);
        if (values.isEmpty()) return;
        List<String> parts = new ArrayList<>(values.size());
        for (Object item : values) {
            if (isBlankValue(item)) continue;
            parts.add(quotedCol + " LIKE ?");
            params.add(prefix + item + suffix);
        }
        if (parts.isEmpty()) return;
        whereClauses.add(parts.size() == 1 ? parts.get(0) : "(" + String.join(" OR ", parts) + ")");
    }

    /**
     * 多值列（JSON 数组 / 分隔串）上的等值类条件
     *
     * <h3>判据是「包含」而不是「相等」</h3>
     *
     * 一行勾选了 {@code [微生物, 重金属]}，用户在查询区勾了 {@code 微生物}，
     * 他想找的就是这一行。而 {@code col = '微生物'} 与
     * {@code col IN ('微生物','重金属')} 都是在拿<b>整串</b>与<b>单项</b>比 ——
     * 前者永远不等，后者只在「这行恰好只勾了一项」时碰巧相等。
     *
     * <p>那个「碰巧」正是本缺陷长期没被发现的原因：它不是查不出数据，
     * 而是<b>查出一部分</b>，看上去像是数据本来就这么少。
     *
     * <h3>多个值之间是 OR</h3>
     *
     * 勾了 {@code 微生物} 与 {@code 重金属}，语义是「勾了其中任一项的行」——
     * 与标量列上 {@code IN} 的语义一致。NE 则取反，是「一项都不含」。
     *
     * <h3>⚠️ NULL 行</h3>
     *
     * NE 不把 {@code col IS NULL} 的行算进来（{@code NOT JSON_CONTAINS(NULL, …)} 是 NULL）。
     * 这与标量列上 {@code col <> ?} 的既有行为一致 —— 刻意保持一致，
     * 否则同一个「不等于」在两种列上给出不同答案，比任一种单独的行为都难解释。
     *
     * @return true 表示已生成片段（含「值为空所以什么都不加」）；
     *         false 表示这个操作符在多值列上没有专门语义，由调用方退回标量分支
     *         —— 大小比较与 BETWEEN 对数组本来就没有意义，IS_NULL / IS_NOT_NULL
     *         两种形态下写法相同，都不必在这里重写一遍
     */
    private boolean appendMultiValueCondition(String quotedCol, OperatorEnum operator, Object value,
                                              List<String> whereClauses, List<Object> params,
                                              MultiValueShape shape) {
        List<?> values;
        boolean negate;
        switch (operator) {
            case EQ, IN -> {
                values = toList(value);
                negate = false;
            }
            case NE -> {
                values = toList(value);
                negate = true;
            }
            case LIKE, LEFT_LIKE, RIGHT_LIKE -> {
                // 模糊匹配对两种形态都成立：MySQL 会把 JSON 值转成文本再比，
                // 分隔串本来就是文本。逐项 OR 的理由同上
                if (isBlankValue(value)) return true;
                appendLike(quotedCol, value, whereClauses, params, "%", "%");
                return true;
            }
            default -> {
                return false;
            }
        }
        // 值为空 ⟹ 用户没勾任何一项 ⟹ 这个条件不成立，什么都不加。
        // 🔴 不能生成一个空的 OR 链或空括号 —— 那是非法 SQL，会让整个列表页 500
        values = values.stream().filter(item -> !isBlankValue(item)).toList();
        if (values.isEmpty()) {
            return true;
        }

        List<String> parts = new ArrayList<>(values.size());
        for (Object item : values) {
            parts.add(containsFragment(quotedCol, shape, params, item));
        }
        if (negate) {
            // NE 要把每一项都排除掉。括号不能省 —— whereClauses 之间是用 AND 连的，
            // 写成 NOT a OR b 会让取反只作用到第一项
            whereClauses.add("(NOT (" + String.join(" OR ", parts) + "))");
        } else {
            whereClauses.add(parts.size() == 1
                    ? parts.get(0)
                    : "(" + String.join(" OR ", parts) + ")");
        }
        return true;
    }

    /**
     * 「这一列包含这个值」的 SQL 片段，并把值追加进参数表
     *
     * <p>🔴 三种写法都<b>只支持 MySQL</b>：JSON_CONTAINS / JSON_QUOTE 是 MySQL 的 JSON 函数，
     * FIND_IN_SET 是 MySQL 方言。这不是偷懒 —— 在线表单运行时本来就只支持主库
     * （{@code FormDesignRuntimeServiceImpl#resolveDataSourceId} 对非主库直接抛错），
     * 列名引号硬编码反引号、分页硬编码 LIMIT/OFFSET 也是同一件事。
     * 真要支持第二种库，这三处与引号、分页要一起改。
     */
    private String containsFragment(String quotedCol, MultiValueShape shape,
                                    List<Object> params, Object item) {
        if (shape.json()) {
            // JSON_QUOTE 把绑进来的裸字符串 a 变成合法 JSON 标量 "a"，
            // 这样 JSON_CONTAINS 才是「数组里有没有这一项」而不是「JSON 文本相等」。
            //
            // ⚠️ 用 JSON_CONTAINS 而不是 JSON_OVERLAPS：后者要 MySQL 8.0.17+，
            // 前者 5.7 就有。这里一次只比一项，用不上 OVERLAPS 的多对多语义
            params.add(item);
            return "JSON_CONTAINS(" + quotedCol + ", JSON_QUOTE(?))";
        }
        String separator = StrUtil.isEmpty(shape.separator()) ? "," : shape.separator();
        if (",".equals(separator)) {
            // FIND_IN_SET 是精确的按项匹配，不是子串匹配 ——
            // 「微生物」不会命中「微生物学」。本仓库已有同型先例，
            // 见 DataInterfaceMapper 里 tags 那一列的注释
            params.add(item);
            return "FIND_IN_SET(?, " + quotedCol + ") > 0";
        }
        // 分隔符可配（structural.delimitedSeparator），而 FIND_IN_SET 只认逗号。
        // 这时改用「两侧补分隔符再做边界匹配」：把 a|b 变成 |a|b|，
        // 找 |x| 就是精确的按项匹配，对任何分隔符都成立。
        //
        // 分隔符本身也走参数绑定，不内联进 SQL —— 它来自控件契约声明而不是代码常量，
        // 内联等于把一个可改的配置值直接拼进语句
        params.add(separator);
        params.add(separator);
        params.add(separator);
        params.add(item);
        params.add(separator);
        return "CONCAT(?, " + quotedCol + ", ?) LIKE CONCAT('%', ?, ?, ?, '%')";
    }

    /**
     * 由 form-create 的 field 解析出物理列名
     *
     * 解析不到返回 null，由调用方决定忽略还是报错。
     */
    String resolveColumn(TableBinding binding, String field) {
        if (binding.getFieldMapping() == null) {
            return null;
        }
        FieldMapping mapping = binding.getFieldMapping().get(field);
        if (mapping != null && StrUtil.isNotBlank(mapping.getColumn())) {
            return mapping.getColumn();
        }
        // 允许直接传主键列名，列表页删除/编辑会用到
        if (Objects.equals(field, binding.getMainPk())) {
            return binding.getMainPk();
        }
        return null;
    }

    boolean isBlankValue(Object value) {
        return value == null || (value instanceof CharSequence && StrUtil.isBlank((CharSequence) value));
    }

    List<?> toList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value instanceof Object[] array) {
            return Arrays.asList(array);
        }
        // 逗号分隔的字符串
        if (value instanceof CharSequence) {
            return Arrays.stream(value.toString().split(","))
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .toList();
        }
        return List.of(value);
    }

    /**
     * 追加租户隔离条件
     *
     * 表单设计模块走 JdbcTemplate 拼原生 SQL，绕过了框架的 TenantDatabaseInterceptor
     * （它只对 MyBatis Mapper 生效）。必须自行追加 tenant_id = ? 防止跨租户数据越权。
     *
     * @param declaredColumns 当前表的已声明列集合，用于判定 tenant_id 列是否存在
     * @param whereClauses    WHERE 子句列表，会追加 tenant_id 条件
     * @param params          参数列表，会追加租户 ID
     */
    void appendTenantWhere(Long dataSourceId, String tableName,
                                   Set<String> declaredColumns,
                                   List<String> whereClauses, List<Object> params) {
        appendTenantWhereWithPrefix(dataSourceId, tableName, declaredColumns, whereClauses, params, "");
    }

    /**
     * 追加租户隔离条件（带表前缀）
     *
     * @param prefix 表前缀，如 "`biz_customer`."，无 JOIN 时为空串
     */
    void appendTenantWhereWithPrefix(Long dataSourceId, String tableName,
                                             Set<String> declaredColumns,
                                             List<String> whereClauses, List<Object> params,
                                             String prefix) {
        // 全局忽略多租户时跳过（定时任务等场景）
        if (TenantContextHolder.isIgnore()) {
            return;
        }
        // 表里没有 tenant_id 列时跳过 —— 直接查元数据而不依赖白名单，
        // 因为 tenant_id 通常不在用户配置的 fieldMapping 里
        Set<String> realColumns = guard.getRealColumns(dataSourceId, tableName);
        if (!realColumns.contains("tenant_id")) {
            return;
        }
        whereClauses.add(prefix + guard.quote("tenant_id") + " = ?");
        params.add(TenantContextHolder.getRequiredTenantId());
    }

}
