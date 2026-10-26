package cn.enlev.ainb.module.system.controller.admin.permission;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.GrantRespVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_SUBJECT_IDENTIFIER_REQUIRED;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_SUBJECT_TYPE_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GrantAdminController#listBySubject} 的主体寻址解析
 *
 * <h3>钉住的是「参数不全时的表现」，不是查询本身</h3>
 *
 * 这个端点原先自己内联了一套解析，两条分支各错一种，而两种错法都比直接报错更坏：
 *
 * <ul>
 *   <li>类型解析不出来 ⟹ {@code return success(List.of())}：一次参数失误被伪装成
 *       「这个主体没有任何授权」这个<b>合法业务结论</b></li>
 *   <li>类型对但少给标识 ⟹ 直接 {@code new SubjectKey(...)}，撞上 record 紧凑构造器里的
 *       {@code IllegalArgumentException}（code 型缺 code）与 {@code Objects.requireNonNull}
 *       的 {@code NullPointerException}（ref 型缺 refId）。两者都没有对应的全局 handler，
 *       于是落到兜底分支：用户读到「系统异常」，{@code infra_api_error_log} 里各多一条
 *       3010 字节的堆栈 —— 这两条是对着跑起来的后端实测复现出来的，不是推测</li>
 * </ul>
 *
 * 现在它与 {@code /effective-tree} 共用 {@code resolveSubject}，三种情况一律报可读的业务错误：
 * 类型认不出来报 {@code GRANT_SUBJECT_TYPE_INVALID}，类型对但少给标识报
 * {@code GRANT_SUBJECT_IDENTIFIER_REQUIRED} 并点名缺哪个参数。
 *
 * <p>🔴 下面几条断言的是<b>整句消息</b>而不只是错误码，这是刻意的：
 * 这两个码原先是一条，模板写作「类型 {} 不可识别，或缺少定位该主体所需的编号/编码」，
 * 而「少给标识」那一支只能把 {@code "SYSTEM 缺少 subjectCode"} 整个塞进 {@code {}}，
 * 实测拼出来是「类型 SYSTEM 缺少 subjectCode 不可识别，或缺少……」——
 * 一句话里同时说「缺少」和「不可识别」。错误码对了、消息仍然是坏的，
 * 只断言 code 的话这种回归抓不到
 *
 * <p>⚠️ 用纯 Mockito 而不是 BaseDbUnitTest：这里要验的恰恰是「请求<b>压根没有</b>落到
 * mapper 上」，真连数据库的话那条 {@code never()} 断言就无从谈起
 *
 * @author 走进新时代
 */
public class GrantAdminControllerTest extends BaseMockitoUnitTest {

    @InjectMocks
    private GrantAdminController controller;

    @Mock
    private GrantMapper grantMapper;

    // ===================== 三种参数错误：一律可读的业务错误 =====================

    @Test
    @DisplayName("code 型主体缺 subjectCode：报业务错误，而不是 IllegalArgumentException 变成的 500")
    void testListBySubjectCodeTypeWithoutCode() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.listBySubject("SYSTEM", null, null));

        assertEquals(GRANT_SUBJECT_IDENTIFIER_REQUIRED.getCode(), ex.getCode());
        // 🔴 整句比对而不是 contains：只断言错误码抓不到「模板套不上参数」那种回归（见类注释）
        assertEquals("无法定位授权主体：SYSTEM 类型的主体必须给 subjectCode", ex.getMessage());
        verify(grantMapper, never()).selectListBySubject(any());
    }

    @Test
    @DisplayName("ref 型主体缺 subjectRefId：报业务错误，而不是 requireNonNull 的 NPE 变成的 500")
    void testListBySubjectRefTypeWithoutRefId() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.listBySubject("ROLE", null, null));

        assertEquals(GRANT_SUBJECT_IDENTIFIER_REQUIRED.getCode(), ex.getCode());
        assertEquals("无法定位授权主体：ROLE 类型的主体必须给 subjectRefId", ex.getMessage());
        verify(grantMapper, never()).selectListBySubject(any());
    }

    @Test
    @DisplayName("类型解析不出来：报错，而不是返回一个会被读成「该主体没有授权」的空列表")
    void testListBySubjectUnparseableType() {
        // 🔴 这一条是**行为变更**，原先这里 return success(List.of())。
        // 空列表在授权配置页上的读法是「这个主体没有任何授权」，而真实原因只是类型拼错了 ——
        // 把参数错误伪装成一个合法的业务结论，比直接报错危险得多
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.listBySubject("ROLES", null, null)); // 复数，最常见的笔误

        assertEquals(GRANT_SUBJECT_TYPE_INVALID.getCode(), ex.getCode());
        assertEquals("无法定位授权主体：类型 ROLES 不可识别", ex.getMessage());
        verify(grantMapper, never()).selectListBySubject(any());
    }

    @Test
    @DisplayName("ALL 也是 code 寻址：缺 subjectCode 同样要拦，不能漏成 500")
    void testListBySubjectAllTypeWithoutCode() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.listBySubject("ALL", null, null));

        assertEquals(GRANT_SUBJECT_IDENTIFIER_REQUIRED.getCode(), ex.getCode());
        assertEquals("无法定位授权主体：ALL 类型的主体必须给 subjectCode", ex.getMessage());
        verify(grantMapper, never()).selectListBySubject(any());
    }

    // ===================== 参数齐全：照常查，且寻址串构造正确 =====================

    @Test
    @DisplayName("ref 型参数齐全：拿 ROLE:refId 去查，空结果正常返回")
    void testListBySubjectRefTypeOk() {
        when(grantMapper.selectListBySubject(any())).thenReturn(List.<GrantDO>of());

        CommonResult<List<GrantRespVO>> result = controller.listBySubject("ROLE", 9000000576L, null);

        assertEquals(0, result.getCode().intValue());
        assertTrue(result.getData().isEmpty());
        // 🔴 断言的是**传下去的寻址串**，不只是「没抛」：解析对了但拼错 key，
        // 查回来照样是空列表，症状与上面那条被修掉的 bug 一模一样
        ArgumentCaptor<SubjectKey> captor = ArgumentCaptor.forClass(SubjectKey.class);
        verify(grantMapper).selectListBySubject(captor.capture());
        assertEquals(SubjectKey.ofRole(9000000576L), captor.getValue());
    }

    @Test
    @DisplayName("code 型参数齐全：拿 SYSTEM:code 去查")
    void testListBySubjectCodeTypeOk() {
        when(grantMapper.selectListBySubject(any())).thenReturn(List.<GrantDO>of());

        CommonResult<List<GrantRespVO>> result = controller.listBySubject("SYSTEM", null, "bpm-engine");

        assertEquals(0, result.getCode().intValue());
        ArgumentCaptor<SubjectKey> captor = ArgumentCaptor.forClass(SubjectKey.class);
        verify(grantMapper).selectListBySubject(captor.capture());
        assertEquals(SubjectKey.ofSystem("bpm-engine"), captor.getValue());
    }

}
