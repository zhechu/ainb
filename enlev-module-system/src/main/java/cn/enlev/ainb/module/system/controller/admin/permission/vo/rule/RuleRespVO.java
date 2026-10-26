package cn.enlev.ainb.module.system.controller.admin.permission.vo.rule;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行过滤规则响应 VO（用于授权配置时的规则下拉选择）
 */
@Schema(description = "管理后台 - 行过滤规则响应")
@Data
public class RuleRespVO {

    @Schema(description = "规则编号")
    private Long id;

    @Schema(description = "规则名称")
    private String name;

    @Schema(description = "规则模板代码，如 BUILT_IN_OWNER_ONLY")
    private String template;

    @Schema(description = "模板参数，JSON 格式")
    private String params;

    @Schema(description = "规则描述")
    private String description;

    @Schema(description = "状态：0 启用 1 停用")
    private Integer status;


    /**
     * 是否系统内置
     *
     * 前端据此显示「内置/自定义」标签并决定删除按钮是否可用。
     * ⚠️ 不能让前端拿 template 是否以 BUILT_IN_ 开头来判 —— 管理员自己建的规则
     * 同样可以选内置模板，那样判会把它们全标成内置、还连带禁用删除按钮
     */
    @Schema(description = "是否系统内置", example = "false")
    private Boolean builtIn;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
