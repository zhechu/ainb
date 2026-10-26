package cn.enlev.ainb.module.infra.framework.datainterface.core.param;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.infra.framework.datamodel.core.ValueConverter;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * SQL 参数绑定器
 *
 * 把用户 SQL 里的 {@code #{paramName}} 占位符转成 JDBC 的 {@code ?}，并按出现顺序
 * 收集对应的值。
 *
 * ⚠️ 安全底线：**参数值永远走 ? 占位符，绝不拼进 SQL 字符串**。
 * 这是防 SQL 注入的根本手段 —— 用户输入的值无论多恶意，都只是一个参数值，
 * 不会改变 SQL 的语法结构。
 *
 * 内置变量（{@code #{_xxx}}）的值取自服务端上下文而非请求参数，用户无法伪造：
 * <ul>
 *     <li>{@code #{_userId}}   当前登录用户编号</li>
 *     <li>{@code #{_tenantId}} 当前租户编号</li>
 *     <li>{@code #{_deptId}}   当前用户部门编号</li>
 * </ul>
 *
 * 分页系统变量（开启分页时自动注入，不需要用户定义）：
 * <ul>
 *     <li>{@code #{pageSize}}   每页条数（用户传入）</li>
 *     <li>{@code #{pageNo}}     当前页码（用户传入）</li>
 *     <li>{@code #{offsetSize}} 偏移量（系统自动计算）</li>
 * </ul>
 *
 * @author 走进新时代
 */
public class SqlParamBinder {

    /**
     * 占位符：#{paramName}
     *
     * 参数名限制为字母数字下划线，避免奇怪字符混入
     */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("#\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}");

    /**
     * 内置变量前缀
     */
    private static final String BUILTIN_PREFIX = "_";

    public static final String VAR_USER_ID = "_userId";
    public static final String VAR_TENANT_ID = "_tenantId";
    public static final String VAR_DEPT_ID = "_deptId";

    /**
     * 分页系统变量（开启分页时自动注入）
     */
    public static final String VAR_PAGE_SIZE = "pageSize";
    public static final String VAR_PAGE_NO = "pageNo";
    public static final String VAR_OFFSET_SIZE = "offsetSize";

    /**
     * 绑定结果
     */
    @Getter
    public static class BindResult {

        /**
         * 转换后的 SQL，占位符已变成 ?
         */
        private String sql;
        /**
         * 与 ? 一一对应的参数值，顺序即绑定顺序
         */
        private final List<Object> args = new ArrayList<>();

    }

    private SqlParamBinder() {
    }

    /**
     * 提取 SQL 里用到的参数名（不含内置变量和分页系统变量）
     *
     * 用于保存时校验「SQL 里用到的参数都已定义」。
     */
    public static List<String> extractParamNames(String sql) {
        List<String> names = new ArrayList<>();
        if (StrUtil.isBlank(sql)) {
            return names;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(sql);
        while (matcher.find()) {
            String name = matcher.group(1);
            // 排除内置变量（_xxx）和分页系统变量（pageSize/pageNo/offsetSize）
            if (!name.startsWith(BUILTIN_PREFIX)
                && !isPaginationSystemVar(name)
                && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 判断是否为分页系统变量
     */
    private static boolean isPaginationSystemVar(String name) {
        return VAR_PAGE_SIZE.equals(name)
            || VAR_PAGE_NO.equals(name)
            || VAR_OFFSET_SIZE.equals(name);
    }

    /**
     * 绑定参数
     *
     * @param sql         用户配置的 SQL，含 #{xx} 占位符
     * @param definitions 入参定义
     * @param requestArgs 请求传来的参数值
     * @return 绑定结果
     */
    public static BindResult bind(String sql, List<ParamDefinition> definitions,
                                  Map<String, Object> requestArgs) {
        // 1. 先按定义校验并转换类型
        Map<String, Object> values = ParamValidator.validateAndConvert(definitions, requestArgs);
        // 2. 补上内置变量
        values.putAll(resolveBuiltinVariables());

        // 3. 逐个替换占位符，同时按顺序收集值
        BindResult result = new BindResult();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(sql);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String name = matcher.group(1);

            if (!values.containsKey(name)) {
                throw exception(DATA_INTERFACE_PARAM_NOT_DEFINED, name);
            }
            sb.append(sql, last, matcher.start()).append('?');
            result.args.add(values.get(name));
            last = matcher.end();
        }
        sb.append(sql.substring(last));
        result.sql = sb.toString();
        return result;
    }

    /**
     * 解析内置变量
     *
     * 值全部来自服务端上下文，用户传同名参数也会被这里覆盖 ——
     * 这是刻意的：内置变量不允许伪造。
     */
    private static Map<String, Object> resolveBuiltinVariables() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(VAR_USER_ID, SecurityFrameworkUtils.getLoginUserId());
        values.put(VAR_TENANT_ID, TenantContextHolder.getTenantId());
        values.put(VAR_DEPT_ID, SecurityFrameworkUtils.getLoginUserDeptId());
        return values;
    }

}
