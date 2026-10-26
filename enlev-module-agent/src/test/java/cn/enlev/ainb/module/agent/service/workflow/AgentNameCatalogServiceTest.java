package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentNameCatalogRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentNameCatalogRespVO.ToolItem;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 名称目录 Service 的单元测试
 *
 * <p>mock 三个数据源（自定义工具 Service / 流程定义 Service / 表单设计 api）+ 真 registry。
 * 重点测<b>合流顺序</b>与<b>去重</b>——这两处出错的表现是「下拉里显示 A、真正被调用的是 B」，
 * 从界面上完全看不出来，只能靠测试钉住。
 *
 * <p>另一半钉的是<b>声明值与生效值不许串</b>（D4-22）：目录里 {@code risk} 与 {@code declaredRisk}
 * 是两份口径不同的值，各道闸读后者、维护页改前者。串了不会有任何报错，
 * 只会让设计器把降过险的写工具画成「无需确认」。
 *
 * @author 走进新时代
 */
public class AgentNameCatalogServiceTest extends BaseMockitoUnitTest {

    /** 覆盖快照挂在哪个租户下；不设租户上下文时生效值恒等于声明值（registry 直接返回 null 覆盖） */
    private static final Long TENANT = 1L;

    @InjectMocks
    private AgentNameCatalogServiceImpl service;

    @Mock
    private AgentToolDefService toolDefService;
    @Mock
    private AgentWorkflowDefService defService;
    @Mock
    private FormDesignApi formDesignApi;
    @Spy
    private AgentToolRegistry toolRegistry = new AgentToolRegistry();

    @AgentTool(name = "datamodel_create", displayName = "创建数据表", groupName = "数据建模",
            description = "按给定结构新建一张业务表", permission = "infra:data-model:create",
            risk = RiskLevel.IRREVERSIBLE)
    static class CreateTool implements cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction {
        @Override
        public Object execute(java.util.Map<String, Object> params) {
            return null;
        }
    }

    @AgentTool(name = "datamodel_list", displayName = "查询数据表列表", groupName = "数据建模",
            permission = "infra:data-model:query", risk = RiskLevel.READ_ONLY)
    static class ListTool implements cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction {
        @Override
        public Object execute(java.util.Map<String, Object> params) {
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        // 注册顺序刻意与排序结果不同（list 在 create 之前），才能验出「输出顺序来自排序而非注册顺序」
        toolRegistry.register(ListTool.class, new ListTool());
        toolRegistry.register(CreateTool.class, new CreateTool());
        lenient().when(toolDefService.getToolDefListIncludingDisabled()).thenReturn(List.of());
        lenient().when(defService.listPublishedSimple()).thenReturn(List.of());
        lenient().when(formDesignApi.getWidgetContracts()).thenReturn(List.of());
        lenient().when(formDesignApi.getSearchOperatorList()).thenReturn(List.of());
    }

    /**
     * 🔴 必须清租户上下文：它是 ThreadLocal，而 {@code toolRegistry} 是 {@code @Spy} 真实例、
     * 覆盖缓存也真挂在它身上。漏了这一步，本类里降过险的那条用例会顺着线程泄给同类中其余用例，
     * 表现是「单跑绿、整类跑红」这种最难查的顺序依赖。
     */
    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private AgentToolDefDO customTool(String key, String displayName, String groupName, String risk) {
        AgentToolDefDO row = new AgentToolDefDO();
        row.setToolKey(key);
        row.setDisplayName(displayName);
        row.setGroupName(groupName);
        row.setRiskLevel(risk);
        row.setSource("DATA_INTERFACE");
        row.setEnabled(true);
        return row;
    }

    private ToolItem tool(AgentNameCatalogRespVO catalog, String name) {
        return catalog.getTools().stream().filter(t -> name.equals(t.getName())).findFirst().orElse(null);
    }

    @Test
    @DisplayName("内置与自定义工具合流，source 区分两者")
    void testMergeBothSources() {
        when(toolDefService.getToolDefListIncludingDisabled())
                .thenReturn(List.of(customTool("sales_daily_report", "销售日报", "报表", "READ_ONLY")));

        AgentNameCatalogRespVO catalog = service.getNameCatalog();

        assertEquals(3, catalog.getTools().size());
        ToolItem builtin = tool(catalog, "datamodel_create");
        assertEquals("创建数据表", builtin.getDisplayName());
        assertEquals(AgentNameCatalogRespVO.SOURCE_BUILTIN, builtin.getSource());
        assertEquals("IRREVERSIBLE", builtin.getRisk());
        assertEquals("不可逆", builtin.getRiskDescription());
        assertTrue(builtin.getRequireConfirm());
        assertFalse(builtin.getOverridden(), "无租户上下文 → 没有覆盖行");

        ToolItem custom = tool(catalog, "sales_daily_report");
        assertEquals("销售日报", custom.getDisplayName());
        assertEquals("DATA_INTERFACE", custom.getSource());
        assertFalse(custom.getRequireConfirm(), "只读工具不进 Plan");
    }

    @Test
    @DisplayName("🔴 与内置工具重名 → 只出内置那一行（与 AgentToolExecutor 的 registry-first 逐字同口径）")
    void testBuiltinWinsOnNameConflict() {
        // 登记时本就会拒重名；能走到这里只有一种可能：内置工具是后加的、占了已有自定义工具的名字。
        // 此时若两行都出，下拉里会有两个「创建数据表」，而真正被调用的是内置那个 —— 静默分叉。
        when(toolDefService.getToolDefListIncludingDisabled())
                .thenReturn(List.of(customTool("datamodel_create", "建表（自定义）", "报表", "READ_ONLY")));

        AgentNameCatalogRespVO catalog = service.getNameCatalog();

        assertEquals(2, catalog.getTools().size(), "重名的自定义行被丢弃，只剩两个内置工具");
        ToolItem winner = tool(catalog, "datamodel_create");
        assertEquals("创建数据表", winner.getDisplayName());
        assertEquals(AgentNameCatalogRespVO.SOURCE_BUILTIN, winner.getSource());
    }

    @Test
    @DisplayName("🔴 自定义工具的 enabled 读真实列值、不写死 true（D4-26）")
    void testCustomEnabledReadsRealColumn() {
        // D4-26 写这条时「停用行」还到不了 buildTools（数据源是 getEnabledToolDefList()，WHERE enabled=true），
        // 钉的是**契约**而不是当下的可达性：字段 javadoc 写的是「生效值」，写死常量就是在契约上撒谎 ——
        // 哪天有人为了修「停用的自定义工具从下拉里整个消失、存量 DSL 里那个键退化成裸机器键」
        // 而把数据源换成全量列表，前端刚加的置灰逻辑（nameCatalog.disabledToolBlock）会读到 true，
        // 把停用的工具画成正常候选，界面主动指反方向，比不显示更坏。
        // 🔴 D4-27 就是那个「哪天」：数据源已换成 getToolDefListIncludingDisabled()，停用行现在真进得来，
        //    本用例从「钉契约」升级成「钉可达行为」。下面那条 testDisabledCustomToolStillInCatalog 是它的正面。
        AgentToolDefDO off = customTool("tool_off", "停用的工具", "报表", "READ_ONLY");
        off.setEnabled(false);
        AgentToolDefDO nullEnabled = customTool("tool_null", "空启用位", "报表", "READ_ONLY");
        nullEnabled.setEnabled(null);
        when(toolDefService.getToolDefListIncludingDisabled()).thenReturn(List.of(
                off, nullEnabled, customTool("tool_on", "启用中的工具", "报表", "READ_ONLY")));

        AgentNameCatalogRespVO catalog = service.getNameCatalog();

        assertFalse(tool(catalog, "tool_off").getEnabled(), "列上写着停用就必须下发停用");
        // null 按停用判，与 AgentToolDefServiceImpl#resolveForGateway 的 Boolean.TRUE.equals 逐字同口径。
        // 建表那一列虽是 NOT NULL DEFAULT b'1'，展示层仍不赌这个：目录必须和网关给出同一个答案，
        // 否则前端置灰了、网关却放行（或反之），两边各说一套正是本模块的头号缺陷。
        assertFalse(tool(catalog, "tool_null").getEnabled(), "null 按停用判，与网关同口径");
        assertTrue(tool(catalog, "tool_on").getEnabled());
    }

    /**
     * 🔴 D4-27 的正面：停用的自定义工具<b>置灰而不是消失</b>
     *
     * <p>此前 {@code buildTools} 的数据源是 {@code getEnabledToolDefList()}（{@code WHERE enabled=true}），
     * 管理员一停用某个自定义工具，它就从目录里<b>整个消失</b>，连锁两件事：
     * <ol>
     *   <li>存量 DSL 里那个键翻不出名字。前端 {@code nameResolver.toolLabel} 查不到，
     *       {@code el-select} 的 {@code getOption} 回落到 {@code currentLabel = value} ——
     *       画布上「销售日报」退化成一串裸机器键，看上去像「这工具不存在」，而它只是被关了；</li>
     *   <li>下拉是 {@code allow-create} 的，用户还能把那个键重新敲回去，敲回去之后<b>一个警告都没有</b>。</li>
     * </ol>
     * 内置工具走的是另一条路（{@code AgentToolRegistry.list()} 本来就返回全部，靠 {@code enabled}
     * 让前端置灰），于是同一个「停用」动作在两类工具上处置不一致 —— 内置的置灰、自定义的消失。
     *
     * <p>🔴 断言顺序刻意是「在不在」→「名字对不对」→「enabled 对不对」：上面那条
     * {@link #testCustomEnabledReadsRealColumn} 也喂了停用行，但它上来就 {@code .getEnabled()}，
     * 行若被某道后置过滤悄悄抹掉，红点是一个 {@code NullPointerException} ——
     * 红是红了，却指不到「目录少了一行」这个真正的病因上，排查的人会先去怀疑 mock 没配好。
     *
     * <p>🔴 前端<b>不需要</b>任何改动：{@code disabledToolBlock} 读的是 {@code t.enabled} 且与
     * {@code source} 无关，所以「下发 + enabled=false」这个形状它本来就会置灰。
     * 本用例钉的正是后端只需把行发出去、不该自己替前端决定「停用的就别发了」。
     */
    @Test
    @DisplayName("🔴 停用的自定义工具仍进目录、且带着中文名（D4-27：置灰而不是消失）")
    void testDisabledCustomToolStillInCatalog() {
        AgentToolDefDO off = customTool("sales_daily_report", "销售日报", "报表", "READ_ONLY");
        off.setEnabled(false);
        when(toolDefService.getToolDefListIncludingDisabled()).thenReturn(List.of(
                off, customTool("sales_hourly_report", "销售时报", "报表", "READ_ONLY")));

        AgentNameCatalogRespVO catalog = service.getNameCatalog();

        ToolItem disabled = tool(catalog, "sales_daily_report");
        assertNotNull(disabled, "停用的自定义工具必须仍在目录里 —— 「消失」就是 D4-27 的缺陷本体");
        // 名字才是这条用例的真正交付物：前端全靠它把存量 DSL 里的机器键翻成中文
        assertEquals("销售日报", disabled.getDisplayName());
        assertFalse(disabled.getEnabled(), "在目录里，但必须标成停用，前端据此置灰、挑不动");
        assertEquals("DATA_INTERFACE", disabled.getSource(), "source 不能被置灰逻辑吃掉，前端还要据此分类");

        // 对照：同一份数据里启用中的那个照旧可挑。没有这半边，「全下发」退化成「全都置灰」也测不出来
        assertTrue(tool(catalog, "sales_hourly_report").getEnabled());

        // 2 个自定义 + 2 个内置 = 4：停用那行没有被排序、去重等任何一道后置处理悄悄抹掉
        assertEquals(4, catalog.getTools().size());
    }

    @Test
    @DisplayName("🔴 自定义工具 risk 列缺失或非法 → 保守回落 HIGH（要确认，不当只读）")
    void testCustomRiskFallsBackToHigh() {
        when(toolDefService.getToolDefListIncludingDisabled()).thenReturn(List.of(
                customTool("tool_blank_risk", "空风险", "", null),
                customTool("tool_bad_risk", "非法风险", "", "WHATEVER")));

        AgentNameCatalogRespVO catalog = service.getNameCatalog();

        for (String name : List.of("tool_blank_risk", "tool_bad_risk")) {
            ToolItem item = tool(catalog, name);
            assertEquals("HIGH", item.getRisk(), name);
            assertEquals("高风险", item.getRiskDescription(), name);
            assertTrue(item.getRequireConfirm(), name + " 必须进 Plan");
        }
    }

    @Test
    @DisplayName("自定义工具展示名/分组留空 → 回落工具键与空串，下拉里不出现空白行")
    void testCustomDisplayNameFallback() {
        when(toolDefService.getToolDefListIncludingDisabled())
                .thenReturn(List.of(customTool("no_label_tool", "  ", null, "READ_ONLY")));

        ToolItem item = tool(service.getNameCatalog(), "no_label_tool");

        assertEquals("no_label_tool", item.getDisplayName());
        assertEquals("", item.getGroupName());
    }

    @Test
    @DisplayName("工具按「分组名（拼音）→ 工具键」排序，输出与注册/查库顺序无关")
    void testToolsAreSortedDeterministically() {
        when(toolDefService.getToolDefListIncludingDisabled()).thenReturn(List.of(
                customTool("zzz_tool", "zzz", "报表", "READ_ONLY"),
                customTool("aaa_tool", "aaa", "数据建模", "READ_ONLY")));

        List<ToolItem> tools = service.getNameCatalog().getTools();

        // 注册顺序是 list → create，查库顺序是 zzz → aaa，两者都不该决定输出。
        // 分组按拼音：报表(bào) < 数据建模(shù)；组内按工具键的 ASCII 序。
        assertEquals(List.of("zzz_tool", "aaa_tool", "datamodel_create", "datamodel_list"),
                tools.stream().map(ToolItem::getName).toList());
    }

    @Test
    @DisplayName("已发布流程同 code 多版本 → 按 code 去重，取排在前面的「当前生效版」")
    void testWorkflowsDedupedByCode() {
        when(defService.listPublishedSimple()).thenReturn(List.of(
                publishedDef("wf_asset_check", "资产盘点 v3", 3),
                publishedDef("wf_asset_check", "资产盘点 v2", 2),
                publishedDef("wf_onboard", null, 1)));

        AgentNameCatalogRespVO catalog = service.getNameCatalog();

        assertEquals(2, catalog.getWorkflows().size());
        assertEquals("资产盘点 v3", catalog.getWorkflows().get(0).getName(),
                "Mapper 已按 primary_flag DESC, version DESC 排序，首行即生效版");
        assertEquals("wf_onboard", catalog.getWorkflows().get(1).getName(), "名字空着回落 code");
    }

    @Test
    @DisplayName("控件目录不过滤 supported/deprecated —— 历史表单引用的旧控件也要翻得出中文名")
    void testWidgetLabelsIncludeUnsupported() {
        when(formDesignApi.getWidgetContracts()).thenReturn(List.of(
                widget("input", "单行文本", true),
                widget("oldRichText", "旧富文本", false),
                widget("noLabel", null, true)));

        AgentNameCatalogRespVO catalog = service.getNameCatalog();

        assertEquals("单行文本", catalog.getWidgetLabels().get("input"));
        assertEquals("旧富文本", catalog.getWidgetLabels().get("oldRichText"));
        assertEquals("noLabel", catalog.getWidgetLabels().get("noLabel"), "label 空着回落 type");
    }

    @Test
    @DisplayName("操作符原样搬运；🔴 不带 sqlFragment（拼接细节不出 api 层）")
    void testOperatorsCarryNoSqlFragment() {
        FormDesignApi.SearchOperatorRespDTO like = new FormDesignApi.SearchOperatorRespDTO();
        like.setOperator("LIKE");
        like.setLabel("包含");
        when(formDesignApi.getSearchOperatorList()).thenReturn(List.of(like));

        AgentNameCatalogRespVO catalog = service.getNameCatalog();

        assertEquals(1, catalog.getOperators().size());
        assertEquals("LIKE", catalog.getOperators().get(0).getOperator());
        assertEquals("包含", catalog.getOperators().get(0).getLabel());
    }

    @Test
    @DisplayName("四个数据源全空 → 各字段是空集合/空 map，绝不给 null（前端不必逐处判空）")
    void testEmptyCatalogHasNoNulls() {
        AgentNameCatalogRespVO catalog = service.getNameCatalog();

        assertNotNull(catalog.getTools());
        assertNotNull(catalog.getWidgetLabels());
        assertNotNull(catalog.getOperators());
        assertNotNull(catalog.getWorkflows());
        assertTrue(catalog.getWidgetLabels().isEmpty());
        assertTrue(catalog.getWorkflows().isEmpty());
        // 注册表里有两个测试工具，故 tools 不为空 —— 这条断言钉的是「内置工具不依赖任何数据源」
        assertEquals(2, catalog.getTools().size());
    }

    @Test
    @DisplayName("🔴 降险覆盖只改生效值：declared* 三件套钉在代码声明上，riskOverridden 才为 true")
    void testRiskOverrideDoesNotMoveDeclaredFields() {
        // 这一条钉的是 D4-22 的真实危害：运行期各道闸读 getDeclaredRisk()，降险改的是 getRisk()。
        // 目录若只下发生效值，设计器就会把降过险的工具画成「无需确认」（READ_ONLY 连色点都不画），
        // 用户照着编排、点发布才被 V3 撞回来 —— 界面主动指了反方向。
        TenantContextHolder.setTenantId(TENANT);
        toolRegistry.refreshTenantOverrides(TENANT, Map.of("datamodel_create",
                new AgentToolRegistry.ToolOverride("建表（改过名）", null, null,
                        RiskLevel.READ_ONLY, null, null, null)));

        ToolItem item = tool(service.getNameCatalog(), "datamodel_create");

        // 生效值跟着覆盖走（管理员要能看出自己那次维护改到了什么）
        assertEquals("READ_ONLY", item.getRisk());
        assertEquals("只读", item.getRiskDescription());
        assertFalse(item.getRequireConfirm());
        assertEquals("建表（改过名）", item.getDisplayName());
        assertTrue(item.getOverridden());
        // 🔴 声明值一动不动 —— 前端画点、写「需人工确认」、过滤「只读可用」只能读这一组
        assertEquals("IRREVERSIBLE", item.getDeclaredRisk());
        assertEquals("不可逆", item.getDeclaredRiskDescription());
        assertTrue(item.getDeclaredRequireConfirm());
        assertTrue(item.getRiskOverridden(), "生效 ≠ 声明，必须把这个差异标出来");
    }

    @Test
    @DisplayName("🔴 只改展示名（不动风险）→ riskOverridden 仍为 false：别把「维护过」误报成「降过险」")
    void testRenameOnlyDoesNotFlagRiskOverridden() {
        // 若拿 isOverridden()（= 有没有覆盖行）当 riskOverridden 用，改个中文名就会让设计器
        // 平白多出一句「风险标注已被在线维护成…」的注解，且说的还是生效值等于声明值这种废话。
        TenantContextHolder.setTenantId(TENANT);
        toolRegistry.refreshTenantOverrides(TENANT, Map.of("datamodel_create",
                new AgentToolRegistry.ToolOverride("建表（改过名）", null, null, null, null, null, null)));

        ToolItem item = tool(service.getNameCatalog(), "datamodel_create");

        assertTrue(item.getOverridden());
        assertFalse(item.getRiskOverridden());
        assertEquals("IRREVERSIBLE", item.getRisk());
        assertEquals("IRREVERSIBLE", item.getDeclaredRisk());
    }

    @Test
    @DisplayName("未维护的内置工具 + 自定义工具 → 声明值与生效值恒等，riskOverridden=false")
    void testDeclaredEqualsEffectiveWithoutOverride() {
        // 自定义工具那一半尤其要紧：agent_tool_def 那行【就是】它的声明，没有第二个来源可以分叉。
        // 若 declared* 留 null，前端「预测闸门行为」的地方一律读 declared*，
        // 于是自定义工具在设计器里一个色点都不画、也永远进不了「只读可用」那一档 ——
        // 那是把内置工具的病换成自定义工具的病。
        when(toolDefService.getToolDefListIncludingDisabled())
                .thenReturn(List.of(customTool("sales_daily_report", "销售日报", "报表", "MEDIUM")));

        AgentNameCatalogRespVO catalog = service.getNameCatalog();

        ToolItem untouched = tool(catalog, "datamodel_list");
        assertEquals("READ_ONLY", untouched.getRisk());
        assertEquals("READ_ONLY", untouched.getDeclaredRisk());
        assertEquals(untouched.getRequireConfirm(), untouched.getDeclaredRequireConfirm());
        assertFalse(untouched.getRiskOverridden());

        ToolItem custom = tool(catalog, "sales_daily_report");
        assertEquals("MEDIUM", custom.getRisk());
        assertEquals("MEDIUM", custom.getDeclaredRisk());
        assertEquals("中风险", custom.getDeclaredRiskDescription());
        assertTrue(custom.getDeclaredRequireConfirm());
        assertFalse(custom.getRiskOverridden());
    }

    private AgentWorkflowDefDO publishedDef(String code, String name, Integer version) {
        AgentWorkflowDefDO def = new AgentWorkflowDefDO();
        def.setCode(code);
        def.setName(name);
        def.setVersion(version);
        return def;
    }

    private FormDesignApi.WidgetSimpleRespDTO widget(String type, String label, boolean supported) {
        FormDesignApi.WidgetSimpleRespDTO dto = new FormDesignApi.WidgetSimpleRespDTO();
        dto.setType(type);
        dto.setLabel(label);
        dto.setSupported(supported);
        return dto;
    }

}
