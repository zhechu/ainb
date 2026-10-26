package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可导入字段的注册表
 *
 * 从表单的字段规则与列表配置里，算出「这张表单有哪些字段是可以从 Excel 导进来的」。
 *
 * <h3>哪些字段不进这份清单</h3>
 *
 * <ul>
 *   <li><b>纯布局控件</b>（栅格、标签页、卡片、标题、分割线…）—— 它们没有值</li>
 *   <li><b>只读的系统字段</b>（创建人、创建时间…）—— 由自动填充写，导入时不该让文件决定</li>
 *   <li><b>公式字段</b> —— **进清单但标记 formula**，因为导出文件里带着它们，
 *       得能匹配上表头（匹配不上会报「未识别列」，误导用户）；
 *       但导入时它的值会被服务端重算覆盖，文件里填什么都不作数</li>
 *   <li><b>受控子表控件本身</b>（group / subForm / tableForm）—— 有值的是它里面
 *       的列，不是它自己。列会以 subField 标注的形式进清单</li>
 * </ul>
 *
 * @author 走进新时代
 */
@Component
public class ImportWidgetRegistry {

    /**
     * 不进清单的控件类型
     *
     * 判据是「它自己不承载值」。有值但不该导入的（公式、系统字段）走别的分支，
     * 不写进这里 —— 混在一起会让「为什么这个字段不在导入清单里」查不出来。
     */
    private static final List<String> LAYOUT_TYPES = List.of(
            "elAlert", "elButton", "elDivider", "html", "elImage", "Link", "elTag",
            "text", "fcTitle", "fcRow", "col", "elCard", "elCollapse", "elCollapseItem",
            "elTabs", "elTabPane", "FormSteps", "FormStep", "fcTable", "space");

    /**
     * 子表类控件。它们的值是一组明细行
     */
    private static final List<String> SUB_TABLE_TYPES = List.of("group", "subForm", "tableForm");

    /**
     * 校验用不到但模板/表头要认得出来的字段类型（保留给未来扩展）
     */
    private static final List<String> FILE_TYPES = List.of(
            "UploadFile", "UploadImg", "UploadImgs", "Signature", "signaturePad", "upload");

    /**
     * 服务端系统字段名。这些是框架表上的列，不是业务字段，
     * 不进导入清单 —— 让文件决定创建时间会把审计链路搞乱
     */
    private static final List<String> SYSTEM_FIELDS = List.of(
            "id", "creator", "create_time", "updater", "update_time", "deleted", "version",
            "process_instance_id", "process_status");

    /**
     * 解析出一张表单的可导入字段
     *
     * @param formFieldsJson 表单字段规则 JSON
     * @param listConfJson   列表配置 JSON，可为空
     * @return 字段名 → 字段，保持表单里的定义顺序
     */
    public Map<String, ImportWidget> parse(String formFieldsJson, String listConfJson) {
        Map<String, ImportWidget> result = new LinkedHashMap<>();

        // 列表列上的 label 先建一张索引：导出文件的表头就是它，
        // 导入时要靠它反查回字段
        Map<String, String> columnLabelOf = parseColumnLabels(listConfJson);

        for (String rule : FormFieldsCodec.read(formFieldsJson)) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collect(node, null, null, columnLabelOf, result);
            }
        }
        return result;
    }

    /**
     * 递归收集字段
     *
     * 必须递归：字段可能被放在栅格、标签页、卡片这些布局容器里，
     * 只扫顶层的话「放进标签页里的字段」会整片消失 ——
     * 而用户看到的只是「这几列识别不出来」。
     *
     * @param subField     当前所在的子表控件 field，为空表示主表
     * @param subTitle     当前所在的子表控件标题
     */
    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> node, String subField, String subTitle,
                         Map<String, String> columnLabelOf, Map<String, ImportWidget> result) {
        String type = asText(node.get("type"));
        String field = asText(node.get("field"));

        boolean isSubTable = SUB_TABLE_TYPES.contains(type);

        // 子表控件本身不进清单（它没有标量值），但它的内层要带上下文递归下去
        if (isSubTable) {
            String nextSubField = StrUtil.isNotBlank(field) ? field : subField;
            String nextSubTitle = asText(node.get("title"));
            Object children = node.get("children");
            if (children instanceof List<?> list) {
                for (Object child : list) {
                    if (child instanceof Map<?, ?> childMap) {
                        collect((Map<String, Object>) childMap, nextSubField, nextSubTitle,
                                columnLabelOf, result);
                    }
                }
            }
            // ⚠️ 子表还可能把列放在 props.rule 里（设计器存的形态），
            //    只扫 children 的话会一条明细列都收不到
            collectSubTableRule(node, nextSubField, nextSubTitle, columnLabelOf, result);
            return;
        }

        // 有 field 但类型是布局控件的，值是空的，跳过；但它的 children 照样要递归
        if (StrUtil.isNotBlank(field) && !LAYOUT_TYPES.contains(type)
                && !SYSTEM_FIELDS.contains(field)) {
            ImportWidget widget = build(node, field, type, subField, subTitle, columnLabelOf);
            result.put(field, widget);
        }

        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collect((Map<String, Object>) childMap, subField, subTitle, columnLabelOf, result);
                }
            }
        }
    }

    /**
     * 从子表控件的 props.rule 里收集明细列
     *
     * 设计器把子表的列存在 props.rule（一个规则数组，每项是 JSON 串或对象），
     * 与 form-create 的 children 是两份东西。只认 children 会漏掉全部明细列。
     */
    @SuppressWarnings("unchecked")
    private void collectSubTableRule(Map<String, Object> node, String subField, String subTitle,
                                     Map<String, String> columnLabelOf,
                                     Map<String, ImportWidget> result) {
        Object props = node.get("props");
        if (!(props instanceof Map<?, ?> propsMap)) {
            return;
        }
        Object rule = ((Map<String, Object>) propsMap).get("rule");
        if (!(rule instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            Map<String, Object> columnNode = null;
            if (item instanceof Map<?, ?> itemMap) {
                columnNode = (Map<String, Object>) itemMap;
            } else if (item instanceof String text) {
                columnNode = JsonUtils.parseObjectQuietly(text,
                        new TypeReference<Map<String, Object>>() {});
            }
            if (columnNode != null) {
                collect(columnNode, subField, subTitle, columnLabelOf, result);
            }
        }
    }

    /**
     * 造一个字段模型
     */
    @SuppressWarnings("unchecked")
    private ImportWidget build(Map<String, Object> node, String field, String type,
                               String subField, String subTitle,
                               Map<String, String> columnLabelOf) {
        ImportWidget widget = new ImportWidget();
        widget.setField(field);
        widget.setTitle(asText(node.get("title")));
        widget.setType(type);
        widget.setRequired(Boolean.TRUE.equals(node.get("$required")));
        widget.setSubField(subField);
        widget.setSubTitle(subTitle);
        widget.setColumnLabel(columnLabelOf.get(field));
        // 公式字段：类型是 Formula，或者 props 里有 expression ——
        // 设计器两种都产出过
        widget.setFormula("Formula".equals(type) || "FormulaInput".equals(type));

        Object props = node.get("props");
        Map<String, Object> propsMap = props instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        widget.setDisabled(Boolean.TRUE.equals(propsMap.get("disabled")));
        widget.setUniqueCheck(Boolean.TRUE.equals(propsMap.get("uniqueCheck")));
        widget.setMasked(propsMap.get("maskRule") != null);
        widget.setPrecision(asInt(propsMap.get("precision")));
        widget.setMin(asDecimal(propsMap.get("min")));
        widget.setMax(asDecimal(propsMap.get("max")));
        widget.setValueFormat(asText(propsMap.get("valueFormat")));
        if (propsMap.get("expression") != null) {
            widget.setFormula(true);
        }

        // 日期控件的粒度。设计器存在 props.type（date / datetime / time），
        // 与控件类型名 datePicker 区分开
        widget.setDateType("datePicker".equals(type) || "dateRange".equals(type)
                || "timePicker".equals(type) || "timeRange".equals(type)
                ? asText(propsMap.get("type")) : null);

        // 字典来源。有两种形态：props.dictSource（新）与 props.dictType（旧），
        // 两种都认 —— 存量表单里两种都在用
        Object dictSource = propsMap.get("dictSource");
        if (dictSource instanceof Map<?, ?> ds) {
            widget.setDictType(asText(((Map<String, Object>) ds).get("dictType")));
            widget.setDictValueType(asText(((Map<String, Object>) ds).get("valueType")));
        } else if (StrUtil.isNotBlank(asText(propsMap.get("dictType")))) {
            widget.setDictType(asText(propsMap.get("dictType")));
        }

        widget.setApiCode(firstNonBlank(asText(propsMap.get("apiCode")),
                asText(propsMap.get("interfaceCode"))));
        return widget;
    }

    /**
     * 收集列表列上的 label
     *
     * ⚠️ 明细列（带 subField 的）也要收，但键用 field ——
     * 导出时明细列的表头取的就是它的 label 拼出来的那一列，
     * 不认的话用户把导出文件原样导回来会报「明细列未识别」。
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseColumnLabels(String listConfJson) {
        Map<String, String> result = new LinkedHashMap<>();
        if (StrUtil.isBlank(listConfJson)) {
            return result;
        }
        Map<String, Object> conf = JsonUtils.parseObjectQuietly(listConfJson,
                new TypeReference<Map<String, Object>>() {});
        if (conf == null || !(conf.get("columns") instanceof List<?> columns)) {
            return result;
        }
        for (Object item : columns) {
            if (!(item instanceof Map<?, ?> colMap)) {
                continue;
            }
            Map<String, Object> col = (Map<String, Object>) colMap;
            String field = asText(col.get("field"));
            String label = asText(col.get("label"));
            if (StrUtil.isNotBlank(field) && StrUtil.isNotBlank(label)) {
                // 同一个字段配了多列时保留第一个 —— 后面的多半是重复展示，
                // 而表头匹配只需要一个能命中的名字
                result.putIfAbsent(field, label);
            }
        }
        return result;
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static java.math.BigDecimal asDecimal(Object value) {
        if (value instanceof Number number) {
            return new java.math.BigDecimal(number.toString());
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                return new java.math.BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 这个字段的值是否需要从文件里读
     *
     * 公式字段与只读字段的值由服务端算，文件里填什么都不作数。
     * 但它们**仍然要能匹配上表头** —— 导出文件里有它们，匹配不上会报「未识别列」。
     */
    public static boolean isValueFromFile(ImportWidget widget) {
        return !widget.isFormula() && !widget.isDisabled();
    }

}
