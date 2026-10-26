package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.io.NodeIoContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code WorkflowDslValidator#knownTypesHint()} 的「单一出处」强制点（台账 #101(a)）。
 *
 * <p>那句提示是「type 未知」错误文案的填充物，直接喂给模型自我纠错。改造前它把类型名<b>手抄</b>成
 * 10 个字面量，再并上 {@code GRAPH_BUILDER_TYPES} 的 3 个；与权威出处 {@link NodeIoContract}
 * 之间<b>没有任何比对</b>，所以：
 * <ul>
 *   <li>契约加第 14 型时，提示会静默少一个 —— 模型照着提示改、改完仍报「不被支持」，来回打转；</li>
 *   <li>{@code GRAPH_BUILDER_TYPES} 是 {@code Set.of}，迭代顺序掺了每次 JVM 启动都不同的 SALT，
 *       于是同一份代码在不同进程里给出<b>不同的提示串</b>（实测 12 次全新 JVM 得 5 种，
 *       见 {@code _evidence/setorder121.txt}）。任何想逐字钉住它的测试都会变成偶发红，
 *       而 flaky 通常被当环境问题忽略掉。</li>
 * </ul>
 *
 * <p>改造后提示派生自 {@link NodeIoContract#all()}（{@code List.copyOf}，<b>保持声明顺序</b>），
 * 两个毛病一起没了：5 次全新 JVM 输出恒为 1 种（{@code _evidence/hintorder121.txt}）。
 *
 * <h3>本类四条断言各守什么，为什么互不冗余</h3>
 * <ol>
 *   <li>{@link #hintPinsTheThirteenTypesInDeclarationOrder} 钉<b>值</b>（右边是字面量，非恒真）：
 *       契约改名或改序都会红。</li>
 *   <li>{@link #graphBuilderTypesAreAllRegisteredInContract} 钉<b>另一半出处</b>：
 *       {@code isKnownType} 认的类型 = {@code GRAPH_BUILDER_TYPES} ∪ 注册表，
 *       前者若加了一型而契约没加，第 1 条<b>看不出来</b>（提示仍与契约相等），只有这条能红。</li>
 *   <li>{@link #hintBodyDerivesFromContractWithoutTypeNameLiterals} 钉<b>机制</b>：扫源码。
 *       改回一个<b>同值</b>的手抄，第 1 条照绿（值一样），纪律却已破 —— 这正是台账 #98 撞过的坑
 *       （{@code NodeIoContractTest#mainSourcesHaveNoDefaultNameLiterals} 同一道理）。</li>
 *   <li>{@link #literalScannerIsNotVacuous} 是第 3 条的<b>自检</b>：把改造前那段原文喂给探测器，
 *       必须正好扫出 10 条。探测器扫不出东西时，第 3 条是假绿。</li>
 * </ol>
 *
 * <h3>⚠️ 域的边界</h3>
 * 第 3 条只扫 {@code knownTypesHint()} 的<b>方法体</b>，不扫整个 src/main：类型名字面量在别处
 * 有合法用途（{@code isNonCompensableType} 的静态集合、各执行器的 {@code getType()}）。
 * 扫描是<b>文本级</b>的，分不清注释与代码，故方法体里的行尾注释也不许带类型名的双引号形式 ——
 * 与 {@code NodeIoContractTest} 同一取舍。
 *
 * <p>本类<b>不覆盖</b>「注册表里某个执行器的 {@code getType()} 不在契约里」这一路：那需要枚举
 * 全部 {@code NodeExecutor} 实现，属于 S9-① 覆盖表 / 台账 #97 的域，不在本刀范围内。
 *
 * @author 走进新时代
 */
class KnownTypesHintSingleSourceTest {

    /**
     * 改造后 {@code knownTypesHint()} 的真实输出，<b>观测得来</b>（先跑一发打印再抄进来，不预测）。
     * 长度 167、13 段、顺序 = {@link NodeIoContract} 里的声明顺序（基础 / 分支与并行 / 人工介入 / 高级）。
     */
    private static final String EXPECTED_HINT = "tool / llm / knowledge_retrieval / script / "
            + "condition.rule / condition.llm / parallel / iteration / "
            + "hitl.confirm / hitl.bpm_approval / wait / agent_loop / sub_workflow";

    private static final String SIGNATURE = "private String knownTypesHint()";

    /** Java 字符串字面量（含转义）。方法体里没有含大括号的字面量，故 {@link #methodBody} 的配对计数不受影响 */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    /** 方法体里唯一合法的字符串字面量：连接符。除此之外任何字面量都算「又手抄了一个类型名」 */
    private static final String SEPARATOR_LITERAL = "\" / \"";

    @Test
    @DisplayName("🔴 提示串逐字钉住：13 型、契约声明顺序（生产派生、测试钉字面量）")
    void hintPinsTheThirteenTypesInDeclarationOrder() {
        // knownTypesHint() 只读静态（NodeIoContract + GRAPH_BUILDER_TYPES），不碰任何实例字段，
        // 所以裸 new 就够，不必搭 Spring 上下文或桩注册表
        String hint = ReflectionTestUtils.invokeMethod(new WorkflowDslValidator(), "knownTypesHint");
        assertNotNull(hint, "knownTypesHint() 返回 null —— 方法签名或可见性变了？");
        assertEquals(EXPECTED_HINT, hint,
                """
                提示串与钉住的字面量不一致。这句文案会直接印在「type 未知」的错误里喂给模型，
                所以两种成因都得人来判：
                ① 契约增删了类型或改了声明顺序 —— 那是<b>有意的</b>，把上面的字面量同步过来即可；
                ② knownTypesHint() 被改回手抄/改用 NodeIoContract.types() —— types() 是无序 Set，
                   会让这句文案在不同进程里抖出不同顺序（实测原实现 12 次 JVM 得 5 种串）。
                """);
        assertEquals(13, hint.split(" / ", -1).length, "提示串应正好列出 13 型");
    }

    @Test
    @DisplayName("🔴 GRAPH_BUILDER_TYPES 全部登记在契约里 —— isKnownType 认的另一半出处不许脱离契约")
    void graphBuilderTypesAreAllRegisteredInContract() {
        // ⚠️ getField 的返回类型是 Object（不像 invokeMethod 那样带 <T>），故必须显式转型
        @SuppressWarnings("unchecked")
        Set<String> graphBuilderTypes =
                (Set<String>) ReflectionTestUtils.getField(WorkflowDslValidator.class, "GRAPH_BUILDER_TYPES");
        // 空集会让下面的 containsAll 恒真（字段改名时反射返回 null / 空），故先钉住「它真的有内容」
        assertNotNull(graphBuilderTypes,
                "反射读不到 WorkflowDslValidator.GRAPH_BUILDER_TYPES —— 字段被改名或删了？"
                        + "改名的话本条断言会退化成恒真，必须同步改这里");
        assertFalse(graphBuilderTypes.isEmpty(), "GRAPH_BUILDER_TYPES 是空集，本条断言已失去意义");

        Set<String> contractTypes = NodeIoContract.types();
        List<String> missing = new ArrayList<>();
        for (String type : graphBuilderTypes) {
            if (!contractTypes.contains(type)) {
                missing.add(type);
            }
        }
        assertTrue(missing.isEmpty(),
                "这些类型由编译器合成子图、被 isKnownType 放行，却没登记进 NodeIoContract：" + missing
                        + "。后果正是台账 #101(a) 那个缺陷的另一条来路：DSL 写它能过校验，"
                        + "而「type 未知」的提示里永远列不出它，模型无从得知这个类型存在");
    }

    @Test
    @DisplayName("🔴 knownTypesHint() 方法体零个类型名字面量、且引 all() 不引 types() —— 同值手抄能骗过值比对，只有扫源码拦得住")
    void hintBodyDerivesFromContractWithoutTypeNameLiterals() throws Exception {
        Path file = Paths.get("src", "main", "java", "cn", "enlev", "ainb", "module", "agent",
                "framework", "agent", "core", "validate", "WorkflowDslValidator.java");
        assertTrue(Files.isRegularFile(file),
                "找不到 " + file.toAbsolutePath() + "（工作目录应是模块根，与 NodeIoContractTest#mainSources 同一口径）");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        // 下限自检：路径口径写窄只会静少扫，而少扫的强制点看着一样绿。真实 2405 行，取 1500 只在整体坏掉时才红
        assertTrue(source.split("\n", -1).length >= 1500,
                "WorkflowDslValidator.java 只有 " + source.split("\n", -1).length
                        + " 行（真实约 2405）—— 扫到的不是那个文件，本条断言不作数");

        String body = methodBody(source);
        assertEquals(List.of(), typeNameLiteralsIn(body),
                """
                knownTypesHint() 的方法体里出现了连接符之外的字符串字面量，说明类型名又被手抄回来了：
                方法体 → """ + body + """

                手抄即便<b>同值</b>也算破纪律：契约加型时这个读者不会跟着动，提示会静默少一型，
                而「少一型」的表现是模型照着提示改、改完仍报不被支持。请改成遍历 NodeIoContract.all()。
                ⚠️ 本扫描是文本级的，分不清注释与代码；若命中的是方法体里的注释，把双引号去掉即可。
                """);
        assertTrue(body.contains("NodeIoContract.all()"),
                "方法体没引 NodeIoContract.all() —— 派生链断了。方法体 → " + body);
        assertFalse(body.contains("NodeIoContract.types()"),
                "方法体改用了 NodeIoContract.types()：它是 Set.copyOf(keySet())，<b>无序</b>，"
                        + "会把顺序抖动从原来的 3 个扩到 13 个。必须用 all()（List.copyOf(values())，保持声明顺序）");
    }

    @Test
    @DisplayName("🔴 探测器自检：改造前那段原文必须正好扫出 10 条类型名字面量，改造后必须扫出 0 条")
    void literalScannerIsNotVacuous() {
        // 改造前的真实实现，逐字抄自 git 历史（HEAD 6ded131e8a 之前的 WorkflowDslValidator:1972-1980）
        String before = """
                private String knownTypesHint() {
                    Set<String> all = new LinkedHashSet<>(GRAPH_BUILDER_TYPES);
                    all.addAll(List.of("llm", "tool", "condition.rule", "condition.llm",
                            "hitl.confirm", "hitl.bpm_approval", "knowledge_retrieval", "parallel", "wait", "script"));
                    return String.join(" / ", all);
                }""";
        List<String> hits = typeNameLiteralsIn(methodBody(before));
        assertEquals(10, hits.size(),
                "探测器在改造前那段原文里扫出 " + hits.size() + " 条字面量，期望正好 10 条（手抄的类型名个数）："
                        + hits + "。扫少了 ⟹ 上一条测试是假绿；扫多了 ⟹ 探测器把连接符也算进去了");
        assertTrue(hits.contains("\"knowledge_retrieval\"") && hits.contains("\"script\""),
                "探测器漏掉了长名字/短名字的某一端，口径不稳：" + hits);

        // 反向：改造后的真实写法必须扫不出来（否则上一条测试永远红，等于没有强制点）
        String after = """
                private String knownTypesHint() {
                    List<String> all = new ArrayList<>();
                    for (NodeIoContract.Contract contract : NodeIoContract.all()) {
                        all.add(contract.type());
                    }
                    return String.join(" / ", all);
                }""";
        assertEquals(List.of(), typeNameLiteralsIn(methodBody(after)),
                "派生写法被误判成手抄，探测器口径太宽");
        // 反向：javadoc/行注释里不带引号地点名类型（生产代码的注释就是这么写的），也必须扫不出来
        assertEquals(List.of(), typeNameLiteralsIn("// 契约里 hitl.confirm 与 sub_workflow 同组"),
                "注释里点名类型被误判成字面量，探测器口径太宽");
    }

    /** 取 {@code knownTypesHint()} 的方法体（含签名与收尾大括号）；找不到或大括号不配对都直接失败，不静默返回空串 */
    private static String methodBody(String source) {
        int start = source.indexOf(SIGNATURE);
        assertTrue(start >= 0,
                "找不到签名「" + SIGNATURE + "」—— 方法被改名/改可见性/删了？"
                        + "改名会让本类三条源码断言一起失去对象，必须同步改 SIGNATURE");
        int depth = 0;
        for (int i = source.indexOf('{', start); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("「" + SIGNATURE + "」的方法体大括号不配对，扫描结果不作数");
    }

    /** 方法体里除连接符外的全部字符串字面量（保持出现顺序，便于失败信息定位） */
    private static List<String> typeNameLiteralsIn(String body) {
        List<String> hits = new ArrayList<>();
        Matcher matcher = STRING_LITERAL.matcher(body);
        while (matcher.find()) {
            if (!SEPARATOR_LITERAL.equals(matcher.group())) {
                hits.add(matcher.group());
            }
        }
        return hits;
    }
}
