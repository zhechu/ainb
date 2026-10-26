package cn.enlev.ainb.module.infra.framework.formdesign.core.resource;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormResourceDescriptorDTO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.mask.MaskRule;
import cn.enlev.ainb.module.infra.framework.formdesign.core.meta.TableMetadataCache;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表单「可授权对象」描述的构建器
 *
 * 把 formFields（表单字段规则）、listConf（列表配置）、tableBinding（表绑定）
 * 三份 JSON 解析成一份扁平清单，交给权限模块登记资源。
 *
 * <h3>为什么解析失败不抛异常</h3>
 *
 * 只有一处例外：主表名为空时返回 null（调用方据此跳过登记）。其余解析失败一律
 * 降级为「这一项没有」，理由与 {@code FormFieldsCodec.read} 一致 ——
 * 配置结构会随迭代演进，一条历史脏规则不该让整张表单发布不了。
 *
 * 代价是「脏数据导致某个敏感字段没被登记」。这个代价是可接受的：
 * 字段没登记 → 走类型默认效果 ALLOW → 显示，与登记前的现状一致，不会更糟。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormResourceDescriptorBuilder {

    /**
     * 子表明细字段的标识分隔符
     *
     * 常量定义在 {@link FormResourceDescriptorDTO} 上，因为它是跨模块契约的一部分：
     * 权限模块靠它判断一个标识是不是子表字段，而权限模块看不到本解析器
     */
    private static final String SUB_FIELD_SEPARATOR = FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR;

    @Resource
    private TableMetadataCache metadataCache;

    /**
     * 构建描述
     *
     * @param design 表单设计
     * @return 描述；主表未绑定时返回 null
     */
    public FormResourceDescriptorDTO build(FormDesignDO design) {
        if (design == null || StrUtil.isBlank(design.getMainTable())) {
            return null;
        }
        FormResourceDescriptorDTO dto = new FormResourceDescriptorDTO();
        dto.setFormId(design.getId());
        dto.setFormCode(design.getCode());
        dto.setFormName(design.getName());
        dto.setDataSourceConfigId(design.getDataSourceConfigId());
        dto.setMainTable(design.getMainTable());
        dto.setMainPk(design.getMainPk());

        TableBinding binding = parseBinding(design.getTableBinding());
        // 字段 → 物理列。子表字段的键带上子表控件前缀，与 Item.key 对齐
        Map<String, String> columnByField = resolveColumnByField(binding);
        if (binding != null && binding.getAutoFill() != null && binding.getAutoFill().isCreatorAuto()) {
            // 只小写，不做驼峰转换：这是设计者填的物理列名，不是 Java 属性名
            dto.setCreatorColumn(binding.getAutoFill().getCreatorColumn().toLowerCase());
        }
        dto.setTableColumns(readTableColumns(design));
        dto.setFields(parseFields(design.getFormFields(), columnByField));
        dto.setColumns(parseColumns(design.getListConf(), dto.getFields(), columnByField));
        return dto;
    }

    // ==================== 表绑定 ====================

    private TableBinding parseBinding(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(json, TableBinding.class);
        } catch (Exception e) {
            log.warn("[parseBinding][表绑定解析失败，字段与物理列的映射本次为空]", e);
            return null;
        }
    }

    /**
     * 汇总「字段标识 → 物理列名」，主表与子表一起
     */
    private Map<String, String> resolveColumnByField(TableBinding binding) {
        Map<String, String> result = new LinkedHashMap<>();
        if (binding == null) {
            return result;
        }
        putMapping(result, null, binding.getFieldMapping());
        if (binding.getSubTables() != null) {
            for (SubTableBinding sub : binding.getSubTables()) {
                if (sub != null && StrUtil.isNotBlank(sub.getField())) {
                    putMapping(result, sub.getField(), sub.getFieldMapping());
                }
            }
        }
        return result;
    }

    private void putMapping(Map<String, String> result, String subField,
                            Map<String, FieldMapping> mapping) {
        if (mapping == null) {
            return;
        }
        mapping.forEach((field, fm) -> {
            if (StrUtil.isBlank(field) || fm == null || StrUtil.isBlank(fm.getColumn())) {
                return;
            }
            result.put(buildKey(subField, field), fm.getColumn().toLowerCase());
        });
    }

    /**
     * 读主表的物理列名全集
     *
     * 读不到（数据源不通、表被删）时返回空列表而不是抛：发布校验已经核实过表存在，
     * 走到这里还读不到属于偶发，不值得让发布失败。后果只是槽位推断退化为
     * 「只看字段映射到的列」，推不出来的槽位不写 —— 不会推出错的槽位
     */
    private List<String> readTableColumns(FormDesignDO design) {
        try {
            return new ArrayList<>(metadataCache
                    .columns(design.getDataSourceConfigId(), design.getMainTable()).keySet());
        } catch (Exception e) {
            log.warn("[readTableColumns][读主表({})结构失败，槽位推断退化]", design.getMainTable(), e);
            return new ArrayList<>();
        }
    }

    // ==================== 表单字段 ====================

    /**
     * 递归提取表单字段。字段可能嵌在布局容器或子表控件里
     */
    public List<FormResourceDescriptorDTO.Item> parseFields(String formFieldsJson,
                                                            Map<String, String> columnByField) {
        List<FormResourceDescriptorDTO.Item> result = new ArrayList<>();
        for (String rule : FormFieldsCodec.read(formFieldsJson)) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collect(node, result, null, columnByField);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> node, List<FormResourceDescriptorDTO.Item> result,
                         String subField, Map<String, String> columnByField) {
        if (node.get("field") instanceof String field && StrUtil.isNotBlank(field)) {
            Map<String, Object> props = node.get("props") instanceof Map<?, ?> p
                    ? (Map<String, Object>) p : Map.of();
            String key = buildKey(subField, field);
            FormResourceDescriptorDTO.Item item = new FormResourceDescriptorDTO.Item();
            item.setKey(key);
            item.setLabel(node.get("title") instanceof String t && StrUtil.isNotBlank(t) ? t : key);
            item.setColumn(columnByField.get(key));
            item.setSensitive(Boolean.TRUE.equals(props.get("sensitive")));
            item.setMasked(MaskRule.parse(props.get("maskRule") instanceof String s ? s : null)
                    != MaskRule.NONE);
            result.add(item);

            // 子表控件：内部规则挂在 props.rule 下，它们的 subField 是本控件的 field。
            // 与 FormDataMasker.collect 同构 —— 两处都在走同一棵树，走法不一致就会
            // 出现「脱敏认得这个字段、权限不认得」
            if (props.get("rule") instanceof List<?> innerRules) {
                for (Object inner : innerRules) {
                    if (inner instanceof Map<?, ?> innerMap) {
                        collect((Map<String, Object>) innerMap, result, field, columnByField);
                    }
                }
            }
        }
        if (node.get("children") instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> childMap) {
                    // 布局容器不改变归属，原样把 subField 传下去
                    collect((Map<String, Object>) childMap, result, subField, columnByField);
                }
            }
        }
    }

    // ==================== 列表列 ====================

    /**
     * 提取列表列
     *
     * 列的敏感标记<b>继承自同名字段</b>：敏感性是数据本身的属性，
     * 不该在「详情里的手机号」和「列表里的手机号」上各标一次 ——
     * 那必然出现只标了一处的配置，而漏掉的那一处就是泄露口。
     */
    @SuppressWarnings("unchecked")
    public List<FormResourceDescriptorDTO.Item> parseColumns(String listConfJson,
                                                             List<FormResourceDescriptorDTO.Item> fields,
                                                             Map<String, String> columnByField) {
        List<FormResourceDescriptorDTO.Item> result = new ArrayList<>();
        if (StrUtil.isBlank(listConfJson)) {
            return result;
        }
        Map<String, Object> listConf = JsonUtils.parseObjectQuietly(listConfJson,
                new TypeReference<Map<String, Object>>() {});
        if (listConf == null || !(listConf.get("columns") instanceof List<?> columns)) {
            return result;
        }
        Map<String, FormResourceDescriptorDTO.Item> fieldByKey = new LinkedHashMap<>();
        for (FormResourceDescriptorDTO.Item field : fields) {
            fieldByKey.putIfAbsent(field.getKey(), field);
        }
        for (Object item : columns) {
            if (!(item instanceof Map<?, ?> colMap)) {
                continue;
            }
            Map<String, Object> col = (Map<String, Object>) colMap;
            if (!(col.get("field") instanceof String field) || StrUtil.isBlank(field)) {
                continue;
            }
            String sub = col.get("subField") instanceof String sf && StrUtil.isNotBlank(sf) ? sf : null;
            String key = buildKey(sub, field);
            FormResourceDescriptorDTO.Item column = new FormResourceDescriptorDTO.Item();
            column.setKey(key);
            column.setLabel(col.get("label") instanceof String l && StrUtil.isNotBlank(l) ? l : key);
            column.setColumn(columnByField.get(key));
            FormResourceDescriptorDTO.Item related = fieldByKey.get(key);
            column.setSensitive(related != null && related.isSensitive());
            column.setMasked(related != null && related.isMasked());
            result.add(column);
        }
        return result;
    }

    private String buildKey(String subField, String field) {
        return StrUtil.isBlank(subField) ? field : subField + SUB_FIELD_SEPARATOR + field;
    }

}
