package cn.enlev.ainb.module.infra.framework.formdesign.core.subtable;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormErrorText;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 子表的服务端校验
 *
 * <h3>它防的是什么</h3>
 *
 * 子表控件在前端可以配「最少几行、最多几行」，明细里的字段也可以配必填。
 * 这些约束和别处一样，绕过界面直接构造请求就能突破：
 * <ul>
 *   <li>提交 0 行明细 —— 一张没有任何明细的订单</li>
 *   <li>提交 5000 行明细 —— 撑爆写入事务，或把列表页拖垮</li>
 *   <li>明细行里必填的单价留空 —— 后续算总价时按 0 处理，金额直接错</li>
 * </ul>
 *
 * 所以提交时服务端要按同一套配置再校验一遍。
 *
 * <h3>行数上限有一个与配置无关的硬顶</h3>
 *
 * 即便用户没配 maxRows，也要有个兜底上限。子表是一次性全量写入的，
 * 几千行会让单个事务变得很长、锁很多行；而正常业务里明细行数极少超过几百。
 * 没有硬顶的话，一个构造出来的请求就能让数据库卡住 —— 这不是「用户配错了」，
 * 是「我们没有防」。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class SubTableGuard {

    /**
     * 行数硬顶
     *
     * 与用户配置无关的兜底。取 1000：正常业务的明细行数极少超过几百，
     * 而 1000 行的一次性写入已经是一个相当长的事务了。
     */
    public static final int HARD_MAX_ROWS = 1000;

    @Resource
    private WidgetContractRegistry widgetContractRegistry;

    /**
     * 校验表单里所有子表的行数与行内必填
     *
     * @param formFieldsJson 表单字段规则 JSON
     * @param data           提交数据
     * @return 校验结果
     */
    public ValidationResult validate(String formFieldsJson, Map<String, Object> data) {
        ValidationResult result = new ValidationResult();
        List<SubTableWidget> widgets = parseSubTableWidgets(formFieldsJson);
        if (widgets.isEmpty()) {
            return result;
        }

        for (SubTableWidget widget : widgets) {
            Object raw = data == null ? null : data.get(widget.getField());
            List<Map<String, Object>> rows = toRows(raw);
            int rowCount = rows.size();

            // 1. 行数硬顶。放在最前面 —— 后面的逐行校验要遍历所有行，
            //    先把明显过量的请求挡掉，避免为一个恶意请求做几千次字段检查
            if (rowCount > HARD_MAX_ROWS) {
                result.addError(String.format("「%s」的明细行数是 %d，超过系统上限 %d 行",
                        widget.label(), rowCount, HARD_MAX_ROWS));
                continue;
            }
            // 2. 用户配置的行数区间
            if (widget.getMinRows() != null && widget.getMinRows() > 0 && rowCount < widget.getMinRows()) {
                result.addError(String.format("「%s」至少需要 %d 行明细，当前 %d 行",
                        widget.label(), widget.getMinRows(), rowCount));
            }
            if (widget.getMaxRows() != null && widget.getMaxRows() > 0 && rowCount > widget.getMaxRows()) {
                result.addError(String.format("「%s」最多 %d 行明细，当前 %d 行",
                        widget.label(), widget.getMaxRows(), rowCount));
            }

            // 3. 行内必填
            validateRequiredCells(widget, rows, result);
        }
        return result;
    }

    /**
     * 逐行校验必填单元格
     *
     * 报错要带行号 —— 一张 30 行的明细里说「单价必填」，用户不知道是哪一行。
     * 行号从 1 开始计，与界面上显示的一致。
     *
     * 同一列的多行缺失合并成一条：30 行都没填单价时报 30 条错误，
     * 用户看到的是一屏刷屏，反而找不到重点。
     */
    private void validateRequiredCells(SubTableWidget widget, List<Map<String, Object>> rows,
                                       ValidationResult result) {
        for (SubTableColumn column : widget.getColumns()) {
            if (!column.isRequired()) {
                continue;
            }
            List<Integer> missingRows = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Object value = rows.get(i).get(column.getField());
                if (isEmpty(value)) {
                    missingRows.add(i + 1);
                }
            }
            if (missingRows.isEmpty()) {
                continue;
            }
            // 缺失行太多时只列前几个，后面用「等 N 行」概括。
            // 格式统一收在 FormErrorText —— 各校验器各写一套概括方式，
            // 用户会以为是两套不同的东西
            String rowsText = FormErrorText.rows(missingRows);
            result.addError(String.format("「%s」第 %s 的「%s」是必填的",
                    widget.label(), rowsText, column.label()));
        }
    }

    private boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence cs) {
            return cs.isEmpty();
        }
        if (value instanceof Collection<?> c) {
            return c.isEmpty();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toRows(Object raw) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Map<?, ?> map) {
                    rows.add((Map<String, Object>) map);
                }
            }
        }
        return rows;
    }

    // ==================== 解析 ====================

    /**
     * 从表单字段规则里提取全部子表控件及其列配置
     */
    public List<SubTableWidget> parseSubTableWidgets(String formFieldsJson) {
        List<SubTableWidget> result = new ArrayList<>();
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
    private void collect(Map<String, Object> node, List<SubTableWidget> result) {
        String subTableType = widgetContractRegistry.getSubTableWidgetType();
        if (subTableType.equals(node.get("type"))
                && node.get("field") instanceof String field && StrUtil.isNotBlank(field)) {
            SubTableWidget widget = new SubTableWidget();
            widget.setField(field);
            widget.setTitle(node.get("title") instanceof String t ? t : null);

            if (node.get("props") instanceof Map<?, ?> propsMap) {
                Map<String, Object> props = (Map<String, Object>) propsMap;
                // ⚠️ 键名用 min/max，与设计器内核一致。
                // 内核的子表控件属性面板本来就有这两项，用自造的 minRows/maxRows
                // 会读不到用户配的值 —— 界面上限制了，服务端却放行，
                // 这种「配了但不生效」比没有校验更糟
                if (props.get("min") instanceof Number n) {
                    widget.setMinRows(n.intValue());
                }
                if (props.get("max") instanceof Number n) {
                    widget.setMaxRows(n.intValue());
                }
                // 明细列：子表控件的内部规则挂在 props.rule 下
                if (props.get("rule") instanceof List<?> innerRules) {
                    for (Object inner : innerRules) {
                        if (inner instanceof Map<?, ?> innerMap) {
                            collectColumn((Map<String, Object>) innerMap, widget);
                        }
                    }
                }
            }
            result.add(widget);
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
    private void collectColumn(Map<String, Object> node, SubTableWidget widget) {
        if (node.get("field") instanceof String field && StrUtil.isNotBlank(field)) {
            SubTableColumn column = new SubTableColumn();
            column.setField(field);
            column.setTitle(node.get("title") instanceof String t ? t : null);
            column.setRequired(Boolean.TRUE.equals(node.get("$required")));
            widget.getColumns().add(column);
        }
        // 明细列也可能被放在栅格里
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collectColumn((Map<String, Object>) childMap, widget);
                }
            }
        }
    }

    // ==================== 模型 ====================

    /**
     * 一个子表控件
     */
    @Data
    public static class SubTableWidget {

        private String field;
        private String title;
        /**
         * 最少行数，null 或 0 表示不限
         */
        private Integer minRows;
        /**
         * 最多行数，null 或 0 表示不限（仍受 {@link #HARD_MAX_ROWS} 约束）
         */
        private Integer maxRows;
        private final List<SubTableColumn> columns = new ArrayList<>();

        /**
         * 报错时用的展示名。有标题用标题 —— 报字段 ID 用户看不懂
         */
        public String label() {
            return StrUtil.isNotBlank(title) ? title : field;
        }

    }

    /**
     * 子表里的一列
     */
    @Data
    public static class SubTableColumn {

        private String field;
        private String title;
        private boolean required;

        public String label() {
            return StrUtil.isNotBlank(title) ? title : field;
        }

    }

    /**
     * 校验结果
     */
    @Data
    public static class ValidationResult {

        private final List<String> errors = new ArrayList<>();

        public void addError(String message) {
            errors.add(message);
        }

        public boolean isPassed() {
            return errors.isEmpty();
        }

        /**
         * 汇总成一条消息
         *
         * 一次性把所有问题列出来，而不是报第一条就返回 —— 用户改完一处再提交
         * 又被拦下一处，来回好几轮
         */
        public String toMessage() {
            return String.join("；", errors);
        }

    }

}
