package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.system.dal.dataobject.dept.DeptDO;
import cn.enlev.ainb.module.system.dal.dataobject.dept.PostDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SubjectDO;
import cn.enlev.ainb.module.system.dal.dataobject.user.AdminUserDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.SubjectMapper;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import cn.enlev.ainb.module.system.service.dept.PostService;
import cn.enlev.ainb.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 授权主体的「展示名」批量解析
 *
 * <h3>为什么需要它</h3>
 *
 * {@code sys_grant} 存的是 (subject_type, subject_ref_id) 而不是外键，所以授权列表
 * 拿到的主体那一列天然是一串数字。管理员看到「主体ID = 104」根本无从判断
 * 这条权限授给了谁 —— 而这一列恰恰是整张表里最需要读懂的一列：
 * 授权配错对象是权限事故里最常见的一种，读不懂就核对不了。
 *
 * <h3>🔴 为什么不能读 sys_subject.name</h3>
 *
 * 那张表<b>不是</b>用户/角色/部门的镜像，它只按需登记「需要区别对待」的主体
 * （见 {@code SubjectDO} 的类注释）：一个没有继承边、也没被单独授权的普通角色，
 * 在里面<b>根本不存在</b>；而且用户类型压根不落行。它的 name 列自己就写着
 * 「仅用于排错与列表展示，可能过期，任何判定逻辑都不许读它」。
 * 拿它做展示会得到「一部分主体有名字、一部分是空的、有名字的那个还可能是旧名」——
 * 比统一显示数字更容易误导人。
 *
 * 所以这里一律回<b>权威业务表</b>：system_role / system_users / system_dept /
 * system_post；只有用户组没有独立业务表（{@code refId} 就是 sys_subject.id 自身），
 * 才回落 sys_subject。
 *
 * <h3>⚠️ 为什么不用前端的 simple-list 自己拼</h3>
 *
 * 前端能拿到的 {@code /system/role/simple-list} 只有 (id, name)、<b>没有 code</b>；
 * {@code /system/user/simple-list} 只有 (id, nickname, ...)、<b>没有 username</b>，
 * 而且它只返回<b>启用</b>的用户（{@code getUserListByStatus(ENABLE)}）——
 * 一个被停用的用户身上的授权行会解析不出名字，而那正是最需要看清「这条权限还在谁身上」的时候。
 * 另外前端拼名字要把全量用户/角色拉下来才能对上几个 id，本类只按出现的 id 批量查。
 *
 * <h3>展示格式</h3>
 *
 * 一律「标识（名称）」，与用户在角色/用户列表里看到的写法一致：
 * 角色 {@code super_admin（超级管理员）}、用户 {@code admin（超级管理员）}、
 * 岗位 {@code ceo（董事长）}；部门与用户组没有编码，只显示名称；
 * SYSTEM / ALL 用 code 寻址（如 {@code bpm-engine}、{@code all-users}），code 本身就是它的身份。
 *
 * @author 走进新时代
 */
@Component
public class SubjectDisplayNameResolver {

    @Resource
    private RoleService roleService;
    @Resource
    private AdminUserService adminUserService;
    @Resource
    private DeptService deptService;
    @Resource
    private PostService postService;
    /**
     * 只为用户组回落它 —— 用户组没有独立业务表，refId 指向的就是本表自身的 id
     */
    @Resource
    private SubjectMapper subjectMapper;

    /**
     * 批量解析一批主体的展示名
     *
     * 每种类型只查一次库（{@code selectBatchIds}），所以 N 条授权行的代价是
     * 「出现了几种主体类型」次查询，而不是 N 次。
     *
     * @return key 是入参里的 SubjectKey；<b>解析不出来时不会放进 map</b>，
     *         由调用方决定怎么回落（见 {@link #describeUnknown}）
     */
    public Map<SubjectKey, String> resolve(Collection<SubjectKey> keys) {
        if (CollUtil.isEmpty(keys)) {
            return Map.of();
        }
        Map<Long, RoleDO> roles = loadByType(keys, SubjectTypeEnum.ROLE,
                ids -> toMap(roleService.getRoleList(ids), RoleDO::getId));
        Map<Long, AdminUserDO> users = loadByType(keys, SubjectTypeEnum.USER,
                ids -> toMap(adminUserService.getUserList(ids), AdminUserDO::getId));
        Map<Long, DeptDO> depts = loadByType(keys, SubjectTypeEnum.DEPT,
                ids -> toMap(deptService.getDeptList(ids), DeptDO::getId));
        Map<Long, PostDO> posts = loadByType(keys, SubjectTypeEnum.POST,
                ids -> toMap(postService.getPostList(ids), PostDO::getId));
        Map<Long, SubjectDO> groups = loadByType(keys, SubjectTypeEnum.GROUP,
                ids -> toMap(subjectMapper.selectByIds(ids), SubjectDO::getId));

        Map<SubjectKey, String> result = new HashMap<>(keys.size());
        for (SubjectKey key : keys) {
            String name = describe(key, roles, users, depts, posts, groups);
            if (name != null) {
                result.put(key, name);
            }
        }
        return result;
    }

    /**
     * 解析不出名字时的回落文案
     *
     * 🔴 刻意写「未找到」而不是「已删除」：查不到的原因不止删除一种 ——
     * 业务表带着租户隔离，跨租户的 ref_id 同样查不出来；用户被停用也仍然查得到，
     * 但角色被停用/删除、部门被撤掉都会落进这一支。断言「已删除」是在替数据库下结论，
     * 而这句话会直接显示给管理员看，说错了就是把人往错的方向支。
     */
    public String describeUnknown(SubjectKey key) {
        if (key == null) {
            return null;
        }
        return key.refId() != null ? "#" + key.refId() + "（未找到）" : null;
    }

    private String describe(SubjectKey key,
                            Map<Long, RoleDO> roles,
                            Map<Long, AdminUserDO> users,
                            Map<Long, DeptDO> depts,
                            Map<Long, PostDO> posts,
                            Map<Long, SubjectDO> groups) {
        SubjectTypeEnum type = key.type();
        if (type == null) {
            return null;
        }
        // SYSTEM / ALL 不指向任何业务表，code 就是它的身份，不需要查库
        if (!type.isRefBased()) {
            return StrUtil.isBlank(key.code()) ? null : key.code();
        }
        Long refId = key.refId();
        if (refId == null) {
            return null;
        }
        return switch (type) {
            case ROLE -> {
                RoleDO role = roles.get(refId);
                yield role == null ? null : withCode(role.getCode(), role.getName());
            }
            case USER -> {
                AdminUserDO user = users.get(refId);
                yield user == null ? null : withCode(user.getUsername(), user.getNickname());
            }
            case DEPT -> {
                DeptDO dept = depts.get(refId);
                yield dept == null ? null : withCode(dept.getCode(), dept.getName());
            }
            case POST -> {
                PostDO post = posts.get(refId);
                yield post == null ? null : withCode(post.getCode(), post.getName());
            }
            case GROUP -> {
                SubjectDO group = groups.get(refId);
                yield group == null ? null : group.getName();
            }
            default -> null;
        };
    }

    /**
     * 「标识（名称）」；两边只有一个有值时不套括号，都没有就返回 null
     *
     * ⚠️ 用全角括号，与本仓库 commit message、页面文案的口径一致
     */
    private String withCode(String code, String name) {
        boolean hasCode = !StrUtil.isBlank(code);
        boolean hasName = !StrUtil.isBlank(name);
        if (hasCode && hasName) {
            return code + "（" + name + "）";
        }
        if (hasName) {
            return name;
        }
        return hasCode ? code : null;
    }

    /**
     * 每种主体类型只在这批 id 里真的出现过时才查库
     *
     * @param loader 由调用方给「怎么查」，本方法只负责决定「要不要查」
     */
    private <T> Map<Long, T> loadByType(Collection<SubjectKey> keys, SubjectTypeEnum type,
                                        Function<Collection<Long>, Map<Long, T>> loader) {
        Set<Long> ids = refIdsOf(keys, type);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return loader.apply(ids);
    }

    private Set<Long> refIdsOf(Collection<SubjectKey> keys, SubjectTypeEnum type) {
        Set<Long> ids = new HashSet<>();
        for (SubjectKey key : keys) {
            if (key != null && key.type() == type && key.refId() != null) {
                ids.add(key.refId());
            }
        }
        return ids;
    }

    private <T> Map<Long, T> toMap(List<T> list, Function<T, Long> idGetter) {
        if (CollUtil.isEmpty(list)) {
            return Map.of();
        }
        return list.stream().collect(Collectors.toMap(idGetter, Function.identity(), (a, b) -> a));
    }

}
