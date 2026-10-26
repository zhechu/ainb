package cn.enlev.ainb.module.system.enums.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 授权效果枚举，对应 sys_grant.effect
 *
 * 枚举值直接落库（varchar），不要改名 —— 与 {@link GrantActionEnum} 同一约束。
 *
 * <h3>⚠️ 本枚举当前的【唯一】职责</h3>
 *
 * 是给 {@code GrantSaveReqVO#effect} 上那条 {@code @InEnum} 提供合法值集合。
 * main 代码里还有二十余处 {@code "ALLOW"} / {@code "DENY"} 字符串字面量
 * （决策引擎 GrantDecisionEngine、套餐同步 PermissionServiceImpl、
 * {@code ResourceTypeEnum#defaultEffect} 等），<b>刻意没有</b>借本次收敛成枚举引用：
 * 那些字面量全在安全关键路径上，替换是零行为变更的纯 churn，却会让一次
 * 「批量授权」的 diff 里混进整个鉴权引擎的改动，审查成本与风险完全不成比例。
 *
 * 字面量恰好等于这里的 code，不会漂移 —— 这个「恰好」由 {@code GrantSaveReqVOTest}
 * 断言枚举全集（只有 ALLOW/DENY 两项）钉住。<b>要全量替换字面量请另开任务、单独提交</b>；
 * 反过来，改这里的枚举值也会静默改变那二十余处的语义，同样要另开任务。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum GrantEffectEnum implements ArrayValuable<String> {

    /**
     * 明确允许
     */
    ALLOW("ALLOW"),
    /**
     * 明确拒绝
     *
     * 🔴 一票否决：同键下任意一行 DENY 否决所有 ALLOW
     * （{@code GrantDecisionEngine#isAllowed} 对 DENY 用 anyMatch 判定）。
     * 这也是「同键重复行」在 effect 不一致时会变成安全问题、而不只是脏数据的原因
     */
    DENY("DENY");

    public static final String[] ARRAYS = Arrays.stream(values())
            .map(GrantEffectEnum::getCode).toArray(String[]::new);

    private final String code;

    public static GrantEffectEnum parse(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        for (GrantEffectEnum effect : values()) {
            if (effect.code.equals(code)) {
                return effect;
            }
        }
        return null;
    }

    @Override
    public String[] array() {
        return ARRAYS;
    }

}
