package cn.enlev.ainb.module.infra.framework.sourcehygiene;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 禁止把厂商 API 密钥写进源码（全仓扫描）
 *
 * <h3>立案时量到的事实</h3>
 * 2026-10-07 扫出 {@code enlev-module-ai/src/test} 下有 <b>10 处</b>真形态的厂商密钥字面量
 * （Anthropic / OpenAI / SiliconFlow×2 / 通义×3 / Midjourney / StabilityAi / 博查），
 * 分布在 9 个文件里。它们全都挂在 {@code @Disabled} 的集成测试上，<b>一次也没被执行过</b> ——
 * 换句话说，这些密钥换来的唯一效果就是「随 git 永久留在历史里」。
 * {@code git log --follow} 显示这 9 个文件全部来自上游提交，本仓只是继承了它们。
 * 现已一律改成读环境变量（{@code VendorApiKeys.of("XXX_API_KEY")}，取不到就 assumeTrue 跳过）。
 *
 * <h3>本守卫防的是什么</h3>
 * 防的是<b>下一次 upstream 合并把它们原样带回来</b>。这 9 个文件是上游持续在改的
 * （最近一次是 {@code fix：【ai】单测在 spring-ai 1.1.0 版本下的报错}），
 * 合并冲突一旦被人图省事地「取 theirs」，密钥就静默回到 HEAD，而所有测试照旧全绿 ——
 * 因为它们本来就是 {@code @Disabled} 的。没有这条守卫，本次修复的半衰期就是一次合并。
 *
 * <h3>⚠️ 改掉源码 ≠ 密钥不再泄露</h3>
 * git 历史里那 10 个值仍然存在，任何一次 {@code git log -p} 都捞得出来。
 * <b>唯一的补救是去各家控制台吊销并换发</b>。而且它们是上游作者的演示密钥，
 * 本仓使用者多半没权限吊销 —— 所以别把「守卫绿了」读成「安全了」。
 *
 * <h3>⚠️ 域的边界（照 §0.8.4.1③「『真跑』也有域」写明，别让读者以为它管得更多）</h3>
 * <ol>
 *   <li><b>只认 {@code sk-} 前缀 + 32 位以上纯字母数字</b>这一个形态。别的厂商格式一律看不见：
 *       AWS 的 {@code AKIA…}、GitHub 的 {@code ghp_…}、Slack 的 {@code xoxb-…}、
 *       以及带连字符或短于 32 位的 key。选这个口径是因为<b>量到的 10 把全是这个形态</b> ——
 *       真实的威胁向量就是它，放宽只会把可读的假夹具也扫进来（见下面那条 allowlist）。</li>
 *   <li><b>只扫后端 Java 源码树</b>（按 pom.xml 递归认模块，含 {@code enlev-framework} 的嵌套子模块；
 *       按<b>磁盘</b>认而非按构建认，所以被根 pom 注释掉、不参与编译的模块也在域内 ——
 *       密钥写在源码里就已经进了 git，编不编译都一样泄露。
 *       当初的具体例子是 im，{@code 12347efcd1}（2026-10-22 清除未用上的模块）已把它连目录删掉，
 *       但这条口径保留）。前端 {@code ainb-ui-admin-vue3}、
 *       sql 脚本、yaml 配置<b>都不在域内</b> —— 前端已单独 grep 过，当前干净，但没有强制点。</li>
 *   <li><b>扫的是文本行，分不清注释与代码</b>：javadoc 里举例写一个 32 位以上的 {@code "sk-…"}
 *       也会被判违规。这是<b>刻意</b>的 —— 举例用的密钥同样会被爬虫收走。</li>
 *   <li><b>不校验环境变量是否真的配了、也不校验密钥是否有效</b>。
 *       🔴 尤其不许拿扫出来的密钥去打厂商接口验活：那会消耗别人的额度，而且验活本身就是一次外泄。</li>
 * </ol>
 *
 * @author 走进新时代
 */
@DisplayName("禁止把厂商 API 密钥写进源码")
class HardcodedVendorKeyBanTest {

    /**
     * 真形态的密钥字面量：引号内 {@code sk-} 后接 ≥32 位纯字母数字
     *
     * <p>刻意<b>不</b>把连字符放进字符类：仓里那些一眼可读的假夹具
     * （{@code "sk-real-key-0123456789"}、{@code "sk-test-only"}、
     * {@code "sk-this-is-plaintext-not-encrypted"}）正是靠连字符自证是假的，
     * 放进来的话 allowlist 会立刻涨到 4 条，把真信号淹掉。
     */
    private static final Pattern VENDOR_KEY = Pattern.compile("\"sk-[A-Za-z0-9]{32,}\"");

    /**
     * 唯一一条放行：{@code AiApiKeyEncryptTypeHandlerTest} 里那把
     * {@code sk-abcdefghijklmnopqrstuvwxyz0123456789}
     *
     * <p>它是「字母表 + 数字」的可见合成串，用来测 TypeHandler 的加解密往返，
     * 但形态上恰好撞上本守卫的口径（36 位纯字母数字），只能按文件放行。
     *
     * <p>🔴 放行的是<b>整个文件</b>，所以这个文件里将来若真被塞进一把活密钥，本守卫看不见。
     * 这是已知豁口，不是疏忽：按值放行就得在守卫源码里写下那串假密钥，
     * 而守卫自己也在扫描域内，会自己判自己违规。
     */
    private static final Set<String> ALLOWED_FILES = Set.of(
            "enlev-module-ai/src/test/java/cn/enlev/ainb/module/ai/framework/type/AiApiKeyEncryptTypeHandlerTest.java");

    /**
     * 扫描域下限
     *
     * <p>历史：2026-10-15 量到 36 个源码根、2926 个 .java。2026-10-22 的
     * {@code 12347efcd1 refactor（系统）：清除未用上的模块} 删掉 pay / mall / crm / erp /
     * member / iot / mp / im 等模块共 <b>300 个 .java</b>（2926 − 300 = 2626），
     * 之后三天里 nav-node、表单发布到 App 端等新增把它抬到 2656（2026-10-25 量到）。
     *
     * <p>🔴 下调下限<b>不是</b>把守卫放松到能绿。删模块是仓库真实变小，
     * 本条自检要抓的是「按 pom.xml 递归认模块 / 向上找根」的口径坏了 ⟹ 域悄悄缩水 ⟹ 上面那条禁令假绿。
     * 判据是<b>下限必须能由当前磁盘状态解释</b>：改这个数之前先用一条独立的 find 量一遍
     * （完整命令写在下面 {@code scanDomainIsNotVacuous} 那条断言的报错文案里 ——
     * 🔴 别把它抄进本 javadoc，{@code -not -path} 那个参数里的星号斜杠会提前把注释块关掉），
     * 两边对得上才说明是仓库真变小了，对不上就是扫描口径坏了。
     * {@code SOURCE_ROOT_FLOOR} 留在 20（2026-10-25 实测 34 个源码根）是刻意的宽松，
     * 它只需要能抓住「递归没认到 enlev-framework 下那批嵌套子模块」这种整片漏检。
     */
    private static final int SOURCE_ROOT_FLOOR = 20;
    private static final int JAVA_FILE_FLOOR = 2500;

    @Test
    @DisplayName("🔴 全仓 Java 源码里不许出现真形态的厂商密钥字面量")
    void noHardcodedVendorKeys() {
        List<String> hits = new ArrayList<>();
        for (Path file : javaSources()) {
            String relative = relativize(file);
            if (ALLOWED_FILES.contains(relative)) {
                continue;
            }
            List<String> lines = readLines(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = VENDOR_KEY.matcher(lines.get(i));
                while (m.find()) {
                    // 🔴 报错信息里只打前 12 位：把整把密钥再抄一遍到控制台/CI 日志里，
                    //    等于把泄露面从「源码」扩大到「构建日志」，那正是本守卫要收窄的东西
                    hits.add(relative + ":" + (i + 1) + " " + m.group().substring(0, 12) + "…("
                            + (m.group().length() - 2) + " 位)");
                }
            }
        }

        assertEquals(List.of(), hits,
                "源码里出现了真形态的厂商密钥字面量。改法：删掉字面量，"
                        + "改成 VendorApiKeys.of(\"XXX_API_KEY\")（在 enlev-module-ai 的 test 源码里，"
                        + "取不到环境变量就 assumeTrue 跳过），或换成本模块自己的等价读法。"
                        + "⚠️ 别只是把密钥挪到别处或截短几位：真密钥一旦进过 git，"
                        + "历史里就永久留着，唯一补救是去厂商控制台吊销换发。"
                        + "🔴 也别拿它去打厂商接口验活 —— 那会消耗别人的额度");
    }

    @Test
    @DisplayName("🔴 扫描域自检：模块数与文件数不许悄悄缩水")
    void scanDomainIsNotVacuous() {
        List<Path> roots = sourceRoots();
        Set<String> modules = new HashSet<>();
        for (Path root : roots) {
            modules.add(moduleOf(root));
        }
        assertTrue(roots.size() >= SOURCE_ROOT_FLOOR,
                "只认到 " + roots.size() + " 个源码根，少于 " + SOURCE_ROOT_FLOOR
                        + "（2026-10-25 量到 34 个）。多半是「按 pom.xml 递归认模块」的口径坏了 —— "
                        + "那样上面那条禁令会假绿，别把这条自检删掉了事");
        assertTrue(modules.contains("enlev-module-ai"),
                "扫描域里没有 enlev-module-ai —— 而那正是 10 把密钥的所在地，等于守卫整个扑空。实际认到：" + modules);
        // enlev-framework 自己只是个聚合 pom、没有 src，能证明「递归认到了嵌套子模块」的是它下面那 15 个叶子
        assertTrue(modules.stream().anyMatch(m -> m.startsWith("enlev-spring-boot-starter-")),
                "扫描域里没有任何 enlev-spring-boot-starter-* —— 说明只扫到了仓库根的直接子目录，"
                        + "enlev-framework 下那 15 个嵌套子模块整片漏检。实际认到：" + modules);

        int files = javaSources().size();
        assertTrue(files >= JAVA_FILE_FLOOR,
                "只扫到 " + files + " 个 .java，少于 " + JAVA_FILE_FLOOR
                        + "（2026-10-25 量到 2656）。先独立量一遍再下结论："
                        + "find . -name '*.java' -not -path '*/target/*' | wc -l —— "
                        + "与本条数字对得上就是仓库真变小了（同步下调下限并在注释记下成因），"
                        + "对不上就是扫描口径坏了，上面那条禁令会假绿");
    }

    @Test
    @DisplayName("🔴 探测器自检：真形态必须被扫出来，可读的假夹具必须不被扫出来")
    void detectorIsNotVacuous() {
        // 正向：这正是量到的 10 把的形态（这里是重新编的假串，不是真密钥）
        assertTrue(VENDOR_KEY.matcher(
                        "        .apiKey(\"sk-AAAAAAAAAABBBBBBBBBBCCCCCCCCCCDDEE\")").find(),
                "探测器连一条真形态的密钥都扫不出来，上面那条禁令是假绿");

        // 正向：那条 allowlist 必须是「吃劲的」—— 若字母表假串本来就扫不出来，
        // 放行整个文件就白豁了一个口子
        assertTrue(VENDOR_KEY.matcher(
                        "        String raw = \"sk-abcdefghijklmnopqrstuvwxyz0123456789\";").find(),
                "allowlist 放行的那个形态已经扫不出来了 —— 那条放行是死代码，"
                        + "请把它删掉，别留着一个白豁的口子");

        // 反向：仓里现存的那几个「一眼可读」的假夹具都不许被误判，否则守卫一上线就红、然后被人整条删掉。
        // 另有「字母表 + 数字」那串合成 key 形态上恰好撞上口径，走 ALLOWED_FILES 按文件放行，
        // 由上面那条「allowlist 必须吃劲」的正向断言看守
        for (String fake : List.of("\"sk-test-only\"",
                "\"sk-this-is-plaintext-not-encrypted\"",
                "\"sk-a-very-long-api-key-that-encrypts-to-more-than-76-base64-chars-0123456789\"",
                "\"sk-real-key-0123456789\"")) {
            assertTrue(!VENDOR_KEY.matcher("        foo(" + fake + ");").find(),
                    "可读的假夹具 " + fake + " 被误判成真密钥，探测器口径太宽");
        }

        // 阈值边界必须钉死：31 位不算、32 位就算。
        // 🔴 这一对不能省 —— 少了它，「{32,} 写成 {31,} 或 {33,}」这类改动不会有任何测试变红，
        //    而放宽一位就会把上面那条 allowlist 覆盖不到的合成串扫进来，收紧一位就可能放过真密钥。
        String alnum31 = "A".repeat(31);
        assertTrue(!VENDOR_KEY.matcher("        foo(\"sk-" + alnum31 + "\");").find(),
                "sk- 后 31 位字母数字被判成了密钥，阈值被放宽了（口径见 VENDOR_KEY 的注释）");
        assertTrue(VENDOR_KEY.matcher("        foo(\"sk-" + alnum31 + "A\");").find(),
                "sk- 后 32 位字母数字没被判成密钥，阈值被收紧了 —— 量到的 10 把真密钥最短的正是 32 位");

        // 反向：没引号的不算（那是环境变量名或文档里的提法，不是字面量）
        assertTrue(!VENDOR_KEY.matcher("        VendorApiKeys.of(\"OPENAI_API_KEY\")").find(),
                "环境变量名被误判成密钥字面量");
    }

    // ========== 扫描 ==========

    /** 全仓所有 {@code src/main/java} 与 {@code src/test/java} 下的 .java（排除 target） */
    private static List<Path> javaSources() {
        Path root = repoRoot();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !hasSegment(p, "target"))
                    .filter(p -> hasSegment(p, "java") && (hasSegment(p, "main") || hasSegment(p, "test")))
                    .forEach(files::add);
        } catch (IOException e) {
            fail("扫不动仓库根 " + root.toAbsolutePath() + "：" + e
                    + "。扫失败必须大声报错 —— 静默返回空列表会让上面那条禁令假绿");
        }
        return files;
    }

    /** 含 {@code src/main/java} 或 {@code src/test/java} 的目录，按 pom.xml 递归认（覆盖嵌套多模块） */
    private static List<Path> sourceRoots() {
        Path root = repoRoot();
        List<Path> subDirs = List.of(Paths.get("src", "main", "java"), Paths.get("src", "test", "java"));
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !hasSegment(p, "target"))
                    .map(Path::getParent)
                    .distinct()
                    .forEach(dir -> {
                        for (Path sub : subDirs) {
                            Path src = dir.resolve(sub);
                            if (Files.isDirectory(src)) {
                                roots.add(src);
                            }
                        }
                    });
        } catch (IOException e) {
            fail("扫不动仓库根 " + root.toAbsolutePath() + "：" + e);
        }
        return roots;
    }

    private static List<String> readLines(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return List.of(text.split("\n", -1));
        } catch (IOException e) {
            return fail("读不出 " + file.toAbsolutePath() + "：" + e
                    + "。读失败必须大声报错，不能静默跳过 —— 跳过就等于那个文件从此无人看守");
        }
    }

    private static boolean hasSegment(Path path, String segment) {
        for (Path part : path) {
            if (part.toString().equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String relativize(Path file) {
        Path relative = repoRoot().relativize(file.toAbsolutePath());
        return relative.toString().replace('\\', '/');
    }

    /** 从源码根反推所属模块名（{@code .../enlev-module-ai/src/main/java} -> {@code enlev-module-ai}） */
    private static String moduleOf(Path sourceRoot) {
        Path p = sourceRoot.toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("pom.xml"))) {
            p = p.getParent();
        }
        return p == null ? sourceRoot.toString() : p.getFileName().toString();
    }

    /**
     * 从 surefire 的工作目录（模块 basedir）向上找仓库根
     *
     * <p>认法是「有 pom.xml 且直接子目录里有 ≥5 个 enlev-module-*」。模块自己也满足前半个条件，
     * 但它没有 enlev-module-* 子目录，所以不会被认错。
     *
     * <p>🔴 找不到就 {@code fail}，绝不退化成「只扫本模块」——
     * 后者会让守卫悄悄缩小到 1/8 的域，看着一样绿。
     */
    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            if (!Files.isRegularFile(dir.resolve("pom.xml"))) {
                continue;
            }
            try (Stream<Path> children = Files.list(dir)) {
                long modules = children.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().startsWith("enlev-module-"))
                        .count();
                if (modules >= 5) {
                    return dir;
                }
            } catch (IOException ignored) {
                // 这一层读不动就继续向上，最终由下面的 fail 兜住
            }
        }
        return fail("从 " + Paths.get("").toAbsolutePath() + " 向上 8 层都没找到仓库根"
                + "（认法：有 pom.xml 且直接子目录里 ≥5 个 enlev-module-*）。"
                + "本类要跨模块扫密钥，找不到根就没法扫 —— 请确认 surefire 的工作目录是模块 basedir，"
                + "而不是让这条守卫静默缩小到只扫本模块");
    }

}
