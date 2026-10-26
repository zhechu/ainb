package cn.enlev.ainb.module.system.dal.dataobject.permission;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import cn.enlev.ainb.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 语义槽位映射
 *
 * 把「语义槽位名称」（如 owner）映射到「某张表单的真实列名」（如 create_user_id）。
 * 这样行过滤规则只需要引用槽位名，不需要知道每张表单叫什么字段，
 * 同一条规则可以跨表单复用。
 *
 * ## 防注入机制（关键）
 *
 * 条件树求值器（{@code ConditionTreeEvaluator}）在生成 SQL 时，
 * 列名只从这张映射表里取（过标识符正则 + 写入时核实列真的存在），不从用户输入里取。
 * 即使攻击者把 slot 设成 {@code '; DROP TABLE...}，也只会因为在映射表里查不到这个
 * 槽位而让整条规则「求不出来」，进而落成恒假条件（一行都看不到），不会生成恶意 SQL。
 *
 * {@link #tableName} 更是<b>永远不进 SQL</b> —— 它只当查限定符的 key 用，
 * 详见该字段的注释。
 *
 * ## 全局表，不分租户
 *
 * 槽位映射说的是「这张物理表的哪一列存创建人」—— 是表结构的事实，
 * 多租户是同库同表加 tenant_id 列，表结构对所有租户都一样，
 * 所以这份映射也对所有租户一样。跟 {@link ResourceDO} 一并不分租户。
 *
 * @author 走进新时代
 */
@TableName("sys_slot_mapping")
@KeySequence("sys_slot_mapping_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@TenantIgnore
public class SlotMappingDO extends BaseDO {

    @TableId
    private Long id;

    /**
     * 资源编号，关联 {@link ResourceDO#getId()}，通常是 DATASET 类型的资源
     */
    private Long resourceId;

    /**
     * 语义槽位名称，如 owner / dept / status
     * 规则模板里用 {owner} 来引用
     */
    private String slot;

    /**
     * {@link #columnName} 所在的物理表名（小写）。为空表示数据集的主表
     *
     * <h3>为什么必须记这张表</h3>
     *
     * 在线表单的列表查询是带 JOIN 的（{@code TableBinding.joinTables}）。
     * 只存列名的话，「这个槽位对应关联表上的那一列」就表达不出来 ——
     * 求值时只有一个前缀（主表的），关联表的列压根配不上槽位。
     *
     * <h3>🔴 它存的是<b>物理表名</b>，而拼进 SQL 的必须是<b>本次查询里合法的限定符</b></h3>
     *
     * 两者不是一回事：JOIN 的关联表可以带别名，一旦带了别名，
     * 用物理表名去限定就是非法 SQL（MySQL 报 {@code Unknown column 'tbl.col'}）。
     * 所以本字段<b>只当 key 用</b> —— 拿它去调用方现场给的
     * 「物理表名 → 合法限定前缀」这张表里查（见 {@code ConditionTreeEvaluator}）。
     * 查不到就说明这张表不在本次查询里，整条规则按「求不出来」处理，走 fail-closed。
     *
     * 这样即便这里被写了个乱七八糟的表名，它也进不了 SQL —— 顶多让规则失效，
     * 而失效是有人会来报的那种失败方式。
     */
    private String tableName;

    /**
     * 真实列名，如 create_user_id
     *
     * ⚠️ 生成 SQL 前必须过标识符正则；写入时还会核实这一列在 {@link #tableName} 上真的存在
     */
    private String columnName;

    /** 槽位说明，如「记录创建者用户 id」 */
    private String description;

}
