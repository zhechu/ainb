package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.biz.system.permission.dto.DataRowFilterRespDTO;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
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
 * 「规则被停用」时的行为
 *
 * <h3>这批用例钉住的是一个曾经【完全不生效】的开关</h3>
 *
 * {@code sys_rule.status} 有列、有 DDL 默认值、规则页有 StatusSwitch、
 * 后端有 {@code PUT /system/rule/update} 能写它 —— 而判定链上没有任何一处读它。
 * 于是管理员把一条规则关掉，界面提示「已关闭」，行过滤照旧按它算。
 * 这类缺陷最坏的地方是它<b>不报错也不留痕</b>：从界面上看，开关是好的。
 *
 * <h3>为什么语义是「不参与」而不是「往紧里落」</h3>
 *
 * 同一条循环里，「规则求不出条件」是落恒假 / 全拒的（见
 * {@link DataRowFilterUnevaluableRuleTest}）。停用没有照抄那套，因为两者性质不同：
 * 求不出条件是事故，我们不知道管理员想挡住什么；停用是他亲手按的开关。
 * 若停用也往紧里落，停掉一条 DENY 规则本该解除禁令，结果变成整表不可见 ——
 * 开关的含义就反了。详细论证写在 {@code resolve} 里那段注释。
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class DataRowFilterDisabledRuleTest extends BaseMockitoUnitTest {

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
        dataset.setPermission("infra:form-design-run:demo");
        when(resourceMapper.selectListByDatasetTable(TABLE)).thenReturn(List.of(dataset));
    }

    private GrantDO grant(Long id, String effect, Long ruleId) {
        GrantDO grant = new GrantDO();
        grant.setId(id);
        grant.setRuleId(ruleId);
        grant.setResourceId(RESOURCE_ID);
        grant.setEffect(effect);
        grant.setSubjectType("USER");
        grant.setSubjectRefId(USER_ID);
        return grant;
    }

    /**
     * 一条启用规则，求值器给它一个可辨识的条件
     *
     * @param sql 片段文本，用例靠它判断「哪条规则真的参与了」
     */
    private void enabledRule(Long ruleId, String sql) {
        rule(ruleId, CommonStatusEnum.ENABLE.getStatus());
        when(conditionTreeEvaluator.evaluate(any(ConditionNode.class), eq(RESOURCE_ID),
                anyMap(), any(), any()))
                .thenReturn(new ConditionTreeEvaluator.SqlFragment(sql, List.of(USER_ID)));
    }

    private void disabledRule(Long ruleId) {
        rule(ruleId, CommonStatusEnum.DISABLE.getStatus());
    }

    private void rule(Long ruleId, Integer status) {
        RuleDO rule = new RuleDO();
        rule.setId(ruleId);
        rule.setName("规则" + ruleId);
        rule.setTemplate("BUILT_IN_OWNER_ONLY");
        rule.setStatus(status);
        when(ruleMapper.selectById(ruleId)).thenReturn(rule);
    }

    // ========== 停用的规则不参与判定 ==========

    @Test
    @DisplayName("🔴 停用的 ALLOW 规则不再生效：唯一一条时这张表回到「不过滤」")
    void testDisabledAllowRuleDropsOut() {
        when(grantMapper.selectListByResourceIds(anySet()))
                .thenReturn(List.of(grant(1L, "ALLOW", 100L)));
        disabledRule(100L);

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertFalse(result.isFiltered(),
                "停用之后还在按它过滤，那这个开关就是装饰");
        verify(conditionTreeEvaluator, never()).evaluate(any(), any(), anyMap(), any(), any());
    }

    @Test
    @DisplayName("🔴 停用的 DENY 规则不再挡行 —— 别把「解除禁令」做成「整表不可见」")
    void testDisabledDenyRuleDropsOut() {
        when(grantMapper.selectListByResourceIds(anySet()))
                .thenReturn(List.of(grant(1L, "DENY", 100L)));
        disabledRule(100L);

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertFalse(result.isDenyAll(),
                "往紧里落的话，停用 DENY 规则会变成整表拒绝，管理员再没有别的办法解除它");
        assertFalse(result.isFiltered());
    }

    @Test
    @DisplayName("两条 ALLOW 里停用一条：启用那条照常生效，不受牵连")
    void testDisabledAllowDoesNotAffectEnabledSibling() {
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of(
                grant(1L, "ALLOW", 100L),
                grant(2L, "ALLOW", 200L)));
        enabledRule(100L, "creator = ?");
        disabledRule(200L);

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.isFiltered());
        assertTrue(result.getSql().contains("creator = ?"),
                "启用的规则被停用的那条拖垮了：" + result.getSql());
        // ALLOW 之间是 OR；只剩一条时不该冒出 OR，更不该冒出恒假
        assertFalse(result.getSql().contains(" OR "), "只剩一条 ALLOW 却拼出了 OR：" + result.getSql());
        assertFalse(result.getSql().contains("1 = 0"), "停用的规则不该贡献恒假条件");
    }

    @Test
    @DisplayName("停用的 DENY + 启用的 ALLOW：只出 ALLOW 的条件，不出现 NOT")
    void testDisabledDenyWithEnabledAllow() {
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of(
                grant(1L, "ALLOW", 100L),
                grant(2L, "DENY", 200L)));
        enabledRule(100L, "creator = ?");
        disabledRule(200L);

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.getSql().contains("creator = ?"));
        assertFalse(result.getSql().contains("NOT"),
                "停用的 DENY 还在参与合并：" + result.getSql());
    }

    @Test
    @DisplayName("停用的 ALLOW + 启用的 DENY：禁令照常生效")
    void testEnabledDenyStillBlocksWhenAllowDisabled() {
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of(
                grant(1L, "ALLOW", 100L),
                grant(2L, "DENY", 200L)));
        disabledRule(100L);
        enabledRule(200L, "status = ?");

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.getSql().contains("NOT ("), "只剩 DENY 时应当是 NOT(...)：" + result.getSql());
        assertTrue(result.getSql().contains("status = ?"));
    }

    // ========== 对照组：别把「没写 status」误判成停用 ==========

    @Test
    @DisplayName("🔴 status 为 null 视为启用 —— 判据必须是「显式停用」")
    void testNullStatusTreatedAsEnabled() {
        // DDL 是 NOT NULL DEFAULT 0，正常库里不会出现 null。
        // 但判据写成 !isEnable(status) 的话，null 会被当成停用 ——
        // 于是任何一处漏设 status 的构造路径（包括本仓库现有的全部单测夹具）
        // 都会让规则集体失效。用 isDisable 才是安全的那一侧
        when(grantMapper.selectListByResourceIds(anySet()))
                .thenReturn(List.of(grant(1L, "ALLOW", 100L)));
        rule(100L, null);
        when(conditionTreeEvaluator.evaluate(any(ConditionNode.class), eq(RESOURCE_ID),
                anyMap(), any(), any()))
                .thenReturn(new ConditionTreeEvaluator.SqlFragment("creator = ?", List.of(USER_ID)));

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.getSql().contains("creator = ?"),
                "status=null 被当成停用了，规则会集体失效：" + result.getSql());
    }

    @Test
    @DisplayName("对照组：status=0（启用）照常生成条件，没有被本次改动波及")
    void testEnabledRuleUnaffected() {
        when(grantMapper.selectListByResourceIds(anySet()))
                .thenReturn(List.of(grant(1L, "ALLOW", 100L)));
        enabledRule(100L, "creator = ?");

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.getSql().contains("creator = ?"));
    }

    // ========== 停用 ≠ 规则不存在 ==========

    @Test
    @DisplayName("🔴 规则行没了仍然是全拒 —— 「停用」放宽，「数据损坏」不放宽")
    void testMissingRuleStillDeniesAll() {
        // 这两件事看着都是「规则不生效」，但方向必须相反：
        // 停用是管理员的决定，规则行消失是数据损坏。
        // 后者若也放宽，删掉一条规则就等于提权，且没有任何痕迹
        when(grantMapper.selectListByResourceIds(anySet()))
                .thenReturn(List.of(grant(1L, "ALLOW", 100L)));
        when(ruleMapper.selectById(100L)).thenReturn(null);

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.isDenyAll());
    }

    @Test
    @DisplayName("停用的规则压根不进求值器：params 坏了也不会因此报错或落恒假")
    void testDisabledRuleNeverEvaluated() {
        // 一条被停用的规则，它的 params 很可能已经过时甚至不合法
        // （停用往往就是因为它算错了）。既然不参与判定，就不该去碰它 ——
        // 碰了就会走进「求不出条件」那条落恒假的分支，与停用的语义打架
        when(grantMapper.selectListByResourceIds(anySet()))
                .thenReturn(List.of(grant(1L, "ALLOW", 100L)));
        RuleDO broken = new RuleDO();
        broken.setId(100L);
        broken.setName("算错的那条");
        broken.setTemplate("BUILT_IN_CUSTOM_TREE");
        broken.setParams(null); // 会让 evaluate 返回 null
        broken.setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(ruleMapper.selectById(100L)).thenReturn(broken);

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertFalse(result.getSql() != null && result.getSql().contains("1 = 0"),
                "停用的规则走了「求不出条件」的恒假分支：" + result.getSql());
        verify(conditionTreeEvaluator, never()).evaluate(any(), any(), anyMap(), any(), any());
    }

}
