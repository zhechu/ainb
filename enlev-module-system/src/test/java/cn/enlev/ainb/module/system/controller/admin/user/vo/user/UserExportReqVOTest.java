package cn.enlev.ainb.module.system.controller.admin.user.vo.user;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UserExportReqVO} 的单元测试
 *
 * 这个类很薄，但 {@code resolveScope} 的兜底值得钉住：
 * 导出是个读操作，为一个拼错的枚举值让用户导不出来不值得。
 * 反过来（认不出就抛）会让前端发了个新值、后端还没部署时，导出整体不可用。
 *
 * @author 芋道源码
 */
public class UserExportReqVOTest extends BaseMockitoUnitTest {

    @Test
    @DisplayName("默认是「全部」—— 不给值时不该悄悄改变改造前的行为")
    void testDefaultScope() {
        UserExportReqVO vo = new UserExportReqVO();
        assertEquals(UserExportReqVO.Scope.ALL, vo.resolveScope());
        assertFalse(vo.isCurrentPage());
    }

    @Test
    @DisplayName("范围解析大小写不敏感，且忽略首尾空白")
    void testResolveScope() {
        UserExportReqVO vo = new UserExportReqVO();
        vo.setScope("CURRENT_PAGE");
        assertTrue(vo.isCurrentPage());
        vo.setScope("current_page");
        assertTrue(vo.isCurrentPage());
        vo.setScope("  current_page  ");
        assertTrue(vo.isCurrentPage());
        vo.setScope("ALL");
        assertFalse(vo.isCurrentPage());
    }

    @Test
    @DisplayName("⚠️ 认不出的范围按「全部」处理，不抛异常")
    void testResolveScopeFallback() {
        UserExportReqVO vo = new UserExportReqVO();
        for (String raw : new String[]{"SOMETHING_NEW", "", "   ", null}) {
            vo.setScope(raw);
            assertEquals(UserExportReqVO.Scope.ALL, vo.resolveScope(),
                    "认不出的值应当回落到 ALL：" + raw);
        }
    }

}
