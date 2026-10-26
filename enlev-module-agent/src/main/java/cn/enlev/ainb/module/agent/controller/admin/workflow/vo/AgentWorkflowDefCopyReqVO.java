package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - Workflow 定义 复制 Request VO（S5-b / 方案 §3-L4 4.1）
 *
 * <h3>🔴 与「新增/修改」那个 VO 刻意分开，不复用</h3>
 * 复制<b>不接受 dsl 入参</b>：DSL 一律取源定义库里那一版，再由 {@code WorkflowCopyRewriter} 改写。
 * 若复用 {@code AgentWorkflowDefSaveReqVO}，前端就能把一份自己拼的 DSL 传进「复制」——
 * 那正是 G11 的形状（前端组装 DSL、与服务端各说各话），也是本次要把
 * {@code WorkflowForm.vue} 的「复制已有」改成调服务端的原因。
 *
 * <h3>code 的校验与新增<b>同一个常量</b></h3>
 * 引 {@link AgentWorkflowDefSaveReqVO#CODE_PATTERN}，不抄字面量（方案 §2-D7）：
 * 复制出来的是同一张表里的一行，两处正则一旦漂移，表现就是「新增能存的编码、复制时被拒」。
 * 名称/描述的长度上限仍是两份字面量 —— {@code @Size} 的 max 是 int，提成常量收益远小于噪声。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow 定义 复制 Request VO")
@Data
public class AgentWorkflowDefCopyReqVO {

    @Schema(description = "源定义编号（可为任意状态：草稿/已发布/已停用都能作为复制蓝本）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "源流程编号不能为空")
    private Long id;

    @Schema(description = "新业务编码，= 新 DSL 的 id；租户内不得与已有流程重名",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "wf_plan_customer_copy")
    @NotBlank(message = "业务编码不能为空")
    @Pattern(regexp = AgentWorkflowDefSaveReqVO.CODE_PATTERN,
            message = AgentWorkflowDefSaveReqVO.CODE_PATTERN_MESSAGE)
    private String newCode;

    @Schema(description = "新名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "客户跟进流程（副本）")
    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称长度不能超过 128")
    private String newName;

    @Schema(description = "新描述；留空表示沿用源流程的描述", example = "由「客户跟进流程」复制而来")
    @Size(max = 500, message = "描述长度不能超过 500")
    private String newDescription;

    /**
     * 是否自动改写冲突配置（建表名 / 表单编码 / BPM key / webhook path）
     *
     * <p>🔴 <b>{@code null} 按 {@code true} 处理</b>，服务端用 {@code !Boolean.FALSE.equals(…)} 判，
     * 不用字段初始值。理由：只有「显式关掉」才该关掉 —— 前端漏传这一格时若默认不改写，
     * 结果是两条流程去建同一张表（不可逆），而用户在结果对话框里只会看到一堆「未改写」。
     * 默认值的方向必须朝着「不会撞车」那一边。
     *
     * <p>关掉之后<b>照样逐格回显</b>：用户主动选择自己处理，那他更需要一份完整的待办清单。
     */
    @Schema(description = "是否自动改写冲突配置；缺省=true", example = "true")
    private Boolean rewriteConflicts;

}
