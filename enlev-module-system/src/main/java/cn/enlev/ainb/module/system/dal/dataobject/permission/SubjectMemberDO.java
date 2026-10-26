package cn.enlev.ainb.module.system.dal.dataobject.permission;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 主体成员关系（一条边）
 *
 * ## ⚠️ 方向：成员继承容器的权限，不是反过来
 *
 * 这是整个主体模型里**唯一一处写反了会静默提权**的地方，务必看清：
 *
 * <pre>
 *     subject_id  = 容器主体 = 权限较【基础】的一方
 *     member_*    = 成员主体 = 继承者，拿到容器的全部权限，再叠加自己的
 * </pre>
 *
 * 以「高级销售 = 销售 + 额外权限」为例，正确的落库是：
 *
 * <pre>
 *     subject_id → ROLE:销售          （容器）
 *     member     → ROLE:高级销售      （成员）
 * </pre>
 *
 * 读作「高级销售 是 销售 的成员」，于是高级销售的人也算销售，拿到销售的权限。
 *
 * **写反了**（容器=高级销售、成员=销售）会变成：所有销售都算高级销售，
 * 于是普通销售拿到了高级销售的全部权限 —— 而界面上看不出任何异常。
 *
 * 前端（S8a）呈现为「高级销售 继承自 销售」，落库时注意方向要翻过来。
 *
 * ## 这张表只存【新增的】边
 *
 * 不存 user→role / user→dept / user→post 这三类原生成员关系 ——
 * 它们分别在 system_user_role、system_users.dept_id、system_users.post_ids 里，
 * 求闭包时现查（且都有现成缓存）。镜像进来只会制造双写不一致。
 *
 * 实际落在这里的是：ROLE→ROLE 的继承边、GROUP→任意主体 的成员边。
 *
 * @author 走进新时代
 */
@TableName("sys_subject_member")
@KeySequence("sys_subject_member_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
public class SubjectMemberDO extends TenantBaseDO {

    /**
     * 自增主键
     */
    @TableId
    private Long id;

    /**
     * 容器主体，关联 {@link SubjectDO#getId()}
     *
     * 用外键而不是 (type, ref_id) 两列，是为了让「按需落行」自然发生：
     * 建一条边必须先有容器的主体行，而一个成为容器的主体，正是需要区别对待的主体
     */
    private Long subjectId;

    /**
     * 成员主体的类型，见 {@link SubjectTypeEnum}
     *
     * 成员用 (type, ref_id) 直接寻址而不是外键，是因为成员绝大多数是 USER，
     * 而用户【不落】主体行
     */
    private String memberType;

    /**
     * 成员主体指向的业务表主键
     */
    private Long memberRefId;

    /**
     * 成员的寻址串对象
     */
    public SubjectKey toMemberKey() {
        SubjectTypeEnum typeEnum = SubjectTypeEnum.parse(memberType);
        if (typeEnum == null || !typeEnum.isRefBased()) {
            // SYSTEM 不允许作为成员（见 SubjectServiceImpl 的校验），这里防脏数据
            return null;
        }
        return SubjectKey.of(typeEnum, memberRefId);
    }

}
