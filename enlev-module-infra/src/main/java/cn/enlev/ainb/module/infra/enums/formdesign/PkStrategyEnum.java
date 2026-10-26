package cn.enlev.ainb.module.infra.enums.formdesign;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 主键策略枚举
 *
 * 用于 {@link cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding}
 * 的 pkStrategy 字段。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum PkStrategyEnum {

    /**
     * 数据库自增
     *
     * INSERT 后通过 JDBC getGeneratedKeys 取回主键。
     */
    AUTO("AUTO", "数据库自增"),

    /**
     * 雪花算法
     *
     * INSERT 前由应用层生成 Long 型主键。
     */
    SNOWFLAKE("SNOWFLAKE", "雪花算法"),

    /**
     * UUID
     *
     * INSERT 前由应用层生成 String 型 UUID（去掉连字符的 32 位小写）。
     */
    UUID("UUID", "UUID");

    /**
     * 策略代码
     */
    private final String code;
    /**
     * 策略名称
     */
    private final String name;

}
