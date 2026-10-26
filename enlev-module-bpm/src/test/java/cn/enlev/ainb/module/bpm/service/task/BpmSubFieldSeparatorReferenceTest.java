package cn.enlev.ainb.module.bpm.service.task;

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
 * bpm 侧的子表字段分隔符：必须是<b>引用</b>，不是自己那份字面量（台账 #101(b)）
 *
 * <h3>为什么值断言不够，非得扫源码</h3>
 * {@code BpmTaskServiceImpl.SUB_FIELD_SEPARATOR} 现在写的是
 * {@code = FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR}。这条引用一旦成立，
 * <b>任何</b>拿这两侧做比对的断言都变成恒真（编译期就是同一个符号），
 * 于是「谁把它改回 {@code = "."}」在值比对下完全看不出来 —— 值一模一样，全套测试照样绿，
 * 而改契约 JSON 的人从此再也带不动这一份。这正是台账 #98 量到的教训，故本类的主力是扫描而不是比对。
 *
 * <h3>一松一紧：本类同时有两种职责相反的断言</h3>
 * <ul>
 *   <li>{@link #bpmMainReferencesTheInfraApiConstant}（松）：生产源码里<b>不许</b>出现字面量声明。
 *       守的是「别把它拆回去」。</li>
 *   <li>{@link #separatorValueIsPinned}（紧）：<b>刻意</b>钉死字面量 {@code "."}。
 *       守的是「别悄悄改值」—— 引用形态下改 infra 那份常量，本模块会跟着变，
 *       而前端与已落库的字段权限 key 不会跟着变，所以改值必须是一次显式动作、并且在这里撞一次红。</li>
 * </ul>
 * 缺任何一侧，改名或改值都会静默通过。
 *
 * <h3>⚠️ 域的边界</h3>
 * <ol>
 *   <li><b>只扫 bpm 的 src/main/java</b> —— infra 侧由
 *       {@code SubFieldSeparatorSingleSourceTest} 守（跨模块读不到彼此的源码树）。
 *       system / agent 今天<b>零份</b>声明（已数过），它们没有强制点。</li>
 *   <li><b>不含 src/test</b> —— 刻意不含，理由同上：测试必须钉字面量。</li>
 *   <li><b>扫的是文本行，分不清注释与代码</b> —— javadoc 里点名这个常量时不要写成
 *       {@code String SUB_FIELD_SEPARATOR = "."} 这种带等号带引号的形态。</li>
 * </ol>
 *
 * <p>⚠️ 编译期常量会被<b>内联</b>进本模块的 class（JLS 15.28）：只重装 infra 的 jar
 * 而没重装 bpm，运行期就会与契约 JSON 漂移，而本类<b>结构上看不见</b>（它跑在一次完整编译之后）。
 * 那种漂移由 {@code DiagnoseNotWorkingTool#diagnoseSubFieldSeparator} 在运行期兜。
 *
 * @author 走进新时代
 */
@DisplayName("bpm 的子表字段分隔符必须引用 infra 的 api 常量")
class BpmSubFieldSeparatorReferenceTest {

    /**
     * 带字面量的声明形态，如 {@code public static final String SUB_FIELD_SEPARATOR = ".";}
     */
    private static final Pattern LITERAL_DECLARATION =
            Pattern.compile("String\\s+SUB_FIELD_SEPARATOR\\s*=\\s*\"");

    /**
     * 任何形式的声明（字面量或引用）
     */
    private static final Pattern ANY_DECLARATION =
            Pattern.compile("String\\s+SUB_FIELD_SEPARATOR\\s*=");

    @Test
    @DisplayName("🔴 src/main 里零个字面量声明；唯一那份声明必须引 FormResourceDescriptorDTO")
    void bpmMainReferencesTheInfraApiConstant() throws Exception {
        List<String> literals = new ArrayList<>();
        List<String> references = new ArrayList<>();
        List<String> otherForms = new ArrayList<>();
        for (Path file : mainSources()) {
            String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!ANY_DECLARATION.matcher(lines[i]).find()) {
                    continue;
                }
                String where = file.getFileName() + ":" + (i + 1);
                if (LITERAL_DECLARATION.matcher(lines[i]).find()) {
                    literals.add(where + " → " + lines[i].trim());
                } else if (lines[i].contains("FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR")) {
                    references.add(where);
                } else {
                    otherForms.add(where + " → " + lines[i].trim());
                }
            }
        }

        assertTrue(literals.isEmpty(),
                """
                bpm 的 src/main 里又出现了分隔符字面量声明：
                """ + literals + """

                它必须引用 infra 的 FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR（api 包里，跨模块可引）。
                🔴 别以为「值一样就没关系」：同值字面量能骗过一切值比对，
                但改契约 JSON 时这一份不会跟着动 —— 表现是「子表字段配了权限却不生效」，
                或反过来把有权限的字段裁掉，从现象反推不到分隔符。
                当年这里就是一份孤立的 "."，理由是「跨模块不互相依赖内部实现」，
                而那个理由是假的：本模块 pom 依赖 infra，被引的常量也在 api 包里（台账 #101(b)）。
                """);
        assertTrue(otherForms.isEmpty(),
                "出现了第三种形态（既不是字面量也不是引 FormResourceDescriptorDTO）：" + otherForms
                        + "。别再加一层中转常量，那等于又造了一份副本");
        assertEquals(1, references.size(),
                "bpm 里应正好有 1 处引用声明（BpmTaskServiceImpl 上那份公开常量，BpmModelApiImpl 靠它报给诊断工具），"
                        + "实际 " + references.size() + "：" + references
                        + "。多了说明有人在别处又引了一份本地别名 —— 本模块不需要，直接用 BpmTaskServiceImpl 那份");
        assertTrue(references.get(0).startsWith("BpmTaskServiceImpl.java:"),
                "那份引用声明应在 BpmTaskServiceImpl 上，实际在 " + references.get(0));
    }

    @Test
    @DisplayName("🔴 值钉死为「.」—— 引用形态下改 infra 常量会带着本模块一起变，所以改值必须在这里撞一次红")
    void separatorValueIsPinned() {
        // 🔴 这里<b>刻意</b>写字面量，不写 registry/契约那一侧的值：
        //    写常量比对的话，改值时两侧一起动，断言恒真，等于没有强制点（台账 #98 的教训）。
        //    本模块的红由这条负责，契约 JSON 的红由 WidgetContractRegistryTest#testOtherStructuralFacts 负责。
        assertEquals(".", BpmTaskServiceImpl.SUB_FIELD_SEPARATOR,
                """
                分隔符的值被改了。这不是一个可以只改一侧的值：
                已落库的字段权限 key（形如 contacts.remark）与前端 parseFormFields 拼出来的 key
                都按旧值写着，改完它们不会自动迁移。
                真要改，需要同步：契约 JSON 的 structural.subFieldSeparator（规范副本在后端
                resources/form-design/ 下，改完 cp 到前端）、FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR、
                两条钉字面量的测试（本条与 WidgetContractRegistryTest#testOtherStructuralFacts），
                然后全量 mvn install（常量会被内联进本模块的 class）。
                """);
    }

    @Test
    @DisplayName("🔴 探测器自检：同值字面量必须被扫出来，引用形态与 javadoc 点名必须不被扫出来")
    void detectorIsNotVacuous() {
        // 正向：这正是「有人图省事改回去」的形态
        assertTrue(LITERAL_DECLARATION.matcher(
                        "    public static final String SUB_FIELD_SEPARATOR = \".\";").find(),
                "探测器连一条同值字面量声明都扫不出来，扫描那条测试是假绿");
        // 反向：今天的真实写法必须扫不出来，否则本模块唯一的合法声明会被判成违规
        assertTrue(!LITERAL_DECLARATION.matcher(
                        "    public static final String SUB_FIELD_SEPARATOR = "
                                + "FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR;").find(),
                "引用形态被误判成字面量，探测器口径太宽");
        // 反向：消费点（拼接用法）与 javadoc 点名都不该被当成声明
        assertTrue(!ANY_DECLARATION.matcher(
                        "        String prefix = subTableField + SUB_FIELD_SEPARATOR;").find(),
                "使用点被误判成声明，探测器口径太宽");
        assertTrue(!ANY_DECLARATION.matcher(
                        " * 值取自 {@code BpmTaskServiceImpl.SUB_FIELD_SEPARATOR}").find(),
                "javadoc 里点名被误判成声明，探测器口径太宽");
    }

    /**
     * 本模块 src/main/java 下全部 .java 文件
     *
     * <p>带一条<b>数量下限</b>的自检：口径写错（比如工作目录不是模块根）时 {@code Files.walk} 会抛异常，
     * 但过滤条件写窄时只会静少扫 —— 少扫的强制点看着一样绿。下限取 200，远低于本模块真实文件数
     * （量出来是 266），只在口径整体坏掉时才红。
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
            assertTrue(files.size() >= 200,
                    "只扫到 " + files.size() + " 个源文件，少于 200，扫描口径坏了（别把这条测试删掉了事）");
            return files;
        }
    }

}
