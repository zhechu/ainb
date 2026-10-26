package cn.enlev.ainb.module.system.enums.permission;

import cn.enlev.ainb.module.system.framework.permission.core.rule.BuiltInVariableProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RuleTemplateMetaRegistry} 的防漂移闸门测试
 *
 * <p>纯静态数据校验，不需要 Spring 上下文。核心目的：新增模板而忘了登记元数据、
 * 或注册表声明的 params key 与 {@code DataRowFilterResolver} 读取的 key 不一致时，
 * 让构建直接失败——而不是等到线上「配了不生效」。
 *
 * @author 走进新时代
 */
public class RuleTemplateMetaRegistryTest {

    /** ① 每个模板都必须登记（穷尽性闸门） */
    @Test
    public void testEveryTemplateRegistered() {
        for (RuleTemplateEnum t : RuleTemplateEnum.values()) {
            assertNotNull(RuleTemplateMetaRegistry.get(t),
                    "模板 " + t.getCode() + " 未在 RuleTemplateMetaRegistry 登记");
        }
        assertEquals(RuleTemplateEnum.values().length, RuleTemplateMetaRegistry.size());
    }

    /** ② 恰好 10 个模板，且 DEPT_IN（前端历史漏过）在列、参数为 deptIds/DEPT_MULTI */
    @Test
    public void testTemplateCountAndDeptIn() {
        assertEquals(10, RuleTemplateEnum.values().length);

        RuleTemplateMetaRegistry.Meta deptIn = RuleTemplateMetaRegistry.get(RuleTemplateEnum.DEPT_IN);
        assertNotNull(deptIn);
        assertEquals(RuleTemplateMetaRegistry.GROUP_BASIC, deptIn.group());
        assertEquals(1, deptIn.paramSpecs().size());
        RuleTemplateMetaRegistry.ParamSpec spec = deptIn.paramSpecs().get(0);
        assertEquals(RuleTemplateMetaRegistry.PARAM_DEPT_IDS, spec.key());
        assertEquals(RuleTemplateMetaRegistry.KIND_DEPT_MULTI, spec.kind());
        assertTrue(spec.required());
        assertEquals(RuleTemplateMetaRegistry.VALUE_TYPE_NUMBER, spec.valueType());
    }

    /** ③ 各模板的 paramSpec key 必须等于 resolver 读取的 PARAM_* 常量 */
    @Test
    public void testParamKeysMatchResolverContract() {
        assertKeys(RuleTemplateEnum.STATUS_IN, RuleTemplateMetaRegistry.PARAM_ALLOWED_STATUSES);
        assertKeys(RuleTemplateEnum.DEPT_IN, RuleTemplateMetaRegistry.PARAM_DEPT_IDS);
        assertKeys(RuleTemplateEnum.FIELD_EQUALS,
                RuleTemplateMetaRegistry.PARAM_FIELD, RuleTemplateMetaRegistry.PARAM_VALUE);
        assertKeys(RuleTemplateEnum.CUSTOM_TREE, RuleTemplateMetaRegistry.PARAM_CONDITION_TREE);
    }

    private void assertKeys(RuleTemplateEnum template, String... expectedKeys) {
        List<String> actual = RuleTemplateMetaRegistry.get(template).paramSpecs().stream()
                .map(RuleTemplateMetaRegistry.ParamSpec::key)
                .toList();
        assertEquals(List.of(expectedKeys), actual,
                "模板 " + template.getCode() + " 的参数 key 与契约不符");
    }

    /** ④ 无参数模板 paramSpecs 为空；CUSTOM_TREE 恰一个 CONDITION_TREE；FIELD_EQUALS 恰 SLOT+TEXT */
    @Test
    public void testParamSpecShapes() {
        List<RuleTemplateEnum> noParam = List.of(
                RuleTemplateEnum.OWNER_ONLY, RuleTemplateEnum.DEPT_ONLY,
                RuleTemplateEnum.DEPT_AND_CHILDREN, RuleTemplateEnum.OWNER_OR_DEPT,
                RuleTemplateEnum.BPM_PARTICIPANT, RuleTemplateEnum.RECORD_SHARED);
        for (RuleTemplateEnum t : noParam) {
            assertTrue(RuleTemplateMetaRegistry.get(t).paramSpecs().isEmpty(),
                    t.getCode() + " 应为无参数模板");
        }

        RuleTemplateMetaRegistry.Meta customTree = RuleTemplateMetaRegistry.get(RuleTemplateEnum.CUSTOM_TREE);
        assertEquals(RuleTemplateMetaRegistry.GROUP_ADVANCED, customTree.group());
        assertEquals(1, customTree.paramSpecs().size());
        assertEquals(RuleTemplateMetaRegistry.KIND_CONDITION_TREE, customTree.paramSpecs().get(0).kind());

        RuleTemplateMetaRegistry.Meta fieldEquals = RuleTemplateMetaRegistry.get(RuleTemplateEnum.FIELD_EQUALS);
        assertEquals(2, fieldEquals.paramSpecs().size());
        assertEquals(RuleTemplateMetaRegistry.KIND_SLOT, fieldEquals.paramSpecs().get(0).kind());
        assertEquals(RuleTemplateMetaRegistry.KIND_TEXT, fieldEquals.paramSpecs().get(1).kind());
    }

    /** 派生模板归入 derived 组 */
    @Test
    public void testDerivedGroup() {
        assertEquals(RuleTemplateMetaRegistry.GROUP_DERIVED,
                RuleTemplateMetaRegistry.get(RuleTemplateEnum.BPM_PARTICIPANT).group());
        assertEquals(RuleTemplateMetaRegistry.GROUP_DERIVED,
                RuleTemplateMetaRegistry.get(RuleTemplateEnum.RECORD_SHARED).group());
    }

    /** ⑤ 变量元数据：12 个，且恰 3 个是集合类型（供 IN/NOT_IN 选用） */
    @Test
    public void testVariableMetadata() {
        List<BuiltInVariableProvider.VariableMeta> vars = BuiltInVariableProvider.VARIABLE_METADATA;
        assertEquals(12, vars.size());

        Set<String> collectionVars = vars.stream()
                .filter(BuiltInVariableProvider.VariableMeta::collection)
                .map(BuiltInVariableProvider.VariableMeta::name)
                .collect(Collectors.toSet());
        assertEquals(Set.of("currentDeptAndChildren", "currentRoleIds", "currentPostIds"), collectionVars);

        // 每个变量都得有中文名，且 name 非空
        for (BuiltInVariableProvider.VariableMeta v : vars) {
            assertNotNull(v.name());
            assertFalse(v.name().isBlank());
            assertNotNull(v.label());
            assertFalse(v.label().isBlank());
        }
    }

    /** requiredSlots 引用的槽位名必须是合法的 PermissionSlotEnum */
    @Test
    public void testRequiredSlotsAreValid() {
        for (RuleTemplateEnum t : RuleTemplateEnum.values()) {
            for (String slot : RuleTemplateMetaRegistry.get(t).requiredSlots()) {
                assertNotNull(PermissionSlotEnum.parse(slot),
                        t.getCode() + " 引用了未知槽位 " + slot);
            }
        }
    }

}
