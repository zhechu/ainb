package cn.enlev.ainb.module.agent.tool.impl.datainterface;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.datainterface.DataInterfaceApi;
import cn.enlev.ainb.module.infra.api.datainterface.dto.DataInterfaceSimpleRespDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataInterfaceListTool} 的单元测试
 *
 * <p>钉住：解析后的 keyword/onlyReadOnly/limit 原样透传给 api（钳制在 resolver 与 infra Impl
 * 各有一层，这里验工具这层传递不走样），返回形状 {total,list}。
 *
 * @author 走进新时代
 */
public class DataInterfaceListToolTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataInterfaceListTool tool;

    @Mock
    private DataInterfaceApi dataInterfaceApi;
    @Spy
    private DataInterfaceParamResolver resolver = new DataInterfaceParamResolver();

    private static DataInterfaceSimpleRespDTO dto(String code) {
        DataInterfaceSimpleRespDTO dto = new DataInterfaceSimpleRespDTO();
        dto.setCode(code);
        return dto;
    }

    @Test
    @DisplayName("正常：keyword/onlyReadOnly/limit 透传，返回 {total,list}")
    public void testListOk() {
        when(dataInterfaceApi.listInterfaces("q_read", true, 10))
                .thenReturn(List.of(dto("q_read_sys"), dto("q_read_bt")));

        Map<?, ?> resp = (Map<?, ?>) tool.execute(
                Map.of("keyword", "q_read", "onlyReadOnly", true, "limit", 10));

        assertEquals(2, resp.get("total"));
        assertEquals(2, ((List<?>) resp.get("list")).size());
        verify(dataInterfaceApi).listInterfaces("q_read", true, 10);
    }

    @Test
    @DisplayName("缺省参数：keyword=null、onlyReadOnly=false、limit=20")
    public void testListDefaults() {
        when(dataInterfaceApi.listInterfaces(null, false, 20)).thenReturn(List.of());

        Map<?, ?> resp = (Map<?, ?>) tool.execute(Map.of());

        assertEquals(0, resp.get("total"));
        verify(dataInterfaceApi).listInterfaces(null, false, 20);
    }

    @Test
    @DisplayName("🔴 limit 超上限被钳到 50 再传 api（防 token 炸弹的第一道闸）")
    public void testLimitCapped() {
        when(dataInterfaceApi.listInterfaces(null, false, 50)).thenReturn(List.of());

        tool.execute(Map.of("limit", 500));

        verify(dataInterfaceApi).listInterfaces(null, false, 50);
    }

}
