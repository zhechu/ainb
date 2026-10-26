package cn.enlev.ainb.module.infra.api.datainterface;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.datainterface.dto.DataInterfaceSimpleRespDTO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceDO;
import cn.enlev.ainb.module.infra.service.datainterface.DataInterfaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataInterfaceApiImpl} 的单元测试
 *
 * <h3>钉住的是三件事</h3>
 * ① 目录收敛：keyword 模糊过滤、onlyReadOnly 过滤、limit 钳制（null→20、超限→50）——
 *    目录现网 380+ 条，这层收敛是防 token 炸弹的唯一闸门；
 * ② 🔴 writeOperation 以「service 只读清单成员」为权威，而不是 DO 的 hasWriteOperation 列
 *    （该列只对 SQL 可靠，HTTP POST 恒 false，搬列会把能写的接口标成只读）；
 * ③ 执行方法薄委托：queryData→invokeReadOnly、invokeData→invoke，参数原样、异常原样冒泡。
 *
 * @author 走进新时代
 */
public class DataInterfaceApiImplTest extends BaseMockitoUnitTest {

    @Mock
    private DataInterfaceService dataInterfaceService;

    @InjectMocks
    private DataInterfaceApiImpl dataInterfaceApi;

    private static DataInterfaceDO buildDO(Long id, String code, String name, Integer type,
                                           Boolean hasWriteOperation, Integer hasPage, String paramsJson) {
        DataInterfaceDO entity = new DataInterfaceDO();
        entity.setId(id);
        entity.setCode(code);
        entity.setName(name);
        entity.setCategory("测试分类");
        entity.setType(type);
        entity.setStatus(0);
        entity.setHasWriteOperation(hasWriteOperation);
        entity.setHasPage(hasPage);
        entity.setParams(paramsJson);
        entity.setRemark("备注-" + code);
        return entity;
    }

    // ==================== listInterfaces ====================

    @Test
    @DisplayName("DO→DTO 白名单装配：typeName 映射、params JSON 解析、hasPage 转布尔；config/script 不下发")
    public void testListMapsDto() {
        DataInterfaceDO sqlRead = buildDO(1L, "q_read_sys", "系统用户查询", 1, false, 1,
                "[{\"name\":\"status\",\"label\":\"状态\",\"type\":\"INT\",\"required\":true,\"defaultValue\":\"1\",\"remark\":\"1-启用\"}]");
        when(dataInterfaceService.getEnabledSimpleList()).thenReturn(List.of(sqlRead));
        when(dataInterfaceService.getReadOnlySimpleList()).thenReturn(List.of(sqlRead));

        List<DataInterfaceSimpleRespDTO> list = dataInterfaceApi.listInterfaces(null, null, null);

        assertEquals(1, list.size());
        DataInterfaceSimpleRespDTO dto = list.get(0);
        assertEquals("q_read_sys", dto.getCode());
        assertEquals("系统用户查询", dto.getName());
        assertEquals("测试分类", dto.getCategory());
        assertEquals(1, dto.getType());
        assertEquals("SQL 查询", dto.getTypeName());
        // 🔴 在只读清单里 → writeOperation=false（不是搬 hasWriteOperation 列）
        assertFalse(dto.getWriteOperation());
        assertEquals(Boolean.TRUE, dto.getHasPage());
        assertEquals("备注-q_read_sys", dto.getRemark());
        assertEquals(1, dto.getParams().size());
        DataInterfaceSimpleRespDTO.Param param = dto.getParams().get(0);
        assertEquals("status", param.getName());
        assertEquals("状态", param.getLabel());
        assertEquals("INT", param.getType());
        assertEquals(Boolean.TRUE, param.getRequired());
        assertEquals("1", param.getDefaultValue());
        assertEquals("1-启用", param.getRemark());
    }

    @Test
    @DisplayName("🔴 HTTP POST 接口 hasWriteOperation 列为 false，但不在只读清单 → writeOperation=true（列不可信，清单权威）")
    public void testWriteOperationFromReadOnlyMembership() {
        // HTTP 类型(3)：列值 false（非 SQL 类型该列恒 false），但 service 判定非只读（POST）
        DataInterfaceDO httpPost = buildDO(2L, "http_post_api", "外部写接口", 3, false, 0, null);
        DataInterfaceDO sqlRead = buildDO(1L, "q_read_sys", "只读查询", 1, false, 0, null);
        when(dataInterfaceService.getEnabledSimpleList()).thenReturn(List.of(sqlRead, httpPost));
        when(dataInterfaceService.getReadOnlySimpleList()).thenReturn(List.of(sqlRead));

        List<DataInterfaceSimpleRespDTO> list = dataInterfaceApi.listInterfaces(null, null, null);

        assertEquals(2, list.size());
        DataInterfaceSimpleRespDTO readDto = list.stream()
                .filter(dto -> "q_read_sys".equals(dto.getCode())).findFirst().orElseThrow();
        DataInterfaceSimpleRespDTO writeDto = list.stream()
                .filter(dto -> "http_post_api".equals(dto.getCode())).findFirst().orElseThrow();
        assertFalse(readDto.getWriteOperation());
        assertTrue(writeDto.getWriteOperation());
        assertEquals("HTTP 转发", writeDto.getTypeName());
        // params 空 JSON → 空列表而非 null
        assertTrue(writeDto.getParams().isEmpty());
    }

    @Test
    @DisplayName("onlyReadOnly=true：写型接口被过滤掉")
    public void testOnlyReadOnlyFilter() {
        DataInterfaceDO sqlRead = buildDO(1L, "q_read_sys", "只读查询", 1, false, 0, null);
        DataInterfaceDO sqlWrite = buildDO(3L, "w_void", "作废单据", 1, true, 0, null);
        when(dataInterfaceService.getEnabledSimpleList()).thenReturn(List.of(sqlRead, sqlWrite));
        when(dataInterfaceService.getReadOnlySimpleList()).thenReturn(List.of(sqlRead));

        List<DataInterfaceSimpleRespDTO> list = dataInterfaceApi.listInterfaces(null, true, null);

        assertEquals(1, list.size());
        assertEquals("q_read_sys", list.get(0).getCode());
    }

    @Test
    @DisplayName("keyword 模糊匹配 code/name，忽略大小写")
    public void testKeywordFilter() {
        DataInterfaceDO a = buildDO(1L, "q_read_sys", "系统用户查询", 1, false, 0, null);
        DataInterfaceDO b = buildDO(2L, "order_list", "订单列表", 1, false, 0, null);
        DataInterfaceDO c = buildDO(3L, "misc", "含 READ 字样", 1, false, 0, null);
        when(dataInterfaceService.getEnabledSimpleList()).thenReturn(List.of(a, b, c));
        when(dataInterfaceService.getReadOnlySimpleList()).thenReturn(List.of(a, b, c));

        // 命中 code（小写关键字匹配大写无关）与 name
        List<DataInterfaceSimpleRespDTO> byCode = dataInterfaceApi.listInterfaces("Q_READ", null, null);
        assertEquals(1, byCode.size());
        assertEquals("q_read_sys", byCode.get(0).getCode());

        List<DataInterfaceSimpleRespDTO> byName = dataInterfaceApi.listInterfaces("订单", null, null);
        assertEquals(1, byName.size());
        assertEquals("order_list", byName.get(0).getCode());

        List<DataInterfaceSimpleRespDTO> byNameRead = dataInterfaceApi.listInterfaces("read", null, null);
        assertEquals(2, byNameRead.size()); // q_read_sys 的 code + misc 的 name 都含 read
    }

    @Test
    @DisplayName("limit 钳制：null→20、100→50、给定值生效")
    public void testLimitCap() {
        List<DataInterfaceDO> many = new java.util.ArrayList<>();
        for (long i = 1; i <= 60; i++) {
            many.add(buildDO(i, "iface_" + i, "接口" + i, 2, false, 0, null));
        }
        when(dataInterfaceService.getEnabledSimpleList()).thenReturn(many);
        when(dataInterfaceService.getReadOnlySimpleList()).thenReturn(many);

        assertEquals(20, dataInterfaceApi.listInterfaces(null, null, null).size());
        assertEquals(50, dataInterfaceApi.listInterfaces(null, null, 100).size());
        assertEquals(5, dataInterfaceApi.listInterfaces(null, null, 5).size());
    }

    @Test
    @DisplayName("未知类型：typeName 为 null，不抛")
    public void testUnknownTypeNullSafe() {
        DataInterfaceDO weird = buildDO(9L, "weird", "未知类型", 99, false, 0, null);
        when(dataInterfaceService.getEnabledSimpleList()).thenReturn(List.of(weird));
        when(dataInterfaceService.getReadOnlySimpleList()).thenReturn(List.of());

        DataInterfaceSimpleRespDTO dto = dataInterfaceApi.listInterfaces(null, null, null).get(0);
        assertNull(dto.getTypeName());
        assertTrue(dto.getWriteOperation()); // 不在只读清单 → 保守标为可写
    }

    // ==================== queryData / invokeData ====================

    @Test
    @DisplayName("queryData 薄委托 invokeReadOnly：code/params 原样")
    public void testQueryDataDelegates() {
        Map<String, Object> args = Map.of("status", 1);
        Object expected = Map.of("code", 0, "data", List.of());
        when(dataInterfaceService.invokeReadOnly("q_read_sys", args)).thenReturn(expected);

        Object actual = dataInterfaceApi.queryData("q_read_sys", args);

        assertSame(expected, actual);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(dataInterfaceService).invokeReadOnly(org.mockito.ArgumentMatchers.eq("q_read_sys"), captor.capture());
        assertSame(args, captor.getValue());
    }

    @Test
    @DisplayName("invokeData 薄委托 invoke：code/params 原样")
    public void testInvokeDataDelegates() {
        Map<String, Object> args = Map.of("id", 7);
        Object expected = 1;
        when(dataInterfaceService.invoke("w_void", args)).thenReturn(expected);

        Object actual = dataInterfaceApi.invokeData("w_void", args);

        assertSame(expected, actual);
        verify(dataInterfaceService).invoke("w_void", args);
    }

    @Test
    @DisplayName("🔴 service 抛 ServiceException（不存在/未启用/只读拦截）→ 原样冒泡，不吞不包")
    public void testServiceExceptionPropagates() {
        when(dataInterfaceService.invokeReadOnly(any(), any()))
                .thenThrow(new ServiceException(1_001_209_005, "数据接口不存在"));
        when(dataInterfaceService.invoke(any(), any()))
                .thenThrow(new ServiceException(1_001_209_006, "数据接口已停用，无法调用"));

        ServiceException queryEx = assertThrows(ServiceException.class,
                () -> dataInterfaceApi.queryData("nope", Map.of()));
        assertTrue(queryEx.getMessage().contains("数据接口不存在"), queryEx.getMessage());

        ServiceException invokeEx = assertThrows(ServiceException.class,
                () -> dataInterfaceApi.invokeData("disabled_one", Map.of()));
        assertTrue(invokeEx.getMessage().contains("已停用"), invokeEx.getMessage());
    }

}
