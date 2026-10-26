package cn.enlev.ainb.module.agent.controller.admin.tooldef.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - 用户自定义工具 新增/修改 Request VO
 *
 * <h3>🔴 登记 = 管理员配置行为，走专属 {@code agent:tool-def:*} 权限</h3>
 * 登记一个工具与「开启助手」同信任级：由管理员声明它的权限串与风险等级，
 * 之后 workflow {@code tool} 节点 / Agent 循环按声明过安全网关调用。
 *
 * <h3>🔴 config 只含 {@code {code, mode}}，不含凭据</h3>
 * 自定义工具是数据接口的「AI 包装层」：真正的地址、SQL、鉴权全在 {@code infra_data_interface} 那边治理，
 * 这里只记「绑哪个接口、只读还是全能力执行」。故不需要脱敏，也不需要「留空表示不修改」的回填语义。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 用户自定义工具 新增/修改 Request VO")
@Data
public class AgentToolDefSaveReqVO {

    @Schema(description = "编号（新增时为空，修改时必填）", example = "1")
    private Long id;

    @Schema(description = "工具键，租户内唯一，workflow tool 节点按此引用；不得与代码工具重名",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "sales_daily_report")
    @NotBlank(message = "工具键不能为空")
    @Pattern(regexp = "^[a-z][a-z0-9_]{1,63}$",
            message = "工具键必须以小写字母开头，仅含小写字母/数字/下划线，长度 2~64")
    private String toolKey;

    @Schema(description = "展示名", requiredMode = Schema.RequiredMode.REQUIRED, example = "销售日报")
    @NotBlank(message = "展示名不能为空")
    @Size(max = 128, message = "展示名长度不能超过 128")
    private String displayName;

    @Schema(description = "工具说明（进 LLM prompt / 设计器）", example = "查询某天的销售日报汇总")
    @Size(max = 500, message = "工具说明长度不能超过 500")
    private String description;

    @Schema(description = "来源，当前仅 DATA_INTERFACE 可执行", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "DATA_INTERFACE")
    @NotBlank(message = "来源不能为空")
    private String source;

    @Schema(description = "分组（管理页与设计器归类用）", example = "数据接口")
    @Size(max = 64, message = "分组长度不能超过 64")
    private String groupName;

    @Schema(description = "风险等级 READ_ONLY/LOW/MEDIUM/HIGH/IRREVERSIBLE（登记必填，无默认）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "READ_ONLY")
    @NotBlank(message = "风险等级不能为空")
    private String riskLevel;

    @Schema(description = "调用所需权限串，逗号分隔（AND 语义）；留空表示登录即可调用。"
            + "🔴 mode=INVOKE（全能力含写）时必须包含 infra:data-interface:invoke",
            example = "agent:config:query")
    @Size(max = 500, message = "权限串长度不能超过 500")
    private String permissions;

    @Schema(description = "是否启用", example = "true")
    private Boolean enabled;

    @Schema(description = "数据接口绑定 JSON：{code,mode}，mode 取 QUERY（只读）/ INVOKE（全能力含写）",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "{\"code\":\"order_stat_daily\",\"mode\":\"QUERY\"}")
    @NotBlank(message = "config 不能为空")
    @Size(max = 2000, message = "config 长度不能超过 2000")
    private String config;

    @Schema(description = "参数说明（自由文本，进 prompt）。留空时服务端按绑定接口的入参定义自动生成",
            example = "statDate（必填，DATE）：统计日期")
    @Size(max = 1000, message = "参数说明长度不能超过 1000")
    private String paramsSchema;

    @Schema(description = "备注", example = "给销售助手用")
    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;

}
