package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.enlev.ainb.module.infra.enums.datamodel.SystemColumnProfileEnum;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 系统字段注入器
 *
 * 建表时自动补齐框架约定的系统字段，让建模产出的表与框架自带表结构一致。
 * 这不只是「省事」，而是**动态 CRUD 的安全前提**：
 *
 * - 没有 tenant_id → {@link DynamicSqlExecutor} 拼不出租户条件 → 跨租户数据泄漏
 * - 没有 deleted   → 删除变成物理删除，不可恢复
 * - 没有审计字段   → 无法追溯谁改了数据
 *
 * 字段顺序刻意设计为「id → 业务字段 → 审计字段 → deleted → tenant_id」，
 * 与框架表的习惯一致，便于人工看表时对照。
 *
 * <p><b>三种策略</b>见 {@link SystemColumnProfileEnum} —— 它把「不要 tenant_id」
 * 从「绕过注入器的偶然行为」变成了显式声明。
 *
 * @author 走进新时代
 */
public class SystemColumnInjector {

    /**
     * 乐观锁版本列
     *
     * ⚠️ 纳入托管是为了让存量样板表（{@code test_demo_*}，15 处 version）能被一致管理 ——
     * 此前注入器<b>不认识它</b>，于是它在改表时被当成普通业务列，漏传就会被删掉。
     *
     * <p>代价要说清楚：一旦纳入，<b>任何表里名为 version 的列都不能再被删除</b>
     * （{@code validateSystemColumnsNotDropped} 会拦）。把 version 当业务字段用的表会受影响，
     * 但在这个框架里 version 本就是乐观锁的保留名，当业务字段用本身就该改。
     */
    public static final String COLUMN_VERSION = "version";

    /**
     * 系统字段名集合，用于判断某字段是否由本类托管
     *
     * 注意这是<b>并集</b>：包含所有 profile 可能注入的列。判断「是否系统列」时不区分 profile ——
     * 因为读一张已存在的表时我们并不知道它当初是按哪个 profile 建的
     * （profile 不落库，见 {@link SystemColumnProfileEnum} 的类注释）。
     */
    public static final Set<String> SYSTEM_COLUMNS = new LinkedHashSet<>(List.of(
            DynamicSqlExecutor.COLUMN_ID,
            DynamicSqlExecutor.COLUMN_CREATOR,
            DynamicSqlExecutor.COLUMN_CREATE_TIME,
            DynamicSqlExecutor.COLUMN_UPDATER,
            DynamicSqlExecutor.COLUMN_UPDATE_TIME,
            COLUMN_VERSION,
            DynamicSqlExecutor.COLUMN_DELETED,
            DynamicSqlExecutor.COLUMN_TENANT_ID
    ));

    private SystemColumnInjector() {
    }

    /**
     * 判断是否为系统字段（大小写不敏感）
     */
    public static boolean isSystemColumn(String columnName) {
        return columnName != null
                && SYSTEM_COLUMNS.stream().anyMatch(item -> item.equalsIgnoreCase(columnName));
    }

    /**
     * 给字段列表补齐系统字段，按默认的多租户策略
     *
     * 保留这个重载是为了让不关心 profile 的调用方（预览、索引校验）行为与改造前完全一致。
     */
    public static List<TableColumnModel> inject(List<TableColumnModel> businessColumns) {
        return inject(businessColumns, SystemColumnProfileEnum.TENANT);
    }

    /**
     * 给字段列表补齐系统字段
     *
     * 用户已定义的同名字段**保持原样不覆盖** —— 导入已有表再改结构时，
     * 原表的 id 可能不是 bigint 自增，强行覆盖会生成错误的 DDL。
     *
     * @param businessColumns 用户定义的字段
     * @param profile         系统列策略，null 按 {@link SystemColumnProfileEnum#TENANT} 处理
     * @return 补齐后的完整字段列表
     */
    public static List<TableColumnModel> inject(List<TableColumnModel> businessColumns,
                                                SystemColumnProfileEnum profile) {
        SystemColumnProfileEnum actual = profile == null ? SystemColumnProfileEnum.TENANT : profile;
        Set<String> existing = new LinkedHashSet<>();
        List<TableColumnModel> business = new ArrayList<>();
        for (TableColumnModel column : businessColumns) {
            existing.add(column.getColumnName().toLowerCase());
            business.add(column);
        }

        List<TableColumnModel> result = new ArrayList<>();
        // 1. 主键放最前
        if (!existing.contains(DynamicSqlExecutor.COLUMN_ID)) {
            result.add(idColumn());
        }
        // 2. 业务字段（含用户自己定义的 id 等系统字段，位置由用户决定）
        result.addAll(business);
        // 3. 审计与框架字段放最后
        if (!existing.contains(DynamicSqlExecutor.COLUMN_CREATOR)) {
            result.add(varcharColumn(DynamicSqlExecutor.COLUMN_CREATOR, "创建者", 64));
        }
        if (!existing.contains(DynamicSqlExecutor.COLUMN_CREATE_TIME)) {
            result.add(createTimeColumn());
        }
        if (!existing.contains(DynamicSqlExecutor.COLUMN_UPDATER)) {
            result.add(varcharColumn(DynamicSqlExecutor.COLUMN_UPDATER, "更新者", 64));
        }
        if (!existing.contains(DynamicSqlExecutor.COLUMN_UPDATE_TIME)) {
            result.add(updateTimeColumn());
        }
        // 3.1 version 只有 LEGACY 策略才注入
        if (actual == SystemColumnProfileEnum.LEGACY && !existing.contains(COLUMN_VERSION)) {
            result.add(new TableColumnModel()
                    .setColumnName(COLUMN_VERSION)
                    .setColumnComment("乐观锁版本")
                    .setColumnType("INT")
                    .setNullable(false)
                    .setPrimaryKey(false)
                    .setDefaultValue("0"));
        }
        if (!existing.contains(DynamicSqlExecutor.COLUMN_DELETED)) {
            result.add(new TableColumnModel()
                    .setColumnName(DynamicSqlExecutor.COLUMN_DELETED)
                    .setColumnComment("是否删除")
                    .setColumnType("BOOLEAN")
                    .setNullable(false)
                    .setPrimaryKey(false)
                    .setDefaultValue("0"));
        }
        // 3.2 tenant_id 只有 TENANT 策略才注入 —— GLOBAL 与 LEGACY 都是显式声明「不要租户隔离」
        if (actual == SystemColumnProfileEnum.TENANT
                && !existing.contains(DynamicSqlExecutor.COLUMN_TENANT_ID)) {
            result.add(new TableColumnModel()
                    .setColumnName(DynamicSqlExecutor.COLUMN_TENANT_ID)
                    .setColumnComment("租户编号")
                    .setColumnType("LONG")
                    .setNullable(false)
                    .setPrimaryKey(false)
                    .setDefaultValue("0"));
        }
        return result;
    }

    private static TableColumnModel idColumn() {
        return new TableColumnModel()
                .setColumnName(DynamicSqlExecutor.COLUMN_ID)
                .setColumnComment("编号")
                .setColumnType("LONG")
                .setNullable(false)
                .setPrimaryKey(true)
                .setAutoIncrement(true);
    }

    /**
     * 审计人列：{@code varchar(n) NULL}
     *
     * ⚠️ 与框架基线表的 {@code varchar(64) DEFAULT ''} 有一处<b>已知且刻意保留</b>的差异：
     * 这里生成不带 {@code DEFAULT} 子句的版本。
     *
     * <p>原因是当前模型无法表达「默认值就是空字符串」—— {@code defaultValue = ""} 在
     * {@link MysqlDdlBuilder#buildColumnDefinition} 里被占用为「没设默认值」的语义
     * （界面上那个输入框的初值就是空串，按字面理解会让每个列都平白多出 {@code DEFAULT ''}）。
     *
     * <p>实际影响很小：动态 CRUD 的 {@code fillCreateFields} 总会填充 creator/updater，
     * 只有绕开应用层的直接 INSERT 才会拿到 {@code NULL} 而不是 {@code ''}。
     * 要彻底对齐得给模型加「空串默认值」的表达位，收益不抵复杂度。
     */
    private static TableColumnModel varcharColumn(String name, String comment, int length) {
        return new TableColumnModel()
                .setColumnName(name)
                .setColumnComment(comment)
                .setColumnType("STRING")
                .setLength(length)
                .setNullable(true)
                .setPrimaryKey(false);
    }

    /**
     * 创建时间列：{@code NOT NULL DEFAULT CURRENT_TIMESTAMP}
     *
     * 改造前生成的是「可空、无默认值」，与本分支样板表的写法不一致（R2）。
     * 给默认值不是锦上添花 —— 动态 CRUD 之外的写入路径（DBA 手工 INSERT、
     * 数据导入脚本）不会去填审计字段，没有默认值时这些行的时间就是 NULL。
     */
    private static TableColumnModel createTimeColumn() {
        return new TableColumnModel()
                .setColumnName(DynamicSqlExecutor.COLUMN_CREATE_TIME)
                .setColumnComment("创建时间")
                .setColumnType("DATETIME")
                .setNullable(false)
                .setPrimaryKey(false)
                .setDefaultValue("CURRENT_TIMESTAMP");
    }

    /**
     * 更新时间列：{@code NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP}
     *
     * {@code ON UPDATE} 让「绕开应用层的直接 UPDATE」也能刷新更新时间。
     */
    private static TableColumnModel updateTimeColumn() {
        return new TableColumnModel()
                .setColumnName(DynamicSqlExecutor.COLUMN_UPDATE_TIME)
                .setColumnComment("更新时间")
                .setColumnType("DATETIME")
                .setNullable(false)
                .setPrimaryKey(false)
                .setDefaultValue("CURRENT_TIMESTAMP")
                .setOnUpdateCurrentTimestamp(true);
    }

}
