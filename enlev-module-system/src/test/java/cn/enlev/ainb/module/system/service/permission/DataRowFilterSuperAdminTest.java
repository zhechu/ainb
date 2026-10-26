package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.biz.system.permission.dto.DataRowFilterRespDTO;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RuleDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.RuleMapper;
import cn.enlev.ainb.module.system.framework.permission.core.rule.ConditionNode;
import cn.enlev.ainb.module.system.framework.permission.core.rule.ConditionTreeEvaluator;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 行级过滤对超管短路
 *
 * <h3>这批用例守的是一次真实事故</h3>
 *
 * 超级管理员角色在 {@code system:dept} / {@code system:user} 上那条
 * 「无规则的 ALLOW」（= 旧 {@code data_scope} 的 ALL）被套餐重算误删之后，
 * 超管就只剩它身上另一个普通角色的「本部门数据」规则生效：
 * 组织管理只显示 1 个部门、用户管理只显示 1 个用户，<b>且不报任何错</b>。
 *
 * 删除那一侧的修法见 {@code PermissionServiceImpl#filterMenuDerived}；
 * 本类守的是另一侧：行级过滤不再依赖授权数据是否完好。
 * 与 {@code PermissionServiceImpl#hasAnyPermissions}、
 * {@code GrantDecisionService#listAllowedPermissions} / {@code #listAllowedMenuIds}
 * 的超管短路同一条不变量。
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class DataRowFilterSuperAdminTest extends BaseMockitoUnitTest {

    private static final String TABLE = "system_users";
    private static final Long USER_ID = 1L;
    private static final Long SUPER_ADMIN_ROLE_ID = 1L;
    private static final Long COMMON_ROLE_ID = 2L;
    private static final Long RESOURCE_ID = 2061L;
    private static final Long RULE_ID = 2L;

    @Mock
    private SubjectClosureService subjectClosureService;
    @Mock
    private ResourceMapper resourceMapper;
    @Mock
    private GrantMapper grantMapper;
    @Mock
    private RuleMapper ruleMapper;
    @Mock
    private ConditionTreeEvaluator conditionTreeEvaluator;
    @Mock
    private RoleService roleService;

    private DataRowFilterResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DataRowFilterResolver();
        ReflectionTestUtils.setField(resolver, "subjectClosureService", subjectClosureService);
        ReflectionTestUtils.setField(resolver, "resourceMapper", resourceMapper);
        ReflectionTestUtils.setField(resolver, "grantMapper", grantMapper);
        ReflectionTestUtils.setField(resolver, "ruleMapper", ruleMapper);
        ReflectionTestUtils.setField(resolver, "conditionTreeEvaluator", conditionTreeEvaluator);
        ReflectionTestUtils.setField(resolver, "roleService", roleService);
        ReflectionTestUtils.setField(resolver, "variableProviders", List.of());

        ResourceDO dataset = new ResourceDO();
        dataset.setId(RESOURCE_ID);
        dataset.setPermission("system:user");
        dataset.setDatasetTable(TABLE);
        when(resourceMapper.selectListByDatasetTable(TABLE)).thenReturn(List.of(dataset));
    }

    /** 闭包 = 用户 + 它的角色 */
    private void closureWithRoles(Long... roleIds) {
        Set<SubjectKey> subjects = new java.util.LinkedHashSet<>();
        subjects.add(SubjectKey.ofUser(USER_ID));
        for (Long roleId : roleIds) {
            subjects.add(SubjectKey.ofRole(roleId));
        }
        when(subjectClosureService.getSubjectKeysByUserId(USER_ID)).thenReturn(subjects);
    }

    /** 普通角色那条「本部门数据」授权 —— 事故里唯一还活着的一条 */
    private void onlyDeptOnlyGrant() {
        onlyDeptOnlyGrant(SubjectTypeEnum.ROLE.name(), COMMON_ROLE_ID);
    }

    private void onlyDeptOnlyGrant(String subjectType, Long subjectRefId) {
        GrantDO grant = new GrantDO();
        grant.setId(9000100213L);
        grant.setRuleId(RULE_ID);
        grant.setResourceId(RESOURCE_ID);
        grant.setEffect("ALLOW");
        grant.setSubjectType(subjectType);
        grant.setSubjectRefId(subjectRefId);
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of(grant));

        RuleDO rule = new RuleDO();
        rule.setId(RULE_ID);
        rule.setTemplate("BUILT_IN_DEPT_ONLY");
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule);
        when(conditionTreeEvaluator.evaluate(any(ConditionNode.class), any(), anyMap(), any(), any()))
                .thenReturn(new ConditionTreeEvaluator.SqlFragment("dept_id = ?", List.of(103L)));
    }

    @Test
    @DisplayName("🔴 超管 + 只剩「本部门数据」授权 → 不过滤（复现事故：授权被误删后超管被普通角色收窄）")
    void testSuperAdminNotFiltered() {
        closureWithRoles(SUPER_ADMIN_ROLE_ID, COMMON_ROLE_ID);
        when(roleService.hasAnySuperAdmin(anySet())).thenReturn(true);
        onlyDeptOnlyGrant();

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertFalse(result.isFiltered(), "超管不该受行级过滤，实际：" + result.getSql());
        // 短路发生在查授权之前，规则压根不该被求值
        verify(ruleMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("非超管 + 同样的授权 → 仍然按规则过滤（短路没有把所有人放开）")
    void testNormalUserStillFiltered() {
        closureWithRoles(COMMON_ROLE_ID);
        when(roleService.hasAnySuperAdmin(anySet())).thenReturn(false);
        onlyDeptOnlyGrant();

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.isFiltered());
        assertTrue(result.getSql().contains("dept_id"), "应当落成部门条件，实际：" + result.getSql());
    }

    @Test
    @DisplayName("超管 + 一条无规则的 DENY → 仍然不过滤（短路优先于禁止令）")
    void testSuperAdminBeatsDeny() {
        closureWithRoles(SUPER_ADMIN_ROLE_ID);
        when(roleService.hasAnySuperAdmin(anySet())).thenReturn(true);
        GrantDO deny = new GrantDO();
        deny.setId(1L);
        deny.setRuleId(null);
        deny.setResourceId(RESOURCE_ID);
        deny.setEffect("DENY");
        deny.setSubjectType("ROLE");
        deny.setSubjectRefId(SUPER_ADMIN_ROLE_ID);
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of(deny));

        assertFalse(resolver.resolve(USER_ID, TABLE, "").isFiltered());
    }

    @Test
    @DisplayName("超管判定抛异常 → 按非超管处理，照常过滤（判不出来时宁可少放行）")
    void testSuperAdminCheckFailureFallsBackToNormal() {
        closureWithRoles(COMMON_ROLE_ID);
        when(roleService.hasAnySuperAdmin(anySet())).thenThrow(new RuntimeException("角色缓存挂了"));
        onlyDeptOnlyGrant();

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.isFiltered(), "判定失败不能变成放行全部数据");
    }

    @Test
    @DisplayName("闭包里没有角色主体 → 不去问 RoleService")
    void testNoRoleSubjectSkipsCheck() {
        when(subjectClosureService.getSubjectKeysByUserId(USER_ID))
                .thenReturn(Set.of(SubjectKey.ofUser(USER_ID)));
        // 授权挂在 USER 主体上：闭包里既然没有角色，角色授权根本不会命中，
        // 那样断言的会是「没授权所以不过滤」，而不是「没角色所以不问超管」
        onlyDeptOnlyGrant(SubjectTypeEnum.USER.name(), USER_ID);

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.isFiltered());
        verify(roleService, never()).hasAnySuperAdmin(anySet());
    }

}
