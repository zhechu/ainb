package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - Workflow 定义 新增/修改 Request VO（增量8 / P4 管理页）
 *
 * <h3>🔴 修改仅对 DRAFT 生效</h3>
 * 已发布/已停用的定义是「执行实例绑定的版本快照」，就地改其 DSL 会让在途实例 resume 时对不上节点。
 * 要改已发布流程，请基于它新建一个版本（{@code Service.updateDef} 对非 DRAFT 抛 {@code WORKFLOW_DEF_NOT_DRAFT}）。
 *
 * <h3>DSL 结构校验的边界</h3>
 * 本 VO 只做字段级 Bean 校验（非空/长度/编码格式）。整份 DSL 的<b>结构</b>校验（节点类型存在、边端点可解析、
 * 变量声明一致）由 {@code WorkflowDslValidator} 负责——当前唯一的生产者 plan-as-workflow 生成器已在落库前校验过。
 * 管理页的可视化编辑器（后续刀）会在保存时接同一校验器；本刀 create/update 落库前经 {@code WorkflowTriggerExtractor}
 * 解析 DSL（触发器非法即抛 {@code WORKFLOW_TRIGGER_CONFIG_INVALID}），提供基础的「可解析」守卫。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow 定义 新增/修改 Request VO")
@Data
public class AgentWorkflowDefSaveReqVO {

    /**
     * 业务编码的合法形状（= {@code agent_workflow_def.code} 列的规矩，也 = DSL 顶层 {@code id} 的规矩）
     *
     * <p>🔴 public 是为了让 {@code AgentWorkflowDefCopyReqVO} 引它而不是抄一份（方案 §2-D7）：
     * 复制出来的是同一张表里的一行，两处正则一旦漂移，表现就是「新增能存的编码、复制时被拒」
     * （或反过来，复制出一份库里存不下的编码）。注解值要求编译期常量，故它是 {@code static final String}
     * 而不是一个校验方法 —— 与 {@code ToolNodeExecutor.DEFAULT_OUTPUT_VAR} 那条 public 同一个理由。
     */
    public static final String CODE_PATTERN = "^[a-z][a-z0-9_]{1,63}$";

    /** {@link #CODE_PATTERN} 的驳回文案，与正则同源，故也必须只有一份 */
    public static final String CODE_PATTERN_MESSAGE =
            "业务编码必须以小写字母开头，仅含小写字母/数字/下划线，长度 2~64";

    @Schema(description = "编号（新增时为空，修改时必填）", example = "1")
    private Long id;

    @Schema(description = "业务编码，租户内同一 code 多版本共存；= DSL 的 id",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "wf_plan_customer")
    @NotBlank(message = "业务编码不能为空")
    @Pattern(regexp = CODE_PATTERN, message = CODE_PATTERN_MESSAGE)
    private String code;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "客户跟进流程")
    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称长度不能超过 128")
    private String name;

    @Schema(description = "Workflow DSL v1 JSON 原文", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "DSL 不能为空")
    private String dsl;

    /**
     * 描述
     *
     * 🔴 本 VO 刻意没有 remark：description 已承担「说明这条流程是干什么的」，
     * 再加一个语义重叠的备注只会让表单多一个没人填的框（apply 前实测 43 行、0 行非空）。
     */
    @Schema(description = "描述", example = "表单提交后自动跟进")
    @Size(max = 500, message = "描述长度不能超过 500")
    private String description;

}
