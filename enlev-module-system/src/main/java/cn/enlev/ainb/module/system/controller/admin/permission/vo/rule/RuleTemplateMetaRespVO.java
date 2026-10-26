package cn.enlev.ainb.module.system.controller.admin.permission.vo.rule;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 规则模板元数据响应 VO
 *
 * <p>规则编辑器（{@code system/rule}）据此<b>动态渲染</b>模板下拉、参数向导、
 * 条件树构建器，避免前端硬编码模板/槽位/操作符而与后端枚举漂移
 * （历史上前端就漏过 DEPT_IN 模板）。
 *
 * <p>数据来源：{@code RuleTemplateEnum} + {@code RuleTemplateMetaRegistry}
 * + {@code PermissionSlotEnum} + {@code ConditionOperatorEnum}
 * + {@code BuiltInVariableProvider.VARIABLE_METADATA}，全部静态组装，不查库。
 */
@Schema(description = "管理后台 - 规则模板元数据响应")
@Data
public class RuleTemplateMetaRespVO {

    @Schema(description = "模板列表")
    private List<TemplateMeta> templates;

    @Schema(description = "语义槽位列表")
    private List<SlotMeta> slots;

    @Schema(description = "条件操作符列表")
    private List<OperatorMeta> operators;

    @Schema(description = "运行时变量列表（条件树值可引用）")
    private List<VariableMeta> variables;

    @Schema(description = "模板元数据")
    @Data
    public static class TemplateMeta {

        @Schema(description = "模板代码，如 BUILT_IN_OWNER_ONLY")
        private String code;

        @Schema(description = "模板显示名，如 仅自己的数据")
        private String displayName;

        @Schema(description = "分组：basic 基础 / derived 派生 / advanced 高级")
        private String group;

        @Schema(description = "面向业务人员的白话说明")
        private String businessDescription;

        @Schema(description = "依赖的语义槽位名；为空表示取决于参数")
        private List<String> requiredSlots;

        @Schema(description = "参数录入规格；无参数模板为空列表")
        private List<ParamSpec> paramSpecs;

    }

    @Schema(description = "参数录入规格")
    @Data
    public static class ParamSpec {

        @Schema(description = "params JSON 里的键，如 allowedStatuses")
        private String key;

        @Schema(description = "子编辑器种类：STATUS_LIST / DEPT_MULTI / SLOT / TEXT / CONDITION_TREE")
        private String kind;

        @Schema(description = "中文标签")
        private String label;

        @Schema(description = "是否必填")
        private Boolean required;

        @Schema(description = "值类型：number / string；无则空")
        private String valueType;

    }

    @Schema(description = "语义槽位元数据")
    @Data
    public static class SlotMeta {

        @Schema(description = "槽位名，如 owner")
        private String slot;

        @Schema(description = "槽位说明")
        private String description;

    }

    @Schema(description = "条件操作符元数据")
    @Data
    public static class OperatorMeta {

        @Schema(description = "操作符代码，如 EQ")
        private String code;

        @Schema(description = "中文名，如 等于")
        private String label;

        @Schema(description = "是否一元操作符（无需值），如 IS_NULL")
        private Boolean unary;

        @Schema(description = "是否多值操作符（值为集合），如 IN")
        private Boolean multiValue;

    }

    @Schema(description = "运行时变量元数据")
    @Data
    public static class VariableMeta {

        @Schema(description = "变量名，条件树里以 :name 引用，如 currentUserId")
        private String name;

        @Schema(description = "中文名")
        private String label;

        @Schema(description = "是否集合类型（仅集合变量可用于 IN / NOT_IN）")
        private Boolean collection;

    }

}
