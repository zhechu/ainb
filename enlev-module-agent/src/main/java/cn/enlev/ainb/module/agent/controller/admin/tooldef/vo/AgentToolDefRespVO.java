package cn.enlev.ainb.module.agent.controller.admin.tooldef.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台 - 用户自定义工具 Response VO（增量5a）
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 用户自定义工具 Response VO")
@Data
public class AgentToolDefRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "工具键", example = "sales_daily_report")
    private String toolKey;

    @Schema(description = "展示名", example = "销售日报")
    private String displayName;

    @Schema(description = "工具说明", example = "查询某天的销售日报汇总")
    private String description;

    @Schema(description = "来源", example = "DATA_INTERFACE")
    private String source;

    @Schema(description = "分组", example = "数据接口")
    private String groupName;

    @Schema(description = "风险等级", example = "READ_ONLY")
    private String riskLevel;

    @Schema(description = "调用所需权限串，逗号分隔", example = "agent:config:query")
    private String permissions;

    @Schema(description = "是否启用", example = "true")
    private Boolean enabled;

    @Schema(description = "数据接口绑定 JSON：{code,mode}", example = "{\"code\":\"order_stat_daily\",\"mode\":\"QUERY\"}")
    private String config;

    @Schema(description = "参数说明（自由文本）")
    private String paramsSchema;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
