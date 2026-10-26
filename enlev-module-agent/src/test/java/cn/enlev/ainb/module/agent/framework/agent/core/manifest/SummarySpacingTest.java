package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelCreateTool;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelParamResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static cn.enlev.ainb.module.agent.framework.agent.core.manifest.ManifestTestFixtures.context;
import static cn.enlev.ainb.module.agent.framework.agent.core.manifest.ManifestTestFixtures.toolNode;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * summary 留白规则的<b>强制点</b>：ASCII 两侧留空格，全角「」（）前不留
 *
 * <h3>规则本身</h3>
 * <ul>
 *   <li>表名、编号、字段名、数字这类 ASCII token 两侧各留一个空格：
 *       {@code 修改表 biz_employee 的结构：新增 2 列}</li>
 *   <li>全角括号与书名号前<b>不</b>留空格：{@code 新建数据表（表名未填）}、
 *       {@code 发布表单 #1024，挂到「人事管理」下}</li>
 * </ul>
 * 混着写的话，同一份清单里既有 {@code 修改表 biz_employee 的} 又有 {@code 新建数据表 （表名未填）}，
 * 读起来像两套系统拼起来的。而后者更糟：那个多出来的空格让整句话看着像
 * <b>模板没填进去</b>，审批人会以为清单坏了 —— 而这句话出现的时机，
 * 恰恰是模型把参数写错、审批人正在逐字读的时候。
 *
 * <h3>🔴 为什么规则要有测试，而不是只写在 javadoc 里</h3>
 * 这条规则原先只写在 {@link FormDataArgs#codeSlot} 与
 * {@code FormSaveDraftChangeExtractor#summary} 的注释里，结果是<b>说清楚了、没人执行</b>：
 * S2-c2 提交之后回头一 grep，另外三个抽取器
 * （{@code DataModelCreateChangeExtractor} / {@code DataModelUpdateChangeExtractor} /
 * {@code FormUpdateChangeExtractor}）都还留着这个洞，而它们的单测是<b>照着错输出</b>逐字断言的，
 * 所以全绿。这正是本模块反复撞上的那类缺陷「约束无强制点」：
 * 规矩写在文档里，编译器不管，测试也不管，只有用户能感觉到。
 * 故把规则搬到这里 —— 约束要落在会红的地方。
 *
 * <h3>⚠️ 两个扫描域，各盯一类产出品</h3>
 * <ul>
 *   <li><b>③ 抽取器域</b>：11 个 {@code ToolChangeExtractor} 在<b>最退化入参</b>下产出的 summary。
 *       它不覆盖占位符分支（{@code 发布表单（编号运行期才确定：{{$.save_draft.id}}）}）——
 *       那些句子由各抽取器自己的逐字断言盯着。</li>
 *   <li><b>④ 产出品域</b>（E9 补）：<b>整份 {@link ChangeManifest}</b> 里的每一个中文字符串，
 *       包括 {@code degraded()} 那五段、builder 拼的 headline / riskNote、以及
 *       {@code unresolved[]} / {@code waits[]} / {@code approvals[]} 的原因文案。</li>
 * </ul>
 * 两层是互补的：逐字断言盯<b>某一个</b>产家的措辞，本测试盯一条<b>跨全部产家</b>的横向规则。
 * 横向规则没有单独的家，所以每加一个产家它就会漂一点，直到没人记得有这么条规矩 ——
 * ③ 原先只有抽取器这一域，正是因为「加抽取器」比「加 builder 分支」频繁得多，
 * 于是洞先在那儿被发现；而 builder 那侧的分支一直<b>没人扫过</b>（E9）。
 *
 * <h3>🔴 为什么 ④ 是「跑一遍产物再扫」，而不是「扫源码里的中文字面量」</h3>
 * 静态扫字面量看着更省事、覆盖更全，但它对<b>这一类缺陷结构上是瞎的</b>。2026-10-06 实测：
 * 本包 src/main 26 个文件共 551 个字面量、其中 308 个含中文（散在 21 个文件里），
 * 而 {@code  （} / {@code  「} 在<b>字面量内部</b>
 * 出现 <b>0</b> 次（129 处命中全在注释里）。原因是洞<b>从来不在单个字面量里，而在接缝上</b>：
 * <pre>
 *   "…也不是当前租户启用中的自定义工具"  +  "（相近的有 " + … + "）"
 * </pre>
 * 两段各自都干净，只有<b>拼起来</b>才看得出多空了一格 —— 与 {@code DataModelCreateChangeExtractor}
 * 当初那个洞（{@code "新建数据表"} + {@code "（表名未填）"}）同型。故静态扫描会起手就绿、
 * 且永远绿，是一份<b>恒绿负控</b>：比没有负控更坏，因为它会让人以为这里有人守着。
 *
 * <h3>负控记录（2026-10-06，三发都实测过）</h3>
 * <ul>
 *   <li><b>探针 A + C</b>：把 {@code unknownToolReason} 的 hint 改成 {@code " （相近的有 "}、
 *       并把 {@code ChangeManifest#degraded} 的 riskNote 改成 {@code 算出 「将会做什么」}。
 *       ⟹ {@link #noManifestProductCarriesATemplateHole} 变红，报出<b>三条</b>并带准确路径：
 *       {@code degraded 的 manifest.riskNote}（顶层字段）、
 *       {@code degraded（原因为空串） 的 manifest.riskNote}、
 *       {@code builder 的 manifest.unresolved[1].reason}（嵌套列表元素）。
 *       两种形状都扫到了，说明反射遍历没有只走到其中一半。</li>
 *   <li><b>🔴 同一发上 {@code ChangeManifestDegradedTest} 全绿（5/5）</b>。这不是巧合：
 *       它对 riskNote 只断言 {@code contains("不代表本次没有影响")}，多一个空格它看不见。
 *       E9 说的「兜底的文案没人校」由此得到实证 —— 逐字断言盯的是<b>措辞</b>，
 *       而留白是一条<b>横向</b>规则，横向规则必须有自己的家。</li>
 *   <li><b>探针 B</b>：把 {@code DEGENERATE_DSL} 里 {@code n_typo} 那个节点删掉（模拟「有人精简夹具」）。
 *       ⟹ 上面那条断言<b>照样绿</b>（洞确实没了），而
 *       {@link #productDomainActuallyReachesEveryBranchItClaims} 变红并报出
 *       「builder：扫到的 12 段中文里没有「相近的有」—— 那条分支没走到，规则对它是空跑」。
 *       这正是 ④ 唯一的静默失效方式，故它必须有自己的哨兵。</li>
 * </ul>
 * 三发均已机械还原（{@code git diff --numstat} 对 src/main 为 <b>0</b>，行尾仍是 LF），基线复绿 7/7。
 *
 * <h3>为什么用扫描而不是抄一份抽取器名单</h3>
 * 与 {@code ToolChangeExtractorCoverageTest} 同一个理由：抄名单就是第二份口径，
 * 新增抽取器时没人会想起回来补，于是测试继续绿着、洞继续存在。
 *
 * @author 走进新时代
 */
class SummarySpacingTest {

    private static final String MANIFEST_PACKAGE =
            "cn.enlev.ainb.module.agent.framework.agent.core.manifest";

    /** 全角左括号前多了一个空格 */
    private static final String SPACE_BEFORE_PAREN = " （";

    /** 全角左书名号前多了一个空格 */
    private static final String SPACE_BEFORE_QUOTE = " 「";

    /**
     * 「身份字段没填」那条分支的措辞标记
     *
     * <p>已核实 11 个抽取器在该分支上用的词：{@code 表名未填 / 编号未填 / 名称未填 / 编码未填}
     * 都含「未填」，只有 {@code DataModelImportChangeExtractor} 说的是「未认出表名」。
     * 用途与耦合代价见 {@link #degenerateShapesActuallyReachTheUnfilledBranch}。
     */
    private static final List<String> UNFILLED_MARKERS = List.of("未填", "未认出");

    /**
     * 光喂空参数走不到「X 未填」那条分支的抽取器：工具键 → 能走到那条分支的 {@code args}
     *
     * <p>🔴 这张表的存在是被一次变异探针逼出来的。最初版本只喂两种退化入参，
     * 把 {@code DataModelCreateChangeExtractor} 的打底串改回带尾随空格的错写法之后，
     * 本测试<b>照样绿</b> —— 因为 {@code model} 整个缺失时它早退到另一句话
     * （「没有给出建表定义…」），压根没走到 {@code （表名未填）} 那个分支。
     * 也就是说强制点自己有盲区，而盲区正好盖住了唯一一个真出过问题的抽取器。
     *
     * <p>⚠️ 表里的行必须<b>合格且必要</b>，{@link #everyOverrideRowIsStillNeeded} 两件事都查：
     * 补喂之后产出必须变（没变说明这行是多余的），且必须说出「未填 / 未认出」
     * （否则它指向的是别的分支）。多余的行会让人误以为这个抽取器需要特殊照顾，从而照抄。
     */
    private static final Map<String, Map<String, Object>> ARGS_TO_REACH_UNFILLED_BRANCH = Map.of(
            DataModelCreateTool.NAME, Map.of(
                    // 有 model、有列，就是没写 tableName：这才是「表名未填」，
                    // 与「整份建表定义都没给」是两种不同的退化形态，措辞也刻意不同
                    DataModelParamResolver.PARAM_MODEL,
                    Map.of("columns", List.of(Map.of("columnName", "c1")))));

    // ==================== ① 探测器自己得能看见洞 ====================

    @Test
    @DisplayName("🔴 探测器自检：喂它一句带洞的、一句干净的，结论必须相反（否则下面所有断言都是假绿）")
    void detectorIsNotVacuous() {
        // 这两句是本测试存在的理由：第一句是三个抽取器曾经真实产出过的输出
        assertTrue(violationsOf("新建数据表 （表名未填），共 1 列").contains(SPACE_BEFORE_PAREN));
        assertTrue(violationsOf("修改表 （表名未填） 的结构：").contains(SPACE_BEFORE_PAREN));
        assertTrue(violationsOf("发布表单（编号未填），挂到「人事管理」下").isEmpty());
        assertTrue(violationsOf("修改表 biz_employee 的结构：新增 2 列").isEmpty(),
                "ASCII 两侧的空格是规则要求的，不能被当成违规");
        assertTrue(violationsOf("发布表单 #1024，挂到「人事管理」下").isEmpty());
    }

    // ==================== ② 扫描口径本身有效 ====================

    @Test
    @DisplayName("扫到了预期数量的抽取器（扫出 0 个时后面的断言会「假绿」）")
    void scanFindsTheExtractors() throws Exception {
        Map<String, ToolChangeExtractor> extractors = realExtractors();
        assertFalse(extractors.isEmpty(), "没扫到任何抽取器，扫描口径坏了");
        // 与 ToolChangeExtractorCoverageTest#extractorsSurviveEmptyArgs 同一份口径：
        // 抽取器个数变了，两处一起改，顺手提醒回写 S2 的方案
        assertTrue(extractors.size() >= 11,
                "只扫到 " + extractors.size() + " 个抽取器，少于已知的 11 个，扫描口径坏了");
    }

    // ==================== ③ 真规则：不许产出带洞的句子 ====================

    @Test
    @DisplayName("🔴 每个抽取器在退化入参下产出的 summary 都不带「空格+全角括号」的模板洞")
    void noExtractorEmitsATemplateHole() throws Exception {
        List<String> problems = new ArrayList<>();
        realExtractors().forEach((toolId, extractor) ->
                degenerateSummaries(toolId, extractor).forEach((shape, summary) ->
                        violationsOf(summary).forEach(violation ->
                                problems.add(toolId + "（" + shape + "）→「" + summary + "」含 "
                                        + describe(violation)))));

        assertTrue(problems.isEmpty(),
                "这些 summary 在全角括号前多了一个空格，读起来像模板没填进去。"
                        + "规则：ASCII 两侧留空格，全角「」（）前不留；"
                        + "改法是把空格挪进「有值」那条分支（照 FormDataArgs#codeSlot 写），"
                        + "而不是留在打底串里：" + problems);
    }

    /**
     * 🔴 每个抽取器在退化入参下都必须走到「身份字段没填」那条分支
     *
     * <p>这是给 {@link #ARGS_TO_REACH_UNFILLED_BRANCH} 兜底的哨兵，判据是那句
     * <b>措辞</b>（{@code 未填 / 未认出}），不是「有没有全角括号」。
     * 后者看着更通用，其实什么都拦不住：{@code datamodel_create} 早退时说的是
     * 「没有给出建表定义<b>（args.model 缺失或不是对象）</b>」，一样带全角括号 ——
     * 用「有括号」当判据的话，本测试的第一版（正是它漏掉了那个洞）也会绿。
     *
     * <p>⚠️ 这条断言与措辞耦合，是<b>故意</b>的：措辞一改它就红，逼改的人回头看一眼
     * 「退化入参到底走没走到那条分支」。改完措辞请把新词补进 {@link #UNFILLED_MARKERS}，
     * 不要为了变绿把这条测试删掉 —— 删了之后强制点就有了盲区，而盲区不会自己报告。
     */
    @Test
    @DisplayName("🔴 退化入参确实走到了「X 未填」那条分支，否则留白规则对它是空跑")
    void degenerateShapesActuallyReachTheUnfilledBranch() throws Exception {
        List<String> unreached = new ArrayList<>();
        realExtractors().forEach((toolId, extractor) -> {
            Map<String, String> summaries = degenerateSummaries(toolId, extractor);
            if (summaries.values().stream().noneMatch(SummarySpacingTest::mentionsUnfilled)) {
                unreached.add(toolId + " → " + summaries);
            }
        });

        assertTrue(unreached.isEmpty(),
                "这些抽取器在喂进去的退化入参下没有说出「未填 / 未认出」，"
                        + "说明没走到身份字段缺失那条分支 —— 而留白规则的洞只在一条分支上出现过，"
                        + "那就是这条。请在 ARGS_TO_REACH_UNFILLED_BRANCH 里补一行能走到它的 args"
                        + "（本测试的第一版就栽在这个盲区上）：" + unreached);
    }

    /**
     * 这个抽取器在全部退化入参下产出的 summary：入参形态 → 那句话
     *
     * <p>「没有 {@code args} 键」与「{@code args} 是空对象」在 {@code ManifestArgs.of} 之后
     * 是同一个空 Map，但节点形状不同：有的抽取器会先看「{@code args} 这个键在不在」
     * 再说「没给出定义」，两条分支的措辞不一样，都值得过一遍规则。
     *
     * <p>⚠️ 用 {@code LinkedHashMap} 而不是 {@code Map.of}：形态名要按喂进去的顺序出现在
     * 失败信息里，而 {@code Map.of} 的迭代顺序<b>每个 JVM 实例都不一样</b>
     * （{@code ImmutableCollections.SALT} 在启动时随机），会让同一次失败每次打印出不同的顺序。
     */
    private Map<String, String> degenerateSummaries(String toolId, ToolChangeExtractor extractor) {
        Map<String, String> summaries = new LinkedHashMap<>();
        summaries.put("无 args 键", summaryOf(extractor, toolNode("probe", toolId, (String) null)));
        summaries.put("args 为空对象",
                summaryOf(extractor, toolNode("probe", toolId, new LinkedHashMap<String, Object>())));
        Map<String, Object> override = ARGS_TO_REACH_UNFILLED_BRANCH.get(toolId);
        if (override != null) {
            summaries.put("补喂入参", summaryOf(extractor, toolNode("probe", toolId, override)));
        }
        return summaries;
    }

    private static boolean mentionsUnfilled(String summary) {
        return UNFILLED_MARKERS.stream().anyMatch(summary::contains);
    }

    @Test
    @DisplayName("补喂表里没有多余的行（多余的行会让人以为这个抽取器需要特殊照顾，从而照抄）")
    void everyOverrideRowIsStillNeeded() throws Exception {
        Map<String, ToolChangeExtractor> extractors = realExtractors();
        List<String> unknown = new ArrayList<>();
        List<String> useless = new ArrayList<>();
        ARGS_TO_REACH_UNFILLED_BRANCH.forEach((toolId, args) -> {
            ToolChangeExtractor extractor = extractors.get(toolId);
            if (extractor == null) {
                unknown.add(toolId);
                return;
            }
            String empty = summaryOf(extractor,
                    toolNode("probe", toolId, new LinkedHashMap<String, Object>()));
            String overridden = summaryOf(extractor, toolNode("probe", toolId, args));
            if (empty.equals(overridden)) {
                useless.add(toolId + "：补喂之后产出没变，空参数已经能走到那条分支");
            }
            // 补喂的意义就是「走到那条分支」，所以补喂出来的那句必须真的提到未填。
            // 只比「变了没」的话，一行指向别的分支的 args 也能蒙混过关
            if (!mentionsUnfilled(overridden)) {
                useless.add(toolId + "：补喂之后仍然没说「未填 / 未认出」，这行 args 没走到目标分支");
            }
        });

        assertTrue(unknown.isEmpty(), "补喂表里有已经不存在的工具键（工具改名了？）：" + unknown);
        assertTrue(useless.isEmpty(), "补喂表里有不合格的行：" + useless);
    }

    // ==================== ④ 产出品域：整份清单的中文都过一遍规则（E9） ====================

    /**
     * 中文字符（含扩展前的基本区）：只用来判「这段文案是不是给人看的中文」
     *
     * <p>{@link #violationsOf} 找的两个片段本身就含全角字符，故纯 ASCII 串天然不会命中；
     * 这个判据只用于<b>收集</b>（决定哪些串值得进失败信息），不用于判定。
     */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    /** 递归深度上限：最深的一条是 manifest → changes[] → dryRun → …，8 层用不满；撞上说明结构变了 */
    private static final int MAX_DEPTH = 8;

    /**
     * 一份「什么都认不出」的 DSL：六个节点各踩一条 builder 自己拼中文的分支
     *
     * <p>🔴 这份 DSL 的价值在于它<b>一个工具都跑不通</b>：认不出的节点全落进 {@code unresolved[]}，
     * 于是 builder 那四句自己写的原因文案、{@code buildRiskNote} 的「另有 N 处」拼接、
     * 以及 {@code buildHeadline} 的空分支<b>一次全被走到</b> —— 不需要任何真工具、真抽取器、真 infra。
     *
     * <p>⚠️ 节点的 {@code name} 是<b>作者原文</b>，会被 {@code blankToDefault} 原样搬进
     * {@code nodeName}。留白规则管的是<b>平台自己拼的文案</b>，不管作者写了什么 ——
     * 作者爱怎么写是他自己的表单名字。故这里刻意全用不带全角括号的中文名：
     * 一旦夹具里出现「某节点 （甲）」，本测试就会<b>报一个不是平台缺陷的洞</b>，
     * 而那种假阳性最坏的结果是有人为了变绿把整条断言删掉。
     */
    private static final String DEGENERATE_DSL = """
            {
              "dslVersion": "1.0",
              "id": "wf_spacing_probe",
              "name": "留白规则探针流程",
              "trigger": { "type": "trigger.chat" },
              "variables": [ { "key": "input", "type": "object" } ],
              "nodes": [
                { "id": "n_missing",   "type": "tool",         "name": "没填工具键",   "config": {} },
                { "id": "n_typo",      "type": "tool",         "name": "工具键打错",   "config": { "toolId": "datamodel_creat" } },
                { "id": "n_sub_blank", "type": "sub_workflow", "name": "子流程没填",   "config": {} },
                { "id": "n_sub_code",  "type": "sub_workflow", "name": "子流程有编号", "config": { "workflowId": "wf_child" } },
                { "id": "n_hitl",      "type": "hitl.manual",  "name": "人工确认",     "config": {} },
                { "id": "n_wait",      "type": "wait",         "name": "等外部事件",   "config": { "waitType": "event" } }
              ],
              "edges": []
            }
            """;

    @Test
    @DisplayName("🔴 整份清单产出品里的每一段中文都不带模板洞（degraded / builder / ChangeKind 全在内）")
    void noManifestProductCarriesATemplateHole() throws Exception {
        List<String> problems = new ArrayList<>();
        products().forEach((product, strings) ->
                strings.forEach((path, text) ->
                        violationsOf(text).forEach(violation ->
                                problems.add(product + " 的 " + path + " →「" + text + "」含 "
                                        + describe(violation)))));

        assertTrue(problems.isEmpty(),
                "这些文案在全角括号前多了一个空格，读起来像模板没填进去。"
                        + "规则：ASCII 两侧留空格，全角「」（）前不留。"
                        + "🔴 改法是把空格挪进「有值」那条分支（照 FormDataArgs#codeSlot 写），"
                        + "别留在打底串里 —— 洞从来不在单个字面量里，而在两个字面量的<b>接缝</b>上："
                        + "「自定义工具」+「（相近的有 …）」各自都干净，拼起来才看得出多空了一格。"
                        + problems);
    }

    /**
     * 🔴 反向自检：④ 这一域必须<b>真的扫到了</b>它声称覆盖的那些分支
     *
     * <p>这一条是 ④ 的命根子，理由与 {@link #degenerateShapesActuallyReachTheUnfilledBranch} 完全同源：
     * 产出品域是靠「喂一份 DSL、扫它的产物」实现的，于是它有一种<b>静默失效</b>方式 ——
     * 分支没走到，产物里就没有那段文案，扫描自然一个洞也找不到，测试<b>更绿</b>。
     * 具体到本测试，四种失效都真实可能：
     * <ul>
     *   <li>{@code datamodel_creat} 不再被 {@code ToolKeySuggester} 认成相近 ⟹ {@code hint} 是空串
     *       ⟹「自定义工具」+「（相近的有 …）」那个<b>最典型的接缝压根没被拼出来</b></li>
     *   <li>builder 改了节点分派（比如 {@code sub_workflow} 换了常量）⟹ 那两句子流程文案消失</li>
     *   <li>{@code unresolved[]} 空了 ⟹ {@code buildRiskNote} 不加「另有 N 处」那段</li>
     *   <li>{@code degraded()} 改了措辞 ⟹ 五段里少一段</li>
     * </ul>
     * 四种都<b>不会</b>让上面那条断言变红，只会让它扫得更少。故这里逐条钉住「那段文案必须在」。
     *
     * <p>⚠️ 与措辞耦合是<b>故意</b>的：措辞一改这里就红，逼改的人回来确认新措辞也进了扫描域。
     * 请把新词补进下面的标记表，不要为了变绿把这条测试删掉。
     */
    @Test
    @DisplayName("🔴 反向自检：产出品域确实走到了每一条它声称覆盖的分支（否则它只是扫得更少、照样绿）")
    void productDomainActuallyReachesEveryBranchItClaims() throws Exception {
        Map<String, Map<String, String>> products = products();

        // 标记 → 谁负责产出它。用 LinkedHashMap 让失败信息的顺序稳定（Map.of 的顺序每个 JVM 都不同）
        Map<String, List<String>> markers = new LinkedHashMap<>();
        markers.put("builder", List.of(
                "没有指定 toolId",       // TYPE_TOOL_MISSING 那句
                "相近的有",              // 🔴 unknownToolReason 的 hint —— 接缝最典型的一处
                "未填 workflowId",       // TYPE_SUB_WORKFLOW 的空分支
                "其内部的变更未展开",     // TYPE_SUB_WORKFLOW 的「「code」」分支
                "另有",                  // buildRiskNote 拼上去的那半句
                "本次不产生任何写操作")); // buildHeadline 的空分支
        markers.put("degraded", List.of(
                "生成失败", "风险未知", "不代表本次没有影响", "整条流程"));
        markers.put("ChangeKind", List.of(
                "新建数据表", "调用工具", "张数据表", "个工具"));

        List<String> missing = new ArrayList<>();
        markers.forEach((product, wanted) -> {
            Map<String, String> strings = products.get(product);
            if (strings == null) {
                missing.add(product + "：这个产出品压根没被生产出来（products() 的键改了？）");
                return;
            }
            String all = String.join("\n", strings.values());
            for (String marker : wanted) {
                if (!all.contains(marker)) {
                    missing.add(product + "：扫到的 " + strings.size() + " 段中文里没有「" + marker
                            + "」—— 那条分支没走到，规则对它是空跑");
                }
            }
        });

        assertTrue(missing.isEmpty(),
                "产出品域有盲区，而盲区不会自己报告。请修夹具（多半是 DEGENERATE_DSL 踩不到那条分支了），"
                        + "或在分支确实被删掉之后把对应标记一并删掉并说明理由：" + missing);

        // 还有一条更粗的兜底：每个产出品都得扫出中文来，否则「一个洞都没有」等于「一个字都没看」
        products.forEach((product, strings) ->
                assertFalse(strings.isEmpty(),
                        "产出品「" + product + "」一段中文都没扫出来 —— 反射遍历坏了，"
                                + "上面那条断言现在是恒绿的空测试"));
    }

    /**
     * 被扫的产出品：名字 → （字段路径 → 那段中文）
     *
     * <p>🔴 用 {@link LinkedHashMap} 而不是 {@code Map.of}，理由与 {@link #degenerateSummaries} 同：
     * 失败信息里的顺序必须稳定，而 {@code Map.of} 的迭代顺序每个 JVM 实例都不一样。
     */
    private Map<String, Map<String, String>> products() throws Exception {
        Map<String, Map<String, String>> products = new LinkedHashMap<>();

        // degraded()：五段中文。两种原因都喂一遍 —— 空串那一发专门盯
        // 「变更清单生成失败：」+ reason 这个接缝（reason 为空时冒号后面什么都没有）
        products.put("degraded", sweep(ChangeManifest.degraded("子流程 wf_child 展不开")));
        products.put("degraded（原因为空串）", sweep(ChangeManifest.degraded("")));

        WorkflowChangeManifestBuilder builder = realBuilder();
        products.put("builder", sweep(builder.build(parseDsl(DEGENERATE_DSL))));
        // def 为 null 是 build() 的一条独立早退分支，headline / riskNote 走的是另一套值。
        // ⚠️ 这里刻意<b>不</b>收 {@code new ChangeManifest()}：一份没填过的清单里
        // headline / riskNote 全是 null、四个列表全空，扫出来<b>零段中文</b>是它的正确形状。
        // 把它收进来就得给「非空」那条自检开例外，而一个永远扫不出东西的产出品
        // 既抓不到洞、也让自检的口径变模糊 —— 少一个产出品，不多一个例外。
        products.put("builder（DSL 为 null）", sweep(builder.build(null)));

        // ChangeKind 的 label 与 headline 只有在【真有变更项】时才会流进清单，
        // 而上面那份 DSL 刻意一个变更项都产不出来，故这两个模板要单独扫
        Map<String, String> kinds = new LinkedHashMap<>();
        for (ChangeKind kind : ChangeKind.values()) {
            kinds.put(kind.name() + ".label", kind.getLabel());
            kinds.put(kind.name() + ".headline", kind.headline(2));
        }
        products.put("ChangeKind", kinds);
        return products;
    }

    private WorkflowDefDTO parseDsl(String json) throws Exception {
        return new ObjectMapper().readValue(json, WorkflowDefDTO.class);
    }

    /**
     * 一个能真跑的 builder：真抽取器 + 真工具注册表，只有 infra 那两只依赖是 mock
     *
     * <p>🔴 {@code toolRegistry.register(Class)} <b>只读注解、不实例化</b>，故这里登记的是
     * {@code tool/impl/} 下那个<b>真</b>工具类，不是抄一份桩注解 —— 抄桩就等于第二份口径，
     * 真工具改了键名而桩没改时，{@code ToolKeySuggester} 的候选会跟着漂，
     * 而漂的结果恰好是「相近的有」那段 hint 消失，也就是 {@link #productDomainActuallyReachesEveryBranchItClaims}
     * 要抓的那种静默失效。
     *
     * <p>{@code formDesignApi} 传 null 是<b>被支持</b>的：{@code ManifestLabels} 的字段注释明写
     * 「可为 null：纯单测里不需要真去查 infra，此时所有查表都回落机器码」。
     * {@code dryRunner} 用 mock：它的 {@code enrich} 什么都不做 ⟹ 每项 {@code dryRun} 恒为 null，
     * 与本测试无关（干跑自己的三态由 {@code ChangeManifestDryRunnerTest} 覆盖）。
     */
    private WorkflowChangeManifestBuilder realBuilder() throws Exception {
        AgentToolRegistry toolRegistry = new AgentToolRegistry();
        toolRegistry.register(DataModelCreateTool.class);

        ToolChangeExtractorRegistry extractorRegistry = new ToolChangeExtractorRegistry();
        extractorRegistry.setExtractors(List.copyOf(realExtractors().values()));

        AgentToolDefService toolDefService = mock(AgentToolDefService.class);
        when(toolDefService.getEnabledToolDefList()).thenReturn(List.of());

        WorkflowChangeManifestBuilder builder = new WorkflowChangeManifestBuilder();
        ReflectionTestUtils.setField(builder, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(builder, "extractorRegistry", extractorRegistry);
        ReflectionTestUtils.setField(builder, "genericExtractor", new GenericToolChangeExtractor());
        ReflectionTestUtils.setField(builder, "toolDefService", toolDefService);
        ReflectionTestUtils.setField(builder, "formDesignApi", null);
        ReflectionTestUtils.setField(builder, "dryRunner", mock(ChangeManifestDryRunner.class));
        return builder;
    }

    /** 把一份清单里所有中文字符串连同字段路径捞出来（路径进失败信息，省得读的人再去翻结构） */
    private static Map<String, String> sweep(Object manifest) {
        return collectChinese(manifest, "manifest", 0,
                new IdentityHashMap<>(), new LinkedHashMap<>());
    }

    /**
     * 反射遍历，收集每一段中文
     *
     * <p>🔴 刻意<b>不</b>列字段名单。名单就是第二份口径：{@code ChangeManifest} 加一个中文字段
     * （S6 就加过 {@code waits[]}、{@code scope.note}），没人会想起回来补名单，
     * 于是新字段永远在扫描域外 —— 与本测试存在的理由正好相反。
     * 反射遍历让「加字段」自动等于「进扫描域」。
     *
     * <p>只下钻<b>本包内</b>的 POJO：{@code Integer} / {@code LocalDateTime} / {@code RiskLevel}
     * 这些既不含中文也不该展开。⚠️ 因此有一条已知边界：{@code ChangeItem.dryRun} 虽然是本包的
     * {@code DryRunResult}（会被展开），但它在上面那份夹具里恒为 null，
     * 故 {@code ChangeManifestDryRunner} 那 117 段中文<b>不在本域内</b> ——
     * 要覆盖它得连 infra 的只读接口一起 mock，成本另算，
     * 且它的文案已由 {@code ChangeManifestDryRunnerTest} 逐字钉住。
     */
    private static Map<String, String> collectChinese(Object obj, String path, int depth,
                                                      Map<Object, Boolean> seen,
                                                      Map<String, String> out) {
        if (obj == null || depth > MAX_DEPTH) {
            return out;
        }
        if (obj instanceof String text) {
            if (CJK.matcher(text).find()) {
                out.put(path, text);
            }
            return out;
        }
        if (obj instanceof Collection<?> coll) {
            int i = 0;
            for (Object element : coll) {
                collectChinese(element, path + "[" + i++ + "]", depth + 1, seen, out);
            }
            return out;
        }
        if (obj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collectChinese(entry.getValue(), path + "." + entry.getKey(), depth + 1, seen, out);
            }
            return out;
        }
        if (!obj.getClass().getPackageName().startsWith(MANIFEST_PACKAGE)
                || seen.put(obj, Boolean.TRUE) != null) {
            return out;
        }
        for (Class<?> c = obj.getClass();
             c != null && c.getPackageName().startsWith(MANIFEST_PACKAGE);
             c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    collectChinese(field.get(obj), path + "." + field.getName(), depth + 1, seen, out);
                } catch (IllegalAccessException ignored) {
                    // 读不到就当它不存在：本测试问的是「文案有没有洞」，不是「反射通不通」
                }
            }
        }
        return out;
    }

    // ==================== 夹具 ====================

    private String summaryOf(ToolChangeExtractor extractor, NodeDTO node) {
        ChangeItem item = extractor.extract(node, context(node));
        assertNotNull(item, extractor.toolId() + " 返回了 null 变更项");
        String summary = item.getSummary();
        assertNotNull(summary, extractor.toolId() + " 没给 summary，"
                + "确认卡上会回落成一句说不出改了什么的话");
        return summary;
    }

    /**
     * 一句话里出现的所有违规片段
     *
     * <p>返回片段而不是布尔，是为了让失败信息能直接指出是哪一种洞 ——
     * 两种洞的改法一样，但看到「（」和看到「」」能少翻一次源码。
     */
    private List<String> violationsOf(String summary) {
        List<String> found = new ArrayList<>();
        if (summary == null) {
            return found;
        }
        for (String pattern : List.of(SPACE_BEFORE_PAREN, SPACE_BEFORE_QUOTE)) {
            if (summary.contains(pattern)) {
                found.add(pattern);
            }
        }
        return found;
    }

    private String describe(String violation) {
        return SPACE_BEFORE_QUOTE.equals(violation) ? "「空格+全角书名号」" : "「空格+全角左括号」";
    }

    /**
     * 真抽取器：工具键 → 实例
     *
     * <p>⚠️ 跳过嵌套类，理由与 {@code ToolChangeExtractorCoverageTest#realExtractors} 一致：
     * 同包的 {@code WorkflowChangeManifestBuilderTest} 里有四个私有桩抽取器，
     * 它们的 {@code toolId()} 是构造参数、summary 是桩自己编的，不参与横向规则的检查。
     */
    private static Map<String, ToolChangeExtractor> realExtractors() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ToolChangeExtractor.class));
        Map<String, ToolChangeExtractor> extractors = new LinkedHashMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(MANIFEST_PACKAGE)) {
            Class<?> clazz = Class.forName(bd.getBeanClassName());
            if (clazz.getEnclosingClass() != null) {
                continue;
            }
            ToolChangeExtractor extractor =
                    (ToolChangeExtractor) clazz.getDeclaredConstructor().newInstance();
            extractors.put(extractor.toolId(), extractor);
        }
        return extractors;
    }

}
