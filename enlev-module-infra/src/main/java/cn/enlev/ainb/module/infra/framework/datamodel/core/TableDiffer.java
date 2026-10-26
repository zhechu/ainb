package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableIndexModel;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 表结构差异比对器
 *
 * 把「目标结构」与「当前真实结构」对比，算出需要执行哪些改动。
 * 这是改表的核心：只对真正变化的字段生成 DDL，避免无谓的 ALTER
 * （每次 ALTER 在大表上都可能锁表很久）。
 *
 * 字段的对应关系判定：
 * - 目标字段带 oldColumnName → 按 oldColumnName 匹配现有字段（这是改名）
 * - 否则按 columnName 匹配
 * - 现有字段在目标里找不到对应 → 判定为删除
 *
 * @author 走进新时代
 */
public class TableDiffer {

    /**
     * 比对结果
     */
    @Getter
    public static class Diff {

        private final List<TableColumnModel> addColumns = new ArrayList<>();
        private final List<TableColumnModel> modifyColumns = new ArrayList<>();
        private final List<String> dropColumns = new ArrayList<>();

        public boolean isEmpty() {
            return addColumns.isEmpty() && modifyColumns.isEmpty() && dropColumns.isEmpty();
        }

        /**
         * 是否包含有损操作（删字段）
         *
         * 有损操作需要用户显式确认风险，见 DataModelService 的 riskConfirmed 处理
         */
        public boolean hasLossyChange() {
            return !dropColumns.isEmpty();
        }

    }

    /**
     * 索引比对结果
     */
    @Getter
    public static class IndexDiff {

        private final List<TableIndexModel> addIndexes = new ArrayList<>();
        private final List<String> dropIndexNames = new ArrayList<>();

        public boolean isEmpty() {
            return addIndexes.isEmpty() && dropIndexNames.isEmpty();
        }

    }

    private TableDiffer() {
    }

    /**
     * 比对表结构
     *
     * @param targetColumns  目标字段列表（用户提交的）
     * @param currentColumns 当前真实字段列表（读 information_schema 得到）
     * @return 差异
     */
    public static Diff diff(List<TableColumnModel> targetColumns,
                            List<TableColumnModel> currentColumns) {
        Diff diff = new Diff();
        // 现有字段：key 用小写，因为 MySQL 字段名大小写不敏感
        Map<String, TableColumnModel> currentMap = new LinkedHashMap<>();
        for (TableColumnModel column : currentColumns) {
            currentMap.put(column.getColumnName().toLowerCase(), column);
        }

        // 逐个处理目标字段，命中的从 currentMap 移除，剩下的就是要删的
        for (TableColumnModel target : targetColumns) {
            String matchKey = StrUtil.isNotBlank(target.getOldColumnName())
                    ? target.getOldColumnName().toLowerCase()
                    : target.getColumnName().toLowerCase();
            TableColumnModel current = currentMap.remove(matchKey);
            if (current == null) {
                // 现有结构里没有 → 新增
                diff.addColumns.add(target);
                continue;
            }
            if (isChanged(target, current)) {
                diff.modifyColumns.add(target);
            }
        }
        // currentMap 里剩下的都是目标结构中不存在的 → 删除
        currentMap.values().forEach(column -> diff.dropColumns.add(column.getColumnName()));
        return diff;
    }

    /**
     * 判断字段是否发生变化
     *
     * 逐项比较而非直接 equals，因为读出来的模型带有 oldColumnName 等无关字段，
     * 而且默认值、长度在两侧的 null 语义需要归一化处理。
     */
    private static boolean isChanged(TableColumnModel target, TableColumnModel current) {
        // 改名
        if (!target.getColumnName().equalsIgnoreCase(current.getColumnName())) {
            return true;
        }
        // 类型
        if (!StrUtil.equalsIgnoreCase(target.getColumnType(), current.getColumnType())) {
            return true;
        }
        // 长度与精度：null 与 0 视作等价，避免前端不传长度时被判定为变化
        if (!Objects.equals(normalizeNumber(target.getLength()), normalizeNumber(current.getLength()))) {
            return true;
        }
        if (!Objects.equals(normalizeNumber(target.getScale()), normalizeNumber(current.getScale()))) {
            return true;
        }
        // 可空、主键、自增
        if (target.isNullable() != current.isNullable()) {
            return true;
        }
        if (target.isPrimaryKey() != current.isPrimaryKey()) {
            return true;
        }
        if (target.isAutoIncrement() != current.isAutoIncrement()) {
            return true;
        }
        // ON UPDATE CURRENT_TIMESTAMP：注入器给 update_time 带上它，reader 也读得回来，
        // 两侧口径必须一致 —— 否则每次改表都会把 update_time 判成「变了」，白生成一条 MODIFY
        if (target.isOnUpdateCurrentTimestamp() != current.isOnUpdateCurrentTimestamp()) {
            return true;
        }
        // 注释
        if (!StrUtil.equals(StrUtil.emptyIfNull(target.getColumnComment()),
                StrUtil.emptyIfNull(current.getColumnComment()))) {
            return true;
        }
        // 默认值
        return !StrUtil.equals(StrUtil.emptyIfNull(target.getDefaultValue()),
                StrUtil.emptyIfNull(current.getDefaultValue()));
    }

    /**
     * 长度归一化：null 与 0 都视作「未指定」
     */
    private static Integer normalizeNumber(Integer value) {
        return value == null || value == 0 ? null : value;
    }

    /**
     * 比对索引结构
     *
     * 采用与字段一致的「全量同步」语义：目标里有、当前没有 → 新增；
     * 两边同名但结构签名不同 → 先删旧再加新（MySQL 不支持原地改索引）；
     * 当前有、目标没有 → 删除。索引名大小写不敏感地匹配。
     *
     * @param targetIndexes  目标索引列表（用户提交的，可能为 null）
     * @param currentIndexes 当前真实索引列表（读 information_schema 得到，可能为 null）
     * @return 索引差异
     */
    public static IndexDiff diffIndexes(List<TableIndexModel> targetIndexes,
                                        List<TableIndexModel> currentIndexes) {
        IndexDiff diff = new IndexDiff();
        Map<String, TableIndexModel> currentMap = new LinkedHashMap<>();
        for (TableIndexModel index : CollUtil.emptyIfNull(currentIndexes)) {
            currentMap.put(index.getIndexName().toLowerCase(), index);
        }

        for (TableIndexModel target : CollUtil.emptyIfNull(targetIndexes)) {
            TableIndexModel current = currentMap.remove(target.getIndexName().toLowerCase());
            if (current == null) {
                diff.addIndexes.add(target);
            } else if (!Objects.equals(target.signature(), current.signature())) {
                // 同名但列或类型变了：删旧建新
                diff.dropIndexNames.add(current.getIndexName());
                diff.addIndexes.add(target);
            }
        }
        // 剩下的都是目标里不再需要的索引
        currentMap.values().forEach(index -> diff.dropIndexNames.add(index.getIndexName()));
        return diff;
    }

}
