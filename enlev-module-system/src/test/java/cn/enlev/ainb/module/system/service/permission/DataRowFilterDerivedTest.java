package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.biz.bpm.BpmParticipantCommonApi;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties;
import cn.enlev.ainb.module.system.framework.permission.core.rule.ConditionNode;
import cn.enlev.ainb.module.system.framework.permission.core.rule.ConditionTreeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 派生可见性（{@code BUILT_IN_BPM_PARTICIPANT}）的求值
 *
 * <h3>⚠️ 这批用例守的是一条「反直觉」的不变量</h3>
 *
 * 派生可见性求不出结果时，必须落成<b>恒假</b>（一行都看不到），
 * <b>不能</b>返回 null 或空片段。
 *
 * 原因藏在 {@code DataRowFilterResolver.combine} 里：一条 ALLOW 规则若求值为空，
 * 它会被跳过；若它是<b>唯一</b>的 ALLOW 规则，最终
 * {@code allowSql == null && denySql == null} → 返回 {@code pass()} —— <b>整表不过滤</b>。
 *
 * 也就是说，「配了派生可见性但取不到参与数据」会变成<b>比不配还宽</b>。
 * 这类缺陷不报错、不打日志、界面完全正常，只有盯着 SQL 看才会发现 ——
 * 正是 CLAUDE.md 说的「配了、存了、但没生效」里最贵的一种。
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class DataRowFilterDerivedTest extends BaseMockitoUnitTest {

    private static final Long RESOURCE_ID = 10L;
    private static final Long USER_ID = 1000L;
    private static final String FORM_CODE = "demo_leave";

    @Mock
    private ResourceMapper resourceMapper;
    @Mock
    private ConditionTreeEvaluator conditionTreeEvaluator;
    @Mock
    private BpmParticipantCommonApi bpmParticipantApi;

    private DataRowFilterResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DataRowFilterResolver();
        ReflectionTestUtils.setField(resolver, "resourceMapper", resourceMapper);
        ReflectionTestUtils.setField(resolver, "conditionTreeEvaluator", conditionTreeEvaluator);
        ReflectionTestUtils.setField(resolver, "bpmParticipantApiProvider", provider(bpmParticipantApi));
        ReflectionTestUtils.setField(resolver, "permissionProperties", new PermissionProperties());

        // 默认：资源是一个正常的在线表单数据集
        ResourceDO resource = new ResourceDO();
        resource.setId(RESOURCE_ID);
        resource.setPermission("infra:form-design-run:" + FORM_CODE);
        when(resourceMapper.selectById(RESOURCE_ID)).thenReturn(resource);

        // 默认：求值器正常工作
        when(conditionTreeEvaluator.evaluate(any(ConditionNode.class), any(), anyMap(), any(), any()))
                .thenReturn(new ConditionTreeEvaluator.SqlFragment("`biz_key` IN (?,?)",
                        List.of("66", "77")));
    }

    /** 反射调用私有的派生求值方法 */
    private ConditionTreeEvaluator.SqlFragment evaluate() {
        Map<String, Object> variables = new java.util.LinkedHashMap<>();
        // 最后一个参数是「本次查询里每张表的合法限定符」。派生可见性走的是单表子查询，
        // 调用方不知道也不关心有哪些表，传 null 就是「一律用列前缀」—— 与 resolve 的三参入口一致
        return (ConditionTreeEvaluator.SqlFragment) ReflectionTestUtils.invokeMethod(
                resolver, "evaluateBpmParticipant", RESOURCE_ID, variables, "", USER_ID, null);
    }

    private void assertDenyAll(ConditionTreeEvaluator.SqlFragment fragment, String why) {
        assertNotNull(fragment, why + "：返回 null 会被当成「这条规则没有限制」，最终退化成整表可见");
        assertFalse(fragment.isEmpty(), why + "：空片段与 null 等价，同样会退化成整表可见");
        assertEquals("1 = 0", fragment.sql(), why);
    }

    @Test
    @DisplayName("正常：参与过的业务主键被拼进 IN 条件")
    void testNormal() {
        when(bpmParticipantApi.getParticipatedBusinessKeys(USER_ID, FORM_CODE))
                .thenReturn(Set.of("66", "77"));

        ConditionTreeEvaluator.SqlFragment fragment = evaluate();

        assertTrue(fragment.sql().contains("IN"), "应当落成 IN 条件：" + fragment.sql());
        assertEquals(List.of("66", "77"), fragment.params());
    }

    @Test
    @DisplayName("⚠️ 一条流程都没参与过 → 恒假，而不是「不过滤」")
    void testNoParticipationDeniesAll() {
        when(bpmParticipantApi.getParticipatedBusinessKeys(any(), any())).thenReturn(Set.of());

        assertDenyAll(evaluate(), "没参与过任何流程就该一行都看不到");
    }

    @Test
    @DisplayName("⚠️ bpm 模块不可用 → 恒假，不能把故障变成越权")
    void testBpmApiMissingDeniesAll() {
        ReflectionTestUtils.setField(resolver, "bpmParticipantApiProvider", provider(null));

        assertDenyAll(evaluate(), "取不到参与数据时放行，等于把一次装配问题放大成全表泄露");
    }

    @Test
    @DisplayName("⚠️ 资源不是在线表单数据集 → 恒假")
    void testNonDatasetResourceDeniesAll() {
        ResourceDO resource = new ResourceDO();
        resource.setId(RESOURCE_ID);
        resource.setPermission("system:user:query"); // 普通菜单权限，不是数据集
        when(resourceMapper.selectById(RESOURCE_ID)).thenReturn(resource);

        assertDenyAll(evaluate(), "资源上根本没有表单编码，派生可见性无从谈起");
    }

    @Test
    @DisplayName("⚠️ 资源是列/字段而不是数据集 → 恒假")
    void testColumnResourceDeniesAll() {
        ResourceDO resource = new ResourceDO();
        resource.setId(RESOURCE_ID);
        resource.setPermission("infra:form-design-run:" + FORM_CODE + ":col:amount");
        when(resourceMapper.selectById(RESOURCE_ID)).thenReturn(resource);

        assertDenyAll(evaluate(), "列资源的权限串带 :col: 段，截出来的不是表单编码");
    }

    @Test
    @DisplayName("⚠️ bizKey 槽位没配映射 → 恒假")
    void testMissingSlotMappingDeniesAll() {
        when(bpmParticipantApi.getParticipatedBusinessKeys(any(), any())).thenReturn(Set.of("66"));
        // 求值器对槽位缺映射只打 WARN + 返回空片段
        when(conditionTreeEvaluator.evaluate(any(ConditionNode.class), any(), anyMap(), any(), any()))
                .thenReturn(new ConditionTreeEvaluator.SqlFragment("", List.of()));

        assertDenyAll(evaluate(), "槽位没映射意味着条件落不到具体列上，此时放行就是全表可见");
    }

    @Test
    @DisplayName("⚠️ 业务主键数超阈值只告警，【不改变行为】")
    void testOverThresholdStillReturnsFullCondition() {
        PermissionProperties props = new PermissionProperties();
        props.setDerivedKeyWarnThreshold(1); // 把阈值压到 1，必然触发
        ReflectionTestUtils.setField(resolver, "permissionProperties", props);
        when(bpmParticipantApi.getParticipatedBusinessKeys(any(), any()))
                .thenReturn(Set.of("66", "77"));

        ConditionTreeEvaluator.SqlFragment fragment = evaluate();

        // 截断会静默少数据、拒绝会让「参与得越多看到的越少」—— 两者都是错的
        assertTrue(fragment.sql().contains("IN"), "超阈值不该改变条件形状");
        assertEquals(List.of("66", "77"), fragment.params(),
                "超阈值不该截断参数，那会让用户看不到本该看到的单据且无从察觉");
    }

    @Test
    @DisplayName("求值走的是条件树求值器 —— 列名白名单不能绕过")
    void testUsesEvaluatorForColumnWhitelist() {
        when(bpmParticipantApi.getParticipatedBusinessKeys(any(), any())).thenReturn(Set.of("66"));

        evaluate();

        // 自己拼列名的话，列名就不再经过槽位映射白名单校验 —— 那是注入口
        verify(conditionTreeEvaluator).evaluate(any(ConditionNode.class), eq(RESOURCE_ID),
                anyMap(), any(), any());
    }

    // ==================== 构造 ====================

    private ObjectProvider<BpmParticipantCommonApi> provider(BpmParticipantCommonApi api) {
        return new ObjectProvider<>() {
            @Override
            public BpmParticipantCommonApi getObject() {
                return api;
            }

            @Override
            public BpmParticipantCommonApi getObject(Object... args) {
                return api;
            }

            @Override
            public BpmParticipantCommonApi getIfAvailable() {
                return api;
            }

            @Override
            public BpmParticipantCommonApi getIfUnique() {
                return api;
            }
        };
    }

}
