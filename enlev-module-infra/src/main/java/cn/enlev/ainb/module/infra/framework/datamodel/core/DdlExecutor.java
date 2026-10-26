package cn.enlev.ainb.module.infra.framework.datamodel.core;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * DDL 执行器
 *
 * ⚠️ 关于事务：**MySQL 的 DDL 无法回滚**（8.0 的原子 DDL 仅保证单条语句的原子性，
 * 不提供跨语句事务）。所以一次改表包含多条 DDL 时，若第 3 条失败，前 2 条**已经生效**。
 * 本类的应对是：
 * 1. 顺序执行，失败即停，不再执行后续语句
 * 2. 返回「已成功执行的语句」清单，让调用方能记录到日志、并如实告知用户当前状态
 * 3. 不假装能回滚 —— 这一点必须在 UI 上让用户知晓
 *
 * @author 走进新时代
 */
@Slf4j
public class DdlExecutor {

    private final JdbcTemplate jdbcTemplate;

    public DdlExecutor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 执行结果
     */
    @Getter
    public static class Result {

        /**
         * 已成功执行的语句
         */
        private final List<String> executedSqlList = new ArrayList<>();
        /**
         * 失败的语句，为 null 表示全部成功
         */
        private String failedSql;
        /**
         * 失败原因
         */
        private String errorMsg;
        /**
         * 总耗时，单位：毫秒
         */
        private long duration;

        public boolean isSuccess() {
            return failedSql == null;
        }

        /**
         * 是否处于「部分执行」状态 —— 这是最需要告知用户的情况
         */
        public boolean isPartial() {
            return failedSql != null && !executedSqlList.isEmpty();
        }

    }

    /**
     * 顺序执行多条 DDL，失败即停
     *
     * @param sqlList DDL 列表
     * @return 执行结果
     */
    public Result execute(List<String> sqlList) {
        Result result = new Result();
        long start = System.currentTimeMillis();
        for (String sql : sqlList) {
            try {
                log.info("[execute][执行 DDL：{}]", sql);
                jdbcTemplate.execute(sql);
                result.executedSqlList.add(sql);
            } catch (Exception ex) {
                log.error("[execute][DDL 执行失败：{}]", sql, ex);
                result.failedSql = sql;
                result.errorMsg = ex.getMessage();
                break;
            }
        }
        result.duration = System.currentTimeMillis() - start;
        return result;
    }

    /**
     * 执行单条 DDL
     */
    public Result execute(String sql) {
        return execute(List.of(sql));
    }

}
