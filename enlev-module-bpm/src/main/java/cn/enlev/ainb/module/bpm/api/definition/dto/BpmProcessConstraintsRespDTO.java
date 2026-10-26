package cn.enlev.ainb.module.bpm.api.definition.dto;

import lombok.Data;

import java.util.List;

/**
 * 流程生成规则
 *
 * <h3>这个 DTO 存在的唯一理由：让规则只有一份</h3>
 * 调用方（AI 的流程校验器、prompt 组装）需要知道「节点类型有哪些」「候选人策略怎么传参」
 * 「审批方式哪个要额外给比例」。这些都能从 bpm 的枚举里读出来 ——
 * 但如果调用方自己抄一份常量，就会漂移，而漂移的两个方向都很糟：
 * 放行了后端会拒的（白烧一轮模型调用），或拦住了界面上明明能配的（AI 显得比人笨）。
 *
 * <p>与 {@code DataModelApi.getTableConstraints()} 是同一个套路。
 *
 * @author 走进新时代
 */
@Data
public class BpmProcessConstraintsRespDTO {

    /**
     * 审批节点的 approveType 必须取的值
     *
     * 🔴 <b>这是整套流程配置里唯一会「静默失效」的一条</b>：
     * {@code SimpleModelUtils.buildBpmnUserTask} 在 approveType 不等于该值时<b>提前 return</b>，
     * 于是候选人、审批方式、字段权限、按钮、超时、监听器<b>全部被丢弃</b>，
     * 不报错、不告警、部署照样成功，只有真正跑流程时才发现「这个节点谁都审批不了」。
     *
     * <p>所以校验器必须<b>硬拦</b>它，而不是给个警告。
     */
    private Integer requiredApproveType;

    /**
     * 允许的节点类型
     */
    private List<NodeType> nodeTypes;

    /**
     * 候选人策略
     */
    private List<CandidateStrategy> candidateStrategies;

    /**
     * 审批方式
     */
    private List<ApproveMethod> approveMethods;

    /**
     * 不允许用在<b>第一个</b>审批节点上的候选人策略
     *
     * 「审批人自选」要由上一个审批人来指定，而第一个节点前面没有审批人。
     * 部署时会被后端拒绝。
     */
    private List<Integer> firstNodeForbiddenStrategies;

    /**
     * 条件类型：1 条件表达式、2 条件规则
     */
    private List<ConditionType> conditionTypes;

    /**
     * bpm 侧使用的子表字段分隔符
     *
     * 值取自 {@code BpmTaskServiceImpl.SUB_FIELD_SEPARATOR}，而它自台账 #101(b) 起引用
     * infra 的 {@code FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR} —— 也就是<b>编译期就同源</b>，
     * 不再是「两份独立副本」。
     *
     * ⚠️ 但同源只在「一次完整构建之内」成立：编译期常量会被内联进 bpm 的 class（JLS 15.28），
     * 只重装 infra 的 jar 而没重装 bpm，两侧就会静默漂移。这种漂移单测结构上看不见，
     * 而它的表现是「子表字段配了权限却不生效」—— 从现象几乎不可能反推到分隔符不一致，
     * 所以仍然把这个值暴露出来，让诊断工具在运行期直接比对。
     */
    private String subFieldSeparator;

    @Data
    public static class NodeType {

        private Integer type;
        private String name;
        /**
         * 是否分支节点（其 conditionNodes 需要挂条件）
         */
        private Boolean branch;

    }

    @Data
    public static class CandidateStrategy {

        private Integer strategy;
        private String name;
        /**
         * 是否必须提供 candidateParam
         */
        private Boolean paramRequired;
        /**
         * candidateParam 的格式说明，写给模型看
         */
        private String paramFormat;

    }

    @Data
    public static class ApproveMethod {

        private Integer method;
        private String name;
        /**
         * 是否必须额外提供 approveRatio
         */
        private Boolean ratioRequired;

    }

    @Data
    public static class ConditionType {

        private Integer type;
        private String name;
        /**
         * 用法说明
         */
        private String usage;

    }

}
