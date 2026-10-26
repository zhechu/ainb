package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentPlannerAdoptionStatRespVO;

/**
 * 规划采纳率埋点 Service（迭代七 B5-3 / B5-8）
 *
 * <h3>它解决什么</h3>
 * 把「AI 起草的 Workflow 草稿 → 用户确认并运行」这条链留痕，回答「采纳率多少、采纳前要不要人改、改几处」。
 * 详见 {@link cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentPlannerAdoptionDO} 的类注释。
 *
 * <h3>🔴 全程 best-effort：埋点绝不拖垮主流程</h3>
 * 生成草稿、确认发布都是<b>主流程</b>；采纳率是<b>附加观测</b>。埋点写库失败（表没建、DDL 没跑、约束撞了）
 * 只该记日志，绝不能让「用户点确认并运行」或「AI 生成草稿」因此抛错。故两个 record* 方法内部全程 try/catch。
 * 与 {@link AgentFailureAlertService} 同一哲学。
 *
 * <h3>🔴 埋点范围：只盖「AI 生成 + 聊天确认并运行」，不盖管理页手写流程</h3>
 * {@link #recordGenerated} 只挂在 AI 草稿的两条生成路径（plan-workflow 端点、聊天 BUILD 意图）；
 * {@link #recordAdopted} 只挂在「确认并运行」的发布路径（publish-workflow 端点、plan-workflow 的可选自动发布）。
 * 管理页「新增」的手写流程、管理页列表上的「发布」按钮都<b>不</b>埋点 —— 那不是「规划采纳」，
 * 数进来会把采纳率稀释成无意义的数字。口径详见各调用点注释。
 *
 * @author 走进新时代
 */
public interface AgentPlannerAdoptionService {

    /**
     * 记一次「AI 生成草稿」：INSERT 一行，冻结基线 DSL。
     *
     * @param code        AI 生成的流程编码（{@code wf_plan_*}）
     * @param defId       落的草稿定义编号（可空，仅留痕）
     * @param baselineDsl AI 原样产出的 DSL（确认时比对用）
     */
    void recordGenerated(String code, Long defId, String baselineDsl);

    /**
     * 记一次「确认并运行」：按 code 找回待确认行，比对基线与当前 DSL，落 hand_modified / edit_count，置 adopted。
     *
     * <p>找不到待确认行（不是 AI 生成的流程、或已确认过）→ 静默 no-op（best-effort）。
     *
     * @param code       被确认发布的流程编码
     * @param currentDsl 发布那一刻的当前 DSL（可能已被 updateDef 手改过）
     */
    void recordAdopted(String code, String currentDsl);

    /**
     * 采纳率统计（B5-8 管理页出的那个数字）。当前租户维度，含原始计数（供只读 SQL 复核）与派生比率。
     */
    AgentPlannerAdoptionStatRespVO getAdoptionStat();

}
