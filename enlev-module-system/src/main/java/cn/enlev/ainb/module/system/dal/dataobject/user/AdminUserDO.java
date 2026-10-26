package cn.enlev.ainb.module.system.dal.dataobject.user;

import cn.enlev.ainb.framework.common.biz.system.permission.annotation.PermissionDataset;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import cn.enlev.ainb.module.system.enums.common.SexEnum;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 管理后台的用户 DO
 *
 * @author 芋道源码
 */
@PermissionDataset(value = "system:user", name = "用户",
        owner = "id", dept = "dept_id", status = "status",
        // ⚠️ 这里列的是「允许被字段级权限控制的字段」，不是「会被隐藏的字段」。
        // 登记成资源后才能在授权页上给它配 HIDDEN/READONLY —— 没登记的字段，
        // 配也配不出来（授权页的资源树里根本没有那一项）。
        // fields = 详情里可控的字段（:fld:）；columns = 列表里可控的列（:col:）
        // 两者列同样的名字，但生成互不影响的两套资源 ——
        // 这样才能配出「列表里不显示手机号，但点进详情能看」
        // fields 含 nickname 不含 username：
        //   · nickname 要能配「只读」——「昵称能看不能改」是常见诉求
        //   · username 是登录账号，改它等于换账号，不该由字段权限管；
        //     要禁就在动作层禁掉 system:user:update
        fields = {"nickname", "mobile", "email", "deptName", "postIds", "sex", "avatar",
                "status", "remark", "loginIp", "loginDate", "createTime"},
        // columns 额外含 username：列表可以把标识列也隐藏 ——
        // 配合行过滤「只看自己那条」时，列表就一行、就是自己，不需要标识列也认得出来
        columns = {"username", "nickname", "mobile", "email", "deptName", "postIds", "sex",
                "avatar", "status", "remark", "loginIp", "loginDate", "createTime"},
        // 资源展示名用中文（授权页资源树是给管理员看的），口径对齐前端用户管理页的标签。
        // 生成的资源名形如「用户:字段:手机号」「用户:列:手机号」
        labels = {"username=用户名称", "nickname=用户昵称", "mobile=手机号", "email=邮箱",
                "deptName=部门", "postIds=岗位", "sex=性别", "avatar=头像", "status=状态",
                "remark=备注", "loginIp=最后登录IP", "loginDate=最后登录时间", "createTime=创建时间"})
@TableName(value = "system_users", autoResultMap = true) // 由于 SQL Server 的 system_user 是关键字，所以使用 system_users
@KeySequence("system_users_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserDO extends TenantBaseDO {

    /**
     * 用户ID
     */
    @TableId
    private Long id;
    /**
     * 用户账号
     */
    private String username;
    /**
     * 加密后的密码
     *
     * 因为目前使用 {@link BCryptPasswordEncoder} 加密器，所以无需自己处理 salt 盐
     */
    private String password;
    /**
     * 用户昵称
     */
    private String nickname;
    /**
     * 备注
     */
    private String remark;
    /**
     * 部门 ID
     */
    private Long deptId;
    /**
     * 岗位编号数组
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Set<Long> postIds;
    /**
     * 用户邮箱
     */
    private String email;
    /**
     * 手机号码
     */
    private String mobile;
    /**
     * 用户性别
     *
     * 枚举类 {@link SexEnum}
     */
    private Integer sex;
    /**
     * 用户头像
     */
    private String avatar;
    /**
     * 帐号状态
     *
     * 枚举 {@link CommonStatusEnum}
     */
    private Integer status;
    /**
     * 最后登录IP
     */
    private String loginIp;
    /**
     * 最后登录时间
     */
    private LocalDateTime loginDate;

}
