package cn.enlev.ainb.module.infra.framework.formdesign.core.validation;

import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicRule;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 跨字段校验规则
 *
 * 表单级配置，存在 {@code infra_form_design.validate_rules} 里。
 * 与前端 {@code src/components/FormValidation/types.ts} 一一对应。
 *
 * <h3>语义：when 满足时，must 必须也满足</h3>
 *
 * 两个条件组都复用联动规则的 {@link LogicRule.ConditionGroup} —— 条件模型
 * （嵌套组、AND/OR、15 个操作符、右值可以取另一个字段）在 B1 已经建好并且
 * 双端行为一致。为跨字段校验再造一套条件模型，等于要再维护一份双端一致性。
 *
 * 设计方案里点名要支持的三种约束都能表达：
 * <table>
 *   <tr><th>约束</th><th>when</th><th>must</th></tr>
 *   <tr>
 *     <td>结束日期 ≥ 开始日期</td>
 *     <td>（空，恒真）</td>
 *     <td>结束日期 GE 开始日期（valueType=FIELD）</td>
 *   </tr>
 *   <tr>
 *     <td>A 填了则 B 必填</td>
 *     <td>A NOT_EMPTY</td>
 *     <td>B NOT_EMPTY</td>
 *   </tr>
 *   <tr>
 *     <td>A 与 B 不能同时为空</td>
 *     <td>A EMPTY</td>
 *     <td>B NOT_EMPTY</td>
 *   </tr>
 * </table>
 *
 * <h3>为什么不做成联动规则的一种动作</h3>
 *
 * 联动规则的动作是「改变字段状态或值」，它的结果是幂等的、可以反复求值到收敛。
 * 校验的结果是「拒绝这次提交」，没有 otherwise 分支、也不参与不动点迭代。
 * 混进同一个规则列表会让「规则跑完之后表单变成什么样」这件事不再可推理。
 *
 * @author 走进新时代
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CrossFieldRule {

    /**
     * 规则编号，表单内唯一
     */
    private String id;
    /**
     * 规则名称，仅用于设计器展示
     */
    private String name;
    /**
     * 是否启用。缺省视为启用
     */
    private Boolean enabled;

    /**
     * 前置条件。为空视为恒真 —— 即「这条约束永远要检查」
     */
    private LogicRule.ConditionGroup when;

    /**
     * 必须满足的条件
     *
     * 为空时整条规则跳过：一条没有断言的校验规则恒定通过，
     * 但它更可能是配置没写完。跳过并记日志，而不是当成恒真悄悄放过。
     *
     * <p>键名用 {@code must} 而不是 {@code assert}：后者是 Java 关键字，
     * 字段名得改叫 assertion 再挂 {@code @JsonProperty}，前后端两边名字就不一样了。
     */
    private LogicRule.ConditionGroup must;

    /**
     * 校验不通过时的提示
     *
     * 必填。自动生成的提示（「字段 A 与 字段 B 的关系不满足」）对用户毫无意义 ——
     * 只有配规则的人知道这条约束想表达什么业务含义。发布校验会拦住空提示。
     */
    private String message;

    /**
     * 关联字段
     *
     * 仅用于前端把错误提示挂到具体的表单项上（否则错误只能显示在表单顶部）。
     * 服务端不用它 —— 服务端只负责拒绝，不负责定位到控件。
     */
    private List<String> fields;

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public List<String> getFieldsOrEmpty() {
        return fields != null ? fields : Collections.emptyList();
    }

    /**
     * 规则是否可用：有断言、有提示
     */
    public boolean isUsable() {
        return must != null && !must.getItemsOrEmpty().isEmpty()
                && message != null && !message.isBlank();
    }

}
