package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.system.dal.dataobject.dept.DeptDO;
import cn.enlev.ainb.module.system.dal.dataobject.dept.PostDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleDO;
import cn.enlev.ainb.module.system.dal.dataobject.user.AdminUserDO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 塌缩迁移自校验的快照夹具：把 {@code _scratch/s2c_snapshot.json} 读成 DO
 *
 * <h3>为什么夹具来自一份仓库外的 JSON，而不是写死在测试代码里</h3>
 *
 * 自校验要证明的是「<b>这个库</b>迁移前后判定不变」，所以它必须吃真实数据：
 * 实测 1022 行资源、1802 条授权、686 行菜单、11 个启用用户。手搓的夹具只能覆盖
 * 手搓的人想到的形态，而这次迁移最怕的恰恰是想不到的那些
 * （跨类型合并、同串多行、停用行、空权限叶子、指向已软删资源的授权）。
 *
 * <p>这份 JSON <b>不进 git</b>：它含真实的用户 id、角色 id、租户 id 与逐条授权明细，
 * 而且每次导出都不一样，进版本库只会制造无意义的 diff。
 * 所以 {@link #loadOrNull()} 在文件不存在时返回 null，由调用方 {@code assumeTrue} 跳过 ——
 * <b>跳过而不是失败</b>：CI 上没有那个库，把「没导出快照」判成红灯只会让人学会忽略红灯。
 *
 * <h3>为什么字段是手工逐个取的，不用 {@code JSONUtil.toBean}</h3>
 *
 * 这是一次不可回滚迁移的唯一一道网。自动映射把「哪个键对应哪个字段」这件事藏进了库的实现里，
 * 一旦键名对不上，得到的是一个字段为 null 的 DO —— 不报错，只是校验跑在一个残缺的世界上。
 * 手工取值的代码长一点，但每一行都能用眼睛核对
 *
 * @author 走进新时代
 */
final class CollapseSnapshot {

    /** 相对 surefire 的工作目录（模块根）向上找的最大层数 */
    private static final int MAX_UPWARD = 8;

    private final List<ResourceDO> resources;
    private final List<GrantDO> grants;
    private final List<MenuDO> menus;
    private final List<AdminUserDO> users;
    private final Map<Long, Set<Long>> roleIdsByUser;
    private final Map<Long, RoleDO> rolesById;
    private final Map<Long, PostDO> postsById;
    private final Map<Long, DeptDO> deptsById;
    private final int subjectRows;
    private final int subjectMemberRows;

    private CollapseSnapshot(JSONObject doc) {
        this.resources = Collections.unmodifiableList(resources(doc));
        this.grants = Collections.unmodifiableList(grants(doc));
        this.menus = Collections.unmodifiableList(menus(doc));
        this.users = Collections.unmodifiableList(users(doc));
        this.roleIdsByUser = Collections.unmodifiableMap(userRoles(doc));
        this.rolesById = Collections.unmodifiableMap(index(roles(doc), RoleDO::getId));
        this.postsById = Collections.unmodifiableMap(index(posts(doc), PostDO::getId));
        this.deptsById = Collections.unmodifiableMap(index(depts(doc), DeptDO::getId));
        this.subjectRows = size(doc, "subjects");
        this.subjectMemberRows = size(doc, "subjectMembers");
    }

    /**
     * 读快照；文件不在就返回 null
     *
     * <p>从 {@code user.dir} 逐级向上找 {@code _scratch/s2c_snapshot.json} ——
     * surefire 的工作目录是模块根（{@code enlev-module-system}），
     * 而快照在仓库<b>外面</b>（{@code C:\code\enlev\_scratch}），所以要往上走两层。
     * 写死绝对路径的话换一台机器就废了，写死相对层数的话从 IDE 里跑（工作目录是仓库根）又废了
     */
    static CollapseSnapshot loadOrNull() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < MAX_UPWARD && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("_scratch").resolve("s2c_snapshot.json");
            if (Files.isRegularFile(candidate)) {
                try {
                    return new CollapseSnapshot(
                            JSONUtil.parseObj(Files.readString(candidate, StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    throw new IllegalStateException("快照读不出来：" + candidate, e);
                }
            }
        }
        return null;
    }

    // ==================== 派生视图 ====================

    /** {@code system_menu.permission} 的非空去重集合，计划器 R2 反向展开表的唯一来源 */
    Set<String> menuPermissions() {
        Set<String> out = new LinkedHashSet<>();
        for (MenuDO m : menus) {
            if (StrUtil.isNotBlank(m.getPermission())) {
                out.add(m.getPermission());
            }
        }
        return out;
    }

    /** {@code system_menu} 里 type≠按钮(3) 的菜单编号，计划器 R1 挑 menu_id 的优先级依据 */
    Set<Long> navMenuIds() {
        Set<Long> out = new LinkedHashSet<>();
        for (MenuDO m : menus) {
            if (m.getType() == null || m.getType() != 3) {
                out.add(m.getId());
            }
        }
        return out;
    }

    /**
     * 待测的权限串全集：{@code sys_resource.permission} 的非空去重值
     *
     * <p>为什么以资源表为准而不是菜单表：实测「菜单有而资源表没有的权限串 = 0」，
     * 菜单集合是资源集合的子集，取资源表就已经把菜单表全覆盖了。
     * 而 {@code @ss.hasPermission} 的入参本来就来自这两个地方
     */
    List<String> allPermissions() {
        Set<String> out = new LinkedHashSet<>();
        for (ResourceDO r : resources) {
            if (StrUtil.isNotBlank(r.getPermission())) {
                out.add(r.getPermission());
            }
        }
        List<String> sorted = new ArrayList<>(out);
        Collections.sort(sorted);   // 排序：失败信息的编号在两次运行之间可比
        return sorted;
    }

    /** 授权表里出现过的全部主体元组，{@code hasExplicitDeny} 按这个跑 */
    List<String[]> grantSubjectTuples() {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> out = new ArrayList<>();
        for (GrantDO g : grants) {
            String code = StrUtil.emptyToNull(g.getSubjectCode());
            String key = g.getSubjectType() + "\u0000" + code;
            if (seen.add(key)) {
                out.add(new String[]{g.getSubjectType(), code});
            }
        }
        return out;
    }

    /**
     * 祖先图必须是空的，这条断言才成立
     *
     * <p>自校验里 {@code SubjectGraphLoader} 被打桩成「返回一张空图」。
     * 实测 {@code sys_subject} 与 {@code sys_subject_member} 都是 0 行，所以这个桩是忠实的 ——
     * 但那是<b>实测</b>，不是设计保证。哪天有人配了角色继承，这个桩就会静默地把闭包算小，
     * 于是校验少覆盖一批主体，而它照样全绿。
     *
     * <p>所以这里把它变成一个<b>会失败的前置条件</b>：真出现了非空，
     * 就得去读 {@link SubjectGraphLoader} 并把边真的建出来，而不是让校验继续假装图是空的
     */
    void assertSubjectGraphEmpty() {
        if (subjectRows != 0 || subjectMemberRows != 0) {
            throw new IllegalStateException(String.format(
                    "主体图不再是空的（sys_subject=%d 行、sys_subject_member=%d 行）。"
                            + "自校验里 SubjectGraphLoader 被打桩成返回空图，现在已经不忠实了："
                            + "去读 SubjectGraphLoader#load，按同一口径把边建出来再跑",
                    subjectRows, subjectMemberRows));
        }
    }

    // ==================== 只读访问 ====================

    List<ResourceDO> resources() {
        return resources;
    }

    List<GrantDO> grants() {
        return grants;
    }

    List<MenuDO> menus() {
        return menus;
    }

    List<AdminUserDO> users() {
        return users;
    }

    /** 这个用户的角色编号集合。与 {@code PermissionServiceImpl#getUserRoleIdListByUserId} 同口径：不过滤角色状态 */
    Set<Long> roleIdsOf(Long userId) {
        return roleIdsByUser.getOrDefault(userId, Set.of());
    }

    Collection<RoleDO> rolesOf(Collection<Long> roleIds) {
        List<RoleDO> out = new ArrayList<>(roleIds.size());
        for (Long id : roleIds) {
            RoleDO role = rolesById.get(id);
            if (role != null) {
                out.add(role);
            }
        }
        return out;
    }

    AdminUserDO userOf(Long userId) {
        for (AdminUserDO u : users) {
            if (u.getId().equals(userId)) {
                return u;
            }
        }
        return null;
    }

    Collection<PostDO> postsOf(Collection<Long> postIds) {
        List<PostDO> out = new ArrayList<>(postIds.size());
        for (Long id : postIds) {
            PostDO post = postsById.get(id);
            if (post != null) {
                out.add(post);
            }
        }
        return out;
    }

    DeptDO deptOf(Long deptId) {
        return deptId == null ? null : deptsById.get(deptId);
    }

    /**
     * 沿 {@code parent_id} 向上取全部祖先部门
     *
     * <p>与 {@code DeptServiceImpl#getParentDeptList} 同口径：自带 visited 集合，
     * 脏数据成环时提前结束而不是栈溢出
     */
    List<DeptDO> parentDeptsOf(Long deptId) {
        List<DeptDO> out = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Long cursor = deptId;
        while (cursor != null && visited.add(cursor)) {
            DeptDO dept = deptsById.get(cursor);
            if (dept == null) {
                break;
            }
            DeptDO parent = dept.getParentId() == null ? null : deptsById.get(dept.getParentId());
            if (parent == null) {
                break;
            }
            out.add(parent);
            cursor = parent.getId();
        }
        return out;
    }

    // ==================== 解析 ====================

    private static int size(JSONObject doc, String key) {
        JSONArray arr = doc.getJSONArray(key);
        return arr == null ? 0 : arr.size();
    }

    private static <T> Map<Long, T> index(List<T> rows, java.util.function.Function<T, Long> idOf) {
        Map<Long, T> out = new HashMap<>(rows.size());
        for (T row : rows) {
            out.put(idOf.apply(row), row);
        }
        return out;
    }

    private static JSONArray array(JSONObject doc, String key) {
        JSONArray arr = doc.getJSONArray(key);
        if (arr == null) {
            throw new IllegalStateException("快照里缺 " + key + " 这一项 —— 导出脚本与本类口径漂移了");
        }
        return arr;
    }

    private static List<ResourceDO> resources(JSONObject doc) {
        JSONArray arr = array(doc, "resources");
        List<ResourceDO> out = new ArrayList<>(arr.size());
        for (Object o : arr) {
            JSONObject j = (JSONObject) o;
            ResourceDO r = new ResourceDO();
            r.setId(j.getLong("id"));
            r.setPermission(j.getStr("permission"));
            r.setName(j.getStr("name"));
            r.setType(j.getStr("type"));
            r.setParentId(j.getLong("parentId"));
            r.setPath(j.getStr("path"));
            Integer depth = j.getInt("depth");
            r.setDepth(depth == null ? null : depth.shortValue());
            r.setSort(j.getInt("sort"));
            r.setDefaultEffect(j.getStr("defaultEffect"));
            r.setStatus(j.getInt("status"));
            r.setMenuId(j.getLong("menuId"));
            r.setDatasetTable(j.getStr("datasetTable"));
            r.setSharePolicy(j.getStr("sharePolicy"));
            out.add(r);
        }
        return out;
    }

    private static List<GrantDO> grants(JSONObject doc) {
        JSONArray arr = array(doc, "grants");
        List<GrantDO> out = new ArrayList<>(arr.size());
        for (Object o : arr) {
            JSONObject j = (JSONObject) o;
            GrantDO g = new GrantDO();
            g.setId(j.getLong("id"));
            g.setSubjectType(j.getStr("subjectType"));
            g.setSubjectRefId(j.getLong("subjectRefId"));
            g.setSubjectCode(j.getStr("subjectCode"));
            g.setResourceId(j.getLong("resourceId"));
            g.setContextResourceId(j.getLong("contextResourceId"));
            g.setAction(j.getStr("action"));
            g.setEffect(j.getStr("effect"));
            g.setRuleId(j.getLong("ruleId"));
            g.setFieldMode(j.getStr("fieldMode"));
            String expire = j.getStr("expireTime");
            g.setExpireTime(StrUtil.isBlank(expire) ? null : LocalDateTime.parse(expire));
            g.setTenantId(j.getLong("tenantId"));
            out.add(g);
        }
        return out;
    }

    private static List<MenuDO> menus(JSONObject doc) {
        JSONArray arr = array(doc, "menus");
        List<MenuDO> out = new ArrayList<>(arr.size());
        for (Object o : arr) {
            JSONObject j = (JSONObject) o;
            MenuDO m = new MenuDO();
            m.setId(j.getLong("id"));
            m.setName(j.getStr("name"));
            m.setPermission(j.getStr("permission"));
            m.setType(j.getInt("type"));
            m.setSort(j.getInt("sort"));
            m.setParentId(j.getLong("parentId"));
            m.setStatus(j.getInt("status"));
            m.setVisible(j.getBool("visible"));
            out.add(m);
        }
        return out;
    }

    private static List<AdminUserDO> users(JSONObject doc) {
        JSONArray arr = array(doc, "users");
        List<AdminUserDO> out = new ArrayList<>(arr.size());
        for (Object o : arr) {
            JSONObject j = (JSONObject) o;
            AdminUserDO u = new AdminUserDO();
            u.setId(j.getLong("id"));
            u.setDeptId(j.getLong("deptId"));
            u.setPostIds(postIds(j.getStr("postIds", "")));
            u.setStatus(j.getInt("status"));
            u.setTenantId(j.getLong("tenantId"));
            out.add(u);
        }
        return out;
    }

    /**
     * 解析 {@code system_users.post_ids}
     *
     * <p>🔴 这一列是 <b>JSON 数组文本</b>（{@code [1,2]} / {@code []} / NULL），不是逗号分隔 ——
     * 生产侧靠 {@code AdminUserDO#postIds} 上的 {@code JacksonTypeHandler} 转成 {@code Set<Long>}。
     * 按逗号 split 会得到 {@code "[1"} 和 {@code "2]"}，第一片直接 {@code NumberFormatException}。
     *
     * <p>这里显式解析而不是把整行丢给 {@code JSONUtil.toBean}，理由与本类其它字段相同：
     * 这是一次不可回滚迁移的唯一一道网，「哪个键对应哪个字段」必须写在能读的地方。
     * 岗位是主体闭包的种子之一，解析错了的表现是<b>闭包变小、校验少覆盖一批授权行，然后全绿</b>
     */
    private static Set<Long> postIds(String raw) {
        Set<Long> out = new LinkedHashSet<>();
        if (StrUtil.isBlank(raw)) {
            return out;
        }
        for (Object item : JSONUtil.parseArray(raw)) {
            out.add(Long.parseLong(item.toString().trim()));
        }
        return out;
    }

    private static Map<Long, Set<Long>> userRoles(JSONObject doc) {
        JSONArray arr = array(doc, "userRoles");
        Map<Long, Set<Long>> out = new HashMap<>();
        for (Object o : arr) {
            JSONObject j = (JSONObject) o;
            out.computeIfAbsent(j.getLong("userId"), k -> new LinkedHashSet<>()).add(j.getLong("roleId"));
        }
        return out;
    }

    private static List<RoleDO> roles(JSONObject doc) {
        JSONArray arr = array(doc, "roles");
        List<RoleDO> out = new ArrayList<>(arr.size());
        for (Object o : arr) {
            JSONObject j = (JSONObject) o;
            RoleDO r = new RoleDO();
            r.setId(j.getLong("id"));
            r.setCode(j.getStr("code"));
            r.setStatus(j.getInt("status"));
            r.setType(j.getInt("type"));
            r.setTenantId(j.getLong("tenantId"));
            out.add(r);
        }
        return out;
    }

    private static List<PostDO> posts(JSONObject doc) {
        JSONArray arr = array(doc, "posts");
        List<PostDO> out = new ArrayList<>(arr.size());
        for (Object o : arr) {
            JSONObject j = (JSONObject) o;
            PostDO p = new PostDO();
            p.setId(j.getLong("id"));
            p.setCode(j.getStr("code"));
            p.setStatus(j.getInt("status"));
            out.add(p);
        }
        return out;
    }

    private static List<DeptDO> depts(JSONObject doc) {
        JSONArray arr = array(doc, "depts");
        List<DeptDO> out = new ArrayList<>(arr.size());
        for (Object o : arr) {
            JSONObject j = (JSONObject) o;
            DeptDO d = new DeptDO();
            d.setId(j.getLong("id"));
            d.setParentId(j.getLong("parentId"));
            d.setStatus(j.getInt("status"));
            out.add(d);
        }
        return out;
    }

}
