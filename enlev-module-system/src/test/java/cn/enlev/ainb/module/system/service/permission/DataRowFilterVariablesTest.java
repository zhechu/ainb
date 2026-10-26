package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.biz.system.permission.dto.DataRowFilterRespDTO;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RuleDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.RuleMapper;
import cn.enlev.ainb.module.system.framework.permission.core.rule.BuiltInVariableProvider;
import cn.enlev.ainb.module.system.framework.permission.core.rule.ConditionNode;
import cn.enlev.ainb.module.system.framework.permission.core.rule.ConditionTreeEvaluator;
import cn.enlev.ainb.module.system.framework.permission.core.rule.RuleVariableProvider;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import cn.enlev.ainb.module.system.service.user.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 规则变量从 {@code resolve} 到求值器这一段的路况
 *
 * <h3>为什么这段要有专门的用例</h3>
 *
 * {@code :currentRoleIds} 曾经恒为空集合（{@code BuiltInVariableProvider} 里写死），
 * 而它自己的单测只能证明「给了闭包就能投影出角色」—— 证明不了
 * <b>{@code resolve} 真的把闭包给了它</b>。这条线断了的话，
 * 两个类各自的测试都是绿的，合起来仍然是坏的。
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class DataRowFilterVariablesTest extends BaseMockitoUnitTest {

    private static final String TABLE = "biz_order";
    private static final Long USER_ID = 1000L;
    private static final Long RESOURCE_ID = 10L;

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
    private AdminUserService adminUserService;
    @Mock
    private DeptService deptService;
    /**
     * 本用例的主体闭包里带 ROLE，会走到超管判定。不注进来就是 null，
     * 靠 isSuperAdmin 里那个 catch 兜住 NPE —— 能跑，但每个用例都会打一条误导性 WARN
     */
    @Mock
    private RoleService roleService;

    @Captor
    private ArgumentCaptor<Map<String, Object>> variablesCaptor;

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
        when(roleService.hasAnySuperAdmin(anySet())).thenReturn(false);

        // 用【真的】内置变量提供者，只有它是真的，这条线才算被测到
        BuiltInVariableProvider provider = new BuiltInVariableProvider();
        ReflectionTestUtils.setField(provider, "adminUserService", adminUserService);
        ReflectionTestUtils.setField(provider, "deptService", deptService);
        ReflectionTestUtils.setField(resolver, "variableProviders", List.<RuleVariableProvider>of(provider));

        ResourceDO dataset = new ResourceDO();
        dataset.setId(RESOURCE_ID);
        dataset.setPermission("infra:form-design-run:demo");
        when(resourceMapper.selectListByDatasetTable(TABLE)).thenReturn(List.of(dataset));

        GrantDO grant = new GrantDO();
        grant.setId(1L);
        grant.setRuleId(100L);
        grant.setResourceId(RESOURCE_ID);
        grant.setEffect("ALLOW");
        grant.setSubjectType("USER");
        grant.setSubjectRefId(USER_ID);
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of(grant));

        RuleDO rule = new RuleDO();
        rule.setId(100L);
        rule.setTemplate("BUILT_IN_OWNER_ONLY");
        when(ruleMapper.selectById(100L)).thenReturn(rule);

        when(conditionTreeEvaluator.evaluate(any(ConditionNode.class), eq(RESOURCE_ID),
                anyMap(), any(), any()))
                .thenReturn(new ConditionTreeEvaluator.SqlFragment("creator = ?", List.of(USER_ID)));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 判定用的主体闭包被透给变量提供者：:currentRoleIds 拿到的就是那批角色")
    void testClosureReachesVariableProvider() {
        when(subjectClosureService.getSubjectKeysByUserId(USER_ID)).thenReturn(Set.of(
                SubjectKey.ofUser(USER_ID),
                SubjectKey.ofRole(5L),
                SubjectKey.ofRole(6L),
                SubjectKey.ofAllUsers()));

        resolver.resolve(USER_ID, TABLE, "");

        verify(conditionTreeEvaluator).evaluate(any(ConditionNode.class), eq(RESOURCE_ID),
                variablesCaptor.capture(), any(), any());
        Object roleIds = variablesCaptor.getValue().get("currentRoleIds");
        assertEquals(Set.of(5L, 6L), Set.copyOf((Set<Long>) roleIds),
                "闭包没被透下去的话这里会是空集合，规则里的 :currentRoleIds 就永远是空的");
    }

    @Test
    @DisplayName("🔴 变量提供者抛异常 → 全拒，不是「少个变量继续算」")
    void testProviderFailureDeniesAll() {
        // 少一个变量的后果不是「规则宽松一点」：求值器对「变量取不到值」的处置是
        // 丢掉那个条件、且刻意不标 unevaluable（那是为「这个用户没有部门」准备的）。
        // AND 组里少一条 = 过滤变宽 = 提权，而且全程不报错
        when(subjectClosureService.getSubjectKeysByUserId(USER_ID)).thenReturn(Set.of(
                SubjectKey.ofUser(USER_ID), SubjectKey.ofRole(5L)));
        ReflectionTestUtils.setField(resolver, "variableProviders",
                List.<RuleVariableProvider>of((userId, ctx) -> {
                    throw new IllegalStateException("变量提供者炸了");
                }));

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.isDenyAll(), "异常被吞掉就等于把异常变成越权");
        verify(conditionTreeEvaluator, never()).evaluate(any(), any(), anyMap(), any(), any());
    }

    @Test
    @DisplayName("对照组：一切正常时闭包只算一次 —— 变量提供者不该自己去重算它")
    void testClosureComputedOnce() {
        // getSubjectKeysByUserId 内部要取 @Cacheable 在 Redis 上的【全量】主体祖先图，
        // 而 resolve 是每条带行级权限的查询都要走的热路径。多算一次是实打实的开销
        when(subjectClosureService.getSubjectKeysByUserId(USER_ID)).thenReturn(Set.of(
                SubjectKey.ofUser(USER_ID), SubjectKey.ofRole(5L)));

        resolver.resolve(USER_ID, TABLE, "");

        verify(subjectClosureService, times(1)).getSubjectKeysByUserId(USER_ID);
    }

}
