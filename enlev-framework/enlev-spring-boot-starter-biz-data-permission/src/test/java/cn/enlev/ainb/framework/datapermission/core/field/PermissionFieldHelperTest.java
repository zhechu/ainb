package cn.enlev.ainb.framework.datapermission.core.field;

import cn.enlev.ainb.framework.common.biz.system.permission.PermissionCommonApi;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DatasetFieldModeRespDTO;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import lombok.Data;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 原生模块字段裁剪（S7a）
 *
 * <h3>这组用例守的三条口径</h3>
 *
 * 它们与在线表单那边（{@code FormFieldCropperTest}）刻意完全一致 ——
 * 两套实现对同一件事给出不同答案，就会出现「同一个字段，在表单里看不见、
 * 在原生接口里看得见」，而没人能凭直觉推出是哪边错了。
 *
 * <ol>
 *   <li>查不到资源行 = 放行（按需创建的前提）</li>
 *   <li>权限模块不可用 = 放行 + 留痕</li>
 *   <li>无登录上下文 = 不限制（内部调用；判成 HIDDEN 会让写路径把提交值丢光）</li>
 * </ol>
 *
 * @author 走进新时代
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class PermissionFieldHelperTest {

    private static final String DATASET = "system:user";
    private static final Long USER_ID = 100L;

    @Mock
    private PermissionCommonApi permissionApi;

    private PermissionFieldHelper helper;

    @Data
    public static class UserVO {
        private Long id;
        private String nickname;
        private String mobile;
        private String email;
        /**
         * 基本类型：置不了 null，用来钉住「不编造数据」那条
         */
        private int sex;
    }

    @BeforeEach
    void setUp() {
        helper = new PermissionFieldHelper(provider(permissionApi));
        login(USER_ID);
    }

    @AfterEach
    void tearDown() {
        // 上下文是 ThreadLocal，留着会串到同线程跑的下一个用例
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("HIDDEN 的字段被置空，其它字段原样保留")
    void testCropHidden() {
        modes(Map.of("mobile", DatasetFieldModeRespDTO.HIDDEN));
        UserVO vo = user();

        helper.crop(vo, DATASET, List.of("mobile", "email"));

        assertNull(vo.getMobile());
        assertEquals("a@b.com", vo.getEmail());
        assertEquals("张三", vo.getNickname());
    }

    @Test
    @DisplayName("⚠️ READONLY 在读路径【不】置空 —— 「不能改」不该被执行成「看不见」")
    void testReadonlyNotCroppedOnRead() {
        modes(Map.of("mobile", DatasetFieldModeRespDTO.READONLY));
        UserVO vo = user();

        helper.crop(vo, DATASET, List.of("mobile", "email"));

        assertEquals("13900001111", vo.getMobile());
    }

    @Test
    @DisplayName("写路径把 HIDDEN 与 READONLY 一起丢掉 —— 前端 disabled 不是安全边界")
    void testStripNonWritable() {
        modes(Map.of("mobile", DatasetFieldModeRespDTO.HIDDEN,
                "email", DatasetFieldModeRespDTO.READONLY));
        UserVO vo = user();

        Set<String> stripped = helper.stripNonWritable(vo, DATASET, List.of("mobile", "email", "nickname"));

        assertEquals(Set.of("mobile", "email"), stripped);
        assertNull(vo.getMobile());
        assertNull(vo.getEmail());
        assertEquals("张三", vo.getNickname());
    }

    @Test
    @DisplayName("🔴 查不到资源行 = 放行。写反的表现是「所有接口的所有字段全部清空」")
    void testUnknownFieldPasses() {
        modes(Map.of());
        UserVO vo = user();

        helper.crop(vo, DATASET, List.of("mobile", "email"));

        assertEquals("13900001111", vo.getMobile());
        assertEquals("a@b.com", vo.getEmail());
    }

    @Test
    @DisplayName("⚠️ 无登录上下文时不限制 —— 内部调用的结果不流向用户，判成 HIDDEN 会让写路径丢光提交值")
    void testNoLoginPasses() {
        modes(Map.of("mobile", DatasetFieldModeRespDTO.HIDDEN));
        SecurityContextHolder.clearContext();
        UserVO vo = user();

        helper.crop(vo, DATASET, List.of("mobile"));

        assertEquals("13900001111", vo.getMobile());
        verify(permissionApi, never()).getFieldModes(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("权限模块不可用时放行 —— 但那是「权限关着」，实现里必须留 ERROR 痕迹")
    void testApiMissingPasses() {
        helper = new PermissionFieldHelper(provider(null));
        UserVO vo = user();

        helper.crop(vo, DATASET, List.of("mobile"));

        assertEquals("13900001111", vo.getMobile());
    }

    @Test
    @DisplayName("判定抛异常时不裁 —— 把一次偶发故障放大成全量数据丢失，用户会以为数据坏了")
    void testResolveFailurePasses() {
        when(permissionApi.getFieldModes(any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("boom"));
        UserVO vo = user();

        helper.crop(vo, DATASET, List.of("mobile"));

        assertEquals("13900001111", vo.getMobile());
    }

    @Test
    @DisplayName("PageResult 里的每一行都要裁 —— 只裁详情不裁列表，抓包照样看到明文")
    void testCropPageResult() {
        modes(Map.of("mobile", DatasetFieldModeRespDTO.HIDDEN));
        PageResult<UserVO> page = new PageResult<>(List.of(user(), user()), 2L);

        helper.crop(page, DATASET, List.of("mobile"));

        page.getList().forEach(vo -> assertNull(vo.getMobile()));
    }

    @Test
    @DisplayName("List 也要裁（导出走的就是这条）")
    void testCropList() {
        modes(Map.of("mobile", DatasetFieldModeRespDTO.HIDDEN));
        List<UserVO> list = List.of(user(), user());

        helper.crop(list, DATASET, List.of("mobile"));

        list.forEach(vo -> assertNull(vo.getMobile()));
    }

    @Test
    @DisplayName("⚠️ 基本类型字段置不了空时保持原值，不塞 0 —— 字段权限该做的是「不给」，不是「给个假的」")
    void testPrimitiveFieldKeepsValue() {
        modes(Map.of("sex", DatasetFieldModeRespDTO.HIDDEN));
        UserVO vo = user();

        helper.crop(vo, DATASET, List.of("sex"));

        assertEquals(1, vo.getSex());
    }

    @Test
    @DisplayName("不传字段清单时取对象的全部属性")
    void testFieldsOmittedUsesAllProperties() {
        modes(Map.of("nickname", DatasetFieldModeRespDTO.HIDDEN));
        UserVO vo = user();

        helper.crop(vo, DATASET, List.of());

        assertNull(vo.getNickname());
        assertEquals("13900001111", vo.getMobile());
    }

    private UserVO user() {
        UserVO vo = new UserVO();
        vo.setId(1L);
        vo.setNickname("张三");
        vo.setMobile("13900001111");
        vo.setEmail("a@b.com");
        vo.setSex(1);
        return vo;
    }

    private void modes(Map<String, String> modes) {
        DatasetFieldModeRespDTO dto = new DatasetFieldModeRespDTO();
        dto.setModes(modes);
        when(permissionApi.getFieldModes(any(), any(), any(), anyBoolean())).thenReturn(dto);
    }

    private void login(Long userId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(userId);
        loginUser.setUserType(2);
        // ⚠️ request 不能传 null：setLoginUser 内部会读 getRemoteAddr()
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        SecurityFrameworkUtils.setLoginUser(loginUser, request);
    }

    private ObjectProvider<PermissionCommonApi> provider(PermissionCommonApi api) {
        return new ObjectProvider<>() {
            @Override
            public PermissionCommonApi getObject() {
                return api;
            }

            @Override
            public PermissionCommonApi getObject(Object... args) {
                return api;
            }

            @Override
            public PermissionCommonApi getIfAvailable() {
                return api;
            }

            @Override
            public PermissionCommonApi getIfUnique() {
                return api;
            }
        };
    }

}
