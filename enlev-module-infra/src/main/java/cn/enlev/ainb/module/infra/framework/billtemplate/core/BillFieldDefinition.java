package cn.enlev.ainb.module.infra.framework.billtemplate.core;

import lombok.Data;

import java.util.Map;

/**
 * 表单里一个单据字段的定义
 *
 * 由 {@link FormBillFieldResolver} 从表单的 form-create 规则中解析而来，
 * 描述「这个字段的单号该怎么生成」。
 *
 * @author 走进新时代
 */
@Data
public class BillFieldDefinition {

    /**
     * 引用单据模板
     */
    public static final int RULE_MODE_TEMPLATE = 1;
    /**
     * 内联规则：规则直接写在字段上，前后缀可取自其它表单字段
     */
    public static final int RULE_MODE_INLINE = 2;

    /**
     * form-create 的字段名
     */
    private String field;

    /**
     * 字段标题，仅用于报错信息 —— 让用户看到「单据编号」而不是 {@code fc_a1b2c3}
     */
    private String title;

    /**
     * 规则模式
     */
    private Integer ruleMode;

    /**
     * 引用的单据模板编码，{@link #RULE_MODE_TEMPLATE} 时有值
     */
    private String templateCode;

    /**
     * 内联规则的原始配置，{@link #RULE_MODE_INLINE} 时有值
     *
     * 保留成 Map 而不是强类型对象：内联规则的结构随设计器演进，
     * 在解析阶段就绑死结构，会让历史表单在结构变更后直接解析失败。
     */
    private Map<String, Object> ruleConfig;

    /**
     * 所属子表的 form-create 字段名
     *
     * 为空表示该字段属于主表。由表绑定配置判定，而不是靠 form-create 的容器类型推断 ——
     * 容器类型（栅格、标签页、子表单…）种类多且会随设计器版本变化，
     * 而表绑定里的 {@code subTables[].fieldMapping} 是本模块自己的权威声明。
     */
    private String subTableField;

    public boolean isTemplateMode() {
        return ruleMode == null || ruleMode == RULE_MODE_TEMPLATE;
    }

    public boolean isInlineMode() {
        return ruleMode != null && ruleMode == RULE_MODE_INLINE;
    }

    /**
     * 是否属于主表
     */
    public boolean isMainTable() {
        return subTableField == null;
    }

    /**
     * 展示名，用于报错信息
     */
    public String displayName() {
        return title != null && !title.isBlank() ? title : field;
    }

}
