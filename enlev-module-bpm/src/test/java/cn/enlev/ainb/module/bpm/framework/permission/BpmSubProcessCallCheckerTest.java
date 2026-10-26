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
 * 子流程的 SYSTEM 调起授权（v9 §4.2）
 *
 * <h3>它堵的洞</h3>
 *
 * 不做这道校验的话：<b>谁都能间接调起任何子流程</b>。
 * 只要在自己的流程里加一个 CallActivity 指向「薪资核定」，
 * 就绕开了那个子流程上所有的 START 授权，而且看上去完全正常。
 *
 * <h3>两条必须钉住的边界</h3>
 *
 * <ol>
 *   <li><b>基线是放行</b> —— 存量父子编排必须继续能跑。
 *       用「最终允不允许」去判的话，流程资源的 default_effect 是 DENY，
 *       一问就全被拦住，等于把线上流程打死</li>
 *   <li><b>主体是 SYSTEM，不是发起人</b> —— 子流程的发起人可能被配成
 *       主流程发起人／表单里的某个人／子流程管理员，他们并没有「发起」这个子流程。
 *       拿他们校验的话，合法编排会因为「发起人恰好没权限」而失败</li>
 * </ol>
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class BpmSubProcessCallCheckerTest extends BaseMockitoUnitTest {

    private static final String PARENT_DEF_ID = "main:1:uuid";
    private static final String CHILD_KEY = "salary_review";

    @Mock
    private PermissionCommonApi permissionApi;

    private BpmSubProcessCallChecker checker;

    @BeforeEach
    void setUp() {
        checker = new BpmSubProcessCallChecker();
        ReflectionTestUtils.setField(checker, "permissionApiProvider", provider(permissionApi));
        when(permissionApi.hasExplicitDeny(any(), any(), any(), any())).thenReturn(false);
    }

    @Test
    @DisplayName("⚠️ 基线放行：没配过任何禁止时可以调起 —— 存量编排不能被打死")
    void testAllowedByDefault() {
        assertTrue(checker.canCall(PARENT_DEF_ID, CHILD_KEY));
    }

    @Test
    @DisplayName("配了显式 DENY 时拦住")
    void testExplicitDenyBlocks() {
        when(permissionApi.hasExplicitDeny("SYSTEM", BpmSubProcessCallChecker.SYSTEM_SUBJECT_CODE,
                "bpm:process:" + CHILD_KEY, "START")).thenReturn(true);

        assertFalse(checker.canCall(PARENT_DEF_ID, CHILD_KEY));
    }

    @Test
    @DisplayName("⚠️ 主体是 SYSTEM:bpm-engine，不是任何一个人")
    void testSubjectIsSystemNotUser() {
        checker.canCall(PARENT_DEF_ID, CHILD_KEY);

        verify(permissionApi).hasExplicitDeny(eq("SYSTEM"),
                eq(BpmSubProcessCallChecker.SYSTEM_SUBJECT_CODE), anyString(), anyString());
    }

    @Test
    @DisplayName("⚠️ 判的是【子流程】资源，不是主流程")
    void testChecksChildResource() {
        checker.canCall(PARENT_DEF_ID, CHILD_KEY);

        verify(permissionApi).hasExplicitDeny(any(), any(),
                eq("bpm:process:" + CHILD_KEY), eq("START"));
    }

    @Test
    @DisplayName("拿不到子流程 key 时放行 —— 不是常规子流程调用，拦住只会误伤")
    void testBlankChildKeyPasses() {
        assertTrue(checker.canCall(PARENT_DEF_ID, null));
        assertTrue(checker.canCall(PARENT_DEF_ID, ""));
        verify(permissionApi, never()).hasExplicitDeny(any(), any(), any(), any());
    }

    @Test
    @DisplayName("权限模块不可用时放行，不让一次装配问题把所有子流程打死")
    void testApiMissingPasses() {
        ReflectionTestUtils.setField(checker, "permissionApiProvider", provider(null));

        assertTrue(checker.canCall(PARENT_DEF_ID, CHILD_KEY));
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
