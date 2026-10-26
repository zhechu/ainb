package cn.enlev.ainb.module.agent.dal.dataobject.workflow;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 规划采纳率埋点 DO（迭代七 B5-3）：一行 = 一份 AI 生成的 Workflow 草稿
 *
 * <h3>它解决什么</h3>
 * plan-as-workflow 生成器产出 AI 草稿后，用户可能原样确认、也可能手改几处再确认。
 * 本表把「生成 → 确认」这条链留痕：生成时 INSERT（冻结 {@link #baselineDsl} 基线），
 * 确认时 UPDATE（比对基线与当前 DSL，得 {@link #handModified} / {@link #editCount}，置 {@link #adopted}）。
 *
 * <h3>🔴 基线为什么必须在生成时冻下来</h3>
 * 草稿 DSL 被 {@code updateDef} <b>就地改写</b>（同 id、同 version），改完 AI 那份原文就没了。
 * 确认时手里只剩「改过的当前 DSL」，没有「AI 原本写的」可比 —— 故基线只能在生成那一刻存进本列。
 *
 * <h3>口径</h3>
 * 采纳率 = adopted 行 / 总行；手改率 = hand_modified 行 / adopted 行。见
 * {@code AgentPlannerAdoptionServiceImpl#getAdoptionStat}。
 *
 * @author 走进新时代
 */
@TableName("agent_planner_adoption")
@KeySequence("agent_planner_adoption_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentPlannerAdoptionDO extends TenantBaseDO {

    private Long id;

    /** AI 生成的流程编码（形如 {@code wf_plan_*}）。确认（publish）按 code 找回本行 */
    private String workflowCode;

    /** 生成时落的草稿定义编号（{@code agent_workflow_def.id}），仅留痕/排障用 */
    private Long workflowDefId;

    /**
     * AI 原样产出的 DSL 基线。确认时与「当前 DSL」比对算手改，故存原文（mediumtext，同 {@code workflow_def.dsl}）
     */
    private String baselineDsl;

    /** 是否已「确认并运行」（发布）。生成时 false，确认时翻 true */
    private Boolean adopted;

    /**
     * 确认时是否手改过 DSL。
     * 🔴 {@code null} = 尚未确认（还没得比）；{@code false} = 原样采纳；{@code true} = 改过。
     * 用 Boolean（可空）而不是 boolean：三态里「尚未确认」这一态必须能与「确认了但没改」区分开。
     */
    private Boolean handModified;

    /**
     * 手改了几处：基线与当前 DSL 各自规范化成「键排序 + 缩进」的 JSON 后按行做 LCS 差异计数。
     * 🔴 {@code null} = 尚未确认。口径与诚实说明见 {@code AgentPlannerAdoptionServiceImpl#countEdits}。
     */
    private Integer editCount;

    /** 生成时刻 */
    private LocalDateTime generateTime;

    /** 确认（发布）时刻；尚未确认时为 null */
    private LocalDateTime adoptTime;

}
