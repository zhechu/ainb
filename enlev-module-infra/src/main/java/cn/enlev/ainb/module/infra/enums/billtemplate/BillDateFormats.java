package cn.enlev.ainb.module.infra.enums.billtemplate;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 单据号日期段的可选格式
 *
 * 为什么是白名单而不是让用户自由填 pattern：
 * {@code date_format} 最终会进 {@link java.time.format.DateTimeFormatter#ofPattern}，
 * 自由输入既可能抛异常（非法 pattern），也可能塞进 {@code 'literal'} 之类的东西
 * 把号码搞得面目全非。白名单覆盖了实际用得到的全部粒度。
 *
 * 白名单里直接用 Java 的 pattern 字面量，前后端共用同一套取值，
 * 中间不做任何格式转换 —— 转换表是最容易漏改的地方，新增一种粒度就得记得两边都加。
 *
 * @author 走进新时代
 */
public interface BillDateFormats {

    /**
     * 不含日期段的标记
     */
    String NONE = "NONE";

    String YEAR = "yyyy";
    String MONTH = "yyyyMM";
    String DAY = "yyyyMMdd";
    String HOUR = "yyyyMMddHH";
    String MINUTE = "yyyyMMddHHmm";
    String SECOND = "yyyyMMddHHmmss";
    String MILLIS = "yyyyMMddHHmmssSSS";

    /**
     * 全部合法值。用 LinkedHashSet 保持粒度从粗到细的顺序，报错信息里的枚举顺序才稳定
     */
    Set<String> ALL = new LinkedHashSet<>(java.util.List.of(
            NONE, YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, MILLIS));

    /**
     * 是否合法。空值视为合法 —— 等同于 {@link #NONE}
     */
    static boolean isValid(String dateFormat) {
        return StrUtil.isBlank(dateFormat) || ALL.contains(dateFormat);
    }

    /**
     * 是否需要输出日期段
     */
    static boolean hasDate(String dateFormat) {
        return StrUtil.isNotBlank(dateFormat) && !NONE.equalsIgnoreCase(dateFormat);
    }

}
