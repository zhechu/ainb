package cn.enlev.ainb.module.agent.tool.impl.formdata;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.LinkedHashMap;
import java.util.Map;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_FORM_DATA_PERMISSION_DENIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FormDataGetTool} 的单元测试
 *
 * <p>三个要点：① 只读工具走 query 动作的动态权限门；② 权限门<b>先于</b> api 调用；
 * ③ infra 的「行不存在」原样冒泡（不吞不包，AI 靠中文消息自修正）。
 *
 * @author 走进新时代
 */
public class FormDataGetToolTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDataGetTool tool;

    @Mock
    private FormDesignApi formDesignApi;
    @Mock
    private FormDataPermissionChecker permissionChecker;
    @Spy
    private FormDataParamResolver resolver = new FormDataParamResolver();

    @Test
    @DisplayName("正常：解析 code/pk → 过动态权限门(query) → getData 结果透传")
    public void testGetOk() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("name", "张三");
        when(formDesignApi.getData("customer", "1")).thenReturn(row);

        Map<?, ?> resp = (Map<?, ?>) tool.execute(Map.of("code", "customer", "pk", "1"));

        assertEquals("customer", resp.get("code"));
        assertEquals("1", resp.get("id"));
        assertEquals(row, resp.get("data"));
        // 🔴 动态数据权限门必须被调用，且 action=query（get/page 同属 query）
        verify(permissionChecker).check("customer", "query");
    }

    @Test
    @DisplayName("别名 id 也能定位主键")
    public void testGetByIdAlias() {
        when(formDesignApi.getData("customer", "7")).thenReturn(new LinkedHashMap<>());

        tool.execute(Map.of("code", "customer", "id", "7"));

        verify(formDesignApi).getData("customer", "7");
    }

    @Test
    @DisplayName("🔴 无权限：动态门先抛，getData 绝不被调（越权读被挡在 api 之前）")
    public void testPermissionDeniedShortCircuits() {
        doThrow(new ServiceException(AGENT_FORM_DATA_PERMISSION_DENIED.getCode(), "没有权限"))
                .when(permissionChecker).check("customer", "query");

        assertThrows(ServiceException.class,
                () -> tool.execute(Map.of("code", "customer", "pk", "1")));

        verify(formDesignApi, never()).getData(any(), any());
    }

    @Test
    @DisplayName("行不存在：infra 抛 DATA_NOT_EXISTS，原样冒泡")
    public void testRowNotFoundPropagates() {
        when(formDesignApi.getData("customer", "999"))
                .thenThrow(new ServiceException(1_001_204_031, "表单数据不存在"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> tool.execute(Map.of("code", "customer", "pk", "999")));

        assertTrue(ex.getMessage().contains("表单数据不存在"), ex.getMessage());
    }

    @Test
    @DisplayName("缺 code：PARAM_INVALID，权限门与 api 都不被调")
    public void testMissingCode() {
        assertThrows(ServiceException.class, () -> tool.execute(Map.of("pk", "1")));

        verify(permissionChecker, never()).check(any(), any());
        verify(formDesignApi, never()).getData(any(), any());
    }

}
