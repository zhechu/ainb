package cn.enlev.ainb.module.system.dal.dataobject.dept;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 岗位表
 *
 * @author ruoyi
 */
@TableName("system_post")
@KeySequence("system_post_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
public class PostDO extends BaseDO {

    /**
     * 岗位序号
     */
    @TableId
    private Long id;
    /**
     * 岗位名称
     */
    private String name;
    /**
     * 岗位编码
     */
    private String code;
    /**
     * 岗位排序
     */
    private Integer sort;
    /**
     * 所属组织编号
     *
     * 关联 {@link DeptDO#getId()}；允许为空，表示该岗位不挂组织。
     * 无物理外键，保存时不校验组织是否存在 —— 组织被删后本字段成为悬空引用，前端显示为空
     *
     * ⚠️ updateStrategy=ALWAYS：本字段「允许被编辑清空」（岗位从某组织改为不挂组织），
     * 而 updateById 默认跳过 null 字段 —— 不加这个注解时「编辑里把组织清掉」会静默不生效，
     * 界面上表现为保存成功、列表刷新后组织又回来了。与 GrantDO 同一口径。
     * 有回归单测钉着（{@code PostServiceImplTest#testUpdatePost_clearDeptId}），别当成 bug 改掉
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long deptId;
    /**
     * 状态
     *
     * 枚举 {@link CommonStatusEnum}
     */
    private Integer status;
    /**
     * 备注
     */
    private String remark;

}
