package cn.enlev.ainb.module.agent.tool.impl.formdata;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FormDataUpdateTool} 的单元测试
 *
 * <h3>🔴 核心用例是「读-合-写」</h3>
 * {@code updateData} 是按 fieldMapping <b>全量覆盖</b>的，缺键写 null。若直接把模型给的
 * 「只含要改字段」的 data 传下去，其余字段会被<b>静默清空</b>。工具必须先读回整行再合并。
 * 用 ArgumentCaptor 钉死「未传字段保留、传入字段覆盖」，这是防数据损坏的关键断言。
 *
 * @author 走进新时代
 */
public class FormDataUpdateToolTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDataUpdateTool tool;

    @Mock
    private FormDesignApi formDesignApi;
    @Mock
    private FormDataPermissionChecker permissionChecker;
    @Spy
    private FormDataParamResolver resolver = new FormDataParamResolver();

    @Test
    @DisplayName("🔴 读-合-写：未提到的字段保留、传入的字段覆盖（PATCH 语义）")
    @SuppressWarnings("unchecked")
    public void testUpdateReadMergeWrite() {
        Map<String, Object> exists = new LinkedHashMap<>();
        exists.put("a", 1);
        exists.put("b", 2);
        exists.put("c", 3);
        when(formDesignApi.getData("customer", "1")).thenReturn(exists);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        Map<?, ?> resp = (Map<?, ?>) tool.execute(
                Map.of("code", "customer", "pk", "1", "data", Map.of("b", 9)));

        verify(formDesignApi).updateData(eq("customer"), eq("1"), captor.capture());
        Map<String, Object> merged = captor.getValue();
        // 🔴 a/c 没在入参里，必须原样保留；b 被覆盖成 9
        assertEquals(1, merged.get("a"));
        assertEquals(9, merged.get("b"));
        assertEquals(3, merged.get("c"));
        verify(permissionChecker).check("customer", "update");
        // 回灌只报「改了哪些字段」，不啰嗦整行
        assertTrue(((java.util.Collection<String>) resp.get("updatedFields")).contains("b"));
    }

    @Test
    @DisplayName("🔴 行不存在：getData 抛 → 冒泡，updateData 绝不被调（不会更新不存在的行）")
    public void testUpdateRowNotFoundNoWrite() {
        when(formDesignApi.getData("customer", "999"))
                .thenThrow(new ServiceException(1_001_204_031, "表单数据不存在"));

        assertThrows(ServiceException.class, () -> tool.execute(
                Map.of("code", "customer", "pk", "999", "data", Map.of("b", 9))));

        verify(formDesignApi, never()).updateData(any(), any(), any());
    }

    @Test
    @DisplayName("🔴 无权限：getData/updateData 绝不被调")
    public void testUpdatePermissionDenied() {
        doThrow(new ServiceException(AGENT_FORM_DATA_PERMISSION_DENIED.getCode(), "没有权限"))
                .when(permissionChecker).check("customer", "update");

        assertThrows(ServiceException.class, () -> tool.execute(
                Map.of("code", "customer", "pk", "1", "data", Map.of("b", 9))));

        verify(formDesignApi, never()).getData(any(), any());
        verify(formDesignApi, never()).updateData(any(), any(), any());
    }

    @Test
    @DisplayName("data 缺失：PARAM_INVALID，不发生任何读写")
    public void testUpdateMissingData() {
        assertThrows(ServiceException.class,
                () -> tool.execute(Map.of("code", "customer", "pk", "1")));

        verify(formDesignApi, never()).getData(any(), any());
        verify(formDesignApi, never()).updateData(any(), any(), any());
    }

}
