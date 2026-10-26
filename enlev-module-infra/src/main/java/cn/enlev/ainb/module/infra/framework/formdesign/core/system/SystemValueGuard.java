package cn.enlev.ainb.module.infra.framework.formdesign.core.system;

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
 * 系统值控件的提交值剥离
 *
 * <h3>为什么必须剥离，而不是「校验它对不对」</h3>
 *
 * 系统值控件展示的是创建人、创建时间、当前登录人这类由系统决定的值。
 * 它们【绝不能】由前端提交决定 —— 否则构造一个请求就能把创建人改成别人，
 * 而创建人往往是数据权限、审批流转、责任追溯的依据。
 *
 * 关键设计：这些控件在前端是只读渲染的，值本身也不由它们落库 ——
 * 落库完全走已有的 {@code AutoFillConfig}（后端按登录上下文写 creator / create_time）。
 * 因此本类要做的事很简单也很彻底：把这些字段从提交数据里【删掉】。
 *
 * 这与「拖 6 个可提交控件（创建人/创建时间/…）」的做法本质不同 ——
 * 后者的每个控件都是普通字段，前端提交什么就存什么。
 *
 * <h3>剥离而不是报错</h3>
 *
 * 前端把整个表单对象原样提交是常态，里面自然包含这些只读字段的当前值。
 * 报错会让正常操作失败；删掉既安全又不打扰用户。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class SystemValueGuard {

    /**
     * 系统值控件在 form-create 规则里的 type
     */
    public static final String SYSTEM_VALUE_WIDGET_TYPE = "SystemValue";

    /**
     * 剥离表单里所有系统值字段的提交值
     *
     * @param formFieldsJson 表单字段规则 JSON
     * @param data           提交数据。本方法不修改入参
     * @return 剥离后的数据
     */
    public Map<String, Object> strip(String formFieldsJson, Map<String, Object> data) {
        Map<String, Object> result = data != null ? new LinkedHashMap<>(data) : new LinkedHashMap<>();
        List<SystemValueField> fields = parseSystemValueFields(formFieldsJson);
        if (fields.isEmpty()) {
            return result;
        }
        List<String> stripped = new ArrayList<>();
        for (SystemValueField field : fields) {
            if (result.remove(field.getField()) != null) {
                stripped.add(field.getField());
            }
        }
        if (!stripped.isEmpty()) {
            // 记 debug 而不是 warn：前端原样回传整个表单对象是常态，不是异常。
            // 真正的伪造尝试同样会落在这里，需要时可按字段名检索
            log.debug("[strip][剥离了 {} 个系统值字段的提交值：{}]", stripped.size(), stripped);
        }
        return result;
    }

    /**
     * 从表单字段规则里提取全部系统值控件
     *
     * 要递归：控件可能被放在栅格、标签页等布局容器里。
     */
    public List<SystemValueField> parseSystemValueFields(String formFieldsJson) {
        List<SystemValueField> result = new ArrayList<>();
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
    private void collect(Map<String, Object> node, List<SystemValueField> result) {
        if (SYSTEM_VALUE_WIDGET_TYPE.equals(node.get("type"))
                && node.get("field") instanceof String field && StrUtil.isNotBlank(field)) {
            SystemValueField sv = new SystemValueField();
            sv.setField(field);
            if (node.get("props") instanceof Map<?, ?> propsMap) {
                Object source = ((Map<String, Object>) propsMap).get("source");
                sv.setSource(source instanceof String s ? s : null);
            }
            result.add(sv);
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

    /**
     * 一个系统值字段
     */
    @Data
    public static class SystemValueField {

        /**
         * 字段名
         */
        private String field;
        /**
         * 取值来源：CREATOR / CREATE_TIME / UPDATER / UPDATE_TIME
         * / CURRENT_USER / CURRENT_DEPT / CURRENT_POST / NOW
         */
        private String source;

    }

    /**
     * 取值来源与 AutoFillConfig 里对应列的映射
     *
     * 只有这四种来源是「由后端自动填充列」承载的，发布校验要核对它们
     * 与 AutoFillConfig 的配置一致 —— 一个展示「创建人」的控件，
     * 若绑定的列不是自动填充的创建人列，它显示的就不是创建人。
     */
    public enum AutoFillSource {
        CREATOR,
        CREATE_TIME,
        UPDATER,
        UPDATE_TIME;

        public static AutoFillSource parse(String source) {
            if (source == null) {
                return null;
            }
            try {
                return valueOf(source.toUpperCase());
            } catch (IllegalArgumentException e) {
                // CURRENT_USER / CURRENT_DEPT / NOW 这些不落库、只做展示，
                // 解析不到是正常的
                return null;
            }
        }
    }

}
