package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 管理后台 - Workflow DSL 校验 Response VO（增量8 / P4 第二刀：新建-编辑表单）
 *
 * <h3>errors 与 warnings 的语义差别</h3>
 * <ul>
 *   <li>{@link #errors}：<b>阻断</b>性问题（节点 id 非法/重复、边端点解析不到、缺 START 入口/END 出口、
 *       深层建图失败等）。{@link #passed} 为假即「不能发布成可执行流程」，前端应显式列出、并阻止/警告保存。</li>
 *   <li>{@link #warnings}：<b>不阻断</b>但会被后端「静默改写」或「配了可能不按预期生效」的写法
 *       （如 outputVar 未在 variables[] 声明）。前端提示即可，不拦保存。</li>
 * </ul>
 *
 * <p>文案沿用 {@code AgentValidateResult.toFeedback} 的纪律——每条都说清「怎么改」，因为它们最初是写给
 * LLM 自修正看的，如今也直接展示给编排者，同样受益。
 *
 * <p>S2-f 起多带一个 {@link #manifest}：体检回答「这 DSL 写得对不对」，清单回答「它跑起来会改什么」，
 * 两者是同一个「保存/发布前先看清楚」动作的两半，故一次拿全。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow DSL 校验 Response VO")
@Data
public class AgentWorkflowDefValidateRespVO {

    @Schema(description = "是否通过（无阻断性 error）", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    private Boolean passed;

    @Schema(description = "阻断性错误：必须修正才能发布")
    private List<String> errors;

    @Schema(description = "警告：不阻断，建议调整")
    private List<String> warnings;

    /**
     * 变更清单（S2-f）：体检结果与影响面预览一次拿全，设计器少一次往返
     *
     * <p>🔴 <b>{@code null} 不等于「本次不产生任何写操作」</b>。null 的含义是「这份 DSL 没能生成清单」
     * （最常见的原因是 DSL 连 JSON 都 parse 不出来 —— 那种情况 {@link #errors} 里已经写着原因了），
     * 前端必须渲染成「清单不可用」，<b>不能</b>渲染成空清单的那句 headline，否则等于告诉用户
     * 「放心点同意」。要一份一定能拿到的清单请单独调 {@code POST /agent/workflow-def/manifest}，
     * 那个端点失败会报错而不是给 null。
     *
     * <p>⚠️ 与 {@code passed} <b>不是</b>一回事：DSL 有结构问题时清单照出（能认多少认多少，
     * 认不出来的落进 {@code unresolved[]}），因为「这流程跑起来会改什么」恰恰是修错误时最需要看的上下文。
     *
     * <p>清单里可能含干跑结论，而干跑会打 infra/bpm 的只读接口 —— 故本字段让 {@code /validate}
     * 从「纯静态、不联网」变成「可能联网」。infra 不通时干跑落 {@code NOT_PROBED}（黄色「本次没校验」），
     * 不会让本字段变 null。
     */
    @Schema(description = "变更清单：这流程若发布/执行会改平台上的什么（null 表示清单不可用，不是「没有变更」）")
    private ChangeManifest manifest;

}
