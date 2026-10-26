package cn.enlev.ainb.module.infra.service.portal;

import org.junit.jupiter.api.Test;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.PORTAL_CONFIG_INVALID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class PortalConfigValidatorTest {

    private final PortalConfigValidator validator = new PortalConfigValidator();

    @Test
    void validate_success() {
        assertDoesNotThrow(() -> validator.validate(content("[]")));
    }

    @Test
    void validate_nestedComponents() {
        String child = item("child-1", "text", "");
        String card = item("card-1", "card", "\"children\":[" + child + "]");
        assertDoesNotThrow(() -> validator.validate(content("[" + card + "]")));
    }

    @Test
    void validate_nestedTabs() {
        String child = item("child-1", "notice", "\"items\":[]");
        String tabs = item("tabs-1", "tabs", "\"tabs\":[{\"id\":\"tab-1\",\"label\":\"概览\",\"items\":[" + child + "]}]");
        assertDoesNotThrow(() -> validator.validate(content("[" + tabs + "]")));
    }

    @Test
    void validate_commonFunction() {
        String commonFunction = item("common-1", "commonFunc",
                "\"items\":[{\"label\":\"用户管理\",\"icon\":\"ep:user\",\"url\":\"/system/user\"}]");
        assertDoesNotThrow(() -> validator.validate(content("[" + commonFunction + "]")));
    }

    @Test
    void validate_systemPortalComponents() {
        String myTodo = item("my-todo-1", "myTodo", "\"items\":[]");
        String todoList = item("todo-list-1", "todoList", "\"pageSize\":7,\"items\":[]");
        String doingList = item("doing-list-1", "doingList", "\"pageSize\":7,\"items\":[]");
        String notice = item("notice-1", "notice", "\"items\":[],\"columns\":[]");

        assertDoesNotThrow(() -> validator.validate(content(
                "[" + myTodo + "," + todoList + "," + doingList + "," + notice + "]")));
    }

    @Test
    void validate_blankContent() {
        assertServiceException(() -> validator.validate(" "), PORTAL_CONFIG_INVALID, "配置不能为空");
    }

    @Test
    void validate_invalidJson() {
        assertServiceException(() -> validator.validate("{"), PORTAL_CONFIG_INVALID, "必须是合法的 JSON");
    }

    @Test
    void validate_unsupportedSchemaVersion() {
        assertServiceException(() -> validator.validate(contentWith("\"schemaVersion\": 2")),
                PORTAL_CONFIG_INVALID, "不支持的 schemaVersion");
    }

    @Test
    void validate_unsupportedComponent() {
        String item = "{\"id\":\"item-1\",\"type\":\"unknown\",\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"props\":{}}";
        assertServiceException(() -> validator.validate(content("[" + item + "]")),
                PORTAL_CONFIG_INVALID, "不支持的组件类型：unknown");
    }

    @Test
    void validate_componentOutOfBounds() {
        String item = "{\"id\":\"item-1\",\"type\":\"text\",\"x\":20,\"y\":0,\"w\":5,\"h\":1,\"props\":{}}";
        assertServiceException(() -> validator.validate(content("[" + item + "]")),
                PORTAL_CONFIG_INVALID, "组件不能超出画布宽度");
    }

    @Test
    void validate_columnsMustBe24() {
        String invalidColumns = "{\"schemaVersion\":1,\"columns\":12,\"rowHeight\":8,\"gap\":12,\"items\":[]}";
        assertServiceException(() -> validator.validate(invalidColumns),
                PORTAL_CONFIG_INVALID, "columns 必须在 24 到 24 之间");
    }

    @Test
    void validate_duplicateComponentId() {
        String item = "{\"id\":\"item-1\",\"type\":\"text\",\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"props\":{}}";
        assertServiceException(() -> validator.validate(content("[" + item + "," + item + "]")),
                PORTAL_CONFIG_INVALID, "组件 id 不能重复：item-1");
    }

    @Test
    void validate_duplicateNestedComponentId() {
        String child = item("item-1", "text", "");
        String card = item("card-1", "card", "\"children\":[" + child + "]");
        String sibling = item("item-1", "notice", "\"items\":[]");
        assertServiceException(() -> validator.validate(content("[" + card + "," + sibling + "]")),
                PORTAL_CONFIG_INVALID, "组件 id 不能重复：item-1");
    }

    @Test
    void validate_nestedDepthExceeded() {
        String nested = item("item-6", "text", "");
        for (int i = 5; i >= 0; i--) {
            nested = item("item-" + i, "card", "\"children\":[" + nested + "]");
        }
        String tooDeep = nested;
        assertServiceException(() -> validator.validate(content("[" + tooDeep + "]")),
                PORTAL_CONFIG_INVALID, "组件嵌套层级不能超过4");
    }

    @Test
    void validate_missingProps() {
        String item = "{\"id\":\"item-1\",\"type\":\"text\",\"x\":0,\"y\":0,\"w\":1,\"h\":1}";
        assertServiceException(() -> validator.validate(content("[" + item + "]")),
                PORTAL_CONFIG_INVALID, "组件 props 必须是对象");
    }

    @Test
    void validate_fractionalCoordinate() {
        String item = "{\"id\":\"item-1\",\"type\":\"text\",\"x\":1.5,\"y\":0,\"w\":1,\"h\":1,\"props\":{}}";
        assertServiceException(() -> validator.validate(content("[" + item + "]")),
                PORTAL_CONFIG_INVALID, "x 必须在 0 到 23 之间");
    }

    @Test
    void validate_tooManyItems() {
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) items.append(',');
            items.append("{\"id\":\"item-").append(i)
                    .append("\",\"type\":\"text\",\"x\":0,\"y\":")
                    .append(i).append(",\"w\":1,\"h\":1,\"props\":{}}");
        }
        items.append(']');
        assertServiceException(() -> validator.validate(content(items.toString())),
                PORTAL_CONFIG_INVALID, "组件数量不能超过50个");
    }

    private static String content(String items) {
        return "{\"schemaVersion\":1,\"columns\":24,\"rowHeight\":8,\"gap\":12,\"items\":"
                + items + "}";
    }

    private static String contentWith(String schemaVersion) {
        return "{" + schemaVersion + ",\"columns\":24,\"rowHeight\":8,\"gap\":12,\"items\":[]}";
    }

    private static String item(String id, String type, String props) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type
                + "\",\"x\":0,\"y\":0,\"w\":12,\"h\":4,\"props\":{" + props + "}}";
    }
}
