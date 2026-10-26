package cn.enlev.ainb.module.agent.framework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：注释里不得写死「标识符:行号」这种源码位置引用（E4 的强制点）
 *
 * <h3>为什么要改</h3>
 * 注释里钉的行号会随代码增删而漂移，漂了之后比没写还坏 —— 它把读者精准地导向一个
 * <b>已经不是当初那处</b>的位置，还带着「有出处」的可信度。E4 实测这种引用在后端
 * src/main 里有 100 处，且漂移<b>已经真实发生</b>：{@code AgentAssistantServiceImpl:195}
 * 早已漂到约 900 行外的 {@code #runToolLoop}、{@code WorkflowDslValidator:365-366} 漂到
 * 1689 行外的 {@code #validateReferenceReachability}，共 6 处原行号指向了<b>完全错误的成员</b>。
 * E4 把这 100 处换成了不随增删漂移的锚点（类#成员 / 类注释「节名」那一节），
 * 本类就是把「注释里没有裸行号引用」变成<b>持续成立</b>的强制点，挡住下一个人再写一个。
 *
 * <h3>怎么改（正确写法）</h3>
 * 指向某成员 → 写「类#成员」（{@code AiChatMessageServiceImpl#buildPrompt}）；
 * 指向类级 javadoc 的一段话 → 写「类注释『节名』那一节」；指向前端跨仓目标 →
 * 写「File.vue 的 xxx computed」。锚点机械可核对、且不随行数漂移。
 *
 * <h3>🔴 为什么必须用真正的注释状态机，而不是隔壁的行前缀粗判</h3>
 * {@code AgentArchitectureTest#isCommentLine} 用的是「整行以 {@code //}、{@code *}、{@code /*}
 * 开头就跳过」——那是为了<b>忽略</b>注释、只扫代码。本类要的恰恰相反：<b>只扫注释正文</b>。
 * 行前缀粗判会两头错：行尾注释（{@code int x = 1; // Foo:123}）不以注释符开头 → 漏掉；
 * 而字符串里的 {@code "Foo:123"} 又会被误当违规。故这里跑一遍<b>等长</b>字符状态机
 * （{@link #commentView}），产出「只含注释正文、代码/字符串/字符/文本块全抹平、换行原样保留」的视图，
 * 于是行号在两个视图里完全一致，报告里的行号可信（由 {@link #commentViewPreservesLineCount} 钉住）。
 *
 * <h3>⚠️ 刻意不覆盖 src/test（照 {@code NodeIoContractRuntimeReadScanTest}「不含 src/test」同一取舍）</h3>
 * src/test 的注释里同样有会漂的行号引用（实测约 30 处，如 {@code WorkflowDslValidator:693}），
 * 但那里还<b>合法地钉着</b>与违规同形状的领域字面量 —— {@code EXEC:1}/{@code EXEC:12}（任务键前缀粘连用例）、
 * {@code theId:6}（模拟事件 payload）、{@code column:3}（截窗口径）。要在 src/test 上做「零白名单」的禁令，
 * 就得先把这两类分开，是独立的一刀；混进本守卫只会逼出一张白名单，而<b>白名单正是新违规藏身之处</b>。
 *
 * <h3>🔴 域自检 + 变异探针（方案 §4.2）</h3>
 * 文本扫描的共通失效方式是「把要守的东西删掉，禁令就更绿」（判定没了违规自然没了；目录挪走了
 * {@code Files.walk} 什么都不返回）。所以除了「零违规」这条正向断言，还配了文件数下限、
 * {@link #scannerFlagsKnownShapes}（已知形状必须扫得出）与 {@link #scannerIgnoresNonRefs}
 * （字符串/文本块/URL 端口/纯数字比值不得误报），让「静默失效」变成「撞红」。
 *
 * @author 走进新时代
 */
@DisplayName("🔴 注释里禁止写死「标识符:行号」—— 漂了比没写还坏（E4）")
class CommentLineRefBanTest {

    /** 本模块的 main 源码根目录；surefire 工作目录就是模块根，与 AgentArchitectureTest 同一口径 */
    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    /**
     * 被禁的形状：一个标识符样的 token（可含 {@code .} / {@code #} 成员分隔）紧跟 {@code :} 加数字。
     * 首字符必须是字母或下划线，故 {@code 16:9}、{@code 12:30} 这类纯数字比值天然不匹配。
     */
    private static final Pattern LINE_REF = Pattern.compile("[A-Za-z_][A-Za-z0-9_.#]*:\\d+");

    /**
     * URL 的 authority（{@code scheme://host:port}）不是行号引用；扫之前先从注释视图里抹掉，
     * 免得 {@code http://localhost:8080} 里的 {@code localhost:8080} 变成假警报
     * （假警报的代价是真警报没人看）。今天 src/main 的注释里 0 处 URL 带端口，这条纯属未来的保险。
     */
    private static final Pattern URL = Pattern.compile("[A-Za-z][A-Za-z0-9+.\\-]*://\\S*");

    @Test
    @DisplayName("全域 src/main：注释里不得出现「标识符:行号」")
    void noLineRefInComments() throws IOException {
        List<Path> files = javaFilesUnder(SOURCE_ROOT);
        // 🔴 域自检：目录挪走/路径写错时 Files.walk 返回空，禁令会变成永远通过的空测试。
        //    下限 250 取自今天实测的 281；别把这条下限删掉了事（删了就等于关掉整条守卫）。
        assertTrue(files.size() >= 250,
                "只扫到 " + files.size() + " 个 .java（今天实测 281），少于 250 ⟹ 扫描口径大概率坏了，"
                        + "工作目录应是模块根");

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String comments = stripUrls(commentView(raw));
            String[] lines = comments.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = LINE_REF.matcher(lines[i]);
                while (m.find()) {
                    violations.add(SOURCE_ROOT.relativize(file).toString().replace('\\', '/')
                            + ":" + (i + 1) + "  「" + m.group() + "」  " + lines[i].trim());
                }
            }
        }
        assertTrue(violations.isEmpty(),
                """
                注释里不得写死「标识符:行号」这种源码位置引用 —— 行号会随代码增删漂移，
                漂了之后比没写还坏：它把读者精准导向一个已经不是当初那处的位置，还带着「有出处」的可信度。
                （E4 实测：AgentAssistantServiceImpl:195 早漂到约 900 行外的 #runToolLoop，
                WorkflowDslValidator:365-366 漂到 1689 行外的 #validateReferenceReachability。）

                正确写法（换成不漂移的锚点）：
                  · 指向某成员      → 类#成员，如 AiChatMessageServiceImpl#buildPrompt
                  · 指向类级 javadoc → 类注释「节名」那一节
                  · 指向前端跨仓目标 → File.vue 的 xxx computed
                  · 描述枚举/编码值  → 用等号，如 type=11（冒号会被本守卫当成行号引用）

                若命中的其实是 URL 端口（host:port）或纯数字比值，本守卫已放行；
                仍被拦下说明形状确实是「标识符:数字」，请按上面改写。

                违规位置：
                """ + String.join("\n", violations));
    }

    // ==================== 变异探针：证明扫描器既不太窄也不太宽 ====================

    @Test
    @DisplayName("🔴 正向自检：行注释 / 块注释 / javadoc / 行尾注释里的「标识符:行号」都必须扫得出")
    void scannerFlagsKnownShapes() {
        assertEquals(List.of("Foo:123"), refsIn("// Foo:123"),
                "行注释里的裸行号引用扫不出来 ⟹ 守卫是假绿");
        assertEquals(List.of("Bar:45"), refsIn("/* 见 Bar:45-67 */"),
                "块注释里的引用扫不出来（区间只认头一段是对的，仍能定位）");
        assertEquals(List.of("Baz.java:12"), refsIn("/** {@code Baz.java:12} 那一处 */"),
                "javadoc 里的引用扫不出来");
        assertEquals(List.of("Qux:10"), refsIn("int x = 1; // Qux:10"),
                "行尾注释里的引用扫不出来 ⟹ 这正是隔壁行前缀粗判会漏的形状");
        assertEquals(List.of("a:1", "B:2"), refsIn("// a:1 与 B:2"),
                "同一行里多处引用没被全部扫出");
    }

    @Test
    @DisplayName("🔴 反向自检：字符串 / 文本块 / URL 端口 / 纯数字比值 / 代码位都不得误报")
    void scannerIgnoresNonRefs() {
        assertEquals(List.of(), refsIn("class C { String s = \"Foo:123\"; }"),
                "普通字符串正文里的 Foo:123 被当成注释引用 ⟹ 太宽（会误伤运行期字面量）");
        assertEquals(List.of(), refsIn("class C { String s = \"\"\"\n        Foo:123\n        \"\"\"; }"),
                "文本块里的内容没被整块识别成字符串 ⟹ 提示词里的示例会变成假违规");
        assertEquals(List.of(), refsIn("// 见 http://localhost:8080/admin 与 https://x.io:443/y"),
                "URL 的 host:port 被当成行号引用 ⟹ 假警报，代价是真警报没人看");
        assertEquals(List.of(), refsIn("// 比例 16:9，时间 12:30，坐标 3:4:5"),
                "纯数字比值被匹配 ⟹ 首字符必须是字母/下划线才守得住这条");
        assertEquals(List.of(), refsIn("int a = flag ? 1 : 2; map.put(\"k:9\", v);"),
                "代码位（三元 / 字符串键）被扫进来 ⟹ 本守卫只认注释视图");
        assertEquals(List.of(), refsIn("// 枚举值 type=11、candidateStrategy=37 用等号写就不触发"),
                "用等号描述的枚举值被误报 ⟹ 这正是 commit ② 把 type:11 改成 type=11 的原因");
    }

    @Test
    @DisplayName("🔴 自检：注释视图必须与原文件等长且逐行对齐（否则报告里的行号不可信）")
    void commentViewPreservesLineCount() {
        String raw = """
                package p;
                // 行注释 Foo:1
                class C {
                    /* 块注释
                       跨行 Bar:2 */
                    String s = "字符串里的 Baz:3 不算";
                    String t = \"\"\"
                            文本块 Qux:4 也不算
                            \"\"\";
                    int x = 1; // 行尾 Quux:5
                }
                """;
        String view = commentView(raw);
        assertEquals(countNewlines(raw), countNewlines(view),
                "注释视图与原文件换行数不等 ⟹ 报告里的行号会错位，等于给读者又一个假出处");
        assertEquals(raw.length(), view.length(), "视图必须等长（等长才能保证偏移/行号一致）");
        // 顺带确认：该扫出的都扫出了、不该扫出的都没扫出
        assertEquals(List.of("Foo:1", "Bar:2", "Quux:5"), refsIn(raw),
                "合成样例里的注释引用没被完整、且仅被注释位地扫出");
    }

    // ==================== 扫描内核 ====================

    /** 对一段源码跑「注释视图 → 抹 URL → 匹配」，返回命中的引用文本（供自检用） */
    private static List<String> refsIn(String snippet) {
        List<String> out = new ArrayList<>();
        Matcher m = LINE_REF.matcher(stripUrls(commentView(snippet)));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** 把注释视图里的 URL 整段抹成等长空格，避免 host:port 变成假警报 */
    private static String stripUrls(String view) {
        Matcher m = URL.matcher(view);
        StringBuilder sb = new StringBuilder(view.length());
        int last = 0;
        while (m.find()) {
            sb.append(view, last, m.start());
            sb.append(" ".repeat(m.end() - m.start()));
            last = m.end();
        }
        sb.append(view, last, view.length());
        return sb.toString();
    }

    /**
     * 产出「只含注释正文」的<b>等长</b>视图：代码、字符串、字符字面量、文本块全抹成空格，
     * 行/块注释正文原样保留，所有换行原样保留 ⟹ 偏移与行号在两个视图里完全一致。
     *
     * <p>状态：0=代码 1=行注释 2=块注释 3=字符串 5=字符字面量（4 留给文本块，它在状态 0 里整块跳过）。
     * 与 {@code NodeIoContractRuntimeReadScanTest#parse} 同一套走法，只是那边保留代码、这边保留注释。
     */
    private static String commentView(String raw) {
        int n = raw.length();
        char[] v = new char[n];
        Arrays.fill(v, ' ');
        int st = 0;
        int i = 0;
        while (i < n) {
            char c = raw.charAt(i);
            char nx = i + 1 < n ? raw.charAt(i + 1) : '\0';
            char nx2 = i + 2 < n ? raw.charAt(i + 2) : '\0';
            switch (st) {
                case 0 -> {
                    if (c == '/' && nx == '/') {
                        st = 1;
                        i += 2;
                    } else if (c == '/' && nx == '*') {
                        st = 2;
                        i += 2;
                    } else if (c == '"' && nx == '"' && nx2 == '"') {
                        int[] sp = textBlockSpan(raw, i);
                        for (int j = sp[0]; j < sp[1]; j++) {
                            if (raw.charAt(j) == '\n') {
                                v[j] = '\n';
                            }
                        }
                        i = sp[1];
                    } else if (c == '"') {
                        st = 3;
                        i++;
                    } else if (c == '\'') {
                        st = 5;
                        i++;
                    } else {
                        if (c == '\n') {
                            v[i] = '\n';
                        }
                        i++;
                    }
                }
                case 1 -> {
                    if (c == '\n') {
                        v[i] = '\n';
                        st = 0;
                    } else {
                        v[i] = c;
                    }
                    i++;
                }
                case 2 -> {
                    if (c == '*' && nx == '/') {
                        st = 0;
                        i += 2;
                    } else {
                        v[i] = c == '\n' ? '\n' : c;
                        i++;
                    }
                }
                case 3 -> {
                    if (c == '\\') {
                        if (i + 1 < n && raw.charAt(i + 1) == '\n') {
                            v[i + 1] = '\n';
                        }
                        i += 2;
                    } else {
                        if (c == '"') {
                            st = 0;
                        }
                        if (c == '\n') {
                            v[i] = '\n';
                        }
                        i++;
                    }
                }
                default -> {              // 5 = 字符字面量
                    if (c == '\\') {
                        i += 2;
                    } else {
                        if (c == '\'') {
                            st = 0;
                        }
                        i++;
                    }
                }
            }
        }
        return new String(v);
    }

    /** 文本块 {@code """ … """} 的 [start,end)；未闭合就吃到文件尾（宁可整块当字符串，也不要漏出假引用） */
    private static int[] textBlockSpan(String raw, int from) {
        int j = from + 3;
        while (j < raw.length()) {
            if (raw.charAt(j) == '\\' && j + 1 < raw.length()) {
                j += 2;
                continue;
            }
            if (raw.charAt(j) == '"' && j + 2 < raw.length()
                    && raw.charAt(j + 1) == '"' && raw.charAt(j + 2) == '"') {
                return new int[]{from, j + 3};
            }
            j++;
        }
        return new int[]{from, raw.length()};
    }

    private static int countNewlines(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                c++;
            }
        }
        return c;
    }

    private static List<Path> javaFilesUnder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

}
