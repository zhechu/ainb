package cn.enlev.ainb.module.infra.framework.formdesign.core.validation;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 字段级校验规则
 *
 * 配置存放在控件的 {@code props.validateRules} 数组里，一个字段可以配多条，逐条校验。
 *
 * <h3>为什么键名不叫 validate</h3>
 *
 * form-create 内核本来就有 {@code validate}（async-validator 的规则数组）。这里刻意不复用它：
 * <ul>
 *   <li>{@code validate} 的语义是 async-validator 的全集 —— 类型转换、自定义
 *       validator 函数、异步校验器。服务端要复校验就得把那一套解释一遍，
 *       实际只会实现其中一小部分，结果是「配了但服务端不生效」，
 *       而这比没有校验更糟</li>
 *   <li>引用内置正则库需要保留 key（而不是把正则内容拷进配置），
 *       否则正则库升级后存量表单还在用旧正则</li>
 * </ul>
 *
 * 所以 {@code validateRules} 是我们自己的声明式模型、是唯一事实来源，
 * 运行时由前端【派生】出 form-create 的 {@code validate} 条目。单向派生，不双向同步。
 *
 * @author 走进新时代
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldValidationRule {

    /**
     * 规则类型
     */
    public enum Type {

        /**
         * 引用内置正则库
         */
        BUILTIN,

        /**
         * 自定义正则
         */
        REGEX,

    }

    /**
     * 规则类型。识别不出来时按 {@link Type#REGEX} 处理，见 {@link #resolveType()}
     */
    private String type;
    /**
     * 内置正则的 key，type=BUILTIN 时使用
     */
    private String patternKey;
    /**
     * 自定义正则，type=REGEX 时使用
     */
    private String pattern;
    /**
     * 自定义错误提示。为空时用内置正则自带的提示
     */
    private String message;
    /**
     * 是否启用。缺省视为启用 —— 少写一个布尔不该让规则静默失效
     */
    private Boolean enabled;

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /**
     * 解析规则类型
     *
     * 缺省与识别失败都回退到 {@link Type#REGEX}：判据是「有没有 patternKey」——
     * 配了 patternKey 显然是想引用内置库。这样少写一个 type 字段不会让规则失效。
     */
    public Type resolveType() {
        if (StrUtil.isNotBlank(type)) {
            try {
                return Type.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 落到下面按 patternKey 推断
            }
        }
        return StrUtil.isNotBlank(patternKey) ? Type.BUILTIN : Type.REGEX;
    }

    /**
     * 规则是否可用
     *
     * 两种类型各缺自己那个必填项时不可用，调用方应当跳过并记日志。
     */
    public boolean isUsable() {
        return resolveType() == Type.BUILTIN
                ? StrUtil.isNotBlank(patternKey)
                : StrUtil.isNotBlank(pattern);
    }

}
