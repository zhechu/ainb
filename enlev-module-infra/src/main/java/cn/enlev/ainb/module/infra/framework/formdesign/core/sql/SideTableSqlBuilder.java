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
 * 副表（1:1，参与写入）的 SQL 构建
 *
 * 副表与主表一对一，随主表一起增删改。字段名用 FormFieldNaming 的
 * 「表名_列名」形式与主表字段区分开。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class SideTableSqlBuilder {

    @Resource
    private SqlIdentifierGuard guard;
    @Resource
    private SqlBuilderSupport support;


    /**
     * 副表的 SELECT 列与输出别名
     *
     * <h3>为什么别名必须是表单字段名</h3>
     * 写入侧（{@link #buildSideTableInsert} / {@link #buildSideTableUpdate}）取值用的是
     * {@code data.get(fieldMapping 的键)}，也就是表单字段名。读取侧若按物理表名列名派生出
     * {@code yd_表_yd_列} 当别名，两侧命名就不对称了 ——
     * 只有字段名恰好等于 {@code yd_表_yd_列} 的表单（即用「一键生成」建出来的）才对得上；
     * 用户手工拖控件命名成 taxNo 时，数据写得进去、却永远读不出来，
     * 表单详情和列表里副表字段全是空的，而数据库里明明有值。
     *
     * 所以映射列一律 AS 成它的表单字段名。表单字段在 form-create 里本就唯一，
     * 不会与主表字段撞名，反而比派生前缀更安全。
     *
     * <h3>主键与外键为什么仍用前缀名</h3>
     * 它们通常不在 fieldMapping 里（外键由后端填，不进表单），是纯内部用途：
     * 批量回查靠外键值把行分组挂回主记录。用带前缀的名字避免与主表列撞名。
     * 若用户把它们也映射进了表单，则同一列输出两个别名，两边都能取到。
     *
     * @return 有序的「物理列 → 输出别名」对，允许同一列出现多次（别名不同）
     */
    private List<Map.Entry<String, String>> sideTableSelectAliases(String tableName,
                                                                   SideTableBinding side) {
        List<Map.Entry<String, String>> aliases = new ArrayList<>();
        Set<String> emitted = new HashSet<>();

        // 主键、外键：内部键，带表名前缀
        for (String internal : List.of(
                StrUtil.nullToEmpty(side.getPk()), StrUtil.nullToEmpty(side.getForeignKey()))) {
            if (StrUtil.isNotBlank(internal) && emitted.add(internal + "#internal")) {
                aliases.add(Map.entry(internal, FormFieldNaming.sideField(tableName, internal)));
            }
        }
        // 映射列：别名就是表单字段名
        if (side.getFieldMapping() != null) {
            for (Map.Entry<String, FieldMapping> entry : side.getFieldMapping().entrySet()) {
                FieldMapping mapping = entry.getValue();
                if (mapping == null || StrUtil.isBlank(mapping.getColumn())
                        || StrUtil.isBlank(entry.getKey())) {
                    continue;
                }
                if (emitted.add(entry.getKey() + "#field")) {
                    aliases.add(Map.entry(mapping.getColumn(), entry.getKey()));
                }
            }
        }
        return aliases;
    }

    /**
     * 把副表的 SELECT 列拼成 {@code `列` AS `别名`} 的形式
     */
    private String quoteSideColumns(Long dataSourceId, String tableName, SideTableBinding side,
                                    Set<String> declaredColumns) {
        List<String> quoted = new ArrayList<>();
        for (Map.Entry<String, String> alias : sideTableSelectAliases(tableName, side)) {
            if (!declaredColumns.contains(alias.getKey())) {
                continue;
            }
            String col = guard.validateColumn(dataSourceId, tableName, alias.getKey(), declaredColumns);
            // 别名是配置里来的字符串，同样要过标识符校验，否则是注入点
            quoted.add(guard.quote(col) + " AS " + guard.quote(guard.validateAlias(alias.getValue())));
        }
        return String.join(", ", quoted);
    }

    /**
     * 构建副表查询语句
     *
     * 副表是 1:1，理论上只有一行。但数据库层面保证不了 ——
     * 若外键上没有唯一索引，同一个外键值可能对应多行。
     * 这里不加 LIMIT 1，由调用方决定是取第一行还是报警告（运行时会记日志）。
     */
    public SqlWithParams buildSideTableSelect(Long dataSourceId, TableBinding binding,
                                             SideTableBinding side, Object fkValue) {
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        Set<String> declaredColumns = support.buildDeclaredColumns(binding);

        String tableName = guard.validateTable(dataSourceId, side.getTable(), declaredTables, false);
        String fkCol = guard.validateColumn(dataSourceId, tableName, side.getForeignKey(), declaredColumns);

        String selectPart = quoteSideColumns(dataSourceId, tableName, side, declaredColumns);
        if (selectPart.isEmpty()) {
            // 没有任何可查的列，退化为查外键，避免生成 SELECT FROM 这种非法 SQL
            selectPart = guard.quote(fkCol) + " AS "
                    + guard.quote(FormFieldNaming.sideField(tableName, fkCol));
        }

        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        whereClauses.add(guard.quote(fkCol) + " = ?");
        params.add(fkValue);
        support.appendLogicDeleteWhereFor(side.getLogicDelete(), dataSourceId, tableName,
                declaredColumns, whereClauses, params);
        support.appendTenantWhere(dataSourceId, tableName, declaredColumns, whereClauses, params);

        String sql = String.format("SELECT %s FROM %s WHERE %s",
                selectPart, guard.quote(tableName),
                String.join(" AND ", whereClauses));
        return new SqlWithParams(sql, params);
    }

    /**
     * 构建副表 INSERT 语句
     *
     * @param fkValue 主表主键值，写入副表外键列
     */
    public SqlWithParams buildSideTableInsert(Long dataSourceId, TableBinding binding,
                                              SideTableBinding side, Map<String, Object> data,
                                              Object fkValue) {
        return buildSideTableInsert(dataSourceId, binding, side, data, fkValue, null);
    }

    /**
     * 构建副表 INSERT 语句
     *
     * @param fkValue     主表主键值，写入副表外键列
     * @param sidePkValue 副表自身的主键值。策略为自增时传 null，交给数据库生成
     */
    public SqlWithParams buildSideTableInsert(Long dataSourceId, TableBinding binding,
                                              SideTableBinding side, Map<String, Object> data,
                                              Object fkValue, Object sidePkValue) {
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        Set<String> declaredColumns = support.buildDeclaredColumns(binding);

        String tableName = guard.validateTable(dataSourceId, side.getTable(), declaredTables, true);

        List<String> columns = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        // 副表自身主键。仅在应用层生成主键时写入 —— 自增策略下写 null 会直接报错
        if (sidePkValue != null && StrUtil.isNotBlank(side.getPk())) {
            String pkCol = guard.validateColumn(dataSourceId, tableName, side.getPk(), declaredColumns);
            columns.add(guard.quote(pkCol));
            params.add(sidePkValue);
        }

        // 外键列：指回主表
        String fkCol = guard.validateColumn(dataSourceId, tableName, side.getForeignKey(), declaredColumns);
        columns.add(guard.quote(fkCol));
        params.add(fkValue);

        // 字段映射
        support.appendMappingColumns(dataSourceId, tableName, side.getFieldMapping(), data,
                declaredColumns, columns, params);

        // 逻辑删除列填「未删除」
        LogicDeleteConfig ld = side.getLogicDelete();
        if (support.isLogicDeleteOn(ld)) {
            String col = guard.validateColumn(dataSourceId, tableName, ld.getColumn(), declaredColumns);
            columns.add(guard.quote(col));
            params.add(support.normalValue(ld));
        }

        // 租户隔离：与主表 INSERT 保持一致。
        // ⚠️ 漏掉这段的后果非常隐蔽：副表行会以 tenant_id = NULL 落库，
        // 而所有副表查询都带 `tenant_id = ?` 过滤，于是数据写得进去、却永远读不出来 ——
        // 表单详情与列表里副表字段全是空的，可数据库里明明有值。
        // 直接查元数据而不依赖白名单，因为 tenant_id 通常不在 fieldMapping 里
        if (!TenantContextHolder.isIgnore()
                && guard.getRealColumns(dataSourceId, tableName).contains("tenant_id")) {
            columns.add(guard.quote("tenant_id"));
            params.add(TenantContextHolder.getRequiredTenantId());
        }

        String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
        String sql = String.format("INSERT INTO %s (%s) VALUES (%s)",
                guard.quote(tableName), String.join(", ", columns), placeholders);
        return new SqlWithParams(sql, params);
    }

    /**
     * 构建副表 UPDATE 语句
     *
     * 按外键定位，而不是按副表主键 —— 调用方只持有主表主键。
     * 返回 null 表示没有任何可更新的列（副表没配字段映射），调用方应跳过执行。
     */
    public SqlWithParams buildSideTableUpdate(Long dataSourceId, TableBinding binding,
                                              SideTableBinding side, Map<String, Object> data,
                                              Object fkValue) {
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        Set<String> declaredColumns = support.buildDeclaredColumns(binding);

        String tableName = guard.validateTable(dataSourceId, side.getTable(), declaredTables, true);
        String fkCol = guard.validateColumn(dataSourceId, tableName, side.getForeignKey(), declaredColumns);

        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (side.getFieldMapping() != null) {
            for (Map.Entry<String, FieldMapping> entry : side.getFieldMapping().entrySet()) {
                FieldMapping mapping = entry.getValue();
                if (mapping == null || StrUtil.isBlank(mapping.getColumn())) {
                    continue;
                }
                // 局部更新，理由同主表 UPDATE
                if (data == null || !data.containsKey(entry.getKey())) {
                    continue;
                }
                support.assertMappable(mapping.getColumn());
                String col = guard.validateColumn(dataSourceId, tableName, mapping.getColumn(), declaredColumns);
                setClauses.add(guard.quote(col) + " = ?");
                params.add(data.get(entry.getKey()));
            }
        }

        if (setClauses.isEmpty()) {
            return null;
        }

        List<String> whereClauses = new ArrayList<>();
        whereClauses.add(guard.quote(fkCol) + " = ?");
        params.add(fkValue);
        support.appendLogicDeleteWhereFor(side.getLogicDelete(), dataSourceId, tableName,
                declaredColumns, whereClauses, params);

        String sql = String.format("UPDATE %s SET %s WHERE %s",
                guard.quote(tableName), String.join(", ", setClauses),
                String.join(" AND ", whereClauses));
        return new SqlWithParams(sql, params);
    }

    /**
     * 构建副表 DELETE 语句
     *
     * 配了逻辑删除时生成 UPDATE 标记语句，否则物理 DELETE。按外键定位。
     */
    public SqlWithParams buildSideTableDelete(Long dataSourceId, TableBinding binding,
                                              SideTableBinding side, Object fkValue) {
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        Set<String> declaredColumns = support.buildDeclaredColumns(binding);

        String tableName = guard.validateTable(dataSourceId, side.getTable(), declaredTables, true);
        String fkCol = guard.validateColumn(dataSourceId, tableName, side.getForeignKey(), declaredColumns);

        LogicDeleteConfig ld = side.getLogicDelete();
        List<Object> params = new ArrayList<>();

        if (support.isLogicDeleteOn(ld)) {
            String ldCol = guard.validateColumn(dataSourceId, tableName, ld.getColumn(), declaredColumns);
            params.add(support.deletedValue(ld));
            params.add(fkValue);
            params.add(support.normalValue(ld));
            String sql = String.format("UPDATE %s SET %s = ? WHERE %s = ? AND %s = ?",
                    guard.quote(tableName), guard.quote(ldCol),
                    guard.quote(fkCol), guard.quote(ldCol));
            return new SqlWithParams(sql, params);
        }

        params.add(fkValue);
        String sql = String.format("DELETE FROM %s WHERE %s = ?",
                guard.quote(tableName), guard.quote(fkCol));
        return new SqlWithParams(sql, params);
    }

    /**
     * 构建副表的批量查询语句
     *
     * 供列表分页用：拿到当页主表主键后，用 IN 一次查出所有副表行，再按外键值拼回各行。
     * 之所以不把副表加进 buildPage 的 JOIN：副表的 1:1 没有数据库层面保证，
     * 外键上没唯一索引时 JOIN 会让主表行数翻倍，分页条数就错了。
     *
     * @param fkValues 主表主键值集合，不能为空
     */
    public SqlWithParams buildSideTableSelectBatch(Long dataSourceId, TableBinding binding,
                                                   SideTableBinding side, Collection<?> fkValues) {
        if (fkValues == null || fkValues.isEmpty()) {
            // 空 IN 会生成 IN () 这种非法 SQL，调用方应先判空
            throw exception(FORM_DESIGN_RUNTIME_EXECUTE_FAIL, "副表批量查询的主键集合不能为空");
        }
        Set<String> declaredTables = support.buildDeclaredTables(binding);
        Set<String> declaredColumns = support.buildDeclaredColumns(binding);

        String tableName = guard.validateTable(dataSourceId, side.getTable(), declaredTables, false);
        String fkCol = guard.validateColumn(dataSourceId, tableName, side.getForeignKey(), declaredColumns);

        // 外键列必须查出来，否则无法把行拼回对应的主表记录。
        // sideTableSelectAliases 保证外键一定带内部别名输出，这里不用再补
        String selectPart = quoteSideColumns(dataSourceId, tableName, side, declaredColumns);

        List<Object> params = new ArrayList<>(fkValues);
        String placeholders = String.join(", ", Collections.nCopies(fkValues.size(), "?"));
        List<String> whereClauses = new ArrayList<>();
        whereClauses.add(guard.quote(fkCol) + " IN (" + placeholders + ")");
        support.appendLogicDeleteWhereFor(side.getLogicDelete(), dataSourceId, tableName,
                declaredColumns, whereClauses, params);
        support.appendTenantWhere(dataSourceId, tableName, declaredColumns, whereClauses, params);

        String sql = String.format("SELECT %s FROM %s WHERE %s",
                selectPart, guard.quote(tableName),
                String.join(" AND ", whereClauses));
        return new SqlWithParams(sql, params);
    }

    /**
     * 把字段映射的列与值追加进 INSERT 的列表
     */
    /**
     * 由后端按登录上下文维护的列，禁止被表单字段映射
     *
     * tenant_id 的值只能来自 {@link TenantContextHolder}。一旦允许某个表单字段映射到它，
     * UPDATE 就会生成 {@code SET tenant_id = ?} 并绑上前端提交的值 ——
     * 等于让调用方自由决定记录属于哪个租户，可以把数据搬走，也可以把别人的数据拉进来。
     *
     * <p>为什么必须在这一层拦，而不是只靠前端的保留列黑名单或发布校验：
     * <ul>
     *   <li>前端黑名单只是界面约束。table_binding 是一段 JSON，调用方能直接调设计接口提交任意内容</li>
     *   <li>表单发布后再调 update 改设计【不会】重新走发布校验，而运行时读的是实时的
     *       table_binding，改完立即生效 —— 只在发布时拦会留下这条绕过路径</li>
     * </ul>
     * 所以真正拼 SQL 的地方才是唯一可靠的边界。
     */
}
