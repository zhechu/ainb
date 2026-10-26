package cn.enlev.ainb.module.system.dal.dataobject.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 授权主体
 *
 * ## 这张表【不是】用户/角色/部门的镜像
 *
 * 它只登记「需要区别对待」的主体 —— 即满足下列之一的：
 *
 * <pre>
 *     ① 它是某条成员关系的容器（比如被继承的基础角色、用户组）
 *     ② 它被直接授权（S2a 的 sys_grant）
 *     ③ 它是 SYSTEM 身份（没有业务实体，只能登记在这里）
 * </pre>
 *
 * 换句话说：**按需创建**，与 v9 §5.2 对列/字段资源的处置是同一条原则。
 * 一个没有继承边、也没被单独授权的普通角色，在这张表里根本不会出现。
 *
 * ## 为什么不镜像
 *
 * 镜像意味着 system_users / system_role / system_dept / system_post 的每个写路径
 * （约 15 处）都要挂同步钩子。漏挂任何一处的表现是「权限静默错误、无任何提示」——
 * 恰好是本项目最贵、最难反查的缺陷形态。单一数据源换来的确定性，
 * 远比「求值时只查一张表」的那点便利重要。
 *
 * 代价是：主体的 name 不在这里（见下），授权表存 (type, ref_id) 而不是外键。
 *
 * @author 走进新时代
 */
@TableName("sys_subject")
@KeySequence("sys_subject_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
public class SubjectDO extends TenantBaseDO {

    /**
     * 自增主键
     */
    @TableId
    private Long id;

    /**
     * 主体类型
     *
     * 枚举 {@link SubjectTypeEnum} 的名字，如 ROLE、GROUP、SYSTEM
     */
    private String type;

    /**
     * 指向的业务表主键
     *
     * ROLE → system_role.id，DEPT → system_dept.id，POST → system_post.id，
     * USER → system_users.id，GROUP → 本表自身的 id（用户组没有独立业务表）。
     * SYSTEM 为 null
     */
    private Long refId;

    /**
     * 主体编码
     *
     * 只有 SYSTEM 用它寻址（如 bpm-engine）。其余类型为 null
     */
    private String code;

    /**
     * 展示名
     *
     * ⚠️ **仅用于排错与列表展示，可能过期**。权威名字在业务表里
     * （角色名改了，这里不会跟着改 —— 挂同步钩子正是本表要避免的东西）。
     * 任何判定逻辑都不许读它
     */
    private String name;

    /**
     * 状态
     *
     * 枚举 {@link cn.enlev.ainb.framework.common.enums.CommonStatusEnum}。
     * 停用的主体不参与闭包 —— 这是「临时关掉一整条继承链」的开关
     */
    private Integer status;

    /**
     * 转成寻址串对象。认不出来一律返回 {@code null}，<b>绝不抛</b>
     *
     * <p>🔴 与 {@link SubjectKey#parse} 同口径，因为两者是同一件事的两个入口：
     * 都在读库路径上，都得假定库里的行可能是脏的。{@code sys_subject.ref_id} 与
     * {@code code} 在表结构上<b>都是可空列</b>，所以「type 与标识不配对」的行
     * （直接改库改出来的）建得出来。而 {@code SubjectKey} 的紧凑构造器对这两种缺参
     * 分别是 {@code NullPointerException}（ref 型缺 refId）与
     * {@code IllegalArgumentException}（code 型缺 code），放过去就是一条 500 加一行堆栈日志
     *
     * <p>唯一调用方 {@code SubjectGraphLoader} 正是按「返回 null」这个契约写的：
     * 拿到 null 就跳过那条边，它自己的注释写着「抛的话，一行坏数据会让所有人都登不上来」——
     * 主体图在登录链路上。原先这里只挡了 type 解析不出来的情况，
     * 少给标识那一支会直接抛，等于把调用方明确要避免的事又做了回去
     */
    public SubjectKey toKey() {
        SubjectTypeEnum typeEnum = SubjectTypeEnum.parse(type);
        if (typeEnum == null) {
            return null;
        }
        return typeEnum.isRefBased()
                ? (refId == null ? null : SubjectKey.of(typeEnum, refId))
                : (StrUtil.isBlank(code) ? null : SubjectKey.ofSystem(code));
    }

}
