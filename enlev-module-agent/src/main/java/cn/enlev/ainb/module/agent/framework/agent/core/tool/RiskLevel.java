package cn.enlev.ainb.module.agent.framework.agent.core.tool;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工具的风险等级
 *
 * 决定两件事：<b>要不要进 Plan 等用户确认</b>，以及<b>界面上怎么标</b>。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum RiskLevel {

    /**
     * 只读，不改任何数据
     *
     * 可以在 Agent 循环里自动执行、不进 Plan。查表结构、查表单设计、查控件契约都属于这一档。
     */
    READ_ONLY("READ_ONLY", "只读", false, 0),

    /**
     * 可逆的写操作
     *
     * 改错了能改回来，如保存表单草稿。仍然要进 Plan 等确认 —— 铁律是「用户确认前不执行任何写操作」，
     * 可逆只影响风险标注，不影响是否需要确认。
     */
    LOW("LOW", "低风险", true, 1),

    /**
     * 有陷阱的写操作
     *
     * 典型是 {@code form_update}：它不是增量更新，没传的字段会被<b>静默清空</b>
     * （联动规则、校验规则、自定义按钮、页签、打印模板、前端脚本）。
     * 界面上要把「由非空变为空」的配置项单独高亮。
     */
    MEDIUM("MEDIUM", "中风险", true, 2),

    /**
     * 高风险写操作
     *
     * 影响面大或涉及多步，如发布表单（连带建菜单、配权限）、部署流程。
     */
    HIGH("HIGH", "高风险", true, 3),

    /**
     * 不可逆
     *
     * 执行了就回不去。建表改表属于这一档 —— MySQL 的 DDL 没有事务，
     * {@code DdlExecutor} 的类注释写得很直白：「不假装能回滚」。
     * 界面上必须显著标注「执行后无法回滚」。
     */
    IRREVERSIBLE("IRREVERSIBLE", "不可逆", true, 4),
    ;

    /**
     * 等级标识
     */
    private final String level;
    /**
     * 中文描述
     */
    private final String description;
    /**
     * 是否必须进 Plan 等用户确认
     */
    private final boolean requireConfirm;
    /**
     * 严重度，只用于「取两者中更严重的那个」
     *
     * <p>🔴 为什么不让调用方直接比 {@code ordinal()} / {@code compareTo()}：上面五个常量的声明顺序
     * 恰好就是严重度升序，于是「比大小」这件事<b>看起来</b>免费。但那份顺序是写给人看的排版，
     * 没有任何东西强制它 —— 谁在 LOW 前面插一个常量，所有比大小的调用点会静默改判，
     * 而表现是「manifest 把不可逆操作标成蓝色」。显式落一列，就把「排版」和「语义」分开了；
     * {@code RiskLevelTest#severityAscendingInDeclarationOrder} 再钉住两者当前一致，
     * 真有人重排声明顺序时会先撞红那条测试，而不是撞红用户。
     */
    private final int severity;

    /**
     * 取两者中更严重的一个；null 视为「不严重」
     *
     * <p>变更清单（{@code ChangeManifest}）的总风险 = 各项变更风险的最大值，走这一个入口。
     */
    public static RiskLevel higher(RiskLevel a, RiskLevel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.severity >= b.severity ? a : b;
    }

    /**
     * 把库里的风险等级字符串解析成枚举；<b>缺失或非法一律回落 {@link #HIGH}</b>
     *
     * <p>🔴 回落方向是刻意的：宁可把只读误判成写（多要一次确认、多 mock 一次，都没有副作用），
     * 也不能把写误判成只读（真跑出副作用）。自定义工具（{@code agent_tool_def.risk_level}）
     * 与任何将来承载风险等级的数据都走这一个入口 —— 两份解析逻辑必然漂移，
     * 而漂移的表现是「同一个工具在沙箱里被 mock、在名称目录里标成只读」这种查不出原因的矛盾。
     *
     * @param raw 库里的原始字符串，可为 null/空白
     * @return 对应枚举；解析不出时 {@link #HIGH}
     */
    public static RiskLevel parseOrHigh(String raw) {
        if (raw == null || raw.isBlank()) {
            return HIGH;
        }
        try {
            return valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            return HIGH;
        }
    }

}
