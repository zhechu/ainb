package cn.enlev.ainb.module.agent.tool.impl.formdata;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.security.core.service.SecurityFrameworkService;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_FORM_DATA_PERMISSION_DENIED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FormDataPermissionChecker} 的单元测试 —— <b>安全关键</b>
 *
 * <h3>这套用例钉死的是「权限串的格式」</h3>
 * 该串在 infra（{@code FormDesignRuntimeController.checkPermission} + {@code FormDesignRuntimeService}）
 * 与 agent（本类）各有一份硬编码副本。哪天 infra 改了格式而 agent 没跟上，
 * {@code hasPermission} 会对一个不存在的串返回 false —— <b>fail-CLOSED</b>（安全，但功能静默失效）。
 * 这些断言就是那道「先红」的警报：格式一变，测试立刻失败，提醒同步。
 *
 * @author 走进新时代
 */
public class FormDataPermissionCheckerTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDataPermissionChecker checker;

    @Mock
    private SecurityFrameworkService securityFrameworkService;

    @Test
    @DisplayName("有权限：放行，且校验的正是逐字对齐 infra 的权限串")
    public void testAllowed() {
        when(securityFrameworkService.hasPermission("infra:form-design-run:customer:query"))
                .thenReturn(true);

        assertDoesNotThrow(() -> checker.check("customer", "query"));

        verify(securityFrameworkService).hasPermission("infra:form-design-run:customer:query");
    }

    @Test
    @DisplayName("🔴 无权限：抛 AGENT_FORM_DATA_PERMISSION_DENIED，消息含 code/action/完整权限串")
    public void testDenied() {
        when(securityFrameworkService.hasPermission("infra:form-design-run:customer:query"))
                .thenReturn(false);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> checker.check("customer", "query"));

        // 必须是「按表单的数据权限」这条专用码，而不是网关那条静态工具权限码 ——
        // 否则 AI 会误判成「工具用不了」而原样重试
        assertEquals(AGENT_FORM_DATA_PERMISSION_DENIED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("customer"), ex.getMessage());
        assertTrue(ex.getMessage().contains("query"), ex.getMessage());
        // 🔴 完整权限串必须在消息里：这是「格式漂移」最直接的可见信号
        assertTrue(ex.getMessage().contains("infra:form-design-run:customer:query"), ex.getMessage());
    }

    @Test
    @DisplayName("🔴 四个 action 各自拼串正确（与 infra Controller 各端点一一对齐）")
    public void testEachActionProducesExactPermission() {
        for (String action : List.of("query", "create", "update", "delete")) {
            String expected = "infra:form-design-run:order:" + action;
            when(securityFrameworkService.hasPermission(expected)).thenReturn(true);

            checker.check("order", action);

            verify(securityFrameworkService).hasPermission(expected);
        }
    }

    @Test
    @DisplayName("🔴 超管也走同一条 hasPermission —— 放行是因为它真的有这条权限，不是绕过校验")
    public void testCheckerAlwaysDelegatesToHasPermission() {
        // 本类不含任何「超管短路」逻辑：是否放行完全取决于 SecurityFrameworkService。
        // 这条用例保证 check() 无论如何都会调用 hasPermission（不会因 code 形态而跳过）
        when(securityFrameworkService.hasPermission("infra:form-design-run:test_demo_customer:create"))
                .thenReturn(true);

        checker.check("test_demo_customer", "create");

        verify(securityFrameworkService).hasPermission("infra:form-design-run:test_demo_customer:create");
    }

}
