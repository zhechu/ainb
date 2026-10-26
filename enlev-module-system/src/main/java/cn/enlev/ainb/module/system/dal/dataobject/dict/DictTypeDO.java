package cn.enlev.ainb.module.system.dal.dataobject.dict;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import cn.enlev.ainb.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 字典类型表
 *
 * @author ruoyi
 */
@TableName("system_dict_type")
@KeySequence("system_dict_type_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TenantIgnore
public class DictTypeDO extends BaseDO {

    /**
     * 根节点的父编号
     */
    public static final Long PARENT_ID_ROOT = 0L;

    /**
     * 字典主键
     */
    @TableId
    private Long id;
    /**
     * 父分类编号
     *
     * 关联 {@link #id}；{@link #PARENT_ID_ROOT} 表示根
     */
    private Long parentId;
    /**
     * 节点类型
     *
     * 枚举 {@link cn.enlev.ainb.module.system.enums.dict.DictTypeNodeEnum}
     *
     * 分组节点不能挂字典数据，仅用于在左树上归拢
     */
    private Integer nodeType;
    /**
     * 字典名称
     */
    private String name;
    /**
     * 字典类型
     *
     * 分组节点同样必须填写且唯一，这样导入导出、跨环境引用时可以按编码寻址
     */
    private String type;
    /**
     * 同级排序
     */
    private Integer sort;
    /**
     * 字典数据是否树形：0 平铺、1 树形
     *
     * 创建后不允许再改：改成平铺会让已存在的子节点变成孤儿。
     * 确有需要走「导出 → 新建 → 导入」
     */
    private Integer treeEnable;
    /**
     * 缓存模式
     *
     * 枚举 {@link cn.enlev.ainb.module.system.enums.dict.DictCacheModeEnum}
     */
    private Integer cacheMode;
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

    /**
     * 删除时间
     */
    private LocalDateTime deletedTime;

}
