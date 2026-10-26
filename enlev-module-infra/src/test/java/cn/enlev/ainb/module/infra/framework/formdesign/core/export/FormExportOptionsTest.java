package cn.enlev.ainb.module.infra.framework.formdesign.core.export;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FormExportOptions} 的单元测试
 *
 * 这个类本身很薄，但 {@code parseScope} 的兜底行为值得钉住：
 * 导出是个读操作，为一个拼错的枚举值让用户导不出来不值得，
 * 所以认不出一律按「全部」处理。反过来（认不出就抛）会让
 * 前端某次发了个新值、后端还没部署时，导出整体不可用。
 *
 * @author 走进新时代
 */
public class FormExportOptionsTest extends BaseMockitoUnitTest {

    @Test
    @DisplayName("默认是「全部」—— 不给值时不该悄悄改变改造前的行为")
    void testDefaultScope() {
        FormExportOptions options = new FormExportOptions();
        assertEquals(FormExportOptions.Scope.ALL, options.getScope());
        assertFalse(options.isCurrentPage());
    }

    @Test
    @DisplayName("默认不指定合计行 —— 由服务端按「跟随列表」处理")
    void testDefaultSummaryIsNull() {
        // ⚠️ 默认必须是 null 而不是 false：false 会把「跟随列表」这层语义盖掉，
        // 日后列表加了合计行也导不出来
        assertNull(new FormExportOptions().getIncludeSummary());
    }

    @Test
    @DisplayName("范围解析大小写不敏感")
    void testParseScope() {
        assertEquals(FormExportOptions.Scope.CURRENT_PAGE,
                FormExportOptions.parseScope("CURRENT_PAGE"));
        assertEquals(FormExportOptions.Scope.CURRENT_PAGE,
                FormExportOptions.parseScope("current_page"));
        assertEquals(FormExportOptions.Scope.CURRENT_PAGE,
                FormExportOptions.parseScope("  CurrentPage  ".replace("CurrentPage", "current_page")));
        assertEquals(FormExportOptions.Scope.ALL, FormExportOptions.parseScope("ALL"));
    }

    @Test
    @DisplayName("⚠️ 认不出的范围按「全部」处理，不抛异常")
    void testParseScopeFallback() {
        // 抛异常的话，前端发了个新值而后端还没部署时，导出会整体不可用
        assertEquals(FormExportOptions.Scope.ALL, FormExportOptions.parseScope("SOMETHING_NEW"));
        assertEquals(FormExportOptions.Scope.ALL, FormExportOptions.parseScope(null));
        assertEquals(FormExportOptions.Scope.ALL, FormExportOptions.parseScope(""));
        assertEquals(FormExportOptions.Scope.ALL, FormExportOptions.parseScope("   "));
    }

    @Test
    @DisplayName("isCurrentPage 跟着 scope 走")
    void testIsCurrentPage() {
        FormExportOptions options = new FormExportOptions();
        options.setScope(FormExportOptions.Scope.CURRENT_PAGE);
        assertTrue(options.isCurrentPage());
    }

}
