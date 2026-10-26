package cn.enlev.ainb.module.infra.framework.formdesign.core.binding;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 从表关联配置（一对一，只读）
 *
 * 用于列表展示关联字段，如「客户列表需要展示业务员姓名」。
 * 执行时会 LEFT JOIN 关联表，只读不写 —— 写入路径不涉及从表。
 * 这条约束大幅降低了写入路径的复杂度与事故面。
 *
 * @author 走进新时代
 */
@Data
public class JoinTableBinding {

    @Schema(description = "关联表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "system_users")
    private String table;

    @Schema(description = "表别名，用于 SQL 和列引用", requiredMode = Schema.RequiredMode.REQUIRED, example = "u")
    private String alias;

    @Schema(description = "关联类型", example = "LEFT")
    private String joinType = "LEFT";

    /**
     * ON 条件：left.column = right.column 的形式
     * 支持多列关联。
     */
    @Schema(description = "ON 条件列表")
    private List<JoinCondition> on;

    /**
     * 要 SELECT 出来供列表展示的列。
     * key 为列名，value 为 AS 后的别名。
     */
    @Schema(description = "SELECT 列配置", example = "{ \"nickname\": \"ownerName\" }")
    private List<SelectColumn> selectColumns;

    @Data
    public static class JoinCondition {
        @Schema(description = "左表列（带表名或别名）", example = "biz_customer.owner_id")
        private String left;

        @Schema(description = "右表列（带别名）", example = "u.id")
        private String right;
    }

    @Data
    public static class SelectColumn {
        @Schema(description = "物理列名", example = "nickname")
        private String column;

        @Schema(description = "AS 后的别名", example = "ownerName")
        private String as;
    }

}
