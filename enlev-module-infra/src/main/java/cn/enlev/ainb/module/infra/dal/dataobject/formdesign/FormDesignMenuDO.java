package cn.enlev.ainb.module.infra.dal.dataobject.formdesign;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import cn.enlev.ainb.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 表单设计与菜单的关联 DO
 *
 * 发布时会往 system_menu 写入 1 条菜单 + 4 条按钮权限（query/create/update/delete），
 * 下线或删除表单时需要精确回收这些记录，因此必须留下「本模块创建了哪些菜单」的账。
 *
 * 回收时的两个边界：
 * 1. 需同步清理 system_role_menu 中的角色授权，否则残留脏数据
 * 2. 用户可能手工去菜单管理删过该菜单，回收时应容错跳过而非报错
 *
 * @author 走进新时代
 */
@TableName("infra_form_design_menu")
@KeySequence("infra_form_design_menu_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TenantIgnore
public class FormDesignMenuDO extends BaseDO {

    /**
     * 编号
     */
    @TableId
    private Long id;
    /**
     * 表单设计编号
     */
    private Long designId;
    /**
     * 菜单编号，对应 system_menu 的主键
     */
    private Long menuId;
    /**
     * 菜单类型：2-菜单 3-按钮权限
     *
     * 与 system_menu 的 type 含义保持一致。
     */
    private Integer menuType;
    /**
     * 权限标识
     *
     * 菜单类型为按钮权限时有值，形如 infra:form-design-run:{code}:{action}。
     */
    private String permission;

}
