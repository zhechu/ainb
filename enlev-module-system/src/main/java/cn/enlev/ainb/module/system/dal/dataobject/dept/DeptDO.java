package cn.enlev.ainb.module.system.dal.dataobject.dept;

import cn.enlev.ainb.framework.common.biz.system.permission.annotation.PermissionDataset;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import cn.enlev.ainb.module.system.dal.dataobject.user.AdminUserDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 组织表
 *
 * @author ruoyi
 * @author 芋道源码
 */
@PermissionDataset(value = "system:dept", name = "组织",
        // ⚠️ dept 槽位指向自己的 id，不是 parent_id。
        // 判据与旧的 DeptDataPermissionRule 逐字对齐 —— 那边写的就是
        // rule.addDeptColumn(DeptDO.class, "id")：对部门表来说，「这条记录属于哪个部门」
        // 的答案是它自己。指到 parent_id 会变成「按上级部门过滤」，语义完全不同
        dept = "id", status = "status")
@TableName("system_dept")
@KeySequence("system_dept_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
public class DeptDO extends TenantBaseDO {

    public static final Long PARENT_ID_ROOT = 0L;

    /**
     * 组织ID
     */
    @TableId
    private Long id;
    /**
     * 组织名称
     */
    private String name;
    /**
     * 组织编码
     *
     * 同一租户内唯一，由 {@code DeptServiceImpl#validateDeptCodeUnique} 在应用层保证。
     * 刻意不建 DB 唯一索引：本表是软删除（deleted bit），唯一索引会让已删除行继续占住编码，
     * 删掉一个组织后无法用同一编码重建 —— 那是假阳性冲突。与 {@code PostDO#code} 同一口径。
     *
     * 库里允许 NULL 而 DeptSaveReqVO 上是必填，这个不对称是刻意的：存量行没有任何可反查的
     * 编码线索，不回填（凭空生成 dept_<id> 就是往事实源里写人造数据），所以列必须可空；
     * 必填是前向约束，存量行走「编辑时补录」逐步收敛。
     *
     * 也正因为必填、不存在「把编码清空」这个语义，本字段不需要 PostDO#deptId 那样的
     * {@code @TableField(updateStrategy = ALWAYS)} —— 别照着那个可空字段抄
     */
    private String code;
    /**
     * 父组织ID
     *
     * 关联 {@link #id}
     */
    private Long parentId;
    /**
     * 显示顺序
     */
    private Integer sort;
    /**
     * 负责人
     *
     * 关联 {@link AdminUserDO#getId()}
     */
    private Long leaderUserId;
    /**
     * 联系电话
     */
    private String phone;
    /**
     * 邮箱
     */
    private String email;
    /**
     * 组织状态
     *
     * 枚举 {@link CommonStatusEnum}
     */
    private Integer status;

}
