package cn.enlev.ainb.module.infra.framework.formdesign.core.changelog;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContract;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段级变更对比
 *
 * <h3>为什么难点不在「对比」而在「什么算变了」</h3>
 *
 * 库里读出来的值和前端提交的值，**类型天然不同**：
 *
 * <ul>
 *   <li>{@code decimal(12,2)} 列读出来是 {@code BigDecimal("10.00")}，前端提交的是 {@code 10}</li>
 *   <li>{@code date} 列读出来是 {@code java.sql.Date}，前端提交的是 {@code "2026-09-10"}</li>
 *   <li>{@code bit} 列读出来是 {@code Boolean}，前端提交的是 {@code 1} 或 {@code "1"}</li>
 * </ul>
 *
 * 直接 {@code toString} 比对的话，**每次保存都会记下一堆没改过的字段**
 * （「金额：10.00 → 10」）。这种日志比没有更糟：真正的变更被噪音埋掉，
 * 而看日志的人无法判断哪条是真的。
 *
 * 所以这里按「先数值、再日期、最后字符串」的顺序归一化后再比。
 *
 * <h3>只对比提交上来的字段</h3>
 *
 * 更新是局部的（见 PartialUpdateTest）：没提交的键压根不参与更新，
 * 自然也不算变更。把它们算进来会让审批节点每次提交都「改」了一堆它看不见的字段。
 *
 * <h3>子表明细不进变更日志</h3>
 *
 * 明细是一整张表的行增删改，塞进「字段：旧值 → 新值」这个形状里只能变成
 * 两大段 JSON 的对比，读的人得不到任何有用信息。真要做得单独设计。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormDataChangeDiffer {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /**
     * 时间（无日期）的展示格式
     *
     * 必须显式给出秒：{@code LocalTime.toString()} 在秒为 0 时会把它省掉（{@code 08:30}），
     * 而库里读出来的 {@code java.sql.Time} 是 {@code 08:30:00}。
     * 两边格式不一致的话，一个没被碰过的时间字段每次保存都会记一条
     * 「08:30:00 → 08:30」的假变更。
     */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 变更值在日志里的长度上限
     *
     * 富文本、多图这类字段的值可以很长，整段存进日志会让一次更新的日志有几十 KB。
     * 截断并加省略号 —— 变更日志的用途是「谁在什么时候把它从什么改成了什么」，
     * 不是留一份内容副本。
     */
    private static final int MAX_VALUE_LENGTH = 500;

    @Resource
    private WidgetContractRegistry contractRegistry;

    /**
     * 对比新旧值，产出字段级变更清单
     *
     * @param formFieldsJson 表单字段规则 JSON，用来取字段标题与判断字段类型
     * @param oldRow         库里的旧值。⚠️ 它的键是**物理列名** —— 读取路径的 SELECT
     *                       不给列起字段名别名。本方法用字段名去取它，靠的是本模块
     *                       「字段 ID 就是物理列名」这条约定（设计器把字段ID做成了
     *                       物理列下拉）。那条约定一旦松掉，这里会取不到旧值、
     *                       把每次保存都记成「从空改成了 X」。见交接文档第十节
     * @param newData        本次提交的数据。键是字段名
     * @return 变更清单，没有变更时返回空列表
     */
    public List<FieldChange> diff(String formFieldsJson, Map<String, Object> oldRow,
                                  Map<String, Object> newData) {
        if (newData == null || newData.isEmpty()) {
            return List.of();
        }
        Map<String, Object> before = oldRow != null ? oldRow : Map.of();
        List<FieldChange> changes = new ArrayList<>();
        for (Map.Entry<String, LoggedField> entry : parseLoggedFields(formFieldsJson).entrySet()) {
            String field = entry.getKey();
            if (!newData.containsKey(field)) {
                // 没提交的字段不参与更新，自然也不算变更
                continue;
            }
            Object oldValue = before.get(field);
            Object newValue = newData.get(field);
            if (isSame(oldValue, newValue)) {
                continue;
            }
            changes.add(new FieldChange(field, entry.getValue().getTitle(),
                    display(oldValue), display(newValue)));
        }
        return changes;
    }

    /**
     * 判断两个值是否「没变」
     *
     * 顺序有讲究：数值 → 日期 → 字符串。反过来先比字符串的话，
     * {@code BigDecimal("10.00")} 与 {@code 10} 会被判成变了。
     */
    boolean isSame(Object oldValue, Object newValue) {
        String oldText = display(oldValue);
        String newText = display(newValue);
        if (StrUtil.isEmpty(oldText) && StrUtil.isEmpty(newText)) {
            // null 与空串视为同一件事：字段被清空后再提交空串，不该记成一次变更
            return true;
        }
        BigDecimal oldNumber = toDecimal(oldText);
        BigDecimal newNumber = toDecimal(newText);
        if (oldNumber != null && newNumber != null) {
            // compareTo 而不是 equals：10.00 与 10 的 scale 不同，equals 判不相等
            return oldNumber.compareTo(newNumber) == 0;
        }
        return oldText.equals(newText);
    }

    private BigDecimal toDecimal(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 把值转成日志里展示的字符串
     *
     * 日期类型统一格式化：不做的话，同一个日期从库里读出来是
     * {@code 2026-09-10 00:00:00.0}、前端提交的是 {@code 2026-09-10}，
     * 每次保存都会记一条假变更。
     *
     * <h3>⚠️ java.sql 的三个子类必须各判各的，一个都不能漏</h3>
     *
     * {@code java.sql.Date}、{@code java.sql.Time}、{@code java.sql.Timestamp}
     * 都继承自 {@code java.util.Date}，但前两个的 {@code toInstant()}
     * <b>是无条件抛 {@link UnsupportedOperationException} 的</b> ——
     * JDK 的原话是「SQL DATE 值没有时间部分 / SQL TIME 值没有日期部分」，
     * 所以它们不给 Instant。
     *
     * 漏掉 {@code java.sql.Time} 的后果不是格式难看，是<b>整个更新接口 500</b>：
     * 它会落到下面那个 {@code instanceof Date} 的兜底分支里，
     * 在 {@code toInstant()} 上炸开，而这一步跑在事务里、位于真正的 UPDATE 之后，
     * 于是「表里带 time 列 + 开了变更日志」的表单一改就报系统异常，新增却是好的。
     */
    String display(Object value) {
        if (value == null) {
            return "";
        }
        String text;
        if (value instanceof LocalDateTime dateTime) {
            text = DATE_TIME.format(dateTime);
        } else if (value instanceof LocalDate date) {
            text = DATE.format(date);
        } else if (value instanceof LocalTime time) {
            // 写入侧会把毫秒数归一成 LocalTime（见 TemporalValueCoercer），
            // 与读取侧的 java.sql.Time 格式必须一致，否则两边对不上
            text = TIME.format(time);
        } else if (value instanceof java.sql.Date sqlDate) {
            text = DATE.format(sqlDate.toLocalDate());
        } else if (value instanceof java.sql.Time sqlTime) {
            // 必须排在下面的 instanceof Date 之前：它也是 java.util.Date 的子类
            text = TIME.format(sqlTime.toLocalTime());
        } else if (value instanceof java.sql.Timestamp timestamp) {
            text = DATE_TIME.format(timestamp.toLocalDateTime());
        } else if (value instanceof Date date) {
            // 走到这里的只剩朴素的 java.util.Date，它的 toInstant() 是安全的
            text = DATE_TIME.format(
                    LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault()));
        } else if (value instanceof Collection<?> || value instanceof Map<?, ?>) {
            // 数组/对象值（多选、上传）序列化成 JSON。它们此刻还是逻辑形态 ——
            // 变更对比排在落库序列化之前
            text = JsonUtils.toJsonString(value);
        } else {
            text = String.valueOf(value);
        }
        return StrUtil.maxLength(text, MAX_VALUE_LENGTH);
    }

    // ==================== 字段解析 ====================

    /**
     * 提取要记变更的字段
     *
     * 排除三类：布局容器（没有值）、子表控件（见类注释）、不落库的展示型控件。
     *
     * @return 字段名 -> 字段信息
     */
    public Map<String, LoggedField> parseLoggedFields(String formFieldsJson) {
        Map<String, LoggedField> result = new LinkedHashMap<>();
        for (String rule : FormFieldsCodec.read(formFieldsJson)) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collect(node, result);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> node, Map<String, LoggedField> result) {
        String type = node.get("type") instanceof String t ? t : null;
        String field = node.get("field") instanceof String f && StrUtil.isNotBlank(f) ? f : null;
        WidgetContract contract = contractRegistry.get(type);
        boolean loggable = field != null && contract != null
                && contract.isField() && !contract.isSubTableStorage() && !contract.isNoStorage();
        if (loggable) {
            LoggedField logged = new LoggedField();
            logged.setField(field);
            logged.setTitle(node.get("title") instanceof String t && StrUtil.isNotBlank(t)
                    ? t : field);
            result.put(field, logged);
        }
        if (node.get("children") instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collect((Map<String, Object>) childMap, result);
                }
            }
        }
        // 刻意【不】递归 props.rule：那是子表明细列，不进变更日志
    }

    /**
     * 一个参与变更记录的字段
     */
    @Data
    public static class LoggedField {

        private String field;
        /**
         * 字段标题。日志给人看，用标题而不是字段 ID
         */
        private String title;

    }

    /**
     * 一条字段变更
     */
    @Data
    @AllArgsConstructor
    public static class FieldChange {

        private String field;
        private String title;
        private String oldValue;
        private String newValue;

    }

}
