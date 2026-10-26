package cn.enlev.ainb.framework.common.util.json.databind;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 基于时间戳的 LocalDateTime 反序列化器
 *
 * 数字（及纯数字字符串）按 epoch 毫秒解析；同时兼容前端日期控件常用的
 * "yyyy-MM-dd HH:mm:ss" / ISO / "yyyy-MM-dd" 字符串。
 *
 * ⚠️ 历史实现只有 {@code p.getValueAsLong()} 一条路：传日期字符串时它返回 0，
 * 于是所有「JSON 体里带格式化日期」的接口都被静默存成 1970-01-01
 * （表现为一设置到期时间就「已过期」）。解析失败必须抛异常，不能再静默归零。
 *
 * @author 老五
 */
public class TimestampLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    public static final TimestampLocalDateTimeDeserializer INSTANCE = new TimestampLocalDateTimeDeserializer();

    private static final List<DateTimeFormatter> FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        // 数字：epoch 毫秒
        if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return ofEpochMilli(p.getLongValue());
        }
        String text = p.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.trim();
        // 纯数字字符串：epoch 毫秒（部分前端会把时间戳序列化成字符串）
        if (text.chars().allMatch(Character::isDigit)) {
            return ofEpochMilli(Long.parseLong(text));
        }
        for (DateTimeFormatter formatter : FORMATTERS) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // 换下一种格式
            }
        }
        // 只有日期：当天 00:00
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // 落到下面的报错
        }
        throw InvalidFormatException.from(p, String.format(
                "无法解析 LocalDateTime：%s。支持 epoch 毫秒、yyyy-MM-dd HH:mm:ss、ISO、yyyy-MM-dd HH:mm、yyyy-MM-dd", text),
                text, LocalDateTime.class);
    }

    private static LocalDateTime ofEpochMilli(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
    }

}
