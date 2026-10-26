package cn.enlev.ainb.module.agent.service.knowledge;

import cn.hutool.core.util.StrUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentDocMarkdownSanitizer} 的单元测试（纯函数，不碰 DB / Spring / 磁盘）
 *
 * <h3>🔴 每条规则都配一个<b>阳性对照</b>：钉住「不该被改的东西」</h3>
 * 清洗器最危险的失败形态不是「没洗干净」，而是<b>洗掉了正文</b> ——
 * 尤其这两类：
 * <ul>
 *   <li><b>代码块里的示例</b>。「怎么配」的答案往往就在代码块里，而代码块里出现
 *       {@code [文字](./x)}、{@code ![](...)} 是示例不是链接。整篇正则一把梭会把它们改坏，
 *       而改坏之后<b>没有任何报错</b>：灌库照常成功，只是 AI 引用的那段代码从此是错的。</li>
 *   <li><b>标题行</b>。{@code ## } 是这类手册唯一的结构信号（切分器靠它对齐段落边界，
 *       召回结果靠它说明「这段讲的是哪一节」），被吃掉等于把语料拍平。</li>
 * </ul>
 * 所以每条规则一个「该被改」的用例 + 一个「不该被改」的对照，缺一半这组测试就是恒真的。
 *
 * @author 走进新时代
 */
public class AgentDocMarkdownSanitizerTest {

    private static final String BASE = "https://doc.enlev.cn";

    // ==================== 规则一：frontmatter ====================

    @Test
    @DisplayName("规则一：开头的 frontmatter 整块删掉（它是给 VitePress 的，不是给人看的）")
    public void testFrontmatterRemoved() {
        String raw = """
                ---
                title: 用户管理
                outline: [2,3]
                ---

                # 用户管理

                正文。""";
        String out = AgentDocMarkdownSanitizer.sanitize(raw, "guide/01-system/user.md", BASE);
        assertEquals("# 用户管理\n\n正文。", out);
    }

    @Test
    @DisplayName("规则一·阳性对照：正文中间的水平分割线 --- 不是 frontmatter，后面的内容必须留着")
    public void testHorizontalRuleIsNotFrontmatter() {
        // 🔴 这条对照钉的是「只认第一行」：若按「成对的 --- 之间是 frontmatter」来判，
        //    下面这篇会被从第一条分割线起整篇吃掉 —— 而吃掉的恰好是正文最实的那半
        String raw = """
                # 标题

                上半段。

                ---

                下半段。""";
        String out = AgentDocMarkdownSanitizer.sanitize(raw, "guide/x.md", BASE);
        assertTrue(out.contains("上半段。"), "分割线之前的正文要留着");
        assertTrue(out.contains("下半段。"), "🔴 分割线之后的正文也要留着，它不是 frontmatter");
        assertTrue(out.contains("---"), "分割线本身是正文的一部分，原样穿过");
    }

    // ==================== 规则二：mermaid ====================

    @Test
    @DisplayName("规则二：mermaid 围栏整块删掉（图语法对检索是纯噪声）")
    public void testMermaidFenceDropped() {
        String raw = """
                # 流程

                ```mermaid
                graph TD
                  A[开始] --> B{判断}
                ```

                说明文字。""";
        String out = AgentDocMarkdownSanitizer.sanitize(raw, "guide/x.md", BASE);
        assertEquals("# 流程\n\n说明文字。", out);
        assertFalse(out.contains("graph TD"), "图语法不能残留");
        assertFalse(out.contains("```"), "mermaid 的围栏本身也要删，留一半只会让下游切分器误判");
    }

    @Test
    @DisplayName("规则二·阳性对照：其它语言的代码块<b>逐字</b>保留（含围栏与里面的 markdown 示例）")
    public void testNonMermaidFenceUntouched() {
        String raw = """
                # 配置

                ```yaml
                enlev:
                  agent:
                    doc:
                      root-path: C:/code/xpy-blog/docs
                ```

                写法如下：

                ```md
                [用户管理](../01-system/user.md)
                ![授权抽屉](./grant.png)
                ::: warning 注意
                ```""";
        String out = AgentDocMarkdownSanitizer.sanitize(raw, "guide/x.md", BASE);
        assertTrue(out.contains("root-path: C:/code/xpy-blog/docs"), "yaml 示例逐字保留");
        // 🔴 这两条是整组测试里最要紧的对照：代码块里的链接与图片是【示例】，
        //    被改写成公开 URL 或「（图：…）」之后，用户照着抄就配错了
        assertTrue(out.contains("[用户管理](../01-system/user.md)"), "代码块里的链接示例不能被改写");
        assertTrue(out.contains("![授权抽屉](./grant.png)"), "代码块里的图片示例不能被改写");
        assertTrue(out.contains("::: warning 注意"), "代码块里的容器围栏示例不能被拆");
        assertEquals(4, StrUtil.count(out, "```"), "yaml 与 md 两对的围栏记号原样保留");
    }

    // ==================== 规则三：图片 ====================

    @Test
    @DisplayName("规则三：图片换成「（图：alt）」—— alt 常含界面词，url 是噪声")
    public void testImageRewritten() {
        String out = AgentDocMarkdownSanitizer.sanitize(
                "点开后是这样：\n\n![授权抽屉](/images/grant-drawer.png \"标题\")\n\n按上面勾选。",
                "guide/01-system/grant.md", BASE);
        assertTrue(out.contains("（图：授权抽屉）"), "alt 里的界面词要留下，它是好的检索锚");
        assertFalse(out.contains("grant-drawer.png"), "图片地址对检索是噪声");
        assertFalse(out.contains("标题"), "图片的 title 属性也是噪声");
    }

    @Test
    @DisplayName("规则三·阳性对照：alt 为空的图片整处删掉，不留一个空壳「（图：）」")
    public void testImageWithoutAltDropped() {
        String out = AgentDocMarkdownSanitizer.sanitize("上文\n\n![](/x.png)\n\n下文", "guide/x.md", BASE);
        assertEquals("上文\n\n下文", out);
    }

    // ==================== 规则四：容器围栏 ====================

    @Test
    @DisplayName("规则四：::: 容器围栏删掉，但保留它那一行上的标题文字")
    public void testContainerUnwrapped() {
        String raw = """
                ::: warning 入参里的变量引用语法
                这里可以用 {{$.rec_out.list}} 取上一节点的输出。
                :::

                ::: tip
                没有标题的容器。
                :::""";
        String out = AgentDocMarkdownSanitizer.sanitize(raw, "guide/x.md", BASE);
        assertTrue(out.contains("入参里的变量引用语法"), "容器标题是好的检索锚，要留");
        assertTrue(out.contains("这里可以用 {{$.rec_out.list}} 取上一节点的输出。"), "容器正文要留");
        assertTrue(out.contains("没有标题的容器。"), "无标题容器的正文也要留");
        assertFalse(out.contains(":::"), "围栏本身对检索是噪声");
    }

    // ==================== 规则五：v-pre ====================

    @Test
    @DisplayName("规则五：<code v-pre> 还原成里面的原文（v-pre 只是叫 VitePress 别编译）")
    public void testVPreUnwrapped() {
        String out = AgentDocMarkdownSanitizer.sanitize(
                "取值写 <code v-pre>{{$.rec_out.list[0].urgent}}</code> 即可。",
                "guide/x.md", BASE);
        assertEquals("取值写 {{$.rec_out.list[0].urgent}} 即可。", out);
    }

    // ==================== 规则六：链接 ====================

    @Test
    @DisplayName("规则六：相对链接改写成手册站的公开地址（含 .. 归一化与 cleanUrls 去后缀）")
    public void testRelativeLinkRewritten() {
        String out = AgentDocMarkdownSanitizer.sanitize(
                "详见[用户管理](../01-system/user.md)与[快速开始](/guide/quick-start.md)。",
                "guide/04-ai/assistant.md", BASE);
        assertTrue(out.contains("[用户管理](https://doc.enlev.cn/guide/01-system/user)"),
                ".. 要归一化，且 cleanUrls 下不带 .md 后缀");
        assertTrue(out.contains("[快速开始](https://doc.enlev.cn/guide/quick-start)"),
                "以 / 开头的是站点根相对路径");
    }

    @Test
    @DisplayName("规则六：同页锚点降级成纯文本 —— 不猜中文 slug")
    public void testSamePageAnchorDegraded() {
        String out = AgentDocMarkdownSanitizer.sanitize(
                "见[常见问题](#常见问题)一节。", "guide/x.md", BASE);
        assertEquals("见常见问题一节。", out);
        // 🔴 理由记在 rewriteLink 上：VitePress 的中文 slug 规则反直觉到手册站专门为它写了门禁
        //    （check-anchors.cjs，其 README 原话「中文 slug 规则反直觉，手写必错」）。
        //    在 Java 侧再实现一份必然对不上，产出一批「看着像链接、点开跳错位置」的引用 —— 那比不给链接更坏。
        assertFalse(out.contains("]("), "不能留下半个链接语法");
    }

    @Test
    @DisplayName("规则六·阳性对照：绝对地址（http/https/mailto/tel）原样保留，不二次拼接")
    public void testAbsoluteLinkKept() {
        String out = AgentDocMarkdownSanitizer.sanitize(
                "[上游](https://example.com/a.md) [邮件](mailto:a@b.c) [电话](tel:10086)",
                "guide/x.md", BASE);
        assertEquals("[上游](https://example.com/a.md) [邮件](mailto:a@b.c) [电话](tel:10086)", out);
    }

    // ==================== 规则七：空行 ====================

    @Test
    @DisplayName("规则七：连续空行压成一行，首尾空行去掉")
    public void testBlankLinesCollapsed() {
        String out = AgentDocMarkdownSanitizer.sanitize(
                "\n\n\n# 标题\n\n\n\n\n正文\n\n\n", "guide/x.md", BASE);
        assertEquals("# 标题\n\n正文", out);
    }

    @Test
    @DisplayName("规则七·阳性对照：行尾空白去掉后「看着是空行、其实有空格」的行也算空行")
    public void testWhitespaceOnlyLineIsBlank() {
        String out = AgentDocMarkdownSanitizer.sanitize("甲\n   \n\t\n乙", "guide/x.md", BASE);
        assertEquals("甲\n\n乙", out);
    }

    // ==================== 🔴 底线：标题行必须原样穿过 ====================

    @Test
    @DisplayName("底线：# / ## / ### 标题与表格原样穿过 —— 标题是这类手册唯一的结构信号")
    public void testHeadingsAndTablesSurvive() {
        String raw = """
                # 一级

                ## 二级 ★

                ### 三级

                | 列 A | 列 B |
                |---|---|
                | 1 | 2 |""";
        String out = AgentDocMarkdownSanitizer.sanitize(raw, "guide/x.md", BASE);
        assertEquals(raw, out);
    }

    @Test
    @DisplayName("边界：null / 空串返回空串，不抛异常")
    public void testEmptyInput() {
        assertEquals("", AgentDocMarkdownSanitizer.sanitize(null, "guide/x.md", BASE));
        assertEquals("", AgentDocMarkdownSanitizer.sanitize("", "guide/x.md", BASE));
    }

    // ==================== toPublicUrl 映射表 ====================

    @Test
    @DisplayName("toPublicUrl：映射表逐行对齐 VitePress cleanUrls 的事实")
    public void testToPublicUrl() {
        // 🔴 口径不是猜的：产物是 guide/04-ai/assistant.html，服务器把 /guide/04-ai/assistant 映射过去，
        //    index.md 映射到所在目录的 /。写错一位，引用链接点开就是 404 —— 而 404 不会报错，只会让人不信任 AI
        assertEquals(BASE + "/", AgentDocMarkdownSanitizer.toPublicUrl("index.md", BASE));
        assertEquals(BASE + "/guide/", AgentDocMarkdownSanitizer.toPublicUrl("guide/index.md", BASE));
        assertEquals(BASE + "/guide/01-system/user",
                AgentDocMarkdownSanitizer.toPublicUrl("guide/01-system/user.md", BASE));
        assertEquals(BASE + "/changelog", AgentDocMarkdownSanitizer.toPublicUrl("changelog.md", BASE));
        assertEquals(BASE + "/develop/03-agent/overview",
                AgentDocMarkdownSanitizer.toPublicUrl("develop/03-agent/overview.markdown", BASE));
    }

    @Test
    @DisplayName("toPublicUrl：锚点/查询串先切掉，反斜杠归一，base 末尾的斜杠被吃掉")
    public void testToPublicUrlEdgeCases() {
        assertEquals(BASE + "/guide/01-system/user",
                AgentDocMarkdownSanitizer.toPublicUrl("guide/01-system/user.md#常见问题", BASE));
        assertEquals(BASE + "/guide/01-system/user",
                AgentDocMarkdownSanitizer.toPublicUrl("guide/01-system/user.md?a=1", BASE));
        assertEquals(BASE + "/guide/01-system/user",
                AgentDocMarkdownSanitizer.toPublicUrl("guide\\01-system\\user.md", BASE + "/"));
        assertEquals(BASE + "/", AgentDocMarkdownSanitizer.toPublicUrl(null, BASE));
    }

    // ==================== extractTitle ====================

    @Test
    @DisplayName("extractTitle：取首个一级标题，★ 后缀保留（它是「本页主体为自研功能」的标记）")
    public void testExtractTitle() {
        assertEquals("用户管理",
                AgentDocMarkdownSanitizer.extractTitle("# 用户管理\n\n正文", "guide/01-system/user.md"));
        assertEquals("权限控制 ★",
                AgentDocMarkdownSanitizer.extractTitle("## 先来个二级\n\n# 权限控制 ★\n", "guide/x.md"));
        // 前 40 行里找不到一级标题时回落到文件名，并去掉序号前缀
        assertEquals("常见坑",
                AgentDocMarkdownSanitizer.extractTitle("没有一级标题\n", "develop/05-practice/09-常见坑.md"));
        assertNull(AgentDocMarkdownSanitizer.extractTitle(null, null));
    }

    // ==================== 相对路径归一化 ====================

    @Test
    @DisplayName("resolveRelative：.. 与 . 逐级归一，越过根就不再往上")
    public void testResolveRelative() {
        assertEquals("guide/01-system/user.md",
                AgentDocMarkdownSanitizer.resolveRelative("guide/04-ai", "../01-system/user.md"));
        assertEquals("changelog.md",
                AgentDocMarkdownSanitizer.resolveRelative("guide/04-ai", "../../changelog.md"));
        assertEquals("changelog.md",
                AgentDocMarkdownSanitizer.resolveRelative("guide", "../../../changelog.md"));
        assertEquals("guide/04-ai/quick-start.md",
                AgentDocMarkdownSanitizer.resolveRelative("guide/04-ai", "./quick-start.md"));
        assertEquals("guide/x.md", AgentDocMarkdownSanitizer.resolveRelative("", "guide/x.md"));
    }

    @Test
    @DisplayName("parentOf：取父目录，根下的文件得到空串")
    public void testParentOf() {
        assertEquals("guide/04-ai", AgentDocMarkdownSanitizer.parentOf("guide/04-ai/x.md"));
        assertEquals("guide", AgentDocMarkdownSanitizer.parentOf("guide/x.md"));
        assertEquals("", AgentDocMarkdownSanitizer.parentOf("index.md"));
        assertEquals("", AgentDocMarkdownSanitizer.parentOf(null));
    }

}
