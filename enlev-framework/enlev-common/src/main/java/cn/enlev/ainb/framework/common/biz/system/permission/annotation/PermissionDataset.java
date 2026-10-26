package cn.enlev.ainb.framework.common.biz.system.permission.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一张<b>原生模块</b>的表是「数据集」，并给出它的语义槽位（S7a）
 *
 * <h3>它解决的问题：原生模块此前一行都进不了行级权限</h3>
 *
 * 行级权限的两条通道里，MyBatis 那条（{@code RuleDataPermissionRule}）
 * 只对<b>注册过的表</b>生效，而注册的唯一入口一直是「在线表单发布」。
 * 也就是说：{@code system_users}、{@code infra_file} 这些原生模块的表
 * <b>一张都没注册过</b> —— 在授权页上给它们配行级规则，配了也不会被调用，
 * 且不报错。典型的「配了、存了、没生效」。
 *
 * 标了本注解的类，启动时会被登记成 DATASET 资源、写入槽位映射、并注册进
 * 行级规则的表名集合，于是规则真的开始工作。
 *
 * <h3>为什么槽位要显式写，不像在线表单那样猜</h3>
 *
 * 在线表单的槽位是 {@code SlotInferencer} 按列名猜的 —— 那是不得已：
 * 表单由用户设计，开发期拿不到列名。原生模块的表结构在代码里摆着，
 * 猜错的代价（按错误的列过滤 / 规则被静默跳过）却和在线表单一样大。
 * 能确定就不要猜。
 *
 * <h3>用法</h3>
 *
 * <pre>
 * &#64;PermissionDataset(value = "system:user", name = "用户", owner = "id", dept = "dept_id")
 * &#64;TableName("system_users")
 * public class AdminUserDO extends BaseDO { ... }
 * </pre>
 *
 * {@link #table()} 留空时自动读同一个类上的 MyBatis Plus {@code @TableName}。
 *
 * ⚠️ <b>标上它等于打开这张表的行级过滤</b>。没有人配规则时行为不变
 * （{@code DataRowFilterResolver} 对「没配规则」返回不过滤，有对照单测钉住），
 * 但一旦有人配了规则就会立刻生效 —— 这正是本注解的目的，
 * 也意味着不要给不该被过滤的基础数据表（字典、地区…）随手标上。
 *
 * @author 走进新时代
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PermissionDataset {

    /**
     * 数据集的权限标识，如 {@code system:user}
     *
     * ⚠️ 与该模块已有的权限串前缀保持一致（{@code system:user:query} 等动作的父段），
     * 这样六个动作资源与本数据集在资源树上是同一条祖先链 ——
     * 换一个串的话，「给整张表授权」会落不到一个节点上
     */
    String value();

    /**
     * 展示名，授权页上显示。留空时取 {@link #value()}
     */
    String name() default "";

    /**
     * 物理表名。留空时读同类上的 {@code @TableName}
     */
    String table() default "";

    /**
     * owner 槽位对应的列名（记录归属人的用户编号）。留空表示这张表没有归属人
     */
    String owner() default "";

    /**
     * dept 槽位对应的列名（记录归属部门）
     */
    String dept() default "";

    /**
     * status 槽位对应的列名（记录状态）
     */
    String status() default "";

    /**
     * bizKey 槽位对应的列名（业务主键），默认 {@code id}
     *
     * <h3>它和上面三个不是一回事</h3>
     *
     * owner/dept/status 是「按属性圈一批行」，bizKey 是「按一串具体主键点名放行」。
     * 派生可见性（流程参与者可见）与记录共享都落在这个槽位上。
     *
     * ⚠️ 默认给 {@code id} 而不是留空：留空的话这两种规则在该表上求不出条件，
     * 而求不出条件的 ALLOW 会贡献恒假（TODO #28）—— 表现是
     * 「配了『共享给我的记录』反而一行都看不到」。
     * 主键列不叫 id 的表显式写出来即可
     */
    String bizKey() default "id";

    /**
     * 需要做字段级控制的字段名（VO / DO 的属性名）
     *
     * 与在线表单一样是<b>按需创建</b>：不写在这里的字段不落 {@code sys_resource} 行，
     * 判定时按「查不到资源行 = 放行」处理。全表字段都登记的话，
     * 一个中等规模的系统会多出几万行资源，而其中 99% 永远不会被授权
     */
    String[] fields() default {};

    /**
     * 需要<b>列级</b>权限控制的列名（列表里的一列），登记成 {@code :col:} 资源
     *
     * <h3>与 {@link #fields()} 的区别：列表 vs 详情</h3>
     *
     * {@code COLUMN} 管「列表里这一列显不显示」，{@code FIELD} 管「详情里这个字段能不能看」。
     * 分开的意义是支持<b>「列表里不显示手机号，但点进详情能看」</b>这类配置 ——
     * 合成一套的话，配一次两边一起变。
     *
     * 留空表示这张表不做列级控制。两者可以写同样的名字，
     * 会生成两条互不影响的资源（{@code xxx:col:mobile} 与 {@code xxx:fld:mobile}）
     */
    String[] columns() default {};

    /**
     * 字段/列的中文展示名，条目格式 {@code 字段名=中文名}，如 {@code mobile=手机号}
     *
     * 登记 {@code :fld:} / {@code :col:} 资源时，资源名会生成
     * {@code 数据集名:字段:中文名} / {@code 数据集名:列:中文名} ——
     * 授权页的资源树是给管理员看的，一树英文字段名没法读。
     * 对 {@link #fields()} 与 {@link #columns()} 同时生效；
     * 没配 label 的字段回落显示字段名本身，建议写全。
     *
     * ⚠️ 改这里等于改资源名：启动登记时会把已存在的同名资源一并改名
     */
    String[] labels() default {};

    /**
     * 上面这些字段是否默认不可见（落行时 {@code default_effect = DENY}）
     *
     * 默认 false，与在线表单「配了脱敏也保持 ALLOW」同一个理由：
     * 翻成 DENY 会让这些字段在接入那天对所有人直接消失，
     * 而没有任何人配置过什么
     */
    boolean sensitive() default false;

}
