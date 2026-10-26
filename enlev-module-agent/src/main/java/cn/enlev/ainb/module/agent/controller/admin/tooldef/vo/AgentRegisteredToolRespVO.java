package cn.enlev.ainb.module.agent.controller.admin.tooldef.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台 - 内置（已注册）工具 Response VO（2026-09-28 增量）
 *
 * <p>合并视图：每个 {@code @AgentTool} 代码工具一条，同时携带<b>生效值</b>（当前维护后的值，网关/提示词读这些）
 * 与<b>声明值</b>（代码默认，供管理页回显「你正在覆盖什么」）。「维护中」标志由 {@link #overridden} 表达。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 内置（已注册）工具 Response VO")
@Data
public class AgentRegisteredToolRespVO {

    @Schema(description = "工具名（= @AgentTool#name / 注册表键）", example = "datamodel_create")
    private String toolName;

    // ---------- 生效值（当前维护后的值） ----------

    @Schema(description = "生效展示名（未维护=代码声明的中文展示名，再回落工具名）", example = "创建数据表")
    private String displayName;

    @Schema(description = "生效工具说明", example = "根据需求创建数据表")
    private String description;

    @Schema(description = "生效分组", example = "数据建模")
    private String groupName;

    @Schema(description = "生效风险等级", example = "IRREVERSIBLE")
    private String riskLevel;

    @Schema(description = "生效风险等级中文描述", example = "不可逆")
    private String riskDescription;

    @Schema(description = "生效调用所需权限串，逗号分隔（空=登录即可）", example = "infra:data-model:create")
    private String permissions;

    @Schema(description = "生效启用状态", example = "true")
    private Boolean enabled;

    @Schema(description = "是否必须进 Plan 等用户确认（跟随生效风险）", example = "true")
    private Boolean requireConfirm;

    @Schema(description = "生效参数说明（进 prompt）")
    private String paramsSchema;

    @Schema(description = "是否正被在线维护（生效值≠声明值）", example = "true")
    private Boolean overridden;

    // ---------- 声明值（代码默认，供回显「正在覆盖什么」） ----------

    @Schema(description = "代码默认展示名（未声明时=工具名）", example = "创建数据表")
    private String declaredDisplayName;

    @Schema(description = "代码默认说明", example = "根据需求创建数据表")
    private String declaredDescription;

    @Schema(description = "代码默认分组", example = "数据建模")
    private String declaredGroupName;

    @Schema(description = "代码默认风险等级", example = "IRREVERSIBLE")
    private String declaredRiskLevel;

    @Schema(description = "代码默认权限串，逗号分隔", example = "infra:data-model:create")
    private String declaredPermissions;

    @Schema(description = "代码默认启用状态", example = "true")
    private Boolean declaredEnabled;

    @Schema(description = "代码默认参数说明")
    private String declaredParamsSchema;

    @Schema(description = "实现类全名（排查「这个工具到底是谁」）",
            example = "cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelCreateTool")
    private String implClassName;

    // ---------- 覆盖行元数据（未维护时为 null） ----------

    @Schema(description = "覆盖行编号（未维护=null）", example = "1")
    private Long overrideId;

    @Schema(description = "覆盖行最后更新时间（未维护=null）")
    private LocalDateTime overrideUpdateTime;

    @Schema(description = "覆盖行最后更新人（未维护=null）", example = "1")
    private String overrideUpdater;

    @Schema(description = "覆盖行备注（未维护=null）", example = "本租户暂不开放建表")
    private String remark;

}
