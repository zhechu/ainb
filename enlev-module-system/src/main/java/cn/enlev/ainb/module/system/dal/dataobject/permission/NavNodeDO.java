package cn.enlev.ainb.module.system.dal.dataobject.permission;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import cn.enlev.ainb.framework.tenant.core.aop.TenantIgnore;
import cn.enlev.ainb.module.system.enums.permission.NavNodeTypeEnum;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 分端导航节点 DO
 *
 * <p>每端一棵独立于 {@code system_menu} 的自引用导航树（分组 → 子分组 → 导航项）。
 * 现阶段只有 {@code terminal='app'} 的移动端树；Web 端不写此表，继续走 {@link MenuDO}。
 *
 * <p>与 {@link MenuDO} 的唯一挂接点是 {@link #permissions}：导航项不复制权限定义，
 * 只存权限串（逗号分隔、任一命中即可见），运行时拿它去命中 {@code get-permission-info}
 * 下发的共享授权集合。授权判定只有一处真相，见「移动端优化方案」§4.4.1
 *
 * @author 走进新时代
 */
@TableName("system_nav_node")
@KeySequence("system_nav_node_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@TenantIgnore
public class NavNodeDO extends BaseDO {

    /**
     * 父节点编号 - 根节点
     */
    public static final Long ID_ROOT = 0L;

    /**
     * 节点编号
     */
    @TableId
    private Long id;
    /**
     * 端标识
     *
     * 枚举 {@link cn.enlev.ainb.module.system.enums.permission.NavTerminalEnum}
     */
    private String terminal;
    /**
     * 父节点编号，{@link #ID_ROOT} 为根
     */
    private Long parentId;
    /**
     * 节点类型
     *
     * 枚举 {@link NavNodeTypeEnum}
     */
    private Integer nodeType;
    /**
     * 展示名称
     */
    private String name;
    /**
     * 叶子节点页面路径（须命中移动端 pages.json）；分组节点为空
     */
    private String route;
    /**
     * 挂接共享授权的权限串列表（逗号分隔，任一命中即可见；候选取自 system_menu 权限目录）
     *
     * 空 = 不设权限门槛，是否显示纯由 {@link #visible} + {@link #status} 决定
     */
    private String permissions;
    /**
     * 可选：指回 {@link MenuDO#getId()}，标记「与某 Web 菜单同一能力」
     *
     * 仅供 admin 选择器带出 permission/name 及审计，不是导航承重字段；移动专属项为空
     */
    private Long menuRefId;
    /**
     * 图标（wot-ui 图标名或图片路径）
     */
    private String icon;
    /**
     * 图标颜色（移动端专属展示）
     */
    private String iconColor;
    /**
     * 是否 tabbar 页：决定 switchTab vs navigateTo
     */
    private Boolean isTabbar;
    /**
     * 仅 PC：移动端点击跳「仅 PC」提示页
     */
    private Boolean onlyPc;
    /**
     * 页面参数（如在线表单 code=xxx）
     */
    private String query;
    /**
     * 同级排序
     */
    private Integer sort;
    /**
     * 该端是否显示（= 原始诉求的「移动端开启标记」）
     */
    private Boolean visible;
    /**
     * 状态
     *
     * 枚举 {@link CommonStatusEnum}；停用则整枝不展示
     */
    private Integer status;

}
