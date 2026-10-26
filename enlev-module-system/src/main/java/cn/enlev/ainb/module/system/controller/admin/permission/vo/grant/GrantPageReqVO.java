package cn.enlev.ainb.module.system.controller.admin.permission.vo.grant;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import cn.enlev.ainb.framework.common.validation.InEnum;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.enlev.ainb.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 授权行分页 Request VO
 *
 * 授权配置页列表用：一次拉全量的 list-by-subject 在授权行多时会把页面卡死，
 * 所以按主体分页查询
 */
@Schema(description = "管理后台 - 授权行分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class GrantPageReqVO extends PageParam {

    /**
     * 主体类型，取值见 {@code SubjectTypeEnum}
     *
     * 🔴 这条 {@code @InEnum} 拦的不是「写脏数据」，而是「把一个参数错误伪装成合法的业务结论」：
     * 类型名写错（{@code ROLES}、小写 {@code role}）时 SQL 照样执行、照样返回 200，
     * 只是<b>一行都匹配不到</b>。空列表在授权配置页上的读法是「这个角色什么权限都没有」，
     * 而真实原因只是参数拼错了 —— 与 {@code GrantAdminController#resolveSubject}
     * 对生效权限树刻意报错、不肯返回空树，是同一条口径
     */
    @Schema(description = "主体类型，取值见 SubjectTypeEnum：USER/ROLE/DEPT/POST/GROUP/SYSTEM/ALL",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "ROLE")
    @NotEmpty(message = "主体类型不能为空")
    @InEnum(value = SubjectTypeEnum.class, message = "主体类型不合法，必须是 {value} 之一")
    private String subjectType;

    @Schema(description = "主体业务 id，SYSTEM/ALL 类型不填", example = "1024")
    private Long subjectRefId;

    @Schema(description = "主体编码，仅 SYSTEM/ALL 类型填", example = "system")
    private String subjectCode;

    @Schema(description = "资源编号", example = "2048")
    private Long resourceId;

    @Schema(description = "动作，如 VIEW/CREATE/UPDATE/DELETE/*", example = "VIEW")
    private String action;

    @Schema(description = "效果：ALLOW 或 DENY", example = "ALLOW")
    private String effect;

    @Schema(description = "资源名称，模糊匹配", example = "用户")
    private String resourceName;

    @Schema(description = "权限标识，模糊匹配", example = "system:user")
    private String resourcePermission;

    @Schema(description = "到期时间范围：[起, 止]，落在区间内的授权行才会返回")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] expireTime;

}
