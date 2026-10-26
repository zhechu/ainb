package cn.enlev.ainb.framework.mybatis.core.util;

/**
 * 表操作类型
 *
 * 用于 {@link SystemTablePolicy} 区分「这张表能不能被这么操作」。
 * 拆开的理由见 SystemTablePolicy 的类注释 —— 不同子系统拦的本来就不是同一件事。
 *
 * @author 走进新时代
 */
public enum TableOperation {

    /**
     * 改表结构：建表、改表、删表
     *
     * 最严 —— 连未启用模块的前缀也占住，避免将来撞名。
     */
    DDL,

    /**
     * 写数据：insert / update / delete
     *
     * 只禁系统核心表。业务模块表（crm_/erp_ 等）是合法的写入目标。
     */
    WRITE,

    /**
     * 读数据：select
     *
     * 一律放行 —— 做报表经常需要读系统表。不要改坏这条。
     */
    READ,

    /**
     * 登记纳管：把已存在的物理表导入数据建模
     *
     * 口径与 DDL 一致 —— 登记即纳入受管范围，不该把别的模块的表拉进来。
     */
    REGISTER

}
