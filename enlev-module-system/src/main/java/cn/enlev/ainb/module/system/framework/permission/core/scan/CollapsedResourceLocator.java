package cn.enlev.ainb.module.system.framework.permission.core.scan;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「这个权限串该由 {@code sys_resource} 里哪一行承载」的<b>带库查询</b>版口径
 *
 * <h3>为什么要有这一层，而不是让调用方直接用 {@link PermissionStringResolver}</h3>
 *
 * 解析器是纯函数，它算不出「塌缩后的身份串」这一个答案 —— 还差一个<b>库里的事实</b>：
 * 原始串自己是不是还有存活资源行（R11 把 20 个族整族拦下了，那些族的成员行还在，
 * 读路径就仍然按原始串查）。这个事实必须查库，而查库的<b>口径必须与读路径逐字节一致</b>：
 * 读路径的 {@code GrantDecisionService#chainsOf} 用的是 {@code selectListByPermission}
 * （只取 {@code status=0}），这里就必须用同一个过滤条件。
 *
 * 两边不一致的后果是静默的，而且两个方向都错：
 * <ul>
 *   <li>写侧认为「成员行还在」而读侧认为「不在」⟹ 写侧保留成员行、读侧去查身份行
 *       ⟹ 身份行上没有授权 ⟹ <b>权限凭空消失</b></li>
 *   <li>反过来 ⟹ 写侧为原始串新建一行、读侧却查身份行 ⟹ 新行<b>永远没人查</b>，
 *       而它还会顺着资源树把授权继承给自己的兄弟</li>
 * </ul>
 * 把「查库 + 合成答案」收在一个组件里，就是为了这两个方向都写不出来。
 *
 * <h3>谁在用</h3>
 *
 * <ul>
 *   <li>{@code ResourceServiceImpl#syncFromMenu}：菜单增改删时决定「该建哪一行 / 该同步哪一行」</li>
 *   <li>{@code PermissionDriftDetector}：把代码里的权限串换算成身份串再去比漂移、推树</li>
 * </ul>
 *
 * 判定侧（{@code GrantDecisionService#lookupOf}）<b>不</b>走本组件 —— 它有自己的
 * {@code resourceChainCache}，那个缓存同时供祖先链复用；换成这里会多查一次库。
 * 两侧共用的是下面那个纯函数 {@link PermissionStringResolver#identityOf}，
 * 缓存只是各自实现「成员行还在不在」这一次查询的方式不同。
 *
 * @author 走进新时代
 */
@Component
public class CollapsedResourceLocator {

    @Resource
    private PermissionProperties permissionProperties;

    @Resource
    private ResourceMapper resourceMapper;

    private final PermissionStringResolver resolver = new PermissionStringResolver();

    /**
     * {@code enlev.permission.collapse-enabled} 是否打开
     *
     * <p>关闭时本类的每个方法都<b>原样返回入参</b>，调用方不需要再判一次开关 ——
     * 少一处判断就少一处「某个调用方忘了判」的机会
     */
    public boolean collapseEnabled() {
        return Boolean.TRUE.equals(permissionProperties.getCollapseEnabled());
    }

    /**
     * 单个权限串 → 承载它的资源行的 permission
     *
     * @param permission 原始权限串，如 {@code system:user:create}
     * @return 开关关闭、该串不塌缩、或它的成员行还活着时，<b>原样返回入参</b>；
     *         否则返回塌缩后的身份串（如 {@code system:user}）。
     *         空串原样返回 —— 它没有身份可谈，调用方按「没有权限串」的老路走
     */
    public String identityOf(String permission) {
        if (StrUtil.isBlank(permission)) {
            return permission;
        }
        return identityOfAll(List.of(permission)).get(permission);
    }

    /**
     * 批量版：一批权限串 → 各自的承载串
     *
     * <p>存在批量版不是为了好看，是因为 {@code PermissionDriftDetector} 一次要换算
     * 两百多个串（代码注解扫出来的全部权限串）。逐个调 {@link #identityOf}
     * 就是两百多次 round-trip，而它跑在应用启动的关键路径上。
     *
     * <p>⚠️ 返回的 Map 用 {@link LinkedHashMap} 保序：调用方要拿它去建资源树，
     * 顺序随 hash 漂移会让「同一份代码跑两次建出不同的树」，而树的形状决定了
     * 授权继承的范围 —— 那不是性能问题，是结果不稳定
     */
    public Map<String, String> identityOfAll(Collection<String> permissions) {
        Map<String, String> out = new LinkedHashMap<>();
        if (CollUtil.isEmpty(permissions)) {
            return out;
        }
        if (!collapseEnabled()) {
            for (String permission : permissions) {
                out.put(permission, permission);
            }
            return out;
        }
        // 🔴 判据与 GrantDecisionService#chainsOf 同源：只认 status=0 的存活行。
        // selectListByPermissions 内部就是这个过滤条件，别换成 selectByPermissionIncludeDisabled ——
        // 后者连停用的行也算「还在」，会让一个被停用的成员行把整族钉在塌缩前的形状上，
        // 而读路径（chainsOf）看不到它、照样去查身份行
        Set<String> memberRowAlive = resourceMapper.selectListByPermissions(permissions).stream()
                .map(ResourceDO::getPermission)
                .collect(Collectors.toSet());
        for (String permission : permissions) {
            out.put(permission, resolver.identityOf(permission, memberRowAlive.contains(permission)));
        }
        return out;
    }

}
