package cn.enlev.ainb.module.system.controller.admin.permission.vo.grant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生效权限 - 单个动作
 *
 * 一行 = 「该资源节点上的某一个动作是生效的，它是这么来的」。
 *
 * <h3>与授权行（{@link GrantRespVO}）的本质区别</h3>
 *
 * 授权行是**台账**：配了什么就有什么，同一资源上 VIEW/CREATE/UPDATE/DELETE 各一行、
 * 过期的一行也照列。本 VO 是**判决结果**：由
 * {@link cn.enlev.ainb.module.system.framework.permission.core.decision.GrantDecisionEngine#explain}
 * 算出「这个动作对该主体是放行的」，然后回填「谁投出的这一票」。
 *
 * 所以台账上的 4 行可能只产出 3 个动作（UPDATE 被显式 DENY 否决）、
 * 也可能一行都不产出（全部过期），还可能一个动作来自某条**祖先**的行（继承放行）。
 */
@Schema(description = "管理后台 - 生效权限动作")
@Data
public class EffectiveActionVO {

    /** 来源：本资源自己身上的授权行 */
    public static final String SOURCE_EXPLICIT = "EXPLICIT";
    /**
     * 来源：祖先资源上的授权行（继承放行）
     *
     * 🔴 这一类是**扁平列表永远展示不出来的信息** —— 列表页只能看到父菜单那一条 VIEW ALLOW，
     * 看不到它其实放行了 20 个子按钮。权限地图的核心价值就在这里
     */
    public static final String SOURCE_INHERITED = "INHERITED";
    /** 来源：全员主体（ALL / all-users）上的授权行 */
    public static final String SOURCE_ALL_USERS = "ALL_USERS";
    /** 来源：链上没有任何命中行，回落到资源的 default_effect=ALLOW */
    public static final String SOURCE_DEFAULT = "DEFAULT";

    @Schema(description = "动作码，如 VIEW/CREATE/UPDATE/DELETE/MANAGE/START；* 表示全部动作")
    private String action;

    @Schema(description = "效果，恒为 ALLOW —— 被判 DENY 的动作不会出现在生效清单里", example = "ALLOW")
    private String effect;

    @Schema(description = "权限来源：EXPLICIT=本资源已配置 / INHERITED=继承自上级 / "
            + "ALL_USERS=全员默认拥有 / DEFAULT=默认放行（未配置）", example = "INHERITED")
    private String source;

    @Schema(description = "投出决定性一票的资源编号。INHERITED 时指向那条祖先，DEFAULT 时指向本资源")
    private Long sourceResourceId;

    @Schema(description = "投出决定性一票的资源名称，前端据此显示「继承自「X」」")
    private String sourceResourceName;

    @Schema(description = "命中授权行编号，可用来跳回列表页定位那一行；DEFAULT 来源为 null")
    private Long grantId;

    @Schema(description = "行过滤规则编号，null 表示不过滤")
    private Long ruleId;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "字段显示模式：EDITABLE/READONLY/HIDDEN，null 表示不限制")
    private String fieldMode;

    @Schema(description = "到期时间，null 表示永不过期")
    private LocalDateTime expireTime;

    @Schema(description = "命中授权行的主体类型，如 ROLE/ALL")
    private String subjectType;

    @Schema(description = "命中授权行的主体业务 id")
    private Long subjectRefId;

    @Schema(description = "命中授权行的主体编码")
    private String subjectCode;

}
