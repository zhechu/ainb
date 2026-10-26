package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictDataRespDTO;
import cn.enlev.ainb.framework.dict.core.DictFrameworkUtils;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单元格解析器
 *
 * 把一个 Excel 单元格里的文本，按目标字段的控件类型转成可以提交给运行时的值。
 *
 * <h3>为什么这件事值得单独写一层</h3>
 *
 * 用户手里的 Excel 是自由的：日期可以是 {@code 2026-09-14}、
 * {@code 2026/9/14}、{@code 2026年9月14日}，也可以是真的日期单元格；
 * 金额可能带千分位与货币符号；布尔可能是「是」「Y」「√」「启用」。
 *
 * 不管的话只有两种结局：要么整行被拒（用户不知道该怎么改），
 * 要么被后端当成脏值存进去（日期差一天、金额变成 0）。
 * 两条都是「导入了但数据不对」，比报错难查得多。
 *
 * <h3>解析失败不抛异常</h3>
 *
 * 返回 {@link Outcome#error}，由调用方汇总成「第几行第几列为什么不行」。
 * 抛异常的话第一条错误就把整次导入打断了，用户得改一处、导一次，
 * 来回几十轮 —— 这正是导入功能最容易被骂的地方。
 *
 * @author 走进新时代
 */
@Component
public class ImportCellReader {

    /**
     * 一次解析的结果
     */
    @Data
    public static class Outcome {

        /**
         * 解析出来的值。失败时为 null
         */
        private Object value;

        /**
         * 错误原因。成功时为 null
         */
        private String error;

        public static Outcome ok(Object value) {
            Outcome outcome = new Outcome();
            outcome.setValue(value);
            return outcome;
        }

        public static Outcome error(String message) {
            Outcome outcome = new Outcome();
            outcome.setError(message);
            return outcome;
        }

        public boolean isError() {
            return error != null;
        }
    }

    /**
     * 解析一个单元格
     *
     * @param raw    单元格原始值，可能是 String / Number / Date / Boolean
     * @param widget 目标字段
     * @return 解析结果
     */
    public Outcome read(Object raw, ImportWidget widget) {
        String text = toText(raw);
        if (text.isEmpty()) {
            // 空值一律交给必填校验去判 —— 这里报「不能为空」的话，
            // 非必填字段留空也会报错
            return Outcome.ok(null);
        }

        String type = widget.getType();
        // 单元格本来就是日期时直接用 —— 导出写的是 Excel 真日期，
        // 绕一圈格式化再解析回来没有收益，只有出错的机会
        if (List.of("datePicker", "dateRange").contains(type)) {
            Outcome direct = readTemporalDirect(raw, widget);
            if (direct != null) {
                return direct;
            }
        }
        return switch (type) {
            case "inputNumber", "slider", "rate" -> readNumber(text, widget);
            case "datePicker", "dateRange" -> readDate(text, widget);
            case "timePicker", "timeRange" -> readTime(text);
            case "switch" -> readBoolean(text);
            case "select", "radio", "DictSelect", "elTreeSelect", "tree", "cascader" ->
                    readSelect(text, widget);
            case "checkbox", "elTransfer" -> readMultiSelect(text, widget);
            case "UploadFile", "UploadImg", "UploadImgs", "Signature",
                 "signaturePad", "upload" -> readFileValue(text, widget);
            default -> Outcome.ok(text);
        };
    }

    // ==================== 数字 ====================

    /**
     * 数字
     *
     * 把「1,234.50」「￥1234.5」「１２３４」（全角）都收进来。
     * 用户从别处复制过来的金额基本都带千分位，
     * 直接 {@code new BigDecimal(text)} 会抛 NumberFormatException，
     * 而报出来的是一句英文异常，看不出是哪一列。
     */
    private Outcome readNumber(String text, ImportWidget widget) {
        String cleaned = text.trim()
                .replace(",", "").replace("，", "")
                .replace("￥", "").replace("¥", "").replace("$", "")
                .replace(" ", "").replace("%", "");
        cleaned = toHalfWidth(cleaned);

        if (cleaned.isEmpty() || "-".equals(cleaned) || "—".equals(cleaned)) {
            return Outcome.ok(null);
        }

        java.math.BigDecimal number;
        try {
            number = new java.math.BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return Outcome.error(String.format(
                    "必须是数字，「%s」无法识别（可带千分位与货币符号，如 1,234.50）", text));
        }

        // 精度越界：设计器上配了 precision，超了就是「填进去会在库里被截断」的那类问题。
        // 这里直接报出来而不是四舍五入 —— 用户填了 1.234，库里存 1.23，
        // 差额对不上账时没人会想到是导入时被舍掉的
        if (widget.getPrecision() != null && widget.getPrecision() >= 0) {
            int actualScale = Math.max(number.scale(), 0);
            if (actualScale > widget.getPrecision()) {
                return Outcome.error(String.format(
                        "小数位最多 %d 位，「%s」有 %d 位小数",
                        widget.getPrecision(), text, actualScale));
            }
        }
        // 去掉多余的尾零：库里是 decimal(18,2)，1.5000 与 1.5 存进去一样，
        // 但提交时带上 1.5000 会让「值有没有变」的判断（乐观锁那种）误判为变了
        return Outcome.ok(number.stripTrailingZeros().toPlainString());
    }

    // ==================== 日期时间 ====================

    /**
     * 常见的日期写法
     *
     * ⚠️ 顺序有意义：{@code yyyy-MM-dd} 必须先试，否则 {@code yyyy-M-d} 之外的
     * 解析器可能把 {@code 2026-09-14} 当成别的东西。用 tryParse 逐个试，
     * 失败不算异常。
     */
    private static final List<String> DATE_PATTERNS = List.of(
            "yyyy-MM-dd", "yyyy/M/d", "yyyy-M-d", "yyyy.MM.dd",
            "yyyyMMdd", "yyyy年M月d日", "yyyy年MM月dd日",
            "MM/dd/yyyy", "dd/MM/yyyy");

    private static final List<String> DATETIME_PATTERNS = List.of(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/M/d HH:mm:ss",
            "yyyy/M/d H:mm", "yyyy-M-d H:mm:ss", "yyyy年M月d日 HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss", "yyyyMMddHHmmss");

    /**
     * 日期 / 日期时间
     *
     * 输出一律是字符串而不是 java.util.Date：
     * 提交给运行时的值最终要经 FormValueSerializer 与 TemporalValueCoercer 处理，
     * 那两处都按字符串形态识别。这里给 Date 对象反而会让它们认不出来。
     */
    private Outcome readDate(String text, ImportWidget widget) {
        String cleaned = toHalfWidth(text.trim());
        // 日期后面带时间的（用户把「开始日期」填成了「2026-09-14 10:00」）
        // 按日期处理，把时间部分丢掉，而不是报错
        boolean wantsDateTime = "datetime".equalsIgnoreCase(String.valueOf(widget.getDateType()));

        if (wantsDateTime) {
            java.time.LocalDateTime dateTime = parseDateTime(cleaned);
            if (dateTime != null) {
                return Outcome.ok(dateTime.format(java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            // 日期时间列只填了日期：补 00:00:00。
            // 报错的话用户得为「我没填时间」这件事改一遍文件，而语义上它是明确的
            java.time.LocalDate date = parseDate(cleaned);
            if (date != null) {
                return Outcome.ok(date.atStartOfDay().format(java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            return Outcome.error(String.format(
                    "日期时间格式不对，「%s」无法识别（可用 2026-09-14 10:30:00）", text));
        }

        java.time.LocalDate date = parseDate(cleaned);
        if (date != null) {
            return Outcome.ok(date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        }
        // 日期列填了完整时间：取日期部分
        java.time.LocalDateTime dateTime = parseDateTime(cleaned);
        if (dateTime != null) {
            return Outcome.ok(dateTime.toLocalDate().format(
                    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        }
        return Outcome.error(String.format(
                "日期格式不对，「%s」无法识别（可用 2026-09-14 / 2026/9/14 / 2026年9月14日）", text));
    }

    /**
     * 单元格本来就是日期时，直接用，不走文本解析
     *
     * Excel 的真日期单元格经 ImportSheetReader 还原成 LocalDateTime。
     * 再把它格式化成字符串、又解析回来，中间会经过一次时区与格式的往返 ——
     * 没有收益，只有出错的机会。
     */
    private ImportCellReader.Outcome readTemporalDirect(Object raw, ImportWidget widget) {
        java.time.LocalDateTime dateTime;
        if (raw instanceof java.time.LocalDateTime dt) {
            dateTime = dt;
        } else if (raw instanceof java.time.LocalDate d) {
            dateTime = d.atStartOfDay();
        } else {
            return null;
        }
        boolean wantsDateTime = "datetime".equalsIgnoreCase(String.valueOf(widget.getDateType()));
        return Outcome.ok(wantsDateTime
                ? dateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : dateTime.toLocalDate().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
    }

    private java.time.LocalDate parseDate(String text) {
        // Excel 的真日期单元格被 FastExcel 读出来可能是这样一串：
        // Sun Sep 14 00:00:00 CST 2026。它 exDate 的 toString 就是这种形态
        java.time.LocalDate excelDate = parseJavaUtilDateStyle(text);
        if (excelDate != null) {
            return excelDate;
        }
        for (String pattern : DATE_PATTERNS) {
            try {
                return java.time.LocalDate.parse(text,
                        java.time.format.DateTimeFormatter.ofPattern(pattern,
                                java.util.Locale.CHINA));
            } catch (java.time.format.DateTimeParseException ignored) {
                // 试下一个格式
            }
        }
        // 带时间的串，取日期部分再试一次
        int space = text.indexOf(' ');
        if (space > 0) {
            String head = text.substring(0, space);
            if (!head.equals(text)) {
                return parseDate(head);
            }
        }
        return null;
    }

    private java.time.LocalDateTime parseDateTime(String text) {
        java.time.LocalDate excelDate = parseJavaUtilDateStyle(text);
        if (excelDate != null) {
            return excelDate.atStartOfDay();
        }
        for (String pattern : DATETIME_PATTERNS) {
            try {
                return java.time.LocalDateTime.parse(text,
                        java.time.format.DateTimeFormatter.ofPattern(pattern,
                                java.util.Locale.CHINA));
            } catch (java.time.format.DateTimeParseException ignored) {
                // 试下一个格式
            }
        }
        java.time.LocalDate date = parseDate(text);
        return date != null ? date.atStartOfDay() : null;
    }

    /**
     * 解析 {@code java.util.Date#toString()} 那种形态
     *
     * 「Sun Sep 14 00:00:00 CST 2026」。Excel 的日期单元格经 FastExcel 读出来时，
     * 有些场景会变成这个。它不符合任何常规 pattern，
     * 不专门处理的话日期列会整片报「格式不对」。
     */
    private java.time.LocalDate parseJavaUtilDateStyle(String text) {
        try {
            java.util.Date parsed = new java.text.SimpleDateFormat(
                    "EEE MMM dd HH:mm:ss zzz yyyy", java.util.Locale.US).parse(text);
            if (parsed == null) {
                return null;
            }
            return parsed.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        } catch (java.text.ParseException e) {
            return null;
        }
    }

    /**
     * 纯时间
     */
    private Outcome readTime(String text) {
        String cleaned = toHalfWidth(text.trim());
        for (String pattern : List.of("HH:mm:ss", "HH:mm", "H:mm:ss", "H:mm")) {
            try {
                return Outcome.ok(java.time.LocalTime.parse(cleaned,
                        java.time.format.DateTimeFormatter.ofPattern(pattern))
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
            } catch (java.time.format.DateTimeParseException ignored) {
                // 试下一个
            }
        }
        return Outcome.error(String.format("时间格式不对，「%s」无法识别（可用 10:30:00）", text));
    }

    // ==================== 布尔 ====================

    private static final List<String> TRUE_WORDS = List.of(
            "是", "有", "真", "对", "√", "✓", "y", "yes", "true", "t", "1", "启用", "开", "on");

    private static final List<String> FALSE_WORDS = List.of(
            "否", "无", "假", "错", "×", "✗", "x", "n", "no", "false", "f", "0", "停用", "关", "off");

    /**
     * 布尔
     *
     * 用户在布尔列上填的东西什么都有。只认 true/false 的话，
     * 「是」会被当成非空值存进去 —— 而服务端读回来的是 0，也就是「否」，
     * 与用户看到的正好相反。这是导入里最危险的一种静默错误。
     *
     * 所以这里**认不出就报错**，绝不放过。
     */
    private Outcome readBoolean(String text) {
        String cleaned = text.trim().toLowerCase();
        if (TRUE_WORDS.contains(cleaned)) {
            return Outcome.ok(1);
        }
        if (FALSE_WORDS.contains(cleaned)) {
            return Outcome.ok(0);
        }
        return Outcome.error(String.format(
                "「%s」不是有效的布尔值（可用 是/否、Y/N、1/0）", text));
    }

    // ==================== 字典 ====================

    /**
     * 单值字典
     *
     * **标签与编码都吃**。用户从模板或别人给的表里拿到的多半是中文标签
     * （「软件开发」），而库里存的是编码（SOFT）。
     * 只认编码的话用户得先去查字典表 —— 那等于把字典的负担转嫁给他。
     *
     * 反查顺序：先编码后标签。
     *
     * ⚠️ 先试编码，看起来与「用户填的多半是标签」矛盾，但这是对的：
     *    编码是**精确命中**（字典里确实有这个值），而标签可能被多个条目共用。
     *    反过来先试标签的话，「1」既是某字典的一条编码、又是另一条的标签时，
     *    会按标签理解 —— 而用户从导出文件里拿到的本来就是编码。
     *
     * 归一化那一轮放在最后：标签里的全角括号、多余空格很常见。
     */
    private Outcome readSelect(String text, ImportWidget widget) {
        String trimmed = text.trim();
        if (widget.getDictType() == null) {
            // 没配字典的 select（选项直接写在 props.options 里）——
            // 解析不了选项，原样透传，让服务端去判
            return Outcome.ok(text);
        }

        List<DictDataRespDTO> dictDatas = dictDataOf(widget.getDictType());

        // 1. 精确等于某个编码
        for (DictDataRespDTO data : dictDatas) {
            if (trimmed.equals(data.getValue())) {
                return Outcome.ok(castDictValue(data.getValue(), widget));
            }
        }
        // 2. 精确等于某个标签
        for (DictDataRespDTO data : dictDatas) {
            if (trimmed.equals(data.getLabel())) {
                return Outcome.ok(castDictValue(data.getValue(), widget));
            }
        }
        // 3. 归一化后等于某个标签
        String normalized = ImportHeaderText.normalize(trimmed);
        for (DictDataRespDTO data : dictDatas) {
            if (normalized.equals(ImportHeaderText.normalize(data.getLabel()))) {
                return Outcome.ok(castDictValue(data.getValue(), widget));
            }
        }
        return Outcome.error(String.format(
                "「%s」不在字典范围内（可选：%s）", text, describeDictOptions(dictDatas)));
    }

    /**
     * 多值字典
     *
     * 多选值在 Excel 里一般是一个格子里用分隔符连着写。
     * 提交给运行时时多选应当是数组 —— FormValueSerializer 会把它拼成落库的分隔串。
     */
    private Outcome readMultiSelect(String text, ImportWidget widget) {
        List<String> parts = ImportHeaderText.splitMultiValue(text);
        if (parts.isEmpty()) {
            return Outcome.ok(null);
        }
        List<Object> values = new ArrayList<>(parts.size());
        List<String> bad = new ArrayList<>();
        for (String part : parts) {
            Outcome one = readSelect(part, widget);
            if (one.isError()) {
                bad.add(part);
                continue;
            }
            if (one.getValue() != null) {
                values.add(one.getValue());
            }
        }
        if (!bad.isEmpty()) {
            return Outcome.error(String.format("「%s」不在字典范围内（可选：%s）",
                    String.join("、", bad),
                    describeDictOptions(dictDataOf(widget.getDictType()))));
        }
        return Outcome.ok(values);
    }

    /**
     * 取字典数据
     *
     * ⚠️ 走框架的 DictFrameworkUtils 而不是直接查库：
     *    它自带缓存（1 分钟），而导入是逐行逐列的，一张 5000 行的表
     *    对着同一个字典类型会问上万次。直接查库的写法会把导入变成一次压力测试。
     *
     * 取不到（字典被删了、dictType 写错）时返回空表，
     * 让调用方落到「不在字典范围内」而不是抛异常 ——
     * 字典配错是配置问题，不该让整份文件导不进来。
     */
    private List<DictDataRespDTO> dictDataOf(String dictType) {
        if (dictType == null) {
            return List.of();
        }
        try {
            return DictFrameworkUtils.getDictDataList(dictType);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 字典值的类型
     *
     * 字典项的值在库里是字符串，但字段上可能声明 valueType = num。
     * 不按声明转的话，数字型字典列提交的是字符串，
     * 后端比对与落库都可能出问题（「1」与 1 在 SQL 里不总是等价）。
     */
    private Object castDictValue(String value, ImportWidget widget) {
        if (!"num".equalsIgnoreCase(String.valueOf(widget.getDictValueType()))) {
            return value;
        }
        try {
            return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private String describeDictOptions(List<DictDataRespDTO> dictDatas) {
        if (dictDatas == null || dictDatas.isEmpty()) {
            return "字典未配置或已失效";
        }
        List<String> labels = new ArrayList<>();
        for (DictDataRespDTO data : dictDatas) {
            if (data.getLabel() != null && !data.getLabel().isBlank() && labels.size() < 12) {
                labels.add(data.getLabel());
            }
        }
        String joined = String.join("、", labels);
        return dictDatas.size() > labels.size() ? joined + "…" : joined;
    }

    // ==================== 文件 ====================

    /**
     * 文件类字段
     *
     * 只接受两种形态：
     * <ol>
     *   <li>一个 URL（图片/附件已经在文件服务里的地址）</li>
     *   <li>文件服务里的「原始文件名:存储名」对，逗号分隔多个（导出时就是这个形态）</li>
     * </ol>
     *
     * ⚠️ **不支持从 Excel 的内嵌图片导入**。这件事做不到「看起来对」：
     * 单元格里的图片在 xlsx 里是浮在格子上的一层对象，与行列没有可靠绑定，
     * 解析出来常常张冠李戴。与其做一个偶尔错位的功能，不如明确报错说清楚。
     */
    private Outcome readFileValue(String text, ImportWidget widget) {
        String trimmed = text.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")
                || trimmed.startsWith("/")) {
            return Outcome.ok(trimmed);
        }
        if (trimmed.contains(":")) {
            // 「原始名:存储名,原始名2:存储名2」—— 导出时写的就是这个
            return Outcome.ok(trimmed);
        }
        return Outcome.error(String.format(
                "「%s」不是有效的文件地址。附件列请填文件 URL；"
                        + "Excel 里内嵌的图片无法导入，请先上传到文件服务再填地址", text));
    }

    // ==================== 工具 ====================

    /**
     * 单元格值 → 文本
     *
     * 数字要按**原始形态**取，不能 toString 之后再处理 ——
     * 科学计数法会把 19 位的银行账号变成 1.2345678901234568E18，
     * 而那是不可逆的。这里对 BigDecimal 用 toPlainString。
     */
    public static String toText(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof java.math.BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (raw instanceof Double || raw instanceof Float) {
            java.math.BigDecimal decimal = java.math.BigDecimal.valueOf(
                    ((Number) raw).doubleValue());
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (raw instanceof Number number) {
            return number.toString();
        }
        if (raw instanceof java.time.LocalDateTime dateTime) {
            return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        if (raw instanceof java.time.LocalDate date) {
            return date.toString();
        }
        if (raw instanceof java.util.Date date) {
            return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
        }
        return String.valueOf(raw).trim();
    }

    private static String toHalfWidth(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '　') {
                chars[i] = ' ';
            } else if (chars[i] >= '！' && chars[i] <= '～') {
                chars[i] = (char) (chars[i] - 0xFEE0);
            }
        }
        return new String(chars);
    }

}
