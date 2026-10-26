package cn.enlev.ainb.module.infra.api.formdesign;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDataPageReqDTO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.FormDesignSqlBuilder;
import cn.enlev.ainb.module.infra.service.formdesign.runtime.FormDesignRuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FormDesignApiImpl#pageData} 的单元测试
 *
 * <h3>钉住的是「api DTO → service 内部 QueryCondition」的转换</h3>
 * 🔴 {@code FormDataPageReqDTO.Condition} 是 api 级类型，{@code FormDesignSqlBuilder.QueryCondition}
 * 是 service 内部类型 —— 后者<b>绝不能</b>泄漏进 api 签名，转换必须在 Impl 里做，
 * 且与 {@code FormDesignRuntimeController.convertConditions} 同口径（逐字段搬 field/operator/value/value2，
 * 不校验 operator —— 白名单在 SqlBuilder 内兜底）。这组用例保证转换不丢字段、分页缺省兜对默认。
 *
 * @author 走进新时代
 */
public class FormDesignApiImplPageDataTest extends BaseMockitoUnitTest {

    @Mock
    private FormDesignRuntimeService formDesignRuntimeService;

    @InjectMocks
    private FormDesignApiImpl formDesignApi;

    private void stubPage() {
        PageResult<Map<String, Object>> empty = new PageResult<>(new ArrayList<>(), 0L);
        when(formDesignRuntimeService.page(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(empty);
    }

    @Test
    @DisplayName("条件逐字段转换：field/operator/value/value2 原样搬进 QueryCondition")
    @SuppressWarnings("unchecked")
    public void testConvertsConditions() {
        stubPage();
        FormDataPageReqDTO reqDTO = new FormDataPageReqDTO();
        FormDataPageReqDTO.Condition between = new FormDataPageReqDTO.Condition();
        between.setField("age");
        between.setOperator("BETWEEN");
        between.setValue(18);
        between.setValue2(60);
        reqDTO.setConditions(List.of(between));
        reqDTO.setSortField("create_time");
        reqDTO.setSortOrder("DESC");
        reqDTO.setPageNo(2);
        reqDTO.setPageSize(5);

        formDesignApi.pageData("customer", reqDTO);

        ArgumentCaptor<List<FormDesignSqlBuilder.QueryCondition>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(formDesignRuntimeService).page(eq("customer"), captor.capture(),
                eq("create_time"), eq("DESC"), eq(2), eq(5));
        List<FormDesignSqlBuilder.QueryCondition> sent = captor.getValue();
        assertEquals(1, sent.size());
        FormDesignSqlBuilder.QueryCondition qc = sent.get(0);
        assertEquals("age", qc.getField());
        assertEquals("BETWEEN", qc.getOperator());
        assertEquals(18, qc.getValue());
        assertEquals(60, qc.getValue2());
    }

    @Test
    @DisplayName("分页缺省：pageNo/pageSize 为 null 时兜默认 1 / 10，sort 为 null")
    public void testDefaultsPaging() {
        stubPage();

        formDesignApi.pageData("customer", new FormDataPageReqDTO());

        verify(formDesignRuntimeService).page(eq("customer"), any(),
                isNull(), isNull(), eq(1), eq(10));
    }

    @Test
    @DisplayName("conditions 为 null：转成空列表（不是 null）传下去")
    @SuppressWarnings("unchecked")
    public void testNullConditionsBecomesEmptyList() {
        stubPage();
        FormDataPageReqDTO reqDTO = new FormDataPageReqDTO();
        reqDTO.setConditions(null);

        formDesignApi.pageData("customer", reqDTO);

        ArgumentCaptor<List<FormDesignSqlBuilder.QueryCondition>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(formDesignRuntimeService).page(eq("customer"), captor.capture(),
                isNull(), isNull(), eq(1), eq(10));
        assertTrue(captor.getValue().isEmpty());
    }

    @Test
    @DisplayName("条件列表里的 null 元素被跳过（镜像 convertConditions 的防御）")
    @SuppressWarnings("unchecked")
    public void testSkipsNullCondition() {
        stubPage();
        FormDataPageReqDTO.Condition valid = new FormDataPageReqDTO.Condition();
        valid.setField("name");
        valid.setOperator("EQ");
        valid.setValue("张三");
        FormDataPageReqDTO reqDTO = new FormDataPageReqDTO();
        // List.of 不接受 null，用 Arrays.asList 造出含 null 的列表
        reqDTO.setConditions(Arrays.asList(valid, null));

        formDesignApi.pageData("customer", reqDTO);

        ArgumentCaptor<List<FormDesignSqlBuilder.QueryCondition>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(formDesignRuntimeService).page(eq("customer"), captor.capture(),
                isNull(), isNull(), eq(1), eq(10));
        assertEquals(1, captor.getValue().size());
        assertEquals("name", captor.getValue().get(0).getField());
        assertNull(captor.getValue().get(0).getValue2());
    }

    @Test
    @DisplayName("🔴 不做权限校验：本方法是薄委托，权限门在调用方（agent 工具）——这里不应触碰任何权限服务")
    public void testThinDelegationNoSideEffects() {
        stubPage();

        PageResult<Map<String, Object>> result = formDesignApi.pageData("customer",
                new FormDataPageReqDTO());

        // 只委托 runtimeService.page，返回其结果；不碰 formDesignService/databaseTableService 等
        assertTrue(result != null);
        verify(formDesignRuntimeService).page(eq("customer"), any(), isNull(), isNull(), eq(1), eq(10));
    }

}
