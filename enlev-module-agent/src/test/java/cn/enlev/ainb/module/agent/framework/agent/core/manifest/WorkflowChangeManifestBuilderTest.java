package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.ApprovalItem;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.ManifestStats;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.ScopeInfo;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.UnresolvedItem;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.UntrustedInput;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.WaitItem;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowChangeManifestBuilder} 单测（S2-c1 主体，S2-d3d 追加第 ⑦ 节裁剪，
 * S2-e3b 追加第 ⑧ 节溯源接线，S6 追加第 ⑨ 节 waits 记账）
 *
 * <p>S2-c1 那一刀只交付「与具体工具无关」的那一半：遍历、认工具、取风险、算可达性、汇总、拼 headline。
 * 11 个专用抽取器在 S2-c2，故这里的抽取器全是测试内定义的桩 ——
 * 桩的存在恰恰是本测试的重点：<b>桩能不能被正确覆盖、违约时会不会让变更凭空消失</b>。
 *
 * <h3>🔴 钉得最紧的四条，都是「静默少一块」的不同入口</h3>
 * <ol>
 *   <li>{@link #writeToolWithoutDedicatedExtractor_fallsBackToGeneric} —— 新增写工具忘了配抽取器，
 *       清单里仍然有一项，且 {@code stats.genericTools} 留下计数。</li>
 *   <li>{@link #extractorReturningNull_fallsBackInsteadOfDroppingTheChange} —— 抽取器违约返回 null，
 *       变更照样出现在清单里。少一块最直接的写法就是这里 {@code continue} 掉。</li>
 *   <li>{@link #everyChangeKindHasItsOwnStatsSlot} —— 新增一种 {@link ChangeKind} 却忘了在 stats 里给它一列，
 *       headline 会说、徽标不会说。这条用反射逐 kind 验，加常量不用回来改测试。</li>
 *   <li>{@link #pruningIsNeverSilent} 与 {@link #scopingFromATerminalNodeYieldsAnEmptyButHonestManifest}
 *       —— 按 hitl 节点裁剪是<b>故意</b>少一块，而它是四条里唯一一条「设计如此」的：
 *       裁掉之后 headline 会说「本次不产生任何写操作」，与同样从那个节点裁剪、
 *       但流程里真的一个写工具都没有的那一份<b>逐字相同</b>。
 *       唯一能区分两者的是 {@code scope.pruned}，所以它必须在、必须带数、必须带一句人话。</li>
 * </ol>
 *
 * <h3>第 ⑦⑧ 节还钉了三个「顺序反了就出错、但两种顺序都编译得过」的位置</h3>
 * <ul>
 *   <li>干跑必须在裁剪<b>之前</b>（{@link #dryRunSeesTheWholeGraphBeforeScoping}）：
 *       干跑器有一项跨变更项的判定「本流程自己会不会把这张表建出来」，
 *       先裁就等于把上游那张建表节点从它视野里抹掉，最标准的
 *       {@code 建表 → 审批 → 建表单} 会被判成「主表不存在，会被拒」。</li>
 *   <li>汇总必须在裁剪<b>之后</b>（{@link #statsAndHeadlineAreRecomputedAfterPruning}、
 *       {@link #dryRunCountersOnlyCountVisibleItems}）：反过来就是
 *       「标题说 3 张表、changes 里只有 1 项」。这一条不是靠测试守着的，
 *       是靠 {@code finish} 改成从留下来的 {@code changes} 现算 —— 结构上写不出那种不一致。</li>
 *   <li>溯源必须在裁剪<b>之前</b>（{@link #scopingPrunesUntrustedFlowsBySink}）：
 *       裁剪器要按 {@code sinkNodeId} 剪那个列表并记 {@code hiddenUntrusted}，
 *       顺序倒了就会把剪掉的条目又加回来，那个数成了假话。
 *       {@link #prunedIsTrueWhenOnlyUntrustedFlowsWereHidden} 钉的则是记账本身 ——
 *       {@code pruned} 少算这一项，渲染层就不显示「已裁剪」横幅，与静默裁剪等价。</li>
 * </ul>
 *
 * <p>第 ⑨ 节补的是同一族里最后一条：裁剪器管<b>六个</b>列表，而 ⑦⑧ 只钉住了五个。
 * 第六个 {@code waits[]}（S6 加的）由 {@link #scopingCountsHiddenWaitsToo} 钉住计数与人话，
 * 由 {@link #durationWaitsAreNotReportedAsExternalWaits} 钉住「只有 event 那一支才进清单」——
 * 否则「所有 wait 都进」与「只有 event 进」在上一发里是同一份绿。
 *
 * @author 走进新时代
 */
class WorkflowChangeManifestBuilderTest {

    // ==================== 桩工具：只借注解声明「名字 + 展示名 + 风险」，不需要执行体 ====================
    // 🔴 刻意用真实工具键而不是 made-up 名字：DSL fixture 因此读起来跟线上真的一样，
    // 「相近的有 datamodel_list」那条候选提示也才有意义。它们的注解只在本测试类里，
    // 与 tool/impl/ 下的真类没有任何关系（register(Class) 只读注解、不实例化）。

    @AgentTool(name = "datamodel_create", displayName = "创建数据表", groupName = "数据建模",
            description = "桩", permission = "infra:data-model:create", risk = RiskLevel.IRREVERSIBLE)
    static class StubCreateTableTool {
    }

    @AgentTool(name = "form_save_draft", displayName = "保存表单草稿", groupName = "表单设计",
            description = "桩", permission = "infra:form-design:create", risk = RiskLevel.LOW)
    static class StubSaveDraftTool {
    }

    @AgentTool(name = "form_publish_bundle", displayName = "一键发布表单", groupName = "表单设计",
            description = "桩", permission = "infra:form-design:publish", risk = RiskLevel.HIGH)
    static class StubPublishTool {
    }

    @AgentTool(name = "form_update", displayName = "修改表单", groupName = "表单设计",
            description = "桩", permission = "infra:form-design:update", risk = RiskLevel.MEDIUM)
    static class StubFormUpdateTool {
    }

    @AgentTool(name = "datamodel_list", displayName = "查询数据表清单", groupName = "数据建模",
            description = "桩", permission = "infra:data-model:query", risk = RiskLevel.READ_ONLY)
    static class StubListTableTool {
    }

    @AgentTool(name = "form_inspect", displayName = "体检表单", groupName = "表单设计",
            description = "桩", permission = "infra:form-design:query", risk = RiskLevel.READ_ONLY)
    static class StubInspectTool {
    }

    // ==================== 桩抽取器 ====================

    /** 正常抽取器：只填 kind/target/detail/summary，其余交给 builder（契约见 ToolChangeExtractor） */
    private static class StubExtractor implements ToolChangeExtractor {
        private final String toolId;
        private final ChangeKind kind;
        private final Map<String, Object> target = new LinkedHashMap<>();
        private final Map<String, Object> detail = new LinkedHashMap<>();
        private String summary;

        StubExtractor(String toolId, ChangeKind kind) {
            this.toolId = toolId;
            this.kind = kind;
        }

        StubExtractor target(String key, Object value) {
            target.put(key, value);
            return this;
        }

        StubExtractor detail(String key, Object value) {
            detail.put(key, value);
            return this;
        }

        StubExtractor summary(String summary) {
            this.summary = summary;
            return this;
        }

        @Override
        public String toolId() {
            return toolId;
        }

        @Override
        public ChangeItem extract(NodeDTO node,
                                  ManifestContext ctx) {
            ChangeItem item = new ChangeItem();
            item.setKind(kind);
            item.getTarget().putAll(target);
            item.getDetail().putAll(detail);
            item.setSummary(summary);
            return item;
        }
    }

    /** 违约抽取器：返回 null（契约明令禁止，builder 必须兜住） */
    private record NullExtractor(String toolId) implements ToolChangeExtractor {
        @Override
        public ChangeItem extract(NodeDTO node,
                                  ManifestContext ctx) {
            return null;
        }
    }

    /** 违约抽取器：忘了设 kind（builder 必须按 GENERIC_TOOL 兜，而不是拿 null 去撞 EnumMap） */
    private record NoKindExtractor(String toolId) implements ToolChangeExtractor {
        @Override
        public ChangeItem extract(NodeDTO node,
                                  ManifestContext ctx) {
            ChangeItem item = new ChangeItem();
            item.setSummary("我忘了设 kind");
            return item;
        }
    }

    /**
     * 越权抽取器：连 builder 该管的字段一起写。
     *
     * <p>🔴 这条桩是 {@link #builderOwnsToolIdentityAndRiskFields} 的全部意义 ——
     * 把风险等级写成 READ_ONLY、把可逆写成 true，看 builder 是否照单全收。
     * 收了的后果是「工具注册表标不可逆、变更清单标可逆」，而用户是照着清单点同意的。
     */
    private record LiarExtractor(String toolId) implements ToolChangeExtractor {
        @Override
        public ChangeItem extract(NodeDTO node,
                                  ManifestContext ctx) {
            ChangeItem item = new ChangeItem();
            item.setKind(ChangeKind.TABLE_CREATE);
            item.setNodeId("伪造的节点");
            item.setNodeName("伪造的名字");
            item.setTool("伪造的工具键");
            item.setToolDisplayName("伪造的展示名");
            item.setRiskLevel(RiskLevel.READ_ONLY.getLevel());
            item.setRiskDescription(RiskLevel.READ_ONLY.getDescription());
            item.setReversible(true);
            return item;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private AgentToolRegistry toolRegistry;
    private GenericToolChangeExtractor genericExtractor;
    private AgentToolDefService toolDefService;
    /**
     * 干跑是 mock：本测试只测「遍历、认工具、取风险、算可达性、汇总」，
     * 干跑的三态与撞车探测由 {@code ChangeManifestDryRunnerTest} 单独覆盖。
     * mock 的 {@code enrich} 默认什么都不做 ⟹ 每项的 {@code dryRun} 恒为 null，
     * 与既有断言互不干扰。第 ⑦ 节的两个用例用 {@link #stubDryRun} 给它按节点 id 发结论 ——
     * 那里要测的正是「干跑的计数会不会跟着裁剪重算」，不喂结论就无从验起。
     */
    private ChangeManifestDryRunner dryRunner;

    @BeforeEach
    void setUp() {
        toolRegistry = new AgentToolRegistry();
        toolRegistry.register(StubCreateTableTool.class);
        toolRegistry.register(StubSaveDraftTool.class);
        toolRegistry.register(StubPublishTool.class);
        toolRegistry.register(StubFormUpdateTool.class);
        toolRegistry.register(StubListTableTool.class);
        toolRegistry.register(StubInspectTool.class);
        genericExtractor = new GenericToolChangeExtractor();
        toolDefService = mock(AgentToolDefService.class);
        when(toolDefService.getEnabledToolDefList()).thenReturn(List.of());
        dryRunner = mock(ChangeManifestDryRunner.class);
    }

    private WorkflowChangeManifestBuilder builder(ToolChangeExtractor... extractors) {
        ToolChangeExtractorRegistry registry = new ToolChangeExtractorRegistry();
        registry.setExtractors(List.of(extractors));
        WorkflowChangeManifestBuilder builder = new WorkflowChangeManifestBuilder();
        ReflectionTestUtils.setField(builder, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(builder, "extractorRegistry", registry);
        ReflectionTestUtils.setField(builder, "genericExtractor", genericExtractor);
        ReflectionTestUtils.setField(builder, "toolDefService", toolDefService);
        ReflectionTestUtils.setField(builder, "dryRunner", dryRunner);
        return builder;
    }

    private WorkflowDefDTO dsl(String json) throws Exception {
        return mapper.readValue(json, WorkflowDefDTO.class);
    }

    /** 一个工具节点的最小 DSL；toolJson 直接拼进 config，省得每个用例重写一遍骨架 */
    private String wrap(String nodesJson, String edgesJson) {
        return "{\"dslVersion\":\"1.0\",\"id\":\"wf_demo\",\"name\":\"演示流程\","
                + "\"trigger\":{\"type\":\"trigger.chat\"},"
                + "\"variables\":[{\"key\":\"input\",\"type\":\"object\"}],"
                + "\"nodes\":[" + nodesJson + "],\"edges\":[" + edgesJson + "]}";
    }

    private static AgentToolDefDO customTool(String key, String displayName, String riskLevel) {
        AgentToolDefDO row = new AgentToolDefDO();
        row.setToolKey(key);
        row.setDisplayName(displayName);
        row.setRiskLevel(riskLevel);
        row.setEnabled(true);
        return row;
    }

    // ==================== ① 遍历与分派 ====================

    @Test
    @DisplayName("只读工具节点不产生变更项，但要计入 readToolCount（证明扫过了）")
    void readOnlyToolsAreCountedButNotListed() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"list_tables","type":"tool","name":"查表","config":{"toolId":"datamodel_list"}},
                {"id":"inspect","type":"tool","name":"体检","config":{"toolId":"form_inspect"}},
                {"id":"think","type":"llm","name":"想一下","config":{"prompt":"{{$.input.q}}"}},
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}}
                """, """
                {"from":"START","to":"list_tables"},{"from":"list_tables","to":"inspect"},
                {"from":"inspect","to":"think"},{"from":"think","to":"make"},{"from":"make","to":"END"}
                """));

        ChangeManifest m = builder().build(def, 3);

        assertEquals(1, m.getChanges().size(), "两个只读工具 + 一个 llm 节点都不该出现在变更里");
        assertEquals(ChangeKind.GENERIC_TOOL, m.getChanges().get(0).getKind(), "没配抽取器 ⟹ 兜底");
        assertEquals(2, m.getStats().getReadToolCount());
        assertEquals(1, m.getStats().getWriteToolCount());
        assertEquals(3, m.getWorkflow().getVersion());
        assertEquals("wf_demo", m.getWorkflow().getCode());
        assertEquals("演示流程", m.getWorkflow().getName());
        assertEquals("trigger.chat", m.getWorkflow().getTriggerType());
        assertEquals(ChangeManifest.VERSION, m.getManifestVersion());
        assertNotNull(m.getGeneratedAt());
    }

    @Test
    @DisplayName("变更项按 DSL 里的节点顺序，不按风险排序（与画布一致才对得上号）")
    void changesFollowDslNodeOrder() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"publish","type":"tool","name":"发布","config":{"toolId":"form_publish_bundle"}},
                {"id":"create","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}}
                """, """
                {"from":"START","to":"publish"},{"from":"publish","to":"create"},{"from":"create","to":"END"}
                """));

        ChangeManifest m = builder().build(def);

        assertEquals(List.of("publish", "create"),
                m.getChanges().stream().map(ChangeItem::getNodeId).toList());
        assertEquals("IRREVERSIBLE", m.getRiskLevel(), "总风险仍取最严重那个，与顺序无关");
    }

    @Test
    @DisplayName("🔴 没配专用抽取器的写工具走兜底：清单里仍有一项，且 genericTools 留下计数")
    void writeToolWithoutDedicatedExtractor_fallsBackToGeneric() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create",
                  "args":{"model":{"tableName":"biz_employee","tableComment":"员工信息",
                            "columns":[{"columnName":"emp_name","columnType":"STRING","length":64}]}}}}
                """, """
                {"from":"START","to":"make"},{"from":"make","to":"END"}
                """));

        ChangeManifest m = builder().build(def);

        assertEquals(1, m.getChanges().size(), "漏配抽取器绝不能表现为「清单里少一项」");
        ChangeItem item = m.getChanges().get(0);
        assertEquals(ChangeKind.GENERIC_TOOL, item.getKind());
        assertEquals(1, m.getStats().getGenericTools(), "这个计数不为 0 就是「有写工具没配抽取器」的信号");
        // 兜底必须把参数摆出来：嵌套 Map 渲染成 JSON 串，且键序保持 DSL 里的顺序
        @SuppressWarnings("unchecked")
        Map<String, String> preview = (Map<String, String>) item.getDetail().get("argsPreview");
        assertNotNull(preview, "兜底项必须带 argsPreview");
        assertEquals(List.of("model"), List.copyOf(preview.keySet()));
        assertTrue(preview.get("model").contains("biz_employee"), "参数值要能被看见：" + preview.get("model"));
        assertEquals(Boolean.TRUE, item.getDetail().get("incomplete"), "要明确标注解读不完整");
        assertTrue(item.getSummary().contains("暂无专用解读"),
                "summary 要自己承认说不全，别让用户以为这就是全部：" + item.getSummary());
        assertTrue(item.getSummary().contains("创建数据表"), "summary 要带中文展示名：" + item.getSummary());
    }

    @Test
    @DisplayName("🔴 抽取器违约返回 null 时退回兜底，而不是让这项变更凭空消失")
    void extractorReturningNull_fallsBackInsteadOfDroppingTheChange() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}}
                """, """
                {"from":"START","to":"make"},{"from":"make","to":"END"}
                """));

        ChangeManifest m = builder(new NullExtractor("datamodel_create")).build(def);

        assertEquals(1, m.getChanges().size(), "返回 null 就被 continue 掉的话，这项变更会在运行期照做、清单上却没有");
        assertEquals(ChangeKind.GENERIC_TOOL, m.getChanges().get(0).getKind());
        assertTrue(m.getUnresolved().isEmpty(), "工具是认得的，不该混进 unresolved");
    }

    @Test
    @DisplayName("抽取器忘了设 kind ⟹ 按 GENERIC_TOOL 兜，不能让 null 键撞进 EnumMap 变成接口 500")
    void extractorForgettingKind_isTreatedAsGeneric() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}}
                """, """
                {"from":"START","to":"make"},{"from":"make","to":"END"}
                """));

        ChangeManifest m = builder(new NoKindExtractor("datamodel_create")).build(def);

        assertEquals(1, m.getChanges().size());
        assertEquals(ChangeKind.GENERIC_TOOL, m.getChanges().get(0).getKind());
        assertEquals(1, m.getStats().getGenericTools());
        assertEquals("我忘了设 kind", m.getChanges().get(0).getSummary(), "抽取器自己写的 summary 要留着");
    }

    @Test
    @DisplayName("🔴 工具身份与风险由 builder 覆盖式填写，抽取器越权写的值一律作废")
    void builderOwnsToolIdentityAndRiskFields() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}}
                """, """
                {"from":"START","to":"make"},{"from":"make","to":"END"}
                """));

        ChangeManifest m = builder(new LiarExtractor("datamodel_create")).build(def);

        ChangeItem item = m.getChanges().get(0);
        assertEquals(ChangeKind.TABLE_CREATE, item.getKind(), "kind 归抽取器，这一半要认");
        assertEquals("make", item.getNodeId());
        assertEquals("建表", item.getNodeName());
        assertEquals("datamodel_create", item.getTool());
        assertEquals("创建数据表", item.getToolDisplayName());
        assertEquals("IRREVERSIBLE", item.getRiskLevel(), "风险等级的唯一权威是工具注册表");
        assertEquals("不可逆", item.getRiskDescription());
        assertFalse(item.isReversible(), "可逆性由风险等级推导，抽取器说什么都不算");
    }

    /**
     * 🔴 P0-1 / D11：一行 {@code agent_tool_override} 把「建表」压成生效 READ_ONLY，
     * 变更清单里那个节点<b>照样必须出现</b>、照样必须标 IRREVERSIBLE。
     *
     * <p>为什么这条是本节最要紧的一刀：{@code resolveTool} 的风险级喂给
     * {@code if (meta.risk() == RiskLevel.READ_ONLY) { readToolNodeIds.add(...); continue; }}，
     * 也就是「只读工具不进 {@code changes[]}」。所以一旦它读生效值，覆盖行一按下去：
     * <ol>
     *   <li>建表节点被<b>静默</b>当成只读工具跳过，清单里一个字都不提；</li>
     *   <li>用户照着一份「什么都没改」的清单点了同意，BPM 审批也照这份清单走；</li>
     *   <li>运行起来照建表 —— 清单与事实相反，而清单正是「执行前把后果说清楚」的那份东西。</li>
     * </ol>
     *
     * <p>更糟的是它会与 {@code WorkflowDslValidator} 的 V11（{@code ToolResolver#declaredRiskOf}，
     * 读声明值）<b>互相矛盾</b>：同一个工具，校验器说不可逆、清单说只读。
     *
     * <p>本用例走的是<b>真</b> {@link AgentToolRegistry} + 真覆盖表（不是 mock），
     * 故它同时证明了「覆盖数据这条路真的能压到 READ_ONLY」—— 即 {@code getRisk()} 确实返回覆盖值，
     * 变异探针才有意义。
     *
     * <p><b>🔴 变异探针</b>：把 {@code WorkflowChangeManifestBuilder#resolveTool} 里的
     * {@code getDeclaredRisk()} 改回 {@code getRisk()}，本用例必须变红，
     * 且红在第一条断言（{@code changes[]} 空了）。
     */
    @Test
    @DisplayName("🔴 被覆盖行压成只读的建表工具：变更清单照样列出、照样标不可逆（D11）")
    void toolDowngradedByOverrideStillListedAsIrreversible() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}}
                """, """
                {"from":"START","to":"make"},{"from":"make","to":"END"}
                """));
        Long tenantId = 99001L;
        try {
            TenantContextHolder.setTenantId(tenantId);
            toolRegistry.refreshTenantOverrides(tenantId, Map.of("datamodel_create",
                    new AgentToolRegistry.ToolOverride("创建数据表", "桩", "数据建模",
                            RiskLevel.READ_ONLY, List.of("infra:data-model:create"), true, "无参数")));
            // 🔴 先证明覆盖<b>真的生效了</b>（阳性对照，§2-6）：否则下面「照样列出」可能只是
            //    因为压根没压下去，用例就成了永绿的空断言
            AgentToolRegistry.ToolDefinition overridden = toolRegistry.get("datamodel_create");
            assertEquals(RiskLevel.READ_ONLY, overridden.getRisk(),
                    "阳性对照失效：覆盖行没把生效风险压成 READ_ONLY，本用例证不了任何事");
            assertFalse(overridden.isRequireConfirm(), "阳性对照失效：生效值仍要求确认");
            assertEquals(RiskLevel.IRREVERSIBLE, overridden.getDeclaredRisk(), "声明值必须搬不动");

            ChangeManifest m = builder().build(def);

            assertEquals(1, m.getChanges().size(),
                    "🔴 建表节点被当成只读工具跳过了 —— 用户会照着一份没提建表的清单点同意");
            ChangeItem item = m.getChanges().get(0);
            assertEquals("make", item.getNodeId());
            assertEquals("IRREVERSIBLE", item.getRiskLevel(), "清单只认声明风险级");
            assertEquals("不可逆", item.getRiskDescription());
            assertFalse(item.isReversible());
            assertEquals("IRREVERSIBLE", m.getRiskLevel(), "总风险也不能被覆盖行拉低");
            assertEquals(0, m.getStats().getReadToolCount(), "它不是只读工具，不该计入只读数");
            assertEquals(1, m.getStats().getWriteToolCount());
        } finally {
            // 🔴 必须清：TenantContextHolder 是 ThreadLocal，JUnit 复用线程，
            //    留着会让后面所有用例都在这个租户上下文里跑（且覆盖表是 registry 实例级，
            //    setUp 每条用例重建 registry，故不必单独清覆盖）
            TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("节点没写 name 时回落节点 id，绝不留空白")
    void nodeNameFallsBackToId() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"make","type":"tool","config":{"toolId":"datamodel_create"}}
                """, """
                {"from":"START","to":"make"},{"from":"make","to":"END"}
                """));

        ChangeManifest m = builder().build(def);

        assertEquals("make", m.getChanges().get(0).getNodeName());
    }

    // ==================== ② 认工具 ====================

    @Test
    @DisplayName("🔴 认不出的工具键进 unresolved，并给出相近候选（LLM 打错键是头号成因）")
    void unknownToolLandsInUnresolvedWithCandidates() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"n3","type":"tool","name":"查表","config":{"toolId":"datamodel_lst"}}
                """, """
                {"from":"START","to":"n3"},{"from":"n3","to":"END"}
                """));

        ChangeManifest m = builder().build(def);

        assertTrue(m.getChanges().isEmpty(), "认不出的工具算不出风险，不能当变更项混进清单");
        assertEquals(1, m.getUnresolved().size());
        UnresolvedItem u = m.getUnresolved().get(0);
        assertEquals(UnresolvedItem.TYPE_TOOL_UNKNOWN, u.getType());
        assertEquals("n3", u.getNodeId());
        assertEquals("查表", u.getNodeName());
        assertEquals("datamodel_lst", u.getTool());
        // 阳性对照：候选必须真给出来了，否则「相近的有 X」这句就是永假的装饰
        assertTrue(u.getReason().contains("datamodel_list"),
                "应当提示相近的内置工具键，实际：" + u.getReason());
        assertTrue(u.getReason().contains("datamodel_lst"), "要点名写错的那个键：" + u.getReason());
        assertTrue(m.getRiskNote().contains("1 处说不清"),
                "有 unresolved 时风险提示必须提到，否则清单看起来是干净的：" + m.getRiskNote());
        assertEquals("本次不产生任何写操作", m.getHeadline(),
                "认不出的工具不计入 headline —— 算不出它是干什么的，就不能替它编一句话");
    }

    @Test
    @DisplayName("节点没指定 toolId ⟹ 单独一类 unresolved（与「工具键写错」的处置不同）")
    void missingToolIdLandsInUnresolved() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"n4","type":"tool","name":"忘了选工具","config":{"args":{"a":1}}}
                """, """
                {"from":"START","to":"n4"},{"from":"n4","to":"END"}
                """));

        ChangeManifest m = builder().build(def);

        assertEquals(1, m.getUnresolved().size());
        assertEquals(UnresolvedItem.TYPE_TOOL_MISSING, m.getUnresolved().get(0).getType());
        assertNull(m.getUnresolved().get(0).getTool());
        assertTrue(m.getUnresolved().get(0).getReason().contains("选择要调用的工具"),
                "要说清怎么改：" + m.getUnresolved().get(0).getReason());
    }

    @Test
    @DisplayName("自定义工具（agent_tool_def）认得出来，展示名与风险按行取；风险串非法回落 HIGH")
    void customToolIsResolvedFromToolDefService() throws Exception {
        when(toolDefService.getEnabledToolDefList()).thenReturn(List.of(
                customTool("my_sync", "同步到第三方", "MEDIUM"),
                customTool("my_mystery", null, "不是合法等级")));
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"a","type":"tool","name":"同步","config":{"toolId":"my_sync"}},
                {"id":"b","type":"tool","name":"神秘工具","config":{"toolId":"my_mystery"}}
                """, """
                {"from":"START","to":"a"},{"from":"a","to":"b"},{"from":"b","to":"END"}
                """));

        ChangeManifest m = builder().build(def);

        assertEquals(2, m.getChanges().size(), "自定义工具也是工具，不能当成「未登记」");
        assertTrue(m.getUnresolved().isEmpty(), m.getUnresolved().toString());
        ChangeItem a = m.getChanges().get(0);
        assertEquals("同步到第三方", a.getToolDisplayName());
        assertEquals("MEDIUM", a.getRiskLevel());
        assertEquals("中风险", a.getRiskDescription());
        ChangeItem b = m.getChanges().get(1);
        assertEquals("my_mystery", b.getToolDisplayName(), "displayName 为空时回落工具键，不留空白行");
        assertEquals("HIGH", b.getRiskLevel(), "非法风险串回落 HIGH，与 RiskLevel.parseOrHigh 同口径");
        assertEquals(2, m.getStats().getGenericTools(), "自定义工具没有专用抽取器，全走兜底");
    }

    @Test
    @DisplayName("🔴 registry-first：自定义工具抢不到内置工具的名字（与执行路径同口径）")
    void registryWinsOverCustomToolWithSameKey() throws Exception {
        when(toolDefService.getEnabledToolDefList()).thenReturn(List.of(
                customTool("datamodel_create", "冒名的自定义工具", "READ_ONLY")));
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}}
                """, """
                {"from":"START","to":"make"},{"from":"make","to":"END"}
                """));

        ChangeManifest m = builder().build(def);

        assertEquals(1, m.getChanges().size());
        ChangeItem item = m.getChanges().get(0);
        assertEquals("创建数据表", item.getToolDisplayName(), "必须显示内置那个的展示名");
        assertEquals("IRREVERSIBLE", item.getRiskLevel(),
                "若被自定义那行的 READ_ONLY 覆盖，清单会漏报一项不可逆变更，而运行期调的仍是内置工具");
    }

    @Test
    @DisplayName("纯内置工具的 DSL 完全不查自定义工具表（懒加载）")
    void customToolTableIsNotQueriedWhenEveryToolIsBuiltin() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}}
                """, """
                {"from":"START","to":"make"},{"from":"make","to":"END"}
                """));

        builder().build(def);

        verify(toolDefService, never()).getEnabledToolDefList();
    }

    // ==================== ③ 人工介入点 ====================

    @Test
    @DisplayName("确认点只报「他同意之后」会发生的变更；身后没有写操作时 willSee=false")
    void approvalsCarryDownstreamKindsByReachability() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"gate","type":"hitl.confirm","name":"人工确认"},
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}},
                {"id":"save","type":"tool","name":"存草稿","config":{"toolId":"form_save_draft"}},
                {"id":"late","type":"hitl.confirm","name":"事后确认"}
                """, """
                {"from":"START","to":"gate"},{"from":"gate","to":"make"},
                {"from":"make","to":"save"},{"from":"save","to":"late"},{"from":"late","to":"END"}
                """));

        // 配两个专用抽取器：没有它们，两项变更都落 GENERIC_TOOL，downstreamKinds 去重后只剩一个，
        // 这条测试就退化成「有没有下游」，测不出「下游是哪几类」
        ChangeManifest m = builder(
                new StubExtractor("datamodel_create", ChangeKind.TABLE_CREATE),
                new StubExtractor("form_save_draft", ChangeKind.FORM_CREATE)).build(def);

        assertEquals(2, m.getApprovals().size());
        ApprovalItem gate = m.getApprovals().get(0);
        assertEquals("confirm", gate.getType(), "= 节点 type 去掉 hitl. 前缀");
        assertEquals("人工确认", gate.getNodeName());
        // 顺序是 ChangeKind 声明顺序，不是 DSL 里的节点顺序 —— 见 fillDownstreamKinds
        assertEquals(List.of("TABLE_CREATE", "FORM_CREATE"), gate.getDownstreamKinds());
        assertTrue(gate.isWillSee());

        ApprovalItem late = m.getApprovals().get(1);
        assertTrue(late.getDownstreamKinds().isEmpty());
        assertFalse(late.isWillSee(), "身后没有任何写操作 —— 这是编排错误，渲染层要据此提示，不能显示一个空清单");
    }

    @Test
    @DisplayName("🔴 条件边的多余 to 不算通路：清单不会让审批人以为自己要批一项根本不会执行的变更")
    void conditionalEdgeStrayToIsNotAPathForApprovals() throws Exception {
        // gate 唯一的「通路」是条件边上那个多余的 to；编译器从不读它（DslToStateGraphCompiler:372-382），
        // S2-b 已把 GraphReachability 统一到运行期口径，故这里必须判「看不见」。
        // 同一语义的抽取器级测试见 GraphReachabilityTest#conditionalEdgeWithStrayToIsIgnoredLikeTheCompiler。
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"gate","type":"hitl.confirm","name":"人工确认"},
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}},
                {"id":"think","type":"llm","name":"想一下"}
                """, """
                {"from":"START","to":"gate"},
                {"from":"gate","type":"conditional","to":"make","cases":{"ok":"think"},"default":"END"},
                {"from":"make","to":"END"},{"from":"think","to":"END"}
                """));

        ChangeManifest m = builder().build(def);

        assertEquals(1, m.getChanges().size(), "变更项照列 —— 建表节点本身还在图里，只是 gate 走不到它");
        ApprovalItem gate = m.getApprovals().get(0);
        assertTrue(gate.getDownstreamKinds().isEmpty(),
                "gate 的下游只有 think（llm，无变更），不该把 make 算进来：" + gate.getDownstreamKinds());
        assertFalse(gate.isWillSee());
    }

    @Test
    @DisplayName("bpm_approval 带出流程 key 与表单变量名，但绝不带出变量的值")
    void bpmApprovalCarriesProcessKeyAndFormVarNamesOnly() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"bpm_gate","type":"hitl.bpm_approval","name":"走审批","config":{
                   "bpmProcessDefKey":"employee_approval",
                   "formVars":{"emp_name":"{{$.input.name}}","idCard":"{{$.input.idCard}}"}}}
                """, """
                {"from":"START","to":"bpm_gate"},{"from":"bpm_gate","to":"END"}
                """));

        ChangeManifest m = builder().build(def);

        ApprovalItem approval = m.getApprovals().get(0);
        assertEquals("bpm_approval", approval.getType());
        assertEquals("employee_approval", approval.getBpmProcessDefKey());
        assertEquals(List.of("emp_name", "idCard"), approval.getFormVarsKeys());
        // 清单会被冻结进 BPM 流程变量长期留库，值可能是身份证号这类敏感内容
        String json = JsonUtils.toJsonString(m);
        assertFalse(json.contains("{{$.input.idCard}}"), "只给变量名，不给值：" + json);
        assertFalse(json.contains("{{$.input.name}}"), "只给变量名，不给值：" + json);
    }

    // ==================== ④ 子流程 ====================

    @Test
    @DisplayName("🔴 sub_workflow 显式进 unresolved，不静默跳过（子流程里建表与顶层建表对用户是同一件事）")
    void subWorkflowIsReportedNotSilentlySkipped() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"sw","type":"sub_workflow","name":"调子流程","config":{"workflowId":"child_wf"}}
                """, """
                {"from":"START","to":"sw"},{"from":"sw","to":"END"}
                """));

        ChangeManifest m = builder().build(def);

        assertEquals(1, m.getUnresolved().size());
        UnresolvedItem u = m.getUnresolved().get(0);
        assertEquals(UnresolvedItem.TYPE_SUB_WORKFLOW, u.getType());
        assertNull(u.getTool());
        assertTrue(u.getReason().contains("child_wf"), "要点名是哪个子流程：" + u.getReason());
        assertTrue(u.getReason().contains("变更清单"), "要说清去哪儿看：" + u.getReason());
    }

    // ==================== ⑤ 汇总：headline / 风险 / stats ====================

    @Test
    @DisplayName("🔴 每种 ChangeKind 都有自己的一列 stats —— 新增常量忘了配列会撞红这条")
    void everyChangeKindHasItsOwnStatsSlot() throws Exception {
        // permissions 不按 kind 计（它来自 FORM_PUBLISH 项的 detail），故排除；
        // writeToolCount / irreversibleCount / readToolCount 是横切计数，同样排除
        Set<String> excluded = Set.of("writeToolCount", "irreversibleCount", "readToolCount", "permissions");
        List<Field> slots = new ArrayList<>();
        for (Field field : ManifestStats.class.getDeclaredFields()) {
            if (field.getType() == int.class && !excluded.contains(field.getName())) {
                field.setAccessible(true);
                slots.add(field);
            }
        }
        // 阳性对照：排除名单写错（比如把整张表都排掉）会让下面整轮循环空跑而全绿
        assertTrue(slots.size() >= ChangeKind.values().length,
                "按 kind 分列的 stats 字段只有 " + slots.size() + " 个，少于 ChangeKind 的 "
                        + ChangeKind.values().length + " 个，排除名单大概率写多了");

        for (ChangeKind kind : ChangeKind.values()) {
            WorkflowDefDTO def = dsl(wrap("""
                    {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}}
                    """, """
                    {"from":"START","to":"make"},{"from":"make","to":"END"}
                    """));
            ChangeManifest m = builder(new StubExtractor("datamodel_create", kind)).build(def);

            int sum = 0;
            for (Field field : slots) {
                sum += field.getInt(m.getStats());
            }
            assertEquals(1, sum, kind + " 没有被任何 stats 列计到（或计了两次）：" + m.getStats());
            assertEquals(1, m.getChanges().size());
        }
    }

    @Test
    @DisplayName("headline 按 ChangeKind 声明序拼，并把「顺手建了几个权限点」说出来")
    void headlineCountsEachKindAndMentionsPermissions() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"save","type":"tool","name":"存草稿","config":{"toolId":"form_save_draft"}},
                {"id":"pub","type":"tool","name":"发布","config":{"toolId":"form_publish_bundle"}},
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}}
                """, """
                {"from":"START","to":"save"},{"from":"save","to":"pub"},
                {"from":"pub","to":"make"},{"from":"make","to":"END"}
                """));
        List<String> sevenPermissions = List.of("query", "create", "update", "delete",
                "export", "import", "reference");

        ChangeManifest m = builder(
                new StubExtractor("form_save_draft", ChangeKind.FORM_CREATE),
                new StubExtractor("form_publish_bundle", ChangeKind.FORM_PUBLISH)
                        .detail("permissions", sevenPermissions),
                new StubExtractor("datamodel_create", ChangeKind.TABLE_CREATE))
                .build(def);

        // 节点顺序是 save→pub→make，但 headline 按 ChangeKind 声明序：表在表单前、表单在发布前
        assertEquals("将新建 1 张数据表、新建 1 张表单、发布 1 个表单（含 7 个按钮权限）", m.getHeadline());
        assertEquals(7, m.getStats().getPermissions());
        assertEquals(1, m.getStats().getTables());
        assertEquals(1, m.getStats().getForms());
        assertEquals(1, m.getStats().getMenus());
        assertEquals(3, m.getStats().getWriteToolCount());
        assertEquals("IRREVERSIBLE", m.getRiskLevel());
        assertEquals("不可逆", m.getRiskDescription());
        assertEquals(1, m.getStats().getIrreversibleCount());
        assertTrue(m.getRiskNote().contains("无法自动回滚"), m.getRiskNote());
    }

    @Test
    @DisplayName("总风险取最严重那一项，与节点顺序无关")
    void riskAggregationTakesTheWorst() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}},
                {"id":"pub","type":"tool","name":"发布","config":{"toolId":"form_publish_bundle"}},
                {"id":"upd","type":"tool","name":"改表单","config":{"toolId":"form_update"}},
                {"id":"save","type":"tool","name":"存草稿","config":{"toolId":"form_save_draft"}}
                """, """
                {"from":"START","to":"make"},{"from":"make","to":"pub"},
                {"from":"pub","to":"upd"},{"from":"upd","to":"save"},{"from":"save","to":"END"}
                """));

        // 只给建表配抽取器：其余三个走兜底，用来验「有 kind 的」与「兜底的」混在一起时各计各的
        ChangeManifest m = builder(new StubExtractor("datamodel_create", ChangeKind.TABLE_CREATE)).build(def);

        assertEquals(4, m.getChanges().size());
        assertEquals("IRREVERSIBLE", m.getRiskLevel());
        assertEquals(1, m.getStats().getIrreversibleCount());
        assertEquals(3, m.getStats().getGenericTools());
        assertEquals(1, m.getStats().getTables());
        // 可逆性逐项由各自的风险等级推导：建表不可逆，其余三个可逆
        assertFalse(m.getChanges().get(0).isReversible());
        assertTrue(m.getChanges().get(1).isReversible());
        assertEquals("将新建 1 张数据表、调用 3 个工具", m.getHeadline());
    }

    @Test
    @DisplayName("没有任何写操作时，headline 与 riskNote 都直说，不含糊")
    void emptyDslSaysNoWriteOperation() throws Exception {
        ChangeManifest fromNull = builder().build(null);
        assertEquals("本次不产生任何写操作", fromNull.getHeadline());
        assertEquals("READ_ONLY", fromNull.getRiskLevel());
        assertEquals("只读", fromNull.getRiskDescription());
        assertTrue(fromNull.getChanges().isEmpty());
        assertNull(fromNull.getWorkflow().getCode());

        WorkflowDefDTO noNodes = dsl("{\"id\":\"wf_empty\",\"nodes\":[],\"edges\":[]}");
        ChangeManifest m = builder().build(noNodes);
        assertEquals("本次不产生任何写操作", m.getHeadline());
        assertEquals("本次不产生任何写操作", m.getRiskNote());
        assertEquals(0, m.getStats().getWriteToolCount());
        assertEquals(0, m.getStats().getReadToolCount());
    }

    // ==================== ⑥ 注册表与序列化契约 ====================

    @Test
    @DisplayName("🔴 抽取器 toolId() 返回空白 ⟹ 启动就炸（空键的实现永远查不到，等于静默少一块）")
    void blankExtractorToolIdFailsAtStartup() {
        ToolChangeExtractorRegistry registry = new ToolChangeExtractorRegistry();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.setExtractors(List.of(new StubExtractor("  ", ChangeKind.TABLE_CREATE))));
        assertTrue(ex.getMessage().contains("toolId()"), ex.getMessage());
    }

    @Test
    @DisplayName("清单可被 Jackson 序列化并原样读回（它要能塞进 BPM 流程变量）")
    void manifestRoundTripsThroughJson() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"gate","type":"hitl.confirm","name":"人工确认"},
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create",
                  "args":{"model":{"tableName":"biz_employee"}}}}
                """, """
                {"from":"START","to":"gate"},{"from":"gate","to":"make"},{"from":"make","to":"END"}
                """));

        ChangeManifest origin = builder(new StubExtractor("datamodel_create", ChangeKind.TABLE_CREATE)
                .target("tableName", "biz_employee")).build(def, 7);
        ChangeManifest back = JsonUtils.parseObject(JsonUtils.toJsonString(origin), ChangeManifest.class);

        assertEquals(ChangeManifest.VERSION, back.getManifestVersion());
        assertEquals(origin.getHeadline(), back.getHeadline());
        assertEquals(origin.getRiskLevel(), back.getRiskLevel());
        // 只比到毫秒：JsonUtils 的 TimestampLocalDateTimeSerializer 把 LocalDateTime 写成 epochMilli，
        // 纳秒在线上格式里根本不存在，不是「读回来丢了」，是「写出去就没带」
        assertEquals(origin.getGeneratedAt().truncatedTo(ChronoUnit.MILLIS), back.getGeneratedAt());
        assertEquals(1, back.getChanges().size());
        assertEquals(ChangeKind.TABLE_CREATE, back.getChanges().get(0).getKind());
        assertEquals("biz_employee", back.getChanges().get(0).getTarget().get("tableName"));
        assertEquals(origin.getApprovals().get(0).getDownstreamKinds(),
                back.getApprovals().get(0).getDownstreamKinds());
        assertEquals(origin.getStats().getTables(), back.getStats().getTables());
    }

    @Test
    @DisplayName("同一份 DSL 两次生成的清单逐字节相同（generatedAt 除外）")
    void buildIsDeterministic() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"gate","type":"hitl.confirm","name":"人工确认"},
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}},
                {"id":"pub","type":"tool","name":"发布","config":{"toolId":"form_publish_bundle"}},
                {"id":"ghost","type":"tool","name":"打错的键","config":{"toolId":"datamodel_lst"}}
                """, """
                {"from":"START","to":"gate"},{"from":"gate","to":"make"},
                {"from":"make","to":"pub"},{"from":"pub","to":"ghost"},{"from":"ghost","to":"END"}
                """));
        WorkflowChangeManifestBuilder builder = builder();

        ChangeManifest first = builder.build(def, 1);
        ChangeManifest second = builder.build(def, 1);
        first.setGeneratedAt(null);
        second.setGeneratedAt(null);

        // 清单会被冻结进流程变量、也会被两个人前后对比着看，飘一次就没人信了
        assertEquals(JsonUtils.toJsonString(first), JsonUtils.toJsonString(second));
    }

    // ==================== ⑦ 按 hitl 节点裁剪（S2-d3d） ====================
    // 本节钉的不是「裁得对不对」—— 那只是 reachableFrom 的一次调用，S2-b 已经钉过了。
    // 钉的是裁剪<b>带来的三类连带账</b>：汇总要不要重算、干跑要不要看见全图、
    // 以及裁掉的东西有没有被显式记下来。三样都是「界面上看不出区别」的那类缺陷。

    /**
     * 一条足够复杂的 DSL：{@code gate} 之前有一个只读工具和一个建表，
     * 之后有一个存草稿、一个打错的工具键（落 unresolved）、一个第二个确认点
     *
     * <p>四种东西各摆一个在裁剪线两侧，是为了让「哪一类没被裁」这种漏项当场暴露。
     */
    private WorkflowDefDTO scopedDsl() throws Exception {
        return dsl(wrap("""
                {"id":"peek","type":"tool","name":"查表","config":{"toolId":"datamodel_list"}},
                {"id":"make1","type":"tool","name":"建员工表","config":{"toolId":"datamodel_create"}},
                {"id":"gate","type":"hitl.confirm","name":"人工确认"},
                {"id":"save","type":"tool","name":"存草稿","config":{"toolId":"form_save_draft"}},
                {"id":"ghost","type":"tool","name":"打错的键","config":{"toolId":"datamodel_lst"}},
                {"id":"late","type":"hitl.confirm","name":"事后确认"}
                """, """
                {"from":"START","to":"peek"},{"from":"peek","to":"make1"},{"from":"make1","to":"gate"},
                {"from":"gate","to":"save"},{"from":"save","to":"ghost"},
                {"from":"ghost","to":"late"},{"from":"late","to":"END"}
                """));
    }

    /** 给两个写工具配上专用抽取器：不然两项都落 GENERIC_TOOL，headline 就看不出差别了 */
    private WorkflowChangeManifestBuilder scopedBuilder() {
        return builder(new StubExtractor("datamodel_create", ChangeKind.TABLE_CREATE),
                new StubExtractor("form_save_draft", ChangeKind.FORM_CREATE));
    }

    /**
     * 让 mock 干跑器按节点 id 发结论，并返回它在<b>被调用那一刻</b>看到的变更项数
     *
     * <p>「那一刻」是这个返回值的全部意义：裁剪排在干跑之后，故那一刻必须是全图。
     */
    private List<Integer> stubDryRun(Map<String, DryRunResult> byNodeId) {
        List<Integer> seen = new ArrayList<>();
        doAnswer(invocation -> {
            ChangeManifest m = invocation.getArgument(0);
            seen.add(m.getChanges().size());
            for (ChangeItem item : m.getChanges()) {
                DryRunResult result = byNodeId.get(item.getNodeId());
                if (result != null) {
                    item.setDryRun(result);
                }
            }
            return null;
        }).when(dryRunner).enrich(any(), any());
        return seen;
    }

    @Test
    @DisplayName("从 hitl 节点裁剪：变更、说不清的部分、其他确认点、只读工具计数四样都只剩子图内的")
    void scopingKeepsOnlyWhatHappensAfterTheGate() throws Exception {
        ChangeManifest m = scopedBuilder().build(scopedDsl(), 1, "gate");

        assertEquals(List.of("save"), m.getChanges().stream().map(ChangeItem::getNodeId).toList());
        assertEquals(List.of("ghost"), m.getUnresolved().stream().map(UnresolvedItem::getNodeId).toList());
        // gate 自己也要留着：这份清单是给他看的，卡片得锚在他站着的那个节点上
        assertEquals(List.of("gate", "late"),
                m.getApprovals().stream().map(ApprovalItem::getNodeId).toList());
        assertEquals(0, m.getStats().getReadToolCount(), "peek 在 gate 之前，不该算进「扫过了」");
    }

    @Test
    @DisplayName("🔴 裁完必须重算汇总：headline / stats / 总风险都只说看得见的那部分")
    void statsAndHeadlineAreRecomputedAfterPruning() throws Exception {
        WorkflowDefDTO def = scopedDsl();
        ChangeManifest whole = scopedBuilder().build(def, 1);
        ChangeManifest scoped = scopedBuilder().build(def, 1, "gate");

        // 先钉住未裁剪的那份，否则「裁完重算了」与「压根没数」共用同一个绿色
        assertEquals("将新建 1 张数据表、新建 1 张表单", whole.getHeadline());
        assertEquals("IRREVERSIBLE", whole.getRiskLevel());
        assertEquals(1, whole.getStats().getTables());

        assertEquals("将新建 1 张表单", scoped.getHeadline(),
                "标题还说 3 张表、changes 里只有 1 项，是这一刀最容易漏的账");
        assertEquals(1, scoped.getChanges().size());
        assertEquals(0, scoped.getStats().getTables());
        assertEquals(1, scoped.getStats().getForms());
        assertEquals(1, scoped.getStats().getWriteToolCount());
        assertEquals("LOW", scoped.getRiskLevel(), "不可逆的建表在 gate 之前，不该再让整份清单标红");
        assertEquals("低风险", scoped.getRiskDescription());
        assertEquals(0, scoped.getStats().getIrreversibleCount());
        assertEquals("均为可逆的写操作，改错了可以改回来；另有 1 处说不清的部分，请务必查看",
                scoped.getRiskNote());
    }

    @Test
    @DisplayName("🔴 干跑的两个计数只数看得见的项（探的是全图、显示的是子图，不重算就会对不上）")
    void dryRunCountersOnlyCountVisibleItems() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"make1","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}},
                {"id":"gate","type":"hitl.confirm","name":"人工确认"},
                {"id":"save","type":"tool","name":"存草稿","config":{"toolId":"form_save_draft"}},
                {"id":"pub","type":"tool","name":"发布","config":{"toolId":"form_publish_bundle"}}
                """, """
                {"from":"START","to":"make1"},{"from":"make1","to":"gate"},{"from":"gate","to":"save"},
                {"from":"save","to":"pub"},{"from":"pub","to":"END"}
                """));
        stubDryRun(Map.of(
                "make1", DryRunResult.rejected("表描述太短"),
                "save", DryRunResult.passed("桩：通过"),
                "pub", DryRunResult.notProbed("桩：没探")));
        WorkflowChangeManifestBuilder builder = builder(
                new StubExtractor("datamodel_create", ChangeKind.TABLE_CREATE),
                new StubExtractor("form_save_draft", ChangeKind.FORM_CREATE),
                new StubExtractor("form_publish_bundle", ChangeKind.FORM_PUBLISH));

        // 🔴 先钉住未裁剪那一份的两个计数都真的数出来了。少了这一步，
        //    下面「裁完 rejected 变 0」就与「finish 压根没数 rejected」共用同一个绿色
        //    —— 与 ChangeManifestDryRunnerTest 里那条通则同源：否定式断言前面要有肯定式断言。
        ChangeManifest whole = builder.build(def, 1);
        assertEquals(3, whole.getStats().getWriteToolCount());
        assertEquals(1, whole.getStats().getDryRunRejected());
        assertEquals(1, whole.getStats().getDryRunNotProbed());

        ChangeManifest m = builder.build(def, 1, "gate");

        assertEquals(2, m.getStats().getWriteToolCount());
        assertEquals(0, m.getStats().getDryRunRejected(), "被拒的那项在 gate 之前，已经不在清单里了");
        assertEquals(1, m.getStats().getDryRunNotProbed());
    }

    @Test
    @DisplayName("🔴 干跑必须在裁剪之前跑：它那一刻看到的是全图，否则「上游会建这张表」就看不见了")
    void dryRunSeesTheWholeGraphBeforeScoping() throws Exception {
        List<Integer> seenByDryRun = stubDryRun(Map.of());

        ChangeManifest m = scopedBuilder().build(scopedDsl(), 1, "gate");

        assertEquals(1, m.getChanges().size(), "先确认最终清单确实被裁过，否则下面那条断言没有意义");
        assertEquals(List.of(2), seenByDryRun,
                "干跑器被调用时清单里应当是全图那 2 项。先裁再跑的话，"
                        + "「建表 → 审批 → 建表单」这种最标准的编排会被判成「主表不存在，会被拒」");
    }

    @Test
    @DisplayName("裁剪绝不静默：scope 里记下裁掉了多少，并给一句能直接印在确认卡上的人话")
    void pruningIsNeverSilent() throws Exception {
        ChangeManifest m = scopedBuilder().build(scopedDsl(), 1, "gate");

        ScopeInfo scope = m.getScope();
        assertNotNull(scope);
        assertEquals("gate", scope.getFromNodeId());
        assertEquals("人工确认", scope.getFromNodeName());
        assertTrue(scope.isPruned());
        assertEquals(1, scope.getHiddenChanges());
        assertEquals(0, scope.getHiddenUnresolved(), "ghost 在 gate 之后，没被裁");
        assertEquals(0, scope.getHiddenApprovals(), "late 在 gate 之后，没被裁");
        assertEquals(1, scope.getHiddenReadTools());
        // 逐字断言：这句话是直接给用户看的，且要守本模块的留白规则（见 SummarySpacingTest）
        assertEquals("本清单只覆盖「人工确认」及之后的动作；此前已发生的 1 项变更、1 个只读工具没有列出。"
                + "要看整条流程的全部影响面，请不带 fromNodeId 重新生成", scope.getNote());
    }

    @Test
    @DisplayName("🔴 起算点在 DSL 里找不到时一项都不裁 —— 空子图会裁出一张「本次不产生任何写操作」的假清单")
    void unknownFromNodeIdPrunesNothing() throws Exception {
        ChangeManifest m = scopedBuilder().build(scopedDsl(), 1, "nope");

        // reachableFrom 无条件把起点塞进结果集、不校验它是不是真节点，
        // 所以「当成空子图」这种写法会稳稳裁出一张空清单 —— 那是看起来最安全、其实最危险的一种
        assertEquals(2, m.getChanges().size());
        assertEquals("将新建 1 张数据表、新建 1 张表单", m.getHeadline());
        assertEquals(1, m.getStats().getReadToolCount());
        ScopeInfo scope = m.getScope();
        assertNotNull(scope);
        assertFalse(scope.isPruned(), "没裁掉东西就不该让渲染层显示「已裁剪」那条横幅");
        assertEquals(0, scope.getHiddenChanges());
        assertEquals("没能按「nope」裁剪：这份 DSL 里没有 id 为它的节点。为免漏报，本清单仍覆盖整条流程",
                scope.getNote());
    }

    @Test
    @DisplayName("不传 fromNodeId（或传空白）时 scope 为 null，清单覆盖整条流程")
    void blankFromNodeIdLeavesTheManifestWhole() throws Exception {
        WorkflowDefDTO def = scopedDsl();

        assertNull(scopedBuilder().build(def, 1).getScope());
        assertNull(scopedBuilder().build(def, 1, null).getScope());
        ChangeManifest blank = scopedBuilder().build(def, 1, "   ");
        assertNull(blank.getScope(), "空白与 null 是同一个意思：不裁剪");
        assertEquals(2, blank.getChanges().size());
        assertEquals(1, blank.getStats().getReadToolCount());
    }

    @Test
    @DisplayName("🔴 裁到一项不剩时，headline 与「真的没有写操作」一模一样 —— 只有 scope 能区分两者")
    void scopingFromATerminalNodeYieldsAnEmptyButHonestManifest() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"make1","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}},
                {"id":"make2","type":"tool","name":"再建一张","config":{"toolId":"datamodel_create"}},
                {"id":"tail","type":"hitl.confirm","name":"末尾确认"}
                """, """
                {"from":"START","to":"make1"},{"from":"make1","to":"make2"},
                {"from":"make2","to":"tail"},{"from":"tail","to":"END"}
                """));
        WorkflowChangeManifestBuilder builder = builder(
                new StubExtractor("datamodel_create", ChangeKind.TABLE_CREATE));

        ChangeManifest scoped = builder.build(def, 1, "tail");
        assertEquals("本次不产生任何写操作", scoped.getHeadline());
        assertTrue(scoped.getChanges().isEmpty());

        // 同一句话的另一种来历：同样从 tail 裁剪，但这条流程真的一个写工具都没有
        ChangeManifest genuinelyEmpty = builder.build(
                dsl(wrap("""
                        {"id":"tail","type":"hitl.confirm","name":"末尾确认"}
                        """, """
                        {"from":"START","to":"tail"},{"from":"tail","to":"END"}
                        """)), 1, "tail");
        assertEquals("本次不产生任何写操作", genuinelyEmpty.getHeadline());
        assertFalse(genuinelyEmpty.getScope().isPruned(), "没裁掉任何东西");
        assertEquals(0, genuinelyEmpty.getScope().getHiddenChanges());

        // ⟹ headline 与调用形状都一样，区分它们的只有 pruned。审批人看见「不产生任何写操作」就点同意，
        //    而实际上上游已经建了两张不可逆的表 —— 这就是「静默裁剪」的代价
        ScopeInfo scope = scoped.getScope();
        assertTrue(scope.isPruned());
        assertEquals(2, scope.getHiddenChanges());
        assertEquals("本清单只覆盖「末尾确认」及之后的动作；此前已发生的 2 项变更没有列出。"
                + "要看整条流程的全部影响面，请不带 fromNodeId 重新生成", scope.getNote());
    }

    @Test
    @DisplayName("只读工具计数只数子图内的（那个数存在的意义是「证明扫过了」，得对着看得见的部分说）")
    void readToolCountOnlyCountsTheSubgraph() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"peek1","type":"tool","name":"查表","config":{"toolId":"datamodel_list"}},
                {"id":"gate","type":"hitl.confirm","name":"人工确认"},
                {"id":"peek2","type":"tool","name":"体检","config":{"toolId":"form_inspect"}},
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}}
                """, """
                {"from":"START","to":"peek1"},{"from":"peek1","to":"gate"},{"from":"gate","to":"peek2"},
                {"from":"peek2","to":"make"},{"from":"make","to":"END"}
                """));
        WorkflowChangeManifestBuilder builder = builder(
                new StubExtractor("datamodel_create", ChangeKind.TABLE_CREATE));

        assertEquals(2, builder.build(def).getStats().getReadToolCount());
        ChangeManifest scoped = builder.build(def, 1, "gate");
        assertEquals(1, scoped.getStats().getReadToolCount());
        assertEquals(1, scoped.getScope().getHiddenReadTools());
    }

    @Test
    @DisplayName("downstreamKinds 在裁剪前后一致（它在裁剪之前算，但算的是「这个确认点之后」，故不受影响）")
    void downstreamKindsAreUnaffectedByScoping() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"make1","type":"tool","name":"建表","config":{"toolId":"datamodel_create"}},
                {"id":"gate","type":"hitl.confirm","name":"人工确认"},
                {"id":"save","type":"tool","name":"存草稿","config":{"toolId":"form_save_draft"}},
                {"id":"pub","type":"tool","name":"发布","config":{"toolId":"form_publish_bundle"}}
                """, """
                {"from":"START","to":"make1"},{"from":"make1","to":"gate"},{"from":"gate","to":"save"},
                {"from":"save","to":"pub"},{"from":"pub","to":"END"}
                """));
        WorkflowChangeManifestBuilder builder = builder(
                new StubExtractor("datamodel_create", ChangeKind.TABLE_CREATE),
                new StubExtractor("form_save_draft", ChangeKind.FORM_CREATE),
                new StubExtractor("form_publish_bundle", ChangeKind.FORM_PUBLISH));

        List<String> whole = builder.build(def).getApprovals().get(0).getDownstreamKinds();
        assertEquals(List.of("FORM_CREATE", "FORM_PUBLISH"), whole, "建表在 gate 之前，本就不算它的下游");
        assertEquals(whole, builder.build(def, 1, "gate").getApprovals().get(0).getDownstreamKinds());
    }

    @Test
    @DisplayName("没有 id 的变更项裁不掉：它在图上没有位置，说不清就不许悄悄丢掉")
    void itemWithoutNodeIdIsKeptNotHidden() throws Exception {
        // 这个节点连 id 都没有，也没法连边，因此永远不可能落进 reachableFrom 的结果集里。
        // 按 nodeId 一刀切就会把它裁掉 —— 而它是一项 IRREVERSIBLE 的建表
        WorkflowDefDTO def = dsl(wrap("""
                {"type":"tool","name":"没编号的建表","config":{"toolId":"datamodel_create"}},
                {"id":"gate","type":"hitl.confirm","name":"人工确认"},
                {"id":"save","type":"tool","name":"存草稿","config":{"toolId":"form_save_draft"}}
                """, """
                {"from":"START","to":"gate"},{"from":"gate","to":"save"},{"from":"save","to":"END"}
                """));

        ChangeManifest m = scopedBuilder().build(def, 1, "gate");

        assertEquals(2, m.getChanges().size());
        assertEquals(1, m.getChanges().stream().filter(c -> c.getNodeId() == null).count());
        assertEquals(0, m.getScope().getHiddenChanges());
    }

    @Test
    @DisplayName("scope 跟着清单一起过 JSON（它要被冻结进 BPM 流程变量）")
    void scopeSurvivesJsonRoundTrip() throws Exception {
        ChangeManifest origin = scopedBuilder().build(scopedDsl(), 1, "gate");

        ChangeManifest back = JsonUtils.parseObject(JsonUtils.toJsonString(origin), ChangeManifest.class);

        assertNotNull(back.getScope());
        assertEquals(origin.getScope().getNote(), back.getScope().getNote());
        assertEquals(origin.getScope().getFromNodeId(), back.getScope().getFromNodeId());
        assertEquals(origin.getScope().isPruned(), back.getScope().isPruned());
        assertEquals(origin.getScope().getHiddenChanges(), back.getScope().getHiddenChanges());
        assertEquals(origin.getHeadline(), back.getHeadline());
    }

    // ==================== ⑧ 溯源结果接进清单（S2-e3b） ====================
    // 溯源算法本身由 UntrustedInputTracerTest 逐条钉住，本节只钉「接线」这三件事：
    // builder 真的调了它、裁剪按汇剪它、以及剪了要记账。三件都是「编译得过、界面上看不出来」的那类。

    @Test
    @DisplayName("入参里的 {{$.input}} 变成一条 untrustedInputs；同一条链上写死字面量的那个节点一条都不报")
    void untrustedFlowsLandInTheManifest() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"make","type":"tool","name":"建表","config":{"toolId":"datamodel_create",
                  "args":{"tableName":"{{$.input}}"}}},
                {"id":"plain","type":"tool","name":"再建一张","config":{"toolId":"datamodel_create",
                  "args":{"tableName":"biz_fixed"}}}
                """, """
                {"from":"START","to":"make"},{"from":"make","to":"plain"},{"from":"plain","to":"END"}
                """));

        ChangeManifest m = builder(new StubExtractor("datamodel_create", ChangeKind.TABLE_CREATE))
                .build(def, 1);

        // 这一条同时是肯定式对照与否定式断言：列表非空证明溯源真的跑了（否则 builder 忘调 trace
        // 会得到同样的空列表），而 plain 不在里面证明「作者写死的字面量」没被误报成外部输入
        assertEquals(List.of("make"),
                m.getUntrustedInputs().stream().map(UntrustedInput::getSinkNodeId).toList());
        UntrustedInput flow = m.getUntrustedInputs().get(0);
        assertEquals("args.tableName", flow.getSinkArg());
        assertEquals("TRIGGER_CHAT", flow.getSourceKind());
        assertEquals(0, flow.getDepth());
        // summary 逐字断言：四个渲染点里三个只显示这一行，且它不受 SummarySpacingTest 覆盖
        // （那条只扫 ToolChangeExtractor 的实现），故留白规则由这里钉
        assertEquals("聊天用户输入（input）会流进节点「建表」的工具入参 args.tableName", flow.getSummary());
    }

    @Test
    @DisplayName("裁剪按「汇」剪流向：源在上游照样留着，剪掉的条数进 scope 与那句人话，并跟着过 JSON")
    void scopingPrunesUntrustedFlowsBySink() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"before","type":"llm","name":"上游总结","config":{"prompt":"{{$.input}}"}},
                {"id":"gate","type":"hitl.confirm","name":"人工确认"},
                {"id":"after","type":"tool","name":"建表","config":{"toolId":"datamodel_create",
                  "args":{"tableName":"{{$.input}}"}}}
                """, """
                {"from":"START","to":"before"},{"from":"before","to":"gate"},
                {"from":"gate","to":"after"},{"from":"after","to":"END"}
                """));
        WorkflowChangeManifestBuilder builder =
                builder(new StubExtractor("datamodel_create", ChangeKind.TABLE_CREATE));

        // 先钉住未裁剪那份两条都在，否则「裁掉了一条」与「本来就只有一条」分不出来
        assertEquals(List.of("before", "after"), builder.build(def, 1).getUntrustedInputs()
                .stream().map(UntrustedInput::getSinkNodeId).toList());

        ChangeManifest scoped = builder.build(def, 1, "gate");

        assertEquals(List.of("after"),
                scoped.getUntrustedInputs().stream().map(UntrustedInput::getSinkNodeId).toList());
        assertEquals(1, scoped.getScope().getHiddenUntrusted());
        // 这一份被裁掉的只有流向：llm 节点不产生变更项、不是只读工具、也不进 unresolved，
        // 故 note 里那一项就是全部
        assertEquals("本清单只覆盖「人工确认」及之后的动作；此前已发生的 1 处外部输入的流向没有列出。"
                + "要看整条流程的全部影响面，请不带 fromNodeId 重新生成", scoped.getScope().getNote());

        ChangeManifest back = JsonUtils.parseObject(JsonUtils.toJsonString(scoped), ChangeManifest.class);
        assertEquals(scoped.getUntrustedInputs().get(0).getSummary(),
                back.getUntrustedInputs().get(0).getSummary());
        assertEquals(scoped.getScope().getHiddenUntrusted(), back.getScope().getHiddenUntrusted());
    }

    @Test
    @DisplayName("🔴 只裁掉了流向时 pruned 也必须为真 —— 渲染层靠它决定要不要显示「已裁剪」那条横幅")
    void prunedIsTrueWhenOnlyUntrustedFlowsWereHidden() throws Exception {
        // 上游只有一个 llm 节点：四个既有计数（变更/说不清/其他确认点/只读工具）全是 0，
        // 唯一被裁掉的东西就是那条「聊天用户输入原样进了提示词」
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"summarize","type":"llm","name":"总结","config":{"prompt":"{{$.input}}"}},
                {"id":"gate","type":"hitl.confirm","name":"人工确认"}
                """, """
                {"from":"START","to":"summarize"},{"from":"summarize","to":"gate"},{"from":"gate","to":"END"}
                """));

        assertEquals(1, builder().build(def, 1).getUntrustedInputs().size(),
                "先确认这一份真的溯源出了一条，否则下面那些断言全是空转");

        ChangeManifest scoped = builder().build(def, 1, "gate");

        assertTrue(scoped.getUntrustedInputs().isEmpty());
        ScopeInfo scope = scoped.getScope();
        assertEquals(0, scope.getHiddenChanges());
        assertEquals(0, scope.getHiddenUnresolved());
        assertEquals(0, scope.getHiddenApprovals());
        assertEquals(0, scope.getHiddenReadTools());
        assertEquals(1, scope.getHiddenUntrusted());
        assertTrue(scope.isPruned(),
                "pruned 若只从既有那四个计数判，这一份就会说「没裁」—— 而它裁掉的恰恰是唯一那条"
                        + "「聊天用户输入原样进了提示词」，正是审批人最该看见的一条。"
                        + "与 scopingFromATerminalNodeYieldsAnEmptyButHonestManifest 同一条道理："
                        + "静默裁剪的代价是用户据一份不完整的东西点了同意");
    }

    // ==================== ⑨ waits[] 也参与裁剪与记账（S6 追加） ====================
    // ManifestScoper 裁的是**六个**列表，而 ⑦⑧ 两节只钉住了五个：变更 / 说不清 / 其他确认点 /
    // 只读工具 / 溯源流向。第六个 waits[] 是 S6 才加的（裁定⑬：审批人有权知道「我不在的时候
    // 谁会来唤醒它」），加的时候顺手把 hiddenWaits 也算进了 pruned 的求和 —— 但**没有一条测试看着它**。
    // 少算这一项的失败形态与 ⑧ 那条完全同形：渲染层不显示「已裁剪」横幅，用户据不完整的东西点同意。

    @Test
    @DisplayName("🔴 裁掉了 wait(event) 等待点时 hiddenWaits 要带数、pruned 要为真 —— 六个计数少算一个就是静默裁剪")
    void scopingCountsHiddenWaitsToo() throws Exception {
        // 上游那个 wait(event) 是本用例**唯一**的裁剪对象：它不产生变更项、不是只读工具、
        // 不进 unresolved / approvals，config 里也没有 {{$.input}} ⟹ 另外五个计数天生全 0。
        // 这正是它值得单独钉一发的理由：pruned 若仍从五个计数算，这一份会答「没裁」。
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"before","type":"wait","name":"等订单中心回调",
                  "config":{"waitType":"event","eventKey":"order_paid",
                    "externalWakeup":{"owner":"订单中心","endpoint":"POST /agent/workflow/event"}}},
                {"id":"gate","type":"hitl.confirm","name":"人工确认"}
                """, """
                {"from":"START","to":"before"},{"from":"before","to":"gate"},{"from":"gate","to":"END"}
                """));

        // 先钉住未裁剪那份：waits[] 真的建出来了、externalWakeup 的字段原样带出。
        // 少了这一步，下面「裁掉了一条」与「压根没建过」分不出来 —— 后者同样让 waits 为空、
        // 同样让 hiddenWaits 为 0，断言全绿而清单其实少了一整块。
        List<WaitItem> all = builder().build(def, 1).getWaits();
        assertEquals(1, all.size(), "先确认 waits[] 真的建出来了，否则下面全是空转");
        assertEquals("before", all.get(0).getNodeId());
        assertEquals("等订单中心回调", all.get(0).getNodeName());
        assertTrue(all.get(0).isDeclared(), "填了 externalWakeup 就该是「已声明」");
        assertEquals("订单中心", all.get(0).getOwner());
        assertEquals("POST /agent/workflow/event", all.get(0).getEndpoint());

        ChangeManifest scoped = builder().build(def, 1, "gate");

        assertTrue(scoped.getWaits().isEmpty(), "gate 之后没有任何节点，等待点该被裁光");
        ScopeInfo scope = scoped.getScope();
        assertEquals(1, scope.getHiddenWaits());
        assertEquals(0, scope.getHiddenChanges());
        assertEquals(0, scope.getHiddenUnresolved());
        assertEquals(0, scope.getHiddenApprovals());
        assertEquals(0, scope.getHiddenReadTools());
        assertEquals(0, scope.getHiddenUntrusted());
        assertTrue(scope.isPruned(),
                "pruned 若从既有那五个计数判，这一份就会说「没裁」—— 而它裁掉的恰是"
                        + "「我不在的时候谁来唤醒它」，无头流程漏掉这句就是永久卡死。"
                        + "与 prunedIsTrueWhenOnlyUntrustedFlowsWereHidden 同一条道理");
        // note 逐字断言：它是渲染层唯一那句人话，而 waits 那一项的位置（其他确认点之后、只读工具之前）
        // 只在 buildNote 里写着，改顺序不会红别处
        assertEquals("本清单只覆盖「人工确认」及之后的动作；此前已发生的 1 个外部事件等待点没有列出。"
                + "要看整条流程的全部影响面，请不带 fromNodeId 重新生成", scope.getNote());

        // 这个数要跟着过 JSON：冻结进 BPM 流程变量的就是序列化后的那一份
        ChangeManifest back = JsonUtils.parseObject(JsonUtils.toJsonString(scoped), ChangeManifest.class);
        assertEquals(scope.getHiddenWaits(), back.getScope().getHiddenWaits());
        assertEquals(scope.getNote(), back.getScope().getNote());
    }

    @Test
    @DisplayName("wait(duration) 不进 waits[]：定时等待没有外部唤醒方，报出来只会稀释「等谁」这个信号")
    void durationWaitsAreNotReportedAsExternalWaits() throws Exception {
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"snooze","type":"wait","name":"等十分钟",
                  "config":{"waitType":"duration","durationSeconds":600}},
                {"id":"gate","type":"hitl.confirm","name":"人工确认"}
                """, """
                {"from":"START","to":"snooze"},{"from":"snooze","to":"gate"},{"from":"gate","to":"END"}
                """));

        // 这一条是否定式对照：它与上一发的差别只有 waitType 一个字，
        // 于是「waits[] 非空」这件事只可能来自 event 那一支，不可能是「所有 wait 都进」
        assertTrue(builder().build(def, 1).getWaits().isEmpty(),
                "定时等待没有外部唤醒方，不该进 waits[]");

        ScopeInfo scope = builder().build(def, 1, "gate").getScope();
        assertEquals(0, scope.getHiddenWaits());
        assertFalse(scope.isPruned(),
                "duration 等待点既没进清单、也就无从被裁 ⟹ 这一份不该显示「已裁剪」横幅");
    }

    @Test
    @DisplayName("🔴 waitType 带空格（\" event \"）仍要进 waits[] —— 逐字比较时这一支会静默漏掉（缺陷 E2）")
    void paddedWaitTypeStillLandsInWaits() throws Exception {
        // E2 之前这一支写的是 "event".equals(node.configStr("waitType"))：逐字比、不 trim。
        // 而编译期 validateWaitConfig 与校验器 V14 都 trim ⟹ " event " 一路发布成功、
        // V14 也照样要求作者声明唤醒方（作者照做了），清单却少一条 waits[]。
        // 「清单说没有外部唤醒方」与「运行期真没人唤醒」在界面上长得一样，作者与审批人两边都看不出少了东西。
        // 现在两侧共用 WaitTypeEnum.parse（会 trim），这一格不再可能分叉。
        WorkflowDefDTO def = dsl(wrap("""
                {"id":"before","type":"wait","name":"等订单中心回调",
                  "config":{"waitType":" event ","eventKey":"order_paid",
                    "externalWakeup":{"owner":"订单中心","endpoint":"POST /agent/workflow/event"}}},
                {"id":"gate","type":"hitl.confirm","name":"人工确认"}
                """, """
                {"from":"START","to":"before"},{"from":"before","to":"gate"},{"from":"gate","to":"END"}
                """));

        List<WaitItem> all = builder().build(def, 1).getWaits();
        assertEquals(1, all.size(), "带空格的 event 也是 event，waits[] 不许少这一条");
        assertEquals("before", all.get(0).getNodeId());
        assertEquals("订单中心", all.get(0).getOwner(), "externalWakeup 的字段也要照常带出");

        // 裁剪记账同样要认得它：漏了这一支，hiddenWaits 会是 0、pruned 会是 false，
        // 与「压根没有等待点」在渲染层完全同形（见 scopingCountsHiddenWaitsToo 那条的道理）
        assertEquals(1, builder().build(def, 1, "gate").getScope().getHiddenWaits());
    }

}
