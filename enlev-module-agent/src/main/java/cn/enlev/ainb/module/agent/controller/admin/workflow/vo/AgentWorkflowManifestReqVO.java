package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理后台 - Workflow 变更清单 Request VO（S2-f：L2 的第一个对外出口）
 *
 * <h3>为什么 {@code dsl} / {@code id} 不加 Bean 校验注解</h3>
 * 这一对是「<b>恰好给一个</b>」的互斥关系，{@code @NotBlank} / {@code @NotNull} 表达不了
 * （加了就变成「两个都必填」）。故与 {@code AgentWorkflowDefValidateReqVO} 同一个处理方式：
 * 不在 Bean 校验层拦，由 {@code AgentWorkflowDefServiceImpl#buildManifest} 判完后抛
 * {@code WORKFLOW_MANIFEST_SOURCE_INVALID}，文案会说清是「都没给」还是「都给」。
 *
 * <p>🔴 两个都给时<b>刻意不挑一个</b>：清单是给人做发布/审批决定用的，
 * 「我传了手上的草稿、看到的却是库里那一版的影响面」比直接报错坏得多。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow 变更清单 Request VO")
@Data
public class AgentWorkflowManifestReqVO {

    @Schema(description = "待预览的 Workflow DSL v1 JSON 原文（与 id 二选一：设计器里改了还没保存的那一份）")
    private String dsl;

    @Schema(description = "Workflow 定义编号（与 dsl 二选一：看库里已落的那一版）", example = "1")
    private Long id;

    @Schema(description = "起算节点，通常是 hitl.* 节点；传了就只展示「从这一步往后」，裁掉多少记在 scope 里。"
            + "空白表示不裁剪", example = "approve_order")
    private String fromNodeId;

}
