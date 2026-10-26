package cn.enlev.ainb.module.infra.framework.formdesign.core.permission;

import cn.enlev.ainb.framework.common.biz.system.permission.PermissionCommonApi;
import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.core.context.SecurityContextHolder;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DatasetFieldModeRespDTO;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FormFieldCropper} 的单元测试
 *
 * 断言的是<b>裁剪后的 config JSON 与数据 Map 本身</b>，不是接口返回 200 ——
 * 字段裁剪的典型缺陷形态是「配了、返回也正常、就是没裁掉」，
 * 接口冒烟完全测不出来（CLAUDE.md 记的那一类）。
 *
 * @author 走进新时代
 */
class FormFieldCropperTest {

    private static final String FORM_CODE = "demo_form";

    private FormFieldCropper cropper;
    private PermissionCommonApi permissionApi;

    /**
     * 字段规则：两个主表字段 + 一个含明细的子表控件 + 一层布局容器
     */
    private List<String> formFields() {
        List<String> rules = new ArrayList<>();
        rules.add("{\"field\":\"name\",\"title\":\"名称\",\"props\":{}}");
        rules.add("{\"field\":\"mobile\",\"title\":\"手机号\",\"props\":{}}");
        // 布局容器：children 里的字段归属不变
        rules.add("{\"type\":\"row\",\"children\":[{\"field\":\"amount\",\"title\":\"金额\"}]}");
        // 子表控件：内部规则挂在 props.rule 下，明细字段标识是 items.xxx
        rules.add("{\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":["
                + "{\"field\":\"sku\",\"title\":\"货号\"},"
                + "{\"field\":\"cost\",\"title\":\"成本\"}]}}");
        return rules;
    }

    private String formFieldsJson() {
        return JsonUtils.toJsonString(formFields());
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        permissionApi = mock(PermissionCommonApi.class);
        ObjectProvider<PermissionCommonApi> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(permissionApi);
        cropper = new FormFieldCropper(provider);

        // ⚠️ 必须建登录上下文：本类验的是【登录用户】的字段裁剪，
        // 而无登录上下文时裁剪按设计不生效（内部调用不该被裁，见 FormFieldCropperNoLoginTest）。
        // 不建的话这些用例验的其实是另一条分支，且会全部失败
        LoginUser loginUser = new LoginUser();
        loginUser.setId(1000L);
        loginUser.setUserType(1);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        SecurityFrameworkUtils.setLoginUser(loginUser, request);
    }

    @AfterEach
    void tearDown() {
        // 上下文是 ThreadLocal，留着会串到同线程跑的下一个测试类
        SecurityContextHolder.clearContext();
    }

    /**
     * 让权限接口对指定字段返回指定模式，其余字段一律 EDITABLE
     */
    private void mockModes(Map<String, String> modes) {
        DatasetFieldModeRespDTO dto = new DatasetFieldModeRespDTO();
        dto.setModes(modes);
        when(permissionApi.getDatasetFieldModes(nullable(Long.class), anyString(), any(), anyBoolean()))
                .thenReturn(dto);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        return JsonUtils.parseObject(json, new TypeReference<Map<String, Object>>() {});
    }

    // ==================== config ====================

    @Test
    @DisplayName("config：HIDDEN 的规则被整条剔除")
    void testHiddenRuleRemovedFromConfig() {
        mockModes(Map.of("mobile", DatasetFieldModeRespDTO.HIDDEN));

        List<String> result = cropper.cropFormFields(FORM_CODE, formFields());

        assertEquals(3, result.size(), "四条规则里 mobile 那条该被整条剔除");
        assertTrue(result.stream().noneMatch(r -> r.contains("\"mobile\"")),
                "HIDDEN 字段的规则不能留在 config 里，否则前端仍会渲染出这个控件");
    }

    @Test
    @DisplayName("config：READONLY 注入 props.disabled，但规则本身保留")
    void testReadonlyInjectsDisabled() {
        mockModes(Map.of("name", DatasetFieldModeRespDTO.READONLY));

        List<String> result = cropper.cropFormFields(FORM_CODE, formFields());

        assertEquals(4, result.size(), "READONLY 不该把规则删掉，只是置灰");
        Map<String, Object> node = parse(result.get(0));
        assertEquals("name", node.get("field"));
        Map<String, Object> props = (Map<String, Object>) node.get("props");
        assertEquals(Boolean.TRUE, props.get("disabled"));
    }

    @Test
    @DisplayName("config：布局容器里的字段同样被裁，归属不受容器影响")
    void testHiddenFieldInsideLayoutContainer() {
        mockModes(Map.of("amount", DatasetFieldModeRespDTO.HIDDEN));

        List<String> result = cropper.cropFormFields(FORM_CODE, formFields());

        String rowRule = result.stream().filter(r -> r.contains("\"row\"")).findFirst().orElseThrow();
        assertFalse(rowRule.contains("amount"),
                "布局容器不改变字段归属，容器里的 HIDDEN 字段也要裁掉");
    }

    @Test
    @DisplayName("config：子表明细列按 items.xxx 判定并剔除")
    void testHiddenSubFieldRemovedFromConfig() {
        mockModes(Map.of("items.cost", DatasetFieldModeRespDTO.HIDDEN));

        List<String> result = cropper.cropFormFields(FORM_CODE, formFields());

        String subRule = result.stream().filter(r -> r.contains("\"items\"")).findFirst().orElseThrow();
        assertTrue(subRule.contains("sku"), "同一子表里没被裁的明细列要保留");
        assertFalse(subRule.contains("cost"), "HIDDEN 的明细列要从 props.rule 里剔除");
    }

    @Test
    @DisplayName("没配任何字段权限时 config 原样返回")
    void testNoModesKeepsConfigIntact() {
        mockModes(Map.of());

        List<String> result = cropper.cropFormFields(FORM_CODE, formFields());

        assertEquals(formFields(), result);
    }

    // ==================== 数据 ====================

    @Test
    @DisplayName("数据：HIDDEN 字段的键从行里删掉，不是置空")
    void testHiddenKeyRemovedFromRow() {
        mockModes(Map.of("mobile", DatasetFieldModeRespDTO.HIDDEN));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "张三");
        row.put("mobile", "13800138000");

        Map<String, Object> result = cropper.cropRow(FORM_CODE, row,
                List.of("name", "mobile"), false);

        assertFalse(result.containsKey("mobile"),
                "必须删键而不是置 null —— 置 null 仍然暴露「这个字段存在」");
        assertEquals("张三", result.get("name"));
    }

    @Test
    @DisplayName("数据：子表明细里的 HIDDEN 列逐行删掉")
    void testHiddenSubFieldRemovedFromRows() {
        mockModes(Map.of("items.cost", DatasetFieldModeRespDTO.HIDDEN));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("items", List.of(
                new LinkedHashMap<>(Map.of("sku", "A1", "cost", 100)),
                new LinkedHashMap<>(Map.of("sku", "A2", "cost", 200))));

        Map<String, Object> result = cropper.cropRow(FORM_CODE, row,
                List.of("items.cost"), false);

        List<Map<String, Object>> details = (List<Map<String, Object>>) result.get("items");
        assertEquals(2, details.size());
        assertTrue(details.stream().noneMatch(d -> d.containsKey("cost")),
                "明细行里的 HIDDEN 列要逐行删，漏一行就是一次泄露");
        assertTrue(details.stream().allMatch(d -> d.containsKey("sku")));
    }

    @Test
    @DisplayName("数据：READONLY 字段照常下发 —— 只读不等于不可见")
    void testReadonlyValueStillReturned() {
        mockModes(Map.of("mobile", DatasetFieldModeRespDTO.READONLY));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mobile", "13800138000");

        Map<String, Object> result = cropper.cropRow(FORM_CODE, row, List.of("mobile"), false);

        assertEquals("13800138000", result.get("mobile"));
    }

    @Test
    @DisplayName("数据：多行只解析一次权限，逐行套用同一份模式")
    void testCropRowsAppliesToEveryRow() {
        mockModes(Map.of("mobile", DatasetFieldModeRespDTO.HIDDEN));
        List<Map<String, Object>> rows = List.of(
                new LinkedHashMap<>(Map.of("name", "张三", "mobile", "138")),
                new LinkedHashMap<>(Map.of("name", "李四", "mobile", "139")));

        List<Map<String, Object>> result = cropper.cropRows(FORM_CODE, rows,
                List.of("name", "mobile"), true);

        assertTrue(result.stream().noneMatch(r -> r.containsKey("mobile")),
                "列表每一行都要裁，只裁第一行是最容易漏的写法");
    }

    // ==================== 写路径 ====================

    @Test
    @DisplayName("写路径：HIDDEN 与 READONLY 的提交值都被丢弃")
    void testStripNonWritableDropsHiddenAndReadonly() {
        mockModes(Map.of(
                "mobile", DatasetFieldModeRespDTO.HIDDEN,
                "name", DatasetFieldModeRespDTO.READONLY,
                "amount", DatasetFieldModeRespDTO.EDITABLE));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "改名");
        data.put("mobile", "13900139000");
        data.put("amount", 99);

        FormFieldCropper.StripResult result =
                cropper.stripNonWritable(FORM_CODE, formFieldsJson(), data);

        assertFalse(result.data().containsKey("mobile"),
                "看不见的字段更不该能写 —— 前端不渲染挡不住直接构造请求");
        assertFalse(result.data().containsKey("name"),
                "前端的 disabled 不是安全边界，服务端必须自己丢");
        assertEquals(99, result.data().get("amount"), "EDITABLE 的字段不受影响");
        assertEquals(2, result.discarded().size());
    }

    @Test
    @DisplayName("写路径：入参不被就地修改")
    void testStripDoesNotMutateInput() {
        mockModes(Map.of("mobile", DatasetFieldModeRespDTO.HIDDEN));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mobile", "13900139000");

        cropper.stripNonWritable(FORM_CODE, formFieldsJson(), data);

        assertTrue(data.containsKey("mobile"), "净化要返回副本，就地改会让调用方的后续逻辑看到半成品");
    }

    // ==================== 降级 ====================

    @Test
    @DisplayName("权限接口抛异常时按全部隐藏处理，不能降级成放行")
    void testExceptionFallsBackToHideAll() {
        when(permissionApi.getDatasetFieldModes(nullable(Long.class), anyString(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("boom"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "张三");
        row.put("mobile", "13800138000");

        Map<String, Object> result = cropper.cropRow(FORM_CODE, row,
                List.of("name", "mobile"), false);

        assertTrue(result.isEmpty(),
                "把异常降级成「不裁剪」等于把一次故障变成一次泄露（S4b §3.6 同一口径）");
    }

    @Test
    @DisplayName("PermissionCommonApi 不在容器里时不裁剪 —— 那只会发生在单测环境")
    @SuppressWarnings("unchecked")
    void testMissingApiKeepsDataIntact() {
        ObjectProvider<PermissionCommonApi> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        FormFieldCropper standalone = new FormFieldCropper(empty);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mobile", "13800138000");

        Map<String, Object> result = standalone.cropRow(FORM_CODE, row, List.of("mobile"), false);

        assertEquals("13800138000", result.get("mobile"));
    }

}
