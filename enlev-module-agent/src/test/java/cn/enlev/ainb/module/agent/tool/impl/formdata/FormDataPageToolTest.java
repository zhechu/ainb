package cn.enlev.ainb.module.agent.tool.impl.formdata;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDataPageReqDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.LinkedHashMap;
import java.util.List;
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
 * {@link FormDataPageTool} 的单元测试
 *
 * <p>要点：① query 动态门；② 返回形状是精简的 {@code {total,pageNo,pageSize,list}}（控 token）；
 * ③ conditions/pageSize 正确组装进 {@link FormDataPageReqDTO} 透传给新 infra api {@code pageData}。
 *
 * @author 走进新时代
 */
public class FormDataPageToolTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDataPageTool tool;

    @Mock
    private FormDesignApi formDesignApi;
    @Mock
    private FormDataPermissionChecker permissionChecker;
    @Spy
    private FormDataParamResolver resolver = new FormDataParamResolver();

    @Test
    @DisplayName("正常：过 query 门 → pageData → 返回 {total,pageNo,pageSize,list}")
    public void testPageOk() {
        List<Map<String, Object>> rows = List.of(Map.of("id", 1), Map.of("id", 2));
        when(formDesignApi.pageData(eq("customer"), any(FormDataPageReqDTO.class)))
                .thenReturn(new PageResult<>(rows, 15L));

        Map<?, ?> resp = (Map<?, ?>) tool.execute(Map.of("code", "customer", "pageSize", 5));

        assertEquals(15L, resp.get("total"));
        assertEquals(1, resp.get("pageNo"));    // 未传 → 回显默认 1
        assertEquals(5, resp.get("pageSize"));  // 传了 5 → 回显 5
        assertEquals(rows, resp.get("list"));
        verify(permissionChecker).check("customer", "query");
    }

    @Test
    @DisplayName("分页参数透传：conditions/pageSize 组装进 FormDataPageReqDTO")
    public void testPageReqPassedThrough() {
        ArgumentCaptor<FormDataPageReqDTO> captor = ArgumentCaptor.forClass(FormDataPageReqDTO.class);
        when(formDesignApi.pageData(eq("customer"), captor.capture()))
                .thenReturn(new PageResult<>(List.of(), 0L));
        Map<String, Object> cond = new LinkedHashMap<>();
        cond.put("field", "name");
        cond.put("operator", "EQ");
        cond.put("value", "张三");

        tool.execute(Map.of("code", "customer", "pageSize", 3, "conditions", List.of(cond)));

        FormDataPageReqDTO sent = captor.getValue();
        assertEquals(3, sent.getPageSize());
        assertEquals(1, sent.getConditions().size());
        assertEquals("name", sent.getConditions().get(0).getField());
        assertEquals("EQ", sent.getConditions().get(0).getOperator());
    }

    @Test
    @DisplayName("🔴 无权限：pageData 绝不被调")
    public void testPermissionDeniedShortCircuits() {
        doThrow(new ServiceException(AGENT_FORM_DATA_PERMISSION_DENIED.getCode(), "没有权限"))
                .when(permissionChecker).check("customer", "query");

        assertThrows(ServiceException.class, () -> tool.execute(Map.of("code", "customer")));

        verify(formDesignApi, never()).pageData(any(), any());
    }

}
