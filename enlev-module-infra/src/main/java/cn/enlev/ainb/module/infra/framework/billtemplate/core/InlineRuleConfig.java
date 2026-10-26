package cn.enlev.ainb.module.infra.framework.billtemplate.core;

import lombok.Data;

import java.util.List;

/**
 * 内联单据规则配置
 *
 * 规则直接写在表单字段上（而不是引用一个单据模板），前后缀由若干「段」拼成，
 * 段可以是常量，也可以取自同表单其它字段的值。典型场景：
 * 单号形如 {@code 客户编号-20260906-001}，其中客户编号来自表单里的客户字段。
 *
 * <p>结构与 {@link cn.enlev.ainb.module.infra.dal.dataobject.billtemplate.BillTemplateDO}
 * 刻意保持一致的字段命名，两者最终都归一到 {@link BillNumberSpec}。
 *
 * @author 走进新时代
 */
@Data
public class InlineRuleConfig {

    /**
     * 前缀段列表，按顺序拼接
     */
    private List<RuleSegment> prefixSegments;

    /**
     * 后缀段列表，按顺序拼接
     */
    private List<RuleSegment> suffixSegments;

    /**
     * 生成方式：1-时间流水 2-随机数 3-UUID
     */
    private Integer genType;

    private String dateFormat;
    private Integer digit;
    private Integer startNumber;
    private Integer step;
    private String resetCycle;

    /**
     * 流水是否按前缀分组重置
     *
     * 内联模式默认开启：前缀取自「客户编号」这类字段时，期望的是每个客户各自从 1 开始，
     * 而不是全局一个计数器。用 Boolean 而非 boolean，才能区分「用户显式关掉」
     * 与「历史配置里根本没有这个字段」——后者要走默认值。
     */
    private Boolean groupByPrefix;

    private Integer randomDigit;
    private Integer randomType;

    /**
     * 是否按前缀分组，未显式配置时默认开启
     */
    public boolean groupByPrefixOrDefault() {
        return groupByPrefix == null || groupByPrefix;
    }

    /**
     * 前后缀里的一段
     */
    @Data
    public static class RuleSegment {

        /**
         * 取值来源
         *
         * 枚举 {@link cn.enlev.ainb.module.infra.enums.billtemplate.SegmentSourceEnum}
         */
        private Integer sourceType;

        /**
         * 常量来源时为字面值；字段来源时为被引用字段的 form-create field
         */
        private String value;

    }

}
