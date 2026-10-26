package cn.enlev.ainb.module.system.enums.permission;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 分端导航的「端」枚举
 *
 * <p>{@code system_nav_node.terminal} 的取值域。现阶段仅 {@link #APP}；
 * Web 端<b>不写</b>这张表（继续用 {@code system_menu}），故没有 WEB 值。
 * 未来 h5 / mp-weixin 各加一行即可，无需改表结构
 *
 * <p>用枚举而不是裸字符串，是为了在写入 / 查询时挡住手滑打错的 terminal
 * （打错不会报错，只会让那棵树的节点永远查不出来 —— 静默的空工作台）
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum NavTerminalEnum {

    APP("app") // 移动端（uniapp）
    ;

    /**
     * 端标识，落 {@code system_nav_node.terminal} 列
     */
    private final String terminal;

    /**
     * 校验一个 terminal 串是否受支持
     *
     * @param terminal 端标识
     * @return 是否是已知端
     */
    public static boolean isValid(String terminal) {
        if (terminal == null) {
            return false;
        }
        for (NavTerminalEnum value : values()) {
            if (value.getTerminal().equals(terminal)) {
                return true;
            }
        }
        return false;
    }

}
