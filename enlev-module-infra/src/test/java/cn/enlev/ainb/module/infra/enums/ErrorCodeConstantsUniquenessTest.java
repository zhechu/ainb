package cn.enlev.ainb.module.infra.enums;

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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 错误码「号码」的唯一性与号段归属强制点
 *
 * <h3>为什么要新加这一条</h3>
 * 2026-10-07 全仓扫了一遍，量到 <b>17 组撞号</b>（infra 11、crm 3、ai 2、bpm 1），
 * 另有 ai 的 {@code WRITE_STREAM_ERROR} 号码写成 {@code 1_040_07_001} —— 少一位，
 * 落在业务码的十位制之外。撞号那批里有 19 处让了位，本类负责<b>不让它长回来</b>。
 *
 * <p>撞号的害处要说准，别说过头：
 * <ul>
 *   <li>{@code ServiceException} 是把 code 与 msg <b>一起</b>带出去的，
 *       所以界面上的文字<b>不会</b>张冠李戴 —— 抛哪条就是哪条的话。</li>
 *   <li>{@code infra_api_error_log}（{@code ApiErrorLogDO}）<b>根本没有存错误码的列</b>
 *       （只有 exceptionName / exceptionMessage / exceptionStackTrace 等），
 *       所以「按码聚合告警会算错」这个说法<b>不成立</b>，别拿它当理由。</li>
 *   <li>真正的害处只有一条：<b>任何按码分流的逻辑失效</b> —— 客户端拿到 1001204100
 *       无法判断是「控件类型未登记」还是「联动规则引用了不存在的字段」还是「导入文件无法解析」
 *       （这三个当年就共用这一个号），只能退回去匹配中文文案，而文案是随时会改的。</li>
 * </ul>
 *
 * <h3>三条断言分别拦什么</h3>
 * <ol>
 *   <li>{@link #noTwoConstantsShareANumber}：全仓号码唯一。撞号的直接拦截点。</li>
 *   <li>{@link #everyNumberIsATenDigitBusinessCode}：号码必须是 {@code 1_XXX_YYY_ZZZ} 十位制。
 *       拦 {@code 1_040_07_001} 那类少写一位的笔误 —— 它<b>不撞号</b>，唯一性断言看不见它，
 *       但它落到了业务码号段之外（{@code 104007001 < 1_000_000_000}），
 *       任何按 {@code code / 1_000_000} 取模块段的工具都会算错模块。</li>
 *   <li>{@link #everyNumberLiesInsideItsDeclaredSection}：号码必须落在<b>它自己头上那句
 *       {@code // ========== 节名 1-040-002-000 ==========}</b> 声明的节里。
 *       这条拦的是「归档归错段」—— ai 的 {@code CHAT_ROLE_DISABLE} 头上写着
 *       「API 聊天角色 1-040-002-000」，号却是 {@code 1_040_001_001}（API 模型段），
 *       于是与 {@code MODEL_DISABLE} 撞在一起。crm 的 {@code RECEIVABLE_PLAN_*} 同款：
 *       头上写「回款计划 1-020-005-000」，号却占了客户管理的 1-020-006-000。
 *       <b>节注释就是号码的产权登记</b>，谁与登记不符谁就是闯入者。</li>
 * </ol>
 *
 * <h3>⚠️ 域的边界（照仓内既有写法讲清楚，别让读者以为它管得更多）</h3>
 * <ol>
 *   <li><b>本类刻意跨模块读源码树。</b>仓内的通行约定是「跨模块读不到彼此的源码树、每模块守自己」
 *       （见 {@code SubFieldSeparatorSingleSourceTest} 的域说明）。这里<b>故意不照办</b>：
 *       号码是<b>全仓共用一个空间</b>的，分模块各守一份会漏掉跨模块撞号，
 *       而且要把同一份逻辑抄 5 遍（现在的业务模块数）。所以它从 surefire 的工作目录（模块 basedir）<b>向上找仓库根</b>，
 *       再扫所有 {@code enlev-module-*}。<b>找不到仓库根就大声失败</b>，绝不静默通过。</li>
 *   <li><b>按磁盘扫，不按构建扫。</b>当初的理由是 im：它在根 pom 里被注释掉、不参与编译，
 *       但号码已经写死在源码里，一旦被启用就会带着撞号上线。
 *       {@code 12347efcd1}（2026-10-22 清除未用上的模块）把 im 连目录一起删了，
 *       这个具体例子已经不存在，但<b>口径保留</b> —— 被注释掉的模块随时可能再出现，
 *       而「按构建扫」看不见它，正是本类要防的那种静默豁口。</li>
 *   <li><b>不扫 src/test。</b>{@code OAuth2OpenControllerTest} 等测试里有
 *       {@code new ErrorCode(400, ...)} 这类<b>临时构造</b>，那是测试夹具、不是号码登记，不在域内。</li>
 *   <li><b>不管 {@code GlobalErrorCodeConstants}。</b>那是 HTTP 语义的 0/400/401/500/999 一套，
 *       与业务码不同号空间；第 2 条断言（十位制）本身就保证业务码永远撞不到它。</li>
 *   <li><b>扫的是文本行，不解析 Java 语法。</b>因此注释里若出现
 *       {@code ErrorCode FOO = new ErrorCode(1_001_000_000,} 这种完整形态会被当成真声明。
 *       写文档时避免这种写法即可。</li>
 * </ol>
 *
 * <h3>⚠️ 一件曾经量到、如今已随 im 消失的事（留档，别再按它推理）</h3>
 * {@code ai} 与 {@code im} 的类注释曾<b>都</b>写着「使用 1-040-000-000 段」—— 两个模块共用一个号段。
 * 当时不撞，靠的是一处<b>没有写进任何文档的自发划分</b>：ai 占 {@code 1_040_000_xxx ~ 1_040_011_xxx}，
 * im 占 {@code 1_040_300_xxx ~ 1_040_900_xxx}。本类<b>没有</b>为这条划分单独写断言
 * （硬编一个 300 的边界只会把一次历史巧合固化成规矩）。
 *
 * <p>{@code 12347efcd1}（2026-10-22）删掉 im 之后，1-040 段的 {@code 300+} 整片空了出来，
 * ai 往后扩节不必再避让任何邻居。<b>但「共用号段靠自发划分」这个形状没有消失</b>，
 * 它只是暂时没有第二个当事人：真再撞上的那一刻，兜底的仍旧只有第 1 条「全仓唯一」，
 * 它会红并报出两边的常量名 —— 号段归属本身依旧无人看守。
 *
 * @author 走进新时代
 */
@DisplayName("错误码号码的唯一性与号段归属")
class ErrorCodeConstantsUniquenessTest {

    /**
     * 一条声明：{@code ErrorCode NAME = new ErrorCode(1_001_004_002, "...")}
     *
     * <p>刻意<b>不</b>要求整行闭合，因为有的文案跨行拼接（见 bpm 的
     * {@code PROCESS_INSTANCE_START_CHILD_NOT_ALLOWED}）—— 号码总在第一个参数上，认到它就够。
     */
    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s*ErrorCode\\s+([A-Z0-9_]+)\\s*=\\s*new\\s+ErrorCode\\s*\\(\\s*(\\d[\\d_]*)\\s*,");

    /** 节标题：{@code // ========== 节名 1-040-002-000 ==========} */
    private static final Pattern SECTION_HEADER = Pattern.compile("^\\s*//\\s*=+\\s*(.*?)\\s*=+\\s*$");

    /**
     * 节标题里的号段。分隔符<b>同时认 {@code -} 与 {@code _}</b>：
     * 仓内两种写法都在用（ai 写 {@code 1-040-002-000}，system 写 {@code 1_002_031_000}）。
     */
    private static final Pattern SECTION_NUMBER = Pattern.compile("(\\d)[-_](\\d{3})[-_](\\d{3})[-_](\\d{3})");

    /** 业务码的十位制下限：{@code 1_000_000_000} */
    private static final int BUSINESS_CODE_FLOOR = 1_000_000_000;

    /**
     * 磁盘上应有的模块数下限
     *
     * <p>历史：2026-10-15 量到 6，之前是 7（crm 被删）。2026-10-22 的
     * {@code 12347efcd1 refactor（系统）：清除未用上的模块} 又把 {@code enlev-module-im}
     * 连目录一起删掉，于是变 5 —— 与根 pom {@code <modules>} 里的 5 个业务模块正好对齐。
     *
     * <p>🔴 下调下限<b>不是</b>「把守卫放松到能绿」：删模块是仓库真实变小，
     * 而本条自检要抓的是「glob / 向上找根坏了 ⟹ 域悄悄缩水 ⟹ 上面三条断言假绿」。
     * 判据是<b>下限必须与根 pom 的模块清单同源</b>：两者对不上才是口径坏了。
     * 所以再加/再删业务模块时，这里与下面的 {@link #REQUIRED_MODULES} 要一起改。
     */
    private static final int MODULE_FILE_FLOOR = 5;

    /** 全仓业务码总数下限（2026-10-15 量到 668；im 那 76 个随 12347efcd1 一起没了，2026-10-25 量到 603） */
    private static final int CODE_FLOOR = 570;

    /** 必须扫到的模块名 —— 少一个就说明 glob 口径坏了（im 已随 12347efcd1 从仓里删除） */
    private static final Set<String> REQUIRED_MODULES =
            Set.of("agent", "ai", "bpm", "infra", "system");

    private record Decl(String module, int line, String name, int code, Integer declaredSection) {

        String where() {
            return module + "/ErrorCodeConstants.java:" + line + " " + name;
        }
    }

    @Test
    @DisplayName("🔴 全仓没有任何两个常量共用一个号码")
    void noTwoConstantsShareANumber() {
        Map<Integer, List<Decl>> byCode = new TreeMap<>();
        for (Decl d : parseAll()) {
            byCode.computeIfAbsent(d.code(), k -> new ArrayList<>()).add(d);
        }
        List<String> dupes = new ArrayList<>();
        byCode.forEach((code, holders) -> {
            if (holders.size() > 1) {
                dupes.add(format(code) + " -> " + holders.stream().map(Decl::where).toList());
            }
        });
        assertTrue(dupes.isEmpty(), () -> """
                有 %d 组错误码撞号：
                  %s

                害处只有一条，但很实在：任何按码分流的逻辑失效 —— 客户端拿到一个号码，
                无法判断到底是这几件事里的哪一件，只能退回去匹配中文文案，而文案随时会改。
                （⚠️ 别把害处说过头：ServiceException 把 code 与 msg 一起带出去，界面文字不会张冠李戴；
                 infra_api_error_log 也根本没有存错误码的列，谈不上「按码聚合算错」。）

                改法（照 2026-10-07 那次修复的机械规则，不要凭记忆挑谁让位）：
                  1. 若撞号者中有一条的号码落在它自己头上 // ========== 节标题声明的节之外，
                     那条是闯入者，由它让位、挪回自己声明的节；
                  2. 否则（同一节内真撞号）由行号靠后的那条让位；
                  3. 新号取「目标节内严格大于原号的最小空号」，按 (文件, 行号) 顺序逐个分配。
                改前先确认爆炸半径：这些号码在前端 src/tests 与后端 .java/.sql/.xml/.yaml/.json 里
                有没有被硬编（2026-10-07 那次量到的是零命中，调用点一律按常量名引用）。
                """.formatted(dupes.size(), String.join("\n  ", dupes)));
    }

    @Test
    @DisplayName("🔴 每个号码都是 1_XXX_YYY_ZZZ 十位制的业务码")
    void everyNumberIsATenDigitBusinessCode() {
        List<String> malformed = new ArrayList<>();
        for (Decl d : parseAll()) {
            if (d.code() < BUSINESS_CODE_FLOOR || d.code() > 1_999_999_999) {
                malformed.add(d.where() + " = " + d.code());
            }
        }
        assertTrue(malformed.isEmpty(), () -> """
                有 %d 个错误码不是十位制的业务码（应在 1_000_000_000 ~ 1_999_999_999 之间）：
                  %s

                这类号码不一定撞号，所以「唯一性」那条看不见它 —— 但它落到了业务码号段之外，
                任何按 code / 1_000_000 取模块段的工具都会算错模块，按节聚合也会把它归到不存在的节里。
                典型成因是少写一位：ai 的 WRITE_STREAM_ERROR 当年写成 1_040_07_001（应为 1_040_007_001）。
                """.formatted(malformed.size(), String.join("\n  ", malformed)));
    }

    @Test
    @DisplayName("🔴 每个号码都落在它自己头上那句节标题声明的节里")
    void everyNumberLiesInsideItsDeclaredSection() {
        List<String> stray = new ArrayList<>();
        for (Decl d : parseAll()) {
            if (d.declaredSection() == null) {
                continue; // 头上没有任何带号段的节标题，无从判定归属，不算违规
            }
            if (d.code() / 1000 != d.declaredSection() / 1000) {
                stray.add(d.where() + " = " + format(d.code())
                        + "，但节标题声明的是 " + format(d.declaredSection()) + " 这一段");
            }
        }
        assertTrue(stray.isEmpty(), () -> """
                有 %d 个常量的号码不在它自己头上那句节标题声明的节里：
                  %s

                节标题就是号码的产权登记。归错段的常量会去占别人家的号，
                于是与那一家真正的主人撞号 —— ai 的 CHAT_ROLE_DISABLE 头上写着
                「API 聊天角色 1-040-002-000」，号却写成 1_040_001_001（API 模型段），
                结果与 MODEL_DISABLE 撞在一起；crm 的 RECEIVABLE_PLAN_* 同款
                （头上写「回款计划 1-020-005-000」，号占了客户管理的 1-020-006-000）。

                改法：把号码挪回它自己声明的那一节（取该节内大于原号的最小空号），
                而不是去改节标题 —— 除非你确认这个常量「本来就该」属于另一节。
                """.formatted(stray.size(), String.join("\n  ", stray)));
    }

    @Test
    @DisplayName("🔴 扫描域自检：模块数、码数下限，且五个模块一个不少")
    void scanDomainIsNotVacuous() {
        List<Path> files = constantsFiles();
        assertTrue(files.size() >= MODULE_FILE_FLOOR,
                "只扫到 " + files.size() + " 个 ErrorCodeConstants.java，少于 " + MODULE_FILE_FLOOR
                        + "。先跟根 pom 的 <modules> 对一下：那边是 5 个业务模块，两边应当同源。"
                        + "对得上就是仓里真删了模块（请同步下调本下限与 REQUIRED_MODULES，"
                        + "并在注释里记下是哪个提交删的）；对不上就是「向上找仓库根」或 glob 口径坏了 —— "
                        + "那样上面三条断言会假绿，别把这条自检删掉了事");

        Set<String> found = new HashSet<>();
        for (Path f : files) {
            found.add(moduleOf(f));
        }
        assertEquals(REQUIRED_MODULES, found,
                "扫到的模块与预期不符。少了说明 glob 漏了模块（漏掉的那个从此无人看守），"
                        + "多了说明仓里新加了模块 —— 那请把它补进 REQUIRED_MODULES，"
                        + "并确认它的号段没有与既有模块重叠");

        int total = parseAll().size();
        assertTrue(total >= CODE_FLOOR,
                "全仓只解析出 " + total + " 个错误码，少于 " + CODE_FLOOR
                        + "（2026-10-25 量到的是 603）。解析口径坏了，上面三条断言会假绿");
    }

    @Test
    @DisplayName("🔴 探测器自检：撞号、少一位、归错段都必须被扫出来，正常声明必须不被误判")
    void detectorsAreNotVacuous() {
        // 正向：撞号 —— 这正是 2026-10-07 量到的形态（两条不同语义共用 1_001_204_100）
        List<Decl> dupes = parseSource("dup", """
                public interface ErrorCodeConstants {
                    // ========== 表单设计 1-001-204-000 ==========
                    ErrorCode FORM_DESIGN_WIDGET_UNKNOWN = new ErrorCode(1_001_204_100, "控件类型未登记");
                    ErrorCode FORM_DESIGN_IMPORT_FILE_INVALID = new ErrorCode(1_001_204_100, "导入文件无法解析");
                }
                """);
        assertEquals(2, dupes.size(), "探测器连两条声明都没扫出来，上面几条断言全是假绿");
        assertEquals(dupes.get(0).code(), dupes.get(1).code(), "探测器没认出这是一个撞号");

        // 正向：少一位的号码 —— 十位制断言要能抓住它
        List<Decl> malformed = parseSource("malformed", """
                public interface ErrorCodeConstants {
                    // ========== API 写作 1-040-007-000 ==========
                    ErrorCode WRITE_STREAM_ERROR = new ErrorCode(1_040_07_001, "写作生成异常");
                }
                """);
        assertEquals(1, malformed.size(), "探测器没扫出少一位的那条声明");
        assertTrue(malformed.get(0).code() < BUSINESS_CODE_FLOOR,
                "1_040_07_001 应被判为落在十位制之外，实际 " + malformed.get(0).code());

        // 正向：归错段 —— 节标题声明 1-040-002-000，号却写成 1-040-001-001
        List<Decl> stray = parseSource("stray", """
                public interface ErrorCodeConstants {
                    // ========== API 聊天角色 1-040-002-000 ==========
                    ErrorCode CHAT_ROLE_DISABLE = new ErrorCode(1_040_001_001, "聊天角色已禁用");
                }
                """);
        assertEquals(1, stray.size(), "探测器没扫出归错段的那条声明");
        assertEquals(1_040_002_000, stray.get(0).declaredSection(),
                "节标题里的号段没被解析出来，归错段这类违规将永远扫不到");
        assertTrue(stray.get(0).code() / 1000 != stray.get(0).declaredSection() / 1000,
                "1_040_001_001 应被判为不在 1-040-002-000 这一节里");

        // 反向：节标题用下划线写（system 的真实形态）也必须认得，否则 system 会整片漏检
        List<Decl> underscored = parseSource("underscored", """
                public interface ErrorCodeConstants {
                    // ========== 权限资源 1_002_031_000 ==========
                    ErrorCode RESOURCE_TYPE_INVALID = new ErrorCode(1_002_031_002, "资源类型不合法：{}");
                }
                """);
        assertEquals(1_002_031_000, underscored.get(0).declaredSection(),
                "下划线写法的节标题没被解析出来，system 会整片漏检");
        assertTrue(underscored.get(0).code() / 1000 == underscored.get(0).declaredSection() / 1000,
                "在自己节里的正常声明被误判成归错段，探测器口径太严");

        // 反向：文案跨行拼接的声明（bpm 的真实形态）也要认得，号码在第一个参数上
        List<Decl> multiline = parseSource("multiline", """
                public interface ErrorCodeConstants {
                    // ========== 流程实例 1-009-004-000 ==========
                    ErrorCode PROCESS_INSTANCE_START_CHILD_NOT_ALLOWED = new ErrorCode(1_009_004_011,
                            "该主流程未被授权调起子流程（{}）。"
                            + "校验的是「主流程能不能调这个子流程」");
                }
                """);
        assertEquals(1, multiline.size(), "文案跨行拼接的声明没被扫出来，bpm 会漏检");
        assertEquals(1_009_004_011, multiline.get(0).code(), "跨行声明的号码解析错了");

        // 反向：不带号段的节标题（如 infra 的「// 运行时」）不许把归属清空
        List<Decl> unnumbered = parseSource("unnumbered", """
                public interface ErrorCodeConstants {
                    // ========== 表单设计 1-001-204-000 ==========
                    ErrorCode FORM_DESIGN_PUBLISH_INVALID = new ErrorCode(1_001_204_096, "{}");
                    // 运行时
                    ErrorCode FORM_DESIGN_RUNTIME_EXECUTE_FAIL = new ErrorCode(1_001_204_030, "操作失败");
                }
                """);
        assertEquals(2, unnumbered.size(), "普通行注释把声明扫描打断了");
        assertEquals(1_001_204_000, unnumbered.get(1).declaredSection(),
                "不带号段的「// 运行时」把节归属清空了 —— 那样归错段这类违规会被静默放过");
    }

    // ========== 扫描与解析 ==========

    private static List<Decl> parseAll() {
        List<Decl> all = new ArrayList<>();
        for (Path file : constantsFiles()) {
            try {
                all.addAll(parseSource(moduleOf(file), Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                fail("读不出 " + file.toAbsolutePath() + "：" + e
                        + "。读失败必须大声报错，不能静默跳过 —— 跳过就等于那个模块从此无人看守");
            }
        }
        return all;
    }

    /**
     * 解析一份源码文本，逐行认声明，并记住「最近一句带号段的节标题」作为归属
     *
     * <p>单独抽出来是为了让 {@link #detectorsAreNotVacuous} 能喂合成文本进去 ——
     * 探测器若连一个<b>种进去的</b>撞号都认不出来，那几条断言就是假绿。
     */
    private static List<Decl> parseSource(String module, String source) {
        List<Decl> out = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        Integer section = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher header = SECTION_HEADER.matcher(line);
            if (header.matches()) {
                // 只有真的带号段的节标题才更新归属；「// 运行时」这类不带号段的原样保留上一节
                Matcher num = SECTION_NUMBER.matcher(header.group(1));
                if (num.find()) {
                    section = Integer.parseInt(num.group(1)) * 1_000_000_000
                            + Integer.parseInt(num.group(2)) * 1_000_000
                            + Integer.parseInt(num.group(3)) * 1_000
                            + Integer.parseInt(num.group(4));
                }
                continue;
            }
            Matcher decl = DECLARATION.matcher(line);
            if (decl.find()) {
                out.add(new Decl(module, i + 1, decl.group(1),
                        Integer.parseInt(decl.group(2).replace("_", "")), section));
            }
        }
        return out;
    }

    /** 磁盘上所有 {@code enlev-module-*}{@code /src/main/java/**}{@code /enums/ErrorCodeConstants.java} */
    private static List<Path> constantsFiles() {
        Path root = repoRoot();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("enlev-module-"))
                    .sorted()
                    .toList()) {
                Path src = module.resolve(Paths.get("src", "main", "java"));
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(src)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().equals("ErrorCodeConstants.java"))
                            .filter(p -> p.getParent() != null
                                    && p.getParent().getFileName().toString().equals("enums"))
                            .forEach(files::add);
                }
            }
        } catch (IOException e) {
            fail("扫不动仓库根 " + root.toAbsolutePath() + "：" + e
                    + "。扫失败必须大声报错 —— 静默返回空列表会让上面三条断言全部假绿");
        }
        return files;
    }

    /**
     * 从 surefire 的工作目录（模块 basedir）向上找仓库根
     *
     * <p>认法是「有 pom.xml 且直接子目录里有 ≥5 个 enlev-module-*」。模块自己也满足前半个条件，
     * 但它没有 enlev-module-* 子目录，所以不会被认错。
     *
     * <p>🔴 找不到就 {@code fail}，绝不返回 null 或退化成「只扫本模块」——
     * 后者会让守卫悄悄缩小到 1/7 的域，看着一样绿。
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
                + "本类要跨模块扫号码，找不到根就没法扫 —— 请确认 surefire 的工作目录是模块 basedir，"
                + "而不是让这条守卫静默缩小到只扫本模块");
    }

    private static String moduleOf(Path constantsFile) {
        Path p = constantsFile;
        while (p != null && !p.getFileName().toString().startsWith("enlev-module-")) {
            p = p.getParent();
        }
        return p == null ? constantsFile.toString() : p.getFileName().toString().replace("enlev-module-", "");
    }

    /**
     * {@code 1040009103 -> 1_040_009_103}，只为报错信息好读
     *
     * <p>🔴 不足十位时<b>原样打印、绝不补零</b>：补零会把「少写一位」这个病因本身藏起来
     * （{@code 104007001} 补成 {@code 0_104_007_001} 之后，看着像是个正常的号，只是分错了节）。
     */
    private static String format(int code) {
        String s = String.valueOf(code);
        if (s.length() != 10) {
            return s;
        }
        return s.substring(0, 1) + "_" + s.substring(1, 4) + "_" + s.substring(4, 7) + "_" + s.substring(7);
    }

}
