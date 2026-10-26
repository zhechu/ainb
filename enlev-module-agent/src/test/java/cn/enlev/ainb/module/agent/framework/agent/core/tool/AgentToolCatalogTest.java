package cn.enlev.ainb.module.agent.framework.agent.core.tool;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.enums.ToolSourceEnum;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentToolCatalog} 的单元测试（B2）
 *
 * <p>本类钉的是<b>合并视图本身的口径</b>，不是各道闸的行为 —— 闸的行为由
 * {@code AgentDefServiceTest}（发布期）、{@code AgentChatToolLoopTest}（聊天工具循环运行期）、
 * {@code AgentLoopGraphBuilderTest}（agent_loop 编译期 + 运行期）三处各自钉住。
 * 分工是这样的：那三处只能断言「拒了 / 放了」，而<b>它们读的那一格是不是声明值</b>，
 * 取决于本类有没有把 declared 与 effective 分开放 —— 所以两边都得有，缺一边就有盲区。
 *
 * <h3>🔴 头号断言：两格绝不合并</h3>
 * {@code declaredRisk} 与 {@code effectiveRisk} 看着冗余，而把它们合并成一格正是
 * <b>本类唯一能造成的安全事故</b>：一行 {@code agent_tool_override} 就能把 IRREVERSIBLE 洗成
 * READ_ONLY，四道无人值守的只读闸会同时瞎掉，而界面上工具照样标着写操作、
 * 审计日志里也照样是写操作 —— 全程静默（D11）。
 * 故 {@link #resolve_builtin_declaredAndEffective_stayApart_downgraded()} 与
 * {@link #resolve_builtin_declaredAndEffective_stayApart_upgraded()} 是双向闸：
 * 缺任一向，把两格合并也能让另一向变绿。
 *
 * <p><b>变异探针</b>：把 {@code AgentToolCatalog#toView(ToolDefinition)} 里给
 * {@code declaredRisk} 那一格传的值改成 {@code effective}（或反之），上面两条必须变红，
 * 且红在 {@code declaredRequireConfirm()} 那行。
 *
 * @author 走进新时代
 */
class AgentToolCatalogTest extends BaseMockitoUnitTest {

    @Mock
    private AgentToolRegistry toolRegistry;
    @Mock
    private AgentToolDefService toolDefService;

    private AgentToolCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new AgentToolCatalog(toolRegistry, toolDefService);
    }

    // ==================== 桩 ====================

    /**
     * 桩一个内置工具，<b>声明值与生效值分别给</b>
     *
     * <p>11 个 getter 全桩满：{@code toView} 每一个都会读。少桩一个 STRICT_STUBS 也不会报，
     * 但那个字段就会以 mock 默认值（null / false）混进断言里，
     * 届时分不清是「桩漏了」还是「映射错了」—— 那正是本类要区分的东西。
     */
    private AgentToolRegistry.ToolDefinition builtin(String name, RiskLevel declared, RiskLevel effective) {
        AgentToolRegistry.ToolDefinition def = mock(AgentToolRegistry.ToolDefinition.class);
        when(def.getName()).thenReturn(name);
        when(def.getDeclaredRisk()).thenReturn(declared);
        when(def.getRisk()).thenReturn(effective);
        when(def.isEnabled()).thenReturn(true);
        when(def.getDisplayName()).thenReturn("列出数据模型");
        when(def.getDescription()).thenReturn("查询当前租户已有的数据模型");
        when(def.getParamsSchema()).thenReturn("keyword（可选，string）：名称关键字");
        when(def.getGroupName()).thenReturn("数据模型");
        when(def.getPermissions()).thenReturn(List.of("infra:data-model:query"));
        when(def.isTruncateResult()).thenReturn(false);
        when(def.isOverridden()).thenReturn(declared != effective);
        return def;
    }

    /**
     * 桩一个内置工具并挂到 {@code registry.get(name)} 上
     *
     * <p>🔴 必须分两步、不能写成 {@code when(toolRegistry.get(x)).thenReturn(builtin(x, ...))}：
     * {@code thenReturn} 的实参在外层 {@code when(...)} 尚未收尾时求值，而 {@link #builtin} 自己
     * 正在打十一个桩 —— 嵌套打桩，Mockito 直接抛 {@code UnfinishedStubbingException}。
     * {@link #custom} 系列不打桩（只是 new 一个 DO），所以那几个可以放心嵌套。
     */
    private AgentToolRegistry.ToolDefinition stubBuiltinGet(String name, RiskLevel declared, RiskLevel effective) {
        AgentToolRegistry.ToolDefinition def = builtin(name, declared, effective);
        when(toolRegistry.get(name)).thenReturn(def);
        return def;
    }

    /** 同上：{@code registry.list()} 版本 */
    private void stubBuiltinList(AgentToolRegistry.ToolDefinition... defs) {
        when(toolRegistry.list()).thenReturn(List.of(defs));
    }

    private AgentToolDefDO custom(String key, String source, String riskLevel, Boolean enabled) {
        AgentToolDefDO row = new AgentToolDefDO();
        row.setToolKey(key);
        row.setSource(source);
        row.setRiskLevel(riskLevel);
        row.setEnabled(enabled);
        row.setDisplayName("销售日报");
        row.setDescription("按天统计销售额");
        row.setParamsSchema("startDate（必填，date）：起始日期");
        row.setGroupName("报表");
        row.setPermissions("infra:data-interface:invoke,report:sales:query");
        return row;
    }

    private AgentToolDefDO dataInterface(String key, String riskLevel) {
        return custom(key, ToolSourceEnum.DATA_INTERFACE.getSource(), riskLevel, true);
    }

    private void stubCustom(AgentToolDefDO... rows) {
        when(toolDefService.getToolDefListIncludingDisabled()).thenReturn(Arrays.asList(rows));
    }

    private static AgentToolCatalog.ToolView named(List<AgentToolCatalog.ToolView> views, String name) {
        return views.stream().filter(v -> name.equals(v.name())).findFirst().orElse(null);
    }

    private static List<String> names(List<AgentToolCatalog.ToolView> views) {
        return views.stream().map(AgentToolCatalog.ToolView::name).toList();
    }

    // ==================== 🔴 声明值与生效值不许串（D11） ====================

    @Test
    @DisplayName("🔴 内置工具被覆盖行压成只读：declared 仍是 IRREVERSIBLE，两格各归各位")
    void resolve_builtin_declaredAndEffective_stayApart_downgraded() {
        stubBuiltinGet("datamodel_create", RiskLevel.IRREVERSIBLE, RiskLevel.READ_ONLY);

        AgentToolCatalog.ToolView view = catalog.resolve("datamodel_create");

        assertNotNull(view);
        assertTrue(view.builtin());
        assertEquals(AgentToolCatalog.SOURCE_BUILTIN, view.source());
        assertTrue(view.executable(), "内置工具恒可执行");
        assertSame(RiskLevel.IRREVERSIBLE, view.declaredRisk(), "闸读的就是这一格，绝不能被覆盖行推动");
        assertSame(RiskLevel.READ_ONLY, view.effectiveRisk(), "快照端点读的是这一格，也得如实");
        assertTrue(view.declaredRequireConfirm(), "🔴 声明口径必须仍要求确认 —— 四道无人值守闸的判据");
        assertFalse(view.requireConfirm(), "生效口径确实不要求确认（这就是覆盖行的效果，如实呈现）");
        assertTrue(view.riskOverridden(), "两格不等 = 风险标注被在线维护改过，界面上要能看出来");
        assertFalse(view.truncateResult(), "内置工具的白名单值原样搬过来，不许被合并逻辑改掉");
        assertEquals(List.of("infra:data-model:query"), view.permissions());
    }

    @Test
    @DisplayName("🔴 反向：内置工具被覆盖行调高风险，declared 仍是 READ_ONLY（缺一向则合并两格也能变绿）")
    void resolve_builtin_declaredAndEffective_stayApart_upgraded() {
        stubBuiltinGet("datamodel_list", RiskLevel.READ_ONLY, RiskLevel.MEDIUM);

        AgentToolCatalog.ToolView view = catalog.resolve("datamodel_list");

        assertNotNull(view);
        assertSame(RiskLevel.READ_ONLY, view.declaredRisk());
        assertSame(RiskLevel.MEDIUM, view.effectiveRisk());
        assertFalse(view.declaredRequireConfirm(), "本质只读的工具被租户加了保险，不该因此被闸拦下");
        assertTrue(view.requireConfirm());
        assertTrue(view.riskOverridden());
    }

    @Test
    @DisplayName("内置工具 declaredRisk 为 null：按 HIGH 兜（宁可多说风险，不能把写操作标成只读）")
    void resolve_builtin_nullDeclaredRisk_fallsBackToHigh() {
        stubBuiltinGet("weird", null, null);

        AgentToolCatalog.ToolView view = catalog.resolve("weird");

        assertSame(RiskLevel.HIGH, view.declaredRisk(), "与 WorkflowDslValidator.ToolResolver 同方向兜底");
        assertSame(RiskLevel.HIGH, view.effectiveRisk(), "生效值也 null 时回落声明值，不许留下 null 格");
        assertTrue(view.declaredRequireConfirm());
    }

    @Test
    @DisplayName("内置工具只有 effectiveRisk 为 null：回落到 declaredRisk，而不是回落成 HIGH")
    void resolve_builtin_nullEffectiveRisk_fallsBackToDeclared() {
        stubBuiltinGet("datamodel_list", RiskLevel.READ_ONLY, null);

        AgentToolCatalog.ToolView view = catalog.resolve("datamodel_list");

        assertSame(RiskLevel.READ_ONLY, view.declaredRisk());
        assertSame(RiskLevel.READ_ONLY, view.effectiveRisk(), "本来就没被覆盖过，回落声明值");
        assertFalse(view.riskOverridden());
    }

    // ==================== 自定义工具的映射 ====================

    @Test
    @DisplayName("自定义只读数据接口工具：能解析、可执行、风险两格恒等（它没有覆盖表）")
    void resolve_customReadOnlyDataInterface_mapsAllFields() {
        stubCustom(dataInterface("sales_daily_report", "READ_ONLY"));

        AgentToolCatalog.ToolView view = catalog.resolve("sales_daily_report");

        assertNotNull(view, "🔴 B2 的本体：此前这里返回 null，自定义工具压根绑不到智能体");
        assertEquals("sales_daily_report", view.name());
        assertFalse(view.builtin());
        assertEquals(ToolSourceEnum.DATA_INTERFACE.getSource(), view.source());
        assertTrue(view.executable(), "DATA_INTERFACE 是当前唯一可执行的来源");
        assertTrue(view.enabled());
        assertSame(RiskLevel.READ_ONLY, view.declaredRisk());
        assertSame(RiskLevel.READ_ONLY, view.effectiveRisk(),
                "自定义工具没有 agent_tool_override，risk_level 那一列<b>就是</b>它的声明，两格必然相等");
        assertFalse(view.riskOverridden());
        assertFalse(view.declaredRequireConfirm(), "只读 → 可进无人值守的聊天工具循环 / agent_loop");
        assertFalse(view.overridden(), "整行就是它的定义，谈不上「被在线维护改过」");
        assertTrue(view.truncateResult(),
                "🔴 恒 true：@AgentTool(truncateResult=false) 是代码里的白名单，数据接口回多大平台无从静态断言");
        assertEquals("销售日报", view.displayName());
        assertEquals("按天统计销售额", view.description());
        assertEquals("startDate（必填，date）：起始日期", view.paramsSchema());
        assertEquals("报表", view.groupName());
        assertEquals(List.of("infra:data-interface:invoke", "report:sales:query"), view.permissions());
    }

    @Test
    @DisplayName("自定义写工具：declaredRequireConfirm 为 true —— 闸据此拒，绝不因「是自定义的」而放宽")
    void resolve_customWriteTool_declaredRequireConfirm() {
        stubCustom(dataInterface("sales_reset", "IRREVERSIBLE"));

        AgentToolCatalog.ToolView view = catalog.resolve("sales_reset");

        assertNotNull(view);
        assertSame(RiskLevel.IRREVERSIBLE, view.declaredRisk());
        assertTrue(view.declaredRequireConfirm());
        assertTrue(view.requireConfirm());
    }

    @Test
    @DisplayName("🔴 source 是预留位（FORM/SCRIPT）：executable=false —— 绕过登记校验塞进来的行要在编译期就拦下")
    void resolve_customReservedSource_notExecutable() {
        stubCustom(custom("form_writer", ToolSourceEnum.FORM.getSource(), "READ_ONLY", true));

        AgentToolCatalog.ToolView view = catalog.resolve("form_writer");

        assertNotNull(view, "认得出来（否则会退化成「未登记」，把管理员引向错误的修法：去登记一个已存在的东西）");
        assertFalse(view.executable(), "但不可执行：buildFunction 遇到 FORM 抛 AGENT_TOOL_SOURCE_UNSUPPORTED");
        assertEquals(ToolSourceEnum.FORM.getSource(), view.source());
    }

    @Test
    @DisplayName("riskLevel 缺失或非法：按 HIGH 兜，与 RiskLevel.parseOrHigh 逐字同口径")
    void resolve_customBlankOrInvalidRisk_fallsBackToHigh() {
        stubCustom(dataInterface("risk_blank", null), dataInterface("risk_garbage", "SUPER_SAFE"));

        assertSame(RiskLevel.HIGH, catalog.resolve("risk_blank").declaredRisk(),
                "会改数据的接口不该因为一列漏填就被当成只读放进无人值守的循环");
        assertSame(RiskLevel.HIGH, catalog.resolve("risk_garbage").declaredRisk());
    }

    @Test
    @DisplayName("enabled 为 null 按停用判：与网关的 Boolean.TRUE.equals 同口径，两边不能各说一套")
    void resolve_customNullEnabled_treatedAsDisabled() {
        stubCustom(custom("null_enabled", ToolSourceEnum.DATA_INTERFACE.getSource(), "READ_ONLY", null));

        AgentToolCatalog.ToolView view = catalog.resolve("null_enabled");

        assertNotNull(view, "停用的工具仍要解析得出来 —— 「消失」会让存量绑定退化成裸机器键（D4-27）");
        assertFalse(view.enabled());
    }

    @Test
    @DisplayName("displayName 留空回落到工具键；groupName 为 null 回落成空串（不许把 null 拼进 prompt）")
    void resolve_customBlankDisplayName_fallsBackToKey() {
        AgentToolDefDO row = dataInterface("sales_daily_report", "READ_ONLY");
        row.setDisplayName("  ");
        row.setGroupName(null);
        stubCustom(row);

        AgentToolCatalog.ToolView view = catalog.resolve("sales_daily_report");

        assertEquals("sales_daily_report", view.displayName());
        assertEquals("", view.groupName());
    }

    @Test
    @DisplayName("permissions 串：trim、丢空、去重 —— 与 AgentToolDefServiceImpl#splitPermissions 同语义")
    void resolve_customPermissions_trimmedDeduped() {
        AgentToolDefDO row = dataInterface("sales_daily_report", "READ_ONLY");
        row.setPermissions(" a:b , ,a:b, c:d ");
        stubCustom(row);

        assertEquals(List.of("a:b", "c:d"), catalog.resolve("sales_daily_report").permissions());
    }

    @Test
    @DisplayName("permissions 为空：空列表（= 登录即可调用），而不是 null")
    void resolve_customBlankPermissions_emptyList() {
        AgentToolDefDO row = dataInterface("sales_daily_report", "READ_ONLY");
        row.setPermissions(null);
        stubCustom(row);

        assertEquals(List.of(), catalog.resolve("sales_daily_report").permissions());
    }

    // ==================== registry-first 与查不到 ====================

    @Test
    @DisplayName("🔴 registry-first：内置工具命中时压根不查自定义目录（与网关/执行器/名称目录同优先级）")
    void resolve_registryFirst_shortCircuitsBeforeCustomTable() {
        stubBuiltinGet("datamodel_create", RiskLevel.IRREVERSIBLE, RiskLevel.IRREVERSIBLE);

        AgentToolCatalog.ToolView view = catalog.resolve("datamodel_create");

        assertTrue(view.builtin());
        assertEquals("列出数据模型", view.displayName());
        assertSame(RiskLevel.IRREVERSIBLE, view.declaredRisk());
        // 🔴 这条比「结果对了」更强：即便有人绕过 validateForSave 第 1 步往 agent_tool_def 塞了一行
        //    同名的「只读假建表工具」，resolve 也<b>根本不会去看它</b> —— 短路发生在读库之前，
        //    不存在「先读到假行、再被内置行覆盖」的时序窗口。
        //    两边都被加载时谁赢，由 list_dropsCustomRowCollidingWithBuiltin 钉住。
        verify(toolDefService, never()).getToolDefListIncludingDisabled();
    }

    @Test
    @DisplayName("两类都查不到：返回 null，由调用点各自决定说「未登记」还是「工具不存在」")
    void resolve_unknownKey_returnsNull() {
        stubCustom(dataInterface("sales_daily_report", "READ_ONLY"));

        assertNull(catalog.resolve("ghost"));
    }

    @Test
    @DisplayName("键为空：直接 null，连库都不查（每轮对话都要渲染 prompt，白查一次不值）")
    void resolve_blankKey_returnsNullWithoutTouchingDb() {
        assertNull(catalog.resolve(null));
        assertNull(catalog.resolve(""));
        assertNull(catalog.resolve("   "));

        verify(toolDefService, never()).getToolDefListIncludingDisabled();
    }

    @Test
    @DisplayName("🔴 读自定义目录失败：fail-soft 返回 null，绝不把异常抛进正在进行的对话")
    void resolve_dbFailure_failSoftReturnsNull() {
        when(toolDefService.getToolDefListIncludingDisabled()).thenThrow(new RuntimeException("db down"));

        assertNull(catalog.resolve("sales_daily_report"),
                "查不到就是 null，闸照样拒 —— fail-close 由调用点保证，这里吞异常不构成放行");
    }

    // ==================== list()：合并清单 ====================

    @Test
    @DisplayName("list()：内置全量在前、自定义全量在后，含停用行")
    void list_mergesBothSources_builtinFirst_includesDisabled() {
        stubBuiltinList(
                builtin("datamodel_list", RiskLevel.READ_ONLY, RiskLevel.READ_ONLY),
                builtin("datamodel_create", RiskLevel.IRREVERSIBLE, RiskLevel.IRREVERSIBLE));
        AgentToolDefDO off = dataInterface("sales_daily_report", "READ_ONLY");
        off.setEnabled(false);
        stubCustom(off, dataInterface("sales_hourly_report", "MEDIUM"));

        List<AgentToolCatalog.ToolView> views = catalog.list();

        assertEquals(List.of("datamodel_list", "datamodel_create", "sales_daily_report", "sales_hourly_report"),
                names(views), "内置按登记顺序在前，自定义在后");
        AgentToolCatalog.ToolView disabled = named(views, "sales_daily_report");
        assertNotNull(disabled, "🔴 停用的自定义工具必须仍在清单里，靠 enabled 让前端置灰（D4-25/D4-27）");
        assertFalse(disabled.enabled());
    }

    @Test
    @DisplayName("list()：与内置重名的自定义行丢弃 —— 同一个键在清单里只出现一次")
    void list_dropsCustomRowCollidingWithBuiltin() {
        stubBuiltinList(builtin("datamodel_create", RiskLevel.IRREVERSIBLE, RiskLevel.IRREVERSIBLE));
        stubCustom(dataInterface("datamodel_create", "READ_ONLY"),
                dataInterface("sales_daily_report", "READ_ONLY"));

        List<AgentToolCatalog.ToolView> views = catalog.list();

        assertEquals(2, views.size(), "重名那行被丢掉，不是覆盖也不是并列");
        assertTrue(named(views, "datamodel_create").builtin());
        assertNotNull(named(views, "sales_daily_report"));
    }

    @Test
    @DisplayName("list()：跳过 null 行与空键行 —— 一行脏数据不该让整屏工具清单 500")
    void list_skipsNullAndBlankKeyRows() {
        stubBuiltinList(builtin("datamodel_list", RiskLevel.READ_ONLY, RiskLevel.READ_ONLY));
        when(toolDefService.getToolDefListIncludingDisabled()).thenReturn(Arrays.asList(
                null, dataInterface("   ", "READ_ONLY"), dataInterface("ok_tool", "READ_ONLY")));

        assertEquals(List.of("datamodel_list", "ok_tool"), names(catalog.list()));
    }

    @Test
    @DisplayName("list()：读自定义目录失败时只列内置 —— 快照端点降级可用，不整屏报错")
    void list_dbFailure_returnsBuiltinsOnly() {
        stubBuiltinList(builtin("datamodel_list", RiskLevel.READ_ONLY, RiskLevel.READ_ONLY));
        when(toolDefService.getToolDefListIncludingDisabled()).thenThrow(new RuntimeException("db down"));

        List<AgentToolCatalog.ToolView> views = catalog.list();

        assertEquals(1, views.size());
        assertTrue(views.get(0).builtin());
    }

    @Test
    @DisplayName("list() 里的内置工具同样两格分明：快照端点据此同时下发生效值与声明值（D4-22）")
    void list_builtinViewsCarryBothRiskColumns() {
        stubBuiltinList(builtin("datamodel_create", RiskLevel.IRREVERSIBLE, RiskLevel.READ_ONLY));
        stubCustom();

        AgentToolCatalog.ToolView view = named(catalog.list(), "datamodel_create");

        assertNotNull(view);
        assertSame(RiskLevel.IRREVERSIBLE, view.declaredRisk());
        assertSame(RiskLevel.READ_ONLY, view.effectiveRisk());
        assertTrue(view.riskOverridden());
        assertTrue(view.overridden());
    }

    @Test
    @DisplayName("🔴 刻意不加缓存：同一个键连问两次就查两次库 —— 单例 bean 上的成员缓存会跨租户串数据")
    void resolve_isNotCached() {
        stubCustom(dataInterface("sales_daily_report", "READ_ONLY"));

        assertNotNull(catalog.resolve("sales_daily_report"));
        assertNotNull(catalog.resolve("sales_daily_report"));

        verify(toolDefService, times(2)).getToolDefListIncludingDisabled();
    }

}
