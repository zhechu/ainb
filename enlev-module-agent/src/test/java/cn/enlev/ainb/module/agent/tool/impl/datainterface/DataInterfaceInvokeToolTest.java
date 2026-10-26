package cn.enlev.ainb.module.agent.tool.impl.datainterface;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.datainterface.DataInterfaceApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataInterfaceInvokeTool} 的单元测试
 *
 * <p>钉住：① code/params 原样委托 invokeData（全能力路径），结果原样透传；
 * ② 缺 code → PARAM_INVALID 且 api 不被调；③ infra 异常原样冒泡。
 * 权限（infra:data-interface:invoke）不在工具内校验 —— 那是网关的职责，
 * 声明契约由 {@code DataInterfaceToolContractTest} 钉死。
 *
 * @author 走进新时代
 */
public class DataInterfaceInvokeToolTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataInterfaceInvokeTool tool;

    @Mock
    private DataInterfaceApi dataInterfaceApi;
    @Spy
    private DataInterfaceParamResolver resolver = new DataInterfaceParamResolver();

    @Test
    @DisplayName("正常：code/params 委托 invokeData，结果同一对象透传")
    public void testInvokeOk() {
        Object affected = 1;
        when(dataInterfaceApi.invokeData(any(), any())).thenReturn(affected);

        Object resp = tool.execute(Map.of("code", " w_void ", "params", "{\"id\":7}"));

        assertSame(affected, resp);
        verify(dataInterfaceApi).invokeData("w_void", Map.of("id", 7));
    }

    @Test
    @DisplayName("无参接口：params 缺省 → 空 Map 委托")
    public void testInvokeWithoutParams() {
        when(dataInterfaceApi.invokeData(any(), any())).thenReturn("done");

        tool.execute(Map.of("code", "sync_job"));

        verify(dataInterfaceApi).invokeData("sync_job", Map.of());
    }

    @Test
    @DisplayName("缺 code：PARAM_INVALID，api 不被调")
    public void testMissingCode() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> tool.execute(Map.of("params", Map.of("id", 1))));

        assertTrue(ex.getMessage().contains("code"), ex.getMessage());
        verify(dataInterfaceApi, never()).invokeData(any(), any());
    }

    @Test
    @DisplayName("infra 异常（不存在/未启用/缺参）原样冒泡")
    public void testServiceExceptionPropagates() {
        when(dataInterfaceApi.invokeData(any(), any()))
                .thenThrow(new ServiceException(1_001_209_005, "数据接口不存在"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> tool.execute(Map.of("code", "nope")));

        assertTrue(ex.getMessage().contains("数据接口不存在"), ex.getMessage());
    }

}
