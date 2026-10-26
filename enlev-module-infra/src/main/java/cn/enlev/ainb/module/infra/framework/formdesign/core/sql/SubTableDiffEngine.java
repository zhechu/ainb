package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import lombok.Data;

import java.util.*;

/**
 * 子表明细的逐行 diff 引擎
 *
 * 采用【逐行 diff】而非全量替换（删光再插）：按主键比对新旧明细，
 * 分别产出「新增 / 修改 / 删除」三组，从而保持未变动明细行的主键不变。
 *
 * 为什么不做全量替换：很多业务会关联明细表的 id（外部引用、附件挂载、
 * 流程节点指向某一行等）。删光再插会让 id 全部漂移，下游引用直接断掉。
 * 代价是实现复杂一些，但这个正确性是必须的。
 *
 * 判定规则：
 * - 提交行【无主键】 → 新增
 * - 提交行【有主键】且在旧集合中存在 → 修改
 * - 提交行【有主键】但旧集合中不存在 → 视为新增（容错：前端可能传了脏 id）
 * - 旧集合中存在但提交里没出现的主键 → 删除
 *
 * @author 走进新时代
 */
public class SubTableDiffEngine {

    /**
     * 计算 diff
     *
     * @param subTable    子表配置
     * @param submitted   提交的明细行（form-create 子表控件的值）
     * @param existingPks 数据库中现存的明细主键集合
     * @return diff 结果
     */
    public static DiffResult diff(SubTableBinding subTable,
                                  List<Map<String, Object>> submitted,
                                  Set<Object> existingPks) {
        DiffResult result = new DiffResult();
        String pkField = StrUtil.isNotBlank(subTable.getPk()) ? subTable.getPk() : "id";

        Set<Object> submittedPks = new HashSet<>();

        if (submitted != null) {
            for (Map<String, Object> row : submitted) {
                if (row == null || row.isEmpty()) {
                    continue;
                }
                Object pkValue = row.get(pkField);
                if (isBlank(pkValue)) {
                    // 无主键 → 新增
                    result.getToInsert().add(row);
                    continue;
                }
                Object normalizedPk = normalize(pkValue);
                if (existingPks != null && containsNormalized(existingPks, normalizedPk)) {
                    // 有主键且库里存在 → 修改
                    result.getToUpdate().add(row);
                    submittedPks.add(normalizedPk);
                } else {
                    // 有主键但库里没有：前端可能传了脏 id，按新增处理而非报错
                    result.getToInsert().add(row);
                }
            }
        }

        // 库里存在但提交里没出现的 → 删除
        if (existingPks != null) {
            for (Object existingPk : existingPks) {
                Object normalized = normalize(existingPk);
                if (!containsNormalized(submittedPks, normalized)) {
                    result.getToDeletePks().add(existingPk);
                }
            }
        }

        return result;
    }

    /**
     * 主键归一化为字符串比较
     *
     * 必要性：JSON 反序列化后同一个主键可能是 Integer、Long 或 String
     * （前端传 JSON 时数字类型不稳定），直接用 equals 会漏判导致误删。
     */
    private static Object normalize(Object pk) {
        return pk == null ? null : pk.toString();
    }

    private static boolean containsNormalized(Set<Object> pks, Object normalizedPk) {
        if (pks == null || normalizedPk == null) {
            return false;
        }
        for (Object pk : pks) {
            if (Objects.equals(normalize(pk), normalizedPk)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(Object value) {
        return value == null
                || (value instanceof CharSequence && StrUtil.isBlank((CharSequence) value));
    }

    /**
     * diff 结果
     */
    @Data
    public static class DiffResult {
        /**
         * 需要插入的明细行
         */
        private final List<Map<String, Object>> toInsert = new ArrayList<>();
        /**
         * 需要更新的明细行（含主键）
         */
        private final List<Map<String, Object>> toUpdate = new ArrayList<>();
        /**
         * 需要删除的明细主键
         */
        private final List<Object> toDeletePks = new ArrayList<>();

        public boolean isEmpty() {
            return toInsert.isEmpty() && toUpdate.isEmpty() && toDeletePks.isEmpty();
        }
    }

}
