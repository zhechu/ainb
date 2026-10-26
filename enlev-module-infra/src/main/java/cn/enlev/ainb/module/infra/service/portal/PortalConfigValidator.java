package cn.enlev.ainb.module.infra.service.portal;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.PORTAL_CONFIG_INVALID;

/**
 * Validates persisted portal content before it is saved or published.
 */
@Component
public class PortalConfigValidator {

    public static final int SCHEMA_VERSION = 1;
    private static final int COLUMNS = 24;
    private static final int MAX_CONTENT_LENGTH = 256 * 1024;
    private static final int MAX_ITEMS = 50;
    private static final int MAX_TOTAL_ITEMS = 100;
    private static final int MAX_NESTING_DEPTH = 4;

    public void validate(String content) {
        if (StrUtil.isBlank(content)) {
            throw exception(PORTAL_CONFIG_INVALID, "配置不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw exception(PORTAL_CONFIG_INVALID, "配置不能超过256KB");
        }

        Map<String, Object> root;
        try {
            root = JsonUtils.parseObject(content, Map.class);
        } catch (RuntimeException e) {
            throw exception(PORTAL_CONFIG_INVALID, "必须是合法的 JSON");
        }
        if (root == null) {
            throw exception(PORTAL_CONFIG_INVALID, "根节点不能为空");
        }

        Object schemaVersion = root.get("schemaVersion");
        if (!(schemaVersion instanceof Number)
                || ((Number) schemaVersion).intValue() != SCHEMA_VERSION) {
            throw exception(PORTAL_CONFIG_INVALID, "不支持的 schemaVersion");
        }
        validateInteger(root, "columns", COLUMNS, COLUMNS);
        validateInteger(root, "rowHeight", 1, 100);
        validateInteger(root, "gap", 0, 64);

        Object itemsValue = root.get("items");
        if (!(itemsValue instanceof List<?> items)) {
            throw exception(PORTAL_CONFIG_INVALID, "items 必须是数组");
        }
        if (items.size() > MAX_ITEMS) {
            throw exception(PORTAL_CONFIG_INVALID, "组件数量不能超过50个");
        }

        Set<String> ids = new HashSet<>();
        int[] itemCount = {0};
        for (Object item : items) {
            validateItem(item, ids, 0, itemCount);
        }
    }

    private void validateItem(Object itemValue, Set<String> ids, int depth, int[] itemCount) {
        if (depth > MAX_NESTING_DEPTH) {
            throw exception(PORTAL_CONFIG_INVALID, "组件嵌套层级不能超过" + MAX_NESTING_DEPTH);
        }
        if (++itemCount[0] > MAX_TOTAL_ITEMS) {
            throw exception(PORTAL_CONFIG_INVALID, "门户控件总数不能超过" + MAX_TOTAL_ITEMS + "个");
        }
        if (!(itemValue instanceof Map<?, ?> item)) {
            throw exception(PORTAL_CONFIG_INVALID, "组件配置必须是对象");
        }

        String id = asString(item.get("id"));
        String type = asString(item.get("type"));
        if (StrUtil.isBlank(id) || id.length() > 64) {
            throw exception(PORTAL_CONFIG_INVALID, "组件 id 不合法");
        }
        if (!isSupportedType(type)) {
            throw exception(PORTAL_CONFIG_INVALID, "不支持的组件类型：" + type);
        }
        if (!(item.get("props") instanceof Map<?, ?> props)) {
            throw exception(PORTAL_CONFIG_INVALID, "组件 props 必须是对象");
        }
        if (!ids.add(id)) {
            throw exception(PORTAL_CONFIG_INVALID, "组件 id 不能重复：" + id);
        }

        int x = validateInteger(item, "x", 0, COLUMNS - 1);
        validateInteger(item, "y", 0, 10000);
        int w = validateInteger(item, "w", 1, COLUMNS);
        validateInteger(item, "h", 1, 1000);
        if (x + w > COLUMNS) {
            throw exception(PORTAL_CONFIG_INVALID, "组件不能超出画布宽度");
        }

        if ("card".equals(type)) {
            validateNestedItems(props.get("children"), ids, depth + 1, itemCount);
        } else if ("tabs".equals(type)) {
            Object tabsValue = props.get("tabs");
            if (tabsValue != null && !(tabsValue instanceof List<?>)) {
                throw exception(PORTAL_CONFIG_INVALID, "tabs 必须是数组");
            }
            if (tabsValue instanceof List<?> tabs) {
                for (Object tabValue : tabs) {
                    if (!(tabValue instanceof Map<?, ?> tab)) {
                        throw exception(PORTAL_CONFIG_INVALID, "tab 配置必须是对象");
                    }
                    validateNestedItems(tab.get("items"), ids, depth + 1, itemCount);
                }
            }
        }
    }

    private void validateNestedItems(Object value, Set<String> ids, int depth, int[] itemCount) {
        if (value == null) {
            return;
        }
        if (!(value instanceof List<?> items)) {
            throw exception(PORTAL_CONFIG_INVALID, "嵌套控件列表必须是数组");
        }
        if (items.size() > MAX_ITEMS) {
            throw exception(PORTAL_CONFIG_INVALID, "嵌套控件数量不能超过50个");
        }
        for (Object item : items) {
            validateItem(item, ids, depth, itemCount);
        }
    }

    private int validateInteger(Map<?, ?> item, String key, int min, int max) {
        Object value = item.get(key);
        if (!(value instanceof Number number)
                || number.doubleValue() != number.intValue()
                || number.intValue() < min
                || number.intValue() > max) {
            throw exception(PORTAL_CONFIG_INVALID, key + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return number.intValue();
    }

    private boolean isSupportedType(String type) {
        return "card".equals(type) || "tabs".equals(type)
                || "text".equals(type) || "image".equals(type) || "carousel".equals(type)
                || "video".equals(type) || "iframe".equals(type)
                || "commonFunc".equals(type)
                || "shortcut".equals(type) || "icon".equals(type)
                || "todo".equals(type) || "myTodo".equals(type)
                || "todoList".equals(type) || "doingList".equals(type)
                || "notice".equals(type) || "email".equals(type)
                || "dataBoard".equals(type) || "tableList".equals(type)
                || "chart.bar".equals(type) || "chart.line".equals(type)
                || "chart.pie".equals(type) || "chart.radar".equals(type)
                || "rankList".equals(type) || "timeAxis".equals(type)
                || "mapChart".equals(type) || "customEcharts".equals(type);
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
