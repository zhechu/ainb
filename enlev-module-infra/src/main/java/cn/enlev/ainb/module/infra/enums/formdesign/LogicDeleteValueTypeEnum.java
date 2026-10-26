package cn.enlev.ainb.module.infra.enums.formdesign;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 逻辑删除值类型枚举
 *
 * 用于 {@link cn.enlev.ainb.module.infra.framework.formdesign.core.binding.LogicDeleteConfig}
 * 的 valueType 字段。
 *
 * 存在的原因：配置里的 deletedValue / normalValue 是字符串（JSON 结构决定），
 * 但物理列可能是 bit / tinyint / char。把字符串 "0" 直接绑给 bit(1) 列，
 * MySQL 会抛「Data truncation: Data too long for column」。
 * 因此绑参前必须按本枚举转换成对应的 Java 类型。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum LogicDeleteValueTypeEnum {

    /**
     * 布尔。对应 bit(1) 且希望以 true/false 绑定的场景
     */
    BOOLEAN("BOOLEAN", "布尔"),

    /**
     * 数字。对应 tinyint / int / bit(1)，是最常见的约定
     */
    NUMBER("NUMBER", "数字"),

    /**
     * 字符串。对应 char / varchar，如用 'Y'/'N' 标记删除的老表
     */
    STRING("STRING", "字符串");

    /**
     * 类型代码
     */
    private final String code;
    /**
     * 类型名称
     */
    private final String name;

    /**
     * 按代码解析，解析不到返回 {@link #NUMBER}
     *
     * 不抛异常：历史配置里没有 valueType 字段，读到 null 时应回退到默认值而不是让运行时崩掉。
     */
    public static LogicDeleteValueTypeEnum parse(String code) {
        if (StrUtil.isBlank(code)) {
            return NUMBER;
        }
        return Arrays.stream(values())
                .filter(type -> type.getCode().equalsIgnoreCase(code))
                .findFirst()
                .orElse(NUMBER);
    }

    /**
     * 把配置里的字符串值转换成绑参用的 Java 类型
     *
     * @param rawValue 配置里的原始字符串，如 "0" / "1" / "Y"
     * @return 转换后的值。空值返回 null（让数据库用默认值）
     */
    public Object convert(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return switch (this) {
            // "1" / "true" / "y" 都当真，容忍用户在配置里的不同写法
            case BOOLEAN -> "1".equals(trimmed)
                    || "true".equalsIgnoreCase(trimmed)
                    || "y".equalsIgnoreCase(trimmed);
            case NUMBER -> parseNumber(trimmed);
            case STRING -> trimmed;
        };
    }

    /**
     * 解析数字值
     *
     * 转不动时回退成原字符串而不是抛异常 —— 配置是用户填的，
     * 让数据库去报「类型不匹配」比在这里抛 NumberFormatException 更好定位。
     */
    private static Object parseNumber(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            if ("true".equalsIgnoreCase(value)) {
                return 1L;
            }
            if ("false".equalsIgnoreCase(value)) {
                return 0L;
            }
            return value;
        }
    }

}
