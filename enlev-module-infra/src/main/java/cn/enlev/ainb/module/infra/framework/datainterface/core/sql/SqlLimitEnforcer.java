package cn.enlev.ainb.module.infra.framework.datainterface.core.sql;

import cn.hutool.core.util.StrUtil;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * SQL LIMIT 强制检查与注入
 *
 * 分页功能的安全保障：
 * 1. 开启分页时，强制检查 SQL 是否含 LIMIT（不含则报错）
 * 2. 未开启分页时，自动加 LIMIT 10000（防止大结果集打爆内存）
 *
 * @author 走进新时代
 */
public class SqlLimitEnforcer {

    /**
     * 未开启分页时的默认上限
     */
    private static final int DEFAULT_LIMIT = 10000;

    /**
     * 执行前强制检查 LIMIT
     *
     * @param sql 用户 SQL
     * @param hasPage 是否开启分页
     * @return 可能被改写后的 SQL
     */
    public static String enforce(String sql, boolean hasPage) {
        if (StrUtil.isBlank(sql)) {
            return sql;
        }

        boolean hasLimit = containsLimit(sql);

        // 1. 已开启分页 → 检查 SQL 是否含 LIMIT
        if (hasPage) {
            if (!hasLimit) {
                throw exception(DATA_INTERFACE_SQL_MISSING_LIMIT,
                        "已开启分页但 SQL 未使用 LIMIT，请添加 LIMIT #{@pageSize} OFFSET #{@offsetSize}");
            }
            return sql;  // 原样返回
        }

        // 2. 未开启分页 → 强制加 LIMIT 10000
        if (!hasLimit) {
            return injectLimit(sql, DEFAULT_LIMIT);
        }

        return sql;
    }

    /**
     * 检查 SQL 是否含 LIMIT
     *
     * 只检查最外层 SELECT，子查询里的 LIMIT 不算。
     */
    private static boolean containsLimit(String sql) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select) {
                Select select = (Select) stmt;
                if (select.getSelectBody() instanceof PlainSelect) {
                    PlainSelect ps = (PlainSelect) select.getSelectBody();
                    return ps.getLimit() != null;
                }
            }
            return false;
        } catch (JSQLParserException e) {
            // 解析失败，保守地认为没有 LIMIT
            return false;
        }
    }

    /**
     * 自动注入 LIMIT
     *
     * 使用 JSqlParser 改写，避免简单字符串拼接破坏子查询。
     */
    private static String injectLimit(String sql, int limit) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select) {
                Select select = (Select) stmt;
                if (select.getSelectBody() instanceof PlainSelect) {
                    PlainSelect ps = (PlainSelect) select.getSelectBody();
                    if (ps.getLimit() == null) {
                        Limit limitClause = new Limit();
                        limitClause.setRowCount(new net.sf.jsqlparser.expression.LongValue(limit));
                        ps.setLimit(limitClause);
                    }
                }
            }
            return stmt.toString();
        } catch (JSQLParserException e) {
            // 解析失败，用字符串拼接兜底（有风险但至少加了限制）
            return sql + " LIMIT " + limit;
        }
    }

}
