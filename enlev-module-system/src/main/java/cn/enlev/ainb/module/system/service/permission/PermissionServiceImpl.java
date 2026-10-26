package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.util.collection.CollectionUtils;
import cn.enlev.ainb.framework.datapermission.core.annotation.DataPermission;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DeptDataPermissionRespDTO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleMenuDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.UserRoleDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.MenuMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.RoleMenuMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.UserRoleMapper;
import cn.enlev.ainb.module.system.dal.redis.RedisKeyConstants;
import cn.enlev.ainb.module.system.enums.permission.DataScopeEnum;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.framework.permission.core.scan.PermissionStringResolver;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import cn.enlev.ainb.module.system.service.user.AdminUserService;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.function.Supplier;

import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.enlev.ainb.framework.common.util.json.JsonUtils.toJsonString;

/**
 * 权限 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Slf4j
public class PermissionServiceImpl implements PermissionService {

    @Resource
    private RoleMenuMapper roleMenuMapper;
    @Resource
    private UserRoleMapper userRoleMapper;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private GrantMapper grantMapper;
    @Resource
    private MenuMapper menuMapper;

    @Resource
    private RoleService roleService;
    @Resource
    private MenuService menuService;
    @Resource
    private DeptService deptService;
    @Resource
    private AdminUserService userService;
    @Resource
    private GrantDecisionService grantDecisionService;
    @Resource
    private cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties permissionProperties;

    /**
     * 套餐换算（R5）用：把菜单的权限串拆成「资源身份 + 动作」
     *
     * <p>与 {@code GrantDecisionService} 里那个是同一无状态对象的两个实例。
     * 刻意不抽成 Bean：它的动词映射表是 {@code static final}，
     * 而做成 Bean 只会让「两侧用的是不是同一份表」这件事从「读代码就知道」
     * 变成「得看装配」—— 那正是本次改造最怕的分叉点
     */
    private final PermissionStringResolver resolver = new PermissionStringResolver();

    private boolean collapseEnabled() {
        return Boolean.TRUE.equals(permissionProperties.getCollapseEnabled());
    }

    @Override
    public boolean hasAnyPermissions(Long userId, String... permissions) {
        // 如果为空，说明已经有权限
        if (ArrayUtil.isEmpty(permissions)) {
            return true;
        }

        // 情况一：超管直接放行（新旧路径都保留这一条）
        List<RoleDO> roles = getEnableUserRoleListByUserIdFromCache(userId);
        if (roleService.hasAnySuperAdmin(convertSet(roles, RoleDO::getId))) {
            return true;
        }

        // 情况二：按开关决定走新引擎还是旧路径
        // enlev.permission.grant-enabled = false（默认）→ 走旧路径，保证现有环境不受影响
        // enlev.permission.grant-enabled = true → 走新决策引擎
        // 迁移脚本跑完、验证权限点都正常后，改为 true 才真正走新路径
        if (Boolean.TRUE.equals(permissionProperties.getGrantEnabled())) {
            for (String permission : permissions) {
                if (grantDecisionService.hasPermission(userId, permission, null)) {
                    return true;
                }
            }
            return false;
        }

        // 旧路径：system_role_menu
        for (String permission : permissions) {
            if (hasAnyPermissionLegacy(roles, permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 旧路径：通过 system_role_menu 判断是否有权限
     *
     * S2a 起被 GrantDecisionService 取代。当 enlev.permission.grant-enabled = false 时
     * 仍走此路径，保证现有环境不受影响。迁移验证通过后可以删除
     */
    private boolean hasAnyPermissionLegacy(List<RoleDO> roles, String permission) {
        List<Long> menuIds = menuService.getMenuIdListByPermissionFromCache(permission);
        // 采用严格模式，如果权限找不到对应的 Menu 的话，也认为没有权限
        if (CollUtil.isEmpty(menuIds)) {
            return false;
        }

        // 判断是否有权限
        Set<Long> roleIds = convertSet(roles, RoleDO::getId);
        for (Long menuId : menuIds) {
            // 获得拥有该菜单的角色编号集合
            Set<Long> menuRoleIds = getSelf().getMenuRoleIdListByMenuIdFromCache(menuId);
            // 如果有交集，说明有权限
            if (CollUtil.containsAny(menuRoleIds, roleIds)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasAnyRoles(Long userId, String... roles) {
        // 如果为空，说明已经有权限
        if (ArrayUtil.isEmpty(roles)) {
            return true;
        }

        // 获得当前登录的角色。如果为空，说明没有权限
        List<RoleDO> roleList = getEnableUserRoleListByUserIdFromCache(userId);
        if (CollUtil.isEmpty(roleList)) {
            return false;
        }

        // 判断是否有角色
        Set<String> userRoles = convertSet(roleList, RoleDO::getCode);
        return CollUtil.containsAny(userRoles, Sets.newHashSet(roles));
    }

    // ========== 角色-菜单的相关方法  ==========

    @Override
    @DSTransactional // 多数据源，使用 @DSTransactional 保证本地事务，以及数据源的切换
    @Caching(evict = {
            @CacheEvict(value = RedisKeyConstants.MENU_ROLE_ID_LIST,
            allEntries = true),
            @CacheEvict(value = RedisKeyConstants.PERMISSION_MENU_ID_LIST,
            allEntries = true) // allEntries 清空所有缓存，主要一次更新涉及到的 menuIds 较多，反倒批量会更快
    })
    public void assignRoleMenu(Long roleId, Set<Long> menuIds) {
        // 获得角色拥有菜单编号
        Set<Long> dbMenuIds = convertSet(roleMenuMapper.selectListByRoleId(roleId), RoleMenuDO::getMenuId);
        // 计算新增和删除的菜单编号
        Set<Long> menuIdList = CollUtil.emptyIfNull(menuIds);
        Collection<Long> createMenuIds = CollUtil.subtract(menuIdList, dbMenuIds);
        Collection<Long> deleteMenuIds = CollUtil.subtract(dbMenuIds, menuIdList);
        // 执行新增和删除。对于已经授权的菜单，不用做任何处理
        if (CollUtil.isNotEmpty(createMenuIds)) {
            roleMenuMapper.insertBatch(CollectionUtils.convertList(createMenuIds, menuId -> {
                RoleMenuDO entity = new RoleMenuDO();
                entity.setRoleId(roleId);
                entity.setMenuId(menuId);
                return entity;
            }));
        }
        if (CollUtil.isNotEmpty(deleteMenuIds)) {
            roleMenuMapper.deleteListByRoleIdAndMenuIds(roleId, deleteMenuIds);
        }
    }

    @Override
    @DSTransactional
    @Caching(evict = {
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_PERMISSIONS + "#30s", allEntries = true),
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_MENU_IDS + "#30s", allEntries = true)
    })
    public void assignRoleMenuGrants(Long roleId, Set<Long> menuIds) {
        syncRoleMenuGrants(roleId, menuIds, false);
    }

    @Override
    @DSTransactional
    @Caching(evict = {
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_PERMISSIONS + "#30s", allEntries = true),
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_MENU_IDS + "#30s", allEntries = true)
    })
    public void retainRoleMenuGrants(Long roleId, Set<Long> menuIds) {
        syncRoleMenuGrants(roleId, menuIds, true);
    }

    /**
     * {@link #assignRoleMenuGrants} 与 {@link #retainRoleMenuGrants} 的共同实现
     *
     * ⚠️ 两个入口必须共用这一份：各写一份必然漂移，而这里漂移的表现是
     * 「租户管理员的权限对了、其他角色的没对」这种极难反查的状态
     *
     * @param retainOnly true 表示只删不加（收窄语义）
     */
    private void syncRoleMenuGrants(Long roleId, Set<Long> menuIds, boolean retainOnly) {
        boolean collapse = collapseEnabled();

        // 1. 菜单 → (资源, 动作)。目录没有资源行，在这里自然落空，这是预期的（见接口注释）
        Set<GrantTarget> targets = collapse ? collapseTargets(menuIds) : legacyTargets(menuIds);

        // 2. 该角色现有的套餐型授权行（ALLOW + 无上下文），且必须由菜单派生
        //
        // 🔴 动作过滤跟着开关走。关闭态仍是 VIEW，与改造前逐字节一致；
        //    打开态必须传 null（不限动作）—— R5 之后同一个资源上会同时存在
        //    VIEW / CREATE / UPDATE … 多行套餐授权，只查 VIEW 会让第 3 步看不见其余的行，
        //    于是「套餐里取消勾选某个按钮」在 VIEW 上收得回、在具体动作上永远收不回，且不报错
        List<GrantDO> existing = filterMenuDerived(grantMapper.selectListPackageStyleByRole(
                roleId, collapse ? null : GrantActionEnum.VIEW.getCode()));

        // 3. 删：超出目标集合的
        //
        // ⚠️ 这一步是整件事的重点。旧的 assignRoleMenu 只写 system_role_menu，
        // 而通往 sys_grant 的唯一通路（GrantMigrateService）是 insert-only 的 ——
        // 于是「套餐降级」在旧表上算得好好的，在真正参与判定的 sys_grant 上一行都没少
        //
        // 🔴 比较粒度是 (resource_id, action) 而不是 resource_id。塌缩后一个资源上有多行
        //    不同动作的套餐授权，按 resource_id 比会得出「这个资源还在目标里」，
        //    于是取消勾选的那一个动作被留下 —— 权限收不回，同样不报错
        Set<GrantTarget> existingTargets = new HashSet<>();
        List<Long> toDeleteIds = new ArrayList<>();
        for (GrantDO grant : existing) {
            GrantTarget target = new GrantTarget(grant.getResourceId(), grant.getAction());
            existingTargets.add(target);
            if (!targets.contains(target)) {
                toDeleteIds.add(grant.getId());
            }
        }
        if (CollUtil.isNotEmpty(toDeleteIds)) {
            grantMapper.deleteByIds(toDeleteIds);
        }

        // 4. 补：目标集合里还没有的。收窄语义下跳过
        int created = 0;
        if (!retainOnly) {
            List<GrantDO> toInsert = new ArrayList<>();
            for (GrantTarget target : targets) {
                if (existingTargets.contains(target)) {
                    continue;
                }
                GrantDO grant = new GrantDO();
                grant.setSubjectType(SubjectTypeEnum.ROLE.name());
                grant.setSubjectRefId(roleId);
                grant.setResourceId(target.resourceId());
                grant.setAction(target.action());
                grant.setEffect("ALLOW");
                toInsert.add(grant);
            }
            // tenant_id 由租户插件按当前上下文自动填（GrantDO extends TenantBaseDO），
            // 调用方 TenantServiceImpl 已用 TenantUtils.execute 包住
            if (CollUtil.isNotEmpty(toInsert)) {
                grantMapper.insertBatch(toInsert);
                created = toInsert.size();
            }
        }
        log.info("[syncRoleMenuGrants][角色({}) retainOnly={} 目标授权 {} 个，新建 {} 条，删除 {} 条]",
                roleId, retainOnly, targets.size(), created, toDeleteIds.size());
    }

    /**
     * 套餐授权的目标：一个资源上的一个动作
     *
     * <p>用 record 而不是 {@code Set<Long>} 是 R5 的落点：塌缩之后「一个资源」不再等于
     * 「一个权限点」，{@code system:user} 上的 VIEW 与 CREATE 是两条独立的授权，
     * 必须能被分别对齐、分别删除。写成 record 也让「只比 resource_id」这种漏改法
     * 在编译期就没有对应的重载可用
     */
    private record GrantTarget(Long resourceId, String action) {
    }

    /**
     * 关闭态（默认）：菜单 → 资源行，动作一律 VIEW
     *
     * <p>这是塌缩改造之前的原样口径，保留它是为了「开关关着时行为逐字节不变」
     */
    private Set<GrantTarget> legacyTargets(Set<Long> menuIds) {
        if (CollUtil.isEmpty(menuIds)) {
            return new HashSet<>();
        }
        Set<GrantTarget> targets = new HashSet<>();
        for (ResourceDO resource : resourceMapper.selectListByMenuIds(menuIds)) {
            targets.add(new GrantTarget(resource.getId(), GrantActionEnum.VIEW.getCode()));
        }
        return targets;
    }

    /**
     * 打开态：菜单 → (资源身份, 动作)（规则 R5）
     *
     * <h3>🔴 为什么必须走权限串，不能继续走 {@code menu_id}</h3>
     *
     * 塌缩把若干兄弟资源行合并成一个身份行，而一个身份行<b>只能挂一个 menu_id</b>
     * （{@code ResourceCollapsePlanner#chooseMenuId} 挑出来的那一个）。
     * 于是「菜单集合 → {@code selectListByMenuIds} → 资源集合」这条换算断了：
     * 实测 107 个身份的 menu_id 扇入 &gt; 1，其中 401 个菜单是按钮。
     *
     * <p>具体后果：套餐里勾了「API 密钥创建」但没勾「API 密钥查询」的角色，
     * 塌缩后 {@code selectListByMenuIds} 查不到那个身份行 ⟹ <b>该角色凭空丢掉 create 权限</b>。
     * 实测核心遗留族 168 个 (角色, 身份) 组合里有 55 个（32.7%）是这种部分勾选，
     * 且四个租户（1 / 121 / 122 / 162）全都有。
     *
     * <p>走权限串就没有这个问题：勾了哪个按钮，就解析出那个按钮对应的动作。
     * 顺带这也是 {@code sys_grant.action} 第一次真正有内容 ——
     * 改造前 1802 条活授权里 VIEW 占 1771（98.3%），CREATE/UPDATE/DELETE/EXPORT/IMPORT 一条都没有。
     *
     * <h3>两类菜单走不了权限串，必须回落 {@code menu_id}</h3>
     *
     * <ol>
     *   <li><b>空权限串的菜单</b>（实测 119 行资源、160 条活授权、3 个角色）。
     *       规则 R3 明确不合并、不铸串，它们的授权原样留在自己那行上。
     *       🔴 少了这个回落，下一次套餐重算会把这 160 条授权当成「超出目标集合」全删掉 ——
     *       表现是三个角色的菜单可见性整片消失，而且不报错</li>
     *   <li><b>解析出的身份在库里找不到启用行</b>。塌缩身份在迁移后一定存在
     *       （锚点或新建行都是启用的），但<b>停用</b>的遗留行按 R4 不参与塌缩，
     *       于是它对应的身份可能压根没被建出来。实测 3 行属于这种
     *       （{@code infra:build:list} / {@code infra:codegen:query} / {@code infra:swagger:list}，
     *       共 12 条活授权，四个租户的套餐角色各一条）。
     *       回落之后目标集合与关闭态一致，那些授权不会被误删</li>
     * </ol>
     *
     * <p>回落用的 {@code selectListByMenuIds} 不过滤 status，与关闭态同一个查询 ——
     * 这是有意的：它保证「开关打开不会多删任何一条关闭态会留下的授权」。
     */
    private Set<GrantTarget> collapseTargets(Set<Long> menuIds) {
        if (CollUtil.isEmpty(menuIds)) {
            return new HashSet<>();
        }

        // 1. 菜单 → 身份 → 动作集合。同一个身份会被多个菜单命中（查询/新增/删除…），
        //    所以要按身份聚合动作，而不是一个菜单一条目标
        Map<String, Set<String>> actionsByIdentity = new LinkedHashMap<>();
        Set<Long> fallbackMenuIds = new LinkedHashSet<>();
        Map<String, Long> firstMenuOfIdentity = new HashMap<>();
        for (MenuDO menu : menuMapper.selectByIds(menuIds)) {
            if (StrUtil.isBlank(menu.getPermission())) {
                fallbackMenuIds.add(menu.getId());
                continue;
            }
            PermissionStringResolver.Resolution resolution = resolver.resolve(menu.getPermission());
            // 档 C（业务专有动词）/ 档 D（两段式名词）/ 模块根 的 resourceKey 就是整串、
            // action 恒为 VIEW，所以这里不需要分档：不塌缩的档走的是同一行代码，
            // 得到的目标与关闭态一致
            String identity = resolution.getResourceKey();
            actionsByIdentity.computeIfAbsent(identity, k -> new LinkedHashSet<>())
                    .add(resolution.getAction().getCode());
            firstMenuOfIdentity.putIfAbsent(identity, menu.getId());
        }

        // 2. 身份 → 资源行 id
        //
        // ⚠️ 同串多行时取【启用行里 id 最小的】，与 ResourceCollapsePlanner 建锚点的口径
        //    必须一致：不一致的话，迁移把授权写在 A 行、套餐重算对齐到 B 行，
        //    两行都活着，于是授权页出现「配了却删不掉」的重复行。
        //    实测今天只有 4 个权限串有重复行，且都不含停用行，但口径不能靠数据侥幸
        Map<String, Long> idOfIdentity = new HashMap<>();
        for (ResourceDO resource : resourceMapper.selectListByPermissions(actionsByIdentity.keySet())) {
            String permission = resource.getPermission();
            if (!actionsByIdentity.containsKey(permission)) {
                // SQL 的 IN 按列排序规则（*_ai_ci）比较，是忽略大小写与重音的；
                // 而这里认回身份用的是 Java 精确匹配。真出现「同串不同大小写」时，
                // 这一行认不回任何身份 —— 与其把它错配到别的身份上（那会写到别人的资源行），
                // 不如喊出来：它会退化成下面的 menu_id 回落，而那至少与关闭态口径一致
                log.warn("[collapseTargets][资源({}) 的权限串({}) 与请求的身份大小写不一致，已跳过]",
                        resource.getId(), permission);
                continue;
            }
            idOfIdentity.merge(permission, resource.getId(), (a, b) -> a < b ? a : b);
        }

        // 3. 组装目标；身份找不到启用行的，把对应菜单记进回落集合
        Set<GrantTarget> targets = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : actionsByIdentity.entrySet()) {
            Long resourceId = idOfIdentity.get(entry.getKey());
            if (resourceId == null) {
                fallbackMenuIds.add(firstMenuOfIdentity.get(entry.getKey()));
                continue;
            }
            for (String action : entry.getValue()) {
                targets.add(new GrantTarget(resourceId, action));
            }
        }

        // 4. 回落：空权限菜单 + 身份无启用行的菜单，沿用关闭态的 menu_id 口径
        if (CollUtil.isNotEmpty(fallbackMenuIds)) {
            for (ResourceDO resource : resourceMapper.selectListByMenuIds(fallbackMenuIds)) {
                targets.add(new GrantTarget(resource.getId(), GrantActionEnum.VIEW.getCode()));
            }
        }
        return targets;
    }

    /**
     * 只留下「由菜单派生的资源」上的授权行
     *
     * <h3>🔴 少了这一步会静默删掉行级权限</h3>
     *
     * {@code selectListPackageStyleByRole} 的判据是 ALLOW + 无上下文（动作按塌缩开关决定
     * 过不过滤），而 {@code DataScopeMigrateService} 迁出来的<b>行级数据集授权</b>
     * （部门表/用户表上的「全部数据」「本部门数据」）恰好也是这个形状 ——
     * 它压根不是套餐的一部分，却会被第 3 步当成「超出目标集合」删掉。
     *
     * 实测事故：一次在线表单发布（{@code FormPublishBundleTool} →
     * {@code MenuApi#assignRoleMenus}）触发套餐重算，把超级管理员角色在
     * {@code system:dept} / {@code system:user} 上的「全部数据」授权删了，
     * 于是超管只剩它身上那个普通角色的「本部门数据」规则生效 ——
     * 组织管理只显示 1 个部门、用户管理只显示 1 个用户，<b>且不报任何错</b>。
     *
     * 反方向更糟：普通角色唯一的行级授权被删后，{@code DataRowFilterResolver}
     * 查不到任何命中授权 → 返回 {@code pass()} → <b>整表不过滤</b>，权限凭空放大。
     *
     * <h3>为什么判据是 {@code sys_resource.menu_id}</h3>
     *
     * 套餐重算的输入就是菜单集合，它能对齐的只有菜单派生出来的资源
     * （关闭态下第 4 步的新建也只来自 {@code selectListByMenuIds}）。
     * DATASET / COLUMN / FIELD / PROCESS / CATEGORY 的 menu_id 恒为 NULL，
     * 既不可能由本方法新建，也就不该由本方法删除。
     *
     * <h3>🔴 塌缩打开后，这个判据依赖 R1 把身份行的 menu_id 填上</h3>
     *
     * 打开态下第 4 步新建的授权落在<b>身份行</b>上（{@code system:user}），
     * 而身份行是 {@code PermissionTreeDeriver} 建出来的中间节点 ——
     * 实测 84 个这样的锚点行 <b>menu_id 全是 NULL</b>。
     * 若迁移不给它们挑一个 menu_id（{@code ResourceCollapsePlanner#chooseMenuId}），
     * 本方法就不认它们是套餐派生的：
     * <ul>
     *   <li>第 3 步会跳过它们 ⟹ <b>套餐降级收不回权限，且不报错</b></li>
     *   <li>第 4 步照样新建 ⟹ 下一次重算还是收不回，行越攒越多</li>
     * </ul>
     * 所以「R1 必须挑出 menu_id」与「本方法的判据是 menu_id」是一对，改任何一侧都要看另一侧。
     */
    private List<GrantDO> filterMenuDerived(List<GrantDO> grants) {
        if (CollUtil.isEmpty(grants)) {
            return grants;
        }
        Set<Long> menuDerivedResourceIds = new HashSet<>();
        for (ResourceDO resource : resourceMapper.selectByIds(convertSet(grants, GrantDO::getResourceId))) {
            if (resource.getMenuId() != null) {
                menuDerivedResourceIds.add(resource.getId());
            }
        }
        List<GrantDO> result = new ArrayList<>(grants.size());
        for (GrantDO grant : grants) {
            if (menuDerivedResourceIds.contains(grant.getResourceId())) {
                result.add(grant);
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Caching(evict = {
            @CacheEvict(value = RedisKeyConstants.MENU_ROLE_ID_LIST,
                    allEntries = true), // allEntries 清空所有缓存，此处无法方便获得 roleId 对应的 menu 缓存们
            @CacheEvict(value = RedisKeyConstants.USER_ROLE_ID_LIST,
                    allEntries = true) // allEntries 清空所有缓存，此处无法方便获得 roleId 对应的 user 缓存们
    })
    public void processRoleDeleted(Long roleId) {
        // 标记删除 UserRole
        userRoleMapper.deleteListByRoleId(roleId);
        // 标记删除 RoleMenu
        roleMenuMapper.deleteListByRoleId(roleId);
    }

    @Override
    @CacheEvict(value = RedisKeyConstants.MENU_ROLE_ID_LIST, key = "#menuId")
    public void processMenuDeleted(Long menuId) {
        roleMenuMapper.deleteListByMenuId(menuId);
    }

    @Override
    public Set<Long> getRoleMenuListByRoleId(Collection<Long> roleIds) {
        if (CollUtil.isEmpty(roleIds)) {
            return Collections.emptySet();
        }

        // 如果是管理员的情况下，获取全部菜单编号
        if (roleService.hasAnySuperAdmin(roleIds)) {
            return convertSet(menuService.getMenuList(), MenuDO::getId);
        }
        // 如果是非管理员的情况下，获得拥有的菜单编号
        return convertSet(roleMenuMapper.selectListByRoleId(roleIds), RoleMenuDO::getMenuId);
    }

    @Override
    @Cacheable(value = RedisKeyConstants.MENU_ROLE_ID_LIST, key = "#menuId")
    public Set<Long> getMenuRoleIdListByMenuIdFromCache(Long menuId) {
        return convertSet(roleMenuMapper.selectListByMenuId(menuId), RoleMenuDO::getRoleId);
    }

    // ========== 用户-角色的相关方法  ==========

    @Override
    @DSTransactional // 多数据源，使用 @DSTransactional 保证本地事务，以及数据源的切换
    @CacheEvict(value = RedisKeyConstants.USER_ROLE_ID_LIST, key = "#userId")
    public void assignUserRole(Long userId, Set<Long> roleIds) {
        // 获得角色拥有角色编号
        Set<Long> dbRoleIds = convertSet(userRoleMapper.selectListByUserId(userId),
                UserRoleDO::getRoleId);
        // 计算新增和删除的角色编号
        Set<Long> roleIdList = CollUtil.emptyIfNull(roleIds);
        Collection<Long> createRoleIds = CollUtil.subtract(roleIdList, dbRoleIds);
        Collection<Long> deleteMenuIds = CollUtil.subtract(dbRoleIds, roleIdList);
        // 执行新增和删除。对于已经授权的角色，不用做任何处理
        if (!CollectionUtil.isEmpty(createRoleIds)) {
            userRoleMapper.insertBatch(CollectionUtils.convertList(createRoleIds, roleId -> {
                UserRoleDO entity = new UserRoleDO();
                entity.setUserId(userId);
                entity.setRoleId(roleId);
                return entity;
            }));
        }
        if (!CollectionUtil.isEmpty(deleteMenuIds)) {
            userRoleMapper.deleteListByUserIdAndRoleIdIds(userId, deleteMenuIds);
        }
    }

    @Override
    @CacheEvict(value = RedisKeyConstants.USER_ROLE_ID_LIST, key = "#userId")
    public void processUserDeleted(Long userId) {
        userRoleMapper.deleteListByUserId(userId);
    }

    @Override
    public Set<Long> getUserRoleIdListByUserId(Long userId) {
        return convertSet(userRoleMapper.selectListByUserId(userId), UserRoleDO::getRoleId);
    }

    @Override
    @Cacheable(value = RedisKeyConstants.USER_ROLE_ID_LIST, key = "#userId")
    public Set<Long> getUserRoleIdListByUserIdFromCache(Long userId) {
        return getUserRoleIdListByUserId(userId);
    }

    @Override
    public Set<Long> getUserRoleIdListByRoleId(Collection<Long> roleIds) {
        return convertSet(userRoleMapper.selectListByRoleIds(roleIds), UserRoleDO::getUserId);
    }

    /**
     * 获得用户拥有的角色，并且这些角色是开启状态的
     *
     * @param userId 用户编号
     * @return 用户拥有的角色
     */
    @VisibleForTesting
    List<RoleDO> getEnableUserRoleListByUserIdFromCache(Long userId) {
        // 获得用户拥有的角色编号
        Set<Long> roleIds = getSelf().getUserRoleIdListByUserIdFromCache(userId);
        // 获得角色数组，并移除被禁用的
        List<RoleDO> roles = roleService.getRoleListFromCache(roleIds);
        roles.removeIf(role -> !CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus()));
        return roles;
    }

    // ========== 用户-部门的相关方法  ==========

    @Override
    public void assignRoleDataScope(Long roleId, Integer dataScope, Set<Long> dataScopeDeptIds) {
        roleService.updateRoleDataScope(roleId, dataScope, dataScopeDeptIds);
    }

    @Override
    @DataPermission(enable = false) // 关闭数据权限，不然就会出现递归获取数据权限的问题
    public DeptDataPermissionRespDTO getDeptDataPermission(Long userId) {
        // 获得用户的角色
        List<RoleDO> roles = getEnableUserRoleListByUserIdFromCache(userId);

        // 如果角色为空，则只能查看自己
        DeptDataPermissionRespDTO result = new DeptDataPermissionRespDTO();
        if (CollUtil.isEmpty(roles)) {
            result.setSelf(true);
            return result;
        }

        // 获得用户的部门编号的缓存，通过 Guava 的 Suppliers 惰性求值，即有且仅有第一次发起 DB 的查询
        Supplier<Long> userDeptId = Suppliers.memoize(() -> userService.getUser(userId).getDeptId());
        // 遍历每个角色，计算
        for (RoleDO role : roles) {
            // 为空时，跳过
            if (role.getDataScope() == null) {
                continue;
            }
            // 情况一，ALL
            if (Objects.equals(role.getDataScope(), DataScopeEnum.ALL.getScope())) {
                result.setAll(true);
                continue;
            }
            // 情况二，DEPT_CUSTOM
            if (Objects.equals(role.getDataScope(), DataScopeEnum.DEPT_CUSTOM.getScope())) {
                CollUtil.addAll(result.getDeptIds(), role.getDataScopeDeptIds());
                // 自定义可见部门时，保证可以看到自己所在的部门。否则，一些场景下可能会有问题。
                // 例如说，登录时，基于 t_user 的 username 查询会可能被 dept_id 过滤掉
                CollectionUtils.addIfNotNull(result.getDeptIds(), userDeptId.get());
                continue;
            }
            // 情况三，DEPT_ONLY
            if (Objects.equals(role.getDataScope(), DataScopeEnum.DEPT_ONLY.getScope())) {
                CollectionUtils.addIfNotNull(result.getDeptIds(), userDeptId.get());
                continue;
            }
            // 情况四，DEPT_DEPT_AND_CHILD
            if (Objects.equals(role.getDataScope(), DataScopeEnum.DEPT_AND_CHILD.getScope())) {
                Long deptId = userDeptId.get();
                // 用户未设置部门，直接跳过；否则 getChildDeptIdListFromCache 走缓存注解会因 null key 报错
                if (deptId == null) {
                    continue;
                }
                CollUtil.addAll(result.getDeptIds(), deptService.getChildDeptIdListFromCache(deptId));
                // 添加本身部门编号
                result.getDeptIds().add(deptId);
                continue;
            }
            // 情况五，SELF
            if (Objects.equals(role.getDataScope(), DataScopeEnum.SELF.getScope())) {
                result.setSelf(true);
                continue;
            }
            // 未知情况，error log 即可
            log.error("[getDeptDataPermission][LoginUser({}) role({}) 无法处理]", userId, toJsonString(result));
        }
        return result;
    }

    /**
     * 获得自身的代理对象，解决 AOP 生效问题
     *
     * @return 自己
     */
    private PermissionServiceImpl getSelf() {
        return SpringUtil.getBean(getClass());
    }

}
