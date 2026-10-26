package cn.enlev.ainb.module.system.framework.permission.core.scan;

import cn.enlev.ainb.module.system.framework.permission.core.scan.PermissionStringResolver.Resolution;
import cn.enlev.ainb.module.system.framework.permission.core.scan.PermissionStringResolver.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：本模块源码里的每个权限串都必须被 {@link PermissionStringResolver} 显式归档
 *
 * <h3>为什么要这条守卫</h3>
 * 解析层把权限串拆成「资源身份 + 动作」，靠的是一张<b>动词映射表</b>。表里没有的末段
 * 会落进档 C（{@link Tier#BUSINESS_VERB}），处置是「整串保留为子资源」——
 * 这是一条<b>静默的回落</b>：不报错、不告警，只是那个权限点从此不参与塌缩。
 * 阶段 0 已经吃过一次这个亏：第一版映射表漏了 {@code reference}，39 行被误判进档 C，
 * 而档 C 从 35 行虚高到 153 行（23.3%），差点让「69.5% 可直接塌缩」这个结论被推翻。
 *
 * <p>所以档 C 的成员必须是<b>显式清单</b>。清单变了就说明有人新写了一个业务动词，
 * 那时需要的是一个<b>有意识的决定</b>（它到底该并进某个动作，还是真的是独立权限点），
 * 而不是一次静默回落。
 *
 * <h3>⚠️ 作用域只有 enlev-module-system</h3>
 * surefire 的工作目录是模块根，所以这里只能扫到本模块的 {@code src/main/java}
 * （与 agent 模块的 {@code AgentArchitectureTest}、{@code CommentLineRefBanTest} 同一口径）。
 * 参与构建的另外四个模块（infra / bpm / ai / agent）里的权限串<b>不在本守卫覆盖范围内</b>。
 * 阶段 0 实测全仓库并集是 394 个串，本模块占 107 个。别把这条守卫当成全量覆盖来引用。
 *
 * <h3>🔴 为什么必须剥注释，而 {@link #isCommentLine} 的行前缀粗判在这里够用</h3>
 * {@code PermissionAnnotationScanner} 的 Javadoc 里写着
 * {@code @ss.hasPermission('xxx')} 与 {@code @ss.hasPermission('...')} 两个<b>示例串</b>。
 * 不剥注释就会把它们当成真权限串收进来（阶段 0 的第一版盘点因此多报了 2 个「脏串」，
 * 而注解面的真实脏串数是 <b>0</b>）。
 *
 * <p>{@code CommentLineRefBanTest} 用的是等长字符状态机，因为它要<b>只扫注释正文</b>、
 * 且报告里的行号必须可信。本守卫要的恰好相反（<b>只扫代码</b>），而且只关心串本身、不关心行号，
 * 所以沿用 {@code AgentArchitectureTest#isCommentLine} 的行前缀粗判即可。
 * 粗判的已知盲区是「块注释里不以 {@code *} 开头的行」——本模块没有这种写法，
 * 且真漏进来也会撞下面那条档 C 清单，不会静默通过。{@link #scannerSkipsJavadocExamples}
 * 把这个取舍钉成了可执行的断言。
 *
 * <h3>🔴 域自检 + 变异探针</h3>
 * 文本扫描的共通失效方式是「把要守的东西删掉，守卫就更绿」：目录挪走了 {@code Files.walk}
 * 什么都不返回，于是「零违规」永远成立。所以除了正向断言，还配了文件数/串数下限
 * 与两条自检（已知形状必须扫得出、注释里的示例必须扫不出），让静默失效变成撞红。
 *
 * @author 走进新时代
 */
@DisplayName("🔴 权限串必须被解析层显式归档 —— 档 C 是静默回落，不许悄悄长大")
class PermissionStringResolverGuardTest {

    /** 本模块的 main 源码根目录；surefire 工作目录就是模块根 */
    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    /**
     * {@code @ss.hasPermission('x')} 与 {@code @ss.hasAnyPermissions('a', 'b')} 两种形态
     */
    private static final Pattern ANNOTATION = Pattern.compile("@ss\\.has(?:Any)?Permissions?\\(([^)]*)\\)");

    /** 注解参数里的单引号串。双引号形态不存在于本仓库，见 {@link #scannerSkipsJavadocExamples} */
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

    /**
     * 档 C（业务专有动词）的显式清单 —— 本模块实测 9 个
     *
     * <p>🔴 改这张表之前先读阶段 0 勘察报告第三节。其中 {@code page}、{@code detail}、
     * {@code manager-query}、{@code get-key-list} 语义上其实是 {@code query} 的变体，
     * <b>能</b>并进 VIEW，但刻意不并：并错一个就是一次静默放权，得逐个确认调用方语义。
     *
     * <p>⚠️ {@code run} 来自 {@code infra:portal:run}，它写在 system 模块的
     * {@code SystemPortalDataController} 上 —— 即<b>权限串的模块前缀与所在模块可以不一致</b>，
     * 解析层绝不能拿调用方所在模块去推断前缀。{@link #crossModuleStringIsPresent} 钉住这一点。
     */
    private static final Set<String> EXPECTED_BUSINESS_VERBS = Set.of(
            "assign-role-data-scope",
            "assign-role-menu",
            "assign-user-role",
            "page",
            "run",
            "send-mail",
            "send-notify",
            "send-sms",
            "update-password");

    private final PermissionStringResolver resolver = new PermissionStringResolver();

    @Test
    @DisplayName("全域 src/main：每个权限串都能解析出资源身份与动作")
    void everyPermissionStringResolves() throws IOException {
        Set<String> permissions = scanPermissions();
        // 🔴 域自检：目录挪走/路径写错时 Files.walk 返回空，守卫会变成永远通过的空测试。
        //    下限取自阶段 0 实测（555 个 .java、107 个 distinct 串）；别把这两条下限删掉了事
        assertTrue(permissions.size() >= 100,
                "只扫到 " + permissions.size() + " 个权限串（今天实测 107），少于 100 "
                        + "⟹ 扫描口径大概率坏了，工作目录应是模块根");

        List<String> unresolvable = new ArrayList<>();
        for (String permission : permissions) {
            Resolution r = resolver.resolve(permission);
            if (r.getTier() == Tier.BLANK || r.getResourceKey() == null || r.getAction() == null) {
                unresolvable.add(permission);
            }
        }
        assertTrue(unresolvable.isEmpty(),
                """
                下列权限串解析不出资源身份或动作：
                  %s

                空权限串没法参与「资源 × 动作」判定，而它的失败形态是【静默判成无权】——
                正是本仓库反复记录的那个「配了、看着生效了、就是不生效」。
                如果这是新写的注解，请补上完整的 module:resource:action 三段；
                如果它确实是资源级权限点（不带动词），那它应当是两段式，会落进档 D。
                """.formatted(String.join("\n  ", unresolvable)));
    }

    @Test
    @DisplayName("🔴 档 C 动词必须恰好等于显式清单 —— 多一个少一个都要撞红")
    void businessVerbsMatchExplicitList() throws IOException {
        Set<String> actual = new TreeSet<>();
        for (String permission : scanPermissions()) {
            if (resolver.resolve(permission).getTier() == Tier.BUSINESS_VERB) {
                actual.add(permission.substring(permission.lastIndexOf(':') + 1));
            }
        }
        assertEquals(new TreeSet<>(EXPECTED_BUSINESS_VERBS), actual,
                """
                档 C（业务专有动词）的成员变了。档 C 的处置是「整串保留为子资源、不参与塌缩」，
                这是一条【静默的回落】：不报错、不告警，只是那个权限点从此留在旧形态里。

                · 清单里【多出来】的动词 ⟹ 有人新写了一个末段。需要一个有意识的决定：
                  它该并进某个 GrantActionEnum 成员（那就去改 PermissionStringResolver 的映射表，
                  并在 PermissionStringResolverTest#testVerbs_映射表内容与阶段0实测一致 里同步），
                  还是真的是独立权限点（那就把它加进本类的 EXPECTED_BUSINESS_VERBS）。
                  ⚠️ 别忘了阶段 0 的教训：reference 曾被当成业务专有动词，
                  而它其实是 GrantActionEnum 的正式成员，漏掉它让 39 行白白留在旧形态。
                · 清单里【少掉】的动词 ⟹ 那个注解被改名或删除了。确认是有意为之，
                  再从 EXPECTED_BUSINESS_VERBS 里去掉，别为了让测试变绿直接清空清单。
                """);
    }

    @Test
    @DisplayName("跨模块权限串确实存在 —— 解析层不许用所在模块推断前缀")
    void crossModuleStringIsPresent() throws IOException {
        Set<String> permissions = scanPermissions();
        assertTrue(permissions.contains("infra:portal:run"),
                "SystemPortalDataController 上的 infra:portal:run 没扫到 ⟹ 扫描口径坏了，"
                        + "或那处注解被改了。它的存在证明【权限串的模块前缀与所在模块可以不一致】");
        // 前缀是 infra，但档位由末段动词决定，与所在模块无关
        Resolution r = resolver.resolve("infra:portal:run");
        assertEquals(Tier.BUSINESS_VERB, r.getTier());
        assertEquals("infra:portal:run", r.getResourceKey());
    }

    // ==================== 变异探针：证明扫描器既不太窄也不太宽 ====================

    @Test
    @DisplayName("🔴 正向自检：单串 / hasAnyPermissions 多串 / 换行注解都必须扫得出")
    void scannerFindsKnownShapes() {
        assertEquals(Set.of("system:user:query"), permissionsIn(
                        """
                        @PreAuthorize("@ss.hasPermission('system:user:query')")
                        public CommonResult<Void> x() { return null; }
                        """),
                "最基本的形态扫不出来 ⟹ 守卫是假绿");

        assertEquals(Set.of("system:user:query", "system:user:create"), permissionsIn(
                        """
                        @PreAuthorize("@ss.hasAnyPermissions('system:user:query', 'system:user:create')")
                        public void y() {}
                        """),
                "hasAnyPermissions 里的多个串没被全部扫出");

        assertEquals(Set.of("system:role:delete"), permissionsIn(
                        """
                        // 注释掉的不算：// @PreAuthorize("@ss.hasPermission('system:role:query')")
                        @PreAuthorize("@ss.hasPermission('system:role:delete')")
                        public void z() {}
                        """),
                "被注释掉的注解不该收进来，真注解不该漏");
    }

    @Test
    @DisplayName("🔴 反向自检：Javadoc 里的示例串必须被剥掉")
    void scannerSkipsJavadocExamples() {
        // 这正是 PermissionAnnotationScanner 的 Javadoc 形状。阶段 0 的第一版盘点没剥注释，
        // 把 'xxx' 与 '...' 当成了两个「脏串」，而注解面的真实脏串数是 0
        Set<String> found = permissionsIn(
                """
                /**
                 * 从 Spring 容器里扫描所有 @PreAuthorize("@ss.hasPermission('...')") 注解，
                 * 匹配 @ss.hasPermission('xxx') 或 @ss.hasPermission("xxx") 里的权限串
                 */
                public class Foo {
                    @PreAuthorize("@ss.hasPermission('system:real:query')")
                    public void bar() {}
                }
                """);
        assertEquals(Set.of("system:real:query"), found,
                "Javadoc 里的示例串被当成真权限串收进来了 ⟹ 会凭空多出脏数据，"
                        + "并让档 C 清单与档位统计全部失真");
        assertFalse(found.contains("xxx"), "示例串 'xxx' 不该被收进来");
        assertFalse(found.contains("..."), "示例串 '...' 不该被收进来");
    }

    @Test
    @DisplayName("🔴 域自检：源码根必须真的存在且扫得到文件")
    void sourceRootIsScannable() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_ROOT),
                "找不到 " + SOURCE_ROOT.toAbsolutePath() + " ⟹ surefire 工作目录不是模块根，"
                        + "此时上面几条断言全都会因为「扫到 0 个串」而失真");
        int files = javaFilesUnder(SOURCE_ROOT).size();
        assertTrue(files >= 500,
                "只扫到 " + files + " 个 .java（今天实测 555），少于 500 ⟹ 扫描口径大概率坏了");
    }

    // ==================== 扫描内核 ====================

    /** 扫本模块 src/main/java 里全部 @PreAuthorize 权限串（已剥注释） */
    private Set<String> scanPermissions() throws IOException {
        Set<String> out = new TreeSet<>();
        for (Path file : javaFilesUnder(SOURCE_ROOT)) {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            out.addAll(permissionsIn(raw));
        }
        return out;
    }

    /** 对一段源码跑「逐行剥注释 → 匹配注解 → 抽单引号串」（供自检用） */
    private static Set<String> permissionsIn(String snippet) {
        Set<String> out = new TreeSet<>();
        for (String line : snippet.split("\n", -1)) {
            if (isCommentLine(line)) {
                continue;
            }
            Matcher m = ANNOTATION.matcher(line);
            while (m.find()) {
                Matcher q = QUOTED.matcher(m.group(1));
                while (q.find()) {
                    String permission = q.group(1).trim();
                    if (!permission.isEmpty()) {
                        out.add(permission);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 整行以 {@code //}、{@code *}、{@code /*} 开头就当作注释行跳过
     *
     * 与 {@code AgentArchitectureTest#isCommentLine} 同一口径。已知盲区见类注释
     */
    private static boolean isCommentLine(String line) {
        String t = line.trim();
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*");
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
