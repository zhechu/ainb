package cn.enlev.ainb.module.infra.framework.formdesign.core;

import cn.hutool.core.util.StrUtil;

/**
 * 表单字段名与物理列名的命名约定
 *
 * 核心是「主表零转换，副表加前缀」：
 *
 * <ul>
 *   <li><b>主表字段</b>：field 直接等于物理列名。SELECT 不加 AS，查询结果的 key 天然就是 field，
 *       前端按 field 取值即可，中间不需要任何映射查找。</li>
 *   <li><b>副表字段</b>：field 为 {@code yd_<表名>_yd_<列名>}。SELECT 时用
 *       {@code column AS yd_表名_yd_列名} 还原成 field，这样多张表的同名列
 *       （比如主表和副表都有 remark）不会互相覆盖。</li>
 * </ul>
 *
 * 为什么分隔符用双标记 {@code _yd_} 而不是单个下划线：
 * 表名和列名本身都可能含下划线（test_customer_ext、tax_no），
 * 单下划线无法反向拆分。双标记让拆分唯一确定 ——
 * 第一个 {@code yd_} 之后到最后一个 {@code _yd_} 之前是表名，之后是列名。
 *
 * 为什么前缀不以下划线开头：form-create 的 field 禁止下划线开头（与内部保留字冲突）。
 *
 * @author 走进新时代
 */
public final class FormFieldNaming {

    /**
     * 副表字段的前缀标记
     */
    private static final String PREFIX = "yd_";

    /**
     * 表名与列名之间的分隔标记
     */
    private static final String SEPARATOR = "_yd_";

    private FormFieldNaming() {
    }

    /**
     * 生成副表字段名
     *
     * @param table  副表名
     * @param column 物理列名
     * @return 形如 {@code yd_test_customer_ext_yd_tax_no}
     */
    public static String sideField(String table, String column) {
        return PREFIX + table + SEPARATOR + column;
    }

    /**
     * 判断是否为副表字段
     */
    public static boolean isSideField(String field) {
        return StrUtil.isNotBlank(field)
                && field.startsWith(PREFIX)
                && field.indexOf(SEPARATOR, PREFIX.length()) > 0;
    }

    /**
     * 从副表字段名里取出表名
     *
     * @return 不是副表字段时返回 null
     */
    public static String extractTable(String field) {
        if (!isSideField(field)) {
            return null;
        }
        int sepIdx = field.lastIndexOf(SEPARATOR);
        return field.substring(PREFIX.length(), sepIdx);
    }

    /**
     * 从副表字段名里取出物理列名
     *
     * @return 不是副表字段时原样返回（主表字段的 field 就等于列名）
     */
    public static String extractColumn(String field) {
        if (!isSideField(field)) {
            return field;
        }
        int sepIdx = field.lastIndexOf(SEPARATOR);
        return field.substring(sepIdx + SEPARATOR.length());
    }

}
