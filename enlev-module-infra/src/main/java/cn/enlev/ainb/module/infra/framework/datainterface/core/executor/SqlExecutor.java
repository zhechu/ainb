package cn.enlev.ainb.module.infra.framework.datainterface.core.executor;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.infra.enums.datainterface.DataInterfaceTypeEnum;
import cn.enlev.ainb.module.infra.enums.datainterface.SqlStatementTypeEnum;
import cn.enlev.ainb.module.infra.framework.datainterface.core.param.SqlParamBinder;
import cn.enlev.ainb.module.infra.framework.datainterface.core.sql.SqlLimitEnforcer;
import cn.enlev.ainb.module.infra.framework.datainterface.core.sql.SqlPolicyValidator;
import cn.enlev.ainb.module.infra.framework.datainterface.core.sql.SqlStatementAnalyzer;
import cn.enlev.ainb.module.infra.framework.datainterface.core.sql.SqlTenantRewriter;
import cn.enlev.ainb.module.infra.framework.datamodel.core.SqlSafeGuard;
import cn.enlev.ainb.module.infra.framework.datamodel.core.TableMetaReader;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * SQL 类型的接口执行器
 *
 * 执行流程（顺序很重要）：
 * <pre>
 * ① 参数绑定：#{xx} → ?，值收集到参数数组。值永远不拼进 SQL
 * ② 语句分析：识别类型、提取表名
 * ③ 框架表写保护：禁止对系统内置表做写操作
 * ④ 租户改写：为每条语句注入租户条件；解析不了就拒绝
 * ⑤ 逐条执行：JDBC 不允许一次执行多条语句，必须拆开
 * </pre>
 *
 * 返回格式看**用户配置的动作类型**（{@code sqlStatementType}），不看解析结果：
 * 查询动作返回结果集，写动作只返回汇总影响行数。解析结果只用来决定单条语句
 * 该调 {@code query} 还是 {@code update}。
 *
 * 多语句的事务由调用方的 {@code @Transactional} 保证「失败全回滚」。
 * 注意 DDL 在 MySQL 里无法回滚，多语句里混有 DDL 时事务保不住 —— 这一点
 * 在配置界面会提示用户。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class SqlExecutor implements InterfaceExecutor {

    /**
     * 预览时的默认返回条数上限
     */
    private static final int DEFAULT_PREVIEW_LIMIT = 100;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 「表是否需要租户隔离」的缓存
     *
     * 每次执行都查 information_schema 太浪费，而表结构在运行期极少变。
     * 用 ConcurrentHashMap 缓存判定结果；表结构真变了重启即可，
     * 不值得为此引入失效机制。
     */
    private final Map<String, Boolean> tenantTableCache = new ConcurrentHashMap<>();

    @Override
    public DataInterfaceTypeEnum getType() {
        return DataInterfaceTypeEnum.SQL;
    }

    @Override
    public Object execute(InterfaceExecuteContext context) {
        String rawSql = context.getDataInterface().getConfig();
        if (StrUtil.isBlank(rawSql)) {
            throw exception(DATA_INTERFACE_SQL_EMPTY);
        }

        // ① 参数预处理：如果开启分页，注入分页系统变量
        Map<String, Object> finalParams = context.getRequestArgs();
        boolean hasPage = context.getDataInterface().getHasPage() != null && context.getDataInterface().getHasPage() == 1;
        if (hasPage) {
            finalParams = enrichPaginationParams(context.getRequestArgs());
        }

        // ② 参数绑定：值走占位符
        SqlParamBinder.BindResult bind = SqlParamBinder.bind(rawSql,
                context.getParamDefinitions(), finalParams);

        // ③ 语句分析
        SqlStatementAnalyzer.AnalyzeResult analyze = SqlStatementAnalyzer.analyze(bind.getSql());
        if (!analyze.isSuccess()) {
            throw exception(DATA_INTERFACE_SQL_PARSE_FAIL, analyze.getErrorMsg());
        }

        // ④ 策略校验：禁 DDL、框架表写保护
        SqlPolicyValidator.validate(analyze);

        // ⑤ 租户改写
        Long tenantId = TenantContextHolder.getTenantId();
        SqlTenantRewriter rewriter = new SqlTenantRewriter(this::isTenantTable);
        SqlTenantRewriter.RewriteResult rewrite = rewriter.rewrite(bind.getSql(), tenantId);
        if (!rewrite.isSuccess()) {
            throw exception(DATA_INTERFACE_SQL_TENANT_REWRITE_FAIL, rewrite.getErrorMsg());
        }
        context.setExecuteSql(rewrite.getSql());

        // ⑥ 取用户选的动作类型：它决定返回格式（查询返结果集 / 其它返影响行数）
        SqlStatementTypeEnum actionType = SqlStatementTypeEnum
                .typeOf(context.getDataInterface().getSqlStatementType());
        boolean queryAction = SqlStatementTypeEnum.SELECT == actionType;

        // ⑦ LIMIT 强制检查。只对查询动作有意义 —— 写操作的 SQL 不该被塞 LIMIT
        List<String> finalSqlList = new ArrayList<>();
        for (String sql : rewrite.getStatementSqlList()) {
            finalSqlList.add(queryAction ? SqlLimitEnforcer.enforce(sql, hasPage) : sql);
        }

        // ⑧ 执行 SQL
        Object result = executeStatements(finalSqlList, analyze, bind.getArgs(), context, queryAction);

        // ⑨ 查询动作且开启分页时，执行 COUNT SQL 并返回 {total, list} 格式
        if (queryAction && hasPage && finalSqlList.size() == 1) {
            return executePaginatedQuery(result, context, finalParams);
        }

        return result;
    }

    /**
     * 注入分页系统变量：pageSize、pageNo、offsetSize
     *
     * 约定规则：
     * - pageSize 和 pageNo 由用户传入（不需要在参数定义中声明）
     * - offsetSize 系统自动计算：(pageNo - 1) * pageSize
     */
    private Map<String, Object> enrichPaginationParams(Map<String, Object> originalParams) {
        Map<String, Object> enriched = new java.util.HashMap<>(originalParams);

        // 提取 pageSize 和 pageNo（用户传入的值）
        Integer pageSize = getIntParam(originalParams, "pageSize", 20);  // 默认20
        Integer pageNo = getIntParam(originalParams, "pageNo", 1);       // 默认1

        // 计算 offsetSize
        int offsetSize = (pageNo - 1) * pageSize;

        // 注入三个系统变量
        enriched.put("pageSize", pageSize);
        enriched.put("pageNo", pageNo);
        enriched.put("offsetSize", offsetSize);

        return enriched;
    }

    /**
     * 获取整数参数
     */
    private Integer getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 逐条执行
     *
     * 参数按占位符出现顺序分配给各条语句 —— 拆分后每条语句只拿属于自己的那部分参数。
     *
     * 返回格式由**用户选的动作类型**决定，而不是逐条看解析结果：
     * <ul>
     *     <li>查询动作：返回结果集。多条语句时返回结果数组</li>
     *     <li>写动作：只返回汇总的影响行数 {@code {affectedRows: N}}。
     *         这是刻意的 —— 用户既然把接口标成「新增」，调用方要的就是「成功改了几行」，
     *         中间夹的 SELECT（如查主键回填）属于实现细节，不该泄漏到响应里</li>
     * </ul>
     *
     * @param queryAction 用户选的动作类型是否为「查询」
     */
    private Object executeStatements(List<String> sqlList,
                                     SqlStatementAnalyzer.AnalyzeResult analyze,
                                     List<Object> allArgs,
                                     InterfaceExecuteContext context,
                                     boolean queryAction) {
        List<Object> results = new ArrayList<>();
        int argIndex = 0;
        int totalAffectedRows = 0;
        for (int i = 0; i < sqlList.size(); i++) {
            String sql = sqlList.get(i);
            int placeholderCount = countPlaceholders(sql);
            Object[] args = allArgs.subList(argIndex, argIndex + placeholderCount).toArray();
            argIndex += placeholderCount;

            // 单条语句该用 query 还是 update，仍要看这条语句本身是什么 ——
            // JDBC 对 SELECT 调 update() 会直接报错
            boolean selectStatement = i < analyze.getStatements().size()
                    && SqlStatementTypeEnum.SELECT.getType()
                            .equals(analyze.getStatements().get(i).getStatementType());
            if (selectStatement) {
                List<Map<String, Object>> rows = queryWithLimit(sql, args, context);
                if (queryAction) {
                    results.add(rows);
                }
                // 写动作里夹的 SELECT 照常执行（可能是 SELECT ... FOR UPDATE 之类），但结果不外传
            } else {
                int affectedRows = jdbcTemplate.update(sql, args);
                totalAffectedRows += affectedRows;
                if (queryAction) {
                    // 用户标成查询却混进了写语句，理论上保存时已被拦住；
                    // 历史数据兜底，仍把影响行数放进结果里，不静默丢弃
                    results.add(Map.of("affectedRows", affectedRows));
                }
            }
        }

        if (!queryAction) {
            return Map.of("affectedRows", totalAffectedRows);
        }
        // 单语句直接返回其结果，多语句返回结果数组
        return results.size() == 1 ? results.get(0) : results;
    }

    /**
     * 查询并按预览上限截断
     *
     * 预览场景下强制限制条数，避免用户误配全表查询把页面卡死。
     * 不改写 SQL 加 LIMIT，而是在结果侧截断 —— 改写 LIMIT 要处理用户已写 LIMIT 的情况，
     * 复杂且容易出错；查询本身的代价由数据库承担，这里只保护前端。
     */
    private List<Map<String, Object>> queryWithLimit(String sql, Object[] args,
                                                     InterfaceExecuteContext context) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, args);
        if (!context.isPreview()) {
            return list;
        }
        int limit = context.getPreviewLimit() == null
                ? DEFAULT_PREVIEW_LIMIT : context.getPreviewLimit();
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    /**
     * 判断表是否需要租户隔离：有 tenant_id 字段即需要
     */
    private boolean isTenantTable(String tableName) {
        return tenantTableCache.computeIfAbsent(tableName.toLowerCase(), name -> {
            try {
                Set<String> columns = new TableMetaReader(jdbcTemplate).getColumnNames(name);
                return columns.stream()
                        .anyMatch(column -> SqlTenantRewriter.TENANT_COLUMN.equalsIgnoreCase(column));
            } catch (Exception ex) {
                // 读不到表结构（如跨库表、视图）时保守处理：不注入，
                // 但这类表通常也不含租户字段
                log.warn("[isTenantTable][读取表({})结构失败]", name, ex);
                return false;
            }
        });
    }

    /**
     * 执行分页查询，返回 {total, list} 格式
     */
    private Object executePaginatedQuery(Object listResult, InterfaceExecuteContext context, Map<String, Object> finalParams) {
        String countSql = context.getDataInterface().getCountSql();
        Long total = 0L;

        try {
            if (StrUtil.isNotBlank(countSql)) {
                // 用户配置了 COUNT SQL
                SqlParamBinder.BindResult countBind = SqlParamBinder.bind(countSql,
                        context.getParamDefinitions(), finalParams);

                Long tenantId = TenantContextHolder.getTenantId();
                SqlTenantRewriter rewriter = new SqlTenantRewriter(this::isTenantTable);
                SqlTenantRewriter.RewriteResult rewrite = rewriter.rewrite(countBind.getSql(), tenantId);
                if (!rewrite.isSuccess()) {
                    throw exception(DATA_INTERFACE_SQL_TENANT_REWRITE_FAIL, rewrite.getErrorMsg());
                }

                total = jdbcTemplate.queryForObject(rewrite.getStatementSqlList().get(0), Long.class, countBind.getArgs().toArray());
            } else {
                // 自动生成 COUNT SQL
                String originalSql = context.getDataInterface().getConfig();
                String countQuerySql = generateCountSql(originalSql);

                SqlParamBinder.BindResult countBind = SqlParamBinder.bind(countQuerySql,
                        context.getParamDefinitions(), finalParams);

                Long tenantId = TenantContextHolder.getTenantId();
                SqlTenantRewriter rewriter = new SqlTenantRewriter(this::isTenantTable);
                SqlTenantRewriter.RewriteResult rewrite = rewriter.rewrite(countBind.getSql(), tenantId);
                if (!rewrite.isSuccess()) {
                    throw exception(DATA_INTERFACE_SQL_TENANT_REWRITE_FAIL, rewrite.getErrorMsg());
                }

                total = jdbcTemplate.queryForObject(rewrite.getStatementSqlList().get(0), Long.class, countBind.getArgs().toArray());
            }
        } catch (Exception e) {
            log.error("[executePaginatedQuery][COUNT SQL 执行失败]", e);
            total = 0L;
        }

        // 返回分页格式
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", total);
        result.put("list", listResult);
        return result;
    }

    /**
     * 自动生成 COUNT SQL
     *
     * @param originalSql 原始 SQL
     * @return COUNT SQL
     */
    private String generateCountSql(String originalSql) {
        // 移除 LIMIT 和 OFFSET 子句
        String cleanedSql = originalSql.replaceAll("(?i)\\s+LIMIT\\s+[^;]*", "");
        cleanedSql = cleanedSql.replaceAll("(?i)\\s+OFFSET\\s+[^;]*", "");

        // 包装为 COUNT 查询
        return "SELECT COUNT(*) FROM (" + cleanedSql + ") AS t";
    }

    /**
     * 统计 SQL 里的占位符个数
     *
     * 跳过字符串字面量里的问号，避免 'a?b' 被误计
     */
    private int countPlaceholders(String sql) {
        int count = 0;
        boolean inLiteral = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inLiteral = !inLiteral;
            } else if (c == '?' && !inLiteral) {
                count++;
            }
        }
        return count;
    }

}
