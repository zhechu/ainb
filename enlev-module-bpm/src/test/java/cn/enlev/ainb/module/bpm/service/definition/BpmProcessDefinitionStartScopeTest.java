package cn.enlev.ainb.module.bpm.service.definition;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import cn.enlev.ainb.module.bpm.framework.permission.BpmProcessPermissionChecker;
import cn.enlev.ainb.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 流程「可发起范围」的判定
 *
 * <h3>它现在守的是什么：判据只剩一条</h3>
 *
 * S7b（TODO #30）摘掉了存量回落。三个 ID 列表不再是判据 ——
 * 它们在每次部署时被投影成授权（{@code ProcessResourceCommonApi#syncProcessAuthorization}），
 * 判定处只问 {@code BpmProcessPermissionChecker}。
 *
 * 所以这里钉两件事：
 * <ol>
 *   <li>判定<b>确实只问授权</b>，不再碰 {@code startUserIds} / {@code startDeptIds}</li>
 *   <li>不能再有第二条「兜底放行」的路 —— 哪怕流程定义上的列表看起来很宽松</li>
 * </ol>
 *
 * <h3>⚠️ 那个被修掉的历史缺陷去哪了（别把它改回来）</h3>
 *
 * 改造之前这里的写法是：
 * <pre>
 * if (用户范围非空) { return 用户范围.contains(userId); }   // ← 直接 return
 * if (部门范围非空) { ... }                                 // ← 永远走不到
 * </pre>
 * 而前端「指定人员」与「指定部门」是两个独立选择器，<b>可以同时填</b>。
 * 于是同时配了之后部门范围整个失效 —— 该部门的人发不了流程，
 * 且不报错、配置页上部门名字还好端端显示着。
 *
 * 这个「两个范围是【或】」的语义现在落在<b>投影</b>那一侧：
 * 两个列表各自投影成 USER / DEPT 授权，而决策引擎对多条 ALLOW 天然取或。
 * 钉住它的用例在 system 模块的 {@code ProcessScopeProjectionTest}，
 * <b>那边也有一条同名的对照用例</b> —— 两边一起看才是完整的。
 *
 * @author 走进新时代
 */
public class BpmProcessDefinitionStartScopeTest extends BaseMockitoUnitTest {

    private static final String DEF_ID = "leave:3:uuid";

    @InjectMocks
    private BpmProcessDefinitionServiceImpl service;

    @Mock
    private AdminUserApi adminUserApi;
    @Mock
    private BpmProcessPermissionChecker processPermissionChecker;

    private BpmProcessDefinitionInfoDO definition(java.util.List<Long> userIds,
                                                  java.util.List<Long> deptIds) {
        BpmProcessDefinitionInfoDO info = new BpmProcessDefinitionInfoDO();
        info.setProcessDefinitionId(DEF_ID);
        info.setStartUserIds(userIds);
        info.setStartDeptIds(deptIds);
        return info;
    }

    @Test
    @DisplayName("有 START 授权 → 可发起")
    void testGrantAllows() {
        when(processPermissionChecker.canStart(1L, DEF_ID)).thenReturn(true);

        assertTrue(service.canUserStartProcessDefinition(definition(null, null), 1L));
    }

    @Test
    @DisplayName("🔴 没有 START 授权 → 不可发起，哪怕旧的 startUserIds 里有他")
    void testLegacyListNoLongerGrants() {
        when(processPermissionChecker.canStart(any(), any())).thenReturn(false);

        assertFalse(service.canUserStartProcessDefinition(definition(Arrays.asList(1L, 2L), null), 1L),
                "回落已摘：旧列表不再是判据，它只是部署时被投影成授权的输入");
    }

    @Test
    @DisplayName("🔴 两个范围都为空也【不】兜底放行 —— 「人人可发起」靠的是 ALL:all-users 那条授权")
    void testEmptyScopeIsNotAFallback() {
        when(processPermissionChecker.canStart(any(), any())).thenReturn(false);

        assertFalse(service.canUserStartProcessDefinition(definition(null, null), 1L),
                "留着「没配 = 放行」这条兜底的话，DENY 就永远收不紧，"
                        + "而且会掩盖「投影没跑」这种真问题");
    }

    @Test
    @DisplayName("⚠️ 判定不再查用户所属部门 —— 部门范围是投影时的事，不是判定时的事")
    void testDoesNotQueryUserDept() {
        when(processPermissionChecker.canStart(any(), any())).thenReturn(false);

        service.canUserStartProcessDefinition(definition(null, Arrays.asList(100L)), 9L);

        verify(adminUserApi, never()).getUser(any());
    }

    @Test
    @DisplayName("用的是流程定义编号，由 checker 自己反解 key")
    void testPassesDefinitionId() {
        service.canUserStartProcessDefinition(definition(null, null), 1L);

        verify(processPermissionChecker).canStart(eq(1L), eq(DEF_ID));
    }

    @Test
    @DisplayName("流程定义为空 → 不可发起，且不去问授权")
    void testNullDefinition() {
        assertFalse(service.canUserStartProcessDefinition(null, 1L));
        verify(processPermissionChecker, never()).canStart(any(), any());
    }

}
