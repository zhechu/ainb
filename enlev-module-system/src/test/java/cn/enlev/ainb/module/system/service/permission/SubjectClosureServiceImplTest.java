package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.dept.DeptDO;
import cn.enlev.ainb.module.system.dal.dataobject.dept.PostDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleDO;
import cn.enlev.ainb.module.system.dal.dataobject.user.AdminUserDO;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionConfiguration;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import cn.enlev.ainb.module.system.service.dept.PostService;
import cn.enlev.ainb.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SubjectClosureServiceImpl} 的单元测试类
 *
 * 用 {@link BaseDbUnitTest} 而不是纯 Mockito，有两个原因：
 * ① 图闭包要走真实的 sys_subject_member 查询，Mock 掉就等于没测；
 * ② {@code getSelf()} 靠 SpringUtil 取代理，没有 Spring 上下文会直接 NPE。
 * 别的模块的 Service（角色/岗位/部门/用户）按惯例 Mock
 *
 * @author 走进新时代
 */
@Import({SubjectClosureServiceImpl.class, SubjectServiceImpl.class,
        SubjectGraphLoader.class, PermissionConfiguration.class})
public class SubjectClosureServiceImplTest extends BaseDbUnitTest {

    private static final Long USER_ID = 1024L;

    @Resource
    private SubjectClosureServiceImpl subjectClosureService;
    @Resource
    private SubjectServiceImpl subjectService;
    @Resource
    private PermissionProperties permissionProperties;

    @MockitoBean
    private PermissionService permissionService;
    @MockitoBean
    private RoleService roleService;
    @MockitoBean
    private PostService postService;
    @MockitoBean
    private DeptService deptService;
    @MockitoBean
    private AdminUserService userService;

    @BeforeEach
    public void setUp() {
        // 配置项是单例 bean，测试之间会串，每个用例开始前先复位
        permissionProperties.getSubject().setPostEnabled(true);
        permissionProperties.getSubject().setDeptEnabled(false);
        permissionProperties.getSubject().setDeptAncestorEnabled(true);
    }

    private static RoleDO role(Long id, Integer status) {
        RoleDO role = new RoleDO();
        role.setId(id);
        role.setStatus(status);
        return role;
    }

    private static PostDO post(Long id, Integer status) {
        PostDO post = new PostDO();
        post.setId(id);
        post.setStatus(status);
        return post;
    }

    private static DeptDO dept(Long id, Integer status) {
        DeptDO dept = new DeptDO();
        dept.setId(id);
        dept.setStatus(status);
        return dept;
    }

    private void mockUser(Long deptId, Set<Long> postIds) {
        AdminUserDO user = new AdminUserDO();
        user.setId(USER_ID);
        user.setDeptId(deptId);
        user.setPostIds(postIds);
        when(userService.getUser(USER_ID)).thenReturn(user);
    }

    @Test
    public void testGetSubjectKeysByUserId_只有用户自己和ALL() {
        when(permissionService.getUserRoleIdListByUserIdFromCache(USER_ID)).thenReturn(Set.of());
        mockUser(null, null);

        Set<SubjectKey> keys = subjectClosureService.getSubjectKeysByUserId(USER_ID);

        // ⚠️ ALL:all-users 恒在闭包里 —— 它是「不限制」的显式表达（S7b）。
        // 没有它的话，流程资源那种 default_effect=DENY 的对象就没法表达「人人可用」，
        // 只能靠判定处开一条「没配 = 放行」的兜底，而那条兜底会让 DENY 永远收不紧
        assertEquals(Set.of(SubjectKey.ofUser(USER_ID), SubjectKey.ofAllUsers()), keys);
    }

    @Test
    public void testGetSubjectKeysByUserId_ALL主体恒在闭包里() {
        when(permissionService.getUserRoleIdListByUserIdFromCache(USER_ID)).thenReturn(Set.of(1L));
        when(roleService.getRoleListFromCache(Set.of(1L))).thenReturn(List.of(
                role(1L, CommonStatusEnum.ENABLE.getStatus())));
        mockUser(null, null);

        assertTrue(subjectClosureService.getSubjectKeysByUserId(USER_ID)
                .contains(SubjectKey.ofAllUsers()));
    }

    @Test
    public void testGetSubjectKeysByUserId_userId为空() {
        // ⚠️ 未登录拿不到 ALL —— 「所有【登录】用户」，内部调用与匿名请求不在其列
        assertTrue(subjectClosureService.getSubjectKeysByUserId(null).isEmpty());
        verifyNoInteractions(permissionService, userService);
    }

    @Test
    public void testGetSubjectKeysByUserId_禁用的角色不算() {
        when(permissionService.getUserRoleIdListByUserIdFromCache(USER_ID)).thenReturn(Set.of(1L, 2L));
        when(roleService.getRoleListFromCache(Set.of(1L, 2L))).thenReturn(List.of(
                role(1L, CommonStatusEnum.ENABLE.getStatus()),
                role(2L, CommonStatusEnum.DISABLE.getStatus())));
        mockUser(null, null);

        Set<SubjectKey> keys = subjectClosureService.getSubjectKeysByUserId(USER_ID);

        assertTrue(keys.contains(SubjectKey.ofRole(1L)));
        // 禁用的角色不该再给任何权限
        assertFalse(keys.contains(SubjectKey.ofRole(2L)));
    }

    @Test
    public void testGetSubjectKeysByUserId_岗位开关() {
        when(permissionService.getUserRoleIdListByUserIdFromCache(USER_ID)).thenReturn(Set.of());
        mockUser(null, Set.of(7L, 8L));
        when(postService.getPostList(Set.of(7L, 8L))).thenReturn(List.of(
                post(7L, CommonStatusEnum.ENABLE.getStatus()),
                post(8L, CommonStatusEnum.DISABLE.getStatus())));

        Set<SubjectKey> keys = subjectClosureService.getSubjectKeysByUserId(USER_ID);
        assertTrue(keys.contains(SubjectKey.ofPost(7L)));
        assertFalse(keys.contains(SubjectKey.ofPost(8L)));

        // 关掉之后，连用户行都不该去查
        permissionProperties.getSubject().setPostEnabled(false);
        reset(userService);
        keys = subjectClosureService.getSubjectKeysByUserId(USER_ID);
        assertEquals(Set.of(SubjectKey.ofUser(USER_ID), SubjectKey.ofAllUsers()), keys);
        verify(userService, never()).getUser(any());
    }

    @Test
    public void testGetSubjectKeysByUserId_部门默认关闭() {
        when(permissionService.getUserRoleIdListByUserIdFromCache(USER_ID)).thenReturn(Set.of());
        mockUser(50L, null);

        Set<SubjectKey> keys = subjectClosureService.getSubjectKeysByUserId(USER_ID);

        // v10 §7.2 第 8 项：DEPT 作为授权主体，模型支持但默认关闭
        assertFalse(keys.contains(SubjectKey.ofDept(50L)));
        verifyNoInteractions(deptService);
    }

    @Test
    public void testGetSubjectKeysByUserId_部门开启时含祖先链() {
        permissionProperties.getSubject().setDeptEnabled(true);
        when(permissionService.getUserRoleIdListByUserIdFromCache(USER_ID)).thenReturn(Set.of());
        mockUser(50L, null);
        when(deptService.getDept(50L)).thenReturn(dept(50L, CommonStatusEnum.ENABLE.getStatus()));
        when(deptService.getParentDeptList(50L)).thenReturn(List.of(
                dept(40L, CommonStatusEnum.ENABLE.getStatus()),
                dept(30L, CommonStatusEnum.DISABLE.getStatus())));

        Set<SubjectKey> keys = subjectClosureService.getSubjectKeysByUserId(USER_ID);

        assertTrue(keys.contains(SubjectKey.ofDept(50L)));
        assertTrue(keys.contains(SubjectKey.ofDept(40L)));
        // 停用的祖先部门不算，与角色、岗位一个口径
        assertFalse(keys.contains(SubjectKey.ofDept(30L)));
    }

    @Test
    public void testGetSubjectKeysByUserId_祖先链可以单独关掉() {
        permissionProperties.getSubject().setDeptEnabled(true);
        permissionProperties.getSubject().setDeptAncestorEnabled(false);
        when(permissionService.getUserRoleIdListByUserIdFromCache(USER_ID)).thenReturn(Set.of());
        mockUser(50L, null);
        when(deptService.getDept(50L)).thenReturn(dept(50L, CommonStatusEnum.ENABLE.getStatus()));

        Set<SubjectKey> keys = subjectClosureService.getSubjectKeysByUserId(USER_ID);

        assertTrue(keys.contains(SubjectKey.ofDept(50L)));
        verify(deptService, never()).getParentDeptList(any());
    }

    @Test
    public void testGetSubjectKeysByUserId_叠加角色继承链() {
        // ROLE:1（销售）← ROLE:2（高级销售）← ROLE:3（销售总监）
        // 用户挂的是 ROLE:3，应该一路继承到 ROLE:1
        subjectService.addMember(SubjectKey.ofRole(1L), SubjectKey.ofRole(2L));
        subjectService.addMember(SubjectKey.ofRole(2L), SubjectKey.ofRole(3L));
        when(permissionService.getUserRoleIdListByUserIdFromCache(USER_ID)).thenReturn(Set.of(3L));
        when(roleService.getRoleListFromCache(Set.of(3L)))
                .thenReturn(List.of(role(3L, CommonStatusEnum.ENABLE.getStatus())));
        mockUser(null, null);

        Set<SubjectKey> keys = subjectClosureService.getSubjectKeysByUserId(USER_ID);

        assertEquals(Set.of(SubjectKey.ofUser(USER_ID), SubjectKey.ofAllUsers(),
                SubjectKey.ofRole(3L), SubjectKey.ofRole(2L), SubjectKey.ofRole(1L)), keys);
    }

    @Test
    public void testGetSubjectKeysByUserId_继承是单向的() {
        // 反过来问：挂了基础角色 ROLE:1 的人，不该拿到 ROLE:2 的权限。
        // 这一条就是「方向写反会静默提权」的回归测试
        subjectService.addMember(SubjectKey.ofRole(1L), SubjectKey.ofRole(2L));
        when(permissionService.getUserRoleIdListByUserIdFromCache(USER_ID)).thenReturn(Set.of(1L));
        when(roleService.getRoleListFromCache(Set.of(1L)))
                .thenReturn(List.of(role(1L, CommonStatusEnum.ENABLE.getStatus())));
        mockUser(null, null);

        Set<SubjectKey> keys = subjectClosureService.getSubjectKeysByUserId(USER_ID);

        assertEquals(Set.of(SubjectKey.ofUser(USER_ID), SubjectKey.ofAllUsers(),
                SubjectKey.ofRole(1L)), keys);
    }

    @Test
    public void testGetSubjectKeysByUserId_用户组走的是同一张图() {
        // 用户组：GROUP ← USER。用户组不需要原生边，成员关系本来就在图里
        Long groupId = subjectService.createGroupSubject("值班组");
        subjectService.addMember(SubjectKey.ofGroup(groupId), SubjectKey.ofUser(USER_ID));
        when(permissionService.getUserRoleIdListByUserIdFromCache(USER_ID)).thenReturn(Set.of());
        mockUser(null, null);

        Set<SubjectKey> keys = subjectClosureService.getSubjectKeysByUserId(USER_ID);

        assertEquals(Set.of(SubjectKey.ofUser(USER_ID), SubjectKey.ofAllUsers(),
                SubjectKey.ofGroup(groupId)), keys);
    }

    @Test
    public void testGetAncestorMapFromCache_停用的容器不进图() {
        subjectService.addMember(SubjectKey.ofRole(1L), SubjectKey.ofRole(2L));
        assertEquals(Set.of(SubjectKey.ofRole(1L)),
                subjectClosureService.getAncestorKeys(SubjectKey.ofRole(2L)));

        // 停掉基础角色，继承它的角色应当立刻失去那批权限
        subjectService.updateSubjectStatus(SubjectKey.ofRole(1L), CommonStatusEnum.DISABLE.getStatus());

        assertTrue(subjectClosureService.getAncestorKeys(SubjectKey.ofRole(2L)).isEmpty());
    }

    @Test
    public void testGetAncestorKeys_空与不存在() {
        assertTrue(subjectClosureService.getAncestorKeys(null).isEmpty());
        assertTrue(subjectClosureService.getAncestorKeys(SubjectKey.ofRole(99L)).isEmpty());
    }

}
