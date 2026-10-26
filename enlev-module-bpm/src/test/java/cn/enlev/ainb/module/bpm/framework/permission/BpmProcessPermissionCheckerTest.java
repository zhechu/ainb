package cn.enlev.ainb.module.bpm.framework.permission;

import cn.enlev.ainb.framework.common.biz.system.permission.PermissionCommonApi;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 流程静态授权（v9 §7.2 / S5d，S7b 摘掉存量回落后）
 *
 * <h3>回落摘了之后，这批用例守的是什么</h3>
 *
 * S5d 时判据是「授权 OR 旧的三个 ID 列表」。S7b 把那三个列表改成
 * 每次部署时投影成授权（见 {@code ProcessResourceCommonApi#syncProcessAuthorization}），
 * 判据于是只剩一条。所以原来那条「没有授权但在旧列表里 → 仍可发起」的用例<b>删掉了</b>，
 * 取而代之的是：
 *
 * <ol>
 *   <li><b>没有授权就是不行</b> —— 这正是回落摘掉后的新语义，
 *       也意味着 DENY 终于能收紧了（回落还在时，DENY 会被旧列表绕过）</li>
 *   <li><b>权限模块不可用时拒绝，不是放行</b> —— 没有回落可退了。
 *       放行等于一次装配问题就把流程权限整个关掉，且毫无迹象</li>
 *   <li><b>不能把 definitionId 当资源标识</b> —— 它每次部署都变，
 *       拿它配的授权会在流程一改一部署之后集体失效，而界面上权限还在</li>
 * </ol>
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class BpmProcessPermissionCheckerTest extends BaseMockitoUnitTest {

    private static final Long USER = 1000L;
    private static final Long OTHER = 2000L;
    /** Flowable 的 definitionId 形如 key:version:uuid */
    private static final String DEF_ID = "leave:3:febcddb8-a399-11f1-9696-0a0027000007";

    @Mock
    private PermissionCommonApi permissionApi;

    private BpmProcessPermissionChecker checker;

    @BeforeEach
    void setUp() {
        checker = new BpmProcessPermissionChecker();
        ReflectionTestUtils.setField(checker, "permissionApiProvider", provider(permissionApi));
        when(permissionApi.hasResourceAction(any(), anyString(), anyString())).thenReturn(false);
    }

    @Test
    @DisplayName("⚠️ 资源标识用 key，不用 definitionId —— 否则一部署授权就全失效")
    void testUsesProcessKeyNotDefinitionId() {
        when(permissionApi.hasResourceAction(USER, "bpm:process:leave", "START")).thenReturn(true);

        assertTrue(checker.canStart(USER, DEF_ID));
        // 绝不能拿整个 definitionId 去问
        verify(permissionApi, never()).hasResourceAction(any(), eq("bpm:process:" + DEF_ID), anyString());
    }

    @Test
    @DisplayName("有 START 授权即可发起")
    void testGrantAllowsStart() {
        when(permissionApi.hasResourceAction(USER, "bpm:process:leave", "START")).thenReturn(true);

        assertTrue(checker.canStart(USER, DEF_ID));
    }

    @Test
    @DisplayName("🔴 没有 START 授权就是不行 —— 回落摘掉后 DENY 终于能收紧了")
    void testNoGrantDenies() {
        assertFalse(checker.canStart(USER, DEF_ID),
                "回落还在时，旧 startUserIds 会把 DENY 绕过去，等于「只能放开不能收紧」");
    }

    @Test
    @DisplayName("⚠️ 「人人可发起」不是靠「没有授权」，而是靠给 ALL:all-users 的那条 START 授权")
    void testEveryoneIsAnExplicitGrant() {
        // 模拟决策引擎在 ALL 主体上命中：对调用方而言就是 hasResourceAction 返回 true
        when(permissionApi.hasResourceAction(OTHER, "bpm:process:leave", "START")).thenReturn(true);

        assertTrue(checker.canStart(OTHER, DEF_ID));
        assertFalse(checker.canStart(USER, DEF_ID),
                "没有任何授权命中时必须是拒绝 —— 靠「缺少某行」表达「人人可发起」"
                        + "会让所有没限定范围的流程在摘掉回落的当天全部停摆");
    }

    @Test
    @DisplayName("管理员：只看 MANAGE 授权")
    void testManagerUsesGrantOnly() {
        when(permissionApi.hasResourceAction(USER, "bpm:process:leave", "MANAGE")).thenReturn(true);

        assertTrue(checker.isManager(USER, DEF_ID));
        assertFalse(checker.isManager(OTHER, DEF_ID));
    }

    @Test
    @DisplayName("VIEW_ALL 是独立动作，不被 MANAGE 顺带放行")
    void testViewAllIsSeparateAction() {
        when(permissionApi.hasResourceAction(USER, "bpm:process:leave", "MANAGE")).thenReturn(true);

        assertFalse(checker.canViewAll(USER, DEF_ID),
                "几个动作混成一个的话，「能管理」就等于「能看全部实例」，等于没区分");
    }

    @Test
    @DisplayName("有 DESIGN 授权即可设计")
    void testGrantAllowsDesign() {
        when(permissionApi.hasResourceAction(USER, "bpm:process:leave", "DESIGN")).thenReturn(true);

        assertTrue(checker.canDesign(USER, DEF_ID));
        assertFalse(checker.canDesign(OTHER, DEF_ID));
    }

    @Test
    @DisplayName("🔴 DESIGN 不被 MANAGE 蕴含 —— 所以「设计权」的判定必须写成 MANAGE || DESIGN")
    void testDesignIsNotImpliedByManager() {
        when(permissionApi.hasResourceAction(USER, "bpm:process:leave", "MANAGE")).thenReturn(true);

        assertFalse(checker.canDesign(USER, DEF_ID),
                "sys_grant 按 action 精确匹配，一行 MANAGE 授权匹配不上 DESIGN 的查询。"
                        + "调用方若只看 canDesign 就判设计权，管理员反而会被挡在外面");
    }

    @Test
    @DisplayName("🔴 权限模块不可用时【拒绝】—— 没有回落可退了，放行等于把流程权限整个关掉且毫无迹象")
    void testApiMissingDenies() {
        ReflectionTestUtils.setField(checker, "permissionApiProvider", provider(null));

        assertFalse(checker.canStart(USER, DEF_ID));
        assertFalse(checker.isManager(USER, DEF_ID));
        assertFalse(checker.canViewAll(USER, DEF_ID));
        assertFalse(checker.canDesign(USER, DEF_ID));
    }

    @Test
    @DisplayName("未登录直接拒绝，连问都不问")
    void testNullUserDenied() {
        assertFalse(checker.canStart(null, DEF_ID));
        verify(permissionApi, never()).hasResourceAction(any(), anyString(), anyString());
    }

    private ObjectProvider<PermissionCommonApi> provider(PermissionCommonApi api) {
        return new ObjectProvider<>() {
            @Override
            public PermissionCommonApi getObject() {
                return api;
            }

            @Override
            public PermissionCommonApi getObject(Object... args) {
                return api;
            }

            @Override
            public PermissionCommonApi getIfAvailable() {
                return api;
            }

            @Override
            public PermissionCommonApi getIfUnique() {
                return api;
            }
        };
    }

}
