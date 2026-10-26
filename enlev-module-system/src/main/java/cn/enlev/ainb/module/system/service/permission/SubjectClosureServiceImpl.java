package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.datapermission.core.annotation.DataPermission;
import cn.enlev.ainb.module.system.dal.dataobject.dept.DeptDO;
import cn.enlev.ainb.module.system.dal.dataobject.dept.PostDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleDO;
import cn.enlev.ainb.module.system.dal.dataobject.user.AdminUserDO;
import cn.enlev.ainb.module.system.dal.redis.RedisKeyConstants;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectGraph;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import cn.enlev.ainb.module.system.service.dept.PostService;
import cn.enlev.ainb.module.system.service.user.AdminUserService;
import com.google.common.base.Suppliers;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Supplier;

/**
 * 主体闭包 Service 实现类
 *
 * ## 闭包由两段拼成
 *
 * <pre>
 *     ① 原生边：用户 → 他的角色 / 岗位 / 部门
 *        现查 system_user_role 与 system_users，**不镜像**到主体表。
 *        这三张表是这三类关系的唯一数据源，镜像一份只会制造双写不一致，
 *        而权限的双写不一致是「静默错误」——「配了、保存成功了、但没生效」
 *
 *     ② 图闭包：上面这批主体沿 sys_subject_member 向上的全部祖先
 *        就是角色继承与用户组。这部分是本子系统自己写的数据，
 *        所以它的缓存失效也只挂在自己的写路径上 —— 缓存边界与数据所有权对齐
 * </pre>
 *
 * ## 缓存策略：缓存图，不缓存用户
 *
 * 整张图**一个 Redis key**（见 {@link RedisKeyConstants#SUBJECT_ANCESTORS}）。
 * 没有做「用户 → 闭包」的缓存，是刻意的：那份缓存要在
 * 「改用户角色 / 改用户部门 / 改用户岗位 / 改角色状态 / 改主体图」五处写路径上失效，
 * 其中前四处不归本子系统管。漏清一处的表现是**权限多出来且不会自己好** ——
 * 宁可每次多一次 PK 查询，也不要一个跨四个 Service 的失效契约。
 *
 * 代价：{@code postEnabled} 或 {@code deptEnabled} 打开时，每次闭包多一次
 * system_users 的主键查询。S2a 的决策引擎会在单次请求内持有闭包结果，
 * 那一层拿掉重复开销更自然。压测门槛见 v9 §8.2
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class SubjectClosureServiceImpl implements SubjectClosureService {

    @Resource
    private SubjectGraphLoader subjectGraphLoader;

    @Resource
    private PermissionService permissionService;
    @Resource
    private RoleService roleService;
    @Resource
    private PostService postService;
    @Resource
    private DeptService deptService;
    @Resource
    private AdminUserService userService;

    @Resource
    private PermissionProperties permissionProperties;

    @Override
    @DataPermission(enable = false) // 关掉数据权限，否则「算权限」本身又要先算一次权限
    public Set<SubjectKey> getSubjectKeysByUserId(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        // 1. 原生边：用户自己 + 角色 + 岗位 + 部门
        Set<SubjectKey> seeds = new LinkedHashSet<>();
        seeds.add(SubjectKey.ofUser(userId));
        // 「所有登录用户」恒在闭包里：它是「不限制」的显式表达（见 SubjectTypeEnum.ALL）。
        // ⚠️ 只要有 userId 就加 —— userId 为 null 的路径在方法开头已经返回空集，
        // 也就是说匿名/内部调用拿不到 ALL，这与「所有【登录】用户」的语义一致
        seeds.add(SubjectKey.ofAllUsers());
        addRoleSeeds(seeds, userId);
        // 用户行只在真的要用（岗位/部门主体开着）时才查，且一次请求内只查一次
        Supplier<AdminUserDO> user = Suppliers.memoize(() -> userService.getUser(userId));
        PermissionProperties.Subject config = permissionProperties.getSubject();
        if (Boolean.TRUE.equals(config.getPostEnabled())) {
            addPostSeeds(seeds, user.get());
        }
        if (Boolean.TRUE.equals(config.getDeptEnabled())) {
            addDeptSeeds(seeds, user.get(), Boolean.TRUE.equals(config.getDeptAncestorEnabled()));
        }

        // 2. 图闭包：每个种子向上取祖先
        Map<String, Set<String>> ancestorMap = getSelf().getAncestorMapFromCache();
        if (ancestorMap.isEmpty()) {
            return seeds;
        }
        Set<SubjectKey> result = new LinkedHashSet<>(seeds);
        for (SubjectKey seed : seeds) {
            for (String ancestor : ancestorMap.getOrDefault(seed.asString(), Set.of())) {
                SubjectKey key = SubjectKey.parse(ancestor);
                if (key != null) {
                    result.add(key);
                }
            }
        }
        return result;
    }

    @Override
    public Set<SubjectKey> getAncestorKeys(SubjectKey key) {
        if (key == null) {
            return Set.of();
        }
        Set<String> ancestors = getSelf().getAncestorMapFromCache().getOrDefault(key.asString(), Set.of());
        Set<SubjectKey> result = new LinkedHashSet<>(ancestors.size());
        for (String ancestor : ancestors) {
            SubjectKey parsed = SubjectKey.parse(ancestor);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    @Override
    @DataPermission(enable = false) // 建缓存时必须是全量的，否则不同用户建出来的缓存内容不一样
    @Cacheable(cacheNames = RedisKeyConstants.SUBJECT_ANCESTORS, key = "'all'")
    public Map<String, Set<String>> getAncestorMapFromCache() {
        // 停用的主体不进图：停掉一个基础角色，继承它的角色就该立刻失去那批权限
        SubjectGraph graph = subjectGraphLoader.load(false);
        Map<String, Set<String>> ancestorMap = graph.buildAncestorMap();
        log.debug("[getAncestorMapFromCache][主体图 {} 条边，算出 {} 个主体的祖先]",
                graph.edgeCount(), ancestorMap.size());
        return ancestorMap;
    }

    /**
     * 角色种子。只取启用的 —— 禁用的角色不该再给任何权限
     */
    private void addRoleSeeds(Set<SubjectKey> seeds, Long userId) {
        Set<Long> roleIds = permissionService.getUserRoleIdListByUserIdFromCache(userId);
        if (CollUtil.isEmpty(roleIds)) {
            return;
        }
        for (RoleDO role : roleService.getRoleListFromCache(roleIds)) {
            if (CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus())) {
                seeds.add(SubjectKey.ofRole(role.getId()));
            }
        }
    }

    /**
     * 岗位种子。同样只取启用的
     */
    private void addPostSeeds(Set<SubjectKey> seeds, AdminUserDO user) {
        if (user == null || CollUtil.isEmpty(user.getPostIds())) {
            return;
        }
        for (PostDO post : postService.getPostList(user.getPostIds())) {
            if (CommonStatusEnum.ENABLE.getStatus().equals(post.getStatus())) {
                seeds.add(SubjectKey.ofPost(post.getId()));
            }
        }
    }

    /**
     * 部门种子
     *
     * ⚠️ 默认整块不开（enlev.permission.subject.dept-enabled = false），理由见
     * {@link PermissionProperties.Subject#getDeptEnabled()}
     *
     * 开启时默认连祖先部门一起算：给「研发中心」授权，下属各组的人自然应该有 ——
     * 不这么做的话，配置者看到的是「配了研发中心，研发一组的人没生效」，
     * 而界面上看不出任何异常
     */
    private void addDeptSeeds(Set<SubjectKey> seeds, AdminUserDO user, boolean withAncestor) {
        if (user == null || user.getDeptId() == null) {
            return;
        }
        DeptDO dept = deptService.getDept(user.getDeptId());
        // 部门被停用/删除时不算：跟角色、岗位一个口径
        if (dept == null || !CommonStatusEnum.ENABLE.getStatus().equals(dept.getStatus())) {
            return;
        }
        seeds.add(SubjectKey.ofDept(dept.getId()));
        if (!withAncestor) {
            return;
        }
        // getParentDeptList 自带 visited 集合与次数上限，脏数据成环时会提前结束
        for (DeptDO parent : deptService.getParentDeptList(dept.getId())) {
            if (CommonStatusEnum.ENABLE.getStatus().equals(parent.getStatus())) {
                seeds.add(SubjectKey.ofDept(parent.getId()));
            }
        }
    }

    /**
     * 获得自身的代理对象，解决 @Cacheable 在类内调用时不生效的问题
     */
    private SubjectClosureServiceImpl getSelf() {
        return SpringUtil.getBean(getClass());
    }

}
