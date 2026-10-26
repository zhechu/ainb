package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RecordShareDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.RecordShareMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 记录共享的行过滤求值（S7b-2）
 *
 * <h3>与派生可见性共用一条通道，所以降级方向必须逐字对齐</h3>
 *
 * {@code BUILT_IN_RECORD_SHARED} 与 {@code BUILT_IN_BPM_PARTICIPANT} 都是
 * 「一串具体的业务主键对某人可见」，都落在 {@code bizKey} 槽位上。
 * 两条路径对「取不到数据」给出不同答案的话，会出现
 * 「共享的看得到、参与的看不到」这种没人能解释的不一致。
 *
 * 所以这批用例与 {@link DataRowFilterDerivedTest} 是**对称**的：
 * 那边每有一条「→ 恒假」，这边就有一条同形的。
 *
 * <h3>为什么求不出来必须是恒假而不是「不过滤」</h3>
 *
 * 见 {@code DataRowFilterResolver.combine}：一条求值为空的 ALLOW 规则会被跳过，
 * 若它是唯一的 ALLOW，最终退化成<b>整表不过滤</b> —— 配了比不配还宽。
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class DataRowFilterRecordShareTest extends BaseMockitoUnitTest {

    private static final Long RESOURCE_ID = 10L;
    private static final Long USER_ID = 1000L;
    private static final String FORM_CODE = "demo_leave";

    @Mock
    private ResourceMapper resourceMapper;
    @Mock
    private ConditionTreeEvaluator conditionTreeEvaluator;
    @Mock
    private RecordShareMapper recordShareMapper;
    @Mock
    private SubjectClosureService subjectClosureService;

    private DataRowFilterResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DataRowFilterResolver();
        ReflectionTestUtils.setField(resolver, "resourceMapper", resourceMapper);
        ReflectionTestUtils.setField(resolver, "conditionTreeEvaluator", conditionTreeEvaluator);
        ReflectionTestUtils.setField(resolver, "recordShareMapper", recordShareMapper);
        ReflectionTestUtils.setField(resolver, "subjectClosureService", subjectClosureService);
        ReflectionTestUtils.setField(resolver, "permissionProperties", new PermissionProperties());

        ResourceDO resource = new ResourceDO();
        resource.setId(RESOURCE_ID);
        resource.setPermission("infra:form-design-run:" + FORM_CODE);
        when(resourceMapper.selectById(RESOURCE_ID)).thenReturn(resource);

        when(subjectClosureService.getSubjectKeysByUserId(USER_ID))
                .thenReturn(Set.of(SubjectKey.ofUser(USER_ID)));

        when(conditionTreeEvaluator.evaluate(any(ConditionNode.class), any(), anyMap(), any(), any()))
                .thenReturn(new ConditionTreeEvaluator.SqlFragment("`id` IN (?,?)",
                        List.of("66", "77")));
    }

    private ConditionTreeEvaluator.SqlFragment evaluate() {
        Map<String, Object> variables = new java.util.LinkedHashMap<>();
        // 末参是表限定符表；记录分享判定同样走单表子查询，传 null = 「一律用列前缀」
        return (ConditionTreeEvaluator.SqlFragment) ReflectionTestUtils.invokeMethod(
                resolver, "evaluateRecordShared", RESOURCE_ID, variables, "", USER_ID, null);
    }

    private void assertDenyAll(ConditionTreeEvaluator.SqlFragment fragment, String why) {
        assertNotNull(fragment, why + "：返回 null 会被当成「这条规则没有限制」，最终退化成整表可见");
        assertFalse(fragment.isEmpty(), why + "：空片段与 null 等价，同样会退化成整表可见");
        assertEquals("1 = 0", fragment.sql(), why);
    }

    private RecordShareDO share(String recordId, SubjectKey subject) {
        RecordShareDO dto = new RecordShareDO();
        dto.setDatasetCode(FORM_CODE);
        dto.setRecordId(recordId);
        dto.setSubjectType(subject.type().name());
        dto.setSubjectRefId(subject.type().isRefBased() ? subject.refId() : null);
        dto.setSubjectCode(subject.type().isRefBased() ? null : subject.code());
        return dto;
    }

    @Test
    @DisplayName("正常：分享给他的主键被拼进 IN 条件")
    void testNormal() {
        when(recordShareMapper.selectListBySubjects(eq(FORM_CODE), any()))
                .thenReturn(List.of(share("66", SubjectKey.ofUser(USER_ID)),
                        share("77", SubjectKey.ofUser(USER_ID))));

        ConditionTreeEvaluator.SqlFragment fragment = evaluate();

        assertTrue(fragment.sql().contains("IN"), "应当落成 IN 条件：" + fragment.sql());
        assertEquals(List.of("66", "77"), fragment.params());
    }

    @Test
    @DisplayName("⚠️ 没人分享给他 → 恒假，而不是「不过滤」")
    void testNoShareDeniesAll() {
        when(recordShareMapper.selectListBySubjects(any(), any())).thenReturn(List.of());

        assertDenyAll(evaluate(), "没被分享过就该一行都看不到");
    }

    @Test
    @DisplayName("🔴 SQL 查出来但主体对不上的行要被剔掉 —— 多放行是没人会来报的那种错")
    void testSubjectMismatchFilteredInMemory() {
        // 模拟「SQL 分支拼错了」：返回了一条分享给别人的记录
        when(recordShareMapper.selectListBySubjects(eq(FORM_CODE), any()))
                .thenReturn(List.of(share("66", SubjectKey.ofUser(999L))));

        assertDenyAll(evaluate(), "主体对不上的共享不该放行");
    }

    @Test
    @DisplayName("⚠️ 主体闭包取不到 → 恒假，不能把故障变成越权")
    void testClosureFailureDeniesAll() {
        when(subjectClosureService.getSubjectKeysByUserId(any()))
                .thenThrow(new IllegalStateException("boom"));

        assertDenyAll(evaluate(), "算不出他是谁的时候放行，等于把一次故障放大成全表泄露");
    }

    @Test
    @DisplayName("⚠️ 资源不是数据集 → 恒假")
    void testNonDatasetResourceDeniesAll() {
        ResourceDO resource = new ResourceDO();
        resource.setId(RESOURCE_ID);
        resource.setPermission("system:user:query"); // 普通菜单权限，datasetTable 为空
        when(resourceMapper.selectById(RESOURCE_ID)).thenReturn(resource);

        assertDenyAll(evaluate(), "资源上根本没有数据集编码，记录共享无从谈起");
    }

    @Test
    @DisplayName("⚠️ 原生数据集（system:user）也要认 —— 只认在线表单的话，原生模块上配的共享会恒假")
    void testNativeDatasetRecognized() {
        ResourceDO resource = new ResourceDO();
        resource.setId(RESOURCE_ID);
        resource.setPermission("system:user");
        resource.setDatasetTable("system_users"); // 有绑表 = 确实是数据集节点
        when(resourceMapper.selectById(RESOURCE_ID)).thenReturn(resource);
        when(recordShareMapper.selectListBySubjects(eq("system:user"), any()))
                .thenReturn(List.of(share("66", SubjectKey.ofUser(USER_ID))));

        ConditionTreeEvaluator.SqlFragment fragment = evaluate();

        assertTrue(fragment.sql().contains("IN"), "原生数据集上的共享该正常生效：" + fragment.sql());
    }

    @Test
    @DisplayName("🔴 bizKey 槽位没配映射 → 恒假。这正是 S5c 留下的那个坑，S7b-2 让它不再出现")
    void testMissingBizKeySlotDeniesAll() {
        when(recordShareMapper.selectListBySubjects(eq(FORM_CODE), any()))
                .thenReturn(List.of(share("66", SubjectKey.ofUser(USER_ID))));
        // 求值器对缺槽位映射只打 WARN 并返回空片段
        when(conditionTreeEvaluator.evaluate(any(ConditionNode.class), any(), anyMap(), any(), any()))
                .thenReturn(new ConditionTreeEvaluator.SqlFragment("", List.of()));

        assertDenyAll(evaluate(), "槽位缺映射时空片段会被当成「没有限制」，必须转成恒假");
    }

    @Test
    @DisplayName("条件走的是 bizKey 槽位 —— 与派生可见性同一个槽位，不能各用各的")
    void testUsesBizKeySlot() {
        when(recordShareMapper.selectListBySubjects(eq(FORM_CODE), any()))
                .thenReturn(List.of(share("66", SubjectKey.ofUser(USER_ID))));

        evaluate();

        verify(conditionTreeEvaluator).evaluate(
                argThat(node -> "bizKey".equals(node.getSlot())), any(), anyMap(), any(), any());
    }

}
