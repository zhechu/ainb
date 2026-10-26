package cn.enlev.ainb.module.infra.enums.formdesign;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 系统字段填充方式枚举
 *
 * 用于 {@link cn.enlev.ainb.module.infra.framework.formdesign.core.binding.AutoFillConfig}
 * 的四个 xxxMode 字段。
 *
 * 存在的原因：审计列可能由后端自动填，也可能需要用户在表单里手填（数据补录、
 * 代客户填写），还可能交给数据库默认值。三者的写入行为完全不同，必须让用户显式选择，
 * 否则后端自动填充与表单字段映射会同时写同一列，生成
 * INSERT INTO t (creator, creator) 这种非法 SQL。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum AutoFillModeEnum {

    /**
     * 系统自动填充
     *
     * 后端填当前登录人 / 当前时间。该列不该同时出现在表单字段映射里。
     */
    AUTO("AUTO", "系统自动填充"),

    /**
     * 表单手工输入
     *
     * 后端跳过该列，完全交给 fieldMapping 负责。
     */
    MANUAL("MANUAL", "表单手工输入"),

    /**
     * 不填充
     *
     * 后端与表单都不写，保持数据库默认值或触发器的结果。
     */
    NONE("NONE", "不填充");

    /**
     * 方式代码
     */
    private final String code;
    /**
     * 方式名称
     */
    private final String name;

    /**
     * 按代码解析，解析不到返回 {@link #AUTO}
     *
     * 不抛异常：历史配置里没有 mode 字段，读到 null 时应回退到原有行为
     * （改动前的实现就是无条件自动填充），而不是让运行时崩掉。
     */
    public static AutoFillModeEnum parse(String code) {
        if (StrUtil.isBlank(code)) {
            return AUTO;
        }
        return Arrays.stream(values())
                .filter(mode -> mode.getCode().equalsIgnoreCase(code))
                .findFirst()
                .orElse(AUTO);
    }

}
