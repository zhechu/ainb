package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理后台 - 规划采纳率统计 Response VO（迭代七 B5-8）
 *
 * <h3>它解决什么</h3>
 * 管理页要「出一个数字」回答「AI 起草的流程到底被不被采纳、采纳前要不要人改」。
 * 本 VO 把 {@code agent_planner_adoption} 表数出来的几个计数与比率一次性下发。
 *
 * <h3>🔴 为什么把原始计数也下发，而不只给一个比率</h3>
 * 验收标准③要求「管理页采纳率数字与 {@code agent_planner_adoption} 行数<b>对得上</b>（只读 SQL 复核）」。
 * 只给一个百分比没法复核 —— 50% 可能是 1/2 也可能是 500/1000。故 {@link #generated}（= 表行数）
 * 与 {@link #adopted}、{@link #handModified} 三个<b>原始计数</b>一并下发，
 * 复核时拿 {@code SELECT COUNT(*) ...} 逐一对齐；比率只是它们的派生展示。
 *
 * <h3>🔴 比率为 null 的语义：分母为 0（还没有任何草稿），不是「0%」</h3>
 * 「一份都没生成过」与「生成了但一份没被采纳」是两件事，前者不该显示成刺眼的 0%。
 * 故分母为 0 时比率落 {@code null}，前端渲染成「暂无」而不是「0%」。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 规划采纳率统计 Response VO")
@Data
public class AgentPlannerAdoptionStatRespVO {

    @Schema(description = "AI 生成的草稿总数（= agent_planner_adoption 表行数，供只读 SQL 复核）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
    private Long generated;

    @Schema(description = "已「确认并运行」（发布）的草稿数", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "8")
    private Long adopted;

    @Schema(description = "确认时手改过 DSL 的草稿数（adopted 的子集）", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "3")
    private Long handModified;

    @Schema(description = "采纳率（%）= adopted / generated；generated 为 0 时为 null（前端渲染「暂无」）",
            example = "66.7")
    private BigDecimal adoptionRate;

    @Schema(description = "手改率（%）= handModified / adopted；adopted 为 0 时为 null", example = "37.5")
    private BigDecimal handModifiedRate;

}
