package cn.enlev.ainb.module.agent.service.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手册 markdown 的语料清洗（纯函数，无 IO）
 *
 * <h3>为什么要清洗：脏内容会<b>直接</b>变成脏向量</h3>
 * 上游的切分器（{@code MarkdownQaSplitter} / {@code SemanticTextSplitter}）只按长度与段落边界切，
 * <b>一个字都不改</b>。所以 markdown 里那些「给渲染器看、不给人读」的东西会原样进 chunk、原样进 embedding：
 * 实测 108 篇语料里有 39 篇含 mermaid 图（单张可达数百字符的图语法）、280 处图片、16 处 {@code <code v-pre>}、
 * 600+ 个 {@code :::} 容器围栏。这些字符在向量空间里不是中性的 —— 它们把段落往「代码/标记」那一侧拽，
 * 于是用户问「字段权限在哪配」时，含大量图语法的段落反而更容易被召回。
 *
 * <h3>🔴 清洗的底线：<b>不能把 {@code ## } 标题吃掉</b></h3>
 * 标题是这类手册唯一的结构信号：切分器靠它对齐段落边界，召回结果靠它说明「这段讲的是哪一节」。
 * 本类的每条规则都只动「围栏 / 图 / 转义标签 / 链接地址」，标题行原样穿过。
 *
 * <h3>🔴 代码块内部一律不动</h3>
 * 「怎么配」的答案往往就在代码块里，而代码块里出现 {@code [文字](./x)}、{@code ![](...)} 是<b>示例</b>，
 * 不是链接。所以这里逐行跑一个围栏状态机：进了非 mermaid 围栏就原样透传，
 * 只有围栏外的正文才做图片/链接/容器处理。按整篇正则一把梭会把示例代码改坏。
 *
 * @author 走进新时代
 */
public final class AgentDocMarkdownSanitizer {

    /** 图片：{@code ![alt](url "title")}。alt 常含界面词（有价值），url 是噪声 */
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(\\s*([^)\\s]*)(?:\\s+\"[^\"]*\")?\\s*\\)");

    /** 链接：{@code [text](target "title")}。图片已先一步替换掉，故这里不会撞上 {@code ![]} */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\(\\s*([^)\\s]*)(?:\\s+\"[^\"]*\")?\\s*\\)");

    /** {@code <code v-pre>{{$.var}}</code>} —— v-pre 只是告诉 VitePress「别编译这段」，标签本身对 embedding 是噪声 */
    private static final Pattern V_PRE = Pattern.compile("<code[^>]*\\bv-pre\\b[^>]*>(.*?)</code>", Pattern.DOTALL);

    /** VitePress 容器：{@code ::: warning 标题} 开、{@code :::} 闭 */
    private static final Pattern CONTAINER = Pattern.compile("^:::\\s*([A-Za-z][A-Za-z0-9_-]*)?\\s*(.*)$");

    /** 代码围栏：``` 或 ~~~，后面可跟语言名 */
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})\\s*(\\S*)\\s*$");

    private AgentDocMarkdownSanitizer() {
    }

    /**
     * 清洗一篇手册
     *
     * @param raw     markdown 原文
     * @param docPath 该文档相对语料根的路径（如 {@code guide/04-ai/assistant.md}），用于解析相对链接
     * @param baseUrl 手册站公开地址前缀（如 {@code https://doc.enlev.cn}）
     * @return 清洗后的正文；入参为空则返回空串
     */
    public static String sanitize(String raw, String docPath, String baseUrl) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String[] lines = raw.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);

        boolean inFrontmatter = false;
        boolean inFence = false;
        char fenceChar = 0;
        boolean dropping = false;

        for (int i = 0; i < lines.length; i++) {
            String line = stripCr(lines[i]);

            // frontmatter 只可能出现在第一行：它是给 VitePress 的（title/layout/outline），不是给人看的。
            // 🔴 只认第一行，否则正文里的水平分割线 --- 会被当成 frontmatter 开头，把后面整篇吃掉
            if (i == 0 && line.equals("---")) {
                inFrontmatter = true;
                continue;
            }
            if (inFrontmatter) {
                if (line.equals("---") || line.equals("...")) {
                    inFrontmatter = false;
                }
                continue;
            }

            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marks = fence.group(1);
                if (!inFence) {
                    inFence = true;
                    fenceChar = marks.charAt(0);
                    // mermaid 整块删：53 张图的图语法对检索是纯噪声，删掉不影响任何一句人话
                    dropping = fence.group(2).toLowerCase().startsWith("mermaid");
                    if (!dropping) {
                        addLine(out, line);
                    }
                } else if (marks.charAt(0) == fenceChar) {
                    inFence = false;
                    if (!dropping) {
                        addLine(out, line);
                    }
                    dropping = false;
                } else if (!dropping) {
                    // 围栏里嵌了另一种围栏记号（如 ``` 块里写 ~~~），当正文透传
                    addLine(out, line);
                }
                continue;
            }
            if (inFence) {
                if (!dropping) {
                    addLine(out, line);
                }
                continue;
            }

            addLine(out, sanitizeLine(line, docPath, baseUrl));
        }

        return collapseBlanks(out);
    }

    /**
     * 处理一行正文（围栏外）
     *
     * 顺序有意义：容器 → v-pre → 图片 → 链接。
     * 图片必须排在链接前，否则 {@code ![alt](url)} 会被链接规则先咬掉 {@code [alt](url)} 那半截，
     * 留下一个孤零零的 {@code !}。
     */
    private static String sanitizeLine(String line, String docPath, String baseUrl) {
        // 容器：保留标题文字（它是好的检索锚，如「入参里的变量引用语法」），删掉 ::: 围栏本身
        Matcher container = CONTAINER.matcher(line);
        if (container.matches()) {
            line = container.group(2) == null ? "" : container.group(2).strip();
            if (line.isEmpty()) {
                return "";
            }
        }
        // $1 是 replaceAll 的分组引用语法（不是字面量）：把 <code v-pre>{{$.x}}</code> 还原成 {{$.x}}
        line = V_PRE.matcher(line).replaceAll("$1");
        line = IMAGE.matcher(line).replaceAll(mr -> {
            String alt = mr.group(1).strip();
            // alt 为空时整处删掉：留一个「（图：）」只是往 chunk 里塞噪声
            return alt.isEmpty() ? "" : "（图：" + Matcher.quoteReplacement(alt) + "）";
        });
        line = LINK.matcher(line).replaceAll(mr ->
                Matcher.quoteReplacement(rewriteLink(mr.group(1), mr.group(2), docPath, baseUrl)));
        return line;
    }

    /**
     * 改写一个链接目标
     *
     * <p>🔴 <b>同页锚点（{@code #常见问题}）降级成纯文本</b>，不猜 slug。
     * VitePress 的中文 slug 规则反直觉到手册站专门为它写了一个门禁
     * （{@code xpy-blog/tools/check-anchors.cjs}，其 README 原话「中文 slug 规则反直觉，手写必错」）。
     * 在 Java 侧再实现一份必然与它对不上，产出一批「看着像链接、点开跳错位置」的引用 ——
     * 那比不给链接更坏。
     */
    private static String rewriteLink(String text, String target, String docPath, String baseUrl) {
        if (target == null || target.isEmpty()) {
            return text;
        }
        String lower = target.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("mailto:") || lower.startsWith("tel:")) {
            return "[" + text + "](" + target + ")"; // 已是绝对地址，原样保留
        }
        if (target.startsWith("#")) {
            return text;
        }
        String path = target.startsWith("/")
                ? target.substring(1)
                : resolveRelative(parentOf(docPath), target);
        return "[" + text + "](" + toPublicUrl(path, baseUrl) + ")";
    }

    /**
     * 相对路径 → 手册站公开 URL
     *
     * <p>口径是 VitePress 的 {@code cleanUrls: true}（见 {@code xpy-blog/docs/.vitepress/config.mts}）的事实，
     * 不是猜的：产物是 {@code guide/04-ai/assistant.html}，而服务器把 {@code /guide/04-ai/assistant}
     * 映射过去，{@code index.md} 映射到所在目录的 {@code /}。
     *
     * <table border="1">
     *   <tr><th>相对路径</th><th>公开 URL</th></tr>
     *   <tr><td>{@code index.md}</td><td>{@code {base}/}</td></tr>
     *   <tr><td>{@code guide/index.md}</td><td>{@code {base}/guide/}</td></tr>
     *   <tr><td>{@code guide/01-system/user.md}</td><td>{@code {base}/guide/01-system/user}</td></tr>
     *   <tr><td>{@code changelog.md}</td><td>{@code {base}/changelog}</td></tr>
     * </table>
     *
     * <p>🔴 <b>公开 URL 没有 {@code .md} 后缀</b>，而上游
     * {@code AiKnowledgeSegmentServiceImpl#isMarkdownQaFormat} 判 {@code MARKDOWN_QA} 的第一条就是
     * 「url 以 {@code .md} 结尾」。所以存了这个 url 之后，切分策略必然落到 {@code SEMANTIC}。
     * 这不是漏改：实测 108 篇语料里只有 4 篇（{@code demo-grid/demo-ie/demo-verify/demo-widget}）
     * 能满足它另一条「二级标题占行数 &gt; 10%」的要求 —— 手册每页固定 7 个小节、页均 130 行，
     * 占比只有 5% 上下，<b>结构性达不到</b>。而 {@code detectDocumentStrategy} 的两条回落分支
     * 返回的都是 {@code SEMANTIC}，故 {@code .md} 后缀在这里对结果没有影响。
     * 真要换切分策略，得改上游让它接受显式策略入参，不是靠改 url 蒙混。
     *
     * @param docPath 相对路径，可带 {@code .md} / {@code .html} 后缀与 {@code #锚点}
     * @param baseUrl 公开地址前缀，末尾的 {@code /} 会被吃掉
     */
    public static String toPublicUrl(String docPath, String baseUrl) {
        String base = trimSlash(baseUrl);
        if (docPath == null) {
            return base + "/";
        }
        String path = docPath.replace('\\', '/').strip();
        int cut = indexOfAny(path, '#', '?');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        path = path.replaceAll("\\.(md|markdown|html?)$", "");
        path = trimSlash(path);
        if (path.isEmpty() || path.equals("index")) {
            return base + "/";
        }
        if (path.endsWith("/index")) {
            return base + "/" + path.substring(0, path.length() - "index".length());
        }
        return base + "/" + path;
    }

    /**
     * 取标题：优先正文首个一级标题，回落到文件名
     *
     * <p>{@code ★} 后缀<b>保留</b> —— 它是「本页主体为自研功能」的标记，
     * 对回答「这是不是上游自带的」这类问题有用。
     *
     * <p>🔴 这里与 {@code AiRagApiImpl#extractTitle} 是同一条规则的两份实现。
     * 之所以没抽成一份：{@code ai} 模块不能反向依赖 {@code agent}（依赖是单向的），
     * 而检索侧回填标题必须在 {@code ai} 里做。改规则时两处一起改。
     *
     * @param content  正文（清洗前或清洗后都行，一级标题两种情况下都在）
     * @param docPath  相对路径，作为兜底标题的来源
     */
    public static String extractTitle(String content, String docPath) {
        if (content != null) {
            for (String line : content.split("\n", 40)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("# ")) {
                    return trimmed.substring(2).strip();
                }
            }
        }
        if (docPath == null || docPath.isEmpty()) {
            return null;
        }
        String name = docPath.substring(docPath.lastIndexOf('/') + 1).replaceAll("\\.(md|markdown)$", "");
        return name.replaceFirst("^\\d+[-_]", "");
    }

    /**
     * 按 {@code ..} / {@code .} 归一化相对路径
     *
     * @param dir    基准目录（相对语料根，可为空串表示根）
     * @param target 链接目标
     */
    static String resolveRelative(String dir, String target) {
        List<String> segments = new ArrayList<>();
        for (String s : trimSlash(dir).split("/")) {
            if (!s.isEmpty()) {
                segments.add(s);
            }
        }
        for (String s : target.split("/")) {
            if (s.isEmpty() || s.equals(".")) {
                continue;
            }
            if (s.equals("..")) {
                if (!segments.isEmpty()) {
                    segments.remove(segments.size() - 1);
                }
                continue;
            }
            segments.add(s);
        }
        return String.join("/", segments);
    }

    /** 取路径的父目录；{@code guide/04-ai/x.md} → {@code guide/04-ai}，{@code index.md} → {@code ""} */
    static String parentOf(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/');
        int idx = p.lastIndexOf('/');
        return idx < 0 ? "" : p.substring(0, idx);
    }

    private static String trimSlash(String s) {
        if (s == null) {
            return "";
        }
        String r = s.strip();
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        return r;
    }

    private static int indexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        return ib < 0 ? ia : Math.min(ia, ib);
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /** 行尾空白去掉，避免「看着是空行、其实有空格」绕过去 */
    private static void addLine(List<String> out, String line) {
        out.add(line.stripTrailing());
    }

    /**
     * 连续空行压成一行，并去掉首尾空行
     *
     * 切分器按段落走，空行堆会白占 chunk 预算；首尾空行还会让第一段以空白开头，
     * 影响 embedding 的句首权重。
     */
    private static String collapseBlanks(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        boolean pendingBlank = false;
        for (String line : lines) {
            if (line.isEmpty()) {
                pendingBlank = sb.length() > 0;
                continue;
            }
            if (pendingBlank) {
                sb.append('\n');
                pendingBlank = false;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

}
