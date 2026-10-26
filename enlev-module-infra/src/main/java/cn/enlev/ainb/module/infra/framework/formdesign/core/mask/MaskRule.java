package cn.enlev.ainb.module.infra.framework.formdesign.core.mask;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 脱敏策略
 *
 * 每种策略只描述「保留前几位、保留后几位」，具体遮掩由 {@link #apply} 统一处理 ——
 * 各策略自己写一套字符串截取，几乎必然在边界情况上出现分歧
 * （值比要保留的位数还短时怎么办）。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum MaskRule {

    /**
     * 不脱敏
     */
    NONE(0, 0, "不脱敏"),

    /**
     * 手机号：138****8000
     */
    MOBILE(3, 4, "手机号"),

    /**
     * 身份证：110***********1234
     */
    ID_CARD(3, 4, "身份证"),

    /**
     * 银行卡：6222**********1234
     */
    BANK_CARD(4, 4, "银行卡"),

    /**
     * 姓名：张**
     *
     * 只保留姓。保留后一位的话「张三」会变成「张三」——等于没脱敏
     */
    NAME(1, 0, "姓名"),

    /**
     * 邮箱：ab****@example.com
     *
     * 邮箱的遮掩规则与其它不同（只遮 @ 前面的部分），单独处理，见 apply
     */
    EMAIL(2, 0, "邮箱"),

    /**
     * 自定义：前后保留位数由字段配置决定
     */
    CUSTOM(0, 0, "自定义");

    private final int keepPrefix;
    private final int keepSuffix;
    private final String label;

    public static MaskRule parse(String name) {
        if (StrUtil.isBlank(name)) {
            return NONE;
        }
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            // 认不出来的策略按不脱敏处理，而不是按最严格处理 ——
            // 后者会让一个拼错的配置把正常字段变成一片星号，用户会以为数据坏了
            return NONE;
        }
    }

    /**
     * 执行脱敏
     *
     * @param value       原值
     * @param filler      补位符，默认 *
     * @param keepPrefix  自定义策略时的前保留位数
     * @param keepSuffix  自定义策略时的后保留位数
     */
    public String apply(Object value, String filler, Integer keepPrefix, Integer keepSuffix) {
        if (this == NONE || value == null) {
            return value == null ? null : String.valueOf(value);
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return text;
        }
        String fill = StrUtil.isNotBlank(filler) ? filler : "*";

        if (this == EMAIL) {
            return maskEmail(text, fill);
        }

        int prefix = this == CUSTOM ? nullToZero(keepPrefix) : this.keepPrefix;
        int suffix = this == CUSTOM ? nullToZero(keepSuffix) : this.keepSuffix;
        return maskMiddle(text, prefix, suffix, fill);
    }

    /**
     * 遮掩中间部分
     *
     * 值比要保留的位数还短时全部遮掉 —— 这是关键的边界：
     * 一个只有 3 位的「手机号」若按「保留前 3 后 4」处理，
     * 会原样输出全部内容，等于没脱敏。脏数据不该成为脱敏的绕过口。
     */
    private String maskMiddle(String text, int prefix, int suffix, String fill) {
        int length = text.length();
        if (prefix + suffix >= length) {
            return fill.repeat(length);
        }
        return text.substring(0, prefix)
                + fill.repeat(length - prefix - suffix)
                + text.substring(length - suffix);
    }

    /**
     * 邮箱只遮 @ 前面的部分，域名保留
     *
     * 遮掉域名意义不大（域名本身不敏感），却会让用户认不出是哪个邮箱。
     */
    private String maskEmail(String text, String fill) {
        int at = text.indexOf('@');
        if (at <= 0) {
            // 不是合法邮箱，退化成中间遮掩，而不是原样返回
            return maskMiddle(text, 2, 0, fill);
        }
        String local = text.substring(0, at);
        String domain = text.substring(at);
        if (local.length() <= 2) {
            return fill.repeat(local.length()) + domain;
        }
        return local.substring(0, 2) + fill.repeat(local.length() - 2) + domain;
    }

    private int nullToZero(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

}
