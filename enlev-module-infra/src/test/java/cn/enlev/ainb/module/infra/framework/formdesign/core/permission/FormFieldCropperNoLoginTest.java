package cn.enlev.ainb.module.infra.framework.formdesign.core.permission;

import cn.enlev.ainb.framework.common.biz.system.permission.PermissionCommonApi;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DatasetFieldModeRespDTO;
import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 无登录上下文时，字段裁剪与行过滤必须<b>同方向</b>
 *
 * <h3>这条用例挡的是什么（全局 TODO #23）</h3>
 *
 * 在线表单有两条横切能力作用在同一份数据上：
 *
 * <ul>
 *   <li>{@code FormRowFilter} —— 无登录上下文时返回 {@code pass()}（<b>不过滤</b>）</li>
 *   <li>{@code FormFieldCropper} —— 曾经在同一条件下把字段<b>全判 HIDDEN</b></li>
 * </ul>
 *
 * 方向相反的后果不是「权限更严」，而是一个<b>没人能反查的状态</b>：
 * 流程引擎超时自动通过时回写业务数据 —— 行能写（没被过滤），
 * 但字段值在 {@code stripNonWritable} 里被全部丢光（判成 HIDDEN），
 * 于是单据写成了一条空记录。接口 200、无异常、无日志。
 *
 * 判据是本项目自己定的那条（见 {@code FormRowFilter} 类注释）：
 * 「这次查询的结果会不会流向用户」。无登录上下文 = 没有用户 = 不会流向用户。
 * 真正的匿名 HTTP 请求在 Spring Security 那层就被挡了，到不了这里。
 *
 * @author 走进新时代
 */
class FormFieldCropperNoLoginTest {

    private static final String FORM_CODE = "demo_form";

    private FormFieldCropper cropper;
    private PermissionCommonApi permissionApi;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        permissionApi = mock(PermissionCommonApi.class);
        ObjectProvider<PermissionCommonApi> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(permissionApi);
        cropper = new FormFieldCropper(provider);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login(Long userId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(userId);
        loginUser.setUserType(1);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        SecurityFrameworkUtils.setLoginUser(loginUser, request);
    }

    /** 与 FormFieldCropperTest 一致：字段规则是 JSON 字符串列表，整体再序列化一次 */
    private String formFields() {
        return JsonUtils.toJsonString(List.of(
                "{\"field\":\"name\",\"title\":\"名称\",\"props\":{}}",
                "{\"field\":\"mobile\",\"title\":\"手机号\",\"props\":{}}"));
    }

    @Test
    @DisplayName("🔴 无登录上下文：提交值不得被丢弃 —— 否则流程回写写进去的是空记录")
    void testNoLoginKeepsSubmittedValues() {
        // 没有登录上下文（setUp 已清空）
        Map<String, Object> submitted = new java.util.LinkedHashMap<>();
        submitted.put("name", "张三");
        submitted.put("mobile", "13900001111");

        Map<String, Object> kept = cropper.stripNonWritable(FORM_CODE, formFields(), submitted).data();

        assertEquals("张三", kept.get("name"),
                "字段被丢弃的话，超时自动通过的流程会把单据回写成空记录，且全程无日志");
        assertEquals("13900001111", kept.get("mobile"));
        // 压根不该去问权限模块 —— 没有用户，问了也只能得到「什么都不允许」
        verify(permissionApi, never()).getDatasetFieldModes(any(), anyString(), anyCollection(), anyBoolean());
    }

    @Test
    @DisplayName("对照组：有登录上下文时，字段权限照常生效")
    void testWithLoginStillEnforced() {
        login(1000L);
        DatasetFieldModeRespDTO dto = new DatasetFieldModeRespDTO();
        dto.setModes(Map.of("mobile", DatasetFieldModeRespDTO.HIDDEN));
        when(permissionApi.getDatasetFieldModes(any(), anyString(), anyCollection(), anyBoolean()))
                .thenReturn(dto);

        Map<String, Object> submitted = new java.util.LinkedHashMap<>();
        submitted.put("name", "张三");
        submitted.put("mobile", "13900001111");

        Map<String, Object> kept = cropper.stripNonWritable(FORM_CODE, formFields(), submitted).data();

        assertTrue(kept.containsKey("name"));
        assertFalse(kept.containsKey("mobile"),
                "有登录用户时 HIDDEN 字段的提交值必须丢弃 —— 这条不能因为本次改动松掉");
    }

}
