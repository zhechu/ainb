package cn.enlev.ainb.module.system.enums.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 字段显示模式枚举
 *
 * 优先级：EDITABLE > READONLY > HIDDEN（越往右越严格）
 * min 取「更严格」，max 取「更宽松」。
 *
 * 三层合并规则（v8 §3.2，v9 §7.1 第 7 步）：
 * <pre>
 *   ceiling = min(全局 subject 命中的 field_mode)        ← 角色红线，不能被上下文突破
 *   value   = 上下文 subject max ?? 上下文基线 ?? 全局 subject max ?? default
 *   最终    = min(ceiling, value)
 * </pre>
 *
 * ⚠️ 三条校验（必须各一条单测）：
 *   1. 角色 READONLY（全局）+ 节点基线 EDITABLE → 最终 READONLY   （红线生效）
 *   2. 节点基线 READONLY + 主管在该节点 EDITABLE → 最终 EDITABLE  （上下文提权）
 *   3. 角色 EDITABLE（全局）+ 节点基线 READONLY → 最终 READONLY   （审批节点收紧生效）
 *
 * <h3>实现 ArrayValuable 是为了 {@code GrantSaveReqVO#fieldMode} 上那条 {@code @InEnum}</h3>
 *
 * 🔴 {@link #parse} 对枚举外的值返回 null，而调用方把 null 当作「这一行没配字段模式」——
 * 于是写成 {@code VISIBLE}（最常见的笔误，因为「可见」听起来就像 EDITABLE 的同义词）
 * 会让<b>整行授权被静默忽略</b>：保存成功、列表正常、不报错，只是权限压根没生效。
 * 与 action / effect 是同一个形状，那两个早就有 {@code @InEnum} 拦着了，fieldMode 是漏网的。
 * 现在入口直接 400 并把合法值列出来，比事后靠「诊断工具」去库里捞非法值便宜得多。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum FieldModeEnum implements ArrayValuable<String> {

    /**
     * 可编辑（最宽松）
     */
    EDITABLE("EDITABLE", 2),
    /**
     * 只读
     */
    READONLY("READONLY", 1),
    /**
     * 隐藏（最严格）
     */
    HIDDEN("HIDDEN", 0);

    public static final String[] ARRAYS = Arrays.stream(values())
            .map(FieldModeEnum::getCode).toArray(String[]::new);

    private final String code;
    /**
     * 严格度：数字越小越严格，min/max 用它比较
     */
    private final int strictness;

    /**
     * 取「更严格」（ceiling 用它：全局 DENY 红线）
     */
    public static FieldModeEnum min(FieldModeEnum a, FieldModeEnum b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.strictness <= b.strictness ? a : b;
    }

    /**
     * 取「更宽松」（value 用它：同一上下文内取最优）
     */
    public static FieldModeEnum max(FieldModeEnum a, FieldModeEnum b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.strictness >= b.strictness ? a : b;
    }

    public static FieldModeEnum parse(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        for (FieldModeEnum mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        return null;
    }

    @Override
    public String[] array() {
        return ARRAYS;
    }

}
