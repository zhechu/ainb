package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.mybatis.core.util.SystemTablePolicy;
import cn.enlev.ainb.framework.mybatis.core.util.TableOperation;
import cn.enlev.ainb.module.infra.enums.formdesign.OperatorEnum;
import cn.enlev.ainb.module.infra.framework.formdesign.core.meta.TableMetadataCache;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * SQL 标识符守卫 —— 所有动态 SQL 的唯一入口
 *
 * 核心原则：凡是不能参数化的部分（表名、列名、排序方向、操作符），
 * 一律【不接受用户输入】，只接受「从已发布配置中取出、并与数据库真实元数据比对通过」的值。
 *
 * 前端传来的查询参数中，只有「值」是自由的，且全部走 ? 占位符参数绑定。
 * 字段名必须命中配置里声明的白名单，否则该条件被忽略 —— 这一层过滤在服务端做，
 * 不依赖前端自律。
 *
 * 三重校验链：
 * 1. 白名单：必须在 table_binding 声明的表/列集合内
 * 2. 元数据：必须在数据库真实存在（防 DBA 改表后配置失效）
 * 3. 正则兜底：^[A-Za-z_][A-Za-z0-9_]{0,63}$
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class SqlIdentifierGuard {

    /**
     * 合法标识符正则。作为白名单与元数据校验之外的最后一道兜底。
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    /**
     * 允许的排序方向。只有这两个常量，绝不拼接用户输入。
     */
    private static final Set<String> ALLOWED_SORT_DIRECTIONS = Set.of("ASC", "DESC");

    @Resource
    private TableMetadataCache metadataCache;

    /**
     * 校验表名
     *
     * @param dataSourceId   数据源编号
     * @param tableName      待校验的表名
     * @param declaredTables 配置中声明的表名集合（白名单）
     * @param forWrite       是否用于写操作。写操作会额外拦截框架表
     * @return 校验通过的表名（原样返回，便于链式使用）
     */
    public String validateTable(Long dataSourceId, String tableName,
                                Set<String> declaredTables, boolean forWrite) {
        // 1. 正则兜底
        if (StrUtil.isBlank(tableName) || !IDENTIFIER_PATTERN.matcher(tableName).matches()) {
            throw exception(FORM_DESIGN_IDENTIFIER_INVALID, tableName);
        }
        // 2. 白名单：必须是配置里声明过的表
        if (declaredTables == null || !declaredTables.contains(tableName)) {
            throw exception(FORM_DESIGN_IDENTIFIER_INVALID, tableName);
        }
        // 3. 写操作时拦截系统表。读一律放行 —— 做报表经常需要读系统表，这是刻意的
        if (forWrite && !SystemTablePolicy.isAllowed(tableName, TableOperation.WRITE)) {
            throw exception(FORM_DESIGN_FRAMEWORK_TABLE_DENY, tableName);
        }
        // 4. 元数据：必须在数据库真实存在
        TableInfo table = metadataCache.get(dataSourceId, tableName);
        if (table == null) {
            throw exception(FORM_DESIGN_TABLE_NOT_EXISTS, tableName);
        }
        return tableName;
    }

    /**
     * 校验列名
     *
     * @param dataSourceId    数据源编号
     * @param tableName       所属表名（调用前应已通过 validateTable）
     * @param columnName      待校验的列名
     * @param declaredColumns 配置中声明的列名集合（白名单）
     * @return 校验通过的列名
     */
    public String validateColumn(Long dataSourceId, String tableName, String columnName,
                                 Set<String> declaredColumns) {
        // 1. 正则兜底
        if (StrUtil.isBlank(columnName) || !IDENTIFIER_PATTERN.matcher(columnName).matches()) {
            throw exception(FORM_DESIGN_IDENTIFIER_INVALID, columnName);
        }
        // 2. 白名单
        if (declaredColumns == null || !declaredColumns.contains(columnName)) {
            throw exception(FORM_DESIGN_IDENTIFIER_INVALID, columnName);
        }
        // 3. 元数据
        if (!getRealColumns(dataSourceId, tableName).contains(columnName)) {
            throw exception(FORM_DESIGN_COLUMN_NOT_EXISTS, tableName, columnName);
        }
        return columnName;
    }

    /**
     * 读取表的真实列名集合
     *
     * ⚠️ 本方法【每校验一列就会被调一次】（见 {@link #validateColumn}），一次 SELECT /
     * INSERT 有多少个映射列就调多少次。所以它走 {@link TableMetadataCache} 而不是
     * 直接调 {@code DatabaseTableService} —— 后者每次都新建连接、用 Generator 解析
     * 一遍整库表结构，实测单次 50–80ms，一张 4 字段的表单一次写入就要 160–335ms。
     *
     * 本类改造前的注释写着「不要在本类做静态缓存 —— DBA 改表后需要能及时反映出来」。
     * 那个顾虑是对的，解决办法不是不缓存，而是<b>带过期时间的缓存 + 发布时主动失效</b>：
     * 发布校验的职责是「真去核实表与列存在」，它会先 invalidate 再校验，
     * 所以「改表后要能反映出来」这条约束仍然成立，只是运行时允许有一个 TTL 的滞后。
     */
    public Set<String> getRealColumns(Long dataSourceId, String tableName) {
        TableInfo table = metadataCache.get(dataSourceId, tableName);
        if (table == null || table.getFields() == null) {
            throw exception(FORM_DESIGN_TABLE_NOT_EXISTS, tableName);
        }
        Set<String> columns = new HashSet<>();
        for (TableField field : table.getFields()) {
            columns.add(field.getName());
        }
        return columns;
    }

    /**
     * 校验并返回排序方向
     *
     * 只允许 ASC / DESC 两个常量，其余一律拒绝。
     */
    public String validateSortDirection(String direction) {
        if (StrUtil.isBlank(direction)) {
            return "ASC";
        }
        String upper = direction.trim().toUpperCase();
        if (!ALLOWED_SORT_DIRECTIONS.contains(upper)) {
            throw exception(FORM_DESIGN_IDENTIFIER_INVALID, direction);
        }
        return upper;
    }

    /**
     * 校验表别名与列别名
     *
     * 别名由用户在关联表配置里填写，会直接拼进 SQL（AS 后面、表限定前缀里），
     * 无法参数化，因此必须过与列名同样严格的正则。
     *
     * 与 {@link #validateColumn} 的区别：别名是新造的标识符，
     * 数据库元数据里不存在，所以只能做正则校验，没有白名单可比对。
     */
    public String validateAlias(String alias) {
        if (StrUtil.isBlank(alias) || !IDENTIFIER_PATTERN.matcher(alias).matches()) {
            throw exception(FORM_DESIGN_IDENTIFIER_INVALID, alias);
        }
        return alias;
    }

    /**
     * 判断是否为合法标识符，不合法时返回 false 而不是抛异常
     *
     * 给「不合法就退回降级方案」的调用方用（见 SqlBuilderSupport 的字段别名）。
     * 那类场景下抛异常会把一个局部的配置问题放大成整个查询失败，
     * 反而比降级更严重。
     */
    public boolean isValidIdentifier(String identifier) {
        return StrUtil.isNotBlank(identifier) && IDENTIFIER_PATTERN.matcher(identifier).matches();
    }

    /**
     * 校验并返回操作符枚举
     *
     * 操作符枚举化是防注入的关键 —— 运行时绝不拼接用户传来的操作符字符串。
     */
    public OperatorEnum validateOperator(String operatorCode) {
        if (StrUtil.isBlank(operatorCode)) {
            return OperatorEnum.EQ;
        }
        return Arrays.stream(OperatorEnum.values())
                .filter(op -> op.getCode().equalsIgnoreCase(operatorCode))
                .findFirst()
                .orElseThrow(() -> exception(FORM_DESIGN_OPERATOR_INVALID, operatorCode));
    }

    /**
     * 用反引号包裹标识符
     *
     * 仅在标识符已通过 validateTable / validateColumn 之后使用。
     * 包裹是为了兼容与关键字同名的列（如 order、status），不是安全手段 ——
     * 安全性由前面的三重校验保证。
     */
    public String quote(String identifier) {
        return "`" + identifier + "`";
    }

}
