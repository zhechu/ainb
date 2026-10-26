package cn.enlev.ainb.module.agent.tool.impl.datainterface;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.datainterface.DataInterfaceApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataInterfaceQueryTool} 的单元测试
 *
 * <p>三个要点：① 🔴 只读工具<b>绝不能</b>触碰 invokeData（verify never，钉死读写分离）；
 * ② code/params 原样委托 queryData，结果原样透传（infra 已整形，不再包壳）；
 * ③ infra 异常（不存在/未启用/缺参/写接口被只读约束拦截）原样冒泡，AI 靠中文消息自修正。
 *
 * @author 走进新时代
 */
public class DataInterfaceQueryToolTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataInterfaceQueryTool tool;

    @Mock
    private DataInterfaceApi dataInterfaceApi;
    @Spy
    private DataInterfaceParamResolver resolver = new DataInterfaceParamResolver();

    @Test
    @DisplayName("正常：code/params 委托 queryData，结果同一对象透传")
    public void testQueryOk() {
        Map<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("code", 0);
        shaped.put("data", List.of(Map.of("id", 1)));
        when(dataInterfaceApi.queryData(any(), any())).thenReturn(shaped);

        Object resp = tool.execute(Map.of("code", " q_read_sys ", "params", Map.of("status", 1)));

        assertSame(shaped, resp);
        verify(dataInterfaceApi).queryData("q_read_sys", Map.of("status", 1));
        // 🔴 只读工具绝不碰写方法
        verify(dataInterfaceApi, never()).invokeData(any(), any());
    }

    @Test
    @DisplayName("无参接口：params 缺省 → 空 Map 委托")
    public void testQueryWithoutParams() {
        when(dataInterfaceApi.queryData(any(), any())).thenReturn("ok");

        tool.execute(Map.of("code", "static_cfg"));

        verify(dataInterfaceApi).queryData("static_cfg", Map.of());
    }

    @Test
    @DisplayName("缺 code：PARAM_INVALID，api 不被调")
    public void testMissingCode() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> tool.execute(Map.of("params", Map.of())));

        assertTrue(ex.getMessage().contains("code"), ex.getMessage());
        verify(dataInterfaceApi, never()).queryData(any(), any());
    }

    @Test
    @DisplayName("🔴 infra 异常原样冒泡：写接口被只读约束拦截的中文消息不丢")
    public void testReadOnlyViolationPropagates() {
        when(dataInterfaceApi.queryData(any(), any()))
                .thenThrow(new ServiceException(1_001_209_010, "该接口含写操作，不允许只读调用"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> tool.execute(Map.of("code", "w_void")));

        assertEquals(1_001_209_010, ex.getCode());
        assertTrue(ex.getMessage().contains("写操作"), ex.getMessage());
    }

}
