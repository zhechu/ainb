package cn.enlev.ainb.module.system.framework.permission.core.rule;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import cn.enlev.ainb.module.system.service.user.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code :currentRoleIds} 的取值，以及 12 个内置变量的完整性
 *
 * <h3>这批用例钉住的是一个「恒为空集合」的内置变量</h3>
 *
 * {@code BuiltInVariableProvider} 里原本写的是 {@code Set<Long> roleIds = Set.of();}，
 * 旁边一行注释写着「后续可以替换」。于是规则编辑器里能选到「当前用户角色」，
 * 条件树也能存下来，求值时永远拿到空集合：
 *
 * <ul>
 *   <li>{@code 角色 IN :currentRoleIds} → {@code 1=0} → 一行都看不到</li>
 *   <li>{@code 角色 NOT_IN :currentRoleIds} → {@code 1=1} → 完全不设限</li>
 * </ul>
 *
 * 两种都不报错，界面上规则看着配得好好的。第二种尤其危险：它是<b>提权</b>，
 * 而且方向与「配了排除规则」的意图正好相反。
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class BuiltInVariableProviderTest extends BaseMockitoUnitTest {

    private static final Long USER_ID = 1000L;

    @Mock
    private AdminUserService adminUserService;
    @Mock
    private DeptService deptService;

    private BuiltInVariableProvider provider;

    @BeforeEach
    void setUp() {
        provider = new BuiltInVariableProvider();
        ReflectionTestUtils.setField(provider, "adminUserService", adminUserService);
        ReflectionTestUtils.setField(provider, "deptService", deptService);
    }

    /** 调用方（DataRowFilterResolver）把已经算好的主体闭包透进来的那种上下文 */
    private Map<String, Object> contextWith(SubjectKey... keys) {
        Set<SubjectKey> subjects = new LinkedHashSet<>(List.of(keys));
        return Map.of(BuiltInVariableProvider.CONTEXT_SUBJECT_KEYS, subjects);
    }

    @SuppressWarnings("unchecked")
    private Set<Long> roleIdsOf(Map<String, Object> vars) {
        Object raw = vars.get("currentRoleIds");
        assertTrue(raw instanceof Collection, "currentRoleIds 必须是集合，否则 IN/NOT_IN 会把它当单值");
        return new LinkedHashSet<>((Collection<Long>) raw);
    }

    // ========== currentRoleIds ==========

    @Test
    @DisplayName("🔴 currentRoleIds 取自闭包里的 ROLE 主体，多个角色都在")
    void testRoleIdsFromClosure() {
        Map<String, Object> vars = provider.provide(USER_ID, contextWith(
                SubjectKey.ofUser(USER_ID),
                SubjectKey.ofRole(5L),
                SubjectKey.ofRole(6L)));

        assertEquals(Set.of(5L, 6L), roleIdsOf(vars));
    }

    @Test
    @DisplayName("🔴 只有 ROLE 主体进 currentRoleIds：USER/DEPT/POST/GROUP/ALL 都不能混进来")
    void testNonRoleSubjectsExcluded() {
        // refId 是同一套 Long 空间，混进来不会报错，只会在 SQL 里静默匹配到错误的角色 ——
        // 比如把部门 id 12 当成角色 id 12。这类错误没有任何外部迹象
        Map<String, Object> vars = provider.provide(USER_ID, contextWith(
                SubjectKey.ofUser(USER_ID),
                SubjectKey.ofRole(5L),
                SubjectKey.ofDept(12L),
                SubjectKey.ofPost(12L),
                SubjectKey.ofGroup(12L),
                SubjectKey.ofAllUsers()));

        assertEquals(Set.of(5L), roleIdsOf(vars));
    }

    @Test
    @DisplayName("没传闭包时是空集合，且不抛异常（provide 是公开 SPI，入参形状不受本类控制）")
    void testMissingContextYieldsEmpty() {
        assertEquals(Set.of(), roleIdsOf(provider.provide(USER_ID, Map.of())));
        assertEquals(Set.of(), roleIdsOf(provider.provide(USER_ID, null)));
    }

    @Test
    @DisplayName("🔴 __subjectKeys 是入参不是变量，不能漏进返回值")
    void testContextKeyNotLeakedAsVariable() {
        // 漏出去就成了一个不在 VARIABLE_METADATA 里、编辑器选不到、
        // 也没人知道存在的第 13 个变量，规则里可以用 :__subjectKeys 引用到它
        Map<String, Object> vars = provider.provide(USER_ID, contextWith(SubjectKey.ofRole(5L)));

        assertFalse(vars.containsKey(BuiltInVariableProvider.CONTEXT_SUBJECT_KEYS));
    }

    @Test
    @DisplayName("requestContext 里的其它键照常透传（processInstanceId / taskId）")
    void testOtherContextKeysPassThrough() {
        Map<String, Object> vars = provider.provide(USER_ID, Map.of(
                BuiltInVariableProvider.CONTEXT_SUBJECT_KEYS, Set.of(SubjectKey.ofRole(5L)),
                "processInstanceId", "PI-1",
                "taskId", "T-9"));

        assertEquals("PI-1", vars.get("processInstanceId"));
        assertEquals("T-9", vars.get("taskId"));
        assertEquals(Set.of(5L), roleIdsOf(vars));
    }

    // ========== 12 个内置变量的完整性 ==========

    @Test
    @DisplayName("🔴 VARIABLE_METADATA 里声明的变量，provide 必须一个不少地注入")
    void testEveryDeclaredVariableIsProvided() {
        // 类注释写着「这份表是 provide 注入的变量的视图元数据，两者必须一致」，
        // 但没有任何编译期或运行期约束能保证它。漂移的表现是编辑器里选得到一个变量、
        // 求值时却取不到值 → 那个条件被静默丢掉 → AND 组少一条 → 过滤变宽
        Map<String, Object> vars = provider.provide(USER_ID, Map.of());

        for (BuiltInVariableProvider.VariableMeta meta : BuiltInVariableProvider.VARIABLE_METADATA) {
            assertTrue(vars.containsKey(meta.name()),
                    "元数据声明了 " + meta.name() + " 但 provide 没注入，编辑器会选到一个取不到值的变量");
            if (meta.collection()) {
                assertTrue(vars.get(meta.name()) instanceof Collection,
                        meta.name() + " 标了 collection=true，值却不是集合，IN/NOT_IN 会把它当单值处理");
            }
        }
        assertEquals(BuiltInVariableProvider.VARIABLE_METADATA.size(), 12, "内置变量个数变了，本类与文档都要同步");
    }

    @Test
    @DisplayName("对照组：currentUserId / currentDeptAndChildren 等取值没有被本次改动波及")
    void testOtherVariablesUnaffected() {
        Map<String, Object> vars = provider.provide(USER_ID, contextWith(SubjectKey.ofRole(5L)));

        assertEquals(USER_ID, vars.get("currentUserId"));
        // 用户查不到（mock 返回 null）时部门相关是空/ null，这是既有行为
        assertNull(vars.get("currentDeptId"));
        assertEquals(Set.of(), vars.get("currentDeptAndChildren"));
        assertNotNull(vars.get("currentDateTime"));
        assertNotNull(vars.get("currentDayOfWeek"));
    }

}
