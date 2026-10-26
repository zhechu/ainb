package cn.enlev.ainb.module.infra.enums.formdesign;

import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * beforeSubmit 钩子超时后的动作
 *
 * 默认取 {@link #BLOCK}（阻断）。理由：beforeSubmit 的典型用途是提交前校验，
 * 超时意味着「校验没跑完」。此时放行等于静默跳过校验，是最难排查的一类脏数据来源 ——
 * 数据进了库、接口返回成功、没有任何异常记录。阻断至少会把问题暴露出来。
 *
 * 确有「钩子只做非关键补全」的表单，再单独切 {@link #PASS}。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum SubmitHookTimeoutActionEnum implements ArrayValuable<Integer> {

    /**
     * 阻断提交并提示。默认值
     */
    BLOCK(1, "阻断提交"),

    /**
     * 放行并记警告日志
     */
    PASS(2, "放行");

    public static final Integer[] ARRAYS = Arrays.stream(values())
            .map(SubmitHookTimeoutActionEnum::getAction).toArray(Integer[]::new);

    /**
     * 默认动作
     *
     * ⚠️ 与 DDL 里 submit_hook_timeout_action 的 DEFAULT 必须一致。
     */
    public static final SubmitHookTimeoutActionEnum DEFAULT = BLOCK;

    /**
     * 动作值
     */
    private final Integer action;
    /**
     * 描述
     */
    private final String name;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }

    public static SubmitHookTimeoutActionEnum actionOf(Integer action) {
        return Arrays.stream(values())
                .filter(item -> item.getAction().equals(action))
                .findFirst().orElse(null);
    }

}
