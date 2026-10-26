package cn.enlev.ainb.module.bpm.framework.flowable.core.candidate;

import cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmTaskCandidateStrategyEnum;

/**
 * 候选策略 → 授权主体类型的映射（v9 §7.2「候选策略统一输出主体集合」）
 *
 * <h3>为什么要有这层映射</h3>
 *
 * 十六种候选策略最终都输出一组 userId，但它们的<b>来源语义</b>完全不同：
 *
 * <pre>
 * ROLE       → 这些人是「某个角色的成员」
 * DEPT_LEADER→ 这些人是「某个部门的负责人」
 * USER       → 这些人是「被点名的」
 * EXPRESSION → 这些人是「一段表达式算出来的」
 * </pre>
 *
 * 只留 userId 的话，这层语义就丢了。而它恰恰是<b>离职交接</b>唯一能依赖的信息：
 *
 * <ul>
 *   <li>张三因「研发部负责人」被选中 → 换了负责人就自动有解，<b>不需要人工介入</b></li>
 *   <li>张三因「被点名」被选中 → 没有任何规则能推出接手人，<b>必须人工指定</b></li>
 * </ul>
 *
 * 两者的处理方式完全相反，而只看 userId 是分不出来的 ——
 * 结果就是离职交接只能把所有任务一股脑转给一个人，转完还是错的。
 *
 * <h3>⚠️ 主体类型不等于「授权主体」</h3>
 *
 * 这里标的是<b>候选人从哪来</b>，与 {@code sys_subject} 的授权主体是两回事。
 * 比如 {@code START_USER}（发起人自己）映射成 USER —— 它确实产出一个具体的人，
 * 但「发起人」这个身份并不是一个可授权主体。
 *
 * @author 走进新时代
 */
public final class BpmCandidateSubjectTypes {

    public static final String USER = "USER";
    public static final String ROLE = "ROLE";
    public static final String DEPT = "DEPT";
    public static final String POST = "POST";
    public static final String GROUP = "GROUP";
    /**
     * 算不出稳定来源的：表达式、表单字段、运行时自选
     *
     * ⚠️ 这一类<b>不能重算</b> —— 表达式依赖流程变量、自选依赖当时那个人的选择，
     * 离开当时的上下文重算出来的结果是错的。离职交接遇到它们只能人工指定
     */
    public static final String DYNAMIC = "DYNAMIC";

    private BpmCandidateSubjectTypes() {
    }

    /**
     * 求某个候选策略产出的主体类型
     */
    public static String of(BpmTaskCandidateStrategyEnum strategy) {
        if (strategy == null) {
            return DYNAMIC;
        }
        return switch (strategy) {
            case ROLE -> ROLE;
            case POST -> POST;
            case USER_GROUP -> GROUP;
            // 部门系：成员、负责人、多级负责人，来源都是「部门」
            case DEPT_MEMBER, DEPT_LEADER, MULTI_DEPT_LEADER_MULTI,
                 START_USER_DEPT_LEADER, START_USER_DEPT_LEADER_MULTI,
                 FORM_DEPT_LEADER -> DEPT;
            // 点名到人：参数里就是 userId
            case USER, START_USER, ASSIGN_EMPTY -> USER;
            // 运行时才知道、或依赖流程变量的，一律归为不可重算
            case APPROVE_USER_SELECT, START_USER_SELECT, FORM_USER, EXPRESSION -> DYNAMIC;
        };
    }

    /**
     * 该主体类型算出来的候选人，能否在组织变动后<b>自动重算</b>
     *
     * {@link #DYNAMIC} 不能 —— 见该常量的注释
     */
    public static boolean recalculable(String subjectType) {
        return !DYNAMIC.equals(subjectType);
    }

}
