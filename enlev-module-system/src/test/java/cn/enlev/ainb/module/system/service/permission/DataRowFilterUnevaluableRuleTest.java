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
 * 「规则求不出条件」时的行为
 *
 * <h3>⚠️ 这批用例守的是全局 TODO #28</h3>
 *
 * 旧实现里，一条求值失败的规则会被 {@code continue} 跳过。看着人畜无害，
 * 实则是个提权缺陷：若它是<b>唯一</b>的 ALLOW 规则，最终
 * {@code allow} 与 {@code deny} 都为空 → {@code combine} 返回 {@code pass()}
 * → <b>整表不过滤</b>。
 *
 * 也就是说「规则配错了」的后果不是「规则不生效」，而是<b>比不配规则还宽</b>。
 * 而配错规则太容易了：槽位忘了配映射、{@code params} 少个字段，都会走到这里，
 * 且全程没有报错、界面完全正常。
 *
 * 触发路径（都实际存在）：
 * <ul>
 *   <li>{@code STATUS_IN} 的 params 里没有 allowedStatuses → 返回 null</li>
 *   <li>条件树引用的槽位没配映射 → 求值器返回空片段</li>
 *   <li>{@code CUSTOM_TREE} 的 params 为空或没有 conditionTree → 返回 null</li>
 *   <li>规则 JSON 解析异常 → catch 住返回 null</li>
 * </ul>
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class DataRowFilterUnevaluableRuleTest extends BaseMockitoUnitTest {

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

    /** 一条挂了规则的授权 */
    private GrantDO grant(Long id, String effect) {
        GrantDO grant = new GrantDO();
        grant.setId(id);
        grant.setRuleId(100L);
        grant.setResourceId(RESOURCE_ID);
        grant.setEffect(effect);
        grant.setSubjectType("USER");
        grant.setSubjectRefId(USER_ID);
        return grant;
    }

    /** 让规则存在，但求值器返回空片段（模拟槽位没配映射） */
    private void ruleEvaluatesToNothing() {
        RuleDO rule = new RuleDO();
        rule.setId(100L);
        rule.setTemplate("BUILT_IN_CUSTOM_TREE"); // 走条件树路径
        rule.setParams(null);                     // params 为空 → evaluate 返回 null
        when(ruleMapper.selectById(100L)).thenReturn(rule);
    }

    private void ruleEvaluatesTo(String sql) {
        RuleDO rule = new RuleDO();
        rule.setId(100L);
        rule.setTemplate("BUILT_IN_OWNER_ONLY");
        when(ruleMapper.selectById(100L)).thenReturn(rule);
        when(conditionTreeEvaluator.evaluate(any(ConditionNode.class), any(), anyMap(), any(), any()))
                .thenReturn(new ConditionTreeEvaluator.SqlFragment(sql, List.of(USER_ID)));
    }

    @Test
    @DisplayName("🔴 唯一的 ALLOW 规则求不出条件 → 必须恒假，不能变成「不过滤」")
    void testUnevaluableAllowDeniesAll() {
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of(grant(1L, "ALLOW")));
        ruleEvaluatesToNothing();

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.isFiltered(), "退化成不过滤的话，配错规则比不配规则还宽");
        assertTrue(result.getSql().contains("1 = 0"),
                "应当落成恒假条件，实际：" + result.getSql());
    }

    @Test
    @DisplayName("多条 ALLOW 里坏了一条：好的那条照常生效，不被坏的拖垮")
    void testOneBadAllowDoesNotBreakGoodOne() {
        GrantDO good = grant(1L, "ALLOW");
        GrantDO bad = grant(2L, "ALLOW");
        bad.setRuleId(200L);
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of(good, bad));
        ruleEvaluatesTo("creator = ?");
        RuleDO broken = new RuleDO();
        broken.setId(200L);
        broken.setTemplate("BUILT_IN_CUSTOM_TREE");
        broken.setParams(null);
        when(ruleMapper.selectById(200L)).thenReturn(broken);

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        // ALLOW 之间是 OR：坏的那条贡献 1=0，不影响好的那条
        assertTrue(result.getSql().contains("creator = ?"),
                "好的规则被拖垮了：" + result.getSql());
    }

    @Test
    @DisplayName("🔴 DENY 规则求不出条件 → 全拒，而不是让这条禁止令静默失效")
    void testUnevaluableDenyDeniesAll() {
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of(grant(1L, "DENY")));
        ruleEvaluatesToNothing();

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.isDenyAll(),
                "忽略一条算不出来的 DENY，等于让用户看到本不该看的数据，且毫无迹象");
    }

    @Test
    @DisplayName("🔴 授权指向的规则不存在（数据损坏）→ 全拒")
    void testMissingRuleDeniesAll() {
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of(grant(1L, "ALLOW")));
        when(ruleMapper.selectById(100L)).thenReturn(null);

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.isDenyAll(), "规则行没了就当没配过的话，等于规则被删就自动提权");
    }

    @Test
    @DisplayName("对照组：规则正常时照常生成条件，没有被本次改动波及")
    void testNormalRuleUnaffected() {
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of(grant(1L, "ALLOW")));
        ruleEvaluatesTo("creator = ?");

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertTrue(result.getSql().contains("creator = ?"));
        assertFalse(result.getSql().contains("1 = 0"), "正常规则不该冒出恒假条件");
    }

    @Test
    @DisplayName("对照组：这张表压根没配规则时，仍然是「不过滤」")
    void testNoRuleStillPasses() {
        when(grantMapper.selectListByResourceIds(anySet())).thenReturn(List.of());

        DataRowFilterRespDTO result = resolver.resolve(USER_ID, TABLE, "");

        assertFalse(result.isFiltered(), "没配规则却开始过滤，会让一堆现有功能突然查不到数据");
    }

}
