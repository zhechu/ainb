package cn.enlev.ainb.framework.common.biz.system.permission;

import cn.enlev.ainb.framework.common.biz.system.permission.dto.DataRowFilterRespDTO;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DatasetFieldModeRespDTO;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DeptDataPermissionRespDTO;

import java.util.Collection;
import java.util.Map;

/**
 * 权限 API 接口
 *
 * @author 芋道源码
 */
public interface PermissionCommonApi {

    /**
     * 判断是否有权限，任一一个即可
     *
     * @param userId 用户编号
     * @param permissions 权限
     * @return 是否
     */
    boolean hasAnyPermissions(Long userId, String... permissions);

    /**
     * 判断是否有角色，任一一个即可
     *
     * @param userId 用户编号
     * @param roles 角色数组
     * @return 是否
     */
    boolean hasAnyRoles(Long userId, String... roles);

    /**
     * 判断用户对某个资源是否有指定<b>动作</b>的权限
     *
     * <h3>与 {@link #hasAnyPermissions} 的区别</h3>
     *
     * 那个方法是给菜单/按钮用的，语义只有「能不能点」（动作固定为 VIEW）。
     * 而流程资源有四种互不相同的动作（v9 §7.2）：
     * {@code START} 发起 / {@code DESIGN} 设计 / {@code MANAGE} 管理 /
     * {@code VIEW_ALL} 看全部实例。用 VIEW 一把抓的话，
     * 「能看到这个流程」就等于「能发起、能改设计」——等于没区分。
     *
     * ⚠️ 资源行不存在时返回 {@code false}（严格模式）。调用方若需要
     * 「存量对象尚未登记资源时不要一刀切」，得自己做回落 ——
     * 这是 S4c §5.3 踩过的坑：不回落的话，升级瞬间存量对象的相关功能集体失效。
     *
     * @param userId     用户编号
     * @param permission 资源的权限标识，如 {@code bpm:process:leave}
     * @param action     动作码，如 {@code START} / {@code MANAGE}
     */
    boolean hasResourceAction(Long userId, String permission, String action);

    /**
     * 判断某个资源的某个动作上，是否存在<b>针对指定主体的显式 DENY 授权</b>
     *
     * <h3>为什么需要「显式 DENY」这种问法</h3>
     *
     * 常规判定回答的是「最终允不允许」，而它会受 {@code default_effect} 影响。
     * 有些控制点的基线是<b>放行</b>（比如子流程调起：现存的父子编排必须继续能跑），
     * 只有管理员明确禁止时才拦。这种场景问「最终允不允许」会得到错误答案 ——
     * 流程资源的 default_effect 是 DENY，一问就全被拦住。
     *
     * 所以单独提供这个问法：<b>只看有没有人明确说「不」</b>。
     *
     * @param subjectType 主体类型，如 {@code SYSTEM}
     * @param subjectCode 主体编码，如 {@code SYSTEM:bpm-engine}
     * @param permission  资源的权限标识
     * @param action      动作码
     */
    boolean hasExplicitDeny(String subjectType, String subjectCode, String permission, String action);

    /**
     * 获得登陆用户的部门数据权限
     *
     * @param userId 用户编号
     * @return 部门数据权限
     */
    DeptDataPermissionRespDTO getDeptDataPermission(Long userId);

    /**
     * 解析某张物理表的行过滤条件
     *
     * 给<b>自己拼 SQL</b> 的调用方使用（在线表单运行时用 JdbcTemplate 拼原生 SQL，
     * MyBatis-Plus 的数据权限拦截器对它完全不生效）。详见
     * {@link DataRowFilterRespDTO} 的类注释。
     *
     * @param userId     用户编号
     * @param tableName  物理表名
     * @param tableAlias 列名前缀，形如 {@code `biz_order`.}；无 JOIN 时传 null 或空串。
     *                   ⚠️ 有 JOIN 的查询里必须传 —— 裸列名在多表下会歧义，
     *                   而歧义的表现是 SQL 直接报错（还算好的），
     *                   或者匹配到关联表的同名列（那就是错误的过滤）
     * @return 行过滤条件
     */
    DataRowFilterRespDTO getDataRowFilter(Long userId, String tableName, String tableAlias);

    /**
     * 同上，并额外告知「本次查询里每张表的合法限定符」
     *
     * <h3>为什么需要它</h3>
     *
     * 槽位映射可以绑到<b>关联表</b>上（{@code SlotMappingDO.tableName}），
     * 而 {@code tableAlias} 说的只是主表。只有一个前缀时，
     * 求值器分不清某个列该用主表限定还是关联表限定 ——
     * 用错了会生成一条<b>语法合法、语义错误</b>的 SQL（匹配到主表的同名列），
     * 或者在带别名的 JOIN 上直接报 Unknown column。
     *
     * <h3>为什么 value 是「限定符」而不是「别名」</h3>
     *
     * JOIN 的关联表带了别名之后，物理表名就<b>不是</b>合法的限定符了。
     * 只有拼 FROM/JOIN 的那一层知道这次查询里它到底叫什么，所以由调用方现场算好交进来。
     *
     * @param tableQualifiers 物理表名（小写）→ 该表在本次查询里合法的限定前缀
     *                        （含结尾的点，无 JOIN 时是空串）。
     *                        ⚠️ 必须把主表也放进去（值就是 tableAlias）。
     *                        传 null 表示「只有单表」，此时一律用 tableAlias
     */
    default DataRowFilterRespDTO getDataRowFilter(Long userId, String tableName, String tableAlias,
                                                 Map<String, String> tableQualifiers) {
        return getDataRowFilter(userId, tableName, tableAlias);
    }

    /**
     * 批量解析数据集的列/字段显示模式
     *
     * <h3>为什么入参是 datasetCode + fieldKeys，而不是让调用方传权限标识</h3>
     *
     * 列/字段资源的命名规则在 {@code DatasetResourceNaming}（system 模块内部），
     * infra 看不到它。让 infra 自己拼权限串等于把命名规则复制一份到另一个模块，
     * 两份迟早漂 —— 而漂的表现是「配了字段权限不生效」，没有任何报错。
     *
     * <h3>为什么必须批量</h3>
     *
     * 一张表单几十个字段，逐个走「主体闭包 + 资源链 + 授权查询」会把列表页拖垮。
     * 实现侧在一次调用里复用同一份主体闭包与授权行。
     *
     * @param userId      用户编号
     * @param datasetCode 数据集编码，即在线表单的 code
     * @param fieldKeys   列/字段标识，子表明细形如 {@code items.mobile}
     * @param column      true = 列表列（COLUMN），false = 详情字段（FIELD）。
     *                    两者分开控制 —— 「列表里隐掉手机号」不该顺带把详情里的也隐掉
     * @return 字段模式；<b>查不到资源行的字段是 EDITABLE 而不是 HIDDEN</b>
     */
    DatasetFieldModeRespDTO getDatasetFieldModes(Long userId, String datasetCode,
                                                 Collection<String> fieldKeys, boolean column);

    /**
     * 批量解析字段显示模式（<b>入参是数据集的权限标识本身</b>，S7a）
     *
     * <h3>为什么原生模块不能复用上面那个方法</h3>
     *
     * {@link #getDatasetFieldModes} 的 {@code datasetCode} 是<b>在线表单的 code</b>，
     * 实现侧会给它拼上 {@code infra:form-design-run:} 前缀。
     * 原生模块的数据集权限串是模块自己的前缀（{@code system:user}），
     * 拿它当 code 传进去会被拼成 {@code infra:form-design-run:system:user:fld:mobile} ——
     * 这个串在资源树上不存在，于是所有字段一律判成「没配过权限 = 放行」，
     * <b>配了字段权限一律不生效且不报错</b>。
     *
     * 两个方法的判定逻辑完全相同，区别只在权限串怎么来。
     *
     * @param userId            用户编号
     * @param datasetPermission 数据集资源的权限标识，如 {@code system:user}
     * @param fieldKeys         字段标识
     * @param column            true = 列表列（COLUMN），false = 详情字段（FIELD）
     * @return 字段模式；<b>查不到资源行的字段是 EDITABLE 而不是 HIDDEN</b>
     */
    DatasetFieldModeRespDTO getFieldModes(Long userId, String datasetPermission,
                                          Collection<String> fieldKeys, boolean column);

}
