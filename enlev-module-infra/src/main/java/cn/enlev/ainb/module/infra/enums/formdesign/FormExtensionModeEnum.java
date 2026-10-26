package cn.enlev.ainb.module.infra.enums.formdesign;

import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 表单的业务逻辑扩展模式
 *
 * 三层扩展机制里的第二层（第一层是始终启用的声明式联动规则）：
 * 前端脚本 与 服务端扩展点 二选一，【互斥】。
 *
 * <h3>互斥的执行语义</h3>
 *
 * <ul>
 *   <li>{@link #NONE}：只跑声明式规则</li>
 *   <li>{@link #FRONTEND_SCRIPT}：声明式规则 + 前端脚本；服务端扩展点即便存在对应 Bean 也不调用</li>
 *   <li>{@link #SERVER_EXTENSION}：声明式规则 + 服务端扩展点；前端脚本即便有内容也不执行</li>
 * </ul>
 *
 * 切换模式时【不删除】另一模式的既有配置（脚本文本保留在库里），
 * 只是不执行。这样过渡期可以来回切换做对比验证。
 *
 * <h3>默认值是 FRONTEND_SCRIPT，以及它带来的陷阱</h3>
 *
 * 默认取前端脚本是为了过渡期顺手。代价是服务端扩展点默认不生效：
 * 开发者写好 {@code FormDesignExtension} 实现并部署后，如果没人把表单切过来，
 * 扩展点一次都不会被调用，也没有任何报错。
 * 这个「静默不执行」由三道告警兜住，见 {@code FormDesignExtensionRegistry}。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum FormExtensionModeEnum implements ArrayValuable<Integer> {

    /**
     * 无扩展，只跑声明式联动规则
     */
    NONE(0, "无"),

    /**
     * 前端脚本。新建表单的默认值
     */
    FRONTEND_SCRIPT(1, "前端脚本"),

    /**
     * 服务端扩展点
     */
    SERVER_EXTENSION(2, "服务端扩展点");

    public static final Integer[] ARRAYS = Arrays.stream(values())
            .map(FormExtensionModeEnum::getMode).toArray(Integer[]::new);

    /**
     * 新建表单的默认模式
     *
     * ⚠️ 与 DDL 里 extension_mode 的 DEFAULT 必须一致。
     * 两边不一致会导致「接口新建的表单」与「直接插库的表单」默认行为不同。
     */
    public static final FormExtensionModeEnum DEFAULT = FRONTEND_SCRIPT;

    /**
     * 模式值
     */
    private final Integer mode;
    /**
     * 描述
     */
    private final String name;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }

    public static FormExtensionModeEnum modeOf(Integer mode) {
        return Arrays.stream(values())
                .filter(item -> item.getMode().equals(mode))
                .findFirst().orElse(null);
    }

    /**
     * 是否为前端脚本模式
     *
     * 空值按默认模式处理 —— 历史数据或手工插库的行可能没有这一列的值，
     * 此时的行为应与新建表单一致，而不是变成「无扩展」。
     */
    public static boolean isFrontendScript(Integer mode) {
        return mode == null ? DEFAULT == FRONTEND_SCRIPT : FRONTEND_SCRIPT.getMode().equals(mode);
    }

    /**
     * 是否为服务端扩展点模式
     */
    public static boolean isServerExtension(Integer mode) {
        return mode == null ? DEFAULT == SERVER_EXTENSION : SERVER_EXTENSION.getMode().equals(mode);
    }

}
