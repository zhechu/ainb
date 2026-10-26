package cn.enlev.ainb.module.infra.framework.formdesign.core.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.PermissionCommonApi;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DataRowFilterRespDTO;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在线表单的行级权限取用口
 *
 * <h3>为什么在线表单必须自己来取</h3>
 *
 * 行级权限原本只有 {@code RuleDataPermissionRule} 一条路径，那是 MyBatis-Plus 的
 * 拦截器。而本模块的运行时读写是<b>用 JdbcTemplate 拼原生 SQL</b> 的 ——
 * 压根不经过 MyBatis 执行器，拦截器对它一行 SQL 都改写不了。
 *
 * 也就是说：在接入本类之前，在线表单的行级规则「配了、表也注册了、启动日志也打了
 * 已注册 N 张表」，但查询照常返回全部数据。没有报错、没有日志、界面完全正常 ——
 * 这正是本项目最贵的缺陷形态（见 CLAUDE.md）。
 *
 * <h3>忽略开关</h3>
 *
 * 有些查询不该被过滤：流程回写系统列、导入时的唯一性查重、以及「算权限」本身。
 * 这些场景用 {@link #executeIgnore} 包住。
 *
 * ⚠️ 加忽略开关要非常克制 —— 每加一处都是一个绕过点。判断标准是
 * 「这次查询的结果会不会流向用户」：不会（比如查重只返回布尔）才可以忽略。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormRowFilter {

    /**
     * 忽略标记
     *
     * 用 ThreadLocal 而不是方法参数：需要忽略的是<b>一整段调用链</b>
     * （比如导入的一行数据要经过查重、落域校验、写入多个环节），
     * 逐层传参的话中间漏传一层就是一个静默的绕过点
     */
    private static final ThreadLocal<Boolean> IGNORE = ThreadLocal.withInitial(() -> false);

    /**
     * 只在第一次缺失时告警，避免刷屏
     */
    private final AtomicBoolean missingWarned = new AtomicBoolean(false);

    /**
     * 用 ObjectProvider 而不是直接注入：本模块不依赖 system 模块，
     * {@link PermissionCommonApi} 的实现由 system 提供。
     * 单测里只装配本模块的 Bean，那个实现不在容器里 —— 直接注入会让所有单测起不来
     */
    private final ObjectProvider<PermissionCommonApi> permissionApiProvider;

    public FormRowFilter(ObjectProvider<PermissionCommonApi> permissionApiProvider) {
        this.permissionApiProvider = permissionApiProvider;
    }

    /**
     * 取当前用户对某张表的行过滤条件 —— <b>单表</b>查询用这个
     *
     * <p>限定符表在这里就地构好，只放主表一条。⚠️ 这不是省略，是有意义的：
     * 槽位映射可以绑到别的表上（{@code SlotMappingDO.tableName}），
     * 交一张「只有主表」的表进去，绑在别处的槽位就会按<b>求不出来</b>失效（fail-closed）；
     * 而交 null 进去的含义是「调用方不知道有哪些表」，求值器会退回用主表前缀去凑 ——
     * 那可能正好匹配到主表的同名列，生成一条<b>语法合法、语义错误</b>的过滤。
     *
     * @param tableName   物理表名
     * @param columnPrefix 列名前缀，形如 {@code `biz_order`.}；无 JOIN 时传空串
     */
    public DataRowFilterRespDTO resolve(String tableName, String columnPrefix) {
        return resolve(tableName, columnPrefix, StrUtil.isBlank(tableName) ? null
                : Map.of(tableName.toLowerCase(), StrUtil.nullToEmpty(columnPrefix)));
    }

    /**
     * 取当前用户对某张表的行过滤条件 —— <b>带 JOIN</b> 的查询用这个
     *
     * @param tableQualifiers 物理表名（小写）→ 该表在本次查询里合法的限定前缀（含结尾的点）。
     *                        ⚠️ 必须把主表也放进去。关联表带了别名时，值只能是<b>别名</b> ——
     *                        别名一旦生效，物理表名就不再是合法的限定符了
     */
    public DataRowFilterRespDTO resolve(String tableName, String columnPrefix,
                                        Map<String, String> tableQualifiers) {
        if (Boolean.TRUE.equals(IGNORE.get())) {
            return DataRowFilterRespDTO.pass();
        }
        PermissionCommonApi api = permissionApiProvider.getIfAvailable();
        if (api == null) {
            // 真实部署（enlev-server 同时装配 system 与 infra）里不会走到这里。
            // 走到了说明权限模块没起来，此时行级权限是关着的 —— 必须留下痕迹，
            // 否则就又变成一次「静默失效」
            if (missingWarned.compareAndSet(false, true)) {
                log.error("[resolve][PermissionCommonApi 不可用，在线表单的行级权限当前不生效！"
                        + "若非单元测试环境，请检查 enlev-module-system 是否已装配]");
            }
            return DataRowFilterRespDTO.pass();
        }
        try {
            return api.getDataRowFilter(SecurityFrameworkUtils.getLoginUserId(),
                    tableName, columnPrefix, tableQualifiers);
        } catch (Exception e) {
            // 算不出条件就全拒，不能放行 —— 放行等于把异常变成越权
            log.error("[resolve][解析表({})的行过滤条件失败，按全拒处理]", tableName, e);
            return DataRowFilterRespDTO.denyAll();
        }
    }

    /**
     * 在忽略行级权限的上下文里执行
     *
     * 用 try-finally 恢复原值而不是直接置 false：调用可能嵌套，
     * 内层结束时把外层的忽略也清掉的话，外层剩下的语句会突然开始过滤
     */
    public static <V> V executeIgnore(Callable<V> callable) {
        Boolean origin = IGNORE.get();
        IGNORE.set(true);
        try {
            return callable.call();
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            IGNORE.set(origin);
        }
    }

    public static void executeIgnore(Runnable runnable) {
        executeIgnore(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 把条件拼进 WHERE 子句
     *
     * @return true 表示调用方应当直接返回空结果（全拒）
     */
    public boolean append(DataRowFilterRespDTO filter,
                          java.util.List<String> whereClauses,
                          java.util.List<Object> params) {
        if (filter == null || !filter.isFiltered()) {
            return false;
        }
        if (filter.isDenyAll()) {
            // 拼一个恒假条件而不是靠调用方记得检查返回值 ——
            // 漏检一处就是一个越权出口，而恒假条件在任何路径上都是安全的
            whereClauses.add("1 = 0");
            return true;
        }
        if (StrUtil.isNotBlank(filter.getSql())) {
            whereClauses.add(filter.getSql());
            params.addAll(filter.getParams());
        }
        return false;
    }

}
