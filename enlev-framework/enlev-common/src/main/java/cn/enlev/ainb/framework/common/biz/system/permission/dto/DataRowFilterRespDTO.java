package cn.enlev.ainb.framework.common.biz.system.permission.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 行过滤条件
 *
 * 权限模块算出「当前用户能看这张表的哪些行」，以 SQL 片段 + 参数的形态交给调用方，
 * 由调用方拼进自己的查询。
 *
 * <h3>为什么要有这个 DTO（而不是只靠 MyBatis 拦截器）</h3>
 *
 * 行级权限原本只有一条实现路径：{@code RuleDataPermissionRule}，它是 MyBatis-Plus 的
 * {@code DataPermissionRule}，只能拦截走 MyBatis 的 SQL。
 *
 * 而在线表单的运行时读写是<b>用 JdbcTemplate 拼原生 SQL</b> 的 ——
 * 它压根不经过 MyBatis 的执行器，拦截器对它一行 SQL 都改写不了。
 * 后果是「行级规则配了、表也注册了、日志也打了，但查询照常返回全部数据」，
 * 没有报错、没有日志、界面完全正常。
 *
 * 所以自己拼 SQL 的调用方需要这样一个出口，主动把条件要过去。
 *
 * @author 走进新时代
 */
@Data
public class DataRowFilterRespDTO {

    /**
     * 是否需要过滤
     *
     * false 表示「这张表上没有任何行级规则」——调用方原样查询即可。
     * ⚠️ 不要把它和 {@link #denyAll} 搞混：不过滤是「全放行」，全拒是「一行都不给」
     */
    private boolean filtered;

    /**
     * 是否全部拒绝
     *
     * 用户主体解析失败等异常场景。调用方必须直接返回空结果，
     * <b>不能</b>当成「没有过滤条件」继续查 —— 那是把异常变成越权
     */
    private boolean denyAll;

    /**
     * SQL 条件片段，不含 WHERE 关键字，形如 {@code (`t`.`creator` = ? OR `t`.`dept_id` IN (?, ?))}
     *
     * 列名只来自 {@code sys_slot_mapping}（列白名单），不来自用户输入。
     * 调用方把它用 AND 拼进自己的 WHERE 即可
     */
    private String sql;

    /**
     * 参数值，顺序与 sql 里的 {@code ?} 一一对应
     */
    private List<Object> params = new ArrayList<>();

    /**
     * 不需要过滤（这张表没有配任何行级规则）
     */
    public static DataRowFilterRespDTO pass() {
        DataRowFilterRespDTO dto = new DataRowFilterRespDTO();
        dto.setFiltered(false);
        return dto;
    }

    /**
     * 一行都不给
     */
    public static DataRowFilterRespDTO denyAll() {
        DataRowFilterRespDTO dto = new DataRowFilterRespDTO();
        dto.setFiltered(true);
        dto.setDenyAll(true);
        return dto;
    }

    public static DataRowFilterRespDTO of(String sql, List<Object> params) {
        DataRowFilterRespDTO dto = new DataRowFilterRespDTO();
        dto.setFiltered(true);
        dto.setSql(sql);
        dto.setParams(params != null ? new ArrayList<>(params) : new ArrayList<>());
        return dto;
    }

}
