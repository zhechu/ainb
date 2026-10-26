package cn.enlev.ainb.module.infra.framework.formdesign.core.extension;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交上下文
 *
 * 传给 {@link FormDesignExtension} 的钩子。扩展点通过它读表单元信息、改数据、收集校验错误。
 *
 * 为什么给的是可修改的 data 而不是只读快照：扩展点的典型用途就是补全字段
 * （按客户等级算折扣、按地区补税率）。给只读快照就只能做校验，一半的场景用不了。
 *
 * @author 走进新时代
 */
@Data
public class FormSubmitContext {

    /**
     * 操作类型
     */
    public enum Operation {
        CREATE,
        UPDATE,
        DELETE
    }

    /**
     * 表单编码
     */
    private final String formCode;
    /**
     * 表单编号
     */
    private final Long formId;
    /**
     * 操作类型
     */
    private final Operation operation;
    /**
     * 主键值。CREATE 时可能为 null（自增主键在写入后才有值）
     */
    private final Object primaryKey;
    /**
     * 当前登录用户编号
     */
    private final Long userId;
    /**
     * 表单数据，可修改
     *
     * 扩展点改了这里，写入的就是改后的值。
     */
    private final Map<String, Object> data;

    /**
     * 校验错误
     *
     * 扩展点往这里加消息，不要自己抛异常 —— 加进来的错误会被一次性汇总后
     * 抛成业务异常，用户一次看到全部问题；自己抛异常只能报出第一条。
     */
    private final List<String> errors = new ArrayList<>();

    public FormSubmitContext(String formCode, Long formId, Operation operation,
                             Object primaryKey, Long userId, Map<String, Object> data) {
        this.formCode = formCode;
        this.formId = formId;
        this.operation = operation;
        this.primaryKey = primaryKey;
        this.userId = userId;
        this.data = data != null ? data : new LinkedHashMap<>();
    }

    /**
     * 添加一条校验错误
     */
    public void addError(String message) {
        if (message != null && !message.isBlank()) {
            errors.add(message);
        }
    }

    /**
     * 是否有校验错误
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * 取字段值
     */
    public Object get(String field) {
        return data.get(field);
    }

    /**
     * 设置字段值
     */
    public void set(String field, Object value) {
        data.put(field, value);
    }

}
