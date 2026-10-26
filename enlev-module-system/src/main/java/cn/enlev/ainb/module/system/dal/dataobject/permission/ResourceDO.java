package cn.enlev.ainb.module.system.dal.dataobject.permission;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import cn.enlev.ainb.framework.tenant.core.aop.TenantIgnore;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权限资源
 *
 * ## 职责分离：导航归 system_menu，授权归这里
 *
 * system_menu 原先同时承担两个职责：
 *   ① 渲染导航树（path/icon/component/visible/keepAlive/alwaysShow）
 *   ② 作为权限授予的对象（permission 字段 + 角色-菜单关联）
 *
 * 两者是不同频率的变化：导航跟着前端版本走，资源跟着功能迭代走。
 * 分开后，加一个新资源点不需要同时考虑它在导航树里放在哪里，
 * 而导航结构调整也不会牵扯权限配置。
 *
 * ## 按需创建（v9 §5.2）
 *
 * 列/字段不落行，默认效果 ALLOW。只有被标「敏感」（default_effect = DENY）
 * 或被单独授权时才创建对应的资源行。
 * 这一条让资源表的量级保持在数千行而不是数十万行。
 *
 * ## 物化路径（v9 §5.3）
 *
 * path 存祖先链，格式 /root/system/system:user/。
 * 查一个资源的所有祖先：WHERE :myPath LIKE CONCAT(path, '%')，不递归。
 *
 * ## 全局表，不分租户（与 system_menu 对齐）
 *
 * 资源回答的是「这套系统里存在哪些可授权对象」，这与租户无关 ——
 * system_menu 本来就是 {@code @TenantIgnore} 的，权限侧从它拆出来自然也该是。
 * 租户差异体现在 {@code sys_grant}（谁被授予了什么），那张表是分租户的。
 *
 * ⚠️ 这一点踩过一次坑：本类最初继承 TenantBaseDO，而资源行是启动期迁移进来的
 * （tenant_id = 0）。于是租户 1 的用户查资源时带上 {@code tenant_id = 1}，
 * 一行都查不到 —— 所有 {@code @ss.hasPermission} 对非超管用户恒为 false，
 * 且没有任何报错。表里 tenant_id 列保留但不再参与读写。
 *
 * @author 走进新时代
 */
@TableName("sys_resource")
@KeySequence("sys_resource_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@TenantIgnore
public class ResourceDO extends BaseDO {

    /**
     * 物化路径的分隔符
     */
    public static final String PATH_SEPARATOR = "/";
    /**
     * 物化路径的根节点前缀
     */
    public static final String PATH_ROOT_PREFIX = "/root/";
    /**
     * 根节点编号
     */
    public static final Long ID_ROOT = 0L;

    /**
     * 资源编号
     */
    @TableId
    private Long id;

    /**
     * 权限标识
     *
     * 对应 @ss.hasPermission 的参数，格式如 system:user:query。
     * 可以为空：目录层的资源不需要权限标识，它们只是树的结构节点
     */
    private String permission;

    /**
     * 资源名称，展示用
     */
    private String name;

    /**
     * 资源类型
     *
     * 枚举 {@link ResourceTypeEnum} 的 code
     */
    private String type;

    /**
     * 父资源编号，根节点为 0
     */
    private Long parentId;

    /**
     * 物化路径
     *
     * 格式：/root/{层1}/{层2}/…/{本节点permission}/
     * 祖先链查询：WHERE :myPath LIKE CONCAT(path, '%')
     *
     * ⚠️ 本字段由 Service 层维护，不要直接 set —— 树结构变动时 Service 会级联更新
     */
    private String path;

    /**
     * 节点深度，根节点为 0
     *
     * 冗余字段，配合 path 做深度筛选
     */
    private Short depth;

    /**
     * 显示顺序
     */
    private Integer sort;

    /**
     * 无授权时的默认效果：ALLOW 或 DENY
     *
     * v9 §4.1：COLUMN/FIELD 默认 ALLOW（加字段忘配权限 → 显示），
     * 其余默认 DENY（必须显式授权才能用）
     */
    private String defaultEffect;

    /**
     * 状态，枚举 {@link cn.enlev.ainb.framework.common.enums.CommonStatusEnum}
     */
    private Integer status;

    /**
     * 迁移用：对应的 system_menu.id
     *
     * 迁移期间可以通过这个字段把两张表的记录对应起来。
     * 迁移完成并验证无误后，这个字段可以置空或删掉。
     * ⚠️ 任何判定逻辑都不许读它 —— 它只是迁移期的临时映射
     */
    private Long menuId;

    /**
     * 数据集绑定的物理表名，仅 DATASET 类型有值
     *
     * ## 为什么必须落库，而不是运行时去问表单模块
     *
     * 行级权限的入口是 {@code DataPermissionRule.getTableNames()} ——
     * 框架在<b>拦截 SQL 时</b>拿这个集合决定要不要改写语句。
     * 集合只在表单发布时才填的话，应用一重启它就空了，
     * 而所有已配好的行级规则会<b>静默失效</b>（查询照常返回，只是没了过滤）。
     *
     * 存在这里之后，启动时一次查询就能把全部数据集的表名灌回去
     * （见 {@code DatasetTableRegisterRunner}），不必反向依赖表单模块。
     *
     * 同时它也是 {@code RuleDataPermissionRule.findResourcesByTable} 的反查依据：
     * 拦到一张表 → 找到它的数据集资源 → 才能取到挂在该资源上的授权与槽位映射
     */
    private String datasetTable;

    /**
     * 记录共享的发起权口径，见 {@link cn.enlev.ainb.module.system.enums.permission.RecordSharePolicyEnum}
     *
     * NULL = 默认档 {@code OWNER_ONLY}（仅归属人可分享）。
     *
     * ⚠️ 只对 DATASET 类型有意义。放在资源行上而不是单独一张配置表，
     * 是因为「这张表能不能被逐条分享」与「这张表是什么」是同一件事，
     * 拆开之后配了一半（有资源没配置 / 有配置没资源）的状态没人能解释
     */
    private String sharePolicy;

    /**
     * 计算本节点的物化路径
     *
     * @param parentPath 父节点的 path
     * @param ident      本节点在路径里的标识符（permission 或 id 的 String 形式）
     */
    public static String buildPath(String parentPath, String ident) {
        if (parentPath == null || parentPath.isEmpty()) {
            return PATH_ROOT_PREFIX + ident + PATH_SEPARATOR;
        }
        return parentPath + ident + PATH_SEPARATOR;
    }

}
