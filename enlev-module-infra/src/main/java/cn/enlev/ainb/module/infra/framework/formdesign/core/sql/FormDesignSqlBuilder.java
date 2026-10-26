package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.enums.formdesign.LogicDeleteValueTypeEnum;
import cn.enlev.ainb.module.infra.enums.formdesign.OperatorEnum;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldNaming;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.AutoFillConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.value.TemporalValueCoercer;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.JoinTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.LogicDeleteConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.OptimisticLockConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SideTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DataRowFilterRespDTO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.permission.FormRowFilter;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * 动态 SQL 构建器
 *
 * 基于 TableBinding 与 SqlIdentifierGuard 构建 INSERT/UPDATE/DELETE/SELECT。
 * 所有表名列名都经过守卫校验，所有值都通过参数绑定（? 占位符）。
 *
 * 返回的 {@link SqlWithParams} 包含 SQL 字符串与参数列表，调用方负责执行与事务。
 *
 * <h3>拆分后的职责边界</h3>
 *
 * 本类原先是一个 1600 行的单体，主表、副表、子表三段构建逻辑与大量公用方法混在一起，
 * 改任何一段都要在整个文件里翻找依赖。现在拆成：
 * <ul>
 *   <li>本类：主表的 INSERT/UPDATE/DELETE/SELECT/COUNT/去重值，以及副表、子表的委托入口</li>
 *   <li>{@link SqlBuilderSupport}：白名单、SELECT 列、查询条件、逻辑删除、租户条件等公用部分</li>
 *   <li>{@link SideTableSqlBuilder}：副表（1:1，参与写入）</li>
 *   <li>{@link SubTableSqlBuilder}：子表（1:N 明细）查询</li>
 * </ul>
 * 对外 API 一个没变 —— 副表与子表的方法仍从本类调用，只是转成委托。
 * {@link SqlWithParams} 与 {@link QueryCondition} 也留在本类，避免动到 110 处调用点。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormDesignSqlBuilder {

    /**
     * 行级权限。由本类统一在三个读出口上追加条件，而不是让每个调用方记得传 ——
     * 漏掉任何一个出口都是一个越权出口，而「漏了」在界面上看不出来
     */
    @Resource
    private FormRowFilter rowFilter;
    @Resource
    private SqlIdentifierGuard guard;
    @Resource
    private SqlBuilderSupport support;
    @Resource
    private SideTableSqlBuilder sideTableSqlBuilder;
    @Resource
    private SubTableSqlBuilder subTableSqlBuilder;

    /**
     * 构建 INSERT 语句
     *
     * @param dataSourceId 数据源编号
     * @param binding      表绑定配置
     * @param data         form-create 的提交数据（field → value）
     * @param userId       当前用户编号，用于自动填充 creator
     * @param pkValue      主键值。若主键策略为 AUTO 则传 null，若为 SNOWFLAKE/UUID 则由调用方生成后传入
     * @return SQL 与参数
     */
    public SqlWithParams buildInsert(Long dataSourceId, TableBinding binding,
                                     Map<String, Object> data, Long userId, Object pkValue) {
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        Set<String> declaredColumns = support.buildDeclaredColumns(binding);

        String tableName = guard.validateTable(dataSourceId, binding.getMainTable(), declaredTables, true);

        List<String> columns = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        // 主键列（如果策略不是 AUTO）
        if (pkValue != null && StrUtil.isNotBlank(binding.getMainPk())) {
            String pkCol = guard.validateColumn(dataSourceId, tableName, binding.getMainPk(), declaredColumns);
            columns.add(guard.quote(pkCol));
            params.add(pkValue);
        }

        // 字段映射
        if (binding.getFieldMapping() != null) {
            for (Map.Entry<String, FieldMapping> entry : binding.getFieldMapping().entrySet()) {
                String field = entry.getKey();
                FieldMapping mapping = entry.getValue();
                if (mapping == null || StrUtil.isBlank(mapping.getColumn())) {
                    continue;
                }
                // 租户等由后端维护的列不接受表单值，见 BACKEND_MANAGED_COLUMNS
                support.assertMappable(mapping.getColumn());
                String col = guard.validateColumn(dataSourceId, tableName, mapping.getColumn(), declaredColumns);
                Object value = data.get(field);
                columns.add(guard.quote(col));
                // 时间列容忍纪元毫秒：读取路径下发的就是毫秒数，
                // 原样回传时不归一会被数据库拒绝。见 TemporalValueCoercer
                params.add(TemporalValueCoercer.coerce(value, mapping.getJdbcType()));
            }
        }

        // 自动填充。只有 AUTO 模式才由后端写入 ——
        // MANUAL 交给上面的 fieldMapping，NONE 两边都不写，
        // 否则同一列会被拼两次，生成 INSERT INTO t (creator, creator) 这种非法 SQL
        AutoFillConfig autoFill = binding.getAutoFill();
        if (autoFill != null) {
            if (autoFill.isCreatorAuto()) {
                String col = guard.validateColumn(dataSourceId, tableName, autoFill.getCreatorColumn(), declaredColumns);
                columns.add(guard.quote(col));
                params.add(userId);
            }
            if (autoFill.isCreateTimeAuto()) {
                String col = guard.validateColumn(dataSourceId, tableName, autoFill.getCreateTimeColumn(), declaredColumns);
                columns.add(guard.quote(col));
                params.add(LocalDateTime.now());
            }
            if (autoFill.isUpdaterAuto()) {
                String col = guard.validateColumn(dataSourceId, tableName, autoFill.getUpdaterColumn(), declaredColumns);
                columns.add(guard.quote(col));
                params.add(userId);
            }
            if (autoFill.isUpdateTimeAuto()) {
                String col = guard.validateColumn(dataSourceId, tableName, autoFill.getUpdateTimeColumn(), declaredColumns);
                columns.add(guard.quote(col));
                params.add(LocalDateTime.now());
            }
        }

        // 租户隔离：写入时自动填充当前租户 ID
        // 直接查元数据而不依赖白名单，因为 tenant_id 通常不在 fieldMapping 里
        if (!TenantContextHolder.isIgnore()
                && guard.getRealColumns(dataSourceId, tableName).contains("tenant_id")) {
            columns.add(guard.quote("tenant_id"));
            params.add(TenantContextHolder.getRequiredTenantId());
        }

        // 逻辑删除默认值（未删除）
        LogicDeleteConfig logicDelete = binding.getLogicDelete();
        if (logicDelete != null && logicDelete.getEnabled() != null && logicDelete.getEnabled()
                && StrUtil.isNotBlank(logicDelete.getColumn())) {
            String col = guard.validateColumn(dataSourceId, tableName, logicDelete.getColumn(), declaredColumns);
            columns.add(guard.quote(col));
            params.add(support.normalValue(logicDelete));
        }

        String columnsPart = String.join(", ", columns);
        String valuesPart = String.join(", ", Collections.nCopies(columns.size(), "?"));
        String sql = String.format("INSERT INTO %s (%s) VALUES (%s)",
                guard.quote(tableName), columnsPart, valuesPart);

        return new SqlWithParams(sql, params);
    }

    /**
     * 构建 UPDATE 语句（不带乐观锁版本号）
     *
     * 子表明细的逐行更新走这个重载：乐观锁配在主表上，明细行没有自己的版本列。
     * 主表的更新一律走带 version 的那个 —— 少传版本号的表现是「开了乐观锁但不生效」。
     */
    public SqlWithParams buildUpdate(Long dataSourceId, TableBinding binding,
                                     Map<String, Object> data, Long userId, Object pkValue) {
        return buildUpdate(dataSourceId, binding, data, userId, pkValue, null);
    }

    /**
     * 构建 UPDATE 语句
     *
     * @param dataSourceId 数据源编号
     * @param binding      表绑定配置
     * @param data         form-create 的提交数据
     * @param userId       当前用户编号
     * @param pkValue      主键值
     * @param version      乐观锁版本号。未启用乐观锁时忽略
     * @return SQL 与参数
     */
    public SqlWithParams buildUpdate(Long dataSourceId, TableBinding binding,
                                     Map<String, Object> data, Long userId, Object pkValue,
                                     Object version) {
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        Set<String> declaredColumns = support.buildDeclaredColumns(binding);

        String tableName = guard.validateTable(dataSourceId, binding.getMainTable(), declaredTables, true);
        String pkCol = guard.validateColumn(dataSourceId, tableName, binding.getMainPk(), declaredColumns);

        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        // 字段映射
        if (binding.getFieldMapping() != null) {
            for (Map.Entry<String, FieldMapping> entry : binding.getFieldMapping().entrySet()) {
                String field = entry.getKey();
                FieldMapping mapping = entry.getValue();
                if (mapping == null || StrUtil.isBlank(mapping.getColumn())) {
                    continue;
                }
                // 局部更新：只改提交上来的字段。
                // 无条件遍历整个 fieldMapping 会把没提交的字段取成 null 写进 SET，
                // 于是审批节点按字段权限裁剪后一提交，发起人填的内容就被整片清空，且不报错
                if (data == null || !data.containsKey(field)) {
                    continue;
                }
                // 租户等由后端维护的列不接受表单值，见 BACKEND_MANAGED_COLUMNS
                support.assertMappable(mapping.getColumn());
                String col = guard.validateColumn(dataSourceId, tableName, mapping.getColumn(), declaredColumns);
                Object value = data.get(field);
                setClauses.add(guard.quote(col) + " = ?");
                // 同 buildInsert：时间列容忍纪元毫秒，见 TemporalValueCoercer
                params.add(TemporalValueCoercer.coerce(value, mapping.getJdbcType()));
            }
        }

        // 自动填充：更新时只填 updater 与 update_time，且仅 AUTO 模式
        AutoFillConfig autoFill = binding.getAutoFill();
        if (autoFill != null) {
            if (autoFill.isUpdaterAuto()) {
                String col = guard.validateColumn(dataSourceId, tableName, autoFill.getUpdaterColumn(), declaredColumns);
                setClauses.add(guard.quote(col) + " = ?");
                params.add(userId);
            }
            if (autoFill.isUpdateTimeAuto()) {
                String col = guard.validateColumn(dataSourceId, tableName, autoFill.getUpdateTimeColumn(), declaredColumns);
                setClauses.add(guard.quote(col) + " = ?");
                params.add(LocalDateTime.now());
            }
        }

        if (setClauses.isEmpty()) {
            throw new IllegalArgumentException("UPDATE 没有可更新的列");
        }

        // 乐观锁：版本列自增。
        // ⚠️ 必须放在 setClauses 判空【之后】—— 放在之前的话，一次「什么字段都没提交」
        // 的更新会因为多了这一条 SET 而变成合法 SQL，把版本号白白推进一格，
        // 别人手上的版本号随之作废
        OptimisticLockConfig lock = binding.getOptimisticLock();
        String versionCol = null;
        if (lock != null && lock.isUsable()) {
            versionCol = guard.validateColumn(dataSourceId, tableName, lock.getColumn(), declaredColumns);
            // 用 列 = 列 + 1 而不是绑一个算好的值：并发下算好的值可能已经过期，
            // 而这条表达式由数据库在行锁内求值
            setClauses.add(guard.quote(versionCol) + " = " + guard.quote(versionCol) + " + 1");
        }

        // WHERE 主键 + 逻辑删除 + 租户
        List<String> whereClauses = new ArrayList<>();
        whereClauses.add(guard.quote(pkCol) + " = ?");
        params.add(pkValue);

        // 乐观锁的版本条件。版本号由调用方从提交数据里取出后传进来 ——
        // 它不是表单字段，不能走 fieldMapping
        if (versionCol != null) {
            whereClauses.add(guard.quote(versionCol) + " = ?");
            params.add(version);
        }

        support.appendLogicDeleteWhere(binding, dataSourceId, tableName, declaredColumns, whereClauses, params);
        support.appendTenantWhere(dataSourceId, tableName, declaredColumns, whereClauses, params);
        // 行级权限：改之前，目标行必须本来就在当前用户的可见域内。
        //
        // 放在 WHERE 里而不是先 SELECT 再判断，有两个好处：
        //   ① 零额外查询；② 不存在「查完到改之间」的竞态
        // 无权时影响行数为 0，调用方按「记录不存在或已被修改」处理 —— 这正是想要的语义：
        // 不告诉越权者「这条记录存在但你没权限」
        rowFilter.append(rowFilter.resolve(tableName, ""), whereClauses, params);

        String sql = String.format("UPDATE %s SET %s WHERE %s",
                guard.quote(tableName),
                String.join(", ", setClauses),
                String.join(" AND ", whereClauses));

        return new SqlWithParams(sql, params);
    }

    /**
     * 构建「系统列」UPDATE 语句
     *
     * 用途：流程对接需要回写 process_instance_id / process_status 这类**系统列**，它们不是表单字段，
     * 因此不在 fieldMapping 里，走 {@link #buildUpdate} 会被直接忽略（那里只遍历 fieldMapping）。
     *
     * 安全约束（务必保留）：
     * 1. 列名必须在调用方传入的 allowedColumns 白名单内 —— 本方法不允许写任意列，
     *    否则就成了绕过表绑定配置的通用写入口子
     * 2. 仍走 guard 的正则与真实元数据校验
     * 3. 只更新传入的列，不像 buildUpdate 那样全量覆盖 —— 系统列回写是局部更新语义
     *
     * @param dataSourceId   数据源编号
     * @param binding        表绑定
     * @param columnValues   列名 -> 值。列名是物理列名，不是表单字段名
     * @param allowedColumns 允许写入的系统列白名单
     * @param pkValue        主键值
     * @return SQL 与参数
     */
    public SqlWithParams buildSystemColumnUpdate(Long dataSourceId, TableBinding binding,
                                                 Map<String, Object> columnValues,
                                                 Set<String> allowedColumns,
                                                 Object pkValue) {
        if (columnValues == null || columnValues.isEmpty()) {
            throw new IllegalArgumentException("UPDATE 没有可更新的列");
        }
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        Set<String> declaredColumns = support.buildDeclaredColumns(binding);

        String tableName = guard.validateTable(dataSourceId, binding.getMainTable(), declaredTables, true);
        String pkCol = guard.validateColumn(dataSourceId, tableName, binding.getMainPk(), declaredColumns);

        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> entry : columnValues.entrySet()) {
            String column = entry.getKey();
            if (allowedColumns == null || !allowedColumns.contains(column)) {
                throw new IllegalArgumentException("不允许写入的系统列：" + column);
            }
            // 系统列不在 declaredColumns 里，把它临时并入白名单交给 guard 做正则与元数据校验
            Set<String> allowed = new HashSet<>(declaredColumns);
            allowed.add(column);
            String col = guard.validateColumn(dataSourceId, tableName, column, allowed);
            setClauses.add(guard.quote(col) + " = ?");
            params.add(entry.getValue());
        }

        // WHERE 主键 + 逻辑删除 + 租户
        List<String> whereClauses = new ArrayList<>();
        whereClauses.add(guard.quote(pkCol) + " = ?");
        params.add(pkValue);

        support.appendLogicDeleteWhere(binding, dataSourceId, tableName, declaredColumns, whereClauses, params);
        support.appendTenantWhere(dataSourceId, tableName, declaredColumns, whereClauses, params);

        String sql = String.format("UPDATE %s SET %s WHERE %s",
                guard.quote(tableName),
                String.join(", ", setClauses),
                String.join(" AND ", whereClauses));

        return new SqlWithParams(sql, params);
    }

    /**
     * 构建 DELETE 语句
     *
     * 配了逻辑删除时生成 UPDATE 标记语句，否则生成物理 DELETE。
     */
    public SqlWithParams buildDelete(Long dataSourceId, TableBinding binding,
                                     Long userId, Object pkValue) {
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        Set<String> declaredColumns = support.buildDeclaredColumns(binding);

        String tableName = guard.validateTable(dataSourceId, binding.getMainTable(), declaredTables, true);
        String pkCol = guard.validateColumn(dataSourceId, tableName, binding.getMainPk(), declaredColumns);

        LogicDeleteConfig ld = binding.getLogicDelete();
        boolean logicDeleteOn = ld != null && ld.getEnabled() != null && ld.getEnabled()
                && StrUtil.isNotBlank(ld.getColumn());

        List<Object> params = new ArrayList<>();

        if (logicDeleteOn) {
            // 逻辑删除：UPDATE 标记列，顺带更新 updater / update_time
            String ldCol = guard.validateColumn(dataSourceId, tableName, ld.getColumn(), declaredColumns);
            List<String> setClauses = new ArrayList<>();
            setClauses.add(guard.quote(ldCol) + " = ?");
            params.add(support.deletedValue(ld));

            // 顺带更新审计列，同样只认 AUTO 模式
            AutoFillConfig autoFill = binding.getAutoFill();
            if (autoFill != null) {
                if (autoFill.isUpdaterAuto()) {
                    String col = guard.validateColumn(dataSourceId, tableName, autoFill.getUpdaterColumn(), declaredColumns);
                    setClauses.add(guard.quote(col) + " = ?");
                    params.add(userId);
                }
                if (autoFill.isUpdateTimeAuto()) {
                    String col = guard.validateColumn(dataSourceId, tableName, autoFill.getUpdateTimeColumn(), declaredColumns);
                    setClauses.add(guard.quote(col) + " = ?");
                    params.add(LocalDateTime.now());
                }
            }

            List<String> whereClauses = new ArrayList<>();
            whereClauses.add(guard.quote(pkCol) + " = ?");
            params.add(pkValue);
            // 已删除的记录不再重复删
            whereClauses.add(guard.quote(ldCol) + " = ?");
            params.add(support.normalValue(ld));
            // 租户隔离
            support.appendTenantWhere(dataSourceId, tableName, declaredColumns, whereClauses, params);
            // 行级权限：不能删自己看不见的行。理由同 buildUpdate
            rowFilter.append(rowFilter.resolve(tableName, ""), whereClauses, params);

            String sql = String.format("UPDATE %s SET %s WHERE %s",
                    guard.quote(tableName),
                    String.join(", ", setClauses),
                    String.join(" AND ", whereClauses));
            return new SqlWithParams(sql, params);
        }

        // 物理删除也需租户隔离
        List<String> whereClauses = new ArrayList<>();
        whereClauses.add(guard.quote(pkCol) + " = ?");
        params.add(pkValue);
        support.appendTenantWhere(dataSourceId, tableName, declaredColumns, whereClauses, params);
        // 行级权限：物理删除同样不能删自己看不见的行 ——
        // 逻辑删除那条分支加了而这条漏了的话，凡是没配逻辑删除的表单就全是敞开的
        rowFilter.append(rowFilter.resolve(tableName, ""), whereClauses, params);

        String sql = String.format("DELETE FROM %s WHERE %s",
                guard.quote(tableName), String.join(" AND ", whereClauses));
        return new SqlWithParams(sql, params);
    }

    /**
     * 构建单条查询语句
     */
    public SqlWithParams buildGet(Long dataSourceId, TableBinding binding, Object pkValue) {
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        Set<String> declaredColumns = support.buildDeclaredColumns(binding);

        String tableName = guard.validateTable(dataSourceId, binding.getMainTable(), declaredTables, false);
        String pkCol = guard.validateColumn(dataSourceId, tableName, binding.getMainPk(), declaredColumns);

        String selectPart = support.buildSelectColumns(dataSourceId, tableName, binding, declaredColumns);

        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        whereClauses.add(guard.quote(pkCol) + " = ?");
        params.add(pkValue);
        support.appendLogicDeleteWhere(binding, dataSourceId, tableName, declaredColumns, whereClauses, params);
        support.appendTenantWhere(dataSourceId, tableName, declaredColumns, whereClauses, params);
        // 行级权限。无 JOIN，列名不加前缀
        rowFilter.append(rowFilter.resolve(tableName, ""), whereClauses, params);

        String sql = String.format("SELECT %s FROM %s WHERE %s",
                selectPart, guard.quote(tableName), String.join(" AND ", whereClauses));
        return new SqlWithParams(sql, params);
    }

    /**
     * 构建分页查询语句
     *
     * @param conditions 查询条件。field 必须命中 fieldMapping 的键，否则该条件被忽略 ——
     *                   这层过滤在服务端做，不依赖前端自律
     * @param sortField  排序字段，需命中白名单
     * @param sortOrder  排序方向，只允许 ASC / DESC
     */
    public SqlWithParams buildPage(Long dataSourceId, TableBinding binding,
                                   List<QueryCondition> conditions,
                                   String sortField, String sortOrder,
                                   int pageNo, int pageSize) {
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        Set<String> declaredColumns = support.buildDeclaredColumns(binding);

        String tableName = guard.validateTable(dataSourceId, binding.getMainTable(), declaredTables, false);

        // 有关联表时需要 JOIN，此时所有列都要带表限定前缀以消除同名列歧义
        Map<String, String> qualifiers = new LinkedHashMap<>();
        String joinPart = buildJoinClause(dataSourceId, binding, declaredTables, declaredColumns, qualifiers);
        boolean hasJoin = StrUtil.isNotBlank(joinPart);
        String selectPart = support.buildSelectColumns(dataSourceId, tableName, binding, declaredColumns, hasJoin);
        String mainPrefix = hasJoin ? guard.quote(tableName) + "." : "";
        qualifiers.put(tableName.toLowerCase(), mainPrefix);

        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        support.appendLogicDeleteWhere(binding, dataSourceId, tableName, declaredColumns,
                whereClauses, params, mainPrefix);
        support.appendConditions(dataSourceId, tableName, binding, declaredColumns, conditions,
                whereClauses, params, mainPrefix);
        // 租户隔离（mainPrefix 带表限定前缀时要传进去）
        support.appendTenantWhereWithPrefix(dataSourceId, tableName, declaredColumns,
                whereClauses, params, mainPrefix);
        // 行级权限。有 JOIN 时必须带主表前缀，否则裸列名在多表下会歧义 ——
        // 要么 SQL 直接报错，要么匹配到关联表的同名列，那就是错误的过滤。
        // qualifiers 也得传：槽位映射能绑到关联表上，而关联表在本次查询里的合法限定符是别名，
        // 只有拼 JOIN 的这一刻知道该用哪个
        rowFilter.append(rowFilter.resolve(tableName, mainPrefix, qualifiers), whereClauses, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(selectPart)
           .append(" FROM ").append(guard.quote(tableName));
        if (hasJoin) {
            sql.append(joinPart);
        }
        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }

        // 排序
        if (StrUtil.isNotBlank(sortField)) {
            String col = support.resolveColumn(binding, sortField);
            if (col != null) {
                String validCol = guard.validateColumn(dataSourceId, tableName, col, declaredColumns);
                String direction = guard.validateSortDirection(sortOrder);
                sql.append(" ORDER BY ").append(mainPrefix).append(guard.quote(validCol))
                   .append(" ").append(direction);
            }
        }

        // 分页。LIMIT/OFFSET 用参数绑定，不拼接
        sql.append(" LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add((pageNo - 1) * pageSize);

        return new SqlWithParams(sql.toString(), params);
    }

    /**
     * 构建 JOIN 子句
     *
     * 只处理 joinTables（只读关联表）。副表不 JOIN —— 它由 mergeSideTableData 单独查，
     * 这样 1:1 被破坏时（外键无唯一索引导致多行）不会让主表行数翻倍。
     *
     * @param qualifiersOut 出参：本次查询里每张关联表的合法限定符，
     *                      key 是<b>物理表名</b>（小写），value 是带反引号与结尾点的限定符。
     *                      行级权限要用它把槽位映射上的表名翻译成 SQL 里真正能写的东西 ——
     *                      关联表一旦带别名，用物理表名去限定就是非法 SQL
     *                      （MySQL 报 Unknown column）。同一张表被 JOIN 两次时后写的覆盖先写的，
     *                      这种配置下槽位映射本就无从区分是哪一个别名
     * @return JOIN 子句，无关联表时返回空串
     */
    private String buildJoinClause(Long dataSourceId, TableBinding binding,
                                   Set<String> declaredTables, Set<String> declaredColumns,
                                   Map<String, String> qualifiersOut) {
        if (binding.getJoinTables() == null || binding.getJoinTables().isEmpty()) {
            return "";
        }
        StringBuilder joins = new StringBuilder();
        for (JoinTableBinding join : binding.getJoinTables()) {
            if (join == null || StrUtil.isBlank(join.getTable())
                    || join.getOn() == null || join.getOn().isEmpty()) {
                continue;
            }
            String joinTable = guard.validateTable(dataSourceId, join.getTable(), declaredTables, false);
            // 只允许 LEFT / INNER，不接受用户随意拼
            String joinType = resolveJoinType(join.getJoinType());
            String alias = StrUtil.isNotBlank(join.getAlias())
                    ? guard.validateAlias(join.getAlias()) : null;

            joins.append(" ").append(joinType).append(" JOIN ").append(guard.quote(joinTable));
            if (alias != null) {
                joins.append(" AS ").append(guard.quote(alias));
            }
            qualifiersOut.put(joinTable.toLowerCase(),
                    guard.quote(alias != null ? alias : joinTable) + ".");

            List<String> onClauses = new ArrayList<>();
            for (JoinTableBinding.JoinCondition on : join.getOn()) {
                if (on == null || StrUtil.isBlank(on.getLeft()) || StrUtil.isBlank(on.getRight())) {
                    continue;
                }
                // ON 两侧写法是 "表名或别名.列名"，两段都要校验
                onClauses.add(qualifyOnSide(dataSourceId, on.getLeft(), binding, declaredColumns)
                        + " = " + qualifyOnSide(dataSourceId, on.getRight(), binding, declaredColumns));
            }
            if (onClauses.isEmpty()) {
                // ON 条件全无效时不能生成裸 JOIN，那会变成笛卡尔积
                throw exception(FORM_DESIGN_IDENTIFIER_INVALID, join.getTable());
            }
            joins.append(" ON ").append(String.join(" AND ", onClauses));
        }
        return joins.toString();
    }

    /**
     * 只允许 LEFT / INNER 两种 JOIN
     *
     * RIGHT JOIN 在「主表为准」的列表语义下会丢主表行，不开放。
     */
    private String resolveJoinType(String joinType) {
        if (StrUtil.isBlank(joinType)) {
            return "LEFT";
        }
        String upper = joinType.trim().toUpperCase();
        if ("LEFT".equals(upper) || "INNER".equals(upper)) {
            return upper;
        }
        throw exception(FORM_DESIGN_IDENTIFIER_INVALID, joinType);
    }

    /**
     * 校验并拼装 ON 条件的一侧
     *
     * 输入形如 "biz_customer.owner_id" 或 "u.id"。
     * 限定符可能是表名也可能是别名：表名走白名单校验，别名走正则校验
     * （别名是配置里新造的，元数据里不存在）。
     */
    private String qualifyOnSide(Long dataSourceId, String side, TableBinding binding,
                                 Set<String> declaredColumns) {
        int idx = side.lastIndexOf('.');
        if (idx < 0) {
            // 没写限定符，默认按主表解析
            String col = guard.validateColumn(dataSourceId, binding.getMainTable(), side, declaredColumns);
            return guard.quote(binding.getMainTable()) + "." + guard.quote(col);
        }
        String qualifier = side.substring(0, idx);
        String column = side.substring(idx + 1);

        // 限定符是已声明的表名 → 可以用元数据核实列归属
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        if (declaredTables.contains(qualifier)) {
            String table = guard.validateTable(dataSourceId, qualifier, declaredTables, false);
            String col = guard.validateColumn(dataSourceId, table, column, declaredColumns);
            return guard.quote(table) + "." + guard.quote(col);
        }
        // 限定符是别名 → 只能正则校验；列名仍要在白名单里
        String alias = guard.validateAlias(qualifier);
        if (!declaredColumns.contains(column)) {
            throw exception(FORM_DESIGN_IDENTIFIER_INVALID, column);
        }
        return guard.quote(alias) + "." + guard.quote(guard.validateAlias(column));
    }

    /**
     * 构建分页配套的 COUNT 语句
     *
     * JOIN 必须与 buildPage 一致 —— INNER JOIN 会过滤掉无匹配的主表行，
     * COUNT 不带 JOIN 会导致总数比列表实际条数多，分页器出现空白页。
     */
    public SqlWithParams buildCount(Long dataSourceId, TableBinding binding,
                                    List<QueryCondition> conditions) {
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        Set<String> declaredColumns = support.buildDeclaredColumns(binding);

        String tableName = guard.validateTable(dataSourceId, binding.getMainTable(), declaredTables, false);

        Map<String, String> qualifiers = new LinkedHashMap<>();
        String joinPart = buildJoinClause(dataSourceId, binding, declaredTables, declaredColumns, qualifiers);
        boolean hasJoin = StrUtil.isNotBlank(joinPart);
        String mainPrefix = hasJoin ? guard.quote(tableName) + "." : "";
        qualifiers.put(tableName.toLowerCase(), mainPrefix);

        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        support.appendLogicDeleteWhere(binding, dataSourceId, tableName, declaredColumns,
                whereClauses, params, mainPrefix);
        support.appendConditions(dataSourceId, tableName, binding, declaredColumns, conditions,
                whereClauses, params, mainPrefix);
        // 租户隔离
        support.appendTenantWhereWithPrefix(dataSourceId, tableName, declaredColumns,
                whereClauses, params, mainPrefix);
        // 行级权限。⚠️ 必须与 buildPage 用完全相同的条件（含相同的 qualifiers）——
        // COUNT 不过滤而列表过滤的话，总数会大于实际可见条数，
        // 表现是「分页器显示 100 条，翻到第 5 页却是空的」
        rowFilter.append(rowFilter.resolve(tableName, mainPrefix, qualifiers), whereClauses, params);

        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ");
        sql.append(guard.quote(tableName));
        if (hasJoin) {
            sql.append(joinPart);
        }
        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }
        return new SqlWithParams(sql.toString(), params);
    }


    /**
     * 构建某字段的去重值查询
     *
     * 供列表页的左侧树筛选用：树节点是该列的全部去重值。
     * 之所以不复用 buildPage 再前端去重：分页只能拿到当前页，
     * 第二页才出现的值在树里就看不到了。
     *
     * @param field 前端传来的 form-create 字段名，必须能在 fieldMapping 里解析出物理列
     * @param limit 返回值数量上限，防止对高基数列（如姓名）查出几十万行
     */
    public SqlWithParams buildDistinctValues(Long dataSourceId, TableBinding binding,
                                             String field, int limit) {
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        Set<String> declaredColumns = support.buildDeclaredColumns(binding);

        String tableName = guard.validateTable(dataSourceId, binding.getMainTable(), declaredTables, false);

        // 字段必须在配置里声明过 —— 不接受前端传任意列名
        String column = support.resolveColumn(binding, field);
        if (column == null) {
            throw exception(FORM_DESIGN_IDENTIFIER_INVALID, field);
        }
        String col = guard.validateColumn(dataSourceId, tableName, column, declaredColumns);

        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        support.appendLogicDeleteWhere(binding, dataSourceId, tableName, declaredColumns, whereClauses, params);
        support.appendTenantWhere(dataSourceId, tableName, declaredColumns, whereClauses, params);
        // NULL 不作为树节点，过滤掉
        whereClauses.add(guard.quote(col) + " IS NOT NULL");

        StringBuilder sql = new StringBuilder("SELECT DISTINCT ");
        sql.append(guard.quote(col))
           .append(" FROM ").append(guard.quote(tableName))
           .append(" WHERE ").append(String.join(" AND ", whereClauses))
           .append(" ORDER BY ").append(guard.quote(col))
           .append(" LIMIT ?");
        params.add(limit);

        return new SqlWithParams(sql.toString(), params);
    }


    // ==================== 副表与子表：委托给专门的构建器 ====================
    //
    // 保留这些入口而不是让调用方直接用子构建器：对外 API 一个没变，
    // 调用方（FormDesignRuntimeServiceImpl 与既有测试）一行都不用改。

    /**
     * 构建子表明细查询语句
     *
     * @see SubTableSqlBuilder
     */
    public SqlWithParams buildSubTableSelect(Long dataSourceId, String subTableName,
                                             String foreignKey, Set<String> subColumns,
                                             Object fkValue, LogicDeleteConfig logicDelete) {
        return subTableSqlBuilder.buildSubTableSelect(dataSourceId, subTableName, foreignKey,
                subColumns, fkValue, logicDelete);
    }

    /**
     * 构建子表明细查询语句（不过滤逻辑删除）
     */
    public SqlWithParams buildSubTableSelect(Long dataSourceId, String subTableName,
                                             String foreignKey, Set<String> subColumns,
                                             Object fkValue) {
        return subTableSqlBuilder.buildSubTableSelect(dataSourceId, subTableName, foreignKey,
                subColumns, fkValue);
    }

    /**
     * 构建子表明细查询语句（按子表配置）
     */
    public SqlWithParams buildSubTableSelect(Long dataSourceId, SubTableBinding subTable,
                                             Set<String> subColumns, Object fkValue) {
        return subTableSqlBuilder.buildSubTableSelect(dataSourceId, subTable, subColumns, fkValue);
    }

    /**
     * 批量查询多个主键对应的子表明细
     */
    public SqlWithParams buildSubTableSelectBatch(Long dataSourceId, SubTableBinding subTable,
                                                  Set<String> subColumns, Collection<?> fkValues) {
        return subTableSqlBuilder.buildSubTableSelectBatch(dataSourceId, subTable, subColumns, fkValues);
    }

    /**
     * 构建副表查询语句
     *
     * @see SideTableSqlBuilder
     */
    public SqlWithParams buildSideTableSelect(Long dataSourceId, TableBinding binding,
                                              SideTableBinding side, Object fkValue) {
        return sideTableSqlBuilder.buildSideTableSelect(dataSourceId, binding, side, fkValue);
    }

    /**
     * 构建副表 INSERT 语句
     */
    public SqlWithParams buildSideTableInsert(Long dataSourceId, TableBinding binding,
                                              SideTableBinding side, Map<String, Object> data,
                                              Object fkValue) {
        return sideTableSqlBuilder.buildSideTableInsert(dataSourceId, binding, side, data, fkValue);
    }

    /**
     * 构建副表 INSERT 语句（指定副表主键）
     */
    public SqlWithParams buildSideTableInsert(Long dataSourceId, TableBinding binding,
                                              SideTableBinding side, Map<String, Object> data,
                                              Object fkValue, Object sidePkValue) {
        return sideTableSqlBuilder.buildSideTableInsert(dataSourceId, binding, side, data,
                fkValue, sidePkValue);
    }

    /**
     * 构建副表 UPDATE 语句
     */
    public SqlWithParams buildSideTableUpdate(Long dataSourceId, TableBinding binding,
                                              SideTableBinding side, Map<String, Object> data,
                                              Object fkValue) {
        return sideTableSqlBuilder.buildSideTableUpdate(dataSourceId, binding, side, data, fkValue);
    }

    /**
     * 构建副表 DELETE 语句
     */
    public SqlWithParams buildSideTableDelete(Long dataSourceId, TableBinding binding,
                                              SideTableBinding side, Object fkValue) {
        return sideTableSqlBuilder.buildSideTableDelete(dataSourceId, binding, side, fkValue);
    }

    /**
     * 批量查询多个主键对应的副表数据
     */
    public SqlWithParams buildSideTableSelectBatch(Long dataSourceId, TableBinding binding,
                                                   SideTableBinding side, Collection<?> fkValues) {
        return sideTableSqlBuilder.buildSideTableSelectBatch(dataSourceId, binding, side, fkValues);
    }

    /**
     * 查询条件
     */
    @Data
    public static class QueryCondition {
        /**
         * form-create 的字段名。必须能在 fieldMapping 里解析出物理列
         */
        private String field;
        /**
         * 操作符代码，见 {@link OperatorEnum}
         */
        private String operator;
        private Object value;
        /**
         * BETWEEN 的第二个值
         */
        private Object value2;
        /**
         * 该字段落库时的多值形态，为 null 表示按标量处理
         *
         * <p>🔴 只有【用户从查询区发来的条件】才该被打上它。
         *
         * <p>服务内部另有三处也构造 EQ 条件 ——
         * {@code validateBusinessKey}、{@code countByFieldValue}（唯一性校验）、
         * {@code FormImportServiceImpl#findExistingRow}（导入判重）。
         * 它们传进去的值<b>已经过 serialize</b>，语义就是「与库里那一整串逐字相等」，
         * 正是判重需要的语义。给它们打上形态标记，判据会从「整串相等」松成
         * 「交集非空」，于是两条勾了部分相同选项的记录会被判成不重复 —— 静默放过脏数据。
         *
         * <p>默认 null 就是这道保护：不打标记的路径行为与本次改动之前逐字节一致。
         */
        private MultiValueShape storedShape;
    }

    /**
     * SQL 与参数的封装
     */
    @Data
    public static class SqlWithParams {
        private final String sql;
        private final List<Object> params;

        public SqlWithParams(String sql, List<Object> params) {
            this.sql = sql;
            this.params = params != null ? params : Collections.emptyList();
        }
    }

}
