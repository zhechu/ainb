package cn.enlev.ainb.module.infra.enums.datainterface;

import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * SQL 语句类型（动作类型）枚举
 *
 * ⚠️ 这个值**由用户在配置界面显式选择**，不是程序推断出来的。
 * 原因：一个接口允许配多条 SQL（如「插主表 + 插子表」），程序无法可靠地
 * 判断这一组语句整体算什么动作 —— 让用户自己选更精确。
 * {@link cn.enlev.ainb.module.infra.framework.datainterface.core.sql.SqlStatementAnalyzer}
 * 的解析结果降级为校验依据：用户选「查询」但 SQL 里有写语句时拒绝保存。
 *
 * 用途：
 * 1. 决定返回格式：查询返回结果集，其它返回影响行数
 * 2. 决定能否开启分页：只有查询可以
 * 3. 列表页醒目标注哪些接口会修改数据
 * 4. 保存时判断是否需要二次确认
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum SqlStatementTypeEnum implements ArrayValuable<String> {

    SELECT("SELECT", "查询", false, true),
    INSERT("INSERT", "新增", true, true),
    UPDATE("UPDATE", "修改", true, true),
    DELETE("DELETE", "删除", true, true),
    // 下面两个不开放给用户选择：DDL 已被策略禁止，OTHER 是解析器认不出语句时的兜底值
    DDL("DDL", "结构变更", true, false),
    OTHER("OTHER", "其它", true, false);

    public static final String[] ARRAYS = Arrays.stream(values())
            .map(SqlStatementTypeEnum::getType).toArray(String[]::new);

    /**
     * 类型
     */
    private final String type;
    /**
     * 描述
     */
    private final String name;
    /**
     * 是否属于写操作
     *
     * 无法识别的语句（OTHER）保守地视为写操作 —— 拿不准时按更危险的处理
     */
    private final Boolean writeOperation;
    /**
     * 是否开放给用户在配置界面选择
     */
    private final Boolean selectable;

    @Override
    public String[] array() {
        return ARRAYS;
    }

    public static SqlStatementTypeEnum typeOf(String type) {
        return Arrays.stream(values())
                .filter(item -> item.getType().equalsIgnoreCase(type))
                .findFirst().orElse(OTHER);
    }

    /**
     * 用户可选的动作类型
     */
    public static List<SqlStatementTypeEnum> selectableValues() {
        return Arrays.stream(values()).filter(SqlStatementTypeEnum::getSelectable).toList();
    }

    /**
     * 判断是否为用户可选的动作类型
     */
    public static boolean isSelectable(String type) {
        return Arrays.stream(values())
                .anyMatch(item -> item.getType().equalsIgnoreCase(type) && item.getSelectable());
    }

}
