package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子表明细行的「物理列名 ↔ 表单字段名」转换
 *
 * <h3>为什么要转换</h3>
 * JDBC 查出来的 Map 键是物理列名（{@code product_name}、{@code qty}），
 * 而前端 form-create 的子表控件按字段名取值（{@code productName}、{@code quantity}）。
 * 不转换的话前端拿到的行数据键全对不上，明细显示为一片空白。
 *
 * <h3>主键的特殊约定</h3>
 * 主键【始终】以物理列名再输出一份，即使用户把它映射成了别的字段名。
 * 逐行 diff 靠这个键判断「这一行是老行还是新行」（见 {@link SubTableDiffEngine}），
 * 而 form-create 的子表控件会把未声明的键原样带回提交数据，所以这条链路是通的。
 *
 * 反过来，若只按 fieldMapping 输出主键，一旦用户把主键列映射成别的字段名，
 * diff 就取不到主键 —— 每次保存都会把全部明细当新增插入、再把旧明细全删，
 * 明细 ID 全部漂移，逐行 diff 退化成它本来就要避免的全量替换。
 *
 * <h3>为什么单独成类</h3>
 * 这段逻辑原本内联在运行时 Service 里，测试只能照抄一份实现来验证，
 * 于是实现改了、测试里的副本没改，主键那条规则的缺陷一直测不出来。
 * 抽出来之后测试打的是真实代码。
 *
 * @author 走进新时代
 */
public final class SubTableRowMapper {

    private SubTableRowMapper() {
    }

    /**
     * 把 JDBC 原始行转成前端可直接消费的行
     *
     * @param subTable 子表配置
     * @param rawRows  JDBC 查询结果，键为物理列名
     * @return 键为表单字段名的行；主键额外保留一份物理列名的键
     */
    public static List<Map<String, Object>> toFieldKeyedRows(SubTableBinding subTable,
                                                             List<Map<String, Object>> rawRows) {
        if (rawRows == null || rawRows.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> columnToField = buildColumnToField(subTable);
        String pkColumn = pkColumnOf(subTable);

        List<Map<String, Object>> rows = new ArrayList<>(rawRows.size());
        for (Map<String, Object> rawRow : rawRows) {
            Map<String, Object> row = new HashMap<>();
            for (Map.Entry<String, Object> entry : rawRow.entrySet()) {
                // 未映射的列保持原名：外键、审计列等前端可能要显示
                String field = columnToField.getOrDefault(entry.getKey(), entry.getKey());
                row.put(field, entry.getValue());
            }
            // 主键补一份按物理列名的副本，保证 diff 一定取得到
            if (rawRow.containsKey(pkColumn)) {
                row.put(pkColumn, rawRow.get(pkColumn));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 构建「物理列名 → 表单字段名」索引
     *
     * 多个字段映射到同一列时后者覆盖前者。这属于配置错误（同一列被写两次），
     * 发布校验之外这里不额外报错 —— 读取路径不该因为配置问题而整个查不出来。
     */
    private static Map<String, String> buildColumnToField(SubTableBinding subTable) {
        Map<String, String> columnToField = new HashMap<>();
        if (subTable == null || subTable.getFieldMapping() == null) {
            return columnToField;
        }
        for (Map.Entry<String, FieldMapping> entry : subTable.getFieldMapping().entrySet()) {
            FieldMapping mapping = entry.getValue();
            if (mapping != null && StrUtil.isNotBlank(mapping.getColumn())) {
                columnToField.put(mapping.getColumn(), entry.getKey());
            }
        }
        return columnToField;
    }

    /**
     * 子表主键列名，未配置时按约定用 id
     */
    public static String pkColumnOf(SubTableBinding subTable) {
        if (subTable == null || StrUtil.isBlank(subTable.getPk())) {
            return "id";
        }
        return subTable.getPk();
    }

}
