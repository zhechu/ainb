package cn.enlev.ainb.module.agent.tool.impl.diagnostic;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.BpmProcessValidator;
import cn.enlev.ainb.module.bpm.api.definition.BpmModelApi;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelRespDTO;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmProcessConstraintsRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.system.api.permission.SystemDiagnosticApi;
import cn.enlev.ainb.module.system.api.permission.dto.ResourceDiagnosticRespDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link DiagnoseNotWorkingTool} 的单元测试
 *
 * <p>断言不只看「有没有报问题」，还要看<b>报的是不是那个问题</b> ——
 * 诊断工具报错了但指错方向，比不报还糟：人会顺着错的方向查下去。
 *
 * @author 走进新时代
 */
public class DiagnoseNotWorkingToolTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DiagnoseNotWorkingTool tool;

    @Mock
    private SystemDiagnosticApi systemDiagnosticApi;
    @Mock
    private BpmModelApi bpmModelApi;
    @Mock
    private FormDesignApi formDesignApi;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findingsOf(Object result) {
        return (List<Map<String, Object>>) ((Map<String, Object>) result).get("findings");
    }

    private long problemCountOf(Object result) {
        return ((Number) ((Map<String, Object>) result).get("problemCount")).longValue();
    }

    private boolean hasFindingContaining(Object result, String keyword) {
        return findingsOf(result).stream().anyMatch(item ->
                String.valueOf(item.get("title")).contains(keyword)
                        || String.valueOf(item.get("detail")).contains(keyword)
                        || String.valueOf(item.get("advice")).contains(keyword));
    }

    // ==================== 边界声明 ====================

    @Test
    @DisplayName("🔴 输出里必须带「不能替代在浏览器里真跑一遍」")
    public void testDisclaimerAlwaysPresent() {
        when(formDesignApi.getSubFieldSeparator()).thenReturn(".");
        when(bpmModelApi.getProcessConstraints()).thenReturn(constraints("."));

        Object result = tool.execute(Map.of("targetType", "subField"));
        String disclaimer = String.valueOf(((Map<String, Object>) result).get("disclaimer"));
        assertTrue(disclaimer.contains("不能替代在浏览器里真跑一遍"),
                "这句话是产品承诺的一部分：工具说没问题很容易被当成问题解决了");
    }

    // ==================== 子表分隔符 ====================

    @Test
    @DisplayName("契约里的分隔符与 bpm 内联的值不一致要报出来")
    public void testSubFieldSeparatorMismatch() {
        when(formDesignApi.getSubFieldSeparator()).thenReturn(".");
        when(bpmModelApi.getProcessConstraints()).thenReturn(constraints("#"));

        Object result = tool.execute(Map.of("targetType", "subField"));
        assertEquals(1L, problemCountOf(result));
        assertTrue(hasFindingContaining(result, "分隔符"), "要明确指向分隔符");
    }

    @Test
    @DisplayName("两侧分隔符一致则报正常")
    public void testSubFieldSeparatorMatch() {
        when(formDesignApi.getSubFieldSeparator()).thenReturn(".");
        when(bpmModelApi.getProcessConstraints()).thenReturn(constraints("."));

        Object result = tool.execute(Map.of("targetType", "subField"));
        assertEquals(0L, problemCountOf(result));
    }

    // ==================== 行级规则 ====================

    @Test
    @DisplayName("🔴 表没登记为数据集 —— 行级规则一次都不会被调用")
    public void testTableNotRegisteredAsDataset() {
        when(systemDiagnosticApi.getNativeDatasetTables())
                .thenReturn(Set.of("system_users", "system_dept"));

        Object result = tool.execute(Map.of("targetType", "table", "target", "biz_order"));
        assertEquals(1L, problemCountOf(result));
        assertTrue(hasFindingContaining(result, "@PermissionDataset"),
                "要给出具体修法，而不只说「没登记」");
    }

    @Test
    @DisplayName("表已登记为数据集则报正常")
    public void testTableRegistered() {
        when(systemDiagnosticApi.getNativeDatasetTables()).thenReturn(Set.of("biz_order"));

        Object result = tool.execute(Map.of("targetType", "table", "target", "BIZ_ORDER"));
        assertEquals(0L, problemCountOf(result), "表名比对要忽略大小写");
    }

    // ==================== 权限点 ====================

    @Test
    @DisplayName("🔴 资源未登记 —— 授权配了等于没配")
    public void testPermissionNotRegistered() {
        ResourceDiagnosticRespDTO dto = new ResourceDiagnosticRespDTO();
        dto.setRegistered(false);
        when(systemDiagnosticApi.diagnoseResource(eq("infra:foo:bar"))).thenReturn(dto);

        Object result = tool.execute(Map.of("targetType", "permission", "target", "infra:foo:bar"));
        assertEquals(1L, problemCountOf(result));
        assertTrue(hasFindingContaining(result, "未登记"));
    }

    @Test
    @DisplayName("🔴 授权的租户写错")
    public void testGrantTenantMismatch() {
        ResourceDiagnosticRespDTO dto = new ResourceDiagnosticRespDTO();
        dto.setRegistered(true);
        dto.setResourceCount(1);
        dto.setGrantCount(5);
        dto.setTenantMismatchGrantCount(2);
        dto.setIllegalFieldModes(List.of());
        when(systemDiagnosticApi.diagnoseResource(any())).thenReturn(dto);

        Object result = tool.execute(Map.of("targetType", "permission", "target", "a:b:c"));
        assertEquals(1L, problemCountOf(result));
        assertTrue(hasFindingContaining(result, "system_role.tenant_id"),
                "要说清楚正确的租户来源是哪张表");
    }

    @Test
    @DisplayName("🔴 非法的 fieldMode（最常见是 VISIBLE）")
    public void testIllegalFieldMode() {
        ResourceDiagnosticRespDTO dto = new ResourceDiagnosticRespDTO();
        dto.setRegistered(true);
        dto.setResourceCount(1);
        dto.setGrantCount(3);
        dto.setTenantMismatchGrantCount(0);
        dto.setIllegalFieldModes(List.of("VISIBLE"));
        when(systemDiagnosticApi.diagnoseResource(any())).thenReturn(dto);
        when(systemDiagnosticApi.getLegalFieldModes())
                .thenReturn(List.of("EDITABLE", "READONLY", "HIDDEN"));

        Object result = tool.execute(Map.of("targetType", "permission", "target", "a:b:c"));
        assertEquals(1L, problemCountOf(result));
        assertTrue(hasFindingContaining(result, "VISIBLE"));
        assertTrue(hasFindingContaining(result, "静默丢弃"));
    }

    @Test
    @DisplayName("一个权限串对应多行资源 —— 提示但不算问题")
    public void testMultipleResourceRows() {
        ResourceDiagnosticRespDTO dto = new ResourceDiagnosticRespDTO();
        dto.setRegistered(true);
        dto.setResourceCount(3);
        dto.setGrantCount(2);
        dto.setTenantMismatchGrantCount(0);
        dto.setIllegalFieldModes(List.of());
        when(systemDiagnosticApi.diagnoseResource(any())).thenReturn(dto);

        Object result = tool.execute(Map.of("targetType", "permission", "target", "a:b:c"));
        assertEquals(0L, problemCountOf(result), "多行资源是隐患不是缺陷，不该报成问题");
        assertTrue(hasFindingContaining(result, "多行资源"));
    }

    // ==================== 流程 ====================

    @Test
    @DisplayName("🔴 审批节点 approveType 不对 —— 唯一会静默失效的一条")
    public void testProcessApproveTypeWrong() {
        mockProcess("oa_leave", true);
        ResourceDiagnosticRespDTO dto = registeredResource();
        when(systemDiagnosticApi.diagnoseResource(eq("bpm:process:oa_leave"))).thenReturn(dto);
        when(bpmModelApi.getModelSimpleJson(any())).thenReturn("""
                {"id":"StartUserNode","type":10,"name":"发起人",
                 "childNode":{"id":"n1","type":11,"name":"部门审批","approveType":2,
                   "childNode":{"id":"EndEvent","type":1,"name":"结束"}}}""");

        Object result = tool.execute(Map.of("targetType", "process", "target", "oa_leave"));
        assertTrue(hasFindingContaining(result, "approveType"));
        assertTrue(hasFindingContaining(result, "部门审批"), "要指出是哪个节点");
        assertTrue(hasFindingContaining(result, "静默"), "要说清楚它为什么难查");
    }

    @Test
    @DisplayName("🔴 流程资源未登记 —— 节点级字段权限配了等于没配")
    public void testProcessResourceNotRegistered() {
        mockProcess("oa_leave", true);
        ResourceDiagnosticRespDTO dto = new ResourceDiagnosticRespDTO();
        dto.setRegistered(false);
        when(systemDiagnosticApi.diagnoseResource(eq("bpm:process:oa_leave"))).thenReturn(dto);
        lenient().when(bpmModelApi.getModelSimpleJson(any())).thenReturn("""
                {"id":"StartUserNode","type":10,"childNode":{"id":"EndEvent","type":1}}""");

        Object result = tool.execute(Map.of("targetType", "process", "target", "oa_leave"));
        assertTrue(hasFindingContaining(result, "资源未登记"));
        assertTrue(hasFindingContaining(result, "contextId"), "要说清楚失效的机理");
    }

    @Test
    @DisplayName("流程从未部署")
    public void testProcessNotDeployed() {
        mockProcess("oa_leave", false);
        when(systemDiagnosticApi.diagnoseResource(any())).thenReturn(registeredResource());
        lenient().when(bpmModelApi.getModelSimpleJson(any())).thenReturn("""
                {"id":"StartUserNode","type":10,"childNode":{"id":"EndEvent","type":1}}""");

        Object result = tool.execute(Map.of("targetType", "process", "target", "oa_leave"));
        assertTrue(hasFindingContaining(result, "从未部署"));
    }

    @Test
    @DisplayName("流程不存在时要说清楚，而不是报一堆无关结论")
    public void testProcessNotFound() {
        when(bpmModelApi.getModelList(any())).thenReturn(List.of());

        Object result = tool.execute(Map.of("targetType", "process", "target", "nope"));
        assertEquals(1L, problemCountOf(result));
        assertTrue(hasFindingContaining(result, "流程不存在"));
    }

    // ==================== 造数据 ====================

    private void mockProcess(String key, boolean deployed) {
        BpmModelRespDTO model = new BpmModelRespDTO();
        model.setId("model-id");
        model.setKey(key);
        model.setName("请假");
        model.setDeployed(deployed);
        when(bpmModelApi.getModelList(any())).thenReturn(List.of(model));
        lenient().when(bpmModelApi.getProcessConstraints()).thenReturn(constraints("."));
    }

    private ResourceDiagnosticRespDTO registeredResource() {
        ResourceDiagnosticRespDTO dto = new ResourceDiagnosticRespDTO();
        dto.setRegistered(true);
        dto.setResourceCount(1);
        dto.setGrantCount(1);
        dto.setTenantMismatchGrantCount(0);
        dto.setIllegalFieldModes(List.of());
        return dto;
    }

    private BpmProcessConstraintsRespDTO constraints(String separator) {
        BpmProcessConstraintsRespDTO dto = new BpmProcessConstraintsRespDTO();
        dto.setRequiredApproveType(1);
        dto.setSubFieldSeparator(separator);
        return dto;
    }

}
