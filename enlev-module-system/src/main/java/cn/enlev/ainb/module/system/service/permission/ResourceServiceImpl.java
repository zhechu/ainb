package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.MenuMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.redis.RedisKeyConstants;
import cn.enlev.ainb.module.system.enums.permission.MenuTypeEnum;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.framework.permission.core.scan.CollapsedResourceLocator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.*;

/**
 * 权限资源 Service 实现类
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class ResourceServiceImpl implements ResourceService {

    /**
     * 无权限标识的菜单，物化路径里用的 ident 前缀
     *
     * ⚠️ 不能沿用「没有 permission 就用 id」那套：{@link #fillPathAndDepth} 是在 insert
     * <b>之前</b>调用的，那时 {@code resource.getId()} 还是 null，于是所有无权限串的资源
     * 会<b>全部退化成同一个 {@code _tmp_} 路径</b> —— 物化路径撞车，祖先链上溯直接乱套。
     * menuId 在建行前就已经有值，拿它做 ident 才是稳定的。
     *
     * 全库已确认没有以 {@code menu:} 开头的权限串，不会和真实 permission 撞
     */
    private static final String MENU_IDENT_PREFIX = "menu:";

    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private MenuMapper menuMapper;
    /**
     * 「这个权限串该由哪一行资源承载」的唯一口径。
     * 本类的每个写分支都必须走它，判据与判定侧（{@code GrantDecisionService#lookupOf}）同源
     */
    @Resource
    private CollapsedResourceLocator collapsedLocator;
    /**
     * 只为在资源树变化时清它那份进程内祖先链缓存，见 {@link #evictChainCache()}
     */
    @Resource
    private GrantDecisionService grantDecisionService;

    /**
     * 🔴 资源的写操作必须同时驱逐这四个缓存，本类 5 个写方法（create / update /
     * delete / delete-list / syncFromMenu）一个都不能少
     *
     * <ul>
     *   <li>{@code PERMISSION_MENU_ID_LIST}：权限串 → 菜单 id 的反查表，
     *       资源的 permission 改了它就必须失效</li>
     *   <li>{@code grant_user_permissions#30s} / {@code grant_user_menu_ids#30s}：
     *       按用户缓存的「可见权限串 / 可见菜单」集合（{@code GrantDecisionService}）。
     *       授权行引用的是资源，资源的 permission 串一改、或资源一删，
     *       缓存里那份旧串就成了<b>幽灵权限</b>：界面上资源已经没了，
     *       判定侧在 TTL 内照旧放行，且不报任何错</li>
     *   <li>{@code GrantDecisionService#resourceChainCache}：权限串 → 资源祖先链，
     *       <b>进程内、无 TTL</b>，只靠 {@link #evictChainCache()} 手工清。前三个有 30s
     *       TTL 兜底、漏驱最多脏半分钟；这个漏驱就是<b>永久</b>脏 —— 实测过：BPM 部署钩子
     *       在 registerProcess 建资源之前若有人按新 key 查过一次（空链入缓存），
     *       该流程 afterwards 对所有人「谁都发不了」，重启后端才恢复</li>
     * </ul>
     *
     * 只驱逐第一个的表现是「改完权限串要等半分钟才生效」—— 正好卡在
     * 人工验证「改完立刻刷新看效果」的时间窗之外，极易被误判成「没改成功」。
     */
    private void evictChainCache() {
        grantDecisionService.evictResourceChainCache();
    }
    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = RedisKeyConstants.PERMISSION_MENU_ID_LIST, allEntries = true),
            @CacheEvict(cacheNames = RedisKeyConstants.GRANT_USER_PERMISSIONS + "#30s", allEntries = true),
            @CacheEvict(cacheNames = RedisKeyConstants.GRANT_USER_MENU_IDS + "#30s", allEntries = true)
    })
    public Long createResource(ResourceDO resource) {
        // 计算物化路径
        fillPathAndDepth(resource);
        resourceMapper.insert(resource);
        evictChainCache();
        return resource.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Caching(evict = {
            @CacheEvict(cacheNames = RedisKeyConstants.PERMISSION_MENU_ID_LIST, allEntries = true),
            @CacheEvict(cacheNames = RedisKeyConstants.GRANT_USER_PERMISSIONS + "#30s", allEntries = true),
            @CacheEvict(cacheNames = RedisKeyConstants.GRANT_USER_MENU_IDS + "#30s", allEntries = true)
    })
    public void updateResource(ResourceDO resource) {
        ResourceDO existing = resourceMapper.selectById(resource.getId());
        if (existing == null) {
            throw exception(MENU_NOT_EXISTS); // 暂用菜单错误码，S2c 有专属资源错误码时换掉
        }
        // 若 parentId 或 permission（用于 path 的 ident 部分）有变，级联更新所有后代
        boolean pathChanged = !existing.getParentId().equals(resource.getParentId())
                || !StrUtil.equals(existing.getPermission(), resource.getPermission());
        if (pathChanged) {
            fillPathAndDepth(resource);
            // 把旧 path 作为前缀，批量更新后代
            cascadeUpdatePath(existing.getPath(), resource.getPath());
        }
        resourceMapper.updateById(resource);
        evictChainCache();
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = RedisKeyConstants.PERMISSION_MENU_ID_LIST, allEntries = true),
            @CacheEvict(cacheNames = RedisKeyConstants.GRANT_USER_PERMISSIONS + "#30s", allEntries = true),
            @CacheEvict(cacheNames = RedisKeyConstants.GRANT_USER_MENU_IDS + "#30s", allEntries = true)
    })
    public void deleteResource(Long id) {
        Long children = resourceMapper.selectCountByParentId(id);
        if (children != null && children > 0) {
            throw exception(RESOURCE_HAS_CHILDREN, children);
        }
        resourceMapper.deleteById(id);
        evictChainCache();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Caching(evict = {
            @CacheEvict(cacheNames = RedisKeyConstants.PERMISSION_MENU_ID_LIST, allEntries = true),
            @CacheEvict(cacheNames = RedisKeyConstants.GRANT_USER_PERMISSIONS + "#30s", allEntries = true),
            @CacheEvict(cacheNames = RedisKeyConstants.GRANT_USER_MENU_IDS + "#30s", allEntries = true)
    })
    public void deleteResourceList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 全有或全无：任一资源的子节点没被一起勾选，删完就会在树里留下孤儿
        // （父没了子还在，子的物化路径 path 指向一个不存在的祖先），因此整批拒绝。
        // 父与子都在这批里 = 整棵子树一起删，放行。orphans 统计「没被一起勾选」的子节点数，
        // 直接喂给错误文案，告诉用户还差几个。
        Set<Long> idSet = new HashSet<>(ids);
        long orphans = 0;
        for (Long id : ids) {
            for (ResourceDO child : resourceMapper.selectListByParentId(id)) {
                if (!idSet.contains(child.getId())) {
                    orphans++;
                }
            }
        }
        if (orphans > 0) {
            throw exception(RESOURCE_HAS_CHILDREN, orphans);
        }
        resourceMapper.deleteByIds(ids);
        evictChainCache();
    }

    @Override
    public ResourceDO getResource(Long id) {
        return resourceMapper.selectById(id);
    }

    @Override
    public List<ResourceDO> getResourceListByPermission(String permission) {
        return resourceMapper.selectListByPermission(permission);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void migrateFromMenu() {
        // 🔴 塌缩开启后，本方法的幂等键（menu_id）在<b>已塌缩的库</b>上失效，跑一次就把塌缩退回去。
        //
        // 塌缩的正题是「一个身份行承载同一页上的多个按钮」，所以 R1 只给每个身份行留了
        // <b>一个</b>代表 menu_id（实测 64 行被重指到锚点上），其余按钮的 menu_id 关联被解除。
        // 而下面的幂等判据是 selectByMenuId —— 它查不到那些被解除关联的按钮，
        // 于是判定「没迁过」，逐个 insert 出成员行。
        // 实测这个数字是 <b>341 / 666</b>：非目录菜单里 341 个已经没有承载自己 menu_id 的存活资源行
        // （塌缩前是 0）。也就是一次重启退回四分之三个塌缩，而且全程无异常。
        //
        // 退回之后更坏的是它<b>不会</b>表现为「权限变多」：新建出来的成员行会让
        // GrantDecisionService#lookupOf 的 R11 镜像判定「成员行还在」⟹ 改按原始串查 ⟹
        // 而授权早已搬到身份行上 ⟹ <b>静默判成无权</b>。
        //
        // 所以非空库上整体跳过；增量入口是 syncFromMenu（菜单增改删都会调它）。
        // ⚠️ 空库仍然要跑：全新安装靠本方法引导，跳过就等于「装完什么都点不动」
        if (collapsedLocator.collapseEnabled() && resourceMapper.selectCount() > 0) {
            log.info("[migrateFromMenu][塌缩读路径已开启且 sys_resource 非空，跳过全量引导。"
                    + "menu_id 在塌缩后不再是幂等键，增量请走 syncFromMenu]");
            return;
        }
        boolean collapse = collapsedLocator.collapseEnabled();
        List<MenuDO> menus = menuMapper.selectList();
        // 塌缩时同一族会有多个菜单落到同一个身份串上，谁当「代表菜单」必须与计划器 R1 同口径：
        // 非按钮菜单优先（R1 的 navMenuIds 就是 type≠BUTTON 的集合）。
        // List#sort 是稳定排序，所以同组内仍是原来的 id 序
        if (collapse) {
            menus = new ArrayList<>(menus);
            menus.sort(Comparator.comparing(
                    m -> MenuTypeEnum.BUTTON.getType().equals(m.getType()) ? 1 : 0));
        }
        Map<String, String> identities = collapse
                ? collapsedLocator.identityOfAll(menus.stream().map(MenuDO::getPermission)
                        .filter(StrUtil::isNotBlank).collect(Collectors.toList()))
                : Map.of();
        int created = 0, skipped = 0;
        for (MenuDO menu : menus) {
            if (!shouldHaveResource(menu)) {
                skipped++;
                continue;
            }
            // 塌缩且这个串真的被塌了 → 由身份行承载，一族只建一行。
            // ⚠️ 判据是「identity 与原串不同」而不是「开关开着」：档 C（业务专有动词）与
            //    档 D（两段式身份）压根没被本次迁移塌缩，它们在库里的形状与今天一致
            //    （同一个权限串可以对应多行资源，实测 hrm:portal:query 有 8 行），
            //    这里若替它们合并成一行，就是一次没经过计划器、没经过自校验的额外改动
            String identity = identities.get(menu.getPermission());
            if (identity != null && !identity.equals(menu.getPermission())) {
                // 含停用的行：停用的行同样占位，漏掉它会插出第二行同权限串的资源
                if (resourceMapper.selectByPermissionIncludeDisabled(identity) != null) {
                    skipped++;
                    continue;
                }
                resourceMapper.insert(buildCarrierFromMenu(menu, identity));
                created++;
                continue;
            }
            // 幂等：按 menu_id 查，已迁移的跳过
            if (resourceMapper.selectByMenuId(menu.getId()) != null) {
                skipped++;
                continue;
            }
            resourceMapper.insert(buildFromMenu(menu));
            created++;
        }
        log.info("[migrateFromMenu][迁移完成，新建 {} 行，跳过 {} 行，塌缩={}]", created, skipped, collapse);
    }

    /**
     * 这个菜单该不该有资源行
     *
     * <h3>判据是「是不是目录」，不再是「有没有 permission」</h3>
     *
     * 原判据是「有没有 permission」，理由是「没有权限串的菜单靠 listAllowedMenuIds
     * 沿菜单父链补全，不需要资源行」。那个理由<b>只对有带权限子孙的菜单成立</b>。
     *
     * 实测全库有 <b>21 个菜单整棵子树都没有任何权限标识</b>
     * （审批中心/发起流程、审批中心/已办任务、系统管理/地区管理、
     *   表单设计/表单设计器、表单设计/表单运行时、监控中心的三项 …）。
     * 它们没有资源行 → 不参与授权判定；又没有带权限的子孙 → 祖先补全也够不着，
     * 于是<b>对非超管永远不可见，且没有任何界面能把它配出来</b>。
     * 老的「菜单权限」按钮曾是唯一入口，但它写的 system_role_menu 在
     * {@code grant-enabled=true} 后已不参与判定（且那 305 条指向无资源行菜单的授权，
     * 迁移时本来就被当作「未映射」静默丢弃了）。
     *
     * <h3>为什么目录仍然不建</h3>
     *
     * 目录（type=1）是纯容器，它的可见性完全由子节点决定 ——
     * {@code completeMenuAncestors} 沿菜单父链把它补出来即可，从来不需要被显式授权。
     * 给 97 个目录都建上资源行，只会让授权页的资源树里多出一堆「勾了也没用」的节点，
     * 而「勾了没用」正是这个子系统最贵的那类缺陷。
     */
    private boolean shouldHaveResource(MenuDO menu) {
        return !MenuTypeEnum.DIR.getType().equals(menu.getType());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Caching(evict = {
            @CacheEvict(cacheNames = RedisKeyConstants.PERMISSION_MENU_ID_LIST, allEntries = true),
            @CacheEvict(cacheNames = RedisKeyConstants.GRANT_USER_PERMISSIONS + "#30s", allEntries = true),
            @CacheEvict(cacheNames = RedisKeyConstants.GRANT_USER_MENU_IDS + "#30s", allEntries = true)
    })
    public void syncFromMenu(Long menuId) {
        try {
            doSyncFromMenu(menuId);
        } finally {
            // 本方法有 6 个出口、5 处直接写库，逐出口补驱逐必然漏；统一在 finally 里清
            evictChainCache();
        }
    }

    private void doSyncFromMenu(Long menuId) {
        if (menuId == null) {
            return;
        }
        MenuDO menu = menuMapper.selectById(menuId);
        ResourceDO existing = resourceMapper.selectByMenuId(menuId);

        // 1. 菜单已删除（或查不到）→ 把资源行一起清掉，否则资源树里会留一个点不开的幽灵节点
        if (menu == null) {
            if (existing != null) {
                // 🔴 塌缩后「代表菜单被删」不等于「身份行该删」：同族可能还有别的活菜单靠它承载。
                //    删掉它 = 删掉一整页按钮的家（授权还挂在上面），后果是静默 403
                if (repointCarrierToSuccessor(existing, menuId)) {
                    return;
                }
                // ⚠️ 有后代的不能删（比如它下面挂了列/字段资源），留着比删出一堆孤儿好
                if (resourceMapper.selectCountByParentId(existing.getId()) > 0) {
                    log.warn("[syncFromMenu][菜单({})已删除，但资源({})下还有后代，保留]",
                            menuId, existing.getId());
                    return;
                }
                resourceMapper.deleteById(existing.getId());
                log.info("[syncFromMenu][菜单({})已删除，同步删掉资源({})]", menuId, existing.getId());
            }
            return;
        }

        // 2. 目录不该有资源行（判据与 migrateFromMenu 一致）。
        //    原来是菜单/按钮、现在被改成目录了的，要把资源行删掉
        if (!shouldHaveResource(menu)) {
            if (existing != null && repointCarrierToSuccessor(existing, menuId)) {
                return;
            }
            if (existing != null && resourceMapper.selectCountByParentId(existing.getId()) == 0) {
                resourceMapper.deleteById(existing.getId());
                log.info("[syncFromMenu][菜单({})已改为目录，同步删掉资源({})]",
                        menuId, existing.getId());
            }
            return;
        }

        // 3. 🔴 塌缩开启后，菜单的权限串<b>不再等于</b>承载它的资源行的权限串，
        //    下面分支 3/4 的两条判据（「menu_id 指着我的那一行」「把 permission 改成菜单的」）
        //    全部失效。走单独一条路，未塌缩的老路一个字节不动。
        //
        //    不改的后果是实测过的：R1 把 64 个 menu_id 重指到锚点上之后，
        //    <b>94 个存活资源行的 permission 与它 menu_id 所指菜单的 permission 不一致，
        //    其中 85 个菜单是按钮</b>。于是管理员在界面上编辑其中任意一个按钮，
        //    分支 4 就会把身份行 system:role 改名回 system:role:query 并级联重算后代路径 ——
        //    而判定侧查的是 system:role，权限当场消失，且没有任何日志说明原因
        if (collapsedLocator.collapseEnabled() && StrUtil.isNotBlank(menu.getPermission())) {
            syncCarrierFromMenu(menu);
            return;
        }

        // 4. 新建
        if (existing == null) {
            ResourceDO resource = buildFromMenu(menu);
            attachToConventionParent(resource);   // 按权限串命名约定挂到正确的父节点下
            resourceMapper.insert(resource);
            log.info("[syncFromMenu][菜单({}) 新建资源({}) permission={} parent={}]",
                    menuId, resource.getId(), resource.getPermission(), resource.getParentId());
            return;
        }

        // 5. 更新。permission 变了要走 updateResource —— 它会级联更新后代的物化路径
        boolean permissionChanged = !StrUtil.equals(existing.getPermission(), menu.getPermission());
        existing.setPermission(menu.getPermission());
        existing.setName(menu.getName());
        existing.setSort(menu.getSort());
        existing.setStatus(menu.getStatus());
        // ⚠️ 类型跟着 permission 的有无走，和 buildFromMenu 同一套判据。
        // 不跟着切的话，「本来没权限串、后来补上了」的菜单会一直停在 MENU 类型，
        // 而 listAllowedPermissions 只认 permission 非空的行 —— 类型虽不影响那里，
        // 但资源树的展示与 defaultEffect 会和新建出来的行不一致，属于典型的漂移
        applyTypeByPermission(existing);
        if (permissionChanged) {
            updateResource(existing); // 内部会重算 path 并级联后代
            log.info("[syncFromMenu][菜单({})的权限标识变更，资源({})已重算路径]",
                    menuId, existing.getId());
        } else {
            resourceMapper.updateById(existing);
        }
    }

    /**
     * 塌缩开启时的菜单 → 资源同步：先算「该由哪一行承载」，再决定建还是改
     *
     * <h3>与未塌缩那条路的差别只有一处，但那处是全部</h3>
     *
     * 未塌缩时「菜单 : 资源行」是 1:1，{@code selectByMenuId} 就够；
     * 塌缩后是 <b>N:1</b> —— {@code system:user:query} 与 {@code system:user:create}
     * 都由 {@code system:user} 这一行承载。于是：
     * <ul>
     *   <li>「该不该新建」的判据从「有没有行指着这个菜单」变成「这个身份有没有行」</li>
     *   <li>「该不该改显示字段」多了一条前置：<b>本菜单是不是那一行的代表菜单</b>。
     *       不是的话就什么都不做 —— 那一行的名字属于代表菜单，而本菜单的按钮权限
     *       已经由「这一行 + 动作码」表达了，它本来就不需要自己的行</li>
     * </ul>
     *
     * <p>⚠️ 代表菜单的 {@code status} 会带动整行：停用代表菜单等于停用整个身份的承载行。
     * 这是「一行代表一族」的固有后果，也是 R1 优先挑<b>非按钮</b>菜单当代表的原因 ——
     * 页面菜单比某个按钮更适合代表整页
     *
     * <p>🔴 但「非按钮优先」只是偏好，不是保证：R1 找不到非按钮候选时只能挑按钮，
     * 因为页面菜单的 permission 是空串，压根进不了塌缩的候选集。本库实测
     * {@code infra:data-model}、{@code system:role} 这些行的 menu_id 都指着按钮。
     * 于是「名字取谁的」必须单独定一条规则，见 {@link #carrierDisplayName}
     */
    private void syncCarrierFromMenu(MenuDO menu) {
        String identity = collapsedLocator.identityOf(menu.getPermission());
        ResourceDO carrier = resourceMapper.selectByPermissionIncludeDisabled(identity);

        // 承载行还不存在：这个菜单是该身份的第一个入口，建出来
        if (carrier == null) {
            ResourceDO created = buildCarrierFromMenu(menu, identity);
            resourceMapper.insert(created);
            log.info("[syncCarrierFromMenu][菜单({}) permission={} 新建承载资源({}) permission={} parent={}]",
                    menu.getId(), menu.getPermission(), created.getId(), identity, created.getParentId());
            return;
        }

        if (!menu.getId().equals(carrier.getMenuId())) {
            log.info("[syncCarrierFromMenu][菜单({}) permission={} 由资源({}) permission={} 承载，"
                            + "但该行的 menu_id 指向菜单({})，本次不同步显示字段]",
                    menu.getId(), menu.getPermission(), carrier.getId(), identity, carrier.getMenuId());
            return;
        }

        carrier.setName(carrierDisplayName(menu));
        carrier.setSort(menu.getSort());
        carrier.setStatus(menu.getStatus());
        // ⚠️ 刻意不调 applyTypeByPermission：承载行的类型是它自己的事。
        //    塌缩新建的 33 行是 ACTION，而 64 个复用既有锚点的行里有一批本来就是 MENU
        //    （阶段 0 实测「有 permission 的 MENU 资源」84 行 == 档 D + 模块根）。
        //    跟着菜单改类型会顺带改 default_effect —— 那是一次没人请求过的授权语义变更
        resourceMapper.updateById(carrier);
    }

    /**
     * 承载行该显示什么名字
     *
     * <p>代表菜单是按钮时，改用它的<b>父级页面菜单</b>的名字。承载行代表整个身份
     * （{@code infra:data-model} ＝「数据模型」这一页），而按钮只代表其中一个动作
     * （{@code infra:data-model:query} ＝「数据模型查询」）—— 拿动作名当整页的名字，
     * 授权树上就会显示「数据模型查询」底下挂着创建、更新、删除，读不通。
     *
     * <p>🔴 这条路径是可达的，不是理论问题：R1 只在找不到非按钮候选时才挑按钮当代表，
     * 而页面菜单的 permission 是空串、进不了塌缩的候选集，所以本库里
     * {@code infra:data-model}、{@code system:role} 这些行的代表都是按钮。
     * 管理员在菜单管理里编辑那个按钮（哪怕只是调 sort 后点保存），
     * {@code syncFromMenu} 就会带着按钮名走到这里。而承载行不是菜单、菜单管理里看不到它，
     * 资源树又是只读的 —— 名字一旦被冲掉，没有任何界面能改回去。
     *
     * <p>⚠️ 只管 name 这一个展示字段。sort / status 仍然取自代表菜单：
     * 它们是这一行的运行属性，改动会连带影响排序与整行启停，属于另一件事，
     * 也是上面那条「停用代表菜单等于停用整个身份」警告的由来。
     *
     * <p>父级不是页面菜单时（按钮挂在顶层、或父级是目录）退回按钮自己的名字 ——
     * 那种情况下按钮名是唯一可用的显示来源，行为与塌缩前一致。
     * 目录名（如「数据管理」）刻意不采纳：它是容器名，比动作名更不像这一页。
     */
    private String carrierDisplayName(MenuDO menu) {
        if (!MenuTypeEnum.BUTTON.getType().equals(menu.getType()) || menu.getParentId() == null) {
            return menu.getName();
        }
        MenuDO parent = menuMapper.selectById(menu.getParentId());
        if (parent == null || !MenuTypeEnum.MENU.getType().equals(parent.getType())) {
            return menu.getName();
        }
        return parent.getName();
    }

    /**
     * 代表菜单要没了（被删 / 被改成目录）时，把承载行的 menu_id 转交给同族的下一个活菜单
     *
     * <p>🔴 塌缩后一个身份行同时是一整页按钮的家，而授权是挂在这一行上的。
     * 代表菜单被删就删行，等于把这一页的授权一起删掉 —— 表现是整页按钮静默 403，
     * 而且删掉的是<b>有授权引用的</b>行，事后从界面上完全看不出发生了什么。
     *
     * <p>挑选口径与 {@code ResourceCollapsePlanner} 的 R1 一致：非按钮菜单优先，
     * 同组内取 id 最小的。查不到接班人时返回 false，由调用方继续走原来的删除分支
     *
     * @return 是否已经转交（true ⟹ 调用方应当直接返回，不要删行）
     */
    private boolean repointCarrierToSuccessor(ResourceDO carrier, Long removedMenuId) {
        if (!collapsedLocator.collapseEnabled() || StrUtil.isBlank(carrier.getPermission())) {
            return false;
        }
        List<MenuDO> candidates = menuMapper.selectList().stream()
                .filter(m -> !m.getId().equals(removedMenuId))
                .filter(m -> !MenuTypeEnum.DIR.getType().equals(m.getType()))
                .filter(m -> carrier.getPermission().equals(collapsedLocator.identityOf(m.getPermission())))
                // 非按钮优先，其次 id 小的优先 —— 与 R1 同口径
                .sorted(Comparator
                        .comparing((MenuDO m) -> MenuTypeEnum.BUTTON.getType().equals(m.getType()) ? 1 : 0)
                        .thenComparing(MenuDO::getId))
                .toList();
        if (candidates.isEmpty()) {
            return false;
        }
        Long successor = candidates.get(0).getId();
        carrier.setMenuId(successor);
        resourceMapper.updateById(carrier);
        log.info("[repointCarrierToSuccessor][菜单({})已失效，资源({}) permission={} 的 menu_id 转交给菜单({})，"
                        + "同族还有 {} 个候选]",
                removedMenuId, carrier.getId(), carrier.getPermission(), successor, candidates.size());
        return true;
    }

    /**
     * 按菜单行构造<b>塌缩后的承载行</b>：permission 用身份串，其余显示字段抄菜单
     *
     * <p>与 {@link #buildFromMenu} 的差别是 permission 与随之而来的类型/路径 ——
     * 权限串是物化路径的 ident，改了它就必须重算，否则这一行会挂在
     * {@code /root/system:user:create/} 这种已经不存在的路径上，祖先链上溯找不到它
     */
    private ResourceDO buildCarrierFromMenu(MenuDO menu, String identity) {
        ResourceDO resource = buildFromMenu(menu);
        resource.setPermission(identity);
        // 与 syncCarrierFromMenu 同一条口径：新建时就别把动作名当整页的名字，
        // 否则这一行从出生起就是错的，而资源树只读、没有界面能改
        resource.setName(carrierDisplayName(menu));
        applyTypeByPermission(resource);   // 身份串非空 → ACTION，与计划器 newIdentityTemplate 同口径
        fillPathAndDepth(resource);        // ident 变了要重算
        attachToConventionParent(resource); // 找到约定父节点时会再算一次
        return resource;
    }

    /**
     * 按权限串的命名约定，把资源挂到正确的父节点下
     *
     * <h3>为什么新建时就要挂对</h3>
     *
     * {@link #buildFromMenu} 建出来的资源是 {@code parentId = ID_ROOT}（扁平），
     * 原本指望「约定式推导会重建树结构」—— 但那个推导只新建缺失节点、
     * 对已存在的一律跳过，于是扁平状态永远不会被修正
     * （实测 840 个资源里 712 个挂在根下）。
     *
     * 而挂在根下的后果是<b>授权继承失效</b>：给 {@code system:role} 授权，
     * 覆盖不到挂在根下的 {@code system:role:query} —— 必须逐个单独授权。
     *
     * 所以新建时就按约定找父：{@code a:b:c} 的父是 {@code a:b}，
     * 父不存在就退回根下（等推导 Runner 下次补齐父节点后再归位）。
     */
    private void attachToConventionParent(ResourceDO resource) {
        String permission = resource.getPermission();
        if (StrUtil.isBlank(permission)) {
            return;
        }
        int lastColon = permission.lastIndexOf(':');
        if (lastColon <= 0) {
            return; // 只有一段（如 system），本来就该挂根下
        }
        String parentPermission = permission.substring(0, lastColon);
        List<ResourceDO> parents = resourceMapper.selectListByPermission(parentPermission);
        if (!parents.isEmpty()) {
            resource.setParentId(parents.get(0).getId());
            fillPathAndDepth(resource); // 父变了要重算物化路径
        }
    }

    /**
     * 按菜单行构造资源行。{@link #migrateFromMenu()} 与 {@link #syncFromMenu(Long)} 共用
     *
     * ⚠️ 两条路径必须共用这一份，各写一份必然漂移 ——
     * 漂移的表现是「重启前后同一个菜单建出来的资源不一样」
     */
    private ResourceDO buildFromMenu(MenuDO menu) {
        ResourceDO resource = new ResourceDO();
        resource.setPermission(menu.getPermission());
        resource.setName(menu.getName());
        resource.setParentId(ResourceDO.ID_ROOT); // 先挂根下，S2c 的约定式推导会重建树结构
        resource.setSort(menu.getSort());
        resource.setStatus(menu.getStatus());
        resource.setMenuId(menu.getId());
        applyTypeByPermission(resource);
        fillPathAndDepth(resource);
        return resource;
    }

    /**
     * 按「有没有权限标识」决定资源类型与默认效果
     *
     * <ul>
     *   <li><b>有</b> permission → {@code ACTION}。带 permission 的<i>菜单层</i>同样建成 ACTION
     *       而不是 MENU：sys_resource 里的类型跟着<b>语义</b>走（这行是要被授权的权限点），
     *       不跟着导航形态走。塌缩落库后实测本库有 <b>84</b> 个 type=MENU 且带 permission 的行
     *       （阶段 0 的口径是「档 D 79 + root 5」，正好也是 84；本注释早期写 68，那是更早的库），
     *       它们既是导航项也是权限点</li>
     *   <li><b>没有</b> permission → {@code MENU}。这类菜单没有权限串可配，
     *       只能按菜单本身授权，{@code listAllowedMenuIds} 靠 {@code menu_id} 把它映射回导航</li>
     * </ul>
     *
     * 两者的 {@code defaultEffect} 都是 DENY（见 {@link ResourceTypeEnum#defaultEffect()}），
     * 都必须显式授权才可见 —— 语义一致，不会因为换了类型就悄悄放开。
     *
     * <h3>🔴 下面那条「空 permission → MENU」分支<b>不能删</b></h3>
     *
     * 《权限资源统一方案》阶段 3 第 11 条要求删掉它，前提是方案「症状 1」已经解决 ——
     * 即那 325 行 {@code /root/menu:<id>/} 已经塌进约定树、「页面不再是一个资源」。
     * <b>那个前提没有成立</b>，塌缩落库后实测：
     *
     * <ul>
     *   <li>{@code sys_resource} 里空权限串的存活行 <b>119 行，类型全是 MENU</b></li>
     *   <li>{@code system_menu} 里非目录且权限串为空的菜单 <b>116 个</b>（type=2 有 115 个、type=3 有 1 个），
     *       其中包含角色管理、菜单管理、组织管理、租户列表、流程模型这些<b>主页面</b></li>
     *   <li>{@link #syncFromMenu} 的塌缩分支（第 3 步）判据是
     *       {@code collapseEnabled() && isNotBlank(menu.getPermission())} ——
     *       权限串为空的菜单<b>走不进</b>承载行那条路，仍然落到第 4 步的
     *       {@link #buildFromMenu}，也就仍然会调进本方法</li>
     * </ul>
     *
     * 所以这一支是<b>活的、而且在承重</b>。删掉它的后果是这 119 行在下次菜单同步时被改成
     * {@code type=ACTION} 且权限串为空，而 {@code PermissionDriftDetector} 的「陈旧」判据
     * 正是 {@code type == ACTION} —— 于是 119 个空字符串会灌进那份报告，
     * 把刚拆开的两类陈旧又搅成一团。要删它，得先做完症状 1（让这些页面不再需要资源行）
     */
    private void applyTypeByPermission(ResourceDO resource) {
        ResourceTypeEnum type = StrUtil.isNotBlank(resource.getPermission())
                ? ResourceTypeEnum.ACTION
                : ResourceTypeEnum.MENU;
        resource.setType(type.getCode());
        resource.setDefaultEffect(type.defaultEffect());
    }

    /**
     * 计算并填写物化路径与深度
     *
     * 路径的 ident 优先用 permission；没有 permission 的用 {@code menu:<menuId>}
     * （见 {@link #MENU_IDENT_PREFIX}）
     */
    private void fillPathAndDepth(ResourceDO resource) {
        String parentPath;
        int depth;
        if (resource.getParentId() == null || ResourceDO.ID_ROOT.equals(resource.getParentId())) {
            parentPath = ResourceDO.PATH_ROOT_PREFIX;
            depth = 1;
        } else {
            ResourceDO parent = resourceMapper.selectById(resource.getParentId());
            if (parent == null) {
                // 父节点不存在，退化为挂根节点：比报错好，迁移时不应该因为一个父不存在就整批失败
                log.warn("[fillPathAndDepth][父资源({})不存在，退化为根节点]", resource.getParentId());
                parentPath = ResourceDO.PATH_ROOT_PREFIX;
                depth = 1;
            } else {
                parentPath = parent.getPath();
                depth = parent.getDepth() + 1;
            }
        }
        // ident：有 permission 用 permission；没有的用 menu:<menuId>
        //
        // ⚠️ 原来这里写的是「没有 permission 就用 resource.getId()」，而本方法是在 insert
        // 之前调用的 —— 那时 id 还是 null，会整批落到 "_tmp_" 这个兜底值上。
        // 以前不暴露是因为无 permission 的资源根本建不出来；现在能建了，不修就是
        // 163 个资源共用同一条物化路径，祖先链上溯直接乱套
        String ident;
        if (StrUtil.isNotBlank(resource.getPermission())) {
            ident = resource.getPermission();
        } else if (resource.getMenuId() != null) {
            ident = MENU_IDENT_PREFIX + resource.getMenuId();
        } else {
            ident = resource.getId() != null ? String.valueOf(resource.getId()) : "_tmp_";
        }
        resource.setPath(ResourceDO.buildPath(parentPath, ident));
        resource.setDepth((short) depth);
    }

    /**
     * 把旧路径前缀替换成新路径前缀，级联更新所有后代
     */
    private void cascadeUpdatePath(String oldPathPrefix, String newPathPrefix) {
        if (StrUtil.equals(oldPathPrefix, newPathPrefix)) {
            return;
        }
        List<ResourceDO> descendants = resourceMapper.selectDescendantsByPathPrefix(oldPathPrefix);
        for (ResourceDO desc : descendants) {
            if (!desc.getPath().startsWith(oldPathPrefix)) {
                continue;
            }
            String newPath = newPathPrefix + desc.getPath().substring(oldPathPrefix.length());
            // ⚠️ 口径必须与 fillPathAndDepth 一致：挂在根下的 depth = 1。
            //
            // 物化路径形如 /root/a/b/，斜杠数 = 层数 + 2（开头一个、root 后一个、每层末尾一个），
            // 所以 depth = 斜杠数 - 2。原来写的是 -1，比 fillPathAndDepth 算出来的【多 1】：
            // 同一个节点，新建时 depth 正确，一旦父节点变更走了级联更新就被写大 1，
            // 实测库里有 219 个资源是这个状态。
            //
            // 它不影响鉴权（判定走 parent_id 建链，不读 depth），所以一直没暴露；
            // 但 depth 是给树形展示和"限制层级"这类逻辑用的，错了迟早出问题
            int newDepth = (int) newPath.chars().filter(c -> c == '/').count() - 2;
            ResourceDO updateObj = new ResourceDO();
            updateObj.setId(desc.getId());
            updateObj.setPath(newPath);
            updateObj.setDepth((short) Math.max(1, newDepth));
            resourceMapper.updateById(updateObj);
        }
    }

}
