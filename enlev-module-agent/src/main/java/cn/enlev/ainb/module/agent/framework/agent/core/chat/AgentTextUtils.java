package cn.enlev.ainb.module.agent.framework.agent.core.chat;

import cn.hutool.core.util.StrUtil;

/**
 * 文本截断
 *
 * <h3>🔴 为什么不直接用 {@code StrUtil.maxLength}</h3>
 * 它的语义<b>不是</b>「结果不超过 n 个字符」，而是「正文截到 n 个字符，再补三个点」——
 * 返回值实际长度是 <b>n + 3</b>。
 *
 * <p>这个差别看着无所谓，落到数据库上就是故障：
 * {@code maxLength(errorMsg, 500)} 写进 {@code varchar(500)} 的列，
 * 一旦原文超过 500 字，插入直接失败。而这类代码的调用点往往在异常处理里 ——
 * 也就是说，<b>报错信息越长越写不进去</b>，恰好在最需要留证据的时候丢掉记录。
 *
 * <p>{@code agent_usage_log} 的写入被包在 try/catch 里（计量失败不能让业务失败），
 * 所以它不会炸到用户面前，只会打一条「账单将出现空洞」的 ERROR ——
 * 空洞是真的，而原因藏在一个谁都不会去看的日志里。
 *
 * @author 走进新时代
 */
public final class AgentTextUtils {

    /**
     * 省略号占的字符数，与 {@code StrUtil.maxLength} 的实现一致
     */
    private static final int ELLIPSIS_LENGTH = 3;

    private AgentTextUtils() {
    }

    /**
     * 按<b>数据库列宽</b>截断，保证结果长度不超过 {@code columnLength}
     *
     * @param text         原文，可为 null
     * @param columnLength 列宽（字符数）
     * @return 截断后的文本，null 转成空串（列多为 NOT NULL DEFAULT ''）
     */
    public static String truncateForColumn(String text, int columnLength) {
        String value = StrUtil.emptyIfNull(text);
        if (value.length() <= columnLength) {
            return value;
        }
        // 留出省略号的位置，这样加完三个点仍然刚好卡在列宽内
        return StrUtil.maxLength(value, columnLength - ELLIPSIS_LENGTH);
    }

}
