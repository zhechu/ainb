package cn.enlev.ainb.module.infra.enums.formdesign;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 列表列格式化类型枚举
 *
 * 用于 list_conf.columns[].formatter.type。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum FormatterTypeEnum {

    NONE("NONE", "无格式化"),
    DICT("DICT", "字典翻译"),
    DATE("DATE", "日期格式化"),
    DATETIME("DATETIME", "日期时间格式化"),
    NUMBER("NUMBER", "数字格式化"),
    BOOLEAN("BOOLEAN", "布尔值"),
    LINK("LINK", "链接"),
    IMAGE("IMAGE", "图片"),
    TAG("TAG", "标签");

    /**
     * 类型代码
     */
    private final String code;
    /**
     * 类型名称
     */
    private final String name;

}
