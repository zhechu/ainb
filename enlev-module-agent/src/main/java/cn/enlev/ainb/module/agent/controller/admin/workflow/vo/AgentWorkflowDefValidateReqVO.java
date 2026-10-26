package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理后台 - Workflow DSL 校验 Request VO（增量8 / P4 第二刀：新建-编辑表单）
 *
 * <h3>为什么 {@code dsl} 不加 {@code @NotBlank}</h3>
 * 校验端点的职责是「把这份 DSL 的问题结构化地告诉编排者」，而不是「拒收非法输入」。
 * 空串/非法 JSON 本身就是一种要被诊断的情况——由 {@code DslParser} 捕获成一条 error 返回
 * （{@code passed=false}），而非在 Bean 校验层直接 400，这样前端拿到统一的 {@code {passed,errors,warnings}}
 * 形状，无论问题出在「JSON 格式」还是「结构」都能内联展示。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow DSL 校验 Request VO")
@Data
public class AgentWorkflowDefValidateReqVO {

    @Schema(description = "待校验的 Workflow DSL v1 JSON 原文", requiredMode = Schema.RequiredMode.REQUIRED)
    private String dsl;

    /**
     * 定义编号，<b>可选</b>：只用来取「这条流程名下真跑过几次」，据此给已落地的撞车降一档（台账 #81）
     *
     * <h3>🔴 与 {@code /manifest} 的 {@code id} 语义<b>不同</b>，别把那边的互斥规则搬过来</h3>
     * {@code /manifest} 里 {@code dsl} 与 {@code id} 是「预览哪一份」的二选一，都给就抛
     * {@code WORKFLOW_MANIFEST_SOURCE_INVALID} —— 因为挑错一份会让人据一份不是自己的清单点同意。
     * 这里 {@code dsl} 永远是<b>被打分的对象</b>，{@code id} 只是<b>身份</b>：两者同时给是<b>正常且必要</b>的用法，
     * 发布预检传的正是「手上这一版的 dsl」+「它所在行的 id」。
     * 若照搬互斥规则，预检就得二选一：只给 dsl 则永远拿不到执行史（降档在 UI 上永远触发不了），
     * 只给 id 则体检的是库里那一版而不是用户正要发布的那一版。
     *
     * <p>不传 / 传了一个库里不存在的 id ⟹ 按「没跑过」算，一档都不降（fail-close，不抛「不存在」：
     * 只读打分端点不该要求先证明这行存在）。
     */
    @Schema(description = "定义编号，可选；只用于取执行史以给已落地的撞车降档。与 dsl 可同时给", example = "1024")
    private Long id;

}
