package cn.enlev.ainb.module.infra.framework.formdesign.core.contract;

import cn.enlev.ainb.module.infra.api.formdesign.dto.FormResourceDescriptorDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子表字段分隔符的「单一事实源」强制点（台账 #101(b)）
 *
 * <h3>为什么要新加这一条</h3>
 * 立案时记的是「{@code SUB_FIELD_SEPARATOR} 的 bpm 副本零测试」。核实现状后发现这句话
 * <b>字面为真但会误导</b>：值本身并非无人看守 ——
 * 契约 JSON 里的 {@code structural.subFieldSeparator} 被 {@code WidgetContractRegistryTest}
 * 钉着字面量，前后端两份 JSON 由 e2e {@code form-widget-contract-sync.spec.ts} 逐项比对。
 * 真正的空洞是<b>没有任何一处把「契约 JSON 的值」与「Java 常量的值」放在一起比过</b>：
 * 改 JSON 的人会被上面那条测试拦住，然后顺手把测试一起改掉，而 Java 侧两份字面量
 * 会静默留在旧值上 —— 表现是「子表字段配了权限却不生效」，从现象反推不到分隔符。
 *
 * <h3>本类补的是哪一段</h3>
 * <ul>
 *   <li>{@link #javaConstantMatchesContract}：Java 侧唯一那份常量 ↔ 契约 JSON。<b>不是恒真断言</b> ——
 *       左边是运行期解析 JSON 得到的字符串，右边是编译期常量，两者不是同一个符号
 *       （对比 {@code assertEquals(X, X)} 那种引用同一常量之后必然为真的写法，见台账 #98）。</li>
 *   <li>{@link #infraMainHasExactlyOneSeparatorDeclaration}：扫本模块 src/main 全部源码，
 *       拦住「再声明一份同值常量」与「把引用改回字面量」。值比对拦不住这两种：
 *       同值字面量在值比对下完全一样绿。</li>
 * </ul>
 *
 * <h3>⚠️ 域的三个边界（照 §0.8.4.1③「『真跑』也有域」写明，别让读者以为它管得更多）</h3>
 * <ol>
 *   <li><b>只扫 infra 的 src/main/java</b> —— bpm 侧由
 *       {@code BpmSubFieldSeparatorReferenceTest} 守（跨模块读不到彼此的源码树）。
 *       system / agent 两个模块今天<b>零份</b>声明（已数过），它们没有强制点：
 *       真在那边新写一份，两个扫描测试都不会红。</li>
 *   <li><b>不含 src/test</b> —— 而且是<b>刻意</b>不含：测试的职责相反，必须钉字面量，
 *       这样改常量的<b>值</b>才会红。生产引常量、测试钉字面量，一松一紧，缺一侧改名就静默通过。</li>
 *   <li><b>扫的是文本行，分不清注释与代码</b> —— 故 javadoc 里点名这个常量时不要写成
 *       {@code String SUB_FIELD_SEPARATOR = "."} 这种带等号带引号的形态，会被判成第二份声明。</li>
 * </ol>
 *
 * <p>另有一件事本类<b>结构上管不到</b>：编译期常量会被内联进引用方的 class（JLS 15.28），
 * 只重装 infra 的 jar 而没重装 bpm 就会静默漂移。那种漂移只能靠运行期比对
 * （{@code DiagnoseNotWorkingTool#diagnoseSubFieldSeparator}）兜，这也是那个出口保留的原因。
 *
 * @author 走进新时代
 */
@DisplayName("子表字段分隔符的单一事实源")
class SubFieldSeparatorSingleSourceTest {

    /**
     * 常量声明行里带字面量的形态，如 {@code public static final String SUB_FIELD_SEPARATOR = ".";}
     *
     * <p>故意只认「名字 + 等号 + 引号」，不认 {@code = SomeOther.CONSTANT}：后者是引用，是本刀要的形态。
     */
    private static final Pattern LITERAL_DECLARATION =
            Pattern.compile("String\\s+SUB_FIELD_SEPARATOR\\s*=\\s*\"");

    /**
     * 任何形式的赋值行（字面量或引用），用来把「第三种形态」也拦下来
     */
    private static final Pattern ANY_ASSIGNMENT =
            Pattern.compile("String\\s+SUB_FIELD_SEPARATOR\\s*=");

    private WidgetContractRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WidgetContractRegistry();
        registry.init();
    }

    @Test
    @DisplayName("Java 侧唯一那份常量的值 == 契约 JSON 里的 structural.subFieldSeparator")
    void javaConstantMatchesContract() {
        // 左边来自运行期解析的 JSON（registry.init() 读 classpath 下的契约文件），
        // 右边是编译期常量 —— 不是同一个符号，所以这条断言有内容。
        // 🔴 不许把它写成 assertEquals(FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR,
        //    FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR) 之类的自比对：那样它恒真，看着一样绿。
        assertEquals(registry.getSubFieldSeparator(), FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR,
                """
                契约 JSON 的 structural.subFieldSeparator 与 Java 常量分家了。
                两边都是「事实源」的样子，但真正生效的是 Java 常量（权限裁剪按它拼 key），
                而前端与 LLM 看到的是 JSON —— 于是「AI 按契约配、后端按常量裁」，各自都自洽。
                改法：确认要哪个值，然后同时改契约 JSON（规范副本在后端 resources/form-design/ 下，
                改完 cp 到前端）与 FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR，
                并让钉字面量的测试跟着改（WidgetContractRegistryTest#testOtherStructuralFacts、
                BpmSubFieldSeparatorReferenceTest#separatorValueIsPinned —— 它们红是设计如此）。
                ⚠️ 改完要全量 mvn install：常量会被内联进 bpm 的 class。
                """);
    }

    @Test
    @DisplayName("🔴 infra 的 src/main 里只有一处带字面量的分隔符声明，且在 FormResourceDescriptorDTO 上")
    void infraMainHasExactlyOneSeparatorDeclaration() throws Exception {
        List<String> literals = new ArrayList<>();
        List<String> references = new ArrayList<>();
        List<String> otherForms = new ArrayList<>();
        for (Path file : mainSources()) {
            String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!ANY_ASSIGNMENT.matcher(lines[i]).find()) {
                    continue;
                }
                String where = file.getFileName() + ":" + (i + 1);
                if (LITERAL_DECLARATION.matcher(lines[i]).find()) {
                    literals.add(where);
                } else if (lines[i].contains("FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR")) {
                    references.add(where);
                } else {
                    // 既不是字面量、也不是引那份常量 —— 例如引了某个本地私有常量或读配置。
                    // 那等于又造了一个中转副本，值比对看不见它。
                    otherForms.add(where + " → " + lines[i].trim());
                }
            }
        }

        assertTrue(otherForms.isEmpty(),
                "出现了第三种形态的分隔符赋值（既不是字面量也不是引 FormResourceDescriptorDTO）："
                        + otherForms + "。请改成直接引那份常量，别再加一层中转");
        assertEquals(1, literals.size(),
                "带字面量的声明应正好 1 处（FormResourceDescriptorDTO），实际 " + literals.size() + "：" + literals
                        + "。多了说明有人在别处又写了一份同值字面量 —— 同值能骗过值比对，"
                        + "但改契约时它不会跟着动，正是台账 #101(b) 立案的那个形态");
        assertTrue(literals.get(0).startsWith("FormResourceDescriptorDTO.java:"),
                "唯一那份字面量声明应在 FormResourceDescriptorDTO 上（它是跨模块 api 包里的权威），实际在 "
                        + literals.get(0));
        // 引用行数<b>不设上限</b>：新消费方直接引常量是好事，不该逼着回来改测试。
        // 只要求它至少覆盖已知的两个解析器读者 —— 少了说明有人把引用改回了字面量（那会先被上面拦住）
        // 或者干脆把常量抄进了别的名字。
        assertTrue(references.size() >= 2,
                "infra 里引用 FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR 的赋值行应至少 2 处"
                        + "（FormFieldCropper 与 FormResourceDescriptorBuilder 各一份本地别名），实际 "
                        + references.size() + "：" + references);
    }

    @Test
    @DisplayName("🔴 探测器自检：同值字面量必须被扫出来，引用常量与 javadoc 点名必须不被扫出来")
    void literalDetectorIsNotVacuous() {
        // 正向：这正是「有人图省事改回去」的形态 —— 值一模一样，值比对看不出来
        assertTrue(LITERAL_DECLARATION.matcher(
                        "    private static final String SUB_FIELD_SEPARATOR = \".\";").find(),
                "探测器连一条同值字面量声明都扫不出来，上一条测试是假绿");
        // 反向：今天的真实写法（引用那份常量）必须扫不出来，否则两处引用会被当成两条违规
        assertTrue(!LITERAL_DECLARATION.matcher(
                        "    private static final String SUB_FIELD_SEPARATOR = "
                                + "FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR;").find(),
                "引用常量的写法被误判成字面量，探测器口径太宽");
        // 反向：javadoc 里不带等号地点名，也必须扫不出来
        assertTrue(!ANY_ASSIGNMENT.matcher(
                        " * {@link FormResourceDescriptorDTO#SUB_FIELD_SEPARATOR} 是 Java 侧唯一那份").find(),
                "javadoc 里点名被误判成赋值行，探测器口径太宽");
        // 反向：别的名字的同值字面量不在域内（本扫描按名字认，不按值认）
        assertTrue(!ANY_ASSIGNMENT.matcher(
                        "    private static final String DOT = \".\";").find(),
                "探测器认错了名字，会把无关常量也扫进来");
    }

    /**
     * 本模块 src/main/java 下全部 .java 文件
     *
     * <p>带一条<b>数量下限</b>的自检：口径写错（比如工作目录不是模块根）时 {@code Files.walk} 会抛异常，
     * 但过滤条件写窄时只会静少扫 —— 少扫的强制点看着一样绿。下限取 300，远低于本模块真实文件数
     * （量出来是 464），只在口径整体坏掉时才红。
     */
    private static List<Path> mainSources() throws IOException {
        Path root = Paths.get("src", "main", "java");
        assertTrue(Files.isDirectory(root),
                "找不到 " + root.toAbsolutePath() + "（工作目录应是模块根，surefire 默认就是模块 basedir）");
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
            assertTrue(files.size() >= 300,
                    "只扫到 " + files.size() + " 个源文件，少于 300，扫描口径坏了（别把这条测试删掉了事）");
            return files;
        }
    }

}
