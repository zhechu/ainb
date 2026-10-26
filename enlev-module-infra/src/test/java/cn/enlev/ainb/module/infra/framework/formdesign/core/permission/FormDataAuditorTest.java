package cn.enlev.ainb.module.infra.framework.formdesign.core.permission;

import cn.enlev.ainb.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.enlev.ainb.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.enlev.ainb.framework.security.core.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FormDataAuditor} 的单元测试
 *
 * <h3>为什么这个类值得单测</h3>
 *
 * 它的两条审计链路都已经端到端验过（明文查看、按钮调用都真的落进了
 * {@code system_operate_log}）。这里补单测是为了钉住一条**端到端测不出、
 * 却极容易被后人善意改掉**的不变量：
 *
 * <blockquote>
 * 审计记录里<b>绝不能出现字段值本身</b>。
 * </blockquote>
 *
 * 「顺手把看到的值也记下来，排查时更方便」是个非常自然的想法，
 * 而它一旦落地就是二次泄露：敏感值从一张有脱敏与字段权限保护的业务表，
 * 复制进了一张只要有「操作日志」菜单就能翻的表。
 *
 * 端到端测不出这一点 —— 接口照样 200、日志照样有记录，
 * 只有盯着日志内容逐字看才会发现。所以把它写成断言。
 *
 * @author 走进新时代
 */
class FormDataAuditorTest {

    private static final String FORM_CODE = "demo_form";
    private static final Object PK = 2098810661864275968L;

    private FormDataAuditor auditor;
    private OperateLogCommonApi operateLogApi;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        operateLogApi = mock(OperateLogCommonApi.class);
        ObjectProvider<OperateLogCommonApi> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(operateLogApi);
        auditor = new FormDataAuditor(provider);

        // 审计要取登录用户。没有上下文时本类会跳过记录，
        // 那样下面的断言全都验不到东西
        LoginUser loginUser = new LoginUser();
        loginUser.setId(1L);
        loginUser.setUserType(1);
        // ⚠️ request 不能传 null：setLoginUser 内部会读 getRemoteAddr()，
        // 传 null 直接 NPE 在 setUp 里，五条用例会一起变红且报错信息完全指不到真因
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        SecurityFrameworkUtils.setLoginUser(loginUser, request);
    }

    @AfterEach
    void tearDown() {
        // SecurityFrameworkUtils 没有 clear 方法，直接清 Spring Security 的上下文。
        // 必须清 —— 上下文是 ThreadLocal，留着会串到同线程跑的下一个用例
        SecurityContextHolder.clearContext();
    }

    private OperateLogCreateReqDTO captureLog() {
        ArgumentCaptor<OperateLogCreateReqDTO> captor =
                ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);
        verify(operateLogApi).createOperateLogAsync(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("⚠️ 明文查看：记字段名与主键，绝不记字段值")
    void testPlainFieldViewedNeverRecordsValue() {
        auditor.plainFieldViewed(FORM_CODE, PK, "contact_mobile");

        OperateLogCreateReqDTO dto = captureLog();
        String all = dto.getAction() + " " + dto.getExtra();

        assertTrue(all.contains("contact_mobile"), "字段名要记下来，否则查不出看的是哪一列");
        assertTrue(all.contains(String.valueOf(PK)), "记录主键要记下来，否则定位不到哪一条");
        // 这一条是本测试存在的理由
        assertFalse(all.contains("13900001111"),
                "审计里绝不能出现字段值。把明文抄进日志表是二次泄露 —— "
                        + "敏感值就此脱离脱敏与字段权限的管辖，谁能看操作日志谁就能读");
        assertEquals(FormDataAuditor.SUB_TYPE_PLAIN_VIEW, dto.getSubType());
    }

    @Test
    @DisplayName("⚠️ 被拒的明文查看也要记，且带上当时的权限模式")
    void testPlainFieldDeniedIsRecorded() {
        auditor.plainFieldDenied(FORM_CODE, PK, "id_card", "HIDDEN");

        OperateLogCreateReqDTO dto = captureLog();
        assertEquals(FormDataAuditor.SUB_TYPE_PLAIN_DENIED, dto.getSubType());
        assertTrue(dto.getExtra().contains("HIDDEN"),
                "要记下被拒时的权限模式 —— 只记「被拒了」的话，"
                        + "事后分不清是权限配置问题还是探测行为");
        // 被拒这条尤其不能带值：它本来就是「不该让这个人看到」的字段
        assertFalse((dto.getAction() + dto.getExtra()).contains("110101199001011234"));
    }

    @Test
    @DisplayName("⚠️ 按钮调用：只记参数名，不记参数值")
    void testButtonInvokedRecordsParamNamesOnly() {
        auditor.buttonInvoked(FORM_CODE, "void_bill", "bill_void_api", PK,
                List.of("p_mobile", "p_bank"));

        OperateLogCreateReqDTO dto = captureLog();
        String all = dto.getAction() + " " + dto.getExtra();

        assertTrue(all.contains("p_mobile") && all.contains("p_bank"), "参数名要记");
        assertTrue(all.contains("bill_void_api"), "调了哪个接口是这条记录的重点");
        assertFalse(all.contains("6222020200198765432"),
                "入参是服务端从记录里取的，里面可能有账号手机号 —— 只记名字");
    }

    @Test
    @DisplayName("操作日志模块不可用时不抛异常，业务不该被审计拖垮")
    @SuppressWarnings("unchecked")
    void testMissingApiDoesNotBreakBusiness() {
        ObjectProvider<OperateLogCommonApi> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        FormDataAuditor lonely = new FormDataAuditor(empty);

        assertDoesNotThrow(() -> lonely.plainFieldViewed(FORM_CODE, PK, "mobile"),
                "审计写不了不该让用户的操作失败 —— "
                        + "那会把一次运维问题放大成业务不可用");
    }

    @Test
    @DisplayName("没有登录上下文时跳过记录，而不是构造一条 userId 为空的非法日志")
    void testNoLoginUserSkipsRecording() {
        SecurityContextHolder.clearContext();

        auditor.plainFieldViewed(FORM_CODE, PK, "mobile");

        verify(operateLogApi, never()).createOperateLogAsync(org.mockito.ArgumentMatchers.any());
    }

}
