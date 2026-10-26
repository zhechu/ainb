package cn.enlev.ainb.module.system.controller.admin.permission.vo.grant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 授权行响应 VO
 */
@Schema(description = "管理后台 - 授权行响应")
@Data
public class GrantRespVO {

    @Schema(description = "授权编号")
    private Long id;

    @Schema(description = "主体类型，如 ROLE/USER/DEPT/GROUP/SYSTEM/ALL")
    private String subjectType;

    @Schema(description = "主体业务表主键，SYSTEM/ALL 类型为 null")
    private Long subjectRefId;

    @Schema(description = "主体编码，仅 SYSTEM/ALL 类型使用")
    private String subjectCode;

    /**
     * 主体的展示名，如 {@code super_admin（超级管理员）}、{@code admin（超级管理员）}。
     *
     * 🔴 由后端从<b>权威业务表</b>批量解析（见 {@code SubjectDisplayNameResolver}），
     * 不是 {@code sys_subject.name}：那张表只按需登记主体，普通角色在里面根本没有行、
     * 用户类型压根不落行，拿它展示会得到「一部分有名字、一部分空、有的还是旧名」。
     * 也不能由前端 join simple-list 自己拼 —— 角色 simple-list 没有 code、
     * 用户 simple-list 没有 username 且只返回启用的用户。
     *
     * 解析不出来（跨租户、已删除等）时为 null，前端回落到 id 本身，
     * <b>绝不</b>显示空白：这一列是管理员核对「权限授给了谁」的唯一依据。
     */
    @Schema(description = "主体展示名，如 super_admin（超级管理员）；解析不到时为 null")
    private String subjectName;

    @Schema(description = "资源编号")
    private Long resourceId;

    @Schema(description = "资源权限标识，如 system:user:query")
    private String resourcePermission;

    @Schema(description = "资源名称")
    private String resourceName;

    /**
     * 资源类型，枚举 {@link cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum} 的 code。
     *
     * 前端拿它判「这一行上的 ruleId / fieldMode 到底有没有意义」：行过滤规则只在 DATASET 上会被读到
     * （{@link cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper#selectListByDatasetTable}
     * 是按物理表名反查数据集资源的），字段显示模式只在 COLUMN/FIELD 上有意义。
     * 没有这个字段，列表页只能对**所有**行一律显示「不受限（全部行）」——
     * 而绑在菜单上的规则压根不参与任何查询，那句话是假的。
     */
    @Schema(description = "资源类型：MENU/ACTION/PROCESS/CATEGORY/DATASET/COLUMN/FIELD")
    private String resourceType;

    @Schema(description = "上下文资源编号，null 表示全局")
    private Long contextResourceId;

    @Schema(description = "动作，如 VIEW/CREATE/UPDATE/DELETE/*")
    private String action;

    @Schema(description = "效果：ALLOW 或 DENY")
    private String effect;

    @Schema(description = "行过滤规则编号，null 表示不过滤")
    private Long ruleId;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "字段显示模式：EDITABLE/READONLY/HIDDEN，null 表示不限制")
    private String fieldMode;

    @Schema(description = "到期时间，null 表示永不过期")
    private LocalDateTime expireTime;

    @Schema(description = "是否已过期")
    private Boolean expired;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
