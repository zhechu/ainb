package cn.enlev.ainb.module.system.framework.permission.core.rule;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DataRowFilterRespDTO;
import cn.enlev.ainb.framework.datapermission.core.rule.DataPermissionRule;
import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.module.system.service.permission.DataRowFilterResolver;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 sys_grant + sys_rule 的行过滤数据权限规则（MyBatis 通道）
 *
 * <h3>只负责「组装」，不负责「判定」</h3>
 *
 * 判定逻辑（主体闭包 → 命中的授权 → 条件树求值 → ALLOW/DENY 合并）统一在
 * {@link DataRowFilterResolver}，本类只把它的产出转成 JSqlParser 的 {@code Expression}。
 *
 * 之所以拆开：行级权限有两个消费方，走的通道完全不同 ——
 * 本类拦的是 MyBatis 的 SQL，而在线表单运行时是用 JdbcTemplate 拼原生 SQL 的，
 * 它压根不经过 MyBatis 执行器，本类对它一行 SQL 都改写不了。
 * 两边各写一套判定必然漂移，而漂移的表现是「同一个用户，列表看得到的行详情打不开」，
 * 极难归因。
 *
 * <h3>参数为什么要内联成字面量</h3>
 *
 * {@link DataRowFilterResolver} 产出的是参数化片段（{@code ? } + 参数表），这对
 * JdbcTemplate 是最安全的形态。但 MyBatis 拦截器改写的是<b>已经定型的</b> SQL 语句，
 * 没有办法再往 {@code BoundSql} 的 ParameterMapping 里追加参数，
 * 所以这里必须把值内联回去。
 *
 * 内联是安全的：值来自服务端变量（当前用户 id、部门 id 等），不是用户输入；
 * 列名来自槽位映射白名单。字符串值仍做了引号转义。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class RuleDataPermissionRule implements DataPermissionRule {

    /**
     * 声明需要行级过滤的表名集合
     *
     * 两个写入时机，缺一不可：
     * <ul>
     *   <li>表单发布时（{@code DatasetResourceServiceImpl.register}）—— 新表单立刻生效</li>
     *   <li>应用启动时（{@code DatasetTableRegisterRunner}）—— 否则重启后本集合为空，
     *       所有行级规则静默失效</li>
     * </ul>
     */
    private final Set<String> tableNames = new LinkedHashSet<>();

    @Resource
    private DataRowFilterResolver rowFilterResolver;

    public void registerTable(String tableName) {
        if (StrUtil.isBlank(tableName)) {
            return;
        }
        tableNames.add(tableName.toLowerCase());
    }

    @Override
    public Set<String> getTableNames() {
        return Collections.unmodifiableSet(tableNames);
    }

    @Override
    public Expression getExpression(String tableName, Alias tableAlias) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long userId = loginUser != null ? loginUser.getId() : null;

        // 列前缀：有别名用别名，没有就用表名限定。
        // 不加限定的话，JOIN 查询里裸列名会歧义 —— 要么 SQL 直接报错，
        // 要么匹配到关联表的同名列，那就是错误的过滤
        String prefix = tableAlias != null && StrUtil.isNotBlank(tableAlias.getName())
                ? tableAlias.getName() + "."
                : tableName + ".";

        DataRowFilterRespDTO filter = rowFilterResolver.resolve(userId, tableName, prefix,
                // MyBatis 这条路一次只看得到一张表（拦截器按表回调），所以限定符表就一条。
                // ⚠️ 必须显式给：槽位映射可以绑到别的表上，而那条映射在这张表的查询里
                //    求不出来 —— 让它按「求不出来」失效（fail-closed）比悄悄用主表前缀去凑好，
                //    后者会匹配到主表的同名列，生成一条语法合法、语义错误的过滤
                Map.of(tableName.toLowerCase(), prefix));
        if (filter.isDenyAll()) {
            return alwaysFalse();
        }
        if (!filter.isFiltered() || StrUtil.isBlank(filter.getSql())) {
            return null; // 不追加过滤
        }
        try {
            return CCJSqlParserUtil.parseCondExpression(inlineParams(filter));
        } catch (Exception e) {
            // 解析不了就全拒，不能放行 —— 放行等于把异常变成越权
            log.error("[getExpression][表({})的行过滤条件解析失败，按全拒处理：{}]",
                    tableName, filter.getSql(), e);
            return alwaysFalse();
        }
    }

    /**
     * 把 {@code ?} 按顺序替换成字面量
     */
    private String inlineParams(DataRowFilterRespDTO filter) {
        String sql = filter.getSql();
        StringBuilder result = new StringBuilder(sql.length() + 32);
        int paramIndex = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?' && paramIndex < filter.getParams().size()) {
                result.append(toLiteral(filter.getParams().get(paramIndex++)));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private String toLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder sb = new StringBuilder("(");
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(toLiteral(item));
                first = false;
            }
            return sb.append(")").toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    /**
     * 恒假条件，用于「一行都不给」
     *
     * 用 {@code 1 = 0} 而不是构造一个空的 EqualsTo —— 后者在不同 JSqlParser
     * 版本上的 toString 结果不稳定，曾经拼出过语法不合法的 SQL
     */
    private Expression alwaysFalse() {
        try {
            return CCJSqlParserUtil.parseCondExpression("1 = 0");
        } catch (Exception e) {
            throw new IllegalStateException("构造恒假条件失败", e);
        }
    }

}
