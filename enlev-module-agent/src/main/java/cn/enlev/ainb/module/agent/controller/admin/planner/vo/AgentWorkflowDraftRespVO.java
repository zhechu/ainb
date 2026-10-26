package cn.enlev.ainb.module.agent.controller.admin.planner.vo;

import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Workflow DSL 草稿预览（增量7b）
 *
 * <p>聊天里那张待确认草稿卡片的<b>数据源</b>。前端拿 {@code workflow_def_id} 调
 * {@code GET /agent/planner/workflow-draft} 取回本 VO，再 {@code JSON.parse(dsl)} 取
 * {@code nodes[]} 渲染只读节点预览。<b>无论直播还是刷新页面，都走这一条路径</b>（单一装配路径）——
 * WORKFLOW_DRAFT 事件只带 defId/code/name/summary/warnings/rounds，不带 DSL，避免两条组装逻辑漂移。
 *
 * <p>⚠️ {@code rounds}（用掉几轮自修正）刻意<b>只</b>在事件里、不在本 VO 上：它是这一次生成过程的
 * 属性，没有落库，刷新后凭 defId 重建卡片时拿不到。要让它可恢复就得给 {@code agent_workflow_def}
 * 加一列存「上次 AI 生成用了几轮」—— 为了一个软信心信号加一列不值得。故前端必须容忍它为 null。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow DSL 草稿预览")
@Data
public class AgentWorkflowDraftRespVO {

    @Schema(description = "Workflow 定义编号", example = "555")
    private Long defId;

    @Schema(description = "业务编码（确认并运行时用它 publish / run）", example = "wf_plan_lz4k2a_3")
    private String code;

    @Schema(description = "流程名", example = "查询数据接口")
    private String name;

    @Schema(description = "Workflow DSL JSON 原文，前端 parse 出 nodes 渲染预览")
    private String dsl;

    @Schema(description = "定义状态：DRAFT / PUBLISHED / DISABLED", example = "DRAFT")
    private String status;

    @Schema(description = "版本号", example = "1")
    private Integer version;

    /**
     * 变更清单（S2-f）：草稿卡在节点预览之上多一块「变更预演」折叠区
     *
     * <p>AI 生成的草稿最需要的恰恰是这个：节点预览说的是「流程长什么样」，而用户真正要决定的是
     * 「点了确认并运行之后，平台上会多出/改掉什么」。建表这类 IRREVERSIBLE 的动作，
     * 在确认卡上就该看见，而不是等运行完才发现多了一张表。
     *
     * <p>🔴 <b>{@code null} 不等于「本次不产生任何写操作」</b>：null 表示清单没能生成
     * （原因在后端日志里），渲染方必须显示「清单不可用」，不能显示空清单那句 headline。
     * 理由与 {@code AgentWorkflowDefValidateRespVO#manifest} 同一条。
     *
     * <p>⚠️ 本端点的权限是 {@code agent:assistant:use} 而不是 {@code agent:workflow:query}，
     * 但这里不构成越权：同一个响应已经把完整 DSL 原文给出去了，清单只是把它翻译成人话，
     * 没有暴露任何用户本来看不到的东西。
     */
    @Schema(description = "变更清单：这份草稿若发布会改平台上的什么（null 表示清单不可用，不是「没有变更」）")
    private ChangeManifest manifest;

}
