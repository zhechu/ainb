package cn.enlev.ainb.module.infra.framework.datainterface.core.sql;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static cn.enlev.ainb.module.infra.enums.datainterface.SqlStatementTypeEnum.*;

/**
 * SQL 语句分析器
 *
 * 支持**多条语句**（业务需要：一个接口里做「插主表 + 插子表」这类组合操作）。
 * 逐条分析每一句，汇总出：
 * 1. 每条语句的类型与涉及的表，供租户改写与框架表保护逐条处理
 * 2. 整体是否含写操作，用于列表页标注与高危权限判断
 *
 * 任何一条解析失败都返回失败 —— 无法确认能否隔离的语句，不允许配置。
 *
 * @author 走进新时代
 */
@Slf4j
public class SqlStatementAnalyzer {

    /**
     * 单条语句的分析结果
     */
    @Getter
    public static class StatementInfo {

        /**
         * 原始 SQL 文本
         */
        private final String sql;
        /**
         * 语句类型
         */
        private final String statementType;
        /**
         * 是否写操作
         */
        private final boolean writeOperation;
        /**
         * 涉及的表名
         */
        private final Set<String> tableNames;

        StatementInfo(String sql, String statementType, boolean writeOperation, Set<String> tableNames) {
            this.sql = sql;
            this.statementType = statementType;
            this.writeOperation = writeOperation;
            this.tableNames = tableNames;
        }

    }

    /**
     * 整体分析结果
     */
    @Getter
    public static class AnalyzeResult {

        /**
         * 逐条语句的分析结果，顺序与 SQL 中出现顺序一致
         */
        private final List<StatementInfo> statements = new ArrayList<>();
        /**
         * 失败原因；成功时为 null
         */
        private String errorMsg;

        public boolean isSuccess() {
            return errorMsg == null;
        }

        /**
         * 是否为多语句
         */
        public boolean isMultiStatement() {
            return statements.size() > 1;
        }

        /**
         * 整体是否含写操作：任一条是写操作即为 true
         */
        public boolean hasWriteOperation() {
            return statements.stream().anyMatch(StatementInfo::isWriteOperation);
        }

        /**
         * 整体语句类型
         *
         * 单语句返回该语句类型；多语句返回首条写操作的类型，全是查询则返回 SELECT。
         * 这个值只用于列表页展示，精确判断请用 {@link #getStatements()}。
         */
        public String getOverallType() {
            if (statements.isEmpty()) {
                return OTHER.getType();
            }
            if (statements.size() == 1) {
                return statements.get(0).getStatementType();
            }
            return statements.stream().filter(StatementInfo::isWriteOperation).findFirst()
                    .map(StatementInfo::getStatementType).orElse(SELECT.getType());
        }

        /**
         * 所有语句涉及的表名合集
         */
        public Set<String> getAllTableNames() {
            Set<String> all = new LinkedHashSet<>();
            statements.forEach(item -> all.addAll(item.getTableNames()));
            return all;
        }

        static AnalyzeResult fail(String errorMsg) {
            AnalyzeResult result = new AnalyzeResult();
            result.errorMsg = errorMsg;
            return result;
        }

    }

    private SqlStatementAnalyzer() {
    }

    /**
     * 分析 SQL，支持多条语句
     *
     * @param sql 用户配置的 SQL（占位符已替换为 ?，否则解析会失败）
     */
    public static AnalyzeResult analyze(String sql) {
        List<Statement> parsed;
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            parsed = statements.getStatements();
        } catch (Exception ex) {
            log.warn("[analyze][SQL 解析失败：{}]", sql, ex);
            return AnalyzeResult.fail("SQL 语法解析失败：" + ex.getMessage());
        }
        if (parsed == null || parsed.isEmpty()) {
            return AnalyzeResult.fail("SQL 不能为空");
        }

        AnalyzeResult result = new AnalyzeResult();
        for (Statement statement : parsed) {
            String type = resolveType(statement).getType();
            result.statements.add(new StatementInfo(
                    statement.toString(),
                    type,
                    typeOf(type).getWriteOperation(),
                    extractTableNames(statement)));
        }
        return result;
    }

    /**
     * 判断语句类型
     */
    private static cn.enlev.ainb.module.infra.enums.datainterface.SqlStatementTypeEnum
            resolveType(Statement statement) {
        if (statement instanceof Select) {
            return SELECT;
        }
        if (statement instanceof Insert) {
            return INSERT;
        }
        if (statement instanceof Update) {
            return UPDATE;
        }
        if (statement instanceof Delete) {
            return DELETE;
        }
        if (statement instanceof CreateTable || statement instanceof Alter
                || statement instanceof Drop || statement instanceof Truncate) {
            return DDL;
        }
        // 认不出来的按最危险处理
        return OTHER;
    }

    /**
     * 提取涉及的表名
     *
     * 提取失败不阻断分析 —— 表名只用于框架表保护这一层附加校验。
     */
    private static Set<String> extractTableNames(Statement statement) {
        try {
            return new LinkedHashSet<>(new TablesNamesFinder().getTableList(statement));
        } catch (Exception ex) {
            log.warn("[extractTableNames][表名提取失败]", ex);
            return Collections.emptySet();
        }
    }

}
