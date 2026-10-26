package cn.enlev.ainb.module.infra.framework.formdesign.core.relation;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关联表单控件的配置解析
 *
 * 关联表单做的事：从另一张已发布表单里选一条记录，落库它的主键，
 * 并按「带出字段映射表」把该记录的若干字段抄到当前表单。
 *
 * <h3>为什么带出关系是宿主控件的属性，而不是独立控件</h3>
 *
 * 同类产品的做法是：为每个要带出的字段再拖一个「关联属性」控件，反向绑定宿主。
 * 一个带出 5 个字段的关联要拖 6 个控件、做 6 次绑定，而且删掉宿主之后
 * 那些属性控件会变成孤儿 —— 它们指向一个不存在的控件，界面上还占着位置。
 *
 * 本实现把带出关系收进宿主控件的属性面板：一个控件、一张映射表、
 * 宿主删除即整体消失。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class RelationFormResolver {

    /**
     * 关联表单控件在 form-create 规则里的 type
     */
    public static final String RELATION_WIDGET_TYPE = "RelationForm";

    /**
     * 带出字段的更新方式
     */
    public enum UpdateMode {
        /**
         * 仅首次带出：目标字段为空时才写
         */
        ONCE,
        /**
         * 每次覆盖：只要关联记录变了就重新写
         */
        ALWAYS,
        /**
         * 允许手工改后不再覆盖。与 ONCE 的区别是语义而非行为 ——
         * 保留两个是因为设计器里要给用户两种不同的措辞
         */
        MANUAL
    }

    /**
     * 从表单字段规则里提取全部关联表单控件
     *
     * 要递归：控件可能被放在栅格、标签页等布局容器里。
     */
    public List<RelationField> parseRelationFields(String formFieldsJson) {
        List<RelationField> result = new ArrayList<>();
        List<String> rules = FormFieldsCodec.read(formFieldsJson);
        for (String rule : rules) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collect(node, result);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> node, List<RelationField> result) {
        if (RELATION_WIDGET_TYPE.equals(node.get("type"))
                && node.get("field") instanceof String field && StrUtil.isNotBlank(field)
                && node.get("props") instanceof Map<?, ?> propsMap) {
            RelationField relation = read(field, (Map<String, Object>) propsMap);
            if (relation != null) {
                result.add(relation);
            }
        }
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collect((Map<String, Object>) childMap, result);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private RelationField read(String field, Map<String, Object> props) {
        String targetFormCode = props.get("targetFormCode") instanceof String s ? s : null;
        if (StrUtil.isBlank(targetFormCode)) {
            // 还没选关联表单，跳过而不是报错 —— 一个没配完的控件不该让整张表单提交不了
            return null;
        }
        RelationField relation = new RelationField();
        relation.setField(field);
        relation.setTargetFormCode(targetFormCode);
        relation.setDisplayField(props.get("displayField") instanceof String s ? s : null);

        Object mappings = props.get("fieldMappings");
        if (mappings instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) map;
                String source = m.get("sourceField") instanceof String s ? s : null;
                String target = m.get("targetField") instanceof String s ? s : null;
                if (StrUtil.isBlank(source) || StrUtil.isBlank(target)) {
                    continue;
                }
                FieldMapping mapping = new FieldMapping();
                mapping.setSourceField(source);
                mapping.setTargetField(target);
                mapping.setUpdateMode(parseUpdateMode(m.get("updateMode")));
                relation.getFieldMappings().add(mapping);
            }
        }
        return relation;
    }

    private UpdateMode parseUpdateMode(Object raw) {
        if (raw == null) {
            return UpdateMode.ALWAYS;
        }
        try {
            return UpdateMode.valueOf(String.valueOf(raw).toUpperCase());
        } catch (IllegalArgumentException e) {
            // 写错了按「每次覆盖」处理：那是最安全的语义 ——
            // 带出值始终与关联记录一致，不会留下过期数据
            return UpdateMode.ALWAYS;
        }
    }

    // ==================== 模型 ====================

    /**
     * 一个关联表单字段
     */
    @Data
    public static class RelationField {

        /**
         * 本控件的字段名。它的值是被关联记录的主键
         */
        private String field;
        /**
         * 被关联的表单编码
         */
        private String targetFormCode;
        /**
         * 选中后输入框展示哪个字段
         */
        private String displayField;
        /**
         * 带出字段映射
         */
        private final List<FieldMapping> fieldMappings = new ArrayList<>();

        /**
         * 本次提交需要带出的目标字段 → 源字段
         *
         * @param currentData 当前提交数据，用于判断 ONCE/MANUAL 模式下目标字段是否已有值
         */
        public Map<String, String> resolveMappingsToApply(Map<String, Object> currentData) {
            Map<String, String> result = new LinkedHashMap<>();
            for (FieldMapping mapping : fieldMappings) {
                if (mapping.getUpdateMode() == UpdateMode.ALWAYS) {
                    result.put(mapping.getTargetField(), mapping.getSourceField());
                    continue;
                }
                // ONCE 与 MANUAL：目标字段已有值就不动它
                Object existing = currentData == null ? null : currentData.get(mapping.getTargetField());
                boolean empty = existing == null
                        || (existing instanceof CharSequence cs && cs.isEmpty());
                if (empty) {
                    result.put(mapping.getTargetField(), mapping.getSourceField());
                }
            }
            return result;
        }

    }

    /**
     * 一条带出字段映射
     */
    @Data
    public static class FieldMapping {

        /**
         * 被关联表单里的字段
         */
        private String sourceField;
        /**
         * 当前表单里的字段
         */
        private String targetField;
        /**
         * 更新方式
         */
        private UpdateMode updateMode = UpdateMode.ALWAYS;

    }

}
