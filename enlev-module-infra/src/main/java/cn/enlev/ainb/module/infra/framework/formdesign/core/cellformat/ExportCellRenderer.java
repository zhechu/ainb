package cn.enlev.ainb.module.infra.framework.formdesign.core.cellformat;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.dict.core.DictFrameworkUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 导出单元格的渲染 —— 列表怎么显示，导出就怎么导
 *
 * <h3>为什么不是简单地「把列表的文本抄进 Excel」</h3>
 *
 * 那样做导出的文件就<b>改完导不回来</b>了：金额变成带逗号的文本、
 * 百分比变成带 % 的文本，回导时要再解析一遍，而解析必然有歧义
 * （{@code 13.00%} 到底是 0.13 还是 13？）。
 *
 * Excel 里「显示格式」与「值」本来就是两层。所以数值、日期这类
 * 一律写<b>真值 + 数字格式</b>：用户看到的和列表一样，
 * 而单元格的真值仍然是 0.13、是日期，导回来零歧义。
 *
 * 字典与布尔没有对应的数字格式可用，只能写文本 ——
 * 而导入侧本来就支持中文标签反查与六种布尔写法，同样能回导。
 *
 * @author 走进新时代
 */
@Slf4j
@Component
public class ExportCellRenderer {

    @Resource
    private CellFormatContractRegistry registry;

    /**
     * 渲染出来的一个单元格
     */
    @Data
    public static class Cell {

        /**
         * 要写进单元格的值。
         * 文本类是 String，数值类是 BigDecimal，日期类是 LocalDate / LocalDateTime
         */
        private Object value;

        /**
         * Excel 的数字格式串。为空表示不设格式（文本类）
         */
        private String numFmt;

        static Cell text(Object value) {
            Cell cell = new Cell();
            cell.setValue(value);
            return cell;
        }

        static Cell formatted(Object value, String numFmt) {
            Cell cell = new Cell();
            cell.setValue(value);
            cell.setNumFmt(numFmt);
            return cell;
        }
    }

    /**
     * 渲染一个单元格
     *
     * @param value     库里读出来的原始值
     * @param formatter 列上配的格式化（{@code listConf.columns[].formatter}），可为 null
     * @return 渲染结果。值为空时返回空单元格而不是占位符「-」——
     *         占位符是给屏幕看的，写进 Excel 会让整列变成文本，
     *         用户没法对这一列求和
     */
    @SuppressWarnings("unchecked")
    public Cell render(Object value, Map<String, Object> formatter) {
        if (isEmpty(value)) {
            return Cell.text(null);
        }
        String type = formatter == null ? null : asText(formatter.get("type"));
        CellFormatContract contract = registry.get(type);
        CellFormatContract.ExportRule rule = contract.getExport();

        if ("VALUE_WITH_FMT".equals(rule.getMode())) {
            return renderWithFormat(value, formatter, rule);
        }
        return renderText(value, formatter, rule);
    }

    // ==================== 文本类 ====================

    private Cell renderText(Object value, Map<String, Object> formatter,
                            CellFormatContract.ExportRule rule) {
        String source = String.valueOf(rule.getSource());
        switch (source) {
            case "DICT_LABEL": {
                String dictType = formatter == null ? null : asText(formatter.get("dictType"));
                // ⚠️ 标签色不导出，理由见契约文件里 TAG 那一条的注释：
                //    跨模块拿不到 colorType，且颜色不承载数据
                return Cell.text(dictLabel(dictType, value));
            }
            case "BOOLEAN_TEXT":
                return Cell.text(registry.isTruthy(value)
                        ? registry.getBooleanText().getTrueText()
                        : registry.getBooleanText().getFalseText());
            case "FILE_URL":
                return Cell.text(fileUrls(value));
            default:
                return Cell.text(plainText(value));
        }
    }

    /**
     * 字典翻译
     *
     * 翻不出来时<b>原样返回编码</b>，不返回空 ——
     * 空值会让人以为这条数据没填，而实际是字典里少了一项。
     * 与前端 {@code getDictLabel} 的兜底一致。
     */
    private String dictLabel(String dictType, Object value) {
        String raw = plainText(value);
        if (StrUtil.isBlank(dictType)) {
            return raw;
        }
        try {
            String label = DictFrameworkUtils.parseDictDataLabel(dictType, raw);
            return StrUtil.isNotBlank(label) ? label : raw;
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * 文件类字段取 URL
     *
     * ⚠️ 故意不跟随列表（列表显示的是文件名）。
     * 文件名不唯一、也定位不到文件，导回来就断了。
     */
    @SuppressWarnings("unchecked")
    private String fileUrls(Object value) {
        if (value instanceof Collection<?> collection) {
            List<String> urls = collection.stream().map(this::singleFileUrl)
                    .filter(StrUtil::isNotBlank).toList();
            return String.join(",", urls);
        }
        return singleFileUrl(value);
    }

    private String singleFileUrl(Object item) {
        if (item instanceof Map<?, ?> map) {
            Object url = map.get("url");
            return url != null ? String.valueOf(url) : "";
        }
        return item == null ? "" : String.valueOf(item);
    }

    // ==================== 值 + 格式 ====================

    private Cell renderWithFormat(Object value, Map<String, Object> formatter,
                                  CellFormatContract.ExportRule rule) {
        String valueKind = String.valueOf(rule.getValueKind());
        if ("DATE".equals(valueKind) || "DATETIME".equals(valueKind)) {
            Object temporal = toTemporal(value, "DATE".equals(valueKind));
            // 解析不出日期时退回文本 —— 写一个坏的日期值会让 Excel 提示文件损坏，
            // 比「这一格是文本」糟得多
            return temporal != null
                    ? Cell.formatted(temporal, rule.getNumFmt())
                    : Cell.text(plainText(value));
        }

        BigDecimal number = toDecimal(value);
        if (number == null) {
            return Cell.text(plainText(value));
        }

        Integer precision = asInt(formatter == null ? null : formatter.get("precision"));
        boolean thousands = formatter != null && Boolean.TRUE.equals(formatter.get("thousands"));
        boolean alreadyScaled = formatter != null
                && Boolean.TRUE.equals(formatter.get("alreadyScaled"));

        // ⚠️ 百分比：Excel 的 % 格式会把值自动乘 100 显示。
        //    列配了 alreadyScaled（库里存 13 而不是 0.13）时要先除回去，
        //    不除的话印出来是 1300.00%
        if (Boolean.TRUE.equals(rule.getDivideBy100WhenAlreadyScaled()) && alreadyScaled) {
            number = number.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
        }

        String numFmt = StrUtil.isNotBlank(rule.getNumFmt())
                ? rule.getNumFmt()
                : registry.buildNumFmt(precision, thousands, rule.getNumFmtSuffix());
        return Cell.formatted(number, numFmt);
    }

    // ==================== 工具 ====================

    private boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence cs) {
            return cs.isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        return false;
    }

    /**
     * 非格式化值的文本形态
     *
     * 多值字段用逗号连接，与列表一致；上传类的元素取文件名。
     */
    private String plainText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::plainElement).toList()
                    .stream().filter(StrUtil::isNotBlank)
                    .reduce((a, b) -> a + ", " + b).orElse("");
        }
        if (value instanceof Map<?, ?> map) {
            Object name = map.get("name");
            return name != null ? String.valueOf(name) : String.valueOf(value);
        }
        return String.valueOf(value);
    }

    private String plainElement(Object item) {
        if (item instanceof Map<?, ?> map) {
            Object name = map.get("name");
            if (name != null) {
                return String.valueOf(name);
            }
        }
        return item == null ? "" : String.valueOf(item);
    }

    private BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 转成 Excel 认得的日期类型
     *
     * ⚠️ 必须给 LocalDate / LocalDateTime，不能给字符串 ——
     * 给字符串的话单元格是文本，配了 numFmt 也不起作用，
     * 表现是「格式配了但没生效」，而且那一列没法排序。
     */
    private Object toTemporal(Object value, boolean dateOnly) {
        java.time.LocalDateTime dateTime = parseDateTime(value);
        if (dateTime == null) {
            return null;
        }
        return dateOnly ? dateTime.toLocalDate() : dateTime;
    }

    private java.time.LocalDateTime parseDateTime(Object value) {
        if (value instanceof java.time.LocalDateTime dt) {
            return dt;
        }
        if (value instanceof java.time.LocalDate d) {
            return d.atStartOfDay();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().atStartOfDay();
        }
        if (value instanceof java.util.Date d) {
            return d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        // 运行时的 TemporalValueCoercer 已经把时间类统一成字符串，
        // 所以这里主要处理 "2026-09-14" 与 "2026-09-14 10:30:00" 两种
        for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm")) {
            try {
                return java.time.LocalDateTime.parse(text,
                        java.time.format.DateTimeFormatter.ofPattern(pattern));
            } catch (java.time.format.DateTimeParseException ignored) {
                // 试下一个
            }
        }
        try {
            return java.time.LocalDate.parse(text).atStartOfDay();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

}
