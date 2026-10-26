package cn.enlev.ainb.module.agent.controller.admin.tooldef.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - 内置（已注册）工具维护 Request VO（2026-09-28 增量）
 *
 * <p>🔴「整份提交」语义：每次保存把该工具的<b>全部</b>可维护字段一起提交，覆盖行按此整份重写
 * （而非增量合并），与「全字段自由维护」的产品决策一致。各字段空值含义见字段注释。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 内置工具维护 Request VO")
@Data
public class AgentRegisteredToolUpdateReqVO {

    @Schema(description = "工具名（= @AgentTool#name / 注册表键），必填", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "datamodel_create")
    @NotEmpty(message = "工具名不能为空")
    @Size(max = 64, message = "工具名长度不能超过 64")
    private String toolName;

    @Schema(description = "展示名；留空=用工具名", example = "建表")
    @Size(max = 128, message = "展示名长度不能超过 128")
    private String displayName;

    @Schema(description = "工具说明；留空=覆盖为空说明", example = "根据需求创建数据表")
    private String description;

    @Schema(description = "分组；留空=无分组", example = "数据建模")
    @Size(max = 64, message = "分组长度不能超过 64")
    private String groupName;

    @Schema(description = "风险等级；留空=不覆盖（沿用代码声明）。可低于声明值（产品决策，Service log.warn 留痕）",
            example = "IRREVERSIBLE")
    private String riskLevel;

    @Schema(description = "调用所需权限串，逗号分隔；留空=调用只需登录（即删空代码声明的权限）",
            example = "infra:data-model:create")
    @Size(max = 500, message = "权限串长度不能超过 500")
    private String permissions;

    @Schema(description = "是否启用；必填", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;

    @Schema(description = "参数说明（进 prompt）；留空=覆盖为空", example = "tableName 表名；fields 字段列表")
    private String paramsSchema;

    @Schema(description = "备注（记录为何维护）", example = "本租户暂不开放建表")
    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;

    /**
     * 🔴 二次确认：本次维护若放松了安全约束（风险调低 / 权限删少），必须显式带 {@code true} 才会执行
     *
     * <p>不传或传 {@code false} 而实际发生了放松 ⟹ 抛 {@code AGENT_TOOL_OVERRIDE_RELAX_NEEDS_CONFIRM}，
     * 且<b>覆盖行与审计行都不写</b>（判定在 upsert 之前，见 {@code AgentToolOverrideServiceImpl#update}）。
     *
     * <p>为什么要后端也来一道、而不是只在前端弹框：前端框挡得住点鼠标的人，挡不住 curl、
     * 别的后台、将来的批量脚本。删权限是实打实的放松（网关读的就是生效权限串，删空后 AI 只需登录就能调），
     * 这个后果不该由「调用方走的是哪条路」决定要不要看见。
     *
     * <p>它<b>不是</b>「禁止放松」的后门开关：带上 {@code true} 就能过，产品决策要的就是保留放松能力
     * （迭代四 §11 A-Ⅲ）。过了之后照样落一行 {@code agent_tool_override_audit}。
     */
    @Schema(description = "二次确认：本次维护会放松安全约束（风险调低/权限删少）时必须为 true，否则拒绝且不落库",
            example = "true")
    private Boolean confirmRelax;

}
