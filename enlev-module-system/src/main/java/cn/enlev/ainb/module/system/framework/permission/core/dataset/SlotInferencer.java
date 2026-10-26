package cn.enlev.ainb.module.system.framework.permission.core.dataset;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormResourceDescriptorDTO;
import cn.enlev.ainb.module.system.enums.permission.PermissionSlotEnum;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 语义槽位自动推断
 *
 * 从表单描述里推断 owner / dept / status 三个槽位各自对应哪一列。
 *
 * <h3>推断的两个来源，以及为什么显式声明优先</h3>
 *
 * <ol>
 *   <li><b>显式声明</b>：{@code AutoFillConfig.creatorColumn}（且为 AUTO 模式）——
 *       设计者明说了「这一列存创建人」，这是事实，不是推测</li>
 *   <li><b>按列名猜</b>：在主表物理列里按 {@link PermissionSlotEnum#getCandidates()}
 *       的顺序找第一个命中的</li>
 * </ol>
 *
 * <h3>为什么要看物理列全集，而不是只看表单映射到的列</h3>
 *
 * status、create_dept_id 这类列通常<b>不在</b>表单字段里 —— 它们由后端填、由流程写。
 * 只看字段映射的话，最常用的两个槽位恰恰都推不出来。物理列读不到时（数据源不通）
 * 退化为只看字段映射，推不出来的槽位就不写。
 *
 * <h3>推不出来时写什么：什么都不写</h3>
 *
 * 不写占位、不写空串。条件树求值时找不到槽位映射会跳过该条件并记告警
 * （见 {@code ConditionTreeEvaluator.resolveColumnName}），
 * 而写一个空列名会让它生成一条语法不合法的 SQL —— 那是整张列表查不出来，
 * 比「这条规则没生效」严重得多。
 *
 * <p>本类是纯函数，不碰数据库，便于单测覆盖各种表结构。
 *
 * @author 走进新时代
 */
public class SlotInferencer {

    /**
     * 推断槽位映射
     *
     * @param descriptor 表单描述
     * @return 槽位名 → 列名（小写）。推不出来的槽位不出现在结果里
     */
    public Map<String, String> infer(FormResourceDescriptorDTO descriptor) {
        Map<String, String> result = new LinkedHashMap<>();
        if (descriptor == null) {
            return result;
        }
        Set<String> knownColumns = collectKnownColumns(descriptor);

        // owner：显式声明的创建人列优先，且必须真的存在于已知列里 ——
        // 设计者填错列名时发布校验会拦，但这里再确认一次，成本为零
        String creatorColumn = lower(descriptor.getCreatorColumn());
        if (StrUtil.isNotBlank(creatorColumn) && knownColumns.contains(creatorColumn)) {
            result.put(PermissionSlotEnum.OWNER.getSlot(), creatorColumn);
        }

        // bizKey：表绑定里的主键列是<b>显式声明</b>，优先于按列名猜。
        //
        // ⚠️ 这一条不能省。派生可见性与记录共享都落在 bizKey 上，而在 S7b 之前
        // 这个槽位从来没有被自动登记过 —— 结果是「配了『流程参与者可见』
        // 反而一行都看不到」（条件树求不出列 → 空片段 → 恒假，见 TODO #28）。
        //
        // 这里【不】要求 mainPk 出现在 knownColumns 里：主键通常不在表单字段里，
        // 而物理列全集在数据源不通时会是空的。要求它存在等于在最需要它的场景下推不出来
        String mainPk = lower(descriptor.getMainPk());
        if (StrUtil.isNotBlank(mainPk)) {
            result.put(PermissionSlotEnum.BIZ_KEY.getSlot(), mainPk);
        }

        for (PermissionSlotEnum slot : PermissionSlotEnum.values()) {
            if (result.containsKey(slot.getSlot())) {
                continue;
            }
            for (String candidate : slot.getCandidates()) {
                if (knownColumns.contains(candidate)) {
                    result.put(slot.getSlot(), candidate);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 汇总「这张表上我们知道存在的列」
     *
     * 物理列全集是主来源；字段映射到的列作为补充 —— 元数据读不到时它是唯一线索
     */
    private Set<String> collectKnownColumns(FormResourceDescriptorDTO descriptor) {
        Set<String> columns = new LinkedHashSet<>();
        if (descriptor.getTableColumns() != null) {
            for (String column : descriptor.getTableColumns()) {
                if (StrUtil.isNotBlank(column)) {
                    columns.add(lower(column));
                }
            }
        }
        if (descriptor.getFields() != null) {
            for (FormResourceDescriptorDTO.Item field : descriptor.getFields()) {
                // 子表字段映射的是子表的列，不是主表的 —— 行过滤作用在主表上，
                // 把子表列当成主表列会推出一个主表根本没有的列名
                if (field != null && StrUtil.isNotBlank(field.getColumn()) && !field.isSubItem()) {
                    columns.add(lower(field.getColumn()));
                }
            }
        }
        return columns;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

}
