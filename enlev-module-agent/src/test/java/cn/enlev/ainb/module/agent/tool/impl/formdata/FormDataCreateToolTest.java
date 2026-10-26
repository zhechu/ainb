package cn.enlev.ainb.module.agent.tool.impl.formdata;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.Map;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_FORM_DATA_PERMISSION_DENIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FormDataCreateTool} 的单元测试
 *
 * <p>要点：① create 动态门；② 返回新主键进 {@code {id}}；③ data 缺失时在<b>权限门与 api 之前</b>就拦下。
 *
 * @author 走进新时代
 */
public class FormDataCreateToolTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDataCreateTool tool;

    @Mock
    private FormDesignApi formDesignApi;
    @Mock
    private FormDataPermissionChecker permissionChecker;
    @Spy
    private FormDataParamResolver resolver = new FormDataParamResolver();

    @Test
    @DisplayName("正常：过 create 门 → createData 返回主键进 {id}")
    public void testCreateOk() {
        when(formDesignApi.createData(eq("customer"), any())).thenReturn(100L);

        Map<?, ?> resp = (Map<?, ?>) tool.execute(
                Map.of("code", "customer", "data", Map.of("name", "张三")));

        assertEquals(100L, resp.get("id"));
        verify(permissionChecker).check("customer", "create");
    }

    @Test
    @DisplayName("🔴 data 缺失：PARAM_INVALID，权限门与 createData 都不被调")
    public void testCreateMissingData() {
        assertThrows(ServiceException.class, () -> tool.execute(Map.of("code", "customer")));

        verify(permissionChecker, never()).check(any(), any());
        verify(formDesignApi, never()).createData(any(), any());
    }

    @Test
    @DisplayName("🔴 无权限：createData 绝不被调")
    public void testCreatePermissionDenied() {
        doThrow(new ServiceException(AGENT_FORM_DATA_PERMISSION_DENIED.getCode(), "没有权限"))
                .when(permissionChecker).check("customer", "create");

        assertThrows(ServiceException.class, () -> tool.execute(
                Map.of("code", "customer", "data", Map.of("name", "张三"))));

        verify(formDesignApi, never()).createData(any(), any());
    }

    @Test
    @DisplayName("infra 唯一/必填校验失败：原样冒泡（工具不重复做字段级校验）")
    public void testCreateInfraValidationPropagates() {
        when(formDesignApi.createData(eq("customer"), any()))
                .thenThrow(new ServiceException(1_001_204_020, "字段 name 不能为空"));

        ServiceException ex = assertThrows(ServiceException.class, () -> tool.execute(
                Map.of("code", "customer", "data", Map.of("age", 1))));

        assertEquals(1_001_204_020, ex.getCode());
    }

}
