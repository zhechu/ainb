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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link FormDataDeleteTool} 的单元测试
 *
 * <p>要点：① delete 动态门；② {@code deleteData(code,pk)} 被正确调用；③ pk 缺失时在 api 之前拦下。
 *
 * @author 走进新时代
 */
public class FormDataDeleteToolTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDataDeleteTool tool;

    @Mock
    private FormDesignApi formDesignApi;
    @Mock
    private FormDataPermissionChecker permissionChecker;
    @Spy
    private FormDataParamResolver resolver = new FormDataParamResolver();

    @Test
    @DisplayName("正常：过 delete 门 → deleteData(code,pk) 被调，返回 {id,message}")
    public void testDeleteOk() {
        Map<?, ?> resp = (Map<?, ?>) tool.execute(Map.of("code", "customer", "pk", "1"));

        assertEquals("1", resp.get("id"));
        verify(formDesignApi).deleteData("customer", "1");
        verify(permissionChecker).check("customer", "delete");
    }

    @Test
    @DisplayName("pk 缺失：PARAM_INVALID，deleteData 不被调")
    public void testDeleteMissingPk() {
        assertThrows(ServiceException.class, () -> tool.execute(Map.of("code", "customer")));

        verify(permissionChecker, never()).check(any(), any());
        verify(formDesignApi, never()).deleteData(any(), any());
    }

    @Test
    @DisplayName("🔴 无权限：deleteData 绝不被调")
    public void testDeletePermissionDenied() {
        doThrow(new ServiceException(AGENT_FORM_DATA_PERMISSION_DENIED.getCode(), "没有权限"))
                .when(permissionChecker).check("customer", "delete");

        assertThrows(ServiceException.class,
                () -> tool.execute(Map.of("code", "customer", "pk", "1")));

        verify(formDesignApi, never()).deleteData(any(), any());
    }

}
