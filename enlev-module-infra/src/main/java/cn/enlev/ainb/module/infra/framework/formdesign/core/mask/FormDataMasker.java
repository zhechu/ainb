package cn.enlev.ainb.module.infra.framework.formdesign.core.mask;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据脱敏
 *
 * <h3>为什么脱敏必须在服务端做</h3>
 *
 * 前端脱敏是安全假象：接口照样返回明文，抓包即可见。
 * 本模块的字段级权限延后了（见设计方案 6.2），**脱敏是敏感字段目前唯一的防线**，
 * 所以它必须真的发生在数据离开服务端之前。
 *
 * <h3>三个出口统一处理</h3>
 *
 * 详情 {@code get}、列表 {@code page}、导出 {@code export} 三处都要脱敏。
 * 漏掉任何一个都等于开了一扇后门 —— 而导出恰恰是最容易被忘记、
 * 也是泄露量最大的那个（一次导出上万行明文）。
 *
 * <h3>子表明细一并处理</h3>
 *
 * 明细行里同样可能有手机号。只脱主表字段的话，把敏感信息挪到明细里就能绕过。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormDataMasker {

    /**
     * 对一行数据脱敏
     *
     * @param formFieldsJson 表单字段规则 JSON
     * @param row            数据行。本方法不修改入参
     * @return 脱敏后的数据
     */
    public Map<String, Object> mask(String formFieldsJson, Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        List<MaskedField> fields = parseMaskedFields(formFieldsJson);
        if (fields.isEmpty()) {
            return row;
        }
        return maskRow(row, fields);
    }

    /**
     * 对多行数据脱敏（列表与导出用）
     */
    public List<Map<String, Object>> maskAll(String formFieldsJson,
                                             List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        List<MaskedField> fields = parseMaskedFields(formFieldsJson);
        if (fields.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(row == null ? null : maskRow(row, fields));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> maskRow(Map<String, Object> row, List<MaskedField> fields) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        for (MaskedField field : fields) {
            if (field.getSubField() == null) {
                // 主表字段
                Object value = result.get(field.getField());
                if (value != null) {
                    result.put(field.getField(), field.apply(value));
                }
                continue;
            }
            // 子表明细：逐行脱敏。只脱主表字段的话，
            // 把敏感信息挪到明细里就能绕过
            Object raw = result.get(field.getSubField());
            if (!(raw instanceof Collection<?> collection)) {
                continue;
            }
            List<Object> maskedRows = new ArrayList<>(collection.size());
            for (Object item : collection) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> detail = new LinkedHashMap<>((Map<String, Object>) map);
                    Object value = detail.get(field.getField());
                    if (value != null) {
                        detail.put(field.getField(), field.apply(value));
                    }
                    maskedRows.add(detail);
                } else {
                    maskedRows.add(item);
                }
            }
            result.put(field.getSubField(), maskedRows);
        }
        return result;
    }

    // ==================== 回写保护 ====================

    /**
     * 剔除主表字段里的「掩码回显」
     *
     * <h3>为什么必须有这一步</h3>
     *
     * 详情 {@code get} 返回的是脱敏值，而前端提交时回传的是整条记录
     * （{@code {...完整记录, ...本次改动}}）。用户只改了金额，手机号那一格
     * 交上来的就是 {@code 138****5678} —— 直接写库的话，真实手机号被永久覆盖，
     * 且没有任何报错。**这是数据损坏，不是显示问题。**
     *
     * <h3>判定方式</h3>
     *
     * 提交值与「库里当前值的脱敏结果」完全相等 → 认定用户没动过这一格，剔除。
     * 用的是同一份 {@link MaskedField} 规则，不会与脱敏逻辑漂移。
     *
     * 用户真把手机号改成别的值时两者不相等，照常写入 —— 所以「改」这条路没被堵死。
     *
     * <h3>剩下的那个边界</h3>
     *
     * 用户把手机号原样重新输入一遍（值与掩码恰好相同）是无法与回显区分的，
     * 但那种情况下写与不写的结果完全一致，没有实际影响。
     *
     * @param formFieldsJson 表单字段规则
     * @param submitted      提交上来的数据（逻辑形态）
     * @param current        库里的当前行（未脱敏）
     * @return 剔除后的新 Map；没有脱敏字段时原样返回入参
     */
    public Map<String, Object> stripMaskedEcho(String formFieldsJson,
                                               Map<String, Object> submitted,
                                               Map<String, Object> current) {
        if (submitted == null || current == null) {
            return submitted;
        }
        List<MaskedField> fields = parseMaskedFields(formFieldsJson).stream()
                .filter(field -> field.getSubField() == null)
                .toList();
        if (fields.isEmpty()) {
            return submitted;
        }
        Map<String, Object> result = new LinkedHashMap<>(submitted);
        for (MaskedField field : fields) {
            if (isMaskedEcho(field, result.get(field.getField()), current.get(field.getField()))) {
                // 移除而不是替换成原值：不进 UPDATE 的字段集，
                // 既不会写坏数据，也不会在变更日志里留下一条「没变化的变化」
                result.remove(field.getField());
                log.debug("[stripMaskedEcho][字段({})提交的是脱敏回显，已跳过更新]", field.getField());
            }
        }
        return result;
    }

    /**
     * 把「掩码回显」的字段填回库里的原值
     *
     * <h3>与 {@link #stripMaskedEcho} 的区别，以及为什么需要它</h3>
     *
     * 两者判定「这是不是掩码回显」的规则完全相同，处理方式不同：
     * 一个移除这个键，一个填回库里的真实值。
     *
     * 移除的语义有两个问题，而它们只在「脱敏字段同时配了别的约束」时才暴露：
     *
     * <ul>
     *   <li><b>格式校验</b>：字段还在时掩码过不了正则。所以剔除必须排在校验之前 ——
     *       而排在之前又意味着校验看不到这个字段</li>
     *   <li><b>必填校验</b>：字段被移除后，联动守卫会判成「必填项没填」</li>
     * </ul>
     *
     * 填回原值则让下游（校验、必填、联动、公式、扩展点）看到<b>真实数据</b>，
     * 那本来就是它们该看到的。代价只是 UPDATE 多写一列相同的值 ——
     * 而变更日志不会因此产生噪音（{@code FormDataChangeDiffer} 逐字段比对，
     * 值相同不记录）。
     *
     * @param submitted 提交上来的数据（逻辑形态）
     * @param current   库里的当前行（未脱敏）
     * @return 填回后的新 Map；没有脱敏字段时原样返回入参
     */
    public Map<String, Object> restoreMaskedEcho(String formFieldsJson,
                                                 Map<String, Object> submitted,
                                                 Map<String, Object> current) {
        if (submitted == null || current == null) {
            return submitted;
        }
        List<MaskedField> fields = parseMaskedFields(formFieldsJson).stream()
                .filter(field -> field.getSubField() == null)
                .toList();
        if (fields.isEmpty()) {
            return submitted;
        }
        Map<String, Object> result = new LinkedHashMap<>(submitted);
        for (MaskedField field : fields) {
            if (isMaskedEcho(field, result.get(field.getField()), current.get(field.getField()))) {
                result.put(field.getField(), current.get(field.getField()));
                log.debug("[restoreMaskedEcho][字段({})提交的是脱敏回显，已填回原值]",
                        field.getField());
            }
        }
        return result;
    }

    /**
     * 剔除一行子表明细里的「掩码回显」
     *
     * 明细行同样会被脱敏下发，所以同样会被掩码回写。只保主表等于留了半扇门。
     *
     * @param subField     子表控件的 field
     * @param submittedRow 提交上来的明细行
     * @param currentRow   库里对应的明细行（未脱敏）；为空表示新增行，原样返回
     */
    public Map<String, Object> stripMaskedEchoForSubRow(String formFieldsJson,
                                                        String subField,
                                                        Map<String, Object> submittedRow,
                                                        Map<String, Object> currentRow) {
        if (submittedRow == null || currentRow == null || StrUtil.isBlank(subField)) {
            return submittedRow;
        }
        List<MaskedField> fields = parseMaskedFields(formFieldsJson).stream()
                .filter(field -> subField.equals(field.getSubField()))
                .toList();
        if (fields.isEmpty()) {
            return submittedRow;
        }
        Map<String, Object> result = new LinkedHashMap<>(submittedRow);
        for (MaskedField field : fields) {
            if (isMaskedEcho(field, result.get(field.getField()), currentRow.get(field.getField()))) {
                result.remove(field.getField());
            }
        }
        return result;
    }

    /**
     * 该表单有没有配主表脱敏字段
     *
     * 给调用方做短路用：没配就不必为回写保护多读一次库
     */
    public boolean hasMaskedMainFields(String formFieldsJson) {
        return parseMaskedFields(formFieldsJson).stream()
                .anyMatch(field -> field.getSubField() == null);
    }

    /**
     * 该子表控件有没有配脱敏字段
     */
    public boolean hasMaskedSubFields(String formFieldsJson, String subField) {
        if (StrUtil.isBlank(subField)) {
            return false;
        }
        return parseMaskedFields(formFieldsJson).stream()
                .anyMatch(field -> subField.equals(field.getSubField()));
    }

    /**
     * 提交值是不是「当前值的脱敏结果」
     *
     * 当前值为空时一律判否：空值脱敏出来还是空，此时提交的任何值都该被当成新值写入。
     */
    private boolean isMaskedEcho(MaskedField field, Object submitted, Object current) {
        if (submitted == null || current == null) {
            return false;
        }
        String masked = field.apply(current);
        return masked != null && masked.equals(submitted.toString());
    }

    // ==================== 解析 ====================

    /**
     * 从表单字段规则里提取配置了脱敏的字段
     *
     * 要递归：字段可能在布局容器或子表里。
     */
    public List<MaskedField> parseMaskedFields(String formFieldsJson) {
        List<MaskedField> result = new ArrayList<>();
        List<String> rules = FormFieldsCodec.read(formFieldsJson);
        for (String rule : rules) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collect(node, result, null);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> node, List<MaskedField> result, String subField) {
        if (node.get("field") instanceof String field && StrUtil.isNotBlank(field)
                && node.get("props") instanceof Map<?, ?> propsMap) {
            Map<String, Object> props = (Map<String, Object>) propsMap;
            MaskRule rule = MaskRule.parse(props.get("maskRule") instanceof String s ? s : null);
            if (rule != MaskRule.NONE) {
                MaskedField masked = new MaskedField();
                masked.setField(field);
                masked.setSubField(subField);
                masked.setRule(rule);
                masked.setFiller(props.get("maskFiller") instanceof String s ? s : "*");
                if (props.get("maskKeepPrefix") instanceof Number n) {
                    masked.setKeepPrefix(n.intValue());
                }
                if (props.get("maskKeepSuffix") instanceof Number n) {
                    masked.setKeepSuffix(n.intValue());
                }
                result.add(masked);
            }
            // 子表控件：内部规则挂在 props.rule 下，它们的 subField 是本控件的 field
            if (props.get("rule") instanceof List<?> innerRules) {
                for (Object inner : innerRules) {
                    if (inner instanceof Map<?, ?> innerMap) {
                        collect((Map<String, Object>) innerMap, result, field);
                    }
                }
            }
        }
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    // 布局容器不改变归属，原样把 subField 传下去
                    collect((Map<String, Object>) childMap, result, subField);
                }
            }
        }
    }

    /**
     * 一个配置了脱敏的字段
     */
    @Data
    public static class MaskedField {

        private String field;
        /**
         * 所属子表控件的 field；为空表示主表字段
         */
        private String subField;
        private MaskRule rule;
        private String filler = "*";
        private Integer keepPrefix;
        private Integer keepSuffix;

        public String apply(Object value) {
            return rule.apply(value, filler, keepPrefix, keepSuffix);
        }

    }

}
