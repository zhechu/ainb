package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「每个写工具都必须有变更抽取器」的<b>强制点</b>（S2-c2）
 *
 * <h3>为什么这条必须有测试，而不能只写在文档里</h3>
 * 变更清单的全部价值在于「用户点同意之前看见的，就是运行期会发生的」。
 * 新增一个写工具却忘了配抽取器时，兜底 {@link GenericToolChangeExtractor} 会保证
 * 清单里<b>不少一项</b>，但那一项只会说「调用工具（参数略）」——
 * 说不出改的是哪张表、哪张表单、哪条记录。审批人照样点了同意，
 * 只是他同意的东西比清单看起来更模糊。这属于「约束没有强制点」那一类缺陷：
 * 规矩写在方案里，编译器不管，运行期不报错，只有用户能感觉到。
 * 所以这里把它变成一条会红的断言。
 *
 * <h3>🔴 双向都查，因为两个方向都会静默失效</h3>
 * <ul>
 *   <li><b>正向</b>（{@link #everyWriteToolHasADedicatedExtractor}）：写工具没抽取器 ⟹ 清单退化成兜底</li>
 *   <li><b>反向</b>（{@link #everyExtractorPointsAtARealTool}）：抽取器的 {@code toolId()} 指向一个
 *       不存在的工具键 ⟹ 这个抽取器永远分派不到，成了死代码，而对应工具的变更悄悄退回兜底。
 *       工具改名时最容易撞上：改了 {@code NAME} 却忘了抽取器，或反过来</li>
 * </ul>
 * 只查正向的话，反向那种「看起来有抽取器、其实不生效」查不出来 ——
 * 而它正是本模块最恨的失败模式（配了但不生效）。
 *
 * <h3>为什么用扫描而不是把 30 个工具名抄进测试</h3>
 * 抄一份名单就是第二份口径：新增工具时没人会想起回来改测试，
 * 于是测试继续绿着、缺口继续存在 —— 那等于没写。
 * 扫描 {@code @AgentTool} 让「加工具」这个动作本身就自动进入被检范围。
 *
 * @author 走进新时代
 */
class ToolChangeExtractorCoverageTest {

    /**
     * 扫描根。🔴 刻意扫整个 agent 模块而不是 {@code tool.impl} 子包：
     * 扫子包的话，将来有人把工具放到别处，这条断言就<b>看不见</b>它，
     * 测试照样绿 —— 强制点自己有了盲区，比没有强制点更糟（会让人误以为被保护着）。
     */
    private static final String BASE_PACKAGE = "cn.enlev.ainb.module.agent";

    /** 抽取器所在的包 */
    private static final String MANIFEST_PACKAGE = BASE_PACKAGE + ".framework.agent.core.manifest";

    /**
     * 真工具 = {@code @AgentTool} + {@code @Component}
     *
     * <p>🔴 加上 {@code @Component} 这个条件不是为了挑刺，而是为了把<b>测试桩</b>排除掉：
     * 本包的 {@code WorkflowChangeManifestBuilderTest} 里有六个只带 {@code @AgentTool}
     * 的桩类（用来喂 builder 的名字与风险等级），它们不是 Spring bean、永远不会被执行，
     * 因此也永远不可能产生变更。而扫描的 classpath 里含着 {@code target/test-classes}，
     * 不加这个条件就会把桩当成真工具，测试的结论取决于「桩的风险等级抄得准不准」。
     * 已核实：{@code tool/impl} 下 30 个真工具<b>全部</b>带 {@code @Component}，桩<b>一个都没有</b>。
     */
    private static Map<String, RiskLevel> realToolRisks() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(AgentTool.class));
        Map<String, RiskLevel> risks = new LinkedHashMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> clazz = Class.forName(bd.getBeanClassName());
            if (!clazz.isAnnotationPresent(Component.class)) {
                continue;
            }
            AgentTool annotation = clazz.getAnnotation(AgentTool.class);
            risks.put(annotation.name(), annotation.risk());
        }
        return risks;
    }

    /**
     * 真抽取器：工具键 → 实现类简名
     *
     * <p>⚠️ 跳过嵌套类：{@code WorkflowChangeManifestBuilderTest} 在<b>同一个包</b>里
     * 定义了四个私有桩抽取器（{@code StubExtractor} / {@code NullExtractor} /
     * {@code NoKindExtractor} / {@code LiarExtractor}），它们不该参与覆盖率统计 ——
     * 它们的 {@code toolId()} 是构造参数、可以是任意字符串。
     * 11 个真抽取器全是顶层类，故按「有没有外围类」区分即可，不必去读源码目录。
     */
    private static Map<String, String> realExtractors() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ToolChangeExtractor.class));
        Map<String, String> extractors = new LinkedHashMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(MANIFEST_PACKAGE)) {
            Class<?> clazz = Class.forName(bd.getBeanClassName());
            if (clazz.getEnclosingClass() != null) {
                continue;
            }
            ToolChangeExtractor extractor =
                    (ToolChangeExtractor) clazz.getDeclaredConstructor().newInstance();
            extractors.put(extractor.toolId(), clazz.getSimpleName());
        }
        return extractors;
    }

    // ==================== ① 正向：写工具必须有专用抽取器 ====================

    @Test
    @DisplayName("🔴 每个 risk != READ_ONLY 的工具都有专用抽取器（否则清单只能说「调用工具」，说不出改的是谁）")
    void everyWriteToolHasADedicatedExtractor() throws Exception {
        Map<String, RiskLevel> tools = realToolRisks();
        Set<String> covered = realExtractors().keySet();

        Set<String> writeTools = new TreeSet<>();
        tools.forEach((key, risk) -> {
            if (risk != RiskLevel.READ_ONLY) {
                writeTools.add(key);
            }
        });

        // 先确认扫描本身是有效的：扫出 0 个工具时后面所有断言都会「假绿」
        assertFalse(tools.isEmpty(), "没扫到任何 @AgentTool 工具，扫描口径坏了");
        assertFalse(writeTools.isEmpty(), "没扫到任何写工具，扫描口径坏了");

        List<String> missing = new ArrayList<>(writeTools);
        missing.removeAll(covered);
        assertTrue(missing.isEmpty(),
                "这些写工具没有变更抽取器，它们在清单里只会显示成「调用工具」，"
                        + "审批人看不出改的是哪张表/哪张表单/哪条记录：" + missing
                        + "。照着 " + MANIFEST_PACKAGE + " 下任一 *ChangeExtractor 加一个即可");
    }

    @Test
    @DisplayName("只读工具不必有抽取器（它们根本不进清单，见 builder 的 READ_ONLY 短路）")
    void readOnlyToolsAreNotRequiredToHaveExtractors() throws Exception {
        Map<String, RiskLevel> tools = realToolRisks();
        Set<String> covered = realExtractors().keySet();

        List<String> unexpected = new ArrayList<>();
        tools.forEach((key, risk) -> {
            if (risk == RiskLevel.READ_ONLY && covered.contains(key)) {
                unexpected.add(key);
            }
        });
        // 只读工具配了抽取器不是错误，但一定是误解：builder 在 READ_ONLY 处就短路了，
        // 那段代码永远跑不到。留着会让人以为「清单里会显示查询」
        assertTrue(unexpected.isEmpty(),
                "这些只读工具配了抽取器，但 builder 对 READ_ONLY 直接跳过、永不产出变更项，"
                        + "抽取器是死代码：" + unexpected);
    }

    // ==================== ② 反向：抽取器必须指向真工具 ====================

    @Test
    @DisplayName("🔴 每个抽取器的 toolId() 都对应一个真实存在的工具键（否则它是死代码，对应工具悄悄退回兜底）")
    void everyExtractorPointsAtARealTool() throws Exception {
        Set<String> tools = realToolRisks().keySet();
        Map<String, String> extractors = realExtractors();

        List<String> orphans = new ArrayList<>();
        extractors.forEach((toolId, className) -> {
            if (!tools.contains(toolId)) {
                orphans.add(className + " → " + toolId);
            }
        });
        assertFalse(extractors.isEmpty(), "没扫到任何抽取器，扫描口径坏了");
        assertTrue(orphans.isEmpty(),
                "这些抽取器的 toolId() 在 @AgentTool 里找不到对应工具，它们永远不会被分派到："
                        + orphans + "。常见原因是工具改了名而抽取器没跟着改");
    }

    @Test
    @DisplayName("🔴 一个工具键只能被一个抽取器认领（注册表对此只 warn，测试这里必须红）")
    void extractorsClaimDistinctTools() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ToolChangeExtractor.class));

        Map<String, List<String>> byToolId = new LinkedHashMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(MANIFEST_PACKAGE)) {
            Class<?> clazz = Class.forName(bd.getBeanClassName());
            if (clazz.getEnclosingClass() != null) {
                continue;
            }
            ToolChangeExtractor extractor =
                    (ToolChangeExtractor) clazz.getDeclaredConstructor().newInstance();
            byToolId.computeIfAbsent(extractor.toolId(), k -> new ArrayList<>())
                    .add(clazz.getSimpleName());
        }

        List<String> duplicated = new ArrayList<>();
        byToolId.forEach((toolId, classes) -> {
            if (classes.size() > 1) {
                duplicated.add(toolId + " ← " + classes);
            }
        });
        // ToolChangeExtractorRegistry#setExtractors 对重复只打 warn（后注册的覆盖前面的），
        // 表现是「同一个工具的变更，解读口径悄悄换了一套」，从界面上查不出原因。
        // warn 会被日志刷过去，故在这里硬性拦下
        assertTrue(duplicated.isEmpty(), "这些工具键被多个抽取器认领：" + duplicated);
    }

    // ==================== ③ 工具键与 NAME 常量同源 ====================

    @Test
    @DisplayName("🔴 工具声明了 NAME 常量时，@AgentTool(name=...) 必须与它逐字相等")
    void annotationNameMatchesNameConstant() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(AgentTool.class));

        int checked = 0;
        List<String> drifted = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> clazz = Class.forName(bd.getBeanClassName());
            if (!clazz.isAnnotationPresent(Component.class)) {
                continue;
            }
            Field field;
            try {
                field = clazz.getDeclaredField("NAME");
            } catch (NoSuchFieldException ex) {
                continue;
            }
            if (field.getType() != String.class
                    || !Modifier.isStatic(field.getModifiers())
                    || !Modifier.isFinal(field.getModifiers())) {
                continue;
            }
            checked++;
            String constant = (String) field.get(null);
            String annotated = clazz.getAnnotation(AgentTool.class).name();
            if (!annotated.equals(constant)) {
                drifted.add(clazz.getSimpleName() + "：注解写的是 " + annotated
                        + "，NAME 常量是 " + constant);
            }
        }

        // 抽取器一律引用 XxxTool.NAME，而注册表按注解的 name 建索引。
        // 两处各写一遍字面量时，改了其中一处不会报错 —— 只会让清单静默退回兜底解读
        assertTrue(checked >= 11, "只核到 " + checked + " 个带 NAME 常量的工具，扫描口径坏了");
        assertTrue(drifted.isEmpty(), "工具键与 NAME 常量不一致：" + drifted);
    }

    // ==================== ④ 每种变更类型都有人产出 ====================

    /**
     * 🔴 除兜底外，每个 {@link ChangeKind} 都至少被一个抽取器产出
     *
     * <p>加了一个 kind 却没有抽取器会用它，表现是 {@link ChangeKind#getHeadlineTemplate()}
     * 里那句量词（如「登记 %d 张已有表」）永远不会出现 —— 白加一个常量，
     * 而且从界面上完全看不出来（没人会注意到「清单从来没提过登记已有表」）。
     *
     * <p>做法是造一份「每个写工具各一个节点、且 {@code args} 全空」的 DSL 走一遍真 builder：
     * 空参数是最退化的输入，能同时验到「kind 都有人产出」与「抽取器不会 NPE」两件事。
     */
    @Test
    @DisplayName("🔴 除兜底外每个 ChangeKind 都有抽取器产出；且空参数下全链路不抛")
    void everyChangeKindIsProducible() throws Exception {
        ChangeManifest manifest = buildManifestForAllWriteToolsWithEmptyArgs();

        Set<ChangeKind> produced = EnumSet.noneOf(ChangeKind.class);
        List<String> fellBack = new ArrayList<>();
        for (ChangeItem item : manifest.getChanges()) {
            produced.add(item.getKind());
            if (item.getKind() == ChangeKind.GENERIC_TOOL) {
                fellBack.add(item.getTool());
            }
        }

        assertTrue(fellBack.isEmpty(),
                "这些工具明明有专用抽取器，却落到了兜底（说明抽取器没被分派到）：" + fellBack);

        Set<ChangeKind> dead = EnumSet.allOf(ChangeKind.class);
        dead.removeAll(produced);
        dead.remove(ChangeKind.GENERIC_TOOL);
        assertTrue(dead.isEmpty(),
                "这些 ChangeKind 没有任何抽取器会产出，它们的 headline 量词永远不会出现，"
                        + "等于白加一个常量：" + dead);
    }

    @Test
    @DisplayName("11 个抽取器在空参数节点上都给出了 kind 与非空 summary（summary 为空的话，确认卡上就是一行空白）")
    void extractorsSurviveEmptyArgs() throws Exception {
        assertEquals(11, realExtractors().size(),
                "抽取器个数与预期不符。若确实新增/删除了抽取器，请同步改这条断言与 S2 的方案回写");

        ChangeManifest manifest = buildManifestForAllWriteToolsWithEmptyArgs();
        List<String> problems = new ArrayList<>();
        for (ChangeItem item : manifest.getChanges()) {
            if (item.getKind() == null) {
                problems.add(item.getTool() + "：kind 为 null");
            }
            if (item.getSummary() == null || item.getSummary().isBlank()) {
                // builder 只对兜底产出回落 summary（那句话要工具展示名）；
                // 专用抽取器留空的话，聊天确认卡与审批详情上就是一行空白
                problems.add(item.getTool() + "：summary 为空");
            }
            if (item.getToolDisplayName() == null || item.getToolDisplayName().isBlank()) {
                problems.add(item.getTool() + "：工具展示名没被 builder 填上");
            }
        }
        assertTrue(problems.isEmpty(), "空参数下抽取器产出不合格：" + problems);
        assertEquals(0, manifest.getStats().getGenericTools(),
                "全部 11 项都该由专用抽取器解读，兜底计数应为 0");
    }

    /**
     * 造一份「每个写工具一个节点、args 全空」的 DSL，用<b>真</b>注册表与<b>真</b>抽取器跑一遍 builder
     *
     * <p>节点按工具键字典序串成一条链。这么排是为了让 DSL 由扫描结果自动生成 ——
     * 手抄一份节点清单就是第二份口径，新增工具时没人会想起回来补，
     * 于是这条测试会绿着放过缺口，正是它要防的事。
     */
    private ChangeManifest buildManifestForAllWriteToolsWithEmptyArgs() throws Exception {
        Map<String, RiskLevel> tools = realToolRisks();
        Set<String> writeTools = new TreeSet<>();
        tools.forEach((key, risk) -> {
            if (risk != RiskLevel.READ_ONLY) {
                writeTools.add(key);
            }
        });

        StringBuilder nodes = new StringBuilder();
        StringBuilder edges = new StringBuilder();
        String previous = "START";
        int index = 0;
        for (String key : writeTools) {
            String id = "n" + index++;
            if (nodes.length() > 0) {
                nodes.append(",");
                edges.append(",");
            }
            // config 里只有 toolId、没有 args：这是最退化的输入
            nodes.append("{\"id\":\"").append(id).append("\",\"type\":\"tool\",\"name\":\"")
                    .append(id).append("\",\"config\":{\"toolId\":\"").append(key).append("\"}}");
            edges.append("{\"from\":\"").append(previous).append("\",\"to\":\"").append(id).append("\"}");
            previous = id;
        }
        edges.append(",{\"from\":\"").append(previous).append("\",\"to\":\"END\"}");

        String dsl = "{\"dslVersion\":\"1.0\",\"id\":\"wf_coverage\",\"name\":\"覆盖率探针\","
                + "\"trigger\":{\"type\":\"trigger.chat\"},"
                + "\"variables\":[{\"key\":\"input\",\"type\":\"object\"}],"
                + "\"nodes\":[" + nodes + "],\"edges\":[" + edges + "]}";
        WorkflowDefDTO def = new ObjectMapper().readValue(dsl, WorkflowDefDTO.class);

        return builderWithRealToolsAndExtractors(tools.keySet()).build(def);
    }

    /**
     * 装配一个「全真」的 builder：真工具类（取展示名与风险等级的生效值）+ 真抽取器
     *
     * <p>{@code formDesignApi} 刻意留 null：{@link ManifestLabels} 的设计就是
     * 拿不到 api 时退化成「机器码原样显示」而不是抛（清单绝不能成为新的失败点）。
     * 这条路径在这里被顺带覆盖。
     */
    private WorkflowChangeManifestBuilder builderWithRealToolsAndExtractors(Set<String> toolKeys)
            throws Exception {
        AgentToolRegistry toolRegistry = new AgentToolRegistry();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(AgentTool.class));
        for (BeanDefinition bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> clazz = Class.forName(bd.getBeanClassName());
            if (clazz.isAnnotationPresent(Component.class)
                    && toolKeys.contains(clazz.getAnnotation(AgentTool.class).name())) {
                toolRegistry.register(clazz);
            }
        }

        List<ToolChangeExtractor> extractors = new ArrayList<>();
        for (String className : realExtractors().values()) {
            Class<?> clazz = Class.forName(MANIFEST_PACKAGE + "." + className);
            extractors.add((ToolChangeExtractor) clazz.getDeclaredConstructor().newInstance());
        }
        ToolChangeExtractorRegistry extractorRegistry = new ToolChangeExtractorRegistry();
        extractorRegistry.setExtractors(extractors);

        AgentToolDefService toolDefService = mock(AgentToolDefService.class);
        when(toolDefService.getEnabledToolDefList()).thenReturn(List.of());

        WorkflowChangeManifestBuilder builder = new WorkflowChangeManifestBuilder();
        ReflectionTestUtils.setField(builder, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(builder, "extractorRegistry", extractorRegistry);
        ReflectionTestUtils.setField(builder, "genericExtractor", new GenericToolChangeExtractor());
        ReflectionTestUtils.setField(builder, "toolDefService", toolDefService);
        // 干跑一律 mock 掉：它要查库，而本测试的目的是「抽取器有没有被漏配」，
        // 不该因为 infra 接口没打桩而变成一片 NOT_PROBED
        ReflectionTestUtils.setField(builder, "dryRunner", mock(ChangeManifestDryRunner.class));
        return builder;
    }

}
