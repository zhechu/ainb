package cn.enlev.ainb.module.infra.framework.formdesign.core.widget;

import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超链接协议白名单的测试
 *
 * 这些用例守的是一件事：**表单设计者配的地址，填表人点下去不能执行脚本**。
 * 两者往往不是同一个人、也不是同一个信任级别。
 *
 * 绕过手法都写成了独立用例 —— 黑名单永远列不全，正是这些变形逼着我们用白名单。
 *
 * @author 走进新时代
 */
class LinkUrlGuardTest {

    @Test
    @DisplayName("白名单内的协议放行")
    void testAllowed() {
        assertTrue(LinkUrlGuard.isSafe("https://example.com/a?b=1#c"));
        assertTrue(LinkUrlGuard.isSafe("http://example.com"));
        assertTrue(LinkUrlGuard.isSafe("mailto:a@b.com"));
        assertTrue(LinkUrlGuard.isSafe("tel:+8613812345678"));
    }

    @Test
    @DisplayName("相对地址、锚点、查询串没有协议，一律放行")
    void testRelative() {
        assertTrue(LinkUrlGuard.isSafe("/infra/form-design"));
        assertTrue(LinkUrlGuard.isSafe("./detail"));
        assertTrue(LinkUrlGuard.isSafe("#anchor"));
        assertTrue(LinkUrlGuard.isSafe("?id=1"));
        // 路径里出现冒号不是协议：判据是冒号要在第一个 / ? # 之前
        assertTrue(LinkUrlGuard.isSafe("/a:b/c"));
    }

    @Test
    @DisplayName("⚠️ javascript: 与 data: 被拦下")
    void testDangerous() {
        assertFalse(LinkUrlGuard.isSafe("javascript:alert(1)"));
        assertFalse(LinkUrlGuard.isSafe("data:text/html,<script>alert(1)</script>"));
        assertFalse(LinkUrlGuard.isSafe("vbscript:msgbox(1)"));
        // file: 不在白名单里 —— 它能探测本机文件系统
        assertFalse(LinkUrlGuard.isSafe("file:///etc/passwd"));
    }

    @Test
    @DisplayName("⚠️ 大小写、空格、控制字符的变形也要拦 —— 这正是不能用黑名单的原因")
    void testEvasion() {
        assertFalse(LinkUrlGuard.isSafe("JaVaScRiPt:alert(1)"));
        assertFalse(LinkUrlGuard.isSafe("  javascript:alert(1)"));
        // 制表符 / 换行 / 空字符插在协议中间，浏览器照样当成 javascript:
        assertFalse(LinkUrlGuard.isSafe("java\tscript:alert(1)"));
        assertFalse(LinkUrlGuard.isSafe("java\nscript:alert(1)"));
        // 空字符变形用 (char) 0 显式拼接：源码里直接写真实控制字符会让整个文件被当成二进制
        assertFalse(LinkUrlGuard.isSafe("java" + (char) 0 + "script:alert(1)"));
        assertFalse(LinkUrlGuard.isSafe("javascript:alert(1)"));
    }

    @Test
    @DisplayName("空地址算安全 —— 那只是没配，不是危险")
    void testBlank() {
        assertTrue(LinkUrlGuard.isSafe(null));
        assertTrue(LinkUrlGuard.isSafe(""));
        assertTrue(LinkUrlGuard.isSafe("   "));
    }

    // ==================== 从表单规则里找 ====================

    private String fields(String... rules) {
        return FormFieldsCodec.write(List.of(rules));
    }

    private String link(String title, String url) {
        return String.format("{\"type\":\"Link\",\"title\":\"%s\",\"props\":{\"url\":\"%s\"}}",
                title, url);
    }

    @Test
    @DisplayName("找出地址不安全的超链接，报错带控件标题")
    void testFindUnsafe() {
        List<LinkUrlGuard.UnsafeLink> unsafe = LinkUrlGuard.findUnsafeLinks(fields(
                link("正常链接", "https://example.com"),
                link("危险链接", "javascript:alert(1)")));
        assertEquals(1, unsafe.size());
        // 报标题而不是字段 ID —— 用户看不懂 fc_a1b2c3
        assertEquals("危险链接", unsafe.get(0).getTitle());
    }

    @Test
    @DisplayName("布局容器与子表里的超链接同样要查 —— 藏在栅格里就绕过了才是真问题")
    void testNested() {
        String inLayout = "{\"type\":\"col\",\"children\":[" + link("栅格里", "javascript:1") + "]}";
        String inSubTable = "{\"type\":\"group\",\"field\":\"items\",\"props\":{\"rule\":["
                + link("明细里", "data:text/html,x") + "]}}";
        assertEquals(2, LinkUrlGuard.findUnsafeLinks(fields(inLayout, inSubTable)).size());
    }

    @Test
    @DisplayName("没有超链接控件时返回空列表")
    void testNoLink() {
        assertTrue(LinkUrlGuard.findUnsafeLinks(
                fields("{\"type\":\"input\",\"field\":\"a\"}")).isEmpty());
        assertTrue(LinkUrlGuard.findUnsafeLinks(null).isEmpty());
    }

}
