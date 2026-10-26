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
 * 「授权没绑规则」时的行为 —— 旧 {@code data_scope} 的 ALL 语义
 *
 * <h3>⚠️ 这批用例守的是 data_scope 收敛的等价性</h3>
 *
 * 旧体系里多角色的数据范围取<b>并集</b>，ALL（全部数据）胜出：
 * 一个人既有超管角色（ALL）又有普通角色（本部门），他看到的是全部。
 *
 * 新体系若把「ALLOW 且没绑规则」的授权直接滤掉，这个用户就只剩下
 * 「本部门」那条规则参与合并 —— <b>迁移后权限凭空收紧，且不报任何错</b>。
 * 实测受影响的是 hrmgr 这类「普通角色 + 业务管理员角色」的账号。
 *
 * 所以「ALLOW 无规则」必须落成<b>恒真</b>参与 OR，而不是被跳过。
 *
 * 反方向同样要守住：{@code DENY} 无规则表达「这个主体完全不能看这张表」，
 * 必须落成恒真的 DENY，否则一条配下去的禁止令会静默失效。
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class DataRowFilterUnrestrictedGrantTest extends BaseMockitoUnitTest {

    private static final String TABLE = "system_users";
    private static final Long USER_ID = 1000L;
    private static final Long RESOURCE_ID = 10L;
    private static final Long RULE_ID = 100L;

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

    private DataRowFilterResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DataRowFilterResolver();
        ReflectionTestUtils.setField(resolver, "subjectClosureService", subjectClosureService);
        ReflectionTestUtils.setField(resolver, "resourceMapper", resourceMapper);
        ReflectionTestUtils.setField(resolver, "grantMapper", grantMapper);
        ReflectionTestUtils.setField(resolver, "ruleMapper", ruleMapper);
        ReflectionTestUtils.setField(resolver, "conditionTreeEvaluator", conditionTreeEvaluator);
        ReflectionTestUtils.setField(resolver, "variableProviders", List.of());

        when(subjectClosureService.getSubjectKeysByUserId(USER_ID))
                .thenReturn(Set.of(SubjectKey.ofUser(USER_ID)));
        ResourceDO dataset = new ResourceDO();
        dataset.setId(RESOURCE_ID);
        dataset.setPermission("system:user");
        dataset.setDatasetTable(TABLE);
        when(resourceMapper.selectListByDatasetTable(TABLE)).thenReturn(List.of(dataset));
    }

    /** 一条【没绑规则】的授权 —— 对应旧 data_scope 的 ALL */
    private GrantDO unrestrictedGrant(Long id, String effect) {
        GrantDO grant = new GrantDO();
        grant.setId(id);
        grant.setRuleId(null); // 关键：没有规则
        grant.setResourceId(RESOURCE_ID);
        grant.setEffect(effect);
        grant.setSubjectType("USER");
        grant.setSubjectRefId(USER_ID);
        return grant;
    }

    /** 一条【绑了规则】的授权，规则求值成「本部门」 */
    private GrantDO restrictedGrant(Long id, String effect) {
        GrantDO grant = new GrantDO();
        grant.setId(id);
        grant.setRuleId(RULE_ID);
        grant.setResourceId(RESOURCE_ID);
        grant.setEffect(effect);
        grant.setSubjectType("USER");
        grant.setSubjectRefId(USER_ID);
        return grant;
    }

    private void ruleEvaluatesToDeptOnly() {
        RuleDO rule = new RuleDO();
        rule.setId(RULE_ID);
        rule.setTemplate("BUILT_IN_DEPT_ONLY");
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule);
        when(conditionTreeEvaluator.evaluate(any(ConditionNode.class), any(), anyMap(), any(), any()))
                .thenReturn(new ConditionTreeEvaluator.SqlFragment("dept_id = ?", List.of(107L)));
    }

    @Test
    @DisplayName("全部授权都没绑规则 → 不过滤（与历史行为一致）")
    void testAllGrantsWithoutRulePass() {
        when(grantMapper.selectListByResourceIds(anySet()))
                .thenReturn(List.of(unrestrictedGrant(1L, "ALLOW"), unrestrictedGrant(2L, "ALLOW")));

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertFalse(result.isFiltered(), "没有任何行级配置时不该追加过滤条件");
    }

    @Test
    @DisplayName("🔴 ALL 角色 + 受限角色 → 必须全放行，不能被受限角色收紧")
    void testUnrestrictedAllowWinsOverRestricted() {
        // 对应真实场景：hrmgr 同时有「普通角色(本部门)」和「CRM 管理员(全部数据)」
        when(grantMapper.selectListByResourceIds(anySet()))
                .thenReturn(List.of(restrictedGrant(1L, "ALLOW"), unrestrictedGrant(2L, "ALLOW")));
        ruleEvaluatesToDeptOnly();

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        // OR(本部门, 恒真) = 恒真 → 实际能看到全部行
        assertTrue(result.getSql() == null || result.getSql().contains("1 = 1"),
                "ALL 角色必须参与 OR 并胜出，实际：" + result.getSql());
    }

    @Test
    @DisplayName("只有受限角色 → 按规则过滤")
    void testOnlyRestrictedGrantFilters() {
        when(grantMapper.selectListByResourceIds(anySet()))
                .thenReturn(List.of(restrictedGrant(1L, "ALLOW")));
        ruleEvaluatesToDeptOnly();

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.isFiltered());
        assertTrue(result.getSql().contains("dept_id"),
                "应当落成部门条件，实际：" + result.getSql());
    }

    @Test
    @DisplayName("🔴 DENY 且没绑规则 → 整表拒绝，不能静默失效")
    void testUnrestrictedDenyBlocksAll() {
        when(grantMapper.selectListByResourceIds(anySet()))
                .thenReturn(List.of(unrestrictedGrant(1L, "ALLOW"), unrestrictedGrant(2L, "DENY")));

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.isFiltered(), "配了 DENY 却不过滤，等于这条禁止令没生效");
        // ALLOW 恒真 AND NOT(DENY 恒真) → 一行都看不到
        assertTrue(result.getSql().contains("NOT"),
                "应当落成 NOT(恒真)，实际：" + result.getSql());
    }

    @Test
    @DisplayName("DENY 无规则 + ALLOW 有规则 → 仍然整表拒绝")
    void testUnrestrictedDenyBeatsRestrictedAllow() {
        when(grantMapper.selectListByResourceIds(anySet()))
                .thenReturn(List.of(restrictedGrant(1L, "ALLOW"), unrestrictedGrant(2L, "DENY")));
        ruleEvaluatesToDeptOnly();

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.isFiltered());
        assertTrue(result.getSql().contains("NOT"),
                "DENY 必须收紧 ALLOW 的结果，实际：" + result.getSql());
    }

    @Test
    @DisplayName("无任何命中授权 + 数据集默认 ALLOW → 不过滤（历史行为不变）")
    void testNoMatchedGrantWithAllowDefaultPasses() {
        datasetDefaultEffect("ALLOW");
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of());

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertFalse(result.isFiltered(), "没配过行级权限的表照旧不过滤");
    }

    @Test
    @DisplayName("🔴 无任何命中授权 + 数据集默认 DENY → 整表拒绝（default_effect 不是死配置）")
    void testNoMatchedGrantWithDenyDefaultBlocksAll() {
        datasetDefaultEffect("DENY");
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of());

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.isFiltered(), "管理员把默认效果配成 DENY 就必须生效");
        assertTrue(result.isDenyAll(), "应当是全拒而不是附加条件，实际 sql：" + result.getSql());
    }

    /** 重挂一个带 default_effect 的数据集资源 */
    private void datasetDefaultEffect(String effect) {
        ResourceDO dataset = new ResourceDO();
        dataset.setId(RESOURCE_ID);
        dataset.setPermission("system:user");
        dataset.setDatasetTable(TABLE);
        dataset.setDefaultEffect(effect);
        when(resourceMapper.selectListByDatasetTable(TABLE)).thenReturn(List.of(dataset));
    }

}
