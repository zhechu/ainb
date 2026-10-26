package cn.enlev.ainb.module.system.dal.dataobject.dict;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import cn.enlev.ainb.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 字典数据表
 *
 * @author ruoyi
 */
@TableName("system_dict_data")
@KeySequence("system_dict_data_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@TenantIgnore
public class DictDataDO extends BaseDO {

    /**
     * 根节点的父编号
     */
    public static final Long PARENT_ID_ROOT = 0L;

    /**
     * 路径分隔符
     *
     * 首尾都带它，是为了让 {@code LIKE ',45,%'} 不会误命中 {@code ,145,} 这类前缀
     */
    public static final String PATH_SEPARATOR = ",";

    /**
     * 字典数据编号
     */
    @TableId
    private Long id;
    /**
     * 父节点编号
     *
     * 关联 {@link #id}；{@link #PARENT_ID_ROOT} 表示根。
     * 用 id 而不是 value：value 允许被修改，用它做父引用会在改码时断链
     */
    private Long parentId;
    /**
     * 层级，根为 1
     *
     * 由 {@link #idPath} 派生而来的冗余列，为的是不用解析字符串就能做层级上限校验
     */
    private Integer treeLevel;
    /**
     * 祖先路径，形如 {@code ,12,45,78,}（含自身）
     *
     * 物化路径。只有 parentId 的话，取子树要递归查询或全表捞进内存；
     * 有了它，取子树、防环、移动整棵子树都变成常数条 SQL
     */
    private String idPath;
    /**
     * 字典排序
     */
    private Integer sort;
    /**
     * 字典标签
     */
    private String label;
    /**
     * 字典值
     */
    private String value;
    /**
     * 字典类型
     *
     * 冗余 {@link DictDataDO#getDictType()}
     */
    private String dictType;
    /**
     * 状态
     *
     * 枚举 {@link CommonStatusEnum}
     */
    private Integer status;
    /**
     * 颜色类型
     *
     * 对应到 element-ui 为 default、primary、success、info、warning、danger
     */
    private String colorType;
    /**
     * css 样式
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String cssClass;
    /**
     * 备注
     */
    private String remark;

}
