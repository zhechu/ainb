package cn.enlev.ainb.module.infra.framework.datainterface.core.sql;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * COUNT SQL 自动改写器
 *
 * 把 SELECT xxx FROM ... 改成 SELECT COUNT(*) FROM ...
 * 用于分页时获取总数，避免用户配两遍 SQL。
 *
 * 改写策略：包一层子查询（最稳妥）
 * SELECT COUNT(*) AS total FROM (原始SQL) AS temp_count_table
 *
 * 这种做法适用于所有复杂查询（JOIN / GROUP BY / 子查询 / UNION）。
 *
 * @author 走进新时代
 */
@Slf4j
public class CountSqlRewriter {

    /**
     * 生成 COUNT SQL
     *
     * @param originalSql 用户的查询 SQL
     * @return COUNT SQL
     */
    public static String rewrite(String originalSql) {
        if (StrUtil.isBlank(originalSql)) {
            return "SELECT COUNT(*) AS total";
        }
        // 包一层是最稳妥的做法，适用于所有复杂查询
        return "SELECT COUNT(*) AS total FROM (" + originalSql + ") AS temp_count_table";
    }

}
